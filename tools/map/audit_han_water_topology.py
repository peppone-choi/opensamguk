#!/usr/bin/env python3
"""Audit the committed, evidence-reviewed Han water-topology pilot."""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path
from typing import Any

import build_han_water_topology as builder
from han_tiles_contract import loads_json_strict, validate_water_overlay_document


ROOT = Path(__file__).resolve().parents[2]
DOSSIER = ROOT / "data" / "curated" / "han" / "territory-disconnection-adjudications-v1.json"

ARTIFACT_KEYS = {
    "schemaVersion", "artifactId", "topologyRevision", "base",
    "landProvinceIds", "geometryComponents", "waterZones", "riverBarriers",
    "traversalEdges", "routeCandidates", "activationBlockers",
}
GEOMETRY_KEYS = {"id", "kind", "terrainCode", "waterScope", "cellRuns", "cellCount"}
ZONE_KEYS = {
    "id", "kind", "geometryRef", "sourceRefs", "confidence", "flowDirection",
    "depthBand", "seasonalAvailability", "connectionStatus",
}
BARRIER_KEYS = {
    "id", "firstLandProvinceId", "secondLandProvinceId", "sourceRefs", "confidence",
}
EDGE_KEYS = {
    "id", "from", "to", "mode", "directed", "movementCost", "capacity",
    "riskBand", "seasonalAvailability", "supplyAllowed", "sourceRefs", "confidence",
    "barrierId", "directionPairKey",
}
ROUTE_KEYS = {
    "id", "fromLandProvinceId", "toLandProvinceId", "viaWaterZoneId", "mode",
    "status", "blockerCode", "sourceRefs",
}
BLOCKER_KEYS = {"feature", "status", "code", "requiredEvidence"}
CELL_RUN_KEYS = {"row", "startCol", "endCol"}
ALLOWED_WATER_SCOPES = {"INLAND_LAKE", "REVIEWED_STRAIT", "REVIEWED_RIVER_REACH"}
ZONE_SOURCE_VERDICTS = {
    "LAKE_BASIN": {"GEOMETRY_DEFECT", "WATER_SEPARATED"},
    "COASTAL_SEA": {"WATER_SEPARATED"},
    "RIVER_REACH": {"WATER_SEPARATED"},
}
_PER_TILE_ID = re.compile(r".*(?:cell|tile)-[0-9]+-[0-9]+.*\Z")


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


def _unique(values: list[str], where: str) -> None:
    if len(values) != len(set(values)):
        raise ValueError(f"duplicate {where}")


def _cells_are_connected(cells: set[tuple[int, int]]) -> bool:
    pending = [next(iter(cells))]
    seen = {pending[0]}
    while pending:
        row, col = pending.pop()
        for delta_row, delta_col in ((-1, 0), (1, 0), (0, -1), (0, 1)):
            neighbor = (row + delta_row, col + delta_col)
            if neighbor in cells and neighbor not in seen:
                seen.add(neighbor)
                pending.append(neighbor)
    return seen == cells


