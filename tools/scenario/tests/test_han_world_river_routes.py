"""강 뱃길 게이트 — han-world-v3 의 kind=RIVER 뱃길은 수로 망 원장의 portLinks 와 정확히 같다.

표를 두 군데 두지 않는다: build_han_world.v3_river_routes 가 `han-waterway-network-v1.json` 을 읽는다.
여기서는 (1) 커밋된 세계 파일이 그 표와 한 줄도 다르지 않은지, (2) 끝점이 검토된 PORT 노드가 아니거나
출처가 없거나 상태를 모르는 줄이 오면 생성기가 죽는지를 본다.
"""

import copy
import json
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "tools" / "scenario"))

import build_han_world as B  # noqa: E402

WORLD = ROOT / "infra" / "src" / "main" / "resources" / "map" / "han-world-v3.json"


class RiverRouteTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.world = json.loads(WORLD.read_text(encoding="utf-8"))
        cls.network = json.loads(B.WATERWAY_NETWORK.read_text(encoding="utf-8"))

    def test_world_river_routes_are_exactly_the_ledger_port_links(self):
        city_by_ref = {c["physicalPlaceRef"]: c for c in self.world["cities"]}
        expected = set()
        for a_ref, b_ref, why in B.v3_river_routes(self.network):
            a, b = city_by_ref[a_ref], city_by_ref[b_ref]
            self.assertIn(b["id"], a["connections"])
            self.assertIn(a["id"], b["connections"])
            expected.add((min(a["id"], b["id"]), max(a["id"], b["id"]), why))
        actual = {(r["from"], r["to"], r["source"]) for r in self.world["seaRoutes"] if r["kind"] == "RIVER"}
        self.assertEqual(actual, expected)
        self.assertEqual(len(expected), len(self.network["portLinks"]))
        self.assertTrue(all(r["kind"] in {"SEA", "RIVER"} for r in self.world["seaRoutes"]))

    def test_endpoint_must_be_a_reviewed_port_node(self):
        network = copy.deepcopy(self.network)
        crossing = next(n for n in network["nodes"] if "PORT" not in n["roles"])
        network["portLinks"][0]["toNodeId"] = crossing["id"]
        with self.assertRaisesRegex(AssertionError, "PORT 노드가 아니다"):
            B.v3_river_routes(network)
        network = copy.deepcopy(self.network)
        network["portLinks"][0]["toNodeId"] = "waterway-node:guangling"   # blocked 에만 있는 城(江陵 은 GH #806 뒤 항구)
        with self.assertRaisesRegex(AssertionError, "PORT 노드가 아니다"):
            B.v3_river_routes(network)

    def test_link_needs_a_source_and_a_known_status(self):
        network = copy.deepcopy(self.network)
        network["portLinks"][0]["sourceRefs"] = []
        with self.assertRaisesRegex(AssertionError, "출처가 없다"):
            B.v3_river_routes(network)
        network = copy.deepcopy(self.network)
        network["portLinks"][0]["status"] = "PROPOSED_NOT_ACTIVATED"
        with self.assertRaisesRegex(AssertionError, "상태를 모른다"):
            B.v3_river_routes(network)


if __name__ == "__main__":
    unittest.main()
