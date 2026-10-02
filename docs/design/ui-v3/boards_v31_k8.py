# K8 — 2·3층 · 시즌 화면 보드(v3.1). 설계서: 메타 reports/opensamguk/tasks/2026-09-30-k8-design-spec.md
# 등급 A·B 페이지만 그린다(관직 · 봉신 · 황실 · 통일 · 세력 · 주변 세계 · 역정보). 칭제(D)는 보드 없음, 참모 제안 · 시즌 결산 · 제도(C)는 나중.
# 본 보드 = 서버가 준비된 뒤의 목표 모양. 지금 모양(서버 대기 A)은 페이지마다 상태 보드에 따로 둔다(설계서 §2.2).
# 수치는 서버 응답 · 원장 값이 들어갈 자리를 [값], 설계에서 정하지 않은 것은 [미정]. 사람 자리 표시는 [인물].
# 지명 · 치소 · 관할 구성은 data/map/han-tiles.json 과 data/curated/han/administrative-zhou-axis-v1.json 에서 옮겼다.
# v31system.py · names.py(K3) · v31assets.py(K0)는 고치지 않는다.
#
# PYTHONPATH=<v31assets 폴더> python3 boards_v31_k8.py → project/V31K8*.dc.html
import glob
import os

from v31system import *  # noqa: F401,F403
from v31system import CELLS, DESK_PX, MOB_PX, NATION, P, PT, ART, MAP, HAVE_ASSETS

WHO_LORD = '조조 · 군주'


def as_lord(html):
    """군주가 볼 때의 보드 — 머리줄 사람 칩만 바꾼다(임명 · 봉신 세우기는 군주의 결정)."""
    return html.replace('하후돈 · 조조 소속', WHO_LORD)


def desk(name, title, rail_on, head, tabs, on, body, actions='', lord=False, season_dot=False):
    main_ = (f'<main style="flex-grow:1;min-width:0;display:flex;flex-direction:column;position:relative">'
             + pagehead(head, tabs, on, actions) + body + '</main>')
    html = shell_desk(head, rail_on, main_, season_dot=season_dot)
    page31(name, title, as_lord(html) if lord else html)


def mob(name, title, tab_on, mtitle, back, tabs, on, body, lord=False):
    main = (f'<main style="height:724px;flex-shrink:0;position:relative;overflow:hidden;display:flex;flex-direction:column">'
            + (mtabs_row(tabs, on) if tabs else '') + body + '</main>')
    html = shell_mob(main, tab_on, mtitle, back)
    page31(name, title, as_lord(html) if lord else html, w=390, h=844)


def grid2(right_w, left, right, pad=12):
    return (f'<div style="flex-grow:1;display:grid;grid-template-columns:minmax(0,1fr) {right_w}px;gap:12px;padding:{pad}px;min-height:0">'
            f'{left}{right}</div>')


def col(*parts, gap=12):
    return f'<div style="display:flex;flex-direction:column;gap:{gap}px;min-height:0;min-width:0">{"".join(parts)}</div>'


def panel(title, sub, inner, style=''):
    return f'<section class="panel" style="{style}">{sec(title, sub)}{inner}</section>'


def warnbar(t, tone='rust'):
    c = {'rust': ('#e08a7c', 'rgba(201,107,93,.10)', '#c96b5d'), 'info': ('#7aa7c7', 'rgba(122,167,199,.10)', '#4b6d87'),
         'bronze': ('#d3b064', 'rgba(211,176,100,.10)', '#9c7f3f')}[tone]
    ic = {'rust': 'alert', 'info': 'clock', 'bronze': 'lock'}[tone]
    return (f'<div role="status" style="min-height:44px;display:flex;align-items:center;gap:8px;padding:6px 12px;background:{c[1]};'
            f'border-bottom:1px solid {c[2]};color:{c[0]};font-size:12.5px;line-height:1.45;flex-shrink:0">{icon(ic, 16, c[0])}<span>{t}</span></div>')


def note(t, style=''):
    return f'<span class="note" style="{style}">{t}</span>'


def req(kind, who_key, who, what, due, consequence='', input_id=''):
    """K3 request_card — 입력 id 가 정해지지 않은 요청(K8-02 등)은 data-input-id 를 달지 않는다."""
    html = request_card(kind, who_key, who, what, due, consequence, input_id=input_id or 'X')
    return html if input_id else html.replace(' data-input-id=X', '')


def lordchip():
    return chip('군주가 볼 때', 'bronze')


def game_term():
    return chip('게임 용어', 'info')


def nat_dot(n):
    return f'<i class="dot" style="background:{NATION.get(n, "#5a625c")}"></i>'


def pfield(label, value, sub='', pick='지도에서 고르기'):
    """고른 값 + 고르기 단추(PlaceField 모양) — 44 + 44."""
    return field(label, f'<div style="display:flex;gap:8px"><div class="inp" style="flex:1;flex-direction:column;align-items:flex-start;justify-content:center;gap:0;height:52px">'
                        f'<span style="font-size:14px">{value}</span><span class="muted" style="font-size:11px">{sub}</span></div>'
                        f'<button type="button" class="btn" style="height:52px">{icon("target", 18)}{pick}</button></div>')


# ================================================================== P-K03 관직 — 지방 관직(2층)
TABS_OFF = ['지방 관직', '추천 · 자칭', '중앙 관직', '봉신']
ST = {  # 서버 state → 칩(설계서 P-K03 표)
    'EFFECTIVE': ('실권 있음', 'moss'), 'NOMINAL': ('명목', 'rust'), 'AWAITING_ARRIVAL': ('부임 전', 'info'),
    'PENDING_ACCEPTANCE': ('수락 대기', 'info'), 'VACANT': ('공석', ''), 'PLACED': ('배치됨', ''), 'VACANT_COUNTY': ('공석', ''),
}
EVID = [('LIVING_CLAIM', '앉은 사람이 살아 있다'), ('ACCEPTED_TENURE', '임명을 받아들였다'), ('ASSUMED_SEAT', '부임했다'),
        ('SEAT_OWNED', '치소 현을 가졌다'), ('HOLDER_AT_SEAT', '앉은 사람이 치소에 있다'), ('COUNTY_MAJORITY', '관할 현을 문턱 넘게 가졌다'),
        ('WAREHOUSE_CONNECTION', '치소 창고가 보급망에 이어졌다'), ('LOCAL_MAGISTRATE_OR_GARRISON', '현령이 앉았거나 군대가 머문다')]

# (깊이, 관할, 꼬리표, 관직, 앉은 사람, 상태, 실효 현)
TREE = [
    (0, '예주', '', '자사', '—', 'VACANT', '—'),
    (1, '영천군', '치소 양적현', '태수', '하후돈', 'EFFECTIVE', '[값]곳'),
    (2, '양적현', '군 치소', '현령', '—', 'VACANT_COUNTY', 'lock'),
    (2, '장사현', '', '현령', '이전', 'PLACED', 'lock'),
    (2, '허현', '', '현령', '[인물]', 'PLACED', 'lock'),
    (2, 'more', '', '', '', '', ''),
    (1, '여남군', '치소 평여현', '태수', '[인물]', 'AWAITING_ARRIVAL', '—'),
    (1, '양국', '치소 하읍현', '국상', '—', 'VACANT', '—'),
    (1, '패국', '치소 상현', '국상', '[인물]', 'NOMINAL', '0곳'),
    (0, '연주', '', '주목', '조조', 'EFFECTIVE', '감찰'),
    (1, '진류군', '치소 진류현', '태수', '하후돈', 'PENDING_ACCEPTANCE', '—'),
    (1, '동군', '치소 연현', '태수', '[인물]', 'EFFECTIVE', '[값]곳'),
]
TCOLS = '248px 56px 128px minmax(0,1fr) 92px'


def tree_row(d, name, tag, office, holder, st, eff, sel=False, h=44):
    if name == 'more':
        return (f'<button type="button" aria-expanded="false" style="height:{h}px;width:100%;display:flex;align-items:center;gap:6px;padding:0 12px 0 {12 + d * 18}px;'
                f'background:transparent;border:0;border-bottom:1px solid #2c342f;color:#b9b2a3;font:inherit;font-size:12.5px;cursor:pointer">{icon("next", 14)}현 16곳 더 — 펼치기</button>')
    lbl, tone = ST[st]
    tagc = chip(tag, 'bronze') if tag else ''
    who = (f'<span class="serif" style="font-weight:700">{holder}</span>{chip("나", "bronze")}' if holder == '하후돈'
           else f'<span class="{"muted" if holder in ("—", "[인물]") else ""}">{holder}</span>')
    if eff == 'lock':
        effc = f'<span style="display:inline-flex;align-items:center;gap:4px;color:#8a8477;font-size:11.5px">{icon("lock", 14)}배치로</span>'
    else:
        effc = f'<span class="mono" style="font-size:12px">{eff}</span>'
    fs = {0: 16, 1: 15, 2: 13}[d]
    style = 'background:rgba(211,176,100,.08);box-shadow:inset 3px 0 0 #d3b064;' if sel else ''
    return (f'<button type="button" aria-pressed="{"true" if sel else "false"}" style="height:{h}px;width:100%;display:grid;grid-template-columns:{TCOLS};gap:8px;align-items:center;'
            f'padding:0 12px;background:transparent;border:0;border-bottom:1px solid #2c342f;color:#ece6d8;font:inherit;text-align:left;cursor:pointer;{style}">'
            f'<span style="display:flex;align-items:center;gap:6px;padding-left:{d * 18}px;min-width:0"><span class="serif" style="font-weight:{900 if d == 0 else 700};font-size:{fs}px;white-space:nowrap">{name}</span>{tagc}</span>'
            f'<span class="t2" style="font-size:12px">{office}</span><span style="display:flex;align-items:center;gap:4px;font-size:12.5px;min-width:0">{who}</span>'
            f'<span>{chip(lbl, tone)}</span>{effc}</button>')


def tree_panel(sel='패국', rows=TREE, title='관할과 앉은 사람'):
    legend = (f'<div style="min-height:44px;display:flex;align-items:center;gap:6px;flex-wrap:wrap;padding:6px 12px;border-bottom:1px solid #2c342f">'
              f'{chip("실권 있음", "moss")}{chip("명목", "rust")}{chip("부임 전", "info")}{chip("수락 대기", "info")}{chip("공석")}'
              f'<span class="muted" style="font-size:11.5px;display:inline-flex;align-items:center;gap:4px;margin-left:6px">{icon("lock", 13)}현령은 배치 · 발령으로 정합니다</span></div>')
    head = (f'<div style="height:32px;flex-shrink:0;display:grid;grid-template-columns:{TCOLS};gap:8px;align-items:center;padding:0 12px;font-size:11px;color:#8a8477;'
            f'background:#141816;border-bottom:1px solid #3d4740"><span>관할</span><span>관직</span><span>앉은 사람</span><span>상태</span><span>실효 현</span></div>')
    body = ''.join(tree_row(*r, sel=(r[1] == sel)) for r in rows)
    foot = (f'<div style="margin-top:auto;padding:10px 12px;display:flex;flex-direction:column;gap:4px;border-top:1px solid #2c342f">'
            f'<span class="t2" style="font-size:12px">공석 3 · 수락 대기 1 · 명목 1</span>'
            f'<span class="muted" style="font-size:11.5px">다른 세력의 관직은 보이지 않습니다. 관직을 둘 수 없는 관할(치소를 모르는 곳)은 목록에 없습니다.</span></div>')
    return panel(title, '조조 소속 · 주 → 군국 → 현', legend + head + f'<div role="list" style="display:flex;flex-direction:column">{body}</div>' + foot)


def evid_list(missing, extra=None, h=30):
    extra = extra or {}
    rows = ''
    for code, t in EVID:
        ok = code not in missing
        mark = icon('check', 16, '#8fa77a') if ok else icon('close', 16, '#e08a7c')
        ex = extra.get(code, '')
        rows += (f'<div style="height:{h}px;display:flex;align-items:center;gap:8px;padding:0 12px;border-bottom:1px solid #1f2522;font-size:12.5px">'
                 f'{mark}<span class="{"t2" if ok else "rs"}">{t}</span><span style="margin-left:auto;display:flex;align-items:center;gap:6px">{ex}</span></div>')
    return f'<div role="list" aria-label="실효 판정" style="display:flex;flex-direction:column">{rows}</div>'


