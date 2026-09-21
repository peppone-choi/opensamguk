#!/usr/bin/env python3
"""Apply reviewed Korean-region seat corrections after the lowland stage.

The ledger reverses every changed field so earlier historical stages retain their
original input pins. Seat provinces may exchange geometry only within the same
jurisdiction; political boundaries and stable IDs must not change.
"""
from __future__ import annotations
import argparse
import copy
import hashlib
import json
import sys
from pathlib import Path
import numpy as np
ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
TILES = ROOT / 'data/map/han-tiles.json'
DECISIONS = ROOT / 'data/curated/han/korea-place-corrections-v1.json'
LEDGER = ROOT / 'data/curated/han/korea-place-correction-stage-v1.json'

def digest(value):
    return hashlib.sha256(json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(',', ':')).encode()).hexdigest()

def apply(source, decisions):
    from tools.map.measure_province_seat_offset import expand_rle, project_cell
    from tools.map.build_terrain_grid import adjacency
    from tools.map.world_province_geometry import _encode_runs, _rederive_parent_surfaces
    doc = copy.deepcopy(source)
    meta = doc['_meta']
    owner = expand_rle(doc['owner'], meta['rows'], meta['cols'])
    if decisions.get('extension'):
        from tools.map.korea_map_extension import apply as extend
        owner = extend(doc, owner, decisions['extension'])
    changed_parents = set()
    for decision in decisions['decisions']:
        pid = decision['id']
        city_index = next(i for i, r in enumerate(doc['cities']) if r['id'] == pid)
        province_index = next(i for i, r in enumerate(doc['provinceRecords']) if r['cityIndex'] == city_index)
        province = doc['provinceRecords'][province_index]
        col, row = map(int, project_cell(meta['projection'], decision['lat'], decision['lon']))
        target = int(owner[row, col])
        if target < 0 or doc['provinceRecords'][target]['jurisdictionId'] != province['jurisdictionId']:
            raise ValueError(f'{pid}: moving outside the reviewed jurisdiction needs a separate boundary decision')
        if target != province_index:
            if doc['provinceRecords'][target]['cityIndex'] is not None:
                raise ValueError(f'{pid}: destination already has a seat')
            original = owner.copy()
            owner[original == province_index] = target
            owner[original == target] = province_index
        doc['cities'][city_index].update(lon=decision['lon'], lat=decision['lat'], col=col, row=row)
        province.update(confidence=decision['confidence'], assignmentConfidence=decision['confidence'])
        for i, jun in enumerate(doc['juns']):
            if jun['seat'] == city_index:
                jun.update(col=col, row=row)
                changed_parents.add(i)
    from tools.map.korea_settlement_layout import add_settlements
    owner = add_settlements(doc, owner, decisions)
    from tools.map.korea_settlement_layout import cap_settlement_provinces
    owner = cap_settlement_provinces(doc, owner, decisions)
    # Explicitly reviewed re-parenting; the old numeric parent namespace is append-only.
    for parent in decisions.get('newParents', []):
        ids=set(parent['jurisdictionIds'])
        city_index=next(i for i,c in enumerate(doc['cities']) if c['id']==parent['seatPlaceId'])
        seat=doc['cities'][city_index]
        seat['seat']=True
        doc['parentRegions'].append(dict(id=parent['id'],displayName=parent['displayName'],nameCh=parent['nameCh'],administrativeSystem='EXTERNAL_POLITY'))
        doc['juns'].append(dict(name=parent['displayName'],nameCh=parent['nameCh'],seat=city_index,col=seat['col'],row=seat['row']))
        doc['commanderyRecords'].append(dict(id=parent['id'],displayName=parent['displayName'],nameCh=parent['nameCh'],kind='COMMANDERY',seatJurisdictionId=parent['seatPlaceId'],jurisdictionIds=sorted(ids)))
        for p in doc['provinceRecords']:
            if p['jurisdictionId'] in ids:p['parentRegionId']=parent['id']
        for j in doc['jurisdictionRecords']:
            if j['id'] in ids:j['commanderyId']=parent['id']
    for c in doc['commanderyRecords']:
        c['jurisdictionIds']=sorted(j['id'] for j in doc['jurisdictionRecords'] if j['commanderyId']==c['id'])
    doc['_meta']['counts'].update(parentRegions=len(doc['parentRegions']),commanderies=len(doc['commanderyRecords']),seats=len(doc['juns']))
    for label in decisions.get('labelOverrides', []):
        for city in doc['cities']:
            if city['id']==label['id']:
                city['name']=label['displayName']
                if 'nameCh' in label:city['nameCh']=label['nameCh']
        for row in doc['jurisdictionRecords']:
            if row['id']==label['id']:
                row['displayName']=label['displayName']
                if 'nameCh' in label:row['nameCh']=label['nameCh']
        for row in doc['provinceRecords']:
            if row['jurisdictionId']==label['id']:
                row['displayName']=label['displayName']
                if 'nameCh' in label:row['nameCh']=label['nameCh']
        for i,jun in enumerate(doc['juns']):
            if doc['cities'][jun['seat']]['id']==label['id']:
                jun['name']=label['displayName']
                if 'nameCh' in label:
                    jun['nameCh']=label['nameCh']
                    doc['parentRegions'][i]['nameCh']=label['nameCh']
                    doc['commanderyRecords'][i]['nameCh']=label['nameCh']
                doc['parentRegions'][i]['displayName']=label['displayName']
                doc['commanderyRecords'][i]['displayName']=label['displayName']
                # 옛 이름(aliases)도 같이 싣는다. 표시명을 바꾸면 런타임 월드(han.json)가
                # 아직 옛 이름을 meta.jun 으로 실어 보내므로, 이걸 안 내리면 소유권 바인딩이
                # 그 郡 을 못 찾는다 — build_tile_grid.PARENT_TEMPORAL_ALIASES 와 같은 뜻이고
                # provinceMap.buildProvinceAdministrativeIndex 가 parentRegions[].aliases 를 읽는다.
                # 2026-09-21: 이 줄이 없어 고령가야·대가야·성산가야 3 곳이 안 풀렸다.
                if label.get('aliases'):
                    for record in (doc['parentRegions'][i], doc['commanderyRecords'][i]):
                        record['aliases']=sorted(set(record.get('aliases',[])) | set(label['aliases']))
    doc['owner'] = _encode_runs(owner)
    doc['adjacency']['county'] = adjacency(owner, min_shared_edges=1)
    # Rejudge paths from the moved seats; unaffected pairs retain their verdicts.
    doc['adjacency']['commandery'] = [e for e in doc['adjacency']['commandery']
        if e['a'] not in changed_parents and e['b'] not in changed_parents]
    _rederive_parent_surfaces(doc)
    if not decisions.get('newParents') and not decisions.get('extension') and source['parentOwner'] != doc['parentOwner']:
        raise ValueError('seat correction changed political boundaries')
    return doc

