from __future__ import annotations

import hashlib
import json
import re
from collections import Counter, deque
from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from pathlib import Path
from typing import Final, TypeAlias
from uuid import UUID

JsonValue: TypeAlias = str | int | float | bool | None | Sequence["JsonValue"] | Mapping[str, "JsonValue"]
JsonObject: TypeAlias = dict[str, JsonValue]
EndpointPair: TypeAlias = tuple[str, str]

EXPECTED_SOURCE_HASHES: Final = {
    "legacyHanMap": "2a2cd0c5813bbdd037c0cad41dc2ccd34c582830aacadb1ad8c135985f4d3a58",
    "routeNodeSelection": "e2f2f1aec914071fbf8658ceacb099cbd9948f91766139eaa1316a87017f8c4a",
    "legacyExternalPlaces": "33cd7fbc2068b0552bc557e879ada0230596365f440db1719133a2dae05d20fe",
    "scenarioCatalogService": "e0a60532dcd47c7d8fd222aa153d03a73b381733be686ed795fc4be09c1b8f7c",
}
EXPECTED_NUMERIC_EDGE_SHA: Final = "2c3ca02f316ee409e1045e1eda8d57c0aa048b9b21a3ec868595f5b5ac0032cf"
EXPECTED_ROUTE_KEY_EDGE_SHA: Final = "0a9742971f63840e96291979ba3e900d8f07f2aecd681025ab017fb920144377"
EXPECTED_REGISTRY_SHA: Final = "fd2e7a823245db1717d3751e3e5bdd813c3b649a4e0186669912ebcee4a1e8b3"
RUNTIME_BOUNDARY: Final = "NOT_CLAIMED_BY_W1_DATA_CONTRACT"
ALLOWED_CORRIDOR_MODES: Final = ("ROAD", "PASS", "BRIDGE", "FORD", "WATERWAY", "SEA_ROUTE", "STEPPE_CORRIDOR")
EXTERNAL_ENTITY_TYPES: Final = ("AdministrativePlace", "AnchoredPlace", "PolityPresence", "RemoteGate")


@dataclass(frozen=True, slots=True)
class ContractError(ValueError):
    message: str

    def __str__(self) -> str:
        return self.message


@dataclass(frozen=True, slots=True)
class InputPaths:
    root: Path
    han: Path
    selection: Path
    external: Path
    scenario_service: Path


@dataclass(frozen=True, slots=True)
class SourceBundle:
    paths: InputPaths
    han: JsonObject
    selection: JsonObject
    external: JsonObject
    hashes: dict[str, str]
    scenarios: tuple[JsonObject, ...]
    active_product_codes: tuple[str, ...]


@dataclass(frozen=True, slots=True)
class RouteNodeProvenance:
    route_node_key: str
    legacy_city_id: int
    legacy_node_fingerprint: str
    legacy_disposition: str


@dataclass(frozen=True, slots=True)
class Topology:
    endpoint_pairs: tuple[EndpointPair, ...]
    provenance_by_pair: dict[EndpointPair, tuple[RouteNodeProvenance, RouteNodeProvenance]]
    directed_count: int
    minimum_degree: int
    no_replacement_endpoint_count: int
    at_least_one_replaced_endpoint_count: int
    both_endpoints_replaced_count: int


def require_object(value: JsonValue, label: str) -> JsonObject:
    if not isinstance(value, dict):
        raise ContractError(f"{label} must be an object")
    return value


def require_array(container: JsonObject, key: str) -> list[JsonValue]:
    value = container.get(key)
    if not isinstance(value, list):
        raise ContractError(f"{key} must be an array")
    return value


def require_text(container: JsonObject, key: str) -> str:
    value = container.get(key)
    if not isinstance(value, str) or not value:
        raise ContractError(f"{key} must be a non-empty string")
    return value


def require_integer(container: JsonObject, key: str) -> int:
    value = container.get(key)
    if not isinstance(value, int) or isinstance(value, bool):
        raise ContractError(f"{key} must be an integer")
    return value


def load_object(path: Path) -> JsonObject:
    value: JsonValue = json.loads(path.read_text(encoding="utf-8"))
    return require_object(value, path.name)


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def serialize(document: JsonObject) -> str:
    return json.dumps(document, ensure_ascii=False, indent=2, sort_keys=True) + "\n"


