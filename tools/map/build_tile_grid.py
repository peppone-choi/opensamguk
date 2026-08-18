#!/usr/bin/env python3
"""타일 격자 패키저 — 렌더러 산출물을 프런트가 먹을 수 있는 한 파일로 굽는다.

`build_terrain_grid.py` 가 만든 `data/map/terrain-grid.json`(256×256 지형·소유·지역)과
`build_han_places.py` 의 `data/map/han-places.json`(1092 군현 좌표)을 읽어
`data/map/han-tiles.json` 을 낸다. 지형을 **유도하지 않는다** — 유도는 렌더러가 이미 했다.

이 스크립트가 하는 일은 셋뿐이다.
  1. 셀 격자를 압축한다. 지형은 셀당 한 글자, 소유는 런렝스.
  2. 도로를 셀로 굽고 이웃 비트마스크를 매긴다. 타일 아트가 서로 이어지려면 필요하다.
  3. 지역(태행산·화북평원 …)을 셀 목록이 아니라 라벨 놓을 무게중심 하나로 줄인다.

이전 판은 che 도로 그래프에서 지형을 지어냈다(96×96, 도로에서 멀면 산). 실제 지형 격자가
생겼으므로 그 추정은 버린다 — 두 개의 지형 진실을 두면 어느 쪽이 맞는지 아무도 모른다.

    python3 tools/map/build_tile_grid.py
    python3 tools/map/build_tile_grid.py --check   # 손편집 드리프트 검사
"""

from __future__ import annotations

import argparse
import json
import sys
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
MAP = ROOT / "data" / "map"
GRID = MAP / "terrain-grid.json"
PLACES = MAP / "han-places.json"
OUT = MAP / "han-tiles.json"

# 도로 비트마스크 — 이웃 4방향(N=1 E=2 S=4 W=8). 타일 16종이 서로 이어진다.
NEI = {(0, -1): 1, (1, 0): 2, (0, 1): 4, (-1, 0): 8}


def rle(rows: list[list[int]]) -> list[list[int]]:
    """[[값, 반복], …] 로 평탄화. 소유 격자는 1092종이라 한 글자에 안 들어간다.

    보로노이 소유는 큰 덩어리로 뭉쳐 있어 런렝스가 잘 먹는다(65536셀 → 수천 쌍).
    """
    out: list[list[int]] = []
    for row in rows:
        for v in row:
            if out and out[-1][0] == v:
                out[-1][1] += 1
            else:
                out.append([v, 1])
    return out


