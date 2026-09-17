import json
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import march_tempo as M  # noqa: E402


class MarchTempoTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tiles = json.loads(M.TILES.read_text(encoding="utf-8"))
        cls.graph = M.Graph(cls.tiles)
        cls.rows = M.table(cls.tiles)

    def test_fitted_scale_is_the_measured_one(self):
        # 2026-09-17 실측. ADR-LITE-053 의 lon 80.5–116.6 범위를 쓰면 dlon 이 0.047 이 되어 이 단언이 빨개진다.
        self.assertAlmostEqual(self.graph.dlon, 0.0534, places=3)
        self.assertAlmostEqual(self.graph.dlat, -0.0461, places=3)

    def test_every_route_is_reachable_and_deterministic(self):
        self.assertTrue(all(r["reachable"] for r in self.rows))
        self.assertEqual(self.rows, M.table(self.tiles))

    def test_rough_factor_never_makes_a_route_cheaper(self):
        by = {}
        for r in self.rows:
            by.setdefault((r["from"], r["to"]), []).append(r)
        for rs in by.values():
            costs = [r["costKm"] for r in sorted(rs, key=lambda r: r["roughFactor"])]
            self.assertEqual(costs, sorted(costs))

    def test_absent_county_name_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "matched 0"):
            M.county(self.tiles, "濮阳县")  # 1133 판 관할에 없다(東郡 治所는 燕縣으로 잡혀 있다)

    def test_homonym_county_name_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "matched 2"):
            M.county(self.tiles, "安阳县")  # 같은 nameCh 관할이 둘이다 — 첫 번째를 조용히 고르면 안 된다


if __name__ == "__main__":
    unittest.main()