def card_pei(lord=False):
    """관할 카드 — 패국 국상, 명목(치소 상실) 경고."""
    missing = {'SEAT_OWNED', 'HOLDER_AT_SEAT', 'COUNTY_MAJORITY', 'WAREHOUSE_CONNECTION'}
    ex = {'SEAT_OWNED': '<span class="rs" style="font-size:11.5px">상현 — 잃음</span>',
          'COUNTY_MAJORITY': f'<span class="mono muted" style="font-size:11px">[값] / 21곳 · 문턱 [값]%</span>{game_term()}'}
    can = (f'<div style="padding:10px 12px;display:flex;flex-direction:column;gap:6px;border-top:1px solid #2c342f">'
           f'<span class="muted" style="font-size:11.5px">이 자리로 할 수 있는 것</span>'
           f'<div style="display:flex;gap:6px;flex-wrap:wrap;opacity:.55">{chip("군 방침 걸기")}{chip("실효 현에서 공사")}</div>'
           f'<span class="rs" style="font-size:12px">지금은 없습니다 — 명목 자리입니다.</span></div>')
    dis = (input_btn('파면', 'AVAILABLE', input_id='court.dismiss', kind='danger') if lord
           else input_btn('파면', 'BLOCKED', '권한 없음 — 군주 조조의 결정', 'court.dismiss', kind='danger'))
    acts = (f'<div style="padding:10px 12px;display:flex;gap:8px;flex-wrap:wrap;border-top:1px solid #2c342f">{dis}'
            f'{btn("치소 상현 보기", "", "war", href="#")}</div>')
    inner = (warnbar('명목입니다 — 치소 상현을 잃어 이 자리로 할 수 있는 일이 없습니다.')
             + f'<div style="padding:10px 12px;display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:8px">'
             f'{kv("앉은 사람", "[인물]")}{kv("상태", "명목", "rs")}{kv("부임", "[값]년 [값]월 [값]순")}</div>'
             f'<div style="padding:4px 12px 6px;font-size:11.5px" class="muted">실효 판정 — 모두 맞아야 실권이 있습니다</div>'
             + evid_list(missing, ex) + can + acts)
    return panel('패국 국상', '예주 · 치소 상현', inner)


def offer_panel():
    card = req('임명 제안', 'jojo', '조조', '하후돈을 <span class="bz">진류군 태수</span>로 — 받아들이면 진류현으로 부임 이동', '[값]까지 · 넘기면 수락')
    more = (f'<div style="padding:0 12px 10px;display:flex;flex-direction:column;gap:4px">'
            f'<span class="t2" style="font-size:11.5px">지금 영천군 태수와 함께 맡습니다 · 함께 맡을 수 있는 지방 관직 [값]개</span>'
            + note('응답 입력 이름은 서버 설계 대기(K8-02).') + '</div>')
    return panel('받은 임명 제안', '1건 · 응답은 장수 행동을 쓰지 않는다',
                 f'<div style="padding:8px 12px 0">{help_strip("[도움말 문장 — 주제 대기]")}</div>'
                 f'<div style="padding:8px 12px 0">{card}</div>' + more, 'flex-grow:1')


def board_offices():
    body = grid2(440, tree_panel('패국'), col(card_pei(), offer_panel()))
    desk('V31K8Offices.dc.html', 'K8 관직 — 지방 관직(데스크톱)', 'court', '관직 · 봉신', TABS_OFF, '지방 관직', body, btn('도움말', '', 'help'))


# 관할 이름 단추 자리 — K3 V31SystemMapModes 와 같은 MAP['jun'](840×480) 위 좌표(420×240 기준 × 2)
RGN = [('영천군', 120, 90), ('진류군', 300, 20), ('여남군', 330, 196), ('양국', 330, 120)]


def jur_map(W, H, states, sel, sc=1.25, ox=-150, oy=40, title='관할 고르기 — 지방 관직 임명', sub='군국 · 주 이름을 누르거나 목록에서'):
    clamp = lambda v, lo, hi: max(lo, min(hi, v))  # noqa: E731
    rg = ''
    for n, x, y in RGN:
        k, r = states.get(n, ('ok', ''))
        k = 'sel' if n == sel else k
        lab = {'sel': '고른 관할', 'no': '고를 수 없음', 'ok': '고를 수 있음'}[k]
        rg += (f'<button type="button" class="rgn {k}" aria-label="{n} — {lab}" style="left:{clamp(int(x * 2 * sc) + ox, 80, W - 110)}px;top:{int(y * 2 * sc) + oy}px">'
               f'{n}{" " + why_tag(r) if r else ""}</button>')
    return (f'<section style="position:relative;width:{W}px;height:{H}px;overflow:hidden;border:1px solid #3d4740;background:#0c0f0e;flex-shrink:0">'
            f'{mapimg("jun", int(840 * sc), int(480 * sc), "영천 일대 — 군 보기", ox, oy)}<div class="dim"></div>{rg}'
            + pick_bar(title, sub) + '</section>')


def board_offices_lord():
    states = {'영천군': ('no', '앉은 사람이 있음'), '진류군': ('no', '앉은 사람이 있음'), '여남군': ('no', '앉은 사람이 있음'), '양국': ('ok', '')}
    mp = jur_map(700, 560, states, '양국')
    lst = (f'<section class="panel" style="flex-grow:1">{sec("관할 후보", "임명할 수 있는 자리 · 서버가 준 목록")}'
           f'<div role="listbox" aria-label="관할 후보" style="display:flex;flex-direction:column">'
           + opt('양국', '예주 · 국상 자리 · 치소 하읍현', ok_chip(), sel=True, h=48)
           + opt('예주', '자사 자리 · 주 감찰', ok_chip(), h=48)
           + opt('영천군', '예주 · 태수 자리', why_tag('앉은 사람이 있음'), no=True, h=48)
           + opt('진류군', '연주 · 태수 자리', why_tag('앉은 사람이 있음'), no=True, h=48) + '</div>'
           f'<div style="margin-top:auto;padding:8px 12px;display:flex;align-items:center;gap:8px;border-top:1px solid #2c342f">'
           f'<span class="t2" style="font-size:12.5px;flex:1">고름 — <b class="bz">양국</b> · 국상 자리</span>{btn("이 관할로 정하기", "primary")}</div></section>')
    left = col(mp, lst)
    cands = (f'<div role="listbox" aria-label="후보" style="display:flex;flex-direction:column;border-top:1px solid #2c342f">'
             + person_row('sunuk', '순욱', '사람', '조조 소속', '허현', sel=True, h=56)
             + person_row('join', '조인', 'NPC', '조조 소속', '여남군', h=56)
             + person_row('heojeo', '허저', 'NPC', '조조 소속', '장사현', off_reason='[서버 사유]', h=56) + '</div>')
    form = (f'<div style="padding:10px 12px 0">{help_strip("[도움말 문장 — 주제 대기]")}</div>'
            f'<div style="padding:10px 12px;display:flex;flex-direction:column;gap:12px">'
            + pfield('관할', '양국 — 국상', '예주 · 치소 하읍현 · 현 10곳')
            + field('자리', seg(['국상'], '국상', '자리'), '이 관할에 둘 수 있는 자리만 나옵니다.')
            + f'<div class="fld"><span class="lb">앉힐 사람</span>{search("이름 · 초성으로 찾기")}</div></div>' + cands
            + f'<div style="padding:10px 12px;display:flex;flex-direction:column;gap:6px;border-top:1px solid #2c342f">'
            f'<span class="muted" style="font-size:11.5px">임명하면</span>'
            f'<span class="t2" style="font-size:12.5px;line-height:1.5">순욱은 사람 장수라 받아들여야 자리에 앉습니다. [값]까지 답이 없으면 받아들인 것으로 봅니다. 받아들인 뒤 하읍현으로 부임 이동을 시작합니다.</span></div>'
            f'<div style="margin-top:auto;padding:10px 12px;display:flex;gap:8px;border-top:1px solid #2c342f">'
            f'{input_btn("순욱을 양국 국상으로 임명", "AVAILABLE", input_id="court.appoint")}{btn("취소")}</div>')
    pend = (f'<section class="panel">{sec("보낸 임명 제안", "1건")}'
            f'<div style="height:52px;display:flex;align-items:center;gap:10px;padding:0 12px">{portrait("hahoudon", "하후돈", 30, 42)}'
            f'<span style="font-size:12.5px"><span class="serif" style="font-weight:700">하후돈</span> — 진류군 태수</span>'
            f'<span style="margin-left:auto;display:flex;gap:6px;align-items:center">{chip("수락 대기", "info")}<span class="mono muted" style="font-size:11px">기한 [값]</span></span></div></section>')
    right = col(f'<section class="panel" style="flex-grow:1">{sec("임명", "군주의 결정 · court.appoint")}{form}</section>', pend)
    body = grid2(600, left, right)
    desk('V31K8OfficesLord.dc.html', 'K8 관직 — 임명(군주가 볼 때)', 'court', '관직 · 봉신', TABS_OFF, '지방 관직', body, lordchip(), lord=True)


def board_moffices():
    def zcard(title, office, holder, st, kids):
        lbl, tone = ST[st]
        rows = ''.join(
            f'<button type="button" style="height:52px;width:100%;display:flex;align-items:center;gap:8px;padding:0 12px;background:{"rgba(211,176,100,.08)" if n == "패국" else "transparent"};'
            f'border:0;border-top:1px solid #2c342f;color:#ece6d8;font:inherit;text-align:left;cursor:pointer;{"box-shadow:inset 3px 0 0 #d3b064;" if n == "패국" else ""}">'
            f'<span style="display:flex;flex-direction:column;min-width:0;gap:1px;flex:1"><span class="serif" style="font-weight:700;font-size:15px">{n} <span class="t2" style="font-family:inherit;font-weight:400;font-size:12px">{o}</span></span>'
            f'<span class="muted" style="font-size:11.5px">{h}</span></span>{chip(ST[s][0], ST[s][1])}{icon("next", 16, "#8a8477")}</button>'
            for n, o, h, s in kids)
        return (f'<div style="border:1px solid #3d4740;background:#141816;display:flex;flex-direction:column">'
                f'<div style="min-height:52px;display:flex;align-items:center;gap:8px;padding:0 12px"><span class="serif" style="font-weight:900;font-size:17px">{title}</span>'
                f'<span class="t2" style="font-size:12px">{office} · {holder}</span><span style="margin-left:auto">{chip(lbl, tone)}</span></div>{rows}</div>')
    cards = (zcard('예주', '자사', '—', 'VACANT', [('영천군', '태수', '하후돈(나) · 실효 현 [값]곳', 'EFFECTIVE'), ('여남군', '태수', '[인물]', 'AWAITING_ARRIVAL'),
                                               ('양국', '국상', '—', 'VACANT'), ('패국', '국상', '[인물]', 'NOMINAL')])
             + zcard('연주', '주목', '조조', 'EFFECTIVE', [('진류군', '태수', '하후돈(나)', 'PENDING_ACCEPTANCE')]))
    body = (f'<div style="padding:10px 12px;display:flex;flex-direction:column;gap:8px;overflow:hidden">{cards}</div>'
            f'<div class="scrim" style="top:60px"></div>'
            + sheet('패국 국상', warnbar('명목입니다 — 치소 상현을 잃었습니다.')
                    + evid_list({'SEAT_OWNED', 'HOLDER_AT_SEAT', 'COUNTY_MAJORITY', 'WAREHOUSE_CONNECTION'},
                                {'SEAT_OWNED': '<span class="rs" style="font-size:11px">상현</span>'}, h=28),
                    height=404, foot=input_btn('파면', 'BLOCKED', '권한 없음 — 군주의 결정', 'court.dismiss', kind='danger', style='flex:1')))
    mob('V31K8MOffices.dc.html', 'K8 관직 — 지방 관직(모바일)', 'menu', '관직 · 봉신', '조정', TABS_OFF, '지방 관직', body)


