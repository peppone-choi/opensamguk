import copy
import importlib.util
import json
import os
import subprocess
import sys
import tempfile
import time
import unittest
from pathlib import Path
from unittest import mock


RUNNER_PATH = Path(__file__).with_name("run-runtime-baseline.py")
RUNNER_SPEC = importlib.util.spec_from_file_location("cqrs_runtime_baseline", RUNNER_PATH)
if RUNNER_SPEC is None or RUNNER_SPEC.loader is None:
    raise RuntimeError(f"Unable to load {RUNNER_PATH}")
runner = importlib.util.module_from_spec(RUNNER_SPEC)
sys.modules[RUNNER_SPEC.name] = runner
RUNNER_SPEC.loader.exec_module(runner)


def make_record(profile: str, multiplier: int, *, base_rows: int = 10_000, hot_rows: int = 256) -> dict:
    return {
        "schemaVersion": "cqrs-runtime-baseline.raw.v2",
        "profile": profile,
        "fixture": {
            "baseRows": base_rows,
            "fixedHotLogRows": hot_rows,
            "logPayloadCharacters": 192,
            "coldHistoryMultiplier": multiplier,
            "coldHistoryRows": base_rows * multiplier,
            "sha256": f"{profile}-{multiplier}",
        },
        "cgroup": {"memoryLimitBytes": runner.REQUIRED_CGROUP_BYTES},
        "jvm": {
            "version": "21.0.11+10-LTS",
            "inputArguments": list(runner.REQUIRED_JVM_ARGS),
        },
        "memory": {
            "rssBeforeGcBytes": 2,
            "rssAfterGcBytes": 1,
            "heapBeforeGc": {"usedBytes": 3, "committedBytes": 4},
            "heapAfterGc": {"usedBytes": 1, "committedBytes": 4},
        },
        "gc": {"collectionTimeDeltaMillis": 1},
        "durations": {"bootDurationMs": 1, "snapshotDurationMs": 1, "tickDurationMs": 1},
        "rows": {
            "database": {
                "world_state": 1,
                "general": 1,
                "city": 0,
                "nation": 0,
                "diplomacy": 0,
                "rank_data": 0,
                "log_entry": hot_rows + base_rows * multiplier,
            },
            "snapshot": {
                "generals": 1,
                "cities": 0,
                "nations": 0,
                "diplomacy": 0,
                "accessLogs": 0,
                "globalLogs": hot_rows + base_rows * multiplier,
                "nationHistoryEntries": 0,
                "generalHistoryEntries": 0,
            },
            "loaderInputs": {},
        },
        "artifacts": {"jfrConfiguration": "profile"},
        "images": {
            "probeTag": "eclipse-temurin:21-jre",
            "probeId": "sha256:probe",
            "postgresTag": "postgres:16-alpine",
            "postgresId": "sha256:postgres",
        },
    }


def make_records() -> dict[str, list[dict]]:
    return {
        "current": [make_record("current", 1) for _ in range(3)],
        "cold10x": [make_record("cold10x", 10) for _ in range(3)],
    }


def gc_phase_pause_event(gc_id: int, duration: str) -> dict:
    return {
        "type": "jdk.GCPhasePause",
        "values": {"gcId": gc_id, "duration": duration},
    }


def garbage_collection_event(gc_id: int, cause: str) -> dict:
    return {
        "type": "jdk.GarbageCollection",
        "values": {"gcId": gc_id, "cause": cause},
    }


def sample_analysis(profile: str, operational: list[float], forced: list[float]) -> dict:
    return {
        "profile": profile,
        "jfrGcPhasePause": {
            "operational": runner.gc_phase_pause_metrics(operational),
            "forcedRetainedHeapProbe": runner.gc_phase_pause_metrics(forced),
        },
        "jfrGcPhasePauseDurationsMillis": {
            "operational": operational,
            "forcedRetainedHeapProbe": forced,
        },
    }


JFR_SUMMARY_OUTPUT = (
    " Event Type                              Count  Size (bytes)\n"
    "=============================================================\n"
    " jdk.GCPhasePause                           20           486\n"
    " jdk.GarbageCollection                      18           423\n"
    " jdk.ObjectAllocationSample                930         14142\n"
)


