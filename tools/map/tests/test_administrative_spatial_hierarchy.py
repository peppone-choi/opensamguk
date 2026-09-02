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
            },
            {
                "id": "P2",
                "kind": "SPATIAL_PROVINCE",
                "jurisdictionId": "COUNTY-B",
            },
        ],
        "jurisdictionRecords": [
            {
                "id": "COUNTY-A",
                "kind": "COUNTY",
                "commanderyId": "R1",
                "seatPlaceId": "SEAT-A",
                "provinceIds": ["P1"],
            },
            {
                "id": "COUNTY-B",
                "kind": "COUNTY",
                "commanderyId": "R1",
                "seatPlaceId": "SEAT-B",
                "provinceIds": ["P2"],
            },
        ],
        "commanderyRecords": [{
            "id": "R1",
            "displayName": "A군",
            "kind": "COMMANDERY",
            "seatJurisdictionId": "COUNTY-A",
            "jurisdictionIds": ["COUNTY-A", "COUNTY-B"],
        }],
        "parentRegions": [{"id": "R1", "displayName": "A군"}],
        "settlementRecords": [
            {
                "id": "S1", "seatPlaceId": "SEAT-A", "jurisdictionId": "COUNTY-A",
                "col": 0, "row": 0, "roles": ["COUNTY_SEAT", "COMMANDERY_SEAT"],
            },
            {
                "id": "S2", "seatPlaceId": "SEAT-B", "jurisdictionId": "COUNTY-B",
                "col": 0, "row": 1, "roles": ["COUNTY_SEAT"],
            },
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

    def test_transition_audit_reads_materialized_jurisdictions_when_present(self):
        document = fixture_document()
        document["cities"] = []

        debt = audit_transition_debt(document)

        self.assertEqual((), debt.direct_territory_ids)
        self.assertEqual((), debt.parents_without_county_ids)

    def test_accepts_counties_with_one_or_more_spatial_provinces(self):
        audit = validate_hierarchy(fixture_document())

        self.assertEqual(2, audit.province_count)
        self.assertEqual(2, audit.jurisdiction_count)
        self.assertEqual(1, audit.commandery_count)
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
        document["commanderyRecords"].append({
            "id": "R2", "displayName": "B군", "kind": "COMMANDERY",
            "seatJurisdictionId": "COUNTY-B", "jurisdictionIds": ["COUNTY-B"],
        })
        document["jurisdictionRecords"][1]["commanderyId"] = "R2"
        document["settlementRecords"][1]["roles"].append("COMMANDERY_SEAT")

        with self.assertRaisesRegex(ValueError, "jurisdiction COUNTY-B belongs to multiple commanderies"):
            validate_hierarchy(document)

    def test_rejects_a_county_without_a_spatial_province(self):
        document = fixture_document()
        document["jurisdictionRecords"].append({
            "id": "COUNTY-C",
            "kind": "COUNTY",
            "commanderyId": "R1",
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
            "col": 0,
            "row": 0,
            "roles": ["COUNTY_SEAT"],
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
        document["settlementRecords"][0].update(col=2, row=1)
        document["settlementRecords"][1].update(col=1, row=2)

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
        document["settlementRecords"][1].update(col=2, row=1)

        self.assertEqual(0, validate_hierarchy(document).enclosed_non_playable_land_components)

    def test_rejects_owner_geometry_outside_the_province_namespace(self):
        document = fixture_document()
        document["owner"] = [[99, 6]]

        with self.assertRaisesRegex(ValueError, "owner references unknown province index 99"):
            validate_hierarchy(document)

    def test_rejects_a_province_record_without_geometry_cells(self):
        document = fixture_document()
        document["owner"] = [[0, 4], [-1, 2]]

        with self.assertRaisesRegex(ValueError, "province P2 has no owner cell"):
            validate_hierarchy(document)

    def test_strict_counts_pin_the_han_baseline(self):
        with self.assertRaisesRegex(ValueError, "province count 2 does not match 1524"):
            validate_hierarchy(
                fixture_document(),
                expected_province_count=1524,
                expected_commandery_count=172,
            )

    def test_rejects_a_province_below_the_configured_minimum_cell_area(self):
        with self.assertRaisesRegex(ValueError, "province P1 has 2 cells; minimum is 3"):
            validate_hierarchy(fixture_document(), expected_min_province_cells=3)

    def test_rejects_a_commandery_disguised_as_a_jurisdiction(self):
        document = fixture_document()
        document["jurisdictionRecords"][0]["kind"] = "COMMANDERY"

        with self.assertRaisesRegex(ValueError, "jurisdiction COUNTY-A has invalid kind COMMANDERY"):
            validate_hierarchy(document)

    def test_rejects_a_missing_or_orphaned_county_seat(self):
        missing = fixture_document()
        missing["jurisdictionRecords"][0]["seatPlaceId"] = "NO-SUCH-SEAT"
        with self.assertRaisesRegex(ValueError, "jurisdiction COUNTY-A seat NO-SUCH-SEAT is missing"):
            validate_hierarchy(missing)

        orphan = fixture_document()
        orphan["settlementRecords"][0]["jurisdictionId"] = "UNKNOWN"
        with self.assertRaisesRegex(ValueError, "settlement S1 references unknown jurisdiction UNKNOWN"):
            validate_hierarchy(orphan)

    def test_rejects_duplicate_county_seats_and_invalid_roles(self):
        duplicate = fixture_document()
        duplicate["jurisdictionRecords"][1]["seatPlaceId"] = "SEAT-A"
        with self.assertRaisesRegex(ValueError, "seat SEAT-A is assigned to multiple jurisdictions"):
            validate_hierarchy(duplicate)

        invalid_role = fixture_document()
        invalid_role["settlementRecords"][0]["roles"] = ["COMMANDERY"]
        with self.assertRaisesRegex(ValueError, "settlement S1 has invalid role COMMANDERY"):
            validate_hierarchy(invalid_role)

    def test_rejects_a_narrow_black_land_tendril_from_the_map_edge(self):
        document = fixture_document()
        document["_meta"].update(cols=5, rows=5)
        document["terrain"] = ["11111"] * 5
        owner = [0] * 25
        for index in (2, 7, 12):
            owner[index] = -1
        runs = []
        for value in owner:
            if runs and runs[-1][0] == value:
                runs[-1][1] += 1
            else:
                runs.append([value, 1])
        document["owner"] = runs

        audit = audit_hierarchy(document)
        self.assertEqual(1, audit.narrow_non_playable_land_tendrils)
        with self.assertRaisesRegex(ValueError, "narrow non-playable land tendril"):
            validate_hierarchy(document)

    def test_rejects_a_two_cell_wide_black_land_tendril(self):
        document = fixture_document()
        document["_meta"].update(cols=5, rows=5)
        document["terrain"] = ["11111"] * 5
        owner = [0] * 25
        for index in (1, 2, 6, 7, 11, 12):
            owner[index] = -1
        runs = []
        for value in owner:
            if runs and runs[-1][0] == value:
                runs[-1][1] += 1
            else:
                runs.append([value, 1])
        document["owner"] = runs

        self.assertEqual(1, audit_hierarchy(document).narrow_non_playable_land_tendrils)

    def test_rejects_a_two_cell_tendril_attached_to_a_broad_exterior(self):
        document = fixture_document()
        document["_meta"].update(cols=7, rows=7)
        document["terrain"] = ["1111111"] * 7
        owner = [0] * 49
        for index in range(7):
            owner[index] = -1
        for row in (1, 2, 3):
            for col in (2, 3):
                owner[row * 7 + col] = -1
        owner[40] = 1
        runs = []
        for value in owner:
            if runs and runs[-1][0] == value:
                runs[-1][1] += 1
            else:
                runs.append([value, 1])
        document["owner"] = runs
        document["settlementRecords"][1].update(col=5, row=5)

        self.assertEqual(1, audit_hierarchy(document).narrow_non_playable_land_tendrils)

    def test_internal_lake_does_not_truncate_a_black_land_tendril(self):
        document = fixture_document()
        document["_meta"].update(
            cols=5,
            rows=5,
            terrainLegend={"0": "SEA", "1": "PLAIN", "4": "LAKE"},
        )
        terrain = [list("11111") for _ in range(5)]
        terrain[2][3] = "4"
        document["terrain"] = ["".join(row) for row in terrain]
        owner = [0] * 25
        for index in (2, 7, 12, 13):
            owner[index] = -1
        owner[24] = 1
        runs = []
        for value in owner:
            if runs and runs[-1][0] == value:
                runs[-1][1] += 1
            else:
                runs.append([value, 1])
        document["owner"] = runs
        document["settlementRecords"][1].update(col=4, row=4)

        self.assertEqual(1, audit_hierarchy(document).narrow_non_playable_land_tendrils)

    def test_rejects_a_settlement_on_unowned_land(self):
        document = fixture_document()
        document["settlementRecords"][1].update(col=1, row=1)

        with self.assertRaisesRegex(
            ValueError, "settlement S2 is outside jurisdiction COUNTY-B"
        ):
            validate_hierarchy(document)

    def test_rejects_a_settlement_inside_another_county(self):
        document = fixture_document()
        document["settlementRecords"][1].update(col=1, row=0)

        with self.assertRaisesRegex(
            ValueError, "settlement S2 is outside jurisdiction COUNTY-B"
        ):
            validate_hierarchy(document)

    def test_requires_county_and_commandery_seat_roles(self):
        county = fixture_document()
        county["settlementRecords"][1]["roles"] = ["PORT"]
        with self.assertRaisesRegex(ValueError, "jurisdiction COUNTY-B seat lacks COUNTY_SEAT"):
            validate_hierarchy(county)

        commandery = fixture_document()
        commandery["settlementRecords"][0]["roles"] = ["COUNTY_SEAT"]
        with self.assertRaisesRegex(ValueError, "commandery R1 seat lacks COMMANDERY_SEAT"):
            validate_hierarchy(commandery)

    def test_rejects_commandery_seat_role_on_a_non_seat_county(self):
        document = fixture_document()
        document["settlementRecords"][1]["roles"] = ["COUNTY_SEAT", "COMMANDERY_SEAT"]

        with self.assertRaisesRegex(ValueError, "jurisdiction COUNTY-B is not a commandery seat"):
            validate_hierarchy(document)


if __name__ == "__main__":
    unittest.main()
