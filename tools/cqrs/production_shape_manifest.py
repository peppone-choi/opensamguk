from __future__ import annotations

import copy
import datetime as dt
import hashlib
import json
import re
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Mapping


MANIFEST_SCHEMA_VERSION = "cqrs-production-shape-manifest.v3"
FIXTURE_CONFIG_SCHEMA_VERSION = "cqrs-runtime-baseline.fixture-config.v3"
LOCAL_SANITIZED_AGGREGATE_POLICY_SCHEMA_VERSION = "cqrs-local-sanitized-aggregate-policy.v1"
LOCAL_SANITIZED_AGGREGATE_FIXTURE_CONFIG_SCHEMA_VERSION = "cqrs-runtime-baseline.local-sanitized-aggregate-config.v1"
LOCAL_SANITIZED_AGGREGATE_FIXTURE_KIND = "local-sanitized-aggregate-surrogate"
LOCAL_SANITIZED_AGGREGATE_SOURCE_CLASS = "local-deterministic-policy"
LOCAL_SANITIZED_AGGREGATE_PROVENANCE = (
    "checked-in deterministic local aggregate policy; no production, live, or seeded-db observation"
)
PAYLOAD_BYTE_SEMANTICS = "selected-loader-fields-postgres-text-bytes.v1"
LOADER_INPUT_INVENTORY_SCHEMA_VERSION = "cqrs-loader-input-inventory.v2"
LOADER_INPUT_INVENTORY_PATH = (
    Path(__file__).resolve().parents[2]
    / "app/game-engine/src/baseline/resources/opensamguk/engine/baseline/loader-input-inventory.json"
)
REQUIRED_TABLE_CARDINALITIES = (
    "worldState",
    "city",
    "nation",
    "general",
    "diplomacy",
    "rankData",
    "logEntry",
)
REQUIRED_SNAPSHOT_CARDINALITIES = (
    "generals",
    "cities",
    "nations",
    "diplomacy",
    "accessLogs",
    "globalLogs",
    "nationHistoryEntries",
    "generalHistoryEntries",
)
SOURCE_CLASSES = frozenset(
    {
        "ci-aggregate-record",
        "backup-aggregate-query",
        "database-aggregate-query",
        "operator-reviewed-aggregate-query",
    }
)
SHA256_PATTERN = re.compile(r"^[0-9a-f]{64}$")
MAX_CARDINALITY = 2**31 - 1
MAX_PAYLOAD_BYTES = 1024 * 1024


class ManifestValidationError(ValueError):
    pass


