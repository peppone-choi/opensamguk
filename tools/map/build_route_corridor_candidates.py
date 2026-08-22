#!/usr/bin/env python3
# /// script
# requires-python = ">=3.11"
# dependencies = []
# ///
# ─── How to run ───
# uv run tools/map/build_route_corridor_candidates.py
# uv run tools/map/build_route_corridor_candidates.py --check

from __future__ import annotations

import argparse
import sys
from pathlib import Path
from typing import Final, TypeAlias

ROOT: Final = Path(__file__).resolve().parents[2]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from tools.map.route_network_contract import (
    ALLOWED_CORRIDOR_MODES,
    EXPECTED_NUMERIC_EDGE_SHA,
    EXPECTED_REGISTRY_SHA,
    EXPECTED_ROUTE_KEY_EDGE_SHA,
    EXTERNAL_ENTITY_TYPES,
    RUNTIME_BOUNDARY,
    ContractError,
    InputPaths,
    JsonObject,
    SourceBundle,
    Topology,
    build_registry,
    build_topology,
    load_object,
    load_sources,
    pair_key_index,
    require_array,
    require_object,
    require_text,
    serialize,
    sha256,
)
from tools.map.route_network_validation import validate_documents

DEFAULT_HAN: Final = ROOT / "infra/src/main/resources/map/han.json"
DEFAULT_SELECTION: Final = ROOT / "data/curated/han/route-node-selection-v1.json"
DEFAULT_EXTERNAL: Final = ROOT / "data/map/external-places.json"
DEFAULT_SERVICE: Final = ROOT / "app/gateway-api/src/main/kotlin/opensamguk/gateway/service/ScenarioCatalogService.kt"
GeneratedDocuments: TypeAlias = dict[str, JsonObject]


def _input_provenance(source: SourceBundle) -> JsonObject:
    return {
        key: {
            "path": path.resolve().relative_to(source.paths.root.resolve()).as_posix(),
            "sha256": source.hashes[key],
        }
        for key, path in {
            "legacyHanMap": source.paths.han,
            "routeNodeSelection": source.paths.selection,
            "legacyExternalPlaces": source.paths.external,
            "scenarioCatalogService": source.paths.scenario_service,
        }.items()
    }


def _candidate(topology: Topology, pair: tuple[str, str], corridor_key: str) -> JsonObject:
    endpoints = topology.provenance_by_pair[pair]
    replaced_count = sum(endpoint.legacy_disposition == "REPLACED" for endpoint in endpoints)
    return {
        "corridorKey": corridor_key,
        "canonicalEndpointPair": list(pair),
        "reviewState": "PENDING",
        "legacyTopologyClass": "LEGACY_TOPOLOGY_UNCLASSIFIED",
        "legacyTopologyProvenance": {
            "endpoints": [
                {
                    "routeNodeKey": endpoint.route_node_key,
                    "legacyCityId": endpoint.legacy_city_id,
                    "legacyNodeFingerprint": endpoint.legacy_node_fingerprint,
                    "legacyDisposition": endpoint.legacy_disposition,
                }
                for endpoint in endpoints
            ],
            "replacementRisk": {
                "touchesReplacedUnrelatedNode": replaced_count > 0,
                "replacedEndpointCount": replaced_count,
                "reviewInterpretation": "RISK_ONLY_NOT_APPROVAL_BASIS",
            },
        },
    }


