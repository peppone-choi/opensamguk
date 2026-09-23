"""Engine-external Yuzhou campaign: one state across march, siege and relief."""
import hashlib
import json
import math
from pathlib import Path
import sys

root = Path(__file__).resolve().parents[2]
sys.path[:0] = [str(root / 'tools/sim'), str(root)]
import march_tempo as M
import supply_passage as P
import convoy_schedule as V
import county_economy as C
import siege_resolution as S
import county_capture as K
import army_siege as A
import convoy_movement as D
import simulation_evidence as E
from tools.scenario import build_han_world as H


def settle_campaign_relief(*, graph, path, cost, speed, rough, owners, encircled,
                           destination_seat, destination_provinces, donor, destination,
                           donor_stock, grain, settle_county, horizon):
    """Army control precedes convoy movement; capture affects the next boundary.

    The callback consumes actual arrivals through the existing county resolver.
    A captured county blocks later friendly deliveries across every province.
    """
    allowed = {}
    for turn in range(1, horizon + 1):
        presence = [P.ArmyPresence(destination_seat, 1)] if encircled[turn] else []
        passage = P.passage_snapshot(provinces=set(graph.center), nation_id=2,
            owners=owners, armies=presence, effective_war_nations={1})
        allowed[turn] = set(passage['allowedProvinces'])

    def deliver():
        movement = D.move_convoy(graph=graph, path=path, speed=speed,
            rough_factor=rough, allowed_by_turn=allowed, horizon=horizon)
        transport = V.schedule_convoys(stocks={donor: donor_stock, destination: 0},
            horizon=horizon, orders=[dict(id='relief', source=donor, destination=destination,
                grain=grain, travelTurns=max(1, math.ceil(cost / speed)) if movement['dispatched'] else None)],
            actual_arrivals={'relief': movement['arrivalTurn']})
        county = settle_county(transport['arrivals'][destination])
        return movement, transport, county

    movement, transport, county = deliver()
    if county['capture']:
        first_capture = county['capture']['turn']
        for turn in range(first_capture + 1, horizon + 1):
            allowed[turn] -= destination_provinces
        movement, transport, county = deliver()
        # Removing only future deliveries cannot change an already settled capture.
        assert county['capture']['turn'] == first_capture
    return movement, transport, county


