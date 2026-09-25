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
        # 2026-09-18 실측(GH #806 지리 재분할 뒤). 城 씨앗이 실제 위치로 돌아와 최소제곱이 투영식 그 자체
        # (cell/k = 0.04690971/0.866025 = 0.05417°/col, cell = 0.04691°/row)로 수렴한다. 2026-09-17 의 0.0534/0.0461 은
        # 밀린 씨앗(p90 12.8칸)이 끌어내린 값이었다. ADR-LITE-053 의 lon 80.5–116.6 범위를 쓰면 0.047 이 되어 빨개진다.
        scale = self.tiles['_meta'].get('resolutionScale', 1)
        self.assertAlmostEqual(self.graph.dlon * scale, 0.0542, places=3)
        self.assertAlmostEqual(self.graph.dlat * scale, -0.0469, places=3)

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

    def test_current_puyang_is_present_and_absent_name_is_rejected(self):
        self.assertIsInstance(M.county(self.tiles, "濮陽縣")["province"], int)
        with self.assertRaisesRegex(ValueError, "matched 0"):
            M.county(self.tiles, "不存在县")

    def test_homonym_county_name_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "matched 2"):
            M.county(self.tiles, "安阳县")  # 같은 nameCh 관할이 둘이다 — 첫 번째를 조용히 고르면 안 된다

    def test_research_note_tables_match_tool_output(self):
        # 타일이 바뀌면(#804 전례) 노트가 조용히 낡는다. 비교 표 24행과 승인 기준선 8행을 도구 출력과 맞춘다.
        note = (M.ROOT / "docs/superpowers/research/2026-09-17-march-tempo-baseline.md").read_text(encoding="utf-8")
        lines = [l for l in note.splitlines() if l.startswith("| ") and "→" in l]
        full = [l for l in lines if l.count("|") == 10]
        expected = [f"| {r['from']}→{r['to']} | {r['roughFactor']} | {r['straightKm']} | {r['km']} | {r['provinces']} | "
                    + " | ".join(str(r["turns"][str(s)]) for s in M.SPEEDS_KM_PER_TURN) + " |" for r in self.rows]
        self.assertEqual(full, expected)
        approved = [l for l in lines if l.count("|") == 7]
        want = [f"| {r['from']}→{r['to']} | {r['straightKm']} | {r['km']} | {r['provinces']} | {r['turns']['30']} | {r['turns']['45']} |"
                for r in self.rows if r["roughFactor"] == 1.5]
        self.assertEqual(approved, want)

if __name__ == "__main__":
    unittest.main()
