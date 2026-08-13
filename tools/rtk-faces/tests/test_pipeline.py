import io
import json
import sys
import unittest
from pathlib import Path
from unittest import mock

from PIL import Image

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import build_rtk14_faces as b  # noqa: E402

# --------------------------------------------------------------------------- #
# fakes                                                                        #
# --------------------------------------------------------------------------- #
def img_bytes(w, h):
    return json.dumps({"w": w, "h": h}).encode()


class FakeImageOps:
    def __init__(self, decodable=True):
        self.decodable = decodable

    def decode(self, data):
        if not self.decodable:
            return None
        d = json.loads(data.decode())
        return (d["w"], d["h"])

    def size(self, img):
        return img[0], img[1]

    def resize_encode(self, img, max_width, max_height):
        width, height = self.size(img)
        scale = min(1.0, max_width / width, max_height / height)
        out_width = max(1, min(max_width, int(round(width * scale))))
        out_height = max(1, min(max_height, int(round(height * scale))))
        payload = f"{width}x{height}->{out_width}x{out_height}".encode()
        return payload, out_width, out_height, "png"


class FakeFetcher:
    def __init__(self, table):
        # table: canonical_url -> bytes | Exception
        self.table = table
        self.calls = []

    def fetch(self, canonical_url):
        self.calls.append(canonical_url)
        v = self.table[canonical_url]
        if isinstance(v, Exception):
            raise v
        return v, False


class TestPillowImageOps(unittest.TestCase):
    def test_given_633x900_png_when_resized_then_full_frame_fits_148x210(self):
        source = Image.new("RGB", (633, 900), "black")
        source.paste("red", (0, 0, 120, 120))
        source.paste("green", (513, 0, 633, 120))
        source.paste("blue", (0, 780, 120, 900))
        source.paste("yellow", (513, 780, 633, 900))
        raw = io.BytesIO()
        source.save(raw, format="PNG")

        output, width, height, fmt = b.PillowImageOps().resize_encode(
            b.PillowImageOps().decode(raw.getvalue()),
            156,
            210,
        )

        with Image.open(io.BytesIO(output)) as resized:
            self.assertEqual((width, height, fmt), (148, 210, "png"))
            self.assertEqual(resized.getpixel((10, 10)), (255, 0, 0))
            self.assertEqual(resized.getpixel((width - 11, 10)), (0, 128, 0))
            self.assertEqual(resized.getpixel((10, height - 11)), (0, 0, 255))
            self.assertEqual(resized.getpixel((width - 11, height - 11)), (255, 255, 0))

    def test_png_encoding_is_deterministic_and_does_not_upscale(self):
        source = Image.new("RGB", (100, 80), "purple")
        raw = io.BytesIO()
        source.save(raw, format="PNG")
        image = b.PillowImageOps().decode(raw.getvalue())

        first, first_width, first_height, first_format = b.PillowImageOps().resize_encode(
            image,
            156,
            210,
        )
        second, second_width, second_height, second_format = b.PillowImageOps().resize_encode(
            image,
            156,
            210,
        )

        self.assertEqual((first_width, first_height, first_format), (100, 80, "png"))
        self.assertEqual((second_width, second_height, second_format), (100, 80, "png"))
        self.assertEqual(first, second)

    def test_png_transparency_is_preserved(self):
        source = Image.new("RGBA", (100, 80), (12, 34, 56, 0))
        source.putpixel((50, 40), (78, 90, 12, 255))
        raw = io.BytesIO()
        source.save(raw, format="PNG")

        output, width, height, _ = b.PillowImageOps().resize_encode(
            b.PillowImageOps().decode(raw.getvalue()),
            156,
            210,
        )

        with Image.open(io.BytesIO(output)) as resized:
            self.assertEqual((width, height, resized.mode), (100, 80, "RGBA"))
            self.assertEqual(resized.getpixel((0, 0)), (12, 34, 56, 0))

    def test_oversized_pixel_dimensions_fail_decode(self):
        source = Image.new("RGB", (100, 100), "black")
        raw = io.BytesIO()
        source.save(raw, format="PNG")

        with mock.patch.object(b, "MAX_IMAGE_PIXELS", 9_999):
            self.assertIsNone(b.PillowImageOps().decode(raw.getvalue()))


# --------------------------------------------------------------------------- #
# per-entry classification + report                                           #
# --------------------------------------------------------------------------- #
def _img_target(name, url):
    page_key = f"test-{name}"
    return b.Target(
        name,
        f"https://wikiwiki.jp/sangokushi14/{page_key}",
        url,
    )


