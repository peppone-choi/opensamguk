#!/usr/bin/env python3
"""Append the remaining 郡國志 ABSENT rows as distinct game counties.

Three source names with undeciphered glyphs are recorded as user exclusions.

This is a one-time, user-approved gameplay expansion. A synthetic cell is a
playable location, not a claim about an ancient seat. Source text and the
placement basis remain separate in gap-counties-v1.json.

The ignored shiliao corpus is needed only to copy and verify the cited line:

    python3 tools/map/append_all_gap_counties.py --corpus-root /path/to/data/corpus
"""
from __future__ import annotations

import argparse
import collections
import hashlib
import json
import math
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from tools.map.audit_county_coverage import make_normalizer  # noqa: E402

try:
    import hanja
except ImportError:
    hanja = None

CURATED = ROOT / "data/curated/han"
GAPS = CURATED / "junguozhi-county-gaps-v1.json"
CATALOG = CURATED / "administrative-units.json"
READINESS = CURATED / "gap-placement-readiness-v1.json"
# 동명 재심에서 실결손으로 판정된 縣의 출처 좌표·존속 판정(2026-09-27).
RECHECK = CURATED / "gap-county-source-recheck-v1.json"
LEDGER = CURATED / "gap-counties-v1.json"
TILES = ROOT / "data/map/province-tiles.json"
SYNTHETIC = "SYNTHETIC_COMMANDERY_CELL"
DRY = set("125678")
EXCLUDED_UNDECIPHERED = {
    "gc-g0071-005": "参[�]",
    "gc-g0072-007": "朴[B459]",
    "gc-g0102-010": "朱[B42B]",
}
# 읽기 전용 글자표. 卷113 저본은 簡體인데 hanja 는 广 을 부수 「엄」, 乐 을 「악」, 媪 을 「오」로
# 읽는다 — 地名에서는 廣(광)·樂(낙/락)·媼(온)이다(漢書 卷028 地理志 廣漢·廣至·成樂·媼圍).
# nameHan 원문 표기는 바꾸지 않는다. 簡體 한 글자가 繁體 여럿에 대응하는 글자(郁·干·于)는 넣지 않는다.
READING_GLYPHS = {"广": "廣", "乐": "樂", "媪": "媼"}
# 글자표로 못 푸는 이름은 이름 단위로 적는다. 증인은 漢書 卷028 地理志의 繁體 표기다.
READING_FORMS = {
    ("鬱林郡", "广郁"): "廣鬱",  # 郁 은 그 자체로 繁體(욱)이다 — 여기서는 鬱(울)
    # 〈糸言糸〉 는 코퍼스의 조합 표기다. 後漢書 卷001A 註 「南䜌，縣名，屬鉅鹿郡」, 지도 87055 남련현.
    ("鉅鹿郡", "南〈糸言糸〉"): "南䜌",
}
# hanja 가 못 읽거나 관용 독음과 다르게 읽는 이름. 증인을 같이 적는다.
READING_OVERRIDES = {
    # 𧦦 는 䛁 의 코퍼스 자형이다. hanja 는 𧦦 를 못 읽고 䛁邯 을 「염감」으로 읽는다.
    ("樂浪郡", "𧦦邯"): ("남감", "https://ko.wikipedia.org/wiki/낙랑군 속현표 「남감현(䛁邯縣)」"),
}


def reading_form(commandery: str, name: str) -> str:
    """hanja 에 넘길 읽기 형태. 원문 표기(nameHan)와 별개다."""
    form = READING_FORMS.get((commandery, name))
    if form is not None:
        return form
    return "".join(READING_GLYPHS.get(ch, ch) for ch in name)


def korean_reading(commandery: str, name: str) -> str:
    override = READING_OVERRIDES.get((commandery, name))
    if override is not None:
        return override[0]
    if hanja is None:
        raise RuntimeError("hanja is required for new Korean readings")
    return hanja.translate(reading_form(commandery, name), "substitution")


def reading_drift(ledger: dict) -> list[dict]:
    """합성 행 중 nameKo 가 읽기 형태의 hanja 출력과 다른 행."""
    rows = []
    for row in ledger["counties"]:
        if row.get("nameScript") != "SOURCE_LITERAL":
            continue
        expected = korean_reading(row["commanderyHan"], row["nameHan"])
        if row["nameKo"] != expected:
            rows.append({"id": row["id"], "nameHan": row["nameHan"],
                         "nameKo": row["nameKo"], "expected": expected})
    return rows


