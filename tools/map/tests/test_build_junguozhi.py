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

    def test_implicit_leading_one_before_wan(self):
        # 十/百/千 앞의 「一」 생략은 이미 처리되지만(「十四」=14), 「萬」 앞의
        # 「一」 생략은 별도 처리가 없으면 만 단위 전체가 사라진다. 酒泉郡 원문
        # 「戶萬二千七百六」(data/chgis-source/junguozhi/si.html)= 12706,
        # 上谷郡 원문 「戶萬三百五十二」= 10352 — 둘 다 앞에 「一」 없이 「萬」이
        # 곧장 온다.
        self.assertEqual(bj.cn_int('萬二千七百六'), 12706)
        self.assertEqual(bj.cn_int('萬三百五十二'), 10352)
        self.assertEqual(bj.cn_int('萬'), 10000)
        # 회귀 방지: 앞자리가 이미 있는 경우(생략 아님)는 그대로여야 한다.
        self.assertEqual(bj.cn_int('二萬三千'), 23000)


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

    def _counties(self, jun):
        return next(p for p in self.data['places'] if p['name'] == jun)['counties']

    def test_note_internal_landmark_does_not_resync_as_fake_county(self):
        # 「有X」 註 안 지명이 縣 경계로 오인되는 사고 一族 — 개별 CONFIRMED_*
        # 화이트리스트가 아니라 일반 규칙으로 막혀야 한다(有 로 시작하는 절은
        # NOTE_START 재동기화 대상에서 아예 제외). 아래 셋은 실측(수정 전
        # data/map/junguozhi.json)에서 실제로 가짜 縣으로 새 나온 사례다,
        # per-name 예외를 하나도 걸지 않은 채로.
        #
        # 陳留郡 外黃 註 원문(data/corpus/hhs-111.txt:27)
        # 「有葵丘聚，齊桓公會此，城中有曲棘里。有繁陽城。」— 「有繁陽城」의
        # 「繁陽」이 딴 郡(汝南郡 등)의 동명 CHGIS 점과 우연히 맞아 재동기화,
        # 陳留郡에 가짜 「繁陽」 縣(note='城')이 생겼다.
        self.assertNotIn('繁陽', {c['name'] for c in self._counties('陳留郡')},
                          '外黃 註 「有繁陽城」의 「繁陽」이 陳留郡에 가짜 縣으로 새 나왔다')
        # 泰山郡 南城 註 원문(data/chgis-source/junguozhi/san.html:860) 「有東陽城」
        # 의 「東陽」이 가짜 「東陽」 縣(note='城')으로 새 나왔다.
        self.assertNotIn('東陽', {c['name'] for c in self._counties('泰山郡')},
                          '南城 註 「有東陽城」의 「東陽」이 泰山郡에 가짜 縣으로 새 나왔다')
        # 주의: 下邳國 「葛嶧」/「山，」은 이 부류가 아니다 — 이 파이프라인이
        # 실제로 읽는 data/chgis-source/junguozhi/san.html:1088 원문은
        # 「本屬東海。葛嶧山，本嶧陽山。有鐵。」로 「有」가 없다(위키문헌 계열
        # hhs-111.txt 만 「有葛嶧山」으로 되어 있다 — 底本이 다르다). 「有」로
        # 시작하지 않는 절이라 이 규칙의 적용 대상이 아니다 — NOTE_START 자체가
        # 안 걸리는 run-splitting 결손이라 별도 항목(涅陽류)이다.

    def test_reign_era_restoration_note_does_not_become_fake_counties(self):
        # 「…年復/年置/年徙/年分…」류 연호 註는 縣名 앞뒤에 구두점·NOTE_START
        # 트리거 없이 바로 붙기도 한다(上谷郡 원문 data/chgis-source/junguozhi/
        # wu.html 「沮陽潘永元十一年復。甯…」— 「潘」 뒤에 아무 표시 없이 바로
        # 「永元十一年復」이 온다). 이 절이 縣 경계 탐색으로 떨어지면 DP 가
        # 「永元」「十一」「年復」 같은 가짜 縣으로 쪼갠다. 개별 연호 이름
        # (永元·永平·建初…) 화이트리스트가 아니라 「…年(復|置|省|并|罷|徙|屬|
        # 分|更)」 구조 자체로 잡는다 — 실제 縣名 중 「年」이 든 것은 cnty_xy
        # 직접매치로 이 검사 전에 이미 소비되는 廣年·萬年 둘뿐이다.
        for jun in ('上谷郡', '左馮翊', '安定郡', '沛國', '勃海郡', '東海郡',
                    '琅邪國', '零陵郡', '武陵郡', '南郡', '交趾郡', '豫章郡'):
            names = {c['name'] for c in self._counties(jun)}
            for fake in ('永元', '永平', '建初', '延光', '永和', '建武', '陽嘉', '建康'):
                self.assertNotIn(fake, names,
                                  f'{jun}에 연호 註 조각이 가짜 縣으로 새 나왔다: {fake}')

    def test_household_only_header_does_not_leak_into_first_county(self):
        # 酒泉郡 원문(data/chgis-source/junguozhi/si.html)은 「九城，戶萬二千
        # 七百六。福祿…」로 口(인구) 없이 戶(호구)만 있다. HU 정규식이 「戶X，
        # 口Y。」 꼴만 받으면 이 郡은 통째로 안 걸려 「戶萬二千七百六。」가
        # rest 맨 앞에 그대로 남고 縣 경계 탐색에 떨어져 「戶萬」「二千」「七」
        # 「百六」 같은 가짜 縣으로 쪼개진다.
        names = {c['name'] for c in self._counties('酒泉郡')}
        for fake in ('戶萬', '二千', '七', '百六'):
            self.assertNotIn(fake, names, f'酒泉郡에 戶口 註 조각이 가짜 縣으로 새 나왔다: {fake}')
        self.assertIn('福祿', names)


