# 캔버스 v3.1 · K6 전투 보드 개정(D24, ADR-LITE-049 2026-10-01 개정 — 사용자 승인) — 사용자 결정 D-BATTLE 2C · 1A 반영.
# D24 세부: 많을 때 축소하면 깃발 + 숫자로 묶기 · 판에서 고르기 = 두 점 누르기 + 데스크톱 마우스 끌기(모바일은 두 점) · 시작 배율 늘 원작 2배.
# 2C: 참전 군단의 모든 부곡이 동시에 출전한다(6 · 12자리 상한 없음, 수를 이유로 예비대 · 탈락 없음).
# 1A: 장수는 자기 군단의 부곡만 지휘한다(위임 없음) — 이 화면이 고르고 명령하는 것은 「내 군단 부곡」뿐이다.
# 승인 보드(V31K6BattleJoin · BattleLive · BattleLiveUnits · M*)의 「우리 쪽 여섯 자리」 · 「숫자키 1–6」 · 「두 자리 맞바꾸기」를
# 장수별로 묶은 동적 부곡 목록 · 여러 개 고르기 · 판 칸 고르기로 바꾼다. 판 그림 · B안 유닛 표기(원작 유닛 + 작은 깃발, D11) ·
# 성벽 윗면 막음(D12) · 명령 6개 + 집결 3개는 그대로다.
# 적 · 같은 편 다른 군단의 공개 범위는 결정 전(C2 계약 미합의) — 판에는 내 부곡만 그리고 상대 칸은 「서버 대기」다.
# 「목표」 단추는 C2 의도 집합(6명령 · 3집결점)에 없어 그리지 않는다(원장 행 없음 = 그리지 않음).
#   python3 boards_v31_k6_battle_v2.py  → project/V31K6v2*.dc.html
# 예시 상황은 기존 보드와 같다: 하후돈(주장) · 조조 소속 · 영천 북쪽 구릉 야전. 수치는 지어내지 않고 [값].
import glob
import os

from boards_v31_k6 import *  # noqa: F401,F403 — 판 · 유닛 표지 · 명령 막대 등 승인 보드 부품을 그대로 쓴다

# 내 군단 부곡(예시) — (장수 키, 장수 이름, 역할, [(부곡 이름, 병종, 칸 r, 칸 c, 지금 명령, 사기 경고)])
GROUPS = [
    ('hahoudon', '하후돈', '주장', [('본대 1', 'general', 34, 6, '대형', False), ('본대 2', 'inf', 35, 8, '대형', False),
                                   ('본대 3', 'inf', 33, 8, '수비', False), ('본대 궁', 'archer', 36, 5, '공격', False)]),
    ('heojeo', '허저', '부 인물', [('호위 1', 'inf', 34, 15, '돌격', False), ('호위 2', 'inf', 33, 13, '돌격', False), ('호위 기병', 'cav', 36, 14, '돌격', False)]),
    ('ijeon', '이전', '부 인물', [('이전 궁 1', 'archer', 25, 10, '후퇴 우선', True), ('이전 궁 2', 'archer', 27, 11, '공격', False)]),
]
# 부곡 수십 개(6장수 · 34부곡) — 묶음을 접어 둔 목록 · 판 전체 보기에서 겹침 숫자
MANY = [('hahoudon', '하후돈', '주장', 8, '대형 5 · 수비 3'), ('heojeo', '허저', '부 인물', 6, '돌격 6'), ('ijeon', '이전', '부 인물', 5, '공격 4 · 후퇴 우선 1'),
        ('', '악진', '부 인물', 6, '공격 6'), ('', '우금', '부 인물', 5, '수비 5'), ('', '조인', '부 인물', 4, '대형 4')]
ME = 'me'
KIND_LABEL = {'general': '장수 기마', 'cav': '기병', 'archer': '궁병', 'inf': '보병'}


def all_units():
    """판에 놓을 내 부곡 — iso_board 의 units 형태(r, c, 글자, 편, 이름표, ai, sel, est)와 병종 표."""
    units, kinds = [], {}
    for _k, gname, _role, rows in GROUPS:
        for bname, kind, r, c, _now, _warn in rows:
            label = f'{gname} · {bname}'
            units.append((r, c, gname[0], ME, label, False, False, False))
            kinds[label] = kind
    return units, kinds


def pick(units, chosen):
    """chosen(이름표 집합)을 고른 표시로."""
    return [(r, c, ch, side, label, ai, label in chosen, est) for r, c, ch, side, label, ai, sel, est in units]


