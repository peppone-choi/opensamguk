#!/usr/bin/env python3
"""타일 격자 패키저 — 렌더러 산출물을 프런트가 먹을 수 있는 한 파일로 굽는다.

`build_terrain_grid.py` 가 만든 `data/map/terrain-grid.json`(768×669 지형·소유·지역)과
`build_han_places.py` 의 `data/map/han-places.json`(군현 좌표)을 읽어
`data/map/han-tiles.json` 을 낸다. 지형을 **유도하지 않는다** — 유도는 렌더러가 이미 했다.

이 스크립트가 하는 일은 셋뿐이다.
  1. 셀 격자를 압축한다. 지형은 셀당 한 글자, 소유는 런렝스.
  2. 도로를 셀로 굽고 이웃 비트마스크를 매긴다. 타일 아트가 서로 이어지려면 필요하다.
  3. 지역(태행산·화북평원 …)을 셀 목록이 아니라 라벨 놓을 무게중심 하나로 줄인다.

이전 판은 che 도로 그래프에서 지형을 지어냈다(96×96, 도로에서 멀면 산). 실제 지형 격자가
생겼으므로 그 추정은 버린다 — 두 개의 지형 진실을 두면 어느 쪽이 맞는지 아무도 모른다.

    python3 tools/map/build_tile_grid.py
    python3 tools/map/build_tile_grid.py --check   # 손편집 드리프트 검사
"""

from __future__ import annotations

import argparse
import copy
import json
import re
import sys
from collections import Counter
from pathlib import Path

try:
    from tools.map.world_province_geometry import (
        apply_jurisdiction_parent_adjudications,
        assign_province_jurisdictions,
        infer_commandery_kind,
        validate_jurisdiction_recovery_document,
    )
except ModuleNotFoundError:  # pragma: no cover - direct script compatibility
    from world_province_geometry import (
        apply_jurisdiction_parent_adjudications,
        assign_province_jurisdictions,
        infer_commandery_kind,
        validate_jurisdiction_recovery_document,
    )

try:
    from tools.map.han_place_merge_runtime import (
        REVIEWED_CATALOG_PLACE_IDS,
        SOURCE_PLACE_IDS,
        validate_reviewed_catalog_policy,
    )
except ModuleNotFoundError:  # pragma: no cover - direct script compatibility
    from han_place_merge_runtime import (
        REVIEWED_CATALOG_PLACE_IDS,
        SOURCE_PLACE_IDS,
        validate_reviewed_catalog_policy,
    )

ROOT = Path(__file__).resolve().parents[2]
MAP = ROOT / "data" / "map"
GRID = MAP / "terrain-grid.json"
PLACES = MAP / "han-places.json"
READINGS = MAP / "readings.json"
OUT = MAP / "han-tiles.json"
LEGACY_GAMEPLAY_TILES = MAP / "han-780-v1-tiles.json"
HAN_GAMEPLAY = ROOT / "infra" / "src" / "main" / "resources" / "map" / "han.json"
JURISDICTION_RECOVERIES = (
    ROOT / "data" / "curated" / "han" / "jurisdiction-seat-recoveries-v1.json"
)
JURISDICTION_PARENT_ADJUDICATIONS = (
    ROOT / "data" / "curated" / "han" / "jurisdiction-commandery-adjudications-v1.json"
)

# 220년 정본 군국명과 시나리오 시기명이 다른 같은 행정 권역.
# 소유권 바인딩에서만 별칭으로 해석하고 지도 표시명은 220년 정본을 유지한다.
PARENT_TEMPORAL_ALIASES = {
    '甘陵郡': ['신성후국'],  # 新成侯國 → 甘陵郡
}

# 도로 비트마스크 — 이웃 4방향(N=1 E=2 S=4 W=8). 타일 16종이 서로 이어진다.
NEI = {(0, -1): 1, (1, 0): 2, (0, 1): 4, (-1, 0): 8}
CANONICAL_COLS = 768
CANONICAL_ROWS = 669
CANONICAL_YEAR = 220