def jfr_pause_output() -> str:
    return json.dumps(
        {
            "recording": {
                "events": [
                    garbage_collection_event(1, "G1 Evacuation Pause"),
                    garbage_collection_event(2, "System.gc()"),
                    gc_phase_pause_event(1, "PT0.001S"),
                    gc_phase_pause_event(2, "PT0.002S"),
                ]
            }
        }
    )


def write_analysis_fixture(root: Path) -> Path:
    run_directory = root / "analysis-run"
    raw_directory = run_directory / "raw"
    jfr_directory = run_directory / "jfr"
    (run_directory / "logs").mkdir(parents=True)
    raw_directory.mkdir()
    jfr_directory.mkdir()
    for profile in runner.PROFILES:
        multiplier = 1 if profile == "current" else 10
        for index in range(1, runner.SAMPLES_PER_PROFILE + 1):
            stem = f"{profile}-{index}"
            raw = make_record(profile, multiplier)
            raw["artifacts"]["jfrFile"] = f"{stem}.jfr"
            (raw_directory / f"{stem}.json").write_text(
                json.dumps(raw, indent=2) + "\n",
                encoding="utf-8",
            )
            (jfr_directory / f"{stem}.jfr").write_bytes(f"JFR source {stem}".encode("utf-8"))
    return run_directory


def source_hashes(run_directory: Path) -> dict[str, str]:
    paths = sorted((run_directory / "raw").glob("*.json")) + sorted((run_directory / "jfr").glob("*.jfr"))
    return {str(path.relative_to(run_directory)): runner.file_sha256(path) for path in paths}


def derived_artifact_paths(run_directory: Path) -> set[str]:
    candidates = [
        run_directory / "analysis.json",
        run_directory / "summary.json",
        run_directory / "summary.md",
    ]
    candidates.extend(
        path
        for stem in runner.expected_sample_stems()
        for path in (
            run_directory / "logs" / f"{stem}.jfr-summary.txt",
            run_directory / "logs" / f"{stem}.jfr-gc-phase-pause.json",
        )
    )
    return {
        str(path.relative_to(run_directory))
        for path in candidates
        if path.exists() or path.is_symlink()
    }