def bugok_row(gname, bname, kind, now, warn, checked=None, sel=False, cell=None):
    """부곡 한 줄(누름 44+). checked=None 이면 체크 없이 한 개 고르기(배치), True/False 면 여러 개 고르기(진행)."""
    lead = '' if checked is None else (f'<i aria-hidden="true" class="cbx {"on" if checked else ""}" style="width:20px;height:20px;flex-shrink:0;border:1.5px solid {"#ffd36d" if checked else "#5a625c"};'
                                        f'background:{"#ffd36d" if checked else "transparent"};display:flex;align-items:center;justify-content:center">'
                                        f'{icon("check", 14, "#161410") if checked else ""}</i>')
    on = sel or bool(checked)
    w = f'<span class="rs" style="font-size:10.5px">사기 100 아래 — 명령을 받지 않는다</span>' if warn else ''
    where = f' · 칸 [값]' if cell else ''
    role = 'checkbox' if checked is not None else 'option'
    state = f'aria-checked="{"true" if checked else "false"}"' if checked is not None else f'aria-selected="{"true" if sel else "false"}"'
    return (f'<button type="button" role="{role}" {state} style="min-height:48px;width:100%;display:flex;align-items:center;gap:8px;padding:4px 8px;font:inherit;text-align:left;color:#ece6d8;'
            f'background:{"rgba(211,176,100,.10)" if on else "#141816"};border:{"2px solid #ffd36d" if sel else "1px solid #2c342f"};cursor:pointer">'
            f'{lead}<span style="flex-shrink:0">{unit_sprite(kind, ME, 1)}</span>'
            f'<span style="display:flex;flex-direction:column;min-width:0;flex:1;gap:1px"><span style="display:flex;gap:6px;align-items:center">'
            f'<span class="serif" style="font-weight:700;font-size:13px">{bname}</span><span class="muted" style="font-size:11px">{KIND_LABEL[kind]}</span></span>'
            f'<span class="t2" style="font-size:11px">병력 [값] · 사기 [값] · {now}{where}</span>{w}</span></button>')


def group_head(key, gname, role, n, summary, open_=True, checked=None, partial=False):
    """장수 묶음 머리 — 펼침 · 이 장수 부곡 전부 고르기(여러 개 고르기에서만)."""
    if checked is None:
        lead = ''
    else:
        mark = icon('check', 14, '#161410') if checked else ('<span style="width:10px;height:2px;background:#161410;display:block"></span>' if partial else '')
        fill = '#ffd36d' if (checked or partial) else 'transparent'
        lead = (f'<button type="button" role="checkbox" aria-checked="{"true" if checked else ("mixed" if partial else "false")}" aria-label="{gname} 부곡 전부 고르기" '
                f'style="width:44px;height:44px;flex-shrink:0;padding:0;border:0;background:transparent;display:flex;align-items:center;justify-content:center;cursor:pointer">'
                f'<i aria-hidden="true" style="width:20px;height:20px;border:1.5px solid {"#ffd36d" if (checked or partial) else "#5a625c"};background:{fill};display:flex;align-items:center;justify-content:center">{mark}</i></button>')
    caret = '▾' if open_ else '▸'
    return (f'<div style="display:flex;align-items:center;gap:2px;background:#1b201d;border-bottom:1px solid #2c342f">{lead}'
            f'<button type="button" aria-expanded="{"true" if open_ else "false"}" style="flex:1;min-height:44px;display:flex;align-items:center;gap:8px;padding:2px 8px;font:inherit;text-align:left;color:#ece6d8;background:transparent;border:0;cursor:pointer">'
            f'{portrait(key, gname, 24, 34)}<span style="display:flex;flex-direction:column;min-width:0;flex:1"><span style="display:flex;gap:6px;align-items:center">'
            f'<span class="serif" style="font-weight:700">{gname}</span>{chip(role, "bronze" if role == "주장" else "")}<span class="muted" style="font-size:11px">부곡 {n}</span></span>'
            f'<span class="t2" style="font-size:11px">{summary}</span></span><span class="muted" aria-hidden="true" style="font-size:14px">{caret}</span></button></div>')


def zone_overlay(kind, s, ox, oy, r0, r1, c0, c1, vw, vh):
    """배치 구역(내가 부곡을 놓을 수 있는 칸) — 칸 사각 범위를 아이소 마름모로. 실제 구역은 서버 SNAPSHOT 이 준다(계약 보강 요청 #4)."""
    pts = [iso_px(kind, r0, c0, s, ox, oy), iso_px(kind, r0, c1, s, ox, oy), iso_px(kind, r1, c1, s, ox, oy), iso_px(kind, r1, c0, s, ox, oy)]
    poly = ' '.join(f'{x},{y}' for x, y in pts)
    return (f'<svg width="{vw}" height="{vh}" style="position:absolute;left:0;top:0;pointer-events:none" aria-hidden="true">'
            f'<polygon points="{poly}" fill="rgba(111,155,124,.14)" stroke="#6f9b7c" stroke-width="2" stroke-dasharray="6 4"></polygon></svg>')


