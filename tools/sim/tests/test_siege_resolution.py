import copy
import json
import sys
import unittest
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from county_economy import settle_county
from siege_resolution import resolve_siege

ROOT = Path(__file__).resolve().parents[3]


class SiegeResolutionTest(unittest.TestCase):
    def policy(self):
        return json.loads((ROOT / 'data/curated/han/march-tempo-targets-v1.json').read_text())['siegeResolution']

    def run_siege(self, stock=18, deliveries=None, encircled=None, horizon=36):
        settlement = settle_county(initial_grain=stock*100, people=1, deliveries=deliveries or {}, tax_grain={},
            recruitment_orders=[dict(id='guard', turn=1, troops=1, equipGrain=0)], ration_per_soldier=100, horizon=horizon)
        return resolve_siege(ledger=settlement['ledger'], encircled=encircled or {t:True for t in range(1,horizon+1)}, policy=self.policy())

    def test_reference_surrenders_after_four_actual_shortfalls(self):
        out = self.run_siege()
        self.assertEqual(out['surrender']['turn'], 22)
        self.assertEqual(out['ledger'][17]['moraleAfter'], 10000)
        self.assertEqual(out['ledger'][18]['moraleAfter'], 7500)
        self.assertFalse(out['ownershipTransferred'])
        self.assertFalse(out['s2GatePassed'])

    def test_stock_sensitivity_matches_target_without_forcing_turn(self):
        for stock in (8,12,18,20,30):
            self.assertEqual(self.run_siege(stock)['surrender']['turn'], stock+4)

    def test_relief_at_decisive_boundary_prevents_surrender(self):
        out = self.run_siege(deliveries={22:1200})
        self.assertEqual(out['ledger'][21]['moraleAfter'], 3750)
        self.assertIsNone(out['surrender'])

    def test_late_relief_does_not_reverse_terminal_surrender(self):
        out = self.run_siege(deliveries={23:1200})
        self.assertEqual(out['surrender']['turn'], 22)
        self.assertEqual(len(out['ledger']),22)

    def test_broken_encirclement_prevents_surrender_despite_hunger(self):
        out = self.run_siege(encircled={t:t<22 for t in range(1,37)})
        self.assertIsNone(out['surrender'])
        self.assertEqual(out['ledger'][-1]['moraleAfter'],0)

    def test_partial_supply_causes_proportional_loss_not_full_recovery(self):
        out = self.run_siege(stock=0, deliveries={t:50 for t in range(1,37)})
        self.assertEqual(out['surrender']['turn'],8)
        self.assertEqual(out['ledger'][0]['moraleAfter'],8750)

    def test_full_supply_caps_recovery(self):
        out = self.run_siege(stock=0, deliveries={t:100 for t in range(1,37)})
        self.assertIsNone(out['surrender'])
        self.assertTrue(all(r['moraleAfter']==10000 for r in out['ledger']))

    def test_partial_loss_integer_ceiling(self):
        for demand, loss in [(2501,1),(2499,2)]:
            out = resolve_siege(ledger=[dict(turn=1,rationDemand=demand,rationConsumed=demand-1,
                unmetRation=1,closingGrain=0)],encircled={1:True},policy=self.policy())
            self.assertEqual(out['ledger'][0]['moraleAfter'],10000-loss)

    def test_month_end_tax_cannot_feed_or_recover_same_boundary(self):
        state = settle_county(initial_grain=0,people=1,deliveries={},tax_grain={4:1000},
            recruitment_orders=[dict(id='guard',turn=1,troops=1,equipGrain=0)],ration_per_soldier=100,horizon=4)
        out = resolve_siege(ledger=state['ledger'],encircled={t:True for t in range(1,5)},policy=self.policy())
        self.assertEqual(out['surrender']['turn'],4)
        self.assertEqual(out['surrender']['warehouseGrainAtSurrender'],0)

    def test_repeat_and_input_nonmutation_and_invalid_ledger(self):
        row=dict(turn=1,rationDemand=100,rationConsumed=0,unmetRation=100,closingGrain=0)
        rows=[row]; original=copy.deepcopy(rows)
        kwargs=dict(ledger=rows,encircled={1:True},policy=self.policy())
        self.assertEqual(resolve_siege(**kwargs),resolve_siege(**kwargs))
        self.assertEqual(rows,original)
        row['rationConsumed']=1
        with self.assertRaises(ValueError): resolve_siege(**kwargs)
        row.update(rationConsumed=0,rationDemand=0,unmetRation=0)
        with self.assertRaises(ValueError): resolve_siege(**kwargs)

if __name__ == '__main__': unittest.main()