def diff(before, after):
    patches = []
    def visit(old, new, path):
        if old == new:
            return
        if isinstance(old, dict) and isinstance(new, dict) and old.keys() == new.keys():
            for key in old:
                visit(old[key], new[key], path + [key])
        elif isinstance(old, list) and isinstance(new, list) and len(old) == len(new):
            for index, (a, b) in enumerate(zip(old, new)):
                visit(a, b, path + [index])
        else:
            patches.append(dict(path=path, before=old, after=new))
    for key in before:
        # Labels can change; juns have no stable ID and must reverse by list position.
        identity = 'id' if key in ('cities', 'provinceRecords', 'jurisdictionRecords', 'commanderyRecords') else None
        if identity:
            old = {r[identity]: r for r in before[key]}
            for row in after[key]:
                if old.get(row[identity]) != row:
                    patches.append(dict(key=key, identity=identity, value=row[identity],
                                        before=old.get(row[identity]), after=row))
        else:
            visit(before[key], after[key], [key])
    return patches

def peel(document):
    if not LEDGER.exists() or not document.get("cities"):
        return document, None
    ledger = json.loads(LEDGER.read_text())
    patches = ledger['patches']
    order = ledger.get('cityOrder')
    if order and [row['id'] for row in document['cities']] != order:
        document = copy.deepcopy(document)
        old = document['cities']
        by_id = {row['id']: row for row in old}
        if set(order) != set(by_id):
            return document, None
        index = {pid: i for i, pid in enumerate(order)}
        for province in document['provinceRecords']:
            if province.get('cityIndex') is not None:
                province['cityIndex'] = index[old[province['cityIndex']]['id']]
        for jun in document['juns']:
            jun['seat'] = index[old[jun['seat']]['id']]
        document['cities'] = [by_id[pid] for pid in order]
    # Recognize by all moved seats, not merely by the document's array order.
    city_patches = [p for p in patches if p.get('key') == 'cities']
    current = {r['id']: r for r in document['cities']}
    if not city_patches or not all(current.get(p['value']) == p['after'] for p in city_patches):
        return document, None
    restored = copy.deepcopy(document)
    for p in patches:
        if 'identity' in p:
            index = next(i for i, r in enumerate(restored[p['key']]) if r[p['identity']] == p['value'])
            if p['before'] is None:
                restored[p['key']].pop(index)
            else:
                restored[p['key']][index] = copy.deepcopy(p['before'])
        else:
            target = restored
            for component in p['path'][:-1]:
                target = target[component]
            target[p['path'][-1]] = copy.deepcopy(p['before'])
    return restored, ledger

