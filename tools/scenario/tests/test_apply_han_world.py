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
    def test_active_general_contracts_are_preflighted_before_rewrite(self) -> None:
        with self.assertRaisesRegex(ValueError, "scenario_missing"):
            apply_han_world.validate_active_general_contracts(["scenario_1010", "scenario_missing"])

    def test_yellow_turban_han_faction_is_he_jins_practical_control(self) -> None:
        by_jun, id_of, seat_of = apply_han_world.load_world()
        che2jun = {
            key: value["jun"]
            for key, value in json.loads(apply_han_world.CHE_TO_JUN.read_text(encoding="utf-8"))["map"].items()
        }
        ownership = json.loads(apply_han_world.OWNERSHIP.read_text(encoding="utf-8"))
        source = ownership["scenario_1010"]
        expected_juns = {
            "하남윤", "하내군", "하동군", "홍농군", "경조윤", "좌풍익", "우부풍",
            "광양군", "하간국", "태원군", "발해군", "상당군",
        }

        self.assertEqual(set(source["nations"]["후한"]["juns"]), expected_juns)

        document = json.loads(
            (apply_han_world.SCEN / "scenario_1010.json").read_text(encoding="utf-8")
        )
        rewritten, _ = apply_han_world.rewrite(
            document, "scenario_1010", by_jun, id_of, seat_of, che2jun, ownership,
        )
        nations = {row[0]: row for row in rewritten["nation"]}
        self.assertIn("후한", nations)
        self.assertEqual(
            set(nations["후한"][apply_han_world.NATION_CITIES]),
            {city for jun in expected_juns for city in by_jun[jun]},
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
        by_jun, id_of, seat_of = apply_han_world.load_world()
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
                document, code, by_jun, id_of, seat_of, che2jun, ownership,
            )
            self.assertEqual(rewritten["imperialGenerals"], emperor_names)
            roster_names = {row[1] for key in apply_han_world.GENERAL_KEYS for row in rewritten.get(key, [])}
            self.assertTrue(set(emperor_names) <= roster_names)
            self.assertNotIn("헌제", roster_names)

    def test_if_scenario_preserves_its_own_placement_basis(self) -> None:
        by_jun, id_of, seat_of = apply_han_world.load_world()
        che2jun = {
            key: value["jun"]
            for key, value in json.loads(apply_han_world.CHE_TO_JUN.read_text(encoding="utf-8"))["map"].items()
        }
        ownership = json.loads(apply_han_world.OWNERSHIP.read_text(encoding="utf-8"))
        document = json.loads((apply_han_world.SCEN / "scenario_1120.json").read_text(encoding="utf-8"))

        rewritten, _ = apply_han_world.rewrite(
            document, "scenario_1120", by_jun, id_of, seat_of, che2jun, ownership,
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

    def test_every_historical_faction_has_a_reviewed_symbol_color(self) -> None:
        palette = json.loads(apply_han_world.PALETTE.read_text(encoding="utf-8"))["colors"]
        by_jun, id_of, seat_of = apply_han_world.load_world()
        che2jun = {
            key: value["jun"]
            for key, value in json.loads(apply_han_world.CHE_TO_JUN.read_text(encoding="utf-8"))["map"].items()
        }
        ownership = json.loads(apply_han_world.OWNERSHIP.read_text(encoding="utf-8"))

        for code in sorted(key for key in ownership if key.startswith("scenario_")):
            document = json.loads((apply_han_world.SCEN / f"{code}.json").read_text(encoding="utf-8"))
            rewritten, _ = apply_han_world.rewrite(
                document, code, by_jun, id_of, seat_of, che2jun, ownership,
            )
            for row in rewritten["nation"]:
                with self.subTest(code=code, nation=row[0]):
                    self.assertIn(row[0], palette)
                    self.assertEqual(row[1], palette[row[0]]["hex"])

    def test_all_scenario_borders_are_sourced_disjoint_and_use_known_cities(self) -> None:
        by_jun, id_of, seat_of = apply_han_world.load_world()
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
                    document, code, by_jun, id_of, seat_of, che2jun, ownership,
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
        by_jun, id_of, seat_of = apply_han_world.load_world()
        che2jun = {
            key: value["jun"]
            for key, value in json.loads(apply_han_world.CHE_TO_JUN.read_text(encoding="utf-8"))["map"].items()
        }
        ownership = json.loads(apply_han_world.OWNERSHIP.read_text(encoding="utf-8"))

        for code, (base, extended) in expected.items():
            with self.subTest(code=code):
                document = json.loads((apply_han_world.SCEN / f"{code}.json").read_text(encoding="utf-8"))
                rewritten, _ = apply_han_world.rewrite(
                    document, code, by_jun, id_of, seat_of, che2jun, ownership,
                )
                self.assertEqual(
                    rewritten.get("seedContract"),
                    {"activeGenerals": {"base": base, "extended": extended}},
                )

    def test_new_city_counties_belong_to_wei_in_225_and_228(self) -> None:
        by_jun, id_of, seat_of = apply_han_world.load_world()
        che2jun = {
            key: value["jun"]
            for key, value in json.loads(apply_han_world.CHE_TO_JUN.read_text(encoding="utf-8"))["map"].items()
        }
        ownership = json.loads(apply_han_world.OWNERSHIP.read_text(encoding="utf-8"))

        for code, wei_name in (("scenario_1100", "조비"), ("scenario_1110", "조예")):
            with self.subTest(code=code):
                document = json.loads((apply_han_world.SCEN / f"{code}.json").read_text(encoding="utf-8"))
                rewritten, _ = apply_han_world.rewrite(
                    document, code, by_jun, id_of, seat_of, che2jun, ownership,
                )
                cities_by_nation = {row[0]: set(row[apply_han_world.NATION_CITIES]) for row in rewritten["nation"]}

                self.assertTrue({435, 590}.issubset(cities_by_nation[wei_name]))
                self.assertFalse(any(
                    {435, 590} & cities
                    for nation, cities in cities_by_nation.items()
                    if nation != wei_name
                ))


if __name__ == "__main__":
    unittest.main()
