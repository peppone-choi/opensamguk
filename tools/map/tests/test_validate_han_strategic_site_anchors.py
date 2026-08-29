import copy
import importlib.util
import json
import subprocess
import sys
import unittest
from collections import Counter
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
MODULE_PATH = ROOT / "tools/map/validate_han_strategic_site_anchors.py"
LEDGER_PATH = ROOT / "data/curated/han/strategic-site-anchor-review-v1.json"

EXPECTED_SITE_IDS = [
    "site:dingjunshan",
    "site:jiange",
    "site:jieting",
    "site:qishan",
    "site:wuzhangyuan",
    "site:yanganguan",
    "site:yangpingguan",
]
EXPECTED_SOURCE_URLS = {
    "https://www.mianxian.gov.cn/mxzf/mlmx/lsrw/202010/da415101654b4384a385f8674884a758.shtml",
    "https://dfz.shaanxi.gov.cn/zslm/fzzlk/sxjz/hzs_16205/201706/P020240923599655489696.pdf",
    "https://lcj.sc.gov.cn/scslyt/c1055/2023/6/21/d1de680159b84a90987dd3d4b6409f45/files/%E5%89%91%E9%97%A8%E8%9C%80%E9%81%93%E9%A3%8E%E6%99%AF%E5%90%8D%E8%83%9C%E5%8C%BA%EF%BC%88%E5%B9%BF%E5%85%83%E6%AE%B5%EF%BC%89%E5%89%91%E9%97%A8%E5%85%B3%E6%99%AF%E5%8C%BA%E8%AF%A6%E7%BB%86%E8%A7%84%E5%88%92%EF%BC%88%E5%BE%81%E6%B1%82%E6%84%8F%E8%A7%81%E7%A8%BF%EF%BC%89%E8%AF%B4%E6%98%8E%E4%B9%A6.pdf",
    "https://gsdfszw.org.cn/gsxzcz/tss/201905/P020190528326424696493.pdf",
    "https://zx.hanzhong.gov.cn/hzzxwz/thhm/201605/t20160512_330971.shtml",
    "https://www.gs.gov.cn/gsszf/c100054/202304/169834641/files/d47e74422e0f4aa99fbeb09bf1f9b698.pdf",
    "https://cjp.baoji.gov.cn/col3866/col3880/200810/t20081027_710024.html",
    "https://dfz.shaanxi.gov.cn/zslm/fzzlk/sxjz/hzs_16205/201706/P020240923600372937321.pdf",
    "https://dfz.shaanxi.gov.cn/zslm/fzzlk/xbsxsxz/xbsxz/hzs_16205/201405/P020240923623803686515.pdf",
}


class ValidatorPresenceTest(unittest.TestCase):
    def test_validator_and_hand_reviewed_ledger_exist(self):
        self.assertTrue(MODULE_PATH.is_file(), f"missing validator: {MODULE_PATH}")
        self.assertTrue(LEDGER_PATH.is_file(), f"missing reviewed ledger: {LEDGER_PATH}")


