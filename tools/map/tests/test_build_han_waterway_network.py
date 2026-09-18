"""Red probes for the waterway network gates: each one breaks the ledger and must go red."""

import copy
import json
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import build_han_waterway_network as B  # noqa: E402


class WaterwayNetworkTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tiles_bytes = B.TILES.read_bytes()
        cls.strong_bytes = B.STRONGHOLDS.read_bytes()
        cls.tiles = json.loads(cls.tiles_bytes)
        cls.strong = json.loads(cls.strong_bytes)
        cls.ledger = json.loads(B.LEDGER.read_bytes())

    def build(self, ledger):
        return B.build(self.tiles, self.tiles_bytes, self.strong, self.strong_bytes, ledger)

    def mutated(self):
        return copy.deepcopy(self.ledger)

    def node(self, ledger, key):
        return next(n for n in ledger["nodes"] if n["stableKey"] == key)

    def assertRed(self, ledger, fragment):
        with self.assertRaises(ValueError) as caught:
            self.build(ledger)
        self.assertIn(fragment, str(caught.exception))

    def test_committed_ledger_builds_and_artifact_is_current(self):
        artifact = self.build(self.ledger)
        self.assertEqual(B.canonical_json_bytes(artifact), B.OUTPUT.read_bytes())
        self.assertEqual(artifact["activation"], "NON_ACTIVATING")
        self.assertTrue(all(e["status"] == "PROPOSED_NOT_ACTIVATED"
                            for e in artifact["proposedTraversalEdges"]))

    def test_check_goes_red_on_artifact_drift(self):
        original = B.OUTPUT
        try:
            drifted = Path(self.id() + ".tmp.json")
            drifted.write_bytes(original.read_bytes().replace(b"NON_ACTIVATING", b"NON_ACTIVATINX", 1))
            B.OUTPUT = drifted
            self.assertEqual(B.main(["--check"]), 1)
            B.OUTPUT = original
            self.assertEqual(B.main(["--check"]), 0)
        finally:
            B.OUTPUT = original
            drifted.unlink(missing_ok=True)

    def test_inland_port_is_impossible(self):
        # 廣陵 sits 3 cells from water (GH #806 뒤 실측): promoting it to a port must fail.
        # (江陵 이 이 프로브였다 — 城 씨앗이 실제 위치로 돌아와 江에 붙어 항구가 됐다.)
        ledger = self.mutated()
        row = next(b for b in ledger["blocked"] if b["stableKey"] == "guangling")
        ledger["blocked"].remove(row)
        city = next(c for c in self.tiles["cities"] if c["id"] == row["siteRef"]["id"])
        ledger["nodes"].append({
            "stableKey": "guangling", "nameHan": row["nameHan"], "siteRef": row["siteRef"],
            "cell": {"row": city["row"], "col": city["col"]}, "reach": "jiang-ruxu-jianye",
            "roles": ["PORT"], "sourceRefs": row["sourceRefs"], "crossing": None,
            "port": {"landProvinceId": row["siteRef"]["id"], "sourceRefs": row["sourceRefs"]}})
        self.assertRed(ledger, "no inland port")

    def test_port_on_the_wrong_reach_is_rejected(self):
        ledger = self.mutated()
        self.node(ledger, "fankou")["reach"] = "he-mengjin"
        self.assertRed(ledger, "no inland port")

    def test_site_cannot_be_moved_to_the_water(self):
        ledger = self.mutated()
        self.node(ledger, "jiangzhou")["cell"]["col"] += 1
        self.assertRed(ledger, "sites are never moved")

    def test_crossing_banks_on_the_same_bank_are_rejected(self):
        ledger = self.mutated()
        crossing = self.node(ledger, "pubanjin")["crossing"]
        # 95495 also owns cells on the WEST bank (229,332): both banks west of 河.
        crossing["bankB"] = {"landProvinceId": "95495", "row": 229, "col": 332}
        self.assertRed(ledger, "SAME bank")

    def test_crossing_banks_in_one_province_are_rejected(self):
        ledger = self.mutated()
        crossing = self.node(ledger, "pubanjin")["crossing"]
        crossing["bankA"] = {"landProvinceId": "95495", "row": 229, "col": 332}
        self.assertRed(ledger, "both banks belong to one province")

    def test_bank_cell_must_be_owned_by_the_named_province(self):
        ledger = self.mutated()
        self.node(ledger, "mengjin")["crossing"]["bankB"]["landProvinceId"] = "82512"
        self.assertRed(ledger, "is not owned by")

    def test_every_row_needs_a_source(self):
        for collection in ("reaches", "nodes", "flowLinks", "blocked"):
            with self.subTest(collection=collection):
                ledger = self.mutated()
                ledger[collection][0]["sourceRefs"] = []
                self.assertRed(ledger, "has no source")
        ledger = self.mutated()
        self.node(ledger, "fankou")["port"]["sourceRefs"] = ["shiliao:not-a-source"]
        self.assertRed(ledger, "unknown sources")

    def test_stronghold_quote_drift_is_detected(self):
        ledger = self.mutated()
        row = next(s for s in ledger["sources"] if s["sourceId"] == "stronghold:mengjin#0")
        row["quote"] += "。"
        self.assertRed(ledger, "no longer matches")

    def test_every_ferry_is_accounted_for_and_distances_are_measured(self):
        ledger = self.mutated()
        ledger["blocked"] = [b for b in ledger["blocked"] if b["stableKey"] != "guandu"]
        self.assertRed(ledger, "unaccounted")
        ledger = self.mutated()
        next(b for b in ledger["blocked"] if b["stableKey"] == "guandu")["measuredCellDistanceToWater"] = 1
        self.assertRed(ledger, "!= measured")

    def test_far_ferry_cannot_be_smuggled_in_as_a_node(self):
        ledger = self.mutated()
        row = next(b for b in ledger["blocked"] if b["stableKey"] == "guandu")
        ledger["blocked"].remove(row)
        anchor = next(s for s in self.strong["strongholds"] if s["id"] == "guandu")["tileAnchor"]
        ledger["nodes"].append({
            "stableKey": "guandu", "nameHan": "官渡", "siteRef": row["siteRef"],
            "cell": {"row": anchor["row"], "col": anchor["col"]}, "reach": "he-mengjin",
            "roles": ["PORT"], "sourceRefs": row["sourceRefs"], "crossing": None,
            "port": {"landProvinceId": "82879", "sourceRefs": row["sourceRefs"]}})
        self.assertRed(ledger, "no inland port")

    def test_flow_link_cannot_jump_between_reaches(self):
        ledger = self.mutated()
        ledger["flowLinks"][0]["downstreamReach"] = "jiang-jiangzhou-yiling"
        self.assertRed(ledger, "do not touch")

    def test_port_links_are_exactly_the_consecutive_ports(self):
        artifact = self.build(self.ledger)
        self.assertEqual([row["id"] for row in artifact["portLinks"]],
                         # GH #806 뒤 江陵·沙羨(夏口)·建業이 항구가 되어 연속 항구 쌍이 3 → 6 (夷陵–樊口 는 건너뛰기가 됐다).
                         ["port-link:fankou--ruxukou", "port-link:jiangling--xiakou-shaxian",
                          "port-link:jiangzhou--yiling", "port-link:ruxukou--jianye",
                          "port-link:xiakou-shaxian--fankou", "port-link:yiling--jiangling"])
        self.assertTrue(all(row["status"] == "CITY_CONNECTION_ONLY" for row in artifact["portLinks"]))

        def link(a, b):
            return {"stableKey": f"{a}--{b}", "fromNode": a, "toNode": b,
                    "sourceRefs": ["shiliao:sgz-jianye-suliu"]}
        ledger = self.mutated()   # 夷陵을 건너뛴다
        ledger["portLinks"].append(link("jiangzhou", "fankou"))
        self.assertRed(ledger, "skip a port")
        ledger = self.mutated()   # 沔水 구간은 江 과 흐름으로 이어져 있지 않다
        ledger["portLinks"].append(link("hanjin", "fankou"))
        self.assertRed(ledger, "cannot jump between reaches")
        ledger = self.mutated()   # 도하점은 항구가 아니다
        ledger["portLinks"].append(link("mengjin", "fankou"))
        self.assertRed(ledger, "reviewed PORT nodes")
        ledger = self.mutated()   # 빠뜨리기
        ledger["portLinks"].pop()
        self.assertRed(ledger, "port links missing")
        ledger = self.mutated()   # 출처 없음
        ledger["portLinks"][0]["sourceRefs"] = []
        self.assertRed(ledger, "has no source")
        ledger = self.mutated()   # 흐름 연결을 지우면 그 위의 뱃길도 선다
        ledger["flowLinks"] = [f for f in ledger["flowLinks"] if f["stableKey"] != "jiang-yiling-to-xiakou"]
        self.assertRed(ledger, "cannot jump between reaches")

    def test_activation_and_base_pins_are_enforced(self):
        ledger = self.mutated()
        ledger["activation"] = "ACTIVE"
        self.assertRed(ledger, "NON_ACTIVATING")
        ledger = self.mutated()
        ledger["base"]["hanTiles"]["sha256"] = "0" * 64
        self.assertRed(ledger, "han-tiles base pin drift")

    def test_reach_geometry_is_pinned(self):
        ledger = self.mutated()
        ledger["reaches"][0]["selector"]["colMax"] -= 10
        self.assertRed(ledger, "cell count")


if __name__ == "__main__":
    unittest.main()
