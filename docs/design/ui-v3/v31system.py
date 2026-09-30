# 캔버스 v3.1 디자인 시스템 — 전 페이지 설계(57페이지 × 데스크톱 · 모바일)가 같이 쓰는 셸 · 부품 · 상태.
# 09-26 승인본(v3common: 셸 하나 · 메뉴 한 벌 · 사유 시트 · 자원색 · 기록 5분류)을 잇고, v3.1 에서 더한 것만 여기 둔다.
#
# 설계 레인(K4–K8)은 boards_v31_<레인>.py 에서 `from v31system import *` 로 쓴다. 부품 id 는 PARTS(보드 V31SystemIndex).
#   page31(name, title, body, w, h)        — v3.1 보드 한 장(CSS + V3CSS + V31CSS)
#   shell_desk(title, on, main, band_html) — 데스크톱 1440: 머리줄 48 + (알림 띠) + 레일 56 + 본문
#   shell_mob(main, on, title, back)       — 모바일 390: 머리줄 56 + 본문 + 하단 탭 64(본문 높이 724)
#   topbar31 / mtop31 / rail31 / tabbar31  — 셸 조각(계절 칩 · 연습 서버 칩 · 부 이름)
#   pagehead / subtabs / mtabs_row         — 내용 페이지 머리 · 묶음 안 하위 화면 탭
#   btn / btn_off / ibtn / chip / why_tag / field / inp / search / seg / opt / checkbox / dialog / sheet / pop / toast / tbl
#   state_*(…)                             — 상태(P-X01)
#   band(kind, mobile)                     — 공통 알림 띠(P-W05)
#   turn_strip / cat_tabs / cmd_row / help_strip / target_field — 명령 흐름(P-W02 부품)
#   mk / mlab / cand_row / pick_bar / path_line / view_bar      — 지도 대상 고르기
#   me_marker / me_edge / me_card          — 내 위치 표지
#   person_row / people_picker             — 사람 고르기(NPC 포함)
#   season_panel                           — 머리줄 계절 칩 → 떠 있는 패널 · 하단 시트(P-K07 자리)
# 이 파일은 K3 만 고친다. 부품이 더 필요하면 K3 에게 요청한다. 그림 id 는 v31assets.py(K0) 에서만 온다.
#
# python3 v31system.py → project/V31System*.dc.html
import os

from v3common import *  # noqa: F401,F403 — CSS · V3CSS · sec · kv · icon · IC · cat · CATS · res · RES · LOGO …
from v3common import CSS, V3CSS, IC, P, LOGO, LOGO_M, apply_terms, icon, sec, kv, cat, CATS, res
try:  # 관직 한글 표기(K8). names.py 가 옛 판이면(다른 worktree 로 이 파일만 복사했을 때) 같은 표를 여기서 쓴다.
    from names import office_ko  # noqa: F401
except ImportError:
    _OFFICE_KO = {'刺史': '자사', '州牧': '주목', '太守': '태수', '國相': '국상', '縣令': '현령', '縣長': '현장', '侯國相': '후국상'}

    def office_ko(name, default=None):
        if name in _OFFICE_KO:
            return _OFFICE_KO[name]
        if default is not None:
            return default
        raise KeyError(name)

try:  # v3.1 캔버스(KCFDJTVgSGFa9N4qzrQ6By) 의 그림 id. 09-18 캔버스 id(ui.py) 는 v3.1 캔버스에서 풀리지 않는다.
    from v31assets import PT, ART, FIELD, MAP
    HAVE_ASSETS = True
except ImportError:  # v31assets.py 가 아직 이 브랜치에 없으면 그림 자리를 점선 상자로 그린다.
    PT = {k: '' for k in ('jojo', 'hahoudon', 'sunuk', 'heojeo', 'join', 'ijeon')}
    ART, FIELD, MAP = {}, '', {}
    HAVE_ASSETS = False

# ------------------------------------------------------------------ 토큰(제품 web/shared/src/tokens.css 이름과 같게 옮긴다)
# 브레이크포인트 3단: 모바일 < 768 ≤ 태블릿 < 1200 ≤ 데스크톱. 화면별 임의 px 금지.
BP = {'tablet': 768, 'desktop': 1200}
LAYOUT = {  # 틀 치수(px)
    '--header-h': 48, '--header-h-mobile': 56, '--rail-w': 56, '--tabbar-h': 64,
    '--turns-w': 336, '--turns-w-tablet': 288, '--flow-w': 576, '--flow-w-tablet': 480,
    '--flow-list-w': 240, '--flow-args-w': 336, '--drawer-w': 400, '--drawer-w-tablet': 360,
    '--sheet-peek': 124, '--card-float-w': 320, '--touch': 44, '--row-h': 44, '--row-h-comfy': 52,
}
Z = [('--z-map', 0, '지도 캔버스'), ('--z-map-mark', 10, '지도 표지 · 이름표 · 내 위치(맨 위)'), ('--z-map-ctrl', 20, '지도 위 단추 · 고르기 띠 · 작은 지도'),
     ('--z-float', 30, '떠 있는 카드(선택 · 내 장수 · 계절)'), ('--z-drawer', 40, '서랍(지난 순 · 도움말 · 서신)'), ('--z-sheet', 50, '하단 시트 · 사유 말풍선'),
     ('--z-dialog', 60, '대화상자 · 가림막'), ('--z-toast', 70, '알림 토스트')]
SHADOW = [('떠 있는 카드', '0 10px 28px rgba(0,0,0,.5)'), ('말풍선', '0 10px 30px rgba(0,0,0,.55)'),
          ('하단 시트', '0 -12px 32px rgba(0,0,0,.55)'), ('대화상자', '0 24px 64px rgba(0,0,0,.6)')]
NATION = {'조조': '#4f7fbf', '원소': '#b0569a', '유표': '#4f9e8a', '무주': '#5a625c'}  # 예시(유저가 고른다) — v3map.F 와 같다

V31CSS = '''
.btn.sm{height:44px;padding:0 12px;font-size:12px}
.why{height:44px;padding:0 10px;font-size:12px}
.whyt{display:inline-flex;align-items:center;height:24px;padding:0 7px;font-size:11.5px;color:#e08a7c;border:1px dashed #c96b5d;white-space:nowrap}
.hchip{display:inline-flex;align-items:center;gap:6px;height:44px;padding:0 10px;font:inherit;font-size:12px;color:#ece6d8;background:transparent;border:1px solid #3d4740;white-space:nowrap;cursor:pointer;position:relative}
.hchip .dot{position:absolute;top:6px;right:6px}
.fld{display:flex;flex-direction:column;gap:6px;min-width:0}
.fld .lb{font-size:12px;color:#b9b2a3;font-weight:500}
.fld .help{font-size:11.5px;color:#8a8477;line-height:1.4}
.fld .err{font-size:12px;color:#e08a7c;line-height:1.4}
.inp{height:44px;width:100%;padding:0 12px;background:#141816;border:1px solid #3d4740;color:#ece6d8;font:inherit;font-size:14px;display:flex;align-items:center;gap:8px;min-width:0}
.inp.bad{border-color:#c96b5d;background:rgba(201,107,93,.06)}
.inp .ph{color:#8a8477}
.inp .unit{margin-left:auto;font-size:12px;color:#8a8477}
.area{height:auto;min-height:88px;align-items:flex-start;padding:10px 12px;line-height:1.5}
.seg{display:flex;gap:2px;min-width:0}
.seg button{min-width:44px;height:44px;padding:0 12px;font:inherit;font-size:13px;color:#ece6d8;background:#141816;border:1px solid #3d4740;cursor:pointer;white-space:nowrap;display:inline-flex;align-items:center;justify-content:center;gap:6px}
.seg button[aria-pressed="true"]{background:#d3b064;color:#161410;border-color:#9c7f3f;font-weight:700}
.seg button .n{font-size:11px;opacity:.8}
.seg.v{flex-direction:column}
.opt{width:100%;min-height:52px;display:flex;align-items:center;gap:10px;padding:4px 12px;font:inherit;font-size:14px;text-align:left;color:#ece6d8;background:transparent;border:0;border-bottom:1px solid #2c342f;cursor:pointer}
.opt[aria-selected="true"]{background:rgba(211,176,100,.10);box-shadow:inset 3px 0 0 #d3b064}
.opt[aria-disabled="true"]{color:#8a8477}
.opt .nm{font-family:'Noto Serif KR',serif;font-weight:700;font-size:15px;white-space:nowrap}
.opt .sub{font-size:11.5px;color:#8a8477;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;min-width:0}
.opt .end{margin-left:auto;display:flex;align-items:center;gap:6px;flex-shrink:0}
.dot{width:8px;height:8px;display:inline-block;flex-shrink:0}
.cb{display:inline-flex;align-items:center;gap:8px;min-height:44px;font-size:13px;color:#ece6d8;cursor:pointer;white-space:nowrap}
.cb i{width:20px;height:20px;border:1px solid #5a625c;background:#141816;display:inline-flex;align-items:center;justify-content:center;flex-shrink:0}
.cb i.on{background:#d3b064;border-color:#9c7f3f}
.slot{height:44px;min-width:0;display:flex;flex-direction:column;justify-content:center;gap:1px;padding:0 6px 0 9px;font:inherit;text-align:left;color:#ece6d8;background:#141816;border:1px solid #3d4740;cursor:pointer;box-shadow:inset 3px 0 0 transparent;overflow:hidden}
.slot .d{font-family:'JetBrains Mono',monospace;font-size:10.5px;color:#b9b2a3;white-space:nowrap}
.slot .c{font-family:'Noto Serif KR',serif;font-size:13px;font-weight:700;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;line-height:1.2}
.slot.done{box-shadow:inset 3px 0 0 #697e58}.slot.done .c{color:#8a8477}
.slot.res{box-shadow:inset 3px 0 0 #d3b064}
.slot.warn{box-shadow:inset 3px 0 0 #c96b5d}
.slot.empty{border-style:dashed}.slot.empty .c{color:#8a8477;font-family:inherit;font-weight:400;font-size:12px}
.slot[aria-current="true"]{outline:3px solid #ffd36d;outline-offset:-3px;background:#232a26}
.mk{position:absolute;width:44px;height:44px;margin:-22px 0 0 -22px;padding:0;font:inherit;background:transparent;cursor:pointer}
.mk.ok{border:2px solid #8fa77a;background:rgba(143,167,122,.20)}
.mk.no{border:2px dashed #e08a7c;background:rgba(12,15,14,.40)}
.mk.sel{border:3px solid #ffd36d;background:rgba(255,211,109,.18);box-shadow:0 0 0 2px rgba(12,15,14,.9)}
.mk.corps{border-radius:50%;border:2px solid #8fa77a;background:rgba(12,15,14,.6);color:#ece6d8;font-family:'Noto Serif KR',serif;font-weight:900;font-size:15px}
.mk .n{position:absolute;right:-8px;top:-8px;min-width:20px;height:20px;padding:0 4px;font-family:'JetBrains Mono',monospace;font-size:11px;font-weight:700;line-height:20px;color:#161410;background:#ffd36d}
.mlab{position:absolute;height:22px;padding:0 6px;display:inline-flex;align-items:center;font-family:'Noto Serif KR',serif;font-size:14px;font-weight:700;color:#f5ecd6;background:rgba(12,15,14,.78);white-space:nowrap;transform:translateX(-50%)}
.mlab.no{color:#b9b2a3;text-decoration:line-through;text-decoration-color:rgba(224,138,124,.7)}
.mlab.big{font-size:17px;font-weight:900;height:26px}
.rgn{position:absolute;transform:translate(-50%,-50%);min-height:44px;padding:0 14px;display:inline-flex;align-items:center;gap:6px;font:inherit;font-family:'Noto Serif KR',serif;font-size:16px;font-weight:900;color:#f5ecd6;background:rgba(12,15,14,.82);cursor:pointer;white-space:nowrap}
.rgn.ok{border:2px solid #8fa77a}.rgn.no{border:2px dashed #e08a7c;color:#b9b2a3}.rgn.sel{border:3px solid #ffd36d}
.skel{display:block;background:#232a26}
.pop{position:absolute;width:320px;background:#1b201d;border:1px solid #9c7f3f;box-shadow:0 10px 30px rgba(0,0,0,.55);padding:10px 4px 4px 12px;display:flex;flex-direction:column;gap:6px}
.dlg{background:#1b201d;border:1px solid #9c7f3f;box-shadow:0 24px 64px rgba(0,0,0,.6);display:flex;flex-direction:column}
.scrim{position:absolute;inset:0;background:rgba(8,10,9,.62)}
.dim{position:absolute;inset:0;background:rgba(8,10,9,.42)}
.band{min-height:44px;display:flex;align-items:center;gap:10px;padding:0 6px 0 16px;font-size:13px;flex-shrink:0;border-bottom:1px solid #3d4740}
.band.catch{background:rgba(211,176,100,.12);border-bottom-color:#9c7f3f}
.band.stop{background:rgba(201,107,93,.16);border-bottom-color:#c96b5d}
.band.notice,.band.tutorial{background:rgba(122,167,199,.12);border-bottom-color:#4b6d87}
.toast{display:flex;align-items:center;gap:10px;min-height:48px;padding:0 6px 0 14px;background:#232a26;border:1px solid #3d4740;box-shadow:0 10px 30px rgba(0,0,0,.5);font-size:13px}
.toast.ok{border-color:#697e58}.toast.bad{border-color:#c96b5d}
.note{font-size:12px;line-height:1.55;color:#b9b2a3}
.ul{margin:0;padding:0;list-style:none}
.ul li{padding:6px 0;border-bottom:1px solid #2c342f;font-size:12.5px;line-height:1.5;color:#b9b2a3}
.ul li b{color:#ece6d8;font-weight:700}
.mono{font-family:'JetBrains Mono',ui-monospace,Menlo,monospace}
.hstrip{min-height:44px;display:flex;align-items:center;gap:8px;padding:0 4px 0 10px;background:#141816;border:1px solid #2c342f;font-size:12px;color:#b9b2a3}
.cal{display:flex;gap:1px}.cal i{flex:1 1 0;height:18px;background:#232a26;display:block}.cal i.past{background:#3d4740}.cal i.now{background:#ffd36d}
'''

IC.update({
    'search': '<circle cx="11" cy="11" r="6.5"/><path d="M16 16l4.5 4.5"/>',
    'target': '<circle cx="12" cy="12" r="7"/><circle cx="12" cy="12" r="2"/><path d="M12 2v4M12 18v4M2 12h4M18 12h4"/>',
    'check': '<path d="M5 12.5l4.5 4.5L19 7.5"/>',
    'alert': '<path d="M12 3l10 18H2z"/><path d="M12 10v5M12 18v.5"/>',
    'lock': '<rect x="5" y="11" width="14" height="10"/><path d="M8 11V8a4 4 0 0 1 8 0v3"/>',
    'refresh': '<path d="M20 11a8 8 0 1 0-2.3 5.7"/><path d="M20 4v7h-7"/>',
    'clock': '<circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/>',
    'prev': '<path d="M14 6l-6 6 6 6"/>',
    'next': '<path d="M10 6l6 6-6 6"/>',
    'swap': '<path d="M7 7h13l-3-3M17 17H4l3 3"/>',
    'clear': '<path d="M5 7h14M9 7V4h6v3M7 7l1 13h8l1-13"/>',
    'list': '<path d="M9 6h11M9 12h11M9 18h11M4 6h1M4 12h1M4 18h1"/>',
    'tools': '<path d="M14 6l4-2 2 2-2 4-3 1-7 7-3-3 7-7z"/>',
    'copy': '<rect x="8" y="8" width="12" height="12"/><path d="M4 16V4h12"/>',
    'arrow': '<path d="M12 4v16M5 13l7 7 7-7"/>',
    'season': '<circle cx="12" cy="12" r="4"/><path d="M12 2v3M12 19v3M2 12h3M19 12h3M5 5l2 2M17 17l2 2M5 19l2-2M17 7l2-2"/>',
    'unplug': '<path d="M9 7V3M15 7V3M7 7h10v4a5 5 0 0 1-10 0z"/><path d="M12 16v5M3 3l18 18"/>',
    'crown': '<path d="M3 18h18M4 16l-1-9 5 4 4-7 4 7 5-4-1 9z"/>',
})


# ------------------------------------------------------------------ 보드 한 장
def page31(name, title, body, w=1440, h=1000):
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
<style>{CSS}{V3CSS}{V31CSS}</style>
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


# ------------------------------------------------------------------ 그림
def pic(src, w, h, alt, style=''):
    if src:
        return f'<img src="{src}" alt="{alt}" style="width:{w}px;height:{h}px;display:block;object-fit:cover;{style}">'
    return (f'<div role="img" aria-label="{alt}" style="width:{w}px;height:{h}px;flex-shrink:0;border:1px dashed #5a625c;display:flex;align-items:center;'
            f'justify-content:center;padding:4px;font-size:10px;color:#8a8477;text-align:center;background:#141816;{style}">{alt}</div>')


def portrait(key, name, w=34, h=48):
    """초상. 그림이 없는 인물은 이름 첫 글자 판."""
    src = PT.get(key, '') if key else ''
    if src:
        return f'<img class="pt" src="{src}" alt="{name} 초상" style="width:{w}px;height:{h}px;flex-shrink:0">'
    return (f'<div role="img" aria-label="{name} — 초상 없음" style="width:{w}px;height:{h}px;flex-shrink:0;border:1px solid #3d4740;background:#232a26;display:flex;'
            f'align-items:center;justify-content:center;font-family:\'Noto Serif KR\',serif;font-weight:900;font-size:{max(12, w // 2)}px;color:#b9b2a3">{name[0]}</div>')


def mapimg(key, w, h, alt, left=0, top=0):
    """지도 그림(와룡전 원본 타일 렌더, 캔버스 blob). left/top 으로 잘라 보인다."""
    src = MAP.get(key, '')
    if src:
        return (f'<img src="{src}" alt="{alt}" style="position:absolute;left:{left}px;top:{top}px;width:{w}px;height:{h}px;display:block;'
                f'image-rendering:pixelated;max-width:none">')
    return (f'<div role="img" aria-label="{alt}" style="position:absolute;left:{left}px;top:{top}px;width:{w}px;height:{h}px;background:#1b2a22;'
            f'display:flex;align-items:center;justify-content:center;color:#8a8477;font-size:12px">{alt}</div>')


