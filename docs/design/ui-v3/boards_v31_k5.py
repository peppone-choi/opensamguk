# 캔버스 v3.1 · K5 보드 — 게이트웨이 · 입장 · 기록 · 광장 · 관리(P-G01~G11 · P-E01~E04 · P-H01~H03 · P-Q01 · P-A03).
#   PYTHONPATH=. python3 boards_v31_k5.py   → project/V31K5*.dc.html
# 설계서(항목 번호 · 판정 · API 행의 정본): 메타 reports/opensamguk/tasks/2026-09-30-k5-design-spec.md
# v3.1 시스템(v31system.py, K3) · 그림 id(v31assets.py, K0) · names.py 는 import 만 한다.
# 코에이(RTK14) 능력치는 커밋하지 않는다 — 저장소 사본은 「—」. 설계에서 정하지 않은 수치는 [미정] · [값].
import v31system as _S
from v31system import *  # noqa: F401,F403
from v31system import NATION, PT, MAP, DESK_PX, CELLS
import v31assets as _A  # 워드마크 그림 id(_A.LOGO). v3common 의 LOGO(머리줄 자리 표시)와 이름이 같아 모듈로 부른다.

W, H = 1440, 1000
MW, MH = 390, 844

# 게이트웨이 · 입장 셸 · 걸음 입력 · 시간 막대 · 사람 여러 명 고르기는 v31system 3.1.1(K3, K5 요청)의 gw_topbar · gw_mtop · entry_topbar · entry_mtop · step_bar · step_foot · time_bar · people_picker_multi 를 쓴다.
assert getattr(_S, "V31_VERSION", "3.1.0") >= "3.1.4", "v31system 3.1.4 이상이 필요하다(K5 부품 5개 · time_bar now_text · gw_topbar logo_on)"
# 로그인 · 가입(데스크톱 · 모바일)은 머리줄 로고를 끄고(logo_on=False) 히어로 워드마크만 둔다. 로비 · 계정 · 커뮤니티 · 정책 · 게임 안은 머리줄 로고 그대로(K0 결정).

# ------------------------------------------------------------------ 예시 자료(현행 화면에 나오는 종류의 값 · 설계 미정은 [미정])
NATS = [('조조', NATION['조조'], 9, 41, True), ('원소', NATION['원소'], 14, 52, False), ('유표', NATION['유표'], 8, 27, False),
        ('손책', '#c9a15a', 6, 22, False), ('유비', '#a5744a', 1, 9, False)]
# 천하 정세 — 알림체(게임 로그 스펙 §3 · EventKind WORLD). 문장은 화면이 kind + refs 로 만든다. 숫자는 보태지 않는다.
WORLD = [('200년 3월 중순', '허현의 소유 세력이 원소에서 조조로 바뀌었습니다.'),
         ('200년 3월 상순', '200년 3월 월단평 결과가 발표됐습니다.'),
         ('200년 2월 하순', '백마 보루를 원소가 차지했습니다.'),
         ('200년 2월 중순', '환현의 소유 세력이 유훈에서 손책으로 바뀌었습니다.'),
         ('200년 2월 상순', '서버가 밀린 순을 모두 따라잡았습니다.')]
NOTICES = [('09.30', True, 'pep 1기 공개 알파 안내'), ('09.29', False, '점검 완료 — 턴 처리 다시 시작'), ('09.26', False, '초상 올리기 방식이 바뀌었습니다')]
DASH = '—'  # 코에이 능력치 자리(저장소 사본)


def wordmark(w):
    """오픈삼국 워드마크(금색 붓글씨, 원본 1200×448 비율). 어두운 판 위에만 놓는다."""
    src = getattr(_A, 'LOGO', '')
    h = round(w * 448 / 1200)
    return pic(src, w, h, '오픈삼국', 'object-fit:contain')


def flag(c, w=10, h=14):
    return f'<i aria-hidden="true" style="width:{w}px;height:{h}px;display:inline-block;background:{c};border:1px solid rgba(0,0,0,.45);flex-shrink:0"></i>'


def crown_badge(t='황제 · 허현'):
    """황제 표식(K0 확정: 청동 + 관). /api/imperial/presence READY 일 때만."""
    return f'<span class="chip bronze" style="gap:6px">{icon("crown", 14, "#d3b064")}{t}</span>'


def floatp(title, sub, body, style):
    return (f'<section class="panel" aria-label="{title}" style="position:absolute;background:rgba(27,32,29,.95);box-shadow:0 10px 28px rgba(0,0,0,.5);{style}">'
            f'{sec(title, sub)}{body}</section>')


def over(inner, z=30):
    """지도 위에 뜨는 셸 조각(투명 머리줄)."""
    return f'<div style="position:absolute;left:0;right:0;top:0;z-index:{z}">{inner}</div>'


def nat_rows(mine=False, h=44, n=5):
    return ''.join(
        f'<div style="height:{h}px;display:grid;grid-template-columns:14px minmax(0,1fr) 56px 64px;gap:8px;align-items:center;padding:0 12px;border-bottom:1px solid #2c342f;font-size:13px">'
        f'{flag(c)}<span style="display:flex;gap:6px;align-items:center;white-space:nowrap">{nm}{chip("내 소속", "bronze") if mine and m else ""}</span>'
        f'<span class="mono" style="text-align:right">현 {a}</span><span class="mono t2" style="text-align:right">장수 {g}</span></div>' for nm, c, a, g, m in NATS[:n])


def world_rows(n=5, h=44, badge=False):
    return ''.join(
        f'<div style="min-height:{h}px;display:flex;gap:10px;align-items:center;padding:4px 12px;border-bottom:1px solid #2c342f;font-size:12.5px">'
        f'{cat("천하 정세") if badge else ""}<span class="mono muted" style="white-space:nowrap;font-size:11.5px">{d}</span><span class="t2" style="min-width:0">{t}</span></div>'
        for d, t in WORLD[:n])


def notice_rows(open_first=True, n=3):
    out = ''
    for i, (d, pin, t) in enumerate(NOTICES[:n]):
        ex = 'true' if (open_first and i == 0) else 'false'
        out += (f'<button type="button" aria-expanded="{ex}" style="display:flex;align-items:center;gap:8px;width:100%;min-height:44px;padding:0 12px;background:none;border:0;'
                f'border-bottom:1px solid #2c342f;color:#ece6d8;font:inherit;font-size:12.5px;text-align:left;cursor:pointer"><span class="mono muted">{d}</span>'
                f'{chip("고정", "bronze") if pin else ""}<span style="white-space:nowrap;overflow:hidden;text-overflow:ellipsis;{"font-weight:700" if pin else ""}">{t}</span></button>')
        if ex == 'true':
            out += ('<p class="t2" style="margin:0;padding:8px 12px;font-size:12px;line-height:1.55;border-bottom:1px solid #2c342f;background:#141816">'
                    '한 서버에 장수 한 명으로 시작합니다. 한 순은 10분입니다. [공개 알파 안내 본문 — 운영자가 쓴다]</p>')
    return out + '<a href="#" style="display:flex;align-items:center;min-height:44px;padding:0 12px;font-size:12px">공지 모두 보기</a>'


def alert_box(t, tone='rust'):
    col = {'rust': ('#c96b5d', '#e08a7c', 'rgba(201,107,93,.10)'), 'info': ('#4b6d87', '#7aa7c7', 'rgba(122,167,199,.10)'),
           'moss': ('#697e58', '#8fa77a', 'rgba(105,126,88,.14)')}[tone]
    ic = 'alert' if tone == 'rust' else ('check' if tone == 'moss' else 'clock')
    return (f'<div role="{"alert" if tone == "rust" else "status"}" style="display:flex;gap:8px;align-items:center;padding:8px 10px;border:1px solid {col[0]};color:{col[1]};'
            f'background:{col[2]};font-size:12.5px;line-height:1.45">{icon(ic, 16, col[1])}<span>{t}</span></div>')


def map_ctrl(style, lod=False):
    """로그인 · 가입 · 입구 지도 조작(내 장수가 없어 「내 위치로」 없음)."""
    b = 'background:rgba(20,24,22,.92)'
    lv = seg(['주', '군', '현'], '군', '보기 수준', vertical=True) if lod else ''
    return (f'<div style="position:absolute;{style};display:flex;flex-direction:column;gap:8px">{lv}<div style="display:flex;flex-direction:column;gap:2px">'
            f'<button type="button" class="ibtn" aria-label="확대" style="{b};font-size:20px">+</button>'
            f'<button type="button" class="ibtn" aria-label="축소" style="{b};font-size:20px">−</button></div>'
            f'{ibtn("layers", "지도 레이어 — 경계 · 이름", style=b)}</div>')


def policy_links(style=''):
    return (f'<nav aria-label="정책" style="display:flex;align-items:center;justify-content:center;gap:4px;font-size:12px;{style}">'
            f'<a href="#" style="color:#b9b2a3;min-height:44px;display:inline-flex;align-items:center;padding:0 10px">개인정보처리방침</a>'
            f'<a href="#" style="color:#b9b2a3;min-height:44px;display:inline-flex;align-items:center;padding:0 10px">이용약관</a></nav>')


def draft_chip(t='문구 초안 — 공개 알파 문구(U5)와 함께 승인'):
    return chip(t, 'info')


def gw_page(on, main, admin=True):
    return gw_topbar('in', on, admin) + f'<div style="flex-grow:1;display:flex;min-height:0;position:relative">{main}</div>'


# ================================================================== P-G02 로그인(지도가 주인공)
LOGIN_COPY = '장수 한 명으로 시작해 순마다 명령을 세우고, 전투가 열리면 직접 지휘한다.'
PW = '<div style="display:flex;gap:6px">' + inp('•' * 8) + '<button type="button" class="btn" aria-pressed="false">표시</button></div>'


def login_form(err=''):
    return (f'<div style="padding:12px 16px 14px;display:flex;flex-direction:column;gap:10px">'
            f'{field("계정명", inp("hahoudon"))}{field("비밀번호", PW)}{alert_box(err) if err else ""}'
            f'{btn("로그인", "primary", style="width:100%")}'
            f'<a href="#" style="font-size:12.5px;min-height:44px;display:inline-flex;align-items:center">계정이 없으신가요? 회원가입</a></div>')


def server_chips(style=''):
    return (f'<div role="group" aria-label="서버 고르기" style="display:flex;gap:6px;{style}">'
            '<button type="button" class="btn sm" aria-pressed="true" style="background:#d3b064;color:#161410;border-color:#9c7f3f;font-weight:700;flex-shrink:0">pep <span class="chip" style="margin-left:4px;height:18px;background:#161410;color:#d3b064">1기</span></button>'
            '<button type="button" class="btn sm" aria-pressed="false" style="background:rgba(20,24,22,.92);flex-shrink:0">통일 서버 <span class="chip" style="margin-left:4px;height:18px">3기</span></button>'
            '<button type="button" class="btn sm" aria-pressed="false" style="background:rgba(20,24,22,.92);flex-shrink:0">s2 <span class="chip" style="margin-left:4px;height:18px">7기</span></button></div>')


def login_card(err=''):
    return (f'<section class="panel" aria-label="로그인" style="position:absolute;right:32px;top:80px;width:380px;background:rgba(27,32,29,.97);border-color:#9c7f3f;'
            f'box-shadow:0 10px 28px rgba(0,0,0,.5)"><div style="padding:14px 16px 0"><h1 class="serif" style="margin:0;font-size:22px;font-weight:900">로그인</h1></div>'
            f'{login_form(err)}</section>')


def board_login():
    intro = (f'<div style="position:absolute;left:32px;top:80px;width:520px;padding:22px 24px;background:rgba(12,15,14,.78);display:flex;flex-direction:column;gap:12px">'
             f'{wordmark(420)}'
             f'<h2 class="serif" style="margin:0;font-size:30px;font-weight:900;line-height:1.3">한 명의 장수에서 천하까지.</h2>'
             f'<p class="t2" style="margin:0;font-size:14px;line-height:1.6">{LOGIN_COPY}</p><span style="display:flex">{draft_chip()}</span></div>')
    nat = floatp('세력 현황', 'pep 1기 · 세력 5', nat_rows(h=40), 'left:32px;top:668px;width:320px')
    wl = floatp('천하 정세', '공개 사건 · 최근', world_rows(4, 44)
                + '<a href="#" style="display:flex;align-items:center;min-height:44px;padding:0 12px;font-size:12px">더 보기 — 로그인하면 기록에서</a>', 'left:364px;top:668px;width:460px')
    nt = floatp('공지', '3건', notice_rows(), 'right:32px;top:560px;width:380px')
    chips = f'<div style="position:absolute;left:32px;top:612px">{server_chips()}</div>'
    cap = '<span class="chip" style="position:absolute;left:364px;top:618px;height:32px;background:rgba(20,24,22,.92);font-size:12px">pep 1기 · 200년 3월 중순 · 군 보기</span>'
    foot = f'<div style="position:absolute;left:0;right:0;bottom:0;background:rgba(12,15,14,.8)">{policy_links("height:44px")}</div>'
    body = (f'<main aria-label="로그인 — 서버 현황 지도" style="position:relative;width:{W}px;height:{H}px;overflow:hidden">'
            f'{mapimg("hero", W, H, "중원 일대 지도 — pep 1기의 지금 판도(군 보기)")}{over(gw_topbar("login", transparent=True, logo_on=False))}'
            f'{intro}{login_card()}{map_ctrl("left:32px;top:420px")}{chips}{cap}{nat}{wl}{nt}{foot}</main>')
    page31('V31K5Login.dc.html', 'K5 P-G02 로그인 — 지도가 주인공(데스크톱)', body)


def board_login_empty():
    empty = (f'<section class="panel" aria-label="서버 현황" style="position:absolute;left:32px;top:612px;width:560px;height:320px;background:rgba(27,32,29,.96)">'
             f'{sec("서버 현황", "서버 0")}{state_empty("지금 열린 서버가 없습니다", "새 서버가 열리면 이 자리에 지도 · 세력 · 천하 정세가 보입니다. 공지를 확인하세요.")}</section>')
    nt = floatp('공지', '3건', notice_rows(open_first=False), 'right:32px;top:560px;width:380px')
    note = (f'<div style="position:absolute;left:32px;top:80px;width:520px;padding:18px 24px;background:rgba(12,15,14,.78);display:flex;flex-direction:column;gap:10px">{wordmark(360)}'
            f'<span style="display:flex">{chip("서버가 없으면 지도는 세력 없는 기본 지형(설계 층 export)", "info")}</span></div>')
    wait = (f'<section class="panel" aria-label="서버 상태 예시" style="position:absolute;left:32px;top:420px;width:520px;background:rgba(27,32,29,.96)">{sec("서버가 있을 때 지도 위 상태 칩", "K3-03 · K10-01")}'
            f'<div style="padding:10px 12px;display:flex;gap:6px;flex-wrap:wrap">{chip("점검 중", "rust")}{chip("준비 중 — 10월 3일 20:00 열림", "info")}{chip("턴 멈춤", "rust")}'
            f'{chip("따라잡는 중 · 2배속", "bronze")}</div></section>')
    body = (f'<main style="position:relative;width:{W}px;height:{H}px;overflow:hidden">{mapimg("hero", W, H, "중원 일대 기본 지형 — 세력 없음")}'
            f'{over(gw_topbar("login", transparent=True, logo_on=False))}{note}{wait}{login_card("계정명이나 비밀번호가 맞지 않습니다.")}{empty}{nt}</main>')
    page31('V31K5LoginEmpty.dc.html', 'K5 P-G02 로그인 — 서버 0 · 로그인 거절 · 서버 상태 칩', body)


def board_mlogin():
    top = (f'<div style="position:relative;height:480px;flex-shrink:0;overflow:hidden">{mapimg("hero_m", MW, 480, "낙양 일대 지도 — pep 1기 판도")}'
           f'{over(gw_mtop(transparent=True, logo_on=False))}<div style="position:absolute;left:12px;top:292px;padding:6px 10px;background:rgba(12,15,14,.72)">{wordmark(220)}</div>'
           f'<span class="chip" style="position:absolute;left:12px;top:392px;height:32px;background:rgba(20,24,22,.94);font-size:12px">pep 1기 · 3월 중순 · 세력 5</span>'
           f'<div style="position:absolute;right:8px;top:64px;display:flex;flex-direction:column;gap:2px">'
           f'<button type="button" class="ibtn" aria-label="확대" style="background:rgba(20,24,22,.92);font-size:20px">+</button>'
           f'<button type="button" class="ibtn" aria-label="축소" style="background:rgba(20,24,22,.92);font-size:20px">−</button></div></div>')
    form = (f'<section aria-label="로그인" style="position:absolute;left:0;right:0;top:440px;bottom:0;background:#1b201d;border-top:1px solid #9c7f3f;'
            f'box-shadow:0 -12px 32px rgba(0,0,0,.55);display:flex;flex-direction:column">'
            f'<div style="padding:12px 16px 0"><h1 class="serif" style="margin:0;font-size:20px;font-weight:900">로그인</h1></div>{login_form()}'
            f'<a href="#" style="margin-top:auto;display:flex;align-items:center;justify-content:center;gap:6px;min-height:44px;font-size:12.5px;border-top:1px solid #2c342f">'
            f'{icon("arrow", 16)}서버 현황 — 세력 · 천하 정세 · 공지</a></section>')
    page31('V31K5MLogin.dc.html', 'K5 P-G02 로그인 — 모바일 첫 화면', f'<main style="position:relative;width:{MW}px;height:{MH}px;overflow:hidden">{top}{form}</main>', w=MW, h=MH)


def board_mlogin_scroll():
    body = (f'{gw_mtop(logo_on=False)}<div style="flex-grow:1;overflow:hidden;display:flex;flex-direction:column;gap:12px;padding:12px">'
            f'<div style="overflow:hidden">{server_chips("flex-wrap:nowrap")}</div>'
            f'<section class="panel">{sec("세력 현황", "pep 1기 · 세력 5")}{nat_rows(h=44)}</section>'
            f'<section class="panel">{sec("천하 정세", "공개 사건")}{world_rows(3, 56)}</section>'
            f'<section class="panel">{sec("공지", "3건")}{notice_rows(open_first=False, n=2)}</section></div>'
            f'<div style="border-top:1px solid #2c342f;flex-shrink:0">{policy_links()}</div>')
    page31('V31K5MLoginScroll.dc.html', 'K5 P-G02 로그인 — 모바일 스크롤 아래(서버 현황)', body, w=MW, h=MH)


# ================================================================== P-G03 가입
def join_fields(err=True):
    email = f'<span style="display:inline-flex;gap:6px;align-items:center">이메일{chip("선택")}</span>'
    return (f'{field("계정명", inp("hahoudon"), "3~50자")}'
            f'{field("비밀번호", PW, "6자 이상")}'
            f'{field("비밀번호 확인", inp(chr(8226) * 7, cls="bad" if err else ""), err="비밀번호가 서로 다릅니다." if err else "")}'
            f'{field("별명", inp("원양"), "2~20자, 다른 사람과 겹칠 수 없습니다.")}'
            f'{field(email, inp("", "이메일 주소"))}')


def join_intro():
    return (f'<p class="t2" style="margin:0;font-size:14px;line-height:1.6">계정은 한 번 만들면 계속 씁니다. 서버가 새로 시작하면 장수만 다시 만듭니다.</p>'
            f'<p class="rs" style="margin:0;font-size:12.5px;line-height:1.55">한 사람이 계정 여러 개를 쓰거나 남의 턴을 대신 넣으면 이용이 막힐 수 있습니다.</p>'
            f'{alert_box("가입하면 바로 로그인되어 로비로 갑니다. 장수는 서버마다 따로 만듭니다.", "info")}<span style="display:flex">{draft_chip()}</span>')


def board_join():
    intro = (f'<div style="position:absolute;left:32px;top:80px;width:520px;padding:22px 24px;background:rgba(12,15,14,.8);display:flex;flex-direction:column;gap:12px">'
             f'{wordmark(360)}{join_intro()}</div>')
    card = (f'<section class="panel" aria-label="회원 가입" style="position:absolute;right:32px;top:80px;width:420px;background:rgba(27,32,29,.97);border-color:#9c7f3f;box-shadow:0 10px 28px rgba(0,0,0,.5)">'
            f'<div style="padding:14px 16px 0"><h1 class="serif" style="margin:0;font-size:22px;font-weight:900">회원 가입</h1></div>'
            f'<div style="padding:12px 16px 14px;display:flex;flex-direction:column;gap:10px">{join_fields()}{btn("회원가입", "primary", style="width:100%")}'
            f'<a href="#" style="font-size:12.5px;min-height:44px;display:inline-flex;align-items:center">이미 계정이 있으신가요? 로그인</a></div></section>')
    foot = f'<div style="position:absolute;left:0;right:0;bottom:0;background:rgba(12,15,14,.8)">{policy_links("height:44px")}</div>'
    body = (f'<main style="position:relative;width:{W}px;height:{H}px;overflow:hidden">{mapimg("hero", W, H, "중원 일대 지도 — 배경")}'
            f'{over(gw_topbar("join", transparent=True, logo_on=False))}{intro}{card}{foot}</main>')
    page31('V31K5Join.dc.html', 'K5 P-G03 가입 — 비밀번호 확인 오류(데스크톱)', body)


