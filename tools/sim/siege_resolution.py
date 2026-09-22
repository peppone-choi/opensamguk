"""Delegated starvation/surrender rule; pure engine-external evaluation.

Consumes actual ration settlement, never invents receipts or changes ownership.
The caller supplies maintained encirclement at each boundary; this does not
model attackers, assaults, civilian hunger, casualties, or post-capture economy.
Only rows through surrender are evaluated. Later receipts cannot undo surrender.
"""


def resolve_siege(*, ledger: list[dict], encircled: dict[int, bool], policy: dict) -> dict:
    if policy.get('status') != 'OWNER_DELEGATED_DECISION':
        raise ValueError('siege rule requires an explicit delegated decision')
    keys = ('maxMorale', 'initialMorale', 'fullShortfallMoraleLoss', 'fullyFedMoraleRecovery')
    if any(type(policy.get(k)) is not int or policy[k] <= 0 for k in keys):
        raise ValueError('positive integer morale parameters required')
    maximum, morale, loss, recovery = (policy[k] for k in keys)
    if morale > maximum:
        raise ValueError('initial morale exceeds maximum')
    if not ledger or [r['turn'] for r in ledger] != list(range(1, len(ledger) + 1)):
        raise ValueError('complete sequential siege ledger from turn one required')
    if set(encircled) != set(range(1, len(ledger) + 1)) or any(type(v) is not bool for v in encircled.values()):
        raise ValueError('explicit encirclement required at every boundary')
    for row in ledger:
        if any(type(row[k]) is not int or row[k] < 0 for k in ('rationDemand', 'rationConsumed', 'unmetRation', 'closingGrain')):
            raise ValueError('nonnegative integer grain ledger required')
        if type(row.get('taxGrain', 0)) is not int or not 0 <= row.get('taxGrain', 0) <= row['closingGrain']:
            raise ValueError('valid post-ration monthly tax required')
        if row['rationDemand'] <= 0 or row['rationConsumed'] + row['unmetRation'] != row['rationDemand']:
            raise ValueError('positive garrison demand and conserved ration settlement required')
    rows = []
    surrender = None
    for row in ledger:
        before = morale
        demand, unmet = row['rationDemand'], row['unmetRation']
        decrease = (loss * unmet + demand - 1) // demand if unmet else 0
        morale = max(0, morale - decrease) if unmet else min(maximum, morale + recovery)
        surrendered = morale == 0 and encircled[row['turn']]
        rows.append({'turn': row['turn'], 'moraleBefore': before, 'moraleAfter': morale,
                     'rationDemand': demand, 'unmetRation': unmet,
                     'encircled': encircled[row['turn']], 'surrendered': surrendered})
        if surrendered:
            surrender = {'turn': row['turn'], 'reason': 'STARVATION_MORALE_COLLAPSE_UNDER_ENCIRCLEMENT',
                         'warehouseGrainAtSurrender': row['closingGrain'] - row.get('taxGrain', 0)}
            break
    return {'status': 'DELEGATED_SIEGE_RULE_EVALUATION', 'ledger': rows, 'surrender': surrender,
            'ownershipTransferred': False, 's2GatePassed': False}
