import copy
import importlib.util
import json
import struct
import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
MODULE_PATH = ROOT / "tools/map/build_administrative_place_overlay.py"
SPEC = importlib.util.spec_from_file_location("administrative_place_overlay", MODULE_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = MODULE
SPEC.loader.exec_module(MODULE)


def unit(
    ordinal: int,
    source_name: str,
    *,
    status: str = "SOURCE_LITERAL",
    corrected_name: str | None = None,
) -> dict:
    row = {
        "sourceVolume": 109,
        "canonicalGroup": "測試郡",
        "ordinal": ordinal,
        "sourceName": source_name,
        "sourceNameStatus": status,
        "unitType": "COUNTY",
        "sourceCitation": {"corpusPath": "fixture.txt", "line": ordinal},
    }
    if corrected_name is not None:
        row["nameCorrection"] = {
            "correctedName": corrected_name,
            "sourceQuote": "fixture quote",
            "sourceCitation": {"corpusPath": "correction.txt", "line": 1},
        }
    if status == "SOURCE_PLACEHOLDER":
        row["sourceNameIssue"] = {
            "resolutionStatus": "UNRESOLVED_SOURCE_PLACEHOLDER",
            "witnessText": "damaged witness",
        }
    return row


def catalog(units: list[dict]) -> dict:
    return {
        "schemaVersion": 1,
        "catalogId": "fixture-catalog",
        "expectedGroupCount": 1,
        "expectedUnitCount": len(units),
        "detectedGroupCount": 1,
        "detectedUnitCount": len(units),
        "groups": [
            {
                "sourceVolume": 109,
                "canonicalGroup": "測試郡",
                "units": units,
            }
        ],
    }


def record(
    sys_id: str,
    name_ch: str,
    *,
    name_ft: str = "",
    beg: int = 200,
    end: int = 230,
    lon: float = 110.0,
    lat: float = 35.0,
) -> dict:
    return {
        "recordIndex": int(sys_id.removeprefix("R")),
        "SYS_ID": sys_id,
        "NAME_CH": name_ch,
        "NAME_FT": name_ft,
        "X_COOR": lon,
        "Y_COOR": lat,
        "BEG_YR": beg,
        "END_YR": end,
        "PRES_LOC": "fixture",
    }


def write_dbf(path: Path, values: dict[str, str]) -> None:
    fields = [
        ("NAME_CH", 16),
        ("NAME_FT", 16),
        ("X_COOR", 12),
        ("Y_COOR", 12),
        ("PRES_LOC", 16),
        ("BEG_YR", 8),
        ("END_YR", 8),
        ("SYS_ID", 12),
    ]
    header_length = 32 + 32 * len(fields) + 1
    record_length = 1 + sum(size for _, size in fields)
    header = bytearray(header_length)
    header[0] = 3
    header[4:12] = struct.pack("<IHH", 1, header_length, record_length)
    for index, (name, size) in enumerate(fields):
        offset = 32 + index * 32
        header[offset : offset + len(name)] = name.encode("ascii")
        header[offset + 11] = ord("C")
        header[offset + 16] = size
    header[-1] = 0x0D
    body = bytearray(b" ")
    for name, size in fields:
        encoded = values.get(name, "").encode("utf-8")
        if len(encoded) > size:
            raise ValueError(f"fixture value too wide: {name}")
        body.extend(encoded.ljust(size, b" "))
    path.write_bytes(bytes(header + body + b"\x1a"))


class AdministrativePlaceOverlayTest(unittest.TestCase):
    def test_exact_active_name_is_resolved_with_record_provenance(self):
        doc = MODULE.build_overlay(
            catalog([unit(1, "雒陽")]),
            [record("R1", "雒陽縣", lon=112.5963, lat=34.73157)],
            source_year=220,
        )

        row = doc["administrativeUnits"][0]
        self.assertEqual("hhs:109:測試郡:001", row["administrativeUnitId"])
        self.assertEqual("RESOLVED_POINT", row["joinStatus"])
        self.assertEqual(1, row["candidateCount"])
        self.assertEqual("R1", row["selectedCandidate"]["chgisSysId"])
        self.assertEqual([112.5963, 34.73157], row["selectedCandidate"]["coordinate"])

    def test_two_candidates_remain_ambiguous_without_selected_coordinate(self):
        doc = MODULE.build_overlay(
            catalog([unit(1, "中牟")]),
            [
                record("R1", "中牟縣", lon=110.0),
                record("R2", "中牟縣", lon=118.0),
            ],
            source_year=220,
        )

        row = doc["administrativeUnits"][0]
        self.assertEqual("AMBIGUOUS_POINT", row["joinStatus"])
        self.assertEqual(2, row["candidateCount"])
        self.assertNotIn("selectedCandidate", row)
        self.assertEqual({"R1", "R2"}, {candidate["chgisSysId"] for candidate in row["candidates"]})

    def test_one_candidate_shared_by_two_units_is_ambiguous_for_both(self):
        source = catalog([unit(1, "甲"), unit(2, "乙")])
        doc = MODULE.build_overlay(
            source,
            [record("R1", "甲縣", name_ft="乙縣")],
            source_year=220,
        )

        first, second = doc["administrativeUnits"]
        self.assertEqual(["AMBIGUOUS_POINT", "AMBIGUOUS_POINT"], [first["joinStatus"], second["joinStatus"]])
        self.assertNotIn("selectedCandidate", first)
        self.assertNotIn("selectedCandidate", second)
        self.assertEqual([second["administrativeUnitId"]], first["candidates"][0]["competingAdministrativeUnitIds"])
        self.assertEqual([first["administrativeUnitId"]], second["candidates"][0]["competingAdministrativeUnitIds"])

    def test_duplicate_active_chgis_sys_id_fails_closed(self):
        first = record("R1", "甲縣")
        second = record("R2", "乙縣")
        first["SYS_ID"] = "SAME"
        second["SYS_ID"] = "SAME"

        with self.assertRaisesRegex(ValueError, "duplicate active CHGIS physicalPlaceId"):
            MODULE.build_overlay(
                catalog([unit(1, "甲"), unit(2, "乙")]),
                [first, second],
                source_year=220,
            )

    def test_inactive_candidates_are_not_joined(self):
        doc = MODULE.build_overlay(
            catalog([unit(1, "舊縣")]),
            [record("R1", "舊縣", beg=100, end=219)],
            source_year=220,
        )

        row = doc["administrativeUnits"][0]
        self.assertEqual("NO_COORDINATE_CANDIDATE", row["joinStatus"])
        self.assertEqual(0, row["candidateCount"])

    def test_source_year_is_fixed_to_220_and_boundaries_are_inclusive(self):
        with self.assertRaisesRegex(ValueError, "source year must be exactly 220"):
            MODULE.build_overlay(catalog([unit(1, "甲")]), [], source_year=219)

        doc = MODULE.build_overlay(
            catalog([unit(1, "甲"), unit(2, "乙"), unit(3, "丙"), unit(4, "丁")]),
            [
                record("R1", "甲縣", beg=220, end=230),
                record("R2", "乙縣", beg=200, end=220),
                record("R3", "丙縣", beg=200, end=219),
                record("R4", "丁縣", beg=221, end=230),
            ],
            source_year=220,
        )
        self.assertEqual(
            ["RESOLVED_POINT", "RESOLVED_POINT", "NO_COORDINATE_CANDIDATE", "NO_COORDINATE_CANDIDATE"],
            [row["joinStatus"] for row in doc["administrativeUnits"]],
        )

    def test_independently_cited_correction_is_a_match_name(self):
        doc = MODULE.build_overlay(
            catalog([unit(1, "龟兹属国", corrected_name="龜茲")]),
            [record("R1", "龜茲縣")],
            source_year=220,
        )

        row = doc["administrativeUnits"][0]
        self.assertEqual(["龟兹属国", "龜茲"], row["matchNames"])
        self.assertEqual("RESOLVED_POINT", row["joinStatus"])

    def test_damaged_source_placeholder_is_never_auto_joined(self):
        doc = MODULE.build_overlay(
            catalog([unit(1, "参[�]", status="SOURCE_PLACEHOLDER")]),
            [record("R1", "參讀縣")],
            source_year=220,
        )

        row = doc["administrativeUnits"][0]
        self.assertEqual("SOURCE_PLACEHOLDER", row["joinStatus"])
        self.assertEqual([], row["matchNames"])
        self.assertEqual(0, row["candidateCount"])
        self.assertNotIn("selectedCandidate", row)

    def test_same_record_matching_both_name_fields_is_deduplicated(self):
        doc = MODULE.build_overlay(
            catalog([unit(1, "測試")]),
            [record("R1", "測試縣", name_ft="測試縣")],
            source_year=220,
        )

        row = doc["administrativeUnits"][0]
        self.assertEqual(1, row["candidateCount"])
        self.assertEqual(["NAME_CH", "NAME_FT"], row["selectedCandidate"]["matchedFields"])

    def test_commandery_and_kingdom_suffixes_are_not_stripped(self):
        doc = MODULE.build_overlay(
            catalog([unit(1, "輿")]),
            [record("R1", "輿國")],
            source_year=220,
        )

        self.assertEqual("NO_COORDINATE_CANDIDATE", doc["administrativeUnits"][0]["joinStatus"])

    def test_all_input_identities_are_preserved_in_source_order(self):
        source = catalog([unit(1, "甲"), unit(2, "乙"), unit(3, "丙")])
        doc = MODULE.build_overlay(source, [], source_year=220)

        self.assertEqual(3, doc["summary"]["administrativeUnitCount"])
        self.assertEqual(
            ["hhs:109:測試郡:001", "hhs:109:測試郡:002", "hhs:109:測試郡:003"],
            [row["administrativeUnitId"] for row in doc["administrativeUnits"]],
        )
        self.assertEqual(3, doc["summary"]["joinStatusCounts"]["NO_COORDINATE_CANDIDATE"])

    def test_invalid_catalog_counts_fail_closed(self):
        source = catalog([unit(1, "甲")])
        source["detectedUnitCount"] = 2

        with self.assertRaisesRegex(ValueError, "catalog count contract"):
            MODULE.build_overlay(source, [], source_year=220)

    def test_catalog_rejects_non_integer_ordinal_duplicate_group_and_uncited_correction(self):
        string_ordinal = catalog([unit(1, "甲")])
        string_ordinal["groups"][0]["units"][0]["ordinal"] = "1"
        with self.assertRaisesRegex(ValueError, "ordinal must be an int"):
            MODULE.build_overlay(string_ordinal, [], source_year=220)

        duplicate_group = catalog([unit(1, "甲")])
        duplicate_group["groups"].append(copy.deepcopy(duplicate_group["groups"][0]))
        duplicate_group["expectedGroupCount"] = 2
        duplicate_group["detectedGroupCount"] = 2
        duplicate_group["expectedUnitCount"] = 2
        duplicate_group["detectedUnitCount"] = 2
        with self.assertRaisesRegex(ValueError, "duplicate catalog group"):
            MODULE.build_overlay(duplicate_group, [], source_year=220)

        uncited = catalog([unit(1, "甲", corrected_name="乙")])
        uncited["groups"][0]["units"][0]["nameCorrection"] = {"correctedName": "乙"}
        with self.assertRaisesRegex(ValueError, "nameCorrection requires"):
            MODULE.build_overlay(uncited, [], source_year=220)

    def test_active_dbf_record_with_invalid_coordinate_fails_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "fixture.dbf"
            write_dbf(
                path,
                {
                    "NAME_CH": "甲縣",
                    "NAME_FT": "甲縣",
                    "X_COOR": "**********",
                    "Y_COOR": "35.0",
                    "PRES_LOC": "fixture",
                    "BEG_YR": "200",
                    "END_YR": "230",
                    "SYS_ID": "1",
                },
            )
            with self.assertRaisesRegex(ValueError, "active DBF record 0 has invalid coordinates"):
                MODULE.read_dbf(path, source_year=220)

    def test_coordinate_output_must_be_outside_repo_or_gitignored(self):
        tracked = ROOT / "README.md"
        with self.assertRaisesRegex(ValueError, "tracked repo path"):
            MODULE.assert_untracked(tracked, is_ignored=lambda _: False)
        ignored = ROOT / "data/map/fixture-overlay.json"
        self.assertEqual(ignored.resolve(), MODULE.assert_untracked(ignored, is_ignored=lambda _: True))
        with tempfile.TemporaryDirectory() as directory:
            outside = Path(directory) / "overlay.json"
            self.assertEqual(outside.resolve(), MODULE.assert_untracked(outside, is_ignored=lambda _: False))
            self.assertEqual("external:overlay.json", MODULE.source_label(outside))

    def test_build_is_deterministic_and_does_not_mutate_inputs(self):
        source = catalog([unit(1, "甲")])
        records = [record("R2", "甲縣", lon=119.0), record("R1", "甲縣", lon=110.0)]
        source_before = copy.deepcopy(source)
        records_before = copy.deepcopy(records)

        first = MODULE.build_overlay(source, records, source_year=220)
        second = MODULE.build_overlay(source, list(reversed(records)), source_year=220)

        self.assertEqual(
            json.dumps(first, ensure_ascii=False, sort_keys=True),
            json.dumps(second, ensure_ascii=False, sort_keys=True),
        )
        self.assertEqual(source_before, source)
        self.assertEqual(records_before, records)


if __name__ == "__main__":
    unittest.main()