# ================================================================== P-G04 로비
def lobby_card(name, gen, chips_html, lines, me, action, h=208, thumb_w=311, thumb_h=190, crown=False, toggle='현황 펼치기'):
    ln = ''.join(f'<span class="{c}" style="font-size:12.5px;line-height:1.5">{t}</span>' for t, c in lines)
    return (f'<article class="panel" aria-label="서버 {name}" style="flex-shrink:0;height:{h}px;flex-direction:row;gap:14px;padding:8px">'
            f'<div style="position:relative;width:{thumb_w}px;height:{thumb_h}px;flex-shrink:0;overflow:hidden;border:1px solid #3d4740">{mapimg("thumb", thumb_w, thumb_h, f"{name} 판도 — 작은 지도")}</div>'
            f'<div style="flex:1;min-width:0;display:flex;flex-direction:column;gap:4px;padding-top:2px">'
            f'<div style="display:flex;gap:6px;align-items:center;flex-wrap:wrap"><h3 class="serif" style="margin:0;font-size:19px;font-weight:900">{name}</h3>{chip(gen, "bronze")}{chips_html}</div>'
            f'{ln}{crown_badge() if crown else ""}</div>'
            f'<div style="width:236px;flex-shrink:0;display:flex;flex-direction:column;gap:8px;border-left:1px solid #2c342f;padding-left:12px">'
            f'<span class="muted" style="font-size:11px">내 장수</span>{me}<div style="margin-top:auto;display:flex;flex-direction:column;gap:6px">{action}'
            f'{btn(toggle, "sm", "up", style="background:transparent;transform:none")}</div></div></article>')


def me_block(has=True):
    if has:
        return (f'<div style="display:flex;gap:10px;align-items:center">{portrait("hahoudon", "하후돈", 52, 74)}<div style="display:flex;flex-direction:column;gap:4px">'
                f'<span class="serif" style="font-size:16px;font-weight:900">하후돈</span><span style="display:flex;gap:4px;align-items:center">{flag(NATION["조조"])}<span class="t2" style="font-size:12px">조조 소속</span></span></div></div>')
    return '<span class="muted" style="font-size:12.5px">내 장수 없음</span>'


def compact_row(name, gen, chips_html, text, action):
    return (f'<div style="height:52px;flex-shrink:0;display:flex;align-items:center;gap:10px;padding:0 4px 0 12px;background:#1b201d;border:1px solid #2c342f">'
            f'<span class="serif" style="font-size:16px;font-weight:900">{name}</span>{chip(gen, "bronze")}{chips_html}'
            f'<span class="t2" style="font-size:12.5px;min-width:0;white-space:nowrap;overflow:hidden;text-overflow:ellipsis">{text}</span><span style="margin-left:auto;display:flex;gap:6px">{action}</span></div>')


LOBBY_FILTER = [('전체', 5), ('참가 중', 1), ('참가 가능', 1), ('끝난 서버', 1), ('닫힘', 1)]


def lobby_notes():
    return (f'<section class="panel">{sec("알아 둘 것")}<div style="padding:10px 12px;display:flex;flex-direction:column;gap:8px;font-size:12px;line-height:1.55">'
            f'<span class="rs">한 사람이 계정 여러 개를 쓰거나 남의 턴을 대신 넣으면 이용이 막힐 수 있습니다.</span>'
            f'<span class="t2">계정은 한 번 만들면 계속 씁니다. 서버가 새로 시작하면 장수만 다시 만듭니다.</span>'
            f'<span class="muted">현황을 받지 못한 서버는 「응답 없음」으로 보입니다.</span><span style="display:flex">{draft_chip()}</span></div></section>')


def board_lobby():
    c1 = lobby_card('pep', '1기', chip('참가 중', 'moss'),
                    [('200년 3월 중순 · 군웅할거', 't2'), ('세력 5 · 사람 24 / 30 · NPC 412', 't2'), ('한 순 10분', 'muted'),
                     ('최근: 허현의 소유 세력이 원소에서 조조로 바뀌었습니다.', 'muted')], me_block(), btn('입장', 'primary', style='width:100%', href='#'))
    c2 = lobby_card('통일 서버', '3기', chip('모집 중', 'moss') + chip('따라잡는 중 · 2배속', 'bronze'),
                    [('194년 7월 상순 · 반동탁연합', 't2'), ('세력 11 · 사람 12 / 30 · NPC 380', 't2'), ('한 순 10분', 'muted')],
                    me_block(False), btn('장수 만들기', 'primary', style='width:100%', href='#'))
    c3 = lobby_card('s2', '7기', chip('마감', 'rust'),
                    [('231년 1월 하순 · 삼국정립', 't2'), ('세력 3 · 사람 30 / 30 · NPC 95', 't2'), ('한 순 5분', 'muted')],
                    me_block(False), btn_off('장수 만들기', '사람 장수 30 / 30', style='flex-direction:column;align-items:stretch'))
    r1 = compact_row('s1', '12기', chip('시즌 끝', '') + chip('통일', 'bronze'), '조조가 천하를 통일했습니다 — 읽기 전용', btn('결산 보기', 'sm', 'records', href='#'))
    r2 = compact_row('old', '2기', chip('응답 없음', 'rust'), '현황을 받지 못했습니다', btn('다시 시도', 'sm', 'refresh'))
    left = (f'<div style="flex:1;min-width:0;display:flex;flex-direction:column;gap:8px;padding:12px">'
            f'<div style="height:40px;display:flex;align-items:center;gap:14px"><h1 class="serif" style="margin:0;font-size:22px;font-weight:900">게임 로비</h1>'
            f'<span class="t2" style="font-size:12.5px">내 장수가 있는 서버는 바로 입장하고, 없는 서버는 장수를 만들어 시작합니다.</span></div>'
            f'{seg(LOBBY_FILTER, "전체", "서버 거르기")}<div role="list" aria-label="서버" style="display:flex;flex-direction:column;gap:8px">{c1}{c2}{c3}{r1}{r2}</div></div>')
    right = (f'<aside style="width:344px;flex-shrink:0;display:flex;flex-direction:column;gap:12px;padding:12px 12px 12px 0">'
             f'<section class="panel">{sec("공지", "3건")}{notice_rows()}</section>{lobby_notes()}</aside>')
    page31('V31K5Lobby.dc.html', 'K5 P-G04 로비 — 서버 카드 · 내 장수 · 입장(데스크톱)', gw_page('로비', left + right))


# ------------------------------------------------------------------ 로비 펼친 지도 칸(사용자 D87, 2026-10-03 — 실측 주 이름표 7 → 14)
# 사용자 D69(10-03 19:49): 「칸을 더 높이기」 — 채움(D54)으로 1032×358 띠에 14주 중 7주가 잘렸다(K10 재캡처 4).
# 지도 그림 비율은 1032 : 900 ≈ 1.15 : 1(K10 extent: 폭 1032 에 지도 높이 900). 보드 그림은 전체 개관 MAP['prov'](1024×892, 1.148:1).
def lobby_status_panels(stack=False):
    rows = ''.join(f'<li style="min-height:32px;display:flex;align-items:center;gap:8px;padding:0 12px;border-top:1px solid #2c342f">{flag(col)}'
                   f'<span style="flex:1;display:flex;gap:6px;align-items:center;font-size:13px">{n}{chip("내 소속", "bronze") if mine else ""}</span>'
                   f'<span class="mono t2" style="font-size:12.5px">현 {c}</span></li>' for n, col, _, c, mine in NATS)
    nat = f'<section class="panel" aria-label="세력 현황">{sec("세력 현황", "pep 1기 · 세력 5")}<ul style="margin:0;padding:0;list-style:none">{rows}</ul></section>'
    ev = ''.join(f'<li style="padding:6px 12px;border-top:1px solid #2c342f;display:flex;flex-direction:column;gap:2px"><span class="mono muted" style="font-size:11px">{d}</span>'
                 f'<span style="font-size:12.5px;line-height:1.5">{t}</span></li>' for d, t in WORLD[:4])
    wev = f'<section class="panel" aria-label="천하 정세">{sec("천하 정세", "공개 사건 · 최근")}<ul style="margin:0;padding:0;list-style:none">{ev}</ul></section>'
    if stack:
        return f'<div style="display:flex;flex-direction:column;gap:12px;min-width:0">{nat}{wev}</div>'
    return f'<div style="display:grid;grid-template-columns:minmax(0,1fr) minmax(0,1fr);gap:12px">{nat}{wev}</div>'


def lobby_open_map(w, h):
    """펼친 카드 지도 칸 — 전체 개관을 칸에 맞춘다. 오른쪽 아래 + · − · 「이름」(지금 구현 자리)."""
    ctl = (f'<div style="position:absolute;right:8px;bottom:8px;display:flex;flex-direction:column;gap:4px">'
           f'<button type="button" class="btn sm" aria-label="확대" style="width:44px;padding:0">+</button>'
           f'<button type="button" class="btn sm" aria-label="축소" style="width:44px;padding:0">−</button>'
           f'<button type="button" class="btn sm" aria-pressed="true" style="width:44px;padding:0">이름</button></div>')
    return (f'<div style="position:relative;width:{w}px;height:{h}px;flex-shrink:0;overflow:hidden;border:1px solid #3d4740;background:#0c0f0e">'
            f'{mapimg("prov", w, round(w * 892 / 1024), "pep 판도 — 천하 전체")}{ctl}</div>')


def lobby_open_card():
    """「현황 펼치기」로 연 서버 카드 — 지도 칸은 지도 비율 1032×899(천하가 한 칸에, 사용자 D87), 아래 세력 현황 · 천하 정세 두 칸."""
    top = lobby_card('pep', '1기', chip('참가 중', 'moss'),
                     [('200년 3월 중순 · 군웅할거', 't2'), ('세력 5 · 사람 24 / 30 · NPC 412', 't2'), ('한 순 10분', 'muted'),
                      ('최근: 허현의 소유 세력이 원소에서 조조로 바뀌었습니다.', 'muted')], me_block(), btn('입장', 'primary', style='width:100%', href='#'), h=212, toggle='현황 접기')  # 212: 접기 단추가 아래 현황 칸에 2px 덮이지 않게
    status = f'<div style="display:flex;flex-direction:column;gap:12px;padding:12px">{lobby_open_map(1032, 899)}{lobby_status_panels()}</div>'
    return (f'<div role="listitem" style="display:flex;flex-direction:column;border:1px solid #9c7f3f;background:#1b201d" data-card="open">{top}'
            f'<div style="border-top:1px solid #2c342f">{status}</div></div>')


def board_lobby_open():
    c2 = lobby_card('통일 서버', '3기', chip('모집 중', 'moss') + chip('따라잡는 중 · 2배속', 'bronze'),
                    [('194년 7월 상순 · 반동탁연합', 't2'), ('세력 11 · 사람 12 / 30 · NPC 380', 't2'), ('한 순 10분', 'muted')],
                    me_block(False), btn('장수 만들기', 'primary', style='width:100%', href='#'))
    left = (f'<div style="flex:1;min-width:0;display:flex;flex-direction:column;gap:8px;padding:12px">'
            f'<div style="height:40px;display:flex;align-items:center;gap:14px"><h1 class="serif" style="margin:0;font-size:22px;font-weight:900">게임 로비</h1>'
            f'<span class="t2" style="font-size:12.5px">내 장수가 있는 서버는 바로 입장하고, 없는 서버는 장수를 만들어 시작합니다.</span></div>'
            f'{seg(LOBBY_FILTER, "전체", "서버 거르기")}<div role="list" aria-label="서버" style="display:flex;flex-direction:column;gap:8px">{lobby_open_card()}{c2}</div></div>')
    right = (f'<aside style="width:344px;flex-shrink:0;display:flex;flex-direction:column;gap:12px;padding:12px 12px 12px 0">'
             f'<section class="panel">{sec("공지", "3건")}{notice_rows()}</section>{lobby_notes()}</aside>')
    page31('V31K5LobbyOpen.dc.html', 'K5 P-G04 로비 — 현황 펼침(지도 칸 1032×899 · 세력 현황 · 천하 정세, 데스크톱)', gw_page('로비', left + right), h=1840)


# ------------------------------------------------------------------ 모바일 가입 지도 띠(사용자 D88, 2026-10-03 — 로고를 머리줄로, 실측 주 이름표 1 → 5)
# 사용자 D70(10-03 19:49): 「로고 판을 줄이거나 옮기기」 — 지금 판(워드마크 190, 약 206×79)이 96 띠의 왼쪽 절반 넘게를 덮어 주 이름표가 서주 하나(K10 재캡처 4).
def mjoin_body(strip_plate, header_logo):
    return (f'{gw_mtop(logo_on=header_logo)}<div style="position:relative;height:96px;flex-shrink:0;overflow:hidden">{mapimg("hero_m", MW, 480, "낙양 일대 지도 — 띠", top=-200)}'
            f'{strip_plate}</div>'
            f'<div style="padding:10px 16px;display:flex;flex-direction:column;gap:8px;flex-grow:1;overflow:hidden">'
            f'<h1 class="serif" style="margin:0;font-size:20px;font-weight:900">회원 가입</h1>{join_fields()}{btn("회원가입", "primary", style="width:100%")}'
            f'<a href="#" style="font-size:12.5px;min-height:44px;display:inline-flex;align-items:center">이미 계정이 있으신가요? 로그인</a></div>')


def board_mjoin():
    page31('V31K5MJoin.dc.html', 'K5 P-G03 가입 — 모바일(로고는 머리줄 · 96 띠는 지도만)', mjoin_body('', True), w=MW, h=MH)


def board_lobby_states():
    c_close = compact_row('pep', '1기', chip('점검 중', 'rust'), '운영진이 서버를 살피는 중입니다', btn_off('입장', '점검 중입니다'))
    c_pre = compact_row('s3', '1기', chip('준비 중', 'info'), '190년 1월 상순 · [시나리오]', btn_off('장수 만들기', '10월 3일 20:00에 열립니다'))
    popx = pop('마감 — 장수를 만들 수 없습니다', '사람 장수 자리가 모두 찼습니다(30 / 30). 자리가 나면 다시 열립니다.', recovery='다른 서버를 고르거나, 공지에서 새 서버 소식을 확인하세요.',
               style='position:relative;width:420px')
    col1 = (f'<div style="flex:1;min-width:0;display:flex;flex-direction:column;gap:10px;padding:12px">'
            f'<h2 class="serif" style="margin:0;font-size:18px;font-weight:900">서버 카드 상태</h2>{c_close}{c_pre}'
            f'{compact_row("s2", "7기", chip("마감", "rust"), "231년 1월 하순 · 삼국정립", btn_off("장수 만들기", "사람 장수 30 / 30"))}'
            f'<span class="muted" style="font-size:12px">사유 단추를 누르면(데스크톱 말풍선 · 모바일 하단 시트):</span>{popx}'
            f'{compact_row("pep", "1기", chip("참가 중", "moss") + chip("턴 멈춤", "rust"), "마지막 순 3월 중순 21:40 — 예약은 그대로 남습니다", btn("입장", "primary", href="#"))}</div>')
    col2 = (f'<div style="width:560px;flex-shrink:0;display:flex;flex-direction:column;gap:12px;padding:12px 12px 12px 0">'
            f'<section class="panel" style="height:280px">{sec("거르기 결과 0", "「참가 가능」")}{state_empty("이 조건에 맞는 서버가 없습니다", "다른 거르기를 고르거나 「전체」를 보세요.", btn("전체 보기", "sm"))}</section>'
            f'<section class="panel" style="height:300px">{sec("서버 목록 실패", "빈 목록과 다른 모양")}{state_error("서버 목록을 불러오지 못했습니다")}</section>'
            f'<section class="panel" style="flex:1;min-height:0">{sec("서버 0", "")}{state_empty("현재 이용할 수 있는 게임 서버가 없습니다", "새 서버가 열리면 공지로 알립니다.")}</section></div>')
    page31('V31K5LobbyStates.dc.html', 'K5 P-G04 로비 — 점검 · 준비 중 · 마감 사유 · 빈 · 오류', gw_page('로비', col1 + col2))


def board_mlobby():
    card = (f'<article class="panel" aria-label="서버 pep" style="flex-shrink:0">'
            f'<div style="position:relative;width:364px;height:222px;overflow:hidden">{mapimg("thumb", 364, 222, "pep 판도 — 작은 지도")}</div>'
            f'<div style="padding:10px 12px;display:flex;flex-direction:column;gap:6px"><div style="display:flex;gap:6px;align-items:center">'
            f'<h3 class="serif" style="margin:0;font-size:18px;font-weight:900">pep</h3>{chip("1기", "bronze")}{chip("참가 중", "moss")}</div>'
            f'<span class="t2" style="font-size:12.5px">200년 3월 중순 · 군웅할거</span><span class="t2" style="font-size:12.5px">세력 5 · 사람 24 / 30 · NPC 412 · 한 순 10분</span>'
            f'<div style="display:flex;gap:10px;align-items:center;padding:6px 0;border-top:1px solid #2c342f">{portrait("hahoudon", "하후돈", 34, 48)}'
            f'<span class="serif" style="font-weight:900">하후돈</span><span class="t2" style="font-size:12px">조조 소속</span></div>'
            f'{btn("입장", "primary", style="width:100%", href="#")}{btn("현황 펼치기", "sm", style="width:100%;background:transparent")}</div></article>')
    card2 = (f'<article class="panel" aria-label="서버 통일 서버" style="flex-shrink:0"><div style="position:relative;width:364px;height:120px;overflow:hidden">'
             f'{mapimg("thumb", 364, 222, "통일 서버 판도 — 작은 지도")}</div><div style="padding:10px 12px;display:flex;gap:6px;align-items:center">'
             f'<h3 class="serif" style="margin:0;font-size:18px;font-weight:900">통일 서버</h3>{chip("3기", "bronze")}{chip("모집 중", "moss")}</div></article>')
    body = (f'{gw_mtop()}<div role="group" aria-label="서버 거르기 — 옆으로 밀어 보기" style="height:60px;flex-shrink:0;display:flex;gap:6px;padding:8px 12px;overflow-x:auto;border-bottom:1px solid #2c342f">'
            + ''.join(f'<button type="button" class="btn sm" aria-pressed="{"true" if t == "전체" else "false"}" style="flex-shrink:0;{"background:#d3b064;color:#161410;border-color:#9c7f3f;font-weight:700" if t == "전체" else ""}">{t} <span class="mono" style="font-size:11px">{n}</span></button>' for t, n in LOBBY_FILTER)
            + f'</div><div style="flex-grow:1;overflow:hidden;display:flex;flex-direction:column;gap:10px;padding:10px 12px">{card}{card2}</div>')
    page31('V31K5MLobby.dc.html', 'K5 P-G04 로비 — 모바일', body, w=MW, h=MH)


# ================================================================== P-G05 계정
def slider(label, val, pos):
    return (f'<div class="fld"><span class="lb" style="display:flex;justify-content:space-between">{label}<output class="mono t2">{val}</output></span>'
            f'<div role="slider" aria-label="{label}" aria-valuenow="{pos}" style="position:relative;height:44px"><i style="position:absolute;left:0;right:0;top:20px;height:4px;background:#3d4740"></i>'
            f'<i style="position:absolute;left:{pos}%;top:10px;width:24px;height:24px;margin-left:-12px;background:#d3b064;border:2px solid #0c0f0e"></i></div></div>')


def portrait_editor(mobile=False):
    area = 326 if mobile else 300
    crop = (f'<div role="img" aria-label="카드 자르기 영역 — 끌어서 얼굴을 맞춘다 · 두 손가락으로 확대" style="position:relative;width:{area}px;height:{area if mobile else 380}px;overflow:hidden;border:1px solid #3d4740;flex-shrink:0">'
            f'{pic(PT.get("hahoudon", ""), area, area if mobile else 380, "원본 이미지")}'
            f'<i style="position:absolute;left:{area // 2 - 74}px;top:40px;width:148px;height:210px;border:2px solid #ffd36d;box-shadow:0 0 0 999px rgba(8,10,9,.55)"></i></div>')
    ctrls = (f'<div style="display:flex;flex-direction:column;gap:6px;min-width:0;flex:1"><span class="serif" style="font-weight:700">카드 구도 조절</span>'
             f'<span class="muted" style="font-size:11.5px">이미지를 끌어 얼굴 위치를 맞추세요. 휠이나 두 손가락으로 확대할 수 있습니다.</span>'
             f'{slider("확대 · 축소", "1.6배", 30)}{slider("좌우 위치", "", 48)}{slider("상하 위치", "", 22)}{btn("이 구도 초기화", "sm")}</div>')
    prev = (f'<div style="display:flex;flex-direction:column;gap:6px"><span class="muted" style="font-size:11.5px">세 구도 미리보기</span><div style="display:flex;gap:8px;align-items:flex-end">'
            f'{portrait("hahoudon", "큰 그림", 80 if mobile else 127, 114 if mobile else 180)}{portrait("hahoudon", "카드", 62 if mobile else 89, 88 if mobile else 126)}'
            f'{portrait("hahoudon", "아이콘", 44 if mobile else 56, 44 if mobile else 56)}</div>'
            f'<span class="muted" style="font-size:11px">큰 그림 633×900 · 카드 148×210 · 아이콘 96×96</span></div>')
    return seg(['큰 그림', '카드', '아이콘'], '카드', '편집할 구도'), crop, ctrls, prev


