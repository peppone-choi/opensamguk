import copy
import hashlib
import sys
import unittest
from pathlib import Path


SCENARIO_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCENARIO_DIR))

from build_scenario import build, dump_scenario, validate_scenario_shape


DEFAULTS = {
    "palette": ["#8B0000", "#1F4E79"],
    "scale_by_member_count": [[1, 1]],
    "resources_by_scale": {"1": [3000, 3000, 250]},
    "ideology": "중립",
    "diplomacy": {
        "neutral_state": 2,
        "neutral_term": 0,
        "war_state": 0,
        "war_term": 0,
    },
}

CITY_MAP = {"北平": "북평", "洛陽": "낙양"}
REMAP = {"小沛": "패"}
NAME_MAP = {
    10001: "가",
    10002: "가나",
    10003: "나",
    10004: "나다",
    10005: "재야",
    10999: "무시",
}


def scenario_row(status: str | None, location: str | None, faction: str | None) -> dict:
    return {
        "year_month": "190.1",
        "status": status,
        "location": location,
        "faction": faction,
        "v1_rank": {"君主": 12, "太守": 4, "都督": 0, "一般": 0, "在野": 0}.get(status),
    }


def officer(
    officer_id: int,
    name_kanji: str,
    name_korean: str,
    status: str | None,
    location: str | None,
    faction: str | None,
    *,
    stat_offset: int = 0,
) -> dict:
    return {
        "id": officer_id,
        "name_kanji": name_kanji,
        "name_korean": name_korean,
        "birth": 150 + stat_offset,
        "death": 220 + stat_offset,
        "leadership": 60 + stat_offset,
        "strength": 61 + stat_offset,
        "intelligence": 62 + stat_offset,
        "politics": 63 + stat_offset,
        "charm": 64 + stat_offset,
        "scenarios": [scenario_row(status, location, faction)],
    }


def refined_fixture() -> list[dict]:
    return [
        officer(10003, "B", "나", "君主", "北平", "B", stat_offset=2),
        officer(10005, "N", "재야", "在野", "北平", None, stat_offset=4),
        officer(10002, "AA", "가나", "太守", "洛陽", "A", stat_offset=1),
        officer(10001, "A", "가", "君主", "가", "A"),
        officer(10004, "AB", "나다", "一般", "가", "A", stat_offset=3),
        officer(10999, "I", "무시", "未登場", None, None, stat_offset=5),
    ]


def nation(lord_id: int, name: str, cities: list[str]) -> dict:
    return {
        "lord_id": lord_id,
        "name": name,
        "color": "#8B0000",
        "gold": 6000,
        "rice": 6000,
        "tech": 550,
        "ideology": "중립",
        "scale": 3,
        "cities": cities,
    }


def normalized_manifest() -> dict:
    return {
        "code": "scenario_3190",
        "number": 3190,
        "title": "서로 맞선 세력들",
        "year_month": "190.1",
        "startYear": 190,
        "map": "che",
        "life": 1,
        "fiction": 0,
        "const": {"defaultMaxGeneral": 600},
        "nations": [
            nation(10003, "나", ["낙양"]),
            nation(10001, "가", ["가"]),
        ],
        "diplomacy": [[10003, 10001, 0, 0]],
        "city_relocations": [{
            "lord_id": 10003,
            "source_city": "북평",
            "assigned_city": "낙양",
            "reason": "lord_city_collision",
        }],
    }


