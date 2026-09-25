#!/usr/bin/env python3
"""縣 월간 철·말 생산 원장.

**어느 縣이 생산하는가는 사료가 정하고, 얼마나 생산하는가는 게임 설계가 정한다.**
`resource-sites-v1.json` 은 스스로 「게임 수치가 아니다」라고 적어 둔 근거 원장이라 산출량이 없다.
그래서 이 도구는 산지 **위치**만 그 원장에서 읽고, 산출량은 아래 [RATES] 로 명시해 원장에 함께 적는다.
수치를 바꾸려면 코드가 아니라 이 상수를 고치고 다시 생성한다.

범위:
  - 철: 後漢 항목 중 縣(jurisdictionId) 이 결속된 것. 郡만 결속된 항목은 縣을 특정할 수 없어 뺀다.
  - 말: 後漢 항목은 郡 단위 기록뿐이라 그 郡의 治所 縣에 싣는다. 州만 적힌 항목·출처 없는 항목은 뺀다.
  - 목재: 산지 원장이 못 받친다(後漢 항목 1건). 「목재는 분산」(2026-09-21 결정)은 **면적 축**으로 옮긴다 —
    han-tiles 에서 그 縣이 가진 삼림이 설 수 있는 땅 칸 수를 센다. 바다·강·호수·사막·범위밖은 뺀다.
    실측으로 1,168 縣 중 1,164 곳(99%)이 생산한다 — 분산이다. 山地+丘陵만 쓰면 342 곳(29%)뿐이라
    「분산」이 아니고, han-tiles 지형에는 삼림 분류가 아예 없다.
  - 소금: 5자원에 없다.
"""
import argparse
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
SITES = ROOT / "data/curated/han/resource-sites-v1.json"
TILES = ROOT / "data/map/han-tiles.json"
OUT = ROOT / "data/curated/han/resource-production-v1.json"
RUNTIME_MAP = ROOT / "infra/src/main/resources/map/han-world-v3.json"
# 런타임이 읽는 판. 원장은 縣 키로 적고 이쪽은 런타임 城 id 로만 적는다 — 엔진이 지도 파일을 다시 읽지 않는다.
RUNTIME_OUT = ROOT / "infra/src/main/resources/campaign/county-production-v1.json"

# 게임 설계 수치다. 사료 근거가 없다 — 사료는 「어디에」만 말한다.
# 규모 감각: 전 지도 월 전 5,005만 · 곡 5억 1,302만(초안 규모)에 견주면 철·말은 3 자리 수 더 희소하다.
RATES = {"IRON": 1000, "HORSE": 100}
# 목재는 칸당 값이다(다른 둘은 산지 1곳당). 칸 수는 han-tiles 실측이라 근거가 있고 단가만 설계다.
TIMBER_PER_CELL = 1
# 삼림이 설 수 있는 땅: 평지·산지·고원·분지·구릉. 바다(0)·강(3)·호수(4)·사막(5)·범위밖(9) 제외.
WOODED_TERRAIN = frozenset("12678")
ERA = "LATER_HAN"