def board_account():
    lp = lambda t, b, st='': f'<section class="panel" style="{st}">{sec(t)}<div style="padding:10px 12px;display:flex;flex-direction:column;gap:8px">{b}</div></section>'
    left = (f'<div style="width:400px;flex-shrink:0;display:flex;flex-direction:column;gap:10px">'
            + lp('별명 바꾸기', f'{field("별명", inp("원양"), "2~20자, 다른 사람과 겹칠 수 없습니다.")}<div style="display:flex;gap:8px;align-items:center">{btn("별명 바꾸기", "primary")}<span class="ms" style="font-size:12px">별명을 바꿨습니다.</span></div>')
            + lp('비밀번호 바꾸기', f'{field("현재 비밀번호", inp(chr(8226) * 8))}<div style="display:grid;grid-template-columns:1fr 1fr;gap:8px">{field("새 비밀번호", inp("", "6자 이상"))}{field("새 비밀번호 확인", inp(""))}</div>{btn_off("바꾸기", "새 비밀번호를 쓰세요")}')
            + lp('대표 장수', f'<span class="muted" style="font-size:11.5px">커뮤니티 글 · 댓글에 붙는 서버 배지입니다. 내 계정이 가진 장수만 고를 수 있습니다.</span>'
                 f'<span role="status" style="font-size:12.5px">지금 대표 장수: <b>하후돈 · pep 1기</b></span>'
                 f'<div role="listbox" aria-label="대표 장수" style="display:flex;flex-direction:column;border-top:1px solid #2c342f">{opt("하후돈", "pep 1기 · 군웅할거", "", sel=True, h=48)}{opt("없음", "배지를 달지 않는다", "", h=48)}</div>{btn("대표 장수 저장")}')
            + '</div>')
    quit_ = (f'<section class="panel" style="border-color:#c96b5d;flex-shrink:0">{sec("계정 탈퇴")}<div style="padding:10px 12px;display:flex;gap:10px;align-items:flex-end">'
             f'{field("현재 비밀번호", inp("", "탈퇴하려면 입력"), "계정을 지우면 되돌릴 수 없습니다.", style="flex:1")}{btn("계정 삭제", "danger")}</div></section>')
    segx, crop, ctrls, prev = portrait_editor()
    right = (f'<div style="flex:1;min-width:0;display:flex;flex-direction:column;gap:10px"><section class="panel" style="flex-shrink:0">{sec("초상", "jpg · png · webp · 최대 8MB")}'
             f'<div style="padding:12px;display:grid;grid-template-columns:126px minmax(0,1fr);gap:16px;border-bottom:1px solid #2c342f">{portrait("hahoudon", "지금 초상", 126, 178)}'
             f'<div style="display:flex;flex-direction:column;gap:8px"><span class="t2" style="font-size:12px">원본을 올린 뒤 큰 그림 · 카드 · 아이콘의 구도를 각각 조절하세요. 원본은 보관되어 다시 편집할 수 있습니다.</span>'
             f'{field("이미지 파일", inp("hahoudon.png", ic="copy"))}<div style="display:flex;gap:8px;flex-wrap:wrap">{btn("보관된 원본으로 다시 편집")}'
             f'{btn_off("올리기", "세 구도를 확인하세요")}{btn("지우기", "danger")}</div></div></div>'
             f'<div style="padding:12px;display:flex;gap:16px;align-items:flex-start"><div style="display:flex;flex-direction:column;gap:8px">{segx}{crop}</div>{ctrls}{prev}</div></section>{quit_}</div>')
    main = (f'<div style="flex:1;min-width:0;display:flex;flex-direction:column;gap:10px;padding:12px"><div style="height:40px;display:flex;align-items:center;justify-content:space-between">'
            f'<h1 class="serif" style="margin:0;font-size:22px;font-weight:900">계정 설정</h1>{btn("로비로", "sm", "lobby", href="#")}</div>'
            f'<div style="display:flex;gap:12px;min-height:0;flex:1">{left}{right}</div></div>')
    page31('V31K5Account.dc.html', 'K5 P-G05 계정 — 별명 · 비밀번호 · 초상 · 대표 장수 · 탈퇴', gw_page('계정', main))


def board_maccount():
    segx, crop, ctrls, prev = portrait_editor(mobile=True)
    reason = sheet('올리기 — 아직 할 수 없습니다', '<div style="padding:0 16px 12px;display:flex;flex-direction:column;gap:8px"><span class="rs" style="font-weight:700">세 구도를 확인하세요</span>'
                   '<span class="t2" style="font-size:12.5px;line-height:1.5">큰 그림 · 카드 · 아이콘을 한 번씩 눌러 구도를 맞추면 올릴 수 있습니다.</span></div>', height=176)
    body = (f'{gw_mtop()}<div style="flex-grow:1;overflow:hidden;display:flex;flex-direction:column;gap:8px;padding:10px 12px">'
            f'<h1 class="serif" style="margin:0;font-size:19px;font-weight:900">계정 · 초상</h1>{segx}{crop}{prev}'
            f'<div style="display:flex;gap:8px">{btn_off("올리기", "세 구도를 확인하세요")}</div></div>'
            f'<div class="dim"></div>{reason}')
    page31('V31K5MAccount.dc.html', 'K5 P-G05 계정 — 모바일 초상 편집 · 사유 시트', body, w=MW, h=MH)


# ================================================================== P-G06~G08 커뮤니티
BOARDS_CAT = [('전체', '1,284'), ('공지', '12'), ('자유', '611'), ('건의', '87'), ('전략·공략', '204'), ('서버 이야기', '263'), ('창작·일지', '107')]
POSTS = [('공지', True, '3기 서버 개시 안내와 바뀐 규칙', '운영자', '', '2026. 09. 28. 12:00', '4,210', '38'),
         ('전략·공략', False, '영천군에서 보급이 끊기지 않게 창고를 두는 법', '북풍', '안량 · pep 1기', '2026. 09. 29. 22:41', '812', '21'),
         ('서버 이야기', False, '관도 전선이 열렸다 — 원소 쪽 분위기', '북풍', '안량 · pep 1기', '2026. 09. 29. 20:03', '655', '17'),
         ('자유', False, '밤 턴 넘기는 사람들 모여라', '솔바람', '', '2026. 09. 29. 18:27', '301', '9'),
         ('창작·일지', False, '하후돈 일지 3화 — 양성현의 봄', '원양', '하후돈 · pep 1기', '2026. 09. 28. 23:55', '498', '14'),
         ('건의', False, '서신에서 보낸 것만 모아 보기', '단풍', '황충 · 통일 서버 3기', '2026. 09. 28. 15:12', '144', '3'),
         ('전략·공략', False, '계책 덱을 비우는 순서', '원양', '하후돈 · pep 1기', '2026. 09. 27. 21:09', '920', '26')]


def rep_chip(t):
    return f'<span class="chip" style="gap:4px">{icon("retinue", 12)}대표 장수 · {t}</span>' if t else ''


def post_row(c, pin, title, who, rep, when, views, cm, mobile=False):
    if mobile:
        return (f'<a href="#" style="display:flex;flex-direction:column;gap:4px;padding:10px 12px;border-bottom:1px solid #2c342f;color:#ece6d8;min-height:44px">'
                f'<span style="display:flex;gap:6px;align-items:center">{chip(c)}{chip("고정", "bronze") if pin else ""}</span>'
                f'<span style="font-size:14px;font-weight:{700 if pin else 500};line-height:1.4">{title}</span>'
                f'<span class="muted" style="font-size:11.5px;display:flex;gap:6px;flex-wrap:wrap;align-items:center">{who}{rep_chip(rep)}<span class="mono">{when[6:]}</span>조회 {views} · 댓글 {cm}</span></a>')
    return (f'<a href="#" style="min-height:60px;display:grid;grid-template-columns:40px minmax(0,1fr) 160px;gap:10px;align-items:center;padding:6px 12px;border-bottom:1px solid #2c342f;color:#ece6d8">'
            f'{portrait("", who, 40, 40)}<div style="display:flex;flex-direction:column;gap:3px;min-width:0"><span style="display:flex;gap:6px;align-items:center;min-width:0">{chip(c)}'
            f'{chip("고정", "bronze") if pin else ""}<span style="font-weight:{700 if pin else 500};white-space:nowrap;overflow:hidden;text-overflow:ellipsis">{title}</span></span>'
            f'<span class="muted" style="font-size:11.5px;display:flex;gap:6px;align-items:center">{who}{rep_chip(rep)}</span></div>'
            f'<div class="muted" style="font-size:11.5px;text-align:right;line-height:1.5"><span class="mono">{when}</span><br>조회 {views} · 댓글 {cm}</div></a>')


def board_rail():
    return (f'<aside style="width:300px;flex-shrink:0;display:flex;flex-direction:column;gap:12px">'
            f'<section class="panel">{sec("내 계정")}<div style="padding:10px 12px;display:flex;gap:10px">{portrait("hahoudon", "원양", 48, 48)}<div style="display:flex;flex-direction:column;gap:4px">'
            f'<b>원양</b><span class="muted" style="font-size:11.5px;line-height:1.45">얼굴은 계정 초상입니다. 대표 장수는 계정 설정에서 정합니다.</span><a href="#" style="font-size:12px;min-height:44px;display:inline-flex;align-items:center">대표 장수 바꾸기</a></div></div></section>'
            f'<section class="panel">{sec("인기 글", "최근 7일")}<ol style="margin:0;padding:0 12px;list-style:none">'
            + ''.join(f'<li style="min-height:44px;display:flex;gap:8px;align-items:center;border-bottom:1px solid #2c342f;font-size:12.5px"><span class="mono bz">{i}</span><a href="#" style="color:#ece6d8;flex:1;min-width:0;min-height:44px;display:flex;align-items:center;overflow:hidden"><span style="white-space:nowrap;overflow:hidden;text-overflow:ellipsis">{t}</span></a><span class="mono muted">{c}</span></li>'
                      for i, (t, c) in enumerate([('3기 서버 개시 안내와 바뀐 규칙', 38), ('계책 덱을 비우는 순서', 26), ('영천군에서 보급이 끊기지 않게…', 21)], 1))
            + '</ol></section>'
            f'<section class="panel">{sec("세 공간의 경계", "서로 섞이지 않는다")}<ul class="ul" style="padding:4px 12px 8px"><li><b>커뮤니티</b> — 서버 밖, 모든 계정</li>'
            f'<li><b>회의실</b> — 게임 안, 같은 세력 장수</li><li><b>기밀실</b> — 게임 안, 기밀실 참여자만 · 열람 기록이 남음</li></ul></section></aside>')


def board_board():
    cats = seg(BOARDS_CAT, '전체', '게시판')
    tools = (f'<div style="display:flex;gap:8px;align-items:center">{seg(["최신", "인기", "내 글"], "최신", "정렬")}'
             f'<div style="margin-left:auto;display:flex;gap:6px;width:320px">{search("검색 — 제목 · 내용", style="flex:1")}{btn("검색")}</div></div>')
    listx = ''.join(post_row(*p) for p in POSTS)
    pager = f'<div style="display:flex;gap:8px;align-items:center;justify-content:center;padding:8px">{btn_off("이전", "첫 쪽입니다")}<span class="mono t2">1 / 65</span>{btn("다음")}</div>'
    main = (f'<div style="width:920px;flex-shrink:0;display:flex;flex-direction:column;gap:10px">'
            f'<div style="display:flex;align-items:flex-end;gap:12px"><div style="display:flex;flex-direction:column;gap:2px"><span class="muted" style="font-size:11px;letter-spacing:.1em">오픈삼국 커뮤니티</span>'
            f'<h1 class="serif" style="margin:0;font-size:24px;font-weight:900">커뮤니티 게시판</h1><span class="t2" style="font-size:12.5px">서버 밖, 계정 단위 공간입니다. 세력 회의실 · 기밀실과 분리됩니다.</span></div>'
            f'{btn("글쓰기", "primary", "copy", style="margin-left:auto", href="#")}</div>{cats}'
            f'<span class="muted" style="font-size:11px">게시판 목록 · 순서 · 쓰기 허용은 서버 목록(G-01)을 읽는다 — 운영 콘솔에서 만들고 지운다</span>{tools}'
            f'<section class="panel">{listx}{pager}</section></div>')
    page31('V31K5Board.dc.html', 'K5 P-G06 커뮤니티 목록(데스크톱)', gw_page('커뮤니티', f'<div style="flex:1;display:flex;gap:12px;justify-content:center;padding:12px;min-width:0">{main}{board_rail()}</div>'))


def board_mboard():
    cats = ('<div role="group" aria-label="게시판 — 옆으로 밀어 보기" style="display:flex;gap:6px;overflow-x:auto;flex-shrink:0">'
            + ''.join(f'<button type="button" class="btn sm" aria-pressed="{"true" if t == "전체" else "false"}" style="flex-shrink:0;{"background:#d3b064;color:#161410;border-color:#9c7f3f;font-weight:700" if t == "전체" else ""}">{t}</button>' for t, n in BOARDS_CAT)
            + '</div>')
    body = (f'{gw_mtop()}<div style="flex-grow:1;overflow:hidden;display:flex;flex-direction:column;gap:8px;padding:10px 12px;position:relative">'
            f'<h1 class="serif" style="margin:0;font-size:20px;font-weight:900">커뮤니티</h1>{cats}<div style="display:flex;gap:6px">{seg(["최신", "인기", "내 글"], "최신", "정렬")}{ibtn("search", "검색")}</div>'
            f'<section class="panel">{"".join(post_row(*p, mobile=True) for p in POSTS[:5])}</section>'
            f'<a href="#" class="btn primary" aria-label="글쓰기" style="position:absolute;right:12px;bottom:12px;width:56px;height:56px;padding:0">{icon("copy", 22, "#161410")}</a></div>')
    page31('V31K5MBoard.dc.html', 'K5 P-G06 커뮤니티 목록 — 모바일', body, w=MW, h=MH)


COMMENTS = [('북풍', '안량 · pep 1기', '2026. 09. 29. 23:02', '창고를 영음현에 두면 허현까지 한 길로 이어지더군요.'),
            ('솔바람', '', '2026. 09. 29. 23:40', '좋은 글 고맙습니다.')]


def post_body(mobile=False):
    cm = ''.join(f'<div style="display:grid;grid-template-columns:28px minmax(0,1fr) auto;gap:8px;padding:8px 0;border-bottom:1px solid #2c342f">{portrait("", w, 28, 28)}'
                 f'<div style="display:flex;flex-direction:column;gap:2px;min-width:0"><span style="font-size:12.5px;display:flex;gap:6px;align-items:center;flex-wrap:wrap"><b>{w}</b>{rep_chip(r)}<span class="mono muted" style="font-size:11px">{t}</span></span>'
                 f'<span class="t2" style="font-size:13px;line-height:1.5">{c}</span></div>{btn("신고", "sm", style="background:transparent")}</div>' for w, r, t, c in COMMENTS)
    return (f'<a href="#" style="font-size:12.5px;min-height:44px;display:inline-flex;align-items:center;gap:6px">{icon("back", 16)}게시판 목록</a>'
            f'<div style="display:flex;gap:6px">{chip("전략·공략")}</div><h1 class="serif" style="margin:0;font-size:{20 if mobile else 26}px;font-weight:900;line-height:1.35">영천군에서 보급이 끊기지 않게 창고를 두는 법</h1>'
            f'<div style="display:flex;gap:8px;align-items:center;flex-wrap:wrap;padding-bottom:10px;border-bottom:1px solid #2c342f">{portrait("", "북풍", 40, 40)}<b>북풍</b>{rep_chip("안량 · pep 1기")}'
            f'<span class="mono muted" style="font-size:11.5px">2026. 09. 29. 22:41 · 조회 812 · 수정됨</span></div>'
            f'<div class="t2" style="font-size:14px;line-height:1.75"><p style="margin:0 0 10px">창고는 군 치소 가까이에 두고, 현과 현 사이 길이 끊기지 않는지 먼저 봅니다.</p>'
            f'<p style="margin:0"><b>양적현 → 영음현 → 허현</b> 줄이 가장 안전했습니다. [글 본문 예시]</p></div>'
            f'<div style="display:flex;gap:8px;flex-wrap:wrap">{btn("신고", "sm", "alert")}</div>'
            f'<h2 class="serif" style="margin:6px 0 0;font-size:16px;font-weight:900">댓글 2</h2>{cm}'
            f'{field("댓글", inp("", "댓글을 남겨보세요 — 2000자까지", cls="area"))}<div>{btn("댓글 등록", "primary")}</div>')


def board_post():
    main = f'<article style="width:920px;flex-shrink:0;display:flex;flex-direction:column;gap:10px">{post_body()}</article>'
    page31('V31K5BoardPost.dc.html', 'K5 P-G07 커뮤니티 글(데스크톱)', gw_page('커뮤니티', f'<div style="flex:1;display:flex;justify-content:center;padding:12px;overflow:hidden">{main}</div>'))


def board_mpost():
    rep = sheet('글 신고', f'<div style="padding:0 16px 8px;display:flex;flex-direction:column;gap:8px">{field("신고 사유", inp("", "무엇이 문제인지 적어 주세요 — 200자까지", cls="area"))}'
                f'<span class="muted" style="font-size:11.5px">운영자가 확인합니다. 같은 글에 이미 열린 신고가 있으면 접수되지 않습니다.</span></div>',
                height=300, foot=f'{btn("취소", style="flex:1")}{btn_off("신고 접수", "사유를 쓰세요", style="flex:2")}')
    body = (f'{gw_mtop()}<article style="flex-grow:1;overflow:hidden;display:flex;flex-direction:column;gap:8px;padding:10px 12px">{post_body(True)}</article>'
            f'<div class="dim"></div>{rep}')
    page31('V31K5MBoardPost.dc.html', 'K5 P-G07 커뮤니티 글 — 모바일 · 신고 시트', body, w=MW, h=MH)


def write_form(mobile=False):
    tb = (f'<div role="toolbar" aria-label="서식" style="display:flex;gap:4px;padding:6px;border-bottom:1px solid #2c342f">'
          f'<button type="button" class="ibtn" aria-pressed="false" aria-label="굵게" style="font-weight:900">가</button>'
          f'<button type="button" class="ibtn" aria-pressed="false" aria-label="기울임" style="font-style:italic">가</button>'
          f'<button type="button" class="ibtn" aria-pressed="false" aria-label="취소선" style="text-decoration:line-through">가</button></div>')
    ed = (f'<div class="fld"><span class="lb">내용</span><div class="panel" style="min-height:{260 if mobile else 380}px">{tb}'
          f'<div role="textbox" aria-multiline="true" aria-label="내용" style="padding:12px;font-size:14px;line-height:1.7" class="t2">영천군 보급을 한 줄로 이으려면 …</div></div>'
          f'<span class="help" style="text-align:right" aria-live="polite">112 / 10000</span></div>')
    brd = (f'<div role="listbox" aria-label="게시판" style="display:flex;gap:6px;flex-wrap:wrap">'
           + ''.join(f'<button type="button" role="option" class="btn sm" aria-selected="{"true" if t == "전략·공략" else "false"}" style="{"background:#d3b064;color:#161410;border-color:#9c7f3f;font-weight:700" if t == "전략·공략" else ""}">{t}</button>'
                     for t, n in BOARDS_CAT[2:]) + '</div>')
    return (f'{field("게시판", brd, "쓰기가 열린 게시판만 보인다(공지는 운영자만)")}{field("제목", inp("영천군에서 보급이 끊기지 않게 창고를 두는 법"), "120자까지")}{ed}'
            f'<div style="display:flex;gap:8px;justify-content:flex-end">{btn("취소", href="#")}{btn("등록", "primary")}</div>')


def board_write():
    main = (f'<div style="width:920px;flex-shrink:0;display:flex;flex-direction:column;gap:10px"><div style="display:flex;flex-direction:column;gap:2px">'
            f'<span class="muted" style="font-size:11px;letter-spacing:.1em">새 글</span><h1 class="serif" style="margin:0;font-size:24px;font-weight:900">게시글 작성</h1>'
            f'<span class="t2" style="font-size:12.5px">굵게 · 기울임 · 취소선 같은 기본 서식을 쓸 수 있습니다.</span></div>{write_form()}</div>')
    page31('V31K5BoardWrite.dc.html', 'K5 P-G08 커뮤니티 글쓰기(데스크톱)', gw_page('커뮤니티', f'<div style="flex:1;display:flex;justify-content:center;padding:12px;overflow:hidden">{main}</div>'))


def board_mwrite():
    body = (f'{gw_mtop()}<div style="flex-grow:1;overflow:hidden;display:flex;flex-direction:column;gap:8px;padding:10px 12px">'
            f'<h1 class="serif" style="margin:0;font-size:20px;font-weight:900">게시글 작성</h1>{write_form(True)}</div>')
    page31('V31K5MBoardWrite.dc.html', 'K5 P-G08 커뮤니티 글쓰기 — 모바일', body, w=MW, h=MH)


# ================================================================== P-G10 · P-G11 정책 문서(K0 가 원장에 더함 — 본문은 U5 초안 → 승인)
def policy_doc(title, mobile=False):
    toc = ['1. 모으는 정보', '2. 쓰는 곳', '3. 보관 기간', '4. 지우기 요청', '5. 연락처'] if '개인' in title else ['1. 계정', '2. 금지 행동', '3. 이용 제한', '4. 서비스 변경', '5. 연락처']
    sec_ = ''.join(f'<h2 class="serif" style="margin:14px 0 6px;font-size:16px;font-weight:900">{t}</h2><p class="t2" style="margin:0;font-size:13.5px;line-height:1.75">[본문 초안 — 공개 알파 정책 문구(U5)로 쓰고 사용자가 승인]</p>' for t in toc[:4 if mobile else 5])
    nav = ('' if mobile else '<nav aria-label="차례" style="width:220px;flex-shrink:0;display:flex;flex-direction:column;border-left:1px solid #2c342f;padding-left:12px">'
           + ''.join(f'<a href="#" style="min-height:44px;display:flex;align-items:center;font-size:12.5px;color:#b9b2a3">{t}</a>' for t in toc) + '</nav>')
    return (f'<div style="display:flex;gap:24px;min-width:0"><article style="flex:1;min-width:0"><div style="display:flex;gap:6px;align-items:center">{draft_chip("본문 초안 — U5")}'
            f'<span class="muted mono" style="font-size:11.5px">시행 [미정]</span></div><h1 class="serif" style="margin:8px 0 0;font-size:{22 if mobile else 28}px;font-weight:900">{title}</h1>{sec_}</article>{nav}</div>')


