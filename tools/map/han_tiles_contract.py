"""Pure validation helpers for Han tile build contracts and attestations.

This module deliberately has no filesystem, subprocess, or source-root access.  It
validates already-loaded JSON-compatible objects so the protected orchestrator and
the public contract checker can share one fail-closed schema implementation.  An
attestation records orchestrator-produced manifests; it is not proof that execution
occurred, so execution and materialization remain protected-orchestrator duties.
"""

from __future__ import annotations

import hashlib
import json
import re
from typing import Any


SCHEMA_VERSION = 1
CONTRACT_ID = "han-tiles-build-contract-v1"
ATTESTATION_ID = "han-tiles-build-attestation-v1"

RESTRICTED_INPUT_ROLES = (
    "CHGIS_PREF_DBF",
    "CHGIS_COUNTY_DBF",
    "CTEXT_JUNGUOZHI_YI",
    "CTEXT_JUNGUOZHI_ER",
    "CTEXT_JUNGUOZHI_SAN",
    "CTEXT_JUNGUOZHI_SI",
    "CTEXT_JUNGUOZHI_WU",
    "NE_LAND_50M",
    "NE_LAKES_50M",
    "NE_RIVERS_50M",
    "NE_REGIONS_10M",
    "MODERN_ADMIN_ADM2",
)
TRACKED_INPUT_ROLES = (
    "ADMINISTRATIVE_UNITS",
    "ADMINISTRATIVE_BINDINGS",
    "ADMINISTRATIVE_HISTORY",
    "DUPLICATE_ADJUDICATIONS",
    "STABLE_ID_ADJUDICATIONS",
    "MERGE_ADJUDICATIONS",
    "TEMPORAL_ADJUDICATIONS",
    "EXTERNAL_PLACES",
)
INPUT_ROLES = RESTRICTED_INPUT_ROLES + TRACKED_INPUT_ROLES
GENERATOR_ROLES = (
    "BUILD_HAN_PLACES",
    "BUILD_JUNGUOZHI",
    "BUILD_TERRAIN_GRID",
    "BUILD_READINGS",
    "BUILD_HAN_TILES",
)
VERIFIER_ROLES = (
    "HAN_TILES_CONTRACT_VALIDATOR",
    "HAN_TILES_ORCHESTRATOR",
)
HELPER_ROLES = (
    "HAN_PLACE_STABLE_ID_LOADER",
    "HAN_PLACE_MERGE_ADJUDICATIONS",
    "HAN_PLACE_MERGE_RUNTIME",
    "HAN_TEMPORAL_PARENT_RUNTIME",
    "HAN_PARENT_RECONCILIATION_HELPER",
    "HAN_PROVINCE_MODEL",
    "HAN_TILES_CONTRACT_HELPER",
)
OUTPUT_ROLES = (
    "HAN_PLACES",
    "JUNGUOZHI",
    "TERRAIN_GRID",
    "READINGS",
    "HAN_TILES",
)
DEPENDENCIES = {
    "NUMPY": {
        "distributionName": "numpy",
        "lockRole": "HAN_TILES_PYTHON_LOCK",
    },
    "PILLOW": {
        "distributionName": "Pillow",
        "lockRole": "HAN_TILES_PYTHON_LOCK",
    },
    "HANJA": {
        "distributionName": "hanja",
        "lockRole": "HAN_TILES_PYTHON_LOCK",
    },
    "PYYAML": {
        "distributionName": "PyYAML",
        "lockRole": "HAN_TILES_PYTHON_LOCK",
        "requiredByRole": "HANJA",
    },
}