# CHGIS v6 preserves a handful of source-editor annotations in place names.  They
# are useful provenance, but they are not player-facing county names.  Stable
# physical IDs keep these corrections independent from array order, coordinates,
# or translated text.  The observed source pair is part of the contract: an
# upstream change must be reviewed instead of being silently rewritten.
CANONICAL_PLACE_NAME_NORMALIZATIONS = {
    "82175": {
        "sourceName": "[부언]현",
        "sourceNameCh": "[阝焉]县",
        "displayName": "언현",
        "nameCh": "鄢县",
    },
    "85202": {
        "sourceName": "영현（영현）",
        "sourceNameCh": "灵县（零县）",
        "displayName": "영현",
        "nameCh": "灵县",
        "runtimeSourceName": "영현（영현）",
        "runtimePreviousName": "영",
        "runtimeName": "청하국 영",
        "runtimeCityId": 200,
        "runtimeCityConstRowIds": [199, 200, 255, 274, 360, 365],
        "runtimeSourceOccurrences": 6,
        "runtimeCanonicalOccurrences": 6,
    },
    "85376": {
        "sourceName": "[건궁현]（심궁현，수궁현）후국",
        "sourceNameCh": "[巾弓玄]（忄弓玄，扌弓玄）侯国",
        "displayName": "견후국",
        "nameCh": "㡉侯国",
    },
    "85377": {
        "sourceName": "[건궁현]현",
        "sourceNameCh": "[巾弓玄]县",
        "displayName": "견현",
        "nameCh": "㡉县",
    },
    "85448": {
        "sourceName": "곡성（성）현",
        "sourceNameCh": "曲成（城）县",
        "displayName": "곡성현",
        "nameCh": "曲成县",
        "runtimeSourceName": "곡성（성）",
        "runtimePreviousName": "곡성",
        "runtimeName": "동래군 곡성",
        "runtimeCityId": 373,
        "runtimeCityConstRowIds": [367, 371, 373],
        "runtimeSourceOccurrences": 3,
        "runtimeCanonicalOccurrences": 3,
    },
}
SOURCE_ANNOTATION_PATTERN = re.compile(r"[\[\]（）()]")


def _normalizable_collections(document: dict) -> list[tuple[str, list[dict], str]]:
    collections: list[tuple[str, list[dict], str]] = []
    for key, display_key in (
            ("cities", "name"),
            ("provinceRecords", "displayName"),
            ("jurisdictionRecords", "displayName")):
        rows = document.get(key)
        if isinstance(rows, list):
            collections.append((key, rows, display_key))
    legacy = document.get("legacyGameplay")
    if isinstance(legacy, dict) and isinstance(legacy.get("cities"), list):
        collections.append(("legacyGameplay.cities", legacy["cities"], "name"))
    return collections


def normalize_han_place_names(document: dict) -> dict:
    """Apply reviewed stable-ID name corrections to every materialized view."""
    result = copy.deepcopy(document)
    for collection_name, rows, display_key in _normalizable_collections(result):
        for row in rows:
            place_id = str(row.get("id", ""))
            normalization = CANONICAL_PLACE_NAME_NORMALIZATIONS.get(place_id)
            if normalization is None:
                continue
            actual = (row.get(display_key), row.get("nameCh"))
            source = (normalization["sourceName"], normalization["sourceNameCh"])
            canonical = (normalization["displayName"], normalization["nameCh"])
            if actual not in {source, canonical}:
                raise ValueError(
                    f"{place_id} source name drift in {collection_name}: {actual!r}"
                )
            row[display_key], row["nameCh"] = canonical
    validate_han_place_names(result)
    return result


def validate_han_place_names(document: dict) -> None:
    """Reject CHGIS source annotations from Han county-level display names."""
    failures: list[str] = []
    for collection_name, rows, display_key in _normalizable_collections(document):
        for row in rows:
            is_han_county = (
                row.get("kind") in {"COUNTY", "MARQUISATE"}
                or (
                    row.get("kind") == "SPATIAL_PROVINCE"
                    and row.get("administrativeSystem") == "HAN_COMMANDERY"
                )
            )
            # Small unit fixtures may omit kind; they still exercise the public
            # validator and therefore remain subject to the same text contract.
            if row.get("kind") is None:
                is_han_county = True
            if not is_han_county:
                continue
            display_name, name_ch = row.get(display_key), row.get("nameCh")
            if any(
                isinstance(value, str) and SOURCE_ANNOTATION_PATTERN.search(value)
                for value in (display_name, name_ch)
            ):
                failures.append(f"{collection_name}:{row.get('id')}")
    if failures:
        raise ValueError(
            "Han county name contains a source annotation: " + ", ".join(failures)
        )


def rle(rows: list[list[int]]) -> list[list[int]]:
    """[[값, 반복], …] 로 평탄화. 소유 격자는 1092종이라 한 글자에 안 들어간다.

    보로노이 소유는 큰 덩어리로 뭉쳐 있어 런렝스가 잘 먹는다(65536셀 → 수천 쌍).
    """
    out: list[list[int]] = []
    for row in rows:
        for v in row:
            if out and out[-1][0] == v:
                out[-1][1] += 1
            else:
                out.append([v, 1])
    return out


