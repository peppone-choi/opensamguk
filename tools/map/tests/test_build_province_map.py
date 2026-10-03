import hashlib
import json
import struct
import tempfile
import unittest
import zlib
from dataclasses import dataclass
from pathlib import Path
from unittest.mock import patch

from tools.map.build_province_map import (
    build_assets,
    build_from_runs,
    check_assets,
    decode_identity,
    encode_identity,
    _terrain_mismatches,
)


valid_fixture = {
    "_meta": {"cols": 3, "rows": 2},
    "terrain": ["011", "110"],
    "owner": [[-1, 1], [0, 1], [1, 1], [2, 2], [-1, 1]],
    "seatOwner": [[-1, 1], [0, 2], [1, 2], [-1, 1]],
    "cities": [{}, {}, {}],
    "juns": [{}, {}],
}

generic_fixture = {
    "_meta": {"cols": 3, "rows": 2},
    "terrain": ["011", "110"],
    "owner": [[-1, 1], [0, 1], [1, 1], [2, 2], [-1, 1]],
    "parentOwner": [[-1, 1], [0, 2], [1, 2], [-1, 1]],
    "provinceRecords": [{}, {}, {}],
    "parentRegions": [{}, {}],
    "cities": [{}, {}],
    "juns": [{}, {}],
}


@dataclass
class FixtureResult:
    input_path: Path
    output_dir: Path
    png_path: Path
    png_bytes: bytes
    metadata_bytes: bytes
    decoded_provinces: list[int]
    decoded_commanderies: list[int]
    temporary_directory: tempfile.TemporaryDirectory


def decode_png_identities(png: bytes) -> tuple[int, int, list[int], list[int]]:
    assert png[:8] == b"\x89PNG\r\n\x1a\n"
    position, chunks = 8, []
    while position < len(png):
        size = struct.unpack(">I", png[position:position + 4])[0]
        kind = png[position + 4:position + 8]
        payload = png[position + 8:position + 8 + size]
        chunks.append((kind, payload))
        position += 12 + size
    ihdr = next(payload for kind, payload in chunks if kind == b"IHDR")
    width, height, depth, color_type, *_ = struct.unpack(">IIBBBBB", ihdr)
    assert (depth, color_type) == (8, 2)
    raw = zlib.decompress(b"".join(payload for kind, payload in chunks if kind == b"IDAT"))
    provinces, commanderies, offset = [], [], 0
    for _ in range(height):
        assert raw[offset] == 0
        offset += 1
        for _ in range(width):
            code = (raw[offset] << 16) | (raw[offset + 1] << 8) | raw[offset + 2]
            province = (code & 0x0FFF) - 1 if code else -1
            commandery = (code >> 12) - 1 if code else -1
            provinces.append(province)
            commanderies.append(commandery)
            offset += 3
    return width, height, provinces, commanderies


def recompress_png(png: bytes, level: int) -> bytes:
    idat_at = png.index(b"IDAT") - 4
    idat_length = struct.unpack(">I", png[idat_at:idat_at + 4])[0]
    compressed = png[idat_at + 8:idat_at + 8 + idat_length]
    replacement = zlib.compress(zlib.decompress(compressed), level=level)
    chunk = b"IDAT" + replacement
    variant = png[:idat_at] + struct.pack(">I", len(replacement)) + chunk
    variant += struct.pack(">I", zlib.crc32(chunk) & 0xFFFFFFFF)
    return variant + png[idat_at + 12 + idat_length:]


