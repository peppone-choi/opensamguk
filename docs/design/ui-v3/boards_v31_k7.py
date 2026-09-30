# 캔버스 v3.1 — K7 도움말 · 튜토리얼 「첫걸음」(P-A01 · P-A02) 보드.
# 설계서: 메타 reports/opensamguk/tasks/2026-09-30-k7-design-spec.md. 시스템은 v31system(K3), 그림 id 는 v31assets(K0) — 둘 다 고치지 않는다.
# 도움말 글 · 실패 사유 · 원장 값은 저장소 data/help/*.json · data/commands/input-catalog.json 에서 그대로 읽는다(지금 전부 초안).
#
# PYTHONPATH=<v31assets 폴더> python3 boards_v31_k7.py → project/V31K7*.dc.html
import glob
import json
import os

from v31system import *  # noqa: F401,F403
from v31system import (P, DESK_PX, MOB_PX, CELLS, HERE, TURNS, TURN_WORD, HAVE_ASSETS, sec, kv, icon, res,
                       page31, shell_desk, shell_mob, pagehead, btn, btn_off, ibtn, chip, why_tag, search, seg, opt,
                       sheet, pop, toast, help_strip, band, state_empty, state_error, state_waiting, _state,
                       mapimg, mlab, me_marker, view_bar, tabbar31, portrait, cmd_label, CARD_RENAME)

ROOT = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', '..'))


def _j(path):
    return json.load(open(os.path.join(ROOT, path), encoding='utf-8'))


CAT = {r['inputId']: r for r in _j('data/commands/input-catalog.json')['inputs']}
TOP = {t['id']: t for t in _j('data/help/topics.json')['topics']}
FR = {r['code']: r for r in _j('data/help/failure-reasons.json')['reasons']}

# ------------------------------------------------------------------ 원장 코드 → 화면 말(설계서 부록 A)
KIND = {'GENERAL_ACTION': '직접 행동', 'PLACEMENT': '배치', 'POLICY': '방침', 'WORK': '공사', 'STRATAGEM': '계책', 'COURT_DECISION': '조정 결정'}
ACTOR = {'GENERAL': '장수 본인', 'LORD': '주공', 'RULER': '군주', 'OFFICE_HOLDER': '그 관할의 관직자'}
AUTH = {'SUBJECT_OWNER': '내 장수', 'DECISION_AUTHORITY': '결정권자', 'CARD_OWNER': '카드 주인', 'DIRECT_RETAINER_OWNER': '직접 거느린 주인',
        'JURISDICTION_OFFICE': '관할 관직자'}
SLOT_PHASE = {'POLITICS': '정치', 'MOVE': '이동', 'SIEGE': '공성', 'FIELD': '현장 행동'}
OTHER_PHASE = {'DECISION_TURN': '결정권자의 턴에', 'NEXT_CARD_TURN': '맡긴 인물의 다음 턴부터', 'NEXT_PHASE_BOUNDARY': '다음 순 경계부터',
               'CARD_TRIGGER': '카드 조건이 맞을 때'}
SCOPE = {'ACTOR_LOCATION': '내가 있는 곳', 'DECISION_TARGET': '결정 대상', 'CARD_TARGET': '카드 대상', 'TARGET_COUNTY': '고른 현',
         'DIRECT_RETAINER': '직접 거느린 인물', 'ASSIGNED_JURISDICTION': '맡은 관할'}
RES5 = [('금', 'money'), ('쌀', 'grain'), ('철', 'iron'), ('목재', 'timber'), ('말', 'horses')]


def topic(iid):
    return TOP[CAT[iid]['helpTopicId']]


# 도움말 본문 속 옛 이름(K0 3.1.2 규칙) — 저장소 글이 고쳐지면(U5) 이 표를 지운다.
OLD_WORDS = [('숙련전환', '병종 바꿔 익히기'), ('군량매매', '쌀 사고팔기'), *CARD_RENAME.items(), ('군량', '쌀')]


def say(text):
    for a, b in OLD_WORDS:
        text = text.replace(a, b)
    return text


def nm(iid):
    """명령 이름 — 원장 displayName(없으면 주제 제목), 옛 말은 cmd_label 로 새 이름 + 「이름 승인 대기」."""
    return cmd_label(iid, CAT[iid].get('displayName') or topic(iid)['title'])


def when(iid):
    tm = CAT[iid]['timing']
    if tm['phase'] in SLOT_PHASE:
        return f'명령 목록 12순 · 한 순에 하나 · {SLOT_PHASE[tm["phase"]]} 단계'
    return OTHER_PHASE[tm['phase']]


def target(iid):
    return '준비 중' if CAT[iid]['targetSchema'].get('status') == 'PLANNED' else '명령 화면에서 고릅니다'


def cost_line(iid):
    c = CAT[iid]['costSchema']
    vals = {k: c.get(k) for _, k in RES5}
    if all(v is None for v in vals.values()):
        return f'{" ".join(res(n) for n, _ in RES5)} <span class="t2">— 상황에 따라</span>'
    parts = []
    for n, k in RES5:
        v = vals[k]
        parts.append(f'{res(n)} <span class="t2">{"들지 않음" if v == 0 else ("상황에 따라" if v is None else v)}</span>')
    return ' '.join(parts)


DRAFT_LINE = '초안 — 아직 검수 전인 설명입니다. 실제 결과와 다르면 결과를 믿으세요.'
PHASE_COUNT = {}
for _r in CAT.values():
    if _r['kind'] == 'GENERAL_ACTION':
        PHASE_COUNT[_r['timing']['phase']] = PHASE_COUNT.get(_r['timing']['phase'], 0) + 1


def by_phase(phase):
    return [i for i, r in CAT.items() if r['kind'] == 'GENERAL_ACTION' and r['timing']['phase'] == phase]


# ------------------------------------------------------------------ K7 부품(시스템 부품 위에 얹는 내용)
def trow(iid, h=52, sel=False):
    """도움말 목록 한 줄 — 입력 이름 · 설명 한 줄 · 초안 / 준비 중."""
    end = chip('준비 중', 'rust') if CAT[iid]['deliveryState'] == 'PLANNED' else ''
    end += chip('초안', 'info') if topic(iid)['reviewState'] == 'DRAFT' else ''
    return opt(nm(iid), say(topic(iid)['sections']['explanation']), end, sel=sel, h=h)


def ghead(t, sub=''):
    return (f'<div style="height:30px;flex-shrink:0;display:flex;align-items:center;gap:8px;padding:0 12px;background:#141816;border-bottom:1px solid #2c342f">'
            f'<span class="serif bz" style="font-size:13px;font-weight:700">{t}</span><span class="muted" style="font-size:11px;margin-left:auto">{sub}</span></div>')


