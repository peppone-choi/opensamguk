import copy
import json
import unittest

from tools.content.validate_unit_identity_sources import (
    IDENTITY_LEDGER,
    UNIT_LEDGER,
    validate_identities,
    validate_units,
)


class UnitIdentitySourceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.units = json.loads(UNIT_LEDGER.read_text(encoding="utf-8"))
        cls.identities = json.loads(IDENTITY_LEDGER.read_text(encoding="utf-8"))

    def test_checked_in_ledgers_cover_source_backed_units_and_all_presets(self):
        self.assertEqual(validate_units(self.units), 10)
        self.assertEqual(validate_identities(self.identities), 15)

    def test_historical_row_without_quote_is_rejected(self):
        broken = copy.deepcopy(self.units)
        broken["rows"][0]["sources"][0]["quote"] = ""
        with self.assertRaisesRegex(ValueError, "quote"):
            validate_units(broken)

    def test_historical_name_must_appear_in_quote(self):
        broken = copy.deepcopy(self.units)
        broken["rows"][0]["historicalName"] = "不存在的兵名"
        with self.assertRaisesRegex(ValueError, "historical name"):
            validate_units(broken)

    def test_game_term_badge_is_required(self):
        broken = copy.deepcopy(self.identities)
        broken["rows"][0].pop("displayBadge")
        with self.assertRaisesRegex(ValueError, "게임 용어"):
            validate_identities(broken)

    def test_renown_cost_requires_agent_decision(self):
        broken = copy.deepcopy(self.units)
        broken["rows"][0]["renownCost"].pop("decidedBy")
        with self.assertRaisesRegex(ValueError, "decidedBy"):
            validate_units(broken)

    def test_missing_preset_is_rejected(self):
        broken = copy.deepcopy(self.identities)
        broken["rows"].pop()
        with self.assertRaisesRegex(ValueError, "15 specified presets"):
            validate_identities(broken)


if __name__ == "__main__":
    unittest.main()
