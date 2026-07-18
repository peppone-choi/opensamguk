import unittest
import sys
import json
from pathlib import Path
from tempfile import TemporaryDirectory

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
import build_rtk14_hexmap as b


def geom(**over):
    g = dict(
        hex_size=b.DEFAULTS["hex_size"], origin_x=b.DEFAULTS["origin_x"],
        origin_y=b.DEFAULTS["origin_y"], orientation=b.DEFAULTS["orientation"],
        sample_radius=b.DEFAULTS["sample_radius"], color_tol=b.DEFAULTS["color_tol"],
        min_conf=b.DEFAULTS["min_conf"],
    )
    g.update(over)
    return g


def solid(color, h=40, w=40):
    im = np.empty((h, w, 3), dtype=np.uint8)
    im[:, :] = color
    return im


def classify_center(im, cx, cy, g=None):
    g = g or geom()
    idx, dist = b.nearest_label_map(im)
    return b.classify_cell(idx, dist, cx, cy, g)


class HexGeometryTest(unittest.TestCase):
    def test_offset_column_centers(self):
        cells = list(b.iter_cells(100, 100, geom()))
        d = {(c, r): (cx, cy) for c, r, cx, cy in cells}
        # even column: no vertical offset; centers x=9+19*col, y=19*row
        self.assertEqual((9.0, 0.0), d[(0, 0)])
        self.assertEqual((9.0, 19.0), d[(0, 1)])
        # odd column: shifted +hex_size/2 in y
        self.assertEqual((28.0, 9.5), d[(1, 0)])
        self.assertEqual((28.0, 28.5), d[(1, 1)])

    def test_centers_native_pixel_and_in_bounds(self):
        W, H = 200, 150
        for c, r, cx, cy in b.iter_cells(W, H, geom()):
            self.assertTrue(0 <= cx < W)
            self.assertTrue(0 <= cy < H)
            self.assertEqual(cx, 9.0 + 19.0 * c)

    def test_column_count_covers_width(self):
        cols = {c for c, r, cx, cy in b.iter_cells(4181, 4191, geom())}
        # x = 9 + 19*col < 4181  -> col 0..219
        self.assertEqual(min(cols), 0)
        self.assertEqual(max(cols), 219)


class PaletteClassifyTest(unittest.TestCase):
    def test_nearest_label_map_exact_colors(self):
        # wiki カラー hex, verbatim: 平地/大河/森
        colors = [(189, 183, 107), (33, 88, 103), (117, 146, 60)]
        im = np.array([colors], dtype=np.uint8)  # 1x3
        idx, dist = b.nearest_label_map(im)
        for j, col in enumerate(colors):
            self.assertEqual(tuple(b.PALETTE[idx[0, j]][0]), col)
            self.assertEqual(dist[0, j], 0)

    def test_wiki_hex_terrains(self):
        # each wiki カラー swatch color classifies to its own terrain code+kind at conf 1.0
        for jp, code, kind, hx, _eff in b.WIKI_TERRAIN:
            rgb = b._hex2rgb(hx)
            out_jp, out_code, out_kind, conf = classify_center(solid(rgb), 20, 20)
            self.assertEqual(out_code, code, f"{hx} {jp} -> {out_code}")
            self.assertEqual(out_jp, jp)
            self.assertEqual(out_kind, kind)
            self.assertAlmostEqual(conf, 1.0, places=6)

    def test_no_sea_label_in_palette(self):
        # 海/SEA is NOT in the wiki color legend; must not appear as a terrain.
        codes = {code for _rgb, _jp, code, _kind, _hx in b.PALETTE}
        self.assertNotIn("SEA", codes)
        self.assertIn("MAJOR_RIVER", codes)
        self.assertIn("SWIFT_CURRENT", codes)

    def test_unmapped_color_is_unknown(self):
        # (255,0,255) magenta is L1-far from every wiki centroid.
        jp, code, kind, conf = classify_center(solid((255, 0, 255)), 20, 20)
        self.assertEqual(code, "UNKNOWN")
        self.assertEqual(conf, 0.0)

    def test_government_marker_kept(self):
        # red #ff0000 is the wiki 府(GOVERNMENT) color, not a generic occluding marker.
        jp, code, kind, conf = classify_center(solid((255, 0, 0)), 20, 20)
        self.assertEqual(code, "GOVERNMENT")
        self.assertEqual(jp, "府")
        self.assertEqual(kind, "infra")

    def test_city_marker_kept(self):
        jp, code, kind, conf = classify_center(solid((255, 255, 0)), 20, 20)
        self.assertEqual(code, "CITY")
        self.assertEqual(jp, "都市")
        self.assertEqual(kind, "infra")

    def test_no_gray_background_sentinel(self):
        # regression: the invented gray GRID sentinel was removed (it cannibalized 平地).
        # gridline gray (188,188,188) now resolves by nearest-swatch tolerance to WETLAND
        # (L1=49), NOT to a background/OUT_OF_BOUNDS sentinel. It is out-voted at cell
        # centers, so it does not inflate WETLAND.
        jp, code, kind, conf = classify_center(solid((188, 188, 188)), 20, 20)
        self.assertNotEqual(code, "OUT_OF_BOUNDS")
        self.assertEqual(code, "WETLAND")

    def test_far_gray_is_unknown(self):
        # a mid-gray tol-far from every wiki swatch stays UNKNOWN (never invented).
        jp, code, kind, conf = classify_center(solid((128, 128, 128)), 20, 20)
        self.assertEqual(code, "UNKNOWN")

    def test_rendered_plains_classifies_as_plains(self):
        # the map paints 平地 lighter than the wiki swatch (≈197,190,151); with the gray
        # sentinel removed it must resolve to PLAINS, not be cannibalized to background.
        jp, code, kind, conf = classify_center(solid((197, 190, 151)), 20, 20)
        self.assertEqual(code, "PLAINS")

    def test_white_background_out_of_bounds(self):
        jp, code, kind, conf = classify_center(solid((248, 248, 248)), 20, 20)
        self.assertEqual(code, "OUT_OF_BOUNDS")

    def test_low_confidence_below_min_conf_is_unknown(self):
        # window mostly unmapped magenta with a few PLAINS pixels -> kept majority < min_conf
        im = solid((255, 0, 255), 40, 40)
        im[20, 20] = (189, 183, 107)   # wiki 平地
        im[20, 21] = (189, 183, 107)
        jp, code, kind, conf = classify_center(im, 20, 20)
        self.assertEqual(code, "UNKNOWN")
        self.assertLess(conf, geom()["min_conf"])


