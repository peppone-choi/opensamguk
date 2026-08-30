"""Runtime application of reviewed stable-ID Han place merges."""

import copy
import json
from pathlib import Path
import unittest

import numpy as np

from tools.map import han_place_merge_runtime as runtime
from tools.map import build_terrain_grid as terrain_builder
from tools.map import build_tile_grid as tile_builder


ROOT = Path(__file__).resolve().parents[3]
LEDGER_PATH = ROOT / "data/curated/han/han-place-merge-adjudications-v1.json"


class RuntimeApiRedTest(unittest.TestCase):
    def test_reviewed_merge_transform_api_exists(self):
        self.assertTrue(hasattr(runtime, "apply_reviewed_merges"))
        self.assertTrue(hasattr(runtime, "load_reviewed_ledger"))
        self.assertTrue(hasattr(runtime, "validate_compacted_state"))


class ReviewedMergeTransformTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.ledger = json.loads(LEDGER_PATH.read_text(encoding="utf-8"))

    def make_state(self):
        places = []
        for row in self.ledger["adjudications"]:
            for role in ("sourcePlace", "targetPlace"):
                identity = row[role]
                index = len(places)
                places.append({
                    "id": identity["physicalPlaceId"],
                    "nameCh": identity["sourceNameCh"],
                    "nameFt": identity["sourceNameFt"],
                    "typeCh": identity["typeCh"],
                    "begYr": identity["begYr"],
                    "endYr": identity["endYr"],
                    "kind": identity["kind"],
                    "level": identity["level"],
                    "gx": index % 5,
                    "gy": index // 5,
                    "lon": 100.0 + index,
                    "lat": 20.0 + index,
                })
        for offset, jun_name in enumerate(("南郡", "漢中郡", "巴郡")):
            index = len(places)
            places.append({
                "id": f"fixture-hub-{offset}",
                "nameCh": f"治{offset}",
                "nameFt": f"治{offset}",
                "typeCh": "县",
                "begYr": 1,
                "endYr": 999,
                "kind": "COUNTY",
                "level": 5,
                "gx": index % 5,
                "gy": index // 5,
                "lon": 100.0 + index,
                "lat": 20.0 + index,
                "fixtureJun": jun_name,
            })
        places.extend([
            {
                "id": "33425", "nameCh": "彭城国", "nameFt": "彭城國",
                "typeCh": "侯国", "begYr": 88, "endYr": 323,
                "kind": "KINGDOM", "level": 6,
                "gx": 0, "gy": 3, "lon": 117.0, "lat": 34.0,
            },
            {
                "id": "42777", "nameCh": "彭城县", "nameFt": "彭城縣",
                "typeCh": "县", "begYr": -223, "endYr": 1264,
                "kind": "COUNTY", "level": 5,
                "gx": 1, "gy": 3, "lon": 117.1, "lat": 34.1,
            },
        ])
        jun_names = [
            "宜都郡", "新城郡", "巴西郡", "北平郡", "馮翊郡", "西陵郡",
            "南郡", "漢中郡", "巴郡", "右北平郡", "左馮翊", "江夏郡",
            "彭城國",
        ]
        jun_of = np.array([
            0, 6, 1, 7, 2, 8, 3, 9, 4, 10, 5, 11, 6, 7, 8,
            12, 12,
        ], dtype=np.int32)
        hubs = [0, 2, 4, 6, 8, 10, 12, 13, 14, 7, 9, 11, 16]
        owner = np.array(list(range(17)) + [0, 1, 2], dtype=np.int32).reshape(4, 5)
        seat_owner = jun_of[owner]
        return {
            "places": places,
            "owner": owner,
            "seat_owner": seat_owner,
            "jun_of": jun_of,
            "hubs": hubs,
            "jun_names": jun_names,
            "zhi_places": list(range(15)) + [16],
            "ledger": copy.deepcopy(self.ledger),
        }

    def apply(self, state=None):
        return runtime.apply_reviewed_merges(**(state or self.make_state()))

    def assert_invalid(self, mutate):
        state = self.make_state()
        mutate(state)
        with self.assertRaises((TypeError, ValueError)):
            self.apply(state)

    def test_reseat_merge_and_compaction_preserve_raw_order(self):
        result = self.apply()
        self.assertEqual(
            [
                "45796", "45921", "44558", "87458", "70741", "43503",
                "fixture-hub-0", "fixture-hub-1", "fixture-hub-2", "33425", "42777",
            ],
            result["placeIds"],
        )
        self.assertEqual(
            [
                [0, 0, 1, 1, 2],
                [2, 3, 3, 4, 4],
                [5, 5, 6, 7, 8],
                [9, 10, 0, 0, 1],
            ],
            result["owner"].tolist(),
        )
        self.assertEqual(
            ["宜都郡", "新城郡", "巴西郡", "南郡", "漢中郡", "巴郡", "右北平郡", "左馮翊", "江夏郡", "彭城國"],
            result["junNames"],
        )
        self.assertEqual([0, 1, 2, 6, 7, 8, 3, 4, 5, 10], result["hubs"])
        self.assertEqual([0, 1, 2, 6, 7, 8, 3, 4, 5, 9, 9], result["junOf"].tolist())
        self.assertEqual(11, len(result["places"]))
        self.assertEqual(10, len(set(result["hubs"])))
        self.assertTrue(runtime.validate_compacted_state(result))

        counts = np.bincount(result["seatOwner"][result["seatOwner"] >= 0], minlength=10)
        self.assertTrue(np.all(counts > 0), counts.tolist())
        for jun_index, hub in enumerate(result["hubs"]):
            place = result["places"][hub]
            self.assertEqual(jun_index, result["seatOwner"][place["gy"], place["gx"]])

    def test_exact_sources_identities_and_initial_juns_fail_closed(self):
        mutations = (
            lambda s: s["places"].__delitem__(0),
            lambda s: s["places"][0].update(nameFt="錯"),
            lambda s: s["jun_names"].__setitem__(0, "錯郡"),
            lambda s: s["places"][1].update(id=s["places"][0]["id"]),
            lambda s: s["ledger"]["adjudications"][0].update(
                targetPlace=copy.deepcopy(s["ledger"]["adjudications"][0]["sourcePlace"])
            ),
        )
        for mutate in mutations:
            with self.subTest(mutate=mutate):
                self.assert_invalid(mutate)

    def test_initial_indices_hubs_labels_and_areas_fail_closed(self):
        mutations = (
            lambda s: s["hubs"].__setitem__(1, s["hubs"][0]),
            lambda s: s["hubs"].__setitem__(0, 999),
            lambda s: s["jun_of"].__setitem__(0, 99),
            lambda s: s["owner"].__setitem__((0, 0), 99),
            lambda s: s["seat_owner"].__setitem__((0, 0), 99),
            lambda s: s["places"][2].update(gx=s["places"][0]["gx"], gy=s["places"][0]["gy"]),
        )
        for mutate in mutations:
            with self.subTest(mutate=mutate):
                self.assert_invalid(mutate)

    def test_load_ledger_is_strict_and_validated(self):
        self.assertEqual(self.ledger, runtime.load_reviewed_ledger())

    def test_pengcheng_guard_preserves_city_nonseat_and_reviewed_hub(self):
        state = {
            "places": [
                copy.deepcopy(runtime.REVIEWED_CATALOG_IDENTITIES["33425"]),
                copy.deepcopy(runtime.REVIEWED_CATALOG_IDENTITIES["42777"]),
            ],
            "placeIds": ["33425", "42777"],
            "hubs": [1],
            "junNames": ["彭城國"],
            "junOf": [0, 0],
            "zhiPlaces": [1],
        }
        self.assertTrue(runtime.validate_reviewed_catalog_policy(state))
        mutations = (
            lambda broken: broken["placeIds"].remove("33425"),
            lambda broken: broken["placeIds"].remove("42777"),
            lambda broken: broken["hubs"].__setitem__(0, 0),
            lambda broken: broken["places"][0].update(kind="COUNTY"),
            lambda broken: broken["places"][0].update(nameCh="彭城郡"),
            lambda broken: broken["places"][0].update(level=5),
            lambda broken: broken["places"][0].update(nameFt="彭城郡"),
            lambda broken: broken["places"][0].update(typeCh="国"),
            lambda broken: broken["places"][0].update(begYr=89),
            lambda broken: broken["places"][1].update(nameCh="錯"),
            lambda broken: broken["places"][1].update(kind="KINGDOM"),
            lambda broken: broken["junOf"].__setitem__(0, 1),
            lambda broken: broken["zhiPlaces"].append(0),
        )
        for mutate in mutations:
            broken = copy.deepcopy(state)
            mutate(broken)
            with self.subTest(mutate=mutate), self.assertRaises((IndexError, ValueError)):
                runtime.validate_reviewed_catalog_policy(broken)

    def test_every_compact_place_must_own_a_cell(self):
        result = self.apply()
        result["owner"][result["owner"] == 0] = 1
        with self.assertRaisesRegex(ValueError, "place.*own"):
            runtime.validate_compacted_state(result)


