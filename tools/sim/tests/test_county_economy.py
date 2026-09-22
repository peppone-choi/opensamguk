import sys
import unittest
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from county_economy import settle_county


class CountyEconomyTest(unittest.TestCase):
    def run_case(self, **overrides):
        args = dict(initial_grain=0, people=10, deliveries={}, tax_grain={},
                    recruitment_orders=[], ration_per_soldier=1, horizon=4)
        args.update(overrides)
        return settle_county(**args)

    def test_arrivals_recruitment_rations_and_conservation(self):
        r = self.run_case(deliveries={2: 8}, tax_grain={3: 2}, recruitment_orders=[
            dict(id='a', turn=2, troops=2, equipGrain=3)])
        self.assertEqual([x['closingGrain'] for x in r['ledger']], [0, 3, 3, 1])
        for x in r['ledger']:
            self.assertEqual(x['openingGrain'] + x['delivered'] + x['taxGrain'],
                             x['equipmentConsumed'] + x['rationConsumed'] + x['closingGrain'])
            self.assertEqual(x['people'] + x['troops'], 10)
        self.assertEqual(r['status'], 'EXPLORATORY')

    def test_future_tax_cannot_pay_recruitment(self):
        r = self.run_case(tax_grain={3: 9}, recruitment_orders=[dict(id='a',turn=2,troops=2,equipGrain=2)])
        self.assertEqual(r['recruitDecisions'][0]['status'], 'REJECTED_GRAIN')
        self.assertEqual(r['ledger'][-1]['people'], 10)

    def test_monthly_tax_arrives_after_this_boundary_military_rations(self):
        r = self.run_case(tax_grain={1: 2}, recruitment_orders=[
            dict(id='a', turn=1, troops=2, equipGrain=0)])
        self.assertEqual([x['rationConsumed'] for x in r['ledger']], [0, 2, 0, 0])
        self.assertEqual([x['closingGrain'] for x in r['ledger']], [2, 0, 0, 0])
        self.assertEqual(r['ledger'][0]['unmetRation'], 2)

    def test_same_boundary_monthly_tax_cannot_prepay_recruitment(self):
        r = self.run_case(tax_grain={1: 10}, recruitment_orders=[
            dict(id='a', turn=1, troops=2, equipGrain=5)])
        self.assertEqual(r['recruitDecisions'][0]['status'], 'REJECTED_GRAIN')
        self.assertEqual(r['ledger'][0]['closingGrain'], 10)
        self.assertEqual(r['ledger'][0]['troops'], 0)

    def test_population_shortage_is_atomic(self):
        r = self.run_case(initial_grain=20,recruitment_orders=[dict(id='a',turn=1,troops=11,equipGrain=2)])
        self.assertEqual(r['recruitDecisions'][0]['status'], 'REJECTED_PEOPLE')
        self.assertEqual(r['ledger'][-1]['closingGrain'], 20)

    def test_duplicate_applied_and_rejected_do_not_retry(self):
        orders=[dict(id='a',turn=1,troops=2,equipGrain=2),dict(id='a',turn=3,troops=2,equipGrain=2)]
        for grain, expected in [(0,'REJECTED_GRAIN'), (2,'APPLIED')]:
            r=self.run_case(initial_grain=grain,tax_grain={2:20},recruitment_orders=orders)
            self.assertEqual([d['status'] for d in r['recruitDecisions']], [expected,'DUPLICATE'])
            self.assertEqual(r['recruitDecisions'][1]['originalStatus'], expected)
            self.assertEqual(r['ledger'][-1]['troops'], 2 if grain else 0)

    def test_changed_duplicate_is_rejected(self):
        for field in ['troops','equipGrain']:
            a=dict(id='a',turn=1,troops=2,equipGrain=2);b=dict(a,turn=2);b[field]=3
            with self.assertRaisesRegex(ValueError,'conflicting'):
                self.run_case(recruitment_orders=[a,b])

    def test_unmet_rations_do_not_kill_soldiers(self):
        r=self.run_case(initial_grain=3,recruitment_orders=[dict(id='a',turn=1,troops=2,equipGrain=0)])
        self.assertEqual([x['rationConsumed'] for x in r['ledger']], [2,1,0,0])
        self.assertEqual([x['unmetRation'] for x in r['ledger']], [0,1,2,2])
        self.assertEqual(r['ledger'][-1]['troops'],2)

    def test_order_insertion_is_irrelevant(self):
        orders=[dict(id='b',turn=1,troops=3,equipGrain=2),dict(id='a',turn=1,troops=2,equipGrain=2)]
        self.assertEqual(self.run_case(initial_grain=2,recruitment_orders=orders),self.run_case(initial_grain=2,recruitment_orders=orders[::-1]))

    def test_invalid_inputs_fail_before_settlement(self):
        for args in [dict(people=True),dict(initial_grain=-1),dict(ration_per_soldier=0),dict(horizon=0),dict(deliveries={0:1}),dict(tax_grain={5:1}),dict(deliveries={1:1.5}),dict(recruitment_orders=[dict(id='',turn=1,troops=1,equipGrain=0)]),dict(recruitment_orders=[dict(id='a',turn=1,troops=0,equipGrain=0)])]:
            with self.subTest(args=args),self.assertRaises(ValueError): self.run_case(**args)

if __name__ == '__main__': unittest.main()
