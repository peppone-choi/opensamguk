import io
import json
import sys
import tempfile
import unittest
from pathlib import Path

from PIL import Image

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import build_rtk14_faces as b  # noqa: E402


class TestManifestOnlyCli(unittest.TestCase):
    def test_manifest_is_required_before_any_network_path(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            with self.assertRaises(SystemExit) as raised:
                b.main(
                    [
                        "--source-dir",
                        str(root / "source"),
                        "--out-dir",
                        str(root / "out"),
                        "--report",
                        str(root / "report.json"),
                    ]
                )

            self.assertEqual(raised.exception.code, 2)

    def test_cached_manifest_run_is_byte_deterministic(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source_dir = root / "source"
            output_dir = root / "out"
            report_path = root / "report.json"
            manifest_path = root / "observed.tsv"
            canonical_url = (
                "https://cdn.wikiwiki.jp/to/w/sangokushi14/ENC/::attach/f.png"
            )
            manifest_path.write_text(
                "조조\thttps://wikiwiki.jp/sangokushi14/ENC\t"
                f"{canonical_url}?rev=observed\n",
                encoding="utf-8",
            )
            source = Image.new("RGB", (633, 900), "black")
            source.paste("red", (0, 0, 120, 120))
            source.paste("green", (513, 0, 633, 120))
            source.paste("blue", (0, 780, 120, 900))
            source.paste("yellow", (513, 780, 633, 900))
            payload = io.BytesIO()
            source.save(payload, format="PNG")
            cache_path = source_dir / "cache" / (
                b.sha256_hex(canonical_url.encode("utf-8")) + ".bin"
            )
            cache_path.parent.mkdir(parents=True)
            cache_path.write_bytes(payload.getvalue())
            arguments = [
                "--manifest",
                str(manifest_path),
                "--source-dir",
                str(source_dir),
                "--out-dir",
                str(output_dir),
                "--report",
                str(report_path),
            ]

            self.assertEqual(b.main(arguments), 0)
            first_report = report_path.read_bytes()
            first_outputs = {
                path.name: path.read_bytes() for path in output_dir.iterdir()
            }
            self.assertEqual(b.main(arguments), 0)

            report = json.loads(report_path.read_text(encoding="utf-8"))
            self.assertEqual(report_path.read_bytes(), first_report)
            self.assertEqual(
                {path.name: path.read_bytes() for path in output_dir.iterdir()},
                first_outputs,
            )
            self.assertEqual(report["meta"]["counts"], {"OK": 1, "FAIL": 0})
            self.assertEqual(report["meta"]["provenance"], "unverified")
            self.assertEqual(
                (
                    report["entries"][0]["output"]["width"],
                    report["entries"][0]["output"]["height"],
                ),
                (148, 210),
            )

    def test_uncached_manifest_entry_remains_fail(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest_path = root / "observed.tsv"
            report_path = root / "report.json"
            manifest_path.write_text(
                "조조\thttps://wikiwiki.jp/sangokushi14/ENC\t"
                "https://cdn.wikiwiki.jp/to/w/sangokushi14/ENC/::attach/f.png\n",
                encoding="utf-8",
            )

            self.assertEqual(
                b.main(
                    [
                        "--manifest",
                        str(manifest_path),
                        "--source-dir",
                        str(root / "source"),
                        "--out-dir",
                        str(root / "out"),
                        "--report",
                        str(report_path),
                    ]
                ),
                0,
            )

            report = json.loads(report_path.read_text(encoding="utf-8"))
            self.assertEqual(report["meta"]["counts"], {"OK": 0, "FAIL": 1})
            self.assertEqual(report["entries"][0]["status"], "FAIL")
            self.assertEqual(report["entries"][0]["reason"], "cache_miss")
            self.assertIsNone(report["entries"][0]["output"])


if __name__ == "__main__":
    unittest.main()
