#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
RTK14(삼국지14) 지도 원본 이미지 → 헥스 격자 지형 데이터 결정적 추출 빌더.

**DIVERGENCE / RIGHTS 도구** — devsam/core 패러티 대상 아님. 입력 원본 지도 이미지는 코에이 IP이고
재배포 권리가 확인되지 않았다(OPENSAM-102 `RIGHTS WARN`). 따라서 원본 이미지도, 산출 JSON도
저장소에 커밋하지 않는다. 이 스크립트(알고리즘)와 그 테스트·방법 문서만 버전 관리한다.
`--source`/`--out`은 repo 밖 또는 gitignored 경로만 허용하며 tracked repo 경로에는 fail-closed 한다.

지도 구조(측정): RTK14 지도는 헥스맵을 **offset-column(flat-top)** square 타일로 렌더한다.
- 타일 pitch = 19px(x/y 공통, autocorrelation으로 측정).
- 열 중심 x = origin_x + pitch*col (측정 origin_x=9.0; 세로 격자선은 x=18.5+19k).
- 짝수 열 타일 중심 y = origin_y + pitch*row, 홀수 열은 +pitch/2 만큼 세로로 어긋난다(측정 offset≈9).
좌표계는 offset-column이며 q=열 인덱스, r=열 내부 행 인덱스다. 각 셀의 cx,cy(native pixel 중심)가
OPENSAM-102 좌표 원장(4181×4191 동일 native-pixel 공간)과 join하는 authoritative 키다.

지형 색상·라벨은 RTK14 공식 wiki 地理 페이지의 지형 효과표 "カラー" 열(지형별 CSS 스와치 hex)을
1차 근거로 고정한다. 22개 지형(平地/街道/砂地/湿地/森/密林/毒沼/低山/中山/高山/山道/浅瀬/川/大河/
府/都市/関所/港/奔流/険山/崖/急流)의 hex를 verbatim 팔레트로 쓰고, 눈대중 보정은 그 hex 주변
tolerance로만 허용한다. 지형 타입 집합의 2차 근거는 공식 웹 매뉴얼(3300.html)이다. wiki 범례에
海(개활 해양) 색은 없다 — 외곽 수역은 大河/急流 색으로 렌더된다. 격자선·지도밖 백색은 wiki 지형이
아닌 sentinel(GRID/OUT_OF_BOUNDS)로만 표시하고, wiki 색 미매칭·저신뢰 셀은 `UNKNOWN`(관측 RGB
기록)으로 남겨 추측하지 않는다.

사용:
  python3 tools/rtk14/build_rtk14_hexmap.py \
      --source /path/outside-repo/PK.png \
      --out    /path/outside-repo/rtk14-hexmap.json

