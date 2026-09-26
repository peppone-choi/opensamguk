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

import numpy as np
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import build_map_design as B  # noqa: E402

TOOL = B.ROOT / "tools/map/build_map_design.py"


class MapDesignInvariantsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.inp0 = B.load_inputs()
        cls.docs = {k: json.loads((B.OUT / k).read_text())
                    for k in (B.RIVERS, B.PLACEMENTS, B.ROADS_OUT, B.WATERS_OUT, B.MOUNTAINS, B.RELIEF, B.LANDCOVER, B.DODGE)}
        cls.tier, cls.width, cls.name = B.load_rivers_grid(cls.inp0, cls.docs[B.RIVERS])
        cls.pl = cls.docs[B.PLACEMENTS]["placements"]
        dem = B.load_dem()
        cls.roads, cls.rstat = B.compute_roads(cls.inp0, cls.tier, cls.width, cls.pl, dem)
        cls.inp, cls.wstat = B.with_waters(cls.inp0, cls.tier, cls.width, cls.pl, cls.roads)   # 아래 단계는 설계 길·다듬은 해안을 따른다
        cls.lv = B.compute_relief(cls.inp, cls.tier, cls.width, cls.pl, cls.docs[B.MOUNTAINS], dem)
        cls.des, cls.plat = B.compute_desert(cls.inp, cls.lv, B.compute_plateau(cls.inp, cls.lv, dem), dem, cls.tier, cls.width)

    # ── 초록 ──
    def test_committed_layer_is_clean(self):
        self.assertEqual(B.check_river_lines(self.docs[B.RIVERS]), [])
        self.assertEqual(B.check_placements(self.inp0, self.tier, self.width, self.pl), [])
        self.assertEqual(B.check_mountains(self.inp, self.tier, self.width, self.pl, self.docs[B.MOUNTAINS]), [])
        self.assertEqual(B.check_relief(self.inp, self.tier, self.width, self.pl, self.lv), [])
        self.assertEqual(B.relief_summary(self.lv, self.inp["terrain0"]), self.docs[B.RELIEF]["result"])
        self.assertEqual(B.check_plateau(self.inp, self.plat), [])
        self.assertEqual(B.check_plateau(self.inp, self.des, "사막"), [])
        self.assertEqual(B.check_plateau_desert(self.plat, self.des), [])
        self.assertEqual(B.plateau_summary(self.plat, self.inp["terrain0"]), self.docs[B.RELIEF]["plateau"]["result"])
        self.assertEqual(B.plateau_summary(self.des, self.inp["terrain0"], B.TERRAIN_DESERT), self.docs[B.RELIEF]["desert"]["result"])

    def test_desert_boundary_is_less_straight_and_stays_off_river_banks(self):
        def straight(m):
            tot = long = 0
            for a in ((m[1:, :] != m[:-1, :]), (m[:, 1:] != m[:, :-1]).T):
                d = np.diff(np.pad(a.astype(np.int8), ((0, 0), (1, 1))), axis=1)
                L = np.nonzero(d == -1)[1] - np.nonzero(d == 1)[1]; tot += L.sum(); long += L[L >= 20].sum()
            return long / max(1, tot)
        self.assertLess(straight(self.des), straight(self.inp["terrain0"] == B.TERRAIN_DESERT) / 2)
        rb = B.river_band(self.tier, self.width, B.DESERT_PARAMS["riverMargin"])
        self.assertEqual(int((self.des & rb).sum()), 0)

    def test_plateau_escarpment_is_high_mountain(self):
        # 四姑娘山 31.10667°N 102.90167°E(https://en.wikipedia.org/wiki/Mount_Siguniang): 지형 분류는 고원, 蜀 서쪽 산벽이라 높은 산
        T, P = self.inp["terrain0"], self.inp["proj"]
        r, c = (int(round(v)) for v in B.proj_rc(102.90167, 31.10667, P))
        w = (slice(r - 4, r + 5), slice(c - 4, c + 5))
        self.assertTrue((T[w] == B.TERRAIN_PLATEAU).all())
        self.assertTrue((self.lv[w] == 3).all())

    def test_plateau_boundary_is_less_straight_than_the_polygon(self):
        def straight(m):
            tot = long = 0
            for a in ((m[1:, :] != m[:-1, :]), (m[:, 1:] != m[:, :-1]).T):
                d = np.diff(np.pad(a.astype(np.int8), ((0, 0), (1, 1))), axis=1)
                L = np.nonzero(d == -1)[1] - np.nonzero(d == 1)[1]; tot += L.sum(); long += L[L >= 20].sum()
            return long / max(1, tot)
        self.assertLess(straight(self.plat), straight(self.inp["terrain0"] == B.TERRAIN_PLATEAU))

    def test_relief_fixes_known_misclassified_places(self):
        # 米倉山 32.631°N 106.823°E(https://peakvisor.com/peak/micang-mountains.html): 지형 분류는 평지, 표고로 산
        # 寶雞(陳倉) 34.363°N 107.238°E(https://en.wikipedia.org/wiki/Baoji): 渭水 골짜기 바닥인데 지형 분류는 산
        T, P = self.inp["terrain0"], self.inp["proj"]

        def window(lat, lon, dr, dc):
            r, c = (int(round(v)) for v in B.proj_rc(lon, lat, P))
            return slice(r - dr, r + dr + 1), slice(c - dc, c + dc + 1)
        micang, baoji = window(32.631, 106.823, 6, 8), window(34.363, 107.238, 2, 6)
        self.assertEqual((T[micang] == B.TERRAIN_MOUNTAIN).mean(), 0)
        self.assertGreater((self.lv[micang] > 0).mean(), 0.9)
        self.assertEqual((T[baoji] == B.TERRAIN_MOUNTAIN).mean(), 1)
        self.assertLess((self.lv[baoji] > 0).mean(), 0.1)

    def test_component_sizes_counts_4_connected_cells(self):
        import numpy as np
        m = np.array([[1, 1, 0, 1], [0, 1, 0, 1], [1, 0, 0, 1], [1, 1, 0, 0]], bool)
        self.assertEqual(B.component_sizes(m).tolist(), [[3, 3, 0, 3], [0, 3, 0, 3], [3, 0, 0, 3], [3, 3, 0, 0]])
        self.assertEqual(B._drop_small(m, 4).sum(), 0)

    def test_roads_follow_rules_and_are_less_straight(self):
        self.assertEqual(B.check_roads(self.inp0, self.roads), [])
        self.assertEqual(B.roads_summary(self.roads, self.rstat), self.docs[B.ROADS_OUT]["result"])

        def straight_share(trails, run):
            tot = long = 0
            for tr in trails:
                if len(tr) < 3:
                    continue
                d = np.diff(np.array(tr), axis=0); key = d[:, 0] * 3 + d[:, 1]
                L = np.diff(np.r_[0, np.nonzero(key[1:] != key[:-1])[0] + 1, len(key)]); tot += L.sum(); long += L[L >= run].sum()
            return long / max(1, tot)
        built = [e for e in json.loads(B.ROADS.read_text())["edges"] if e["status"] == "BUILT"]
        old = [e.get(k) or [] for e in built for k in ("fromTrail", "toTrail")]
        new = [self.roads[e["id"]][k] for e in built for k in ("fromTrail", "toTrail")]
        self.assertGreater(straight_share(old, 20), 0.4)            # 적색 기준: 원래 길은 걸린다(0.471)
        self.assertLess(straight_share(new, 20), 0.05)              # 설계 길(0.012)

    def test_red_road_off_province_gap_and_water(self):
        rid, r = next((k, v) for k, v in self.roads.items() if v["status"] == "BUILT" and len(v["fromTrail"]) > 10)
        own, T = self.inp0["owner"], self.inp0["terrain"]
        bad = copy.deepcopy(self.roads); tr = bad[rid]["fromTrail"]; y, x = tr[5]
        other = next((yy, xx) for yy in range(y - 30, y + 31) for xx in range(x - 30, x + 31) if own[yy, xx] not in (own[y, x], -1))
        tr[5] = [other[0], other[1]]
        errs = " | ".join(B.check_roads(self.inp0, bad))
        self.assertIn("8-연결 끊김", errs)
        bad = copy.deepcopy(self.roads); tr = bad[rid]["fromTrail"]; tr.insert(6, list(tr[5]))
        self.assertIn("두 번", " | ".join(B.check_roads(self.inp0, bad)))
        bad = copy.deepcopy(self.roads); bad[rid]["fromTrail"] = bad[rid]["fromTrail"][:-1]
        self.assertIn("끝 칸", " | ".join(B.check_roads(self.inp0, bad)))
        # 이웃 省으로 한 칸 비켜 가는 궤적(연결은 유지)
        sy, sx = np.nonzero(own[:-1, :] != own[1:, :]); k = next(i for i in range(len(sy)) if own[sy[i], sx[i]] >= 0 and own[sy[i] + 1, sx[i]] >= 0)
        y0, x0 = int(sy[k]), int(sx[k]); prov = own[y0, x0]
        fake = {"x": dict(status="BUILT", fromTrail=[[y0, x0], [y0 + 1, x0]], toTrail=[])}
        orig = [dict(id="x", fromProvinceId=self.inp0["ht"]["provinceRecords"][prov]["id"], toProvinceId="", fromTrail=[[y0, x0], [y0 + 1, x0]], toTrail=[])]
        saved = B.ROADS
        try:
            tmp = Path(tempfile.mkdtemp()) / "roads.json"; tmp.write_text(json.dumps(dict(edges=orig))); B.ROADS = tmp
            self.assertIn("제 省 밖", " | ".join(B.check_roads(self.inp0, fake)))
            wy, wx = map(int, np.argwhere(T == B.TERRAIN_SEA)[0])
            fake["x"]["fromTrail"] = [[wy, wx]]; orig[0]["fromTrail"] = [[wy, wx]]; tmp.write_text(json.dumps(dict(edges=orig)))
            self.assertIn("물", " | ".join(B.check_roads(self.inp0, fake)))
        finally:
            B.ROADS = saved

    def test_coast_is_smoothed_without_changing_topology(self):
        T0, T2 = self.inp["terrain0"], self.inp["terrain"]
        self.assertEqual(B.check_waters(self.inp0, T2, self.inp["owner"], self.inp["roadAll"], self.tier), [])
        self.assertEqual(self.wstat, self.docs[B.WATERS_OUT]["result"])
        fx = T0 == B.TERRAIN_OUT

        def stair(w):
            v = (w[1:, :] != w[:-1, :]) & ~fx[1:, :] & ~fx[:-1, :]; h = (w[:, 1:] != w[:, :-1]) & ~fx[:, 1:] & ~fx[:, :-1]
            rv = (np.arange(1, w.shape[0]) % 4 == 0)[:, None]; rh = (np.arange(1, w.shape[1]) % 4 == 0)[None, :]
            return ((v & rv).sum() + (h & rh).sum()) / max(1, v.sum() + h.sum())
        self.assertGreater(stair(np.isin(T0, (B.TERRAIN_SEA, B.TERRAIN_LAKE))), 0.95)      # 적색 기준: 옛 해안은 걸린다
        self.assertLess(stair(np.isin(T2, (B.TERRAIN_SEA, B.TERRAIN_LAKE))), 0.6)          # 설계 해안(0.509)

    def test_red_coast_drowns_road_or_changes_neighbours(self):
        T2 = self.inp["terrain"].copy(); o2 = self.inp["owner"].copy()
        ys, xs = np.nonzero(self.inp["roadAll"] & ~np.isin(self.inp["terrain0"], (B.TERRAIN_SEA, B.TERRAIN_LAKE)))
        T2[ys[0], xs[0]] = B.TERRAIN_SEA; o2[ys[0], xs[0]] = -1
        self.assertIn("길·강 칸", " | ".join(B.check_waters(self.inp0, T2, o2, self.inp["roadAll"], self.tier)))
        o2 = self.inp["owner"].copy()
        # 두 省이 맞닿은 줄 하나를 통째로 바다로 → 이웃 쌍이 사라지거나 덩어리가 바뀐다
        T3 = self.inp["terrain"].copy(); T3[:, 1500] = B.TERRAIN_SEA; o3 = o2.copy(); o3[:, 1500] = -1
        errs = " | ".join(B.check_waters(self.inp0, T3, o3, np.zeros_like(self.inp["roadAll"]), self.tier))
        self.assertIn("이웃 쌍", errs); self.assertIn("덩어리", errs)

    def test_every_river_has_a_source_and_every_dodge_city_exists(self):
        self.assertTrue(all(r[2] for r in self.docs[B.RIVERS]["rivers"]))
        ids = {c["id"] for c in self.inp["cities"]}
        self.assertTrue(all(x["cityId"] in ids for x in self.docs[B.DODGE]["cities"]))

    def test_no_city_left_for_review_and_ferry_exceptions_are_marked(self):
        self.assertEqual([p["name"] for p in self.pl if p["status"] != "PROPOSED"], [])
        own = self.inp["owner0"]
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
        self.assertIn(f"{victim['name']}: 물에 잠김", B.check_placements(self.inp0, self.tier, self.width, pl))

    def test_red_placement_too_far_or_out_of_province(self):
        own = self.inp["owner0"]; p = copy.deepcopy(next(p for p in self.pl if p["reason"] == "RIVER_BANK"))
        r0, c0 = p["frm"]
        far = next((r0 + d, c0) for d in range(13, 60) if own[r0 + d, c0] == own[r0, c0])
        p["to"] = list(far)
        self.assertTrue(any("칸 > 12" in e for e in B.check_placements(self.inp0, self.tier, self.width, [p])))
        other = next((r0 + dy, c0 + dx) for dy in range(-12, 13) for dx in range(-12, 13) if own[r0 + dy, c0 + dx] not in (own[r0, c0], -1))
        p["to"] = list(other)
        self.assertTrue(any("제 省 밖" in e for e in B.check_placements(self.inp0, self.tier, self.width, [p])))

    def test_red_mountain_on_protected_cell(self):
        mnt = copy.deepcopy(self.docs[B.MOUNTAINS]); ys, xs = self.inp["road"].nonzero()
        mnt["cells"].append([int(ys[0]), int(xs[0]), "NAMED_RANGE"])
        self.assertTrue(any("보호 칸" in e for e in B.check_mountains(self.inp, self.tier, self.width, self.pl, mnt)))
        mnt = copy.deepcopy(self.docs[B.MOUNTAINS])
        next(l for l in mnt["log"] if l["reason"] == "NAMED_RANGE").pop("source")
        self.assertTrue(any("출처 없음" in e for e in B.check_mountains(self.inp, self.tier, self.width, self.pl, mnt)))


    def test_boundaries_do_not_inherit_4x4_block_stairs(self):
        # 옛 지형 분류는 768 격자 ×4 덩이라 경계가 4칸 격자선 위에만 놓인다(지수 1.0). 자연스러운 경계는 약 0.25.
        land = ~np.isin(self.inp["terrain"], (B.TERRAIN_SEA, B.TERRAIN_LAKE, B.TERRAIN_OUT))

        def stair(m):
            v = (m[1:, :] != m[:-1, :]) & land[1:, :] & land[:-1, :]; h = (m[:, 1:] != m[:, :-1]) & land[:, 1:] & land[:, :-1]
            rv = (np.arange(1, m.shape[0]) % 4 == 0)[:, None]; rh = (np.arange(1, m.shape[1]) % 4 == 0)[None, :]
            return ((v & rv).sum() + (h & rh).sum()) / max(1, v.sum() + h.sum())
        self.assertGreater(stair(self.inp["terrain0"] == B.TERRAIN_DESERT), 0.95)         # 적색 기준: 옛 분류는 걸린다
        # 기준은 다듬기를 끈 값(사막 0.334 · 고원 0.476 · 산 0.296)과 다듬은 값(0.233 · 0.289 · 0.263) 사이
        for name, m, lim in (("산", self.lv > 0, 0.28), ("2단", self.lv >= 2, 0.28), ("3단", self.lv >= 3, 0.28),
                             ("사막", self.des, 0.30), ("고원", self.plat, 0.32)):
            self.assertLess(stair(m), lim, name)

    def test_red_plateau_and_desert_overlap(self):
        des = self.des.copy(); ys, xs = self.plat.nonzero(); des[ys[0], xs[0]] = True
        self.assertTrue(any("둘 다" in e for e in B.check_plateau_desert(self.plat, des)))

    def test_red_plateau_on_sea(self):
        plat = self.plat.copy(); ys, xs = (self.inp["terrain"] == B.TERRAIN_SEA).nonzero(); plat[ys[0], xs[0]] = True
        self.assertTrue(any("고원" in e for e in B.check_plateau(self.inp, plat)))

    def test_red_relief_on_road_and_loose_upper_tier(self):
        lv = self.lv.copy(); ys, xs = self.inp["road"].nonzero(); lv[ys[0], xs[0]] = 1
        self.assertTrue(any("보호 칸" in e for e in B.check_relief(self.inp, self.tier, self.width, self.pl, lv)))
        lv = self.lv.copy(); edge = (self.lv == 1) & ~B._erode4(self.lv >= 1); y, x = map(int, next(zip(*edge.nonzero())))
        lv[y, x] = 2
        self.assertTrue(any("2단 칸" in e for e in B.check_relief(self.inp, self.tier, self.width, self.pl, lv)))


