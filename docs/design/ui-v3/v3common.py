# 캔버스 v3 공용 부품 — ADR-LITE-049 2026-09-26 개정.
# 셸 하나, 메뉴 = 작전실 + 6묶음(휘하·계책·영지·군단·조정·기록) + 광장, 모바일도 같은 게임.
# ui.py 의 색·서체·부품(CSS, sec, kv, mod, PT, ART)을 그대로 잇고 v3 에서 더한 것만 여기에 둔다.
import os
from ui import CSS, PT, ART, FIELD, sec, kv, mod
from names import apply_terms, PROV

R = os.path.dirname(os.path.abspath(__file__))
P = os.path.join(R, 'project')
os.makedirs(P, exist_ok=True)

# 자원색 — 의미색(청동 = 지위·주 행동, 이끼 = 가능, 적갈 = 경고, 청 = 중립)과 겹치지 않게 따로 둔다.
# 자원은 색만으로 구분하지 않는다: 늘 글자와 함께 쓴다.
RES = {'금': '#e6c35c', '쌀': '#e2dcc3', '철': '#8fa0ad', '목재': '#a5744a', '말': '#9c7bb0'}

V3CSS = '''
.rail{width:76px;flex-shrink:0;display:flex;flex-direction:column;background:#141816;border-right:1px solid #3d4740}
.rail a{height:62px;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:4px;color:#b9b2a3;font-size:11px;border-bottom:1px solid #1f2522}
.rail a.on{color:#d3b064;background:rgba(211,176,100,.10);box-shadow:inset 3px 0 0 #d3b064}
.rail.slim{width:56px}.rail.slim a{height:56px;font-size:10px}
.ibtn{width:44px;height:44px;display:inline-flex;align-items:center;justify-content:center;background:#141816;border:1px solid #3d4740;color:#ece6d8;position:relative;flex-shrink:0;cursor:pointer;font:inherit}
.ibtn .badge{position:absolute;top:4px;right:4px;min-width:16px;height:16px;padding:0 4px;font-size:10px;font-weight:700;line-height:16px;color:#161410;background:#d3b064}
.btn.off{background:transparent;color:#8a8477;border:1px dashed #5a625c;font-weight:500;cursor:pointer}
.why{display:inline-flex;align-items:center;gap:4px;height:28px;padding:0 8px;font:inherit;font-size:11px;color:#e08a7c;background:transparent;border:1px dashed #c96b5d;cursor:pointer;white-space:nowrap}
.sheet{position:absolute;left:0;right:0;background:#1b201d;border-top:1px solid #9c7f3f;box-shadow:0 -12px 32px rgba(0,0,0,.55);display:flex;flex-direction:column}
.sheet .grip{width:40px;height:4px;background:#5a625c;margin:8px auto 4px;flex-shrink:0}
.tabbar{height:64px;flex-shrink:0;display:flex;background:#141816;border-top:1px solid #3d4740}
.tabbar a{flex:1 1 0;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:3px;color:#b9b2a3;font-size:11px}
.tabbar a.on{color:#d3b064;box-shadow:inset 0 3px 0 #d3b064}
.cat{display:inline-flex;align-items:center;gap:5px;height:20px;padding:0 7px;font-size:11px;color:#b9b2a3;border:1px solid #3d4740;background:#141816;white-space:nowrap}
.cat i{width:7px;height:7px;display:inline-block}
.res{display:inline-flex;align-items:center;gap:4px;font-size:12px;white-space:nowrap}.res i{width:10px;height:10px;display:inline-block;border:1px solid rgba(0,0,0,.45)}
'''

