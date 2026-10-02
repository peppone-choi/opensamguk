"""K2 지도 — 새 지도(탑다운) 군단 표지 세 상태. ADR-LITE-049 개정(사용자 승인 2026-10-02, 원장 §1 D34).

옛 지도(`web/shared/src/iso/corpsOverlay.ts`)에는 있지만 v3.1 승인 보드에 모양이 없는 것 셋을 새 타일 위에 옮겨 그린다(K0 10-02 지시).
  - 첩보(마지막 목격): α 0.55 + 점선 테두리 + 「?」 — 옛 지도 값 그대로
  - 내 군단: 토큰 청동(#d3b064) 테두리 2px + 행군 경로(D34: 옛 지도 #c9a656 대신 토큰 청동)
  - 병력 띠: 내 군단은 정확한 수, 다른 세력은 병력대(서버 troopsBand)
표지 자체는 승인 보드의 군단 표지(`mk(..., 'corps')`, 원형 44 · 첫 글자)로 그린다. 앱은 #1102 원작 부대 몸통 + 장수 깃발을 그리고,
세 상태 처리는 둘 다 같다. `python3 boards_v31_k2.py`
"""
from v31system import *  # noqa: F401,F403

BRONZE = '#d3b064'  # 디자인 토큰 청동(--bronze), D34 승인 「가」
STATE_LABEL = {'seen': '보임', 'intel': '첩보 · 마지막 목격', 'own': '내 군단'}


def corps_mark(x, y, name, state='seen', band='', age='', zoom='county'):
    """군단 표지(승인 보드 원형 44) + 상태. x, y = 표지 가운데. 병력 띠 · 첩보 나이는 현 보기에서만."""
    style = f'left:{x}px;top:{y}px'
    badge = ''
    if state == 'intel':
        style += ';opacity:.55;border-style:dashed'
        badge = ('<span aria-hidden="true" style="position:absolute;right:-9px;top:-11px;width:18px;height:18px;border-radius:50%;'
                 'background:#ece6d8;color:#161410;font:900 13px/18px \'Noto Serif KR\',serif;text-align:center">?</span>')
    elif state == 'own':
        style += f';border:2px solid {BRONZE};box-shadow:0 0 0 1px rgba(18,12,6,.92)'
    aria = f'{name} — 군단, {STATE_LABEL[state]}' + (f', {band}' if band else '') + (f', {age}' if age else '')
    mark = f'<button type="button" class="mk corps" aria-label="{aria}" style="{style}">{name[0]}{badge}</button>'
    if zoom != 'county':
        return mark
    under = band + (f' · {age}' if age else '')
    tag = (f'<span class="mlab" style="left:{x}px;top:{y + 26}px;height:20px;font-size:12px;font-family:\'Noto Sans KR\',sans-serif;'
           f'{"opacity:.75" if state == "intel" else ""}">{under}</span>') if under else ''
    return mark + tag


def cell(title, inner, w=300, h=200, img='desk', left=0, top=0):
    iw, ih = {'desk': (1048, 952), 'jun': (840, 480), 'mob': (390, 844)}[img]
    return (f'<figure style="margin:0;display:flex;flex-direction:column;gap:6px"><div style="position:relative;width:{w}px;height:{h}px;overflow:hidden;'
            f'border:1px solid #3d4740;background:#0c0f0e">{mapimg(img, iw, ih, "지도", left, top)}{inner}</div>'
            f'<figcaption class="t2" style="font-size:12px">{title}</figcaption></figure>')


DIFFS = [
    ('색 대비', '첩보 α .55 는 밝은 평지 타일에서 흐려 보이지 않을 수 있다 — 색만으로 가르지 않고 점선 테두리 + 「?」를 같이 단다.'),
    ('청동 · 금색', '내 군단 청동(토큰 #d3b064)과 고른 곳 금색(#ffd36d)이 가깝다 — 고른 곳은 3px 금색 사각, 내 군단은 2px 청동 원으로 모양이 다르다.'),
    ('44', '누를 영역은 상태와 상관없이 44 이상(첩보로 흐려도 줄지 않음). 「?」 표는 누를 영역 안.'),
    ('겹칠 때', '위에서부터 내 위치 핀 > 내 군단 > 보이는 군단 > 첩보. 병력 띠 · 이름표는 표지를 피하고, 못 피하면 첩보 · 보임 순으로 띠를 뺀다(내 군단 띠는 남긴다).'),
    ('보기 수준', '병력 띠 · 첩보 나이는 현 보기에서만. 군 보기는 표지 + 상태(흐림 · 「?」 · 청동)만, 주 보기는 군단 표지를 그리지 않는다(지금 앱과 같음).'),
    ('병력 수', '내 군단만 정확한 수(서버 troops), 다른 세력은 병력대 글(서버 troopsBand.label) 그대로 — 지어내지 않는다.'),
]


def rules():
    return ('<ul class="ul" style="padding:4px 12px">' + ''.join(f'<li><b>{a}</b> — {b}</li>' for a, b in DIFFS) + '</ul>')


# 군단은 성 밖 들판에 선다 — 들판 자리(MAP desk 화면 좌표)를 칸 가운데로 잘라 보인다(성 그림 위에 표지를 올리지 않는다)
FIELD_A, FIELD_B, FIELD_C, FIELD_D = (480, 200), (560, 420), (520, 470), (600, 230)


