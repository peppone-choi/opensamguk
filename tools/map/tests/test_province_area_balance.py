from __future__ import annotations

import unittest

import numpy as np

from tools.map.province_quality import (
    allocate_parent_province_counts,
    balanced_parent_labels,
    repair_label_connectivity,
)


class ProvinceAreaBalanceTest(unittest.TestCase):
    def test_allocates_fixed_total_to_reduce_largest_parent_load(self) -> None:
        counts = allocate_parent_province_counts(
            {"dense": 300, "outer": 3000, "island": 1},
            {"dense": 18, "outer": 3, "island": 1},
            total_provinces=30,
        )

        self.assertEqual({"dense": 18, "outer": 11, "island": 1}, counts)
        self.assertEqual(30, sum(counts.values()))

    def test_rejects_total_below_historical_seed_count(self) -> None:
        with self.assertRaisesRegex(ValueError, "below required seed count"):
            allocate_parent_province_counts(
                {"a": 10}, {"a": 2}, total_provinces=1,
            )

    def test_is_deterministic_when_parent_loads_tie(self) -> None:
        counts = allocate_parent_province_counts(
            {"a": 100, "b": 100}, {"a": 1, "b": 1}, total_provinces=3,
        )
        self.assertEqual({"a": 2, "b": 1}, counts)

    def test_balanced_labels_bound_both_area_tails(self) -> None:
        mask = np.ones((20, 40), dtype=bool)
        labels, anchors = balanced_parent_labels(mask, province_count=4)
        areas = np.bincount(labels[mask], minlength=4)

        self.assertEqual(4, len(anchors))
        self.assertGreaterEqual(int(areas.min()), 180)
        self.assertLessEqual(int(areas.max()), 220)
        self.assertTrue(all(labels[row, col] == index for index, (row, col) in enumerate(anchors)))

    def test_balanced_labels_keep_each_province_connected_on_irregular_land(self) -> None:
        mask = np.ones((24, 36), dtype=bool)
        mask[5:19, 14:22] = False
        mask[11:13, 14:22] = True

        labels, _ = balanced_parent_labels(mask, province_count=8)

        for province in range(8):
            cells = {tuple(cell) for cell in np.argwhere(labels == province)}
            pending = [next(iter(cells))]
            seen = {pending[0]}
            while pending:
                row, col = pending.pop()
                for neighbor in ((row - 1, col), (row + 1, col), (row, col - 1), (row, col + 1)):
                    if neighbor in cells and neighbor not in seen:
                        seen.add(neighbor)
                        pending.append(neighbor)
            self.assertEqual(cells, seen, f"province {province} was disconnected")

    def test_balanced_labels_seed_each_disconnected_land_component(self) -> None:
        mask = np.zeros((12, 30), dtype=bool)
        mask[1:11, 1:12] = True
        mask[3:9, 20:29] = True

        labels, _ = balanced_parent_labels(mask, province_count=4)

        for province in range(4):
            cells = np.argwhere(labels == province)
            self.assertTrue(np.all(cells[:, 1] < 12) or np.all(cells[:, 1] >= 20))

    def test_connectivity_repair_moves_detached_piece_across_shared_edge(self) -> None:
        labels = np.array([
            [0, 0, 1, 1],
            [0, 1, 1, 0],
        ], dtype=np.int32)

        repaired = repair_label_connectivity(labels, labels >= 0, 2)

        self.assertEqual(1, repaired[1, 3])


if __name__ == "__main__":
    unittest.main()
