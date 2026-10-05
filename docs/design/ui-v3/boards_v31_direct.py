# 캔버스 v3.1 · 직속 명령 · 상관 건의(D60–D67) — K4 주관 묶음. K4 절(관계 · 진입) · K6 절(명령 흐름 · 수신함) · K8 절(건의).
# 요구: 메타 reports/opensamguk/tasks/2026-10-03-k4-direct-command-front-requirements.md · 2026-10-03-k8-petition-front-requirements.md,
#       C3 화면 결정 상세 2026-10-03-c3-direct-command-front-product-detail.md.
# 2026-10-06 원장 D127 승인 — 「[결정 대기]」 칸을 D128–D135 로 문구만 채웠다(새 칸 없음). 보류(P03 · P04 · P14 · P15 · P16 · H01)는 [값] · [미정],
# 원장 줄이 없는 「명령 책임」(D66 책임 규칙)만 [결정 대기]로 남긴다.
# 부품은 v31system(K3) · 레인 보드 조각(boards_v31_k4 · boards_v31_k6)만 부른다. 그 파일들은 고치지 않는다.
#   PYTHONPATH=<v31assets 폴더> python3 boards_v31_direct.py   → project/V31Direct*.dc.html (이 파일의 보드만 굽는다)
# 예시 상황: 하후돈(사람, 조조 소속) · 200년 3월 중순 · 영천군. 내 직속 상관 = 조조(NPC 군주). 내 직속 부하 = 허저 · 이전 · 무명 공조(NPC).
# 「받은 건의」 보드만 악진(사람)이 하후돈의 직속 부하라고 놓은 예시다. 정하지 않은 값은 [미정], 서버가 줄 값은 [값].
# inputId 는 하나도 확정되지 않았다(C1 원장 전) — 입력 단추에 data-input-id 를 달지 않는다.
import glob
import os

from v31system import *  # noqa: F401,F403
from v31system import CELLS, DESK_PX, MOB_PX, P
import boards_v31_k4 as k4
import boards_v31_k6 as k6

BOARDS = []


def board(fn):
    BOARDS.append(fn)
    return fn


MW, MH = 390, 844
BU = k4.BU
RET_TABS = k4.RET_TABS


# ------------------------------------------------------------------ 공용 조각
def pending(t=''):
    """결정 대기 표지 — 사용자 결정(C0 → CEO) 전이라 값 · 문구를 정하지 않은 칸. 점선 파랑."""
    tail = f' {t}' if t else ''
    return (f'<span class="chip" style="border-style:dashed;border-color:#7eabcb;color:#7eabcb;white-space:nowrap">[결정 대기]{tail}</span>')


def kvline(label, value, dim=False, changed=False):
    """두 칸 줄(32) — 흐린 줄은 바뀌지 않은 칸, 표시 줄은 바뀐 칸(고친 기록)."""
    st = 'opacity:1;color:#8e8879;' if dim else ''
    mark = f'<span class="chip bronze" style="height:20px">바뀜</span>' if changed else ''
    return (f'<div style="display:flex;justify-content:space-between;align-items:center;gap:8px;min-height:32px;border-bottom:1px solid #2c342f;font-size:12.5px;{st}">'
            f'<span class="{"muted" if dim else "t2"}">{label}</span><span style="display:flex;align-items:center;gap:6px;text-align:right">{mark}{value}</span></div>')


def who_line(key, name, sub, chips=''):
    return (f'<div style="display:flex;align-items:center;gap:10px">{portrait(key, name, 30, 42)}'
            f'<span style="display:flex;flex-direction:column;min-width:0;gap:2px"><span style="display:flex;align-items:center;gap:6px">'
            f'<span class="serif" style="font-weight:900;font-size:15px">{name}</span>{chips}</span><span class="muted" style="font-size:11.5px">{sub}</span></span></div>')


def rel_card(key, name, sub, chips, action):
    """관계 칸 한 사람 — 이름(인물 상세 고리) · 사람/NPC · 자리 · 근거 · 끝에 진입 단추."""
    return (f'<div style="display:flex;align-items:center;gap:10px;padding:8px 12px;min-height:60px;border-bottom:1px solid #2c342f">'
            f'{portrait(key, name, 30, 42)}<span style="display:flex;flex-direction:column;gap:2px;min-width:0;flex:1">'
            f'<span style="display:flex;align-items:center;gap:6px"><a href="#" class="serif" style="font-weight:900;font-size:14px;min-height:44px;min-width:44px;display:inline-flex;align-items:center;color:#ece6d8">{name}</a>{chips}</span>'
            f'<span class="muted" style="font-size:11.5px">{sub}</span></span>{action}</div>')


def order_btn(label='명령 내리기', style=''):
    """직속 명령 진입 — 원장 행이 생기기 전이라 inputId 없이 「준비 중」 점선(InputAction NOT_DELIVERED 모양)."""
    return input_btn(label, 'NOT_DELIVERED', style=style)


def petition_btn(label='건의 올리기', style=''):
    return input_btn(label, 'NOT_DELIVERED', style=style)


SUBS = [('heojeo', '허저', '양적현 · 대기', '[값]종'), ('ijeon', '이전', '장사현 · 현령', '[값]종'), ('', '무명 공조', '양적현 · 미배치', '[값]종')]


def relation_panel():
    """부 편성 오른쪽 「직속 관계」 — 내 상관 하나 · 직속 부하(한 단계만). 빈 목록은 서버 사유 셋으로 가른다."""
    boss = rel_card('jojo', '조조', '군주 · 근거 [근거] · 허현', chip('NPC') + chip('내 상관', 'bronze'), petition_btn())
    subs = ''.join(rel_card(k, n, f'{w} · 내릴 수 있는 명령 {c}', chip('NPC'), order_btn()) for k, n, w, c in SUBS)
    empty = (f'<div style="padding:8px 12px;display:flex;flex-direction:column;gap:4px">'
             f'<span class="muted" style="font-size:11.5px">부하가 없을 때는 서버 사유대로 따로 보인다 — 「직속 부하 없음」 · 「명령할 권한이 없습니다」 · 「아직 받지 못했습니다」</span>'
             f'<span class="muted" style="font-size:11.5px">사람 · NPC 를 모르면 「확인 중」 — NPC로 바꿔 쓰지 않는다</span></div>')
    return (f'<section class="panel" style="width:380px;flex-shrink:0">{sec("직속 관계", "한 단계만 · 서버 정본")}'
            f'<div style="padding:6px 12px 2px"><span class="t2" style="font-size:12px">내 상관</span></div>{boss}'
            f'<div style="padding:8px 12px 2px;display:flex;align-items:center;gap:6px"><span class="t2" style="font-size:12px">직속 부하 3</span>'
            f'<span class="muted" style="font-size:11px">이 목록의 인물 카드</span></div>{subs}{empty}'
            f'<div style="margin-top:auto;padding:10px 12px;border-top:1px solid #2c342f">'
            f'{help_strip("부하에게 내린 명령은 받아들이는 단계 없이 그 부하의 순에 실행됩니다. 상관에게는 건의를 올립니다.")}</div></section>')