_STAGES = (
    {
        "stageId": "HAN_PLACES",
        "generatorRole": "BUILD_HAN_PLACES",
        "inputRoles": [
            "CHGIS_PREF_DBF",
            "CHGIS_COUNTY_DBF",
            "ADMINISTRATIVE_UNITS",
            "DUPLICATE_ADJUDICATIONS",
            "STABLE_ID_ADJUDICATIONS",
            "EXTERNAL_PLACES",
        ],
        "dependencyRoles": [],
        "outputRole": "HAN_PLACES",
        "argv": ["--year", "220", "--grid", "768"],
    },
    {
        "stageId": "JUNGUOZHI",
        "generatorRole": "BUILD_JUNGUOZHI",
        "inputRoles": [
            "CHGIS_COUNTY_DBF",
            "CTEXT_JUNGUOZHI_YI",
            "CTEXT_JUNGUOZHI_ER",
            "CTEXT_JUNGUOZHI_SAN",
            "CTEXT_JUNGUOZHI_SI",
            "CTEXT_JUNGUOZHI_WU",
            "EXTERNAL_PLACES",
        ],
        "dependencyRoles": [],
        "outputRole": "JUNGUOZHI",
        "argv": [],
    },
    {
        "stageId": "TERRAIN_GRID",
        "generatorRole": "BUILD_TERRAIN_GRID",
        "inputRoles": [
            "HAN_PLACES",
            "JUNGUOZHI",
            "NE_LAND_50M",
            "NE_LAKES_50M",
            "NE_RIVERS_50M",
            "NE_REGIONS_10M",
            "MODERN_ADMIN_ADM2",
            "ADMINISTRATIVE_UNITS",
            "ADMINISTRATIVE_BINDINGS",
            "ADMINISTRATIVE_HISTORY",
            "MERGE_ADJUDICATIONS",
            "TEMPORAL_ADJUDICATIONS",
        ],
        "dependencyRoles": ["NUMPY", "PILLOW"],
        "outputRole": "TERRAIN_GRID",
        "argv": ["--grid", "768"],
    },
    {
        "stageId": "READINGS",
        "generatorRole": "BUILD_READINGS",
        "inputRoles": ["HAN_PLACES", "JUNGUOZHI", "TERRAIN_GRID"],
        "dependencyRoles": ["HANJA", "PYYAML"],
        "outputRole": "READINGS",
        "argv": [],
    },
    {
        "stageId": "HAN_TILES",
        "generatorRole": "BUILD_HAN_TILES",
        "inputRoles": ["HAN_PLACES", "TERRAIN_GRID", "READINGS"],
        "dependencyRoles": [],
        "outputRole": "HAN_TILES",
        "argv": [],
    },
)

_SUMMARY_KEYS = {
    "HAN_PLACES": (
        "year", "cols", "rows", "placeCount", "nudgedCount",
    ),
    "JUNGUOZHI": (
        "groupCount", "countyCount", "resolvedCount", "candidateCount",
        "checksumPassCount", "noCountCount",
    ),
    "TERRAIN_GRID": (
        "year", "cols", "rows", "terrainCellCount", "ownerCellCount",
        "seatOwnerCellCount", "hubCount", "regionCount", "countyEdgeCount",
        "commanderyEdgeCount",
    ),
    "READINGS": ("entryCount",),
    "HAN_TILES": (
        "year", "cols", "rows", "cityCount", "junCount", "regionCount",
        "ownerRunCount", "seatOwnerRunCount", "countyEdgeCount",
        "commanderyEdgeCount",
    ),
}

_FORBIDDEN_FIELD_NAMES = {
    "path",
    "absolutepath",
    "sourceroot",
    "coordinate",
    "coordinates",
    "projection",
    "cells",
    "owners",
    "url",
    "bbox",
    "lon",
    "lat",
    "x",
    "y",
    "row",
    "col",
}
_SHA256 = re.compile(r"[0-9a-f]{64}\Z")
_PYTHON_VERSION = re.compile(r"[0-9]+\.[0-9]+\.[0-9]+\Z")


def _canonical_bytes(value: Any) -> bytes:
    try:
        return json.dumps(
            value,
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
            allow_nan=False,
        ).encode("utf-8")
    except (TypeError, ValueError) as error:
        raise ValueError("document is not canonical JSON data") from error