# ------------------------------------------------------------------ 메뉴 한 벌 v3.1 — 09-26 NAV + 09-30 정보 구조 결정(K0 · K6 §6 · K8 §2.1)
# (키, 레일 이름, 아이콘, [(화면, 새 경로, 지금 화면 → 308, 레인)])
NAV31 = [
    ('war', '작전실', 'war', [('지도 · 명령 목록 12순 · 지난 순 서랍', '/game/<서버>', '/game · /war-room · /map', 'K4'),
                            ('이번 순에 할 일 — 명령 흐름', '(작전실 옆 열 · 시트)', 'CommandModal', 'K6')]),
    ('retinue', '부', 'retinue', [('편성 · 결속 · 명망', '/retinue', '/retinue', 'K4'), ('인물 일람', '/retinue/people', '/generals · /rankings/*', 'K4'),
                                 ('인물 상세', '/retinue/people/[id]', '—', 'K4'), ('월단평', '/retinue/yuedan', '/yuedan', 'K4'),
                                 ('포로 · 등용', '/retinue/captives', '—', 'K4')]),
    ('stratagem', '계책', 'stratagem', [('계책 덱', '/stratagem', '/hand', 'K6'), ('역정보', '/stratagem/counter-intel', '—', 'K8')]),
    ('territory', '영지', 'territory', [('배치 · 방침 · 공사', '/territory', '/posts', 'K4'), ('현 상세', '/territory/county/[id]', '/city · /my-cities', 'K4'),
                                       ('군 내정 현황', '/territory/commandery/[id]', '—', 'K4'), ('창고망 · 보급', '/territory/supply', '/supply', 'K4')]),
    ('corps', '군단', 'corps', [('군단 · 세력 작전', '/corps', '—', 'K6'), ('공성', '/corps/siege', '/siege', 'K4'),
                               ('전투 — 내 전투 · 부재 대비', '/corps/battle', '/battle-center', 'K6'), ('전투 단계 — 참가 대기 · 배치 → 실시간', '/corps/battle/[id]', '—', 'K6'),
                               ('시야 · 첩보', '/corps/intel', '—', 'K6')]),
    ('court', '조정', 'court', [('발령 · 포상 · 조정 결정 · 천도', '/court', '/orders · /court', 'K4'), ('관직 · 봉신', '/court/offices', '—', 'K8'),
                               ('외교 · 외교 서신 · 주변 세계', '/court/diplomacy', '/global-diplomacy · 서신 외교 탭', 'K6 · K8'), ('참모 제안 · 회의', '/court/proposals', '—', 'K8'),
                               ('황실 · 인장 · 조서 · 칭제', '/court/imperial', '—', 'K8'), ('세력 정체성 · 제도 · 시설 · 편제', '/court/realm', '/my-nation', 'K8')]),
    ('records', '기록', 'records', [('기록 5분류', '/records', '/world-log', 'K5'), ('연감', '/records/yearbook', '/history · /rankings/kingdoms', 'K5'),
                                   ('리플레이', '/records/replay/[id]', '/battle-replay/[id]', 'K5'), ('천하 형세 · 통일 판정', '/records/unification', '—', 'K8'),
                                   ('시즌 결산', '/records/season', '—', 'K8')]),
    ('plaza', '광장', 'plaza', [('회의실 · 기밀실', '/council', '/board', 'K5'), ('서신 — 머리줄 서랍 · 전체 화면', '/mail', '/mailbox · MessagePanel', 'K6'),
                               ('커뮤니티', '게이트웨이 /board', '—', 'K5')]),
]
EXTRA31 = [('help', '도움말', 'help'), ('admin', '관리', 'admin')]
MTABS31 = [('war', '작전실'), ('retinue', '부'), ('stratagem', '계책'), ('records', '기록'), ('menu', '전체')]


def rail31(on='war'):
    items = ''.join(f'<a href="#" class="{"on" if k == on else ""}" aria-current="{"page" if k == on else "false"}">{icon(ic, 20)}<span>{n}</span></a>'
                    for k, n, ic, _ in NAV31)
    extra = ''.join(f'<a href="#">{icon(ic, 20)}<span>{n}</span></a>' for k, n, ic in EXTRA31)
    return f'<nav class="rail slim" aria-label="게임 메뉴">{items}<div style="flex-grow:1"></div>{extra}</nav>'


def tabbar31(on='war'):
    return '<nav class="tabbar" aria-label="게임 메뉴">' + ''.join(
        f'<a href="#" class="{"on" if k == on else ""}" aria-current="{"page" if k == on else "false"}">{icon(k, 22)}<span>{n}</span></a>' for k, n in MTABS31) + '</nav>'


def season_chip(label='봄 · 200년 3월 중순', dot=False, pressed=False):
    """계절 · 날짜 칩(SeasonChip, 누르는 44). 닫힌 길 · 내 영지 계절 사건이 있으면 점. 누르면 계절 패널(P-K07)."""
    d = '<span class="dot" style="background:#e08a7c" aria-label="새 계절 소식"></span>' if dot else ''
    st = 'border-color:#ffd36d;background:#232a26' if pressed else ''
    return (f'<button type="button" class="hchip" aria-haspopup="dialog" aria-expanded="{"true" if pressed else "false"}" style="{st}">'
            f'{icon("season", 16, "#d3b064")}<span>{label}</span>{d}</button>')


def topbar31(title, practice=False, season_dot=False, season_open=False, tablet=False):
    """머리줄 48. 누르는 것은 모두 44. 첫걸음 칩은 연습 서버에서만(K0 결정) — 본 서버는 도움말 서랍의 안내판."""
    nxt = '' if tablet else '<span class="chip">다음 개인 턴 21:40</span>'
    tut = ('<span class="chip info">연습 서버</span><a href="#" class="hchip" style="border-color:#4b6d87;color:#7aa7c7" data-guide="tutorial.chip">첫걸음 3 / 8</a>'
           if practice else '')
    return (f'<header style="height:48px;display:flex;align-items:center;justify-content:space-between;gap:12px;padding:0 8px 0 16px;border-bottom:1px solid #3d4740;'
            f'background:linear-gradient(180deg,#232a26,#1b201d);flex-shrink:0">'
            f'<div style="display:flex;align-items:center;gap:14px;min-width:0">{LOGO}<h1 class="serif" style="margin:0;font-size:18px;font-weight:900;white-space:nowrap">{title}</h1></div>'
            f'<div style="display:flex;align-items:center;gap:6px">{season_chip(dot=season_dot, pressed=season_open)}{nxt}{tut}'
            f'<button type="button" class="ibtn" aria-label="서신 2통 — 서신 서랍">{icon("mail")}<span class="badge">2</span></button>'
            f'<button type="button" class="ibtn" aria-label="이 화면 도움말">{icon("help")}</button>'
            f'<a href="#" class="hchip">하후돈 · 조조 소속</a><span class="chip bronze">명망 [미정]</span></div></header>')


def mtop31(title=None, back=None, season_dot=False):
    left = (f'<a href="#" class="ibtn" aria-label="{back}로 돌아가기">{icon("back")}</a><span class="serif" style="font-size:17px;font-weight:900;white-space:nowrap">{title}</span>'
            if back else f'{LOGO_M}{season_chip("봄 · 3월 중순", dot=season_dot)}')
    return (f'<header style="height:56px;flex-shrink:0;display:flex;align-items:center;justify-content:space-between;gap:8px;padding:0 8px 0 12px;border-bottom:1px solid #3d4740;background:#1b201d">'
            f'<div style="display:flex;align-items:center;gap:8px;min-width:0">{left}</div>'
            f'<div style="display:flex;gap:6px"><button type="button" class="ibtn" aria-label="서신 2통 — 서신 시트">{icon("mail")}<span class="badge">2</span></button>'
            f'<button type="button" class="ibtn" aria-label="이 화면 도움말">{icon("help")}</button></div></header>')


def shell_desk(title, on, main, band_html='', practice=False, season_dot=False, season_open=False):
    return (topbar31(title, practice, season_dot, season_open) + band_html
            + f'<div style="flex-grow:1;display:flex;min-height:0;position:relative">{rail31(on)}{main}</div>')


def shell_mob(main, on='war', title=None, back=None, band_html='', tabs=True):
    return mtop31(title, back) + band_html + main + (tabbar31(on) if tabs else '')


def pagehead(title, tabs=None, on=None, actions=''):
    t = subtabs(tabs, on) if tabs else ''
    return (f'<div style="height:56px;flex-shrink:0;display:flex;align-items:center;gap:16px;padding:0 16px;border-bottom:1px solid #2c342f">'
            f'<h2 class="serif" style="margin:0;font-size:20px;font-weight:900;white-space:nowrap">{title}</h2>{t}'
            f'<div style="margin-left:auto;display:flex;gap:8px">{actions}</div></div>')


def subtabs(tabs, on):
    return ('<nav aria-label="하위 화면" class="seg">' + ''.join(
        f'<a href="#" class="btn sm" aria-current="{"page" if t == on else "false"}" style="{"background:#d3b064;color:#161410;border-color:#9c7f3f;font-weight:700" if t == on else ""}">{t}</a>'
        for t in tabs) + '</nav>')


def mtabs_row(tabs, on):
    return ('<nav aria-label="하위 화면" style="height:60px;flex-shrink:0;display:flex;gap:6px;padding:8px 12px;overflow:hidden;border-bottom:1px solid #2c342f">'
            + ''.join(f'<a href="#" class="btn sm" aria-current="{"page" if t == on else "false"}" style="flex-shrink:0;'
                      f'{"background:#d3b064;color:#161410;border-color:#9c7f3f;font-weight:700" if t == on else ""}">{t}</a>' for t in tabs)
            + '</nav>')


# ------------------------------------------------------------------ 부품
def btn(label, kind='', ic=None, style='', href=None, attrs=''):
    """단추. kind = '' | 'primary' | 'danger' | 'sm'. 누르는 높이는 늘 44. 입력을 보내는 단추는 attrs 에 data-input-id."""
    inner = (icon(ic, 18) if ic else '') + label
    if href is not None:
        return f'<a class="btn {kind}" href="{href}" style="{style}" {attrs}>{inner}</a>'
    return f'<button type="button" class="btn {kind}" style="{style}" {attrs}>{inner}</button>'


def btn_off(label, reason, style='', input_id=''):
    """비활성 + 사유(ReasonTooltip): 흐리지 않고 점선. 둘 다 누를 수 있고, 누르면 사유(데스크톱 말풍선 · 모바일 하단 시트)."""
    a = f' data-input-id="{input_id}"' if input_id else ''
    return (f'<span style="display:inline-flex;align-items:center;gap:6px;{style}"><button type="button" class="btn off" aria-disabled="true" aria-haspopup="dialog"{a}>{label}</button>'
            f'<button type="button" class="why" aria-haspopup="dialog">{reason}</button></span>')


def input_btn(label, state='AVAILABLE', reason='', input_id='', kind='primary', style=''):
    """입력 단추(InputAction) — 가능 여부를 코드에 박지 않고 서버 응답으로만 그린다(K4 §1.2, K0 승인).
    AVAILABLE → 보통 · BLOCKED → 점선 + 누르면 사유 · NOT_DELIVERED(원장 PLANNED) → 점선 + 「준비 중」 · None(원장 행 없음) → 그리지 않음."""
    if state is None:
        return ''
    if state == 'AVAILABLE':
        return btn(label, kind, style=style, attrs=f'data-input-id="{input_id}"' if input_id else '')
    return btn_off(label, reason if state == 'BLOCKED' else '준비 중', style=style, input_id=input_id)


def ibtn(ic, label, badge=None, style=''):
    b = f'<span class="badge">{badge}</span>' if badge else ''
    return f'<button type="button" class="ibtn" aria-label="{label}" style="{style}">{icon(ic)}{b}</button>'


def chip(t, tone=''):
    return f'<span class="chip {tone}">{t}</span>'


def ok_chip(t='가능'):
    return f'<span class="chip moss">{t}</span>'


def why_tag(t):
    """목록 행 안의 사유 꼬리표(단추가 아니다 — 행 전체가 사유를 여는 단추다)."""
    return f'<span class="whyt">{t}</span>'


def why(t):
    """행 밖에서 쓰는 사유 단추(44)."""
    return f'<button type="button" class="why" aria-haspopup="dialog">{t}</button>'


def inp(value='', ph='', cls='', unit='', ic=None, style=''):
    v = value if value else f'<span class="ph">{ph}</span>'
    u = f'<span class="unit">{unit}</span>' if unit else ''
    i = icon(ic, 18, '#8a8477') if ic else ''
    return f'<div class="inp {cls}" style="{style}">{i}{v}{u}</div>'


def field(label, control, help='', err='', style=''):
    h = f'<span class="help">{help}</span>' if help else ''
    e = f'<span class="err" role="alert">{err}</span>' if err else ''
    return f'<div class="fld" style="{style}"><span class="lb">{label}</span>{control}{h}{e}</div>'


def search(ph, value='', style=''):
    return f'<div role="search" style="{style}">{inp(value, ph, ic="search")}</div>'


def seg(items, on, label='고르기', style='', vertical=False):
    out = ''
    for it in items:
        t, n = (it if isinstance(it, tuple) else (it, None))
        nn = f'<span class="n">{n}</span>' if n is not None else ''
        out += f'<button type="button" aria-pressed="{"true" if t == on else "false"}">{t}{nn}</button>'
    return f'<div role="group" aria-label="{label}" class="seg{" v" if vertical else ""}" style="{style}">{out}</div>'


def checkbox(t, on=False):
    return f'<label class="cb"><i class="{"on" if on else ""}">{icon("check", 14, "#161410") if on else ""}</i>{t}</label>'


def opt(name, sub='', end='', sel=False, no=False, lead='', h=52, attrs=''):
    """선택 목록 한 줄(OptionList). 불가 행은 aria-disabled + 누르면 사유 — 행 안에 단추를 넣지 않는다."""
    dis = ' aria-disabled="true" aria-haspopup="dialog"' if no else ''
    return (f'<button type="button" role="option" class="opt" aria-selected="{"true" if sel else "false"}"{dis} style="min-height:{h}px" {attrs}>'
            f'{lead}<span style="display:flex;flex-direction:column;min-width:0;gap:1px"><span class="nm">{name}</span>'
            f'{f"<span class=sub>{sub}</span>" if sub else ""}</span><span class="end">{end}</span></button>')


def pop(title, body, recovery='', help_topic='', style=''):
    """사유 말풍선(ReasonTooltip 데스크톱). 받는 값: reason · code? · inputId? · title? · recovery?(K7) · onHelp?(K7)."""
    rec = (f'<span style="display:flex;flex-direction:column;gap:2px;padding:6px 8px;margin-right:8px;background:#141816"><span class="bz" style="font-size:11.5px;font-weight:700">이렇게 하면 됩니다</span>'
           f'<span class="t2" style="font-size:12px;line-height:1.5">{recovery}</span></span>') if recovery else ''
    hl = (f'<div style="display:flex;justify-content:space-between;align-items:center"><a href="#" style="font-size:12px;min-height:44px;display:inline-flex;align-items:center">도움말 — {help_topic} →</a>'
          f'{ibtn("close", "닫기", style="border:0;background:transparent")}</div>') if help_topic else ''
    return (f'<div class="pop" role="dialog" aria-label="{title}" style="{style}"><span class="rs" style="font-size:13px;font-weight:700;padding-right:8px">{title}</span>'
            f'<span class="t2" style="font-size:12px;line-height:1.5;padding-right:8px">{body}</span>{rec}{hl}</div>')


def sheet(title, body, top=None, height=None, close=True, foot='', bottom=0):
    """모바일 하단 시트(BottomSheet). 높이 3단: 살짝 124 · 반 · 가득(머리줄 아래까지). 끌기 없이 단추로도 열고 닫는다."""
    pos = f'top:{top}px;bottom:{bottom}px' if top is not None else f'bottom:{bottom}px;height:{height}px'
    c = ibtn('close', '시트 닫기', style='border:0;background:transparent') if close else ''
    f = f'<div style="margin-top:auto;padding:8px 12px;display:flex;gap:8px;border-top:1px solid #2c342f;flex-shrink:0">{foot}</div>' if foot else ''
    return (f'<section class="sheet" role="dialog" aria-label="{title}" style="{pos}"><div class="grip"></div>'
            f'<div style="height:44px;flex-shrink:0;display:flex;align-items:center;justify-content:space-between;padding:0 4px 0 16px">'
            f'<span class="serif" style="font-size:17px;font-weight:900">{title}</span>{c}</div>{body}{f}</section>')


def dialog(title, body, foot, w=480, style=''):
    return (f'<section class="dlg" role="dialog" aria-modal="true" aria-label="{title}" style="width:{w}px;{style}">'
            f'<div style="height:52px;flex-shrink:0;display:flex;align-items:center;justify-content:space-between;padding:0 4px 0 16px;border-bottom:1px solid #2c342f">'
            f'<span class="serif" style="font-size:17px;font-weight:900">{title}</span>{ibtn("close", "닫기", style="border:0;background:transparent")}</div>'
            f'{body}<div style="padding:12px 16px;display:flex;gap:8px;justify-content:flex-end;border-top:1px solid #2c342f">{foot}</div></section>')


def toast(t, kind='ok', action=''):
    ic = icon('check', 18, '#8fa77a') if kind == 'ok' else icon('alert', 18, '#e08a7c')
    return f'<div class="toast {kind}" role="status">{ic}<span>{t}</span><span style="margin-left:auto;display:flex">{action}</span></div>'


def tbl(heads, rows, style=''):
    th = ''.join(f'<th>{h}</th>' for h in heads)
    tr = ''.join('<tr>' + ''.join(f'<td>{c}</td>' for c in r) + '</tr>' for r in rows)
    return f'<table class="table" style="{style}"><thead><tr>{th}</tr></thead><tbody>{tr}</tbody></table>'


def help_strip(text, draft=True):
    """도움말 띠(HelpStrip, K7 내용) — 결정 화면 인자 패널 제목 바로 아래 44. 설명 한 문장 · 잘 되면 · 안 되면 · 초안."""
    d = chip('초안', 'info') if draft else ''
    return (f'<div class="hstrip">{icon("help", 16, "#7aa7c7")}<span style="flex:1;min-width:0">{text}</span>{d}'
            f'<button type="button" class="btn sm" aria-expanded="false" style="background:transparent;border:0;color:#7aa7c7">잘 되면 · 안 되면</button></div>')


# ------------------------------------------------------------------ 상태(P-X01) — StatusView kind 하나로
def _state(ic, color, title, body, actions='', extra='', pad=16):
    return (f'<div style="flex-grow:1;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:8px;padding:{pad}px;text-align:center">'
            f'<span style="width:44px;height:44px;flex-shrink:0;display:inline-flex;align-items:center;justify-content:center;border:1px solid {color}">{icon(ic, 22, color)}</span>'
            f'<span class="serif" style="font-size:16px;font-weight:900">{title}</span><span class="t2" style="font-size:12.5px;line-height:1.55;max-width:340px">{body}</span>'
            f'{extra}{f"<div style=display:flex;gap:8px;flex-wrap:wrap;justify-content:center>{actions}</div>" if actions else ""}</div>')


def state_loading(rows=3):
    sk = ''.join(f'<div style="height:44px;flex-shrink:0;display:flex;align-items:center;gap:10px;padding:0 12px;border-bottom:1px solid #2c342f">'
                 f'<span class="skel" style="width:24px;height:34px"></span><span class="skel" style="width:{120 - 20 * (i % 2)}px;height:12px"></span>'
                 f'<span class="skel" style="width:60px;height:12px;margin-left:auto"></span></div>' for i in range(rows))
    return (f'<div aria-busy="true" style="display:flex;flex-direction:column">{sk}'
            f'<span class="muted" style="font-size:12px;padding:10px 12px">불러오는 중…</span></div>')


def state_empty(title, body, actions='', pad=16):
    return _state('list', '#8a8477', title, body, actions, pad=pad)


def state_error(title, body='잠시 뒤 다시 해 보세요. 계속되면 오류 번호를 알려 주세요.', pad=16):
    extra = (f'<button type="button" class="btn sm" aria-label="오류 번호 복사" style="gap:6px">{icon("copy", 16)}<span class="mono">오류 번호 [값]</span></button>')
    return _state('alert', '#e08a7c', title, body, btn('다시 시도', 'primary', 'refresh'), extra, pad=pad)


def state_denied(title, how, topic, pad=16):
    return _state('lock', '#e08a7c', title, f'<span class="bz" style="font-weight:700">이렇게 하면 됩니다</span><br>{how}',
                  btn(f'도움말 — {topic}', '', 'help', href='#'), pad=pad)