def timber_cells_by_jurisdiction(tiles: dict) -> dict[str, int]:
    """縣별 삼림 가능 면적을 원래 게임 격자의 칸 수로 환산한다.

    지도 해상도를 높여도 생산량은 변하지 않는다. 마지막 정수 반올림은
    국소 해안선 조정으로 4×4 블록이 완전하지 않을 때만 영향을 준다.
    """
    from tools.map.measure_province_seat_offset import expand_rle

    meta = tiles["_meta"]
    owner = expand_rle(tiles["owner"], meta["rows"], meta["cols"])
    records = tiles["provinceRecords"]
    cells: dict[str, int] = {}
    for row_index, terrain_row in enumerate(tiles["terrain"]):
        owner_row = owner[row_index]
        for col, code in enumerate(terrain_row):
            if code not in WOODED_TERRAIN:
                continue
            province = int(owner_row[col])
            if province < 0:
                continue
            jurisdiction = records[province].get("jurisdictionId")
            if jurisdiction:
                cells[jurisdiction] = cells.get(jurisdiction, 0) + 1
    scale = int(meta.get("resolutionScale", 1))
    if scale < 1:
        raise ValueError("resolutionScale must be positive")
    area_divisor = scale * scale
    return {jurisdiction: max(1, (raw + area_divisor // 2) // area_divisor)
            for jurisdiction, raw in cells.items()}


def runtime_county_by_jurisdiction(tiles: dict) -> dict[str, int]:
    """縣 키 → 런타임 城 id. 런타임 지도의 `spatialProvinceId` 가 han-tiles 의 省을 가리키고
    그 省이 縣을 가리킨다. 둘 다 커밋된 파일이라 추측이 없다. 한 縣에 城이 둘이면 거절한다."""
    jurisdiction_of = {row["id"]: row.get("jurisdictionId") for row in tiles["provinceRecords"]}
    mapping: dict[str, int] = {}
    for city in json.loads(RUNTIME_MAP.read_text(encoding="utf-8"))["cities"]:
        jurisdiction = jurisdiction_of.get(city.get("spatialProvinceId"))
        if not jurisdiction:
            continue
        if jurisdiction in mapping:
            raise ValueError(f"jurisdiction {jurisdiction} has two runtime cities")
        mapping[jurisdiction] = int(city["id"])
    return mapping


def build() -> dict:
    sites = json.loads(SITES.read_text(encoding="utf-8"))
    tiles = json.loads(TILES.read_text(encoding="utf-8"))
    seat_of = {c["id"]: c.get("seatJurisdictionId") for c in tiles["commanderyRecords"]}
    known = {j["id"] for j in tiles["jurisdictionRecords"]}
    runtime = runtime_county_by_jurisdiction(tiles)

    rows, skipped = {}, []
    for entry in sites["entries"]:
        if entry["era"] != ERA or entry["resource"] not in RATES:
            continue
        resource = entry["resource"]
        if resource == "IRON":
            county = entry.get("jurisdictionId")
            basis = "SOURCE_COUNTY"
        else:
            county = seat_of.get(entry.get("commanderyId") or "")
            basis = "COMMANDERY_SEAT"
        if not county or county not in known:
            skipped.append({"id": entry["id"], "resource": resource,
                            "matchStatus": entry["matchStatus"], "reason": "no bound county"})
            continue
        row = rows.setdefault(county, {"jurisdictionId": county, "countyId": runtime.get(county),
                                       "sites": [], "monthly": {}})
        row["sites"].append({"id": entry["id"], "resource": resource, "basis": basis,
                             "sourceName": entry.get("sourceName"), "matchStatus": entry["matchStatus"]})
        row["monthly"][resource] = row["monthly"].get(resource, 0) + RATES[resource]

    # 목재: 면적 축. 세부 격자 수는 기준 격자 면적으로 환산한다.
    for jurisdiction, cells in timber_cells_by_jurisdiction(tiles).items():
        if jurisdiction not in known or cells <= 0:
            continue
        row = rows.setdefault(jurisdiction, {"jurisdictionId": jurisdiction,
                                             "countyId": runtime.get(jurisdiction),
                                             "sites": [], "monthly": {}})
        row["monthly"]["TIMBER"] = cells * TIMBER_PER_CELL
        row["woodedCells"] = cells

    ordered = [rows[k] for k in sorted(rows)]
    for row in ordered:
        row["sites"].sort(key=lambda s: s["id"])
        row["monthly"] = {k: row["monthly"][k] for k in sorted(row["monthly"])}
    missing_runtime = [row["jurisdictionId"] for row in ordered if row.get("countyId") is None]
    totals = {}
    for row in ordered:
        for resource, amount in row["monthly"].items():
            totals[resource] = totals.get(resource, 0) + amount
    return {
        "schemaVersion": 2,
        "ledgerId": "resource-production-v1",
        "generator": "tools/map/build_county_resource_production.py",
        "note": "철·말의 위치는 resource-sites-v1 의 사료 근거, 목재는 han-tiles 면적 축이다. 산출량 단가는 모두 게임 설계 수치다. 소금은 5자원에 없다.",
        "era": ERA,
        "rates": dict(sorted(RATES.items())),
        "timber": {"perWoodedCell": TIMBER_PER_CELL, "woodedTerrainCodes": sorted(WOODED_TERRAIN),
                   "resolutionAreaDivisor": int(tiles["_meta"].get("resolutionScale", 1)) ** 2,
                   "axis": "han-tiles 소유 격자의 삼림 가능 면적(기준 격자 칸 수로 환산)",
                   "basis": "GAME_DESIGN_DISTRIBUTED"},
        "source": {"path": "data/curated/han/resource-sites-v1.json",
                   "catalogId": sites["catalogId"]},
        "counts": {"counties": len(ordered), "monthlyTotals": dict(sorted(totals.items())),
                   "skipped": len(skipped), "withoutRuntimeCity": len(missing_runtime)},
        "skipped": sorted(skipped, key=lambda s: s["id"]),
        "counties": ordered,
    }


# 원장 이름 → 런타임 필드 이름. 소문자화만으로는 HORSE 가 horses 가 되지 않는다.
RUNTIME_FIELD = {"IRON": "iron", "TIMBER": "timber", "HORSE": "horses"}


def runtime_document(built: dict) -> dict:
    """엔진이 읽는 최소 문서. 키는 런타임 城 id, 값은 월 생산량이다. 전·곡은 식이 만들므로 없다."""
    rows = [
        {"countyId": row["countyId"],
         "monthly": {RUNTIME_FIELD[name]: amount for name, amount in sorted(row["monthly"].items())}}
        for row in built["counties"] if row.get("countyId") is not None
    ]
    rows.sort(key=lambda row: row["countyId"])
    return {
        "schemaVersion": 1,
        "artifactId": "county-production-v1",
        "generator": "tools/map/build_county_resource_production.py",
        "note": "철·말은 사료 산지, 목재는 면적 축이다. 전·곡은 CountyIncome 의 식이 만든다.",
        "sourceLedger": "data/curated/han/resource-production-v1.json",
        "counts": {"counties": len(rows), "monthlyTotals": built["counts"]["monthlyTotals"]},
        "counties": rows,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    built = build()
    text = json.dumps(built, ensure_ascii=False, indent=1) + "\n"
    runtime_text = json.dumps(runtime_document(built), ensure_ascii=False, indent=1) + "\n"
    outputs = ((OUT, text), (RUNTIME_OUT, runtime_text))
    if args.check:
        stale = [path for path, body in outputs
                 if not path.is_file() or path.read_text(encoding="utf-8") != body]
        if stale:
            for path in stale:
                print(f"STALE {path.relative_to(ROOT)} — python3 {parser.prog}", flush=True)
            return 1
        print(f"OK {OUT.relative_to(ROOT)} {built['counts']}")
        return 0
    for path, body in outputs:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(body, encoding="utf-8")
        print(f"wrote {path.relative_to(ROOT)}")
    print(built["counts"])
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