class TestNanyangYuyangNoteReferenceRegression(unittest.TestCase):
    """南陽郡 育陽 註 내부 지명 참조 회귀 — 인라인 픽스처, data/chgis-source/**
    없이도 CI 에서 항상 돈다. 育陽 註 「有小長安。」(後漢書 卷112 郡國四 원문,
    data/corpus/hhs-112.txt:216 — 「育陽，邑。有小長安，{{*|漢軍為甄阜所破處。}}
    有東陽聚。」)의 「小長安」은 育陽 안의 별칭 지명이지 縣이 아니다. 그런데
    「長安」이 cnty_xy 에 京兆尹 도성 長安으로 실존하고 南陽郡 앵커에서
    363.8km 로 MAX_KM(400km) 문턱 안에 들어 near() 를 통과해, NOTE_START
    재동기화가 이걸 진짜 縣으로 오인했다(河南尹 「有前亭」·「有高都」와 같은
    클래스 — CONFIRMED_NOTE_INTERNAL_REFERENCE 로 봉쇄). 아래 세그먼트는
    data/chgis-source/junguozhi/si.html 의 「南陽郡」 블록 원문 셀을 그대로
    옮긴 것이다(회귀 당시 실측, 2026-08 조사)."""

    # data/chgis-source/junguozhi/si.html 「南陽郡」 블록 원문 셀 전체(37城
    # 전량, 회귀 당시 실측). 涅陽‧陰‧酇‧鄧 병합(별개 미해결 결손)도 그대로
    # 재현된다 — 실제 산출물도 checksum PASS(37=37) 로 통과하는 채 이 결손을
    # 안고 있어, 부분 블록으로 잘라내면 city 수가 안 맞아 checksum FAIL 로
    # main() 이 죽는다. 育陽 「長安」 회귀만 이 테스트의 대상이다.
    SEGMENTS = [
        ('si', '右青州刺史部，郡、國六，縣六十五。南陽郡'),
        ('si', '三十七城，戶五十二萬八千五百五十一，口二百四十三萬九千六百一十八。'),
        ('si', '宛'),
        ('si', '。本申伯國。有南就聚。有瓜里津。有夕陽聚。有東武亭。冠軍邑。葉有長山，曰方城。有卷城。新野'),
        ('si', '有東鄉，故新都。有黃郵聚。章陵故舂陵，世祖更名。有上唐鄉。西鄂雉'),
        ('si', '魯陽'),
        ('si', '有魯山。有牛蘭累亭。犨堵陽博望舞陰邑。比陽復陽侯國。有杏聚。平氏桐柏大復山，淮水出。有宜秋聚。棘陽'),
        ('si', '有藍鄉。有黃淳聚。湖陽邑。隨西有斷蛇丘。育陽邑。有小長安。有東陽聚。涅陽陰酇鄧'),
        ('si', '有鄾聚。山都侯國。酈侯國。穰'),
        ('si', '朝陽'),
        ('si', '蔡'),
        ('si', '陽'),
        ('si', '侯國。安眾侯國。筑陽侯國。有涉都鄉。武當有和成聚。順陽侯國，故博山。有須聚。成都襄鄉南鄉'),
        ('si', '丹水'),
        ('si', '故屬弘農。有章密鄉。有三戶亭。析故屬弘農，故楚白羽邑。有武關，在縣西。有豐鄉城。'),
    ]

    # 실제 CHGIS v6_time_cnty 좌표(회귀 재현에 필요한 것만). 「長安」은 京兆尹
    # 도성 좌표를 그대로 넣는다 — 넣어야 near() 가 통과해 봉쇄 이전 상태를
    # 재현한다(안 넣으면 이 테스트가 회귀 자체를 증명 못 한다). 筑陽‧順陽‧丹水
    # ‧涅陽 은 실제 cnty_xy 에도 없다(CHGIS 결손) — 일부러 안 넣는다.
    CNTY_XY = {
        '宛': [(112.53547, 33.00168)], '冠軍': [(111.9222, 32.80205)],
        '葉': [(113.30023, 33.50015)], '新野': [(112.35843, 32.52487)],
        '章陵': [(112.77004, 31.99921)], '西鄂': [(112.61277, 33.17732)],
        '雉': [(112.65376, 33.34375)], '魯陽': [(112.90194, 33.73712)],
        '犨': [(113.14788, 33.65464)], '堵陽': [(113.02336, 33.25974)],
        '博望': [(112.71764, 33.1689)], '舞陰': [(113.19347, 32.95993)],
        '比陽': [(113.31919, 32.72317)], '平氏': [(113.07872, 32.53161)],
        '棘陽': [(112.51661, 32.47629)], '湖陽': [(112.745, 32.40853)],
        '隨': [(113.36982, 31.71511)], '育陽': [(112.4143, 32.78379)],
        '山都': [(111.78699, 32.12441), (111.80595, 32.13685)],
        '酈': [(111.79439, 33.12248)], '穰': [(112.08096, 32.68483)],
        '朝陽': [(112.32459, 32.43506), (117.52655, 37.0117)],
        '蔡陽': [(112.51083, 32.08644)], '安眾': [(112.28225, 32.79029)],
        '武當': [(111.16658, 32.68161)], '成都': [(104.078, 30.65038)],
        '襄鄉': [(112.87054, 32.22641)], '南鄉': [(107.9833, 32.32573)],
        '析': [(111.47452, 33.30071)], '陰': [(111.62382, 32.46271)],
        '酇': [(116.10895, 33.95912)], '鄧': [(112.09982, 32.08813)],
        '長安': [(108.93719, 34.31799)],   # 京兆尹 도성 — 育陽과 무관, 회귀 원인
    }
    PREF_XY = {'南陽': [(112.535744, 33.001573)]}  # 앵커 — 실제 산출물 앵커값

    @classmethod
    def setUpClass(cls):
        import json
        import tempfile

        orig = dict(read_segments=bj.read_segments, chgis_points=bj.chgis_points,
                    county_lexicon=bj.county_lexicon, OUT=bj.OUT)
        bj.read_segments = lambda: list(cls.SEGMENTS)
        bj.chgis_points = lambda layer, field='NAME_FT': (
            dict(cls.CNTY_XY) if layer == 'cnty' else dict(cls.PREF_XY))
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
        cls.nanyang = next(p for p in cls.data['places'] if p['name'] == '南陽郡')

    def test_changan_note_reference_does_not_become_a_fake_county(self):
        names = {c['name'] for c in self.nanyang['counties']}
        self.assertNotIn('長安', names, '「有小長安」 안의 「長安」 이 育陽 註에서 縣으로 오인됐다')

    def test_yuyang_is_still_resolved_correctly(self):
        yuyang = next(c for c in self.nanyang['counties'] if c['name'] == '育陽')
        self.assertEqual(yuyang['resolution'], 'RESOLVED_POINT')
        self.assertEqual(yuyang['note'], '邑')

    def test_real_counties_after_yuyang_are_not_swallowed(self):
        names = {c['name'] for c in self.nanyang['counties']}
        for real in ('山都', '酈', '穰'):
            self.assertIn(real, names, f'{real} 이 育陽 註 오인 파편에 밀려 사라졌다')


