# 캔버스 v3 · 1묶음 — 셸 · 메뉴 · 작전실(데스크톱 · 태블릿 · 모바일) · 시스템 보강.  python3 boards_v3_shell.py
# 예시 상황은 기존 시안과 같다: 플레이어 = 하후돈(조조 휘하), 200년 3월 중순, 관도 · 영천 방면.
# 인물 능력치는 RTK14 추출본 값(로컬 캐시·V3_KOEI=1 일 때만, 저장소 사본은 「—」), 적성은 확정 가중 평균식(장 = 통×0.6+무×0.4 · 리 = 정×0.7+지×0.3 · 사 = 지×0.8+정×0.2 · 사자 = 매×0.6+정×0.4).
from v3common import *
from v3map import mapsvg, F

WR_VB = '-357 -92 1714 1124'

# ------------------------------------------------------------------ 12순
# (순, 시각, 행동, 대상, 상태, 경고, 효력 표식)
SLOTS = [
    ('3월 중순', '21:40', '농지개간', '장사현', '실행됨', '', ''),
    ('3월 하순', '22:40', '훈련', '하후돈 군단', '예약', '', ''),
    ('4월 상순', '23:40', '징병', '장사현 — 실행 때 성 밖이면 무효', '예약', 'warn', ''),
    ('4월 중순', '00:40', '', '', '빈 순', '', '배치 효력 시작'),
    ('4월 하순', '01:40', '등용', '인재탐색 결과 대기', '예약', '', ''),
    ('5월 상순', '02:40', '결의', '이전', '예약', '', ''),
    ('5월 중순', '03:40', '', '', '빈 순', '', ''), ('5월 하순', '04:40', '', '', '빈 순', '', ''),
    ('6월 상순', '05:40', '', '', '빈 순', '', ''), ('6월 중순', '06:40', '', '', '빈 순', '', ''),
    ('6월 하순', '07:40', '', '', '빈 순', '', ''), ('7월 상순', '08:40', '', '', '빈 순', '', ''),
]
ST = {'실행됨': 'moss', '예약': 'bronze', '무효': 'rust', '빈 순': ''}
NEXT = 1  # 다음에 실행될 순


def slotrow(i, s, h=56):
    d, t, c, tg, st, w, mark = s
    name = (f'<span class="serif" style="font-size:15px;font-weight:700;flex-shrink:0">{c}</span>' if c
            else '<span class="muted" style="font-size:13px;flex-shrink:0">빈 순</span>')
    tgt = (f'<span class="{"rs" if w else "muted"}" style="font-size:11px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;min-width:0">{tg}</span>'
           if tg else '')
    mk = f'<span class="chip info" style="height:18px;font-size:10px">{mark}</span>' if mark else ''
    right = f'<span class="chip {ST[st]}">{st}</span>' if c else '<a class="btn sm" href="#">+ 예약</a>'
    bg = 'background:rgba(211,176,100,.08);box-shadow:inset 3px 0 0 #d3b064;' if i == NEXT else ''
    return (f'<div style="height:{h}px;flex-shrink:0;display:grid;grid-template-columns:24px minmax(0,1fr) auto;gap:8px;align-items:center;padding:0 10px;border-bottom:1px solid #2c342f;{bg}">'
            f'<span class="mono muted" style="font-size:12px">{i + 1:02d}</span>'
            f'<div style="display:flex;flex-direction:column;gap:1px;min-width:0"><span class="mono t2" style="font-size:11px;white-space:nowrap">{d} · {t}</span>'
            f'<div style="display:flex;align-items:center;gap:8px;min-width:0">{name}{tgt}{mk}</div></div>{right}</div>')


def slotline(i, s, h=46):
    d, t, c, tg, st, w, mark = s
    name = f'<span class="serif" style="font-size:14px;font-weight:700">{c}</span>' if c else '<span class="muted" style="font-size:12px">빈 순</span>'
    right = f'<span class="chip {ST[st]}">{st}</span>' if c else '<a href="#" style="font-size:12px">+ 예약</a>'
    bg = 'background:rgba(211,176,100,.08);box-shadow:inset 3px 0 0 #d3b064;' if i == NEXT else ''
    return (f'<div style="height:{h}px;flex-shrink:0;display:grid;grid-template-columns:22px 94px minmax(0,1fr) auto;gap:6px;align-items:center;padding:0 8px;border-bottom:1px solid #2c342f;{bg}">'
            f'<span class="mono muted" style="font-size:11px">{i + 1:02d}</span><span class="mono t2" style="font-size:11px;white-space:nowrap">{d} {t}</span>{name}{right}</div>')


def standing(n, t, cls='bz'):
    return (f'<a href="#" style="flex:1 1 0;min-width:0;height:56px;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:2px;background:#141816;border:1px solid #2c342f;color:#ece6d8">'
            f'<span class="mono {cls}" style="font-size:17px;font-weight:700">{n}</span><span class="t2" style="font-size:10.5px;white-space:nowrap">{t}</span></a>')


STANDING = standing(3, '배치') + standing(4, '방침') + standing(2, '공사') + standing(1, '설치 계책') + standing(1, '발령 대기', 'rs')