def board_offices_states():
    def box(title, sub, inner):
        return f'<section class="panel">{sec(title, sub)}<div style="flex-grow:1;display:flex;flex-direction:column;min-height:0;overflow:hidden">{inner}</div></section>'
    boxes = [
        box('지금 — 서버 대기(A)', '관직 읽기가 아직 없다', state_waiting('관직 정보가 아직 없습니다')),
        box('빈 — 세력 없음', '재야 장수', state_empty('세력에 속해야 관직이 있습니다', '주공에게 출사하면 그 세력의 관직이 여기 보입니다.', btn('출사하러 가기', 'sm', href='#'))),
        box('빈 — 임명된 관직 0', '', state_empty('임명된 지방 관직이 없습니다', '군주가 자사 · 태수 · 국상을 임명하면 여기 보입니다. 현령은 배치 · 발령으로 정합니다.')),
        box('빈 — 임명 선택지 0', '군주가 볼 때', state_empty('임명할 수 있는 관할이 없습니다', '치소를 가진 군국 · 주가 있어야 관직을 둘 수 있습니다.')),
        box('오류 — 읽기 실패', '빈 목록과 다르게', state_error('관직 정보를 지금 읽을 수 없습니다')),
        box('로딩', '', state_loading(5)),
        box('추천 · 자칭 탭', '3층 · 서버 대기(C6)', state_waiting('추천 · 자칭 기록이 아직 없습니다', '추천 → 심의 → 결과, 자칭과 추인의 기록이 이 자리에 보입니다. 서버가 아직 주지 않습니다.')),
        box('중앙 관직 탭', '3층 · 조서로만 받는다', state_waiting('중앙 관직이 아직 없습니다', '삼공 · 구경 · 상서 · 장군은 황실 조서를 받아들여야 생깁니다. 서버가 아직 주지 않습니다.')),
    ]
    body = f'<div style="flex-grow:1;display:grid;grid-template-columns:repeat(4,minmax(0,1fr));grid-template-rows:1fr 1fr;gap:12px;padding:12px;min-height:0">{"".join(boxes)}</div>'
    desk('V31K8OfficesStates.dc.html', 'K8 관직 — 상태', 'court', '관직 · 봉신', TABS_OFF, '지방 관직', body)


# ================================================================== P-K03 3층 — 추천 · 자칭 · 중앙 관직(K8-05, 새 보드 초안 2026-10-01)
# 승인 보드가 없던 탭이다. 설계서 P-K03 3층 · 모델(OfficeNomination · OfficeClaimRecord · CentralOfficeCatalog)과
# 메타 2026-10-01-k8-05-10-board-slots.md 의 K8-05 칸 목록으로 그린 초안 — K0 가 사용자 확인을 받는다.
# 입력 이름은 등록 전 후보(layer3-runtime-integration.md 입력 후보 표)다.
NOM_STEP = {'DRAFT': ('작성', ''), 'SUBMITTED': ('제출', 'info'), 'UNDER_REVIEW': ('심의 중', 'info'),
            'APPROVED': ('원안대로', 'moss'), 'DOWNGRADED': ('낮은 관직으로', 'bronze'), 'ACTING': ('대행으로', 'bronze'),
            'DEFERRED': ('보류', ''), 'REJECTED': ('기각', 'rust'), 'COMPETING': ('경쟁 후보', 'bronze')}
# claim 출처 8종 중 한글 이름이 정해진 것은 셋뿐이다(설계서 P-K03 옮길 정보 항목). 나머지 다섯은 표기 미정.
ORIGIN_KO = {'IMPERIAL_GRANT': ('조서 임명', 'moss'), 'NOMINATED': ('추천됨', 'info'), 'SELF_STYLED': ('자칭', 'rust')}
CENTRAL = [('삼공', 3), ('구경', 9), ('상서', 2), ('장군', 8)]  # 2026-10-01 원장(imperial-central-offices.json) officeClass 별 자리 수 — 보드 예시. 구현은 원장에서 읽고 묶음 수 · 자리 수를 고정하지 않는다(D27).


def nom_rows(sel=0):
    rows = [('[관직] — [관할]', '[인물]', '[인물]', 'UNDER_REVIEW'), ('[관직] — [관할]', '[인물]', '[인물]', 'APPROVED'),
            ('[관직] — [관할]', '[인물]', '[인물]', 'DOWNGRADED'), ('[관직] — [관할]', '[인물]', '[인물]', 'REJECTED')]
    out = ''
    for i, (o, cand, prop, st) in enumerate(rows):
        lbl, tone = NOM_STEP[st]
        s = i == sel
        out += (f'<button type="button" aria-pressed="{"true" if s else "false"}" style="min-height:56px;width:100%;display:grid;grid-template-columns:minmax(0,1fr) 88px 88px 96px;gap:8px;align-items:center;'
                f'padding:6px 12px;background:{"rgba(211,176,100,.10)" if s else "transparent"};box-shadow:{"inset 3px 0 0 #d3b064" if s else "none"};border:0;border-bottom:1px solid #2c342f;'
                f'color:#ece6d8;font:inherit;text-align:left;cursor:pointer"><span class="serif" style="font-weight:700;font-size:14px">{o}</span>'
                f'<span style="font-size:12.5px">{cand}</span><span class="t2" style="font-size:12px">{prop}</span><span>{chip(lbl, tone)}</span></button>')
    head = (f'<div style="height:32px;display:grid;grid-template-columns:minmax(0,1fr) 88px 88px 96px;gap:8px;align-items:center;padding:0 12px;font-size:11px;color:#8a8477;'
            f'background:#141816;border-bottom:1px solid #3d4740"><span>관직 · 관할</span><span>후보</span><span>추천한 사람</span><span>단계</span></div>')
    return head + f'<div role="list" aria-label="추천" style="display:flex;flex-direction:column">{out}</div>'


def nom_detail():
    steps = ''.join(chip(t, tone) + ('<span class="muted" style="font-size:11px">→</span>' if i < 3 else '')
                    for i, (t, tone) in enumerate([('작성', 'moss'), ('제출', 'moss'), ('심의 중', 'bronze'), ('결과', '')]))
    outcomes = ''.join(chip(NOM_STEP[k][0]) for k in ('APPROVED', 'DOWNGRADED', 'ACTING', 'DEFERRED', 'REJECTED', 'COMPETING'))
    body = (f'<div style="padding:10px 12px;display:flex;flex-direction:column;gap:10px">'
            f'<div role="list" aria-label="추천 단계" style="display:flex;align-items:center;gap:4px;flex-wrap:wrap">{steps}</div>'
            f'<div style="display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:8px">{kv("후보", "[인물]")}{kv("추천한 사람", "[인물]")}{kv("심의하는 조정", "[조정]")}'
            f'{kv("바라는 관직", "[관직]")}{kv("관할", "[관할]")}{kv("기한", "[값]순까지")}</div>'
            f'<div class="inset" style="padding:8px 12px;display:flex;flex-direction:column;gap:4px"><span class="muted" style="font-size:11px">추천 근거</span>'
            f'<span class="t2" style="font-size:12.5px;line-height:1.6">[추천 글 — 서버가 준 근거]</span></div>'
            f'<div style="display:flex;align-items:center;gap:6px;flex-wrap:wrap"><span class="muted" style="font-size:11.5px">결과는 여섯 가지</span>{outcomes}</div>'
            + note('심의는 점수가 아니라 조정의 판단입니다. 원안대로 되어도 실제 임명은 지방 관직 임명 판정을 다시 거칩니다.') + '</div>')
    return panel('[관직] 추천', '심의 중 · 우리가 낸 추천', body)


def claim_history():
    rows = [('SELF_STYLED', '[인물] — [관직]', '[값]년 [값]월', ''), ('IMPERIAL_GRANT', '[인물] — [관직]', '[값]년 [값]월', '조서 [조서] · 발급 [인물]')]
    items = ''
    for org, what, when, extra in rows:
        lbl, tone = ORIGIN_KO[org]
        items += (f'<div style="min-height:52px;display:flex;align-items:center;gap:10px;padding:6px 12px;border-bottom:1px solid #2c342f">{chip(lbl, tone)}'
                  f'<span style="display:flex;flex-direction:column;gap:2px;min-width:0;flex:1"><span class="serif" style="font-weight:700;font-size:14px">{what}</span>'
                  f'<span class="muted" style="font-size:11.5px">{extra or "스스로 칭함"}</span></span><span class="mono muted" style="font-size:11px">{when}</span></div>')
    recog = (f'<div style="padding:10px 12px;display:flex;flex-direction:column;gap:6px;border-top:1px solid #2c342f">'
             f'<span class="muted" style="font-size:11.5px">세력마다 인정하는가</span>'
             f'<div style="display:flex;gap:6px;flex-wrap:wrap">{nat_dot("조조")}{chip("조조 인정", "moss")}{nat_dot("원소")}{chip("원소 다툼", "bronze")}{nat_dot("유표")}{chip("유표 부정", "rust")}</div></div>')
    power = (f'<div style="padding:10px 12px;display:flex;align-items:center;gap:8px;border-top:1px solid #2c342f">'
             f'<span class="muted" style="font-size:11.5px">실권</span>{chip("명목", "rust")}'
             f'<span class="t2" style="font-size:12px">세력 인정과 실효 판정이 둘 다 있어야 실권입니다 — 실효 판정은 지방 관직 탭과 같습니다.</span></div>')
    return panel('자칭 · 추인 이력', '앞 기록은 바꾸지 않고 덧붙인다', f'<div role="list" aria-label="자칭 · 추인 이력">{items}</div>' + recog + power)


def origin_legend():
    known = ''.join(chip(t, tone) for t, tone in ORIGIN_KO.values())
    return (f'<div style="min-height:44px;display:flex;align-items:center;gap:6px;flex-wrap:wrap;padding:6px 12px;border-bottom:1px solid #2c342f">'
            f'<span class="muted" style="font-size:11.5px">명분</span>{known}{chip("[표기 미정] × 5")}</div>')


def board_offices_claims():
    nom = (f'<section class="panel" style="flex-grow:1">{sec("추천", "우리가 낸 추천 · 추천 → 심의 → 결과")}{origin_legend()}{nom_rows()}'
           f'<div style="margin-top:auto;padding:10px 12px;display:flex;gap:8px;align-items:center;border-top:1px solid #2c342f">'
           f'{input_btn("새로 추천하기", "AVAILABLE", input_id="court.officeNominate")}'
           f'<span class="muted" style="font-size:11.5px">추천만으로는 자리에 앉지 않습니다.</span></div></section>')
    offer = panel('받은 관직 제안', '후보 본인만 답한다',
                  f'<div style="padding:8px 12px 0">{help_strip("[도움말 문장 — 주제 대기]")}</div>'
                  f'<div style="padding:8px 12px 10px">{req("관직 제안", "jojo", "[조정]", "하후돈을 <span class=bz>[관직]</span>으로 — 조정 심의를 거친 제안", "[값]까지", input_id="court.officeNominationReply")}</div>')
    body = grid2(440, col(nom, claim_history()), col(nom_detail(), offer))
    desk('V31K8OfficesClaims.dc.html', 'K8 관직 — 추천 · 자칭(데스크톱, 새 보드 초안)', 'court', '관직 · 봉신', TABS_OFF, '추천 · 자칭', body, btn('도움말', '', 'help'))


def board_offices_central():
    groups = ''
    for g, n in CENTRAL:
        rows = ''.join(f'<div style="height:44px;display:grid;grid-template-columns:minmax(0,1fr) 120px 120px 96px;gap:8px;align-items:center;padding:0 12px;border-bottom:1px solid #1f2522;font-size:12.5px">'
                       f'<span class="serif" style="font-weight:700">[관직]</span><span>{h}</span><span class="t2">{e}</span><span>{c}</span></div>'
                       for h, e, c in ([('[인물]', '조서 [조서]', chip('앉음', 'moss')), ('—', '—', chip('공석'))] if n > 1 else [('—', '—', chip('공석'))]))
        more = f'<div style="height:32px;display:flex;align-items:center;padding:0 12px" class="muted"><span style="font-size:11.5px">외 [값]자리</span></div>' if n > 2 else ''
        groups += (f'<div style="height:36px;display:flex;align-items:center;gap:8px;padding:0 12px;background:#141816;border-bottom:1px solid #3d4740">'
                   f'<span class="serif" style="font-weight:900;font-size:14px">{g}</span><span class="mono muted" style="font-size:11px">{n}자리</span></div>{rows}{more}')
    head = (f'<div style="height:32px;display:grid;grid-template-columns:minmax(0,1fr) 120px 120px 96px;gap:8px;align-items:center;padding:0 12px;font-size:11px;color:#8a8477;'
            f'background:#141816;border-bottom:1px solid #3d4740"><span>관직</span><span>앉은 사람</span><span>받은 조서</span><span>상태</span></div>')
    left = (f'<section class="panel" style="flex-grow:1">{sec("중앙 관직", "22자리 · 조서를 받아들여야 생긴다")}{head}'
            f'<div role="list" aria-label="중앙 관직" style="display:flex;flex-direction:column">{groups}</div></section>')
    det = panel('[관직]', '고른 중앙 관직',
                f'<div style="padding:10px 12px;display:flex;flex-direction:column;gap:8px">'
                f'<div style="display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:8px">{kv("묶음 · 등급", "[묶음] · [등급]")}{kv("앉은 사람", "[인물]")}'
                f'{kv("근거 조서", "[조서]")}{kv("부임", "[값]년 [값]월")}</div>'
                f'<span class="muted" style="font-size:11.5px">이 자리로 할 수 있는 것 — [미정]</span>'
                + note('중앙 관직은 지방 관직 · 자칭으로 생기지 않습니다. 조서를 받아들일 때만 생깁니다.') + '</div>')
    states = col(panel('황실 없음', '지금 모든 월드', state_empty('중앙 관직이 없습니다', '황실이 없는 시나리오라 조서가 없고, 중앙 관직도 생기지 않습니다.', pad=8)),
                 panel('모두 공석', '', state_empty('앉은 사람이 없습니다', '조서로 임명되면 여기 보입니다.', pad=8)))
    body = grid2(440, left, col(det, states))
    desk('V31K8OfficesCentral.dc.html', 'K8 관직 — 중앙 관직(데스크톱, 새 보드 초안)', 'court', '관직 · 봉신', TABS_OFF, '중앙 관직', body)


