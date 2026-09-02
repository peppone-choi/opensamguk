from __future__ import annotations

import importlib.util
import json
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

ROOT = Path(__file__).resolve().parents[3]
MODULE_PATH = ROOT / "tools/scenario/materialize_han_route_node_selection.py"
sys.path.insert(0, str(MODULE_PATH.parent))
SPEC = importlib.util.spec_from_file_location("han_route_node_materializer", MODULE_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = MODULE
SPEC.loader.exec_module(MODULE)
from tools.scenario import han_route_node_selection as SELECTION


def repin_policy_input(inputs: MODULE.MaterializerInputs, key: str, path: Path) -> None:
    if inputs.review_policy.resolve() == MODULE.default_inputs().review_policy.resolve():
        raise AssertionError("tests must never repin the curated production policy")
    policy = json.loads(inputs.review_policy.read_text(encoding="utf-8"))
    policy["inputs"][key]["sha256"] = MODULE._digest(path)
    inputs.review_policy.write_text(MODULE.serialize(policy), encoding="utf-8")


class HanRouteNodeMaterializerTest(unittest.TestCase):
    def test_approved_780_candidates_use_reproducible_frozen_inputs(self) -> None:
        inputs = MODULE.default_inputs()

        self.assertEqual("han-780-v1.json", inputs.han.name)
        self.assertEqual("han-780-v1-tiles.json", inputs.tiles.name)
        result = subprocess.run(
            [
                sys.executable,
                str(ROOT / "tools/scenario/build_han_route_node_candidates.py"),
                "--output",
                str(inputs.candidate),
                "--check",
            ],
            cwd=ROOT,
            capture_output=True,
            text=True,
            check=False,
        )
        self.assertEqual(0, result.returncode, result.stderr)

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
                repin_policy_input(inputs, "routeNodeKeyRegistry", inputs.key_registry)

                with self.assertRaisesRegex(MODULE.MaterializationContractError, "registry"):
                    MODULE.materialize(inputs)

    def test_registry_contract_flags_fail_closed(self) -> None:
        registry = json.loads(MODULE.default_inputs().key_registry.read_text(encoding="utf-8"))
        for field in (
            "derivedFromNumericCityId",
            "derivedFromAdministrativeIdentity",
            "derivedFromPhysicalPlace",
            "derivedFromSourceClaim",
            "rebindingChangesKey",
        ):
            with self.subTest(field=field):
                mutated = json.loads(json.dumps(registry))
                mutated["keyPolicy"][field] = True
                with self.assertRaisesRegex(
                    MODULE.MaterializationContractError, "opaque non-derived keys"
                ):
                    SELECTION._uuid_keys(mutated, {
                        row["initialAdministrativeUnitId"] for row in mutated["keys"]
                    })

    def test_policy_count_drift_fails_closed(self) -> None:
        with tempfile.TemporaryDirectory() as raw_directory:
            inputs = MODULE.copy_default_inputs(Path(raw_directory))
            policy = json.loads(inputs.review_policy.read_text(encoding="utf-8"))
            policy["expectedSelection"]["routeNodeCount"] = 779
            inputs.review_policy.write_text(json.dumps(policy, ensure_ascii=False), encoding="utf-8")

            with self.assertRaisesRegex(MODULE.MaterializationContractError, "policy count"):
                MODULE.materialize(inputs)

    def test_policy_forbidden_name_drift_fails_closed(self) -> None:
        with tempfile.TemporaryDirectory() as raw_directory:
            inputs = MODULE.copy_default_inputs(Path(raw_directory))
            policy = json.loads(inputs.review_policy.read_text(encoding="utf-8"))
            policy["forbiddenSelections"]["canonicalNames"].append("雒陽")
            inputs.review_policy.write_text(json.dumps(policy, ensure_ascii=False), encoding="utf-8")

            with self.assertRaisesRegex(
                MODULE.MaterializationContractError, "forbidden selection policy drift"
            ):
                MODULE.materialize(inputs)

    def test_policy_forbidden_physical_place_drift_fails_closed(self) -> None:
        with tempfile.TemporaryDirectory() as raw_directory:
            inputs = MODULE.copy_default_inputs(Path(raw_directory))
            policy = json.loads(inputs.review_policy.read_text(encoding="utf-8"))
            selection = MODULE.materialize(inputs).selection
            policy["forbiddenSelections"]["physicalPlaceIds"].append(
                selection["routeNodes"][0]["physicalPlaceRef"]
            )
            inputs.review_policy.write_text(json.dumps(policy, ensure_ascii=False), encoding="utf-8")

            with self.assertRaisesRegex(
                MODULE.MaterializationContractError, "forbidden selection policy drift"
            ):
                MODULE.materialize(inputs)

    def test_policy_forbidden_node_class_drift_fails_closed(self) -> None:
        with tempfile.TemporaryDirectory() as raw_directory:
            inputs = MODULE.copy_default_inputs(Path(raw_directory))
            policy = json.loads(inputs.review_policy.read_text(encoding="utf-8"))
            policy["forbiddenSelections"]["nodeClasses"].append("COUNTY_NODE")
            inputs.review_policy.write_text(json.dumps(policy, ensure_ascii=False), encoding="utf-8")

            with self.assertRaisesRegex(
                MODULE.MaterializationContractError, "forbidden selection policy drift"
            ):
                MODULE.materialize(inputs)

    def test_selected_nodes_reach_all_forbidden_enforcement_branches(self) -> None:
        cases = (
            ("physicalPlaceIds", "physicalPlaceRef", "chgis:v6:cnty:forbidden", "physicalPlaceRef"),
            ("canonicalNames", "canonicalName", "FORBIDDEN_NAME", "canonical name"),
            ("nodeClasses", "nodeClass", "FORBIDDEN_NODE", "nodeClass"),
        )
        for policy_field, node_field, value, message in cases:
            with self.subTest(policy_field=policy_field):
                forbidden = json.loads(json.dumps(SELECTION.EXPECTED_FORBIDDEN_SELECTIONS))
                forbidden[policy_field].append(value)
                with self.assertRaisesRegex(MODULE.MaterializationContractError, message):
                    SELECTION._enforce_forbidden_selection([{node_field: value}], forbidden)

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

    def test_cross_bound_external_authority_row_fails_closed(self) -> None:
        with tempfile.TemporaryDirectory() as raw_directory:
            inputs = MODULE.copy_default_inputs(Path(raw_directory))
            claims = json.loads(inputs.source_claims.read_text(encoding="utf-8"))
            claims["claims"][1]["locationResolution"]["physicalPlaceId"] = (
                claims["claims"][0]["locationResolution"]["physicalPlaceId"]
            )
            claims["claims"][1]["locationResolution"]["coordinateDatasetRef"] = (
                claims["claims"][0]["locationResolution"]["coordinateDatasetRef"]
            )
            inputs.source_claims.write_text(json.dumps(claims, ensure_ascii=False), encoding="utf-8")
            repin_policy_input(inputs, "locationClaims", inputs.source_claims)

            with self.assertRaisesRegex(MODULE.MaterializationContractError, "authority subjectKey does not match"):
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

    def test_location_claim_source_integrity_is_verified_during_materialization(self) -> None:
        with tempfile.TemporaryDirectory() as raw_directory:
            inputs = MODULE.copy_default_inputs(Path(raw_directory))
            claims = json.loads(inputs.source_claims.read_text(encoding="utf-8"))
            claims["claims"][0]["sourceRecords"][0]["snapshotSha256"] = "0" * 64
            inputs.source_claims.write_text(json.dumps(claims, ensure_ascii=False), encoding="utf-8")
            repin_policy_input(inputs, "locationClaims", inputs.source_claims)

            with self.assertRaisesRegex(MODULE.MaterializationContractError, "source record"):
                MODULE.materialize(inputs)

        claims = json.loads(MODULE.default_inputs().source_claims.read_text(encoding="utf-8"))
        with tempfile.TemporaryDirectory() as raw_directory:
            fixture_root = Path(raw_directory).resolve()
            for claim in claims["claims"]:
                dataset_path = Path(claim["locationResolution"]["coordinateDatasetRef"]["datasetPath"])
                target = fixture_root / dataset_path
                target.parent.mkdir(parents=True, exist_ok=True)
                if not target.exists():
                    shutil.copy2(ROOT / dataset_path, target)

            records_by_path: dict[str, list[dict[str, object]]] = {}
            for claim in claims["claims"]:
                for record in claim["sourceRecords"]:
                    records_by_path.setdefault(record["corpusPath"], []).append(record)
            for corpus_path, records in records_by_path.items():
                line_count = max(int(record["lineEnd"]) for record in records)
                lines = ["fixture source line"] * line_count
                for record in records:
                    lines[int(record["lineStart"]) - 1] = str(record["verbatim"])
                target = fixture_root / corpus_path
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_text("\n".join(lines) + "\n", encoding="utf-8")
                snapshot = MODULE._digest(target)
                for record in records:
                    record["snapshotSha256"] = snapshot

            with mock.patch.object(MODULE, "ROOT", fixture_root):
                MODULE._verify_location_claim_sources(claims, fixture_root / "unused-witness.json")
                first = claims["claims"][0]["sourceRecords"][0]
                for field, value, error in (
                    ("snapshotSha256", "0" * 64, "hash does not match"),
                    ("lineEnd", 9999, "line range is outside"),
                    ("verbatim", "not in fixture", "verbatim does not match"),
                ):
                    with self.subTest(field=field):
                        original = first[field]
                        first[field] = value
                        with self.assertRaisesRegex(MODULE.MaterializationContractError, error):
                            MODULE._verify_location_claim_sources(
                                claims, fixture_root / "unused-witness.json"
                            )
                        first[field] = original

    def test_unreviewed_or_malformed_materialization_inputs_fail_closed(self) -> None:
        with tempfile.TemporaryDirectory() as raw_directory:
            inputs = MODULE.copy_default_inputs(Path(raw_directory))
            decisions = json.loads(inputs.adjudications.read_text(encoding="utf-8"))
            decisions["adjudications"][0]["reviewState"] = "PENDING"
            inputs.adjudications.write_text(json.dumps(decisions, ensure_ascii=False), encoding="utf-8")
            repin_policy_input(inputs, "locationAdjudications", inputs.adjudications)

            with self.assertRaisesRegex(MODULE.MaterializationContractError, "unreviewed ambiguity"):
                MODULE.materialize(inputs)

        for field in ("rationaleCode", "rationale", "evidenceRefs"):
            with self.subTest(field=field), tempfile.TemporaryDirectory() as raw_directory:
                inputs = MODULE.copy_default_inputs(Path(raw_directory))
                decisions = json.loads(inputs.adjudications.read_text(encoding="utf-8"))
                decisions["adjudications"][0].pop(field)
                inputs.adjudications.write_text(
                    json.dumps(decisions, ensure_ascii=False), encoding="utf-8"
                )
                repin_policy_input(inputs, "locationAdjudications", inputs.adjudications)

                with self.assertRaisesRegex(MODULE.MaterializationContractError, field):
                    MODULE.materialize(inputs)

        for field in ("rationaleCode", "rationale", "evidenceRefs"):
            with self.subTest(rejected_field=field), tempfile.TemporaryDirectory() as raw_directory:
                inputs = MODULE.copy_default_inputs(Path(raw_directory))
                decisions = json.loads(inputs.adjudications.read_text(encoding="utf-8"))
                rejected = next(
                    row for row in decisions["adjudications"]
                    if row["reviewState"] == "REJECTED_FALSE_HOMONYM"
                )
                if field == "evidenceRefs":
                    rejected[field] = []
                else:
                    rejected.pop(field)
                inputs.adjudications.write_text(
                    json.dumps(decisions, ensure_ascii=False), encoding="utf-8"
                )
                repin_policy_input(inputs, "locationAdjudications", inputs.adjudications)

                with self.assertRaisesRegex(MODULE.MaterializationContractError, field):
                    MODULE.materialize(inputs)

        catalog = json.loads(MODULE.default_inputs().catalog.read_text(encoding="utf-8"))
        unit = catalog["groups"][0]["units"][0]
        unit["unitType"] = "CITY"
        unit_id = f"hhs:{unit['sourceVolume']}:{unit['canonicalGroup']}:{unit['ordinal']:03d}"
        with self.assertRaisesRegex(MODULE.MaterializationContractError, f"unitType.*{unit_id}"):
            SELECTION._catalog(catalog)

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

    def test_policy_scenario_activation_fields_are_enforced(self) -> None:
        for field, value in (
            ("expectedScenarioCount", 14),
            ("runtimeEnforcement", "APPROVED"),
        ):
            with self.subTest(field=field), tempfile.TemporaryDirectory() as raw_directory:
                inputs = MODULE.copy_default_inputs(Path(raw_directory))
                policy = json.loads(inputs.review_policy.read_text(encoding="utf-8"))
                policy["scenarioActivationPolicy"][field] = value
                inputs.review_policy.write_text(MODULE.serialize(policy), encoding="utf-8")

                with self.assertRaisesRegex(
                    MODULE.MaterializationContractError, "scenario activation policy drift"
                ):
                    MODULE.materialize(inputs)

    def test_custom_inputs_require_their_own_source_witness(self) -> None:
        with tempfile.TemporaryDirectory() as raw_directory:
            inputs = MODULE.copy_default_inputs(Path(raw_directory))
            mismatched = MODULE.MaterializerInputs(
                **{
                    field: (
                        MODULE.default_inputs().source_witness
                        if field == "source_witness"
                        else getattr(inputs, field)
                    )
                    for field in MODULE.MaterializerInputs.__dataclass_fields__
                }
            )
            with self.assertRaisesRegex(
                MODULE.MaterializationContractError, "beside the selected source claims"
            ):
                MODULE.materialize(mismatched)

            inputs.source_witness.unlink()
            isolated_root = Path(raw_directory) / "isolated-repository"
            claims = json.loads(inputs.source_claims.read_text(encoding="utf-8"))
            for claim in claims["claims"]:
                dataset_path = Path(
                    claim["locationResolution"]["coordinateDatasetRef"]["datasetPath"]
                )
                target = isolated_root / dataset_path
                target.parent.mkdir(parents=True, exist_ok=True)
                if not target.exists():
                    shutil.copy2(ROOT / dataset_path, target)

            with mock.patch.object(MODULE, "ROOT", isolated_root):
                with self.assertRaisesRegex(
                    MODULE.MaterializationContractError, "source witness is missing"
                ):
                    MODULE.materialize(inputs)

    def test_location_claim_requires_no_coordinate_overlay_status(self) -> None:
        with tempfile.TemporaryDirectory() as raw_directory:
            inputs = MODULE.copy_default_inputs(Path(raw_directory))
            claims = json.loads(inputs.source_claims.read_text(encoding="utf-8"))
            unit_id = claims["claims"][0]["subjectKey"]
            overlay = json.loads(inputs.overlay.read_text(encoding="utf-8"))
            overlay_row = next(
                row for row in overlay["administrativeUnits"]
                if row["administrativeUnitId"] == unit_id
            )
            overlay_row["joinStatus"] = "SOURCE_PLACEHOLDER"
            inputs.overlay.write_text(MODULE.serialize(overlay), encoding="utf-8")
            candidate = json.loads(inputs.candidate.read_text(encoding="utf-8"))
            candidate["provenance"]["inputs"]["administrativePlaceOverlay"]["sha256"] = (
                MODULE._digest(inputs.overlay)
            )
            inputs.candidate.write_text(MODULE.serialize(candidate), encoding="utf-8")
            policy = json.loads(inputs.review_policy.read_text(encoding="utf-8"))
            policy["inputs"]["coordinateOverlaySha256"] = MODULE._digest(inputs.overlay)
            policy["inputs"]["candidateManifest"]["sha256"] = MODULE._digest(inputs.candidate)
            inputs.review_policy.write_text(MODULE.serialize(policy), encoding="utf-8")

            with self.assertRaisesRegex(
                MODULE.MaterializationContractError, "NO_COORDINATE_CANDIDATE overlay"
            ):
                MODULE.materialize(inputs)

    def test_missing_scenario_start_year_fails_with_contract_error(self) -> None:
        with tempfile.TemporaryDirectory() as raw_directory:
            inputs = MODULE.copy_default_inputs(Path(raw_directory))
            candidate = json.loads(inputs.candidate.read_text(encoding="utf-8"))
            candidate["scenarioCatalog"][0].pop("startYear")

            with self.assertRaisesRegex(MODULE.MaterializationContractError, "startYear"):
                MODULE._scenario_resources(candidate, inputs.scenario_dir)

    def test_nonnumeric_scenario_code_fails_with_contract_error(self) -> None:
        with tempfile.TemporaryDirectory() as raw_directory:
            inputs = MODULE.copy_default_inputs(Path(raw_directory))
            candidate = json.loads(inputs.candidate.read_text(encoding="utf-8"))
            candidate["scenarioCatalog"][0]["code"] = "not-a-number"

            with self.assertRaisesRegex(MODULE.MaterializationContractError, "must be numeric"):
                MODULE._scenario_resources(candidate, inputs.scenario_dir)

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