# ------------------------------------------------------------------ 지난 순(개인 12순 · 세력 요약) — 기록 5분류 표식
def feed(c, st, title, txt, link=''):
    stc = f'<span class="chip {st[1]}">{st[0]}</span>' if st else ''
    return (f'<div style="padding:10px 12px;border-bottom:1px solid #2c342f;display:flex;flex-direction:column;gap:5px">'
            f'<div style="display:flex;align-items:center;gap:6px">{cat(c)}{stc}</div>'
            f'<span class="serif" style="font-size:13px;font-weight:700">{title}</span><span class="t2" style="font-size:12px;line-height:1.45">{txt}</span>{link}</div>')


FEED = (feed('개인 행적', ('실행됨', 'moss'), '농지개간 · 장사현', '3월 중순 21:40 — 농지개간을 마쳤습니다.')
        + feed('개인 행적', ('무효', 'rust'), '등용 · 3월 상순', '대상이 같은 구역에 없어 무효가 되었습니다. 비용은 들지 않았습니다.')
        + feed('전장 보고', None, '조우 전투 · 영천 북쪽 구릉', '아군이 물러났습니다.', '<a class="btn sm" href="#" style="align-self:flex-start">리플레이 보기</a>')
        + feed('전장 보고', None, '계책 「매복」 공개 · 관도 북쪽', '적 선봉이 조건에 걸렸습니다.')
        + feed('조정 공문', ('응답 대기', 'bronze'), '발령 도착 · 주공 조조', '관도 방면 군단장으로 발령했습니다.', '<a class="btn sm" href="#" style="align-self:flex-start">응답하기</a>'))


def tabs2(a, b):
    return (f'<div style="display:flex;gap:2px;padding:8px;border-bottom:1px solid #2c342f">'
            f'<button type="button" class="btn sm" style="flex:1;height:44px;background:#d3b064;color:#161410;border-color:#9c7f3f;font-weight:700" aria-pressed="true">{a}</button>'
            f'<button type="button" class="btn sm" style="flex:1;height:44px" aria-pressed="false">{b}</button></div>')


# ------------------------------------------------------------------ 범례(국가색은 유저가 고른 색 — 예시)
def swatch(c, n):
    return f'<span class="chip" style="gap:6px"><i style="width:10px;height:10px;display:inline-block;background:{c}"></i>{n}</span>'


LEGEND = (swatch(F['jo'], '조조') + swatch(F['won'], '원소') + swatch(F['etc'], '기타 세력') + swatch(F['none'], '무주')
          + '<span class="chip">빗금 = 미정찰</span>')

# ================================================================== 1. V3WarRoom — 데스크톱 1440
left = f'''<section class="panel" aria-label="지난 순" style="position:absolute;left:12px;top:12px;bottom:12px;width:300px;background:rgba(27,32,29,.96)">{sec('지난 순', '200년 3월 상순 – 중순')}
{tabs2('개인 12순', '세력 요약')}{FEED}
<a href="#" style="margin-top:auto;height:48px;display:flex;align-items:center;justify-content:center;border-top:1px solid #2c342f;font-size:13px">기록 전체 보기 →</a></section>'''

right = f'''<section class="panel" aria-label="명령 목록 12순" style="position:absolute;right:12px;top:12px;bottom:12px;width:312px;background:rgba(27,32,29,.96)">{sec('명령 목록 12순', '직접 행동 · 한 순에 하나')}
{''.join(slotrow(i, s) for i, s in enumerate(SLOTS))}
{sec('맡겨 둔 일', '순마다 굴러간다')}
<div style="padding:8px;display:flex;gap:4px">{STANDING}</div>
<div style="padding:8px;display:flex;gap:8px;margin-top:auto;border-top:1px solid #2c342f"><a class="btn primary" href="#" style="flex:1">이번 순에 할 일</a><button class="btn" type="button">당기기</button><button class="btn" type="button">밀기</button></div></section>'''
# 36 + 12*56 + 36 + 72 + 61 = 877 <= 920

target = f'''<section class="panel" aria-label="선택한 구역" style="position:absolute;left:324px;right:336px;bottom:12px;height:152px;background:rgba(27,32,29,.96);flex-direction:row">
<div style="flex:1 1 0;min-width:0;display:flex;flex-direction:column">{sec('선택한 ⟦PROV⟧ — 관도', '⟦PROV⟧ · 현 · 군')}
<div style="display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:6px;padding:8px">{kv('현 · 군', '중모현 · 하남윤')}{kv('지형', '평지 · 하수 남안')}{kv('보급 연결', '장사현 ← 연결됨', 'ms')}{kv('주둔 군단', '하후돈 군단', 'bz')}</div>
<div style="padding:0 8px;display:flex;gap:6px"><span class="chip">성 없음</span><span class="chip info">내 설치 계책 1</span><span class="chip rust">전선 — 하수 건너 적 군단</span></div></div>
<div style="width:292px;border-left:1px solid #2c342f;display:flex;flex-direction:column">{sec('내 위치', '하후돈')}
<div style="padding:8px 10px;display:flex;flex-direction:column;gap:6px"><div style="display:flex;gap:8px;align-items:center"><img class="pt" src="{PT['hahoudon']}" alt="하후돈 초상" style="width:34px;height:48px"><div style="display:flex;flex-direction:column;min-width:0"><span class="serif" style="font-weight:700">관도 · 군단과 함께</span><span class="muted" style="font-size:11px">귀환 성 — 장사현</span></div></div>
<div style="display:flex;align-items:center;justify-content:space-between;gap:6px;border:1px solid #c96b5d;background:rgba(201,107,93,.12);padding:4px 4px 4px 8px"><span class="rs" style="font-size:12px">성 밖 — 도시 행동 불가</span><button type="button" class="why" aria-haspopup="dialog">이유</button></div></div></div></section>'''

