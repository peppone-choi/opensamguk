from __future__ import annotations

import importlib.util
import json
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
TEST_DIR = Path(__file__).resolve().parent
if str(TEST_DIR) not in sys.path:
    sys.path.insert(0, str(TEST_DIR))

from han_route_node_candidate_fixtures import (
    admin_id,
    fixture,
    group,
    overlay_row,
    tile,
    unit,
)

MODULE_PATH = ROOT / "tools/scenario/build_han_route_node_candidates.py"
SPEC = importlib.util.spec_from_file_location("build_han_route_node_candidates", MODULE_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = MODULE
SPEC.loader.exec_module(MODULE)


class HanRouteNodeCandidateTest(unittest.TestCase):
    def test_resolved_identity_uses_exact_canonical_group_and_stays_pending(self) -> None:
        source_unit = unit("測試郡", 1)
        inputs = fixture(
            [source_unit],
            [overlay_row(source_unit, "RESOLVED_POINT", ["chgis:v6:cnty:100"])],
            [tile("100", "縣一")],
            ["測試郡"],
        )

        document = MODULE.build_candidates(*inputs)

        candidate = document["candidates"][0]
        self.assertEqual("HHS_RESOLVED", candidate["classification"])
        self.assertEqual(admin_id(source_unit), candidate["proposedAdministrativeUnitId"])
        self.assertEqual("PENDING", candidate["reviewState"])
        self.assertTrue(candidate["legacyNodeFingerprint"].startswith("sha256:"))

    def test_unique_source_group_alias_is_used_only_after_no_canonical_match(self) -> None:
        source_unit = unit("正式郡", 1)
        inputs = fixture(
            [source_unit],
            [overlay_row(source_unit, "RESOLVED_POINT", ["chgis:v6:cnty:100"])],
            [tile("100", "縣一")],
            ["別名郡"],
            groups=[group("正式郡", [source_unit], alias="別名郡")],
        )

        candidate = MODULE.build_candidates(*inputs)["candidates"][0]

        self.assertEqual("SOURCE_GROUP_ALIAS", candidate["ownerGroupResolution"])
        self.assertEqual("HHS_RESOLVED", candidate["classification"])

    def test_reverse_shared_physical_place_is_never_resolved(self) -> None:
        first = unit("測試郡", 1)
        second = unit("測試郡", 2)
        physical_id = "chgis:v6:cnty:100"
        inputs = fixture(
            [first, second],
            [
                overlay_row(first, "RESOLVED_POINT", [physical_id]),
                overlay_row(second, "AMBIGUOUS_POINT", [physical_id]),
            ],
            [tile("100", "縣一")],
            ["測試郡"],
        )

        candidate = MODULE.build_candidates(*inputs)["candidates"][0]

        self.assertEqual("HHS_AMBIGUOUS", candidate["classification"])
        self.assertEqual(2, candidate["physicalPlaceAdministrativeUnitCount"])
        self.assertNotIn("proposedAdministrativeUnitId", candidate)

    def test_physical_match_owned_by_another_group_is_attribution_conflict(self) -> None:
        foreign = unit("他郡", 1)
        local = unit("當郡", 1)
        inputs = fixture(
            [foreign],
            [
                overlay_row(foreign, "RESOLVED_POINT", ["chgis:v6:cnty:100"]),
                overlay_row(local, "NO_COORDINATE_CANDIDATE", []),
            ],
            [tile("100", "縣一")],
            ["當郡"],
            groups=[group("他郡", [foreign]), group("當郡", [local])],
        )

        candidate = MODULE.build_candidates(*inputs)["candidates"][0]

        self.assertEqual("HHS_ATTRIBUTION_CONFLICT", candidate["classification"])
        self.assertEqual([admin_id(foreign)], candidate["incompatibleAdministrativeUnitIds"])

    def test_x026_is_external_pending_with_hhs_correction_candidate(self) -> None:
        correction = unit("上郡", 9, volume=113)
        inputs = fixture(
            [correction],
            [overlay_row(correction, "NO_COORDINATE_CANDIDATE", [])],
            [tile("X026", "龜茲屬國", kind="COMMANDERY")],
            ["龜茲屬國"],
            groups=[group("上郡", [correction])],
        )

        candidate = MODULE.build_candidates(*inputs)["candidates"][0]

        self.assertEqual("EXTERNAL_OR_LATER_OR_MOVING", candidate["classification"])
        self.assertEqual("hhs:113:上郡:009", candidate["correctionCandidate"]["administrativeUnitId"])
        self.assertEqual("PENDING", candidate["correctionCandidate"]["reviewState"])

    def test_output_redacts_coordinates_and_absolute_paths(self) -> None:
        source_unit = unit("測試郡", 1)
        inputs = fixture(
            [source_unit],
            [overlay_row(source_unit, "RESOLVED_POINT", ["chgis:v6:cnty:100"])],
            [tile("100", "縣一")],
            ["測試郡"],
        )

        blob = MODULE.serialized(MODULE.build_candidates(*inputs))

        self.assertNotIn('"coordinate"', blob)
        self.assertNotIn('"lon"', blob)
        self.assertNotIn('"lat"', blob)
        self.assertNotIn('"recordIndex"', blob)
        self.assertNotIn('"presentLocation"', blob)
        self.assertNotIn("/private/external", blob)

    def test_every_hhs_unit_is_also_a_pending_replacement_pool_candidate(self) -> None:
        first = unit("測試郡", 1)
        second = unit("測試郡", 2)
        inputs = fixture(
            [first, second],
            [
                overlay_row(first, "RESOLVED_POINT", ["chgis:v6:cnty:100"]),
                overlay_row(second, "NO_COORDINATE_CANDIDATE", []),
            ],
            [tile("100", "縣一")],
            ["測試郡"],
        )

        document = MODULE.build_candidates(*inputs)
        pool = [row for row in document["candidates"] if row["origin"] == "HHS_REPLACEMENT_POOL"]

        self.assertEqual(2, len(pool))
        self.assertEqual(
            {"RESOLVED_POINT", "NO_COORDINATE_CANDIDATE"},
            {row["overlayJoinStatus"] for row in pool},
        )
        self.assertEqual(len(document["candidates"]), len({row["candidateKey"] for row in document["candidates"]}))
        self.assertEqual({"PENDING"}, {row["reviewState"] for row in document["candidates"]})

    def test_scenario_catalog_records_code_start_year_and_resource_hash(self) -> None:
        with tempfile.TemporaryDirectory() as raw_dir:
            scenario_dir = Path(raw_dir)
            (scenario_dir / "scenario_20.json").write_text(
                json.dumps({"startYear": 200, "map": {"mapName": "han"}}),
                encoding="utf-8",
            )
            (scenario_dir / "scenario_3.json").write_text(
                json.dumps({"startYear": 190, "map": {"mapName": "han"}}),
                encoding="utf-8",
            )

            rows = MODULE.build_scenario_catalog(scenario_dir)

        self.assertEqual(["3", "20"], [row["code"] for row in rows])
        self.assertEqual([190, 200], [row["startYear"] for row in rows])
        self.assertTrue(all(len(row["resourceSha256"]) == 64 for row in rows))
        self.assertTrue(all(row["resourcePath"].startswith("external:") for row in rows))

    def test_duplicate_legacy_city_id_fails_closed(self) -> None:
        source_unit = unit("測試郡", 1)
        inputs = list(
            fixture(
                [source_unit],
                [overlay_row(source_unit, "RESOLVED_POINT", ["chgis:v6:cnty:100"])],
                [tile("100", "縣一"), tile("101", "縣二")],
                ["測試郡", "測試郡"],
            )
        )
        inputs[3]["cities"][1]["id"] = 1

        with self.assertRaisesRegex(ValueError, "duplicate legacy city id"):
            MODULE.build_candidates(*inputs)

    def test_missing_or_malformed_input_fails_closed(self) -> None:
        with tempfile.TemporaryDirectory() as raw_dir:
            temp_dir = Path(raw_dir)
            missing = temp_dir / "missing.json"
            malformed = temp_dir / "malformed.json"
            malformed.write_text("{", encoding="utf-8")

            with self.assertRaises(FileNotFoundError):
                MODULE.load_document(missing)
            with self.assertRaises(json.JSONDecodeError):
                MODULE.load_document(malformed)

    def test_serialization_is_byte_deterministic(self) -> None:
        source_unit = unit("測試郡", 1)
        inputs = fixture(
            [source_unit],
            [overlay_row(source_unit, "RESOLVED_POINT", ["chgis:v6:cnty:100"])],
            [tile("100", "縣一")],
            ["測試郡"],
        )

        first = MODULE.serialized(MODULE.build_candidates(*inputs))
        second = MODULE.serialized(MODULE.build_candidates(*inputs))

        self.assertEqual(first, second)
        self.assertNotIn("APPROVED", first)


if __name__ == "__main__":
    unittest.main()
