# 캔버스 v3.1 · K4 — 작전실 · 부 · 영지 · 공성 · 조정(13페이지 × 데스크톱 · 모바일 + 상태).
# 설계서: 메타 reports/opensamguk/tasks/2026-09-30-k4-design-spec.md. 시스템: v31system.py(K3), 그림: v31assets.py(K0) — 둘 다 고치지 않는다.
#   PYTHONPATH=<v31assets 폴더> python3 boards_v31_k4.py   → project/V31K4*.dc.html
# 예시 상황: 하후돈(조조 소속) · 200년 3월 중순 · 영천군. 내 부 = 허저 · 이전(NPC) + 무명 인물 1. 순욱 · 악진 · 우금 · 만총 = 사람 장수.
# 인물 능력치는 「—」(코에이 수치 커밋 금지), 정하지 않은 값은 [미정], 서버가 줄 값은 [값].
import glob
import os
from html.parser import HTMLParser

from v31system import *  # noqa: F401,F403
from v31system import CELLS, DESK_PX, MOB_PX, TURNS, TURN_WORD, NATION, P, MAP

BOARDS = []


def board(fn):
    BOARDS.append(fn)
    return fn


RET_TABS = ['편성 · 결속', '인물 일람', '월단평', '포로 · 등용']
TER_TABS = ['배치 · 방침 · 공사', '현 상세', '군 내정 현황', '창고망 · 보급']
CORPS_TABS = ['군단 · 세력 작전', '공성', '전투', '시야 · 첩보']
COURT_TABS = ['발령 · 포상 · 조정 결정', '관직 · 봉신', '외교', '참모 제안', '황실', '세력']
BU = '하후돈의 막부'  # 페이지 머리 = me.buName(자리에 따라 막부 → 군부 …). 레일 · 탭은 「부」 고정(K0).
RESN = ['금', '쌀', '철', '목재', '말']


# ------------------------------------------------------------------ 공용 조각
def panel(title, sub, inner, style=''):
    return f'<section class="panel" style="{style}">{sec(title, sub)}{inner}</section>'


def desk_main(head, body, pad=True):
    p = 'padding:12px;' if pad else ''
    return (f'<main style="flex-grow:1;min-width:0;display:flex;flex-direction:column;overflow:hidden">{head}'
            f'<div style="flex-grow:1;min-height:0;display:flex;gap:12px;{p}overflow:hidden">{body}</div></main>')


def mob_first_line(title, sub=''):
    """모바일 묶음 첫 화면의 페이지 첫 줄(K0 판정 2026-09-30) — 부 이름 = me.buName. K3 가 부품으로 정하면 그것으로 바꾼다."""
    return (f'<div style="height:48px;flex-shrink:0;display:flex;align-items:center;gap:8px;padding:0 12px;border-bottom:1px solid #2c342f">'
            f'<h2 class="serif" style="margin:0;font-size:19px;font-weight:900;white-space:nowrap">{title}</h2>{f"<span class=muted style=font-size:11.5px>{sub}</span>" if sub else ""}</div>')


def mob_main(inner, tabs=None, on=None, h=724, foot='', first=''):
    t = (mob_first_line(first) if first else '') + (mtabs_row(tabs, on) if tabs else '')
    f = (f'<div style="position:absolute;left:0;right:0;bottom:0;min-height:64px;display:flex;gap:8px;padding:10px 12px;background:#1b201d;'
         f'border-top:1px solid #3d4740">{foot}</div>') if foot else ''
    return (f'<main style="height:{h}px;flex-shrink:0;position:relative;overflow:hidden;display:flex;flex-direction:column">{t}'
            f'<div style="flex-grow:1;min-height:0;display:flex;flex-direction:column;gap:8px;padding:10px 12px {76 if foot else 10}px;overflow:hidden">{inner}</div>{f}</main>')


def gbar(label, pct, val='[값]', cap='[상한]', warn=False, h=26, w_label=44, compact=False):
    col = 'linear-gradient(90deg,#9c4a3f,#e08a7c)' if warn else 'linear-gradient(90deg,#9c7f3f,#d3b064)'
    return (f'<div style="height:{h}px;display:grid;grid-template-columns:{w_label}px minmax(0,1fr) auto;gap:8px;align-items:center;font-size:12px">'
            f'<span class="t2">{label}</span><div class="g-bar"><i style="width:{pct}%;background:{col}"></i></div>'
            f'<span class="mono {"rs" if warn else "t2"}" style="font-size:11px;white-space:nowrap">{val if compact else val + " / " + cap}</span></div>')


INDI = [('호구', 62), ('전답', 48), ('시장', 35), ('치안', 55), ('민심', 28), ('방비', 40), ('성벽', 44)]  # 막대 길이는 배치 예시(값 아님)


def indicators(warn=('민심',), cols=1, h=26, dim=False, compact=False):
    rows = ''.join(gbar(n, p, warn=n in warn, h=h, w_label=32 if compact else 44, compact=compact) for n, p in INDI)
    st = 'opacity:.55;' if dim else ''
    return f'<div style="display:grid;grid-template-columns:repeat({cols},minmax(0,1fr));gap:2px 14px;{st}">{rows}</div>'


def resline(vals=None, size=''):
    vals = vals or ['[값]'] * 5
    return '<div style="display:flex;gap:10px;flex-wrap:wrap">' + ''.join(res(n, v) for n, v in zip(RESN, vals)) + '</div>'


def row(inner, h=52, style=''):
    return f'<div style="min-height:{h}px;display:flex;align-items:center;gap:10px;padding:4px 12px;border-bottom:1px solid #2c342f;{style}">{inner}</div>'


def name_block(name, sub='', chips=''):
    return (f'<div style="display:flex;flex-direction:column;min-width:0;flex:1;gap:2px"><div style="display:flex;align-items:center;gap:6px;min-width:0">'
            f'<span class="serif" style="font-size:15px;font-weight:700;white-space:nowrap">{name}</span>{chips}</div>'
            f'{f"<span class=muted style=font-size:11.5px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis>{sub}</span>" if sub else ""}</div>')


def nat(n):
    return f'<span style="display:inline-flex;align-items:center;gap:5px;white-space:nowrap"><i class="dot" style="background:{NATION.get(n, "#5a625c")}"></i>{n}</span>'


def link(t, style=''):
    return f'<a href="#" style="min-height:44px;min-width:44px;display:inline-flex;align-items:center;font-size:12.5px;{style}">{t}</a>'


def note(t, cls='muted'):
    return f'<span class="{cls}" style="font-size:11.5px;line-height:1.5">{t}</span>'


def slot_row(no, d, t, c, st, tgt='', mark='', cur=False, h=52):
    """12순 열 한 줄(K4 TurnList). 빈 순 = 「+ 예약」(그 순으로 명령 흐름), 찬 순 = 누르면 편집(바꾸기 · 비우기 · 앞 순으로 · 뒤 순으로)."""
    name = (f'<span class="serif" style="font-size:15px;font-weight:700;flex-shrink:0">{c}</span>' if c
            else '<span class="muted" style="font-size:13px;flex-shrink:0">빈 순</span>')
    tg = (f'<span class="{"rs" if st == "warn" else "muted"}" style="font-size:11px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;min-width:0">{tgt}</span>'
          if tgt else '')
    mk_ = chip(mark, 'info') if mark else ''
    tone = {'done': 'moss', 'res': 'bronze', 'warn': 'rust', 'empty': ''}[st]
    right = (chip(TURN_WORD[st].replace(' · 경고', ''), tone) if c else
             f'<a class="btn sm" href="#" aria-label="{no:02d}순에 예약" style="min-width:64px">+ 예약</a>')
    bg = 'background:rgba(211,176,100,.08);box-shadow:inset 3px 0 0 #d3b064;' if cur else ''
    lab = f'{no:02d}순 {d} {t} — {c or "빈 순"}' + (' · 누르면 바꾸기 · 비우기 · 앞 순으로 · 뒤 순으로' if c else '')
    tag = 'button type="button"' if c else 'div'
    close = 'button' if c else 'div'
    return (f'<{tag} aria-label="{lab}" style="height:{h}px;flex-shrink:0;width:100%;display:grid;grid-template-columns:24px minmax(0,1fr) auto;gap:8px;align-items:center;'
            f'padding:0 10px;font:inherit;text-align:left;color:#ece6d8;background:transparent;border:0;border-bottom:1px solid #2c342f;{bg}">'
            f'<span class="mono muted" style="font-size:12px">{no:02d}</span><span style="display:flex;flex-direction:column;gap:1px;min-width:0">'
            f'<span class="mono t2" style="font-size:11px;white-space:nowrap">{d} · {t}</span><span style="display:flex;align-items:center;gap:8px;min-width:0">{name}{tg}{mk_}</span></span>'
            f'{right}</{close}>')


TGT = {1: '장사현', 2: '하후돈 군단', 3: '장사현 — 실행 때 성 밖이면 무효', 5: '찾은 인재 — 결과 대기', 6: '이전'}
MARK = {4: '배치 효력 시작'}
NEXT = 2


def slots(h=52, n=12):
    return ''.join(slot_row(no, d, t, c, st, TGT.get(no, ''), MARK.get(no, ''), no == NEXT, h) for no, d, t, c, st in TURNS[:n])


def standing_tile(n, t, sub='', tone='bz', href='#'):
    return (f'<a href="{href}" style="min-width:0;height:52px;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:1px;background:#141816;'
            f'border:1px solid {"#c96b5d" if tone == "rs" else "#2c342f"};color:#ece6d8">'
            f'<span class="mono {tone}" style="font-size:15px;font-weight:700;white-space:nowrap">{n}</span>'
            f'<span class="t2" style="font-size:10.5px;white-space:nowrap">{t}{(" · " + sub) if sub else ""}</span></a>')


STANDING6 = (standing_tile('행군 중', '출병') + standing_tile('3', '배치') + standing_tile('4', '방침')
             + standing_tile('2', '공사', '1 멈춤') + standing_tile('3 · 1', '계책', '손패 · 설치') + standing_tile('응답 1', '발령', '대기 1', 'rs'))


# ------------------------------------------------------------------ 지도 조각
W_MAP, H_MAP = 1048, 952
LABEL_SET = ['양적현', '장사현', '영음현', '허현', '영양현', '신정현', '번창현', '임영현', '양성현', '언릉현', '위씨현', '마피영']


def desk_labels(skip=()):
    out = ''
    for n in LABEL_SET:
        if n in skip:
            continue
        x, y = DESK_PX(*CELLS[n])
        if 20 < x < W_MAP - 20 and 20 < y < H_MAP - 40:
            out += mlab(n, x, y + 16, big=(n == '양적현'), dim=False)
    return out


def map_btn(ic, label, style=''):
    return ibtn(ic, label, style=f'background:rgba(20,24,22,.92);{style}')


def place_band(t='영천군 · 예주', sub='현 보기 · 보이는 곳 이름'):
    """위 왼쪽: 보는 곳 이름(고르기 모드에선 고르기 띠가 이 자리를 쓴다)."""
    return (f'<div style="position:absolute;left:56px;top:12px;min-height:44px;display:flex;align-items:center;gap:8px;padding:0 12px;background:rgba(20,24,22,.92);'
            f'border:1px solid #3d4740"><span class="serif" style="font-size:15px;font-weight:900">{t}</span><span class="muted" style="font-size:11px">{sub}</span></div>')


def minimap(right=12, bottom=12):
    src = MAP.get('prov', '')
    img = (f'<img src="{src}" alt="천하 개관 — 지금 보는 곳 표시" style="width:176px;height:153px;display:block">' if src
           else '<div style="width:176px;height:153px;background:#1b2a22"></div>')
    return (f'<div style="position:absolute;right:{right}px;bottom:{bottom}px;width:176px;height:153px;border:1px solid #3d4740;overflow:hidden">{img}'
            f'<span style="position:absolute;left:94px;top:64px;width:14px;height:12px;border:2px solid #ffd36d"></span></div>')


def sel_box(x, y):
    return f'<span aria-hidden="true" style="position:absolute;left:{x - 20}px;top:{y - 20}px;width:40px;height:40px;border:2px solid #ffd36d;box-shadow:0 0 0 2px rgba(12,15,14,.8)"></span>'


def sel_card(style):
    kv3 = f'<div style="display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:6px;padding:8px 8px 4px">{kv("소속", nat("조조"))}{kv("보급", "이어짐", "ms")}{kv("주둔", "하후돈 군단")}</div>'
    return (f'<section class="panel" aria-label="고른 현 — 장사현" style="position:absolute;width:320px;background:rgba(27,32,29,.97);box-shadow:0 10px 28px rgba(0,0,0,.5);{style}">'
            f'<div style="min-height:48px;display:flex;align-items:center;gap:6px;padding:0 4px 0 12px;border-bottom:1px solid #2c342f">'
            f'<span class="serif" style="font-size:16px;font-weight:900">장사현</span>{chip("영천군")}{chip("[등급]")}{chip("내 귀환 성", "bronze")}'
            f'{ibtn("close", "닫기", style="margin-left:auto;border:0;background:transparent")}</div>{kv3}'
            f'<div style="padding:0 8px 6px;display:flex;gap:6px;flex-wrap:wrap">{chip("지금 보임", "moss")}{chip("특산 [자원] [값]/월")}{chip("내 설치 계책 1", "info")}</div>'
            f'<div style="padding:4px 12px 8px"><span class="muted" style="font-size:11px">현 형편(지금 보일 때만)</span>{indicators(cols=2, h=22, compact=True)}</div>'
            f'<div style="display:flex;gap:6px;padding:0 8px 8px">{btn("현 상세", "sm", style="flex:1", href="#")}'
            f'{btn("첩보", "sm", style="flex:1", attrs="data-input-id=action.scout")}{btn("여기로 명령", "primary sm", style="flex:1.4", href="#")}</div></section>')