def board_moffices_claims():
    offer = (f'<section class="panel">{sec("받은 관직 제안", "후보 본인만 답한다")}'
             f'<div style="padding:8px 12px 10px">{req("관직 제안", "jojo", "[조정]", "하후돈을 <span class=bz>[관직]</span>으로", "[값]까지", input_id="court.officeNominationReply")}</div></section>')
    cards = ''.join(f'<a href="#" style="min-height:64px;display:flex;align-items:center;gap:10px;padding:8px 12px;border:1px solid #3d4740;background:#141816;color:#ece6d8">'
                    f'<span style="display:flex;flex-direction:column;gap:3px;flex:1;min-width:0"><span class="serif" style="font-weight:700;font-size:14px">[관직] — [관할]</span>'
                    f'<span style="display:flex;gap:6px;align-items:center"><span class="muted" style="font-size:11px">후보 [인물]</span>{chip(*NOM_STEP[st])}</span></span>{icon("next", 16, "#8a8477")}</a>'
                    for st in ('UNDER_REVIEW', 'APPROVED'))
    hist = (f'<section class="panel">{sec("자칭 · 추인 이력", "덧붙인다")}'
            f'<div style="min-height:52px;display:flex;align-items:center;gap:8px;padding:6px 12px;border-bottom:1px solid #2c342f">{chip("자칭", "rust")}<span class="serif" style="font-weight:700">[인물] — [관직]</span></div>'
            f'<div style="min-height:52px;display:flex;align-items:center;gap:8px;padding:6px 12px">{chip("조서 임명", "moss")}<span class="serif" style="font-weight:700">[인물] — [관직]</span>'
            f'<span style="margin-left:auto">{chip("명목", "rust")}</span></div></section>')
    body = (f'<div style="padding:10px 12px;display:flex;flex-direction:column;gap:10px;overflow:hidden">{offer}'
            f'<div style="display:flex;flex-direction:column;gap:8px"><span class="serif" style="font-weight:900;font-size:15px">우리가 낸 추천</span>{cards}</div>{hist}</div>')
    mob('V31K8MOfficesClaims.dc.html', 'K8 관직 — 추천 · 자칭(모바일, 새 보드 초안)', 'menu', '관직 · 봉신', '조정', TABS_OFF, '추천 · 자칭', body)


# ================================================================== P-K04 봉신 계약(관직 · 봉신의 탭)
RES5 = [('금', 'money'), ('쌀', 'grain'), ('철', 'iron'), ('목재', 'timber'), ('말', 'horses')]
AUTO = [('COUNTY_POLICY', '현 방침'), ('TAX_ALLOCATION', '세금 배분'), ('GARRISON_COMMAND', '수비군 지휘')]
DIPLO = [('NONE', '없음'), ('WITH_APPROVAL', '군주 승인 뒤'), ('INDEPENDENT', '독자')]


def contract_list(sel='조인'):
    rows = [('join', '조인', 'NPC', '번창현 · 임영현', '완납', 'moss'), ('', '[인물]', '사람', '[현] 외 [값]곳', '미납', 'rust')]
    out = ''
    for k, n, kd, f, t, tone in rows:
        s = n == sel
        out += (f'<button type="button" aria-pressed="{"true" if s else "false"}" style="min-height:64px;width:100%;display:flex;align-items:center;gap:10px;padding:6px 12px;'
                f'background:{"rgba(211,176,100,.10)" if s else "transparent"};box-shadow:{"inset 3px 0 0 #d3b064" if s else "none"};border:0;border-bottom:1px solid #2c342f;'
                f'color:#ece6d8;font:inherit;text-align:left;cursor:pointer">{portrait(k, n, 36, 50)}'
                f'<span style="display:flex;flex-direction:column;gap:2px;min-width:0;flex:1"><span style="display:flex;align-items:center;gap:6px">'
                f'<span class="serif" style="font-weight:700;font-size:15px">{n}</span>{chip("사람", "info") if kd == "사람" else ""}</span>'
                f'<span class="muted" style="font-size:11.5px">봉토 {f} · 상납 [값]%</span></span><span style="display:flex;flex-direction:column;align-items:flex-end;gap:4px">'
                f'{chip("이번 달 " + t, tone)}<span class="mono muted" style="font-size:11px">충성 [값]</span></span></button>')
    return out


def fief_map(W, H, picks, ox, oy, mobile=False, others=('양적현', '장사현', '영음현', '허현', '영양현', '임영현')):
    px = MOB_PX if mobile else DESK_PX
    key = 'mob' if mobile else 'desk'
    iw, ih = (390, 844) if mobile else (1048, 952)
    marks = ''
    for n in others:
        x, y = px(*CELLS[n])
        marks += mk(x - ox, y - oy, 'ok', n)
    for i, n in enumerate(picks, 1):
        x, y = px(*CELLS[n])
        marks += mk(x - ox, y - oy, 'sel', n, n=i)
    return (f'<div style="position:relative;width:{W}px;height:{H}px;overflow:hidden;border:1px solid #3d4740;background:#0c0f0e;flex-shrink:0">'
            f'{mapimg(key, iw, ih, "영천 일대 — 현 보기", -ox, -oy)}<div class="dim"></div>{marks}</div>')


def contract_detail(lord=True):
    fief = fief_map(460, 260, ['번창현', '임영현'], ox=300, oy=580, others=('영양현',))
    terms = (f'<div style="display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:8px">'
             f'{kv("상납률", "[값]% — 봉토 수입에서")}{kv("원군 의무", "[값]명 · 응답 [값]순")}'
             f'{kv("외교권", "군주 승인 뒤")}{kv("충성", "[값]")}</div>'
             f'<div style="display:flex;align-items:center;gap:6px;flex-wrap:wrap"><span class="muted" style="font-size:11.5px">자치</span>'
             f'{chip("현 방침", "moss")}{chip("세금 배분")}{chip("수비군 지휘")}<span class="muted" style="font-size:11px;margin-left:auto">맺은 때 200년 [값]월 [값]순</span>{game_term()}</div>')
    hist_rows = [['1월', *(f'<span class="mono">[값]</span>' for _ in RES5), chip('완납', 'moss')],
                 ['2월', *(f'<span class="mono">[값]</span>' for _ in RES5), chip('완납', 'moss')],
                 ['3월', *(f'<span class="mono rs">[값]</span>' if r in ('쌀', '금') else '<span class="mono">[값]</span>' for r, _ in RES5), chip('미납', 'rust')]]
    hist = tbl(['달', *[r for r, _ in RES5], ''], hist_rows, 'font-size:12px')
    acts = (f'{input_btn("계약 변경", "AVAILABLE", input_id="court.amendVassal", kind="")}{input_btn("계약 끝내기", "AVAILABLE", input_id="court.endVassal", kind="danger")}'
            if lord else f'{input_btn("계약 변경", "BLOCKED", "권한 없음 — 군주의 결정", "court.amendVassal", kind="")}')
    inner = (f'<div style="padding:10px 12px;display:grid;grid-template-columns:460px minmax(0,1fr);gap:12px">{fief}'
             f'<div style="display:flex;flex-direction:column;gap:8px"><span class="t2" style="font-size:12.5px">봉토 현 2곳 — 번창현 · 임영현</span>{terms}</div></div>'
             + warnbar('3월 상납을 다 옮기지 못했습니다 — 봉토 창고와 군주 창고가 보급망에서 끊겼습니다. [값]달 이어 못 내면 계약 위반입니다.')
             + f'<div style="padding:8px 12px 0" class="muted"><span style="font-size:11.5px">상납 이력 — 매달 수입이 들어온 뒤 한 번</span></div>'
             f'<div style="padding:4px 12px 0">{hist}</div>'
             f'<div style="margin-top:auto;padding:10px 12px;display:flex;gap:8px;border-top:1px solid #2c342f">{acts}{btn("창고망 보기", "", "territory", href="#")}</div>')
    return f'<section class="panel" style="flex-grow:1">{sec("조인 — 봉신 계약", "군주 조조 ↔ 봉신 조인")}{inner}</section>'


def found_panel():
    cands = (f'<div role="listbox" aria-label="봉신 후보" style="display:flex;flex-direction:column;border-top:1px solid #2c342f">'
             + person_row('hahoudon', '하후돈', '사람', '직속 부', '양적현', sel=True, h=56)
             + person_row('ijeon', '이전', 'NPC', '직속 부', '장사현', h=56)
             + person_row('heojeo', '허저', 'NPC', '직속 부', '장사현', off_reason='[서버 사유]', h=56) + '</div>')
    fch = ''.join(f'<span class="chip bronze" style="height:44px;gap:6px;padding:0 4px 0 10px"><span class="mono">{i}</span>{n}'
                  f'<button type="button" aria-label="{n} 빼기" style="width:44px;height:44px;margin:-1px -5px -1px 0;border:0;background:transparent;color:#d3b064;display:inline-flex;align-items:center;justify-content:center;padding:0">{icon("close", 14)}</button></span>'
                  for i, n in [(1, '양성현'), (2, '영양현')])
    body = (f'<div style="padding:10px 12px 0">{help_strip("[도움말 문장 — 주제 대기]")}</div>'
            f'<div style="padding:10px 12px 0" class="fld"><span class="lb">봉신이 될 장수 — 직속 부에서</span></div>{cands}'
            f'<div style="padding:10px 12px;display:flex;flex-direction:column;gap:10px">'
            + field('봉토 현', f'<div style="display:flex;gap:6px;flex-wrap:wrap;align-items:center">{fch}<button type="button" class="btn">{icon("target", 18)}지도에서 더 고르기</button></div>',
                    '우리 세력의 현만, 다른 봉신의 봉토와 겹치지 않게.')
            + field('상납률', inp('[값]', unit='%', style='width:160px'), '범위 [값]–[값]% · 기본 [값]%(서버 규칙)')
            + note('원군 · 자치 · 외교권은 이 결정으로 정하지 않습니다 — 맺은 뒤 계약 변경으로.')
            + f'</div><div style="margin-top:auto;padding:10px 12px;display:flex;flex-direction:column;gap:6px;border-top:1px solid #2c342f">'
            f'<span class="t2" style="font-size:12px">하후돈은 사람 장수라 동의해야 봉신이 됩니다.</span>'
            f'<div style="display:flex;gap:8px">{input_btn("하후돈을 봉신으로 세우기", "AVAILABLE", input_id="court.foundVassal", style="flex:1")}</div></div>')
    return f'<section class="panel" style="flex-grow:1">{sec("봉신 세우기", "군주의 결정 · 직속 부의 장수를 봉신 주공으로")}{body}</section>'


def board_vassals():
    left = col(f'<section class="panel">{sec("봉신", "2명 · 같은 세력의 주공")}<div role="list">{contract_list()}</div></section>', contract_detail())
    body = grid2(420, left, found_panel())
    desk('V31K8Vassals.dc.html', 'K8 봉신 계약(군주가 볼 때)', 'court', '관직 · 봉신', TABS_OFF, '봉신', body, lordchip(), lord=True)