def state_waiting(title, body='이 화면에 보일 내용을 서버가 아직 주지 않습니다. 준비되면 이 자리에 바로 보입니다.', pad=16):
    """서버 대기 A — 읽기가 아직 없다(영역 전체)."""
    return _state('clock', '#7aa7c7', title, body, '', '<span class="chip info">준비 중</span>', pad=pad)


def state_stale(title='연결이 끊겼습니다', pad=16):
    return _state('unplug', '#d3b064', title, '마지막으로 받은 자료를 보이는 중입니다(3월 중순 21:40). 다시 이어지면 저절로 새로 고칩니다.',
                  btn('지금 다시 잇기', '', 'refresh'), pad=pad)


def state_notfound(pad=16):
    return _state('back', '#8a8477', '찾는 화면이 없습니다', '주소가 바뀌었거나 없어진 화면입니다. 옛 주소는 새 화면으로 저절로 넘어갑니다.',
                  btn('작전실로', 'primary', 'war', href='#') + btn('기록으로', '', 'records', href='#'), pad=pad)


# ------------------------------------------------------------------ 알림 띠(P-W05, NoticeBand) — 머리줄 바로 아래 한 줄, 한 번에 하나
BANDS = {
    'catch': ('catch', 'clock', '#d3b064', '<b>따라잡는 중</b> — 서버가 멈췄던 동안 밀린 순을 2배 빠르기로 돌립니다. 다 따라잡는 때 <span class="mono">21:40</span>', '자세히'),
    'stop': ('stop', 'alert', '#e08a7c', '<b>턴이 멈췄습니다</b> — 마지막 순 3월 중순 <span class="mono">21:40</span>. 운영진이 살피는 중입니다. 예약은 그대로 남습니다', '상태 보기'),
    'notice': ('notice', 'tools', '#7aa7c7', '<b>점검 예정</b> — 오늘 <span class="mono">22:00</span>부터 [미정]분. 그동안 턴이 돌지 않습니다', '공지 보기'),
    'tutorial': ('tutorial', 'check', '#7aa7c7', '<b>첫걸음 3 / 8 달성</b> — 첫 발령을 마쳤습니다. 다음: 첫 공사', '다음 보기'),
}
BAND_ORDER = '점검 중(전체 화면) > 턴 정지 > 따라잡기 > 점검 예고 > 첫걸음 달성(6초 뒤 접힘)'


def band(kind, mobile=False):
    cls, ic, c, text, act = BANDS[kind]
    if mobile:
        return (f'<div class="band {cls}" role="status" style="height:72px;align-items:flex-start;padding:8px 4px 8px 12px;gap:8px">{icon(ic, 18, c)}'
                f'<span style="font-size:12px;line-height:1.45;flex:1;min-width:0">{text}</span>'
                f'<button type="button" class="btn sm" style="flex-shrink:0;background:transparent">{act}</button></div>')
    return (f'<div class="band {cls}" role="status">{icon(ic, 18, c)}<span>{text}</span>'
            f'<button type="button" class="btn sm" style="margin-left:auto;background:transparent">{act}</button></div>')


# ------------------------------------------------------------------ 명령 흐름(P-W02 부품 — K6 와 맞춤)
TURNS = [(1, '3월 중순', '21:40', '농지개간', 'done'), (2, '3월 하순', '22:40', '훈련', 'res'), (3, '4월 상순', '23:40', '징병', 'warn'),
         (4, '4월 중순', '00:40', '', 'empty'), (5, '4월 하순', '01:40', '등용', 'res'), (6, '5월 상순', '02:40', '결의', 'res'),
         (7, '5월 중순', '03:40', '', 'empty'), (8, '5월 하순', '04:40', '', 'empty'), (9, '6월 상순', '05:40', '', 'empty'),
         (10, '6월 중순', '06:40', '', 'empty'), (11, '6월 하순', '07:40', '', 'empty'), (12, '7월 상순', '08:40', '', 'empty')]
TURN_WORD = {'done': '실행됨', 'res': '예약', 'warn': '예약 · 경고', 'empty': '빈 순'}


def turn_strip(cur=4, n=12, cols=6, gap=4, cell_w=None):
    """순 띠(TurnStrip) — 칸 = 순 번호 · 날짜 · 명령 이름(또는 빈 순). 데스크톱 2줄 × 6칸, 모바일은 가로로 밀리는 한 줄(cell_w 고정).
    누르면 그 순을 고른다: 빈 순 = 새로 예약, 찬 순 = 바꾸기 · 비우기 · 당기기 · 밀기. 끌기 없이 탭으로 다 된다."""
    cells = ''
    for no, d, t, c, st in TURNS[:n]:
        lab = f'{no:02d}순 {d} {t} — {c + " " if c else ""}{TURN_WORD[st]}'
        w = f'width:{cell_w}px;flex-shrink:0' if cell_w else ''
        cells += (f'<button type="button" class="slot {st}" aria-label="{lab}" aria-current="{"true" if no == cur else "false"}" style="{w}">'
                  f'<span class="d">{no:02d} · {d}</span><span class="c">{c or "빈 순"}</span></button>')
    lay = f'display:flex;gap:{gap}px' if cell_w else f'display:grid;grid-template-columns:repeat({cols},minmax(0,1fr));gap:{gap}px'
    return f'<div role="group" aria-label="순 띠 — 몇 번째 순에 넣을지" style="{lay}">{cells}</div>'


def turn_caption(cur=4):
    no, d, t, c, st = TURNS[cur - 1]
    what = f'<span class="serif" style="font-weight:700">{c}</span> {chip(TURN_WORD[st], "bronze")}' if c else chip('빈 순')
    return (f'<div style="display:flex;align-items:center;gap:8px;font-size:13px"><span class="mono bz" style="font-weight:700">{no:02d}순</span>'
            f'<span class="t2">{d} · <span class="mono">{t}</span></span>{what}</div>')


CMD_CATS = ['전체', '내정', '군사', '이동', '인물', '개인', '나라', '물자']  # K6 안 — 정본은 입력 원장 kind · phase 에서 K6 가 정한다


def cat_tabs(on='이동', drafts=('군사',), style='', scroll=False):
    """명령 분류 탭. 적는 중인 명령이 있는 분류에는 점."""
    out = ''
    for c in CMD_CATS:
        d = '<span class="dot" style="background:#ffd36d" aria-label="적는 중인 명령 있음"></span>' if c in drafts else ''
        fl = 'flex-shrink:0' if scroll else 'flex:1 1 0;padding:0 6px'
        out += f'<button type="button" role="tab" aria-selected="{"true" if c == on else "false"}" aria-pressed="{"true" if c == on else "false"}" style="{fl}">{c}{d}</button>'
    return f'<div role="tablist" aria-label="명령 분류" class="seg" style="{"overflow:hidden;" if scroll else ""}{style}">{out}</div>'


def cmd_row(name, sub, state='ok', reason='', sel=False, draft=False, h=44, input_id=''):
    """명령 목록 한 줄(CommandList 행 44) — 이름 · 한 줄 효과 · 상태: 가능 / 불가(점선 꼬리표, 누르면 사유) / 준비 중(서버 PLANNED)."""
    end = '<span class="chip" style="height:20px;border-color:#9c7f3f;color:#ffd36d">적는 중</span>' if draft else ''
    end += ok_chip() if state == 'ok' else (chip('준비 중', 'info') if state == 'wait' else why_tag(reason))
    a = f'data-input-id="{input_id}"' if input_id else ''
    return opt(name, sub, end, sel=sel, no=(state != 'ok'), h=h, attrs=a)


def target_field(label, value, sub, picking=True):
    """장소 칸(PlaceField): 값 + 「지도에서 고르기」. 누르면 지도가 대상 고르기 모드(MapTargetPicker)가 되고, 같은 후보를 목록 · 찾기로도 고른다."""
    b = (f'<button type="button" class="btn" aria-pressed="{"true" if picking else "false"}" style="width:100%;{"border-color:#ffd36d;color:#ffd36d" if picking else ""}">'
         f'{icon("target", 18)}{"지도에서 고르는 중" if picking else "지도에서 고르기"}</button>')
    return field(label,
                 f'<div class="inp" style="border-color:#ffd36d">{icon("target", 18, "#ffd36d")}'
                 f'<span class="serif" style="font-weight:700">{value}</span><span class="muted" style="font-size:12px">{sub}</span></div>{b}')


# ------------------------------------------------------------------ 지도 부품 — 대상 고르기 · 내 위치 · 보기 단추
def mk(x, y, kind='ok', label='', lab_cls='', n=None):
    """지도 대상 표지(44) + 이름표(표지 아래). kind = ok | no | sel | corps. n = 여러 개 고를 때 고른 차례."""
    inner = label[0] if kind == 'corps' else ''
    if n is not None:
        inner += f'<span class="n">{n}</span>'
    aria = {'ok': '고를 수 있음', 'no': '고를 수 없음 — 누르면 이유', 'sel': '고른 곳', 'corps': '군단'}[kind]
    lab = f'<span class="mlab {lab_cls}" style="left:{x}px;top:{y + 26}px">{label}</span>' if label and kind != 'corps' else ''
    return f'<button type="button" class="mk {kind}" aria-label="{label} — {aria}" style="left:{x}px;top:{y}px">{inner}</button>{lab}'


def mlab(t, x, y, big=False, dim=True):
    return f'<span class="mlab{" big" if big else ""}" style="left:{x}px;top:{y}px;{"opacity:.75" if dim else ""}">{t}</span>'


def cand_row(name, sub, dist_, state='ok', reason='', sel=False, here=False, h=48):
    """대상 후보 한 줄(TargetCandidateList) — 지도 표지와 같은 상태 · 같은 사유."""
    if here:
        end = chip('지금 자리', 'bronze')
    elif state == 'ok':
        end = f'<span class="mono muted" style="font-size:11px">{dist_}</span>{ok_chip()}'
    else:
        end = f'<span class="mono muted" style="font-size:11px">{dist_}</span>{why_tag(reason)}'
    lead = (f'<span class="dot" style="background:{"#8fa77a" if state == "ok" and not here else ("#d3b064" if here else "transparent")};'
            f'outline:{"none" if state == "ok" or here else "1px dashed #e08a7c"}"></span>')
    return opt(name, sub, end, sel=sel, no=(state != 'ok' and not here), lead=lead, h=h)


def pick_bar(title, sub, right=0, mobile=False, done=''):
    """고르기 띠 — 무엇을 고르는지 · 후보 수 · 그만(Esc). 지도 맨 위(--z-map-ctrl)."""
    if mobile:
        return (f'<div style="position:absolute;left:8px;right:8px;top:8px;display:flex;align-items:center;gap:6px;padding:0 4px 0 10px;min-height:56px;'
                f'background:rgba(27,32,29,.97);border:1px solid #ffd36d">{icon("target", 18, "#ffd36d")}'
                f'<span style="display:flex;flex-direction:column;min-width:0;flex:1"><span class="serif" style="font-weight:900;font-size:14px">{title}</span>'
                f'<span class="muted" style="font-size:11px">{sub}</span></span>'
                f'<button type="button" class="btn sm">{icon("list", 16)}목록</button><button type="button" class="ibtn" aria-label="그만 고르기" style="border:0;background:transparent">{icon("close")}</button></div>')
    return (f'<div style="position:absolute;left:12px;right:{12 + right}px;top:12px;display:flex;align-items:center;gap:8px;padding:0 6px 0 12px;min-height:52px;'
            f'background:rgba(27,32,29,.97);border:1px solid #ffd36d">{icon("target", 20, "#ffd36d")}'
            f'<span style="display:flex;flex-direction:column;min-width:0"><span class="serif" style="font-weight:900;font-size:15px">{title}</span>'
            f'<span class="muted" style="font-size:11.5px">{sub}</span></span><span style="margin-left:auto;display:flex;gap:6px">{done}'
            f'<button type="button" class="btn sm">그만 고르기 <span class="mono muted">Esc</span></button></span></div>')


def path_line(x1, y1, x2, y2, w, h, label=''):
    """내 위치 → 고른 곳 점선(금색) + 거리 꼬리표."""
    mx, my = (x1 + x2) // 2, (y1 + y2) // 2
    lab = (f'<span class="mono" style="position:absolute;left:{mx}px;top:{my}px;transform:translate(-50%,-50%);height:22px;padding:0 6px;display:inline-flex;align-items:center;'
           f'font-size:11px;font-weight:700;color:#161410;background:#ffd36d;white-space:nowrap">{label}</span>') if label else ''
    return (f'<svg width="{w}" height="{h}" style="position:absolute;left:0;top:0;pointer-events:none" aria-hidden="true">'
            f'<path d="M{x1} {y1} L{x2} {y2}" stroke="#ffd36d" stroke-width="3" stroke-dasharray="8 6" fill="none"></path></svg>{lab}')


def view_bar(on='현', style='left:12px;bottom:12px', lod=True):
    """지도 보기 단추(MapViewBar) — 왼쪽 아래 세로 줄: 보기 수준 주 · 군 · 현 → 확대 · 축소 → 내 위치로. 모두 44."""
    b = 'background:rgba(20,24,22,.92)'
    lv = seg(['주', '군', '현'], on, '보기 수준', vertical=True) if lod else ''
    return (f'<div style="position:absolute;{style};display:flex;flex-direction:column;gap:8px">{lv}'
            f'<div style="display:flex;flex-direction:column;gap:2px"><button type="button" class="ibtn" aria-label="확대" style="{b};font-size:20px">+</button>'
            f'<button type="button" class="ibtn" aria-label="축소" style="{b};font-size:20px">−</button></div>{ibtn("war", "내 위치로(Home)", style=b)}</div>')


ME_STATE = {'in': '성 안', 'out': '성 밖', 'corps': '군단과 함께', 'move': '이동 중'}


def me_marker(x, y, state='in', name='하후돈', ring=NATION['조조'], tag=True, dest=None):
    """내 위치 표지(MyLocationMarker) — 내 장수 초상 핀 + 국가색 링(ADR-049 규칙 4). (x, y) = 핀 끝 = 실제 자리.
    모든 보기 수준에서 같은 화면 크기, 이름표 · 성 · 깃발보다 위. 누르면 내 장수 카드. K0 원안(boards_v3_bundle1_rev.me_marker)을 잇는다."""
    pt = PT.get('hahoudon', '')
    face = (f'<img src="{pt}" alt="" style="width:100%;height:100%;object-fit:cover;object-position:top center;display:block">' if pt
            else f'<span style="display:flex;width:100%;height:100%;align-items:center;justify-content:center;font-family:\'Noto Serif KR\',serif;font-weight:900;font-size:20px;color:#ece6d8">{name[0]}</span>')
    t = ''
    if tag:
        sub = ME_STATE[state] + (f' → {dest}' if state == 'move' and dest else '')
        t = (f'<span style="position:absolute;left:52px;top:10px;height:24px;padding:0 8px;display:inline-flex;align-items:center;gap:4px;font-size:12px;font-weight:700;'
             f'color:#161410;background:#ffd36d;border:1px solid #9c7f3f;white-space:nowrap">내 위치 · {sub}</span>')
    badge = ''
    if state == 'corps':
        badge = (f'<span aria-hidden="true" style="position:absolute;left:34px;top:30px;width:20px;height:16px;background:{ring};border:1px solid #0c0f0e;'
                 f'clip-path:polygon(0 0,100% 0,75% 50%,100% 100%,0 100%)"></span>')
    return (f'<a href="#" aria-label="내 위치 — {name}, {ME_STATE[state]}. 누르면 내 장수 카드" style="position:absolute;left:{x - 24}px;top:{y - 62}px;width:48px;height:62px;display:block">'
            f'<span style="position:absolute;left:0;top:0;width:48px;height:48px;border-radius:50%;border:3px solid {ring};box-shadow:0 0 0 2px #ffd36d,0 4px 12px rgba(0,0,0,.6);overflow:hidden;background:#141816">{face}</span>'
            f'<span style="position:absolute;left:18px;top:46px;width:0;height:0;border-left:6px solid transparent;border-right:6px solid transparent;border-top:14px solid #ffd36d"></span>'
            f'{badge}{t}</a>')


def me_edge(side, pos, dist_='', ring=NATION['조조']):
    """내 위치가 화면 밖일 때(MyLocationEdge) — 그 방향 가장자리의 화살표 단추(44 × 52). 누르면 그리로."""
    rot = {'left': 90, 'right': -90, 'top': 180, 'bottom': 0}[side]
    place = {'left': f'left:4px;top:{pos}px', 'right': f'right:4px;top:{pos}px', 'top': f'top:4px;left:{pos}px', 'bottom': f'bottom:4px;left:{pos}px'}[side]
    d = f'<span class="mono" style="font-size:10px;color:#161410">{dist_}</span>' if dist_ else ''
    return (f'<button type="button" aria-label="내 위치는 화면 밖 — 누르면 그리로" style="position:absolute;{place};min-width:44px;height:52px;padding:2px 6px;display:flex;'
            f'flex-direction:column;align-items:center;justify-content:center;gap:1px;font:inherit;background:#ffd36d;border:2px solid {ring};cursor:pointer">'
            f'<span style="display:inline-flex;transform:rotate({rot}deg)">{icon("arrow", 18, "#161410")}</span>'
            f'<span style="font-size:11px;font-weight:700;color:#161410;white-space:nowrap">내 위치</span>{d}</button>')


def me_card(state='in', place='양적현', sub='영천군', style=''):
    """내 장수 카드(MyGeneralCard) — 표지를 누르면. 데스크톱 떠 있는 카드 · 모바일 하단 시트."""
    return (f'<section class="panel" aria-label="내 장수" style="width:320px;background:rgba(27,32,29,.97);box-shadow:0 10px 28px rgba(0,0,0,.5);{style}">'
            f'<div style="display:flex;gap:10px;padding:10px 4px 10px 12px;border-bottom:1px solid #2c342f">{portrait("hahoudon", "하후돈", 34, 48)}'
            f'<div style="display:flex;flex-direction:column;min-width:0;flex:1;gap:2px"><span class="serif" style="font-size:16px;font-weight:900">하후돈</span>'
            f'<span class="muted" style="font-size:11.5px">조조 소속 · {place} · {sub}</span></div>{ibtn("close", "닫기", style="border:0;background:transparent")}</div>'
            f'<div style="display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:6px;padding:8px">{kv("자리", ME_STATE[state], "bz")}{kv("귀환 성", "장사현")}{kv("다음 개인 턴", "21:40")}</div>'
            f'<div style="display:flex;gap:6px;padding:0 8px 8px">{btn("이번 순에 할 일", "primary", style="flex:1", href="#")}{btn("장수 상세", "", style="flex:1", href="#")}</div></section>')


# ------------------------------------------------------------------ 사람 고르기(PeoplePicker) — 서신 받는 사람 · 발령 · 포상 · 등용 · 증여 · 선양 · 결의 …
PEOPLE = [  # (초상 키, 이름, 사람|NPC, 소속, 묶음, 자리)  — 자리 「—」 = 시야 밖
    ('sunuk', '순욱', '사람', '조조 소속', '소속 세력', '허현'),
    ('jojo', '조조', 'NPC', '조조 · 군주', '소속 세력', '허현'),
    ('heojeo', '허저', 'NPC', '내 부', '내 부', '장사현'),
    ('ijeon', '이전', 'NPC', '내 부', '내 부', '장사현'),
    ('join', '조인', 'NPC', '조조 소속', '소속 세력', '여남군'),
    ('', '원소', 'NPC', '원소 · 군주', '다른 세력 군주', '—'),
    ('', '유표', 'NPC', '유표 · 군주', '다른 세력 군주', '—'),
]
PGROUPS = [('내 부', 2), ('소속 세력', 4), ('다른 세력 군주', 2), ('전체', 7)]


