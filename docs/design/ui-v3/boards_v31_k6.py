# 캔버스 v3.1 · K6 입력 화면 — 명령 흐름 · 계책 · 군단 · 전투 · 시야 · 외교 · 서신.
# 설계서: 메타 reports/opensamguk/tasks/2026-09-30-k6-design-spec.md (§2 부품 · §3 페이지 · §4 입력 도달 경로 · §7 보드 계획)
# 부품은 v31system(K3) 것만 쓴다. v31system.py · names.py · v31assets.py 는 고치지 않는다(K3 · K0 소유).
#   PYTHONPATH=<v31assets 가 있는 폴더> python3 boards_v31_k6.py  → project/V31K6*.dc.html
# 예시 상황은 기존 보드와 같다: 하후돈 · 조조 소속 · 200년 3월 중순 · 영천 · 양적 · 장사. 수치는 지어내지 않고 [미정] · [값].
# 전투 판(P-C03 · C05)의 그림 방식(아이소 · 탑다운)은 사용자 답 대기(K0) — 판 자리는 자리 표시로 둔다.
import glob
import os

from v31system import *  # noqa: F401,F403

W, H = 1440, 1000
MW, MH = 390, 844
BODY_H = 952          # 데스크톱 머리줄 48 아래
CONTENT_W = 1384      # 레일 56 옆
MAIN_M = 724          # 모바일 머리줄 56 · 하단 탭 64 사이


def mmain(inner, bg='#0c0f0e', h=MAIN_M):
    return f'<main style="position:relative;width:390px;height:{h}px;flex-shrink:0;overflow:hidden;background:{bg}">{inner}</main>'


def inset(inner, style=''):
    return f'<div class="inset" style="padding:8px 10px;display:flex;flex-direction:column;gap:4px;{style}">{inner}</div>'


def warnbox(t, style=''):
    return (f'<div style="border:1px solid #c96b5d;background:rgba(201,107,93,.12);padding:8px 10px;font-size:12.5px;line-height:1.45;display:flex;gap:8px;align-items:flex-start;{style}" class="rs">'
            f'{icon("alert", 16, "#e08a7c")}<span>{t}</span></div>')


def infobox(t, style=''):
    return (f'<div style="border:1px solid #4b6d87;background:rgba(122,167,199,.10);padding:8px 10px;font-size:12.5px;line-height:1.45;display:flex;gap:8px;align-items:flex-start;{style}">'
            f'{icon("clock", 16, "#7aa7c7")}<span class="t2">{t}</span></div>')


def boardnote(t):
    """설계 주석(제품 화면에는 없음) — 보드 오른쪽 아래 작은 쪽지."""
    return f'<span class="note" style="font-size:11px;color:#8a8477">설계 주석 — {t}</span>'


def fieldrow(label, value, sub='', cls=''):
    return (f'<div style="display:flex;justify-content:space-between;align-items:center;gap:8px;min-height:32px;border-bottom:1px solid #2c342f;font-size:12.5px">'
            f'<span class="t2">{label}</span><span class="{cls}" style="text-align:right">{value}{f" <span class=muted>{sub}</span>" if sub else ""}</span></div>')


def resrow(items):
    return '<div style="display:flex;gap:10px;flex-wrap:wrap">' + ''.join(res(n, v) for n, v in items) + '</div>'


def qty(value='[값]', mx='[값]'):
    """수량 칸 + 빠른 조절 단추(옛 SelectAmountField 개념). 모두 44."""
    bb = ''.join(f'<button type="button" class="btn sm" style="min-width:52px;padding:0 8px">{t}</button>' for t in ['−100', '−10', '+10', '+100', '최대'])
    return field('수량', inp(value, unit=f'최대 {mx}'), '') + f'<div style="display:flex;gap:4px;margin-top:-4px">{bb}</div>'


# ================================================================== 명령 흐름 공용 조각
FLOW_W, LIST_W, ARGS_W = 576, 240, 336


def flow_head(sub='하후돈 · 양적현'):
    return (f'<div style="height:52px;flex-shrink:0;display:flex;align-items:center;gap:10px;padding:0 4px 0 16px;border-bottom:1px solid #3d4740;'
            f'background:linear-gradient(180deg,#232a26,#1b201d)">{portrait("hahoudon", "하후돈", 26, 36)}'
            f'<span class="serif" style="font-size:18px;font-weight:900">이번 순에 할 일</span><span class="muted" style="font-size:12px">{sub}</span>'
            f'<button type="button" class="ibtn" aria-label="닫고 12순으로(Esc)" style="margin-left:auto;border:0;background:transparent">{icon("close")}</button></div>')


def flow_strip(cur):
    return f'<div style="flex-shrink:0;padding:10px 16px;border-bottom:1px solid #2c342f">{turn_strip(cur, 12, 6, 4)}</div>'


def flow_cats(on, drafts=()):
    return f'<div style="flex-shrink:0;padding:8px 16px;border-bottom:1px solid #2c342f">{cat_tabs(on, drafts)}</div>'


def flow_list(rows, foot='', label='명령'):
    return (f'<div style="width:{LIST_W}px;flex-shrink:0;display:flex;flex-direction:column;border-right:1px solid #2c342f;min-height:0">'
            f'<div style="padding:8px">{search("명령 찾기 · 초성 · 옛 이름")}</div>'
            f'<div role="listbox" aria-label="{label}" style="display:flex;flex-direction:column;border-top:1px solid #2c342f">{rows}</div>'
            f'<div style="margin-top:auto;padding:8px 10px">{foot}</div></div>')


def flow_args(inner):
    return f'<div style="width:{ARGS_W}px;flex-shrink:0;display:flex;flex-direction:column;padding:12px 16px;gap:10px;overflow:hidden;min-height:0">{inner}</div>'


def args_head(name, slot, when):
    return (f'<div style="display:flex;align-items:center;gap:8px"><span class="serif" style="font-size:22px;font-weight:900">{name}</span>'
            f'{chip(slot, "bronze")}<span class="mono muted" style="font-size:11.5px">{when}</span></div>')


def flow(cur, cat_on, drafts, rows, args, list_foot='', band=''):
    return (f'<aside aria-label="이번 순에 할 일" style="width:{FLOW_W}px;flex-shrink:0;display:flex;flex-direction:column;background:#1b201d;border-left:1px solid #9c7f3f;min-height:0">'
            f'{flow_head()}{flow_strip(cur)}{band}{flow_cats(cat_on, drafts)}<div style="flex-grow:1;display:flex;min-height:0">{flow_list(rows, list_foot)}{flow_args(args)}</div></aside>')


# ------------------------------------------------------------------ 1. V31K6Command — 출병 · 지도 대상 고르기(구역) · 부대 고르기
def board_command():
    OX = 100
    MAPW = CONTENT_W - FLOW_W  # 808
    hx, hy = DESK_PX(*CELLS[HERE]); hx -= OX
    pick = '번창현'
    px, py = DESK_PX(*CELLS[pick]); px -= OX
    marks = ''
    for n, sub, st, r in CANDS:
        x, y = DESK_PX(*CELLS[n])
        if x - OX < 20 or x - OX > MAPW - 20:
            continue
        marks += mk(x - OX, y, 'sel' if n == pick else st, n, 'no' if st == 'no' else '')
    mapst = (f'<main aria-label="지도 — 출병 목적지 고르는 중" style="position:relative;width:{MAPW}px;flex-shrink:0;overflow:hidden;background:#0c0f0e">'
             f'{mapimg("desk", 1048, 952, "영천 일대 지도 — 현 보기", -OX, 0)}<div class="dim"></div>'
             f'{path_line(hx, hy, px, py, MAPW, 952, dist(pick) + " · [미정]순")}{marks}{me_marker(hx, hy - 22, "in", tag=False)}'
             f'{pick_bar("출병 목적지 고르기 — 04순", "지도를 누르거나 오른쪽 목록에서 · 가능 7 · 불가 3 · 불가도 누르면 이유")}{view_bar()}</main>')
    rows = (cmd_row('징병', '성 안의 호구로 병사를 모은다', 'ok', input_id='action.conscript')
            + cmd_row('훈련', '내 부대의 훈련을 올린다', 'ok', input_id='action.train')
            + cmd_row('출병', '부대를 이끌고 나간다', 'ok', sel=True, draft=True, input_id='action.deploy')
            + cmd_row('집합', '흩어진 부곡을 지금 구역으로', 'ok', input_id='action.muster')
            + cmd_row('첩보', '이웃 군을 살핀다', 'ok', input_id='action.scout')
            + cmd_row('강공', '포위 중인 성을 친다', 'no', '포위 중 아님', input_id='action.assault')
            + cmd_row('항복 권고', '포위 중인 성에 권한다', 'no', '포위 중 아님', input_id='action.demandSurrender'))
    foot = boardnote('군사 11개 중 7개. 불가는 행 전체가 사유 단추다.')
    bug = (f'<div role="group" aria-label="출병할 부대" style="display:flex;flex-direction:column;border:1px solid #2c342f">'
           f'{checkbox("하후돈 부곡 · 보병 · 병력 [값]", True)}'
           f'<div style="display:flex;align-items:center;justify-content:space-between;gap:8px;padding-right:4px">{checkbox("허저 호위 부곡 · 병력 [값]", True)}</div>'
           f'<button type="button" class="opt" aria-disabled="true" aria-haspopup="dialog" style="min-height:44px"><span class="sub" style="font-size:12.5px">청주병 · 지휘 인물 없음</span><span class="end">{why_tag("지휘 없음")}</span></button></div>')
    args = (args_head('출병', '04순', '4월 중순 00:40') + help_strip('부대를 이끌고 목적지로 나갑니다.')
            + field('부대', bug)
            + target_field('목적지(⟦PROV⟧)', pick, '영천군 · ' + dist(pick) + ' · [미정]순')
            + inset('<span class="muted" style="font-size:11px">미리 보기 — 서버 값</span>'
                    + fieldrow('걸리는 순', '[미정]') + fieldrow('휴대 쌀', '[미정]') + fieldrow('지금 출병 명령', '없음', cls='ms'))
            + warnbox('실행 때 조건을 다시 봅니다. 가는 길에 적 군단 · 적 계책 · 요격 범위를 만나면 그 자리에서 멈추고 싸웁니다.')
            + f'<div style="margin-top:auto;display:flex;flex-direction:column;gap:6px"><span class="t2" style="font-size:12.5px">04순에 <b class="bz">부곡 2</b>을 이끌고 <b class="bz">{pick}</b> 방면으로.</span>'
            + btn('04순에 예약', 'primary', style='width:100%', attrs='data-input-id="action.deploy"') + '</div>')
    page31('V31K6Command.dc.html', 'K6 명령 흐름 — 출병 · 지도 대상 고르기(데스크톱)',
           shell_desk('작전실', 'war', mapst + flow(4, '군사', ('군사', '이동'), rows, args, foot)))


# ------------------------------------------------------------------ 2. V31K6CommandEdit — 채운 순 편집 · 준비 중 사유
def board_command_edit():
    OX = 100
    MAPW = CONTENT_W - FLOW_W
    hx, hy = DESK_PX(*CELLS[HERE]); hx -= OX
    labs = ''.join(mlab(n, DESK_PX(*CELLS[n])[0] - OX, DESK_PX(*CELLS[n])[1] + 26, dim=False) for n in ['장사현', '영양현', '신정현', '번창현'])
    mapst = (f'<main aria-label="지도" style="position:relative;width:{MAPW}px;flex-shrink:0;overflow:hidden;background:#0c0f0e">'
             f'{mapimg("desk", 1048, 952, "영천 일대 지도 — 현 보기", -OX, 0)}{labs}{me_marker(hx, hy - 22, "in")}{view_bar()}</main>')
    editband = (f'<div role="region" aria-label="고른 순 — 채운 순" style="flex-shrink:0;padding:8px 16px;border-bottom:1px solid #9c7f3f;background:rgba(211,176,100,.08);display:flex;flex-direction:column;gap:8px">'
                f'<div style="display:flex;align-items:center;gap:8px;font-size:13px"><span class="mono bz" style="font-weight:700">02순</span><span class="t2">3월 하순 · <span class="mono">22:40</span></span>'
                f'<span>지금 예약: <b class="serif">훈련</b> · 하후돈 부곡</span>{chip("예약", "bronze")}</div>'
                f'<div style="display:flex;gap:6px">{btn("바꾸기", "sm", "swap")}{btn("비우기", "sm", "clear")}{btn("앞 순으로", "sm", "prev")}{btn("뒤 순으로", "sm", "next")}'
                f'<span class="muted" style="font-size:11px;align-self:center;margin-left:auto">12순 전체 당기기 · 밀기는 12순 열</span></div></div>')
    rows = (cmd_row('출사', '섬길 주공을 정한다', 'no', '이미 섬기는 중', input_id='action.enlist')
            + cmd_row('하야', '섬기던 주공을 떠난다', 'wait', input_id='action.resign')
            + cmd_row('거병', '무주 현에서 일어선다', 'wait', sel=True, input_id='action.rise')
            + cmd_row('독립', '섬기던 세력에서 나온다', 'wait', input_id='action.independence')
            + cmd_row('건국', '세력을 나라로 선포한다', 'no', '주공만', input_id='action.foundState')
            + cmd_row('선양', '주공 자리를 넘긴다', 'no', '주공만', input_id='action.abdicate')
            + cmd_row('결의', '같은 구역의 장수와 맺는다', 'ok', input_id='action.oath')
            + cmd_row('세력 해산', '세력을 흩는다', 'wait', input_id='action.dissolve'))
    popw = pop('거병 — 아직 열리지 않은 명령입니다', '서버에 이 명령의 처리가 아직 없습니다. 열리면 이 자리에서 바로 예약할 수 있습니다.',
               '열리면: 무주 현에서 새 세력을 일으켜 주공이 됩니다.', '거병', 'left:252px;top:470px;width:316px')
    args = (args_head('훈련', '02순', '3월 하순 22:40') + help_strip('내 부대의 훈련을 올립니다.')
            + inset('<span class="muted" style="font-size:11px">지금 예약된 값 — 바꾸기 전까지 그대로</span>'
                    + fieldrow('대상', '지금 선 현 — 장사현') + fieldrow('훈련', '[값] → [값]') + fieldrow('쌀 · 금', '[미정]'))
            + f'<div class="inset" style="padding:10px;display:flex;flex-direction:column;gap:6px"><span class="bz" style="font-size:12px;font-weight:700">바꾸기를 누르면</span>'
            f'<span class="t2" style="font-size:12px;line-height:1.5">목록에서 새 명령을 고르고 인자를 채운 뒤 「02순 덮어쓰기」로 확인합니다. 훈련의 값은 초안으로 남습니다.</span></div>'
            + f'<div class="inset" style="padding:10px;display:flex;flex-direction:column;gap:6px"><span class="bz" style="font-size:12px;font-weight:700">12순이 다 찼을 때</span>'
            f'<span class="t2" style="font-size:12px;line-height:1.5">「이번 순에 할 일」은 「12순이 다 찼습니다 — 채운 순을 눌러 바꾸세요」를 보이고 01순을 고른 채 엽니다.</span></div>'
            + f'<div style="margin-top:auto;display:flex;flex-direction:column;gap:6px">{toast("06순 결의를 07순으로 옮겼습니다", "ok", btn("되돌리기", "sm"))}</div>')
    aside = (f'<aside aria-label="이번 순에 할 일" style="width:{FLOW_W}px;flex-shrink:0;display:flex;flex-direction:column;background:#1b201d;border-left:1px solid #9c7f3f;min-height:0;position:relative">'
             f'{flow_head()}{flow_strip(2)}{editband}{flow_cats("나라", ())}<div style="flex-grow:1;display:flex;min-height:0">'
             f'{flow_list(rows, boardnote("준비 중 = 서버에 처리가 아직 없는 명령. 숨기지 않는다(K0 Q2)."))}{flow_args(args)}</div>{popw}</aside>')
    page31('V31K6CommandEdit.dc.html', 'K6 명령 흐름 — 채운 순 편집 · 준비 중(데스크톱)', shell_desk('작전실', 'war', mapst + aside))


