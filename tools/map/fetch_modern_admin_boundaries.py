#!/usr/bin/env python3
"""Fetch the pinned geoBoundaries CGAZ ADM2 archive and extract its GeoJSON."""

from __future__ import annotations

import argparse
import hashlib
import io
import json
import os
import struct
import urllib.request
import zipfile
from dataclasses import dataclass
from pathlib import Path
from typing import Any


_RECIPE_KEYS = {
    "schemaVersion", "dataset", "release", "level", "product", "url",
    "sha256", "license", "attribution", "retrievedAt",
}
_HEX = frozenset("0123456789abcdef")
_MEMBER = "geoBoundariesCGAZ_ADM2.geojson"
_SHP_MEMBER = "geoBoundariesCGAZ_ADM2.shp"
_DBF_MEMBER = "geoBoundariesCGAZ_ADM2.dbf"
# Canonical 768×669 frame plus one degree of rasterization padding.
_FRAME_BBOX = (91.0, 135.0, 13.0, 47.0)


@dataclass(frozen=True)
class BoundaryRecipe:
    schema_version: int
    dataset: str
    release: str
    level: str
    product: str
    url: str
    sha256: str
    license: str
    attribution: str
    retrieved_at: str


@dataclass(frozen=True)
class BoundaryArtifact:
    output: Path
    archive_sha256: str
    output_sha256: str
    feature_count: int


def _exact_object(value: Any, keys: set[str], where: str) -> dict[str, Any]:
    if not isinstance(value, dict) or set(value) != keys:
        actual = set(value) if isinstance(value, dict) else set()
        raise ValueError(
            f"{where}: exact keys required; missing={sorted(keys - actual)}, "
            f"unknown={sorted(actual - keys)}"
        )
    return value