# ================================================================== K4 — 부 편성 「직속 관계」 · 인물 상세 「관계」 · 진입
@board
def direct_retinue():
    lst = (f'<section class="panel" style="width:420px;flex-shrink:0">{sec("인물 카드", "직접 거느린 인물 3")}'
           f'<div style="padding:8px 12px">{seg(["등록순", "코스트", "충성", "이탈 판정"], "등록순", "정렬")}</div>'
           f'<div role="listbox" aria-label="부의 인물" style="border-top:1px solid #2c342f">'
           + ''.join(k4.person_list_row(*p, sel=(i == 0)) for i, p in enumerate(k4.PEOPLE_R)) + '</div></section>')
    det = (f'<section class="panel" style="flex:1;min-width:0">{sec("허저", "인물 카드 · 내 부 · NPC · 내 직속 부하")}'
           f'<div style="display:grid;grid-template-columns:140px minmax(0,1fr);gap:14px;padding:12px">{portrait("heojeo", "허저", 140, 198)}'
           f'<div style="display:flex;flex-direction:column;gap:10px;min-width:0">'
           f'<div style="display:flex;gap:6px;flex-wrap:wrap">{chip("유일", "bronze")}{chip("충성 높음", "moss")}{chip("직속 부하", "bronze")}</div>'
           f'{k4.stat_grid()}{k4.apt_grid()}</div></div>'
           f'<div style="padding:0 12px 10px;display:flex;flex-direction:column;gap:6px"><span class="t2" style="font-size:12px">이 인물에게 내릴 수 있는 명령</span>'
           f'<div style="display:flex;gap:6px;flex-wrap:wrap">{chip("출병")}{chip("이동")}{chip("훈련")}{chip("그 밖 [값]종")}</div>'
           f'<span class="muted" style="font-size:11.5px">서버가 이 부하에게 준 것만 · 행동별 권한 · 비용 · 충돌 표는 [미정]</span></div>'
           f'<div style="margin-top:auto;padding:10px 12px;display:flex;gap:8px;border-top:1px solid #2c342f">'
           f'{order_btn(style="flex:1")}{input_btn("자리에 배치", "AVAILABLE", input_id="placement.assign")}{btn("인물 상세", "", href="#")}</div></section>')
    head = pagehead(BU, RET_TABS, '편성 · 결속', btn('도움말', '', 'help')) + k4.renown_band()
    page31('V31DirectRetinue.dc.html', 'K4 직속 관계 — 부 편성 · 명령 내리기 진입(데스크톱)',
           shell_desk('부', 'retinue', k4.desk_main(head, lst + det + relation_panel())))


@board
def direct_mretinue():
    boss = (f'<a href="#" style="display:flex;align-items:center;gap:10px;min-height:56px;padding:6px 10px;border:1px solid #3d4740;background:#141816;color:#ece6d8">'
            f'{portrait("jojo", "조조", 30, 42)}<span style="display:flex;flex-direction:column;min-width:0;flex:1"><span class="muted" style="font-size:11px">내 상관</span>'
            f'<span style="display:flex;align-items:center;gap:6px"><span class="serif" style="font-weight:900">조조</span>{chip("NPC")}{chip("군주")}</span></span>'
            f'<span style="align-self:center">{icon("next", 18, "#8e8879")}</span></a>')
    inner = (f'<div style="display:flex;align-items:center;gap:8px;padding:8px 10px;background:#141816;border:1px solid #2c342f">'
             f'<span class="t2" style="font-size:12px">명망</span><span class="mono bz" style="font-weight:700">[미정]</span>'
             f'<div class="g-bar" style="flex:1"><i style="width:86%"></i></div>{chip("상한 초과", "rust")}</div>'
             f'{boss}{seg([("인물", 3), ("직속 부하", 3), ("부대", 2), ("결속", None)], "직속 부하", "보기", style="flex-shrink:0")}'
             f'<div role="listbox" aria-label="직속 부하" style="border-top:1px solid #2c342f">'
             + ''.join(k4.person_list_row(*p, mobile=True) for p in k4.PEOPLE_R) + '</div>'
             + f'<span class="muted" style="font-size:11.5px">부하를 누르면 인물 상세 — 「명령 내리기」는 그 화면 아래 줄.</span>')
    foot = f'{input_btn("인재탐색", "AVAILABLE", input_id="action.search", kind="", style="flex:1")}{input_btn("등용", "AVAILABLE", input_id="action.employ", style="flex:1")}'
    page31('V31DirectMRetinue.dc.html', 'K4 직속 관계 — 부 편성(모바일)',
           shell_mob(k4.mob_main(inner, RET_TABS, '편성 · 결속', foot=foot, first=BU), 'retinue', None, None), w=MW, h=MH)


