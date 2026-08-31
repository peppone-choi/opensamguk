from __future__ import annotations

import json
import unittest
from pathlib import Path

from tools.scenario.migrate_han_ownership_claims import (
    canonical_bytes,
    count_direct_grants,
    count_parent_grants,
    legacy_political_view,
    migrate,
    project_legacy_political_view,
)


ROOT = Path(__file__).resolve().parents[3]
LEGACY = ROOT / "tools/scenario/han_ownership.json"
MAP = ROOT / "data/map/han-tiles.json"


def load_json(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


class MigrateHanOwnershipClaimsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.legacy = load_json(LEGACY)
        cls.map_doc = load_json(MAP)

    def test_projection_preserves_every_legacy_political_decision(self):
        curated = migrate(self.legacy, self.map_doc)

        self.assertEqual(15, len(curated["scenarios"]))
        self.assertEqual(187, sum(len(s["nationRefs"]) for s in curated["scenarios"]))
        self.assertEqual(1091, count_parent_grants(curated))
        self.assertEqual(6, count_direct_grants(curated))
        self.assertEqual(
            legacy_political_view(self.legacy),
            project_legacy_political_view(curated, self.map_doc),
        )

    def test_migration_is_byte_deterministic(self):
        first = migrate(self.legacy, self.map_doc)
        second = migrate(self.legacy, self.map_doc)

        self.assertEqual(canonical_bytes(first), canonical_bytes(second))

    def test_every_scenario_has_one_baseline_and_stable_nation_keys(self):
        curated = migrate(self.legacy, self.map_doc)

        for scenario in curated["scenarios"]:
            baselines = [
                claim for claim in scenario["claims"]
                if claim["claimKind"] == "SCENARIO_BASELINE_UNOWNED"
            ]
            self.assertEqual(1, len(baselines), scenario["scenarioCode"])
            keys = [row["nationKey"] for row in scenario["nationRefs"]]
            self.assertEqual(len(keys), len(set(keys)), scenario["scenarioCode"])
            self.assertTrue(all(key.startswith(f"S{scenario['scenarioCode']}-N") for key in keys))

    def test_seven_reviewed_unplaced_forces_have_no_positive_claim(self):
        curated = migrate(self.legacy, self.map_doc)
        unplaced = []
        for scenario in curated["scenarios"]:
            claimed = {
                claim["ownerNationKey"] for claim in scenario["claims"]
                if claim["ownerNationKey"] is not None
            }
            unplaced.extend(
                (scenario["scenarioCode"], nation["scenarioNationName"])
                for nation in scenario["nationRefs"]
                if nation["nationKey"] not in claimed
            )

        self.assertEqual(7, len(unplaced))
        self.assertIn((1050, "유비"), unplaced)
        self.assertIn((1080, "마초"), unplaced)

    def test_direct_city_grants_resolve_to_exact_stable_province_ids(self):
        curated = migrate(self.legacy, self.map_doc)
        direct_targets = {
            province_id
            for scenario in curated["scenarios"]
            for claim in scenario["claims"]
            if claim["claimKind"] in {"PROVINCE_DIRECT", "IF_SCENARIO"}
            for province_id in claim["target"].get("provinceIds", [])
        }

        self.assertTrue({"82749", "45934", "45921", "44356"}.issubset(direct_targets))


if __name__ == "__main__":
    unittest.main()
