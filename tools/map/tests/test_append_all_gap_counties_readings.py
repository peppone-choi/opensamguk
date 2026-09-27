#!/usr/bin/env python3
"""결손 縣 합성 행의 한글 읽기 — 簡體 원문 글자를 hanja 가 딴 글자로 읽는 사고를 막는다.

卷113 저본은 簡體다. hanja 는 广 을 부수 「엄」(广汉 → 엄한), 乐 을 「악」, 媪 을 「오」로 읽는다.
CI 에는 hanja 가 없으므로 읽기 형태(reading_form)와 원장의 글자 위치만 검사한다.
"""
import json
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import append_all_gap_counties as A  # noqa: E402

# 簡體 글자 → 그 자리에 와야 할 한글 음(두음법칙 포함). 원장 검사용이다.
EXPECTED_SYLLABLES = {"广": {"광"}, "乐": {"낙", "락"}, "媪": {"온"}}
# 2026-09-27 재핀 사슬에서 `--refresh-readings` 로 오독 12행을 고쳤다(南〈糸言糸〉 은 중복으로 은퇴). 남은 오독은 없다.
PENDING_REFRESH: dict[str, str] = {}
# 한글이 아닌 글자가 남은 읽기(조합 표기·코퍼스 자형). 없다.
PENDING_NON_HANGUL: dict[str, str] = {}


class ReadingFormTest(unittest.TestCase):
    def test_simplified_glyphs_are_read_as_their_place_name_form(self):
        # 오독하는 글자만 바꾼다. 汉·阳·围 는 hanja 가 한·양·위 로 바로 읽는다.
        self.assertEqual("廣汉", A.reading_form("廣漢郡", "广汉"))
        self.assertEqual("樂都", A.reading_form("樂浪郡", "乐都"))
        self.assertEqual("阳樂", A.reading_form("遼西郡", "阳乐"))
        self.assertEqual("媼围", A.reading_form("武威郡", "媪围"))

    def test_ambiguous_glyph_uses_the_per_name_form(self):
        # 郁 은 그 자체로 繁體(욱)라 글자표에 넣을 수 없다. 漢書 地理志 鬱林郡 「廣鬱」.
        self.assertEqual("廣鬱", A.reading_form("鬱林郡", "广郁"))
        self.assertNotIn("郁", A.READING_GLYPHS)

    def test_glyphs_that_are_themselves_traditional_are_left_alone(self):
        # 于離(漢書 太原郡)·蘭干(漢陽郡)은 원래 于·干 이다. 於·乾 으로 바꾸면 어리·난건 이 된다.
        self.assertEqual("于离", A.reading_form("太原郡", "于离"))
        self.assertEqual("兰干", A.reading_form("漢陽郡", "兰干"))

    def test_source_spelling_is_not_rewritten(self):
        ledger = json.loads(A.LEDGER.read_text(encoding="utf-8"))
        row = next(r for r in ledger["counties"] if r["id"] == "hhs:113:廣漢郡:010")
        self.assertEqual("广汉", row["nameHan"])


class LedgerReadingTest(unittest.TestCase):
    def test_simplified_glyph_positions_carry_the_place_name_syllable(self):
        ledger = json.loads(A.LEDGER.read_text(encoding="utf-8"))
        wrong = {}
        for row in ledger["counties"]:
            if row.get("nameScript") != "SOURCE_LITERAL" or len(row["nameKo"]) != len(row["nameHan"]):
                continue
            for glyph, syllable in zip(row["nameHan"], row["nameKo"]):
                if glyph in EXPECTED_SYLLABLES and syllable not in EXPECTED_SYLLABLES[glyph]:
                    wrong[row["id"]] = row["nameKo"]
        self.assertEqual(PENDING_REFRESH, wrong)

    def test_readings_are_hangul_only(self):
        ledger = json.loads(A.LEDGER.read_text(encoding="utf-8"))
        wrong = {row["id"]: row["nameKo"] for row in ledger["counties"]
                 if not all("가" <= ch <= "힣" for ch in row["nameKo"])}
        self.assertEqual(PENDING_NON_HANGUL, wrong)

    def test_non_hangul_rows_have_a_reviewed_form_or_override(self):
        self.assertEqual("南䜌", A.reading_form("鉅鹿郡", "南〈糸言糸〉"))
        self.assertEqual("남감", A.korean_reading("樂浪郡", "𧦦邯"))

    def test_every_pending_row_is_fixed_by_the_reading_form(self):
        ledger = {r["id"]: r for r in json.loads(A.LEDGER.read_text(encoding="utf-8"))["counties"]}
        for row_id in PENDING_REFRESH:
            row = ledger[row_id]
            form = A.reading_form(row["commanderyHan"], row["nameHan"])
            self.assertFalse(set(form) & set(A.READING_GLYPHS), row_id)
            self.assertNotEqual(row["nameHan"], form, row_id)


if __name__ == "__main__":
    unittest.main()