top = (f'<div style="position:absolute;left:324px;right:336px;top:12px;display:flex;align-items:center;gap:6px;flex-wrap:wrap">{LEGEND}'
       f'<button type="button" class="btn sm" style="margin-left:auto;height:44px">{icon("layers", 18)}레이어</button></div>')

stage = f'<main style="flex-grow:1;position:relative;min-width:0;overflow:hidden">{mapsvg(WR_VB, fs=15, uid="w")}{top}{left}{right}{target}</main>'
page3('V3WarRoom.dc.html', '작전실 — 데스크톱',
      topbar('작전실') + f'<div style="flex-grow:1;display:flex;min-height:0">{rail("war")}{stage}</div>')

# ================================================================== 2. V3WarRoomTablet — 태블릿 1024 × 768
rightT = f'''<section class="panel" aria-label="명령 목록 12순" style="position:absolute;right:12px;top:12px;bottom:12px;width:288px;background:rgba(27,32,29,.96)">{sec('명령 목록 12순', '한 순에 하나')}
{''.join(slotline(i, s) for i, s in enumerate(SLOTS))}
<a href="#" style="height:44px;flex-shrink:0;display:flex;align-items:center;justify-content:space-between;padding:0 10px;border-top:1px solid #2c342f;font-size:12px;color:#ece6d8"><span>맡겨 둔 일 <span class="bz">배치 3 · 방침 4 · 공사 2</span></span><span class="rs">발령 대기 1</span></a>
<div style="padding:8px;display:flex;gap:8px;border-top:1px solid #2c342f"><a class="btn primary" href="#" style="flex:1">이번 순에 할 일</a></div></section>'''
# 36 + 12*46 + 44 + 61 = 693 <= 692+1

targetT = f'''<section class="panel" aria-label="선택한 구역" style="position:absolute;left:12px;right:312px;bottom:12px;height:112px;background:rgba(27,32,29,.96)">{sec('선택한 ⟦PROV⟧ — 관도', '중모현 · 하남윤')}
<div style="display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:6px;padding:8px">{kv('지형', '평지 · 하수 남안')}{kv('보급 연결', '장사현 ← 연결됨', 'ms')}{kv('주둔 군단', '하후돈 군단', 'bz')}</div></section>'''

topT = (f'<div style="position:absolute;left:12px;right:312px;top:12px;display:flex;align-items:center;gap:6px;flex-wrap:wrap">'
        f'<button type="button" class="btn" aria-haspopup="dialog">지난 순 <span class="chip bronze">5</span></button>'
        f'{swatch(F["jo"], "조조")}{swatch(F["won"], "원소")}'
        f'<button type="button" class="btn sm" style="margin-left:auto;height:44px">{icon("layers", 18)}레이어</button></div>')

stageT = f'<main style="flex-grow:1;position:relative;min-width:0;overflow:hidden">{mapsvg(WR_VB, fs=14, uid="t")}{topT}{rightT}{targetT}</main>'
page3('V3WarRoomTablet.dc.html', '작전실 — 태블릿',
      topbar('작전실', h=52, compact=True) + f'<div style="flex-grow:1;display:flex;min-height:0">{rail("war", slim=True)}{stageT}</div>',
      w=1024, h=768)

# ================================================================== 3. V3MWarRoom — 모바일 390 × 844, 시트 접힘
MSTAGE_H = 844 - 56 - 64  # 724


def mfloat():
    return (f'<div style="position:absolute;left:12px;right:12px;top:12px;display:flex;justify-content:space-between">'
            f'<button type="button" class="btn sm" style="height:44px;background:rgba(20,24,22,.94)">{icon("legend", 18)}범례</button>'
            f'<button type="button" class="btn sm" style="height:44px;background:rgba(20,24,22,.94)">{icon("layers", 18)}레이어</button></div>')


pill = ('<button type="button" style="position:absolute;left:12px;right:12px;bottom:180px;height:44px;display:flex;align-items:center;gap:8px;padding:0 12px;'
        'font:inherit;font-size:13px;color:#ece6d8;background:rgba(27,32,29,.96);border:1px solid #3d4740;text-align:left">'
        '<span class="serif" style="font-weight:700">관도</span><span class="muted" style="font-size:12px">중모현 · 하수 남안</span>'
        '<span class="chip" style="margin-left:auto">성 없음</span></button>')

