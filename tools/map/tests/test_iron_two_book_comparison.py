import copy
import hashlib
import json
import unittest

from tools.map import check_iron_two_book_comparison as audit


class IronTwoBookComparisonTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        raw = audit.SITES.read_bytes()
        cls.sites = json.loads(raw)
        cls.comparison = json.loads(audit.COMPARISON.read_bytes())
        cls.digest = hashlib.sha256(raw).hexdigest()

    def test_complete_live_set_with_positive_and_missing_controls(self):
        counts = audit.check(self.sites, self.comparison, self.digest)
        self.assertGreater(counts["statuses"]["TEXTUAL_COUNTERPART"], 0)
        self.assertGreater(counts["statuses"]["FORMER_HAN_ANNOTATION_NOT_COLLECTED"], 0)
        self.assertEqual(
            {e["id"] for e in self.sites["entries"] if e["resource"] == "IRON" and e["era"] == "LATER_HAN"},
            {row["laterHan"]["entryId"] for row in self.comparison["rows"]},
        )

    def test_old_hash_or_missing_later_han_row_is_red(self):
        changed = copy.deepcopy(self.comparison)
        changed["inputs"]["resourceLedger"]["sha256"] = "0" * 64
        with self.assertRaisesRegex(ValueError, "stale"):
            audit.check(self.sites, changed, self.digest)
        changed = copy.deepcopy(self.comparison)
        changed["rows"].pop()
        with self.assertRaisesRegex(ValueError, "missing or extra"):
            audit.check(self.sites, changed, self.digest)


if __name__ == "__main__":
    unittest.main()