def drawer_handle(n=5):
    return (f'<button type="button" aria-expanded="false" aria-label="지난 순 — 새 기록 {n}" style="position:absolute;left:0;top:120px;width:44px;height:132px;display:flex;'
            f'flex-direction:column;align-items:center;justify-content:center;gap:6px;font:inherit;font-size:12px;color:#ece6d8;background:rgba(27,32,29,.95);border:1px solid #3d4740;'
            f'border-left:0;writing-mode:vertical-rl;letter-spacing:2px">지난 순 <span class="chip bronze" style="writing-mode:horizontal-tb">{n}</span></button>')


def layer_fail():
    return (f'<button type="button" style="position:absolute;left:360px;top:12px;min-height:44px;display:flex;align-items:center;gap:8px;padding:0 12px;font:inherit;font-size:12px;'
            f'color:#e08a7c;background:rgba(27,32,29,.95);border:1px dashed #c96b5d">{icon("alert", 16, "#e08a7c")}공사 · 포위 표지를 못 불러왔습니다 — 다시</button>')


def turns_aside(h_slot=52):
    return (f'<aside aria-label="명령 목록 12순" style="width:336px;flex-shrink:0;display:flex;flex-direction:column;background:#1b201d;border-left:1px solid #3d4740">'
            f'{sec("명령 목록 12순", "직접 행동 · 한 순에 하나")}{slots(h_slot)}{sec("맡겨 둔 일", "순마다 스스로 굴러간다")}'
            f'<div style="padding:8px;display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:4px">{STANDING6}</div>'
            f'<div style="padding:8px;margin-top:auto;border-top:1px solid #2c342f;display:flex;gap:6px">{btn("이번 순에 할 일 — 02순", "primary", style="flex:1", href="#")}'
            f'{btn("당기기", "", attrs="aria-label=\"12순 전체를 한 칸 앞으로\"")}{btn("밀기", "", attrs="aria-label=\"12순 전체를 한 칸 뒤로\"")}</div></aside>')


def warroom_map(drawer=''):
    mx, my = DESK_PX(*CELLS['양적현'])
    sx, sy = DESK_PX(*CELLS['장사현'])
    ctrl = (f'<div style="position:absolute;right:12px;top:12px;display:flex;gap:6px">{map_btn("layers", "지도 레이어")}{map_btn("legend", "범례")}</div>')
    card = '' if drawer else sel_card('left:690px;top:120px')
    return (f'<div aria-label="지도" role="region" style="position:relative;width:{W_MAP}px;height:{H_MAP}px;flex-shrink:0;overflow:hidden;background:#0c0f0e">'
            f'{mapimg("desk", W_MAP, H_MAP, "영천 일대 지도 — 현 보기")}{desk_labels()}{sel_box(sx, sy)}{me_marker(mx, my, "in")}'
            f'{place_band() if not drawer else ""}{ctrl}{layer_fail() if not drawer else ""}{drawer_handle() if not drawer else ""}'
            f'{view_bar("현", "left:392px;bottom:12px" if drawer else "left:12px;bottom:12px")}{minimap()}{card}{drawer}</div>')  # 서랍(380, 비모달)이 열리면 보기 단추를 서랍 오른쪽 + 12로(K0 판정)


# ================================================================== P-W01 작전실
@board
def warroom():
    body = f'{warroom_map()}{turns_aside()}'
    page31('V31K4WarRoom.dc.html', 'K4 P-W01 작전실 — 데스크톱',
           shell_desk('작전실', 'war', f'<main style="flex-grow:1;min-width:0;display:flex;overflow:hidden">{body}</main>'))


def mob_map_base(extra='', pill=True, sheet_html=None):
    hx, hy = MOB_PX(*CELLS['양적현'])
    labs = mlab('양적현', hx, hy + 14, big=True, dim=False)
    top = (f'<div style="position:absolute;left:8px;right:8px;top:8px;display:flex;align-items:center;gap:6px">'
           f'<button type="button" class="hchip" style="background:rgba(20,24,22,.94)">{icon("season", 16, "#d3b064")}3월 중순 · 다음 턴 21:40</button>'
           f'<button type="button" class="hchip" aria-label="지난 순 — 새 기록 5" style="background:rgba(20,24,22,.94)">지난 순 {chip("5", "bronze")}</button>'
           f'<span style="margin-left:auto;display:flex;gap:6px">{map_btn("mail", "서신 2통")}{map_btn("help", "이 화면 도움말")}</span></div>')
    side = f'<div style="position:absolute;right:8px;top:64px;display:flex;flex-direction:column;gap:6px">{map_btn("layers", "지도 레이어")}{map_btn("legend", "범례")}</div>'
    pl = ('<button type="button" style="position:absolute;left:64px;right:8px;bottom:196px;min-height:48px;display:flex;align-items:center;gap:8px;padding:0 12px;font:inherit;'
          'color:#ece6d8;background:rgba(27,32,29,.97);border:1px solid #9c7f3f;text-align:left"><span class="serif" style="font-weight:900;font-size:15px">양적현</span>'
          f'<span class="muted" style="font-size:12px">영천군 치소</span>{chip("내 위치", "bronze")}<span style="margin-left:auto">{icon("up", 16)}</span></button>') if pill else ''
    peek = sheet_html if sheet_html is not None else (
        f'<section class="sheet" aria-label="명령 목록 12순" style="bottom:64px;height:124px"><div class="grip"></div>'
        f'<div style="height:52px;display:grid;grid-template-columns:24px minmax(0,1fr) auto;gap:8px;align-items:center;padding:0 12px;background:rgba(211,176,100,.08);'
        f'box-shadow:inset 3px 0 0 #d3b064"><span class="mono muted" style="font-size:12px">02</span><span style="display:flex;flex-direction:column;min-width:0">'
        f'<span class="mono t2" style="font-size:11px">3월 하순 · 22:40</span><span><span class="serif" style="font-size:15px;font-weight:700">훈련</span> '
        f'<span class="muted" style="font-size:11px">하후돈 군단</span></span></span>{chip("예약", "bronze")}</div>'
        f'<div style="padding:6px 12px;display:flex;gap:8px">{btn("이번 순에 할 일", "primary", style="flex:1", href="#")}'
        f'<button type="button" class="btn" aria-expanded="false">{icon("up", 18)}12순 · 맡겨 둔 일</button></div></section>')
    return (f'<main aria-label="지도" style="position:relative;width:390px;height:844px;overflow:hidden">{mapimg("mob", 390, 844, "양적 일대 지도 — 현 보기")}'
            f'{labs}{me_marker(hx, hy, "in")}{top}{side}{view_bar("현", "left:8px;bottom:196px")}{pl}{extra}{peek}'
            f'<div style="position:absolute;left:0;right:0;bottom:0">{tabbar31("war")}</div></main>')


@board
def mwarroom():
    warn = ('<button type="button" style="position:absolute;left:64px;right:60px;top:64px;min-height:44px;display:flex;align-items:center;gap:6px;padding:0 10px;font:inherit;'
            f'font-size:11.5px;color:#e08a7c;background:rgba(27,32,29,.95);border:1px dashed #c96b5d">{icon("alert", 16, "#e08a7c")}시야를 못 불러 안개를 비웠습니다 — 다시</button>')
    page31('V31K4MWarRoom.dc.html', 'K4 P-W01 작전실 — 모바일', mob_map_base(warn), w=390, h=844)


# ================================================================== P-W04 지난 순 서랍
def feed_item(c, st, title, text, action=''):
    stc = chip(st[0], st[1]) if st else ''
    return (f'<div style="padding:10px 12px;border-bottom:1px solid #2c342f;display:flex;flex-direction:column;gap:5px">'
            f'<div style="display:flex;align-items:center;gap:6px">{cat(c)}{stc}</div>'
            f'<span class="serif" style="font-size:13px;font-weight:700">{title}</span><span class="t2" style="font-size:12px;line-height:1.45">{text}</span>{action}</div>')


def request_card():
    return (f'<div style="margin-top:4px;padding:10px;background:#141816;border:1px solid #9c7f3f;display:flex;flex-direction:column;gap:8px">'
            f'<div style="display:flex;gap:10px">{portrait("jojo", "조조", 34, 48)}<div style="display:flex;flex-direction:column;gap:2px;min-width:0">'
            f'<span class="serif" style="font-size:14px;font-weight:900">진류 방면 군단장으로 부임하라</span>'
            f'<span class="muted" style="font-size:11px">주공 조조 · 기한 [미정] · 지나면 수락 · 순을 쓰지 않음</span></div></div>'
            f'<div style="display:flex;gap:6px">{input_btn("수락", "AVAILABLE", input_id="court.dispatchReply", style="flex:1")}'
            f'{input_btn("거절", "AVAILABLE", input_id="court.dispatchReply", kind="danger", style="flex:1")}</div>'
            f'{link("조정에서 모두 보기 →", "min-height:44px")}</div>')


FEED = (feed_item('개인 행적', ('무효', 'rust'), '등용 · 3월 상순', '대상이 같은 구역에 없어 무효가 되었습니다. 비용은 들지 않았습니다.', why('왜?'))
        + feed_item('조정 공문', ('응답 대기', 'bronze'), '발령 도착 · 주공 조조', '관도 방면 군단장 발령이 왔습니다.', request_card())
        + feed_item('전장 보고', None, '행군 · 하후돈 군단', '군단이 움직였습니다.', chip('누가 · 어디 · 리플레이 — 준비 중', 'info'))
        + feed_item('부 · 세력', None, '이탈 판정 순서', '무명 인물 1명이 이탈 판정 1순위입니다.', link('월단평 열기 →'))
        + feed_item('천하 정세', None, '점령 · 진류현', '원소가 진류현을 차지했습니다.'))
FEED_M = FEED.split('<div style="padding:10px 12px;border-bottom:1px solid #2c342f;display:flex;flex-direction:column;gap:5px">')
FEED_M = '<div style="padding:10px 12px;border-bottom:1px solid #2c342f;display:flex;flex-direction:column;gap:5px">'.join(FEED_M[:4])


def drawer_body(mobile=False):
    feed = FEED_M if mobile else FEED
    tabs = seg(['내 12순', '부 · 세력', '전체'], '내 12순', '범위', style='padding:8px 12px 0')
    cats = ('<div style="display:flex;gap:4px;padding:8px 12px;overflow:hidden;flex-shrink:0">'
            + ''.join(f'<button type="button" class="btn sm" aria-pressed="{"true" if c == "전체" else "false"}" style="flex-shrink:0;'
                      f'{"background:#d3b064;color:#161410;border-color:#9c7f3f" if c == "전체" else ""}">{c}</button>'
                      for c in ['전체', '개인', '부 · 세력', '조정', '전장', '천하']) + '</div>')
    return f'{tabs}{cats}<div style="flex-grow:1;min-height:0;overflow:hidden;border-top:1px solid #2c342f">{feed}</div>'


@board
def drawer():
    dr = (f'<section aria-label="지난 순" style="position:absolute;left:0;top:0;bottom:0;width:380px;display:flex;flex-direction:column;background:rgba(27,32,29,.98);'
          f'border-right:1px solid #9c7f3f;box-shadow:10px 0 28px rgba(0,0,0,.5)">'
          f'<div style="height:48px;flex-shrink:0;display:flex;align-items:center;gap:8px;padding:0 4px 0 12px;border-bottom:1px solid #2c342f">'
          f'<span class="serif" style="font-size:16px;font-weight:900">지난 순</span><span class="muted mono" style="font-size:11px">200년 3월 상순 – 중순</span>'
          f'{ibtn("close", "서랍 닫기(Esc)", style="margin-left:auto;border:0;background:transparent")}</div>{drawer_body()}'
          f'<a href="#" style="height:48px;flex-shrink:0;display:flex;align-items:center;justify-content:center;border-top:1px solid #2c342f;font-size:13px">기록 전체 보기 →</a></section>')
    body = f'{warroom_map(drawer=dr)}{turns_aside()}'
    page31('V31K4Drawer.dc.html', 'K4 P-W04 지난 순 서랍 — 데스크톱',
           shell_desk('작전실', 'war', f'<main style="flex-grow:1;min-width:0;display:flex;overflow:hidden">{body}</main>'))


@board
def mdrawer():
    sh = (f'<div class="scrim"></div><section class="sheet" role="dialog" aria-label="지난 순" style="top:72px;bottom:64px"><div class="grip"></div>'
          f'<div style="height:44px;flex-shrink:0;display:flex;align-items:center;justify-content:space-between;padding:0 4px 0 16px">'
          f'<span class="serif" style="font-size:17px;font-weight:900">지난 순 <span class="muted mono" style="font-size:11px;font-weight:400">3월 상순 – 중순</span></span>'
          f'{ibtn("close", "시트 닫기", style="border:0;background:transparent")}</div>{drawer_body(True)}</section>')
    page31('V31K4MDrawer.dc.html', 'K4 P-W04 지난 순 — 모바일 시트', mob_map_base(pill=False, sheet_html=sh), w=390, h=844)