class FoldCandidateTierRegressionTest(unittest.TestCase):
    def test_hhs_county_exact_name_never_consumes_a_commandery_seed(self):
        places = [
            {
                "id": "32540", "nameCh": "庐陵郡", "nameFt": "廬陵郡",
                "kind": "COMMANDERY", "level": 6, "lon": 115.00, "lat": 27.00,
            },
            {
                "id": "county-luling", "nameCh": "庐陵县", "nameFt": "廬陵縣",
                "kind": "COUNTY", "level": 5, "lon": 115.03, "lat": 27.00,
            },
            {
                "id": "county-nanchang", "nameCh": "南昌县", "nameFt": "南昌縣",
                "kind": "COUNTY", "level": 5, "lon": 116.00, "lat": 28.00,
            },
        ]
        junguo = [{
            "name": "豫章郡",
            "seat": "南昌",
            "counties": [
                {"name": "廬陵", "lon": 115.001, "lat": 27.00},
                {"name": "南昌", "lon": 116.00, "lat": 28.00},
            ],
        }]
        jun_of, hubs, jun_names, _ = terrain_builder.fold_to_jun(
            places, None, junguo
        )
        self.assertEqual(1, sum(place["id"] == "32540" for place in places))
        luling_jun = jun_names.index("廬陵郡")
        self.assertEqual(luling_jun, int(jun_of[0]))
        self.assertEqual(luling_jun, int(jun_of[1]))
        self.assertIn(hubs[luling_jun], (0, 1))


