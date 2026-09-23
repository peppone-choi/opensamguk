#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""郡國志 縣 목록과 han-tiles 관할의 차집합 원장 — 이슈 #180.

이름 대조만 한다(판정 아님). 後漢書 郡國志 105 郡國의 縣 이름을 han-tiles `jurisdictionRecords`
와 맞춰, 縣마다 IN_OWN_COMMANDERY / IN_OTHER_COMMANDERY / ABSENT 로 분류한다. 개명·僑置·
220년 단면 차이는 가리지 않으므로 모든 행은 `review: UNREVIEWED_NAME_MATCH` 다.
사용자가 지도에서 빼라고 지정한 미해독 3행은 ABSENT 를 유지하고 별도 disposition 을 기록한다.

입력(커밋본): data/curated/han/administrative-units.json, data/map/han-tiles.json,
data/curated/han/han-name-simplification-v1.json(audit_county_coverage 의 정규화 규칙을 그대로 쓴다),
data/curated/han/gap-counties-v1.json(excludedUndeciphered 처분)
사람 판정 목록: data/curated/han/junguozhi-county-name-review-v1.json — 글자표로 접지 않은 한 글자 차이 후보(개명·잘림·이문)
출력: data/curated/han/junguozhi-county-gaps-v1.json   (`--check` = 재생성 대조)
"""
from __future__ import annotations

import argparse
import collections
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
UNITS = ROOT / "data/curated/han/administrative-units.json"
TILES = ROOT / "data/map/han-tiles.json"
WORLD = ROOT / "infra/src/main/resources/map/han-world-v3.json"
GAP_LEDGER = ROOT / "data/curated/han/gap-counties-v1.json"
OUTPUT = ROOT / "data/curated/han/junguozhi-county-gaps-v1.json"
sys.path.insert(0, str(Path(__file__).resolve().parent))
import audit_county_coverage  # noqa: E402 — 접미사·글자표 규칙의 정본. 따로 만들면 邑·道·國을 잘라 먹는다(2026-09-17 교차 비평)



def _near(a: str, b: str) -> bool:
    if len(a) == len(b) and len(a) >= 2:
        return sum(x != y for x, y in zip(a, b)) == 1
    return abs(len(a) - len(b)) == 1 and (a in b or b in a)


def build(units: dict, tiles: dict, world: dict, gap_ledger: dict | None = None) -> dict:
    if gap_ledger is None:
        gap_ledger = json.loads(GAP_LEDGER.read_text(encoding="utf-8"))
    excluded = {(row["commandery"], row["sourceName"]): row["reason"]
                for row in gap_ledger.get("excludedUndeciphered", [])}
    fold = audit_county_coverage.make_normalizer()
    fold_group = audit_county_coverage.make_normalizer(group=True)
    commandery_name = {c["id"]: c["nameCh"] for c in tiles["commanderyRecords"]}
    by_commandery: dict[str, dict[str, str]] = collections.defaultdict(dict)
    anywhere: dict[str, list[str]] = collections.defaultdict(list)
    for j in tiles["jurisdictionRecords"]:
        com = commandery_name[j["commanderyId"]]
        by_commandery[fold_group(com)][fold(j["nameCh"])] = j["id"]
        anywhere[fold(j["nameCh"])].append(com)
    # 城의 meta.junCh 는 郡國志 시점의 郡이다. 타일의 郡은 220년 단면이라 분할된 郡(襄陽·章陵…)의 縣이 남의 郡으로 보인다.
    by_source_jun: dict[str, dict[str, int]] = collections.defaultdict(dict)
    for city in world["cities"]:
        meta = city.get("meta") or {}
        if meta.get("junCh") and meta.get("nameCh"):
            by_source_jun[fold_group(meta["junCh"])][fold(meta["nameCh"])] = city["id"]
    groups = []
    totals = collections.Counter()
    for g in units["groups"]:
        own = by_commandery.get(fold_group(g["canonicalGroup"]), {})
        rows = []
        for u in g["units"]:
            key = fold(u["sourceName"])
            source_own = by_source_jun.get(fold_group(g["canonicalGroup"]), {})
            if key in own:
                status, extra = "IN_OWN_COMMANDERY", {"basis": "TILE_COMMANDERY", "jurisdictionId": own[key]}
            elif key in source_own:
                status, extra = "IN_OWN_COMMANDERY", {"basis": "CITY_SOURCE_JUN", "cityId": source_own[key],
                                                      "tileCommanderies": sorted(set(anywhere.get(key, [])))}
            elif key in anywhere:
                status, extra = "IN_OTHER_COMMANDERY", {"foundIn": sorted(set(anywhere[key]))}
            else:
                status, extra = "ABSENT", {}
                disposition = excluded.get((g["canonicalGroup"], u["sourceName"]))
                if disposition is not None:
                    extra["disposition"] = disposition
            rows.append({"ordinal": u["ordinal"], "sourceName": u["sourceName"], "status": status, **extra, "_key": key})
        # 부재 후보에 같은 郡의 「아직 안 맞은」 타일 縣 중 한 글자 차이 이름을 단다(異體字·개명 후보). 승격하지 않는다.
        taken = {r["_key"] for r in rows if r["status"] != "ABSENT"}
        free = sorted(set(own) - taken)
        # 한 타일 縣이 여러 부재 행에 달리면(汝南 慎阳 → 新陽·灌陽·細陽·鮦陽·愼陽) 그 후보는 어느 행의 것도 아니다.
        # 행마다 달지 않고 따로 모은다(#813).
        claims = collections.Counter(f for r in rows if r["status"] == "ABSENT" for f in free if _near(r["_key"], f))
        for r in rows:
            key = r.pop("_key")
            if r["status"] == "ABSENT":
                near = [f for f in free if _near(key, f)]
                unique = [f for f in near if claims[f] == 1]
                shared = [f for f in near if claims[f] > 1]
                if unique:
                    r["nearMatchInOwnCommandery"] = unique
                    totals["absentWithNearMatch"] += 1
                if shared:
                    r["ambiguousNearMatchInOwnCommandery"] = shared
                    totals["absentWithAmbiguousNearMatch"] += 1
            totals[r["status"]] += 1
        first = rows[0] if rows else None
        groups.append({
            "sourceVolume": g["sourceVolume"],
            "commandery": g["canonicalGroup"],
            "inTiles": fold_group(g["canonicalGroup"]) in by_commandery,
            "declaredCounties": len(rows),
            "absent": sum(1 for r in rows if r["status"] == "ABSENT"),
            "firstListedCounty": first and {"sourceName": first["sourceName"], "status": first["status"]},
            "counties": rows,
        })
    return {
        "schemaVersion": 1,
        "review": "UNREVIEWED_NAME_MATCH",
        "note": "이름 대조 결과다. ABSENT 는 「지도에 없다」의 후보이지 판정이 아니다 — 개명·僑置·220년 단면 차이를 郡별로 심사해야 한다. 미해독 3행은 지도에서 제외하라는 사용자 결정으로 disposition 을 닫았다.",
        "totals": {"counties": sum(g["declaredCounties"] for g in groups), **{k: totals[k] for k in sorted(totals)},
                   "undisposedAbsent": sum(1 for g in groups for row in g["counties"]
                                            if row["status"] == "ABSENT" and "disposition" not in row),
                   "commanderiesWithAbsentFirstCounty": sum(
                       1 for g in groups if g["firstListedCounty"] and g["firstListedCounty"]["status"] == "ABSENT")},
        "commanderies": groups,
    }


def render(doc: dict) -> str:
    return json.dumps(doc, ensure_ascii=False, indent=1) + "\n"


def _rel(path: Path) -> str:
    try:
        return str(path.relative_to(ROOT))
    except ValueError:
        return str(path)


def main(argv=None) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--check", action="store_true")
    args = ap.parse_args(argv)
    doc = build(*(json.loads(p.read_text(encoding="utf-8")) for p in (UNITS, TILES, WORLD)))
    text = render(doc)
    if args.check:
        if not OUTPUT.exists() or OUTPUT.read_text(encoding="utf-8") != text:
            print(f"DRIFT: {_rel(OUTPUT)} is stale — rerun {Path(__file__).name}", file=sys.stderr)
            return 1
        print(f"OK {_rel(OUTPUT)} {doc['totals']}")
        return 0
    OUTPUT.write_text(text, encoding="utf-8")
    print(f"wrote {_rel(OUTPUT)} {doc['totals']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