def drawer_head(back=False, q='', tabs=None, on=None):
    """도움말 서랍 머리 56 + 찾기 60 + 보기 탭 52(주제 보기에서는 탭 없음)."""
    b = ibtn('back', '앞 보기로', style='border:0;background:transparent') if back else ''
    h = (f'<div style="height:56px;flex-shrink:0;display:flex;align-items:center;gap:4px;padding:0 4px 0 {4 if back else 16}px;border-bottom:1px solid #2c342f">'
         f'{b}<span class="serif" style="font-size:17px;font-weight:900">도움말</span>'
         f'<span style="margin-left:auto">{ibtn("close", "도움말 닫기(Esc)", style="border:0;background:transparent")}</span></div>'
         f'<div style="height:60px;flex-shrink:0;padding:8px 12px">{search("도움말 찾기 — 두 글자 이상", q)}</div>')
    if tabs:
        h += f'<div style="height:52px;flex-shrink:0;padding:0 12px 8px">{seg(tabs, on, "도움말 보기", style="width:100%")}</div>'
    return h


def drawer(body, w=400, **kw):
    """데스크톱 도움말 서랍 — 모달 아님(aside), 지도와 12순 열 사이, 지도를 밀어 줄인다."""
    return (f'<aside aria-label="도움말" style="width:{w}px;flex-shrink:0;display:flex;flex-direction:column;background:#1b201d;'
            f'border-left:1px solid #4b6d87;border-right:1px solid #3d4740;min-height:0;overflow:hidden">{drawer_head(**kw)}'
            f'<div style="flex-grow:1;min-height:0;display:flex;flex-direction:column;overflow:hidden">{body}</div></aside>')


def home_body(on_phase='FIELD', rows=7, mobile=False):
    """홈 — 이 화면(작전실): 직접 행동을 단계별로 + 개념 묶음(빈 상태)."""
    items = [(f'{SLOT_PHASE[p]}', PHASE_COUNT[p]) for p in ('FIELD', 'MOVE', 'POLITICS', 'SIEGE')]
    lst = [i for i in by_phase(on_phase) if i != 'action.scout'][:rows]  # 첩보 초안 글에 한자가 섞여 예시에서 뺀다(U5 검수 목록)
    more = len(by_phase(on_phase)) - len(lst)
    body = (ghead('작전실에서 하는 일', f'직접 행동 {sum(PHASE_COUNT.values())}')
            + f'<div style="padding:8px 12px;flex-shrink:0">{seg(items, SLOT_PHASE[on_phase], "단계", style="width:100%")}</div>'
            + '<div role="list" aria-label="도움말 주제" style="display:flex;flex-direction:column;border-top:1px solid #2c342f">'
            + ''.join(trow(i) for i in lst) + '</div>'
            + f'<div style="padding:6px 12px">{btn(f"{more}개 더 보기", "sm", style="width:100%;background:transparent")}</div>'
            + ghead('개념', '부 · 소속 · 순 · 명망 · 보급 …')
            + '<div style="padding:10px 12px;display:flex;gap:10px;align-items:flex-start;flex-shrink:0">'
            + f'<span style="width:36px;height:36px;flex-shrink:0;display:inline-flex;align-items:center;justify-content:center;border:1px solid #8a8477">{icon("list", 18, "#8a8477")}</span>'
            + '<span style="display:flex;flex-direction:column;gap:4px"><span style="font-size:13px;font-weight:700">개념 도움말은 준비 중입니다</span>'
            + '<span class="t2" style="font-size:12px;line-height:1.5">지금은 명령마다 설명이 있습니다. 부 · 소속 · 순 같은 말의 풀이는 곧 이 자리에 들어옵니다.</span></span></div>')
    if not mobile:
        body += f'<div style="margin-top:auto;padding:10px 12px;border-top:1px solid #2c342f" class="muted"><span style="font-size:11.5px">{chip("초안", "info")} {DRAFT_LINE}</span></div>'
    return body


def para(label, text, cls='t2', gap=2):
    return (f'<div style="display:flex;flex-direction:column;gap:{gap}px;flex-shrink:0"><span class="muted" style="font-size:11px;font-weight:700">{label}</span>'
            f'<span class="{cls}" style="font-size:13px;line-height:1.5">{text}</span></div>')


def okno(iid, stacked=False):
    s = topic(iid)['sections']
    box = lambda c, t, x: (f'<div style="flex:1 1 0;min-width:0;border:1px solid {c};padding:8px 10px;display:flex;flex-direction:column;gap:3px">'  # noqa: E731
                           f'<span style="font-size:11.5px;font-weight:700;color:{c}">{t}</span><span class="t2" style="font-size:12px;line-height:1.45">{x}</span></div>')
    return (f'<div style="display:flex;flex-direction:{"column" if stacked else "row"};gap:8px;flex-shrink:0">'
            f'{box("#8fa77a", "잘 되면", say(s["successExample"]))}{box("#e08a7c", "안 되면", say(s["failureExample"]))}</div>')


def rules(iid, compact=False):
    r = CAT[iid]
    rows = [('누가', f'{ACTOR[r["actor"]]} · {AUTH[r["authorityRule"]]}'), ('언제', when(iid)), ('어디에', SCOPE[r['effectScope']]),
            ('대상', target(iid)), ('비용', cost_line(iid))]
    lines = ''.join(f'<div style="display:grid;grid-template-columns:44px minmax(0,1fr);gap:8px;min-height:22px;align-items:baseline">'
                    f'<span class="muted" style="font-size:11.5px">{a}</span><span style="font-size:12.5px;line-height:1.45">{b}</span></div>' for a, b in rows)
    return (f'<div class="inset" style="padding:8px 10px;display:flex;flex-direction:column;gap:3px;flex-shrink:0">'
            f'<span class="muted" style="font-size:11px;font-weight:700">이 명령의 규칙</span>{lines}'
            + ('' if compact else '<span class="muted" style="font-size:11px;line-height:1.4">실제 비용과 대상은 제출 전에 명령 화면에서 보여 줍니다.</span>') + '</div>')


