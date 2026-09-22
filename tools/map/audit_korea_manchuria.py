#!/usr/bin/env python3
"""Reproducible coverage/density audit of the existing game map (not a new atlas)."""
import argparse
from collections import Counter
import json
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
OUT=ROOT/'data/curated/han/korea-manchuria-coverage-v1.json'

def build():
    load=lambda p:json.loads((ROOT/p).read_text())
    t=load('data/map/han-tiles.json');w=load('infra/src/main/resources/map/han-world-v3.json')
    policy=load('data/curated/han/external-region-hierarchy-policy-v1.json')
    area=Counter()
    for pi,n in t['owner']:
        if pi>=0:area[t['provinceRecords'][pi]['jurisdictionId']]+=n
    by_jur={t['provinceRecords'][c['spatialProvinceIndex']]['jurisdictionId']:c for c in w['cities']}
    points={str(c['id']):c for c in t['cities']}
    groups=[r for r in policy['macroRegions'] if r['id'] in {'GOGURYEO','OKJEO','YILOU','BUYEO','YE','MAHAN','JINHAN','BYEONHAN','USAN','JUHO','WUHUAN','XIANBEI','SONGHUA','AMUR','USSURI'}]
    for ch in ['遼西郡','遼東郡','玄菟郡','樂浪郡','遼東屬國','帶方郡']:
        parent=next(r for r in t['parentRegions'] if r['nameCh']==ch)
        groups.append(dict(id=parent['id'],displayName=parent['displayName'],jurisdictionIds=[j['id'] for j in t['jurisdictionRecords'] if j['commanderyId']==parent['id']]))
    out=[]
    for group in groups:
        members=[]
        for jid in group['jurisdictionIds']:
            city=by_jur[jid];p=points[city['physicalPlaceRef'].rsplit(':',1)[-1]]
            members.append(dict(jurisdictionId=jid,cityId=city['id'],name=city['name'],nameCh=city['meta']['nameCh'],longitude=p.get('lon'),latitude=p.get('lat'),landCells=area[jid],spatialProvinceCount=sum(r['jurisdictionId']==jid for r in t['provinceRecords']),connectionCount=len(city['connections']),runtimeParentName=city['meta']['junCh']))
        total=sum(x['landCells'] for x in members)
        out.append(dict(id=group['id'],name=group['displayName'],cityCount=len(members),landCells=total,cellsPerCity=round(total/len(members),1),largestJurisdictionCells=max(x['landCells'] for x in members),members=members))
    return dict(schemaVersion=1,referenceYear=220,gridNorthLatitude=t['_meta']['projection']['y1']+t['_meta']['projection']['pad'],areaUnit='OWNED_GRID_CELLS_NOT_KM2',groupSemantics='Display regions and runtime administrative groups overlap; do not sum across groups. Sparse does not establish historical low population.',regions=out)

def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--check',action='store_true');a=parser.parse_args()
    data=json.dumps(build(),ensure_ascii=False,indent=2)+'\n'
    if a.check:return not OUT.exists() or OUT.read_text()!=data
    OUT.write_text(data);return 0
if __name__=='__main__':raise SystemExit(main())
