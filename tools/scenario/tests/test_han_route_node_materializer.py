from __future__ import annotations

import importlib.util
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
MODULE_PATH = ROOT / "tools/scenario/materialize_han_route_node_selection.py"
sys.path.insert(0, str(MODULE_PATH.parent))
SPEC = importlib.util.spec_from_file_location("han_route_node_materializer", MODULE_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = MODULE
SPEC.loader.exec_module(MODULE)


class HanRouteNodeMaterializerTest(unittest.TestCase):
    def test_real_approved_ledgers_materialize_exact_contract(self) -> None:
        result = MODULE.materialize(MODULE.default_inputs())

        self.assertEqual(780, len(result.selection["routeNodes"]))
        self.assertEqual(780, len(result.migration["rows"]))
        self.assertEqual(31, result.selection["scenarioCatalog"]["resourceCount"])
        self.assertEqual(101, result.migration["summary"]["routeNodeReplacementCount"])
        self.assertEqual(25, result.migration["summary"]["historicalBindingCorrectionCount"])
        self.assertEqual(1, result.migration["summary"]["physicalPlaceCorrectionCount"])
        self.assertEqual(0, result.migration["summary"]["numericCityIdChangeCount"])

    def test_route_keys_are_copied_verbatim_from_registry(self) -> None:
        inputs = MODULE.default_inputs()
        result = MODULE.materialize(inputs)
        registry = json.loads(inputs.key_registry.read_text(encoding="utf-8"))

        expected = {row["routeNodeKey"] for row in registry["keys"]}
        actual = {row["routeNodeKey"] for row in result.selection["routeNodes"]}
        self.assertEqual(expected, actual)

    def test_outputs_omit_external_coordinate_payload_fields(self) -> None:
        result = MODULE.materialize(MODULE.default_inputs())
        serialized = MODULE.serialize(result.selection) + MODULE.serialize(result.migration)

        self.assertNotIn('"coordinate"', serialized)
        self.assertNotIn('"presentLocation"', serialized)
        self.assertNotIn('"recordIndex"', serialized)

    def test_wrong_review_hash_fails_closed(self) -> None:
        with tempfile.TemporaryDirectory() as raw_directory:
            inputs = MODULE.copy_default_inputs(Path(raw_directory))
            policy = json.loads(inputs.review_policy.read_text(encoding="utf-8"))
            policy["inputs"]["locationAdjudications"]["sha256"] = "0" * 64
            inputs.review_policy.write_text(json.dumps(policy, ensure_ascii=False), encoding="utf-8")

            with self.assertRaisesRegex(MODULE.MaterializationContractError, "adjudication hash"):
                MODULE.materialize(inputs)

    def test_missing_and_extra_registry_keys_fail_closed(self) -> None:
        for mutation in ("missing", "extra"):
            with self.subTest(mutation=mutation), tempfile.TemporaryDirectory() as raw_directory:
                inputs = MODULE.copy_default_inputs(Path(raw_directory))
                registry = json.loads(inputs.key_registry.read_text(encoding="utf-8"))
                if mutation == "missing":
                    registry["keys"].pop()
                else:
                    registry["keys"].append(dict(registry["keys"][0]))
                inputs.key_registry.write_text(json.dumps(registry, ensure_ascii=False), encoding="utf-8")
                MODULE.repin_policy_input(inputs, "routeNodeKeyRegistry", inputs.key_registry)

                with self.assertRaisesRegex(MODULE.MaterializationContractError, "registry"):
                    MODULE.materialize(inputs)

    def test_policy_count_drift_fails_closed(self) -> None:
        with tempfile.TemporaryDirectory() as raw_directory:
            inputs = MODULE.copy_default_inputs(Path(raw_directory))
            policy = json.loads(inputs.review_policy.read_text(encoding="utf-8"))
            policy["expectedSelection"]["routeNodeCount"] = 779
            inputs.review_policy.write_text(json.dumps(policy, ensure_ascii=False), encoding="utf-8")

            with self.assertRaisesRegex(MODULE.MaterializationContractError, "policy count"):
                MODULE.materialize(inputs)

    def test_policy_forbidden_name_is_enforced_during_materialization(self) -> None:
        with tempfile.TemporaryDirectory() as raw_directory:
            inputs = MODULE.copy_default_inputs(Path(raw_directory))
            policy = json.loads(inputs.review_policy.read_text(encoding="utf-8"))
            policy["forbiddenSelections"]["canonicalNames"].append("雒陽")
            inputs.review_policy.write_text(json.dumps(policy, ensure_ascii=False), encoding="utf-8")

            with self.assertRaisesRegex(MODULE.MaterializationContractError, "forbidden"):
                MODULE.materialize(inputs)

    def test_policy_forbidden_physical_place_is_enforced_during_materialization(self) -> None:
        with tempfile.TemporaryDirectory() as raw_directory:
            inputs = MODULE.copy_default_inputs(Path(raw_directory))
            policy = json.loads(inputs.review_policy.read_text(encoding="utf-8"))
            selection = MODULE.materialize(inputs).selection
            policy["forbiddenSelections"]["physicalPlaceIds"].append(
                selection["routeNodes"][0]["physicalPlaceRef"]
            )
            inputs.review_policy.write_text(json.dumps(policy, ensure_ascii=False), encoding="utf-8")

            with self.assertRaisesRegex(MODULE.MaterializationContractError, "forbidden"):
                MODULE.materialize(inputs)

    def test_policy_forbidden_node_class_is_enforced_during_materialization(self) -> None:
        with tempfile.TemporaryDirectory() as raw_directory:
            inputs = MODULE.copy_default_inputs(Path(raw_directory))
            policy = json.loads(inputs.review_policy.read_text(encoding="utf-8"))
            policy["forbiddenSelections"]["nodeClasses"].append("COUNTY_NODE")
            inputs.review_policy.write_text(json.dumps(policy, ensure_ascii=False), encoding="utf-8")

            with self.assertRaisesRegex(MODULE.MaterializationContractError, "forbidden"):
                MODULE.materialize(inputs)

    def test_save_inventory_separates_mutable_audit_and_derived_surfaces(self) -> None:
        migration = MODULE.materialize(MODULE.default_inputs()).migration

        self.assertEqual(
            {
                "city.id", "general.city_id", "general.officer_city", "nation.capital_city_id",
                "v2_city_ledger.city_id", "general_turn.arg", "nation_turn.arg",
                "general.last_turn", "general.meta.officer_city", "command_inbox.payload",
            },
            set(migration["referenceInventory"]["mutable"]),
        )
        self.assertEqual(
            {"command_result.result_payload", "command_outbox.payload", "history", "replay"},
            set(migration["referenceInventory"]["immutableAudit"]),
        )
        self.assertEqual(
            {"scenario.nation.city_ids", "scenario.general.city_id", "HanCityConst", "HanGateIndex", "map.connections"},
            set(migration["referenceInventory"]["derivedReseed"]),
        )

    def test_duplicate_selected_physical_place_fails_closed(self) -> None:
        with tempfile.TemporaryDirectory() as raw_directory:
            inputs = MODULE.copy_default_inputs(Path(raw_directory))
            claims = json.loads(inputs.source_claims.read_text(encoding="utf-8"))
            claims["claims"][1]["locationResolution"]["physicalPlaceId"] = (
                claims["claims"][0]["locationResolution"]["physicalPlaceId"]
            )
            inputs.source_claims.write_text(json.dumps(claims, ensure_ascii=False), encoding="utf-8")
            MODULE.repin_policy_input(inputs, "locationClaims", inputs.source_claims)

            with self.assertRaisesRegex(MODULE.MaterializationContractError, "duplicate physicalPlaceRef"):
                MODULE.materialize(inputs)

    def test_forbidden_x060_cannot_be_selected_even_when_inputs_are_rehashed(self) -> None:
        with tempfile.TemporaryDirectory() as raw_directory:
            inputs = MODULE.copy_default_inputs(Path(raw_directory))
            selection = MODULE.materialize(inputs).selection
            target = selection["routeNodes"][0]
            unit_id, legacy_id = target["administrativeUnitId"], target["legacyCityId"]
            overlay = json.loads(inputs.overlay.read_text(encoding="utf-8"))
            overlay_row = next(
                row for row in overlay["administrativeUnits"] if row["administrativeUnitId"] == unit_id
            )
            overlay_row["selectedCandidate"]["physicalPlaceId"] = "external:v1:X060"
            inputs.overlay.write_text(json.dumps(overlay, ensure_ascii=False), encoding="utf-8")
            candidate = json.loads(inputs.candidate.read_text(encoding="utf-8"))
            current = next(
                row for row in candidate["candidates"]
                if row.get("origin") == "CURRENT_780" and row.get("legacyCityId") == legacy_id
            )
            current["physicalPlaceRef"] = "external:v1:X060"
            candidate["provenance"]["inputs"]["administrativePlaceOverlay"]["sha256"] = MODULE._digest(inputs.overlay)
            inputs.candidate.write_text(json.dumps(candidate, ensure_ascii=False), encoding="utf-8")
            policy = json.loads(inputs.review_policy.read_text(encoding="utf-8"))
            policy["inputs"]["coordinateOverlaySha256"] = MODULE._digest(inputs.overlay)
            policy["inputs"]["candidateManifest"]["sha256"] = MODULE._digest(inputs.candidate)
            inputs.review_policy.write_text(json.dumps(policy, ensure_ascii=False), encoding="utf-8")

            with self.assertRaisesRegex(MODULE.MaterializationContractError, "forbidden physicalPlaceRef"):
                MODULE.materialize(inputs)

    def test_location_claim_source_hash_is_verified_during_materialization(self) -> None:
        with tempfile.TemporaryDirectory() as raw_directory:
            inputs = MODULE.copy_default_inputs(Path(raw_directory))
            claims = json.loads(inputs.source_claims.read_text(encoding="utf-8"))
            claims["claims"][0]["sourceRecords"][0]["snapshotSha256"] = "0" * 64
            inputs.source_claims.write_text(json.dumps(claims, ensure_ascii=False), encoding="utf-8")
            MODULE.repin_policy_input(inputs, "locationClaims", inputs.source_claims)

            with self.assertRaisesRegex(MODULE.MaterializationContractError, "source record hash"):
                MODULE.materialize(inputs)

    def test_unreviewed_ambiguous_location_fails_closed(self) -> None:
        with tempfile.TemporaryDirectory() as raw_directory:
            inputs = MODULE.copy_default_inputs(Path(raw_directory))
            decisions = json.loads(inputs.adjudications.read_text(encoding="utf-8"))
            decisions["adjudications"][0]["reviewState"] = "PENDING"
            inputs.adjudications.write_text(json.dumps(decisions, ensure_ascii=False), encoding="utf-8")
            MODULE.repin_policy_input(inputs, "locationAdjudications", inputs.adjudications)

            with self.assertRaisesRegex(MODULE.MaterializationContractError, "unreviewed ambiguity"):
                MODULE.materialize(inputs)

    def test_wrong_x026_correction_fails_closed(self) -> None:
        with tempfile.TemporaryDirectory() as raw_directory:
            inputs = MODULE.copy_default_inputs(Path(raw_directory))
            policy = json.loads(inputs.review_policy.read_text(encoding="utf-8"))
            policy["legacySameNodeCorrections"][0]["administrativeUnitId"] = "hhs:113:上郡:008"
            inputs.review_policy.write_text(json.dumps(policy, ensure_ascii=False), encoding="utf-8")

            with self.assertRaisesRegex(MODULE.MaterializationContractError, "X026"):
                MODULE.materialize(inputs)

    def test_scenario_resource_hash_drift_fails_closed(self) -> None:
        with tempfile.TemporaryDirectory() as raw_directory:
            inputs = MODULE.copy_default_inputs(Path(raw_directory))
            scenario = next(inputs.scenario_dir.glob("scenario_*.json"))
            scenario.write_text(scenario.read_text(encoding="utf-8") + "\n", encoding="utf-8")

            with self.assertRaisesRegex(MODULE.MaterializationContractError, "scenario hash"):
                MODULE.materialize(inputs)

    def test_cli_check_and_repeated_materialization_are_byte_identical(self) -> None:
        first = MODULE.materialize(MODULE.default_inputs())
        second = MODULE.materialize(MODULE.default_inputs())
        self.assertEqual(MODULE.serialize(first.selection), MODULE.serialize(second.selection))
        self.assertEqual(MODULE.serialize(first.migration), MODULE.serialize(second.migration))

        with tempfile.TemporaryDirectory() as raw_directory:
            selection = Path(raw_directory) / "selection.json"
            migration = Path(raw_directory) / "migration.json"
            write = subprocess.run(
                [sys.executable, str(MODULE_PATH), "--selection-output", str(selection),
                 "--migration-output", str(migration)],
                cwd=ROOT, capture_output=True, text=True, check=False,
            )
            check = subprocess.run(
                [sys.executable, str(MODULE_PATH), "--selection-output", str(selection),
                 "--migration-output", str(migration), "--check"],
                cwd=ROOT, capture_output=True, text=True, check=False,
            )

        self.assertEqual(0, write.returncode, write.stderr)
        self.assertEqual(0, check.returncode, check.stderr)
        self.assertIn("no drift", check.stdout)


if __name__ == "__main__":
    unittest.main()