def build_corridor_candidates(
    source: SourceBundle,
    topology: Topology,
    registry: JsonObject,
) -> JsonObject:
    keys = pair_key_index(registry, "entries")
    candidates = [_candidate(topology, pair, keys[pair]) for pair in topology.endpoint_pairs]
    return require_object({
        "schemaVersion": 1,
        "candidateSetId": "han-route-corridor-candidates-v1",
        "reviewState": "PENDING",
        "runtimeActivation": RUNTIME_BOUNDARY,
        "candidatePolicy": {
            "automaticApprovalCount": 0,
            "automaticModeAssignmentCount": 0,
            "automaticGeometryAssignmentCount": 0,
            "automaticSourceClaimCount": 0,
        },
        "summary": {
            "routeNodeCount": 780,
            "candidateCount": len(candidates),
            "directedConnectionCount": topology.directed_count,
            "reviewStateCounts": {"PENDING": len(candidates)},
            "selfEdgeCount": 0,
            "danglingEndpointCount": 0,
            "duplicateEdgeCount": 0,
            "asymmetricConnectionCount": 0,
            "connectedComponentCount": 1,
            "minimumDegree": topology.minimum_degree,
            "graphStatus": "LEGACY_CANDIDATE_BASELINE_NOT_APPROVAL",
            "replacementRiskCounts": {
                "noReplacementEndpoint": topology.no_replacement_endpoint_count,
                "atLeastOneReplacedEndpoint": topology.at_least_one_replaced_endpoint_count,
                "bothEndpointsReplaced": topology.both_endpoints_replaced_count,
            },
        },
        "provenance": {
            "generator": "tools/map/build_route_corridor_candidates.py",
            "inputs": _input_provenance(source),
            "numericUndirectedEdgeSha256": EXPECTED_NUMERIC_EDGE_SHA,
            "routeKeyUndirectedEdgeSha256": EXPECTED_ROUTE_KEY_EDGE_SHA,
        },
        "candidates": candidates,
    }, "generated corridor candidates")


def build_external_candidates(source: SourceBundle) -> JsonObject:
    places = [require_object(row, "legacy external place") for row in require_array(source.external, "places")]
    ids = [require_text(row, "id") for row in places]
    if len(places) != 65 or len(ids) != len(set(ids)):
        raise ContractError("legacy external inventory must contain exactly 65 unique rows")
    if any(row.get("begYr") != -9999 or row.get("endYr") != 9999 for row in places):
        raise ContractError("legacy external lifecycle baseline drift")
    candidates = [
        {
            "candidateKey": f"legacy-external:{legacy_id}",
            "reviewState": "PENDING",
            "runtimeActivation": RUNTIME_BOUNDARY,
            "legacyLifecycleStatus": "UNBOUNDED_DEFAULT_REQUIRES_REVIEW",
            "legacyProvenance": {"rowIndex": index, "sourceId": legacy_id},
            "rawLegacy": dict(row),
        }
        for index, (legacy_id, row) in enumerate(zip(ids, places, strict=True))
    ]
    return require_object({
        "schemaVersion": 1,
        "candidateSetId": "han-external-world-candidates-v1",
        "reviewState": "PENDING",
        "runtimeActivation": RUNTIME_BOUNDARY,
        "classificationPolicy": "NO_W1_A_FINAL_EXTERNAL_TYPE_ASSIGNMENT",
        "legacySourceMeta": dict(require_object(source.external.get("_meta"), "external source metadata")),
        "summary": {
            "candidateCount": len(candidates),
            "reviewStateCounts": {"PENDING": len(candidates)},
            "legacyLifecycleStatusCounts": {"UNBOUNDED_DEFAULT_REQUIRES_REVIEW": len(candidates)},
            "runtimeActiveCount": 0,
        },
        "provenance": {
            "generator": "tools/map/build_route_corridor_candidates.py",
            "input": _input_provenance(source)["legacyExternalPlaces"],
        },
        "candidates": candidates,
    }, "generated external candidates")


