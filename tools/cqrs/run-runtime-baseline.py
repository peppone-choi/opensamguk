from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import math
import os
import re
import shutil
import stat
import subprocess
import sys
import tempfile
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Mapping, Sequence


MODULE_DIRECTORY = Path(__file__).resolve().parent
if str(MODULE_DIRECTORY) not in sys.path:
    sys.path.insert(0, str(MODULE_DIRECTORY))

from production_shape_manifest import (
    FIXTURE_CONFIG_SCHEMA_VERSION,
    LOCAL_SANITIZED_AGGREGATE_FIXTURE_CONFIG_SCHEMA_VERSION,
    LOCAL_SANITIZED_AGGREGATE_FIXTURE_KIND,
    LOADER_INPUT_INVENTORY_SHA256,
    MAX_CARDINALITY,
    LocalSanitizedAggregatePolicy,
    ManifestValidationError,
    PAYLOAD_BYTE_SEMANTICS,
    ProductionShapeManifest,
    REQUIRED_LOADER_INPUT_IDS,
    REQUIRED_SNAPSHOT_CARDINALITIES,
    REQUIRED_TABLE_CARDINALITIES,
    canonical_manifest_bytes,
    fixture_config_for_manifest as project_fixture_config,
    local_fixture_config_for_policy as project_local_fixture_config,
    local_sanitized_aggregate_policy_sha256 as project_local_policy_sha256,
    load_local_sanitized_aggregate_policy as project_load_local_policy,
    load_production_shape_manifest,
    loader_input_observation_contract,
    required_loader_input_ids as manifest_required_loader_input_ids,
    validate_manifest,
)


REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
ENGINE_DIR = REPOSITORY_ROOT / "app" / "game-engine"
LOCAL_SANITIZED_AGGREGATE_POLICY_PATH = (
    ENGINE_DIR
    / "src/baseline/resources/opensamguk/engine/baseline/local-sanitized-aggregate-policy.json"
)
DEFAULT_OUTPUT_ROOT = ENGINE_DIR / "build" / "cqrs-runtime-baseline"
BASELINE_JAR_DIRECTORY = DEFAULT_OUTPUT_ROOT / "jars"
BASELINE_JAR_PATH = BASELINE_JAR_DIRECTORY / "game-engine-cqrs-baseline.jar"
PRODUCTION_JAR_DIRECTORY = ENGINE_DIR / "build" / "libs"
PROBE_IMAGE = "eclipse-temurin:21-jre"
POSTGRES_IMAGE = "postgres:16-alpine"
POSTGRES_USER = "baseline"
POSTGRES_PASSWORD = "baseline"
POSTGRES_DATABASE = "baseline"
REQUIRED_CGROUP_BYTES = 2 * 1024 * 1024 * 1024
REQUIRED_JVM_ARGS = [
    "-XX:+UseG1GC",
    "-XX:MaxRAMPercentage=60",
    "-XX:InitialRAMPercentage=40",
]
PROFILES = ("current", "cold10x")
SAMPLES_PER_PROFILE = 3
LOCAL_SANITIZED_AGGREGATE_RAW_SCHEMA_VERSION = "cqrs-runtime-baseline.raw.local-sanitized-aggregate.v1"
PRODUCTION_SHAPE_MANIFEST_FILE_NAME = "approved-production-shape-manifest.json"
LOCAL_SANITIZED_AGGREGATE_POLICY_FILE_NAME = "local-sanitized-aggregate-policy.json"
PRODUCTION_SHAPE_TABLE_TO_RAW_FIELD = {
    "worldState": "world_state",
    "city": "city",
    "nation": "nation",
    "general": "general",
    "diplomacy": "diplomacy",
    "rankData": "rank_data",
    "logEntry": "log_entry",
}
RUN_LABEL_KEY = "opensamguk.cqrs-baseline.run"
REQUIRED_JFR_EVENTS = (
    "jdk.GCPhasePause",
    "jdk.GarbageCollection",
    "jdk.ObjectAllocationSample",
)
JFR_GC_PHASE_PAUSE_EVENT = "jdk.GCPhasePause"
JFR_GARBAGE_COLLECTION_EVENT = "jdk.GarbageCollection"
JFR_GC_PAUSE_ANALYSIS_EVENTS = f"{JFR_GC_PHASE_PAUSE_EVENT},{JFR_GARBAGE_COLLECTION_EVENT}"
FORCED_GC_CAUSE = "System.gc()"
ANALYSIS_SCHEMA_VERSION = "cqrs-runtime-baseline.analysis.v1"
ISO_DURATION_PATTERN = re.compile(
    r"^P(?:(?P<days>\d+(?:\.\d+)?)D)?"
    r"(?:T(?:(?P<hours>\d+(?:\.\d+)?)H)?(?:(?P<minutes>\d+(?:\.\d+)?)M)?"
    r"(?:(?P<seconds>\d+(?:\.\d+)?)S)?)?$"
)


class RunnerFailure(RuntimeError):
    pass


@dataclass(frozen=True)
class ImmutableFixtureConfig:
    path: Path
    identity: tuple[int, int, int]
    sha256: str

    def verify(self) -> None:
        if _regular_file_identity(self.path) != self.identity:
            raise RunnerFailure("immutable probe fixture config identity changed during probe execution")
        if file_sha256(self.path) != self.sha256:
            raise RunnerFailure("immutable probe fixture config content changed during probe execution")


def _regular_file_identity(path: Path) -> tuple[int, int, int]:
    try:
        status = path.lstat()
    except OSError as exc:
        raise RunnerFailure("immutable probe fixture config is unavailable") from exc
    if not stat.S_ISREG(status.st_mode):
        raise RunnerFailure("immutable probe fixture config must remain a regular non-symlink file")
    if status.st_mode & 0o222:
        raise RunnerFailure("immutable probe fixture config must not be writable")
    return status.st_dev, status.st_ino, status.st_size


def prepare_immutable_probe_fixture_config(
    source_path: Path,
    profile: str,
    directory: Path,
) -> tuple[Mapping[str, Any], ImmutableFixtureConfig]:
    expected = load_baseline_fixture_config(source_path, profile)
    content = canonical_fixture_config_bytes(expected)
    target = directory / "fixture-config.json"
    try:
        descriptor = os.open(target, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o400)
        with os.fdopen(descriptor, "wb") as fixture_file:
            fixture_file.write(content)
        os.chmod(target, 0o400)
    except OSError as exc:
        raise RunnerFailure("could not create immutable probe fixture config") from exc
    return expected, ImmutableFixtureConfig(
        path=target,
        identity=_regular_file_identity(target),
        sha256=hashlib.sha256(content).hexdigest(),
    )


def validate_production_shape_manifest(value: Mapping[str, Any]) -> ProductionShapeManifest:
    try:
        return validate_manifest(value)
    except ManifestValidationError as exc:
        raise RunnerFailure(str(exc)) from exc


def load_validated_production_shape_manifest(path: Path) -> ProductionShapeManifest:
    try:
        return load_production_shape_manifest(path)
    except ManifestValidationError as exc:
        raise RunnerFailure(str(exc)) from exc


def fixture_config_for_manifest(manifest: ProductionShapeManifest, profile: str) -> dict[str, Any]:
    try:
        return project_fixture_config(manifest, profile)
    except ManifestValidationError as exc:
        raise RunnerFailure(str(exc)) from exc


def required_loader_input_ids() -> tuple[str, ...]:
    return manifest_required_loader_input_ids()


def loader_input_inventory_sha256() -> str:
    return LOADER_INPUT_INVENTORY_SHA256


def local_sanitized_aggregate_policy_document() -> dict[str, Any]:
    if LOCAL_SANITIZED_AGGREGATE_POLICY_PATH.is_symlink() or not LOCAL_SANITIZED_AGGREGATE_POLICY_PATH.is_file():
        raise RunnerFailure("local sanitized aggregate policy must be a regular non-symlink file")
    try:
        value = json.loads(
            LOCAL_SANITIZED_AGGREGATE_POLICY_PATH.read_text(encoding="utf-8"),
            object_pairs_hook=_no_duplicate_json_keys,
        )
    except (OSError, json.JSONDecodeError) as exc:
        raise RunnerFailure("local sanitized aggregate policy is not readable JSON") from exc
    if not isinstance(value, Mapping):
        raise RunnerFailure("local sanitized aggregate policy root must be an object")
    return dict(value)


def local_sanitized_aggregate_policy_sha256(value: Mapping[str, Any]) -> str:
    return project_local_policy_sha256(value)


def load_local_sanitized_aggregate_policy(
    path: Path,
    *,
    value: Mapping[str, Any] | None = None,
) -> LocalSanitizedAggregatePolicy:
    try:
        return project_load_local_policy(path, value=value)
    except ManifestValidationError as exc:
        raise RunnerFailure(str(exc)) from exc


def local_fixture_config_for_policy(policy: LocalSanitizedAggregatePolicy, profile: str) -> dict[str, Any]:
    try:
        return project_local_fixture_config(policy, profile)
    except ManifestValidationError as exc:
        raise RunnerFailure(str(exc)) from exc


def validate_local_sanitized_aggregate_feasibility(policy: LocalSanitizedAggregatePolicy) -> None:
    current = policy.profiles["current"]
    cold = policy.profiles["cold10x"]
    expected_tables = {
        "worldState": 1,
        "city": 0,
        "nation": 0,
        "general": 1,
        "diplomacy": 0,
        "rankData": 0,
    }
    expected_snapshot = {
        "generals": 1,
        "cities": 0,
        "nations": 0,
        "diplomacy": 0,
        "accessLogs": 0,
        "nationHistoryEntries": 0,
        "generalHistoryEntries": 0,
    }
    for profile_name, profile in (("current", current), ("cold10x", cold)):
        if profile.fixed_hot_action_rows != 256:
            raise RunnerFailure(f"{profile_name} local policy must materialize exactly 256 hot action rows")
        if profile.payload_size_bytes != {"hotAction": 192, "coldHistory": 192}:
            raise RunnerFailure(f"{profile_name} local policy must use 192-byte hot and cold payloads")
        for field, expected in expected_tables.items():
            if profile.table_cardinalities[field] != expected:
                raise RunnerFailure(f"{profile_name} local policy cannot materialize table cardinality {field}")
        for field, expected in expected_snapshot.items():
            if profile.snapshot_cardinalities[field] != expected:
                raise RunnerFailure(f"{profile_name} local policy cannot materialize snapshot cardinality {field}")
        if profile.table_cardinalities["logEntry"] != 256 + profile.cold_history_rows:
            raise RunnerFailure(f"{profile_name} local policy logEntry cardinality is not deterministic")
        if profile.snapshot_cardinalities["globalLogs"] != 256 + profile.cold_history_rows:
            raise RunnerFailure(f"{profile_name} local policy globalLogs cardinality is not deterministic")
        expected_metrics = {
            "worldState": (1, 1, 4096),
            "ngGames": (0, 1, 1),
            "systemActionLogs": (256, 256, 256 * 192),
            "systemHistoryLogs": (
                profile.cold_history_rows,
                profile.cold_history_rows,
                profile.cold_history_rows * 192,
            ),
            "generals": (1, 1, 4096),
        }
        for input_id in REQUIRED_LOADER_INPUT_IDS:
            metrics = profile.loader_inputs[input_id]
            expected = expected_metrics.get(input_id, (0, 0, 0))
            actual = (metrics.source_rows, metrics.retained_items, metrics.payload_bytes)
            if actual != expected:
                raise RunnerFailure(f"{profile_name} local policy cannot materialize loader input {input_id}")
    if current.cold_history_rows != 10_000 or cold.cold_history_rows != 100_000:
        raise RunnerFailure("local policy must retain the fixed current and cold10x aggregate sizes")


def write_production_shape_fixture_configs(
    manifest: ProductionShapeManifest,
    fixture_directory: Path,
) -> dict[str, Path]:
    fixture_directory.mkdir(parents=True, exist_ok=False)
    paths: dict[str, Path] = {}
    for profile in PROFILES:
        config = fixture_config_for_manifest(manifest, profile)
        target = fixture_directory / f"{profile}.json"
        temporary = fixture_directory / f".{profile}.tmp"
        temporary.write_bytes(canonical_fixture_config_bytes(config))
        os.replace(temporary, target)
        paths[profile] = target
    return paths


