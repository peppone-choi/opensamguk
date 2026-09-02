import unittest
from pathlib import Path

from tools.scenario.runtime_province_fill_audit import (
    audit_repository,
    audit_runtime_fill,
    normalize_scenario_code,
)


class RuntimeProvinceFillAuditTest(unittest.TestCase):
    def test_repository_runtime_fill_debt_is_pinned_for_all_scenarios(self):
        root = Path(__file__).resolve().parents[3]

        actual = [
            (
                row.scenario_code,
                row.canonical_owned_count,
                row.runtime_colored_count,
                len(row.missing_owned_province_record_ids),
                len(row.extra_runtime_province_record_ids),
                len(row.owner_mismatches),
                len(row.owned_city_ids_without_province_index),
            )
            for row in audit_repository(root)
        ]

        self.assertEqual(
            [
                (1010, 277, 224, 54, 1, 0, 3),
                (1020, 643, 390, 253, 0, 2, 5),
                (1021, 657, 385, 274, 2, 1, 6),
                (1030, 790, 471, 320, 1, 2, 2),
                (1031, 847, 500, 348, 1, 3, 4),
                (1040, 796, 485, 311, 0, 1, 3),
                (1041, 879, 520, 359, 0, 0, 4),
                (1050, 1009, 606, 404, 1, 1, 5),
                (1060, 1009, 606, 404, 1, 2, 5),
                (1070, 1038, 628, 411, 1, 0, 8),
                (1080, 1141, 651, 490, 0, 1, 11),
                (1090, 1309, 686, 623, 0, 1, 13),
                (1100, 1310, 706, 604, 0, 1, 24),
                (1110, 1310, 706, 604, 0, 1, 24),
                (1120, 393, 271, 124, 2, 2, 1),
            ],
            actual,
        )

    def test_normalizes_the_runtime_resource_scenario_code(self):
        self.assertEqual(1010, normalize_scenario_code("scenario_1010"))
        with self.assertRaisesRegex(ValueError, "invalid scenario resource code"):
            normalize_scenario_code("1010")

    def test_joins_integer_province_indexes_to_stable_record_ids(self):
        tiles = {"provinceRecords": [{"id": "P-A"}, {"id": "P-B"}, {"id": "P-C"}]}
        map_resource = {"cities": [
            {"id": 10, "provinceId": 0},
            {"id": 11, "provinceId": 2},
            {"id": 12},
        ]}
        scenario = {"nation": [
            ["세력A", "#000", 0, 0, "", 0, "", 0, [10, 12]],
            ["세력B", "#fff", 0, 0, "", 0, "", 0, [11]],
        ]}
        ownership = {"assignments": [
            {"provinceId": "P-A", "ownerNationKey": "A"},
            {"provinceId": "P-B", "ownerNationKey": "A"},
            {"provinceId": "P-C", "ownerNationKey": "A"},
        ]}

        audit = audit_runtime_fill(
            scenario_code=1010,
            tiles=tiles,
            map_resource=map_resource,
            scenario=scenario,
            ownership=ownership,
            nation_name_by_key={"A": "세력A"},
        )

        self.assertEqual(("P-B",), audit.missing_owned_province_record_ids)
        self.assertEqual((), audit.extra_runtime_province_record_ids)
        self.assertEqual({"P-C": ("세력B", "세력A")}, audit.owner_mismatches)
        self.assertEqual((12,), audit.owned_city_ids_without_province_index)
        self.assertEqual(3, audit.canonical_owned_count)
        self.assertEqual(2, audit.runtime_colored_count)

    def test_rejects_an_out_of_range_city_province_index(self):
        with self.assertRaisesRegex(ValueError, "city 10 references unknown province index 3"):
            audit_runtime_fill(
                scenario_code=1010,
                tiles={"provinceRecords": [{"id": "P-A"}]},
                map_resource={"cities": [{"id": 10, "provinceId": 3}]},
                scenario={"nation": [["세력A", "", 0, 0, "", 0, "", 0, [10]]]},
                ownership={"assignments": [{"provinceId": "P-A", "ownerNationKey": "A"}]},
                nation_name_by_key={"A": "세력A"},
            )


if __name__ == "__main__":
    unittest.main()
