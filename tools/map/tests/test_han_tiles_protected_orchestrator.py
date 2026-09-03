"""Protected, fail-closed execution of an already-approved Han tile contract."""

from __future__ import annotations

import copy
import hashlib
import json
import tempfile
import unittest
import os
import subprocess
import sys
from pathlib import Path
from unittest import mock

from tools.map import han_tiles_contract
from tools.map.tests.test_han_tiles_contract import valid_contract

try:
    from tools.map import han_tiles_protected_orchestrator as orchestrator
except ImportError:  # RED: protected execution module does not exist yet.
    orchestrator = None


def _sha(blob: bytes) -> str:
    return hashlib.sha256(blob).hexdigest()


ROOT = Path(__file__).resolve().parents[3]


@unittest.skipUnless(orchestrator is not None, "protected orchestrator absent during RED")
class ProtectedOrchestratorTest(unittest.TestCase):
    def _lock(self, contract):
        runtime = contract["recipe"]["runtime"]
        versions = {"NUMPY": "2.4.6", "PILLOW": "12.2.0", "HANJA": "0.15.1", "PYYAML": "6.0.3"}
        artifacts = {
            "NUMPY": "numpy-2.4.6-cp314-cp314-macosx_14_0_x86_64.whl",
            "PILLOW": "pillow-12.2.0-cp314-cp314-macosx_10_15_x86_64.whl",
            "HANJA": "hanja-0.15.1-py3-none-any.whl",
            "PYYAML": "pyyaml-6.0.3-cp314-cp314-macosx_10_13_x86_64.whl",
        }
        artifact_hashes = {
            "NUMPY": "0a041d3d761dc3c35cc56ce0351506a02bcbc25f7b169f652435141a17db9096",
            "PILLOW": "2bb4a8d594eacdfc59d9e5ad972aa8afdd48d584ffd5f13a937a664c3e7db0ed",
            "HANJA": "66981f7dedf7ad298661d7d58f2702ea84304617fbe52478a42833f9819d75be",
            "PYYAML": "8d1fab6bb153a416f9aeb4b8763bc0f22a5586065f86f7664fc23339fc1c1fac",
        }
        artifact_bytes = {
            "NUMPY": 6543947, "PILLOW": 5312185, "HANJA": 124262, "PYYAML": 181814,
        }
        return {
            "schemaVersion": 1,
            "lockId": "han-tiles-python-lock-v1",
            "lockType": "REVIEWED_PLATFORM_WHEELHOUSE",
            "installPolicy": "NO_INDEX_ONLY_BINARY_NO_DEPS",
            "pythonImplementation": runtime["pythonImplementation"],
            "pythonVersion": runtime["pythonVersion"],
            "platform": {
                "system": "Darwin", "machine": "x86_64", "pythonTag": "cp314",
            },
            "reviewedException": {
                "dependencyRole": "PYYAML", "requiredByRole": "HANJA",
                "declaredVersion": "6.0.1", "selectedVersion": "6.0.3",
                "reason": "hanja METADATA pins PyYAML==6.0.1 + unmarked test deps; 6.0.1 has no cp314 wheel; reviewed no-deps runtime closure is exactly 4",
            },
            "distributions": {
                role: {
                    "distributionName": row["distributionName"],
                    "version": versions[role],
                    "artifactFilename": artifacts[role],
                    "artifactSha256": artifact_hashes[role],
                    "artifactBytes": artifact_bytes[role],
                }
                for role, row in contract["recipe"]["dependencies"].items()
            },
        }

    def _source_tree(self, root: Path, contract):
        role_maps = (
            (orchestrator.INPUT_RELATIVE_PATHS, contract["recipe"]["inputs"]),
            (orchestrator.GENERATOR_RELATIVE_PATHS, contract["recipe"]["generators"]),
            (orchestrator.HELPER_RELATIVE_PATHS, contract["recipe"]["helpers"]),
            (orchestrator.VERIFIER_RELATIVE_PATHS, contract["recipe"]["verifiers"]),
        )
        by_path = {}
        for paths, records in role_maps:
            for role, relative in paths.items():
                blob = by_path.setdefault(relative, f"approved:{relative}\n".encode())
                target = root / relative
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(blob)
                records[role].update(sha256=_sha(blob), bytes=len(blob))
        lock_blob = json.dumps(
            self._lock(contract), sort_keys=True, separators=(",", ":")
        ).encode()
        lock_path = root / orchestrator.DEPENDENCY_LOCK_RELATIVE_PATH
        lock_path.parent.mkdir(parents=True, exist_ok=True)
        lock_path.write_bytes(lock_blob)
        contract["recipe"]["runtime"]["dependencyLock"].update(
            sha256=_sha(lock_blob), bytes=len(lock_blob)
        )
        contract["recipeSha256"] = han_tiles_contract.recipe_sha256(contract["recipe"])
        return lock_blob

    def test_fixed_role_paths_close_every_actual_tracked_and_imported_input(self):
        self.assertEqual(set(han_tiles_contract.INPUT_ROLES), set(orchestrator.INPUT_RELATIVE_PATHS))
        self.assertEqual(set(han_tiles_contract.GENERATOR_ROLES), set(orchestrator.GENERATOR_RELATIVE_PATHS))
        self.assertEqual(set(han_tiles_contract.HELPER_ROLES), set(orchestrator.HELPER_RELATIVE_PATHS))
        self.assertEqual(set(han_tiles_contract.VERIFIER_ROLES), set(orchestrator.VERIFIER_RELATIVE_PATHS))
        self.assertEqual(
            {
                "ADMINISTRATIVE_UNITS", "ADMINISTRATIVE_BINDINGS",
                "ADMINISTRATIVE_HISTORY", "DUPLICATE_ADJUDICATIONS",
                "STABLE_ID_ADJUDICATIONS", "MERGE_ADJUDICATIONS",
                "TEMPORAL_ADJUDICATIONS", "EXTERNAL_PLACES",
                "EXTERNAL_PROVINCE_SEEDS", "EXTERNAL_ADMINISTRATIVE_SYSTEMS",
                "PROVINCE_SHAPE_EXCEPTIONS", "NON_PLAYABLE_REGIONS",
                "MODERN_ADMIN_RECIPE", "JURISDICTION_SEAT_RECOVERIES",
                "JURISDICTION_PARENT_ADJUDICATIONS",
            },
            set(han_tiles_contract.TRACKED_INPUT_ROLES),
        )

    def test_dependency_lock_is_strict_and_binds_every_distribution_and_runtime(self):
        contract = valid_contract()
        lock = self._lock(contract)
        installed = {
            row["distributionName"]: lock["distributions"][role]["version"]
            for role, row in contract["recipe"]["dependencies"].items()
        }
        runtime_platform = lock["platform"]
        blob = json.dumps(lock, sort_keys=True, separators=(",", ":")).encode()
        self.assertTrue(orchestrator.validate_dependency_lock_bytes(
            blob, contract, installed, runtime_platform,
        ))
        mutations = [
            lambda d: d.update(extra=True),
            lambda d: d.update(lockType="GENERAL_RESOLVER_LOCK"),
            lambda d: d.update(installPolicy="ALLOW_INDEX"),
            lambda d: d["platform"].update(machine="arm64"),
            lambda d: d["reviewedException"].update(selectedVersion="6.0.1"),
            lambda d: d["distributions"].pop("PYYAML"),
            lambda d: d["distributions"]["PYYAML"].update(distributionName="yaml"),
            lambda d: d["distributions"]["PYYAML"].update(version="9.9.9"),
            lambda d: d["distributions"]["PYYAML"].update(artifactFilename="../bad.whl"),
            lambda d: d["distributions"]["PYYAML"].update(artifactSha256="0" * 64),
            lambda d: d["distributions"]["PYYAML"].update(artifactBytes=0),
            lambda d: d.update(pythonVersion="0.0.0"),
        ]
        for index, mutate in enumerate(mutations):
            changed = copy.deepcopy(lock)
            mutate(changed)
            with self.subTest(index=index), self.assertRaises(ValueError):
                orchestrator.validate_dependency_lock_bytes(
                    json.dumps(changed, separators=(",", ":")).encode(),
                    contract, installed, runtime_platform,
                )

    def test_wheelhouse_requires_exact_reviewed_artifact_set_hashes_and_bytes(self):
        contract = valid_contract()
        lock = self._lock(contract)
        with tempfile.TemporaryDirectory() as directory:
            wheelhouse = Path(directory)
            for role, row in lock["distributions"].items():
                blob = f"reviewed wheel:{role}".encode()
                row.update(artifactSha256=_sha(blob), artifactBytes=len(blob))
                (wheelhouse / row["artifactFilename"]).write_bytes(blob)
            lock_blob = json.dumps(lock, separators=(",", ":")).encode()
            self.assertTrue(orchestrator.validate_wheelhouse(lock_blob, wheelhouse))
            target = wheelhouse / lock["distributions"]["HANJA"]["artifactFilename"]
            target.write_bytes(target.read_bytes() + b"drift")
            with self.assertRaisesRegex(ValueError, "hash|bytes"):
                orchestrator.validate_wheelhouse(lock_blob, wheelhouse)

    def test_approved_files_reject_missing_hash_drift_and_symlink(self):
        contract = valid_contract()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._source_tree(root, contract)
            approved = orchestrator.validate_approved_source_files(root, contract)
            self.assertIn(root.resolve() / "data/map/external-places.json", approved)

            target = root / orchestrator.INPUT_RELATIVE_PATHS["ADMINISTRATIVE_HISTORY"]
            original = target.read_bytes()
            target.write_bytes(original + b"drift")
            with self.assertRaisesRegex(ValueError, "hash|bytes"):
                orchestrator.validate_approved_source_files(root, contract)
            target.unlink()
            with self.assertRaisesRegex(ValueError, "missing"):
                orchestrator.validate_approved_source_files(root, contract)

    def test_source_root_symlink_is_rejected_before_resolution(self):
        contract = valid_contract()
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            source = base / "source"
            source.mkdir()
            self._source_tree(source, contract)
            alias = base / "source-alias"
            alias.symlink_to(source, target_is_directory=True)
            with self.assertRaisesRegex(ValueError, "symlink"):
                orchestrator.validate_approved_source_files(alias, contract)

            with self.assertRaisesRegex(ValueError, "source root symlink"):
                orchestrator.run_protected_build(
                    source_root=alias, output_root=base / "output",
                    work_root=base / "work", wheelhouse_root=base / "wheels",
                    contract=contract,
                )

    def test_reviewed_wheels_are_installed_into_the_execution_runtime(self):
        contract = valid_contract()
        lock = self._lock(contract)
        lock_blob = json.dumps(lock, separators=(",", ":")).encode()
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            runtime = base / "runtime"
            calls = []

            def run(command, **kwargs):
                calls.append(command)
                if command[1:3] == ["-m", "venv"]:
                    target = Path(command[4]) / "bin/python"
                    target.parent.mkdir(parents=True)
                    target.write_bytes(b"python")
                return subprocess.CompletedProcess(command, 0)

            probe = {
                "implementation": "CPython", "version": "3.14.5",
                "system": "Darwin", "machine": "x86_64", "pythonTag": "cp314",
                "distributions": {
                    row["distributionName"]: lock["distributions"][role]["version"]
                    for role, row in contract["recipe"]["dependencies"].items()
                },
            }
            with mock.patch.object(orchestrator.subprocess, "run", side_effect=run), mock.patch.object(
                orchestrator, "_probe_interpreter", return_value=probe,
            ):
                selected = orchestrator.prepare_reviewed_runtime(
                    base_python=Path("/approved/python"), runtime_root=runtime,
                    wheelhouse_root=base / "wheels", lock_blob=lock_blob,
                    contract=contract,
                )
            self.assertEqual(runtime / "bin/python", selected)
            self.assertEqual(["-m", "venv"], calls[0][1:3])
            self.assertIn("--copies", calls[0])
            self.assertIn("--no-index", calls[1])
            self.assertIn("--no-deps", calls[1])
            for row in lock["distributions"].values():
                self.assertIn(str(base / "wheels" / row["artifactFilename"]), calls[1])

    def test_clean_copy_contains_only_approved_contract_files(self):
        contract = valid_contract()
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            source, run = base / "source", base / "run"
            source.mkdir()
            self._source_tree(source, contract)
            (source / "unapproved.txt").write_text("must not leak")
            approved = orchestrator.validate_approved_source_files(source, contract)
            orchestrator.create_clean_copy(source, run, approved)
            self.assertFalse((run / "unapproved.txt").exists())
            self.assertTrue((run / "tools/map/build_han_places.py").is_file())
            with self.assertRaisesRegex(ValueError, "clean"):
                orchestrator.create_clean_copy(source, run, approved)

    def test_actual_canonical_modules_import_from_approved_clean_copy_only(self):
        relative_files = set(orchestrator.GENERATOR_RELATIVE_PATHS.values())
        relative_files.update(orchestrator.HELPER_RELATIVE_PATHS.values())
        relative_files.add("data/curated/han/administrative-units.json")
        approved = tuple(ROOT / relative for relative in sorted(relative_files))
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            run = base / "run"
            dependency = base / "dependency"
            dependency.mkdir()
            (dependency / "hanja.py").write_text(
                "def translate(value, mode): return value\n", encoding="utf-8"
            )
            orchestrator.create_clean_copy(ROOT, run, approved)
            env = dict(os.environ)
            env["PYTHONPATH"] = os.pathsep.join(
                (str(dependency), str(run / "tools/map"))
            )
            command = [
                sys.executable, "-c",
                "import build_han_places,build_junguozhi,build_terrain_grid,build_readings,build_tile_grid",
            ]
            result = subprocess.run(
                command, cwd=run, env=env, capture_output=True, text=True, check=False,
            )
            self.assertEqual(0, result.returncode, result.stderr)
            for script_name in (
                "build_han_places.py", "build_terrain_grid.py",
                "build_readings.py", "build_tile_grid.py",
            ):
                result = subprocess.run(
                    [sys.executable, str(run / "tools/map" / script_name), "--help"],
                    cwd=run, env=env, capture_output=True, text=True, check=False,
                )
                self.assertEqual(0, result.returncode, f"{script_name}: {result.stderr}")
            self.assertFalse((run / "unapproved.txt").exists())

    def test_materialized_han_tiles_must_pass_rle_and_connectivity_gates(self):
        tiles = json.loads((ROOT / "data/map/han-tiles.json").read_text(encoding="utf-8"))
        documents = {role: {} for role in han_tiles_contract.OUTPUT_ROLES}
        documents["HAN_TILES"] = tiles
        self.assertTrue(orchestrator.validate_semantic_outputs(documents))
        mutations = [
            lambda d: d["owner"][0].__setitem__(1, 1),
            lambda d: d["adjacency"].update(county=[]),
            lambda d: d["adjacency"].update(commandery=[]),
            lambda d: d["provinceRecords"].append(copy.deepcopy(d["provinceRecords"][0])),
            lambda d: d["jurisdictionRecords"][0]["provinceIds"].append("NO-SUCH-PROVINCE"),
            lambda d: d["commanderyRecords"][0]["jurisdictionIds"].clear(),
            lambda d: d["commanderyRecords"][0].update(seatJurisdictionId="NO-SUCH-JURISDICTION"),
        ]
        for index, mutate in enumerate(mutations):
            changed = copy.deepcopy(tiles)
            mutate(changed)
            documents["HAN_TILES"] = changed
            with self.subTest(index=index), self.assertRaisesRegex(
                ValueError, "RLE|connectivity|commandery|province|jurisdiction|seat"
            ):
                orchestrator.validate_semantic_outputs(documents)

    def test_stage_success_without_required_output_cannot_fall_back(self):
        contract = valid_contract()
        with tempfile.TemporaryDirectory() as directory:
            run = Path(directory)
            for relative in orchestrator.GENERATOR_RELATIVE_PATHS.values():
                script = run / relative
                script.parent.mkdir(parents=True, exist_ok=True)
                script.write_text("raise SystemExit(0)\n", encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "did not produce"):
                orchestrator.execute_clean_run(
                    run_root=run, contract=contract,
                    python_executable=Path(sys.executable).resolve(),
                )

    def test_two_identical_runs_materialize_tiles_and_emit_valid_pathless_attestation(self):
        contract = valid_contract()
        outputs = copy.deepcopy(contract["expectedOutputs"])
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            source, output, work = base / "source", base / "output", base / "work"
            source.mkdir()
            self._source_tree(source, contract)
            blobs = {role: f"approved:{role}".encode() for role in outputs}
            blobs["HAN_TILES"] = b'{"approved":"han-tiles"}\n'
            for role, blob in blobs.items():
                outputs[role].update(sha256=_sha(blob), bytes=len(blob))
            contract["expectedOutputs"] = outputs

            with mock.patch.object(orchestrator, "prevalidate_runtime_and_lock", return_value=b"lock"), mock.patch.object(
                orchestrator, "prepare_reviewed_runtime", return_value=Path(sys.executable).resolve(),
            ), mock.patch.object(
                orchestrator, "execute_clean_run",
                side_effect=[(copy.deepcopy(outputs), copy.deepcopy(blobs)),
                             (copy.deepcopy(outputs), copy.deepcopy(blobs))],
            ), mock.patch.object(orchestrator, "validate_semantic_outputs"):
                attestation = orchestrator.run_protected_build(
                    source_root=source, output_root=output, work_root=work,
                    wheelhouse_root=base / "wheels", contract=contract,
                )

            materialized = output / "data/map/han-tiles.json"
            self.assertEqual(blobs["HAN_TILES"], materialized.read_bytes())
            self.assertTrue(han_tiles_contract.validate_attestation(contract, attestation))
            serialized = json.dumps(attestation, ensure_ascii=False).lower()
            for forbidden in (str(source).lower(), "coordinate", "projection", "lon", "lat"):
                self.assertNotIn(forbidden, serialized)

    def test_run_drift_fails_before_materialization(self):
        contract = valid_contract()
        outputs = copy.deepcopy(contract["expectedOutputs"])
        blobs = {role: f"approved:{role}".encode() for role in outputs}
        for role, blob in blobs.items():
            outputs[role].update(sha256=_sha(blob), bytes=len(blob))
        contract["expectedOutputs"] = outputs
        drifted = copy.deepcopy(outputs)
        drifted["READINGS"]["sha256"] = _sha(b"drift")
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            source = base / "source"
            source.mkdir()
            self._source_tree(source, contract)
            with mock.patch.object(orchestrator, "prevalidate_runtime_and_lock", return_value=b"lock"), mock.patch.object(
                orchestrator, "prepare_reviewed_runtime", return_value=Path(sys.executable).resolve(),
            ), mock.patch.object(
                orchestrator, "execute_clean_run",
                side_effect=[(outputs, blobs), (drifted, blobs)],
            ), mock.patch.object(orchestrator, "validate_semantic_outputs"):
                with self.assertRaisesRegex(ValueError, "drift|differ"):
                    orchestrator.run_protected_build(
                        source_root=source, output_root=base / "output",
                        work_root=base / "work", wheelhouse_root=base / "wheels",
                        contract=contract,
                    )
            self.assertFalse((base / "output/data/map/han-tiles.json").exists())


class ProtectedOrchestratorApiRedTest(unittest.TestCase):
    def test_protected_orchestrator_api_exists(self):
        self.assertIsNotNone(orchestrator)
        for name in (
            "validate_dependency_lock_bytes", "validate_approved_source_files",
            "create_clean_copy", "execute_clean_run", "validate_semantic_outputs",
            "run_protected_build",
        ):
            self.assertTrue(hasattr(orchestrator, name), name)


if __name__ == "__main__":
    unittest.main()