def enemy_wait():
    return (f'<section class="panel">{sec("상대 — 보이는 만큼", "")}<div style="padding:10px 12px;display:flex;flex-direction:column;gap:6px">'
            f'<span style="display:flex;gap:6px;align-items:center">{chip("서버 대기", "info")}<span class="t2" style="font-size:12px">공개 범위가 아직 정해지지 않았습니다</span></span>'
            f'<span class="muted" style="font-size:11.5px;line-height:1.5">적 · 같은 편 다른 군단을 얼마나 보일지 정해지면 판과 이 칸에 그린다. 그 전에는 추정값을 만들지 않는다.</span></div></section>')


# ================================================================== P-C03 참가 대기 · 배치(데스크톱)
def board_join():
    top = (f'<div style="height:64px;flex-shrink:0;display:flex;align-items:center;gap:14px;padding:0 16px;border-bottom:1px solid #9c7f3f;background:rgba(211,176,100,.08)">'
           f'<span class="mono bz" style="font-size:30px;font-weight:700">0:42</span><span style="display:flex;flex-direction:column"><span class="serif" style="font-size:17px;font-weight:900">개전까지 — 참가 대기 · 배치</span>'
           f'<span class="t2" style="font-size:12px">야전 · 영천 북쪽 구릉 · 조조 ↔ [적 세력] · 내 군단 부곡 9개가 모두 나간다</span></span>'
           f'<span style="margin-left:auto;display:flex;gap:8px">{btn("입장", "primary", attrs="data-guide=\"tutorial.battle\"")}{btn("기본 배치 그대로")}{btn("나가기 — AI 에게 맡긴다")}</span></div>')
    rows = ''
    for key, gname, role, items in GROUPS:
        rows += group_head(key, gname, role, len(items), f'병력 합 [값] · {" · ".join(sorted({KIND_LABEL[k] for _b, k, *_x in items}))}')
        rows += ''.join(bugok_row(gname, b, k, '배치', w, sel=(gname == '허저' and b == '호위 1'), cell=True) for b, k, _r, _c, _n, w in items)
    left = (f'<section class="panel" style="width:340px;flex-shrink:0;display:flex;flex-direction:column;min-height:0">{sec("내 군단 부곡 9", "장수별 · 하나 골라 판의 칸을 누른다")}'
            f'<div role="listbox" aria-label="내 군단 부곡" style="padding:6px 8px;display:flex;flex-direction:column;gap:4px;overflow:hidden">{rows}</div></section>')
    s, ox, oy = 1.25, -60, -20
    units, kinds = all_units()
    units = pick(units, {'허저 · 호위 1'})
    marks = [(34, 15, 'sel', ''), (29, 15, 'rally', '→')]
    note = ('<div style="position:absolute;left:10px;bottom:10px;max-width:440px;padding:8px 10px;background:rgba(27,32,29,.94);border:1px solid #3d4740;font-size:12px;line-height:1.55" class="t2">'
            '<b class="bz">허저 · 호위 1</b>을 골랐다 — 초록 점선(배치 구역) 안의 칸을 누르면 그리로 옮긴다. 내 부곡이 있는 칸이면 둘을 맞바꾼다. '
            '구역 밖 · 다른 군단 부곡은 누를 수 없다(누르면 사유).</div>')
    tag = '<span class="chip info" style="position:absolute;left:10px;top:10px;background:rgba(20,24,22,.94)">D24 — 6자리 대신 부곡 전부(2C)</span>'
    board = iso_board('field', 728, 660, s, ox, oy, units, marks, extra=zone_overlay('field', s, ox, oy, 22, 40, 2, 18, 728, 660) + note + tag + board_zoom(),
                      sprite_kind=kinds, sprite_k=1)
    right = (f'<div style="width:288px;flex-shrink:0;display:flex;flex-direction:column;gap:12px">{enemy_wait()}'
             f'<section class="panel">{sec("전장", "티켓에 고정")}<div style="padding:8px 12px">'
             + fieldrow('판', '야전 판 · [값]번') + fieldrow('날씨 · 밤 · 계절', chip('서버 대기', 'info')) + fieldrow('목표', chip('서버 대기', 'info'))
             + fieldrow('길이', '5분 · 3,000틱') + '</div></section>'
             f'<section class="panel" style="flex-grow:1">{sec("안내", "")}<ul class="ul" style="padding:4px 12px">'
             '<li><b>모두 나간다</b> — 내 군단 부곡은 수와 상관없이 전부 판에 선다. 예비대 · 빠지는 부곡은 없다.</li>'
             '<li><b>옮기기</b> — 부곡 하나를 고르고 칸을 누른다. 내 부곡끼리는 맞바꾼다. 끌기는 없다.</li>'
             '<li><b>기본 배치</b> — 서버가 정한 자리. 안 고치거나 안 들어오면 0:00 에 그대로 선다.</li>'
             '<li><b>다른 군단</b> — 같은 편이어도 그 장수의 부곡은 옮기지 못한다.</li></ul></section></div>')
    body = top + f'<div style="flex-grow:1;display:flex;gap:12px;padding:12px;min-height:0">{left}<div style="flex:1 1 0;min-width:0;display:flex;flex-direction:column;gap:8px">{board}</div>{right}</div>'
    page31('V31K6v2BattleJoin.dc.html', 'K6 전투 참가 대기 · 배치 — D24 개정(데스크톱)',
           shell_desk('전투', 'corps', f'<main style="flex-grow:1;min-width:0;display:flex;flex-direction:column">{body}</main>'))