def board_mvassal_found():
    steps = ''.join(f'<span class="chip {"bronze" if i == 2 else ("moss" if i < 2 else "")}" style="height:28px">{i} {t}</span>'
                    for i, t in [(1, '장수'), (2, '봉토'), (3, '상납'), (4, '확인')])
    mp = fief_map(390, 724, ['양성현', '영양현'], ox=40, oy=300, others=('마피영',))  # 16px/칸 현 보기(MAP['desk'])를 폰 크기로 자른다
    fch = ''.join(f'<span class="chip bronze" style="height:44px;gap:6px;padding:0 4px 0 10px"><span class="mono">{i}</span>{n}'
                  f'<button type="button" aria-label="{n} 빼기" style="width:44px;height:44px;margin:-1px -5px -1px 0;border:0;background:transparent;color:#d3b064;display:inline-flex;align-items:center;justify-content:center;padding:0">{icon("close", 14)}</button></span>'
                  for i, n in [(1, '양성현'), (2, '영양현')])
    body = (f'<div style="position:absolute;inset:0">{mp}</div>'
            + pick_bar('봉토 고르기 — 하후돈', '누를 때마다 넣고 뺀다 · 2곳 고름', mobile=True)
            + f'<section class="sheet" aria-label="고른 봉토" style="bottom:0;height:164px"><div class="grip"></div>'
            f'<div style="padding:4px 12px;display:flex;flex-direction:column;gap:8px"><div style="display:flex;gap:4px;align-items:center">{steps}<span class="t2" style="font-size:12px;margin-left:auto">봉토 <b class="bz">2</b>곳</span></div>'
            f'<div style="display:flex;gap:6px;flex-wrap:wrap">{fch}</div>'
            f'<div style="display:flex;gap:8px">{btn("이전", "", style="flex:1")}{btn("다 골랐다 — 상납", "primary", style="flex:2")}</div></div></section>')
    mob('V31K8MVassalFound.dc.html', 'K8 봉신 세우기 — 봉토 고르기(모바일, 군주)', 'menu', '봉신 세우기', '봉신', None, None, body, lord=True)


def board_mvassal_side():
    offer = (f'<section class="panel">{sec("받은 봉신 제안", "동의해야 맺어진다")}'
             f'<div style="padding:10px 12px 0">{req("봉신 제안", "jojo", "조조", "하후돈을 봉신 주공으로 — 봉토 양성현 · 영양현 · 상납 [값]%", "[값]까지")}</div>'
             f'<div style="padding:8px 12px 10px;display:flex;flex-direction:column;gap:4px"><span class="t2" style="font-size:11.5px">봉신이 되면 조조의 부를 떠나 스스로 주공이 됩니다. 내 부는 나를 따릅니다.</span>'
             + note('응답 입력 이름은 서버 설계 대기(K8-02).') + '</div></section>')
    reinf = (f'<section class="panel">{sec("받은 원군 요청", "봉신의 의무")}'
             f'<article class="req" aria-label="원군 요청 — 조조" style="margin:10px 12px 0"><div style="display:flex;gap:10px;padding:10px 12px">{portrait("jojo", "조조", 30, 42)}'
             f'<div style="display:flex;flex-direction:column;gap:3px;min-width:0;flex:1"><div style="display:flex;align-items:center;gap:6px">{chip("원군 요청", "bronze")}'
             f'<span class="serif" style="font-weight:700;font-size:14px">조조</span><span class="mono muted" style="font-size:11px;margin-left:auto">기한 [값]순</span></div>'
             f'<span style="font-size:13px">원군 [값]명을 보내라</span><span class="rs" style="font-size:12px">거절하거나 적게 보내면 — 계약 위반</span></div></div></article>'
             + f'<div style="padding:10px 12px;display:flex;flex-direction:column;gap:8px">'
             + field('보낼 병력', inp('[값]', unit='명'), '의무 [값]명 · 줄여 보내면 축소 이행')
             + f'</div><div style="padding:0 12px 10px;display:grid;grid-template-columns:1fr 1fr;gap:8px">{btn("수락", "primary")}{btn("지연")}{btn("축소해서 보냄")}{btn("거절", "danger")}</div>'
             + note('원군 응답 입력은 서버 설계 대기(C5).', 'padding:0 12px 10px;display:block') + '</section>')
    body = f'<div style="padding:10px 12px;display:flex;flex-direction:column;gap:10px;overflow:hidden">{offer}{reinf}</div>'
    mob('V31K8MVassalSide.dc.html', 'K8 봉신 — 받은 제안 · 원군 요청(모바일)', 'menu', '관직 · 봉신', '조정', TABS_OFF, '봉신', body)


# ================================================================== P-K09 황실 · 인장 · 조서
TABS_IMP = ['황실 · 조서', '칭제']
REL = {'COURT_GUARDIAN': ('조정을 지킴', 'bronze'), 'LOYAL': ('충성', 'moss'), 'INVESTED': ('책봉받음', 'moss'), 'TRIBUTARY': ('조공', ''),
       'NEUTRAL': ('중립', ''), 'REJECTED': ('인정 안 함', 'rust'), 'HOSTILE': ('적대', 'rust'), 'PRETENDER': ('따로 황제를 세움', 'rust')}
EDICT_STEPS = ['제안', '황제 심의', '상서 등록', '인장', '사자 발송', '전달', '응답']


def crown(size=18):
    return icon('crown', size, '#d3b064')


def step_band(done, cur, small=False):
    out = ''
    for i, s in enumerate(EDICT_STEPS):
        tone = 'moss' if i < done else ('bronze' if i == cur else '')
        out += chip(s, tone) + ('<span class="muted" style="font-size:11px">→</span>' if i < len(EDICT_STEPS) - 1 else '')
    return f'<div role="list" aria-label="조서 단계" style="display:flex;align-items:center;gap:4px;flex-wrap:wrap">{out}</div>'


def line_card(mini=True, w_map=260):
    hx, hy = DESK_PX(*CELLS['허현'])
    mp = (f'<div style="position:relative;width:{w_map}px;height:112px;overflow:hidden;border:1px solid #3d4740;flex-shrink:0">'
          f'{mapimg("desk", 1048, 952, "허현 일대", -(hx - w_map // 2), -(hy - 56))}'
          f'<span aria-label="황제 — 허현" style="pointer-events:none;position:absolute;left:{w_map // 2 - 18}px;top:38px;width:36px;height:36px;display:inline-flex;align-items:center;justify-content:center;'
          f'background:rgba(12,15,14,.85);border:2px solid #d3b064">{crown(20)}</span></div>') if mini else ''
    facts = (f'<div style="display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:8px;flex:1;min-width:0">'
             f'{kv("황제", "유협")}{kv("있는 곳", "허현 · 성 안")}{kv("조정", "허현")}{kv("섭정", "—")}'
             f'{kv("조정을 지키는 세력", nat_dot("조조") + " 조조")}{kv("조정 상태", "[상태]")}</div>')
    return (f'<section class="panel" style="flex:1">{sec("황통 — 한", "활성 · 황통은 여럿일 수 있다")}'
            f'<div style="padding:10px 12px;display:flex;gap:12px;align-items:flex-start">{mp}{facts}</div></section>')


def relation_panel():
    rows = [('조조', 'COURT_GUARDIAN', '인정'), ('원소', None, '[관계]'), ('유표', None, '[관계]')]
    body = ''
    for n, r, rec in rows:
        lab = chip(*REL[r]) if r else chip('[관계]')
        body += (f'<div style="height:44px;display:flex;align-items:center;gap:8px;padding:0 12px;border-bottom:1px solid #2c342f;{"background:rgba(211,176,100,.06);" if n == "조조" else ""}">'
                 f'{nat_dot(n)}<span class="serif" style="font-weight:700;width:48px">{n}</span>{lab}<span class="t2" style="font-size:12px">{rec}</span>'
                 f'<span class="mono muted" style="font-size:11px;margin-left:auto">호의 [값]</span></div>')
    return (f'<section class="panel" style="width:440px;flex-shrink:0">{sec("세력과 황실", "한 황통 기준 · 호의 하나로 줄이지 않는다")}{body}'
            f'<span class="muted" style="font-size:11.5px;padding:8px 12px;display:block">관계는 외교 · 사건 · 황제의 이동 · 제위가 바뀔 때만 달라집니다.</span></section>')


def edict_list():
    items = [('관직 수여 — [인물]을 [관직]으로', '조정 → 조조 세력', '전달됨 · 답 기다림', 'bronze', True),
             ('정벌 명분 — [세력]', '조정 → 조조 세력', '상서 등록', '', False)]
    out = ''
    for t, ft, st, tone, sel in items:
        out += (f'<button type="button" aria-pressed="{"true" if sel else "false"}" style="min-height:64px;width:100%;display:flex;flex-direction:column;justify-content:center;gap:3px;padding:6px 12px;'
                f'background:{"rgba(211,176,100,.10)" if sel else "transparent"};box-shadow:{"inset 3px 0 0 #d3b064" if sel else "none"};border:0;border-bottom:1px solid #2c342f;color:#ece6d8;font:inherit;text-align:left;cursor:pointer">'
                f'<span class="serif" style="font-weight:700;font-size:14px">{t}</span><span style="display:flex;align-items:center;gap:6px"><span class="muted" style="font-size:11.5px">{ft}</span>{chip(st, tone)}</span></button>')
    return (f'<section class="panel" style="width:420px;flex-shrink:0">{sec("조서", "우리에게 보이는 것만")}<div role="list">{out}</div>'
            f'<span class="muted" style="font-size:11.5px;padding:8px 12px;display:block">남에게 간 조서와 밀지는 보이지 않습니다.</span></section>')


def edict_detail(lord=False):
    off = ''.join(f'<button type="button" class="btn {"off" if not lord else ""}" {"aria-disabled=\"true\" aria-haspopup=\"dialog\"" if not lord else ""}>{t}</button>'
                  for t in ['수락', '부분 수락', '지연', '거부', '공개 비난'])
    auth = why('권한 없음 — 받는 세력의 군주 조조가 답합니다') if not lord else ''
    body = (f'<div style="padding:8px 12px;display:flex;flex-direction:column;gap:8px">{step_band(5, 6)}'
            f'<div style="display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:8px">{kv("보낸 곳", "조정 — 허현")}{kv("사자", "[인물]")}{kv("전달", "200년 3월 중순")}</div>'
            f'<div class="inset" style="padding:8px 12px;min-height:64px;display:flex;flex-direction:column;gap:4px"><span class="muted" style="font-size:11px">조서 글</span>'
            f'<span class="t2" style="font-size:13px;line-height:1.6">[조서 글 — 서버가 받는 쪽에 보여 주는 글]</span></div>'
            f'<div style="display:flex;flex-direction:column;gap:4px"><span class="muted" style="font-size:11.5px">이 조서를 믿을 만한가 — 세력마다 다르게 본다</span>'
            f'<div style="display:flex;gap:6px;flex-wrap:wrap">{chip("황제의 재가", "moss")}{chip("상서 기록", "moss")}{chip("인장 진위 [값]")}{chip("사자 믿음 [값]")}{chip("강압이 드러남 [값]", "rust")}</div></div></div>'
            f'<div style="margin-top:auto;padding:10px 12px;display:flex;flex-direction:column;gap:8px;border-top:1px solid #2c342f">'
            f'{help_strip("[도움말 문장 — 주제 대기]")}<div style="display:flex;gap:6px;flex-wrap:wrap;align-items:center">{off}{auth}</div>'
            + note('지연 · 거부 · 공개 비난은 황실과의 관계에 값을 치릅니다. 조서 응답 입력은 서버 설계 대기(K8-10).') + '</div>')
    return f'<section class="panel" style="flex:1;min-width:0">{sec("관직 수여 — [인물]을 [관직]으로", "받는 쪽 조조 세력")}{body}</section>'


def regalia_panel():
    def rcard(name, kind, keep, claims, extra):
        return (f'<div class="inset" style="flex:1;min-width:0;padding:10px 12px;display:flex;flex-direction:column;gap:6px">'
                f'<span style="display:flex;align-items:center;gap:6px"><span class="serif" style="font-weight:900;font-size:15px">{name}</span>{chip(kind, "bronze")}</span>'
                f'<span class="t2" style="font-size:12px">보관 {keep}</span><div style="display:flex;gap:6px;flex-wrap:wrap">{claims}</div>'
                f'<span class="muted" style="font-size:11.5px">{extra}</span></div>')
    cards = (rcard('전국옥새', '황실 인장', '[인물] · 허현', chip('주장 [값]') + chip('다툼 [값]', 'rust'), '가졌다고 황제가 되지 않습니다.')
             + rcard('영천군 태수 관인', '관인', '하후돈 · 양적현', chip('맞음', 'moss'), '영천군 태수 자리에 [값]순까지 쓸 수 있습니다.'))
    return f'<section class="panel" style="flex:1;min-width:0">{sec("인장", "주인과 보관자는 다르다")}<div style="padding:10px 12px;display:flex;gap:10px">{cards}</div></section>'


