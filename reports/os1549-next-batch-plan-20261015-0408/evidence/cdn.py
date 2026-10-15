import sys, urllib.request, concurrent.futures as cf
B="https://cdn.jsdelivr.net/gh/peppone-choi/opensamguk-images/portraits/rtk14/serving"
jobs=[f"{v}/{i}.{e}" for i in (10399,10019,10909,10245,10810) for v,e in (("original","jpg"),("portrait","png"),("icon","png"))]
def get(p):
    for attempt in range(3):
        try:
            with urllib.request.urlopen(B+"/"+p, timeout=40) as r:
                b=r.read(); return f"{p} {r.status} {r.headers.get('content-type')} {len(b)} magic={b[:4].hex()}"
        except Exception as e: err=repr(e)
    return f"{p} FAIL {err}"
with cf.ThreadPoolExecutor(6) as ex:
    for line in ex.map(get, jobs): print(line, flush=True)
