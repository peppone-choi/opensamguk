#!/usr/bin/env python3
"""Artifact-only full bake with measured stages and an explicit static publication contract.

The build command is expensive and needs a separately allocated Linux runner. The audit
command checks an existing bundle; neither command installs, mounts, or activates it.
"""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import gzip
import hashlib
from importlib import metadata
import json
import os
from pathlib import Path
import platform
import re
import shutil
import struct
import subprocess
import sys
import time
import zlib

ROOT = Path(__file__).resolve().parents[2]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))
from tools.map.export_metadata import load_export_metadata, read_metadata_file, MANIFEST_FILE, ROADS_FILE
SHA = re.compile(r"[0-9a-f]{64}")
COMMIT = re.compile(r"[0-9a-f]{40}")
FILE = re.compile(r"(?:grid/L0/[0-9]+_[0-9]+\.bin\.gz|grid/L2\.bin\.gz|places\.json\.gz|defects\.json)")
MAX_BYTES = 16 * 1024 * 1024
MAX_MANIFEST = 2 * 1024 * 1024
LAYERS = ("ground", "relief", "facets", "landcover", "riverWidth", "riverTier", "roads", "owner")
THREADS = ("OMP_NUM_THREADS", "OPENBLAS_NUM_THREADS", "MKL_NUM_THREADS", "NUMEXPR_NUM_THREADS", "VECLIB_MAXIMUM_THREADS")
SOURCE_PATHS = {
    "sourceTiles": "data/map/province-tiles.json",
    "world": "infra/src/main/resources/map/han-world-v3.json",
    "roads": "data/map/han-land-roads-v1.json",
    "juIndex": "data/map/han-ju-index-v1.json",
    "placements": "data/curated/han/map-design/placements-v1.json",
    "economy": "data/curated/han/county-economy-inputs-v1.json",
    "dem": "web/game/public/map/elevation/han-world-v3-metres.png",
    "artifactCatalog": "data/map/province-world-20261003-artifacts/catalog.json",
    "exportMetadata": "tools/map/export_metadata.py",
}
EXPORT_SOURCE_KEYS = dict(tilesSha256="sourceTiles", worldJsonSha256="world", roadsSha256="roads",
                          demSha256="dem", economySha256="economy", artifactCatalogSha256="artifactCatalog",
                          exportMetadataSha256="exportMetadata")
KIT_DIR = "data/map/waryong/273d596"
BUILD_TOOL = "tools/map/build_map_design.py"
BAKE_TOOL = "tools/map/bake_topdown_map.py"
ARTIFACT_TOOL = "tools/map/build_topdown_artifact.py"


def require(condition, message):
    if not condition:
        raise ValueError(message)


def sha(data):
    return hashlib.sha256(data).hexdigest()


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    temporary.replace(path)


def read_json(data):
    def pairs(items):
        result = {}
        for key, value in items:
            require(key not in result, "duplicate JSON key")
            result[key] = value
        return result
    return json.loads(data, object_pairs_hook=pairs,
                      parse_constant=lambda value: (_ for _ in ()).throw(ValueError("nonfinite JSON number")))


def fields(row, allowed, context, optional=()):
    require(isinstance(row, dict), f"{context}: expected object")
    required = set(allowed) - set(optional)
    require(required <= row.keys() and row.keys() <= set(allowed), f"{context}: unexpected or missing public field")


def leaf_rows(rows, allowed, context, optional=(), nested=None):
    require(isinstance(rows, list), f"{context}: expected list")
    nested = nested or {}
    for row in rows:
        fields(row, allowed, context, optional)
        for key, value in row.items():
            if key in nested:
                fields(value, nested[key], f"{context}.{key}")
                require(all(isinstance(v, (int, float, str, bool)) or v is None for v in value.values()),
                        f"{context}.{key}: nested private object")
            else:
                def public_leaf(item):
                    return (isinstance(item, (int, float, str, bool)) or item is None or
                            (isinstance(item, list) and all(public_leaf(v) for v in item)))
                require(public_leaf(value), f"{context}.{key}: nested private object")
                array_fields = {"cell", "roofCell", "anchor", "gateCells", "wallCells", "wallEnd", "next", "roadCells", "otherProvinces"}
                require(not isinstance(value, list) or key in array_fields, f"{context}.{key}: scalar field is an array")
                text_fields = {"name", "sourceName", "kind", "text", "site", "gates", "orientation", "side", "code", "severity", "reason", "terrainClass"}
                require(key not in text_fields or value is None or isinstance(value, str), f"{context}.{key}: text field is not text")


