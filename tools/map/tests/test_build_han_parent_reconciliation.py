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


def replace_nested(document, keys, value):
    current = document
    for key in keys[:-1]:
        current = current[key]
    current[keys[-1]] = value


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

    def test_weiguo_uses_reviewed_exact_binding_not_nearest_dunqiu_geometry(self):
        """Removing the reviewed 85083 override would regress to the 頓丘 geometry proposal."""
        row = self.rows["85083"]

        self.assertEqual("EXACT_APPROVED", row["decision"])
        self.assertEqual("hhs:111:東郡:014", row["approvedParentAdministrativeUnitId"])
        self.assertEqual(
            "chgis:v6:cnty:85083",
            row["approvalEvidence"]["physicalPlaceRef"],
        )
        self.assertEqual(
            "REVIEWED_TEMPORAL_ADMINISTRATIVE_ADJUDICATION",
            row["approvalEvidence"]["method"],
        )

    def test_weiguo_temporal_review_contains_exact_chgis_physical_witness(self):
        temporal = self.documents[
            "data/curated/han/administrative-temporal-adjudications-v1.json"
        ]
        self.assertEqual(
            {
                "sourceId": "chgis-cnty-dbf",
                "snapshotSha256": "e782572a2af83fa246d608ffb13729835d535f3c010eee79ce0545f5430eb616",
                "locator": "SYS_ID=85083",
                "physicalPlaceId": "85083",
                "nameCh": "卫国",
                "nameFt": "衛國",
                "typeCh": "国",
                "begYr": 37,
                "endYr": 265,
                "lon": 115.11117,
                "lat": 35.88519,
                "gx": 422,
                "gy": 209,
                "junguozhiChildName": "衛",
            },
            temporal["adjudications"][0].get("physicalWitness"),
        )

    def test_temporal_physical_witness_is_an_exact_closed_contract(self):
        temporal_path = "data/curated/han/administrative-temporal-adjudications-v1.json"
        mutations = {
            "sourceId": "fake-source",
            "snapshotSha256": "0" * 64,
            "locator": "SYS_ID=85084",
            "physicalPlaceId": "85084",
            "nameCh": "錯國",
            "nameFt": "錯國",
            "typeCh": "县",
            "begYr": 38,
            "endYr": 264,
            "lon": 115.11118,
            "lat": 35.88518,
            "gx": 421,
            "gy": 208,
            "junguozhiChildName": "衞",
        }
        for field, value in mutations.items():
            documents = copy.deepcopy(self.documents)
            documents[temporal_path]["adjudications"][0]["physicalWitness"][field] = value
            with self.subTest(field=field), self.assertRaisesRegex(
                ValueError, "physical witness"
            ):
                self.module.build_ledger(documents, self.input_records)

    def test_temporal_binding_joins_catalog_binding_and_history_ids_exactly(self):
        mutations = [
            ("administrativeUnitId", "hhs:111:東郡:999"),
            ("historyChildId", "county:hhs:111:24:4"),
            ("physicalPlaceRef", "chgis:v6:cnty:not-decimal"),
            ("administrativeUnitId", "hhs:111:東郡:14"),
            ("historyChildId", "county:hhs:111:24:014"),
        ]
        for field, value in mutations:
            with self.subTest(field=field):
                documents = copy.deepcopy(self.documents)
                documents[
                    "data/curated/han/administrative-temporal-adjudications-v1.json"
                ]["adjudications"][0][field] = value
                with self.assertRaisesRegex(
                    ValueError, "catalog|binding|history|identity|stable ID"
                ):
                    self.module.build_ledger(documents, self.input_records)

    def test_temporal_contract_rejects_schema_drift_and_weak_evidence(self):
        temporal_path = "data/curated/han/administrative-temporal-adjudications-v1.json"
        mutations = [
            ("root extra", lambda document: document.update(extra=True)),
            (
                "row extra",
                lambda document: document["adjudications"][0].update(extra=True),
            ),
            (
                "evidence extra",
                lambda document: document["adjudications"][0]["identityEvidence"].update(
                    extra=True
                ),
            ),
            (
                "interval extra",
                lambda document: document["adjudications"][0]["parentIntervals"][0].update(
                    extra=True
                ),
            ),
            (
                "empty quote",
                lambda document: document["adjudications"][0]["identityEvidence"].update(
                    quote=""
                ),
            ),
            (
                "boolean line",
                lambda document: document["adjudications"][0]["identityEvidence"].update(
                    line=True
                ),
            ),
            (
                "invalid hash",
                lambda document: document["adjudications"][0]["identityEvidence"].update(
                    snapshotSha256="0"
                ),
            ),
            (
                "valid but unpinned hash",
                lambda document: document["adjudications"][0]["identityEvidence"].update(
                    snapshotSha256="0" * 64
                ),
            ),
        ]
        for label, mutate in mutations:
            with self.subTest(label=label):
                documents = copy.deepcopy(self.documents)
                mutate(documents[temporal_path])
                with self.assertRaisesRegex(
                    ValueError, "schema|keys|Evidence|evidence|line|hash|nonempty|unpinned"
                ):
                    self.module.build_ledger(documents, self.input_records)

    def test_temporal_evidence_rejects_coordinated_self_attestation(self):
        temporal_path = "data/curated/han/administrative-temporal-adjudications-v1.json"
        mutations = [
            (
                "coordinated fake witness",
                lambda document: (
                    document["sourceWitnesses"][1].update(
                        corpusPath="data/corpus/fake.txt",
                        sourceUrl="https://example.invalid/fake",
                        snapshotSha256="0" * 64,
                    ),
                    document["adjudications"][0]["parentIntervals"][1].update(
                        corpusPath="data/corpus/fake.txt",
                        sourceUrl="https://example.invalid/fake",
                        snapshotSha256="0" * 64,
                        quote="fake but nonempty",
                    ),
                ),
            ),
            (
                "generic identity claim",
                lambda document: document["adjudications"][0]["identityEvidence"].update(
                    claim="generic nonempty claim"
                ),
            ),
            (
                "weak identity quote",
                lambda document: document["adjudications"][0]["identityEvidence"].update(
                    quote="衞 fake but still names the child"
                ),
            ),
        ]
        for label, mutate in mutations:
            with self.subTest(label=label):
                documents = copy.deepcopy(self.documents)
                mutate(documents[temporal_path])
                with self.assertRaisesRegex(ValueError, "reviewed|witness|quote|claim|evidence"):
                    self.module.build_ledger(documents, self.input_records)

    def test_temporal_intervals_and_forbidden_ids_are_closed_contracts(self):
        temporal_path = "data/curated/han/administrative-temporal-adjudications-v1.json"
        mutations = [
            (
                "zero width",
                lambda row: row["parentIntervals"][0].update(effectiveFrom=212),
            ),
            (
                "gap",
                lambda row: row["parentIntervals"][1].update(effectiveFrom=213),
            ),
            (
                "empty forbidden",
                lambda row: row.update(forbiddenIdentities=[]),
            ),
            (
                "duplicate forbidden",
                lambda row: row.update(
                    forbiddenIdentities=["hhs:111:東郡:004", "hhs:111:東郡:004"]
                ),
            ),
            (
                "unknown forbidden",
                lambda row: row.update(forbiddenIdentities=["hhs:111:東郡:999"]),
            ),
            (
                "unrelated known forbidden",
                lambda row: row.update(forbiddenIdentities=["hhs:111:東郡:003"]),
            ),
        ]
        for label, mutate in mutations:
            with self.subTest(label=label):
                documents = copy.deepcopy(self.documents)
                mutate(documents[temporal_path]["adjudications"][0])
                with self.assertRaisesRegex(ValueError, "interval|contiguous|forbidden|identity"):
                    self.module.build_ledger(documents, self.input_records)

    def test_complete_exact_approval_mapping_equals_independently_joined_selection(self):
        selection = self.documents["data/curated/han/route-node-selection-v1.json"]
        tile_city_ids = {
            str(city["id"])
            for city in self.documents["data/map/han-tiles.json"]["cities"]
        }
        expected = {}
        for route_node in selection["routeNodes"]:
            terminal = route_node["physicalPlaceRef"].rsplit(":", 1)[-1]
            if route_node["reviewState"] == "APPROVED" and terminal in tile_city_ids:
                expected[terminal] = {
                    "administrativeUnitId": route_node["administrativeUnitId"],
                    "physicalPlaceRef": route_node["physicalPlaceRef"],
                    "routeNodeKey": route_node["routeNodeKey"],
                }
        temporal = self.documents[
            "data/curated/han/administrative-temporal-adjudications-v1.json"
        ]
        for adjudication in temporal["adjudications"]:
            terminal = adjudication["physicalPlaceRef"].rsplit(":", 1)[-1]
            expected[terminal] = {
                "administrativeUnitId": adjudication["administrativeUnitId"],
                "physicalPlaceRef": adjudication["physicalPlaceRef"],
                "routeNodeKey": None,
            }
        actual = {
            row["cityId"]: {
                "administrativeUnitId": row["approvedParentAdministrativeUnitId"],
                "physicalPlaceRef": row["approvalEvidence"]["physicalPlaceRef"],
                "routeNodeKey": row["approvalEvidence"].get("routeNodeKey"),
            }
            for row in self.ledger["rows"]
            if row["decision"] == "EXACT_APPROVED"
        }

        self.assertEqual(779, len(expected))
        self.assertEqual(expected, actual)

    def test_contract_versions_ids_years_and_closed_enums_fail_closed(self):
        mutations = [
            ("selection schema", "data/curated/han/route-node-selection-v1.json", ("schemaVersion",), 999),
            ("selection id", "data/curated/han/route-node-selection-v1.json", ("selectionId",), "wrong"),
            ("selection year", "data/curated/han/route-node-selection-v1.json", ("baselineYear",), 221),
            ("selection node class", "data/curated/han/route-node-selection-v1.json", ("routeNodes", 0, "nodeClass"), "UNKNOWN"),
            ("selection seat role", "data/curated/han/route-node-selection-v1.json", ("routeNodes", 0, "seatRole"), "UNKNOWN"),
            ("policy schema", "data/curated/han/route-node-review-policy-v1.json", ("schemaVersion",), 999),
            ("policy id", "data/curated/han/route-node-review-policy-v1.json", ("policyId",), "wrong"),
            ("policy batch state", "data/curated/han/route-node-review-policy-v1.json", ("selectionBatches", 0, "reviewState"), "UNKNOWN"),
            ("bindings schema", "data/curated/han/administrative-place-bindings-v1.json", ("schemaVersion",), 999),
            ("bindings id", "data/curated/han/administrative-place-bindings-v1.json", ("catalogId",), "wrong"),
            ("bindings year", "data/curated/han/administrative-place-bindings-v1.json", ("sourceYear",), 221),
            ("bindings join status", "data/curated/han/administrative-place-bindings-v1.json", ("administrativeUnits", 0, "joinStatus"), "UNKNOWN"),
            ("temporal schema", "data/curated/han/administrative-temporal-adjudications-v1.json", ("schemaVersion",), 999),
            ("temporal id", "data/curated/han/administrative-temporal-adjudications-v1.json", ("adjudicationSetId",), "wrong"),
            ("temporal year", "data/curated/han/administrative-temporal-adjudications-v1.json", ("referenceYear",), 221),
            ("temporal state", "data/curated/han/administrative-temporal-adjudications-v1.json", ("adjudications", 0, "reviewState"), "UNKNOWN"),
            ("adjudication schema", "data/curated/han/route-node-location-adjudications-v1.json", ("schemaVersion",), 999),
            ("adjudication id", "data/curated/han/route-node-location-adjudications-v1.json", ("adjudicationSetId",), "wrong"),
            ("adjudication state", "data/curated/han/route-node-location-adjudications-v1.json", ("adjudications", 0, "reviewState"), "UNKNOWN"),
            ("candidate schema", "data/curated/han/route-node-selection-candidates-v1.json", ("schemaVersion",), 999),
            ("candidate id", "data/curated/han/route-node-selection-candidates-v1.json", ("selectionId",), "wrong"),
            ("candidate year", "data/curated/han/route-node-selection-candidates-v1.json", ("fixedYear",), 221),
            ("candidate origin", "data/curated/han/route-node-selection-candidates-v1.json", ("candidates", 0, "origin"), "UNKNOWN"),
            ("candidate state", "data/curated/han/route-node-selection-candidates-v1.json", ("candidates", 0, "reviewState"), "UNKNOWN"),
            ("validation schema", "data/curated/han/route-node-validation-contract-v1.json", ("schemaVersion",), 999),
            ("validation id", "data/curated/han/route-node-validation-contract-v1.json", ("contractId",), "wrong"),
            ("history schema", "data/map/han-administrative-history.json", ("schemaVersion",), 999),
            ("history years", "data/map/han-administrative-history.json", ("supportedYears",), [220]),
            ("tile year", "data/map/han-tiles.json", ("_meta", "year"), 221),
            ("tile kind", "data/map/han-tiles.json", ("cities", 0, "kind"), "UNKNOWN"),
            ("external confidence", "data/map/external-places.json", ("places", 0, "conf"), "UNKNOWN"),
            ("external kind", "data/map/external-places.json", ("places", 0, "kind"), "UNKNOWN"),
        ]
        for label, path, keys, value in mutations:
            with self.subTest(label=label):
                documents = copy.deepcopy(self.documents)
                replace_nested(documents[path], keys, value)
                with self.assertRaisesRegex(ValueError, "contract|enum|year|schema|identity"):
                    self.module.build_ledger(documents, self.input_records)

    def test_contract_membership_arrays_ignore_order_but_reject_duplicates(self):
        membership_arrays = [
            (
                "data/map/han-administrative-history.json",
                "supportedYears",
            ),
            (
                "data/curated/han/route-node-validation-contract-v1.json",
                "allowedNodeClasses",
            ),
        ]
        for path, field in membership_arrays:
            with self.subTest(path=path, field=field, mutation="permuted"):
                documents = copy.deepcopy(self.documents)
                documents[path][field] = list(reversed(documents[path][field]))
                self.assertEqual(
                    self.ledger,
                    self.module.build_ledger(documents, self.input_records),
                )

            with self.subTest(path=path, field=field, mutation="duplicate"):
                documents = copy.deepcopy(self.documents)
                values = documents[path][field]
                documents[path][field] = values[:-1] + [values[0]]
                with self.assertRaisesRegex(ValueError, "contract membership mismatch"):
                    self.module.build_ledger(documents, self.input_records)

    def test_every_embedded_hash_edge_between_pinned_inputs_fails_closed_on_drift(self):
        edges = [
            ("data/curated/han/route-node-review-policy-v1.json", ("inputs", "coordinateOverlaySha256")),
            ("data/curated/han/route-node-review-policy-v1.json", ("inputs", "candidateManifest", "sha256")),
            ("data/curated/han/route-node-review-policy-v1.json", ("inputs", "locationAdjudications", "sha256")),
            ("data/curated/han/route-node-selection-v1.json", ("provenance", "inputs", "administrativePlaceOverlay", "sha256")),
            ("data/curated/han/route-node-selection-v1.json", ("provenance", "inputs", "candidate", "sha256")),
            ("data/curated/han/route-node-selection-v1.json", ("provenance", "inputs", "legacyTileMap", "sha256")),
            ("data/curated/han/route-node-selection-v1.json", ("provenance", "inputs", "locationAdjudications", "sha256")),
            ("data/curated/han/route-node-selection-v1.json", ("provenance", "inputs", "reviewPolicy", "sha256")),
            ("data/curated/han/route-node-location-adjudications-v1.json", ("inputOverlaySha256",)),
            ("data/curated/han/route-node-selection-candidates-v1.json", ("provenance", "inputs", "administrativePlaceOverlay", "sha256")),
            ("data/curated/han/route-node-selection-candidates-v1.json", ("provenance", "inputs", "legacyTileMap", "sha256")),
        ]
        for source_path, keys in edges:
            with self.subTest(source_path=source_path, keys=keys):
                documents = copy.deepcopy(self.documents)
                replace_nested(documents[source_path], keys, "0" * 64)
                with self.assertRaisesRegex(ValueError, "embedded input hash mismatch"):
                    self.module.build_ledger(documents, self.input_records)

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
                "EXACT_APPROVED": 779,
                "PROPOSED_GEOMETRIC": 278,
                "BLOCKED_DIRECT_TERRITORY_REVIEW": 41,
                "BLOCKED_EXTERNAL_POLITY_REVIEW": 40,
            },
            dict(decisions),
        )
        self.assertEqual(
            {
                "EXACT_APPROVED": 199_874,
                "PROPOSED_GEOMETRIC": 65_983,
                "BLOCKED_DIRECT_TERRITORY_REVIEW": 20_987,
                "BLOCKED_EXTERNAL_POLITY_REVIEW": 46_070,
            },
            dict(decision_cells),
        )
        self.assertEqual(359, summary["unresolvedRowCount"])
        self.assertEqual(133_040, summary["unresolvedCellCount"])
        self.assertEqual(
            {"rowCount": 162, "cellCount": 45_828},
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
            {"rowCount": 273, "cellCount": 65_634},
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

    def test_committed_generated_artifact_is_current_and_byte_identical(self):
        self.assertTrue(
            self.module.check_ledger(output_path=self.module.OUTPUT),
            "committed parent reconciliation ledger is stale or corrupt; run --write",
        )


if __name__ == "__main__":
    unittest.main()