# 아이콘 — 인라인 stroke SVG(24 격자).
IC = {
    'war': '<path d="M4 6l5-2 6 2 5-2v14l-5 2-6-2-5 2z"/><path d="M9 4v14M15 6v14"/>',
    'retinue': '<circle cx="9" cy="8" r="3"/><path d="M3 20c0-3.3 2.7-5 6-5s6 1.7 6 5"/><circle cx="17" cy="9" r="2.5"/><path d="M16 15c2.8.2 5 1.8 5 4.5"/>',
    'stratagem': '<rect x="4" y="5" width="10" height="14"/><path d="M10 5l8 2-3 13-4-1"/>',
    'territory': '<path d="M3 11l9-7 9 7v9H3z"/><path d="M9 20v-6h6v6"/>',
    'corps': '<path d="M5 21V3M5 4h12l-3 4 3 4H5"/>',
    'court': '<path d="M3 9l9-5 9 5M5 9v9M10 9v9M14 9v9M19 9v9M3 20h18"/>',
    'records': '<path d="M5 4h11a3 3 0 0 1 3 3v13H8a3 3 0 0 1-3-3z"/><path d="M9 8h7M9 12h7"/>',
    'plaza': '<path d="M4 5h16v11H9l-5 4z"/>',
    'help': '<circle cx="12" cy="12" r="9"/><path d="M9.5 9.2a2.6 2.6 0 1 1 3.6 2.4c-.7.3-1.1.9-1.1 1.6v.6M12 17v.6"/>',
    'admin': '<circle cx="12" cy="12" r="3"/><path d="M12 3v3M12 18v3M3 12h3M18 12h3M5.6 5.6l2.1 2.1M16.3 16.3l2.1 2.1M5.6 18.4l2.1-2.1M16.3 7.7l2.1-2.1"/>',
    'mail': '<rect x="3" y="5" width="18" height="14"/><path d="M3 6l9 7 9-7"/>',
    'menu': '<path d="M4 7h16M4 12h16M4 17h16"/>',
    'layers': '<path d="M12 3l9 5-9 5-9-5z"/><path d="M3 13l9 5 9-5"/>',
    'close': '<path d="M6 6l12 12M18 6L6 18"/>',
    'back': '<path d="M15 5l-7 7 7 7"/>',
    'up': '<path d="M6 15l6-6 6 6"/>',
    'legend': '<rect x="4" y="4" width="6" height="6"/><rect x="4" y="14" width="6" height="6"/><path d="M13 7h7M13 17h7"/>',
    'lobby': '<path d="M4 12h12M11 6l-6 6 6 6"/><path d="M20 4v16"/>',
}


def icon(name, size=20, color='currentColor'):
    return (f'<svg width="{size}" height="{size}" viewBox="0 0 24 24" fill="none" stroke="{color}" '
            f'stroke-width="1.8" stroke-linecap="square" aria-hidden="true">{IC[name]}</svg>')


# 메뉴 — 한 벌. (키, 이름, 아이콘, [(화면, 새 경로, 지금 화면)])
NAV = [
    ('war', '작전실', 'war', [('지도 · 명령 목록 12순 · 지난 순', '/game/<서버>', '/game · /game/war-room · /game/map')]),
    ('retinue', '휘하', 'retinue', [('편성 · 결속 · 명망', '/retinue', '/retinue'),
                                   ('인물 일람', '/retinue/people', '/generals · /rankings/generals · /rankings/best-generals'),
                                   ('월단평', '/retinue/yuedan', '/yuedan')]),
    ('stratagem', '계책', 'stratagem', [('계책 덱', '/stratagem', '/hand')]),
    ('territory', '영지', 'territory', [('배치 · 방침 · 공사', '/territory', '/posts'),
                                       ('현 상세', '/territory/county/[id]', '/city · /my-cities'),
                                       ('창고망 · 보급', '/territory/supply', '/supply')]),
    ('corps', '군단', 'corps', [('군단 · 세력 작전', '/corps', '— (새 화면)'),
                               ('공성', '/corps/siege', '/siege'),
                               ('전투 계획 · 방어 대비', '/corps/battle/[id]', '— (새 화면)')]),
    ('court', '조정', 'court', [('발령 · 포상 · 조정 결정', '/court', '/orders · /court'),
                               ('관직', '/court/offices', '— (2층)'),
                               ('외교', '/court/diplomacy', '/global-diplomacy · 서신 외교 탭')]),
    ('records', '기록', 'records', [('지난 순 · 기록 5분류', '/records', '/world-log · /battle-center · /my 기록'),
                                   ('연감', '/records/yearbook', '/history'),
                                   ('리플레이', '/records/replay/[id]', '/battle-replay/[id]')]),
    ('plaza', '광장', 'plaza', [('회의실 · 기밀실', '/council', '/board'),
                               ('서신', '/mail', '/mailbox'),
                               ('커뮤니티', '게이트웨이 /board', '게이트웨이 /board')]),
]
DESK_EXTRA = [('help', '도움말', 'help'), ('admin', '관리', 'admin')]


def rail(on='war', slim=False):
    items = ''.join(
        f'<a href="#" class="{"on" if k == on else ""}" aria-current="{"page" if k == on else "false"}">{icon(ic, 20 if slim else 22)}'
        f'<span>{n}</span></a>' for k, n, ic, _ in NAV)
    extra = ''.join(f'<a href="#">{icon(ic, 20)}<span>{n}</span></a>' for k, n, ic in DESK_EXTRA)
    return f'<nav class="rail{" slim" if slim else ""}" aria-label="게임 메뉴">{items}<div style="flex-grow:1"></div>{extra}</nav>'