def audit_public(places, defects):
    fields(places, ("schemaVersion", "provinceCount", "provinceAdmin", "counties", "commanderies", "ju", "cities",
                   "passes", "passEndpointChecks", "labels", "seatAudit", "sourceDefinitions", "roadEdges"),
           "places", optional=("roadEdges",))
    require(places["schemaVersion"] == 1 and type(places["provinceCount"]) is int and places["provinceCount"] > 0,
            "invalid places schema/province count")
    require(len(places["provinceAdmin"]) == places["provinceCount"] and all(
        isinstance(row, list) and len(row) == 3 and all(type(v) is int for v in row)
        for row in places["provinceAdmin"]), "provinceAdmin does not match province count")
    leaf_rows(places["counties"], ("id", "name", "kind", "cityId"), "counties")
    leaf_rows(places["commanderies"], ("id", "name", "kind", "seatCityId", "seatJurisdictionId", "commanderyNo", "labelAnchorMissing"), "commanderies")
    leaf_rows(places["ju"], ("name", "anchor"), "ju")
    leaf_rows(places["cities"], ("id", "name", "sourceName", "level", "cell", "provinceIndex", "countyIndex", "commanderyIndex", "isSeat",
                               "isAdministrativeSeat", "footprint", "roofCell", "gates", "site", "households"), "cities",
              nested={"footprint": ("originCol", "originRow", "span", "innerSpan")})
    leaf_rows(places["passes"], ("cityId", "orientation", "gateCells", "wallCells"), "passes")
    leaf_rows(places["passEndpointChecks"], ("cityId", "orientation", "wallEnd", "next", "terrainClass", "ground", "relief",
                                          "riverWidth", "road", "accepted", "side"), "passEndpointChecks")
    leaf_rows(places["labels"], ("id", "text", "kind", "anchor", "priority", "priorityHouseholds", "footprintSpan"), "labels",
              optional=("priorityHouseholds",))
    roads = places.get("roadEdges", {})
    require(isinstance(roads, dict), "roadEdges: expected object")
    for edge_id, edge in roads.items():
        require(isinstance(edge_id, str) and bool(edge_id), "invalid public road edge ID")
        fields(edge, ("status", "cells"), f"roadEdges.{edge_id}")
        require(isinstance(edge["status"], str) and bool(edge["status"]), "invalid public road status")
        require(isinstance(edge["cells"], list) and bool(edge["cells"]) and all(
            isinstance(cell, list) and len(cell) == 2 and all(type(v) is int and v >= 0 for v in cell)
            for cell in edge["cells"]), "invalid public road cells")
    fields(places["seatAudit"], ("administrativeCityIds", "gameCityIds", "intersection", "administrativeOnly", "gameOnly"), "seatAudit")
    require(all(isinstance(v, list) and all(type(x) is int for x in v) for v in places["seatAudit"].values()), "invalid seatAudit")
    fields(places["sourceDefinitions"], ("administrativeSeat", "gameSeat"), "sourceDefinitions")
    require(all(isinstance(v, str) for v in places["sourceDefinitions"].values()), "invalid sourceDefinitions")
    fields(defects, ("schemaVersion", "counts", "defects"), "defects")
    require(defects["schemaVersion"] == 1, "invalid defects schema")
    kinds = {
        "PASS_WALL_OPEN": ("code", "severity", "cityId", "orientation", "wallEnd", "next", "reason", "wallCells", "limit"),
        "PASS_BYPASS_ROAD": ("code", "severity", "cityId", "orientation", "side", "roadCells"),
        "CITY_FOOTPRINT_CROSSES_PROVINCE": ("code", "severity", "cityId", "span", "province", "cells", "otherProvinces"),
    }
    require(isinstance(defects["counts"], dict) and defects["counts"].keys() <= kinds.keys(), "unexpected defect count field")
    require(isinstance(defects["defects"], list), "invalid defect records")
    counts = {}
    for defect in defects["defects"]:
        require(isinstance(defect, dict) and defect.get("code") in kinds, "unknown public defect")
        code = defect["code"]
        leaf_rows([defect], kinds[code], code)
        counts[code] = counts.get(code, 0) + 1
    require(counts == defects["counts"], "defect counts differ")
    return {"policy": "static-map-v1", "provinceCount": places["provinceCount"], "cityCount": len(places["cities"]),
            "defectCounts": counts, "liveNationFogPersonalFields": False,
            "gamePassControlVerified": False, "publicationApproved": False}