def _validate_source_catalog(
    adjudications: dict,
) -> tuple[dict[str, set[str]], dict[str, set[str]], dict[str, str], dict[str, str]]:
    dossier_bytes = DOSSIER.read_bytes()
    dossier = _object(loads_json_strict(dossier_bytes), "territory disconnection dossier")
    rows = _array(dossier.get("adjudications"), "territory disconnection adjudications")
    by_component: dict[str, list[dict]] = {}
    for row in rows:
        if isinstance(row, dict) and isinstance(row.get("componentKey"), str):
            by_component.setdefault(row["componentKey"], []).append(row)

    source_ids: list[str] = []
    source_verdicts: dict[str, str] = {}
    for index, raw_source in enumerate(_array(adjudications.get("sourceCatalog"), "sourceCatalog")):
        source = _object(raw_source, f"sourceCatalog[{index}]", builder.SOURCE_KEYS)
        source_id = source.get("sourceId")
        selector = _object(source.get("selector"), f"sourceCatalog[{index}].selector", {"componentKey"})
        component_key = selector.get("componentKey")
        if not isinstance(source_id, str) or source_id != f"territory-disconnection:{component_key}":
            raise ValueError(f"sourceCatalog[{index}] sourceId does not match its evidence selector")
        if source.get("path") != DOSSIER.relative_to(ROOT).as_posix():
            raise ValueError(f"sourceCatalog[{index}] does not cite the tracked dossier")
        matches = by_component.get(component_key, [])
        if len(matches) != 1:
            raise ValueError(
                f"sourceCatalog[{index}] selector must resolve one existing evidence row: {component_key}"
            )
        review = matches[0].get("review")
        if not isinstance(review, dict) or review.get("state") != "UPHELD":
            raise ValueError(f"sourceCatalog[{index}] evidence is not review-UPHELD")
        if source.get("reviewState") != review["state"]:
            raise ValueError(f"sourceCatalog[{index}] reviewState disagrees with its evidence row")
        verdict = matches[0].get("verdict")
        if not isinstance(verdict, str) or not verdict:
            raise ValueError(f"sourceCatalog[{index}] evidence row lacks a verdict")
        if source.get("unitId") != matches[0].get("unitId"):
            raise ValueError(f"sourceCatalog[{index}] unitId disagrees with its evidence row")
        member_ids = source.get("memberIds")
        if (
            not isinstance(member_ids, list)
            or member_ids != sorted(matches[0].get("memberIds", []))
        ):
            raise ValueError(f"sourceCatalog[{index}] memberIds disagree with its evidence row")
        source_ids.append(source_id)
        source_verdicts[source_id] = verdict
    _unique(source_ids, "sourceCatalog sourceId")
    return (
        {
            source["sourceId"]: {source["unitId"], *source["memberIds"]}
            for source in adjudications["sourceCatalog"]
        },
        {
            source["sourceId"]: set(source["memberIds"])
            for source in adjudications["sourceCatalog"]
        },
        {
            source["sourceId"]: builder.SOURCE_TYPE_BY_PATH[source["path"]]
            for source in adjudications["sourceCatalog"]
        },
        source_verdicts,
    )


def _validate_source_refs(rows: list, known_sources: dict[str, set[str]], collection: str) -> None:
    for index, row in enumerate(rows):
        refs = row.get("sourceRefs")
        if not isinstance(refs, list) or not refs or any(not isinstance(ref, str) for ref in refs):
            raise ValueError(f"{collection}[{index}].sourceRefs must be non-empty")
        if len(refs) != len(set(refs)) or set(refs) - set(known_sources):
            raise ValueError(f"{collection}[{index}].sourceRefs are duplicate or unknown")


def _validate_land_source_coverage(
    land_endpoints: set[str], refs: list[str], source_coverage: dict[str, set[str]], where: str
) -> None:
    if not isinstance(refs, list) or not refs or set(refs) - set(source_coverage):
        raise ValueError(f"{where}.sourceRefs are empty or unknown")
    covered = set().union(*(source_coverage[ref] for ref in refs))
    missing = sorted(land_endpoints - covered)
    if missing:
        raise ValueError(f"{where} land endpoints lack exact source coverage: {missing}")


def _land_touches_cells(
    land_id: str,
    cells: set[tuple[int, int]],
    owner: list[list[int]],
    province_index_by_id: dict[str, int],
) -> bool:
    expected_owner = province_index_by_id[land_id]
    rows, cols = len(owner), len(owner[0])
    return any(
        0 <= row + delta_row < rows
        and 0 <= col + delta_col < cols
        and owner[row + delta_row][col + delta_col] == expected_owner
        for row, col in cells
        for delta_row, delta_col in ((-1, 0), (1, 0), (0, -1), (0, 1))
    )