class TestJunHitTakesPriorityOverPartialCntyMatch(unittest.TestCase):
    """확증된 (郡, 縣名) 은 우연한 짧은 cnty_xy 부분일치보다 먼저 봐야 한다 —
    廣漢屬國 「陰平道」의 앞 2자 「陰平」만 cnty_xy 에 좌표로 있어, hit 체크를
    먼저 보면 「陰平」만 잘라먹고 「道」가 뒤 縣과 섞여 가짜 縣이 된다."""

    def test_confirmed_full_name_wins_over_shorter_cnty_hit(self):
        self.assertIn(('廣漢屬國', '陰平道'), bj.CONFIRMED_JUN_NAMES_NO_CHGIS)
        self.assertIn(('廣漢屬國', '甸氐道'), bj.CONFIRMED_JUN_NAMES_NO_CHGIS)
        self.assertIn(('廣漢屬國', '剛氐道'), bj.CONFIRMED_JUN_NAMES_NO_CHGIS)


class TestSplitUnresolvedRunJunWhitelist(unittest.TestCase):
    """張掖屬國 「候官左騎千人司馬官千人官」— 사전 적중이 하나도 없는 run 에서
    DP 는 2자 조각 보너스만 보고 맹목적으로 2자씩 자른다(候官‧左騎‧千人 은
    우연히 2자라 결과가 맞았지만, 3자인 司馬官‧千人官 은 司馬‧官千‧人官 세
    가짜 縣으로 쪼개졌다, 실측). jun 을 넘기면 CONFIRMED_JUN_NAMES_NO_CHGIS
    항목이 사전 적중과 같은 가중치를 받아 DP 가 정확한 경계를 고른다."""

    def test_jun_whitelisted_pieces_override_blind_pairing(self):
        run = '候官左騎千人司馬官千人官'
        pieces = bj.split_unresolved_run(run, lex=frozenset(), cnty_xy=None,
                                          anchor=None, max_km=None, jun='張掖屬國')
        self.assertEqual(pieces, ['候官', '左騎', '千人', '司馬官', '千人官'])

    def test_without_jun_falls_back_to_blind_pairing(self):
        # jun 을 안 넘기면(다른 郡 호출부) 예전처럼 2자 그리디 그대로다 —
        # 이 whitelist 가 다른 郡에 새는 게 아님을 확인한다.
        run = '候官左騎千人司馬官千人官'
        pieces = bj.split_unresolved_run(run, lex=frozenset(), cnty_xy=None,
                                          anchor=None, max_km=None)
        self.assertNotEqual(pieces, ['候官', '左騎', '千人', '司馬官', '千人官'])