peek = f'''<section class="sheet" aria-label="명령 목록 12순" style="bottom:0;height:168px"><div class="grip"></div>
<div style="height:36px;display:flex;align-items:center;justify-content:space-between;padding:0 12px"><span class="serif" style="font-size:15px;font-weight:700">명령 목록 12순</span><span class="muted" style="font-size:11px">다음 개인 턴 21:40</span></div>
<div style="height:52px;display:grid;grid-template-columns:24px minmax(0,1fr) auto;gap:8px;align-items:center;padding:0 12px;background:rgba(211,176,100,.08);box-shadow:inset 3px 0 0 #d3b064"><span class="mono muted" style="font-size:12px">02</span><div style="display:flex;flex-direction:column;min-width:0"><span class="mono t2" style="font-size:11px">3월 하순 · 22:40</span><span><span class="serif" style="font-size:15px;font-weight:700">훈련</span> <span class="muted" style="font-size:11px">하후돈 군단</span></span></div><span class="chip bronze">예약</span></div>
<div style="padding:8px 12px;display:flex;gap:8px"><a class="btn primary" href="#" style="flex:1">이번 순에 할 일</a><button type="button" class="btn" aria-expanded="false">{icon("up", 18)}12순 열기</button></div></section>'''
# 16 + 36 + 52 + 60 = 164

mstage = f'<main style="height:{MSTAGE_H}px;flex-shrink:0;position:relative;overflow:hidden">{mapsvg("-60 -40 1000 1100", fs=22, uid="m", sub=False)}{mfloat()}{pill}{peek}</main>'
page3('V3MWarRoom.dc.html', '모바일 작전실 — 지도 전면', mtop() + mstage + tabbar('war'), w=390, h=844)

# ================================================================== 4. V3MSheet — 12순 시트 열림
full = f'''<section class="sheet" aria-label="명령 목록 12순" style="top:92px;bottom:0"><div class="grip"></div>
<div style="height:44px;flex-shrink:0;display:flex;align-items:center;justify-content:space-between;padding:0 4px 0 12px"><div style="display:flex;flex-direction:column"><span class="serif" style="font-size:15px;font-weight:700">명령 목록 12순</span><span class="muted" style="font-size:11px">직접 행동 · 한 순에 하나</span></div><button type="button" class="ibtn" aria-label="시트 접기">{icon("close")}</button></div>
{''.join(slotrow(i, s, 48) for i, s in enumerate(SLOTS[:8]))}
<div style="height:28px;flex-shrink:0;display:flex;align-items:center;justify-content:center;font-size:11px" class="muted">4순 더 — 밀어 올려 보기</div>
<div style="padding:4px 8px;display:flex;gap:4px">{STANDING}</div>
<div style="padding:8px 12px;display:flex;gap:8px;border-top:1px solid #2c342f"><a class="btn primary" href="#" style="flex:1">이번 순에 할 일</a><button class="btn" type="button">당기기</button><button class="btn" type="button">밀기</button></div></section>'''
# 16 + 44 + 8*48 + 28 + 64 + 61 = 597 <= 724-92 = 632

mstage2 = (f'<main style="height:{MSTAGE_H}px;flex-shrink:0;position:relative;overflow:hidden">{mapsvg("-60 -40 1000 1100", fs=22, uid="s", sub=False)}'
           f'<div style="position:absolute;inset:0;background:rgba(8,10,9,.55)"></div>{full}</main>')
page3('V3MSheet.dc.html', '모바일 작전실 — 12순 시트 열림', mtop() + mstage2 + tabbar('war'), w=390, h=844)


# ================================================================== 5. V3MReason — 비활성 입력과 사유 시트(조정)
def crow(name, sub, state, why=''):
    if state == 'on':
        act = f'<span class="chip moss">{why}</span>' if why else '<span class="chip moss">가능</span>'
        return (f'<a href="#" style="height:60px;display:flex;align-items:center;gap:10px;padding:0 12px;border-bottom:1px solid #2c342f;color:#ece6d8">'
                f'<div style="display:flex;flex-direction:column;min-width:0;flex:1"><span class="serif" style="font-size:15px;font-weight:700">{name}</span>'
                f'<span class="muted" style="font-size:11px">{sub}</span></div>{act}</a>')
    return (f'<div style="height:60px;display:flex;align-items:center;gap:10px;padding:0 12px;border-bottom:1px solid #2c342f">'
            f'<button type="button" aria-disabled="true" style="flex:1;min-width:0;height:48px;display:flex;flex-direction:column;justify-content:center;align-items:flex-start;padding:0 10px;font:inherit;text-align:left;color:#8a8477;background:transparent;border:1px dashed #5a625c;cursor:pointer">'
            f'<span class="serif" style="font-size:15px;font-weight:700">{name}</span><span style="font-size:11px">{sub}</span></button>'
            f'<button type="button" class="why" aria-haspopup="dialog">{why}</button></div>')


clist = (sec('조정 결정', '하후돈 · 조조를 섬기는 장수')
         + crow('발령 응답', '주공이 보낸 발령에 답한다', 'on', '응답 대기 1')
         + crow('정치 동의', '선양 · 결의 제안에 답한다', 'on')
         + crow('발령', '사람 장수를 자리에 보낸다', 'off', '권한 없음')
         + crow('포상 · 몰수', '봉록 · 상사', 'off', '권한 없음')
         + crow('천도 · 현 포기', '치소를 옮긴다 · 현을 버린다', 'off', '권한 없음')
         + crow('외교', '원조 · 불가침 · 선전포고 · 종전 · 파기', 'off', '준비 중'))

