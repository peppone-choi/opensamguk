import json,os,datetime
from names import apply_terms, PROVINCE_TERM, ko as place_ko
CARD_HANJA={'의병':'疑兵'}   # 한글만으로는 뜻이 갈리는 카드 이름 — 이름 뒤에 작게 붙인다
def hj(n): return f' <span class="muted" style="font-family:inherit;font-size:10px;font-weight:400">{CARD_HANJA[n]}</span>' if n in CARD_HANJA else ''
R=os.path.dirname(os.path.abspath(__file__)); P=os.path.join(R,'project'); os.makedirs(P,exist_ok=True)
# 공용 부품. 아트보드 스크립트는 'from ui import *' 로 쓴다. page(name,title,body,w,h) 가 project/<name> 을 쓴다.
PT={'jojo':'/_blob/b965f9c62e2764ad338852339c51bd94','hahoudon':'/_blob/bfc1849a76733e1e68402cec98bb4d7e','sunuk':'/_blob/aa0cb4edb6dc533600b2855a39b804f8','heojeo':'/_blob/bd282a61eb4928ba21f1e69e6d6cac99','join':'/_blob/c3ab12b126b97614378879ab621a1857','ijeon':'/_blob/9e43022fa6020acb4e26e31eba12f041'}
CSS='''
body{margin:0;background:#0c0f0e;color:#ece6d8;font-family:'Noto Sans KR','Apple SD Gothic Neo',system-ui,sans-serif;font-size:14px;line-height:1.5;-webkit-font-smoothing:antialiased}
*{box-sizing:border-box} a{color:#d3b064;text-decoration:none} a:hover{color:#ffd36d}
.serif{font-family:'Noto Serif KR','Nanum Myeongjo',serif} .mono{font-family:'JetBrains Mono',ui-monospace,Menlo,monospace;font-variant-numeric:tabular-nums}
.panel{background:#1b201d;border:1px solid #2c342f;display:flex;flex-direction:column;min-height:0}
.inset{background:#141816;border:1px solid #2c342f}
.sec-h{display:flex;align-items:center;gap:10px;height:36px;padding:0 12px;border-bottom:1px solid #2c342f;background:linear-gradient(180deg,#232a26,#1b201d);flex-shrink:0}
.sec-h .bar{width:3px;height:14px;background:#d3b064}.sec-h .t{font-family:'Noto Serif KR',serif;font-weight:700;font-size:14px}.sec-h .sub{font-size:11px;color:#8a8477;margin-left:auto}
.chip{display:inline-flex;align-items:center;gap:4px;height:20px;padding:0 7px;font-size:11px;font-weight:500;border:1px solid #3d4740;color:#b9b2a3;background:#141816;white-space:nowrap}
.chip.bronze{color:#d3b064;border-color:#9c7f3f;background:rgba(211,176,100,.10)}.chip.moss{color:#8fa77a;border-color:#697e58;background:rgba(105,126,88,.16)}
.chip.rust{color:#e08a7c;border-color:#c96b5d;background:rgba(201,107,93,.14)}.chip.info{color:#7aa7c7;border-color:#4b6d87;background:rgba(122,167,199,.12)}
.btn{display:inline-flex;align-items:center;justify-content:center;gap:6px;height:44px;padding:0 16px;font:inherit;font-size:13px;font-weight:500;color:#ece6d8;background:#232a26;border:1px solid #3d4740;cursor:pointer;white-space:nowrap}
.btn.primary{background:linear-gradient(180deg,#e2c37a,#c9a656);color:#161410;font-weight:700;border-color:#9c7f3f}
.btn.danger{background:rgba(201,107,93,.12);color:#e08a7c;border-color:#c96b5d}.btn.sm{height:32px;padding:0 10px;font-size:12px}
.tabs{display:flex;gap:2px}.tabs span{display:inline-flex;align-items:center;height:32px;padding:0 12px;font-size:12px;color:#b9b2a3;border:1px solid #2c342f;background:#141816}
.tabs span.on{color:#161410;background:#d3b064;border-color:#9c7f3f;font-weight:700}
.g-bar{height:8px;background:#141816;border:1px solid #2c342f;position:relative}.g-bar i{position:absolute;left:0;top:0;bottom:0;background:linear-gradient(90deg,#9c7f3f,#d3b064)}
.table{width:100%;border-collapse:collapse;font-size:12px}.table th{height:32px;padding:0 10px;text-align:left;color:#8a8477;font-weight:500;background:#141816;border-bottom:1px solid #3d4740;white-space:nowrap}
.table td{height:44px;padding:0 10px;border-bottom:1px solid #2c342f;white-space:nowrap}.table tr.me td{background:rgba(211,176,100,.08)}
.pt{width:44px;height:62px;object-fit:cover;object-position:top center;border:1px solid #3d4740;display:block;background:#141816}
.muted{color:#8a8477}.t2{color:#b9b2a3}.bz{color:#d3b064}.ms{color:#8fa77a}.rs{color:#e08a7c}
.cost{display:inline-flex;align-items:center;gap:4px;font-size:11px}.cost i{width:9px;height:9px;display:inline-block;border:1px solid rgba(0,0,0,.4)}
'''
COL={'금':'#d3b064','쌀':'#8fa77a','철':'#9aa3a8','목재':'#a5744a','말':'#c96b5d'}
def cost(c): return '' if c=='—' else f'<span class="cost"><i style="background:{COL[c]}"></i>{c}</span>'
def head(title,on):
    tabs=''.join(f'<span class="{"on" if t==on else ""}">{t}</span>' for t in ['장수 행동','배치','방침','공사','계책','조정 결정'])
    return f'''<div style="height:56px;display:flex;align-items:center;justify-content:space-between;padding:0 20px;border-bottom:1px solid #3d4740;background:linear-gradient(180deg,#232a26,#1b201d);flex-shrink:0">
<div style="display:flex;align-items:center;gap:14px"><a class="btn sm" href="#">← 작전실</a><span class="serif" style="font-size:18px;font-weight:900">{title}</span><div class="tabs">{tabs}</div></div>
<div style="display:flex;align-items:center;gap:8px"><span class="chip">하후돈 · 조조 휘하</span><span class="chip bronze">명망 [미정]</span><span class="chip">200년 3월 중순</span></div></div>'''
def sec(t,sub=''): return f'<div class="sec-h"><span class="bar"></span><span class="t">{t}</span><span class="sub">{sub}</span></div>'
def page(name,title,body,w=1440,h=1000):
    doc=f'''<!doctype html>
<html lang="ko">
<head>
<meta charset="utf-8">
<title>{title}</title>
<script src="./support.js"></script>
</head>
<body>
<x-dc>
<helmet>
<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Noto+Serif+KR:wght@700;900&amp;family=Noto+Sans+KR:wght@400;500;700&amp;family=JetBrains+Mono:wght@500;700&amp;display=swap">
<style>{CSS}</style>
</helmet>
<div style="width: {w}px; height: {h}px; background: #0c0f0e; display: flex; flex-direction: column; overflow: hidden;">
{body}
</div>
</x-dc>
<script type="text/x-dc" data-dc-script data-props='{{"$preview":{{"width":{w},"height":{h}}}}}'>
class Component extends DCLogic {{
renderVals() {{ return {{}}; }}
}}
</script>
</body>
</html>
'''
    open(os.path.join(P,name),'w',encoding='utf-8').write(apply_terms(doc))