def at(field, x, y):
    """desk 지도에서 field 자리가 칸 안 (x, y)에 오게 자르는 left · top."""
    return {'left': -(field[0] - x), 'top': -(field[1] - y)}


def board_corps_states():
    st = ('<div style="display:grid;grid-template-columns:repeat(2,300px);gap:12px">'
          + cell('보임 — 승인 표지 그대로 + 병력대', corps_mark(150, 100, '원소 군단', 'seen', '5천~1만'), **at(FIELD_A, 150, 100))
          + cell('첩보(마지막 목격) — α .55 · 점선 · 「?」', corps_mark(150, 100, '원소 군단', 'intel', '5천~1만', '2순 전'), **at(FIELD_B, 150, 100))
          + cell('내 군단 — 청동 2px · 정확한 병력 · 경로',
                 path_line(110, 80, 250, 160, 300, 200, '행군 경로') + corps_mark(110, 80, '하후돈 군단', 'own', '3,200명'), **at(FIELD_C, 110, 80))
          + cell('겹칠 때 — 내 군단이 위, 못 피한 띠는 첩보 · 보임 순으로 뺀다',
                 corps_mark(162, 112, '원소 군단', 'intel') + corps_mark(138, 98, '원소 군단', 'seen')
                 + corps_mark(182, 92, '하후돈 군단', 'own', '3,200명'), **at(FIELD_D, 150, 100))
          + '</div>')
    lod = ('<div style="display:flex;gap:12px">'
           + cell('군 보기(4px/칸) — 표지 + 상태만, 띠 없음',
                  corps_mark(70, 70, '하후돈 군단', 'own', zoom='commandery') + corps_mark(150, 110, '원소 군단', 'intel', zoom='commandery')
                  + corps_mark(110, 140, '원소 군단', 'seen', zoom='commandery'), 220, 180, 'jun', -300, -150)
           + cell('현 보기(16px/칸) — 병력 띠 · 첩보 나이',
                  corps_mark(70, 70, '하후돈 군단', 'own', '3,200명') + corps_mark(150, 120, '원소 군단', 'intel', '5천~1만', '2순 전'),
                  220, 180, 'desk', **at(FIELD_A, 70, 70))
           + '</div>')
    main_ = (f'<main style="flex-grow:1;min-width:0;display:flex;gap:12px;padding:12px;overflow:hidden">'
             f'<section class="panel" style="width:640px;flex-shrink:0">{sec("군단 표지 세 상태", "현 보기 16px/칸 · ADR-049 개정 D34")}<div style="padding:12px">{st}</div></section>'
             f'<div style="flex:1;min-width:0;display:flex;flex-direction:column;gap:12px">'
             f'<section class="panel">{sec("보기 수준", "")}<div style="padding:12px">{lod}</div></section>'
             f'<section class="panel" style="flex:1">{sec("옛 지도와 다른 점 · 규칙", "D34 승인")}{rules()}</section></div></main>')
    page31('V31K2CorpsStates.dc.html', 'K2 지도 — 군단 표지 세 상태(데스크톱, ADR-049 개정 D34)',
           shell_desk('지도 — 군단 표지 상태', 'war', main_))


def board_mcorps_states():
    hx, hy = MOB_PX(*CELLS['양적현'])
    ox, oy = hx - 110, hy - 160  # 내 군단은 양적 성 밖 서북 들판
    marks = (path_line(ox, oy, ox + 90, oy + 100, 390, 724, '행군 경로')
             + corps_mark(ox, oy, '하후돈 군단', 'own', '3,200명')
             + corps_mark(hx + 110, hy - 170, '원소 군단', 'seen', '5천~1만')
             + corps_mark(hx - 90, hy + 90, '원소 군단', 'intel', '5천~1만', '2순 전'))
    inset = ('<div style="position:absolute;right:8px;top:64px;width:150px;height:120px;overflow:hidden;border:1px solid #3d4740;background:#0c0f0e">'
             f'{mapimg("jun", 840, 480, "군 보기", -330, -170)}'
             f'{corps_mark(40, 40, "하후돈 군단", "own", zoom="commandery")}{corps_mark(105, 80, "원소 군단", "intel", zoom="commandery")}'
             '<span class="chip" style="position:absolute;left:4px;bottom:4px">군 보기 — 띠 없음</span></div>')
    main = (f'<main aria-label="지도" style="position:relative;width:390px;height:724px;overflow:hidden">{mapimg("mob", 390, 844, "양적 일대 지도", 0, -60)}'
            f'{marks}{inset}'
            f'<div style="position:absolute;left:8px;top:8px;padding:4px;background:rgba(27,32,29,.95);border:1px solid #3d4740">{chip("군단 표지 세 상태 — D34", "info")}</div>'
            f'<div style="position:absolute;left:8px;right:8px;bottom:8px;padding:8px 10px;background:rgba(27,32,29,.95);border:1px solid #3d4740;font-size:12px;line-height:1.5">'
            f'청동 = 내 군단 · 점선 + 「?」 = 첩보(마지막 목격, 흐림) · 띠 = 병력(내 군단만 정확한 수) · 누를 영역 44 그대로</div></main>')
    page31('V31K2MCorpsStates.dc.html', 'K2 지도 — 군단 표지 세 상태(모바일, ADR-049 개정 D34)',
           mtop31() + main + tabbar31('war'), w=390, h=844)


if __name__ == '__main__':
    board_corps_states()
    board_mcorps_states()