def person_row(key, name, kind, aff, where, sel=False, off_reason='', h=60):
    """사람 한 줄(PersonRow) — 초상 · 이름 · 「사람」 칩(사람 장수만, K6) · 소속(국가색 점) · 자리 · 고름/불가 사유."""
    nat = '원소' if '원소' in aff else ('유표' if '유표' in aff else '조조')
    sw = f'<i class="dot" style="background:{NATION[nat]}"></i>'
    kchip = chip('사람', 'info') if kind == '사람' else ''
    wh = f'자리 {where}' if where != '—' else '자리 모름(시야 밖)'
    end = why_tag(off_reason) if off_reason else (chip('고름', 'bronze') if sel else '')
    dis = ' aria-disabled="true" aria-haspopup="dialog"' if off_reason else ''
    return (f'<button type="button" role="option" class="opt" aria-selected="{"true" if sel else "false"}"{dis} style="min-height:{h}px">'
            f'{portrait(key, name, 30, 42)}<span style="display:flex;flex-direction:column;min-width:0;gap:2px">'
            f'<span style="display:flex;align-items:center;gap:6px"><span class="nm">{name}</span>{kchip}</span>'
            f'<span class="sub" style="display:flex;align-items:center;gap:5px">{sw}{aff} · {wh}</span></span><span class="end">{end}</span></button>')


def people_picker(on='전체', rows=None, sel='순욱', off=None, q='', mobile=False):
    """사람 고르기: 찾기(이름 · 초성) + 묶음(내 부 · 소속 세력 · 다른 세력 군주 · 전체). NPC 포함(D4). 빈 목록 · 찾기 없음 · 실패를 따로 보인다."""
    rows = PEOPLE if rows is None else rows
    off = off or {}
    groups = seg(PGROUPS, on, '묶음', style='flex-wrap:nowrap;overflow:hidden')
    body = ''.join(person_row(k, n, kd, a, w, sel=(n == sel), off_reason=off.get(n, ''), h=56 if mobile else 60) for k, n, kd, a, g, w in rows)
    return (f'<div style="display:flex;flex-direction:column;gap:8px;padding:10px 12px 0">{search("이름 · 초성으로 찾기 — 예: ㅅㅇ", q)}{groups}</div>'
            f'<div role="listbox" aria-label="사람 목록" style="display:flex;flex-direction:column;margin-top:8px;border-top:1px solid #2c342f">{body}</div>')


# ------------------------------------------------------------------ 계절 패널(P-K07 자리 — 내용은 K8 명세, 1년 36순 · 봄 3–5 · 여름 6–8 · 가을 9–11 · 겨울 12–2)
SEASONS = [('겨울', 1, 2), ('봄', 3, 5), ('여름', 6, 8), ('가을', 9, 11), ('겨울', 12, 12)]


def season_calendar(month=3, phase=1):
    """1월 상순부터 36칸. 지난 순 · 지금 순 · 남은 순. 계절 경계에 이름. 서버 없이 그릴 수 있는 달력."""
    now = (month - 1) * 3 + phase
    cells = ''.join(f'<i class="{"now" if k == now else ("past" if k < now else "")}"></i>' for k in range(36))
    labs = ''.join(f'<span style="flex:{(b - a + 1) * 3} 1 0;font-size:11px;border-left:1px solid #5a625c;padding-left:4px;white-space:nowrap;overflow:hidden" class="{"bz" if a <= month <= b else "muted"}">{n}</span>'
                   for n, a, b in SEASONS)
    return (f'<div role="img" aria-label="1년 36순 달력 — 지금 {month}월 {["상", "중", "하"][phase]}순" style="display:flex;flex-direction:column;gap:4px">'
            f'<div style="display:flex">{labs}</div><div class="cal">{cells}</div>'
            f'<div style="display:flex;justify-content:space-between" class="mono muted"><span style="font-size:10.5px">1월</span><span style="font-size:10.5px">12월</span></div></div>')


def season_panel(mobile=False):
    left = 5 * 3 - ((3 - 1) * 3 + 2)  # 5월 하순까지 남은 순(지금 3월 중순 제외) — 달력 계산
    body = (f'<div style="padding:{"0 16px" if mobile else "12px"};display:flex;flex-direction:column;gap:12px">{season_calendar()}'
            f'<span class="t2" style="font-size:12.5px"><b class="bz">봄</b> — 5월 하순까지 {left}순 남았습니다. 다음은 여름(6–8월).</span>'
            f'<div class="inset" style="display:flex;flex-direction:column">'
            f'<div style="padding:8px 10px;border-bottom:1px solid #2c342f;display:flex;justify-content:space-between;align-items:center"><span style="font-size:12.5px">이번 계절에 닫힌 길</span>{chip("준비 중", "info")}</div>'
            f'<div style="padding:8px 10px;display:flex;justify-content:space-between;align-items:center"><span style="font-size:12.5px">내 영지 계절 사건</span>{chip("준비 중", "info")}</div></div>'
            f'<span class="muted" style="font-size:11.5px;line-height:1.5">가뭄 · 홍수 · 역병 · 황충 · 결빙 · 우기 통행. 사건 읽기는 서버 대기(C5) — 준비되면 이 자리에 현 · 사건 · 방향(▼ 민심 등)이 보인다.</span>'
            f'{btn("기록에서 계절 소식 보기", "", "records", style="width:100%", href="#")}</div>')
    if mobile:
        return body
    return (f'<section class="panel" role="dialog" aria-label="계절" style="position:absolute;right:430px;top:4px;width:400px;background:#1b201d;border-color:#9c7f3f;box-shadow:0 10px 28px rgba(0,0,0,.5)">'
            f'<div style="height:44px;display:flex;align-items:center;justify-content:space-between;padding:0 4px 0 12px;border-bottom:1px solid #2c342f">'
            f'<span class="serif" style="font-size:16px;font-weight:900">계절 — 봄</span>{ibtn("close", "닫기", style="border:0;background:transparent")}</div>{body}</section>')


# ------------------------------------------------------------------ 부품 목록(id) — 설계 레인은 이 id 로 부른다. 코드 이름 = 제품 React 부품 이름.
# (묶음, id, 한글 이름, 크기 · 자리, 상태 · 변형, 제품의 지금 짝, 담당)
PARTS = [
    ('셸', 'AppHeader', '머리줄', '48 · 모바일 56', '계절 칩 · 다음 개인 턴 · 연습 서버 · 서신 · 도움말 · 나 · 명망', '새로', 'K3'),
    ('셸', 'GameRail', '레일', '56', '작전실 + 6묶음 + 광장 | 도움말 · 관리', 'NavItem', 'K3'),
    ('셸', 'MobileTabBar', '하단 탭', '64', '작전실 · 부 · 계책 · 기록 · 전체', '새로', 'K3'),
    ('셸', 'PageHead', '페이지 머리 + 하위 탭', '56 · 모바일 탭 60', '묶음 이름 · 하위 화면 · 행동', 'SectionHeader', 'K3'),
    ('셸', 'NoticeBand', '알림 띠(P-W05)', '44 · 모바일 72', '따라잡기 · 턴 정지 · 점검 예고 · 첫걸음 달성', '새로', 'K3'),
    ('셸', 'HelpDrawer', '도움말 서랍 자리', '400 · 태블릿 360 · 모바일 724', '?help= · 모달 아님 · 지도 648', '새로', 'K3 자리 · K7 내용'),
    ('셸', 'MailDrawer', '서신 서랍 자리', '도움말 서랍과 같은 자리', '받은 · 보낸 · 쓰기(PeoplePicker)', 'MessagePanel', 'K3 자리 · K6 내용'),
    ('셸', 'SeasonChip', '계절 칩 → 패널', '칩 44 · 패널 400 · 모바일 시트', '소식 점 · 서버 대기', '새로', 'K3 자리 · K8 내용'),
    ('기본', 'Button', '단추', '44', '주 · 기본 · 위험 · 작게(44)', 'Button', 'K3'),
    ('기본', 'InputAction', '입력 단추', '44', 'AVAILABLE · BLOCKED(사유) · NOT_DELIVERED(준비 중) · 행 없음(안 그림)', '새로', 'K3'),
    ('기본', 'IconButton', '아이콘 단추', '44 × 44', '배지', '새로', 'K3'),
    ('기본', 'ReasonTooltip', '비활성 + 사유', '말풍선 320 · 모바일 시트 340', 'reason · code? · inputId? · title? · recovery? · onHelp?', 'ReasonTooltip', 'K3 · K7'),
    ('기본', 'Chip', '꼬리표', '20(누르지 않음)', '청동 · 이끼 · 적갈 · 청 · 기본', 'Chip', 'K3'),
    ('기본', 'Segmented', '분할 선택', '44', '가로 · 세로 · 수 붙임', 'PillTabs', 'K3'),
    ('기본', 'Field', '입력칸 틀', '이름표 + 44 + 도움말 · 오류', '글 · 수(단위) · 찾기 · 긴 글 · 오류', '새로', 'K3'),
    ('기본', 'OptionList', '선택 목록', '행 44 / 52', '고름 · 불가(점선 꼬리표) · 지금 자리', '새로', 'K3'),
    ('기본', 'Checkbox', '체크', '44', '', '새로', 'K3'),
    ('기본', 'ConfirmDialog', '확인 대화', '424–480', '되돌릴 수 없는 일에만', 'ConfirmDialog', 'K3'),
    ('기본', 'BottomSheet', '하단 시트', '살짝 124 · 반 · 가득', '손잡이 + 닫기 단추', '새로', 'K3'),
    ('기본', 'Toast', '알림 토스트', '48', '성공 4초 · 실패는 닫을 때까지 · 되돌리기', '새로', 'K3'),
    ('기본', 'DataTable', '표 → 카드', '행 44', '모바일 카드, 정렬 · 거르기 유지', 'Table', 'K3'),
    ('기본', 'Portrait', '초상', '24–48 · 핀 48 원형', '없으면 첫 글자 판', 'Portrait', 'K3'),
    ('상태', 'StatusView', '상태(P-X01)', '영역 · 전체 화면', 'loading · empty · error · denied · wait-read · wait-input · stale · not-found · maintenance', 'EmptyState', 'K3'),
    ('명령 흐름', 'CommandFlow', '이번 순에 할 일', '576 · 태블릿 480 · 모바일 가득 시트', '모달 아님 · Esc · URL 에 명령 · 순 · 대상', '새로', 'K3 모양 · K6 동작'),
    ('명령 흐름', 'TurnStrip', '순 띠', '2 × 6 칸 44 · 모바일 한 줄 96', '실행됨 · 예약 · 경고 · 빈 순 · 고름', 'Slot', 'K3'),
    ('명령 흐름', 'CommandList', '명령 목록', '240 · 행 44', '분류 8 · 찾기(초성 · 옛 이름) · 가능 · 불가 · 준비 중 · 적는 중', '새로', 'K3 · K6'),
    ('명령 흐름', 'CommandArgs', '인자 패널', '336', '머리(이름 · 순) · 도움말 띠 · 칸 · 미리보기 · 예약', '새로', 'K3 · K6'),
    ('명령 흐름', 'HelpStrip', '도움말 띠', '44', '설명 · 잘 되면 · 안 되면 · 초안', '새로', 'K3 자리 · K7 내용'),
    ('명령 흐름', 'PlaceField', '장소 칸', '44 + 44', '값 · 지도에서 고르기 · 고르는 중', '새로', 'K3'),
    ('명령 흐름', 'PersonField', '사람 칸', '44', '값 · PeoplePicker 열기', '새로', 'K3'),
    ('지도', 'MapTargetPicker', '지도 대상 고르기', '고르기 띠 52 · 표지 44', 'place · jurisdiction(주 · 군국) · corps · multi-county', '새로', 'K3 · K2'),
    ('지도', 'TargetCandidateList', '후보 목록', '336 · 행 44–52', '내 영지 · 이웃 · 전체 · 가능만 · 가까운 순', '새로', 'K3'),
    ('지도', 'MyLocationMarker', '내 위치 표지', '핀 48 × 62', '성 안 · 성 밖 · 군단과 함께 · 이동 중', '새로', 'K3 · K2'),
    ('지도', 'MyLocationEdge', '화면 밖 화살표', '44 × 52', '네 방향 · 거리', '새로', 'K3 · K2'),
    ('지도', 'MyGeneralCard', '내 장수 카드', '320 · 모바일 시트', '', '새로', 'K3'),
    ('지도', 'MapViewBar', '보기 단추', '왼쪽 아래 세로 줄 44', '주 · 군 · 현 → + − → 내 위치로', '새로', 'K2'),
    ('지도', 'MapLayerPanel', '레이어', '떠 있는 패널 · 모바일 시트', '경계 · 이름 · 보급선 · 시야 · 부대 경로', '새로', 'K2'),
    ('지도', 'MapSelectionCard', '선택 카드', '320 · 모바일 알약 → 시트', '성 · 현 · 관(MapPassCard) · 부대', '새로', 'K2'),
    ('지도', 'MapLabel', '이름표', '주 18 · 군 15 + 치소 13 · 현 15', '겹치면 등급 → 인구 순으로 숨김', '새로', 'K2'),
    ('사람', 'PeoplePicker', '사람 고르기', '380 · 모바일 가득 시트', '묶음 4 · 초성 찾기 · 빈 · 찾기 없음 · 실패', '새로', 'K3'),
    ('사람', 'PersonRow', '사람 한 줄', '56–60', '사람 칩 · 국가색 점 · 시야 밖 · 불가 사유', '새로', 'K3'),
]

RULES = [
    ('누르는 것 44', '단추 · 탭 · 표지 · 행 · 칩 단추. 작은 단추도 높이 44.'),
    ('호버 · title 금지', '정보는 누르면 열린다. 비활성은 aria-disabled(네이티브 disabled 금지).'),
    ('단추 안 단추 금지', '불가 행은 행 전체가 사유를 여는 단추, 사유는 점선 꼬리표.'),
    ('입력 상태는 서버가', 'AVAILABLE 보통 · BLOCKED 점선 + 사유 · NOT_DELIVERED 점선 + 「준비 중」 · 원장 행 없음 = 그리지 않음. 코드에 박지 않는다.'),
    ('입력 앵커', '입력을 보내는 조작마다 data-input-id="<inputId>", 입력이 아닌 목표엔 data-guide="<objectiveId>"(K7).'),
    ('빈 ≠ 실패', '빈 목록은 이유 + 채우는 법, 실패는 다시 시도 + 오류 번호. 같은 모양 금지.'),
    ('서버 대기 두 모양', 'A 읽기 없음 = 영역 전체 StatusView. B 읽기는 있고 입력만 없음 = 내용 그대로 + 단추 점선 「준비 중」.'),
    ('수치', '설계에서 정하지 않은 값은 [미정]. 서버 원장 값은 서버가 준 대로.'),
    ('국가색', '경계 띠 · 깃발 · 성 지붕 · 1칸 거점 · 내 위치 링에만. 지형을 색으로 덮지 않는다.'),
    ('빗금', '빗금 = 미정찰(시야 밖). 고를 수 없음은 빗금이 아니라 적갈 점선.'),
    ('표기', '縣 → 현 · 郡 → 군 · 城 → 성 · 省 → 구역 · 금 · 쌀 · 부(府) · 소속. 관직은 names.office_ko.'),
]


# ================================================================== 보드
# 지도 예시 좌표: MAP['desk'] = 영천 일대 16px/칸 1048 × 952. 칸 → px: x = (col − 1597)·16 + 660, y = (row − 977)·16 + 292.
# 칸 좌표는 data/map/han-tiles.json 의 실제 값. 거리 = 칸 수(가로 · 세로 중 큰 쪽).
def DESK_PX(col, row):
    return (col - 1597) * 16 + 660, (row - 977) * 16 + 292


def MOB_PX(col, row):  # MAP['mob'] = 양적 일대 16px/칸 390 × 844
    return (col - 1569) * 16 + 203, (row - 981) * 16 + 430


CELLS = {'양적현': (1569, 981), '장사현': (1597, 977), '영음현': (1597, 993), '허현': (1609, 997), '영양현': (1577, 997),
         '신정현': (1589, 961), '번창현': (1593, 1005), '임영현': (1601, 1009), '양성현': (1569, 1009), '언릉현': (1621, 981),
         '위씨현': (1621, 961), '밀현': (1573, 957), '마피영': (1561, 1003)}
HERE = '양적현'


def dist(a, b=HERE):
    (c1, r1), (c2, r2) = CELLS[a], CELLS[b]
    return f'{max(abs(c1 - c2), abs(r1 - r2))}칸'


# 이동(action.move) 후보 — 상태는 예시, 사유는 입력 원장 failureReasons 의 실제 코드를 쉬운 말로.
# NO_ROUTE → 갈 길이 없음 · INVALID_DESTINATION → 갈 수 없는 곳
CANDS = [('영양현', '영천군', 'ok', ''), ('신정현', '하남윤', 'no', '갈 길이 없음'), ('밀현', '하남윤', 'ok', ''),
         ('번창현', '영천군', 'ok', ''), ('마피영', '영천군', 'no', '갈 수 없는 곳'), ('장사현', '영천군', 'ok', ''),
         ('영음현', '영천군', 'ok', ''), ('양성현', '영천군', 'ok', ''), ('임영현', '영천군', 'ok', ''), ('허현', '영천군', 'no', '갈 수 없는 곳')]
CANDS.sort(key=lambda c: int(dist(c[0])[:-1]))
PICK = '영양현'


def _desk_marks(ox=0, oy=0, sel=PICK):
    out = ''
    for n, sub, st, r in CANDS:
        x, y = DESK_PX(*CELLS[n])
        out += mk(x - ox, y - oy, 'sel' if n == sel else st, n, 'no' if st == 'no' else '')
    return out