@board
def direct_person():
    hero = (f'<section class="panel" style="width:360px;flex-shrink:0">'
            f'<div style="padding:12px;display:flex;justify-content:center">{portrait("heojeo", "허저", 220, 312)}</div>'
            f'<div style="padding:0 16px 10px;display:flex;flex-direction:column;gap:8px"><span class="serif" style="font-size:24px;font-weight:900">허저</span>'
            f'<div style="display:flex;gap:6px;flex-wrap:wrap">{chip("NPC")}{chip("내 부")}{chip("직속 부하", "bronze")}{chip("충성 높음", "moss")}</div></div>'
            f'<div style="margin-top:auto;padding:10px 12px;display:flex;flex-direction:column;gap:8px;border-top:1px solid #2c342f">'
            f'{order_btn(style="width:100%")}{input_btn("자리에 배치", "AVAILABLE", input_id="placement.assign", kind="", style="width:100%")}'
            f'{btn("부 편성에서 보기", "", style="width:100%", href="#")}</div></section>')
    rel = (f'<div style="padding:8px 12px">'
           + kvline('나와의 관계', f'내 직속 부하 {chip("한 단계")}')
           + kvline('관계 근거', '[근거] — 표시용, 권한 근거와 따로')
           + kvline('내릴 수 있는 명령', '[값]종 — 서버가 준 것만')
           + kvline('지금 받은 명령', '없음')
           + f'</div><div style="padding:0 12px 10px"><span class="muted" style="font-size:11.5px">관계가 바뀌면 새 상관에게 옛 명령 · 건의를 보이지 않습니다.</span></div>')
    grid = (f'<div style="flex:1;min-width:0;display:grid;grid-template-columns:repeat(2,minmax(0,1fr));grid-template-rows:auto auto minmax(0,1fr);gap:12px">'
            f'{k4.panel("능력", "숫자 그대로", "<div style=padding:10px>" + k4.stat_grid() + "</div>")}'
            f'{k4.panel("역할 적성", "장 · 리 · 사 · 사자", "<div style=padding:10px>" + k4.apt_grid() + "</div>")}'
            f'{k4.panel("관계", "직속 상관 · 부하 — 서버 정본", rel)}'
            f'{k4.panel("자리 · 상태", "", "<div style=padding:10px>" + k4.state8() + "</div>")}'
            f'<section class="panel" style="grid-column:1 / span 2;display:flex;flex-direction:column">{sec("이 부하에게 내린 명령", "보낸 명령 — 수신함과 같은 카드")}'
            f'{state_empty("아직 내린 명령이 없습니다", "「명령 내리기」로 명령 흐름을 열면 대상이 허저로 채워집니다.", pad=10)}</section></div>')
    head = pagehead(BU, RET_TABS, '인물 일람', btn('← 인물 일람', '', href='#'))
    page31('V31DirectPerson.dc.html', 'K4 인물 상세 — 직속 부하 · 관계 칸 · 명령 내리기(데스크톱)',
           shell_desk('부', 'retinue', k4.desk_main(head, hero + grid)))


@board
def direct_mperson():
    hero = f'<div style="height:180px;flex-shrink:0;border:1px solid #9c7f3f">{portrait("jojo", "조조", 364, 178)}</div>'
    rel = (kvline('나와의 관계', '내 직속 상관') + kvline('관계 근거', '[근거]') + kvline('사람 · NPC', 'NPC — 건의는 자기 순에 규칙으로 판단')
           + kvline('보낸 건의', '1건 — 판단 대기'))
    inner = (f'{hero}<div style="display:flex;align-items:baseline;gap:8px"><span class="serif" style="font-size:22px;font-weight:900">조조</span></div>'
             f'<div style="display:flex;gap:6px;flex-wrap:wrap">{chip("NPC")}{chip("군주")}{chip("내 상관", "bronze")}</div>'
             f'<section class="panel">{sec("관계", "서버 정본")}<div style="padding:4px 12px 8px">{rel}</div></section>'
             f'<span class="muted" style="font-size:11.5px">같은 내용의 건의가 대기 중이면 「이미 올린 건의가 있습니다」로 막힌다.</span>')
    foot = f'{btn("서신 쓰기", "", "mail", style="flex:1")}{petition_btn(style="flex:1")}'
    page31('V31DirectMPerson.dc.html', 'K4 인물 상세 — 내 상관 · 건의 올리기(모바일)',
           shell_mob(k4.mob_main(inner, foot=foot), 'retinue', '조조', '인물 일람'), w=MW, h=MH)


# ================================================================== K6 절 — 직속 명령 흐름
def who_pick(mobile=False, open_=False):
    """「누가」 — 흐름 머리 sub 자리(K6 §2.1, CommandFlow 「이번 순에 할 일 · {generalName}」). 나 / 직속 부하. 진입점에서 미리 채운다."""
    menu = ''
    if open_:
        rows = (opt('하후돈(나)', '내 12순', h=48) + opt('허저', '직속 부하 · NPC · 양적현', ok_chip('명령 [값]종'), sel=True, h=48)
                + opt('이전', '직속 부하 · NPC · 장사현', ok_chip('명령 [값]종'), h=48) + opt('무명 공조', '직속 부하 · NPC · 양적현', ok_chip('명령 [값]종'), h=48))
        menu = (f'<div role="listbox" aria-label="누가 할까" style="position:absolute;left:{12 if mobile else 56}px;top:{44 if mobile else 50}px;width:300px;z-index:20;'
                f'background:#1b201d;border:1px solid #9c7f3f;box-shadow:0 10px 28px rgba(0,0,0,.5);display:flex;flex-direction:column">{rows}</div>')
    return (f'<button type="button" class="btn sm" aria-haspopup="listbox" aria-expanded="{"true" if open_ else "false"}" style="border-color:#9c7f3f;color:#ffd36d">'
            f'{portrait("heojeo", "허저", 18, 26)}허저 · 직속 부하 ▾</button>', menu)


def flow_head_who(open_=False):
    b, menu = who_pick(open_=open_)
    return (f'<div style="height:52px;flex-shrink:0;display:flex;align-items:center;gap:10px;padding:0 4px 0 16px;border-bottom:1px solid #3d4740;position:relative;'
            f'background:linear-gradient(180deg,#232a26,#1b201d)">{portrait("hahoudon", "하후돈", 26, 36)}'
            f'<span class="serif" style="font-size:18px;font-weight:900">이번 순에 할 일</span>{b}'
            f'<button type="button" class="ibtn" aria-label="닫고 12순으로(Esc)" style="margin-left:auto;border:0;background:transparent">{icon("close")}</button>{menu}</div>')


def two_slots():
    return k6.inset('<span class="muted" style="font-size:11px">순 — 두 칸(상관 1순 + 부하 1순)</span>'
                    + k6.fieldrow('내 발행 순', '04순 · 4월 중순 00:40', cls='bz')
                    + f'<div style="display:flex;justify-content:space-between;align-items:center;gap:8px;min-height:32px;border-bottom:1px solid #2c342f;font-size:12.5px">'
                    f'<span class="t2">허저 실행 순</span><span style="display:flex;gap:6px;align-items:center">[값]<span class="muted" style="font-size:11px">서버가 정함 · 발행 뒤 잠기지 않은 다음 순</span></span></div>')


