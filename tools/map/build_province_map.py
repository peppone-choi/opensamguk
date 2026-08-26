#!/usr/bin/env python3
"""Build a deterministic lossless province-identity PNG from map tile RLE data."""

import argparse
import hashlib
import json
import re
import struct
import sys
import zlib
from dataclasses import dataclass
from pathlib import Path


PROVINCE_BITS = 12
PROVINCE_LIMIT = (1 << PROVINCE_BITS) - 1
COMMANDERY_LIMIT = 255
MAP_CODE_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_-]*$")
PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"


@dataclass(frozen=True)
class BuildResult:
    png_path: Path
    metadata_path: Path
    png_bytes: bytes
    metadata_bytes: bytes
    width: int
    height: int
    province_count: int
    commandery_count: int


def expand_rle(runs: list[list[int]], cells: int) -> list[int]:
    """Expand row-major ``[value, count]`` runs, rejecting malformed coverage."""
    values: list[int] = []
    if not isinstance(runs, list):
        raise ValueError("RLE runs must be a list")
    for run in runs:
        if not isinstance(run, list) or len(run) != 2:
            raise ValueError("RLE run must contain a value and count")
        value, count = run
        if not isinstance(value, int) or not isinstance(count, int) or count < 0:
            raise ValueError("RLE values and counts must be integers with non-negative counts")
        values.extend([value] * count)
        if len(values) > cells:
            raise ValueError(f"RLE expansion exceeds expected cell count {cells}")
    if len(values) != cells:
        raise ValueError(f"RLE expansion has {len(values)} cells; expected {cells}")
    return values


def encode_identity(province: int, commandery: int) -> tuple[int, int, int]:
    if province == commandery == -1:
        return (0, 0, 0)
    if not 0 <= province < PROVINCE_LIMIT:
        raise ValueError(f"province index out of range: {province}")
    if not 0 <= commandery < COMMANDERY_LIMIT:
        raise ValueError(f"commandery index out of range: {commandery}")
    code = ((commandery + 1) << PROVINCE_BITS) | (province + 1)
    return ((code >> 16) & 0xFF, (code >> 8) & 0xFF, code & 0xFF)


def decode_identity(rgb: tuple[int, int, int]) -> tuple[int, int] | None:
    if len(rgb) != 3 or any(not isinstance(channel, int) or not 0 <= channel <= 0xFF for channel in rgb):
        raise ValueError("RGB identity must contain three byte values")
    code = (rgb[0] << 16) | (rgb[1] << 8) | rgb[2]
    if code == 0:
        return None
    province = (code & PROVINCE_LIMIT) - 1
    commandery = (code >> PROVINCE_BITS) - 1
    if province < 0 or commandery < 0:
        raise ValueError("identity code has a zero hierarchy field")
    if commandery >= COMMANDERY_LIMIT:
        raise ValueError(f"commandery index out of range: {commandery}")
    return province, commandery


def build_from_runs(
    owner: list[list[int]], seat_owner: list[list[int]], cols: int, rows: int
) -> tuple[list[int], list[int], bytes]:
    if not isinstance(cols, int) or not isinstance(rows, int) or cols <= 0 or rows <= 0:
        raise ValueError("map dimensions must be positive integers")
    cells = cols * rows
    provinces = expand_rle(owner, cells)
    commanderies = expand_rle(seat_owner, cells)
    pixels = bytearray()
    for index, (province, commandery) in enumerate(zip(provinces, commanderies)):
        if (province == -1) != (commandery == -1):
            raise ValueError(f"coverage disagreement at cell {index}")
        pixels.extend(encode_identity(province, commandery))
    return provinces, commanderies, bytes(pixels)


def _png_chunk(kind: bytes, payload: bytes) -> bytes:
    return struct.pack(">I", len(payload)) + kind + payload + struct.pack(">I", zlib.crc32(kind + payload) & 0xFFFFFFFF)


def _stored_deflate(data: bytes) -> bytes:
    blocks = bytearray(b"\x78\x01")
    for offset in range(0, len(data) or 1, 65535):
        block = data[offset:offset + 65535]
        final = offset + 65535 >= len(data)
        blocks.append(1 if final else 0)
        blocks.extend(struct.pack("<H", len(block)))
        blocks.extend(struct.pack("<H", 0xFFFF ^ len(block)))
        blocks.extend(block)
    blocks.extend(struct.pack(">I", zlib.adler32(data) & 0xFFFFFFFF))
    return bytes(blocks)


def _make_png(width: int, height: int, pixels: bytes) -> bytes:
    expected = width * height * 3
    if len(pixels) != expected:
        raise ValueError(f"pixel buffer has {len(pixels)} bytes; expected {expected}")
    stride = width * 3
    raw = b"".join(b"\0" + pixels[offset:offset + stride] for offset in range(0, len(pixels), stride))
    ihdr = struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)
    return PNG_SIGNATURE + _png_chunk(b"IHDR", ihdr) + _png_chunk(b"IDAT", _stored_deflate(raw)) + _png_chunk(b"IEND", b"")


