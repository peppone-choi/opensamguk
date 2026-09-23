import json
import unittest

from tools.map import build_hwiha_resource_production as tool


class HwihaResourceProductionTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.built = tool.build()
        cls.committed = json.loads(tool.OUT.read_text(encoding="utf-8"))
        cls.sites = json.loads(tool.SITES.read_text(encoding="utf-8"))
        cls.tiles = json.loads(tool.TILES.read_text(encoding="utf-8"))

    def test_committed_ledger_is_current(self):
        self.assertEqual(self.committed, self.built)

    def test_only_later_han_iron_and_horse_are_carried(self):
        carried = {site["id"] for row in self.built["counties"] for site in row["sites"]}
        skipped = {row["id"] for row in self.built["skipped"]}
        for entry in self.sites["entries"]:
            eligible = entry["era"] == tool.ERA and entry["resource"] in tool.RATES
            if not eligible:
                self.assertNotIn(entry["id"], carried, entry["id"])
                self.assertNotIn(entry["id"], skipped, entry["id"])
            else:
                self.assertTrue(entry["id"] in carried or entry["id"] in skipped, entry["id"])

    def test_site_ledger_only_drives_iron_and_horse(self):
        # 목재 後漢 항목은 1건뿐이라 산지 원장으로는 「분산」을 못 받친다. 소금은 5자원에 없다.
        self.assertNotIn("WOOD", tool.RATES)
        self.assertNotIn("SALT", tool.RATES)
        self.assertEqual({"HORSE", "IRON"}, set(tool.RATES))
        sited = {s["resource"] for row in self.built["counties"] for s in row["sites"]}
        self.assertEqual({"HORSE", "IRON"}, sited)
        resources = {r for row in self.built["counties"] for r in row["monthly"]}
        self.assertEqual({"HORSE", "IRON", "TIMBER"}, resources)

    def test_timber_is_distributed_not_concentrated(self):
        """「목재는 분산」(2026-09-21 결정)이 실제로 분산인지 본다."""
        producing = [row for row in self.built["counties"] if "TIMBER" in row["monthly"]]
        total = len(self.tiles["jurisdictionRecords"])
        self.assertGreaterEqual(len(producing), total * 95 // 100,
                                "목재가 일부 縣에만 나면 분산이 아니다")
        # 山地+丘陵만 쓰면 29% 뿐이라 분산이 아니었다 — 그 축으로 되돌아가지 않는다.
        self.assertLess(total * 50 // 100, len(producing))

    def test_timber_is_wooded_cells_times_the_rate(self):
        for row in self.built["counties"]:
            if "TIMBER" not in row["monthly"]:
                self.assertNotIn("woodedCells", row)
                continue
            self.assertEqual(row["woodedCells"] * tool.TIMBER_PER_CELL, row["monthly"]["TIMBER"])
            self.assertGreater(row["woodedCells"], 0)

    def test_timber_axis_excludes_water_and_desert(self):
        # 바다·강·호수·사막·범위밖에는 삼림이 서지 않는다.
        legend = self.tiles["_meta"]["terrainLegend"]
        excluded = {code for code, name in legend.items()
                    if name in {"SEA", "RIVER", "LAKE", "DESERT", "OUT_OF_SCOPE"}}
        self.assertTrue(excluded)
        self.assertEqual(set(), excluded & tool.WOODED_TERRAIN)
        self.assertEqual(set(legend) - excluded, set(tool.WOODED_TERRAIN))

    def test_horse_rows_sit_on_their_commandery_seat(self):
        seat_of = {c["id"]: c.get("seatJurisdictionId") for c in self.tiles["commanderyRecords"]}
        horse = {e["id"]: e for e in self.sites["entries"]
                 if e["era"] == tool.ERA and e["resource"] == "HORSE" and e.get("commanderyId")}
        self.assertTrue(horse, "後漢 말 산지 郡 항목이 있어야 이 검사가 뜻을 가진다")
        placed = {site["id"]: row["jurisdictionId"]
                  for row in self.built["counties"] for site in row["sites"] if site["resource"] == "HORSE"}
        for site_id, entry in horse.items():
            self.assertEqual(seat_of[entry["commanderyId"]], placed[site_id], site_id)
            self.assertEqual("COMMANDERY_SEAT", next(
                s["basis"] for row in self.built["counties"] for s in row["sites"] if s["id"] == site_id))

    def test_iron_rows_keep_their_source_county(self):
        iron = {e["id"]: e["jurisdictionId"] for e in self.sites["entries"]
                if e["era"] == tool.ERA and e["resource"] == "IRON" and e.get("jurisdictionId")}
        placed = {site["id"]: row["jurisdictionId"]
                  for row in self.built["counties"] for site in row["sites"] if site["resource"] == "IRON"}
        self.assertEqual(iron, {k: v for k, v in placed.items() if k in iron})

    def test_every_county_is_a_known_jurisdiction(self):
        known = {j["id"] for j in self.tiles["jurisdictionRecords"]}
        for row in self.built["counties"]:
            self.assertIn(row["jurisdictionId"], known)

    def test_monthly_amount_is_rate_times_site_count(self):
        for row in self.built["counties"]:
            expected = {}
            for site in row["sites"]:
                expected[site["resource"]] = expected.get(site["resource"], 0) + tool.RATES[site["resource"]]
            if "woodedCells" in row:
                expected["TIMBER"] = row["woodedCells"] * tool.TIMBER_PER_CELL
            self.assertEqual(expected, row["monthly"], row["jurisdictionId"])

    def test_rates_are_recorded_in_the_artifact(self):
        # 수치는 게임 설계다. 산출물에 적혀 있어야 코드를 읽지 않고도 검토·변경할 수 있다.
        self.assertEqual(dict(sorted(tool.RATES.items())), self.built["rates"])
        self.assertEqual(tool.TIMBER_PER_CELL, self.built["timber"]["perWoodedCell"])
        self.assertEqual(sorted(tool.WOODED_TERRAIN), self.built["timber"]["woodedTerrainCodes"])

    def test_unbound_entries_are_skipped_not_guessed(self):
        for row in self.built["skipped"]:
            self.assertEqual("no bound county", row["reason"])
        self.assertEqual(len(self.built["skipped"]), self.built["counts"]["skipped"])


if __name__ == "__main__":
    unittest.main()