class TileStableIdAlignmentTest(ReviewedMergeTransformTest):
    def make_grid(self, state):
        rows, cols = state["owner"].shape
        county = terrain_builder.adjacency(state["owner"])
        commandery = [
            {**edge, "cross": "LAND"}
            for edge in terrain_builder.adjacency(state["seatOwner"])
        ]
        return {
            "grid": cols,
            "cols": cols,
            "rows": rows,
            "year": 220,
            "projection": {"cols": cols, "rows": rows},
            "legend": {"1": "PLAIN"},
            "terrain": [[1] * cols for _ in range(rows)],
            "owner": state["owner"].tolist(),
            "region": [[-1] * cols for _ in range(rows)],
            "regionNames": [],
            "placeIds": list(state["placeIds"]),
            "hubs": list(state["hubs"]),
            "junOf": state["junOf"].tolist(),
            "junNames": list(state["junNames"]),
            "zhiPlaces": list(state["zhiPlaces"]),
            "seatOwner": state["seatOwner"].tolist(),
            "adjacency": {
                "county": county,
                "commandery": commandery,
            },
        }

    def make_documents(self):
        raw = self.make_state()
        compact = self.apply(raw)
        grid = self.make_grid(compact)
        places_document = {"places": raw["places"]}
        return raw, compact, grid, places_document

    def test_grid_place_ids_resolve_compact_places_in_reviewed_order(self):
        _, compact, grid, places_document = self.make_documents()
        resolved = tile_builder.resolve_compact_places(grid, places_document)
        self.assertEqual(compact["placeIds"], [place["id"] for place in resolved])
        self.assertTrue(tile_builder.validate_compact_grid(grid, resolved))

    def test_missing_duplicate_unknown_source_and_order_drift_fail_closed(self):
        mutations = (
            lambda g: g["placeIds"].pop(),
            lambda g: g["placeIds"].__setitem__(1, g["placeIds"][0]),
            lambda g: g["placeIds"].__setitem__(0, "unknown"),
            lambda g: g["placeIds"].__setitem__(0, "34539"),
            lambda g: g["placeIds"].reverse(),
        )
        for mutate in mutations:
            raw, _, grid, places_document = self.make_documents()
            mutate(grid)
            with self.subTest(mutate=mutate), self.assertRaises(ValueError):
                tile_builder.resolve_compact_places(grid, places_document)

    def test_empty_raw_and_compact_catalogs_fail_closed(self):
        with self.assertRaisesRegex(ValueError, "reviewed source IDs"):
            tile_builder.resolve_compact_places(
                {"placeIds": []}, {"places": []}
            )

    def test_every_grid_index_and_array_shape_is_validated(self):
        mutations = (
            lambda g: g["owner"][0].__setitem__(0, 99),
            lambda g: g["seatOwner"][0].__setitem__(0, 99),
            lambda g: g["hubs"].__setitem__(0, 99),
            lambda g: g["zhiPlaces"].__setitem__(0, 99),
            lambda g: g["junOf"].__setitem__(0, 99),
            lambda g: g["adjacency"]["county"].append({"a": 99, "b": 100, "cells": 3}),
            lambda g: g["adjacency"]["commandery"].append({"a": 0, "b": 99, "cells": 3, "cross": "LAND"}),
            lambda g: g["terrain"][0].__setitem__(0, 99),
            lambda g: g["region"][0].__setitem__(0, 0),
            lambda g: g["terrain"].pop(),
            lambda g: g["region"][0].pop(),
        )
        for mutate in mutations:
            _, _, grid, places_document = self.make_documents()
            resolved = tile_builder.resolve_compact_places(grid, places_document)
            mutate(grid)
            with self.subTest(mutate=mutate), self.assertRaises(ValueError):
                tile_builder.validate_compact_grid(grid, resolved)

    def test_adjacency_schema_duplicates_cells_and_derivation_fail_closed(self):
        mutations = (
            lambda g: g["adjacency"]["county"].extend([
                {"a": 0, "b": 1, "cells": 3}, {"a": 0, "b": 1, "cells": 3},
            ]),
            lambda g: g["adjacency"]["county"].append({"a": 1, "b": 0, "cells": 3}),
            lambda g: g["adjacency"]["county"].append({"a": 0, "b": 1}),
            lambda g: g["adjacency"]["county"].append({"a": 0, "b": 1, "cells": True}),
            lambda g: g["adjacency"]["county"].append({"a": 0, "b": 1, "cells": 3, "extra": 1}),
            lambda g: g["adjacency"]["commandery"].append({"a": 0, "b": 1, "cells": 3, "cross": True}),
            lambda g: g["adjacency"]["commandery"].append({"a": 0, "b": 1, "cells": 3, "cross": "LAND", "extra": 1}),
            lambda g: g["adjacency"]["county"].append({"a": 0, "b": 1, "cells": 4}),
        )
        for mutate in mutations:
            _, _, grid, places_document = self.make_documents()
            resolved = tile_builder.resolve_compact_places(grid, places_document)
            mutate(grid)
            with self.subTest(mutate=mutate), self.assertRaises(ValueError):
                tile_builder.validate_compact_grid(grid, resolved)

    def test_valid_nonempty_county_and_commandery_edges_pass_schema(self):
        expected = [{"a": 0, "b": 1, "cells": 3}]
        tile_builder._validate_edges(
            copy.deepcopy(expected), "county", 2, expected,
            commandery=False, cols=3, rows=3,
        )
        tile_builder._validate_edges(
            [{**expected[0], "cross": "LAND"}], "commandery", 2, expected,
            commandery=True, cols=3, rows=3,
        )

    def test_tile_validator_rejects_zero_area_places_and_missing_pengcheng(self):
        mutations = (
            lambda g: [row.__setitem__(col, 1) for row in g["owner"] for col, value in enumerate(row) if value == 0],
            lambda g: g["placeIds"].remove("33425"),
            lambda g: g["placeIds"].remove("42777"),
        )
        for mutate in mutations:
            _, _, grid, places_document = self.make_documents()
            mutate(grid)
            with self.subTest(mutate=mutate), self.assertRaises(ValueError):
                resolved = tile_builder.resolve_compact_places(grid, places_document)
                tile_builder.validate_compact_grid(grid, resolved)

    def test_direct_build_recomputes_counts_from_compact_state(self):
        _, compact, grid, places_document = self.make_documents()
        grid["_meta"] = {"counts": {"cities": 999, "adjCounty": 999}}
        readings = {
            name: name
            for name in (
                [place["nameFt"] for place in compact["places"]]
                + compact["junNames"]
            )
        }
        rendered = tile_builder.build(
            grid_document=grid,
            places_document=places_document,
            readings_document=readings,
        )
        self.assertEqual(11, rendered["_meta"]["counts"]["cities"])
        self.assertEqual(10, rendered["_meta"]["counts"]["seats"])
        self.assertEqual(len(grid["adjacency"]["county"]), rendered["_meta"]["counts"]["adjCounty"])
        self.assertEqual(len(grid["adjacency"]["commandery"]), rendered["_meta"]["counts"]["adjCommandery"])
        self.assertEqual(10, rendered["_meta"]["counts"]["COUNTY"])
        self.assertEqual(1, rendered["_meta"]["counts"]["KINGDOM"])
        self.assertEqual(
            compact["placeIds"], [city["id"] for city in rendered["cities"]]
        )


