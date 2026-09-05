"""Administrative aliases at a proven seat retain their historical parent."""

import unittest

from tools.map.build_terrain_grid import fold_to_jun


class FoldAdminRepresentativeTest(unittest.TestCase):
    def places(self, representative_lon=108.93719):
        # The earlier competing point wins the old nearest-point tie even
        # though the named representative and its county are the same place.
        return [
            {
                "id": "competing-parent", "nameCh": "馮翊郡", "nameFt": "馮翊郡",
                "kind": "COMMANDERY", "lon": representative_lon, "lat": 34.31799,
            },
            {
                "id": "212275", "nameCh": "京兆尹", "nameFt": "京兆尹",
                "kind": "COMMANDERY", "lon": representative_lon, "lat": 34.31799,
            },
            {
                "id": "70623", "nameCh": "长安县", "nameFt": "長安縣",
                "kind": "COUNTY", "lon": 108.93719, "lat": 34.31799,
            },
        ]

    def junguozhi(self):
        return [
            {
                "name": "京兆尹", "seat": "長安",
                "counties": [{"name": "長安", "lon": 108.93719, "lat": 34.31799}],
            },
            {"name": "馮翊郡", "counties": []},
        ]

    def test_named_representative_at_proven_county_seat_keeps_parent_without_becoming_hub(self):
        parents, hubs, names, zhi = fold_to_jun(self.places(), None, self.junguozhi())

        self.assertEqual("京兆尹", names[parents[1]])
        self.assertEqual("京兆尹", names[parents[2]])
        self.assertEqual(2, hubs[names.index("京兆尹")])
        self.assertEqual([2], zhi)

    def test_displaced_representative_is_not_reattached_only_because_its_name_matches(self):
        parents, hubs, names, zhi = fold_to_jun(
            self.places(representative_lon=109.03719), None, self.junguozhi()
        )

        self.assertEqual("馮翊郡", names[parents[1]])
        self.assertEqual(2, hubs[names.index("京兆尹")])
        self.assertEqual([2], zhi)

    def test_proximity_fallback_is_not_treated_as_a_proven_named_county_seat(self):
        places = self.places()
        places[2].update(nameCh="池阳县", nameFt="池陽縣")
        parents, _, names, _ = fold_to_jun(places, None, self.junguozhi())

        self.assertEqual("馮翊郡", names[parents[1]])


if __name__ == "__main__":
    unittest.main()
