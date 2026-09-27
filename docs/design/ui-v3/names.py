# 화면 표기 공용 규칙 — 플레이어가 읽는 글은 한글 우선.
# ko('陽翟') -> '양적현' : 제품 데이터(han-tiles.json) 의 한글 이름. 繁→簡 은 프로젝트 글자표로 눕힌다.
# PROVINCE_TERM : 省 의 화면 용어. 省·城 이 둘 다 「성」이라 省 은 다른 말로 쓴다. 확정(2026-09-18). 여기 한 곳만 바꾸면 된다.
# 아트보드 본문에서는 토큰 PROV 를 쓰고, page() 가 PROVINCE_TERM 으로 바꾼다.
import json, os

PROVINCE_TERM = '구역'
PROV = '⟦PROV⟧'          # ⟦PROV⟧ — 본문 안의 자리 표시


def apply_terms(html):
    return html.replace(PROV, PROVINCE_TERM)


_R = os.path.dirname(os.path.abspath(__file__))
_G = os.path.normpath(os.path.join(_R, '..', '..', '..'))
_cache = {}
# 프로젝트 글자표(실사용 글자만 실림)에 없는 繁→簡 — 이 화면들이 쓰는 글자만 보탠다.
EXTRA_FOLD = {'郟': '郏', '滎': '荥', '懷': '怀'}


def _load():
    if _cache:
        return _cache
    f = json.load(open(os.path.join(_G, 'data/curated/han/han-name-simplification-v1.json'), encoding='utf-8'))
    fold = dict(f['table'])
    for a in f.get('reviewedVariantAdditions', []):
        fold[a['from']] = a['to']
    fold.update(EXTRA_FOLD)
    t = json.load(open(os.path.join(_G, 'data/map/han-tiles.json'), encoding='utf-8'))
    look = {}
    sm = lambda x: ''.join(fold.get(ch, ch) for ch in x)
    for c in t['cities']:
        look.setdefault(sm(c['nameCh']), c['name'])
    for j in t['jurisdictionRecords']:
        look[sm(j['nameCh'])] = j.get('displayName') or j.get('name')
    for c in t['commanderyRecords']:
        look[sm(c['nameCh'])] = c.get('displayName') or c.get('name')
    _cache.update(fold=fold, look=look)
    return _cache


def simp(s):
    fold = _load()['fold']
    return ''.join(fold.get(ch, ch) for ch in s)


# 데이터에 없는 이름 — 표준 한국 한자음으로 직접 옮긴 것(검수 대상).
MANUAL = {
    '江水': '강수', '河水': '하수', '洧水': '유수', '潁水': '영수', '嵩高山': '숭고산',
    '官渡': '관도', '摩陂營': '마피영', '豫州': '예주', '兗州': '연주', '并州': '병주', '冀州': '기주', '冀': '기주',
    '負黍聚': '부서취',
}


def ko(name, default=None):
    """한자 지명 -> 제품 한글 이름. 縣 접미사 없는 繁體도 받는다. 없으면 MANUAL, 그래도 없으면 default/KeyError."""
    look = _load()['look']
    s = simp(name)
    for cand in (s, s + '县', s + '郡', s + '国', s + '侯国'):
        if cand in look:
            return look[cand]
    if name in MANUAL:
        return MANUAL[name]
    if default is not None:
        return default
    raise KeyError(name)


def source(name):
    """'data' | 'manual' | None"""
    look = _load()['look']
    s = simp(name)
    if any(c in look for c in (s, s + '县', s + '郡', s + '国', s + '侯国')):
        return 'data'
    return 'manual' if name in MANUAL else None


if __name__ == '__main__':
    import sys
    for n in sys.argv[1:]:
        print(n, ko(n, '?'), source(n))