def settlement_panel(lord=False):
    rows = [('봉대', '황제가 인사 · 경비 · 의제를 이끈다', '한실 최고 보호자 · 충성파의 지지', '불리한 인사 · 정벌 요구'),
            ('보정', '황제의 마지막 재가는 남는다', '추천권 · 조서 함께 쓰기 · 수도 방위', '두 권력이 부딪친다'),
            ('협제', '경비 · 인장 · 사자를 쥔다', '조서를 쉽게 만든다', '조서 믿음이 떨어진다 · 밀지 · 탈출')]
    cols = ''.join(f'<div class="inset" style="padding:8px 10px;display:flex;flex-direction:column;gap:4px;min-width:0">'
                   f'<span class="serif" style="font-weight:900;font-size:15px">{n}</span><span class="t2" style="font-size:11.5px;line-height:1.45">{a}</span>'
                   f'<span class="ms" style="font-size:11.5px;line-height:1.45">얻는 것 — {b}</span><span class="rs" style="font-size:11.5px;line-height:1.45">위험 — {c}</span></div>'
                   for n, a, b, c in rows)
    conds = (f'<div style="display:flex;gap:6px;flex-wrap:wrap;align-items:center"><span class="muted" style="font-size:11.5px">고를 조건</span>'
             f'{chip("조정 성에 도착", "moss")}{chip("경비 [값]")}{chip("쌀 [값]")}{chip("상서", "moss")}{chip("인장", "moss")}</div>')
    return (f'<section class="panel" style="flex:1;min-width:0">{sec("조정을 지키는 방침", "지키는 세력의 군주가 고른다 · 지금 [방침]")}'
            f'<div style="padding:10px 12px;display:flex;flex-direction:column;gap:8px"><div style="display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:8px">{cols}</div>{conds}'
            + note('방침 선택 입력은 서버 설계 대기(K8-10).') + '</div></section>')


def board_imperial():
    top = f'<div style="display:flex;gap:12px;flex-shrink:0">{line_card()}{relation_panel()}</div>'
    mid = f'<div style="display:flex;gap:12px;flex:1;min-height:0">{edict_list()}{edict_detail()}</div>'
    bot = f'<div style="display:flex;gap:12px;flex-shrink:0">{regalia_panel()}{settlement_panel()}</div>'
    body = f'<div style="flex-grow:1;display:flex;flex-direction:column;gap:12px;padding:12px;min-height:0">{top}{mid}{bot}</div>'
    desk('V31K8Imperial.dc.html', 'K8 황실 · 인장 · 조서(데스크톱)', 'court', '황실', TABS_IMP, '황실 · 조서', body, btn('도움말', '', 'help'))


def board_mimperial():
    line = (f'<section class="panel">{sec("황통 — 한", "활성")}<div style="padding:10px 12px;display:grid;grid-template-columns:1fr 1fr;gap:8px">'
            f'{kv("황제", crown(14) + " 유협")}{kv("있는 곳", "허현 · 성 안")}{kv("조정", "허현")}{kv("지키는 세력", nat_dot("조조") + " 조조")}</div>'
            f'<div style="height:44px;display:flex;align-items:center;gap:8px;padding:0 12px;border-top:1px solid #2c342f">{nat_dot("조조")}<span class="t2" style="font-size:12px">우리 세력</span>'
            f'{chip(*REL["COURT_GUARDIAN"])}<span class="mono muted" style="font-size:11px;margin-left:auto">호의 [값]</span></div></section>')
    cards = ''.join(f'<a href="#" style="min-height:64px;display:flex;align-items:center;gap:10px;padding:8px 12px;border:1px solid #3d4740;background:#141816;color:#ece6d8">'
                    f'<span style="display:flex;flex-direction:column;gap:3px;flex:1;min-width:0"><span class="serif" style="font-weight:700;font-size:14px">{t}</span>'
                    f'<span style="display:flex;gap:6px;align-items:center"><span class="muted" style="font-size:11px">{f}</span>{chip(s, tone)}</span></span>{icon("next", 16, "#8a8477")}</a>'
                    for t, f, s, tone in [('관직 수여 — [인물]을 [관직]으로', '조정 → 조조 세력', '답 기다림', 'bronze'), ('정벌 명분 — [세력]', '조정 → 조조 세력', '상서 등록', '')])
    reg = (f'<section class="panel">{sec("인장", "2")}<div style="height:52px;display:flex;align-items:center;gap:8px;padding:0 12px">'
           f'<span class="serif" style="font-weight:900">전국옥새</span>{chip("황실 인장", "bronze")}<span class="t2" style="font-size:12px;margin-left:auto">[인물] · 허현</span></div></section>')
    body = (f'<div style="padding:10px 12px;display:flex;flex-direction:column;gap:10px;overflow:hidden">{line}'
            f'<div style="display:flex;flex-direction:column;gap:8px"><span class="serif" style="font-weight:900;font-size:15px">조서</span>{cards}'
            f'<span class="muted" style="font-size:11.5px">남에게 간 조서와 밀지는 보이지 않습니다.</span></div>{reg}</div>')
    mob('V31K8MImperial.dc.html', 'K8 황실(모바일)', 'menu', '황실', '조정', TABS_IMP, '황실 · 조서', body)


def board_imperial_states():
    def box(title, sub, inner, style=''):
        return f'<section class="panel" style="{style}">{sec(title, sub)}<div style="flex-grow:1;display:flex;flex-direction:column;min-height:0;overflow:hidden">{inner}</div></section>'
    left = col(
        box('황실 없음', '지금 모든 월드의 모양', state_empty('이 천하에는 황실이 없습니다', '황제와 조정이 없는 시나리오입니다. 지도에도 황제 표식이 나오지 않습니다.'), 'flex:1'),
        box('읽기 실패', '빈 것과 다르게', state_error('황실 정보를 지금 읽을 수 없습니다', '지도의 황제 표식도 잠시 숨깁니다. 잠시 뒤 다시 해 보세요.'), 'flex:1'),
        box('공위', '황통은 있고 제위가 빔', state_empty('지금 황제가 없습니다', '제위가 비어 있습니다. 누가 오를지는 황통의 후계 규칙이 정합니다.'), 'flex:1'))
    near = (f'<div style="display:flex;flex-direction:column;gap:12px;min-height:0">{line_card()}'
            f'<div style="display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:12px;flex:1;min-height:0">'
            + box('세력과 황실', '서버 대기(A)', state_waiting('아직 없습니다', '세력과 황실의 관계는 서버가 아직 주지 않습니다.'))
            + box('조서', '서버 대기(A)', state_waiting('아직 없습니다', '조서는 서버가 아직 주지 않습니다. 준비되면 이 자리에 보입니다.'))
            + box('인장 · 방침', '서버 대기(A)', state_waiting('아직 없습니다', '인장과 조정 방침은 서버가 아직 주지 않습니다.'))
            + '</div></div>')
    body = (f'<div style="flex-grow:1;display:grid;grid-template-columns:400px minmax(0,1fr);gap:12px;padding:12px;min-height:0">{left}'
            f'<div style="display:flex;flex-direction:column;gap:8px;min-height:0"><span class="t2" style="font-size:12.5px">가까운 모양 — 황제가 있는 곳만 먼저 보인다</span>{near}</div></div>')
    desk('V31K8ImperialStates.dc.html', 'K8 황실 — 상태', 'court', '황실', TABS_IMP, '황실 · 조서', body)


# ================================================================== P-H04 천하 형세 — 통일 판정
TABS_REC = ['기록 5분류', '연감', '리플레이', '천하 형세', '시즌 결산']
ZHOU = [('사례', '司隸'), ('기주', '冀州'), ('예주', '豫州'), ('서주', '徐州'), ('연주', '兗州'), ('양주', '揚州'), ('청주', '靑州'),
        ('형주', '荊州'), ('유주', '幽州'), ('병주', '并州'), ('교주', '交州'), ('서량', '涼州'), ('익주', '益州')]
ZSTATE = {'연주': ('조조', True), '기주': ('원소', True), '예주': ('조조', False), '형주': ('유표', False)}


ZNAME = dict(zip(ZHOU, places(ZHOU)))  # 涼州는 「서량」(사용자 결정 D25) — 같은 이름이 없어져 한자를 붙이지 않는다(K3 places 는 겹칠 때만 붙인다)


def zhou_tile(n, hz, w=176, h=132, small=False):
    label = ZNAME[(n, hz)]
    who, full = ZSTATE.get(n, (None, False))
    if full:
        st = f'<span style="display:flex;align-items:center;gap:4px">{nat_dot(who)}{chip("모두 — " + who, "bronze")}</span>'
    elif who:
        st = f'<span style="display:flex;align-items:center;gap:4px">{nat_dot(who)}<span class="t2" style="font-size:11.5px">{who} [값] · 남은 군국 [값]</span></span>'
    else:
        st = '<span class="muted" style="font-size:11.5px">모두 쥔 세력 없음</span>'
    if small:
        return (f'<a href="#" style="min-height:52px;display:flex;flex-direction:column;justify-content:center;gap:2px;padding:4px 8px;border:1px solid {"#9c7f3f" if full else "#3d4740"};background:#141816;color:#ece6d8">'
                f'<span class="serif" style="font-weight:900;font-size:13px">{label}</span>{chip("모두 " + who, "bronze") if full else "<span class=muted style=font-size:10.5px>[값] / [값]</span>"}</a>')
    return (f'<a href="#" style="width:{w}px;height:{h}px;display:flex;flex-direction:column;gap:6px;padding:10px 12px;border:1px solid {"#9c7f3f" if full else "#3d4740"};'
            f'background:{"rgba(211,176,100,.06)" if full else "#141816"};color:#ece6d8">'
            f'<span class="serif" style="font-weight:900;font-size:17px">{label}</span><span class="mono muted" style="font-size:11px">군국 [값]</span>{st}'
            f'<span class="muted" style="font-size:11px;margin-top:auto">누르면 군국별로</span></a>')


def board_unification():
    tiles = ''.join(zhou_tile(n, hz) for n, hz in ZHOU)
    legend = (f'<div style="width:176px;height:132px;display:flex;flex-direction:column;gap:6px;padding:10px 12px;border:1px dashed #3d4740">'
              f'<span class="muted" style="font-size:11.5px">190년 한의 13주 · 군국만 셉니다. 군국 밖 거점은 세지 않습니다.</span></div>')
    grid = f'<div style="display:grid;grid-template-columns:repeat(4,176px);gap:12px;padding:12px">{tiles}{legend}</div>'
    left = (f'<section class="panel">{sec("13주", "주마다 모두 쥔 세력 · 계산 [값]")}{grid}'
            f'<div style="margin-top:auto;padding:10px 12px;display:flex;gap:8px;border-top:1px solid #2c342f">{btn("지도에서 보기 — 주 경계", "", "war", href="#")}</div></section>')
    cond = (f'<section class="panel">{sec("통일 조건", "둘 다 이루면 시즌이 끝난다")}'
            f'<div style="padding:10px 12px;display:flex;flex-direction:column;gap:8px">'
            f'<div class="inset" style="padding:10px 12px;display:flex;flex-direction:column;gap:4px"><span class="serif" style="font-weight:900;font-size:15px">① 13주 · 군국을 모두 쥔다</span>'
            f'<span class="t2" style="font-size:12px;line-height:1.5">「쥔다」의 뜻 — [미정]</span></div>'
            f'<div class="inset" style="padding:10px 12px;display:flex;flex-direction:column;gap:4px"><span style="display:flex;align-items:center;gap:6px"><span class="serif" style="font-weight:900;font-size:15px">② 칭제</span>{crown(16)}</span>'
            f'<span class="t2" style="font-size:12px;line-height:1.5">칭제 규칙은 아직 없습니다.</span>{chip("서버 설계 대기", "info")}</div></div></section>')
    facs = [('조조', True, '[값] / [값]', '연주'), ('원소', False, '[값] / [값]', '기주'), ('유표', False, '[값] / [값]', '—')]
    rows = ''.join(f'<div style="height:52px;display:grid;grid-template-columns:24px 90px minmax(0,1fr) 110px 56px;gap:8px;align-items:center;padding:0 12px;border-bottom:1px solid #2c342f;'
                   f'{"background:rgba(211,176,100,.08);box-shadow:inset 3px 0 0 #d3b064;" if me else ""}"><span class="mono muted">{i}</span>'
                   f'<span style="display:flex;align-items:center;gap:6px">{nat_dot(n)}<span class="serif" style="font-weight:700;font-size:15px">{n}</span></span>'
                   f'<span class="mono" style="font-size:12px">군국 {c}</span><span class="t2" style="font-size:12px">모두 쥔 주 {z}</span><span class="muted" style="font-size:12px">칭제 —</span></div>'
                   for i, (n, me, c, z) in enumerate(facs, 1))
    rows += (f'<div style="height:52px;display:flex;align-items:center;gap:8px;padding:0 12px"><span class="muted" style="width:24px"></span>{nat_dot("무주")}'
             f'<span class="muted" style="font-size:12.5px">중립 현 · 호족 — 군국 [값]</span><span class="muted" style="font-size:11.5px;margin-left:auto">쥘 수 있다</span></div>')
    rank = f'<section class="panel">{sec("세력", "공개 범위 [미정]")}{rows}</section>'
    mine = (f'<section class="panel" style="flex:1">{sec("내 몫", "하후돈 · 조조 소속")}'
            f'<div style="padding:10px 12px;display:grid;grid-template-columns:44px minmax(0,1fr);gap:12px">{portrait("hahoudon", "하후돈", 44, 62)}'
            f'<div style="display:flex;flex-direction:column;gap:0">{mod("우리 세력이 쥔 군국", "[값]", "bz")}{mod("모두 쥔 주", "연주", "ms")}'
            f'{mod("내가 태수인 군", "영천군 — 실권 있음", "ms")}{mod("예주에서 남은 군국", "[값]", "rs")}</div></div></section>')
    body = grid2(560, left, col(cond, rank, mine))
    desk('V31K8Unification.dc.html', 'K8 천하 형세 — 통일 판정(데스크톱)', 'records', '기록', TABS_REC, '천하 형세', body)


