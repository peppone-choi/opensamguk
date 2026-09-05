import json
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]


class Scenario9200CityIdentityTest(unittest.TestCase):
    def test_capitals_and_generals_resolve_to_the_reviewed_v3_physical_cities(self) -> None:
        scenario = json.loads((ROOT / "infra/src/main/resources/scenario/scenario_9200.json").read_text())
        world = json.loads((ROOT / "infra/src/main/resources/map/han-world-v3.json").read_text())
        self.assertEqual("han-world-v3", scenario["map"]["mapName"])
        self.assertEqual("han-world-v3", scenario["cityIdentityVersion"])
        cities = {row["id"]: row for row in world["cities"]}
        expected_places = ("chgis:v6:cnty:82828", "chgis:v6:cnty:70623")
        self.assertEqual(2, len(scenario["nation"]))
        self.assertEqual(2, len(scenario["general"]))
        for nation, general, expected in zip(scenario["nation"], scenario["general"], expected_places):
            with self.subTest(nation=nation[0]):
                self.assertEqual(1, len(nation[8]))
                self.assertIs(type(nation[8][0]), int)
                self.assertIs(type(general[4]), int)
                self.assertEqual(expected, cities[nation[8][0]]["physicalPlaceRef"])
                self.assertEqual(expected, cities[general[4]]["physicalPlaceRef"])


if __name__ == "__main__":
    unittest.main()