def fails(iid, show=4, open_=True, hi=None):
    codes = CAT[iid]['failureReasons']
    headb = (f'<button type="button" class="btn sm" aria-expanded="{"true" if open_ else "false"}" style="width:100%;flex-shrink:0;justify-content:space-between;background:transparent">'
             f'<span>안 되는 경우 {len(codes)}가지</span><span class="muted">{"접기" if open_ else "펼치기"}</span></button>')
    if not open_:
        return headb
    pick = [c for c in codes if c not in ('WRONG_RULE_PROFILE', 'UNKNOWN_INPUT', 'INVALID_REQUEST', 'INVALID_INPUT_CHANNEL', 'ACTOR_NOT_FOUND')][:show]
    if hi and hi in codes and hi not in pick:
        pick = [hi] + pick[:show - 1]
    rows = ''.join(f'<button type="button" class="opt" style="min-height:44px;font-size:12.5px;{"background:rgba(201,107,93,.10);box-shadow:inset 3px 0 0 #c96b5d" if c == hi else ""}">'
                   f'<span style="min-width:0">{say(FR[c]["explanation"])}</span><span class="end">{icon("next", 14, "#8a8477")}</span></button>' for c in pick)
    return (f'<div style="display:flex;flex-direction:column">{headb}<div role="list" style="display:flex;flex-direction:column;border-top:1px solid #2c342f">{rows}</div>'
            f'<span class="muted" style="font-size:11.5px;padding:6px 0 0">{len(codes) - len(pick)}가지 더 — 누르면 설명과 다시 하는 법</span></div>')


def topic_body(iid, mobile=False, fails_open=True, show=4, hi=None, go=True):
    t = topic(iid)
    s = t['sections']
    planned = CAT[iid]['deliveryState'] == 'PLANNED'
    head = (f'<div style="display:flex;align-items:center;gap:8px;flex-wrap:wrap;flex-shrink:0"><h3 class="serif" style="margin:0;font-size:20px;font-weight:900">{cmd_label(iid, t["title"])}</h3>'
            f'{chip(KIND[CAT[iid]["kind"]])}{chip("초안", "info") if t["reviewState"] == "DRAFT" else ""}{chip("준비 중", "rust") if planned else ""}</div>')
    note = ('<span class="rs" style="font-size:12px">아직 준비 중인 명령입니다. 설명만 볼 수 있습니다.</span>' if planned
            else '<span class="muted" style="font-size:11.5px">초안 — 아직 검수 전인 설명입니다.</span>')
    goact = (btn_off('이 명령 하러 가기', '준비 중', style='width:100%', input_id=iid) if planned
             else btn('이 명령 하러 가기', 'primary', 'next', style='width:100%', attrs=f'data-input-id="{iid}"'))
    g = 6 if mobile else 10
    body = (f'<div style="padding:12px;display:flex;flex-direction:column;gap:{g}px;min-height:0">{head}{note}'
            f'{para("설명", say(s["explanation"]))}{para("이렇게 합니다", say(s["example"]))}{okno(iid, stacked=mobile)}'
            f'{para("다시 하려면", say(s["recoveryAdvice"]))}{rules(iid, compact=mobile)}{fails(iid, show, fails_open, hi)}</div>')
    if go:
        body += f'<div style="margin-top:auto;padding:8px 12px 12px;border-top:1px solid #2c342f;flex-shrink:0">{goact}</div>'
    return body


def turns_col(cur=4):
    """12순 열(작전실 오른쪽 336, A1 약식) — 도움말 서랍이 열려도 그대로 보인다."""
    rows = ''
    for no, d, tm, c, st in TURNS:
        stc = {'done': 'moss', 'res': 'bronze', 'warn': 'rust', 'empty': ''}[st]
        cur_ = 'background:rgba(211,176,100,.08);box-shadow:inset 3px 0 0 #d3b064' if no == cur else ''
        rows += (f'<button type="button" class="opt" aria-label="{no:02d}순 {d} {tm} — {c or "빈 순"}" style="min-height:52px;{cur_}">'
                 f'<span class="mono muted" style="font-size:12px;width:20px">{no:02d}</span>'
                 f'<span style="display:flex;flex-direction:column;min-width:0"><span class="mono t2" style="font-size:11px">{d} · {tm}</span>'
                 f'<span class="{"serif" if c else "muted"}" style="font-size:{14 if c else 12}px;font-weight:{700 if c else 400}">{c or "빈 순"}</span></span>'
                 f'<span class="end">{chip(TURN_WORD[st], stc) if c else chip("+ 예약")}</span></button>')
    return (f'<aside aria-label="명령 목록 12순" style="width:336px;flex-shrink:0;display:flex;flex-direction:column;background:#1b201d;border-left:1px solid #3d4740;min-height:0">'
            f'{sec("명령 목록 12순", "직접 행동 · 한 순에 하나")}<div style="display:flex;flex-direction:column;overflow:hidden">{rows}</div>'
            f'<div style="margin-top:auto;padding:8px;display:flex;gap:6px;border-top:1px solid #2c342f">{btn("이번 순에 할 일", "primary", style="flex:1")}'
            f'{btn("당기기", "sm")}{btn("밀기", "sm")}</div></aside>')


def desk_map(w, ox=100, label='지도'):
    hx, hy = DESK_PX(*CELLS[HERE])
    labs = ''.join(mlab(n, DESK_PX(*CELLS[n])[0] - ox, DESK_PX(*CELLS[n])[1] + 26, dim=False)
                   for n in ('장사현', '영양현', '신정현', '영음현', '번창현') if 40 < DESK_PX(*CELLS[n])[0] - ox < w - 40)
    return (f'<main aria-label="{label}" style="position:relative;width:{w}px;flex-shrink:0;overflow:hidden;background:#0c0f0e">'
            f'{mapimg("desk", 1048, 952, "영천 일대 지도 — 현 보기", -ox, 0)}{deco(labs)}{me_marker(hx - ox, hy - 22, "in")}'
            f'{view_bar(style="left:12px;bottom:12px")}</main>')


def deco(inner):
    """장식 층(이름표 · 어둡게) — 누르기를 먹지 않는다(3.1.2 「덮지 않기」). 표식은 이 층 뒤에 그려 맨 위."""
    return f'<div aria-hidden="true" style="position:absolute;inset:0;pointer-events:none">{inner}</div>'


def coach_ring(inner, block=False):
    """목표 표시 테두리 — 대상 둘레 청동 2px(바깥 4px). 누르는 것을 가리지 않는다(pointer-events:none · aria-hidden)."""
    return (f'<span style="position:relative;display:{"flex" if block else "inline-flex"};flex:1 1 auto" aria-describedby="coach">{inner}'
            f'<span aria-hidden="true" style="position:absolute;inset:-6px;border:2px solid #d3b064;box-shadow:0 0 0 2px rgba(12,15,14,.85);pointer-events:none"></span></span>')