reason = f'''<div style="position:absolute;inset:0;background:rgba(8,10,9,.62)"></div>
<section class="sheet" role="dialog" aria-label="발령을 할 수 없는 이유" style="bottom:0;height:340px"><div class="grip"></div>
<div style="height:48px;flex-shrink:0;display:flex;align-items:center;justify-content:space-between;padding:0 4px 0 16px"><span class="serif" style="font-size:17px;font-weight:900">발령 — 지금은 할 수 없습니다</span><button type="button" class="ibtn" aria-label="닫기">{icon("close")}</button></div>
<div style="padding:4px 16px 12px;display:flex;flex-direction:column;gap:10px">
<div style="border:1px solid #c96b5d;background:rgba(201,107,93,.12);padding:10px 12px;display:flex;flex-direction:column;gap:4px"><span class="rs" style="font-size:14px;font-weight:700">발령은 주공만 할 수 있습니다.</span><span class="t2" style="font-size:12px">하후돈은 지금 조조를 섬기는 장수입니다.</span></div>
<div class="inset" style="padding:10px 12px;display:flex;flex-direction:column;gap:4px"><span class="bz" style="font-size:12px;font-weight:700">이렇게 하면 됩니다</span><span class="t2" style="font-size:12px;line-height:1.5">주공이 되려면 거병하거나 독립해야 합니다. 주공이 되면 휘하의 사람 장수에게 발령을 낼 수 있습니다.</span></div></div>
<div style="margin-top:auto;padding:8px 16px 12px;display:flex;gap:8px;border-top:1px solid #2c342f"><a class="btn" href="#" style="flex:1">{icon("help", 18)}도움말 — 발령</a><button type="button" class="btn primary" style="flex:1">확인</button></div></section>'''

mstage3 = f'<main style="height:{MSTAGE_H}px;flex-shrink:0;position:relative;overflow:hidden;display:flex;flex-direction:column">{clist}{reason}</main>'
page3('V3MReason.dc.html', '모바일 — 비활성 입력과 사유', mtop('조정', '전체 메뉴') + mstage3 + tabbar('menu'), w=390, h=844)

# ================================================================== 6. V3MMenu — 전체 메뉴
SHORT = {
    'retinue': ['편성 · 결속', '인물 일람', '월단평'], 'stratagem': ['계책 덱'],
    'territory': ['배치 · 방침 · 공사', '현 상세', '창고망'], 'corps': ['군단 · 작전', '공성', '전투 계획'],
    'court': ['발령 · 포상', '관직', '외교'], 'records': ['지난 순 · 기록', '연감', '리플레이'],
    'plaza': ['회의실', '기밀실', '서신', '커뮤니티'],
}


def mgroup(k, n, ic):
    items = ''.join(f'<a href="#" class="btn sm" style="height:44px;flex:1 1 auto">{t}</a>' for t in SHORT[k])
    return (f'<div style="display:grid;grid-template-columns:72px minmax(0,1fr);gap:8px;align-items:start;padding:8px 12px;border-bottom:1px solid #2c342f">'
            f'<div style="height:44px;display:flex;align-items:center;gap:6px" class="bz">{icon(ic, 18)}<span class="serif" style="font-size:14px;font-weight:700">{n}</span></div>'
            f'<div style="display:flex;flex-wrap:wrap;gap:6px">{items}</div></div>')


me = (f'<div style="display:flex;align-items:center;gap:10px;padding:10px 12px;border-bottom:1px solid #3d4740;background:#141816">'
      f'<img class="pt" src="{PT["hahoudon"]}" alt="하후돈 초상" style="width:40px;height:56px">'
      f'<div style="display:flex;flex-direction:column;min-width:0;flex:1"><span class="serif" style="font-size:16px;font-weight:900">하후돈</span>'
      f'<span class="muted" style="font-size:11px">조조 휘하 · 관도</span></div><span class="chip bronze">명망 [미정]</span></div>')
groups = ''.join(mgroup(k, n, ic) for k, n, ic, _ in NAV if k != 'war')
helprow = (f'<div style="display:flex;gap:6px;padding:8px 12px;border-bottom:1px solid #2c342f">'
           f'<a href="#" class="btn sm" style="height:44px;flex:1">{icon("help", 18)}도움말</a><a href="#" class="btn sm" style="height:44px;flex:1"><span class="chip info">첫걸음 3 / 8</span></a></div>')
foot = (f'<div style="margin-top:auto;display:flex;gap:6px;padding:8px 12px;border-top:1px solid #2c342f">'
        f'<a href="#" class="btn sm" style="height:44px;flex:1">{icon("lobby", 18)}로비로</a></div>')
mstage4 = f'<main style="height:{MSTAGE_H}px;flex-shrink:0;display:flex;flex-direction:column;overflow:hidden">{me}{groups}{helprow}{foot}</main>'
page3('V3MMenu.dc.html', '모바일 — 전체 메뉴', mtop() + mstage4 + tabbar('menu'), w=390, h=844)