def board_munification():
    cond = (f'<section class="panel">{sec("통일 조건", "둘 다")}<div style="padding:8px 12px;display:flex;flex-direction:column;gap:6px">'
            f'<span style="font-size:13px"><b class="serif">① 13주 · 군국을 모두 쥔다</b> <span class="muted" style="font-size:11.5px">— 뜻 [미정]</span></span>'
            f'<span style="font-size:13px;display:flex;align-items:center;gap:6px"><b class="serif">② 칭제</b>{chip("서버 설계 대기", "info")}</span></div></section>')
    facs = ''.join(f'<div style="height:52px;display:flex;align-items:center;gap:8px;padding:0 12px;border-bottom:1px solid #2c342f;{"background:rgba(211,176,100,.08);" if n == "조조" else ""}">'
                   f'{nat_dot(n)}<span class="serif" style="font-weight:700">{n}</span><span class="mono t2" style="font-size:12px">군국 [값] / [값]</span>'
                   f'<span class="muted" style="font-size:11.5px;margin-left:auto">모두 쥔 주 {z}</span></div>' for n, z in [('조조', '연주'), ('원소', '기주'), ('유표', '—')])
    grid = ''.join(zhou_tile(n, hz, small=True) for n, hz in ZHOU)
    body = (f'<div style="padding:10px 12px;display:flex;flex-direction:column;gap:10px;overflow:hidden">{cond}'
            f'<section class="panel">{sec("세력", "")}{facs}</section>'
            f'<div style="display:flex;flex-direction:column;gap:6px"><span class="serif" style="font-weight:900;font-size:15px">13주</span>'
            f'<div style="display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:6px">{grid}</div></div></div>')
    mob('V31K8MUnification.dc.html', 'K8 천하 형세(모바일)', 'records', '천하 형세', '기록', None, None, body)


# ================================================================== P-K10 세력 — 요약 · 정체성 · 편제 전통
TABS_REALM = ['요약 · 현 목록', '정체성', '제도', '편제 전통']
AUD = ['한 조정', '지방 명사', '백성', '신도', '군사 추종자', '변경 공동체']
PRESETS = [('덕가', 'G'), ('도가', 'G'), ('도적', 'P'), ('명가', 'G'), ('묵가', 'G'), ('법가', 'G'), ('병가', 'G'), ('불가', 'G'),
           ('오두미도', 'P'), ('유가', 'G'), ('음양가', 'G'), ('종횡가', 'G'), ('태평도', 'P'), ('중립', 'S'), ('없음', 'S')]
RES_KO = {'MONEY': '금', 'GRAIN': '쌀', 'IRON': '철', 'TIMBER': '목재', 'HORSES': '말'}
# (이름, 유일/공용, 요구 인물, 요구 지역, 비용 색) — data/curated/han/named-unit-traditions.json
UNITS = [('백마의종', 'UNIQUE', '공손찬', '유주', ['GRAIN', 'HORSES']), ('호표기', 'UNIQUE', '조순', '—', ['GRAIN', 'IRON', 'HORSES']),
         ('함진영', 'UNIQUE', '고순', '—', ['GRAIN', 'IRON']), ('청주병', 'UNIQUE', '조조', '청주', ['GRAIN']),
         ('대극사', 'UNIQUE', '원소', '—', ['GRAIN', 'IRON']), ('해번병', 'UNIQUE', '한당', '—', ['GRAIN', 'IRON']),
         ('유주돌기', 'COMMON', '—', '유주', ['GRAIN', 'HORSES']), ('단양병', 'COMMON', '—', '단양', ['GRAIN', 'IRON']),
         ('적갑군', 'COMMON', '—', '부릉군', ['GRAIN', 'IRON']), ('연노사', 'COMMON', '제갈량', '부릉군', ['GRAIN', 'IRON', 'TIMBER'])]


def realm_band():
    return (f'<div style="height:84px;flex-shrink:0;display:flex;align-items:center;gap:16px;padding:0 16px;border-bottom:1px solid #2c342f;background:#141816">'
            f'<span aria-hidden="true" style="width:36px;height:48px;background:{NATION["조조"]};border:1px solid #9c7f3f;flex-shrink:0"></span>'
            f'<span style="display:flex;flex-direction:column;gap:2px"><span class="serif" style="font-weight:900;font-size:20px">조조</span><span class="muted" style="font-size:11.5px">군주 조조 · 수도 [성]</span></span>'
            f'<div style="display:flex;gap:8px;margin-left:12px">{kv("다스리는 현", "[값]")}{kv("소속 인물", "[값]")}{kv("호구", "[값]")}{kv("병력", "[값]")}'
            f'{kv("수도 창고", res("금", "[값]") + " " + res("쌀", "[값]"))}</div>'
            f'<a href="#" class="btn sm" style="margin-left:auto">{icon("territory", 16)}창고망 보기</a></div>')


def board_realm():
    aud = ''.join(f'<div style="height:40px;display:flex;align-items:center;gap:8px;padding:0 12px;border-bottom:1px solid #1f2522">'
                  f'<span class="t2" style="font-size:12.5px;width:110px">{a}</span><span class="mono" style="font-size:12px">[값]</span></div>' for a in AUD)
    ident = (f'<section class="panel" style="flex:1">{sec("정체성", "세력의 성격 · 행동으로 바뀐다")}'
             f'<div style="padding:12px;display:flex;gap:12px;align-items:center;border-bottom:1px solid #2c342f">'
             f'<span class="serif" style="font-weight:900;font-size:24px">유가</span>{game_term()}{chip("영토 정권", "bronze")}'
             f'<span class="muted" style="font-size:12px;margin-left:auto">조직망 자리 — 학교</span></div>'
             f'<div style="display:grid;grid-template-columns:minmax(0,1fr) minmax(0,1fr);gap:0;flex:1;min-height:0">'
             f'<div style="border-right:1px solid #2c342f"><div style="padding:8px 12px" class="muted"><span style="font-size:11.5px">누구에게 정당한가</span></div>{aud}</div>'
             f'<div style="display:flex;flex-direction:column"><div style="padding:8px 12px" class="muted"><span style="font-size:11.5px">조직망 — 성과 길에 남는다</span></div>'
             + tbl(['종류', '곳', '드러남', '세기'], [['학교', '[현]', chip('공개'), '[값]'], ['학교', '[현]', chip('공개'), '[값]']], 'font-size:12px')
             + f'<div style="padding:10px 12px 0" class="muted"><span style="font-size:11.5px">제도 긴장</span></div>'
             f'<div style="margin:6px 12px;border:1px dashed #3d4740;display:flex;min-height:120px">{state_empty("긴장이 없습니다", "정체성을 바꾸면 밀려난 전통이 여기 남습니다.", pad=8)}</div></div></div>'
             f'<div style="padding:10px 12px;border-top:1px solid #2c342f">' + note('정체성은 결정과 행동으로 천천히 바뀝니다. 바꾸는 결정 입력은 서버 설계 대기(C6).') + '</div></section>')
    plist = ''.join(opt(n, '', game_term() if g == 'G' else (chip('상태') if g == 'S' else ''), sel=(n == '유가'), h=44) for n, g in PRESETS)
    cat = f'<section class="panel">{sec("정체성 15", "게임 용어는 사료에 없는 게임의 말")}<div role="listbox" aria-label="정체성" style="display:flex;flex-direction:column">{plist}</div></section>'
    body = realm_band() + grid2(340, ident, cat)
    desk('V31K8Realm.dc.html', 'K8 세력 — 요약 · 정체성(데스크톱)', 'court', '세력', TABS_REALM, '정체성', body)


def board_realm_units():
    def costs(cs):
        return ' '.join(res(RES_KO[c]) for c in cs)
    rows = []
    for n, a, g, r, cs in UNITS:
        rows.append([f'<span class="serif" style="font-weight:700">{n}</span>', chip('유일', 'bronze') if a == 'UNIQUE' else chip('공용'),
                     g, r, costs(cs), '[인물]' if a == 'UNIQUE' else '—', why_tag('[서버 사유]') if n != '호표기' else ok_chip('편성할 수 있음')])
    table = tbl(['부대', '', '요구 인물', '요구 지역', '비용', '지금 가진 장수', '우리'], rows, 'font-size:12.5px')
    left = f'<section class="panel" style="flex:1">{sec("편제 전통 — 실명 · 지역 부대", "10장 · 유일은 서버에 한 장")}<div style="padding:0 12px">{table}</div></section>'
    det = (f'<section class="panel" style="flex:1">{sec("호표기", "유일 · 실명 부대")}'
           f'<div style="padding:10px 12px;display:flex;flex-direction:column;gap:8px">'
           f'<div style="display:grid;grid-template-columns:1fr 1fr;gap:8px">{kv("요구 인물", "조순")}{kv("요구 지역", "—")}{kv("명망 코스트", "[값]")}{kv("비용", costs(["GRAIN", "IRON", "HORSES"]))}</div>'
           f'<span class="muted" style="font-size:11.5px">편성 판정 — 요구 인물 → 요구 지역 → 서버에 한 장 → 명망 여유</span>'
           f'<div style="display:flex;flex-direction:column">{mod("요구 인물", "조순 — 우리 부에 있다", "ms")}{mod("서버에 한 장", "아직 아무도 없다", "ms")}{mod("명망 여유", "[값]", "t2")}</div>'
           f'{btn("부 편성에서 편성하기", "primary", "retinue", href="#")}</div></section>')
    inst = (f'<section class="panel" style="flex:1">{sec("제도", "서버 설계 대기(C6)")}'
            f'<div style="flex-grow:1;display:flex;min-height:0">{state_waiting("제도 확산이 아직 없습니다", "제도를 세력이 정하고, 담당관이 시범 군현에서 시행하고, 넓혀 가는 흐름이 여기 보입니다. 서버가 아직 주지 않습니다.")}</div></section>')
    body = realm_band() + grid2(420, left, col(det, inst))
    desk('V31K8RealmUnits.dc.html', 'K8 세력 — 편제 전통(데스크톱)', 'court', '세력', TABS_REALM, '편제 전통', body)


