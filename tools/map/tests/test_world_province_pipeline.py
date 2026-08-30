from __future__ import annotations

import unittest

import numpy as np

from tools.map.build_terrain_grid import (
    _assign_unowned_islands,
    _split_large_zones,
    derive_world_adjacency,
)


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

    def test_final_parent_owner_drives_commandery_adjacency_and_fords(self) -> None:
        terrain = np.ones((5, 6), dtype=np.uint8)
        terrain[:, 2] = 3
        county_owner = np.asarray([
            [0, 0, 0, 1, 1, 1],
            [0, 0, 0, 1, 1, 1],
            [0, 0, 0, 1, 1, 1],
            [0, 0, 0, 1, 1, 1],
            [0, 0, 0, 1, 1, 1],
        ], dtype=np.int16)
        final_parent_owner = np.asarray([
            [0, 0, 0, 1, 1, 1],
            [0, 0, 0, 1, 1, 1],
            [0, 0, 0, 1, 1, 1],
            [0, 0, 0, 1, 1, 1],
            [0, 0, 0, 1, 1, 1],
        ], dtype=np.int16)
        points = np.asarray([[0, 2], [5, 2]], dtype=float)

        county_edges, commandery_edges = derive_world_adjacency(
            terrain, points, county_owner, final_parent_owner,
        )

        self.assertEqual([{"a": 0, "b": 1, "cells": 5}], county_edges)
        self.assertEqual(1, len(commandery_edges))
        self.assertEqual("RIVER", commandery_edges[0]["cross"])
        self.assertIn("ford", commandery_edges[0])

    def test_final_parent_owner_keeps_a_one_edge_commandery_border(self) -> None:
        terrain = np.ones((2, 2), dtype=np.uint8)
        owner = np.asarray([[0, 0], [0, 1]], dtype=np.int16)
        parent_owner = np.asarray([[0, 0], [-1, 1]], dtype=np.int16)
        points = np.asarray([[0, 0], [1, 1]], dtype=float)

        _, commandery_edges = derive_world_adjacency(
            terrain, points, owner, parent_owner,
        )

        self.assertEqual(
            [{"a": 0, "b": 1, "cells": 1, "cross": "LAND"}],
            commandery_edges,
        )


if __name__ == "__main__":
    unittest.main()
