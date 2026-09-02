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


TILES = ROOT / "data" / "map" / "han-tiles.json"
UNITS = ROOT / "data" / "curated" / "han" / "administrative-units.json"
BINDINGS = ROOT / "data" / "curated" / "han" / "administrative-place-bindings-v1.json"
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
    def test_detects_disconnected_and_fully_enclosed_components(self) -> None:
        result = audit_document(synthetic_document(), {"groups": []}, {"administrativeUnits": []})

        disconnected = {row["id"]: row for row in result["jurisdictionTopology"]["disconnected"]}
        self.assertEqual([3, 1], disconnected["J-A"]["componentCellCounts"])
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

    def test_ailao_ethnic_region_and_county_must_remain_distinct(self) -> None:
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

        validate_ailao_layers(document)

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

    def test_canonical_snapshot_is_current_and_complete(self) -> None:
        result = subprocess.run(
            [
                sys.executable,
                str(MAP_TOOLS / "audit_han_admin_topology.py"),
                "--tiles", str(TILES),
                "--units", str(UNITS),
                "--bindings", str(BINDINGS),
                "--output", str(SNAPSHOT),
                "--check",
            ],
            cwd=ROOT,
            text=True,
            capture_output=True,
        )

        self.assertEqual(0, result.returncode, result.stderr or result.stdout)
        snapshot = json.loads(SNAPSHOT.read_text(encoding="utf-8"))
        self.assertEqual(32, snapshot["provinceTopology"]["disconnectedCount"])
        self.assertEqual(15, snapshot["provinceTopology"]["fullyEnclosedCount"])
        self.assertEqual(0, snapshot["provinceTopology"]["belowMinimumCount"])
        self.assertEqual(34, snapshot["jurisdictionTopology"]["disconnectedCount"])
        self.assertEqual(14, snapshot["jurisdictionTopology"]["fullyEnclosedCount"])
        self.assertEqual(47, snapshot["commanderyTopology"]["disconnectedCount"])
        self.assertEqual(15, snapshot["commanderyTopology"]["fullyEnclosedCount"])
        self.assertEqual(73, snapshot["singleJurisdictionCommanderyCount"])

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
            for source, name in ((TILES, "tiles.json"), (UNITS, "units.json"), (BINDINGS, "bindings.json")):
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
            materialize(TILES, UNITS, BINDINGS),
            first_result,
        )
        self.assertNotIn("path", first_result["inputs"]["hanTiles"])


if __name__ == "__main__":
    unittest.main()