# ------------------------------------------------------------------ 3. V31K6MCommand — 모바일, 지도에서 「여기로 명령」으로 연 목록
def board_mcommand():
    strip = f'<div style="padding:4px 12px 8px;border-bottom:1px solid #2c342f;overflow:hidden">{turn_strip(4, 12, gap=4, cell_w=96)}</div>'
    here = (f'<div style="margin:8px 12px 0;padding:0 4px 0 10px;min-height:48px;display:flex;align-items:center;gap:8px;border:1px solid #ffd36d;background:rgba(255,211,109,.08)">'
            f'{icon("target", 18, "#ffd36d")}<span style="display:flex;flex-direction:column;min-width:0;flex:1"><span class="serif" style="font-weight:900">영양현</span>'
            f'<span class="muted" style="font-size:11px">이 곳에 할 수 있는 명령을 위로 · {dist("영양현")}</span></span>'
            f'<button type="button" class="ibtn" aria-label="장소 빼기" style="border:0;background:transparent">{icon("close", 18)}</button></div>')
    rows = (cmd_row('이동', '이동 · 영양현으로', 'ok', h=52, input_id='action.move')
            + cmd_row('강행', '이동 · 빨리, 지친다', 'ok', h=52, input_id='action.forcedMarch')
            + cmd_row('출병', '군사 · 부대를 이끌고', 'ok', draft=True, h=52, input_id='action.deploy')
            + cmd_row('물자조달', '물자 · 인접 현만', 'no', '인접 아님', h=52, input_id='action.transport')
            + '<div style="height:28px;display:flex;align-items:center;padding:0 12px;font-size:11px;background:#141816" class="muted">다른 명령</div>'
            + cmd_row('농지개간', '내정 · 지금 선 현', 'ok', h=52, input_id='action.farm')
            + cmd_row('징병', '군사 · 성 안에서', 'no', '성 밖', h=52, input_id='action.conscript'))
    body = (f'{strip}{here}<div style="padding:8px 12px">{cat_tabs("전체", ("군사",), scroll=True)}</div>'
            f'<div role="listbox" aria-label="명령" style="display:flex;flex-direction:column;border-top:1px solid #2c342f">{rows}</div>')
    main = (f'<main style="position:relative;width:390px;height:844px;overflow:hidden">{mapimg("mob", 390, 844, "양적 일대 지도")}'
            f'<div class="scrim"></div>{sheet("이번 순에 할 일", body, top=40)}</main>')
    page31('V31K6MCommand.dc.html', 'K6 명령 흐름 — 여기로 명령(모바일)', main, w=MW, h=MH)


# ------------------------------------------------------------------ 4. V31K6MCommandArgs — 모바일 증여 · 사람 칸 · 자원 · 수량
def board_mcommandargs():
    head = (f'<div style="display:flex;align-items:center;gap:6px;padding:0 12px 0 4px;height:44px;border-bottom:1px solid #2c342f">'
            f'<a href="#" class="btn sm" style="background:transparent;border:0">{icon("back", 16)}명령 목록</a>'
            f'<span style="margin-left:auto">{turn_caption(4)}</span></div>')
    person = field('받는 사람', f'<button type="button" class="inp" style="cursor:pointer;text-align:left">{portrait("heojeo", "허저", 22, 30)}'
                                f'<span class="serif" style="font-weight:700">허저</span><span class="muted" style="font-size:12px">내 부 · 장사현</span>'
                                f'<span style="margin-left:auto;color:#d3b064;font-size:12px">바꾸기</span></button>', '같은 구역의 장수만 · 사람 고르기로 연다')
    rsc = field('자원', seg([('금', None), ('쌀', None), ('철', None), ('목재', None), ('말', None)], '쌀', '자원', style='flex-wrap:nowrap'),
                '철 · 목재 · 말은 개인 재고 경로가 생기기 전까지 고를 수 없음(누르면 이유)')
    body = (f'{head}<div style="padding:12px;display:flex;flex-direction:column;gap:10px">'
            f'<div style="display:flex;align-items:baseline;gap:8px"><span class="serif" style="font-size:22px;font-weight:900">증여</span>{chip("04순", "bronze")}</div>'
            + help_strip('내 금 · 쌀을 다른 장수에게 줍니다.') + person + rsc + qty('[값]', '[값]')
            + '</div>')
    foot = btn('04순에 예약', 'primary', style='flex:1', attrs='data-input-id="action.gift"')
    main = (f'<main style="position:relative;width:390px;height:844px;overflow:hidden">{mapimg("mob", 390, 844, "양적 일대 지도")}'
            f'<div class="scrim"></div>{sheet("이번 순에 할 일", body, top=40, foot=foot)}</main>')
    page31('V31K6MCommandArgs.dc.html', 'K6 명령 흐름 — 증여 인자(모바일)', main, w=MW, h=MH)


# ------------------------------------------------------------------ 5. V31K6MPick — 모바일 첩보 대상(군) 고르기 · 군 보기
def board_mpick():
    # 군 보기 그림(MAP['jun'] 840×480, 4px/칸)을 1.76배로 채운다. 이름표 자리는 방위만 맞춘 예시(하남윤 북서 · 진류 북동 · 양국 동 · 여남 남동).
    ox, oy = -227, 0
    rg = [('영천군', 195, 330, 'ok', '', '다 보임'), ('하남윤', 92, 150, 'sel', '', '첩보 · 4순 전'), ('진류군', 292, 150, 'ok', '', '안 보임'),
          ('양국', 318, 420, 'ok', '', '안 보임'), ('여남군', 280, 520, 'no', '이웃 아님', '안 보임')]
    rgs = ''
    for n, x, y, k, r, tier in rg:
        rgs += (f'<button type="button" class="rgn {k}" aria-label="{n} — {tier}" style="left:{x}px;top:{y}px;flex-direction:column;gap:0;padding:4px 12px">'
                f'<span>{n}</span><span style="font-family:\'Noto Sans KR\',sans-serif;font-size:10.5px;font-weight:500;color:#b9b2a3">{tier}</span>'
                f'{why_tag(r) if r else ""}</button>')
    hx, hy = 262, 296
    conf = (f'<section class="sheet" aria-label="고른 군" style="bottom:0;height:200px"><div class="grip"></div>'
            f'<div style="height:44px;display:flex;align-items:center;gap:8px;padding:0 12px"><span class="serif" style="font-size:17px;font-weight:900">하남윤</span>'
            f'{chip("첩보 · 4순 전", "info")}{ok_chip()}</div>'
            f'<div style="padding:0 12px" class="t2">04순 · 4월 중순 00:40에 살핍니다. 다시 첩보하면 갱신됩니다.</div>'
            f'<div style="padding:10px 12px;display:flex;gap:8px">{btn("이곳으로 정하기", "primary", style="flex:1")}{btn("다시 고르기")}</div></section>')
    main = (f'<main aria-label="지도 — 첩보할 군 고르는 중" style="position:relative;width:390px;height:844px;overflow:hidden">'
            f'{mapimg("jun", 1478, 844, "영천 일대 — 군 보기", ox, oy)}<div class="dim"></div>{rgs}{me_marker(hx, hy, "in", tag=False)}'
            f'{pick_bar("첩보할 군 고르기", "04순 · 이웃 군만 · 가능 3 · 불가 1", mobile=True)}{conf}</main>')
    page31('V31K6MPick.dc.html', 'K6 명령 흐름 — 첩보할 군 고르기(모바일)', main, w=MW, h=MH)


# ================================================================== 계책 덱 · 계책 쓰기
# 지금 서버 공급 v1: 소유 장수별 견벽 · 간파(설계 §6.4 「최초 공용 손패 공급 v1」), 손패 상한 3. 쓰기 · 걸기 입력은 13행 모두 PLANNED.
# 원장 표시 이름을 바꿔 그린 것 — 전체 승인 때 사용자 문구로 올라간다(K0 추천안, 2026-09-30). 승인되면 C1이 원장 displayName을 고친다.
NAME_WAIT = {'보급 습격': '군량 습격', '쌀 사고팔기': '군량매매', '병종 바꿔 익히기': '숙련전환'}


def name_wait(text):
    return (chip('이름 승인 대기', 'info') + ' ') if any(k in text for k in NAME_WAIT) else ''


HAND = [('견벽', '대응', '공격받으면 방비가 오르고, 대신 쌀을 더 쓴다', '수공 · 보급 습격'),
        ('간파', '대응', '상대 계책 한 장을 무효로 한다', '—')]
MODE_TONE = {'즉시': 'bronze', '설치': 'info', '대응': 'moss'}
CORPS_SUB = ['군단 · 세력 작전', '공성', '전투', '시야 · 첩보']


def card(name, mode, eff, block, sel=False, w=156, h=404):
    img = pic(ART.get(name, ''), w - 2, int(h * .51), f'{name} 카드 그림', 'border-bottom:1px solid #2c342f')
    return (f'<button type="button" aria-pressed="{"true" if sel else "false"}" style="width:{w}px;height:{h}px;flex-shrink:0;display:flex;flex-direction:column;padding:0;font:inherit;text-align:left;color:#ece6d8;'
            f'background:#1b201d;border:1px solid {"#d3b064" if sel else "#3d4740"};cursor:pointer;{"transform:translateY(-12px);box-shadow:0 0 24px rgba(211,176,100,.22);" if sel else ""}">'
            f'<span style="display:flex;justify-content:space-between;align-items:center;width:100%;padding:6px 8px;border-bottom:1px solid #2c342f;background:#232a26">{chip(mode, MODE_TONE[mode])}'
            f'<span class="muted" style="font-size:11px">비용 [미정]</span></span>{img}'
            f'<span style="padding:8px;display:flex;flex-direction:column;gap:4px;flex-grow:1"><span class="serif" style="font-size:17px;font-weight:900">{name}</span>'
            f'<span class="t2" style="font-size:11.5px;line-height:1.45">{eff}</span></span>'
            f'<span style="padding:6px 8px;border-top:1px solid #2c342f;font-size:10.5px;display:flex;flex-wrap:wrap;gap:4px;align-items:center" class="muted">막는 법 · {block}{name_wait(block)}</span></button>')


def zone(title, sub, inner, h=176):
    return f'<section class="panel" style="flex:1 1 0;min-width:0;height:{h}px">{sec(title, sub)}<div style="padding:10px;display:flex;flex-direction:column;gap:8px;flex-grow:1">{inner}</div></section>'


def emptyslot(t):
    return f'<div style="flex-grow:1;border:1px dashed #3d4740;display:flex;align-items:center;justify-content:center;text-align:center;padding:8px;font-size:12px" class="muted">{t}</div>'


def board_hand():
    zones = ('<div style="display:flex;gap:12px">'
             + zone('즉시', '내 턴에 공개', emptyslot('비어 있음 — 손패에서 카드를 누르고 「쓰기」'))
             + zone('설치', '숨겨 깐다 · 조건이 맞으면 발동', emptyslot('비어 있음 — 설치 카드가 손패에 없다'))
             + zone('대응', '방어 칸 · 공격받을 때 공개', emptyslot('비어 있음 — 대응 카드를 누르고 「걸기」'))
             + '</div>')
    cards = ''.join(card(n, m, e, b, sel=(n == '간파')) for n, m, e, b in HAND)
    nxt = (f'<div style="width:156px;height:404px;flex-shrink:0;border:1px dashed #3d4740;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:6px;padding:10px;text-align:center">'
           f'{icon("clock", 22, "#8a8477")}<span class="muted" style="font-size:12px;line-height:1.5">다음 개인 턴에 한 장 · 한도 3</span></div>')
    handp = (f'<section class="panel" style="flex-grow:1;min-height:0">{sec("손패", "2 / 3 · 자기 턴마다 한 장 뽑는다")}'
             f'<div style="flex-grow:1;display:flex;align-items:center;justify-content:center;gap:14px;padding:20px 12px">{cards}{nxt}</div>'
             f'<div style="padding:10px 16px;border-top:1px solid #2c342f;display:flex;gap:8px;align-items:center">'
             f'{input_btn("간파 — 대응 칸에 걸기", "NOT_DELIVERED", input_id="stratagem.play")}{btn("자세히")}'
             f'<span class="muted" style="font-size:12px;margin-left:auto;text-align:right">비용은 사용 위치의 보급망 창고에서 · 판정은 쓰는 인물 지력 대 상대 지력</span></div></section>')
    right = (f'<div style="width:380px;flex-shrink:0;display:flex;flex-direction:column;gap:12px;min-height:0">'
             f'<section class="panel" style="height:250px">{sec("덱 — 누가 어떤 카드를 넣었나", "")}{state_waiting("덱 기여 읽기 준비 중", "인물별로 넣은 카드는 서버가 아직 주지 않습니다(계약판 K6-10 보강).", pad=10)}</section>'
             f'<section class="panel" style="height:250px">{sec("지난 발동", "성공 · 무효 · 적중")}{state_waiting("발동 기록 준비 중", "계책 입력이 열리면 기록 5분류의 사건으로 보입니다.", pad=10)}</section>'
             f'<section class="panel" style="flex-grow:1">{sec("다른 상태", "")}<div style="padding:10px;display:flex;flex-direction:column;gap:8px">'
             f'{infobox("<b>첫 손패 전</b> — 「아직 첫 손패를 받지 않았습니다. 다음 개인 턴에 받습니다.」(NOT_READY)")}'
             f'{infobox("<b>손패 비었음</b> — 「손패가 비었습니다. 다음 개인 턴에 한 장 뽑습니다.」")}'
             f'{warnbox("<b>불러오기 실패</b> — 다시 시도 + 오류 번호. 빈 손패와 같은 모양으로 그리지 않는다.")}</div></section></div>')
    body = (pagehead('계책', ['계책 덱', '역정보'], '계책 덱', chip('손패 2 / 3', 'bronze'))
            + f'<div style="flex-grow:1;display:flex;gap:12px;padding:12px;min-height:0"><div style="flex:1 1 0;min-width:0;display:flex;flex-direction:column;gap:12px">{zones}{handp}</div>{right}</div>')
    page31('V31K6Hand.dc.html', 'K6 계책 덱(데스크톱)', shell_desk('계책', 'stratagem', f'<main style="flex-grow:1;min-width:0;display:flex;flex-direction:column">{body}</main>'))


def board_mhand():
    cards = ''.join(card(n, m, e, b, sel=(n == '간파'), w=150, h=230) for n, m, e, b in HAND)
    zrow = lambda t, tone, sub, st: (f'<div style="min-height:60px;display:flex;align-items:center;gap:10px;padding:0 12px;border-bottom:1px solid #2c342f">'  # noqa: E731
                                     f'{chip(t, tone)}<span style="display:flex;flex-direction:column;min-width:0;flex:1"><span style="font-size:13px">{sub}</span>'
                                     f'<span class="muted" style="font-size:11px">{st}</span></span></div>')
    inner = (f'<div style="height:40px;display:flex;align-items:center;justify-content:space-between;padding:0 12px;border-bottom:1px solid #2c342f">'
             f'<span class="serif" style="font-size:16px;font-weight:900">계책 덱</span>{chip("손패 2 / 3", "bronze")}</div>'
             f'<div style="height:262px;display:flex;gap:10px;padding:18px 12px 8px;overflow:hidden">{cards}</div>'
             f'<div style="padding:0 12px 10px;display:flex;gap:8px">{input_btn("간파 걸기", "NOT_DELIVERED", input_id="stratagem.play", style="flex:1")}{btn("자세히")}</div>'
             f'<div style="border-top:1px solid #3d4740">{zrow("즉시", "bronze", "내 턴에 공개", "비어 있음")}{zrow("설치", "info", "숨겨 깐다", "비어 있음")}'
             f'{zrow("대응", "moss", "공격받을 때 공개", "비어 있음 — 카드를 누르고 「걸기」")}</div>'
             f'<div style="padding:10px 12px"><span class="note">끌어다 놓기 없음 — 카드를 누르고 단추로 건다. 덱 기여 · 지난 발동은 「자세히」 아래(서버 대기).</span></div>')
    page31('V31K6MHand.dc.html', 'K6 계책 덱(모바일)', shell_mob(mmain(inner), 'stratagem'), w=MW, h=MH)


