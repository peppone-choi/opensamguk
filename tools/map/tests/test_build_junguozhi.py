# build_junguozhi.py 는 tools/map/tests/test_junguozhi_contract.py 가 검사하는
# junguozhi_contract.py(위키문헌 → data/curated/han/administrative-units.json)와
# 완전히 다른 파이프라인이다(ctext.org HTML → data/map/junguozhi.json, 게임이
# 실제로 쓰는 산출물). 이 파일은 그 파이프라인을 직접 검사한다.
#
# data/chgis-source/** 와 data/map/junguozhi.json 은 ADR-LITE-039 로 gitignore
# 돼 있어 CI 체크아웃에는 없다 — 그래서 여기 두 계층으로 나눈다:
#   1) 순수 로직(정규식·DP·cn_int 등)은 합성 fixture 로 CI 에서 항상 돈다.
#   2) 실제 산출물(checksum·郡國 105개 등) 검증은 로컬에 원본 데이터가 있을
#      때만 돈다 — 없으면 skip(CI에서는 항상 skip, 실패로 착각하지 말 것).
import importlib.util
import re
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
MODULE_PATH = ROOT / "tools/map/build_junguozhi.py"
SPEC = importlib.util.spec_from_file_location("build_junguozhi", MODULE_PATH)
bj = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = bj
SPEC.loader.exec_module(bj)


class TestCnInt(unittest.TestCase):
    def test_basic(self):
        self.assertEqual(bj.cn_int('十四'), 14)
        self.assertEqual(bj.cn_int('十八'), 18)
        self.assertEqual(bj.cn_int('一百五'), 105)
        self.assertEqual(bj.cn_int('六萬一千四百九十二'), 61492)

    def test_none_on_garbage(self):
        self.assertIsNone(bj.cn_int('abc'))


class TestStateTail(unittest.TestCase):
    """右X州刺史部 요약문 — 郡 블록에 들어가면 마지막 縣의 註를 오염시킨다
    (魯國 汶陽·長沙郡 容陵·日南郡 象林에서 실측). 12州 전부 매칭돼야 한다."""

    LINES = (
        '右豫州刺史部，郡、國六，縣、邑、、侯國九十九。',
        '右冀州刺史部，郡、國九，縣、邑、侯國百。',
        '右兗州刺史部，郡、國八，縣、邑、公、侯國八十。',
        '右徐州刺史部，郡、國五，縣、邑、侯國六十二。',
        '右青州刺史部，郡、國六，縣六十五。',
        '右荊州刺史部，郡七，縣、邑、侯國百一十七。',
        '右揚州刺史部，郡六，縣、邑、侯國九十二。',
        '右益州刺史部，郡、國十二，縣、道百一十八。',
        '右涼州刺史部，郡國十二，縣、道、候官九十八。',
        '右并州刺史部，郡九，縣、邑、侯國九十八。',
        '右幽州刺史部，郡、國十一，縣、邑、侯國九十。',
        '右交州刺史部，郡七，縣五十六。',
    )

    def test_matches_all_twelve_provinces(self):
        for line in self.LINES:
            self.assertRegex(line, bj.STATE_TAIL.pattern)
            self.assertTrue(bj.STATE_TAIL.match(line), line)

    def test_does_not_swallow_a_real_county_name(self):
        self.assertFalse(bj.STATE_TAIL.match('江州'))
        self.assertFalse(bj.STATE_TAIL.match('比景'))


class TestSplitUnresolvedRun(unittest.TestCase):
    """CHGIS 사전에 없는 縣이 註 없이 줄줄이 붙을 때(西河郡·樂浪郡류) DP 가
    다수결로 2자 짝을 우선하는지 — 사전 적중이 전혀 없는 순수 짝수 런에서는
    항상 전부 2자로 쪼개져야 한다."""

    def test_all_unknown_even_run_prefers_pairs(self):
        run = '朝鮮烫邯浿水含資'  # 樂浪郡 앞부분, 전부 CHGIS 밖 지명
        pieces = bj.split_unresolved_run(run, lex=frozenset(), cnty_xy=None,
                                          anchor=None, max_km=None)
        self.assertEqual(pieces, ['朝鮮', '烫邯', '浿水', '含資'])

    def test_confirmed_1char_name_whitelisted(self):
        # 藺(西河郡) — cnty_xy 엔 있지만 lex 엔 없다고 확인된 실제 사례.
        self.assertIn('藺', bj.CONFIRMED_1CHAR_NAMES)