def coach_card(step, name_, goal, style='', w=280, status=''):
    """목표 표시 카드 — 누를 수 있는 것은 이 카드뿐. 단계 번호(칩의 완료 수와 다르다)."""
    st = f'<span class="ms" style="font-size:12px;line-height:1.45">{status}</span>' if status else ''
    wd = f'width:{w}px;' if w else ''
    return (f'<section id="coach" role="region" aria-label="첫걸음 안내" style="position:absolute;{style};{wd}background:rgba(27,32,29,.98);border:1px solid #d3b064;'
            f'box-shadow:0 10px 28px rgba(0,0,0,.55);padding:10px 4px 4px 12px;display:flex;flex-direction:column;gap:6px">'
            f'<span class="bz" style="font-size:12px;font-weight:700">첫걸음 · {step}단계 — {name_}</span>'
            f'<span style="font-size:13px;line-height:1.5;padding-right:8px">{goal}</span>{st}'
            f'<div style="display:flex;gap:6px;justify-content:flex-end">{btn("도움말", "sm", "help", style="background:transparent")}{btn("숨기기", "sm", style="background:transparent")}</div></section>')


# ------------------------------------------------------------------ 첫걸음 8단계(계약 fixture 의 목표 id · 순서) — 목표 문장은 초안(K7-07 로 옮긴다)
STEPS = [('tutorial.register', '가입', '계정을 만듭니다.', '게이트웨이 가입'),
         ('tutorial.createGeneral', '장수 생성', '이름 · 본관 현 · 다섯 능력 · 주의 · 개성을 정해 내 장수를 만듭니다. 역사 인물을 골라도 됩니다.', '입장 › 장수 만들기'),
         ('tutorial.enlist', '첫 출사', '섬길 주공을 골라 출사합니다.', '입장 › 출사'),
         ('tutorial.dispatch', '첫 발령', '주공이 보낸 발령에 답합니다.', '조정 › 발령 응답'),
         ('tutorial.work', '첫 공사', '맡은 현에서 공사를 시작합니다.', '영지 › 공사'),
         ('tutorial.employ', '첫 등용', '인재탐색으로 재야 인물을 찾고, 찾은 인물을 등용합니다.', '작전실 › 이번 순에 할 일'),
         ('tutorial.march', '첫 행군', '지도에서 목적지를 골라 출병합니다. 이동도 됩니다.', '작전실 › 이번 순에 할 일 · 지도'),
         ('tutorial.battle', '첫 전투', '적과 맞붙어 전투 결과를 받습니다. 이기든 지든 됩니다.', '전투 알림 › 실시간 전투')]
DONE_AT = ['가입 때', '20:12', '20:40', '21:05', '21:18']


def tut_bar(done, cur):
    cells = ''.join(f'<i style="flex:1 1 0;height:12px;display:block;{"background:#d3b064" if k < done else ("border:2px solid #d3b064;background:transparent" if k == cur - 1 else "background:#2c342f")}"></i>'
                    for k in range(8))
    return (f'<div role="img" aria-label="첫걸음 {done} / 8 완료, 지금 {cur}단계" style="display:flex;flex-direction:column;gap:6px">'
            f'<div style="display:flex;align-items:center;gap:8px"><span class="serif" style="font-size:15px;font-weight:900">첫걸음</span>{chip("연습 서버", "info")}'
            f'<span class="mono bz" style="margin-left:auto;font-size:13px;font-weight:700">{done} / 8</span></div><div style="display:flex;gap:3px">{cells}</div></div>')


def step_card(cur, how, status, status_cls='ms', where_here=False, help_name=''):
    sid, name_, goal, where = STEPS[cur - 1]
    hows = ''.join(f'<li style="font-size:12.5px;line-height:1.45;padding:1px 0"><span class="mono bz">{i}</span> {h}</li>' for i, h in enumerate(how, 1))
    goto = chip('지금 이 화면', 'bronze') if where_here else btn('그 화면으로', 'sm', 'next')
    return (f'<section aria-label="지금 단계" style="border:1px solid #d3b064;background:rgba(211,176,100,.06);padding:8px 12px 10px;display:flex;flex-direction:column;gap:6px;flex-shrink:0">'
            f'<div style="display:flex;align-items:center;gap:8px"><span class="serif" style="font-size:16px;font-weight:900">{cur}단계 · {name_}</span>{chip("지금", "bronze")}'
            f'<span style="margin-left:auto">{goto}</span></div>'
            f'<span style="font-size:13px;line-height:1.5">{goal} <span class="t2" style="font-size:12px">어디서 — {where}</span></span>'
            f'<ol class="ul" style="margin:0;padding:0;list-style:none">{hows}</ol>'
            f'<span class="{status_cls}" style="font-size:12.5px;line-height:1.45;display:flex;gap:6px;align-items:flex-start">{icon("clock", 16)}<span>{status}</span></span>'
            f'<div style="display:flex;gap:6px">{btn("도움말 — " + (help_name or name_), "sm", "help", style="flex:1")}{btn("안 될 때", "sm", style="background:transparent")}</div></section>')


def step_list(done, cur, h=44, fold=False):
    out = ''
    if fold and done:
        out += (f'<button type="button" class="opt" aria-expanded="false" style="min-height:{h}px">'
                f'<span class="nm" style="font-size:14px">1–{done}단계</span><span class="end">{chip(f"완료 {done}", "moss")}<span class="muted" style="font-size:12px">펼치기</span></span></button>')
    for k, (sid, name_, goal, where) in enumerate(STEPS, 1):
        if fold and k <= done:
            continue
        if k <= done:
            end = f'<span class="mono muted" style="font-size:11px">{DONE_AT[k - 1]}</span>{chip("완료", "moss")}'
        elif k == cur:
            end = chip('지금', 'bronze')
        else:
            end = chip('잠김')
        cur_ = 'background:rgba(211,176,100,.08);box-shadow:inset 3px 0 0 #d3b064' if k == cur else ''
        out += (f'<button type="button" class="opt" aria-expanded="false" style="min-height:{h}px;{cur_}">'
                f'<span class="mono {"bz" if k == cur else "muted"}" style="font-size:12px;width:16px">{k}</span>'
                f'<span class="nm" style="font-size:14px;{"color:#8a8477" if k > cur else ""}">{name_}</span><span class="end">{end}</span></button>')
    return f'<div role="list" aria-label="첫걸음 8단계" style="display:flex;flex-direction:column;border-top:1px solid #2c342f">{out}</div>'