def board_mrealm():
    aud = ''.join(f'<div style="height:40px;display:flex;align-items:center;justify-content:space-between;padding:0 12px;border-bottom:1px solid #1f2522">'
                  f'<span class="t2" style="font-size:12.5px">{a}</span><span class="mono" style="font-size:12px">[값]</span></div>' for a in AUD)
    body = (f'<div style="padding:10px 12px;display:flex;flex-direction:column;gap:10px;overflow:hidden">'
            f'<section class="panel"><div style="min-height:64px;display:flex;align-items:center;gap:10px;padding:8px 12px">'
            f'<span aria-hidden="true" style="width:28px;height:38px;background:{NATION["조조"]};border:1px solid #9c7f3f"></span>'
            f'<span style="display:flex;flex-direction:column"><span class="serif" style="font-weight:900;font-size:18px">조조</span><span class="muted" style="font-size:11px">수도 [성] · 현 [값] · 인물 [값]</span></span></div></section>'
            f'<section class="panel">{sec("정체성", "")}<div style="padding:10px 12px;display:flex;gap:8px;align-items:center">'
            f'<span class="serif" style="font-weight:900;font-size:20px">유가</span>{game_term()}{chip("영토 정권", "bronze")}</div>'
            f'<div style="padding:0 12px 6px" class="muted"><span style="font-size:11.5px">누구에게 정당한가</span></div>{aud}</section></div>')
    mob('V31K8MRealm.dc.html', 'K8 세력(모바일)', 'menu', '세력', '조정', TABS_REALM, '정체성', body)


# ================================================================== P-K08 주변 세계 — 외교의 탭(K6 페이지)
TABS_DIP = ['세력 외교', '외교 서신', '주변 세계']
XREL = {'HOSTILE': ('적대', 'rust'), 'TRIBUTARY': ('조공', 'bronze'), 'SUBMITTED': ('내속', 'moss'), 'TRADE': ('교역', 'info'), 'NEUTRAL': ('관계 없음', '')}


def board_frontier():
    empty = (f'<section class="panel" style="height:170px">{sec("우리 세력", "조조 소속")}'
             f'<div style="flex-grow:1;display:flex">{state_empty("접경한 주변 세계가 없습니다", "주변 세계는 변경 현과 맞닿은 세력에만 나옵니다.", pad=8)}</div></section>')
    acts = [('HOSTILE', '[현] · [현]', '변경 침입 — [값]순 전'), ('TRADE', '[현]', '교역 — [값]순 전'), ('TRIBUTARY', '[현]', '조공 — 지난달'), ('NEUTRAL', '[현]', '—')]
    cards = ''.join(f'<button type="button" aria-pressed="{"true" if i == 0 else "false"}" style="min-height:96px;display:flex;flex-direction:column;gap:6px;padding:10px 12px;text-align:left;'
                    f'background:{"rgba(211,176,100,.08)" if i == 0 else "#141816"};border:1px solid {"#d3b064" if i == 0 else "#3d4740"};color:#ece6d8;font:inherit;cursor:pointer">'
                    f'<span style="display:flex;align-items:center;gap:8px"><span class="serif" style="font-weight:900;font-size:17px">[행위자]</span>{chip(*XREL[r])}</span>'
                    f'<span class="t2" style="font-size:12px">맞닿은 현 {b}</span><span class="muted" style="font-size:11.5px">{e}</span></button>'
                    for i, (r, b, e) in enumerate(acts))
    grid = (f'<section class="panel" style="flex:1">{sec("변경에 닿은 세력일 때", "지도 밖 집단 · 세력이 아니다")}'
            f'<div style="padding:12px;display:grid;grid-template-columns:1fr 1fr;gap:12px">{cards}</div>'
            f'<div style="padding:0 12px 12px;display:flex;gap:6px;flex-wrap:wrap;align-items:center"><span class="muted" style="font-size:11.5px">관계</span>'
            + ''.join(chip(*v) for v in XREL.values()) + '</div></section>')
    det = (f'<section class="panel" style="flex:1">{sec("[행위자]", "적대")}'
           + warnbar('지난 [값]순 전 변경 침입이 있었습니다 — [현]의 창고와 민심이 줄었습니다.')
           + f'<div style="padding:10px 12px;display:flex;flex-direction:column;gap:10px">'
           f'<div style="display:grid;grid-template-columns:1fr 1fr;gap:8px">{kv("우리와의 관계", "적대", "rs")}{kv("맞닿은 현", "[현] · [현]")}</div>'
           f'<span class="muted" style="font-size:11.5px">일어난 일</span>'
           + tbl(['때', '일', '곳', '결과'], [['200년 [값]월 [값]순', '변경 침입', '[현]', '<span class="rs">창고 ▼ 민심 ▼</span>'], ['199년 [값]월 [값]순', '교역', '[현]', '<span class="ms">금 ▲</span>']], 'font-size:12px')
           + f'{btn("기록에서 모두 보기", "", "records", href="#")}'
           f'<div class="inset" style="padding:10px 12px;display:flex;flex-direction:column;gap:4px"><span class="t2" style="font-size:12.5px">사자를 보내 조공 · 교역을 청할 수 있게 됩니다.</span>'
           f'<span class="muted" style="font-size:11.5px">사자 배치 · 제의 입력은 서버 설계 대기(K8-09).</span></div></div></section>')
    body = grid2(460, col(empty, grid), det)
    desk('V31K8Frontier.dc.html', 'K8 주변 세계 — 외교의 탭(데스크톱)', 'court', '외교', TABS_DIP, '주변 세계', body)


def board_mfrontier():
    cards = ''.join(f'<a href="#" style="min-height:72px;display:flex;align-items:center;gap:10px;padding:8px 12px;border:1px solid #3d4740;background:#141816;color:#ece6d8">'
                    f'<span style="display:flex;flex-direction:column;gap:3px;flex:1"><span style="display:flex;gap:6px;align-items:center"><span class="serif" style="font-weight:900;font-size:16px">[행위자]</span>{chip(*XREL[r])}</span>'
                    f'<span class="muted" style="font-size:11.5px">{e}</span></span>{icon("next", 16, "#8a8477")}</a>'
                    for r, e in [('HOSTILE', '변경 침입 — [값]순 전'), ('TRADE', '교역 — [값]순 전'), ('TRIBUTARY', '조공 — 지난달')])
    body = (f'<div style="padding:10px 12px;display:flex;flex-direction:column;gap:8px;overflow:hidden">'
            f'<div style="border:1px dashed #3d4740;height:150px;display:flex">{state_empty("접경한 주변 세계가 없습니다", "변경 현과 맞닿은 세력에만 나옵니다.", pad=8)}</div>'
            f'<span class="t2" style="font-size:12px">변경에 닿은 세력일 때</span>{cards}</div>')
    mob('V31K8MFrontier.dc.html', 'K8 주변 세계(모바일)', 'menu', '외교', '조정', TABS_DIP, '주변 세계', body)


# ================================================================== P-K06 역정보 — 계책 묶음
TABS_ST = ['계책 덱', '역정보']


def board_misinfo():
    # 상대는 세력이 아니라 장수다(서버 victimGeneralId), 소속 세력은 둘째 줄(D35, 2026-10-02).
    victim = ('<span style="display:flex;flex-direction:column;gap:1px"><span class="serif" style="font-weight:700">[인물]</span>'
              '<span class="muted" style="font-size:11px">[세력] 소속</span></span>')
    rows = [[victim, '하남윤', '신정현 구역', '<span class="mono">[값]순</span>', chip('보이는 중', 'moss')],
            [victim, '[군국]', '[구역]', '—', chip('사라짐 — 상대가 다시 첩보')],
            [victim, '[군국]', '[구역]', '—', chip('끝남')]]
    left = (f'<section class="panel" style="flex:1">{sec("내가 건 역정보", "나에게만 보인다")}<div style="padding:0 12px">'
            + tbl(['상대 장수', '군국', '가짜 군세가 보이는 곳', '남은 순', '상태'], rows, 'font-size:12.5px') + '</div>'
            f'</section><section class="panel" style="height:230px">{sec("건 것이 없을 때", "빈 상태")}'
            f'<div style="flex-grow:1;display:flex">{state_empty("건 역정보가 없습니다", "계책 덱의 의병 · 반간 카드로 겁니다.", btn("계책 덱으로", "sm", href="#"), pad=8)}</div></section>')
    sx, sy = DESK_PX(*CELLS['신정현'])
    mp = (f'<div style="position:relative;height:220px;overflow:hidden;border:1px solid #3d4740">{mapimg("desk", 1048, 952, "신정현 일대", -(sx - 200), -(sy - 110))}'
          f'<span aria-label="가짜 군세 — 나에게만" style="pointer-events:none;position:absolute;left:178px;top:88px;width:44px;height:44px;border-radius:50%;border:2px dashed #d3b064;background:rgba(12,15,14,.7);'
          f'display:inline-flex;align-items:center;justify-content:center;font-family:\'Noto Serif KR\',serif;font-weight:900;color:#d3b064">가</span>'
          f'<span class="mlab" style="left:200px;top:136px">가짜 군세 · 나에게만</span></div>')
    art = pic(ART.get('의병', ''), 96, 104, '의병 카드 그림')
    det = (f'<section class="panel" style="flex:1">{sec("의병 — 가짜 군세", "진행 중")}'
           f'<div style="padding:10px 12px;display:flex;flex-direction:column;gap:10px">{mp}'
           f'<div style="display:flex;gap:12px">{art}<div style="display:flex;flex-direction:column;gap:0;flex:1">'
           f'{mod("상대 장수", "[인물] · [세력] 소속")}{mod("보이는 곳", "신정현 구역 · 하남윤")}{mod("남은 순", "[값]")}{mod("들킬 수 있다", "매 순 [값]", "rs")}</div></div>'
           f'<div class="inset" style="padding:10px 12px;display:flex;flex-direction:column;gap:4px"><span class="t2" style="font-size:12.5px">상대는 이것이 가짜인 줄 모릅니다. 상대가 그 군국을 다시 첩보하면 사라집니다.</span>'
           f'<span class="muted" style="font-size:11.5px">가짜 군세는 싸움 · 보급 길에 끼지 않습니다.</span></div></div></section>')
    body = grid2(460, col(left), det)
    desk('V31K8Misinfo.dc.html', 'K8 역정보 — 계책 묶음(데스크톱)', 'stratagem', '계책', TABS_ST, '역정보', body)


def board_mmisinfo():
    cards = ''.join(f'<a href="#" style="min-height:64px;display:flex;align-items:center;gap:10px;padding:8px 12px;border:1px solid #3d4740;background:#141816;color:#ece6d8">'
                    f'<span style="display:flex;flex-direction:column;gap:3px;flex:1"><span class="serif" style="font-weight:700;font-size:14px">{a} · {b}</span>'
                    f'<span style="display:flex;gap:6px;align-items:center"><span class="muted" style="font-size:11.5px">{c}</span>{chip(s, t)}</span></span>{icon("next", 16, "#8a8477")}</a>'
                    for a, b, c, s, t in [('[인물]', '하남윤', '[세력] 소속 · 남은 [값]순', '보이는 중', 'moss'), ('[인물]', '[군국]', '[세력] 소속', '끝남', '')])
    body = (f'<div style="padding:10px 12px;display:flex;flex-direction:column;gap:8px;overflow:hidden">'
            f'<span class="t2" style="font-size:12px">내가 건 역정보 — 나에게만 보인다</span>{cards}'
            f'<div class="inset" style="padding:10px 12px"><span class="t2" style="font-size:12.5px;line-height:1.5">상대는 가짜인 줄 모릅니다. 상대가 다시 첩보하면 사라집니다.</span></div>'
            f'{btn("계책 덱에서 새로 걸기", "primary", "stratagem", href="#")}</div>')
    mob('V31K8MMisinfo.dc.html', 'K8 역정보(모바일)', 'stratagem', '역정보', '계책', TABS_ST, '역정보', body)


# ================================================================== 실행
BOARDS = [board_offices, board_offices_lord, board_moffices, board_offices_states,
          board_offices_claims, board_offices_central, board_moffices_claims,  # K8-05 새 보드 초안(2026-10-01, 사용자 확인 대기)
          board_vassals, board_mvassal_found, board_mvassal_side,
          board_imperial, board_mimperial, board_imperial_states, board_unification, board_munification,
          board_realm, board_realm_units, board_mrealm, board_frontier, board_mfrontier, board_misinfo, board_mmisinfo]

if __name__ == '__main__':
    for f in glob.glob(os.path.join(P, 'V31K8*.dc.html')):
        os.remove(f)
    for b in BOARDS:
        b()
    print(f'ok boards_v31_k8 — {len(BOARDS)} boards' + ('' if HAVE_ASSETS else ' (v31assets 없음: 그림 자리 표시)'))