def reapply(document, ledger):
    return apply(document, ledger['decisions'])

def build_stage(source, decisions):
    result = apply(source, decisions)
    ledger = dict(schemaVersion=1, inputSha256=digest(source), outputSha256=digest(result),
                  decisions=decisions, cityOrder=[row['id'] for row in result['cities']], patches=diff(source, result))
    return result, ledger

def restack(document, preceding_ledger, prepare=False):
    """Keep this stage when a preceding stage is explicitly rebuilt."""
    if preceding_ledger is None or '_koreaStage' not in preceding_ledger:
        return document
    result, ledger = build_stage(document, json.loads(DECISIONS.read_text()))
    if prepare:
        LEDGER.write_text(json.dumps(ledger, ensure_ascii=False, separators=(',', ':'))+'\n')
    return result

def check(document, ledger):
    original, found = peel(document)
    if found is None:
        return ['map does not contain the reviewed Korean place correction stage']
    problems = []
    decisions = json.loads(DECISIONS.read_text())
    if decisions != ledger['decisions']:
        problems.append('decisions changed after stage preparation')
    if digest(original) != ledger['inputSha256']:
        problems.append('restored input differs from the pinned preceding stage')
    if digest(document) != ledger['outputSha256']:
        problems.append('map differs from the pinned correction output')
    if apply(original, decisions) != document:
        problems.append('corrections do not reproduce the current map')
    registry = json.loads((ROOT/'data/curated/han/northeast-parent-id-append-v1.json').read_text())
    expected = {(p['id'], p['nameCh']) for p in decisions['newParents']}
    actual = {(p['commanderyId'], p['identity']) for p in registry['entries'] if p['status'] == 'ACTIVE'}
    if actual != expected or len(actual) != sum(p['status'] == 'ACTIVE' for p in registry['entries']):
        problems.append('northeast parent append registry differs from reviewed parents')
    if registry['nextOrdinal'] - registry['baseNextOrdinal'] != len(registry['entries']):
        problems.append('northeast parent append registry ordinal range differs')
    retired = json.loads((ROOT/'data/curated/han/korea-retired-settlements-v1.json').read_text())
    retired_ids = {r['id'] for r in retired['places']}
    if retired_ids & {r['id'] for r in document['cities']}:
        problems.append('withdrawn numbered settlements reappeared in the active map')
    if any(r.get('coordinateBasis') == 'GAME_DESIGN_FARTHEST_LAND_CELL' for r in decisions['settlements']):
        problems.append('density-only placement is no longer an approved settlement basis')
    external = {r['id']: r for r in json.loads((ROOT/'data/map/external-places.json').read_text())['places']}
    for d in decisions['decisions'] + decisions.get('settlements', []):
        if external[d['id']]['conf'] != d['confidence']:
            problems.append(f"{d['id']}: external source confidence differs")
        for key in ('lon', 'lat', 'wikidata', 'basis'):
            if external[d['id']][key] != d[key]:
                problems.append(f"{d['id']}: external source {key} differs")
    return problems

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--prepare', action='store_true')
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    document = json.loads(TILES.read_text())
    if args.check:
        problems = check(document, json.loads(LEDGER.read_text()))
        for p in problems: print(p, file=sys.stderr)
        return bool(problems)
    if not args.prepare: parser.error('choose --prepare or --check')
    source, _ = peel(document)
    lowland = json.loads((ROOT / 'data/curated/han/lowland-terrain-reclassifications-v1.json').read_text())
    if digest(source) not in {stage['outputDocumentSha256'] for stage in lowland['geometry']['stages']}:
        raise ValueError('prepare requires a reviewed lowland-stage output')
    decisions = json.loads(DECISIONS.read_text())
    result, ledger = build_stage(source, decisions)
    LEDGER.write_text(json.dumps(ledger, ensure_ascii=False, separators=(',', ':'))+'\n')
    TILES.write_text(json.dumps(result, ensure_ascii=False, separators=(',', ':'))+'\n')
    print('Applied Korean place corrections:', ', '.join(d['id'] for d in decisions['decisions']))
    return 0
if __name__ == '__main__': raise SystemExit(main())