# ================================================================== 보드
def board_help():
    """P-A01 데스크톱 — 작전실 + 도움말 서랍 「이 화면」. 지도 1048 → 648(밀림, 덮지 않음), 12순 336 그대로."""
    main = desk_map(648) + drawer(home_body(), tabs=['이 화면', '분류', '첫걸음'], on='이 화면') + turns_col()
    page31('V31K7Help.dc.html', 'K7 도움말 서랍 — 이 화면(데스크톱)', shell_desk('작전실', 'war', main))


def board_help_topic():
    """P-A01 데스크톱 — 주제 「출사」(문맥 도움말: 원장 투영 + 안 되는 경우)."""
    main = desk_map(648) + drawer(topic_body('action.enlist'), back=True) + turns_col()
    page31('V31K7HelpTopic.dc.html', 'K7 도움말 주제 — 출사(데스크톱)', shell_desk('작전실', 'war', main))


def board_help_search():
    """P-A01 독립 페이지 /game/<서버>/help — 찾기 결과 + 빈 · 오류 · 서버 대기."""
    q = '인물'
    hits = []
    for t in TOP.values():
        s = t['sections']
        sect = 'title' if q in t['title'] else ('explanation' if q in s['explanation'] else ('example' if q in s['example'] else None))
        if sect:
            hits.append((sect, t['id'], t))
    hits.sort(key=lambda h: ({'title': 0, 'explanation': 1, 'example': 2}[h[0]], h[1]))
    word = {'title': '제목', 'explanation': '설명', 'example': '예'}
    rows = ''.join(opt(say(t['title']), say(t['sections']['example'] if sect == 'example' else t['sections']['explanation']),
                       chip(word[sect]) + chip('초안', 'info'), h=60) for sect, _, t in hits[:9])
    left = (f'<section class="panel" style="flex:1 1 0;min-width:0">{sec("찾기", "제목 → 설명 → 예 순서")}'
            f'<div style="padding:10px 12px;display:flex;flex-direction:column;gap:6px">{search("도움말 찾기 — 두 글자 이상", q)}'
            f'<span class="t2" style="font-size:12px" aria-live="polite">결과 {len(hits)}개 · 모두 초안</span></div>'
            f'<div role="list" aria-label="찾은 도움말" style="display:flex;flex-direction:column;border-top:1px solid #2c342f">{rows}</div></section>')

    def box(title, sub, inner, h):
        return f'<section class="panel" style="height:{h}px;flex-shrink:0">{sec(title, sub)}<div style="flex-grow:1;display:flex;flex-direction:column;min-height:0">{inner}</div></section>'

    one = ('<div style="padding:10px 12px;display:flex;flex-direction:column;gap:6px">' + search('도움말 찾기 — 두 글자 이상', '화')
           + '<span class="rs" style="font-size:12px">두 글자 이상 적어 주세요.</span></div>')
    empty = state_empty('"화계"에 맞는 도움말이 없습니다', '다른 말로 찾거나 분류에서 골라 보세요. 예: 출병 · 징병 · 발령',
                        btn('분류로 찾기', '', 'list'), pad=8)
    right = (f'<div style="width:440px;flex-shrink:0;display:flex;flex-direction:column;gap:12px">'
             f'{box("한 글자", "요청 전에 막는다", one, 132)}{box("결과 0", "빈 상태", empty, 212)}'
             f'{box("불러오기 실패", "연결 · 5xx", state_error("도움말을 불러오지 못했습니다", pad=8), 268)}'
             f'{box("서버 대기", "503 · 점검", state_waiting("서버가 준비 중이라 도움말도 잠시 쉽니다", "점검이 끝나면 이 자리에서 바로 다시 찾을 수 있습니다.", pad=8), 212)}</div>')
    main = (f'<main style="flex-grow:1;min-width:0;display:flex;flex-direction:column;overflow:hidden">'
            f'{pagehead("도움말", ["이 화면", "분류", "찾기", "첫걸음"], "찾기")}'
            f'<div style="flex-grow:1;min-height:0;display:flex;gap:12px;padding:12px 16px">{left}{right}</div></main>')
    page31('V31K7HelpSearch.dc.html', 'K7 도움말 찾기 · 상태(데스크톱)', shell_desk('도움말', '', main))


def mob_map_bg():
    hx, hy = MOB_PX(*CELLS[HERE])
    return mapimg('mob', 390, 844, '양적 일대 지도 — 현 보기', 0, -56) + me_marker(hx, hy - 56 - 22, 'in', tag=False)


def board_mhelp():
    """P-A01 모바일 — 머리 아래 ~ 탭 위 724 시트, 「이 화면」."""
    body = (f'<div style="height:60px;flex-shrink:0;padding:8px 12px">{search("도움말 찾기 — 두 글자 이상")}</div>'
            f'<div style="height:52px;flex-shrink:0;padding:0 12px 8px">{seg(["이 화면", "분류", "첫걸음"], "이 화면", "도움말 보기", style="width:100%")}</div>'
            f'<div style="display:flex;flex-direction:column;min-height:0;overflow:hidden">{home_body(rows=5, mobile=True)}</div>')
    main = (f'<main style="position:relative;height:724px;flex-shrink:0;overflow:hidden">{mob_map_bg()}<div class="dim" style="pointer-events:none"></div>'
            f'{sheet("도움말", body, top=0)}</main>')
    page31('V31K7MHelp.dc.html', 'K7 도움말 시트 — 이 화면(모바일)', shell_mob(main, 'war'), w=390, h=844)


def board_mhelp_topic():
    """P-A01 모바일 — 주제 「방화」(원장 PLANNED = 준비 중)."""
    iid = 'stratagem.fire'
    head = (f'<div style="height:44px;flex-shrink:0;display:flex;align-items:center;gap:4px;padding:0 4px;border-bottom:1px solid #2c342f">'
            f'<a href="#" class="btn sm" style="background:transparent;border:0">{icon("back", 16)}도움말</a>'
            f'<span style="margin-left:auto">{ibtn("close", "도움말 닫기", style="border:0;background:transparent")}</span></div>')
    body = (f'<section class="sheet" role="dialog" aria-label="도움말 — {topic(iid)["title"]}" style="top:0;bottom:0"><div class="grip"></div>{head}'
            f'<div style="flex-grow:1;min-height:0;display:flex;flex-direction:column;overflow:hidden">{topic_body(iid, mobile=True, fails_open=False)}</div></section>')
    main = f'<main style="position:relative;height:724px;flex-shrink:0;overflow:hidden">{mob_map_bg()}<div class="dim" style="pointer-events:none"></div>{body}</main>'
    page31('V31K7MHelpTopic.dc.html', 'K7 도움말 주제 — 방화 · 준비 중(모바일)', shell_mob(main, 'stratagem', '계책 덱', '작전실'), w=390, h=844)