def build() -> dict:
    grid = json.loads(GRID.read_text())
    places = json.loads(PLACES.read_text())["places"]
    cols, rows = grid["cols"], grid["rows"]

    # 도로: 허브(郡治) 사이 직선을 셀로 굽는다. 육로와 해로는 타일이 달라 따로 센다.
    # 경로는 렌더러가 이미 지형 통행비용 위에서 뽑아 셀 목록으로 준다. 여기서 다시 직선을
    # 그으면 그 계산을 버리는 셈이다 — 예전 판이 그랬고, 그래서 도로가 산을 관통했다.
    cells: dict[tuple[int, int], str] = {}
    for r in grid["roads"]:
        for gx, gy in r["cells"]:
            # 한 셀에 육로와 해로가 겹치면 육로가 이긴다 — 걸어서 갈 수 있으면 배는 선택지다.
            if r["kind"] == "LAND" or (gx, gy) not in cells:
                cells[(gx, gy)] = r["kind"]

    roads = {}
    for (gx, gy), kind in sorted(cells.items()):
        m = 0
        for (dx, dy), bit in NEI.items():
            if (gx + dx, gy + dy) in cells:
                m |= bit
        roads[f"{gx},{gy}"] = m if kind == "LAND" else m | 16   # 비트 16 = 수로

    # 지역은 셀 목록 대신 라벨 하나로 줄인다. 65536셀을 프런트에 넘길 이유가 없다.
    acc: dict[int, list[int]] = {}
    for y, row in enumerate(grid["region"]):
        for x, rid in enumerate(row):
            if rid >= 0:
                a = acc.setdefault(rid, [0, 0, 0])
                a[0] += x; a[1] += y; a[2] += 1
    regions = []
    for rid, (sx, sy, c) in sorted(acc.items()):
        # regionNames 원소는 {name, zh, cls} 다. 통째로 넣으면 라벨이 dict 가 된다.
        nm = grid["regionNames"][rid]
        regions.append({"name": nm["zh"] or nm["name"], "en": nm["name"], "cls": nm["cls"],
                        "col": round(sx / c), "row": round(sy / c), "cells": c})

    hubs = set(grid["hubs"])
    cities = [{"id": p["id"], "name": p["nameFt"], "nameCh": p["nameCh"], "level": p.get("level", 5),
               "kind": p["kind"], "seat": i in hubs, "col": p["gx"], "row": p["gy"],
               "lon": p["lon"], "lat": p["lat"]}
              for i, p in enumerate(places)]

    kinds = Counter(c["kind"] for c in cities)
    owner = rle(grid["owner"])
    # 압축은 조용히 틀리는 종류의 코드다 — 되돌렸을 때 크기가 맞는지 여기서 깨뜨린다.
    assert sum(c for _, c in owner) == cols * rows, "소유 런렝스 총합이 격자 크기와 다르다"
    assert all(len(r) == cols for r in grid["terrain"]) and len(grid["terrain"]) == rows, "지형 행/열"
    off = [c["name"] for c in cities if c["seat"] and grid["terrain"][c["row"]][c["col"]] == 0]
    assert not off, f"군치가 바다 위에 있다: {off[:5]}"
    return {
        "_meta": {
            "source": f"{GRID.name} + {PLACES.name}",
            "generator": "tools/map/build_tile_grid.py",
            "note": "지형·소유는 렌더러 산출물 그대로다. 이 파일은 압축·비트마스크·라벨만 더한다.",
            "cols": cols, "rows": rows,
            "year": grid["year"],
            "projection": grid["projection"],
            "terrainLegend": grid["legend"],
            "roadMaskBits": {"N": 1, "E": 2, "S": 4, "W": 8, "WATER": 16},
            "ownerEncoding": "run-length [[placeIndex, count], …] · row-major · -1 = 바다",
            "counts": {"cities": len(cities), "seats": len(hubs), "roadCells": len(roads),
                       "fords": len(grid.get("fords", [])), "regions": len(regions),
                       "adjCounty": len(grid["adjacency"]["county"]),
                       "adjCommandery": len(grid["adjacency"]["commandery"]), **kinds},
        },
        "terrain": ["".join(str(v) for v in row) for row in grid["terrain"]],
        "owner": owner,
        "roads": roads,
        "regions": regions,
        # 이동 그래프. 길이 아니라 영역 인접이다.
        "adjacency": grid["adjacency"],
        # 郡 명부. adjacency 의 a/b 가 이 배열의 인덱스다 — 이름 없이 인덱스만 넘기면
        # 프런트가 어느 郡인지 알 수 없다.
        "juns": [{"name": nm, "seat": h, "col": places[h]["gx"], "row": places[h]["gy"]}
                 for nm, h in zip(grid["junNames"], grid["hubs"])],
        "seatOwner": rle(grid["seatOwner"]),
        "fords": grid.get("fords", []),
        "cities": cities,
    }


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--check", action="store_true")
    args = ap.parse_args()
    for src in (GRID, PLACES):
        if not src.exists():
            sys.exit(f"{src.relative_to(ROOT)} 가 없다. tools/map/build_terrain_grid.py 를 먼저 돌려라.")
    blob = json.dumps(build(), ensure_ascii=False, separators=(",", ":")) + "\n"
    if args.check:
        if OUT.exists() and OUT.read_text() == blob:
            print("드리프트 없음.")
            return 0
        print(f"드리프트: {OUT.relative_to(ROOT)}")
        return 1
    OUT.write_text(blob)
    meta = json.loads(blob)["_meta"]["counts"]
    print(f"{OUT.relative_to(ROOT)} · {len(blob)/1e6:.1f}MB · " +
          " · ".join(f"{k} {v}" for k, v in meta.items()))
    return 0


if __name__ == "__main__":
    sys.exit(main())
