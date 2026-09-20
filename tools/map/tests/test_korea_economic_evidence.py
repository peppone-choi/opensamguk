import json
import unittest
from pathlib import Path
ROOT=Path(__file__).resolve().parents[3]
class KoreaEconomicEvidenceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.evidence=json.loads((ROOT/'data/curated/han/korea-economic-evidence-v1.json').read_text())
        cls.economy=json.loads((ROOT/'data/curated/han/county-economy-inputs-v1.json').read_text())
    def test_every_scope_is_bound_without_duplicating_totals(self):
        rows={r['jurisdictionId']:r for r in self.economy['jurisdictions']}
        ids=set()
        for group in self.evidence['groups']:
            self.assertNotIn(group['id'],ids)
            ids.add(group['id'])
            for jid in group['jurisdictionIds']:
                self.assertIn(group['id'],rows[jid]['historicalEconomyRefs'])
                if rows[jid]['households'] is not None:
                    self.assertEqual('GAME_DESIGN_SHARE_OF_JUNGUOZHI_TOTAL_NOT_LOCAL_CENSUS',rows[jid]['householdsBasis'])
            self.assertIsNone(group['perCityAllocation'])
            for activity in group['activities']:
                self.assertTrue(activity['evidence']['quote'])
                self.assertTrue(activity['evidence']['url'])
        self.assertEqual(self.evidence,self.economy['historicalEconomy'])
    def test_uncertain_ranges_and_missing_values_are_preserved(self):
        groups={g['id']:g for g in self.evidence['groups']}
        h=groups['SGZ30-JINBYEONHAN']['households']
        self.assertEqual((40000,50000,'RANGE'),(h['value'],h['upperValue'],h['qualifier']))
        self.assertEqual('MORE_THAN',groups['SGZ30-MAHAN']['households']['qualifier'])
        self.assertIsNone(groups['SGZ30-NORTHOKJEO']['households'])
        self.assertIsNone(groups['SGZ30-JUHO']['households'])
        self.assertNotIn('X030',groups['SGZ30-DONGOKJEO']['jurisdictionIds'])
        self.assertEqual('HOUSEHOLDS',groups['SGZ30-BUYEO']['households']['unit'])