# ================================================================== 부(府) — P-R01~R05
def renown_band(over=True, empty=False):
    warn = chip('상한 초과 — 다음 월단평(4월 상순)에 충성 낮은 인물부터 이탈 판정', 'rust') if over else chip('상한 안', 'moss')
    cnt = '인물 0 · 부대 0' if empty else '인물 3 · 부대 2'
    return (f'<div style="min-height:56px;flex-shrink:0;display:flex;align-items:center;gap:16px;padding:0 16px;border-bottom:1px solid #2c342f;background:#141816">'
            f'<span class="t2" style="font-size:12px">명망</span><span class="mono bz" style="font-size:18px;font-weight:700">[미정]</span>'
            f'<span class="t2" style="font-size:12px">부 코스트 합 / 명망 상한</span><div class="g-bar" style="width:220px"><i style="width:{0 if empty else 86}%"></i></div>'
            f'<span class="mono" style="font-size:12px">[값] / [미정]</span>{warn if not empty else ""}<span class="muted" style="font-size:12px;margin-left:auto">{cnt}</span>'
            f'{link("월단평 →")}</div>')


PEOPLE_R = [('heojeo', '허저', '향당 · 초현', '군단장 · 영천 군단', '높음', 'moss', '', '[미정]'),
            ('ijeon', '이전', '향당 · 거야현', '현령 · 장사현', '보통', '', '', '[미정]'),
            ('', '무명 공조', '결속 없음', '미배치', '낮음', 'rust', '이탈 1순위', '[미정]')]


def person_list_row(k, n, bond, post, loy, tone, risk, cost, sel=False, mobile=False):
    chips = chip('공용', 'info') if k == '' else chip('유일', 'bronze')
    risk_c = chip(risk, 'rust') if risk else ''
    return (f'<button type="button" role="option" class="opt" aria-selected="{"true" if sel else "false"}" style="min-height:{72 if mobile else 68}px;align-items:center">'
            f'{portrait(k, n, 40, 56)}<span style="display:flex;flex-direction:column;gap:4px;min-width:0;flex:1">'
            f'<span style="display:flex;align-items:center;gap:6px"><span class="nm">{n}</span>{chips}{risk_c}</span>'
            f'<span style="display:flex;gap:6px;flex-wrap:wrap">{chip(bond, "bronze" if "향당" in bond else "")}{chip("충성 " + loy, tone)}</span>'
            f'<span class="sub">자리 {post}</span></span>'
            f'<span class="end" style="flex-direction:column;align-items:flex-end;gap:0"><span class="muted" style="font-size:10px">코스트</span><span class="mono bz" style="font-size:15px;font-weight:700">{cost}</span></span></button>')


STAT5 = ['통솔', '무력', '지력', '정치', '매력']
APT4 = [('장', '군단'), ('리', '내정'), ('사', '계책'), ('사자', '외교')]


def stat_grid(cols=5):
    return (f'<div style="display:grid;grid-template-columns:repeat({cols},minmax(0,1fr));gap:6px">'
            + ''.join(f'<div class="inset" style="display:flex;flex-direction:column;align-items:center;padding:6px 0"><span class="muted" style="font-size:10px">{s}</span>'
                      f'<span class="mono" style="font-size:17px;font-weight:700">—</span></div>' for s in STAT5) + '</div>')


def apt_grid():
    return (f'<div style="display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:6px">'
            + ''.join(f'<div class="inset" style="padding:6px 8px;display:flex;flex-direction:column;gap:1px"><span class="muted" style="font-size:10px">{a} · {b}</span>'
                      f'<span class="mono" style="font-size:14px;font-weight:700">—</span></div>' for a, b in APT4) + '</div>')


def state8(hidden=()):
    items = [('위치', '영천군 · 양적현'), ('자리', '군단장'), ('부상 · 피로', '없음 · [값]'), ('녹봉', '[값] 금 / 월'),
             ('보물 칸', '[미정]'), ('경험', '[미정]'), ('충성', '높음'), ('생몰', '?–?')]
    return (f'<div style="display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:6px">'
            + ''.join(kv(a, '? — 내 부 인물만' if a in hidden else b, 'muted' if a in hidden else '') for a, b in items) + '</div>')


def unit_cards():
    def u(n, kind, cmd, warn=False):
        c = f'<span class="{"rs" if warn else "t2"}" style="font-size:12px">지휘 {cmd}</span>'
        return (f'<div style="padding:8px 12px;border-bottom:1px solid #2c342f;display:flex;flex-direction:column;gap:4px">'
                f'<div style="display:flex;align-items:center;gap:6px"><span class="serif" style="font-weight:700">{n}</span>{chip(kind)}<span style="margin-left:auto">{c}</span></div>'
                f'<span class="mono t2" style="font-size:11.5px">병력 [값] · 훈련 [값] · 사기 [값] · 피로 [값] · 쌀 [값]순 분</span></div>')
    return u('하후돈 부곡 1', '보병', '허저') + u('하후돈 부곡 2', '궁병', '없음 — 움직일 수 없음', True)


@board
def retinue():
    lst = (f'<section class="panel" style="width:440px;flex-shrink:0">{sec("인물 카드", "직접 거느린 인물 3")}'
           f'<div style="padding:8px 12px">{seg(["등록순", "코스트", "충성", "이탈 판정"], "등록순", "정렬")}</div>'
           f'<div role="listbox" aria-label="부의 인물" style="border-top:1px solid #2c342f">'
           + ''.join(person_list_row(*p, sel=(i == 0)) for i, p in enumerate(PEOPLE_R))
           + f'</div><div style="margin-top:auto;padding:10px 12px;display:flex;flex-direction:column;gap:8px;border-top:1px solid #2c342f">'
           f'{help_strip("등용과 인재탐색은 명령 목록 12순에 넣는 직접 행동입니다.")}'
           f'<div style="display:flex;gap:8px">{input_btn("등용 — 명령 목록에 넣기", "AVAILABLE", input_id="action.employ", style="flex:1")}'
           f'{input_btn("인재탐색", "AVAILABLE", input_id="action.search", kind="")}</div></div></section>')
    det = (f'<section class="panel" style="flex:1;min-width:0">{sec("허저 許褚", "인물 카드 · 내 부 · NPC")}'
           f'<div style="display:grid;grid-template-columns:168px minmax(0,1fr);gap:14px;padding:12px">{portrait("heojeo", "허저", 168, 238)}'
           f'<div style="display:flex;flex-direction:column;gap:10px;min-width:0">'
           f'<div style="display:flex;gap:6px;flex-wrap:wrap">{chip("유일", "bronze")}{chip("충성 높음", "moss")}{chip("코스트 [미정]")}</div>'
           f'{stat_grid()}{apt_grid()}'
           f'<div style="display:flex;flex-direction:column;gap:4px"><span class="t2" style="font-size:12px">결속</span>'
           f'<div style="display:flex;gap:6px;flex-wrap:wrap">{chip("향당 · 초현", "bronze")}{chip("주공과 같은 고향", "moss")}</div></div></div></div>'
           f'<div style="padding:0 12px 10px">{state8()}</div>'
           f'<div style="padding:0 12px 10px;display:flex;align-items:center;gap:8px"><span class="t2" style="font-size:12px">계책 기여</span>{chip("준비 중", "info")}'
           f'{note("이 인물이 부를 떠나면 기여한 계책 카드도 덱에서 빠집니다.")}</div>'
           f'<div style="margin-top:auto;padding:10px 12px;display:flex;gap:8px;border-top:1px solid #2c342f">'
           f'{input_btn("자리에 배치", "AVAILABLE", input_id="placement.assign", style="flex:1")}{input_btn("내보내기", "NOT_DELIVERED")}'
           f'{btn("인물 상세", "", href="#")}</div></section>')
    bonds = ''.join(row(f'{chip(a, "bronze" if b else "")}<span class="t2" style="font-size:12px;flex:1">{c}</span>{chip(d, t) if d else ""}', 48)
                    for a, b, c, d, t in [('향당 · 초현', True, '하후돈 · 허저', '발동', 'moss'), ('혈연', False, '서버 준비 중', '준비 중', 'info'),
                                          ('은의 · 결의 · 명망', False, '서버 준비 중', '준비 중', 'info')])
    right = (f'<div style="width:360px;flex-shrink:0;display:flex;flex-direction:column;gap:12px">'
             f'{panel("부대 카드", "지휘 인물 1명이 붙어야 움직인다", unit_cards() + row(note("실명 부대는 이름 뒤에 「유일」 칩이 붙습니다. 지금 거느린 실명 부대 없음."), 44))}'
             f'{panel("결속", "일곱 가지 중 서버가 준 것", bonds, "flex:1")}</div>')
    head = pagehead(BU, RET_TABS, '편성 · 결속', btn('도움말', '', 'help')) + renown_band()
    page31('V31K4Retinue.dc.html', 'K4 P-R01 편성 · 결속 · 명망 — 데스크톱', shell_desk('부', 'retinue', desk_main(head, lst + det + right)))


@board
def mretinue():
    inner = (f'<div style="display:flex;align-items:center;gap:8px;padding:8px 10px;background:#141816;border:1px solid #2c342f">'
             f'<span class="t2" style="font-size:12px">명망</span><span class="mono bz" style="font-weight:700">[미정]</span>'
             f'<div class="g-bar" style="flex:1"><i style="width:86%"></i></div>{chip("상한 초과", "rust")}</div>'
             f'{seg(["인물 3", "부대 2", "결속"], "인물 3", "보기", style="flex-shrink:0")}'
             f'<div role="listbox" aria-label="부의 인물" style="border-top:1px solid #2c342f">'
             + ''.join(person_list_row(*p, mobile=True) for p in PEOPLE_R) + '</div>'
             + note('인물을 누르면 인물 상세(자리에 배치 · 내보내기). 사람 장수는 조정에서 발령합니다.'))
    foot = f'{input_btn("인재탐색", "AVAILABLE", input_id="action.search", kind="", style="flex:1")}{input_btn("등용", "AVAILABLE", input_id="action.employ", style="flex:1")}'
    page31('V31K4MRetinue.dc.html', 'K4 P-R01 편성 — 모바일',
           shell_mob(mob_main(inner, RET_TABS, '편성 · 결속', foot=foot, first=BU), 'retinue', None, None), w=390, h=844)


@board
def retinue_empty():
    big = (f'<section class="panel" style="flex:1;min-width:0;display:flex">'
           + state_empty('아직 거느린 인물이 없습니다',
                         '인재탐색으로 재야 인물을 찾고 등용하면 여기에 인물 카드가 생깁니다. 인재탐색과 등용은 명령 목록 12순에 넣는 직접 행동입니다 — 한 순에 하나.',
                         input_btn('인재탐색 — 명령 목록에 넣기', 'AVAILABLE', input_id='action.search')
                         + input_btn('등용', 'BLOCKED', '찾은 인재가 없음', input_id='action.employ', kind=''))
           + '</section>')
    right = (f'<div style="width:360px;flex-shrink:0;display:flex;flex-direction:column;gap:12px">'
             f'<section class="panel" style="height:300px">{sec("부대 카드", "")}{state_empty("편성한 부대가 없습니다", "부곡은 징병 · 모병으로 모은 병력으로 편성합니다.", pad=10)}</section>'
             f'<section class="panel" style="flex:1">{sec("결속", "")}{state_empty("결속이 없습니다", "인물이 생기면 본관 · 혈연 · 천거로 결속이 붙습니다.", pad=10)}</section></div>')
    head = pagehead(BU, RET_TABS, '편성 · 결속', btn('도움말', '', 'help')) + renown_band(over=False, empty=True)
    page31('V31K4RetinueEmpty.dc.html', 'K4 P-R01 편성 — 인물 없는 월드(빈 상태)', shell_desk('부', 'retinue', desk_main(head, big + right)))


@board
def mretinue_empty():
    inner = (f'<div style="display:flex;align-items:center;gap:8px;padding:8px 10px;background:#141816;border:1px solid #2c342f">'
             f'<span class="t2" style="font-size:12px">명망</span><span class="mono bz" style="font-weight:700">[미정]</span>'
             f'<div class="g-bar" style="flex:1"><i style="width:0%"></i></div>{chip("인물 0", "")}</div>'
             f'<div style="flex:1;display:flex;border:1px dashed #3d4740">'
             + state_empty('아직 거느린 인물이 없습니다', '인재탐색으로 재야 인물을 찾고 등용하면 인물 카드가 생깁니다.', pad=10) + '</div>')
    foot = f'{input_btn("인재탐색", "AVAILABLE", input_id="action.search", style="flex:1")}{input_btn("등용", "BLOCKED", "찾은 인재 없음", input_id="action.employ", kind="")}'
    page31('V31K4MRetinueEmpty.dc.html', 'K4 P-R01 편성 — 모바일 빈 상태',
           shell_mob(mob_main(inner, RET_TABS, '편성 · 결속', foot=foot, first=BU), 'retinue', None, None), w=390, h=844)


