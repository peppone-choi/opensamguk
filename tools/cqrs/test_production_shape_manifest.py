import copy
import hashlib
import importlib.util
import json
import os
import sys
import tempfile
import time
import unittest
import zipfile
from pathlib import Path
from unittest import mock


RUNNER_PATH = Path(__file__).with_name("run-runtime-baseline.py")
MANIFEST_SCHEMA_PATH = Path(__file__).with_name("production-shape-manifest.schema.json")
RUNNER_SPEC = importlib.util.spec_from_file_location("cqrs_runtime_baseline_manifest_test", RUNNER_PATH)
if RUNNER_SPEC is None or RUNNER_SPEC.loader is None:
    raise RuntimeError(f"Unable to load {RUNNER_PATH}")
runner = importlib.util.module_from_spec(RUNNER_SPEC)
sys.modules[RUNNER_SPEC.name] = runner
RUNNER_SPEC.loader.exec_module(runner)


TABLE_CARDINALITIES = {
    "worldState": 1,
    "city": 4,
    "nation": 2,
    "general": 8,
    "diplomacy": 1,
    "rankData": 3,
    "logEntry": 266,
}

SNAPSHOT_CARDINALITIES = {
    "generals": 8,
    "cities": 4,
    "nations": 2,
    "diplomacy": 1,
    "accessLogs": 0,
    "globalLogs": 0,
    "nationHistoryEntries": 0,
    "generalHistoryEntries": 0,
}

RAW_TABLE_NAMES = {
    "worldState": "world_state",
    "city": "city",
    "nation": "nation",
    "general": "general",
    "diplomacy": "diplomacy",
    "rankData": "rank_data",
    "logEntry": "log_entry",
}

LOADER_INPUT_IDS = {
    "worldState",
    "ngGames",
    "archivedNationIds",
    "statistics",
    "nationHistoryLogs",
    "generalHistoryLogs",
    "systemActionLogs",
    "systemHistoryLogs",
    "activeUniqueAuctionItems",
    "storedUniqueItemNamespaces",
    "gameEnv",
    "nationEnv",
    "inheritancePoints",
    "generalRankValues",
    "nations",
    "cities",
    "generals",
    "diplomacy",
    "generalAccessLogs",
}

LOADER_INPUT_INVENTORY_JAR_PATH = "BOOT-INF/classes/opensamguk/engine/baseline/loader-input-inventory.json"


def manifest_sha256(manifest: dict) -> str:
    body = copy.deepcopy(manifest)
    body.pop("sha256", None)
    encoded = json.dumps(body, ensure_ascii=True, separators=(",", ":"), sort_keys=True).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def seal(manifest: dict) -> dict:
    manifest["sha256"] = manifest_sha256(manifest)
    return manifest


def write_jar_with_mutated_inventory_query(jar: Path, target: Path, old: bytes, new: bytes) -> None:
    replaced = False
    with zipfile.ZipFile(jar, "r") as source, zipfile.ZipFile(target, "w") as destination:
        for entry in source.infolist():
            content = source.read(entry.filename)
            if entry.filename == LOADER_INPUT_INVENTORY_JAR_PATH:
                if content.count(old) != 1:
                    raise AssertionError(f"expected exactly one inventory query occurrence for {old!r}")
                content = content.replace(old, new)
                replaced = True
            destination.writestr(entry, content)
    if not replaced:
        raise AssertionError("baseline jar did not contain the loader-input inventory resource")


def complete_manifest() -> dict:
    current_tables = dict(TABLE_CARDINALITIES)
    current_snapshot = dict(SNAPSHOT_CARDINALITIES)
    required_dimensions = [
        *(f"tables.{name}" for name in current_tables),
        *(f"snapshot.{name}" for name in current_snapshot),
        *(f"loaderInputs.{name}" for name in LOADER_INPUT_IDS),
        "fixture.current.fixedHotActionRows",
        "fixture.current.coldHistoryRows",
        "fixture.payloadSizeBytes.hotAction",
        "fixture.payloadSizeBytes.coldHistory",
    ]
    cold_tables = dict(current_tables, logEntry=356)
    cold_snapshot = dict(current_snapshot)
    manifest = {
        "schemaVersion": "cqrs-production-shape-manifest.v3",
        "loaderInputInventorySha256": runner.loader_input_inventory_sha256(),
        "payloadByteSemantics": "selected-loader-fields-postgres-text-bytes.v1",
        "source": {
            "sourceClass": "production-aggregate-observation",
            "observedAt": "2026-07-11T00:00:00Z",
            "provenance": [
                {
                    "sourceClass": "ci-aggregate-record",
                    "observedAt": "2026-07-11T00:00:00Z",
                    "evidenceSha256": "a" * 64,
                    "dimensions": required_dimensions,
                }
            ],
        },
        "observed": {
            "tableCardinalities": dict(current_tables),
            "snapshotCardinalities": dict(current_snapshot),
            "loaderInputs": loader_inputs(cold_history_rows=10),
        },
        "fixture": {
            "profiles": {
                "current": {
                    "tableCardinalities": dict(current_tables),
                    "snapshotCardinalities": dict(current_snapshot),
                    "loaderInputs": loader_inputs(cold_history_rows=10),
                    "fixedHotActionRows": 256,
                    "coldHistoryRows": 10,
                    "payloadSizeBytes": {"hotAction": 200, "coldHistory": 200},
                },
                "cold10x": {
                    "tableCardinalities": cold_tables,
                    "snapshotCardinalities": cold_snapshot,
                    "loaderInputs": loader_inputs(cold_history_rows=100),
                    "fixedHotActionRows": 256,
                    "coldHistoryRows": 100,
                    "payloadSizeBytes": {"hotAction": 200, "coldHistory": 200},
                },
            },
        },
    }
    return seal(manifest)