# ================================================================== P-C05 실시간 전투(데스크톱) — 여러 개 고르기
def cmdbar_v2(n, mobile=False):
    """명령 막대 — 6명령 + 집결 3 + 계책. 「목표」는 의도 집합에 없어 그리지 않는다. 일기토는 조건 단추."""
    b = ''.join(f'<button type="button" class="btn" style="{"flex:1 1 0;padding:0 4px" if mobile else "min-width:76px"}">{c}</button>' for c in CMDS)
    r = ''.join(f'<button type="button" class="btn" style="{"flex:1 1 0;padding:0 4px" if mobile else ""}">집결 {i}</button>' for i in (1, 2, 3))
    if mobile:
        return (f'<div style="display:flex;flex-direction:column;gap:4px;padding:6px 8px;border-top:1px solid #3d4740;background:#1b201d">'
                f'<span class="t2" style="font-size:11.5px">고른 부곡 <b class="bz">{n}</b>개에게</span>'
                f'<div style="display:flex;gap:4px">{b}</div><div style="display:flex;gap:4px">{r}<button type="button" class="btn" style="flex:1 1 0;padding:0 4px">계책</button></div></div>')
    return (f'<div style="height:64px;flex-shrink:0;display:flex;align-items:center;gap:6px;padding:0 12px;border-top:1px solid #3d4740;background:#1b201d">'
            f'<span class="t2" style="font-size:12px;margin-right:4px;white-space:nowrap">고른 부곡 <b class="bz">{n}</b>개에게</span>{b}<span style="width:1px;height:32px;background:#3d4740"></span>{r}'
            f'<span style="width:1px;height:32px;background:#3d4740"></span><button type="button" class="btn">계책</button>{btn_off("일기토", "조건 안 됨")}</div>')


def select_bar(n, total, mobile=False):
    return (f'<div style="display:flex;align-items:center;gap:6px;padding:6px 8px;border-bottom:1px solid #2c342f;flex-wrap:wrap">'
            f'<span class="t2" style="font-size:12px">고른 부곡 <b class="bz">{n}</b> / {total}</span>'
            f'<span style="margin-left:auto;display:flex;gap:4px">{btn("내 부곡 전부", "sm")}{btn("다 풀기", "sm")}</span></div>')