def board_policy():
    main = f'<div style="width:960px;padding:24px 0;overflow:hidden">{policy_doc("개인정보처리방침")}</div>'
    body = gw_topbar('login') + f'<div style="flex-grow:1;display:flex;justify-content:center;min-height:0">{main}</div><div style="border-top:1px solid #2c342f;flex-shrink:0">{policy_links()}</div>'
    page31('V31K5Policy.dc.html', 'K5 P-G10 · P-G11 정책 문서 — 개인정보처리방침 · 이용약관(같은 틀)', body)


def board_mpolicy():
    body = gw_mtop() + f'<div style="flex-grow:1;overflow:hidden;padding:12px">{policy_doc("이용약관", True)}</div>'
    page31('V31K5MPolicy.dc.html', 'K5 P-G11 이용약관 — 모바일', body, w=MW, h=MH)


# ================================================================== P-G09 운영 콘솔
CONSOLE_TABS = [('개요', '조회'), ('회원', '가역 · 파괴적'), ('게시판', '가역'), ('신고', '가역'), ('공지', '가역'), ('턴', '가역'), ('따라잡기', '가역'), ('서버', '배포 · 파괴적')]


def console(on, body, title=None, risk=None):
    # 켜진 탭 위험 표식은 --text-2(t2) — 청동 바탕 위 --muted 는 4.26:1(AA 미만, K10 10-03 axe). 사용자 D57.
    rk = risk or dict(CONSOLE_TABS)[on]
    rail = ('<nav aria-label="운영 콘솔" style="width:200px;flex-shrink:0;display:flex;flex-direction:column;background:#141816;border-right:1px solid #3d4740;padding:8px 0">'
            '<span class="serif" style="font-size:15px;font-weight:900;padding:6px 14px 10px">운영 콘솔</span>'
            + ''.join(f'<a href="#" aria-current="{"page" if t == on else "false"}" style="min-height:48px;display:flex;flex-direction:column;justify-content:center;padding:0 14px;'
                      f'{"background:rgba(211,176,100,.10);box-shadow:inset 3px 0 0 #d3b064;color:#d3b064" if t == on else "color:#ece6d8"}"><span style="font-size:13.5px;font-weight:{700 if t == on else 500}">{t}</span>'
                      f'<span class="{"t2" if t == on else "muted"}" style="font-size:10.5px">{r}</span></a>' for t, r in CONSOLE_TABS) + '</nav>')
    head = (f'<div style="height:52px;flex-shrink:0;display:flex;align-items:center;gap:10px;padding:0 16px;border-bottom:1px solid #2c342f">'
            f'<h1 class="serif" style="margin:0;font-size:20px;font-weight:900">{title or on}</h1>{chip("위험 등급 · " + rk, "rust" if "파괴" in rk else "")}</div>')
    return gw_page('관리', f'{rail}<div style="flex:1;min-width:0;display:flex;flex-direction:column;position:relative">{head}'
                           f'<div style="flex:1;min-height:0;overflow:hidden;padding:12px 16px;display:flex;flex-direction:column;gap:12px">{body}</div></div>')


SERVERS = [('pep', '1기', '군웅할거', 'sha-3aa678b', 'sha-3aa678b', 'sha-3aa678b', 'run', ''), ('통일 서버', '3기', '반동탁연합', 'sha-3aa678b', 'sha-19c2e0d', 'sha-3aa678b', 'catch', '불일치'),
           ('old', '2기', '—', '연결 실패', '연결 실패', '조회 실패', 'fail', '')]


def board_admin():
    gw = (f'<section class="panel">{sec("게이트웨이", "위험 등급: 조회")}<div style="padding:10px 12px;display:flex;gap:10px;align-items:center;font-size:13px">'
          f'<span class="mono muted">gateway</span><span class="mono">sha-3aa678b · 0.9.2</span>{chip("새 버전 sha-4f01c77", "info")}</div></section>')
    rows = []
    for n, g, sc, a, e, tag, st, skew in SERVERS:
        turn = {'run': f'{chip("턴 도는 중", "moss")}<span class="mono muted" style="font-size:11px">마지막 턴 3분 전</span>',
                'catch': f'{chip("따라잡는 중 · 2배속", "bronze")}',
                'fail': f'{chip("조회 실패", "rust")}'}[st]
        rows.append([f'<span style="display:flex;gap:6px;align-items:center"><b>{n}</b>{chip(g, "bronze")}{chip(skew, "rust") if skew else ""}</span>', sc,
                     f'<span class="mono">{a}</span>', f'<span class="mono">{e}</span>', f'<span class="mono">{tag}</span>', f'<span style="display:flex;gap:6px;align-items:center">{turn}</span>',
                     btn('게임 관리', 'sm', 'admin', href='#') if st != 'fail' else btn('다시 시도', 'sm', 'refresh')])
    tb = tbl(['서버', '시나리오', 'game-api', 'game-engine', '배포 태그', '턴', ''], rows)
    gs = (f'<section class="panel">{sec("게임 서버", "3대")}<div style="padding:4px 8px 8px">{tb}</div>'
          f'<div style="padding:0 12px 12px;display:flex;gap:8px">{btn("서버 탭으로", "sm")}<span class="muted" style="font-size:11.5px;align-self:center">마지막 턴 · 턴 멈춤 의심은 공개 응답의 턴 시각(K10-01)을 쓴다 — 서버 대기</span></div></section>')
    warn = alert_box('턴 멈춤 의심 — 통일 서버 3기: 마지막 턴이 [미정]분 넘게 움직이지 않았습니다. 턴 탭에서 살피세요.')
    page31('V31K5Admin.dc.html', 'K5 P-G09 운영 콘솔 — 개요', console('개요', gw + warn + gs))


SCN_LIST = ('<div role="listbox" aria-label="시나리오" style="display:flex;flex-direction:column;border-top:1px solid #2c342f">'
            + opt('군웅할거', 'scenario_1020', '', sel=True, h=48) + opt('반동탁연합', '[코드]', '', h=48) + '</div>')


def board_admin_server():
    ver = (f'<section class="panel">{sec("실행 버전 · 버전 배포")}<div style="padding:8px 12px;display:flex;gap:10px;align-items:center;font-size:13px"><b>pep</b>{chip("1기", "bronze")}'
           f'<span class="mono">지금 sha-3aa678b</span>{chip("새 버전 sha-4f01c77", "info")}{btn("최신으로 승격", "sm")}{btn_off("이 버전으로 배포", "지금 버전입니다")}</div>'
           f'<div style="padding:0 12px 10px" class="muted"><span style="font-size:11.5px">이 배포는 game-api · web-game 만 바꾼다. game-engine(진행 중 턴 상태)은 엔진 포함 승격 워크플로(콘솔 밖)로.</span></div></section>')
    gset = (f'<section class="panel">{sec("게임 설정 · pep 1기", "게임 관리의 게임 설정을 이 한 곳으로(K0 결정)")}<div style="padding:10px 12px;display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:10px">'
            f'{kv("상태", "열림", "ms")}{kv("시나리오", "군웅할거")}{kv("지금", "200년 3월 중순")}{kv("한 순", "10분")}'
            f'{field("사람 장수 상한", inp("30"))}{field("시작 시각", inp("2026-09-30 20:00"), "형식 2026-10-03 20:00")}{field("시작 연도(읽기만)", inp("190"), "시나리오가 정한다")}'
            f'{field("한 순 길이", inp("10", unit="분"), "1 · 2 · 5 · 10 · 20 · 30 · 60 · 120 — 엔진을 다시 띄워야 적용")}</div></section>')
    dlg = dialog('pep 리셋', f'<div style="padding:14px 16px;display:flex;flex-direction:column;gap:12px">'
                 f'{alert_box("이 서버를 아래 설정으로 처음부터 다시 시작합니다. DB · Redis 볼륨을 초기화하며 되돌릴 수 없습니다.")}'
                 f'<div style="display:grid;grid-template-columns:120px minmax(0,1fr);gap:10px">{field("기수", inp("2"))}'
                 f'{field("한 순 길이(분)", seg(["1", "2", "5", "10", "20", "30", "60", "120"], "10", "한 순 길이"))}</div>'
                 f'{field("시나리오", SCN_LIST)}'
                 f'{checkbox("시나리오 자동 시드", True)}'
                 f'<span class="muted" data-lint="skip" style="font-size:11.5px;line-height:1.5">뺀 칸(삼모): 시간 동기화 · NPC 상성 · 확장 NPC · 장수 임의 생성 · NPC 빙의 · 이미지 표기 · 휴식 턴 시 장수 턴 · 자동 행동 유효 시간 · 임관 모드 · 토너먼트 자동 시작 · 오픈 예약 · 가오픈 예약</span></div>',
                 f'{btn("취소")}{btn("리셋 실행", "danger")}', w=560, style='position:absolute;left:calc(50% - 280px);top:40px')
    body = ver + gset + f'<div class="scrim"></div>{dlg}'
    page31('V31K5AdminServer.dc.html', 'K5 P-G09 운영 콘솔 — 서버(리셋 대화상자 · 새 폼)', console('서버', body))


MEMBERS = [('1024', 'hahoudon', 'hhd@…', '일반', '원양', 'pep 하후돈', '2026-08-01', '2026-09-30', '—'),
           ('1031', 'jojo', '—', '운영자', '맹덕', 'pep 조조', '2026-07-20', '2026-09-30', '—'),
           ('1077', 'spam01', 'sp@…', '차단 · 2026-10-07까지', '—', '—', '2026-09-10', '2026-09-11', '—'),
           ('1090', 'bye', 'by@…', '일반', '떠난이', '—', '2026-06-02', '2026-08-02', '2026-10-01')]


def board_admin_members():
    sw = (f'<div style="display:flex;gap:16px;align-items:center;flex-wrap:wrap"><span style="display:flex;gap:8px;align-items:center">새 가입 받기{seg(["받음", "막음"], "받음", "새 가입")}</span>'
          f'<span style="display:flex;gap:8px;align-items:center">로그인 받기{seg(["받음", "막음"], "받음", "로그인")}</span>'
          f'<span style="margin-left:auto;display:flex;gap:6px">{btn("탈퇴 계정 정리(1개월+)", "danger")}{btn("오래된 계정 정리(6개월+)", "danger")}</span></div>')
    rows = [[f'<span class="mono">{i}</span>', u, e, chip(st, 'rust' if '차단' in st else ('bronze' if st == '운영자' else '')), nk, portrait('', nk if nk != '—' else '?', 32, 32),
             (chip('서버 대기', 'info') if g == '—' else g), f'<span class="mono">{j}</span>', f'<span class="mono">{l}</span>', f'<span class="mono">{d}</span>',
             btn('조치', 'sm', 'tools')] for i, u, e, st, nk, g, j, l, d in MEMBERS]
    tb = tbl(['번호', '계정명', '이메일', '상태', '별명', '초상', '서버별 장수', '가입일', '최근 로그인', '탈퇴 예정', ''], rows)
    act = (f'<section class="panel" style="position:absolute;right:16px;top:188px;width:340px;background:#1b201d;border-color:#9c7f3f;box-shadow:0 10px 30px rgba(0,0,0,.55)">'
           f'{sec("spam01 조치")}<div role="listbox" aria-label="조치" style="display:flex;flex-direction:column">{opt("임시 비밀번호 발급", "결과 창에서 복사한다", "", h=48)}'
           f'{opt("차단", "일수를 정한다 — 0 이하는 영구", "", h=48)}{opt("차단 풀기", "", "", sel=True, h=48)}{opt("이메일 영구 차단", "이메일이 없으면 할 수 없다", "", no=True, h=48)}'
           f'{opt("강제 탈퇴", "되돌릴 수 없다", "", h=48)}</div></section>')
    body = (sw + f'<div style="display:flex;gap:8px;align-items:center">{search("계정명 · 별명으로 찾기", style="width:360px")}{seg(["전체", "일반", "운영자", "차단"], "전체", "상태 거르기")}</div>'
            f'<section class="panel">{tb}</section>'
            f'<span class="muted" style="font-size:11.5px">「특별 · 부운영자」 등급과 「별도 권한」은 뺐다(역할이 USER/ADMIN뿐이라 효과가 없다). 서버별 장수는 원천이 비어 있다(K5-14).</span>{act}')
    page31('V31K5AdminMembers.dc.html', 'K5 P-G09 운영 콘솔 — 회원(조치 목록 열림)', console('회원', body))


def board_admin_turn():
    srv = f'<div style="display:flex;gap:8px;align-items:center">게임 서버{seg([("pep", "1기"), ("통일 서버", "3기")], "통일 서버", "게임 서버")}</div>'
    turn = (f'<section class="panel">{sec("턴", "통일 서버 3기")}<div style="padding:12px;display:flex;gap:12px;align-items:center;flex-wrap:wrap">'
            f'{chip("턴 도는 중", "moss")}<span class="mono t2">마지막 턴 194년 7월 상순 · 21:40</span>'
            f'<span style="margin-left:auto;display:flex;gap:6px">{btn("턴 멈추기", "danger")}{btn_off("다시 돌리기", "이미 도는 중입니다")}</span></div>'
            f'<div style="padding:0 12px 12px"><span class="muted" style="font-size:11.5px">「턴 멈추기」는 확인 대화상자를 거친다 — 그 서버의 턴이 멈춘다. 예약은 그대로 남는다.</span></div></section>')
    catch = (f'<section class="panel">{sec("밀린 턴 따라잡기", "2026-09-27 결정")}<div style="padding:12px;display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:10px">'
             f'{kv("지금 지연", "3시간 20분", "rs")}{kv("회복한 지연", "1시간 05분")}{kv("지금 배속", "2배속", "bz")}{kv("정상 속도 예상", "2026-09-30 23:10 (한국 시간)")}</div>'
             f'<div style="padding:0 12px 12px;display:flex;gap:10px;align-items:center">{seg(["2배속", "4배속"], "2배속", "따라잡기 배속")}{btn_off("배속 적용", "지금과 같은 배속입니다")}</div></section>')
    calm = (f'<section class="panel" style="height:200px">{sec("pep 1기", "따라잡기 없음")}{state_empty("정상 속도로 돌고 있습니다", "밀린 순이 생기면 여기서 배속을 고를 수 있습니다.")}</section>')
    page31('V31K5AdminTurn.dc.html', 'K5 P-G09 운영 콘솔 — 턴 · 따라잡기', console('따라잡기', srv + turn + catch + calm, title='턴 · 따라잡기'))


BOARD_DEFS = [('공지', '운영자만', 12), ('자유', '모든 회원', 611), ('건의', '모든 회원', 87), ('전략·공략', '모든 회원', 204), ('서버 이야기', '모든 회원', 263), ('창작·일지', '모든 회원', 107)]


def board_admin_boards():
    rows = [[f'<span class="mono muted">{i}</span>', f'<b>{n}</b>', chip(w, 'bronze' if w == '운영자만' else ''), f'<span class="mono">{c}</span>',
             f'<span style="display:flex;gap:4px">{ibtn("up", "위로")}{ibtn("arrow", "아래로")}{btn("이름 바꾸기", "sm")}{btn("삭제", "sm danger")}</span>'] for i, (n, w, c) in enumerate(BOARD_DEFS, 1)]
    top = (f'<div style="display:flex;gap:8px;align-items:center">{seg(["게시물", "게시판 관리"], "게시판 관리", "게시판 탭")}{btn("게시판 만들기", "primary", "copy", style="margin-left:auto")}</div>'
           f'<section class="panel">{tbl(["순서", "게시판", "쓰기", "글", ""], rows)}</section>'
           f'<span class="muted" style="font-size:11.5px">게시판 정의 · 관리 API는 계약판 G-01(서버 먼저). 게시물 목록은 지운 글을 보이지 않는다(G-02).</span>')
    moveto = ''.join(opt(n, f'글 {c}개', '', sel=(n == '건의'), h=48) for n, w, c in BOARD_DEFS if n not in ('자유',))
    dlg = dialog('「자유」 게시판 지우기', f'<div style="padding:14px 16px;display:flex;flex-direction:column;gap:10px">'
                 f'<span class="t2" style="font-size:13px">이 게시판에 글이 611개 있습니다. 글을 잃지 않게 옮길 게시판을 먼저 고르세요.</span>'
                 f'<div class="fld"><span class="lb">글을 옮길 게시판</span><div role="listbox" aria-label="글을 옮길 게시판" style="display:flex;flex-direction:column;border-top:1px solid #2c342f">{moveto}</div></div>'
                 f'{alert_box("자유 게시판의 글 611개를 건의로 옮기고 자유 게시판을 지웁니다.")}</div>',
                 f'{btn("취소")}{btn("옮기고 지우기", "danger")}', w=480, style='position:absolute;left:calc(50% - 240px);top:60px')
    page31('V31K5AdminBoards.dc.html', 'K5 P-G09 운영 콘솔 — 게시판 관리(지울 때 옮길 게시판 고르기)', console('게시판', top + f'<div class="scrim"></div>{dlg}'))


def board_madmin():
    tabs = ('<div role="group" aria-label="운영 콘솔 — 옆으로 밀어 보기" style="height:60px;flex-shrink:0;display:flex;gap:6px;padding:8px 12px;overflow-x:auto;border-bottom:1px solid #2c342f">'
            + ''.join(f'<button type="button" class="btn sm" aria-pressed="{"true" if t == "개요" else "false"}" style="flex-shrink:0;min-width:44px;{"background:#d3b064;color:#161410;border-color:#9c7f3f;font-weight:700" if t == "개요" else ""}">{t}</button>' for t, r in CONSOLE_TABS)
            + '</div>')
    cards = ''.join(f'<article class="panel" style="flex-shrink:0"><div style="padding:10px 12px;display:flex;flex-direction:column;gap:6px"><div style="display:flex;gap:6px;align-items:center">'
                    f'<b class="serif" style="font-size:16px">{n}</b>{chip(g, "bronze")}{chip(skew, "rust") if skew else ""}</div><span class="t2" style="font-size:12px">{sc} · engine <span class="mono">{e}</span></span>'
                    f'<div style="display:flex;gap:6px;align-items:center">{ {"run": chip("턴 도는 중", "moss"), "catch": chip("따라잡는 중 · 2배속", "bronze"), "fail": chip("조회 실패", "rust")}[st]}</div>'
                    f'{btn("게임 관리", "sm", "admin", href="#") if st != "fail" else btn("다시 시도", "sm", "refresh")}</div></article>' for n, g, sc, a, e, tag, st, skew in SERVERS)
    body = (f'{gw_mtop()}{tabs}<div style="flex-grow:1;overflow:hidden;display:flex;flex-direction:column;gap:10px;padding:10px 12px">'
            f'<div style="display:flex;gap:8px;align-items:center"><h1 class="serif" style="margin:0;font-size:19px;font-weight:900">개요</h1>{chip("위험 등급 · 조회")}</div>'
            f'<section class="panel"><div style="padding:10px 12px;display:flex;gap:8px;align-items:center;flex-wrap:wrap"><span class="mono muted">gateway</span><span class="mono">sha-3aa678b · 0.9.2</span>{chip("새 버전", "info")}</div></section>'
            f'{cards}</div>')
    page31('V31K5MAdmin.dc.html', 'K5 P-G09 운영 콘솔 — 모바일 개요(탭 칩 · sticky 아님)', body, w=MW, h=MH)


def board_madmin_members():
    cards = ''.join(f'<article class="panel" style="flex-shrink:0"><div style="padding:8px 12px;display:flex;gap:10px;align-items:center">{portrait("", nk if nk != "—" else "?", 32, 32)}'
                    f'<div style="display:flex;flex-direction:column;gap:2px;min-width:0;flex:1"><span style="display:flex;gap:6px;align-items:center"><b>{u}</b>{chip(st, "rust" if "차단" in st else ("bronze" if st == "운영자" else ""))}</span>'
                    f'<span class="muted" style="font-size:11.5px">{nk} · 가입 {j} · 최근 {l}</span></div>{btn("조치", "sm", "tools")}</div></article>' for i, u, e, st, nk, g, j, l, d in MEMBERS)
    act = sheet('spam01 조치', f'<div role="listbox" aria-label="조치" style="display:flex;flex-direction:column;border-top:1px solid #2c342f">{opt("임시 비밀번호 발급", "", "", h=52)}'
                f'{opt("차단", "일수를 정한다", "", h=52)}{opt("차단 풀기", "", "", sel=True, h=52)}{opt("이메일 영구 차단", "", "", h=52)}{opt("강제 탈퇴", "되돌릴 수 없다", "", h=52)}</div>',
                height=380)
    body = (f'{gw_mtop()}<div style="flex-grow:1;overflow:hidden;display:flex;flex-direction:column;gap:8px;padding:10px 12px">'
            f'<h1 class="serif" style="margin:0;font-size:19px;font-weight:900">회원</h1>{search("계정명 · 별명으로 찾기")}{cards}</div><div class="dim"></div>{act}')
    page31('V31K5MAdminMembers.dc.html', 'K5 P-G09 운영 콘솔 — 모바일 회원 카드 · 조치 시트', body, w=MW, h=MH)


BOARDS_GW2 = [board_board, board_mboard, board_post, board_mpost, board_write, board_mwrite, board_policy, board_mpolicy,
              board_admin, board_admin_server, board_admin_members, board_admin_turn, board_admin_boards, board_madmin, board_madmin_members]


# ================================================================== 입장 P-E01 ~ P-E04(입장 셸 — 레일 · 하단 탭 없음)
def entry_page(main, title_row=''):
    return entry_topbar() + title_row + f'<div style="flex-grow:1;display:flex;min-height:0;position:relative">{main}</div>'


def trow(title, sub='', right=''):
    return (f'<div style="height:52px;flex-shrink:0;display:flex;align-items:center;gap:12px;padding:0 16px;border-bottom:1px solid #2c342f">'
            f'<h2 class="serif" style="margin:0;font-size:20px;font-weight:900">{title}</h2><span class="t2" style="font-size:12.5px">{sub}</span>'
            f'<span style="margin-left:auto;display:flex;gap:6px">{right}</span></div>')