class TestLiaodongShuguoNoCountBoundaryRegression(unittest.TestCase):
    """遼東屬國류 屬國都尉는 「몇十몇城」선언이 없다(cities=None). 이전에는
    PASS-B gap 이 0으로 고정돼 잔여 縣이 통째로 사라졌고(張掖屬國‧遼東屬國
    0개), b['rest'] 계산도 DIST(雒陽거리) 절 끝을 안 봐서 「雒陽東北三千
    二百六十里」가 그대로 남아 縣 경계 탐색에 떨어져 가짜 縣(雒陽‧東北‧三‧
    千二‧百六‧十里)이 됐다. 아래는 data/chgis-source/junguozhi/wu.html 「遼東
    屬國」 블록 원문 셀을 그대로 옮긴 것이다."""

    SEGMENTS = [
        ('wu', '遼東屬國'),
        ('wu', '雒陽東'),
        ('wu', '北三千二百六十里。'),
        ('wu', '昌遼'),
        ('wu', '故天遼，屬遼西。賓徒故屬遼西。徒河故屬遼西。無慮有醫無慮山。'
               '險瀆房'),
        ('wu', '右幽州刺史部，郡、國十一，縣、邑、侯國九十。南海郡'),
        ('wu', '一城，戶七萬一千四百七十七，口二十五萬二百八十二。'),
        ('wu', '番禺'),
    ]

    CNTY_XY = {
        '番禺': [(113.26436, 23.12908)],
    }
    PREF_XY = {'遼東屬': [(123.5, 41.5)], '南海': [(113.26436, 23.12908)]}

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
        cls.liaodong = next(p for p in cls.data['places'] if p['name'] == '遼東屬國')

    def test_distance_from_luoyang_is_extracted(self):
        self.assertEqual(self.liaodong['distanceFromLuoyang'], 3260)
        self.assertEqual(self.liaodong['direction'], '東北')

    def test_no_count_jun_still_recovers_all_six_counties(self):
        names = [c['name'] for c in self.liaodong['counties']]
        self.assertEqual(names, ['昌遼', '賓徒', '徒河', '無慮', '險瀆', '房'])

    def test_distance_clause_does_not_leak_into_fake_counties(self):
        names = {c['name'] for c in self.liaodong['counties']}
        for fake in ('雒陽', '東北', '三', '千二', '百六', '十里'):
            self.assertNotIn(fake, names,
                              f'{fake} 은 雒陽거리 절 파편이지 縣이 아니다')


