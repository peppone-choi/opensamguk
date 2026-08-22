from __future__ import annotations

import hashlib
import re
from typing import Final, TypeAlias

from tools.map.route_network_contract import (
    ALLOWED_CORRIDOR_MODES,
    EXPECTED_REGISTRY_SHA,
    EXTERNAL_ENTITY_TYPES,
    RUNTIME_BOUNDARY,
    ContractError,
    EndpointPair,
    JsonObject,
    SourceBundle,
    Topology,
    pair_key_index,
    require_array,
    require_integer,
    require_object,
    require_text,
    serialize,
)
from tools.map.route_network_source_validation import validate_source_binding

GeneratedDocuments: TypeAlias = dict[str, JsonObject]
EXPECTED_REPLACEMENT_COUNTS: Final = {
    "noReplacementEndpoint": 1444,
    "atLeastOneReplacedEndpoint": 339,
    "bothEndpointsReplaced": 53,
}
EXPECTED_CANDIDATE_POLICY: Final = {
    "automaticApprovalCount": 0,
    "automaticModeAssignmentCount": 0,
    "automaticGeometryAssignmentCount": 0,
    "automaticSourceClaimCount": 0,
}
EXPECTED_HAN_SCENARIO_IDS: Final = ("0", "1", "2", "900", "901", "902", "903", "905", "906", "908", "910", "911", "912", "913", "914", "1010", "1020", "1021", "1030", "1031", "1040", "1041", "1050", "1060", "1070", "1080", "1090", "1100", "1110", "1120", "9200")


def _validate_registry(registry: JsonObject) -> dict[EndpointPair, str]:
    if hashlib.sha256(serialize(registry).encode()).hexdigest() != EXPECTED_REGISTRY_SHA:
        raise ContractError("canonical corridor registry SHA or identity drift")
    if (
        registry.get("schemaVersion") != 1
        or registry.get("registryId") != "han-route-corridor-key-registry-v1"
        or registry.get("identityPolicy")
        != "INDEPENDENT_UUID4_PRESERVED_BY_CANONICAL_ENDPOINT_PAIR"
    ):
        raise ContractError("corridor registry schema, id, or identity policy drift")
    indexed = pair_key_index(registry, "entries")
    summary = require_object(registry.get("summary"), "registry summary")
    if summary != {"entryCount": 1783, "uuidVersion": 4}:
        raise ContractError("corridor registry summary drift")
    if len(indexed) != 1783 or len(set(indexed.values())) != 1783:
        raise ContractError("corridor registry must contain 1,783 unique pairs and keys")
    return indexed


def _replacement_count(candidate: JsonObject, pair: EndpointPair) -> int:
    provenance = require_object(candidate.get("legacyTopologyProvenance"), "legacy topology provenance")
    endpoints = [require_object(row, "endpoint provenance") for row in require_array(provenance, "endpoints")]
    if len(endpoints) != 2:
        raise ContractError("endpoint provenance must contain exactly two endpoints")
    replaced_count = 0
    for index, endpoint in enumerate(endpoints):
        if endpoint.get("routeNodeKey") != pair[index]:
            raise ContractError("endpoint provenance order must match canonical endpoint pair")
        fingerprint = require_text(endpoint, "legacyNodeFingerprint")
        disposition = require_text(endpoint, "legacyDisposition")
        _ = require_integer(endpoint, "legacyCityId")
        if re.fullmatch(r"sha256:[0-9a-f]{64}", fingerprint) is None:
            raise ContractError("endpoint legacy fingerprint is malformed")
        if disposition not in {"RETAINED", "REPLACED"}:
            raise ContractError("endpoint legacy disposition is malformed")
        replaced_count += disposition == "REPLACED"
    risk = require_object(provenance.get("replacementRisk"), "replacement risk")
    expected_risk: JsonObject = {
        "touchesReplacedUnrelatedNode": replaced_count > 0,
        "replacedEndpointCount": replaced_count,
        "reviewInterpretation": "RISK_ONLY_NOT_APPROVAL_BASIS",
    }
    if risk != expected_risk:
        raise ContractError("candidate replacement risk differs from endpoint provenance")
    return replaced_count


