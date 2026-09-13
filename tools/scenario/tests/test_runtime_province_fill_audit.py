import json
import unittest
from pathlib import Path

from tools.scenario.runtime_province_fill_audit import (
    audit_repository,
    audit_runtime_fill,
    normalize_scenario_code,
)


class RuntimeProvinceFillAuditTest(unittest.TestCase):
    def test_repository_runtime_fill_debt_does_not_regress_with_city_expansion(self):
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

        # Historical debt ceilings permit missing counties to become real cities.
        # Exact current city/color counts must not freeze future map expansion.
        baseline = [
                (1010, 278, 236, 49, 7, 2, 0),
                (1020, 643, 448, 202, 7, 4, 0),
                (1021, 661, 460, 211, 10, 2, 0),
                (1030, 789, 538, 259, 8, 4, 0),
                (1031, 850, 578, 279, 7, 6, 0),
                (1040, 797, 546, 257, 6, 4, 0),
                (1041, 881, 602, 286, 7, 5, 0),
                (1050, 1003, 677, 330, 4, 3, 1),
                (1060, 1003, 677, 330, 4, 5, 1),
                (1070, 1038, 723, 319, 4, 2, 1),
                (1080, 1137, 780, 362, 5, 2, 1),
                (1090, 1305, 816, 492, 3, 3, 1),
                (1100, 1306, 825, 484, 3, 2, 1),
                (1110, 1306, 825, 484, 3, 2, 1),
                (1120, 394, 307, 99, 12, 3, 0),
            ]
        ceilings = {row[0]: row for row in baseline}
        ownership = json.loads((root / "data/map/han-scenario-province-ownership-v1.json").read_text())
        claims = json.loads((root / "data/curated/han/scenario-province-claims-v1.json").read_text())
        expected_codes = {int(row["scenarioCode"]) for row in ownership["scenarios"]}
        self.assertEqual(expected_codes, {int(row["scenarioCode"]) for row in claims["scenarios"]})
        self.assertEqual(expected_codes, {row[0] for row in actual})
        self.assertTrue(actual)
        self.assertEqual(len(actual), len({row[0] for row in actual}))
        for code, canonical, colored, missing, extra, mismatches, unbound in actual:
            with self.subTest(scenario=code):
                self.assertEqual(canonical + extra, colored + missing)
                if code in ceilings:
                    prior = ceilings[code]
                    self.assertLessEqual(missing, prior[3])
                    self.assertLessEqual(extra, prior[4])
                    self.assertLessEqual(mismatches, prior[5])
                    self.assertLessEqual(unbound, prior[6])
                else:
                    self.assertEqual((missing, extra, mismatches, unbound), (0, 0, 0, 0),
                                     "New scenario debt requires explicit adjudication")

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

    def test_rejects_a_string_instead_of_a_nation_city_array(self):
        with self.assertRaisesRegex(ValueError, "city array at index 8 must be an array"):
            audit_runtime_fill(
                scenario_code=1010,
                tiles={"provinceRecords": [{"id": "P-A"}]},
                map_resource={"cities": [{"id": 10, "provinceId": 0}]},
                scenario={"nation": [["세력A", "", 0, 0, "", 0, "", 0, "10"]]},
                ownership={"assignments": [{"provinceId": "P-A", "ownerNationKey": "A"}]},
                nation_name_by_key={"A": "세력A"},
            )

    def test_rejects_conflicting_canonical_owners_for_one_province(self):
        with self.assertRaisesRegex(ValueError, "conflicting canonical owners for province P-A"):
            audit_runtime_fill(
                scenario_code=1010,
                tiles={"provinceRecords": [{"id": "P-A"}]},
                map_resource={"cities": [{"id": 10, "provinceId": 0}]},
                scenario={"nation": [["세력A", "", 0, 0, "", 0, "", 0, [10]]]},
                ownership={"assignments": [
                    {"provinceId": "P-A", "ownerNationKey": "A"},
                    {"provinceId": "P-A", "ownerNationKey": "B"},
                ]},
                nation_name_by_key={"A": "세력A", "B": "세력B"},
            )


if __name__ == "__main__":
    unittest.main()