def board_shell():
    def frame(label, rng, w, h, inner):
        return (f'<div style="display:flex;flex-direction:column;gap:6px;align-items:flex-start"><span class="serif" style="font-size:12.5px;font-weight:700;white-space:nowrap">{label} '
                f'<span class="mono muted" style="font-size:11px;font-weight:500">{rng}</span></span>'
                f'<div style="width:{w}px;height:{h}px;border:1px solid #3d4740;background:#101412;position:relative;overflow:hidden">{inner}</div></div>')

    def blk(style, txt, c='#2c342f'):
        return f'<div style="position:absolute;{style};background:{c};display:flex;align-items:center;justify-content:center;font-size:9px;color:#b9b2a3;text-align:center;line-height:1.2">{txt}</div>'

    MAPC, TURN, PANEL, HELP = '#1b2a22', '#3a3526', '#2c342f', '#23303a'
    mapf = ('<div style="display:flex;gap:12px;align-items:flex-end;padding:12px">'
            + frame('모바일', '< 768', 70, 152, blk('left:0;right:0;top:0;bottom:0', '지도 100%', MAPC) + blk('left:3px;right:3px;top:3px;height:10px', '머리줄')
                    + blk('left:0;right:0;bottom:12px;height:26px', '12순 시트', TURN) + blk('left:0;right:0;bottom:0;height:12px', '탭'))
            + frame('태블릿', '768 – 1199', 132, 100, blk('left:0;right:0;top:0;height:7px', '') + blk('left:0;top:7px;bottom:0;width:7px', '')
                    + blk('left:7px;right:37px;top:7px;bottom:0', '지도 70%', MAPC) + blk('right:0;top:7px;bottom:0;width:37px', '12순 288', TURN))
            + frame('데스크톱', '≥ 1200', 184, 128, blk('left:0;right:0;top:0;height:6px', '') + blk('left:0;top:6px;bottom:0;width:7px', '')
                    + blk('left:7px;right:43px;top:6px;bottom:0', '지도 76%', MAPC) + blk('right:0;top:6px;bottom:0;width:43px', '12순 336', TURN)
                    + blk('left:7px;top:36px;width:6px;height:24px', '', '#3d4740') + blk('left:100px;top:28px;width:40px;height:22px', '선택 카드', PANEL))
            + '</div>')
    flowf = ('<div style="display:flex;gap:12px;align-items:flex-end;padding:0 12px 12px">'
             + frame('명령 흐름', '데스크톱', 184, 128, blk('left:0;right:0;top:0;height:6px', '') + blk('left:0;top:6px;bottom:0;width:7px', '')
                     + blk('left:7px;right:74px;top:6px;bottom:0', '지도 808', MAPC) + blk('right:0;top:6px;bottom:0;width:74px', '흐름 576', TURN))
             + frame('도움말 · 서신 서랍', '데스크톱', 184, 128, blk('left:0;right:0;top:0;height:6px', '') + blk('left:0;top:6px;bottom:0;width:7px', '')
                     + blk('left:7px;right:95px;top:6px;bottom:0', '지도 648', MAPC) + blk('right:43px;top:6px;bottom:0;width:52px', '서랍 400', HELP)
                     + blk('right:0;top:6px;bottom:0;width:43px', '12순', TURN))
             + '</div>')
    pagef = ('<div style="display:flex;gap:12px;align-items:flex-end;padding:12px">'
             + frame('모바일', '< 768', 70, 152, blk('left:0;right:0;top:0;height:12px', '머리 · ←') + blk('left:0;right:0;top:13px;height:10px', '하위 탭', '#232a26')
                     + blk('left:4px;right:4px;top:27px;height:30px', '카드', PANEL) + blk('left:4px;right:4px;top:60px;height:30px', '카드', PANEL)
                     + blk('left:0;right:0;bottom:12px;height:14px', '행동 막대', TURN) + blk('left:0;right:0;bottom:0;height:12px', '탭'))
             + frame('태블릿', '768 – 1199', 132, 100, blk('left:0;right:0;top:0;height:7px', '') + blk('left:0;top:7px;bottom:0;width:7px', '')
                     + blk('left:10px;right:4px;top:10px;height:9px', '머리 · 하위 탭', '#232a26') + blk('left:10px;right:4px;top:22px;bottom:4px', '주 패널(1열)', PANEL))
             + frame('데스크톱', '≥ 1200', 184, 128, blk('left:0;right:0;top:0;height:6px', '') + blk('left:0;top:6px;bottom:0;width:7px', '')
                     + blk('left:10px;right:4px;top:9px;height:9px', '머리 · 하위 탭', '#232a26') + blk('left:10px;right:52px;top:22px;bottom:4px', '주 패널(표)', PANEL)
                     + blk('right:4px;top:22px;bottom:4px;width:44px', '옆 336', PANEL))
             + '</div>')
    hero = ('<ul class="ul" style="padding:4px 12px 10px">'
            '<li><b>작전실 = 지도 + 12순 열.</b> 데스크톱은 레일 56 · 12순 열 336 을 뺀 전부(76%). 태블릿 12순 288(70%). 모바일은 화면 전체 + 12순 하단 시트.</li>'
            '<li><b>올려도 되는 것</b> — 떠 있는 단추(44) · 고르기 띠 · 작은 지도(176) · 선택 · 내 장수 · 계절 카드(폭 400 이하) · 지난 순 서랍(누를 때만).</li>'
            '<li><b>옆으로 여는 것</b> — 명령 흐름(12순 열 자리를 576 으로, 지도 808) · 도움말 · 서신 서랍(지도와 12순 사이 400, 지도 648). 지도를 다 덮는 창은 없다.</li>'
            '<li><b>지도 위 자리</b> — 위 왼쪽: 보는 곳 이름 · 고르기 띠. 위 오른쪽: 레이어 · 범례. 왼쪽 아래: 보기 단추(주 · 군 · 현 · + · − · 내 위치로). 오른쪽 아래: 작은 지도.</li>'
            '<li><b>지형은 덮지 않는다</b> — 국가색은 경계 · 깃발 · 성 지붕 · 내 위치 링만. 고르는 중에는 후보 밖을 어둡게(α .42).</li></ul>')
    order = ''.join(f'<li><b>{n}</b> <span class="muted">{sub}</span></li>' for n, sub in [
        ('작전실', '지도 · 명령 목록 12순 · 지난 순'), ('부', '레일 · 탭 이름은 「부」. 페이지 머리는 내 부의 지금 이름(me.buName — 막부 · 군부 …, 확정 전 [부 이름])'),
        ('계책 · 영지 · 군단 · 조정 · 기록', '6묶음의 나머지'), ('광장', '회의실 · 기밀실 · 서신 · 커뮤니티'), ('도움말 · 관리', '레일 아래쪽. 관리는 권한자만')])
    head = ('<ul class="ul" style="padding:4px 12px">'
            '<li><b>머리줄 48</b> — 로고 · 화면 이름 | 계절 · 날짜 칩(44, 소식 점) · 다음 개인 턴 · [연습 서버 · 첫걸음 3/8] · 서신 · 도움말 · 나(소속) · 명망.</li>'
            '<li><b>첫걸음 칩</b>은 연습 서버에서만. 본 서버는 도움말 서랍 안 안내판(K7).</li>'
            '<li><b>서신</b> 단추는 서신 서랍(작전실 MessagePanel 대체). 외교 서신은 조정 › 외교.</li></ul>')
    mob = (f'<div style="display:flex;flex-direction:column;gap:10px;padding:12px">'
           f'<div style="width:390px;border:1px solid #3d4740;display:flex;flex-direction:column">{mtop31()}{tabbar31("war")}</div>'
           f'<ul class="ul"><li><b>하단 탭 64</b> — 작전실 · 부 · 계책 · 기록 · 전체. 나머지 묶음은 「전체」.</li>'
           f'<li><b>시트 3단</b> — 살짝 124 · 반 · 가득. 끌기 없이 단추로도.</li>'
           f'<li><b>도움말 · 서신</b> — 머리 아래 ~ 탭 위 724 시트, 주소 ?help= · 기기 뒤로 가기로 닫힘.</li></ul></div>')
    zl = ''.join(f'<tr><td class="mono bz" style="height:34px">{n}</td><td class="mono" style="height:34px">{v}</td><td class="t2" style="height:34px;white-space:normal">{d}</td></tr>' for n, v, d in Z)
    g = 'display:grid;grid-template-columns:repeat(3,minmax(0,1fr));grid-template-rows:repeat(2,minmax(0,1fr));gap:12px;padding:12px;flex-grow:1;min-height:0'
    body = (f'<main style="flex-grow:1;min-width:0;display:flex;flex-direction:column;overflow:hidden"><div style="{g}">'
            f'<section class="panel">{sec("지도 화면 — 폭 3단 · 옆으로 여는 것", "작전실")}{mapf}{flowf}</section>'
            f'<section class="panel">{sec("내용 화면 — 폭 3단", "부 · 영지 · 조정 · 기록 …")}{pagef}{head}</section>'
            f'<section class="panel">{sec("지도가 주인공 — 규칙", "모든 지도 화면")}{hero}</section>'
            f'<section class="panel">{sec("모바일 머리줄 · 하단 탭", "390")}{mob}</section>'
            f'<section class="panel">{sec("레일 56 — 순서", "데스크톱 · 태블릿")}<ul class="ul" style="padding:4px 12px">{order}</ul></section>'
            f'<section class="panel">{sec("쌓는 순서", "아래 → 위")}<div style="padding:4px 10px">'
            f'<table class="table" style="font-size:12px"><tbody>{zl}</tbody></table></div></section>'
            f'</div></main>')
    page31('V31SystemShell.dc.html', '시스템 v3.1 — 셸 · 배치 규칙', shell_desk('시스템 v3.1 — 셸 · 배치', 'war', body, practice=True))


def board_tokens():
    def sw(c, n, tok, d):
        return (f'<div style="display:flex;align-items:center;gap:8px;height:30px"><i style="width:22px;height:22px;display:inline-block;background:{c};border:1px solid #3d4740;flex-shrink:0"></i>'
                f'<span style="font-size:12px;font-weight:700;width:40px">{n}</span><span class="mono bz" style="font-size:11px;width:92px">{tok}</span><span class="muted" style="font-size:11px">{d}</span></div>')

    base = ''.join(sw(*a) for a in [('#0c0f0e', '바탕', '--bg', '화면 바탕'), ('#1b201d', '패널', '--panel', '패널 · 시트'), ('#141816', '안쪽', '--inset', '입력칸'),
                                    ('#232a26', '돌출', '--raised', '머리 · 눌림'), ('#2c342f', '선 약', '--line', '행 나눔'), ('#3d4740', '선 강', '--line-2', '테두리')])
    sem = ''.join(sw(*a) for a in [('#d3b064', '청동', '--bronze', '지위 · 주 행동 · 켜짐 · 황실'), ('#8fa77a', '이끼', '--moss-2', '가능 · 양호'),
                                   ('#e08a7c', '적갈', '--rust-2', '경고 · 불가 · 위험'), ('#7aa7c7', '청', '--info', '중립 · 준비 중 · 연습'),
                                   ('#ffd36d', '초점', '--focus', '포커스 · 고른 곳 · 내 위치')])
    txt = ''.join(sw(*a) for a in [('#ece6d8', '글자', '--text', '본문'), ('#b9b2a3', '글자 2', '--text-2', '보조'), ('#8e8879', '흐린 글', '--muted', '설명 · 4.6:1'),
                                   ('#161410', '먹', '--ink', '청동 위 글자')])
    resr = ''.join(f'<div style="display:flex;align-items:center;gap:8px;height:28px">{res(n)}<span class="mono bz" style="font-size:11px">--res-{k}</span></div>'
                   for n, k in [('금', 'gold'), ('쌀', 'rice'), ('철', 'iron'), ('목재', 'timber'), ('말', 'horse')])
    catr = ''.join(f'<div style="display:flex;align-items:center;gap:8px;height:28px">{cat(c)}<span class="mono bz" style="font-size:11px">--cat-{k}</span></div>'
                   for c, k in zip(CATS, ['self', 'bu', 'court', 'battle', 'world']))
    nat = ''.join(f'<span class="chip" style="gap:6px"><i style="width:10px;height:10px;display:inline-block;background:{c}"></i>{n}</span>' for n, c in NATION.items())
    colors = (f'<div style="display:grid;grid-template-columns:1fr 1fr;gap:4px 16px;padding:8px 12px">'
              f'<div><span class="muted" style="font-size:11px">바탕 · 면</span>{base}</div><div><span class="muted" style="font-size:11px">의미</span>{sem}</div>'
              f'<div><span class="muted" style="font-size:11px">글자</span>{txt}</div>'
              f'<div><span class="muted" style="font-size:11px">자원 — 늘 글자와 함께</span>{resr}</div>'
              f'<div><span class="muted" style="font-size:11px">기록 5분류</span>{catr}</div>'
              f'<div style="display:flex;flex-direction:column;gap:6px"><span class="muted" style="font-size:11px">국가색 — 유저가 고른다(예시)</span><div style="display:flex;flex-wrap:wrap;gap:4px">{nat}</div>'
              f'<span class="note">경계 · 깃발 · 성 지붕 · 1칸 거점 · 내 위치 링에만.</span>'
              f'<span class="muted" style="font-size:11px;margin-top:6px">황실 표식</span><span style="display:flex;gap:6px;align-items:center"><span class="chip bronze">{icon("crown", 14, "#d3b064")}황제</span>'
              f'<span class="note">청동 + 관 그림(SVG). 금색을 따로 두지 않는다 — 금은 자원색.</span></span></div></div>')
    type_ = ''.join(f'<div style="display:flex;align-items:baseline;gap:12px;padding:5px 0;border-bottom:1px solid #2c342f"><span class="mono bz" style="font-size:11px;width:118px;flex-shrink:0">{t}</span>'
                    f'<span class="{cls}" style="font-size:{s}px;font-weight:{wgt}">{ex}</span></div>' for t, cls, s, wgt, ex in [
                        ('제목 24 · 명조 900', 'serif', 24, 900, '작전실'), ('제목 20 · 명조 900', 'serif', 20, 900, '인물 일람'),
                        ('제목 17 · 명조 900', 'serif', 17, 900, '발령 — 지금은 할 수 없습니다'), ('이름 15 · 명조 700', 'serif', 15, 700, '하후돈 · 양적현'),
                        ('본문 14 · 고딕 400', '', 14, 400, '영천군 치소로 옮깁니다.'), ('보조 12 · 고딕 400', 't2', 12, 400, '3월 하순 · 22:40'),
                        ('작은 글 11 · 최소', 'muted', 11, 400, '11px 아래로 쓰지 않는다'), ('숫자 · 고정폭', 'mono', 13, 500, '04순 · 21:40 · 1,428')])
    sp = ''.join(f'<div style="display:flex;align-items:center;gap:8px;height:26px"><span class="mono bz" style="font-size:11px;width:78px">{t}</span>'
                 f'<i style="width:{v}px;height:10px;background:#d3b064;display:inline-block"></i><span class="mono muted" style="font-size:11px">{v}</span></div>'
                 for t, v in [('--space-1', 4), ('--space-2', 8), ('--space-3', 12), ('--space-4', 16), ('--space-6', 24)])
    lay = ''.join(f'<tr><td class="mono bz" style="height:20px;font-size:10.5px">{k}</td><td class="mono" style="height:20px;font-size:10.5px">{v}</td></tr>' for k, v in LAYOUT.items())
    bp = ('<div style="padding:8px 12px;display:flex;flex-direction:column;gap:6px">'
          + ''.join(f'<div style="display:flex;align-items:center;gap:10px;height:30px"><span class="mono bz" style="font-size:12px;width:130px">{t}</span>'
                    f'<span style="font-size:12.5px">{d}</span></div>' for t, d in [
                        ('모바일', '0 – 767 · 하단 탭 · 시트 · 카드'), ('--bp-tablet 768', '768 – 1199 · 좁은 레일 · 1열 · 명령 흐름 480'),
                        ('--bp-desktop 1200', '1200 이상 · 레일 · 옆 패널 · 명령 흐름 576')])
          + '<span class="note">CSS 는 <span class="mono">@media (min-width: 768px)</span> · <span class="mono">(min-width: 1200px)</span> 두 개만. JS 는 web/shared 의 <span class="mono">BREAKPOINTS</span> 한 곳에서.</span></div>')
    shadows = ''.join(f'<li><b>{n}</b> <span class="mono muted">{v}</span></li>' for n, v in SHADOW)
    rules = ('<ul class="ul" style="padding:4px 12px">'
             '<li><b>모서리 0</b> — 각진 판. 예외: 내 위치 핀(원형 초상), 군단 표지.</li>'
             '<li><b>포커스</b> — 3px <span class="mono">#ffd36d</span> 링. 지우지 않는다.</li>'
             '<li><b>움직임</b> — 시트 · 서랍 200ms, 줄이기 설정이면 바로.</li>'
             f'{shadows}</ul>')
    g = 'display:grid;grid-template-columns:1.35fr 1fr 1fr;grid-template-rows:repeat(2,minmax(0,1fr));gap:12px;padding:12px;flex-grow:1;min-height:0'
    body = (f'<main style="flex-grow:1;min-width:0;display:flex;flex-direction:column;overflow:hidden"><div style="{g}">'
            f'<section class="panel" style="grid-row:span 2">{sec("색", "제품 토큰 이름 · tokens.css")}{colors}</section>'
            f'<section class="panel">{sec("글자", "명조 제목 · 고딕 본문 · 고정폭 숫자")}<div style="padding:4px 12px">{type_}</div></section>'
            f'<section class="panel">{sec("간격 · 틀 치수", "px")}<div style="display:flex;gap:12px;padding:8px 12px"><div>{sp}</div>'
            f'<table class="table"><tbody>{lay}</tbody></table></div></section>'
            f'<section class="panel">{sec("화면 폭 3단", "브레이크포인트 토큰")}{bp}</section>'
            f'<section class="panel">{sec("모양 · 그림자", "모든 부품")}{rules}</section></div></main>')
    page31('V31SystemTokens.dc.html', '시스템 v3.1 — 토큰', shell_desk('시스템 v3.1 — 토큰', 'war', body))


def board_index():
    def item(grp, i, n, sz, v, have, own):
        return (f'<div style="padding:5px 0;border-bottom:1px solid #2c342f;display:flex;flex-direction:column;gap:1px">'
                f'<div style="display:flex;align-items:baseline;gap:8px"><span class="mono bz" style="font-size:12px;font-weight:700">{i}</span><span style="font-size:12.5px">{n}</span>'
                f'<span class="muted" style="margin-left:auto;font-size:10.5px;white-space:nowrap">{own}</span></div>'
                f'<span class="muted" style="font-size:11px;line-height:1.4">{sz}{" · " + v if v else ""}{"" if have == "새로" else " · 지금 짝 " + have}</span></div>')

    def col(items, title, sub):
        body, last = '', None
        for it in items:
            if it[0] != last:
                body += f'<div class="serif bz" style="font-size:12.5px;font-weight:700;padding:8px 0 2px">{it[0]}</div>'
                last = it[0]
            body += item(*it)
        return f'<section class="panel" style="flex:1 1 0;min-width:0">{sec(title, sub)}<div style="padding:0 12px 8px">{body}</div></section>'

    a, b, c = PARTS[:16], PARTS[16:30], PARTS[30:]
    rl = ''.join(f'<li><b>{n}</b> — {d}</li>' for n, d in RULES)
    body = (f'<main style="flex-grow:1;min-width:0;display:flex;gap:12px;padding:12px;overflow:hidden">'
            + col(a, '부품 1', '설계 레인은 이 id 로 부른다') + col(b, '부품 2', '담당 = 모양 · 내용')
            + f'<div style="flex:1 1 0;min-width:0;display:flex;flex-direction:column;gap:12px">{col(c, "부품 3", "지금 짝 = web/shared 에 있는 부품")}'
            f'<section class="panel">{sec("구현 규칙", "K10 일관성 검사가 센다")}<ul class="ul" style="padding:0 12px">{rl}</ul></section></div></main>')
    page31('V31SystemIndex.dc.html', '시스템 v3.1 — 부품 목록 · 규칙', shell_desk('시스템 v3.1 — 부품 목록', 'war', body))


