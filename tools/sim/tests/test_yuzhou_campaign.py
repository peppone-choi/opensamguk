"""Composition regressions: military control must govern real funded deliveries."""
import json
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import army_siege as A
import county_capture as K
import county_economy as C
from march_tempo import Graph
from yuzhou_campaign import settle_campaign_relief

ROOT = Path(__file__).resolve().parents[3]


class CampaignCompositionTest(unittest.TestCase):
    def run_case(self, *, army_grain=16, encounter=False, release=False):
        tempo = json.loads((ROOT / 'data/curated/han/march-tempo-targets-v1.json').read_text())
        graph = Graph.__new__(Graph)
        graph.center = {0: (0, 0), 1: (1, 0), 2: (2, 0)}
        graph.adj = {0: {1}, 1: {0, 2}, 2: {1}}
        graph.rough_share = {p: 0 for p in graph.center}
        graph.km = lambda a, b: 15
        army = C.settle_county(initial_grain=army_grain, people=2, deliveries={}, tax_grain={},
            recruitment_orders=[dict(id='army', turn=1, troops=2, equipGrain=0)],
            ration_per_soldier=1, horizon=8)
        military = A.army_encirclement(settlement=army, travel_turns=1, defender_troops=1,
            approaches_controlled={t: not release or t <= 5 for t in range(1, 9)},
            encounters={2} if encounter else set(), policy=tempo['armyEncirclement'])

        def settle(deliveries):
            return K.settle_besieged_county(county_id='target', owner=2, besieger=1,
                initial_grain=1, people=5, deliveries=deliveries, tax_grain={},
                post_capture_tax_grain={6: 7},
                recruitment_orders=[dict(id='guard', turn=1, troops=1, equipGrain=0)],
                ration_per_soldier=1, horizon=8, encircled=military['encircled'],
                policy=tempo['siegeResolution'])

        return settle_campaign_relief(graph=graph, path=[0, 1, 2], cost=30, speed=30,
            rough=1.5, owners={p: 2 for p in graph.center}, encircled=military['encircled'],
            destination_seat=2, destination_provinces={1, 2}, donor='depot', destination='target',
            donor_stock=4, grain=4, settle_county=settle, horizon=8)

    def test_army_arrival_blocks_same_boundary_delivery_then_capture_owns_tax(self):
        movement, transport, county = self.run_case()
        self.assertIsNone(movement['arrivalTurn'])
        self.assertEqual(transport['stocksAfterDispatch']['depot'], 0)
        self.assertEqual(transport['ledger'][-1]['inTransit'], 4)
        self.assertEqual(county['capture']['turn'], 5)
        self.assertEqual(county['taxByOwner'], {'2': 0, '1': 7})
        self.assertEqual(county['ledger'][-1]['people'], 5)
        self.assertEqual(county['ledger'][-1]['troops'], 0)

    def test_failed_army_cannot_block_relief_or_collect_target_tax(self):
        for args in (dict(army_grain=2), dict(encounter=True)):
            with self.subTest(args=args):
                movement, transport, county = self.run_case(**args)
                self.assertEqual(movement['arrivalTurn'], 2)
                self.assertEqual(transport['arrivals']['target'], {2: 4})
                self.assertEqual(transport['ledger'][-1]['inTransit'], 0)
                self.assertIsNone(county['capture'])
                self.assertEqual(sum(county['taxByOwner'].values()), 0)

    def test_capture_keeps_entire_county_closed_after_army_releases_approaches(self):
        movement, transport, county = self.run_case(release=True)
        self.assertEqual(county['capture']['turn'], 5)
        self.assertIsNone(movement['arrivalTurn'])
        self.assertEqual(transport['arrivals']['target'], {})
        self.assertEqual(movement['ledger'][5]['movedCostKm'], 0)
        self.assertEqual(transport['ledger'][-1]['inTransit'], 4)


if __name__ == '__main__':
    unittest.main()