def board_stratagem():
    OX, OY = 100, 120  # 밀현(북쪽)이 보이게 지도를 아래로 내린다
    MAPW = CONTENT_W - FLOW_W
    P_ = lambda n: (DESK_PX(*CELLS[n])[0] - OX, DESK_PX(*CELLS[n])[1] + OY)  # noqa: E731
    hx, hy = P_(HERE)
    cand = [('밀현', 'ok', ''), ('신정현', 'no', '사거리 밖'), ('영양현', 'no', '내 현'), ('장사현', 'no', '내 현')]
    marks = ''.join(mk(*P_(n), 'sel' if n == '밀현' else st, n, 'no' if st == 'no' else '') for n, st, r in cand)
    tx, ty = P_('밀현')
    ring = (f'<svg width="{MAPW}" height="952" style="position:absolute;left:0;top:0;pointer-events:none" aria-hidden="true">'
            f'<circle cx="{hx}" cy="{hy}" r="420" stroke="#7aa7c7" stroke-width="2" stroke-dasharray="6 6" fill="rgba(122,167,199,.06)"></circle></svg>'
            f'<span class="chip info" style="position:absolute;left:{hx + 250}px;top:{hy + 330}px">사거리 [미정]</span>')
    mapst = (f'<main aria-label="지도 — 계책 대상 고르는 중" style="position:relative;width:{MAPW}px;flex-shrink:0;overflow:hidden;background:#0c0f0e">'
             f'{mapimg("desk", 1048, 952, "영천 일대 지도 — 현 보기", -OX, OY)}<div class="dim"></div>{ring}{path_line(hx, hy, tx, ty, MAPW, 952, dist("밀현"))}{marks}'
             f'{me_marker(hx, hy - 22, "in", tag=False)}{pick_bar("화계 — 대상 현 고르기", "인접한 적 현만 · 가능 1 · 불가 3")}{view_bar()}</main>')
    hand = ''.join(f'<button type="button" aria-pressed="{"true" if n == "화계" else "false"}" style="width:64px;height:88px;flex-shrink:0;padding:0;border:{"2px solid #d3b064" if n == "화계" else "1px solid #3d4740"};background:#141816;cursor:pointer">'
                   f'{pic(ART.get(n, ""), 60, 60, n)}<span class="serif" style="display:block;font-size:12px;font-weight:700;padding-top:2px">{n}</span></button>' for n in ['화계', '첩보', '견벽', '간파'])
    cands = ''.join(opt(n, '[적 세력] · ' + dist(n) if st == 'ok' else dist(n), ok_chip() if st == 'ok' else why_tag(r), sel=(n == '밀현'), no=(st != 'ok'), h=44) for n, st, r in cand)
    panel = (f'<aside aria-label="계책 쓰기" style="width:{FLOW_W}px;flex-shrink:0;display:flex;flex-direction:column;background:#1b201d;border-left:1px solid #9c7f3f;min-height:0">'
             f'<div style="height:52px;flex-shrink:0;display:flex;align-items:center;gap:10px;padding:0 4px 0 16px;border-bottom:1px solid #3d4740;background:linear-gradient(180deg,#232a26,#1b201d)">'
             f'<span class="serif" style="font-size:18px;font-weight:900">계책 쓰기</span><span class="muted" style="font-size:12px">손패에서 한 장</span>'
             f'<button type="button" class="ibtn" aria-label="닫기(Esc)" style="margin-left:auto;border:0;background:transparent">{icon("close")}</button></div>'
             f'<div style="flex-shrink:0;display:flex;gap:6px;padding:10px 16px;border-bottom:1px solid #2c342f">{hand}'
             f'<span class="note" style="align-self:center;padding-left:6px">예시 손패. 지금 서버가 주는 카드는 견벽 · 간파뿐이다.</span></div>'
             f'<div style="flex-grow:1;display:flex;min-height:0">'
             f'<div style="width:220px;flex-shrink:0;border-right:1px solid #2c342f;padding:12px;display:flex;flex-direction:column;gap:8px">'
             f'{pic(ART.get("화계", ""), 196, 200, "화계 카드 그림")}<span class="serif" style="font-size:20px;font-weight:900">화계</span>'
             f'<div style="display:flex;gap:6px">{chip("즉시", "bronze")}{chip("지도 계열")}</div>'
             f'<span class="t2" style="font-size:12px;line-height:1.5">적 현 창고의 쌀 · 목재 일부를 태운다.</span><span class="muted" style="font-size:11px">막는 법 · 창고 분산</span></div>'
             f'<div style="flex:1;min-width:0;padding:12px 16px;display:flex;flex-direction:column;gap:10px;overflow:hidden">'
             + help_strip('적 현 창고를 불태웁니다.')
             + target_field('대상 현', '밀현', '[적 세력] · ' + dist('밀현'))
             + f'<div role="listbox" aria-label="대상 후보" style="display:flex;flex-direction:column;border-top:1px solid #2c342f">{cands}</div>'
             + inset(fieldrow('비용', res('목재', '[미정]')) + fieldrow('내는 곳', '장사현 창고(보급망)') + fieldrow('판정', '하후돈 지력 대 대상 쪽 지력'))
             + f'<div style="margin-top:auto">{input_btn("쓰기", "NOT_DELIVERED", input_id="stratagem.fire", style="width:100%")}</div></div></div></aside>')
    page31('V31K6Stratagem.dc.html', 'K6 계책 쓰기 — 대상 고르기(데스크톱)', shell_desk('계책', 'stratagem', mapst + panel))


def board_mstratagem():
    rows = (opt('양적현 수비', '하후돈 · 성 안', ok_chip(), sel=True, h=56) + opt('하후돈 부곡 — 야전', '출전하면 이 칸', ok_chip(), h=56)
            + opt('장사현 수비', '이전 · 내 자리가 아님', why_tag('내 자리 아님'), no=True, h=56))
    body = (f'<div style="padding:4px 12px 10px;display:flex;gap:10px;align-items:center;border-bottom:1px solid #2c342f">{pic(ART.get("간파", ""), 64, 80, "간파 카드 그림")}'
            f'<span style="display:flex;flex-direction:column;gap:4px"><span class="serif" style="font-size:18px;font-weight:900">간파</span>'
            f'<span style="display:flex;gap:6px">{chip("대응", "moss")}{chip("전투 계열")}</span><span class="t2" style="font-size:12px">상대 계책 한 장을 무효로 한다</span></span></div>'
            f'<div style="padding:10px 12px 0">{help_strip("공격받을 때 공개됩니다.")}</div>'
            f'<div style="padding:10px 12px 4px" class="t2">어느 방어 칸에 걸까</div>'
            f'<div role="listbox" aria-label="방어 칸" style="display:flex;flex-direction:column;border-top:1px solid #2c342f">{rows}</div>'
            f'<div style="padding:10px 12px">{infobox("계책 쓰기 · 걸기는 아직 열리지 않았습니다. 대상까지 고를 수 있고, 걸기 단추는 「준비 중」입니다.")}</div>')
    foot = input_btn('간파 걸기', 'NOT_DELIVERED', input_id='stratagem.play', style='flex:1')
    main = (f'<main style="position:relative;width:390px;height:844px;overflow:hidden">{mapimg("mob", 390, 844, "양적 일대 지도")}'
            f'<div class="scrim"></div>{sheet("계책 걸기", body, top=80, foot=foot)}</main>')
    page31('V31K6MStratagem.dc.html', 'K6 계책 걸기(모바일)', main, w=MW, h=MH)


# ================================================================== 군단 · 세력 작전
def corps_card():
    return (f'<section class="panel" aria-label="고른 군단">{sec("하후돈 군단", "내 군단 · 행군 중")}'
            f'<div style="display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:6px;padding:8px">'
            f'{kv("지휘", "하후돈")}{kv("부대", "부곡 2")}{kv("병력", "[값]")}{kv("지금", "양적현")}{kv("목적", "번창현", "bz")}{kv("멈춘 이유", "행군 중")}</div>'
            f'<div style="padding:0 8px 8px;display:flex;flex-direction:column;gap:6px">'
            + fieldrow('군단 방침', '수비', '적이 오면 자리를 지킨다')
            + f'<div style="display:flex;flex-wrap:wrap;gap:6px;padding-top:4px">'
            + input_btn('출병', 'BLOCKED', '출전 중', input_id='action.deploy', kind='')
            + input_btn('부대 모으기', 'AVAILABLE', input_id='action.muster', kind='')
            + btn('방침 바꾸기', href='#') + btn('군단장 바꾸기', href='#')
            + input_btn('편성 해제', 'BLOCKED', '주공만', input_id='court.releaseCorps', kind='')
            + '</div></div></section>')


def board_corps():
    MAPW = 936
    OX, OY = 60, 80  # 신정현(북쪽)의 적 군단이 보이게 지도를 아래로 내린다
    P_ = lambda n: (DESK_PX(*CELLS[n])[0] - OX, DESK_PX(*CELLS[n])[1] + OY)  # noqa: E731
    hx, hy = P_(HERE)
    bx, by = P_('번창현')
    ex, ey = P_('신정현')
    mapst = (f'<main aria-label="지도 — 군단" style="position:relative;width:{MAPW}px;flex-shrink:0;overflow:hidden;background:#0c0f0e">'
             f'{mapimg("desk", 1048, 952, "영천 일대 지도 — 현 보기", -OX, OY)}{path_line(hx, hy, bx, by, MAPW, 952, "행군 경로")}'
             f'{mk(hx + 40, hy + 30, "corps", "하후돈 군단")}{mk(ex, ey, "corps", "[적] 군단")}'
             f'<span class="chip info" style="position:absolute;left:{ex + 30}px;top:{ey - 10}px">5천~1만 · 첩보 2순 전</span>'
             f'{me_marker(hx, hy - 22, "corps", tag=True)}{mlab("번창현", bx, by + 26)}{view_bar()}</main>')
    rows = (opt('하후돈 군단', '내 군단 · 양적현 → 번창현 · 행군 중', chip('내 군단', 'bronze'), sel=True, h=56)
            + opt('[적] 군단', '[적 세력] · 신정현 · 병력 5천~1만', chip('첩보 · 2순 전', 'info'), h=56))
    right = (f'<div style="width:{CONTENT_W - MAPW}px;flex-shrink:0;display:flex;flex-direction:column;gap:12px;padding:12px;min-height:0;overflow:hidden">'
             f'{seg(["보이는 군단", "세력 작전"], "보이는 군단", "보기")}'
             f'<section class="panel">{sec("보이는 군단", "안 보이는 군단은 목록에도 없다")}<div role="listbox" aria-label="군단" style="display:flex;flex-direction:column">{rows}</div></section>'
             f'{corps_card()}'
             f'<section class="panel" style="flex-grow:1;min-height:0">{sec("세력 작전 · 원군 요청", "주공 · 군주")}{state_waiting("세력 작전 준비 중", "여러 군단을 한 목표로 묶는 입력이 원장에 아직 없습니다(계약판 K6-06). 원군 요청(봉신)도 여기로 온다(K8).", pad=10)}</section></div>')
    body = pagehead('군단', CORPS_SUB, '군단 · 세력 작전') + f'<div style="flex-grow:1;display:flex;min-height:0">{mapst}{right}</div>'
    page31('V31K6Corps.dc.html', 'K6 군단 · 세력 작전(데스크톱)', shell_desk('군단', 'corps', f'<main style="flex-grow:1;min-width:0;display:flex;flex-direction:column">{body}</main>'))


def board_mcorps():
    hx, hy = MOB_PX(*CELLS[HERE])
    rows = (opt('하후돈 군단', '내 군단 · 행군 중 → 번창현', chip('내 군단', 'bronze'), sel=True, h=56)
            + opt('[적] 군단', '신정현 · 5천~1만', chip('2순 전', 'info'), h=56))
    body = (f'<div role="listbox" aria-label="군단" style="display:flex;flex-direction:column;border-top:1px solid #2c342f">{rows}</div>'
            f'<div style="display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:6px;padding:8px 12px">{kv("부대", "부곡 2")}{kv("병력", "[값]")}{kv("방침", "수비")}</div>'
            f'<div style="padding:0 12px 10px;display:flex;flex-wrap:wrap;gap:6px">'
            + input_btn('부대 모으기', 'AVAILABLE', input_id='action.muster', kind='')
            + input_btn('출병', 'BLOCKED', '출전 중', input_id='action.deploy', kind='') + btn('방침 바꾸기', href='#') + '</div>')
    inner = (f'{mapimg("mob", 390, 844, "양적 일대 지도", 0, -150)}{mk(hx + 40, hy - 150, "corps", "하후돈 군단")}{me_marker(hx, hy - 172, "corps", tag=False)}'
             f'<div style="position:absolute;left:8px;right:8px;top:8px">{seg(["보이는 군단", "세력 작전"], "보이는 군단", "보기")}</div>'
             f'{sheet("군단", body, height=340)}')
    page31('V31K6MCorps.dc.html', 'K6 군단(모바일)', shell_mob(mmain(inner), 'menu', '군단', '전체 메뉴'), w=MW, h=MH)


# ================================================================== 전투 — 전투 · 부재 대비(P-C04) · 참가 대기(P-C03) · 실시간(P-C05)
# 판 그림(64 × 64 전장)의 방식(아이소 · 탑다운)은 사용자 답 대기 — 자리 표시. 수치는 원장(data/battle/waryong-tactical-rules-v1.json) 값만.
SEATS = [('선봉', 'heojeo', '허저', '호위 부곡', '부 인물', '나'), ('중앙', 'hahoudon', '하후돈', '부곡 · 보병', '주장', '나'),
         ('좌익', 'ijeon', '이전', '부곡', '부 인물', 'AI'), ('좌비', '', '', '', '', ''), ('우익', '', '', '', '', ''), ('우비', '', '', '', '', '')]
BATTLES = [('야전', '영천 북쪽 구릉 · 양적현 부근', '조조 ↔ [적 세력]', '중앙 · 주장', 'join', '참가 대기 · 42초 남음'),
           ('성새전', '장사현 — 수비', '[적 세력] → 조조', '수비 책임 아님 · 참가', 'live', '진행 중 · AI가 맡는 중'),
           ('일기토', '신정현 부근 · 개인 조우', '하후돈 ↔ [적 장수]', '당사자', 'apply', '끝남 · 캠페인에 반영 중'),
           ('야전', '허현 남쪽', '[적 세력] ↔ 조조', '—', 'auto', '자동 진행됨 · 사람 참가 없음')]


# ------------------------------------------------------------------ 아이소 전투 판(2026-09-30 사용자 결정: 전투는 원작처럼 아이소, 전략 지도는 탑다운)
# 그림: MAP['battle_field'](원작 192판 · 야전 능선, 1024×544) · MAP['battle_siege'](원작 040판 · 성벽 · 성문 · 강 · 다리, 1024×524).
# 원작 투영: 칸 (r, c) → x = (r + c)·16, y = (r − c)·8(원본 해상도). 이 그림은 절반 크기라 x = (r + c)·8, y = (r − c)·4 + 왼쪽 꼭짓점 높이.
# (0,0) = 왼쪽 꼭짓점, (63,0) = 아래, (0,63) = 위, (63,63) = 오른쪽. 칸 자리는 그림을 보고 맞춘 예시다.
ISO = {'field': ('battle_field', 1024, 544, 8, 283, '야전 판 — 능선'), 'siege': ('battle_siege', 1024, 524, 8, 268, '성새 판 — 성벽 · 성문 · 강 · 다리')}
SIDE_COLOR = {'me': NATION['조조'], 'enemy': NATION['원소']}


def iso_px(kind, r, c, s=1.0, ox=0, oy=0):
    _, _, _, x0, y0, _ = ISO[kind]
    return int(((r + c) * 8 + x0 + 8) * s + ox), int(((r - c) * 4 + y0) * s + oy)


