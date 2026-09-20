"""Attach evidence/interpretation to existing runtime city records, not a new atlas."""
def attach(cities, tiles, decisions):
    by_jur={tiles['provinceRecords'][c['spatialProvinceIndex']]['jurisdictionId']:c for c in cities}
    for decision in decisions.get('settlements',[]):
        city=by_jur[decision['id']]
        city['meta']['historicalPlace']={
            'placementBasis':decision['placementBasis'],
            'coordinateBasis':decision.get('coordinateBasis','MODERN_WIKIDATA_PROXY_WITH_REVIEWED_GRID_SNAP'),
            'confidence':decision['confidence'],
            'independentPolityAtReferenceYear':decision['independentPolityAt220'],
            'evidence':decision['historicalEvidence'],
            'regionId':decision['regionId'],
            'decisionRef':'data/curated/han/korea-place-corrections-v1.json#'+decision['id'],
        }
    for row in decisions.get('labelOverrides',[]):
        city=by_jur[row['id']]
        city['name']=row['displayName']
        city['meta']['historicalPlace']={'aliases':row['aliases'],'nameBasis':row['basis'],'independentPolityAtReferenceYear':False}
    for row in decisions.get('nameInterpretations',[]):
        target_jid=row['target'] if row['target'] in by_jur else next((p['seatJurisdictionId'] for p in tiles['commanderyRecords'] if p['id']==row['target']),None)
        targets=[by_jur[target_jid]] if target_jid in by_jur else []
        # Parent identities are also exposed in the top-level curated decision
        # ledger when a runtime DTO does not carry commanderyId.
        for city in targets:
            city['meta'].setdefault('historicalPlace',{}).setdefault('nameInterpretations',[]).append(row)
    for jid,city in by_jur.items():
        if jid.startswith(('fc-lelang-','fc-daifang-','fc-liaodong-','fc-xuantu-')):
            city['meta'].setdefault('historicalPlace',{}).update(coordinateBasis='REVIEWED_FRONTIER_COUNTY_PROXY',referenceYear=220,modernProxyDoesNotProveAncientSeat=True)
    for jid,basis in [('X040','목지국 익산 비정 유지. 직산·아산만·예산 등 경쟁설 존재; 건마를 동일 익산 중심점에 중복 배치하지 않음'),('X035','울릉도의 지역 거점. 512년 복속 기사가 220년 우산국 성립을 입증하는 것은 아님'),('X043','주호=제주 비정 채택. 주호의 호수·정확한 국체는 별도 미상'),('X006','220년 제3현도 무순 방면. 초기 현도의 동북 이동 경로와 분리')]:
        if jid in by_jur:by_jur[jid]['meta'].setdefault('historicalPlace',{}).update(nameBasis=basis,referenceYear=220)
