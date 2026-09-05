#!/usr/bin/env python3
from __future__ import annotations

import copy
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
MAP_TOOLS = ROOT / "tools" / "map"
sys.path.insert(0, str(MAP_TOOLS))

from audit_han_admin_topology import (  # noqa: E402
    audit_document,
    materialize,
    validate_ailao_layers,
)
from build_administrative_place_overlay import build_overlay  # noqa: E402


TILES = ROOT / "data" / "map" / "han-tiles.json"
UNITS = ROOT / "data" / "curated" / "han" / "administrative-units.json"
BINDINGS = ROOT / "data" / "curated" / "han" / "administrative-place-bindings-v1.json"
EXTERNAL_POLICY = ROOT / "data" / "curated" / "han" / "external-region-hierarchy-policy-v1.json"
SNAPSHOT = ROOT / "data" / "curated" / "han" / "administrative-topology-audit-v1.json"


def rle(values: list[int]) -> list[list[int]]:
    runs: list[list[int]] = []
    for value in values:
        if runs and runs[-1][0] == value:
            runs[-1][1] += 1
        else:
            runs.append([value, 1])
    return runs


def synthetic_document() -> dict:
    # A(0) is split into a main component and a one-cell component enclosed by B(1).
    # C(2) is one connected jurisdiction and is the sole child of commandery C.
    grid = [
        -1, -1, -1, -1, -1, -1,
        -1, 0, 0, 1, 1, -1,
        -1, 0, 1, 1, 1, -1,
        -1, 1, 1, 0, 1, -1,
        -1, 2, 2, 1, 1, -1,
        -1, -1, -1, -1, -1, -1,
    ]
    return {
        "_meta": {"rows": 6, "cols": 6},
        "owner": rle(grid),
        "provinceRecords": [
            {"id": "P-A", "displayName": "가 프로빈스", "jurisdictionId": "J-A"},
            {"id": "P-B", "displayName": "나 프로빈스", "jurisdictionId": "J-B"},
            {"id": "P-C", "displayName": "다 프로빈스", "jurisdictionId": "J-C"},
        ],
        "jurisdictionRecords": [
            {"id": "J-A", "displayName": "가현", "nameCh": "甲县", "commanderyId": "C-A", "provinceIds": ["P-A"]},
            {"id": "J-B", "displayName": "나현", "nameCh": "乙县", "commanderyId": "C-B", "provinceIds": ["P-B"]},
            {"id": "J-C", "displayName": "다현", "nameCh": "丙县", "commanderyId": "C-C", "provinceIds": ["P-C"]},
        ],
        "commanderyRecords": [
            {"id": "C-A", "displayName": "가군", "nameCh": "甲郡", "kind": "COMMANDERY", "jurisdictionIds": ["J-A"]},
            {"id": "C-B", "displayName": "나군", "nameCh": "乙郡", "kind": "COMMANDERY", "jurisdictionIds": ["J-B"]},
            {"id": "C-C", "displayName": "다군", "nameCh": "丙郡", "kind": "COMMANDERY", "jurisdictionIds": ["J-C"]},
        ],
        "cities": [],
    }