def court_row(name_, sub, state='on', tail='', iid=''):
    a = f' data-input-id="{iid}"' if iid else ''
    if state == 'on':
        return (f'<a href="#" style="min-height:60px;display:flex;align-items:center;gap:10px;padding:0 12px;border-bottom:1px solid #2c342f;color:#ece6d8"{a}>'
                f'<span style="display:flex;flex-direction:column;flex:1;min-width:0"><span class="serif" style="font-size:15px;font-weight:700">{name_}</span>'
                f'<span class="muted" style="font-size:11.5px">{sub}</span></span>{tail}</a>')
    return (f'<button type="button" class="opt" aria-disabled="true" aria-haspopup="dialog" style="min-height:60px"{a}>'
            f'<span style="display:flex;flex-direction:column;min-width:0"><span class="nm">{name_}</span><span class="sub">{sub}</span></span>'
            f'<span class="end">{why_tag(tail)}</span></button>')


def court_list(pop_html=''):
    return (f'<section class="panel" style="width:440px;flex-shrink:0;position:relative;overflow:visible">{sec("조정 결정", "하후돈 · 조조를 섬기는 장수")}'
            + court_row('발령 응답', '주공이 보낸 발령에 답한다', 'on', chip('응답 대기 1', 'moss'), 'court.dispatchReply')
            + court_row('정치 동의', '선양 · 결의 제안에 답한다', 'on', chip('가능', 'moss'), 'court.politicalConsent')
            + court_row('발령', '사람 장수를 자리에 보낸다', 'off', '권한 없음', 'court.dispatch')
            + court_row('포상', '봉록 · 상사', 'off', '권한 없음', 'court.reward')
            + court_row('천도 · 현 포기', '치소를 옮긴다 · 현을 버린다', 'off', '권한 없음', 'court.moveCapital')
            + court_row('외교', '불가침 · 선전포고 · 강화 · 파기', 'off', '준비 중', 'court.diplomacy')
            + pop_html + '</section>')


def dispatch_card(coach=False):
    iid = 'court.dispatchReply'
    accept = btn('수락', 'primary', 'check', style='width:100%', attrs=f'data-input-id="{iid}"')
    if coach:
        accept = coach_ring(accept, block=True)
    return (f'<section class="panel" style="position:relative;overflow:visible">{sec("발령 응답", "응답 대기 1")}'
            f'<div style="padding:12px;display:flex;flex-direction:column;gap:10px">{help_strip(say(topic(iid)["sections"]["explanation"]))}'
            f'<div style="display:flex;gap:10px;align-items:center">{portrait("jojo", "조조", 40, 56)}<span style="font-size:14px;line-height:1.5">'
            f'주공 <b class="serif">조조</b>가 <b class="serif">하후돈</b>을 <b class="bz">양적현 현령</b> 자리로 발령했습니다.</span></div>'
            f'<div style="display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:6px">{kv("자리", "양적현 현령", "bz")}{kv("받은 때", "3월 상순")}{kv("답할 기한", "[미정]")}</div>'
            f'<span class="t2" style="font-size:12px">답하지 않으면 기한이 지난 뒤 수락으로 봅니다. 답은 장수 행동을 쓰지 않습니다.</span>'
            f'<div style="display:flex;gap:8px;align-items:center">{accept}{btn("거절", "", style="flex:0 0 120px")}</div></div></section>')


def board_reason():
    """사유 시트의 도움말 연결(데스크톱) — 비활성 「발령」 말풍선 + 제출 거절 결과. 문구는 도움말 저장소 초안 그대로."""
    r = FR['NOT_LORD']
    p = pop('발령 — 지금은 할 수 없습니다', f'{r["explanation"]} 하후돈은 지금 조조를 섬기는 장수입니다.', r['recoveryAdvice'] + f' {chip("초안", "info")}',
            '발령', 'left:112px;top:222px;z-index:5')
    b = FR['NO_DISPATCH']
    res_ = (f'<section class="panel">{sec("제출이 거절됐을 때", "사유와 함께 돌아온다")}<div style="padding:12px;display:flex;flex-direction:column;gap:10px">'
            f'{toast("발령 응답을 보내지 못했습니다 — " + b["explanation"], "bad", btn("이유 · 도움말", "sm", style="background:transparent"))}'
            f'<div class="inset" style="padding:8px 10px;display:flex;flex-direction:column;gap:4px"><span class="bz" style="font-size:11.5px;font-weight:700">이렇게 하면 됩니다 {chip("초안", "info")}</span>'
            f'<span class="t2" style="font-size:12px;line-height:1.5">{b["recoveryAdvice"]}</span></div>'
            f'<span class="note">사유 문장은 서버가 준 그대로. 「이렇게 하면 됩니다」는 실패 도움말(사유 · 명령)에서. 도움말 조회가 실패하면 이 칸만 숨긴다.</span></div></section>')
    right = f'<div style="flex:1 1 0;min-width:0;display:flex;flex-direction:column;gap:12px">{dispatch_card()}{res_}</div>'
    main = (f'<main style="flex-grow:1;min-width:0;display:flex;flex-direction:column;overflow:hidden">'
            f'{pagehead("조정", ["발령 · 포상 · 조정 결정", "관직 · 봉신", "외교"], "발령 · 포상 · 조정 결정")}'
            f'<div style="flex-grow:1;min-height:0;display:flex;gap:12px;padding:12px 16px">{court_list(p)}{right}</div></main>')
    page31('V31K7Reason.dc.html', 'K7 사유 → 도움말 연결(데스크톱)', shell_desk('조정', 'court', main))


def board_mreason():
    """사유 시트(모바일) — 12순 예약 「등용」이 거절됐다. 회복 조언 + 「도움말 — 등용」."""
    r = FR['BATTLE_PENDING']
    body = (f'<div style="padding:4px 16px 12px;display:flex;flex-direction:column;gap:10px">'
            f'<div style="border:1px solid #c96b5d;background:rgba(201,107,93,.12);padding:10px 12px;display:flex;flex-direction:column;gap:4px">'
            f'<span class="rs" style="font-size:14px;font-weight:700">{r["explanation"]}</span><span class="t2" style="font-size:12px">05순 등용 예약을 받지 않았습니다.</span></div>'
            f'<div class="inset" style="padding:10px 12px;display:flex;flex-direction:column;gap:4px"><span style="display:flex;align-items:center;justify-content:space-between">'
            f'<span class="bz" style="font-size:12px;font-weight:700">이렇게 하면 됩니다</span>{chip("초안", "info")}</span>'
            f'<span class="t2" style="font-size:12px;line-height:1.5">{r["recoveryAdvice"]}</span></div></div>')
    foot = btn('도움말 — 등용', '', 'help', style='flex:1', href='#') + btn('확인', 'primary', style='flex:1')
    hx, hy = MOB_PX(*CELLS[HERE])
    main = (f'<main aria-label="지도" style="position:relative;width:390px;height:844px;overflow:hidden">{mapimg("mob", 390, 844, "양적 일대 지도 — 현 보기")}'
            f'{me_marker(hx, hy - 22, "in", tag=False)}<div class="scrim"></div>'
            f'{sheet("등용 — 지금은 할 수 없습니다", body, height=340, foot=foot, bottom=64)}'
            f'<div style="position:absolute;left:0;right:0;bottom:0">{tabbar31("war")}</div></main>')
    page31('V31K7MReason.dc.html', 'K7 사유 시트 → 도움말(모바일)', main, w=390, h=844)


