from __future__ import annotations

import unittest
import json
from pathlib import Path

from tools.map.build_tile_grid import resolve_province_record_names


class ProvinceRecordNameResolutionTest(unittest.TestCase):
    def test_committed_chinese_spatial_provinces_resolve_to_real_county_jurisdictions(self) -> None:
        root = Path(__file__).resolve().parents[3]
        tiles = json.loads((root / "data/map/han-tiles.json").read_text(encoding="utf-8"))
        chinese = [
            record for record in tiles["provinceRecords"]
            if record["administrativeSystem"] == "HAN_COMMANDERY"
        ]
        jurisdictions = {
            record["id"]: record for record in tiles["jurisdictionRecords"]
        }

        self.assertTrue(chinese)
        self.assertFalse(any("직할" in record["displayName"] for record in chinese))
        self.assertTrue(all(record["nameCh"] for record in chinese))
        self.assertTrue(all(record["kind"] == "SPATIAL_PROVINCE" for record in chinese))
        self.assertTrue(all(record["jurisdictionId"] in jurisdictions for record in chinese))
        chinese_jurisdictions = {
            jurisdictions[record["jurisdictionId"]]["id"] for record in chinese
        }
        self.assertTrue(chinese_jurisdictions)
        self.assertTrue(all(
            jurisdictions[jurisdiction_id]["displayName"]
            for jurisdiction_id in chinese_jurisdictions
        ))
        self.assertTrue(all(
            jurisdictions[jurisdiction_id]["nameCh"]
            for jurisdiction_id in chinese_jurisdictions
        ))
        self.assertTrue(all(
            jurisdictions[jurisdiction_id]["kind"]
            in {"COUNTY", "MARQUISATE", "EXTERNAL_SETTLEMENT"}
            for jurisdiction_id in chinese_jurisdictions
        ))

    def test_committed_player_labels_contain_no_review_placeholders(self) -> None:
        root = Path(__file__).resolve().parents[3]
        tiles = json.loads((root / "data/map/han-tiles.json").read_text(encoding="utf-8"))
        forbidden = ("직할지", "(비정)", "(추정)", " 일대")
        invalid = [
            record["displayName"] for record in tiles["provinceRecords"]
            if any(token in record["displayName"] for token in forbidden)
        ]

        self.assertEqual([], invalid)

    def test_chinese_direct_fragment_uses_parent_commandery_name_without_becoming_a_county(self) -> None:
        cities = [
            {"name": "낙양현", "nameCh": "雒陽縣", "row": 0, "col": 0},
            {"name": "평현", "nameCh": "平縣", "row": 0, "col": 4},
        ]
        parents = [{
            "id": "PARENT-0", "displayName": "하남윤", "nameCh": "河南尹",
            "administrativeSystem": "HAN_COMMANDERY",
        }]
        records = [
            {
                "id": "COUNTY-0", "displayName": "낙양현", "nameCh": "雒陽縣",
                "administrativeSystem": "HAN_COMMANDERY", "kind": "COUNTY",
                "parentRegionId": "PARENT-0", "cityIndex": 0,
                "geometryBasis": "HISTORICAL_SEAT_ADAPTED", "confidence": "REVIEWED",
            },
            {
                "id": "COUNTY-1", "displayName": "평현", "nameCh": "平縣",
                "administrativeSystem": "HAN_COMMANDERY", "kind": "COUNTY",
                "parentRegionId": "PARENT-0", "cityIndex": 1,
                "geometryBasis": "HISTORICAL_SEAT_ADAPTED", "confidence": "REVIEWED",
            },
            {
                "id": "DIRECT-0", "displayName": "하남윤 직할지", "nameCh": "",
                "administrativeSystem": "HAN_COMMANDERY", "kind": "DIRECT_TERRITORY",
                "parentRegionId": "PARENT-0", "cityIndex": None,
                "geometryBasis": "MODERN_ADMIN_FALLBACK", "confidence": "INFERRED",
            },
        ]

        resolved = resolve_province_record_names(records, parents, cities, [0])

        self.assertEqual(resolved[2]["displayName"], "하남윤")
        self.assertEqual(resolved[2]["nameCh"], "河南尹")
        self.assertIsNone(resolved[2]["cityIndex"])
        self.assertEqual(resolved[2]["kind"], "DIRECT_TERRITORY")

    def test_parent_without_county_uses_its_real_commandery_name(self) -> None:
        cities = [{"name": "조선현", "nameCh": "朝鮮縣"}]
        parents = [{
            "id": "PARENT-0", "displayName": "낙랑군", "nameCh": "樂浪郡",
            "administrativeSystem": "HAN_COMMANDERY",
        }]
        records = [{
            "id": "DIRECT-0", "displayName": "낙랑군 직할지", "nameCh": "",
            "administrativeSystem": "HAN_COMMANDERY", "kind": "DIRECT_TERRITORY",
            "parentRegionId": "PARENT-0", "cityIndex": None,
            "geometryBasis": "MODERN_ADMIN_FALLBACK", "confidence": "INFERRED",
        }]

        resolved = resolve_province_record_names(records, parents, cities, [0])

        self.assertEqual(resolved[0]["displayName"], "낙랑군")
        self.assertEqual(resolved[0]["nameCh"], "樂浪郡")
        self.assertIsNone(resolved[0]["cityIndex"])
        self.assertEqual(resolved[0]["kind"], "DIRECT_TERRITORY")
        self.assertNotIn("직할", resolved[0]["displayName"])

    def test_external_direct_fragment_uses_its_real_parent_name(self) -> None:
        parents = [{
            "id": "PARENT-0", "displayName": "우환", "nameCh": "烏桓",
            "administrativeSystem": "WUHUAN",
        }]
        records = [{
            "id": "DIRECT-0", "displayName": "烏桓 직할지", "nameCh": "",
            "administrativeSystem": "WUHUAN", "kind": "DIRECT_TERRITORY",
            "parentRegionId": "PARENT-0", "cityIndex": None,
            "geometryBasis": "MODERN_ADMIN_FALLBACK", "confidence": "INFERRED",
        }]

        resolved = resolve_province_record_names(records, parents, [], [])

        self.assertEqual(resolved[0]["displayName"], "우환")
        self.assertEqual(resolved[0]["nameCh"], "烏桓")
        self.assertNotIn("직할", resolved[0]["displayName"])


if __name__ == "__main__":
    unittest.main()