def prejudge():
    """미리 판정 — 서버 값. 부하 예약과 겹침(D60) · 다른 상관 명령과 겹침(D63)을 한 상자에."""
    return (f'<div style="display:flex;flex-direction:column;gap:4px;padding:8px 10px;border:1px solid #9c7f3f;background:rgba(211,176,100,.08)">'
            f'<span style="display:flex;align-items:center;gap:6px"><span class="t2" style="font-size:12px;font-weight:700">미리 판정 — 서버 값</span>{ok_chip("가능")}</span>'
            f'<span class="t2" style="font-size:12px;line-height:1.45">허저의 그 순에 원래 예약이 있습니다 — 이 명령이 우선합니다. 예약 내용은 허저만 봅니다.</span>'
            f'<span class="t2" style="font-size:12px;line-height:1.45">다른 상관의 명령과 겹치면 더 높은 상관의 명령이 대신합니다.</span>'
            f'<span class="muted" style="font-size:11.5px">우선권 세부 [미정]</span></div>')


@board
def direct_order():
    OX = 100
    MAPW = 1384 - k6.FLOW_W
    hx, hy = DESK_PX(*CELLS[HERE]); hx -= OX
    pick = '번창현'
    px, py = DESK_PX(*CELLS[pick]); px -= OX
    marks = ''
    for n, sub, st, r in CANDS:
        x, y = DESK_PX(*CELLS[n])
        if x - OX < 20 or x - OX > MAPW - 20:
            continue
        marks += mk(x - OX, y, 'sel' if n == pick else st, n, 'no' if st == 'no' else '')
    mapst = (f'<main aria-label="지도 — 허저 출병 목적지 고르는 중" style="position:relative;width:{MAPW}px;flex-shrink:0;overflow:hidden;background:#0c0f0e">'
             f'{mapimg("desk", 1048, 952, "영천 일대 지도 — 현 보기", -OX, 0)}<div class="dim"></div>'
             f'{path_line(hx, hy, px, py, MAPW, 952, dist(pick) + " · [미정]순")}{marks}{me_marker(hx, hy - 22, "in", tag=False)}'
             f'{pick_bar("허저 출병 목적지 고르기", "허저가 갈 수 있는 곳만 · 불가도 누르면 이유")}{view_bar()}</main>')
    rows = (cmd_row('출병', '부하의 부곡을 이끌고 나간다', 'ok', sel=True, draft=True)
            + cmd_row('이동', '부하를 옮긴다', 'ok')
            + cmd_row('훈련', '부하 부대의 훈련', 'ok')
            + cmd_row('첩보', '이웃 군을 살핀다', 'ok')
            + cmd_row('징병', '성 안에서 병사를 모은다', 'no', '허저는 성 밖')
            + cmd_row('그 밖', '서버가 준 [값]종', 'wait'))
    foot = k6.boardnote('직속 명령 행동 전체(서버 목록) · 막힌 것은 사유와 함께. 행동별 권한 · 비용 · 충돌 표는 C3/C5. 예약 단추는 열린 뒤 모습 — 원장 행 전에는 「준비 중」 점선.')
    bug = (f'<div role="group" aria-label="허저의 부곡 — 내가 고른다" style="display:flex;flex-direction:column;border:1px solid #2c342f">'
           f'{checkbox("허저 부곡 1 · 보병 · 병력 [값] · 훈련 [값] · 양적현", True)}{checkbox("허저 부곡 2 · 기병 · 병력 [값] · 훈련 [값] · 양적현", False)}'
           f'<button type="button" class="opt" aria-disabled="true" aria-haspopup="dialog" style="min-height:44px"><span class="sub" style="font-size:12.5px">허저 부곡 3 · 장사현</span>'
           f'<span class="end">{why_tag("다른 현")}</span></button></div>')
    args = (k6.args_head('출병', '04순', '허저에게 · 내 발행 순') + help_strip('허저가 받아들이는 단계 없이 허저의 순에 실행됩니다.')
            + field('부곡 — 허저 것 중 내가 고름', bug)
            + target_field('목적지(구역)', pick, '영천군 · ' + dist(pick))
            + two_slots() + prejudge()
            + f'<div style="margin-top:auto;display:flex;flex-direction:column;gap:6px">'
            f'<span class="t2" style="font-size:12px">접수 ≠ 발행 — 04순에 명령이 나갑니다.</span>'
            + btn('04순에 명령 예약', 'primary', style='width:100%') + '</div>')
    aside = (f'<aside aria-label="이번 순에 할 일 — 직속 명령" style="width:{k6.FLOW_W}px;flex-shrink:0;display:flex;flex-direction:column;background:#1b201d;border-left:1px solid #9c7f3f;min-height:0">'
             f'{flow_head_who(open_=True)}<div style="flex-shrink:0;padding:6px 16px 0" class="muted"><span style="font-size:11px">순 띠 = 내 발행 순(상관 1순) · 허저 실행 순은 인자 칸</span></div>{k6.flow_strip(4)}'
             f'<div style="flex-grow:1;display:flex;min-height:0">{k6.flow_list(rows, foot, "허저에게 내릴 명령")}{k6.flow_args(args)}</div></aside>')
    page31('V31DirectOrder.dc.html', 'K6 절 직속 명령 흐름 — 허저에게 출병(데스크톱)', shell_desk('작전실', 'war', mapst + aside))


@board
def direct_morder():
    head = (f'<div style="display:flex;align-items:center;gap:6px;padding:0 12px 0 4px;height:44px;border-bottom:1px solid #2c342f">'
            f'<a href="#" class="btn sm" style="background:transparent;border:0">{icon("back", 16)}명령 목록</a>'
            f'<span style="margin-left:auto">{turn_caption(4)}</span></div>')
    bug = (f'<div role="group" aria-label="허저의 부곡 — 내가 고른다" style="display:flex;flex-direction:column;border:1px solid #2c342f">'
           f'{checkbox("허저 부곡 1 · 보병 · 병력 [값] · 훈련 [값]", True)}{checkbox("허저 부곡 2 · 기병 · 병력 [값] · 훈련 [값]", False)}</div>')
    who, _ = who_pick(mobile=True)
    body = (f'{head}<div style="padding:8px 12px 0;display:flex;align-items:center;gap:8px"><span class="t2" style="font-size:12px">누가</span>{who}</div>'
            f'<div style="padding:10px 12px;display:flex;flex-direction:column;gap:8px">'
            f'<div style="display:flex;align-items:baseline;gap:8px"><span class="serif" style="font-size:22px;font-weight:900">출병</span>{chip("04순", "bronze")}'
            f'<span class="muted" style="font-size:11.5px">내 발행 순</span></div>'
            + field('부곡 — 내가 고름', bug)
            + field('목적지', f'<button type="button" class="inp" style="cursor:pointer;text-align:left;border-color:#ffd36d">{icon("target", 18, "#ffd36d")}'
                            f'<span class="serif" style="font-weight:700">번창현</span><span class="muted" style="font-size:12px">{dist("번창현")}</span>'
                            f'<span style="margin-left:auto;color:#d3b064;font-size:12px">지도에서</span></button>')
            + two_slots() + prejudge() + '</div>')
    foot = btn('04순에 명령 예약', 'primary', style='flex:1')
    main = (f'<main style="position:relative;width:390px;height:844px;overflow:hidden">{mapimg("mob", 390, 844, "양적 일대 지도")}'
            f'<div class="scrim"></div>{sheet("허저에게 명령", body, top=40, foot=foot)}</main>')
    page31('V31DirectMOrder.dc.html', 'K6 절 직속 명령 흐름 — 허저에게 출병(모바일)', main, w=MW, h=MH)