# ------------------------------------------------------------------ P-R02 인물 일람
PEOPLE_T = [('jojo', '조조', '조조', '군주', '허현', False, True), ('hahoudon', '하후돈', '조조', '군단장', '양적현', True, False),
            ('sunuk', '순욱', '조조', '[관직]', '허현', True, False), ('heojeo', '허저', '조조', '군단장', '양적현', False, False),
            ('ijeon', '이전', '조조', '현령', '장사현', False, False), ('join', '조인', '조조', '[자리]', '여남군', False, False),
            ('', '악진', '조조', '[자리]', '[소재]', True, False), ('', '우금', '조조', '[자리]', '[소재]', True, False),
            ('', '만총', '조조', '[자리]', '[소재]', True, False), ('', '원소', '원소', '군주', '— 안 보임', False, True),
            ('', '유표', '유표', '군주', '— 안 보임', False, True), ('', '석도', '재야', '—', '양적현', False, False)]


def people_rows():
    rows = []
    for i, (k, n, aff, post, loc, human, lord) in enumerate(PEOPLE_T, 1):
        me = n == '하후돈'
        c = (chip('나', 'bronze') if me else '') + (chip('사람', 'info') if human else '') + (chip('군주', 'bronze') if lord else '')
        rows.append([f'<span class="mono muted">{i}</span>',
                     f'<span style="display:inline-flex;align-items:center;gap:6px">{portrait(k, n, 24, 34)}<span class="serif" style="font-weight:700">{n}</span>{c}</span>',
                     nat(aff) if aff != '재야' else '<span class="muted">재야</span>', post, loc] + ['—'] * 7)
    return rows


@board
def people():
    filt = (f'<div style="display:flex;gap:8px;align-items:center;padding:10px 12px;flex-wrap:nowrap">'
            f'{seg(["내 부", "소속 세력", "전체"], "전체", "범위")}{search("이름 · 초성 — 예: ㅅㅇ", style="width:240px")}'
            f'{btn("소속", "sm", "legend")}{btn("자리 · 결속 · 적성", "sm", "list")}{btn("정렬: 능력 합", "sm", "swap")}'
            f'<span class="muted mono" style="margin-left:auto;font-size:12px;white-space:nowrap">1,000명 중 50</span></div>')
    table = tbl(['#', '인물', '소속', '자리', '소재', '통', '무', '지', '정', '매', '합', '적성'], people_rows(), 'font-size:12px')
    table = table.replace('<tr><td><span class="mono muted">2</span>', '<tr class="me"><td><span class="mono muted">2</span>', 1)
    main_tbl = (f'<section class="panel" style="flex:1;min-width:0">{sec("인물 일람", "시야 · 권한 밖 값은 「?」 · 5능력은 공개")}{filt}'
                f'<div style="padding:0 12px;overflow:hidden">{table}</div>'
                f'<div style="padding:8px 12px;margin-top:auto;display:flex;gap:8px;align-items:center;border-top:1px solid #2c342f">'
                f'{btn("50명 더 보기", "", "arrow")}{note("적성 · 결속 열은 서버가 아직 주지 않습니다(K4-05). 「준비 중」 칸은 채워지는 대로 보입니다.")}</div></section>')
    prev = (f'<section class="panel" style="width:360px;flex-shrink:0">{sec("순욱", "소속 세력 · 사람 장수")}'
            f'<div style="display:flex;gap:12px;padding:12px">{portrait("sunuk", "순욱", 96, 136)}<div style="display:flex;flex-direction:column;gap:6px;min-width:0">'
            f'<span class="serif" style="font-size:18px;font-weight:900">순욱</span><div style="display:flex;gap:6px;flex-wrap:wrap">{chip("사람", "info")}{chip("조조 소속")}</div>'
            f'<span class="muted" style="font-size:12px">소재 허현 · 자리 [관직]</span></div></div>'
            f'<div style="padding:0 12px 10px">{stat_grid()}</div><div style="padding:0 12px 10px">{apt_grid()}</div>'
            f'<div style="padding:0 12px 10px;display:flex;gap:6px;flex-wrap:wrap">{chip("결속 — 준비 중", "info")}</div>'
            f'<div style="margin-top:auto;padding:10px 12px;display:flex;flex-direction:column;gap:8px;border-top:1px solid #2c342f">'
            f'{btn("서신 쓰기", "", "mail", style="width:100%")}{btn("인물 상세 열기", "primary", style="width:100%", href="#")}</div></section>')
    head = pagehead(BU, RET_TABS, '인물 일람', btn('도움말', '', 'help'))
    page31('V31K4People.dc.html', 'K4 P-R02 인물 일람 — 데스크톱', shell_desk('부', 'retinue', desk_main(head, main_tbl + prev)))


@board
def mpeople():
    def card(k, n, aff, post, loc, human, lord):
        c = (chip('사람', 'info') if human else '') + (chip('군주', 'bronze') if lord else '') + (chip('나', 'bronze') if n == '하후돈' else '')
        return (f'<a href="#" style="border:1px solid #3d4740;background:#141816;padding:8px 10px;display:flex;gap:10px;color:#ece6d8;min-height:80px">{portrait(k, n, 40, 56)}'
                f'<div style="display:flex;flex-direction:column;gap:3px;min-width:0;flex:1"><span style="display:flex;align-items:center;gap:6px">'
                f'<span class="serif" style="font-size:15px;font-weight:700">{n}</span>{c}</span>'
                f'<span class="muted" style="font-size:11.5px">{aff} · {post} · {loc}</span>'
                f'<span class="mono t2" style="font-size:11.5px">통 — · 무 — · 지 — · 정 — · 매 —</span></div>'
                f'<span style="align-self:center">{icon("next", 18, "#8a8477")}</span></a>')
    inner = (f'{search("이름 · 초성")}<div style="display:flex;gap:6px">{seg(["내 부", "소속", "전체"], "전체", "범위", style="flex:1")}'
             f'{btn("거르기 · 정렬", "sm", "list")}</div><span class="muted mono" style="font-size:11px">1,000명 중 50 · 정렬 능력 합</span>'
             + ''.join(card(*p) for p in PEOPLE_T[:5]) + btn('50명 더 보기', '', 'arrow', style='width:100%'))
    page31('V31K4MPeople.dc.html', 'K4 P-R02 인물 일람 — 모바일',
           shell_mob(mob_main(inner, RET_TABS, '인물 일람'), 'retinue', '인물 일람', BU), w=390, h=844)


# ------------------------------------------------------------------ P-R03 인물 상세
@board
def person():
    hero = (f'<section class="panel" style="width:360px;flex-shrink:0">'
            f'<div style="padding:12px;display:flex;justify-content:center">{portrait("sunuk", "순욱", 240, 340)}</div>'
            f'<div style="padding:0 16px 10px;display:flex;flex-direction:column;gap:8px"><span class="serif" style="font-size:24px;font-weight:900">순욱 <span class="muted" style="font-size:14px;font-weight:700">荀彧</span></span>'
            f'<div style="display:flex;gap:6px;flex-wrap:wrap">{chip("사람", "info")}{chip("조조 소속")}{chip("유일", "bronze")}{chip("나이 [값]")}</div>'
            f'{note("같은 세력의 사람 장수 — 충성 · 코스트 · 녹봉은 내 부 인물만 보입니다.")}</div>'
            f'<div style="margin-top:auto;padding:10px 12px;display:flex;flex-direction:column;gap:8px;border-top:1px solid #2c342f">'
            f'{btn("서신 쓰기", "primary", "mail", style="width:100%")}{btn("이 인물의 기록", "", "records", style="width:100%", href="#")}'
            f'{input_btn("발령", "BLOCKED", "주공만 · 내 부 사람 장수만", input_id="court.dispatch", kind="")}</div></section>')
    grid = (f'<div style="flex:1;min-width:0;display:grid;grid-template-columns:repeat(2,minmax(0,1fr));grid-template-rows:auto auto minmax(0,1fr);gap:12px">'
            f'{panel("능력", "숫자 그대로", f"<div style=padding:10px>{stat_grid()}</div>")}'
            f'{panel("역할 적성", "장 · 리 · 사 · 사자", f"<div style=padding:10px>{apt_grid()}</div>")}'
            f'{panel("결속", "본관 · 혈연 · 은의 · 결의 · 명망", row(chip("향당 · 영음현", "bronze") + note("본관 — 영천군 영음현"), 48) + row(chip("그 밖의 결속 — 준비 중", "info"), 48))}'
            f'{panel("자리 · 상태", "", f"<div style=padding:10px>{state8(hidden=("충성", "녹봉", "보물 칸", "경험"))}</div>")}'
            f'<section class="panel" style="display:flex">{sec("계책 기여", "")}</section>'
            f'<section class="panel">{sec("인물 관직 카드", "조정 · 지방 관직 · 작위 · 추천 이력")}</section></div>')
    # 마지막 두 칸은 서버 대기(K4-13 · K8)
    grid = grid.replace(f'<section class="panel" style="display:flex">{sec("계책 기여", "")}</section>',
                        f'<section class="panel" style="display:flex;flex-direction:column">{sec("계책 기여", "")}{state_waiting("계책 기여 — 준비 중", "이 인물이 덱에 넣는 계책 카드는 서버가 아직 주지 않습니다(K4-13).", pad=10)}</section>')
    grid = grid.replace(f'<section class="panel">{sec("인물 관직 카드", "조정 · 지방 관직 · 작위 · 추천 이력")}</section>',
                        f'<section class="panel" style="display:flex;flex-direction:column">{sec("인물 관직 카드", "조정 · 지방 관직 · 작위 · 추천 이력")}'
                        f'{state_waiting("관직 카드 — 준비 중", "관직 체계(2층)가 들어오면 법적 관할 · 실효 관할이 여기에 보입니다.", pad=10)}</section>')
    head = pagehead(BU, RET_TABS, '인물 일람', btn('← 인물 일람', '', href='#'))
    page31('V31K4Person.dc.html', 'K4 P-R03 인물 상세 — 데스크톱(같은 세력 사람 장수)', shell_desk('부', 'retinue', desk_main(head, hero + grid)))


@board
def mperson():
    hero = (f'<div style="height:200px;flex-shrink:0;border:1px solid #9c7f3f">{portrait("heojeo", "허저", 364, 198)}</div>')
    inner = (f'{hero}<div style="display:flex;align-items:baseline;gap:8px"><span class="serif" style="font-size:22px;font-weight:900">허저</span><span class="muted">許褚</span></div>'
             f'<div style="display:flex;gap:6px;flex-wrap:wrap">{chip("내 부")}{chip("유일", "bronze")}{chip("충성 높음", "moss")}{chip("코스트 [미정]")}</div>'
             f'{stat_grid()}{apt_grid()}<div style="display:flex;gap:6px">{chip("향당 · 초현", "bronze")}{chip("계책 기여 — 준비 중", "info")}</div>')
    foot = f'{input_btn("자리에 배치", "AVAILABLE", input_id="placement.assign", style="flex:1")}{input_btn("내보내기", "NOT_DELIVERED")}'
    page31('V31K4MPerson.dc.html', 'K4 P-R03 인물 상세 — 모바일(내 부 NPC)',
           shell_mob(mob_main(inner, foot=foot), 'retinue', '허저', '인물 일람'), w=390, h=844)


# ------------------------------------------------------------------ P-R04 월단평
REASONS_UP = ['전공', '치적', '관직', '결속']
REASONS_DN = ['패전', '배신', '실정', '발령 거절']


@board
def yuedan():
    rank = [['1', '조조', nat('조조'), '[값]', chip('치적 [값]', 'moss')], ['2', '원소', nat('원소'), '[값]', chip('전공 [값]', 'moss')],
            ['3', '유표', nat('유표'), '[값]', '—'], ['…', '', '', '', ''],
            [f'<span class="mono">[순위]</span>', '<span class="serif" style="font-weight:700">하후돈</span> ' + chip('나', 'bronze'), nat('조조'), '[값]', chip('전공 [값]', 'moss')],
            ['[순위]', '만총', nat('조조'), '[값]', chip('발령 거절 [값]', 'rust')]]
    left = (f'<section class="panel" style="flex:1;min-width:0">{sec("200년 3월 월단평", "매월 상순 발표 · 순위는 공개")}'
            f'<div style="padding:0 12px">{tbl(["순위", "장수", "세력", "명망", "이번 달 사유"], rank, "font-size:12.5px")}</div>'
            f'<div style="padding:8px 12px">{btn("내 순위로", "sm", "war")}</div>'
            f'<div style="margin-top:auto;padding:12px;display:grid;grid-template-columns:1fr 1fr;gap:12px;border-top:1px solid #2c342f">'
            f'<div style="display:flex;flex-direction:column;gap:6px"><span class="ms" style="font-size:12px;font-weight:700">오르는 경로</span>'
            f'<div style="display:flex;gap:6px;flex-wrap:wrap">{"".join(chip(r, "moss") for r in REASONS_UP)}</div></div>'
            f'<div style="display:flex;flex-direction:column;gap:6px"><span class="rs" style="font-size:12px;font-weight:700">떨어지는 경로</span>'
            f'<div style="display:flex;gap:6px;flex-wrap:wrap">{"".join(chip(r, "rust") for r in REASONS_DN)}</div></div></div></section>')
    dep = ''.join(row(f'<span class="mono muted">{i}</span>{portrait(k, n, 28, 40)}{name_block(n, b)}{chip(l, t)}', 56)
                  for i, (k, n, b, l, t) in enumerate([('', '무명 공조', '결속 없음 · 공용', '충성 낮음', 'rust'), ('ijeon', '이전', '향당', '보통', ''),
                                                        ('heojeo', '허저', '향당', '높음', 'moss')], 1))
    right = (f'<div style="width:440px;flex-shrink:0;display:flex;flex-direction:column;gap:12px">'
             f'<section class="panel">{sec("내 명망", "매월 상순 갱신")}<div style="padding:14px 16px;display:flex;flex-direction:column;gap:10px">'
             f'<div style="display:flex;align-items:baseline;gap:10px"><span class="mono bz" style="font-size:34px;font-weight:700">[미정]</span>{chip("코스트 초과 — 이탈 판정 대상", "rust")}</div>'
             f'<div style="display:flex;justify-content:space-between;font-size:12px"><span class="t2">부 코스트 합 / 코스트 상한</span><span class="mono">[값] / [미정]</span></div>'
             f'<div class="g-bar"><i style="width:86%"></i></div>{note("명망이 곧 거느릴 수 있는 부 코스트의 상한입니다.")}'
             f'<span class="t2" style="font-size:12px">다음 월단평에 반영될 일</span><div style="display:flex;gap:6px;flex-wrap:wrap">{chip("치적 · 장사현 [값]", "moss")}{chip("발령 거절 없음")}</div></div></section>'
             f'<section class="panel" style="flex:1">{sec("이탈 판정 순서", "상한을 넘으면 충성이 낮은 인물부터")}{dep}'
             f'<div style="padding:8px 12px">{link("부 편성으로 →")}</div></section></div>')
    head = pagehead(BU, RET_TABS, '월단평', btn('도움말', '', 'help'))
    page31('V31K4Yuedan.dc.html', 'K4 P-R04 월단평 — 데스크톱', shell_desk('부', 'retinue', desk_main(head, left + right)))


