from __future__ import annotations

import json
import unittest
from pathlib import Path

import numpy as np

from tools.map.rebalance_han_tiles import adapt_historical_city_seeds, jun_seat_coordinates
from tools.map.province_quality import balanced_parent_labels


class RebalanceHanTilesTest(unittest.TestCase):
    def test_commandery_adjacency_uses_relocated_seat_city_coordinates(self):
        document = {
            "juns": [
                {"seat": 1, "row": 90, "col": 91},
                {"seat": 0, "row": 80, "col": 81},
            ],
        }
        relocated_cities = [
            {"row": 10, "col": 11},
            {"row": 20, "col": 21},
        ]

        self.assertEqual([(21, 20), (11, 10)], jun_seat_coordinates(document, relocated_cities))

    def test_tracked_adjacency_counts_match_metadata(self):
        root = Path(__file__).resolve().parents[3]
        document = json.loads(
            (root / "data/map/han-tiles.json").read_text(encoding="utf-8")
        )

        self.assertEqual(
            len(document["adjacency"]["county"]),
            document["_meta"]["counts"]["adjCounty"],
        )
        self.assertEqual(
            len(document["adjacency"]["commandery"]),
            document["_meta"]["counts"]["adjCommandery"],
        )

    def test_historical_city_outside_its_parent_moves_to_nearest_free_parent_cell(self):
        parent_owner = np.array([
            [0, 0, 1, 1],
            [0, 0, 1, 1],
        ], dtype=np.int32)
        cities = [
            {"row": 0, "col": 2, "name": "A"},
            {"row": 1, "col": 3, "name": "B"},
        ]
        records = [
            {"id": "PA", "parentRegionId": "PARENT-0000", "cityIndex": 0, "kind": "COUNTY"},
            {"id": "PB", "parentRegionId": "PARENT-0001", "cityIndex": 1, "kind": "COUNTY"},
        ]

        adapted = adapt_historical_city_seeds(cities, records, parent_owner)

        self.assertEqual((0, 1), (adapted[0]["row"], adapted[0]["col"]))
        self.assertEqual((1, 3), (adapted[1]["row"], adapted[1]["col"]))
        self.assertEqual((0, 2), (cities[0]["row"], cities[0]["col"]))

    def test_crowded_historical_cities_are_adapted_to_support_minimum_footprints(self):
        parent_owner = np.zeros((10, 30), dtype=np.int32)
        cities = [
            {"row": 5, "col": 5, "name": "A"},
            {"row": 5, "col": 6, "name": "B"},
            {"row": 5, "col": 7, "name": "C"},
        ]
        records = [
            {"id": f"P{index}", "parentRegionId": "PARENT-0000", "cityIndex": index, "kind": "COUNTY"}
            for index in range(3)
        ]

        adapted = adapt_historical_city_seeds(cities, records, parent_owner)
        anchors = tuple((row["row"], row["col"]) for row in adapted)

        self.assertNotEqual(((5, 5), (5, 6), (5, 7)), anchors)
        self.assertGreaterEqual(
            min(
                abs(left[0] - right[0]) + abs(left[1] - right[1])
                for index, left in enumerate(anchors)
                for right in anchors[index + 1:]
            ),
            4,
        )
        labels, _ = balanced_parent_labels(
            parent_owner == 0,
            3,
            fixed_anchors=anchors,
            minimum_anchor_area=8,
        )
        self.assertGreaterEqual(int(np.bincount(labels.ravel()).min()), 8)

    def test_historical_cities_respect_small_component_anchor_capacity(self):
        parent_owner = np.full((12, 24), -1, dtype=np.int32)
        parent_owner[1:11, 1:21] = 0
        parent_owner[1:4, 21:24] = 0
        cities = [
            {"row": 1, "col": 21 + index, "name": chr(ord("A") + index)}
            for index in range(3)
        ]
        records = [
            {"id": f"P{index}", "parentRegionId": "PARENT-0000", "cityIndex": index, "kind": "COUNTY"}
            for index in range(3)
        ]

        adapted = adapt_historical_city_seeds(
            cities, records, parent_owner, minimum_area=8,
        )

        island_count = sum(city["col"] >= 21 for city in adapted)
        self.assertLessEqual(island_count, 1)


if __name__ == "__main__":
    unittest.main()