# ================================================================== K6 절 — 수신함(받은 명령 · 보낸 명령 · 건의 카드)
def cmd_card(kind, who_key, who, what, due, st, tone, foot='', note_=''):
    """요청 카드 틀(RequestCard, v31system.request_card 3.1.2 와 같은 머리) — 명령 · 건의는 응답(거절 | 수락)을 쓰지 않고 상태 칩만(K6 §2.2).
    보낸 명령은 foot 에 「명령 취소」(D64)."""
    n = f'<span class="t2" style="font-size:12px">{note_}</span>' if note_ else ''
    head = (f'<div style="display:flex;gap:10px;padding:10px 12px">{portrait(who_key, who, 30, 42)}<div style="display:flex;flex-direction:column;gap:3px;min-width:0;flex:1">'
            f'<div style="display:flex;align-items:center;gap:6px">{chip(kind, "bronze")}<span class="serif" style="font-weight:700;font-size:14px">{who}</span>'
            f'<span class="mono muted" style="font-size:11px;margin-left:auto;white-space:nowrap">{due}</span></div>'
            f'<span style="font-size:13px">{what}</span><span style="display:flex;gap:6px;align-items:center">{chip(st, tone)}{n}</span></div></div>')
    f = f'<div style="display:flex;gap:6px;padding:0 12px 10px">{foot}</div>' if foot else ''
    return f'<article class="req" aria-label="{kind} — {who}">{head}{f}</article>'


RECV = [('받은 명령', 'jojo', '조조', '출병 · 진류군 방면', '실행 [값]순', '발행됨', 'bronze', '원예약 보존됨 — 나만 봅니다'),
        ('받은 건의', '', '악진', '출병 건의 · 영양현 방면', '[값]순 뒤 만료', '판단 대기', 'info', '')]
# 보낸 명령은 대상 순 잠금 전까지 취소(D64) — 접수 · 발행됨 둘 다. 실행 못 함은 사유만(원예약 줄은 부하 쪽에만, D60).
SENT = [('보낸 명령', 'heojeo', '허저', '출병 · 번창현 · 발행 04순', '잠금 [시각]', '접수', 'info', ''),
        ('보낸 명령', '', '무명 공조', '이동 · 양성현', '잠금 [시각]', '발행됨', 'bronze', ''),
        ('보낸 명령', 'heojeo', '허저', '훈련 · [순]', '[순]', '교체됨', '', '조조의 명령으로 · [사유]'),
        ('보낸 명령', 'ijeon', '이전', '훈련 · 장사현', '[순]', '실행 못 함', 'rust', '[사유]'),
        ('보낸 건의', 'jojo', '조조', '출병 건의 · 진류군 방면', '[값]순 뒤 만료', 'NPC 판단 대기', 'info', '')]
CANCELABLE = ('접수', '발행됨')


def recv_cards(sel=None):
    out = ''
    for r in RECV:
        c = cmd_card(*r[:7], note_=r[7])
        if sel and r[2] == sel[0] and r[3] == sel[1]:
            c = c.replace('class="req"', 'class="req" style="outline:2px solid #d3b064;outline-offset:-2px"', 1)
        out += c
    return out


def sent_cards(rows=None):
    out = ''
    for r in (rows or SENT):
        foot = btn('명령 취소', 'danger', style='flex:1') if r[5] in CANCELABLE else ''
        out += cmd_card(*r[:7], foot=foot, note_=r[7])
    return out


def req_tabs(side='받은 것', mobile=False):
    # 탭 수 = 내가 답해야 할 요청 수(useRequests.waiting) — 발령 · 정치 동의 · 받은 건의 판단만. 받은 명령은 답이 없어 세지 않는다(K6).
    t = seg([('개인', 3), ('세력', None), ('전체', None), ('요청', 1)], '요청', '서신 묶음', style='flex-wrap:nowrap' if mobile else '')
    s2 = seg(['받은 것', '보낸 것'], side, '요청 — 받은 것 · 보낸 것')
    return (f'<div style="padding:8px {12 if mobile else 8}px;display:flex;flex-direction:column;gap:6px;border-bottom:1px solid #2c342f">{t}{s2}'
            f'<span class="muted" style="font-size:11px">요청 탭 안 「받은 것 | 보낸 것」 — 발령 · 정치 동의 요청과 같은 목록</span></div>')


def inbox_panel(side='받은 것', cards='', w=400, title='서신'):
    return (f'<section class="panel" style="width:{w}px;flex-shrink:0">{sec(title, "요청 — " + side)}{req_tabs(side)}'
            f'<div style="display:flex;flex-direction:column">{cards}</div></section>')


def slot_band():
    """부하의 순 띠(K6 §3.1) — 실행 순 칸 = 상관 명령(잠김) + 원예약 「보존됨」 한 줄."""
    cell = (f'<div style="display:flex;flex-direction:column;gap:3px;padding:8px 10px;border:1px solid #9c7f3f;background:rgba(211,176,100,.08)">'
            f'<span style="display:flex;align-items:center;gap:6px"><span class="mono bz" style="font-weight:700">[값]순</span>'
            f'<span class="serif" style="font-weight:700">출병</span><span class="muted" style="font-size:11.5px">조조의 명령</span>{chip("잠김", "bronze")}</span>'
            f'<span class="muted" style="font-size:11.5px">원래 예약 「훈련」 — 보존됨 · 나만 봅니다</span></div>')
    return (f'<div style="display:flex;flex-direction:column;gap:4px"><span class="t2" style="font-size:12px">내 12순의 그 칸</span>{cell}'
            f'<a href="#" style="font-size:12px;min-height:44px;display:inline-flex;align-items:center">내 12순에서 보기 →</a></div>')