ART={'매복':'/_blob/1d81436ec25f68825bc721a382a9bbdd','의병':'/_blob/e7d809b260db0e57d20ca48f9d40285a','화계':'/_blob/23818f355d14fdf3313104affdd06de2','첩보':'/_blob/20999df936a61cb5ac7093c35d066e56','견벽':'/_blob/b358d34476b501ec30ab29b32b152956','간파':'/_blob/7dc9f1ab50a246bbf1c581b97b9e6a29'}
FIELD='/_blob/25b6e78333ef9054ce1d3b8175aa00bb'
mode={'즉시':'bronze','설치':'info','대응':'moss'}
FORMS=['선형','종대','방진','안행','추행']
def mod(a,b,c='t2'): return f'<div style="display:flex;justify-content:space-between;gap:8px;font-size:12px;padding:6px 0;border-bottom:1px solid #2c342f"><span class="t2">{a}</span><span class="{c}">{b}</span></div>'
def smallcard(n,m,art,on=False): return f'<div style="width:112px;background:#1b201d;border:1px solid {"#d3b064" if on else "#3d4740"};display:flex;flex-direction:column;flex-shrink:0"><img src="{ART[art]}" alt="{n} 카드 그림" style="width:110px;height:120px;object-fit:cover;display:block"><div style="padding:6px;display:flex;flex-direction:column;gap:4px"><span class="serif" style="font-size:13px;font-weight:900">{n}{hj(n)}</span><span class="chip {mode[m]}" style="align-self:flex-start">{m}</span></div></div>'
def kv(a,b,c=''): return f'<div class="inset" style="padding:8px;display:flex;flex-direction:column;gap:2px"><span class="muted" style="font-size:10px">{a}</span><span style="font-size:13px" class="{c}">{b}</span></div>'
