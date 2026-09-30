# 캔버스 v3 · 1묶음 개정(2026-09-30) — 「지도가 주인공, 크게 시원하게」 + M2 지도(와룡전 원본 타일).  python3 boards_v3_bundle1_rev.py
# 2026-09-26 승인본(boards_v3_shell.py)의 셸 · 메뉴 · 12순 · 사유 시트는 그대로 쓰고, 작전실 배치와 지도만 바꾼다.
# - 데스크톱: 지난 순 왼쪽 패널과 아래 구역 패널을 지도 위에서 걷는다. 지도 = 레일 · 12순 열을 뺀 전부.
# - 모바일: 머리줄도 지도 위에 띄운다. 지도 = 화면 전체, 12순은 하단 시트.
# - 지도 그림은 와룡전 원본 타일을 그대로 쓴다(2026-09-30 사용자 결정). 그림은 아티팩트 저장소 /_blob 참조라
#   이 저장소에서는 그려지지 않는다. 원본 그림은 커밋하지 않는다(opensamguk-images 정본, owner-accepted).
import boards_v3_shell as S
from v3common import *

from v31assets import MAP as IMG, PT, LOGO as LOGO_SRC  # 캔버스 그림 id 는 v31assets.py 한 곳에 둔다
import v3common as _vc  # 머리줄 로고 자리 표시를 실제 워드마크로(v31system 3.1.3과 같은 높이 28 · 모바일 24)
_vc.LOGO = f'<img src="{LOGO_SRC}" alt="오픈삼국" style="height:28px;width:auto;display:block;flex-shrink:0">'
_vc.LOGO_M = f'<img src="{LOGO_SRC}" alt="오픈삼국" style="height:24px;width:auto;display:block;flex-shrink:0">'

# 영천 일대 縣 이름(데스크톱 지도 칸 좌표, px). 치소·등급은 로컬 지도 자료(1447 월드) 기준 예시다.
DESK_LABELS = [('신정', 532, 36, 0), ('양적', 212, 356, 2), ('임영', 724, 804, 1), ('양성', 212, 804, 1), ('허', 852, 612, 1),
               ('장사', 660, 292, 1), ('영양', 340, 612, 1), ('영음', 660, 548, 1), ('위씨', 1044, 36, 1), ('번창', 596, 740, 1),
               ('언릉', 1044, 356, 1), ('마피영', 84, 708, 0)]
MOB_LABELS = [('밀', 267, 46, 0), ('양적', 203, 430, 2), ('영양', 331, 686, 1), ('마피영', 75, 782, 0)]