class TestZhangyeShuguoJunHitLongestMatchRegression(unittest.TestCase):
    """張掖屬國의 마지막 縣 「千人官」이 「千人」(#247)과 이름이 겹친다.
    CONFIRMED_JUN_NAMES_NO_CHGIS 에 두 이름이 다 확증돼 있는데, jun_hit
    조회가 frozenset 을 그냥 next() 로 훑으면 순회 순서가 안 보장돼 짧은
    「千人」이 먼저 걸려 3번째 글자 「官」이 떨어져 나가 가짜 1자 縣이 됐다
    (개수는 5로 맞아떨어져 城數 체크섬은 통과했다 — 河南尹 21=21 과 같은
    「개수는 맞는데 내용이 틀린」 상쇄 사례). data/chgis-source/junguozhi/
    wu.html 「張掖屬國」 블록 원문 셀(#242-250, 候官/左騎/千人/司馬官/千人官
    5개 각각 별도 셀) 그대로."""

    SEGMENTS = [
        ('wu', '張掖屬國'),
        ('wu', '戶四千'),
        ('wu', '六百五十六，口萬六千九百五十二。'),
        ('wu', '候官'),
        ('wu', '左騎'),
        ('wu', '千人'),
        ('wu', '司馬官'),
        ('wu', '千人官。'),
        ('wu', '張掖居延屬國'),
        ('wu', '戶一千五百六十，口四千七百三十三。'),
        ('wu', '居延'),
    ]

    CNTY_XY = {}
    PREF_XY = {}

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
        cls.zhangye = next(p for p in cls.data['places'] if p['name'] == '張掖屬國')

    def test_all_five_county_names_exact(self):
        names = [c['name'] for c in self.zhangye['counties']]
        self.assertEqual(names, ['候官', '左騎', '千人', '司馬官', '千人官'])

    def test_no_stray_guan_fragment(self):
        names = {c['name'] for c in self.zhangye['counties']}
        self.assertNotIn('官', names,
                          '「千人官」의 3번째 글자만 떨어져 나온 가짜 1자 縣')

    def test_notes_are_clean(self):
        for c in self.zhangye['counties']:
            self.assertEqual(c['note'], '', f"{c['name']} 의 note 가 비어있지 않다")


