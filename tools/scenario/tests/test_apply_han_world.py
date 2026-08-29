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
