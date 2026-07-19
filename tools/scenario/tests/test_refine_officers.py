import json
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path
from xml.sax.saxutils import escape


SCENARIO_DIR = Path(__file__).resolve().parents[1]
REPOSITORY_ROOT = SCENARIO_DIR.parents[1]
sys.path.insert(0, str(SCENARIO_DIR))

from refine_officers import (
    join_korean_names,
    load_mapping,
    load_name_join_overrides,
    load_xlsx_rows,
    refine,
    validate_location_maps,
    validate_location_remaps,
)


FINGERPRINT_FIELDS = ("birth", "death", "leadership", "strength", "intelligence", "politics", "charm")


def raw_record(name: str, page_key: str, offset: int = 0, status: str = "一般", office: str | None = None) -> dict:
    values = {
        "birth": 150 + offset,
        "death": 220 + offset,
        "leadership": 60 + offset,
        "strength": 61 + offset,
        "intelligence": 62 + offset,
        "politics": 63 + offset,
        "charm": 64 + offset,
    }
    return {
        "name_kanji": name,
        "name_reading": f"reading-{name}",
        "page_key": page_key,
        **values,
        "scenarios": [
            {
                "year_month": "190.1",
                "status": status,
                "location": "北平",
                "faction": name if status == "君主" else "君主",
                "office": office,
            }
        ],
    }


def xlsx_row(name: str, offset: int = 0) -> dict:
    return {
        "name_korean": name,
        "birth": 150 + offset,
        "death": 220 + offset,
        "leadership": 60 + offset,
        "strength": 61 + offset,
        "intelligence": 62 + offset,
        "politics": 63 + offset,
        "charm": 64 + offset,
    }


def numbered_xlsx_row(number: str, name: str, offset: int = 0) -> dict:
    row = xlsx_row(name, offset)
    row["officer_number"] = number
    return row


def write_xlsx(path: Path, rows: list[dict]) -> None:
    headers = ["무장", "생년", "몰년", "통솔", "무력", "지력", "정치", "매력"]
    fields = ["name_korean", *FINGERPRINT_FIELDS]

    def sheet_row(number: int, values: list[object]) -> str:
        cells = []
        for index, value in enumerate(values):
            column = chr(ord("A") + index)
            cells.append(f'<c r="{column}{number}" t="inlineStr"><is><t>{escape(str(value))}</t></is></c>')
        return f'<row r="{number}">{"".join(cells)}</row>'

    sheet = (
        '<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>'
        + sheet_row(1, headers)
        + "".join(sheet_row(index + 2, [row[field] for field in fields]) for index, row in enumerate(rows))
        + "</sheetData></worksheet>"
    )
    with zipfile.ZipFile(path, "w") as archive:
        archive.writestr("xl/worksheets/sheet1.xml", sheet)


