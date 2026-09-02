from __future__ import annotations

import copy
import json
import unittest
from pathlib import Path
from unittest import mock

from tools.map.materialize_province_jurisdictions import _commandery_kind, materialize_document
from tools.map.world_province_geometry import (
    assign_province_jurisdictions,
    validate_jurisdiction_recovery_document,
)


class ProvinceJurisdictionMaterializationTest(unittest.TestCase):
    def test_recovery_evidence_is_bound_to_the_reviewed_corpus_revision_and_rows(self) -> None:
        root = Path(__file__).resolve().parents[3]
        document = json.loads((
            root / "data/curated/han/jurisdiction-seat-recoveries-v1.json"
        ).read_text(encoding="utf-8"))
        self.assertEqual(document["recoveries"], validate_jurisdiction_recovery_document(document))

        for mutate in (
            lambda value: value.update(sourceRevision="0" * 40),
            lambda value: value["recoveries"][0]["sourceCitation"].update(quote="作為"),
        ):
            changed = copy.deepcopy(document)
            mutate(changed)
            with self.assertRaisesRegex(ValueError, "source revision|evidence digest"):
                validate_jurisdiction_recovery_document(changed)

    def test_committed_hierarchy_is_total_and_referentially_closed(self) -> None:
        root = Path(__file__).resolve().parents[3]
        document = json.loads(
            (root / "data/map/han-tiles.json").read_text(encoding="utf-8")
        )
        provinces = document["provinceRecords"]
        jurisdictions = document["jurisdictionRecords"]
        commanderies = document["commanderyRecords"]
        jurisdiction_ids = {record["id"] for record in jurisdictions}
        commandery_ids = {record["id"] for record in commanderies}

        self.assertEqual(1524, len(provinces))
        self.assertEqual(1020, len(jurisdictions))
        self.assertEqual(172, len(commanderies))
        self.assertEqual({"SPATIAL_PROVINCE"}, {record["kind"] for record in provinces})
        self.assertEqual(len(provinces), len({record["id"] for record in provinces}))
        self.assertEqual(len(jurisdictions), len(jurisdiction_ids))
        self.assertEqual(len(commanderies), len(commandery_ids))
        self.assertTrue(all(record["jurisdictionId"] in jurisdiction_ids for record in provinces))
        for jurisdiction in jurisdictions:
            expected = sorted(
                record["id"] for record in provinces
                if record["jurisdictionId"] == jurisdiction["id"]
            )
            self.assertEqual(expected, jurisdiction["provinceIds"])
        self.assertTrue(all(record["commanderyId"] in commandery_ids for record in jurisdictions))
        for commandery in commanderies:
            expected = sorted(
                record["id"] for record in jurisdictions
                if record["commanderyId"] == commandery["id"]
            )
            self.assertEqual(expected, commandery["jurisdictionIds"])
        self.assertTrue(all(
            record["seatJurisdictionId"] in record["jurisdictionIds"]
            for record in commanderies
        ))

    def test_commandery_kind_follows_the_historical_parent_unit(self) -> None:
        self.assertEqual("KINGDOM", _commandery_kind({"nameCh": "趙國"}, {"kind": "COMMANDERY"}))
        self.assertEqual("METROPOLITAN", _commandery_kind({"nameCh": "河南尹"}, {"kind": "COMMANDERY"}))
        self.assertEqual("COMMANDERY", _commandery_kind({"nameCh": "張掖屬國"}, {"kind": "KINGDOM"}))
        self.assertEqual("COMMANDERY", _commandery_kind({"nameCh": "魏郡"}, {"kind": "COMMANDERY"}))

    def test_rematerialization_repairs_commandery_kind_and_spatial_seat_reference(self) -> None:
        document = {
            "_meta": {"cols": 4, "rows": 1, "counts": {"provinces": 2}},
            "owner": [[0, 2], [1, 2]],
            "provinceRecords": [
                {
                    "id": "COUNTY-A", "displayName": "갑현", "nameCh": "甲縣",
                    "administrativeSystem": "HAN_COMMANDERY", "kind": "COUNTY",
                    "parentRegionId": "PARENT-1", "cityIndex": 0,
                },
                {
                    "id": "COUNTY-B", "displayName": "을현", "nameCh": "乙縣",
                    "administrativeSystem": "HAN_COMMANDERY", "kind": "COUNTY",
                    "parentRegionId": "PARENT-1", "cityIndex": 1,
                },
            ],
            "parentRegions": [{
                "id": "PARENT-1", "displayName": "갑윤", "nameCh": "甲尹",
                "administrativeSystem": "HAN_COMMANDERY",
            }],
            "juns": [{"name": "갑윤", "nameCh": "甲尹", "seat": 2}],
            "cities": [
                {"id": "PLACE-A", "name": "갑현", "row": 0, "col": 0},
                {"id": "PLACE-B", "name": "을현", "row": 0, "col": 3},
                {"id": "COMMANDERY-SEAT", "name": "갑윤 치소", "row": 0, "col": 2},
            ],
        }
        recoveries = {"recoveries": []}
        with mock.patch(
            "tools.map.materialize_province_jurisdictions.validate_jurisdiction_recovery_document",
            return_value=[],
        ):
            materialized = materialize_document(document, recoveries)
        drifted = copy.deepcopy(materialized)
        drifted["commanderyRecords"][0]["kind"] = "COMMANDERY"
        drifted["commanderyRecords"][0]["seatJurisdictionId"] = "COUNTY-A"

        with mock.patch(
            "tools.map.materialize_province_jurisdictions.validate_jurisdiction_recovery_document",
            return_value=[],
        ):
            repaired = materialize_document(drifted, recoveries)

        self.assertEqual("METROPOLITAN", repaired["commanderyRecords"][0]["kind"])
        self.assertEqual("COUNTY-B", repaired["commanderyRecords"][0]["seatJurisdictionId"])

    def test_document_materializer_preserves_geometry_and_adds_three_level_records(self) -> None:
        document = {
            "_meta": {"cols": 2, "rows": 1, "counts": {"provinces": 1}},
            "owner": [[0, 2]],
            "terrain": ["11"],
            "provinceRecords": [{
                "id": "DIRECT-1", "displayName": "서하군", "nameCh": "西河郡",
                "administrativeSystem": "HAN_COMMANDERY", "kind": "DIRECT_TERRITORY",
                "parentRegionId": "PARENT-1", "cityIndex": None,
                "geometryBasis": "MODERN_ADMIN_FALLBACK", "confidence": "INFERRED",
            }],
            "parentRegions": [{
                "id": "PARENT-1", "displayName": "서하군", "nameCh": "西河郡",
                "administrativeSystem": "HAN_COMMANDERY",
            }],
            "juns": [{"name": "서하군", "nameCh": "西河郡", "seat": 0}],
            "cities": [{
                "id": "X-SEAT", "name": "서하군", "nameCh": "西河郡",
                "kind": "COMMANDERY", "row": 0, "col": 0,
            }],
        }
        recoveries = {"recoveries": [{
            "parentRegionId": "PARENT-1", "jurisdictionId": "RECOVERED-1",
            "displayName": "이석현", "nameCh": "離石縣", "kind": "COUNTY",
            "seatPlaceId": "X-SEAT", "reviewState": "REVIEWED",
            "sourceCitation": {
                "corpusPath": "data/corpus/hhs-113.txt", "line": 587, "quote": "離石",
            },
        }]}

        with mock.patch(
            "tools.map.materialize_province_jurisdictions.validate_jurisdiction_recovery_document",
            return_value=recoveries["recoveries"],
        ):
            result = materialize_document(document, recoveries)

        self.assertEqual([[0, 2]], result["owner"])
        self.assertEqual(["11"], result["terrain"])
        self.assertEqual("SPATIAL_PROVINCE", result["provinceRecords"][0]["kind"])
        self.assertEqual("RECOVERED-1", result["provinceRecords"][0]["jurisdictionId"])
        self.assertEqual(1, len(result["jurisdictionRecords"]))
        self.assertEqual(1, len(result["commanderyRecords"]))
        self.assertEqual(1, result["_meta"]["counts"]["jurisdictions"])
        self.assertEqual(1, result["_meta"]["counts"]["commanderies"])

        with mock.patch(
            "tools.map.materialize_province_jurisdictions.validate_jurisdiction_recovery_document",
            return_value=recoveries["recoveries"],
        ):
            self.assertEqual(result, materialize_document(result, recoveries))

    def test_direct_fragment_joins_longest_same_parent_boundary(self) -> None:
        result = assign_province_jurisdictions(
            owner=[
                [0, 2, 2, 1],
                [0, 2, 1, 1],
            ],
            province_records=[
                {
                    "id": "COUNTY-A",
                    "displayName": "갑현",
                    "nameCh": "甲縣",
                    "administrativeSystem": "HAN_COMMANDERY",
                    "kind": "COUNTY",
                    "parentRegionId": "PARENT-1",
                    "cityIndex": 0,
                    "geometryBasis": "HISTORICAL_SEAT_ADAPTED",
                    "confidence": "REVIEWED",
                },
                {
                    "id": "COUNTY-B",
                    "displayName": "을현",
                    "nameCh": "乙縣",
                    "administrativeSystem": "HAN_COMMANDERY",
                    "kind": "COUNTY",
                    "parentRegionId": "PARENT-1",
                    "cityIndex": 1,
                    "geometryBasis": "HISTORICAL_SEAT_ADAPTED",
                    "confidence": "REVIEWED",
                },
                {
                    "id": "DIRECT-1",
                    "displayName": "갑군",
                    "nameCh": "甲郡",
                    "administrativeSystem": "HAN_COMMANDERY",
                    "kind": "DIRECT_TERRITORY",
                    "parentRegionId": "PARENT-1",
                    "cityIndex": None,
                    "geometryBasis": "MODERN_ADMIN_FALLBACK",
                    "confidence": "INFERRED",
                },
            ],
            parent_regions=[{
                "id": "PARENT-1",
                "displayName": "갑군",
                "nameCh": "甲郡",
                "administrativeSystem": "HAN_COMMANDERY",
            }],
            cities=[
                {"id": "PLACE-A", "name": "갑현", "nameCh": "甲縣", "row": 0, "col": 0},
                {"id": "PLACE-B", "name": "을현", "nameCh": "乙縣", "row": 1, "col": 3},
            ],
            parent_seats=[0],
        )

        direct = result.province_records[2]
        self.assertEqual("COUNTY-B", direct["jurisdictionId"])
        self.assertEqual("MAX_SHARED_BOUNDARY", direct["assignmentBasis"])
        self.assertEqual("INFERRED", direct["assignmentConfidence"])
        self.assertEqual(
            [
                {
                    "id": "COUNTY-A", "displayName": "갑현", "nameCh": "甲縣",
                    "kind": "COUNTY", "commanderyId": "PARENT-1",
                    "seatPlaceId": "PLACE-A", "provinceIds": ["COUNTY-A"],
                },
                {
                    "id": "COUNTY-B", "displayName": "을현", "nameCh": "乙縣",
                    "kind": "COUNTY", "commanderyId": "PARENT-1",
                    "seatPlaceId": "PLACE-B", "provinceIds": ["COUNTY-B", "DIRECT-1"],
                },
            ],
            list(result.jurisdiction_records),
        )
        self.assertEqual(
            [{
                "id": "PARENT-1", "displayName": "갑군", "nameCh": "甲郡",
                "kind": "COMMANDERY", "seatJurisdictionId": "COUNTY-A",
                "jurisdictionIds": ["COUNTY-A", "COUNTY-B"],
            }],
            list(result.commandery_records),
        )
        self.assertEqual(
            ["SPATIAL_PROVINCE", "SPATIAL_PROVINCE", "SPATIAL_PROVINCE"],
            [record["kind"] for record in result.province_records],
        )

    def test_equal_boundary_uses_nearest_seat_before_stable_id(self) -> None:
        result = assign_province_jurisdictions(
            owner=[[0, 2, 1]],
            province_records=[
                {
                    "id": "COUNTY-A", "displayName": "갑현", "nameCh": "甲縣",
                    "administrativeSystem": "HAN_COMMANDERY", "kind": "COUNTY",
                    "parentRegionId": "PARENT-1", "cityIndex": 0,
                    "geometryBasis": "HISTORICAL_SEAT_ADAPTED", "confidence": "REVIEWED",
                },
                {
                    "id": "COUNTY-Z", "displayName": "을현", "nameCh": "乙縣",
                    "administrativeSystem": "HAN_COMMANDERY", "kind": "COUNTY",
                    "parentRegionId": "PARENT-1", "cityIndex": 1,
                    "geometryBasis": "HISTORICAL_SEAT_ADAPTED", "confidence": "REVIEWED",
                },
                {
                    "id": "DIRECT-1", "displayName": "갑군", "nameCh": "甲郡",
                    "administrativeSystem": "HAN_COMMANDERY", "kind": "DIRECT_TERRITORY",
                    "parentRegionId": "PARENT-1", "cityIndex": None,
                    "geometryBasis": "MODERN_ADMIN_FALLBACK", "confidence": "INFERRED",
                },
            ],
            parent_regions=[{
                "id": "PARENT-1", "displayName": "갑군", "nameCh": "甲郡",
                "administrativeSystem": "HAN_COMMANDERY",
            }],
            cities=[
                {"id": "PLACE-A", "name": "갑현", "row": 0, "col": 20},
                {"id": "PLACE-Z", "name": "을현", "row": 0, "col": 2},
            ],
            parent_seats=[1],
        )

        self.assertEqual("COUNTY-Z", result.province_records[2]["jurisdictionId"])
        self.assertEqual("MAX_SHARED_BOUNDARY", result.province_records[2]["assignmentBasis"])

    def test_isolated_fragment_joins_nearest_same_parent_seat_then_stable_id(self) -> None:
        result = assign_province_jurisdictions(
            owner=[[0, 0, -1, 2, -1, 1, 1]],
            province_records=[
                {
                    "id": "COUNTY-A", "displayName": "갑현", "nameCh": "甲縣",
                    "administrativeSystem": "HAN_COMMANDERY", "kind": "COUNTY",
                    "parentRegionId": "PARENT-1", "cityIndex": 0,
                    "geometryBasis": "HISTORICAL_SEAT_ADAPTED", "confidence": "REVIEWED",
                },
                {
                    "id": "COUNTY-B", "displayName": "을현", "nameCh": "乙縣",
                    "administrativeSystem": "HAN_COMMANDERY", "kind": "COUNTY",
                    "parentRegionId": "PARENT-1", "cityIndex": 1,
                    "geometryBasis": "HISTORICAL_SEAT_ADAPTED", "confidence": "REVIEWED",
                },
                {
                    "id": "DIRECT-1", "displayName": "갑군", "nameCh": "甲郡",
                    "administrativeSystem": "HAN_COMMANDERY", "kind": "DIRECT_TERRITORY",
                    "parentRegionId": "PARENT-1", "cityIndex": None,
                    "geometryBasis": "MODERN_ADMIN_FALLBACK", "confidence": "INFERRED",
                },
            ],
            parent_regions=[{
                "id": "PARENT-1", "displayName": "갑군", "nameCh": "甲郡",
                "administrativeSystem": "HAN_COMMANDERY",
            }],
            cities=[
                {"id": "PLACE-A", "name": "갑현", "nameCh": "甲縣", "row": 0, "col": 0},
                {"id": "PLACE-B", "name": "을현", "nameCh": "乙縣", "row": 0, "col": 6},
            ],
            parent_seats=[0],
        )

        direct = result.province_records[2]
        self.assertEqual("COUNTY-A", direct["jurisdictionId"])
        self.assertEqual("NEAREST_SEAT_WITHIN_PARENT", direct["assignmentBasis"])

    def test_commandery_seat_resolves_from_the_spatial_province_at_its_grid_cell(self) -> None:
        result = assign_province_jurisdictions(
            owner=[[0, 0, 1, 1]],
            province_records=[
                {
                    "id": "COUNTY-A", "displayName": "갑현", "nameCh": "甲縣",
                    "administrativeSystem": "HAN_COMMANDERY", "kind": "COUNTY",
                    "parentRegionId": "PARENT-1", "cityIndex": 0,
                    "geometryBasis": "HISTORICAL_SEAT_ADAPTED", "confidence": "REVIEWED",
                },
                {
                    "id": "COUNTY-B", "displayName": "을현", "nameCh": "乙縣",
                    "administrativeSystem": "HAN_COMMANDERY", "kind": "COUNTY",
                    "parentRegionId": "PARENT-1", "cityIndex": 1,
                    "geometryBasis": "HISTORICAL_SEAT_ADAPTED", "confidence": "REVIEWED",
                },
            ],
            parent_regions=[{
                "id": "PARENT-1", "displayName": "갑군", "nameCh": "甲郡",
                "administrativeSystem": "HAN_COMMANDERY",
            }],
            cities=[
                {"id": "PLACE-A", "name": "갑현", "row": 0, "col": 0},
                {"id": "PLACE-B", "name": "을현", "row": 0, "col": 3},
                {"id": "COMMANDERY-SEAT", "name": "갑군 치소", "row": 0, "col": 2},
            ],
            parent_seats=[2],
        )

        self.assertEqual("COUNTY-B", result.commandery_records[0]["seatJurisdictionId"])

    def test_reviewed_recovery_materializes_a_real_county_for_a_parent_without_one(self) -> None:
        direct_record = {
            "id": "DIRECT-1", "displayName": "서하군", "nameCh": "西河郡",
            "administrativeSystem": "HAN_COMMANDERY", "kind": "DIRECT_TERRITORY",
            "parentRegionId": "PARENT-1", "cityIndex": None,
            "geometryBasis": "MODERN_ADMIN_FALLBACK", "confidence": "INFERRED",
        }
        result = assign_province_jurisdictions(
            owner=[[0, 0]],
            province_records=[direct_record],
            parent_regions=[{
                "id": "PARENT-1", "displayName": "서하군", "nameCh": "西河郡",
                "administrativeSystem": "HAN_COMMANDERY",
            }],
            cities=[{
                "id": "X-SEAT", "name": "서하군", "nameCh": "西河郡",
                "kind": "COMMANDERY", "row": 0, "col": 0,
            }],
            parent_seats=[0],
            jurisdiction_recoveries=[{
                "parentRegionId": "PARENT-1",
                "jurisdictionId": "RECOVERED-PARENT-1",
                "displayName": "이석현",
                "nameCh": "離石縣",
                "kind": "COUNTY",
                "seatPlaceId": "X-SEAT",
                "reviewState": "REVIEWED",
                "sourceCitation": {
                    "corpusPath": "data/corpus/hhs-113.txt",
                    "line": 587,
                    "quote": "離石",
                },
            }],
        )

        self.assertEqual("RECOVERED-PARENT-1", result.province_records[0]["jurisdictionId"])
        self.assertEqual("REVIEWED_PARENT_SEAT_RECOVERY", result.province_records[0]["assignmentBasis"])
        self.assertEqual(
            [{
                "id": "RECOVERED-PARENT-1", "displayName": "이석현", "nameCh": "離石縣",
                "kind": "COUNTY", "commanderyId": "PARENT-1", "seatPlaceId": "X-SEAT",
                "provinceIds": ["DIRECT-1"],
            }],
            list(result.jurisdiction_records),
        )

    def test_external_parent_seat_becomes_a_local_settlement_jurisdiction(self) -> None:
        result = assign_province_jurisdictions(
            owner=[[0, 0]],
            province_records=[{
                "id": "DIRECT-EXT", "displayName": "남만", "nameCh": "南蠻",
                "administrativeSystem": "EXTERNAL_POLITY", "kind": "DIRECT_TERRITORY",
                "parentRegionId": "PARENT-EXT", "cityIndex": None,
                "geometryBasis": "MODERN_ADMIN_FALLBACK", "confidence": "INFERRED",
            }],
            parent_regions=[{
                "id": "PARENT-EXT", "displayName": "남만", "nameCh": "南蠻",
                "administrativeSystem": "EXTERNAL_POLITY",
            }],
            cities=[{
                "id": "PLACE-EXT", "name": "남만 취락", "nameCh": "南蠻聚落",
                "kind": "EXTERNAL_PLACE", "row": 0, "col": 0,
            }],
            parent_seats=[0],
        )

        self.assertEqual(
            "JURISDICTION-PARENT-EXT-SEAT",
            result.province_records[0]["jurisdictionId"],
        )
        self.assertEqual(
            "EXISTING_EXTERNAL_PARENT_SEAT",
            result.province_records[0]["assignmentBasis"],
        )
        self.assertEqual("EXTERNAL_SETTLEMENT", result.jurisdiction_records[0]["kind"])


if __name__ == "__main__":
    unittest.main()