def canonical_fixture_config_bytes(value: Mapping[str, Any]) -> bytes:
    return json.dumps(value, ensure_ascii=True, sort_keys=True, separators=(",", ":")).encode("utf-8") + b"\n"


def write_production_shape_run_contract(
    manifest: ProductionShapeManifest,
    run_directory: Path,
) -> dict[str, Path]:
    manifest_directory = run_directory / "manifest"
    manifest_directory.mkdir(parents=True, exist_ok=False)
    manifest_path = manifest_directory / PRODUCTION_SHAPE_MANIFEST_FILE_NAME
    manifest_path.write_bytes(canonical_manifest_bytes(manifest.canonical_document))
    return {
        "manifest": manifest_path,
        **write_production_shape_fixture_configs(manifest, run_directory / "fixture"),
    }


def write_local_sanitized_aggregate_run_contract(
    policy: LocalSanitizedAggregatePolicy,
    run_directory: Path,
) -> dict[str, Path]:
    manifest_directory = run_directory / "manifest"
    manifest_directory.mkdir(parents=True, exist_ok=False)
    policy_path = manifest_directory / LOCAL_SANITIZED_AGGREGATE_POLICY_FILE_NAME
    policy_path.write_bytes(canonical_fixture_config_bytes(policy.canonical_document))
    fixture_directory = run_directory / "fixture"
    fixture_directory.mkdir(parents=True, exist_ok=False)
    paths: dict[str, Path] = {"manifest": policy_path}
    for profile in PROFILES:
        config = local_fixture_config_for_policy(policy, profile)
        target = fixture_directory / f"{profile}.json"
        target.write_bytes(canonical_fixture_config_bytes(config))
        paths[profile] = target
    return paths


def load_canonical_production_shape_manifest(path: Path) -> ProductionShapeManifest:
    if path.is_symlink() or not path.is_file():
        raise RunnerFailure("recorded production-shape manifest must be a regular non-symlink file")
    try:
        content = path.read_bytes()
        value = json.loads(content.decode("utf-8"), object_pairs_hook=_no_duplicate_json_keys)
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise RunnerFailure("recorded production-shape manifest is not readable canonical JSON") from exc
    if not isinstance(value, Mapping):
        raise RunnerFailure("recorded production-shape manifest root must be an object")
    manifest = validate_production_shape_manifest(value)
    if content != canonical_manifest_bytes(value):
        raise RunnerFailure("recorded production-shape manifest is not canonical JSON")
    return manifest


def load_canonical_local_sanitized_aggregate_policy(path: Path) -> LocalSanitizedAggregatePolicy:
    if path.is_symlink() or not path.is_file():
        raise RunnerFailure("recorded local sanitized aggregate policy must be a regular non-symlink file")
    try:
        content = path.read_bytes()
        value = json.loads(content.decode("utf-8"), object_pairs_hook=_no_duplicate_json_keys)
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise RunnerFailure("recorded local sanitized aggregate policy is not readable canonical JSON") from exc
    if not isinstance(value, Mapping):
        raise RunnerFailure("recorded local sanitized aggregate policy root must be an object")
    policy = load_local_sanitized_aggregate_policy(path, value=value)
    if content != canonical_fixture_config_bytes(value):
        raise RunnerFailure("recorded local sanitized aggregate policy is not canonical JSON")
    validate_local_sanitized_aggregate_feasibility(policy)
    return policy


def load_checked_in_local_sanitized_aggregate_policy() -> LocalSanitizedAggregatePolicy:
    document = local_sanitized_aggregate_policy_document()
    return load_local_sanitized_aggregate_policy(
        LOCAL_SANITIZED_AGGREGATE_POLICY_PATH,
        value=document,
    )


def load_bound_local_sanitized_aggregate_policy(path: Path) -> LocalSanitizedAggregatePolicy:
    policy = load_local_sanitized_aggregate_policy(path)
    checked_in_policy = load_checked_in_local_sanitized_aggregate_policy()
    policy_content = canonical_fixture_config_bytes(policy.canonical_document)
    checked_in_content = canonical_fixture_config_bytes(checked_in_policy.canonical_document)
    if policy_content != checked_in_content or policy.sha256 != checked_in_policy.sha256:
        raise RunnerFailure(
            "local sanitized aggregate policy must exactly match the checked-in local sanitized aggregate policy canonical document and SHA-256"
        )
    return policy


def expected_recorded_fixture_config(
    path: Path,
    manifest: ProductionShapeManifest,
    profile: str,
) -> Mapping[str, Any]:
    expected = fixture_config_for_manifest(manifest, profile)
    try:
        saved = path.read_bytes()
    except OSError as exc:
        raise RunnerFailure("recorded production-shape fixture config could not be read") from exc
    if saved != canonical_fixture_config_bytes(expected):
        raise RunnerFailure("recorded production-shape fixture config does not match the canonical manifest projection")
    return validate_production_shape_fixture_config(expected, profile)


def expected_recorded_local_fixture_config(
    path: Path,
    policy: LocalSanitizedAggregatePolicy,
    profile: str,
) -> Mapping[str, Any]:
    expected = local_fixture_config_for_policy(policy, profile)
    try:
        saved = path.read_bytes()
    except OSError as exc:
        raise RunnerFailure("recorded local sanitized aggregate fixture config could not be read") from exc
    if saved != canonical_fixture_config_bytes(expected):
        raise RunnerFailure("recorded local sanitized aggregate fixture config does not match the canonical policy projection")
    return validate_local_sanitized_aggregate_fixture_config(expected, profile)


PRODUCTION_SHAPE_FIXTURE_CONFIG_FIELDS = frozenset(
    {
        "schemaVersion",
        "kind",
        "manifestSha256",
        "loaderInputInventorySha256",
        "payloadByteSemantics",
        "loaderInputObservation",
        "profile",
        "fixedHotActionRows",
        "coldHistoryRows",
        "payloadSizeBytes",
        "expectedTableCardinalities",
        "expectedSnapshotCardinalities",
        "expectedLoaderInputs",
    }
)

LOCAL_SANITIZED_AGGREGATE_FIXTURE_CONFIG_FIELDS = frozenset(
    {
        "schemaVersion",
        "kind",
        "policyId",
        "policySha256",
        "loaderInputInventorySha256",
        "payloadByteSemantics",
        "loaderInputObservation",
        "profile",
        "fixedHotActionRows",
        "coldHistoryRows",
        "payloadSizeBytes",
        "expectedTableCardinalities",
        "expectedSnapshotCardinalities",
        "expectedLoaderInputs",
    }
)


def load_production_shape_fixture_config(path: Path, profile: str) -> Mapping[str, Any]:
    if path.is_symlink() or not path.is_file():
        raise RunnerFailure("production-shape fixture config must be a regular non-symlink file")
    try:
        value = json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=_no_duplicate_json_keys)
    except (OSError, json.JSONDecodeError) as exc:
        raise RunnerFailure("production-shape fixture config is not readable JSON") from exc
    return validate_production_shape_fixture_config(value, profile)


def load_baseline_fixture_config(path: Path, profile: str) -> Mapping[str, Any]:
    if path.is_symlink() or not path.is_file():
        raise RunnerFailure("baseline fixture config must be a regular non-symlink file")
    try:
        value = json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=_no_duplicate_json_keys)
    except (OSError, json.JSONDecodeError) as exc:
        raise RunnerFailure("baseline fixture config is not readable JSON") from exc
    return validate_baseline_fixture_config(value, profile)


def _no_duplicate_json_keys(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    value: dict[str, Any] = {}
    for key, item in pairs:
        if key in value:
            raise RunnerFailure(f"production-shape fixture config repeats JSON key {key!r}")
        value[key] = item
    return value


def validate_production_shape_fixture_config(value: Any, profile: str) -> Mapping[str, Any]:
    if not isinstance(value, Mapping):
        raise RunnerFailure("production-shape fixture config must be an object")
    if set(value) != PRODUCTION_SHAPE_FIXTURE_CONFIG_FIELDS:
        raise RunnerFailure("production-shape fixture config has an unexpected field set")
    if value.get("schemaVersion") != FIXTURE_CONFIG_SCHEMA_VERSION:
        raise RunnerFailure("production-shape fixture config schema is unsupported")
    if value.get("kind") != "sanitized-production-shape" or value.get("profile") != profile:
        raise RunnerFailure("production-shape fixture config does not match the requested profile")
    manifest_sha256 = value.get("manifestSha256")
    if not isinstance(manifest_sha256, str) or not re.fullmatch(r"[0-9a-f]{64}", manifest_sha256):
        raise RunnerFailure("production-shape fixture config manifest SHA-256 is invalid")
    if value.get("loaderInputInventorySha256") != LOADER_INPUT_INVENTORY_SHA256:
        raise RunnerFailure("production-shape fixture config is not bound to the checked-in loader-input inventory")
    if value.get("payloadByteSemantics") != PAYLOAD_BYTE_SEMANTICS:
        raise RunnerFailure("production-shape fixture config payloadByteSemantics is unsupported")
    if value.get("loaderInputObservation") != loader_input_observation_contract():
        raise RunnerFailure("production-shape fixture config loaderInputObservation diverges from the checked-in inventory")
    _validate_positive_fixture_number(value.get("fixedHotActionRows"), "fixedHotActionRows")
    _validate_positive_fixture_number(value.get("coldHistoryRows"), "coldHistoryRows")
    _validate_fixture_number_map(value.get("payloadSizeBytes"), {"hotAction", "coldHistory"}, "payloadSizeBytes", positive=True)
    _validate_fixture_number_map(
        value.get("expectedTableCardinalities"),
        set(REQUIRED_TABLE_CARDINALITIES),
        "expectedTableCardinalities",
        positive=False,
    )
    _validate_fixture_number_map(
        value.get("expectedSnapshotCardinalities"),
        set(REQUIRED_SNAPSHOT_CARDINALITIES),
        "expectedSnapshotCardinalities",
        positive=False,
    )
    _validate_loader_input_metrics(value.get("expectedLoaderInputs"), "expectedLoaderInputs")
    return value


def validate_local_sanitized_aggregate_fixture_config(value: Any, profile: str) -> Mapping[str, Any]:
    if not isinstance(value, Mapping):
        raise RunnerFailure("local sanitized aggregate fixture config must be an object")
    if set(value) != LOCAL_SANITIZED_AGGREGATE_FIXTURE_CONFIG_FIELDS:
        raise RunnerFailure("local sanitized aggregate fixture config has an unexpected field set")
    if value.get("schemaVersion") != LOCAL_SANITIZED_AGGREGATE_FIXTURE_CONFIG_SCHEMA_VERSION:
        raise RunnerFailure("local sanitized aggregate fixture config schema is unsupported")
    if value.get("kind") != LOCAL_SANITIZED_AGGREGATE_FIXTURE_KIND or value.get("profile") != profile:
        raise RunnerFailure("local sanitized aggregate fixture config does not match the requested profile")
    policy_id = value.get("policyId")
    if not isinstance(policy_id, str) or not re.fullmatch(r"op123-local-sanitized-aggregate-v[0-9]+", policy_id):
        raise RunnerFailure("local sanitized aggregate fixture config policy id is invalid")
    policy_sha256 = value.get("policySha256")
    if not isinstance(policy_sha256, str) or not re.fullmatch(r"[0-9a-f]{64}", policy_sha256):
        raise RunnerFailure("local sanitized aggregate fixture config policy SHA-256 is invalid")
    if value.get("loaderInputInventorySha256") != LOADER_INPUT_INVENTORY_SHA256:
        raise RunnerFailure("local sanitized aggregate fixture config is not bound to the checked-in loader-input inventory")
    if value.get("payloadByteSemantics") != PAYLOAD_BYTE_SEMANTICS:
        raise RunnerFailure("local sanitized aggregate fixture config payloadByteSemantics is unsupported")
    if value.get("loaderInputObservation") != loader_input_observation_contract():
        raise RunnerFailure("local sanitized aggregate fixture config loaderInputObservation diverges from the checked-in inventory")
    _validate_positive_fixture_number(value.get("fixedHotActionRows"), "fixedHotActionRows")
    _validate_positive_fixture_number(value.get("coldHistoryRows"), "coldHistoryRows")
    _validate_fixture_number_map(value.get("payloadSizeBytes"), {"hotAction", "coldHistory"}, "payloadSizeBytes", positive=True)
    _validate_fixture_number_map(
        value.get("expectedTableCardinalities"),
        set(REQUIRED_TABLE_CARDINALITIES),
        "expectedTableCardinalities",
        positive=False,
    )
    _validate_fixture_number_map(
        value.get("expectedSnapshotCardinalities"),
        set(REQUIRED_SNAPSHOT_CARDINALITIES),
        "expectedSnapshotCardinalities",
        positive=False,
    )
    _validate_loader_input_metrics(value.get("expectedLoaderInputs"), "expectedLoaderInputs")
    return value


def validate_baseline_fixture_config(value: Any, profile: str) -> Mapping[str, Any]:
    if not isinstance(value, Mapping):
        raise RunnerFailure("baseline fixture config must be an object")
    kind = value.get("kind")
    if kind == "sanitized-production-shape":
        return validate_production_shape_fixture_config(value, profile)
    if kind == LOCAL_SANITIZED_AGGREGATE_FIXTURE_KIND:
        return validate_local_sanitized_aggregate_fixture_config(value, profile)
    raise RunnerFailure("baseline fixture config kind is unsupported")


def _validate_positive_fixture_number(value: Any, label: str) -> None:
    if isinstance(value, bool) or not isinstance(value, int) or not 0 < value <= MAX_CARDINALITY:
        raise RunnerFailure(f"production-shape fixture config {label} must be a positive integer")


def _validate_fixture_number_map(
    value: Any,
    expected_keys: set[str],
    label: str,
    *,
    positive: bool,
) -> None:
    if not isinstance(value, Mapping) or set(value) != expected_keys:
        raise RunnerFailure(f"production-shape fixture config {label} has an unexpected field set")
    for key, number in value.items():
        if (
            isinstance(number, bool)
            or not isinstance(number, int)
            or number > MAX_CARDINALITY
            or (number <= 0 if positive else number < 0)
        ):
            qualifier = "positive" if positive else "non-negative"
            raise RunnerFailure(f"production-shape fixture config {label}.{key} must be a {qualifier} integer")


def _validate_loader_input_metrics(value: Any, label: str) -> None:
    if not isinstance(value, Mapping) or set(value) != set(REQUIRED_LOADER_INPUT_IDS):
        raise RunnerFailure(f"production-shape fixture config {label} has an unexpected field set")
    for input_id, metrics in value.items():
        if not isinstance(metrics, Mapping) or set(metrics) != {"sourceRows", "retainedItems", "payloadBytes"}:
            raise RunnerFailure(f"production-shape fixture config {label}.{input_id} has an unexpected field set")
        for metric, number in metrics.items():
            if isinstance(number, bool) or not isinstance(number, int) or not 0 <= number <= MAX_CARDINALITY:
                raise RunnerFailure(
                    f"production-shape fixture config {label}.{input_id}.{metric} must be a non-negative integer"
                )


@dataclass(frozen=True)
class AnalysisArtifactLayout:
    run_directory: Path
    raw_directory: Path
    jfr_directory: Path
    logs_directory: Path
    fixture_directory: Path | None
    manifest_directory: Path | None
    manifest_path: Path | None
    contract_kind: str | None
    source_paths: Mapping[str, Path]
    derived_paths: Mapping[str, Path]


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Build and run the isolated OPENSAM-123 synthetic scenario-seed baseline probe in six fresh Docker runs."
    )
    parser.add_argument("--base-rows", type=int)
    parser.add_argument("--run-id")
    parser.add_argument("--analyze-run-id")
    parser.add_argument(
        "--production-shape-manifest",
        type=Path,
        help="Blocked until a deterministic sanitized materializer or approved sanitized restore exists; use --validate-production-shape-manifest.",
    )
    parser.add_argument(
        "--validate-production-shape-manifest",
        type=Path,
        help="Validate a sanitized aggregate contract without building or running a probe.",
    )
    parser.add_argument(
        "--local-sanitized-aggregate-policy",
        type=Path,
        help="Run the checked-in deterministic local-only aggregate surrogate; this is not a production-shape capture.",
    )
    parser.add_argument("--output-dir", type=Path, default=DEFAULT_OUTPUT_ROOT)
    return parser.parse_args()