결정성: 같은 입력 → byte-identical 출력(엔트리에 timestamp 없음, 셀 정렬 고정).
"""
import argparse
import hashlib
import json
import subprocess
from pathlib import Path

import numpy as np
from PIL import Image

# ── 지형 색상·라벨 근거: RTK14 공식 wiki 地理 페이지 カラー 범례 ──────────────────
# 색상은 wikiwiki.jp/sangokushi14 地理 페이지 지형 효과표 "カラー" 열(지형별 CSS 스와치 hex)에서
# verbatim으로 가져온다. 눈대중 보정은 이 wiki hex 주변 tolerance로만 허용한다.
#   1차(색+타입): 地理 page カラー 열   ·   2차(타입 집합): 공식 웹 매뉴얼 3300.html
WIKI_COLOR_LEGEND_URL = "https://wikiwiki.jp/sangokushi14/地理"
TERRAIN_EVIDENCE_URL = "https://www.gamecity.ne.jp/manual/JuLnHe14/jp/3300.html"

# wiki 地理 カラー 범례(verbatim): (terrain_jp, terrain_code, kind, wiki_hex, wiki_effect)
#   kind: "terrain" 자연 지형 / "infra" 도로·거점 등 인공 지물(wiki 지형표 등재 색) / "background" wiki 외 sentinel
# RTK14 地理 지형표에는 海(개활 해양) 색이 없다 — 외곽 수역은 大河/急流 색으로 렌더된다.
WIKI_TERRAIN = [
    ("平地", "PLAINS",         "terrain", "#bdb76b", "移動〇 火計〇 建設可能"),
    ("街道", "ROAD",           "infra",   "#ffcc00", "移動◎ 火計〇 建設可能"),
    ("砂地", "SAND",           "terrain", "#ffff99", "移動〇 火計△ 建設可能"),
    ("湿地", "WETLAND",        "terrain", "#e6b9b8", "移動△ 火計△ 士気△"),
    ("森",   "FOREST",         "terrain", "#75923c", "森戦・山越・南蛮で強化"),
    ("密林", "DENSE_FOREST",   "terrain", "#4f6228", "森戦・山越・南蛮で強化"),
    ("毒沼", "POISON_SWAMP",   "terrain", "#7030a0", "ダメージ 解毒で回避"),
    ("低山", "MOUNTAIN_LOW",   "terrain", "#ff9933", "山戦・烏桓/鮮卑/匈奴/羌氐で強化"),
    ("中山", "MOUNTAIN_MID",   "terrain", "#e46d0a", "山戦・烏桓/鮮卑/匈奴/羌氐で強化"),
    ("高山", "MOUNTAIN_HIGH",  "terrain", "#974807", "山戦・烏桓/鮮卑/匈奴/羌氐で強化"),
    ("山道", "MOUNTAIN_PATH",  "terrain", "#d8d8d8", "山戦・烏桓/鮮卑/匈奴/羌氐で強化"),
    ("浅瀬", "SHALLOWS",       "terrain", "#93cddd", "山越で強化"),
    ("川",   "RIVER",          "terrain", "#31849b", "山越で強化"),
    ("大河", "MAJOR_RIVER",    "terrain", "#215867", "火船設置可 水戦で強化"),
    ("府",   "GOVERNMENT",     "infra",   "#ff0000", "使役で強化"),
    ("都市", "CITY",           "infra",   "#ffff00", ""),
    ("関所", "FORT",           "infra",   "#c00000", ""),
    ("港",   "PORT",           "infra",   "#ff66cc", "使役で強化"),
    ("奔流", "RAPIDS",         "terrain", "#9bc5ff", "蒙衝・楼船のみ進入可 建築不可"),
    ("険山", "STEEP_MOUNTAIN", "terrain", "#333333", "進入不可"),
    ("崖",   "CLIFF",          "terrain", "#632523", "進入不可"),
    ("急流", "SWIFT_CURRENT",  "terrain", "#0f253f", "進入不可"),
]

# wiki 범례에 없는 image-mechanical sentinel — 지형이 아니라 '지형 없음'을 표시한다.
# 격자선 회색은 sentinel로 두지 않는다: 렌더된 平地(≈197,190,151)가 회색 쪽으로 끌려가 잠식되기 때문.
# 격자 그물은 셀 중심 윈도에서 out-vote되고, 순수 격자선 회색은 어느 wiki 색과도 tol 밖이라 UNKNOWN이 된다.
SENTINELS = [
    ("地図外", "OUT_OF_BOUNDS", "background", "#f8f8f8", "지도밖 백색(wiki 지형 아님)"),
]


def _hex2rgb(h):
    h = h.lstrip("#")
    return (int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16))


# 팔레트 엔트리: (centroid RGB = wiki_hex→8bit, terrain_jp, terrain_code, kind, wiki_hex)
PALETTE = [
    (_hex2rgb(hx), jp, code, kind, hx)
    for (jp, code, kind, hx, _eff) in WIKI_TERRAIN + SENTINELS
]

# background sentinel/미매칭은 지형이 아니므로 지형값을 추측하지 않는다(OUT_OF_BOUNDS/UNKNOWN).
UNKNOWN = ("UNKNOWN", "UNKNOWN", "unknown")
OUT_OF_BOUNDS = ("地図外", "OUT_OF_BOUNDS", "background")

DEFAULTS = dict(
    hex_size=19.0,
    origin_x=9.0,
    origin_y=0.0,       # 짝수 열 row0 중심 y
    orientation="flat",  # offset-column; "pointy"는 offset-row
    sample_radius=4,     # 셀 중심 ±N px 정사각 윈도(≈13px 타일 내부, 격자선 회피)
    color_tol=60,        # centroid L1 거리 임계(초과 픽셀은 ambiguous로 제외)
    min_conf=0.5,        # 윈도 majority 라벨 비율 최소치(미달 → UNKNOWN)
)


def sha256_of(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def _repo_root(start):
    p = Path(start).resolve()
    for cand in [p, *p.parents]:
        if (cand / ".git").exists():
            return cand
    return None


def _git_ignored(repo_root, path):
    r = subprocess.run(
        ["git", "-C", str(repo_root), "check-ignore", "-q", str(path)],
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
    )
    return r.returncode == 0


def assert_untracked(path, is_ignored=None):
    """repo 밖 또는 gitignored 경로만 허용. tracked repo 경로면 fail-closed(SystemExit)."""
    resolved = Path(path).resolve()
    root = _repo_root(Path(__file__).parent)
    if root is None:
        return resolved
    inside = root == resolved or root in resolved.parents
    if not inside:
        return resolved
    ignored = (is_ignored or (lambda p: _git_ignored(root, p)))(resolved)
    if not ignored:
        raise SystemExit(
            "fail-closed: 경로가 tracked repo 안이다. 원본/산출물은 repo 밖 또는 gitignored 경로만 허용한다."
        )
    return resolved


def nearest_label_map(im, palette=PALETTE):
    """H×W 각 픽셀의 nearest palette centroid 인덱스와 L1 거리(H×W int)를 반환."""
    h, w, _ = im.shape
    best_idx = np.full((h, w), -1, dtype=np.int16)
    best_dist = np.full((h, w), 1 << 30, dtype=np.int32)
    imi = im.astype(np.int32)
    for i, (rgb, *_rest) in enumerate(palette):
        d = np.abs(imi - np.array(rgb, dtype=np.int32)).sum(axis=2)
        upd = d < best_dist
        best_dist[upd] = d[upd]
        best_idx[upd] = i
    return best_idx, best_dist


def iter_cells(width, height, geom):
    """지도 bounding box를 덮는 offset-column 헥스 셀 (col,row,cx,cy)를 결정적 순서로 산출."""
    p = geom["hex_size"]
    ox, oy = geom["origin_x"], geom["origin_y"]
    flat = geom["orientation"] == "flat"
    half = p / 2.0
    ncol = int((width - ox) / p) + 1 if flat else int((width - ox) / p) + 1
    for col in range(ncol):
        cx = ox + p * col
        if cx < 0 or cx >= width:
            continue
        base = oy + (half if (flat and col % 2 == 1) else 0.0)
        nrow = int((height - base) / p) + 1
        for row in range(nrow):
            cy = base + p * row
            if 0 <= cy < height:
                yield col, row, cx, cy


def _window_bounds(cx, cy, r, h, w):
    """셀 중심 ±r px 정사각 윈도의 이미지 슬라이스 경계(클램프)."""
    x0, x1 = max(0, int(round(cx)) - r), min(w, int(round(cx)) + r + 1)
    y0, y1 = max(0, int(round(cy)) - r), min(h, int(round(cy)) + r + 1)
    return x0, x1, y0, y1


def classify_cell(label_idx, dist, cx, cy, geom, palette=PALETTE):
    """셀 중심 윈도의 nearest-centroid majority 투표로 (terrain_jp, code, kind, confidence) 판정."""
    r = geom["sample_radius"]
    tol = geom["color_tol"]
    min_conf = geom["min_conf"]
    h, w = label_idx.shape
    x0, x1, y0, y1 = _window_bounds(cx, cy, r, h, w)
    win_idx = label_idx[y0:y1, x0:x1].ravel()
    win_dist = dist[y0:y1, x0:x1].ravel()
    total = win_idx.size
    if total == 0:
        return (*UNKNOWN, 0.0)
    keep = win_dist <= tol
    if not keep.any():
        return (*UNKNOWN, 0.0)          # wiki 색 미매칭(격자선/경계 anti-alias 등)
    counts = np.bincount(win_idx[keep], minlength=len(palette))
    win = int(counts.argmax())
    conf = counts[win] / total
    if conf < min_conf:
        return (*UNKNOWN, round(float(conf), 4))
    _rgb, jp, code, kind, _hx = palette[win]
    if kind == "background":            # 격자선/지도밖 sentinel = 지형 없음
        return (*OUT_OF_BOUNDS, round(float(conf), 4))
    return jp, code, kind, round(float(conf), 4)


def build(im, source_meta, geom):
    label_idx, dist = nearest_label_map(im)
    h, w, _ = im.shape
    r = geom["sample_radius"]
    cells = []
    counts = {}
    for col, row, cx, cy in iter_cells(w, h, geom):
        jp, code, kind, conf = classify_cell(label_idx, dist, cx, cy, geom)
        cell = {
            "q": col, "r": row,
            "cx": round(float(cx), 1), "cy": round(float(cy), 1),
            "terrain": code, "terrain_jp": jp, "kind": kind,
            "confidence": conf,
        }
        if code == "UNKNOWN":            # wiki 색 미매칭 → 관측 RGB 기록(발명 금지)
            x0, x1, y0, y1 = _window_bounds(cx, cy, r, h, w)
            win = im[y0:y1, x0:x1].reshape(-1, 3)
            cell["observed_rgb"] = (
                [int(round(v)) for v in win.mean(axis=0)] if win.size else [-1, -1, -1]
            )
        cells.append(cell)
        counts[code] = counts.get(code, 0) + 1
    cells.sort(key=lambda c: (c["q"], c["r"]))
    palette_doc = [
        {"centroid_rgb": list(rgb), "wiki_hex": hx,
         "terrain_jp": jp, "terrain_code": code, "kind": kind}
        for rgb, jp, code, kind, hx in PALETTE
    ]
    wiki_legend = [
        {"terrain_jp": jp, "terrain_code": code, "kind": kind,
         "wiki_hex": hx, "rgb": list(_hex2rgb(hx)), "wiki_effect": eff}
        for (jp, code, kind, hx, eff) in WIKI_TERRAIN
    ]
    return {
        "schema": "rtk14-hexmap/v2",
        "source": source_meta,
        "geometry": {
            "layout": "offset-column" if geom["orientation"] == "flat" else "offset-row",
            "orientation": geom["orientation"],
            "hex_size": geom["hex_size"],
            "origin_x": geom["origin_x"],
            "origin_y": geom["origin_y"],
            "odd_col_dy": geom["hex_size"] / 2.0 if geom["orientation"] == "flat" else 0.0,
            "coord_system": "q=column index, r=row index within column; cx,cy=native-pixel center",
            "sample_radius": geom["sample_radius"],
            "color_tol": geom["color_tol"],
            "min_conf": geom["min_conf"],
        },
        "palette": palette_doc,
        "wiki_color_legend": {
            "source_url": WIKI_COLOR_LEGEND_URL,
            "column": "地理 지형 효과표 'カラー' 열(지형별 CSS 스와치 hex) — verbatim",
            "note": "색상=wiki hex; rgb=hex→8bit. 눈대중은 tolerance로만. wiki 색 미매칭 셀→UNKNOWN(observed_rgb 기록).",
            "no_sea_color": "RTK14 地理 범례에 海(개활 해양) 색 없음 → 외곽 수역은 大河/急流 색으로 렌더.",
            "sentinels_not_wiki": ["GRID #bcbcbc(격자선)", "OUT_OF_BOUNDS #f8f8f8(지도밖)"],
            "legend": wiki_legend,
        },
        "terrain_type_evidence_url": TERRAIN_EVIDENCE_URL,
        "counts": dict(sorted(counts.items())),
        "cell_count": len(cells),
        "cells": cells,
    }


def main():
    ap = argparse.ArgumentParser(description="RTK14 지도 → 헥스 지형 데이터")
    ap.add_argument("--source", required=True, help="원본 지도 이미지(repo 밖 또는 gitignored)")
    ap.add_argument("--out", required=True, help="출력 JSON(repo 밖 또는 gitignored)")
    ap.add_argument("--hex-size", type=float, default=DEFAULTS["hex_size"])
    ap.add_argument("--origin-x", type=float, default=DEFAULTS["origin_x"])
    ap.add_argument("--origin-y", type=float, default=DEFAULTS["origin_y"])
    ap.add_argument("--orientation", choices=["flat", "pointy"], default=DEFAULTS["orientation"])
    ap.add_argument("--sample-radius", type=int, default=DEFAULTS["sample_radius"])
    ap.add_argument("--color-tol", type=int, default=DEFAULTS["color_tol"])
    ap.add_argument("--min-conf", type=float, default=DEFAULTS["min_conf"])
    a = ap.parse_args()

    source = assert_untracked(a.source)
    out = assert_untracked(a.out)

    im_pil = Image.open(source).convert("RGB")
    im = np.asarray(im_pil)
    source_meta = {
        "sha256": sha256_of(source),
        "width": im_pil.width,
        "height": im_pil.height,
        "bytes": source.stat().st_size,
        "rights": "RIGHTS WARN — Koei IP, redistribution unconfirmed; image not bundled (OPENSAM-102)",
    }
    geom = dict(
        hex_size=a.hex_size, origin_x=a.origin_x, origin_y=a.origin_y,
        orientation=a.orientation, sample_radius=a.sample_radius,
        color_tol=a.color_tol, min_conf=a.min_conf,
    )
    result = build(im, source_meta, geom)

    out.parent.mkdir(parents=True, exist_ok=True)
    with open(out, "w", encoding="utf-8") as f:
        json.dump(result, f, ensure_ascii=False, separators=(",", ":"), sort_keys=False)
    c = result["counts"]
    total = result["cell_count"]
    unk = c.get("UNKNOWN", 0)
    print(f"cells={total} unknown={unk} ({100*unk/total:.1f}%) -> {out} (git-ignored — 코에이 IP)")
    for code, n in result["counts"].items():
        print(f"  {code:16s} {n:6d} ({100*n/total:4.1f}%)")


if __name__ == "__main__":
    main()