def _inventory_no_duplicate_keys(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise ManifestValidationError(f"loader-input inventory repeats JSON key {key!r}")
        result[key] = value
    return result


def _load_loader_input_inventory() -> tuple[tuple[str, ...], str, Mapping[str, Mapping[str, tuple[str, ...]]]]:
    try:
        raw = json.loads(
            LOADER_INPUT_INVENTORY_PATH.read_text(encoding="utf-8"),
            object_pairs_hook=_inventory_no_duplicate_keys,
        )
    except (OSError, json.JSONDecodeError) as exc:
        raise ManifestValidationError("loader-input inventory is not readable canonical JSON") from exc
    if not isinstance(raw, Mapping) or set(raw) != {"schemaVersion", "payloadByteSemantics", "inputs"}:
        raise ManifestValidationError("loader-input inventory has an unexpected field set")
    if (
        raw["schemaVersion"] != LOADER_INPUT_INVENTORY_SCHEMA_VERSION
        or raw["payloadByteSemantics"] != PAYLOAD_BYTE_SEMANTICS
        or not isinstance(raw["inputs"], list)
    ):
        raise ManifestValidationError("loader-input inventory schema is unsupported")
    ids: list[str] = []
    observations: dict[str, Mapping[str, tuple[str, ...]]] = {}
    for index, value in enumerate(raw["inputs"]):
        if not isinstance(value, Mapping):
            raise ManifestValidationError(f"loader-input inventory inputs[{index}] must be an object")
        input_value = value
        if set(input_value) != {"id", "source", "retainedAs", "loaderColumns", "payloadColumns"}:
            raise ManifestValidationError(f"loader-input inventory inputs[{index}] has an unexpected field set")
        input_id = input_value["id"]
        if not isinstance(input_id, str) or not re.fullmatch(r"[A-Za-z][A-Za-z0-9]*", input_id):
            raise ManifestValidationError(f"loader-input inventory inputs[{index}].id is invalid")
        if not all(isinstance(input_value[key], str) and input_value[key] for key in ("source", "retainedAs")):
            raise ManifestValidationError(f"loader-input inventory inputs[{index}] descriptions are invalid")
        loader_columns = _validate_inventory_columns(input_value["loaderColumns"], f"inputs[{index}].loaderColumns")
        payload_columns = _validate_inventory_columns(input_value["payloadColumns"], f"inputs[{index}].payloadColumns")
        if not set(payload_columns).issubset(loader_columns):
            raise ManifestValidationError(f"loader-input inventory inputs[{index}] payload columns are not loader columns")
        ids.append(input_id)
        observations[input_id] = {
            "loaderColumns": loader_columns,
            "payloadColumns": payload_columns,
        }
    if not ids or len(set(ids)) != len(ids):
        raise ManifestValidationError("loader-input inventory must contain unique input ids")
    return tuple(ids), hashlib.sha256(LOADER_INPUT_INVENTORY_PATH.read_bytes()).hexdigest(), observations


def _validate_inventory_columns(value: Any, label: str) -> tuple[str, ...]:
    if not isinstance(value, list) or not value or any(not isinstance(column, str) for column in value):
        raise ManifestValidationError(f"loader-input inventory {label} must be a non-empty string list")
    if any(not re.fullmatch(r"[a-z][a-z0-9_]*", column) for column in value):
        raise ManifestValidationError(f"loader-input inventory {label} has an invalid column name")
    if len(set(value)) != len(value):
        raise ManifestValidationError(f"loader-input inventory {label} repeats a column")
    return tuple(value)


REQUIRED_LOADER_INPUT_IDS, LOADER_INPUT_INVENTORY_SHA256, REQUIRED_LOADER_INPUT_OBSERVATIONS = _load_loader_input_inventory()
REQUIRED_PROVENANCE_DIMENSIONS = frozenset(
    {
        *(f"tables.{name}" for name in REQUIRED_TABLE_CARDINALITIES),
        *(f"snapshot.{name}" for name in REQUIRED_SNAPSHOT_CARDINALITIES),
        *(f"loaderInputs.{name}" for name in REQUIRED_LOADER_INPUT_IDS),
        "fixture.current.fixedHotActionRows",
        "fixture.current.coldHistoryRows",
        "fixture.payloadSizeBytes.hotAction",
        "fixture.payloadSizeBytes.coldHistory",
    }
)


@dataclass(frozen=True)
class FixtureProfile:
    table_cardinalities: Mapping[str, int]
    snapshot_cardinalities: Mapping[str, int]
    loader_inputs: Mapping[str, "LoaderInputMetrics"]
    fixed_hot_action_rows: int
    cold_history_rows: int
    payload_size_bytes: Mapping[str, int]


@dataclass(frozen=True)
class ProductionShapeManifest:
    sha256: str
    observed_at: str
    profiles: Mapping[str, FixtureProfile]
    loader_input_inventory_sha256: str
    payload_byte_semantics: str
    canonical_document: Mapping[str, Any]


@dataclass(frozen=True)
class LocalSanitizedAggregatePolicy:
    sha256: str
    policy_id: str
    fixture_kind: str
    profiles: Mapping[str, FixtureProfile]
    loader_input_inventory_sha256: str
    payload_byte_semantics: str
    canonical_document: Mapping[str, Any]


@dataclass(frozen=True)
class LoaderInputMetrics:
    source_rows: int
    retained_items: int
    payload_bytes: int


def required_loader_input_ids() -> tuple[str, ...]:
    return REQUIRED_LOADER_INPUT_IDS


def loader_input_inventory_sha256() -> str:
    return LOADER_INPUT_INVENTORY_SHA256


def loader_input_observation_contract() -> dict[str, Any]:
    return {
        "payloadByteSemantics": PAYLOAD_BYTE_SEMANTICS,
        "inputs": {
            input_id: {
                "loaderColumns": list(REQUIRED_LOADER_INPUT_OBSERVATIONS[input_id]["loaderColumns"]),
                "payloadColumns": list(REQUIRED_LOADER_INPUT_OBSERVATIONS[input_id]["payloadColumns"]),
            }
            for input_id in REQUIRED_LOADER_INPUT_IDS
        },
    }


def canonical_json_bytes(value: Mapping[str, Any]) -> bytes:
    return json.dumps(value, ensure_ascii=True, separators=(",", ":"), sort_keys=True).encode("utf-8")


def canonical_manifest_bytes(value: Mapping[str, Any]) -> bytes:
    return canonical_json_bytes(value) + b"\n"


def manifest_sha256(value: Mapping[str, Any]) -> str:
    body = copy.deepcopy(dict(value))
    body.pop("sha256", None)
    return hashlib.sha256(canonical_json_bytes(body)).hexdigest()


def load_production_shape_manifest(path: Path) -> ProductionShapeManifest:
    if path.is_symlink() or not path.is_file():
        raise ManifestValidationError("production-shape manifest must be a regular non-symlink file")
    try:
        raw = json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=_no_duplicate_keys)
    except (OSError, json.JSONDecodeError) as exc:
        raise ManifestValidationError("production-shape manifest is not readable canonical JSON") from exc
    if not isinstance(raw, Mapping):
        raise ManifestValidationError("production-shape manifest root must be an object")
    return validate_manifest(raw)


