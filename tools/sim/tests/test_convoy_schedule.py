import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from convoy_schedule import schedule_convoys
from county_economy import settle_county


class ConvoyScheduleTest(unittest.TestCase):
    def order(self, **values):
        return dict(id='c1', source='a', destination='b', grain=7, travelTurns=2, **values)

    def run_case(self, orders=None, **values):
        return schedule_convoys(stocks={'a': 10, 'b': 3}, orders=orders or [self.order()], horizon=4, **values)

    def test_dispatch_reserves_source_and_arrival_is_delayed(self):
        result = self.run_case()
        self.assertEqual(result['stocksAfterDispatch'], {'a': 3, 'b': 3})
        self.assertEqual(result['arrivals'], {'a': {}, 'b': {3: 7}})
        self.assertEqual([r['inTransit'] for r in result['ledger']], [7, 7, 0, 0])
        self.assertEqual([r['arrived'] for r in result['ledger']], [0, 0, 7, 7])
        for row in result['ledger']:
            self.assertEqual(sum(result['stocksAfterDispatch'].values()) + row['inTransit'] + row['arrived'], 13)

    def test_future_arrival_is_retained_in_transit(self):
        order = self.order(); order['travelTurns'] = 8
        result = self.run_case([order])
        self.assertEqual(result['arrivals']['b'], {})
        self.assertEqual(result['ledger'][-1]['inTransit'], 7)
        self.assertEqual(result['decisions'][0]['arrivalTurn'], 9)

    def test_insufficient_stock_and_unreachable_route_do_not_debit(self):
        for change, status in [({'grain': 11}, 'REJECTED_STOCK'), ({'travelTurns': None}, 'NO_ROUTE')]:
            order = self.order(); order.update(change)
            result = self.run_case([order])
            self.assertEqual(result['decisions'][0]['status'], status)
            self.assertEqual(result['stocksAfterDispatch'], {'a': 10, 'b': 3})
            self.assertEqual(result['ledger'][-1]['arrived'], 0)

    def test_duplicates_never_dispatch_twice(self):
        order = self.order()
        result = self.run_case([order, dict(order)])
        self.assertEqual(result['stocksAfterDispatch']['a'], 3)
        self.assertEqual([r['status'] for r in result['decisions']], ['DISPATCHED', 'DUPLICATE'])

    def test_rejected_duplicates_preserve_first_result(self):
        for change, status in [({'grain': 11}, 'REJECTED_STOCK'), ({'travelTurns': None}, 'NO_ROUTE')]:
            order = self.order(); order.update(change)
            result = self.run_case([order, dict(order)])
            self.assertEqual([r['status'] for r in result['decisions']], [status, 'DUPLICATE'])
            self.assertEqual(result['decisions'][1]['originalStatus'], status)
            self.assertEqual(result['stocksAfterDispatch'], {'a': 10, 'b': 3})

    def test_conflicting_id_rejects_input(self):
        order = self.order(); changed = dict(order, grain=6)
        with self.assertRaisesRegex(ValueError, 'conflicting'):
            self.run_case([order, changed])

    def test_allocation_is_deterministic_and_never_spends_future_arrivals(self):
        forward = self.order()
        reverse = dict(id='c2', source='b', destination='a', grain=7, travelTurns=1)
        a = self.run_case([forward, reverse]); b = self.run_case([reverse, forward])
        self.assertEqual(a, b)
        self.assertEqual(a['decisions'][1]['status'], 'REJECTED_STOCK')

    def test_county_settlement_consumes_only_arrived_grain_and_conserves_network(self):
        convoys = schedule_convoys(stocks={'a': 10, 'b': 0},
                                  orders=[dict(id='c', source='a', destination='b', grain=8, travelTurns=2)],
                                  horizon=5)
        counties = {}
        for county in ['a', 'b']:
            counties[county] = settle_county(
                initial_grain=convoys['stocksAfterDispatch'][county], people=5,
                deliveries=convoys['arrivals'][county], tax_grain={},
                recruitment_orders=[dict(id='r', turn=1, troops=2, equipGrain=0)] if county == 'b' else [],
                ration_per_soldier=1, horizon=5)['ledger']
        self.assertEqual([r['rationConsumed'] for r in counties['b']], [0, 0, 2, 2, 2])
        consumed = 0
        for index, transfer in enumerate(convoys['ledger']):
            consumed += sum(rows[index]['rationConsumed'] for rows in counties.values())
            closing = sum(rows[index]['closingGrain'] for rows in counties.values())
            self.assertEqual(closing + transfer['inTransit'] + consumed, 10)

    def test_invalid_quantities_endpoints_and_travel_fail(self):
        for change in [{'grain': True}, {'grain': 0}, {'travelTurns': -1}, {'travelTurns': 0}, {'source': 'missing'}, {'destination': 'a'}]:
            order = self.order(); order.update(change)
            with self.subTest(change=change), self.assertRaises(ValueError):
                self.run_case([order])


if __name__ == '__main__':
    unittest.main()
