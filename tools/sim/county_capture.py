"""Compose real ration-ledger surrender with county ownership and monthly grain tax.

Engine-external reference: peaceful surrender disarms the defending garrison,
returning its people to the county's civilians without casualties or recruitment
into the victor's army. The physical warehouse stays in the county. Tax at the
capture boundary belongs to its new owner because capture precedes monthly tax.
Attacking-army rations and post-capture military occupation are not modeled here.
"""
from county_economy import settle_county
from siege_resolution import resolve_siege


def settle_besieged_county(*, county_id: str, owner: int, besieger: int, initial_grain: int,
                           people: int, deliveries: dict, tax_grain: dict,
                           recruitment_orders: list, ration_per_soldier: int, horizon: int,
                           encircled: dict, policy: dict, post_capture_tax_grain: dict | None = None) -> dict:
    capture_policy = policy.get('captureSettlement', {})
    if (capture_policy.get('status') != 'OWNER_DELEGATED_DECISION'
            or capture_policy.get('garrison') != 'DISARM_TO_COUNTY_CIVILIANS'
            or capture_policy.get('warehouse') != 'KEEP_IN_COUNTY_UNDER_NEW_OWNER'
            or capture_policy.get('monthlyTax') != 'OWNER_AFTER_CAPTURE'):
        raise ValueError('matching delegated peaceful-capture policy required')
    if not isinstance(county_id, str) or not county_id:
        raise ValueError('county identity required')
    if type(owner) is not int or owner < 0 or type(besieger) is not int or besieger <= 0 or owner == besieger:
        raise ValueError('distinct county owner and positive besieger required')
    post_tax = tax_grain if post_capture_tax_grain is None else post_capture_tax_grain
    if not isinstance(post_tax, dict) or any(type(t) is not int or not 1 <= t <= horizon
            or type(v) is not int or v < 0 for t,v in post_tax.items()):
        raise ValueError('valid post-capture tax schedule required')
    # Generate the pre-capture trajectory with the existing grain settlement.
    before = settle_county(initial_grain=initial_grain, people=people, deliveries=deliveries,
        tax_grain=tax_grain, recruitment_orders=recruitment_orders,
        ration_per_soldier=ration_per_soldier, horizon=horizon)
    if (not any(d['status']=='APPLIED' for d in before['recruitDecisions'])
            or not all((d['status']=='APPLIED' and d['turn']==1) or
                       (d['status']=='DUPLICATE' and d['originalStatus']=='APPLIED')
                       for d in before['recruitDecisions'])):
        raise ValueError('reference garrison must be recruited successfully')
    siege = resolve_siege(ledger=before['ledger'], encircled=encircled, policy=policy)
    event = siege['surrender']
    at = event['turn'] if event else None
    rows = []
    tax_by_owner = {str(owner): 0, str(besieger): 0}
    for row in before['ledger'][:at]:
        entry = dict(row, ownerBefore=owner, ownerAfter=owner, demobilized=0)
        if row['turn'] == at:
            entry.update(ownerAfter=besieger, demobilized=row['troops'], troops=0,
                         people=row['people'] + row['troops'], taxGrain=post_tax.get(at,0),
                         closingGrain=row['closingGrain']-row['taxGrain']+post_tax.get(at,0))
        tax_by_owner[str(entry['ownerAfter'])] += entry['taxGrain']
        rows.append(entry)
    if at is not None and at < horizon:
        remaining = horizon-at
        after = settle_county(initial_grain=rows[-1]['closingGrain'], people=rows[-1]['people'],
            deliveries={t-at:v for t,v in deliveries.items() if t>at},
            tax_grain={t-at:v for t,v in post_tax.items() if t>at}, recruitment_orders=[],
            ration_per_soldier=ration_per_soldier, horizon=remaining)
        for row in after['ledger']:
            rows.append(dict(row,turn=row['turn']+at,ownerBefore=besieger,ownerAfter=besieger,demobilized=0))
            tax_by_owner[str(besieger)] += row['taxGrain']
    captured = None if at is None else {'id': f'{county_id}:surrender:{at}', 'turn':at,
        'previousOwner':owner, 'newOwner':besieger, 'warehouseBeforeMonthlyTax':event['warehouseGrainAtSurrender'],
        'demobilized':rows[at-1]['demobilized']}
    # No county grain is teleported to a national treasury by capture.
    assert len(rows) == horizon
    assert initial_grain + sum(deliveries.values()) + sum(r['taxGrain'] for r in rows) == (
        rows[-1]['closingGrain'] + sum(r['equipmentConsumed']+r['rationConsumed'] for r in rows))
    assert all(r['people']+r['troops']==people for r in rows)
    return {'status':'ENGINE_EXTERNAL_CAPTURE_SETTLEMENT', 'countyId':county_id,
            'capture':captured, 'siege':siege, 'ledger':rows, 'taxByOwner':tax_by_owner,
            's2GatePassed':False, 'runtimeCaptureImplemented':False}
