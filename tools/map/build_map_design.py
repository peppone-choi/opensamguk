#!/usr/bin/env python3
"""지도 설계 층(map design layer) v1 — 강·城 위치 수정·산 편집·산 높이·땅 피복.

실제 지형(`data/map/han-tiles.json`)은 **입력**이고, 이 도구가 만드는 설계 층이 화면과
규칙이 함께 읽는 **정본**이다(ADR-LITE-044 개정 2). 설계 방법은 지도 일반화 다섯 가지 —
합치기·드러내기·과장하기·줄이기·생략하기 — 이다. 계획: 메타 `docs/map-design-plan.md`.

산출(`data/curated/han/map-design/`)
    rivers-v1.json       사료로 확인한 강 36개의 중심선 칸(4-연결 한 줄)·위계·폭 규칙·출처
    placements-v1.json   과장한 강 폭에 잠긴 城을 기슭으로 옮기는 안(사유·출처)
    mountains-v1.json    관 양옆 능선·이름난 산 과장 칸(사유·출처)
    relief-v1.json       산 높이 0–3단(표고로 산 범위를 고치고 단을 나눈다) 매개변수와 결과 지문
    landcover-v1.json    논·밭·숲·마을 피복 생성 매개변수와 결과 지문(sha256)

입력
    data/map/han-tiles.json                       지형·소유·縣 좌표·투영
    infra/src/main/resources/map/han-world-v3.json  城 1447
    data/map/han-land-roads-v1.json               건설된 길
    data/curated/han/county-economy-inputs-v1.json 縣 호구·경작 칸
    web/game/public/map/elevation/han-world-v3-metres.png  표고(NOAA ETOPO1, 퍼블릭 도메인, 768×669 = 우리 칸 ×¼)
    data/curated/han/map-design/river-waypoints-v1.json  NE에 없는 강의 경유지(水經注·웹 출처) — 판정 입력
    data/curated/han/map-design/river-dodge-v1.json      城 대신 강을 비킬 27곳 — 판정 입력
    data/natural-earth/ne_10m_rivers_lake_centerlines.geojson  **gitignored**. 강 선 재생성에만 쓴다.

    MAP_DESIGN_NE10M=<경로> python3 tools/map/build_map_design.py --build  # 강 선부터 전부(NE 10m 있는 로컬)
    python3 tools/map/build_map_design.py --write-derived  # 커밋된 강 선에서 위치·산 편집·산 높이·피복만(han-tiles 바뀐 뒤)
    python3 tools/map/build_map_design.py --check          # 불변식 + 결정적 재계산 대조(CI, 결합 목록)
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import sys
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "data/curated/han/map-design"
HAN_TILES = ROOT / "data/map/han-tiles.json"
WORLD = ROOT / "infra/src/main/resources/map/han-world-v3.json"
ROADS = ROOT / "data/map/han-land-roads-v1.json"
ECONOMY = ROOT / "data/curated/han/county-economy-inputs-v1.json"
WAYPOINTS = "river-waypoints-v1.json"      # 설계 판정 입력(사람이 고친다)
DODGE = "river-dodge-v1.json"
RIVERS, PLACEMENTS, MOUNTAINS, LANDCOVER = "rivers-v1.json", "placements-v1.json", "mountains-v1.json", "landcover-v1.json"
RELIEF = "relief-v1.json"
NE10M = Path(os.environ.get("MAP_DESIGN_NE10M", ROOT / "data/natural-earth/ne_10m_rivers_lake_centerlines.geojson"))

# 城 성내 한 변(칸). 장현 1 · 영현 3 · 소 5 · 중 7 · 대 9 · 특 11 · 경 13, 수·진·관·이 1 (2026-09-26 사용자 안 B)
SPAN = {9: 13, 8: 11, 7: 9, 6: 7, 5: 5, 10: 3}
TERRAIN_SEA, TERRAIN_PLAIN, TERRAIN_MOUNTAIN, TERRAIN_RIVER, TERRAIN_LAKE = 0, 1, 2, 3, 4
TERRAIN_PLATEAU, TERRAIN_BASIN, TERRAIN_HILL, TERRAIN_OUT = 6, 7, 8, 9
MOVE_LIMIT = 12
FERRY_LIMIT = 6

# ── 입력 ─────────────────────────────────────────────────────────────────────────────
_CACHE: dict = {}


def load_inputs() -> dict:
    if _CACHE:
        return _CACHE
    ht = json.loads(HAN_TILES.read_text())
    proj = ht["_meta"]["projection"]
    terrain = np.array([np.frombuffer(r.encode(), np.uint8) - 48 for r in ht["terrain"]], np.uint8)
    h, w = terrain.shape
    flat = np.empty(h * w, np.int32)
    i = 0
    for p, n in ht["owner"]:
        flat[i:i + n] = p
        i += n
    owner = flat.reshape(h, w)
    tiles_city = {str(c["id"]): c for c in ht["cities"]}
    world = json.loads(WORLD.read_text())
    cities = []
    for c in world["cities"]:
        t = tiles_city.get(str(c.get("spatialProvinceId"))) or tiles_city.get(str(c.get("physicalPlaceRef", "")).split(":")[-1])
        if t is None:
            raise ValueError(f"城 {c['id']} {c['name']}: han-tiles 좌표를 찾지 못했다")
        cities.append(dict(id=c["id"], name=c["name"], level=c["level"], row=int(t["row"]), col=int(t["col"])))
    road = np.zeros((h, w), bool)
    for e in json.loads(ROADS.read_text())["edges"]:
        if e["status"] != "BUILT":
            continue
        for r, cc in (e.get("fromTrail") or []) + (e.get("toTrail") or []):
            road[r, cc] = True
    _CACHE.update(ht=ht, proj=proj, terrain=terrain, owner=owner, cities=cities, road=road, world=world)
    return _CACHE


def proj_rc(lon: float, lat: float, P: dict) -> tuple[float, float]:
    return ((P["y1"] + P["pad"] - lat) / P["cell"], (lon * P["k"] - P["x0"] + P["pad"]) / P["cell"])


def unproj_lon(col: float, P: dict) -> float:
    return (col * P["cell"] - P["pad"] + P["x0"]) / P["k"]


def sha256_bytes(b: bytes) -> str:
    return hashlib.sha256(b).hexdigest()


def noise(y: int, x: int, salt: str = "") -> float:
    return int(hashlib.md5(f"{salt}{y},{x}".encode()).hexdigest()[:4], 16) / 0xFFFF


# ── 강 ───────────────────────────────────────────────────────────────────────────────
# NE 10m 이름 → (漢代 이름, 위계, 사료 근거). 위계 1=과장 폭, 2=3칸, 3=한 줄.
NE_RIVERS = {
    "Huang": ("河", 1, "三國志 卷01 渡河 · 孟津 三國志 15권 — 孟津 상류는 현대 물길로 근사"),
    "Chang Jiang": ("江", 1, "三國志 卷54 渡江·夏口·赤壁"),
    "Yangtze": ("江", 1, "三國志 卷54 渡江"),
    "Jinsha": ("瀘水", 2, "後漢書 卷86 度瀘水"),
    "Min": ("江(汶江)", 2, "後漢書 卷113 郡國志 汶江道 — 漢代 江의 원류로 봄(확인 필요)"),
    "Han": ("沔水(漢水)", 2, "三國志 卷36 漢水 · 沔水 三國志 6권"),
    "Huai": ("淮水", 2, "後漢書 卷111 郡國志 淮水"),
    "Wei": ("渭水", 2, "後漢書 卷113 郡國志 渭水"),
    "Xiang": ("湘水", 2, "三國志 卷54 湘水"),
    "Yuan": ("沅水", 2, "後漢書 卷001B 沅水"),
    "Gan": ("豫章水", 2, "後漢書 卷112 郡國志 贛 有豫章水"),
    "Liao": ("遼水", 2, "三國志 卷03 遼水"),
    "Fen": ("汾水", 3, "後漢書 卷113 郡國志 汾水"),
    "Zhang": ("漳水", 3, "後漢書 卷074B 漳水"),
    "Jing": ("涇水", 3, "後漢書 卷040A 涇水"),
    "Qin": ("沁水", 3, "後漢書 卷109 郡國志 沁水"),
    "Ying": ("潁水", 3, "後漢書 卷110 郡國志 潁水"),
    "Huan": ("洹水", 3, "後漢書 卷109 郡國志 洹水"),
    "Dan": ("丹水", 3, "後漢書 卷112 郡國志 丹水"),
    "Jialing": ("閬水", 3, "三國志 卷41 由閬水上"),
    "Yi": ("伊水", 3, "後漢書 卷109 郡國志 伊水 — 위치로 伊/沂 판별"),
}
TIER2 = {"瀘水", "沔水(漢水)", "沅水", "湘水", "豫章水", "渭水", "遼水", "江(汶江)", "淮水", "泗水", "洛水"}
# 東漢 河 하류: 孟津 하류를 수로망 나루 노드(han-waterway-network-v1, 768 격자 ×4)를 경로점으로 다시 긋는다
HE_EAST_WAYPOINTS = [(230 * 4 + 2, 378 * 4 + 2), (232 * 4 + 2, 382 * 4 + 2), (219 * 4 + 2, 406 * 4 + 2),
                     (202 * 4 + 2, 436 * 4 + 2), (760, 1880), (690, 1943)]
HE_EAST_NOTE = "孟津·小平津·延津·倉亭津(han-waterway-network-v1 노드) 경유, 하구는 千乘 부근 근사 — 東漢 물길 대조 필요"
EXTRA_TIER = {"泗水": 2, "洛水": 2}
EXTRA_ORDER = ["泗水", "洛水", "汝水", "濟水", "汴水", "鴻溝", "睢水", "淯水", "涪水", "白水", "沮水", "資水", "夏水", "滹沱", "易水", "淇水"]
JOIN_MAP = {"河": "河", "淮": "淮水", "淮水": "淮水", "沔水": "沔水(漢水)", "沔水(漢水)": "沔水(漢水)",
            "西漢水(嘉陵)": "閬水", "西漢水(嘉陵江)": "閬水", "嘉陵江": "閬水", "閬水": "閬水",
            "滹沱(文安)": "滹沱", "滹沱": "滹沱", "泗水": "泗水", "江": "江", "洞庭": "洞庭", "海": "海"}
DESIGN_OVERRIDES = {
    "濟水": dict(drop=["溫", "温"], note="溫(河 북안) 구간 생략 — 河를 건너는 구간을 그리지 않는다"),
    "鴻溝": dict(start_at=["浚儀", "浚仪"], note="滎陽→官渡→浚儀는 汴水와 같은 물길이라 합친다(浚儀부터)"),
}
WRAPS = [dict(city="낭중", river="閬水", path=["W", "S", "E"], R=4.0,
              source="閬中古城: 嘉陵江이 서·남·동 三面을 U자로 감싼다, 북은 蟠龍山 — https://baike.baidu.com/item/%E9%98%86%E4%B8%AD%E5%8F%A4%E5%9F%8E/7504387")]


def width_of(name: str, tier: int, lon: float) -> int:
    """과장 폭(칸). 江·河는 하류로 갈수록 넓게, 三峽은 좁은 협곡으로."""
    if name == "江":
        if 109.3 <= lon < 111.4:
            return 3
        return 5 if lon < 108 else (7 if lon < 114 else 9)
    if name == "河":
        return 4 if lon < 110 else (5 if lon < 114 else 6)
    return {1: 5, 2: 3, 3: 1}[tier]


def tier_of(name: str) -> int:
    return 1 if name in ("江", "河") else (2 if name in TIER2 else 3)


def densify(pts, step=0.5):
    out = [tuple(pts[0])]
    for a, b in zip(pts, pts[1:]):
        a = np.array(a, float); b = np.array(b, float)
        n = int(np.hypot(*(b - a)) / step) + 1
        for t in np.linspace(0, 1, n + 1)[1:]:
            out.append(tuple(a + (b - a) * t))
    return out


def catmull(pts, n=24):
    P = [pts[0]] + list(pts) + [pts[-1]]
    out = []
    for i in range(1, len(P) - 2):
        p0, p1, p2, p3 = [np.array(q, float) for q in P[i - 1:i + 3]]
        for t in np.linspace(0, 1, n, endpoint=False):
            out.append(0.5 * ((2 * p1) + (-p0 + p2) * t + (2 * p0 - 5 * p1 + 4 * p2 - p3) * t * t + (-p0 + 3 * p1 - 3 * p2 + p3) * t ** 3))
    out.append(np.array(P[-2], float))
    return out


def meander(pts, anchors, amp=8, wave=60, seed="r"):
    """중심선에 수직으로 굽이를 준다. 경로점 가까이서는 0으로 줄인다."""
    P = np.array(pts, float)
    d = np.r_[0, np.cumsum(np.hypot(*np.diff(P, axis=0).T))]
    tang = np.gradient(P, axis=0)
    tang /= np.maximum(np.hypot(tang[:, 0], tang[:, 1]), 1e-9)[:, None]
    nrm = np.c_[-tang[:, 1], tang[:, 0]]
    ph = int(hashlib.md5(seed.encode()).hexdigest()[:6], 16) % 628 / 100
    off = amp * (np.sin(2 * np.pi * d / wave + ph) * 0.75 + np.sin(2 * np.pi * d / (wave * 0.37) + ph * 1.7) * 0.25)
    A = np.array(anchors, float)
    da = np.min(np.hypot(P[:, None, 0] - A[None, :, 0], P[:, None, 1] - A[None, :, 1]), axis=1)
    off *= np.clip(da / 18, 0, 1)
    return [tuple(p) for p in P + nrm * off[:, None]]


def raster_cells(pts):
    """점 목록을 4-연결 한 줄 칸으로."""
    out, prev = [], None
    for r, c in pts:
        rc = (int(round(r)), int(round(c)))
        if prev is None:
            out.append(rc)
        elif rc != prev:
            y, x = prev
            while (y, x) != rc:
                if x != rc[1]:
                    x += 1 if rc[1] > x else -1
                elif y != rc[0]:
                    y += 1 if rc[0] > y else -1
                out.append((y, x))
        prev = rc
    return clean_path(out)


def clean_path(cells, window=40):
    """4-연결 경로에서 되돌이·2×2를 없앤다. 반올림이 .5 경계에서 오락가락하면 경로가 제 몸에 닿는다.
    앞 칸과 4-이웃인 가장 먼 뒤 칸(window 안)으로 건너뛰면, 이웃한 칸은 언제나 연달아 있게 되어 2×2가 생길 수 없다."""
    ded = [cells[0]]
    for q in cells[1:]:
        if q != ded[-1]:
            ded.append(q)
    out, i, n = [], 0, len(ded)
    while i < n:
        out.append(ded[i]); y, x = ded[i]; j = i + 1
        for k in range(min(n - 1, i + window), i + 1, -1):
            if abs(ded[k][0] - y) + abs(ded[k][1] - x) <= 1:
                j = k if ded[k] != ded[i] else k + 1
                break
        i = j
    return out


def _span_near(q, cities):
    best = 1
    for c in cities:
        if abs(c["row"] - q[0]) <= 1 and abs(c["col"] - q[1]) <= 1:
            best = max(best, SPAN.get(c["level"], 1))
    return best


def gather_lines(inp: dict, out: Path = OUT) -> list[dict]:
    """NE 10m 중심선(사료 확인 강) + 東漢 河 하류 + 경유지 강 → 선 목록(격자 좌표 점)."""
    P = inp["proj"]
    if not NE10M.exists():
        raise FileNotFoundError(f"{NE10M} 가 없다 — 강 선 재생성은 NE 10m 이 있는 로컬에서만 한다")
    d = json.loads(NE10M.read_text())
    L = []
    for f in d["features"]:
        g = f["geometry"]; p = f["properties"]; n = p.get("name")
        if not g or n not in NE_RIVERS:
            continue
        lines = [g["coordinates"]] if g["type"] == "LineString" else g["coordinates"]
        pts = [q for l in lines for q in l]
        if not any(100 <= x <= 125 and 20 <= y <= 44 for x, y in pts):
            continue
        han, tr, src = NE_RIVERS[n]
        if n == "Yi":
            mx = float(np.mean([x for x, _ in pts]))
            han, src = ("伊水", "後漢書 卷109 郡國志 伊水") if mx < 114 else ("沂水", "後漢書 卷111 郡國志 沂水")
        for l in lines:
            rc = [proj_rc(x, y, P) for x, y in l]
            if han == "河":
                cut = HE_EAST_WAYPOINTS[0][1]
                rc = [q for q in rc if q[1] < cut]
                if len(rc) < 2:
                    continue
            L.append(dict(name=han, tier=tr, source=src, origin=f"NE10m:{n}", pts=densify(rc)))
    east = meander(catmull(HE_EAST_WAYPOINTS, 40), HE_EAST_WAYPOINTS, amp=9, wave=70, seed="he")
    L.append(dict(name="河", tier=1, source=HE_EAST_NOTE, origin="design:he-east", pts=densify(east)))
    # 경유지 강(水經注·웹 출처)
    W = {x["river"]: x for x in json.loads((out / WAYPOINTS).read_text())}
    T = inp["terrain"]
    for han in EXTRA_ORDER:
        v = W.get(han)
        if not v or len(v["waypoints"]) < 2:
            continue
        wps = list(v["waypoints"]); ov = DESIGN_OVERRIDES.get(han, {})
        if "drop" in ov:
            wps = [w for w in wps if not any(k in w["place"] for k in ov["drop"])]
        if "start_at" in ov:
            idx = [i for i, w in enumerate(wps) if any(k in w["place"] for k in ov["start_at"])]
            if idx:
                wps = wps[idx[0]:]
        if len(wps) < 2:
            continue
        tr = EXTRA_TIER.get(han, 3)
        raw = np.array([(float(w["row"]), float(w["col"])) if w.get("row") is not None else proj_rc(w["lon"], w["lat"], P) for w in wps], float)
        hwv = (width_of(han, tr, unproj_lon(raw[0][1], P)) - 1) / 2
        wp = []
        for i, q in enumerate(raw):
            a = raw[max(0, i - 1)]; b = raw[min(len(raw) - 1, i + 1)]; t = b - a
            t /= max(np.hypot(*t), 1e-9)
            nrm = np.array([-t[1], t[0]])
            if wps[i].get("match") == "counties":
                off = hwv + _span_near(q, inp["cities"]) // 2 + 1.5   # 경유 縣 城은 곁을 지나게 비켜 찍는다
            else:
                off = 0.0
            wp.append(tuple(q + nrm * off))
        join = JOIN_MAP.get(v["joins"], v["joins"]); end = raw[-1]; tgt = None
        if join in ("海", "洞庭"):
            code = TERRAIN_SEA if join == "海" else TERRAIN_LAKE
            r0, c0 = int(end[0]), int(end[1]); R = 160
            sub = T[max(0, r0 - R):r0 + R, max(0, c0 - R):c0 + R]; yy, xx = np.where(sub == code)
            if len(yy):
                k = int(np.argmin((yy + max(0, r0 - R) - end[0]) ** 2 + (xx + max(0, c0 - R) - end[1]) ** 2))
                tgt = np.array([yy[k] + max(0, r0 - R), xx[k] + max(0, c0 - R)], float)
        else:
            cand = [np.array(l["pts"]) for l in L if l["name"] == join]
            if cand:
                A = np.vstack(cand); k = int(np.argmin(((A - end) ** 2).sum(1))); tgt = A[k]
        if tgt is not None and np.hypot(*(tgt - end)) < 70:
            wp = list(wp) + [tuple(tgt)]
        pts = meander(catmull(wp, 30), wp, amp=5 if tr == 2 else 3, wave=50, seed=han)
        L.append(dict(name=han, tier=tr, source=f"경유지 {WAYPOINTS}({len(wps)}점)" + (f" · {ov['note']}" if ov else ""),
                      origin="waypoints", pts=densify(pts)))
    return L


def apply_wraps(L, inp):
    byname = {c["name"]: c for c in inp["cities"]}
    DIR = {"N": (-1, 0), "S": (1, 0), "W": (0, -1), "E": (0, 1)}
    for wr in WRAPS:
        c = byname.get(wr["city"])
        if not c:
            continue
        P = np.array([c["row"], c["col"]], float)
        cands = [ln for ln in L if ln["name"] == wr["river"]]
        if not cands:
            continue
        ln = min(cands, key=lambda l: np.min(np.hypot(*(np.array(l["pts"]) - P).T)))
        A = np.array(ln["pts"]); d = np.hypot(*(A - P).T); near = np.where(d < 14)[0]
        if len(near) == 0:
            continue
        i0, i1 = max(0, near.min() - 1), min(len(A) - 1, near.max() + 1)
        ctrl = [tuple(A[i0])] + [tuple(P + np.array(DIR[k]) * wr["R"]) for k in wr["path"]] + [tuple(A[i1])]
        rounded = [ctrl[0]]
        for a, b in zip(ctrl[1:-2], ctrl[2:-1]):
            mid = np.array(a) + np.array(b) - 2 * P
            rounded += [a, tuple(P + mid / np.hypot(*mid) * wr["R"])]
        rounded += [ctrl[-2], ctrl[-1]]
        arc = densify(catmull(rounded, 12))
        ln["pts"] = [tuple(q) for q in A[:i0]] + list(arc) + [tuple(q) for q in A[i1 + 1:]]


def dodge(L, targets, sigma=10.0):
    """targets: [(row, col, need)]. 城 가까이 온 선 점을 need 까지 밀고 선을 따라 부드럽게 퍼뜨린다."""
    for ln in L:
        P = np.array(ln["pts"], float); D = np.zeros_like(P); hit = False
        for tr_, tc_, need in targets:
            v = P - np.array([tr_, tc_]); d = np.hypot(v[:, 0], v[:, 1]); m = d < need
            if not m.any():
                continue
            hit = True
            u = v[m] / np.maximum(d[m], 1e-6)[:, None]
            zero = d[m] < 0.5
            if zero.any():
                tg = np.gradient(P, axis=0)[m][zero]
                tg /= np.maximum(np.hypot(tg[:, 0], tg[:, 1]), 1e-9)[:, None]
                u[zero] = np.c_[-tg[:, 1], tg[:, 0]]
            D[m] += (np.array([tr_, tc_]) + u * need) - P[m]
        if not hit:
            continue
        s_ = np.r_[0, np.cumsum(np.hypot(*np.diff(P, axis=0).T))]
        Wt = np.exp(-((s_[:, None] - s_[None, :]) ** 2) / (2 * sigma * sigma))
        Ds = (Wt @ D) / np.maximum(Wt.sum(1), 1e-9)[:, None]
        mx0 = np.hypot(D[:, 0], D[:, 1]).max(); mx1 = np.hypot(Ds[:, 0], Ds[:, 1]).max()
        if mx1 > 1e-6:
            Ds *= mx0 / mx1
        ln["pts"] = [tuple(q) for q in P + Ds]


def lines_to_grid(lines, shape, P):
    tier = np.zeros(shape, np.int8); width = np.zeros(shape, np.int8); name = np.full(shape, "", dtype="<U8")
    for ln in lines:
        for r, c in ln["cells"]:
            if 0 <= r < shape[0] and 0 <= c < shape[1] and (tier[r, c] == 0 or ln["tier"] < tier[r, c]):
                tier[r, c] = ln["tier"]; name[r, c] = ln["name"]; width[r, c] = width_of(ln["name"], ln["tier"], unproj_lon(c, P))
    return tier, width, name


def submerged_ok(d: float, span: int) -> bool:
    """강 물가까지 거리 d(칸, 중심선 거리 − 반폭). 1칸 城은 물가에 붙어도 된다."""
    return d >= (span // 2 + 0.5 if span >= 3 else 0.3)


class RiverIndex:
    def __init__(self, tier, width):
        self.ys, self.xs = np.where(tier > 0)
        self.hw = np.maximum(width[self.ys, self.xs] - 1, 0) / 2

    def dist(self, r, c):
        d = np.hypot(self.ys - r, self.xs - c) - self.hw
        k = int(np.argmin(d))
        return float(d[k]), k


def compute_placements(inp, tier, width, name):
    """잠긴 城 → 같은 省 안 기슭(≤12칸). 口·津 1칸 거점은 省을 넘어 6칸 안 물가(FERRY_MOUTH)."""
    own, T = inp["owner"], inp["terrain"]; ri = RiverIndex(tier, width); res = []
    for city in inp["cities"]:
        s = SPAN.get(city["level"], 1); b = s // 2; r0, c0 = city["row"], city["col"]
        d, k = ri.dist(r0, c0)
        if submerged_ok(d, s):
            continue
        p = np.array([ri.ys[k], ri.xs[k]], float); v = np.array([r0, c0], float) - p; L = np.hypot(*v)
        if L < 1e-6:
            v = np.array([1.0, 0.0]); L = 1.0
        v /= L; placed = None
        for sgn, label in ((1, "SAME_BANK"), (-1, "OTHER_BANK")):
            for e in (0, 1, 2, 3):
                q = p + sgn * v * (ri.hw[k] + b + (1.5 if s >= 3 else 0.8) + e); r, c = int(round(q[0])), int(round(q[1]))
                if not (0 <= r < own.shape[0] and 0 <= c < own.shape[1]) or own[r, c] != own[r0, c0] or T[r, c] == TERRAIN_OUT:
                    continue
                if not submerged_ok(ri.dist(r, c)[0], s):
                    continue
                mv = float(np.hypot(r - r0, c - c0))
                if mv > MOVE_LIMIT:
                    continue
                placed = (r, c, round(mv, 1), label); break
            if placed:
                break
        base = dict(cityId=city["id"], name=city["name"], level=city["level"], span=s, river=str(name[ri.ys[k], ri.xs[k]]), frm=[r0, c0])
        if placed:
            res.append(dict(base, to=[placed[0], placed[1]], move=placed[2], side=placed[3], reason="RIVER_BANK", status="PROPOSED"))
            continue
        if s == 1 and any(ch in city["name"] for ch in ("구", "진")):
            best = None
            for dr in range(-FERRY_LIMIT, FERRY_LIMIT + 1):
                for dc in range(-FERRY_LIMIT, FERRY_LIMIT + 1):
                    rr, cc = r0 + dr, c0 + dc
                    if T[rr, cc] in (TERRAIN_SEA, TERRAIN_LAKE, TERRAIN_OUT):
                        continue
                    dd, _ = ri.dist(rr, cc)
                    if 0.3 <= dd <= 1.2:
                        mv = float(np.hypot(dr, dc))
                        if best is None or mv < best[2]:
                            best = (rr, cc, mv)
            if best:
                res.append(dict(base, to=[best[0], best[1]], move=round(best[2], 1), side="WATERSIDE", reason="FERRY_MOUTH",
                                status="PROPOSED", provinceChanged=bool(own[best[0], best[1]] != own[r0, c0]),
                                note="口·津 나루 거점은 물가에 둔다 — 省 경계 예외"))
                continue
        res.append(dict(base, to=None, move=None, side=None, reason="RIVER_BANK", status="NEEDS_REVIEW"))
    return res


def build_rivers(inp, out: Path = OUT):
    """선 모으기 → 감싸기 → 판정 城(river-dodge-v1) 비키기 4회 → 칸. 모자란 거리는 반복마다 더 민다."""
    P = inp["proj"]; shape = inp["terrain"].shape; byid = {c["id"]: c for c in inp["cities"]}
    dodge_doc = json.loads((out / DODGE).read_text())
    need = {}
    for x in dodge_doc["cities"]:
        c = byid[x["cityId"]]; s = SPAN.get(c["level"], 1)
        need[c["id"]] = (width_of(x["river"], tier_of(x["river"]), unproj_lon(c["col"], P)) - 1) / 2 + s // 2 + 1.5
    extra = {k: 0.0 for k in need}; best = None
    for _it in range(4):
        used = {k: round(v + extra[k], 3) for k, v in need.items()}
        L = gather_lines(inp, out); apply_wraps(L, inp)
        dodge(L, [(byid[k]["row"], byid[k]["col"], need[k] + extra[k]) for k in need])
        lines = [dict(name=l["name"], tier=l["tier"], source=l["source"], origin=l["origin"], cells=raster_cells(l["pts"])) for l in L]
        ri = RiverIndex(*lines_to_grid(lines, shape, P)[:2]); bad = []
        for k in need:
            c = byid[k]; s = SPAN.get(c["level"], 1); d, _ = ri.dist(c["row"], c["col"])
            if not submerged_ok(d, s):
                extra[k] += ((s // 2 + 0.5 if s >= 3 else 0.3) - d) + 0.6; bad.append(k)
        if best is None or len(bad) < best[0]:
            best = (len(bad), lines, used)
    return best


# ── 산 편집 ──────────────────────────────────────────────────────────────────────────
NAMED_RANGES = [
    dict(name="泰山", kind="blob", at=(36.2558, 117.1075), a=14, b=11, src="https://en.wikipedia.org/wiki/Mount_Tai",
         why="三國志 19권·後漢書 21권에 나오나 지형은 구릉 144칸뿐 — 과장"),
    dict(name="廬山", kind="blob", at=(29.5594, 115.9934), a=11, b=8, src="https://en.wikipedia.org/wiki/Mount_Lu",
         why="後漢書 2권, 지형 산 0 — 25×10km 타원을 과장(방향 미확인이라 정하지 않음)"),
    dict(name="定軍山", kind="blob", at=(33.1150, 106.6614), a=8, b=6, src="https://en.wikipedia.org/wiki/Mount_Dingjun",
         why="三國志 3권(定軍山 전투), 지형 산 391/1681 — 漢水 남쪽 보강"),
    dict(name="衡山", kind="ridge", pts=[(27.05, 112.60), (27.3017, 112.6847), (27.75, 112.75), (28.18, 112.93)], w=5,
         src="https://en.wikipedia.org/wiki/Mount_Heng_(Hunan)", why="後漢書 9권, 지형 산 0 — 남북 150km 산줄기(回雁峰~岳麓山)"),
    dict(name="隴山", kind="ridge", pts=[(35.50, 106.45), (35.20, 106.55), (34.95, 106.50), (34.55, 106.75)], w=6,
         src="https://www.baoji.gov.cn/bmpd/bjslyj/ztzl/djjjhjswm/202305/t20230508_741089.html",
         why="後漢書 3권, 지형은 고원 — 남북 능선(平涼~寶雞, 華亭·張家川·隴縣)"),
    dict(name="沂山", kind="blob", at=(36.165, 118.633), a=10, b=7, src="https://baike.baidu.com/en/item/Yishan%20Scenic%20Area/1531139",
         why="後漢書 卷002 顯宗紀 「青州，其山曰沂山」 — 靑州의 鎭山. 표고 자료(약 5km)로는 山東 구릉이 잡히지 않아 과장"),
    dict(name="勞山", kind="blob", at=(36.19167, 120.59167), a=11, b=9, src="https://en.wikipedia.org/wiki/Mount_Lao",
         why="後漢書 卷083 逸民列傳 逢萌 「乃之琅邪勞山」·郡國志 琅邪 注 — 膠東 바닷가 산(주봉 1,133m), 표고 자료로는 기복 300m 미만"),
]


def moved_cities(inp, placements):
    to = {p["cityId"]: p["to"] for p in placements if p.get("to")}
    return [dict(c, row=to[c["id"]][0], col=to[c["id"]][1]) if c["id"] in to else c for c in inp["cities"]]


def protected_mask(inp, tier, width, placements):
    T = inp["terrain"]; h, w = T.shape
    prot = np.isin(T, (TERRAIN_SEA, TERRAIN_LAKE, TERRAIN_OUT)) | inp["road"]
    for c in moved_cities(inp, placements):
        b = SPAN.get(c["level"], 1) // 2 + 1
        prot[max(0, c["row"] - b):c["row"] + b + 1, max(0, c["col"] - b):c["col"] + b + 1] = True
    ys, xs = np.where(tier > 0)
    for y, x in zip(ys, xs):
        r = (max(1, int(width[y, x])) - 1) // 2 + 1
        prot[max(0, y - r):y + r + 1, max(0, x - r):x + r + 1] = True
    return prot


def compute_mountains(inp, tier, width, placements):
    T = inp["terrain"]; P = inp["proj"]; H, W = T.shape
    prot = protected_mask(inp, tier, width, placements); cities = moved_cities(inp, placements)
    edits: dict[tuple, str] = {}; log = []

    def ok(y, x):
        # 편집 = 반드시 산이어야 할 칸. 지형 분류가 이미 산인 칸도 넣는다 — 산 높이(relief)가 평평하다고 지우지 못하게.
        return 0 <= y < H and 0 <= x < W and not prot[y, x]

    road = inp["road"]
    for c in cities:
        if c["level"] != 3:
            continue
        r, cc = c["row"], c["col"]
        ys, xs = np.where(road[r - 4:r + 5, cc - 4:cc + 5])
        near = np.c_[ys - 4, xs - 4].astype(float)
        A = near if len(near) > 2 else np.array([[0, -1], [0, 1.0]])
        wv, vv = np.linalg.eigh(np.cov(A.T)); d = vv[:, np.argmax(wv)]; n = np.array([-d[1], d[0]])
        added = 0
        for sgn in (1, -1):
            # 능선 자리가 강 기슭·길로 절반 넘게 막히면(관 바로 옆을 강이 지남) 능선을 바깥으로 민다
            for off in (7, 9, 11, 13):
                ctr = np.array([r, cc], float) + n * off * sgn; shape, free = [], []
                for dy in range(-14, 15):
                    for dx in range(-14, 15):
                        q = ctr + np.array([dy, dx], float); y, x = int(round(q[0])), int(round(q[1]))
                        rel = np.array([y - r, x - cc], float); a = rel @ d; bb = rel @ n - off * sgn
                        if (a / 9) ** 2 + (bb / 5) ** 2 > 1 + 0.35 * (noise(y, x) - 0.5) or abs(rel @ n) < 2.5 or (y, x) in shape:
                            continue
                        shape.append((y, x))
                        if ok(y, x) and (y, x) not in edits:
                            free.append((y, x))
                if len(free) * 2 >= len(shape) or off == 13:
                    break
            for y, x in free:
                edits[(y, x)] = "PASS_GORGE"; added += 1
        log.append(dict(name=c["name"], cityId=c["id"], reason="PASS_GORGE", cells=added, why="모든 관의 양옆 능선을 과장한다(길과 직각), 길 선 ±2칸은 골짜기"))
    for m in NAMED_RANGES:
        added = 0
        if m["kind"] == "blob":
            cr, ccol = proj_rc(m["at"][1], m["at"][0], P)
            for dy in range(-m["a"] - 4, m["a"] + 5):
                for dx in range(-m["a"] - 4, m["a"] + 5):
                    y, x = int(cr + dy), int(ccol + dx)
                    if (dx / m["a"]) ** 2 + (dy / m["b"]) ** 2 > 1 + 0.4 * (noise(y, x, m["name"]) - 0.5) or not ok(y, x) or (y, x) in edits:
                        continue
                    edits[(y, x)] = "NAMED_RANGE"; added += 1
        else:
            pts = [np.array(proj_rc(lon, lat, P)) for lat, lon in m["pts"]]
            for a_, b_ in zip(pts, pts[1:]):
                Ln = int(np.hypot(*(b_ - a_))) + 1
                for t in np.linspace(0, 1, Ln * 2):
                    q = a_ + (b_ - a_) * t
                    for dy in range(-m["w"] - 2, m["w"] + 3):
                        for dx in range(-m["w"] - 2, m["w"] + 3):
                            y, x = int(q[0] + dy), int(q[1] + dx)
                            if dy * dy + dx * dx > (m["w"] * (1 + 0.35 * (noise(y, x, m["name"]) - 0.5))) ** 2 or not ok(y, x) or (y, x) in edits:
                                continue
                            edits[(y, x)] = "NAMED_RANGE"; added += 1
        log.append(dict(name=m["name"], reason="NAMED_RANGE", cells=added, why=m["why"], source=m["src"]))
    cells = sorted([y, x, reason] for (y, x), reason in edits.items())
    return dict(cells=cells, log=log)


# ── 산 높이(relief) ──────────────────────────────────────────────────────────────────
# 커밋된 표고(NOAA ETOPO1, 퍼블릭 도메인)로 산 범위를 고치고 1–3단을 나눈다. 지형 분류의 산은 Natural Earth
# 지리구역 폴리곤이라 곧은 변의 큰 덩어리다(秦嶺 남쪽 米倉·大巴가 평지, 太行 안 上黨·太原 분지가 산).
#   줄이기: 산인데 주변보다 dropBelow m 도 안 솟은 곳은 뺀다.
#   드러내기: 평지·분지·구릉인데 addAbove m 넘게 솟은 곳은 산으로 한다. 고원 분류는 건드리지 않는다.
#   기복 = 표고 − (반경 baseRadius 표고칸 최저값을 흐린 것). 표고칸 하나는 우리 4×4칸이다.
# 물·길·강 기슭·城(성내 + 1칸)은 산이 되지 않는다(골짜기). 윗단은 아랫단 안쪽 1칸 이상에 둔다.
DEM = ROOT / "web/game/public/map/elevation/han-world-v3-metres.png"
RELIEF_PARAMS = dict(baseRadius=6, dropBelow=150, addAbove=500, addPlateauAbove=1000, tier2=450, tier3=850, smoothRadius=3,
                     minMass=200, minTier=80)
# 고원 경계: 지형 분류의 고원(NE 폴리곤, 곧은 변)을 경계 띠 안에서만 다시 긋는다. 턱(고원 안 평균 − 저지 평균)이 step m
# 이상이면 그 중간 높이가 경계, 턱이 없으면 경계가 임의이므로 곧은 선을 결정적 잡음으로 흔든다. 산·물은 건드리지 않는다.
PLATEAU_PARAMS = dict(meanRadius=40, band=24, step=150, wobbleScale=12, wobbleAmp=0.35, smoothRadius=3, minMass=300)
RELIEF_CODES = {"0": "산 아님", "1": "낮은 산", "2": "산", "3": "높은 산"}


def _box(a, r):
    p = np.pad(a, r, mode="edge"); c = np.cumsum(np.cumsum(p, 0), 1); c = np.pad(c, ((1, 0), (1, 0))); k = 2 * r + 1
    return (c[k:, k:] - c[:-k, k:] - c[k:, :-k] + c[:-k, :-k]) / (k * k)


def _up4(d):
    """표고칸(768×669) → 우리 칸 ×4, 이중선형. 표고칸 중심 = 우리 칸 4k+1.5."""
    h, w = d.shape; y = (np.arange(h * 4) - 1.5) / 4; x = (np.arange(w * 4) - 1.5) / 4
    y0 = np.clip(np.floor(y).astype(int), 0, h - 1); x0 = np.clip(np.floor(x).astype(int), 0, w - 1)
    y1 = np.clip(y0 + 1, 0, h - 1); x1 = np.clip(x0 + 1, 0, w - 1)
    fy = np.clip(y - np.floor(y), 0, 1)[:, None]; fx = np.clip(x - np.floor(x), 0, 1)[None, :]
    return d[y0][:, x0] * (1 - fy) * (1 - fx) + d[y1][:, x0] * fy * (1 - fx) + d[y0][:, x1] * (1 - fy) * fx + d[y1][:, x1] * fy * fx


def _majority(m, r, times, off=None):
    M = m.astype(np.float64)
    for _ in range(times):
        M = (_box(M, r) > 0.5).astype(np.float64)
        if off is not None:
            M[off] = 0
    return M.astype(bool)


def _erode4(m):
    e = m.copy(); e[1:] &= m[:-1]; e[:-1] &= m[1:]; e[:, 1:] &= m[:, :-1]; e[:, :-1] &= m[:, 1:]
    return e


def component_sizes(m):
    """칸마다 제 4-연결 성분의 칸 수(성분 밖 0). 가로 구간 단위 합집합-찾기라 칸 수가 아니라 구간 수에 비례한다."""
    h, w = m.shape
    d = np.diff(np.pad(m.astype(np.int8), ((0, 0), (1, 1))), axis=1)
    sy, sx = np.nonzero(d == 1); _, ex = np.nonzero(d == -1)
    n = len(sy); out = np.zeros(h * w, np.int64)
    if n == 0:
        return out.reshape(h, w)
    parent = list(range(n))

    def find(a):
        while parent[a] != a:
            parent[a] = parent[parent[a]]; a = parent[a]
        return a
    row = np.searchsorted(sy, np.arange(h + 1)).tolist(); sxl = sx.tolist(); exl = ex.tolist()
    for y in range(1, h):
        i, i1, j, j1 = row[y - 1], row[y], row[y], row[y + 1]
        while i < i1 and j < j1:
            if sxl[i] < exl[j] and sxl[j] < exl[i]:
                a, b = find(i), find(j)
                if a != b:
                    parent[max(a, b)] = min(a, b)
            if exl[i] < exl[j]:
                i += 1
            else:
                j += 1
    roots = np.fromiter((find(i) for i in range(n)), np.int64, n); L = ex - sx
    size = np.bincount(roots, weights=L, minlength=n).astype(np.int64)
    offs = np.arange(L.sum()) - np.repeat(np.cumsum(L) - L, L)
    out[np.repeat(sy * w + sx, L) + offs] = np.repeat(size[roots], L)
    return out.reshape(h, w)


def _drop_small(m, n):
    return m & (component_sizes(m) >= n)


def load_dem():
    from PIL import Image
    return np.maximum(np.array(Image.open(DEM)).astype(np.float64) - 32768, 0)


def relief_rel(dem, r):
    """주변 낮은 땅보다 얼마나 솟았나(m), 우리 칸 격자."""
    from numpy.lib.stride_tricks import sliding_window_view
    low = sliding_window_view(np.pad(dem, r, mode="edge"), (2 * r + 1, 2 * r + 1)).min(axis=(-1, -2))
    return _up4(_box(dem, 1) - _box(low, r))


def compute_relief(inp, tier, width, placements, mnt, dem=None):
    prm = RELIEF_PARAMS; T = inp["terrain"]
    rel = relief_rel(load_dem() if dem is None else dem, prm["baseRadius"])
    valley = protected_mask(inp, tier, width, placements)
    rs = _box(rel, 2)
    m = ((T == TERRAIN_MOUNTAIN) & (rs >= prm["dropBelow"])) | (np.isin(T, (TERRAIN_PLAIN, TERRAIN_BASIN, TERRAIN_HILL)) & (rs >= prm["addAbove"]))
    m |= (T == TERRAIN_PLATEAU) & (rs >= prm["addPlateauAbove"])     # 고원 가장자리 산벽(蜀 서쪽 龍門·邛崍)과 고원 안 산맥
    m = _majority(m, prm["smoothRadius"], 2, off=valley)
    m = _drop_small(m, prm["minMass"]); m = ~_drop_small(~m, prm["minMass"]) & ~valley
    for y, x, _reason in mnt["cells"]:
        if not valley[y, x]:
            m[y, x] = True
    hs = _box(rel, 5); lv = np.zeros(T.shape, np.uint8); lv[m] = 1; prev = m
    for k, th in ((2, prm["tier2"]), (3, prm["tier3"])):
        mk = _majority(prev & (hs >= th), prm["smoothRadius"], 2) & _erode4(prev)
        mk = _drop_small(mk, prm["minTier"])
        lv[mk] = k; prev = mk
    return lv


def value_noise(shape, scale, salt):
    """결정적 값 잡음 0..1(격자점 해시 + 부드러운 보간)."""
    h, w = shape; gh, gw = h // scale + 2, w // scale + 2
    g = np.array([[int(hashlib.md5(f"{salt}{j},{i}".encode()).hexdigest()[:6], 16) / 0xFFFFFF for i in range(gw)] for j in range(gh)])
    ys = np.arange(h) / scale; xs = np.arange(w) / scale; y0 = ys.astype(int); x0 = xs.astype(int)
    fy = (ys - y0)[:, None]; fx = (xs - x0)[None, :]; fy = fy * fy * (3 - 2 * fy); fx = fx * fx * (3 - 2 * fx)
    return g[y0][:, x0] * (1 - fy) * (1 - fx) + g[y0][:, x0 + 1] * (1 - fy) * fx + g[y0 + 1][:, x0] * fy * (1 - fx) + g[y0 + 1][:, x0 + 1] * fy * fx


def compute_plateau(inp, lv, dem=None):
    prm = PLATEAU_PARAMS; T = inp["terrain"]
    E = _up4(_box(load_dem() if dem is None else dem, 1))
    P = T == TERRAIN_PLATEAU; low = np.isin(T, (TERRAIN_PLAIN, TERRAIN_BASIN, TERRAIN_HILL)) & (lv == 0)
    cand = (P | low) & (lv == 0); Pf = P.astype(float); Lf = low.astype(float); R = prm["meanRadius"]
    ehi = _box(E * Pf, R) / np.maximum(_box(Pf, R), 1e-6); elo = _box(E * Lf, R) / np.maximum(_box(Lf, R), 1e-6)
    near = _box(Pf, prm["band"]); band = (near > 0) & (near < 1) & cand & (_box(Lf, prm["band"]) > 0)
    step = ehi - elo; es = _box(E, 2)
    wob = _box(Pf, 6) + prm["wobbleAmp"] * (value_noise(T.shape, prm["wobbleScale"], "plateau") - 0.5) > 0.5
    out = P.copy(); real = band & (step >= prm["step"]); flat = band & (step < prm["step"])
    out[real] = es[real] >= ((ehi + elo) / 2)[real]; out[flat] = wob[flat]
    keep = cand | P; out &= keep
    out = _majority(out, prm["smoothRadius"], 2) & keep
    out = _drop_small(out, prm["minMass"]); out = ~_drop_small(~out, prm["minMass"]) & keep
    return out | (P & (lv > 0))          # 산 칸의 고원 여부(색)는 지형 분류 그대로


def plateau_summary(pl, T):
    old = T == TERRAIN_PLATEAU
    return dict(sha256=sha256_bytes(pl.tobytes()), cells=int(pl.sum()), terrainPlateau=int(old.sum()),
                removed=int((old & ~pl).sum()), added=int((~old & pl).sum()))


def relief_summary(lv, T):
    old = T == TERRAIN_MOUNTAIN
    return dict(sha256=sha256_bytes(lv.tobytes()), shape=list(lv.shape),
                counts={RELIEF_CODES[str(k)]: int((lv == k).sum()) for k in range(4)},
                terrainMountain=int(old.sum()), removedFromMountain=int((old & (lv == 0)).sum()),
                addedToMountain=int((~old & (lv > 0)).sum()))


def check_plateau(inp, plat) -> list[str]:
    bad = int((plat & np.isin(inp["terrain"], (TERRAIN_SEA, TERRAIN_LAKE, TERRAIN_OUT))).sum())
    return [f"고원: 물·지도 밖 칸 {bad}개가 고원이다"] if bad else []


def check_relief(inp, tier, width, placements, lv) -> list[str]:
    errs = []; prot = protected_mask(inp, tier, width, placements)
    bad = int((prot & (lv > 0)).sum())
    if (lv > 3).any():
        errs.append("산 높이: 0–3 밖의 값")
    if bad:
        errs.append(f"산 높이: 물·길·강 기슭·城 보호 칸 {bad}개가 산이다")
    for k in (2, 3):
        loose = int(((lv >= k) & ~_erode4(lv >= k - 1)).sum())
        if loose:
            errs.append(f"산 높이: {k}단 칸 {loose}개가 {k - 1}단 안쪽 1칸 밖에 있다")
    return errs


# ── 땅 피복 ──────────────────────────────────────────────────────────────────────────
LANDCOVER_PARAMS = dict(targetMedianFieldFraction=0.20, fieldFractionRange=[0.02, 0.55], seatClearCells=3,
                        paddyWetCells=3, paddySouthOfLat=33.3, villageHouseholds=9000, villageMax=6, villageSpacing=5,
                        forestMax=0.22, forestDensitySlope=0.02, forestMin=0.02, blockCells=2)
LANDCOVER_CODES = {"0": "없음", "1": "논", "2": "밭", "3": "숲", "4": "마을"}


def _hn(yy, xx, salt):
    """칸(또는 덩이) 좌표의 결정적 잡음 0..1. 같은 좌표는 한 번만 해시한다."""
    key = np.asarray(yy, np.int64) * 65536 + np.asarray(xx, np.int64)
    u, inv = np.unique(key, return_inverse=True)
    vals = np.fromiter((int(hashlib.md5(f"{salt}{k // 65536},{k % 65536}".encode()).hexdigest()[:4], 16) for k in u.tolist()),
                       np.int64, len(u))
    return vals[inv].reshape(key.shape) / 0xFFFF


def cell_city_grid(inp):
    """칸 → 縣 治所 城 id(省 → 관할 → 治所 城)."""
    ht = inp["ht"]; pr = ht["provinceRecords"]; jr = {j["id"]: j for j in ht["jurisdictionRecords"]}
    by_sp = {str(c.get("spatialProvinceId")): c["id"] for c in inp["world"]["cities"]}
    p2c = np.full(len(pr), -1, np.int32)
    for k, p in enumerate(pr):
        j = jr.get(p.get("jurisdictionId"))
        if j and str(j["seatPlaceId"]) in by_sp:
            p2c[k] = by_sp[str(j["seatPlaceId"])]
    own = inp["owner"]
    return np.where(own >= 0, p2c[np.clip(own, 0, None)], -1)


def compute_landcover(inp, tier, width, placements, relief, plateau):
    T = inp["terrain"]; H, W = T.shape; P = inp["proj"]; prm = LANDCOVER_PARAMS
    cei = json.loads(ECONOMY.read_text()); J = {j["cityId"]: j for j in cei["jurisdictions"] if j.get("cityId") is not None}
    seat = {c["id"]: (c["row"], c["col"]) for c in moved_cities(inp, placements)}
    row_huai = int((P["y1"] - prm["paddySouthOfLat"] + P["pad"]) / P["cell"])
    wet = np.isin(T, (TERRAIN_SEA, TERRAIN_LAKE)); ys, xs = np.where(tier > 0)
    for y, x in zip(ys, xs):
        r = (max(1, int(width[y, x])) - 1) // 2 + prm["paddyWetCells"]
        wet[max(0, y - r):y + r + 1, max(0, x - r):x + r + 1] = True
    road = inp["road"]; LC = np.zeros((H, W), np.uint8)
    arable = (np.isin(T, (TERRAIN_PLAIN, TERRAIN_BASIN)) | (T == TERRAIN_PLATEAU)) & ~plateau & (relief == 0)   # 설계 층의 산·고원은 경작지가 아니다
    ratio = [(J[k].get("households") or 0) / max(1, J[k]["arableCells"]) for k in J if J[k].get("arableCells") and J[k].get("households")]
    H0 = float(np.median(ratio)) / prm["targetMedianFieldFraction"]
    CC = cell_city_grid(inp); flat = CC.ravel(); order = np.argsort(flat, kind="stable"); srt = flat[order]
    ids, start = np.unique(srt, return_index=True); ends = np.r_[start[1:], len(srt)]
    sc = prm["seatClearCells"]
    for cid, a, b in zip(ids, start, ends):
        if cid < 0 or cid not in J:
            continue
        cells = order[a:b]; yy, xx = np.divmod(cells, W); j = J[cid]
        sr, scc = seat.get(int(cid), (int(yy.mean()), int(xx.mean())))
        ok = arable[yy, xx] & ~road[yy, xx] & ~((np.abs(yy - sr) <= sc) & (np.abs(xx - scc) <= sc))
        hh = j.get("households") or 0; ar = max(1, j.get("arableCells") or 1)
        frac = float(np.clip(hh / (ar * H0), *prm["fieldFractionRange"])); cy, cx = yy[ok], xx[ok]
        if len(cy):
            key = np.unique((cy // 2) * 4096 + (cx // 2)); ky, kx = np.divmod(key, 4096)
            dist = np.maximum(np.abs(ky * 2 - sr), np.abs(kx * 2 - scc)) + _hn(ky, kx, "lc") * 6
            chosen = key[np.argsort(dist, kind="stable")[: int(round(frac * len(cy) / 4))]]
            m = np.isin((cy // 2) * 4096 + (cx // 2), chosen)
            fy, fx = cy[m], cx[m]
            paddy = wet[fy, fx] & ((fy > row_huai) | (T[fy, fx] == TERRAIN_BASIN))
            LC[fy[paddy], fx[paddy]] = 1; LC[fy[~paddy], fx[~paddy]] = 2
            nv = int(np.clip(round(hh / prm["villageHouseholds"]), 1 if hh > 0 else 0, prm["villageMax"])); placed = []
            cand = np.argsort(np.maximum(np.abs(fy - sr), np.abs(fx - scc)) + _hn(fy, fx, "vil") * 8, kind="stable")
            for k in cand:
                if len(placed) >= nv:
                    break
                y, x = int(fy[k]), int(fx[k])
                if all(max(abs(y - p[0]), abs(x - p[1])) >= prm["villageSpacing"] for p in placed):
                    LC[y, x] = 4; placed.append((y, x))
        hill = T[yy, xx] == TERRAIN_HILL; LC[yy[hill], xx[hill]] = 3
        dens = hh / max(1, len(cells)); ffrac = float(np.clip(prm["forestMax"] - dens * prm["forestDensitySlope"], prm["forestMin"], prm["forestMax"]))
        free = arable[yy, xx] & (LC[yy, xx] == 0) & ~road[yy, xx]
        if free.any():
            fy2, fx2 = yy[free], xx[free]; z = _hn(fy2 // 5, fx2 // 5, "wood"); m2 = z < ffrac
            LC[fy2[m2], fx2[m2]] = 3
    return LC


def landcover_summary(LC):
    counts = {LANDCOVER_CODES[str(k)]: int((LC == k).sum()) for k in range(5)}
    return dict(sha256=sha256_bytes(LC.tobytes()), shape=list(LC.shape), counts=counts)


# ── 쓰기·검사 ────────────────────────────────────────────────────────────────────────
def dump(path: Path, obj) -> None:
    path.write_text(json.dumps(obj, ensure_ascii=False, separators=(",", ":")) + "\n")


def load_rivers_grid(inp, rivers_doc):
    lines = [dict(name=l["name"], tier=l["tier"], cells=[tuple(c) for c in l["cells"]]) for l in rivers_doc["lines"]]
    return lines_to_grid(lines, inp["terrain"].shape, inp["proj"])


def write_derived(inp, out: Path, rivers_doc) -> dict:
    """커밋된 강 선에서 위치 수정·산 편집·피복을 다시 만든다(NE 10m 불필요)."""
    tier, width, name = load_rivers_grid(inp, rivers_doc)
    placements = compute_placements(inp, tier, width, name)
    dump(out / PLACEMENTS, dict(schemaVersion=1, artifactId="map-design-placements-v1", moveLimit=MOVE_LIMIT,
                                ferryLimit=FERRY_LIMIT, placements=placements))
    mnt = compute_mountains(inp, tier, width, placements)
    dump(out / MOUNTAINS, dict(schemaVersion=1, artifactId="map-design-mountains-v1", **mnt))
    dem = load_dem(); lv = compute_relief(inp, tier, width, placements, mnt, dem); plat = compute_plateau(inp, lv, dem)
    dump(out / RELIEF, dict(schemaVersion=1, artifactId="map-design-relief-v1", params=RELIEF_PARAMS, codes=RELIEF_CODES,
                            input=dict(dem=DEM.relative_to(ROOT).as_posix(), demSha256=sha256_bytes(DEM.read_bytes()),
                                       source="NOAA NCEI ETOPO1 Ice Surface (public domain), web/game/public/map/elevation/manifest-legacy.json"),
                            result=relief_summary(lv, inp["terrain"]),
                            plateau=dict(params=PLATEAU_PARAMS, result=plateau_summary(plat, inp["terrain"]))))
    LC = compute_landcover(inp, tier, width, placements, lv, plat)
    dump(out / LANDCOVER, dict(schemaVersion=1, artifactId="map-design-landcover-v1", params=LANDCOVER_PARAMS,
                               codes=LANDCOVER_CODES, result=landcover_summary(LC)))
    review = [p["name"] for p in placements if p["status"] == "NEEDS_REVIEW"]
    return dict(moved=sum(1 for p in placements if p["to"]), review=review, mountainCells=len(mnt["cells"]),
                relief=relief_summary(lv, inp["terrain"])["counts"], plateau=plateau_summary(plat, inp["terrain"]),
                landcover=landcover_summary(LC)["counts"])


def river_input_hashes(out: Path) -> dict:
    return dict(waypointsSha256=sha256_bytes((out / WAYPOINTS).read_bytes()), dodgeSha256=sha256_bytes((out / DODGE).read_bytes()))


def cmd_build(out: Path) -> int:
    if not NE10M.exists():
        print(f"{NE10M} 가 없다 — 강 선 재생성은 NE 10m 이 있는 로컬에서만 한다(MAP_DESIGN_NE10M 로 경로 지정)", file=sys.stderr)
        return 2
    inp = load_inputs()
    n_bad, lines, targets = build_rivers(inp, out)
    rivers_doc = dict(
        schemaVersion=1, artifactId="map-design-rivers-v1", generator="tools/map/build_map_design.py --build",
        inputs=dict(naturalEarth10mSha256=sha256_bytes(NE10M.read_bytes()), **river_input_hashes(out)),
        rules=dict(widths="江: 상류5·중류7·하류9, 三峽(109.3–111.4°E) 3 / 河: 4→6 / 2위계 3 / 3위계 한 줄",
                   tier2=sorted(TIER2), overrides=DESIGN_OVERRIDES, wraps=WRAPS),
        rivers=sorted({(l["name"], l["tier"], l["source"]) for l in lines}),
        dodgeTargets=[dict(cityId=k, need=round(v, 2)) for k, v in sorted(targets.items())],
        lines=[dict(name=l["name"], tier=l["tier"], origin=l["origin"], cells=[list(c) for c in l["cells"]]) for l in lines],
    )
    dump(out / RIVERS, rivers_doc)
    r = write_derived(inp, out, rivers_doc)
    print(f"강 선 {len(lines)} · 비키기 미달 {n_bad} · 이동 {r['moved']} · 판정 남음 {len(r['review'])} {r['review']} · "
          f"산 편집 {r['mountainCells']} · 산 높이 {r['relief']} · 고원 {r['plateau']} · 피복 {r['landcover']}")
    return 1 if r["review"] else 0


def cmd_write_derived(out: Path) -> int:
    inp = load_inputs()
    r = write_derived(inp, out, json.loads((out / RIVERS).read_text()))
    print(f"이동 {r['moved']} · 판정 남음 {len(r['review'])} {r['review']} · 산 편집 {r['mountainCells']} · "
          f"산 높이 {r['relief']} · 고원 {r['plateau']} · 피복 {r['landcover']}")
    return 1 if r["review"] else 0


# 검사 함수는 오류 목록을 돌려준다(테스트의 적색 프로브가 망가뜨린 입력을 넣는다)
def check_river_lines(rivers_doc) -> list[str]:
    errs = []
    names = {r[0] for r in rivers_doc["rivers"]}
    for r in rivers_doc["rivers"]:
        if not r[2]:
            errs.append(f"{r[0]}: 출처 없음")
        if r[1] not in (1, 2, 3):
            errs.append(f"{r[0]}: 위계 {r[1]}")
    for i, l in enumerate(rivers_doc["lines"]):
        if l["name"] not in names:
            errs.append(f"선 {i}: 목록에 없는 강 {l['name']}")
        cells = [tuple(c) for c in l["cells"]]
        for a, b in zip(cells, cells[1:]):
            if abs(a[0] - b[0]) + abs(a[1] - b[1]) != 1:
                errs.append(f"선 {i}({l['name']}): 4-연결 끊김 {a}→{b}"); break
        s = set(cells)
        if len(s) != len(cells):
            errs.append(f"선 {i}({l['name']}): 같은 칸을 두 번 지난다")
        for y, x in s:
            if (y + 1, x) in s and (y, x + 1) in s and (y + 1, x + 1) in s:
                errs.append(f"선 {i}({l['name']}): 2×2 덩이 {(y, x)}"); break
    return errs


def check_placements(inp, tier, width, placements) -> list[str]:
    errs = []; own = inp["owner"]; ri = RiverIndex(tier, width)
    moved = {p["cityId"]: p for p in placements}
    for p in placements:
        if p["status"] != "PROPOSED" or not p.get("to"):
            errs.append(f"{p['name']}: 판정 남음({p['status']})"); continue
        r0, c0 = p["frm"]; r, c = p["to"]
        lim = FERRY_LIMIT * 1.5 if p["reason"] == "FERRY_MOUTH" else MOVE_LIMIT
        if np.hypot(r - r0, c - c0) > lim + 1e-9:
            errs.append(f"{p['name']}: 이동 {np.hypot(r - r0, c - c0):.1f}칸 > {lim}")
        if p["reason"] != "FERRY_MOUTH" and own[r, c] != own[r0, c0]:
            errs.append(f"{p['name']}: 제 省 밖으로 이동")
    for c in inp["cities"]:
        r, cc = (moved[c["id"]]["to"] if c["id"] in moved and moved[c["id"]].get("to") else (c["row"], c["col"]))
        if not submerged_ok(ri.dist(r, cc)[0], SPAN.get(c["level"], 1)):
            errs.append(f"{c['name']}: 물에 잠김")
    return errs


def check_mountains(inp, tier, width, placements, mnt) -> list[str]:
    prot = protected_mask(inp, tier, width, placements); errs = []
    for y, x, reason in mnt["cells"]:
        if prot[y, x]:
            errs.append(f"산 편집 {(y, x)}({reason}): 물·길·城 보호 칸")
    for l in mnt["log"]:
        if l["reason"] == "NAMED_RANGE" and not l.get("source"):
            errs.append(f"{l['name']}: 출처 없음")
    return errs


def run_checks(inp, out: Path) -> list[str]:
    docs = {k: json.loads((out / k).read_text()) for k in (RIVERS, PLACEMENTS, MOUNTAINS, RELIEF, LANDCOVER)}
    rivers_doc, pl = docs[RIVERS], docs[PLACEMENTS]["placements"]
    tier, width, name = load_rivers_grid(inp, rivers_doc)
    errs = check_river_lines(rivers_doc) + check_placements(inp, tier, width, pl) + check_mountains(inp, tier, width, pl, docs[MOUNTAINS])
    # 판정 입력을 고치고 강을 다시 새기지 않은 것
    for k, v in river_input_hashes(out).items():
        if rivers_doc["inputs"].get(k) != v:
            errs.append(f"{RIVERS}: 입력 {k} 가 바뀌었다 — --build 로 강을 다시 새겨라")
    if NE10M.exists() and rivers_doc["inputs"].get("naturalEarth10mSha256") != sha256_bytes(NE10M.read_bytes()):
        errs.append(f"{RIVERS}: NE 10m 원본이 다르다")
    # 결정적 재계산 대조(han-tiles·월드·길·경제 입력이 바뀌면 여기서 낡음이 드러난다)
    if compute_placements(inp, tier, width, name) != pl:
        errs.append(f"{PLACEMENTS} 가 재계산과 다르다 — --write-derived")
    if compute_mountains(inp, tier, width, pl)["cells"] != docs[MOUNTAINS]["cells"]:
        errs.append(f"{MOUNTAINS} 가 재계산과 다르다 — --write-derived")
    dem = load_dem(); lv = compute_relief(inp, tier, width, pl, docs[MOUNTAINS], dem); plat = compute_plateau(inp, lv, dem)
    errs += check_relief(inp, tier, width, pl, lv) + check_plateau(inp, plat)
    if docs[RELIEF]["input"]["demSha256"] != sha256_bytes(DEM.read_bytes()):
        errs.append(f"{RELIEF}: 표고 원판({DEM.name})이 바뀌었다 — --write-derived")
    if docs[RELIEF]["params"] != RELIEF_PARAMS or relief_summary(lv, inp["terrain"]) != docs[RELIEF]["result"]:
        errs.append(f"{RELIEF} 지문이 재계산과 다르다 — --write-derived")
    if docs[RELIEF].get("plateau", {}).get("params") != PLATEAU_PARAMS or plateau_summary(plat, inp["terrain"]) != docs[RELIEF]["plateau"]["result"]:
        errs.append(f"{RELIEF} 고원 지문이 재계산과 다르다 — --write-derived")
    if docs[LANDCOVER]["params"] != LANDCOVER_PARAMS or landcover_summary(compute_landcover(inp, tier, width, pl, lv, plat)) != docs[LANDCOVER]["result"]:
        errs.append(f"{LANDCOVER} 지문이 재계산과 다르다 — --write-derived")
    return errs


def cmd_check(out: Path) -> int:
    errs = run_checks(load_inputs(), out)
    for e in errs[:40]:
        print("FAIL", e)
    print(f"지도 설계 층 검사: 오류 {len(errs)}")
    return 1 if errs else 0


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    g = ap.add_mutually_exclusive_group(required=True)
    g.add_argument("--build", action="store_true", help="강 선부터 전부 다시 만든다(NE 10m 필요)")
    g.add_argument("--write-derived", action="store_true", help="커밋된 강 선에서 위치·산 편집·산 높이·피복만 다시 만든다")
    g.add_argument("--check", action="store_true", help="불변식 + 결정적 재계산 대조")
    ap.add_argument("--dir", type=Path, default=OUT, help="설계 층 디렉터리(적색 프로브용)")
    a = ap.parse_args(argv)
    if a.build:
        return cmd_build(a.dir)
    if a.write_derived:
        return cmd_write_derived(a.dir)
    return cmd_check(a.dir)


if __name__ == "__main__":
    sys.exit(main())