class MapDesignCliProbeTest(unittest.TestCase):
    """사본 디렉터리에서 CLI --check 가 초록·적색을 실제로 가르는지."""

    def _run(self, d: Path):
        return subprocess.run([sys.executable, str(TOOL), "--check", "--dir", str(d)], capture_output=True, text=True)

    def test_red_after_stale_edits(self):
        with tempfile.TemporaryDirectory() as tmp:
            d = Path(tmp) / "map-design"; shutil.copytree(B.OUT, d)
            # 커밋본의 CLI 초록은 결합 목록 일괄 검사(check_han_tiles_coupled.py --check)가 CI 에서 돈다. 여기서는 적색만 본다.
            # 판정 입력을 고치고 강을 다시 새기지 않음 · 산 칸 하나 빠짐 · 피복 매개변수만 바꿈 · 이동안 좌표 조작
            dodge = json.loads((d / B.DODGE).read_text()); dodge["cities"].pop(); (d / B.DODGE).write_text(json.dumps(dodge))
            mnt = json.loads((d / B.MOUNTAINS).read_text()); mnt["cells"].pop(); (d / B.MOUNTAINS).write_text(json.dumps(mnt))
            lc = json.loads((d / B.LANDCOVER).read_text()); lc["params"]["villageMax"] = 7; (d / B.LANDCOVER).write_text(json.dumps(lc))
            pl = json.loads((d / B.PLACEMENTS).read_text()); pl["placements"][0]["to"][1] += 1; (d / B.PLACEMENTS).write_text(json.dumps(pl))
            rf = json.loads((d / B.RELIEF).read_text()); rf["params"]["tier3"] = 900; rf["input"]["demSha256"] = "0" * 64
            rf["plateau"]["params"]["step"] = 200; rf["desert"]["result"]["cells"] += 1
            rd = json.loads((d / B.ROADS_OUT).read_text()); rd["params"]["bendWeight"] = 2.0; (d / B.ROADS_OUT).write_text(json.dumps(rd))
            wd = json.loads((d / B.WATERS_OUT).read_text()); wd["result"]["waterToLand"] += 1; (d / B.WATERS_OUT).write_text(json.dumps(wd))
            (d / B.RELIEF).write_text(json.dumps(rf))
            bad = self._run(d)
            self.assertEqual(bad.returncode, 1, bad.stdout)
            for needle in ("dodgeSha256", f"{B.MOUNTAINS} 가 재계산과 다르다", f"{B.LANDCOVER} 지문", f"{B.PLACEMENTS} 가 재계산과 다르다",
                           f"{B.RELIEF} 지문", "표고 원판", f"{B.RELIEF} 고원 지문", f"{B.RELIEF} 사막 지문", f"{B.ROADS_OUT} 지문", f"{B.WATERS_OUT} 지문"):
                self.assertIn(needle, bad.stdout)


if __name__ == "__main__":
    unittest.main()
