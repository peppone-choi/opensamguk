#!/usr/bin/env python3
"""아이소메트릭 타일 격자 생성기 (결정적).

`data/extracted/map/*.json`(MIT, legacy/devsam-core 유래)의 도시 좌표·연결만 읽어
`data/map/<code>-tiles.json` 타일 격자를 만든다. **CDN 배경 래스터를 읽지 않는다** —
`opensamguk-images`의 `game/**`은 권리 UNKNOWN이라(에셋 감사 §1-2) 파생이 막혀 있다.
지형은 도시·도로에서 절차적으로 유도한 우리 저작물이다.

    python3 tools/map/build_tile_grid.py
    python3 tools/map/build_tile_grid.py --check   # 손편집 드리프트 검사

격자는 평면 (x,y)를 격자 좌표로 정규화만 한다. 아이소 회전은 렌더 단계에서 한다
(격자는 투영에 독립 — 나중에 평면으로 되돌려도 같은 데이터를 쓴다).
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SRC = ROOT / "data" / "extracted" / "map"
OUT = ROOT / "data" / "map"

COLS, ROWS = 96, 96
# 도시/도로에서 이 반경(셀) 안이면 육지. 밖은 바다.
LAND_R = 4
# 육지지만 도로에서 이만큼 먼 셀은 산지 — 길은 고개를 따라 난다는 통념대로 읽힌다.
MOUNTAIN_R = 6

SEA, PLAIN, MOUNTAIN = 0, 1, 2


def grid_pos(x: int, y: int, w: int, h: int) -> tuple[int, int]:
    """평면 좌표 → 격자 좌표. 가장자리 셀을 비워 두려고 1..COLS-2 로 클램프한다."""
    gx = 1 + round((x / w) * (COLS - 3))
    gy = 1 + round((y / h) * (ROWS - 3))
    return gx, gy


def line_cells(a: tuple[int, int], b: tuple[int, int]) -> list[tuple[int, int]]:
    """두 격자점을 잇는 셀 — 정수 브레젠험."""
    (x0, y0), (x1, y1) = a, b
    dx, dy = abs(x1 - x0), -abs(y1 - y0)
    sx, sy = (1 if x0 < x1 else -1), (1 if y0 < y1 else -1)
    err = dx + dy
    out = []
    while True:
        out.append((x0, y0))
        if (x0, y0) == (x1, y1):
            return out
        e2 = 2 * err
        if e2 >= dy:
            err += dy
            x0 += sx
        if e2 <= dx:
            err += dx
            y0 += sy


def dilate(seeds: set[tuple[int, int]], r: int) -> set[tuple[int, int]]:
    """체비쇼프가 아니라 원형 팽창 — 사각형 해안선이 생기지 않게."""
    out = set()
    for (cx, cy) in seeds:
        for dy in range(-r, r + 1):
            for dx in range(-r, r + 1):
                if dx * dx + dy * dy <= r * r:
                    p = (cx + dx, cy + dy)
                    if 0 <= p[0] < COLS and 0 <= p[1] < ROWS:
                        out.add(p)
    return out


# 도로 비트마스크 — 이웃 4방향(N=1 E=2 S=4 W=8). 타일 16종이 서로 이어진다.
NEI = {(0, -1): 1, (1, 0): 2, (0, 1): 4, (-1, 0): 8}


def build(code: str) -> dict:
    data = json.loads((SRC / f"{code}.json").read_text())
    cities = data["cities"]
    w = data.get("_meta", {}).get("width", 700)
    h = data.get("_meta", {}).get("height", 500)

    anchors: dict[int, tuple[int, int]] = {}
    for c in cities:
        anchors[c["id"]] = grid_pos(c["position"]["x"], c["position"]["y"], w, h)
    if len(set(anchors.values())) != len(anchors):
        dup = len(anchors) - len(set(anchors.values()))
        raise SystemExit(f"격자 해상도 부족 — 도시 {dup}개가 같은 셀에 겹친다. COLS/ROWS를 올려라.")

    road: set[tuple[int, int]] = set()
    edges = set()
    for c in cities:
        for n in c["connections"]:
            if n in anchors:
                edges.add(tuple(sorted((c["id"], n))))
    for a, b in sorted(edges):
        road.update(line_cells(anchors[a], anchors[b]))

    land = dilate(set(anchors.values()) | road, LAND_R)
    near_road = dilate(road, MOUNTAIN_R)

    terrain = [[SEA] * COLS for _ in range(ROWS)]
    for (gx, gy) in land:
        terrain[gy][gx] = PLAIN if (gx, gy) in near_road else MOUNTAIN
    for (gx, gy) in anchors.values():
        terrain[gy][gx] = PLAIN

    mask = {}
    for (gx, gy) in road:
        m = 0
        for (dx, dy), bit in NEI.items():
            if (gx + dx, gy + dy) in road:
                m |= bit
        mask[f"{gx},{gy}"] = m

    return {
        "_meta": {
            "source": f"data/extracted/map/{code}.json (MIT, devsam-core)",
            "generator": "tools/map/build_tile_grid.py",
            "note": "지형은 도시·도로에서 유도한 자작물. CDN 래스터 미사용(권리 UNKNOWN).",
            "cols": COLS, "rows": ROWS,
            "landRadius": LAND_R, "mountainRadius": MOUNTAIN_R,
            "terrainLegend": {"0": "sea", "1": "plain", "2": "mountain"},
            "roadMaskBits": {"N": 1, "E": 2, "S": 4, "W": 8},
        },
        "terrain": ["".join(str(v) for v in row) for row in terrain],
        "roads": mask,
        "cities": {str(cid): {"col": p[0], "row": p[1]} for cid, p in sorted(anchors.items())},
    }


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--check", action="store_true")
    args = ap.parse_args()
    OUT.mkdir(parents=True, exist_ok=True)
    bad = 0
    for src in sorted(SRC.glob("*.json")):
        code = src.stem
        blob = json.dumps(build(code), ensure_ascii=False, indent=1, sort_keys=False) + "\n"
        dst = OUT / f"{code}-tiles.json"
        if args.check:
            if not dst.exists() or dst.read_text() != blob:
                print(f"드리프트: {dst.relative_to(ROOT)}")
                bad += 1
        else:
            dst.write_text(blob)
            print(f"wrote {dst.relative_to(ROOT)}")
    if args.check:
        print("드리프트 없음." if not bad else f"{bad}개 불일치.")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
