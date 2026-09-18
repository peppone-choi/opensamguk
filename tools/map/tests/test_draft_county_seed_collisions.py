"""county-seed-collisions-v1 초안: 기계 필드가 커밋본과 맞고, 사람 판정을 짓지 않는다 (GH #806 S0)."""
import json
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT))

from tools.map import draft_county_seed_collisions as draft  # noqa: E402


class SeedCollisionLedgerTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.committed = json.loads(draft.LEDGER.read_text(encoding="utf-8"))
        cls.rebuilt = draft.build(cls.committed)

    def test_committed_ledger_matches_the_committed_tiles(self):
        self.assertEqual(self.committed, self.rebuilt)
        self.assertEqual(self.committed["counts"]["cells"], 14)
        self.assertEqual(self.committed["counts"]["cities"], 28)

    def test_red_probe_a_stale_member_list_is_caught(self):
        stale = json.loads(json.dumps(self.committed))
        stale["rows"][0]["members"].pop()
        self.assertNotEqual(draft.build(stale), stale)

    def test_generator_never_invents_a_ruling_and_keeps_a_human_one(self):
        fresh = draft.build(None)
        self.assertTrue(all(row["ruling"] is None and row["reviewState"] == "NEEDS_HUMAN_REVIEW"
                            for row in fresh["rows"]))
        ruled = json.loads(json.dumps(self.committed))
        ruled["rows"][0].update({"ruling": "DISTINCT_COUNTIES", "reviewState": "REVIEWED", "basis": "test"})
        kept = draft.build(ruled)["rows"][0]
        self.assertEqual((kept["ruling"], kept["reviewState"], kept["basis"]), ("DISTINCT_COUNTIES", "REVIEWED", "test"))


if __name__ == "__main__":
    unittest.main()
