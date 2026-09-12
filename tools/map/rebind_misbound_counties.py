#!/usr/bin/env python3
"""Move mis-bound counties onto their corrected physical place, with local geometry.

Four counties carried the coordinates of a **same-named place somewhere else**
(see data/curated/han/county-misbinding-rebindings-v1.json for the source
records and why the 220 snapshot handed them over).  This applies the reviewed
correction to data/map/han-tiles.json:

  * the old province mask is dissolved into the neighbours it never belonged to,
  * a connected footprint is carved out of the province that owns the corrected
    seat cell, leaving that donor connected and still holding its own seat,
  * the city label, its cell and the province parent follow the corrected place.

The new boundary is an adapted local partition, not a reconstructed historical
boundary.  Only the old mask and the donor mask can change owners; the ledger
pins every changed cell so a later run cannot drift.
"""
from __future__ import annotations

import argparse
import copy
import hashlib
import heapq
import json
import math
import sys
from collections import Counter, deque
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from tools.map.build_terrain_grid import Proj, adjacency  # noqa: E402
from tools.map.world_province_geometry import _rederive_parent_surfaces  # noqa: E402

TILES = ROOT / 'data/map/han-tiles.json'
LEDGER = ROOT / 'data/curated/han/county-misbinding-rebindings-v1.json'
MINIMUM_AREA = 8
MAXIMUM_CARVE = 60


def digest(document: dict) -> str:
    return hashlib.sha256(json.dumps(document, ensure_ascii=False, sort_keys=True,
                                     separators=(',', ':')).encode()).hexdigest()


def expand(runs, rows: int, cols: int) -> np.ndarray:
    values, counts = zip(*((int(a), int(b)) for a, b in runs))
    grid = np.repeat(np.asarray(values, dtype=np.int32), counts)
    if grid.size != rows * cols:
        raise ValueError('owner run-length does not fill the grid')
    return grid.reshape(rows, cols)


def encode(grid: np.ndarray) -> list[list[int]]:
    runs: list[list[int]] = []
    for value in grid.ravel().tolist():
        if runs and runs[-1][0] == value:
            runs[-1][1] += 1
        else:
            runs.append([value, 1])
    return runs


def neighbours(row: int, col: int, rows: int, cols: int):
    if row:
        yield row - 1, col
    if row + 1 < rows:
        yield row + 1, col
    if col:
        yield row, col - 1
    if col + 1 < cols:
        yield row, col + 1


def connected(cells: set[tuple[int, int]], rows: int, cols: int) -> bool:
    if not cells:
        return False
    start = next(iter(cells))
    seen = {start}
    stack = [start]
    while stack:
        row, col = stack.pop()
        for cell in neighbours(row, col, rows, cols):
            if cell in cells and cell not in seen:
                seen.add(cell)
                stack.append(cell)
    return len(seen) == len(cells)


def _dissolve(owner: np.ndarray, index: int, records: list[dict]) -> None:
    """Give every cell of a province to its nearest surviving neighbour."""
    rows, cols = owner.shape
    mask = {(int(row), int(col)) for row, col in np.argwhere(owner == index)}
    queue: list[tuple[int, str, tuple[int, int], int]] = []
    for row, col in sorted(mask):
        for next_row, next_col in neighbours(row, col, rows, cols):
            other = int(owner[next_row, next_col])
            if other >= 0 and other != index:
                heapq.heappush(queue, (1, records[other]['id'], (row, col), other))
    assigned: dict[tuple[int, int], int] = {}
    while queue:
        distance, stable_id, cell, other = heapq.heappop(queue)
        if cell in assigned:
            continue
        assigned[cell] = other
        for neighbour in neighbours(cell[0], cell[1], rows, cols):
            if neighbour in mask and neighbour not in assigned:
                heapq.heappush(queue, (distance + 1, stable_id, neighbour, other))
    if set(assigned) != mask:
        raise ValueError('the old province has cells no neighbour can absorb')
    for cell, other in assigned.items():
        owner[cell] = other


