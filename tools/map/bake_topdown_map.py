#!/usr/bin/env python3
"""탑다운 지도 굽기 — 참고 렌더러(`ourmap.render_region`)를 칸마다 키트 번호를 내는 도구로 옮긴 실행 명세.

설계서: 메타 `reports/opensamguk/tasks/2026-09-30-K2-design-spec.md` §2.3. 그림을 굽지 않고 숫자 격자를 굽는다.

입력
    --export-dir   `build_map_design.py --export` 산출(설계 층 8장 + 매니페스트)
    --kit-dir      와룡전 지도 키트(`catalog.json` · `kit-index.png` · `synth-stats.json.gz`)
    저장소 파일    han-world-v3.json(城) · han-tiles.json(행정 계층 · 城 칸) · han-ju-index-v1.json(州) ·
                   placements-v1.json(옮긴 城) · county-economy-inputs-v1.json(縣 戶數, 이름표 우선순위)

산출(--out)
    manifest.json            입력 지문 · 조각 목록 · 개관 · places
    grid/L0/{cx}_{cy}.bin.gz 256×256 u16 LE 타일 평면 + 256×256 u16 LE 구역 평면(gzip, mtime=0)
    grid/L2.bin.gz           768×669, 4×4 칸마다 최빈 타일 · 최빈 구역(같은 두 평면)
    places.json.gz           구역 → 縣 → 郡 → 州, 城 발자국, 관, 이름표
    defects.json             PASS_WALL_OPEN · PASS_BYPASS_ROAD · CITY_FOOTPRINT_CROSSES_PROVINCE

타일 평면의 65535 = 그리지 않는 칸(지도 밖 · 분류 V). 키트 0번은 실제 타일이다. 구역 평면은 그 칸에서 0.

    python3 tools/map/bake_topdown_map.py --export-dir X --kit-dir K --out O [--workers N] [--region r0 r1 c0 c1]
    python3 tools/map/bake_topdown_map.py --export-dir X --kit-dir K --out O --check
    python3 tools/map/bake_topdown_map.py --kit-dir K --out O --parity-reference ~/.cache/waryongjeon --region 853 1109 1441 1697
"""
from __future__ import annotations

import argparse
import gzip
import hashlib
import json
import os
import shutil
import tempfile
import subprocess
import sys
import time
import zlib
from bisect import bisect_right
from concurrent.futures import ProcessPoolExecutor
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parents[2]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))
from tools.map.audit_topdown_places import seat_audit
from tools.map.export_metadata import load_export_metadata
WORLD = ROOT / "infra/src/main/resources/map/han-world-v3.json"
HAN_TILES = ROOT / "data/map/han-tiles.json"
JU_INDEX = ROOT / "data/map/han-ju-index-v1.json"
PLACEMENTS = ROOT / "data/curated/han/map-design/placements-v1.json"
ECONOMY = ROOT / "data/curated/han/county-economy-inputs-v1.json"
EXPORT_LAYERS = ("ground", "relief", "facets", "landcover", "riverWidth", "riverTier", "roads", "owner")

# 城 성내 한 변(칸). build_map_design.SPAN 과 같다(B안). 없는 등급(수 · 진 · 관 · 이 · 장현)은 1.
SPAN = {9: 13, 8: 11, 7: 9, 6: 7, 5: 5, 10: 3}
CHUNK = 256
PAD = 6                  # 참고 렌더러 render_region(pad=6)
CITY_MARGIN = 8          # 참고 렌더러: 덧댄 영역 ±8칸 안의 城만 본다
UNDRAWN = 0xFFFF
L2_BLOCK = 4
FORMAT_VERSION = 1
PASS_WALL_LIMIT = 5      # 참고 렌더러의 관 성벽 최대 길이(칸). 제품 규칙은 C9 관 구조(U-05)가 정한다
RIDGE_SCAN = 8           # PASS_BYPASS_ROAD: 성벽 끝 너머 능선(산 칸)을 몇 칸까지 보나
SAMPLE_CHUNKS = ((5, 3), (8, 2))   # --check 표본: 洛陽 일대(행 933 · 열 1505) · 해안(渤海 서안)

OUR = {0: "W", 3: "r", 4: "W", 1: "L", 5: "L", 6: "L", 7: "L", 8: "M", 2: "M", 9: "V"}
FACETS = ("T", "S", "E", "P")
OFF = ((-1, 0), (-1, 1), (0, 1), (1, 1), (1, 0), (1, -1), (0, -1), (-1, -1))   # N NE E SE S SW W NW
DIRS = (("N", -1, 0), ("E", 0, 1), ("S", 1, 0), ("W", 0, -1))
PADDY, FIELD, FIELD2, WOOD, WOOD2, WOOD_HILL = 0xB4, 0xB5, 0xB6, 0xB1, 0xB2, 0xB3
TOP_FLAT, TOP_MID, TOP_RARE, TOP_ROCKS = 0x83, 0x7D, 0x7C, (0x93, 0xA6, 0xA2)
SITE_OF_LEVEL = {11: "county", 1: "ferry", 2: "fort", 4: "tribe"}
LEVEL_RANK = {9: 9, 8: 8, 7: 7, 6: 6, 5: 5, 10: 4, 11: 3, 1: 2, 2: 2, 4: 2}


def sha256(b: bytes) -> str:
    return hashlib.sha256(b).hexdigest()


def gz(b: bytes) -> bytes:
    blob = bytearray(gzip.compress(b, compresslevel=9, mtime=0))
    blob[9] = 255  # gzip's platform byte must not vary between build hosts.
    return bytes(blob)


def md5int(s: str, n: int) -> int:
    return int(hashlib.md5(s.encode()).hexdigest()[:n], 16)


# ── 키트 ────────────────────────────────────────────────────────────────────────────
class Kit:
    """키트 카탈로그 · 색인 아틀라스 · 합성 통계. 키트 0–255 = 원작 타일 번호."""

    def __init__(self, kit_dir: Path):
        from PIL import Image
        kit_dir = Path(kit_dir)
        self.dir = kit_dir
        cat_bytes = (kit_dir / "catalog.json").read_bytes()
        self.catalog = json.loads(cat_bytes)
        self.catalog_sha = sha256(cat_bytes)
        export_path = kit_dir / "export.json"
        self.export = json.loads(export_path.read_bytes()) if export_path.is_file() else None
        self.kit_id = self.export["kitId"] if self.export else self.catalog_sha[:12]
        self.kit_version = self.export["source"]["mergeCommit"] if self.export else self.catalog_sha
        self.paths = {name: self.asset_path(name) for name in ("catalog.json", "kit-index.png", "synth-stats.json.gz")}
        if self.export:
            for name, path in self.paths.items():
                entries = [entry for key, entry in self.export["files"].items() if Path(key).name == name]
                if len(entries) != 1 or entries[0]["sha256"] != sha256(path.read_bytes()):
                    raise ValueError(f"kit export fingerprint differs: {name}")
        cols = int(self.catalog["atlasColumns"])
        with Image.open(self.paths["kit-index.png"]) as image:
            a = np.array(image)
        rows = a.shape[0] // 16
        tiles = (a & 15).reshape(rows, 16, cols, 16).transpose(0, 2, 1, 3).reshape(rows * cols, 16, 16)
        self.wf = [float(v) for v in np.isin(tiles[:256], (3, 8)).mean(axis=(1, 2))]    # 원작 타일의 물 비율
        stats = json.loads(gzip.decompress(self.paths["synth-stats.json.gz"].read_bytes()))
        self.order = list(stats["order"])
        self.tables = {name: [stats[name][k] for k in self.order] for name in ("terrain", "facet")}
        self.tile_facet = {int(t): f for t, f in stats["tileFacet"]}
        L = self.catalog["kit"]["lookup"]
        self.river = {k: int(v) for k, v in L["river"].items()}
        self.road = {k: int(v) for k, v in L["road"].items()}
        self.bridge = {k: int(v) for k, v in L["bridge"].items()}
        self.castle = L["castle"]
        self.pass_ = L["pass"]
        self.village = int(L["village"])
        self.roof_tiles = [int(v) for v in L["roofTiles"]]
        n = int(self.catalog["kit"]["count"])
        self.lut = {}
        for name in ("desert", "plateau"):
            lut = np.arange(max(n, UNDRAWN + 1), dtype=np.uint16)
            for base, vid in L["variants"][name].items():
                lut[int(base)] = int(vid)
            self.lut[name] = lut

    def asset_path(self, name: str) -> Path:
        local = self.dir / name
        if local.is_file():
            return local
        return ROOT / "web/game/public/map/waryong" / self.kit_id / name

    def input_hashes(self) -> dict:
        hashes = {f"kit/{name}": sha256(path.read_bytes()) for name, path in self.paths.items()}
        if self.export:
            hashes["kit/export.json"] = sha256((self.dir / "export.json").read_bytes())
            hashes["kit/sourceMergeCommit"] = self.kit_version
        return hashes


# ── 참고 렌더러의 보조 함수(그대로 옮김) ─────────────────────────────────────────────
def vnoise(h, w, oy, ox, scale, salt):
    gy, gx = (h + oy % scale) // scale + 3, (w + ox % scale) // scale + 3
    base = np.array([[md5int(f"{salt},{(oy // scale) + j},{(ox // scale) + i}", 6) / 0xFFFFFF for i in range(gx)] for j in range(gy)])
    ys = (np.arange(h) + oy % scale) / scale; xs = (np.arange(w) + ox % scale) / scale
    y0 = ys.astype(int); x0 = xs.astype(int); fy = (ys - y0)[:, None]; fx = (xs - x0)[None, :]
    fy = fy * fy * (3 - 2 * fy); fx = fx * fx * (3 - 2 * fx)
    a = base[y0][:, x0]; b = base[y0][:, x0 + 1]; c = base[y0 + 1][:, x0]; d = base[y0 + 1][:, x0 + 1]
    return a * (1 - fy) * (1 - fx) + b * (1 - fy) * fx + c * fy * (1 - fx) + d * fy * fx


def cell_noise(ys, xs, oy, ox, salt):
    """vnoise(scale=1) 의 칸 값. 배율 1이면 보간 가중이 0이라 격자 값 그대로다 — 필요한 칸만 센다."""
    return np.array([md5int(f"{salt},{oy + y},{ox + x}", 6) / 0xFFFFFF for y, x in zip(ys.tolist(), xs.tolist())])