def apt(t, m, i, p, c):
    """역할 적성(장 · 리 · 사 · 사자) — v3 V3System 의 확정 가중 평균식과 같다."""
    return round(t * .6 + m * .4), round(p * .7 + i * .3), round(i * .8 + p * .2), round(c * .6 + p * .4)


# 영천 일대 지도(MAP['desk'] 16px/칸 1048×952)의 칸 → px(v31system.DESK_PX). s = 줄인 배율.
def dpx(name, s=1.0, ox=0, oy=0):
    x, y = DESK_PX(*CELLS[name])
    return round(x * s) - ox, round(y * s) - oy


EMAP_CITIES = ['양적현', '장사현', '영음현', '허현', '영양현', '신정현', '번창현', '임영현', '양성현', '언릉현']


def start_card(title, desc, action):
    return (f'<article class="panel" style="flex:1 1 0;min-width:0"><div style="padding:12px;display:flex;flex-direction:column;gap:8px;height:100%">'
            f'<h3 class="serif" style="margin:0;font-size:17px;font-weight:900">{title}</h3><span class="t2" style="font-size:12.5px;line-height:1.5">{desc}</span>'
            f'<div style="margin-top:auto">{action}</div></div></article>')


GROWTH = ['재야', '출사', '현령 · 부장', '태수 · 군단장', '봉신 주공 또는 독립', '군주']


def growth_path():
    return ('<div style="display:flex;gap:4px;flex-wrap:wrap;align-items:center">'
            + '<span class="muted">→</span>'.join(chip(g, 'bronze' if i == 0 else '') for i, g in enumerate(GROWTH)) + '</div>')


def summary_panel():
    return (f'<section class="panel">{sec("pep 1기", "군웅할거")}<div style="padding:10px 12px;display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:6px">'
            f'{kv("지금", "200년 3월 중순")}{kv("한 순", "10분")}{kv("세력", "5")}{kv("사람 장수", "24 / 30", "bz")}{kv("NPC", "412")}{kv("상태", "열림", "ms")}</div></section>')


def entry_map(w, h, s=0.84, ox=0, oy=0, sel=None):
    labs = ''
    for n in EMAP_CITIES:
        x, y = dpx(n, s, ox, oy)
        if 40 < x < w - 40 and 20 < y < h:
            labs += mlab(n, x, y - 12, big=(n == '허현'), dim=False)
    hx, hy = dpx('허현', s, ox, oy)
    cx = hx + 40 if hx < w - 140 else hx - 140
    cap = (f'<span style="position:absolute;left:{cx}px;top:{hy - 46}px;display:flex;gap:4px">{flag(NATION["조조"])}{chip("조조 · 수도", "bronze")}</span>'
           if 100 < hx < w and 60 < hy < h else '')
    return (f'<div style="position:relative;width:{w}px;height:{h}px;overflow:hidden;flex-shrink:0">{mapimg("desk", round(1048 * s), round(952 * s), "영천 일대 지도 — pep 1기 지금 판도", left=-ox, top=-oy)}'
            f'{labs}{cap}{map_ctrl("left:12px;bottom:12px", lod=True) if h >= 400 else map_ctrl("right:8px;top:8px")}</div>')


def board_mentry():
    body = (entry_mtop('pep 1기 — 시작하기') + f'<div style="flex-grow:1;overflow:hidden;display:flex;flex-direction:column;gap:10px">'
            f'{entry_map(MW, 250, s=0.5, ox=90, oy=40)}<div style="padding:0 12px;display:flex;flex-direction:column;gap:10px">'
            f'{btn("역사 인물 고르기", "primary", style="width:100%", href="#")}{btn("내 장수 만들기", "", style="width:100%", href="#")}'
            f'<span class="t2" style="font-size:12px;line-height:1.5">역사 인물은 그 사람의 자리(주공 · 중간직 · 소속 장수 · 예비 주공)로, 내 장수는 주공을 섬기거나 예비 주공으로 시작합니다.</span>'
            f'{slots_panel(mobile=True)}{summary_panel()}<section class="panel">{sec("출사할 곳 — 세력")}{nat_rows(h=44, n=3)}</section></div></div>')
    page31('V31K5MEntry.dc.html', 'K5 P-E01 게임 입구 — 모바일(역할 · 열린 자리)', body, w=MW, h=1240)


def board_entry_states():
    popx = pop('장수 만들기가 아직 열리지 않았습니다', '서버 준비 중 — 장수 생성 쓰기(K5-01)가 오면 바로 열립니다.', recovery='공지를 확인하세요.',  # 연습 월드 없음(D21) — 없는 기능을 가리키는 문구를 걷었다(D89)
               style='position:relative;width:100%')
    a = (f'<section class="panel" style="flex:1 1 0;min-width:0">{sec("난세 개막 — 세력 0", "빈 상태")}<div style="padding:12px;display:flex;flex-direction:column;gap:10px">'
         f'{alert_box("아직 세력이 없습니다. 예비 주공 3명이 거병을 기다립니다. 예비 주공으로 시작하거나, 거병한 주공에게 출사하거나, 스스로 거병할 수 있습니다.", "info")}'
         f'<div style="display:flex;gap:8px">{btn("역사 인물 고르기", "primary", style="flex:1")}{btn("내 장수 만들기", "", style="flex:1")}</div></div>'
         f'<section aria-label="거병을 기다리는 예비 주공">{sec("거병을 기다리는 예비 주공", "깃발 없음 · 거병하면 세력이 된다")}{pre_lord_rows()}</section></section>')
    b = (f'<section class="panel" style="flex:1 1 0;min-width:0">{sec("생성 대기 · 거절", "결과 확인")}<div style="padding:12px;display:flex;flex-direction:column;gap:10px">'
         f'{alert_box("장수를 만드는 중입니다 — 접수했습니다. 결과를 확인하는 중…", "info")}{state_loading(2)}'
         f'{alert_box("장수를 만들지 못했습니다 — 다른 계정이 먼저 이 인물을 선택했습니다.")}{btn("다시 고르기", "primary")}</div></section>')
    c = (f'<section class="panel" style="flex:1 1 0;min-width:0">{sec("서버 대기 — 생성 API 전", "K5-01")}<div style="padding:12px;display:flex;flex-direction:column;gap:10px">'
         f'{btn_off("내 장수 만들기", "아직 열리지 않았습니다", style="flex-direction:column;align-items:stretch")}{popx}'
         f'{btn_off("역사 인물 고르기", "아직 열리지 않았습니다", style="flex-direction:column;align-items:stretch")}'
         f'{compact_row("pep", "1기", chip("점검 중", "rust"), "점검 중입니다", "")}</div></section>')
    page31('V31K5EntryStates.dc.html', 'K5 P-E01 게임 입구 — 난세 개막 빈 · 생성 대기 · 거절 · 서버 대기', entry_page(f'<div style="flex:1;display:flex;gap:12px;padding:12px">{a}{b}{c}</div>'))


# ------------------------------------------------------------------ P-E02 장수 생성
STATS = [('통솔', 70), ('무력', 65), ('지력', 55), ('정치', 50), ('매력', 40)]  # 합 280 → 20 남음(예시)
IDEO = ['왕도', '패도', '아도', '할거', '명리', '예교']
# 개성 이름은 선택 정책 원장의 표시명(officer-catalog displayNameKo)을 그대로 받는다. RENOWN 「명성」은 이름 규칙이 막는 삼모 능력치가 아니라
# 원장 · 생성 계약이 정한 개성이라 그 칩만 검사에서 뺀다(K0 판정 2026-09-30). 대체어 여부는 계약판 §2i K5-COPY-01(C7 · C4 검토).
TRAIT = ['규율', '수전', '명성', '논객', '책사', '일기']
LEDGER_NAMES = {'명성'}
LINT_SKIP = ' data-lint="skip"'
HOME_OK = ['영양현', '장사현', '영음현', '번창현', '양성현']


def stat_rows(h=52):
    return ''.join(
        f'<div style="display:grid;grid-template-columns:44px 44px minmax(0,1fr) 44px 64px;gap:8px;align-items:center;height:{h}px;border-bottom:1px solid #2c342f">'
        f'<span class="serif" style="font-weight:700">{n}</span><button type="button" class="ibtn" aria-label="{n} 내리기">−</button>'
        f'<div class="g-bar" style="height:10px"><i style="width:{round((v - 20) / 65 * 100)}%"></i></div><button type="button" class="ibtn" aria-label="{n} 올리기">+</button>'
        f'<div class="inp mono" style="justify-content:center;padding:0" aria-label="{n} 값">{v}</div></div>' for n, v in STATS)


def pick_grid(label, items, on, help_=''):
    b = ''.join(f'<button type="button" aria-pressed="{"true" if t == on else "false"}" class="btn"{LINT_SKIP if t in LEDGER_NAMES else ""} style="{"background:#d3b064;color:#161410;border-color:#9c7f3f;font-weight:700" if t == on else ""}">{t}</button>' for t in items)
    note = '<!-- 개성 이름 = 선택 정책 원장 표시명(displayNameKo) 그대로. 명성은 원장 이름이라 검사 제외(K0 2026-09-30) -->' if set(items) & LEDGER_NAMES else ''
    return field(label, f'<div role="group" aria-label="{label}" style="display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:4px">{note}{b}</div>', help_)


def preview_card(name='[이름]', key='', home='허현', status='재야'):
    t, m, i, p, c = [v for _, v in STATS]
    a = apt(t, m, i, p, c)
    st = ''.join(f'<div style="display:flex;justify-content:space-between;font-size:12.5px;padding:4px 0;border-bottom:1px solid #2c342f"><span class="t2">{n}</span><span class="mono">{v}</span></div>' for n, v in STATS)
    face = portrait(key, name, 148, 210) if key else pic('', 148, 210, '계정 초상 — 없으면 기본 실루엣')
    return (f'<div style="padding:12px;display:grid;grid-template-columns:148px minmax(0,1fr);gap:12px">{face}'
            f'<div style="display:flex;flex-direction:column;gap:4px;min-width:0"><span class="serif" style="font-size:20px;font-weight:900">{name}</span>'
            f'<div style="display:flex;gap:4px;flex-wrap:wrap">{chip("향당 · " + home, "bronze")}{chip("주의 · 왕도")}{chip("개성 · 규율")}</div>{st}</div></div>'
            f'<div style="padding:0 12px 8px;display:flex;gap:4px;flex-wrap:wrap">{chip(f"장 {a[0]}", "bronze")}{chip(f"리 {a[1]}")}{chip(f"사 {a[2]}")}{chip(f"사자 {a[3]}")}'
            f'<span class="muted" style="font-size:11px;align-self:center">적성 — 능력에서 계산</span></div>'
            f'<div style="padding:0 12px;display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:6px">{kv("명망", "30", "bz")}{kv("신분", status)}{kv("시작", home + "의 성")}</div>'
            f'<div style="padding:8px 12px" class="muted"><span style="font-size:11.5px;line-height:1.5">본관이 같은 인물과 향당 결속이 생깁니다(효과 [미정]). 주의 · 개성은 지금은 표시용입니다.</span></div>')


def board_mcreate1():
    s = 0.62
    marks = ''
    for n in ['양적현', '장사현', '영음현', '허현', '영양현', '번창현', '양성현', '마피영']:
        x, y = dpx(n, s, 140, 40)
        if 20 < x < 370 and 60 < y < 430:
            kind = 'sel' if n == '허현' else ('no' if n == '마피영' else 'ok')
            marks += mk(x, y, kind, n, 'no' if kind == 'no' else '')
    mapx = (f'<div style="position:relative;flex:1;min-height:0;overflow:hidden">{mapimg("desk", round(1048 * s), round(952 * s), "영천 일대 지도 — 본관 현 고르기", left=-140, top=-40)}'
            f'{marks}{pick_bar("본관 현 고르기", "성이 있는 현만", mobile=True)}</div>')
    peek = (f'<section class="sheet" aria-label="본관 현 후보" style="position:absolute;left:0;right:0;bottom:64px;height:172px"><div class="grip"></div>'
            f'<div role="listbox" aria-label="본관 현 후보" style="display:flex;flex-direction:column">{cand_row("허현", "영천군 · 고름", "", "ok", sel=True, h=52)}{cand_row("장사현", "영천군", "", "ok", h=52)}</div></section>')
    body = (entry_mtop('장수 만들기', '입구') + step_bar5(STEPS5, 2) + f'<div style="flex-grow:1;display:flex;flex-direction:column;min-height:0;position:relative">{mapx}{peek}</div>'
            + step_foot('이전 — 역할', '다음 — 능력'))
    page31('V31K5MCreate1.dc.html', 'K5 P-E02 장수 생성 — 모바일 2 본관(지도 고르기 + 후보 시트)', body, w=MW, h=MH)


def board_mcreate2():
    body = (entry_mtop('장수 만들기', '입구') + step_bar5(STEPS5, 3) + f'<div style="flex-grow:1;overflow:hidden;display:flex;flex-direction:column;gap:8px;padding:10px 12px">'
            f'{help_strip("이름 · 다섯 능력을 정합니다.", draft=True)}{field("이름", inp("[이름]"))}<div>{stat_rows(56)}</div>'
            f'<span class="rs" style="font-size:12.5px;display:flex;gap:6px;align-items:center">{icon("alert", 16, "#e08a7c")}20점이 남았습니다</span>'
            f'<div style="display:flex;gap:8px;align-items:center"><span class="muted" style="font-size:11.5px">각 20–85 · 합 300</span>{btn("고르게", "sm", style="margin-left:auto")}</div></div>'
            + step_foot('이전 — 본관', '다음 — 주의 · 개성'))
    page31('V31K5MCreate2.dc.html', 'K5 P-E02 장수 생성 — 모바일 3 능력', body, w=MW, h=MH)


def board_mcreate4():
    body = (entry_mtop('장수 만들기', '입구') + step_bar5(STEPS5, 5) + f'<div style="flex-grow:1;overflow:hidden;display:flex;flex-direction:column">'
            f'<section class="panel" style="margin:10px 12px 0">{sec("미리보기", "유일 카드")}{preview_card(status="재야 → 출사")}</section></div>'
            + step_foot('이전', '만들고 섬길 주공 고르기', 'data-guide="tutorial.createGeneral"'))
    page31('V31K5MCreate4.dc.html', 'K5 P-E02 장수 생성 — 모바일 5 확인', body, w=MW, h=MH)


def board_create_pending():
    a = (f'<section class="panel" style="width:560px">{sec("접수했습니다", "202 · 결과 확인")}<div style="padding:16px;display:flex;flex-direction:column;gap:12px;align-items:center;text-align:center">'
         f'{pic("", 148, 210, "계정 초상")}<span class="serif" style="font-size:20px;font-weight:900">장수를 만드는 중입니다</span>'
         f'<span class="t2" style="font-size:13px;line-height:1.55">서버가 반영하면 곧바로 출사 화면으로 넘어갑니다. 이 창을 닫아도 로비나 입구에서 이어서 확인합니다.</span>{state_loading(1)}</div></section>')
    b = (f'<section class="panel" style="width:560px">{sec("만들지 못했습니다", "REJECTED — 서버 문장 그대로")}<div style="padding:16px;display:flex;flex-direction:column;gap:12px">'
         f'{alert_box("다른 계정이 먼저 이 인물을 선택했습니다.")}<span class="t2" style="font-size:12.5px">입력한 값은 남아 있습니다. 다른 인물을 고르거나 직접 만드세요.</span>'
         f'<div style="display:flex;gap:8px">{btn("다른 인물 고르기", "primary")}{btn("직접 만들기")}{btn("도움말 — 장수 만들기", "", "help", href="#")}</div>'
         f'{alert_box("이미 이 서버에 장수가 있습니다.")}{btn("작전실로", "", "war", href="#")}</div></section>')
    page31('V31K5CreatePending.dc.html', 'K5 P-E02 장수 생성 — 만드는 중 · 거절',
           entry_page(f'<div style="flex:1;display:flex;gap:16px;justify-content:center;align-items:flex-start;padding:24px">{a}{b}</div>'))


# ------------------------------------------------------------------ P-E03 역사 인물
# 인물 이름은 한글만 보인다(K0 2026-09-30). 한자 칸은 같은 읽기 인물이 한 화면에 함께 나올 때 twin() 으로만 쓴다 — 지금 목록엔 없다.
def board_mhistorical():
    lst = ''.join(hist_role_card(*h, mobile=True) for h in HIST_ROLES[:6])
    body = (entry_mtop('역사 인물 고르기', '입구') + f'<div style="flex-grow:1;overflow:hidden;display:flex;flex-direction:column;gap:8px;padding:10px 12px">'
            f'<div style="display:flex;gap:6px">{search("이름 · 본관", style="flex:1")}{btn("거르기", "", "list")}</div>'
            f'<span class="muted" style="font-size:11.5px">등장한 인물 1,000명 · 역할 넷 · 고를 수 있음만(거르기 시트에서 역할을 고른다)</span><div role="listbox" aria-label="역사 인물" style="display:flex;flex-direction:column;gap:6px">{lst}</div></div>')
    page31('V31K5MHistorical.dc.html', 'K5 P-E03 역사 인물 — 모바일 목록(역할 칩)', body, w=MW, h=MH)


# ------------------------------------------------------------------ P-E04 출사
LORDS = [('조조', 'jojo', '세력 · 주공 조조', '현 9 · 수도 허현', 'ok', ''), ('원소', '', '세력 · 주공 원소', '현 14 · 수도 업', 'no', '명망 수용량 부족'),
         ('유표', '', '세력 · 주공 유표', '현 8 · 수도 양양', 'ok', ''), ('손책', '', '세력 · 주공 손책', '현 6 · 수도 오', 'ok', ''), ('무작위 출사', '', '받아 주는 세력 가운데 하나', '', 'ok', '')]


def lord_rows(sel='조조', h=60):
    out = ''
    for n, k, sub, sub2, st, r in LORDS:
        nat = NATION.get(n, '#5a625c')
        lead = portrait(k, n, 30, 42) if n != '무작위 출사' else f'<span style="width:30px;height:42px;display:inline-flex;align-items:center;justify-content:center;border:1px dashed #5a625c">{icon("refresh", 16)}</span>'
        end = why_tag(r) if st == 'no' else (chip('고름', 'bronze') if n == sel else ok_chip())
        out += (f'<button type="button" role="option" class="opt" aria-selected="{"true" if n == sel else "false"}"{" aria-disabled=\"true\" aria-haspopup=\"dialog\"" if st == "no" else ""} style="min-height:{h}px">'
                f'{lead}<span style="display:flex;flex-direction:column;min-width:0;gap:2px"><span style="display:flex;align-items:center;gap:6px">{flag(nat) if n != "무작위 출사" else ""}<span class="nm">{n}</span></span>'
                f'<span class="sub">{sub}{" · " + sub2 if sub2 else ""}</span></span><span class="end">{end}</span></button>')
    return f'<div role="listbox" aria-label="섬길 주공" style="display:flex;flex-direction:column;border-top:1px solid #2c342f">{out}</div>'


def slot_field():
    return field('몇 번째 순에', f'<div style="display:flex;gap:6px">{inp("1순 · 3월 하순 21:40 — 가장 빠른 빈 순", ic="clock")}{btn("바꾸기", "sm")}</div>', '다음 개인 턴에 출사한다. 그때 조건을 다시 확인한다.')


def board_enlist():
    mapx = entry_map(760, 952, s=1.0, ox=250, oy=0)
    popx = pop('출사할 수 없습니다', '해당 주공의 명망 수용량이 부족합니다.', recovery='명망을 더 쌓거나 다른 주공을 고르세요.', help_topic='출사', style='position:absolute;left:24px;top:330px;width:360px')
    right = (f'<section class="panel" style="flex:1;min-width:0;position:relative">{sec("섬길 주공을 고른다", "출사 — 다음 개인 턴")}'
             f'<div style="padding:10px 12px 0;display:flex;flex-direction:column;gap:8px">{help_strip("섬길 주공을 골라 출사합니다. 부곡 · 부장이 함께 갑니다.", draft=True)}'
             f'{search("주공 · 세력 이름으로 찾기")}{seg([("세력", 4), ("장수", 0), ("무작위", 1)], "세력", "묶음")}</div>'
             f'<div style="margin-top:8px">{lord_rows()}</div><div style="padding:12px;display:flex;flex-direction:column;gap:10px">{slot_field()}'
             f'<div style="display:flex;gap:8px">{input_btn("출사 예약", "AVAILABLE", input_id="action.enlist", style="flex:2")}{btn("재야로 시작", "", style="flex:1", href="#")}</div></div>{popx}</section>')
    page31('V31K5Enlist.dc.html', 'K5 P-E04 출사 — 주공 고르기 · 불가 사유(데스크톱)',
           entry_page(f'<div style="flex:1;display:flex;min-width:0">{mapx}<div style="flex:1;display:flex;padding:12px;min-width:0">{right}</div></div>'))


def board_menlist():
    body = (entry_mtop('출사 — 주공 고르기', '입구') + f'<div style="flex-grow:1;overflow:hidden;display:flex;flex-direction:column">{entry_map(MW, 200, s=0.5, ox=240, oy=150)}'
            f'<div style="padding:8px 12px;display:flex;flex-direction:column;gap:8px">{help_strip("섬길 주공을 골라 출사합니다.", draft=True)}{seg([("세력", 4), ("장수", 0), ("무작위", 1)], "세력", "묶음")}</div>'
            f'{lord_rows(h=56)}</div>'
            f'<div style="height:64px;flex-shrink:0;display:flex;gap:8px;padding:10px 12px;background:#1b201d;border-top:1px solid #3d4740">'
            f'{input_btn("조조에게 출사 예약", "AVAILABLE", input_id="action.enlist", style="flex:2")}{btn("재야로", "", style="flex:1")}</div>')
    page31('V31K5MEnlist.dc.html', 'K5 P-E04 출사 — 모바일', body, w=MW, h=MH)


