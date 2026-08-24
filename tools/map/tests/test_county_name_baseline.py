# -*- coding: utf-8 -*-
"""縣 **이름 집합** 회귀 기준선. 城數 체크섬이 못 보는 축이다.

城數 체크섬은 개수만 센다. 이 저장소에서 縣이 사라진 자리를 註 조각이 정확히
메워 개수가 맞아떨어진 사례가 여덟 번 나왔다(東平國 7=7, 河南尹 21=21, 陳留郡
17=17, 河內郡 18=18, 京兆尹 10=10, 下邳國 17=17, 交趾郡 12=12, 安平國 13=13 —
전부 PASS 였고 전부 틀렸다). **개수 검사가 내용을 증명한 적은 한 번도 없다.**

그래서 축을 바꾼다: 파서가 안 쓰는 입력(위키문헌 원문)에서 縣 이름 목록을 따로
뽑아 이름 집합끼리 댄다. county_names_truth.py 가 그 축이고, 아래
test_truth_module_is_independent_of_parser 가 그 독립성을 단언으로 못박는다.

**게이트가 아니라 기준선이다.** 현재 43개 郡이 어긋나 있고 그걸 당장 0으로
만들 수 없다. 대신 방향을 고정한다 — 나빠지면 FAIL, 좋아져도 FAIL(「기준선을
갱신해라」). 총계만 보면 상쇄에 속으므로 **郡별 이름 목록 그대로** 박는다.

data/corpus/** 와 data/chgis-source/** 는 ADR-LITE-039 로 gitignore 돼 있어 CI
체크아웃에는 없다 — 대조 테스트는 CI 에서 항상 skip 된다(실패 아님). 독립성
단언은 파일만 있으면 되므로 CI 에서도 돈다.
"""
import importlib.util
import json
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
TRUTH_PATH = ROOT / 'tools/map/county_names_truth.py'
BASELINE_PATH = Path(__file__).with_name('county_name_baseline.json')
OUT_PATH = ROOT / 'data/map/junguozhi.json'
CORPUS = ROOT / 'data/corpus/hhs-113.txt'
DBF = ROOT / 'data/chgis-source/v6_time_cnty_pts_utf_wgs84.dbf'

# 같은 縣인데 저본마다 글자꼴이 달라 「차이」로 세면 안 되는 쌍. 자동 판정(같은
# 길이 2자 이상 + 1글자만 다름)으로 안 걸리는 것만 손으로 적는다. 근거는 각
# 항목의 (원문 표기, 우리 표기)가 같은 縣을 가리킨다는 것이고, 어느 쪽 글자가
# 옳은지는 **판정하지 않았다** — 그래서 아래 unknown 에 그 사실을 남긴다.
ORTHOGRAPHIC = frozenset((
    ('魯國', '魯國', '魯'),              # 원문 「魯國，古奄國」 — 縣名은 魯, 추출기가 國까지 떴다
    ('武威郡', '鸾鸟', '鸞鳥'),
    ('武威郡', '朴[B459]', '樸峦'),
    ('武威郡', '媪围', '媼圍'),
    ('東郡', '衞', '衛'),
    ('定襄郡', '骆', '駱'),
    ('吳郡', '呉', '吳'),
    ('汝南郡', '愼', '慎'),
))
# 參䜌·南䜌·朱䳒 — 위키문헌 저본이 이런 글자를 결자(「[B459]」)나 편방 분해
# (「〈糸言糸〉」)로 적어 소스에 그대로 쓸 수 없다. 그 표기가 든 이름은 첫 글자가
# 같은 우리 쪽 이름과 대응시킨다. 완화를 이 표기가 있는 자리로만 좁힌다.
BROKEN_GLYPH = ('[', '〈', '�')


def _load_truth():
    spec = importlib.util.spec_from_file_location('county_names_truth', TRUTH_PATH)
    mod = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = mod
    spec.loader.exec_module(mod)
    return mod


def _align(truth_names, our_names):
    """원문 縣名과 우리 縣名을 맞춰 보고 (놓친 것, 우리에만 있는 것) 을 낸다.

    같은 길이 2자 이상에서 1글자만 다르면 異體字로 보고 대응시킨다. 1자 이름에
    이 완화를 주면 아무 1자끼리나 짝지어져(南陽郡 「酇」이 딴 1자와 붙었다)
    진짜 소실이 숨는다 — 그래서 len >= 2 로 못박는다.
    """
    ours = list(our_names)
    missing = []
    for name in truth_names:
        if name in ours:
            ours.remove(name)
            continue
        variant = next((o for o in ours
                        if len(o) == len(name) >= 2
                        and sum(a != b for a, b in zip(o, name)) == 1), None)
        if variant:
            ours.remove(variant)
            continue
        missing.append(name)
    return missing, ours