def validate_manifest(value: Mapping[str, Any]) -> ProductionShapeManifest:
    document = _require_mapping(value, "manifest")
    _require_exact_keys(
        document,
        {
            "schemaVersion",
            "sha256",
            "loaderInputInventorySha256",
            "payloadByteSemantics",
            "source",
            "observed",
            "fixture",
        },
        "manifest",
    )
    if document["schemaVersion"] != MANIFEST_SCHEMA_VERSION:
        raise ManifestValidationError("unsupported production-shape manifest schemaVersion")
    declared_sha256 = _require_sha256(document["sha256"], "manifest.sha256")
    if declared_sha256 != manifest_sha256(document):
        raise ManifestValidationError("production-shape manifest SHA-256 does not match canonical content")
    inventory_sha256 = _require_sha256(
        document["loaderInputInventorySha256"],
        "manifest.loaderInputInventorySha256",
    )
    if inventory_sha256 != LOADER_INPUT_INVENTORY_SHA256:
        raise ManifestValidationError("production-shape manifest is not bound to the checked-in loader-input inventory")
    if document["payloadByteSemantics"] != PAYLOAD_BYTE_SEMANTICS:
        raise ManifestValidationError("production-shape manifest payloadByteSemantics is unsupported")

    observed_at = _validate_source(_require_mapping(document["source"], "manifest.source"))
    observed_tables, observed_snapshot, observed_loader_inputs = _validate_observed(
        _require_mapping(document["observed"], "manifest.observed")
    )
    profiles = _validate_fixture(_require_mapping(document["fixture"], "manifest.fixture"))
    current = profiles["current"]
    if current.table_cardinalities != observed_tables:
        raise ManifestValidationError("current fixture table cardinalities do not match observed production shape")
    if current.snapshot_cardinalities != observed_snapshot:
        raise ManifestValidationError("current fixture snapshot cardinalities do not match observed production shape")
    if current.loader_inputs != observed_loader_inputs:
        raise ManifestValidationError("current fixture loader inputs do not match observed production shape")
    _validate_cross_profile_equivalence(current, profiles["cold10x"], bounded_snapshot_logs=True)
    return ProductionShapeManifest(
        sha256=declared_sha256,
        observed_at=observed_at,
        profiles=profiles,
        loader_input_inventory_sha256=inventory_sha256,
        payload_byte_semantics=PAYLOAD_BYTE_SEMANTICS,
        canonical_document=copy.deepcopy(dict(document)),
    )


