import copy
import json
import unittest
from pathlib import Path
from tools.scenario.korea_settlement_economy import allocate, FIELDS
ROOT=Path(__file__).resolve().parents[3]

class KoreaSettlementEconomyTest(unittest.TestCase):
    def test_allocation_preserves_each_budget_and_exact_integer_capacity(self):
        cities=[dict(spatialProvinceIndex=i, initial={f:1001 for f in FIELDS},
                     max={f:5951 for f in FIELDS}, meta={}) for i in range(4)]
        tiles={'provinceRecords':[{'jurisdictionId':s} for s in ('donor','a','b','unrelated')]}
        decisions={'settlements':[{'id':s,'fundingJurisdictionId':'donor'} for s in ('a','b')]}
        untouched=copy.deepcopy(cities[-1])
        allocate(cities,tiles,decisions)
        for kind,total in [('initial',1001),('max',5951)]:
            for field in FIELDS:
                self.assertEqual(total,sum(c[kind][field] for c in cities[:3]))
        self.assertEqual(untouched,cities[-1])
        self.assertEqual([1984,1984,1983],[c['max']['population'] for c in cities[:3]])

    def test_generated_world_has_all_reviewed_playable_connected_settlements(self):
        world=json.loads((ROOT/'infra/src/main/resources/map/han-world-v3.json').read_text())
        additions=[c for c in world['cities'] if c['id']>=1134]
        self.assertEqual(35,len(additions))
        self.assertEqual({'external:v1:'+r['id'] for r in json.loads((ROOT/'data/curated/han/korea-place-corrections-v1.json').read_text())['settlements']}, {c['physicalPlaceRef'] for c in additions})
        for city in additions:
            self.assertTrue(city['connections'])
            self.assertFalse(city['meta']['economyBasis']['historicalCensus'])
            for f in FIELDS:
                self.assertGreater(city['initial'][f],0)
                self.assertLessEqual(city['initial'][f],city['max'][f])

    def test_actual_donor_groups_conserve_the_frozen_1133_world(self):
        import gzip
        from collections import defaultdict
        bundle=ROOT/'data/map/han-world-v3-1133-artifacts-v1'
        catalog=json.loads((bundle/'catalog.json').read_text())
        entry=next(r for r in catalog['files'] if r['path']=='infra/src/main/resources/map/han-world-v3.json')
        original={c['id']:c for c in json.loads(gzip.decompress((bundle/entry['blob']).read_bytes()))['cities']}
        current=json.loads((ROOT/'infra/src/main/resources/map/han-world-v3.json').read_text())['cities']
        groups=defaultdict(list)
        for c in current:
            if 'economyBasis' in c['meta']:
                groups[c['meta']['economyBasis']['fundingJurisdictionId']].append(c)
        self.assertTrue(groups)
        for donor,members in groups.items():
            before=[c for c in members if c['id'] in original]
            self.assertEqual(1,len(before),donor)
            baseline=original[before[0]['id']]
            for kind in ('initial','max'):
                for field in FIELDS:
                    self.assertEqual(baseline[kind][field],sum(c[kind][field] for c in members),(donor,kind,field))