def _read(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def _write(path: Path, doc: dict) -> None:
    path.write_text(json.dumps(doc, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def _owner(document: dict) -> list[int]:
    return [value for value, count in document["owner"] for _ in range(count)]


def _lonlat(projection: dict, row: int, col: int) -> tuple[float, float]:
    lon = ((col + .5) * projection["cell"] - projection["pad"] + projection["x0"]) / projection["k"]
    lat = projection["y1"] + projection["pad"] - (row + .5) * projection["cell"]
    return round(lon, 7), round(lat, 7)


def _cell(projection: dict, lon: float, lat: float) -> tuple[int, int]:
    row = math.floor((projection["y1"] + projection["pad"] - lat) / projection["cell"])
    col = math.floor((lon * projection["k"] - projection["x0"] + projection["pad"]) / projection["cell"])
    return row, col


def _eligible_cells(tiles: dict, fold_group) -> dict[str, list[tuple[int, int, int]]]:
    meta = tiles["_meta"]
    rows, cols = meta["rows"], meta["cols"]
    owner = _owner(tiles)
    if len(owner) != rows * cols:
        raise ValueError("owner RLE does not fill the grid")
    parents = {row["id"]: fold_group(row["nameCh"]) for row in tiles["parentRegions"]}
    jurisdictions = {row["id"]: row for row in tiles["jurisdictionRecords"]}
    provinces = tiles["provinceRecords"]
    area = collections.Counter(owner)
    taken = {(city["row"], city["col"]) for city in tiles["cities"]}
    eligible: dict[str, list[tuple[int, int, int]]] = collections.defaultdict(list)
    for position, province_index in enumerate(owner):
        if province_index < 0 or area[province_index] < 18:
            continue
        row, col = divmod(position, cols)
        province = provinces[province_index]
        if province["geometryBasis"] == "GAP_COUNTY_LOCAL_CARVE":
            continue  # this province is absent from the upstream carve input
        if jurisdictions[province["jurisdictionId"]]["kind"] != "COUNTY":
            continue
        if tiles["terrain"][row][col] not in DRY or (row, col) in taken:
            continue
        eligible[parents[province["parentRegionId"]]].append((row, col, province_index))
    return eligible


def _choose_cell(
    key: tuple[str, str], eligible: list[tuple[int, int, int]],
    taken: list[tuple[int, int]], city_cells: list[tuple[int, int]],
    donor_used: collections.Counter[int], area: collections.Counter[int],
    preferred: tuple[int, int] | None,
) -> tuple[int, int, int]:
    if not eligible:
        raise ValueError(f"no eligible county cell: {key}")
    # Spread new 城 across donors, while a documented point keeps local priority.
    # Tie breaking is stable across Python runs and does not rely on hash randomization.
    rank = int.from_bytes(hashlib.sha256(repr(key).encode()).digest()[:8], "big")
    pool = [cell for cell in eligible if donor_used[cell[2]] < max(1, (area[cell[2]] - 8) // 5)]
    if not pool:
        pool = eligible
    for spacing in (6, 4, 2, 0):
        candidates = [cell for cell in pool
                      if all((cell[0] - r) ** 2 + (cell[1] - c) ** 2 >= spacing ** 2
                             for r, c in taken)]
        if candidates:
            break
    if preferred is not None:
        best = min(candidates, key=lambda cell: (
            (cell[0] - preferred[0]) ** 2 + (cell[1] - preferred[1]) ** 2,
            donor_used[cell[2]],
            (cell[0] * 768 + cell[1] + rank) % 104729,
        ))
    else:
        # No historical point: a free cell in the recorded commandery, selected
        # for gameplay spacing. This must never be described as a historical seat.
        best = max(candidates, key=lambda cell: (
            -donor_used[cell[2]],
            min(((cell[0] - r) ** 2 + (cell[1] - c) ** 2 for r, c in city_cells + taken),
                default=0),
            (cell[0] * 768 + cell[1] + rank) % 104729,
        ))
    donor_used[best[2]] += 1
    taken.append((best[0], best[1]))
    return best


def append(corpus_root: Path) -> dict:
    fold = make_normalizer()
    fold_group = make_normalizer(group=True)
    gaps, catalog, readiness, ledger, tiles = map(_read, (GAPS, CATALOG, READINESS, LEDGER, TILES))
    source = {(fold_group(group["canonicalGroup"]), fold(unit["sourceName"])): (i, group, unit)
              for i, group in enumerate(catalog["groups"]) for unit in group["units"]}
    ready = {(fold_group(row["commandery"]), fold(row["name"])): row
             for row in readiness["counties"]}
    existing = {row["id"] for row in ledger["counties"]}
    existing |= {row["id"] for row in ledger.get("retiredAsDuplicate", [])}  # 은퇴 행은 되살리지 않는다
    recheck = {(fold_group(row["commandery"]), fold(row["sourceName"])): row
               for row in _read(RECHECK)["homonymAdjudications"] if row["verdict"] == "HOMONYM_REAL_GAP"}
    eligible = _eligible_cells(tiles, fold_group)
    area = collections.Counter(_owner(tiles))
    parent_names = {row["id"]: fold_group(row["nameCh"]) for row in tiles["parentRegions"]}
    group_cities: dict[str, list[tuple[int, int]]] = collections.defaultdict(list)
    for city in tiles["cities"]:
        # A city can belong to several 省; its own 省 is enough for spacing.
        province = next((p for p in tiles["provinceRecords"] if p.get("cityIndex") is not None
                         and tiles["cities"][p["cityIndex"]]["id"] == city["id"]), None)
        if province is not None:
            group_cities[parent_names[province["parentRegionId"]]].append((city["row"], city["col"]))
    assigned: dict[str, list[tuple[int, int]]] = collections.defaultdict(list)
    donor_used: collections.Counter[int] = collections.Counter()
    new = []
    excluded = []
    for commandery in gaps["commanderies"]:
        group_name = commandery["commandery"]
        group_key = fold_group(group_name)
        for entry in commandery["counties"]:
            if entry["status"] != "ABSENT":
                continue
            key = (group_key, fold(entry["sourceName"]))
            group_index, group, unit = source[key]
            place_id = f"gc-g{group_index:04d}-{unit['ordinal']:03d}"
            if place_id in EXCLUDED_UNDECIPHERED:
                if unit["sourceName"] != EXCLUDED_UNDECIPHERED[place_id]:
                    raise ValueError(f"undeciphered source name changed: {place_id}")
                excluded.append({"placeId": place_id, "sourceName": unit["sourceName"],
                                 "commandery": group_name, "sourceCitation": unit["sourceCitation"],
                                 "reason": "USER_EXCLUDED_UNDECIPHERED_NAME_FROM_MAP"})
                continue
            county_id = f"hhs:{unit['sourceVolume']}:{group_name}:{unit['ordinal']:03d}"
            if county_id in existing:
                continue
            readiness_row = ready.get(key)
            ref = unit["sourceCitation"]
            path = corpus_root / Path(ref["corpusPath"]).name
            body = path.read_bytes()
            if hashlib.sha256(body).hexdigest() != ref["snapshotSha256"]:
                raise ValueError(f"source hash differs: {path}")
            quote = body.decode("utf-8").splitlines()[ref["line"] - 1].strip()
            if not quote:
                raise ValueError(f"empty source line: {path}:{ref['line']}")
            recheck_row = recheck.get(key)
            sourced = recheck_row.get("coordinate") if recheck_row else None
            preferred = None
            if readiness_row and all(field in readiness_row for field in ("lon", "lat")):
                if readiness_row["readiness"] not in {"COORDINATE_REJECTED"}:
                    preferred = _cell(tiles["_meta"]["projection"], readiness_row["lon"], readiness_row["lat"])
            if sourced is None:
                row, col, donor = _choose_cell(key, eligible[group_key], assigned[group_key],
                                               group_cities[group_key], donor_used, area, preferred)
                lon, lat = _lonlat(tiles["_meta"]["projection"], row, col)
            else:
                lon, lat = sourced["lon"], sourced["lat"]
            name = unit["sourceName"]
            reading = korean_reading(group_name, name)
            item = {
                "id": county_id, "nameHan": name, "nameKo": reading,
                "nameScript": "SOURCE_LITERAL", "countySuffix": "縣",
                "commanderyHan": group_name, "role": "COUNTY",
                "positionStatus": "SYNTHETIC", "coordinates": {"latitude": lat, "longitude": lon},
                "coordinateBasis": SYNTHETIC,
                "survivalVerdict": {"ledger": readiness_row["ledger"] if readiness_row else None,
                                    "verdict": readiness_row["verdict"] if readiness_row else "UNKNOWN",
                                    "reading": "게임 배치는 220년 존속 증거가 아니다."},
                "catalogRef": {"sourceVolume": unit["sourceVolume"], "ordinal": unit["ordinal"],
                               "sourceName": name, "declaredCities": group["declaredCities"]},
                "placeSlug": f"g{group_index:04d}",
                "primaryEvidence": [{"book": f"續漢書·郡國志·{group['sourceGroupName']} {name}",
                                     "volume": unit["sourceVolume"], "sourceRepository": "shiliao",
                                     "sourcePath": str(Path("corpus") / path.name),
                                     "sha256": ref["snapshotSha256"], "line": ref["line"],
                                     "quote": quote, "evidenceKind": "RECEIVED_TEXT",
                                     "supports": ["SOURCE_CATALOG_ENTRY_FOR_GAME_CITY"]}],
            }
            if sourced is None:
                item["syntheticPlacement"] = {"row": row, "col": col,
                                              "donorProvinceId": tiles["provinceRecords"][donor]["id"],
                                              "historicalSeatClaim": False,
                                              "reason": "USER_APPROVED_DISTINCT_GAME_CITY"}
            else:
                if not sourced["basis"].startswith("TGAZ "):
                    raise ValueError(f"unsupported sourced coordinate basis: {county_id} {sourced['basis']}")
                item.update(positionStatus="APPROXIMATE", coordinateBasis="READY_TGAZ",
                            coordinateEvidence={"ledger": "data/curated/han/gap-county-source-recheck-v1.json",
                                                "record": sourced["basis"], "crossCheck": sourced.get("crossCheck")})
            if recheck_row is not None:
                item["survivalVerdict"] = {"ledger": "gap-county-source-recheck-v1", "verdict": recheck_row["survival220"],
                                           "reading": "게임 배치는 220년 존속 증거가 아니다."}
            new.append(item)
            existing.add(county_id)
    # 원장 순서는 carve 의 append-only 省 발급 순서다. 새 행은 끝에만 붙인다 — 다시 정렬하면 省 번호가 뒤섞인다.
    ledger["counties"] = ledger["counties"] + new
    ledger["totals"]["counties"] = len(ledger["counties"])
    ledger["totals"]["byCoordinateBasis"] = dict(collections.Counter(
        row["coordinateBasis"] for row in ledger["counties"]))
    ledger["totals"]["byNameScript"] = dict(collections.Counter(
        row["nameScript"] for row in ledger["counties"]))
    ledger["totals"]["commanderies"] = len({row["commanderyHan"] for row in ledger["counties"]})
    note = "2026-09-23: 사용자 승인으로 해독 불가 3행을 제외한 223개 ABSENT를 각각 게임 城으로 배치한다. " \
        "SYNTHETIC_COMMANDERY_CELL은 해당 郡의 빈 격자 칸이며 역사적 縣治의 좌표가 아니다."
    if note not in ledger["note"]:
        ledger["note"] += " " + note
    position_note = "새 223행의 합성 위치는 SYNTHETIC_COMMANDERY_CELL로 별도 표시한다."
    if position_note not in ledger["positionBasis"]:
        ledger["positionBasis"] += " " + position_note
    ledger["excludedUndeciphered"] = excluded
    return ledger


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--corpus-root", type=Path)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--check-readings", action="store_true",
                      help="합성 행 nameKo 가 읽기 형태와 다르면 실패한다")
    mode.add_argument("--refresh-readings", action="store_true",
                      help="합성 행 nameKo 를 읽기 형태로 다시 쓴다(han-tiles 재핀 사슬의 첫 단계)")
    args = parser.parse_args()
    if args.check_readings or args.refresh_readings:
        document = _read(LEDGER)
        drift = reading_drift(document)
        for row in drift:
            print(f"{row['id']} {row['nameHan']}: {row['nameKo']} -> {row['expected']}")
        if args.check_readings:
            return 1 if drift else 0
        by_id = {row["id"]: row["expected"] for row in drift}
        for row in document["counties"]:
            if row["id"] in by_id:
                row["nameKo"] = by_id[row["id"]]
        _write(LEDGER, document)
        print(f"rewrote {len(drift)} readings in {LEDGER.relative_to(ROOT)}")
        return 0
    if args.corpus_root is None:
        parser.error("--corpus-root is required to append counties")
    document = append(args.corpus_root)
    _write(LEDGER, document)
    print(f"wrote {len(document['counties'])} counties to {LEDGER.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
