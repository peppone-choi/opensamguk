from __future__ import annotations

import unittest
import json
from pathlib import Path

import numpy as np

from tools.map.non_playable_regions import (
    _smooth_blocked_provinces,
    apply_non_playable_regions,
)


class ProjectionStub:
    def cell_lonlat(self, col: int, row: int) -> tuple[float, float]:
        return float(col), 22.0 - float(row)


class NonPlayableRegionsTest(unittest.TestCase):
    def test_smooths_one_province_black_spike_but_keeps_explicit_exclusion(self) -> None:
        provinces = np.full((5, 5), 2, dtype=np.int32)
        provinces[1:4, 0:2] = 0
        provinces[1:4, 2] = 1

        self.assertEqual(
            {0},
            _smooth_blocked_provinces(provinces, {0, 1}, forced=set(), protected=set()),
        )
        self.assertEqual(
            {0, 1},
            _smooth_blocked_provinces(provinces, {0, 1}, forced={1}, protected=set()),
        )

    def test_excludes_only_matching_parent_cells_south_of_boundary(self) -> None:
        terrain = np.ones((4, 3), dtype=np.int8)
        parents = np.array([
            [0, 1, 1],
            [0, 1, 1],
            [0, 1, 1],
            [0, 1, 1],
        ], dtype=np.int32)
        policy = {
            "schemaVersion": 1,
            "mapVersion": "han-world-v2",
            "exclusions": [{
                "id": "southern-islands",
                "parentRegionId": "PARENT-0001",
                "southOfLatitude": 21.0,
                "terrain": "OUT_OF_SCOPE",
                "reason": "test",
                "evidence": "test",
            }],
        }

        excluded = apply_non_playable_regions(
            terrain, parents, ["PARENT-0000", "PARENT-0001"],
            ProjectionStub(), policy, out_of_scope_value=9,
        )

        np.testing.assert_array_equal(excluded, np.array([
            [False, False, False],
            [False, False, False],
            [False, True, True],
            [False, True, True],
        ]))
        self.assertTrue(np.all(terrain[excluded] == 9))
        self.assertTrue(np.all(parents[excluded] == -1))
        repeated = apply_non_playable_regions(
            terrain, parents, ["PARENT-0000", "PARENT-0001"],
            ProjectionStub(), policy, out_of_scope_value=9,
        )
        np.testing.assert_array_equal(repeated, excluded)

    def test_rejects_unknown_parent_instead_of_silently_doing_nothing(self) -> None:
        with self.assertRaisesRegex(ValueError, "unknown parent region"):
            apply_non_playable_regions(
                np.ones((1, 1), dtype=np.int8),
                np.zeros((1, 1), dtype=np.int32),
                ["PARENT-0000"], ProjectionStub(),
                {
                    "schemaVersion": 1,
                    "mapVersion": "han-world-v2",
                    "exclusions": [{
                        "id": "bad", "parentRegionId": "PARENT-9999",
                        "southOfLatitude": 21.0, "terrain": "OUT_OF_SCOPE",
                        "reason": "test", "evidence": "test",
                    }],
                },
                out_of_scope_value=9,
            )

    def test_removes_cells_far_from_recorded_evidence_but_preserves_exempt_ryukyu(self) -> None:
        terrain = np.ones((1, 8), dtype=np.int8)
        parents = np.array([[0, 0, 0, 0, 1, 1, 1, 1]], dtype=np.int32)
        policy = {
            "schemaVersion": 1,
            "mapVersion": "han-world-v2",
            "maximumEvidenceDistanceCells": 1,
            "evidenceDistanceExemptParentRegionIds": ["PARENT-0001"],
            "evidenceDistanceBasis": "test",
            "exclusions": [],
        }

        excluded = apply_non_playable_regions(
            terrain, parents, ["PARENT-0000", "PARENT-0001"],
            ProjectionStub(), policy, out_of_scope_value=9,
            evidence_cells_by_parent={"PARENT-0000": [(0, 0)], "PARENT-0001": [(0, 4)]},
            province_owner=np.array([[0, 0, 0, 1, 2, 2, 3, 3]], dtype=np.int32),
        )

        np.testing.assert_array_equal(
            excluded,
            np.array([[False, False, False, True, False, False, False, False]]),
        )

    def test_keeps_unsupported_distance_cells_enclosed_inside_playable_land(self) -> None:
        terrain = np.ones((5, 5), dtype=np.int8)
        parents = np.ones((5, 5), dtype=np.int32)
        parents[1:4, 1:4] = 0
        policy = {
            "schemaVersion": 1,
            "mapVersion": "han-world-v2",
            "maximumEvidenceDistanceCells": 1,
            "evidenceDistanceExemptParentRegionIds": ["PARENT-0001"],
            "evidenceDistanceBasis": "test",
            "exclusions": [],
        }

        excluded = apply_non_playable_regions(
            terrain, parents, ["PARENT-0000", "PARENT-0001"],
            ProjectionStub(), policy, out_of_scope_value=9,
            evidence_cells_by_parent={"PARENT-0000": [(1, 1)], "PARENT-0001": [(0, 0)]},
            province_owner=np.arange(25, dtype=np.int32).reshape(5, 5),
        )

        self.assertFalse(np.any(excluded))

    def test_canonical_unsupported_cells_have_no_province_or_parent_identity(self) -> None:
        root = Path(__file__).resolve().parents[3]
        document = json.loads(
            (root / "data/map/han-tiles.json").read_text(encoding="utf-8")
        )
        rows, cols = document["_meta"]["rows"], document["_meta"]["cols"]

        def expand(runs: list[list[int]]) -> np.ndarray:
            values = np.empty(rows * cols, dtype=np.int32)
            offset = 0
            for value, count in runs:
                values[offset:offset + count] = value
                offset += count
            self.assertEqual(rows * cols, offset)
            return values.reshape(rows, cols)

        terrain = np.array([
            [int(value) for value in row] for row in document["terrain"]
        ], dtype=np.int8)
        owner = expand(document["owner"])
        parent_owner = expand(document["parentOwner"])
        excluded = terrain == 9

        self.assertEqual("OUT_OF_SCOPE", document["_meta"]["terrainLegend"]["9"])
        self.assertEqual(110367, int(excluded.sum()))
        self.assertTrue(np.all(owner[excluded] == -1))
        self.assertTrue(np.all(parent_owner[excluded] == -1))
        self.assertFalse(any(
            terrain[int(city["row"]), int(city["col"])] == 9
            for city in document["cities"]
        ))
        taiwan = next(city for city in document["cities"] if city["id"] == "X056")
        self.assertNotEqual(9, terrain[int(taiwan["row"]), int(taiwan["col"])])
        ryukyu_parent = next(
            index for index, row in enumerate(document["parentRegions"])
            if row["id"] == "PARENT-0149"
        )
        self.assertEqual(155, int((parent_owner == ryukyu_parent).sum()))
        vietnam_parents = {
            index for index, row in enumerate(document["parentRegions"])
            if row["id"] in {"PARENT-0102", "PARENT-0103", "PARENT-0104"}
        }
        vietnam = np.isin(parent_owner, list(vietnam_parents))
        vietnam_rows = np.flatnonzero(np.any(vietnam, axis=1))
        self.assertTrue(np.all(np.any(vietnam, axis=1)[vietnam_rows.min():vietnam_rows.max() + 1]))
        start = tuple(np.argwhere(vietnam)[0])
        pending = [start]
        seen = {start}
        while pending:
            row, col = pending.pop()
            for neighbor in ((row - 1, col), (row + 1, col), (row, col - 1), (row, col + 1)):
                next_row, next_col = neighbor
                if (0 <= next_row < rows and 0 <= next_col < cols
                        and vietnam[next_row, next_col] and neighbor not in seen):
                    seen.add(neighbor)
                    pending.append(neighbor)
        self.assertLessEqual(int(vietnam.sum()) - len(seen), 10)


if __name__ == "__main__":
    unittest.main()