def _validate_corridors(corridor: JsonObject, registry_index: dict[EndpointPair, str]) -> None:
    if (
        corridor.get("schemaVersion") != 1
        or corridor.get("candidateSetId") != "han-route-corridor-candidates-v1"
        or corridor.get("reviewState") != "PENDING"
    ):
        raise ContractError("corridor candidate set schema, id, or PENDING review state drift")
    if corridor.get("runtimeActivation") != RUNTIME_BOUNDARY:
        raise ContractError("corridor candidate runtime activation is outside W1-A")
    if require_object(corridor.get("candidatePolicy"), "candidate policy") != EXPECTED_CANDIDATE_POLICY:
        raise ContractError("corridor candidate policy must keep all automatic counts at zero")
    candidates = [require_object(row, "corridor candidate") for row in require_array(corridor, "candidates")]
    forbidden = {"mode", "modes", "geometry", "geometryRef", "sourceClaims", "supportBasis"}
    if len(candidates) != 1783 or pair_key_index(corridor, "candidates") != registry_index:
        raise ContractError("corridor candidates must match the key registry exactly")
    if any(
        row.get("reviewState") != "PENDING"
        or row.get("legacyTopologyClass") != "LEGACY_TOPOLOGY_UNCLASSIFIED"
        or forbidden.intersection(row)
        for row in candidates
    ):
        raise ContractError("corridor candidates must remain PENDING without mode or geography")
    counts = [_replacement_count(row, pair) for row, pair in zip(candidates, registry_index, strict=True)]
    derived = {
        "noReplacementEndpoint": counts.count(0),
        "atLeastOneReplacedEndpoint": sum(count > 0 for count in counts),
        "bothEndpointsReplaced": counts.count(2),
    }
    summary = require_object(corridor.get("summary"), "corridor summary")
    if derived != EXPECTED_REPLACEMENT_COUNTS or summary.get("replacementRiskCounts") != derived:
        raise ContractError("corridor replacement risk summary drift")
    expected_summary = {
        "routeNodeCount": 780,
        "candidateCount": 1783,
        "directedConnectionCount": 3566,
        "reviewStateCounts": {"PENDING": 1783},
        "selfEdgeCount": 0,
        "danglingEndpointCount": 0,
        "duplicateEdgeCount": 0,
        "asymmetricConnectionCount": 0,
        "connectedComponentCount": 1,
        "minimumDegree": 1,
        "graphStatus": "LEGACY_CANDIDATE_BASELINE_NOT_APPROVAL",
        "replacementRiskCounts": derived,
    }
    if summary != expected_summary:
        raise ContractError("corridor summary does not match candidate invariants")


def _validate_external(external: JsonObject) -> None:
    if (
        external.get("schemaVersion") != 1
        or external.get("candidateSetId") != "han-external-world-candidates-v1"
        or external.get("reviewState") != "PENDING"
        or external.get("runtimeActivation") != RUNTIME_BOUNDARY
    ):
        raise ContractError("external candidate set boundary drift")
    if external.get("classificationPolicy") != "NO_W1_A_FINAL_EXTERNAL_TYPE_ASSIGNMENT":
        raise ContractError("external classification policy drift")
    rows = [require_object(row, "external candidate") for row in require_array(external, "candidates")]
    keys: set[str] = set()
    source_ids: set[str] = set()
    for index, row in enumerate(rows):
        provenance = require_object(row.get("legacyProvenance"), "external legacy provenance")
        raw = require_object(row.get("rawLegacy"), "raw external legacy row")
        candidate_key = require_text(row, "candidateKey")
        source_id = require_text(provenance, "sourceId")
        if (
            row.get("reviewState") != "PENDING"
            or row.get("runtimeActivation") != RUNTIME_BOUNDARY
            or row.get("legacyLifecycleStatus") != "UNBOUNDED_DEFAULT_REQUIRES_REVIEW"
            or require_integer(provenance, "rowIndex") != index
            or require_text(raw, "id") != source_id
            or candidate_key != f"legacy-external:{source_id}"
            or candidate_key in keys
            or source_id in source_ids
        ):
            raise ContractError("external candidate identity, provenance, or lifecycle drift")
        keys.add(candidate_key)
        source_ids.add(source_id)
    expected_summary = {
        "candidateCount": 65,
        "reviewStateCounts": {"PENDING": 65},
        "legacyLifecycleStatusCounts": {"UNBOUNDED_DEFAULT_REQUIRES_REVIEW": 65},
        "runtimeActiveCount": 0,
    }
    if len(rows) != 65 or require_object(external.get("summary"), "external summary") != expected_summary:
        raise ContractError("external candidate summary must describe 65 pending rows")


