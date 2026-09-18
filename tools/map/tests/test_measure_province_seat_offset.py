"""measure_province_seat_offset: 합성 격자에서 밀림을 정확히 재는지 본다 (GH #806)."""
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from measure_province_seat_offset import TILES, gate, main, measure, project_cell  # noqa: E402

PROJECTION = {"cell": 1.0, "k": 1.0, "x0": 0.0, "pad": 0.0, "y1": 4.0}


def document(owner_rows):
    rows, cols = len(owner_rows), len(owner_rows[0])
    runs = [[value, 1] for line in owner_rows for value in line]
    return {
        "_meta": {"rows": rows, "cols": cols, "projection": PROJECTION,
                  "terrainLegend": {"1": "PLAIN", "2": "MOUNTAIN"}},
        "owner": runs,
        "parentOwner": [[0, rows * cols]],
        "terrain": ["1122"] * rows,
        "cities": [
            # 실제 위치는 (col 0,row 0) — lon 0.5, lat 3.5
            {"kind": "COUNTY", "seat": True, "col": 3, "row": 0, "lon": 0.5, "lat": 3.5},
            {"kind": "COUNTY", "seat": False, "col": 1, "row": 3, "lon": 1.5, "lat": 0.5},
        ],
        "provinceRecords": [
            {"id": "A", "nameCh": "甲", "parentRegionId": "PARENT-0000", "cityIndex": 0},
            {"id": "B", "nameCh": "乙", "parentRegionId": "PARENT-0000", "cityIndex": 1},
        ],
        "jurisdictionRecords": [
            {"id": "A", "provinceIds": ["A"]}, {"id": "B", "provinceIds": ["B"]},
        ],
    }


class MeasureTest(unittest.TestCase):
    def test_projection_matches_frontier_formula(self):
        self.assertEqual(project_cell(PROJECTION, 3.5, 0.5), (0.5, 0.5))

    def test_displaced_province_is_measured_and_in_place_one_is_zero(self):
        # 甲(0)의 땅은 오른쪽 두 열뿐이다 — 실제 칸 (0,0) 은 乙(1) 땅.
        rows = measure(document([[1, 1, 0, 0]] * 4))
        jia, yi = rows
        self.assertFalse(jia["trueCellInProvince"])
        self.assertEqual(jia["nearestCell"], 2.0)
        self.assertEqual(jia["trueCellOwner"], "乙")
        self.assertEqual(jia["terrainAtTrue"], "PLAIN")
        self.assertEqual(jia["terrainAtSeed"], "MOUNTAIN")
        self.assertEqual(jia["lowlandCells"], 0)
        self.assertTrue(yi["trueCellInProvince"])
        self.assertEqual(yi["nearestCell"], 0.0)

    def test_red_probe_moving_the_province_onto_the_seat_clears_it(self):
        rows = measure(document([[0, 0, 1, 1]] * 4))
        self.assertTrue(rows[0]["trueCellInProvince"])
        self.assertFalse(rows[1]["trueCellInProvince"])

    def test_gate_is_red_on_a_displaced_province_and_green_when_it_sits_on_its_seat(self):
        red = gate(measure(document([[1, 1, 0, 0]] * 4)))
        self.assertEqual([r["id"] for r in red["Q1"]], ["A"])
        self.assertEqual([r["id"] for r in red["Q1b"]], ["A"])  # 실제 칸은 PLAIN 인데 제 省엔 저지 0칸
        # 乙의 실제 칸 (col 1,row 3) 까지 제 땅이어야 초록이다.
        green = gate(measure(document([[0, 0, 1, 1], [0, 0, 1, 1], [0, 0, 1, 1], [0, 1, 1, 1]])))
        self.assertEqual(green, {"Q1": [], "Q1b": []})

    def test_gate_exception_needs_a_ledger_id_and_never_excuses_q1b(self):
        rows = measure(document([[1, 1, 0, 0]] * 4))
        excused = gate(rows, frozenset({"A"}))
        self.assertEqual(excused["Q1"], [])
        self.assertEqual([r["id"] for r in excused["Q1b"]], ["A"])
        self.assertEqual([r["id"] for r in gate(rows, frozenset({"B"}))["Q1"]], ["A"])

    def test_gate_counts_a_seat_province_without_any_cell(self):
        rows = measure(document([[1, 1, 1, 1]] * 4))  # 甲은 칸이 없다 — 걸러지면 게이트가 눈을 감는다
        self.assertEqual([r["id"] for r in gate(rows)["Q1"]], ["A"])

    def test_check_mode_is_red_on_the_committed_tiles_today(self):
        """--check 는 아직 CI 차단 단계가 아니다. 현행 커밋본이 빨간 것이 이 게이트가 살아 있다는 증거다."""
        import contextlib
        import io
        from unittest import mock
        with mock.patch.object(sys, "argv", ["measure", "--check", "--top", "0"]), \
                contextlib.redirect_stderr(io.StringIO()) as err, contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(main(), 1)
        self.assertIn("Q1: 480 / 1131", err.getvalue())
        self.assertIn("Q1b: 24 / 1131", err.getvalue())

    def test_committed_tiles_baseline(self):
        """현행 커밋본의 실측 기준선. 지리 재분할(#806)이 들어오면 의도적으로 고친다."""
        import json
        rows = [r for r in measure(json.loads(TILES.read_text())) if r.get("area")]
        self.assertEqual(len(rows), 1131)
        self.assertEqual(sum(r["trueCellInProvince"] for r in rows), 637)
        self.assertEqual(sum(r["trueCellInJurisdiction"] for r in rows), 651)
        self.assertEqual(sum(r["trueCellInParent"] for r in rows), 1106)


if __name__ == "__main__":
    unittest.main()
