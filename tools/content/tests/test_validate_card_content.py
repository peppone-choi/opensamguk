import copy
import json
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "tools" / "content"))
from validate_card_content import validate_current, validate_domestic, validate_sites


def ledger(name):
    return json.loads((ROOT / "data" / "curated" / "han" / name).read_text(encoding="utf-8"))


class CardContentValidationTest(unittest.TestCase):
    def setUp(self):
        self.domestic = ledger("domestic-v1.json")
        self.sites = ledger("resource-sites-v1.json")
        self.extracts = ledger("resource-site-source-extracts-v1.json")
        self.production = ledger("resource-production-v1.json")

    def test_current_ledgers_pass_without_exact_count_requirement(self):
        self.assertEqual([], validate_current())
        smaller = copy.deepcopy(self.sites)
        smaller["entries"] = [row for row in smaller["entries"] if row["matchStatus"] != "UNKNOWN_NO_SOURCE"]
        self.assertEqual([], validate_sites(smaller, self.extracts, self.production))

    def test_double_consumption_is_rejected(self):
        county = next(row for row in self.production["counties"] if row["sites"])
        county["sites"].append(copy.deepcopy(county["sites"][0]))
        self.assertTrue(any("double consumed" in error for error in
                            validate_sites(self.sites, self.extracts, self.production)))

    def test_partial_construction_is_rejected(self):
        self.domestic["works"]["kinds"][0].pop("name")
        self.assertTrue(any("partial construction" in error for error in validate_domestic(self.domestic)))

    def test_dangling_claim_evidence_and_production_references_are_rejected(self):
        source = self.sites["entries"][0]["id"]
        self.extracts["extracts"] = [row for row in self.extracts["extracts"] if row["extractId"] != source]
        errors = validate_sites(self.sites, self.extracts, self.production)
        self.assertTrue(any("dangling claim/evidence" in error for error in errors))
        county = next(row for row in self.production["counties"] if row["sites"])
        county["sites"][0]["id"] = "missing"
        errors = validate_sites(self.sites, self.extracts, self.production)
        self.assertTrue(any("dangling production site" in error for error in errors))


if __name__ == "__main__":
    unittest.main()