def audit_places_display(places):
    """Classify all text; preserve provenance while rejecting hash markers in display fields."""
    display = {"name", "displayName", "text", "label"}
    metadata = {"id", "kind", "gates", "site", "orientation", "side", "terrainClass", "seatJurisdictionId", "administrativeSeat", "gameSeat"}
    report = {"displayFieldsChecked": 0, "textFieldsChecked": 0, "displayHashHits": [],
              "provenanceHashHits": [], "unclassifiedText": [], "sourceNameIsDisplayApproved": False}
    def visit(value, path, field=None, row_id=None):
        if isinstance(value, dict):
            row_id = value.get("id", value.get("cityId", row_id))
            for key, item in value.items():
                visit(item, f"{path}.{key}", key, row_id)
        elif isinstance(value, list):
            for index, item in enumerate(value):
                visit(item, f"{path}[{index}]", field, row_id)
        elif isinstance(value, str):
            report["textFieldsChecked"] += 1
            entry = dict(path=path, id=row_id, value=value)
            if field in display:
                report["displayFieldsChecked"] += 1
                if "#" in value:
                    report["displayHashHits"].append(entry)
            elif field == "sourceName":
                if "#" in value:
                    report["provenanceHashHits"].append(entry)
            elif field == "status" and path.startswith("places.roadEdges."):
                pass  # Pinned design metadata; not a live road-open decision.
            elif field not in metadata:
                report["unclassifiedText"].append(entry)
    visit(places, "places")
    report["status"] = "PASS" if report["displayFieldsChecked"] > 0 and not report["displayHashHits"] and not report["unclassifiedText"] else "FAILED"
    return report


def audit_places_ids(places, tiles, world, placements):
    """Compare every published city against explicit source bindings, never proximity."""
    def unique(rows, key, context):
        result = {}
        for row in rows:
            value = row[key]
            require(type(value) is int if key == "cityId" or context == "world" else isinstance(value, (str, int)),
                    f"{context}: invalid id")
            require(value not in result, f"{context}: duplicate id")
            result[value] = row
        return result
    source = unique(world["cities"], "id", "world")
    require(bool(source), "world city IDs empty")
    actual = unique(places["cities"], "id", "places")
    require(actual.keys() == source.keys(), "places city IDs missing/unknown")
    anchors = unique(tiles["cities"], "id", "tiles")
    require(len({str(key) for key in anchors}) == len(anchors), "tiles: duplicate normalized id")
    anchors = {str(key): value for key, value in anchors.items()}
    moves = unique(placements["placements"], "cityId", "placements")
    require(moves.keys() <= source.keys(), "placement references unknown city")
    for city_id, city in source.items():
        anchor = anchors.get(str(city.get("spatialProvinceId"))) or anchors.get(str(city.get("physicalPlaceRef", "")).split(":")[-1])
        require(anchor is not None, "city source anchor missing")
        cell = [int(anchor["col"]), int(anchor["row"])]
        move = moves.get(city_id, {}).get("to")
        if move:
            require(len(move) == 2, "invalid placement coordinates")
            cell = [int(move[1]), int(move[0])]
        meta = city.get("meta") or {}
        expected = dict(id=city_id, name=meta.get("displayName") or city["name"], sourceName=city["name"],
                        level=int(city["level"]), cell=cell, provinceIndex=int(city["provinceId"]),
                        isSeat=bool(meta.get("isSeat")))
        require(all(type(actual[city_id][key]) is type(value) and actual[city_id][key] == value
                    for key, value in expected.items()), "places city source binding differs")
    return {"allCityIdsVerified": True, "sourceCityCount": len(source), "publishedCityCount": len(actual),
            "missingUnknownDuplicateIds": 0, "mapping": "world id + explicit tile anchor + committed placement"}


def resource_sample(build_output):
    disk = shutil.disk_usage(build_output.parent)
    memory = {}
    mem_path = Path("/proc/meminfo")
    if mem_path.is_file():
        memory = {key: int(value.split()[0]) for key, value in
                  (line.split(":", 1) for line in mem_path.read_text().splitlines())
                  if key in ("MemTotal", "MemAvailable")}
    owned = {}
    for name in ("export", "bake", "bundles", "candidate"):
        directory = build_output / name
        total = 0
        if directory.is_dir():
            for path in directory.rglob("*"):
                try:
                    if path.is_file() and not path.is_symlink():
                        total += path.stat().st_size
                except FileNotFoundError:
                    # Packaging atomically moves a directory; the next sample captures it.
                    pass
        owned[name] = total
    return {"observedAt": datetime.now(timezone.utc).isoformat(), "diskTotalBytes": disk.total,
            "diskUsedBytes": disk.used, "diskFreeBytes": disk.free,
            "memoryKiB": memory, "ownedOutputBytes": owned}