def differing_shape_manifest() -> dict:
    manifest = complete_manifest()
    for profile in ("current", "cold10x"):
        fixture = manifest["fixture"]["profiles"][profile]
        fixture["tableCardinalities"]["general"] += 1
        fixture["snapshotCardinalities"]["generals"] += 1
        for field in ("sourceRows", "retainedItems", "payloadBytes"):
            fixture["loaderInputs"]["generals"][field] += 1
    manifest["observed"]["tableCardinalities"]["general"] += 1
    manifest["observed"]["snapshotCardinalities"]["generals"] += 1
    for field in ("sourceRows", "retainedItems", "payloadBytes"):
        manifest["observed"]["loaderInputs"]["generals"][field] += 1
    return seal(manifest)


def loader_inputs(*, cold_history_rows: int) -> dict:
    values = {
        input_id: {"sourceRows": 0, "retainedItems": 0, "payloadBytes": 0}
        for input_id in LOADER_INPUT_IDS
    }
    values.update(
        {
            "worldState": {"sourceRows": 1, "retainedItems": 1, "payloadBytes": 2},
            "ngGames": {"sourceRows": 1, "retainedItems": 1, "payloadBytes": 2},
            "statistics": {"sourceRows": 1, "retainedItems": 1, "payloadBytes": 2},
            "systemActionLogs": {"sourceRows": 256, "retainedItems": 0, "payloadBytes": 51_200},
            "systemHistoryLogs": {
                "sourceRows": cold_history_rows,
                "retainedItems": 0,
                "payloadBytes": cold_history_rows * 200,
            },
            "nations": {"sourceRows": 2, "retainedItems": 2, "payloadBytes": 2},
            "cities": {"sourceRows": 4, "retainedItems": 4, "payloadBytes": 4},
            "generals": {"sourceRows": 8, "retainedItems": 8, "payloadBytes": 8},
            "diplomacy": {"sourceRows": 1, "retainedItems": 1, "payloadBytes": 1},
        }
    )
    return values


def complete_loader_manifest() -> dict:
    return complete_manifest()


def raw_record_for_fixture_config(profile: str, config: dict) -> dict:
    return {
        "schemaVersion": "cqrs-runtime-baseline.raw.v2",
        "profile": profile,
        "fixture": {
            "kind": "sanitized-production-shape",
            "profile": profile,
            "baseRows": config["coldHistoryRows"] // (1 if profile == "current" else 10),
            "coldHistoryMultiplier": 1 if profile == "current" else 10,
            "coldHistoryRows": config["coldHistoryRows"],
            "fixedHotLogRows": config["fixedHotActionRows"],
            "payloadSizeBytes": config["payloadSizeBytes"],
            "manifestSha256": config["manifestSha256"],
            "loaderInputInventorySha256": config["loaderInputInventorySha256"],
            "payloadByteSemantics": config["payloadByteSemantics"],
            "loaderInputObservation": config["loaderInputObservation"],
            "sha256": "fixture-test-only",
        },
        "cgroup": {"memoryLimitBytes": runner.REQUIRED_CGROUP_BYTES},
        "jvm": {"version": "21.0.11+10-LTS", "inputArguments": list(runner.REQUIRED_JVM_ARGS)},
        "memory": {"heapAfterGc": {"usedBytes": 1}, "rssAfterGcBytes": 1},
        "gc": {"collectionTimeDeltaMillis": 1},
        "durations": {"bootDurationMs": 1, "snapshotDurationMs": 1, "tickDurationMs": 1},
        "artifacts": {"jfrConfiguration": "profile"},
        "images": {
            "probeTag": "eclipse-temurin:21-jre",
            "probeId": "sha256:probe",
            "postgresTag": "postgres:16-alpine",
            "postgresId": "sha256:postgres",
        },
        "rows": {
            "database": {
                RAW_TABLE_NAMES[name]: count for name, count in config["expectedTableCardinalities"].items()
            },
            "snapshot": config["expectedSnapshotCardinalities"],
            "loaderInputs": config["expectedLoaderInputs"],
        },
    }