class FailClosedTest(unittest.TestCase):
    def test_rejects_tracked_repo_path(self):
        tracked = Path(b.__file__).resolve()  # this builder is tracked in the repo
        with self.assertRaises(SystemExit):
            b.assert_untracked(tracked, is_ignored=lambda p: False)

    def test_allows_gitignored_repo_path(self):
        tracked = Path(b.__file__).resolve()
        self.assertEqual(tracked, b.assert_untracked(tracked, is_ignored=lambda p: True))

    def test_allows_outside_repo_path(self):
        with TemporaryDirectory() as td:
            out = Path(td) / "hexmap.json"
            # temp dir is outside the repo tree -> allowed regardless of git
            self.assertEqual(out.resolve(), b.assert_untracked(out, is_ignored=lambda p: False))


class BuildDeterminismTest(unittest.TestCase):
    def _mixed_image(self):
        im = solid((189, 183, 107), 80, 80)          # 平地 base (wiki)
        im[0:20, 0:20] = (33, 88, 103)               # 大河 corner (wiki)
        im[0:20, 60:80] = (117, 146, 60)             # 森 corner (wiki)
        im[60:80, 0:20] = (255, 153, 51)             # 低山 corner (wiki)
        im[60:80, 60:80] = (255, 0, 255)             # unmapped magenta -> UNKNOWN
        return im

    def test_cells_sorted_and_stable(self):
        im = self._mixed_image()
        meta = {"sha256": "x", "width": 80, "height": 80, "bytes": 0, "rights": "test"}
        r1 = b.build(im, meta, geom())
        r2 = b.build(im, meta, geom())
        self.assertEqual(r1, r2)
        keys = [(c["q"], c["r"]) for c in r1["cells"]]
        self.assertEqual(keys, sorted(keys))

    def test_output_bytes_identical(self):
        im = self._mixed_image()
        meta = {"sha256": "x", "width": 80, "height": 80, "bytes": 0, "rights": "test"}
        r = b.build(im, meta, geom())
        s1 = json.dumps(r, ensure_ascii=False, separators=(",", ":"))
        s2 = json.dumps(b.build(im, meta, geom()), ensure_ascii=False, separators=(",", ":"))
        self.assertEqual(s1, s2)

    def test_header_records_geometry_and_wiki_legend(self):
        im = self._mixed_image()
        meta = {"sha256": "x", "width": 80, "height": 80, "bytes": 0, "rights": "test"}
        r = b.build(im, meta, geom())
        self.assertEqual(r["geometry"]["layout"], "offset-column")
        self.assertEqual(r["geometry"]["hex_size"], 19.0)
        self.assertEqual(r["geometry"]["odd_col_dy"], 9.5)
        self.assertTrue(r["terrain_type_evidence_url"].startswith("http"))
        self.assertEqual(r["cell_count"], len(r["cells"]))
        self.assertEqual(sum(r["counts"].values()), r["cell_count"])
        # wiki color legend recorded in header, one row per wiki カラー terrain, with hex+effect
        wcl = r["wiki_color_legend"]
        self.assertIn("wikiwiki.jp/sangokushi14", wcl["source_url"])
        self.assertEqual(len(wcl["legend"]), len(b.WIKI_TERRAIN))
        for row in wcl["legend"]:
            self.assertRegex(row["wiki_hex"], r"^#[0-9a-fA-F]{6}$")
            self.assertEqual(b._hex2rgb(row["wiki_hex"]), tuple(row["rgb"]))
        # every palette entry carries its wiki_hex
        for p in r["palette"]:
            self.assertRegex(p["wiki_hex"], r"^#[0-9a-fA-F]{6}$")

    def test_unknown_cells_record_observed_rgb(self):
        im = self._mixed_image()
        meta = {"sha256": "x", "width": 80, "height": 80, "bytes": 0, "rights": "test"}
        r = b.build(im, meta, geom())
        unknown = [c for c in r["cells"] if c["terrain"] == "UNKNOWN"]
        self.assertTrue(unknown, "magenta corner should yield UNKNOWN cells")
        for c in unknown:
            self.assertIn("observed_rgb", c)
            self.assertEqual(len(c["observed_rgb"]), 3)


if __name__ == "__main__":
    unittest.main()