def _scenario_resources(paths: InputPaths, selection: JsonObject) -> tuple[JsonObject, ...]:
    catalog = require_object(selection.get("scenarioCatalog"), "scenarioCatalog")
    resources = tuple(require_object(row, "scenario resource") for row in require_array(catalog, "resources"))
    if catalog.get("resourceCount") != 31 or len(resources) != 31:
        raise ContractError("Han map contract must contain exactly 31 scenario resources")
    ids: set[str] = set()
    for row in resources:
        scenario_id = require_text(row, "scenarioId")
        resource_path = paths.root / require_text(row, "resourcePath")
        if scenario_id in ids or not resource_path.is_file():
            raise ContractError(f"duplicate or missing scenario resource: {scenario_id}")
        resource = load_object(resource_path)
        if row.get("sha256") != sha256(resource_path) or row.get("startYear") != resource.get("startYear"):
            raise ContractError(f"scenario resource drift: {scenario_id}")
        ids.add(scenario_id)
    return tuple(sorted(resources, key=lambda row: int(require_text(row, "scenarioId"))))


def load_sources(paths: InputPaths) -> SourceBundle:
    documents = {
        "legacyHanMap": load_object(paths.han),
        "routeNodeSelection": load_object(paths.selection),
        "legacyExternalPlaces": load_object(paths.external),
    }
    hashes = {
        "legacyHanMap": sha256(paths.han),
        "routeNodeSelection": sha256(paths.selection),
        "legacyExternalPlaces": sha256(paths.external),
        "scenarioCatalogService": sha256(paths.scenario_service),
    }
    if hashes != EXPECTED_SOURCE_HASHES:
        raise ContractError("W1-A source hash drift")
    service_text = paths.scenario_service.read_text(encoding="utf-8")
    active = tuple(re.findall(r'"scenario_([0-9]+)"', service_text))
    if len(active) != 15 or len(set(active)) != 15:
        raise ContractError("ScenarioCatalogService must expose exactly 15 unique product scenarios")
    return SourceBundle(
        paths=paths,
        han=documents["legacyHanMap"],
        selection=documents["routeNodeSelection"],
        external=documents["legacyExternalPlaces"],
        hashes=hashes,
        scenarios=_scenario_resources(paths, documents["routeNodeSelection"]),
        active_product_codes=active,
    )


def _canonical_pair(left: str, right: str) -> EndpointPair:
    return (left, right) if left < right else (right, left)


