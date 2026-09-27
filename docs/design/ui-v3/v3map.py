# 작전실 지도 개략도 — 2026-09-18 캔버스 boards_core.py 의 지도 부분을 옮겼다(양성현 한자 병기만 뺐다).
from ui import *
# ------------------------------------------------------------------ 지도
V={'T0':(0,0),'T1':(330,0),'T2':(680,0),'T3':(1000,0),
'N0':(0,140),'N1':(250,120),'Nb':(340,116),'N2':(480,110),'Nc':(700,89),'N3':(740,85),'N4':(1000,60),
'A0':(0,170),'A1':(250,150),'A2':(480,140),'A3':(740,115),'A4':(1000,90),
'B0':(0,340),'B1':(260,325),'B2':(490,335),'B3':(720,320),'B4':(1000,330),
'C0':(0,520),'C1':(270,525),'C2':(490,520),'Cm':(600,522),'C3':(710,525),'C4':(1000,515),
'D0':(0,700),'D1':(270,700),'D2':(600,700),'D3':(1000,700)}
# v3: 국가색은 유저가 고르는 색이라 의미색(청동·적갈·이끼)과 겹치지 않는 예시 색을 쓴다.
F={'jo':'#4f7fbf','won':'#b0569a','etc':'#4f9e8a','none':'#5a625c'}
# (키, 꼭짓점, 세력, 안개, 이름, 부제, 라벨 x,y)
PROV=[
('huai',['T0','T1','Nb','N1','N0'],'won',False,'회현','하내군',150,62),
('liyang',['T1','T2','Nc','N2','Nb'],'won',False,'여양현','위군',505,52),
('baima',['T2','T3','N4','N3','Nc'],'won',True,'백마현','동군',850,40),
('xingyang',['A0','A1','B1','B0'],'jo',False,'형양현','하남윤',120,250),
('guandu',['A1','A2','B2','B1'],'jo',False,'관도','중모현 관할 · 성 없음',368,222),
('chenliu',['A2','A3','B3','B2'],'jo',False,'진류현','진류군',610,225),
('yongqiu',['A3','A4','B4','B3'],'none',True,'옹구현','진류군',865,215),
('yangdi',['B0','B1','C1','C0'],'jo',False,'양적현','영천군 치소',128,430),
('changshe',['B1','B2','C2','C1'],'jo',False,'장사현','영천군',375,430),
('weishi',['B2','B3','C3','Cm','C2'],'jo',False,'위씨현','진류군',600,425),
('chen',['B3','B4','C4','C3'],'none',False,'진현','진국',860,425),
('xiangcheng',['C0','C1','D1','D0'],'jo',False,'양성현','영천군',130,615),
('xu',['C1','C2','Cm','D2','D1'],'jo',False,'허현','영천군',435,615),
('yan',['Cm','C3','C4','D3','D2'],'etc',True,'언현','영천군 · 여남군 접경',800,615),
]
CITY={'huai':(120,95),'liyang':(560,88),'xingyang':(170,215),'chenliu':(640,265),'yangdi':(150,470),'changshe':(400,470),'weishi':(585,465),'chen':(880,465),'xiangcheng':(150,650),'xu':(420,655)}

def pts(keys): return ' '.join(f'{V[k][0]},{V[k][1]}' for k in keys)
def anchor(x,y,c='#7aa7c7'):
    return f'<g transform="translate({x},{y})" fill="none" stroke="{c}" stroke-width="2"><circle r="11" fill="#0c0f0e"></circle><circle cy="-5" r="2"></circle><path d="M0 -3V7M-6 2c0 4 3 6 6 6s6-2 6-6M-3 -1h6"></path></g>'
def ferry(x,y,ghost=False):
    c='#8a8477' if ghost else '#7aa7c7'; d=' stroke-dasharray="3 3"' if ghost else ''
    return f'<g transform="translate({x},{y})" fill="none" stroke="{c}" stroke-width="2"{d}><rect x="-9" y="-9" width="18" height="18" fill="#0c0f0e" transform="rotate(45)"></rect><path d="M-5 -3h10M-5 3h10"></path></g>'