class RefineOfficersTest(unittest.TestCase):
    def test_load_xlsx_rows_reads_the_complete_fingerprint(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            path = Path(temporary_directory) / "officers.xlsx"
            write_xlsx(path, [xlsx_row("한글장수")])
            self.assertEqual(load_xlsx_rows(path), [xlsx_row("한글장수")])

    def test_join_only_accepts_one_exact_fingerprint_candidate(self) -> None:
        unique = raw_record("甲", "page-a", 0)
        ambiguous = raw_record("乙", "page-b", 1)
        unresolved = raw_record("丙", "page-c", 2)
        joined, report = join_korean_names(
            [unresolved, ambiguous, unique],
            [xlsx_row("유일", 0), xlsx_row("중복-가", 1), xlsx_row("중복-나", 1)],
        )

        self.assertEqual([(record["name_kanji"], record["name_korean"]) for record in joined], [("甲", "유일")])
        self.assertEqual([entry["name_kanji"] for entry in report["unresolved"]], ["丙"])
        self.assertEqual([entry["name_kanji"] for entry in report["ambiguous"]], ["乙"])
        self.assertEqual(report["collisions"], [])

    def test_explicit_override_runs_after_exact_joins_and_keeps_raw_stats(self) -> None:
        exact = raw_record("甲", "page-a", 0)
        overridden = raw_record("乙", "page-b", 1)
        candidate = numbered_xlsx_row("2", "나", 1)
        candidate["death"] += 1
        overrides = {
            ("乙", "reading-乙", "page-b"): {
                "officer_number": "2",
                "name_korean": "나",
                "expected_mismatch_field": "death",
            }
        }

        joined, report = join_korean_names(
            [overridden, exact],
            [numbered_xlsx_row("1", "가", 0), candidate],
            overrides,
        )

        by_name = {record["name_kanji"]: record for record in joined}
        self.assertEqual(report["exact_join_count"], 1)
        self.assertEqual(report["override_join_count"], 1)
        self.assertEqual(by_name["乙"]["name_korean"], "나")
        self.assertEqual(by_name["乙"]["death"], overridden["death"])
        self.assertEqual(report["unresolved"], [])

    def test_override_rejects_used_candidate_and_declared_field_drift(self) -> None:
        exact = raw_record("甲", "page-a", 0)
        overridden = raw_record("乙", "page-b", 0)
        overridden["death"] -= 1
        used_candidate = numbered_xlsx_row("1", "가", 0)
        used_override = {
            ("乙", "reading-乙", "page-b"): {
                "officer_number": "1",
                "name_korean": "가",
                "expected_mismatch_field": "death",
            }
        }
        with self.assertRaises(ValueError):
            join_korean_names([exact, overridden], [used_candidate], used_override)

        target = raw_record("丙", "page-c", 2)
        drifted_candidate = numbered_xlsx_row("3", "다", 2)
        drifted_candidate["death"] += 1
        drifted_candidate["politics"] += 1
        drifted_override = {
            ("丙", "reading-丙", "page-c"): {
                "officer_number": "3",
                "name_korean": "다",
                "expected_mismatch_field": "death",
            }
        }
        with self.assertRaises(ValueError):
            join_korean_names([target], [drifted_candidate], drifted_override)

    def test_refine_is_byte_identical_when_inputs_are_shuffled_and_registry_is_preserved(self) -> None:
        raw = [
            raw_record("甲", "page-a", 0, "太守", "태수관직"),
            raw_record("乙", "page-b", 1, "都督", "도독관직"),
            raw_record("丙", "page-c", 2, "君主"),
        ]
        rows = [xlsx_row("가", 0), xlsx_row("나", 1), xlsx_row("다", 2)]
        refined, registry, report = refine(raw, rows, [])
        reversed_refined, reversed_registry, reversed_report = refine(list(reversed(raw)), list(reversed(rows)), list(reversed(registry)))

        self.assertEqual(
            json.dumps((refined, registry, report), ensure_ascii=False, sort_keys=True, separators=(",", ":")),
            json.dumps((reversed_refined, reversed_registry, reversed_report), ensure_ascii=False, sort_keys=True, separators=(",", ":")),
        )
        self.assertEqual([record["id"] for record in refined], [10001, 10002, 10003])
        self.assertEqual({key: report[key] for key in ("unresolved", "ambiguous", "collisions")}, {
            "unresolved": [],
            "ambiguous": [],
            "collisions": [],
        })
        self.assertTrue(any(entry["kind"] == "taesu_officer_city_unrepresentable" for entry in report["semantic_downgrades"]))
        self.assertTrue(any(entry["kind"] == "dudok_rank_downgrade" for entry in report["semantic_downgrades"]))
        by_name = {record["name_kanji"]: record for record in refined}
        self.assertEqual(by_name["甲"]["scenarios"][0]["v1_rank"], 4)
        self.assertEqual(by_name["乙"]["scenarios"][0]["v1_rank"], 0)

    def test_registry_fingerprint_drift_is_rejected(self) -> None:
        raw = [raw_record("甲", "page-a")]
        rows = [xlsx_row("가")]
        _, registry, _ = refine(raw, rows, [])
        changed_raw = raw_record("甲", "page-a")
        changed_raw["leadership"] = 99
        changed_rows = [xlsx_row("가")]
        changed_rows[0]["leadership"] = 99

        with self.assertRaises(ValueError):
            refine([changed_raw], changed_rows, registry)

    def test_initial_assignment_uses_every_id_in_the_reserved_1000_id_band(self) -> None:
        raw = [raw_record(f"武{index:04d}", f"page-{index:04d}", index) for index in range(1000)]
        rows = [xlsx_row(f"한{index:04d}", index) for index in range(1000)]
        refined, registry, report = refine(list(reversed(raw)), list(reversed(rows)), [])

        self.assertEqual([record["id"] for record in refined], list(range(10001, 11001)))
        self.assertEqual([record["id"] for record in registry], list(range(10001, 11001)))
        self.assertEqual({key: report[key] for key in ("unresolved", "ambiguous", "collisions")}, {
            "unresolved": [],
            "ambiguous": [],
            "collisions": [],
        })

    def test_tracked_location_mappings_are_complete_and_reject_unknown_targets_or_duplicate_keys(self) -> None:
        city_map = load_mapping(SCENARIO_DIR / "city_map.json")
        remap = load_mapping(SCENARIO_DIR / "location-remap.yaml")
        che = json.loads((REPOSITORY_ROOT / "infra/src/main/resources/map/che.json").read_text(encoding="utf-8"))
        che_names = {city["name"] for city in che["cities"]}

        validate_location_maps(city_map, remap, che_names)
        self.assertEqual(len(city_map), 42)
        self.assertEqual(remap, {
            "小沛": "패",
            "武威": "서량",
            "武関": "홍농",
            "涪水関": "자동",
            "潼関": "장안",
            "白水関": "자동",
            "綿竹関": "면죽",
            "虎牢関": "사수",
            "陽平関": "한중",
            "函谷関": "함곡",
            "剣閣": "자동",
            "壺関": "호관",
            "襄平": "북평",
            "建安": "회계",
        })
        validate_location_remaps(remap)
        substituted_remap = dict(remap)
        substituted_remap["小沛"] = "북평"
        with self.assertRaisesRegex(ValueError, "must exactly match"):
            validate_location_remaps(substituted_remap)
        with tempfile.TemporaryDirectory() as temporary_directory:
            duplicate = Path(temporary_directory) / "duplicate.json"
            duplicate.write_text('{"同": "북평", "同": "계"}', encoding="utf-8")
            with self.assertRaises(ValueError):
                load_mapping(duplicate)
        with self.assertRaises(ValueError):
            validate_location_maps({"direct": "없는 도시"}, {}, che_names)

    def test_reviewed_overrides_disambiguate_duplicate_korean_names_by_officer_number(self) -> None:
        overrides = load_name_join_overrides(SCENARIO_DIR / "name-join-overrides.tsv")
        expected = {
            ("雷叙", "ライジョ", "雷叙"): ("885", "뇌서", "death"),
            ("李豊", "リホウ", "李豊(袁)"): ("919", "이풍", "death"),
            ("張先", "チョウセン", "張先"): ("641", "장선", "death"),
            ("張楊", "チョウヨウ", "張楊"): ("661", "장양", "death"),
        }

        self.assertEqual(len(overrides), 22)
        for key, values in expected.items():
            override = overrides[key]
            self.assertEqual(
                (override["officer_number"], override["name_korean"], override["expected_mismatch_field"]),
                values,
            )
        self.assertFalse({"884", "917", "918", "642", "664"} & {row["officer_number"] for row in overrides.values()})
        with tempfile.TemporaryDirectory() as temporary_directory:
            duplicate = Path(temporary_directory) / "duplicate-overrides.tsv"
            duplicate.write_text(
                "name_kanji\tname_reading\tpage_key\tofficer_number\tname_korean\texpected_mismatch_field\n"
                "甲\tread\tpage\t1\t가\tdeath\n"
                "甲\tread\tpage\t2\t나\tdeath\n",
                encoding="utf-8",
            )
            with self.assertRaises(ValueError):
                load_name_join_overrides(duplicate)

    def test_22_row_override_set_requires_1000_raw_records_and_978_exact_joins(self) -> None:
        raw = [raw_record(f"exact-{index}", f"exact-page-{index}", index) for index in range(977)]
        rows = [numbered_xlsx_row(str(index + 1), f"exact-ko-{index}", index) for index in range(977)]
        overrides = {}
        for index in range(22):
            record = raw_record(f"override-{index}", f"override-page-{index}", 1000 + index)
            candidate = numbered_xlsx_row(str(978 + index), f"override-ko-{index}", 1000 + index)
            candidate["death"] += 1
            raw.append(record)
            rows.append(candidate)
            overrides[(record["name_kanji"], record["name_reading"], record["page_key"])] = {
                "officer_number": str(978 + index),
                "name_korean": f"override-ko-{index}",
                "expected_mismatch_field": "death",
            }

        with self.assertRaisesRegex(ValueError, "exactly 978 exact joins"):
            join_korean_names(raw, rows, overrides)

    def test_reviewed_join_rejects_an_unused_1001st_xlsx_candidate(self) -> None:
        raw = [raw_record(f"exact-{index}", f"exact-page-{index}", index) for index in range(978)]
        rows = [numbered_xlsx_row(str(index + 1), f"exact-ko-{index}", index) for index in range(978)]
        overrides = {}
        for index in range(22):
            record = raw_record(f"override-{index}", f"override-page-{index}", 1000 + index)
            candidate = numbered_xlsx_row(str(979 + index), f"override-ko-{index}", 1000 + index)
            candidate["death"] += 1
            raw.append(record)
            rows.append(candidate)
            overrides[(record["name_kanji"], record["name_reading"], record["page_key"])] = {
                "officer_number": str(979 + index),
                "name_korean": f"override-ko-{index}",
                "expected_mismatch_field": "death",
            }
        rows.append(numbered_xlsx_row("1001", "unused", 5000))

        with self.assertRaisesRegex(ValueError, "raw and XLSX row counts must match"):
            join_korean_names(raw, rows, overrides)


if __name__ == "__main__":
    unittest.main()