def build_topology(source: SourceBundle) -> Topology:
    nodes = [require_object(row, "route node") for row in require_array(source.selection, "routeNodes")]
    if len(nodes) != 780 or any(row.get("reviewState") != "APPROVED" for row in nodes):
        raise ContractError("route-node selection must contain exactly 780 approved nodes")
    node_index = {
        require_integer(row, "legacyCityId"): RouteNodeProvenance(
            route_node_key=require_text(row, "routeNodeKey"),
            legacy_city_id=require_integer(row, "legacyCityId"),
            legacy_node_fingerprint=require_text(row, "legacyNodeFingerprint"),
            legacy_disposition=require_text(row, "legacyDisposition"),
        )
        for row in nodes
    }
    route_keys = {legacy_id: node.route_node_key for legacy_id, node in node_index.items()}
    if set(route_keys) != set(range(1, 781)) or len(set(route_keys.values())) != 780:
        raise ContractError("route-node identities must be exact and unique for legacy ids 1..780")
    if any(
        not node.legacy_node_fingerprint.startswith("sha256:")
        or node.legacy_disposition not in {"RETAINED", "REPLACED"}
        for node in node_index.values()
    ):
        raise ContractError("route-node legacy provenance is malformed")
    cities = [require_object(row, "legacy city") for row in require_array(source.han, "cities")]
    city_ids = {require_integer(city, "id") for city in cities}
    if city_ids != set(route_keys):
        raise ContractError("legacy map city ids differ from reviewed RouteNodes")
    directed: set[tuple[int, int]] = set()
    degrees: Counter[int] = Counter()
    for city in cities:
        origin = require_integer(city, "id")
        connections = require_array(city, "connections")
        if len(connections) != len({str(value) for value in connections}):
            raise ContractError(f"duplicate directed connection from {origin}")
        for value in connections:
            if not isinstance(value, int) or isinstance(value, bool) or value not in city_ids or value == origin:
                raise ContractError(f"dangling or self connection from {origin}")
            directed.add((origin, value))
            degrees[origin] += 1
    if any((target, origin) not in directed for origin, target in directed):
        raise ContractError("legacy connections are asymmetric")
    legacy_pairs = tuple(sorted({tuple(sorted(edge)) for edge in directed}))
    endpoint_pairs = tuple(sorted(_canonical_pair(route_keys[a], route_keys[b]) for a, b in legacy_pairs))
    numeric_hash = hashlib.sha256("".join(f"{a}:{b}\n" for a, b in legacy_pairs).encode()).hexdigest()
    route_hash = hashlib.sha256("".join(f"{a}:{b}\n" for a, b in endpoint_pairs).encode()).hexdigest()
    if len(directed) != 3566 or len(legacy_pairs) != 1783 or len(endpoint_pairs) != len(set(endpoint_pairs)):
        raise ContractError("legacy topology counts or canonical endpoint pairs drifted")
    if numeric_hash != EXPECTED_NUMERIC_EDGE_SHA or route_hash != EXPECTED_ROUTE_KEY_EDGE_SHA:
        raise ContractError("legacy topology edge hash drift")
    reached = {1}
    queue = deque([1])
    adjacency: dict[int, set[int]] = {city_id: set() for city_id in city_ids}
    for left, right in legacy_pairs:
        adjacency[left].add(right)
        adjacency[right].add(left)
    while queue:
        for target in adjacency[queue.popleft()] - reached:
            reached.add(target)
            queue.append(target)
    if reached != city_ids or min(degrees.values()) < 1:
        raise ContractError("legacy candidate topology must be connected with degree >= 1")
    provenance_by_pair: dict[EndpointPair, tuple[RouteNodeProvenance, RouteNodeProvenance]] = {}
    for left_id, right_id in legacy_pairs:
        endpoints = (node_index[left_id], node_index[right_id])
        ordered = (endpoints[1], endpoints[0]) if endpoints[0].route_node_key > endpoints[1].route_node_key else endpoints
        provenance_by_pair[(ordered[0].route_node_key, ordered[1].route_node_key)] = ordered
    replaced_counts = tuple(
        sum(endpoint.legacy_disposition == "REPLACED" for endpoint in endpoints)
        for endpoints in provenance_by_pair.values()
    )
    replacement_summary = (
        replaced_counts.count(0),
        sum(count > 0 for count in replaced_counts),
        replaced_counts.count(2),
    )
    if replacement_summary != (1444, 339, 53):
        raise ContractError("legacy replacement endpoint exposure drift")
    return Topology(
        endpoint_pairs=endpoint_pairs,
        provenance_by_pair=provenance_by_pair,
        directed_count=len(directed),
        minimum_degree=min(degrees.values()),
        no_replacement_endpoint_count=replacement_summary[0],
        at_least_one_replaced_endpoint_count=replacement_summary[1],
        both_endpoints_replaced_count=replacement_summary[2],
    )


def build_registry(topology: Topology, existing: JsonObject) -> JsonObject:
    digest = hashlib.sha256(serialize(existing).encode()).hexdigest()
    if digest != EXPECTED_REGISTRY_SHA:
        raise ContractError("canonical corridor registry SHA drift")
    preserved = pair_key_index(existing, "entries")
    if set(preserved) != set(topology.endpoint_pairs):
        raise ContractError("corridor registry endpoint-pair drift")
    return existing


def pair_key_index(document: JsonObject, rows_key: str) -> dict[EndpointPair, str]:
    indexed: dict[EndpointPair, str] = {}
    for raw in require_array(document, rows_key):
        row = require_object(raw, "corridor identity row")
        pair_values = require_array(row, "canonicalEndpointPair")
        if len(pair_values) != 2 or any(not isinstance(value, str) for value in pair_values):
            raise ContractError("registry endpoint pair is malformed")
        pair = (str(pair_values[0]), str(pair_values[1]))
        key = require_text(row, "corridorKey")
        parsed = UUID(key)
        if pair[0] >= pair[1] or parsed.version != 4 or str(parsed) != key or pair in indexed:
            raise ContractError("corridor identity must be an independent UUIDv4")
        indexed[pair] = key
    return indexed