@board
def direct_inbox():
    recv = (f'<section class="panel" style="flex:1 1 0;min-width:0">{sec("받은 명령 — 조조", "발행됨 · 실행 [값]순")}'
            f'<div style="padding:12px 16px;display:flex;flex-direction:column;gap:10px">'
            f'{who_line("jojo", "조조", "군주 · 내 직속 상관 · NPC — 내가 올린 건의를 채택한 명령", chip("NPC"))}'
            + k6.inset(kvline('행동', '출병') + kvline('목적지', '진류군 방면 [구역]') + kvline('부곡', '하후돈 부곡 1 · 2 — 상관이 고름')
                       + kvline('상태', chip('발행됨', 'bronze') + ' → 잠김 · 실행됨 · 실행 못 함'))
            + slot_band()
            + f'<span class="t2" style="font-size:12.5px;line-height:1.5">원래 예약은 지우지 않았습니다. 이 명령이 실행 못 하면 원래 예약대로 갑니다. 새로 다시 예약되지는 않습니다.</span>'
            f'<span class="muted" style="font-size:12px;line-height:1.5">받아들이거나 거절하는 단추는 없습니다 — 직속 상관의 명령은 그대로 실행됩니다. 읽음 표시는 동의가 아닙니다.</span>'
            f'<a href="#" style="font-size:12px;min-height:44px;display:inline-flex;align-items:center">원 건의 보기 →</a>'
            f'<div style="display:flex;flex-direction:column;gap:2px"><span class="t2" style="font-size:12px">이 순의 명령 기록</span>'
            + kvline('[순] 조조', '출병 · 진류군 방면 — 지금 명령')
            + f'<span style="display:flex;gap:6px;align-items:center;margin-top:4px"><span class="muted" style="font-size:11px">다른 상관 명령으로 바뀐 기록 — 상대 이름 · 시점 · 사유만 · 우선권 세부 [미정]</span></span></div>'
            f'<span style="display:flex;gap:6px;align-items:center"><span class="muted" style="font-size:11px">NPC 상관도 건의 없이 먼저 명령합니다 — 판단 규칙 · 빈도 [미정]</span></span>'
            f'</div></section>')
    sent = inbox_panel('보낸 것', sent_cards() + '<div style="padding:8px 12px"><span class="muted" style="font-size:11px">명령 취소는 되돌릴 수 없어 한 번 묻는다(군단 편성 해제와 같은 확인 대화). 취소 단추는 열린 뒤 모습 — 원장 행 전에는 준비 중 점선.</span></div>', w=380, title='같은 탭 — 보낸 것')
    body = (pagehead('서신', None, None, btn('새 서신', 'primary', 'mail'))
            + f'<div style="flex-grow:1;display:flex;gap:12px;padding:12px;min-height:0">{inbox_panel("받은 것", recv_cards(("조조", "출병 · 진류군 방면")))}{recv}{sent}</div>')
    page31('V31DirectInbox.dc.html', 'K6 절 수신함 — 요청 탭 받은 것 · 보낸 것, 받은 명령(데스크톱)',
           shell_desk('서신', 'plaza', f'<main style="flex-grow:1;min-width:0;display:flex;flex-direction:column">{body}</main>'))


@board
def direct_minbox():
    inner = (req_tabs('보낸 것', mobile=True) + f'<div style="display:flex;flex-direction:column">{sent_cards(SENT[:3])}</div>'
             + f'<div style="padding:8px 12px"><span class="muted" style="font-size:11.5px">잠긴 뒤에는 「잠겨서 취소할 수 없습니다」 사유가 붙은 점선.</span></div>')
    page31('V31DirectMInbox.dc.html', 'K6 절 수신함 — 보낸 것 · 명령 취소(모바일)', shell_mob(k6.mmain(inner), 'war', '서신', '작전실'), w=MW, h=MH)


# ================================================================== K8 절 — 상관 건의
PET_ACTS = [('출병', '부곡을 이끌고 나간다', True), ('이동', '내가 옮긴다', False), ('그 밖', '서버가 준 [값]종', False)]


@board
def direct_petition():
    hero = (f'<section class="panel" style="width:360px;flex-shrink:0">'
            f'<div style="padding:12px;display:flex;justify-content:center">{portrait("jojo", "조조", 220, 312)}</div>'
            f'<div style="padding:0 16px 10px;display:flex;flex-direction:column;gap:8px"><span class="serif" style="font-size:24px;font-weight:900">조조</span>'
            f'<div style="display:flex;gap:6px;flex-wrap:wrap">{chip("NPC")}{chip("군주")}{chip("내 상관", "bronze")}</div></div></section>')
    acts = ''.join(opt(n, s, ok_chip() if sel else '', sel=sel, h=44) for n, s, sel in PET_ACTS)
    form = (f'<div style="padding:12px 16px;display:flex;flex-direction:column;gap:10px">'
            + field('상관', f'<div class="inp">{portrait("jojo", "조조", 22, 30)}<span class="serif" style="font-weight:700">조조</span>'
                          f'<span class="muted" style="font-size:12px">NPC · 내 직속 상관 — 바꿀 수 없음</span></div>')
            + field('무엇을', f'<div role="listbox" aria-label="건의할 행동" style="display:flex;flex-direction:column;border:1px solid #2c342f">{acts}</div>'
                           f'<span style="display:flex;gap:6px;align-items:center;margin-top:4px"><span class="muted" style="font-size:11px">명령할 수 있는 행동과 같은 목록 — 지금 권한으로 거름</span></span>')
            + f'<div style="display:flex;flex-direction:column;gap:6px;padding:8px 10px;border:1px solid #2c342f;background:#141816">'
            f'<span class="t2" style="font-size:12px">출병의 값 — 행동마다 명령 흐름의 칸을 그대로 쓴다</span>'
            + kvline('목적지', '진류군 방면 [구역]') + kvline('부곡', '상관이 고릅니다 — 희망 의견만 「부곡 1 · 2」', dim=True)
            + kvline('희망 시점', '[값]순 — 희망 의견, 실행 순은 서버가 정함') + '</div>'
            + field('근거', '<div class="inp area" style="min-height:96px">진류 쪽 원소군 기병이 빠졌습니다. 지금 나가면 진류현을 먼저 잡을 수 있습니다.</div>', '글자 [값]자까지')
            + f'<div style="display:flex;align-items:center;gap:6px"><span class="t2" style="font-size:12.5px">미리 판정 — 서버 값</span>{ok_chip("올릴 수 있음")}'
            f'<span class="muted" style="font-size:11.5px">같은 건의가 대기 중이면 「이미 올린 건의가 있습니다」</span></div>'
            + k6.infobox('조조는 NPC라 자기 순에 정해진 규칙으로 판단합니다. 저절로 받아들여지지 않고, [값]순 뒤에 만료됩니다.')
            + '</div>')
    dlg = dialog('조조에게 건의', form, btn('그만두기') + btn('건의 올리기', 'primary'), w=600, style='position:absolute;right:64px;top:24px')
    stage = (f'<div style="flex:1;min-width:0;position:relative;display:flex">'
             f'<div style="flex:1;opacity:.35;display:flex;flex-direction:column;gap:12px">{k4.panel("관계", "", "<div style=padding:10px>" + kvline("나와의 관계", "내 직속 상관") + "</div>")}</div>'
             f'<div class="scrim" style="position:absolute;inset:0"></div>{dlg}</div>')
    head = pagehead(BU, RET_TABS, '인물 일람', btn('← 인물 일람', '', href='#'))
    page31('V31DirectPetition.dc.html', 'K8 절 건의 폼 — 조조에게(데스크톱)',
           shell_desk('부', 'retinue', k4.desk_main(head, hero + stage)))




