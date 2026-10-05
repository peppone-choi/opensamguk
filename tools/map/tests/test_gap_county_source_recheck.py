#!/usr/bin/env python3
"""gap-county-source-recheck-v1 판정이 지도·원장에 적용된 상태인지 본다 (2026-09-27 사용자 승인 D1·D2).

- 중복 합성 城 23곳: gap-counties 의 retiredAsDuplicate 로 옮겨졌고 타일에 없다. 그 郡國志 행은 별칭으로 기존 관할에 붙는다.
- 동명 6건: 개명 1건은 별칭, 실결손 4건은 제 郡의 새 城, 접힌 1건(南陽 酇)은 별도 결정 대기라 그대로다.
- 합성 좌표 4곳: 원장 좌표가 판정 원장의 applied 좌표다.
"""
import json
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

ROOT = Path(__file__).resolve().parents[3]
CURATED = ROOT / "data/curated/han"


def _read(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


class SourceRecheckAppliedTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.doc = _read(CURATED / "gap-county-source-recheck-v1.json")
        gap = _read(CURATED / "gap-counties-v1.json")
        cls.gap = {row["id"]: row for row in gap["counties"]}
        cls.retired = {row["id"]: row for row in gap["retiredAsDuplicate"]}
        tiles = _read(ROOT / "data/map/province-tiles.json")
        cls.cities = {city["id"] for city in tiles["cities"]}
        cls.jurisdictions = {row["id"] for row in tiles["jurisdictionRecords"]}
        ledger = _read(CURATED / "junguozhi-county-gaps-v1.json")
        cls.rows = {(g["commandery"], c["sourceName"]): c for g in ledger["commanderies"] for c in g["counties"]}

    def test_counts_match_rows(self):
        counts = self.doc["counts"]
        self.assertEqual(counts["duplicateSyntheticCounties"], len(self.doc["duplicateSyntheticCounties"]))
        self.assertEqual(counts["sourcedCoordinates"], len(self.doc["sourcedCoordinates"]))
        verdicts = [row["verdict"] for row in self.doc["homonymAdjudications"]]
        self.assertEqual(counts["homonymRealGap"], verdicts.count("HOMONYM_REAL_GAP"))
        self.assertEqual(counts["homonymFalseAbsence"], verdicts.count("FALSE_ABSENCE_POST_220_RENAME"))
        self.assertEqual(counts["homonymFoldedIntoOtherCounty"], verdicts.count("FOLDED_INTO_OTHER_COUNTY"))
        self.assertEqual(len(verdicts), counts["homonymSuspects"])

    def test_duplicates_are_retired_and_their_rows_bind_the_existing_city(self):
        self.assertEqual(len(self.doc["duplicateSyntheticCounties"]), len(self.retired))
        for row in self.doc["duplicateSyntheticCounties"]:
            self.assertIn(row["gapCountyId"], self.retired, row["sourceName"])
            self.assertNotIn(row["gapCountyId"], self.gap, row["sourceName"])
            self.assertNotIn(row["placeId"], self.cities, row["sourceName"])
            current = self.rows[(row["commandery"], row["sourceName"])]
            self.assertEqual(("IN_OWN_COMMANDERY", "REVIEWED_ALIAS", row["mapCity"]["id"]),
                             (current["status"], current.get("basis"), current.get("jurisdictionId")), row["sourceName"])

    def test_homonym_verdicts_are_applied(self):
        for row in self.doc["homonymAdjudications"]:
            current = self.rows[(row["commandery"], row["sourceName"])]
            if row["verdict"] == "FALSE_ABSENCE_POST_220_RENAME":
                self.assertEqual(("IN_OWN_COMMANDERY", "REVIEWED_ALIAS", row["onMapAs"]["id"]),
                                 (current["status"], current.get("basis"), current.get("jurisdictionId")))
            elif row["verdict"] == "HOMONYM_REAL_GAP":
                self.assertEqual(("IN_OWN_COMMANDERY", "TILE_COMMANDERY"), (current["status"], current.get("basis")))
                self.assertTrue(current["jurisdictionId"].startswith("gc-"), row["sourceName"])
                self.assertIn(current["jurisdictionId"], self.jurisdictions)
            else:  # 南陽 酇: 접기 해제는 별도 결정이다. 적용하면 이 분기를 바꿔라.
                self.assertEqual("FOLDED_INTO_OTHER_COUNTY", row["verdict"])
                self.assertEqual("IN_OTHER_COMMANDERY", current["status"])

    def test_sourced_coordinates_replace_synthetic_cells(self):
        for row in self.doc["sourcedCoordinates"]:
            gap = self.gap[row["gapCountyId"]]
            lon, lat = row["applied"]["lonLat"]
            self.assertEqual({"latitude": lat, "longitude": lon}, gap["coordinates"], row["sourceName"])
            self.assertEqual(row["applied"]["coordinateBasis"], gap["coordinateBasis"], row["sourceName"])
            self.assertIn("supersededSyntheticPlacement", gap, row["sourceName"])
            self.assertIn(row["placeId"], self.cities, row["sourceName"])

    def test_duplicate_and_coordinate_sets_do_not_overlap(self):
        duplicates = {row["gapCountyId"] for row in self.doc["duplicateSyntheticCounties"]}
        sourced = {row["gapCountyId"] for row in self.doc["sourcedCoordinates"]}
        self.assertFalse(duplicates & sourced)


if __name__ == "__main__":
    unittest.main()