class ProductionShapeManifestTest(unittest.TestCase):
    def validator(self):
        candidate = getattr(runner, "validate_production_shape_manifest", None)
        self.assertIsNotNone(candidate, "runner must expose fail-closed production-shape validation")
        return candidate

    def fixture_config(self):
        candidate = getattr(runner, "fixture_config_for_manifest", None)
        self.assertIsNotNone(candidate, "runner must project an approved manifest into a probe fixture config")
        return candidate

    def test_complete_manifest_projects_sanitized_profile_configs(self) -> None:
        manifest = self.validator()(complete_manifest())
        current = self.fixture_config()(manifest, "current")
        cold = self.fixture_config()(manifest, "cold10x")

        self.assertEqual("cqrs-runtime-baseline.fixture-config.v3", current["schemaVersion"])
        self.assertEqual("sanitized-production-shape", current["kind"])
        self.assertEqual(10, current["coldHistoryRows"])
        self.assertEqual(100, cold["coldHistoryRows"])
        self.assertEqual(manifest.sha256, current["manifestSha256"])
        self.assertNotIn("source", current)
        self.assertNotIn("observedAt", current)

    def test_manifest_requires_the_complete_world_snapshot_loader_inventory(self) -> None:
        self.assertEqual(LOADER_INPUT_IDS, set(runner.required_loader_input_ids()))
        manifest = complete_loader_manifest()
        self.validator()(manifest)

        manifest["observed"]["loaderInputs"].pop("generalRankValues")
        manifest["fixture"]["profiles"]["current"]["loaderInputs"].pop("generalRankValues")
        manifest["fixture"]["profiles"]["cold10x"]["loaderInputs"].pop("generalRankValues")
        seal(manifest)
        with self.assertRaises(runner.RunnerFailure):
            self.validator()(manifest)

    def test_checked_in_schema_matches_the_fail_closed_manifest_contract(self) -> None:
        schema = json.loads(MANIFEST_SCHEMA_PATH.read_text(encoding="utf-8"))
        self.assertEqual("cqrs-production-shape-manifest.v3", schema["properties"]["schemaVersion"]["const"])
        self.assertFalse(schema["additionalProperties"])
        provenance = schema["$defs"]["provenanceRecord"]
        self.assertFalse(provenance["additionalProperties"])
        self.assertNotIn("hostPath", json.dumps(schema, sort_keys=True))

    def test_manifest_requires_explicit_selected_field_payload_semantics(self) -> None:
        manifest = complete_manifest()
        manifest["payloadByteSemantics"] = "selected-loader-fields-postgres-text-bytes.v1"
        seal(manifest)

        validated = self.validator()(manifest)
        self.assertEqual("selected-loader-fields-postgres-text-bytes.v1", validated.payload_byte_semantics)

    def test_python_schema_and_fixture_projection_share_kotlin_int_range(self) -> None:
        maximum = 2_147_483_647
        schema = json.loads(MANIFEST_SCHEMA_PATH.read_text(encoding="utf-8"))
        self.assertEqual(maximum, schema["$defs"]["nonNegativeCardinality"]["maximum"])
        manifest = complete_manifest()
        manifest["observed"]["tableCardinalities"]["general"] = maximum
        manifest["fixture"]["profiles"]["current"]["tableCardinalities"]["general"] = maximum
        manifest["fixture"]["profiles"]["cold10x"]["tableCardinalities"]["general"] = maximum
        seal(manifest)
        parsed = self.validator()(manifest)
        config = self.fixture_config()(parsed, "current")
        runner.validate_production_shape_fixture_config(config, "current")

        manifest["observed"]["tableCardinalities"]["general"] = maximum + 1
        manifest["fixture"]["profiles"]["current"]["tableCardinalities"]["general"] = maximum + 1
        manifest["fixture"]["profiles"]["cold10x"]["tableCardinalities"]["general"] = maximum + 1
        seal(manifest)
        with self.assertRaises(runner.RunnerFailure):
            self.validator()(manifest)

    def test_incomplete_manifest_fails_closed_even_when_its_hash_is_resealed(self) -> None:
        manifest = complete_manifest()
        manifest["observed"]["tableCardinalities"].pop("logEntry")
        seal(manifest)

        with self.assertRaises(runner.RunnerFailure):
            self.validator()(manifest)

    def test_tampered_manifest_fails_sha256_validation(self) -> None:
        manifest = complete_manifest()
        manifest["fixture"]["profiles"]["current"]["payloadSizeBytes"]["coldHistory"] = 201

        with self.assertRaises(runner.RunnerFailure):
            self.validator()(manifest)

    def test_cross_profile_equivalence_requires_only_exact_tenfold_cold_history_growth(self) -> None:
        manifest = complete_manifest()
        manifest["fixture"]["profiles"]["cold10x"]["tableCardinalities"]["general"] += 1
        seal(manifest)

        with self.assertRaises(runner.RunnerFailure):
            self.validator()(manifest)

    def test_production_manifest_rejects_unbounded_cold_boot_log_retention(self) -> None:
        manifest = complete_manifest()
        for profile_name, retained in (("current", 10), ("cold10x", 100)):
            profile = manifest["fixture"]["profiles"][profile_name]
            profile["snapshotCardinalities"]["globalLogs"] = 256 + retained
            profile["loaderInputs"]["systemActionLogs"]["retainedItems"] = 256
            profile["loaderInputs"]["systemHistoryLogs"]["retainedItems"] = retained
        manifest["observed"]["snapshotCardinalities"] = copy.deepcopy(
            manifest["fixture"]["profiles"]["current"]["snapshotCardinalities"]
        )
        manifest["observed"]["loaderInputs"] = copy.deepcopy(
            manifest["fixture"]["profiles"]["current"]["loaderInputs"]
        )
        seal(manifest)

        with self.assertRaisesRegex(runner.RunnerFailure, "bounded cold boot"):
            self.validator()(manifest)

    def test_unknown_or_sensitive_manifest_fields_are_rejected(self) -> None:
        manifest = complete_manifest()
        manifest["source"]["provenance"][0]["hostPath"] = "/private/production/dump.sql"
        seal(manifest)

        with self.assertRaises(runner.RunnerFailure):
            self.validator()(manifest)

    def test_runner_writes_only_sanitized_fixture_projections_for_the_probe(self) -> None:
        writer = getattr(runner, "write_production_shape_fixture_configs", None)
        self.assertIsNotNone(writer, "runner must write sanitized fixture projections for the probe")
        manifest = self.validator()(complete_manifest())
        with tempfile.TemporaryDirectory() as temporary:
            paths = writer(manifest, Path(temporary) / "fixture")
            self.assertEqual({"current", "cold10x"}, set(paths))
            current = json.loads(paths["current"].read_text(encoding="utf-8"))
            self.assertEqual(manifest.sha256, current["manifestSha256"])
            self.assertNotIn("source", current)
            self.assertNotIn("provenance", current)

    def test_raw_validation_proves_the_observed_fixture_matches_the_approved_profile(self) -> None:
        manifest = self.validator()(complete_manifest())
        config = self.fixture_config()(manifest, "current")
        raw = raw_record_for_fixture_config("current", config)
        runner.validate_raw(raw, "current", expected_fixture_config=config)

        wrong_shape = copy.deepcopy(raw)
        wrong_shape["rows"]["database"]["log_entry"] += 1
        with self.assertRaises(runner.RunnerFailure):
            runner.validate_raw(wrong_shape, "current", expected_fixture_config=config)

    def test_probe_mounts_only_the_sanitized_profile_config_and_validates_its_raw_result(self) -> None:
        manifest = self.validator()(complete_manifest())
        with tempfile.TemporaryDirectory() as temporary:
            run_dir = Path(temporary) / "run"
            for directory in (run_dir / "raw", run_dir / "jfr", run_dir / "logs"):
                directory.mkdir(parents=True)
            config_path = runner.write_production_shape_fixture_configs(manifest, run_dir / "fixture")["current"]
            config = json.loads(config_path.read_text(encoding="utf-8"))
            jar = Path(temporary) / "game-engine-cqrs-baseline.jar"
            jar.touch()
            commands: list[list[str]] = []

            def fake_command(command, **_kwargs):
                commands.append(list(command))
                if command[:2] == ["docker", "run"]:
                    (run_dir / "raw" / "current-1.json").write_text(
                        json.dumps(raw_record_for_fixture_config("current", config)), encoding="utf-8"
                    )
                    (run_dir / "jfr" / "current-1.jfr").write_bytes(b"fixture-jfr")
                return mock.Mock(returncode=0, stdout="")

            with mock.patch.object(runner, "command_output", side_effect=fake_command), mock.patch.object(
                runner, "start_postgres"
            ), mock.patch.object(runner, "cleanup_container"):
                raw = runner.run_probe(
                    jar=jar,
                    run_dir=run_dir,
                    network="cqrs-test-network",
                    run_id="production-shape-test",
                    profile="current",
                    sample=1,
                    base_rows=10_000,
                    images={
                        "probeTag": "eclipse-temurin:21-jre",
                        "probeId": "sha256:probe",
                        "postgresTag": "postgres:16-alpine",
                        "postgresId": "sha256:postgres",
                    },
                    fixture_config_path=config_path,
                )

            docker_run = next(command for command in commands if command[:2] == ["docker", "run"])
            self.assertIn("--fixture-config=/contract/fixture-config.json", docker_run)
            self.assertNotIn("--base-rows=10000", docker_run)
            contract_mount = next(
                value for value in docker_run if value.endswith("dst=/contract/fixture-config.json,readonly")
            )
            self.assertNotIn(str(config_path.resolve()), contract_mount)
            self.assertNotIn(str(run_dir.resolve()), contract_mount)
            self.assertEqual("sanitized-production-shape", raw["fixture"]["kind"])

    def test_probe_rejects_replaced_or_symlinked_immutable_contract_input(self) -> None:
        manifest = self.validator()(complete_manifest())
        with tempfile.TemporaryDirectory() as temporary:
            run_dir = Path(temporary) / "run"
            for directory in (run_dir / "raw", run_dir / "jfr", run_dir / "logs"):
                directory.mkdir(parents=True)
            config_path = runner.write_production_shape_fixture_configs(manifest, run_dir / "fixture")["current"]
            jar = Path(temporary) / "game-engine-cqrs-baseline.jar"
            jar.touch()

            def fake_command(command, **_kwargs):
                if command[:2] == ["docker", "run"]:
                    contract_mount = next(
                        value for value in command if value.endswith("dst=/contract/fixture-config.json,readonly")
                    )
                    contract_path = Path(contract_mount.split("src=", 1)[1].split(",dst=", 1)[0])
                    contract_path.unlink()
                    contract_path.symlink_to(config_path)
                return mock.Mock(returncode=0, stdout="")

            with mock.patch.object(runner, "command_output", side_effect=fake_command), mock.patch.object(
                runner, "start_postgres"
            ), mock.patch.object(runner, "cleanup_container"):
                with self.assertRaises(runner.RunnerFailure):
                    runner.run_probe(
                        jar=jar,
                        run_dir=run_dir,
                        network="cqrs-test-network",
                        run_id="production-shape-test",
                        profile="current",
                        sample=1,
                        base_rows=10_000,
                        images={
                            "probeTag": "eclipse-temurin:21-jre",
                            "probeId": "sha256:probe",
                            "postgresTag": "postgres:16-alpine",
                            "postgresId": "sha256:postgres",
                        },
                        fixture_config_path=config_path,
                    )

    def test_recorded_sanitized_configs_are_required_again_when_analyzing_six_raw_samples(self) -> None:
        manifest = self.validator()(complete_manifest())
        with tempfile.TemporaryDirectory() as temporary:
            run_dir = Path(temporary) / "run"
            for directory in (run_dir / "raw", run_dir / "jfr", run_dir / "logs"):
                directory.mkdir(parents=True)
            configs = runner.write_production_shape_run_contract(manifest, run_dir)
            for profile in runner.PROFILES:
                config = json.loads(configs[profile].read_text(encoding="utf-8"))
                for index in range(1, runner.SAMPLES_PER_PROFILE + 1):
                    stem = f"{profile}-{index}"
                    raw = raw_record_for_fixture_config(profile, config)
                    raw["artifacts"]["jfrFile"] = f"{stem}.jfr"
                    (run_dir / "raw" / f"{stem}.json").write_text(json.dumps(raw), encoding="utf-8")
                    (run_dir / "jfr" / f"{stem}.jfr").write_bytes(b"fixture-jfr")

            layout = runner.preflight_analysis_artifacts(run_dir)
            records = runner.read_existing_run_records(layout)
            contract = runner.fixture_contract(records)
            self.assertEqual("sanitized-production-shape", contract["kind"])
            self.assertEqual(manifest.sha256, contract["manifestSha256"])

            tampered_path = run_dir / "raw" / "cold10x-3.json"
            tampered = json.loads(tampered_path.read_text(encoding="utf-8"))
            tampered["rows"]["snapshot"]["globalLogs"] += 1
            tampered_path.write_text(json.dumps(tampered), encoding="utf-8")
            with self.assertRaises(runner.RunnerFailure):
                runner.read_existing_run_records(layout)

    def test_analysis_rejects_coherent_config_and_raw_tampering_against_the_canonical_manifest(self) -> None:
        manifest = self.validator()(complete_manifest())
        with tempfile.TemporaryDirectory() as temporary:
            run_dir = Path(temporary) / "run"
            for directory in (run_dir / "raw", run_dir / "jfr", run_dir / "logs"):
                directory.mkdir(parents=True)
            contracts = runner.write_production_shape_run_contract(manifest, run_dir)
            for profile in runner.PROFILES:
                config_path = contracts[profile]
                config = json.loads(config_path.read_text(encoding="utf-8"))
                config["expectedTableCardinalities"]["general"] += 1
                config["expectedSnapshotCardinalities"]["globalLogs"] += 1
                config_path.write_bytes(runner.canonical_fixture_config_bytes(config))
                for index in range(1, runner.SAMPLES_PER_PROFILE + 1):
                    stem = f"{profile}-{index}"
                    raw = raw_record_for_fixture_config(profile, config)
                    raw["artifacts"]["jfrFile"] = f"{stem}.jfr"
                    (run_dir / "raw" / f"{stem}.json").write_text(json.dumps(raw), encoding="utf-8")
                    (run_dir / "jfr" / f"{stem}.jfr").write_bytes(b"fixture-jfr")

            layout = runner.preflight_analysis_artifacts(run_dir)
            with self.assertRaises(runner.RunnerFailure):
                runner.read_existing_run_records(layout)

    def test_validate_manifest_cli_accepts_a_complete_sanitized_fixture_without_starting_measurement(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            manifest_path = Path(temporary) / "manifest.json"
            manifest_path.write_text(json.dumps(complete_manifest()), encoding="utf-8")
            with mock.patch.object(
                sys,
                "argv",
                ["run-runtime-baseline.py", "--validate-production-shape-manifest", str(manifest_path)],
            ):
                self.assertEqual(0, runner.main())

    def test_local_sanitized_aggregate_policy_is_explicit_and_feasible_without_database_observation(self) -> None:
        policy_path = getattr(runner, "LOCAL_SANITIZED_AGGREGATE_POLICY_PATH", None)
        loader = getattr(runner, "load_local_sanitized_aggregate_policy", None)
        feasibility = getattr(runner, "validate_local_sanitized_aggregate_feasibility", None)
        self.assertIsNotNone(policy_path, "local surrogate must use a checked-in deterministic policy")
        self.assertIsNotNone(loader, "runner must load the local sanitized aggregate policy")
        self.assertIsNotNone(feasibility, "runner must validate local materialization feasibility before Docker")

        policy = loader(policy_path)
        self.assertEqual("local-sanitized-aggregate-surrogate", policy.fixture_kind)
        self.assertEqual("op123-local-sanitized-aggregate-v1", policy.policy_id)
        for profile in policy.profiles.values():
            self.assertEqual(0, profile.snapshot_cardinalities["globalLogs"])
            self.assertEqual(0, profile.loader_inputs["systemActionLogs"].retained_items)
            self.assertEqual(0, profile.loader_inputs["systemHistoryLogs"].retained_items)
        feasibility(policy)

    def test_local_policy_rejects_impossible_tick_shape_before_materialization(self) -> None:
        policy_path = getattr(runner, "LOCAL_SANITIZED_AGGREGATE_POLICY_PATH", None)
        loader = getattr(runner, "load_local_sanitized_aggregate_policy", None)
        validator = getattr(runner, "validate_local_sanitized_aggregate_feasibility", None)
        reseal = getattr(runner, "local_sanitized_aggregate_policy_sha256", None)
        self.assertIsNotNone(policy_path)
        self.assertIsNotNone(loader)
        self.assertIsNotNone(validator)
        self.assertIsNotNone(reseal)

        policy = loader(policy_path)
        impossible = copy.deepcopy(policy.canonical_document)
        for profile in ("current", "cold10x"):
            target = impossible["fixture"]["profiles"][profile]
            target["tableCardinalities"]["general"] = 0
            target["snapshotCardinalities"]["generals"] = 0
            target["loaderInputs"]["generals"] = {
                "sourceRows": 0,
                "retainedItems": 0,
                "payloadBytes": 0,
            }
        impossible["sha256"] = reseal(impossible)

        with self.assertRaises(runner.RunnerFailure):
            validator(loader(policy_path, value=impossible))

    def test_local_policy_projects_canonical_per_profile_fixture_configs(self) -> None:
        policy = runner.load_local_sanitized_aggregate_policy(runner.LOCAL_SANITIZED_AGGREGATE_POLICY_PATH)
        with tempfile.TemporaryDirectory() as temporary:
            run_directory = Path(temporary) / "local-run"
            run_directory.mkdir()
            paths = runner.write_local_sanitized_aggregate_run_contract(policy, run_directory)

            recorded = runner.load_canonical_local_sanitized_aggregate_policy(paths["manifest"])
            self.assertEqual(policy.sha256, recorded.sha256)
            self.assertEqual(policy.policy_id, recorded.policy_id)
            for profile in runner.PROFILES:
                expected = runner.local_fixture_config_for_policy(policy, profile)
                self.assertEqual(
                    runner.canonical_fixture_config_bytes(expected),
                    paths[profile].read_bytes(),
                )
                validated = runner.load_baseline_fixture_config(paths[profile], profile)
                self.assertEqual("local-sanitized-aggregate-surrogate", validated["kind"])
                self.assertEqual(policy.sha256, validated["policySha256"])


@unittest.skipUnless(
    os.environ.get("RUN_CQRS_PACKAGED_CLI_REGRESSION") == "1",
    "set RUN_CQRS_PACKAGED_CLI_REGRESSION=1 to run the packaged Kotlin CLI regression",
)
class ProductionShapePackagedCliRegressionTest(unittest.TestCase):
    def test_fixture_config_log_entry_containment_rejects_int_overflow(self) -> None:
        host_java_home, host_java = runner.resolve_host_jdk21()
        runner.DEFAULT_OUTPUT_ROOT.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(prefix="fixture-config-cli-", dir=runner.DEFAULT_OUTPUT_ROOT) as temporary:
            run_dir = Path(temporary)
            (run_dir / "logs").mkdir()
            manifest = runner.validate_production_shape_manifest(complete_manifest())
            normal_config = runner.fixture_config_for_manifest(manifest, "current")
            normal_path = run_dir / "normal.json"
            normal_path.write_bytes(runner.canonical_fixture_config_bytes(normal_config))
            jar = runner.build_baseline_jar(run_dir, host_java_home)

            normal = runner.command_output(
                [
                    str(host_java),
                    "-jar",
                    str(jar),
                    "--profile=current",
                    f"--validate-fixture-config={normal_path}",
                ],
                check=False,
            )
            self.assertEqual(0, normal.returncode, normal.stdout)
            self.assertIn("Production-shape fixture config valid: profile=current", normal.stdout)

            overflow_config = copy.deepcopy(normal_config)
            overflow_config["fixedHotActionRows"] = 2_147_483_647
            overflow_config["coldHistoryRows"] = 1
            overflow_config["expectedTableCardinalities"]["logEntry"] = 2_147_483_647
            overflow_path = run_dir / "overflow.json"
            overflow_path.write_bytes(runner.canonical_fixture_config_bytes(overflow_config))
            overflow = runner.command_output(
                [
                    str(host_java),
                    "-jar",
                    str(jar),
                    "--profile=current",
                    f"--validate-fixture-config={overflow_path}",
                ],
                check=False,
            )
            self.assertNotEqual(0, overflow.returncode, overflow.stdout)
            self.assertIn("integer overflow", overflow.stdout)

    def test_ng_games_multi_row_composition_and_validation_only_capture_block(self) -> None:
        host_java_home, host_java = runner.resolve_host_jdk21()
        runner.DEFAULT_OUTPUT_ROOT.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(prefix="fixture-capture-block-", dir=runner.DEFAULT_OUTPUT_ROOT) as temporary:
            run_dir = Path(temporary)
            (run_dir / "logs").mkdir()
            manifest = runner.validate_production_shape_manifest(differing_shape_manifest())
            config = runner.fixture_config_for_manifest(manifest, "current")
            config_path = run_dir / "differing-shape.json"
            config_path.write_bytes(runner.canonical_fixture_config_bytes(config))
            jar = runner.build_baseline_jar(run_dir, host_java_home)

            active_fields = [
                "101",
                "active-server",
                "184",
                "scenario_1010",
                "active scenario",
                "map-1",
                '{"server_id":"active-server"}',
            ]
            inactive_fields = [
                "102",
                "inactive-server",
                "184",
                "scenario_1010",
                "inactive scenario",
                "map-1",
                "x" * 4096,
            ]
            active_payload_bytes = sum(len(value.encode("utf-8")) for value in active_fields)
            inactive_payload_bytes = sum(len(value.encode("utf-8")) for value in inactive_fields)
            expected_payload_bytes = len(b"2") + active_payload_bytes
            all_rows_payload_bytes = active_payload_bytes + inactive_payload_bytes
            composition = runner.command_output(
                [
                    str(host_java),
                    "-jar",
                    str(jar),
                    f"--validate-ng-games-observation=2:{len(b'2')}:1:{active_payload_bytes}",
                ],
                check=False,
            )
            self.assertEqual(0, composition.returncode, composition.stdout)
            self.assertIn(
                f"ngGames observation valid: sourceRows=2 retainedItems=2 payloadBytes={expected_payload_bytes}",
                composition.stdout,
            )
            self.assertGreater(all_rows_payload_bytes, expected_payload_bytes)

            for name, old, new in (
                (
                    "count-query-drift",
                    b"octet_length((count(*))::text)",
                    b"octet_length('wrong-count')",
                ),
                (
                    "active-query-drift",
                    b"WHERE server_id = ?",
                    b"WHERE server_id IS NULL",
                ),
            ):
                mutated_jar = run_dir / f"{name}.jar"
                write_jar_with_mutated_inventory_query(jar, mutated_jar, old, new)
                drift = runner.command_output(
                    [
                        str(host_java),
                        "-jar",
                        str(mutated_jar),
                        f"--validate-ng-games-observation=2:{len(b'2')}:1:{active_payload_bytes}",
                    ],
                    check=False,
                )
                self.assertNotEqual(0, drift.returncode, drift.stdout)
                self.assertIn("executable source diverged from the checked-in inventory for ngGames", drift.stdout)

            validation = runner.command_output(
                [
                    str(host_java),
                    "-jar",
                    str(jar),
                    "--profile=current",
                    f"--validate-fixture-config={config_path}",
                ],
                check=False,
            )
            self.assertEqual(0, validation.returncode, validation.stdout)

            capture = runner.command_output(
                [
                    str(host_java),
                    "-jar",
                    str(jar),
                    "--profile=current",
                    f"--fixture-config={config_path}",
                    f"--output={run_dir / 'capture.json'}",
                    f"--jfr={run_dir / 'capture.jfr'}",
                ],
                check=False,
            )
            self.assertNotEqual(0, capture.returncode, capture.stdout)
            self.assertIn("sanitized production-shape capture is blocked", capture.stdout)


@unittest.skipUnless(
    os.environ.get("RUN_CQRS_DOCKER_SCENARIO_PROXY_VALIDATION") == "1",
    "set RUN_CQRS_DOCKER_SCENARIO_PROXY_VALIDATION=1 to run the disposable scenario-seed proxy validation",
)
class ScenarioSeedProxyDockerValidationTest(unittest.TestCase):
    def test_real_postgres_scenario_seed_proxy_preserves_selected_field_payload_contract(self) -> None:
        host_java_home, _ = runner.resolve_host_jdk21()
        with tempfile.TemporaryDirectory(prefix="payload-contract-", dir=runner.DEFAULT_OUTPUT_ROOT) as temporary:
            run_dir = Path(temporary)
            for directory in (run_dir / "raw", run_dir / "jfr", run_dir / "logs"):
                directory.mkdir(parents=True)
            images = runner.ensure_docker_and_images()
            jar = runner.build_baseline_jar(run_dir, host_java_home)
            run_id = f"payload-contract-{os.getpid()}-{time.time_ns()}"
            network = runner.docker_name(run_id, "network")
            network_created = False
            try:
                runner.command_output(["docker", "network", "create", *runner.docker_label_args(run_id), network])
                network_created = True
                current = runner.run_probe(
                    jar=jar,
                    run_dir=run_dir,
                    network=network,
                    run_id=run_id,
                    profile="current",
                    sample=1,
                    base_rows=1,
                    images=images,
                )
                cold = runner.run_probe(
                    jar=jar,
                    run_dir=run_dir,
                    network=network,
                    run_id=run_id,
                    profile="cold10x",
                    sample=1,
                    base_rows=1,
                    images=images,
                )
            finally:
                if network_created:
                    runner.command_output(["docker", "network", "rm", network], check=False)

            current_history = current["rows"]["loaderInputs"]["systemHistoryLogs"]
            cold_history = cold["rows"]["loaderInputs"]["systemHistoryLogs"]
            self.assertGreater(current_history["sourceRows"], 0)
            self.assertEqual(0, current_history["retainedItems"])
            self.assertGreater(current_history["payloadBytes"], 0)
            self.assertEqual(current_history["sourceRows"] + 9, cold_history["sourceRows"])
            self.assertEqual(0, cold_history["retainedItems"])
            self.assertEqual(current_history["payloadBytes"] + 9 * 192, cold_history["payloadBytes"])

            fixture = current["fixture"]
            self.assertEqual("synthetic-scenario-seed-proxy", fixture["kind"])
            self.assertEqual("selected-loader-fields-postgres-text-bytes.v1", fixture["payloadByteSemantics"])
            observation = fixture["loaderInputObservation"]["inputs"]
            expected_entity_columns = {
                "nations": ["id", "name", "color", "capital_city_id", "gold", "rice", "tech", "power", "level", "type_code", "meta"],
                "cities": ["id", "name", "nation_id", "level", "state", "supply_state", "front_state", "pop", "pop_max", "dead", "agri", "agri_max", "comm", "comm_max", "secu", "secu_max", "trust", "def", "def_max", "wall", "wall_max", "trade", "region", "term", "officer_set", "conflict", "meta"],
                "generals": ["id", "name", "nation_id", "city_id", "troop_id", "npc_state", "affinity", "leadership", "strength", "intel", "politics", "charm", "experience", "dedication", "officer_level", "injury", "gold", "rice", "crew", "crew_type_id", "train", "atmos", "age", "weapon_code", "book_code", "horse_code", "item_code", "turn_time", "recent_war_time", "user_id", "born_year", "dead_year", "picture", "image_server", "start_age", "personal_code", "special_code", "special2_code", "officer_city", "last_turn", "penalty", "meta"],
                "diplomacy": ["src_nation_id", "dest_nation_id", "state_code", "term", "is_dead", "meta"],
            }
            for input_id, columns in expected_entity_columns.items():
                self.assertEqual(columns, observation[input_id]["loaderColumns"])
                self.assertEqual(columns, observation[input_id]["payloadColumns"])
                metrics = current["rows"]["loaderInputs"][input_id]
                self.assertEqual(metrics["sourceRows"], metrics["retainedItems"])
                self.assertGreater(metrics["sourceRows"], 0)
                self.assertGreater(metrics["payloadBytes"], 0)


if __name__ == "__main__":
    unittest.main()
