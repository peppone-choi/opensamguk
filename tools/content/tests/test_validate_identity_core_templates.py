import copy
import json
import unittest

from tools.content.validate_identity_core_templates import SOURCES, TEMPLATES, validate


class IdentityCoreTemplateTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.templates = json.loads(TEMPLATES.read_text(encoding="utf-8"))
        cls.sources = json.loads(SOURCES.read_text(encoding="utf-8"))

    def test_three_source_linked_templates(self):
        self.assertEqual(3, validate(self.templates, self.sources))

    def test_first_stages_match_the_identity_design(self):
        stages = {row["id"]: row["initialStage"] for row in self.templates["rows"]}
        self.assertEqual({
            "identity.confucian": "TERRITORIAL_REGIME",
            "identity.taiping": "MOVEMENT",
            "identity.bandit": "CONFEDERATION",
        }, stages)

    def test_missing_first_preset_is_rejected(self):
        broken = copy.deepcopy(self.templates)
        broken["rows"].pop()
        with self.assertRaisesRegex(ValueError, "three first"):
            validate(broken, self.sources)

    def test_unconfirmed_game_decision_is_rejected(self):
        broken = copy.deepcopy(self.templates)
        broken["rows"][0]["designDecision"]["status"] = "PROVISIONAL"
        with self.assertRaisesRegex(ValueError, "CONFIRMED"):
            validate(broken, self.sources)

    def test_duplicate_capability_is_rejected(self):
        broken = copy.deepcopy(self.templates)
        row = broken["rows"][0]
        row["commandCapabilities"].append(row["commandCapabilities"][0])
        with self.assertRaisesRegex(ValueError, "commandCapabilities"):
            validate(broken, self.sources)

    def test_unknown_network_place_kind_is_rejected(self):
        broken = copy.deepcopy(self.templates)
        broken["rows"][2]["startingNetworks"][0]["placeKind"] = "NOWHERE"
        with self.assertRaisesRegex(ValueError, "place binding"):
            validate(broken, self.sources)


if __name__ == "__main__":
    unittest.main()