def board_tutorial():
    """P-A02 데스크톱(연습 서버) — 4단계 첫 발령: 조정 「발령 응답」의 「수락」에 목표 표시 + 서랍 「첫걸음」."""
    coach = coach_card(4, '첫 발령', '주공 조조가 보낸 발령에 답하세요. 「수락」을 누르면 됩니다.', 'left:12px;bottom:-128px', 300)
    card = f'<div style="position:relative;flex:1 1 0;min-width:0">{dispatch_card(coach=True)}{coach}</div>'
    cont = (f'<main style="flex-grow:1;min-width:0;display:flex;flex-direction:column;overflow:hidden">'
            f'{pagehead("조정", ["발령 · 포상 · 조정 결정", "관직 · 봉신", "외교"], "발령 · 포상 · 조정 결정")}'
            f'<div style="flex-grow:1;min-height:0;display:flex;gap:12px;padding:12px 16px;align-items:flex-start">{court_list()}{card}</div></main>')
    tb = (f'<div style="padding:10px 12px;display:flex;flex-direction:column;gap:10px;flex-shrink:0">{tut_bar(3, 4)}'
          + step_card(4, ['조정 › 발령 응답을 엽니다.', '발령 내용(자리 · 기한)을 봅니다.', '「수락」을 누릅니다.'],
                      '주공 조조의 발령이 왔습니다 — 답하면 됩니다.', where_here=True, help_name='발령 응답') + '</div>'
          + step_list(3, 4)
          + f'<div style="margin-top:auto;padding:8px 12px;border-top:1px solid #2c342f">{btn("첫걸음 안내 숨기기", "sm", style="width:100%;background:transparent")}</div>')
    main = cont + drawer(tb, tabs=['이 화면', '분류', '첫걸음'], on='첫걸음')
    page31('V31K7Tutorial.dc.html', 'K7 첫걸음 — 4단계 첫 발령(데스크톱 · 연습 서버)', shell_desk('조정', 'court', main, practice=True))


def board_mtutorial():
    """P-A02 모바일(연습 서버) — 6단계 첫 등용: 12순 시트의 「이번 순에 할 일」에 목표 표시, 카드는 시트 위."""
    hx, hy = MOB_PX(*CELLS[HERE])
    top = (f'<div style="position:absolute;left:8px;right:8px;top:8px;display:flex;align-items:center;gap:6px">'
           f'<span class="chip" style="height:44px;padding:0 10px;background:rgba(20,24,22,.94);font-size:12px">3월 중순 · 21:40</span>'
           f'<a href="#" class="hchip" data-guide="tutorial.chip" style="border-color:#4b6d87;color:#7aa7c7;background:rgba(20,24,22,.94)">첫걸음 5 / 8</a>'
           f'<span style="margin-left:auto;display:flex;gap:6px">{ibtn("mail", "서신 2통", 2, "background:rgba(20,24,22,.94)")}'
           f'{ibtn("help", "이 화면 도움말", style="background:rgba(20,24,22,.94)")}</span></div>')
    act = coach_ring(f'<a class="btn primary" href="#" style="flex:1;height:44px" data-input-id="action.search">이번 순에 할 일</a>', block=True)
    sh = (f'<section class="sheet" aria-label="명령 목록 12순" style="bottom:64px;height:124px"><div class="grip"></div>'
          f'<div style="height:52px;display:grid;grid-template-columns:24px minmax(0,1fr) auto;gap:8px;align-items:center;padding:0 12px;background:rgba(211,176,100,.08);box-shadow:inset 3px 0 0 #d3b064">'
          f'<span class="mono muted" style="font-size:12px">04</span><div style="display:flex;flex-direction:column;min-width:0"><span class="mono t2" style="font-size:11px">4월 중순 · 00:40</span>'
          f'<span class="muted" style="font-size:12px">빈 순</span></div>{chip("+ 예약")}</div>'
          f'<div style="padding:6px 12px;display:flex;gap:8px">{act}<button type="button" class="btn" aria-expanded="false" style="height:44px">{icon("up", 18)}12순</button></div></section>')
    coach = coach_card(6, '첫 등용', '먼저 인재탐색으로 재야 인물을 찾으세요. 찾으면 다음 순에 그 인물을 등용합니다.', 'left:8px;right:8px;bottom:204px', None,
                       '저항해도 이 단계는 끝납니다. 성공하면 부에 들어옵니다.')
    main = (f'<main aria-label="지도" style="position:relative;width:390px;height:844px;overflow:hidden">{mapimg("mob", 390, 844, "양적 일대 지도 — 현 보기")}'
            f'{deco(mlab("밀", *MOB_PX(*CELLS["밀현"]), dim=False) + mlab("영양", *MOB_PX(*CELLS["영양현"]), dim=False))}{me_marker(hx, hy, "in")}'
            f'{top}{view_bar(style="left:8px;top:64px", lod=False)}{coach}{sh}<div style="position:absolute;left:0;right:0;bottom:0">{tabbar31("war")}</div></main>')
    page31('V31K7MTutorial.dc.html', 'K7 첫걸음 — 6단계 첫 등용(모바일 · 연습 서버)', main, w=390, h=844)