def command_output(
    command: Sequence[str],
    *,
    cwd: Path = REPOSITORY_ROOT,
    environment: Mapping[str, str] | None = None,
    check: bool = True,
) -> subprocess.CompletedProcess[str]:
    completed = subprocess.run(
        [str(part) for part in command],
        cwd=cwd,
        env=dict(environment) if environment is not None else None,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        check=False,
    )
    if check and completed.returncode != 0:
        raise RunnerFailure(
            f"command failed ({completed.returncode}): {' '.join(command)}\n{completed.stdout[-4000:]}"
        )
    return completed


def require_within_build(path: Path) -> Path:
    resolved = path.resolve()
    engine_build = (ENGINE_DIR / "build").resolve()
    try:
        resolved.relative_to(engine_build)
    except ValueError as exc:
        raise RunnerFailure(f"output directory must stay under {engine_build}, found {resolved}") from exc
    return resolved


def resolve_host_jdk21() -> tuple[Path, Path]:
    candidates: list[Path] = []
    java_home = os.environ.get("JAVA_HOME")
    if java_home:
        candidates.append(Path(java_home) / "bin" / "java")
    mac_java_home = Path("/usr/libexec/java_home")
    if mac_java_home.is_file():
        selected = command_output([str(mac_java_home), "-v", "21"], check=False)
        if selected.returncode == 0:
            candidates.append(Path(selected.stdout.strip()) / "bin" / "java")
    discovered = shutil.which("java")
    if discovered:
        candidates.append(Path(discovered))
    for java in candidates:
        if not java.is_file():
            continue
        version_output = command_output([str(java), "-version"], check=False).stdout
        if java_major(version_output) == 21:
            return java.parent.parent, java
    raise RunnerFailure("JDK 21 is required to build the baseline classifier jar")


def java_major(version_output: str) -> int | None:
    match = re.search(r"(?:openjdk|java) version\s+\"?(\d+)", version_output, re.IGNORECASE)
    if match:
        return int(match.group(1))
    match = re.match(r"\s*(\d+)(?:[.+-]|$)", version_output)
    if match:
        return int(match.group(1))
    return None


def ensure_docker_and_images() -> dict[str, str]:
    if shutil.which("docker") is None:
        raise RunnerFailure("Docker CLI is required")
    command_output(["docker", "info", "--format", "{{.ServerVersion}}"])
    for image in (PROBE_IMAGE, POSTGRES_IMAGE):
        inspected = command_output(["docker", "image", "inspect", image], check=False)
        if inspected.returncode != 0:
            command_output(["docker", "pull", image])
    version = command_output(["docker", "run", "--rm", PROBE_IMAGE, "java", "-version"]).stdout
    if java_major(version) != 21:
        raise RunnerFailure(f"Probe image must provide JDK 21, got: {version.strip()}")
    return {
        "probeTag": PROBE_IMAGE,
        "probeId": image_id(PROBE_IMAGE),
        "postgresTag": POSTGRES_IMAGE,
        "postgresId": image_id(POSTGRES_IMAGE),
    }


def image_id(image: str) -> str:
    identity = command_output(["docker", "image", "inspect", "--format", "{{.Id}}", image]).stdout.strip()
    if not identity:
        raise RunnerFailure(f"Docker image {image} did not report an image ID")
    return identity