class TestNoteStartRun(unittest.TestCase):
    """run_end 전방탐색용 NOTE_START_RUN 은 秦/周/漢 단독 왕조자를 뺀다 —
    西河郡 「平周」의 「周」가 註 시작으로 오인되던 회귀 방지."""

    def test_bare_dynasty_chars_excluded(self):
        for ch in ('秦', '周', '漢'):
            self.assertFalse(bj.NOTE_START_RUN.match(ch))
            self.assertTrue(bj.NOTE_START.match(ch))

    def test_multichar_forms_still_present(self):
        for w in ('世祖', '光武', '王莽', '莽曰', '春秋'):
            self.assertTrue(bj.NOTE_START_RUN.match(w))


class TestConfirmedNoteLikeNames(unittest.TestCase):
    def test_tunyou_cited(self):
        # 屯有 — 後漢書 卷113 郡國志 원문 〖屯有〗(樂浪郡). 有 가 NOTE_START 라
        # run_end 탐색이 屯 뒤에서 끊기던 회귀 방지.
        self.assertIn('屯有', bj.CONFIRMED_NOTE_LIKE_NAMES)


@unittest.skipUnless(
    (ROOT / 'data/chgis-source/junguozhi/wu.html').exists() and
    (ROOT / 'data/chgis-source/v6_time_cnty_pts_utf_wgs84.dbf').exists(),
    'data/chgis-source/** 는 ADR-LITE-039 로 gitignore 돼 있다 — CI 체크아웃에는 '
    '없으므로 이 클래스는 CI 에서 항상 skip 된다(실패 아님). 로컬에 원본을 받아 '
    '두면 실제 산출물(checksum·郡國 105개)까지 검증한다.'
)
class TestFullPipelineOutput(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.out = []
        real_exit = bj.sys.exit
        captured = {}

        def capture_exit(msg=None):
            captured['msg'] = msg
            raise SystemExit(msg)

        bj.sys.exit = capture_exit
        try:
            bj.main()
            captured['ok'] = True
        except SystemExit as e:
            captured['ok'] = False
            captured['msg'] = str(e)
        finally:
            bj.sys.exit = real_exit
        cls.result = captured
        import json
        cls.data = json.load(open(ROOT / 'data/map/junguozhi.json', encoding='utf-8'))

    def test_gate_passes_clean(self):
        self.assertTrue(self.result['ok'],
                         f"checksum 하드게이트가 막았다: {self.result.get('msg')}")

    def test_no_fail_checksum(self):
        fails = [p['name'] for p in self.data['places'] if p['checksum'] == 'FAIL']
        self.assertEqual(fails, [], f'checksum FAIL: {fails}')

    def test_no_count_only_whitelisted(self):
        bad = [p['name'] for p in self.data['places']
               if p['checksum'] == 'NO_COUNT' and p['name'] not in bj.NO_COUNT_WHITELIST]
        self.assertEqual(bad, [], f'화이트리스트 밖 NO_COUNT: {bad}')

    def test_jun_count_is_105(self):
        # 後漢書 卷113 郡國志: 「凡郡、國百五，縣、邑、道、侯國千一百八十」
        self.assertEqual(len(self.data['places']), 105)

    def test_every_seat_is_first_listed_county(self):
        # 「凡縣名先書者，郡所治也」
        for p in self.data['places']:
            if p['counties']:
                self.assertEqual(p['seat'], p['counties'][0]['name'], p['name'])

    def test_no_state_tail_leaked_into_a_note(self):
        for p in self.data['places']:
            for c in p['counties']:
                self.assertNotIn('刺史部', c['note'], f"{p['name']}/{c['name']}")


if __name__ == '__main__':
    unittest.main()
