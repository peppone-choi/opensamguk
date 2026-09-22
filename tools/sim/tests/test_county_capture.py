import json
import sys
import unittest
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from county_capture import settle_besieged_county
ROOT=Path(__file__).resolve().parents[3]

class CountyCaptureTest(unittest.TestCase):
    def run_case(self, **changes):
        values=dict(county_id='county',owner=2,besieger=1,initial_grain=18,people=5,
            deliveries={},tax_grain={24:10,27:10},recruitment_orders=[dict(id='guard',turn=1,troops=1,equipGrain=0)],
            ration_per_soldier=1,horizon=27,encircled={t:True for t in range(1,28)},
            policy=json.loads((ROOT/'data/curated/han/march-tempo-targets-v1.json').read_text())['siegeResolution'])
        values.update(changes);return settle_besieged_county(**values)

    def test_surrender_changes_owner_and_disarms_without_losing_people(self):
        out=self.run_case()
        self.assertEqual(out['capture']['turn'],22)
        self.assertEqual(out['capture']['demobilized'],1)
        self.assertEqual(out['ledger'][20]['ownerAfter'],2)
        self.assertEqual(out['ledger'][21]['ownerAfter'],1)
        self.assertEqual(out['ledger'][21]['people'],5)
        self.assertEqual(out['ledger'][-1]['troops'],0)
        self.assertEqual(out['taxByOwner'],{'2':0,'1':20})
        self.assertEqual(out['ledger'][-1]['closingGrain'],20)

    def test_same_boundary_tax_is_new_owner_and_cannot_undo_surrender(self):
        out=self.run_case(tax_grain={22:10})
        self.assertEqual(out['capture']['warehouseBeforeMonthlyTax'],0)
        self.assertEqual(out['ledger'][21]['closingGrain'],10)
        self.assertEqual(out['taxByOwner'],{'2':0,'1':10})

    def test_breaking_siege_retains_owner_and_garrison(self):
        out=self.run_case(encircled={t:t<22 for t in range(1,28)})
        self.assertIsNone(out['capture'])
        self.assertTrue(all(r['ownerAfter']==2 for r in out['ledger']))
        self.assertEqual(out['ledger'][-1]['troops'],1)
        self.assertEqual(out['taxByOwner'],{'2':20,'1':0})

    def test_terminal_capture_not_revoked_by_late_delivery(self):
        out=self.run_case(deliveries={23:100})
        self.assertEqual(out['capture']['turn'],22)
        self.assertEqual(out['ledger'][-1]['closingGrain'],120)
        self.assertEqual(out,self.run_case(deliveries={23:100}))

    def test_collection_resumes_only_after_actual_capture(self):
        out=self.run_case(tax_grain={},post_capture_tax_grain={3:10,21:10,24:10,27:10})
        self.assertEqual(out['capture']['turn'],22)
        self.assertEqual(out['taxByOwner'],{'2':0,'1':20})
        self.assertEqual(out['ledger'][-1]['closingGrain'],20)
        out=self.run_case(tax_grain={},post_capture_tax_grain={24:10},
                          encircled={t:False for t in range(1,28)})
        self.assertEqual(out['taxByOwner'],{'2':0,'1':0})

    def test_duplicate_garrison_order_does_not_cancel_capture(self):
        out=self.run_case(recruitment_orders=[dict(id='guard',turn=t,troops=1,equipGrain=0) for t in (1,5)])
        self.assertEqual(out,self.run_case())

    def test_invalid_or_failed_garrison_is_not_capture(self):
        for change in [dict(owner=1),dict(besieger=0),dict(people=0),
                       dict(recruitment_orders=[dict(id='late',turn=2,troops=1,equipGrain=0)])]:
            with self.assertRaises(ValueError): self.run_case(**change)

if __name__=='__main__':unittest.main()
