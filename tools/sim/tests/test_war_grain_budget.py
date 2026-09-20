import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from county_economy import settle_county, assess_war_grain_budget


class WarGrainBudgetTest(unittest.TestCase):
    def target(self):
        return dict(status='OWNER_DELEGATED_DECISION', periodTurns=36,
                    minPercentOfPeriodNetGrainTax=25, maxPercentOfPeriodNetGrainTax=50)

    def settlement(self, *, grain=100, tax=100, cost=3):
        return settle_county(initial_grain=grain, people=5, deliveries={}, tax_grain={36: tax},
                             recruitment_orders=[dict(id='muster', turn=1, troops=1, equipGrain=cost)],
                             ration_per_soldier=1, horizon=36)

    def test_uses_demand_not_actual_consumption_and_excludes_initial_stock(self):
        result = assess_war_grain_budget(self.settlement(grain=3), self.target())
        self.assertEqual(result['requiredGrain'], 39)
        self.assertEqual(result['netGrainTax'], 100)
        self.assertTrue(result['withinBudget'])
        self.assertFalse(result['fullySupplied'])
        self.assertFalse(result['eligibleReference'])

    def test_reference_has_full_muster_supply_and_budget(self):
        result = assess_war_grain_budget(self.settlement(), self.target())
        self.assertTrue(result['eligibleReference'])
        self.assertEqual(result['percentOfNetGrainTax'], 39)
        self.assertFalse(result['s2GatePassed'])

    def test_inclusive_bounds(self):
        for tax in [144, 72]:
            self.assertTrue(assess_war_grain_budget(self.settlement(tax=tax, cost=0), self.target())['withinBudget'])
        for tax in [145, 71]:
            self.assertFalse(assess_war_grain_budget(self.settlement(tax=tax, cost=0), self.target())['withinBudget'])

    def test_rejected_muster_is_not_a_cheap_reference(self):
        result = assess_war_grain_budget(self.settlement(grain=0), self.target())
        self.assertFalse(result['allRecruitmentsApplied'])
        self.assertFalse(result['eligibleReference'])

    def test_late_muster_does_not_qualify_as_full_period_upkeep(self):
        state = settle_county(initial_grain=25, people=1, deliveries={}, tax_grain={36: 100},
                              recruitment_orders=[dict(id='late', turn=36, troops=1, equipGrain=24)],
                              ration_per_soldier=1, horizon=36)
        with self.assertRaises(ValueError):
            assess_war_grain_budget(state, self.target())

    def test_late_duplicate_does_not_change_initial_cohort(self):
        state = settle_county(initial_grain=100, people=1, deliveries={}, tax_grain={36: 100},
                              recruitment_orders=[dict(id='initial', turn=t, troops=1, equipGrain=3)
                                                  for t in (1, 36)],
                              ration_per_soldier=1, horizon=36)
        self.assertTrue(assess_war_grain_budget(state, self.target())['eligibleReference'])

    def test_zero_tax_is_unknown_not_zero_cost_success(self):
        result = assess_war_grain_budget(self.settlement(tax=0), self.target())
        self.assertIsNone(result['percentOfNetGrainTax'])
        self.assertFalse(result['eligibleReference'])

    def test_incomplete_window_and_unapproved_target_fail(self):
        state = self.settlement(); state['ledger'].pop()
        with self.assertRaises(ValueError):
            assess_war_grain_budget(state, self.target())
        target = self.target(); target['status'] = 'EXPLORATORY'
        with self.assertRaises(ValueError):
            assess_war_grain_budget(self.settlement(), target)


if __name__ == '__main__':
    unittest.main()
