import json
import struct
import tempfile
import unittest
import zlib
from dataclasses import dataclass
from pathlib import Path

from tools.map.build_province_map import (
    build_assets,
    build_from_runs,
    check_assets,
    decode_identity,
    encode_identity,
)


valid_fixture = {
    "_meta": {"cols": 3, "rows": 2},
    "terrain": ["011", "110"],
    "owner": [[-1, 1], [0, 1], [1, 1], [2, 2], [-1, 1]],
    "seatOwner": [[-1, 1], [0, 2], [1, 2], [-1, 1]],
    "cities": [{}, {}, {}],
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
            identity = decode_identity(tuple(raw[offset:offset + 3]))
            province, commandery = identity if identity is not None else (-1, -1)
            provinces.append(province)
            commanderies.append(commandery)
            offset += 3
    return width, height, provinces, commanderies


def build_fixture(data: dict) -> FixtureResult:
    temporary_directory = tempfile.TemporaryDirectory()
    root = Path(temporary_directory.name)
    input_path = root / "han-tiles.json"
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
    def test_identity_codec_uses_documented_bit_layout(self):
        self.assertEqual(encode_identity(-1, -1), (0, 0, 0))
        self.assertEqual(encode_identity(0, 0), (0x00, 0x10, 0x01))
        self.assertEqual(decode_identity((0x00, 0x10, 0x01)), (0, 0))
        self.assertEqual(decode_identity((0, 0, 0)), None)

    def test_build_round_trips_both_grids_and_is_byte_deterministic(self):
        first = build_fixture(valid_fixture)
        second = build_fixture(valid_fixture)
        self.addCleanup(first.temporary_directory.cleanup)
        self.addCleanup(second.temporary_directory.cleanup)
        self.assertEqual(first.png_bytes, second.png_bytes)
        self.assertEqual(first.metadata_bytes, second.metadata_bytes)
        self.assertEqual(first.decoded_provinces, [-1, 0, 1, 2, 2, -1])
        self.assertEqual(first.decoded_commanderies, [-1, 0, 0, 1, 1, -1])

    def test_rejects_coverage_disagreement_and_index_overflow(self):
        with self.assertRaisesRegex(ValueError, "coverage disagreement"):
            build_from_runs(owner=[[0, 1]], seat_owner=[[-1, 1]], cols=1, rows=1)
        with self.assertRaisesRegex(ValueError, "province index"):
            encode_identity(4095, 0)
        with self.assertRaisesRegex(ValueError, "commandery index"):
            encode_identity(0, 255)

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


if __name__ == "__main__":
    unittest.main()
