from __future__ import annotations

from typing import Final, TypeAlias

from tools.map.route_network_contract import (
    EXPECTED_NUMERIC_EDGE_SHA,
    EXPECTED_ROUTE_KEY_EDGE_SHA,
    RUNTIME_BOUNDARY,
    ContractError,
    JsonObject,
    SourceBundle,
    Topology,
    require_array,
    require_object,
    require_text,
)

GeneratedDocuments: TypeAlias = dict[str, JsonObject]
REGISTRY_KEYS: Final = frozenset({"schemaVersion", "registryId", "identityPolicy", "summary", "entries"})
REGISTRY_SUMMARY_KEYS: Final = frozenset({"entryCount", "uuidVersion"})
REGISTRY_ENTRY_KEYS: Final = frozenset({"canonicalEndpointPair", "corridorKey"})
CORRIDOR_KEYS: Final = frozenset({"schemaVersion", "candidateSetId", "reviewState", "runtimeActivation", "candidatePolicy", "summary", "provenance", "candidates"})
CANDIDATE_POLICY_KEYS: Final = frozenset({"automaticApprovalCount", "automaticModeAssignmentCount", "automaticGeometryAssignmentCount", "automaticSourceClaimCount"})
CORRIDOR_SUMMARY_KEYS: Final = frozenset({"routeNodeCount", "candidateCount", "directedConnectionCount", "reviewStateCounts", "selfEdgeCount", "danglingEndpointCount", "duplicateEdgeCount", "asymmetricConnectionCount", "connectedComponentCount", "minimumDegree", "graphStatus", "replacementRiskCounts"})
CORRIDOR_PROVENANCE_KEYS: Final = frozenset({"generator", "inputs", "numericUndirectedEdgeSha256", "routeKeyUndirectedEdgeSha256"})
CORRIDOR_ROW_KEYS: Final = frozenset({"corridorKey", "canonicalEndpointPair", "reviewState", "legacyTopologyClass", "legacyTopologyProvenance"})
TOPOLOGY_PROVENANCE_KEYS: Final = frozenset({"endpoints", "replacementRisk"})
ENDPOINT_PROVENANCE_KEYS: Final = frozenset({"routeNodeKey", "legacyCityId", "legacyNodeFingerprint", "legacyDisposition"})
REPLACEMENT_RISK_KEYS: Final = frozenset({"touchesReplacedUnrelatedNode", "replacedEndpointCount", "reviewInterpretation"})
REPLACEMENT_COUNT_KEYS: Final = frozenset({"noReplacementEndpoint", "atLeastOneReplacedEndpoint", "bothEndpointsReplaced"})
EXTERNAL_KEYS: Final = frozenset({"schemaVersion", "candidateSetId", "reviewState", "runtimeActivation", "classificationPolicy", "legacySourceMeta", "summary", "provenance", "candidates"})
EXTERNAL_SUMMARY_KEYS: Final = frozenset({"candidateCount", "reviewStateCounts", "legacyLifecycleStatusCounts", "runtimeActiveCount"})
EXTERNAL_PROVENANCE_KEYS: Final = frozenset({"generator", "input"})
EXTERNAL_ROW_KEYS: Final = frozenset({"candidateKey", "reviewState", "runtimeActivation", "legacyLifecycleStatus", "legacyProvenance", "rawLegacy"})
LEGACY_PROVENANCE_KEYS: Final = frozenset({"rowIndex", "sourceId"})
NETWORK_KEYS: Final = frozenset({"schemaVersion", "contractId", "runtimeActivation", "activeProductScenarios", "hanMapContractScenarios", "allowedCorridorModes", "externalEntityTypes", "corridorEndpointRules", "infrastructureStateSchema", "approvalBoundary", "provenance"})
ACTIVE_SCENARIO_KEYS: Final = frozenset({"count", "scenarioIds", "serviceCodes", "source"})
HAN_SCENARIO_KEYS: Final = frozenset({"count", "resources", "lifecycleValidationScope"})
SCENARIO_RESOURCE_KEYS: Final = frozenset({"scenarioId", "startYear", "resourcePath", "resourceName", "sha256"})
ENDPOINT_RULE_KEYS: Final = frozenset({"RouteNode", "AdministrativePlace", "AnchoredPlace", "PolityPresence", "RemoteGate"})
INFRASTRUCTURE_KEYS: Final = frozenset({"liveValues", "defaultPolicy", "fields"})
INFRASTRUCTURE_FIELD_KEYS: Final = frozenset({"grade", "condition", "capacity", "control", "access", "season", "damage"})
FIELD_SCHEMA_KEYS: Final = frozenset({"type"})
APPROVAL_KEYS: Final = frozenset({"candidateReviewState", "approvedCorridorCount", "approvedSnapshot"})
NETWORK_PROVENANCE_KEYS: Final = frozenset({"generator", "inputs"})
INPUTS_KEYS: Final = frozenset({"legacyHanMap", "routeNodeSelection", "legacyExternalPlaces", "scenarioCatalogService"})
INPUT_RECORD_KEYS: Final = frozenset({"path", "sha256"})


