#!/usr/bin/env python3
"""Materialize the reviewed Han water-topology pilot without inferring routes."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from collections import Counter, deque
from pathlib import Path
from typing import Any

from han_tiles_contract import (
    loads_json_strict,
    validate_water_overlay_base,
    validate_water_overlay_document,
    water_overlay_base_binding,
)


ROOT = Path(__file__).resolve().parents[2]
TILES = ROOT / "data" / "map" / "han-tiles.json"
ADJUDICATIONS = ROOT / "data" / "curated" / "han" / "water-topology-adjudications-v1.json"
OUTPUT = ROOT / "data" / "map" / "han-water-topology-v1.json"
MANIFEST = ROOT / "data" / "map" / "han-strategic-topology-manifest-v1.json"

SCHEMA_VERSION = 1
LEDGER_ID = "han-water-topology-adjudications-v1"
ARTIFACT_ID = "han-water-topology-v1"
MANIFEST_ID = "han-strategic-topology-manifest-v1"

ZONE_KINDS = {"RIVER_REACH", "LAKE_BASIN", "COASTAL_SEA"}
MODES = {
    "LAND", "FORD", "BRIDGE", "FERRY", "EMBARK", "DISEMBARK",
    "RIVER_UP", "RIVER_DOWN", "LAKE", "COASTAL",
}
CROSSING_MODES = {"FORD", "BRIDGE", "FERRY"}
RIVER_MODES = {"RIVER_UP", "RIVER_DOWN"}
CONFIDENCE = {"EXACT", "REVIEWED", "INFERRED"}
SEASONS = {"ALWAYS", "SEASONAL", "CLOSED"}
RISK_BANDS = {"LOW", "MEDIUM", "HIGH"}
EXPECTED_TERRAIN = {"RIVER_REACH": 3, "LAKE_BASIN": 4, "COASTAL_SEA": 0}

ROOT_KEYS = {
    "schemaVersion", "ledgerId", "topologyRevision", "base", "sourceCatalog",
    "zoneAdjudications", "barrierAdjudications", "edgeAdjudications",
    "routeCandidates", "activationBlockers",
}
SOURCE_KEYS = {"sourceId", "path", "selector", "claim", "reviewState"}
ZONE_KEYS = {
    "stableKey", "kind", "geometrySelector", "sourceRefs", "confidence",
    "flowDirection", "depthBand", "seasonalAvailability", "status",
}
BARRIER_KEYS = {
    "stableKey", "firstLandProvinceId", "secondLandProvinceId", "sourceRefs",
    "confidence", "status",
}
EDGE_KEYS = {
    "stableKey", "from", "to", "mode", "directed", "movementCost", "capacity",
    "riskBand", "seasonalAvailability", "supplyAllowed", "sourceRefs",
    "confidence", "status", "barrierStableKey", "directionPairKey",
}
ROUTE_KEYS = {
    "stableKey", "fromLandProvinceId", "toLandProvinceId", "viaZoneStableKey",
    "mode", "status", "blockerCode", "sourceRefs",
}
BLOCKER_KEYS = {"feature", "status", "code", "requiredEvidence"}
NODE_KEYS = {"kind", "id"}
COMPONENT_SELECTOR_KEYS = {
    "kind", "terrainCode", "seedRow", "seedCol", "expectedCellCount",
}
RANGE_SELECTOR_KEYS = {"kind", "terrainCode", "cellRuns", "expectedCellCount"}
CELL_RUN_KEYS = {"row", "startCol", "endCol"}

_STABLE_KEY = re.compile(r"[a-z0-9]+(?:-[a-z0-9]+)*\Z")
_PER_TILE_KEY = re.compile(r".*(?:cell|tile)-[0-9]+-[0-9]+.*\Z")


def canonical_json_bytes(value: object) -> bytes:
    return (
        json.dumps(
            value, ensure_ascii=False, sort_keys=True, separators=(",", ":"),
            allow_nan=False,
        ) + "\n"
    ).encode("utf-8")


def _sha256(document: bytes) -> str:
    return hashlib.sha256(document).hexdigest()


def _object(value: Any, where: str, keys: set[str] | None = None) -> dict:
    if not isinstance(value, dict) or not all(isinstance(key, str) for key in value):
        raise ValueError(f"{where} must be an object")
    if keys is not None and set(value) != keys:
        raise ValueError(
            f"{where} exact schema mismatch: missing={sorted(keys - set(value))}, "
            f"unknown={sorted(set(value) - keys)}"
        )
    return value


def _array(value: Any, where: str) -> list:
    if not isinstance(value, list):
        raise ValueError(f"{where} must be an array")
    return value


def _string(value: Any, where: str) -> str:
    if not isinstance(value, str) or not value.strip():
        raise ValueError(f"{where} must be a non-empty string")
    return value


def _integer(value: Any, where: str, minimum: int = 0) -> int:
    if type(value) is not int or value < minimum:
        raise ValueError(f"{where} must be an integer >= {minimum}")
    return value


def _stable_key(value: Any, where: str) -> str:
    key = _string(value, where)
    if _STABLE_KEY.fullmatch(key) is None:
        raise ValueError(f"{where} must be a lowercase stable key")
    if _PER_TILE_KEY.fullmatch(key):
        raise ValueError(f"{where} creates a forbidden per-water-tile node")
    return key


def _unique(values: list[str], where: str) -> None:
    if len(values) != len(set(values)):
        raise ValueError(f"duplicate {where}")


def _source_refs(value: Any, known_sources: set[str], where: str) -> list[str]:
    refs = _array(value, where)
    if not refs or any(not isinstance(ref, str) or not ref for ref in refs):
        raise ValueError(f"{where} must contain non-empty sourceRefs")
    if len(refs) != len(set(refs)):
        raise ValueError(f"{where} contains duplicate sourceRefs")
    unknown = sorted(set(refs) - known_sources)
    if unknown:
        raise ValueError(f"{where} contains unknown sourceRefs: {unknown}")
    return sorted(refs)


def _validate_sources(value: Any) -> tuple[list[dict], set[str]]:
    sources = []
    for index, raw in enumerate(_array(value, "sourceCatalog")):
        source = _object(raw, f"sourceCatalog[{index}]", SOURCE_KEYS)
        source_id = _string(source["sourceId"], f"sourceCatalog[{index}].sourceId")
        selector = _object(source["selector"], f"sourceCatalog[{index}].selector", {"componentKey"})
        _string(selector["componentKey"], f"sourceCatalog[{index}].selector.componentKey")
        if source["path"] != "data/curated/han/territory-disconnection-adjudications-v1.json":
            raise ValueError("water sourceCatalog must cite the tracked disconnection dossier")
        if source["reviewState"] != "UPHELD":
            raise ValueError(f"sourceCatalog evidence must be UPHELD: {source_id}")
        _string(source["claim"], f"sourceCatalog[{index}].claim")
        sources.append({
            "sourceId": source_id,
            "path": source["path"],
            "selector": {"componentKey": selector["componentKey"]},
            "claim": source["claim"],
            "reviewState": source["reviewState"],
        })
    ids = [source["sourceId"] for source in sources]
    _unique(ids, "sourceId")
    return sorted(sources, key=lambda row: row["sourceId"]), set(ids)


def _encode_cells(cells: set[tuple[int, int]]) -> list[dict[str, int]]:
    by_row: dict[int, list[int]] = {}
    for row, col in sorted(cells):
        by_row.setdefault(row, []).append(col)
    runs: list[dict[str, int]] = []
    for row in sorted(by_row):
        start = previous = by_row[row][0]
        for col in by_row[row][1:]:
            if col == previous + 1:
                previous = col
                continue
            runs.append({"row": row, "startCol": start, "endCol": previous})
            start = previous = col
        runs.append({"row": row, "startCol": start, "endCol": previous})
    return runs


def _terrain_component(
    terrain: list[str], terrain_code: int, seed_row: int, seed_col: int
) -> set[tuple[int, int]]:
    rows, cols = len(terrain), len(terrain[0])
    if not (0 <= seed_row < rows and 0 <= seed_col < cols):
        raise ValueError("geometry seed cell is outside base dimensions")
    expected = str(terrain_code)
    if terrain[seed_row][seed_col] != expected:
        raise ValueError("geometry seed terrain does not match terrainCode")
    cells = {(seed_row, seed_col)}
    pending = deque(cells)
    while pending:
        row, col = pending.popleft()
        for delta_row, delta_col in ((-1, 0), (1, 0), (0, -1), (0, 1)):
            candidate = (row + delta_row, col + delta_col)
            if (
                0 <= candidate[0] < rows and 0 <= candidate[1] < cols
                and candidate not in cells
                and terrain[candidate[0]][candidate[1]] == expected
            ):
                cells.add(candidate)
                pending.append(candidate)
    return cells


def _geometry(
    zone: dict, terrain: list[str], where: str
) -> tuple[dict[str, Any], str]:
    kind = zone["kind"]
    selector = _object(zone["geometrySelector"], f"{where}.geometrySelector")
    selector_kind = selector.get("kind")
    if kind == "COASTAL_SEA" and selector_kind != "CELL_RANGES":
        raise ValueError("coastal geometry must use reviewed CELL_RANGES; deep-sea components are forbidden")
    if selector_kind == "TERRAIN_COMPONENT":
        _object(selector, f"{where}.geometrySelector", COMPONENT_SELECTOR_KEYS)
        terrain_code = _integer(selector["terrainCode"], f"{where}.terrainCode")
        cells = _terrain_component(
            terrain,
            terrain_code,
            _integer(selector["seedRow"], f"{where}.seedRow"),
            _integer(selector["seedCol"], f"{where}.seedCol"),
        )
    elif selector_kind == "CELL_RANGES":
        _object(selector, f"{where}.geometrySelector", RANGE_SELECTOR_KEYS)
        terrain_code = _integer(selector["terrainCode"], f"{where}.terrainCode")
        cells: set[tuple[int, int]] = set()
        rows, cols = len(terrain), len(terrain[0])
        for run_index, raw_run in enumerate(_array(selector["cellRuns"], f"{where}.cellRuns")):
            run = _object(raw_run, f"{where}.cellRuns[{run_index}]", CELL_RUN_KEYS)
            row = _integer(run["row"], f"{where}.cellRuns[{run_index}].row")
            start = _integer(run["startCol"], f"{where}.cellRuns[{run_index}].startCol")
            end = _integer(run["endCol"], f"{where}.cellRuns[{run_index}].endCol")
            if row >= rows or start > end or end >= cols:
                raise ValueError(f"{where}.cellRuns[{run_index}] is outside base dimensions")
            for col in range(start, end + 1):
                if (row, col) in cells:
                    raise ValueError(f"{where}.cellRuns overlap")
                if terrain[row][col] != str(terrain_code):
                    raise ValueError(f"{where}.cellRuns terrain does not match terrainCode")
                cells.add((row, col))
    else:
        raise ValueError(f"{where}.geometrySelector kind is unsupported")
    if terrain_code != EXPECTED_TERRAIN[kind]:
        raise ValueError(f"{where} kind and terrainCode mismatch")
    expected_count = _integer(selector["expectedCellCount"], f"{where}.expectedCellCount", 1)
    if len(cells) != expected_count:
        raise ValueError(
            f"{where} geometry cell count mismatch: expected {expected_count}, got {len(cells)}"
        )
    stable_key = zone["stableKey"]
    geometry_id = f"geometry:{stable_key}"
    scope = "REVIEWED_STRAIT" if kind == "COASTAL_SEA" else (
        "INLAND_LAKE" if kind == "LAKE_BASIN" else "REVIEWED_RIVER_REACH"
    )
    return {
        "id": geometry_id,
        "kind": "CELL_RANGES",
        "terrainCode": terrain_code,
        "waterScope": scope,
        "cellRuns": _encode_cells(cells),
        "cellCount": len(cells),
    }, geometry_id


def _node(raw: Any, where: str, land_ids: set[str], zone_keys: set[str]) -> dict:
    node = _object(raw, where, NODE_KEYS)
    kind = node.get("kind")
    node_id = _string(node.get("id"), f"{where}.id")
    if kind == "LAND_PROVINCE":
        if node_id not in land_ids:
            raise ValueError(f"{where} endpoint references unknown land province: {node_id}")
        return {"kind": kind, "id": node_id}
    if kind == "WATER_ZONE":
        if node_id not in zone_keys:
            raise ValueError(f"{where} endpoint references unknown water zone: {node_id}")
        return {"kind": kind, "id": f"water-zone:{node_id}"}
    raise ValueError(f"{where} endpoint kind is invalid")


def _validate_edge_modes(edge: dict, zones_by_key: dict[str, dict], barriers_by_key: dict[str, dict]) -> None:
    mode = edge["mode"]
    from_kind, to_kind = edge["from"]["kind"], edge["to"]["kind"]
    valid = {
        "LAND": ("LAND_PROVINCE", "LAND_PROVINCE"),
        "FORD": ("LAND_PROVINCE", "LAND_PROVINCE"),
        "BRIDGE": ("LAND_PROVINCE", "LAND_PROVINCE"),
        "FERRY": ("LAND_PROVINCE", "LAND_PROVINCE"),
        "EMBARK": ("LAND_PROVINCE", "WATER_ZONE"),
        "DISEMBARK": ("WATER_ZONE", "LAND_PROVINCE"),
        "RIVER_UP": ("WATER_ZONE", "WATER_ZONE"),
        "RIVER_DOWN": ("WATER_ZONE", "WATER_ZONE"),
        "LAKE": ("WATER_ZONE", "WATER_ZONE"),
        "COASTAL": ("WATER_ZONE", "WATER_ZONE"),
    }[mode]
    if (from_kind, to_kind) != valid:
        raise ValueError(f"edge {edge['stableKey']} endpoint kinds are incompatible with mode {mode}")
    directed = edge["directed"]
    if type(directed) is not bool or directed != (mode in RIVER_MODES):
        raise ValueError(f"edge {edge['stableKey']} directed flag is incompatible with mode {mode}")
    if mode in CROSSING_MODES:
        barrier_key = edge["barrierStableKey"]
        if barrier_key not in barriers_by_key:
            raise ValueError(f"crossing {edge['stableKey']} requires a reviewed river barrier")
        barrier = barriers_by_key[barrier_key]
        if {edge["from"]["id"], edge["to"]["id"]} != {
            barrier["firstLandProvinceId"], barrier["secondLandProvinceId"]
        }:
            raise ValueError(f"crossing {edge['stableKey']} endpoints do not match its river barrier")
    elif edge["barrierStableKey"] is not None:
        raise ValueError(f"edge {edge['stableKey']} may not cite a river barrier")
    if mode in RIVER_MODES:
        if not isinstance(edge["directionPairKey"], str) or not edge["directionPairKey"]:
            raise ValueError(f"river edge {edge['stableKey']} requires directionPairKey")
    elif edge["directionPairKey"] is not None:
        raise ValueError(f"non-river edge {edge['stableKey']} may not have directionPairKey")
    expected_zone_kind = {
        "RIVER_UP": "RIVER_REACH", "RIVER_DOWN": "RIVER_REACH",
        "LAKE": "LAKE_BASIN", "COASTAL": "COASTAL_SEA",
    }.get(mode)
    if expected_zone_kind:
        for endpoint in (edge["from"], edge["to"]):
            if zones_by_key[endpoint["id"]]["kind"] != expected_zone_kind:
                raise ValueError(f"edge {edge['stableKey']} water endpoint kind does not match mode {mode}")


def _validate_flow_pairs(edges: list[dict]) -> None:
    pairs: dict[str, list[dict]] = {}
    for edge in edges:
        if edge["mode"] in RIVER_MODES:
            pairs.setdefault(edge["directionPairKey"], []).append(edge)
    for pair_key, pair in pairs.items():
        if len(pair) != 2 or {edge["mode"] for edge in pair} != RIVER_MODES:
            raise ValueError(f"directed-flow pair {pair_key} must contain one RIVER_UP and one RIVER_DOWN")
        upstream = next(edge for edge in pair if edge["mode"] == "RIVER_UP")
        downstream = next(edge for edge in pair if edge["mode"] == "RIVER_DOWN")
        if upstream["from"] != downstream["to"] or upstream["to"] != downstream["from"]:
            raise ValueError(f"directed-flow pair {pair_key} must use reversed endpoints")


def build_water_topology(
    tiles: dict, tiles_bytes: bytes, adjudications: dict
) -> dict:
    ledger = _object(adjudications, "adjudications", ROOT_KEYS)
    if type(ledger["schemaVersion"]) is not int or ledger["schemaVersion"] != SCHEMA_VERSION:
        raise ValueError("adjudications schemaVersion must be 1")
    if ledger["ledgerId"] != LEDGER_ID:
        raise ValueError(f"adjudications ledgerId must be {LEDGER_ID}")
    topology_revision = _string(ledger["topologyRevision"], "topologyRevision")
    validate_water_overlay_base(tiles, tiles_bytes, ledger["base"])
    land_ids = list(ledger["base"]["landProvinceIds"])
    land_id_set = set(land_ids)
    sources, source_ids = _validate_sources(ledger["sourceCatalog"])
    del sources

    terrain = tiles["terrain"]
    zone_rows: list[dict] = []
    geometry_rows: list[dict] = []
    zones_by_key: dict[str, dict] = {}
    for index, raw in enumerate(_array(ledger["zoneAdjudications"], "zoneAdjudications")):
        zone_row = _object(raw, f"zoneAdjudications[{index}]", ZONE_KEYS)
        stable_key = _stable_key(zone_row["stableKey"], f"zoneAdjudications[{index}].stableKey")
        if stable_key in zones_by_key:
            raise ValueError(f"duplicate water zone stableKey: {stable_key}")
        if zone_row["kind"] not in ZONE_KINDS:
            raise ValueError(f"water zone {stable_key} has invalid kind")
        if zone_row["status"] != "APPROVED":
            raise ValueError(f"water zone {stable_key} is not approved")
        if zone_row["confidence"] not in {"EXACT", "REVIEWED"}:
            raise ValueError(f"water zone {stable_key} must be evidence-reviewed")
        if zone_row["seasonalAvailability"] not in SEASONS:
            raise ValueError(f"water zone {stable_key} has invalid seasonalAvailability")
        refs = _source_refs(zone_row["sourceRefs"], source_ids, f"zoneAdjudications[{index}].sourceRefs")
        geometry, geometry_id = _geometry({**zone_row, "stableKey": stable_key}, terrain, f"zoneAdjudications[{index}]")
        zone = {
            "id": f"water-zone:{stable_key}",
            "kind": zone_row["kind"],
            "geometryRef": geometry_id,
            "sourceRefs": refs,
            "confidence": zone_row["confidence"],
            "flowDirection": zone_row["flowDirection"],
            "depthBand": zone_row["depthBand"],
            "seasonalAvailability": zone_row["seasonalAvailability"],
        }
        if zone["flowDirection"] is not None and not isinstance(zone["flowDirection"], str):
            raise ValueError(f"water zone {stable_key} flowDirection must be string or null")
        if zone["depthBand"] not in {None, "SHALLOW", "MEDIUM", "DEEP"}:
            raise ValueError(f"water zone {stable_key} has invalid depthBand")
        zones_by_key[stable_key] = zone_row
        zone_rows.append(zone)
        geometry_rows.append(geometry)

    barrier_rows: list[dict] = []
    barriers_by_key: dict[str, dict] = {}
    boundary_keys: set[tuple[str, str]] = set()
    for index, raw in enumerate(_array(ledger["barrierAdjudications"], "barrierAdjudications")):
        row = _object(raw, f"barrierAdjudications[{index}]", BARRIER_KEYS)
        stable_key = _stable_key(row["stableKey"], f"barrierAdjudications[{index}].stableKey")
        if stable_key in barriers_by_key:
            raise ValueError(f"duplicate river barrier stableKey: {stable_key}")
        first = _string(row["firstLandProvinceId"], f"barrierAdjudications[{index}].firstLandProvinceId")
        second = _string(row["secondLandProvinceId"], f"barrierAdjudications[{index}].secondLandProvinceId")
        if first not in land_id_set or second not in land_id_set or first == second:
            raise ValueError(f"river barrier {stable_key} has invalid land endpoint")
        boundary = tuple(sorted((first, second)))
        if boundary in boundary_keys:
            raise ValueError(f"duplicate river barrier boundary: {boundary}")
        boundary_keys.add(boundary)
        if row["status"] != "APPROVED" or row["confidence"] not in {"EXACT", "REVIEWED"}:
            raise ValueError(f"river barrier {stable_key} must be approved and evidence-reviewed")
        refs = _source_refs(row["sourceRefs"], source_ids, f"barrierAdjudications[{index}].sourceRefs")
        barriers_by_key[stable_key] = row
        barrier_rows.append({
            "id": f"river-barrier:{stable_key}",
            "firstLandProvinceId": first,
            "secondLandProvinceId": second,
            "sourceRefs": refs,
            "confidence": row["confidence"],
        })

    validated_edges: list[dict] = []
    edge_rows: list[dict] = []
    for index, raw in enumerate(_array(ledger["edgeAdjudications"], "edgeAdjudications")):
        row = _object(raw, f"edgeAdjudications[{index}]", EDGE_KEYS)
        stable_key = _stable_key(row["stableKey"], f"edgeAdjudications[{index}].stableKey")
        mode = row["mode"]
        if mode not in MODES:
            raise ValueError(f"edge {stable_key} has invalid mode")
        if row["status"] != "APPROVED":
            label = "unapproved crossing" if mode in CROSSING_MODES else "unapproved traversal edge"
            raise ValueError(f"{label}: {stable_key}")
        if row["confidence"] not in CONFIDENCE or (mode != "LAND" and row["confidence"] == "INFERRED"):
            raise ValueError(f"edge {stable_key} must be evidence-reviewed")
        edge = dict(row)
        edge["stableKey"] = stable_key
        edge["from"] = _node(row["from"], f"edge {stable_key}.from", land_id_set, set(zones_by_key))
        edge["to"] = _node(row["to"], f"edge {stable_key}.to", land_id_set, set(zones_by_key))
        if edge["from"] == edge["to"]:
            raise ValueError(f"edge {stable_key} cannot be a self edge")
        _integer(row["movementCost"], f"edge {stable_key}.movementCost", 1)
        _integer(row["capacity"], f"edge {stable_key}.capacity", 1)
        if row["riskBand"] not in RISK_BANDS or row["seasonalAvailability"] not in SEASONS:
            raise ValueError(f"edge {stable_key} has invalid risk or seasonal value")
        if type(row["supplyAllowed"]) is not bool:
            raise ValueError(f"edge {stable_key}.supplyAllowed must be boolean")
        refs = _source_refs(row["sourceRefs"], source_ids, f"edge {stable_key}.sourceRefs")
        # Mode validation uses adjudication stable keys, before IDs are materialized.
        mode_edge = {**row, "stableKey": stable_key}
        _validate_edge_modes(mode_edge, zones_by_key, barriers_by_key)
        validated_edges.append(mode_edge)
        edge_rows.append({
            "id": f"traversal-edge:{stable_key}",
            "from": edge["from"],
            "to": edge["to"],
            "mode": mode,
            "directed": row["directed"],
            "movementCost": row["movementCost"],
            "capacity": row["capacity"],
            "riskBand": row["riskBand"],
            "seasonalAvailability": row["seasonalAvailability"],
            "supplyAllowed": row["supplyAllowed"],
            "sourceRefs": refs,
            "confidence": row["confidence"],
            "barrierId": None if row["barrierStableKey"] is None else f"river-barrier:{row['barrierStableKey']}",
            "directionPairKey": row["directionPairKey"],
        })
    _unique([row["stableKey"] for row in validated_edges], "traversal edge stableKey")
    _validate_flow_pairs(validated_edges)

    route_rows: list[dict] = []
    for index, raw in enumerate(_array(ledger["routeCandidates"], "routeCandidates")):
        row = _object(raw, f"routeCandidates[{index}]", ROUTE_KEYS)
        stable_key = _stable_key(row["stableKey"], f"routeCandidates[{index}].stableKey")
        if row["status"] != "BLOCKED_PENDING_REVIEW":
            raise ValueError(f"route candidate {stable_key} must remain blocked pending review")
        if row["fromLandProvinceId"] not in land_id_set or row["toLandProvinceId"] not in land_id_set:
            raise ValueError(f"route candidate {stable_key} has an unknown land endpoint")
        if row["viaZoneStableKey"] not in zones_by_key:
            raise ValueError(f"route candidate {stable_key} has an unknown water endpoint")
        if row["mode"] not in {"COASTAL", "LAKE", "RIVER_UP", "RIVER_DOWN"}:
            raise ValueError(f"route candidate {stable_key} has invalid mode")
        refs = _source_refs(row["sourceRefs"], source_ids, f"routeCandidates[{index}].sourceRefs")
        route_rows.append({
            "id": f"route-candidate:{stable_key}",
            "fromLandProvinceId": row["fromLandProvinceId"],
            "toLandProvinceId": row["toLandProvinceId"],
            "viaWaterZoneId": f"water-zone:{row['viaZoneStableKey']}",
            "mode": row["mode"],
            "status": row["status"],
            "blockerCode": _string(row["blockerCode"], f"route candidate {stable_key}.blockerCode"),
            "sourceRefs": refs,
        })

    blocker_rows: list[dict] = []
    for index, raw in enumerate(_array(ledger["activationBlockers"], "activationBlockers")):
        row = _object(raw, f"activationBlockers[{index}]", BLOCKER_KEYS)
        if row["status"] != "BLOCKED":
            raise ValueError("activation blocker status must be BLOCKED")
        required = _array(row["requiredEvidence"], f"activationBlockers[{index}].requiredEvidence")
        if not required or any(not isinstance(item, str) or not item for item in required):
            raise ValueError("activation blocker requires an evidence checklist")
        blocker_rows.append({
            "feature": _string(row["feature"], f"activationBlockers[{index}].feature"),
            "status": "BLOCKED",
            "code": _string(row["code"], f"activationBlockers[{index}].code"),
            "requiredEvidence": sorted(required),
        })

    artifact = {
        "schemaVersion": SCHEMA_VERSION,
        "artifactId": ARTIFACT_ID,
        "topologyRevision": topology_revision,
        "base": ledger["base"],
        "landProvinceIds": land_ids,
        "geometryComponents": sorted(geometry_rows, key=lambda row: row["id"]),
        "waterZones": sorted(zone_rows, key=lambda row: row["id"]),
        "riverBarriers": sorted(barrier_rows, key=lambda row: row["id"]),
        "traversalEdges": sorted(edge_rows, key=lambda row: row["id"]),
        "routeCandidates": sorted(route_rows, key=lambda row: row["id"]),
        "activationBlockers": sorted(blocker_rows, key=lambda row: (row["feature"], row["code"])),
    }
    _unique([row["id"] for row in artifact["waterZones"]], "water zone ID")
    _unique([row["id"] for row in artifact["geometryComponents"]], "geometry ID")
    _unique([row["id"] for row in artifact["riverBarriers"]], "river barrier ID")
    _unique([row["id"] for row in artifact["traversalEdges"]], "traversal edge ID")
    validate_water_overlay_document(tiles, tiles_bytes, artifact)
    return artifact


def render_artifact(tiles: dict, tiles_bytes: bytes, adjudications: dict) -> bytes:
    return canonical_json_bytes(build_water_topology(tiles, tiles_bytes, adjudications))


def load_inputs() -> tuple[dict, bytes, dict, bytes]:
    tiles_bytes = TILES.read_bytes()
    adjudication_bytes = ADJUDICATIONS.read_bytes()
    return (
        loads_json_strict(tiles_bytes),
        tiles_bytes,
        loads_json_strict(adjudication_bytes),
        adjudication_bytes,
    )


def _file_record(path: Path, document: bytes) -> dict:
    return {
        "path": path.relative_to(ROOT).as_posix(),
        "sha256": _sha256(document),
        "bytes": len(document),
    }


def build_manifest(
    tiles_bytes: bytes,
    adjudication_bytes: bytes,
    artifact_bytes: bytes,
    artifact: dict,
) -> dict:
    zone_kinds = Counter(row["kind"] for row in artifact["waterZones"])
    edge_modes = Counter(row["mode"] for row in artifact["traversalEdges"])
    evidence_refs = {
        ref
        for collection in ("waterZones", "riverBarriers", "traversalEdges", "routeCandidates")
        for row in artifact[collection]
        for ref in row["sourceRefs"]
    }
    return {
        "schemaVersion": SCHEMA_VERSION,
        "manifestId": MANIFEST_ID,
        "topologyRevision": artifact["topologyRevision"],
        "files": {
            "baseHanTiles": _file_record(TILES, tiles_bytes),
            "adjudications": _file_record(ADJUDICATIONS, adjudication_bytes),
            "waterTopology": _file_record(OUTPUT, artifact_bytes),
        },
        "counts": {
            "landProvinceIds": len(artifact["landProvinceIds"]),
            "geometryComponents": len(artifact["geometryComponents"]),
            "waterZones": len(artifact["waterZones"]),
            "riverBarriers": len(artifact["riverBarriers"]),
            "traversalEdges": len(artifact["traversalEdges"]),
            "routeCandidates": len(artifact["routeCandidates"]),
            "activationBlockers": len(artifact["activationBlockers"]),
            "evidenceSources": len(evidence_refs),
        },
        "zoneKinds": dict(sorted(zone_kinds.items())),
        "edgeModes": dict(sorted(edge_modes.items())),
    }


def render_outputs() -> tuple[bytes, bytes]:
    tiles, tiles_bytes, adjudications, adjudication_bytes = load_inputs()
    artifact_bytes = render_artifact(tiles, tiles_bytes, adjudications)
    artifact = loads_json_strict(artifact_bytes)
    manifest = build_manifest(tiles_bytes, adjudication_bytes, artifact_bytes, artifact)
    return artifact_bytes, canonical_json_bytes(manifest)


def write_outputs() -> tuple[bytes, bytes]:
    artifact_bytes, manifest_bytes = render_outputs()
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT.write_bytes(artifact_bytes)
    MANIFEST.write_bytes(manifest_bytes)
    return artifact_bytes, manifest_bytes


def check_outputs() -> bool:
    artifact_bytes, manifest_bytes = render_outputs()
    return (
        OUTPUT.is_file() and MANIFEST.is_file()
        and OUTPUT.read_bytes() == artifact_bytes
        and MANIFEST.read_bytes() == manifest_bytes
    )


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--write", action="store_true", help="write deterministic water topology outputs")
    mode.add_argument("--check", action="store_true", help="require byte-identical committed outputs")
    args = parser.parse_args(argv)
    if args.check:
        valid = check_outputs()
        print(f"Han water topology check {'passed' if valid else 'failed'}")
        return 0 if valid else 1
    artifact_bytes, manifest_bytes = write_outputs()
    print(
        f"generated {OUTPUT.relative_to(ROOT)} ({len(artifact_bytes)} bytes) and "
        f"{MANIFEST.relative_to(ROOT)} ({len(manifest_bytes)} bytes)"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
