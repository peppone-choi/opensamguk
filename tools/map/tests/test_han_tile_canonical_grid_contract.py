"""OPENSAM-235: canonical Han tile CLI materialization is fixed to the 768 grid."""

from __future__ import annotations

import copy
import contextlib
import io
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

ROOT = Path(__file__).resolve().parents[3]
MAP_TOOLS = ROOT / "tools" / "map"
sys.path.insert(0, str(MAP_TOOLS))

import build_terrain_grid as terrain_builder  # noqa: E402
import build_tile_grid as tile_builder  # noqa: E402
import han_place_merge_runtime as merge_runtime  # noqa: E402


CANONICAL_PROJECTION = {
    "cell": 0.04690971,
    "cols": 768,
    "k": 0.866025,
    "lat0": 30.0,
    "pad": 0.69282,
    "rows": 669,
    "x0": 80.540363,
    "y1": 45.0,
}


def places_document(projection=None, year=220):
    projection = copy.deepcopy(projection or CANONICAL_PROJECTION)
    return {
        "source": "CHGIS V6 time-pref + time-cnty point layers",
        "year": year,
        "grid": projection["cols"],
        "cols": projection["cols"],
        "rows": projection["rows"],
        "projection": projection,
        "count": 0,
        "nudged": 0,
        "places": [],
    }


def reviewed_places_document(projection=None, year=220):
    document = places_document(projection, year)
    sources = [{"id": place_id} for place_id in tile_builder.SOURCE_PLACE_IDS]
    reviewed = [
        {
            **merge_runtime.REVIEWED_CATALOG_IDENTITIES["33425"],
            "gx": 460, "gy": 242, "lon": 117.187683, "lat": 34.269634,
        },
        {
            **merge_runtime.REVIEWED_CATALOG_IDENTITIES["42777"],
            "gx": 461, "gy": 243, "lon": 117.187683, "lat": 34.269634,
        },
    ]
    document["places"] = sources + reviewed
    document["count"] = len(document["places"])
    return document


def terrain_document(projection=None, year=220):
    projection = copy.deepcopy(projection or CANONICAL_PROJECTION)
    return {
        "grid": projection["cols"],
        "cols": projection["cols"],
        "rows": projection["rows"],
        "year": year,
        "projection": projection,
    }


def complete_terrain_document(projection=None, year=220):
    document = terrain_document(projection, year)
    cols, rows = document["cols"], document["rows"]
    owner = [[0] * cols for _ in range(rows)]
    owner[243][461] = 1
    document.update(
        legend={"1": "PLAIN"},
        terrain=[[1] * cols for _ in range(rows)],
        owner=owner,
        placeIds=["33425", "42777"],
        roads=[],
        hubs=[1],
        junOf=[0, 0],
        junNames=["彭城國"],
        zhiPlaces=[1],
        seatOwner=[[0] * cols for _ in range(rows)],
        region=[[-1] * cols for _ in range(rows)],
        regionNames=[],
        adjacency={
            "county": [{"a": 0, "b": 1, "cells": 4}],
            "commandery": [],
        },
    )
    return document


class CanonicalHelpContractTest(unittest.TestCase):
    def test_places_help_names_768_as_the_canonical_default(self):
        result = subprocess.run(
            [sys.executable, str(MAP_TOOLS / "build_han_places.py"), "--help"],
            cwd=ROOT,
            text=True,
            capture_output=True,
            check=True,
        )
        self.assertIn("canonical default: 768", result.stdout)

    def test_terrain_help_names_768_as_the_canonical_default(self):
        result = subprocess.run(
            [sys.executable, str(MAP_TOOLS / "build_terrain_grid.py"), "--help"],
            cwd=ROOT,
            text=True,
            capture_output=True,
            check=True,
        )
        self.assertIn("canonical default: 768", result.stdout)


class TerrainGridCliContractTest(unittest.TestCase):
    def test_requested_grid_must_equal_places_projection_columns(self):
        noncanonical = copy.deepcopy(CANONICAL_PROJECTION)
        noncanonical.update(cols=256, rows=223, cell=0.14072913)
        with self.assertRaisesRegex(ValueError, "requested grid 768.*projection cols 256"):
            terrain_builder.validate_requested_grid(places_document(noncanonical), 768)

    def test_explicit_noncanonical_grid_is_available_for_research_fixtures(self):
        noncanonical = copy.deepcopy(CANONICAL_PROJECTION)
        noncanonical.update(cols=256, rows=223, cell=0.14072913)
        terrain_builder.validate_requested_grid(places_document(noncanonical), 256)

    def test_default_cli_rejects_an_accidental_256_places_input_before_rasterization(self):
        noncanonical = copy.deepcopy(CANONICAL_PROJECTION)
        noncanonical.update(cols=256, rows=223, cell=0.14072913)
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "han-places.json"
            path.write_text(json.dumps(places_document(noncanonical)))
            with mock.patch.object(terrain_builder, "PLACES", str(path)), mock.patch.object(
                sys, "argv", ["build_terrain_grid.py"]
            ), self.assertRaisesRegex(SystemExit, "requested grid 768.*projection cols 256"):
                terrain_builder.main()