# ------------------------------------------------------------------ 입장 역할(D77–D84, 사용자 승인 D83 2026-10-03) — 요구: 메타 reports/opensamguk/tasks/2026-10-03-k5-entry-role-requirements.md
# D78 역사 인물(그 인물의 자리) + 새 장수(휘하 · 예비 주공). D79 예비 주공 · 묶인 인물. D80 새 장수 휘하는 재야로 만든 뒤 출사.
# D81 묶인 인물도 고를 수 있음. D82 중간직 칩은 실제 자리 이름(「중간직」은 거르기 이름). 숫자 · 한도는 서버 값(roles[].cap) — 보드는 예시.
# 화면 이름: 역할 「휘하」(D78 원문)는 09-26 용어 결정(휘하 → 부, 화면은 쉬운 말 「소속」)에 따라 「소속 장수」로 쓴다.
ROLE_HINT = {'주공': '세력을 이끌고 사람을 거느립니다', '중간직': '주공 밑에서 자기 부를 따로 가집니다', '소속 장수': '주공을 섬깁니다',
             '예비 주공': '거병하면 바로 주공이 됩니다', '재야': '아무 부에도 들지 않았습니다'}
RETAINER_NOTE = '먼저 재야로 만들고, 다음 화면에서 섬길 주공을 고릅니다. 다음 개인 턴에 그 주공의 부에 들어갑니다.'


def role_chip(t):
    return chip(t, 'bronze' if t in ('주공', '예비 주공') else ('info' if t not in ('소속 장수', '재야') else ''))


def slots_panel(mobile=False):
    """사람에게 열린 자리 — 사람 장수 수 + 길(역사 인물 · 새 장수)마다 역할별 열림 · 한도(서버 roles[].cap, 보드 숫자는 예시)."""
    hist = (f'{role_chip("주공")}<span class="mono t2">1 / 2</span>{role_chip("중간직")}<span class="mono t2">5</span>'
            f'{role_chip("소속 장수")}<span class="mono t2">41</span>{role_chip("예비 주공")}<span class="mono t2">3</span>')
    new = f'{role_chip("소속 장수")}<span class="mono t2">열림</span>{role_chip("예비 주공")}<span class="mono t2">1 / 2</span>'
    row = lambda label, body: (f'<div style="display:flex;flex-direction:column;gap:6px;padding:8px 12px;border-top:1px solid #2c342f">'
                               f'<span class="muted" style="font-size:11.5px">{label}</span><div style="display:flex;gap:6px;align-items:center;flex-wrap:wrap;font-size:12.5px">{body}</div></div>')
    return (f'<section class="panel" aria-label="사람에게 열린 자리">{sec("사람에게 열린 자리", "한도는 서버가 정한다")}'
            f'<div style="padding:8px 12px;display:flex;gap:8px;align-items:center;font-size:13px">사람 장수<b class="mono">24 / 30</b>'
            f'<div class="g-bar" style="height:8px;flex:1"><i style="width:80%"></i></div></div>'
            f'{row("역사 인물 — 남은 자리", hist)}{row("새 장수 — 고를 수 있는 역할", new)}'
            f'<span class="muted" data-lint="skip" style="font-size:11px;padding:0 12px 8px;display:block">숫자는 예시 — 한도는 서버 값(C5 숫자 뒤 사용자 결정, Q4)</span></section>')


def board_entry():
    right = (f'<div style="flex:1;min-width:0;display:flex;flex-direction:column;gap:10px;padding:12px;overflow:hidden">'
             f'<h1 class="serif" style="margin:0;font-size:22px;font-weight:900">이 서버에서 시작하기</h1>{summary_panel()}'
             f'<div style="display:flex;gap:10px;height:196px;flex-shrink:0">'
             f'{start_card("역사 인물로 시작", "시나리오에 나온 인물 한 명을 골라 그 사람의 자리(주공 · 중간직 · 소속 장수 · 예비 주공)로 들어갑니다. 서버에 한 장뿐, 먼저 고른 쪽이 가집니다.", btn("역사 인물 고르기", "primary", style="width:100%", href="#"))}'
             f'{start_card("내 장수를 만든다", "주공을 섬기거나, 예비 주공으로 서서 거병을 노립니다. 이름 · 본관 현 · 다섯 능력 · 주의 · 개성을 정합니다.", btn("내 장수 만들기", "", style="width:100%", href="#"))}</div>'
             f'{slots_panel()}'
             f'<section class="panel">{sec("출사할 곳 — 세력", "현 수 순")}{nat_rows(h=44, n=3)}</section></div>')
    main = entry_map(880, 1052, s=1.0, ox=84, oy=0) + right
    page31('V31K5Entry.dc.html', 'K5 P-E01 게임 입구 — 장수가 없을 때 · 역할로 들어가기 · 열린 자리(데스크톱)', entry_page(main), h=1100)


PRE_LORDS = [('', '유비', '묶인 인물 2 — 관우 · 장비', '탁군 탁현', False), ('', '[예비 주공]', '묶인 인물 [서버 값]', '[본관]', True), ('', '[예비 주공]', '묶인 인물 [서버 값]', '[본관]', False)]


def pre_lord_rows(h=56):
    out = ''
    for k, n, sub, home, human in PRE_LORDS:
        end = why_tag('사람이 고름') if human else chip('거병 전')
        out += (f'<div style="min-height:{h}px;display:flex;align-items:center;gap:10px;padding:4px 12px;border-bottom:1px solid #2c342f">{portrait(k, n, 30, 42)}'
                f'<span style="display:flex;flex-direction:column;gap:2px;min-width:0;flex:1"><span style="display:flex;gap:6px;align-items:center"><span class="serif" style="font-weight:700">{n}</span>{role_chip("예비 주공")}</span>'
                f'<span class="t2" style="font-size:12px">{sub} · 본관 {home}</span></span>{end}</div>')
    return out


# (초상 키, 이름, 역할 칩, 주인 줄, 본관, 상태, 고름)
HIST_ROLES = [('jojo', '조조', '주공', '조조 세력 · 군주', '패국 초현', 'ok', False),
              ('hahoudon', '하후돈', '소속 장수', '주공 조조', '패국 초현', 'ok', False),
              ('join', '조인', '태수 · [군]', '주공 조조 · 자기 부 [n]명', '패국 초현', 'ok', False),
              ('sunuk', '순욱', '소속 장수', '주공 조조', '영천군 영음현', 'taken', False),
              ('', '유비', '예비 주공', '묶인 인물 2 — 관우 · 장비', '탁군 탁현', 'ok', True),
              ('', '관우', '소속 장수', '유비 묶음 — 거병하면 유비 소속', '[본관]', 'ok', False),
              ('', '장비', '소속 장수', '유비 묶음 — 거병하면 유비 소속', '[본관]', 'ok', False),
              ('heojeo', '허저', '재야', '아무 데도 속하지 않음', '초국 초현', 'ok', False),
              ('', '원소', '주공', '원소 세력 · 군주', '여남군 여양현', 'taken', False)]


def hist_role_card(k, n, role, owner, home, st, sel, mobile=False):
    chipx = {'ok': chip('고를 수 있음', 'moss'), 'taken': why_tag('다른 사람이 먼저 골랐다')}[st]
    stats = ' · '.join(f'{x} {DASH}' for x in ['통', '무', '지', '정', '매'])
    dis = ' aria-disabled="true" aria-haspopup="dialog"' if st == 'taken' else ''
    return (f'<button type="button" role="option" aria-selected="{"true" if sel else "false"}"{dis} class="opt" style="min-height:{110 if mobile else 132}px;align-items:flex-start;padding:8px;'
            f'border:1px solid {"#d3b064" if sel else "#2c342f"};background:{"rgba(211,176,100,.08)" if sel else "#1b201d"}">{portrait(k, n, 64 if mobile else 74, 90 if mobile else 105)}'
            f'<span style="display:flex;flex-direction:column;gap:3px;min-width:0"><span class="nm">{n}</span>'
            f'<span style="display:flex;gap:4px;align-items:center;flex-wrap:wrap">{role_chip(role)}<span class="sub" style="white-space:normal">{owner}</span></span>'
            f'<span class="sub">본관 {home}</span><span class="mono muted" style="font-size:11px">{stats}</span><span>{chipx}</span></span></button>')


def board_historical():
    roles = ''.join(checkbox(f'{r}', r != '재야') for r in ['주공', '중간직', '소속 장수', '예비 주공', '재야'])
    flt = (f'<section class="panel" style="width:260px;flex-shrink:0">{sec("거르기")}<div style="padding:10px 12px;display:flex;flex-direction:column;gap:10px">'
           f'{search("이름 · 한자 · 본관")}{field("역할", roles, "중간직 = 주공 밑에서 자기 부를 가진 자리")}'
           f'{field("소속", seg(["전체", "조조", "원소", "유표"], "전체", "소속", vertical=True))}'
           f'{field("상태", checkbox("고를 수 있음", True) + checkbox("다른 사람이 고름") + checkbox("아직 등장 안 함"))}{field("정렬", seg(["이름", "통솔", "지력"], "이름", "정렬"))}</div></section>')
    grid = ''.join(hist_role_card(*h) for h in HIST_ROLES)
    center = (f'<section class="panel" style="flex:1;min-width:0">{sec("등장한 인물", "1,000명 중 9 · 더 보기")}<div role="listbox" aria-label="역사 인물" style="padding:10px;display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:8px">{grid}</div>'
              f'<div style="padding:0 10px 10px">{btn("더 보기", style="width:100%")}</div></section>')
    right = (f'<section class="panel" style="width:400px;flex-shrink:0">{sec("유비", "고름")}<div style="padding:10px 12px 0">{help_strip("시나리오에 나온 인물 한 명을 골라 그 사람의 자리로 들어갑니다.", draft=True)}</div>'
             f'<div style="padding:12px;display:grid;grid-template-columns:148px minmax(0,1fr);gap:12px">{portrait("", "유비", 148, 210)}<div style="display:flex;flex-direction:column;gap:4px">'
             f'<span style="display:flex;gap:4px;flex-wrap:wrap">{role_chip("예비 주공")}{chip("유일", "bronze")}</span>'
             + ''.join(f'<div style="display:flex;justify-content:space-between;font-size:12.5px;padding:4px 0;border-bottom:1px solid #2c342f"><span class="t2">{x}</span><span class="mono">{DASH}</span></div>' for x in ['통솔', '무력', '지력', '정치', '매력'])
             + f'</div></div><div style="padding:0 12px;display:flex;flex-direction:column;gap:6px">'
             f'{kv("들어갈 자리", "유비 묶음 · 예비 주공 — 거병하면 관우 · 장비와 함께 주공이 됩니다")}'
             f'{kv("함께 시작", "관우 · 장비(같은 현 재야) — 묶인 인물도 사람이 고를 수 있습니다")}'
             f'{kv("거병 조건", "명망 [미정] · 현 하나")}{kv("시작 위치", "본관 현 — 탁군 탁현")}{kv("자리", "사람 예비 주공 자리 1 / 2(숫자는 예시 — 한도는 서버 값)")}'
             f'<span class="muted" style="font-size:11.5px">능력 · 성향 · 결속은 역사 값 그대로(코에이 수치는 저장소 사본에서 「—」).</span></div>'
             f'<div style="padding:12px;margin-top:auto">{btn("이 인물로 시작", "primary", style="width:100%", attrs="data-guide=\"tutorial.createGeneral\"")}</div></section>')
    page31('V31K5Historical.dc.html', 'K5 P-E03 역사 인물 선택 — 역할 칩 · 역할 거르기 · 예비 주공 묶음(데스크톱)',
           entry_page(f'<div style="flex:1;display:flex;gap:12px;padding:12px;min-width:0">{flt}{center}{right}</div>'), h=1060)


def board_mhistorical_sheet():
    lst = ''.join(hist_role_card(*h, mobile=True) for h in HIST_ROLES[4:7])
    detail = sheet('관우', f'<div style="padding:0 16px;display:flex;flex-direction:column;gap:8px">{help_strip("고른 인물의 자리로 들어갑니다.", draft=True)}'
                   f'<div style="display:flex;gap:10px">{portrait("", "관우", 92, 130)}<div style="display:flex;flex-direction:column;gap:4px;flex:1">'
                   f'<span style="display:flex;gap:4px;flex-wrap:wrap">{role_chip("소속 장수")}{chip("유비 묶음")}</span>'
                   f'<span class="mono muted" style="font-size:12px">통 {DASH} · 무 {DASH} · 지 {DASH} · 정 {DASH} · 매 {DASH}</span>'
                   f'{kv("들어갈 자리", "유비 묶음 — 유비가 거병하면 바로 유비 소속이 됩니다")}{kv("시작 위치", "[본관] — 재야")}</div></div></div>',
                   top=300, foot=btn('이 인물로 시작', 'primary', style='flex:1', attrs='data-guide="tutorial.createGeneral"'))
    body = (entry_mtop('역사 인물 고르기', '입구') + f'<div style="flex-grow:1;overflow:hidden;display:flex;flex-direction:column;gap:6px;padding:10px 12px">{lst}</div>'
            f'<div class="dim"></div>{detail}')
    page31('V31K5MHistoricalSheet.dc.html', 'K5 P-E03 역사 인물 — 모바일 고른 인물 시트(묶인 인물 관우)', body, w=MW, h=MH)


def role_pick(on='소속 장수', mobile=False):
    h = 64 if mobile else 60
    a = opt('주공을 섬기며 시작', '재야로 만든 뒤 섬길 주공을 고릅니다 · 열림', chip('고름', 'bronze') if on == '소속 장수' else '', sel=(on == '소속 장수'), h=h)
    b = opt('예비 주공으로 시작', '본관 현에서 거병을 준비합니다 · 1 / 2 열림', chip('고름', 'bronze') if on == '예비 주공' else '', sel=(on == '예비 주공'), h=h)
    note = f'<span class="t2" style="font-size:12.5px;line-height:1.5;display:flex;gap:6px;align-items:flex-start">{icon("help", 16, "#7aa7c7")}<span>{RETAINER_NOTE}</span></span>' if on == '소속 장수' else ''
    return (f'<div style="display:flex;flex-direction:column;gap:6px"><div role="listbox" aria-label="시작할 역할" style="display:flex;flex-direction:column;border-top:1px solid #2c342f">{a}{b}</div>{note}</div>')


def board_create():
    """역할 칸(D83) + 09-30 승인본의 본관 고르기 · 점수 남음 상태(D83 보충 — 고를 수 없는 현 점선 · 고르게 · 20점 남음 줄 · 사유 팝업 · 주 · 군 · 현 찾기)."""
    s = 0.55
    marks = ''
    for n in EMAP_CITIES + ['마피영']:
        x, y = dpx(n, s)
        x = min(max(x, 24), 552)
        if not 22 <= y <= 524 - 22:  # 누를 영역 44가 지도 상자에 다 들어오는 표식만(K10 3.1.4 검사 — 신정현이 위 끝에서 2px 잘렸다)
            continue
        kind = 'sel' if n == '허현' else ('no' if n == '마피영' else 'ok')
        marks += mk(x, y, kind, n, 'no' if kind == 'no' else '')
    mapw = (f'<div style="position:relative;width:576px;height:524px;overflow:hidden;flex-shrink:0">{mapimg("desk", 576, 524, "영천 일대 지도 — 본관 현 고르기")}'
            f'{marks}{pick_bar("본관 현 고르기", "성이 있는 현만 · 고를 수 없는 곳은 점선", right=0)}{map_ctrl("right:12px;bottom:12px")}</div>')
    lst = ''.join(cand_row(n, '영천군', '', 'ok', '', sel=False, h=48) for n in HOME_OK[:3]) + cand_row('마피영', '영천군', '', 'no', '성이 없어 시작할 수 없음', h=48)
    left = (f'<section class="panel" style="width:600px;flex-shrink:0">{sec("본관 현", "지도에서 누르거나 목록에서 고른다")}<div style="padding:12px 12px 0">{mapw}</div>'
            f'<div style="padding:10px 12px;display:grid;grid-template-columns:1fr 1fr 1.4fr;gap:8px">{field("주", inp("예주"))}{field("군 · 국", inp("영천군"))}{field("현 찾기", inp("", "현 이름", ic="search"))}</div>'
            f'<div role="listbox" aria-label="본관 현 후보" style="display:flex;flex-direction:column;border-top:1px solid #2c342f">{cand_row("허현", "영천군 · 고름", "", "ok", sel=True, h=48)}{lst}</div></section>')
    mid = (f'<section class="panel" style="width:420px;flex-shrink:0">{sec("역할 · 이름 · 다섯 능력 · 주의 · 개성", "남은 점수 20 / 300")}'
           f'<div style="padding:10px 12px 0">{help_strip("역할을 고르고 이름 · 본관 · 다섯 능력 · 주의 · 개성을 정해 내 장수를 만듭니다.", draft=True)}</div>'
           f'<div style="padding:10px 12px;display:flex;flex-direction:column;gap:6px">{field("시작할 역할", role_pick())}{field("이름", inp("[이름]"), "이름 규칙은 서버가 정한다(nameRule)")}'
           f'<div>{stat_rows()}</div><div style="display:flex;gap:8px;align-items:center"><span class="rs" style="font-size:12.5px;display:flex;gap:6px;align-items:center">{icon("alert", 16, "#e08a7c")}20점이 남았습니다 — 합이 300이어야 합니다</span>'
           f'{btn("고르게", "sm", style="margin-left:auto")}</div><span class="muted" style="font-size:11.5px">각 능력은 20–85, 합계는 300입니다.</span>'
           f'{pick_grid("주의", IDEO, "왕도")}{pick_grid("개성", TRAIT, "규율", "지금은 표시용 — 효과는 설계 뒤에")}</div></section>')
    popx = pop('만들 수 없습니다', '20점이 남았습니다. 다섯 능력의 합이 300이어야 합니다.', recovery='남은 점수를 나누거나 「고르게」를 누르세요.', help_topic='장수 만들기',
               style='position:absolute;right:16px;bottom:84px;width:340px')
    right = (f'<section class="panel" style="flex:1;min-width:0;position:relative">{sec("미리보기", "유일 카드")}{preview_card(status="재야 → 출사")}'
             f'<div style="margin-top:auto;padding:12px;display:flex;flex-direction:column;gap:8px"><a href="#" style="font-size:12.5px;min-height:44px;display:inline-flex;align-items:center">역사 인물로 바꾸기</a>'
             f'<span style="display:flex;gap:6px"><button type="button" class="btn off" aria-disabled="true" aria-haspopup="dialog" data-guide="tutorial.createGeneral" style="flex:1">만들고 섬길 주공 고르기</button>'
             f'{why("20점 남음")}</span></div>{popx}</section>')
    page31('V31K5Create.dc.html', 'K5 P-E02 장수 생성 — 역할 · 본관 지도 고르기 · 점수 남음 사유(데스크톱)',
           entry_page(f'<div style="flex:1;display:flex;gap:12px;padding:12px;min-width:0">{left}{mid}{right}</div>'), h=1240)


def board_enlist_empty():
    right = (f'<section class="panel" style="flex:1;min-width:0">{sec("섬길 주공을 고른다", "난세 개막 — 주공 0")}'
             f'{state_empty("아직 거병한 주공이 없습니다", "예비 주공이 거병하면 출사할 수 있습니다. 그때까지 재야로 떠돌거나 스스로 거병합니다.", btn("재야로 시작", "primary", href="#"))}'
             f'<section aria-label="거병 전 예비 주공">{sec("거병 전", "거병하면 이 목록이 출사할 곳이 된다")}{pre_lord_rows()}</section></section>')
    page31('V31K5EnlistEmpty.dc.html', 'K5 P-E04 출사 — 주공 0(빈 상태 · 거병 전 예비 주공)',
           entry_page(f'<div style="flex:1;display:flex;min-width:0">{entry_map(760, 952, s=1.0, ox=250, oy=0)}<div style="flex:1;display:flex;padding:12px;min-width:0">{right}</div></div>'))


STEPS5 = ['역할', '본관', '능력', '주의 · 개성', '확인']


def step_bar5(steps, cur):
    """걸음 다섯(모바일 장수 만들기). 390 폭에서 한 칸이 약 70 이라 공용 step_bar(번호 · 이름 한 줄, 12)로는 「주의 · 개성」이 꺾인다.
    번호를 위에, 이름을 아래 한 줄(11.5 · 줄바꿈 없음)에 둔다. 공용 step_bar 는 그대로(다른 보드)."""
    out = ''
    for i, t in enumerate(steps, 1):
        st = 'done' if i < cur else ('now' if i == cur else 'todo')
        col = {'done': '#8fa77a', 'now': '#d3b064', 'todo': '#5a625c'}[st]
        mark = icon('check', 12, '#8fa77a') if st == 'done' else f'<span class="mono" style="font-size:11px;line-height:1">{i}</span>'
        out += (f'<button type="button" aria-current="{"step" if st == "now" else "false"}" {"aria-disabled=true" if st == "todo" else ""} '
                f'style="flex:1 1 0;min-width:0;height:44px;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:2px;font:inherit;background:transparent;border:0;'
                f'border-top:3px solid {col};color:{"#ece6d8" if st != "todo" else "#8e8879"};cursor:pointer;padding:0 2px">'
                f'{mark}<span style="font-size:11.5px;line-height:1.2;white-space:nowrap;letter-spacing:-0.01em;font-weight:{700 if st == "now" else 500}">{t}</span></button>')
    return f'<nav aria-label="걸음" style="display:flex;gap:4px;padding:8px 12px;border-bottom:1px solid #2c342f;flex-shrink:0">{out}</nav>'