class TestXuantuJunPureNoteFragmentRegression(unittest.TestCase):
    """玄菟郡 「高句驪」 註 「遼山，遼水出。」의 NOTE_START_RUN 「水出」 트리거
    직전 잔여 「遼山，」가 縣名을 하나도 안 담고 있는데도 split_unresolved_run
    이 뭐라도 잘라야 해서 「遼」+「山，」 두 가짜 1자/2자 縣을 냈다. 六城 선언과
    가짜 2개가 우연히 맞아떨어져(가짜 2개가 진짜 候城‧遼陽 두 縣을 城數
    트리밍에서 밀어냄) 체크섬만으로는 못 잡는 「河南尹 21=21」류 상쇄다.
    data/chgis-source/junguozhi/wu.html 「玄菟郡」 블록 원문 셀(#441-447)
    그대로."""

    SEGMENTS = [
        ('wu', '玄菟郡'),
        ('wu', '六城，戶一千五百九十四，口四萬三千一百六十三。'),
        ('wu', '高句驪'),
        ('wu', '遼山，遼水出。西蓋鳥上殷台'),
        ('wu', '高顯'),
        ('wu', '故屬遼東。候城'),
        ('wu', '故屬遼東。遼陽故屬遼東。'),
        ('wu', '樂浪郡'),
        ('wu', '一城，戶六萬一千四百九十二，口二十五萬七千五十。'),  # 종결용 다음 郡, 縣 1개만 제공
        ('wu', '朝鮮'),
    ]

    CNTY_XY = {'朝鮮': [(125.75, 39.02)]}
    PREF_XY = {}

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
        cls.xuantu = next(p for p in cls.data['places'] if p['name'] == '玄菟郡')

    def test_all_six_county_names_exact(self):
        names = [c['name'] for c in self.xuantu['counties']]
        self.assertEqual(names, ['高句驪', '西蓋鳥', '上殷台', '高顯', '候城', '遼陽'])

    def test_no_fake_fragments_from_note(self):
        names = {c['name'] for c in self.xuantu['counties']}
        for fake in ('遼', '山，', '高句', '驪遼'):
            self.assertNotIn(fake, names,
                              f'{fake} 는 「遼山，遼水出」 註 파편이지 縣이 아니다')

    def test_gaogouli_note_carries_full_clause(self):
        gaogouli = next(c for c in self.xuantu['counties'] if c['name'] == '高句驪')
        self.assertEqual(gaogouli['note'], '遼山，遼水出')


class TestYuexiJunGluedTailRegression(unittest.TestCase):
    """越巂郡 十四城 중 뒤 절반이 ctext 원문 셀 하나에 구두점 없이 통째로
    붙어 있어 4중으로 어긋났다: ① 邛都 註 「南山出銅。」의 「南山」이 「出」
    트리거 앞 잔여로 남아 가짜 縣이 됨(玄菟郡 「遼山，」와 같은 클래스).
    ② 「靈關道」(3자)+「臺登」(2자)가 맹목 DP 로 「靈關」「道臺」「登」
    (2‧2‧1) 로 갈림. ③~④ 「闡」(1자)+「蘇谀」「大莋」「莋秦」「姑復」
    (2자×4) 9자가 「蘇谀」「大莋」「莋」「莋秦」「姑復」(2‧2‧1‧2‧2) 로
    갈려 「闡」자체가 없어지고 「姑復」끝의 「莋秦姑復」가 마지막 가짜 縣의
    註로 통째 삼켜졌다(실측 — 개수는 郡 선언 十四城과 우연히 맞아떨어짐).
    data/chgis-source/junguozhi/wu.html 「越巂郡」 블록 원문 셀(#81-93)
    그대로."""

    SEGMENTS = [
        ('wu', '越巂郡'),
        ('wu', '十四城，戶十三萬一百二十，口六十二萬三千四百一十八。'),
        ('wu', '邛都'),
        ('wu', '南山出銅。遂久靈關道臺登出鐵。青蛉有禺同山，俗謂有金馬碧雞。卑水'),
        ('wu', '三縫'),
        ('wu', '會'),
        ('wu', '無'),
        ('wu', '出鐵。定莋'),
        ('wu', '闡'),
        ('wu', '蘇谀'),
        ('wu', '大莋'),
        ('wu', '莋秦'),
        ('wu', '姑復'),
        ('wu', '益州郡'),
        ('wu', '一城，戶二萬九千三十六，口十一萬八百二。'),  # 종결용 다음 郡, 縣 1개만 제공
        ('wu', '滇池'),
    ]

    CNTY_XY = {
        '邛都': [(102.274129, 27.871663)],
        '遂久': [(100.23212, 26.87603)],
        '青蛉': [(101.32531, 25.72573)],
        '滇池': [(102.6, 24.8)],
    }
    PREF_XY = {}

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
        cls.yuexi = next(p for p in cls.data['places'] if p['name'] == '越巂郡')

    def test_all_fourteen_county_names_exact(self):
        names = [c['name'] for c in self.yuexi['counties']]
        self.assertEqual(names, [
            '邛都', '遂久', '靈關道', '臺登', '青蛉', '卑水', '三縫',
            '會無', '定莋', '闡', '蘇谀', '大莋', '莋秦', '姑復',
        ])

    def test_no_fake_fragments(self):
        names = {c['name'] for c in self.yuexi['counties']}
        for fake in ('南山', '靈關', '道臺', '登', '闡蘇', '谀大', '莋'):
            self.assertNotIn(fake, names, f'{fake} 는 구두점 없는 원문 파편이지 縣이 아니다')

    def test_qiongdu_note_carries_full_clause(self):
        qiongdu = next(c for c in self.yuexi['counties'] if c['name'] == '邛都')
        self.assertEqual(qiongdu['note'], '南山出銅')

    def test_taideng_note_is_iron(self):
        taideng = next(c for c in self.yuexi['counties'] if c['name'] == '臺登')
        self.assertEqual(taideng['note'], '出鐵')


