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
SPEC = importlib.util.spec_from_file_location("han_route_node_review_fixes", MODULE_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = MODULE
SPEC.loader.exec_module(MODULE)


class HanRouteNodeReviewFixesTest(unittest.TestCase):
    def test_real_active_scenario_catalog_is_exactly_the_product_15(self) -> None:
        inputs = MODULE.default_inputs()
        candidate = json.loads(inputs.candidate.read_text(encoding="utf-8"))
        resources = candidate["scenarioCatalog"]
        expected_codes = {
            "1010", "1020", "1021", "1030", "1031", "1040", "1041", "1050",
            "1060", "1070", "1080", "1090", "1100", "1110", "1120",
        }

        self.assertEqual(expected_codes, {row["code"] for row in resources})
        self.assertEqual(15, len(resources))
        for row in resources:
            path = ROOT / row["resourcePath"]
            self.assertEqual(row["resourceSha256"], MODULE._digest(path), row["code"])

    def test_copy_default_inputs_copies_only_pinned_scenarios(self) -> None:
        with tempfile.TemporaryDirectory() as raw_directory:
            inputs = MODULE.copy_default_inputs(Path(raw_directory))

            self.assertEqual(15, len(list(inputs.scenario_dir.glob("scenario_*.json"))))
            self.assertFalse((inputs.scenario_dir / "scenario_912.json").exists())

            extra = json.loads(next(inputs.scenario_dir.glob("scenario_*.json")).read_text(encoding="utf-8"))
            (inputs.scenario_dir / "scenario_9999.json").write_text(
                MODULE.serialize(extra), encoding="utf-8"
            )
            candidate = json.loads(inputs.candidate.read_text(encoding="utf-8"))
            with self.assertRaisesRegex(MODULE.MaterializationContractError, "scenario resource set drift"):
                MODULE._scenario_resources(candidate, inputs.scenario_dir)

    def test_unknown_same_node_old_city_id_fails_with_contract_error(self) -> None:
        with tempfile.TemporaryDirectory() as raw_directory:
            inputs = MODULE.copy_default_inputs(Path(raw_directory))
            policy = json.loads(inputs.review_policy.read_text(encoding="utf-8"))
            policy["legacyAttributionCorrections"][0]["oldCityId"] = 999
            inputs.review_policy.write_text(MODULE.serialize(policy), encoding="utf-8")

            with self.assertRaisesRegex(MODULE.MaterializationContractError, "binding correction"):
                MODULE.materialize(inputs)

    def test_unknown_location_old_city_id_fails_with_contract_error(self) -> None:
        with tempfile.TemporaryDirectory() as raw_directory:
            inputs = MODULE.copy_default_inputs(Path(raw_directory))
            policy = json.loads(inputs.review_policy.read_text(encoding="utf-8"))
            policy["legacyLocationCorrections"][0]["oldCityId"] = 999
            inputs.review_policy.write_text(MODULE.serialize(policy), encoding="utf-8")

            with self.assertRaisesRegex(MODULE.MaterializationContractError, "location correction"):
                MODULE.materialize(inputs)

    def test_cli_check_reports_drift_with_exit_one_and_stderr(self) -> None:
        with tempfile.TemporaryDirectory() as raw_directory:
            selection = Path(raw_directory) / "selection.json"
            migration = Path(raw_directory) / "migration.json"
            write = subprocess.run(
                [sys.executable, str(MODULE_PATH), "--selection-output", str(selection),
                 "--migration-output", str(migration)],
                cwd=ROOT, capture_output=True, text=True, check=False,
            )
            self.assertEqual(0, write.returncode, write.stderr)
            selection.write_text(selection.read_text(encoding="utf-8") + "\n", encoding="utf-8")

            check = subprocess.run(
                [sys.executable, str(MODULE_PATH), "--selection-output", str(selection),
                 "--migration-output", str(migration), "--check"],
                cwd=ROOT, capture_output=True, text=True, check=False,
            )

        self.assertEqual(1, check.returncode)
        self.assertIn("drift", check.stderr)

    def test_committed_selection_and_migration_are_materializer_output(self) -> None:
        check = subprocess.run(
            [sys.executable, str(MODULE_PATH), "--check"],
            cwd=ROOT,
            capture_output=True,
            text=True,
            check=False,
        )

        self.assertEqual(0, check.returncode, check.stderr)
        self.assertIn("selection and migration: no drift", check.stdout)


if __name__ == "__main__":
    unittest.main()