def corps(x,y,c,lab,fs,sub=''):
    s=f'<text x="{x+16}" y="{y+fs+8}" font-size="{fs-2}" fill="#b9b2a3">{sub}</text>' if sub else ''
    return f'<g><path d="M{x} {y-14}L{x+13} {y}L{x} {y+14}L{x-13} {y}Z" fill="{c}" stroke="#0c0f0e" stroke-width="2"></path><path d="M{x-5} {y}h10M{x} {y-5}v10" stroke="#0c0f0e" stroke-width="2"></path><text x="{x+16}" y="{y+4}" font-size="{fs}" font-weight="700" fill="#ece6d8" stroke="#0c0f0e" stroke-width="3" paint-order="stroke">{lab}</text>{s}</g>'

def mapsvg(viewbox,fs=13,sel='guandu',supply=False,vision=False,admin=False,sub=True,style='position:absolute;inset:0;width:100%;height:100%',uid='m'):
    o=[f'<svg viewBox="{viewbox}" preserveAspectRatio="xMidYMid slice" style="{style}" role="img" aria-label="영천·진류 방면 ⟦PROV⟧ 지도 (개략도)" xmlns="http://www.w3.org/2000/svg">']
    o.append(f'<defs><pattern id="{uid}fog" width="8" height="8" patternUnits="userSpaceOnUse" patternTransform="rotate(45)"><rect width="8" height="8" fill="#0c0f0e" fill-opacity=".72"></rect><path d="M0 0v8" stroke="#3d4740" stroke-width="2"></path></pattern><pattern id="{uid}far" width="14" height="14" patternUnits="userSpaceOnUse" patternTransform="rotate(45)"><rect width="14" height="14" fill="#101412"></rect><path d="M0 0v14" stroke="#1b201d" stroke-width="3"></path></pattern></defs>')
    o.append(f'<rect x="-1200" y="-800" width="3400" height="2400" fill="url(#{uid}far)"></rect>')
    for k,vs,f,fog,n,s,lx,ly in PROV:
        o.append(f'<polygon points="{pts(vs)}" fill="{F[f]}" fill-opacity="{0.10 if f=="none" else 0.20}" stroke="#3d4740" stroke-width="1.5"></polygon>')
    # 하수(하수) 띠 + 영수(영수)
    o.append('<path d="M-1200 215L0 155L250 135L480 125L740 100L1000 75L2200 -40" fill="none" stroke="#2f5068" stroke-width="30" stroke-linejoin="round"></path>')
    o.append('<path d="M60 380C200 440 330 500 470 560S640 660 700 700" fill="none" stroke="#2f5068" stroke-width="7"></path>')
    for k,vs,f,fog,n,s,lx,ly in PROV:
        if fog: o.append(f'<polygon points="{pts(vs)}" fill="url(#{uid}fog)" stroke="#3d4740" stroke-width="1.5"></polygon>')
    if admin:
        o.append(f'<polygon points="{pts(["B0","B1","B2","C2","Cm","D2","D0"])}" fill="none" stroke="#d3b064" stroke-width="3" stroke-dasharray="10 5"></polygon><text x="20" y="365" font-size="{fs}" fill="#d3b064" font-weight="700">영천군 · 예주</text>')
        o.append(f'<polygon points="{pts(["A2","A4","B4","B3","C3","Cm","C2","B2"])}" fill="none" stroke="#7aa7c7" stroke-width="3" stroke-dasharray="10 5"></polygon><text x="770" y="140" font-size="{fs}" fill="#7aa7c7" font-weight="700">진류군 · 연주</text>')
    # 전선
    o.append('<path d="M0 170L250 150L480 140L740 115" fill="none" stroke="#e08a7c" stroke-width="3" stroke-dasharray="2 5"></path>')
    if supply:
        o.append('<path d="M420 655L400 470L368 270M400 470L150 470M400 470L585 465L640 265M150 470L170 215" fill="none" stroke="#8fa77a" stroke-width="2.5"></path>')
        o.append('<path d="M640 265L790 240" fill="none" stroke="#e08a7c" stroke-width="2.5" stroke-dasharray="6 5"></path><g transform="translate(715,252)" stroke="#e08a7c" stroke-width="3"><path d="M-7 -7L7 7M7 -7L-7 7"></path></g>')
        o.append(f'<text x="700" y="285" font-size="{fs-1}" fill="#e08a7c">보급 끊김 — 옹구현 방면</text>')
    if vision:
        for (x,y,r,t) in [(368,270,120,'정찰 배치'),(170,215,95,'망루'),(640,265,95,'봉화')]:
            o.append(f'<circle cx="{x}" cy="{y}" r="{r}" fill="#7aa7c7" fill-opacity=".07" stroke="#7aa7c7" stroke-width="1.5" stroke-dasharray="5 4"></circle><text x="{x-r+10}" y="{y+r-12}" font-size="{fs-2}" fill="#7aa7c7">{t}</text>')
    # 뱃길
    o.append('<path d="M215 140C330 100 520 150 712 102" fill="none" stroke="#7aa7c7" stroke-width="2.5" stroke-dasharray="8 6"></path>')
    o.append(f'<text x="395" y="{112}" font-size="{fs-2}" fill="#7aa7c7" stroke="#0c0f0e" stroke-width="3" paint-order="stroke">강 뱃길 · 하수</text>')
    o.append(anchor(215,140)+anchor(712,102)+ferry(560,120)+ferry(470,560,True))
    if sub:
        o.append(f'<text x="232" y="172" font-size="{fs-2}" fill="#7aa7c7" stroke="#0c0f0e" stroke-width="3" paint-order="stroke">항구</text><text x="728" y="130" font-size="{fs-2}" fill="#7aa7c7" stroke="#0c0f0e" stroke-width="3" paint-order="stroke">항구</text><text x="575" y="148" font-size="{fs-2}" fill="#7aa7c7" stroke="#0c0f0e" stroke-width="3" paint-order="stroke">나루</text><text x="486" y="585" font-size="{fs-2}" fill="#8a8477" stroke="#0c0f0e" stroke-width="3" paint-order="stroke">나루(표지만)</text>')
    # 선택 ⟦PROV⟧
    for k,vs,*_ in PROV:
        if k==sel: o.append(f'<polygon points="{pts(vs)}" fill="none" stroke="#ffd36d" stroke-width="3.5"></polygon>')
    # 라벨·성
    for k,vs,f,fog,n,s,lx,ly in PROV:
        col='#8a8477' if fog else '#ece6d8'
        o.append(f'<text x="{lx}" y="{ly}" text-anchor="middle" font-size="{fs+2}" font-weight="700" fill="{col}" stroke="#0c0f0e" stroke-width="3" paint-order="stroke" style="font-family:\'Noto Serif KR\',serif">{n}</text>')
        if sub: o.append(f'<text x="{lx}" y="{ly+fs+4}" text-anchor="middle" font-size="{fs-2}" fill="#8a8477" stroke="#0c0f0e" stroke-width="3" paint-order="stroke">{"미정찰 · "+s if fog else s}</text>')
    for k,(x,y) in CITY.items():
        o.append(f'<rect x="{x-6}" y="{y-6}" width="12" height="12" fill="#ece6d8" stroke="#0c0f0e" stroke-width="2"></rect>')
    # 군단·계책
    o.append(corps(368,270,F['jo'],'하후돈 군단',fs,'성 없는 ⟦PROV⟧' if sub else ''))
    o.append(corps(560,55,F['won'],'적 군단',fs,'시야 안 · 요격 가능' if vision else ''))
    if vision:
        o.append(f'<g opacity=".55"><path d="M890 {130-14}L903 130L890 144L877 130Z" fill="none" stroke="#c96b5d" stroke-width="2" stroke-dasharray="3 3"></path><text x="820" y="168" font-size="{fs-2}" fill="#e08a7c" stroke="#0c0f0e" stroke-width="3" paint-order="stroke">시야 밖 — 요격 불가</text></g>')
    o.append(f'<g><circle cx="470" cy="195" r="13" fill="#0c0f0e" stroke="#7aa7c7" stroke-width="2" stroke-dasharray="4 3"></circle><path d="M462 195c3-5 13-5 16 0c-3 5-13 5-16 0M463 203L477 187" fill="none" stroke="#7aa7c7" stroke-width="1.8"></path><text x="488" y="192" font-size="{fs-1}" fill="#7aa7c7" stroke="#0c0f0e" stroke-width="3" paint-order="stroke">설치 · 매복</text>'+(f'<text x="488" y="{192+fs+2}" font-size="{fs-2}" fill="#8a8477" stroke="#0c0f0e" stroke-width="3" paint-order="stroke">나에게만 보인다</text>' if sub else '')+'</g>')
    o.append('</svg>')
    return ''.join(o)

