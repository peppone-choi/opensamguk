import errno
import os
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import build_rtk14_faces as b  # noqa: E402


class TestCacheReader(unittest.TestCase):
    def _close_if_open(self, descriptor):
        try:
            os.close(descriptor)
        except OSError as error:
            if error.errno != errno.EBADF:
                raise

    def _assert_descriptor_closed(self, descriptor):
        with self.assertRaises(OSError) as raised:
            os.fstat(descriptor)
        self.assertEqual(raised.exception.errno, errno.EBADF)

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

    def test_directory_cache_entry_stays_fail_without_reading(self):
        with tempfile.TemporaryDirectory() as directory:
            cache = Path(directory)
            url = "https://cdn.wikiwiki.jp/to/w/sangokushi14/ENC/::attach/f.jpg"
            cache_entry = cache / (b.sha256_hex(url.encode()) + ".bin")
            cache_entry.mkdir()

            with self.assertRaises(b.FetchError) as raised:
                b.CacheReader(cache).fetch(url)

            self.assertEqual(raised.exception.reason, "cache_unsafe")

    def test_fifo_cache_entry_stays_fail_without_blocking(self):
        with tempfile.TemporaryDirectory() as directory:
            cache = Path(directory)
            url = "https://cdn.wikiwiki.jp/to/w/sangokushi14/ENC/::attach/f.jpg"
            cache_entry = cache / (b.sha256_hex(url.encode()) + ".bin")
            os.mkfifo(cache_entry)
            descriptor = os.open(cache_entry, os.O_RDWR | os.O_NONBLOCK)

            with mock.patch.object(b.os, "open", return_value=descriptor) as open_cache:
                with self.assertRaises(b.FetchError) as raised:
                    b.CacheReader(cache).fetch(url)

            flags = open_cache.call_args.args[1]
            self.assertTrue(flags & os.O_NONBLOCK)
            self.assertEqual(raised.exception.reason, "cache_unsafe")

    def test_missing_safe_open_capability_stays_fail_before_open(self):
        with tempfile.TemporaryDirectory() as directory:
            cache = Path(directory)
            url = "https://cdn.wikiwiki.jp/to/w/sangokushi14/ENC/::attach/f.jpg"
            cache_entry = cache / (b.sha256_hex(url.encode()) + ".bin")
            cache_entry.write_bytes(b"operator cache")

            for attribute in ("O_NOFOLLOW", "O_NONBLOCK"):
                with self.subTest(attribute=attribute):
                    with mock.patch.dict(b.os.__dict__, {}, clear=False):
                        del b.os.__dict__[attribute]
                        with mock.patch.object(b.os, "open") as open_cache:
                            with self.assertRaises(b.FetchError) as raised:
                                b.CacheReader(cache).fetch(url)

                            open_cache.assert_not_called()
                            self.assertEqual(raised.exception.reason, "cache_unsafe")

    def test_fdopen_error_stays_fail_and_closes_descriptor(self):
        with tempfile.TemporaryDirectory() as directory:
            cache = Path(directory)
            url = "https://cdn.wikiwiki.jp/to/w/sangokushi14/ENC/::attach/f.jpg"
            cache_entry = cache / (b.sha256_hex(url.encode()) + ".bin")
            cache_entry.write_bytes(b"operator cache")
            descriptor = os.open(cache_entry, os.O_RDONLY)
            self.addCleanup(self._close_if_open, descriptor)

            with (
                mock.patch.object(b.os, "open", return_value=descriptor),
                mock.patch.object(b.os, "fdopen", side_effect=OSError("fdopen")),
            ):
                with self.assertRaises(b.FetchError) as raised:
                    b.CacheReader(cache).fetch(url)

            self.assertEqual(raised.exception.reason, "cache_unsafe")
            self._assert_descriptor_closed(descriptor)

    def test_second_fstat_error_stays_fail_and_closes_descriptor(self):
        with tempfile.TemporaryDirectory() as directory:
            cache = Path(directory)
            url = "https://cdn.wikiwiki.jp/to/w/sangokushi14/ENC/::attach/f.jpg"
            cache_entry = cache / (b.sha256_hex(url.encode()) + ".bin")
            cache_entry.write_bytes(b"operator cache")
            descriptor = os.open(cache_entry, os.O_RDONLY)
            self.addCleanup(self._close_if_open, descriptor)
            metadata = os.stat(cache_entry)

            with (
                mock.patch.object(b.os, "open", return_value=descriptor),
                mock.patch.object(
                    b.os,
                    "fstat",
                    side_effect=(metadata, OSError("second fstat")),
                ),
            ):
                with self.assertRaises(b.FetchError) as raised:
                    b.CacheReader(cache).fetch(url)

            self.assertEqual(raised.exception.reason, "cache_unsafe")
            self._assert_descriptor_closed(descriptor)

    def test_read_error_stays_fail_and_closes_descriptor(self):
        with tempfile.TemporaryDirectory() as directory:
            cache = Path(directory)
            url = "https://cdn.wikiwiki.jp/to/w/sangokushi14/ENC/::attach/f.jpg"
            cache_entry = cache / (b.sha256_hex(url.encode()) + ".bin")
            cache_entry.write_bytes(b"operator cache")
            descriptor = os.open(cache_entry, os.O_RDONLY)
            self.addCleanup(self._close_if_open, descriptor)
            source = mock.MagicMock()
            source.__enter__.return_value = source
            source.__exit__.side_effect = lambda *_args: os.close(descriptor)
            source.fileno.return_value = descriptor
            source.read.side_effect = OSError("read")

            with (
                mock.patch.object(b.os, "open", return_value=descriptor),
                mock.patch.object(b.os, "fdopen", return_value=source),
            ):
                with self.assertRaises(b.FetchError) as raised:
                    b.CacheReader(cache).fetch(url)

            self.assertEqual(raised.exception.reason, "cache_unsafe")
            self._assert_descriptor_closed(descriptor)

    def test_descriptor_error_becomes_per_entry_report_failure(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            cache = root / "cache"
            cache.mkdir()
            url = "https://cdn.wikiwiki.jp/to/w/sangokushi14/ENC/::attach/f.jpg"
            cache_entry = cache / (b.sha256_hex(url.encode()) + ".bin")
            cache_entry.write_bytes(b"operator cache")

            with mock.patch.object(b.os, "fdopen", side_effect=OSError("fdopen")):
                entry = b.process_target(
                    b.Target(
                        name="조조",
                        page_url="https://wikiwiki.jp/sangokushi14/ENC",
                        observed_image_url=url,
                    ),
                    b.CacheReader(cache),
                    mock.Mock(),
                    root / "out",
                )

            self.assertEqual(entry["status"], "FAIL")
            self.assertEqual(entry["reason"], "cache_unsafe")
            self.assertIsNone(entry["output"])

    def test_oversized_cache_entry_stays_fail(self):
        with tempfile.TemporaryDirectory() as directory:
            url = "https://cdn.wikiwiki.jp/to/w/sangokushi14/ENC/::attach/f.jpg"
            cache = Path(directory)
            (cache / (b.sha256_hex(url.encode()) + ".bin")).write_bytes(b"12345")

            with mock.patch.object(b, "MAX_SOURCE_BYTES", 4):
                with self.assertRaises(b.FetchError) as raised:
                    b.CacheReader(cache).fetch(url)

            self.assertEqual(raised.exception.reason, "cache_too_large")

    def test_cache_entry_growing_after_fstat_stays_fail(self):
        with tempfile.TemporaryDirectory() as directory:
            url = "https://cdn.wikiwiki.jp/to/w/sangokushi14/ENC/::attach/f.jpg"
            cache = Path(directory)
            cache_entry = cache / (b.sha256_hex(url.encode()) + ".bin")
            cache_entry.write_bytes(b"12345")
            stale_metadata = mock.Mock(st_mode=os.stat(cache_entry).st_mode, st_size=4)

            with (
                mock.patch.object(b, "MAX_SOURCE_BYTES", 4),
                mock.patch.object(b.os, "fstat", return_value=stale_metadata),
            ):
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
