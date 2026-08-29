from __future__ import annotations

import hashlib
import io
import json
import tempfile
import unittest
import zipfile
from dataclasses import replace
from pathlib import Path
from unittest import mock

from tools.map.fetch_modern_admin_boundaries import (
    fetch_boundary_archive,
    load_boundary_recipe,
)


ROOT = Path(__file__).resolve().parents[3]
RECIPE = ROOT / "data/curated/map/modern-admin-boundaries-v1.json"


def _archive_bytes() -> bytes:
    payload = json.dumps(
        {"type": "FeatureCollection", "features": []}, separators=(",", ":")
    ).encode("utf-8")
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w", zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("geoBoundariesCGAZ_ADM2.geojson", payload)
    return buffer.getvalue()


class FetchModernAdminBoundariesTest(unittest.TestCase):
    def test_recipe_pins_release_license_and_digest(self) -> None:
        recipe = load_boundary_recipe(RECIPE)
        self.assertEqual(recipe.release, "6.0.0")
        self.assertEqual(recipe.license, "CC BY 4.0")
        self.assertEqual(recipe.level, "ADM2")
        self.assertEqual(len(recipe.sha256), 64)

    def test_fetch_extracts_and_validates_feature_collection(self) -> None:
        blob = _archive_bytes()
        recipe = replace(
            load_boundary_recipe(RECIPE),
            url="https://example.invalid/adm2.zip",
            sha256=hashlib.sha256(blob).hexdigest(),
        )
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "adm2.geojson"
            with mock.patch(
                "tools.map.fetch_modern_admin_boundaries.urllib.request.urlopen",
                return_value=io.BytesIO(blob),
            ):
                artifact = fetch_boundary_archive(recipe, output)
            self.assertEqual(artifact.output, output)
            self.assertEqual(artifact.feature_count, 0)
            self.assertEqual(json.loads(output.read_text())["type"], "FeatureCollection")

    def test_fetch_rejects_digest_drift(self) -> None:
        blob = _archive_bytes()
        recipe = replace(
            load_boundary_recipe(RECIPE),
            url="https://example.invalid/adm2.zip",
            sha256="0" * 64,
        )
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "adm2.geojson"
            with mock.patch(
                "tools.map.fetch_modern_admin_boundaries.urllib.request.urlopen",
                return_value=io.BytesIO(blob),
            ):
                with self.assertRaisesRegex(ValueError, "SHA-256"):
                    fetch_boundary_archive(recipe, output)
            self.assertFalse(output.exists())


if __name__ == "__main__":
    unittest.main()