def board_mtutorial_list():
    """P-A02 모바일 — 도움말 시트의 「첫걸음」: 막대 · 지금 단계 · 8단계 목록(찾기칸은 이 보기에서 접는다)."""
    body = (f'<div style="height:52px;flex-shrink:0;padding:0 12px 8px">{seg(["이 화면", "분류", "첫걸음"], "첫걸음", "도움말 보기", style="width:100%")}</div>'
            f'<div style="padding:0 12px 10px;display:flex;flex-direction:column;gap:10px;flex-shrink:0">{tut_bar(5, 6)}'
            + step_card(6, ['이번 순에 할 일 › 인물 › 인재탐색을 예약합니다.', '찾은 인물을 다음 빈 순에 등용합니다.'],
                        '인재탐색을 04순에 예약했습니다 — 다음 개인 턴 21:40에 처리됩니다.', 'info', help_name='등용') + '</div>'
            + step_list(5, 6, fold=True))
    main = f'<main style="position:relative;height:724px;flex-shrink:0;overflow:hidden">{mob_map_bg()}<div class="dim" style="pointer-events:none"></div>{sheet("도움말", body, top=0)}</main>'
    page31('V31K7MTutorialList.dc.html', 'K7 첫걸음 목록(모바일 · 연습 서버)', shell_mob(main, 'menu'), w=390, h=844)


def board_tutorial_states():
    """P-A02 상태 모음(데스크톱) — 제출됨 · 달성 알림 · 발령 기다림 · 실시간 전투 전 · 완주 · 401 · 실패 · 본 서버 안내판 · 장수 만들기 전."""
    def box(title, sub, inner):
        return (f'<section class="panel" style="min-height:0;overflow:hidden">{sec(title, sub)}'
                f'<div style="flex-grow:1;min-height:0;display:flex;flex-direction:column;padding:10px 12px;gap:8px;overflow:hidden">{inner}</div></section>')

    sent = step_card(3, ['주공 카드에서 「출사」를 누릅니다.'], '제출했습니다 — 다음 개인 턴 21:40에 처리됩니다.', 'info', help_name='출사')
    achieved = (f'<div style="border:1px solid #4b6d87">{band("tutorial")}</div>'
                f'<span class="note">알림 띠(P-W05)의 「첫걸음 달성」. 6초 뒤 접히고 머리줄 칩이 3 / 8 → 4 / 8 로 바뀐다. 서버 진척에 새로 늘어난 목표에만, 한 번.</span>'
                f'<span class="note">칩 = 완료 수, 카드 = 단계 번호(「5단계 · 첫 공사」).</span>')
    waiting = step_card(4, ['주공이 발령을 보내면 조정 › 발령 응답에 옵니다.'], '주공 조조의 발령을 기다립니다. 목표 표시는 발령이 오면 켜집니다.', 't2', help_name='발령 응답')
    battle = _state('clock', '#7aa7c7', '실시간 전투가 아직 열리지 않았습니다',
                    '전투는 AI가 대신 치르고, 결과가 오면 8단계 첫 전투가 끝납니다. 이기든 지든 됩니다.', '', chip('서버 준비 중', 'info'), pad=4)
    finish = (f'<div style="display:flex;flex-direction:column;gap:8px"><span class="serif" style="font-size:17px;font-weight:900">첫걸음을 마쳤습니다</span>'
              f'<div style="display:grid;grid-template-columns:1fr 1fr;gap:2px 12px">'
              + ''.join(f'<span style="font-size:12px;display:flex;align-items:center;gap:6px">{icon("check", 14, "#8fa77a")}{n}</span>' for _, n, _, _ in STEPS)
              + '</div><span class="t2" style="font-size:12px">연습 서버의 장수는 본 서버로 넘어가지 않습니다.</span>'
              f'<div style="display:flex;gap:6px">{btn("본 서버로 — 로비", "primary", style="flex:1", href="#")}{btn("연습 서버에 남기", "", style="flex:1")}</div></div>')
    auth = _state('lock', '#7aa7c7', '로그인하면 첫걸음을 이어 갑니다', '진행은 계정마다 서버에 남습니다.', btn('로그인', 'primary', href='#'), pad=4)
    fail = state_error('진행을 불러오지 못했습니다', '칩은 마지막 값을 유지하고 목표 표시는 끕니다.', pad=4)
    guide = (f'<span class="t2" style="font-size:12px;line-height:1.5">본 서버에서는 숫자 칩이 없습니다. 여덟 걸음을 어디서 하는지 안내만 합니다.</span>'
             f'<ol style="margin:0;padding:0;list-style:none;display:grid;grid-template-columns:1fr 1fr;gap:0 12px">'
             + ''.join(f'<li style="font-size:12.5px;padding:3px 0;border-bottom:1px solid #2c342f"><span class="mono bz">{k}</span> {n}</li>'
                       for k, (_, n, _, w) in enumerate(STEPS, 1))
             + f'</ol><span class="muted" style="font-size:11.5px">단계를 누르면 본 서버의 어느 화면에서 하는지 열린다.</span>'
             f'{btn("연습 서버에서 해 보기", "primary", "next", style="width:100%;flex-shrink:0", href="#")}')
    create = _state('clock', '#7aa7c7', '장수 만들기가 아직 열리지 않았습니다', '서버 준비 중입니다. 열리면 2단계 장수 생성부터 이어 갑니다.', '', chip('준비 중', 'info'), pad=4)
    g = 'display:grid;grid-template-columns:repeat(3,minmax(0,1fr));grid-template-rows:repeat(3,minmax(0,1fr));gap:12px;padding:12px 16px;flex-grow:1;min-height:0'
    main = (f'<main style="flex-grow:1;min-width:0;display:flex;flex-direction:column;overflow:hidden"><div style="{g}">'
            f'{box("제출됨 · 처리 대기", "3단계 첫 출사", sent)}{box("달성 알림", "알림 띠 · 한 번", achieved)}{box("NPC를 기다림", "4단계 첫 발령", waiting)}'
            f'{box("선행 API 없음 — 8단계", "실시간 전투 전", battle)}{box("완주", "대화상자 · 한 번", finish)}{box("401", "로그인 필요", auth)}'
            f'{box("불러오기 실패", "칩은 마지막 값", fail)}{box("본 서버 — 안내판", "첫걸음 보기", guide)}{box("선행 API 없음 — 2단계", "장수 생성 전", create)}'
            f'</div></main>')
    page31('V31K7TutorialStates.dc.html', 'K7 첫걸음 — 상태 모음(데스크톱)', shell_desk('첫걸음 — 상태', 'war', main, practice=True))


BOARDS = [board_help, board_help_topic, board_help_search, board_mhelp, board_mhelp_topic, board_reason, board_mreason,
          board_tutorial, board_mtutorial, board_mtutorial_list, board_tutorial_states]

if __name__ == '__main__':
    for f in glob.glob(os.path.join(P, 'V31K7*.dc.html')):
        os.remove(f)
    for b in BOARDS:
        b()
    print(f'ok boards_v31_k7 — {len(BOARDS)} boards' + ('' if HAVE_ASSETS else ' (v31assets 없음: 그림 자리 표시)'))