def board_live():
    chosen = {'허저 · 호위 1', '허저 · 호위 2', '허저 · 호위 기병', '하후돈 · 본대 궁'}
    top = (f'<div style="height:52px;flex-shrink:0;display:flex;align-items:center;gap:14px;padding:0 16px;border-bottom:1px solid #3d4740;background:#1b201d">'
           f'<span class="mono bz" style="font-size:24px;font-weight:700">3:12</span><span class="muted" style="font-size:12px">남음 · 틱 [값] / 3,000</span>'
           f'{chip("야전 · 영천 북쪽 구릉")}{chip("D24 · 부곡 전부", "info")}<span class="t2" style="font-size:12.5px">방금 — 허저 호위 1 돌격 받음</span>'
           f'<span style="margin-left:auto;display:flex;gap:6px">{btn("판에서 고르기", "sm")}{btn("나가기 — AI 에게 맡긴다", "sm")}</span></div>')
    rows = ''
    for key, gname, role, items in GROUPS:
        sel = [b for b, *_x in items if f'{gname} · {b}' in chosen]
        rows += group_head(key, gname, role, len(items), ' · '.join(sorted({n for *_y, n, _w in items})), checked=len(sel) == len(items), partial=0 < len(sel) < len(items))
        rows += ''.join(bugok_row(gname, b, k, n, w, checked=f'{gname} · {b}' in chosen) for b, k, _r, _c, n, w in items)
    left = (f'<section class="panel" style="width:320px;flex-shrink:0;display:flex;flex-direction:column;min-height:0">{sec("내 군단 부곡 9", "여럿 고르기 · 장수 머리로 묶음 고르기")}'
            f'{select_bar(len(chosen), 9)}<div role="group" aria-label="내 군단 부곡" style="padding:6px 8px;display:flex;flex-direction:column;gap:4px;overflow:hidden">{rows}</div></section>')
    s = B_S   # 원작 2배 — 시작 배율은 부곡 수와 상관없이 늘 2배(D24). 넓게 보기는 「−」 · 「전체」
    cx, cy = iso_px('field', 34, 10, s)
    ox, oy = 368 - cx, 380 - cy
    units, kinds = all_units()
    units = pick(units, chosen)
    marks = [(34, 15, 'sel', ''), (33, 13, 'sel', ''), (36, 14, 'sel', ''), (36, 5, 'sel', ''), (38, 10, 'rally', '1')]
    mini = board_mini('field', 200, -ox / s, -oy / s, 736 / s, 740 / s, units)
    tag = ('<span class="chip bronze" style="position:absolute;left:10px;top:10px;background:rgba(20,24,22,.94)">원작 유닛 · 원작 2배로 시작 — 「−」 · 「전체」로 넓게</span>')
    board = iso_board('field', 736, 740, s, ox, oy, units, marks, extra=mini + board_zoom() + tag, sprite_kind=kinds, sprite_k=2)
    log = ''.join(f'<li><span class="mono muted">{t}</span> {x}</li>' for t, x in [
        ('3:12', '<b>명령 받음</b> — 허저 부곡 3 · 하후돈 본대 궁 돌격, 틱 [값]부터'), ('3:20', '<span class="rs">명령 거절</span> — 이전 궁 1: 사기가 낮아 물러나는 중'),
        ('3:31', '계책 「간파」 공개 — 적 계책 무효'), ('3:40', '하후돈 본대 대형')])
    right = (f'<div style="width:300px;flex-shrink:0;display:flex;flex-direction:column;gap:12px;min-height:0">'
             f'<section class="panel" style="flex-grow:1;min-height:0">{sec("사건", "")}<ul class="ul" style="padding:4px 12px">{log}</ul></section>'
             f'{enemy_wait()}'
             f'<section class="panel">{sec("내가 없을 때", "")}<div style="padding:8px;display:flex;flex-direction:column;gap:6px">'
             f'{warnbox("연결이 끊겼습니다 — 다시 잇는 중. 그동안 내 군단 부곡 전부를 AI 가 맡습니다.")}'
             f'<span class="muted" style="font-size:11.5px;line-height:1.5">AI 가 맡는 동안 목록 머리와 판 깃발에 「AI」가 붙는다. 들어오면 다음 틱에 넘겨받는다.</span></div></section></div>')
    body = (top + f'<div style="flex-grow:1;display:flex;gap:12px;padding:12px;min-height:0">{left}'
            f'<div style="flex:1 1 0;min-width:0;display:flex;justify-content:center">{board}</div>{right}</div>' + cmdbar_v2(len(chosen)))
    page31('V31K6v2BattleLive.dc.html', 'K6 실시간 전투 — D24 개정 여러 개 고르기(데스크톱)',
           shell_desk('전투', 'corps', f'<main style="flex-grow:1;min-width:0;display:flex;flex-direction:column">{body}</main>'))


