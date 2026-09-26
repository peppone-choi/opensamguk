#!/usr/bin/env python3
"""Pin every city point moved by the reviewed map4 boundary and carve changes."""
import argparse
import gzip
import hashlib
import json
import sys
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from tools.map.korea_map_extension import base_frame  # noqa: E402

PRIOR = ROOT / 'data/map/han-world-v3-1447-artifacts-v1'
TILES = ROOT / 'data/map/han-tiles.json'
WORLD = ROOT / 'infra/src/main/resources/map/han-world-v3.json'
OUTPUT = ROOT / 'data/curated/han/province-relocations-map4-v1.json'
LABEL = {1: '수', 2: '진', 3: '관', 4: '이'}


def sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def frozen(path: str) -> tuple[dict, str]:
    catalog = json.loads((PRIOR / 'catalog.json').read_bytes())
    entry = next(row for row in catalog['files'] if row['path'] == path)
    compressed = (PRIOR / entry['blob']).read_bytes()
    if sha(compressed) != entry['compressedSha256']:
        raise ValueError(f'frozen compressed blob drift: {path}')
    data = gzip.decompress(compressed)
    if sha(data) != entry['sha256'] or len(data) != entry['bytes']:
        raise ValueError(f'frozen payload drift: {path}')
    return json.loads(data), sha(data)


def has_through_pair(city: dict, cities: dict[int, dict]) -> bool:
    neighbours = city['connections']
    return any(b not in cities[a]['connections'] for ai, a in enumerate(neighbours)
               for b in neighbours[ai + 1:])


def build() -> dict:
    old_tiles, old_tiles_sha = frozen('data/map/han-tiles.json')
    old_world, old_world_sha = frozen('infra/src/main/resources/map/han-world-v3.json')
    tiles_bytes, world_bytes = TILES.read_bytes(), WORLD.read_bytes()
    new_tiles = base_frame(json.loads(tiles_bytes))
    new_world = json.loads(world_bytes)
    old_points = {row['id']: row for row in old_tiles['cities']}
    old_cities = {row['id']: row for row in old_world['cities']}
    new_cities = {row['id']: row for row in new_world['cities']}
    old_by_province = {row['spatialProvinceId']: row for row in old_world['cities']}
    new_by_province = {row['spatialProvinceId']: row for row in new_world['cities']}
    rows = []
    for point in new_tiles['cities']:
        old = old_points.get(point['id'])
        if old is None or (old['row'], old['col']) == (point['row'], point['col']):
            continue
        before = old_by_province.get(point['id'])
        after = new_by_province.get(point['id'])
        if before is None or after is None or before['id'] != after['id']:
            raise ValueError(f'moved point lacks one-to-one game city: {point["id"]}')
        level = after['level']
        if point['id'] == 'ss-tajin':
            reason = 'ATTESTED_COASTAL_LANDING_AND_SEA_ROUTE'
        elif point['id'].startswith('ss-'):
            reason = 'BOUNDARY_FERRY_PASS_OR_FORT_WITH_TWO_DRY_EXITS'
        elif point['id'].startswith('gc-'):
            reason = 'SAME_COMMANDERY_BOUNDARY_CORE_FOR_GROWTH_AND_EXIT'
        else:
            raise ValueError(f'unreviewed physical city move: {point["id"]}')
        delta_row, delta_col = point['row'] - old['row'], point['col'] - old['col']
        rows.append({
            'placeId': point['id'], 'gameCityId': after['id'], 'name': after['name'],
            'level': level, 'levelName': LABEL.get(level, '성'),
            'oldCell': {'row': old['row'], 'col': old['col']},
            'newCell': {'row': point['row'], 'col': point['col']},
            'baseCellDistance': round((delta_row ** 2 + delta_col ** 2) ** 0.5, 2),
            'coordinateBasis': point.get('locationBasis', old.get('locationBasis')),
            'strategicReason': reason,
            'movementGraph': {
                'oldNeighbourCityIds': sorted(before['connections']),
                'newNeighbourCityIds': sorted(after['connections']),
                'oldThroughNeighbourPair': has_through_pair(before, old_cities),
                'newThroughNeighbourPair': has_through_pair(after, new_cities),
            },
        })
    rows.sort(key=lambda row: row['placeId'])
    return {
        'schemaVersion': 1, 'ledgerId': 'province-relocations-map4-v1',
        'authority': '2026-09-24 user approval: strategic relocation, universal 7x7 growth core, no enclosed province',
        'sourcePins': {'frozenTilesSha256': old_tiles_sha, 'frozenWorldSha256': old_world_sha,
                       'map4TilesSha256': sha(tiles_bytes), 'map4WorldSha256': sha(world_bytes)},
        'counts': {'moved': len(rows), 'byLevel': dict(sorted(Counter(row['levelName'] for row in rows).items())),
                   'throughPairGained': sum(not row['movementGraph']['oldThroughNeighbourPair'] and
                                            row['movementGraph']['newThroughNeighbourPair'] for row in rows)},
        'relocations': rows,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument('--write', action='store_true')
    mode.add_argument('--check', action='store_true')
    args = parser.parse_args()
    data = (json.dumps(build(), ensure_ascii=False, indent=2) + '\n').encode()
    if args.write:
        OUTPUT.write_bytes(data)
    matched = OUTPUT.is_file() and OUTPUT.read_bytes() == data
    if not matched:
        print('province relocation ledger drift')
    else:
        print(f'province relocations pinned: {json.loads(data)["counts"]}')
    return int(not matched)


if __name__ == '__main__':
    raise SystemExit(main())
