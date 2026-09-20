"""Game-design subdivisions for historically attested Korean frontier settlements.

Modern locality anchors are explicit proxies, not excavated ancient capitals.
Each existing spatial province stays within its original parent region. Cityless
subdivisions follow the nearest anchor in their original jurisdiction; provinces
containing multiple anchors split by deterministic four-neighbour flood fill.
"""
from __future__ import annotations
from collections import defaultdict, deque
import heapq
import copy
import numpy as np
from tools.map.measure_province_seat_offset import project_cell

def add_settlements(document, owner, decisions):
    rows = decisions.get('settlements', [])
    if not rows:
        return owner
    provinces = document['provinceRecords']
    cities = document['cities']
    jurisdictions = {r['id']: r for r in document['jurisdictionRecords']}
    by_jur = defaultdict(list)
    for decision in rows:
        by_jur[decision['fundingJurisdictionId']].append(decision)
    original_owner = owner.copy()
    original_provinces = copy.deepcopy(provinces)
    for jid, additions in by_jur.items():
        jurisdiction = jurisdictions[jid]
        original_city = next(i for i,r in enumerate(cities) if r['id'] == jurisdiction['seatPlaceId'])
        seat = cities[original_city]
        seeds = [(jid, seat['row'], seat['col'], original_city)]
        for decision in additions:
            pid = decision['id']
            col, row = map(int, project_cell(document['_meta']['projection'], decision['lat'], decision['lon']))
            donor = original_provinces[int(original_owner[row,col])]
            if donor['jurisdictionId'] != jid or donor['parentRegionId'] != decision['commanderyId']:
                raise ValueError(f'{pid}: reviewed donor changed')
            if document['terrain'][row][col] in '049':
                raise ValueError(f'{pid}: locality proxy is not on land')
            seeds.append((pid,row,col,len(cities)))
            cities.append(dict(id=pid,name=decision['name'],nameCh=decision['nameCh'],level=4,
                               kind='EXTERNAL_PLACE',seat=False,zhi=False,col=col,row=row,
                               lon=decision['lon'],lat=decision['lat']))
            jurisdictions[pid] = dict(id=pid,displayName=decision['name'],nameCh=decision['nameCh'],
                                     kind='EXTERNAL_SETTLEMENT',commanderyId=decision['commanderyId'],
                                     seatPlaceId=pid,provinceIds=[])
        if len({(r,c) for _,r,c,_ in seeds}) != len(seeds):
            raise ValueError(f'{jid}: duplicate settlement seed cell')
        # Partition the whole donor jurisdiction, not isolated old subdivisions.
        # Otherwise a cityless 1,700-cell province can stick to one small new seat.
        indices = [i for i,p in enumerate(original_provinces) if p['jurisdictionId'] == jid]
        mask = np.isin(original_owner, indices)
        assigned = np.full(owner.shape, -1, dtype=np.int32)
        distance = np.full(owner.shape, np.inf)
        queue = []
        for label,(_,r,c,_) in enumerate(seeds):
            assigned[r,c] = label
            distance[r,c] = 0
            heapq.heappush(queue,(0,label,r,c))
        costs = {'1': 1, '2': 4, '3': 2, '5': 3, '6': 2, '7': 1, '8': 2}
        while queue:
            dist,label,r,c = heapq.heappop(queue)
            if (dist,label) != (distance[r,c],assigned[r,c]):
                continue
            for nr,nc in ((r-1,c),(r,c-1),(r,c+1),(r+1,c)):
                if not (0 <= nr < owner.shape[0] and 0 <= nc < owner.shape[1]) or not mask[nr,nc]:
                    continue
                nd = dist + costs.get(document['terrain'][nr][nc],4)
                if (nd,label) < (distance[nr,nc],assigned[nr,nc]):
                    distance[nr,nc] = nd
                    assigned[nr,nc] = label
                    heapq.heappush(queue,(nd,label,nr,nc))
        # An existing disconnected island/component gets one nearest settlement;
        # it is never joined by a fictitious land bridge.
        pending = set(map(tuple,np.argwhere(mask & (assigned < 0)).tolist()))
        while pending:
            start=min(pending);pending.remove(start);component=[start];todo=deque([start])
            while todo:
                r,c=todo.popleft()
                for cell in ((r-1,c),(r,c-1),(r,c+1),(r+1,c)):
                    if cell in pending:
                        pending.remove(cell);component.append(cell);todo.append(cell)
            r,c=np.mean(component,axis=0)
            label=min(range(len(seeds)),key=lambda i:((r-seeds[i][1])**2+(c-seeds[i][2])**2,i))
            for r,c in component:assigned[r,c]=label
        for pi in indices:
            province=original_provinces[pi]
            cells=np.argwhere(original_owner==pi)
            pieces={int(label):cells[assigned[cells[:,0],cells[:,1]]==label]
                    for label in np.unique(assigned[cells[:,0],cells[:,1]])}
            # The old seat's piece retains its old province identity/index.
            labels = sorted(pieces, key=lambda label: (label!=0,label))
            for number,label in enumerate(labels):
                target_jid,r,c,city_index = seeds[label]
                record = copy.deepcopy(province)
                if number:
                    record['id'] = f'KOR-{target_jid}-{province["id"]}'
                    index = len(provinces)
                    provinces.append(record)
                else:
                    index = pi
                    provinces[pi] = record
                target = jurisdictions[target_jid]
                record.update(jurisdictionId=target_jid,displayName=target['displayName'],nameCh=target['nameCh'],
                              cityIndex=city_index if original_owner[r,c] == pi else None,
                              geometryBasis='GAME_DESIGN_SETTLEMENT_SUBDIVISION',
                              assignmentBasis='GAME_DESIGN_NEAREST_SETTLEMENT',assignmentConfidence='INFERRED')
                if target_jid != jid:
                    record.update(administrativeSystem=next(d.get('regionId','GOGURYEO') for d in additions if d['id']==target_jid),confidence='DISPUTED')
                part=pieces[label]
                owner[part[:,0],part[:,1]] = index
        for target_jid,_,_,_ in seeds:
            jurisdictions[target_jid]['provinceIds'] = sorted(r['id'] for r in provinces if r['jurisdictionId']==target_jid)
            if not jurisdictions[target_jid]['provinceIds']:
                raise ValueError(f'{target_jid}: empty settlement jurisdiction')
    document['jurisdictionRecords'] = list(jurisdictions.values())
    for commandery in document['commanderyRecords']:
        commandery['jurisdictionIds'] = sorted(r['id'] for r in jurisdictions.values() if r['commanderyId']==commandery['id'])
    document['_meta']['counts'].update(cities=len(cities),provinces=len(provinces),jurisdictions=len(jurisdictions),
                                      EXTERNAL_PLACE=sum(r['kind']=='EXTERNAL_PLACE' for r in cities))
    return owner


