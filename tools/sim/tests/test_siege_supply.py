import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import siege_supply as S  # noqa: E402

NOTE = S.ROOT / "docs/superpowers/research/2026-09-17-siege-supply-baseline.md"


class SiegeSupplyTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tiles = S.load_json(S.M.TILES)
        cls.tempo = S.load_json(S.TEMPO)
        cls.economy = S.load_json(S.ECONOMY)
        cls.result = S.build(cls.tiles, cls.tempo, cls.economy)

    def test_deterministic(self):
        again = S.build(self.tiles, self.tempo, self.economy)
        self.assertEqual(self.result, again)
        self.assertEqual(S.render(self.result), S.render(again))

    def test_everything_is_exploratory(self):
        self.assertEqual(self.result["status"], "EXPLORATORY")

    def test_march_turns_follow_the_approved_tempo_ledger(self):
        # 막는 것: 도구가 템포 원장을 안 읽거나 원장 값이 30/1.5 에서 바뀐 경우. 기대값은 같은 march_tempo 함수로 다시
        # 계산하므로 **독립 검사가 아니다** — 거리 계산(Graph.km) 자체가 틀리면 같이 틀려 통과한다(교차 비평 확인).
        # han-tiles 가 바뀌어도(#804 로 30→29순) 기대값이 같이 움직인다.
        import math
        g = S.M.Graph(self.tiles)
        speed, rough = self.tempo["baseSpeedKmPerTurn"], self.tempo["roughTerrainFactor"]
        self.assertEqual((speed, rough), (30, 1.5))  # 사용자 승인값(2026-09-17). 원장이 바뀌면 이 PR 의 노트도 다시 뽑아야 한다
        turns = {(e["from"], e["to"]): e["turns"] for e in self.result["expedition"]}
        self.assertEqual(set(turns), set(S.M.ROUTES))
        for a, b in S.M.ROUTES:
            cost, _, _ = g.shortest(S.M.county(self.tiles, a)["province"], S.M.county(self.tiles, b)["province"], rough)
            self.assertEqual(turns[(a, b)], math.ceil(cost / speed), (a, b))

    def test_core_median_edge_is_measured_not_hardcoded(self):
        n, median = S.core_median_edge_km(S.M.Graph(self.tiles))
        self.assertGreater(n, 100)
        a = self.result["anchors"][0]
        self.assertEqual(a["edges"], round(1000 * S.HAN_LI_KM / median))
        self.assertEqual(a["coreMedianKm"], round(median, 1))

    def test_unapproved_tempo_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "not owner-approved"):
            S.build(self.tiles, {**self.tempo, "status": "EXPLORATORY"}, self.economy)

    def test_missing_input_fails_instead_of_defaulting(self):
        with tempfile.TemporaryDirectory() as d:
            with self.assertRaises(FileNotFoundError):
                S.load_json(Path(d) / "county-economy-inputs-v1.json")

    def test_county_absent_from_economy_ledger_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "matched 0"):
            S.commandery_households(self.economy, "濮阳县")

    def test_commandery_without_source_households_is_unknown_not_zero(self):
        # 襄陽郡은 永和五年(140) 뒤 신설이라 郡國志 戶數가 없다. 0 으로 읽으면 0 나눗셈이거나 「부담 없음」이 된다.
        self.assertIsNone(S.commandery_households(self.economy, "襄阳县")["households"])
        rows = [e for e in self.result["expedition"] if e["from"] == "襄阳县"]
        self.assertTrue(rows and all(c["perHundredHouseholds"] is None for e in rows for c in e["byTroops"].values()))
        self.assertIn("襄陽郡 | UNKNOWN", S.render(self.result))

    def test_grain_is_monotonic_in_troops_days_and_ration(self):
        self.assertLess(S.grain_hu(10_000, 5, 5, 2.66), S.grain_hu(30_000, 5, 5, 2.66))
        self.assertLess(S.grain_hu(10_000, 5, 5, 2.66), S.grain_hu(10_000, 6, 5, 2.66))
        self.assertLess(S.grain_hu(10_000, 5, 5, 2.66), S.grain_hu(10_000, 5, 10, 2.66))
        self.assertLess(S.grain_hu(10_000, 5, 5, 1.5), S.grain_hu(10_000, 5, 5, 2.66))

    def test_li_gu_arithmetic_reproduces_the_source(self):
        # 後漢書 卷86: 4만 명 · 300일 · 5升/日 → 「用米六十萬斛」. 1순=10일 × 30순 = 300일.
        self.assertEqual(S.grain_hu(40_000, 30, 10, 1.5), 600_000)

    def test_zhao_chongguo_ration_is_the_quoted_division(self):
        self.assertAlmostEqual(dict((k, v) for _, v, k in S.RATIONS)["zhaoChongguo"], 2.6615, places=3)

    def test_arrival_never_rises_with_length_or_loss(self):
        for p in S.LOSS_PER_EDGE:
            vals = [S.arrival(p, e) for e in range(0, 20)]
            self.assertEqual(vals, sorted(vals, reverse=True))
        for e in (1, 5, 17):
            vals = [S.arrival(p, e) for p in S.LOSS_PER_EDGE]
            self.assertEqual(vals, sorted(vals, reverse=True))
        self.assertEqual(S.arrival(0.1, 0), 1.0)
        with self.assertRaises(ValueError):
            S.arrival(1.0, 3)

    def test_wooden_ox_arrival_falls_with_distance_and_floors_at_zero(self):
        self.assertGreater(S.wooden_ox_arrival(100), S.wooden_ox_arrival(700))
        self.assertEqual(S.wooden_ox_arrival(10_000), 0.0)

    def test_implied_loss_round_trips(self):
        p = S.implied_loss(64, 13)
        self.assertAlmostEqual(S.arrival(p, 13), 1 / 64, places=9)

    def test_siege_turns_shrink_as_a_turn_gets_longer(self):
        for s in self.result["sieges"]:
            ts = [s["turns"][str(d)] for d in S.DAYS_PER_TURN]
            if s["approxDays"] is None:
                self.assertEqual(ts, [None] * len(ts))  # 「連月」은 수로 바꾸지 않는다
            else:
                self.assertEqual(ts, sorted(ts, reverse=True))

    def test_siege_durations_are_pinned_to_the_hand_collated_values(self):
        # 고정점. 값이 사료와 맞는지는 증명하지 못한다(사람이 三國志 원문과 대조했다) — 누가 SIEGES 를 조용히 바꾸면
        # 노트를 재생성해도 통과하던 구멍만 막는다. (개월, 일, 하한) — 셋 다 None/False 면 사료가 수를 주지 않는 것.
        pinned = {
            "雍丘 195": (4, None, False), "下邳 198": (3, None, False), "官渡 200": (2, None, False),
            "鄴 204(공격 개시부터)": (6, None, False), "鄴 204(圍壍부터)": (3, None, False),
            "合肥 208(劉馥傳)": (None, 100, True), "合肥 208(吳主傳 — 같은 포위, 기록 상충)": (1, None, True),
            "江陵 208–209": (12, None, True), "樊(襄陽) 219": (2, None, True), "陳倉 228–229": (None, 20, True),
            "襄平 238": (2, None, False), "合肥新城 253": (None, None, False), "壽春 257–258": (8, None, False),
            "東武陽(臧洪) 195/196–": (None, None, False),
        }
        self.assertEqual({s["name"]: (s["months"], s["days"], s["lowerBound"]) for s in S.SIEGES}, pinned)
        for s in S.SIEGES:
            if s["months"] is None and s["days"] is None:
                self.assertTrue(s.get("unknownSpan"), s["name"])

    def test_every_siege_and_ration_cites_a_source(self):
        for s in S.SIEGES:
            self.assertTrue(s["book"] and s["quote"] and s["url"].startswith("https://"), s["name"])
            self.assertFalse(s["months"] is not None and s["days"] is not None, s["name"])
        for _, _, key in S.RATIONS:
            self.assertIn(key, S.SOURCES)

    def test_research_note_tables_match_tool_output_byte_for_byte(self):
        note = NOTE.read_text(encoding="utf-8")
        self.assertIn(S.render(self.result), note)


if __name__ == "__main__":
    unittest.main()