def fixture_config_for_manifest(manifest: ProductionShapeManifest, profile: str) -> dict[str, Any]:
    fixture = manifest.profiles.get(profile)
    if fixture is None:
        raise ManifestValidationError(f"unsupported production-shape profile {profile!r}")
    return {
        "schemaVersion": FIXTURE_CONFIG_SCHEMA_VERSION,
        "kind": "sanitized-production-shape",
        "manifestSha256": manifest.sha256,
        "loaderInputInventorySha256": manifest.loader_input_inventory_sha256,
        "payloadByteSemantics": manifest.payload_byte_semantics,
        "loaderInputObservation": loader_input_observation_contract(),
        "profile": profile,
        "fixedHotActionRows": fixture.fixed_hot_action_rows,
        "coldHistoryRows": fixture.cold_history_rows,
        "payloadSizeBytes": dict(fixture.payload_size_bytes),
        "expectedTableCardinalities": dict(fixture.table_cardinalities),
        "expectedSnapshotCardinalities": dict(fixture.snapshot_cardinalities),
        "expectedLoaderInputs": {
            input_id: {
                "sourceRows": metrics.source_rows,
                "retainedItems": metrics.retained_items,
                "payloadBytes": metrics.payload_bytes,
            }
            for input_id, metrics in fixture.loader_inputs.items()
        },
    }


def local_sanitized_aggregate_policy_sha256(value: Mapping[str, Any]) -> str:
    body = copy.deepcopy(dict(value))
    body.pop("sha256", None)
    return hashlib.sha256(canonical_json_bytes(body)).hexdigest()


def load_local_sanitized_aggregate_policy(
    path: Path,
    *,
    value: Mapping[str, Any] | None = None,
) -> LocalSanitizedAggregatePolicy:
    if value is None:
        if path.is_symlink() or not path.is_file():
            raise ManifestValidationError("local sanitized aggregate policy must be a regular non-symlink file")
        try:
            loaded = json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=_no_duplicate_keys)
        except (OSError, json.JSONDecodeError) as exc:
            raise ManifestValidationError("local sanitized aggregate policy is not readable JSON") from exc
        if not isinstance(loaded, Mapping):
            raise ManifestValidationError("local sanitized aggregate policy root must be an object")
        value = loaded
    return validate_local_sanitized_aggregate_policy(value)


def validate_local_sanitized_aggregate_policy(value: Mapping[str, Any]) -> LocalSanitizedAggregatePolicy:
    document = _require_mapping(value, "local sanitized aggregate policy")
    _require_exact_keys(
        document,
        {
            "schemaVersion",
            "sha256",
            "loaderInputInventorySha256",
            "payloadByteSemantics",
            "source",
            "fixture",
        },
        "local sanitized aggregate policy",
    )
    if document["schemaVersion"] != LOCAL_SANITIZED_AGGREGATE_POLICY_SCHEMA_VERSION:
        raise ManifestValidationError("unsupported local sanitized aggregate policy schemaVersion")
    declared_sha256 = _require_sha256(document["sha256"], "local sanitized aggregate policy.sha256")
    if declared_sha256 != local_sanitized_aggregate_policy_sha256(document):
        raise ManifestValidationError("local sanitized aggregate policy SHA-256 does not match canonical content")
    inventory_sha256 = _require_sha256(
        document["loaderInputInventorySha256"],
        "local sanitized aggregate policy.loaderInputInventorySha256",
    )
    if inventory_sha256 != LOADER_INPUT_INVENTORY_SHA256:
        raise ManifestValidationError("local sanitized aggregate policy is not bound to the checked-in loader-input inventory")
    if document["payloadByteSemantics"] != PAYLOAD_BYTE_SEMANTICS:
        raise ManifestValidationError("local sanitized aggregate policy payloadByteSemantics is unsupported")

    source = _require_mapping(document["source"], "local sanitized aggregate policy.source")
    _require_exact_keys(source, {"sourceClass", "policyId", "provenance"}, "local sanitized aggregate policy.source")
    if source["sourceClass"] != LOCAL_SANITIZED_AGGREGATE_SOURCE_CLASS:
        raise ManifestValidationError("local sanitized aggregate policy sourceClass must be local-deterministic-policy")
    policy_id = source["policyId"]
    if not isinstance(policy_id, str) or not re.fullmatch(r"op123-local-sanitized-aggregate-v[0-9]+", policy_id):
        raise ManifestValidationError("local sanitized aggregate policy source.policyId is unsupported")
    if source["provenance"] != LOCAL_SANITIZED_AGGREGATE_PROVENANCE:
        raise ManifestValidationError("local sanitized aggregate policy provenance must explicitly exclude production, live, and seeded-db observation")

    profiles = _validate_fixture(_require_mapping(document["fixture"], "local sanitized aggregate policy.fixture"))
    _validate_cross_profile_equivalence(profiles["current"], profiles["cold10x"], bounded_snapshot_logs=True)
    return LocalSanitizedAggregatePolicy(
        sha256=declared_sha256,
        policy_id=policy_id,
        fixture_kind=LOCAL_SANITIZED_AGGREGATE_FIXTURE_KIND,
        profiles=profiles,
        loader_input_inventory_sha256=inventory_sha256,
        payload_byte_semantics=PAYLOAD_BYTE_SEMANTICS,
        canonical_document=copy.deepcopy(dict(document)),
    )