def resolve_province_record_names(
        records: list[dict], parent_regions: list[dict], cities: list[dict],
        parent_seats: list[int]) -> list[dict]:
    """Replace Chinese geometry placeholder labels with the parent commandery name.

    A modern ADM2 fragment is geometry provenance, not a historical administrative
    unit. Chinese background fragments remain non-city geometry and inherit their
    sourced commandery label instead of inventing a county or exposing "direct territory".
    """
    parent_index = {parent['id']: index for index, parent in enumerate(parent_regions)}
    for record in records:
        city_index = record.get('cityIndex')
        if city_index is None:
            continue
        if type(city_index) is not int or not 0 <= city_index < len(cities):
            raise ValueError('provinceRecords cityIndex is out of range')

    resolved = []
    for record in records:
        city_index = record.get('cityIndex')
        if city_index is not None:
            if type(city_index) is not int or not 0 <= city_index < len(cities):
                raise ValueError('provinceRecords cityIndex is out of range')
            display_name = (
                cities[city_index]['name']
                if record.get('administrativeSystem') == 'HAN_COMMANDERY'
                else record['displayName']
            )
            resolved.append({**record, 'displayName': display_name})
            continue

        if (record.get('kind') == 'DIRECT_TERRITORY'
                and record.get('administrativeSystem') == 'HAN_COMMANDERY'):
            parent_id = record['parentRegionId']
            index = parent_index.get(parent_id)
            if index is None or index >= len(parent_seats):
                raise ValueError(f'Chinese direct territory has no parent seat: {record["id"]}')
            parent = parent_regions[index]
            city_index = parent_seats[index]
            if type(city_index) is not int or not 0 <= city_index < len(cities):
                raise ValueError(f'Chinese direct territory has invalid parent seat: {record["id"]}')
            resolved.append({
                **record,
                'displayName': parent['displayName'],
                'nameCh': parent['nameCh'],
            })
            continue

        if record.get('kind') == 'DIRECT_TERRITORY':
            parent_id = record['parentRegionId']
            index = parent_index.get(parent_id)
            if index is None:
                raise ValueError(f'direct territory has no parent: {record["id"]}')
            parent = parent_regions[index]
            resolved.append({
                **record,
                'displayName': parent['displayName'],
                'nameCh': parent['nameCh'],
            })
            continue

        resolved.append(dict(record))
    return resolved


def validate_canonical_inputs(
        grid_document: dict, places_document: dict, *, readings_exists: bool) -> None:
    """Validate the commit-producing CLI inputs without constraining direct fixture builds."""
    if not readings_exists:
        raise ValueError('readings.json is required for canonical tile materialization')
    if not isinstance(grid_document, dict) or not isinstance(places_document, dict):
        raise ValueError('terrain-grid and han-places documents are required')
    grid_projection = grid_document.get('projection')
    places_projection = places_document.get('projection')
    if not isinstance(grid_projection, dict) or not isinstance(places_projection, dict):
        raise ValueError('terrain-grid and han-places projection objects are required')
    if grid_projection != places_projection:
        raise ValueError('terrain-grid and han-places projections must match exactly')

    expected_dimensions = (CANONICAL_COLS, CANONICAL_ROWS)
    for label, document in (
            ('terrain-grid', grid_document), ('han-places', places_document)):
        dimensions = (document.get('cols'), document.get('rows'))
        projection_dimensions = (
            document['projection'].get('cols'), document['projection'].get('rows')
        )
        if dimensions != expected_dimensions or projection_dimensions != expected_dimensions:
            raise ValueError(
                f'{label} must use the canonical {CANONICAL_COLS}x{CANONICAL_ROWS} projection'
            )
        if document.get('grid') != CANONICAL_COLS:
            raise ValueError(f'{label} grid must be {CANONICAL_COLS}')
        if document.get('year') != CANONICAL_YEAR:
            raise ValueError(f'{label} year must be {CANONICAL_YEAR}')


