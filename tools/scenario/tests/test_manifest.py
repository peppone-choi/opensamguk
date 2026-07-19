import copy
import json
import sys
import tempfile
import unittest
from contextlib import contextmanager
from pathlib import Path


SCENARIO_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCENARIO_DIR))

import manifest as manifest_module
from manifest import derive_nation_defaults, load_manifest, validate_manifest


CITY_NAMES = {"가", "나", "다", "라"}


def scenario(
    *,
    year_month: str = "190.1",
    status: str,
    location: str | None,
    faction: str | None,
) -> dict:
    return {
        "year_month": year_month,
        "status": status,
        "location": location,
        "faction": faction,
    }


def officer(
    officer_id: int,
    name_kanji: str,
    name_korean: str,
    scenario_row: dict,
) -> dict:
    return {
        "id": officer_id,
        "name_kanji": name_kanji,
        "name_korean": name_korean,
        "scenarios": [scenario_row],
    }


def refined_fixture() -> list[dict]:
    return [
        officer(10001, "A", "가", scenario(status="君主", location="가", faction="A")),
        officer(10002, "AA", "가가", scenario(status="一般", location="다", faction="A")),
        officer(10004, "AB", "가나", scenario(status="一般", location="다", faction="A")),
        officer(10006, "AC", "가다", scenario(status="一般", location="라", faction="A")),
        officer(10003, "B", "나", scenario(status="君主", location="다", faction="B")),
        officer(10005, "BA", "나가", scenario(status="一般", location="나", faction="B")),
        officer(10007, "BB", "나다", scenario(status="一般", location="라", faction="B")),
        officer(10008, "N", "재야", scenario(status="在野", location="가", faction=None)),
    ]


def manifest_fixture() -> dict:
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
        "nations": [{"lord_id": 10003}, {"lord_id": 10001}],
    }


def collision_refined(first_member_count: int, second_member_count: int, *, reserve_nearest_city: bool = False) -> list[dict]:
    records = [
        officer(10001, "A", "가", scenario(status="君主", location="가", faction="A")),
        officer(10003, "B", "나", scenario(status="君主", location="가", faction="B")),
    ]
    for index in range(1, first_member_count):
        records.append(officer(10100 + index, f"A{index}", f"가{index}", scenario(status="一般", location="가", faction="A")))
    for index in range(1, second_member_count):
        records.append(officer(10200 + index, f"B{index}", f"나{index}", scenario(status="一般", location="가", faction="B")))
    if reserve_nearest_city:
        records.append(officer(10005, "C", "다", scenario(status="君主", location="나", faction="C")))
    return records


def collision_manifest(*lord_ids: int) -> dict:
    data = manifest_fixture()
    data["nations"] = [{"lord_id": lord_id} for lord_id in lord_ids]
    return data


def city_graph_fixture() -> dict:
    return {
        "cities": [
            {"id": 1, "name": "가", "connected": ["나", "다"]},
            {"id": 2, "name": "나", "connected": ["가"]},
            {"id": 3, "name": "다", "connected": ["가"]},
        ]
    }


@contextmanager
def temporary_city_graph(data: dict):
    with tempfile.TemporaryDirectory() as temporary_directory:
        path = Path(temporary_directory) / "cities_1010.json"
        path.write_text(json.dumps(data, ensure_ascii=False), encoding="utf-8")
        original_path = manifest_module.CITY_GRAPH_PATH
        manifest_module.CITY_GRAPH_PATH = path
        try:
            yield
        finally:
            manifest_module.CITY_GRAPH_PATH = original_path