class TileGridCanonicalCliContractTest(unittest.TestCase):
    def assert_cli_rejected(self, grid_document, places, readings, pattern):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            grid_path = root / "terrain-grid.json"
            places_path = root / "han-places.json"
            readings_path = root / "readings.json"
            grid_path.write_text(json.dumps(grid_document))
            places_path.write_text(json.dumps(places))
            if readings is not None:
                readings_path.write_text(json.dumps(readings))
            with mock.patch.multiple(
                tile_builder,
                GRID=grid_path,
                PLACES=places_path,
                READINGS=readings_path,
            ), mock.patch.object(
                tile_builder, "build", side_effect=AssertionError("build must not run")
            ), mock.patch.object(
                sys, "argv", ["build_tile_grid.py", "--check"]
            ), self.assertRaisesRegex(SystemExit, pattern):
                tile_builder.main()

    def test_exact_canonical_documents_and_readings_pass(self):
        tile_builder.validate_canonical_inputs(
            terrain_document(), places_document(), readings_exists=True
        )

    def test_256_documents_are_rejected_even_when_they_match_each_other(self):
        noncanonical = copy.deepcopy(CANONICAL_PROJECTION)
        noncanonical.update(cols=256, rows=223, cell=0.14072913)
        with self.assertRaisesRegex(ValueError, "768x669"):
            tile_builder.validate_canonical_inputs(
                terrain_document(noncanonical),
                places_document(noncanonical),
                readings_exists=True,
            )

    def test_projection_mismatch_is_rejected(self):
        places = places_document()
        places["projection"]["cell"] = 0.05
        with self.assertRaisesRegex(ValueError, "projections must match exactly"):
            tile_builder.validate_canonical_inputs(
                terrain_document(), places, readings_exists=True
            )

    def test_year_mismatch_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "year.*220"):
            tile_builder.validate_canonical_inputs(
                terrain_document(), places_document(year=221), readings_exists=True
            )

    def test_missing_readings_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "readings.json is required"):
            tile_builder.validate_canonical_inputs(
                terrain_document(), places_document(), readings_exists=False
            )

    def test_cli_rejects_invalid_inputs_before_build(self):
        noncanonical = copy.deepcopy(CANONICAL_PROJECTION)
        noncanonical.update(cols=256, rows=223, cell=0.14072913)
        self.assert_cli_rejected(
            terrain_document(noncanonical),
            places_document(noncanonical),
            {},
            "768x669",
        )

    def test_cli_rejects_missing_readings_before_build(self):
        self.assert_cli_rejected(
            terrain_document(), places_document(), None, "readings.json is required"
        )

    def test_cli_rejects_projection_mismatch_before_build(self):
        places = places_document()
        places["projection"]["cell"] = 0.05
        self.assert_cli_rejected(
            terrain_document(), places, {}, "projections must match exactly"
        )

    def test_cli_rejects_year_mismatch_before_build(self):
        self.assert_cli_rejected(
            terrain_document(), places_document(year=221), {}, "year.*220"
        )

    def test_canonical_cli_materializes_to_the_requested_output(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            grid = root / "terrain-grid.json"
            places = root / "han-places.json"
            readings = root / "readings.json"
            output = root / "han-tiles.json"
            grid.write_text(json.dumps(complete_terrain_document()))
            places.write_text(json.dumps(reviewed_places_document()))
            readings.write_text(json.dumps({"彭城國": "팽성국", "彭城縣": "팽성현"}))
            with mock.patch.multiple(
                tile_builder,
                GRID=grid,
                PLACES=places,
                READINGS=readings,
                OUT=output,
                ROOT=root,
            ), mock.patch.object(sys, "argv", ["build_tile_grid.py"]), contextlib.redirect_stdout(
                io.StringIO()
            ):
                self.assertEqual(0, tile_builder.main())
            rendered = json.loads(output.read_text())
            self.assertEqual((768, 669, 220), (
                rendered["_meta"]["cols"],
                rendered["_meta"]["rows"],
                rendered["_meta"]["year"],
            ))


if __name__ == "__main__":
    unittest.main()