def resolve_compact_places(grid_document: dict, places_document: dict) -> list[dict]:
    """Resolve terrain-grid placeIds against raw places without positional joins."""
    if not isinstance(grid_document, dict) or not isinstance(places_document, dict):
        raise ValueError('terrain-grid and han-places documents are required')
    raw_places = places_document.get('places')
    place_ids = grid_document.get('placeIds')
    if not isinstance(raw_places, list) or not isinstance(place_ids, list):
        raise ValueError('raw places and terrain-grid placeIds arrays are required')

    raw_ids = []
    by_id = {}
    for index, place in enumerate(raw_places):
        if not isinstance(place, dict):
            raise ValueError(f'places[{index}] must be an object')
        place_id = place.get('id')
        if not isinstance(place_id, str) or not place_id:
            raise ValueError(f'places[{index}].id must be a nonempty string')
        if place_id in by_id:
            raise ValueError(f'duplicate raw physical place ID: {place_id}')
        raw_ids.append(place_id)
        by_id[place_id] = place

    if len(set(place_ids)) != len(place_ids):
        raise ValueError('terrain-grid placeIds must be unique')
    if any(not isinstance(place_id, str) or not place_id for place_id in place_ids):
        raise ValueError('terrain-grid placeIds must be nonempty strings')
    if set(place_ids) & set(SOURCE_PLACE_IDS):
        raise ValueError('terrain-grid placeIds contains a reviewed source ID')
    unknown = [place_id for place_id in place_ids if place_id not in by_id]
    if unknown:
        raise ValueError(f'terrain-grid placeIds contains unknown IDs: {unknown[:5]}')

    expected = [place_id for place_id in raw_ids if place_id not in SOURCE_PLACE_IDS]
    if place_ids != expected:
        raise ValueError(
            'terrain-grid placeIds must equal raw place order minus the six reviewed sources'
        )
    if not set(SOURCE_PLACE_IDS) <= set(raw_ids):
        missing = sorted(set(SOURCE_PLACE_IDS) - set(raw_ids))
        raise ValueError(f'raw han-places is missing reviewed source IDs: {missing}')
    missing_reviewed = set(REVIEWED_CATALOG_PLACE_IDS) - set(raw_ids)
    if missing_reviewed:
        raise ValueError(
            f'raw han-places is missing reviewed catalog IDs: {sorted(missing_reviewed)}'
        )
    return [by_id[place_id] for place_id in place_ids]


def _validate_grid_rows(value, label, rows, cols, *, lower=None, upper=None):
    if not isinstance(value, list) or len(value) != rows:
        raise ValueError(f'{label} must have exactly {rows} rows')
    for row_index, row in enumerate(value):
        if not isinstance(row, list) or len(row) != cols:
            raise ValueError(f'{label}[{row_index}] must have exactly {cols} columns')
        if lower is not None:
            for col_index, item in enumerate(row):
                if type(item) is not int or item < lower or item >= upper:
                    raise ValueError(
                        f'{label}[{row_index}][{col_index}] contains an invalid label'
                    )


def _derive_adjacency(labels):
    touching = Counter()
    rows = len(labels)
    cols = len(labels[0]) if rows else 0
    for row in range(rows):
        for col in range(cols - 1):
            a, b = labels[row][col], labels[row][col + 1]
            if a >= 0 and b >= 0 and a != b:
                touching[tuple(sorted((a, b)))] += 1
    for row in range(rows - 1):
        for col in range(cols):
            a, b = labels[row][col], labels[row + 1][col]
            if a >= 0 and b >= 0 and a != b:
                touching[tuple(sorted((a, b)))] += 1
    return [
        {'a': a, 'b': b, 'cells': cells}
        for (a, b), cells in sorted(touching.items())
    ]


def _validate_edges(edges, label, upper, expected, *, commandery, cols, rows):
    if not isinstance(edges, list):
        raise ValueError(f'{label} adjacency must be an array')
    stripped = []
    seen = set()
    for index, edge in enumerate(edges):
        if not isinstance(edge, dict):
            raise ValueError(f'{label}[{index}] must be an object')
        allowed = (
            {'a', 'b', 'cells', 'cross'}
            if commandery else {'a', 'b', 'cells'}
        )
        if commandery and edge.get('cross') == 'RIVER':
            allowed.add('ford')
        required = {'a', 'b', 'cells', 'cross'} if commandery else {'a', 'b', 'cells'}
        if set(edge) != allowed or not required <= set(edge):
            raise ValueError(f'{label}[{index}] has an invalid edge schema')
        for endpoint in ('a', 'b'):
            value = edge.get(endpoint)
            if type(value) is not int or not 0 <= value < upper:
                raise ValueError(f'{label}[{index}].{endpoint} is out of range')
        if edge['a'] >= edge['b']:
            raise ValueError(f'{label}[{index}] endpoints must be canonical a < b')
        pair = (edge['a'], edge['b'])
        if pair in seen:
            raise ValueError(f'{label}[{index}] duplicates an adjacency pair')
        seen.add(pair)
        if type(edge.get('cells')) is not int or edge['cells'] <= 0:
            raise ValueError(f'{label}[{index}].cells must be a positive integer')
        if commandery:
            if edge['cross'] not in {'NONE', 'LAND', 'RIVER'}:
                raise ValueError(f'{label}[{index}].cross is invalid')
            if edge['cross'] == 'RIVER':
                ford = edge.get('ford')
                if (not isinstance(ford, list) or len(ford) != 2
                        or any(type(value) is not int for value in ford)
                        or not (0 <= ford[0] < cols and 0 <= ford[1] < rows)):
                    raise ValueError(f'{label}[{index}].ford is invalid')
        stripped.append({key: edge[key] for key in ('a', 'b', 'cells')})
    if stripped != expected:
        raise ValueError(f'{label} does not match labels rederived from the grid')


