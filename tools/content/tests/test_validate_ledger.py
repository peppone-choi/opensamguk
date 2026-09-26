import importlib.util
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[1] / "validate_ledger.py"
SPEC = importlib.util.spec_from_file_location("validate_ledger", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class LedgerValidatorTest(unittest.TestCase):
    def setUp(self):
        self.document = {
            "schemaVersion": 1,
            "rows": [
                {
                    "id": "office.prefect",
                    "grade": "PRIMARY",
                    "sources": [{"book": "後漢書", "volume": "118", "section": "百官志", "quote": "每郡置太守一人", "grade": "PRIMARY"}],
                    "values": {"gameRank": {"value": 2, "status": "CONFIRMED", "decidedBy": "구현 에이전트", "decidedAt": "2026-09-25", "basis": "게임 등급"}},
                },
            ],
        }

    def test_valid_historical_row(self):
        self.assertEqual([], MODULE.validate(self.document))

    def test_missing_quote(self):
        self.document["rows"][0]["sources"][0].pop("quote")
        self.assertIn("rows[0].sources[0].quote: required", MODULE.validate(self.document))

    def test_unknown_grade(self):
        self.document["rows"][0]["sources"][0]["grade"] = "MIXED"
        self.assertIn("rows[0].sources[0].grade: unknown MIXED", MODULE.validate(self.document))

    def test_number_decider_required(self):
        self.document["rows"][0]["values"]["gameRank"].pop("decidedBy")
        self.assertIn("rows[0].values.gameRank.decidedBy: required for numeric value", MODULE.validate(self.document))

    def test_duplicate_and_dangling(self):
        self.document["rows"].append(dict(self.document["rows"][0], refs=["missing"]))
        errors = MODULE.validate(self.document)
        self.assertTrue(any("duplicate" in error for error in errors))
        self.assertTrue(any("dangling" in error for error in errors))

    def test_game_term_needs_visible_label(self):
        self.document["rows"] = [{"id": "preset.example", "grade": "GAME_TERM", "sources": [], "gameTermReason": "게임 분류", "displayBadge": "게임 용어"}]
        self.assertEqual([], MODULE.validate(self.document))
        self.document["rows"][0].pop("displayBadge")
        self.assertTrue(any("displayBadge" in error for error in MODULE.validate(self.document)))


if __name__ == "__main__":
    unittest.main()
