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
MAX_DIMENSION = 4096
MAX_CELLS = 4_194_304
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
    if type(cells) is not int or cells < 0 or cells > MAX_CELLS:
        raise ValueError(f"RLE cell count must be an integer from 0 to {MAX_CELLS}")
    if not isinstance(runs, list):
        raise ValueError("RLE runs must be a list")
    for run in runs:
        if not isinstance(run, list) or len(run) != 2:
            raise ValueError("RLE run must contain a value and count")
        value, count = run
        if type(value) is not int or type(count) is not int or count < 0:
            raise ValueError("RLE values and counts must be integers with non-negative counts")
        if count > cells - len(values):
            raise ValueError(f"RLE expansion exceeds expected cell count {cells}")
        values.extend([value] * count)
    if len(values) != cells:
        raise ValueError(f"RLE expansion has {len(values)} cells; expected {cells}")
    return values


def encode_identity(province: int, commandery: int) -> tuple[int, int, int]:
    if type(province) is not int or not -1 <= province < PROVINCE_LIMIT:
        raise ValueError(f"province index out of range: {province}")
    if type(commandery) is not int or not -1 <= commandery < COMMANDERY_LIMIT:
        raise ValueError(f"commandery index out of range: {commandery}")
    if province == commandery == -1:
        return (0, 0, 0)
    if not 0 <= province < PROVINCE_LIMIT:
        raise ValueError(f"province index out of range: {province}")
    if not 0 <= commandery < COMMANDERY_LIMIT:
        raise ValueError(f"commandery index out of range: {commandery}")
    code = ((commandery + 1) << PROVINCE_BITS) | (province + 1)
    return ((code >> 16) & 0xFF, (code >> 8) & 0xFF, code & 0xFF)


def decode_identity(rgb: tuple[int, int, int]) -> tuple[int, int] | None:
    if len(rgb) != 3 or any(type(channel) is not int or not 0 <= channel <= 0xFF for channel in rgb):
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
    if type(cols) is not int or type(rows) is not int or cols <= 0 or rows <= 0:
        raise ValueError("map dimensions must be positive integers")
    if cols > MAX_DIMENSION or rows > MAX_DIMENSION:
        raise ValueError(f"map dimension exceeds practical limit {MAX_DIMENSION}")
    cells = cols * rows
    if cells > MAX_CELLS:
        raise ValueError(f"map cell count exceeds practical limit {MAX_CELLS}")
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


def _decode_emitted_png(png_bytes: bytes) -> tuple[int, int, list[int], list[int]]:
    """Decode emitted scanlines without calling the identity encoder/decoder."""
    if not png_bytes.startswith(PNG_SIGNATURE):
        raise ValueError("PNG signature is invalid")
    position = len(PNG_SIGNATURE)
    chunks: list[tuple[bytes, bytes]] = []
    while position < len(png_bytes):
        if len(png_bytes) - position < 12:
            raise ValueError("PNG chunk is truncated")
        length = struct.unpack(">I", png_bytes[position:position + 4])[0]
        end = position + 12 + length
        if end > len(png_bytes):
            raise ValueError("PNG chunk payload is truncated")
        kind = png_bytes[position + 4:position + 8]
        payload = png_bytes[position + 8:position + 8 + length]
        expected_crc = struct.unpack(">I", png_bytes[position + 8 + length:end])[0]
        if zlib.crc32(kind + payload) & 0xFFFFFFFF != expected_crc:
            raise ValueError(f"PNG {kind!r} CRC is invalid")
        chunks.append((kind, payload))
        position = end
    if [kind for kind, _ in chunks] != [b"IHDR", b"IDAT", b"IEND"]:
        raise ValueError("PNG chunk order is not canonical")

    ihdr = chunks[0][1]
    if len(ihdr) != 13:
        raise ValueError("PNG IHDR length is invalid")
    width, height, bit_depth, color_type, compression, filtering, interlace = struct.unpack(">IIBBBBB", ihdr)
    if (bit_depth, color_type, compression, filtering, interlace) != (8, 2, 0, 0, 0):
        raise ValueError("PNG IHDR format is not canonical RGB8")
    try:
        scanlines = zlib.decompress(chunks[1][1])
    except zlib.error as error:
        raise ValueError(f"PNG IDAT cannot be decompressed: {error}") from error
    stride = width * 3
    expected_length = height * (stride + 1)
    if len(scanlines) != expected_length:
        raise ValueError(f"PNG scanlines have {len(scanlines)} bytes; expected {expected_length}")

    provinces: list[int] = []
    commanderies: list[int] = []
    for row in range(height):
        offset = row * (stride + 1)
        if scanlines[offset] != 0:
            raise ValueError(f"PNG row {row} uses a nonzero filter")
        offset += 1
        for _ in range(width):
            code = (scanlines[offset] << 16) | (scanlines[offset + 1] << 8) | scanlines[offset + 2]
            if code == 0:
                province, commandery = -1, -1
            else:
                province = (code & PROVINCE_LIMIT) - 1
                commandery = (code >> PROVINCE_BITS) - 1
                if province < 0 or commandery < 0 or commandery >= COMMANDERY_LIMIT:
                    raise ValueError("PNG scanline contains an invalid hierarchy identity")
            provinces.append(province)
            commanderies.append(commandery)
            offset += 3
    return width, height, provinces, commanderies


