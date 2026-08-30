from __future__ import annotations

import unittest
import json
from pathlib import Path

from tools.map.build_tile_grid import resolve_province_record_names


class ProvinceRecordNameResolutionTest(unittest.TestCase):
    def test_committed_chinese_provinces_all_expose_real_county_names(self) -> None:
        root = Path(__file__).resolve().parents[3]
        tiles = json.loads((root / "data/map/han-tiles.json").read_text(encoding="utf-8"))
        chinese = [
            record for record in tiles["provinceRecords"]
            if record["administrativeSystem"] == "HAN_COMMANDERY"
        ]

        self.assertTrue(chinese)
        self.assertFalse(any("직할" in record["displayName"] for record in chinese))
        self.assertTrue(all(record["nameCh"] for record in chinese))
        self.assertTrue(all(record["kind"] != "DIRECT_TERRITORY" for record in chinese))
        self.assertTrue(all(record["cityIndex"] is not None for record in chinese))

    def test_committed_player_labels_contain_no_review_placeholders(self) -> None:
        root = Path(__file__).resolve().parents[3]
        tiles = json.loads((root / "data/map/han-tiles.json").read_text(encoding="utf-8"))
        forbidden = ("직할지", "(비정)", "(추정)", " 일대")
        invalid = [
            record["displayName"] for record in tiles["provinceRecords"]
            if any(token in record["displayName"] for token in forbidden)
        ]

        self.assertEqual([], invalid)

    def test_chinese_direct_fragment_uses_real_same_parent_county_name(self) -> None:
        cities = [
            {"name": "낙양현", "nameCh": "雒陽縣"},
            {"name": "평현", "nameCh": "平縣"},
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
                "id": "DIRECT-0", "displayName": "하남윤 직할지", "nameCh": "",
                "administrativeSystem": "HAN_COMMANDERY", "kind": "DIRECT_TERRITORY",
                "parentRegionId": "PARENT-0", "cityIndex": None,
                "geometryBasis": "MODERN_ADMIN_FALLBACK", "confidence": "INFERRED",
            },
        ]

        resolved = resolve_province_record_names(records, parents, cities, [0])

        self.assertEqual(resolved[1]["displayName"], "낙양현")
        self.assertEqual(resolved[1]["nameCh"], "雒陽縣")
        self.assertEqual(resolved[1]["cityIndex"], 0)
        self.assertEqual(resolved[1]["kind"], "COUNTY")

    def test_parent_without_county_uses_its_real_seat_county(self) -> None:
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

        self.assertEqual(resolved[0]["displayName"], "조선현")
        self.assertEqual(resolved[0]["cityIndex"], 0)
        self.assertEqual(resolved[0]["kind"], "COUNTY")
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
