# 캔버스 v3.1 · K6 서신 「도움 요청」(D68, 2026-10-03 사용자 답 「NPC 서신은 실제 도움 요청까지」) — K6 서신(P-Q02) 몫.
# 요구: 메타 reports/opensamguk/tasks/2026-10-03-k6-direct-command-front-requirements.md §6(도움 요청 양식 · 상태).
# 정해진 양식(병력 · 자원)으로 보내고, NPC 가 자기 순에 규칙으로 판단한다(수락 · 거절 · 대기). 본문 글은 명령으로 실행하지 않는다(D68).
# 수락만으로 「도움이 왔다」고 그리지 않는다 — 실제 출발 · 이전 사건이 와야 「출발함」(C0 인계 2026-10-03-c0-d60-d68-decision-handoff.md).
# 수치 · 빈도 · 받는 사람 범위 · 병력 단위 · 기한은 구체안 뒤(C3/C1 → CEO) — 「[결정 대기]」. 서버가 줄 값은 [값].
# 입력 원장 행이 아직 없어 결정 단추는 「준비 중」 점선이고 data-input-id 를 달지 않는다(지어낸 inputId 없음). 보드는 열린 뒤 모습이다.
# 부품은 v31system(K3) · boards_v31_k6 조각만 부른다. 그 파일들은 고치지 않는다. 이 파일의 보드만 굽는다(다른 보드 바이트 그대로).
#   PYTHONPATH=<v31assets 폴더> python3 boards_v31_k6_help.py   → project/V31K6Help*.dc.html
# 예시 상황은 K6 보드와 같다: 하후돈(사람, 조조 소속) · 200년 3월 중순 · 내 부 허저(NPC, 장사현)에게 쌀을 청한다.
import glob
import os

from v31system import *  # noqa: F401,F403
from v31system import P
import boards_v31_k6 as k6

BOARDS = []


def board(fn):
    BOARDS.append(fn)
    return fn


MW, MH = 390, 844


def pending(t=''):
    """결정 대기 표지 — 사용자 결정(C0 → CEO) 전이라 값 · 문구를 정하지 않은 칸. 점선 파랑(K4 직속 명령 묶음과 같은 꼴)."""
    tail = f' {t}' if t else ''
    return f'<span class="chip" style="border-style:dashed;border-color:#7aa7c7;color:#7aa7c7;white-space:nowrap">[결정 대기]{tail}</span>'


# (상태 칩, 칩 색, 보낸 쪽에 보이는 한 줄) — 요구 §6.2. 「수락」은 출발 전이라 도움이 온 것이 아니다.
STATES = [
    ('판단 대기', 'info', '허저는 자기 순에 정해진 규칙으로 판단합니다 · 다음 판단 [값]순'),
    ('대기', '', '아직 정하지 않았습니다 — [사유]'),
    ('수락', 'bronze', '수락했습니다 — 아직 출발 전이라 도움이 온 것이 아닙니다'),
    ('출발함', 'moss', '쌀 [값] 출발 · 도착 예정 [값]순 — 사건 보기'),
    ('거절', 'rust', '거절했습니다 — [사유]'),
    ('기한 지남 · 취소됨', '', '닫혔습니다'),
]


def state_row(st, tone, line, now=False):
    mark = 'border-left:3px solid #d3b064;background:rgba(211,176,100,.08);' if now else 'border-left:3px solid transparent;'
    return (f'<li style="display:flex;align-items:center;gap:8px;min-height:40px;padding:4px 10px;border-bottom:1px solid #2c342f;{mark}">'
            f'{chip(st, tone)}<span class="t2" style="font-size:12.5px;line-height:1.4">{line}</span></li>')


def help_card(st, tone, what, line, foot=''):
    """요청 탭 「보낸 것」 카드 — 요청 카드 머리(RequestCard)와 같은 꼴. 도움 요청은 응답 단추가 없고 상태 칩만."""
    f = f'<div style="display:flex;gap:6px;padding:0 12px 10px">{foot}</div>' if foot else ''
    return (f'<article class="req" aria-label="도움 요청 — 허저"><div style="display:flex;gap:10px;padding:10px 12px">{portrait("heojeo", "허저", 30, 42)}'
            f'<div style="display:flex;flex-direction:column;gap:3px;min-width:0;flex:1"><div style="display:flex;align-items:center;gap:6px">'
            f'{chip("도움 요청", "bronze")}<span class="serif" style="font-weight:700;font-size:14px">허저</span>'
            f'<span class="mono t2" style="font-size:11px;margin-left:auto;white-space:nowrap">기한 [값]순</span></div>'
            f'<span style="font-size:13px">{what}</span><span style="display:flex;gap:6px;align-items:center;flex-wrap:wrap">{chip(st, tone)}'
            f'<span class="t2" style="font-size:12px">{line}</span></span></div></div>{f}</article>')