def observe_resources(args):
    require(0 < args.interval <= 10 and 0 < args.max_seconds <= 21600, "invalid observation interval/limit")
    require(not args.output.exists(), "resource observation directory already exists")
    args.output.mkdir(parents=True)
    started = time.monotonic()
    summary = {"status": "RUNNING", "sampleIntervalSeconds": args.interval, "samples": 0,
               "scope": "shared runner filesystem and owned build output; sampled lower bound, not exact peak",
               "startedAt": datetime.now(timezone.utc).isoformat(), "diskFreeMinimumBytes": None,
               "diskUsedMaximumBytes": 0, "ownedOutputMaximumBytes": 0, "memoryAvailableMinimumKiB": None}
    write_json(args.output / "summary.json", summary)
    try:
        with (args.output / "samples.jsonl").open("w") as stream:
            while True:
                sample = resource_sample(args.build_output)
                stream.write(json.dumps(sample, sort_keys=True) + "\n")
                stream.flush()
                summary["samples"] += 1
                summary["diskFreeMinimumBytes"] = min(sample["diskFreeBytes"] if summary["diskFreeMinimumBytes"] is None else summary["diskFreeMinimumBytes"], sample["diskFreeBytes"])
                summary["diskUsedMaximumBytes"] = max(summary["diskUsedMaximumBytes"], sample["diskUsedBytes"])
                summary["ownedOutputMaximumBytes"] = max(summary["ownedOutputMaximumBytes"], sum(sample["ownedOutputBytes"].values()))
                available = sample["memoryKiB"].get("MemAvailable")
                if available is not None:
                    previous = summary["memoryAvailableMinimumKiB"]
                    summary["memoryAvailableMinimumKiB"] = available if previous is None else min(previous, available)
                write_json(args.output / "summary.json", summary)
                if (args.output / "stop").exists():
                    summary["status"] = "STOPPED"
                    break
                require(time.monotonic() - started < args.max_seconds, "resource observation timed out")
                time.sleep(args.interval)
    except BaseException as error:
        summary.update(status="FAILED", failure=str(error))
        raise
    finally:
        summary.update(finishedAt=datetime.now(timezone.utc).isoformat(), elapsedSeconds=time.monotonic() - started)
        write_json(args.output / "summary.json", summary)


def canonical_identity(manifest):
    identity = {key: manifest[key] for key in ("inputFingerprint", "mapRelease", "kitVersion", "formatVersion")}
    def ascii_keys(value):
        if isinstance(value, dict):
            require(all(isinstance(k, str) and all(32 <= ord(c) <= 126 for c in k) for k in value), "non-ASCII identity key")
            for item in value.values():
                ascii_keys(item)
        elif isinstance(value, list):
            for item in value:
                ascii_keys(item)
    ascii_keys(identity)
    return sha(json.dumps(identity, ensure_ascii=False, sort_keys=True, separators=(",", ":"), allow_nan=False).encode())


def read_regular(root, name, cap):
    require(not Path(name).is_absolute() and ".." not in Path(name).parts, "escaping artifact path")
    path = root / name
    for parent in [path] + list(path.parents):
        require(not parent.is_symlink(), "symlink in artifact path")
        if parent == root:
            break
    require(path.resolve().is_relative_to(root.resolve()) and path.is_file(), "missing/nonregular artifact file")
    size = path.stat().st_size
    require(size <= cap, f"artifact file exceeds cap: {name} bytes={size} cap={cap}")
    return path.read_bytes()


def inflate(blob):
    import io
    with gzip.GzipFile(fileobj=io.BytesIO(blob)) as stream:
        raw = stream.read(MAX_BYTES + 1)
    require(len(raw) <= MAX_BYTES, "inflated artifact exceeds cap")
    return raw


