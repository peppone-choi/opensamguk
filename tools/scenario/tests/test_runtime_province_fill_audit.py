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

        # 모든 active Han scenario는 new-world-only han-world-v3를 쓰므로 실행 채색 부채도
        # 각 scenario의 mapName이 가리키는 832-node resource로 계산한다.
        # legacy han.json의 774-node 배열로 해석하면 동일 numeric ID가 다른
        # physical place를 가리키는 대량의 가짜 mismatch가 생긴다.
        # 2026-09-11 실측 재기준선: 변경 縣 51곳이 실제 城으로 들어와 城 수가 781→832 가
        # 됐고, 縣이 서면서 그 자리의 直領 省 63개가 縣 省으로 대체됐다(1,524→1,520).
        # 마지막 열(省 인덱스 없는 소유 城)이 전 시나리오 0 이 된 것은 예전 마지막 열의
        # 미결 城들이 이제 縣 省을 갖게 됐기 때문이다.
        self.assertEqual(
            [
                (1010, 277, 236, 49, 8, 2, 0),
                (1020, 644, 449, 202, 7, 5, 0),
                (1021, 662, 461, 211, 10, 3, 0),
                (1030, 791, 539, 261, 9, 5, 0),
                (1031, 853, 579, 281, 7, 8, 0),
                (1040, 799, 547, 259, 7, 5, 0),
                (1041, 885, 603, 288, 6, 7, 0),
                (1050, 1006, 679, 331, 4, 6, 0),
                (1060, 1006, 679, 331, 4, 8, 0),
                (1070, 1039, 723, 320, 4, 5, 0),
                (1080, 1137, 780, 362, 5, 5, 0),
                (1090, 1305, 816, 492, 3, 6, 0),
                (1100, 1306, 825, 484, 3, 5, 0),
                (1110, 1306, 825, 484, 3, 5, 0),
                (1120, 394, 308, 99, 13, 4, 0),
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
