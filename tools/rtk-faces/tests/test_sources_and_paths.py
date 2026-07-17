import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import build_rtk14_faces as b  # noqa: E402


def officer_cell(encoded_name, display_name, reading="ヨミ"):
    return (
        f'<td style="text-align:center"><a href="/sangokushi14/{encoded_name}" '
        f'title="{display_name}" class="rel-wiki-page">{display_name}</a></td>'
        f'<td style="text-align:center">{reading}</td>'
    )


class TestManifest(unittest.TestCase):
    def test_ordering_is_deterministic(self):
        text = "\t".join(["b", "http://x/b.png"]) + "\n" + "\t".join(["a", "http://x/a.png"])
        rows1 = b.parse_manifest(text)
        rows2 = b.parse_manifest(text)
        self.assertEqual([row[0] for row in rows1], ["a", "b"])
        self.assertEqual(rows1, rows2)

    def test_rejects_duplicate_name(self):
        text = "n\thttp://x/1.png\nn\thttp://x/2.png"
        with self.assertRaises(b.ManifestError):
            b.parse_manifest(text)

    def test_rejects_empty_name_and_url(self):
        with self.assertRaises(b.ManifestError):
            b.parse_manifest("\thttp://x/1.png")
        with self.assertRaises(b.ManifestError):
            b.parse_manifest("name\t")

    def test_skips_comments_and_blanks(self):
        rows = b.parse_manifest("# header\n\nname\thttp://x/1.png\n")
        self.assertEqual(len(rows), 1)

    def test_normalized_url_strips_rev_query(self):
        raw = "https://cdn.wikiwiki.jp/to/w/sangokushi14/呂布/::attach/f.png?rev=abc&t=1"
        rows = b.parse_manifest(f"呂布\t{raw}")
        self.assertEqual(rows[0][2], "https://cdn.wikiwiki.jp/to/w/sangokushi14/呂布/::attach/f.png")

    def test_strip_query_also_drops_fragment(self):
        self.assertEqual(b.strip_query("http://x/a.png?rev=1#frag"), "http://x/a.png")


class TestParseRoster(unittest.TestCase):
    def test_isolates_officers_sorted_and_deduped(self):
        html = (
            '<a href="/sangokushi14/::cmd/edit?page=x" rel="nofollow">編集</a>'
            '<li><a href="/sangokushi14/FAQ" class="rel-wiki-page">FAQ</a></li>'
            + officer_cell("%E6%9B%B9%E6%93%8D", "曹操", "ソウソウ")
            + officer_cell("%E5%91%82%E5%B8%83", "呂布", "リョフ")
            + officer_cell("%E6%9B%B9%E6%93%8D", "曹操", "ソウソウ")
        )
        rows = b.parse_roster(html)
        self.assertEqual([name for name, _encoded, _reading in rows], ["呂布", "曹操"])
        by_name = {name: (encoded, reading) for name, encoded, reading in rows}
        self.assertEqual(by_name["曹操"], ("%E6%9B%B9%E6%93%8D", "ソウソウ"))

    def test_drops_menu_links_keeps_katakana_substituted_page(self):
        encoded_name = "%E3%83%9B%E3%82%A6%E7%B5%B1"
        html = (
            '<li><a href="/sangokushi14/%E5%9C%B0%E7%90%86" class="rel-wiki-page">地理</a></li>'
            + f'<td><a href="/sangokushi14/{encoded_name}" title="ホウ統" '
              'class="rel-wiki-page">龐統</a></td><td>ホウトウ</td>'
        )
        self.assertEqual(b.parse_roster(html), [("龐統", encoded_name, "ホウトウ")])


class TestReadingPortraitUrl(unittest.TestCase):
    def test_constructs_namespaced_cdn_attach_url(self):
        self.assertEqual(
            b.reading_portrait_url("%E6%9B%B9%E6%93%8D", "ソウソウ"),
            "https://cdn.wikiwiki.jp/to/w/sangokushi14/%E6%9B%B9%E6%93%8D"
            "/::attach/%E3%82%BD%E3%82%A6%E3%82%BD%E3%82%A6.jpg",
        )


class TestParsePortrait(unittest.TestCase):
    def test_prefers_attach_over_ref_and_strips_query(self):
        html = (
            '<img src="https://cdn.wikiwiki.jp/to/w/sangokushi14/ENC/::ref/f.jpg.webp?rev=1&amp;t=2">'
            '<a href="https://cdn.wikiwiki.jp/to/w/sangokushi14/ENC/::attach/f.jpg?rev=1&amp;t=2">'
        )
        self.assertEqual(
            b.parse_portrait_url(html, "ENC"),
            "https://cdn.wikiwiki.jp/to/w/sangokushi14/ENC/::attach/f.jpg",
        )

    def test_falls_back_to_ref_when_no_attach(self):
        html = '<img src="https://cdn.wikiwiki.jp/to/w/sangokushi14/ENC/::ref/f.png?rev=9">'
        self.assertEqual(
            b.parse_portrait_url(html, "ENC"),
            "https://cdn.wikiwiki.jp/to/w/sangokushi14/ENC/::ref/f.png",
        )

    def test_rejects_other_officer_namespace(self):
        html = '<img src="https://cdn.wikiwiki.jp/to/w/sangokushi14/OTHER/::attach/f.jpg?rev=1">'
        self.assertIsNone(b.parse_portrait_url(html, "ENC"))

    def test_none_when_no_portrait(self):
        self.assertIsNone(b.parse_portrait_url("<html>nothing</html>", "ENC"))


class TestPathGuard(unittest.TestCase):
    def setUp(self):
        self.repo = Path(b.__file__).resolve().parents[2]

    def test_outside_repo_is_allowed(self):
        with tempfile.TemporaryDirectory() as directory:
            b.assert_safe_path(Path(directory) / "out", self.repo)

    def test_repo_tracked_path_fails_closed(self):
        inside = self.repo / "tools" / "rtk-faces" / "should-not-write"
        with self.assertRaises(SystemExit):
            b.assert_safe_path(inside, self.repo, is_ignored=lambda path, root: False)

    def test_repo_but_gitignored_is_allowed(self):
        inside = self.repo / "build" / "x"
        b.assert_safe_path(inside, self.repo, is_ignored=lambda path, root: True)

    def test_path_is_outside_helper(self):
        with tempfile.TemporaryDirectory() as directory:
            self.assertTrue(b.path_is_outside(Path(directory), self.repo))
        self.assertFalse(b.path_is_outside(self.repo / "tools", self.repo))


if __name__ == "__main__":
    unittest.main()