def run():
    paths = [M.TILES, root / 'data/curated/han/county-economy-inputs-v1.json',
             root / 'data/curated/han/march-tempo-targets-v1.json', H.CANON_SRC,
             *[Path(m.__file__) for m in (M, P, V, C, E, H, S, K, A, D)]]
    paths.append(Path(__file__).resolve())
    before = E.snapshot(root, paths)
    script_hash = hashlib.sha256(Path(__file__).read_bytes()).hexdigest()
    tiles, economy, tempo = [json.loads(p.read_text()) for p in paths[:3]]
    reference = tempo['warGrainBudget']['referenceCalibration']
    canon = H.canon_ju()
    parents = {p['id'] for p in tiles['commanderyRecords'] if canon.get(p['nameCh']) == '豫州'}
    jurs = {j['id']: j for j in tiles['jurisdictionRecords'] if j['commanderyId'] in parents and j['kind'] == 'COUNTY'}
    rows = {r['jurisdictionId']: r for r in economy['jurisdictions'] if r['jurisdictionId'] in jurs}
    assert rows and set(rows) == set(jurs)
    seats = {}
    for jid, j in jurs.items():
        hits = [i for i, p in enumerate(tiles['provinceRecords']) if p['id'] == j['seatPlaceId']]
        assert len(hits) == 1
        seats[jid] = hits[0]
    graph = M.Graph(tiles)
    provinces = set(graph.center)
    rough, speed = tempo['roughTerrainFactor'], tempo['baseSpeedKmPerTurn']
    results=[]
    for destination in sorted(rows):
        defender=rows[destination]['households']*reference['troopsPerHundredHouseholds']//100
        candidates=[]
        for source in sorted(rows):
            if source==destination:continue
            force=rows[source]['households']*reference['troopsPerHundredHouseholds']//100
            route=graph.shortest_path(seats[source],seats[destination],rough)
            if route is not None and force>=defender*tempo['armyEncirclement']['minimumAttackerRatio']:
                candidates.append((route[0],source,force,route[3]))
        if not candidates:
            results.append(dict(destination=destination,status='NO_SINGLE_COUNTY_FORCE'));continue
        cost,source,force,army_path=min(candidates)
        travel=max(1,math.ceil(cost/speed))
        owners={p:(1 if tiles['provinceRecords'][p]['jurisdictionId']==source else 2) for p in provinces}
        # Military passage may enter territory of the effective war opponent.
        assert all(owners[p] in (1,2) for p in army_path)
        friendly={p for p in provinces if owners[p]==2}
        demand=defender*reference['rationUnitsPerSoldierTurn']
        donors=[]
        for donor in sorted(rows):
            if donor in (source,destination):continue
            stock=rows[donor]['households']*reference['monthlyNetGrainPerHousehold']*reference['initialStockMonthsOfTax']
            route=graph.shortest_path(seats[donor],seats[destination],rough,allowed_provinces=friendly)
            if route is not None and stock>=demand*12:donors.append((route[0],donor,route,stock))
        donor_choice=min(donors,default=None)
        for mode in ('baseline','relief','army_starved','encounter'):
            ration=force*reference['rationUnitsPerSoldierTurn'];muster=force*reference['musterGrainUnitsPerSoldier']
            source_initial=rows[source]['households']*reference['monthlyNetGrainPerHousehold']*reference['initialStockMonthsOfTax']
            packed=muster+ration*(travel if mode=='army_starved' else 36)
            assert packed<=source_initial
            army=C.settle_county(initial_grain=packed,people=force,deliveries={},tax_grain={},
                recruitment_orders=[dict(id='army',turn=1,troops=force,equipGrain=muster)],
                ration_per_soldier=reference['rationUnitsPerSoldierTurn'],horizon=36)
            military=A.army_encirclement(settlement=army,travel_turns=travel,defender_troops=defender,
                approaches_controlled={t:True for t in range(1,37)},encounters={travel+1} if mode=='encounter' else set(),policy=tempo['armyEncirclement'])
            base=C.settle_county(initial_grain=source_initial-packed,
                people=rows[source]['households']*reference['peoplePerHouseholdForExperiment']-force,
                deliveries={},tax_grain={t:rows[source]['households']*reference['monthlyNetGrainPerHousehold'] for t in range(3,37,3)},
                recruitment_orders=[],ration_per_soldier=reference['rationUnitsPerSoldierTurn'],horizon=36)
            # Explicit scenario starting stores include travel-period consumption;
            # no stock is reset when the army arrives. Siege begins with18 rations.
            defender_initial=demand*(tempo['siegeResolution']['referenceInitialRationTurns']+travel)+defender*reference['musterGrainUnitsPerSoldier']
            def settle_deliveries(deliveries):
                return K.settle_besieged_county(county_id=destination,owner=2,besieger=1,initial_grain=defender_initial,
                    people=rows[destination]['households']*reference['peoplePerHouseholdForExperiment'],deliveries=deliveries,tax_grain={},
                    post_capture_tax_grain={t:rows[destination]['households']*reference['monthlyNetGrainPerHousehold'] for t in range(3,37,3)},
                    recruitment_orders=[dict(id='guard',turn=1,troops=defender,equipGrain=defender*reference['musterGrainUnitsPerSoldier'])],
                    ration_per_soldier=reference['rationUnitsPerSoldierTurn'],horizon=36,encircled=military['encircled'],policy=tempo['siegeResolution'])
            county=settle_deliveries({})
            transport=None;movement=None;donor=None;donor_stock=0
            if mode=='relief' and donor_choice is not None:
                relief_cost,donor,relief_route,donor_stock=donor_choice
                movement, transport, county = settle_campaign_relief(
                    graph=graph, path=relief_route[3], cost=relief_cost,
                    speed=tempo['convoyMovement']['baseSpeedKmPerTurn'], rough=rough,
                    owners=owners, encircled=military['encircled'], destination_seat=seats[destination],
                    destination_provinces={p for p in provinces if tiles['provinceRecords'][p]['jurisdictionId']==destination},
                    donor=donor, destination=destination, donor_stock=donor_stock, grain=demand*12,
                    settle_county=settle_deliveries, horizon=36)
            tax=spent=0
            for index in range(36):
                current=[state['ledger'][index] for state in (base,army,county)]
                tax+=sum(r['taxGrain'] for r in current);spent+=sum(r['equipmentConsumed']+r['rationConsumed'] for r in current)
                transit=transport['ledger'][index]['inTransit'] if transport else 0
                remaining_donor=transport['stocksAfterDispatch'][donor] if transport else 0
                assert source_initial+defender_initial+donor_stock+tax==sum(r['closingGrain'] for r in current)+transit+remaining_donor+spent
                assert base['ledger'][index]['people']+army['ledger'][index]['people']+army['ledger'][index]['troops']==rows[source]['households']*reference['peoplePerHouseholdForExperiment']
            net_tax=sum(r['taxGrain'] for r in base['ledger']);required=sum(r['equipmentConsumed']+r['rationDemand'] for r in army['ledger'])
            budget=tempo['warGrainBudget'];assert budget['minPercentOfPeriodNetGrainTax']*net_tax<=100*required<=budget['maxPercentOfPeriodNetGrainTax']*net_tax
            duration=None
            if mode=='baseline':
                assert county['capture'] is not None
                duration=county['capture']['turn']-military['arrivalTurn']+1
                assert tempo['siegeTarget']['minTurns']<=duration<=tempo['siegeTarget']['maxTurns']
            if mode in ('army_starved','encounter'):assert county['capture'] is None
            results.append(dict(destination=destination,source=source,mode=mode,status='EVALUATED',path=army_path,pathCostKm=cost,
                travelTurns=travel,forcedTravelTurns=math.ceil(cost/(speed*tempo['forcedMarch']['speedFactor'])),siegeDuration=duration,
                burdenPercent=100*required/net_tax,initialGrain=dict(source=source_initial,defender=defender_initial,donor=donor_stock),
                army=army,military=military,base=base,county=county,donor=donor,movement=movement,transport=transport))
    summary={mode:dict(cases=sum(r.get('mode')==mode for r in results),
        captured=sum(r.get('mode')==mode and r['county']['capture'] is not None for r in results),
        captureTurns=sorted({r['county']['capture']['turn'] for r in results if r.get('mode')==mode and r['county']['capture']}))
        for mode in ('baseline','relief','army_starved','encounter')}
    summary['noSingleCountyForce']=sum(r['status']=='NO_SINGLE_COUNTY_FORCE' for r in results)
    result=dict(scope=f'Yuzhou {len(rows)} counties; integrated recruitment/march/army rations/passage/finite convoy/capture/monthly tax, independent scenarios',
        summary=summary,cases=results,numericalBaselineWithinApprovedTargets=True,
        limitations=['Not runtime or full S3 play. Tactical approach control is a scenario input; encounter halts without fabricating battle.',
            'Initial defender stock includes explicitly accounted travel consumption plus18 ration-turn siege reserve; never reset at arrival.',
            'Three targets have no single-county army with sufficient strength; coalition play not tested.',
            'Relief depot has finite stock but no modeled depot army or tax. No forced-march fatigue magnitude inferred.'])
    assert hashlib.sha256(Path(__file__).read_bytes()).hexdigest()==script_hash
    return E.evidence(root,paths,before,result)


if __name__ == '__main__':
    print(json.dumps(run(), ensure_ascii=False, indent=1))