def board_nav():
    rows = ''
    for k, n, ic, screens in NAV31:
        for j, (s, new, old, lane) in enumerate(screens):
            grp = (f'<span style="display:inline-flex;align-items:center;gap:6px" class="bz">{icon(ic, 16)}<span class="serif" style="font-weight:700">{n}</span></span>' if j == 0 else '')
            rows += (f'<tr><td style="width:110px;height:26px">{grp}</td><td style="height:26px">{s}</td><td class="mono" style="height:26px;color:#d3b064">{new}</td>'
                     f'<td class="t2" style="height:26px;white-space:normal">{old}</td><td class="muted" style="height:26px">{lane}</td></tr>')
    table = (f'<table class="table" style="font-size:12px"><thead><tr><th>묶음</th><th>화면</th><th>새 경로(서버 경로 아래)</th><th>지금 화면 → 308</th><th>레인</th></tr></thead><tbody>{rows}</tbody></table>')
    changes = ('<ul class="ul" style="padding:4px 12px">'
               '<li><b>레일 · 탭 이름 「막부」 → 「부」</b> — 페이지 머리는 me.buName(확정 전 [부 이름]).</li>'
               '<li><b>군단 › 전투</b> — 내 전투 목록 · 부재 대비, 전투 단계(참가 대기 · 배치 → 실시간). 09-18 전투 계획 봉인 · 방어 대비는 09-27 실시간 전투 결정으로 폐기.</li>'
               '<li><b>관직 탭 안에 봉신</b>, <b>외교 탭 안에 주변 세계 · 외교 서신</b>, <b>황실 안에 칭제</b>.</li>'
               '<li><b>역정보</b>는 계책 묶음(/stratagem/counter-intel).</li>'
               '<li><b>계절 사건</b>은 페이지 없음 — 머리줄 계절 칩 · 기록 「천하 정세」 · 영지 경고.</li>'
               '<li><b>참모 제안</b> /court/council → <b>/court/proposals</b>(광장 /council 과 겹침).</li>'
               '<li><b>서신</b>은 머리줄 서신 서랍(작전실 MessagePanel 대체) + /mail 전체 화면.</li>'
               '<li><b>도움말</b>은 셸 서랍(/help 는 같은 본문의 독립 페이지).</li></ul>')
    body = (f'<main style="flex-grow:1;min-width:0;display:flex;gap:12px;padding:12px;overflow:hidden">'
            f'<section class="panel" style="flex:1 1 0;min-width:0">{sec("메뉴 한 벌 v3.1 — 작전실 · 6묶음 · 광장", "옛 경로는 308 전용")}<div style="padding:6px 10px">{table}</div></section>'
            f'<section class="panel" style="width:400px;flex-shrink:0">{sec("09-26 승인본에서 바뀐 것", "09-30 정보 구조 결정")}{changes}</section></main>')
    page31('V31SystemNav.dc.html', '시스템 v3.1 — 메뉴 한 벌 · 경로', shell_desk('정보 구조 v3.1', 'war', body))


def board_parts():
    col1 = (f'<section class="panel">{sec("단추", "누르는 높이 44")}'
            f'<div style="padding:10px 12px;display:flex;flex-direction:column;gap:10px">'
            f'<div style="display:flex;gap:8px;flex-wrap:wrap">{btn("04순에 예약", "primary", attrs="data-input-id=action.move")}{btn("취소")}{btn("비우기", "danger", "clear")}</div>'
            f'<div style="display:flex;gap:8px;flex-wrap:wrap;align-items:center">{btn("작은 단추", "sm")}{ibtn("help", "도움말")}{ibtn("mail", "서신 2통", 2)}{btn("현 상세 →", "sm", href="#")}</div>'
            f'<span class="note">주 행동은 화면(또는 패널)마다 하나만 청동. 위험한 행동은 적갈 + 확인 대화.</span></div>'
            f'{sec("입력 단추 — 서버 상태 그대로", "보통 · 막힘(사유) · 준비 중 · 행 없음은 안 그림")}'
            f'<div style="padding:10px 12px;position:relative;height:250px">'
            f'<div style="display:flex;gap:6px;align-items:center;flex-wrap:wrap">{input_btn("출병", "AVAILABLE", input_id="action.deploy", kind="")}{input_btn("발령", "BLOCKED", "권한 없음", "court.dispatch", kind="")}{input_btn("헌납", "NOT_DELIVERED", input_id="action.donate", kind="")}</div>'
            + pop('발령은 주공만 할 수 있습니다.', '하후돈은 지금 조조를 섬기는 장수입니다.', '주공이 되려면 거병하거나 독립해야 합니다.', '발령', 'left:100px;top:62px')
            + f'<span class="note" style="position:absolute;left:12px;bottom:4px;width:84px">데스크톱 말풍선 · 모바일 하단 시트</span></div>'
            f'{sec("칩 · 분할 선택 · 하위 탭", "")}'
            f'<div style="padding:10px 12px;display:flex;flex-direction:column;gap:8px"><div style="display:flex;gap:6px;flex-wrap:wrap">{chip("치소", "bronze")}{chip("가능", "moss")}{chip("고립", "rust")}{chip("준비 중", "info")}{chip("빈 순")}{cat("전장 보고")}</div>'
            f'{seg(["주", "군", "현"], "현", "보기 수준")}{subtabs(["편성 · 결속", "인물 일람", "월단평"], "인물 일람")}</div></section>')
    col2 = (f'<section class="panel">{sec("입력칸", "이름표는 칸 위 · 도움말은 아래")}'
            f'<div style="padding:10px 12px;display:grid;grid-template-columns:1fr 1fr;gap:10px">'
            + field('서신 제목', inp('관도 방면 보고'))
            + field('보낼 쌀', inp('1,200', unit='쌀'), '가진 쌀 [값] 안에서')
            + field('군단', inp('하후돈 군단', ic='corps'), '고르는 칸 → 선택 목록')
            + field('찾기', search('이름 · 초성'))
            + field('보낼 금', inp('9,000', cls='bad', unit='금'), '', '가진 금보다 많습니다(가진 금 [값])', style='grid-column:span 2')
            + field('서신 본문', '<div class="inp area">관도 북쪽에 원소군 선봉이 보입니다. 명을 기다립니다.</div>', '[미정]자까지', style='grid-column:span 2')
            + '</div>'
            f'{sec("선택 목록(OptionList)", "불가 행 = 누르면 사유 · 사유는 꼬리표로")}'
            f'<div role="listbox" aria-label="예시 목록" style="display:flex;flex-direction:column">'
            + cand_row('영양현', '영천군', dist('영양현'), sel=True)
            + cand_row('밀현', '하남윤', dist('밀현'))
            + cand_row('신정현', '하남윤', dist('신정현'), 'no', '갈 길이 없음')
            + f'</div><div style="padding:4px 12px;display:flex;gap:16px">{checkbox("가능한 곳만", False)}{checkbox("가까운 순", True)}</div>'
            f'{sec("도움말 띠(HelpStrip)", "인자 패널 제목 아래 · 내용 K7")}<div style="padding:10px 12px">{help_strip("다른 구역으로 옮깁니다.")}</div></section>')
    dlg = dialog('02순 예약을 비울까요?', '<div style="padding:12px 16px" class="t2">02순 「훈련」을 지웁니다. 지운 순은 빈 순이 됩니다. 이미 실행된 순은 지울 수 없습니다.</div>',
                 btn('취소') + btn('비우기', 'danger', 'clear'), w=424)
    strip = (f'<div style="padding:10px 12px;display:flex;flex-direction:column;gap:8px">{turn_strip(2, 8, 4)}{turn_caption(2)}'
             f'<div style="display:flex;gap:6px;flex-wrap:wrap">{btn("바꾸기", "sm", "swap")}{btn("비우기", "sm", "clear")}{btn("당기기", "sm", "prev")}{btn("밀기", "sm", "next")}</div>'
             f'<span class="note">찬 순을 누르면 그 명령이 인자 패널에 열린다. 실행된 순(이끼)은 보기만.</span></div>')
    card = (f'<div style="border:1px solid #3d4740;background:#141816;padding:10px;display:flex;gap:10px">{portrait("heojeo", "허저", 44, 62)}'
            f'<div style="display:flex;flex-direction:column;gap:4px;min-width:0;flex:1"><span style="display:flex;align-items:center;gap:6px"><span class="serif" style="font-size:15px;font-weight:700">허저</span>{chip("내 부", "bronze")}</span>'
            f'<span class="mono t2" style="font-size:11.5px">통 — · 무 — · 지 — · 정 — · 매 —</span><span class="muted" style="font-size:11.5px">자리 장사현 · 호위</span></div></div>')
    col3 = (f'<section class="panel">{sec("확인 대화", "되돌릴 수 없는 일에만")}<div style="padding:10px 12px">{dlg}</div>'
            f'{sec("알림 토스트", "성공 4초 · 실패는 닫을 때까지")}<div style="padding:10px 12px;display:flex;flex-direction:column;gap:8px">'
            f'{toast("04순에 이동을 예약했습니다.", "ok", btn("되돌리기", "sm", style="background:transparent"))}'
            f'{toast("예약하지 못했습니다 — 갈 길이 없습니다.", "bad", btn("이유", "sm", style="background:transparent"))}</div>'
            f'{sec("순 띠 — 찬 순을 골랐을 때", "명령 흐름 · 12순 열 공용")}{strip}'
            f'{sec("표 → 카드", "모바일 · 좁은 칸")}<div style="padding:10px 12px">{card}</div></section>')
    g = 'display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:12px;padding:12px;flex-grow:1;min-height:0'
    body = f'<main style="flex-grow:1;min-width:0;display:flex;flex-direction:column;overflow:hidden"><div style="{g}">{col1}{col2}{col3}</div></main>'
    page31('V31SystemParts.dc.html', '시스템 v3.1 — 부품', shell_desk('시스템 v3.1 — 부품', 'war', body))


def board_mparts():
    cards = ''.join(
        f'<div style="border:1px solid #3d4740;background:#141816;padding:10px;display:flex;gap:10px">{portrait(k, n, 40, 56)}'
        f'<div style="display:flex;flex-direction:column;gap:3px;min-width:0;flex:1"><span class="serif" style="font-size:15px;font-weight:700">{n}</span>'
        f'<span class="muted" style="font-size:11.5px">자리 {w} · {role}</span></div></div>'
        for k, n, w, role in [('heojeo', '허저', '장사현', '호위'), ('ijeon', '이전', '장사현', '[자리 없음]')])
    main = (f'<main style="height:724px;flex-shrink:0;position:relative;overflow:hidden;display:flex;flex-direction:column">'
            f'{mtabs_row(["편성 · 결속", "인물 일람", "월단평", "포로 · 등용"], "편성 · 결속")}'
            f'<div style="padding:12px;display:flex;flex-direction:column;gap:8px">{cards}</div><div class="scrim"></div>'
            + sheet('허저를 어디에 둘까요?',
                    f'<div style="padding:0 16px 8px" class="t2">호위 자리를 비우고 새 자리로 옮깁니다. 적용은 다음 카드 순부터입니다.</div>'
                    f'<div role="listbox" aria-label="자리" style="display:flex;flex-direction:column;border-top:1px solid #2c342f">'
                    + opt('호위', '지금 자리', chip('지금', 'bronze'), h=52)
                    + opt('장사현 수비', '비어 있음', ok_chip(), sel=True, h=52)
                    + opt('양적현 수비', '이전이 있음', why_tag('자리 참'), no=True, h=52) + '</div>',
                    height=420, foot=btn('취소', style='flex:1') + btn('여기에 둔다', 'primary', style='flex:1', attrs='data-input-id="placement.assign"'))
            + '</main>')
    page31('V31SystemMParts.dc.html', '시스템 v3.1 — 모바일 부품(카드 · 하단 시트)', shell_mob(main, 'retinue', '[부 이름]', '전체 메뉴'), w=390, h=844)


def board_page():
    rows = [[f'<span style="display:inline-flex;align-items:center;gap:8px">{portrait(k, n, 24, 34)}<span class="serif" style="font-weight:700">{n}</span>{chip("사람", "info") if kd == "사람" else ""}</span>',
             w, r, '—', btn('자세히', 'sm', href='#')]
            for k, n, kd, w, r in [('heojeo', '허저', 'NPC', '장사현', '호위'), ('ijeon', '이전', 'NPC', '장사현', '[자리 없음]'), ('sunuk', '순욱', '사람', '허현', '조조 소속 · 참모')]]
    table = tbl(['인물', '자리', '맡은 일', '적성', ''], rows, 'font-size:12.5px')
    main_ = (f'<main style="flex-grow:1;min-width:0;display:flex;flex-direction:column">'
             + pagehead('[부 이름]', ['편성 · 결속', '인물 일람', '월단평', '포로 · 등용'], '인물 일람', btn('도움말', '', 'help'))
             + f'<div style="flex-grow:1;display:grid;grid-template-columns:minmax(0,1fr) 336px;gap:12px;padding:12px;min-height:0">'
             f'<section class="panel">{sec("인물 일람", "주 패널 — 표(모바일은 카드)")}'
             f'<div style="padding:10px 12px;display:flex;gap:8px;align-items:center">{search("이름 · 초성", style="width:280px")}{seg([("전체", 3), ("내 부", 2), ("사람", 1)], "전체", "거르기")}</div>'
             f'<div style="padding:0 12px">{table}</div>'
             f'<div style="margin:12px;border:1px dashed #3d4740;height:220px;display:flex">{state_empty("더 볼 인물이 없습니다", "인재탐색으로 새 인물을 찾을 수 있습니다.", btn("인재탐색 예약", "", "search", href="#", attrs="data-input-id=action.search"))}</div></section>'
             f'<section class="panel">{sec("옆 패널", "고른 행의 상세")}<div style="padding:10px 12px"><ul class="ul">'
             f'<li><b>머리 56</b> — 묶음의 이 화면 이름 · 하위 화면 탭 · 오른쪽 행동</li><li><b>주 패널</b> — 표 · 목록. 주 행동 하나.</li>'
             f'<li><b>옆 패널 336</b> — 고른 행의 상세. 태블릿 · 모바일은 시트.</li><li><b>빈 · 경고 상태</b> — 화면마다 하나 이상.</li>'
             f'<li><b>수치</b> — 정하지 않은 값은 [미정].</li></ul></div></section></div></main>')
    page31('V31SystemPage.dc.html', '시스템 v3.1 — 내용 페이지 틀(데스크톱)', shell_desk('[부 이름]', 'retinue', main_))


def board_mpage():
    cards = ''.join(
        f'<a href="#" style="border:1px solid #3d4740;background:#141816;padding:10px;display:flex;gap:10px;color:#ece6d8;min-height:76px">{portrait(k, n, 40, 56)}'
        f'<div style="display:flex;flex-direction:column;gap:3px;min-width:0;flex:1"><span style="display:flex;align-items:center;gap:6px"><span class="serif" style="font-size:15px;font-weight:700">{n}</span>{chip("사람", "info") if kd == "사람" else ""}</span>'
        f'<span class="muted" style="font-size:11.5px">자리 {w} · {r}</span></div><span style="align-self:center">{icon("next", 18, "#8a8477")}</span></a>'
        for k, n, kd, w, r in [('heojeo', '허저', 'NPC', '장사현', '호위'), ('ijeon', '이전', 'NPC', '장사현', '[자리 없음]'), ('sunuk', '순욱', '사람', '허현', '참모')])
    main = (f'<main style="height:724px;flex-shrink:0;position:relative;overflow:hidden;display:flex;flex-direction:column">'
            f'{mtabs_row(["편성 · 결속", "인물 일람", "월단평", "포로 · 등용"], "인물 일람")}'
            f'<div style="padding:10px 12px;display:flex;flex-direction:column;gap:8px">{search("이름 · 초성")}{seg([("전체", 3), ("내 부", 2), ("사람", 1)], "전체", "거르기")}</div>'
            f'<div style="padding:0 12px;display:flex;flex-direction:column;gap:8px">{cards}</div>'
            f'<div style="margin:12px;border:1px dashed #3d4740;height:170px;display:flex">{state_empty("더 볼 인물이 없습니다", "인재탐색으로 새 인물을 찾을 수 있습니다.", pad=8)}</div>'
            f'<div style="position:absolute;left:0;right:0;bottom:0;height:64px;display:flex;gap:8px;padding:10px 12px;background:#1b201d;border-top:1px solid #3d4740">'
            f'{btn("인재탐색 예약", "primary", "search", style="flex:1", href="#", attrs="data-input-id=action.search")}</div></main>')
    page31('V31SystemMPage.dc.html', '시스템 v3.1 — 내용 페이지 틀(모바일)', shell_mob(main, 'retinue', '인물 일람', '[부 이름]'), w=390, h=844)


def board_command():
    """명령 흐름(데스크톱) — 모달 없이, 12순 열 자리를 576 으로 넓혀 연다. 지도는 왼쪽 808 에 남는다."""
    MW, OX = 808, 100
    hx, hy = DESK_PX(*CELLS[HERE]); hx -= OX
    px, py = DESK_PX(*CELLS[PICK]); px -= OX
    mapst = (f'<main aria-label="지도 — 갈 곳 고르는 중" style="position:relative;width:{MW}px;flex-shrink:0;overflow:hidden;background:#0c0f0e">'
             f'{mapimg("desk", 1048, 952, "영천 일대 지도 — 현 보기", -OX, 0)}<div class="dim"></div>'
             f'{path_line(hx, hy, px, py, MW, 952, dist(PICK) + " · [미정]순")}{_desk_marks(OX)}{me_marker(hx, hy - 22, "in", tag=False)}'
             f'{pick_bar("갈 곳 고르기 — 이동 · 04순", "지도를 누르거나 오른쪽 목록에서 · 가능 7 · 불가 3")}{view_bar()}</main>')
    head = (f'<div style="height:52px;flex-shrink:0;display:flex;align-items:center;gap:10px;padding:0 4px 0 16px;border-bottom:1px solid #3d4740;background:linear-gradient(180deg,#232a26,#1b201d)">'
            f'<span class="serif" style="font-size:18px;font-weight:900">이번 순에 할 일</span><span class="muted" style="font-size:12px">하후돈 · 양적현</span>'
            f'<button type="button" class="ibtn" aria-label="닫고 12순으로(Esc)" style="margin-left:auto;border:0;background:transparent">{icon("close")}</button></div>')
    strip = f'<div style="flex-shrink:0;padding:10px 16px;border-bottom:1px solid #2c342f">{turn_strip(4, 12, 6, 4)}</div>'
    cats = f'<div style="flex-shrink:0;padding:8px 16px;border-bottom:1px solid #2c342f">{cat_tabs("이동", ("군사",))}</div>'
    lst = (f'<div style="width:240px;flex-shrink:0;display:flex;flex-direction:column;border-right:1px solid #2c342f">'
           f'<div style="padding:8px">{search("명령 찾기 · 초성 · 옛 이름")}</div>'
           f'<div role="listbox" aria-label="이동 명령" style="display:flex;flex-direction:column;border-top:1px solid #2c342f">'
           + cmd_row('이동', '다른 구역으로', 'ok', sel=True, draft=True, input_id='action.move')
           + cmd_row('강행', '빨리 · 사기가 준다', 'ok', input_id='action.forcedMarch')
           + cmd_row('귀환', '귀환 성으로', 'no', '귀환 성 없음', input_id='action.return')
           + cmd_row('집합', '군단을 한 곳에', 'ok', input_id='action.muster')
           + f'</div><ul class="ul" style="padding:10px 12px;margin-top:auto">'
           f'<li>명령을 바꿔도 적던 값은 명령마다 남는다(분류 탭의 점).</li><li>같은 종류 값은 이어받는다 — 이동 · 강행 · 출병의 갈 곳.</li>'
           f'<li>예약하면 닫지 않고 다음 빈 순으로 간다.</li></ul></div>')
    cand = ''.join(cand_row(n, sub, dist(n), st, r, sel=(n == PICK), h=44) for n, sub, st, r in CANDS[:3])
    args = (f'<div style="width:336px;flex-shrink:0;display:flex;flex-direction:column;padding:12px 16px;gap:10px;overflow:hidden">'
            f'<div style="display:flex;align-items:center;gap:8px"><span class="serif" style="font-size:22px;font-weight:900">이동</span>'
            f'<span class="chip bronze">04순</span><span class="mono muted" style="font-size:11.5px">4월 중순 00:40</span></div>'
            + help_strip('다른 구역으로 옮깁니다.')
            + target_field('갈 곳(⟦PROV⟧)', PICK, '영천군 · ' + dist(PICK))
            + f'<div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:-6px">{seg(["내 영지", "이웃", "전체"], "전체", "후보 묶음")}{checkbox("가능만")}</div>'
            f'<div role="listbox" aria-label="갈 곳 후보 — 가까운 순" style="display:flex;flex-direction:column;border-top:1px solid #2c342f">{cand}</div>'
            f'<div class="inset" style="padding:6px 10px;display:flex;flex-direction:column;gap:3px"><span class="muted" style="font-size:11px">비용 [미정] · 걸리는 순 [미정] · 조건은 실행 때 다시 본다</span>'
            f'<span class="ms" style="font-size:12.5px;display:flex;align-items:center;gap:6px">{icon("check", 14, "#8fa77a")}걸린 전투 없음 · 군단 출진 중 아님</span></div>'
            f'<div style="margin-top:auto;display:flex;flex-direction:column;gap:8px"><span class="t2" style="font-size:12.5px">04순에 <b class="bz">{PICK}</b>으로 옮깁니다.</span>'
            f'{btn("04순에 예약", "primary", style="width:100%", attrs="data-input-id=action.move")}</div></div>')
    flow = (f'<aside aria-label="이번 순에 할 일" style="width:576px;flex-shrink:0;display:flex;flex-direction:column;background:#1b201d;border-left:1px solid #9c7f3f;min-height:0">'
            f'{head}{strip}{cats}<div style="flex-grow:1;display:flex;min-height:0">{lst}{args}</div></aside>')
    page31('V31SystemCommand.dc.html', '시스템 v3.1 — 명령 흐름 · 지도 대상 고르기(데스크톱)', shell_desk('작전실', 'war', mapst + flow))