class HanAdminTopologyAuditTest(unittest.TestCase):
    def test_reviewed_binding_selects_an_existing_physical_place_without_name_inference(self) -> None:
        catalog = {
            "schemaVersion": 1,
            "catalogId": "fixture",
            "expectedGroupCount": 1,
            "expectedUnitCount": 1,
            "detectedGroupCount": 1,
            "detectedUnitCount": 1,
            "groups": [{
                "sourceVolume": 112,
                "canonicalGroup": "濟南國",
                "units": [{
                    "sourceVolume": 112,
                    "canonicalGroup": "濟南國",
                    "ordinal": 1,
                    "sourceName": "歷城",
                    "sourceNameStatus": "SOURCE_LITERAL",
                    "unitType": "COUNTY",
                    "sourceCitation": {"corpusPath": "fixture", "line": 1},
                }],
            }],
        }
        records = [{
            "recordIndex": 1,
            "SYS_ID": "45022",
            "NAME_CH": "历城县",
            "NAME_FT": "",
            "X_COOR": 117.00031,
            "Y_COOR": 36.64912,
            "BEG_YR": 100,
            "END_YR": 300,
            "PRES_LOC": "fixture",
        }]
        reviewed = {
            "schemaVersion": 1,
            "catalogId": "fixture",
            "sourceYear": 220,
            "administrativeUnits": [{
                "administrativeUnitId": "hhs:112:濟南國:001",
                "candidateCount": 1,
                "identity": {"sourceVolume": 112, "canonicalGroup": "濟南國", "ordinal": 1},
                "joinStatus": "RESOLVED_POINT",
                "selectedCandidate": {"physicalPlaceId": "chgis:v6:cnty:45022"},
            }],
        }

        result = build_overlay(catalog, records, reviewed_bindings=reviewed)

        row = result["administrativeUnits"][0]
        self.assertEqual("RESOLVED_POINT", row["joinStatus"])
        self.assertEqual("chgis:v6:cnty:45022", row["selectedCandidate"]["physicalPlaceId"])

    def test_committed_licheng_binding_matches_the_corrected_current_parent(self) -> None:
        result = materialize(TILES, UNITS, BINDINGS, EXTERNAL_POLICY)
        census = result["historicalParentCensus"]
        mismatch_ids = {
            (
                row["sourceVolume"],
                row["sourceCommanderyNameCh"],
                row["ordinal"],
                row["currentJurisdictionId"],
            )
            for row in census["sourceParentMismatches"]
        }

        self.assertNotIn((112, "濟南國", 10, "45022"), mismatch_ids)
        jinan = next(
            row
            for row in census["sourceGroups"]
            if row["sourceVolume"] == 112 and row["sourceCommanderyNameCh"] == "濟南國"
        )
        self.assertEqual(
            {"NO_COORDINATE_CANDIDATE": 1, "RESOLVED_POINT": 9},
            jinan["bindingStatusCounts"],
        )
        self.assertEqual(9, jinan["resolvedCurrentJurisdictionCount"])
        self.assertEqual(9, jinan["sourceParentMatchCount"])

    def test_detects_disconnected_and_fully_enclosed_components(self) -> None:
        result = audit_document(synthetic_document(), {"groups": []}, {"administrativeUnits": []})

        disconnected = {row["id"]: row for row in result["jurisdictionTopology"]["disconnected"]}
        self.assertEqual([3, 1], disconnected["J-A"]["componentCellCounts"])
        self.assertEqual(
            [
                {"cellCount": 3, "memberIds": ["P-A"]},
                {"cellCount": 1, "memberIds": ["P-A"]},
            ],
            disconnected["J-A"]["components"],
        )
        commandery_disconnected = {
            row["id"]: row for row in result["commanderyTopology"]["disconnected"]
        }
        self.assertEqual(
            [
                {"cellCount": 3, "memberIds": ["J-A"]},
                {"cellCount": 1, "memberIds": ["J-A"]},
            ],
            commandery_disconnected["C-A"]["components"],
        )
        province_disconnected = {row["id"]: row for row in result["provinceTopology"]["disconnected"]}
        self.assertEqual([3, 1], province_disconnected["P-A"]["componentCellCounts"])
        province_enclosed = {
            (row["id"], row["componentCellCount"]): row
            for row in result["provinceTopology"]["fullyEnclosed"]
        }
        self.assertEqual(["P-B"], province_enclosed[("P-A", 1)]["surroundingIds"])

        enclosed = {
            (row["id"], row["componentCellCount"]): row
            for row in result["jurisdictionTopology"]["fullyEnclosed"]
        }
        self.assertEqual(["J-B"], enclosed[("J-A", 1)]["surroundingIds"])

    def test_rejects_duplicate_spatial_province_ids(self) -> None:
        document = synthetic_document()
        duplicate = copy.deepcopy(document["provinceRecords"][0])
        document["provinceRecords"].append(duplicate)

        with self.assertRaisesRegex(ValueError, "provinceRecords contains duplicate IDs"):
            audit_document(document, {"groups": []}, {"administrativeUnits": []})

    def test_single_jurisdiction_commandery_includes_source_and_binding_evidence(self) -> None:
        units = {
            "groups": [{
                "canonicalGroup": "丙郡",
                "sourceVolume": 113,
                "units": [
                    {"sourceVolume": 113, "canonicalGroup": "丙郡", "ordinal": 1, "sourceName": "丙"},
                    {"sourceVolume": 113, "canonicalGroup": "丙郡", "ordinal": 2, "sourceName": "丁"},
                ],
            }],
        }
        bindings = {
            "administrativeUnits": [
                {"identity": {"sourceVolume": 113, "canonicalGroup": "丙郡", "ordinal": 1}, "joinStatus": "RESOLVED_POINT", "selectedCandidate": {"physicalPlaceId": "chgis:v6:cnty:1"}},
                {"identity": {"sourceVolume": 113, "canonicalGroup": "丙郡", "ordinal": 2}, "joinStatus": "NO_COORDINATE_CANDIDATE"},
            ],
        }

        result = audit_document(synthetic_document(), units, bindings)
        commandery = next(row for row in result["singleJurisdictionCommanderies"] if row["id"] == "C-C")

        self.assertEqual(2, commandery["sourceEnumeratedUnitCount"])
        self.assertEqual({"RESOLVED_POINT": 1, "NO_COORDINATE_CANDIDATE": 1}, commandery["sourceBindingStatusCounts"])
        self.assertEqual(["丁"], commandery["sourceUnitsWithoutCoordinateCandidate"])
        self.assertEqual([], result["ethnicAdministrativeCoexistence"])

    def test_ailao_ethnic_region_and_county_are_reported_as_same_lineage(self) -> None:
        document = synthetic_document()
        document["cities"] = [
            {"id": "X060", "name": "남만", "nameCh": "哀牢", "kind": "EXTERNAL_PLACE"},
            {"id": "80004", "name": "애뢰현", "nameCh": "哀牢县", "kind": "COUNTY"},
        ]
        document["jurisdictionRecords"].extend([
            {"id": "X060", "displayName": "남만", "nameCh": "哀牢", "kind": "EXTERNAL_PLACE", "commanderyId": "C-ETHNIC", "provinceIds": []},
            {"id": "80004", "displayName": "애뢰현", "nameCh": "哀牢县", "kind": "COUNTY", "commanderyId": "C-COUNTY", "provinceIds": []},
        ])
        document["commanderyRecords"].extend([
            {"id": "C-ETHNIC", "displayName": "남만", "nameCh": "哀牢", "kind": "EXTERNAL_POLITY", "jurisdictionIds": ["X060"]},
            {"id": "C-COUNTY", "displayName": "영창군", "nameCh": "永昌郡", "kind": "COMMANDERY", "jurisdictionIds": ["80004"]},
        ])

        lineage = validate_ailao_layers(document)

        self.assertEqual("SAME_LINEAGE_DO_NOT_RENDER_AS_PEERS", lineage["decision"])
        self.assertEqual("남만", lineage["recommendedMacroRegionDisplayName"])
        self.assertEqual("애뢰", lineage["recommendedJurisdictionDisplayName"])
        self.assertTrue(lineage["politicalOwnershipSeparate"])
        self.assertTrue(lineage["recruitmentEligibilitySeparate"])

        merged = copy.deepcopy(document)
        merged["jurisdictionRecords"] = [row for row in merged["jurisdictionRecords"] if row["id"] != "80004"]
        with self.assertRaisesRegex(ValueError, "哀牢.*哀牢县"):
            validate_ailao_layers(merged)

        dangling = copy.deepcopy(document)
        dangling["commanderyRecords"] = [
            row for row in dangling["commanderyRecords"] if row["id"] != "C-ETHNIC"
        ]
        with self.assertRaisesRegex(ValueError, "哀牢.*哀牢县"):
            validate_ailao_layers(dangling)

    def test_historical_parent_census_reports_every_resolved_binding(self) -> None:
        units = {
            "groups": [{
                "canonicalGroup": "乙郡",
                "sourceVolume": 112,
                "units": [
                    {"sourceVolume": 112, "canonicalGroup": "乙郡", "ordinal": 1, "sourceName": "甲"},
                    {"sourceVolume": 112, "canonicalGroup": "乙郡", "ordinal": 2, "sourceName": "丁"},
                ],
            }],
        }
        bindings = {
            "administrativeUnits": [
                {"identity": {"sourceVolume": 112, "canonicalGroup": "乙郡", "ordinal": 1}, "joinStatus": "RESOLVED_POINT", "selectedCandidate": {"physicalPlaceId": "chgis:v6:cnty:J-A"}},
                {"identity": {"sourceVolume": 112, "canonicalGroup": "乙郡", "ordinal": 2}, "joinStatus": "NO_COORDINATE_CANDIDATE"},
            ],
        }

        result = audit_document(synthetic_document(), units, bindings)
        census = result["historicalParentCensus"]

        self.assertEqual(2, census["sourceUnitCount"])
        self.assertEqual(1, census["resolvedCurrentJurisdictionCount"])
        self.assertEqual(1, census["sourceParentMismatchCount"])
        self.assertEqual(1, census["noCoordinateCandidateCount"])
        self.assertEqual(3, census["currentCommanderyCount"])
        self.assertEqual(1, census["sourceLinkedCurrentCommanderyCount"])
        self.assertEqual(
            ["C-A", "C-B", "C-C"],
            [row["currentCommanderyId"] for row in census["currentCommanderies"]],
        )
        self.assertEqual(
            {
                "sourceName": "甲",
                "sourceCommanderyNameCh": "乙郡",
                "sourceVolume": 112,
                "ordinal": 1,
                "currentJurisdictionId": "J-A",
                "currentJurisdictionDisplayName": "가현",
                "currentCommanderyId": "C-A",
                "currentCommanderyDisplayName": "가군",
                "currentCommanderyNameCh": "甲郡",
                "classification": "SOURCE_PARENT_MISMATCH_REQUIRES_PERIOD_REVIEW",
            },
            census["sourceParentMismatches"][0],
        )

    def test_jurisdiction_name_audit_reports_same_parent_duplicates_and_editor_markup(self) -> None:
        document = synthetic_document()
        document["jurisdictionRecords"].append({
            "id": "J-A2",
            "displayName": "가현",
            "nameCh": "甲县",
            "commanderyId": "C-A",
            "provinceIds": [],
        })
        document["jurisdictionRecords"][0]["displayName"] = "[가현]현"

        result = audit_document(document, {"groups": []}, {"administrativeUnits": []})
        audit = result["jurisdictionNameAudit"]

        self.assertEqual(1, audit["sourceAnnotationCount"])
        self.assertEqual(["J-A"], [row["jurisdictionId"] for row in audit["sourceAnnotations"]])
        self.assertEqual(1, audit["sameCommanderyCanonicalNameDuplicateCount"])
        self.assertEqual(["J-A", "J-A2"], audit["sameCommanderyCanonicalNameDuplicates"][0]["jurisdictionIds"])

    def test_external_region_policy_covers_every_external_jurisdiction_once(self) -> None:
        policy = {
            "schemaVersion": 1,
            "policyId": "test",
            "referenceYear": 220,
            "constraints": {
                "politicalOwnershipSeparate": True,
                "recruitmentEligibilitySeparate": True,
                "noPanEthnicKoreanPeninsulaUmbrella": True,
            },
            "macroRegions": [{
                "id": "KOREA-MAHAN",
                "displayName": "마한",
                "classification": "CONFEDERATION",
                "geographicScope": "KOREAN_PENINSULA",
                "jurisdictionIds": ["X-A"],
            }],
            "sourceBackedCandidates": [],
        }
        document = synthetic_document()
        document["jurisdictionRecords"].append({
            "id": "X-A",
            "displayName": "백제국",
            "nameCh": "伯濟國",
            "kind": "EXTERNAL_SETTLEMENT",
            "commanderyId": "C-A",
            "provinceIds": [],
        })

        result = audit_document(document, {"groups": []}, {"administrativeUnits": []}, policy)

        self.assertEqual(1, result["externalRegionHierarchy"]["coveredJurisdictionCount"])
        self.assertEqual([], result["externalRegionHierarchy"]["uncoveredJurisdictionIds"])

        bad = copy.deepcopy(policy)
        bad["macroRegions"][0]["displayName"] = "동이"
        with self.assertRaisesRegex(ValueError, "동이.*한반도"):
            audit_document(document, {"groups": []}, {"administrativeUnits": []}, bad)

        directional = copy.deepcopy(policy)
        directional["constraints"]["noDirectionalUmbrellaForNamedNorthernOrWesternPeoples"] = True
        directional["macroRegions"][0].update({
            "displayName": "북방",
            "geographicScope": "NORTHERN_FRONTIER",
        })
        with self.assertRaisesRegex(ValueError, "북방.*실명 집단"):
            audit_document(
                document,
                {"groups": []},
                {"administrativeUnits": []},
                directional,
            )

    def test_canonical_snapshot_is_current_and_complete(self) -> None:
        result = subprocess.run(
            [
                sys.executable,
                str(MAP_TOOLS / "audit_han_admin_topology.py"),
                "--tiles", str(TILES),
                "--units", str(UNITS),
                "--bindings", str(BINDINGS),
                "--external-policy", str(EXTERNAL_POLICY),
                "--output", str(SNAPSHOT),
                "--check",
            ],
            cwd=ROOT,
            text=True,
            capture_output=True,
        )

        self.assertEqual(0, result.returncode, result.stderr or result.stdout)
        snapshot = json.loads(SNAPSHOT.read_text(encoding="utf-8"))
        self.assertEqual(27, snapshot["provinceTopology"]["disconnectedCount"])
        self.assertEqual(11, snapshot["provinceTopology"]["fullyEnclosedCount"])
        self.assertEqual(0, snapshot["provinceTopology"]["belowMinimumCount"])
        self.assertEqual(29, snapshot["jurisdictionTopology"]["disconnectedCount"])
        self.assertEqual(10, snapshot["jurisdictionTopology"]["fullyEnclosedCount"])
        # 43→42: 청주 4縣 재판정(data/curated/han/jurisdiction-commandery-adjudications-v1.json)으로 西平昌(45107)·安德(85706)이
        # 平原郡(PARENT-0036)에 붙으면서 平原郡의 세 조각(399·127·36셀)이 한 면으로 이어졌다.
        # 歷城의 39셀을 濟南國으로 재판정하면 셀을 옮기지 않고도
        # 平原郡의 45121 footprint가 나머지 군에서 분리된 기존 격자 사실이 드러난다.
        self.assertEqual(43, snapshot["commanderyTopology"]["disconnectedCount"])
        self.assertEqual(10, snapshot["commanderyTopology"]["fullyEnclosedCount"])
        self.assertEqual(73, snapshot["singleJurisdictionCommanderyCount"])
        self.assertEqual(172, snapshot["historicalParentCensus"]["currentCommanderyCount"])
        self.assertEqual(38, snapshot["externalRegionHierarchy"]["coveredJurisdictionCount"])
        self.assertEqual([], snapshot["externalRegionHierarchy"]["uncoveredJurisdictionIds"])
        self.assertGreater(snapshot["historicalParentCensus"]["sourceParentMismatchCount"], 0)
        self.assertEqual(
            "SAME_LINEAGE_DO_NOT_RENDER_AS_PEERS",
            snapshot["ethnicAdministrativeCoexistence"][0]["decision"],
        )

    def test_check_fails_closed_when_snapshot_or_input_drifts(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            tiles = json.loads(TILES.read_text(encoding="utf-8"))
            tiles["commanderyRecords"][0]["displayName"] = "임의"
            tiles_path = root / "tiles.json"
            output_path = root / "snapshot.json"
            tiles_path.write_text(json.dumps(tiles), encoding="utf-8")
            output_path.write_text(SNAPSHOT.read_text(encoding="utf-8"), encoding="utf-8")

            result = subprocess.run(
                [
                    sys.executable,
                    str(MAP_TOOLS / "audit_han_admin_topology.py"),
                    "--tiles", str(tiles_path),
                    "--units", str(UNITS),
                    "--bindings", str(BINDINGS),
                    "--output", str(output_path),
                    "--check",
                ],
                cwd=ROOT,
                text=True,
                capture_output=True,
            )

        self.assertNotEqual(0, result.returncode)
        self.assertIn("audit snapshot drift", result.stderr + result.stdout)

    def test_external_input_paths_do_not_change_snapshot_identity(self) -> None:
        with tempfile.TemporaryDirectory() as first, tempfile.TemporaryDirectory() as second:
            first_root = Path(first)
            second_root = Path(second)
            first_paths: list[Path] = []
            second_paths: list[Path] = []
            for source, name in (
                (TILES, "tiles.json"),
                (UNITS, "units.json"),
                (BINDINGS, "bindings.json"),
                (EXTERNAL_POLICY, "external-policy.json"),
            ):
                payload = source.read_bytes()
                first_path = first_root / name
                second_path = second_root / name
                first_path.write_bytes(payload)
                second_path.write_bytes(payload)
                first_paths.append(first_path)
                second_paths.append(second_path)

            first_result = materialize(*first_paths)
            second_result = materialize(*second_paths)

        self.assertEqual(first_result, second_result)
        self.assertEqual(
            materialize(TILES, UNITS, BINDINGS, EXTERNAL_POLICY),
            first_result,
        )
        self.assertNotIn("path", first_result["inputs"]["hanTiles"])


if __name__ == "__main__":
    unittest.main()
