import importlib.util
import json
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import build_rtk14_faces as b  # noqa: E402

HAS_OPENCV = (
    importlib.util.find_spec("cv2") is not None
    and importlib.util.find_spec("numpy") is not None
)


# --------------------------------------------------------------------------- #
# fakes                                                                        #
# --------------------------------------------------------------------------- #
def img_bytes(w, h):
    return json.dumps({"w": w, "h": h}).encode()


class FakeImageOps:
    """Deterministic stand-in for CvImageOps operating on img_bytes()."""

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


@unittest.skipUnless(HAS_OPENCV, "OpenCV test requirements are not installed")
class TestCvImageOps(unittest.TestCase):
    def test_full_frame_resize_preserves_all_four_corner_markers(self):
        import cv2
        import numpy as np

        source = np.zeros((900, 633, 3), dtype=np.uint8)
        source[:120, :120] = (0, 0, 255)
        source[:120, -120:] = (0, 255, 0)
        source[-120:, :120] = (255, 0, 0)
        source[-120:, -120:] = (0, 255, 255)
        encoded, source_buffer = cv2.imencode(".png", source)
        self.assertTrue(encoded)

        image_ops = b.CvImageOps()
        decoded = image_ops.decode(source_buffer.tobytes())
        output, width, height, fmt = image_ops.resize_encode(decoded, 156, 210)
        resized = image_ops.decode(output)

        self.assertEqual((width, height, fmt), (148, 210, "png"))
        self.assertEqual(tuple(resized[10, 10]), (0, 0, 255))
        self.assertEqual(tuple(resized[10, width - 11]), (0, 255, 0))
        self.assertEqual(tuple(resized[height - 11, 10]), (255, 0, 0))
        self.assertEqual(tuple(resized[height - 11, width - 11]), (0, 255, 255))


# --------------------------------------------------------------------------- #
# rate limiter                                                                 #
# --------------------------------------------------------------------------- #
class TestRateLimiter(unittest.TestCase):
    def test_floor_at_min_delay(self):
        rl = b.RateLimiter(0.1)
        self.assertEqual(rl.min_delay, b.MIN_DELAY_SECONDS)

    def test_sleeps_between_requests(self):
        t = [0.0]
        slept = []
        rl = b.RateLimiter(2.0, clock=lambda: t[0], sleeper=lambda s: slept.append(s))
        rl.wait()                 # first: no sleep
        t[0] = 0.5                # only 0.5s elapsed
        rl.wait()                 # must sleep the remaining 1.5s
        self.assertEqual(slept, [1.5])

    def test_no_sleep_when_enough_elapsed(self):
        t = [0.0]
        slept = []
        rl = b.RateLimiter(1.0, clock=lambda: t[0], sleeper=lambda s: slept.append(s))
        rl.wait()
        t[0] = 5.0
        rl.wait()
        self.assertEqual(slept, [])


