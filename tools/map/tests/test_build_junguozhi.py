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


class TestHenanYinBoundaryRegression(unittest.TestCase):
    """河南尹(수도) 경계 회귀 — 인라인 픽스처, data/chgis-source/** 없이도 CI 에서
    항상 돈다. ctext.org 원문 셀은 縣/註 경계와 무관하게 잘려 있어(예: 「河南周公
    時所城雒邑也」처럼 앞 縣 雒陽 註 한복판에 다음 縣 「河南」이 註도 구분자도 없이
    바로 붙는다), 雒陽 註 안의 「有前亭」「東城門名鼎門」 같은 낱말이 CHGIS 사전에
    우연히 걸리는 딴 지역 동명과 충돌하면 縣 경계로 오인돼 뒤 진짜 縣 4개(梁·熒陽
    ·菀陵·成睪 — 熒陽/成睪 는 각각 滎陽/成皋 의 ctext 원문 이체자, CHGIS 사전엔
    그 이체자가 없어 좌표 없이 CANDIDATE_REGION 으로 남는다)가 통째로 삼켜졌다.
    아래 세그먼트는 data/chgis-source/junguozhi/yi.html 의 「河南尹」 블록 원문
    셀을 그대로 옮긴 것이다(회귀 당시 실측, 2026-08 조사)."""

    SEGMENTS = [
        ('yi', '河南尹'),
        ('yi', '二十一城，永和五年戶二十萬八千四百八十六，口百一萬八百二十七。'),
        ('yi', '雒陽'),
        ('yi', '周時號成周。有狄泉，在城中。有唐聚。有上程聚。有士鄉聚。有褚氏聚。'
               '有榮錡澗。有前亭。有圉鄉。有大解城。河南周公時所城雒邑也，春秋時謂'
               '之王城。東城門名鼎門，北城門名乾祭。又有甘城，有蒯鄉。梁故國，伯翳'
               '後。有霍陽山。有注城。熒陽有鴻溝水。有廣武城。有总亭，总叔國。有隴'
               '城。有薄亭。有敖亭。有費澤。卷'),
        ('yi', '有長城，經陽武到密。有垣雝城，或曰古衡雍。有扈城亭。原武陽武中牟'),
        ('yi', '有圃田澤。有清口水。有管城。有曲遇聚。有蔡亭。開封菀陵有棐林。有制'
               '澤。有瑣侯亭。平陰穀城瀍水出。有函谷關。緱氏有鄔聚。有轘轅關。鞏'),
        ('yi', '有尋谷水。有東訾聚，今名訾城。有坎埳聚。有黃亭。有湟水。有明谿泉。'
               '成睪有旃然水。有瓶丘聚。有漫水。有汜水。京密'),
        ('yi', '有大騩山。有梅山。有陘山。新城'),
        ('yi', '有高都城。有廣成聚。有鄤聚，古鄤氏，今名蠻中。匽師'),
        ('yi', '有尸鄉，春秋時曰尸氏。新鄭詩鄭國，祝融墟。平'),
    ]

    # 실제 CHGIS v6_time_cnty 좌표(RESOLVED_POINT 되는 16개만). 熒陽·菀陵·成睪·
    # 匽師·梁은 실제 CHGIS 사전에도 이체자/결손으로 없다 — 일부러 안 넣는다
    # (CANDIDATE_REGION 이 되는 게 맞다). 前亭·門名·鼎·東城 도 일부러 안 넣는다 —
    # 넣으면 이 테스트가 회귀 자체를 증명 못 한다.
    CNTY_XY = {
        '雒陽': [(112.5963, 34.73157)], '河南': [(112.41652, 34.67085)],
        '卷': [(113.78767, 35.03897)], '原武': [(113.96297, 35.05195)],
        '陽武': [(114.0983, 34.98309)], '中牟': [(114.05868, 34.73158)],
        '開封': [(114.29403, 34.59517)], '平陰': [(112.64783, 34.81961)],
        '穀城': [(112.33608, 34.70347)], '緱氏': [(112.84392, 34.58003)],
        '鞏': [(112.92985, 34.76676)], '京': [(113.43846, 34.71545)],
        '密': [(113.49292, 34.44397)], '新城': [(108.85463, 34.41219)],
        '新鄭': [(113.71909, 34.39732)], '平': [(112.94898, 34.81825)],
    }
    PREF_XY = {'河南': [(112.5963, 34.73157)]}   # 앵커 — 雒陽 좌표로 근사

    @classmethod
    def setUpClass(cls):
        import json
        import tempfile

        orig = dict(read_segments=bj.read_segments, chgis_points=bj.chgis_points,
                    county_lexicon=bj.county_lexicon, OUT=bj.OUT)
        bj.read_segments = lambda: list(cls.SEGMENTS)
        bj.chgis_points = lambda layer, field='NAME_FT': (
            dict(cls.CNTY_XY) if layer == 'cnty' else dict(cls.PREF_XY))
        bj.county_lexicon = lambda: frozenset()
        fd, path = tempfile.mkstemp(suffix='.json')
        import os as _os
        _os.close(fd)
        bj.OUT = path
        try:
            bj.main()
            cls.data = json.load(open(path, encoding='utf-8'))
        finally:
            bj.read_segments = orig['read_segments']
            bj.chgis_points = orig['chgis_points']
            bj.county_lexicon = orig['county_lexicon']
            bj.OUT = orig['OUT']
            _os.remove(path)
        cls.henan = next(p for p in cls.data['places'] if p['name'] == '河南尹')

    def test_checksum_passes_with_the_correct_21(self):
        self.assertEqual(self.henan['checksum'], 'PASS')
        self.assertEqual(self.henan['cities'], 21)
        self.assertEqual(len(self.henan['counties']), 21)

    def test_real_counties_are_not_swallowed_by_luoyang_note(self):
        names = {c['name'] for c in self.henan['counties']}
        for real in ('梁', '熒陽', '菀陵', '成睪'):
            self.assertIn(real, names, f'{real} 이 雒陽 註에 삼켜져 사라졌다')

    def test_note_fragments_do_not_become_fake_counties(self):
        names = {c['name'] for c in self.henan['counties']}
        for fake in ('前亭', '門名', '東城', '鼎'):
            self.assertNotIn(fake, names, f'{fake} 은 雒陽/河南 註 안의 조각이지 縣이 아니다')


