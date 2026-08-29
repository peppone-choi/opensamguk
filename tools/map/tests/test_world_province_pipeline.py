from __future__ import annotations

import unittest

import numpy as np

from tools.map.build_terrain_grid import _assign_unowned_islands, _split_large_zones


class WorldProvincePipelineTest(unittest.TestCase):
    def test_zone_split_enforces_absolute_cell_cap(self) -> None:
        zones = np.zeros((20, 20), dtype=np.int32)
        split, provenance = _split_large_zones(zones, max_cells=60)
        counts = np.bincount(split.ravel())
        self.assertGreater(len(counts), 1)
        self.assertLessEqual(int(counts.max()), 60)
        self.assertEqual(len(provenance), len(counts))

    def test_parent_coverage_is_removed_from_water(self) -> None:
        parent = np.asarray([[3, 3], [3, -1]], dtype=np.int32)
        land = np.asarray([[False, True], [True, True]])
        places = [{"gx": 1, "gy": 0}]
        effective = _assign_unowned_islands(parent, land, places, [3])
        self.assertEqual(int(effective[0, 0]), -1)
        self.assertTrue(np.all(effective[land] == 3))


if __name__ == "__main__":
    unittest.main()
