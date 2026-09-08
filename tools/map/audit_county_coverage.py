#!/usr/bin/env python3
"""정본 續漢書 郡國志 1,180縣이 지도·좌표 원장에 얼마나 덮여 있는지 잰다.

**왜.** 게임 지도는 정본의 부분집합인데, 얼마나 빠졌는지 아무도 세고 있지 않았다. 실측하니
100郡國 / 781城 으로 **400縣이 郡 안에서 빠져 있고**, 그중 다수는 좌표 자체가 없다.
그리고 5郡國(西河·朔方·定襄·張掖屬國·廣漢屬國)은 통째로 없다.

**대조는 반드시 (郡, 縣) 쌍으로 한다.** 이름만으로 맞추면 동명이지가 겹쳐 788/1180 이 나온다 —
정본 縣이 남의 郡 城에 붙는다. 郡 으로 범위를 좁혀야 400 이라는 정직한 수가 나온다.

**표기는 한 축으로 눕힌다.** 정본은 卷마다 繁/簡 저본이 섞여 있고(卷113 계열이 簡體), 타일
원장은 簡體, 나무위키 수확분은 繁體다. `han-name-simplification-v1.json` 이 그 글자표이고,
그 파일이 자기 생성·검증 근거를 싣는다(id 조인 781쌍 중 772 일치, 예외 9건 명시).

좌표 보유 판정은 jurisdictionRecords의 (commanderyId, nameCh)를 commanderyRecords와
seatPlaceId → cities.id로 조인한다. 郡 표시명인 타일도 실제 관할 縣 이름으로 대조한다.
유한한 숫자 위·경도만 인정하고, 같은 원장 안의 같은 (郡, 縣)에 서로 다른 좌표가 있으면
모호성으로 제외한다. 원장 간 좌표 차이를 자동 판정하지는 않는다. --json의 diagnostics가
invalidCoordinates / ambiguousCoordinates / unresolvedTileIdentity를 구분한다.
placed는 런타임 배치 수이며 좌표 원장의 정확성 인증이 아니다.

접미사 규칙이 함정이다 — `县/縣` 은 타일에만 붙으므로 떼지만, `國·道` 는 정본에서 **이름의
일부**다(安國·夷道). 떼면 安·夷 가 되어 조용히 어긋난다(실측 20건).

사용:
    python3 tools/map/audit_county_coverage.py
    python3 tools/map/audit_county_coverage.py --json
"""

from __future__ import annotations

import argparse
import json
import math
from collections import defaultdict
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[2]
CANON_PATH = ROOT / "data/curated/han/administrative-units.json"
TABLE_PATH = ROOT / "data/curated/han/han-name-simplification-v1.json"
TILES_PATH = ROOT / "data/map/han-tiles.json"
RUNTIME_MAP_PATH = ROOT / "infra/src/main/resources/map/han-world-v3.json"
NAMU_PATH = ROOT / "data/curated/han/namu-place-locations-v1.json"

_GROUP_SUFFIXES = ("侯国", "侯國", "属国", "屬國", "公国", "公國")
_COUNTY_SUFFIXES = ("县", "縣")