def _safe_map_code(map_code: str) -> str:
    if not isinstance(map_code, str) or not MAP_CODE_RE.fullmatch(map_code):
        raise ValueError(f"invalid map code: {map_code!r}")
    return map_code


def _terrain_mismatches(terrain: object, coverage: list[int], cells: int) -> tuple[int, int]:
    if not isinstance(terrain, list) or len(terrain) == 0:
        raise ValueError("terrain must be a non-empty list of rows")
    flattened = "".join(terrain)
    if len(flattened) != cells or any(not isinstance(row, str) for row in terrain):
        raise ValueError(f"terrain has {len(flattened)} cells; expected {cells}")
    water_with_political_coverage = sum(tile == "0" and identity != -1 for tile, identity in zip(flattened, coverage))
    land_without_political_coverage = sum(tile != "0" and identity == -1 for tile, identity in zip(flattened, coverage))
    return water_with_political_coverage, land_without_political_coverage


def _render_assets(source_bytes: bytes, map_data: dict) -> tuple[bytes, bytes, int, int, int, int]:
    meta = map_data.get("_meta")
    if not isinstance(meta, dict):
        raise ValueError("map data is missing _meta")
    cols, rows = meta.get("cols"), meta.get("rows")
    provinces, commanderies, pixels = build_from_runs(map_data.get("owner"), map_data.get("seatOwner"), cols, rows)
    terrain_water_covered, terrain_land_uncovered = _terrain_mismatches(map_data.get("terrain"), provinces, cols * rows)
    png_bytes = _make_png(cols, rows, pixels)
    metadata = {
        "codec": {"commanderyBits": 8, "provinceBits": PROVINCE_BITS, "zeroMeansUncovered": True},
        "counts": {
            "commanderyIdentities": len({value for value in commanderies if value >= 0}),
            "coveredCells": sum(value >= 0 for value in provinces),
            "provinceIdentities": len({value for value in provinces if value >= 0}),
            "terrainLandPoliticalUncovered": terrain_land_uncovered,
            "terrainWaterPoliticalCovered": terrain_water_covered,
        },
        "dimensions": {"cols": cols, "rows": rows},
        "pngSha256": hashlib.sha256(png_bytes).hexdigest(),
        "schemaVersion": 1,
        "sourceSha256": hashlib.sha256(source_bytes).hexdigest(),
    }
    metadata_bytes = (json.dumps(metadata, ensure_ascii=False, sort_keys=True, separators=(",", ":")) + "\n").encode("utf-8")
    return png_bytes, metadata_bytes, cols, rows, metadata["counts"]["provinceIdentities"], metadata["counts"]["commanderyIdentities"]


def build_assets(input_path: Path | str, output_dir: Path | str, map_code: str) -> BuildResult:
    map_code = _safe_map_code(map_code)
    input_path, output_dir = Path(input_path), Path(output_dir)
    source_bytes = input_path.read_bytes()
    try:
        map_data = json.loads(source_bytes)
    except json.JSONDecodeError as error:
        raise ValueError(f"invalid map JSON: {error}") from error
    if not isinstance(map_data, dict):
        raise ValueError("map JSON root must be an object")
    png_bytes, metadata_bytes, width, height, province_count, commandery_count = _render_assets(source_bytes, map_data)
    output_dir.mkdir(parents=True, exist_ok=True)
    png_path = output_dir / f"{map_code}-provinces.png"
    metadata_path = output_dir / f"{map_code}-provinces.meta.json"
    png_path.write_bytes(png_bytes)
    metadata_path.write_bytes(metadata_bytes)
    return BuildResult(png_path, metadata_path, png_bytes, metadata_bytes, width, height, province_count, commandery_count)


def check_assets(input_path: Path | str, output_dir: Path | str, map_code: str) -> bool:
    map_code = _safe_map_code(map_code)
    input_path, output_dir = Path(input_path), Path(output_dir)
    source_bytes = input_path.read_bytes()
    try:
        map_data = json.loads(source_bytes)
    except json.JSONDecodeError:
        return False
    if not isinstance(map_data, dict):
        return False
    png_bytes, metadata_bytes, *_ = _render_assets(source_bytes, map_data)
    png_path = output_dir / f"{map_code}-provinces.png"
    metadata_path = output_dir / f"{map_code}-provinces.meta.json"
    return png_path.is_file() and metadata_path.is_file() and png_path.read_bytes() == png_bytes and metadata_path.read_bytes() == metadata_bytes


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", required=True, type=Path)
    parser.add_argument("--output-dir", required=True, type=Path)
    parser.add_argument("--map-code", required=True)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args(argv)
    if args.check:
        valid = check_assets(args.input, args.output_dir, args.map_code)
        print(f"province map check {'passed' if valid else 'failed'}: {args.map_code}")
        return 0 if valid else 1
    result = build_assets(args.input, args.output_dir, args.map_code)
    print(f"generated {result.png_path}: {result.width}x{result.height}, {result.province_count} province identities, {result.commandery_count} commandery identities")
    return 0


if __name__ == "__main__":
    sys.exit(main())