class ManifestTest(unittest.TestCase):
    def test_load_manifest_accepts_only_json_compatible_yaml(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            path = Path(temporary_directory) / "manifest.yaml"
            expected = manifest_fixture()
            path.write_text(json.dumps(expected, ensure_ascii=False), encoding="utf-8")
            self.assertEqual(load_manifest(path), expected)

            path.write_text("code: scenario_3190\n", encoding="utf-8")
            with self.assertRaises(json.JSONDecodeError):
                load_manifest(path)

    def test_validation_derives_stable_defaults_and_deterministic_city_ownership(self) -> None:
        validated = validate_manifest(manifest_fixture(), refined_fixture(), CITY_NAMES)

        self.assertEqual([nation["lord_id"] for nation in validated["nations"]], [10001, 10003])
        self.assertEqual(validated["nations"][0], {
            "lord_id": 10001,
            "name": "가",
            "color": "#8B0000",
            "gold": 6000,
            "rice": 6000,
            "tech": 550,
            "ideology": "중립",
            "scale": 3,
            "cities": ["가", "라"],
        })
        self.assertEqual(validated["nations"][1], {
            "lord_id": 10003,
            "name": "나",
            "color": "#1F4E79",
            "gold": 6000,
            "rice": 6000,
            "tech": 550,
            "ideology": "중립",
            "scale": 3,
            "cities": ["다", "나"],
        })
        self.assertEqual(validated["diplomacy"], [])
        self.assertEqual(validated["city_relocations"], [])

    def test_derive_nation_defaults_uses_member_count_threshold_and_palette_ordinal(self) -> None:
        defaults = derive_nation_defaults(
            10001,
            [
                {"id": 10001, "name_korean": "가"},
                {"id": 10002, "name_korean": "가가"},
                {"id": 10004, "name_korean": "가나"},
            ],
            13,
        )

        self.assertEqual(defaults, {
            "lord_id": 10001,
            "name": "가",
            "color": "#1F4E79",
            "gold": 6000,
            "rice": 6000,
            "tech": 550,
            "ideology": "중립",
            "scale": 3,
        })

    def test_identity_code_number_and_start_year_mismatches_are_rejected(self) -> None:
        for field, value in (("code", "scenario_3200"), ("number", 3200), ("startYear", 200)):
            with self.subTest(field=field):
                data = manifest_fixture()
                data[field] = value
                with self.assertRaises(ValueError):
                    validate_manifest(data, refined_fixture(), CITY_NAMES)

    def test_fixed_game_settings_reject_noncanonical_boolean_and_float_values(self) -> None:
        for field, value in (
            ("life", True),
            ("life", 1.0),
            ("fiction", False),
            ("fiction", 0.0),
            ("const.defaultMaxGeneral", True),
            ("const.defaultMaxGeneral", 600.0),
        ):
            with self.subTest(field=field, value=value):
                data = manifest_fixture()
                if field == "const.defaultMaxGeneral":
                    data["const"]["defaultMaxGeneral"] = value
                else:
                    data[field] = value
                with self.assertRaises(ValueError):
                    validate_manifest(data, refined_fixture(), CITY_NAMES)

    def test_const_requires_exact_default_max_general_shape(self) -> None:
        for const in (
            {},
            {"defaultMaxGeneral": 600, "extra": 0},
            ["defaultMaxGeneral", 600],
        ):
            with self.subTest(const=const):
                data = manifest_fixture()
                data["const"] = const
                with self.assertRaises(ValueError):
                    validate_manifest(data, refined_fixture(), CITY_NAMES)

    def test_manifest_overrides_require_registered_nation_type_aliases(self) -> None:
        for ideology in ("날조", "도적 ", "che_도적"):
            with self.subTest(ideology=ideology):
                data = manifest_fixture()
                data["overrides"] = {"10001": {"ideology": ideology}}
                with self.assertRaisesRegex(ValueError, "ideology"):
                    validate_manifest(data, refined_fixture(), CITY_NAMES)

    def test_active_rows_with_missing_or_blank_faction_are_rejected_before_grouping(self) -> None:
        for faction in (None, "", "  "):
            with self.subTest(faction=faction):
                refined = copy.deepcopy(refined_fixture())
                refined[1]["scenarios"][0]["faction"] = faction
                with self.assertRaisesRegex(ValueError, "active faction"):
                    validate_manifest(manifest_fixture(), refined, CITY_NAMES)


    def test_collision_priority_uses_member_count_and_relocates_the_loser(self) -> None:
        with temporary_city_graph(city_graph_fixture()):
            validated = validate_manifest(
                collision_manifest(10001, 10003),
                collision_refined(2, 3),
                {"가", "나", "다"},
            )

        cities_by_lord = {nation["lord_id"]: nation["cities"] for nation in validated["nations"]}
        self.assertEqual(cities_by_lord[10001], ["나"])
        self.assertEqual(cities_by_lord[10003], ["가"])
        self.assertEqual(validated["city_relocations"], [{
            "lord_id": 10001,
            "source_city": "가",
            "assigned_city": "나",
            "reason": "lord_city_collision",
        }])

    def test_collision_ties_choose_the_lowest_lord_id_and_lowest_city_id(self) -> None:
        with temporary_city_graph(city_graph_fixture()):
            validated = validate_manifest(
                collision_manifest(10001, 10003),
                collision_refined(2, 2),
                {"가", "나", "다"},
            )

        cities_by_lord = {nation["lord_id"]: nation["cities"] for nation in validated["nations"]}
        self.assertEqual(cities_by_lord[10001], ["가"])
        self.assertEqual(cities_by_lord[10003], ["나"])

    def test_relocation_reserves_other_unique_lord_cities_before_bfs(self) -> None:
        with temporary_city_graph(city_graph_fixture()):
            validated = validate_manifest(
                collision_manifest(10001, 10003, 10005),
                collision_refined(2, 2, reserve_nearest_city=True),
                {"가", "나", "다"},
            )

        cities_by_lord = {nation["lord_id"]: nation["cities"] for nation in validated["nations"]}
        self.assertEqual(cities_by_lord[10001], ["가"])
        self.assertEqual(cities_by_lord[10003], ["다"])
        self.assertEqual(cities_by_lord[10005], ["나"])

    def test_collision_fails_closed_when_no_unowned_city_is_reachable(self) -> None:
        with temporary_city_graph({"cities": [{"id": 1, "name": "가", "connected": []}]}):
            with self.assertRaisesRegex(ValueError, "no unowned city"):
                validate_manifest(collision_manifest(10001, 10003), collision_refined(2, 2), {"가"})

    def test_collision_fails_closed_when_a_source_city_is_absent_from_the_graph(self) -> None:
        with temporary_city_graph({"cities": [{"id": 2, "name": "나", "connected": []}]}):
            with self.assertRaisesRegex(ValueError, "source city is absent"):
                validate_manifest(collision_manifest(10001, 10003), collision_refined(2, 2), {"가", "나"})

    def test_actual_pilots_have_unique_capitals_when_refined_input_is_available(self) -> None:
        repository_root = SCENARIO_DIR.parents[1]
        refined_path = repository_root / "data/scenarios/refined/rtk14-officers.json"
        if not refined_path.exists():
            self.skipTest("ignored refined input is unavailable")
        refined = json.loads(refined_path.read_text(encoding="utf-8"))
        che = json.loads((repository_root / "infra/src/main/resources/map/che.json").read_text(encoding="utf-8"))
        city_names = {city["name"] for city in che["cities"]}
        expected = {
            "scenario_3190.yaml": {
                "active_members": 249,
                "factions": 21,
                "relocations": [{
                    "lord_id": 10056,
                    "source_city": "북평",
                    "assigned_city": "역경",
                    "reason": "lord_city_collision",
                }],
            },
            "scenario_3200.yaml": {
                "active_members": 304,
                "factions": 11,
                "relocations": [],
            },
            "scenario_3219.yaml": {
                "active_members": 370,
                "factions": 6,
                "relocations": [],
            },
        }

        for filename, expectation in expected.items():
            with self.subTest(filename=filename):
                validated = validate_manifest(load_manifest(SCENARIO_DIR / "manifests" / filename), refined, city_names)
                active_rows = [
                    (record, scenario)
                    for record in refined
                    for scenario in record["scenarios"]
                    if scenario.get("year_month") == validated["year_month"]
                    and scenario.get("status") in manifest_module.ACTIVE_STATUSES
                    and isinstance(scenario.get("faction"), str)
                    and scenario["faction"].strip()
                ]
                active_factions = {scenario["faction"] for _, scenario in active_rows}
                source_ruler_ids = {
                    record["id"]
                    for record, scenario in active_rows
                    if scenario["status"] == "君主" and scenario["faction"] == record["name_kanji"]
                }
                cities = [city for nation in validated["nations"] for city in nation["cities"]]
                nation_lord_ids = [nation["lord_id"] for nation in validated["nations"]]
                self.assertEqual(len(active_rows), expectation["active_members"])
                self.assertEqual(len(active_factions), expectation["factions"])
                self.assertEqual(len(validated["nations"]), expectation["factions"])
                self.assertEqual(set(source_ruler_ids) - set(nation_lord_ids), set())
                self.assertEqual(len(nation_lord_ids) - len(set(nation_lord_ids)), 0)
                self.assertEqual(len(cities), len(set(cities)))
                self.assertTrue(all(nation["cities"] for nation in validated["nations"]))
                self.assertEqual(validated["city_relocations"], expectation["relocations"])

    def test_duplicate_unknown_and_uncovered_lords_are_rejected(self) -> None:
        duplicate = manifest_fixture()
        duplicate["nations"].append({"lord_id": 10001})
        with self.assertRaisesRegex(ValueError, "duplicate lord_id"):
            validate_manifest(duplicate, refined_fixture(), CITY_NAMES)

        unknown = manifest_fixture()
        unknown["nations"][0] = {"lord_id": 10999}
        with self.assertRaisesRegex(ValueError, "unknown or inactive ruler"):
            validate_manifest(unknown, refined_fixture(), CITY_NAMES)

        uncovered = manifest_fixture()
        uncovered["nations"] = [{"lord_id": 10001}]
        with self.assertRaisesRegex(ValueError, "uncovered active factions"):
            validate_manifest(uncovered, refined_fixture(), CITY_NAMES)

    def test_stable_id_keyed_overrides_reject_names_and_invalid_values(self) -> None:
        named = manifest_fixture()
        named["overrides"] = {"가": {"color": "#FFFFFF"}}
        with self.assertRaisesRegex(ValueError, "stable lord id"):
            validate_manifest(named, refined_fixture(), CITY_NAMES)

        for override in (
            {"color": "blue"},
            {"gold": -1},
            {"rice": True},
            {"tech": 1.5},
            {"scale": 9},
        ):
            with self.subTest(override=override):
                data = manifest_fixture()
                data["overrides"] = {"10001": override}
                with self.assertRaises(ValueError):
                    validate_manifest(data, refined_fixture(), CITY_NAMES)

    def test_diplomacy_requires_known_stable_lords_and_reviewed_state_term(self) -> None:
        valid = manifest_fixture()
        valid["diplomacy"] = [[10001, 10003, 0, 0]]
        self.assertEqual(validate_manifest(valid, refined_fixture(), CITY_NAMES)["diplomacy"], [[10001, 10003, 0, 0]])

        for diplomacy in (
            [[10001, 10999, 0, 0]],
            [[10001, 10003, 9, 0]],
            [[10001, 10003, 0, 1]],
            [[10001, 10001, 0, 0]],
        ):
            with self.subTest(diplomacy=diplomacy):
                data = manifest_fixture()
                data["diplomacy"] = diplomacy
                with self.assertRaises(ValueError):
                    validate_manifest(data, refined_fixture(), CITY_NAMES)

    def test_city_override_rejects_missing_non_owned_or_overlapping_capitals(self) -> None:
        for overrides in (
            {"10001": {"cities": []}},
            {"10001": {"cities": ["나"]}},
            {"10001": {"cities": ["가", "다"]}},
            {"10001": {"cities": ["가", "라"]}, "10003": {"cities": ["다", "라"]}},
        ):
            with self.subTest(overrides=overrides):
                data = manifest_fixture()
                data["overrides"] = copy.deepcopy(overrides)
                with self.assertRaises(ValueError):
                    validate_manifest(data, refined_fixture(), CITY_NAMES)


if __name__ == "__main__":
    unittest.main()