def _require_keys(document: JsonObject, expected: frozenset[str], label: str) -> None:
    if frozenset(document) != expected:
        raise ContractError(f"{label} exact schema key drift")


def _expected_inputs(source: SourceBundle) -> JsonObject:
    paths = {
        "legacyHanMap": source.paths.han,
        "routeNodeSelection": source.paths.selection,
        "legacyExternalPlaces": source.paths.external,
        "scenarioCatalogService": source.paths.scenario_service,
    }
    return {
        key: {
            "path": path.resolve().relative_to(source.paths.root.resolve()).as_posix(),
            "sha256": source.hashes[key],
        }
        for key, path in paths.items()
    }


def _validate_inputs(actual: JsonObject, expected: JsonObject) -> None:
    _require_keys(actual, INPUTS_KEYS, "provenance inputs")
    for key in INPUTS_KEYS:
        record = require_object(actual.get(key), f"{key} input provenance")
        _require_keys(record, INPUT_RECORD_KEYS, f"{key} input provenance")
    if actual != expected:
        raise ContractError("source input provenance path or SHA drift")


def _validate_registry_schema(registry: JsonObject) -> None:
    _require_keys(registry, REGISTRY_KEYS, "registry")
    _require_keys(require_object(registry.get("summary"), "registry summary"), REGISTRY_SUMMARY_KEYS, "registry summary")
    for raw in require_array(registry, "entries"):
        _require_keys(require_object(raw, "registry entry"), REGISTRY_ENTRY_KEYS, "registry entry")


def _validate_corridor_schema(corridor: JsonObject) -> None:
    _require_keys(corridor, CORRIDOR_KEYS, "corridor document")
    _require_keys(require_object(corridor.get("candidatePolicy"), "candidate policy"), CANDIDATE_POLICY_KEYS, "candidate policy")
    summary = require_object(corridor.get("summary"), "corridor summary")
    _require_keys(summary, CORRIDOR_SUMMARY_KEYS, "corridor summary")
    _require_keys(require_object(summary.get("replacementRiskCounts"), "replacement counts"), REPLACEMENT_COUNT_KEYS, "replacement counts")
    _require_keys(require_object(corridor.get("provenance"), "corridor provenance"), CORRIDOR_PROVENANCE_KEYS, "corridor provenance")
    for raw in require_array(corridor, "candidates"):
        row = require_object(raw, "corridor candidate")
        _require_keys(row, CORRIDOR_ROW_KEYS, "corridor candidate")
        provenance = require_object(row.get("legacyTopologyProvenance"), "topology provenance")
        _require_keys(provenance, TOPOLOGY_PROVENANCE_KEYS, "topology provenance")
        _require_keys(require_object(provenance.get("replacementRisk"), "replacement risk"), REPLACEMENT_RISK_KEYS, "replacement risk")
        for endpoint in require_array(provenance, "endpoints"):
            _require_keys(require_object(endpoint, "endpoint provenance"), ENDPOINT_PROVENANCE_KEYS, "endpoint provenance")


def _validate_corridor_binding(corridor: JsonObject, topology: Topology) -> None:
    rows = [require_object(raw, "corridor candidate") for raw in require_array(corridor, "candidates")]
    if len(rows) != len(topology.endpoint_pairs):
        raise ContractError("corridor source topology count drift")
    for row, pair in zip(rows, topology.endpoint_pairs, strict=True):
        endpoints = topology.provenance_by_pair[pair]
        endpoint_rows = [{"routeNodeKey": endpoint.route_node_key, "legacyCityId": endpoint.legacy_city_id, "legacyNodeFingerprint": endpoint.legacy_node_fingerprint, "legacyDisposition": endpoint.legacy_disposition} for endpoint in endpoints]
        replaced = sum(endpoint.legacy_disposition == "REPLACED" for endpoint in endpoints)
        expected: JsonObject = {"endpoints": endpoint_rows, "replacementRisk": {"touchesReplacedUnrelatedNode": replaced > 0, "replacedEndpointCount": replaced, "reviewInterpretation": "RISK_ONLY_NOT_APPROVAL_BASIS"}}
        if row.get("canonicalEndpointPair") != list(pair) or row.get("legacyTopologyProvenance") != expected:
            raise ContractError("corridor endpoint source provenance drift")