def _owner_identity_sets(province_records: list[dict]) -> list[set[str]]:
    return [
        {
            identity
            for identity in (record.get("id"), record.get("jurisdictionId"))
            if isinstance(identity, str) and identity
        }
        for record in province_records
    ]


def _cells_touch_cited_members(
    cells: set[tuple[int, int]],
    refs: list[str],
    source_members: dict[str, set[str]],
    owner: list[list[int]],
    owner_identities: list[set[str]],
) -> bool:
    rows, cols = len(owner), len(owner[0])
    touched: set[str] = set()
    for row, col in cells:
        for delta_row, delta_col in ((-1, 0), (1, 0), (0, -1), (0, 1)):
            adjacent_row, adjacent_col = row + delta_row, col + delta_col
            if not (0 <= adjacent_row < rows and 0 <= adjacent_col < cols):
                continue
            owner_index = owner[adjacent_row][adjacent_col]
            if 0 <= owner_index < len(owner_identities):
                touched.update(owner_identities[owner_index])
    return all(touched & source_members[ref] for ref in refs)


def _land_owners_are_adjacent(
    first_land_id: str,
    second_land_id: str,
    owner: list[list[int]],
    province_index_by_id: dict[str, int],
) -> bool:
    first_owner = province_index_by_id[first_land_id]
    second_owner = province_index_by_id[second_land_id]
    rows, cols = len(owner), len(owner[0])
    return any(
        owner[row][col] == first_owner
        and (
            (row + 1 < rows and owner[row + 1][col] == second_owner)
            or (col + 1 < cols and owner[row][col + 1] == second_owner)
            or (row > 0 and owner[row - 1][col] == second_owner)
            or (col > 0 and owner[row][col - 1] == second_owner)
        )
        for row in range(rows)
        for col in range(cols)
    )


def _require_source_type(
    refs: list[str], source_types: dict[str, str], expected: str, where: str
) -> None:
    actual = {source_types[ref] for ref in refs}
    if actual != {expected}:
        raise ValueError(f"{where} requires exact {expected} source type, got {sorted(actual)}")


