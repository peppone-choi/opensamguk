"""Bounded internal export metadata; road geometry is never a public bundle file."""
from __future__ import annotations

import hashlib
import json
from pathlib import Path
import re

MANIFEST_FILE = "map-design-manifest.json"
ROADS_FILE = "map-design-roads.json"
MAX_MANIFEST = 2 * 1024 * 1024
MAX_ROADS = 16 * 1024 * 1024


def require(condition, message):
    if not condition:
        raise ValueError(message)


def digest(data):
    return hashlib.sha256(data).hexdigest()


def read_metadata_file(directory, name, cap):
    root = Path(directory)
    require(name in (MANIFEST_FILE, ROADS_FILE), "unexpected export metadata path")
    path = root / name
    require(not path.is_symlink() and not path.parent.is_symlink(), "symlink in export metadata path")
    require(path.is_file(), f"missing/nonregular export metadata: {name}")
    size = path.stat().st_size
    require(size <= cap, f"export metadata exceeds cap: {name} bytes={size} cap={cap}")
    with path.open("rb") as stream:
        data = stream.read(cap + 1)
    require(len(data) == size and len(data) <= cap, f"export metadata changed during read: {name}")
    return data


def parse_metadata(data):
    def pairs(items):
        row = {}
        for key, value in items:
            require(key not in row, "duplicate export JSON key")
            row[key] = value
        return row
    return json.loads(data, object_pairs_hook=pairs,
                      parse_constant=lambda value: (_ for _ in ()).throw(ValueError("nonfinite export JSON number")))


def validate_roads(edges):
    require(isinstance(edges, list), "roadEdges must be a list")
    seen = set()
    for edge in edges:
        require(isinstance(edge, dict) and set(edge) == {"edgeId", "status", "fromProvinceId", "toProvinceId",
                                                       "fromTrail", "toTrail", "cells"}, "unexpected road edge fields")
        require(isinstance(edge["edgeId"], str) and bool(edge["edgeId"]) and edge["edgeId"] not in seen,
                "invalid/duplicate road edge ID")
        seen.add(edge["edgeId"])
        require(isinstance(edge["status"], str) and bool(edge["status"]), "invalid road status")
        for key in ("fromProvinceId", "toProvinceId"):
            require(type(edge[key]) in (str, int), "invalid road province ID")
        for key in ("fromTrail", "toTrail", "cells"):
            require(isinstance(edge[key], list) and all(isinstance(cell, list) and len(cell) == 2 and
                    all(type(v) is int and v >= 0 for v in cell) for cell in edge[key]), "invalid road coordinates")
        require(edge["cells"] == edge["fromTrail"] + list(reversed(edge["toTrail"])), "road trail geometry differs")


def write_road_edges(directory, edges):
    validate_roads(edges)
    data = (json.dumps(dict(schemaVersion=1, roadEdges=edges), ensure_ascii=False,
                       separators=(",", ":"), allow_nan=False) + "\n").encode("utf-8")
    require(len(data) <= MAX_ROADS, f"export metadata exceeds cap: {ROADS_FILE} bytes={len(data)} cap={MAX_ROADS}")
    path = Path(directory) / ROADS_FILE
    require(not path.is_symlink() and not path.parent.is_symlink(), "symlink in export metadata path")
    path.write_bytes(data)
    return dict(file=ROADS_FILE, bytes=len(data), sha256=digest(data))


def load_export_metadata(directory, *, manifest_cap=MAX_MANIFEST, road_cap=MAX_ROADS):
    """Normalize roads in memory while hashing the original manifest and sidecar bytes."""
    blob = read_metadata_file(directory, MANIFEST_FILE, manifest_cap)
    manifest = parse_metadata(blob)
    require(isinstance(manifest, dict) and manifest.get("schemaVersion") == 2, "invalid export metadata schema")
    hashes = {"export/manifest": digest(blob)}
    if "roadEdgesFile" not in manifest:
        # Existing small inline exports retain their renderer contract.
        require(isinstance(manifest.get("roadEdges"), list), "missing inline export roads")
        return manifest, blob, hashes
    require("roadEdges" not in manifest, "ambiguous inline and external export roads")
    entry = manifest["roadEdgesFile"]
    require(isinstance(entry, dict) and set(entry) == {"file", "bytes", "sha256"}, "invalid roads descriptor fields")
    require(entry["file"] == ROADS_FILE, "unexpected export roads path")
    require(type(entry["bytes"]) is int and 0 < entry["bytes"] <= road_cap, "invalid export roads byte count")
    require(isinstance(entry["sha256"], str) and re.fullmatch(r"[0-9a-f]{64}", entry["sha256"]), "invalid export roads SHA")
    data = read_metadata_file(directory, ROADS_FILE, road_cap)
    require(len(data) == entry["bytes"] and digest(data) == entry["sha256"], "export roads transport pin differs")
    roads = parse_metadata(data)
    require(isinstance(roads, dict) and set(roads) == {"schemaVersion", "roadEdges"} and
            type(roads["schemaVersion"]) is int and roads["schemaVersion"] == 1, "invalid export roads schema")
    validate_roads(roads["roadEdges"])
    manifest["roadEdges"] = roads["roadEdges"]
    hashes["export/roadEdges"] = digest(data)
    return manifest, blob, hashes