# ================================================================== 부곡 수십 개(데스크톱) — 접은 묶음 · 판 전체 보기 겹침 숫자
def board_live_many():
    top = (f'<div style="height:52px;flex-shrink:0;display:flex;align-items:center;gap:14px;padding:0 16px;border-bottom:1px solid #3d4740;background:#1b201d">'
           f'<span class="mono bz" style="font-size:24px;font-weight:700">2:05</span><span class="muted" style="font-size:12px">남음 · 틱 [값] / 3,000</span>'
           f'{chip("야전 · 영천 북쪽 구릉")}{chip("D24 — 부곡 34", "info")}'
           f'<span style="margin-left:auto;display:flex;gap:6px">{btn("판에서 고르기", "sm")}{btn("나가기 — AI 에게 맡긴다", "sm")}</span></div>')
    heads = ''
    for i, (key, gname, role, n, summary) in enumerate(MANY):
        heads += group_head(key, gname, role, n, summary, open_=(i == 1), checked=(i == 1), partial=(i == 2))
        if i == 1:
            heads += ''.join(bugok_row(gname, f'호위 {j}', 'inf' if j < 5 else 'cav', '돌격', False, checked=True) for j in range(1, 4))
            heads += f'<div class="muted" style="font-size:11.5px;padding:4px 8px">… 허저 부곡 3개 더 — 목록은 굴려 본다</div>'
    left = (f'<section class="panel" style="width:320px;flex-shrink:0;display:flex;flex-direction:column;min-height:0">{sec("내 군단 부곡 34", "장수 6명 · 묶음은 접어 둔다")}'
            f'{select_bar(9, 34)}<div role="group" aria-label="내 군단 부곡" style="padding:6px 8px;display:flex;flex-direction:column;gap:4px;overflow:hidden">{heads}</div></section>')
    # 「−」로 넓게 본 상태(배치 구역 전체가 한 창에) — 시작은 늘 2배이고, 묶음(깃발 하나 + 숫자)은 축소했을 때만 나타난다(D24).
    # 「+」 · 묶음 누르기로 다가가면 하나씩 갈라진다.
    s = 1.6
    cx, cy = iso_px('field', 31, 10, s)
    ox, oy = 368 - cx, 370 - cy
    clusters = [(24, 4, '하', 8, False), (28, 16, '허', 6, True), (32, 8, '이', 5, False), (38, 6, '악', 6, False), (36, 16, '우', 5, False), (30, 0, '조', 4, False)]
    lay = ''
    for r, c, ch, n, sel in clusters:
        x, y = iso_px('field', r, c, s, ox, oy)
        lay += (f'<button type="button" aria-label="{ch} 부곡 {n}개 — 누르면 다가간다" aria-pressed="{"true" if sel else "false"}" style="position:absolute;left:{x - 22}px;top:{y - 40}px;width:44px;height:44px;padding:0;border:0;background:transparent;cursor:pointer">'
                f'<svg width="30" height="26" viewBox="0 0 30 26" aria-hidden="true" style="position:absolute;left:7px;top:18px"><path d="M3 1v24" stroke="#1b201d" stroke-width="2"></path>'
                f'<path d="M4 2h24l-6 7 6 7H4z" fill="{SIDE_COLOR[ME]}" stroke="{"#ffd36d" if sel else "#0c0f0e"}" stroke-width="{2 if sel else 1}"></path>'
                f'<text x="13" y="13" text-anchor="middle" font-family="Noto Serif KR,serif" font-weight="900" font-size="11" fill="#fff">{ch}</text></svg>'
                f'<span class="mono" style="position:absolute;right:-2px;top:6px;min-width:18px;height:16px;padding:0 3px;font-size:10px;font-weight:700;line-height:16px;color:#161410;background:{"#ffd36d" if sel else "#ece6d8"}">{n}</span></button>')
    note = ('<div style="position:absolute;left:10px;bottom:10px;max-width:460px;padding:8px 10px;background:rgba(27,32,29,.94);border:1px solid #3d4740;font-size:12px;line-height:1.55" class="t2">'
            '「−」로 넓게 본 상태 — 시작은 늘 원작 2배다. 축소하면 가까이 모인 부곡을 깃발 하나와 숫자로 묶고, 묶음을 누르면 그 자리로 다가가 하나씩 갈라진다(유닛 그림). '
            '목록 · 「판에서 고르기」로 여럿을 한 번에 고른다.</div>')
    tag = '<span class="chip" style="position:absolute;left:10px;top:10px;background:rgba(20,24,22,.94)">「−」로 넓게 본 상태 · 묶음은 축소했을 때만</span>'
    board = iso_board('field', 736, 740, s, ox, oy, (), extra=zone_overlay('field', s, ox, oy, 22, 40, 0, 18, 736, 740) + lay + note + tag + board_zoom())
    right = (f'<div style="width:300px;flex-shrink:0;display:flex;flex-direction:column;gap:12px;min-height:0">'
             f'<section class="panel">{sec("판에서 고르기", "데스크톱 · 모바일")}<div style="padding:8px 12px;display:flex;flex-direction:column;gap:6px">'
             f'<span class="t2" style="font-size:12px;line-height:1.55"><b>데스크톱</b> — 마우스로 판을 끌어 사각형 안의 내 부곡을 고른다. 「판에서 고르기」를 켜고 두 점을 눌러도 된다.</span>'
             f'<span class="t2" style="font-size:12px;line-height:1.55"><b>모바일</b> — 「판에서 고르기」를 켜고 두 점을 누른다(끌기는 판 움직이기).</span>'
             f'<span class="muted" style="font-size:11.5px">데스크톱은 Shift · Ctrl 누르고 목록 · 깃발을 눌러 더하기도 된다(보조).</span></div></section>'
             f'{enemy_wait()}'
             f'<section class="panel" style="flex-grow:1;min-height:0">{sec("사건", "부곡이 많으면 장수별로 묶음")}<ul class="ul" style="padding:4px 12px">'
             f'<li><span class="mono muted">2:05</span> <b>명령 받음</b> — 허저 부곡 6 돌격</li><li><span class="mono muted">2:11</span> 악진 부곡 2 교전 · 1 물러남</li>'
             f'<li><span class="mono muted">2:20</span> <span class="rs">명령 거절</span> — 이전 부곡 1: 사기가 낮아 물러나는 중</li></ul></section></div>')
    body = (top + f'<div style="flex-grow:1;display:flex;gap:12px;padding:12px;min-height:0">{left}'
            f'<div style="flex:1 1 0;min-width:0;display:flex;justify-content:center">{board}</div>{right}</div>' + cmdbar_v2(9))
    page31('V31K6v2BattleLiveMany.dc.html', 'K6 실시간 전투 — D24 개정 부곡 수십 개(데스크톱)',
           shell_desk('전투', 'corps', f'<main style="flex-grow:1;min-width:0;display:flex;flex-direction:column">{body}</main>'))


