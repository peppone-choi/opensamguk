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
    "depthBand", "seasonalAvailability",
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


def _validate_source_catalog(adjudications: dict) -> set[str]:
    dossier_bytes = DOSSIER.read_bytes()
    dossier = _object(loads_json_strict(dossier_bytes), "territory disconnection dossier")
    rows = _array(dossier.get("adjudications"), "territory disconnection adjudications")
    by_component: dict[str, list[dict]] = {}
    for row in rows:
        if isinstance(row, dict) and isinstance(row.get("componentKey"), str):
            by_component.setdefault(row["componentKey"], []).append(row)

    source_ids: list[str] = []
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
        source_ids.append(source_id)
    _unique(source_ids, "sourceCatalog sourceId")
    return set(source_ids)


def _validate_source_refs(rows: list, known_sources: set[str], collection: str) -> None:
    for index, row in enumerate(rows):
        refs = row.get("sourceRefs")
        if not isinstance(refs, list) or not refs or any(not isinstance(ref, str) for ref in refs):
            raise ValueError(f"{collection}[{index}].sourceRefs must be non-empty")
        if len(refs) != len(set(refs)) or set(refs) - known_sources:
            raise ValueError(f"{collection}[{index}].sourceRefs are duplicate or unknown")


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

    known_sources = _validate_source_catalog(adjudications)
    terrain = tiles["terrain"]
    row_count, col_count = len(terrain), len(terrain[0])

    geometries = _array(artifact["geometryComponents"], "geometryComponents")
    geometry_ids: list[str] = []
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
        if type(terrain_code) is not int or type(cell_count) is not int or cell_count < 1:
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
        geometry_ids.append(geometry_id)
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
        zone_ids.append(zone_id)
    _unique(zone_ids, "water zone ID")
    zone_id_set = set(zone_ids)
    _validate_source_refs(zones, known_sources, "waterZones")

    barriers = _array(artifact["riverBarriers"], "riverBarriers")
    edges = _array(artifact["traversalEdges"], "traversalEdges")
    routes = _array(artifact["routeCandidates"], "routeCandidates")
    blockers = _array(artifact["activationBlockers"], "activationBlockers")
    for index, row in enumerate(barriers):
        _object(row, f"riverBarriers[{index}]", BARRIER_KEYS)
    for index, row in enumerate(edges):
        _object(row, f"traversalEdges[{index}]", EDGE_KEYS)
    for index, row in enumerate(routes):
        route = _object(row, f"routeCandidates[{index}]", ROUTE_KEYS)
        if route.get("viaWaterZoneId") not in zone_id_set:
            raise ValueError(f"routeCandidates[{index}] has a dangling water endpoint")
        if route.get("status") != "BLOCKED_PENDING_REVIEW":
            raise ValueError(f"routeCandidates[{index}] must remain blocked pending review")
    for index, row in enumerate(blockers):
        _object(row, f"activationBlockers[{index}]", BLOCKER_KEYS)
    _validate_source_refs(barriers, known_sources, "riverBarriers")
    _validate_source_refs(edges, known_sources, "traversalEdges")
    _validate_source_refs(routes, known_sources, "routeCandidates")


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
    return (
        f"landProvinceIds={counts['landProvinceIds']} "
        f"waterZones={counts['waterZones']} "
        f"riverBarriers={counts['riverBarriers']} "
        f"traversalEdges={counts['traversalEdges']} "
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
