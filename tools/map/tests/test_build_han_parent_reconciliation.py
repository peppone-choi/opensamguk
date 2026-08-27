import copy
import hashlib
import importlib.util
import json
import re
import subprocess
import sys
import tempfile
import unittest
from collections import Counter
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
MODULE_PATH = ROOT / "tools/map/build_han_parent_reconciliation.py"


def load_module():
    spec = importlib.util.spec_from_file_location("build_han_parent_reconciliation", MODULE_PATH)
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


def expand_rle(runs):
    return [value for value, count in runs for _ in range(count)]


def encode_rle(values):
    runs = []
    for value in values:
        if runs and runs[-1][0] == value:
            runs[-1][1] += 1
        else:
            runs.append([value, 1])
    return runs


class GeneratorPresenceTest(unittest.TestCase):
    def test_cli_exposes_write_and_check_modes(self):
        result = subprocess.run(
            [sys.executable, str(MODULE_PATH), "--help"],
            cwd=ROOT,
            text=True,
            capture_output=True,
            check=False,
        )

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("--write", result.stdout)
        self.assertIn("--check", result.stdout)


@unittest.skipUnless(MODULE_PATH.exists(), "generator is intentionally absent during RED")
class HanParentReconciliationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.module = load_module()
        cls.documents, cls.input_records = cls.module.load_inputs()
        cls.ledger = cls.module.build_ledger(cls.documents, cls.input_records)
        cls.rows = {row["cityId"]: row for row in cls.ledger["rows"]}

    def test_exact_selection_terminal_id_is_the_only_approval_join(self):
        row = self.rows["70623"]

        self.assertEqual("EXACT_APPROVED", row["decision"])
        self.assertEqual("hhs:109:京兆尹:001", row["approvedParentAdministrativeUnitId"])
        self.assertEqual("chgis:v6:cnty:70623", row["approvalEvidence"]["physicalPlaceRef"])
        self.assertEqual(
            [
                "routeNodeSelection.reviewState",
                "routeNodeSelection.physicalPlaceRefTerminal",
                "hanTiles.cities.id",
            ],
            row["approvalEvidence"]["inputs"],
        )

    def test_rows_are_exhaustive_disjoint_and_match_locked_counts(self):
        summary = self.ledger["summary"]
        decisions = Counter(row["decision"] for row in self.ledger["rows"])
        decision_cells = Counter()
        for row in self.ledger["rows"]:
            decision_cells[row["decision"]] += row["cellCount"]

        self.assertEqual(1_138, len(self.ledger["rows"]))
        self.assertEqual(1_138, len(self.rows))
        self.assertEqual(332_914, sum(row["cellCount"] for row in self.ledger["rows"]))
        self.assertEqual(
            {
                "EXACT_APPROVED": 778,
                "PROPOSED_GEOMETRIC": 279,
                "BLOCKED_DIRECT_TERRITORY_REVIEW": 41,
                "BLOCKED_EXTERNAL_POLITY_REVIEW": 40,
            },
            dict(decisions),
        )
        self.assertEqual(
            {
                "EXACT_APPROVED": 199_859,
                "PROPOSED_GEOMETRIC": 65_998,
                "BLOCKED_DIRECT_TERRITORY_REVIEW": 20_987,
                "BLOCKED_EXTERNAL_POLITY_REVIEW": 46_070,
            },
            dict(decision_cells),
        )
        self.assertEqual(360, summary["unresolvedRowCount"])
        self.assertEqual(133_055, summary["unresolvedCellCount"])
        self.assertEqual(
            {"rowCount": 163, "cellCount": 45_843},
            summary["geometryDiagnostics"]["singleGroupJun"],
        )
        self.assertEqual(
            {"rowCount": 116, "cellCount": 20_155},
            summary["geometryDiagnostics"]["multiGroupJun"],
        )

    def test_geometry_retains_all_five_exact_distance_ties(self):
        tied = {
            row["cityId"]: row
            for row in self.ledger["rows"]
            if row.get("geometryDiagnostic", {}).get("distanceTie")
        }

        self.assertEqual({"210872", "70647", "41305", "32054", "X008"}, set(tied))
        self.assertEqual(349, sum(row["cellCount"] for row in tied.values()))
        self.assertEqual(
            {"87111", "87128"},
            {
                candidate["anchorCityId"]
                for candidate in tied["X008"]["geometryDiagnostic"]["nearestCandidates"]
            },
        )
        self.assertEqual(
            [20, 20],
            [
                candidate["squaredGridDistance"]
                for candidate in tied["X008"]["geometryDiagnostic"]["nearestCandidates"]
            ],
        )
        self.assertEqual(
            {"rowCount": 274, "cellCount": 65_649},
            self.ledger["summary"]["geometryDiagnostics"]["uniqueNearest"],
        )
        self.assertEqual(
            {"rowCount": 5, "cellCount": 349},
            self.ledger["summary"]["geometryDiagnostics"]["distanceTies"],
        )
        for row in self.ledger["rows"]:
            candidates = row.get("geometryDiagnostic", {}).get("nearestCandidates", [])
            self.assertEqual(
                candidates,
                sorted(
                    candidates,
                    key=lambda candidate: (
                        candidate["anchorCityId"],
                        candidate["candidateAdministrativeUnitId"],
                    ),
                ),
            )

    def test_cross_jun_footprints_and_coordinate_majority_mismatches_are_surfaced(self):
        cross_jun = [row for row in self.ledger["rows"] if row["footprintDiagnostic"]["crossJun"]]
        mismatches = [
            row
            for row in self.ledger["rows"]
            if not row["footprintDiagnostic"]["coordinateMatchesFootprintMajority"]
        ]

        self.assertEqual(99, len(cross_jun))
        self.assertEqual(
            {"95125", "95280", "45796", "44320", "41374", "211231", "210791"},
            {row["cityId"] for row in mismatches},
        )
        self.assertEqual(7, len(mismatches))
        self.assertTrue(all(row["footprintDiagnostic"]["diagnosticOnly"] for row in cross_jun))

    def test_non_exact_rows_never_carry_approved_parent_or_forbidden_approval_inputs(self):
        forbidden = {
            "junName",
            "junArrayIndex",
            "cityArrayIndex",
            "HanCityConst",
            "HanGateIndex",
            "nearestGeometry",
            "runtimeNumericId",
        }

        self.assertEqual(forbidden, set(self.ledger["policy"]["forbiddenApprovalInputs"]))
        for row in self.ledger["rows"]:
            if row["decision"] != "EXACT_APPROVED":
                self.assertNotIn("approvedParentAdministrativeUnitId", row)
                self.assertNotIn("approvalEvidence", row)
            if "geometryDiagnostic" in row:
                self.assertTrue(row["geometryDiagnostic"]["diagnosticOnly"])
            self.assertNotIn("legacyCityId", row)
            self.assertNotIn("numericCityId", row)

    def test_two_approved_physical_ids_absent_from_tiles_are_preserved_separately(self):
        self.assertEqual(
            [
                {
                    "administrativeUnitId": "hhs:110:清河國:003",
                    "physicalPlaceRef": "chgis:v6:cnty:85168",
                    "terminalPhysicalPlaceId": "85168",
                },
                {
                    "administrativeUnitId": "hhs:111:東海郡:009",
                    "physicalPlaceRef": "chgis:v6:cnty:42901",
                    "terminalPhysicalPlaceId": "42901",
                },
            ],
            self.ledger["approvedPhysicalPlaceIdsAbsentFromTiles"],
        )

    def test_external_pending_and_disputed_candidates_remain_blocked(self):
        rows = [
            row
            for row in self.ledger["rows"]
            if row["decision"] == "BLOCKED_EXTERNAL_POLITY_REVIEW"
        ]
        without_exact_external_candidate = {
            row["cityId"]
            for row in rows
            if row["externalReview"]["cityExternalPlaceCandidate"] is None
        }
        disputed = [
            row
            for row in rows
            if (row["externalReview"].get("cityExternalPlaceCandidate") or {}).get("confidence")
            == "DISPUTED"
        ]

        self.assertEqual(40, len(rows))
        self.assertEqual({"211791", "87125"}, without_exact_external_candidate)
        self.assertTrue(disputed)
        self.assertTrue(
            all(row["externalReview"]["reviewState"] == "PENDING_EXTERNAL_POLITY_REVIEW" for row in rows)
        )
        self.assertTrue(all("approvedParentAdministrativeUnitId" not in row for row in rows))

    def test_direct_territory_is_rejected_under_six_already_sourced_hhs_groups(self):
        rows = [
            row
            for row in self.ledger["rows"]
            if row["decision"] == "BLOCKED_DIRECT_TERRITORY_REVIEW"
        ]
        rejected = [
            row
            for row in rows
            if row["directTerritoryReview"]["reviewState"]
            == "REJECTED_SOURCED_COUNTIES_ALREADY_EXIST"
        ]

        self.assertEqual(
            {"廣漢屬國", "張掖屬國", "上郡", "西河郡", "定襄郡", "朔方郡"},
            {row["seatJunDiagnostic"]["nameCh"] for row in rejected},
        )
        self.assertTrue(
            all(row["directTerritoryReview"]["sourcedAdministrativeUnitIds"] for row in rejected)
        )
        self.assertTrue(all("approvedParentAdministrativeUnitId" not in row for row in rows))
        self.assertEqual(
            {"rejectedSourcedGroupJunCount": 6, "pendingCandidateJunCount": 26},
            self.ledger["summary"]["directTerritoryReview"],
        )

    def test_input_paths_and_hashes_are_pinned_and_staleness_fails_check(self):
        self.assertEqual(set(self.module.INPUT_PATHS), set(self.ledger["inputs"]))
        for path, record in self.ledger["inputs"].items():
            self.assertEqual(path, record["path"])
            self.assertRegex(record["sha256"], r"^[0-9a-f]{64}$")
            self.assertEqual(
                hashlib.sha256(self.module.INPUT_PATHS[path].read_bytes()).hexdigest(),
                record["sha256"],
            )

        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "ledger.json"
            self.module.write_ledger(output_path=output)
            self.assertTrue(self.module.check_ledger(output_path=output))

            history_key = "data/map/han-administrative-history.json"
            stale_history = Path(directory) / "han-administrative-history.json"
            stale_history.write_bytes(self.module.INPUT_PATHS[history_key].read_bytes() + b"\n")
            stale_paths = dict(self.module.INPUT_PATHS)
            stale_paths[history_key] = stale_history
            self.assertFalse(self.module.check_ledger(output_path=output, input_paths=stale_paths))

    def test_array_order_changes_do_not_change_decisions_or_row_order(self):
        documents = copy.deepcopy(self.documents)
        tiles = documents["data/map/han-tiles.json"]
        old_cities = tiles["cities"]
        old_owner = expand_rle(tiles["owner"])
        reordered_cities = list(reversed(old_cities))
        new_index_by_id = {str(city["id"]): index for index, city in enumerate(reordered_cities)}
        tiles["cities"] = reordered_cities
        tiles["owner"] = encode_rle(
            [-1 if index < 0 else new_index_by_id[str(old_cities[index]["id"])] for index in old_owner]
        )

        for path, key in [
            ("data/map/external-places.json", "places"),
            ("data/curated/han/route-node-selection-v1.json", "routeNodes"),
            ("data/curated/han/administrative-place-bindings-v1.json", "administrativeUnits"),
            ("data/curated/han/route-node-location-adjudications-v1.json", "adjudications"),
            ("data/curated/han/route-node-selection-candidates-v1.json", "candidates"),
        ]:
            documents[path][key].reverse()

        reordered = self.module.build_ledger(documents, self.input_records)

        self.assertEqual(self.ledger["rows"], reordered["rows"])
        self.assertEqual(
            [row["cityId"] for row in reordered["rows"]],
            sorted(self.rows),
        )

    def test_write_and_check_are_byte_identical(self):
        expected = self.module.render_ledger()
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "ledger.json"
            written = self.module.write_ledger(output_path=output)

            self.assertEqual(expected, written)
            self.assertEqual(expected, output.read_bytes())
            self.assertTrue(self.module.check_ledger(output_path=output))
            output.write_bytes(expected + b" ")
            self.assertFalse(self.module.check_ledger(output_path=output))


if __name__ == "__main__":
    unittest.main()