# ================================================================== 모바일 — 참가 대기 · 배치 / 진행 / 고르기 시트
def board_mjoin():
    fs = 374 / 1024
    units, kinds = all_units()
    units = pick(units, {'허저 · 호위 1'})
    zs = 1.6
    zx, zy = iso_px('field', 34, 12, zs)   # 확대 창 가운데 = 고른 부곡(허저 호위 1) 둘레
    zox, zoy = 187 - zx, 140 - zy
    rx, ry, rw, rh = (-zox / zs) * fs, (-zoy / zs) * fs, (374 / zs) * fs, (260 / zs) * fs
    full = iso_board('field', 374, 199, fs, 0, 0, units, small=True,
                     extra=zone_overlay('field', fs, 0, 0, 22, 40, 2, 18, 374, 199) + f'<span style="position:absolute;left:{rx:.0f}px;top:{ry:.0f}px;width:{rw:.0f}px;height:{rh:.0f}px;border:2px solid #ffd36d"></span>')
    zoom = iso_board('field', 374, 260, zs, zox, zoy, units, [(34, 15, 'sel', ''), (29, 15, 'rally', '→')],
                     extra=zone_overlay('field', zs, zox, zoy, 22, 40, 2, 18, 374, 260), sprite_kind=kinds, sprite_k=1)
    rows = (group_head('heojeo', '허저', '부 인물', 3, '호위 1 고름 · 칸을 누르면 옮긴다')
            + bugok_row('허저', '호위 1', 'inf', '배치', False, sel=True, cell=True)
            + group_head('hahoudon', '하후돈', '주장', 4, '본대 4 · 펼치면 보인다', open_=False)
            + group_head('ijeon', '이전', '부 인물', 2, '궁 2 · 하나 사기 100 아래', open_=False))
    inner = (f'<div style="height:52px;display:flex;align-items:center;gap:10px;padding:0 12px;border-bottom:1px solid #9c7f3f;background:rgba(211,176,100,.08)">'
             f'<span class="mono bz" style="font-size:24px;font-weight:700">0:42</span><span class="t2" style="font-size:12px;flex:1">야전 · 부곡 9개 모두 나감</span>'
             f'{btn("입장", "primary", attrs="data-guide=\"tutorial.battle\"")}</div>'
             f'<div style="padding:6px 8px 0">{full}</div>'
             f'<div style="padding:4px 8px 0;display:flex;flex-direction:column;gap:2px"><span class="muted" style="font-size:11px">누른 자리 확대 — 초록 점선 안 칸을 누르면 옮긴다 · 내 부곡 칸이면 맞바꾼다</span>{zoom}</div>'
             f'<div role="listbox" aria-label="내 군단 부곡" style="padding:6px 8px;display:flex;flex-direction:column;gap:4px">{rows}</div>')
    page31('V31K6v2MBattleJoin.dc.html', 'K6 전투 참가 대기 · 배치 — D24 개정(모바일)', mtop31('전투', '전투 목록') + mmain(inner, h=788), w=MW, h=MH)