def label(n, x, y, tier, w_max=1048):
    fs = {0: 13, 1: 15, 2: 18}[tier]
    dy = {0: 14, 1: 18, 2: 52}[tier]
    w = len(n) * fs + 14
    left = max(4, min(w_max - w - 4, x - w // 2))
    return (f'<span class="serif" style="position:absolute;left:{left}px;top:{y + dy}px;height:{fs + 8}px;padding:0 7px;display:inline-flex;align-items:center;'
            f'font-size:{fs}px;font-weight:{900 if tier == 2 else 700};color:#f5ecd6;background:rgba(12,15,14,.72);white-space:nowrap">{n}</span>')


def me_marker(x, y, name='하후돈', ring='#4f7fbf', label=True):
    """내 위치 표지 — 내 장수 초상(96 아이콘 규칙) + 국가색 링 + 핀. 모든 보기 수준에서 이름표보다 위에 그린다."""
    chip = (f'<span style="position:absolute;left:50%;top:-30px;transform:translateX(-50%);height:24px;padding:0 8px;display:inline-flex;align-items:center;gap:4px;'
            f'font-size:12px;font-weight:700;color:#161410;background:#ffd36d;border:1px solid #9c7f3f;white-space:nowrap">내 위치 · {name}</span>') if label else ''
    return (f'<a href="#" aria-label="내 위치 — {name}, 누르면 내 장수 카드" style="position:absolute;left:{x - 24}px;top:{y - 62}px;width:48px;height:62px;display:block">'
            f'{chip}<span style="position:absolute;left:0;top:0;width:48px;height:48px;border-radius:50%;border:3px solid {ring};box-shadow:0 0 0 2px #ffd36d,0 4px 12px rgba(0,0,0,.6);overflow:hidden;background:#141816">'
            f'<img src="{PT["hahoudon"]}" alt="" style="width:100%;height:100%;object-fit:cover;object-position:top center;display:block"></span>'
            f'<span style="position:absolute;left:18px;top:46px;width:0;height:0;border-left:6px solid transparent;border-right:6px solid transparent;border-top:14px solid #ffd36d"></span></a>')


def mapbtn(ic, lab, extra=''):
    return (f'<button type="button" class="ibtn" aria-label="{lab}" style="background:rgba(20,24,22,.92);{extra}">{icon(ic, 20)}</button>')


def zbtn(t, lab):
    return (f'<button type="button" class="ibtn" aria-label="{lab}" style="background:rgba(20,24,22,.92);font-size:20px;font-weight:700">{t}</button>')


def lodseg(on='縣'):
    items = [('州', '주'), ('郡', '군'), ('縣', '현')]
    b = ''.join(f'<button type="button" aria-pressed="{"true" if k == on else "false"}" style="width:52px;height:44px;font:inherit;font-size:13px;font-weight:700;'
                f'{"background:#d3b064;color:#161410;border:1px solid #9c7f3f" if k == on else "background:rgba(20,24,22,.92);color:#ece6d8;border:1px solid #3d4740"}">{v}</button>'
                for k, v in items)
    return f'<div role="group" aria-label="보기 수준" style="display:flex;gap:2px">{b}</div>'


# ================================================================== 1. V31WarRoom — 데스크톱 1440, 지도가 주인공
MAP_W, MAP_H = 1048, 952
labels = ''.join(label(n, x, y, t) for n, x, y, t in DESK_LABELS)
sel = '<span style="position:absolute;left:640px;top:272px;width:40px;height:40px;border:2px solid #ffd36d;box-shadow:0 0 0 2px rgba(12,15,14,.8)"></span>'
card = f'''<section class="panel" aria-label="선택한 현" style="position:absolute;left:700px;top:180px;width:320px;background:rgba(27,32,29,.97);box-shadow:0 10px 28px rgba(0,0,0,.5)">
<div style="height:44px;display:flex;align-items:center;gap:8px;padding:0 4px 0 12px;border-bottom:1px solid #2c342f"><span class="serif" style="font-size:16px;font-weight:900">장사현</span><span class="chip">영천군</span><span class="chip bronze">내 귀환 성</span><button type="button" class="ibtn" aria-label="닫기" style="margin-left:auto;border:0;background:transparent">{icon("close", 18)}</button></div>
<div style="display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:6px;padding:8px">{kv('소속', '조조', 'bz')}{kv('보급', '연결됨', 'ms')}{kv('주둔', '하후돈 군단')}</div>
<div style="display:flex;gap:6px;padding:0 8px 8px"><a class="btn sm" href="#" style="height:44px;flex:1">현 상세</a><a class="btn sm primary" href="#" style="height:44px;flex:1">여기로 명령</a></div></section>'''

mini = (f'<div style="position:absolute;right:12px;bottom:12px;width:176px;height:153px;border:1px solid #3d4740;background:#0c0f0e;overflow:hidden">'
        f'<img src="{IMG["prov"]}" alt="천하 개관 — 지금 보는 곳 표시" style="width:176px;height:153px;display:block">'
        f'<span style="position:absolute;left:94px;top:64px;width:14px;height:12px;border:2px solid #ffd36d"></span></div>')

drawer = ('<button type="button" aria-expanded="false" style="position:absolute;left:0;top:120px;width:44px;height:132px;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:6px;'
          'font:inherit;font-size:12px;color:#ece6d8;background:rgba(27,32,29,.95);border:1px solid #3d4740;border-left:0;writing-mode:vertical-rl;letter-spacing:2px">지난 순 <span class="chip bronze" style="writing-mode:horizontal-tb">5</span></button>')

maptop = (f'<div style="position:absolute;left:56px;top:12px;right:12px;display:flex;align-items:center;gap:8px">{lodseg("縣")}'
          f'<span class="chip" style="height:32px;background:rgba(20,24,22,.92)">영천군 · 예주</span>'
          f'<div style="margin-left:auto;display:flex;gap:6px">{mapbtn("layers", "지도 레이어")}{mapbtn("legend", "범례")}</div></div>')
zoom = (f'<div style="position:absolute;left:12px;bottom:12px;display:flex;flex-direction:column;gap:4px">'
        f'{zbtn("+", "확대")}{zbtn("−", "축소")}{mapbtn("war", "내 위치로")}</div>')

mapstage = (f'<main aria-label="지도" style="position:relative;width:{MAP_W}px;height:{MAP_H}px;flex-shrink:0;overflow:hidden;background:#0c0f0e">'
            f'<img src="{IMG["desk"]}" alt="영천 일대 지도 — 현 보기" style="position:absolute;left:0;top:0;width:{MAP_W}px;height:{MAP_H}px;display:block;image-rendering:pixelated">'
            f'{labels}{sel}{me_marker(660, 292)}{maptop}{drawer}{zoom}{mini}{card}</main>')

aside = f'''<aside aria-label="명령 목록 12순" style="width:336px;flex-shrink:0;display:flex;flex-direction:column;background:#1b201d;border-left:1px solid #3d4740">{sec('명령 목록 12순', '직접 행동 · 한 순에 하나')}
{''.join(S.slotrow(i, s, 52) for i, s in enumerate(S.SLOTS))}
{sec('맡겨 둔 일', '순마다 굴러간다')}
<div style="padding:8px;display:flex;gap:4px">{S.STANDING}</div>
<div style="padding:8px;display:flex;gap:8px;margin-top:auto;border-top:1px solid #2c342f"><a class="btn primary" href="#" style="flex:1">이번 순에 할 일</a><button class="btn" type="button">당기기</button><button class="btn" type="button">밀기</button></div></aside>'''
# 36 + 12*52 + 36 + 72 + 61 = 829 <= 952

page3('V31WarRoom.dc.html', '작전실 v3.1 — 데스크톱, 지도 크게',
      topbar('작전실', h=48) + f'<div style="flex-grow:1;display:flex;min-height:0">{rail("war", slim=True)}{mapstage}{aside}</div>')

# ================================================================== 2. V31MWarRoom — 모바일 390 × 844, 지도 전면
mlabels = ''.join(label(n, x, y, t, w_max=390) for n, x, y, t in MOB_LABELS)
mhead = (f'<div style="position:absolute;left:8px;right:8px;top:8px;display:flex;align-items:center;gap:6px">'
         f'<span class="chip" style="height:44px;padding:0 10px;background:rgba(20,24,22,.94);font-size:12px">3월 중순 · 다음 턴 21:40</span>'
         f'<div style="margin-left:auto;display:flex;gap:6px"><button type="button" class="ibtn" aria-label="서신 2통" style="background:rgba(20,24,22,.94)">{icon("mail")}<span class="badge">2</span></button>'
         f'<button type="button" class="ibtn" aria-label="이 화면 도움말" style="background:rgba(20,24,22,.94)">{icon("help")}</button></div></div>')
mside = (f'<div style="position:absolute;right:8px;top:64px;display:flex;flex-direction:column;gap:4px">'
         f'{mapbtn("layers", "지도 레이어")}{zbtn("+", "확대")}{zbtn("−", "축소")}{mapbtn("war", "내 위치로")}</div>')
mlod = f'<div style="position:absolute;left:8px;top:64px">{lodseg("縣")}</div>'
mpill = ('<button type="button" style="position:absolute;left:8px;right:8px;bottom:196px;height:48px;display:flex;align-items:center;gap:8px;padding:0 12px;'
         'font:inherit;font-size:13px;color:#ece6d8;background:rgba(27,32,29,.97);border:1px solid #9c7f3f;text-align:left">'
         '<span class="serif" style="font-weight:900;font-size:15px">양적현</span><span class="chip bronze" style="height:20px">내 위치</span><span class="muted" style="font-size:12px">영천군 치소</span>'
         '<span class="chip bronze" style="margin-left:auto">조조</span></button>')
mpeek = f'''<section class="sheet" aria-label="명령 목록 12순" style="bottom:64px;height:124px"><div class="grip"></div>
<div style="height:52px;display:grid;grid-template-columns:24px minmax(0,1fr) auto;gap:8px;align-items:center;padding:0 12px;background:rgba(211,176,100,.08);box-shadow:inset 3px 0 0 #d3b064"><span class="mono muted" style="font-size:12px">02</span><div style="display:flex;flex-direction:column;min-width:0"><span class="mono t2" style="font-size:11px">3월 하순 · 22:40</span><span><span class="serif" style="font-size:15px;font-weight:700">훈련</span> <span class="muted" style="font-size:11px">하후돈 군단</span></span></div><span class="chip bronze">예약</span></div>
<div style="padding:6px 12px;display:flex;gap:8px"><a class="btn primary" href="#" style="flex:1;height:44px">이번 순에 할 일</a><button type="button" class="btn" aria-expanded="false" style="height:44px">{icon("up", 18)}12순</button></div></section>'''
# 16 + 52 + 56 = 124
mtab = f'<div style="position:absolute;left:0;right:0;bottom:0">{tabbar("war")}</div>'
mstage = (f'<main aria-label="지도" style="position:relative;width:390px;height:844px;overflow:hidden">'
          f'<img src="{IMG["mob"]}" alt="양적 일대 지도 — 현 보기" style="position:absolute;left:0;top:0;width:390px;height:844px;display:block;image-rendering:pixelated">'
          f'{mlabels}<span style="position:absolute;left:183px;top:410px;width:40px;height:40px;border:2px solid #ffd36d"></span>{me_marker(203, 430, label=False)}'
          f'{mhead}{mlod}{mside}{mpill}{mpeek}{mtab}</main>')
page3('V31MWarRoom.dc.html', '모바일 작전실 v3.1 — 지도 전면', mstage, w=390, h=844)


# ================================================================== 3. V31MapLOD — 확대 수준과 이름 규칙
def lodpanel(title, sub, img, iw, ih, alt, rules, labels_html=''):
    li = ''.join(f'<li style="padding:3px 0;border-bottom:1px solid #2c342f">{r}</li>' for r in rules)
    return (f'<section class="panel" style="flex:1 1 0;min-width:0">{sec(title, sub)}'
            f'<div style="position:relative;width:{iw}px;height:{ih}px;margin:10px auto 0;border:1px solid #3d4740;overflow:hidden">'
            f'<img src="{img}" alt="{alt}" style="width:{iw}px;height:{ih}px;display:block;image-rendering:pixelated">{labels_html}</div>'
            f'<ul style="margin:6px 12px 10px;padding:0;list-style:none;font-size:12px;line-height:1.4" class="t2">{li}</ul></section>')


def tag(t, x, y, fs=13, strong=False):
    return (f'<span class="serif" style="position:absolute;left:{x}px;top:{y}px;padding:1px 6px;font-size:{fs}px;font-weight:{900 if strong else 700};'
            f'color:#f5ecd6;background:rgba(12,15,14,.72);white-space:nowrap">{t}</span>')


prov_l = tag('예주', 262, 150, 15, True) + tag('연주', 250, 118, 15, True) + tag('사례', 196, 132, 15, True) + tag('형주', 220, 196, 15, True) + tag('양주', 320, 206, 15, True) + tag('익주', 90, 210, 15, True)
jun_l = tag('영천군', 120, 90, 14, True) + tag('하남윤', 20, 16, 13) + tag('진류군', 300, 20, 13) + tag('여남군', 330, 196, 13) + tag('양적', 150, 124, 12)
xian_l = tag('양적현', 180, 250, 15, True) + me_marker(218, 170, label=True)
jun_l += me_marker(178, 102, label=False)
prov_l += me_marker(214, 134, label=False)
lod = (f'<div style="flex-grow:1;display:flex;gap:12px;padding:12px;min-height:0">'
       + lodpanel('주 보기', '전체 · 칸당 1px 미만', IMG['prov'], 380, 331, '천하 개관 — 주 보기',
                  ['그림: 미리 구운 개관 한 장(지형색 · 큰 강 · 해안)', '이름: 주 이름만 · 성 이름 없음', '세력: 국경 띠만(굵게) · 깃발 없음',
                   '처음 여는 화면이 아니다 — 축소하면 나온다'], prov_l)
       + lodpanel('군 보기', '칸당 4px', IMG['jun'], 380, 217, '영천 일대 — 군 보기',
                  ['그림: 원본 타일을 줄여 구운 조각(청크)', '이름: 군 이름 + 군 치소만 · 현 이름 없음', '세력: 국경 띠 + 치소 깃발',
                   '겹치면 작은 쪽을 숨긴다(등급 → 인구 순)'], jun_l)
       + lodpanel('현 보기(기본)', '칸당 16px · 최대 32px', IMG['xian32'], 380, 292, '양적현 — 현 보기 32px',
                  ['그림: 원본 16px 타일 그대로 · 32px 는 최근접 2배', '이름: 모든 현 이름 · 글자 15px 이상', '세력: 성 지붕 · 술 끝 깃발(세력 첫 글자) · 국경 띠',
                   '작전실을 열면 내 장수 자리를 이 수준으로 보인다'], xian_l)
       + '</div>')

CTRL = [('휠 · 트랙패드', '확대 · 축소(커서 자리 기준)', '핀치'), ('끌기', '지도 옮기기', '한 손가락 끌기'),
        ('+ · −', '확대 · 축소 단추(44px)', '같음'), ('주 · 군 · 현', '보기 수준 바로 가기', '같음'),
        ('레이어', '구역 · 현 · 군 경계, 보급선, 시야, 부대 경로, 도시 이름 켜고 끄기', '같음'),
        ('누르기', '현 · 성 선택 → 떠 있는 카드', '탭 → 하단 알약'), ('키보드', '방향키 옮기기 · +/− 확대 · Esc 선택 해제', '—'),
        ('내 위치', '내 장수 자리로 돌아오기', '같음'),
        ('내 위치 표지', '내 장수의 실제 자리(성 안 · 성 밖 · 군단과 함께 · 이동 중)에 초상 핀. 모든 보기 수준에서 보이고 이름표에 가리지 않는다. 화면 밖이면 가장자리 화살표', '같음 · 누르면 내 카드')]
ctab = ''.join(f'<tr><td class="bz" style="white-space:nowrap;height:30px">{a}</td><td style="white-space:normal;height:30px;line-height:1.3">{b}</td><td class="t2" style="white-space:nowrap;height:30px">{c}</td></tr>' for a, b, c in CTRL)
BUD = [('첫 그림', '3초 안에 지형(광대역)', '지금 11–19초'), ('같은 자원', '한 번만 받는다', '지금 구역 식별 PNG 24.7MB × 2'),
       ('성 그림', '보이는 수준 · 보이는 곳만', '지금 324장 전부(1x–8x)'), ('움직임', '끌기 · 확대 60fps', '미측정'),
       ('지도 면적', '작전실 가용 영역의 75% 이상', '지금 37%(로그인 미리보기)')]
btab = ''.join(f'<tr><td class="bz" style="height:30px">{a}</td><td style="height:30px">{b}</td><td class="rs" style="height:30px">{c}</td></tr>' for a, b, c in BUD)
lodfoot = (f'<div style="display:flex;gap:12px;padding:0 12px 12px;height:400px;flex-shrink:0">'
           f'<section class="panel" style="flex:1.3 1 0;min-width:0">{sec("조작 — 지금 아이소 지도에 있던 것 전부 옮긴다", "데스크톱 · 모바일")}'
           f'<div style="padding:6px 10px"><table class="table" style="font-size:12px"><thead><tr><th>조작</th><th>하는 일</th><th>모바일</th></tr></thead><tbody>{ctab}</tbody></table></div></section>'
           f'<section class="panel" style="flex:1 1 0;min-width:0">{sec("성능 예산", "PR 마다 잰다")}'
           f'<div style="padding:6px 10px"><table class="table" style="font-size:12px"><thead><tr><th>항목</th><th>목표</th><th>지금 pep</th></tr></thead><tbody>{btab}</tbody></table></div></section></div>')
page3('V31MapLOD.dc.html', '지도 — 보기 수준 · 이름 · 조작 · 예산', topbar('지도 보기 수준', h=48, compact=True) + lod + lodfoot)

# ================================================================== 4. V31MapArt — 와룡전 원본 타일 그대로
def fig(img, w, h, alt, cap):
    return (f'<figure style="margin:0;display:flex;flex-direction:column;gap:6px">'
            f'<img src="{img}" alt="{alt}" style="width:{w}px;height:{h}px;display:block;border:1px solid #3d4740;image-rendering:pixelated">'
            f'<figcaption class="t2" style="font-size:12px">{cap}</figcaption></figure>')


art_top = (f'<div style="display:flex;gap:12px;padding:12px 12px 0">'
           + fig(IMG['guanzhong'], 560, 350, '관중 — 원본 타일 렌더', '관중(장안 · 위수 · 진령) — 강 · 길 · 산 높이 단 · 피복은 지도 설계 층')
           + fig(IMG['jiangdong'], 560, 350, '강동 — 원본 타일 렌더', '강동(말릉 · 태호) — 호수 · 해안 다듬기 반영')
           + f'<section class="panel" style="flex:1 1 0;min-width:0">{sec("정한 것", "2026-09-30")}'
           + '<ul style="margin:0;padding:8px 12px;list-style:none;font-size:12.5px;line-height:1.55" class="t2">'
           + '<li style="padding:4px 0;border-bottom:1px solid #2c342f"><b class="bz">그림 = 와룡전 추출 원본 그대로</b> — 지형 타일 · 성 부품 · 깃발 틀 · 표지</li>'
           + '<li style="padding:4px 0;border-bottom:1px solid #2c342f">새로 그리지 않는다 · AI 생성 없음</li>'
           + '<li style="padding:4px 0;border-bottom:1px solid #2c342f">16px 원본 · 32px 는 최근접 2배(픽셀 그대로)</li>'
           + '<li style="padding:4px 0;border-bottom:1px solid #2c342f">원작에 짝이 없는 것은 원작 부품으로 조립 — 큰 성 · 1칸 거점 · 세력색</li>'
           + '<li style="padding:4px 0;border-bottom:1px solid #2c342f">큰 성은 외성 + 내성(09-26): 중 · 대 내성 3×3, 특 · 경 내성 5×5</li>'
           + '<li style="padding:4px 0;border-bottom:1px solid #2c342f">세력색은 성 지붕 · 깃발 · 국경 띠 · 1칸 거점에만</li>'
           + '<li style="padding:4px 0">정본 opensamguk-images(owner-accepted, 출처 기록) · 앱에는 export 만</li></ul></section></div>')
art_mid = (f'<div style="display:flex;gap:12px;padding:12px">'
           + fig(IMG['castles'], 1062, 272, '성 크기 — B안 7단, 중 이상 외성과 내성', '성 크기 B안: 장현 1 · 영현 3 · 소 5는 한 겹, <b class="bz">중 7 · 대 9는 외성 + 내성 3×3, 특 11 · 경 13은 외성 + 내성 5×5</b>(예: 양적현 = 특). 십자 대로, 관청가는 가운데 · 민가는 바깥, 정원 · 밭 섞기. 성문은 길이 닿는 변에만')
           + fig(IMG['icons1'], 330, 220, '1칸 거점 4종 × 세력색, 원작 부품만', '1칸 거점 — 원작 부품만으로 다시 조립(K2, opensamguk-images #20): 장현 = 원작 민가, 수 = 작은 성 축소 + 원작 다리 판자, 진 = 원작 문루, 이 = 원작 민가 · 숲. 오른쪽은 깃발 천. 관은 1칸이 아니다(B3)')
           + '</div>')
art_bot = (f'<div style="display:flex;gap:12px;padding:0 12px 12px;flex-grow:1;min-height:0">'
           + fig(IMG['flags'], 448, 280, '깃발 시연 — 성 술 끝 · 부대 제비꼬리', '깃발 — 성은 술 끝 + 세력 첫 글자, 부대는 제비꼬리 + 장수 첫 글자, 글자는 천 기울기대로 · 32px 이상')
           + f'<section class="panel" style="flex:1 1 0;min-width:0">{sec("정한 것 · 남은 것", "2026-09-30")}'
           + '<div style="padding:10px 12px;display:flex;flex-direction:column;gap:10px;font-size:12.5px;line-height:1.5">'
           + '<div class="inset" style="padding:10px 12px"><span class="bz" style="font-weight:700">성 크기 — B안으로 정함(09-30)</span><br>'
           + '<span class="t2">장현 1 · 영현 3 · 소 5 · 중 7 · 대 9 · 특 11 · 경 13. 제품 cityFootprint(경 7 · 특 · 대 5 · 중 3 · 1칸)는 C9 가 이 판으로 옮긴다.</span></div>'
           + '<div class="inset" style="padding:10px 12px"><span class="bz" style="font-weight:700">1칸 거점 그림</span><br>'
           + '<span class="t2">원작에 1칸 성이 없다. 3×3 성을 줄인 판을 바탕으로 원작 부품으로 손질한다(위 그림). B안에서 1칸은 장현 · 수 · 진 · 이다. 관은 성벽이 산에 붙는 여러 칸 관문이다(B3). 전체 설계 승인 때 함께 본다.</span></div>'
           + '</div></section></div>')
page3('V31MapArt.dc.html', '지도 그림 — 와룡전 원본 타일 그대로', topbar('지도 그림', h=48, compact=True) + art_top + art_mid + art_bot)

# ================================================================== 5. V31Baseline — 지금 pep(M1 전 기준)
MEAS = [('첫 지도 그림', '11.1 – 18.6초', '콜드 · 광대역 제한 13.1초'), ('요청 수', '436', '/login 한 화면'), ('받은 양', '57.0 MB', ''),
        ('구역 식별 PNG', '24.7 MB × 2번', '무압축 · 같은 요청 두 번'), ('성 그림', '324장 · 5.2 MB', '1x · 2x · 4x · 8x 각 81장'),
        ('지도 크기(데스크톱)', '824 × 588', '화면의 37% · 스크롤 아래'), ('지도 크기(모바일)', '348 × 248', '첫 화면 밖(778px 아래)'),
        ('군 보기 이름', '현 이름이 모두 겹쳐 그려짐', 'dataset.mapLod = COMMANDERY')]
mrows = ''.join(f'<tr><td class="bz">{a}</td><td class="rs mono">{b}</td><td class="t2" style="white-space:normal">{c}</td></tr>' for a, b, c in MEAS)
base = (f'<div style="flex-grow:1;display:flex;gap:12px;padding:12px;min-height:0">'
        + fig(IMG['pep'], 824, 588, '지금 pep /login 지도 미리보기 — 아이소', '지금 pep /login 지도 미리보기(데스크톱 1440, 2026-09-30 17:30 콜드)')
        + fig(IMG['pepm'], 180, 390, '지금 pep /login 모바일', '모바일 390 — 지도는 첫 화면 밖')
        + f'<section class="panel" style="flex:1 1 0;min-width:0">{sec("측정", "M1 전 기준 · 같은 도구로 다시 잰다")}'
        + f'<div style="padding:6px 10px"><table class="table" style="font-size:12px"><thead><tr><th>항목</th><th>지금</th><th>메모</th></tr></thead><tbody>{mrows}</tbody></table></div></section></div>')
page3('V31Baseline.dc.html', '지금 pep 지도 — M1 전 기준', topbar('지금 pep 지도', h=48, compact=True) + base)
# ================================================================== 6. V31MapPass — 관: 성벽이 산에 붙어 길을 막는다
PASS_RULES = [
    ('모양', '길 위에 문 · 문루 · 문 3칸. 성벽은 길과 직각으로 양쪽 산(또는 큰 물)에 닿을 때까지 잇는다. 원작 부품: 남북 길 D4 · D0 · D5, 성벽 E7, 끝 F8 · F9 / 동서 길 D6 · D3 · D7, 성벽 E6, 끝 FB · FA'),
    ('막기', '관 양쪽을 잇는 길은 관문 하나로만 지난다. 성벽 끝과 산 사이에 틈이 없어야 한다 — 틈이 5칸을 넘으면 성벽을 늘리지 말고 능선(PASS_GORGE)을 세워 막는다'),
    ('길', '관 둘레의 길은 관문으로 모은다. 능선 옆으로 돌아가는 길은 지운다(지도 설계 층 roads). 길 골짜기 폭은 관 반경 10칸 안에서 좁게'),
    ('규칙', '화면과 규칙이 같은 층을 읽는다: 관을 거치지 않는 육로 인접이 없어야 하고, 적이 쥔 관은 뚫거나 빼앗아야 지난다(서버 C9 · C3, 계약판 U-05)'),
    ('세력', '관문 지붕 · 깃발은 쥔 세력색. 누르면 관 카드(쥔 세력 · 수비 · 통과 가능 여부와 사유)'),
    ('보기 수준', '군 보기부터 보인다. 주 보기에서는 관 표지 하나'),
]
prow = ''.join(f'<tr><td class="bz" style="white-space:nowrap;vertical-align:top;padding-top:8px">{a}</td><td style="white-space:normal;line-height:1.5;padding:6px 10px">{b}</td></tr>' for a, b in PASS_RULES)
pass_top = (f'<div style="display:flex;gap:12px;padding:12px 12px 0">'
            + fig(IMG['pass_ok'], 468, 324, '원작 관 — 길 위 관문과 산에 닿은 성벽', '<b class="ms">맞는 예(원작)</b> — 동서 길 위에 관문, 성벽이 남북으로 산에 닿아 길이 관문으로만 지난다')
            + fig(IMG['pass_bad_hulao'], 432, 324, '09-26 시안 호뢰관 — 성벽이 들판에서 끝남', '<b class="rs">고칠 것</b> — 호뢰관: 성벽이 들판에서 끝나 옆길이 관을 거치지 않고 지난다')
            + fig(IMG['pass_bad_jiange'], 460, 324, '09-26 시안 검각 — 길이 능선 옆으로 돎', '<b class="rs">고칠 것</b> — 검각: 능선이 섬처럼 떠 있어 길이 관을 비켜 돈다')
            + '</div>')
pass_bot = (f'<div style="display:flex;gap:12px;padding:12px;flex-grow:1;min-height:0">'
            f'<section class="panel" style="flex:1 1 0;min-width:0">{sec("관 — 성벽이 산에 붙어 실제로 길을 막는다", "사용자 2026-09-30 · 지도 설계 계획 §2")}'
            f'<div style="padding:6px 10px"><table class="table" style="font-size:12.5px"><tbody>{prow}</tbody></table></div></section>'
            f'<section class="panel" style="width:360px;flex-shrink:0">{sec("검사", "적색 프로브")}'
            '<ul style="margin:0;padding:8px 12px;list-style:none;font-size:12.5px;line-height:1.6" class="t2">'
            '<li style="padding:4px 0;border-bottom:1px solid #2c342f">관마다 성벽 양끝이 산 · 큰 물 칸에 닿는다(틈 0)</li>'
            '<li style="padding:4px 0;border-bottom:1px solid #2c342f">관 양쪽 구역 쌍 사이에 관을 거치지 않는 길 · 육로 인접 0</li>'
            '<li style="padding:4px 0;border-bottom:1px solid #2c342f">관문 칸을 지나는 길이 정확히 하나</li>'
            '<li style="padding:4px 0">호뢰관 · 검각 · 양안관(지금 반경 4칸 산 0)부터 고친다</li></ul></section></div>')
page3('V31MapPass.dc.html', '지도 — 관은 길을 막는다', topbar('관 — 길을 막는 관문', h=48, compact=True) + pass_top + pass_bot)
# ================================================================== 7. V31Cover — 전 페이지 설계 승인 요청(캔버스 첫 보드)
GROUPS = [('S', '디자인 시스템 v3.1', '셸 · 메뉴 · 토큰 · 부품 · 지도 고르기 · 명령 흐름 · 사람 고르기 · 상태 · 알림 띠', 'K3'),
          ('K4', '작전실 · 부 · 영지', '작전실(지도 + 12순) · 지난 순 · 편성 · 인물 · 월단평 · 포로 · 배치 · 현 · 군 · 창고망 · 공성 · 조정', 'K4'),
          ('K6', '명령 · 계책 · 군단 · 외교 · 서신', '이번 순에 할 일 · 계책 덱 · 군단 · 전투(아이소) · 시야 · 외교 · 서신', 'K6'),
          ('K5', '게이트웨이 · 입장 · 기록', '로그인 · 가입 · 로비 · 계정 · 커뮤니티 · 정책 · 운영 콘솔 · 장수 만들기 · 출사 · 기록 · 연감 · 리플레이 · 회의실 · 게임 관리', 'K5'),
          ('K7', '도움말 · 튜토리얼', '도움말 서랍 · 찾기 · 사유 → 도움말 · 첫걸음 8단계', 'K7'),
          ('K8', '2 · 3층 · 시즌', '관직 · 봉신 · 황실 · 천하 형세 · 세력 · 주변 세계 · 역정보', 'K8'),
          ('B', '지도 그림 · 보기 수준 · 관', '와룡전 원본 타일 · 주 · 군 · 현 보기 · 관은 길을 막는다 · 지금 pep 기준선', 'K0 · K2')]
grows = ''.join(f'<tr><td class="bz" style="white-space:nowrap">{k}</td><td style="white-space:nowrap"><b>{n}</b></td><td class="t2" style="white-space:normal;line-height:1.4">{d}</td><td class="muted">{l}</td></tr>' for k, n, d, l in GROUPS)
ASK = ['전 페이지 설계 v3.1 전체(보드 전부)를 화면 정본으로 삼는다',
       '실시간 전투 화면 재편 — 09-18 전투 계획 봉인 · 방어 대비 대신 「전투 · 부재 대비」 + 「참가 대기 · 배치」(09-27 실시간 전투 결정 때문)',
       '전투 판 위 분대 표기 — A 깃발(전략 지도 · 리플레이와 같은 표기) 또는 B 원작 유닛 그림 + 머리 위 작은 깃발(원작 그대로, 추천). K6 전투 보드 두 판을 나란히 본다',
       '(게임 규칙) 성새 전장의 성벽 윗면 — 지금은 평지처럼 걸어 다닐 수 있다(요새 188판 · 17,920칸, 성문 우회 가능성). 추천: 성벽은 막는다 — 성문 · 성벽이 뚫려야 들어간다(원작도 성벽 위에 유닛을 그리지 않는다는 간접 근거). 다른 안: 원작 이동 판정을 먼저 해독한 뒤 정한다',
       '2 · 3층 메뉴 — 봉신 = 관직 탭, 주변 세계 = 외교 탭, 칭제 = 황실 안, 역정보 = 계책, 계절 사건 = 독립 페이지 없음',
       '기밀실 권한 — 군주 + 봉신 주공 + 군주 지정, 2층 관직 뒤 중앙 관직 추가',
       '개인정보처리방침 · 이용약관 페이지 추가(/privacy · /terms)',
       '튜토리얼 달성 기준 — 첫 발령 = 수락 확정, 첫 등용 = 결과 수신, 첫 행군 = 출병 · 이동. 첫걸음 칩은 연습 서버에서만',
       '입력 이름 — 군량 습격 → 보급 습격, 군량매매 → 쌀 사고팔기, 숙련전환 → 병종 바꿔 익히기',
       '누르는 것은 모두 누를 영역 44px 이상(작은 단추 예외 없음) · 화면 폭 3단 · 입력 4상태(보통 · 막힘 사유 · 준비 중 · 없음)',
       '명령 흐름이 열리면 12순 열 자리를 명령 패널(576)이 쓰고 지도는 808',
       '로그인 소개 · 로비 각주 · 정책 문구(공개 알파 정책 문구와 함께 따로 승인)']
DONE = ['프론트 동결 해제(U1)', '지도 그림 = 와룡전 원본 그대로(U3)', '모든 페이지 한 번에 설계 · 승인 뒤 구현(D1 · D1a)',
        '성 크기 B안 1 · 3 · 5 · 7 · 9 · 11 · 13, 큰 성은 외성 + 내성(D2)', 'NPC에게도 개인 서신(D4)', '지도에 내 위치 표지(D5)',
        '관은 성벽이 산에 붙어 길을 막는다(D6)', '운영 콘솔 — 지운 글 숨김 · 게시판 만들기 · 삭제(D7 · D8)', '실시간 전투 화면은 원작처럼 아이소(D9)']
# 옛 이름을 늘어놓는 설명 줄은 금지어 검사에서 뺀다(K10 board-lint data-lint=skip)
ask = ''.join(f'<li style="padding:6px 0;border-bottom:1px solid #2c342f;display:flex;gap:8px"{" data-lint=\"skip\"" if "→" in t and "군량" in t else ""}><span class="mono bz" style="width:22px;flex-shrink:0">{i + 1}</span><span>{t}</span></li>' for i, t in enumerate(ASK))
done = ''.join(f'<li style="padding:5px 0;border-bottom:1px solid #2c342f" class="t2">{t}</li>' for t in DONE)
MARKS = [('chip info', '서버 대기 A', '읽기 API가 아직 없다 — 모양만 정한다'), ('chip info', '서버 대기 B', '읽기는 있고 입력만 없다'),
         ('chip', '준비 중', '원장 PLANNED 입력 — 보이되 누르면 사유'), ('chip rust', '승인 대기', '이번 승인에서 정할 것'), ('chip', '[미정] · [값]', '수치는 지어내지 않는다')]
marks = ''.join(f'<div style="display:flex;align-items:center;gap:8px;min-height:32px"><span class="{c}">{a}</span><span class="t2" style="font-size:12px">{b}</span></div>' for c, a, b in MARKS)
cover = (f'<div style="flex-grow:1;display:grid;grid-template-columns:minmax(0,1.15fr) minmax(0,1fr);gap:12px;padding:12px;min-height:0">'
         f'<div style="display:flex;flex-direction:column;gap:12px;min-height:0">'
         f'<section class="panel">{sec("보는 순서 — 묶음", "캔버스 위에서 아래로")}<div style="padding:6px 10px"><table class="table" style="font-size:12.5px"><tbody>{grows}</tbody></table></div></section>'
         f'<section class="panel">{sec("표식 읽는 법", "보드 곳곳의 칩")}<div style="padding:8px 12px;display:flex;flex-direction:column;gap:2px">{marks}</div></section>'
         f'<section class="panel">{sec("이미 정한 것(참고)", "2026-09-30 사용자 결정 · ADR-LITE-049 개정 #1082")}<ul style="margin:0;padding:6px 12px;list-style:none;font-size:12.5px">{done}</ul></section></div>'
         f'<section class="panel" style="min-height:0">{sec("이번에 승인할 것", "한 번에 승인 · 승인 뒤 바꾸려면 ADR 개정")}<ul style="margin:0;padding:6px 12px;list-style:none;font-size:13px;line-height:1.45">{ask}</ul>'
         f'<div style="margin-top:auto;padding:10px 12px;border-top:1px solid #2c342f" class="t2">승인하면 ADR-LITE-049에 승인 날짜를 적고 화면 구현(Wave 1)을 시작한다. 고칠 곳은 보드에 댓글로 남기거나 채팅으로 알려 주면 모아서 반영한 뒤 다시 올린다.</div></section></div>')
page3('V31Cover.dc.html', '전 페이지 설계 v3.1 — 승인 요청', topbar('전 페이지 설계 v3.1 — 승인 요청', h=48, compact=True) + cover)
print('ok rev')