def _verify_png_round_trip(
    png_bytes: bytes,
    provinces: list[int],
    commanderies: list[int],
    cols: int,
    rows: int,
) -> None:
    try:
        width, height, decoded_provinces, decoded_commanderies = _decode_emitted_png(png_bytes)
    except ValueError as error:
        raise ValueError(f"PNG round-trip validation failed: {error}") from error
    if (width, height) != (cols, rows):
        raise ValueError(f"PNG round-trip dimensions {(width, height)} do not match {(cols, rows)}")
    if decoded_provinces != provinces:
        raise ValueError("PNG round-trip province grid does not match owner")
    if decoded_commanderies != commanderies:
        raise ValueError("PNG round-trip commandery grid does not match seatOwner")


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


def _validate_entity_indices(provinces: list[int], commanderies: list[int], cities: object, juns: object) -> None:
    if not isinstance(cities, list):
        raise ValueError("cities must be a list")
    if not isinstance(juns, list):
        raise ValueError("juns must be a list")
    for cell, province in enumerate(provinces):
        if province >= len(cities):
            raise ValueError(f"province index {province} at cell {cell} is outside cities")
    for cell, commandery in enumerate(commanderies):
        if commandery >= len(juns):
            raise ValueError(f"commandery index {commandery} at cell {cell} is outside juns")


def _render_assets(source_bytes: bytes, map_data: dict) -> tuple[bytes, bytes, int, int, int, int, list[int], list[int]]:
    meta = map_data.get("_meta")
    if not isinstance(meta, dict):
        raise ValueError("map data is missing _meta")
    cols, rows = meta.get("cols"), meta.get("rows")
    provinces, commanderies, pixels = build_from_runs(map_data.get("owner"), map_data.get("seatOwner"), cols, rows)
    _validate_entity_indices(provinces, commanderies, map_data.get("cities"), map_data.get("juns"))
    terrain_water_covered, terrain_land_uncovered = _terrain_mismatches(map_data.get("terrain"), provinces, cols * rows)
    png_bytes = _make_png(cols, rows, pixels)
    _verify_png_round_trip(png_bytes, provinces, commanderies, cols, rows)
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
    return (
        png_bytes,
        metadata_bytes,
        cols,
        rows,
        metadata["counts"]["provinceIdentities"],
        metadata["counts"]["commanderyIdentities"],
        provinces,
        commanderies,
    )


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
    png_bytes, metadata_bytes, width, height, province_count, commandery_count, _, _ = _render_assets(source_bytes, map_data)
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
    png_bytes, metadata_bytes, cols, rows, _, _, provinces, commanderies = _render_assets(source_bytes, map_data)
    png_path = output_dir / f"{map_code}-provinces.png"
    metadata_path = output_dir / f"{map_code}-provinces.meta.json"
    if not png_path.is_file() or not metadata_path.is_file():
        return False
    emitted_png = png_path.read_bytes()
    if emitted_png != png_bytes or metadata_path.read_bytes() != metadata_bytes:
        return False
    try:
        _verify_png_round_trip(emitted_png, provinces, commanderies, cols, rows)
    except ValueError:
        return False
    return True


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
