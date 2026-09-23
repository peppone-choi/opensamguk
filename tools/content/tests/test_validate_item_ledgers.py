import copy
import json
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "tools" / "content"))
from validate_card_content import validate_item_ledgers


def read(path):
    return json.loads((ROOT / path).read_text(encoding="utf-8"))


class ItemLedgerValidationTest(unittest.TestCase):
    def setUp(self):
        self.source = read("data/extracted/item/items.json")
        self.treasures = read("data/curated/han/hwiha-treasure-cards-v1.json")
        self.equipment = read("data/curated/han/hwiha-equipment-v1.json")
        self.excluded = read("data/curated/han/hwiha-items-excluded-v1.json")

    def validate(self):
        return validate_item_ledgers(self.source, self.treasures, self.equipment, self.excluded)

    def test_current_partition_and_pending_legacy_two_are_valid(self):
        self.assertEqual([], self.validate())
        self.assertTrue(all(row["issuedCopies"] is None for row in self.treasures["cards"]
                            if row["legacyAvailability"] == 2))

    def test_double_consumed_and_missing_source_rows_fail(self):
        self.equipment["equipment"].append(copy.deepcopy(self.equipment["equipment"][0]))
        self.assertTrue(any("double consumed" in error for error in self.validate()))
        self.equipment["equipment"].pop()
        self.equipment["equipment"].pop()
        self.assertTrue(any("partial item split" in error for error in self.validate()))

    def test_dangling_source_and_partial_header_fail(self):
        self.excluded["excluded"][0]["sourceCode"] = "missing-item"
        self.assertTrue(any("dangling item source" in error for error in self.validate()))
        self.excluded["excluded"][0]["sourceCode"] = "None"
        del self.treasures["cards"][0]["header"]["name"]
        self.assertTrue(any("item contract/source mismatch" in error for error in self.validate()))

    def test_copy_count_cannot_be_inferred_from_legacy_availability_two(self):
        row = next(row for row in self.treasures["cards"] if row["legacyAvailability"] == 2)
        row["issuedCopies"] = 2
        self.assertTrue(any("item contract/source mismatch" in error for error in self.validate()))


if __name__ == "__main__":
    unittest.main()
