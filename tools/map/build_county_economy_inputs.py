#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""縣 경제 입력(호구·전답·시장) 원장 빌더 — 재설계 §8.1, 이슈 #777.

입력(전부 커밋된 파일):
  data/map/han-tiles.json                       지형 격자·省·縣 관할·郡國
  infra/src/main/resources/map/han-world-v3.json 城 등급·연결
  data/curated/han/administrative-units.json    郡國志 郡 戶數(build_han_world.junguozhi_groups)
  data/curated/han/county-economy-params-v1.json 가중치(EXPLORATORY)
  data/curated/han/korea-economic-evidence-v1.json 東夷傳 권역 호수·생업·교역
출력: data/curated/han/county-economy-inputs-v1.json
`--check` 는 커밋본이 재생성 결과와 같은지 본다.
"""
from __future__ import annotations

import argparse
import collections
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools" / "scenario"))
import build_han_world  # noqa: E402

TILES = ROOT / "data/map/han-tiles.json"
WORLD = ROOT / "infra/src/main/resources/map/han-world-v3.json"
PARAMS = ROOT / "data/curated/han/county-economy-params-v1.json"
OUTPUT = ROOT / "data/curated/han/county-economy-inputs-v1.json"
WATER = {"SEA", "LAKE", "OUT_OF_SCOPE"}
WET = {"RIVER", "LAKE", "SEA"}


def largest_remainder(total: int, weights: list[float]) -> list[int]:
    s = sum(weights)
    if s <= 0:
        raise ValueError("weights must sum to a positive value")
    raw = [total * w / s for w in weights]
    out = [int(x) for x in raw]
    order = sorted(range(len(raw)), key=lambda i: (-(raw[i] - out[i]), i))
    for i in order[: total - sum(out)]:
        out[i] += 1
    return out


def build(tiles: dict, world: dict, params: dict, households: dict, historical_economy: dict | None = None) -> dict:
    area_decision = params.get("areaInputDecision")
    if not isinstance(area_decision, dict) or area_decision.get("geometrySource") != "COMMITTED_HAN_TILES" or area_decision.get("provinceAreaRebalanceInput") != "NOT_USED":
        raise ValueError("county economy area input must follow the reviewed geography-first decision")
    legend = {int(k): v for k, v in tiles["_meta"]["terrainLegend"].items()}
    cols = tiles["_meta"]["cols"]
    terrain = "".join(tiles["terrain"])
    owner: list[int] = []
    for prov, count in tiles["owner"]:
        owner.extend([prov] * count)
    if len(owner) != len(terrain):
        raise ValueError("owner grid and terrain grid differ in size")
    provinces = tiles["provinceRecords"]
    comp: dict[str, collections.Counter] = collections.defaultdict(collections.Counter)
    wet_adj: collections.Counter = collections.Counter()
    n = len(owner)
    for i, prov in enumerate(owner):
        if prov < 0:
            continue
        jid = provinces[prov].get("jurisdictionId")
        if not jid:
            continue
        name = legend[int(terrain[i])]
        comp[jid][name] += 1
        if name in WATER:
            continue
        x = i % cols
        for j in (i - cols, i + cols, i - 1 if x else -1, i + 1 if x + 1 < cols else -1):
            if 0 <= j < n and legend[int(terrain[j])] in WET:
                wet_adj[jid] += 1
                break
    city_by_jur = {}
    for city in world["cities"]:
        idx = city.get("spatialProvinceIndex")
        if idx is None:
            continue
        jid = provinces[idx].get("jurisdictionId")
        if jid in city_by_jur:
            raise ValueError(f"jurisdiction {jid} has more than one city")
        city_by_jur[jid] = city
    tw = params["terrainWeights"]
    prior = params["seatPriorByCityLevel"]
    floor = params["minTerrainScore"]
    commanderies = {c["id"]: c for c in tiles["commanderyRecords"]}
    seats = {c.get("seatJurisdictionId") for c in commanderies.values()}
    rows = {}
    for jur in tiles["jurisdictionRecords"]:
        jid = jur["id"]
        c = comp[jid]
        city = city_by_jur.get(jid)
        score = sum(c[k] * w for k, w in tw.items())
        rows[jid] = {
            "jurisdictionId": jid,
            "nameCh": jur["nameCh"],
            "commanderyId": jur["commanderyId"],
            "commanderyNameCh": commanderies[jur["commanderyId"]]["nameCh"],
            "cityId": city["id"] if city else None,
            "cityLevel": city["level"] if city else None,
            "isCommanderySeat": jid in seats,
            "landCells": sum(v for k, v in c.items() if k not in WATER),
            "terrainCells": {k: c[k] for k in sorted(c)},
            "arableCells": c["PLAIN"] + c["BASIN"],
            "fieldCapacityCells": c["PLAIN"] + c["BASIN"],
            "terrainScore": round(score, 2),
            "wetAdjacentCells": wet_adj[jid],
            "connections": len(city.get("connections", [])) if city else 0,
            "marketCapacityCells": min(
                sum(v for k, v in c.items() if k not in WATER),
                wet_adj[jid] + (len(city.get("connections", [])) if city else 0),
            ),
            "households": None,
            "householdsBasis": "NO_SOURCE_HOUSEHOLDS",
        }
    fewer = 0
    for cid, com in commanderies.items():
        stat = households.get(com["nameCh"])
        members = [rows[j] for j in com.get("jurisdictionIds", []) if j in rows]
        if not stat or not stat.get("households") or not members:
            continue
        if len(members) < int(stat.get("counties") or 0):
            fewer += 1
        weights = [
            prior.get(str(r["cityLevel"]), 1.0) * max(r["terrainScore"], floor) for r in members
        ]
        # A documented 郡 total should leave at least one household in every
        # occupied 縣, including a tiny desert outpost after boundary edits.
        total = int(stat["households"])
        floor_per_county = 1 if total >= len(members) else 0
        shares = largest_remainder(total - floor_per_county * len(members), weights)
        for r, h in zip(members, shares):
            r["households"] = h + floor_per_county
            r["householdsBasis"] = "JUNGUOZHI_COMMANDERY_SPLIT"
    for jid, city in city_by_jur.items():
        allocation = city.get("meta", {}).get("economyBasis")
        if allocation:
            rows[jid]["gameEconomy"] = dict(allocation, initial=city["initial"], capacity=city["max"])
            if rows[jid]["households"] is not None:
                rows[jid]["householdsBasis"] = "GAME_DESIGN_SHARE_OF_JUNGUOZHI_TOTAL_NOT_LOCAL_CENSUS"
    historical_groups = (historical_economy or {}).get("groups", [])
    seen = set()
    for group in historical_groups:
        if group["id"] in seen:
            raise ValueError(f"duplicate historical economy group: {group['id']}")
        seen.add(group["id"])
        for jid in group["jurisdictionIds"]:
            if jid not in rows:
                raise ValueError(f"unknown historical economy jurisdiction: {jid}")
            rows[jid].setdefault("historicalEconomyRefs", []).append(group["id"])
    ordered = [rows[k] for k in sorted(rows)]
    missing_inputs = []
    for row in ordered:
        states = []
        if row["households"] is None:
            states.append("UNSOURCED_HOUSEHOLDS")
        if row["fieldCapacityCells"] == 0:
            states.append("GEOMETRIC_ZERO_FIELD_CAPACITY")
        if row["marketCapacityCells"] == 0:
            states.append("GEOMETRIC_ZERO_MARKET_CAPACITY")
        if states:
            missing_inputs.append({"jurisdictionId": row["jurisdictionId"], "states": states})
    return {
        "schemaVersion": 1,
        "paramsStatus": params["status"],
        "areaInputDecision": area_decision,
        "source": "後漢書 郡國志 郡 戶數 × 治所 위계·지형 가중; 三國志 東夷傳 권역 경제는 historicalEconomy로 별도 보존",
        "counts": {
            "jurisdictions": len(ordered),
            "withHouseholds": sum(1 for r in ordered if r["households"] is not None),
            "zeroArable": sum(1 for r in ordered if r["arableCells"] == 0),
            "zeroFieldCapacity": sum(r["fieldCapacityCells"] == 0 for r in ordered),
            "zeroMarketCapacity": sum(r["marketCapacityCells"] == 0 for r in ordered),
            "missingInputRows": len(missing_inputs),
            "commanderiesWithFewerCountiesThanSource": fewer,
        },
        "limitations": [
            "보존 단위는 永和五年(140) 郡이다. 타일의 郡이 그 뒤 분할돼 縣이 줄었으면(南陽郡 37城→29縣, 떨어져 나간 "
            "襄陽·章陵·南鄉郡 縣은 NO_SOURCE_HOUSEHOLDS) 郡 戶數 전부가 남은 縣에 몰린다 — "
            "타일에 縣이 빠져서 적은 郡(汝南郡 37→26 등)도 같은 쏠림을 겪는다. counts.commanderiesWithFewerCountiesThanSource 는 원인을 가리지 않고 타일 縣 수 < 郡國志 城數 인 郡을 센 값이다.",
        ],
        "fieldNotes": {
            "landCells": "WATER 가 아닌 칸. RIVER 칸을 포함한다.",
            "wetAdjacentCells": "상하좌우에 물기 있는(WET = RIVER·LAKE·SEA) 이웃이 하나 이상인 뭍 칸의 수. RIVER 칸도 뭍 칸으로 센다.",
            "fieldCapacityCells": "전답 상한의 지형 입력. PLAIN·BASIN 소유 칸 수이며 실제 경작지 면적이나 생산량은 아니다.",
            "marketCapacityCells": "시장 상한의 지형·경로 입력. min(뭍 소유 칸, 물 인접 칸 + 城 경로 연결 수). 실제 시장 수나 역사 도로 수는 아니다.",
        },
        "missingInputs": missing_inputs,
        "historicalEconomy": historical_economy,
        "jurisdictions": ordered,
    }


def _rel(path: Path) -> str:
    try:
        return str(path.relative_to(ROOT))
    except ValueError:
        return str(path)


def render(doc: dict) -> str:
    return json.dumps(doc, ensure_ascii=False, indent=1, sort_keys=False) + "\n"


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--check", action="store_true")
    args = ap.parse_args(argv)
    doc = build(
        json.loads(TILES.read_text(encoding="utf-8")),
        json.loads(WORLD.read_text(encoding="utf-8")),
        json.loads(PARAMS.read_text(encoding="utf-8")),
        build_han_world.junguozhi_groups(),
        json.loads((ROOT / "data/curated/han/korea-economic-evidence-v1.json").read_text(encoding="utf-8")),
    )
    text = render(doc)
    if args.check:
        if not OUTPUT.exists() or OUTPUT.read_text(encoding="utf-8") != text:
            print(f"DRIFT: {_rel(OUTPUT)} is stale — rerun {Path(__file__).name}", file=sys.stderr)
            return 1
        print(f"OK {_rel(OUTPUT)} {doc['counts']}")
        return 0
    OUTPUT.write_text(text, encoding="utf-8")
    print(f"wrote {_rel(OUTPUT)} {doc['counts']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