class BuildScenarioTest(unittest.TestCase):
    def test_build_emits_decoder_tuples_with_stable_officer_mapping(self) -> None:
        scenario, report = build(
            refined_fixture(),
            normalized_manifest(),
            CITY_MAP,
            REMAP,
            NAME_MAP,
            DEFAULTS,
        )

        self.assertEqual(list(scenario), [
            "title",
            "startYear",
            "life",
            "fiction",
            "map",
            "const",
            "stored_icons",
            "nation",
            "general",
            "general_ex",
            "general_neutral",
            "diplomacy",
        ])
        self.assertEqual([len(row) for row in scenario["nation"]], [9, 9])
        self.assertEqual([len(row) for row in scenario["general"]], [16, 16, 16, 16, 16])
        self.assertEqual([len(row) for row in scenario["diplomacy"]], [4, 4])
        self.assertEqual(scenario["nation"], [
            ["가", "#8B0000", 6000, 6000, "", 550, "중립", 3, ["가"]],
            ["나", "#8B0000", 6000, 6000, "", 550, "중립", 3, ["낙양"]],
        ])
        self.assertEqual([row[1] for row in scenario["general"]], ["가", "가나", "나", "나다", "재야"])
        self.assertEqual([row[2] for row in scenario["general"]], [10001, 10002, 10003, 10004, 10005])
        self.assertEqual([row[3] for row in scenario["general"]], [1, 1, 2, 1, 0])
        self.assertEqual([row[4] for row in scenario["general"]], ["가", "낙양", "북평", "가", "북평"])
        self.assertEqual([row[8] for row in scenario["general"]], [12, 4, 12, 0, 0])
        self.assertTrue(all(row[0] == 0 for row in scenario["general"]))
        self.assertEqual(scenario["stored_icons"], {".": {
            "10001": "10001.png",
            "10002": "10002.png",
            "10003": "10003.png",
            "10004": "10004.png",
            "10005": "10005.png",
        }})
        self.assertEqual(scenario["diplomacy"], [[1, 2, 0, 0], [2, 1, 0, 0]])
        self.assertEqual(scenario["general_ex"], [])
        self.assertEqual(report["city_relocations"], normalized_manifest()["city_relocations"])
        validate_scenario_shape(scenario)

    def test_unaffiliated_officers_stay_in_general_and_general_neutral_remains_empty(self) -> None:
        scenario, _ = build(
            refined_fixture(),
            normalized_manifest(),
            CITY_MAP,
            REMAP,
            NAME_MAP,
            DEFAULTS,
        )

        self.assertEqual(scenario["general"][-1], [
            0,
            "재야",
            10005,
            0,
            "북평",
            64,
            65,
            66,
            0,
            154,
            224,
            None,
            None,
            None,
            67,
            68,
        ])
        self.assertEqual(scenario["general_neutral"], [])

    def test_build_requires_exactly_one_manifest_ruler_per_nation(self) -> None:
        missing_ruler = refined_fixture()
        missing_ruler[0]["scenarios"][0]["status"] = "一般"
        missing_ruler[0]["scenarios"][0]["v1_rank"] = 0
        with self.assertRaisesRegex(ValueError, "ruler"):
            build(missing_ruler, normalized_manifest(), CITY_MAP, REMAP, NAME_MAP, DEFAULTS)

        duplicate_ruler = refined_fixture()
        duplicate_ruler[2]["scenarios"][0]["status"] = "君主"
        duplicate_ruler[2]["scenarios"][0]["v1_rank"] = 12
        duplicate_ruler[2]["scenarios"][0]["faction"] = "A"
        with self.assertRaisesRegex(ValueError, "ruler"):
            build(duplicate_ruler, normalized_manifest(), CITY_MAP, REMAP, NAME_MAP, DEFAULTS)

    def test_build_rejects_noncanonical_manifest_fixed_setting_scalars(self) -> None:
        for field, value in (
            ("life", True),
            ("life", 1.0),
            ("fiction", False),
            ("fiction", 0.0),
            ("const.defaultMaxGeneral", True),
            ("const.defaultMaxGeneral", 600.0),
        ):
            with self.subTest(field=field, value=value):
                manifest = normalized_manifest()
                if field == "const.defaultMaxGeneral":
                    manifest["const"]["defaultMaxGeneral"] = value
                else:
                    manifest[field] = value
                with self.assertRaises(ValueError):
                    build(refined_fixture(), manifest, CITY_MAP, REMAP, NAME_MAP, DEFAULTS)

    def test_build_requires_exact_manifest_const_shape(self) -> None:
        for const in (
            {},
            {"defaultMaxGeneral": 600, "extra": 0},
            ["defaultMaxGeneral", 600],
        ):
            with self.subTest(const=const):
                manifest = normalized_manifest()
                manifest["const"] = const
                with self.assertRaisesRegex(ValueError, "manifest const"):
                    build(refined_fixture(), manifest, CITY_MAP, REMAP, NAME_MAP, DEFAULTS)

    def test_build_requires_registered_normalized_nation_type_aliases(self) -> None:
        for ideology in ("날조", "도적 ", "che_도적"):
            with self.subTest(ideology=ideology):
                manifest = normalized_manifest()
                manifest["nations"][0]["ideology"] = ideology
                with self.assertRaisesRegex(ValueError, "ideology"):
                    build(refined_fixture(), manifest, CITY_MAP, REMAP, NAME_MAP, DEFAULTS)

    def test_build_rejects_unknown_or_missing_emittable_fields(self) -> None:
        cases = (
            ("unknown_status", 2, "status", "낯선신분"),
            ("missing_status", 2, "status", None),
            ("blank_status", 2, "status", " "),
            ("missing_faction", 2, "faction", None),
            ("blank_faction", 2, "faction", " "),
            ("missing_location", 2, "location", None),
            ("blank_location", 2, "location", " "),
        )
        for label, record_index, field, value in cases:
            with self.subTest(label=label):
                refined = refined_fixture()
                refined[record_index]["scenarios"][0][field] = value
                with self.assertRaises(ValueError):
                    build(refined, normalized_manifest(), CITY_MAP, REMAP, NAME_MAP, DEFAULTS)

        refined = refined_fixture()
        refined[3]["name_korean"] = ""
        names = dict(NAME_MAP)
        names[10001] = ""
        with self.assertRaisesRegex(ValueError, "Korean"):
            build(refined, normalized_manifest(), CITY_MAP, REMAP, names, DEFAULTS)

    def test_build_rejects_source_labels_instead_of_leaking_them_to_scenario_json(self) -> None:
        refined = refined_fixture()
        refined[3]["name_korean"] = "曹操"
        names = dict(NAME_MAP)
        names[10001] = "曹操"

        with self.assertRaisesRegex(ValueError, "Korean"):
            build(refined, normalized_manifest(), CITY_MAP, REMAP, names, DEFAULTS)

    def test_build_rejects_stable_officer_ids_outside_the_permitted_band(self) -> None:
        for officer_id in (10000, 11001):
            with self.subTest(officer_id=officer_id):
                refined = refined_fixture()
                refined[-1]["id"] = officer_id
                names = dict(NAME_MAP)
                names[officer_id] = names.pop(10999)

                with self.assertRaisesRegex(ValueError, "10001..11000"):
                    build(refined, normalized_manifest(), CITY_MAP, REMAP, names, DEFAULTS)

    def test_build_requires_name_map_to_match_the_refined_id_set_exactly(self) -> None:
        missing = dict(NAME_MAP)
        missing.pop(10999)
        with self.assertRaisesRegex(ValueError, "identity set"):
            build(refined_fixture(), normalized_manifest(), CITY_MAP, REMAP, missing, DEFAULTS)

        extra = dict(NAME_MAP)
        extra[10006] = "추가"
        with self.assertRaisesRegex(ValueError, "identity set"):
            build(refined_fixture(), normalized_manifest(), CITY_MAP, REMAP, extra, DEFAULTS)

    def test_build_rejects_mixed_han_script_korean_name(self) -> None:
        refined = refined_fixture()
        refined[3]["name_korean"] = "가曹"
        names = dict(NAME_MAP)
        names[10001] = "가曹"

        with self.assertRaisesRegex(ValueError, "Korean"):
            build(refined, normalized_manifest(), CITY_MAP, REMAP, names, DEFAULTS)

    def test_build_rejects_mixed_han_script_korean_city(self) -> None:
        city_map = dict(CITY_MAP)
        city_map["北平"] = "북曹"

        with self.assertRaisesRegex(ValueError, "Korean"):
            build(refined_fixture(), normalized_manifest(), city_map, REMAP, NAME_MAP, DEFAULTS)

    def test_build_rejects_mixed_han_script_korean_nation_name(self) -> None:
        manifest = normalized_manifest()
        manifest["nations"][0]["name"] = "나曹"

        with self.assertRaisesRegex(ValueError, "Korean"):
            build(refined_fixture(), manifest, CITY_MAP, REMAP, NAME_MAP, DEFAULTS)

    def test_build_accepts_permitted_korean_output_punctuation(self) -> None:
        refined = refined_fixture()
        refined[3]["name_korean"] = "가 2·호"
        names = dict(NAME_MAP)
        names[10001] = "가 2·호"
        manifest = normalized_manifest()
        manifest["title"] = "서로: 맞선 세력들"
        manifest["nations"][1]["name"] = "가-국 2"
        city_map = dict(CITY_MAP)
        city_map["北平"] = "북 평·2"

        scenario, _ = build(refined, manifest, city_map, REMAP, names, DEFAULTS)

        self.assertEqual(scenario["title"], "서로: 맞선 세력들")
        self.assertEqual(scenario["nation"][0][0], "가-국 2")
        self.assertEqual(scenario["general"][0][1], "가 2·호")
        self.assertEqual(scenario["general"][2][4], "북 평·2")

    def test_dump_is_utf8_ordered_and_byte_identical_for_repeated_and_shuffled_inputs(self) -> None:
        first, first_report = build(
            refined_fixture(),
            normalized_manifest(),
            CITY_MAP,
            REMAP,
            NAME_MAP,
            DEFAULTS,
        )
        second, second_report = build(
            copy.deepcopy(refined_fixture()),
            copy.deepcopy(normalized_manifest()),
            dict(CITY_MAP),
            dict(REMAP),
            dict(NAME_MAP),
            copy.deepcopy(DEFAULTS),
        )
        shuffled, shuffled_report = build(
            list(reversed(refined_fixture())),
            normalized_manifest(),
            CITY_MAP,
            REMAP,
            NAME_MAP,
            DEFAULTS,
        )

        first_bytes = dump_scenario(first)
        second_bytes = dump_scenario(second)
        shuffled_bytes = dump_scenario(shuffled)
        first_sha256 = hashlib.sha256(first_bytes).hexdigest()

        self.assertTrue(first_bytes.endswith(b"\n"))
        self.assertIn("서로 맞선 세력들".encode("utf-8"), first_bytes)
        self.assertNotIn(b"\\u", first_bytes)
        self.assertEqual(first_bytes, second_bytes)
        self.assertEqual(first_bytes, shuffled_bytes)
        self.assertEqual(first_sha256, hashlib.sha256(second_bytes).hexdigest())
        self.assertEqual(first_sha256, hashlib.sha256(shuffled_bytes).hexdigest())
        self.assertEqual(first_report, second_report)
        self.assertEqual(first_report, shuffled_report)
        self.assertNotIn(b"name_kanji", first_bytes)
        self.assertNotIn(b"faction", first_bytes)

    def test_diplomacy_expands_a_stable_lord_relation_in_both_runtime_directions(self) -> None:
        forward, _ = build(
            refined_fixture(),
            normalized_manifest(),
            CITY_MAP,
            REMAP,
            NAME_MAP,
            DEFAULTS,
        )
        reverse_manifest = normalized_manifest()
        reverse_manifest["diplomacy"] = [[10001, 10003, 0, 0]]
        reverse, _ = build(
            refined_fixture(),
            reverse_manifest,
            CITY_MAP,
            REMAP,
            NAME_MAP,
            DEFAULTS,
        )

        self.assertEqual(forward["diplomacy"], [[1, 2, 0, 0], [2, 1, 0, 0]])
        self.assertEqual(forward["diplomacy"], reverse["diplomacy"])

    def test_shape_validator_rejects_wrong_tuple_lengths(self) -> None:
        scenario, _ = build(
            refined_fixture(),
            normalized_manifest(),
            CITY_MAP,
            REMAP,
            NAME_MAP,
            DEFAULTS,
        )
        broken = copy.deepcopy(scenario)
        broken["general"][0].pop()

        with self.assertRaisesRegex(ValueError, "16"):
            validate_scenario_shape(broken)

    def test_shape_validator_rejects_boolean_and_float_scalars(self) -> None:
        scenario, _ = build(
            refined_fixture(),
            normalized_manifest(),
            CITY_MAP,
            REMAP,
            NAME_MAP,
            DEFAULTS,
        )

        boolean_stat = copy.deepcopy(scenario)
        boolean_stat["general"][0][5] = True
        with self.assertRaisesRegex(ValueError, "leadership"):
            validate_scenario_shape(boolean_stat)

        float_picture = copy.deepcopy(scenario)
        float_picture["general"][0][2] = 10001.0
        with self.assertRaisesRegex(ValueError, "picture"):
            validate_scenario_shape(float_picture)


if __name__ == "__main__":
    unittest.main()
