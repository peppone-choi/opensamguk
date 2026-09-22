"""Game allocation, not a census: subdivide existing budgets among added settlements.

Historical regional households and goods remain in korea-economic-evidence-v1.
Unknown local economics use equal-weight game budgets, preserving donor totals.
Exact capacity values are emitted with RawCity statScale=1 to avoid rounding loss.
"""
from collections import defaultdict

FIELDS = ('population', 'agriculture', 'commerce')


def allocate(cities, tiles, decisions):
    by_jur = {tiles['provinceRecords'][c['spatialProvinceIndex']]['jurisdictionId']: c
              for c in cities if 'spatialProvinceIndex' in c}
    groups = defaultdict(list)
    for row in decisions.get('settlements', []):
        groups[row['fundingJurisdictionId']].append(row['id'])
    for donor, additions in sorted(groups.items()):
        members = [donor] + sorted(additions)
        budget = {kind: dict(by_jur[donor][kind]) for kind in ('initial', 'max')}
        for kind in ('initial', 'max'):
            unit = 1
            for field in FIELDS:
                total = budget[kind][field]
                if total % unit:
                    raise ValueError('capacity must remain exactly representable in RawCity')
                share, remainder = divmod(total // unit, len(members))
                for i, jid in enumerate(members):
                    by_jur[jid][kind][field] = (share + (i < remainder)) * unit
        for jid in members:
            c = by_jur[jid]
            if any(c['initial'][f] > c['max'][f] for f in FIELDS):
                raise ValueError(f'{jid}: allocated initial exceeds capacity')
            c['meta']['economyBasis'] = {
                'kind': 'GAME_DESIGN_CONSERVED_BUDGET', 'fundingJurisdictionId': donor,
                'memberJurisdictionIds': members, 'allocation': 'EQUAL_SHARES_STABLE_REMAINDER',
                'historicalCensus': False,
                'decisionRef': 'data/curated/han/korea-place-corrections-v1.json',
            }