class RuntimeBaselineRunnerTest(unittest.TestCase):
    def test_java_version_parsing_handles_runtime_and_openjdk_forms(self) -> None:
        self.assertEqual(21, runner.java_major("21.0.11+10-LTS"))
        self.assertEqual(21, runner.java_major('openjdk version "21.0.11"'))
        self.assertIsNone(runner.java_major("not-java"))

    def test_percentile_uses_linear_interpolation(self) -> None:
        self.assertEqual(20.0, runner.percentile([10.0, 20.0, 40.0], 0.50))
        self.assertEqual(38.0, runner.percentile([10.0, 20.0, 40.0], 0.95))

    def test_fixture_contract_requires_fixed_hot_base_and_exact_tenfold_cold_rows(self) -> None:
        records = make_records()
        self.assertEqual(10, runner.fixture_contract(records)["coldHistoryRatio"])

        bad_hot = copy.deepcopy(records)
        bad_hot["cold10x"][0]["fixture"]["fixedHotLogRows"] = 257
        with self.assertRaises(runner.RunnerFailure):
            runner.fixture_contract(bad_hot)

        bad_base = copy.deepcopy(records)
        bad_base["cold10x"][0]["fixture"]["baseRows"] = 9_999
        with self.assertRaises(runner.RunnerFailure):
            runner.fixture_contract(bad_base)

        bad_cold_rows = copy.deepcopy(records)
        for sample in bad_cold_rows["cold10x"]:
            sample["fixture"]["coldHistoryRows"] = 99_999
        with self.assertRaises(runner.RunnerFailure):
            runner.fixture_contract(bad_cold_rows)

    def test_image_contract_rejects_mixed_image_identity(self) -> None:
        records = make_records()
        records["cold10x"][2]["images"]["probeId"] = "sha256:other"
        with self.assertRaises(runner.RunnerFailure):
            runner.image_contract(records)

    def test_raw_validation_requires_current_schema_and_profile_jfr(self) -> None:
        raw = make_record("current", 1)
        runner.validate_raw(raw, "current")

        wrong_schema = copy.deepcopy(raw)
        wrong_schema["schemaVersion"] = "cqrs-runtime-baseline.raw.v1"
        with self.assertRaises(runner.RunnerFailure):
            runner.validate_raw(wrong_schema, "current")

        wrong_jfr = copy.deepcopy(raw)
        wrong_jfr["artifacts"]["jfrConfiguration"] = "default"
        with self.assertRaises(runner.RunnerFailure):
            runner.validate_raw(wrong_jfr, "current")

    def test_output_scope_run_id_name_and_ownership_contracts(self) -> None:
        self.assertEqual(
            (runner.ENGINE_DIR / "build" / "cqrs-runtime-baseline").resolve(),
            runner.require_within_build(runner.ENGINE_DIR / "build" / "cqrs-runtime-baseline"),
        )
        with self.assertRaises(runner.RunnerFailure):
            runner.require_within_build(runner.REPOSITORY_ROOT / "outside-build")
        self.assertEqual("valid_1-run", runner.validate_run_id("valid_1-run"))
        with self.assertRaises(runner.RunnerFailure):
            runner.validate_run_id("../invalid")

        first = "a" * 63 + "1"
        second = "a" * 63 + "2"
        first_name = runner.docker_name(first, "current-1-probe")
        second_name = runner.docker_name(second, "current-1-probe")
        self.assertNotEqual(first_name, second_name)
        self.assertLessEqual(len(first_name), 63)
        self.assertTrue(runner.label_value_belongs_to_run("run-a", "run-a"))
        self.assertFalse(runner.label_value_belongs_to_run("foreign-run", "run-a"))

    def test_classifier_validation_rejects_production_lib_collision(self) -> None:
        original_baseline_directory = runner.BASELINE_JAR_DIRECTORY
        original_baseline_path = runner.BASELINE_JAR_PATH
        original_production_directory = runner.PRODUCTION_JAR_DIRECTORY
        try:
            with tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                runner.BASELINE_JAR_DIRECTORY = root / "baseline-jars"
                runner.BASELINE_JAR_PATH = runner.BASELINE_JAR_DIRECTORY / "game-engine-cqrs-baseline.jar"
                runner.PRODUCTION_JAR_DIRECTORY = root / "libs"
                runner.BASELINE_JAR_DIRECTORY.mkdir()
                runner.PRODUCTION_JAR_DIRECTORY.mkdir()
                classifier = runner.BASELINE_JAR_PATH
                classifier.touch()
                runner.validate_classifier_jar(classifier)
                (runner.BASELINE_JAR_DIRECTORY / "obsolete-cqrs-baseline.jar").touch()
                with self.assertRaises(runner.RunnerFailure):
                    runner.validate_classifier_jar(classifier)
                (runner.BASELINE_JAR_DIRECTORY / "obsolete-cqrs-baseline.jar").unlink()
                (runner.PRODUCTION_JAR_DIRECTORY / "game-engine-cqrs-baseline.jar").touch()
                with self.assertRaises(runner.RunnerFailure):
                    runner.validate_classifier_jar(classifier)
        finally:
            runner.BASELINE_JAR_DIRECTORY = original_baseline_directory
            runner.BASELINE_JAR_PATH = original_baseline_path
            runner.PRODUCTION_JAR_DIRECTORY = original_production_directory

    def test_gradle_output_and_classifier_freshness_contract(self) -> None:
        runner.require_gradle_success("BUILD SUCCESSFUL")
        with self.assertRaises(runner.RunnerFailure):
            runner.require_gradle_success("BUILD FAILED")
        with tempfile.TemporaryDirectory() as temporary:
            classifier = Path(temporary) / "game-engine-cqrs-baseline.jar"
            started_at = time.time_ns()
            classifier.write_bytes(b"classifier")
            digest = runner.validate_fresh_classifier_jar(classifier, started_at)
            self.assertEqual(runner.file_sha256(classifier), digest)
            stale_time = started_at - 1
            os.utime(classifier, ns=(stale_time, stale_time))
            with self.assertRaises(runner.RunnerFailure):
                runner.validate_fresh_classifier_jar(classifier, started_at)

    def test_jfr_summary_requires_profile_event_evidence(self) -> None:
        summary = (
            " Event Type                              Count  Size (bytes)\n"
            "=============================================================\n"
            " jdk.GCPhasePause                           20           486\n"
            " jdk.GarbageCollection                      18           423\n"
            " jdk.ObjectAllocationSample                930         14142\n"
        )
        counts = runner.validate_jfr_summary(summary)
        self.assertEqual(20, counts["jdk.GCPhasePause"])

        missing = summary.replace(" jdk.ObjectAllocationSample                930         14142\n", "")
        with self.assertRaises(runner.RunnerFailure):
            runner.validate_jfr_summary(missing)

    def test_iso_duration_parser_and_gc_pause_cause_mapping(self) -> None:
        self.assertEqual(1.0, runner.parse_iso_duration_millis("PT0.001S"))
        self.assertEqual(62_500.0, runner.parse_iso_duration_millis("PT1M2.5S"))
        with self.assertRaises(runner.RunnerFailure):
            runner.parse_iso_duration_millis("P1Y")

        output = json.dumps(
            {
                "recording": {
                    "events": [
                        gc_phase_pause_event(1, "PT0.002S"),
                        garbage_collection_event(2, "System.gc()"),
                        garbage_collection_event(1, "G1 Evacuation Pause"),
                        gc_phase_pause_event(2, "PT0.005S"),
                    ]
                }
            }
        )
        durations = runner.jfr_gc_phase_pause_durations_by_cause(output)
        self.assertEqual([2.0], durations["operational"])
        self.assertEqual([5.0], durations["forcedRetainedHeapProbe"])

    def test_gc_pause_analysis_rejects_empty_and_malformed_jfr_events(self) -> None:
        empty = json.dumps({"recording": {"events": []}})
        durations = runner.jfr_gc_phase_pause_durations_by_cause(empty)
        with self.assertRaises(runner.RunnerFailure):
            runner.gc_phase_pause_metrics(durations["operational"])

        malformed = json.dumps(
            {
                "recording": {
                    "events": [
                        garbage_collection_event(1, "G1 Evacuation Pause"),
                        gc_phase_pause_event(1, "not-an-iso-duration"),
                    ]
                }
            }
        )
        with self.assertRaises(runner.RunnerFailure):
            runner.jfr_gc_phase_pause_durations_by_cause(malformed)

    def test_summary_reports_operational_and_forced_gc_pause_metrics(self) -> None:
        records = make_records()
        analysis = {
            "current-1": sample_analysis("current", [1.0, 2.0], [10.0]),
            "current-2": sample_analysis("current", [3.0], [20.0]),
            "current-3": sample_analysis("current", [4.0], [30.0]),
            "cold10x-1": sample_analysis("cold10x", [5.0], [40.0]),
            "cold10x-2": sample_analysis("cold10x", [6.0], [50.0]),
            "cold10x-3": sample_analysis("cold10x", [7.0], [60.0]),
        }
        summary = runner.build_summary(records, analysis)
        current_pause = summary["profiles"]["current"]["metrics"]["jfrGcPhasePause"]
        operational = current_pause["operational"]
        forced = current_pause["forcedRetainedHeapProbe"]
        self.assertEqual(4, operational["eventCount"])
        self.assertEqual(4.0, operational["maxDurationMillis"])
        self.assertAlmostEqual(2.5, operational["p50DurationMillis"])
        self.assertAlmostEqual(3.85, operational["p95DurationMillis"])
        self.assertEqual(3, forced["eventCount"])
        self.assertEqual(30.0, forced["maxDurationMillis"])

        probe = summary["probe"]
        self.assertEqual(
            "linear interpolation over sorted three-sample values",
            probe["threeRunMetricPercentileMethod"],
        )
        self.assertEqual(
            "linear interpolation over pooled per-event pause durations within each profile; event-weighted",
            probe["jfrGcPhasePausePercentileMethod"],
        )
        self.assertEqual(
            {
                "scope": "harnessSetupAndBoot",
                "includes": [
                    "freshPostgresFlywayMigration",
                    "scenarioSeed",
                    "profileFixtureInsert",
                    "worldSnapshotLoad",
                    "inMemoryTurnWorldConstruction",
                ],
            },
            probe["bootDurationMetric"],
        )
        self.assertIn("Harness setup+boot p50 / p95 ms", runner.summary_markdown(summary))

    def test_summary_aggregates_required_memory_and_loaded_row_evidence(self) -> None:
        records = make_records()
        analysis = {
            f"{profile}-{index}": sample_analysis(profile, [float(index)], [float(index + 10)])
            for profile in runner.PROFILES
            for index in range(1, runner.SAMPLES_PER_PROFILE + 1)
        }

        for profile in runner.PROFILES:
            for index, sample in enumerate(records[profile], start=1):
                sample["memory"] = {
                    "rssBeforeGcBytes": 100 + index,
                    "rssAfterGcBytes": 90 + index,
                    "heapBeforeGc": {"usedBytes": 80 + index, "committedBytes": 120},
                    "heapAfterGc": {"usedBytes": 70 + index, "committedBytes": 120},
                }

        summary = runner.build_summary(records, analysis)
        metrics = summary["profiles"]["current"]["metrics"]
        loaded_rows = summary["profiles"]["current"]["loadedRows"]

        self.assertEqual(102.9, metrics["rssBeforeGcBytes"]["p95"])
        self.assertEqual(92.9, metrics["rssAfterGcBytes"]["p95"])
        self.assertEqual(120.0, metrics["heapCommittedBeforeGcBytes"]["mean"])
        self.assertEqual(72.0, metrics["heapUsedAfterGcBytes"]["p50"])
        self.assertEqual(2.0, metrics["heapUsedAfterGcBytes"]["runToRunSpread"])
        self.assertEqual(1.0, loaded_rows["database"]["general"]["mean"])
        self.assertEqual(0.0, loaded_rows["snapshot"]["cities"]["runToRunSpread"])

    def test_cleanup_container_only_removes_container_owned_by_run(self) -> None:
        with mock.patch.object(runner, "container_run_label", return_value="foreign-run"), mock.patch.object(
            runner, "command_output"
        ) as command:
            runner.cleanup_container("foreign-container", "owned-run")
            command.assert_not_called()

        with mock.patch.object(runner, "container_run_label", return_value="owned-run"), mock.patch.object(
            runner, "command_output"
        ) as command:
            runner.cleanup_container("owned-container", "owned-run")
            command.assert_called_once_with(["docker", "rm", "-f", "owned-container"], check=False)

    def test_analyze_run_artifacts_writes_confined_outputs_without_mutating_sources(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            run_directory = write_analysis_fixture(Path(temporary))
            before = source_hashes(run_directory)

            def fake_jfr_command(command: list[str], **_: object) -> subprocess.CompletedProcess[str]:
                if command[1] == "summary":
                    return subprocess.CompletedProcess(command, 0, JFR_SUMMARY_OUTPUT)
                if command[1] == "print":
                    return subprocess.CompletedProcess(command, 0, jfr_pause_output())
                self.fail(f"unexpected JFR command: {command}")

            with mock.patch.object(runner, "command_output", side_effect=fake_jfr_command) as command:
                records, analysis = runner.analyze_run_artifacts(run_directory, Path("/fixture-jdk/bin/jfr"))

            self.assertEqual(3, len(records["current"]))
            self.assertEqual(3, len(records["cold10x"]))
            self.assertEqual(set(runner.expected_sample_stems()), set(analysis))
            self.assertEqual(12, command.call_count)
            self.assertEqual(before, source_hashes(run_directory))
            self.assertEqual(
                {
                    "analysis.json",
                    "summary.json",
                    "summary.md",
                    *{
                        f"logs/{stem}.{suffix}"
                        for stem in runner.expected_sample_stems()
                        for suffix in ("jfr-summary.txt", "jfr-gc-phase-pause.json")
                    },
                },
                derived_artifact_paths(run_directory),
            )
            self.assertTrue((run_directory / "analysis.json").is_file())
            self.assertTrue((run_directory / "summary.json").is_file())
            self.assertTrue((run_directory / "summary.md").is_file())
            for stem in runner.expected_sample_stems():
                self.assertTrue((run_directory / "logs" / f"{stem}.jfr-summary.txt").is_file())
                self.assertTrue((run_directory / "logs" / f"{stem}.jfr-gc-phase-pause.json").is_file())
            self.assertFalse(list(run_directory.rglob(".*.tmp")))

    def test_analyze_run_artifacts_rejects_raw_source_symlink_before_any_write(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            run_directory = write_analysis_fixture(root)
            outside = root / "outside-raw.json"
            outside.write_bytes(b"outside raw source")
            raw_path = run_directory / "raw" / "current-1.json"
            raw_path.unlink()
            raw_path.symlink_to(outside)
            remaining_sources = source_hashes(run_directory)

            with mock.patch.object(runner, "command_output", side_effect=AssertionError("JFR must not run")) as command:
                with self.assertRaises(runner.RunnerFailure):
                    runner.analyze_run_artifacts(run_directory, Path("/fixture-jdk/bin/jfr"))

            command.assert_not_called()
            self.assertEqual(b"outside raw source", outside.read_bytes())
            self.assertEqual(remaining_sources, source_hashes(run_directory))
            self.assertEqual(set(), derived_artifact_paths(run_directory))

    def test_analyze_run_artifacts_rejects_jfr_source_symlink_before_any_write(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            run_directory = write_analysis_fixture(root)
            outside = root / "outside.jfr"
            outside.write_bytes(b"outside JFR source")
            jfr_path = run_directory / "jfr" / "current-1.jfr"
            jfr_path.unlink()
            jfr_path.symlink_to(outside)
            before = source_hashes(run_directory)

            with mock.patch.object(runner, "command_output", side_effect=AssertionError("JFR must not run")) as command:
                with self.assertRaises(runner.RunnerFailure):
                    runner.analyze_run_artifacts(run_directory, Path("/fixture-jdk/bin/jfr"))

            command.assert_not_called()
            self.assertEqual(b"outside JFR source", outside.read_bytes())
            self.assertEqual(before, source_hashes(run_directory))
            self.assertEqual(set(), derived_artifact_paths(run_directory))

    def test_analyze_run_artifacts_rejects_symlinked_logs_directory_before_any_write(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            run_directory = write_analysis_fixture(root)
            logs_directory = run_directory / "logs"
            outside_logs = root / "outside-logs"
            outside_logs.mkdir()
            logs_directory.rmdir()
            logs_directory.symlink_to(outside_logs, target_is_directory=True)
            before = source_hashes(run_directory)

            with mock.patch.object(runner, "command_output", side_effect=AssertionError("JFR must not run")) as command:
                with self.assertRaises(runner.RunnerFailure):
                    runner.analyze_run_artifacts(run_directory, Path("/fixture-jdk/bin/jfr"))

            command.assert_not_called()
            self.assertEqual(before, source_hashes(run_directory))
            self.assertEqual([], list(outside_logs.iterdir()))
            self.assertEqual(set(), derived_artifact_paths(run_directory))

    def test_analyze_run_artifacts_rejects_derived_target_symlink_before_any_write(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            run_directory = write_analysis_fixture(root)
            outside = root / "outside-analysis.json"
            outside.write_text("outside must remain unchanged\n", encoding="utf-8")
            (run_directory / "analysis.json").symlink_to(outside)
            before = source_hashes(run_directory)

            with mock.patch.object(runner, "command_output", side_effect=AssertionError("JFR must not run")) as command:
                with self.assertRaises(runner.RunnerFailure):
                    runner.analyze_run_artifacts(run_directory, Path("/fixture-jdk/bin/jfr"))

            command.assert_not_called()
            self.assertEqual(before, source_hashes(run_directory))
            self.assertEqual("outside must remain unchanged\n", outside.read_text(encoding="utf-8"))
            self.assertTrue((run_directory / "analysis.json").is_symlink())
            self.assertEqual({"analysis.json"}, derived_artifact_paths(run_directory))

    def test_analyze_run_artifacts_rejects_in_run_derived_target_symlink_before_any_write(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            run_directory = write_analysis_fixture(root)
            stale_target = run_directory / "stale-analysis.json"
            stale_target.write_text("stale but protected\n", encoding="utf-8")
            (run_directory / "analysis.json").symlink_to(stale_target)

            with mock.patch.object(runner, "command_output", side_effect=AssertionError("JFR must not run")) as command:
                with self.assertRaises(runner.RunnerFailure):
                    runner.analyze_run_artifacts(run_directory, Path("/fixture-jdk/bin/jfr"))

            command.assert_not_called()
            self.assertEqual("stale but protected\n", stale_target.read_text(encoding="utf-8"))
            self.assertTrue((run_directory / "analysis.json").is_symlink())
            self.assertEqual({"analysis.json"}, derived_artifact_paths(run_directory))

    def test_main_analyze_mode_dispatches_without_capture_processes(self) -> None:
        build_root = runner.ENGINE_DIR / "build"
        build_root.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir=build_root) as temporary:
            output_root = Path(temporary)
            run_id = "analysis-only-fixture"
            run_dir = output_root / run_id
            raw_path = run_dir / "raw" / "source.json"
            jfr_path = run_dir / "jfr" / "source.jfr"
            logs_path = run_dir / "logs"
            raw_path.parent.mkdir(parents=True)
            jfr_path.parent.mkdir(parents=True)
            logs_path.mkdir()
            raw_path.write_bytes(b'{"raw":"immutable"}\n')
            jfr_path.write_bytes(b"immutable-jfr")
            jdk_home = output_root / "jdk21"
            jfr_tool = jdk_home / "bin" / "jfr"
            jfr_tool.parent.mkdir(parents=True)
            jfr_tool.write_bytes(b"not-executed")
            calls: list[tuple[Path, Path]] = []

            def fake_analyze(received_run_dir: Path, received_jfr_tool: Path) -> tuple[dict, dict]:
                calls.append((received_run_dir, received_jfr_tool))
                self.assertEqual(run_dir.resolve(), received_run_dir)
                self.assertEqual(jfr_tool, received_jfr_tool)
                return {}, {}

            def capture_path_must_not_run(*args: object, **kwargs: object) -> None:
                self.fail("analyze-only mode reached a Gradle, Docker, probe, or JFR process path")

            arguments = [
                "run-runtime-baseline.py",
                "--output-dir",
                str(output_root),
                "--analyze-run-id",
                run_id,
            ]
            with (
                mock.patch.object(sys, "argv", arguments),
                mock.patch.object(
                    runner,
                    "resolve_host_jdk21",
                    return_value=(jdk_home, jdk_home / "bin" / "java"),
                ),
                mock.patch.object(runner, "analyze_run_artifacts", side_effect=fake_analyze),
                mock.patch.object(runner, "ensure_docker_and_images", side_effect=capture_path_must_not_run),
                mock.patch.object(runner, "build_baseline_jar", side_effect=capture_path_must_not_run),
                mock.patch.object(runner, "run_probe", side_effect=capture_path_must_not_run),
                mock.patch.object(runner, "command_output", side_effect=capture_path_must_not_run),
            ):
                self.assertEqual(0, runner.main())
                self.assertEqual(0, runner.main())

            self.assertEqual(
                [(run_dir.resolve(), jfr_tool), (run_dir.resolve(), jfr_tool)],
                calls,
            )

    def test_main_blocks_production_shape_capture_before_resolve_build_or_docker(self) -> None:
        arguments = runner.argparse.Namespace(
            base_rows=None,
            run_id=None,
            analyze_run_id=None,
            production_shape_manifest=Path("/pending/sanitized-shape.json"),
            validate_production_shape_manifest=None,
            output_dir=runner.DEFAULT_OUTPUT_ROOT,
        )

        def capture_path_must_not_run(*args: object, **kwargs: object) -> None:
            self.fail("production-shape capture reached manifest loading, host resolution, build, Docker, or probe execution")

        with (
            mock.patch.object(runner, "parse_args", return_value=arguments),
            mock.patch.object(runner, "load_validated_production_shape_manifest", side_effect=capture_path_must_not_run),
            mock.patch.object(runner, "resolve_host_jdk21", side_effect=capture_path_must_not_run),
            mock.patch.object(runner, "build_baseline_jar", side_effect=capture_path_must_not_run),
            mock.patch.object(runner, "ensure_docker_and_images", side_effect=capture_path_must_not_run),
            mock.patch.object(runner, "run_probe", side_effect=capture_path_must_not_run),
            mock.patch.object(runner, "command_output", side_effect=capture_path_must_not_run),
        ):
            with self.assertRaisesRegex(runner.RunnerFailure, "production-shape capture is blocked"):
                runner.main()

    def test_main_rejects_infeasible_local_policy_before_output_jdk_build_or_docker(self) -> None:
        policy_document = getattr(runner, "local_sanitized_aggregate_policy_document", None)
        policy_sha256 = getattr(runner, "local_sanitized_aggregate_policy_sha256", None)
        self.assertIsNotNone(policy_document, "runner must expose its deterministic local policy document")
        self.assertIsNotNone(policy_sha256, "runner must seal a local policy deterministically")

        build_root = runner.ENGINE_DIR / "build"
        build_root.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir=build_root) as temporary:
            root = Path(temporary)
            output_root = root / "cqrs-runtime-baseline"
            output_root.mkdir()
            policy = policy_document()
            for profile in ("current", "cold10x"):
                policy["fixture"]["profiles"][profile]["loaderInputs"]["systemActionLogs"]["payloadBytes"] = 0
            policy["sha256"] = policy_sha256(policy)
            policy_path = root / "infeasible-local-policy.json"
            policy_path.write_text(json.dumps(policy), encoding="utf-8")

            arguments = [
                "run-runtime-baseline.py",
                "--local-sanitized-aggregate-policy",
                str(policy_path),
                "--run-id",
                "infeasible-local-policy",
                "--output-dir",
                str(output_root),
            ]
            with (
                mock.patch.object(sys, "argv", arguments),
                mock.patch.object(runner, "resolve_host_jdk21") as mocked_jdk,
                mock.patch.object(runner, "build_baseline_jar") as mocked_build,
                mock.patch.object(runner, "ensure_docker_and_images") as mocked_docker,
            ):
                with self.assertRaises(runner.RunnerFailure):
                    runner.main()

            self.assertFalse((output_root / "infeasible-local-policy").exists())
            mocked_jdk.assert_not_called()
            mocked_build.assert_not_called()
            mocked_docker.assert_not_called()

    def test_main_rejects_resealed_local_policy_not_equal_to_checked_in_policy_before_output_jdk_build_or_docker(self) -> None:
        policy_document = getattr(runner, "local_sanitized_aggregate_policy_document", None)
        policy_sha256 = getattr(runner, "local_sanitized_aggregate_policy_sha256", None)
        self.assertIsNotNone(policy_document, "runner must expose its checked-in deterministic local policy document")
        self.assertIsNotNone(policy_sha256, "runner must seal a local policy deterministically")

        build_root = runner.ENGINE_DIR / "build"
        build_root.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir=build_root) as temporary:
            root = Path(temporary)
            output_root = root / "cqrs-runtime-baseline"
            output_root.mkdir()
            policy = copy.deepcopy(policy_document())
            policy["source"]["policyId"] = "op123-local-sanitized-aggregate-v999"
            policy["sha256"] = policy_sha256(policy)
            policy_path = root / "resealed-but-unchecked-local-policy.json"
            policy_path.write_text(json.dumps(policy), encoding="utf-8")

            arguments = [
                "run-runtime-baseline.py",
                "--local-sanitized-aggregate-policy",
                str(policy_path),
                "--run-id",
                "resealed-but-unchecked-local-policy",
                "--output-dir",
                str(output_root),
            ]
            with (
                mock.patch.object(sys, "argv", arguments),
                mock.patch.object(runner, "resolve_host_jdk21") as mocked_jdk,
                mock.patch.object(runner, "build_baseline_jar") as mocked_build,
                mock.patch.object(runner, "ensure_docker_and_images") as mocked_docker,
            ):
                with self.assertRaisesRegex(runner.RunnerFailure, "checked-in local sanitized aggregate policy"):
                    runner.main()

            self.assertFalse((output_root / "resealed-but-unchecked-local-policy").exists())
            mocked_jdk.assert_not_called()
            mocked_build.assert_not_called()
            mocked_docker.assert_not_called()


if __name__ == "__main__":
    unittest.main()