class TestWuduJunSplitCellAndGreedyNoteRegression(unittest.TestCase):
    """武都郡 七城 중 두 자리가 서로 다른 원인으로 어긋났다:
    ① 「武都道」(3자)가 ctext 원문 셀에서 「武都」「道」로 두 셀에 걸쳐
    쪼개진 채(cell #154-155) 「下辨武都道上祿」7자가 맹목 DP 로 「下辨」
    「武都」「道」「上祿」(2‧2‧1‧2) 로 갈린다(越巂郡 「靈關道」와 같은
    3자-조각 DP 점수 열세 클래스). 「故道」도 같은 방식으로 두 셀(#157-158)
    에 걸쳐 있지만 CHGIS 직접매치라 원래도 안 깨졌다.
    ② 「沮」는 CHGIS 에 좌표가 실제로 있는데도(cnty_xy 조회 확인) 바로
    앞 「河池」whitelist 매치가 after_residual=True 를 세워 1자 직접-hit
    경로(lens=(4,3,2), 1자 제외)를 꺼버리고, 그 자리에서 註 「沔水出東狼谷」
    의 NOTE_START_RUN 「[1-3자]水出」트리거가 「沮」자신을 그 1-3자 접두로
    삼켜 縣 목록에서 통째로 사라진다(玄菟郡 「遼山，」·越巂郡 「南山」과는
    반대로, 여기는 진짜 CHGIS 점이 있는 縣이 삼켜지는 경우).
    data/chgis-source/junguozhi/wu.html 「武都郡」 블록 원문 셀(#151-161)
    그대로."""

    SEGMENTS = [
        ('wu', '武都郡'),
        ('wu', '七城，戶二萬一百二，口八萬一千七百二十八。'),
        ('wu', '下辨'),
        ('wu', '武都'),
        ('wu', '道'),
        ('wu', '上祿'),
        ('wu', '故'),
        ('wu', '道'),
        ('wu', '河池'),
        ('wu', '沮'),
        ('wu', '沔水出東狼谷。羌道'),
        ('wu', '金城郡'),
        ('wu', '一城，戶三千八百五十八，口萬八千九百四十七。'),  # 종결용 다음 郡, 縣 1개만 제공
        ('wu', '允吾'),
    ]

    CNTY_XY = {
        '故道': [(107.08187, 34.30492)],
        '沮': [(106.44959, 33.27274)],
        '允吾': [(102.86, 36.17)],
    }
    PREF_XY = {'武都': [(105.093816, 33.96348)]}

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
        cls.wudu = next(p for p in cls.data['places'] if p['name'] == '武都郡')

    def test_all_seven_county_names_exact(self):
        names = [c['name'] for c in self.wudu['counties']]
        self.assertEqual(names, [
            '下辨', '武都道', '上祿', '故道', '河池', '沮', '羌道',
        ])

    def test_no_fake_fragments(self):
        names = {c['name'] for c in self.wudu['counties']}
        for fake in ('武都', '道', '道臺'):
            self.assertNotIn(fake, names, f'{fake} 는 구두점 없는 원문 파편이지 縣이 아니다')

    def test_ju_note_carries_full_clause(self):
        ju = next(c for c in self.wudu['counties'] if c['name'] == '沮')
        self.assertEqual(ju['note'], '沔水出東狼谷')


