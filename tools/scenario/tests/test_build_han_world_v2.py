from __future__ import annotations

import hashlib
import importlib.util
import json
import subprocess
import sys
import unittest
from collections import Counter, defaultdict, deque
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
MODULE_PATH = ROOT / "tools/scenario/build_han_world.py"
SPEC = importlib.util.spec_from_file_location("build_han_world", MODULE_PATH)
assert SPEC and SPEC.loader
build_han_world = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(build_han_world)


class HanWorldV2Test(unittest.TestCase):
    def test_replaced_nodes_derive_visible_names_from_selected_physical_identity(self) -> None:
        selection = json.loads(
            (ROOT / "data/curated/han/route-node-selection-v1.json").read_text()
        )["routeNodes"]
        tiles = json.loads((ROOT / "data/map/han-tiles.json").read_text())
        legacy = json.loads(
            (ROOT / "infra/src/main/resources/map/han-780-v1.json").read_text()
        )
        world = json.loads(
            (ROOT / "infra/src/main/resources/map/han-world-v2.json").read_text()
        )
        physical_by_id = {str(city["id"]): city for city in tiles["cities"]}
        legacy_by_id = {city["id"]: city for city in legacy["cities"]}
        world_by_id = {city["id"]: city for city in world["cities"]}

        base_names: dict[int, str] = {}
        for node in selection:
            city_id = node["numericCityId"]
            if node.get("legacyDisposition") == "REPLACED" or city_id > 780:
                place_id = node["physicalPlaceRef"].rsplit(":", 1)[-1]
                base_names[city_id] = physical_by_id[place_id]["name"].removesuffix("현")
            else:
                base_names[city_id] = legacy_by_id[city_id]["name"]
        base_counts = Counter(base_names.values())
        expected_names = {
            node["numericCityId"]: (
                f'{base_names[node["numericCityId"]]}({node["parentName"]})'
                if base_counts[base_names[node["numericCityId"]]] > 1
                else base_names[node["numericCityId"]]
            )
            for node in selection
        }
        qualified_counts = Counter(expected_names.values())
        expected_names = {
            city_id: f"{name}#{city_id}" if qualified_counts[name] > 1 else name
            for city_id, name in expected_names.items()
        }

        replaced = [node for node in selection if node.get("legacyDisposition") == "REPLACED"]
        self.assertEqual(101, len(replaced))
        for node in replaced:
            city_id = node["numericCityId"]
            self.assertEqual(expected_names[city_id], world_by_id[city_id]["name"], node)
        self.assertEqual("수춘", world_by_id[543]["name"])

        retained = [node for node in selection if node.get("legacyDisposition") == "RETAINED"]
        for node in retained:
            city_id = node["numericCityId"]
            self.assertEqual(expected_names[city_id], world_by_id[city_id]["name"], node)
            for field in ("level", "max", "initial"):
                self.assertEqual(legacy_by_id[city_id][field], world_by_id[city_id][field], node)

    def test_county_adjacency_endpoints_are_spatial_province_indices(self) -> None:
        tiles = {
            "cities": [
                {"id": "place-b"},
                {"id": "unrelated"},
                {"id": "place-a"},
            ],
            "provinceRecords": [
                {"id": "jurisdiction-a", "cityIndex": 2},
                {"id": "jurisdiction-b", "cityIndex": 0},
            ],
            "adjacency": {"county": [{"a": 0, "b": 1, "cells": 6}]},
        }

        edges = build_han_world.project_county_adjacency(
            tiles, {"place-a": 781, "place-b": 273}
        )

        self.assertEqual([(273, 781, 6)], edges)

    def test_real_boundary_projection_links_lu_but_not_lu_county_to_licheng(self) -> None:
        tiles = json.loads((ROOT / "data/map/han-tiles.json").read_text())

        edges = build_han_world.project_county_adjacency(
            tiles, {"45098": 273, "45022": 781, "45180": 999}
        )

        self.assertEqual([(273, 781, 6)], edges)

        province_index = {
            str(row["id"]): index for index, row in enumerate(tiles["provinceRecords"])
        }
        graph: dict[int, list[int]] = defaultdict(list)
        for edge in tiles["adjacency"]["county"]:
            graph[edge["a"]].append(edge["b"])
            graph[edge["b"]].append(edge["a"])
        source, destination = province_index["45180"], province_index["45022"]
        queue = deque([(source, 0)])
        visited = {source}
        distance = None
        while queue:
            current, hops = queue.popleft()
            if current == destination:
                distance = hops
                break
            for neighbor in graph[current]:
                if neighbor not in visited:
                    visited.add(neighbor)
                    queue.append((neighbor, hops + 1))
        self.assertEqual(4, distance)

    def test_committed_v2_is_the_reviewed_selection_with_canonical_licheng_edge(self) -> None:
        selection = json.loads(
            (ROOT / "data/curated/han/route-node-selection-v1.json").read_text()
        )
        world = json.loads(
            (ROOT / "infra/src/main/resources/map/han-world-v2.json").read_text()
        )
        manifest = json.loads(
            (ROOT / "data/map/han-world-v2-manifest-v1.json").read_text()
        )
        expected = sorted(
            (
                node["routeNodeKey"],
                node["numericCityId"],
                node["physicalPlaceRef"],
            )
            for node in selection["routeNodes"]
        )
        actual = sorted(
            (city["routeNodeKey"], city["id"], city["physicalPlaceRef"])
            for city in world["cities"]
        )
        self.assertEqual(expected, actual)
        selection_by_id = {
            node["numericCityId"]: node for node in selection["routeNodes"]
        }
        for city in world["cities"]:
            self.assertEqual(selection_by_id[city["id"]]["parentName"], city["meta"]["junCh"])
            self.assertEqual(
                selection_by_id[city["id"]]["seatRole"] == "COMMANDERY_SEAT",
                city["meta"]["isSeat"],
            )
        self.assertEqual(781, len(actual))
        tiles = json.loads((ROOT / "data/map/han-tiles.json").read_text())
        physical = {str(city["id"]): city for city in tiles["cities"]}
        for city in world["cities"]:
            place = physical[city["physicalPlaceRef"].rsplit(":", 1)[-1]]
            self.assertEqual(
                round(place["col"] * world["width"] / tiles["_meta"]["cols"]), city["x"]
            )
            self.assertEqual(
                round(place["row"] * world["height"] / tiles["_meta"]["rows"]), city["y"]
            )
        self.assertEqual(expected, sorted([
            (row["routeNodeKey"], row["numericCityId"], row["physicalPlaceRef"])
            for row in manifest["routeNodes"]
        ]))

        by_id = {city["id"]: city for city in world["cities"]}
        self.assertIn(781, by_id[273]["connections"])
        self.assertIn(273, by_id[781]["connections"])
        edge = next(
            edge for edge in manifest["countyAdjacency"]
            if {edge["a"], edge["b"]} == {273, 781}
        )
        self.assertEqual(6, edge["sharedBoundaryCells"])
        output_paths = {
            "worldJsonSha256": ROOT / "infra/src/main/resources/map/han-world-v2.json",
            "cityConstSha256": ROOT / "common/src/main/kotlin/opensamguk/common/constants/HanWorldV2CityConst.kt",
            "gateIndexSha256": ROOT / "common/src/main/kotlin/opensamguk/common/constants/HanWorldV2GateIndex.kt",
        }
        for field, path in output_paths.items():
            self.assertEqual(
                manifest["outputs"][field], hashlib.sha256(path.read_bytes()).hexdigest()
            )

    def test_v2_check_is_separate_and_legacy_artifacts_remain_pinned(self) -> None:
        expected = {
            "infra/src/main/resources/map/han.json": "5f97f8c9269a0cff44839b55dd9e57e6d830003709df3d4618e4d1a79f76ed61",
            "infra/src/main/resources/map/han-780-v1.json": "a61cbd8aa6fd0dd2f7f794df6d0ebdc026c0b6c351568c60efb8d115f54b3670",
        }
        for rel, digest in expected.items():
            self.assertEqual(digest, hashlib.sha256((ROOT / rel).read_bytes()).hexdigest())
        result = subprocess.run(
            [sys.executable, str(MODULE_PATH), "--target", "han-world-v2", "--check"],
            cwd=ROOT,
            text=True,
            capture_output=True,
        )
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main()