def local_fixture_config_for_policy(policy: LocalSanitizedAggregatePolicy, profile: str) -> dict[str, Any]:
    fixture = policy.profiles.get(profile)
    if fixture is None:
        raise ManifestValidationError(f"unsupported local sanitized aggregate profile {profile!r}")
    return {
        "schemaVersion": LOCAL_SANITIZED_AGGREGATE_FIXTURE_CONFIG_SCHEMA_VERSION,
        "kind": LOCAL_SANITIZED_AGGREGATE_FIXTURE_KIND,
        "policyId": policy.policy_id,
        "policySha256": policy.sha256,
        "loaderInputInventorySha256": policy.loader_input_inventory_sha256,
        "payloadByteSemantics": policy.payload_byte_semantics,
        "loaderInputObservation": loader_input_observation_contract(),
        "profile": profile,
        "fixedHotActionRows": fixture.fixed_hot_action_rows,
        "coldHistoryRows": fixture.cold_history_rows,
        "payloadSizeBytes": dict(fixture.payload_size_bytes),
        "expectedTableCardinalities": dict(fixture.table_cardinalities),
        "expectedSnapshotCardinalities": dict(fixture.snapshot_cardinalities),
        "expectedLoaderInputs": {
            input_id: {
                "sourceRows": metrics.source_rows,
                "retainedItems": metrics.retained_items,
                "payloadBytes": metrics.payload_bytes,
            }
            for input_id, metrics in fixture.loader_inputs.items()
        },
    }