def validate_compact_grid(grid_document: dict, places: list[dict]) -> bool:
    """Validate every compact array and graph index consumed by tile packaging."""
    if not isinstance(grid_document, dict) or not isinstance(places, list):
        raise ValueError('terrain-grid document and resolved places are required')
    cols, rows = grid_document.get('cols'), grid_document.get('rows')
    if type(cols) is not int or type(rows) is not int or cols <= 0 or rows <= 0:
        raise ValueError('terrain-grid cols and rows must be positive integers')
    count = len(places)
    place_ids = grid_document.get('placeIds')
    if not isinstance(place_ids, list) or len(place_ids) != count:
        raise ValueError('placeIds length must equal the compact place count')

    terrain = grid_document.get('terrain')
    _validate_grid_rows(terrain, 'terrain', rows, cols)
    legend = grid_document.get('legend')
    if not isinstance(legend, dict):
        raise ValueError('terrain legend must be an object')
    try:
        terrain_labels = {int(key) for key in legend}
    except (TypeError, ValueError) as error:
        raise ValueError('terrain legend keys must be integer strings') from error
    if any(type(value) is not int or value not in terrain_labels for row in terrain for value in row):
        raise ValueError('terrain contains a label absent from its legend')
    region_names = grid_document.get('regionNames')
    if not isinstance(region_names, list):
        raise ValueError('regionNames must be an array')
    _validate_grid_rows(
        grid_document.get('region'), 'region', rows, cols,
        lower=-1, upper=len(region_names),
    )
    province_records = grid_document.get('provinceRecords')
    generic = isinstance(province_records, list)
    province_count = len(province_records) if generic else count
    _validate_grid_rows(
        grid_document.get('owner'), 'owner', rows, cols,
        lower=-1, upper=province_count,
    )
    owner = grid_document['owner']
    owner_areas = Counter(value for row in owner for value in row if value >= 0)
    if any(owner_areas[index] == 0 for index in range(province_count)):
        raise ValueError('every province record must own at least one owner cell')
    jun_names = grid_document.get('junNames')
    hubs = grid_document.get('hubs')
    if not isinstance(jun_names, list) or not isinstance(hubs, list) or len(hubs) != len(jun_names):
        raise ValueError('hubs and junNames must have exactly matching lengths')
    parent_regions = grid_document.get('parentRegions')
    if generic and (not isinstance(parent_regions, list) or len(parent_regions) != len(jun_names)):
        raise ValueError('parentRegions must align with the reviewed parent catalog')
    jun_count = len(parent_regions) if generic else len(jun_names)
    parent_owner = grid_document.get('parentOwner') if generic else grid_document.get('seatOwner')
    _validate_grid_rows(
        parent_owner, 'parentOwner' if generic else 'seatOwner', rows, cols,
        lower=-1, upper=jun_count,
    )

    jun_of = grid_document.get('junOf')
    if not isinstance(jun_of, list) or len(jun_of) != count:
        raise ValueError('junOf length must equal the compact place count')
    if any(type(value) is not int or not 0 <= value < jun_count for value in jun_of):
        if count or jun_count:
            raise ValueError('junOf contains an out-of-range label')
    checked_hubs = []
    for jun_index, hub in enumerate(hubs):
        if type(hub) is not int or not 0 <= hub < count:
            raise ValueError(f'hubs[{jun_index}] is out of range')
        if jun_of[hub] != jun_index:
            raise ValueError(f'hubs[{jun_index}] is not a member of its jun')
        place = places[hub]
        gx, gy = place.get('gx'), place.get('gy')
        if type(gx) is not int or type(gy) is not int or not (0 <= gx < cols and 0 <= gy < rows):
            raise ValueError(f'hubs[{jun_index}] has an invalid compact place cell')
        if parent_owner[gy][gx] != jun_index:
            raise ValueError(f'hubs[{jun_index}] seat cell does not belong to its jun')
        checked_hubs.append(hub)
    if len(set(checked_hubs)) != len(checked_hubs):
        raise ValueError('hubs must be unique')

    zhi = grid_document.get('zhiPlaces')
    if not isinstance(zhi, list):
        raise ValueError('zhiPlaces must be an array')
    if any(type(value) is not int or not 0 <= value < count for value in zhi):
        raise ValueError('zhiPlaces contains an out-of-range index')
    if len(set(zhi)) != len(zhi):
        raise ValueError('zhiPlaces must be unique')

    validate_reviewed_catalog_policy({
        'places': places,
        'placeIds': place_ids,
        'hubs': hubs,
        'junNames': jun_names,
        'junOf': jun_of,
        'zhiPlaces': zhi,
    })

    adjacency = grid_document.get('adjacency')
    if not isinstance(adjacency, dict) or set(adjacency) != {'county', 'commandery'}:
        raise ValueError('adjacency must contain exactly county and commandery graphs')
    _validate_edges(
        adjacency['county'], 'adjacency.county', province_count,
        _derive_adjacency(owner), commandery=False, cols=cols, rows=rows,
    )
    _validate_edges(
        adjacency['commandery'], 'adjacency.commandery', jun_count,
        _derive_adjacency(parent_owner),
        commandery=True, cols=cols, rows=rows,
    )
    return True


