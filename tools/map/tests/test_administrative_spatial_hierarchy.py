import unittest

from tools.map.administrative_spatial_hierarchy import (
    audit_hierarchy,
    audit_transition_debt,
    validate_hierarchy,
)


def fixture_document() -> dict:
    return {
        "_meta": {
            "cols": 3,
            "rows": 2,
            "terrainLegend": {"0": "SEA", "1": "PLAIN"},
        },
        "terrain": ["111", "111"],
        "owner": [[0, 2], [1, 2], [-1, 2]],
        "provinceRecords": [
            {
                "id": "P1",
                "kind": "SPATIAL_PROVINCE",
                "jurisdictionId": "COUNTY-A",
                "parentRegionId": "R1",
            },
            {
                "id": "P2",
                "kind": "SPATIAL_PROVINCE",
                "jurisdictionId": "COUNTY-B",
                "parentRegionId": "R1",
            },
        ],
        "jurisdictionRecords": [
            {
                "id": "COUNTY-A",
                "kind": "COUNTY",
                "parentRegionId": "R1",
                "seatPlaceId": "SEAT-A",
                "provinceIds": ["P1"],
            },
            {
                "id": "COUNTY-B",
                "kind": "COUNTY",
                "parentRegionId": "R1",
                "seatPlaceId": "SEAT-B",
                "provinceIds": ["P2"],
            },
        ],
        "parentRegions": [{"id": "R1", "displayName": "A군"}],
        "settlementRecords": [
            {"id": "S1", "seatPlaceId": "SEAT-A", "jurisdictionId": "COUNTY-A"},
            {"id": "S2", "seatPlaceId": "SEAT-B", "jurisdictionId": "COUNTY-B"},
        ],
    }


class AdministrativeSpatialHierarchyTest(unittest.TestCase):
    def test_audits_legacy_direct_territory_and_mixed_city_kinds(self):
        document = fixture_document()
        document.pop("jurisdictionRecords")
        document.pop("settlementRecords")
        document["provinceRecords"] = [
            {"id": "P1", "kind": "COUNTY", "parentRegionId": "R1"},
            {"id": "P2", "kind": "DIRECT_TERRITORY", "parentRegionId": "R2"},
        ]
        document["parentRegions"].append({"id": "R2", "displayName": "B군"})
        document["cities"] = [
            {"kind": "COUNTY"},
            {"kind": "COMMANDERY"},
            {"kind": "PROVINCE"},
        ]

        debt = audit_transition_debt(document)

        self.assertEqual(("P2",), debt.direct_territory_ids)
        self.assertEqual(("R2",), debt.parents_without_county_ids)
        self.assertEqual({"COMMANDERY": 1, "COUNTY": 1, "PROVINCE": 1}, debt.city_kind_counts)

    def test_accepts_counties_with_one_or_more_spatial_provinces(self):
        audit = validate_hierarchy(fixture_document())

        self.assertEqual(2, audit.province_count)
        self.assertEqual(2, audit.jurisdiction_count)
        self.assertEqual(1, audit.parent_count)
        self.assertEqual((), audit.unassigned_province_ids)
        self.assertEqual((), audit.direct_territory_ids)
        self.assertEqual((), audit.duplicate_seat_place_ids)
        self.assertEqual(0, audit.enclosed_non_playable_land_components)

    def test_rejects_a_playable_province_without_jurisdiction(self):
        document = fixture_document()
        document["provinceRecords"][1]["jurisdictionId"] = None

        with self.assertRaisesRegex(ValueError, "unassigned playable province P2"):
            validate_hierarchy(document)

    def test_rejects_a_direct_commandery_territory(self):
        document = fixture_document()
        document["provinceRecords"][1].update(
            kind="DIRECT_TERRITORY",
            jurisdictionId=None,
        )

        audit = audit_hierarchy(document)
        self.assertEqual(("P2",), audit.direct_territory_ids)
        with self.assertRaisesRegex(ValueError, "direct territory P2"):
            validate_hierarchy(document)

    def test_rejects_a_commandery_polygon_namespace(self):
        document = fixture_document()
        document["provinceRecords"][1].update(
            id="COMMANDERY-R1",
            kind="COMMANDERY",
        )
        document["jurisdictionRecords"][1]["provinceIds"] = ["COMMANDERY-R1"]

        with self.assertRaisesRegex(ValueError, "commandery geometry COMMANDERY-R1"):
            validate_hierarchy(document)

    def test_rejects_a_cross_commandery_province_binding(self):
        document = fixture_document()
        document["parentRegions"].append({"id": "R2", "displayName": "B군"})
        document["jurisdictionRecords"][1]["parentRegionId"] = "R2"

        with self.assertRaisesRegex(ValueError, "province P2 parent R1 disagrees"):
            validate_hierarchy(document)

    def test_rejects_a_county_without_a_spatial_province(self):
        document = fixture_document()
        document["jurisdictionRecords"].append({
            "id": "COUNTY-C",
            "kind": "COUNTY",
            "parentRegionId": "R1",
            "seatPlaceId": "SEAT-C",
            "provinceIds": [],
        })

        with self.assertRaisesRegex(ValueError, "jurisdiction COUNTY-C has no province"):
            validate_hierarchy(document)

    def test_rejects_duplicate_physical_settlement_markers(self):
        document = fixture_document()
        document["settlementRecords"].append({
            "id": "S3",
            "seatPlaceId": "SEAT-A",
            "jurisdictionId": "COUNTY-A",
        })

        audit = audit_hierarchy(document)
        self.assertEqual(("SEAT-A",), audit.duplicate_seat_place_ids)
        with self.assertRaisesRegex(ValueError, "duplicate physical settlement SEAT-A"):
            validate_hierarchy(document)

    def test_rejects_an_enclosed_non_playable_land_hole(self):
        document = fixture_document()
        document["_meta"].update(cols=3, rows=3)
        document["terrain"] = ["111", "111", "111"]
        document["owner"] = [[0, 4], [-1, 1], [1, 4]]

        audit = audit_hierarchy(document)
        self.assertEqual(1, audit.enclosed_non_playable_land_components)
        with self.assertRaisesRegex(ValueError, "enclosed non-playable land"):
            validate_hierarchy(document)

    def test_allows_non_playable_land_connected_to_outside_sea(self):
        document = fixture_document()
        document["_meta"].update(cols=3, rows=3)
        document["terrain"] = ["000", "011", "011"]
        document["owner"] = [[-1, 5], [0, 2], [1, 2]]

        self.assertEqual(0, validate_hierarchy(document).enclosed_non_playable_land_components)

    def test_does_not_count_an_enclosed_lake_as_a_black_land_hole(self):
        document = fixture_document()
        document["_meta"].update(
            cols=3,
            rows=3,
            terrainLegend={"0": "SEA", "1": "PLAIN", "4": "LAKE"},
        )
        document["terrain"] = ["111", "141", "111"]
        document["owner"] = [[0, 4], [-1, 1], [1, 4]]

        self.assertEqual(0, validate_hierarchy(document).enclosed_non_playable_land_components)


if __name__ == "__main__":
    unittest.main()
