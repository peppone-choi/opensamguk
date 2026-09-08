#!/usr/bin/env python3
"""Reversible, source-pinned local relocation of Geuk province 85272.

The new boundary is an adapted local partition, not a reconstructed historical
boundary. Only the old Geuk mask and the existing Anqiu mask can change owners.
"""
from __future__ import annotations

import argparse
import copy
import hashlib
import heapq
import json
import math
from collections import Counter
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from tools.map import adjudicate_han_province_fragments as fragments
from tools.map.build_terrain_grid import Proj, adjacency
from tools.map.world_province_geometry import _rederive_parent_surfaces
import numpy as np

TILES = ROOT / 'data/map/han-tiles.json'
LEDGER = ROOT / 'data/curated/han/province-relocations-v1.json'
INPUT_SHA256 = '237b8f1d8a8228a89fa251b4020ef254c2176aedd4ba3133c4996a630bd07a63'
INPUT_DOCUMENT_SHA256 = '4e0ced946d84c91344591d59c66e08ae8caacf21e685cf7e0df0691367a13e53'
OUTPUT_DOCUMENT_SHA256 = '0c036d9f13999c9883e8125e7cdf5f07f230f906d447f13fcbf0f30211cc376c'
RELOCATION = {
    'provinceId': '85272', 'destinationDonorId': '85325',
    'excludedBackfillIds': ['85217'], 'lon': 118.77496, 'lat': 36.65675,
    'sourceUrl': 'https://namu.wiki/w/삼국지/지명/청주',
    'sourceLedger': 'data/curated/han/namu-place-locations-v1.json',
    'canonicalGroup': '北海國', 'sourceName': '劇',
    'geometryBasis': 'SOURCE_SEAT_LOCAL_ADAPTATION',
}


def digest(document: dict) -> str:
    return hashlib.sha256(json.dumps(document, ensure_ascii=False, sort_keys=True,
                                     separators=(',', ':')).encode()).hexdigest()


def owner_values(document: dict) -> list[int]:
    meta = document['_meta']
    grid = fragments.expand_rle(document['owner'], meta['rows'], meta['cols'])
    return [value for row in grid for value in row]


def neighbors(pos: int, cols: int, count: int):
    row, col = divmod(pos, cols)
    if col:
        yield pos - 1
    if col + 1 < cols:
        yield pos + 1
    if row:
        yield pos - cols
    if pos + cols < count:
        yield pos + cols


def component_count(owner: list[int], index: int, cols: int) -> int:
    remaining = {pos for pos, value in enumerate(owner) if value == index}
    count = 0
    while remaining:
        count += 1
        queue = [remaining.pop()]
        while queue:
            for pos in neighbors(queue.pop(), cols, len(owner)):
                if pos in remaining:
                    remaining.remove(pos)
                    queue.append(pos)
    return count


def _refresh(document: dict, owner: list[int]) -> None:
    meta = document['_meta']
    cols, rows = meta['cols'], meta['rows']
    grid = [owner[offset:offset + cols] for offset in range(0, len(owner), cols)]
    document['owner'] = fragments.encode_rle(grid)
    document['adjacency']['county'] = adjacency(np.asarray(grid, dtype=np.int32), min_shared_edges=1)
    # Terrain and all commandery seats are frozen. Existing touching pairs keep
    # their prior path verdict; only newly touching pairs need pathfinding.
    _rederive_parent_surfaces(document)