def person_pick():
    return (f'<button type="button" class="inp" style="cursor:pointer;text-align:left">{portrait("heojeo", "허저", 22, 30)}<span class="serif" style="font-weight:700">허저</span>'
            f'<span class="t2" style="font-size:12px">내 부 · NPC · 장사현</span><span style="margin-left:auto;color:#d3b064;font-size:12px">바꾸기</span></button>')


def help_form(mobile=False):
    """도움 요청 양식 — 글 서신과 따로다(본문은 전달만). 받는 사람 · 종류(병력 · 자원) · 양 · 보낼 곳 · 기한 · 본문(선택)."""
    kind = seg([('병력', None), ('자원', None)], '자원', '도움 종류')
    res_row = (f'<div style="display:flex;gap:8px;align-items:center">{seg([("쌀", None), ("금", None), ("목재", None)], "쌀", "자원 종류")}'
               f'{inp("[값]", "양", unit="", style="width:96px")}</div>')
    # 모바일은 아래 고정 단추 위로 본문 안내(전달만)까지 보이게 덜 중요한 안내 두 줄을 뺀다 — 같은 칸 · 같은 입력(축소판 아님).
    body_h = 40 if mobile else 96
    return (field('받는 사람', person_pick(), '' if mobile else '고를 수 있는 사람은 서버가 준다')
            + f'<div style="display:flex;gap:6px;align-items:center;flex-wrap:wrap"><span class="t2" style="font-size:12px">받는 사람 범위</span>{pending("NPC 만 · 사람 장수 포함")}</div>'
            + field('종류', kind, '병력은 부곡 단위로 청한다 — 단위는 결정 대기')
            + field('자원 · 양', res_row, '' if mobile else '한 번에 청할 수 있는 양의 상한은 서버 값')
            + target_field('보낼 곳(구역)', '양적현', '내 현 · 영천군', picking=False)
            + field('기한', f'<div style="display:flex;gap:6px;align-items:center">{inp("[값]순 뒤", "기한", style="width:120px")}{pending("기한 수치")}</div>')
            + field('본문(선택)', f'<div class="inp area" style="min-height:{body_h}px">장사현 창고가 비었소. 쌀을 조금 보내 주게.</div>',
                    '본문은 전달만 됩니다 — 요청 내용은 위 양식으로만 판단합니다'))


def status_panel():
    rows = ''.join(state_row(st, tone, line, now=(i == 0)) for i, (st, tone, line) in enumerate(STATES))
    facts = k6.inset(k6.fieldrow('종류', '자원 · 쌀') + k6.fieldrow('양', '[값]') + k6.fieldrow('보낼 곳', '양적현 [구역]')
                     + k6.fieldrow('기한', '[값]순 뒤') + k6.fieldrow('본문', '전달만 — 명령으로 읽지 않는다'))
    return (f'<section class="panel" style="flex:1 1 0;min-width:0">{sec("보낸 도움 요청 — 허저", "3월 중순 21:30 · 판단 대기")}'
            f'<div style="padding:12px 16px;display:flex;flex-direction:column;gap:10px">'
            f'<div style="display:flex;align-items:center;gap:10px">{portrait("heojeo", "허저", 30, 42)}<span style="display:flex;flex-direction:column;gap:2px">'
            f'<span class="serif" style="font-weight:900;font-size:15px">허저</span><span class="t2" style="font-size:11.5px">내 부 · NPC · 장사현</span></span>{chip("NPC")}</div>'
            f'{facts}'
            f'<div style="display:flex;flex-direction:column;gap:4px"><span class="t2" style="font-size:12px">상태 — 서버가 준 단계만 그린다</span>'
            f'<ol style="list-style:none;margin:0;padding:0;border:1px solid #2c342f">{rows}</ol></div>'
            + k6.infobox('수락만으로 도움이 온 것이 아닙니다. 실제 출발 · 이전 사건이 오면 「출발함」으로 바뀌고, 도착하면 사건 기록으로 보입니다.')
            + f'<div style="display:flex;gap:6px;align-items:center;flex-wrap:wrap"><span class="t2" style="font-size:12px">NPC 판단 규칙 · 빈도</span>{pending("구체안 뒤 다시 정함")}</div>'
            + '</div></section>')