def diamond(x, y, s, stroke, dash='', fill='none', width=2, grow=None):
    grow = grow if grow is not None else (2.0 if s < 2 else 1.0)  # 작게 볼 때만 부풀린다 — 크게 볼 때는 칸 크기 그대로
    hw, hh = 8 * s * grow, 4 * s * grow
    d = f' stroke-dasharray="{dash}"' if dash else ''
    return f'<polygon points="{x - hw:.0f},{y:.0f} {x:.0f},{y - hh:.0f} {x + hw:.0f},{y:.0f} {x:.0f},{y + hh:.0f}" stroke="{stroke}" stroke-width="{width}"{d} fill="{fill}"></polygon>'


def unit_btn(x, y, ch, side, label, ai=False, sel=False, est=False):
    """분대 표지(44 누름) — 제비꼬리 깃발 + 장수 첫 글자(K2 깃발 규칙). 표지 발끝이 칸 자리."""
    col = SIDE_COLOR[side]
    badge = ('<span style="position:absolute;right:-6px;top:-6px;height:16px;padding:0 3px;font-size:10px;font-weight:700;line-height:16px;color:#161410;background:#b9b2a3">AI</span>'
             if ai else '')
    dash = ' stroke-dasharray="3 2"' if est else ''
    flag = (f'<svg width="30" height="26" viewBox="0 0 30 26" aria-hidden="true" style="display:block"><path d="M3 1v24" stroke="#1b201d" stroke-width="2"></path>'
            f'<path d="M4 2h24l-6 7 6 7H4z" fill="{col}" stroke="{"#ffd36d" if sel else "#0c0f0e"}" stroke-width="{2 if sel else 1}"{dash}></path>'
            f'<text x="13" y="13" text-anchor="middle" font-family="Noto Serif KR,serif" font-weight="900" font-size="11" fill="#fff">{ch}</text></svg>')
    return (f'<button type="button" aria-label="{label}" aria-pressed="{"true" if sel else "false"}" style="position:absolute;left:{x - 22}px;top:{y - 40}px;width:44px;height:44px;padding:0;'
            f'border:0;background:transparent;cursor:pointer;display:flex;align-items:flex-end;justify-content:center">{flag}{badge}</button>')


def unit_dot(x, y, side, sel=False):
    return (f'<span aria-hidden="true" style="position:absolute;left:{x - 5}px;top:{y - 5}px;width:10px;height:10px;transform:rotate(45deg);'
            f'background:{SIDE_COLOR[side]};border:{"2px solid #ffd36d" if sel else "1px solid #0c0f0e"}"></span>')


# ------------------------------------------------------------------ B안 — 원작 유닛 그림 + 머리 위 작은 깃발(K0 추천, 사용자 선택 대기)
# MAP['units'] = 원작 recolor 시트에서 자른 한 장(5열 × 2행, 칸 32×32, 투명): 열 = 장수 기마 · 기병 · 궁병 · 보병 · 깃발, 행 = 조조 · 원소.
# 그림 id 가 아직 없으면 칸 크기 점선 자리 표시로 그린다.
UNIT_COL = {'general': 0, 'cav': 1, 'archer': 2, 'inf': 3, 'flag': 4}
UNIT_NAME = {'general': '장수 기마', 'cav': '기병', 'archer': '궁병', 'inf': '보병', 'flag': '깃발'}
UNIT_ROW = {'me': 0, 'enemy': 1}


def unit_sprite(kind, side, k=1):
    w = 32 * k
    src = MAP.get('units', '')
    if not src:
        return (f'<span aria-hidden="true" style="display:block;width:{w}px;height:{w}px;border:1px dashed {SIDE_COLOR[side]};font-size:9px;line-height:1.1;'
                f'color:#ece6d8;background:rgba(12,15,14,.55);text-align:center;padding-top:8px">{UNIT_NAME[kind]}</span>')
    return (f'<span aria-hidden="true" style="display:block;width:{w}px;height:{w}px;overflow:hidden;position:relative">'
            f'<img src="{src}" alt="" style="position:absolute;left:{-UNIT_COL[kind] * w}px;top:{-UNIT_ROW[side] * w}px;width:{160 * k}px;height:{64 * k}px;max-width:none;image-rendering:pixelated"></span>')


def unit_sprite_btn(x, y, kind, ch, side, label, ai=False, sel=False, est=False, k=1):
    """원작 유닛 그림(발끝 = 칸 자리) + 머리 위 작은 깃발(장수 첫 글자 · 세력색). 누름 상자 44 × 52."""
    col = SIDE_COLOR[side]
    dash = ' stroke-dasharray="2 2"' if est else ''
    fw, fh = (20, 14) if k == 1 else (30, 21)
    flag = (f'<svg width="{fw}" height="{fh}" viewBox="0 0 20 14" aria-hidden="true" style="display:block"><path d="M2 0v14" stroke="#1b201d" stroke-width="1.5"></path>'
            f'<path d="M3 1h15l-4 5 4 5H3z" fill="{col}" stroke="{"#ffd36d" if sel else "#0c0f0e"}" stroke-width="{1.5 if sel else 1}"{dash}></path>'
            f'<text x="9" y="9" text-anchor="middle" font-family="Noto Serif KR,serif" font-weight="900" font-size="8" fill="#fff">{ch}</text></svg>')
    body = unit_sprite(kind, side, k) if kind else f'<span aria-hidden="true" style="display:block;width:{32 * k}px;height:{32 * k}px"></span>'
    badge = ('<span style="position:absolute;right:-4px;top:-4px;height:14px;padding:0 3px;font-size:9px;font-weight:700;line-height:14px;color:#161410;background:#b9b2a3">AI</span>'
             if ai else '')
    bw, bh = max(44, 32 * k), 32 * k + fh + 2
    return (f'<button type="button" aria-label="{label}" aria-pressed="{"true" if sel else "false"}" style="position:absolute;left:{x - bw // 2}px;top:{y - bh}px;width:{bw}px;height:{bh}px;padding:0;'
            f'border:0;background:transparent;cursor:pointer;display:flex;flex-direction:column;align-items:center;justify-content:flex-end;gap:1px">{flag}{body}{badge}</button>')


def iso_board(kind, vw, vh, s, ox, oy, units=(), marks=(), gate=None, small=False, fog=None, extra='', sprite_kind=None, sprite_k=1):
    """아이소 판 한 장 — 보는 창(vw × vh) 안에 그림을 s 배로 놓고 ox · oy 만큼 민다. units = (r, c, 글자, 편, 이름표, ai, sel, est),
    marks = (r, c, kind, 글) — kind: sel(고른 분대 칸) · rally(집결점) · target(목표). small = 판 전체 보기(표지는 점, 누르지 않음)."""
    key, W, H, _, _, alt = ISO[kind]
    img = mapimg(key, int(W * s), int(H * s), f'{alt}(원작 아이소 그림)', ox, oy)
    svg = ''
    labs = ''
    for r, c, mkind, txt in marks:
        x, y = iso_px(kind, r, c, s, ox, oy)
        if mkind == 'sel':
            svg += diamond(x, y, s, '#ffd36d', width=3)
        elif mkind == 'rally':
            svg += diamond(x, y, s, '#ffd36d', dash='5 4', fill='rgba(255,211,109,.18)')
            labs += (f'<button type="button" aria-label="집결점 {txt}" style="position:absolute;left:{x - 22}px;top:{y - 22}px;width:44px;height:44px;padding:0;border:0;background:transparent;'
                     f'cursor:pointer;display:flex;align-items:center;justify-content:center"><span class="mono" style="min-width:20px;height:20px;padding:0 4px;font-size:11px;font-weight:700;line-height:20px;'
                     f'color:#161410;background:#ffd36d">{txt}</span></button>')
        elif mkind == 'target':
            svg += diamond(x, y, s, '#e08a7c', dash='5 4', fill='rgba(201,107,93,.16)', width=3)
    ulay = ''
    for r, c, ch, side, label, ai, sel, est in units:
        x, y = iso_px(kind, r, c, s, ox, oy)
        if small:
            ulay += unit_dot(x, y, side, sel)
        elif sprite_kind and 16 * sprite_k + 6 <= x <= vw - 16 * sprite_k - 7 and 32 * sprite_k + 24 <= y <= vh - 5:
            ulay += unit_sprite_btn(x, y, sprite_kind.get(label), ch, side, label, ai, sel, est, sprite_k)
        elif not sprite_kind and 22 <= x <= vw - 23 and 40 <= y <= vh - 5:  # 누름 상자(44)가 보는 창 안에 다 들어올 때만 — 창 밖 분대는 작은 판 · 가장자리로 본다
            ulay += unit_btn(x, y, ch, side, label, ai, sel, est)
    if gate and not small:
        gr, gc, gtxt = gate
        x, y = iso_px(kind, gr, gc, s, ox, oy)
        ulay += (f'<button type="button" aria-haspopup="dialog" style="position:absolute;left:{x - 40}px;top:{y + 6}px;height:44px;padding:0 10px;display:flex;align-items:center;gap:6px;'
                 f'font:inherit;font-size:12px;font-weight:700;color:#ece6d8;background:rgba(27,32,29,.94);border:1px solid #d3b064;cursor:pointer">성문 · {gtxt}</button>')
    fogl = ''
    if fog:
        fogl = (f'<svg width="{vw}" height="{vh}" style="position:absolute;left:0;top:0;pointer-events:none" aria-hidden="true">'
                f'<defs><pattern id="fg{kind}{vw}" width="10" height="10" patternUnits="userSpaceOnUse" patternTransform="rotate(45)"><rect width="5" height="10" fill="rgba(12,15,14,.62)"></rect></pattern></defs>'
                f'<polygon points="{fog}" fill="url(#fg{kind}{vw})"></polygon></svg>')
    over = f'<svg width="{vw}" height="{vh}" style="position:absolute;left:0;top:0;pointer-events:none" aria-hidden="true">{svg}</svg>' if svg else ''
    return (f'<div role="application" aria-label="{alt} — 전투 판" style="position:relative;width:{vw}px;height:{vh}px;flex-shrink:0;overflow:hidden;background:#0c0f0e;border:1px solid #3d4740">'
            f'{img}{fogl}{over}{ulay}{labs}{extra}</div>')


def board_zoom(style='right:10px;bottom:10px'):
    b = 'background:rgba(20,24,22,.92)'
    return (f'<div style="position:absolute;{style};display:flex;flex-direction:column;gap:2px"><button type="button" class="ibtn" aria-label="판 확대" style="{b};font-size:20px">+</button>'
            f'<button type="button" class="ibtn" aria-label="판 축소" style="{b};font-size:20px">−</button><button type="button" class="ibtn" aria-label="판 전체 보기" style="{b};font-size:11px">전체</button></div>')


def board_mini(kind, w, sx, sy, sw, sh, units=()):
    """작은 판(판 전체) + 지금 보는 창 테두리."""
    key, W, H, _, _, alt = ISO[kind]
    s = w / W
    h = int(H * s)
    dots = ''.join(unit_dot(*iso_px(kind, r, c, s), side) for r, c, ch, side, *_ in units)
    return (f'<div style="position:absolute;right:10px;top:10px;width:{w}px;height:{h}px;border:1px solid #3d4740;background:#0c0f0e;overflow:hidden">'
            f'{mapimg(key, w, h, "판 전체")}{dots}<span style="position:absolute;left:{int(sx * s)}px;top:{int(sy * s)}px;width:{int(sw * s)}px;height:{int(sh * s)}px;border:2px solid #ffd36d"></span></div>')


# 야전(영천 북쪽 구릉) 예시 칸 — 우리(조조)는 왼쪽 아래 풀밭, 적은 오른쪽 높은 평지.
FIELD_UNITS = [(34, 15, '허', 'me', '선봉 허저 — 조작 나', False, True, False), (34, 6, '하', 'me', '중앙 하후돈 — 조작 나', False, False, False),
               (25, 10, '이', 'me', '좌익 이전 — 조작 AI', True, False, False), (37, 32, '적', 'enemy', '[적] 선봉 — 기병', False, False, False),
               (44, 38, '?', 'enemy', '[적] 분대 — 추정', False, False, True)]
FIELD_FOG = None
def battle_row(kind, where, sides, seat, st, stxt):
    act = {'join': btn('입장', 'primary', attrs='data-guide="tutorial.battle"'), 'live': btn('입장'), 'apply': btn('결과 보기'),
           'auto': btn('리플레이', href='#')}[st]
    tone = {'join': 'bronze', 'live': 'rust', 'apply': 'info', 'auto': ''}[st]
    return (f'<tr><td>{chip(kind)}</td><td><span class="serif" style="font-weight:700">{where}</span></td><td class="t2">{sides}</td>'
            f'<td class="t2">{seat}</td><td>{chip(stxt, tone)}</td><td style="text-align:right">{act}</td></tr>')


def readiness():
    return (f'<section class="panel">{sec("내가 없을 때", "AI 가 맡는다 · 들어오면 다음 틱에 넘겨받는다")}<div style="padding:8px 12px;display:flex;flex-direction:column">'
            + fieldrow('하후돈 군단 방침', '수비', '바꾸기 →') + fieldrow('내가 수비 책임인 성', '서버 대기', cls='muted')
            + fieldrow('대응 칸', '비어 있음', '계책 덱 →') + fieldrow('기본 자리', '주장 중앙 · 무력 높은 순 선봉')
            + '</div></section>')


def board_battles():
    rows = ''.join(battle_row(*b) for b in BATTLES)
    table = (f'<section class="panel" style="flex-grow:1;min-height:0">{sec("내 전투", "참가 자격이 있는 전투 · 개전 알림")}'
             f'<table class="table"><thead><tr><th>종류</th><th>장소</th><th>양쪽</th><th>내 자리</th><th>상태</th><th></th></tr></thead><tbody>{rows}</tbody></table>'
             f'<div style="padding:10px 12px">{infobox("이 목록 읽기(계약판 K6-11)는 서버 대기다. 준비 전 제품 화면은 이 자리 전체가 「준비 중」 상태다. 사람 참가 자격자가 없는 전투는 기다림 없이 자동으로 끝나고(2026-09-28 결정) 리플레이로만 본다.")}</div></section>')
    empty = f'<section class="panel" style="height:230px">{sec("빈 상태", "")}{state_empty("걸린 전투가 없습니다", "출병 · 수비 중에 적과 만나면 여기 나오고, 머리줄 알림 띠로도 알린다.", pad=10)}</section>'
    right = f'<div style="width:420px;flex-shrink:0;display:flex;flex-direction:column;gap:12px">{readiness()}{empty}</div>'
    body = pagehead('군단', CORPS_SUB, '전투') + f'<div style="flex-grow:1;display:flex;gap:12px;padding:12px;min-height:0"><div style="flex:1 1 0;min-width:0;display:flex;flex-direction:column">{table}</div>{right}</div>'
    band_html = ('<div class="band stop" role="status">' + icon('alert', 18, '#e08a7c') + '<span><b>전투가 열렸습니다</b> — 영천 북쪽 구릉 · 야전 · 42초 뒤 개전</span>'
                 + '<button type="button" class="btn sm primary" style="margin-left:auto" data-guide="tutorial.battle">입장</button></div>')
    page31('V31K6Battles.dc.html', 'K6 전투 · 부재 대비(데스크톱)',
           shell_desk('군단', 'corps', f'<main style="flex-grow:1;min-width:0;display:flex;flex-direction:column">{body}</main>', band_html=band_html))