# --------------------------------------------------------------------------- #
# fetch / cache / retry                                                        #
# --------------------------------------------------------------------------- #
class TestFetcher(unittest.TestCase):
    def _rl(self):
        return b.RateLimiter(1.0, clock=lambda: 0.0, sleeper=lambda s: None)

    def test_cache_hit_skips_fetch_and_ratelimit(self):
        import tempfile
        with tempfile.TemporaryDirectory() as d:
            cache = Path(d) / "cache"
            cache.mkdir()
            url = "http://x/a.png"
            (cache / (b.sha256_hex(url.encode()) + ".bin")).write_bytes(b"CACHED")
            waits = []
            rl = b.RateLimiter(1.0, clock=lambda: 0.0, sleeper=lambda s: waits.append(s))
            calls = []

            def opener(u, ua, to):
                calls.append(u)
                return b"NET"

            f = b.Fetcher(cache, rl, opener=opener)
            data, cached = f.fetch(url)
            self.assertEqual(data, b"CACHED")
            self.assertTrue(cached)
            self.assertEqual(calls, [])   # network never touched
            self.assertEqual(waits, [])   # rate limiter never invoked

    def test_writes_cache_on_success(self):
        import tempfile
        with tempfile.TemporaryDirectory() as d:
            cache = Path(d) / "cache"
            url = "http://x/a.png"
            f = b.Fetcher(cache, self._rl(), opener=lambda u, ua, to: b"BYTES")
            data, cached = f.fetch(url)
            self.assertEqual(data, b"BYTES")
            self.assertFalse(cached)
            self.assertTrue((cache / (b.sha256_hex(url.encode()) + ".bin")).exists())

    def test_retry_bounded_then_fail(self):
        import tempfile
        with tempfile.TemporaryDirectory() as d:
            attempts = []

            def opener(u, ua, to):
                attempts.append(1)
                raise b._Transient("network_error")

            f = b.Fetcher(Path(d), self._rl(), retries=2, opener=opener)
            with self.assertRaises(b.FetchError) as cm:
                f.fetch("http://x/a.png")
            self.assertEqual(cm.exception.reason, "network_error")
            self.assertEqual(len(attempts), 3)  # 1 + 2 retries, bounded

    def test_permanent_http_not_retried(self):
        import tempfile
        with tempfile.TemporaryDirectory() as d:
            attempts = []

            def opener(u, ua, to):
                attempts.append(1)
                raise b._PermanentHttp(403)

            f = b.Fetcher(Path(d), self._rl(), retries=5, opener=opener)
            with self.assertRaises(b.FetchError) as cm:
                f.fetch("http://x/a.png")
            self.assertEqual(cm.exception.reason, "http_403")
            self.assertEqual(len(attempts), 1)  # 4xx = terminal, no retry


# --------------------------------------------------------------------------- #
# per-entry classification + report                                           #
# --------------------------------------------------------------------------- #
def _img_target(name, url):
    return b.Target(name, url, "image", None)


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

    def test_fail_on_decode_error(self):
        f = FakeFetcher({"http://x/a.png": b"garbage"})
        e = b.process_target(_img_target("a", "http://x/a.png"), f, FakeImageOps(decodable=False), self.out)
        self.assertEqual(e["status"], "FAIL")
        self.assertEqual(e["reason"], "decode_failed")

    def test_page_mode_discovers_portrait_then_resizes(self):
        page = b'<a href="x"><img src="https://cdn.wikiwiki.jp/to/w/sangokushi14/ENC/::attach/f.jpg?rev=9"></a>'
        img = img_bytes(400, 400)
        f = FakeFetcher({
            "https://wikiwiki.jp/sangokushi14/ENC": page,
            "https://cdn.wikiwiki.jp/to/w/sangokushi14/ENC/::attach/f.jpg": img,
        })
        t = b.Target("曹操", "https://wikiwiki.jp/sangokushi14/ENC", "page", "ENC")
        e = b.process_target(t, f, FakeImageOps(), self.out)
        self.assertEqual(e["status"], "OK")
        self.assertEqual(e["page_url"], "https://wikiwiki.jp/sangokushi14/ENC")
        self.assertEqual(e["canonical_url"],
                         "https://cdn.wikiwiki.jp/to/w/sangokushi14/ENC/::attach/f.jpg")

    def test_page_mode_no_portrait_fails_closed(self):
        page = b"<html>no portrait here</html>"
        f = FakeFetcher({"https://wikiwiki.jp/sangokushi14/ENC": page})
        t = b.Target("x", "https://wikiwiki.jp/sangokushi14/ENC", "page", "ENC")
        e = b.process_target(t, f, FakeImageOps(), self.out)
        self.assertEqual(e["status"], "FAIL")
        self.assertEqual(e["reason"], "no_portrait_url")
        self.assertEqual(e["page_url"], "https://wikiwiki.jp/sangokushi14/ENC")


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


if __name__ == "__main__":
    unittest.main()