def _candidate(source: dict) -> tuple[dict, list[dict]]:
    if digest(source) != INPUT_DOCUMENT_SHA256:
        raise ValueError('candidate source differs from frozen input document')
    document = copy.deepcopy(source)
    records = document['provinceRecords']
    by_id = {row['id']: i for i, row in enumerate(records)}
    if len(by_id) != len(records):
        raise ValueError('duplicate province IDs')
    target, donor = by_id['85272'], by_id['85325']
    excluded = {by_id[value] for value in RELOCATION['excludedBackfillIds']}
    old = owner_values(document)
    owner = list(old)
    cols = document['_meta']['cols']
    target_city = document['cities'][records[target]['cityIndex']]
    donor_city = document['cities'][records[donor]['cityIndex']]
    if (target_city['lon'], target_city['lat']) != (RELOCATION['lon'], RELOCATION['lat']):
        raise ValueError('Geuk coordinate differs from reviewed source')
    projected = Proj(document['_meta']['projection']).to_cell(target_city['lon'], target_city['lat'])
    target_col, target_row = map(math.floor, projected)
    if old[target_row * cols + target_col] != donor:
        raise ValueError('projected Geuk point is outside reviewed donor')
    donor_point = (donor_city['col'] + .5, donor_city['row'] + .5)
    source_mask = {pos for pos, value in enumerate(old) if value == target}
    queue = []
    for pos in sorted(source_mask):
        for adjacent in neighbors(pos, cols, len(old)):
            index = old[adjacent]
            if index >= 0 and index != target and index not in excluded:
                heapq.heappush(queue, (1, records[index]['id'], pos, index))
    assigned = set()
    while queue:
        distance, stable_id, pos, index = heapq.heappop(queue)
        if pos in assigned:
            continue
        assigned.add(pos)
        owner[pos] = index
        for adjacent in neighbors(pos, cols, len(old)):
            if adjacent in source_mask and adjacent not in assigned:
                heapq.heappush(queue, (distance + 1, stable_id, adjacent, index))
    if assigned != source_mask:
        raise ValueError('old province has unfillable cells')
    for pos, index in enumerate(old):
        if index != donor:
            continue
        x, y = pos % cols + .5, pos // cols + .5
        target_distance = (x - projected[0]) ** 2 + (y - projected[1]) ** 2
        donor_distance = (x - donor_point[0]) ** 2 + (y - donor_point[1]) ** 2
        if (target_distance, records[target]['id']) < (donor_distance, records[donor]['id']):
            owner[pos] = target
    target_city['col'], target_city['row'] = target_col, target_row
    areas = Counter(owner)
    if any(areas[index] < 8 for index in range(len(records))):
        raise ValueError('relocation violates minimum province area 8')
    for index in (target, donor):
        city = document['cities'][records[index]['cityIndex']]
        if component_count(owner, index, cols) != 1 or owner[city['row'] * cols + city['col']] != index:
            raise ValueError('relocated/donor province must stay connected and contain its seat')
    delta = [{'col': pos % cols, 'row': pos // cols,
              'before': records[before]['id'], 'after': records[after]['id']}
             for pos, (before, after) in enumerate(zip(old, owner)) if before != after]
    if len(delta) != 61 or areas[target] != 36 or areas[donor] != 46:
        raise ValueError('local partition differs from reviewed 61-cell result')
    _refresh(document, owner)
    return document, delta


def _cell_digest(cells: set[int]) -> str:
    return hashlib.sha256(json.dumps(sorted(cells), separators=(',', ':')).encode()).hexdigest()