def _validate_network(network: JsonObject) -> None:
    if network.get("schemaVersion") != 1 or network.get("contractId") != "han-route-network-contract-v1":
        raise ContractError("route network contract schema or id drift")
    active = require_object(network.get("activeProductScenarios"), "activeProductScenarios")
    scenarios = require_object(network.get("hanMapContractScenarios"), "hanMapContractScenarios")
    expected_active = ["1010", "1020", "1021", "1030", "1031", "1040", "1041", "1050", "1060", "1070", "1080", "1090", "1100", "1110", "1120"]
    if active.get("count") != 15 or active.get("scenarioIds") != expected_active:
        raise ContractError("product scenario contract must contain exactly 15 codes")
    if active.get("serviceCodes") != [f"scenario_{code}" for code in expected_active]:
        raise ContractError("product scenario codes must match ScenarioCatalogService exactly")
    resources = [require_object(row, "scenario resource") for row in require_array(scenarios, "resources")]
    scenario_ids = tuple(require_text(row, "scenarioId") for row in resources)
    if scenarios.get("count") != 31 or scenario_ids != EXPECTED_HAN_SCENARIO_IDS:
        raise ContractError("Han map contract must contain exactly 31 scenario resources")
    if scenarios.get("lifecycleValidationScope") != "ALL_31_HAN_MAP_CONTRACT_RESOURCES":
        raise ContractError("Han scenario lifecycle validation scope drift")
    if network.get("allowedCorridorModes") != list(ALLOWED_CORRIDOR_MODES):
        raise ContractError("corridor mode contract drift")
    if network.get("externalEntityTypes") != list(EXTERNAL_ENTITY_TYPES):
        raise ContractError("external entity type contract drift")
    expected_rules = {"RouteNode": "ALLOWED", "AdministrativePlace": "FORBIDDEN", "AnchoredPlace": "ALLOWED", "PolityPresence": "FORBIDDEN", "RemoteGate": "TERMINAL_ONLY"}
    if network.get("corridorEndpointRules") != expected_rules:
        raise ContractError("corridor endpoint rule contract drift")
    infrastructure = require_object(network.get("infrastructureStateSchema"), "infrastructureStateSchema")
    field_types = {"grade": "INTEGER", "condition": "DECIMAL", "capacity": "INTEGER", "control": "ENTITY_REF", "access": "STRING_SET", "season": "STRING", "damage": "DECIMAL"}
    expected_fields = {name: {"type": field_type} for name, field_type in field_types.items()}
    if infrastructure != {"liveValues": "NOT_MATERIALIZED_BY_W1_A_DATA_CONTRACT", "defaultPolicy": "NO_DEFAULTS_DEFINED", "fields": expected_fields}:
        raise ContractError("infrastructure schema cannot define live values or defaults")
    expected_approval = {"candidateReviewState": "PENDING", "approvedCorridorCount": 0, "approvedSnapshot": "NOT_CREATED_BY_W1_A"}
    if network.get("approvalBoundary") != expected_approval:
        raise ContractError("route network approval boundary drift")
    if network.get("runtimeActivation") != RUNTIME_BOUNDARY:
        raise ContractError("route network runtime activation is outside W1-A")


def validate_documents(documents: GeneratedDocuments, source: SourceBundle, topology: Topology) -> None:
    registry = documents["route-corridor-key-registry-v1.json"]
    _validate_corridors(documents["route-corridor-candidates-v1.json"], _validate_registry(registry))
    _validate_external(documents["external-world-candidates-v1.json"])
    _validate_network(documents["route-network-contract-v1.json"])
    validate_source_binding(documents, source, topology)
