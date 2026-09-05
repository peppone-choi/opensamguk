"""Reviewed parent adjudications must survive full canonical regeneration.

The materializer patches the committed artifact, but the protected build
regenerates han-tiles.json from terrain-grid.json. If build_tile_grid.py did not
read the same ledger, a full regeneration would silently restore the wrong
parents — and every derived surface (parentOwner, commandery adjacency, counts)
would disagree with the reviewed hierarchy.
"""

from __future__ import annotations

import copy
import json
import tempfile
import unittest
from pathlib import Path
from unittest import mock

import numpy as np

from tools.map import build_terrain_grid as terrain_builder
from tools.map import build_tile_grid as tile_builder
from tools.map import han_tiles_contract
from tools.map import han_tiles_protected_orchestrator as orchestrator
from tools.map.tests.test_han_place_merge_runtime import TileStableIdAlignmentTest
from tools.map.world_province_geometry import (
    validate_jurisdiction_parent_adjudication_document,
)

ROOT = Path(__file__).resolve().parents[3]
LEDGER = ROOT / "data/curated/han/jurisdiction-commandery-adjudications-v1.json"
LEDGER_ROLE = "JURISDICTION_PARENT_ADJUDICATIONS"


def _ledger(rows: list[dict]) -> dict:
    return {
        "schemaVersion": 1,
        "ledgerId": "han-jurisdiction-commandery-adjudications-v1",
        "referenceYear": 220,
        "adjudications": rows,
    }


YILING_TO_XINCHENG = {
    "jurisdictionId": "45796",
    "jurisdictionNameCh": "夷陵縣",
    "fromCommanderyId": "PARENT-2",
    "fromCommanderyNameCh": "南郡",
    "toCommanderyId": "PARENT-0",
    "toCommanderyNameCh": "新城郡",
    "reviewState": "APPROVED_EXACT_PARENT",
    "evidenceRefs": ["test:fixture"],
}


class GeneratorParentAdjudicationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        TileStableIdAlignmentTest.setUpClass()
        cls.helper = TileStableIdAlignmentTest("make_documents")

    def make_spatial_documents(self) -> tuple[dict, dict, dict]:
        """Compact fixture with 宜都郡 folded into 南郡 so one parent owns two counties."""
        _, compact, grid, places_document = self.helper.make_documents()
        old_names = list(compact["junNames"])
        remap = {0: 2, **{index: index - 1 for index in range(1, len(old_names))}}
        jun_names = old_names[1:]
        hubs = list(compact["hubs"])[1:]
        jun_of = [remap[int(value)] for value in compact["junOf"]]
        parent_owner = np.vectorize(lambda value: -1 if value < 0 else remap[value])(
            np.array(grid["seatOwner"])
        )
        grid["junNames"] = jun_names
        grid["hubs"] = hubs
        grid["junOf"] = jun_of
        grid["seatOwner"] = parent_owner.tolist()
        grid["parentOwner"] = parent_owner.tolist()
        grid["adjacency"]["commandery"] = [
            {**edge, "cross": "LAND"} for edge in terrain_builder.adjacency(parent_owner)
        ]
        grid["parentRegions"] = [
            {
                "id": f"PARENT-{index}", "displayName": name, "nameCh": name,
                "administrativeSystem": "HAN_COMMANDERY",
            }
            for index, name in enumerate(jun_names)
        ]
        grid["provinceRecords"] = [
            {
                "id": place["id"], "displayName": place["nameFt"], "nameCh": place["nameFt"],
                "administrativeSystem": "HAN_COMMANDERY", "kind": "COUNTY",
                "parentRegionId": f"PARENT-{jun_of[index]}", "cityIndex": index,
                "geometryBasis": "HISTORICAL_SEAT_ADAPTED", "confidence": "REVIEWED",
            }
            for index, place in enumerate(compact["places"])
        ]
        grid["_meta"] = {"counts": {}}
        readings = {
            name: name
            for name in [place["nameFt"] for place in compact["places"]] + jun_names
        }
        return grid, places_document, readings

    def build(self, grid: dict, places_document: dict, readings: dict, ledger: dict | None) -> dict:
        with mock.patch(
            "tools.map.build_tile_grid.validate_jurisdiction_recovery_document",
            return_value=[],
        ):
            return tile_builder.build(
                grid_document=copy.deepcopy(grid),
                places_document=places_document,
                readings_document=readings,
                jurisdiction_recoveries_document={},
                jurisdiction_parent_adjudications_document=ledger,
            )

    def test_generator_reads_the_tracked_ledger_path_when_no_document_is_passed(self) -> None:
        # The protected build calls build() without a ledger argument. That path
        # must load JURISDICTION_PARENT_ADJUDICATIONS itself; passing the ledger
        # only from tests would leave a full regeneration unadjudicated.
        grid, places_document, readings = self.make_spatial_documents()
        with tempfile.TemporaryDirectory() as tmp:
            ledger_path = Path(tmp) / "ledger.json"
            ledger_path.write_text(
                json.dumps(_ledger([YILING_TO_XINCHENG]), ensure_ascii=False), encoding="utf-8"
            )
            with mock.patch.object(tile_builder, "JURISDICTION_PARENT_ADJUDICATIONS", ledger_path):
                rendered = self.build(grid, places_document, readings, None)
        jurisdictions = {row["id"]: row for row in rendered["jurisdictionRecords"]}
        commanderies = {row["id"]: row for row in rendered["commanderyRecords"]}
        self.assertEqual("PARENT-0", jurisdictions["45796"]["commanderyId"])
        self.assertEqual(["45796", "45921"], commanderies["PARENT-0"]["jurisdictionIds"])

    def test_generator_applies_reviewed_parent_adjudication_to_every_parent_surface(self) -> None:
        grid, places_document, readings = self.make_spatial_documents()
        baseline = self.build(grid, places_document, readings, _ledger([]))
        rendered = self.build(grid, places_document, readings, _ledger([YILING_TO_XINCHENG]))

        jurisdictions = {row["id"]: row for row in rendered["jurisdictionRecords"]}
        commanderies = {row["id"]: row for row in rendered["commanderyRecords"]}
        provinces = {row["id"]: row for row in rendered["provinceRecords"]}
        self.assertEqual("PARENT-0", jurisdictions["45796"]["commanderyId"])
        self.assertEqual("PARENT-0", provinces["45796"]["parentRegionId"])
        self.assertEqual(["45796", "45921"], commanderies["PARENT-0"]["jurisdictionIds"])
        self.assertEqual(["fixture-hub-0"], commanderies["PARENT-2"]["jurisdictionIds"])
        self.assertEqual("fixture-hub-0", commanderies["PARENT-2"]["seatJurisdictionId"])

        # Geometry, the legacy seat grid, and county adjacency are untouched.
        self.assertEqual(baseline["owner"], rendered["owner"])
        self.assertEqual(baseline["seatOwner"], rendered["seatOwner"])
        self.assertEqual(baseline["terrain"], rendered["terrain"])
        self.assertEqual(baseline["adjacency"]["county"], rendered["adjacency"]["county"])
        self.assertEqual(baseline["cities"], rendered["cities"])
        self.assertEqual(baseline["juns"], rendered["juns"])

        # parentOwner and the commandery graph are re-derived from the new parents.
        parent_index = {row["id"]: index for index, row in enumerate(rendered["parentRegions"])}
        owner = np.array(grid["owner"])
        parent_of_province = np.array([
            parent_index[row["parentRegionId"]] for row in rendered["provinceRecords"]
        ])
        expected_parent_owner = np.where(owner >= 0, parent_of_province[owner], -1)
        self.assertEqual(tile_builder.rle(expected_parent_owner.tolist()), rendered["parentOwner"])
        self.assertNotEqual(baseline["parentOwner"], rendered["parentOwner"])
        expected_edges = [
            {**edge, "cross": "LAND"}
            for edge in terrain_builder.adjacency(expected_parent_owner)
        ]
        self.assertEqual(expected_edges, rendered["adjacency"]["commandery"])
        self.assertNotEqual(baseline["adjacency"]["commandery"], rendered["adjacency"]["commandery"])
        self.assertEqual(len(expected_edges), rendered["_meta"]["counts"]["adjCommandery"])
        self.assertEqual(
            len(rendered["adjacency"]["county"]), rendered["_meta"]["counts"]["adjCounty"]
        )

    def test_generator_refuses_a_ledger_that_moves_a_commandery_seat(self) -> None:
        grid, places_document, readings = self.make_spatial_documents()
        seat_move = {
            **YILING_TO_XINCHENG,
            "jurisdictionId": "fixture-hub-0", "jurisdictionNameCh": "治0",
        }
        with self.assertRaisesRegex(ValueError, "seat"):
            self.build(grid, places_document, readings, _ledger([seat_move]))

    def test_generator_refuses_name_drift_between_ledger_and_hierarchy(self) -> None:
        grid, places_document, readings = self.make_spatial_documents()
        drifted = {**YILING_TO_XINCHENG, "toCommanderyNameCh": "錯郡"}
        with self.assertRaisesRegex(ValueError, "name drift"):
            self.build(grid, places_document, readings, _ledger([drifted]))

    def test_default_ledger_is_the_tracked_protected_build_input(self) -> None:
        self.assertEqual(LEDGER, tile_builder.JURISDICTION_PARENT_ADJUDICATIONS)
        self.assertEqual(
            "data/curated/han/jurisdiction-commandery-adjudications-v1.json",
            orchestrator.INPUT_RELATIVE_PATHS[LEDGER_ROLE],
        )
        self.assertIn(LEDGER_ROLE, han_tiles_contract.TRACKED_INPUT_ROLES)
        han_tiles_stage = next(
            stage for stage in han_tiles_contract._STAGES if stage["stageId"] == "HAN_TILES"
        )
        self.assertIn(LEDGER_ROLE, han_tiles_stage["inputRoles"])
        rows = validate_jurisdiction_parent_adjudication_document(
            json.loads(LEDGER.read_text(encoding="utf-8"))
        )
        self.assertEqual(6, len(rows))
        self.assertEqual(
            ("45022", "PARENT-0036", "PARENT-0035"),
            (
                rows[0]["jurisdictionId"],
                rows[0]["fromCommanderyId"],
                rows[0]["toCommanderyId"],
            ),
        )

    def test_committed_ledger_contains_the_exact_ningyang_parent_correction(self) -> None:
        rows = validate_jurisdiction_parent_adjudication_document(
            json.loads(LEDGER.read_text(encoding="utf-8"))
        )
        ningyang = [row for row in rows if row["jurisdictionId"] == "45277"]
        self.assertEqual(1, len(ningyang))
        self.assertEqual(
            {
                "jurisdictionId": "45277",
                "jurisdictionNameCh": "宁阳县",
                "fromCommanderyId": "PARENT-0028",
                "fromCommanderyNameCh": "山陽郡",
                "toCommanderyId": "PARENT-0024",
                "toCommanderyNameCh": "東平國",
                "reviewState": "APPROVED_EXACT_PARENT",
                "evidenceRefs": [
                    "shiliao:後漢書/卷111 郡國志 東平國 縣列「寧陽，故屬泰山」",
                    "https://zh.wikisource.org/wiki/後漢書/卷111#東平國",
                ],
            },
            ningyang[0],
        )


if __name__ == "__main__":
    unittest.main()
