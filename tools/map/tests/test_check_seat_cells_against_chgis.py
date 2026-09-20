"""CHGIS 독립 축 검사(GH #806)의 판정 규칙 — 합성 격자. 원본 DBF 는 gitignored 라 여기서는 쓰지 않는다."""
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import check_seat_cells_against_chgis as axis  # noqa: E402

# 4×2 격자, 칸 = 1°. 왼쪽 두 열은 省 0(관할 A), 오른쪽 두 열은 省 1(관할 B).
PROJECTION = {"k": 1.0, "x0": 0.0, "pad": 0.0, "cell": 1.0, "y1": 2.0}


def document(a_lonlat=(0.5, 1.5), b_lonlat=(2.5, 1.5)):
    return {
        "_meta": {"rows": 2, "cols": 4, "projection": PROJECTION},
        "owner": [[0, 2], [1, 2], [0, 2], [1, 2]],
        "provinceRecords": [{"id": "A", "jurisdictionId": "A", "cityIndex": 0},
                            {"id": "B", "jurisdictionId": "B", "cityIndex": 1}],
        "cities": [{"id": "A", "nameCh": "甲", "lon": a_lonlat[0], "lat": a_lonlat[1]},
                   {"id": "B", "nameCh": "乙", "lon": b_lonlat[0], "lat": b_lonlat[1]}],
    }


class EvaluateTest(unittest.TestCase):
    def test_agreement_and_cities_without_a_chgis_id(self):
        result = axis.evaluate(document(), {"A": (0.5, 1.5)}, set(), "")
        self.assertEqual((result["compared"], result["agree"], result["notInChgis"], result["red"]), (1, 1, 1, []))

    def test_red_probe_chgis_point_in_another_jurisdiction_is_red(self):
        result = axis.evaluate(document(), {"A": (2.5, 1.5), "B": (2.5, 1.5)}, set(), "")
        self.assertEqual([row["cityId"] for row in result["red"]], ["A"])
        self.assertEqual(result["red"][0]["chgisCellOwner"], "B")

    def test_exception_ledger_row_excuses_only_its_own_jurisdiction(self):
        result = axis.evaluate(document(), {"A": (2.5, 1.5)}, {"A"}, "")
        self.assertEqual((len(result["exception"]), result["red"]), (1, []))
        self.assertEqual(len(axis.evaluate(document(), {"A": (2.5, 1.5)}, {"B"}, "")["red"]), 1)

    def test_coordinate_override_needs_a_ledger_that_names_the_city(self):
        """han-tiles 좌표가 CHGIS 와 다르면(판정 원장이 고침) 따로 세되, 고친 근거 원장에 그 id 가 있어야 한다."""
        moved = document(a_lonlat=(1.5, 0.5))
        self.assertEqual(len(axis.evaluate(moved, {"A": (2.5, 1.5)}, set(), '{"id": "A"}')["coordinateOverridden"]), 1)
        self.assertEqual(len(axis.evaluate(moved, {"A": (2.5, 1.5)}, set(), '{"id": "Z"}')["red"]), 1)

    def test_red_probe_shift_disables_exceptions_and_overrides(self):
        result = axis.evaluate(document(), {"A": (0.5, 1.5)}, {"A"}, '"A"', shift=2.0)
        self.assertEqual([row["cityId"] for row in result["red"]], ["A"])

    def test_point_in_water_or_off_grid_is_red_not_a_crash(self):
        result = axis.evaluate(document(), {"A": (99.0, 99.0)}, set(), "")
        self.assertEqual(result["red"][0]["chgisCellOwner"], None)


if __name__ == "__main__":
    unittest.main()