def petition_body(blocked=False):
    rows = (kvline('행동', '출병') + kvline('목적지', '영양현 방면 [구역]') + kvline('부곡', '희망 의견: 악진 부곡 1 — 고르는 사람은 나')
            + kvline('희망 시점', '[값]순') + kvline('만료', '[값]순 뒤'))
    blk = (k6.warnbox('판단 때 다시 검사해 막혔습니다 — [사유]. 명령은 나가지 않았고 건의는 판단 대기로 남습니다. 다시 고치거나 반려할 수 있습니다.')
           if blocked else '')
    return (f'<div style="padding:12px 16px;display:flex;flex-direction:column;gap:10px">'
            f'{who_line("", "악진", "사람 · 내 직속 부하(예시) · 영천군", chip("사람", "info"))}'
            + k6.inset(rows)
            + f'<div style="display:flex;flex-direction:column;gap:4px"><span class="t2" style="font-size:12px">근거 — 악진 원문</span>'
            f'<div class="inset" style="padding:8px 10px;font-size:13px;line-height:1.6">영양현 북쪽 길이 비었습니다. 제 부곡으로 먼저 들어가 길을 막겠습니다.</div></div>'
            + blk + '</div>')


@board
def direct_petition_decide():
    left = inbox_panel('받은 것', recv_cards(('악진', '출병 건의 · 영양현 방면')), w=380)
    acts = (f'<div style="padding:10px 16px;display:flex;flex-direction:column;gap:6px;border-top:1px solid #2c342f">'
            f'<div style="display:flex;gap:8px">{btn("채택", "primary", style="flex:1")}{btn("고쳐서 명령", style="flex:1")}{btn("반려", "danger", style="flex:1")}</div>'
            f'<span style="display:flex;gap:6px;align-items:center;flex-wrap:wrap"><span class="muted" style="font-size:11.5px">채택 · 고쳐서 명령 — 상관 1순 + 부하 1순 · 반려는 순을 쓰지 않음</span></span>'
            f'<span class="muted" style="font-size:11.5px">고쳐서 명령은 직속 명령 흐름에서 건의 값을 채운 채 연다 — 부곡은 내가 고른다.</span></div>')
    mid = (f'<section class="panel" style="flex:1 1 0;min-width:0;display:flex;flex-direction:column">{sec("받은 건의 — 악진", "판단 대기 · [값]순 뒤 만료")}'
           f'{petition_body(blocked=True)}<div style="margin-top:auto">{acts}</div></section>')
    codes = ''.join(opt(t, '', '', sel=(i == 0), h=44) for i, t in enumerate(['내 권한으로는 못 함', '자원 · 부곡이 없음', '방침과 어긋남', '건의할 수 없는 일', '지금은 아님']))
    reject = (f'<section class="panel" role="dialog" aria-label="반려" style="width:360px;flex-shrink:0;border-color:#c96b5d">{sec("반려", "악진에게 사유가 보인다")}'
              f'<div style="padding:12px;display:flex;flex-direction:column;gap:10px">'
              + field('사유', f'<div role="listbox" aria-label="반려 사유" style="display:flex;flex-direction:column;border:1px solid #2c342f">{codes}</div>'
                             f'<span class="muted" style="margin-top:4px;font-size:11.5px">사유 하나는 꼭 고른다 · NPC 상관도 같은 사유</span>')
              + field('덧붙일 말(선택)', '<div class="inp area" style="min-height:72px"><span class="ph">덧붙일 말</span></div>', '판정을 바꾸지 않는다')
              + f'<span class="muted" style="font-size:11.5px">반려해도 악진에게 불이익(충성 감소 등)은 없다.</span>'
              + f'<div style="display:flex;gap:8px">{btn("그만두기", style="flex:1")}{btn("반려", "danger", style="flex:1")}</div></div></section>')
    body = pagehead('서신', None, None, btn('새 서신', 'primary', 'mail')) + f'<div style="flex-grow:1;display:flex;gap:12px;padding:12px;min-height:0">{left}{mid}{reject}</div>'
    page31('V31DirectPetitionDecide.dc.html', 'K8 절 받은 건의 — 판단 · 판단 때 막힘 · 반려 사유(데스크톱)',
           shell_desk('서신', 'plaza', f'<main style="flex-grow:1;min-width:0;display:flex;flex-direction:column">{body}</main>'))