def _no_duplicate_keys(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise ManifestValidationError(f"duplicate JSON key {key!r} is not allowed")
        result[key] = value
    return result


def _require_mapping(value: Any, label: str) -> Mapping[str, Any]:
    if not isinstance(value, Mapping):
        raise ManifestValidationError(f"{label} must be an object")
    return value


def _require_exact_keys(value: Mapping[str, Any], expected: set[str], label: str) -> None:
    actual = set(value)
    missing = expected - actual
    unknown = actual - expected
    if missing:
        raise ManifestValidationError(f"{label} is missing required fields: {', '.join(sorted(missing))}")
    if unknown:
        raise ManifestValidationError(f"{label} contains prohibited fields: {', '.join(sorted(unknown))}")


def _require_sha256(value: Any, label: str) -> str:
    if not isinstance(value, str) or not SHA256_PATTERN.fullmatch(value):
        raise ManifestValidationError(f"{label} must be a lowercase SHA-256 digest")
    return value


def _require_observed_at(value: Any, label: str) -> str:
    if not isinstance(value, str) or not value.endswith("Z"):
        raise ManifestValidationError(f"{label} must be an ISO-8601 UTC timestamp")
    try:
        parsed = dt.datetime.fromisoformat(value.removesuffix("Z") + "+00:00")
    except ValueError as exc:
        raise ManifestValidationError(f"{label} must be an ISO-8601 UTC timestamp") from exc
    if parsed.tzinfo is None:
        raise ManifestValidationError(f"{label} must include UTC timezone")
    return value


def _validate_source(source: Mapping[str, Any]) -> str:
    _require_exact_keys(source, {"sourceClass", "observedAt", "provenance"}, "manifest.source")
    if source["sourceClass"] != "production-aggregate-observation":
        raise ManifestValidationError("manifest.source.sourceClass must be production-aggregate-observation")
    observed_at = _require_observed_at(source["observedAt"], "manifest.source.observedAt")
    provenance = source["provenance"]
    if not isinstance(provenance, list) or not provenance:
        raise ManifestValidationError("manifest.source.provenance must contain aggregate evidence records")
    covered_dimensions: set[str] = set()
    for index, record in enumerate(provenance):
        label = f"manifest.source.provenance[{index}]"
        mapping = _require_mapping(record, label)
        _require_exact_keys(mapping, {"sourceClass", "observedAt", "evidenceSha256", "dimensions"}, label)
        if mapping["sourceClass"] not in SOURCE_CLASSES:
            raise ManifestValidationError(f"{label}.sourceClass is not an approved aggregate evidence class")
        _require_observed_at(mapping["observedAt"], f"{label}.observedAt")
        _require_sha256(mapping["evidenceSha256"], f"{label}.evidenceSha256")
        dimensions = mapping["dimensions"]
        if not isinstance(dimensions, list) or not dimensions or any(not isinstance(item, str) for item in dimensions):
            raise ManifestValidationError(f"{label}.dimensions must be a non-empty string list")
        dimension_set = set(dimensions)
        if len(dimension_set) != len(dimensions):
            raise ManifestValidationError(f"{label}.dimensions must not duplicate a dimension")
        unknown = dimension_set - REQUIRED_PROVENANCE_DIMENSIONS
        if unknown:
            raise ManifestValidationError(f"{label}.dimensions includes unsupported values: {', '.join(sorted(unknown))}")
        covered_dimensions.update(dimension_set)
    missing = REQUIRED_PROVENANCE_DIMENSIONS - covered_dimensions
    if missing:
        raise ManifestValidationError(
            "manifest provenance does not source every required live dimension: " + ", ".join(sorted(missing))
        )
    return observed_at


def _validate_observed(
    observed: Mapping[str, Any],
) -> tuple[Mapping[str, int], Mapping[str, int], Mapping[str, LoaderInputMetrics]]:
    _require_exact_keys(
        observed,
        {"tableCardinalities", "snapshotCardinalities", "loaderInputs"},
        "manifest.observed",
    )
    return (
        _validate_cardinalities(
            _require_mapping(observed["tableCardinalities"], "manifest.observed.tableCardinalities"),
            REQUIRED_TABLE_CARDINALITIES,
            "manifest.observed.tableCardinalities",
        ),
        _validate_cardinalities(
            _require_mapping(observed["snapshotCardinalities"], "manifest.observed.snapshotCardinalities"),
            REQUIRED_SNAPSHOT_CARDINALITIES,
            "manifest.observed.snapshotCardinalities",
        ),
        _validate_loader_inputs(
            _require_mapping(observed["loaderInputs"], "manifest.observed.loaderInputs"),
            "manifest.observed.loaderInputs",
        ),
    )


def _validate_fixture(fixture: Mapping[str, Any]) -> Mapping[str, FixtureProfile]:
    _require_exact_keys(fixture, {"profiles"}, "manifest.fixture")
    profiles = _require_mapping(fixture["profiles"], "manifest.fixture.profiles")
    _require_exact_keys(profiles, {"current", "cold10x"}, "manifest.fixture.profiles")
    return {profile: _validate_fixture_profile(_require_mapping(profiles[profile], f"manifest.fixture.profiles.{profile}"), profile)
            for profile in ("current", "cold10x")}


def _validate_fixture_profile(value: Mapping[str, Any], profile: str) -> FixtureProfile:
    label = f"manifest.fixture.profiles.{profile}"
    _require_exact_keys(
        value,
        {
            "tableCardinalities",
            "snapshotCardinalities",
            "loaderInputs",
            "fixedHotActionRows",
            "coldHistoryRows",
            "payloadSizeBytes",
        },
        label,
    )
    fixed_hot_action_rows = _require_positive_int(value["fixedHotActionRows"], f"{label}.fixedHotActionRows")
    cold_history_rows = _require_positive_int(value["coldHistoryRows"], f"{label}.coldHistoryRows")
    payload_size_bytes = _validate_payload_sizes(_require_mapping(value["payloadSizeBytes"], f"{label}.payloadSizeBytes"), label)
    return FixtureProfile(
        table_cardinalities=_validate_cardinalities(
            _require_mapping(value["tableCardinalities"], f"{label}.tableCardinalities"),
            REQUIRED_TABLE_CARDINALITIES,
            f"{label}.tableCardinalities",
        ),
        snapshot_cardinalities=_validate_cardinalities(
            _require_mapping(value["snapshotCardinalities"], f"{label}.snapshotCardinalities"),
            REQUIRED_SNAPSHOT_CARDINALITIES,
            f"{label}.snapshotCardinalities",
        ),
        loader_inputs=_validate_loader_inputs(
            _require_mapping(value["loaderInputs"], f"{label}.loaderInputs"),
            f"{label}.loaderInputs",
        ),
        fixed_hot_action_rows=fixed_hot_action_rows,
        cold_history_rows=cold_history_rows,
        payload_size_bytes=payload_size_bytes,
    )


def _validate_cardinalities(value: Mapping[str, Any], expected: tuple[str, ...], label: str) -> Mapping[str, int]:
    _require_exact_keys(value, set(expected), label)
    return {key: _require_nonnegative_int(value[key], f"{label}.{key}") for key in expected}


def _validate_payload_sizes(value: Mapping[str, Any], label: str) -> Mapping[str, int]:
    _require_exact_keys(value, {"hotAction", "coldHistory"}, f"{label}.payloadSizeBytes")
    return {
        key: _require_positive_int(value[key], f"{label}.payloadSizeBytes.{key}", maximum=MAX_PAYLOAD_BYTES)
        for key in ("hotAction", "coldHistory")
    }


def _validate_loader_inputs(value: Mapping[str, Any], label: str) -> Mapping[str, LoaderInputMetrics]:
    _require_exact_keys(value, set(REQUIRED_LOADER_INPUT_IDS), label)
    result: dict[str, LoaderInputMetrics] = {}
    for input_id in REQUIRED_LOADER_INPUT_IDS:
        item_label = f"{label}.{input_id}"
        metrics = _require_mapping(value[input_id], item_label)
        _require_exact_keys(metrics, {"sourceRows", "retainedItems", "payloadBytes"}, item_label)
        result[input_id] = LoaderInputMetrics(
            source_rows=_require_nonnegative_int(metrics["sourceRows"], f"{item_label}.sourceRows"),
            retained_items=_require_nonnegative_int(metrics["retainedItems"], f"{item_label}.retainedItems"),
            payload_bytes=_require_nonnegative_int(metrics["payloadBytes"], f"{item_label}.payloadBytes"),
        )
    return result


def _require_nonnegative_int(value: Any, label: str) -> int:
    if type(value) is not int or not 0 <= value <= MAX_CARDINALITY:
        raise ManifestValidationError(f"{label} must be a non-negative integer not greater than {MAX_CARDINALITY}")
    return value


def _require_positive_int(value: Any, label: str, maximum: int = MAX_CARDINALITY) -> int:
    if type(value) is not int or not 0 < value <= maximum:
        raise ManifestValidationError(f"{label} must be a positive integer not greater than {maximum}")
    return value


def _validate_cross_profile_equivalence(
    current: FixtureProfile,
    cold: FixtureProfile,
    *,
    bounded_snapshot_logs: bool = False,
) -> None:
    if current.fixed_hot_action_rows != cold.fixed_hot_action_rows:
        raise ManifestValidationError("cold10x fixed hot action rows diverge from current")
    if current.payload_size_bytes != cold.payload_size_bytes:
        raise ManifestValidationError("cold10x payload-size assumptions diverge from current")
    if cold.cold_history_rows != current.cold_history_rows * 10:
        raise ManifestValidationError("cold10x cold-history rows must be exactly ten times current")
    for key in REQUIRED_TABLE_CARDINALITIES:
        if key != "logEntry" and cold.table_cardinalities[key] != current.table_cardinalities[key]:
            raise ManifestValidationError(f"cold10x table cardinality {key} diverges from current")
    for key in REQUIRED_SNAPSHOT_CARDINALITIES:
        if key != "globalLogs" and cold.snapshot_cardinalities[key] != current.snapshot_cardinalities[key]:
            raise ManifestValidationError(f"cold10x snapshot cardinality {key} diverges from current")
    history_growth = current.cold_history_rows * 9
    if cold.table_cardinalities["logEntry"] != current.table_cardinalities["logEntry"] + history_growth:
        raise ManifestValidationError("cold10x logEntry cardinality must add exactly nine current cold-history sets")
    expected_cold_global_logs = (
        current.snapshot_cardinalities["globalLogs"]
        if bounded_snapshot_logs
        else current.snapshot_cardinalities["globalLogs"] + history_growth
    )
    if cold.snapshot_cardinalities["globalLogs"] != expected_cold_global_logs:
        contract = "bounded cold boot" if bounded_snapshot_logs else "snapshot loading"
        raise ManifestValidationError(f"cold10x globalLogs cardinality does not match {contract}")
    if bounded_snapshot_logs and current.snapshot_cardinalities["globalLogs"] != 0:
        raise ManifestValidationError("current globalLogs cardinality must match bounded cold boot")
    if current.table_cardinalities["logEntry"] < current.fixed_hot_action_rows + current.cold_history_rows:
        raise ManifestValidationError("current logEntry cardinality cannot contain the declared hot and cold fixture rows")
    if not bounded_snapshot_logs and current.snapshot_cardinalities["globalLogs"] < current.fixed_hot_action_rows + current.cold_history_rows:
        raise ManifestValidationError("current globalLogs cardinality cannot contain the declared hot and cold fixture rows")
    for input_id in REQUIRED_LOADER_INPUT_IDS:
        if input_id == "systemHistoryLogs":
            continue
        if cold.loader_inputs[input_id] != current.loader_inputs[input_id]:
            raise ManifestValidationError(f"cold10x loader input {input_id} diverges from current")
    current_actions = current.loader_inputs["systemActionLogs"]
    if current_actions.source_rows < current.fixed_hot_action_rows or (
        not bounded_snapshot_logs and current_actions.retained_items < current.fixed_hot_action_rows
    ):
        raise ManifestValidationError("systemActionLogs cannot contain the declared fixed hot action rows")
    if current_actions.payload_bytes < current.fixed_hot_action_rows * current.payload_size_bytes["hotAction"]:
        raise ManifestValidationError("systemActionLogs payload cannot contain the declared fixed hot action rows")
    if bounded_snapshot_logs and current_actions.retained_items != 0:
        raise ManifestValidationError("current systemActionLogs retained items must match bounded cold boot")
    current_history = current.loader_inputs["systemHistoryLogs"]
    cold_history = cold.loader_inputs["systemHistoryLogs"]
    if (
        current_history.source_rows < current.cold_history_rows
        or (not bounded_snapshot_logs and current_history.retained_items < current.cold_history_rows)
        or current_history.payload_bytes < current.cold_history_rows * current.payload_size_bytes["coldHistory"]
    ):
        raise ManifestValidationError("systemHistoryLogs cannot contain the declared current cold-history rows")
    if bounded_snapshot_logs and current_history.retained_items != 0:
        raise ManifestValidationError("current systemHistoryLogs retained items must match bounded cold boot")
    expected_history_growth = current.cold_history_rows * 9
    expected_history_payload_growth = expected_history_growth * current.payload_size_bytes["coldHistory"]
    if cold_history.source_rows != current_history.source_rows + expected_history_growth:
        raise ManifestValidationError("cold10x systemHistoryLogs source rows must add exactly nine current cold-history sets")
    expected_cold_retained = (
        current_history.retained_items
        if bounded_snapshot_logs
        else current_history.retained_items + expected_history_growth
    )
    if cold_history.retained_items != expected_cold_retained:
        raise ManifestValidationError("cold10x systemHistoryLogs retained items do not match its snapshot-loading contract")
    if cold_history.payload_bytes != current_history.payload_bytes + expected_history_payload_growth:
        raise ManifestValidationError("cold10x systemHistoryLogs payload must add exactly nine current cold-history sets")