LOGO = ('<div style="width:132px;height:30px;border:1px dashed #9c7f3f;display:flex;align-items:center;justify-content:center;'
        'font-size:10px;color:#8a8477;flex-shrink:0">logo-wordmark.png</div>')
LOGO_M = ('<div style="width:96px;height:26px;border:1px dashed #9c7f3f;display:flex;align-items:center;justify-content:center;'
          'font-size:9px;color:#8a8477;flex-shrink:0">logo-wordmark.png</div>')


def topbar(title, h=56, compact=False):
    tut = '' if compact else '<a class="chip info" href="#">첫걸음 3 / 8</a>'
    nxt = '<span class="chip">다음 개인 턴 21:40</span>' if not compact else ''
    return f'''<header style="height:{h}px;display:flex;align-items:center;justify-content:space-between;gap:12px;padding:0 16px;border-bottom:1px solid #3d4740;background:linear-gradient(180deg,#232a26,#1b201d);flex-shrink:0">
<div style="display:flex;align-items:center;gap:14px;min-width:0">{LOGO}<h1 class="serif" style="margin:0;font-size:18px;font-weight:900;white-space:nowrap">{title}</h1></div>
<div style="display:flex;align-items:center;gap:8px"><span class="chip">200년 3월 중순</span>{nxt}{tut}
<button type="button" class="ibtn" aria-label="서신 2통">{icon('mail')}<span class="badge">2</span></button>
<button type="button" class="ibtn" aria-label="이 화면 도움말">{icon('help')}</button>
<span class="chip">하후돈 · 조조 휘하</span><span class="chip bronze">명망 [미정]</span></div></header>'''


def mtop(title=None, back=None):
    left = (f'<a href="#" class="ibtn" aria-label="{back}로 돌아가기">{icon("back")}</a><span class="serif" style="font-size:17px;font-weight:900">{title}</span>'
            if back else f'{LOGO_M}<span class="chip">3월 중순</span>')
    return f'''<header style="height:56px;flex-shrink:0;display:flex;align-items:center;justify-content:space-between;gap:8px;padding:0 8px 0 12px;border-bottom:1px solid #3d4740;background:#1b201d">
<div style="display:flex;align-items:center;gap:8px;min-width:0">{left}</div>
<div style="display:flex;gap:6px"><button type="button" class="ibtn" aria-label="서신 2통">{icon('mail')}<span class="badge">2</span></button><button type="button" class="ibtn" aria-label="이 화면 도움말">{icon('help')}</button></div></header>'''


MTABS = [('war', '작전실'), ('retinue', '휘하'), ('stratagem', '계책'), ('records', '기록'), ('menu', '전체')]


def tabbar(on='war'):
    return '<nav class="tabbar" aria-label="게임 메뉴">' + ''.join(
        f'<a href="#" class="{"on" if k == on else ""}">{icon(k, 22)}<span>{n}</span></a>' for k, n in MTABS) + '</nav>'


# 기록 5분류(ADR-LITE-069) — 분류 표식 색.
CATS = {'개인 행적': '#d3b064', '휘하 · 세력': '#8fa77a', '조정 공문': '#7aa7c7', '전장 보고': '#c96b5d', '천하 정세': '#b9b2a3'}


def cat(c):
    return f'<span class="cat"><i style="background:{CATS[c]}"></i>{c}</span>'


def res(n, v=''):
    return f'<span class="res"><i style="background:{RES[n]}"></i>{n}{(" " + v) if v else ""}</span>'


def page3(name, title, body, w=1440, h=1000):
    doc = f'''<!doctype html>
<html lang="ko">
<head>
<meta charset="utf-8">
<title>{title}</title>
<script src="./support.js"></script>
</head>
<body>
<x-dc>
<helmet>
<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Noto+Serif+KR:wght@700;900&amp;family=Noto+Sans+KR:wght@400;500;700&amp;family=JetBrains+Mono:wght@500;700&amp;display=swap">
<style>{CSS}{V3CSS}</style>
</helmet>
<div style="width: {w}px; height: {h}px; background: #0c0f0e; display: flex; flex-direction: column; overflow: hidden; position: relative;">
{body}
</div>
</x-dc>
<script type="text/x-dc" data-dc-script data-props='{{"$preview":{{"width":{w},"height":{h}}}}}'>
class Component extends DCLogic {{
renderVals() {{ return {{}}; }}
}}
</script>
</body>
</html>
'''
    open(os.path.join(P, name), 'w', encoding='utf-8').write(apply_terms(doc))