# ================================================================== 7. V3Nav — 정보 구조
rows = ''
for k, n, ic, screens in NAV:
    for j, (s, new, old) in enumerate(screens):
        grp = (f'<span style="display:inline-flex;align-items:center;gap:6px" class="bz">{icon(ic, 16)}<span class="serif" style="font-weight:700">{n}</span></span>'
               if j == 0 else '')
        rows += (f'<tr><td style="width:120px">{grp}</td><td>{s}</td><td class="mono" style="color:#d3b064">{new}</td>'
                 f'<td class="t2" style="white-space:normal;line-height:1.35">{old}</td></tr>')
navtable = (f'<table class="table" style="font-size:12.5px"><thead><tr><th>묶음</th><th>화면</th><th>새 경로(서버 경로 아래)</th><th>지금 화면 → 308</th></tr></thead>'
            f'<tbody>{rows}</tbody></table>')

GONE = ['장수 선택 풀 · 빙의', '감찰부', '황제 · 황제 상세', '명예의 전당', 'NPC 일람', '접속 통계',
        '유산 · 경매 · 베팅 · 토너먼트', '설문 보상 · NPC 정책', '사령부 · 모의전투', '세력 정보 · 세력 장수 · 내 정보']
gone = ''.join(f'<li style="height:32px;display:flex;align-items:center;border-bottom:1px solid #2c342f" class="t2">{g}</li>' for g in GONE)
mtabs_demo = f'<div style="width:390px;border:1px solid #3d4740">{tabbar("war")}</div>'
navright = f'''<div style="width:420px;flex-shrink:0;display:flex;flex-direction:column;gap:12px">
<section class="panel">{sec('모바일 하단 탭', '나머지는 「전체」에서')}<div style="padding:12px;display:flex;flex-direction:column;gap:8px">{mtabs_demo}<span class="t2" style="font-size:12px">작전실 · 휘하 · 계책 · 기록 · 전체</span></div></section>
<section class="panel">{sec('태블릿 · 데스크톱', '왼쪽 레일')}<div style="padding:12px;display:flex;gap:12px;align-items:flex-start"><div style="height:300px;overflow:hidden;border:1px solid #3d4740">{rail("war", slim=True)}</div><span class="t2" style="font-size:12px;line-height:1.6">레일 = 작전실 + 6묶음 + 광장<br>아래쪽 = 도움말 · 관리(권한자만)<br>태블릿은 좁은 레일</span></div></section>
<section class="panel">{sec('지우는 화면', '대체 없음 · 옛 경로는 가까운 화면으로 308 또는 404')}<ul style="margin:0;padding:4px 12px 8px;list-style:none;font-size:12.5px">{gone}</ul></section></div>'''
navbody = (f'<div style="flex-grow:1;display:flex;gap:12px;padding:12px;min-height:0">'
           f'<section class="panel" style="flex:1 1 0;min-width:0">{sec("메뉴 한 벌 — 작전실 · 휘하 · 계책 · 영지 · 군단 · 조정 · 기록 · 광장", "옛 경로는 308 전용")}<div style="padding:8px;overflow:hidden">{navtable}</div></section>{navright}</div>')
page3('V3Nav.dc.html', '정보 구조 — 메뉴 한 벌', topbar('정보 구조') + navbody)


# ================================================================== 8. V3System — 시스템 보강
def frame(label, rng, w, h, inner):
    return (f'<div style="display:flex;flex-direction:column;gap:6px;align-items:flex-start"><span class="serif" style="font-size:13px;font-weight:700">{label} <span class="mono muted" style="font-size:11px;font-weight:500">{rng}</span></span>'
            f'<div style="width:{w}px;height:{h}px;border:1px solid #3d4740;background:#101412;position:relative;overflow:hidden">{inner}</div></div>')


def blk(style, txt, c='#2c342f'):
    return f'<div style="position:absolute;{style};background:{c};display:flex;align-items:center;justify-content:center;font-size:9px;color:#b9b2a3;text-align:center">{txt}</div>'


bp = ('<div style="display:flex;gap:16px;align-items:flex-end;padding:12px">'
      + frame('모바일', '< 768', 90, 190, blk('left:0;right:0;top:0;height:14px', '') + blk('left:0;right:0;top:14px;bottom:62px', '지도', '#1b2a22')
              + blk('left:0;right:0;bottom:16px;height:46px', '12순 시트', '#3a3526') + blk('left:0;right:0;bottom:0;height:16px', '탭'))
      + frame('태블릿', '768 – 1199', 150, 110, blk('left:0;right:0;top:0;height:10px', '') + blk('left:0;top:10px;bottom:0;width:10px', '')
              + blk('left:10px;right:44px;top:10px;bottom:0', '지도', '#1b2a22') + blk('right:0;top:10px;bottom:0;width:44px', '12순', '#3a3526'))
      + frame('데스크톱', '≥ 1200', 200, 140, blk('left:0;right:0;top:0;height:10px', '') + blk('left:0;top:10px;bottom:0;width:12px', '')
              + blk('left:12px;right:0;top:10px;bottom:0', '지도', '#1b2a22') + blk('left:16px;top:14px;bottom:4px;width:40px', '지난 순', '#2c342f')
              + blk('right:4px;top:14px;bottom:4px;width:44px', '12순', '#3a3526') + blk('left:60px;right:52px;bottom:4px;height:24px', '선택 ⟦PROV⟧', '#2c342f'))
      + '</div>')