def board_mappick():
    hx, hy = DESK_PX(*CELLS[HERE])
    px, py = DESK_PX(*CELLS[PICK])
    others = ''.join(mlab(n, DESK_PX(*CELLS[n])[0], DESK_PX(*CELLS[n])[1] + 26) for n in ['위씨현', '언릉현'])
    sx, sy = DESK_PX(*CELLS['허현'])
    popr = pop('허현 — 이동으로 갈 수 없는 곳입니다', '이동은 이 곳으로 갈 수 없습니다.', '군단을 이끌고 가려면 출병을 쓰세요.', '이동',
               f'left:{sx - 170}px;top:{sy + 56}px')
    confirm = (f'<section class="panel" aria-label="고른 곳" style="position:absolute;left:{px + 36}px;top:{py - 40}px;width:300px;background:rgba(27,32,29,.97);box-shadow:0 10px 28px rgba(0,0,0,.5);border-color:#ffd36d">'
               f'<div style="height:44px;display:flex;align-items:center;gap:8px;padding:0 12px;border-bottom:1px solid #2c342f"><span class="serif" style="font-size:16px;font-weight:900">{PICK}</span>{chip("영천군")}{ok_chip()}</div>'
               f'<div style="padding:8px 12px" class="t2">내 위치(양적현)에서 {dist(PICK)} · 걸리는 순 [미정]</div>'
               f'<div style="display:flex;gap:6px;padding:0 8px 8px">{btn("이곳으로 정하기", "primary", style="flex:1")}{btn("다시 고르기")}</div></section>')
    legend = (f'<div style="position:absolute;right:12px;top:12px;width:210px;padding:8px 10px;background:rgba(27,32,29,.95);border:1px solid #3d4740;display:flex;flex-direction:column;gap:6px;font-size:12px">'
              f'<span class="muted" style="font-size:11px">표지</span>'
              + ''.join(f'<span style="display:flex;align-items:center;gap:8px"><i style="width:18px;height:18px;flex-shrink:0;display:inline-block;{s}"></i>{t}</span>' for s, t in [
                  ('border:2px solid #8fa77a;background:rgba(143,167,122,.2)', '고를 수 있음'), ('border:2px dashed #e08a7c', '고를 수 없음 — 누르면 이유'),
                  ('border:3px solid #ffd36d', '고른 곳'), ('border:2px solid #8fa77a;border-radius:50%', '군단(군단을 고를 때)'),
                  ('border:2px solid #4f7fbf;border-radius:50%;box-shadow:0 0 0 2px #ffd36d', '내 위치'), ('background:repeating-linear-gradient(45deg,#5a625c 0 2px,transparent 2px 6px)', '빗금 = 미정찰')])
              + '</div>')
    mapst = (f'<main aria-label="지도 — 갈 곳 고르는 중" style="position:relative;width:1048px;flex-shrink:0;overflow:hidden;background:#0c0f0e">'
             f'{mapimg("desk", 1048, 952, "영천 일대 지도 — 현 보기")}<div class="dim"></div>{others}{path_line(hx, hy, px, py, 1048, 952, dist(PICK))}{_desk_marks(0, 0)}'
             f'{me_marker(hx, hy - 22, "in", tag=False)}{popr}{confirm}{pick_bar("갈 곳 고르기 — 이동 · 04순", "후보가 아닌 곳은 흐리게 · 가능 7 · 불가 3", right=222)}{legend}{view_bar()}</main>')
    rows = ''.join(cand_row(n, sub, dist(n), st, r, sel=(n == PICK), h=52) for n, sub, st, r in CANDS)
    side = (f'<aside aria-label="갈 곳 목록" style="width:336px;flex-shrink:0;display:flex;flex-direction:column;background:#1b201d;border-left:1px solid #3d4740">'
            f'{sec("갈 곳 — 목록", "지도와 같은 후보 · 같은 사유")}<div style="padding:8px 12px;display:flex;flex-direction:column;gap:6px">{search("현 이름 · 초성")}'
            f'{seg(["내 영지", "이웃", "전체"], "전체", "후보 묶음")}<div style="display:flex;gap:12px">{checkbox("가능한 곳만")}{checkbox("가까운 순", True)}</div></div>'
            f'<div role="listbox" aria-label="갈 곳 후보" style="display:flex;flex-direction:column;border-top:1px solid #2c342f">{rows}</div></aside>')
    page31('V31SystemMapPick.dc.html', '시스템 v3.1 — 지도 대상 고르기(데스크톱)', shell_desk('작전실', 'war', mapst + side))


def board_mapmodes():
    """고르기 모드 둘(K8): 관할(주 · 군국) 단위 · 현 여러 개. 같은 MapTargetPicker, mode 만 다르다."""
    W, H = 664, 900
    sc, ox, oy = 1.25, -120, 90  # 郡 보기 그림(MAP['jun'] 840×480, 4px/칸)을 1.25배. 이름표 자리는 K0 B1 보드(420×240 기준)를 옮겼다(가장자리는 안으로 당김).
    rg = [('영천군', 120, 90, 'sel', ''), ('하남윤', 20, 16, 'ok', ''), ('진류군', 300, 20, 'no', '[서버 사유]'), ('여남군', 330, 196, 'ok', ''), ('양국', 330, 120, 'no', '[서버 사유]')]
    clamp = lambda v, lo, hi: max(lo, min(hi, v))  # noqa: E731
    rgs = ''.join(f'<button type="button" class="rgn {k}" aria-label="{n} — {"고른 관할" if k == "sel" else ("고를 수 없음" if k == "no" else "고를 수 있음")}" '
                  f'style="left:{clamp(int(x * 2 * sc) + ox, 70, W - 120)}px;top:{int(y * 2 * sc) + oy}px">{n}{" " + why_tag(r) if r else ""}</button>' for n, x, y, k, r in rg)
    left = (f'<section style="position:relative;width:{W}px;height:{H}px;overflow:hidden;border:1px solid #3d4740;background:#0c0f0e">'
            f'{mapimg("jun", int(840 * sc), int(480 * sc), "영천 일대 — 군 보기", ox, oy)}<div class="dim"></div>{rgs}'
            + pick_bar('관할 고르기 — 지방 관직 임명', '군국 · 주 경계를 누르거나 목록에서 · 고른 관할은 경계를 금색으로')
            + f'<div style="position:absolute;left:12px;right:12px;bottom:12px;background:#1b201d;border:1px solid #3d4740">'
            f'<div role="listbox" aria-label="관할 후보" style="display:flex;flex-direction:column">'
            + opt('영천군', f'예주 · {office_ko("太守")} 자리', ok_chip(), sel=True, h=48) + opt('하남윤', '사례 · [관직]', ok_chip(), h=48)
            + opt('진류군', f'연주 · {office_ko("太守")} 자리', why_tag('[서버 사유]'), no=True, h=48) + '</div>'
            f'<div style="padding:8px;display:flex;gap:8px;border-top:1px solid #2c342f">{btn("영천군 " + office_ko("太守") + "로 정하기", "primary", style="flex:1", attrs="data-input-id=court.appoint")}</div></div></section>')
    OX, OY = 60, 150
    picks = {'양적현': 1, '영양현': 2}
    marks = ''
    for n, st in [('양적현', 'ok'), ('영양현', 'ok'), ('장사현', 'ok'), ('신정현', 'no'), ('번창현', 'ok'), ('양성현', 'ok'), ('영음현', 'ok')]:
        x, y = DESK_PX(*CELLS[n])
        marks += mk(x - OX, y - OY, 'sel' if picks.get(n) else st, n, 'no' if st == 'no' else '', n=picks.get(n))
    chips = ''.join(f'<span class="chip bronze" style="height:44px;gap:6px;padding:0 4px 0 10px"><span class="mono">{i}</span>{n}'
                    f'<button type="button" aria-label="{n} 빼기" style="width:44px;height:44px;margin:-1px -5px -1px 0;border:0;background:transparent;color:#d3b064;display:inline-flex;align-items:center;justify-content:center;padding:0">{icon("close", 14)}</button></span>'
                    for i, n in [(1, '양적현'), (2, '영양현')])
    right = (f'<section style="position:relative;width:{W}px;height:{H}px;overflow:hidden;border:1px solid #3d4740;background:#0c0f0e">'
             f'{mapimg("desk", 1048, 952, "영천 일대 — 현 보기", -OX, -OY)}<div class="dim"></div>{marks}'
             + pick_bar('봉토 고르기 — 봉신 세우기', '누를 때마다 넣고 뺀다 · 2개 고름', done=btn('다 골랐다', 'primary'))
             + f'<div style="position:absolute;left:12px;right:12px;bottom:12px;background:#1b201d;border:1px solid #3d4740;padding:10px 12px;display:flex;flex-direction:column;gap:8px">'
             f'<span class="t2" style="font-size:12px">봉토 현 <b class="bz">2</b> · 정한 수 [미정]</span><div style="display:flex;gap:6px;flex-wrap:wrap">{chips}</div>'
             f'<span class="note">표지의 수 = 고른 차례. 목록 · 찾기로도 넣고 뺀다. 고를 수 없는 현은 점선 + 누르면 사유.</span></div></section>')
    body = f'<main style="flex-grow:1;min-width:0;display:flex;gap:12px;padding:12px 16px;overflow:hidden">{left}{right}</main>'
    page31('V31SystemMapModes.dc.html', '시스템 v3.1 — 고르기 모드: 관할 · 현 여러 개', shell_desk('고르기 모드', 'court', body))


def board_mmappick():
    hx, hy = MOB_PX(*CELLS[HERE])
    marks = ''
    for n in ['밀현', '영양현', '마피영']:
        st = next(c[2] for c in CANDS if c[0] == n)
        x, y = MOB_PX(*CELLS[n])
        marks += mk(x, y, 'sel' if n == PICK else st, n, 'no' if st == 'no' else '')
    off = (f'<button type="button" style="position:absolute;right:8px;top:300px;min-height:44px;padding:0 10px;display:flex;align-items:center;gap:6px;font:inherit;font-size:12px;'
           f'color:#ece6d8;background:rgba(27,32,29,.95);border:1px solid #8fa77a">화면 밖 후보 7 {icon("next", 16)}</button>')
    mx, my = MOB_PX(*CELLS['마피영'])
    popr = pop('마피영 — 이동으로 갈 수 없는 곳입니다', '이동은 이 곳으로 갈 수 없습니다.', '', '이동', f'left:12px;top:{my - 176}px;width:280px')
    conf = (f'<section class="sheet" aria-label="고른 곳" style="bottom:0;height:176px"><div class="grip"></div>'
            f'<div style="height:44px;display:flex;align-items:center;gap:8px;padding:0 12px"><span class="serif" style="font-size:17px;font-weight:900">{PICK}</span>'
            f'{chip("영천군")}{ok_chip()}<span class="mono muted" style="font-size:11px;margin-left:auto">{dist(PICK)} · [미정]순</span></div>'
            f'<div style="padding:0 12px" class="t2">04순 · 4월 중순 00:40에 옮깁니다.</div>'
            f'<div style="padding:10px 12px;display:flex;gap:8px">{btn("이곳으로 정하기", "primary", style="flex:1")}{btn("다시 고르기")}</div></section>')
    main = (f'<main aria-label="지도 — 갈 곳 고르는 중" style="position:relative;width:390px;height:844px;overflow:hidden">'
            f'{mapimg("mob", 390, 844, "양적 일대 지도 — 현 보기")}<div class="dim"></div>{path_line(hx, hy, *MOB_PX(*CELLS[PICK]), 390, 844, dist(PICK))}{marks}'
            f'{me_marker(hx, hy - 22, "in", tag=False)}{popr}{pick_bar("갈 곳 고르기 — 이동", "04순 · 가능 7 · 불가 3", mobile=True)}{off}{conf}</main>')
    page31('V31SystemMMapPick.dc.html', '시스템 v3.1 — 지도 대상 고르기(모바일)', main, w=390, h=844)


def board_mcommand():
    rows = (cmd_row('농지개간', '내정 · 쌀 생산', 'ok', h=52) + cmd_row('징병', '군사 · 성 안에서', 'no', '성 밖', h=52)
            + cmd_row('출병', '군사 · 군단을 이끌고', 'ok', draft=True, h=52) + cmd_row('이동', '이동 · 다른 구역으로', 'ok', h=52)
            + cmd_row('귀환', '이동 · 귀환 성으로', 'no', '귀환 성 없음', h=52) + cmd_row('인재탐색', '인물 · 새 인물 찾기', 'ok', h=52)
            + cmd_row('헌납', '인물 · 부에 바친다', 'wait', h=52) + cmd_row('단련', '개인 · 능력 기르기', 'ok', h=52))
    strip = f'<div style="padding:4px 12px 8px;border-bottom:1px solid #2c342f;overflow:hidden">{turn_strip(4, 12, gap=4, cell_w=96)}</div>'
    body = (f'{strip}<div style="padding:8px 12px 0">{search("명령 찾기 · 초성 · 옛 이름")}</div>'
            f'<div style="padding:8px 12px">{cat_tabs("전체", ("군사",), scroll=True)}</div>'
            f'<div role="listbox" aria-label="명령" style="display:flex;flex-direction:column;border-top:1px solid #2c342f">{rows}</div>')
    main = (f'<main style="position:relative;width:390px;height:844px;overflow:hidden">{mapimg("mob", 390, 844, "양적 일대 지도")}'
            f'<div class="scrim"></div>{sheet("이번 순에 할 일", body, top=40)}</main>')
    page31('V31SystemMCommand.dc.html', '시스템 v3.1 — 명령 흐름 1 명령 고르기(모바일)', main, w=390, h=844)


def board_mcommandargs():
    cand = ''.join(cand_row(n, sub, dist(n), st, r, sel=(n == PICK), h=48) for n, sub, st, r in CANDS[:3])
    head = (f'<div style="display:flex;align-items:center;gap:6px;padding:0 12px 0 4px;height:44px;border-bottom:1px solid #2c342f">'
            f'<a href="#" class="btn sm" style="background:transparent;border:0">{icon("back", 16)}명령 목록</a>'
            f'<span style="margin-left:auto">{turn_caption(4)}</span></div>')
    body = (f'{head}<div style="padding:12px;display:flex;flex-direction:column;gap:10px">'
            f'<div style="display:flex;align-items:baseline;gap:8px"><span class="serif" style="font-size:22px;font-weight:900">이동</span>{chip("04순", "bronze")}</div>'
            + help_strip('다른 구역으로 옮깁니다.')
            + target_field('갈 곳(⟦PROV⟧)', PICK, '영천군 · ' + dist(PICK), picking=False)
            + f'<div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:-6px"><span class="t2" style="font-size:12px">목록에서 · 가까운 순</span>{checkbox("가능만")}</div>'
            f'<div role="listbox" aria-label="갈 곳 후보" style="display:flex;flex-direction:column;border-top:1px solid #2c342f">{cand}</div>'
            f'<span class="muted" style="font-size:11.5px">비용 [미정] · 걸리는 순 [미정]</span></div>')
    foot = btn('04순에 예약', 'primary', style='flex:1', attrs='data-input-id=action.move')
    main = (f'<main style="position:relative;width:390px;height:844px;overflow:hidden">{mapimg("mob", 390, 844, "양적 일대 지도")}'
            f'<div class="scrim"></div>{sheet("이번 순에 할 일", body, top=40, foot=foot)}</main>')
    page31('V31SystemMCommandArgs.dc.html', '시스템 v3.1 — 명령 흐름 2 인자(모바일)', main, w=390, h=844)