@unittest.skipUnless(MODULE_PATH.is_file() and LEDGER_PATH.is_file(), "validator not implemented yet")
class StrategicSiteAnchorReviewTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        spec = importlib.util.spec_from_file_location("validate_han_strategic_site_anchors", MODULE_PATH)
        cls.module = importlib.util.module_from_spec(spec)
        sys.modules[spec.name] = cls.module
        spec.loader.exec_module(cls.module)
        cls.ledger, cls.documents, cls.input_records = cls.module.load_bundle()
        if "provinceRecords" in cls.documents["data/map/han-tiles.json"]:
            raise unittest.SkipTest("legacy strategic-anchor ledger is pinned to the pre-v2 tile artifact")
        cls.module.validate_ledger(cls.ledger, cls.documents, cls.input_records)

    def validate_copy(self, mutate):
        ledger = copy.deepcopy(self.ledger)
        documents = copy.deepcopy(self.documents)
        records = copy.deepcopy(self.input_records)
        mutate(ledger, documents, records)
        return self.module.validate_ledger(ledger, documents, records)

    def row(self, site_id):
        return next(row for row in self.ledger["reviewRows"] if row["siteId"] == site_id)

    def test_cli_is_check_only_and_never_rewrites_the_reviewed_ledger(self):
        help_result = subprocess.run(
            [sys.executable, str(MODULE_PATH), "--help"],
            cwd=ROOT,
            capture_output=True,
            text=True,
            check=False,
        )
        self.assertEqual(0, help_result.returncode, help_result.stderr)
        self.assertIn("--check", help_result.stdout)
        self.assertNotIn("--write", help_result.stdout)

        before = LEDGER_PATH.read_bytes()
        check_result = subprocess.run(
            [sys.executable, str(MODULE_PATH), "--check"],
            cwd=ROOT,
            capture_output=True,
            text=True,
            check=False,
        )
        self.assertEqual(0, check_result.returncode, check_result.stdout + check_result.stderr)
        self.assertEqual(before, LEDGER_PATH.read_bytes())

    def test_committed_artifact_has_manifest_order_and_locked_current_states(self):
        manifest_ids = [
            site["id"]
            for site in self.documents["data/map/han-strategic-sites.json"]["sites"]
        ]
        rows = self.ledger["reviewRows"]
        self.assertEqual(EXPECTED_SITE_IDS, manifest_ids)
        self.assertEqual(manifest_ids, [row["siteId"] for row in rows])
        self.assertEqual(
            {"PROPOSED": 5, "BLOCKED": 2},
            dict(Counter(row["reviewState"] for row in rows)),
        )
        self.assertFalse(any(row["reviewState"] == "APPROVED" for row in rows))
        self.assertTrue(self.module.check_ledger())

    def test_state_selection_conflict_and_review_invariants_fail_closed(self):
        mutations = [
            (
                "unknown state",
                lambda ledger, _documents, _records: ledger["reviewRows"][0].update(
                    reviewState="UNKNOWN"
                ),
                "reviewState",
            ),
            (
                "proposed without selection",
                lambda ledger, _documents, _records: ledger["reviewRows"][0].pop(
                    "selectedCandidateId"
                ),
                "PROPOSED",
            ),
            (
                "proposed mandatory runtime seed",
                lambda ledger, _documents, _records: ledger["reviewRows"][0].update(
                    mandatoryRuntimeSeed=True
                ),
                "mandatory runtime",
            ),
            (
                "blocked with selection",
                lambda ledger, _documents, _records: ledger["reviewRows"][2].update(
                    selectedCandidateId=ledger["reviewRows"][2]["candidates"][0]["candidateId"]
                ),
                "BLOCKED",
            ),
            (
                "blocked without candidates or conflict",
                lambda ledger, _documents, _records: ledger["reviewRows"][2].update(
                    candidates=[], conflicts=[]
                ),
                "BLOCKED",
            ),
            (
                "approved without review metadata",
                lambda ledger, _documents, _records: ledger["reviewRows"][0].update(
                    reviewState="APPROVED"
                ),
                "APPROVED",
            ),
            (
                "approved with an empty reviewer identity",
                lambda ledger, _documents, _records: ledger["reviewRows"][0].update(
                    reviewState="APPROVED",
                    reviewerId="reviewer:",
                    reviewedDate="2026-08-28",
                ),
                "stable reviewerId",
            ),
            (
                "rejected retains anchor",
                lambda ledger, _documents, _records: ledger["reviewRows"][0].update(
                    reviewState="REJECTED"
                ),
                "REJECTED",
            ),
            (
                "rejected without evidence",
                lambda ledger, _documents, _records: (
                    ledger["reviewRows"][0].update(
                        reviewState="REJECTED",
                        candidates=[],
                        conflicts=[],
                        rejectedClaims=[],
                        rejection={"reason": "Review rejected this anchor."},
                    ),
                    ledger["reviewRows"][0].pop("selectedCandidateId"),
                ),
                "REJECTED.*evidence",
            ),
        ]
        for label, mutate, message in mutations:
            with self.subTest(label=label):
                with self.assertRaisesRegex(ValueError, message):
                    self.validate_copy(mutate)

    def test_no_forbidden_approval_identity_or_administrative_place_can_enter(self):
        forbidden = [
            ("physicalPlaceId", "chgis:v6:cnty:70623"),
            ("physical_place_id", 70623),
            ("administrativeUnitId", "hhs:109:漢中郡:001"),
            ("administrative_unit_id", 109001),
            ("placeId", 70623),
            ("nearestCityId", "70623"),
            ("nearest_city_id", 70623),
            ("rasterOwner", "county:70623"),
            ("raster_owner_id", 70623),
            ("cityArrayIndex", 10),
            ("city_index", 10),
            ("junArrayIndex", 2),
            ("runtimeId", 99),
            ("runtime_identity", 99),
            ("route_node_key", 99),
            ("siteName", "定軍山"),
            ("displayName", "Dingjunshan"),
            ("nameCh", "定軍山"),
            ("display_label", "Dingjunshan"),
        ]
        for field, value in forbidden:
            with self.subTest(field=field):
                with self.assertRaisesRegex(ValueError, "undeclared|forbidden"):
                    self.validate_copy(
                        lambda ledger, _documents, _records, field=field, value=value: ledger[
                            "reviewRows"
                        ][0].update({field: value})
                    )

        with self.assertRaisesRegex(ValueError, "forbidden administrative physical-place"):
            self.validate_copy(
                lambda ledger, _documents, _records: ledger["reviewRows"][0]["conflicts"].append(
                    {"conflictId": "bad", "kind": "OTHER", "reason": "pref:220:bad"}
                )
            )

    def test_every_ledger_object_layer_rejects_undeclared_keys(self):
        mutations = [
            (
                "ledger",
                lambda ledger, _documents, _records: ledger.update(metadata={}),
            ),
            (
                "reference raster",
                lambda ledger, _documents, _records: ledger["referenceRaster"].update(year=220),
            ),
            (
                "projection",
                lambda ledger, _documents, _records: ledger["referenceRaster"][
                    "projection"
                ].update(epsg=4326),
            ),
            (
                "tracked input record",
                lambda ledger, _documents, _records: ledger["trackedInputs"][
                    "data/map/han-tiles.json"
                ].update(contentHash="0" * 64),
            ),
            (
                "path evidence source",
                lambda ledger, _documents, _records: ledger["evidenceSources"][0].update(
                    digest="0" * 64
                ),
            ),
            (
                "review row",
                lambda ledger, _documents, _records: ledger["reviewRows"][0].update(
                    physical_place_id=70623
                ),
            ),
            (
                "candidate",
                lambda ledger, _documents, _records: ledger["reviewRows"][0]["candidates"][
                    0
                ].update(placeId=70623),
            ),
            (
                "conflict",
                lambda ledger, _documents, _records: ledger["reviewRows"][2]["conflicts"][
                    0
                ].update(administrative_unit_id=109001),
            ),
            (
                "rejected claim",
                lambda ledger, _documents, _records: ledger["reviewRows"][1][
                    "rejectedClaims"
                ][0].update(contentHash="0" * 64),
            ),
            (
                "rejection",
                lambda ledger, _documents, _records: (
                    ledger["reviewRows"][0].update(
                        reviewState="REJECTED",
                        candidates=[],
                        conflicts=[],
                        rejectedClaims=[],
                        rejection={
                            "reason": "Review rejected this anchor.",
                            "evidenceSourceIds": ["mian-county-locality"],
                            "placeId": 70623,
                        },
                    ),
                    ledger["reviewRows"][0].pop("selectedCandidateId"),
                ),
            ),
        ]
        for label, mutate in mutations:
            with self.subTest(label=label):
                with self.assertRaisesRegex(ValueError, "undeclared"):
                    self.validate_copy(mutate)

    def test_evidence_roles_fail_closed_for_candidate_use_and_approval(self):
        self.assertEqual(
            {
                "TRACKED_COORDINATE_CROSSWALK": "SECONDARY_COORDINATE",
                "TRACKED_PROJECTION_WITNESS": "PROJECTION_CONFLICT_WITNESS",
                "OFFICIAL_LOCALITY": "AUTHORITATIVE_LOCALITY",
                "OFFICIAL_GAZETTEER": "HISTORICAL_GEOGRAPHY",
                "OFFICIAL_PLAN": "HISTORICAL_GEOGRAPHY",
                "ACADEMIC_DISPUTE": "HISTORICAL_GEOGRAPHY",
            },
            self.module.EVIDENCE_ROLE_BY_KIND,
        )
        for kind in ("UNKNOWN", "SECONDARY_COORDINATE"):
            with self.subTest(corrupt_kind=kind):
                with self.assertRaisesRegex(ValueError, "kind mismatch"):
                    self.validate_copy(
                        lambda ledger, _documents, _records, kind=kind: ledger[
                            "evidenceSources"
                        ][0].update(kind=kind)
                    )

        cases = [
            (
                "official locality used as coordinate evidence",
                lambda ledger, _documents, _records: ledger["reviewRows"][0]["candidates"][
                    0
                ].update(coordinateEvidenceSourceIds=["mian-county-locality"]),
                "coordinate-capable",
            ),
            (
                "projection witness used as coordinate evidence",
                lambda ledger, _documents, _records: ledger["reviewRows"][4]["candidates"][
                    1
                ].update(coordinateEvidenceSourceIds=["projection-generator-witness"]),
                "coordinate-capable",
            ),
            (
                "secondary coordinate source used as placement evidence",
                lambda ledger, _documents, _records: ledger["reviewRows"][0]["candidates"][
                    0
                ].update(placementEvidenceSourceIds=["tracked-coordinate-crosswalk"]),
                "placement-capable",
            ),
            (
                "projection witness used as placement evidence",
                lambda ledger, _documents, _records: ledger["reviewRows"][4]["candidates"][
                    1
                ].update(placementEvidenceSourceIds=["projection-generator-witness"]),
                "placement-capable",
            ),
            (
                "approved candidate with wrong coordinate role",
                lambda ledger, _documents, _records: (
                    ledger["reviewRows"][0].update(
                        reviewState="APPROVED",
                        reviewerId="reviewer:han-sites-1",
                        reviewedDate="2026-08-28",
                    ),
                    ledger["reviewRows"][0]["candidates"][0].update(
                        coordinateEvidenceSourceIds=["mian-county-locality"]
                    ),
                ),
                "coordinate-capable",
            ),
        ]
        for label, mutate, message in cases:
            with self.subTest(label=label):
                with self.assertRaisesRegex(ValueError, message):
                    self.validate_copy(mutate)

    def test_official_url_sources_are_reference_only_exact_objects(self):
        url_source_index = next(
            index
            for index, source in enumerate(self.ledger["evidenceSources"])
            if "url" in source
        )
        for field, value in (
            ("sha256", "0" * 64),
            ("contentHash", "0" * 64),
            ("digest", "0" * 64),
            ("fetchedContent", "cached"),
        ):
            with self.subTest(field=field):
                with self.assertRaisesRegex(ValueError, "undeclared"):
                    self.validate_copy(
                        lambda ledger, _documents, _records,
                        field=field, value=value: ledger["evidenceSources"][
                            url_source_index
                        ].update({field: value})
                    )

    def test_tracked_hashes_schema_ids_and_projection_contract_fail_closed(self):
        cases = [
            (
                "ledger schema",
                lambda ledger, _documents, _records: ledger.update(schemaVersion=999),
            ),
            (
                "ledger identity",
                lambda ledger, _documents, _records: ledger.update(ledgerId="wrong"),
            ),
            (
                "tracked hash",
                lambda ledger, _documents, _records: ledger["trackedInputs"][
                    "data/map/han-tiles.json"
                ].update(sha256="0" * 64),
            ),
            (
                "manifest schema",
                lambda _ledger, documents, _records: documents[
                    "data/map/han-strategic-sites.json"
                ].update(schemaVersion=999),
            ),
            (
                "projection columns",
                lambda _ledger, documents, _records: documents["data/map/han-tiles.json"][
                    "_meta"
                ].update(cols=767),
            ),
            (
                "projection constant",
                lambda _ledger, documents, _records: documents["data/map/han-tiles.json"][
                    "_meta"
                ]["projection"].update(cell=0.05),
            ),
        ]
        for label, mutate in cases:
            with self.subTest(label=label):
                with self.assertRaisesRegex(ValueError, "schema|identity|hash|projection|raster"):
                    self.validate_copy(mutate)

    def test_every_committed_projection_candidate_recomputes_and_is_on_land(self):
        projection = self.documents["data/map/han-tiles.json"]["_meta"]["projection"]
        terrain = self.documents["data/map/han-tiles.json"]["terrain"]
        committed = [
            candidate
            for row in self.ledger["reviewRows"]
            for candidate in row["candidates"]
            if candidate["cellBasis"] == "COMMITTED_PROJECTION"
        ]
        self.assertEqual(8, len(committed))
        for candidate in committed:
            lon, lat = candidate["coordinate"]
            expected = [
                int((lon * projection["k"] - projection["x0"] + projection["pad"]) // projection["cell"]),
                int((projection["y1"] + projection["pad"] - lat) // projection["cell"]),
            ]
            self.assertEqual(expected, candidate["rasterCell"])
            col, row = expected
            self.assertNotEqual("0", terrain[row][col])

        for row_index, row in enumerate(self.ledger["reviewRows"]):
            for candidate_index, candidate in enumerate(row["candidates"]):
                if candidate["cellBasis"] != "COMMITTED_PROJECTION":
                    continue
                with self.subTest(candidate=candidate["candidateId"]):
                    with self.assertRaisesRegex(ValueError, "recompute"):
                        self.validate_copy(
                            lambda ledger, _documents, _records,
                            row_index=row_index, candidate_index=candidate_index: ledger[
                                "reviewRows"
                            ][row_index]["candidates"][candidate_index].update(
                                rasterCell=[
                                    ledger["reviewRows"][row_index]["candidates"][candidate_index][
                                        "rasterCell"
                                    ][0]
                                    + 1,
                                    ledger["reviewRows"][row_index]["candidates"][candidate_index][
                                        "rasterCell"
                                    ][1],
                                ]
                            )
                        )

    def test_jieting_retains_two_competing_unselected_candidates(self):
        row = self.row("site:jieting")
        self.assertEqual("BLOCKED", row["reviewState"])
        self.assertNotIn("selectedCandidateId", row)
        self.assertEqual(
            ["site:jieting:longcheng-tradition", "site:jieting:maiji-theory"],
            [candidate["candidateId"] for candidate in row["candidates"]],
        )
        self.assertEqual([[254, 228], [254, 239]], [row["rasterCell"] for row in row["candidates"]])
        self.assertEqual(
            [[105.98359, 34.99615], [105.99444, 34.45192]],
            [row["coordinate"] for row in row["candidates"]],
        )

    def test_wuzhangyuan_freezes_the_unresolved_projection_boundary(self):
        row = self.row("site:wuzhangyuan")
        self.assertEqual("BLOCKED", row["reviewState"])
        self.assertNotIn("selectedCandidateId", row)
        self.assertEqual(
            ["COMMITTED_PROJECTION", "FULL_PRECISION_GENERATOR_WITNESS"],
            [candidate["cellBasis"] for candidate in row["candidates"]],
        )
        self.assertEqual([[289, 244], [290, 244]], [row["rasterCell"] for row in row["candidates"]])
        self.assertEqual("PROJECTION_SSO_T_BOUNDARY", row["conflicts"][0]["kind"])

    def test_jiange_malformed_coordinate_is_rejected_without_autocorrection(self):
        row = self.row("site:jiange")
        self.assertEqual(
            [
                {
                    "claimId": "site:jiange:malformed-official-coordinate",
                    "rawCoordinateText": "105°3′E,31°11′N",
                    "disposition": "REJECTED_MALFORMED_INCONSISTENT_COORDINATE",
                    "evidenceSourceIds": ["jiange-official-plan"],
                    "reason": "The published coordinate is inconsistent with the plan locality and is not auto-corrected.",
                }
            ],
            row["rejectedClaims"],
        )
        self.assertNotIn("correctedCoordinate", row["rejectedClaims"][0])

    def test_yangan_and_yangping_lineages_and_selected_anchors_stay_distinct(self):
        yangan = self.row("site:yanganguan")
        yangping = self.row("site:yangpingguan")
        self.assertEqual("THREE_KINGDOMS_YANGAN_NINGQIANG", yangan["siteLineage"])
        self.assertEqual("HAN_YANGPING_MIAXIAN", yangping["siteLineage"])
        self.assertNotEqual(yangan["siteLineage"], yangping["siteLineage"])
        yangan_candidate = yangan["candidates"][0]
        yangping_candidate = yangping["candidates"][0]
        self.assertNotEqual(yangan_candidate["coordinate"], yangping_candidate["coordinate"])
        self.assertNotEqual(yangan_candidate["rasterCell"], yangping_candidate["rasterCell"])

        with self.assertRaisesRegex(ValueError, "lineage"):
            self.validate_copy(
                lambda ledger, _documents, _records: ledger["reviewRows"][6].update(
                    siteLineage=ledger["reviewRows"][5]["siteLineage"]
                )
            )
        with self.assertRaisesRegex(ValueError, "selected anchor"):
            self.validate_copy(
                lambda ledger, _documents, _records: ledger["reviewRows"][6]["candidates"][
                    0
                ].update(
                    coordinate=ledger["reviewRows"][5]["candidates"][0]["coordinate"],
                    rasterCell=ledger["reviewRows"][5]["candidates"][0]["rasterCell"],
                )
            )

    def test_only_declared_source_arrays_ignore_order_and_all_reject_duplicates(self):
        self.assertEqual(
            {
                "evidenceSources": "UNORDERED_UNIQUE_BY_SOURCE_ID",
                "coordinateEvidenceSourceIds": "UNORDERED_UNIQUE",
                "placementEvidenceSourceIds": "UNORDERED_UNIQUE",
                "evidenceSourceIds": "UNORDERED_UNIQUE",
                "reviewRows": "MANIFEST_ORDERED",
                "candidates": "REVIEW_ORDERED",
                "conflicts": "REVIEW_ORDERED",
                "rejectedClaims": "REVIEW_ORDERED",
            },
            self.ledger["arraySemantics"],
        )
        self.validate_copy(
            lambda ledger, _documents, _records: ledger.update(
                evidenceSources=list(reversed(ledger["evidenceSources"]))
            )
        )
        self.validate_copy(
            lambda ledger, _documents, _records: ledger["reviewRows"][0]["candidates"][0].update(
                placementEvidenceSourceIds=list(
                    reversed(
                        ledger["reviewRows"][0]["candidates"][0][
                            "placementEvidenceSourceIds"
                        ]
                    )
                )
            )
        )
        self.validate_copy(
            lambda ledger, _documents, _records: ledger["reviewRows"][4]["candidates"][1].update(
                coordinateEvidenceSourceIds=list(
                    reversed(
                        ledger["reviewRows"][4]["candidates"][1][
                            "coordinateEvidenceSourceIds"
                        ]
                    )
                )
            )
        )
        self.validate_copy(
            lambda ledger, _documents, _records: ledger["reviewRows"][2]["conflicts"][0].update(
                evidenceSourceIds=list(
                    reversed(
                        ledger["reviewRows"][2]["conflicts"][0]["evidenceSourceIds"]
                    )
                )
            )
        )

        duplicate_mutations = [
            lambda ledger: ledger["evidenceSources"].append(
                copy.deepcopy(ledger["evidenceSources"][0])
            ),
            lambda ledger: ledger["reviewRows"][0]["candidates"][0][
                "placementEvidenceSourceIds"
            ].append(
                ledger["reviewRows"][0]["candidates"][0]["placementEvidenceSourceIds"][0]
            ),
            lambda ledger: ledger["reviewRows"][4]["candidates"][1][
                "coordinateEvidenceSourceIds"
            ].append(
                ledger["reviewRows"][4]["candidates"][1]["coordinateEvidenceSourceIds"][0]
            ),
            lambda ledger: ledger["reviewRows"][2]["conflicts"][0][
                "evidenceSourceIds"
            ].append(ledger["reviewRows"][2]["conflicts"][0]["evidenceSourceIds"][0]),
            lambda ledger: ledger["reviewRows"][1]["rejectedClaims"][0][
                "evidenceSourceIds"
            ].append(
                ledger["reviewRows"][1]["rejectedClaims"][0]["evidenceSourceIds"][0]
            ),
        ]
        for mutate in duplicate_mutations:
            with self.subTest(mutation=mutate):
                with self.assertRaisesRegex(ValueError, "duplicate"):
                    self.validate_copy(
                        lambda ledger, _documents, _records, mutate=mutate: mutate(ledger)
                    )

        with self.assertRaisesRegex(ValueError, "manifest order"):
            self.validate_copy(
                lambda ledger, _documents, _records: ledger.update(
                    reviewRows=list(reversed(ledger["reviewRows"]))
                )
            )

    def test_authoritative_locality_urls_are_complete_claim_references(self):
        urls = {
            source["url"]
            for source in self.ledger["evidenceSources"]
            if "url" in source
        }
        self.assertEqual(EXPECTED_SOURCE_URLS, urls)
        self.assertTrue(
            all("sha256" not in source for source in self.ledger["evidenceSources"] if "url" in source)
        )


if __name__ == "__main__":
    unittest.main()