def build(
        *, grid_document: dict | None = None,
        places_document: dict | None = None,
        readings_document: dict | None = None,
        jurisdiction_recoveries_document: dict | None = None,
        jurisdiction_parent_adjudications_document: dict | None = None) -> dict:
    grid = grid_document if grid_document is not None else json.loads(GRID.read_text())
    places_doc = (
        places_document if places_document is not None else json.loads(PLACES.read_text())
    )
    places = resolve_compact_places(grid, places_doc)
    validate_compact_grid(grid, places)
    cols, rows = grid["cols"], grid["rows"]

    # 한글 독음 사전 — tools/map/build_readings.py 산출물. 없으면 지금처럼 한자 그대로
    # 두고 경고만 낸다(폴백). 있으면 name 을 독음으로, 한자 원문은 nameCh 로 보존한다.
    readings: dict[str, str] = {}
    readings_supplied = readings_document is not None
    if readings_supplied:
        if not isinstance(readings_document, dict):
            raise ValueError('readings_document must be an object')
        readings = readings_document
    elif READINGS.exists():
        readings = json.loads(READINGS.read_text())
    else:
        print(f"경고: {READINGS.relative_to(ROOT)} 없음 — 지명이 한자로 남는다. "
              "tools/map/build_readings.py 를 먼저 돌려라.", file=sys.stderr)

    misses: list[str] = []

    def kr(name: str) -> str:
        if name not in readings:
            misses.append(name)
            return name
        return readings[name]

    # 도로·수로·해로는 내보내지 않는다. 자동으로 그은 길이 사료와 너무 어긋나서
    # 사람이 직접 놓기로 했다(2026-08-19 사용자 지시). 계산은 terrain-grid 에 남아
    # 있으니 되살릴 때는 여기서 다시 꺼내 쓰면 된다.
    # 지역은 셀 목록 대신 라벨 하나로 줄인다. 65536셀을 프런트에 넘길 이유가 없다.
    acc: dict[int, list[int]] = {}
    for y, row in enumerate(grid["region"]):
        for x, rid in enumerate(row):
            if rid >= 0:
                a = acc.setdefault(rid, [0, 0, 0])
                a[0] += x; a[1] += y; a[2] += 1
    regions = []
    for rid, (sx, sy, c) in sorted(acc.items()):
        # regionNames 원소는 {name, zh, cls} 다. 통째로 넣으면 라벨이 dict 가 된다.
        nm = grid["regionNames"][rid]
        zh = nm["zh"] or nm["name"]
        regions.append({"name": kr(zh), "nameCh": zh, "en": nm["name"], "cls": nm["cls"],
                        "col": round(sx / c), "row": round(sy / c), "cells": c})

    hubs = set(grid["hubs"])
    # 郡國志에 이름이 실린 縣에 `zhi` 를 세운다. 이 표시가 붙은 縣만 게임 거점이 되고,
    # 나머지 CHGIS 점은 소속 郡도 등급 근거도 없어 배경으로만 남는다(郡國志 추출이 보강되면 는다).
    # 배열에서 빼지는 않는다 — adjacency 와 juns[].seat 의 인덱스가 이 배열이다.
    zhi = set(grid.get("zhiPlaces") or [])
    cities = [{"id": p["id"], "name": kr(p["nameFt"]), "nameCh": p["nameCh"], "level": p.get("level", 5),
               "kind": p["kind"], "seat": i in hubs, "zhi": i in zhi, "col": p["gx"], "row": p["gy"],
               "lon": p["lon"], "lat": p["lat"]}
              for i, p in enumerate(places)]

    kinds = Counter(c["kind"] for c in cities)
    owner = rle(grid["owner"])
    # 압축은 조용히 틀리는 종류의 코드다 — 되돌렸을 때 크기가 맞는지 여기서 깨뜨린다.
    assert sum(c for _, c in owner) == cols * rows, "소유 런렝스 총합이 격자 크기와 다르다"
    assert all(len(r) == cols for r in grid["terrain"]) and len(grid["terrain"]) == rows, "지형 행/열"
    off = [c["name"] for c in cities if c["seat"] and grid["terrain"][c["row"]][c["col"]] == 0]
    assert not off, f"군치가 바다 위에 있다: {off[:5]}"
    # 郡 명부. adjacency 의 a/b 가 이 배열의 인덱스다 — 이름 없이 인덱스만 넘기면
    # 프런트가 어느 郡인지 알 수 없다. kr() 호출은 미스 가드보다 먼저 끝나야 한다 —
    # return 리터럴 안에 두면 가드를 지난 뒤에야 평가돼 juns 전용 이름(河閒國 등
    # cities/regions 어디에도 없는 22개)의 미스를 가드가 못 잡는다(critic-524 재심).
    juns = [{"name": kr(nm), "nameCh": nm, "seat": h, "col": places[h]["gx"], "row": places[h]["gy"]}
            for nm, h in zip(grid["junNames"], grid["hubs"])]
    province_records = None
    parent_regions = None
    jurisdiction_records = None
    commandery_records = None
    legacy_tiles = None
    active_gameplay_parent_names = None
    if isinstance(grid.get('provinceRecords'), list):
        if LEGACY_GAMEPLAY_TILES.is_file():
            legacy_tiles = json.loads(LEGACY_GAMEPLAY_TILES.read_text(encoding='utf-8'))
            if HAN_GAMEPLAY.is_file():
                gameplay = json.loads(HAN_GAMEPLAY.read_text(encoding='utf-8'))
                active_gameplay_parent_names = sorted({
                    city.get('meta', {}).get('junCh')
                    for city in gameplay['cities']
                    if city.get('meta', {}).get('junCh')
                })
                legacy_jun_by_name = {
                    jun['nameCh']: jun for jun in legacy_tiles['juns']
                }
                legacy_cities_by_name = {}
                for index, city in enumerate(legacy_tiles['cities']):
                    legacy_cities_by_name.setdefault(city['nameCh'], []).append(index)
                for gameplay_city in gameplay['cities']:
                    meta = gameplay_city.get('meta', {})
                    if meta.get('isSeat') is not True:
                        continue
                    legacy_jun = legacy_jun_by_name.get(meta.get('junCh'))
                    candidates = legacy_cities_by_name.get(meta.get('nameCh'), [])
                    if legacy_jun is None or len(candidates) != 1:
                        continue
                    legacy_seat = candidates[0]
                    legacy_city = legacy_tiles['cities'][legacy_seat]
                    legacy_jun.update(
                        seat=legacy_seat,
                        col=legacy_city['col'],
                        row=legacy_city['row'],
                    )
        parent_regions = []
        for index, record in enumerate(grid['parentRegions']):
            translated = juns[index]['name'] if index < len(juns) else record['displayName']
            seat_kind = cities[juns[index]['seat']]['kind'] if index < len(juns) else None
            commandery_kind = infer_commandery_kind(record, {'kind': seat_kind})
            resolved = {**record, 'displayName': translated, 'kind': commandery_kind}
            aliases = PARENT_TEMPORAL_ALIASES.get(record['nameCh'])
            if aliases:
                resolved['aliases'] = aliases
            parent_regions.append(resolved)
        province_records = resolve_province_record_names(
            grid['provinceRecords'], parent_regions, cities, [jun['seat'] for jun in juns],
        )
        recoveries_document = jurisdiction_recoveries_document
        if recoveries_document is None:
            recoveries_document = json.loads(JURISDICTION_RECOVERIES.read_text(encoding='utf-8'))
        if not isinstance(recoveries_document, dict):
            raise ValueError('jurisdiction recovery document must be an object')
        recoveries = validate_jurisdiction_recovery_document(recoveries_document)
        assigned = assign_province_jurisdictions(
            grid['owner'], province_records, parent_regions, cities,
            parent_seats=[jun['seat'] for jun in juns],
            jurisdiction_recoveries=recoveries,
        )
        province_records = list(assigned.province_records)
        jurisdiction_records = list(assigned.jurisdiction_records)
        commandery_records = list(assigned.commandery_records)
    # readings.json 이 있는데 빠진 이름이 있으면 조용히 한자로 흘리지 않는다 — 그게 이번에
    # 커밋에 한자 70건을 밀어넣은 사고다(#524 리뷰 HIGH-1). readings.json 이 아예 없을 때는
    # 위에서 이미 경고했으니 여기서는 있는데 불완전한 경우만 잡는다.
    if (readings_supplied or READINGS.exists()) and misses:
        sys.exit(f"readings.json 에 없는 지명 {len(misses)}개: {sorted(set(misses))[:10]}… "
                  "tools/map/build_readings.py 를 다시 돌려라(hanja 패키지 필요).")
    output = {
        "_meta": {
            "source": f"{GRID.name} + {PLACES.name}",
            "generator": "tools/map/build_tile_grid.py",
            "note": "지형·소유는 렌더러 산출물 그대로다. 이 파일은 압축·비트마스크·라벨만 더한다.",
            "cols": cols, "rows": rows,
            "year": grid["year"],
            "projection": grid["projection"],
            "terrainLegend": grid["legend"],
            "ownerEncoding": "run-length [[provinceIndex, count], …] · row-major · -1 = 바다·비플레이 영역",
            "counts": {"cities": len(cities), "seats": len(hubs), "regions": len(regions),
                       "adjCounty": len(grid["adjacency"]["county"]),
                       "adjCommandery": len(grid["adjacency"]["commandery"]), **kinds},
        },
        "terrain": ["".join(str(v) for v in row) for row in grid["terrain"]],
        "owner": owner,
        "regions": regions,
        # 이동 그래프. 길이 아니라 영역 인접이다.
        "adjacency": grid["adjacency"],
        "juns": juns,
        "seatOwner": rle(grid["seatOwner"]),
        "cities": cities,
    }
    adjudications_document = None
    if province_records is not None and parent_regions is not None:
        output['provinceRecords'] = province_records
        output['jurisdictionRecords'] = jurisdiction_records
        output['commanderyRecords'] = commandery_records
        output['parentRegions'] = parent_regions
        output['parentOwner'] = rle(grid['parentOwner'])
        adjudications_document = jurisdiction_parent_adjudications_document
        if adjudications_document is None:
            adjudications_document = json.loads(
                JURISDICTION_PARENT_ADJUDICATIONS.read_text(encoding='utf-8'))
        # Keep the full legacy route-node surface together. Its indices are a
        # separate immutable namespace; mixing only the old seatOwner with the
        # new city/jun arrays creates duplicate seats and invalid references.
        if legacy_tiles is not None:
            output['legacyGameplay'] = {
                'cities': legacy_tiles['cities'],
                'juns': legacy_tiles['juns'],
                'seatOwner': legacy_tiles['seatOwner'],
                'activeParentNames': active_gameplay_parent_names,
            }
        else:
            output['legacyGameplay'] = {
                'cities': output['cities'],
                'juns': output['juns'],
                # Pre-adjudication by construction: the ledger is applied to the
                # normalized artifact below, after this legacy-shaped copy is taken.
                'seatOwner': output['parentOwner'],
            }
        output['_meta']['counts']['provinces'] = len(province_records)
        output['_meta']['counts']['jurisdictions'] = len(jurisdiction_records)
        output['_meta']['counts']['commanderies'] = len(commandery_records)
        output['_meta']['counts']['parentRegions'] = len(parent_regions)
    result = normalize_han_place_names(output)
    if adjudications_document is not None:
        # Reviewed parent moves are applied to the assembled, name-normalized
        # artifact — the same shape the materializer patches — so a full
        # regeneration re-derives parentOwner and the commandery graph from the
        # same ledger and a rebuild cannot drop the review.
        apply_jurisdiction_parent_adjudications(result, adjudications_document)
    return result


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--check", action="store_true")
    args = ap.parse_args()
    for src in (GRID, PLACES):
        if not src.exists():
            sys.exit(f"{src.relative_to(ROOT)} 가 없다. tools/map/build_terrain_grid.py 를 먼저 돌려라.")
    grid_document = json.loads(GRID.read_text())
    places_document = json.loads(PLACES.read_text())
    try:
        validate_canonical_inputs(
            grid_document, places_document, readings_exists=READINGS.exists()
        )
    except ValueError as exc:
        sys.exit(f'canonical Han tile input contract failed: {exc}')
    blob = json.dumps(build(), ensure_ascii=False, separators=(",", ":")) + "\n"
    if args.check:
        if OUT.exists() and OUT.read_text() == blob:
            print("드리프트 없음.")
            return 0
        print(f"드리프트: {OUT.relative_to(ROOT)}")
        return 1
    OUT.write_text(blob)
    meta = json.loads(blob)["_meta"]["counts"]
    print(f"{OUT.relative_to(ROOT)} · {len(blob)/1e6:.1f}MB · " +
          " · ".join(f"{k} {v}" for k, v in meta.items()))
    return 0


if __name__ == "__main__":
    sys.exit(main())