def board_people():
    picker = people_picker('전체', sel='순욱')
    sel_chip = (f'<span class="chip bronze" style="height:44px;gap:6px;padding:0 4px 0 10px">순욱'
                f'<button type="button" aria-label="받는 사람 빼기" style="width:44px;height:44px;margin:-1px -5px -1px 0;border:0;background:transparent;color:#d3b064;display:inline-flex;align-items:center;justify-content:center;padding:0">{icon("close", 14)}</button></span>')
    dlg = (f'<section class="dlg" role="dialog" aria-modal="true" aria-label="서신 쓰기" style="position:absolute;left:24px;top:24px;width:720px;height:880px">'
           f'<div style="height:52px;flex-shrink:0;display:flex;align-items:center;justify-content:space-between;padding:0 4px 0 16px;border-bottom:1px solid #2c342f">'
           f'<span class="serif" style="font-size:17px;font-weight:900">서신 쓰기 — 개인</span>{ibtn("close", "닫기", style="border:0;background:transparent")}</div>'
           f'<div style="display:flex;flex-grow:1;min-height:0">'
           f'<div style="width:380px;flex-shrink:0;display:flex;flex-direction:column;border-right:1px solid #2c342f">'
           f'<div style="padding:10px 12px 0;display:flex;align-items:center;gap:8px"><span class="t2" style="font-size:12px">받는 사람</span>{sel_chip}</div>{picker}</div>'
           f'<div style="flex:1;min-width:0;padding:12px 16px;display:flex;flex-direction:column;gap:12px">'
           + field('제목', inp('관도 방면 보고'))
           + field('본문', '<div class="inp area" style="min-height:220px">관도 북쪽에 원소군 선봉이 보입니다. 명을 기다립니다.</div>', '[미정]자까지')
           + '<div class="inset" style="padding:8px 10px"><span class="t2" style="font-size:12px;line-height:1.5">NPC 에게도 보낼 수 있습니다. NPC 가 어떻게 답하는지는 서버 규칙 승인 뒤 정해집니다.</span></div>'
           f'</div></div>'
           f'<div style="padding:12px 16px;display:flex;gap:8px;justify-content:flex-end;border-top:1px solid #2c342f">{btn("취소")}{btn("보내기", "primary", "mail")}</div></section>')

    def mini(title, inner, h):
        return f'<section class="panel" style="height:{h}px">{sec(title, "")}<div style="flex-grow:1;display:flex;flex-direction:column;min-height:0;overflow:hidden">{inner}</div></section>'

    empty = state_empty('이 묶음에 사람이 없습니다', '내 부에 아직 들인 인물이 없습니다.', btn('전체 보기', 'sm'), pad=8)
    none = state_empty('「장비」와 맞는 사람이 없습니다', '이름을 줄이거나 초성으로 찾아 보세요.', pad=8)
    fail = state_error('받는 사람 목록을 불러오지 못했습니다', '빈 목록이 아닙니다 — 불러오기가 실패했습니다.', pad=8)
    disp = (f'<div role="listbox" aria-label="발령 대상" style="display:flex;flex-direction:column">'
            + person_row('sunuk', '순욱', '사람', '조조 소속', '허현', sel=True, h=56)
            + person_row('heojeo', '허저', 'NPC', '내 부', '장사현', off_reason='사람 장수만', h=56) + '</div>'
            + '<span class="note" style="padding:6px 12px">같은 부품 — 발령 대상. NPC 는 사유와 함께 고를 수 없음(원장 TARGET_NOT_HUMAN).</span>')
    side = (f'<div style="position:absolute;left:768px;top:24px;width:592px;display:grid;grid-template-columns:1fr 1fr;gap:12px">'
            + mini('로딩', state_loading(3), 264) + mini('빈 묶음', empty, 264) + mini('찾기 결과 없음', none, 264) + mini('불러오기 실패', fail, 264)
            + f'<section class="panel" style="grid-column:span 2;height:196px">{sec("같은 부품 — 발령 대상", "court.dispatch")}{disp}</section></div>')
    main = f'<main style="flex-grow:1;position:relative;min-width:0;overflow:hidden;background:#101412">{dlg}{side}</main>'
    page31('V31SystemPeople.dc.html', '시스템 v3.1 — 사람 고르기(데스크톱)', shell_desk('서신', 'plaza', main))


def board_mpeople():
    body = people_picker('전체', sel='순욱', mobile=True)
    foot = f'<span class="t2" style="font-size:12px;align-self:center;flex:1">고름: <b class="bz">순욱</b></span>' + btn('이 사람에게', 'primary')
    main = (f'<main style="position:relative;width:390px;height:844px;overflow:hidden;background:#0c0f0e"><div class="scrim"></div>'
            f'{sheet("받는 사람 고르기", body, top=24, foot=foot)}</main>')
    page31('V31SystemMPeople.dc.html', '시스템 v3.1 — 사람 고르기(모바일)', main, w=390, h=844)


def board_states():
    def box(title, sub, inner):
        return f'<section class="panel">{sec(title, sub)}<div style="flex-grow:1;display:flex;flex-direction:column;min-height:0;overflow:hidden">{inner}</div></section>'

    waitb = (f'<div style="padding:10px 12px;display:flex;flex-direction:column;gap:8px">'
             f'<div class="inset" style="padding:8px 10px;display:flex;justify-content:space-between;align-items:center"><span>원소</span>{chip("적대", "rust")}</div>'
             f'<div class="inset" style="padding:8px 10px;display:flex;justify-content:space-between;align-items:center"><span>유표</span>{chip("관계 없음")}</div>'
             f'<div style="display:flex;gap:8px;align-items:center">{btn_off("불가침 제의", "준비 중", input_id="court.nonAggression")}</div>'
             f'<span class="note">읽기는 있고 입력만 없다 — 내용 그대로, 단추는 점선 + 「준비 중」.</span></div>')
    maint = _state('tools', '#7aa7c7', '점검 중입니다', '끝나면 이 화면이 저절로 바뀝니다. 걸어 둔 예약은 그대로 남습니다.', btn('공지 보기', '', 'records', href='#'), pad=8)
    grid = 'display:grid;grid-template-columns:repeat(3,minmax(0,1fr));grid-template-rows:repeat(3,minmax(0,1fr));gap:12px;padding:12px;flex-grow:1;min-height:0'
    main_ = (f'<main style="flex-grow:1;min-width:0;display:flex;flex-direction:column;overflow:hidden"><div style="{grid}">'
             + box('로딩', '0.3초 넘을 때만 · 뼈대', state_loading(4))
             + box('빈', '무엇이 없는지 + 어떻게 채우는지', state_empty('지금 잡아 둔 포로가 없습니다', '전투에서 이기면 포로를 잡을 수 있습니다.', btn('도움말 — 포로', '', 'help', href='#'), pad=8))
             + box('오류', '빈 것과 다르게 · 다시 시도', state_error('창고망을 불러오지 못했습니다', '잠시 뒤 다시 해 보세요.', pad=8))
             + box('권한 없음', '이유 + 이렇게 하면 됩니다', state_denied('발령은 주공만 할 수 있습니다', '주공이 되려면 거병하거나 독립해야 합니다.', '발령', pad=8))
             + box('서버 대기 A — 읽기 없음', '영역 전체', state_waiting('외교 관계를 아직 볼 수 없습니다', '준비되면 이 자리에 바로 보입니다.', pad=8))
             + box('서버 대기 B — 입력만 없음', '내용 그대로 · 단추만', waitb)
             + box('연결 끊김', '마지막 자료 + 다시 잇기', state_stale(pad=8))
             + box('없는 화면(404)', '옛 주소는 308', state_notfound(pad=8))
             + box('점검 중', '게임 전체 — 전체 화면', maint)
             + '</div></main>')
    page31('V31SystemStates.dc.html', '시스템 v3.1 — 상태(P-X01 데스크톱)', shell_desk('상태', 'war', main_))


def board_mstates():
    def blk(inner, h):
        return f'<div style="height:{h}px;flex-shrink:0;border:1px solid #3d4740;background:#1b201d;display:flex;flex-direction:column;overflow:hidden">{inner}</div>'
    main = (f'<main style="height:724px;flex-shrink:0;overflow:hidden;display:flex;flex-direction:column;gap:8px;padding:8px 12px">'
            + blk(state_loading(2), 128)
            + blk(state_empty('지금 잡아 둔 포로가 없습니다', '전투에서 이기면 포로를 잡을 수 있습니다.', pad=8), 150)
            + blk(state_error('창고망을 불러오지 못했습니다', '잠시 뒤 다시 해 보세요.', pad=8), 226)
            + blk(state_waiting('외교 관계를 아직 볼 수 없습니다', '준비되면 이 자리에 보입니다.', pad=8), 180)
            + '</main>')
    page31('V31SystemMStates.dc.html', '시스템 v3.1 — 상태(P-X01 모바일)', shell_mob(main, 'menu', '상태', '전체 메뉴'), w=390, h=844)


def board_mnotfound():
    main = (f'<main style="height:724px;flex-shrink:0;display:flex;flex-direction:column;gap:12px;padding:12px">'
            f'<div style="flex:1;border:1px solid #3d4740;background:#1b201d;display:flex;flex-direction:column">{state_notfound()}</div>'
            f'<div style="height:300px;border:1px solid #3d4740;background:#1b201d;display:flex;flex-direction:column">{state_denied("발령은 주공만 할 수 있습니다", "주공이 되려면 거병하거나 독립해야 합니다.", "발령", pad=8)}</div></main>')
    page31('V31SystemMNotFound.dc.html', '시스템 v3.1 — 없는 화면 · 권한 없음(모바일)', shell_mob(main, 'menu'), w=390, h=844)


def board_banner():
    hx, hy = DESK_PX(*CELLS[HERE])
    mapst = (f'<main style="position:relative;flex-grow:1;min-width:0;overflow:hidden;background:#0c0f0e">{mapimg("desk", 1048, 908, "영천 일대 지도")}'
             f'{me_marker(hx, hy - 22, "in")}'
             f'<section class="panel" style="position:absolute;left:100px;top:420px;width:860px;background:rgba(27,32,29,.97);box-shadow:0 10px 28px rgba(0,0,0,.5)">'
             f'{sec("알림 띠 — 한 번에 하나, 머리줄 바로 아래", "우선: " + BAND_ORDER)}'
             f'<div style="display:flex;flex-direction:column;gap:8px;padding:10px 12px">{band("stop")}{band("notice")}{band("tutorial")}'
             f'<span class="note">띠는 닫지 않는다 — 상태가 풀리면 저절로 사라진다(첫걸음 달성만 6초 뒤 접힘). 따라잡기는 배속과 다 따라잡는 때를 늘 같이 보인다. '
             f'턴 시각 · 상태는 서버 공개 응답(계약판 K10-01)에서.</span></div></section>{view_bar()}</main>')
    aside = (f'<aside aria-label="명령 목록 12순" style="width:336px;flex-shrink:0;display:flex;flex-direction:column;background:#1b201d;border-left:1px solid #3d4740">'
             f'{sec("명령 목록 12순", "직접 행동 · 한 순에 하나")}<div style="padding:12px;display:flex;flex-direction:column;gap:8px">{turn_strip(2, 6, 3)}{turn_caption(2)}</div></aside>')
    page31('V31SystemBanner.dc.html', '시스템 v3.1 — 공통 알림 띠(P-W05 데스크톱)', shell_desk('작전실', 'war', mapst + aside, band('catch')))


def board_mbanner():
    hx, hy = MOB_PX(*CELLS[HERE])
    H = 844 - 56 - 72 - 64
    main = (f'<main style="position:relative;width:390px;height:{H}px;flex-shrink:0;overflow:hidden">{mapimg("mob", 390, 844, "양적 일대 지도", 0, -60)}'
            f'{me_marker(hx, hy - 82, "in", tag=False)}'
            f'<section class="sheet" aria-label="명령 목록 12순" style="bottom:0;height:124px"><div class="grip"></div>'
            f'<div style="padding:4px 12px;display:flex;flex-direction:column;gap:6px">{turn_caption(2)}'
            f'<div style="display:flex;gap:8px">{btn("이번 순에 할 일", "primary", style="flex:1")}{btn("12순", "", "up")}</div></div></section></main>')
    page31('V31SystemMBanner.dc.html', '시스템 v3.1 — 공통 알림 띠(P-W05 모바일)', mtop31() + band('catch', mobile=True) + main + tabbar31('war'), w=390, h=844)


def board_mmaint():
    main = (f'<main style="height:788px;flex-shrink:0;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:14px;padding:24px;text-align:center">'
            f'<span style="width:56px;height:56px;display:inline-flex;align-items:center;justify-content:center;border:1px solid #7aa7c7">{icon("tools", 28, "#7aa7c7")}</span>'
            f'<h2 class="serif" style="margin:0;font-size:22px;font-weight:900">점검 중입니다</h2>'
            f'<p class="t2" style="margin:0;font-size:13px;line-height:1.6">오늘 <span class="mono">22:00</span>부터 [미정]분 동안 점검합니다.<br>그동안 턴이 돌지 않고, 걸어 둔 예약은 그대로 남습니다.<br>끝나면 이 화면이 저절로 바뀝니다.</p>'
            f'<div style="display:flex;flex-direction:column;gap:8px;width:100%">{btn("공지 보기", "", "records", style="width:100%", href="#")}{btn("로비로", "", "lobby", style="width:100%", href="#")}</div></main>')
    page31('V31SystemMMaint.dc.html', '시스템 v3.1 — 점검 중(모바일 전체 화면)', mtop31() + main, w=390, h=844)


def board_season():
    hx, hy = DESK_PX(*CELLS[HERE])
    mapst = (f'<main style="position:relative;flex-grow:1;min-width:0;overflow:hidden;background:#0c0f0e">{mapimg("desk", 1048, 952, "영천 일대 지도")}'
             f'{me_marker(hx, hy - 22, "in")}{view_bar()}</main>')
    aside = (f'<aside aria-label="명령 목록 12순" style="width:336px;flex-shrink:0;display:flex;flex-direction:column;background:#1b201d;border-left:1px solid #3d4740">'
             f'{sec("명령 목록 12순", "직접 행동 · 한 순에 하나")}<div style="padding:12px;display:flex;flex-direction:column;gap:8px">{turn_strip(2, 6, 3)}{turn_caption(2)}</div></aside>')
    page31('V31SystemSeason.dc.html', '시스템 v3.1 — 계절 칩 · 패널(P-K07 자리, 데스크톱)',
           topbar31('작전실', season_dot=True, season_open=True)
           + f'<div style="flex-grow:1;display:flex;min-height:0;position:relative">{rail31("war")}{mapst}{aside}{season_panel()}</div>')


def board_mseason():
    main = (f'<main style="position:relative;width:390px;height:724px;flex-shrink:0;overflow:hidden">{mapimg("mob", 390, 844, "양적 일대 지도", 0, -60)}'
            f'<div class="scrim"></div>{sheet("계절 — 봄", season_panel(mobile=True), height=520)}</main>')
    page31('V31SystemMSeason.dc.html', '시스템 v3.1 — 계절 시트(P-K07 자리, 모바일)', mtop31(season_dot=True) + main + tabbar31('war'), w=390, h=844)


def board_marker():
    def cell(title, inner, w=300, h=200, img='desk', left=0, top=0):
        iw, ih = {'desk': (1048, 952), 'jun': (840, 480), 'prov': (1024, 892)}[img]
        return (f'<figure style="margin:0;display:flex;flex-direction:column;gap:6px"><div style="position:relative;width:{w}px;height:{h}px;overflow:hidden;border:1px solid #3d4740;background:#0c0f0e">'
                f'{mapimg(img, iw, ih, "지도", left, top)}{inner}</div><figcaption class="t2" style="font-size:12px">{title}</figcaption></figure>')

    sx, sy = DESK_PX(*CELLS['양적현'])
    st4 = ('<div style="display:grid;grid-template-columns:repeat(2,300px);gap:12px">'
           + cell('성 안 — 성 칸 위에 핀', me_marker(150, 118, 'in') + '<span class="mlab" style="left:150px;top:128px">양적현</span>', left=-(sx - 150), top=-(sy - 118))
           + cell('성 밖 — 서 있는 구역 칸 위에', me_marker(150, 118, 'out'), left=-(sx - 110), top=-(sy - 60))
           + cell('군단과 함께 — 핀에 제비꼬리 깃발', me_marker(150, 118, 'corps'), left=-(sx - 200), top=-(sy - 170))
           + cell('이동 중 — 지금 칸에 핀 + 갈 곳까지 점선', path_line(150, 118, 270, 180, 300, 200) + me_marker(150, 118, 'move', dest='영양현'), left=-(sx - 150), top=-(sy - 118))
           + '</div>')
    lod = ('<div style="display:flex;gap:12px">'
           + cell('州 보기 — 핀 크기 그대로(화면 48)', me_marker(150, 110, 'in', tag=False), 220, 180, 'prov', -120, -40)
           + cell('郡 보기 — 이름표가 핀을 피한다', me_marker(110, 100, 'in', tag=False) + '<span class="mlab big" style="left:170px;top:120px">영천군</span>', 220, 180, 'jun', 0, -20)
           + cell('화면 밖 — 가장자리 화살표', me_edge('right', 60, '28칸'), 220, 180, 'jun', -400, -200) + '</div>')
    rules = ('<ul class="ul" style="padding:4px 12px">'
             '<li><b>모양</b> — 내 장수 초상(원형 48) + 국가색 링 3 + 금색 테 + 핀 끝. 핀 끝이 실제 자리.</li>'
             '<li><b>자리</b> — 성 안 · 성 밖 · 군단과 함께 · 이동 중. 城 id 가 아니라 장수의 실제 자리(계약판 U-04).</li>'
             '<li><b>언제나 보인다</b> — 州 · 郡 · 縣 같은 화면 크기. 이름표 · 성 · 깃발보다 위. 이름표가 핀을 피한다.</li>'
             '<li><b>화면 밖</b> — 그 방향 가장자리에 「내 위치」 화살표(44 × 52) + 거리.</li>'
             '<li><b>누르면</b> — 내 장수 카드. 대상 고르는 중에는 표지만 보이고 고르기를 막지 않는다.</li>'
             '<li><b>「내 위치로」</b> — 보기 단추 맨 아래. 키보드 Home.</li></ul>')
    main_ = (f'<main style="flex-grow:1;min-width:0;display:flex;gap:12px;padding:12px;overflow:hidden">'
             f'<section class="panel" style="width:640px;flex-shrink:0">{sec("자리 4가지", "縣 보기 16px/칸")}<div style="padding:12px">{st4}</div></section>'
             f'<div style="flex:1;min-width:0;display:flex;flex-direction:column;gap:12px">'
             f'<section class="panel">{sec("보기 수준 · 화면 밖", "")}<div style="padding:12px">{lod}</div></section>'
             f'<section class="panel" style="flex:1">{sec("규칙", "K2 와 맞춤")}{rules}<div style="padding:8px 12px">{me_card("in", "양적현", "영천군")}</div></section></div></main>')
    page31('V31SystemMarker.dc.html', '시스템 v3.1 — 내 위치 표지(데스크톱)', shell_desk('시스템 v3.1 — 내 위치', 'war', main_))


def board_mmarker():
    hx, hy = MOB_PX(*CELLS[HERE])
    yx, yy = MOB_PX(*CELLS['영양현'])
    labs = ''.join(mlab(n, *MOB_PX(*CELLS[n]), dim=False) for n in ['밀현', '영양현', '마피영'])
    card = me_card('move', '양적현 → 영양현', '영천군', style='position:absolute;left:35px;bottom:80px')
    main = (f'<main aria-label="지도" style="position:relative;width:390px;height:844px;overflow:hidden">{mapimg("mob", 390, 844, "양적 일대 지도")}'
            f'{labs}{path_line(hx, hy, yx, yy, 390, 844)}{me_marker(hx, hy, "move", dest="영양현")}{me_edge("right", 520, "28칸")}{card}'
            f'<div style="position:absolute;left:8px;top:8px;right:8px;display:flex;gap:6px">{chip("내 위치 — 이동 중 · 누르면 카드", "bronze")}</div>'
            f'<div style="position:absolute;left:0;right:0;bottom:0">{tabbar31("war")}</div></main>')
    page31('V31SystemMMarker.dc.html', '시스템 v3.1 — 내 위치 표지(모바일)', main, w=390, h=844)


BOARDS = [board_index, board_nav, board_shell, board_tokens, board_parts, board_mparts, board_page, board_mpage, board_command, board_mcommand,
          board_mcommandargs, board_mappick, board_mmappick, board_mapmodes, board_marker, board_mmarker, board_people, board_mpeople,
          board_states, board_mstates, board_mnotfound, board_banner, board_mbanner, board_mmaint, board_season, board_mseason]

if __name__ == '__main__':
    import glob
    for f in glob.glob(os.path.join(P, 'V31System*.dc.html')):
        os.remove(f)
    for b in BOARDS:
        b()
    print(f'ok v31system — {len(BOARDS)} boards' + ('' if HAVE_ASSETS else ' (v31assets 없음: 그림 자리 표시)'))