def load_boundary_recipe(path: Path | str) -> BoundaryRecipe:
    try:
        raw = json.loads(Path(path).read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise ValueError("boundary recipe is unreadable") from error
    raw = _exact_object(raw, _RECIPE_KEYS, "boundary recipe")
    if raw["schemaVersion"] != 1:
        raise ValueError("boundary recipe schemaVersion must be 1")
    if raw["dataset"] != "geoBoundaries" or raw["product"] != "CGAZ":
        raise ValueError("boundary recipe dataset/product mismatch")
    if raw["release"] != "6.0.0" or raw["level"] != "ADM2":
        raise ValueError("boundary recipe release/level mismatch")
    if raw["license"] != "CC BY 4.0":
        raise ValueError("boundary recipe license mismatch")
    digest = raw["sha256"]
    if not isinstance(digest, str) or len(digest) != 64 or any(ch not in _HEX for ch in digest):
        raise ValueError("boundary recipe SHA-256 is invalid")
    for key in ("url", "attribution", "retrievedAt"):
        if not isinstance(raw[key], str) or not raw[key]:
            raise ValueError(f"boundary recipe {key} is required")
    return BoundaryRecipe(
        schema_version=raw["schemaVersion"], dataset=raw["dataset"],
        release=raw["release"], level=raw["level"], product=raw["product"],
        url=raw["url"], sha256=digest, license=raw["license"],
        attribution=raw["attribution"], retrieved_at=raw["retrievedAt"],
    )


def _read_response(response: Any) -> bytes:
    chunks: list[bytes] = []
    while True:
        chunk = response.read(1024 * 1024)
        if not chunk:
            return b"".join(chunks)
        chunks.append(chunk)


def _dbf_rows(blob: bytes) -> list[dict[str, str]]:
    if len(blob) < 33:
        raise ValueError("boundary DBF is truncated")
    count = struct.unpack_from("<I", blob, 4)[0]
    header_size, record_size = struct.unpack_from("<HH", blob, 8)
    fields: list[tuple[str, int]] = []
    offset = 32
    while offset < header_size and blob[offset] != 0x0D:
        descriptor = blob[offset:offset + 32]
        name = descriptor[:11].split(b"\0", 1)[0].decode("ascii", "strict")
        fields.append((name, descriptor[16]))
        offset += 32
    if sum(width for _, width in fields) + 1 != record_size:
        raise ValueError("boundary DBF record width mismatch")
    rows: list[dict[str, str]] = []
    for index in range(count):
        start = header_size + index * record_size
        record = blob[start:start + record_size]
        if len(record) != record_size:
            raise ValueError("boundary DBF record is truncated")
        cursor = 1
        row: dict[str, str] = {}
        for name, width in fields:
            raw = record[cursor:cursor + width].rstrip(b" \0")
            row[name] = raw.decode("utf-8", "replace")
            cursor += width
        rows.append(row)
    return rows


def _ring_area(ring: list[list[float]]) -> float:
    return sum(
        a[0] * b[1] - b[0] * a[1]
        for a, b in zip(ring, ring[1:] + ring[:1])
    ) / 2.0


def _shape_geometry(content: bytes) -> tuple[tuple[float, float, float, float], dict[str, Any] | None]:
    if len(content) < 44:
        return (0.0, 0.0, 0.0, 0.0), None
    shape_type = struct.unpack_from("<i", content, 0)[0]
    if shape_type == 0:
        return (0.0, 0.0, 0.0, 0.0), None
    if shape_type not in {5, 15, 25}:
        raise ValueError(f"unsupported boundary shape type {shape_type}")
    bbox = struct.unpack_from("<4d", content, 4)
    part_count, point_count = struct.unpack_from("<2i", content, 36)
    parts = list(struct.unpack_from(f"<{part_count}i", content, 44))
    points_offset = 44 + part_count * 4
    required = points_offset + point_count * 16
    if part_count < 1 or point_count < 3 or len(content) < required:
        raise ValueError("boundary polygon record is truncated")
    points = [
        list(struct.unpack_from("<2d", content, points_offset + index * 16))
        for index in range(point_count)
    ]
    rings = [points[start:end] for start, end in zip(parts, parts[1:] + [point_count])]
    rings = [ring for ring in rings if len(ring) >= 4]
    if not rings:
        return bbox, None
    # ESRI polygon exteriors are clockwise (negative signed area).  Associate
    # following counter-clockwise holes with their exterior; tolerate malformed
    # orientation by promoting an orphan ring to an exterior.
    polygons: list[list[list[list[float]]]] = []
    for ring in rings:
        if _ring_area(ring) < 0 or not polygons:
            polygons.append([ring])
        else:
            polygons[-1].append(ring)
    geometry: dict[str, Any]
    if len(polygons) == 1:
        geometry = {"type": "Polygon", "coordinates": polygons[0]}
    else:
        geometry = {"type": "MultiPolygon", "coordinates": polygons}
    return bbox, geometry


def _shapefile_features(shp: bytes, dbf: bytes) -> list[dict[str, Any]]:
    if len(shp) < 100 or struct.unpack_from(">i", shp, 0)[0] != 9994:
        raise ValueError("boundary SHP header is invalid")
    rows = _dbf_rows(dbf)
    features: list[dict[str, Any]] = []
    cursor = 100
    record_index = 0
    lo_x, hi_x, lo_y, hi_y = _FRAME_BBOX
    while cursor < len(shp):
        if cursor + 8 > len(shp):
            raise ValueError("boundary SHP record header is truncated")
        _, words = struct.unpack_from(">2i", shp, cursor)
        size = words * 2
        content = shp[cursor + 8:cursor + 8 + size]
        if len(content) != size:
            raise ValueError("boundary SHP record is truncated")
        if record_index >= len(rows):
            raise ValueError("boundary SHP/DBF record count mismatch")
        bbox, geometry = _shape_geometry(content)
        if geometry is not None and not (
            bbox[2] < lo_x or bbox[0] > hi_x or bbox[3] < lo_y or bbox[1] > hi_y
        ):
            properties = {
                key: value for key, value in rows[record_index].items()
                if key in {"shapeName", "shapeISO", "shapeID", "shapeGroup", "shapeType"}
            }
            features.append({"type": "Feature", "properties": properties, "geometry": geometry})
        record_index += 1
        cursor += 8 + size
    if record_index != len(rows):
        raise ValueError("boundary SHP/DBF record count mismatch")
    return features


def fetch_boundary_archive(recipe: BoundaryRecipe, output: Path | str) -> BoundaryArtifact:
    destination = Path(output)
    destination.parent.mkdir(parents=True, exist_ok=True)
    temporary = destination.with_suffix(destination.suffix + ".part")
    try:
        request = urllib.request.Request(recipe.url, headers={"User-Agent": "OpenSamguk-map-builder/1"})
        with urllib.request.urlopen(request) as response:
            archive_blob = _read_response(response)
        archive_digest = hashlib.sha256(archive_blob).hexdigest()
        if archive_digest != recipe.sha256:
            raise ValueError(
                f"boundary archive SHA-256 mismatch: expected {recipe.sha256}, got {archive_digest}"
            )
        try:
            with zipfile.ZipFile(io.BytesIO(archive_blob)) as archive:
                names = [name for name in archive.namelist() if Path(name).name == _MEMBER]
                if len(names) == 1:
                    geojson_blob = archive.read(names[0])
                else:
                    shp_names = [name for name in archive.namelist() if Path(name).name == _SHP_MEMBER]
                    dbf_names = [name for name in archive.namelist() if Path(name).name == _DBF_MEMBER]
                    if len(shp_names) != 1 or len(dbf_names) != 1:
                        raise ValueError("boundary archive must contain one ADM2 GeoJSON or SHP/DBF pair")
                    document = {
                        "type": "FeatureCollection",
                        "features": _shapefile_features(
                            archive.read(shp_names[0]), archive.read(dbf_names[0])
                        ),
                    }
                    geojson_blob = json.dumps(
                        document, ensure_ascii=False, sort_keys=True,
                        separators=(",", ":"), allow_nan=False,
                    ).encode("utf-8")
        except zipfile.BadZipFile as error:
            raise ValueError("boundary archive is not a valid ZIP") from error
        try:
            document = json.loads(geojson_blob)
        except (UnicodeDecodeError, json.JSONDecodeError) as error:
            raise ValueError("boundary output is not valid GeoJSON JSON") from error
        if not isinstance(document, dict) or document.get("type") != "FeatureCollection":
            raise ValueError("boundary output must be a GeoJSON FeatureCollection")
        features = document.get("features")
        if not isinstance(features, list):
            raise ValueError("boundary FeatureCollection features must be an array")
        temporary.write_bytes(geojson_blob)
        os.replace(temporary, destination)
        return BoundaryArtifact(
            output=destination,
            archive_sha256=archive_digest,
            output_sha256=hashlib.sha256(geojson_blob).hexdigest(),
            feature_count=len(features),
        )
    finally:
        temporary.unlink(missing_ok=True)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--recipe", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    artifact = fetch_boundary_archive(load_boundary_recipe(args.recipe), args.output)
    print(json.dumps({
        "archiveSha256": artifact.archive_sha256,
        "outputSha256": artifact.output_sha256,
        "featureCount": artifact.feature_count,
        "output": str(artifact.output),
    }, ensure_ascii=False, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