def board_mlive():
    chosen = {'허저 · 호위 1', '허저 · 호위 2', '허저 · 호위 기병', '하후돈 · 본대 궁'}
    fs = 374 / 1024
    units, kinds = all_units()
    units = pick(units, chosen)
    zs = 1.6
    zx, zy = iso_px('field', 34, 10, zs)   # 확대 창 가운데 = 고른 부곡(허저 셋 · 하후돈 본대 궁) 둘레
    zox, zoy = 187 - zx, 135 - zy
    rx, ry, rw, rh = (-zox / zs) * fs, (-zoy / zs) * fs, (374 / zs) * fs, (250 / zs) * fs
    full = iso_board('field', 374, 199, fs, 0, 0, units, small=True,
                     extra=f'<span style="position:absolute;left:{rx:.0f}px;top:{ry:.0f}px;width:{rw:.0f}px;height:{rh:.0f}px;border:2px solid #ffd36d"></span>')
    zoom = iso_board('field', 374, 250, zs, zox, zoy, units, [(34, 15, 'sel', ''), (33, 13, 'sel', ''), (36, 14, 'sel', ''), (36, 5, 'sel', '')], sprite_kind=kinds, sprite_k=1)
    selbar = (f'<button type="button" aria-haspopup="dialog" style="width:100%;min-height:48px;display:flex;align-items:center;gap:8px;padding:4px 10px;font:inherit;color:#ece6d8;'
              f'background:#141816;border:1px solid #d3b064;cursor:pointer"><span class="t2" style="font-size:12.5px">고른 부곡 <b class="bz">4</b> / 9 — 허저 3 · 하후돈 1</span>'
              f'<span style="margin-left:auto" class="bz">고르기 ▸</span></button>')
    inner = (f'<div style="height:44px;display:flex;align-items:center;gap:10px;padding:0 12px;border-bottom:1px solid #3d4740">'
             f'<span class="mono bz" style="font-size:20px;font-weight:700">3:12</span><span class="t2" style="font-size:12px;flex:1">야전 · 영천 북쪽</span>{btn("판에서 고르기", "sm")}</div>'
             f'<div style="padding:6px 8px 0">{full}</div>'
             f'<div style="padding:4px 8px 0;display:flex;flex-direction:column;gap:2px"><span class="muted" style="font-size:11px">누른 자리 확대 — 깃발을 눌러 더하거나 뺀다</span>{zoom}</div>'
             f'<div style="padding:6px 8px">{selbar}</div>' + cmdbar_v2(4, mobile=True))
    page31('V31K6v2MBattleLive.dc.html', 'K6 실시간 전투 — D24 개정(모바일)', mtop31('전투', '전투 목록') + mmain(inner, h=788), w=MW, h=MH)


def board_mlive_sheet():
    chosen = {'허저 · 호위 1', '허저 · 호위 2', '허저 · 호위 기병', '하후돈 · 본대 궁'}
    rows = ''
    for key, gname, role, items in GROUPS:
        sel = [b for b, *_x in items if f'{gname} · {b}' in chosen]
        open_ = gname != '이전'
        rows += group_head(key, gname, role, len(items), ' · '.join(sorted({n for *_y, n, _w in items})), open_=open_, checked=len(sel) == len(items), partial=0 < len(sel) < len(items))
        if open_:
            rows += ''.join(bugok_row(gname, b, k, n, w, checked=f'{gname} · {b}' in chosen) for b, k, _r, _c, n, w in items)
    body = (select_bar(4, 9, mobile=True)
            + f'<div role="group" aria-label="내 군단 부곡" style="padding:6px 8px;display:flex;flex-direction:column;gap:4px;overflow:hidden">{rows}</div>')
    foot = btn('다 풀기', style='flex:1') + btn('고른 4개로 — 명령 막대', 'primary', style='flex:2')
    fs = 374 / 1024
    units, _k = all_units()
    inner = (f'<div style="padding:6px 8px">{iso_board("field", 374, 199, fs, 0, 0, pick(units, chosen), small=True)}</div>'
             f'<div class="scrim"></div>{sheet("부곡 고르기", body, top=96, foot=foot)}')
    page31('V31K6v2MBattleLiveSheet.dc.html', 'K6 실시간 전투 — D24 개정 부곡 고르기 시트(모바일)', mtop31('전투', '전투 목록') + mmain(inner, h=788), w=MW, h=MH)


BOARDS_V2 = [board_join, board_live, board_live_many, board_mjoin, board_mlive, board_mlive_sheet]

if __name__ == '__main__':
    for f in glob.glob(os.path.join(P, 'V31K6v2*.dc.html')):
        os.remove(f)
    for b in BOARDS_V2:
        b()
    print(f'ok boards_v31_k6_battle_v2 — {len(BOARDS_V2)} boards' + ('' if HAVE_ASSETS else ' (v31assets 없음: 그림 자리 표시)'))
