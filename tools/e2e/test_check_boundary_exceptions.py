import tempfile
from pathlib import Path
import unittest

from check_boundary_exceptions import scan


class BoundaryExceptionScannerTest(unittest.TestCase):
    def test_missing_source_fails_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(FileNotFoundError):
                scan([Path(directory) / "missing.kt"])

    def test_six_forced_calls_are_reported(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "DomesticBoundary.kt"
            source.write_text("\n".join([
                "checkNotNull(topology)", "checkNotNull(edge)", "checkNotNull(edge)",
                "checkNotNull(row)", "checkNotNull(col)", "checkNotNull(province)",
            ]), encoding="utf-8")
            self.assertEqual(6, len(scan([source])))

    def test_clean_source_has_no_hits(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "DomesticBoundary.kt"
            source.write_text("return stop(countyId, reason)\n", encoding="utf-8")
            self.assertEqual([], scan([source]))


if __name__ == "__main__":
    unittest.main()