@board
def myuedan():
    me = (f'<div style="border:1px solid #9c7f3f;background:#141816;padding:10px 12px;display:flex;flex-direction:column;gap:8px">'
          f'<div style="display:flex;align-items:baseline;gap:8px"><span class="t2" style="font-size:12px">내 명망</span><span class="mono bz" style="font-size:28px;font-weight:700">[미정]</span>{chip("코스트 초과", "rust")}</div>'
          f'<div class="g-bar"><i style="width:86%"></i></div><span class="mono t2" style="font-size:11.5px">부 코스트 [값] / 상한 [미정]</span>'
          f'<div style="display:flex;gap:6px;flex-wrap:wrap">{chip("다음 반영 · 치적 [값]", "moss")}</div></div>')
    cards = ''.join(f'<div style="border:1px solid #3d4740;background:#141816;padding:8px 10px;display:flex;align-items:center;gap:10px;min-height:56px">'
                    f'<span class="mono muted" style="width:44px">{r}</span>{name_block(n, "")}{nat(a)}<span class="mono">{v}</span></div>'
                    for r, n, a, v in [('1', '조조', '조조', '[값]'), ('2', '원소', '원소', '[값]'), ('[순위]', '하후돈 · 나', '조조', '[값]')])
    inner = f'{me}{seg(["순위", "이탈 순서"], "순위", "보기")}{cards}{note("매월 상순 발표 · 순위는 공개, 사유는 본인만")}'
    page31('V31K4MYuedan.dc.html', 'K4 P-R04 월단평 — 모바일', shell_mob(mob_main(inner, RET_TABS, '월단평'), 'retinue', '월단평', BU), w=390, h=844)


# ------------------------------------------------------------------ P-R05 포로 · 등용
def talent_card(n, sub, state, reason='', sel=False):
    end = ok_chip('등용 가능') if state == 'ok' else why_tag(reason)
    return (f'<button type="button" role="option" class="opt" aria-selected="{"true" if sel else "false"}"{" aria-disabled=\"true\" aria-haspopup=\"dialog\"" if state != "ok" else ""} style="min-height:72px">'
            f'{portrait("", n, 40, 56)}<span style="display:flex;flex-direction:column;gap:3px;min-width:0;flex:1"><span class="nm">{n}</span><span class="sub">{sub}</span>'
            f'<span class="mono t2" style="font-size:11px">통 — · 무 — · 지 — · 정 — · 매 — · 확률 [값]</span></span><span class="end">{end}</span></button>')


@board
def captives():
    left = (f'<section class="panel" style="flex:1;min-width:0">{sec("등용할 수 있는 인재", "이 현에서 찾은 재야 인물 · 같은 칸만")}'
            f'<div style="padding:8px 12px;display:flex;align-items:center;gap:10px">{chip("찾지 못한 인물 [값]명")}'
            f'{input_btn("인재탐색 — 명령 목록에 넣기", "AVAILABLE", input_id="action.search", kind="")}</div>'
            f'<div style="padding:0 12px 8px">{help_strip("등용은 한 순에 한 사람. 성공하면 내 부 인물 카드가 되고 명망 코스트가 오릅니다.")}</div>'
            f'<div role="listbox" aria-label="인재" style="border-top:1px solid #2c342f">{talent_card("석도", "재야 · 양적현 · 결속 없음", "ok", sel=True)}'
            f'{talent_card("[재야 인물]", "재야 · 양적현", "no", "이미 소속이 있음")}</div>'
            f'<div style="padding:10px 12px;margin-top:auto;display:flex;gap:8px;border-top:1px solid #2c342f">'
            f'{input_btn("석도 등용 — 명령 목록에 넣기", "AVAILABLE", input_id="action.employ", style="flex:1")}</div></section>')
    right = (f'<section class="panel" style="flex:1;min-width:0">{sec("잡은 포로", "전투에서 이기면 성 안 인물이 잡힐 수 있다")}'
             f'{state_waiting("포로 목록 — 준비 중", "포로를 읽는 서버 기능이 아직 없습니다(K4-12). 포로가 생기면 이 자리에 옛 주인 · 결속 · 설득 확률이 보입니다.")}'
             f'<div style="padding:10px 12px;display:flex;flex-direction:column;gap:8px;border-top:1px solid #2c342f">'
             f'<span class="t2" style="font-size:12px">처분 — 셋 중 하나</span><div style="display:flex;gap:8px;flex-wrap:wrap">'
             f'{input_btn("설득", "NOT_DELIVERED", input_id="action.persuadeCaptive")}{input_btn("석방", "NOT_DELIVERED")}{input_btn("억류", "NOT_DELIVERED")}</div>'
             f'{note("설득은 포로와 같은 자리에서 쓰는 직접 행동입니다. 석방 · 억류는 순을 쓰지 않습니다.")}</div></section>')
    head = pagehead(BU, RET_TABS, '포로 · 등용', btn('도움말', '', 'help')) + renown_band(over=False)
    page31('V31K4Captives.dc.html', 'K4 P-R05 포로 · 등용 — 데스크톱', shell_desk('부', 'retinue', desk_main(head, left + right)))


@board
def mcaptives():
    inner = (f'{seg(["인재 2", "포로"], "인재 2", "보기")}<div style="display:flex;align-items:center;gap:8px">{chip("찾지 못한 인물 [값]명")}</div>'
             f'<div role="listbox" aria-label="인재" style="border-top:1px solid #2c342f">{talent_card("석도", "재야 · 양적현", "ok", sel=True)}'
             f'{talent_card("[재야 인물]", "재야 · 양적현", "no", "이미 소속이 있음")}</div>')
    sh = sheet('석도 — 등용', f'<div style="padding:4px 16px 8px;display:flex;flex-direction:column;gap:8px">{help_strip("성공하면 내 부 인물 카드가 됩니다.")}'
                            f'<div style="display:flex;gap:8px">{kv("확률", "[값]")}{kv("코스트", "+[미정]")}{kv("순", "04순 빈 순")}</div></div>',
               height=270, bottom=64, foot=input_btn('등용 — 명령 흐름에서 순 고르기', 'AVAILABLE', input_id='action.employ', style='flex:1'))
    main = mob_main(inner, RET_TABS, '포로 · 등용').replace('</main>', f'<div class="dim"></div>{sh.replace("bottom:64px", "bottom:0px")}</main>')
    page31('V31K4MCaptives.dc.html', 'K4 P-R05 포로 · 등용 — 모바일', shell_mob(main, 'retinue', '포로 · 등용', BU), w=390, h=844)


# ================================================================== 영지 — P-T01~T04
def res_band():
    return (f'<div style="min-height:56px;flex-shrink:0;display:flex;align-items:center;gap:14px;padding:0 16px;border-bottom:1px solid #2c342f;background:#141816">'
            f'<span class="t2" style="font-size:12px">쓸 수 있는 창고망 합</span>{resline()}<span style="margin-left:auto">{link("창고망 · 보급 →")}</span></div>')


POLICIES = ['권농', '중상', '중세', '휼민', '둔전', '징발']
WORKS9 = ['수리', '둔전', '성방', '도로', '역참', '창고', '망루봉화', '병영', '시장수운']


def place_row(k, n, post, where, st, tone, act):
    return row(f'{portrait(k, n, 32, 44)}{name_block(n, f"{post} · {where}", chip(st, tone))}{act}', 64)


@board
def territory():
    form = (f'<div style="padding:10px 12px;display:flex;flex-direction:column;gap:8px;border-top:1px solid #2c342f;background:#141816">'
            f'<span class="serif" style="font-weight:900">무명 공조 — 자리에 배치</span>{help_strip("배치는 NPC 인물을 자리에 앉힙니다. 카드는 자기 턴마다 걸어서 부임합니다.")}'
            f'{seg(["현령", "군단장", "사자", "정찰", "자리에서 풀기"], "현령", "자리 종류")}'
            f'{target_field("어느 현", "양성현", "영천군 · 현령 빈자리", picking=False)}'
            f'{input_btn("여기에 배치 — 다음 턴부터", "AVAILABLE", input_id="placement.assign", style="width:100%")}</div>')
    place = (f'<section class="panel" style="width:400px;flex-shrink:0">{sec("배치", "NPC 인물을 자리에 앉힌다")}'
             + place_row('heojeo', '허저', '군단장', '영천 군단', '부임 완료', 'moss', btn('바꾸기', 'sm'))
             + place_row('ijeon', '이전', '현령', '장사현', '대기 — 다음 턴부터', 'info', btn('바꾸기', 'sm'))
             + place_row('', '무명 공조', '미배치', '—', '자리 없음', 'rust', btn('배치', 'primary sm'))
             + row(note('사람 장수를 자리에 앉히는 것은 배치가 아니라 발령입니다.') + link('조정 →', 'margin-left:auto'), 48)
             + f'<div style="margin-top:auto">{form}</div></section>')
    prow = [['장사현', '이전', '권농 ' + chip('현'), chip('[미정]'), btn('바꾸기', 'sm')],
            ['양적현', '<span class="muted">빈자리</span>', '[기본 방침] ' + chip('기본'), '—', why('현령 · 군주만')],
            ['영음현', '<span class="muted">빈자리</span>', '[기본 방침] ' + chip('기본'), '—', why('현령 · 군주만')]]
    confirm = (f'<div style="margin:10px 12px;padding:10px 12px;border:1px solid #9c7f3f;background:#141816;display:flex;flex-direction:column;gap:8px">'
               f'<span class="serif" style="font-weight:900">장사현 방침 바꾸기</span>{help_strip("방침은 현령 카드의 다음 턴부터, 효과는 순 경계마다 적용됩니다.")}'
               f'{seg(POLICIES, "휼민", "방침")}<div style="display:flex;gap:6px;flex-wrap:wrap">{chip("민심 ▲", "moss")}{chip("쌀 소모 ▲", "rust")}{chip("세수 ▼", "rust")}</div>'
               f'<div style="display:flex;gap:8px">{input_btn("권농 → 휼민으로 — 다음 턴부터", "AVAILABLE", input_id="policy.set", style="flex:1")}{btn("그만")}</div></div>')
    pol = (f'<section class="panel" style="flex:1;min-width:0">{sec("방침", "자리 · 군단에 걸어 두는 지속 규칙")}'
           f'<div style="padding:8px 12px">{seg([("현", 3), ("군", 1), ("군단", 1)], "현", "방침 대상")}</div>'
           f'<div style="padding:0 12px">{tbl(["현", "현령", "지금 방침", "지난 적용", ""], prow, "font-size:12px")}</div>{confirm}</section>')

    def wcard(n, st='ok', reason=''):
        body = (f'<span class="serif" style="font-weight:700">{n}</span><span style="display:flex;gap:4px">{chip("[미정]")}{chip("[미정]순")}</span>')
        if st == 'ok':
            return f'<button type="button" class="opt" data-input-id="work.start" style="flex-direction:column;align-items:flex-start;gap:4px;min-height:72px;border:1px solid #3d4740;padding:8px">{body}</button>'
        return (f'<button type="button" class="opt" aria-disabled="true" aria-haspopup="dialog" data-input-id="work.start" style="flex-direction:column;align-items:flex-start;gap:4px;'
                f'min-height:72px;border:1px dashed #5a625c;padding:8px">{body}{why_tag(reason)}</button>')
    grid = ('<div style="display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:6px;padding:0 12px">'
            + ''.join(wcard(n, 'no' if n == '성방' else 'ok', '이미 지었음') for n in WORKS9) + '</div>')
    work = (f'<section class="panel" style="width:400px;flex-shrink:0">{sec("공사", "순 경계마다 진척")}'
            f'<div style="padding:8px 12px;display:flex;flex-direction:column;gap:6px;border-bottom:1px solid #2c342f">'
            f'<span><span class="serif" style="font-weight:700">장사현 · 둔전</span> <span class="muted" style="font-size:12px">[값]% · [n]순 남음</span></span>'
            f'<div class="g-bar"><i style="width:55%"></i></div><span class="rs" style="font-size:11.5px">자재 부족 — 멈춤. 목재가 이어진 창고에 닿으면 저절로 이어 갑니다.</span></div>'
            f'<div style="padding:8px 12px;display:flex;align-items:center;gap:8px;border-bottom:1px solid #2c342f">'
            f'<span class="serif" style="font-weight:700">양적현</span>{chip("성방 완공", "moss")}<span style="margin-left:auto">{input_btn("성방 낮추기", "NOT_DELIVERED", input_id="work.reduce")}</span></div>'
            f'<div style="padding:8px 12px 6px;display:flex;align-items:center;gap:8px"><span class="serif" style="font-weight:900">새 공사 — 양적현</span>{note("비용 · 기간은 서버 값")}</div>'
            f'{grid}<div style="padding:8px 12px">{help_strip("도로 · 보루는 지도에서 접경 · 길목을 고릅니다.")}</div>'
            f'<div style="padding:0 12px 10px;display:flex;align-items:center;gap:8px">{chip("시설 분기 — 준비 중(3층)", "info")}</div></section>')
    head = pagehead('영지', TER_TABS, '배치 · 방침 · 공사', btn('도움말', '', 'help')) + res_band()
    page31('V31K4Territory.dc.html', 'K4 P-T01 배치 · 방침 · 공사 — 데스크톱', shell_desk('영지', 'territory', desk_main(head, place + pol + work)))