# ================================================================== 데스크톱 — 서신: 목록 · 보낸 도움 요청 · 도움 요청 쓰기
@board
def board_help():
    tabs = seg([('개인', 3), ('세력', None), ('전체', None), ('요청', 2)], '개인', '서신 묶음')
    rows = (opt('쌀을 부탁드립니다', '허저 · 3월 중순 21:30', chip('도움 요청', 'bronze') + chip('판단 대기', 'info'), sel=True, h=56)
            + ''.join(k6.mail_row(*m[:5], False) for m in k6.MAILS[:3]))
    left = (f'<section class="panel" style="width:360px;flex-shrink:0">{sec("서신", "외교 서신은 조정 › 외교")}<div style="padding:8px">{tabs}</div>'
            f'<div role="listbox" aria-label="서신 목록" style="display:flex;flex-direction:column;border-top:1px solid #2c342f">{rows}</div>'
            f'<div style="padding:10px 12px"><span class="t2" style="font-size:12px">도움 요청은 글 서신과 다른 줄이다 — 종류 칩 「도움 요청」 + 상태 칩</span></div></section>')
    kinds = seg([('글 서신', None), ('도움 요청', None)], '도움 요청', '서신 종류')
    write = (f'<section class="panel" style="width:400px;flex-shrink:0">{sec("서신 쓰기", "도움 요청")}<div style="padding:12px;display:flex;flex-direction:column;gap:10px">'
             f'{kinds}{help_form()}'
             f'<div style="display:flex;flex-direction:column;gap:6px">{input_btn("도움 요청 보내기", "NOT_DELIVERED", style="width:100%")}'
             f'{k6.boardnote("열린 뒤 모습 — 입력 원장 행 전에는 「준비 중」 점선, data-input-id 없음")}</div></div></section>')
    body = (pagehead('서신', None, None, btn('새 서신', 'primary', 'mail'))
            + f'<div style="flex-grow:1;display:flex;gap:12px;padding:12px;min-height:0">{left}{status_panel()}{write}</div>')
    page31('V31K6HelpRequest.dc.html', 'K6 서신 — 도움 요청 쓰기 · 보낸 요청 상태(데스크톱, D68)',
           shell_desk('서신', 'plaza', f'<main style="flex-grow:1;min-width:0;display:flex;flex-direction:column">{body}</main>'))


# ================================================================== 모바일 — 도움 요청 쓰기(서신 › 새 서신 › 도움 요청)
@board
def board_mhelp():
    kinds = f'<div style="padding:8px 12px;border-bottom:1px solid #2c342f">{seg([("글 서신", None), ("도움 요청", None)], "도움 요청", "서신 종류")}</div>'
    form = f'<div style="padding:10px 12px;display:flex;flex-direction:column;gap:8px">{help_form(mobile=True)}</div>'
    foot = (f'<div style="position:absolute;left:0;right:0;bottom:0;padding:10px 12px;border-top:1px solid #3d4740;background:#1b201d">'
            f'{input_btn("도움 요청 보내기", "NOT_DELIVERED", style="width:100%")}</div>')
    inner = f'<div style="height:{k6.MAIN_M - 66}px;overflow:hidden">{kinds}{form}</div>{foot}'
    page31('V31K6MHelpRequest.dc.html', 'K6 서신 — 도움 요청 쓰기(모바일, D68)', shell_mob(k6.mmain(inner), 'war', '도움 요청', '서신'), w=MW, h=MH)


# ================================================================== 모바일 — 요청 탭 › 보낸 것: 도움 요청 상태 카드
@board
def board_mhelpstatus():
    tabs = (f'<div style="padding:8px 12px;display:flex;flex-direction:column;gap:6px;border-bottom:1px solid #2c342f">'
            f'{seg([("개인", 3), ("세력", None), ("전체", None), ("요청", 2)], "요청", "서신 묶음", style="flex-wrap:nowrap")}'
            f'{seg([("받은 것", None), ("보낸 것", None)], "보낸 것", "요청 — 받은 것 · 보낸 것")}</div>')
    cards = (help_card('판단 대기', 'info', '쌀 [값] · 양적현으로', '다음 판단 [값]순')
             + help_card('수락', 'bronze', '쌀 [값] · 양적현으로', '아직 출발 전 — 도움이 온 것이 아닙니다')
             + help_card('출발함', 'moss', '쌀 [값] · 양적현으로', '도착 예정 [값]순', foot=btn('사건 보기', 'sm', style='flex:1'))
             + help_card('거절', 'rust', '부곡 [결정 대기] · 장사현으로', '[사유]'))
    inner = tabs + f'<div style="display:flex;flex-direction:column">{cards}</div>'
    page31('V31K6MHelpStatus.dc.html', 'K6 서신 — 보낸 도움 요청 상태(모바일, D68)', shell_mob(k6.mmain(inner), 'war', '서신', '작전실'), w=MW, h=MH)


if __name__ == '__main__':
    for f in glob.glob(os.path.join(P, 'V31K6Help*.dc.html')) + glob.glob(os.path.join(P, 'V31K6MHelp*.dc.html')):
        os.remove(f)
    for b in BOARDS:
        b()
    print(f'ok boards_v31_k6_help — {len(BOARDS)} boards')