def _load(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def make_normalizer(*, group: bool = False) -> Any:
    table = _load(TABLE_PATH)["table"]

    def normalize(name: str | None) -> str:
        text = (name or "").strip()
        if group:
            return "".join(table.get(ch, ch) for ch in text)
        for suffix in _GROUP_SUFFIXES:
            if text.endswith(suffix) and len(text) > len(suffix):
                text = text[: -len(suffix)]
                break
        else:
            for suffix in _COUNTY_SUFFIXES:
                if text.endswith(suffix) and len(text) > 1:
                    text = text[:-1]
                    break
        return "".join(table.get(ch, ch) for ch in text)

    return normalize


def _coordinate_pair(row: dict[str, Any]) -> tuple[float, float] | None:
    lat, lon = row.get("lat"), row.get("lon")
    if any(type(value) not in (int, float) for value in (lat, lon)):
        return None
    if not (-90 <= lat <= 90 and -180 <= lon <= 180):
        return None
    if not all(math.isfinite(value) for value in (lat, lon)):
        return None
    return lat, lon


def _located_pairs(rows: list[dict[str, Any]], source: str, normalize: Any,
                   normalize_group: Any, diagnostics: dict[str, list]) -> set[tuple[str, str]]:
    coordinates: dict[tuple[str, str], set[tuple[float, float]]] = defaultdict(set)
    for row in rows:
        pair = (normalize_group(row["canonicalGroup"]), normalize(row["sourceName"]))
        coordinate = _coordinate_pair(row)
        if coordinate is None:
            diagnostics["invalidCoordinates"].append({
                "source": source, "canonicalGroup": pair[0], "sourceName": pair[1],
                "placeId": row.get("placeId"),
            })
        else:
            coordinates[pair].add(coordinate)
    for pair, values in sorted(coordinates.items()):
        if len(values) > 1:
            diagnostics["ambiguousCoordinates"].append({
                "source": source, "canonicalGroup": pair[0], "sourceName": pair[1],
                "coordinates": [list(value) for value in sorted(values)],
            })
    return {pair for pair, values in coordinates.items() if len(values) == 1}


def _tile_coordinate_rows(tiles: dict[str, Any], diagnostics: dict[str, list]) -> list[dict[str, Any]]:
    places = {row["id"]: row for row in tiles["cities"]}
    commanderies = {row["id"]: row for row in tiles["commanderyRecords"]}
    rows = []
    for jurisdiction in tiles["jurisdictionRecords"]:
        place = places.get(jurisdiction.get("seatPlaceId"))
        commandery = commanderies.get(jurisdiction.get("commanderyId"))
        if place is None or commandery is None or not jurisdiction.get("nameCh") or not commandery.get("nameCh"):
            diagnostics["unresolvedTileIdentity"].append({
                "jurisdictionId": jurisdiction.get("id"),
                "placeId": jurisdiction.get("seatPlaceId"),
                "commanderyId": jurisdiction.get("commanderyId"),
            })
            continue
        rows.append({"canonicalGroup": commandery["nameCh"], "sourceName": jurisdiction["nameCh"],
                     "placeId": place["id"], "lat": place.get("lat"), "lon": place.get("lon")})
    return rows


def audit() -> dict[str, Any]:
    normalize = make_normalizer()
    normalize_group = make_normalizer(group=True)
    canon = _load(CANON_PATH)
    runtime = _load(RUNTIME_MAP_PATH)
    tiles = _load(TILES_PATH)
    namu = _load(NAMU_PATH) if NAMU_PATH.exists() else {"rows": []}

    diagnostics: dict[str, list] = {"invalidCoordinates": [], "ambiguousCoordinates": [],
                                    "unresolvedTileIdentity": []}
    placed_by_jun: dict[str, set[str]] = defaultdict(set)
    for city in runtime["cities"]:
        placed_by_jun[normalize_group(city["meta"].get("junCh"))].add(normalize(city["meta"].get("nameCh")))
    tile_pairs = _located_pairs(_tile_coordinate_rows(tiles, diagnostics), "tiles", normalize,
                                normalize_group, diagnostics)
    namu_pairs = _located_pairs(namu["rows"], "namu", normalize, normalize_group, diagnostics)

    groups: list[dict[str, Any]] = []
    totals = {"canon": 0, "placed": 0, "namu": 0, "tileOnly": 0, "unlocated": 0}
    for group in canon["groups"]:
        key = normalize_group(group["canonicalGroup"])
        names = {normalize(u["sourceName"]) for u in group.get("units", [])}
        placed = names & placed_by_jun.get(key, set())
        missing = names - placed
        from_namu = {name for name in missing if (key, name) in namu_pairs}
        rest = missing - from_namu
        tile_only = {name for name in rest if (key, name) in tile_pairs}
        unlocated = rest - tile_only

        totals["canon"] += len(names)
        totals["placed"] += len(placed)
        totals["namu"] += len(from_namu)
        totals["tileOnly"] += len(tile_only)
        totals["unlocated"] += len(unlocated)
        groups.append({
            "canonicalGroup": group["canonicalGroup"],
            "canonCounties": len(names),
            "placed": len(placed),
            "filledByNamu": len(from_namu),
            "tileOnly": len(tile_only),
            "unlocated": sorted(unlocated),
        })
    groups.sort(key=lambda g: -len(g["unlocated"]))
    return {"totals": totals, "groups": groups, "diagnostics": diagnostics}


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()
    result = audit()
    if args.json:
        print(json.dumps(result, ensure_ascii=False, indent=2, sort_keys=True))
        return 0
    totals = result["totals"]
    print(f"정본 {totals['canon']}縣")
    print(f"  지도에 배치됨       : {totals['placed']}")
    print(f"  나무위키 원장이 채움 : {totals['namu']}")
    print(f"  타일에 좌표만 있음   : {totals['tileOnly']}")
    print(f"  좌표 없음           : {totals['unlocated']}")
    for category, rows in result["diagnostics"].items():
        if rows:
            print(f"  제외 진단 {category}: {len(rows)} (상세: --json)")
    empty = [g["canonicalGroup"] for g in result["groups"] if g["placed"] == 0]
    print(f"\n지도에 한 城도 없는 郡國: {len(empty)} — {' '.join(empty)}")
    print("\n좌표 없는 縣이 많은 郡國 15")
    print("%-10s %6s %6s %6s" % ("郡國", "정본", "배치", "무좌표"))
    for group in result["groups"][:15]:
        print("%-10s %6d %6d %6d" % (
            group["canonicalGroup"], group["canonCounties"], group["placed"], len(group["unlocated"])
        ))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