@board
def direct_petition_edited():
    def side(title, who, when, rows):
        return (f'<section class="panel" style="flex:1 1 0;min-width:0">{sec(title, f"{who} · {when}")}'
                f'<div style="padding:8px 12px">{rows}</div></section>')
    orig = (kvline('행동', '출병', dim=True) + kvline('목적지', '영양현 방면') + kvline('부곡', '희망: 악진 부곡 1')
            + kvline('시점', '[값]순', dim=True))
    edit = (kvline('행동', '출병', dim=True) + kvline('목적지', '신정현 방면', changed=True) + kvline('부곡', '악진 부곡 1 · 2', changed=True)
            + kvline('시점', '[값]순', dim=True))
    pair = (f'<div style="display:flex;gap:12px">{side("원래 건의", "악진", "[순]", orig)}'
            f'<div style="align-self:center">{icon("next", 22, "#d3b064")}</div>{side("고친 명령", "하후돈", "[순]", edit)}</div>')
    main_ = (f'<section class="panel" style="flex:1 1 0;min-width:0;display:flex;flex-direction:column">{sec("악진의 건의 — 고쳐서 명령으로 보냈습니다", "상관 쪽 표시")}'
             f'<div style="padding:12px 16px;display:flex;flex-direction:column;gap:12px">'
             f'<div style="display:flex;gap:6px;align-items:center">{chip("고쳐서 명령", "bronze")}<span class="t2" style="font-size:12.5px">바뀐 칸에만 표시, 바뀌지 않은 칸은 흐리게</span></div>'
             f'{pair}'
             f'<div style="display:flex;flex-direction:column;gap:4px"><span class="t2" style="font-size:12px">근거 — 악진 원문 그대로</span>'
             f'<div class="inset" style="padding:8px 10px;font-size:13px;line-height:1.6">영양현 북쪽 길이 비었습니다. 제 부곡으로 먼저 들어가 길을 막겠습니다.</div></div>'
             + k6.inset(kvline('명령 책임', f'하후돈 {pending("D66 책임 규칙")}') + kvline('실행 · 자원', '악진 — 악진의 실행 순')
                        + kvline('악진이 고친 판을 보는지', '봅니다 — 원본 · 고친 판 · 결과를 나란히, 원문은 그대로')))
    main_ += (f'<div style="display:flex;gap:8px;align-items:center"><a href="#" class="btn">결과 명령 보기 →</a>'
              f'<span class="muted" style="font-size:11.5px">명령 상태(발행 · 실행 · 실행 못 함)는 수신함 명령 카드가 정본</span></div></div></section>')
    edited = cmd_card('받은 건의', '', '악진', '출병 건의 · 영양현 방면', '[순]', '고쳐서 명령', 'bronze', note_='원래 → 고친 값은 바뀐 칸만')
    left = inbox_panel('받은 것', edited.replace('class="req"', 'class="req" style="outline:2px solid #d3b064;outline-offset:-2px"', 1)
                       + cmd_card(*RECV[0][:7], note_=RECV[0][7]), w=380)
    body = pagehead('서신', None, None, btn('새 서신', 'primary', 'mail')) + f'<div style="flex-grow:1;display:flex;gap:12px;padding:12px;min-height:0">{left}{main_}</div>'
    page31('V31DirectPetitionEdited.dc.html', 'K8 절 고친 기록 — 원래 건의 · 고친 명령(데스크톱)',
           shell_desk('서신', 'plaza', f'<main style="flex-grow:1;min-width:0;display:flex;flex-direction:column">{body}</main>'))


@board
def direct_mpetition():
    body = (f'<div style="padding:4px 12px 10px;display:flex;flex-direction:column;gap:8px">'
            f'<div style="display:flex;gap:6px;align-items:center">{chip("NPC 판단 대기", "info")}<span class="mono muted" style="font-size:11.5px">[값]순 뒤 만료</span></div>'
            + k6.infobox('상관은 자기 순에 정해진 규칙으로 판단합니다 · 다음 판단 [값]순. 저절로 받아들여지지 않습니다.')
            + k6.inset(kvline('행동', '출병') + kvline('목적지', '진류군 방면') + kvline('부곡', '희망 의견만 — 상관이 고름', dim=True))
            + f'<span style="display:flex;gap:6px;align-items:center"><span class="muted" style="font-size:11.5px">건의 거두기 — 판단 전에만 · 순을 쓰지 않음</span></span></div>')
    sh = sheet('조조에게 올린 건의', body, top=290, foot=btn('건의 거두기', style='flex:1'))
    pets = (cmd_card('보낸 건의', 'jojo', '조조', '출병 건의 · 진류군 방면', '[값]순 뒤 만료', 'NPC 판단 대기', 'info')
            + cmd_card('보낸 건의', 'jojo', '조조', '이동 건의 · [구역]', '[순]', '반려', '', note_='지금은 아님')
            + cmd_card('보낸 건의', 'jojo', '조조', '훈련 건의', '[순]', '그대로 명령이 됨', 'bronze', note_='결과 명령 보기 →')
            + cmd_card('보낸 건의', 'jojo', '조조', '첩보 건의', '[순]', '기한이 지나 닫힘', ''))
    inner = req_tabs('보낸 것', mobile=True) + f'<div style="display:flex;flex-direction:column">{pets}</div><div class="scrim"></div>{sh}'
    page31('V31DirectMPetition.dc.html', 'K8 절 보낸 건의 — NPC 상관 판단 대기(모바일)', shell_mob(k6.mmain(inner), 'war', '서신', '작전실'), w=MW, h=MH)


@board
def direct_mpetition_decide():
    body = (petition_body() + f'<div style="padding:0 16px 8px;display:flex;gap:6px;align-items:center;flex-wrap:wrap">'
            f'<span class="muted" style="font-size:11.5px">채택 · 고쳐서 명령 — 상관 1순 + 부하 1순 · 반려는 순 0</span>'
            f'<span class="muted" style="font-size:11.5px">반려 → 데스크톱과 같은 사유 시트(하단)</span></div>')
    foot = btn('반려', 'danger', style='flex:1') + btn('고쳐서 명령', style='flex:1') + btn('채택', 'primary', style='flex:1')
    sh = sheet('악진의 건의 — 판단', body, top=96, foot=foot)
    inner = f'<div class="scrim"></div>{sh}'
    page31('V31DirectMPetitionDecide.dc.html', 'K8 절 받은 건의 — 판단(모바일)', shell_mob(k6.mmain(inner), 'war', '서신', '작전실'), w=MW, h=MH)


if __name__ == '__main__':
    for f in glob.glob(os.path.join(P, 'V31Direct*.dc.html')):
        os.remove(f)
    for b in BOARDS:
        b()
    bad = 0
    for f in sorted(glob.glob(os.path.join(P, 'V31Direct*.dc.html'))):
        e = k4.check(f)
        if e:
            bad += 1
            print(os.path.basename(f), e[:5])
    print(f'ok boards_v31_direct — {len(BOARDS)} boards, 검사 실패 {bad}')