def _carve(owner: np.ndarray, index: int, seat: tuple[int, int],
           donor: int, donor_seat: tuple[int, int] | None) -> set[tuple[int, int]]:
    """Grow a connected footprint from the seat, never disconnecting the donor."""
    rows, cols = owner.shape
    donor_cells = {(int(row), int(col)) for row, col in np.argwhere(owner == donor)}
    wanted = max(MINIMUM_AREA, min(len(donor_cells) // 2, MAXIMUM_CARVE))
    carved = {seat}
    remainder = donor_cells - carved
    while len(carved) < wanted:
        frontier = sorted(
            {cell for taken in carved
             for cell in neighbours(taken[0], taken[1], rows, cols)
             if cell in remainder},
            key=lambda cell: ((cell[0] - seat[0]) ** 2 + (cell[1] - seat[1]) ** 2, cell),
        )
        for candidate in frontier:
            if candidate == donor_seat:
                continue
            if connected(remainder - {candidate}, rows, cols):
                carved.add(candidate)
                remainder.discard(candidate)
                break
        else:
            break
    if len(carved) < MINIMUM_AREA or len(remainder) < MINIMUM_AREA:
        raise ValueError('carving cannot leave both provinces at the minimum area')
    for cell in carved:
        owner[cell] = index
    return carved


def _reattribute(document: dict, owner: np.ndarray, province_id: str, parent_id: str) -> str:
    """Bind an orphaned direct fragment to the county it shares the most boundary with."""
    rows, cols = owner.shape
    records = document['provinceRecords']
    index_by_id = {record['id']: index for index, record in enumerate(records)}
    index = index_by_id[province_id]
    shared: Counter[int] = Counter()
    for row, col in np.argwhere(owner == index):
        for next_row, next_col in neighbours(int(row), int(col), rows, cols):
            other = int(owner[next_row, next_col])
            if other < 0 or other == index:
                continue
            if (records[other]['parentRegionId'] == parent_id
                    and records[other].get('cityIndex') is not None):
                shared[other] += 1
    if not shared:
        raise ValueError(f'{province_id} touches no county inside {parent_id}')
    best = min(shared.items(), key=lambda row: (-row[1], records[row[0]]['id']))[0]
    return records[best]['jurisdictionId']


def apply_rebindings(source: dict, ledger: dict) -> tuple[dict, list[dict], list[dict]]:
    document = copy.deepcopy(source)
    meta = document['_meta']
    rows, cols = meta['rows'], meta['cols']
    projection = Proj(meta['projection'])
    owner = expand(document['owner'], rows, cols)
    before = owner.copy()
    records = document['provinceRecords']
    cities = document['cities']
    index_by_id = {record['id']: index for index, record in enumerate(records)}
    jurisdiction_by_id = {row['id']: row for row in document['jurisdictionRecords']}
    commandery_by_id = {row['id']: row for row in document['commanderyRecords']}
    labels: list[dict] = []
    for rebinding in ledger['rebindings']:
        key = rebinding['runtimePlaceKey']
        corrected = rebinding['correctedPhysicalPlace']
        incorrect = rebinding['incorrectPhysicalPlace']
        index = index_by_id[key]
        record = records[index]
        city = cities[record['cityIndex']]
        if (city['lon'], city['lat']) != (incorrect['lon'], incorrect['lat']):
            raise ValueError(f'{key}: city no longer carries the mis-bound coordinate')
        column, row = projection.to_cell(corrected['lon'], corrected['lat'])
        seat = (math.floor(row), math.floor(column))
        if not (0 <= seat[0] < rows and 0 <= seat[1] < cols):
            raise ValueError(f'{key}: corrected place falls outside the grid')
        donor = int(owner[seat])
        if donor < 0:
            raise ValueError(f'{key}: corrected place falls on unplayable ground')
        destination = rebinding['destinationParentRegionId']
        if records[donor]['parentRegionId'] != destination:
            raise ValueError(f'{key}: corrected place is not inside {destination}')
        origin_parent = record['parentRegionId']
        _dissolve(owner, index, records)
        donor_city = records[donor].get('cityIndex')
        donor_seat = None
        if donor_city is not None:
            donor_seat = (cities[donor_city]['row'], cities[donor_city]['col'])
        _carve(owner, index, seat, donor, donor_seat)
        labels.append({
            'runtimePlaceKey': key,
            'before': {'col': city['col'], 'row': city['row'],
                       'lon': city['lon'], 'lat': city['lat'],
                       'parentRegionId': origin_parent},
            'after': {'col': seat[1], 'row': seat[0],
                      'lon': corrected['lon'], 'lat': corrected['lat'],
                      'parentRegionId': destination},
        })
        city['col'], city['row'] = seat[1], seat[0]
        city['lon'], city['lat'] = corrected['lon'], corrected['lat']
        record['parentRegionId'] = destination
        record['geometryBasis'] = 'CORRECTED_PHYSICAL_PLACE_LOCAL_ADAPTATION'
        jurisdiction = jurisdiction_by_id[record['jurisdictionId']]
        jurisdiction['commanderyId'] = destination
        stranded = [value for value in jurisdiction['provinceIds'] if value != key]
        for province_id in stranded:
            receiver = _reattribute(document, owner, province_id, origin_parent)
            jurisdiction['provinceIds'] = [
                value for value in jurisdiction['provinceIds'] if value != province_id
            ]
            jurisdiction_by_id[receiver]['provinceIds'] = sorted(
                set(jurisdiction_by_id[receiver]['provinceIds']) | {province_id}
            )
            moved = records[index_by_id[province_id]]
            moved['jurisdictionId'] = receiver
            moved['assignmentBasis'] = 'MAX_SHARED_BOUNDARY'
            moved['assignmentConfidence'] = 'INFERRED'
        origin = commandery_by_id[origin_parent]
        origin['jurisdictionIds'] = [
            value for value in origin['jurisdictionIds'] if value != jurisdiction['id']
        ]
        target = commandery_by_id[destination]
        target['jurisdictionIds'] = sorted(set(target['jurisdictionIds']) | {jurisdiction['id']})
        if origin.get('seatJurisdictionId') == jurisdiction['id']:
            raise ValueError(f'{key}: a commandery seat cannot be relocated by this ledger')
        supersede = rebinding.get('supersedesJurisdictionSeatRecovery')
        if supersede is not None:
            _supersede_seat_recovery(document, records, index_by_id, jurisdiction_by_id,
                                     jurisdiction, target, seat, key, supersede)

    areas = Counter(owner.ravel().tolist())
    baseline = Counter(before.ravel().tolist())
    for province_index in range(len(records)):
        if areas[province_index] < min(MINIMUM_AREA, baseline[province_index]):
            raise ValueError(f'{records[province_index]["id"]} fell below its own minimum area')
    for rebinding in ledger['rebindings']:
        index = index_by_id[rebinding['runtimePlaceKey']]
        city = cities[records[index]['cityIndex']]
        if int(owner[city['row'], city['col']]) != index:
            raise ValueError('a relocated city must stand on its own province')
    delta = [{'col': position % cols, 'row': position // cols,
              'before': records[old]['id'], 'after': records[new]['id']}
             for position, (old, new) in enumerate(zip(before.ravel().tolist(),
                                                       owner.ravel().tolist()))
             if old != new]
    document['owner'] = encode(owner)
    document['adjacency']['county'] = adjacency(owner, min_shared_edges=1)
    _rederive_parent_surfaces(document)
    return document, delta, labels



def _supersede_seat_recovery(document, records, index_by_id, jurisdiction_by_id,
                             jurisdiction, target, seat, key, supersede):
    """치소 縣이 실물로 들어오면, 그 자리를 대신 지키던 임시 관할을 접는다.

    `jurisdiction-seat-recoveries-v1.json` 은 縣이 하나도 없는 郡에 치소 縣 관할을
    세워 둔 원장이다. 재바인딩으로 그 縣이 제자리에 실제로 서면 임시 관할은 같은
    칸에 선 같은 이름의 중복이 되고, 제 seat 가 제 省 밖에 놓인다. 임시 관할의 省을
    실물 縣 관할로 넘기고 郡의 치소 관할을 실물로 바꾼다. 되돌리면 그대로 살아난다.
    """
    stand_in = jurisdiction_by_id.get(supersede['jurisdictionId'])
    if stand_in is None:
        raise ValueError(f"{key}: stand-in jurisdiction {supersede['jurisdictionId']} is absent")
    if stand_in['seatPlaceId'] != supersede['seatPlaceId']:
        raise ValueError(f'{key}: stand-in jurisdiction carries a different seat place')
    if stand_in['commanderyId'] != target['id']:
        raise ValueError(f'{key}: stand-in jurisdiction belongs to another commandery')
    place = next((row for row in document['cities'] if row.get('id') == stand_in['seatPlaceId']), None)
    if place is None:
        raise ValueError(f'{key}: stand-in seat place is not on the map')
    if (place['row'], place['col']) != seat:
        raise ValueError(f'{key}: stand-in seat does not stand on the relocated county cell')
    jurisdiction['provinceIds'] = sorted(set(jurisdiction['provinceIds']) | set(stand_in['provinceIds']))
    for province_id in stand_in['provinceIds']:
        moved = records[index_by_id[province_id]]
        moved['jurisdictionId'] = jurisdiction['id']
        moved['assignmentBasis'] = 'SUPERSEDED_SEAT_RECOVERY'
        moved['assignmentConfidence'] = 'INFERRED'
    target['jurisdictionIds'] = [value for value in target['jurisdictionIds']
                                 if value != stand_in['id']]
    if target.get('seatJurisdictionId') == stand_in['id']:
        target['seatJurisdictionId'] = jurisdiction['id']
    document['jurisdictionRecords'] = [row for row in document['jurisdictionRecords']
                                       if row['id'] != stand_in['id']]
    del jurisdiction_by_id[stand_in['id']]


RECORD_COLLECTIONS = ('provinceRecords', 'jurisdictionRecords', 'commanderyRecords')


def _record_delta(source: dict, document: dict) -> tuple[list[dict], list[dict]]:
    """세 원장과 城 목록에서 바뀐 필드만 뽑는다. owner/parentOwner/adjacency 는 파생이라 뺀다."""
    delta: list[dict] = []
    removals: list[dict] = []
    for collection in RECORD_COLLECTIONS:
        before = {row['id']: row for row in source[collection]}
        after = {row['id']: row for row in document[collection]}
        if set(after) - set(before):
            raise ValueError(f'{collection} gained rows')
        for index, row in enumerate(source[collection]):
            if row['id'] not in after:
                removals.append({'collection': collection, 'index': index, 'row': row})
        for key in sorted(before):
            if key not in after:
                continue
            for field in sorted(set(before[key]) | set(after[key])):
                if before[key].get(field) != after[key].get(field):
                    delta.append({'collection': collection, 'id': key, 'field': field,
                                  'before': before[key].get(field), 'after': after[key].get(field)})
    if len(source['cities']) != len(document['cities']):
        raise ValueError('cities gained or lost rows')
    for index, (before_city, after_city) in enumerate(zip(source['cities'], document['cities'])):
        for field in sorted(set(before_city) | set(after_city)):
            if before_city.get(field) != after_city.get(field):
                delta.append({'collection': 'cities', 'id': index, 'field': field,
                              'before': before_city.get(field), 'after': after_city.get(field)})
    return delta, removals


def restore_document(document: dict, ledger: dict) -> dict:
    """재바인딩을 되돌려 이 단계의 입력 문서로 돌아간다. 지문이 맞아야만 한다."""
    geometry = ledger['geometry']
    stage = next((row for row in geometry['stages']
                  if row['outputDocumentSha256'] == digest(document)), None)
    if stage is None:
        raise ValueError('document is not a pinned rebinding output')
    restored = copy.deepcopy(document)
    meta = restored['_meta']
    owner = expand(restored['owner'], meta['rows'], meta['cols'])
    index_by_id = {record['id']: index for index, record in enumerate(restored['provinceRecords'])}
    for entry in geometry['ownerDelta']:
        owner[entry['row'], entry['col']] = index_by_id[entry['before']]
    for entry in sorted(geometry.get('recordRemovals', []), key=lambda row: row['index']):
        restored[entry['collection']].insert(entry['index'], copy.deepcopy(entry['row']))
    rows_by_id = {collection: {row['id']: row for row in restored[collection]}
                  for collection in RECORD_COLLECTIONS}
    for entry in geometry['recordDelta']:
        row = (restored['cities'][entry['id']] if entry['collection'] == 'cities'
               else rows_by_id[entry['collection']][entry['id']])
        if row.get(entry['field']) != entry['after']:
            raise ValueError(f"{entry['collection']}/{entry['id']}/{entry['field']} is not the rebound value")
        row[entry['field']] = entry['before']
    restored['owner'] = encode(owner)
    restored['adjacency']['county'] = adjacency(owner, min_shared_edges=1)
    _rederive_parent_surfaces(restored)
    if digest(restored) != stage['inputDocumentSha256']:
        raise ValueError('restored document differs from the pinned rebinding input')
    return restored


def check(document: dict, ledger: dict) -> list[str]:
    """Report every way the artifact drifts from the reviewed rebinding."""
    problems: list[str] = []
    geometry = ledger.get('geometry')
    if not geometry:
        return ['ledger carries no prepared geometry']
    if not any(row['outputDocumentSha256'] == digest(document) for row in geometry['stages']):
        problems.append('han-tiles.json is not the reviewed rebinding output')
    meta = document['_meta']
    owner = expand(document['owner'], meta['rows'], meta['cols'])
    records = document['provinceRecords']
    index_by_id = {record['id']: index for index, record in enumerate(records)}
    for entry in geometry['ownerDelta']:
        index = int(owner[entry['row'], entry['col']])
        if index < 0 or records[index]['id'] != entry['after']:
            problems.append(f"cell {entry['col']},{entry['row']} is not {entry['after']}")
    for label in geometry['labelDelta']:
        record = records[index_by_id[label['runtimePlaceKey']]]
        city = document['cities'][record['cityIndex']]
        after = label['after']
        if [city['col'], city['row'], city['lon'], city['lat']] != [
                after['col'], after['row'], after['lon'], after['lat']]:
            problems.append(f"{label['runtimePlaceKey']} does not stand on its corrected place")
        if record['parentRegionId'] != after['parentRegionId']:
            problems.append(f"{label['runtimePlaceKey']} is not inside {after['parentRegionId']}")
    return problems


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, default=TILES)
    parser.add_argument('--ledger', type=Path, default=LEDGER)
    parser.add_argument('--output', type=Path)
    parser.add_argument('--prepare', action='store_true')
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    source = json.loads(args.source.read_text(encoding='utf-8'))
    ledger = json.loads(args.ledger.read_text(encoding='utf-8'))
    if args.check:
        problems = check(source, ledger)
        for problem in problems:
            print(problem, file=sys.stderr)
        return 1 if problems else 0
    document, delta, labels = apply_rebindings(source, ledger)
    records, removals = _record_delta(source, document)
    if args.prepare:
        geometry = ledger.get('geometry') or {}
        fresh = {'ownerDelta': delta, 'labelDelta': labels, 'recordDelta': records,
                 'recordRemovals': removals}
        for field, value in fresh.items():
            if field in geometry and geometry[field] != value:
                raise SystemExit(f'{field} differs from the reviewed rebinding')
        stage = {'inputDocumentSha256': digest(source),
                 'outputDocumentSha256': digest(document),
                 'outputCityOrder': [row['id'] for row in document['cities']]}
        stages = [row for row in geometry.get('stages', [])
                  if row['inputDocumentSha256'] != stage['inputDocumentSha256']]
        geometry.update(fresh)
        geometry['stages'] = sorted(stages + [stage], key=lambda row: row['inputDocumentSha256'])
        ledger['geometry'] = geometry
        args.ledger.write_text(json.dumps(ledger, ensure_ascii=False, indent=2) + '\n',
                               encoding='utf-8')
    elif not any(row['outputDocumentSha256'] == digest(document)
                 for row in ledger.get('geometry', {}).get('stages', [])):
        raise SystemExit('rebinding differs from the reviewed output')
    if args.output:
        args.output.write_text(json.dumps(document, ensure_ascii=False,
                                          separators=(',', ':')) + '\n', encoding='utf-8')
    elif not args.prepare:
        parser.error('provide --output, --prepare or --check')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
