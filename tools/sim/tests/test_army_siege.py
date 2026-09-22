import json
import sys
import unittest
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from county_economy import settle_county
from army_siege import army_encirclement
ROOT=Path(__file__).resolve().parents[3]

class ArmySiegeTest(unittest.TestCase):
    def run_case(self,grain=100,troops=2,travel=2,encounters=None,control=None,people=2,duplicate=False):
        state=settle_county(initial_grain=grain,people=people,deliveries={5:100},tax_grain={},
            recruitment_orders=[dict(id='army',turn=t,troops=troops,equipGrain=0) for t in ([1,5] if duplicate else [1])],ration_per_soldier=1,horizon=6)
        policy=json.loads((ROOT/'data/curated/han/march-tempo-targets-v1.json').read_text())['armyEncirclement']
        return army_encirclement(settlement=state,travel_turns=travel,defender_troops=1,
            approaches_controlled=control or {t:True for t in range(1,7)},encounters=encounters or set(),policy=policy)

    def test_cannot_encircle_before_actual_arrival(self):
        out=self.run_case();self.assertEqual(out['arrivalTurn'],3)
        self.assertEqual(out['encircled'],{1:False,2:False,3:True,4:True,5:True,6:True})

    def test_starvation_ends_expedition_and_late_supply_does_not_restart(self):
        out=self.run_case(grain=4)
        self.assertIsNone(out['arrivalTurn'])
        self.assertTrue(all(not v for v in out['encircled'].values()))
        self.assertEqual(out['ledger'][-1]['state'],'WITHDRAWN_FOR_RATIONS')

    def test_arrival_boundary_encounter_prevents_encirclement(self):
        out=self.run_case(encounters={3})
        self.assertEqual(out['ledger'][2]['state'],'HALTED_BY_ENCOUNTER')
        self.assertFalse(any(out['encircled'].values()))

    def test_insufficient_force_and_no_route_and_failed_muster_cannot_encircle(self):
        for changes in (dict(troops=1),dict(travel=None),dict(people=0)):
            self.assertFalse(any(self.run_case(**changes)['encircled'].values()))

    def test_recruitment_redelivery_does_not_cancel_expedition(self):
        self.assertEqual(self.run_case(),self.run_case(duplicate=True))

    def test_approach_loss_breaks_siege_at_same_boundary(self):
        out=self.run_case(control={t:t!=4 for t in range(1,7)})
        self.assertTrue(out['encircled'][3]);self.assertFalse(out['encircled'][4]);self.assertTrue(out['encircled'][5])

if __name__=='__main__':unittest.main()