def validate_artifact(
    tiles: dict, tiles_bytes: bytes, adjudications: dict, artifact: dict
) -> None:
    """Validate the materialized document independently of byte regeneration."""
    artifact = _object(artifact, "artifact", ARTIFACT_KEYS)
    validate_water_overlay_document(tiles, tiles_bytes, artifact)
    if artifact.get("schemaVersion") != 1 or artifact.get("artifactId") != builder.ARTIFACT_ID:
        raise ValueError("artifact identity or schemaVersion is invalid")
    if artifact.get("topologyRevision") != adjudications.get("topologyRevision"):
        raise ValueError("artifact topologyRevision disagrees with adjudications")
    if artifact.get("base") != adjudications.get("base"):
        raise ValueError("artifact base binding disagrees with adjudications")
    if artifact.get("landProvinceIds") != adjudications.get("base", {}).get("landProvinceIds"):
        raise ValueError("artifact landProvinceIds disagree with pinned land IDs")

    source_coverage, source_members, source_types, source_verdicts = _validate_source_catalog(
        adjudications
    )
    known_sources = source_coverage
    terrain = tiles["terrain"]
    row_count, col_count = len(terrain), len(terrain[0])
    owner = builder._decode_owner(tiles)
    owner_identities = _owner_identity_sets(tiles["provinceRecords"])
    province_index_by_id = {
        province["id"]: index for index, province in enumerate(tiles["provinceRecords"])
    }
    land_id_set = set(artifact["landProvinceIds"])

    geometries = _array(artifact["geometryComponents"], "geometryComponents")
    geometry_ids: list[str] = []
    geometry_cells_by_id: dict[str, set[tuple[int, int]]] = {}
    for index, raw in enumerate(geometries):
        geometry = _object(raw, f"geometryComponents[{index}]", GEOMETRY_KEYS)
        geometry_id = geometry.get("id")
        if not isinstance(geometry_id, str) or _PER_TILE_ID.fullmatch(geometry_id):
            raise ValueError(f"geometryComponents[{index}].id creates a forbidden per-water-tile node")
        if geometry.get("kind") != "CELL_RANGES":
            raise ValueError(f"geometryComponents[{index}] must use CELL_RANGES")
        if geometry.get("waterScope") not in ALLOWED_WATER_SCOPES:
            raise ValueError(f"geometryComponents[{index}].waterScope creates a forbidden deep-sea shortcut")
        terrain_code = geometry.get("terrainCode")
        cell_count = geometry.get("cellCount")
        if type(terrain_code) is not int or type(cell_count) is not int or cell_count < 2:
            if cell_count == 1:
                raise ValueError(
                    f"geometryComponents[{index}] minimum component is two cells; "
                    "per-water-tile nodes are forbidden"
                )
            raise ValueError(f"geometryComponents[{index}] has invalid terrainCode or cellCount")
        cells: set[tuple[int, int]] = set()
        for run_index, raw_run in enumerate(_array(geometry.get("cellRuns"), f"geometryComponents[{index}].cellRuns")):
            run = _object(raw_run, f"geometryComponents[{index}].cellRuns[{run_index}]", CELL_RUN_KEYS)
            row, start, end = run.get("row"), run.get("startCol"), run.get("endCol")
            if any(type(value) is not int for value in (row, start, end)):
                raise ValueError("geometry cell run coordinates must be integers")
            if not (0 <= row < row_count and 0 <= start <= end < col_count):
                raise ValueError("geometry cell run is outside base dimensions")
            for col in range(start, end + 1):
                if (row, col) in cells:
                    raise ValueError("geometry cell runs overlap")
                if terrain[row][col] != str(terrain_code):
                    raise ValueError("geometry cell run terrain disagrees with han-tiles")
                cells.add((row, col))
        if len(cells) != cell_count:
            raise ValueError(f"geometryComponents[{index}] cellCount mismatch")
        if not _cells_are_connected(cells):
            raise ValueError(f"geometryComponents[{index}] must form one connected waterbody")
        geometry_ids.append(geometry_id)
        geometry_cells_by_id[geometry_id] = cells
    _unique(geometry_ids, "geometry ID")
    geometry_id_set = set(geometry_ids)

    zones = _array(artifact["waterZones"], "waterZones")
    zone_ids: list[str] = []
    for index, raw in enumerate(zones):
        zone = _object(raw, f"waterZones[{index}]", ZONE_KEYS)
        zone_id = zone.get("id")
        if not isinstance(zone_id, str) or _PER_TILE_ID.fullmatch(zone_id):
            raise ValueError(f"waterZones[{index}].id creates a forbidden per-water-tile node")
        if zone.get("geometryRef") not in geometry_id_set:
            raise ValueError(f"waterZones[{index}] has a dangling geometryRef")
        if zone.get("connectionStatus") not in {
            "CONNECTED", "ISOLATED_NO_REVIEWED_CONNECTION",
        }:
            raise ValueError(f"waterZones[{index}] lacks an explicit isolation adjudication")
        if (
            zone.get("kind") == "COASTAL_SEA"
            and not builder._cells_touch_any_land(
                geometry_cells_by_id[zone["geometryRef"]], owner
            )
        ):
            raise ValueError(f"coastal waterZones[{index}] must touch a decoded owner boundary")
        refs = zone.get("sourceRefs")
        if not isinstance(refs, list) or not refs or set(refs) - set(source_coverage):
            raise ValueError(f"waterZones[{index}].sourceRefs are empty or unknown")
        allowed_verdicts = ZONE_SOURCE_VERDICTS.get(zone.get("kind"))
        if allowed_verdicts is None or any(
            source_verdicts[ref] not in allowed_verdicts for ref in refs
        ):
            raise ValueError(f"waterZones[{index}] source verdict does not match its zone type")
        if zone.get("kind") in {"COASTAL_SEA", "LAKE_BASIN"} and not _cells_touch_cited_members(
            geometry_cells_by_id[zone["geometryRef"]], refs, source_members,
            owner, owner_identities,
        ):
            raise ValueError(
                f"waterZones[{index}] geometry must touch every cited source member boundary"
            )
        zone_ids.append(zone_id)
    _unique(zone_ids, "water zone ID")
    zone_id_set = set(zone_ids)
    _validate_source_refs(zones, known_sources, "waterZones")

    barriers = _array(artifact["riverBarriers"], "riverBarriers")
    edges = _array(artifact["traversalEdges"], "traversalEdges")
    routes = _array(artifact["routeCandidates"], "routeCandidates")
    blockers = _array(artifact["activationBlockers"], "activationBlockers")
    blocker_codes: set[str] = set()
    for index, row in enumerate(blockers):
        blocker = _object(row, f"activationBlockers[{index}]", BLOCKER_KEYS)
        if blocker.get("status") != "BLOCKED":
            raise ValueError(f"activationBlockers[{index}] status must be BLOCKED")
        code = blocker.get("code")
        if not isinstance(code, str) or not code:
            raise ValueError(f"activationBlockers[{index}] code must be non-empty")
        blocker_codes.add(code)
    if "NO_REVIEWED_RIVER_CROSSING_EVIDENCE" in blocker_codes and (
        barriers or any(
            isinstance(edge, dict) and edge.get("mode") in builder.CROSSING_MODES
            for edge in edges
        )
    ):
        raise ValueError(
            "river activation blocker forbids executable river barriers and crossings"
        )
    barrier_by_id: dict[str, dict] = {}
    for index, row in enumerate(barriers):
        barrier = _object(row, f"riverBarriers[{index}]", BARRIER_KEYS)
        barrier_id = barrier.get("id")
        if not isinstance(barrier_id, str) or barrier_id in barrier_by_id:
            raise ValueError(f"riverBarriers[{index}] has a missing or duplicate ID")
        endpoints = {barrier.get("firstLandProvinceId"), barrier.get("secondLandProvinceId")}
        if None in endpoints or len(endpoints) != 2 or not endpoints <= land_id_set:
            raise ValueError(f"riverBarriers[{index}] has an unknown land endpoint")
        _require_source_type(
            barrier["sourceRefs"], source_types, "RIVER_BARRIER", f"riverBarriers[{index}]"
        )
        _validate_land_source_coverage(
            endpoints, barrier["sourceRefs"], source_coverage, f"riverBarriers[{index}]"
        )
        first, second = barrier["firstLandProvinceId"], barrier["secondLandProvinceId"]
        if not _land_owners_are_adjacent(first, second, owner, province_index_by_id):
            raise ValueError(f"riverBarriers[{index}] endpoints are not decoded owner-grid adjacent")
        barrier_by_id[barrier_id] = barrier
    incident_zone_ids: set[str] = set()
    for index, row in enumerate(edges):
        edge = _object(row, f"traversalEdges[{index}]", EDGE_KEYS)
        endpoints = []
        for endpoint_name in ("from", "to"):
            endpoint = _object(
                edge.get(endpoint_name), f"traversalEdges[{index}].{endpoint_name}", {"kind", "id"}
            )
            if endpoint["kind"] == "LAND_PROVINCE":
                if endpoint["id"] not in land_id_set:
                    raise ValueError(f"traversalEdges[{index}] has an unknown land endpoint")
            elif endpoint["kind"] == "WATER_ZONE":
                if endpoint["id"] not in zone_id_set:
                    raise ValueError(f"traversalEdges[{index}] has an unknown water endpoint")
                incident_zone_ids.add(endpoint["id"])
            else:
                raise ValueError(f"traversalEdges[{index}] has an invalid endpoint kind")
            endpoints.append(endpoint)
        land_endpoints = {
            endpoint["id"] for endpoint in endpoints if endpoint["kind"] == "LAND_PROVINCE"
        }
        mode = edge.get("mode")
        if mode not in builder.MODES:
            raise ValueError(f"traversalEdges[{index}] has an invalid mode")
        expected_kinds = {
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
        if tuple(endpoint["kind"] for endpoint in endpoints) != expected_kinds:
            raise ValueError(f"traversalEdges[{index}] endpoint kinds do not match mode {mode}")
        if mode in builder.CROSSING_MODES:
            _require_source_type(
                edge["sourceRefs"], source_types, "RIVER_CROSSING", f"traversalEdges[{index}]"
            )
            barrier = barrier_by_id.get(edge.get("barrierId"))
            if barrier is None or land_endpoints != {
                barrier["firstLandProvinceId"], barrier["secondLandProvinceId"]
            }:
                raise ValueError(f"traversalEdges[{index}] crossing lacks its exact river barrier")
        _validate_land_source_coverage(
            land_endpoints, edge["sourceRefs"], source_coverage, f"traversalEdges[{index}]"
        )
        if mode in {"LAND", *builder.CROSSING_MODES}:
            first, second = (endpoint["id"] for endpoint in endpoints)
            if not _land_owners_are_adjacent(first, second, owner, province_index_by_id):
                raise ValueError(
                    f"traversalEdges[{index}] endpoints are not decoded owner-grid adjacent"
                )
        if edge.get("mode") in {"EMBARK", "DISEMBARK"}:
            land_endpoint = next(endpoint for endpoint in endpoints if endpoint["kind"] == "LAND_PROVINCE")
            water_endpoint = next(endpoint for endpoint in endpoints if endpoint["kind"] == "WATER_ZONE")
            water_zone = next(zone for zone in zones if zone["id"] == water_endpoint["id"])
            if not _land_touches_cells(
                land_endpoint["id"], geometry_cells_by_id[water_zone["geometryRef"]],
                owner, province_index_by_id,
            ):
                raise ValueError(
                    f"traversalEdges[{index}] land endpoint does not touch the water zone owner boundary"
                )
            _require_source_type(
                edge["sourceRefs"], source_types, "PORT_OR_LANDING",
                f"traversalEdges[{index}]",
            )
    for index, row in enumerate(routes):
        route = _object(row, f"routeCandidates[{index}]", ROUTE_KEYS)
        if route.get("viaWaterZoneId") not in zone_id_set:
            raise ValueError(f"routeCandidates[{index}] has a dangling water endpoint")
        if route.get("status") != "BLOCKED_PENDING_REVIEW":
            raise ValueError(f"routeCandidates[{index}] must remain blocked pending review")
        land_endpoints = {route.get("fromLandProvinceId"), route.get("toLandProvinceId")}
        if None in land_endpoints or not land_endpoints <= land_id_set:
            raise ValueError(f"routeCandidates[{index}] has an unknown land endpoint")
        _validate_land_source_coverage(
            land_endpoints, route["sourceRefs"], source_coverage, f"routeCandidates[{index}]"
        )
    _unique([row["id"] for row in routes], "route candidate ID")
    _validate_source_refs(barriers, known_sources, "riverBarriers")
    _validate_source_refs(edges, known_sources, "traversalEdges")
    _validate_source_refs(routes, known_sources, "routeCandidates")
    _unique([row["id"] for row in edges], "traversal edge ID")
    for index, zone in enumerate(zones):
        has_legal_edge = zone["id"] in incident_zone_ids
        if zone["connectionStatus"] == "CONNECTED" and not has_legal_edge:
            raise ValueError(f"waterZones[{index}] claims CONNECTED without a legal traversal edge")
        if zone["connectionStatus"] == "ISOLATED_NO_REVIEWED_CONNECTION" and has_legal_edge:
            raise ValueError(f"waterZones[{index}] claims explicit isolation but has a legal traversal edge")


def audit_documents(
    tiles: dict,
    tiles_bytes: bytes,
    adjudications: dict,
    adjudication_bytes: bytes,
    artifact: dict,
    artifact_bytes: bytes,
    manifest: dict,
) -> dict:
    validate_artifact(tiles, tiles_bytes, adjudications, artifact)
    canonical_artifact = builder.canonical_json_bytes(artifact)
    expected_artifact = builder.render_artifact(tiles, tiles_bytes, adjudications)
    if artifact_bytes != canonical_artifact or artifact_bytes != expected_artifact:
        raise ValueError("artifact bytes do not match deterministic regeneration")
    expected_manifest = builder.build_manifest(
        tiles_bytes, adjudication_bytes, artifact_bytes, artifact
    )
    if manifest != expected_manifest:
        raise ValueError("manifest hashes/counts do not match audited files (sha256 drift)")

    blocker_codes = sorted(
        row["code"] for row in artifact["activationBlockers"]
        if row["status"] == "BLOCKED"
    )
    has_crossing = any(
        edge["mode"] in builder.CROSSING_MODES for edge in artifact["traversalEdges"]
    )
    river_crossing_ready = (
        bool(artifact["riverBarriers"])
        and has_crossing
        and "NO_REVIEWED_RIVER_CROSSING_EVIDENCE" not in blocker_codes
    )
    return {
        "counts": manifest["counts"],
        "zoneKinds": manifest["zoneKinds"],
        "edgeModes": manifest["edgeModes"],
        "activation": {
            "riverCrossingReady": river_crossing_ready,
            "blockerCodes": blocker_codes,
        },
    }


def audit_materialized() -> dict:
    tiles, tiles_bytes, adjudications, adjudication_bytes = builder.load_inputs()
    if not builder.OUTPUT.is_file() or not builder.MANIFEST.is_file():
        raise ValueError("water topology artifact or manifest is missing")
    artifact_bytes = builder.OUTPUT.read_bytes()
    manifest_bytes = builder.MANIFEST.read_bytes()
    artifact = loads_json_strict(artifact_bytes)
    manifest = loads_json_strict(manifest_bytes)
    return audit_documents(
        tiles, tiles_bytes, adjudications, adjudication_bytes,
        artifact, artifact_bytes, manifest,
    )


def _summary(result: dict) -> str:
    counts = result["counts"]
    activation = result["activation"]
    blocker_codes = ",".join(activation["blockerCodes"]) or "none"
    zone_kinds = ",".join(
        f"{kind}:{count}" for kind, count in sorted(result["zoneKinds"].items())
    ) or "none"
    edge_modes = ",".join(
        f"{mode}:{count}" for mode, count in sorted(result["edgeModes"].items())
    ) or "none"
    return (
        f"landProvinceIds={counts['landProvinceIds']} "
        f"waterZones={counts['waterZones']} "
        f"zoneKinds={zone_kinds} "
        f"riverBarriers={counts['riverBarriers']} "
        f"traversalEdges={counts['traversalEdges']} "
        f"edgeModes={edge_modes} "
        f"evidenceSources={counts['evidenceSources']} "
        f"riverCrossingReady={str(activation['riverCrossingReady']).lower()} "
        f"blockerCodes={blocker_codes}"
    )


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--check", action="store_true", help="audit committed artifact and manifest")
    mode.add_argument(
        "--require-river-activation", action="store_true",
        help="fail unless reviewed river barriers and crossings are active",
    )
    args = parser.parse_args(argv)
    try:
        result = audit_materialized()
    except (OSError, ValueError) as error:
        print(f"Han water topology audit failed: {error}", file=sys.stderr)
        return 1
    summary = _summary(result)
    if args.require_river_activation and not result["activation"]["riverCrossingReady"]:
        print(f"Han water topology river activation blocked: {summary}")
        return 1
    print(f"Han water topology audit passed: {summary}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
