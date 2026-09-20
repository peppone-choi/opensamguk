import sys
import json
import hashlib
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from march_tempo import Graph
from supply_passage import passage_snapshot, ArmyPresence
from convoy_schedule import schedule_convoys
from county_economy import settle_county


def graph():
    g = Graph.__new__(Graph)
    g.center = {i: (i, 0) for i in range(4)}
    g.rough_share = {i: 0 for i in range(4)}
    g.adj = {0: {1, 3}, 1: {0, 2}, 2: {1, 3}, 3: {0, 2}}
    g.km = lambda a, b: 1 if 3 not in (a, b) else 3
    return g


class SupplyPassageTest(unittest.TestCase):
    def snapshot(self, owners=None, armies=(), wars=frozenset({2})):
        return passage_snapshot(provinces=set(range(4)), nation_id=1,
                                owners=owners or {i: 1 for i in range(4)},
                                armies=armies, effective_war_nations=wars)

    def test_foreign_and_neutral_territory_are_blocked_without_mutation(self):
        owners = {0: 1, 1: 0, 2: 1, 3: 2}
        result = self.snapshot(owners)
        self.assertEqual(result['allowedProvinces'], [0, 2])
        self.assertEqual(owners, {0: 1, 1: 0, 2: 1, 3: 2})
        self.assertEqual(result['blockedReasons']['1'], ['FOREIGN_TERRITORY'])

    def test_only_effective_war_or_explicit_neutral_armies_block(self):
        armies = [ArmyPresence(1, 2), ArmyPresence(3, 3)]
        result = self.snapshot(armies=armies)
        self.assertEqual(result['allowedProvinces'], [0, 2, 3])
        self.assertEqual(self.snapshot(armies=armies, wars=set())['allowedProvinces'], [0, 1, 2, 3])
        self.assertNotIn(1, self.snapshot(armies=[ArmyPresence(1, 0, True)])['allowedProvinces'])
        self.assertEqual(self.snapshot(armies=[ArmyPresence(1, 1)])['allowedProvinces'], [0, 1, 2, 3])

    def test_unknown_ownership_and_ambiguous_stateless_army_rejected(self):
        with self.assertRaises(ValueError):
            self.snapshot({0: 1})
        with self.assertRaises(ValueError):
            self.snapshot(armies=[ArmyPresence(1, 0)])
        with self.assertRaises(ValueError):
            self.snapshot(armies=[ArmyPresence(10, 2)])

    def test_filter_checks_endpoints_and_reroutes_without_mutating_graph(self):
        g = graph()
        self.assertEqual(g.shortest(0, 2, 1)[0], 2)
        allowed = set(self.snapshot(armies=[ArmyPresence(1, 2)])['allowedProvinces'])
        self.assertEqual(g.shortest(0, 2, 1, allowed_provinces=allowed)[0], 6)
        self.assertEqual(g.shortest(0, 2, 1)[0], 2)
        for src, dst in [(1, 2), (0, 1), (1, 1)]:
            self.assertIsNone(g.shortest(src, dst, 1, allowed_provinces=allowed))

    def test_snapshot_order_independent_and_retains_both_reasons(self):
        owners = {0: 1, 1: 0, 2: 1, 3: 1}
        armies = [ArmyPresence(1, 2), ArmyPresence(3, 0, True)]
        a = self.snapshot(owners, armies)
        b = self.snapshot(dict(reversed(list(owners.items()))), list(reversed(armies)))
        self.assertEqual(a, b)
        self.assertEqual(a['blockedReasons']['1'], ['FOREIGN_TERRITORY', 'HOSTILE_OR_NEUTRAL_ARMED_OCCUPANCY'])

    def test_json_roundtrip_preserves_canonical_hash(self):
        result = passage_snapshot(provinces={1, 2, 10}, nation_id=1,
                                  owners={1: 0, 2: 0, 10: 0}, armies=[], effective_war_nations=set())
        canonical = lambda value: json.dumps(value, sort_keys=True, separators=(',', ':')).encode()
        self.assertEqual(hashlib.sha256(canonical(result)).hexdigest(),
                         hashlib.sha256(canonical(json.loads(json.dumps(result)))).hexdigest())

    def test_blocked_route_cannot_create_or_deliver_grain(self):
        for blocked in (False, True):
            armies = [ArmyPresence(1, 2), ArmyPresence(3, 0, True)] if blocked else []
            allowed = set(self.snapshot(armies=armies)['allowedProvinces'])
            route = graph().shortest(0, 2, 1, allowed_provinces=allowed)
            # Speed is a fixture assumption; one turn's travel means boundary 2 arrival.
            travel = None if route is None else 1
            out = schedule_convoys(stocks={'source': 2, 'castle': 0}, horizon=3,
                                   orders=[dict(id='relief', source='source', destination='castle', grain=2, travelTurns=travel)])
            state = settle_county(initial_grain=out['stocksAfterDispatch']['castle'], people=1,
                                  deliveries=out['arrivals']['castle'], tax_grain={},
                                  recruitment_orders=[dict(id='guard', turn=1, troops=1, equipGrain=0)],
                                  ration_per_soldier=1, horizon=3)
            # Arrival cannot retroactively feed boundary 1. Both cases starve then.
            self.assertEqual(state['ledger'][0]['unmetRation'], 1)
            self.assertEqual(state['ledger'][1]['unmetRation'], int(blocked))
            consumed = 0
            for row, transport in zip(state['ledger'], out['ledger']):
                consumed += row['rationConsumed']
                self.assertEqual(out['stocksAfterDispatch']['source'] + row['closingGrain'] + transport['inTransit'] + consumed, 2)
            self.assertEqual(out['decisions'][0]['status'], 'NO_ROUTE' if blocked else 'DISPATCHED')


if __name__ == '__main__':
    unittest.main()
