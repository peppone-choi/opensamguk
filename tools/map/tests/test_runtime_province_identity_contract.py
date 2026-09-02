import json
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]


class RuntimeProvinceIdentityContractTest(unittest.TestCase):
    def setUp(self):
        self.world = json.loads((ROOT / "infra/src/main/resources/map/han.json").read_text())
        self.tiles = json.loads((ROOT / "data/map/han-tiles.json").read_text())
        self.ledger = json.loads((
            ROOT / "data/curated/han/runtime-province-identity-unresolved-v1.json"
        ).read_text())

    def test_exact_bindings_are_unique_and_reference_the_same_physical_place(self):
        canonical_cities = self.tiles["cities"]
        records = self.tiles["provinceRecords"]
        bound = [city for city in self.world["cities"] if "provinceId" in city]
        self.assertEqual(743, len(bound))
        self.assertEqual(743, len({city["provinceId"] for city in bound}))
        for city in bound:
            record = records[city["provinceId"]]
            self.assertIsNotNone(record["cityIndex"])
            self.assertEqual(city["physicalPlaceId"], canonical_cities[record["cityIndex"]]["id"])

    def test_unresolved_commandery_points_are_explicit_and_never_nearest_assigned(self):
        unresolved_ids = {row["runtimeCityId"] for row in self.ledger["rows"]}
        unbound_ids = {city["id"] for city in self.world["cities"] if "provinceId" not in city}
        self.assertEqual(31, len(unresolved_ids))
        self.assertEqual(unresolved_ids, unbound_ids)
        self.assertTrue(self.ledger["policy"]["forbidNearestGeometryAssignment"])


if __name__ == "__main__":
    unittest.main()