def board_mcreate0():
    body = (entry_mtop('장수 만들기', '입구') + step_bar5(STEPS5, 1) + f'<div style="flex-grow:1;overflow:hidden;display:flex;flex-direction:column;gap:10px;padding:10px 12px">'
            f'{help_strip("어떤 자리로 시작할지 고릅니다.", draft=True)}{role_pick(mobile=True)}'
            f'</div>'
            + step_foot('입구로', '다음 — 본관'))
    page31('V31K5MCreate0.dc.html', 'K5 P-E02 장수 생성 — 모바일 1 역할', body, w=MW, h=MH)


BOARDS_ENTRY = [board_entry, board_mentry, board_entry_states, board_create, board_mcreate1, board_mcreate2, board_mcreate4, board_create_pending,
                board_historical, board_mhistorical, board_mhistorical_sheet, board_enlist, board_menlist, board_enlist_empty, board_mcreate0]


# ================================================================== 기록 P-H01 ~ P-H03(게임 셸 · 레일 「기록」)
REC_TABS = ['기록 5분류', '연감', '리플레이']
CATS5 = ['전체', '개인 행적', '부 · 세력', '조정 공문', '전장 보고', '천하 정세']
STRIPPED = chip('누구 · 어디 — 서버 준비 중', 'info')
EVENTS = [
    ('200년 3월 중순', '천하 정세', '허현의 소유 세력이 원소에서 조조로 바뀌었습니다.', '', True),
    ('200년 3월 중순', '조정 공문', '조조의 발령이 도착했습니다.', 'reply', False),
    ('200년 3월 중순', '전장 보고', '조우가 일어났습니다.', 'strip', False),
    ('200년 3월 중순', '개인 행적', '이번 순 행동이 반영됐습니다.', '', False),
    ('200년 3월 상순', '부 · 세력', '허저가 부에 합류했습니다.', '', False),
    ('200년 3월 상순', '개인 행적', '명망이 바뀌었습니다 — 조우 승리.', '', False),
    ('200년 3월 상순', '천하 정세', '200년 3월 월단평 결과가 발표됐습니다.', '', False),
    ('200년 3월 상순', '부 · 세력', '이번 달 세력 수입이 집계됐습니다.', '', False),
    ('200년 2월 하순', '전장 보고', '출병했습니다.', 'strip', False),
    ('200년 2월 하순', '천하 정세', '백마 보루를 원소가 차지했습니다.', '', False),
]


def rec_shell(main, tab='기록 5분류', band_html=''):
    return shell_desk('기록', 'records', f'<div style="flex:1;min-width:0;display:flex;flex-direction:column">{pagehead("기록", REC_TABS, tab)}{main}</div>', band_html)


def ev_action(kind, mobile=False):
    if kind == 'reply':
        return btn('응답하기', 'sm', href='#')
    if kind == 'strip':
        return STRIPPED
    if kind == 'replay':
        return btn('다시 보기', 'sm', href='#')
    return ''


def ev_rows(events, sel_first=False, h=52, mobile=False):
    out, last = '', None
    for d, c, t, a, sel in events:
        if d != last:
            out += (f'<div role="listitem" style="height:32px;display:flex;align-items:center;padding:0 12px;background:#141816;border-bottom:1px solid #2c342f">'
                    f'<span class="mono bz" style="font-size:12px">{d}</span></div>')
            last = d
        act = ev_action(a, mobile)
        # 줄 단추(고르면 상세) 안에 「응답하기」 · 「다시 보기」 링크를 넣지 않는다 — 누를 것 안의 누를 것(K10 3.1.4 검사). 줄 옆에 따로 둔다.
        hl = 'background:rgba(211,176,100,.10);box-shadow:inset 3px 0 0 #d3b064;' if sel else ''
        end = f'<span style="display:flex;align-items:center;padding:0 12px 0 4px;flex-shrink:0">{act}</span>' if act else ''
        out += (f'<div role="listitem" style="display:flex;align-items:stretch;border-bottom:1px solid #2c342f;{hl}">'
                f'<button type="button" class="opt" aria-current="{"true" if sel else "false"}" style="flex:1;min-width:0;min-height:{h}px;gap:10px;border-bottom:0">{cat(c)}'
                f'<span style="font-size:13px;min-width:0;white-space:{"normal" if mobile else "nowrap"};overflow:hidden;text-overflow:ellipsis;line-height:1.4">{t}</span></button>{end}</div>')
    return f'<div role="list" aria-label="기록" style="display:flex;flex-direction:column">{out}</div>'


def rec_filters(on='전체', mobile=False):
    tabs = seg(CATS5, on, '기록 분류')
    tools = (f'<div style="display:flex;gap:6px;align-items:center">{btn("종류 — 전체", "sm", "list")}{btn("인물 — 전체", "sm", "retinue")}{btn("날짜로 가기", "sm", "clock")}'
             f'<span class="muted" style="font-size:11.5px;margin-left:auto">계절 사건 종류 이름은 K8-EV 뒤</span></div>')
    return tabs, tools


def place_panel(w=568, mh=340):
    hx, hy = DESK_PX(*CELLS['허현'])
    ox, oy = hx - w // 2, hy - mh // 2
    labs = ''.join(mlab(n, DESK_PX(*CELLS[n])[0] - ox, DESK_PX(*CELLS[n])[1] - oy - 12, dim=False)
                   for n in ['영음현', '번창현', '언릉현', '임영현'] if 0 < DESK_PX(*CELLS[n])[0] - ox < w and 20 < DESK_PX(*CELLS[n])[1] - oy < mh)
    return (f'<div style="position:relative;width:{w}px;height:{mh}px;overflow:hidden;border-bottom:1px solid #2c342f">{mapimg("desk", 1048, 952, "허현 일대 지도", left=-ox, top=-oy)}'
            f'{labs}{mk(w // 2, mh // 2, "sel", "허현")}<span style="position:absolute;left:{w // 2 + 30}px;top:{mh // 2 - 46}px;display:flex;gap:4px">{flag(NATION["조조"])}{chip("조조 소유", "bronze")}</span>'
            f'{view_bar("현", "left:12px;bottom:12px", lod=False)}</div>')


def board_records():
    tabs, tools = rec_filters()
    left = (f'<section class="panel" style="width:760px;flex-shrink:0">{sec("기록", "시각순 · 50건씩")}<div style="padding:10px 12px;display:flex;flex-direction:column;gap:8px">{tabs}{tools}</div>'
            f'<button type="button" class="btn sm" style="margin:0 12px 8px;flex-shrink:0;border-color:#9c7f3f;color:#d3b064">새 기록 3건 — 맨 위로</button>'
            f'{ev_rows(EVENTS)}<div style="padding:8px 12px">{btn("더 보기", style="width:100%")}</div></section>')
    right = (f'<section class="panel" style="flex:1;min-width:0">{sec("고른 기록", "천하 정세 · 200년 3월 중순")}{place_panel()}'
             f'<div style="padding:10px 12px;display:flex;flex-direction:column;gap:8px"><span style="font-size:14px">허현의 소유 세력이 원소에서 조조로 바뀌었습니다.</span>'
             f'<div style="display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:6px">{kv("현", "허현 · 영천군")}{kv("이전", "원소")}{kv("지금", "조조", "bz")}</div>'
             f'<div style="display:flex;gap:8px">{btn("현 상세", "", "territory", href="#")}{btn("조조 세력", "", "court", href="#")}</div>'
             f'<span class="muted" style="font-size:11.5px">지명은 사건이 일어난 때의 이름으로 푼다(K5-07 이름 해석). 풀지 못하면 「어느 현」.</span></div></section>')
    page31('V31K5Records.dc.html', 'K5 P-H01 기록 5분류 — 전체 + 고른 기록 지도(데스크톱)',
           rec_shell(f'<div style="flex:1;display:flex;gap:12px;padding:12px;min-height:0">{left}{right}</div>'))


BATTLES = [('장사현 인근', '야전', '200년 3월 중순', '상대 후퇴 — 우리 승리', 'moss', 'ok'), ('허현', '성새전', '200년 3월 상순', '성문 부서짐 — 점령', 'moss', 'ok'),
           ('백마', '성새전', '200년 2월 하순', '결과 반영 전', 'info', 'wait'), ('영음현 인근', '일기토', '200년 2월 중순', '[결과]', '', 'ok')]


def battle_list(mobile=False):
    rows = ''
    for pl, k, d, res_, tone, st in BATTLES:
        act = btn('다시 보기', 'sm', href='#') if st == 'ok' else btn_off('다시 보기', '결과 반영 전')
        rows += (f'<div style="min-height:64px;display:flex;align-items:center;gap:10px;padding:6px 12px;border-bottom:1px solid #2c342f"><div style="display:flex;flex-direction:column;gap:3px;min-width:0;flex:1">'
                 f'<span style="display:flex;gap:6px;align-items:center"><b>{pl}</b>{chip(k)}{chip(res_, tone)}</span>'
                 f'<span class="muted" style="font-size:11.5px"><span class="mono">{d}</span> · 우리 −[값] · 상대 −[값]</span></div>{act}</div>')
    return (f'<section class="panel" style="flex:1;min-width:0">{sec("끝난 전투", "다시 보기 · 열린 전투는 군단 › 전투(K6)")}<div style="padding:10px 12px">{seg(["내 전투", "세력 전투"], "내 전투", "전투 범위")}</div>'
            f'{rows}</section>')


def board_records_battle():
    ev = [e for e in EVENTS if e[1] == '전장 보고'] + [('200년 2월 중순', '전장 보고', '보루 공성이 시작됐습니다.', 'strip', False)]
    tabs, tools = rec_filters('전장 보고')
    left = (f'<section class="panel" style="width:760px;flex-shrink:0">{sec("전장 보고", "BATTLE")}<div style="padding:10px 12px;display:flex;flex-direction:column;gap:8px">{tabs}{tools}'
            f'{alert_box("지금 서버가 전장 보고의 누구 · 어디 · 다시 보기 링크를 지워서 보냅니다. 참가자에게 돌려주도록 K5-07 보강을 요청했습니다.", "info")}</div>{ev_rows(ev)}</section>')
    page31('V31K5RecordsBattle.dc.html', 'K5 P-H01 기록 — 전장 보고 + 끝난 전투 목록',
           rec_shell(f'<div style="flex:1;display:flex;gap:12px;padding:12px;min-height:0">{left}{battle_list()}</div>'))


def board_mrecords():
    chips = ('<div role="group" aria-label="기록 분류 — 옆으로 밀어 보기" style="height:60px;flex-shrink:0;display:flex;gap:6px;padding:8px 12px;overflow-x:auto;border-bottom:1px solid #2c342f">'
             + ''.join(f'<button type="button" class="btn sm" aria-pressed="{"true" if t == "전체" else "false"}" style="flex-shrink:0;{"background:#d3b064;color:#161410;border-color:#9c7f3f;font-weight:700" if t == "전체" else ""}">{t}</button>' for t in CATS5)
             + '</div>')
    main = (f'<div style="flex-grow:1;overflow:hidden;display:flex;flex-direction:column">{mtabs_row(REC_TABS, "기록 5분류")}{chips}'
            f'<div style="display:flex;gap:6px;padding:8px 12px">{btn("거르기", "sm", "list")}{btn("날짜로 가기", "sm", "clock")}</div>{ev_rows(EVENTS[:7], h=60, mobile=True)}</div>')
    page31('V31K5MRecords.dc.html', 'K5 P-H01 기록 — 모바일', shell_mob(main, 'records'), w=MW, h=MH)


def board_mrecords_sheet():
    det = sheet('천하 정세 · 200년 3월 중순', f'{place_panel(MW, 200)}<div style="padding:10px 16px;display:flex;flex-direction:column;gap:8px"><span style="font-size:14px">허현의 소유 세력이 원소에서 조조로 바뀌었습니다.</span>'
                f'<div style="display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:6px">{kv("현", "허현")}{kv("이전", "원소")}{kv("지금", "조조", "bz")}</div></div>',
                top=209, bottom=64, foot=f'{btn("현 상세", "", "territory", style="flex:1", href="#")}{btn("조조 세력", "", "court", style="flex:1", href="#")}')
    main = f'<div style="flex-grow:1;overflow:hidden;display:flex;flex-direction:column">{mtabs_row(REC_TABS, "기록 5분류")}{ev_rows(EVENTS[:6], h=60, mobile=True)}</div>'
    body = mtop31() + main + '<div class="dim"></div>' + det + tabbar31('records')
    page31('V31K5MRecordsSheet.dc.html', 'K5 P-H01 기록 — 모바일 고른 기록 시트(지도)', body, w=MW, h=MH)


def board_records_empty():
    a = (f'<section class="panel" style="flex:1 1 0;min-width:0">{sec("부 · 세력", "재야")}'
         f'{state_empty("소속 세력이 없어 세력 소식이 없습니다", "출사하거나 거병하면 부와 세력의 일이 여기에 쌓입니다.", btn("출사할 주공 고르기", "primary", href="#"))}</section>')
    b = (f'<section class="panel" style="flex:1 1 0;min-width:0">{sec("기록 5분류", "옛 기록 방식 월드")}'
         f'{state_waiting("이 서버는 새 기록 방식 이전에 시작했습니다", "새 기록(종류 + 식별자)은 리셋한 뒤의 새 월드부터 쌓입니다. 이 서버에는 여기에 보일 기록이 없습니다.")}</section>')
    c = (f'<section class="panel" style="flex:1 1 0;min-width:0">{sec("천하 정세", "오류")}{state_error("기록을 불러오지 못했습니다")}</section>')
    page31('V31K5RecordsEmpty.dc.html', 'K5 P-H01 기록 — 재야 빈 · 옛 월드 서버 대기 · 오류',
           rec_shell(f'<div style="flex:1;display:flex;gap:12px;padding:12px;min-height:0">{a}{b}{c}</div>'))


# ------------------------------------------------------------------ P-H02 연감
PROV_LAB = [('예주', 511, 293), ('연주', 488, 230), ('사례', 383, 258), ('형주', 429, 383), ('양주', 625, 402), ('익주', 176, 410)]  # K0 1묶음 개정판 좌표 × 1.952
TERR = [('조조', NATION['조조'], 9, '허현'), ('원소', NATION['원소'], 14, '업'), ('유표', NATION['유표'], 8, '양양'), ('손책', '#c9a15a', 6, '오'), ('유비', '#a5744a', 1, '소패'), ('무주', '#5a625c', '[값]', '')]
YEAR_EV = [('12월 하순', '허현의 소유 세력이 원소에서 조조로 바뀌었습니다.'), ('10월 중순', '백마 보루를 원소가 차지했습니다.'), ('7월 상순', '200년 7월 월단평 결과가 발표됐습니다.'),
           ('3월 중순', '환현의 소유 세력이 유훈에서 손책으로 바뀌었습니다.')]


def year_bar():
    return (f'<div style="display:flex;gap:8px;align-items:center">{btn("199년", "sm", "prev")}'
            f'<div role="listbox" aria-label="연도" style="display:flex;gap:2px">{opt("200년", "발행됨", "", sel=True, h=44)}</div>'
            f'{btn_off("201년", "아직 이 해가 끝나지 않았습니다")}<span class="muted" style="font-size:11.5px;margin-left:8px">연감은 한 해가 끝날 때 한 번 나온다 · 달마다 일은 기록의 날짜로 가기</span></div>')


def terr_table(expand=True):
    rows = ''
    for n, c, k, cap in TERR:
        rows += (f'<button type="button" aria-expanded="{"true" if expand and n == "조조" else "false"}" style="width:100%;min-height:44px;display:grid;grid-template-columns:14px minmax(0,1fr) 72px 110px;gap:8px;align-items:center;'
                 f'padding:0 12px;background:none;border:0;border-bottom:1px solid #2c342f;color:#ece6d8;font:inherit;font-size:13px;text-align:left;cursor:pointer">{flag(c)}<span>{n}</span>'
                 f'<span class="mono" style="text-align:right">현 {k}</span><span>{chip("수도 " + cap, "bronze") if cap else ""}</span></button>')
        if expand and n == '조조':
            rows += (f'<div style="padding:8px 12px;display:flex;gap:4px;flex-wrap:wrap;background:#141816;border-bottom:1px solid #2c342f">{chip("허현 · 수도", "bronze")}'
                     + ''.join(chip(x) for x in ['양적현', '장사현', '영음현', '영양현', '번창현', '임영현', '양성현', '[현]']) + '</div>')
    return rows


def board_yearbook():
    labs = ''.join(mlab(n, x, y, big=True, dim=False) for n, x, y in PROV_LAB)
    mapx = (f'<div style="position:relative;width:820px;height:714px;overflow:hidden;border:1px solid #3d4740;flex-shrink:0">{mapimg("prov", 820, 714, "200년 말 판도 — 주 보기(국경 띠 · 깃발만)")}{labs}'
            f'<span class="chip" style="position:absolute;left:12px;top:12px;height:32px;background:rgba(20,24,22,.92)">200년 말 판도 · 주 보기</span>{view_bar("주", "left:12px;bottom:12px", lod=True)}</div>')
    ev = ''.join(f'<div style="min-height:44px;display:flex;gap:10px;align-items:center;padding:4px 12px;border-bottom:1px solid #2c342f;font-size:12.5px">{cat("천하 정세")}'
                 f'<span class="mono muted" style="white-space:nowrap;font-size:11.5px">{d}</span><span class="t2">{t}</span></div>' for d, t in YEAR_EV)
    right = (f'<div style="flex:1;min-width:0;display:flex;flex-direction:column;gap:10px"><section class="panel">{sec("연말 판도", "세력 5 · 소유 현 수 · 수도")}{terr_table()}</section>'
             f'<section class="panel" style="flex:1;min-height:0">{sec("그해 큰 사건", "공개 사건만")}<div style="padding:8px 12px">{seg(["전체", "조조", "원소", "유표"], "전체", "세력으로 거르기")}</div>{ev}</section></div>')
    main = (f'<div style="flex:1;display:flex;flex-direction:column;gap:10px;padding:12px;min-height:0">{year_bar()}'
            f'<div style="flex:1;display:flex;gap:12px;min-height:0">{mapx}{right}</div></div>')
    page31('V31K5Yearbook.dc.html', 'K5 P-H02 연감 — 200년(데스크톱)', rec_shell(main, '연감'))


def board_myearbook():
    labs = ''.join(mlab(n, round(x * 358 / 820), round(y * 280 / 714), dim=False) for n, x, y in PROV_LAB)
    main = (f'<div style="flex-grow:1;overflow:hidden;display:flex;flex-direction:column;gap:8px">{mtabs_row(REC_TABS, "연감")}'
            f'<div style="display:flex;gap:6px;align-items:center;padding:0 12px">{btn("199년", "sm", "prev")}<b class="serif" style="font-size:17px;flex:1;text-align:center">200년</b>{btn_off("201년", "아직 안 끝남")}</div>'
            f'<div style="position:relative;width:358px;height:280px;margin:0 16px;overflow:hidden;border:1px solid #3d4740">{mapimg("prov", 358, 280, "200년 말 판도")}{labs}</div>'
            f'<section class="panel" style="margin:0 12px">{sec("연말 판도")}{terr_table(False)}</section></div>')
    page31('V31K5MYearbook.dc.html', 'K5 P-H02 연감 — 모바일', shell_mob(main, 'records'), w=MW, h=MH)


def board_yearbook_empty():
    main = (f'<div style="flex:1;display:flex;flex-direction:column;gap:10px;padding:12px;min-height:0">'
            f'<div style="display:flex;gap:8px;align-items:center">{btn_off("199년", "첫 연감 전입니다")}{btn_off("200년", "아직 이 해가 끝나지 않았습니다")}</div>'
            f'<section class="panel" style="flex:1">{state_empty("첫 연감은 200년이 끝나면 나옵니다", "한 해가 끝날 때 그해 공개된 큰 사건과 연말 판도를 한 장으로 묶습니다. 그동안의 일은 기록에서 볼 수 있습니다.", btn("기록으로", "primary", "records", href="#"))}</section></div>')
    page31('V31K5YearbookEmpty.dc.html', 'K5 P-H02 연감 — 첫 해 전(빈)', rec_shell(main, '연감'))


# ------------------------------------------------------------------ P-H03 리플레이
SLOTS6 = ['선봉', '중앙', '좌익', '좌비', '우익', '우비']
OURS = [('heojeo', '허저', 'AI 대리 — 2:40부터'), ('hahoudon', '하후돈', '사람'), ('ijeon', '이전', 'AI'), ('', '', ''), ('join', '조인', 'AI'), ('', '', '')]
THEIRS = [('', '안량', 'AI'), ('', '[장수]', 'AI'), ('', '[장수]', 'AI'), ('', '[장수]', 'AI'), ('', '', ''), ('', '', '')]
BATTLE_EV = [('0:00', '개전 — 참가 대기 60초가 끝났다'), ('0:42', '선봉끼리 부딪혔다'), ('1:12', '우리 선봉 허저가 돌격'), ('1:32', '허저와 안량이 일기토'),
             ('2:40', '중앙 하후돈이 나가 AI가 부곡을 맡았다'), ('3:55', '상대가 후퇴했다'), ('4:10', '끝 — 우리 승리')]


# 시간 막대 사건 표식(전체 4:10 = 250초 기준 %) — BATTLE_EV 와 같은 사건.
RP_EVENTS = [(17, '#8fa77a', '부딪힘'), (29, '#d3b064', '돌격'), (37, '#d3b064', '일기토'), (64, '#7aa7c7', 'AI가 맡음'), (94, '#e08a7c', '후퇴')]
RP_NOW = '우리 선봉 허저와 상대 선봉 안량이 일기토'


