from __future__ import annotations

import copy
import hashlib
import json
import unittest
from pathlib import Path
from unittest import mock

from tools.map.materialize_province_jurisdictions import _commandery_kind, materialize_document
import numpy as np

from tools.map.build_terrain_grid import touching_pairs
from tools.map.world_province_geometry import (
    assign_province_jurisdictions,
    validate_jurisdiction_recovery_document,
)


class ProvinceJurisdictionMaterializationTest(unittest.TestCase):
    def test_committed_licheng_identity_chain_is_reviewed_without_moving_geometry(self) -> None:
        root = Path(__file__).resolve().parents[3]
        tiles = json.loads((root / "data/map/han-tiles.json").read_text(encoding="utf-8"))
        bindings = json.loads(
            (root / "data/curated/han/administrative-place-bindings-v1.json").read_text(
                encoding="utf-8"
            )
        )

        binding = next(
            row
            for row in bindings["administrativeUnits"]
            if row["administrativeUnitId"] == "hhs:112:濟南國:010"
        )
        self.assertEqual("RESOLVED_POINT", binding["joinStatus"])
        self.assertEqual(
            "chgis:v6:cnty:45022",
            binding["selectedCandidate"]["physicalPlaceId"],
        )

        jurisdictions = {row["id"]: row for row in tiles["jurisdictionRecords"]}
        commanderies = {row["id"]: row for row in tiles["commanderyRecords"]}
        cities = {row["id"]: row for row in tiles["cities"]}
        licheng = jurisdictions["45022"]
        self.assertEqual("PARENT-0035", licheng["commanderyId"])
        self.assertEqual(
            {"PARENT-0035"},
            {
                row["parentRegionId"]
                for row in tiles["provinceRecords"]
                if row["jurisdictionId"] == "45022"
            },
        )
        self.assertIn("45022", commanderies["PARENT-0035"]["jurisdictionIds"])
        self.assertNotIn("45022", commanderies["PARENT-0036"]["jurisdictionIds"])
        self.assertNotEqual("45022", commanderies["PARENT-0035"]["seatJurisdictionId"])
        self.assertFalse(cities["45022"]["zhi"])
        self.assertEqual(1_524, len(tiles["provinceRecords"]))
        self.assertEqual(1_020, len(tiles["jurisdictionRecords"]))
        self.assertEqual(172, len(tiles["commanderyRecords"]))
        geometry_hashes = {
            key: hashlib.sha256(
                json.dumps(value, ensure_ascii=False, separators=(",", ":")).encode()
            ).hexdigest()
            for key, value in {
                "terrain": tiles["terrain"],
                "owner": tiles["owner"],
                "cities": tiles["cities"],
                "countyAdjacency": tiles["adjacency"]["county"],
            }.items()
        }
        self.assertEqual(
            {
                "terrain": "89fb2257769e218a76a13e8a876a53242800e0ba76abee74fdc29973dd8c2a04",
                "owner": "7d531ec7c0ce708ab1f7a5768e540b0c3fb8b0398217c50a10248a6f5f494109",
                "cities": "b330c2718f4f02b631709a6d14863bd8ad61b5f9ac476e4331f91d7f3a567ca7",
                "countyAdjacency": "a6d4b32cdcdd68cfcb0c42bea8227666c9fc544b84692d801ec7b4fa0bfdea0d",
            },
            geometry_hashes,
        )

    def test_committed_qingzhou_parent_adjudications_match_220_sources(self) -> None:
        root = Path(__file__).resolve().parents[3]
        document = json.loads(
            (root / "data/map/han-tiles.json").read_text(encoding="utf-8")
        )
        jurisdictions = {
            record["id"]: record["commanderyId"]
            for record in document["jurisdictionRecords"]
        }

        self.assertEqual(
            {
                "45107": "PARENT-0036",  # 西平昌縣 → 平原郡
                "85385": "PARENT-0038",  # 挺縣 → 北海國
                "85505": "PARENT-0039",  # 不其縣 → 東萊郡
                "85706": "PARENT-0036",  # 安德縣 → 平原郡
            },
            {key: jurisdictions[key] for key in ("45107", "85385", "85505", "85706")},
        )

    def test_committed_ningyang_parent_surfaces_follow_dongping_with_fixed_catalog_counts(self) -> None:
        root = Path(__file__).resolve().parents[3]
        document = json.loads(
            (root / "data/map/han-tiles.json").read_text(encoding="utf-8")
        )
        jurisdictions = {row["id"]: row for row in document["jurisdictionRecords"]}
        commanderies = {row["id"]: row for row in document["commanderyRecords"]}
        ningyang_provinces = [
            row for row in document["provinceRecords"] if row["jurisdictionId"] == "45277"
        ]

        self.assertEqual("宁阳县", jurisdictions["45277"]["nameCh"])
        self.assertEqual("PARENT-0024", jurisdictions["45277"]["commanderyId"])
        self.assertEqual({"PARENT-0024"}, {row["parentRegionId"] for row in ningyang_provinces})
        self.assertIn("45277", commanderies["PARENT-0024"]["jurisdictionIds"])
        self.assertNotIn("45277", commanderies["PARENT-0028"]["jurisdictionIds"])
        self.assertNotEqual("45277", commanderies["PARENT-0024"]["seatJurisdictionId"])
        self.assertEqual(1_524, len(document["provinceRecords"]))
        self.assertEqual(1_020, len(document["jurisdictionRecords"]))
        self.assertEqual(172, len(document["commanderyRecords"]))

    def test_ningyang_row_reparents_a_pristine_in_memory_source_parent_fixture(self) -> None:
        document = self.parent_adjudication_fixture()
        commandery_ids = {"C-OLD": "PARENT-0028", "C-NEW": "PARENT-0024"}
        commandery_names = {
            "PARENT-0028": ("산양군", "山陽郡"),
            "PARENT-0024": ("동평국", "東平國"),
        }
        for province in document["provinceRecords"]:
            province["parentRegionId"] = commandery_ids.get(
                province["parentRegionId"], province["parentRegionId"]
            )
            if province["jurisdictionId"] == "J-A":
                province["jurisdictionId"] = "45277"
        for jurisdiction in document["jurisdictionRecords"]:
            jurisdiction["commanderyId"] = commandery_ids.get(
                jurisdiction["commanderyId"], jurisdiction["commanderyId"]
            )
            if jurisdiction["id"] == "J-A":
                jurisdiction.update(id="45277", displayName="영양현", nameCh="宁阳县")
        for commandery in document["commanderyRecords"]:
            commandery["id"] = commandery_ids.get(commandery["id"], commandery["id"])
            commandery["jurisdictionIds"] = [
                "45277" if value == "J-A" else value
                for value in commandery["jurisdictionIds"]
            ]
            if commandery["id"] in commandery_names:
                commandery["displayName"], commandery["nameCh"] = commandery_names[commandery["id"]]
        for parent in document["parentRegions"]:
            parent["id"] = commandery_ids.get(parent["id"], parent["id"])
            if parent["id"] in commandery_names:
                parent["displayName"], parent["nameCh"] = commandery_names[parent["id"]]

        ledger = {
            "schemaVersion": 1,
            "ledgerId": "han-jurisdiction-commandery-adjudications-v1",
            "referenceYear": 220,
            "adjudications": [{
                "jurisdictionId": "45277",
                "jurisdictionNameCh": "宁阳县",
                "fromCommanderyId": "PARENT-0028",
                "fromCommanderyNameCh": "山陽郡",
                "toCommanderyId": "PARENT-0024",
                "toCommanderyNameCh": "東平國",
                "reviewState": "APPROVED_EXACT_PARENT",
                "evidenceRefs": ["shiliao:test"],
            }],
        }
        before = copy.deepcopy(document)

        result = self.materialize_with_ledger(document, ledger)

        jurisdiction = next(row for row in result["jurisdictionRecords"] if row["id"] == "45277")
        self.assertEqual("PARENT-0024", jurisdiction["commanderyId"])
        self.assertEqual(
            {"PARENT-0024"},
            {
                row["parentRegionId"]
                for row in result["provinceRecords"]
                if row["jurisdictionId"] == "45277"
            },
        )
        commanderies = {row["id"]: row for row in result["commanderyRecords"]}
        self.assertNotIn("45277", commanderies["PARENT-0028"]["jurisdictionIds"])
        self.assertIn("45277", commanderies["PARENT-0024"]["jurisdictionIds"])
        self.assertEqual(before["owner"], result["owner"])
        self.assertEqual(before["adjacency"]["county"], result["adjacency"]["county"])
        self.assertEqual(result, self.materialize_with_ledger(result, ledger))

    @staticmethod
    def parent_adjudication_fixture() -> dict:
        """3x2 grid, three commanderies; J-A (P-A, P-B) is a non-seat county of C-OLD."""
        return {
            "_meta": {
                "cols": 3, "rows": 2, "terrainLegend": {"1": "PLAIN"},
                "counts": {"provinces": 5, "adjCounty": 6, "adjCommandery": 2},
            },
            "terrain": ["111", "111"],
            # row0: P-OLD P-A P-NEW · row1: P-FAR P-B P-NEW
            "owner": [[0, 1], [1, 1], [2, 1], [3, 1], [4, 1], [2, 1]],
            # C-OLD=0 C-NEW=1 C-FAR=2
            "parentOwner": [[0, 2], [1, 1], [2, 1], [0, 1], [1, 1]],
            "adjacency": {
                "county": [
                    {"a": 0, "b": 1, "cells": 1}, {"a": 0, "b": 3, "cells": 1},
                    {"a": 1, "b": 2, "cells": 1}, {"a": 1, "b": 4, "cells": 1},
                    {"a": 2, "b": 4, "cells": 1}, {"a": 3, "b": 4, "cells": 1},
                ],
                "commandery": [
                    {"a": 0, "b": 1, "cells": 2, "cross": "LAND"},
                    {"a": 0, "b": 2, "cells": 2, "cross": "LAND"},
                ],
            },
            "provinceRecords": [
                {
                    "id": "P-OLD", "displayName": "구군 지역", "nameCh": "舊郡區",
                    "kind": "SPATIAL_PROVINCE", "jurisdictionId": "J-OLD",
                    "parentRegionId": "C-OLD",
                },
                {
                    "id": "P-A", "displayName": "갑 지역", "nameCh": "甲區",
                    "kind": "SPATIAL_PROVINCE", "jurisdictionId": "J-A",
                    "parentRegionId": "C-OLD",
                },
                {
                    "id": "P-NEW", "displayName": "신군 지역", "nameCh": "新郡區",
                    "kind": "SPATIAL_PROVINCE", "jurisdictionId": "J-NEW",
                    "parentRegionId": "C-NEW",
                },
                {
                    "id": "P-FAR", "displayName": "원군 지역", "nameCh": "遠郡區",
                    "kind": "SPATIAL_PROVINCE", "jurisdictionId": "J-FAR",
                    "parentRegionId": "C-FAR",
                },
                {
                    "id": "P-B", "displayName": "을 지역", "nameCh": "乙區",
                    "kind": "SPATIAL_PROVINCE", "jurisdictionId": "J-A",
                    "parentRegionId": "C-OLD",
                },
            ],
            "jurisdictionRecords": [
                {
                    "id": "J-OLD", "displayName": "구군치현", "nameCh": "舊治縣",
                    "kind": "COUNTY", "commanderyId": "C-OLD",
                    "seatPlaceId": "OLD-SEAT", "provinceIds": ["P-OLD"],
                },
                {
                    "id": "J-A", "displayName": "갑현", "nameCh": "甲縣",
                    "kind": "COUNTY", "commanderyId": "C-OLD",
                    "seatPlaceId": "PLACE-A", "provinceIds": ["P-A", "P-B"],
                },
                {
                    "id": "J-NEW", "displayName": "신군치현", "nameCh": "新治縣",
                    "kind": "COUNTY", "commanderyId": "C-NEW",
                    "seatPlaceId": "NEW-SEAT", "provinceIds": ["P-NEW"],
                },
                {
                    "id": "J-FAR", "displayName": "원군치현", "nameCh": "遠治縣",
                    "kind": "COUNTY", "commanderyId": "C-FAR",
                    "seatPlaceId": "FAR-SEAT", "provinceIds": ["P-FAR"],
                },
            ],
            "commanderyRecords": [
                {
                    "id": "C-OLD", "displayName": "구군", "nameCh": "舊郡",
                    "kind": "COMMANDERY", "seatJurisdictionId": "J-OLD",
                    "jurisdictionIds": ["J-A", "J-OLD"],
                },
                {
                    "id": "C-NEW", "displayName": "신군", "nameCh": "新郡",
                    "kind": "COMMANDERY", "seatJurisdictionId": "J-NEW",
                    "jurisdictionIds": ["J-NEW"],
                },
                {
                    "id": "C-FAR", "displayName": "원군", "nameCh": "遠郡",
                    "kind": "COMMANDERY", "seatJurisdictionId": "J-FAR",
                    "jurisdictionIds": ["J-FAR"],
                },
            ],
            "parentRegions": [
                {"id": "C-OLD", "displayName": "구군", "nameCh": "舊郡"},
                {"id": "C-NEW", "displayName": "신군", "nameCh": "新郡"},
                {"id": "C-FAR", "displayName": "원군", "nameCh": "遠郡"},
            ],
            "juns": [{"seat": 0}, {"seat": 1}, {"seat": 2}],
            "cities": [
                {"id": "OLD-SEAT", "row": 0, "col": 0},
                {"id": "NEW-SEAT", "row": 0, "col": 2},
                {"id": "FAR-SEAT", "row": 1, "col": 0},
            ],
        }

    @staticmethod
    def parent_adjudication_ledger(**overrides: object) -> dict:
        row = {
            "jurisdictionId": "J-A",
            "jurisdictionNameCh": "甲縣",
            "fromCommanderyId": "C-OLD",
            "fromCommanderyNameCh": "舊郡",
            "toCommanderyId": "C-NEW",
            "toCommanderyNameCh": "新郡",
            "reviewState": "APPROVED_EXACT_PARENT",
            "evidenceRefs": ["test:evidence"],
        }
        row.update(overrides)
        return {
            "schemaVersion": 1,
            "ledgerId": "han-jurisdiction-commandery-adjudications-v1",
            "referenceYear": 220,
            "adjudications": [row],
        }

    def materialize_with_ledger(self, document: dict, ledger: dict) -> dict:
        with mock.patch(
            "tools.map.materialize_province_jurisdictions.validate_jurisdiction_recovery_document",
            return_value=[],
        ):
            return materialize_document(
                document, {"recoveries": []}, parent_adjudications_document=ledger,
            )

    def test_reviewed_parent_adjudication_reparents_the_whole_jurisdiction(self) -> None:
        document = self.parent_adjudication_fixture()

        result = self.materialize_with_ledger(document, self.parent_adjudication_ledger())

        jurisdictions = {record["id"]: record for record in result["jurisdictionRecords"]}
        self.assertEqual("C-NEW", jurisdictions["J-A"]["commanderyId"])
        self.assertEqual(
            {"C-NEW"},
            {
                record["parentRegionId"]
                for record in result["provinceRecords"]
                if record["jurisdictionId"] == "J-A"
            },
        )
        commanderies = {record["id"]: record for record in result["commanderyRecords"]}
        self.assertEqual(["J-OLD"], commanderies["C-OLD"]["jurisdictionIds"])
        self.assertEqual(["J-A", "J-NEW"], commanderies["C-NEW"]["jurisdictionIds"])
        # Every cell of P-A and P-B now carries C-NEW; nothing else moved.
        self.assertEqual([[0, 1], [1, 2], [2, 1], [1, 2]], result["parentOwner"])
        self.assertEqual(document["owner"], result["owner"])
        self.assertEqual(document["adjacency"]["county"], result["adjacency"]["county"])
        # The commandery graph is re-derived: shared edges recounted, the crossing
        # verdict of a surviving pair preserved, a newly touching pair derived.
        self.assertEqual(
            [
                {"a": 0, "b": 1, "cells": 1, "cross": "LAND"},
                {"a": 0, "b": 2, "cells": 1, "cross": "LAND"},
                {"a": 1, "b": 2, "cells": 1, "cross": "LAND"},
            ],
            result["adjacency"]["commandery"],
        )
        self.assertEqual(3, result["_meta"]["counts"]["adjCommandery"])
        self.assertEqual(6, result["_meta"]["counts"]["adjCounty"])
        # Idempotent: re-materializing the result with the same ledger is a fixed point.
        self.assertEqual(result, self.materialize_with_ledger(result, self.parent_adjudication_ledger()))

    def test_parent_adjudication_refuses_to_move_a_commandery_seat(self) -> None:
        document = self.parent_adjudication_fixture()
        ledger = self.parent_adjudication_ledger(jurisdictionId="J-OLD", jurisdictionNameCh="舊治縣")

        with self.assertRaisesRegex(ValueError, "seat"):
            self.materialize_with_ledger(document, ledger)

    def test_parent_adjudication_refuses_an_unknown_jurisdiction_id(self) -> None:
        ledger = self.parent_adjudication_ledger(
            jurisdictionId="J-UNKNOWN", jurisdictionNameCh="未知縣",
        )

        with self.assertRaisesRegex(ValueError, "unknown jurisdiction: J-UNKNOWN"):
            self.materialize_with_ledger(self.parent_adjudication_fixture(), ledger)

    def test_parent_adjudication_refuses_an_unknown_source_commandery_id(self) -> None:
        ledger = self.parent_adjudication_ledger(
            fromCommanderyId="C-UNKNOWN", fromCommanderyNameCh="未知郡",
        )

        with self.assertRaisesRegex(ValueError, "unknown commandery: J-A"):
            self.materialize_with_ledger(self.parent_adjudication_fixture(), ledger)

    def test_parent_adjudication_refuses_an_unknown_target_commandery_id(self) -> None:
        ledger = self.parent_adjudication_ledger(
            toCommanderyId="C-UNKNOWN", toCommanderyNameCh="未知郡",
        )

        with self.assertRaisesRegex(ValueError, "unknown commandery: J-A"):
            self.materialize_with_ledger(self.parent_adjudication_fixture(), ledger)

    def test_parent_adjudication_refuses_name_drift_and_unknown_current_parent(self) -> None:
        for overrides, pattern in (
            ({"jurisdictionNameCh": "錯縣"}, "name drift"),
            ({"fromCommanderyNameCh": "錯郡"}, "name drift"),
            ({"toCommanderyNameCh": "錯郡"}, "name drift"),
            ({"fromCommanderyId": "C-FAR", "fromCommanderyNameCh": "遠郡"}, "current parent"),
        ):
            with self.subTest(overrides=overrides), self.assertRaisesRegex(ValueError, pattern):
                self.materialize_with_ledger(
                    self.parent_adjudication_fixture(),
                    self.parent_adjudication_ledger(**overrides),
                )

    def test_committed_parent_owner_and_commandery_graph_follow_the_materialized_parents(self) -> None:
        root = Path(__file__).resolve().parents[3]
        tiles = json.loads((root / "data/map/han-tiles.json").read_text(encoding="utf-8"))
        cols, rows = tiles["_meta"]["cols"], tiles["_meta"]["rows"]

        def expand(runs: list[list[int]]) -> np.ndarray:
            values = np.repeat([run[0] for run in runs], [run[1] for run in runs])
            self.assertEqual(cols * rows, values.size)
            return values.reshape(rows, cols)

        owner = expand(tiles["owner"])
        parent_owner = expand(tiles["parentOwner"])
        parent_index = {row["id"]: index for index, row in enumerate(tiles["parentRegions"])}
        parent_of_province = np.array([
            parent_index[row["parentRegionId"]] for row in tiles["provinceRecords"]
        ])
        expected = np.where(owner >= 0, parent_of_province[np.maximum(owner, 0)], -1)
        stale = np.argwhere(parent_owner != expected)
        self.assertEqual(0, len(stale), f"parentOwner disagrees with provinceRecords at {stale[:5].tolist()}")

        edges = {(edge["a"], edge["b"]): edge["cells"] for edge in tiles["adjacency"]["commandery"]}
        self.assertEqual(dict(touching_pairs(parent_owner)), edges)
        self.assertEqual(len(edges), tiles["_meta"]["counts"]["adjCommandery"])
        self.assertEqual(len(tiles["adjacency"]["county"]), tiles["_meta"]["counts"]["adjCounty"])

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