def grow(m, k):
    o = m.copy()
    for _ in range(k):
        n = o.copy(); n[1:] |= o[:-1]; n[:-1] |= o[1:]; n[:, 1:] |= o[:, :-1]; n[:, :-1] |= o[:, 1:]; o = n
    return o


def thin(mask):
    """Zhang-Suen 세선화(참고 렌더러와 같은 병렬 두 단계, 테두리 칸은 지우지 않는다)."""
    m = mask.astype(np.uint8).copy(); h, w = m.shape
    if h < 3 or w < 3:
        return m.astype(bool)
    while True:
        changed = False
        for step in (0, 1):
            c = m[1:-1, 1:-1]
            P = (m[:-2, 1:-1], m[:-2, 2:], m[1:-1, 2:], m[2:, 2:], m[2:, 1:-1], m[2:, :-2], m[1:-1, :-2], m[:-2, :-2])
            B = sum(p.astype(np.int16) for p in P)
            A = sum(((P[i] == 0) & (P[(i + 1) % 8] == 1)).astype(np.int16) for i in range(8))
            cond = (c == 1) & (B >= 2) & (B <= 6) & (A == 1)
            if step == 0:
                cond &= ~(((P[0] & P[2] & P[4]) | (P[2] & P[4] & P[6])).astype(bool))
            else:
                cond &= ~(((P[0] & P[2] & P[6]) | (P[0] & P[4] & P[6])).astype(bool))
            if cond.any():
                c[cond] = 0; changed = True
        if not changed:
            return m.astype(bool)


def thin4(mask):
    m = thin(mask); o = m.copy()
    if m.shape[0] < 2:
        return o
    a, b = m[:-1], m[1:]
    hit = a[:, :-1] & b[:, 1:] & ~b[:, :-1] & ~a[:, 1:]           # (y,x)→(y+1,x+1): (y,x+1) 을 채운다
    o[:-1, 1:] |= hit
    hit = a[:, 1:] & b[:, :-1] & ~b[:, 1:] & ~a[:, :-1]           # (y,x)→(y+1,x-1): (y,x-1) 을 채운다
    o[:-1, :-1] |= hit
    return o


def drop_small(m, minsize):
    h, w = m.shape; seen = np.zeros_like(m); out = m.copy()
    for y, x in zip(*[a.tolist() for a in np.nonzero(m)]):
        if seen[y, x]:
            continue
        comp = [(y, x)]; seen[y, x] = True; i = 0
        while i < len(comp):
            a, b = comp[i]; i += 1
            for dy, dx in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                p = (a + dy, b + dx)
                if 0 <= p[0] < h and 0 <= p[1] < w and m[p] and not seen[p]:
                    seen[p] = True; comp.append(p)
        if len(comp) < minsize:
            for p in comp:
                out[p] = False
    return out


def _disk_sum(M, pad_mode):
    h, w = M.shape; P = np.pad(M, 3, mode=pad_mode); S = np.zeros_like(M)
    for dy in range(-3, 4):
        for dx in range(-3, 4):
            if dy * dy + dx * dx <= 10:
                S += P[3 + dy:3 + dy + h, 3 + dx:3 + dx + w]
    return S


def four_connect(cells):
    out = []
    for i, (r, c) in enumerate(cells):
        if i and abs(r - cells[i - 1][0]) == 1 and abs(c - cells[i - 1][1]) == 1:
            out.append((cells[i - 1][0], c))
        out.append((r, c))
    return out


def raster_four_connect(m):
    """궤적 순서가 없는 길 격자: 대각으로만 닿는 두 칸 사이에 위 칸 행 · 아래 칸 열 모서리를 채운다(thin4 와 같은 쪽)."""
    o = m.copy(); a, b = m[:-1], m[1:]
    o[:-1, 1:] |= a[:, :-1] & b[:, 1:] & ~b[:, :-1] & ~a[:, 1:]
    o[:-1, :-1] |= a[:, 1:] & b[:, :-1] & ~b[:, 1:] & ~a[:, :-1]
    return o


# ── 1. 칸 분류 ──────────────────────────────────────────────────────────────────────
def classify(t, relief, tier, width):
    """참고 렌더러 render_region 의 분류(USE_RELIEF · USE_DESIGN_RIVERS 경로). t: 지형 코드(0 바다 … 9 지도 밖)."""
    h, w = t.shape
    cls = np.array([OUR.get(i, "V") for i in range(256)], dtype="<U1")[t]
    cls[(cls == "M") & (relief == 0)] = "L"; cls[(relief > 0) & (cls == "L")] = "M"
    cls[t == 3] = "L"
    wide = np.zeros((h, w), bool)
    for k in sorted(set(np.unique(width[width > 1]).tolist())):
        src = width == k; rr = (k - 1) / 2; lo, hi = -int(rr) - 1, int(rr) + 2
        for dy in range(lo, hi):
            for dx in range(lo, hi):
                if dy * dy + dx * dx <= rr * rr + 0.6:
                    ys, yd = slice(max(0, -dy), h - max(0, dy)), slice(max(0, dy), h - max(0, -dy))
                    xs, xd = slice(max(0, -dx), w - max(0, dx)), slice(max(0, dx), w - max(0, -dx))
                    wide[yd, xd] |= src[ys, xs]
    sea = t == 0
    if sea.any() and (~sea).any():
        Sm = sea.astype(np.int32)
        for _ in range(2):
            Sm = (_disk_sum(Sm, "edge") >= 16).astype(np.int32)
        cls[(t == 0) & (Sm == 0) & (t != 9)] = "L"; cls[(Sm == 1) & (t != 9) & ~np.isin(cls, ("r",))] = "W"
    lake = t == 4
    if lake.any():
        Lk = lake.astype(np.int32)
        for _ in range(3):
            Lk = (_disk_sum(Lk, "constant") >= 16).astype(np.int32)
        cls[(t == 4) & (Lk == 0)] = "L"; cls[(Lk == 1) & (t != 9)] = "W"
    cls[wide & (t != 9)] = "W"
    rv = (tier > 0) & (width <= 1) & ~wide & (t != 9) & (cls != "W")
    rv = drop_small(thin4(rv), 5); cls[rv] = "r"
    return cls