def loads_json_strict(document: str | bytes) -> Any:
    """Parse JSON while rejecting duplicate keys and non-finite constants."""
    if not isinstance(document, (str, bytes)):
        raise ValueError("JSON document must be str or bytes")

    def reject_duplicate_keys(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
        result: dict[str, Any] = {}
        for key, value in pairs:
            if key in result:
                raise ValueError(f"duplicate object key: {key!r}")
            result[key] = value
        return result

    def reject_nonfinite(constant: str) -> None:
        raise ValueError(f"non-finite JSON constant: {constant}")

    try:
        return json.loads(
            document,
            object_pairs_hook=reject_duplicate_keys,
            parse_constant=reject_nonfinite,
        )
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ValueError("invalid JSON document") from error


def recipe_sha256(recipe: dict[str, Any]) -> str:
    """Return the canonical UTF-8 JSON digest of the recipe value only."""
    return hashlib.sha256(_canonical_bytes(recipe)).hexdigest()


def _document_sha256(document: dict[str, Any]) -> str:
    return hashlib.sha256(_canonical_bytes(document)).hexdigest()


def _normalized_field_name(name: str) -> str:
    return re.sub(r"[^a-z0-9]", "", name.lower())


def _reject_forbidden_fields(value: Any, where: str) -> None:
    if isinstance(value, dict):
        for key, child in value.items():
            if isinstance(key, str) and _normalized_field_name(key) in _FORBIDDEN_FIELD_NAMES:
                raise ValueError(f"{where}: forbidden field {key!r}")
            _reject_forbidden_fields(child, f"{where}.{key}")
    elif isinstance(value, list):
        for index, child in enumerate(value):
            _reject_forbidden_fields(child, f"{where}[{index}]")


def _require_object(value: Any, where: str) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise ValueError(f"{where} must be an object")
    if not all(isinstance(key, str) for key in value):
        raise ValueError(f"{where} keys must be strings")
    return value


def _require_list(value: Any, where: str) -> list[Any]:
    if not isinstance(value, list):
        raise ValueError(f"{where} must be an array")
    return value


def _require_exact_keys(value: Any, expected: tuple[str, ...] | set[str], where: str) -> dict[str, Any]:
    obj = _require_object(value, where)
    expected_set = set(expected)
    actual_set = set(obj)
    if actual_set != expected_set:
        raise ValueError(
            f"{where}: exact keys required; missing={sorted(expected_set - actual_set)}, "
            f"unknown={sorted(actual_set - expected_set)}"
        )
    return obj


def _require_exact_role_map(value: Any, roles: tuple[str, ...], where: str) -> dict[str, Any]:
    obj = _require_object(value, where)
    if set(obj) != set(roles):
        raise ValueError(
            f"{where}: exact role set required; missing={sorted(set(roles) - set(obj))}, "
            f"unknown={sorted(set(obj) - set(roles))}"
        )
    return obj


def _require_string(value: Any, where: str) -> str:
    if not isinstance(value, str) or not value:
        raise ValueError(f"{where} must be a non-empty string")
    return value


def _require_int(value: Any, where: str, minimum: int = 0) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or value < minimum:
        raise ValueError(f"{where} must be an integer >= {minimum}")
    return value


def _require_exact_int(value: Any, expected: int, where: str) -> int:
    actual = _require_int(value, where, expected)
    if actual != expected:
        raise ValueError(f"{where} must be exactly {expected}")
    return actual


def _require_hash(value: Any, where: str) -> str:
    if not isinstance(value, str) or _SHA256.fullmatch(value) is None or value == "0" * 64:
        raise ValueError(f"{where} must be a non-zero lowercase sha256")
    return value


def _validate_hash_record(value: Any, where: str) -> dict[str, Any]:
    record = _require_exact_keys(value, {"sha256", "bytes"}, where)
    _require_hash(record["sha256"], f"{where}.sha256")
    _require_int(record["bytes"], f"{where}.bytes", 1)
    return record


def _validate_environment(value: Any, where: str) -> dict[str, Any]:
    environment = _require_exact_keys(
        value, {"locale", "timezone", "pythonHashSeed", "threadCount"}, where
    )
    expected = {
        "locale": "C.UTF-8",
        "timezone": "UTC",
        "pythonHashSeed": "0",
        "threadCount": 1,
    }
    _require_exact_int(environment["threadCount"], 1, f"{where}.threadCount")
    if environment != expected:
        raise ValueError(f"{where}: runtime environment must equal {expected}")
    return environment


def _validate_runtime(value: Any) -> dict[str, Any]:
    runtime = _require_exact_keys(
        value,
        {"pythonImplementation", "pythonVersion", "dependencyLock", "environment"},
        "recipe.runtime",
    )
    if runtime["pythonImplementation"] != "CPython":
        raise ValueError("recipe.runtime pythonImplementation must be CPython")
    version = _require_string(runtime["pythonVersion"], "recipe.runtime.pythonVersion")
    if _PYTHON_VERSION.fullmatch(version) is None:
        raise ValueError("recipe.runtime pythonVersion must be an exact X.Y.Z version")
    lock = _require_exact_keys(
        runtime["dependencyLock"], {"role", "sha256", "bytes"},
        "recipe.runtime.dependencyLock",
    )
    if lock["role"] != "HAN_TILES_PYTHON_LOCK":
        raise ValueError("recipe.runtime dependency lock role must be HAN_TILES_PYTHON_LOCK")
    _require_hash(lock["sha256"], "recipe.runtime.dependencyLock.sha256")
    _require_int(lock["bytes"], "recipe.runtime.dependencyLock.bytes", 1)
    _validate_environment(runtime["environment"], "recipe.runtime.environment")
    return runtime


def _validate_stage_graph(value: Any, dependency_roles: set[str]) -> None:
    stages = _require_list(value, "recipe.stages")
    if len(stages) != len(_STAGES):
        raise ValueError("recipe stage graph must contain exactly five stages")
    validated_stages = []
    referenced_dependencies: set[str] = set()
    for index, (actual, expected) in enumerate(zip(stages, _STAGES)):
        stage = _require_exact_keys(
            actual,
            {"stageId", "generatorRole", "inputRoles", "dependencyRoles", "outputRole", "argv"},
            f"recipe.stages[{index}]",
        )
        for field in ("inputRoles", "dependencyRoles", "argv"):
            _require_list(stage[field], f"recipe.stages[{index}].{field}")
        if not all(isinstance(role, str) for role in stage["dependencyRoles"]):
            raise ValueError(
                f"recipe.stages[{index}].dependencyRoles must contain strings"
            )
        referenced_dependencies.update(stage["dependencyRoles"])
        validated_stages.append(stage)

    if referenced_dependencies != dependency_roles:
        raise ValueError(
            "recipe stage graph dependency closure mismatch: "
            f"dangling={sorted(referenced_dependencies - dependency_roles)}, "
            f"unused={sorted(dependency_roles - referenced_dependencies)}"
        )

    for index, (stage, expected) in enumerate(zip(validated_stages, _STAGES)):
        if stage != expected:
            raise ValueError(
                f"recipe stage graph mismatch at index {index}: expected {expected['stageId']}"
            )


def validate_contract(document: dict[str, Any]) -> bool:
    """Validate a complete v1 production contract, returning ``True`` on success."""
    _reject_forbidden_fields(document, "contract")
    contract = _require_exact_keys(
        document,
        {
            "schemaVersion", "contractId", "policy", "recipe", "recipeSha256",
            "expectedOutputs",
        },
        "contract",
    )
    if type(contract["schemaVersion"]) is not int or contract["schemaVersion"] != SCHEMA_VERSION:
        raise ValueError("contract schemaVersion must be 1")
    if contract["contractId"] != CONTRACT_ID:
        raise ValueError(f"contractId must be {CONTRACT_ID}")

    policy = _require_exact_keys(
        contract["policy"],
        {"authoritativeArtifactRole", "provenanceOnly", "committedCoordinateBearingRoles"},
        "contract.policy",
    )
    if type(policy["provenanceOnly"]) is not bool:
        raise ValueError("contract policy provenanceOnly must be a boolean")
    if policy != {
        "authoritativeArtifactRole": "HAN_TILES",
        "provenanceOnly": True,
        "committedCoordinateBearingRoles": ["HAN_TILES"],
    }:
        raise ValueError("contract policy must preserve HAN_TILES as the sole coordinate-bearing artifact")

    recipe = _require_exact_keys(
        contract["recipe"],
        {
            "canonical", "inputs", "generators", "verifiers", "helpers",
            "dependencies", "runtime", "stages",
        },
        "contract.recipe",
    )
    canonical = _require_exact_keys(
        recipe["canonical"], {"year", "gridColumns", "gridRows", "frameId"},
        "recipe.canonical",
    )
    for field, expected in (("year", 220), ("gridColumns", 768), ("gridRows", 669)):
        _require_exact_int(canonical[field], expected, f"recipe.canonical.{field}")
    if canonical != {
        "year": 220,
        "gridColumns": 768,
        "gridRows": 669,
        "frameId": "HAN_WORLD_FRAME_V1",
    }:
        raise ValueError("recipe canonical grid must be year 220 and 768x669")

    inputs = _require_exact_role_map(recipe["inputs"], INPUT_ROLES, "recipe.inputs")
    for role, value in inputs.items():
        record = _require_exact_keys(
            value, {"classification", "sha256", "bytes"}, f"recipe.inputs.{role}"
        )
        expected_class = "RESTRICTED_LOCAL" if role in RESTRICTED_INPUT_ROLES else "TRACKED_REPOSITORY"
        if record["classification"] != expected_class:
            raise ValueError(f"recipe.inputs.{role} classification must be {expected_class}")
        _require_hash(record["sha256"], f"recipe.inputs.{role}.sha256")
        _require_int(record["bytes"], f"recipe.inputs.{role}.bytes", 1)

    for section, roles in (("generators", GENERATOR_ROLES), ("verifiers", VERIFIER_ROLES)):
        role_map = _require_exact_role_map(recipe[section], roles, f"recipe.{section}")
        for role, value in role_map.items():
            _validate_hash_record(value, f"recipe.{section}.{role}")

    helpers = _require_exact_role_map(
        recipe["helpers"], HELPER_ROLES, "recipe.helpers"
    )
    for role, value in helpers.items():
        _validate_hash_record(value, f"recipe.helpers.{role}")

    dependencies = _require_exact_role_map(
        recipe["dependencies"], tuple(DEPENDENCIES), "recipe.dependencies"
    )
    for role, expected in DEPENDENCIES.items():
        record = _require_exact_keys(
            dependencies[role], set(expected),
            f"recipe.dependencies.{role}",
        )
        if record != expected:
            raise ValueError(
                f"recipe.dependencies.{role} must equal {expected}"
            )

    _validate_runtime(recipe["runtime"])
    _validate_stage_graph(recipe["stages"], set(dependencies))

    supplied_hash = _require_hash(contract["recipeSha256"], "contract.recipeSha256")
    expected_hash = recipe_sha256(recipe)
    if supplied_hash != expected_hash:
        raise ValueError(
            f"recipeSha256 mismatch: expected {expected_hash}, got {supplied_hash}"
        )

    _validate_output_map(contract["expectedOutputs"], "contract.expectedOutputs")
    return True


def _validate_summary(role: str, value: Any, where: str) -> dict[str, Any]:
    summary = _require_exact_keys(value, set(_SUMMARY_KEYS[role]), where)
    for key, item in summary.items():
        _require_int(item, f"{where}.{key}", 0)
    if role in {"HAN_PLACES", "TERRAIN_GRID", "HAN_TILES"}:
        if (summary["year"], summary["cols"], summary["rows"]) != (220, 768, 669):
            raise ValueError(f"{where}: canonical summary must be year 220 and 768x669")
    if role == "TERRAIN_GRID":
        cells = 768 * 669
        for key in ("terrainCellCount", "ownerCellCount", "seatOwnerCellCount"):
            if summary[key] != cells:
                raise ValueError(f"{where}.{key}: cell count must be {cells}")
    return summary


def _validate_output_record(role: str, value: Any, where: str) -> dict[str, Any]:
    record = _require_exact_keys(value, {"role", "sha256", "bytes", "summary"}, where)
    if record["role"] != role:
        raise ValueError(f"{where}: output role must be {role}")
    _require_hash(record["sha256"], f"{where}.sha256")
    _require_int(record["bytes"], f"{where}.bytes", 1)
    _validate_summary(role, record["summary"], f"{where}.summary")
    return record


def _validate_output_map(value: Any, where: str) -> dict[str, Any]:
    outputs = _require_exact_role_map(value, OUTPUT_ROLES, where)
    for role in OUTPUT_ROLES:
        _validate_output_record(role, outputs[role], f"{where}.{role}")
    return outputs


def _validate_runtime_fingerprint(value: Any, contract_runtime: dict[str, Any]) -> None:
    fingerprint = _require_exact_keys(
        value,
        {"pythonImplementation", "pythonVersion", "dependencyLockSha256", "environment"},
        "attestation.runtimeFingerprint",
    )
    expected = {
        "pythonImplementation": contract_runtime["pythonImplementation"],
        "pythonVersion": contract_runtime["pythonVersion"],
        "dependencyLockSha256": contract_runtime["dependencyLock"]["sha256"],
        "environment": contract_runtime["environment"],
    }
    _require_hash(
        fingerprint["dependencyLockSha256"],
        "attestation.runtimeFingerprint.dependencyLockSha256",
    )
    _validate_environment(
        fingerprint["environment"], "attestation.runtimeFingerprint.environment"
    )
    if fingerprint != expected:
        raise ValueError("attestation runtimeFingerprint does not match contract runtime")


def validate_attestation(contract_document: dict[str, Any], document: dict[str, Any]) -> bool:
    """Validate coordinate-free orchestrator manifests against their contract."""
    validate_contract(contract_document)
    _reject_forbidden_fields(document, "attestation")
    attestation = _require_exact_keys(
        document,
        {
            "schemaVersion", "attestationId", "contractId", "contractSha256",
            "recipeSha256", "sourceBundleSha256", "runtimeFingerprint",
            "cleanRuns", "materializedArtifact",
        },
        "attestation",
    )
    if type(attestation["schemaVersion"]) is not int or attestation["schemaVersion"] != SCHEMA_VERSION:
        raise ValueError("attestation schemaVersion must be 1")
    if attestation["attestationId"] != ATTESTATION_ID:
        raise ValueError(f"attestationId must be {ATTESTATION_ID}")
    if attestation["contractId"] != contract_document["contractId"]:
        raise ValueError("attestation contractId does not match contract")

    bindings = {
        "contractSha256": _document_sha256(contract_document),
        "recipeSha256": contract_document["recipeSha256"],
        "sourceBundleSha256": recipe_sha256(contract_document["recipe"]["inputs"]),
    }
    for field, expected in bindings.items():
        actual = _require_hash(attestation[field], f"attestation.{field}")
        if actual != expected:
            raise ValueError(f"attestation {field} mismatch: expected {expected}, got {actual}")

    _validate_runtime_fingerprint(
        attestation["runtimeFingerprint"], contract_document["recipe"]["runtime"]
    )

    runs = _require_list(attestation["cleanRuns"], "attestation.cleanRuns")
    if len(runs) != 2:
        raise ValueError("attestation must contain exactly two clean runs")
    validated_outputs = []
    for run_index, run_value in enumerate(runs):
        run = _require_exact_keys(
            run_value, {"runOrdinal", "outputs"}, f"attestation.cleanRuns[{run_index}]"
        )
        expected_ordinal = run_index + 1
        _require_exact_int(
            run["runOrdinal"], expected_ordinal,
            f"attestation.cleanRuns[{run_index}].runOrdinal",
        )
        if run["runOrdinal"] != expected_ordinal:
            raise ValueError(
                f"attestation cleanRuns[{run_index}].runOrdinal must be {expected_ordinal}"
            )
        outputs = _validate_output_map(
            run["outputs"], f"attestation.cleanRuns[{run_index}].outputs"
        )
        validated_outputs.append(outputs)
    if validated_outputs[0] != validated_outputs[1]:
        raise ValueError("attestation clean runs differ")
    if validated_outputs[0] != contract_document["expectedOutputs"]:
        raise ValueError("attestation clean run outputs do not match expectedOutputs")

    materialized = _validate_output_record(
        "HAN_TILES", attestation["materializedArtifact"],
        "attestation.materializedArtifact",
    )
    if materialized != contract_document["expectedOutputs"]["HAN_TILES"]:
        raise ValueError(
            "attestation materializedArtifact does not match expectedOutputs.HAN_TILES"
        )
    return True


def validate_contract_json(document: str | bytes) -> bool:
    """Strictly parse and validate a serialized contract."""
    return validate_contract(loads_json_strict(document))


def validate_attestation_json(
    contract_document: str | bytes, document: str | bytes
) -> bool:
    """Strictly parse and validate serialized contract and attestation documents."""
    return validate_attestation(
        loads_json_strict(contract_document), loads_json_strict(document)
    )