def board_mbattles():
    def mcard(kind, where, sides, seat, st, stxt):
        act = {'join': btn('입장', 'primary', style='flex:1', attrs='data-guide="tutorial.battle"'), 'live': btn('입장', style='flex:1'),
               'apply': btn('결과 보기', style='flex:1'), 'auto': btn('리플레이', style='flex:1', href='#')}[st]
        tone = {'join': 'bronze', 'live': 'rust', 'apply': 'info', 'auto': ''}[st]
        return (f'<div style="padding:10px 12px;border-bottom:1px solid #2c342f;display:flex;flex-direction:column;gap:6px">'
                f'<div style="display:flex;gap:6px;align-items:center">{chip(kind)}{chip(stxt, tone)}</div>'
                f'<span class="serif" style="font-weight:700">{where}</span><span class="t2" style="font-size:12px">{sides} · {seat}</span><div style="display:flex">{act}</div></div>')
    inner = (mtabs_row(CORPS_SUB, '전투') + ''.join(mcard(*b) for b in BATTLES[:3])
             + f'<div style="padding:10px 12px"><button type="button" class="btn" style="width:100%">내가 없을 때 — 방침 · 대응 칸 보기</button></div>')
    page31('V31K6MBattles.dc.html', 'K6 전투(모바일)', shell_mob(mmain(inner, h=MAIN_M - 72), 'menu', '전투', '전체 메뉴',
                                                                   band_html=band('stop', mobile=True).replace('턴이 멈췄습니다', '전투가 열렸습니다').replace(
                                                                       '— 마지막 순 3월 중순 <span class="mono">21:40</span>. 운영진이 살피는 중입니다. 예약은 그대로 남습니다', '— 영천 북쪽 · 42초 뒤 개전').replace('상태 보기', '입장')), w=MW, h=MH)


def seat_card(pos, key, name, unit, who, ctrl, sel=False, warn=False, h=128):
    if not name:
        return (f'<div style="height:{h}px;border:1px dashed #3d4740;display:flex;flex-direction:column;justify-content:center;gap:4px;padding:8px 10px">'
                f'{chip(pos)}<span class="muted" style="font-size:12px">빈 자리 — 부곡 있는 장수가 없다</span></div>')
    w = ('<span class="rs" style="display:flex;gap:6px;align-items:center;font-size:11px;border:1px solid #c96b5d;background:rgba(201,107,93,.12);padding:3px 6px">'
         f'{icon("alert", 14, "#e08a7c")}사기가 100 아래 — 첫 틱부터 물러나며 명령을 받지 않는다</span>') if warn else ''
    return (f'<button type="button" aria-pressed="{"true" if sel else "false"}" style="height:{h}px;display:flex;flex-direction:column;gap:6px;padding:8px 10px;font:inherit;text-align:left;color:#ece6d8;'
            f'background:{"rgba(211,176,100,.10)" if sel else "#141816"};border:{"2px solid #ffd36d" if sel else "1px solid #3d4740"};cursor:pointer">'
            f'<span style="display:flex;align-items:center;gap:6px">{chip(pos, "bronze")}{chip("조작 " + ctrl, "bronze" if ctrl == "나" else "")}<span class="muted" style="font-size:11px;margin-left:auto">{who}</span></span>'
            f'<span style="display:flex;gap:8px;align-items:center">{portrait(key, name, 30, 42)}<span style="display:flex;flex-direction:column"><span class="serif" style="font-weight:700">{name}</span>'
            f'<span class="t2" style="font-size:11.5px">{unit} · 병력 [값] · 사기 [값]</span></span></span>{w}</button>')


def board_battlejoin():
    top = (f'<div style="height:64px;flex-shrink:0;display:flex;align-items:center;gap:14px;padding:0 16px;border-bottom:1px solid #9c7f3f;background:rgba(211,176,100,.08)">'
           f'<span class="mono bz" style="font-size:30px;font-weight:700">0:42</span><span style="display:flex;flex-direction:column"><span class="serif" style="font-size:17px;font-weight:900">개전까지 — 참가 대기</span>'
           f'<span class="t2" style="font-size:12px">야전 · 영천 북쪽 구릉 · 조조 ↔ [적 세력] · 사람 참가 전투(60초 기다림)</span></span>'
           f'<span style="margin-left:auto;display:flex;gap:8px">{btn("입장", "primary", attrs="data-guide=\"tutorial.battle\"")}{btn("나가기 — AI 에게 맡긴다")}</span></div>')
    seats = ''.join(seat_card(*s, sel=(s[0] == '선봉'), warn=(s[0] == '좌익')) for s in SEATS)
    left = (f'<section class="panel" style="width:320px;flex-shrink:0">{sec("우리 쪽 여섯 자리", "누른 두 자리를 맞바꾼다")}'
            f'<div style="padding:8px;display:flex;flex-direction:column;gap:6px;overflow:hidden">{seats}</div></section>')
    right = (f'<div style="width:288px;flex-shrink:0;display:flex;flex-direction:column;gap:12px">'
             f'<section class="panel">{sec("상대 — 보이는 만큼", "")}<div style="padding:8px 12px">'
             + fieldrow('[적] 선봉', '기병 추정') + fieldrow('[적] 중앙', '병력 [값]') + fieldrow('나머지', '안개 속', cls='muted') + '</div></section>'
             f'<section class="panel">{sec("전장", "티켓에 고정")}<div style="padding:8px 12px">'
             + fieldrow('판', '야전 판 · [값]번') + fieldrow('날씨 · 밤 · 계절', '서버 대기', cls='muted') + fieldrow('목표', '서버 대기', cls='muted')
             + fieldrow('길이', '5분 · 3,000틱') + '</div></section>'
             f'<section class="panel" style="flex-grow:1">{sec("안내", "")}<ul class="ul" style="padding:4px 12px">'
             '<li><b>자리</b> — 기본은 주장 중앙, 무력 높은 장수 선봉, 나머지 통솔 순 좌익 → 우익 → 좌비 → 우비.</li>'
             '<li><b>맞바꾸기</b> — 한 자리를 누르고 다른 자리를 누른다. 끌기 없다.</li>'
             '<li><b>안 들어오면</b> — 0:00 에 AI 가 맡는다. 진행 중에 들어오면 다음 틱에 넘겨받는다.</li></ul></section></div>')
    body = top + f'<div style="flex-grow:1;display:flex;gap:12px;padding:12px;min-height:0">{left}<div style="flex:1 1 0;min-width:0;display:flex;flex-direction:column;gap:8px">{join_board()}</div>{right}</div>'
    page31('V31K6BattleJoin.dc.html', 'K6 전투 참가 대기 · 배치(데스크톱)', shell_desk('전투', 'corps', f'<main style="flex-grow:1;min-width:0;display:flex;flex-direction:column">{body}</main>'))


def join_board():
    s, ox, oy = 1.25, -60, -20
    units = [u for u in FIELD_UNITS if u[3] == 'me'] + [(37, 32, '?', 'enemy', '[적] 선봉 — 추정', False, False, True)]
    chips = ''
    for r, c, pos in [(34, 15, '선봉'), (34, 6, '중앙'), (25, 10, '좌익')]:
        x, y = iso_px('field', r, c, s, ox, oy)
        chips += f'<span class="chip bronze" style="position:absolute;left:{x - 20}px;top:{y + 6}px;background:rgba(20,24,22,.94)">{pos}</span>'
    note = ('<div style="position:absolute;left:10px;bottom:10px;max-width:420px;padding:8px 10px;background:rgba(27,32,29,.94);border:1px solid #3d4740;font-size:12px;line-height:1.5" class="t2">'
            '자리 카드나 판의 깃발을 눌러 고르고, 다른 자리를 눌러 맞바꾼다. 상대는 안개 속 — 보이는 것만 점선 깃발로.</div>')
    return iso_board('field', 728, 660, s, ox, oy, units, [(34, 15, 'sel', '')], extra=chips + note + board_zoom())




def board_mbattlejoin():
    seats = ''.join(seat_card(*s, sel=(s[0] == '선봉'), warn=(s[0] == '좌익'), h=112) for s in SEATS[:4])
    inner = (f'<div style="height:52px;display:flex;align-items:center;gap:10px;padding:0 12px;border-bottom:1px solid #9c7f3f;background:rgba(211,176,100,.08)">'
             f'<span class="mono bz" style="font-size:24px;font-weight:700">0:42</span><span class="t2" style="font-size:12px;flex:1">야전 · 영천 북쪽 · 참가 대기</span>'
             f'{btn("입장", "primary", attrs="data-guide=\"tutorial.battle\"")}</div>'
             f'<div style="padding:8px;position:relative">{iso_board("field", 374, 199, 374 / 1024, 0, 0, [u for u in FIELD_UNITS if u[3] == "me"], small=True)}<span class="note" style="display:block;padding-top:4px">판 전체 · 두 손가락으로 확대 · 칸은 탭한 뒤 확대해서 고른다</span></div>'
             f'<div style="padding:0 8px;display:grid;grid-template-columns:1fr 1fr;gap:6px">{seats}</div>'
             f'<div style="padding:8px 12px"><span class="note">나머지 두 자리(우익 · 우비)는 아래로 밀어 본다. 누른 두 자리를 맞바꾼다.</span></div>')
    page31('V31K6MBattleJoin.dc.html', 'K6 전투 참가 대기(모바일)', shell_mob(mmain(inner), 'menu', '전투', '전투 목록', tabs=True), w=MW, h=MH)


CMDS = ['돌격', '공격', '대형', '수비', '성벽', '후퇴']


def cmdbar(mobile=False):
    b = ''.join(f'<button type="button" class="btn" style="{"flex:1 1 0;padding:0 4px" if mobile else "min-width:80px"}">{c}</button>' for c in CMDS)
    r = ''.join(f'<button type="button" class="btn" style="{"flex:1 1 0;padding:0 4px" if mobile else ""}">집결 {i}</button>' for i in (1, 2, 3))
    extra = (f'<button type="button" class="btn">목표</button><button type="button" class="btn">계책</button>'
             + btn_off('일기토', '조건 안 됨'))
    if mobile:
        return (f'<div style="display:flex;flex-direction:column;gap:4px;padding:6px 8px;border-top:1px solid #3d4740;background:#1b201d">'
                f'<div style="display:flex;gap:4px">{b}</div><div style="display:flex;gap:4px">{r}<button type="button" class="btn" style="flex:1 1 0;padding:0 4px">목표</button>'
                f'<button type="button" class="btn" style="flex:1 1 0;padding:0 4px">계책</button></div></div>')
    return (f'<div style="height:64px;flex-shrink:0;display:flex;align-items:center;gap:6px;padding:0 12px;border-top:1px solid #3d4740;background:#1b201d">'
            f'<span class="t2" style="font-size:12px;margin-right:4px">선봉 허저에게</span>{b}<span style="width:1px;height:32px;background:#3d4740"></span>{r}'
            f'<span style="width:1px;height:32px;background:#3d4740"></span>{extra}</div>')


def squad(pos, key, name, ctrl, now, sel=False, note=''):
    return (f'<button type="button" aria-pressed="{"true" if sel else "false"}" style="min-height:64px;display:flex;align-items:center;gap:8px;padding:6px 8px;font:inherit;text-align:left;color:#ece6d8;'
            f'background:{"rgba(211,176,100,.10)" if sel else "#141816"};border:{"2px solid #ffd36d" if sel else "1px solid #3d4740"};cursor:pointer">'
            f'{portrait(key, name, 28, 40)}<span style="display:flex;flex-direction:column;min-width:0;flex:1;gap:2px">'
            f'<span style="display:flex;gap:6px;align-items:center"><span class="serif" style="font-weight:700">{name}</span>{chip(pos, "bronze")}{chip("조작 " + ctrl, "bronze" if ctrl == "나" else "")}</span>'
            f'<span class="t2" style="font-size:11px">병력 [값] · 사기 [값] · {now}</span>{f"<span class=rs style=font-size:11px>{note}</span>" if note else ""}</span></button>')


def live_board():
    s, ox, oy = 1.35, -215, 0
    marks = [(34, 15, 'sel', ''), (35, 23, 'rally', '1'), (37, 32, 'target', '')]
    mini = board_mini('field', 200, 215 / s, 0, 736 / s, 740 / s, FIELD_UNITS)
    return iso_board('field', 736, 740, s, ox, oy, FIELD_UNITS, marks, extra=mini + board_zoom())


def board_battlelive():
    top = (f'<div style="height:52px;flex-shrink:0;display:flex;align-items:center;gap:14px;padding:0 16px;border-bottom:1px solid #3d4740;background:#1b201d">'
           f'<span class="mono bz" style="font-size:24px;font-weight:700">3:12</span><span class="muted" style="font-size:12px">남음 · 틱 [값] / 3,000</span>'
           f'{chip("야전 · 영천 북쪽 구릉")}<span class="t2" style="font-size:12.5px">방금 — 적 선봉이 좌익에 닿았다</span>'
           f'<span style="margin-left:auto;display:flex;gap:6px">{btn("전체에게", "sm")}{btn("나가기 — AI 에게 맡긴다", "sm")}</span></div>')
    squads = (squad('선봉', 'heojeo', '허저', '나', '돌격 중', sel=True) + squad('중앙', 'hahoudon', '하후돈', '나', '대형')
              + squad('좌익', 'ijeon', '이전', 'AI', '후퇴 우선', note='사기 100 아래 — 명령을 받지 않는다'))
    left = (f'<section class="panel" style="width:300px;flex-shrink:0">{sec("우리 분대", "숫자키 1–6 · 누르면 고른다")}'
            f'<div style="padding:8px;display:flex;flex-direction:column;gap:6px">{squads}'
            f'<div style="border:1px dashed #3d4740;padding:8px 10px;font-size:12px" class="muted">좌비 · 우익 · 우비 — 빈 자리</div></div></section>')
    log = ''.join(f'<li><span class="mono muted">{t}</span> {x}</li>' for t, x in [
        ('3:12', '적 선봉이 좌익에 닿았다'), ('3:20', '<b>명령 받음</b> — 허저 돌격, 틱 [값]부터'), ('3:31', '<span class="rs">명령 거절</span> — 이전 공격: 사기가 낮아 물러나는 중'),
        ('3:40', '계책 「간파」 공개 — 적 계책 무효'), ('4:02', '하후돈 군단 대형')])
    right = (f'<div style="width:300px;flex-shrink:0;display:flex;flex-direction:column;gap:12px;min-height:0">'
             f'<section class="panel" style="flex-grow:1;min-height:0">{sec("사건", "")}<ul class="ul" style="padding:4px 12px">{log}</ul></section>'
             f'<section class="panel">{sec("상대 — 보이는 만큼", "")}<div style="padding:8px 12px">{fieldrow("[적] 선봉", "기병 · 병력 [값]")}{fieldrow("[적] 중앙", "안개 속", cls="muted")}</div></section>'
             f'<section class="panel">{sec("다른 상태", "")}<div style="padding:8px;display:flex;flex-direction:column;gap:6px">'
             f'{warnbox("연결이 끊겼습니다 — 다시 잇는 중. 그동안 AI 가 맡습니다.")}{infobox("다시 이음 — 받지 못한 사건을 이어 받았습니다.")}</div></section></div>')
    body = (top + f'<div style="flex-grow:1;display:flex;gap:12px;padding:12px;min-height:0">{left}'
            f'<div style="flex:1 1 0;min-width:0;display:flex;justify-content:center">{live_board()}</div>{right}</div>' + cmdbar())
    page31('V31K6BattleLive.dc.html', 'K6 실시간 전투(데스크톱)', shell_desk('전투', 'corps', f'<main style="flex-grow:1;min-width:0;display:flex;flex-direction:column">{body}</main>'))


SIEGE_UNITS = [(28, 52, '하', 'me', '중앙 하후돈 — 조작 나, 성 안', False, True, False), (27, 57, '이', 'me', '좌익 이전 — 조작 AI', True, False, False),
               (32, 32, '적', 'enemy', '[적] 분대 — 다리 앞', False, False, False), (30, 23, '적', 'enemy', '[적] 분대 — 길 위', False, False, False)]