class TestShangJunQiyuanAndQiuciShuguoRegression(unittest.TestCase):
    """上郡 十城 중 두 자리가 서로 다른 원인으로 어긋났다:
    ① 「漆垣」(2자, ctext 원문 셀 그대로 한 셀)이 앞 「白土」의 CHGIS 결손
    때문에 after_residual=True 로 넘어와 1자 직접-hit 경로(lens=(4,3,2))가
    꺼진 채 run/DP 로 떨어지고, 우연히 딴 지역(각각 387km‧392km 밖)에
    실존하는 1자 縣 「漆」「垣」이 MAX_KM=400 필터를 통과해 진짜 2자 縣을
    가로챈다(西河郡 「平定」류와 같은 클래스).
    ② 「龜茲屬國」(4자, 屬國급 이름이 그대로 一縣 명으로 등재된 사료 원문)
    은 CONFIRMED_JUN_NAMES_NO_CHGIS 로 못박아도 best_split 의 DP 조각 폭이
    1~3자로 고정돼 있어 애초에 후보가 될 수 없다 — run_end 가 그 시작
    지점에서 끊어 주지 않으면 뒤 「候官」과 통째로 「龜茲」「屬國」「候官」
    (2‧2‧2) 로 잘못 갈린다.
    data/chgis-source/junguozhi/wu.html 「上郡」 블록 원문 셀(#279-292)
    그대로."""

    SEGMENTS = [
        ('wu', '上郡'),
        ('wu', '十城，戶五千一百六十九，口二萬八千五百九十九。'),
        ('wu', '膚施'),
        ('wu', '白土'),
        ('wu', '漆垣'),
        ('wu', '奢延'),
        ('wu', '雕'),
        ('wu', '陰'),
        ('wu', '楨林'),
        ('wu', '定陽'),
        ('wu', '高奴'),
        ('wu', '龜茲屬國'),
        ('wu', '候官'),
        ('wu', '西河郡'),
        ('wu', '一城，戶五千六百九十八，口二萬八百三十八。'),  # 종결용 다음 郡, 縣 1개만 제공
        ('wu', '離石'),
    ]

    CNTY_XY = {
        '膚施': [(110.39807, 37.34969)],
        '雕陰': [(109.352, 36.16722)],
        # 딴 지역 우연 동명 1자 縣 — 진짜 「漆垣」을 가로채던 자리(실측 거리
        # 387km‧392km, MAX_KM=400 통과). whitelist 가 이 함정을 실제로
        # 우회하는지 검증하려면 재현해 둬야 한다.
        '漆': [(108.07799, 35.03966)],
        '垣': [(111.812626, 35.148973)],
        '離石': [(111.157, 37.502)],
    }
    PREF_XY = {'上': [(109.73881, 38.26548)]}

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
        cls.shang = next(p for p in cls.data['places'] if p['name'] == '上郡')

    def test_all_ten_county_names_exact(self):
        names = [c['name'] for c in self.shang['counties']]
        self.assertEqual(names, [
            '膚施', '白土', '漆垣', '奢延', '雕陰', '楨林', '定陽', '高奴',
            '龜茲屬國', '候官',
        ])

    def test_no_fake_fragments(self):
        names = {c['name'] for c in self.shang['counties']}
        for fake in ('漆', '垣', '龜茲', '屬國'):
            self.assertNotIn(fake, names, f'{fake} 는 구두점 없는 원문 파편이지 縣이 아니다')


if __name__ == '__main__':
    unittest.main()