@board
def mterritory():
    inner = (f'{seg(["배치 3", "방침 3", "공사 2"], "공사 2", "보기")}'
             f'<div style="border:1px solid #3d4740;background:#141816;padding:10px;display:flex;flex-direction:column;gap:6px">'
             f'<span><span class="serif" style="font-weight:700">장사현 · 둔전</span> <span class="muted" style="font-size:12px">[값]% · [n]순 남음</span></span>'
             f'<div class="g-bar"><i style="width:55%"></i></div><span class="rs" style="font-size:11.5px">자재 부족 — 멈춤</span>{link("창고망에서 보기 →")}</div>'
             f'<div style="border:1px solid #3d4740;background:#141816;padding:10px;display:flex;flex-direction:column;gap:8px">'
             f'<div style="display:flex;align-items:center;gap:8px"><span class="serif" style="font-weight:700">양적현</span>{chip("성방 완공", "moss")}</div>'
             f'{input_btn("성방 낮추기", "NOT_DELIVERED", input_id="work.reduce")}</div>'
             f'<div style="border:1px dashed #3d4740;padding:10px;display:flex;flex-direction:column;gap:6px"><span class="serif" style="font-weight:900">새 공사 — 양적현</span>'
             f'<div style="display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:6px">'
             + ''.join(f'<button type="button" class="btn sm" data-input-id="work.start"{" aria-disabled=\"true\" style=\"border-style:dashed;color:#8a8477\"" if n == "성방" else ""}>{n}</button>' for n in WORKS9)
             + f'</div>{note("성방 — 이미 지었음. 누르면 사유.")}{chip("시설 분기 — 준비 중", "info")}</div>')
    page31('V31K4MTerritory.dc.html', 'K4 P-T01 배치 · 방침 · 공사 — 모바일',
           shell_mob(mob_main(inner, TER_TABS, '배치 · 방침 · 공사'), 'territory', '영지', '전체 메뉴'), w=390, h=844)


# ------------------------------------------------------------------ P-T02 현 상세
def county_head(name, han, chips_, right=''):
    return (f'<div style="min-height:72px;flex-shrink:0;display:flex;align-items:center;gap:12px;padding:0 16px;border-bottom:1px solid #2c342f;background:#141816">'
            f'<span class="serif" style="font-size:24px;font-weight:900">{name}</span><span class="muted serif" style="font-size:14px">{han}</span>'
            f'<div style="display:flex;gap:6px;flex-wrap:wrap">{chips_}</div><div style="margin-left:auto;display:flex;gap:8px">{right}'
            f'{btn("다른 현 보기", "", "search")}{btn("지도에서 보기", "", "war", href="#")}</div></div>')


def season_band(text='계절 사건 — 준비 중. 이 현에 계절 사건이 나면 여기에 경고와 대응(휼민 방침 · 구휼 계책)이 보입니다.'):
    return (f'<div class="band notice" role="status">{icon("season", 18, "#7aa7c7")}<span>{text}</span>{chip("준비 중", "info")}</div>')


def vista(key, w, h, iw=832, ih=640, left=-196, top=-230, alt='현 전경'):
    return f'<div style="position:relative;width:{w}px;height:{h}px;overflow:hidden;border:1px solid #3d4740;flex-shrink:0">{mapimg(key, iw, ih, alt, left, top)}</div>'


def county_body(intel=False):
    key = 'castle_chenliu' if intel else 'county32_yangcheng'
    iw, ih = (768, 768) if intel else (832, 640)
    ind = indicators(warn=() if intel else ('민심',), dim=intel)
    hint = chip('3순 전 첩보 — 지금 값과 다를 수 있음', 'info') if intel else ''
    stock = (f'<div style="padding:8px 12px;display:flex;flex-direction:column;gap:6px;border-top:1px solid #2c342f"><span class="t2" style="font-size:12px">이 현 창고</span>'
             + (f'<span class="muted" style="font-size:12px;padding:6px 8px;background:repeating-linear-gradient(135deg,#141816 0 6px,#1b201d 6px 12px)">안 보임 — 우리 현이 아닙니다</span>' if intel else resline())
             + '</div>')
    left = (f'<section class="panel" style="width:440px;flex-shrink:0">{sec("형편", "호구 · 전답 · 시장 · 치안 · 민심 · 방비 · 성벽")}'
            f'<div style="padding:10px 12px">{vista(key, 414, 170, iw, ih, -180, -220, "현 전경 — 32px 보기")}</div>'
            f'<div style="padding:0 12px 8px;display:flex;flex-direction:column;gap:6px">{hint}{ind}</div>'
            f'<div style="padding:8px 12px;display:flex;align-items:center;gap:8px;border-top:1px solid #2c342f"><span class="t2" style="font-size:12px">특산</span>'
            f'{chip("[자원] [값]/월")}{note("설계값 [값] — 이번 달 0이면 이유가 한 줄로")}</div>{stock}</section>')
    reason_other = '우리 현이 아닙니다'
    if intel:
        gov = (f'{row(portrait("", "?", 32, 44) + name_block("현령 — 안 보임", "원소 소속 현"), 60)}'
               f'<div style="padding:8px 12px;display:flex;gap:8px;flex-wrap:wrap">{input_btn("현령 앉히기", "BLOCKED", reason_other, input_id="placement.assign", kind="")}'
               f'{input_btn("방침 바꾸기", "BLOCKED", reason_other, input_id="policy.set", kind="")}{input_btn("공사 시작", "BLOCKED", reason_other, input_id="work.start", kind="")}</div>')
    else:
        gov = (f'{row(portrait("", "빈", 32, 44) + name_block("현령 — 빈자리", "기본 방침으로 스스로 돌아갑니다 · 현령 능력 보정 없음", chip("빈자리", "rust")), 64)}'
               f'<div style="padding:8px 12px;display:flex;flex-direction:column;gap:8px">{help_strip("현령은 배치(내 NPC 인물) 또는 발령(사람 장수)으로 앉힙니다.")}'
               f'<div style="display:flex;gap:8px">{input_btn("현령 앉히기 — 배치", "AVAILABLE", input_id="placement.assign", style="flex:1")}{btn("발령은 조정 →", "", href="#")}</div></div>'
               f'<ul class="ul" style="padding:0 12px"><li><b>정치</b> — 세수 · 개간</li><li><b>매력</b> — 민심 · 유민</li><li><b>통솔</b> — 치안 · 둔전병</li><li><b>지력</b> — 공사 속도</li>'
               f'<li><b>향당</b> — 본관이 이 현인 인물이면 보너스</li></ul>'
               f'<div style="padding:8px 12px;display:flex;align-items:center;gap:8px;border-top:1px solid #2c342f"><span class="t2" style="font-size:12px">방침</span>'
               f'<span class="serif" style="font-weight:700">[기본 방침]</span>{chip("기본")}<span style="margin-left:auto">{input_btn("바꾸기", "BLOCKED", "현령 · 군주만", input_id="policy.set", kind="")}</span></div>'
               f'<div style="padding:8px 12px;display:flex;flex-direction:column;gap:6px;border-top:1px solid #2c342f">'
               f'<span><span class="serif" style="font-weight:700">성방</span> <span class="muted" style="font-size:12px">[값]% · [n]순 남음</span></span>'
               f'<div class="g-bar"><i style="width:35%;background:linear-gradient(90deg,#9c4a3f,#e08a7c)"></i></div>'
               f'<span class="rs" style="font-size:11.5px">자재 부족 — 멈춤</span></div>')
    mid = f'<section class="panel" style="flex:1;min-width:0">{sec("다스림", "현령 · 방침 · 공사")}{gov}</section>'
    here = ('<span class="muted" style="font-size:12px;padding:6px 12px">안 보임</span>' if intel else
            row(f'{portrait("", "원", 28, 40)}{name_block("원소 군단", "적 군단 · 지금 보임", chip("적", "rust"))}', 56))
    right = (f'<div style="width:360px;flex-shrink:0;display:flex;flex-direction:column;gap:12px">'
             f'{panel("이 현에 있는 사람 · 군단", "", here)}'
             f'<section class="panel" style="flex:1;display:flex;flex-direction:column">{sec("수비군", "병력 · 훈련 · 사기")}{state_waiting("수비군 — 준비 중", "수비군 값을 주는 읽기가 아직 없습니다(K4-04).", pad=8)}</section>'
             f'<section class="panel" style="flex:1;display:flex;flex-direction:column">{sec("최근 사건", "이 현")}{state_waiting("현 사건 — 준비 중", "기록 피드의 현 거르기(K5-07)가 오면 보입니다.", pad=8)}'
             f'<div style="padding:0 12px;border-top:1px solid #2c342f">{link("이 현 기록 모두 보기 →")}</div></section>'
             + (f'<section class="panel">{sec("다시 보기", "")}<div style="padding:10px 12px">{input_btn("다시 첩보 — 명령 목록에 넣기", "AVAILABLE", input_id="action.scout", style="width:100%")}</div></section>' if intel else
                f'<section class="panel">{sec("여기서 할 일", "")}<div style="padding:8px 12px;display:flex;flex-direction:column;gap:8px">'
                f'{note("하후돈은 지금 이 현에 없습니다. 내정 · 징병 같은 직접 행동은 이 현에 서 있을 때만 됩니다.")}{btn("여기로 명령", "primary", style="width:100%", href="#")}</div></section>')
             + '</div>')
    return left + mid + right


@board
def county():
    chips_ = chip('영천군') + chip('[등급]') + nat('조조') + chip('적 군단 주둔', 'rust')
    head = (pagehead('영지', TER_TABS, '현 상세', btn('도움말', '', 'help')) + county_head('양성현', '陽城', chips_) + season_band())
    page31('V31K4County.dc.html', 'K4 P-T02 현 상세 — 데스크톱', shell_desk('영지', 'territory', desk_main(head, county_body())))


@board
def county_intel():
    chips_ = chip('진류군') + chip('[등급]') + nat('원소') + chip('첩보 3순 전', 'info')
    head = pagehead('영지', TER_TABS, '현 상세', btn('도움말', '', 'help')) + county_head('진류현', '陳留', chips_)
    page31('V31K4CountyIntel.dc.html', 'K4 P-T02 현 상세 — 다른 세력 현(첩보 3순 전)', shell_desk('영지', 'territory', desk_main(head, county_body(intel=True))))


@board
def mcounty():
    inner = (f'<div style="display:flex;gap:6px;flex-wrap:wrap">{chip("영천군")}{chip("[등급]")}{nat("조조")}{chip("적 군단 주둔", "rust")}</div>'
             f'{vista("county32_yangcheng", 366, 130, 832, 640, -230, -250, "양성현 전경")}'
             f'<div style="display:flex;gap:4px;overflow:hidden;flex-shrink:0">'
             + ''.join(f'<button type="button" class="btn sm" aria-pressed="{"true" if t == "형편" else "false"}" style="flex-shrink:0;{"background:#d3b064;color:#161410;border-color:#9c7f3f" if t == "형편" else ""}">{t}</button>'
                       for t in ['형편', '다스림', '공사', '사람', '사건']) + '</div>'
             f'{indicators(h=24)}<div style="display:flex;gap:8px;align-items:center">{chip("특산 [자원] [값]/월")}{chip("계절 — 준비 중", "info")}</div>{resline()}')
    foot = btn('여기로 명령', 'primary', style='flex:1', href='#')
    page31('V31K4MCounty.dc.html', 'K4 P-T02 현 상세 — 모바일', shell_mob(mob_main(inner, foot=foot), 'territory', '양성현', '영지'), w=390, h=844)


