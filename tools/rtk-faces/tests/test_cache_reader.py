import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import build_rtk14_faces as b  # noqa: E402


class TestCacheReader(unittest.TestCase):
    def test_cache_hit_returns_operator_supplied_bytes(self):
        with tempfile.TemporaryDirectory() as directory:
            cache = Path(directory)
            url = "http://x/a.png"
            (cache / (b.sha256_hex(url.encode()) + ".bin")).write_bytes(b"CACHED")

            data, cached = b.CacheReader(cache).fetch(url)

            self.assertEqual(data, b"CACHED")
            self.assertTrue(cached)

    def test_attachment_cache_miss_stays_fail(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(b.FetchError) as raised:
                b.CacheReader(Path(directory)).fetch(
                    "https://cdn.wikiwiki.jp/to/w/sangokushi14/ENC/::attach/f.jpg"
                )

            self.assertEqual(raised.exception.reason, "cache_miss")

    def test_oversized_cache_entry_stays_fail(self):
        with tempfile.TemporaryDirectory() as directory:
            url = "https://cdn.wikiwiki.jp/to/w/sangokushi14/ENC/::attach/f.jpg"
            cache = Path(directory)
            (cache / (b.sha256_hex(url.encode()) + ".bin")).write_bytes(b"12345")

            with mock.patch.object(b, "MAX_SOURCE_BYTES", 4):
                with self.assertRaises(b.FetchError) as raised:
                    b.CacheReader(cache).fetch(url)

            self.assertEqual(raised.exception.reason, "cache_too_large")

    def test_attachment_cache_hit_is_reused(self):
        with tempfile.TemporaryDirectory() as directory:
            url = "https://cdn.wikiwiki.jp/to/w/sangokushi14/ENC/::attach/f.jpg"
            cache = Path(directory)
            cache_file = cache / (b.sha256_hex(url.encode()) + ".bin")
            cache_file.write_bytes(b"operator cache")

            data, cached = b.CacheReader(cache).fetch(url)

            self.assertEqual(data, b"operator cache")
            self.assertTrue(cached)


if __name__ == "__main__":
    unittest.main()