class TestYouFuFengBoundaryRegression(unittest.TestCase):
    """右扶風 경계 회귀 — 인라인 픽스처, data/chgis-source/** 없이도 CI 에서 항상
    돈다. 郡治 縣 「槐里」가 CHGIS cnty_xy 에 좌표가 없어(오늘날 陝西 興平 일대,
    CHGIS V6 縣급 레이어 결손) PASS-A q=0 직접매치가 실패하고, 그 뒤를 잇는 縣
    자신의 註(後漢書 卷109 郡國一 원문 〖槐里〗周曰犬丘，高帝改。data/corpus/
    hhs-109.txt:224)가 NOTE_START 재동기화 진입점을 잃어 run_end/DP 가 사전
    정보 없는 run 을 2자씩 그리디로 짝지어 가짜 縣 4개(周曰‧犬丘‧，高‧帝改)를
    냈다. 같은 클래스 회귀가 「武功」 註(卷109 원문 〖武功〗永平八年復。有太一山，
    本終南。垂山，本敦物。有斜谷。)의 「垂山，本敦物」·「永平八年復」 두 절에도
    있다 — 「垂」「永平」어느 것도 CHGIS 근접매치가 없어 같은 DP fallback 이
    가짜 縣 「垂」‧「永平」‧「八年」‧「復」 을 냈고, PASS-B gap 예산이 이 가짜
    조각들에 밀려 진짜 縣 汧‧渝麋‧雍‧美陽 4개가 통째로 빠졌다(15城 checksum
    은 가짜 개수 = 누락 개수라 우연히 PASS 로 통과했다). 아래 세그먼트는
    data/chgis-source/junguozhi/yi.html 의 「右扶風」 블록 원문 셀을 그대로
    옮긴 것이다(회귀 당시 실측, 2026-08 조사)."""

    SEGMENTS = [
        ('yi', '右扶風'),
        ('yi', '十五城，戶萬七千三百五十二，口九萬三千九十一。'),
        ('yi', '槐里'),
        ('yi', '周曰犬丘，高帝改。安陵平陵'),
        ('yi', '茂陵'),
        ('yi', '鄠'),
        ('yi', '豐'),
        ('yi', '水出。有甘亭。郿有邰亭。武功永平八年復。有太一山，本終南。垂山，'
               '本敦物。有斜谷。陳倉汧'),
        ('yi', '有吳嶽山，本名汧，汧水出。有回城，名回中。渝麋侯國。雍'),
        ('yi', '有鐵。栒邑有豳鄉。美陽有岐山，有周城。漆有漆水。有鐵。杜陽永和'
               '二年復。'),
    ]

    # 실제 CHGIS v6_time_cnty 좌표 중 이 회귀와 무관한 것만 넣는다. 槐里(郡治
    # 자신, CHGIS 결손) · 汧‧渝麋‧雍‧美陽(다른 사유로 CANDIDATE_REGION 이 맞는
    # 진짜 縣) · 周曰‧犬丘‧，高‧帝改‧垂‧永平‧八年‧復(전부 가짜) 은 일부러 안
    # 넣는다 — 넣으면 이 테스트가 회귀 자체를 증명 못 한다.
    CNTY_XY = {
        '安陵': [(108.6, 34.4)],  # CHGIS 결손이라 실측 아님 — CANDIDATE_REGION 확인용 최소값
        '平陵': [(108.52646, 34.37203)], '茂陵': [(108.58281, 34.34693)],
        '鄠': [(108.6039, 34.11182)], '郿': [(107.83877, 34.29156)],
        '武功': [(108.10019, 34.25887)], '陳倉': [(107.27861, 34.36926)],
        '栒邑': [(108.37369, 35.21846)], '漆': [(108.07799, 35.03966)],
        '杜陽': [(107.55473, 34.88571)],
    }
    PREF_XY = {'右扶': [(108.47778, 34.27472)]}  # 앵커 — 실제 산출물 앵커값

    @classmethod
    def setUpClass(cls):
        import json
        import tempfile
        import os as _os

        orig = dict(read_segments=bj.read_segments, chgis_points=bj.chgis_points,
                    county_lexicon=bj.county_lexicon, OUT=bj.OUT)
        bj.read_segments = lambda: list(cls.SEGMENTS)
        bj.chgis_points = lambda layer, field='NAME_FT': (
            dict(cls.CNTY_XY) if layer == 'cnty' else dict(cls.PREF_XY))
        bj.county_lexicon = lambda: frozenset()
        fd, path = tempfile.mkstemp(suffix='.json')
        _os.close(fd)
        bj.OUT = path
        try:
            bj.main()
            cls.data = json.load(open(path, encoding='utf-8'))
        finally:
            bj.read_segments = orig['read_segments']
            bj.chgis_points = orig['chgis_points']
            bj.county_lexicon = orig['county_lexicon']
            bj.OUT = orig['OUT']
            _os.remove(path)
        cls.youfufeng = next(p for p in cls.data['places'] if p['name'] == '右扶風')

    def test_checksum_passes_with_the_correct_15(self):
        self.assertEqual(self.youfufeng['checksum'], 'PASS')
        self.assertEqual(self.youfufeng['cities'], 15)
        self.assertEqual(len(self.youfufeng['counties']), 15)

    def test_real_counties_are_not_swallowed(self):
        names = {c['name'] for c in self.youfufeng['counties']}
        for real in ('槐里', '安陵', '平陵', '茂陵', '鄠', '郿', '武功', '陳倉',
                     '栒邑', '漆', '杜陽'):
            self.assertIn(real, names, f'{real} 이 註 파편에 밀려 사라졌다')

    def test_note_fragments_do_not_become_fake_counties(self):
        names = {c['name'] for c in self.youfufeng['counties']}
        for fake in ('周曰', '犬丘', '，高', '帝改', '垂', '永平', '八年', '復'):
            self.assertNotIn(fake, names,
                              f'{fake} 은 槐里/武功 註 안의 조각이지 縣이 아니다')

    def test_huaili_note_is_reassembled_whole(self):
        huaili = next(c for c in self.youfufeng['counties'] if c['name'] == '槐里')
        self.assertEqual(huaili['note'], '周曰犬丘，高帝改')
        self.assertEqual(huaili['resolution'], 'CANDIDATE_REGION')

    def test_wugong_note_is_not_truncated_by_stolen_gap_slots(self):
        wugong = next(c for c in self.youfufeng['counties'] if c['name'] == '武功')
        self.assertEqual(wugong['note'], '永平八年復')


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
