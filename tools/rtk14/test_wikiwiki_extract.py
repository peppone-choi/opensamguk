import io
import json
import sys
import tempfile
import unittest
import urllib.error
import urllib.robotparser
from pathlib import Path
from unittest.mock import patch


sys.path.insert(0, str(Path(__file__).resolve().parent))

from wikiwiki_extract import extract_page
from wikiwiki_fetch import (allowed, cached_page_exists, collect, compressed_for,
                            file_for, read_cached_page, retry_after_seconds, save_page)
from wikiwiki_inventory import key_from_url
from wikiwiki_maps import map_links
from wikiwiki_compact import compact


class WikiwikiExtractTest(unittest.TestCase):
    def test_table_span_ruby_links_and_non_table_sections(self) -> None:
        self.assertFalse(True, "temporary CI red probe")
        page = {"page_key": "190年1月 仮の例", "kind": "시나리오",
                "url": "https://wikiwiki.jp/sangokushi14/example"}
        html = b"""<div id='content'>
          <h2>Data</h2><p>Intro paragraph.</p><h3>Forces</h3>
          <table><tr><th rowspan='2'>A</th><th colspan='2'>B</th></tr>
          <tr><th>C</th><th>D</th></tr>
          <tr><td><ruby>Yamada<rt>yamada</rt></ruby></td>
          <td><a href='/sangokushi14/example2'><strong>Link</strong></a></td><td>3</td></tr></table>
          <dl><dt>Term</dt><dd>Definition</dd></dl>
          <h2>\xe3\x82\xb3\xe3\x83\xa1\xe3\x83\xb3\xe3\x83\x88</h2><p>Ignore comment text.</p>
        </div>"""
        tables, sections, normalized, unmapped = extract_page(page, html)
        self.assertEqual(len(tables), 1)
        table = tables[0]
        self.assertEqual(table["width"], 3)
        self.assertEqual(table["header_rows"], [0, 1])
        self.assertIs(table["rows"][0][0], table["rows"][1][0])
        self.assertIs(table["rows"][0][1], table["rows"][0][2])
        self.assertEqual(table["rows"][2][0]["ruby_readings"], ["yamada"])
        self.assertEqual(table["rows"][2][1]["links"][0]["target"],
                         "https://wikiwiki.jp/sangokushi14/example2")
        self.assertEqual([section["text"] for section in sections],
                         ["Intro paragraph.", "Term", "Definition"])
        self.assertEqual(sections[-1]["title_path"], ["Data", "Forces"])
        self.assertEqual(normalized["year_month"], "190.1")
        self.assertEqual(normalized["table_data"][0]["blocks"][0]["columns"], ["A", "B / C", "B / D"])
        self.assertEqual(unmapped, [])

    def test_headerless_table_is_preserved_and_reported_unmapped(self) -> None:
        page = {"page_key": "sample", "kind": "기타", "url": "https://wikiwiki.jp/sangokushi14/sample"}
        tables, _, normalized, unmapped = extract_page(page, b"<div id='content'><table><tr><td>42</td></tr></table></div>")
        self.assertEqual(tables[0]["rows"][0][0]["text"], "42")
        self.assertEqual(normalized["table_data"][0]["blocks"], [])
        self.assertEqual(unmapped[0]["table_index"], 0)

    def test_synthetic_officer_sections_keep_biography_and_scenario_rows(self) -> None:
        page = {"page_key": "example", "kind": "인물", "url": "https://wikiwiki.jp/sangokushi14/example"}
        html = """<div id='content'>
          <table><tr><td><strong>架空者(カクウ)</strong></td><td><strong>字</strong></td></tr>
          <tr><td></td><td>仮字</td></tr><tr><td colspan='2'><strong>列伝</strong></td></tr>
          <tr><td colspan='2'>Synthetic biography.</td></tr></table>
          <table><tr><th>父親</th><th>親愛</th></tr><tr><td>-</td><td>友人</td></tr></table>
          <table><tr><th>シナリオ</th><th>身分</th><th>所在</th><th>勢力</th><th>爵位/官職</th></tr>
          <tr><td colspan='5'>史実</td></tr>
          <tr><td>190年1月 synthetic</td><td>一般</td><td>仮城</td><td>仮勢力</td><td>仮官</td></tr></table>
        </div>""".encode()
        _, _, normalized, _ = extract_page(page, html)
        self.assertEqual((normalized["name_kanji"], normalized["name_reading"]), ("架空者", "カクウ"))
        self.assertEqual(normalized["biography"], ["Synthetic biography."])
        self.assertEqual(normalized["relationships"], {"親愛": ["友人"]})
        self.assertEqual(normalized["scenarios"][0]["year_month"], "190.1")
        self.assertEqual(normalized["scenarios"][0]["爵位/官職"], "仮官")

    def test_synthetic_city_regions_and_map_selection(self) -> None:
        page = {"page_key": "仮城", "kind": "도시·지역",
                "url": "https://wikiwiki.jp/sangokushi14/example"}
        html = """<div id='content'><h3>地域データ</h3>
          <table><tr><th>地方</th><th>隣接都市</th></tr>
          <tr><td>仮州</td><td>甲、乙、丙</td></tr></table>
          <table><tr><th>種類</th><th>名称</th><th>ヨミ</th><th>所属</th><th>金収入</th></tr>
          <tr><td>⭖</td><td>仮城</td><td>カリ</td><td>仮城</td><td>10</td></tr>
          <tr><td>⚓</td><td>仮港</td><td>カリコウ</td><td>仮城</td><td>4</td></tr>
          <tr><td></td><td>仮府</td><td>カリフ</td><td>仮城</td><td>3</td></tr></table>
          <h3>周辺地図</h3><a data-lightbox='imageset' title='map.jpg'
          href='https://cdn.wikiwiki.jp/to/w/sangokushi14/example/::attach/map.jpg'>map</a>
          <p>街道は仮府に至る。</p></div>""".encode()
        _, _, normalized, _ = extract_page(page, html)
        self.assertEqual([row["name"] for row in normalized["regions"]], ["仮城", "仮港", "仮府"])
        self.assertEqual([row["site_type"] for row in normalized["regions"]], ["city", "port", "region"])
        self.assertEqual(normalized["regions"][2]["fields"]["金収入"], "3")
        self.assertEqual(normalized["adjacent_cities"], ["甲", "乙", "丙"])
        self.assertEqual(len(normalized["road_mentions"]), 1)
        self.assertEqual(len(map_links(html)), 1)

    def test_url_and_cache_guards(self) -> None:
        self.assertEqual(key_from_url("https://wikiwiki.jp/sangokushi14/%E4%BE%8B"), "例")
        self.assertIsNone(key_from_url("https://wikiwiki.jp/sangokushi14/a?x=1"))
        with tempfile.TemporaryDirectory() as temporary:
            path = file_for(Path(temporary), {"page_key": "例/一", "kind": "기타"})
            self.assertEqual(path.name, "%E4%BE%8B%2F%E4%B8%80.html")
        rules = urllib.robotparser.RobotFileParser()
        rules.parse(["User-Agent: *", "Disallow: /*?", "Disallow: /*/::*"])
        self.assertTrue(allowed(rules, "https://wikiwiki.jp/sangokushi14/sample"))
        self.assertFalse(allowed(rules, "https://wikiwiki.jp/sangokushi14/sample?cmd=edit"))
        self.assertFalse(allowed(rules, "https://wikiwiki.jp/sangokushi14/sample/::attach/x.png"))

    def test_429_waits_then_retries_without_consuming_error_attempts(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "robots.txt").write_text("User-Agent: *\nDisallow: /*?\nDisallow: /*/::*\n")
            (root / "inventory.json").write_text(json.dumps({"total": 1, "pages": [
                {"page_key": "sample", "kind": "기타", "url": "https://wikiwiki.jp/sangokushi14/sample"}]}))

            class Opener:
                calls = 0

                def __init__(self):
                    self.error = None

                def open(self, request, timeout):
                    self.calls += 1
                    if self.calls == 1:
                        self.error = urllib.error.HTTPError(request.full_url, 429, "rate limited", {"Retry-After": "120"}, io.BytesIO())
                        raise self.error

                    class Response:
                        status = 200
                        url = request.full_url

                        def __enter__(self):
                            return self

                        def __exit__(self, *args):
                            return False

                        def read(self):
                            return b"<div id='content'></div>"

                    return Response()

            opener = Opener()
            with patch("wikiwiki_fetch.urllib.request.build_opener", return_value=opener), patch("wikiwiki_fetch.time.sleep") as sleep:
                result = collect(root)
            opener.error.close()
            self.assertIsNone(result["stopped"])
            self.assertEqual((result["rate_limited"], result["fetched"]), (1, 1))
            self.assertEqual(opener.calls, 2)
            self.assertTrue(any(call.args == (120,) for call in sleep.call_args_list))
            self.assertEqual(len((root / "fetch-log.jsonl").read_text().splitlines()), 2)
            self.assertTrue(cached_page_exists(file_for(root, {"page_key": "sample", "kind": "기타"})))
            self.assertEqual(read_cached_page(file_for(root, {"page_key": "sample", "kind": "기타"})),
                             b"<div id='content'></div>")

    def test_lossless_cache_compaction_removes_only_verified_html(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = file_for(root, {"page_key": "sample", "kind": "기타"})
            source.parent.mkdir(parents=True)
            original = b"<div id='content'>repeated repeated repeated</div>"
            source.write_bytes(original)
            result = compact(root)
            self.assertEqual(result["converted"], 1)
            self.assertFalse(source.exists())
            self.assertTrue(compressed_for(source).is_file())
            self.assertEqual(read_cached_page(source), original)
            self.assertEqual(compact(root)["converted"], 0)
            other = file_for(root, {"page_key": "new", "kind": "기타"})
            save_page(other, original)
            self.assertFalse(other.exists())
            self.assertEqual(read_cached_page(other), original)

    def test_retry_after_seconds(self) -> None:
        self.assertEqual(retry_after_seconds("120"), 120)
        self.assertEqual(retry_after_seconds("invalid"), 0)


if __name__ == "__main__":
    unittest.main()