def audit_bundle(bundle, expected_identity=None, source_docs=None):
    require(bundle.is_dir() and not bundle.is_symlink() and SHA.fullmatch(bundle.name), "invalid bundle directory identity")
    manifest_bytes = read_regular(bundle, "manifest.json", MAX_MANIFEST)
    manifest = read_json(manifest_bytes)
    fields(manifest, ("schemaVersion", "artifactId", "bakeId", "inputFingerprint", "mapRelease", "kitVersion", "formatVersion", "shape",
                      "chunkSize", "kitId", "kitCatalogSha256", "inputs", "tool", "partial", "region", "sampleChunks", "validation",
                      "format", "undrawnTile", "chunks", "overview", "places", "defects", "files"), "manifest")
    require(manifest["schemaVersion"] == 1 and manifest["formatVersion"] == 1 and manifest["artifactId"] == "topdown-bake", "invalid manifest version")
    require(manifest["partial"] is False and manifest["region"] is None and manifest["inputFingerprint"].get("region") is None,
            "partial bundle is not a candidate")
    require(manifest["bakeId"] == bundle.name == canonical_identity(manifest), "canonical bake identity differs")
    if expected_identity is not None:
        require(all(manifest[key] == value for key, value in expected_identity.items()), "source/runtime/input identity differs")
    fields(manifest["shape"], ("cols", "rows"), "shape")
    cols, rows = manifest["shape"]["cols"], manifest["shape"]["rows"]
    require(type(cols) is int and type(rows) is int and cols > 0 and rows > 0 and cols % 4 == rows % 4 == 0, "invalid shape")
    require(manifest["chunkSize"] == 256 and manifest["undrawnTile"] == 65535, "invalid chunk format")
    fields(manifest["tool"], ("file", "sha256"), "tool")
    require(manifest["tool"]["file"] == BAKE_TOOL and SHA.fullmatch(manifest["tool"]["sha256"]), "invalid producer tool pin")
    fields(manifest["validation"], ("gamePassControl", "provincePlane"), "validation")
    fields(manifest["format"], ("cellOrder", "chunkFile", "tilePlane", "provincePlane", "uniformChunk", "overview", "rawSha256"), "format")
    require(all(isinstance(v, str) for group in ("validation", "format") for v in manifest[group].values()), "unexpected nested manifest field")
    expected_keys = {(cx, cy) for cy in range((rows + 255) // 256) for cx in range((cols + 255) // 256)}
    seen, entries = set(), []
    for chunk in manifest["chunks"]:
        fields(chunk, ("cx", "cy", "file", "sha256", "rawSha256", "bytes") if "file" in chunk else ("cx", "cy", "uniform", "rawSha256"), "chunk")
        key = (chunk["cx"], chunk["cy"])
        require(all(type(v) is int for v in key) and key in expected_keys and key not in seen, "duplicate/outside chunk")
        seen.add(key)
        if "file" in chunk:
            require(chunk["file"] == f"grid/L0/{key[0]}_{key[1]}.bin.gz", "chunk path differs from coordinates")
            entries.append(chunk)
        else:
            fields(chunk["uniform"], ("tile", "province"), "uniform")
            values = [chunk["uniform"][k] for k in ("tile", "province")]
            require(all(type(v) is int and 0 <= v <= 65535 for v in values), "invalid uniform value")
            raw = b"".join(struct.pack("<H", value) * (256 * 256) for value in values)
            require(sha(raw) == chunk["rawSha256"], "uniform raw SHA differs")
    require(seen == expected_keys, "incomplete full chunk coverage")
    for key, filename in (("overview", "grid/L2.bin.gz"), ("places", "places.json.gz"), ("defects", "defects.json")):
        extra = ("cols", "rows", "block") if key == "overview" else (("counts",) if key == "defects" else ())
        fields(manifest[key], ("file", "sha256", "rawSha256", "bytes") + extra, key)
        require(manifest[key]["file"] == filename, f"{key}: unexpected filename")
        entries.append(manifest[key])
    require((manifest["overview"]["cols"], manifest["overview"]["rows"], manifest["overview"]["block"]) == (cols // 4, rows // 4, 4), "overview extent differs")
    expected_files = {"manifest.json"} | {entry["file"] for entry in entries}
    require(all(not path.is_symlink() for path in bundle.rglob("*")), "symlink in bundle")
    actual_files = {path.relative_to(bundle).as_posix() for path in bundle.rglob("*") if path.is_file()}
    require(actual_files == expected_files and len(entries) + 1 == len(expected_files), "missing/extra/duplicate bundle file")
    require(isinstance(manifest["files"], list) and len(manifest["files"]) == len(entries), "invalid files inventory")
    indexed = {}
    for entry in manifest["files"]:
        fields(entry, ("file", "sha256", "bytes", "rawSha256", "compression"), "files")
        require(FILE.fullmatch(entry["file"]) and entry["file"] not in indexed, "invalid/duplicate published path")
        indexed[entry["file"]] = entry
    inventory = [{"file": "manifest.json", "bytes": len(manifest_bytes), "sha256": sha(manifest_bytes), "compression": "none"}]
    decoded = {}
    for entry in entries:
        name = entry["file"]
        require(name in indexed and all(indexed[name][k] == entry[k] for k in ("file", "sha256", "rawSha256", "bytes")), "inventory differs from manifest entry")
        require(type(entry["bytes"]) is int and 0 <= entry["bytes"] <= MAX_BYTES and SHA.fullmatch(entry["sha256"]) and SHA.fullmatch(entry["rawSha256"]), "invalid size/hash")
        compression = "gzip" if name.endswith(".gz") else "none"
        require(indexed[name]["compression"] == compression, "compression contract differs")
        blob = read_regular(bundle, name, MAX_BYTES)
        require(len(blob) == entry["bytes"] and sha(blob) == entry["sha256"], "transport SHA/length differs")
        if compression == "gzip":
            require(blob[:3] == b"\x1f\x8b\x08" and blob[3] == 0 and blob[4:8] == b"\0" * 4 and blob[9] == 255, "gzip header/mtime/platform differs")
        raw = inflate(blob) if compression == "gzip" else blob
        require(sha(raw) == entry["rawSha256"], "raw SHA differs")
        if name.startswith("grid/L0/"):
            require(len(raw) == 256 * 256 * 4, "invalid L0 two-plane length")
        elif name == "grid/L2.bin.gz":
            require(len(raw) == (cols // 4) * (rows // 4) * 4, "invalid L2 two-plane length")
        else:
            decoded[name] = read_json(raw)
        inventory.append(dict(indexed[name], rawBytes=len(raw)))
    public = audit_public(decoded["places.json.gz"], decoded["defects.json"])
    require(manifest["defects"]["counts"] == public["defectCounts"], "manifest defect counts differ")
    display_audit = audit_places_display(decoded["places.json.gz"])
    require(display_audit["status"] == "PASS", "places display #/unclassified text: " + json.dumps(display_audit, ensure_ascii=False))
    source_places = audit_places_ids(decoded["places.json.gz"], **source_docs) if source_docs is not None else None
    return {"placesDisplayAudit": display_audit, "placesSourceAudit": source_places, "bakeId": bundle.name, "manifestSha256": sha(manifest_bytes), "manifestBytes": len(manifest_bytes),
            "identity": {key: manifest[key] for key in ("inputFingerprint", "mapRelease", "kitVersion", "formatVersion")},
            "files": inventory, "publicScope": public, "fullBakeRegenerationCheckedHere": False}


def source_pin(root):
    def fingerprint(name):
        raw = read_regular(root, name, 64 * 1024 * 1024)
        return {"path": name, "bytes": len(raw), "sha256": sha(raw)}
    tracked = set(subprocess.check_output(["git", "ls-files"], cwd=root, text=True).splitlines())
    paths = list(SOURCE_PATHS.values()) + [BUILD_TOOL, BAKE_TOOL, ARTIFACT_TOOL, "tools/map/audit_topdown_places.py", "tools/map/requirements-artifact.txt", ".github/workflows/map-artifact.yml"]
    designs = sorted(p.relative_to(root).as_posix() for p in (root / "data/curated/han/map-design").glob("*.json"))
    paths.extend(designs)
    kit = read_json(read_regular(root, KIT_DIR + "/export.json", MAX_MANIFEST))
    for name in ("catalog.json", "synth-stats.json.gz", "kit-index.png", "export.json"):
        relative = KIT_DIR + "/" + name
        if not (root / relative).is_file():
            relative = f"web/game/public/map/waryong/{kit['kitId']}/{name}"
        paths.append(relative)
    require(all(p in tracked for p in paths), "untracked source input")
    files = {name: fingerprint(name) for name in paths}
    catalog = read_json(read_regular(root, SOURCE_PATHS["artifactCatalog"], MAX_MANIFEST))
    frozen = {row["path"]: row["sha256"] for row in catalog["files"]}
    require(all(frozen[SOURCE_PATHS[name]] == files[SOURCE_PATHS[name]]["sha256"] for name in ("sourceTiles", "world", "roads")), "frozen source catalog differs")
    kit_hashes = {}
    for name in ("catalog.json", "synth-stats.json.gz", "kit-index.png"):
        relative = next(p for p in paths if p.endswith("/" + name) and "/waryong/" in p)
        expected = [row for p, row in kit["files"].items() if Path(p).name == name]
        require(len(expected) == 1 and expected[0]["sha256"] == files[relative]["sha256"], "kit export differs")
        kit_hashes["kit/" + name] = files[relative]["sha256"]
    kit_hashes["kit/export.json"] = files[KIT_DIR + "/export.json"]["sha256"]
    kit_hashes["kit/sourceMergeCommit"] = kit["source"]["mergeCommit"]
    return {"files": files, "designPaths": designs, "mapRelease": catalog["artifactId"], "kitId": kit["kitId"],
            "kitVersion": kit["source"]["mergeCommit"], "kitInputs": kit_hashes}


def expected_identity(pin, export_dir, runtime):
    export, blob, metadata_hashes = load_export_metadata(export_dir, manifest_cap=MAX_MANIFEST, road_cap=MAX_BYTES)
    require(export["schemaVersion"] == 2 and export["artifactId"] == "map-design-export-v2" and export["mapRelease"] == pin["mapRelease"], "export source release differs")
    files = pin["files"]
    fingerprint = {key: files[SOURCE_PATHS[name]]["sha256"] for key, name in EXPORT_SOURCE_KEYS.items()}
    fingerprint["designJsonSha256"] = {p: files[p]["sha256"] for p in pin["designPaths"]}
    fingerprint["exportGeneratorSha256"] = files[BUILD_TOOL]["sha256"]
    require(export["inputFingerprint"] == fingerprint, "export input fingerprint differs")
    require(set(export["files"]) == set(LAYERS), "export layer inventory differs")
    inputs = {"repo/" + name: files[path]["sha256"] for name, path in SOURCE_PATHS.items()}
    inputs.update(pin["kitInputs"])
    inputs.update(metadata_hashes)
    for name in LAYERS:
        entry = export["files"][name]
        require(entry["file"] == f"map-design-{name}.png", "unexpected export layer path")
        data = read_regular(export_dir, entry["file"], MAX_BYTES)
        require(sha(data) == entry["sha256"] and len(data) == entry["bytes"] and SHA.fullmatch(entry["rawSha256"]), "export transport pin differs")
        inputs["export/" + name] = entry["rawSha256"]
    fingerprint.update(bakeInputs=dict(sorted(inputs.items())), bakeGeneratorSha256=files[BAKE_TOOL]["sha256"],
                       chunkSize=256, pad=6, region=None, compressionRuntime=runtime["zlibRuntime"])
    return {"inputFingerprint": fingerprint, "inputs": inputs, "mapRelease": pin["mapRelease"],
            "kitVersion": pin["kitVersion"], "kitId": pin["kitId"], "formatVersion": 1,
            "kitCatalogSha256": pin["kitInputs"]["kit/catalog.json"], "shape": dict(rows=export["shape"][0], cols=export["shape"][1]),
            "tool": {"file": BAKE_TOOL, "sha256": files[BAKE_TOOL]["sha256"]}}


def retain_export_metadata(export_dir, evidence):
    """Record exact metadata bytes before bake so later failures retain their inputs."""
    inventory = {}
    for name, cap in ((MANIFEST_FILE, MAX_MANIFEST), (ROADS_FILE, MAX_BYTES)):
        path = export_dir / name
        if path.exists():
            inventory[name] = dict(bytes=path.stat().st_size, cap=cap)
    write_json(evidence / "export-metadata.json", inventory)
    for name, entry in inventory.items():
        data = read_metadata_file(export_dir, name, entry["cap"])
        (evidence / name).write_bytes(data)
        entry["sha256"] = sha(data)
    write_json(evidence / "export-metadata.json", inventory)


def runtime_pin():
    versions = {name: metadata.version(name) for name in ("numpy", "Pillow")}
    require(sys.version_info[:3] == (3, 12, 10), "Python runtime differs from 3.12.10")
    require(versions == {"numpy": "2.4.6", "Pillow": "12.2.0"}, "dependency versions differ")
    require(all(os.environ.get(name) == "1" for name in THREADS) and os.environ.get("PYTHONHASHSEED") == "0", "single-thread/deterministic runtime not configured")
    return {"python": sys.version, "executable": sys.executable, "platform": platform.platform(), "libc": list(platform.libc_ver()),
            "packages": versions, "zlibBuild": zlib.ZLIB_VERSION, "zlibRuntime": zlib.ZLIB_RUNTIME_VERSION,
            "threads": {name: "1" for name in THREADS}, "workers": 1, "pythonHashSeed": "0"}


def runner_resources(output):
    require(platform.system() == "Linux" and platform.machine() in ("x86_64", "AMD64"), "dedicated Linux x64 runner required")
    mem = dict((key, int(value.split()[0])) for key, value in
               (line.split(":", 1) for line in Path("/proc/meminfo").read_text().splitlines()))
    require((os.cpu_count() or 0) >= 2 and mem["MemTotal"] >= 15 * 1024 * 1024 and mem["MemAvailable"] >= 12 * 1024 * 1024,
            "runner needs 2 CPUs, nominal 16GiB RAM and 12GiB available")
    free = shutil.disk_usage(output).free
    require(free >= 10 * 1024 ** 3, "runner requires 10GiB free artifact space")
    require(Path("/usr/bin/time").is_file(), "GNU time is required")
    return {"cpus": os.cpu_count(), "memoryTotalKiB": mem["MemTotal"], "memoryAvailableKiB": mem["MemAvailable"], "diskFreeBytes": free}


def measured_stage(name, command, evidence, record):
    require(name not in [stage["name"] for stage in record["stages"]], "duplicate build stage")
    stage = {"name": name, "command": command, "startedAt": datetime.now(timezone.utc).isoformat(), "status": "RUNNING", "exitCode": None}
    record["stages"].append(stage)
    write_json(evidence / "run.json", record)
    metrics = evidence / f"{name}.metrics.json"
    started = time.monotonic()
    with (evidence / f"{name}.log").open("wb") as log:
        result = subprocess.run(["/usr/bin/time", "-f", '{"maxRssKiB":%M,"elapsedSeconds":%e,"exitCode":%x}',
                                 "-o", str(metrics), *command], cwd=ROOT, stdout=log, stderr=subprocess.STDOUT)
    stage.update(exitCode=result.returncode, wallSeconds=time.monotonic() - started,
                 finishedAt=datetime.now(timezone.utc).isoformat(), status="PASS" if result.returncode == 0 else "FAILED")
    write_json(evidence / "run.json", record)
    require(result.returncode == 0, f"{name} failed: exit {result.returncode} (77 is not verified)")
    timing = read_json(metrics.read_bytes())
    require(timing["exitCode"] == 0 and timing["maxRssKiB"] > 0 and timing["elapsedSeconds"] >= 0, "invalid stage resource evidence")
    stage["metrics"] = timing
    write_json(evidence / "run.json", record)


def build(args):
    require(COMMIT.fullmatch(args.source_sha) and args.source_sha == args.workflow_sha, "source/workflow commit pin differs")
    require(subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip() == args.source_sha, "checkout SHA differs")
    require(not subprocess.check_output(["git", "status", "--porcelain"], cwd=ROOT, text=True).strip(), "source checkout must be clean")
    output = args.output.resolve()
    require(not output.is_relative_to(ROOT.resolve()) and not output.exists(), "use a fresh output directory outside checkout")
    output.mkdir(parents=True)
    evidence = output / "evidence"
    record = {"status": "BUILDING", "sourceSha": args.source_sha, "workflowSha": args.workflow_sha,
              "startedAt": datetime.now(timezone.utc).isoformat(), "stages": [], "operationalChanges": False,
              "publicationApproved": False}
    write_json(evidence / "run.json", record)
    try:
        record["runtime"] = runtime_pin()
        record["runnerResources"] = runner_resources(output)
        pin = source_pin(ROOT)
        write_json(evidence / "source-inputs.json", pin)
        write_json(evidence / "runtime.json", record["runtime"])
        export, bake, bundles = (output / name for name in ("export", "bake", "bundles"))
        measured_stage("export", [sys.executable, BUILD_TOOL, "--export", str(export)], evidence, record)
        retain_export_metadata(export, evidence)
        measured_stage("bake-package", [sys.executable, BAKE_TOOL, "--export-dir", str(export), "--kit-dir", KIT_DIR,
                                        "--out", str(bake), "--workers", "1", "--bundle-root", str(bundles)], evidence, record)
        measured_stage("published-check", [sys.executable, BAKE_TOOL, "--export-dir", str(export), "--kit-dir", KIT_DIR,
                                           "--check-published", "--bundle-root", str(bundles)], evidence, record)
        measured_stage("public-audit", [sys.executable, ARTIFACT_TOOL, "audit", "--bundle-root", str(bundles),
                                        "--export-dir", str(export), "--evidence", str(evidence)], evidence, record)
        require(pin == source_pin(ROOT) and record["runtime"] == runtime_pin(), "source/runtime changed during generation")
        require(not subprocess.check_output(["git", "status", "--porcelain"], cwd=ROOT, text=True).strip(), "source tree changed during generation")
        candidate = output / "candidate"
        candidate.mkdir()
        bundles.rename(candidate / "bundles")
        audit = read_json((evidence / "bundle-audit.json").read_bytes())
        record.update(status="VERIFIED_CANDIDATE", finishedAt=datetime.now(timezone.utc).isoformat(),
                      bakeId=audit["bakeId"], manifestSha256=audit["manifestSha256"], fullBakeRegenerationChecked=True)
        write_json(evidence / "run.json", record)
        print(f"Verified artifact candidate {audit['bakeId']}; publication remains unapproved")
    except BaseException as error:
        record.update(status="FAILED", finishedAt=datetime.now(timezone.utc).isoformat(), failure=str(error))
        write_json(evidence / "run.json", record)
        raise


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    generation = commands.add_parser("build", help="expensive full bake on a separately allocated runner")
    generation.add_argument("--source-sha", required=True)
    generation.add_argument("--workflow-sha", required=True)
    generation.add_argument("--output", type=Path, required=True)
    observe = commands.add_parser("observe-resources", help="sample dependency/build resource usage; no bake")
    observe.add_argument("--output", type=Path, required=True)
    observe.add_argument("--build-output", type=Path, required=True)
    observe.add_argument("--interval", type=float, default=1)
    observe.add_argument("--max-seconds", type=float, default=21600)
    audit = commands.add_parser("audit", help="audit an existing complete bundle; does not run a bake")
    audit.add_argument("--bundle-root", type=Path, required=True)
    audit.add_argument("--export-dir", type=Path, required=True)
    audit.add_argument("--evidence", type=Path, required=True)
    args = parser.parse_args(argv)
    try:
        if args.command == "build":
            build(args)
        elif args.command == "observe-resources":
            observe_resources(args)
        else:
            pin = read_json((args.evidence / "source-inputs.json").read_bytes())
            runtime = read_json((args.evidence / "runtime.json").read_bytes())
            require(pin == source_pin(ROOT) and runtime == runtime_pin(), "audit source/runtime differs")
            require(args.bundle_root.is_dir() and not args.bundle_root.is_symlink(), "missing bundle root")
            children = list(args.bundle_root.iterdir())
            require(len(children) == 1, "artifact must contain exactly one full bundle")
            docs = {key: read_json(read_regular(ROOT, SOURCE_PATHS[name], 64 * 1024 * 1024))
                    for key, name in (("tiles", "sourceTiles"), ("world", "world"), ("placements", "placements"))}
            result = audit_bundle(children[0], expected_identity(pin, args.export_dir, runtime), docs)
            write_json(args.evidence / "bundle-audit.json", result)
        return 0
    except (ValueError, OSError, KeyError, TypeError, subprocess.SubprocessError) as error:
        print(f"Artifact verification failed: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
