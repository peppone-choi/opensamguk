#!/usr/bin/env python3
"""지도 설계 층(data/curated/han/map-design) 불변식과 적색 프로브.

초록만으로는 게이트가 살아 있다는 증거가 못 된다. 각 불변식마다 커밋본을 일부러 망가뜨려
해당 오류가 이름으로 잡히는지 본다. 커밋본 전체의 결정적 재계산 대조는 결합 목록
(check_han_tiles_coupled.py --check)이 돌고, 여기서는 사본 디렉터리로 CLI 가 초록·적색을 가르는지만 본다.
"""
from __future__ import annotations

import copy
import json
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import build_map_design as B  # noqa: E402

TOOL = B.ROOT / "tools/map/build_map_design.py"


class MapDesignInvariantsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.inp = B.load_inputs()
        cls.docs = {k: json.loads((B.OUT / k).read_text()) for k in (B.RIVERS, B.PLACEMENTS, B.MOUNTAINS, B.LANDCOVER, B.DODGE)}
        cls.tier, cls.width, cls.name = B.load_rivers_grid(cls.inp, cls.docs[B.RIVERS])
        cls.pl = cls.docs[B.PLACEMENTS]["placements"]

    # ── 초록 ──
    def test_committed_layer_is_clean(self):
        self.assertEqual(B.check_river_lines(self.docs[B.RIVERS]), [])
        self.assertEqual(B.check_placements(self.inp, self.tier, self.width, self.pl), [])
        self.assertEqual(B.check_mountains(self.inp, self.tier, self.width, self.pl, self.docs[B.MOUNTAINS]), [])

    def test_every_river_has_a_source_and_every_dodge_city_exists(self):
        self.assertTrue(all(r[2] for r in self.docs[B.RIVERS]["rivers"]))
        ids = {c["id"] for c in self.inp["cities"]}
        self.assertTrue(all(x["cityId"] in ids for x in self.docs[B.DODGE]["cities"]))

    def test_no_city_left_for_review_and_ferry_exceptions_are_marked(self):
        self.assertEqual([p["name"] for p in self.pl if p["status"] != "PROPOSED"], [])
        own = self.inp["owner"]
        crossed = [p for p in self.pl if own[tuple(p["to"])] != own[tuple(p["frm"])]]
        self.assertTrue(all(p["reason"] == "FERRY_MOUTH" for p in crossed), [p["name"] for p in crossed])

    def test_clean_path_removes_backtracks_and_squares(self):
        # .5 경계에서 열이 오락가락하는 반올림을 흉내 낸다
        pts = [(r * 0.5, 10.5 + (0.01 if r % 2 else -0.01)) for r in range(40)]
        cells = B.raster_cells(pts)
        doc = dict(rivers=[["t", 3, "시험"]], lines=[dict(name="t", tier=3, cells=[list(c) for c in cells])])
        self.assertEqual(B.check_river_lines(doc), [])

    # ── 적색 프로브 ──
    def _line_errs(self, mutate):
        doc = copy.deepcopy(self.docs[B.RIVERS]); mutate(doc)
        return " | ".join(B.check_river_lines(doc))

    def test_red_line_gap(self):
        self.assertIn("4-연결 끊김", self._line_errs(lambda d: d["lines"][0]["cells"].pop(len(d["lines"][0]["cells"]) // 2)))

    def test_red_line_square(self):
        def square(d):
            d["lines"][0]["cells"] = [[0, 0], [0, 1], [1, 1], [1, 0], [2, 0]]
        self.assertIn("2×2 덩이", self._line_errs(square))

    def test_red_line_revisit(self):
        def revisit(d):
            d["lines"][0]["cells"] = [[0, 0], [0, 1], [0, 0], [1, 0]]
        self.assertIn("두 번", self._line_errs(revisit))

    def test_red_river_without_source(self):
        def strip(d):
            d["rivers"][0] = [d["rivers"][0][0], d["rivers"][0][1], ""]
        self.assertIn("출처 없음", self._line_errs(strip))

    def test_red_dropped_placement_submerges_its_city(self):
        victim = next(p for p in self.pl if p["reason"] == "RIVER_BANK")
        pl = [p for p in self.pl if p is not victim]
        self.assertIn(f"{victim['name']}: 물에 잠김", B.check_placements(self.inp, self.tier, self.width, pl))

    def test_red_placement_too_far_or_out_of_province(self):
        own = self.inp["owner"]; p = copy.deepcopy(next(p for p in self.pl if p["reason"] == "RIVER_BANK"))
        r0, c0 = p["frm"]
        far = next((r0 + d, c0) for d in range(13, 60) if own[r0 + d, c0] == own[r0, c0])
        p["to"] = list(far)
        self.assertTrue(any("칸 > 12" in e for e in B.check_placements(self.inp, self.tier, self.width, [p])))
        other = next((r0 + dy, c0 + dx) for dy in range(-12, 13) for dx in range(-12, 13) if own[r0 + dy, c0 + dx] not in (own[r0, c0], -1))
        p["to"] = list(other)
        self.assertTrue(any("제 省 밖" in e for e in B.check_placements(self.inp, self.tier, self.width, [p])))

    def test_red_mountain_on_protected_cell(self):
        mnt = copy.deepcopy(self.docs[B.MOUNTAINS]); ys, xs = self.inp["road"].nonzero()
        mnt["cells"].append([int(ys[0]), int(xs[0]), "NAMED_RANGE"])
        self.assertTrue(any("보호 칸" in e for e in B.check_mountains(self.inp, self.tier, self.width, self.pl, mnt)))
        mnt = copy.deepcopy(self.docs[B.MOUNTAINS])
        next(l for l in mnt["log"] if l["reason"] == "NAMED_RANGE").pop("source")
        self.assertTrue(any("출처 없음" in e for e in B.check_mountains(self.inp, self.tier, self.width, self.pl, mnt)))


class MapDesignCliProbeTest(unittest.TestCase):
    """사본 디렉터리에서 CLI --check 가 초록·적색을 실제로 가르는지."""

    def _run(self, d: Path):
        return subprocess.run([sys.executable, str(TOOL), "--check", "--dir", str(d)], capture_output=True, text=True)

    def test_green_copy_then_red_after_stale_edits(self):
        with tempfile.TemporaryDirectory() as tmp:
            d = Path(tmp) / "map-design"; shutil.copytree(B.OUT, d)
            ok = self._run(d)
            self.assertEqual(ok.returncode, 0, ok.stdout + ok.stderr)
            # 판정 입력을 고치고 강을 다시 새기지 않음 · 산 칸 하나 빠짐 · 피복 매개변수만 바꿈 · 이동안 좌표 조작
            dodge = json.loads((d / B.DODGE).read_text()); dodge["cities"].pop(); (d / B.DODGE).write_text(json.dumps(dodge))
            mnt = json.loads((d / B.MOUNTAINS).read_text()); mnt["cells"].pop(); (d / B.MOUNTAINS).write_text(json.dumps(mnt))
            lc = json.loads((d / B.LANDCOVER).read_text()); lc["params"]["villageMax"] = 7; (d / B.LANDCOVER).write_text(json.dumps(lc))
            pl = json.loads((d / B.PLACEMENTS).read_text()); pl["placements"][0]["to"][1] += 1; (d / B.PLACEMENTS).write_text(json.dumps(pl))
            bad = self._run(d)
            self.assertEqual(bad.returncode, 1, bad.stdout)
            for needle in ("dodgeSha256", f"{B.MOUNTAINS} 가 재계산과 다르다", f"{B.LANDCOVER} 지문", f"{B.PLACEMENTS} 가 재계산과 다르다"):
                self.assertIn(needle, bad.stdout)


if __name__ == "__main__":
    unittest.main()