# ------------------------------------------------------------------ P-T03 군 내정 현황
COUNTIES17 = [('양적현', '—', '[기본]', '', '시장수운', ['군 치소']), ('장사현', '이전', '권농', 'ijeon', '둔전', []),
              ('허현', '조정 직할', '—', '', '—', ['수도']), ('양성현 陽城', '빈자리', '[기본]', '', '성방', ['적 군단', '자재 부족']),
              ('윤씨현', '[인물]', '휼민', '', '—', ['고립']), ('영음현', '빈자리', '[기본]', '', '—', ['현령 없음']),
              ('영양현', '빈자리', '[기본]', '', '—', ['현령 없음']), ('임영현', '[무명]', '권농', '', '창고', []),
              ('양성현 襄城', '[무명]', '권농', '', '—', []), ('겹현', '[무명]', '권농', '', '—', ['적 군단']),
              ('부성현', '빈자리', '[기본]', '', '—', ['현령 없음', '적 군단']), ('곤양현', '[무명]', '중상', '', '역참', []),
              ('정릉현', '[무명]', '권농', '', '—', []), ('무양현', '[무명]', '징발', '', '—', ['민심 위험']), ('언현', '[무명]', '권농', '', '—', [])]


def same_name(n):
    """같은 읽기 지명(양성현 陽城 / 襄城)이 한 화면에 함께 나올 때만 이름 뒤 작은 한자. K3 3.1.2 hj 헬퍼로 바꿀 자리."""
    if ' ' in n:
        ko, han = n.split(' ', 1)
        return f'{ko}<span class="muted" lang="zh-Hant" style="font-size:10px;font-weight:400;margin-left:3px">{han}</span>'
    return n


def warn_chips(ws):
    tone = {'군 치소': 'bronze', '수도': 'bronze'}
    return ''.join(chip(w, tone.get(w, 'rust')) for w in ws) or '—'


@board
def commandery():
    rows = [[f'<span class="serif" style="font-weight:700">{same_name(n)}</span>', g if g not in ('빈자리',) else f'<span class="rs">{g}</span>', p]
            + ['[값]'] * 7 + [w, warn_chips(ws)] for n, g, p, k, w, ws in COUNTIES17]
    rows.append([f'<span class="muted">외 2현 — 신급현 · 번창현</span>'] + [''] * 11)
    table = tbl(['현', '현령', '방침', '호구', '전답', '시장', '치안', '민심', '방비', '성벽', '공사', '경고'], rows, 'font-size:11.5px')
    left = f'<section class="panel" style="flex:1;min-width:0">{sec("영천군 · 현 17", "행을 누르면 현 상세 · 현령 앉히기")}<div style="padding:0 8px;overflow:hidden">{table}</div></section>'
    summ = ''.join(row(f'<span class="t2" style="font-size:12px;width:120px">{a}</span><span style="font-size:12px">{b}</span>', 44)
                   for a, b in [('빈 현령 자리', '<span class="rs mono">4 / 17</span>'), ('민심 위험', '무양현'), ('자재 부족', '양성현 · 번창현'), ('적 군단 · 고립', '양성현 · 겹현 · 부성현 · 윤씨현')])
    right = (f'<div style="width:360px;flex-shrink:0;display:flex;flex-direction:column;gap:12px">'
             f'<section class="panel">{sec("군 방침", "소속 현 전체에 · 조정 직할 현은 빠짐")}<div style="padding:10px 12px;display:flex;flex-direction:column;gap:8px">'
             f'<span style="font-size:13px">지금 — <span class="serif" style="font-weight:700">없음</span> <span class="muted" style="font-size:12px">현마다 따로</span></span>'
             f'{seg(POLICIES[:3], None, "방침")}{seg(POLICIES[3:], None, "방침")}'
             f'{input_btn("영천군 전체에 걸기", "BLOCKED", "군 방침은 군주만", input_id="policy.set", kind="")}</div></section>'
             f'{panel("군 요약", "이번 순", summ)}'
             f'<section class="panel" style="flex:1">{sec("태수 · 관직", "읽기 — 관직 화면")}{state_waiting("관직 — 준비 중", "관직 체계(2층)가 오면 태수와 관할이 보입니다. 현령은 배치 · 발령으로 정합니다.", pad=10)}</section></div>')
    band = (f'<div style="min-height:56px;flex-shrink:0;display:flex;align-items:center;gap:10px;padding:0 16px;border-bottom:1px solid #2c342f;background:#141816">'
            f'<span class="serif" style="font-size:20px;font-weight:900">영천군</span>{chip("예주")}{chip("지금 보임", "moss")}{chip("군 치소 양적현", "bronze")}'
            f'<span class="t2" style="font-size:12px;margin-left:12px">이웃 군</span>{btn("하남윤", "sm")}{btn("진류군", "sm")}{btn("여남군", "sm")}'
            f'<span style="margin-left:auto;display:flex;gap:8px">{seg(["이 군", "우리 세력 전체"], "이 군", "범위")}{btn("첩보", "sm", attrs="data-input-id=action.scout")}</span></div>')
    head = pagehead('영지', TER_TABS, '군 내정 현황', btn('도움말', '', 'help')) + band
    page31('V31K4Commandery.dc.html', 'K4 P-T03 군 내정 현황 — 데스크톱', shell_desk('영지', 'territory', desk_main(head, left + right)))


@board
def mcommandery():
    summ = (f'<div style="border:1px solid #9c7f3f;background:#141816;padding:8px 12px;display:flex;flex-direction:column;gap:2px">'
            f'<div style="display:flex;align-items:center;gap:6px"><span class="serif" style="font-size:17px;font-weight:900">영천군</span>{chip("현 17")}{chip("지금 보임", "moss")}</div>'
            + ''.join(f'<button type="button" class="opt" style="min-height:44px;padding:0"><span class="t2" style="font-size:12px;width:96px">{a}</span><span style="font-size:12px">{b}</span></button>'
                      for a, b in [('빈 현령', '4 / 17'), ('민심 위험', '무양현'), ('적 군단 · 고립', '양성현 외 3')]) + '</div>')
    cards = ''.join(f'<a href="#" style="border:1px solid #3d4740;background:#141816;padding:8px 10px;display:flex;flex-direction:column;gap:4px;color:#ece6d8;min-height:64px">'
                    f'<span style="display:flex;align-items:center;gap:6px"><span class="serif" style="font-weight:700">{same_name(n)}</span>{warn_chips(ws)}</span>'
                    f'<span class="muted" style="font-size:11.5px">현령 {g} · 방침 {p} · 호구 [값] · 민심 [값] · 방비 [값]</span></a>'
                    for n, g, p, k, w, ws in COUNTIES17[:4])
    inner = f'{summ}{seg(["이 군", "우리 세력 전체"], "이 군", "범위")}{cards}'
    foot = input_btn('군 방침', 'BLOCKED', '군주만', input_id='policy.set', kind='', style='flex:1')
    page31('V31K4MCommandery.dc.html', 'K4 P-T03 군 내정 현황 — 모바일',
           shell_mob(mob_main(inner, TER_TABS, '군 내정 현황', foot=foot), 'territory', '군 내정 현황', '영지'), w=390, h=844)


# ------------------------------------------------------------------ P-T04 창고망 · 보급
WH = [('허현', '수도', 'bronze', ['[값]'] * 5, '본망'), ('양적현', '군 치소', 'bronze', ['[값]'] * 4 + ['—'], '본망'),
      ('장사현', '현', '', ['[값]', '[값]', '—', '[값]', '—'], '본망'), ('영천 군단', '야전 치중', 'info', ['[값]', '[값]', '—', '—', '[값]'], '본망'),
      ('윤씨현', '고립', 'rust', ['[값]', '[값]', '—', '—', '—'], '끊긴 조각')]


@board
def supply():
    rows = [[f'<span class="serif" style="font-weight:700">{n}</span>', chip(k, t)] + v + [f'<span class="{"rs" if f != "본망" else "t2"}">{f}</span>'] for n, k, t, v, f in WH]
    rows.append(['<span class="t2">합계(본망)</span>', ''] + ['[값]'] * 5 + [''])
    left = (f'<section class="panel" style="flex:1;min-width:0">{sec("창고별 재고", "창고 5 · 다섯 자원 모두 실물")}'
            f'<div style="padding:0 12px">{tbl(["창고", "구분", "금", "쌀", "철", "목재", "말", "조각"], rows, "font-size:12.5px")}</div>'
            f'<div style="padding:10px 12px;display:flex;flex-direction:column;gap:8px">'
            f'<div style="display:flex;align-items:center;gap:8px;padding:8px 10px;border:1px dashed #c96b5d">{icon("alert", 16, "#e08a7c")}<span class="rs" style="font-size:12.5px">읽지 못한 창고 1곳 — 창고 기록이 깨져 합계에서 뺐습니다</span></div>'
            f'{note("세력의 금은 수도 창고에 있습니다. 수도가 함락되면 빼앗깁니다. 이어진 창고끼리는 순 경계마다 물자가 저절로 옮겨 가고, 멀수록 늦게 닿습니다.")}</div></section>')
    cut = (f'<div style="padding:10px 12px;display:flex;flex-direction:column;gap:6px">'
           f'<div style="display:flex;align-items:center;gap:8px"><span class="serif" style="font-weight:700">양적현 — 윤씨현</span>{chip("끊김", "rust")}<span class="muted mono" style="font-size:11px">3월 중순부터</span></div>'
           f'<span class="t2" style="font-size:12px">윤씨현은 수도와 끊겨 제 창고만 씁니다.</span>'
           f'<div style="display:flex;align-items:center;gap:6px"><span class="t2" style="font-size:12px">끊긴 까닭</span>{chip("준비 중", "info")}{note("적 군단 · 수역 봉쇄 · 소유 변경 · 계절 길 닫힘 중 무엇인지는 서버가 곧 줍니다(K4-06).")}</div>'
           f'{btn("지도에서 보기 — 보급선 켜고", "", "war", href="#")}</div>')
    right = (f'<div style="width:400px;flex-shrink:0;display:flex;flex-direction:column;gap:12px">{panel("끊긴 곳 1", "", cut)}'
             f'<section class="panel" style="height:230px">{sec("녹봉 · 부대 유지비", "다음 월 경계(상순)")}{state_waiting("지급 전망 — 준비 중", "녹봉 · 유지비는 카드가 있는 곳의 망에서 나갑니다. 못 받을 사람 · 부대 목록은 서버가 아직 주지 않습니다(K4-14).", pad=8)}</section>'
             f'<section class="panel" style="flex:1">{sec("수송", "망 밖 · 급한 집중")}<div style="padding:10px 12px;display:flex;flex-direction:column;gap:8px">'
             f'{note("창고 사이 수송 명령은 아직 입력이 정해지지 않았습니다.")}{chip("준비 중", "info")}</div></section></div>')
    head = pagehead('영지', TER_TABS, '창고망 · 보급', btn('도움말', '', 'help'))
    page31('V31K4Supply.dc.html', 'K4 P-T04 창고망 · 보급 — 데스크톱', shell_desk('영지', 'territory', desk_main(head, left + right)))


@board
def msupply():
    tot = f'<div style="border:1px solid #9c7f3f;background:#141816;padding:10px 12px;display:flex;flex-direction:column;gap:6px"><span class="t2" style="font-size:12px">본망 합계</span>{resline()}</div>'
    cards = ''.join(f'<div style="border:1px solid {"#c96b5d" if f != "본망" else "#3d4740"};background:#141816;padding:8px 10px;display:flex;flex-direction:column;gap:4px;min-height:60px">'
                    f'<span style="display:flex;align-items:center;gap:6px"><span class="serif" style="font-weight:700">{n}</span>{chip(k, t)}</span>'
                    f'<span class="mono t2" style="font-size:11.5px">금 {v[0]} · 쌀 {v[1]} · 철 {v[2]} · 목재 {v[3]} · 말 {v[4]}</span></div>' for n, k, t, v, f in WH)
    inner = f'{tot}{seg(["창고 5", "끊긴 곳 1", "위험"], "창고 5", "보기")}{cards}'
    page31('V31K4MSupply.dc.html', 'K4 P-T04 창고망 · 보급 — 모바일', shell_mob(mob_main(inner, TER_TABS, '창고망 · 보급'), 'territory', '창고망 · 보급', '영지'), w=390, h=844)


# ================================================================== 군단 — P-C02 공성
def siege_kv():
    return (f'<div style="display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:6px">'
            + ''.join(kv(a, b) for a, b in [('성 안 수비', '[값]'), ('성 안 사기', '[값]%'), ('성 안 쌀', '[값] · ▼'), ('민심', '[값]'), ('포위 병력', '[값]'), ('포위군 급식', '받는 중')]) + '</div>')


FALL = ('<ul class="ul" style="padding:0 12px"><li><b>현</b> — 진류현 전체가 넘어온다</li><li><b>창고</b> — 금 · 쌀 · 철 · 목재 · 말을 빼앗는다</li>'
        '<li><b>수도 창고</b> — 해당 없음(수도 아님)</li><li><b>포로</b> — 성 안 인물이 잡힐 수 있다</li></ul>')