class TestProcessTarget(unittest.TestCase):
    def setUp(self):
        import tempfile
        self._tmp = tempfile.TemporaryDirectory()
        self.out = Path(self._tmp.name)

    def tearDown(self):
        self._tmp.cleanup()

    def test_ok_records_full_image_resize_and_fingerprints(self):
        img = img_bytes(633, 900)
        f = FakeFetcher({"http://x/a.png": img})
        e = b.process_target(_img_target("a", "http://x/a.png?rev=z"), f, FakeImageOps(), self.out)
        self.assertEqual(e["status"], "OK")
        self.assertEqual(e["canonical_url"], "http://x/a.png")
        self.assertEqual(e["source_size"], {"width": 633, "height": 900})
        self.assertEqual(e["resize"], {"max_width": 156, "max_height": 210})
        self.assertEqual((e["output"]["width"], e["output"]["height"]), (148, 210))
        self.assertEqual(e["output"]["format"], "png")
        self.assertIsNotNone(e["output"]["fingerprint"])
        self.assertIsNotNone(e["source_fingerprint"])

    def test_square_source_resizes_without_content_metadata(self):
        img = img_bytes(400, 400)
        f = FakeFetcher({"http://x/a.png": img})
        e = b.process_target(_img_target("a", "http://x/a.png"), f, FakeImageOps(), self.out)
        self.assertEqual(e["status"], "OK")
        self.assertEqual((e["output"]["width"], e["output"]["height"]), (156, 156))

    def test_small_source_is_not_upscaled(self):
        img = img_bytes(100, 80)
        f = FakeFetcher({"http://x/a.png": img})
        e = b.process_target(_img_target("a", "http://x/a.png"), f, FakeImageOps(), self.out)
        self.assertEqual((e["output"]["width"], e["output"]["height"]), (100, 80))

    def test_custom_bounds_preserve_the_full_image_aspect_ratio(self):
        img = img_bytes(600, 900)
        f = FakeFetcher({"http://x/a.png": img})
        e = b.process_target(
            _img_target("a", "http://x/a.png"),
            f,
            FakeImageOps(),
            self.out,
            max_width=120,
            max_height=120,
        )
        self.assertEqual((e["output"]["width"], e["output"]["height"]), (80, 120))

    def test_fail_on_fetch_error(self):
        f = FakeFetcher({"http://x/a.png": b.FetchError("http_403")})
        e = b.process_target(_img_target("a", "http://x/a.png"), f, FakeImageOps(), self.out)
        self.assertEqual(e["status"], "FAIL")
        self.assertEqual(e["reason"], "http_403")

    def test_attachment_cache_miss_failure_stays_fail(self):
        cache = self.out / "cache"
        fetcher = b.CacheReader(cache)
        target = b.Target(
            "a",
            "https://wikiwiki.jp/sangokushi14/ENC",
            "https://cdn.wikiwiki.jp/to/w/sangokushi14/ENC/::attach/f.jpg?rev=blocked",
        )

        entry = b.process_target(target, fetcher, FakeImageOps(), self.out / "output")

        self.assertEqual(entry["status"], "FAIL")
        self.assertEqual(entry["reason"], "cache_miss")
        self.assertIsNone(entry["output"])

    def test_output_symlink_does_not_overwrite_its_target(self):
        img = img_bytes(100, 80)
        target = _img_target("a", "http://x/a.png")
        output_name = b.sha256_hex(b"http://x/a.png")[:16] + ".png"
        protected = self.out / "protected.txt"
        protected.write_bytes(b"keep")
        output_path = self.out / output_name
        output_path.symlink_to(protected)

        entry = b.process_target(
            target,
            FakeFetcher({"http://x/a.png": img}),
            FakeImageOps(),
            self.out,
        )

        self.assertEqual(entry["status"], "OK")
        self.assertEqual(protected.read_bytes(), b"keep")
        self.assertFalse(output_path.is_symlink())

    def test_fail_on_decode_error(self):
        f = FakeFetcher({"http://x/a.png": b"garbage"})
        e = b.process_target(_img_target("a", "http://x/a.png"), f, FakeImageOps(decodable=False), self.out)
        self.assertEqual(e["status"], "FAIL")
        self.assertEqual(e["reason"], "decode_failed")

class TestReport(unittest.TestCase):
    def test_deterministic_ordering_and_bytes(self):
        import tempfile
        with tempfile.TemporaryDirectory() as d:
            out = Path(d)
            imgs = {
                "http://x/b.png": img_bytes(400, 400),
                "http://x/a.png": img_bytes(400, 400),
            }
            targets = [_img_target("b", "http://x/b.png"), _img_target("a", "http://x/a.png")]
            r1 = b.build_report(targets, FakeFetcher(imgs), FakeImageOps(), out)
            r2 = b.build_report(targets, FakeFetcher(imgs), FakeImageOps(), out)
            self.assertEqual([e["name"] for e in r1["entries"]], ["a", "b"])
            self.assertEqual(b.dump_report(r1), b.dump_report(r2))
            self.assertEqual(r1["meta"]["counts"], {"OK": 2, "FAIL": 0})

    def test_duplicate_display_names_have_stable_report_order(self):
        import tempfile
        with tempfile.TemporaryDirectory() as d:
            out = Path(d)
            first = b.Target("동명", "https://wikiwiki.jp/sangokushi14/B", "http://x/b.png")
            second = b.Target("동명", "https://wikiwiki.jp/sangokushi14/A", "http://x/a.png")
            images = {
                "http://x/a.png": img_bytes(400, 400),
                "http://x/b.png": img_bytes(400, 400),
            }

            report = b.build_report([first, second], FakeFetcher(images), FakeImageOps(), out)

            self.assertEqual(
                [entry["page_url"] for entry in report["entries"]],
                ["https://wikiwiki.jp/sangokushi14/A", "https://wikiwiki.jp/sangokushi14/B"],
            )


if __name__ == "__main__":
    unittest.main()
