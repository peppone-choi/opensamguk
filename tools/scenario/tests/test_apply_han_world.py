from __future__ import annotations

import importlib.util
import json
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
MODULE_PATH = ROOT / "tools/scenario/apply_han_world.py"
SPEC = importlib.util.spec_from_file_location("apply_han_world", MODULE_PATH)
assert SPEC and SPEC.loader
apply_han_world = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(apply_han_world)


class HanWorldOwnershipOverrideTest(unittest.TestCase):
    def test_legacy_rewrite_does_not_relabel_774_ids_as_v3(self) -> None:
        by_jun, id_of, seat_of = apply_han_world.load_world()
        original = {"nation": [], "general": []}
        rewritten, _ = apply_han_world.rewrite(
            original, "scenario_1021", by_jun, id_of, seat_of, {}, {"scenario_1021": {}}
        )
        self.assertEqual("han-world-v2", rewritten["map"]["mapName"])
        self.assertNotIn("cityIdentityVersion", rewritten)

    def test_legacy_rewrite_rejects_already_migrated_v3_resources(self) -> None:
        by_jun, id_of, seat_of = apply_han_world.load_world()
        original = {"nation": [], "general": [], "cityIdentityVersion": "han-world-v3"}
        with self.assertRaisesRegex(ValueError, "requires --map han-world-v3"):
            apply_han_world.rewrite(
                original, "scenario_1021", by_jun, id_of, seat_of, {}, {"scenario_1021": {}}
            )

    def test_numeric_city_migration_uses_physical_identity_and_explicit_replacements(self) -> None:
        old = [
            {"id": 1, "physicalPlaceId": "same"},
            {"id": 2, "physicalPlaceId": "retired"},
        ]
        new = [
            {"id": 7, "physicalPlaceRef": "chgis:v6:cnty:same"},
            {"id": 8, "physicalPlaceRef": "chgis:v6:cnty:replacement"},
        ]
        candidates = [{"origin": "CURRENT_780", "legacyCityId": 22, "legacyTileId": "retired"}]
        migration = [{
            "oldCityId": 22, "newCityId": 8,
            "disposition": "REPLACED_UNRELATED_NODE",
        }]

        self.assertEqual(
            {1: 7, 2: 8},
            apply_han_world.build_physical_id_migration(old, new, candidates, migration),
        )

    def test_numeric_city_migration_rejects_unknown_removed_and_duplicate_places(self) -> None:
        with self.assertRaisesRegex(ValueError, "explicit migration"):
            apply_han_world.build_physical_id_migration(
                [{"id": 1, "physicalPlaceId": "removed"}],
                [{"id": 1, "physicalPlaceRef": "chgis:v6:cnty:new"}],
                [], [],
            )

    def test_v2_transition_is_relabelled_once_without_double_mapping_numeric_ids(self) -> None:
        by_jun, id_of, seat_of = apply_han_world.load_world("han-world-v3")
        ownership = {"scenario_1021": {}}
        migration = {412: 414, 414: 416}
        original = {
            "map": {"mapName": "han-world-v2", "unitSet": "han"},
            "nation": [],
            "general": [[0, "원윤", None, "", 412]],
        }

        migrated, _ = apply_han_world.rewrite(
            original, "scenario_1021", by_jun, id_of, seat_of, {}, ownership, migration
        )
        self.assertEqual(414, migrated["general"][0][apply_han_world.LOC_SLOT])

        # b2c795a2 emitted the 781-domain IDs under the transitional V2 label.
        transitional = json.loads(json.dumps(migrated))
        transitional["map"]["mapName"] = "han-world-v2"
        transitional["cityIdentityVersion"] = "han-world-v2"
        relabelled, _ = apply_han_world.rewrite(
            transitional, "scenario_1021", by_jun, id_of, seat_of, {}, ownership, migration
        )
        self.assertEqual(414, relabelled["general"][0][apply_han_world.LOC_SLOT])

        rerun, _ = apply_han_world.rewrite(
            relabelled, "scenario_1021", by_jun, id_of, seat_of, {}, ownership, migration
        )
        self.assertEqual(relabelled, rerun)
        with self.assertRaisesRegex(ValueError, "duplicate physical"):
            apply_han_world.build_physical_id_migration(
                [{"id": 1, "physicalPlaceId": "p"}, {"id": 2, "physicalPlaceId": "p"}],
                [{"id": 1, "physicalPlaceRef": "chgis:v6:cnty:p"}],
                [], [],
            )

    def test_world_v3_loader_verifies_manifest_and_has_all_781_nodes(self) -> None:
        by_jun, id_of, seat_of = apply_han_world.load_world("han-world-v3")
        self.assertEqual(781, len({city for group in by_jun.values() for city in group}))
        self.assertIn(781, by_jun["제남국"])

    def test_all_15_scenarios_migrate_references_and_licheng_owner_from_source(self) -> None:
        self.assertEqual(15, len(apply_han_world.ACTIVE_GENERAL_CONTRACTS))
        by_jun, id_of, seat_of = apply_han_world.load_world("han-world-v3")
        known = set(range(1, 782))
        ownership = json.loads(apply_han_world.OWNERSHIP.read_text(encoding="utf-8"))
        che2jun = {
            key: value["jun"]
            for key, value in json.loads(
                apply_han_world.CHE_TO_JUN.read_text(encoding="utf-8")
            )["map"].items()
        }
        old = json.loads(apply_han_world.HAN_MAP.read_text(encoding="utf-8"))["cities"]
        new = json.loads(apply_han_world.HAN_V3_MAP.read_text(encoding="utf-8"))["cities"]
        candidates = json.loads(
            apply_han_world.ROUTE_CANDIDATES.read_text(encoding="utf-8")
        )["candidates"]
        migration_doc = json.loads(
            apply_han_world.ROUTE_MIGRATION.read_text(encoding="utf-8")
        )
        migration = apply_han_world.build_physical_id_migration(
            old, new, candidates, migration_doc["rows"]
        )
        self.assertEqual(774, len(migration))
        selection_by_id = {
            row["numericCityId"]: row
            for row in json.loads(
                apply_han_world.ROUTE_SELECTION.read_text(encoding="utf-8")
            )["routeNodes"]
        }
        self.assertEqual(780, len(migration_doc["rows"]))
        for row in migration_doc["rows"]:
            self.assertEqual(row["oldCityId"], row["newCityId"])
            self.assertEqual(
                row["routeNodeKey"], selection_by_id[row["newCityId"]]["routeNodeKey"]
            )
        self.assertEqual(
            [{
                "administrativeUnitId": "hhs:112:濟南國:010",
                "disposition": "APPENDED_NEW_WORLD_IDENTITY",
                "newCityId": 781,
                "physicalPlaceRef": "chgis:v6:cnty:45022",
                "routeNodeKey": "f1aae98e-ead0-49f7-b4da-e427277a66ef",
            }],
            migration_doc["appendedRows"],
        )

        for code in sorted(apply_han_world.ACTIVE_GENERAL_CONTRACTS):
            with self.subTest(code=code):
                raw = json.loads(
                    (apply_han_world.SCEN / f"{code}.json").read_text(encoding="utf-8")
                )
                rewritten, _ = apply_han_world.rewrite(
                    raw, code, by_jun, id_of, seat_of, che2jun, ownership, migration
                )
                self.assertEqual("han-world-v3", rewritten["cityIdentityVersion"])
                nation_rows = {row[0]: row for row in rewritten["nation"]}
                all_claims = [
                    city
                    for row in nation_rows.values()
                    for city in row[apply_han_world.NATION_CITIES]
                ]
                self.assertTrue(set(all_claims) <= known)
                for key in apply_han_world.GENERAL_KEYS:
                    for general in rewritten.get(key) or []:
                        self.assertTrue(general[4] is None or general[4] in known)
                expected_owner = next(
                    (
                        nation for nation, source in ownership[code]["nations"].items()
                        if "제남국" in (source.get("juns") or [])
                    ),
                    None,
                )
                actual_owner = next(
                    (nation for nation, row in nation_rows.items() if 781 in row[apply_han_world.NATION_CITIES]),
                    None,
                )
                self.assertEqual(expected_owner, actual_owner)

    def test_active_general_contracts_are_preflighted_before_rewrite(self) -> None:
        with self.assertRaisesRegex(ValueError, "scenario_missing"):
            apply_han_world.validate_active_general_contracts(["scenario_1010", "scenario_missing"])

    def test_yellow_turban_han_faction_is_he_jins_practical_control(self) -> None:
        by_jun, id_of, seat_of = apply_han_world.load_world("han-world-v3")
        che2jun = {
            key: value["jun"]
            for key, value in json.loads(apply_han_world.CHE_TO_JUN.read_text(encoding="utf-8"))["map"].items()
        }
        ownership = json.loads(apply_han_world.OWNERSHIP.read_text(encoding="utf-8"))
        source = ownership["scenario_1010"]
        expected_juns = {
            "하남윤", "하내군", "하동군", "홍농군", "경조윤", "좌풍익", "우부풍",
            "광양군", "하간국", "태원군", "발해군", "상당군", "제북국",
            "광평군", "장락군", "장무군", "장릉군",
        }

        self.assertEqual(set(source["nations"]["후한"]["juns"]), expected_juns)

        document = json.loads(
            (apply_han_world.SCEN / "scenario_1010.json").read_text(encoding="utf-8")
        )
        rewritten, _ = apply_han_world.rewrite(
            document, "scenario_1010", by_jun, id_of, seat_of, che2jun, ownership, city_id_migration={},
        )
        nations = {row[0]: row for row in rewritten["nation"]}
        self.assertIn("후한", nations)
        self.assertEqual(
            set(nations["후한"][apply_han_world.NATION_CITIES]),
            {city for jun in expected_juns for city in by_jun.get(jun, [])},
        )
        self.assertEqual(nations["후한"][1], "#B82020")
        self.assertEqual(nations["황건적"][1], "#E78C11")
        names = {row[1] for key in apply_han_world.GENERAL_KEYS for row in rewritten.get(key, [])}
        self.assertIn("유굉", names)
        self.assertIn("유변", names)
        self.assertIn("유협", names)
        self.assertNotIn("소제1", names)
        self.assertNotIn("헌제", names)
        self.assertEqual(rewritten["imperialGenerals"], ["유굉"])
        roster = {row[1]: row for key in apply_han_world.GENERAL_KEYS for row in rewritten.get(key, [])}
        self.assertEqual(roster["유굉"][8], 12)
        self.assertEqual(roster["하진"][8], 11)

    def test_imperial_roster_uses_personal_names_for_each_start_date(self) -> None:
        by_jun, id_of, seat_of = apply_han_world.load_world("han-world-v3")
        che2jun = {
            key: value["jun"]
            for key, value in json.loads(apply_han_world.CHE_TO_JUN.read_text(encoding="utf-8"))["map"].items()
        }
        ownership = json.loads(apply_han_world.OWNERSHIP.read_text(encoding="utf-8"))
        expected = {
            "scenario_1020": ["유협"],
            "scenario_1041": ["유협", "원술"],
            "scenario_1100": ["조비", "유선"],
            "scenario_1110": ["조예", "유선"],
        }
        for code, emperor_names in expected.items():
            document = json.loads((apply_han_world.SCEN / f"{code}.json").read_text(encoding="utf-8"))
            rewritten, _ = apply_han_world.rewrite(
                document, code, by_jun, id_of, seat_of, che2jun, ownership, city_id_migration={},
            )
            self.assertEqual(rewritten["imperialGenerals"], emperor_names)
            roster_names = {row[1] for key in apply_han_world.GENERAL_KEYS for row in rewritten.get(key, [])}
            self.assertTrue(set(emperor_names) <= roster_names)
            self.assertNotIn("헌제", roster_names)

    def test_if_scenario_preserves_its_own_placement_basis(self) -> None:
        by_jun, id_of, seat_of = apply_han_world.load_world("han-world-v3")
        che2jun = {
            key: value["jun"]
            for key, value in json.loads(apply_han_world.CHE_TO_JUN.read_text(encoding="utf-8"))["map"].items()
        }
        ownership = json.loads(apply_han_world.OWNERSHIP.read_text(encoding="utf-8"))
        document = json.loads((apply_han_world.SCEN / "scenario_1120.json").read_text(encoding="utf-8"))

        rewritten, _ = apply_han_world.rewrite(
            document, "scenario_1120", by_jun, id_of, seat_of, che2jun, ownership, city_id_migration={},
        )
        nations = {row[0]: row for row in rewritten["nation"]}

        self.assertEqual("IF_SCENARIO", rewritten["placementBasis"])
        self.assertEqual(
            {city for jun in ["우북평군", "발해군", "팽성국", "평원군", "북해국", "패국", "하간국", "거록군"] for city in by_jun[jun]},
            set(nations["공손찬"][apply_han_world.NATION_CITIES]),
        )
        self.assertIn(id_of["중모"], set(nations["원소"][apply_han_world.NATION_CITIES]))
        self.assertIn(id_of["낙양"], set(nations["원술"][apply_han_world.NATION_CITIES]))
        self.assertIn(48, set(nations["원술"][apply_han_world.NATION_CITIES]))
        self.assertNotIn(id_of["중모"], set(nations["원술"][apply_han_world.NATION_CITIES]))
        self.assertEqual(
            set(by_jun["하남윤"]) - {id_of["중모"]},
            set(nations["원술"][apply_han_world.NATION_CITIES]) & set(by_jun["하남윤"]),
        )

    def test_duplicate_general_addition_is_reported(self) -> None:
        by_jun, id_of, seat_of = apply_han_world.load_world("han-world-v3")
        ownership = json.loads(apply_han_world.OWNERSHIP.read_text(encoding="utf-8"))
        document = json.loads((apply_han_world.SCEN / "scenario_1120.json").read_text(encoding="utf-8"))
        duplicate = next(row for row in document["general"] if len(row) > 1)
        ownership["scenario_1120"]["generalAdditions"] = [duplicate]

        _, warnings = apply_han_world.rewrite(
            document, "scenario_1120", by_jun, id_of, seat_of, {}, ownership, city_id_migration={},
        )

        self.assertTrue(any(duplicate[1] in warning and "이미" in warning for warning in warnings))

    def test_every_historical_faction_has_a_reviewed_symbol_color(self) -> None:
        palette = json.loads(apply_han_world.PALETTE.read_text(encoding="utf-8"))["colors"]
        by_jun, id_of, seat_of = apply_han_world.load_world("han-world-v3")
        che2jun = {
            key: value["jun"]
            for key, value in json.loads(apply_han_world.CHE_TO_JUN.read_text(encoding="utf-8"))["map"].items()
        }
        ownership = json.loads(apply_han_world.OWNERSHIP.read_text(encoding="utf-8"))

        for code in sorted(key for key in ownership if key.startswith("scenario_")):
            document = json.loads((apply_han_world.SCEN / f"{code}.json").read_text(encoding="utf-8"))
            rewritten, _ = apply_han_world.rewrite(
                document, code, by_jun, id_of, seat_of, che2jun, ownership, city_id_migration={},
            )
            for row in rewritten["nation"]:
                with self.subTest(code=code, nation=row[0]):
                    self.assertIn(row[0], palette)
                    self.assertEqual(row[1], palette[row[0]]["hex"])

    def test_all_scenario_borders_are_sourced_disjoint_and_use_known_cities(self) -> None:
        by_jun, id_of, seat_of = apply_han_world.load_world("han-world-v3")
        che2jun = {
            key: value["jun"]
            for key, value in json.loads(apply_han_world.CHE_TO_JUN.read_text(encoding="utf-8"))["map"].items()
        }
        ownership = json.loads(apply_han_world.OWNERSHIP.read_text(encoding="utf-8"))
        known_cities = {city for cities in by_jun.values() for city in cities}

        for code, source in ownership.items():
            if not code.startswith("scenario_"):
                continue
            with self.subTest(code=code):
                document = json.loads((apply_han_world.SCEN / f"{code}.json").read_text(encoding="utf-8"))
                self.assertEqual(document["startYear"], source["fileStartYear"])
                self.assertTrue(all(
                    str(nation.get("basis", "")).strip()
                    for nation in source["nations"].values()
                ))
                rewritten, _ = apply_han_world.rewrite(
                    document, code, by_jun, id_of, seat_of, che2jun, ownership, city_id_migration={},
                )
                claimed: set[int] = set()
                for nation in rewritten["nation"]:
                    cities = set(nation[apply_han_world.NATION_CITIES])
                    self.assertFalse(claimed & cities, f"duplicate claims: {claimed & cities}")
                    self.assertTrue(cities <= known_cities)
                    claimed |= cities

    def test_all_scenarios_materialize_literal_active_general_contracts(self) -> None:
        expected = {
            "scenario_1010": (175, 230), "scenario_1020": (231, 299),
            "scenario_1021": (339, 339), "scenario_1030": (250, 327),
            "scenario_1031": (365, 365), "scenario_1040": (250, 327),
            "scenario_1041": (363, 363), "scenario_1050": (248, 320),
            "scenario_1060": (238, 305), "scenario_1070": (252, 317),
            "scenario_1080": (237, 302), "scenario_1090": (230, 289),
            "scenario_1100": (206, 260), "scenario_1110": (196, 249),
            "scenario_1120": (240, 309),
        }
        by_jun, id_of, seat_of = apply_han_world.load_world("han-world-v3")
        che2jun = {
            key: value["jun"]
            for key, value in json.loads(apply_han_world.CHE_TO_JUN.read_text(encoding="utf-8"))["map"].items()
        }
        ownership = json.loads(apply_han_world.OWNERSHIP.read_text(encoding="utf-8"))

        for code, (base, extended) in expected.items():
            with self.subTest(code=code):
                document = json.loads((apply_han_world.SCEN / f"{code}.json").read_text(encoding="utf-8"))
                rewritten, _ = apply_han_world.rewrite(
                    document, code, by_jun, id_of, seat_of, che2jun, ownership, city_id_migration={},
                )
                self.assertEqual(
                    rewritten.get("seedContract"),
                    {"activeGenerals": {"base": base, "extended": extended}},
                )

    def test_new_city_counties_belong_to_wei_in_225_and_228(self) -> None:
        by_jun, id_of, seat_of = apply_han_world.load_world("han-world-v3")
        che2jun = {
            key: value["jun"]
            for key, value in json.loads(apply_han_world.CHE_TO_JUN.read_text(encoding="utf-8"))["map"].items()
        }
        ownership = json.loads(apply_han_world.OWNERSHIP.read_text(encoding="utf-8"))

        for code, wei_name in (("scenario_1100", "조비"), ("scenario_1110", "조예")):
            with self.subTest(code=code):
                document = json.loads((apply_han_world.SCEN / f"{code}.json").read_text(encoding="utf-8"))
                rewritten, _ = apply_han_world.rewrite(
                    document, code, by_jun, id_of, seat_of, che2jun, ownership, city_id_migration={},
                )
                cities_by_nation = {row[0]: set(row[apply_han_world.NATION_CITIES]) for row in rewritten["nation"]}

                self.assertTrue({597, 594}.issubset(cities_by_nation[wei_name]))
                self.assertFalse(any(
                    {597, 594} & cities
                    for nation, cities in cities_by_nation.items()
                    if nation != wei_name
                ))

    def test_225_nanzhong_uses_liu_shan_rebel_coalition_and_keeps_ailao_unowned(self) -> None:
        by_jun, id_of, seat_of = apply_han_world.load_world("han-world-v3")
        che2jun = {
            key: value["jun"]
            for key, value in json.loads(apply_han_world.CHE_TO_JUN.read_text(encoding="utf-8"))["map"].items()
        }
        ownership = json.loads(apply_han_world.OWNERSHIP.read_text(encoding="utf-8"))
        source = ownership["scenario_1100"]

        self.assertEqual(source["nationRenames"], {"유비": "유선", "맹획": "남중 반란군"})
        self.assertEqual(
            source["nations"]["남중 반란군"]["juns"],
            ["익주군", "월휴군", "장가군"],
        )
        self.assertTrue(
            {"탕거군", "강양군", "문산군"}
            <= set(source["nations"]["유선"]["juns"])
        )
        self.assertTrue(
            {"탕거군", "강양군", "문산군"}
            <= set(ownership["scenario_1110"]["nations"]["유선"]["juns"])
        )
        for code in ("scenario_1100", "scenario_1110"):
            self.assertTrue(
                {"신도군", "한창군", "기춘군", "비릉전농교위"}
                <= set(ownership[code]["nations"]["손권"]["juns"])
            )
            wei = "조비" if code == "scenario_1100" else "조예"
            self.assertTrue(
                {
                    "양국", "노국", "이성군", "동완군", "낙평군", "낙릉군",
                    "남향군", "광평군", "광위군", "신평군", "감릉군",
                    "장무군", "장릉군", "서평군", "장락군", "양안군",
                }
                <= set(ownership[code]["nations"][wei]["juns"])
            )
        self.assertNotIn(
            "남만",
            {
                jun
                for nation in source["nations"].values()
                for jun in nation.get("juns", [])
            },
        )

        document = json.loads(
            (apply_han_world.SCEN / "scenario_1100.json").read_text(encoding="utf-8")
        )
        rewritten, warnings = apply_han_world.rewrite(
            document, "scenario_1100", by_jun, id_of, seat_of, che2jun, ownership, city_id_migration={},
        )
        nations = {row[0]: row for row in rewritten["nation"]}
        self.assertIn("유선", nations)
        self.assertIn("남중 반란군", nations)
        self.assertNotIn("유비", nations)
        self.assertNotIn("맹획", nations)
        # The forbidden X060 polity placeholder is absent from the reviewed V3 domain.
        self.assertNotIn("남만", id_of)
        world = apply_han_world._load_verified_v3_world()
        self.assertFalse(any(city["physicalPlaceRef"] == "external:v1:X060" for city in world["cities"]))
        self.assertFalse(any("감릉군" in warning for warning in warnings))


if __name__ == "__main__":
    unittest.main()