def board_mbattlelive():
    sq = ''.join(f'<button type="button" aria-pressed="{"true" if i == 1 else "false"}" style="flex:1 1 0;height:52px;padding:0;font:inherit;font-size:11px;color:#ece6d8;'
                 f'background:{"rgba(211,176,100,.14)" if i == 1 else "#141816"};border:{"2px solid #ffd36d" if i == 1 else "1px solid #3d4740"};display:flex;flex-direction:column;align-items:center;justify-content:center;gap:1px">'
                 f'<span class="serif" style="font-weight:700;font-size:13px">{n or "—"}</span><span class="muted">{p}</span></button>'
                 for i, (p, k, n, *_rest) in enumerate(SEATS))
    fs = 374 / 1024
    zs, zox, zoy = 1.1, -440, -100
    # 판 전체 위의 「지금 확대 창이 보는 곳」 — 확대 창(374 × 200, 1.1배)의 원본 범위를 판 전체 배율로
    rx, ry, rw, rh = (-zox / zs) * fs, (-zoy / zs) * fs, (374 / zs) * fs, (200 / zs) * fs
    full = iso_board('siege', 374, 191, fs, 0, 0, SIEGE_UNITS, small=True,
                     extra=f'<span style="position:absolute;left:{rx:.0f}px;top:{ry:.0f}px;width:{rw:.0f}px;height:{rh:.0f}px;border:2px solid #ffd36d"></span>')
    zoom = iso_board('siege', 374, 200, zs, zox, zoy, SIEGE_UNITS, [(28, 52, 'sel', '')], gate=(31, 42, '닫힘'))
    inner = (f'<div style="height:44px;display:flex;align-items:center;gap:10px;padding:0 12px;border-bottom:1px solid #3d4740">'
             f'<span class="mono bz" style="font-size:20px;font-weight:700">3:12</span><span class="t2" style="font-size:12px;flex:1">성새전 · 장사현 수비 · 적이 다리 앞에</span>{chip("AI 1", "")}</div>'
             f'<div style="padding:6px 8px 0">{full}</div>'
             f'<div style="padding:4px 8px 0;display:flex;flex-direction:column;gap:2px"><span class="muted" style="font-size:11px">누른 자리 확대 — 깃발 · 칸 · 성문을 눌러 고른다</span>{zoom}</div>'
             f'<div style="display:flex;gap:4px;padding:6px 8px 4px">{sq}</div>'
             f'<div style="padding:0 8px"><span class="t2" style="font-size:12px">중앙 하후돈 · 성 안 · <span class="ms">명령 받음</span> · 성문은 수비만 연다</span></div>'
             + cmdbar(mobile=True))
    page31('V31K6MBattleLive.dc.html', 'K6 실시간 전투 — 성새전(모바일)', mtop31('전투', '전투 목록') + mmain(inner, h=788), w=MW, h=MH)


FIELD_KIND = {'선봉 허저 — 조작 나': 'inf', '중앙 하후돈 — 조작 나': 'general', '좌익 이전 — 조작 AI': 'archer', '[적] 선봉 — 기병': 'cav', '[적] 분대 — 추정': None}
SIEGE_KIND = {'중앙 하후돈 — 조작 나, 성 안': 'general', '좌익 이전 — 조작 AI': 'archer', '[적] 분대 — 다리 앞': 'inf', '[적] 분대 — 길 위': 'cav'}


# B안 데스크톱은 원작 2배가 기본(K0 2026-09-30: 원작은 640×400을 화면 가득 봤다 — 지금 화면에서 「원작처럼」은 2배, 사용자 「크게크게 시원하게」).
# 절반 그림 기준 4배 = 원작 2배 → 유닛 64px. 「−」로 1배 · 전체 보기. 한 창(736 × 740)에 들도록 예시 칸을 가깝게 둔다.
FIELD_UNITS_B = [(34, 15, '허', 'me', '선봉 허저 — 조작 나', False, True, False), (34, 6, '하', 'me', '중앙 하후돈 — 조작 나', False, False, False),
                 (25, 10, '이', 'me', '좌익 이전 — 조작 AI', True, False, False), (36, 19, '적', 'enemy', '[적] 선봉 — 기병', False, False, False)]
B_S, B_OX, B_OY = 4.0, -1136, -1106


def live_board_units():
    s, ox, oy = B_S, B_OX, B_OY
    marks = [(34, 15, 'sel', ''), (35, 18, 'rally', '1'), (36, 19, 'target', '')]
    mini = board_mini('field', 200, -ox / s, -oy / s, 736 / s, 740 / s, FIELD_UNITS_B)
    tag = ('<span class="chip bronze" style="position:absolute;left:10px;top:10px;background:rgba(20,24,22,.94)">B안 — 원작 유닛 · 원작 2배(기본)</span>')
    zoomlab = ('<span class="chip" style="position:absolute;right:10px;bottom:176px;background:rgba(20,24,22,.94)">2배 · 「−」 1배 · 「전체」 판 전체</span>')
    return iso_board('field', 736, 740, s, ox, oy, FIELD_UNITS_B, marks, extra=mini + board_zoom() + zoomlab + tag, sprite_kind=FIELD_KIND, sprite_k=2)


def ab_cells(variant):
    """비교 칸 — 같은 네 분대를 판 위 제 자리 그림 조각(원작 2배) 위에. variant = 'A'(깃발) · 'B'(유닛 + 작은 깃발). 누르지 않는 그림."""
    out = ''
    for r, c, ch, side, label, ai, sel, est in FIELD_UNITS_B:
        x, y = iso_px('field', r, c, B_S, 0, 0)
        bg = mapimg('battle_field', int(1024 * B_S), int(544 * B_S), '', -(x - 32), -(y - 70))
        if variant == 'A':
            col = SIDE_COLOR[side]
            tok = (f'<svg width="30" height="26" viewBox="0 0 30 26" aria-hidden="true" style="position:absolute;left:18px;top:44px"><path d="M3 1v24" stroke="#1b201d" stroke-width="2"></path>'
                   f'<path d="M4 2h24l-6 7 6 7H4z" fill="{col}" stroke="#0c0f0e" stroke-width="1"></path>'
                   f'<text x="13" y="13" text-anchor="middle" font-family="Noto Serif KR,serif" font-weight="900" font-size="11" fill="#fff">{ch}</text></svg>')
        else:
            fl = (f'<svg width="30" height="21" viewBox="0 0 20 14" aria-hidden="true" style="position:absolute;left:18px;top:0"><path d="M2 0v14" stroke="#1b201d" stroke-width="1.5"></path>'
                  f'<path d="M3 1h15l-4 5 4 5H3z" fill="{SIDE_COLOR[side]}" stroke="#0c0f0e" stroke-width="1"></path>'
                  f'<text x="9" y="9" text-anchor="middle" font-family="Noto Serif KR,serif" font-weight="900" font-size="8" fill="#fff">{ch}</text></svg>')
            tok = fl + f'<span style="position:absolute;left:0;top:6px">{unit_sprite(FIELD_KIND.get(label), side, 2)}</span>'
        name = label.split(' — ')[0].replace('[적] ', '적 ')
        out += (f'<figure style="margin:0;display:flex;flex-direction:column;align-items:center;gap:2px">'
                f'<div aria-hidden="true" style="position:relative;width:64px;height:72px;overflow:hidden;border:1px solid #3d4740">{bg}{tok}</div>'
                f'<figcaption class="t2" style="font-size:10.5px;white-space:nowrap">{name}</figcaption></figure>')
    return f'<div style="display:flex;gap:4px;justify-content:space-between">{out}</div>'


def board_battlelive_units():
    top = (f'<div style="height:52px;flex-shrink:0;display:flex;align-items:center;gap:14px;padding:0 16px;border-bottom:1px solid #3d4740;background:#1b201d">'
           f'<span class="mono bz" style="font-size:24px;font-weight:700">3:12</span><span class="muted" style="font-size:12px">남음 · 틱 [값] / 3,000</span>'
           f'{chip("야전 · 영천 북쪽 구릉")}{chip("분대 표기 B안 — 사용자 선택 대기", "info")}'
           f'<span style="margin-left:auto;display:flex;gap:6px">{btn("전체에게", "sm")}{btn("나가기 — AI 에게 맡긴다", "sm")}</span></div>')
    squads = (squad('선봉', 'heojeo', '허저', '나', '돌격 중 · 보병', sel=True) + squad('중앙', 'hahoudon', '하후돈', '나', '대형 · 장수 기마')
              + squad('좌익', 'ijeon', '이전', 'AI', '후퇴 우선 · 궁병', note='사기 100 아래 — 명령을 받지 않는다'))
    left = (f'<section class="panel" style="width:300px;flex-shrink:0">{sec("우리 분대", "숫자키 1–6 · 누르면 고른다")}'
            f'<div style="padding:8px;display:flex;flex-direction:column;gap:6px">{squads}'
            f'<div style="border:1px dashed #3d4740;padding:8px 10px;font-size:12px" class="muted">좌비 · 우익 · 우비 — 빈 자리</div></div></section>')
    compare = (f'<div style="padding:8px 10px;display:flex;flex-direction:column;gap:6px">'
               f'<span class="bz" style="font-size:12px;font-weight:700">A — 깃발</span>{ab_cells("A")}'
               f'<span class="t2" style="font-size:11.5px">세력 · 장수가 먼저 보인다 — 전략 지도와 같은 표기</span>'
               f'<span class="bz" style="font-size:12px;font-weight:700;margin-top:4px">B — 원작 유닛 + 작은 깃발</span>{ab_cells("B")}'
               f'<span class="t2" style="font-size:11.5px">병종이 그림으로 보인다 — 보병은 작아 깃발로 보완</span></div>')
    right = (f'<div style="width:300px;flex-shrink:0;display:flex;flex-direction:column;gap:12px;min-height:0">'
             f'<section class="panel">{sec("A · B — 같은 2배", "사용자 선택 · K0 추천 B")}{compare}</section>'
             f'<section class="panel" style="flex-grow:1;min-height:0">{sec("사건", "")}<ul class="ul" style="padding:4px 12px">'
             f'<li><span class="mono muted">3:12</span> 적 선봉이 좌익에 닿았다</li><li><span class="mono muted">3:20</span> <b>명령 받음</b> — 허저 돌격</li>'
             f'<li><span class="mono muted">3:31</span> <span class="rs">명령 거절</span> — 이전 공격: 사기가 낮아 물러나는 중</li></ul></section></div>')
    body = (top + f'<div style="flex-grow:1;display:flex;gap:12px;padding:12px;min-height:0">{left}'
            f'<div style="flex:1 1 0;min-width:0;display:flex;justify-content:center">{live_board_units()}</div>{right}</div>' + cmdbar())
    page31('V31K6BattleLiveUnits.dc.html', 'K6 실시간 전투 — B안 원작 유닛(데스크톱)', shell_desk('전투', 'corps', f'<main style="flex-grow:1;min-width:0;display:flex;flex-direction:column">{body}</main>'))


def board_mbattlelive_units():
    sq = ''.join(f'<button type="button" aria-pressed="{"true" if i == 1 else "false"}" style="flex:1 1 0;height:52px;padding:0;font:inherit;font-size:11px;color:#ece6d8;'
                 f'background:{"rgba(211,176,100,.14)" if i == 1 else "#141816"};border:{"2px solid #ffd36d" if i == 1 else "1px solid #3d4740"};display:flex;flex-direction:column;align-items:center;justify-content:center;gap:1px">'
                 f'<span class="serif" style="font-weight:700;font-size:13px">{n or "—"}</span><span class="muted">{p}</span></button>'
                 for i, (p, k, n, *_rest) in enumerate(SEATS))
    fs = 374 / 1024
    zs, zox, zoy = 2.0, -1013, -250
    rx, ry, rw, rh = (-zox / zs) * fs, (-zoy / zs) * fs, (374 / zs) * fs, (300 / zs) * fs
    full = iso_board('siege', 374, 191, fs, 0, 0, SIEGE_UNITS, small=True,
                     extra=f'<span style="position:absolute;left:{rx:.0f}px;top:{ry:.0f}px;width:{rw:.0f}px;height:{rh:.0f}px;border:2px solid #ffd36d"></span>')
    zoom = iso_board('siege', 374, 300, zs, zox, zoy, SIEGE_UNITS, [(28, 52, 'sel', '')], gate=(31, 42, '닫힘'), sprite_kind=SIEGE_KIND)
    inner = (f'<div style="height:44px;display:flex;align-items:center;gap:10px;padding:0 12px;border-bottom:1px solid #3d4740">'
             f'<span class="mono bz" style="font-size:20px;font-weight:700">3:12</span><span class="t2" style="font-size:12px;flex:1">성새전 · 장사현 수비</span>{chip("B안", "info")}</div>'
             f'<div style="padding:6px 8px 0">{full}</div>'
             f'<div style="padding:4px 8px 0;display:flex;flex-direction:column;gap:2px"><span class="muted" style="font-size:11px">누른 자리 확대 · 원작 1:1 — 유닛 · 칸 · 성문을 눌러 고른다</span>{zoom}</div>'
             f'<div style="display:flex;gap:4px;padding:6px 8px 4px">{sq}</div>'
             + cmdbar(mobile=True))
    page31('V31K6MBattleLiveUnits.dc.html', 'K6 실시간 전투 — B안 원작 유닛(모바일)', mtop31('전투', '전투 목록') + mmain(inner, h=788), w=MW, h=MH)


def board_duel():
    body = (f'<div style="padding:0 16px 12px;display:flex;flex-direction:column;gap:12px">'
            f'<div style="display:flex;align-items:center;justify-content:space-around;gap:8px">'
            f'<div style="display:flex;flex-direction:column;align-items:center;gap:4px">{portrait("", "안량", 64, 88)}<span class="serif" style="font-weight:900">안량</span><span class="muted" style="font-size:11px">[적 세력] · 무력 —</span></div>'
            f'<span class="serif rs" style="font-size:22px;font-weight:900">대</span>'
            f'<div style="display:flex;flex-direction:column;align-items:center;gap:4px">{portrait("hahoudon", "하후돈", 64, 88)}<span class="serif" style="font-weight:900">하후돈</span><span class="muted" style="font-size:11px">조조 · 무력 —</span></div></div>'
            f'<span class="t2" style="font-size:13px;text-align:center">안량이 하후돈에게 일기토를 청합니다.</span>'
            f'<div style="display:flex;justify-content:center"><span class="mono bz" style="font-size:28px;font-weight:700">0:08</span></div>'
            f'<ul class="ul"><li>이기면 우리 쪽 사기 오름, 지면 내림(원장 값).</li><li>진행은 서버가 판정하고 연출만 보인다 — 도중 조작은 없다.</li>'
            f'<li>답하지 않으면 AI 가 원장 기준(무력 차이)으로 답한다.</li></ul></div>')
    foot = btn('거절', style='flex:1') + btn('받는다', 'primary', style='flex:1')
    inner = f'<div style="padding:6px 8px">{iso_board("field", 374, 199, 374 / 1024, 0, 0, FIELD_UNITS, small=True)}</div><div class="scrim"></div>{sheet("일기토 신청", body, top=150, foot=foot)}'
    page31('V31K6Duel.dc.html', 'K6 일기토 신청 받음(모바일)', mtop31('전투', '전투 목록') + mmain(inner, h=788), w=MW, h=MH)


def board_battleresult():
    steps = ''.join(f'<span style="display:flex;align-items:center;gap:6px">{chip(t, tone)}{"<span class=muted>→</span>" if i < 3 else ""}</span>'
                    for i, (t, tone) in enumerate([('전투 끝남', 'moss'), ('캠페인에 반영 중', 'bronze'), ('반영됨', ''), ('리플레이 공개', '')]))
    res_p = (f'<section class="panel" style="width:640px">{sec("결과 — 영천 북쪽 구릉 · 야전", "3,000틱 전 끝남")}'
             f'<div style="padding:16px;display:flex;flex-direction:column;gap:12px"><div style="display:flex;align-items:center;gap:12px">'
             f'<span class="serif ms" style="font-size:28px;font-weight:900">이겼습니다</span><span class="t2">[적 세력]이 물러났습니다.</span></div>'
             f'<div style="display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:6px">{kv("우리 잃음", "[값]")}{kv("적 잃음", "[값]")}{kv("포로", "[값]")}{kv("점령", "없음")}</div>'
             f'<div style="display:flex;gap:6px;flex-wrap:wrap;align-items:center">{steps}</div>'
             f'<span class="muted" style="font-size:12px;line-height:1.5">리플레이는 결과가 캠페인에 반영된 뒤 열린다(전투 계약). 반영되면 이 자리에 「리플레이 보기」가 켜진다.</span>'
             f'<div style="display:flex;gap:8px">{btn_off("리플레이 보기", "반영 중")}{btn("전투 목록으로", href="#")}</div></div></section>')
    blocked = (f'<section class="panel" style="width:640px">{sec("다른 상태 — 반영 막힘", "")}<div style="padding:12px">'
               f'{warnbox("결과를 캠페인에 적용하지 못했습니다 — 그 사이 군단 · 성 상태가 바뀌었습니다. 운영진이 확인 중이며 추측으로 적용하지 않습니다.")}</div></section>')
    body = f'<div style="flex-grow:1;display:flex;flex-direction:column;align-items:center;gap:12px;padding:24px;min-height:0">{res_p}{blocked}</div>'
    page31('V31K6BattleResult.dc.html', 'K6 전투 결과(데스크톱)', shell_desk('전투', 'corps', f'<main style="flex-grow:1;min-width:0;display:flex;flex-direction:column">{pagehead("군단", CORPS_SUB, "전투")}{body}</main>'))