class TerrainGraphRederivationTest(ReviewedMergeTransformTest):
    def test_graphs_are_rederived_only_from_compact_owner_and_seat_owner(self):
        state = self.make_state()
        state["owner"] = np.repeat(np.repeat(state["owner"], 3, axis=0), 3, axis=1)
        state["seat_owner"] = np.repeat(
            np.repeat(state["seat_owner"], 3, axis=0), 3, axis=1
        )
        terrain = np.ones(state["owner"].shape, dtype=np.uint8)
        result = terrain_builder.finalize_reviewed_merge_state(
            terrain=terrain,
            places=state["places"],
            owner=state["owner"],
            seat_owner=state["seat_owner"],
            jun_of=state["jun_of"],
            hubs=state["hubs"],
            jun_names=state["jun_names"],
            zhi_places=state["zhi_places"],
            ledger=state["ledger"],
        )
        self.assertEqual(
            terrain_builder.adjacency(result["owner"]),
            result["adjacency"]["county"],
        )
        commandery_without_cross = [
            {key: value for key, value in edge.items() if key not in ("cross", "ford")}
            for edge in result["adjacency"]["commandery"]
        ]
        self.assertEqual(
            terrain_builder.adjacency(result["seatOwner"], min_shared_edges=1),
            commandery_without_cross,
        )
        self.assertTrue(result["adjacency"]["county"])
        self.assertTrue(all(
            0 <= edge[endpoint] < len(result["places"])
            for edge in result["adjacency"]["county"]
            for endpoint in ("a", "b")
        ))
        self.assertTrue(all(
            0 <= road[endpoint] < len(result["places"])
            for road in result["roads"]
            for endpoint in ("a", "b")
        ))


if __name__ == "__main__":
    unittest.main()
