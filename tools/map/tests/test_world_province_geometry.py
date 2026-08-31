from __future__ import annotations

import unittest

import numpy as np

from tools.map.world_province_geometry import (
    ParentRegionRecord,
    ProvinceSeed,
    build_province_geometry,
    rebalance_province_areas,
)
from tools.map.province_quality import measure_province_shapes


def seed(identifier: str, row: int, col: int, parent: str = "R-A") -> ProvinceSeed:
    return ProvinceSeed(
        id=identifier,
        display_name=identifier,
        name_ch="",
        administrative_system="EXTERNAL_POLITY",
        kind="SETTLEMENT",
        parent_region_id=parent,
        row=row,
        col=col,
        city_index=None,
        geometry_basis="HISTORICAL_SEAT_ADAPTED",
        confidence="REVIEWED",
    )


PARENTS = (ParentRegionRecord("R-A", "A", "EXTERNAL_POLITY"),)


class WorldProvinceGeometryTest(unittest.TestCase):
    def test_balancing_guarantees_floor_and_fixed_count_for_tiny_outer_parent(self) -> None:
        terrain = np.ones((8, 20), dtype=np.uint8)
        parents = (
            ParentRegionRecord("R-A", "A", "EXTERNAL_POLITY"),
            ParentRegionRecord("R-B", "B", "EXTERNAL_POLITY"),
        )
        seeds = [seed("P-A", 0, 0, "R-A"), seed("P-B", 4, 10, "R-B")]
        parent_owner = np.ones_like(terrain, dtype=np.int32)
        parent_owner[0, 0] = 0
        initial = build_province_geometry(
            terrain,
            None,
            seeds,
            parents,
            [
                {"id": "ADM-A", "parentRegionId": "R-A", "mask": parent_owner == 0},
                {"id": "ADM-B", "parentRegionId": "R-B", "mask": parent_owner == 1},
            ],
            {},
        )

        result, balanced_parents = rebalance_province_areas(
            initial, seeds, parent_owner, total_provinces=4, minimum_area=8,
        )
        report = measure_province_shapes(result.owner, result.province_records)

        self.assertEqual(4, len(result.province_records))
        self.assertGreaterEqual(min(metric.area for metric in report.metrics), 8)
        self.assertGreaterEqual(int(np.count_nonzero(balanced_parents == 0)), 8)
        self.assertIn("BALANCE_PARENT_AREAS", [row.kind for row in result.audit.decisions])

    def test_balancing_enforces_absolute_maximum_without_splitting_provinces(self) -> None:
        terrain = np.ones((20, 20), dtype=np.uint8)
        seeds = [seed("P-A", 5, 5), seed("P-B", 15, 15)]
        parent_owner = np.zeros_like(terrain, dtype=np.int32)
        initial = build_province_geometry(
            terrain, None, seeds, PARENTS,
            [{"id": "ADM-A", "parentRegionId": "R-A", "mask": terrain > 0}], {},
        )

        result, _ = rebalance_province_areas(
            initial, seeds, parent_owner,
            total_provinces=2, minimum_area=8, maximum_area=210,
        )
        report = measure_province_shapes(result.owner, result.province_records)

        self.assertLessEqual(max(metric.area for metric in report.metrics), 210)
        self.assertLessEqual(max(metric.component_count for metric in report.metrics), 1)

    def test_historical_mask_wins_over_modern_polygon(self) -> None:
        terrain = np.ones((4, 6), dtype=np.uint8)
        historical = np.zeros_like(terrain, dtype=bool)
        historical[1:3, 1:3] = True
        modern = np.ones_like(terrain, dtype=bool)
        result = build_province_geometry(
            terrain, None, [seed("P-H", 1, 1), seed("P-M", 1, 4)], PARENTS,
            [{"id": "ADM-A", "parentRegionId": "R-A", "mask": modern}],
            {"P-H": historical},
        )
        self.assertTrue(np.all(result.owner[historical] == result.index_of("P-H")))
        self.assertIn("PRESERVE_HISTORICAL", [row.kind for row in result.audit.decisions])

    def test_two_seeds_inside_one_admin_polygon_split_without_leaking(self) -> None:
        terrain = np.zeros((5, 9), dtype=np.uint8)
        terrain[:, :7] = 1
        mask = terrain > 0
        result = build_province_geometry(
            terrain, None, [seed("P-A", 2, 1), seed("P-B", 2, 5)], PARENTS,
            [{"id": "ADM-A", "parentRegionId": "R-A", "mask": mask}], {},
        )
        self.assertEqual(
            set(result.owner[:, :7].ravel()),
            {result.index_of("P-A"), result.index_of("P-B")},
        )
        self.assertTrue(np.all(result.owner[:, 7:] == -1))
        self.assertIn("SPLIT_MULTI_SEED", [row.kind for row in result.audit.decisions])

    def test_seedless_polygon_merges_with_same_parent_neighbor(self) -> None:
        terrain = np.ones((3, 6), dtype=np.uint8)
        left = np.zeros_like(terrain, dtype=bool)
        middle = np.zeros_like(terrain, dtype=bool)
        left[:, :3] = True
        middle[:, 3:] = True
        result = build_province_geometry(
            terrain, None, [seed("P-A", 1, 1)], PARENTS,
            [
                {"id": "ADM-A", "parentRegionId": "R-A", "mask": left},
                {"id": "ADM-B", "parentRegionId": "R-A", "mask": middle},
            ], {},
        )
        self.assertTrue(np.all(result.owner == result.index_of("P-A")))
        self.assertIn("MERGE_SEEDLESS", [row.kind for row in result.audit.decisions])

    def test_seedless_isolated_polygon_becomes_direct_territory(self) -> None:
        terrain = np.ones((2, 3), dtype=np.uint8)
        result = build_province_geometry(
            terrain, None, [], PARENTS,
            [{"id": "ADM-X", "parentRegionId": "R-A", "mask": terrain > 0}], {},
        )
        self.assertEqual(len(result.province_records), 1)
        self.assertEqual(result.province_records[0].kind, "DIRECT_TERRITORY")
        self.assertTrue(np.all(result.owner == 0))

    def test_tiny_direct_sliver_merges_after_later_neighbor_is_painted(self) -> None:
        terrain = np.ones((3, 6), dtype=np.uint8)
        left = np.zeros_like(terrain, dtype=bool)
        right = np.zeros_like(terrain, dtype=bool)
        left[:, :3] = True
        right[:, 3:] = True
        result = build_province_geometry(
            terrain, None, [seed("P-A", 1, 4)], PARENTS,
            [
                {"id": "ADM-A", "parentRegionId": "R-A", "mask": left},
                {"id": "ADM-B", "parentRegionId": "R-A", "mask": right},
            ], {},
        )
        self.assertEqual([record.id for record in result.province_records], ["P-A"])
        self.assertTrue(np.all(result.owner == 0))
        self.assertIn("MERGE_TINY_DIRECT", [row.kind for row in result.audit.decisions])

    def test_order_does_not_change_ids_or_owner(self) -> None:
        terrain = np.ones((3, 5), dtype=np.uint8)
        feature = {"id": "ADM-A", "parentRegionId": "R-A", "mask": terrain > 0}
        first = build_province_geometry(
            terrain, None, [seed("P-B", 1, 4), seed("P-A", 1, 0)], PARENTS,
            [feature], {},
        )
        second = build_province_geometry(
            terrain, None, [seed("P-A", 1, 0), seed("P-B", 1, 4)], PARENTS,
            [feature], {},
        )
        self.assertEqual(first.province_records, second.province_records)
        np.testing.assert_array_equal(first.owner, second.owner)


if __name__ == "__main__":
    unittest.main()