# ================================================================== 시야 · 첩보(P-C06) — 「가짜」 · 「역정보」 표식을 두지 않는다(K0 · K8)
TIERS = [('영천군', 'FULL', '다 보임', '내 위치 · 영토', ''), ('하남윤', 'INTEL', '첩보', '4순 전', 'ok'), ('진류군', 'INTEL', '첩보', '2순 전', 'ok'),
         ('양국', 'FOG', '안 보임', '', 'ok'), ('여남군', 'FOG', '안 보임', '', 'no')]
TIER_TONE = {'FULL': 'moss', 'INTEL': 'info', 'FOG': ''}
HATCH = 'repeating-linear-gradient(135deg,rgba(12,15,14,.72) 0 5px,rgba(12,15,14,.38) 5px 10px)'


def tier_row(n, tier, lab, sub, scout):
    """군 한 줄 — 행은 단추가 아니다(안에 「첩보」 단추가 있으므로). 이름을 누르면 지도가 그 군으로 간다."""
    stat = chip(lab + (f' · {sub}' if tier == 'INTEL' else ''), TIER_TONE[tier])
    if scout == 'ok':
        act = btn('첩보', 'sm', attrs='data-input-id="action.scout"')
    elif scout == 'no':
        act = why('이웃 아님')
    else:
        act = ''
    note = sub if tier == 'FULL' else ('다시 첩보하면 갱신됩니다' if tier == 'INTEL' else '정찰이 닿지 않았다')
    return (f'<div role="listitem" style="min-height:52px;display:flex;align-items:center;gap:8px;padding:4px 8px 4px 4px;border-bottom:1px solid #2c342f">'
            f'<button type="button" style="flex:1;min-width:0;min-height:44px;display:flex;flex-direction:column;justify-content:center;gap:1px;padding:0 8px;font:inherit;text-align:left;color:#ece6d8;background:transparent;border:0;cursor:pointer">'
            f'<span class="serif" style="font-weight:700;font-size:15px">{n}</span><span class="muted" style="font-size:11.5px">{note}</span></button>{stat}{act}</div>')


def intel_rgns(pos, scale_box=None):
    out = ''
    for n, tier, lab, sub, sc in TIERS:
        x, y = pos[n]
        hatch = f'background:{HATCH};' if tier == 'FOG' else ''
        cls = {'FULL': 'ok', 'INTEL': 'ok', 'FOG': 'no'}[tier]
        out += (f'<button type="button" class="rgn {"sel" if n == "하남윤" else cls}" aria-label="{n} — {lab}" style="left:{x}px;top:{y}px;flex-direction:column;gap:0;padding:4px 12px;{hatch}'
                f'{"border-style:solid;border-color:#5a625c" if tier == "FOG" else ""}">'
                f'<span>{n}</span><span style="font-family:\'Noto Sans KR\',sans-serif;font-size:10.5px;font-weight:500;color:#b9b2a3">{lab}{" · " + sub if tier == "INTEL" else ""}</span></button>')
    return out


def board_intel():
    MAPW = 936
    pos = {'영천군': (430, 470), '하남윤': (200, 240), '진류군': (660, 230), '양국': (760, 470), '여남군': (640, 700)}
    mapst = (f'<main aria-label="지도 — 시야" style="position:relative;width:{MAPW}px;flex-shrink:0;overflow:hidden;background:#0c0f0e">'
             f'{mapimg("jun", 1680, 960, "영천 일대 — 군 보기", -420, -30)}{intel_rgns(pos)}{me_marker(500, 420, "in", tag=False)}'
             f'<div style="position:absolute;left:12px;top:12px;display:flex;gap:6px">{chip("다 보임", "moss")}{chip("첩보 · N순 전", "info")}'
             f'<span class="chip" style="background:{HATCH}">빗금 = 안 보임</span></div>{view_bar("군")}</main>')
    rows = ''.join(tier_row(*t) for t in TIERS)
    src = ''.join(fieldrow(a, b) for a, b in [('내 위치', '영천군 · 양적현'), ('내 군단', '영천군'), ('부 인물', '영천군 — 장사현'), ('영토', '영천군'),
                                                ('정찰 배치', '없음 — 배치에서 보낸다'), ('망루 · 봉화', '없음 — 공사에서 짓는다')])
    right = (f'<div style="width:{CONTENT_W - MAPW}px;flex-shrink:0;display:flex;flex-direction:column;gap:12px;padding:12px;min-height:0;overflow:hidden">'
             f'<section class="panel">{sec("군마다 시야", "첩보는 그때 본 것 · 만료 없음")}<div role="list" aria-label="군 시야" style="display:flex;flex-direction:column">{rows}</div></section>'
             f'<section class="panel">{sec("내 시야는 어디서", "반경은 원장 값")}<div style="padding:4px 12px 8px">{src}</div>'
             f'<div style="padding:0 12px 10px;display:flex;gap:6px">{btn("정찰 보내기", "sm", href="#")}{btn("망루 짓기", "sm", href="#")}</div></section>'
             f'<section class="panel" style="flex-grow:1">{sec("다른 상태", "")}<div style="padding:8px;display:flex;flex-direction:column;gap:6px">'
             f'{warnbox("장수가 물 위에 있어 첩보할 수 없습니다.")}{warnbox("시야를 계산하지 못했습니다. 잠시 뒤 다시 보세요.")}</div></section></div>')
    body = pagehead('군단', CORPS_SUB, '시야 · 첩보') + f'<div style="flex-grow:1;display:flex;min-height:0">{mapst}{right}</div>'
    page31('V31K6Intel.dc.html', 'K6 시야 · 첩보(데스크톱)', shell_desk('군단', 'corps', f'<main style="flex-grow:1;min-width:0;display:flex;flex-direction:column">{body}</main>'))


def board_mintel():
    pos = {'영천군': (195, 230), '하남윤': (92, 100), '진류군': (292, 100), '양국': (320, 300), '여남군': (250, 370)}
    rows = ''.join(tier_row(*t) for t in TIERS[:4])
    inner = (f'{mapimg("jun", 1478, 844, "영천 일대 — 군 보기", -227, -80)}{intel_rgns(pos)}{me_marker(262, 196, "in", tag=False)}'
             f'{sheet("군마다 시야", f"<div role=list aria-label=군 style=display:flex;flex-direction:column>{rows}</div>", height=300)}')
    page31('V31K6MIntel.dc.html', 'K6 시야 · 첩보(모바일)', shell_mob(mmain(inner), 'menu', '시야 · 첩보', '전체 메뉴'), w=MW, h=MH)


# ================================================================== 외교(P-K02) — 세력 외교 · 주변 세계(K8) · 외교 서신
DIP = [('원소', NATION['원소'], '교전', 'rust', '[값]부터', '', [('종전', 'court.offerPeace')]),
       ('유표', NATION['유표'], '불가침', 'moss', '[값]부터', '', [('원조', 'court.diplomacy'), ('불가침 파기', 'court.breakNonAggression')]),
       ('손책', '#b9b2a3', '관계 없음', '', '—', '', [('원조', 'court.diplomacy'), ('불가침 제의', 'court.nonAggression'), ('선전포고', 'court.declareWar')]),
       ('[세력]', '#8fa0ad', '선전포고 유예', 'bronze', '[미정]순 뒤 교전', '', [('종전', 'court.offerPeace')])]


def dip_row(name, color, rel, tone, since, _, acts, mobile=False):
    bs = ''.join(input_btn(t, 'NOT_DELIVERED', input_id=i, kind='sm') for t, i in acts)
    if mobile:
        return (f'<div style="padding:10px 12px;border-bottom:1px solid #2c342f;display:flex;flex-direction:column;gap:6px">'
                f'<div style="display:flex;align-items:center;gap:8px"><i class="dot" style="background:{color};width:10px;height:10px"></i><span class="serif" style="font-weight:900;font-size:15px">{name}</span>'
                f'{chip(rel, tone)}<span class="muted" style="font-size:11px;margin-left:auto">{since}</span></div><div style="display:flex;flex-wrap:wrap;gap:6px">{bs}</div></div>')
    return (f'<tr><td><span style="display:inline-flex;align-items:center;gap:8px"><i class="dot" style="background:{color};width:10px;height:10px"></i>'
            f'<span class="serif" style="font-weight:900">{name}</span></span></td><td>{chip(rel, tone)}</td><td class="t2">{since}</td>'
            f'<td><span style="display:flex;flex-wrap:wrap;gap:6px;justify-content:flex-end">{bs}</span></td></tr>')


def board_diplomacy():
    notice = infobox('<b>외교는 군주가 합니다</b> — 하후돈은 조조 소속이라 보기만 합니다. 제의 단추는 입력이 열리면 군주에게만 켜진다(지금은 모두 준비 중).')
    rows = ''.join(dip_row(*d) for d in DIP)
    table = (f'<section class="panel">{sec("조조와의 관계", "내 소속 세력 기준")}'
             f'<table class="table"><thead><tr><th>세력</th><th>관계</th><th>언제부터 · 기한</th><th style="text-align:right">제의</th></tr></thead><tbody>{rows}</tbody></table>'
             f'<div style="padding:8px 12px;display:flex;gap:8px;align-items:center"><span class="t2" style="font-size:12px">사자 — 외교에는 사자 적성 인물이 필요하다.</span>'
             f'{btn_off("사자 고르기", "사자 없음")}</div></section>')
    recv = f'<section class="panel" style="height:250px">{sec("받은 제의", "수락 · 거절")}{state_waiting("받은 제의 읽기 준비 중", "외교 제의와 응답 입력이 원장에 아직 없습니다(계약판 K6-05 보강). 옛 삼모 외교 서신의 수락 · 거절은 옮기지 않는다.", pad=10)}</section>'
    world = (f'<section class="panel" style="flex-grow:1;min-height:0">{sec("천하 관계", "다른 세력끼리는 교전 · 선포만 보인다")}<div style="padding:8px 12px">'
             + fieldrow('원소 ↔ [세력]', '교전', cls='rs') + fieldrow('유표 ↔ [세력]', '선전포고 유예', cls='bz')
             + f'</div><div style="padding:0 12px 10px">{btn("세력 × 세력 표로 보기", "sm")}</div></section>')
    mapst = (f'<main aria-label="지도 — 세력" style="position:relative;width:700px;flex-shrink:0;overflow:hidden;background:#0c0f0e">'
             f'{mapimg("prov", 1024, 892, "천하 개관 — 주 보기", -160, 0)}'
             + ''.join(f'<span class="mlab big" style="left:{x}px;top:{y}px">{t}</span>' for t, x, y in [('기주 · 원소', 330, 250), ('연주 · 예주 · 조조', 400, 380), ('형주 · 유표', 300, 560), ('양주 · 손책', 520, 600)])
             + f'<div style="position:absolute;left:12px;top:12px;display:flex;gap:6px">{chip("교전", "rust")}{chip("불가침", "moss")}{chip("선전포고 유예", "bronze")}</div>{view_bar("주")}</main>')
    right = (f'<div style="flex:1 1 0;min-width:0;display:flex;flex-direction:column;gap:12px;padding:12px;min-height:0;overflow:hidden">{notice}{table}'
             f'<div style="display:flex;gap:12px;flex-grow:1;min-height:0">{recv}{world}</div></div>')
    body = pagehead('외교', ['세력 외교', '주변 세계', '외교 서신'], '세력 외교') + f'<div style="flex-grow:1;display:flex;min-height:0">{mapst}{right}</div>'
    page31('V31K6Diplomacy.dc.html', 'K6 외교 — 세력 외교(데스크톱)', shell_desk('조정', 'court', f'<main style="flex-grow:1;min-width:0;display:flex;flex-direction:column">{body}</main>'))


def board_mdiplomacy():
    inner = (mtabs_row(['세력 외교', '주변 세계', '외교 서신'], '세력 외교')
             + f'<div style="padding:8px 12px">{infobox("외교는 군주가 합니다 — 보기만 합니다.")}</div>'
             + ''.join(dip_row(*d, mobile=True) for d in DIP)
             + f'<div style="padding:10px 12px;display:flex;gap:8px">{btn("지도로 보기", style="flex:1")}{btn("받은 제의", style="flex:1")}</div>')
    page31('V31K6MDiplomacy.dc.html', 'K6 외교(모바일)', shell_mob(mmain(inner), 'menu', '외교', '전체 메뉴'), w=MW, h=MH)


# ================================================================== 서신(P-Q02) · 서신 서랍 · 요청 카드
def request_card(kind, who_key, who, what, due, consequence, state='wait', compact=False):
    """요청 카드(RequestCard, K4 · K6 공용) — 발령 응답 · 정치 동의 · 외교 제의. 순을 쓰지 않는다 — 어디서 보든 그 자리에서 답한다."""
    iid = {'발령': 'court.dispatchReply', '정치 동의': 'court.politicalConsent'}.get(kind, '')
    acts = (f'<div style="display:flex;gap:6px">{btn("거절", "danger", style="flex:1", attrs=f"data-input-id=\"{iid}\"")}'
            f'{btn("수락", "primary", style="flex:1", attrs=f"data-input-id=\"{iid}\"")}</div>') if state == 'wait' else f'<div>{chip("수락함", "moss")}</div>'
    return (f'<div style="padding:10px 12px;border-bottom:1px solid #2c342f;display:flex;flex-direction:column;gap:8px">'
            f'<div style="display:flex;gap:10px;align-items:flex-start">{portrait(who_key, who, 30, 42)}<div style="display:flex;flex-direction:column;gap:3px;min-width:0;flex:1">'
            f'<span style="display:flex;gap:6px;align-items:center">{chip(kind, "bronze")}<span class="serif" style="font-weight:700">{who}</span></span>'
            f'<span style="font-size:13px">{what}</span><span class="muted" style="font-size:11px">응답 기한 {due}{" · 순을 쓰지 않는다" if not compact else ""}</span>'
            f'<span class="rs" style="font-size:11px">{consequence}</span></div></div>{acts}</div>')


REQS = [('발령', 'jojo', '조조', '하후돈을 진류군 태수로 발령', '[미정] — 지나면 수락', '거절하면 충성 ▼ 명망 ▼'),
        ('정치 동의', 'sunuk', '순욱', '하후돈과 결의를 청함', '[미정]', '거절해도 불이익 없음 — 결의가 열리지 않는다')]
MAILS = [('개인', '받음', '순욱', '관도 북쪽 소식', '3월 중순 21:12', True), ('개인', '보냄', '허저', '장사현 수비를 부탁한다', '3월 상순 20:40', False),
         ('세력', '받음', '조조', '영천 방면 모든 장수에게', '3월 상순 19:02', False), ('전체', '받음', '[장수]', '천하 모두에게', '2월 하순 18:30', False)]


def mail_row(tp, d, who, title, when, sel):
    dtone = 'info' if d == '받음' else ''
    return opt(title, f'{who} · {when}', chip(tp) + chip(d, dtone), sel=sel, h=56)