dis = f'''<div style="padding:12px;display:flex;flex-direction:column;gap:10px">
<div style="display:flex;gap:8px;align-items:center"><button type="button" class="btn primary">02 순에 예약</button><button type="button" class="btn off" aria-disabled="true">발령</button><button type="button" class="why" aria-haspopup="dialog">권한 없음</button></div>
<div style="position:relative;width:360px;border:1px solid #9c7f3f;background:#1b201d;box-shadow:0 10px 30px rgba(0,0,0,.5);padding:10px 12px;display:flex;flex-direction:column;gap:6px">
<span class="rs" style="font-size:13px;font-weight:700">발령은 주공만 할 수 있습니다.</span><span class="t2" style="font-size:12px">주공이 되려면 거병하거나 독립해야 합니다.</span><a href="#" style="font-size:12px">도움말 — 발령 →</a></div>
<ul style="margin:0;padding-left:16px;font-size:12px;line-height:1.7" class="t2"><li>비활성도 누를 수 있다 — 누르면 사유가 열린다(데스크톱 = 말풍선, 모바일 = 하단 시트)</li><li>흐리게 하지 않는다 — 점선 테두리 + 사유 버튼</li><li>사유 문구 = 입력 원장의 실패 사유 · 회복 방법은 도움말</li></ul></div>'''


def sw(c, n, d):
    return (f'<div style="display:flex;align-items:center;gap:8px;height:32px"><i style="width:22px;height:22px;display:inline-block;background:{c};border:1px solid #0c0f0e"></i>'
            f'<span style="font-size:12.5px;font-weight:700;width:44px">{n}</span><span class="muted" style="font-size:11.5px">{d}</span></div>')


colors = (f'<div style="padding:10px 12px;display:grid;grid-template-columns:1fr 1fr;gap:4px 16px">'
          f'<span class="muted" style="font-size:11px;grid-column:1">의미색</span><span class="muted" style="font-size:11px;grid-column:2">자원색 — 늘 글자와 함께</span>'
          + sw('#d3b064', '청동', '지위 · 주 행동') + f'<div style="display:flex;align-items:center;height:32px">{res("금")}</div>'
          + sw('#8fa77a', '이끼', '가능 · 양호') + f'<div style="display:flex;align-items:center;height:32px">{res("쌀")}</div>'
          + sw('#e08a7c', '적갈', '경고 · 위험') + f'<div style="display:flex;align-items:center;height:32px">{res("철")}</div>'
          + sw('#7aa7c7', '청', '중립 상태') + f'<div style="display:flex;align-items:center;height:32px">{res("목재")}</div>'
          + f'<span class="muted" style="font-size:11px">국가색 — 유저가 고른다(예시)</span><div style="display:flex;align-items:center;height:32px">{res("말")}</div>'
          + f'<div style="display:flex;gap:6px">{swatch(F["jo"], "조조")}{swatch(F["won"], "원소")}</div>'
          + '</div>'
          + f'<div style="padding:4px 12px 10px;display:flex;flex-direction:column;gap:6px"><span class="muted" style="font-size:11px">기록 5분류</span>'
          + f'<div style="display:flex;flex-wrap:wrap;gap:6px">{"".join(cat(c) for c in CATS)}</div></div>')

# 표 → 카드(인물 일람). 능력치 = RTK14, 적성 = 확정식.
# 코에이(RTK14) 수치는 커밋하지 않는다(IP 규칙). 로컬 캐시가 있고 V3_KOEI=1 일 때만 실제 값을 쓰고, 아니면 「—」로 그린다.
import json as _json, os as _os
_CACHE = _os.path.expanduser('~/.cache/rtk14-wikiwiki/extracted/officer-data.json')
_KOEI = {}
if _os.environ.get('V3_KOEI') == '1' and _os.path.exists(_CACHE):
    _KOEI = {r['name_kanji']: [r['leadership'], r['strength'], r['intelligence'], r['politics'], r['charm']] for r in _json.load(open(_CACHE, encoding='utf-8'))}
PEOPLE = [(n, pt, *(_KOEI.get(k) or [None] * 5)) for n, pt, k in [('허저', 'heojeo', '許褚'), ('이전', 'ijeon', '李典')]]


def apt(t, m, i, p, c):
    if t is None:
        return ('—',) * 4
    return (round(t * .6 + m * .4), round(p * .7 + i * .3), round(i * .8 + p * .2), round(c * .6 + p * .4))


def nv(v):
    return '—' if v is None else v


trs = ''
for n, ptk, t, m, i, p, c in PEOPLE:
    a = apt(t, m, i, p, c)
    trs += (f'<tr><td><span style="display:inline-flex;align-items:center;gap:6px"><img class="pt" src="{PT[ptk]}" alt="{n} 초상" style="width:24px;height:34px">{n}</span></td>'
            + ''.join(f'<td class="mono">{nv(v)}</td>' for v in (t, m, i, p, c))
            + f'<td class="mono bz">장 {a[0]} · 리 {a[1]} · 사 {a[2]} · 사자 {a[3]}</td></tr>')