# ── 2. 城 · 관 조립 ─────────────────────────────────────────────────────────────────
def resolve_footprints(cities):
    """큰 城부터 발자국을 놓고 겹치면 변을 2씩 줄인다(참고 렌더러와 같은 규칙). 돌려주는 값: {城 id: 변}."""
    taken = set(); fp = {}
    for c in sorted(cities, key=lambda c: (-SPAN.get(c["level"], 1), c["id"])):
        s = SPAN.get(c["level"], 1)

        def cells(s):
            return [(c["row"] + dy, c["col"] + dx) for dy in range(-(s // 2), s // 2 + 1) for dx in range(-(s // 2), s // 2 + 1)]
        while s > 1 and any(p in taken for p in cells(s)):
            s -= 2
        taken.update(cells(s)); fp[c["id"]] = s
    return fp


def inner_span(s):
    return 5 if s >= 11 else (3 if s >= 7 else 0)


def compose(K, h, w, gates="NSWE", streets="cross"):
    part = K.castle["big"] if min(h, w) >= 5 else K.castle["small"]
    keep, house, street = K.castle["keep"], K.castle["house"], K.castle["street"]
    b = np.zeros((h, w), int); cy, cx = h // 2, w // 2
    for j in range(h):
        for i in range(w):
            if j == 0 and i == 0: t = part["tl"]
            elif j == 0 and i == w - 1: t = part["tr"]
            elif j == h - 1 and i == 0: t = part["bl"]
            elif j == h - 1 and i == w - 1: t = part["br"]
            elif j == 0: t = part["gn"] if (i == cx and "N" in gates) else part["wn"]
            elif j == h - 1: t = part["gs"] if (i == cx and "S" in gates) else part["ws"]
            elif i == 0: t = part["gw"] if (j == cy and "W" in gates) else part["ww"]
            elif i == w - 1: t = part["ge"] if (j == cy and "E" in gates) else part["we"]
            elif (j, i) == (cy, cx): t = keep
            elif streets == "cross": t = street if (j == cy or i == cx) else house
            else: t = street if ((j - cy) % 2 == 0 or (i - cx) % 2 == 0) else house
            b[j, i] = t
    return b


def compose_city(K, n, gates="NSWE", seed=0):
    """중(7) 이상: 외성 + 내성(7 · 9 는 3×3, 11 · 13 은 5×5) + 십자 대로 + 관청 · 민가 · 정원 · 성안 밭(城 id 씨앗)."""
    if n < 7:
        return compose(K, n, n, gates)
    house, street, garden = K.castle["house"], K.castle["street"], K.castle["garden"]
    infield, infield2 = K.castle["infield"]
    b = compose(K, n, n, gates, "cross"); c = n // 2
    inner = inner_span(n)
    b[c - inner // 2:c - inner // 2 + inner, c - inner // 2:c - inner // 2 + inner] = compose(K, inner, inner, "NSWE")
    o = c - inner // 2
    for j in range(1, n - 1):
        for i in range(1, n - 1):
            if o - 1 <= j <= o + inner and o - 1 <= i <= o + inner and not (o <= j < o + inner and o <= i < o + inner):
                b[j, i] = street; continue
            if o <= j < o + inner and o <= i < o + inner:
                continue
            if j == c or i == c:
                b[j, i] = street; continue
            ring = min(j, i, n - 1 - j, n - 1 - i); dcen = max(abs(j - c), abs(i - c))
            r = md5int(f"{seed},{j},{i}", 6) / 0xFFFFFF
            if ring == 1 and r < 0.35: b[j, i] = infield if r < 0.2 else infield2
            elif r < 0.12: b[j, i] = garden
            elif dcen <= inner // 2 + 2: b[j, i] = house if r < 0.85 else street
            else: b[j, i] = street if r < 0.45 else house
    return b


def build_structure(win, cities, spans, R0, C0, K):
    """분류 · 길 · 城 · 관 · 피복. win: 창 배열(t · relief · tier · width · landcover · road(세선화 전)).
    cities: 처리 순서대로(참고 렌더러와 같은 목록 순서), row/col 은 절대 칸. 돌려주는 값: 창 배열과 城 · 관 목록."""
    t = win["t"]; h, w = t.shape
    cls = classify(t, win["relief"], win["tier"], win["width"])
    road = thin4(win["road"])
    fixed = np.full((h, w), -1, np.int16); castle = np.zeros((h, w), bool); gatecell = np.zeros((h, w), bool)
    roofs, icons, passes, castles = [], [], [], []
    P = K.pass_
    for c in cities:
        s = spans[c["id"]]; cy, cx = c["row"] - R0, c["col"] - C0; b = s // 2
        if c["level"] == 3 and 1 <= cy < h - 1 and 1 <= cx < w - 1:
            ns = int(road[cy - 1, cx]) + int(road[cy + 1, cx]); ew = int(road[cy, cx - 1]) + int(road[cy, cx + 1])
            if ns >= ew:        # 남북 길 → 문 D4 · D0 · D5, 성벽은 동서로 E7, 끝 F8(서) · F9(동)
                o = P["NS"]; orient = "NS"
                gate = [((cy - 1, cx), o["gate"][0]), ((cy, cx), o["gate"][1]), ((cy + 1, cx), o["gate"][2])]
                step = [(0, -1, o["wall"], o["endWest"]), (0, 1, o["wall"], o["endEast"])]
            else:               # 동서 길 → 문 D6 · D3 · D7, 성벽은 남북으로 E6, 끝 FB(북) · FA(남)
                o = P["EW"]; orient = "EW"
                gate = [((cy, cx - 1), o["gate"][0]), ((cy, cx), o["gate"][1]), ((cy, cx + 1), o["gate"][2])]
                step = [(-1, 0, o["wall"], o["endNorth"]), (1, 0, o["wall"], o["endSouth"])]
            for (y, x), tt in gate:
                if 0 <= y < h and 0 <= x < w:
                    fixed[y, x] = tt; castle[y, x] = True; road[y, x] = False; gatecell[y, x] = True
            walls, stops = [], []
            for dy, dx, wall, end in step:
                cells_w = []; y, x = cy + dy, cx + dx
                while 0 <= y < h and 0 <= x < w and len(cells_w) < PASS_WALL_LIMIT and cls[y, x] == "L" and not road[y, x] and not castle[y, x]:
                    cells_w.append((y, x)); y += dy; x += dx
                for k, (yy, xx) in enumerate(cells_w):
                    fixed[yy, xx] = end if k == len(cells_w) - 1 else wall; castle[yy, xx] = True
                walls.append(cells_w); stops.append(((y, x), (dy, dx)))
            passes.append(dict(cityId=c["id"], cy=cy, cx=cx, orientation=orient, gate=[p for p, _ in gate], walls=walls, stops=stops))
            roofs.append((cy, cx, c["id"]))
            continue
        if s >= 3:
            gates = ""
            for side, cells_out, front in (("N", [(cy - b - 1, cx + k) for k in range(-b, b + 1)], (cy - b - 1, cx)),
                                           ("S", [(cy + b + 1, cx + k) for k in range(-b, b + 1)], (cy + b + 1, cx)),
                                           ("W", [(cy + k, cx - b - 1) for k in range(-b, b + 1)], (cy, cx - b - 1)),
                                           ("E", [(cy + k, cx + b + 1) for k in range(-b, b + 1)], (cy, cx + b + 1))):
                hits = [p for p in cells_out if 0 <= p[0] < h and 0 <= p[1] < w and road[p]]
                if hits:
                    gates += side
                    p = min(hits, key=lambda q: abs(q[0] - front[0]) + abs(q[1] - front[1]))   # 닿은 곳에서 성문 앞까지 길을 잇는다
                    for yy in range(min(p[0], front[0]), max(p[0], front[0]) + 1):
                        for xx in range(min(p[1], front[1]), max(p[1], front[1]) + 1):
                            if 0 <= yy < h and 0 <= xx < w:
                                road[yy, xx] = True
            blk = compose_city(K, s, gates, seed=c["id"]) if s >= 7 else compose(K, s, s, gates)
            for j in range(s):
                for i in range(s):
                    y, x = cy - b + j, cx - b + i
                    if 0 <= y < h and 0 <= x < w:
                        fixed[y, x] = blk[j, i]; castle[y, x] = True; road[y, x] = False
            for side, (y, x) in (("N", (cy - b, cx)), ("S", (cy + b, cx)), ("W", (cy, cx - b)), ("E", (cy, cx + b))):
                if side in gates and 0 <= y < h and 0 <= x < w:
                    gatecell[y, x] = True
            castles.append(dict(cityId=c["id"], cy=cy, cx=cx, span=s, gates=gates))
            roofs.append((cy, cx, c["id"]))
        else:
            icons.append((cy, cx, c["id"]))
    citycells = np.zeros((h, w), bool)
    for c in cities:
        cy, cx = c["row"] - R0, c["col"] - C0
        if 0 <= cy < h and 0 <= cx < w:
            citycells[cy, cx] = True
    # 땅 피복(설계 층 landcover): 논 B4 · 밭 B5/B6 · 숲 B1/B2(산기슭 B3) · 마을(합성)
    lc = win["landcover"]; deco = np.full((h, w), -1, np.int16)
    okc = (cls == "L") & ~road & ~castle & ~citycells
    mnt3 = grow(cls == "M", 3)
    deco[okc & (lc == 1)] = PADDY
    fz = vnoise(h, w, R0, C0, 6, "fieldkind")
    deco[okc & (lc == 2) & (fz < 0.62)] = FIELD; deco[okc & (lc == 2) & (fz >= 0.62)] = FIELD2
    f = okc & (lc == 3); deco[f & mnt3] = WOOD_HILL
    ys, xs = np.nonzero(f & ~mnt3); nn = cell_noise(ys, xs, R0, C0, "pick")
    deco[ys[nn < 0.5], xs[nn < 0.5]] = WOOD; deco[ys[nn >= 0.5], xs[nn >= 0.5]] = WOOD2
    village = okc & (lc == 4)
    fixed = np.where(fixed >= 0, fixed, deco).astype(np.int16)
    cls2 = cls.copy(); cls2[castle] = "L"
    return dict(cls=cls, cls2=cls2, road=road, castle=castle, gatecell=gatecell, fixed=fixed, village=village,
                roofs=roofs, icons=icons, passes=passes, castles=castles)


# ── 3. 합성 ─────────────────────────────────────────────────────────────────────────
def facet_map(level, wE=1, wP=1, tier=None, wS=3):
    """산 면: 낮은 칸이 남쪽(단 높이만큼) 안에 있으면 S, 동쪽이면 E, 서쪽이면 P, 아니면 T(참고 facets.facet_map)."""
    h, w = level.shape; F = np.full((h, w), "", dtype="<U1"); m = level > 0

    def lower_within(dy, dx, d):
        out = np.zeros((h, w), bool)
        for k in range(1, d + 1):
            sh = np.full((h, w), 255, np.int32)
            ys = slice(max(0, -dy * k), h - max(0, dy * k)); yd = slice(max(0, dy * k), h - max(0, -dy * k))
            xs = slice(max(0, -dx * k), w - max(0, dx * k)); xd = slice(max(0, dx * k), w - max(0, -dx * k))
            sh[ys, xs] = level[yd, xd]
            out |= sh < level
        return out
    if tier is not None:
        s = (lower_within(1, 0, 1) & (tier >= 1)) | (lower_within(1, 0, 2) & (tier >= 2)) | (lower_within(1, 0, 3) & (tier >= 3))
    else:
        s = lower_within(1, 0, wS)
    e = lower_within(0, 1, wE); p = lower_within(0, -1, wP)
    F[m] = "T"; F[m & p] = "P"; F[m & e] = "E"; F[m & s] = "S"
    return F


def neighbour_strings(cls):
    """칸마다 이웃 8칸 분류 문자열(N NE E SE S SW W NW). 창 밖은 가운데 분류, V 는 W 로 본다."""
    h, w = cls.shape; P = np.pad(cls, 1, constant_values="?"); parts = []
    for dy, dx in OFF:
        v = P[1 + dy:1 + dy + h, 1 + dx:1 + dx + w]
        v = np.where(v == "?", cls, v); v = np.where(v == "V", "W", v)
        parts.append(v.astype("<U1"))
    return np.ascontiguousarray(np.stack(parts, axis=-1)).view("<U8").reshape(h, w).tolist()


def synthesize(cls2, fixed, relief, R0, C0, K, seed_origin):
    """원작 통계 합성. 산이 있으면 산을 면(T · S · E · P)으로 나눠 면 통계로, 없으면 지형 통계로(참고 렌더러 USE_FACETS 경로).
    seed_origin: 칸 추첨 씨앗 md5("{행},{열}") 의 원점 — (0, 0) 이면 창 안 좌표(참고 렌더러), (R0, C0) 면 절대 칸."""
    h, w = cls2.shape
    m1 = cls2 == "M"
    if m1.any():
        Lr = np.where(m1, np.maximum(relief.astype(np.int32), 1), 0)
        F = facet_map(Lr, 1, 1, tier=Lr.copy())
        cls = cls2.copy(); cls[m1] = F[m1]
        top = dict(mix=vnoise(h, w, R0, C0, 10, "topmix"), tier=relief, rock=vnoise(h, w, R0, C0, 6, "toprock"))
        return synth_core(cls, fixed, K.tables["facet"], K, True, top, seed_origin)
    return synth_core(cls2, fixed, K.tables["terrain"], K, False, None, seed_origin)


def synth_core(cls, fixed, tabs, K, facet_mode, top, seed_origin):
    h, w = cls.shape; oy, ox = seed_origin
    nbs = neighbour_strings(cls); crows = cls.tolist(); frows = fixed.tolist()
    out = [[-1] * w for _ in range(h)]
    wf, tile_facet = K.wf, K.tile_facet
    cache = {}
    md5 = hashlib.md5
    top_mix = top["mix"].tolist() if top else None
    top_rock = top["rock"].tolist() if top else None
    top_tier = top["tier"].tolist() if top else None

    def candidates(i, key, dry, fc):
        ck = (i, key, dry, fc)
        if ck in cache:
            return cache[ck]
        items = tabs[i].get(key)
        res = None
        if items is not None:
            if dry:
                items = [p for p in items if wf[p[0]] == 0]
            if items and fc is not None:
                items = [p for p in items if tile_facet.get(p[0]) == fc]
            if items:
                cum = []; s = 0
                for _, v in items:
                    s += v; cum.append(s)
                res = ([p[0] for p in items], cum, s)
        cache[ck] = res
        return res

    for y in range(h):
        crow, frow, nrow, orow = crows[y], frows[y], nbs[y], out[y]
        up = out[y - 1] if y else None; sy = y + oy
        for x in range(w):
            c = crow[x]
            if c == "V":
                continue
            f = frow[x]
            if f >= 0:
                orow[x] = f; continue
            n = nrow[x]
            if facet_mode and c == "T" and n == "TTTTTTTT":
                z = int(md5(f"top{sy},{x + ox}".encode()).hexdigest()[:4], 16) / 0xFFFF
                if top_tier[y][x] >= 3 and top_rock[y][x] > 0.55:
                    orow[x] = TOP_ROCKS[int(z * 3) % 3]; continue
                a = 0.97 - 0.22 * float(top_mix[y][x])
                orow[x] = TOP_FLAT if z < a else (TOP_MID if z < a + (1 - a) * 0.8 else TOP_RARE); continue
            dry = c != "W" and c != "r" and "W" not in n
            fc = c if (facet_mode and c in FACETS) else None
            tL = orow[x - 1] if x else -1; tU = up[x] if up is not None else -1
            cls8 = c + n; n4 = c + n[0] + n[2] + n[4] + n[6]
            for i, key in enumerate((f"{tL},{tU},{cls8}", f"{tL},{tU},{n4}", f"{tL},{tU},{c}", cls8, n4, c)):
                ent = candidates(i, key, dry, fc)
                if ent is None:
                    continue
                ids, cum, tot = ent
                r = int(md5(f"{sy},{x + ox}".encode()).hexdigest()[:8], 16) / 0xFFFFFFFF * tot
                j = bisect_right(cum, r)
                orow[x] = ids[j if j < len(ids) else len(ids) - 1]
                break
    return np.array(out, dtype=np.int32)


# ── 4. 강 · 길 · 다리 · 나루 이음, 마을, 사막 · 고원 ───────────────────────────────────
def joins(tiles, cls, road, castle, gatecell, village, desert, plateau, K):
    """합성 타일 번호 → 키트 번호. 참고 렌더러의 칸 그리기 순서를 그대로 따른다."""
    h, w = tiles.shape
    kit = tiles.astype(np.int64).copy()
    conn = road | gatecell
    drawn = tiles >= 0

    def mask(y, x, pred):
        return "".join(d for d, dy, dx in DIRS if 0 <= y + dy < h and 0 <= x + dx < w and pred(y + dy, x + dx))
    rv = (cls == "r") & ~castle & drawn
    for y, x in zip(*[a.tolist() for a in np.nonzero(rv)]):
        rm = mask(y, x, lambda a, b: cls[a, b] in ("r", "W"))
        v = K.river.get(rm, K.river["EW"]) if rm else K.river["EW"]
        if road[y, x]:
            m = mask(y, x, lambda a, b: conn[a, b])
            if ("N" in m or "S" in m) and not ("N" in rm or "S" in rm): v = K.bridge["narrowNS"]
            elif ("E" in m or "W" in m) and not ("E" in rm or "W" in rm): v = K.bridge["narrowEW"]
        kit[y, x] = v
    rd = road & ~castle & (cls != "r") & (cls != "W") & drawn
    ferry = {"W": K.bridge["ferryW"], "S": K.bridge["ferryS"], "E": K.bridge["ferryE"], "N": K.bridge["ferryN"]}
    for y, x in zip(*[a.tolist() for a in np.nonzero(rd)]):
        m = mask(y, x, lambda a, b: conn[a, b] and cls[a, b] != "W")
        wat = mask(y, x, lambda a, b: cls[a, b] == "W" and road[a, b])
        if wat and len(m) == 1:
            kit[y, x] = ferry[wat[0]]           # 나루: 물 쪽 반대로 길이 이어진다
        elif m in K.road:
            kit[y, x] = K.road[m]
        else:
            kit[y, x] = K.road["NS" if m in ("N", "S", "") else "EW"]
    kit[village & ~road & ~castle & drawn] = K.village
    kit = np.where(drawn, kit, UNDRAWN).astype(np.int64)
    d = desert & ~castle & drawn; p = plateau & ~castle & drawn & ~desert
    kit[d] = K.lut["desert"][kit[d]]; kit[p] = K.lut["plateau"][kit[p]]
    return kit.astype(np.uint16)


# ── 입력 읽기(저장소 export) ─────────────────────────────────────────────────────────
def load_export(export_dir: Path):
    from PIL import Image
    export_dir = Path(export_dir)
    man, _manifest_blob, hashes = load_export_metadata(export_dir)
    if man.get("schemaVersion") != 2 or not man.get("inputFingerprint") or not man.get("mapRelease"):
        raise ValueError("topdown bake requires map-design export v2 with source fingerprints")
    layers = {}
    for k in EXPORT_LAYERS:
        ent = man["files"][k]
        blob = (export_dir / ent["file"]).read_bytes()
        if sha256(blob) != ent["sha256"] or len(blob) != ent["bytes"]:
            raise ValueError(f"{k}: PNG transport fingerprint differs")
        with Image.open(export_dir / ent["file"]) as image:
            a = np.array(image).astype(ent["dtype"])
        if list(a.shape) != list(man["shape"]):
            raise ValueError(f"{k}: 모양 {a.shape} != {man['shape']}")
        digest = sha256(a.astype("<u2" if a.dtype == np.uint16 else "u1").tobytes())
        if digest != ent["rawSha256"]:
            raise ValueError(f"{k}: 격자 지문이 매니페스트와 다르다")
        layers[k] = a; hashes[f"export/{k}"] = digest       # PNG 바이트가 아니라 푼 격자 지문(OS 마다 PNG 압축이 다르다)
    return man, layers, hashes


REPO_FILES = dict(world=WORLD, hanTiles=HAN_TILES, juIndex=JU_INDEX, placements=PLACEMENTS, economy=ECONOMY,
                  roads=ROOT / "data/map/han-land-roads-v1.json",
                  dem=ROOT / "web/game/public/map/elevation/han-world-v3-metres.png",
                  artifactCatalog=ROOT / "data/map/han-world-v3-1428-artifacts-v1/catalog.json",
                  exportMetadata=ROOT / "tools/map/export_metadata.py")


def repo_inputs(repo=None):
    """저장소 JSON 입력과 그 지문. 행정 계층 · 城 칸 · 옮긴 城 · 戶數. repo: {이름: 경로}(시험용 덮어쓰기)."""
    paths = dict(REPO_FILES, **(repo or {}))
    raw = {k: Path(p).read_bytes() for k, p in paths.items()}
    docs = {k: json.loads(raw[k]) for k in ("world", "hanTiles", "juIndex", "placements", "economy", "roads")}
    docs["hanTilesSha256"] = sha256(raw["hanTiles"])
    return docs, {f"repo/{k}": sha256(v) for k, v in raw.items()}


def world_cities(docs):
    """han-world-v3 城(목록 순서) + han-tiles 칸(build_map_design.load_inputs 와 같은 규칙) + 옮긴 城."""
    tiles_city = {str(c["id"]): c for c in docs["hanTiles"]["cities"]}
    moved = {int(p["cityId"]): p["to"] for p in docs["placements"]["placements"] if p.get("to")}
    out = []
    for c in docs["world"]["cities"]:
        t = tiles_city.get(str(c.get("spatialProvinceId"))) or tiles_city.get(str(c.get("physicalPlaceRef", "")).split(":")[-1])
        if t is None:
            raise ValueError(f"城 {c['id']} {c['name']}: han-tiles 칸을 찾지 못했다")
        row, col = int(t["row"]), int(t["col"])
        if int(c["id"]) in moved:
            row, col = (int(v) for v in moved[int(c["id"])])
        meta = c.get("meta") or {}
        out.append(dict(id=int(c["id"]), name=meta.get("displayName") or c["name"], sourceName=c["name"],
                        level=int(c["level"]), row=row, col=col,
                        provinceIndex=int(c["provinceId"]), isSeat=bool((c.get("meta") or {}).get("isSeat"))))
    return out


# ── 전 지도 구조(한 번) + 조각 합성(여러 프로세스) ─────────────────────────────────────
def export_window_inputs(layers, road_edges=None):
    g = layers["ground"]
    if road_edges is None:
        raise ValueError("ordered roadEdges are required; raster corner inference is not a source")
    road = np.zeros(g.shape, bool)
    for edge in road_edges:
        if edge["status"] != "BUILT":
            continue
        # Cells follow from-city -> boundary -> to-city; public coordinates are [col,row].
        trail = [(row, col) for col, row in edge["cells"]]
        for i in range(1, len(trail)):
            if max(abs(trail[i][0] - trail[i-1][0]), abs(trail[i][1] - trail[i-1][1])) > 1:
                raise ValueError(f"road edge {edge['edgeId']}: non-adjacent ordered cells")
        # Preserve each source segment's direction when choosing a diagonal corner.
        # Reversing the toTrail for a connected trajectory must not flip its raster corner.
        if ("fromTrail" in edge) != ("toTrail" in edge):
            raise ValueError("ordered road edge has only one source segment")
        segments = [[(row, col) for col, row in edge[key]] for key in ("fromTrail", "toTrail")] if "fromTrail" in edge else [trail]
        for segment in segments:
            for first, second in zip(segment, segment[1:]):
                if max(abs(first[0] - second[0]), abs(first[1] - second[1])) > 1:
                    raise ValueError(f"road edge {edge['edgeId']}: non-adjacent source segment")
            for row, col in four_connect(segment):
                if not (0 <= row < g.shape[0] and 0 <= col < g.shape[1]):
                    raise ValueError(f"road edge {edge['edgeId']}: cell outside map")
                road[row, col] = True
    return dict(t=g, relief=layers["relief"], tier=layers["riverTier"].astype(np.int16), width=layers["riverWidth"].astype(np.int16),
                landcover=layers["landcover"], road=road,
                desert=g == 5, plateau=g == 6, owner=layers["owner"])


def global_structure(layers, cities, K, road_edges):
    win = export_window_inputs(layers, road_edges)
    spans = resolve_footprints(cities)
    st = build_structure(win, cities, spans, 0, 0, K)
    st.update(spans=spans, relief=win["relief"], desert=win["desert"], plateau=win["plateau"], owner=win["owner"],
              ground=win["t"], riverWidth=win["width"], riverTier=win["tier"])
    return st


def chunk_bounds(cx, cy, H, W):
    r0, c0 = cy * CHUNK, cx * CHUNK
    return r0, min(H, r0 + CHUNK), c0, min(W, c0 + CHUNK)


def chunk_job(st, cx, cy):
    """조각 하나를 굽는 데 필요한 창(둘레 PAD 칸, 지도 안으로 자름)."""
    H, W = st["cls"].shape
    r0, r1, c0, c1 = chunk_bounds(cx, cy, H, W)
    R0, R1, C0, C1 = max(0, r0 - PAD), min(H, r1 + PAD), max(0, c0 - PAD), min(W, c1 + PAD)
    sl = (slice(R0, R1), slice(C0, C1))
    arrs = {k: st[k][sl] for k in ("cls", "cls2", "road", "castle", "gatecell", "fixed", "village", "relief", "desert", "plateau")}
    return dict(cx=cx, cy=cy, box=(r0, r1, c0, c1), win=(R0, R1, C0, C1), arrs=arrs)


_WORKER_KIT = None


def _init_worker(kit_dir):
    global _WORKER_KIT
    _WORKER_KIT = Kit(Path(kit_dir))


def bake_chunk(job, K=None):
    K = K or _WORKER_KIT
    a = job["arrs"]; R0, R1, C0, C1 = job["win"]; r0, r1, c0, c1 = job["box"]
    tiles = synthesize(a["cls2"], a["fixed"], a["relief"], R0, C0, K, (R0, C0))
    kit = joins(tiles, a["cls"], a["road"], a["castle"], a["gatecell"], a["village"], a["desert"], a["plateau"], K)
    plane = np.full((CHUNK, CHUNK), UNDRAWN, np.uint16)
    plane[:r1 - r0, :c1 - c0] = kit[r0 - R0:r1 - R0, c0 - C0:c1 - C0]
    return job["cx"], job["cy"], plane


def province_plane(st, cities):
    """구역 평면 = 설계 층 owner. 城 발자국 칸(관은 성문 3칸, 1칸 거점은 그 칸)은 그 城의 구역(provinceId + 1)으로 덮는다."""
    own = st["owner"].astype(np.uint16).copy(); H, W = own.shape
    byid = {c["id"]: c for c in cities}; defects = []
    feet = {}
    for c in cities:
        s = st["spans"][c["id"]]; b = s // 2
        feet[c["id"]] = [(c["row"] + dy, c["col"] + dx) for dy in range(-b, b + 1) for dx in range(-b, b + 1)]
    for p in st["passes"]:
        feet[p["cityId"]] = list(p["gate"])
    for cid, cells in feet.items():
        v = byid[cid]["provinceIndex"] + 1
        cells = [(y, x) for y, x in cells if 0 <= y < H and 0 <= x < W]
        others = sorted({int(own[y, x]) for y, x in cells} - {v})
        if others:
            n = sum(1 for y, x in cells if own[y, x] != v)
            defects.append(dict(code="CITY_FOOTPRINT_CROSSES_PROVINCE", severity="info", cityId=cid, span=st["spans"][cid],
                                province=v, cells=n, otherProvinces=others))
        for y, x in cells:
            own[y, x] = v
    return own, defects


def pass_endpoint_checks(st):
    """Immediate wall contact: a mountain or large water is allowed, a narrow river is not."""
    cls, road = st["cls"], st["road"]; h, w = cls.shape; checks = []
    for p in st["passes"]:
        for walls, ((row, col), (dy, dx)) in zip(p["walls"], p["stops"]):
            inside = 0 <= row < h and 0 <= col < w
            terrain = str(cls[row, col]) if inside else None
            on_road = bool(road[row, col]) if inside else False
            accepted = inside and terrain in ("M", "W") and not on_road
            end = walls[-1] if walls else (p["cy"], p["cx"])
            checks.append(dict(cityId=p["cityId"], orientation=p["orientation"],
                               wallEnd=[end[1], end[0]], next=[col, row], terrainClass=terrain,
                               ground=int(st["ground"][row, col]) if inside and "ground" in st else None,
                               relief=int(st["relief"][row, col]) if inside and "relief" in st else None,
                               riverWidth=int(st["riverWidth"][row, col]) if inside and "riverWidth" in st else None,
                               road=on_road, accepted=bool(accepted), side=f"{dy},{dx}"))
    return checks


def pass_defects(st):
    cls, road = st["cls"], st["road"]; H, W = cls.shape; out = []
    for p in st["passes"]:
        for walls, ((y, x), (dy, dx)) in zip(p["walls"], p["stops"]):
            inside = 0 <= y < H and 0 <= x < W
            end = walls[-1] if walls else (p["cy"], p["cx"])
            if not inside or cls[y, x] not in ("M", "W") or road[y, x]:
                why = ("edge" if not inside else "road" if road[y, x] else "castle" if st["castle"][y, x] and (y, x) not in p["gate"]
                       else "narrow-river" if cls[y, x] == "r" else "void" if cls[y, x] == "V"
                       else "limit" if len(walls) >= PASS_WALL_LIMIT else "open")
                out.append(dict(code="PASS_WALL_OPEN", severity="error", cityId=p["cityId"], orientation=p["orientation"],
                                wallEnd=[end[1], end[0]], next=[x, y], reason=why, wallCells=len(walls), limit=PASS_WALL_LIMIT))
            line = list(walls); yy, xx = y, x; k = 0
            if inside and road[y, x]:
                line.append((y, x))
            while 0 <= yy < H and 0 <= xx < W and k < RIDGE_SCAN and cls[yy, xx] == "M":
                line.append((yy, xx)); yy += dy; xx += dx; k += 1
            hits = [(b, a) for a, b in line if road[a, b]]
            if hits:
                out.append(dict(code="PASS_BYPASS_ROAD", severity="error", cityId=p["cityId"], orientation=p["orientation"],
                                side=f"{dy},{dx}", roadCells=[list(h) for h in hits]))
    return out


def l2_mode(tile, prov):
    """4×4 칸마다 최빈 값(같으면 작은 번호). 타일은 65535 를 빼고 센다(블록 전체가 65535 면 65535)."""
    H, W = tile.shape; bh, bw = H // L2_BLOCK, W // L2_BLOCK

    def mode(a, ignore):
        blk = a[:bh * L2_BLOCK, :bw * L2_BLOCK].reshape(bh, L2_BLOCK, bw, L2_BLOCK).transpose(0, 2, 1, 3).reshape(bh * bw, -1)
        s = np.sort(blk, axis=1).astype(np.int64)
        cnt = np.zeros(s.shape, np.int64)
        for j in range(s.shape[1]):
            cnt += s == s[:, j:j + 1]
        if ignore is not None:
            cnt[s == ignore] = 0
            allign = (s == ignore).all(axis=1)
            cnt[allign, 0] = 1
        return s[np.arange(len(s)), np.argmax(cnt, axis=1)].reshape(bh, bw).astype(np.uint16)
    return mode(tile, UNDRAWN), mode(prov, None)


def planes_bytes(tile, prov):
    return tile.astype("<u2").tobytes() + prov.astype("<u2").tobytes()


# ── places.json ─────────────────────────────────────────────────────────────────────
def build_places(docs, cities, st, own):
    ht = docs["hanTiles"]; prov = ht["provinceRecords"]; jur = ht["jurisdictionRecords"]; com = ht["commanderyRecords"]
    jidx = {j["id"]: i for i, j in enumerate(jur)}; cidx = {c["id"]: i for i, c in enumerate(com)}
    parent_no = {p["id"]: i for i, p in enumerate(ht["parentRegions"])}
    ju_rows = docs["juIndex"]["byTerrainSha256"].get(docs["hanTilesSha256"])
    if ju_rows is None:
        raise ValueError("han-ju-index 에 지금 han-tiles 지문이 없다")
    ju_names = list(dict.fromkeys(ju_rows))
    com_ju = {c["id"]: ju_names.index(ju_rows[parent_no[c["id"]]]) if c["id"] in parent_no else -1 for c in com}
    households = {int(j["cityId"]): j.get("households") for j in docs["economy"]["jurisdictions"] if j.get("cityId") is not None}
    city_of_province = {c["provinceIndex"]: c["id"] for c in cities}
    admin = []
    for p in prov:
        ci = jidx.get(p.get("jurisdictionId"), -1)
        mi = cidx.get(jur[ci]["commanderyId"], -1) if ci >= 0 else -1
        admin.append([ci, mi, com_ju.get(com[mi]["id"], -1) if mi >= 0 else -1])
    audit = seat_audit(ht, docs["world"])
    county_city = {jidx[county["id"]]: county["cityId"] for county in audit["counties"] if county["cityId"] is not None}
    counties = [dict(id=j["id"], name=j["displayName"], kind=j["kind"], cityId=county_city.get(i)) for i, j in enumerate(jur)]
    byid = {c["id"]: c for c in cities}
    commanderies = []
    for i, c in enumerate(com):
        seat = county_city.get(jidx.get(c.get("seatJurisdictionId"), -1))
        commanderies.append(dict(id=c["id"], name=c["displayName"], kind=c["kind"], seatCityId=seat,
                                 seatJurisdictionId=c.get("seatJurisdictionId"),
                                 commanderyNo=parent_no.get(c["id"]), labelAnchorMissing=seat is None))
    # 州 이름표: 그 州 칸의 무게중심 → 가장 가까운 그 州 칸
    ju_of_plane = np.full(len(prov) + 1, -1, np.int32)
    for i, row in enumerate(admin):
        ju_of_plane[i + 1] = row[2]
    jp = ju_of_plane[own.astype(np.int64)]
    ju = []
    for k, name in enumerate(ju_names):
        ys, xs = np.nonzero(jp == k)
        if len(ys) == 0:
            ju.append(dict(name=name, anchor=None)); continue
        my, mx = ys.mean(), xs.mean(); d = (ys - my) ** 2 + (xs - mx) ** 2; i = int(np.argmin(d))
        ju.append(dict(name=name, anchor=[int(xs[i]), int(ys[i])]))
    fp = {c["cityId"]: c for c in st["castles"]}; ps = {p["cityId"]: p for p in st["passes"]}
    out_cities = []
    administrative_seats = {m["seatCityId"] for m in commanderies if m["seatCityId"] is not None}
    for c in cities:
        s = st["spans"][c["id"]]; b = s // 2
        ci, mi = (admin[c["provinceIndex"]][:2] if 0 <= c["provinceIndex"] < len(admin) else (-1, -1))
        roof = [c["col"], c["row"]] if (c["id"] in fp or c["id"] in ps) else None
        gates = fp[c["id"]]["gates"] if c["id"] in fp else (ps[c["id"]]["orientation"] if c["id"] in ps else "")
        # 1칸 거점(관 제외): 수 · 진 · 이는 제 그림, 나머지(장현, 겹쳐 1칸으로 줄어든 城)는 참고 렌더러처럼 현 그림
        site = SITE_OF_LEVEL.get(c["level"], "county") if s == 1 and c["id"] not in ps else None
        out_cities.append(dict(id=c["id"], name=c["name"], sourceName=c.get("sourceName", c["name"]),
                               level=c["level"], cell=[c["col"], c["row"]], provinceIndex=c["provinceIndex"],
                               countyIndex=ci, commanderyIndex=mi, isSeat=c["isSeat"],
                               isAdministrativeSeat=c["id"] in administrative_seats,
                               footprint=dict(originCol=c["col"] - b, originRow=c["row"] - b, span=s, innerSpan=inner_span(s)),
                               roofCell=roof, gates=gates, site=site, households=households.get(c["id"])))
    passes = [dict(cityId=p["cityId"], orientation=p["orientation"], gateCells=[[x, y] for y, x in p["gate"]],
                   wallCells=[[x, y] for side in p["walls"] for y, x in side]) for p in st["passes"]]
    labels = []
    for k, j in enumerate(ju):
        if j["anchor"] is not None:
            labels.append(dict(id=f"ju:{k}", text=j["name"], kind="ju", anchor=j["anchor"], priority=1e6, footprintSpan=0))
    com_house = {}; com_unknown = set()
    for c in cities:
        mi = admin[c["provinceIndex"]][1] if 0 <= c["provinceIndex"] < len(admin) else -1
        hh = households.get(c["id"])
        if hh is None:
            com_unknown.add(mi)
        else:
            com_house[mi] = com_house.get(mi, 0) + hh
    seats = {m["seatCityId"] for m in commanderies if m["seatCityId"] is not None}
    for i, m in enumerate(commanderies):
        seat = byid.get(m["seatCityId"])
        if seat is None:
            continue
        labels.append(dict(id=f"commandery:{i}", text=m["name"], kind="commandery", anchor=[seat["col"], seat["row"]],
                           priority=5e5 + (com_house.get(i, 0) / 10 if i not in com_unknown else 0),
                           priorityHouseholds=None if i in com_unknown else com_house.get(i, 0),
                           footprintSpan=st["spans"][seat["id"]]))
    for c in cities:
        hh = households.get(c["id"])
        if c["level"] == 3:
            kind, pri = "pass", 250000
        elif c["level"] == 1:
            kind, pri = "ferry", 150000
        else:
            kind = "commanderySeat" if c["id"] in seats else "county"
            pri = LEVEL_RANK.get(c["level"], 2) * 1e5 + (min(hh / 10, 99999) if hh is not None else 0)
        labels.append(dict(id=f"city:{c['id']}", text=c["name"], kind=kind, anchor=[c["col"], c["row"]], priority=pri,
                           priorityHouseholds=hh, footprintSpan=st["spans"][c["id"]]))
    game_seats = {c["id"] for c in cities if c["isSeat"]}
    seats = dict(administrativeCityIds=sorted(administrative_seats), gameCityIds=sorted(game_seats),
                      intersection=sorted(administrative_seats & game_seats),
                      administrativeOnly=sorted(administrative_seats - game_seats),
                      gameOnly=sorted(game_seats - administrative_seats))
    return dict(schemaVersion=1, provinceCount=len(prov), provinceAdmin=admin, counties=counties, commanderies=commanderies,
                ju=ju, cities=out_cities, passes=passes, passEndpointChecks=pass_endpoint_checks(st),
                labels=labels, seatAudit=seats,
                sourceDefinitions=dict(administrativeSeat="han-tiles commanderyRecords.seatJurisdictionId -> jurisdiction.seatPlaceId -> explicit world place binding",
                                       gameSeat="han-world cities.meta.isSeat; not inferred from footprint or centre"))


# ── 제품 굽기 ───────────────────────────────────────────────────────────────────────
_TOOL_SHA = sha256(Path(__file__).read_bytes())     # 불러온 판의 지문(실행 중에 파일이 바뀌어도 돈 코드와 맞는다)


def tool_sha():
    return _TOOL_SHA


def current_inputs(export_dir, kit, repo=None):
    man, layers, eh = load_export(export_dir)
    docs, rh = repo_inputs(repo)
    source = man["inputFingerprint"]
    for key, name in (("hanTilesSha256", "hanTiles"), ("worldJsonSha256", "world"),
                      ("roadsSha256", "roads"), ("demSha256", "dem"),
                      ("economySha256", "economy"), ("artifactCatalogSha256", "artifactCatalog")):
        if source.get(key) != rh[f"repo/{name}"]:
            raise ValueError(f"export source fingerprint differs from current {name}")
    if "roadEdgesFile" in man and source.get("exportMetadataSha256") != rh["repo/exportMetadata"]:
        raise ValueError("export metadata helper fingerprint differs; regenerate the export")
    if source.get("exportGeneratorSha256") != sha256((ROOT / "tools/map/build_map_design.py").read_bytes()):
        raise ValueError("export generator fingerprint differs; regenerate the export")
    for path, digest in source["designJsonSha256"].items():
        source_path = Path((repo or {}).get(path, ROOT / path))
        if sha256(source_path.read_bytes()) != digest:
            raise ValueError(f"export design source fingerprint differs: {path}")
    inputs = dict(sorted({**eh, **rh, **kit.input_hashes()}.items()))
    return man, layers, docs, inputs


def bake_identity(man, inputs, kit, region=None):
    fingerprint = dict(man["inputFingerprint"], bakeInputs=inputs, bakeGeneratorSha256=tool_sha(),
                       chunkSize=CHUNK, pad=PAD, region=list(region) if region else None,
                       compressionRuntime=zlib.ZLIB_RUNTIME_VERSION)
    return dict(inputFingerprint=fingerprint, mapRelease=man["mapRelease"],
                kitVersion=kit.kit_version, formatVersion=FORMAT_VERSION)


def bake_id(identity):
    blob = json.dumps(identity, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")
    return sha256(blob)


def run_jobs(jobs, kit_dir, workers, K):
    if workers <= 1:
        return [bake_chunk(j, K) for j in jobs]
    with ProcessPoolExecutor(max_workers=workers, initializer=_init_worker, initargs=(str(kit_dir),)) as ex:
        return list(ex.map(bake_chunk, jobs, chunksize=1))


def bake(export_dir, kit_dir, out, workers=None, region=None, log=print, repo=None):
    t0 = time.time()
    K = Kit(kit_dir)
    man, layers, docs, inputs = current_inputs(export_dir, K, repo)
    H, W = man["shape"]
    if H <= 0 or W <= 0 or H % L2_BLOCK or W % L2_BLOCK:
        raise ValueError("export dimensions must be positive multiples of the overview block")
    if region and not (0 <= region[0] < region[1] <= H and 0 <= region[2] < region[3] <= W):
        raise ValueError("region must be a nonempty rectangle inside the map")
    cities = world_cities(docs)
    st = global_structure(layers, cities, K, man["roadEdges"])
    log(f"전 지도 구조 {time.time() - t0:.1f}s · 城 {len(cities)} · 관 {len(st['passes'])}")
    own, fp_defects = province_plane(st, cities)
    ncy, ncx = -(-H // CHUNK), -(-W // CHUNK)
    keys = [(cx, cy) for cy in range(ncy) for cx in range(ncx)]
    if region:
        r0, r1, c0, c1 = region
        keys = [(cx, cy) for cx, cy in keys if cy * CHUNK < r1 and (cy + 1) * CHUNK > r0 and cx * CHUNK < c1 and (cx + 1) * CHUNK > c0]
    workers = 1 if workers is None else workers
    if workers < 1:
        raise ValueError("workers must be positive")
    t1 = time.time()
    results = run_jobs([chunk_job(st, cx, cy) for cx, cy in keys], kit_dir, workers, K)
    log(f"조각 {len(results)}개 합성 {time.time() - t1:.1f}s · 프로세스 {workers}")
    out = Path(out); (out / "grid/L0").mkdir(parents=True, exist_ok=True)
    tile_full = np.full((H, W), UNDRAWN, np.uint16)
    chunks = []
    for cx, cy, plane in sorted(results, key=lambda r: (r[1], r[0])):
        r0, r1, c0, c1 = chunk_bounds(cx, cy, H, W)
        pv = np.zeros((CHUNK, CHUNK), np.uint16); pv[:r1 - r0, :c1 - c0] = own[r0:r1, c0:c1]
        tile_full[r0:r1, c0:c1] = plane[:r1 - r0, :c1 - c0]
        raw = planes_bytes(plane, pv)
        if (plane == plane.flat[0]).all() and (pv == pv.flat[0]).all():
            chunks.append(dict(cx=cx, cy=cy, uniform=dict(tile=int(plane.flat[0]), province=int(pv.flat[0])), rawSha256=sha256(raw)))
            continue
        fn = f"grid/L0/{cx}_{cy}.bin.gz"; blob = gz(raw); (out / fn).write_bytes(blob)
        chunks.append(dict(cx=cx, cy=cy, file=fn, sha256=sha256(blob), rawSha256=sha256(raw), bytes=len(blob)))
    lt, lp = l2_mode(tile_full, own)
    l2 = gz(planes_bytes(lt, lp)); (out / "grid/L2.bin.gz").write_bytes(l2)
    places = build_places(docs, cities, st, own)
    pl = gz(json.dumps(places, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode()); (out / "places.json.gz").write_bytes(pl)
    defects = pass_defects(st) + fp_defects
    counts = {}
    for d in defects:
        counts[d["code"]] = counts.get(d["code"], 0) + 1
    dfx = (json.dumps(dict(schemaVersion=1, counts=dict(sorted(counts.items())), defects=defects), ensure_ascii=False,
                      sort_keys=True, indent=1) + "\n").encode()
    (out / "defects.json").write_bytes(dfx)
    identity = bake_identity(man, inputs, K, region)
    samples = [list(key) for key in SAMPLE_CHUNKS if key in keys]
    if not samples:
        samples = [list(key) for key in keys[:2]]
    manifest = dict(
        schemaVersion=1, artifactId="topdown-bake", bakeId=bake_id(identity), **identity,
        shape=dict(cols=W, rows=H), chunkSize=CHUNK,
        kitId=K.kit_id, kitCatalogSha256=K.catalog_sha, inputs=inputs, tool=dict(file="tools/map/bake_topdown_map.py", sha256=tool_sha()),
        partial=bool(region), region=list(region) if region else None, sampleChunks=samples,
        validation=dict(gamePassControl="unverified; requires C3 controlled edges and topology contract",
                        provincePlane="display ownership overlay; not game occupancy"),
        format=dict(
            cellOrder="places.json 의 칸은 [col, row]. 격자는 행 우선(row-major).",
            chunkFile="gzip(mtime=0) of tile plane (256×256 u16 LE) followed by province plane (256×256 u16 LE)",
            tilePlane="kit id (catalog kit.entries). 65535 = undrawn: outside the map, or class V (out of scope)",
            provincePlane="0 = none (sea, out of scope, outside the map), n = han-tiles provinceRecords[n-1]; castle footprints carry the city's province",
            uniformChunk="a chunk whose tile plane and province plane are each one value has no file (e.g. {tile: 65535, province: 0})",
            overview="4×4 block mode; tile ignores 65535 unless the whole block is 65535; ties → smaller id",
            rawSha256="sha256 of the uncompressed two planes (gzip bytes can differ between zlib builds)"),
        undrawnTile=UNDRAWN, chunks=chunks,
        overview=dict(file="grid/L2.bin.gz", sha256=sha256(l2), bytes=len(l2), rawSha256=sha256(planes_bytes(lt, lp)), cols=W // L2_BLOCK, rows=H // L2_BLOCK, block=L2_BLOCK),
        places=dict(file="places.json.gz", sha256=sha256(pl), bytes=len(pl), rawSha256=sha256(gzip.decompress(pl))),
        defects=dict(file="defects.json", sha256=sha256(dfx), bytes=len(dfx), rawSha256=sha256(dfx), counts=dict(sorted(counts.items()))))
    manifest["files"] = [dict(file=entry["file"], sha256=entry["sha256"], bytes=entry["bytes"],
                               rawSha256=entry["rawSha256"], compression="gzip" if entry["file"].endswith(".gz") else "none")
                         for entry in output_entries(manifest)]
    (out / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=1) + "\n")
    log(f"끝 {time.time() - t0:.1f}s · 파일 조각 {sum(1 for c in chunks if 'file' in c)} · 한 값 조각 {sum(1 for c in chunks if 'uniform' in c)}")
    return manifest


def output_entries(manifest):
    return [chunk for chunk in manifest["chunks"] if "file" in chunk] + [manifest[key] for key in ("overview", "places", "defects")]


def safe_output_path(root, name):
    path = Path(root) / name
    if Path(name).is_absolute() or ".." in Path(name).parts:
        raise ValueError("output path escapes bundle")
    path.resolve().relative_to(Path(root).resolve())
    return path


def package_bundle(export_dir, kit_dir, out, bundle_root, log=print, repo=None):
    """Verify then stage one complete immutable bundle; never replace an existing ID."""
    errors = check(export_dir, kit_dir, out, log, repo)
    if errors:
        raise ValueError("cannot package invalid bake: " + "; ".join(errors))
    out = Path(out)
    manifest_bytes = (out / "manifest.json").read_bytes()
    manifest = json.loads(manifest_bytes)
    if manifest["partial"] or manifest.get("region") is not None:
        raise ValueError("partial bake cannot be published")
    bundle_root = Path(bundle_root)
    bundle_root.mkdir(parents=True, exist_ok=True)
    target = bundle_root / manifest["bakeId"]
    names = ["manifest.json"] + [entry["file"] for entry in output_entries(manifest)]
    def identical():
        if target.is_symlink() or not target.is_dir():
            return False
        actual = {str(path.relative_to(target)) for path in target.rglob("*") if path.is_file()}
        return actual == set(names) and all(
            not safe_output_path(target, name).is_symlink()
            and safe_output_path(target, name).read_bytes() == safe_output_path(out, name).read_bytes()
            for name in names)
    if target.exists() or target.is_symlink():
        if not identical():
            raise ValueError("immutable bundle already exists with different bytes")
        return target
    with tempfile.TemporaryDirectory(prefix=".topdown-stage-", dir=bundle_root) as stage:
        staged = Path(stage) / "bundle"
        staged.mkdir()
        for name in names:
            destination = safe_output_path(staged, name)
            destination.parent.mkdir(parents=True, exist_ok=True)
            if name == "manifest.json":
                destination.write_bytes(manifest_bytes)
            else:
                shutil.copyfile(safe_output_path(out, name), destination)
                entry = next(entry for entry in output_entries(manifest) if entry["file"] == name)
                blob = destination.read_bytes()
                if len(blob) != entry["bytes"] or sha256(blob) != entry["sha256"]:
                    raise ValueError("source output changed while packaging")
        # A concurrent writer must not replace this ID either.
        try:
            staged.rename(target)
        except OSError:
            if not identical():
                raise
    log(f"불변 번들: {target}")
    return target


def check_published(export_dir, kit_dir, bundle_root, log=print):
    root = Path(bundle_root)
    bundles = [path for path in sorted(root.iterdir()) if not path.name.startswith(".topdown-stage-")] if root.exists() else []
    if not bundles:
        log("SKIPPED topdown-bake: no published bundle (not verified)")
        return 77
    errors = []
    for bundle in bundles:
        try:
            if bundle.is_symlink() or not bundle.is_dir():
                raise ValueError("expected an immutable bundle directory")
            manifest = json.loads((bundle / "manifest.json").read_bytes())
            if bundle.name != manifest["bakeId"] or manifest["partial"] or manifest.get("region") is not None:
                raise ValueError("invalid published identity or partial bundle")
            errors.extend(f"{bundle.name}: {error}" for error in check(export_dir, kit_dir, bundle, log))
        except (ValueError, KeyError, OSError, TypeError) as error:
            errors.append(f"{bundle.name}: {error}")
    for error in errors:
        log("STALE " + error)
    return 1 if errors else 0


def check(export_dir, kit_dir, out, log=print, repo=None) -> list[str]:
    try:
        return _check(export_dir, kit_dir, out, log, repo)
    except (ValueError, KeyError, OSError, TypeError, EOFError) as error:
        return [f"invalid bake: {error}"]


def _check(export_dir, kit_dir, out, log, repo) -> list[str]:
    """CI 용: 입력 지문 · 산출 파일 지문 · 표본 조각 2개 다시 굽기."""
    errs = []; out = Path(out)
    manifest = json.loads((out / "manifest.json").read_text())
    K = Kit(kit_dir)
    man, layers, docs, inputs = current_inputs(export_dir, K, repo)
    if manifest["inputs"] != inputs:
        for k in sorted(set(inputs) | set(manifest["inputs"])):
            if inputs.get(k) != manifest["inputs"].get(k):
                errs.append(f"입력 지문 다름: {k}")
    if manifest["tool"]["sha256"] != tool_sha():
        errs.append("굽기 도구가 바뀌었다(다시 구워야 한다)")
    expected_identity = bake_identity(man, inputs, K, manifest.get("region"))
    if manifest["bakeId"] != bake_id(expected_identity) or any(manifest.get(key) != value for key, value in expected_identity.items()):
        errs.append("bake identity differs from source fingerprints")
    h, w = man["shape"]
    if manifest["shape"] != dict(cols=w, rows=h) or manifest["chunkSize"] != CHUNK or manifest["undrawnTile"] != UNDRAWN:
        errs.append("public grid format differs from export")
    region = manifest.get("region")
    if manifest["partial"] != bool(region):
        errs.append("partial flag differs from identity region")
    expected_keys = [(cx, cy) for cy in range(-(-h // CHUNK)) for cx in range(-(-w // CHUNK))]
    if region:
        r0, r1, c0, c1 = region
        expected_keys = [(cx, cy) for cx, cy in expected_keys if cy * CHUNK < r1 and (cy + 1) * CHUNK > r0
                         and cx * CHUNK < c1 and (cx + 1) * CHUNK > c0]
    keys = [(chunk["cx"], chunk["cy"]) for chunk in manifest["chunks"]]
    if keys != expected_keys:
        errs.append("chunk coverage differs from identity region")
    samples = [list(key) for key in SAMPLE_CHUNKS if key in expected_keys] or [list(key) for key in expected_keys[:2]]
    if manifest["sampleChunks"] != samples:
        errs.append("sample selection differs from source coverage")
    if any(manifest["overview"][key] != value for key, value in dict(cols=w // L2_BLOCK, rows=h // L2_BLOCK, block=L2_BLOCK).items()):
        errs.append("overview dimensions differ")
    for chunk in manifest["chunks"]:
        if "uniform" in chunk:
            values = chunk["uniform"]
            raw = planes_bytes(np.full((CHUNK, CHUNK), values["tile"], np.uint16),
                               np.full((CHUNK, CHUNK), values["province"], np.uint16))
            if sha256(raw) != chunk["rawSha256"]:
                errs.append("uniform planes fingerprint differs")
    entries = output_entries(manifest)
    if len({entry["file"] for entry in entries}) != len(entries):
        errs.append("duplicate output file")
    expected_files = [dict(file=entry["file"], sha256=entry["sha256"], bytes=entry["bytes"],
                           rawSha256=entry["rawSha256"], compression="gzip" if entry["file"].endswith(".gz") else "none")
                      for entry in entries]
    if manifest.get("files") != expected_files:
        errs.append("file allowlist differs from public entries")
    for entry in entries:
        fn = entry["file"]; p = safe_output_path(out, fn)
        if not p.is_file():
            errs.append(f"파일 없음: {fn}")
        else:
            blob = p.read_bytes()
            if sha256(blob) != entry["sha256"] or len(blob) != entry["bytes"]:
                errs.append(f"파일 지문 다름: {fn}")
                continue
            raw = gzip.decompress(blob) if fn.endswith(".gz") else blob
            if sha256(raw) != entry["rawSha256"]:
                errs.append(f"raw file fingerprint differs: {fn}")
            if fn.startswith("grid/L0/") and len(raw) != 4 * CHUNK * CHUNK:
                errs.append(f"chunk must contain two fixed planes: {fn}")
            if fn == "grid/L2.bin.gz" and len(raw) != 4 * (h // L2_BLOCK) * (w // L2_BLOCK):
                errs.append("overview plane length differs")
    if errs:
        return errs
    cities = world_cities(docs)
    st = global_structure(layers, cities, K, man["roadEdges"])
    own, _ = province_plane(st, cities)
    H, W = man["shape"]
    by = {(c["cx"], c["cy"]): c for c in manifest["chunks"]}
    for cx, cy in [tuple(s) for s in manifest["sampleChunks"]]:
        _, _, plane = bake_chunk(chunk_job(st, cx, cy), K)
        r0, r1, c0, c1 = chunk_bounds(cx, cy, H, W)
        pv = np.zeros((CHUNK, CHUNK), np.uint16); pv[:r1 - r0, :c1 - c0] = own[r0:r1, c0:c1]
        ent = by.get((cx, cy))
        if ent is None or ent["rawSha256"] != sha256(planes_bytes(plane, pv)):
            errs.append(f"표본 조각 {cx}_{cy}: 다시 구운 격자가 매니페스트와 다르다")
        else:
            log(f"표본 조각 {cx}_{cy}: 같음")
    return errs


# ── 참고 렌더러 대조(로컬 전용, 와룡전 캐시 필요) ─────────────────────────────────────
REF_HARNESS = r"""
import os, sys, json, pickle
import numpy as np
out = sys.argv[1]; r0, r1, c0, c1 = map(int, sys.argv[2:6])
sys.path.insert(0, 'probe')
import ourmap as M, facets as FA, ourterrain2 as O
im, fp, cs = M.render_region(r0, r1, c0, c1, scale=1, flags=False)
np.save(os.path.join(out, 'ref_rgb.npy'), np.array(im)[..., :3])
def ks(kind, key):
    if kind == 'full': return f'{key[0]},{key[1]},{"".join(key[2])}'
    if kind == 'n4': return f'{key[0]},{key[1]},{"".join(key[2:])}'
    if kind == 'c': return f'{key[0]},{key[1]},{key[2]}'
    if kind in ('cls8', 'c4'): return ''.join(key)
    return str(key)
tab = lambda K, kind: {ks(kind, k): sorted([int(t), int(v)] for t, v in c.items()) for k, c in K.items()}
json.dump(dict(fp={str(k): v for k, v in fp.items()},
               terrain={kind: tab(K, kind) for _, K, kind in M.ORDER}, facet={k: tab(FA.KF[k], k) for k in FA.ORDER},
               wf=[float(x) for x in O.wf], tileFacet=sorted([int(k), v] for k, v in FA.TILE_FACET.items()), rgb=M.RGB.tolist()),
          open(os.path.join(out, 'ref_meta.json'), 'w'))
"""


def parity_inputs(ref: Path):
    o = ref / "out/ours"
    t = np.load(o / "terrain_eff.npy", mmap_mode="r") if (o / "terrain_eff.npy").exists() else np.load(o / "terrain.npy", mmap_mode="r")
    dr = np.load(o / "design_rivers_v1.npz")
    D = json.loads((o / "mapdata_1050.json").read_text())
    trails = [v["fromTrail"] + v["toTrail"] for v in json.loads((o / "roads_design_v1.json").read_text()).values() if v["status"] == "BUILT"]
    moved = {p["id"]: p["to"] for p in json.loads((o / "placements_v1.json").read_text()) if p["to"]}
    cities = []
    for c in D["cities"]:
        row, col = moved.get(c["id"], (c["row"], c["col"]))
        cities.append(dict(id=c["id"], level=c["level"], row=int(row), col=int(col)))
    return dict(t=t, relief=np.load(o / "relief_v1.npy", mmap_mode="r"), tier=dr["tier"], width=dr["width"], landcover=np.load(o / "landcover_v1.npy", mmap_mode="r"),
                desert=np.load(o / "desert_v1.npy", mmap_mode="r"), plateau=np.load(o / "plateau_v1.npy", mmap_mode="r"), trails=trails, cities=cities)


def parity_bake(P, r0, r1, c0, c1, K, pad=PAD):
    """참고 렌더러와 같은 영역 규칙(덧대기 · 영역마다 발자국 풀기 · 창 안 씨앗)으로 키트 번호를 낸다."""
    R0, R1, C0, C1 = r0 - pad, r1 + pad, c0 - pad, c1 + pad; h, w = R1 - R0, C1 - C0
    sl = (slice(R0, R1), slice(C0, C1))
    road = np.zeros((h, w), bool)
    for cells in P["trails"]:
        for r, c in four_connect([tuple(p) for p in cells]):
            if R0 <= r < R1 and C0 <= c < C1:
                road[r - R0, c - C0] = True
    win = dict(t=P["t"][sl], relief=P["relief"][sl], tier=P["tier"][sl], width=P["width"][sl], landcover=P["landcover"][sl], road=road)
    cs = [c for c in P["cities"] if R0 - CITY_MARGIN <= c["row"] < R1 + CITY_MARGIN and C0 - CITY_MARGIN <= c["col"] < C1 + CITY_MARGIN]
    spans = resolve_footprints(cs)
    st = build_structure(win, cs, spans, R0, C0, K)
    tiles = synthesize(st["cls2"], st["fixed"], win["relief"], R0, C0, K, (0, 0))
    kit = joins(tiles, st["cls"], st["road"], st["castle"], st["gatecell"], st["village"], P["desert"][sl], P["plateau"][sl], K)
    excl = np.zeros((h, w), bool)
    for y, x, _ in st["roofs"] + st["icons"]:
        if 0 <= y < h and 0 <= x < w:
            excl[y, x] = True
    crop = (slice(pad, h - pad), slice(pad, w - pad))
    return kit[crop], excl[crop], spans, st


def render_ids(ids, K):
    from PIL import Image
    atlas = np.array(Image.open(K.asset_path("kit.png")).convert("RGB"))
    cols = int(K.catalog["atlasColumns"]); n = atlas.shape[0] // 16 * cols
    tiles = atlas.reshape(atlas.shape[0] // 16, 16, cols, 16, 3).transpose(0, 2, 1, 3, 4).reshape(n, 16, 16, 3)
    tiles = np.concatenate([tiles, np.zeros((1, 16, 16, 3), np.uint8)])      # 마지막 = 그리지 않는 칸(검정, 참고 렌더러의 0 칸)
    safe = np.where(ids == UNDRAWN, n, ids).astype(np.int64)
    h, w = ids.shape
    return tiles[safe].transpose(0, 2, 1, 3, 4).reshape(h * 16, w * 16, 3)


def parity(ref, r0, r1, c0, c1, kit_dir, out, log=print):
    ref = Path(ref).expanduser(); out = Path(out); out.mkdir(parents=True, exist_ok=True)
    K = Kit(kit_dir)
    t0 = time.time()
    if not (out / "ref_rgb.npy").exists() or not (out / "ref_meta.json").exists() or json.loads((out / "ref_region.json").read_text() if (out / "ref_region.json").exists() else "null") != [r0, r1, c0, c1]:
        subprocess.run([sys.executable, "-c", REF_HARNESS, str(out.resolve()), str(r0), str(r1), str(c0), str(c1)], cwd=ref, check=True)
        (out / "ref_region.json").write_text(json.dumps([r0, r1, c0, c1]))
    log(f"참고 렌더 {time.time() - t0:.1f}s")
    meta = json.loads((out / "ref_meta.json").read_text())
    stats_ok = dict(
        terrain=all(meta["terrain"][k] == K.tables["terrain"][i] for i, k in enumerate(K.order)),
        facet=all(meta["facet"][k] == K.tables["facet"][i] for i, k in enumerate(K.order)),
        wf=meta["wf"] == K.wf, tileFacet={int(a): b for a, b in meta["tileFacet"]} == K.tile_facet)
    t1 = time.time()
    P = parity_inputs(ref)
    ids, excl, spans, st = parity_bake(P, r0, r1, c0, c1, K)
    log(f"굽기(대조 판) {time.time() - t1:.1f}s")
    mine = render_ids(ids, K); theirs = np.load(out / "ref_rgb.npy")
    h, w = ids.shape
    diff = (mine != theirs).any(axis=2).reshape(h, 16, w, 16).any(axis=(1, 3))
    fp_ref = {int(k): v for k, v in meta["fp"].items()}
    bad = diff & ~excl
    ys, xs = np.nonzero(bad)
    res = dict(region=[r0, r1, c0, c1], cells=int(h * w), excluded=int(excl.sum()), differingAll=int(diff.sum()),
               differing=int(bad.sum()), footprintsEqual=all(spans.get(k) == v for k, v in fp_ref.items()) and len(spans) == len(fp_ref),
               statsEqual=stats_ok, first=[[int(r0 + y), int(c0 + x)] for y, x in zip(ys[:20], xs[:20])])
    (out / "parity.json").write_text(json.dumps(res, ensure_ascii=False, indent=1) + "\n")
    from PIL import Image
    vis = theirs.copy(); m = np.kron(bad, np.ones((16, 16), bool)); vis[m] = (255, 0, 255)
    Image.fromarray(vis).save(out / "parity-diff.png"); Image.fromarray(mine).save(out / "parity-mine.png")
    log(json.dumps(res, ensure_ascii=False))
    return res


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--export-dir", type=Path)
    ap.add_argument("--kit-dir", type=Path, required=True)
    ap.add_argument("--out", type=Path)
    ap.add_argument("--bundle-root", type=Path)
    ap.add_argument("--check-published", action="store_true")
    ap.add_argument("--region", type=int, nargs=4, metavar=("R0", "R1", "C0", "C1"))
    ap.add_argument("--workers", type=int)
    ap.add_argument("--check", action="store_true")
    ap.add_argument("--parity-reference", type=Path, metavar="WARYONGJEON_CACHE")
    a = ap.parse_args(argv)
    if a.check_published:
        if not a.bundle_root or not a.export_dir:
            ap.error("--check-published requires --bundle-root and --export-dir")
        return check_published(a.export_dir, a.kit_dir, a.bundle_root)
    if not a.out:
        ap.error("--out is required")
    if a.parity_reference:
        if not a.region:
            ap.error("--parity-reference 는 --region 이 필요하다")
        res = parity(a.parity_reference, *a.region, a.kit_dir, a.out)
        return 0 if res["differing"] == 0 else 1
    if not a.export_dir:
        ap.error("--export-dir 가 필요하다")
    if a.check:
        errs = check(a.export_dir, a.kit_dir, a.out)
        for e in errs:
            print("적색:", e)
        print("굽기 검사:", "통과" if not errs else f"{len(errs)}건")
        return 1 if errs else 0
    bake(a.export_dir, a.kit_dir, a.out, a.workers, a.region)
    if a.bundle_root:
        package_bundle(a.export_dir, a.kit_dir, a.out, a.bundle_root)
    return 0


if __name__ == "__main__":
    sys.exit(main())