@board
def siege():
    lst = (f'<section class="panel" style="width:360px;flex-shrink:0">{sec("포위 중인 성", "1곳 · 보루 1")}'
           f'<div role="listbox" aria-label="포위">{opt("진류현", "원소 소속 · 포위 2순째 · 강공까지 [n]순", chip("포위 중", "bronze"), sel=True, h=64)}'
           f'{opt("[길목] 보루", "도로 보루 · 원소 · 포위 진척 [값]", chip("보루"), h=64)}</div>'
           f'<div style="padding:10px 12px">{note("군단이 적 성에 닿으면 여기에 나옵니다. 성 이름을 누르면 오른쪽에 형편이 보입니다.")}</div></section>')
    tl = ''.join(row(f'<span class="mono muted" style="font-size:11px;width:84px">{a}</span><span style="font-size:12.5px">{b}</span>', 44)
                 for a, b in [('2월 하순', '포위 시작'), ('3월 상순', '성 안 쌀 ▼ · 사기 [값]'), ('3월 중순', '성 안 쌀 ▼ · 수비 [값]')])
    mid = (f'<section class="panel" style="flex:1;min-width:0">{sec("진류현 陳留", "원소 → 수비 · 보이는 만큼만 · 마지막 첩보 1순 전")}'
           f'<div style="padding:10px 12px">{vista("castle_chenliu", 590, 220, 768, 768, -90, -250, "진류현 성 — 특 B안 11칸")}</div>'
           f'<div style="padding:0 12px 10px">{siege_kv()}</div>'
           f'<div style="padding:8px 12px;display:flex;align-items:center;gap:8px;border-top:1px solid #2c342f"><span class="t2" style="font-size:12px">포위군 보급</span>'
           f'{chip("창고와 이어짐", "moss")}{chip("보급로가 한 줄 — 끊기면 병력이 준다", "rust")}</div>{sec("포위 기록", "")}{tl}</section>')
    right = (f'<div style="width:360px;flex-shrink:0;display:flex;flex-direction:column;gap:12px">'
             f'<section class="panel">{sec("명령", "명령 목록 12순에 넣는다 — 순을 고른다")}<div style="padding:10px 12px;display:flex;flex-direction:column;gap:8px">'
             f'{help_strip("강공은 성새전(실시간 전투)을 엽니다. 부재 중이면 AI가 대신 싸웁니다.")}'
             f'{input_btn("강공", "BLOCKED", "포위 [n]순째부터", input_id="action.assault", kind="")}'
             f'{input_btn("항복 권고 — 순 고르기", "AVAILABLE", input_id="action.demandSurrender", style="width:100%")}</div></section>'
             f'{panel("항복 권고", "민심 · 성 안 쌀 · 계책으로 판정", row(note("성 안 민심이 낮을수록, 쌀이 줄수록 받아들이기 쉽습니다. 판정 계수 [미정]. 지난 권고 — 없음"), 64))}'
             f'{panel("함락되면", "", FALL)}'
             f'<section class="panel">{sec("계책", "")}<div style="padding:8px 12px">{link("계책 덱에서 공성 계책 쓰기 →")}</div></section></div>')
    head = pagehead('군단', CORPS_TABS, '공성', btn('도움말', '', 'help'))
    page31('V31K4Siege.dc.html', 'K4 P-C02 공성 — 데스크톱', shell_desk('군단', 'corps', desk_main(head, lst + mid + right)))


@board
def msiege():
    inner = (f'<div style="display:flex;align-items:center;gap:6px"><span class="serif" style="font-size:18px;font-weight:900">진류현</span>{chip("포위 중", "bronze")}{chip("2순째")}</div>'
             f'{vista("castle_chenliu", 366, 150, 768, 768, -200, -300, "진류현 성")}{seg(["형편", "기록", "보루 1"], "형편", "보기")}{siege_kv()}'
             f'<div style="display:flex;gap:6px;flex-wrap:wrap">{chip("보급 이어짐", "moss")}{chip("보급로 한 줄", "rust")}</div>')
    foot = f'{input_btn("강공", "BLOCKED", "[n]순째부터", input_id="action.assault", kind="")}{input_btn("항복 권고", "AVAILABLE", input_id="action.demandSurrender", style="flex:1")}'
    page31('V31K4MSiege.dc.html', 'K4 P-C02 공성 — 모바일', shell_mob(mob_main(inner, foot=foot), 'corps', '공성', '군단'), w=390, h=844)


# ================================================================== 조정 — P-K01
def court_card(title, sub, body, style=''):
    return f'<section class="panel" style="{style}">{sec(title, sub)}{body}</section>'


@board
def court():
    req = (f'<div style="flex-shrink:0;display:flex;gap:12px;padding:12px 12px 0">'
           f'<section class="panel" style="flex:1.4;border-color:#9c7f3f">{sec("받은 요청", "응답은 명령 목록 순을 쓰지 않는다")}'
           f'<div style="display:flex;gap:12px;padding:10px 12px">{portrait("jojo", "조조", 44, 62)}<div style="display:flex;flex-direction:column;gap:4px;flex:1;min-width:0">'
           f'<span class="serif" style="font-size:16px;font-weight:900">진류 방면 군단장으로 부임하라</span>'
           f'<span class="muted" style="font-size:12px">발령 · 주공 조조 → 하후돈 · 기한 [미정] — 지나면 수락으로 봅니다 · 거절하면 충성 · 명망이 깎입니다</span></div>'
           f'<div style="display:flex;gap:8px;align-items:center">{input_btn("수락", "AVAILABLE", input_id="court.dispatchReply")}{input_btn("거절", "AVAILABLE", input_id="court.dispatchReply", kind="danger")}</div></div></section>'
           f'<section class="panel" style="flex:1">{sec("정치 동의", "선양 · 결의 제안")}{state_empty("답할 제안이 없습니다", "누가 선양 · 결의를 제안하면 여기와 지난 순 서랍에 카드가 뜹니다.", pad=8)}</section></div>')
    disp = court_card('발령', '사람 장수를 자리에 보낸다',
                      state_denied('발령은 주공만 할 수 있습니다', '주공이 되려면 거병하거나 독립해야 합니다. 주공이 되면 내 부의 사람 장수에게 발령을 냅니다.', '발령', pad=12),
                      'flex:1;min-width:0;display:flex;flex-direction:column')
    reward = court_card('포상', '상사 · 몰수 · 봉록',
                        f'<div style="padding:10px 12px;display:flex;flex-direction:column;gap:8px">'
                        f'{field("상사 — 창고 금을 내린다", inp("", "대상 — 내 부 인물 고르기", ic="retinue"))}'
                        f'{field("금액", inp("", "[값]", unit="금"), help="충성 변화 · 한 번 상한 · 쓸 수 있는 금은 서버 값(K4-15)")}'
                        f'{input_btn("상사", "BLOCKED", "주공만", input_id="court.reward", kind="")}'
                        f'<div style="display:flex;align-items:center;gap:8px;border-top:1px solid #2c342f;padding-top:8px">{input_btn("몰수", "NOT_DELIVERED", input_id="court.confiscate", kind="")}</div>'
                        f'<div style="display:flex;align-items:center;gap:8px;border-top:1px solid #2c342f;padding-top:8px"><span class="t2" style="font-size:12px">봉록</span>{chip("순마다 자동 · 전망 준비 중", "info")}</div></div>',
                        'flex:1;min-width:0')
    dec = court_card('조정 결정', '결정권자의 턴에 실행',
                     row(name_block('부대 탈퇴 지시', '군단에서 부대를 빼게 한다') + input_btn('고르기', 'BLOCKED', '주공만', input_id='court.releaseCorps', kind=''), 64)
                     + row(name_block('현 포기', '지도에서 우리 현 고르기') + input_btn('고르기', 'BLOCKED', '군주만', input_id='court.abandonCounty', kind=''), 64)
                     + row(name_block('천도', '지금 수도 허현 · 새 수도를 지도에서') + input_btn('고르기', 'BLOCKED', '군주만', input_id='court.moveCapital', kind=''), 64)
                     + row(link('관직 · 봉신 →') + link('외교 →', 'margin-left:16px'), 52),
                     'flex:1;min-width:0')
    body = f'<div style="display:flex;flex-direction:column;gap:12px;flex:1;min-width:0">{req.replace("padding:12px 12px 0", "padding:0")}<div style="flex:1;min-height:0;display:flex;gap:12px">{disp}{reward}{dec}</div></div>'
    head = pagehead('조정', COURT_TABS, '발령 · 포상 · 조정 결정', btn('도움말', '', 'help'))
    page31('V31K4Court.dc.html', 'K4 P-K01 발령 · 포상 · 조정 결정 · 천도 — 데스크톱(장수 권한)', shell_desk('조정', 'court', desk_main(head, body)))


@board
def mcourt():
    def crow(n, sub, st, why_=''):
        if st == 'on':
            return (f'<a href="#" style="min-height:60px;display:flex;align-items:center;gap:10px;padding:0 12px;border-bottom:1px solid #2c342f;color:#ece6d8">'
                    f'{name_block(n, sub)}{chip(why_ or "가능", "moss" if not why_ else "bronze")}</a>')
        return (f'<button type="button" class="opt" aria-disabled="true" aria-haspopup="dialog" style="min-height:60px">'
                f'<span style="display:flex;flex-direction:column;min-width:0;flex:1"><span class="nm">{n}</span><span class="sub">{sub}</span></span><span class="end">{why_tag(why_)}</span></button>')
    lst = (f'<div style="border:1px solid #9c7f3f;background:#141816;padding:10px 12px;display:flex;flex-direction:column;gap:6px">'
           f'<span class="serif" style="font-weight:900">받은 요청 — 발령</span><span class="muted" style="font-size:11.5px">주공 조조 · 진류 방면 군단장 · 기한 [미정]</span>'
           f'<div style="display:flex;gap:8px">{input_btn("수락", "AVAILABLE", input_id="court.dispatchReply", style="flex:1")}{input_btn("거절", "AVAILABLE", input_id="court.dispatchReply", kind="danger", style="flex:1")}</div></div>'
           + crow('정치 동의', '선양 · 결의 제안에 답한다', 'on', '없음')
           + crow('발령', '사람 장수를 자리에 보낸다', 'off', '주공만') + crow('포상 · 몰수', '상사 · 봉록', 'off', '주공만')
           + crow('천도 · 현 포기', '수도를 옮긴다 · 현을 버린다', 'off', '군주만'))
    sh = sheet('천도 — 지금은 할 수 없습니다',
               f'<div style="padding:4px 16px 12px;display:flex;flex-direction:column;gap:10px">'
               f'<div style="border:1px solid #c96b5d;background:rgba(201,107,93,.12);padding:10px 12px;display:flex;flex-direction:column;gap:4px">'
               f'<span class="rs" style="font-size:14px;font-weight:700">천도는 군주만 할 수 있습니다.</span><span class="t2" style="font-size:12px">하후돈은 지금 조조를 섬기는 장수입니다.</span></div>'
               f'<div class="inset" style="padding:10px 12px;display:flex;flex-direction:column;gap:4px"><span class="bz" style="font-size:12px;font-weight:700">이렇게 하면 됩니다</span>'
               f'<span class="t2" style="font-size:12px;line-height:1.5">세력의 군주가 되어야 합니다. 천도는 결정권자의 턴에 바로 수도를 옮깁니다.</span></div></div>',
               height=330, bottom=0, foot=btn('도움말 — 천도', '', 'help', style='flex:1') + btn('확인', 'primary', style='flex:1'))
    main = (f'<main style="height:724px;flex-shrink:0;position:relative;overflow:hidden;display:flex;flex-direction:column">'
            f'{mtabs_row(COURT_TABS, "발령 · 포상 · 조정 결정")}<div style="padding:10px 12px;display:flex;flex-direction:column;gap:0">{lst}</div>'
            f'<div class="scrim"></div>{sh}</main>')
    page31('V31K4MCourt.dc.html', 'K4 P-K01 조정 — 모바일(사유 시트)', shell_mob(main, 'menu', '조정', '전체 메뉴'), w=390, h=844)


# ================================================================== (이어짐)
# ------------------------------------------------------------------ 검사 · 실행
class _Check(HTMLParser):
    VOID = {'meta', 'link', 'img', 'br', 'input', 'hr', 'source', 'path', 'circle', 'rect', 'polyline', 'line', 'ellipse', 'polygon'}

    def __init__(self):
        super().__init__()
        self.stack, self.err = [], []

    def handle_starttag(self, t, a):
        if 'title' in dict(a) and t not in ('svg',):
            self.err.append(f'title 속성({t}) — 호버 전용 금지')
        if t not in self.VOID:
            self.stack.append(t)

    def handle_startendtag(self, t, a):
        pass

    def handle_endtag(self, t):
        if t in self.VOID:
            return
        if not self.stack or self.stack[-1] != t:
            self.err.append(f'닫힘 어긋남: </{t}> (열린 {self.stack[-3:]})')
            if t in self.stack:
                while self.stack and self.stack.pop() != t:
                    pass
        else:
            self.stack.pop()


def check(path):
    s = open(path, encoding='utf-8').read()
    c = _Check()
    c.feed(s)
    errs = c.err + ([f'안 닫힌 태그 {c.stack}'] if c.stack else [])
    import re
    if re.search('[\U0001F300-\U0001FAFF☀-➿]', s):
        errs.append('이모지')
    for bad in ('휘하', '군량', '국고', '자금', '縣 ', '郡 ', '城 ', '年', '月 '):
        body = s.split('<body>', 1)[-1]
        if bad in body:
            errs.append(f'금지어 「{bad.strip()}」')
    return errs


if __name__ == '__main__':
    for f in glob.glob(os.path.join(P, 'V31K4*.dc.html')):
        os.remove(f)
    for b in BOARDS:
        b()
    bad = 0
    for f in sorted(glob.glob(os.path.join(P, 'V31K4*.dc.html'))):
        e = check(f)
        if e:
            bad += 1
            print(os.path.basename(f), e[:5])
    print(f'ok boards_v31_k4 — {len(BOARDS)} boards, 검사 실패 {bad}')
