from __future__ import annotations

import copy
import json
import unittest
from pathlib import Path

import numpy as np

from tools.map import fold_cityless_jurisdictions as folding
from tools.map import reclassify_han_lowland_terrain as lowland

ROOT = Path(__file__).resolve().parents[3]
LEGEND = {"0": "SEA", "1": "PLAIN", "2": "MOUNTAIN", "3": "RIVER", "4": "LAKE", "5": "DESERT",
          "6": "PLATEAU", "7": "BASIN", "8": "HILL", "9": "OUT_OF_SCOPE"}
# 칸 하나가 1도인 장난감 투영: 칸 (row, col) 의 중심은 lon = col + 0.5, lat = 10 - (row + 0.5).
PROJECTION = {"cell": 1.0, "pad": 0.0, "x0": 0.0, "k": 1.0, "y1": 10.0}


def toy(terrain: list[str]) -> dict:
    rows, cols = len(terrain), len(terrain[0])
    return {"_meta": {"rows": rows, "cols": cols, "terrainLegend": LEGEND, "projection": PROJECTION},
            "terrain": terrain, "owner": [[0, rows * cols]], "seatOwner": [[0, rows * cols]],
            "parentOwner": [[0, rows * cols]]}


def decisions(units: list[dict], relief: int = 10) -> dict:
    return {"criterion": {"sourceClasses": ["MOUNTAIN", "HILL"], "maxReliefM": relief},
            "elevation": {"path": "unused", "sha256": "unused"}, "units": units}


def unit(uid: str, west: float, east: float, south: float, north: float, target: str = "BASIN",
         ceiling: int | None = None) -> dict:
    return {"id": uid, "targetClass": target, "bbox": {"west": west, "east": east, "south": south, "north": north},
            "maxElevationM": ceiling, "maxElevationBasis": None if ceiling is None else "fixture",
            "evidence": {"sources": ["fixture"], "note": "fixture"}}


class LowlandRuleTest(unittest.TestCase):
    def setUp(self):
        # 왼쪽 4열은 평탄(100 m), 오른쪽 4열은 험준(열마다 +500 m).
        self.elevation = np.array([[100, 100, 100, 100, 600, 1100, 1600, 2100]] * 6, dtype=np.int32)
        self.source = toy(["22222222", "25232222", "22822222", "22222222", "26222222", "22222222"])

    def test_only_flat_mountain_and_hill_cells_inside_the_box_change(self):
        document, result = lowland.apply_reclassification(
            self.source, decisions([unit("west", 0, 8, 4, 10)]), self.elevation)
        # 상자는 위 6행 전부. 평탄한 것은 0–2열뿐이다(3열의 3×3 창은 600 m 칸을 본다).
        self.assertEqual(["77722222", "75732222", "77722222", "77722222", "76722222", "77722222"],
                         document["terrain"])
        self.assertEqual({"MOUNTAIN": 15, "HILL": 1}, result["units"][0]["fromClasses"])
        for key in ("owner", "seatOwner", "parentOwner"):
            self.assertEqual(self.source[key], document[key])

    def test_desert_plateau_and_water_are_never_touched(self):
        document, _ = lowland.apply_reclassification(
            self.source, decisions([unit("west", 0, 8, 4, 10)]), self.elevation)
        for row, col, code in ((1, 1, "5"), (1, 3, "3"), (4, 1, "6")):
            self.assertEqual(code, document["terrain"][row][col])

    def test_sourced_elevation_ceiling_excludes_high_flat_cells(self):
        elevation = self.elevation.copy()
        elevation[:, 0] = 105
        document, result = lowland.apply_reclassification(
            self.source, decisions([unit("west", 0, 8, 4, 10, ceiling=100)]), elevation)
        self.assertTrue(all(row[0] == "2" for row in document["terrain"]))
        self.assertEqual(100, result["units"][0]["elevationM"]["max"])

    def test_first_listed_unit_owns_an_overlapping_cell(self):
        _, result = lowland.apply_reclassification(
            self.source, decisions([unit("a", 0, 2, 4, 10), unit("b", 0, 3, 4, 10, target="PLAIN")]), self.elevation)
        self.assertEqual([10, 6], [row["cellCount"] for row in result["units"]])

    def test_a_unit_that_changes_nothing_is_an_error(self):
        with self.assertRaisesRegex(ValueError, "dead ledger row"):
            lowland.apply_reclassification(self.source, decisions([unit("east", 5, 8, 4, 10)]), self.elevation)

    def test_a_unit_without_evidence_or_with_a_forbidden_class_is_rejected(self):
        bare = unit("west", 0, 8, 4, 10)
        bare["evidence"] = {"sources": [], "note": ""}
        with self.assertRaisesRegex(ValueError, "evidence"):
            lowland.apply_reclassification(self.source, decisions([bare]), self.elevation)
        with self.assertRaisesRegex(ValueError, "targetClass"):
            lowland.apply_reclassification(self.source, decisions([unit("w", 0, 8, 4, 10, target="DESERT")]),
                                           self.elevation)
        widened = decisions([unit("west", 0, 8, 4, 10)])
        widened["criterion"]["sourceClasses"].append("DESERT")
        with self.assertRaisesRegex(ValueError, "sourceClasses"):
            lowland.apply_reclassification(self.source, widened, self.elevation)

    def test_restore_reproduces_the_input_and_refuses_a_tampered_document(self):
        document, result = lowland.apply_reclassification(
            self.source, decisions([unit("west", 0, 8, 4, 10)]), self.elevation)
        ledger = {"geometry": {"stages": [{"inputDocumentSha256": lowland.digest(self.source),
                                           "outputDocumentSha256": lowland.digest(document),
                                           "inputTerrainSha256": lowland.terrain_digest(self.source),
                                           "outputTerrainSha256": lowland.terrain_digest(document), **result}]}}
        self.assertEqual(self.source, lowland.restore_document(document, ledger))
        tampered = copy.deepcopy(document)
        tampered["terrain"][0] = "1" + tampered["terrain"][0][1:]
        self.assertIsNone(lowland.stage_for(tampered, ledger))


class CommittedLowlandStageTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.document = json.loads((ROOT / "data/map/han-tiles.json").read_text(encoding="utf-8"))
        # Verify this stage alone; the Korean seat stage intentionally changes non-terrain fields later.
        from tools.map import refine_korea_places as korea
        cls.document, _ = korea.peel(cls.document)
        cls.ledger = json.loads(lowland.LEDGER.read_text(encoding="utf-8"))
        cls.before, peeled = lowland.peel(cls.document)
        assert peeled is not None

    def test_committed_tiles_are_the_reviewed_stage_output(self):
        self.assertEqual([], lowland.check(self.document, self.ledger))

    def test_check_goes_red_when_one_terrain_cell_drifts(self):
        """적색 프로브 — 게이트가 실제로 빨개지는지 본다."""
        drifted = copy.deepcopy(self.document)
        row = drifted["terrain"][234]
        drifted["terrain"][234] = row[:376] + ("1" if row[376] != "1" else "2") + row[377:]
        self.assertNotEqual([], lowland.check(drifted, self.ledger))

    def test_only_terrain_changed_and_only_between_dry_classes(self):
        for key in self.document:
            if key != "terrain":
                self.assertEqual(self.before[key], self.document[key], key)
        legend = self.document["_meta"]["terrainLegend"]
        pairs = {(legend[a], legend[b]) for old, new in zip(self.before["terrain"], self.document["terrain"])
                 for a, b in zip(old, new) if a != b}
        self.assertTrue(pairs)
        self.assertLessEqual(pairs, {(a, b) for a in ("MOUNTAIN", "HILL") for b in ("PLAIN", "BASIN")})

    def test_luoyang_is_no_longer_all_mountain(self):
        before, after = lowland.lowland_report(self.before), lowland.lowland_report(self.document)
        self.assertIn("雒阳县", {row["seat"] for row in before["zeroLowlandSeats"]})
        self.assertNotIn("雒阳县", {row["seat"] for row in after["zeroLowlandSeats"]})
        self.assertLess(after["zeroLowland"], before["zeroLowland"])

    def test_a_reordered_cities_array_still_peels(self):
        """PR #804 CI 적색의 회귀 — 앞 단계 도구는 cities[] 순서만 바뀐 문서도 받는다."""
        shuffled = copy.deepcopy(self.document)
        shuffled["cities"] = list(reversed(shuffled["cities"]))
        peeled, ledger = lowland.peel(shuffled)
        self.assertIsNotNone(ledger)
        self.assertEqual(self.before["terrain"], peeled["terrain"])
        self.assertNotEqual([], lowland.check(shuffled, self.ledger))

    def test_earlier_stage_checks_see_through_this_stage(self):
        fold_ledger = json.loads(folding.LEDGER.read_text(encoding="utf-8"))
        self.assertIsNotNone(folding.stage_for(self.document, fold_ledger))
        self.assertEqual([], folding.check(self.before, fold_ledger))
        peeled, folded = folding.peel(self.document)
        self.assertIsNotNone(folded)
        self.assertEqual(lowland.digest(self.document), lowland.digest(folding.reapply(peeled, folded)))


if __name__ == "__main__":
    unittest.main()
