from __future__ import annotations

import json
from copy import deepcopy
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
        self.assertEqual(1307, count_parent_grants(curated))
        self.assertEqual(6, count_direct_grants(curated))
        self.assertEqual(
            legacy_political_view(self.legacy, self.map_doc),
            project_legacy_political_view(curated, self.map_doc),
        )

    def test_migration_is_byte_deterministic(self):
        first = migrate(self.legacy, self.map_doc)
        second = migrate(self.legacy, self.map_doc)

        self.assertEqual(canonical_bytes(first), canonical_bytes(second))

    def test_source_url_is_preserved_on_migrated_evidence(self):
        legacy = deepcopy(self.legacy)
        first_nation = next(iter(legacy["scenario_1010"]["nations"].values()))
        first_nation["sourceUrl"] = "https://zh.wikisource.org/wiki/example"

        curated = migrate(legacy, self.map_doc)
        evidence = next(
            row for row in curated["evidence"]
            if row["evidenceId"] == "S1010-N001-PLACEMENT-EVIDENCE"
        )

        self.assertEqual("https://zh.wikisource.org/wiki/example", evidence["url"])

    def test_interior_continuity_assignment_becomes_evidenced_admin_claim(self):
        legacy = deepcopy(self.legacy)
        legacy["_interiorContinuity"] = {
            "assignments": [{
                "scenarioCode": 1010,
                "nation": "후한",
                "juns": ["제북국"],
                "basis": "Reviewed interior continuity.",
                "sourceUrl": "https://zh.wikisource.org/wiki/example",
            }],
            "allowlists": [],
        }

        curated = migrate(legacy, self.map_doc)
        scenario = next(row for row in curated["scenarios"] if row["scenarioCode"] == 1010)
        claim = next(row for row in scenario["claims"] if row["claimId"] == "S1010-CONTINUITY-001")
        evidence = next(
            row for row in curated["evidence"]
            if row["evidenceId"] == "S1010-CONTINUITY-001-EVIDENCE"
        )

        self.assertEqual("S1010-N001", claim["ownerNationKey"])
        self.assertEqual(["PARENT-0027"], claim["target"]["parentRegionIds"])
        self.assertEqual("https://zh.wikisource.org/wiki/example", evidence["url"])

    def test_interior_continuity_can_target_exact_province_ids(self):
        legacy = deepcopy(self.legacy)
        legacy["_interiorContinuity"] = {
            "assignments": [{
                "scenarioCode": 1010,
                "nation": "후한",
                "provinceIds": ["70523"],
                "basis": "Reviewed county-level continuity.",
            }],
            "allowlists": [],
        }

        curated = migrate(legacy, self.map_doc)
        scenario = next(row for row in curated["scenarios"] if row["scenarioCode"] == 1010)
        claim = next(row for row in scenario["claims"] if row["claimId"] == "S1010-CONTINUITY-001")

        self.assertEqual("PROVINCE_DIRECT", claim["claimKind"])
        self.assertEqual({"provinceIds": ["70523"]}, claim["target"])

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

    def test_nation_rename_exposes_runtime_and_reviewed_display_name(self):
        curated = migrate(self.legacy, self.map_doc)
        scenario = next(row for row in curated["scenarios"] if row["scenarioCode"] == 1100)
        refs = {row["displayNationName"]: row for row in scenario["nationRefs"]}

        self.assertEqual("유선", refs["유선"]["scenarioNationName"])
        self.assertEqual("남중 반란군", refs["남중 반란군"]["scenarioNationName"])

    def test_reviewed_holes_migrate_as_exact_evidenced_allowlist_entries(self):
        curated = migrate(self.legacy, self.map_doc)
        scenario = next(row for row in curated["scenarios"] if row["scenarioCode"] == 1100)

        self.assertEqual(3, len(scenario["auditAllowlist"]))
        self.assertEqual(
            ["95125", "95676", "95698", "DIRECT-PARENT-0138-877c5fc0e884"],
            scenario["auditAllowlist"][0]["provinceIds"],
        )
        self.assertTrue(all(row["reviewState"] == "APPROVED" for row in scenario["auditAllowlist"]))
        self.assertTrue(all(row["evidenceIds"] for row in scenario["auditAllowlist"]))


if __name__ == "__main__":
    unittest.main()