def _territory_projection(before: dict, after: dict, rows: list[dict]) -> dict:
    from tools.map import audit_territory_disconnections as audit
    cols, height = before['_meta']['cols'], before['_meta']['rows']
    parent_index = next(i for i, row in enumerate(before['parentRegions']) if row['id'] == 'PARENT-0038')
    def components(document):
        grid = audit._expand(document['parentOwner'], cols * height, 'parentOwner')
        return {audit._anchor(cells, cols): set(cells)
                for cells in audit._components(grid, cols, height)[parent_index]}
    old, new = components(before), components(after)
    source_row = next(row for row in rows if row['componentKey'] == 'PARENT-0038@498:182')
    old_owner = owner_values(before)
    geuk = next(i for i, row in enumerate(before['provinceRecords']) if row['id'] == '85272')
    removed = {pos for pos, value in enumerate(old_owner) if value == geuk}
    if old['498:182'] != new['498:182'] or old['457:176'] - removed != new['456:178']:
        raise ValueError('territory projection is not the exact seat-body swap')
    if len(old['498:182']) != 549 or len(old['457:176']) != 48 or len(new['456:178']) != 23:
        raise ValueError('territory projection cell counts changed')
    if source_row['verdict'] != 'GEOMETRY_DEFECT' or source_row['ifRule'] != 'DEFECT_PRESERVE_PENDING_GEOMETRY_PR':
        raise ValueError('prior territory review does not establish the geometry defect')
    after_component = next(row for row in audit.inventory(after) if row['componentKey'] == 'PARENT-0038@456:178')
    if after_component['memberIds'] != ['85217']:
        raise ValueError('residual component is not exactly Dong Anping')
    return {'basis': 'DERIVED_FROM_EXISTING_REVIEW',
            'sourceLedger': 'data/curated/han/territory-disconnection-adjudications-v1.json',
            'sourceComponentKey': source_row['componentKey'], 'sourceRowSha256': digest(source_row),
            'unchangedBodyCellsSha256': _cell_digest(old['498:182']),
            'priorSeatBodyCellsSha256': _cell_digest(old['457:176']),
            'residualCellsSha256': _cell_digest(new['456:178']),
            'removedGeukCells': [[pos % cols, pos // cols] for pos in sorted(removed)],
            'afterComponent': after_component}


def project_territory_rows(before: dict, after: dict, rows: list[dict], ledger: dict) -> list[dict]:
    projection = _territory_projection(before, after, rows)
    if projection != ledger['territoryProjection']:
        raise ValueError('territory projection differs from reviewed relocation ledger')
    # This transient row is used only to check geometry. The original review and
    # its votes remain byte-for-byte in their original ledger and are not recast
    # as a newly reviewed historical claim about the residual component.
    result = copy.deepcopy(rows)
    row = next(row for row in result if row['componentKey'] == projection['sourceComponentKey'])
    row.update({key: value for key, value in projection['afterComponent'].items()
                if key in row})
    return result


def prepare(source_bytes: bytes) -> tuple[dict, dict]:
    if hashlib.sha256(source_bytes).hexdigest() != INPUT_SHA256:
        raise ValueError('relocation requires the exact reviewed input tiles SHA256')
    source = json.loads(source_bytes)
    output, delta = _candidate(source)
    ledger = {'schemaVersion': 1, 'ledgerId': 'han-province-relocations-v1',
              'inputTilesSha256': INPUT_SHA256, 'inputDocumentSha256': digest(source),
              'outputDocumentSha256': digest(output), 'relocation': RELOCATION,
              'inputCityOrder': [row['id'] for row in source['cities']],
              'ownerDelta': delta, 'labelDelta': {'placeId': '85272', 'before': [462, 179], 'after': [490, 192]}}
    territory = json.loads((ROOT / 'data/curated/han/territory-disconnection-adjudications-v1.json').read_text())
    ledger['territoryProjection'] = _territory_projection(source, output, territory['adjudications'])
    return ledger, output


def canonicalize_city_order(document: dict, ledger: dict) -> dict:
    """Normalize only ID-preserving city-array order for reconciliation callers."""
    normalized = copy.deepcopy(document)
    cities = normalized['cities']
    order = ledger['inputCityOrder']
    by_id = {row['id']: row for row in cities}
    if len(by_id) != len(cities) or len(set(order)) != len(order) or set(by_id) != set(order):
        raise ValueError('city order normalization requires every unique pinned ID')
    positions = {city_id: index for index, city_id in enumerate(order)}
    for province in normalized['provinceRecords']:
        index = province.get('cityIndex')
        if index is not None:
            if type(index) is not int or not 0 <= index < len(cities):
                raise ValueError('invalid linked city index')
            province['cityIndex'] = positions[cities[index]['id']]
    normalized['cities'] = [by_id[city_id] for city_id in order]
    return normalized


def restore_document(document: dict, ledger: dict) -> dict:
    if ledger.get('inputDocumentSha256') != INPUT_DOCUMENT_SHA256:
        raise ValueError('ledger cannot replace the frozen input fingerprint')
    if ledger.get('outputDocumentSha256') != OUTPUT_DOCUMENT_SHA256 or digest(document) != OUTPUT_DOCUMENT_SHA256:
        raise ValueError('document is not the frozen reviewed relocation output')
    restored = copy.deepcopy(document)
    records = {row['id']: index for index, row in enumerate(restored['provinceRecords'])}
    owner = owner_values(restored)
    cols = restored['_meta']['cols']
    seen = set()
    for delta in ledger['ownerDelta']:
        pos = delta['row'] * cols + delta['col']
        if pos in seen or owner[pos] != records[delta['after']]:
            raise ValueError('relocation delta does not match output')
        seen.add(pos)
        owner[pos] = records[delta['before']]
    city = next(row for row in restored['cities'] if row['id'] == ledger['labelDelta']['placeId'])
    if [city['col'], city['row']] != ledger['labelDelta']['after']:
        raise ValueError('relocation label does not match output')
    city['col'], city['row'] = ledger['labelDelta']['before']
    _refresh(restored, owner)
    if digest(restored) != ledger['inputDocumentSha256']:
        raise ValueError('restored document differs from pinned prior stage')
    return restored


def relocate_document(document: dict, ledger: dict) -> dict:
    if ledger.get('schemaVersion') != 1 or ledger.get('relocation') != RELOCATION or ledger.get('inputTilesSha256') != INPUT_SHA256:
        raise ValueError('unsupported relocation ledger')
    if ledger.get('labelDelta') != {'placeId': '85272', 'before': [462, 179], 'after': [490, 192]}:
        raise ValueError('relocation label delta differs from reviewed change')
    source = document
    if digest(source) == ledger['outputDocumentSha256']:
        source = restore_document(source, ledger)
    if digest(source) != ledger['inputDocumentSha256']:
        raise ValueError('document is neither pinned input nor output')
    output, delta = _candidate(source)
    if delta != ledger['ownerDelta'] or digest(output) != ledger['outputDocumentSha256']:
        raise ValueError('relocation differs from reviewed output')
    return output


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, default=TILES)
    parser.add_argument('--ledger', type=Path, default=LEDGER)
    parser.add_argument('--output', type=Path)
    parser.add_argument('--prepare', action='store_true')
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    source_bytes = args.source.read_bytes()
    source = json.loads(source_bytes)
    if args.prepare:
        if args.check or args.output is None:
            parser.error('--prepare requires --output and cannot be used with --check')
        ledger, output = prepare(source_bytes)
        args.ledger.write_text(json.dumps(ledger, ensure_ascii=False, indent=2) + '\n')
    else:
        ledger = json.loads(args.ledger.read_text())
        output = relocate_document(source, ledger)
    if args.check:
        if output != source:
            print('relocation is not applied', file=sys.stderr)
            return 1
    elif args.output:
        args.output.write_text(json.dumps(output, ensure_ascii=False, separators=(',', ':')) + '\n')
    else:
        parser.error('provide --output or --check')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