def _validate_external(external: JsonObject, source: SourceBundle) -> None:
    _require_keys(external, EXTERNAL_KEYS, "external document")
    _require_keys(require_object(external.get("summary"), "external summary"), EXTERNAL_SUMMARY_KEYS, "external summary")
    provenance = require_object(external.get("provenance"), "external provenance")
    _require_keys(provenance, EXTERNAL_PROVENANCE_KEYS, "external provenance")
    _require_keys(require_object(provenance.get("input"), "external input"), INPUT_RECORD_KEYS, "external input")
    source_rows = [require_object(raw, "source external row") for raw in require_array(source.external, "places")]
    rows = [require_object(raw, "external candidate") for raw in require_array(external, "candidates")]
    if external.get("legacySourceMeta") != source.external.get("_meta") or len(rows) != len(source_rows):
        raise ContractError("external source metadata or count drift")
    for index, (row, raw_source) in enumerate(zip(rows, source_rows, strict=True)):
        _require_keys(row, EXTERNAL_ROW_KEYS, "external candidate")
        legacy = require_object(row.get("legacyProvenance"), "external legacy provenance")
        _require_keys(legacy, LEGACY_PROVENANCE_KEYS, "external legacy provenance")
        source_id = require_text(raw_source, "id")
        expected: JsonObject = {"candidateKey": f"legacy-external:{source_id}", "reviewState": "PENDING", "runtimeActivation": RUNTIME_BOUNDARY, "legacyLifecycleStatus": "UNBOUNDED_DEFAULT_REQUIRES_REVIEW", "legacyProvenance": {"rowIndex": index, "sourceId": source_id}, "rawLegacy": raw_source}
        if raw_source.get("begYr") != -9999 or raw_source.get("endYr") != 9999 or row != expected:
            raise ContractError("external source row or quarantine lifecycle drift")


def _validate_network_schema(network: JsonObject) -> None:
    _require_keys(network, NETWORK_KEYS, "network document")
    _require_keys(require_object(network.get("activeProductScenarios"), "active scenarios"), ACTIVE_SCENARIO_KEYS, "active scenarios")
    scenarios = require_object(network.get("hanMapContractScenarios"), "Han scenarios")
    _require_keys(scenarios, HAN_SCENARIO_KEYS, "Han scenarios")
    for raw in require_array(scenarios, "resources"):
        _require_keys(require_object(raw, "scenario resource"), SCENARIO_RESOURCE_KEYS, "scenario resource")
    _require_keys(require_object(network.get("corridorEndpointRules"), "endpoint rules"), ENDPOINT_RULE_KEYS, "endpoint rules")
    infrastructure = require_object(network.get("infrastructureStateSchema"), "infrastructure schema")
    _require_keys(infrastructure, INFRASTRUCTURE_KEYS, "infrastructure schema")
    fields = require_object(infrastructure.get("fields"), "infrastructure fields")
    _require_keys(fields, INFRASTRUCTURE_FIELD_KEYS, "infrastructure fields")
    for name in INFRASTRUCTURE_FIELD_KEYS:
        _require_keys(require_object(fields.get(name), f"{name} field"), FIELD_SCHEMA_KEYS, f"{name} field")
    _require_keys(require_object(network.get("approvalBoundary"), "approval boundary"), APPROVAL_KEYS, "approval boundary")
    _require_keys(require_object(network.get("provenance"), "network provenance"), NETWORK_PROVENANCE_KEYS, "network provenance")


def _validate_network_binding(network: JsonObject, source: SourceBundle, expected_inputs: JsonObject) -> None:
    active = require_object(network.get("activeProductScenarios"), "active scenarios")
    scenarios = require_object(network.get("hanMapContractScenarios"), "Han scenarios")
    provenance = require_object(network.get("provenance"), "network provenance")
    active_source = require_object(active.get("source"), "active scenario source")
    _require_keys(active_source, INPUT_RECORD_KEYS, "active scenario source")
    inputs = require_object(provenance.get("inputs"), "network inputs")
    _validate_inputs(inputs, expected_inputs)
    if active_source != expected_inputs["scenarioCatalogService"] or scenarios.get("resources") != list(source.scenarios):
        raise ContractError("network scenario source binding drift")


def validate_source_binding(documents: GeneratedDocuments, source: SourceBundle, topology: Topology) -> None:
    registry = documents["route-corridor-key-registry-v1.json"]
    corridor = documents["route-corridor-candidates-v1.json"]
    external = documents["external-world-candidates-v1.json"]
    network = documents["route-network-contract-v1.json"]
    expected_inputs = _expected_inputs(source)
    _validate_registry_schema(registry)
    _validate_corridor_schema(corridor)
    corridor_provenance = require_object(corridor.get("provenance"), "corridor provenance")
    _validate_inputs(require_object(corridor_provenance.get("inputs"), "corridor inputs"), expected_inputs)
    if corridor_provenance.get("generator") != "tools/map/build_route_corridor_candidates.py" or corridor_provenance.get("numericUndirectedEdgeSha256") != EXPECTED_NUMERIC_EDGE_SHA or corridor_provenance.get("routeKeyUndirectedEdgeSha256") != EXPECTED_ROUTE_KEY_EDGE_SHA:
        raise ContractError("corridor generator or topology SHA provenance drift")
    _validate_corridor_binding(corridor, topology)
    _validate_external(external, source)
    external_provenance = require_object(external.get("provenance"), "external provenance")
    if external_provenance.get("generator") != "tools/map/build_route_corridor_candidates.py" or external_provenance.get("input") != expected_inputs["legacyExternalPlaces"]:
        raise ContractError("external input provenance drift")
    _validate_network_schema(network)
    _validate_network_binding(network, source, expected_inputs)
    network_provenance = require_object(network.get("provenance"), "network provenance")
    if network_provenance.get("generator") != "tools/map/build_route_corridor_candidates.py":
        raise ContractError("network generator provenance drift")