def build_network_contract(source: SourceBundle) -> JsonObject:
    scenario_ids = list(source.active_product_codes)
    return require_object({
        "schemaVersion": 1,
        "contractId": "han-route-network-contract-v1",
        "runtimeActivation": RUNTIME_BOUNDARY,
        "activeProductScenarios": {
            "count": len(scenario_ids),
            "scenarioIds": scenario_ids,
            "serviceCodes": [f"scenario_{scenario_id}" for scenario_id in scenario_ids],
            "source": _input_provenance(source)["scenarioCatalogService"],
        },
        "hanMapContractScenarios": {
            "count": len(source.scenarios),
            "resources": [dict(row) for row in source.scenarios],
            "lifecycleValidationScope": "ALL_31_HAN_MAP_CONTRACT_RESOURCES",
        },
        "allowedCorridorModes": list(ALLOWED_CORRIDOR_MODES),
        "externalEntityTypes": list(EXTERNAL_ENTITY_TYPES),
        "corridorEndpointRules": {
            "RouteNode": "ALLOWED",
            "AdministrativePlace": "FORBIDDEN",
            "AnchoredPlace": "ALLOWED",
            "PolityPresence": "FORBIDDEN",
            "RemoteGate": "TERMINAL_ONLY",
        },
        "infrastructureStateSchema": {
            "liveValues": "NOT_MATERIALIZED_BY_W1_A_DATA_CONTRACT",
            "defaultPolicy": "NO_DEFAULTS_DEFINED",
            "fields": {
                "grade": {"type": "INTEGER"},
                "condition": {"type": "DECIMAL"},
                "capacity": {"type": "INTEGER"},
                "control": {"type": "ENTITY_REF"},
                "access": {"type": "STRING_SET"},
                "season": {"type": "STRING"},
                "damage": {"type": "DECIMAL"},
            },
        },
        "approvalBoundary": {
            "candidateReviewState": "PENDING",
            "approvedCorridorCount": 0,
            "approvedSnapshot": "NOT_CREATED_BY_W1_A",
        },
        "provenance": {
            "generator": "tools/map/build_route_corridor_candidates.py",
            "inputs": _input_provenance(source),
        },
    }, "generated route network contract")


def build_documents(source: SourceBundle, topology: Topology, existing_registry: JsonObject) -> GeneratedDocuments:
    registry = build_registry(topology, existing_registry)
    return {
        "route-network-contract-v1.json": build_network_contract(source),
        "route-corridor-key-registry-v1.json": registry,
        "route-corridor-candidates-v1.json": build_corridor_candidates(source, topology, registry),
        "external-world-candidates-v1.json": build_external_candidates(source),
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output-dir", type=Path, default=ROOT / "data/curated/han")
    parser.add_argument("--han", type=Path, default=DEFAULT_HAN)
    parser.add_argument("--selection", type=Path, default=DEFAULT_SELECTION)
    parser.add_argument("--external", type=Path, default=DEFAULT_EXTERNAL)
    parser.add_argument("--scenario-service", type=Path, default=DEFAULT_SERVICE)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    try:
        paths = InputPaths(ROOT, args.han, args.selection, args.external, args.scenario_service)
        source = load_sources(paths)
        registry_path = args.output_dir / "route-corridor-key-registry-v1.json"
        if not registry_path.is_file() or sha256(registry_path) != EXPECTED_REGISTRY_SHA:
            raise ContractError("canonical corridor registry is missing or its SHA drifted")
        existing_registry = load_object(registry_path)
        topology = build_topology(source)
        documents = build_documents(source, topology, existing_registry)
        validate_documents(documents, source, topology)
        if args.check:
            for name, document in documents.items():
                path = args.output_dir / name
                if not path.is_file() or path.read_text(encoding="utf-8") != serialize(document):
                    print(f"W1-A route candidate drift: {name}", file=sys.stderr)
                    return 1
            print("W1-A route corridor candidates: no drift")
            return 0
        args.output_dir.mkdir(parents=True, exist_ok=True)
        for name, document in documents.items():
            (args.output_dir / name).write_text(serialize(document), encoding="utf-8")
        print("W1-A route corridor candidates: generated 1783 corridors and 65 external rows")
        return 0
    except (OSError, UnicodeError, ValueError) as error:
        print(f"W1-A route corridor candidate build failed: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
