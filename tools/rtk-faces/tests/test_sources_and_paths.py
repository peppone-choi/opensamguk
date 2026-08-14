import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import build_rtk14_faces as b  # noqa: E402

class TestManifest(unittest.TestCase):
    def test_requires_observed_page_and_attachment_urls(self):
        with self.assertRaises(b.ManifestError):
            b.parse_manifest("조조\thttps://cdn.wikiwiki.jp/to/w/sangokushi14/ENC/::attach/f.jpg")

    def test_rejects_attachment_outside_observed_officer_page_namespace(self):
        text = (
            "조조\thttps://wikiwiki.jp/sangokushi14/ENC\t"
            "https://cdn.wikiwiki.jp/to/w/sangokushi14/OTHER/::attach/f.jpg?rev=abc"
        )
        with self.assertRaises(b.ManifestError):
            b.parse_manifest(text)

    def test_rejects_page_path_traversal(self):
        text = (
            "조조\thttps://wikiwiki.jp/sangokushi14/%2E%2E\t"
            "https://cdn.wikiwiki.jp/to/w/sangokushi14/%2E%2E/::attach/f.jpg"
        )
        with self.assertRaises(b.ManifestError):
            b.parse_manifest(text)

    def test_rejects_page_query_and_non_attachment_reference(self):
        with self.assertRaises(b.ManifestError):
            b.parse_manifest(
                "조조\thttps://wikiwiki.jp/sangokushi14/ENC?cmd=edit\t"
                "https://cdn.wikiwiki.jp/to/w/sangokushi14/ENC/::attach/f.jpg"
            )
        with self.assertRaises(b.ManifestError):
            b.parse_manifest(
                "조조\thttps://wikiwiki.jp/sangokushi14/ENC\t"
                "https://cdn.wikiwiki.jp/to/w/sangokushi14/ENC/::ref/f.jpg"
            )

    def test_ordering_is_deterministic(self):
        text = (
            "b\thttps://wikiwiki.jp/sangokushi14/B\t"
            "https://cdn.wikiwiki.jp/to/w/sangokushi14/B/::attach/b.png\n"
            "a\thttps://wikiwiki.jp/sangokushi14/A\t"
            "https://cdn.wikiwiki.jp/to/w/sangokushi14/A/::attach/a.png"
        )
        rows1 = b.parse_manifest(text)
        rows2 = b.parse_manifest(text)
        self.assertEqual([row.name for row in rows1], ["a", "b"])
        self.assertEqual(rows1, rows2)

    def test_rejects_duplicate_observed_officer_page(self):
        text = (
            "n\thttps://wikiwiki.jp/sangokushi14/ENC\t"
            "https://cdn.wikiwiki.jp/to/w/sangokushi14/ENC/::attach/1.png\n"
            "n2\thttps://wikiwiki.jp/sangokushi14/ENC\t"
            "https://cdn.wikiwiki.jp/to/w/sangokushi14/ENC/::attach/2.png"
        )
        with self.assertRaises(b.ManifestError):
            b.parse_manifest(text)

    def test_rejects_percent_encoded_duplicate_observed_officer_page(self):
        text = (
            "n\thttps://wikiwiki.jp/sangokushi14/ENC\t"
            "https://cdn.wikiwiki.jp/to/w/sangokushi14/ENC/::attach/1.png\n"
            "n2\thttps://wikiwiki.jp/sangokushi14/%45NC\t"
            "https://cdn.wikiwiki.jp/to/w/sangokushi14/%45NC/::attach/2.png"
        )
        with self.assertRaises(b.ManifestError):
            b.parse_manifest(text)

    def test_rejects_nested_encoded_page_traversal(self):
        text = (
            "조조\thttps://wikiwiki.jp/sangokushi14/%252E%252E\t"
            "https://cdn.wikiwiki.jp/to/w/sangokushi14/%252E%252E/::attach/f.jpg"
        )
        with self.assertRaises(b.ManifestError):
            b.parse_manifest(text)

    def test_rejects_triply_encoded_attachment_separator(self):
        text = (
            "조조\thttps://wikiwiki.jp/sangokushi14/ENC\t"
            "https://cdn.wikiwiki.jp/to/w/sangokushi14/ENC/::attach/"
            "%25252Fother.png"
        )
        with self.assertRaises(b.ManifestError):
            b.parse_manifest(text)

    def test_rejects_empty_name_and_url(self):
        with self.assertRaises(b.ManifestError):
            b.parse_manifest(
                "\thttps://wikiwiki.jp/sangokushi14/ENC\t"
                "https://cdn.wikiwiki.jp/to/w/sangokushi14/ENC/::attach/f.png"
            )
        with self.assertRaises(b.ManifestError):
            b.parse_manifest(
                "name\t\thttps://cdn.wikiwiki.jp/to/w/sangokushi14/ENC/::attach/f.png"
            )

    def test_skips_comments_and_blanks(self):
        rows = b.parse_manifest(
            "# header\n\nname\thttps://wikiwiki.jp/sangokushi14/ENC\t"
            "https://cdn.wikiwiki.jp/to/w/sangokushi14/ENC/::attach/f.png\n"
        )
        self.assertEqual(len(rows), 1)

    def test_normalized_url_strips_rev_query(self):
        raw = "https://cdn.wikiwiki.jp/to/w/sangokushi14/ENC/::attach/f.png?rev=abc&t=1"
        rows = b.parse_manifest(f"呂布\thttps://wikiwiki.jp/sangokushi14/ENC\t{raw}")
        self.assertEqual(
            rows[0].observed_image_url,
            "https://cdn.wikiwiki.jp/to/w/sangokushi14/ENC/::attach/f.png",
        )

    def test_strip_query_also_drops_fragment(self):
        self.assertEqual(b.strip_query("http://x/a.png?rev=1#frag"), "http://x/a.png")

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