def file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as artifact:
        while chunk := artifact.read(1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest()


def require_gradle_success(output: str) -> None:
    if "BUILD SUCCESSFUL" not in output:
        raise RunnerFailure("baseline Gradle build did not report BUILD SUCCESSFUL")


def validate_fresh_classifier_jar(classifier_jar: Path, build_started_at_ns: int) -> str:
    if not classifier_jar.is_file():
        raise RunnerFailure(f"runtimeBaselineJar did not emit the expected classifier: {classifier_jar}")
    if classifier_jar.stat().st_mtime_ns < build_started_at_ns:
        raise RunnerFailure(f"runtimeBaselineJar did not refresh classifier artifact: {classifier_jar}")
    return file_sha256(classifier_jar)


def build_baseline_jar(run_dir: Path, host_java_home: Path) -> Path:
    environment = os.environ.copy()
    environment["JAVA_HOME"] = str(host_java_home)
    environment["GRADLE_USER_HOME"] = str(DEFAULT_OUTPUT_ROOT / "gradle-user-home")
    build_log = run_dir / "logs" / "build.log"
    build_started_at_ns = time.time_ns()
    completed = command_output(
        [
            str(REPOSITORY_ROOT / "gradlew"),
            "--no-daemon",
            "--rerun-tasks",
            "-Dkotlin.compiler.execution.strategy=in-process",
            ":app:game-engine:verifyRuntimeBaselineJarIsolation",
        ],
        environment=environment,
        check=False,
    )
    build_log.write_text(completed.stdout, encoding="utf-8")
    if completed.returncode != 0:
        raise RunnerFailure(f"baseline jar build failed; see {build_log}")
    require_gradle_success(completed.stdout)
    classifier_digest = validate_fresh_classifier_jar(BASELINE_JAR_PATH, build_started_at_ns)
    validate_classifier_jar(BASELINE_JAR_PATH)
    build_log.write_text(
        completed.stdout.rstrip() + f"\nClassifier SHA-256: {classifier_digest}\n",
        encoding="utf-8",
    )
    return BASELINE_JAR_PATH


def validate_classifier_jar(classifier_jar: Path) -> None:
    expected_jar = BASELINE_JAR_PATH.resolve()
    if classifier_jar.resolve() != expected_jar:
        raise RunnerFailure(f"baseline classifier must be the expected task archive: {expected_jar}")
    expected_directory = BASELINE_JAR_DIRECTORY.resolve()
    if classifier_jar.resolve().parent != expected_directory:
        raise RunnerFailure(
            f"baseline classifier must stay under {expected_directory}, found {classifier_jar.resolve()}"
        )
    dedicated_candidates = sorted(expected_directory.glob("*-cqrs-baseline.jar"))
    if dedicated_candidates != [expected_jar]:
        raise RunnerFailure(
            "baseline classifier directory must contain only the fixed task archive: "
            + ", ".join(str(path) for path in dedicated_candidates)
        )
    production_collisions = sorted(PRODUCTION_JAR_DIRECTORY.glob("*-cqrs-baseline.jar"))
    if production_collisions:
        raise RunnerFailure(
            "production build/libs must not contain a baseline classifier: "
            + ", ".join(str(path) for path in production_collisions)
        )


def docker_name(run_id: str, suffix: str) -> str:
    normalized_run_id = re.sub(r"[^a-z0-9]+", "-", run_id.lower()).strip("-")
    normalized_suffix = re.sub(r"[^a-z0-9]+", "-", suffix.lower()).strip("-")
    token = hashlib.sha256(run_id.encode("utf-8")).hexdigest()[:16]
    prefix_length = 63 - len("cqrs-baseline-") - len(token) - len(normalized_suffix) - 2
    if prefix_length < 1:
        raise RunnerFailure(f"Docker suffix is too long: {suffix}")
    return f"cqrs-baseline-{normalized_run_id[:prefix_length]}-{token}-{normalized_suffix}"


def label_value_belongs_to_run(label_value: str | None, run_id: str) -> bool:
    return label_value == run_id


def docker_label_args(run_id: str) -> list[str]:
    return ["--label", f"{RUN_LABEL_KEY}={run_id}"]


def container_run_label(name: str) -> str | None:
    template = f'{{{{ index .Config.Labels "{RUN_LABEL_KEY}" }}}}'
    inspected = command_output(["docker", "container", "inspect", "--format", template, name], check=False)
    if inspected.returncode != 0:
        return None
    return inspected.stdout.strip() or None


def cleanup_container(name: str, run_id: str) -> None:
    if label_value_belongs_to_run(container_run_label(name), run_id):
        command_output(["docker", "rm", "-f", name], check=False)


def wait_for_postgres(container: str) -> None:
    deadline = time.monotonic() + 60
    while time.monotonic() < deadline:
        ready = command_output(
            ["docker", "exec", container, "pg_isready", "-U", POSTGRES_USER, "-d", POSTGRES_DATABASE],
            check=False,
        )
        if ready.returncode == 0:
            return
        time.sleep(0.5)
    raise RunnerFailure(f"Postgres container {container} did not become ready within 60 seconds")


def start_postgres(container: str, network: str, run_id: str, image: str) -> None:
    command_output(
        [
            "docker",
            "run",
            "-d",
            "--rm",
            "--name",
            container,
            "--network",
            network,
            *docker_label_args(run_id),
            "-e",
            f"POSTGRES_USER={POSTGRES_USER}",
            "-e",
            f"POSTGRES_PASSWORD={POSTGRES_PASSWORD}",
            "-e",
            f"POSTGRES_DB={POSTGRES_DATABASE}",
            image,
        ]
    )
    wait_for_postgres(container)


def jfr_event_counts(summary: str) -> dict[str, int]:
    if "Event Type" not in summary:
        raise RunnerFailure("JFR summary did not contain an event table")
    counts: dict[str, int] = {}
    for event_type, count in re.findall(r"^\s*(jdk\.[A-Za-z0-9_]+)\s+(\d+)\s+\d+\s*$", summary, re.MULTILINE):
        counts[event_type] = int(count)
    if not counts:
        raise RunnerFailure("JFR summary did not contain parseable event counts")
    return counts


def validate_jfr_summary(summary: str) -> dict[str, int]:
    counts = jfr_event_counts(summary)
    missing = [event_type for event_type in REQUIRED_JFR_EVENTS if counts.get(event_type, 0) <= 0]
    if missing:
        raise RunnerFailure(f"JFR summary omitted required profile events: {', '.join(missing)}")
    return counts


def validate_jfr_file(
    jfr_tool: Path,
    jfr_path: Path,
    summary_path: Path,
    layout: AnalysisArtifactLayout,
) -> dict[str, int]:
    completed = command_output([str(jfr_tool), "summary", str(jfr_path)], check=False)
    atomic_write_text(summary_path, completed.stdout, layout)
    if completed.returncode != 0:
        raise RunnerFailure(f"JFR summary failed for {jfr_path}; see {summary_path}")
    return validate_jfr_summary(completed.stdout)


def parse_iso_duration_millis(value: str) -> float:
    match = ISO_DURATION_PATTERN.fullmatch(value)
    if match is None or not any(match.group(name) is not None for name in ("days", "hours", "minutes", "seconds")):
        raise RunnerFailure(f"JFR duration is not a supported ISO-8601 duration: {value!r}")
    seconds = (
        float(match.group("days") or 0) * 86_400
        + float(match.group("hours") or 0) * 3_600
        + float(match.group("minutes") or 0) * 60
        + float(match.group("seconds") or 0)
    )
    if not math.isfinite(seconds) or seconds < 0:
        raise RunnerFailure(f"JFR duration must be finite and non-negative: {value!r}")
    return seconds * 1_000


def jfr_gc_phase_pause_durations_by_cause(output: str) -> dict[str, list[float]]:
    try:
        document = json.loads(output)
    except json.JSONDecodeError as exc:
        raise RunnerFailure("JFR GC pause analysis output was not valid JSON") from exc
    if not isinstance(document, Mapping):
        raise RunnerFailure("JFR GC pause analysis JSON root must be an object")
    recording = document.get("recording")
    if not isinstance(recording, Mapping):
        raise RunnerFailure("JFR GC pause analysis JSON omitted recording")
    events = recording.get("events")
    if not isinstance(events, list):
        raise RunnerFailure("JFR GC pause analysis JSON omitted events")
    garbage_collection_causes: dict[int, str] = {}
    phase_pauses: list[tuple[int, float]] = []
    for index, event in enumerate(events):
        if not isinstance(event, Mapping):
            raise RunnerFailure(f"JFR GC pause analysis event {index} was not an object")
        event_type = event.get("type")
        values = event.get("values")
        if not isinstance(values, Mapping) or not isinstance(values.get("gcId"), int):
            raise RunnerFailure(f"JFR GC pause analysis event {index} omitted numeric gcId")
        gc_id = values["gcId"]
        if event_type == JFR_GARBAGE_COLLECTION_EVENT:
            cause = values.get("cause")
            if not isinstance(cause, str):
                raise RunnerFailure(f"JFR GarbageCollection event {index} omitted cause")
            prior = garbage_collection_causes.setdefault(gc_id, cause)
            if prior != cause:
                raise RunnerFailure(f"JFR GarbageCollection gcId {gc_id} reported conflicting causes")
        elif event_type == JFR_GC_PHASE_PAUSE_EVENT:
            duration = values.get("duration")
            if not isinstance(duration, str):
                raise RunnerFailure(f"JFR GCPhasePause event {index} omitted duration")
            phase_pauses.append((gc_id, parse_iso_duration_millis(duration)))
        else:
            raise RunnerFailure(f"JFR GC pause analysis event {index} had unexpected type {event_type!r}")
    durations = {"operational": [], "forcedRetainedHeapProbe": []}
    for gc_id, duration in phase_pauses:
        cause = garbage_collection_causes.get(gc_id)
        if cause is None:
            raise RunnerFailure(f"JFR GCPhasePause gcId {gc_id} did not map to a GarbageCollection cause")
        bucket = "forcedRetainedHeapProbe" if cause == FORCED_GC_CAUSE else "operational"
        durations[bucket].append(duration)
    return durations


def gc_phase_pause_metrics(durations_millis: Sequence[float]) -> dict[str, int | float]:
    if not durations_millis:
        raise RunnerFailure("JFR GCPhasePause output did not contain any pause durations")
    ordered = sorted(float(value) for value in durations_millis)
    if any(not math.isfinite(value) or value < 0 for value in ordered):
        raise RunnerFailure("JFR GCPhasePause durations must be finite and non-negative")
    return {
        "eventCount": len(ordered),
        "totalDurationMillis": sum(ordered),
        "maxDurationMillis": ordered[-1],
        "p50DurationMillis": percentile(ordered, 0.50),
        "p95DurationMillis": percentile(ordered, 0.95),
    }


def analyze_jfr_gc_phase_pause(
    jfr_tool: Path,
    jfr_path: Path,
    output_path: Path,
    layout: AnalysisArtifactLayout,
) -> tuple[dict[str, dict[str, int | float]], dict[str, list[float]]]:
    completed = command_output(
        [str(jfr_tool), "print", "--json", "--events", JFR_GC_PAUSE_ANALYSIS_EVENTS, str(jfr_path)],
        check=False,
    )
    atomic_write_text(output_path, completed.stdout, layout)
    if completed.returncode != 0:
        raise RunnerFailure(f"JFR GC pause print failed for {jfr_path}; see {output_path}")
    durations = jfr_gc_phase_pause_durations_by_cause(completed.stdout)
    return {bucket: gc_phase_pause_metrics(values) for bucket, values in durations.items()}, durations


def run_probe(
    *,
    jar: Path,
    run_dir: Path,
    network: str,
    run_id: str,
    profile: str,
    sample: int,
    base_rows: int,
    images: Mapping[str, str],
    fixture_config_path: Path | None = None,
) -> Mapping[str, Any]:
    stem = f"{profile}-{sample}"
    db_name = docker_name(run_id, f"{profile}-{sample}-db")
    probe_name = docker_name(run_id, f"{profile}-{sample}-probe")
    raw_path = run_dir / "raw" / f"{stem}.json"
    jfr_path = run_dir / "jfr" / f"{stem}.jfr"
    log_path = run_dir / "logs" / f"{stem}.log"
    jfr_summary_path = run_dir / "logs" / f"{stem}.jfr-summary.txt"
    fixture_directory = tempfile.TemporaryDirectory(prefix="cqrs-production-shape-contract-") if fixture_config_path else None
    expected_fixture_config: Mapping[str, Any] | None = None
    immutable_fixture_config: ImmutableFixtureConfig | None = None
    if fixture_config_path is not None and fixture_directory is not None:
        contract_directory = Path(fixture_directory.name)
        os.chmod(contract_directory, 0o700)
        expected_fixture_config, immutable_fixture_config = prepare_immutable_probe_fixture_config(
            fixture_config_path,
            profile,
            contract_directory,
        )
    fixture_mount = (
        [
            "--mount",
            f"type=bind,src={immutable_fixture_config.path.resolve()},dst=/contract/fixture-config.json,readonly",
        ]
        if immutable_fixture_config is not None
        else []
    )
    fixture_argument = (
        "--fixture-config=/contract/fixture-config.json"
        if immutable_fixture_config is not None
        else f"--base-rows={base_rows}"
    )
    try:
        if immutable_fixture_config is not None:
            immutable_fixture_config.verify()
        start_postgres(db_name, network, run_id, images["postgresId"])
        command = [
            "docker",
            "run",
            "--rm",
            "--name",
            probe_name,
            "--network",
            network,
            *docker_label_args(run_id),
            "--memory=2g",
            "--memory-swap=2g",
            "--read-only",
            "--tmpfs",
            "/tmp:rw,nosuid,nodev,size=128m",
            "--mount",
            f"type=bind,src={jar.parent.resolve()},dst=/app,readonly",
            "--mount",
            f"type=bind,src={run_dir.resolve()},dst=/artifacts",
            *fixture_mount,
            "-e",
            f"BASELINE_DB_URL=jdbc:postgresql://{db_name}:5432/{POSTGRES_DATABASE}",
            "-e",
            f"BASELINE_DB_USERNAME={POSTGRES_USER}",
            "-e",
            f"BASELINE_DB_PASSWORD={POSTGRES_PASSWORD}",
            "-e",
            f"BASELINE_PROBE_IMAGE_TAG={images['probeTag']}",
            "-e",
            f"BASELINE_PROBE_IMAGE_ID={images['probeId']}",
            "-e",
            f"BASELINE_POSTGRES_IMAGE_TAG={images['postgresTag']}",
            "-e",
            f"BASELINE_POSTGRES_IMAGE_ID={images['postgresId']}",
            images["probeId"],
            "java",
            *REQUIRED_JVM_ARGS,
            "-jar",
            f"/app/{jar.name}",
            f"--profile={profile}",
            fixture_argument,
            f"--output=/artifacts/raw/{raw_path.name}",
            f"--jfr=/artifacts/jfr/{jfr_path.name}",
        ]
        completed = command_output(command, check=False)
        if immutable_fixture_config is not None:
            immutable_fixture_config.verify()
        log_path.write_text(completed.stdout, encoding="utf-8")
        if completed.returncode != 0:
            raise RunnerFailure(f"probe {stem} failed; see {log_path}")
    finally:
        cleanup_container(probe_name, run_id)
        cleanup_container(db_name, run_id)
        if fixture_directory is not None:
            fixture_directory.cleanup()
    if not raw_path.is_file() or not jfr_path.is_file() or jfr_path.stat().st_size == 0:
        raise RunnerFailure(f"probe {stem} did not emit both raw JSON and non-empty JFR")
    raw = json.loads(raw_path.read_text(encoding="utf-8"))
    validate_raw(raw, profile, expected_fixture_config=expected_fixture_config)
    return raw


def validate_raw(
    raw: Mapping[str, Any],
    profile: str,
    *,
    expected_fixture_config: Mapping[str, Any] | None = None,
) -> None:
    expected_schema = "cqrs-runtime-baseline.raw.v2"
    if expected_fixture_config is not None and expected_fixture_config.get("kind") == LOCAL_SANITIZED_AGGREGATE_FIXTURE_KIND:
        expected_schema = LOCAL_SANITIZED_AGGREGATE_RAW_SCHEMA_VERSION
    if raw.get("schemaVersion") != expected_schema:
        raise RunnerFailure("unexpected raw baseline schema")
    if raw.get("profile") != profile:
        raise RunnerFailure(f"raw profile mismatch: expected {profile}, got {raw.get('profile')}")
    cgroup = require_mapping(raw, "cgroup")
    if cgroup.get("memoryLimitBytes") != REQUIRED_CGROUP_BYTES:
        raise RunnerFailure(f"probe did not observe the required 2 GiB cgroup: {cgroup}")
    jvm = require_mapping(raw, "jvm")
    if java_major(str(jvm.get("version", ""))) != 21:
        raise RunnerFailure(f"probe did not report JDK 21: {jvm.get('version')}")
    arguments = jvm.get("inputArguments")
    if not isinstance(arguments, list) or any(argument not in arguments for argument in REQUIRED_JVM_ARGS):
        raise RunnerFailure(f"probe JVM arguments do not match contract: {arguments}")
    memory = require_mapping(raw, "memory")
    heap_after = require_mapping(memory, "heapAfterGc")
    if not isinstance(heap_after.get("usedBytes"), int):
        raise RunnerFailure("raw baseline omitted post-GC retained-heap proxy")
    gc = require_mapping(raw, "gc")
    if not isinstance(gc.get("collectionTimeDeltaMillis"), int):
        raise RunnerFailure("raw baseline omitted the GC collection-time proxy")
    artifacts = require_mapping(raw, "artifacts")
    if artifacts.get("jfrConfiguration") != "profile":
        raise RunnerFailure("raw baseline did not use the JFR profile configuration")
    images = require_mapping(raw, "images")
    for key in ("probeTag", "probeId", "postgresTag", "postgresId"):
        if not isinstance(images.get(key), str) or not images[key]:
            raise RunnerFailure(f"raw baseline omitted image identity {key}")
    if expected_fixture_config is not None:
        validate_contract_fixture(raw, profile, expected_fixture_config)


def validate_contract_fixture(
    raw: Mapping[str, Any],
    profile: str,
    expected_fixture_config: Mapping[str, Any],
) -> None:
    kind = expected_fixture_config.get("kind")
    if kind == "sanitized-production-shape":
        validate_production_shape_fixture(raw, profile, expected_fixture_config)
        return
    if kind == LOCAL_SANITIZED_AGGREGATE_FIXTURE_KIND:
        validate_local_sanitized_aggregate_fixture(raw, profile, expected_fixture_config)
        return
    raise RunnerFailure("raw baseline received an unsupported fixture config kind")


def validate_production_shape_fixture(
    raw: Mapping[str, Any],
    profile: str,
    expected_fixture_config: Mapping[str, Any],
) -> None:
    expected = validate_production_shape_fixture_config(expected_fixture_config, profile)
    fixture = require_mapping(raw, "fixture")
    for key in (
        "kind",
        "manifestSha256",
        "loaderInputInventorySha256",
        "payloadByteSemantics",
        "loaderInputObservation",
        "profile",
        "fixedHotLogRows",
        "coldHistoryRows",
        "payloadSizeBytes",
    ):
        if key not in fixture:
            raise RunnerFailure(f"raw baseline omitted production-shape fixture field {key}")
    if fixture["kind"] != expected["kind"] or fixture["profile"] != profile:
        raise RunnerFailure("raw baseline did not report the approved production-shape fixture kind/profile")
    if fixture["manifestSha256"] != expected["manifestSha256"]:
        raise RunnerFailure("raw baseline production-shape manifest SHA-256 does not match the approved config")
    if fixture["loaderInputInventorySha256"] != expected["loaderInputInventorySha256"]:
        raise RunnerFailure("raw baseline loader-input inventory SHA-256 does not match the approved config")
    if fixture["payloadByteSemantics"] != expected["payloadByteSemantics"]:
        raise RunnerFailure("raw baseline payload byte semantics do not match the approved config")
    if fixture["loaderInputObservation"] != expected["loaderInputObservation"]:
        raise RunnerFailure("raw baseline loader-input observation columns do not match the approved config")
    if fixture["fixedHotLogRows"] != expected["fixedHotActionRows"]:
        raise RunnerFailure("raw baseline fixed hot action rows do not match the approved production shape")
    if fixture["coldHistoryRows"] != expected["coldHistoryRows"]:
        raise RunnerFailure("raw baseline cold-history rows do not match the approved production shape")
    if fixture["payloadSizeBytes"] != expected["payloadSizeBytes"]:
        raise RunnerFailure("raw baseline payload-size assumptions do not match the approved production shape")
    rows = require_mapping(raw, "rows")
    database_rows = require_mapping(rows, "database")
    expected_tables = require_mapping(expected, "expectedTableCardinalities")
    for manifest_name, raw_name in PRODUCTION_SHAPE_TABLE_TO_RAW_FIELD.items():
        if database_rows.get(raw_name) != expected_tables.get(manifest_name):
            raise RunnerFailure(f"raw baseline table cardinality {raw_name} does not match the approved production shape")
    snapshot_rows = require_mapping(rows, "snapshot")
    expected_snapshot = require_mapping(expected, "expectedSnapshotCardinalities")
    for name, expected_count in expected_snapshot.items():
        if snapshot_rows.get(name) != expected_count:
            raise RunnerFailure(f"raw baseline snapshot cardinality {name} does not match the approved production shape")
    observed_loader_inputs = require_mapping(rows, "loaderInputs")
    expected_loader_inputs = require_mapping(expected, "expectedLoaderInputs")
    if observed_loader_inputs != expected_loader_inputs:
        raise RunnerFailure("raw baseline loader-input metrics do not match the approved production shape")


def validate_local_sanitized_aggregate_fixture(
    raw: Mapping[str, Any],
    profile: str,
    expected_fixture_config: Mapping[str, Any],
) -> None:
    expected = validate_local_sanitized_aggregate_fixture_config(expected_fixture_config, profile)
    fixture = require_mapping(raw, "fixture")
    for key in (
        "kind",
        "policyId",
        "policySha256",
        "loaderInputInventorySha256",
        "payloadByteSemantics",
        "loaderInputObservation",
        "profile",
        "fixedHotLogRows",
        "coldHistoryRows",
        "payloadSizeBytes",
    ):
        if key not in fixture:
            raise RunnerFailure(f"raw baseline omitted local sanitized aggregate fixture field {key}")
    if fixture["kind"] != expected["kind"] or fixture["profile"] != profile:
        raise RunnerFailure("raw baseline did not report the local sanitized aggregate fixture kind/profile")
    if fixture["policyId"] != expected["policyId"] or fixture["policySha256"] != expected["policySha256"]:
        raise RunnerFailure("raw baseline local sanitized aggregate policy identity does not match the approved config")
    if fixture["loaderInputInventorySha256"] != expected["loaderInputInventorySha256"]:
        raise RunnerFailure("raw baseline local loader-input inventory SHA-256 does not match the approved config")
    if fixture["payloadByteSemantics"] != expected["payloadByteSemantics"]:
        raise RunnerFailure("raw baseline local payload byte semantics do not match the approved config")
    if fixture["loaderInputObservation"] != expected["loaderInputObservation"]:
        raise RunnerFailure("raw baseline local loader-input observation columns do not match the approved config")
    if fixture["fixedHotLogRows"] != expected["fixedHotActionRows"]:
        raise RunnerFailure("raw baseline local fixed hot action rows do not match the policy")
    if fixture["coldHistoryRows"] != expected["coldHistoryRows"]:
        raise RunnerFailure("raw baseline local cold-history rows do not match the policy")
    if fixture["payloadSizeBytes"] != expected["payloadSizeBytes"]:
        raise RunnerFailure("raw baseline local payload-size assumptions do not match the policy")
    rows = require_mapping(raw, "rows")
    database_rows = require_mapping(rows, "database")
    expected_tables = require_mapping(expected, "expectedTableCardinalities")
    for manifest_name, raw_name in PRODUCTION_SHAPE_TABLE_TO_RAW_FIELD.items():
        if database_rows.get(raw_name) != expected_tables.get(manifest_name):
            raise RunnerFailure(f"raw baseline local table cardinality {raw_name} does not match the policy")
    snapshot_rows = require_mapping(rows, "snapshot")
    expected_snapshot = require_mapping(expected, "expectedSnapshotCardinalities")
    for name, expected_count in expected_snapshot.items():
        if snapshot_rows.get(name) != expected_count:
            raise RunnerFailure(f"raw baseline local snapshot cardinality {name} does not match the policy")
    observed_loader_inputs = require_mapping(rows, "loaderInputs")
    expected_loader_inputs = require_mapping(expected, "expectedLoaderInputs")
    if observed_loader_inputs != expected_loader_inputs:
        raise RunnerFailure("raw baseline local loader-input metrics do not match the policy")


def require_mapping(value: Mapping[str, Any], key: str) -> Mapping[str, Any]:
    candidate = value.get(key)
    if not isinstance(candidate, Mapping):
        raise RunnerFailure(f"raw baseline field {key} must be an object")
    return candidate


def percentile(sorted_values: Sequence[float], fraction: float) -> float:
    if not sorted_values:
        raise RunnerFailure("cannot calculate a percentile for no values")
    position = (len(sorted_values) - 1) * fraction
    lower = math.floor(position)
    upper = math.ceil(position)
    if lower == upper:
        return sorted_values[lower]
    return sorted_values[lower] + (sorted_values[upper] - sorted_values[lower]) * (position - lower)


def summary_metric(values: Sequence[int | float], percentiles: bool = False) -> dict[str, Any]:
    if not values:
        raise RunnerFailure("cannot summarize no samples")
    ordered = sorted(float(value) for value in values)
    result: dict[str, Any] = {
        "n": len(ordered),
        "min": ordered[0],
        "max": ordered[-1],
        "mean": sum(ordered) / len(ordered),
        "runToRunSpread": ordered[-1] - ordered[0],
    }
    if percentiles:
        result["p50"] = percentile(ordered, 0.50)
        result["p95"] = percentile(ordered, 0.95)
    return result


def nested_integer(raw: Mapping[str, Any], *keys: str) -> int:
    current: Any = raw
    for key in keys:
        if not isinstance(current, Mapping) or key not in current:
            raise RunnerFailure(f"raw baseline missing {'/'.join(keys)}")
        current = current[key]
    if not isinstance(current, int):
        raise RunnerFailure(f"raw baseline field {'/'.join(keys)} must be an integer")
    return current


def fixture_contract(records: Mapping[str, Sequence[Mapping[str, Any]]]) -> dict[str, Any]:
    kinds = {
        str(require_mapping(sample, "fixture").get("kind", "synthetic-scenario-seed-proxy"))
        for profile in PROFILES
        for sample in records[profile]
    }
    if len(kinds) != 1:
        raise RunnerFailure("baseline samples did not use one fixture kind")
    kind = kinds.pop()
    if kind == "sanitized-production-shape":
        return sanitized_production_shape_fixture_contract(records)
    if kind == LOCAL_SANITIZED_AGGREGATE_FIXTURE_KIND:
        return local_sanitized_aggregate_fixture_contract(records)
    if kind != "synthetic-scenario-seed-proxy":
        raise RunnerFailure(f"baseline fixture kind is unsupported: {kind}")
    return synthetic_fixture_contract(records)


def synthetic_fixture_contract(records: Mapping[str, Sequence[Mapping[str, Any]]]) -> dict[str, int | str]:
    fields = ("baseRows", "fixedHotLogRows", "logPayloadCharacters", "coldHistoryMultiplier", "coldHistoryRows")
    profile_values: dict[str, dict[str, int]] = {}
    for profile in PROFILES:
        values: dict[str, int] = {}
        for field in fields:
            samples = {nested_integer(sample, "fixture", field) for sample in records[profile]}
            if len(samples) != 1:
                raise RunnerFailure(f"{profile} samples do not share fixture field {field}")
            values[field] = samples.pop()
        profile_values[profile] = values
    current = profile_values["current"]
    cold = profile_values["cold10x"]
    for field in ("baseRows", "fixedHotLogRows", "logPayloadCharacters"):
        if cold[field] != current[field]:
            raise RunnerFailure(f"cold10x fixture field {field} diverged from current")
    if current["coldHistoryMultiplier"] != 1 or cold["coldHistoryMultiplier"] != 10:
        raise RunnerFailure("fixture profiles must use cold-history multipliers 1 and 10")
    if current["coldHistoryRows"] != current["baseRows"]:
        raise RunnerFailure("current fixture cold-history row count must equal its base row count")
    if cold["coldHistoryRows"] != current["coldHistoryRows"] * 10:
        raise RunnerFailure("cold10x fixture must contain exactly ten times current cold-history rows")
    return {
        "kind": "synthetic-scenario-seed-proxy",
        "baseRows": current["baseRows"],
        "fixedHotLogRows": current["fixedHotLogRows"],
        "logPayloadCharacters": current["logPayloadCharacters"],
        "currentColdHistoryRows": current["coldHistoryRows"],
        "cold10xColdHistoryRows": cold["coldHistoryRows"],
        "coldHistoryRatio": 10,
    }


def sanitized_production_shape_fixture_contract(records: Mapping[str, Sequence[Mapping[str, Any]]]) -> dict[str, Any]:
    profile_values: dict[str, dict[str, Any]] = {}
    for profile in PROFILES:
        samples = records[profile]
        values: dict[str, Any] = {}
        for field in ("baseRows", "fixedHotLogRows", "coldHistoryMultiplier", "coldHistoryRows"):
            samples_for_field = {nested_integer(sample, "fixture", field) for sample in samples}
            if len(samples_for_field) != 1:
                raise RunnerFailure(f"{profile} samples do not share fixture field {field}")
            values[field] = samples_for_field.pop()
        manifest_hashes = [require_mapping(sample, "fixture").get("manifestSha256") for sample in samples]
        if (
            not all(isinstance(value, str) and re.fullmatch(r"[0-9a-f]{64}", value) for value in manifest_hashes)
            or len(set(manifest_hashes)) != 1
        ):
            raise RunnerFailure(f"{profile} samples do not share a valid production-shape manifest SHA-256")
        values["manifestSha256"] = manifest_hashes[0]
        payload_sizes = [require_mapping(sample, "fixture").get("payloadSizeBytes") for sample in samples]
        if not all(isinstance(value, Mapping) for value in payload_sizes) or any(value != payload_sizes[0] for value in payload_sizes[1:]):
            raise RunnerFailure(f"{profile} samples do not share production-shape payload-size assumptions")
        values["payloadSizeBytes"] = payload_sizes[0]
        for sample in samples:
            fixture = require_mapping(sample, "fixture")
            if fixture.get("profile") != profile:
                raise RunnerFailure(f"{profile} raw fixture reported a different profile")
        profile_values[profile] = values
    current = profile_values["current"]
    cold = profile_values["cold10x"]
    for field in ("baseRows", "fixedHotLogRows", "manifestSha256", "payloadSizeBytes"):
        if cold[field] != current[field]:
            raise RunnerFailure(f"cold10x production-shape fixture field {field} diverged from current")
    if current["coldHistoryMultiplier"] != 1 or cold["coldHistoryMultiplier"] != 10:
        raise RunnerFailure("production-shape fixture profiles must use cold-history multipliers 1 and 10")
    if current["coldHistoryRows"] != current["baseRows"]:
        raise RunnerFailure("current production-shape fixture cold-history row count must equal its base row count")
    if cold["coldHistoryRows"] != current["coldHistoryRows"] * 10:
        raise RunnerFailure("cold10x production-shape fixture must contain exactly ten times current cold-history rows")
    return {
        "kind": "sanitized-production-shape",
        "manifestSha256": current["manifestSha256"],
        "baseRows": current["baseRows"],
        "fixedHotLogRows": current["fixedHotLogRows"],
        "payloadSizeBytes": current["payloadSizeBytes"],
        "currentColdHistoryRows": current["coldHistoryRows"],
        "cold10xColdHistoryRows": cold["coldHistoryRows"],
        "coldHistoryRatio": 10,
    }


def local_sanitized_aggregate_fixture_contract(records: Mapping[str, Sequence[Mapping[str, Any]]]) -> dict[str, Any]:
    profile_values: dict[str, dict[str, Any]] = {}
    for profile in PROFILES:
        samples = records[profile]
        values: dict[str, Any] = {}
        for field in ("baseRows", "fixedHotLogRows", "coldHistoryMultiplier", "coldHistoryRows"):
            samples_for_field = {nested_integer(sample, "fixture", field) for sample in samples}
            if len(samples_for_field) != 1:
                raise RunnerFailure(f"{profile} samples do not share local fixture field {field}")
            values[field] = samples_for_field.pop()
        for field in ("policyId", "policySha256"):
            field_values = {require_mapping(sample, "fixture").get(field) for sample in samples}
            if len(field_values) != 1:
                raise RunnerFailure(f"{profile} samples do not share local fixture field {field}")
            values[field] = field_values.pop()
        if not isinstance(values["policyId"], str) or not re.fullmatch(r"op123-local-sanitized-aggregate-v[0-9]+", values["policyId"]):
            raise RunnerFailure(f"{profile} local fixture policy id is invalid")
        if not isinstance(values["policySha256"], str) or not re.fullmatch(r"[0-9a-f]{64}", values["policySha256"]):
            raise RunnerFailure(f"{profile} local fixture policy SHA-256 is invalid")
        payload_sizes = [require_mapping(sample, "fixture").get("payloadSizeBytes") for sample in samples]
        if not all(isinstance(value, Mapping) for value in payload_sizes) or any(value != payload_sizes[0] for value in payload_sizes[1:]):
            raise RunnerFailure(f"{profile} samples do not share local payload-size assumptions")
        values["payloadSizeBytes"] = payload_sizes[0]
        profile_values[profile] = values
    current = profile_values["current"]
    cold = profile_values["cold10x"]
    for field in ("baseRows", "fixedHotLogRows", "policyId", "policySha256", "payloadSizeBytes"):
        if cold[field] != current[field]:
            raise RunnerFailure(f"cold10x local fixture field {field} diverged from current")
    if current["coldHistoryMultiplier"] != 1 or cold["coldHistoryMultiplier"] != 10:
        raise RunnerFailure("local fixture profiles must use cold-history multipliers 1 and 10")
    if current["coldHistoryRows"] != current["baseRows"]:
        raise RunnerFailure("current local fixture cold-history row count must equal its base row count")
    if cold["coldHistoryRows"] != current["coldHistoryRows"] * 10:
        raise RunnerFailure("cold10x local fixture must contain exactly ten times current cold-history rows")
    return {
        "kind": LOCAL_SANITIZED_AGGREGATE_FIXTURE_KIND,
        "policyId": current["policyId"],
        "policySha256": current["policySha256"],
        "baseRows": current["baseRows"],
        "fixedHotLogRows": current["fixedHotLogRows"],
        "payloadSizeBytes": current["payloadSizeBytes"],
        "currentColdHistoryRows": current["coldHistoryRows"],
        "cold10xColdHistoryRows": cold["coldHistoryRows"],
        "coldHistoryRatio": 10,
    }


def image_contract(records: Mapping[str, Sequence[Mapping[str, Any]]]) -> dict[str, str]:
    image_keys = ("probeTag", "probeId", "postgresTag", "postgresId")
    identities = {
        tuple((key, str(require_mapping(sample, "images")[key])) for key in image_keys)
        for profile in PROFILES
        for sample in records[profile]
    }
    if len(identities) != 1:
        raise RunnerFailure("baseline samples did not use one stable probe and Postgres image identity")
    return dict(identities.pop())


def expected_sample_stems() -> tuple[str, ...]:
    return tuple(f"{profile}-{index}" for profile in PROFILES for index in range(1, SAMPLES_PER_PROFILE + 1))


def resolve_existing_path(path: Path, label: str) -> Path:
    try:
        return path.resolve(strict=True)
    except (OSError, RuntimeError) as exc:
        raise RunnerFailure(f"baseline analysis {label} could not be resolved: {path}") from exc


def require_path_within(path: Path, root: Path, label: str) -> Path:
    resolved = resolve_existing_path(path, label)
    try:
        resolved.relative_to(root)
    except ValueError as exc:
        raise RunnerFailure(f"baseline analysis {label} escapes run directory: {path}") from exc
    return resolved


def require_real_child_directory(run_directory: Path, name: str) -> Path:
    path = run_directory / name
    if path.is_symlink():
        raise RunnerFailure(f"baseline analysis {name} directory must not be a symlink: {path}")
    if not path.is_dir():
        raise RunnerFailure(f"baseline analysis requires a real {name} directory: {path}")
    resolved = require_path_within(path, run_directory, f"{name} directory")
    if resolved.parent != run_directory:
        raise RunnerFailure(f"baseline analysis {name} directory must be a direct run-directory child: {path}")
    return resolved


def artifact_names(directory: Path, suffix: str) -> set[str]:
    return {
        path.name
        for path in directory.iterdir()
        if path.name.endswith(suffix) and (path.is_file() or path.is_symlink())
    }


def require_source_file(path: Path, directory: Path, run_directory: Path, label: str) -> Path:
    if path.is_symlink():
        raise RunnerFailure(f"baseline analysis source artifact must not be a symlink: {path}")
    if not path.is_file():
        raise RunnerFailure(f"baseline analysis requires a regular source artifact: {path}")
    resolved = require_path_within(path, run_directory, label)
    if resolved.parent != directory:
        raise RunnerFailure(f"baseline analysis source artifact escapes its expected directory: {path}")
    return resolved


def require_safe_derived_target(target: Path, parent: Path, run_directory: Path, label: str) -> None:
    if target.parent != parent:
        raise RunnerFailure(f"baseline analysis derived target has an unexpected parent: {target}")
    if parent.is_symlink() or not parent.is_dir():
        raise RunnerFailure(f"baseline analysis derived target parent is unsafe: {parent}")
    parent_resolved = require_path_within(parent, run_directory, f"{label} parent")
    if target.is_symlink():
        raise RunnerFailure(f"baseline analysis derived target must not be a symlink: {target}")
    if target.exists() and not target.is_file():
        raise RunnerFailure(f"baseline analysis derived target must be a file or absent: {target}")
    if parent_resolved != parent:
        raise RunnerFailure(f"baseline analysis derived target parent is not a real directory: {parent}")


def preflight_analysis_artifacts(run_dir: Path) -> AnalysisArtifactLayout:
    candidate_run_directory = run_dir if run_dir.is_absolute() else Path.cwd() / run_dir
    if candidate_run_directory.is_symlink():
        raise RunnerFailure(f"baseline analysis run directory must not be a symlink: {candidate_run_directory}")
    if not candidate_run_directory.is_dir():
        raise RunnerFailure(f"baseline analysis run does not exist: {candidate_run_directory}")
    run_directory = resolve_existing_path(candidate_run_directory, "run directory")
    raw_directory = require_real_child_directory(run_directory, "raw")
    jfr_directory = require_real_child_directory(run_directory, "jfr")
    logs_directory = require_real_child_directory(run_directory, "logs")
    fixture_candidate = run_directory / "fixture"
    fixture_directory = (
        require_real_child_directory(run_directory, "fixture")
        if fixture_candidate.exists() or fixture_candidate.is_symlink()
        else None
    )
    manifest_candidate = run_directory / "manifest"
    manifest_directory = (
        require_real_child_directory(run_directory, "manifest")
        if manifest_candidate.exists() or manifest_candidate.is_symlink()
        else None
    )
    if (fixture_directory is None) != (manifest_directory is None):
        raise RunnerFailure("contract-backed analysis requires both recorded fixture configs and a canonical contract")
    expected_stems = expected_sample_stems()
    expected_raw_names = {f"{stem}.json" for stem in expected_stems}
    expected_jfr_names = {f"{stem}.jfr" for stem in expected_stems}
    raw_names = artifact_names(raw_directory, ".json")
    jfr_names = artifact_names(jfr_directory, ".jfr")
    if raw_names != expected_raw_names:
        raise RunnerFailure(
            f"baseline analysis requires exactly six raw artifacts, found {', '.join(sorted(raw_names))}"
        )
    if jfr_names != expected_jfr_names:
        raise RunnerFailure(
            f"baseline analysis requires exactly six JFR artifacts, found {', '.join(sorted(jfr_names))}"
        )
    source_paths: dict[str, Path] = {}
    for stem in expected_stems:
        raw_path = raw_directory / f"{stem}.json"
        jfr_path = jfr_directory / f"{stem}.jfr"
        source_paths[f"raw/{raw_path.name}"] = require_source_file(
            raw_path,
            raw_directory,
            run_directory,
            f"raw/{raw_path.name}",
        )
        source_paths[f"jfr/{jfr_path.name}"] = require_source_file(
            jfr_path,
            jfr_directory,
            run_directory,
            f"jfr/{jfr_path.name}",
        )
    manifest_path: Path | None = None
    contract_kind: str | None = None
    if manifest_directory is not None:
        manifest_names = artifact_names(manifest_directory, ".json")
        if manifest_names == {PRODUCTION_SHAPE_MANIFEST_FILE_NAME}:
            manifest_path = manifest_directory / PRODUCTION_SHAPE_MANIFEST_FILE_NAME
            contract_kind = "sanitized-production-shape"
        elif manifest_names == {LOCAL_SANITIZED_AGGREGATE_POLICY_FILE_NAME}:
            manifest_path = manifest_directory / LOCAL_SANITIZED_AGGREGATE_POLICY_FILE_NAME
            contract_kind = LOCAL_SANITIZED_AGGREGATE_FIXTURE_KIND
        else:
            raise RunnerFailure(
                "baseline analysis requires exactly one canonical supported contract, found "
                + ", ".join(sorted(manifest_names))
            )
        source_paths[f"manifest/{manifest_path.name}"] = require_source_file(
            manifest_path,
            manifest_directory,
            run_directory,
            f"manifest/{manifest_path.name}",
        )
    if fixture_directory is not None:
        expected_fixture_names = {f"{profile}.json" for profile in PROFILES}
        fixture_names = artifact_names(fixture_directory, ".json")
        if fixture_names != expected_fixture_names:
            raise RunnerFailure(
                "baseline analysis requires exactly the recorded fixture configs, found "
                + ", ".join(sorted(fixture_names))
            )
        for profile in PROFILES:
            fixture_path = fixture_directory / f"{profile}.json"
            source_paths[f"fixture/{fixture_path.name}"] = require_source_file(
                fixture_path,
                fixture_directory,
                run_directory,
                f"fixture/{fixture_path.name}",
            )
    derived_paths: dict[str, Path] = {
        "analysis": run_directory / "analysis.json",
        "summaryJson": run_directory / "summary.json",
        "summaryMarkdown": run_directory / "summary.md",
    }
    for stem in expected_stems:
        derived_paths[f"summary:{stem}"] = logs_directory / f"{stem}.jfr-summary.txt"
        derived_paths[f"pause:{stem}"] = logs_directory / f"{stem}.jfr-gc-phase-pause.json"
    for key, target in derived_paths.items():
        parent = logs_directory if target.parent == logs_directory else run_directory
        require_safe_derived_target(target, parent, run_directory, key)
    return AnalysisArtifactLayout(
        run_directory=run_directory,
        raw_directory=raw_directory,
        jfr_directory=jfr_directory,
        logs_directory=logs_directory,
        fixture_directory=fixture_directory,
        manifest_directory=manifest_directory,
        manifest_path=manifest_path,
        contract_kind=contract_kind,
        source_paths=source_paths,
        derived_paths=derived_paths,
    )


def atomic_write_text(target: Path, content: str, layout: AnalysisArtifactLayout) -> None:
    if target not in layout.derived_paths.values():
        raise RunnerFailure(f"baseline analysis refused an unplanned derived target: {target}")
    parent = layout.logs_directory if target.parent == layout.logs_directory else layout.run_directory
    require_safe_derived_target(target, parent, layout.run_directory, target.name)
    temporary_path: Path | None = None
    descriptor: int | None = None
    try:
        descriptor, temporary_name = tempfile.mkstemp(
            prefix=f".{target.name}.",
            suffix=".tmp",
            dir=parent,
            text=True,
        )
        temporary_path = Path(temporary_name)
        with os.fdopen(descriptor, "w", encoding="utf-8") as artifact:
            descriptor = None
            artifact.write(content)
        os.replace(temporary_path, target)
        temporary_path = None
    finally:
        if descriptor is not None:
            os.close(descriptor)
        if temporary_path is not None:
            temporary_path.unlink(missing_ok=True)


def read_existing_run_records(layout: AnalysisArtifactLayout) -> dict[str, list[Mapping[str, Any]]]:
    records: dict[str, list[Mapping[str, Any]]] = {profile: [] for profile in PROFILES}
    expected_fixture_configs: Mapping[str, Mapping[str, Any]] = {}
    if layout.fixture_directory is not None:
        if layout.manifest_path is None:
            raise RunnerFailure("recorded fixture configs require a canonical contract")
        if layout.contract_kind == "sanitized-production-shape":
            manifest = load_canonical_production_shape_manifest(layout.manifest_path)
            expected_fixture_configs = {
                profile: expected_recorded_fixture_config(
                    layout.fixture_directory / f"{profile}.json",
                    manifest,
                    profile,
                )
                for profile in PROFILES
            }
        elif layout.contract_kind == LOCAL_SANITIZED_AGGREGATE_FIXTURE_KIND:
            policy = load_canonical_local_sanitized_aggregate_policy(layout.manifest_path)
            expected_fixture_configs = {
                profile: expected_recorded_local_fixture_config(
                    layout.fixture_directory / f"{profile}.json",
                    policy,
                    profile,
                )
                for profile in PROFILES
            }
        else:
            raise RunnerFailure("recorded fixture configs have an unsupported contract kind")
    for profile in PROFILES:
        for index in range(1, SAMPLES_PER_PROFILE + 1):
            stem = f"{profile}-{index}"
            raw_key = f"raw/{stem}.json"
            jfr_key = f"jfr/{stem}.jfr"
            raw_path = layout.source_paths[raw_key]
            jfr_path = layout.source_paths[jfr_key]
            if jfr_path.stat().st_size == 0:
                raise RunnerFailure(f"baseline analysis found an empty JFR artifact: {jfr_path}")
            try:
                raw = json.loads(raw_path.read_text(encoding="utf-8"))
            except json.JSONDecodeError as exc:
                raise RunnerFailure(f"baseline analysis could not parse raw artifact: {raw_path}") from exc
            fixture = require_mapping(raw, "fixture")
            kind = fixture.get("kind", "synthetic-scenario-seed-proxy")
            if kind in {"sanitized-production-shape", LOCAL_SANITIZED_AGGREGATE_FIXTURE_KIND}:
                if layout.fixture_directory is None:
                    raise RunnerFailure("contract-backed raw artifacts require recorded fixture configs")
                if kind != layout.contract_kind:
                    raise RunnerFailure("raw fixture kind does not match the recorded contract")
                expected_fixture_config = expected_fixture_configs[profile]
                validate_raw(raw, profile, expected_fixture_config=expected_fixture_config)
            else:
                if layout.fixture_directory is not None:
                    raise RunnerFailure("recorded fixture configs cannot be analyzed with synthetic raw artifacts")
                validate_raw(raw, profile)
            artifacts = require_mapping(raw, "artifacts")
            if artifacts.get("jfrFile") != jfr_path.name:
                raise RunnerFailure(f"raw artifact {raw_path} does not reference its expected JFR file")
            records[profile].append(raw)
    return records


def source_artifact_hashes(layout: AnalysisArtifactLayout) -> dict[str, str]:
    hashes: dict[str, str] = {}
    for relative, path in layout.source_paths.items():
        if relative.startswith("raw/"):
            directory = layout.raw_directory
        elif relative.startswith("jfr/"):
            directory = layout.jfr_directory
        elif relative.startswith("fixture/") and layout.fixture_directory is not None:
            directory = layout.fixture_directory
        elif relative.startswith("manifest/") and layout.manifest_directory is not None:
            directory = layout.manifest_directory
        else:
            raise RunnerFailure(f"baseline analysis found an unrecognized source artifact: {relative}")
        require_source_file(path, directory, layout.run_directory, relative)
        hashes[relative] = file_sha256(path)
    return hashes


def analyze_run_artifacts(
    run_dir: Path,
    jfr_tool: Path,
) -> tuple[dict[str, list[Mapping[str, Any]]], dict[str, Mapping[str, Any]]]:
    layout = preflight_analysis_artifacts(run_dir)
    records = read_existing_run_records(layout)
    source_hashes = source_artifact_hashes(layout)
    analysis_samples: dict[str, Mapping[str, Any]] = {}
    for profile in PROFILES:
        for index in range(1, SAMPLES_PER_PROFILE + 1):
            stem = f"{profile}-{index}"
            jfr_path = layout.source_paths[f"jfr/{stem}.jfr"]
            summary_path = layout.derived_paths[f"summary:{stem}"]
            pause_json_path = layout.derived_paths[f"pause:{stem}"]
            validate_jfr_file(jfr_tool, jfr_path, summary_path, layout)
            pause_metrics, pause_durations = analyze_jfr_gc_phase_pause(jfr_tool, jfr_path, pause_json_path, layout)
            analysis_samples[stem] = {
                "profile": profile,
                "rawFile": f"raw/{stem}.json",
                "jfrFile": f"jfr/{stem}.jfr",
                "jfrSummaryFile": f"logs/{summary_path.name}",
                "jfrGcPhasePauseJsonFile": f"logs/{pause_json_path.name}",
                "jfrGcPhasePause": pause_metrics,
                "jfrGcPhasePauseDurationsMillis": pause_durations,
            }
    write_analysis(layout, analysis_samples, source_hashes)
    write_summary(layout, records, analysis_samples)
    if source_hashes != source_artifact_hashes(layout):
        raise RunnerFailure("baseline source artifacts changed during derived analysis")
    return records, analysis_samples


def gc_phase_pause_profile_metrics(
    profile: str,
    analysis_samples: Mapping[str, Mapping[str, Any]],
) -> dict[str, dict[str, int | float]]:
    durations_by_bucket: dict[str, list[float]] = {
        "operational": [],
        "forcedRetainedHeapProbe": [],
    }
    for index in range(1, SAMPLES_PER_PROFILE + 1):
        stem = f"{profile}-{index}"
        sample = analysis_samples.get(stem)
        if not isinstance(sample, Mapping) or sample.get("profile") != profile:
            raise RunnerFailure(f"baseline analysis omitted {stem}")
        sample_durations = sample.get("jfrGcPhasePauseDurationsMillis")
        sample_metrics = sample.get("jfrGcPhasePause")
        if not isinstance(sample_durations, Mapping) or not isinstance(sample_metrics, Mapping):
            raise RunnerFailure(f"baseline analysis omitted GC pause details for {stem}")
        for bucket, profile_durations in durations_by_bucket.items():
            values = sample_durations.get(bucket)
            metrics = sample_metrics.get(bucket)
            if not isinstance(values, list) or any(not isinstance(duration, (int, float)) for duration in values):
                raise RunnerFailure(f"baseline analysis omitted numeric {bucket} GC pause durations for {stem}")
            expected_metrics = gc_phase_pause_metrics([float(duration) for duration in values])
            if metrics != expected_metrics:
                raise RunnerFailure(f"baseline analysis {bucket} GC pause metrics do not match durations for {stem}")
            profile_durations.extend(float(duration) for duration in values)
    return {bucket: gc_phase_pause_metrics(values) for bucket, values in durations_by_bucket.items()}


def build_summary(
    records: Mapping[str, Sequence[Mapping[str, Any]]],
    analysis_samples: Mapping[str, Mapping[str, Any]],
) -> dict[str, Any]:
    profile_summary: dict[str, Any] = {}
    for profile in PROFILES:
        samples = records[profile]
        if len(samples) != SAMPLES_PER_PROFILE:
            raise RunnerFailure(f"summary requires {SAMPLES_PER_PROFILE} {profile} samples, found {len(samples)}")
        fixture_hashes = {str(require_mapping(sample, "fixture").get("sha256")) for sample in samples}
        if len(fixture_hashes) != 1:
            raise RunnerFailure(f"{profile} samples do not share a deterministic fixture hash")
        gc_phase_pause = gc_phase_pause_profile_metrics(profile, analysis_samples)
        profile_summary[profile] = {
            "n": len(samples),
            "fixtureSha256": fixture_hashes.pop(),
            "metrics": {
                "bootDurationMs": summary_metric(
                    [nested_integer(sample, "durations", "bootDurationMs") for sample in samples], True
                ),
                "snapshotDurationMs": summary_metric(
                    [nested_integer(sample, "durations", "snapshotDurationMs") for sample in samples], True
                ),
                "tickDurationMs": summary_metric(
                    [nested_integer(sample, "durations", "tickDurationMs") for sample in samples], True
                ),
                "retainedHeapAfterGcBytes": summary_metric(
                    [nested_integer(sample, "memory", "heapAfterGc", "usedBytes") for sample in samples]
                ),
                "rssAfterGcBytes": summary_metric(
                    [nested_integer(sample, "memory", "rssAfterGcBytes") for sample in samples]
                ),
                "gcCollectionTimeProxyMillis": summary_metric(
                    [nested_integer(sample, "gc", "collectionTimeDeltaMillis") for sample in samples]
                ),
                "jfrGcPhasePause": gc_phase_pause,
            },
            "rawFiles": [f"raw/{profile}-{index}.json" for index in range(1, SAMPLES_PER_PROFILE + 1)],
        }
    current_retained = profile_summary["current"]["metrics"]["retainedHeapAfterGcBytes"]["mean"]
    cold_retained = profile_summary["cold10x"]["metrics"]["retainedHeapAfterGcBytes"]["mean"]
    cross_profile_contract = fixture_contract(records)
    fixture_kind = cross_profile_contract["kind"]
    fixture_summary: dict[str, Any] = {
        "label": (
            "sanitized production-shape aggregate fixture; not production data"
            if fixture_kind == "sanitized-production-shape"
            else "checked-in deterministic local sanitized aggregate surrogate; no production, live, or seeded-db observation"
            if fixture_kind == LOCAL_SANITIZED_AGGREGATE_FIXTURE_KIND
            else "synthetic production-shaped seed proxy; not production data or a sanitized production shape"
        ),
        "profiles": list(PROFILES),
        "samplesPerProfile": SAMPLES_PER_PROFILE,
        "crossProfileContract": cross_profile_contract,
    }
    if fixture_kind == "sanitized-production-shape":
        fixture_summary["manifestSha256"] = cross_profile_contract["manifestSha256"]
    if fixture_kind == LOCAL_SANITIZED_AGGREGATE_FIXTURE_KIND:
        fixture_summary["policyId"] = cross_profile_contract["policyId"]
        fixture_summary["policySha256"] = cross_profile_contract["policySha256"]
    boot_includes = [
        "freshPostgresFlywayMigration",
        "localSanitizedAggregateMaterialization",
        "worldSnapshotLoad",
        "inMemoryTurnWorldConstruction",
    ] if fixture_kind == LOCAL_SANITIZED_AGGREGATE_FIXTURE_KIND else [
        "freshPostgresFlywayMigration",
        "scenarioSeed",
        "profileFixtureInsert",
        "worldSnapshotLoad",
        "inMemoryTurnWorldConstruction",
    ]
    return {
        "schemaVersion": "cqrs-runtime-baseline.summary.v2",
        "fixture": fixture_summary,
        "probe": {
            "cgroupMemoryBytes": REQUIRED_CGROUP_BYTES,
            "jvmArguments": REQUIRED_JVM_ARGS,
            "threeRunMetricPercentileMethod": "linear interpolation over sorted three-sample values",
            "jfrGcPhasePausePercentileMethod": (
                "linear interpolation over pooled per-event pause durations within each profile; event-weighted"
            ),
            "bootDurationMetric": {
                "scope": "harnessSetupAndBoot",
                "includes": boot_includes,
            },
            "gcCollectionTimeMetric": (
                "MXBean collection-time proxy; JFR GCPhasePause metrics are reported separately"
            ),
            "jfrGcPhasePauseMetric": (
                "Host JDK JFR JSON duration metrics split by GarbageCollection cause; operational excludes System.gc()"
            ),
        },
        "images": image_contract(records),
        "profiles": profile_summary,
        "retainedHeapComparison": {
            "currentMeanBytes": current_retained,
            "cold10xMeanBytes": cold_retained,
            "deltaBytes": cold_retained - current_retained,
            "deltaPercentOfCurrent": (
                None if current_retained == 0 else (cold_retained - current_retained) * 100 / current_retained
            ),
        },
    }


def format_number(value: float) -> str:
    return f"{value:.2f}"


def summary_markdown(summary: Mapping[str, Any]) -> str:
    profiles = require_mapping(summary, "profiles")
    fixture = require_mapping(summary, "fixture")
    fixture_label = fixture.get("label")
    if not isinstance(fixture_label, str):
        raise RunnerFailure("summary fixture label must be a string")
    lines = [
        "# CQRS runtime baseline",
        "",
        fixture_label,
        "",
        (
            "| Profile | n | Harness setup+boot p50 / p95 ms | Tick p50 / p95 ms | Retained heap mean bytes | "
            "Operational GC pause p50 / p95 / max ms | Forced-probe GC pause p50 / p95 / max ms |"
        ),
        "| --- | ---: | ---: | ---: | ---: | ---: | ---: |",
    ]
    for profile in PROFILES:
        detail = require_mapping(profiles, profile)
        metrics = require_mapping(detail, "metrics")
        boot = require_mapping(metrics, "bootDurationMs")
        tick = require_mapping(metrics, "tickDurationMs")
        retained = require_mapping(metrics, "retainedHeapAfterGcBytes")
        gc_pause = require_mapping(metrics, "jfrGcPhasePause")
        operational_pause = require_mapping(gc_pause, "operational")
        forced_pause = require_mapping(gc_pause, "forcedRetainedHeapProbe")
        lines.append(
            "| "
            + profile
            + " | "
            + str(detail["n"])
            + " | "
            + f"{format_number(float(boot['p50']))} / {format_number(float(boot['p95']))}"
            + " | "
            + f"{format_number(float(tick['p50']))} / {format_number(float(tick['p95']))}"
            + " | "
            + format_number(float(retained["mean"]))
            + " | "
            + " / ".join(
                format_number(float(operational_pause[key]))
                for key in ("p50DurationMillis", "p95DurationMillis", "maxDurationMillis")
            )
            + " | "
            + " / ".join(
                format_number(float(forced_pause[key]))
                for key in ("p50DurationMillis", "p95DurationMillis", "maxDurationMillis")
            )
            + " |"
        )
    comparison = require_mapping(summary, "retainedHeapComparison")
    lines.extend(
        [
            "",
            f"Retained heap delta (cold10x - current): {format_number(float(comparison['deltaBytes']))} bytes "
            f"({format_number(float(comparison['deltaPercentOfCurrent'] or 0.0))}%).",
            "",
            (
                "`heapAfterGc.usedBytes` is a retained-heap proxy after explicit `MemoryMXBean.gc()`, "
                "not an object-retention proof."
            ),
            (
                "`jfrGcPhasePause.operational` is host-JDK postprocessed JFR pause duration evidence and excludes "
                "the explicit retained-heap `System.gc()` probes; the forced-probe metrics are reported separately."
            ),
        ]
    )
    return "\n".join(lines) + "\n"


def write_analysis(
    layout: AnalysisArtifactLayout,
    analysis_samples: Mapping[str, Mapping[str, Any]],
    source_hashes: Mapping[str, str],
) -> None:
    analysis = {
        "schemaVersion": ANALYSIS_SCHEMA_VERSION,
        "jfrEvents": [JFR_GC_PHASE_PAUSE_EVENT, JFR_GARBAGE_COLLECTION_EVENT],
        "forcedGcCause": FORCED_GC_CAUSE,
        "sourceArtifactSha256": source_hashes,
        "samples": analysis_samples,
    }
    atomic_write_text(layout.derived_paths["analysis"], json.dumps(analysis, indent=2) + "\n", layout)


def write_summary(
    layout: AnalysisArtifactLayout,
    records: Mapping[str, Sequence[Mapping[str, Any]]],
    analysis_samples: Mapping[str, Mapping[str, Any]],
) -> None:
    summary = build_summary(records, analysis_samples)
    atomic_write_text(layout.derived_paths["summaryJson"], json.dumps(summary, indent=2) + "\n", layout)
    atomic_write_text(layout.derived_paths["summaryMarkdown"], summary_markdown(summary), layout)


def default_run_id() -> str:
    return dt.datetime.now(dt.UTC).strftime("%Y%m%dT%H%M%SZ") + f"-{os.getpid()}"


def validate_run_id(run_id: str) -> str:
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_-]{0,63}", run_id):
        raise RunnerFailure("run-id must be 1-64 alphanumeric, underscore, or hyphen characters")
    return run_id