tbl = (f'<table class="table" style="font-size:12px"><thead><tr><th>인물</th><th>통</th><th>무</th><th>지</th><th>정</th><th>매</th><th>적성</th></tr></thead><tbody>{trs}</tbody></table>')
n, ptk, t, m, i, p, c = PEOPLE[0]
a = apt(t, m, i, p, c)
card = (f'<div style="width:300px;border:1px solid #3d4740;background:#141816;padding:10px;display:flex;gap:10px">'
        f'<img class="pt" src="{PT[ptk]}" alt="{n} 초상" style="width:44px;height:62px"><div style="display:flex;flex-direction:column;gap:4px;min-width:0;flex:1">'
        f'<span class="serif" style="font-size:15px;font-weight:700">{n}</span>'
        f'<span class="mono t2" style="font-size:11.5px">통 {nv(t)} · 무 {nv(m)} · 지 {nv(i)} · 정 {nv(p)} · 매 {nv(c)}</span>'
        f'<div style="display:flex;flex-wrap:wrap;gap:4px"><span class="chip bronze">장 {a[0]}</span><span class="chip">리 {a[1]}</span><span class="chip">사 {a[2]}</span><span class="chip">사자 {a[3]}</span></div></div></div>')
tc = (f'<div style="padding:10px 12px;display:flex;flex-direction:column;gap:10px"><span class="muted" style="font-size:11px">데스크톱 — 표</span>{tbl}'
      f'<span class="muted" style="font-size:11px">모바일 — 같은 내용을 카드로(정렬 · 거르기는 그대로)</span>{card}</div>')

WORDS = [('縣 · 郡 · 城 · 省', '현 · 군 · 성 · 구역'), ('자금 · 전(錢) · 국고', '금 · 수도 창고'), ('군량 · 곡(穀) · 병량', '쌀'),
         ('예턴 · 사령턴', '명령 목록 · 예약'), ('휴식', '빈 순'), ('계책 손패(화면 이름)', '계책 덱 · 손패는 칸 이름'),
         ('숙련 · 명성 · 계급 · 삭턴 · 벌점', '쓰지 않는다'), ('년 월(표기)', '200년 3월 중순')]
wrows = ''.join(f'<tr><td class="rs" style="text-decoration:line-through">{a}</td><td class="ms">{b}</td></tr>' for a, b in WORDS)
words = f'<div style="padding:8px 12px"><table class="table" style="font-size:12.5px"><thead><tr><th>쓰지 않는 말</th><th>쓰는 말</th></tr></thead><tbody>{wrows}</tbody></table></div>'

touch = (f'<div style="padding:12px;display:flex;flex-direction:column;gap:10px">'
         f'<div style="display:flex;align-items:center;gap:10px"><div style="width:44px;height:44px;border:1px dashed #d3b064;display:flex;align-items:center;justify-content:center" class="mono bz">44</div>'
         f'<span class="t2" style="font-size:12px">누르는 것은 모두 44px 이상 · 표 행 높이 44 · 간격 8 / 12</span></div>'
         f'<div style="display:flex;align-items:center;gap:10px"><div style="width:44px;height:44px;border:1px dashed #7aa7c7;display:flex;align-items:center;justify-content:center">{icon("up", 18, "#7aa7c7")}</div>'
         f'<span class="t2" style="font-size:12px">드래그만 되는 조작은 두지 않는다 — 탭으로도 된다(격자 칸 선택 · 순 옮기기)</span></div>'
         f'<div style="display:flex;align-items:center;gap:10px"><div style="width:44px;height:44px;border:1px dashed #c96b5d;display:flex;align-items:center;justify-content:center" class="rs">?</div>'
         f'<span class="t2" style="font-size:12px">호버 · title 로만 보이는 정보는 두지 않는다</span></div></div>')

g = 'display:grid;grid-template-columns:repeat(3,minmax(0,1fr));grid-template-rows:repeat(2,minmax(0,1fr));gap:12px;padding:12px;flex-grow:1;min-height:0'
sysbody = (f'<div style="{g}">'
           f'<section class="panel">{sec("브레이크포인트 3단", "토큰 · 화면별 임의 값 금지")}{bp}</section>'
           f'<section class="panel">{sec("비활성과 사유", "점선 + 누르면 사유")}{dis}</section>'
           f'<section class="panel">{sec("색", "의미 · 자원 · 국가 · 기록 분류")}{colors}</section>'
           f'<section class="panel">{sec("표 → 카드", "인물 일람 예시")}{tc}</section>'
           f'<section class="panel">{sec("화면 표기", "제품 문자열 lint 로 강제")}{words}</section>'
           f'<section class="panel">{sec("터치 · 밀도", "모바일에서도 같은 게임")}{touch}</section></div>')
page3('V3System.dc.html', '시스템 보강 — 모바일 · 사유 · 색 · 표기', topbar('시스템 보강') + sysbody)

print('ok')