def cap_settlement_provinces(document, owner, decisions, limit=620):
    """Bound added spatial pieces without introducing extra cities or population.

    A connected BFS chunk is at most 500 cells. Old province IDs and their seat
    pieces survive. Small remnants are recorded as reviewed area exceptions.
    """
    affected={d['id'] for d in decisions.get('settlements',[])}|{d['fundingJurisdictionId'] for d in decisions.get('settlements',[])}
    provinces=document['provinceRecords']
    for pi,province in enumerate(list(provinces)):
        if province['jurisdictionId'] not in affected:continue
        cells=np.argwhere(owner==pi)
        if len(cells)<=limit:continue
        pending=set(map(tuple,cells.tolist()));pieces=[]
        city=province.get('cityIndex')
        seat=(document['cities'][city]['row'],document['cities'][city]['col']) if city is not None else None
        while pending:
            start=seat if seat in pending else min(pending)
            pending.remove(start);queue=deque([start]);piece=[]
            while queue and len(piece)<500:
                r,c=queue.popleft();piece.append((r,c))
                for cell in ((r-1,c),(r,c-1),(r,c+1),(r+1,c)):
                    if cell in pending:pending.remove(cell);queue.append(cell)
            pending.update(queue)
            pieces.append(piece)
        for n,piece in enumerate(pieces):
            if n==0:continue
            record=copy.deepcopy(province);record.update(id=f'KOR-SUB-{province["id"]}-{n}',cityIndex=None,geometryBasis='GAME_DESIGN_CONNECTED_AREA_CAP')
            index=len(provinces);provinces.append(record)
            for r,c in piece:owner[r,c]=index
    # Absorb tiny non-seat cap remnants into an adjacent piece of the same
    # jurisdiction. This changes neither a city nor a political boundary.
    remove=set()
    for i,record in enumerate(provinces):
        if not record['id'].startswith('KOR-SUB-'):continue
        cells=np.argwhere(owner==i)
        if len(cells)>=8:continue
        candidates=[]
        for r,c in cells:
            for nr,nc in ((r-1,c),(r,c-1),(r,c+1),(r+1,c)):
                if not(0<=nr<owner.shape[0] and 0<=nc<owner.shape[1]):continue
                target=int(owner[nr,nc])
                if target>=0 and target!=i and target not in remove and provinces[target]['jurisdictionId']==record['jurisdictionId']:
                    count=int(np.count_nonzero(owner==target))
                    if count+len(cells)<=limit:candidates.append((count,target))
        if candidates:
            target=min(candidates)[1];owner[owner==i]=target;remove.add(i)
    if remove:
        keep=[i for i in range(len(provinces)) if i not in remove]
        remap=np.full(len(provinces),-1,dtype=np.int32)
        for new,old in enumerate(keep):remap[old]=new
        valid=owner>=0;owner[valid]=remap[owner[valid]]
        provinces[:]=[provinces[i] for i in keep]
    for j in document['jurisdictionRecords']:j['provinceIds']=sorted(p['id'] for p in provinces if p['jurisdictionId']==j['id'])
    document['_meta']['counts']['provinces']=len(provinces)
    return owner