def build_fixture(data: dict) -> FixtureResult:
    temporary_directory = tempfile.TemporaryDirectory()
    root = Path(temporary_directory.name)
    input_path = root / "province-tiles.json"
    output_dir = root / "generated"
    input_path.write_text(json.dumps(data, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
    try:
        build_assets(input_path, output_dir, "han")
    except Exception:
        temporary_directory.cleanup()
        raise
    png_path = output_dir / "han-provinces.png"
    png_bytes = png_path.read_bytes()
    _, _, provinces, commanderies = decode_png_identities(png_bytes)
    return FixtureResult(
        input_path=input_path,
        output_dir=output_dir,
        png_path=png_path,
        png_bytes=png_bytes,
        metadata_bytes=(output_dir / "han-provinces.meta.json").read_bytes(),
        decoded_provinces=provinces,
        decoded_commanderies=commanderies,
        temporary_directory=temporary_directory,
    )


class ProvinceMapGeneratorTest(unittest.TestCase):
    def test_lakes_are_excluded_from_required_political_coverage(self):
        self.assertEqual(_terrain_mismatches(["0413"], [-1, -1, 0, 0], 4), (0, 0))

    def test_out_of_scope_cells_are_excluded_from_political_coverage(self):
        self.assertEqual(_terrain_mismatches(["091"], [-1, -1, 0], 3), (0, 0))

    def test_identity_codec_uses_documented_bit_layout(self):
        self.assertEqual(encode_identity(-1, -1), (0, 0, 0))
        self.assertEqual(encode_identity(0, 0), (0x00, 0x10, 0x01))
        self.assertEqual(decode_identity((0x00, 0x10, 0x01)), (0, 0))
        self.assertEqual(decode_identity((0, 0, 0)), None)

    def test_build_round_trips_both_grids_with_deterministic_pixels(self):
        first = build_fixture(valid_fixture)
        second = build_fixture(valid_fixture)
        self.addCleanup(first.temporary_directory.cleanup)
        self.addCleanup(second.temporary_directory.cleanup)
        self.assertEqual(first.decoded_provinces, second.decoded_provinces)
        self.assertEqual(first.decoded_commanderies, second.decoded_commanderies)
        self.assertEqual(first.decoded_provinces, [-1, 0, 1, 2, 2, -1])
        self.assertEqual(first.decoded_commanderies, [-1, 0, 0, 1, 1, -1])
        self.assertEqual(json.loads(first.metadata_bytes)["pixelRowsSha256"],
                         json.loads(second.metadata_bytes)["pixelRowsSha256"])

    def test_check_accepts_a_different_deflate_stream_with_identical_pixels(self):
        result = build_fixture(valid_fixture)
        self.addCleanup(result.temporary_directory.cleanup)
        variant = recompress_png(result.png_bytes, level=1)
        self.assertNotEqual(variant, result.png_bytes)
        result.png_path.write_bytes(variant)
        metadata_path = result.output_dir / "han-provinces.meta.json"
        metadata = json.loads(result.metadata_bytes)
        metadata["pngSha256"] = hashlib.sha256(variant).hexdigest()
        metadata_path.write_text(json.dumps(metadata))
        self.assertTrue(check_assets(result.input_path, result.output_dir, "han"))

    def test_build_and_check_enforce_the_png_byte_limit(self):
        result = build_fixture(valid_fixture)
        self.addCleanup(result.temporary_directory.cleanup)
        with patch("tools.map.build_province_map.MAX_PNG_BYTES", len(result.png_bytes)):
            with self.assertRaisesRegex(ValueError, "must be below"):
                build_assets(result.input_path, result.output_dir, "han")
        oversized = recompress_png(result.png_bytes, level=0)
        self.assertGreater(len(oversized), len(result.png_bytes))
        result.png_path.write_bytes(oversized)
        metadata = json.loads(result.metadata_bytes)
        metadata["pngSha256"] = hashlib.sha256(oversized).hexdigest()
        (result.output_dir / "han-provinces.meta.json").write_text(json.dumps(metadata))
        with patch("tools.map.build_province_map.MAX_PNG_BYTES", len(oversized)):
            self.assertFalse(check_assets(result.input_path, result.output_dir, "han"))

    def test_check_rejects_changed_pixels_even_when_png_hash_is_updated(self):
        original = build_fixture(valid_fixture)
        changed = build_fixture({**valid_fixture, "owner": [[-1, 1], [1, 1], [0, 1], [2, 2], [-1, 1]]})
        self.addCleanup(original.temporary_directory.cleanup)
        self.addCleanup(changed.temporary_directory.cleanup)
        original.png_path.write_bytes(changed.png_bytes)
        metadata = json.loads(original.metadata_bytes)
        metadata["pngSha256"] = hashlib.sha256(changed.png_bytes).hexdigest()
        (original.output_dir / "han-provinces.meta.json").write_text(json.dumps(metadata))
        self.assertFalse(check_assets(original.input_path, original.output_dir, "han"))

    def test_check_rejects_nonempty_iend_even_when_pixels_and_png_hash_match(self):
        result = build_fixture(valid_fixture)
        self.addCleanup(result.temporary_directory.cleanup)
        chunk = b"IEND" + b"extra"
        malformed = result.png_bytes[:-12] + struct.pack(">I", 5) + chunk
        malformed += struct.pack(">I", zlib.crc32(chunk) & 0xFFFFFFFF)
        result.png_path.write_bytes(malformed)
        metadata = json.loads(result.metadata_bytes)
        metadata["pngSha256"] = hashlib.sha256(malformed).hexdigest()
        (result.output_dir / "han-provinces.meta.json").write_text(json.dumps(metadata))
        self.assertFalse(check_assets(result.input_path, result.output_dir, "han"))

    def test_generic_hierarchy_allows_direct_territory_without_city(self):
        result = build_fixture(generic_fixture)
        self.addCleanup(result.temporary_directory.cleanup)
        self.assertEqual(result.decoded_provinces, [-1, 0, 1, 2, 2, -1])
        metadata = json.loads(result.metadata_bytes)
        self.assertEqual(metadata["codec"]["parentRegionBits"], 8)
        self.assertNotIn("commanderyBits", metadata["codec"])

    def test_rejects_coverage_disagreement_and_index_overflow(self):
        with self.assertRaisesRegex(ValueError, "coverage disagreement"):
            build_from_runs(owner=[[0, 1]], seat_owner=[[-1, 1]], cols=1, rows=1)
        with self.assertRaisesRegex(ValueError, "province index"):
            encode_identity(4095, 0)
        with self.assertRaisesRegex(ValueError, "commandery index"):
            encode_identity(0, 255)

    def test_rejects_booleans_where_canonical_integers_are_required(self):
        for owner, seat_owner, cols, rows in [
            ([[True, 1]], [[0, 1]], 1, 1),
            ([[0, True]], [[0, 1]], 1, 1),
            ([[0, 1]], [[0, 1]], True, 1),
            ([[0, 1]], [[0, 1]], 1, False),
        ]:
            with self.subTest(owner=owner, seat_owner=seat_owner, cols=cols, rows=rows):
                with self.assertRaisesRegex(ValueError, "integers"):
                    build_from_runs(owner, seat_owner, cols, rows)
        with self.assertRaisesRegex(ValueError, "province index"):
            encode_identity(True, 0)
        with self.assertRaisesRegex(ValueError, "RGB identity"):
            decode_identity((True, 0, 0))

    def test_rejects_impractical_dimensions_and_huge_rle_before_allocation(self):
        with self.assertRaisesRegex(ValueError, "dimension"):
            build_from_runs([], [], 4097, 1)
        with self.assertRaisesRegex(ValueError, "cell count"):
            build_from_runs([], [], 3072, 3000)
        with self.assertRaisesRegex(ValueError, "exceeds"):
            build_from_runs([[0, 10**9]], [[0, 1]], 1, 1)

    def test_rejects_truncated_rle_and_invalid_decoded_commandery(self):
        with self.assertRaisesRegex(ValueError, "expected 6"):
            build_from_runs(owner=[[-1, 1]], seat_owner=[[-1, 1]], cols=3, rows=2)
        with self.assertRaisesRegex(ValueError, "commandery index"):
            decode_identity((0x10, 0x00, 0x01))

    def test_rejects_province_index_that_is_not_a_city(self):
        fixture = {**valid_fixture, "owner": [[-1, 1], [3, 1], [1, 1], [2, 2], [-1, 1]]}
        with self.assertRaisesRegex(ValueError, "cities"):
            build_fixture(fixture)

    def test_rejects_commandery_index_that_is_not_a_jun(self):
        fixture = {**valid_fixture, "seatOwner": [[-1, 1], [2, 2], [1, 2], [-1, 1]]}
        with self.assertRaisesRegex(ValueError, "juns"):
            build_fixture(fixture)

    def test_check_detects_tampered_output_and_map_code_is_safe(self):
        result = build_fixture(valid_fixture)
        self.addCleanup(result.temporary_directory.cleanup)
        result.png_path.write_bytes(result.png_bytes + b"tampered")
        self.assertFalse(check_assets(result.input_path, result.output_dir, "han"))
        with self.assertRaisesRegex(ValueError, "map code"):
            build_assets(result.input_path, result.output_dir, "../han")

    def test_build_independently_rejects_png_scanlines_that_do_not_match_source_grids(self):
        correct = build_fixture(valid_fixture)
        self.addCleanup(correct.temporary_directory.cleanup)
        changed = {
            **valid_fixture,
            "owner": [[-1, 1], [1, 1], [0, 1], [2, 2], [-1, 1]],
        }
        temporary_directory = tempfile.TemporaryDirectory()
        self.addCleanup(temporary_directory.cleanup)
        root = Path(temporary_directory.name)
        input_path = root / "province-tiles.json"
        input_path.write_text(json.dumps(changed, separators=(",", ":")), encoding="utf-8")

        with patch("tools.map.build_province_map._make_png", return_value=correct.png_bytes):
            with self.assertRaisesRegex(ValueError, "round-trip"):
                build_assets(input_path, root / "generated", "han")

    def test_real_han_asset_round_trips_every_owner_and_parent_owner_cell(self):
        source_path = Path(__file__).resolve().parents[3] / "data/map/province-tiles.json"
        source = json.loads(source_path.read_text(encoding="utf-8"))
        with tempfile.TemporaryDirectory() as temporary_directory:
            result = build_assets(source_path, Path(temporary_directory), "han")
            width, height, provinces, commanderies = decode_png_identities(result.png_bytes)

        self.assertLess(len(result.png_bytes), 16 * 1024 * 1024)
        expected_provinces = [value for value, count in source["owner"] for _ in range(count)]
        expected_commanderies = [value for value, count in source["parentOwner"] for _ in range(count)]
        self.assertEqual((width, height), (source["_meta"]["cols"], source["_meta"]["rows"]))
        self.assertEqual(provinces, expected_provinces)
        self.assertEqual(commanderies, expected_commanderies)


if __name__ == "__main__":
    unittest.main()
