"""Derive siege maintenance from actual army recruitment/rations and route time.

A failed expedition never starts a new attempt implicitly. Withdrawal and
encounter terminate this order; return marching and renewed orders are separate.
Approach control is a supplied territorial/tactical snapshot, not inferred from
mere proximity. Travel starts after boundary one and arrives at 1+travel_turns.
"""


def army_encirclement(*, settlement: dict, travel_turns: int | None,
                      defender_troops: int, approaches_controlled: dict[int, bool],
                      encounters: set[int], policy: dict) -> dict:
    if (policy.get('status') != 'OWNER_DELEGATED_DECISION'
            or type(policy.get('minimumAttackerRatio')) is not int or policy['minimumAttackerRatio'] < 1):
        raise ValueError('delegated encirclement policy required')
    if type(defender_troops) is not int or defender_troops <= 0:
        raise ValueError('positive defending garrison required')
    if travel_turns is not None and (type(travel_turns) is not int or travel_turns < 1):
        raise ValueError('positive travel turns or absent route required')
    rows = settlement['ledger']
    turns = set(range(1,len(rows)+1))
    if not rows or [r['turn'] for r in rows] != list(range(1,len(rows)+1)):
        raise ValueError('complete sequential army ledger required')
    if set(approaches_controlled) != turns or any(type(v) is not bool for v in approaches_controlled.values()):
        raise ValueError('explicit approach-control snapshot required at each turn')
    if any(type(t) is not int or t not in turns for t in encounters):
        raise ValueError('encounters must refer to known turns')
    for row in rows:
        if any(type(row[k]) is not int or row[k]<0 for k in ('troops','rationDemand','rationConsumed','unmetRation')):
            raise ValueError('nonnegative integer army state required')
        if row['rationConsumed']+row['unmetRation'] != row['rationDemand']:
            raise ValueError('army ration settlement must conserve demand')
    decisions=settlement['recruitDecisions']
    ready=any(d['status']=='APPLIED' for d in decisions) and all(
        (d['status']=='APPLIED' and d['turn']==1) or
        (d['status']=='DUPLICATE' and d['originalStatus']=='APPLIED') for d in decisions)
    terminal='RECRUITMENT_FAILED' if not ready else ('NO_ROUTE' if travel_turns is None else None)
    arrival=None if travel_turns is None else 1+travel_turns
    out=[]; maintained={}
    actual_arrival=None
    for row in rows:
        t=row['turn']
        if terminal is None and row['unmetRation']:
            terminal='WITHDRAWN_FOR_RATIONS'
        if terminal is None and t in encounters:
            terminal='HALTED_BY_ENCOUNTER'
        if terminal is None and t>=arrival and actual_arrival is None:
            actual_arrival=t
        if terminal is not None:
            state=terminal
        elif t<arrival:
            state='MARCHING'
        elif row['troops'] < defender_troops*policy['minimumAttackerRatio']:
            state='INSUFFICIENT_FORCE'
        elif not approaches_controlled[t]:
            state='APPROACHES_NOT_CONTROLLED'
        else:
            state='ENCIRCLING'
        maintained[t]=state=='ENCIRCLING'
        out.append({'turn':t,'state':state,'troops':row['troops'],'encircled':maintained[t]})
    return {'status':'ENGINE_EXTERNAL_ARMY_SIEGE','plannedArrivalTurn':arrival,'arrivalTurn':actual_arrival,
            'encircled':maintained,'ledger':out,'runtimeImplemented':False}
