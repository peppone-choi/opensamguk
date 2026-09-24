"""Road provenance must affect weights without promoting inferred local paths."""

import json
import unittest
from collections import defaultdict
from pathlib import Path

OUTPUT = Path(__file__).resolve().parents[3] / "data/map/han-land-roads-v1.json"


class HanLandRoadsTest(unittest.TestCase):
    def test_documented_corridors_are_the_only_weighted_edges(self):
        roads = json.loads(OUTPUT.read_text())
        edges = roads["edges"]
        documented = [edge for edge in edges if edge["historicalRouteIds"]]
        inferred = [edge for edge in edges if not edge["historicalRouteIds"]]
        self.assertTrue(documented)
        self.assertTrue(inferred)
        self.assertTrue(all(edge["routeWeightPermille"] == 700 and
                            edge["evidence"] == "DOCUMENTED_CORRIDOR_INFERRED_ALIGNMENT"
                            for edge in documented))
        self.assertTrue(all(edge["routeWeightPermille"] == 1000 and
                            edge["evidence"] == "TERRAIN_INFERRED" for edge in inferred))
        self.assertTrue(all(edge["status"] == "BUILT" for edge in documented))
        self.assertTrue(all(edge["fromProvinceId"] < edge["toProvinceId"] for edge in edges))
        self.assertEqual(len(edges), roads["counts"]["candidateEdges"])
        self.assertEqual(len(edges), roads["counts"]["builtEdges"] +
                         roads["counts"]["unbuiltEdges"] + roads["counts"]["inaccessibleEdges"])
        self.assertEqual(sum(edge["overviewTrunk"] for edge in edges), roads["counts"]["overviewTrunkEdges"])
        self.assertTrue(all(edge["status"] == "BUILT" for edge in edges if edge["overviewTrunk"]))

    def test_overview_is_one_connected_network_and_built_trails_share_seats(self):
        roads = json.loads(OUTPUT.read_text())
        network = defaultdict(set)
        origins = defaultdict(set)
        for edge in roads["edges"]:
            if edge["status"] != "BUILT":
                continue
            origins[edge["fromProvinceId"]].add(tuple(edge["fromTrail"][0]))
            origins[edge["toProvinceId"]].add(tuple(edge["toTrail"][0]))
            if edge["overviewTrunk"]:
                a, b = edge["fromProvinceId"], edge["toProvinceId"]
                network[a].add(b)
                network[b].add(a)
        self.assertTrue(all(len(cells) == 1 for cells in origins.values()))
        visited = set()
        stack = [next(iter(network))]
        while stack:
            node = stack.pop()
            if node not in visited:
                visited.add(node)
                stack.extend(network[node] - visited)
        self.assertEqual(visited, set(network))


if __name__ == "__main__":
    unittest.main()