def board_mail():
    tabs = seg([('개인', 3), ('세력', None), ('전체', None), ('요청', 2)], '개인', '서신 묶음')
    left = (f'<section class="panel" style="width:360px;flex-shrink:0">{sec("서신", "외교 서신은 조정 › 외교")}<div style="padding:8px">{tabs}</div>'
            f'<div role="listbox" aria-label="서신 목록" style="display:flex;flex-direction:column;border-top:1px solid #2c342f">{"".join(mail_row(*m) for m in MAILS)}</div>'
            f'<div style="padding:10px 12px;display:flex;gap:8px">{btn("이전 서신", "sm")}<span class="muted" style="font-size:11px;align-self:center">더 없으면 「처음까지 봤습니다」</span></div></section>')
    read = (f'<section class="panel" style="flex:1 1 0;min-width:0">{sec("관도 북쪽 소식", "개인 · 받음 · 3월 중순 21:12")}'
            f'<div style="padding:16px;display:flex;flex-direction:column;gap:12px"><div style="display:flex;gap:10px;align-items:center">{portrait("sunuk", "순욱", 34, 48)}'
            f'<span style="display:flex;flex-direction:column"><span class="serif" style="font-weight:900">순욱</span><span class="muted" style="font-size:11.5px">조조 소속 · 허현 · 사람</span></span></div>'
            f'<div style="font-size:14px;line-height:1.7">관도 북쪽 나루에 원소 쪽 배가 모인다는 말이 있습니다. 하수를 건너기 전에 살펴 두십시오.</div>'
            f'<div style="display:flex;gap:8px">{btn("답장", "primary", "mail")}{btn("지우기", "sm")}<span class="muted" style="font-size:11px;align-self:center">내가 보낸 서신만 5분 안에 지울 수 있다</span></div></div></section>')
    person = (f'<button type="button" class="inp" style="cursor:pointer;text-align:left">{portrait("jojo", "조조", 22, 30)}<span class="serif" style="font-weight:700">조조</span>'
              f'<span class="muted" style="font-size:12px">조조 · 군주 · NPC</span><span style="margin-left:auto;color:#d3b064;font-size:12px">바꾸기</span></button>')
    write = (f'<section class="panel" style="width:400px;flex-shrink:0">{sec("서신 쓰기", "개인")}<div style="padding:12px;display:flex;flex-direction:column;gap:10px">'
             + field('받는 사람', person, '사람 고르기 — 내 부 · 소속 세력 · 다른 세력 군주 · 전체, NPC 포함')
             + infobox('NPC 에게 보내는 서신은 서버가 받을 준비 중입니다. NPC 가 어떻게 답하는지는 규칙이 승인되면 정해집니다.')
             + field('본문', '<div class="inp area" style="min-height:200px">주공께 — 영천 북쪽 방비를 살폈습니다.</div>', '글자 [미정]자까지 · 굵게 · 기울임 · 글자색')
             + f'<div style="display:flex;gap:8px;justify-content:flex-end">{btn_off("보내기", "준비 중")}</div></div></section>')
    body = pagehead('서신', None, None, btn('새 서신', 'primary', 'mail')) + f'<div style="flex-grow:1;display:flex;gap:12px;padding:12px;min-height:0">{left}{read}{write}</div>'
    page31('V31K6Mail.dc.html', 'K6 서신(데스크톱)', shell_desk('서신', 'plaza', f'<main style="flex-grow:1;min-width:0;display:flex;flex-direction:column">{body}</main>'))


def board_mmail():
    tabs = f'<div style="padding:8px 12px;border-bottom:1px solid #2c342f">{seg([("개인", 3), ("세력", None), ("전체", None), ("요청", 2)], "요청", "서신 묶음", style="flex-wrap:nowrap")}</div>'
    cards = ''.join(request_card(*r) for r in REQS)
    deny = f'<div style="border-top:1px solid #3d4740">{sec("다른 상태 — 볼 수 없음", "서버 401 · 403 · 빈 목록과 다른 모양")}</div><div style="height:190px;display:flex">{state_denied("이 서신함은 볼 수 없습니다", "로그인이 풀렸거나 내 서신함이 아닙니다. 다시 로그인해 보세요.", "서신", pad=8)}</div>'
    inner = tabs + cards + deny
    page31('V31K6MMail.dc.html', 'K6 서신 — 요청(모바일)', shell_mob(mmain(inner), 'war', '서신', '작전실'), w=MW, h=MH)


def board_maildrawer():
    DW = 400
    MAPW = CONTENT_W - DW
    hx, hy = DESK_PX(*CELLS[HERE])
    labs = ''.join(mlab(n, DESK_PX(*CELLS[n])[0], DESK_PX(*CELLS[n])[1] + 26, dim=False) for n in ['장사현', '영양현', '신정현'])
    mapst = (f'<main aria-label="지도" style="position:relative;width:{MAPW}px;flex-shrink:0;overflow:hidden;background:#0c0f0e">'
             f'{mapimg("desk", 1048, 952, "영천 일대 지도 — 현 보기")}{labs}{me_marker(hx, hy - 22, "in")}{view_bar()}</main>')
    tabs = f'<div style="padding:8px 12px">{seg([("개인", 3), ("세력", None), ("전체", None), ("요청", 2)], "요청", "서신 묶음")}</div>'
    quick = (f'<div style="padding:10px 12px;border-top:1px solid #3d4740;display:flex;flex-direction:column;gap:8px">'
             f'<span class="t2" style="font-size:12px">짧은 서신</span>{inp("", "받는 사람 고르기", ic="search")}'
             f'<div style="display:flex;gap:8px">{btn("보내기", "primary", style="flex:1")}{btn("서신에서 쓰기", href="#")}</div></div>')
    drawer = (f'<aside aria-label="서신 서랍" style="width:{DW}px;flex-shrink:0;display:flex;flex-direction:column;background:#1b201d;border-left:1px solid #9c7f3f;min-height:0">'
              f'<div style="height:52px;flex-shrink:0;display:flex;align-items:center;gap:10px;padding:0 4px 0 16px;border-bottom:1px solid #3d4740">'
              f'<span class="serif" style="font-size:18px;font-weight:900">서신</span>{chip("요청 2", "bronze")}'
              f'<a href="#" class="btn sm" style="margin-left:auto;background:transparent;border:0">전체 화면</a>{ibtn("close", "서랍 닫기", style="border:0;background:transparent")}</div>'
              f'{tabs}<div style="display:flex;flex-direction:column;border-top:1px solid #2c342f">{"".join(request_card(*r, compact=True) for r in REQS)}</div>'
              f'<div style="padding:8px 12px"><a href="#" style="font-size:12px;min-height:44px;display:inline-flex;align-items:center">조정에서 모두 보기 →</a></div>'
              f'<div style="margin-top:auto">{quick}</div></aside>')
    page31('V31K6MailDrawer.dc.html', 'K6 서신 서랍 — 요청 카드(데스크톱)', shell_desk('작전실', 'war', mapst + drawer))


# ================================================================== 입력 도달 경로 한 장(설계서 §4 요약 — K0 일관성 검사용)
# (inputId, 이름, 상태 ok|new|wait|k8, 새 경로 약어)
REACH = [
    ('action.enlist', '출사', 'ok', '흐름 · 입장'), ('action.deploy', '출병', 'ok', '흐름 · 지도카드 · 군단'), ('action.scout', '첩보', 'ok', '흐름 · 시야 · 지도카드'),
    ('action.assault', '강공', 'ok', '공성 · 흐름'), ('action.demandSurrender', '항복 권고', 'ok', '공성 · 흐름'), ('action.siegeRoadFort', '보루 포위', 'ok', '공성 · 흐름'),
    ('action.farm', '농지개간', 'ok', '흐름'), ('action.commerce', '상업투자', 'ok', '흐름'), ('action.fortify', '수비강화', 'ok', '흐름'),
    ('action.repairWall', '성벽보수', 'ok', '흐름'), ('action.security', '치안강화', 'ok', '흐름'), ('action.settle', '정착장려', 'ok', '흐름'),
    ('action.selectResidents', '주민선정', 'ok', '흐름'), ('action.tour', '순행', 'ok', '흐름'), ('action.conscript', '징병', 'ok', '흐름'),
    ('action.raiseVolunteers', '모병', 'ok', '흐름'), ('action.train', '훈련', 'ok', '흐름'), ('action.boostMorale', '사기진작', 'ok', '흐름'),
    ('action.demobilize', '소집해제', 'ok', '흐름'), ('action.muster', '집합', 'new', '흐름 · 군단'), ('action.search', '인재탐색', 'ok', '흐름'),
    ('action.employ', '등용', 'ok', '흐름 · 인물카드 · 부'), ('action.persuadeCaptive', '포로 설득', 'wait', '흐름 · 포로'), ('action.travel', '견문', 'ok', '흐름'),
    ('action.selfTrain', '단련', 'ok', '흐름'), ('action.recuperate', '요양', 'ok', '흐름'), ('action.retire', '은퇴', 'wait', '흐름'),
    ('action.convertProficiency', '병종 바꿔 익히기', 'ok', '흐름'), ('action.gift', '증여', 'ok', '흐름 · 인물카드'), ('action.donate', '헌납', 'wait', '흐름'),
    ('action.tradeGrain', '쌀 사고팔기', 'ok', '흐름'), ('action.tradeEquipment', '장비매매', 'wait', '흐름'), ('action.transport', '물자조달', 'ok', '흐름'),
    ('action.move', '이동', 'ok', '흐름 · 지도카드'), ('action.forcedMarch', '강행', 'ok', '흐름 · 지도카드'), ('action.return', '귀환', 'ok', '흐름'),
    ('action.resign', '하야', 'wait', '흐름'), ('action.rise', '거병', 'wait', '흐름'), ('action.independence', '독립', 'wait', '흐름'),
    ('action.foundState', '건국', 'ok', '흐름'), ('action.abdicate', '선양', 'ok', '흐름 · 인물카드'), ('action.oath', '결의', 'ok', '흐름 · 인물카드'),
    ('action.dissolve', '세력 해산', 'wait', '흐름'),
    ('court.dispatch', '발령', 'ok', '조정'), ('court.dispatchReply', '발령 응답', 'ok', '요청 · 조정'), ('court.reward', '포상', 'ok', '조정 · 인물카드'),
    ('court.politicalConsent', '정치 동의', 'new', '요청 · 조정'), ('court.releaseCorps', '군단 편성 해제', 'ok', '조정 · 군단'), ('court.abandonCounty', '현 포기', 'ok', '조정'),
    ('court.moveCapital', '천도', 'ok', '조정'), ('court.confiscate', '몰수', 'wait', '조정'), ('court.diplomacy', '원조', 'wait', '외교'),
    ('court.nonAggression', '불가침 제의', 'wait', '외교'), ('court.declareWar', '선전포고', 'wait', '외교'), ('court.offerPeace', '종전 제의', 'wait', '외교'),
    ('court.breakNonAggression', '불가침 파기', 'wait', '외교'), ('court.institution', '제도', 'k8', '조정 › 세력(K8)'),
    ('placement.assign', '배치', 'ok', '영지 · 부 · 시야 · 군단'), ('policy.set', '방침', 'ok', '영지 · 군단'), ('work.start', '공사', 'ok', '영지 · 시야'),
    ('work.reduce', '성방 감축', 'wait', '영지'),
    ('stratagem.play', '손패 카드 쓰기', 'wait', '계책'), ('stratagem.rumor', '유언', 'wait', '계책'), ('stratagem.steal', '탈취', 'wait', '계책'),
    ('stratagem.sabotage', '파괴', 'wait', '계책'), ('stratagem.fire', '화계', 'wait', '계책'), ('stratagem.flood', '수공', 'wait', '계책'),
    ('stratagem.falseReport', '허보', 'wait', '계책'), ('stratagem.raid', '급습', 'wait', '계책'), ('stratagem.reciprocity', '피장파장', 'wait', '계책'),
    ('stratagem.lastStand', '필사즉생', 'wait', '계책'), ('stratagem.mobilizePeople', '백성동원', 'wait', '계책'), ('stratagem.raiseMilitia', '의병모집', 'wait', '계책'),
    ('stratagem.provokeRivalry', '이호경식', 'wait', '계책'),
]
ST_CHIP = {'ok': ('동작', 'moss'), 'new': ('새로 닿음', 'bronze'), 'wait': ('준비 중', 'info'), 'k8': ('K8', '')}


def board_inputreach():
    assert len(REACH) == 74, len(REACH)
    cnt = {k: sum(1 for r in REACH if r[2] == k) for k in ST_CHIP}
    per = 25
    cols = ''
    for c in range(3):
        rows = ''.join(f'<div style="height:28px;display:grid;grid-template-columns:138px 112px minmax(0,1fr) 74px;gap:6px;align-items:center;padding:0 8px;border-bottom:1px solid #2c342f;font-size:11.5px">'
                       f'<span class="mono t2" style="white-space:nowrap;overflow:hidden;text-overflow:ellipsis">{i}</span><span class="serif" style="font-weight:700;white-space:nowrap">{n}</span>'
                       f'<span class="t2" style="white-space:nowrap;overflow:hidden;text-overflow:ellipsis">{name_wait(n)}{p}</span>{chip(*ST_CHIP[s])}</div>'
                       for i, n, s, p in REACH[c * per:(c + 1) * per])
        cols += f'<section class="panel" style="flex:1 1 0;min-width:0">{sec("입력", f"{c * per + 1}–{min(74, (c + 1) * per)}")}{rows}</section>'
    head = (f'<div style="display:flex;gap:8px;align-items:center;padding:0 16px;height:52px;border-bottom:1px solid #2c342f">'
            + chip('동작 %d' % cnt['ok'], 'moss') + chip('새로 닿음 %d' % cnt['new'], 'bronze') + chip('준비 중 %d' % cnt['wait'], 'info') + chip('K8 %d' % cnt['k8'])
            + f'<span class="t2" style="font-size:12px;margin-left:8px">지금 보낼 수 있는 입력 45 / 74 · 지도로 대상을 고르는 입력은 지금 첩보 하나 → 새 설계 18</span></div>')
    legend = (f'<div style="padding:8px 16px;display:flex;gap:14px;flex-wrap:wrap;font-size:11.5px" class="t2">'
              + ''.join(f'<span><b class="bz">{a}</b> {b}</span>' for a, b in [
                  ('흐름', '작전실 › 이번 순에 할 일'), ('지도카드', '지도 선택 카드 › 여기로 명령'), ('인물카드', '인물 카드 › 이 사람에게'), ('요청', '머리줄 서신 › 요청 · 조정 받은 요청 · 지난 순 서랍'),
                  ('조정', '조정 › 발령 · 포상(K4)'), ('영지', '영지 › 배치 · 방침 · 공사(K4)'), ('공성', '군단 › 공성(K4) → 흐름'), ('군단', '군단 · 세력 작전'),
                  ('시야', '군단 › 시야 · 첩보'), ('계책', '계책 덱 › 쓰기 · 걸기'), ('외교', '조정 › 외교')])
              + '<span class="muted">모바일은 같은 약어 — 하단 탭 · 전체 메뉴 · 시트(설계서 §4.1)</span></div>')
    body = (pagehead('입력 74개 — 어디서 보내나', None, None, chip('설계서 §4', 'info')) + head + legend
            + f'<div style="flex-grow:1;display:flex;gap:12px;padding:0 12px 12px;min-height:0">{cols}</div>')
    page31('V31K6InputReach.dc.html', 'K6 입력 도달 경로(요약)', shell_desk('입력 도달 경로', 'war', f'<main style="flex-grow:1;min-width:0;display:flex;flex-direction:column">{body}</main>'))


BOARDS = [board_command, board_command_edit, board_mcommand, board_mcommandargs, board_mpick,
          board_hand, board_mhand, board_stratagem, board_mstratagem,
          board_corps, board_mcorps,
          board_battles, board_mbattles, board_battlejoin, board_mbattlejoin, board_battlelive, board_mbattlelive, board_battlelive_units, board_mbattlelive_units, board_duel, board_battleresult,
          board_intel, board_mintel, board_diplomacy, board_mdiplomacy,
          board_mail, board_mmail, board_maildrawer, board_inputreach]

if __name__ == '__main__':
    for f in glob.glob(os.path.join(P, 'V31K6*.dc.html')):
        os.remove(f)
    for b in BOARDS:
        b()
    print(f'ok boards_v31_k6 — {len(BOARDS)} boards' + ('' if HAVE_ASSETS else ' (v31assets 없음: 그림 자리 표시)'))
