import copy
import unittest

from tools.help.validate_topic_registry import ROOT, load, validate


class HelpTopicRegistryValidationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.topics = load(ROOT / "data/help/topics.json")
        cls.registry = load(ROOT / "data/help/topic-registry.json")
        cls.catalog = load(ROOT / "data/commands/input-catalog.json")

    def test_current_catalog_and_topics_are_exact(self) -> None:
        self.assertEqual((74, 0), validate(self.topics, self.registry, self.catalog))

    def test_unregistered_concept_fails_red(self) -> None:
        topics = copy.deepcopy(self.topics)
        row = copy.deepcopy(topics["topics"][0])
        row["id"] = "concepts.createGeneral"
        topics["topics"].append(row)
        with self.assertRaisesRegex(ValueError, "orphan"):
            validate(topics, self.registry, self.catalog)

    def test_registered_concept_and_tutorial_pass(self) -> None:
        topics = copy.deepcopy(self.topics)
        registry = copy.deepcopy(self.registry)
        for topic_id, group in (("concepts.createGeneral", "CONCEPT"), ("tutorial.createGeneral", "TUTORIAL")):
            row = copy.deepcopy(topics["topics"][0])
            row["id"] = topic_id
            topics["topics"].append(row)
            registry["topics"].append({"id": topic_id, "group": group})
        self.assertEqual((74, 2), validate(topics, registry, self.catalog))

    def test_wrong_type_and_unmatched_registration_fail_red(self) -> None:
        registry = {"schemaVersion": 1, "topics": [{"id": "concepts.createGeneral", "group": "TUTORIAL"}]}
        with self.assertRaisesRegex(ValueError, "group does not match"):
            validate(self.topics, registry, self.catalog)
        registry["topics"][0]["group"] = "CONCEPT"
        with self.assertRaisesRegex(ValueError, "missing"):
            validate(self.topics, registry, self.catalog)


if __name__ == "__main__":
    unittest.main()