def main() -> int:
    arguments = parse_args()
    local_policy_path = getattr(arguments, "local_sanitized_aggregate_policy", None)
    production_shape_manifest = getattr(arguments, "production_shape_manifest", None)
    validate_production_shape_manifest_path = getattr(arguments, "validate_production_shape_manifest", None)
    if arguments.run_id and arguments.analyze_run_id:
        raise RunnerFailure("--run-id and --analyze-run-id cannot be used together")
    if production_shape_manifest and validate_production_shape_manifest_path:
        raise RunnerFailure("--production-shape-manifest and --validate-production-shape-manifest cannot be used together")
    if local_policy_path and (production_shape_manifest or validate_production_shape_manifest_path):
        raise RunnerFailure("--local-sanitized-aggregate-policy cannot be combined with production-shape arguments")
    if validate_production_shape_manifest_path:
        if arguments.run_id or arguments.analyze_run_id or arguments.base_rows is not None:
            raise RunnerFailure("manifest validation cannot be combined with measurement arguments")
        manifest = load_validated_production_shape_manifest(validate_production_shape_manifest_path)
        print(f"Production-shape manifest valid: sha256={manifest.sha256}")
        return 0
    if production_shape_manifest:
        raise RunnerFailure(
            "sanitized production-shape capture is blocked: scenario_1010 seed proxy cannot materialize an approved sanitized shape; "
            "use --validate-production-shape-manifest until a deterministic sanitized materializer or approved sanitized restore exists"
        )
    local_policy: LocalSanitizedAggregatePolicy | None = None
    if local_policy_path:
        if arguments.analyze_run_id or arguments.base_rows is not None:
            raise RunnerFailure("local sanitized aggregate policy cannot be combined with analysis-only or --base-rows arguments")
        local_policy = load_bound_local_sanitized_aggregate_policy(local_policy_path)
        validate_local_sanitized_aggregate_feasibility(local_policy)
    base_rows = arguments.base_rows if arguments.base_rows is not None else 10_000
    output_root = require_within_build(arguments.output_dir)
    if arguments.analyze_run_id:
        run_id = validate_run_id(arguments.analyze_run_id)
        run_dir = output_root / run_id
        require_within_build(run_dir)
        if not run_dir.is_dir():
            raise RunnerFailure(f"baseline analysis run does not exist: {run_dir}")
        host_java_home, _ = resolve_host_jdk21()
        jfr_tool = host_java_home / "bin" / "jfr"
        if not jfr_tool.is_file():
            raise RunnerFailure(f"JDK 21 did not provide the jfr tool: {jfr_tool}")
        analyze_run_artifacts(run_dir, jfr_tool)
        print(f"Baseline derived analysis written to {run_dir}")
        return 0
    if base_rows <= 0:
        raise RunnerFailure("--base-rows must be positive")
    run_id = validate_run_id(arguments.run_id or default_run_id())
    run_dir = require_within_build(output_root / run_id)
    if run_dir.exists():
        raise RunnerFailure(f"refusing to overwrite an existing baseline run: {run_dir}")
    for directory in (run_dir / "raw", run_dir / "jfr", run_dir / "logs"):
        directory.mkdir(parents=True, exist_ok=False)
    fixture_config_paths: Mapping[str, Path] = {}
    if local_policy is not None:
        fixture_config_paths = write_local_sanitized_aggregate_run_contract(local_policy, run_dir)
    network = docker_name(run_id, "network")
    network_created = False
    try:
        host_java_home, _ = resolve_host_jdk21()
        jfr_tool = host_java_home / "bin" / "jfr"
        if not jfr_tool.is_file():
            raise RunnerFailure(f"JDK 21 did not provide the jfr tool: {jfr_tool}")
        images = ensure_docker_and_images()
        jar = build_baseline_jar(run_dir, host_java_home)
        command_output(["docker", "network", "create", *docker_label_args(run_id), network])
        network_created = True
        for profile in PROFILES:
            for sample in range(1, SAMPLES_PER_PROFILE + 1):
                print(f"Running {profile} sample {sample}/{SAMPLES_PER_PROFILE}")
                run_probe(
                    jar=jar,
                    run_dir=run_dir,
                    network=network,
                    run_id=run_id,
                    profile=profile,
                    sample=sample,
                    base_rows=base_rows,
                    images=images,
                    fixture_config_path=fixture_config_paths.get(profile),
                )
        analyze_run_artifacts(run_dir, jfr_tool)
        print(f"Baseline artifacts written to {run_dir}")
        return 0
    finally:
        if network_created:
            command_output(["docker", "network", "rm", network], check=False)


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except RunnerFailure as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        raise SystemExit(1)