def _drop_orthographic(jun, missing, extra):
    for a in list(missing):                      # 參䜌 / 南䜌 / 朱䳒
        if len(a) >= 2 and any(ch in a[1:] for ch in BROKEN_GLYPH):
            mate = next((x for x in extra if len(x) >= 2 and x[0] == a[0]), None)
            if mate:
                missing.remove(a); extra.remove(mate)
    for j, a, b in ORTHOGRAPHIC:
        if j == jun and a in missing and b in extra:
            missing.remove(a); extra.remove(b)
    return missing, extra


def compare():
    """[(郡, 원문에 있는데 우리에 없는 縣, 우리에만 있는 항목)] — 어긋난 郡만."""
    truth = _load_truth().truth_counties()
    places = json.loads(OUT_PATH.read_text(encoding='utf-8'))['places']
    assert len(places) == len(truth) == 105, (len(places), len(truth))
    rows = []
    for place, ref in zip(places, truth):     # 둘 다 志 순서다
        missing, extra = _align(ref['counties'], [c['name'] for c in place['counties']])
        missing, extra = _drop_orthographic(place['name'], missing, extra)
        if missing or extra:
            rows.append({'jun': place['name'], 'missing': missing, 'extra': extra})
    return rows


class TestTruthModuleIsIndependent(unittest.TestCase):
    """대조 축이 파서 산출물에서 나오면 대조가 아무것도 증명하지 않는다.

    주석으로 「파서를 안 쓴다」고 써 두면 다음 사람이 편의상 깨뜨린다. 소스에
    직접 단언한다 — 이 테스트는 원본 데이터 없이도 CI 에서 돈다.
    """

    def test_truth_module_does_not_touch_parser(self):
        src = TRUTH_PATH.read_text(encoding='utf-8')
        body = '\n'.join(l for l in src.splitlines() if not l.lstrip().startswith('#'))
        # docstring 안에서는 파일명을 설명으로 언급한다 — 코드 줄만 본다.
        code = body.split('"""')[0] + '"""'.join(body.split('"""')[2:])
        for forbidden in ('build_junguozhi', 'junguozhi.json'):
            self.assertNotIn(
                forbidden, code,
                f'county_names_truth.py 가 {forbidden} 를 참조한다. 그러면 대조 축이 '
                f'파서 산출물에서 나오고, 대조가 아무것도 증명하지 않게 된다.')


@unittest.skipUnless(
    CORPUS.exists() and DBF.exists() and OUT_PATH.exists(),
    'data/corpus/** · data/chgis-source/** · data/map/junguozhi.json 은 '
    'ADR-LITE-039 로 gitignore 돼 있다 — CI 에서는 항상 skip 된다(실패 아님).'
)
class TestCountyNameBaseline(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.rows = compare()
        cls.baseline = json.loads(BASELINE_PATH.read_text(encoding='utf-8'))

    def test_matches_baseline(self):
        expected = self.baseline['juns']
        got = {r['jun']: r for r in self.rows}
        want = {r['jun']: r for r in expected}
        new = sorted(set(got) - set(want))
        gone = sorted(set(want) - set(got))
        self.assertEqual(new, [], f'새로 어긋난 郡이다 — 회귀다: {new}')
        self.assertEqual(
            gone, [],
            f'어긋남이 사라진 郡이다. 고친 것이면 기준선을 갱신해라 '
            f'(python3 tools/map/tests/test_county_name_baseline.py --update): {gone}')
        for jun in sorted(want):
            self.assertEqual(got[jun]['missing'], want[jun]['missing'], f'{jun} 소실 縣 목록')
            self.assertEqual(got[jun]['extra'], want[jun]['extra'], f'{jun} 가짜 항목 목록')

    def test_totals_match_baseline(self):
        self.assertEqual(len(self.rows), self.baseline['jun_count'])
        self.assertEqual(sum(len(r['missing']) for r in self.rows),
                         self.baseline['missing_total'])
        self.assertEqual(sum(len(r['extra']) for r in self.rows),
                         self.baseline['extra_total'])

    def test_checksum_blind_spot_is_recorded(self):
        """「城數는 맞는데 이름이 다른」 郡 수 — 이 기준선이 존재하는 이유 그 자체다."""
        places = {p['name']: p for p in json.loads(OUT_PATH.read_text(encoding='utf-8'))['places']}
        blind = [r['jun'] for r in self.rows
                 if len(r['missing']) == len(r['extra'])
                 and places[r['jun']].get('checksum') != 'FAIL']
        self.assertEqual(len(blind), self.baseline['checksum_blind_count'])


def _update():
    rows = compare()
    old = json.loads(BASELINE_PATH.read_text(encoding='utf-8'))
    old.update(juns=rows, jun_count=len(rows),
               missing_total=sum(len(r['missing']) for r in rows),
               extra_total=sum(len(r['extra']) for r in rows))
    BASELINE_PATH.write_text(json.dumps(old, ensure_ascii=False, indent=1) + '\n',
                             encoding='utf-8')
    print(f'갱신: {len(rows)}郡 / 소실 {old["missing_total"]} / 가짜 {old["extra_total"]}')


if __name__ == '__main__':
    if '--update' in sys.argv:
        _update()
    else:
        unittest.main()
