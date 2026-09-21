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

    def test_wood_and_salt_are_not_production_sources(self):
        # 목재 後漢 항목은 1건뿐이라 「분산」 설계를 못 받치고, 소금은 5자원에 없다.
        self.assertNotIn("WOOD", tool.RATES)
        self.assertNotIn("SALT", tool.RATES)
        resources = {r for row in self.built["counties"] for r in row["monthly"]}
        self.assertEqual({"HORSE", "IRON"}, resources)

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
            self.assertEqual(expected, row["monthly"], row["jurisdictionId"])

    def test_rates_are_recorded_in_the_artifact(self):
        # 수치는 게임 설계다. 산출물에 적혀 있어야 코드를 읽지 않고도 검토·변경할 수 있다.
        self.assertEqual(dict(sorted(tool.RATES.items())), self.built["rates"])

    def test_unbound_entries_are_skipped_not_guessed(self):
        for row in self.built["skipped"]:
            self.assertEqual("no bound county", row["reason"])
        self.assertEqual(len(self.built["skipped"]), self.built["counts"]["skipped"])


if __name__ == "__main__":
    unittest.main()
