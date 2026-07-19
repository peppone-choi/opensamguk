import json
import sys
import tempfile
import unittest
from pathlib import Path


SCENARIO_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCENARIO_DIR))

from parse_pages import PageCollectionError, collect_pages, main, parse_officer_page, parse_roster


STATUSES = (
    "君主",
    "太守",
    "都督",
    "一般",
    "在野",
    "未発見",
    "未登場",
    "死亡",
    None,
)


def roster_html() -> str:
    return (
        "<table>\n"
        "  <tr><td><a href=\"/sangokushi14/%E9%BB%84%E3%82%A8%E3%83%B3\">黄琬</a></td></tr>\n"
        "  <tr><td><a href=\"/sangokushi14/%E4%B8%98%E5%8A%9B%E5%B1%85\"><ruby>丘力居<rt>キュウリキキョ</rt></ruby></a></td></tr>\n"
        "</table>\n"
    )


def officer_html() -> str:
    scenario_rows = "\n".join(
        (
            "<tr>\n"
            f"  <td>{184 + index}年{index % 12 + 1}月 scenario</td><td>20</td>\n"
            f"  <td>{status or '-'} </td><td>北平</td><td>公孫瓚</td><td>-</td><td>-</td>\n"
            "</tr>"
        )
        for index, status in enumerate(STATUSES)
    )
    return (
        "<table>\n"
        "  <tr>\n"
        "    <th><strong>統率</strong></th><th><strong>武力</strong></th><th><strong>知力</strong></th>\n"
        "    <th><strong>政治</strong></th><th><strong>魅力</strong></th>\n"
        "  </tr>\n"
        "  <tr><td>82(92)</td><td>73</td><td>64</td><td>55</td><td>46</td></tr>\n"
        "</table>\n"
        "<table>\n"
        "  <tr><th><strong>生年</strong></th><th><strong>没年</strong></th></tr>\n"
        "  <tr><td>161年</td><td>229年</td></tr>\n"
        "</table>\n"
        "<table>\n"
        "  <tr>\n"
        "    <th><strong>シナリオ</strong></th><th><strong>年齢</strong></th><th><strong>身分</strong></th>\n"
        "    <th><strong>所在</strong></th><th><strong>勢力</strong></th><th><strong>爵位</strong></th><th><strong>官職</strong></th>\n"
        "  </tr>\n"
        f"{scenario_rows}\n"
        "</table>\n"
        "<table>\n"
        "  <tr><th><strong>列伝</strong></th></tr>\n"
        "  <tr><td>wiki-authored text must not enter a parsed record</td></tr>\n"
        "</table>\n"
    )


def incomplete_officer_html() -> str:
    return officer_html().replace("<th><strong>魅力</strong></th>", "")


class ParsePagesTest(unittest.TestCase):
    def test_roster_keeps_visible_kanji_and_decodes_rare_page_keys(self) -> None:
        self.assertEqual(
            parse_roster(roster_html()),
            [
                {"name_kanji": "黄琬", "name_reading": None, "page_key": "黄エン"},
                {"name_kanji": "丘力居", "name_reading": "キュウリキキョ", "page_key": "丘力居"},
            ],
        )

    def test_officer_page_extracts_fingerprint_and_all_statuses_without_source_labels(self) -> None:
        record = parse_officer_page(
            officer_html(),
            name_kanji="黄琬",
            name_reading="コウエン",
            page_key="黄エン",
        )

        self.assertEqual(
            {key: record[key] for key in ("birth", "death", "leadership", "strength", "intelligence", "politics", "charm")},
            {
                "birth": 161,
                "death": 229,
                "leadership": 82,
                "strength": 73,
                "intelligence": 64,
                "politics": 55,
                "charm": 46,
            },
        )
        self.assertEqual([row["status"] for row in record["scenarios"]], list(STATUSES))
        self.assertEqual(record["scenarios"][0], {
            "year_month": "184.1",
            "status": "君主",
            "location": "北平",
            "faction": "公孫瓚",
            "office": None,
        })
        serialized = json.dumps(record, ensure_ascii=False)
        self.assertNotIn("列伝", serialized)
        self.assertNotIn("wiki-authored", serialized)

    def test_collect_pages_uses_cache_and_fails_closed_for_missing_pages(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            roster_path = root / "roster.html"
            cache = root / "pages"
            cache.mkdir()
            roster_path.write_text(roster_html(), encoding="utf-8")
            (cache / "黄エン.html").write_text(officer_html(), encoding="utf-8")
            (cache / "丘力居.html").write_text(officer_html(), encoding="utf-8")

            records = collect_pages(roster_path, cache)

            self.assertEqual([record["name_kanji"] for record in records], ["黄琬", "丘力居"])

            (cache / "丘力居.html").unlink()
            with self.assertRaises(PageCollectionError) as raised:
                collect_pages(roster_path, cache)
            self.assertIn("丘力居", str(raised.exception))

    def test_missing_fingerprint_field_fails_parser_collection_and_cli_with_a_named_error(self) -> None:
        with self.assertRaisesRegex(ValueError, "missing required fingerprint fields: charm"):
            parse_officer_page(
                incomplete_officer_html(),
                name_kanji="黄琬",
                name_reading="コウエン",
                page_key="黄エン",
            )

        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            roster_path = root / "roster.html"
            cache = root / "pages"
            output_path = root / "out.json"
            report_path = root / "report.json"
            cache.mkdir()
            roster_path.write_text('<a href="/sangokushi14/%E9%BB%84%E3%82%A8%E3%83%B3">黄琬</a>', encoding="utf-8")
            (cache / "黄エン.html").write_text(incomplete_officer_html(), encoding="utf-8")

            with self.assertRaisesRegex(PageCollectionError, "黄エン: missing required fingerprint fields: charm"):
                collect_pages(roster_path, cache)

            self.assertEqual(
                main([
                    "--roster", str(roster_path),
                    "--page-cache", str(cache),
                    "--out", str(output_path),
                    "--report", str(report_path),
                ]),
                1,
            )
            self.assertFalse(output_path.exists())
            self.assertEqual(json.loads(report_path.read_text(encoding="utf-8")), {
                "records": 0,
                "errors": ["黄エン: missing required fingerprint fields: charm"],
            })


if __name__ == "__main__":
    unittest.main()