def squad_rows(rows, side='우리'):
    out = ''
    for (k, n, ctl), slot in zip(rows, SLOTS6):
        if not n:
            out += (f'<div style="min-height:52px;display:flex;align-items:center;gap:8px;padding:0 10px;border-bottom:1px solid #2c342f"><span class="mono muted" style="width:32px;font-size:11px">{slot}</span>'
                    f'<span class="muted" style="font-size:12px">빈 자리</span></div>')
            continue
        tone = 'info' if ctl == '사람' else ('rust' if '대리' in ctl else '')
        out += (f'<div style="min-height:56px;display:flex;align-items:center;gap:8px;padding:4px 10px;border-bottom:1px solid #2c342f"><span class="mono muted" style="width:32px;font-size:11px">{slot}</span>'
                f'{portrait(k, n, 26, 36) if not n.startswith("[") else pic("", 26, 36, "초상")}<div style="display:flex;flex-direction:column;gap:3px;min-width:0;flex:1"><span style="display:flex;gap:4px;align-items:center"><b style="font-size:13px">{n}</b>{chip(ctl, tone)}</span>'
                f'<div class="g-bar" style="height:6px"><i style="width:60%"></i></div><span class="muted mono" style="font-size:10.5px">병력 [값] · 사기 [값]</span></div></div>')
    return out


def field_box(w, key='battle_field', label='장사현 인근 · 야전 · 200년 3월 중순'):
    """전장 판 — 원작 아이소 판 그대로(2026-09-30 사용자 결정: 실시간 전투 · 리플레이 모두 아이소). 분대 · 깃발 · 성문 상태는 실행 때 얹는다."""
    h = round(w * (544 if key == 'battle_field' else 524) / 1024)
    alt = '원작 전장 192판(야전 · 능선) — 아이소' if key == 'battle_field' else '원작 전장 040판(성새 · 성벽 · 성문) — 아이소'
    return (f'<div style="position:relative;width:{w}px;height:{h}px;flex-shrink:0;overflow:hidden;border:1px solid #3d4740">{mapimg(key, w, h, alt)}'
            f'<span class="chip" style="position:absolute;left:10px;top:10px;height:30px;background:rgba(20,24,22,.92)">{label}</span>'
            f'<span class="chip info" style="position:absolute;right:10px;top:10px;height:30px">분대 12 · 깃발은 실행 때 얹는다</span></div>')


def result_panel():
    return (f'<section class="panel" style="flex:1;min-height:0">{sec("결과", "상대 후퇴 — 우리 승리")}<div style="padding:10px 12px;display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:6px">'
            f'{kv("우리 사상", "[값]", "rs")}{kv("상대 사상", "[값]")}{kv("사기", "[값]")}{kv("퇴로", "상대 — 온 쪽 구역으로")}{kv("포로 · 부상 · 사망", "[값]")}{kv("점령", "없음(야전)")}</div>'
            f'<div style="padding:0 12px 10px"><button type="button" class="btn sm" aria-expanded="false" style="background:transparent">자세히 — 재생 확인값</button></div></section>')


def board_replay():
    left = f'<section class="panel" style="width:260px;flex-shrink:0">{sec("우리 — 조조", "6자리")}{squad_rows(OURS)}</section>'
    evs = ''.join(f'<button type="button" class="opt" style="min-height:44px;gap:8px"><span class="mono bz" style="font-size:11.5px;width:36px">{t}</span><span style="font-size:12.5px;white-space:normal">{e}</span></button>' for t, e in BATTLE_EV)
    right = (f'<div style="width:268px;flex-shrink:0;display:flex;flex-direction:column;gap:8px"><section class="panel">{sec("상대 — 원소", "6자리")}{squad_rows(THEIRS[:4], "상대")}</section>'
             f'<section class="panel" style="flex:1;min-height:0">{sec("사건", "누르면 그 순간으로")}<div role="listbox" aria-label="사건">{evs}</div></section></div>')
    head_actions = f'{chip("200년 3월 중순")}{chip("야전")}{chip("상대 후퇴 — 우리 승리", "moss")}'
    main = (f'<div style="flex:1;min-width:0;display:flex;flex-direction:column">{pagehead("다시 보기 — 장사현 인근 야전", None, None, head_actions)}'
            f'<div style="flex:1;display:flex;gap:12px;padding:12px 12px 0;min-height:0">{left}<div style="width:768px;flex-shrink:0;display:flex;flex-direction:column;gap:12px">{field_box(768)}{result_panel()}</div>{right}</div>'
            f'<div style="padding:0 12px 12px">{time_bar("replay", pos=37, now_text=RP_NOW, events=RP_EVENTS)}</div></div>')
    page31('V31K5Replay.dc.html', 'K5 P-H03 리플레이 — 전장 판 자리 · 6자리 · 사건 · 시간 막대', shell_desk('기록', 'records', main))


def board_mreplay():
    main = (f'<div style="flex-grow:1;overflow:hidden;display:flex;flex-direction:column">{field_box(390, label="장사현 인근 · 야전")}{time_bar("replay", mobile=True, pos=37, now_text=RP_NOW, events=RP_EVENTS)}'
            f'<div style="padding:8px 12px">{seg(["우리", "상대", "사건", "결과"], "우리", "보기")}</div>{squad_rows(OURS[:4])}</div>')
    page31('V31K5MReplay.dc.html', 'K5 P-H03 리플레이 — 모바일', mtop31('다시 보기', back='기록') + main + tabbar31('records'), w=MW, h=MH)


def board_replay_wait():
    main = (f'<div style="flex:1;min-width:0;display:flex;flex-direction:column">{pagehead("다시 보기 — 백마 성새전", None, None, chip("결과 반영 전", "info"))}'
            f'<div style="flex:1;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:12px"><div style="opacity:.45">{field_box(560, "battle_siege", "백마 · 성새전 — 진행 중")}</div>'
            f'{state_waiting("전투가 끝나고 결과가 반영되면 다시 볼 수 있습니다", "진행 중인 전투의 전체 다시 보기는 결과가 캠페인에 반영된 뒤에 공개합니다. 지금 전투에 참가할 수 있다면 군단 › 전투에서 들어가세요.")}'
            f'<div style="display:flex;gap:8px">{btn("군단 › 전투로(참가)", "primary", "corps", href="#")}{btn("기록으로", "", "records", href="#")}</div></div></div>')
    page31('V31K5ReplayWait.dc.html', 'K5 P-H03 리플레이 — 결과 반영 전(서버 대기)', shell_desk('기록', 'records', main))


# ================================================================== P-Q01 회의실 · 기밀실(19장 14 그대로 · 권한 원천만 교체)
ARTS = [('jojo', '조조', '조조 · 군주', '09.30 21:10', '공지', '허현으로 도읍을 옮긴 뒤의 일', '영천이 전장이 되었다. 창고를 영음현으로 모은다.', [('sunuk', '순욱', '영음현 물길로 쌀을 옮기기 쉽습니다.', '09.30 21:32')], None),
        ('heojeo', '허저', '조조 소속 장수', '09.30 22:17', '일반', '군단 쌀이 두 순 치뿐입니다', '양적현 창고에서 보내 주십시오.', [], None)]
SECRET_ARTS = [('jojo', '조조', '조조 · 군주', '09.30 23:40', '작전', '원소 본대의 남하 시점', '관도 방면 척후가 돌아왔다. 수뇌 밖으로 내지 말 것.', [('sunuk', '순욱', '하후돈은 영천을 비우지 마십시오.', '10.01 00:12')], (3, 5))]


def art_card(a, secret=False, open_readers=False):
    k, n, role, t, kind, title, body_, cm, rd = a
    cmx = ''.join(f'<div style="display:grid;grid-template-columns:28px minmax(0,1fr) auto;gap:8px;padding:6px 0;border-top:1px solid #2c342f">{portrait(ck, cn, 28, 40)}'
                  f'<span style="font-size:12.5px"><b>{cn}</b> <span class="t2">{cb}</span></span><span class="mono muted" style="font-size:11px">{ct}</span></div>' for ck, cn, cb, ct in cm)
    readers = ''
    if rd:
        readers = (f'<button type="button" class="btn sm" aria-haspopup="dialog" aria-expanded="{"true" if open_readers else "false"}" style="background:transparent;gap:6px">열람 {rd[0]} / {rd[1]}'
                   f'<span style="display:flex;gap:2px">{portrait("jojo", "조조", 18, 24)}{portrait("sunuk", "순욱", 18, 24)}{portrait("hahoudon", "하후돈", 18, 24)}</span></button>')
    return (f'<article class="panel" style="flex-shrink:0;{"border-color:#c96b5d" if secret else ""}"><div style="padding:10px 12px;display:flex;flex-direction:column;gap:6px">'
            f'<div style="display:flex;gap:8px;align-items:center">{portrait(k, n, 40, 56)}<div style="display:flex;flex-direction:column;gap:2px;min-width:0"><span style="display:flex;gap:6px;align-items:center">'
            f'<b>{n}</b><span class="muted" style="font-size:11.5px">{role}</span>{chip(kind, "bronze" if kind == "공지" else ("rust" if kind == "작전" else ""))}</span>'
            f'<span class="mono muted" style="font-size:11px">{t}</span></div><span style="margin-left:auto">{readers}</span></div>'
            f'<h3 class="serif" style="margin:0;font-size:16px;font-weight:900">{title}</h3><span class="t2" style="font-size:13px;line-height:1.6">{body_}</span>{cmx}'
            f'<div style="display:flex;gap:6px">{inp("", "댓글 달기 — 250자까지", style="flex:1")}{btn_off("등록", "댓글을 쓰세요")}</div></div></article>')


PARTS_Q = [('조조', '활동'), ('순욱', '활동'), ('하후돈', '활동'), ('허저', '침묵'), ('이전', '활동'), ('조인', '침묵')]


def council_rail(secret=False):
    if secret:
        top = (f'<section class="panel">{sec("기밀실 참여", "5명 · 열람 기록 남음")}<ul class="ul" style="padding:4px 12px">'
               + ''.join(f'<li style="display:flex;gap:8px;align-items:center;min-height:44px">{n}<span class="muted" style="font-size:11px">{r}</span></li>'
                         for n, r in [('조조', '군주'), ('순욱', '군주 지정'), ('하후돈', '군주 지정'), ('[봉신 주공]', '봉신 주공'), ('[인물]', '군주 지정')])
               + f'</ul><div style="padding:6px 12px 10px" class="muted"><span style="font-size:11.5px;line-height:1.5">자리가 바뀌면 곧바로 볼 수 없게 되고, 이전 열람 기록은 남습니다. 주소를 직접 쳐도 들어올 수 없습니다.</span>'
               f'<br><span style="display:inline-flex;margin-top:6px">{chip("권한 원천 — 사용자 승인 대기(Q-Q1)", "info")}</span></div></section>')
    else:
        top = (f'<section class="panel">{sec("회의실 참여", "최근 순 · NPC 제외")}<ul class="ul" style="padding:4px 12px">'
               + ''.join(f'<li style="display:flex;gap:8px;align-items:center;min-height:44px">{n}{chip(a, "moss" if a == "활동" else "")}</li>' for n, a in PARTS_Q)
               + '</ul><div style="padding:6px 12px 10px" class="t2"><span style="font-size:12px">활동 4 · 침묵 2</span></div></section>')
    return (f'<aside style="width:344px;flex-shrink:0;display:flex;flex-direction:column;gap:10px">{top}'
            f'<section class="panel">{sec("세 공간의 경계")}<ul class="ul" style="padding:4px 12px 8px"><li><b>커뮤니티</b> — 서버 밖, 모든 계정</li><li><b>회의실</b> — 게임 안, 같은 세력 장수</li>'
            f'<li><b>기밀실</b> — 게임 안, 기밀실 참여자만 · 열람 기록 남음</li></ul></section></aside>')


def council_head(room='회의실'):
    sub = '같은 세력 장수 11명' if room == '회의실' else '참여 5명 · 열람 기록 남음'
    return (f'<div style="display:flex;gap:8px;align-items:center;flex-wrap:wrap">{flag(NATION["조조"], 14, 20)}<b class="serif" style="font-size:17px">조조 · {room}</b>{chip(sub)}'
            f'{chip("내 자리 · 조조 소속 장수")}{chip("참여자만", "rust") if room == "기밀실" else ""}</div>'
            f'<div style="display:flex;gap:8px;align-items:center">{btn("새 글 쓰기", "primary", "copy")}{seg([("전체", 7 if room == "회의실" else 4), ("일반", 4), ("작전", 1), ("공지", 2)], "전체", "글 종류")}</div>')


def q_shell(main, room='회의실'):
    acts = f'{btn("새로고침", "sm", "refresh")}{btn("커뮤니티(서버 밖)", "sm", "plaza", href="#")}'
    return shell_desk('광장', 'plaza', f'<div style="flex:1;min-width:0;display:flex;flex-direction:column">{pagehead("회의실 · 기밀실", ["회의실", "기밀실"], room, acts)}{main}</div>')


def board_council():
    left = (f'<div style="flex:1;min-width:0;display:flex;flex-direction:column;gap:10px">{council_head()}{"".join(art_card(a) for a in ARTS)}</div>')
    page31('V31K5Council.dc.html', 'K5 P-Q01 회의실(데스크톱)', q_shell(f'<div style="flex:1;display:flex;gap:12px;padding:12px;min-height:0;overflow:hidden">{left}{council_rail()}</div>'))


def board_council_secret():
    popx = (f'<section class="panel" role="dialog" aria-label="열람한 사람" style="position:absolute;right:13px;top:157px;width:300px;z-index:5;background:#1b201d;border-color:#9c7f3f;box-shadow:0 10px 30px rgba(0,0,0,.55)">'
            f'{sec("열람한 사람", "3 / 5")}<ul class="ul" style="padding:4px 12px 8px">'
            + ''.join(f'<li style="display:flex;gap:8px;align-items:center;min-height:44px">{n}<span class="mono muted" style="font-size:11px;margin-left:auto">{t}</span></li>'
                      for n, t in [('조조', '09.30 23:40'), ('순욱', '10.01 00:05'), ('하후돈', '10.01 00:12')]) + '</ul></section>')
    left = (f'<div style="flex:1;min-width:0;display:flex;flex-direction:column;gap:10px;position:relative">{council_head("기밀실")}{art_card(SECRET_ARTS[0], True, True)}{popx}</div>')
    page31('V31K5CouncilSecret.dc.html', 'K5 P-Q01 기밀실 — 열람한 사람(누르면 이름 목록)',
           q_shell(f'<div style="flex:1;display:flex;gap:12px;padding:12px;min-height:0;overflow:hidden">{left}{council_rail(True)}</div>', '기밀실'))


def board_mcouncil():
    main = (f'<div style="flex-grow:1;overflow:hidden;display:flex;flex-direction:column;gap:8px;position:relative">{mtabs_row(["회의실", "기밀실"], "회의실")}'
            f'<div style="padding:0 12px;display:flex;flex-direction:column;gap:8px">{seg([("전체", 7), ("일반", 4), ("작전", 1), ("공지", 2)], "전체", "글 종류")}{art_card(ARTS[0])}</div>'
            f'<a href="#" class="btn primary" aria-label="새 글 쓰기" style="position:absolute;right:12px;bottom:12px;width:56px;height:56px;padding:0">{icon("copy", 22, "#161410")}</a></div>')
    page31('V31K5MCouncil.dc.html', 'K5 P-Q01 회의실 — 모바일', shell_mob(main, 'menu'), w=MW, h=MH)


def board_council_denied():
    main = (f'<div style="flex:1;display:flex;gap:12px;padding:12px;min-height:0"><section class="panel" style="flex:1">{sec("회의실", "재야")}'
            f'{state_denied("세력에 속하면 회의실을 쓸 수 있습니다", "출사하거나 거병해 세력에 속하면 같은 세력 장수들과 글을 나눌 수 있습니다.", "회의실")}</section>'
            f'<section class="panel" style="flex:1">{sec("기밀실", "참여자가 아님")}{state_denied("기밀실 참여자만 볼 수 있습니다", "군주가 기밀실 참여자로 지정하면 열립니다. 글 목록 · 쓰기 · 댓글이 모두 닫혀 있습니다.", "기밀실")}</section></div>')
    page31('V31K5CouncilDenied.dc.html', 'K5 P-Q01 회의실 · 기밀실 — 권한 없음', q_shell(main))


# ================================================================== P-A03 게임 관리
GA_TABS = ['세력 개요', '인물 조치', '인물 기록', '외교 관계', '서버 상태']


def ga_shell(main, on='인물 조치'):
    return shell_desk('게임 관리', 'admin', f'<div style="flex:1;min-width:0;display:flex;flex-direction:column">{pagehead("게임 관리 · pep 1기", GA_TABS, on)}{main}</div>')


def action_groups():
    g = lambda t, b, note='': (f'<section class="panel">{sec(t, note)}<div style="padding:10px 12px;display:flex;gap:6px;flex-wrap:wrap">{b}</div></section>')
    return (g('접속', f'{btn("접속 허용")}{btn("접속 제한")}{btn("모두 접속 허용", "danger")}{btn("모두 접속 제한", "danger")}', '전체 대상은 확인을 거친다')
            + g('차단', f'{btn("차단 풀기")}{btn("말하기 막기")}{btn("턴 막기")}{btn("3단계")}', '엔진 동작 확인 — C10(Q-A3)')
            + g('강제 사망', btn('강제 사망', 'danger'), '되돌릴 수 없다')
            + g('운영 알림 보내기', f'<div style="display:flex;gap:6px;width:100%">{inp("", "받는 인물에게 서신으로 — 255자까지", style="flex:1")}{btn("보내기", "primary")}</div>'))


def board_game_admin():
    left = f'<section class="panel" style="width:380px;flex-shrink:0">{sec("대상 인물", "여러 명")}{people_picker_multi(("허저", "이전"))}</section>'
    rows = [[f'<b>{n}</b>', chip('NPC'), '—', '조조 소속', '<span class="mono">21:40</span>', s1, s2] for n, s1, s2 in [('허저', '훈련', '빈 순'), ('이전', '징병', '훈련')]]
    right = (f'<div style="flex:1;min-width:0;display:flex;flex-direction:column;gap:10px">{action_groups()}'
             f'<section class="panel">{sec("고른 인물", "2명")}<div style="padding:4px 8px 8px">{tbl(["인물", "사람/NPC", "차단", "소속", "다음 개인 턴", "1순", "2순"], rows)}</div></section>'
             f'<span class="muted" data-lint="skip" style="font-size:11.5px">뺀 조치(삼모): 무한삭턴 · 숙련 10000 이벤트 · 하야입력 · 방랑해산 · 「NPC유저」 구분. 조치 API는 K5-13(서버 대기).</span></div>')
    page31('V31K5GameAdmin.dc.html', 'K5 P-A03 게임 관리 — 인물 조치', ga_shell(f'<div style="flex:1;display:flex;gap:12px;padding:12px;min-height:0;overflow:hidden">{left}{right}</div>'))


def board_game_admin_nations():
    hd = tbl(['세력', '현', '소속 인물', '수도 창고 금', '수도 창고 쌀', '병력', '호구'], [])
    main = (f'<div style="flex:1;display:flex;flex-direction:column;gap:10px;padding:12px;min-height:0"><section class="panel" style="flex:1">'
            f'{sec("세력 개요", "열 머리를 눌러 정렬")}<div style="padding:4px 8px">{hd}</div>'
            f'{state_waiting("세력 개요를 준비하고 있습니다", "운영자용 세력 읽기(K5-13)가 오면 이 표가 채워집니다.")}</section>'
            f'<span class="muted" style="font-size:11.5px">뺀 열(삼모): 국력 · 기술 · 전략 · 평금 · 평쌀 · 평통 · 평무 · 평지 · 평Lv · 보숙~차숙 · 농업 · 상업 · 치안 · 성벽 · 수비</span></div>')
    page31('V31K5GameAdminNations.dc.html', 'K5 P-A03 게임 관리 — 세력 개요(서버 대기)', ga_shell(main, '세력 개요'))


def board_mgame_admin():
    act = sheet('허저 · 이전 조치', f'<div role="listbox" aria-label="조치" style="display:flex;flex-direction:column;border-top:1px solid #2c342f">'
                f'{opt("접속 제한", "", "", h=52)}{opt("말하기 막기", "", "", h=52)}{opt("턴 막기", "", "", h=52)}{opt("운영 알림 보내기", "서신으로", "", h=52)}{opt("강제 사망", "되돌릴 수 없다", "", h=52)}</div>',
                height=380)
    main = (f'<div style="flex-grow:1;overflow:hidden;display:flex;flex-direction:column">{mtabs_row(GA_TABS, "인물 조치")}{people_picker_multi(("허저", "이전"), mobile=True)}</div>')
    body = mtop31('게임 관리', back='전체 메뉴') + main + '<div class="dim"></div>' + act + tabbar31('menu')
    page31('V31K5MGameAdmin.dc.html', 'K5 P-A03 게임 관리 — 모바일 인물 조치 시트', body, w=MW, h=MH)


BOARDS_GAME = [board_records, board_records_battle, board_mrecords, board_mrecords_sheet, board_records_empty,
               board_yearbook, board_myearbook, board_yearbook_empty, board_replay, board_mreplay, board_replay_wait,
               board_council, board_council_secret, board_mcouncil, board_council_denied,
               board_game_admin, board_game_admin_nations, board_mgame_admin]


BOARDS = [board_login, board_login_empty, board_mlogin, board_mlogin_scroll, board_join, board_mjoin,
          board_lobby, board_lobby_states, board_mlobby, board_account, board_maccount,
          board_lobby_open] + BOARDS_GW2 + BOARDS_ENTRY + BOARDS_GAME

if __name__ == '__main__':
    import glob
    import os as _os
    for f in glob.glob(_os.path.join(P, 'V31K5*.dc.html')):
        _os.remove(f)
    for b in BOARDS:
        b()
    print(f'ok K5 — {len(BOARDS)} boards')
