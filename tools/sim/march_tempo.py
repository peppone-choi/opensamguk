#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""행군 템포 기준선 — 재설계 §15.2 S2, 이슈 #778.

엔진 밖 계산이다. han-tiles 省 그래프 위에서 城 → 城 최단 경로를 km 로 재고,
후보 속도(km/순)·지형 계수마다 소요 순을 낸다. 어떤 값도 게임 수치가 아니다 —
사용자가 목표 템포 표를 고르기 위한 근거 표다.

격자 → km 변환: han-tiles `_meta` 에 경위도 범위가 없어 `cities[]` 의 (col,row)↔(lon,lat)
쌍을 최소제곱으로 맞춘 축척을 쓴다(2026-09-17 실측: 0.0534°/col · 0.0461°/row, 잔차 최대 약 2.8°
— 城 칸은 지형에 맞춰 밀린 씨앗이라 개별 좌표는 어긋나도 축척은 안정적이다).
ADR-LITE-053 의 「lon 80.5–116.6」 범위는 이 격자와 맞지 않는다.
"""
from __future__ import annotations

import argparse
import collections
import heapq
import json
import math
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
TILES = ROOT / "data/map/han-tiles.json"
ROUGH = {"MOUNTAIN", "PLATEAU", "HILL", "DESERT"}
WATER = {"SEA", "LAKE", "OUT_OF_SCOPE"}

# 후보값(EXPLORATORY). 표를 만들기 위한 입력일 뿐이다.
SPEEDS_KM_PER_TURN = (20, 30, 45, 60)
ROUGH_FACTORS = (1.0, 1.5, 2.0)
# 구간은 han-tiles 縣 nameCh(簡體)로 지정한다 — 한글 城 이름은 동명이 많다(복양 = 濮陽/復陽).
ROUTES = (
    ("陈留县", "东武阳县"), ("陈留县", "许县"), ("许县", "邺县"), ("雒阳县", "长安县"),
    ("长安县", "成都县"), ("襄阳县", "江陵县"), ("邺县", "涿县"), ("寿春县", "建业县"),
)


def _fit(xs, ys):
    n = len(xs)
    mx, my = sum(xs) / n, sum(ys) / n
    b = sum((x - mx) * (y - my) for x, y in zip(xs, ys)) / sum((x - mx) ** 2 for x in xs)
    return my - b * mx, b


class Graph:
    def __init__(self, tiles: dict):
        meta = tiles["_meta"]
        self.cols, self.rows = meta["cols"], meta["rows"]
        legend = {int(k): v for k, v in meta["terrainLegend"].items()}
        terrain = "".join(tiles["terrain"])
        owner: list[int] = []
        for prov, count in tiles["owner"]:
            owner.extend([prov] * count)
        n = collections.Counter()
        sx = collections.defaultdict(float)
        sy = collections.defaultdict(float)
        rough = collections.Counter()
        for i, prov in enumerate(owner):
            if prov < 0 or legend[int(terrain[i])] in WATER:
                continue
            n[prov] += 1
            sx[prov] += i % self.cols
            sy[prov] += i // self.cols
            if legend[int(terrain[i])] in ROUGH:
                rough[prov] += 1
        pts = [(c["col"], c["row"], c["lon"], c["lat"]) for c in tiles["cities"]
               if c.get("lon") is not None and c.get("col") is not None]
        self.lon0, self.dlon = _fit([p[0] for p in pts], [p[2] for p in pts])
        self.lat0, self.dlat = _fit([p[1] for p in pts], [p[3] for p in pts])
        self.center = {p: (sx[p] / n[p], sy[p] / n[p]) for p in n}
        self.rough_share = {p: rough[p] / n[p] for p in n}
        self.adj = collections.defaultdict(set)
        for e in tiles["adjacency"]["county"]:
            a, b = e["a"], e["b"]
            if a in self.center and b in self.center:
                self.adj[a].add(b)
                self.adj[b].add(a)

    def km(self, a: int, b: int) -> float:
        (x1, y1), (x2, y2) = self.center[a], self.center[b]
        lat = self.lat0 + ((y1 + y2) / 2) * self.dlat
        return math.hypot((x2 - x1) * self.dlon * 111.32 * math.cos(math.radians(lat)), (y2 - y1) * self.dlat * 110.57)

    def shortest(self, src: int, dst: int, rough_factor: float):
        """(비용 km, 실제 km, 간선 수 — 경유 省 수는 +1). 비용 = km × (1 + (계수-1) × 두 省 험지 비율 평균)."""
        best = {src: (0.0, 0.0, 0)}
        heap = [(0.0, src)]
        while heap:
            cost, u = heapq.heappop(heap)
            if u == dst:
                return best[u]
            if cost > best[u][0]:
                continue
            for v in sorted(self.adj[u]):
                d = self.km(u, v)
                share = (self.rough_share[u] + self.rough_share[v]) / 2
                c = cost + d * (1 + (rough_factor - 1) * share)
                if v not in best or c < best[v][0]:
                    best[v] = (c, best[u][1] + d, best[u][2] + 1)
                    heapq.heappush(heap, (c, v))
        return None


def county(tiles: dict, name_ch: str) -> dict:
    hits = [j for j in tiles["jurisdictionRecords"] if j["nameCh"] == name_ch]
    if len(hits) != 1:
        raise ValueError(f"county {name_ch!r} matched {len(hits)} jurisdictions")
    seat = [c for c in tiles["cities"] if str(c.get("id")) == str(hits[0].get("seatPlaceId"))]
    prov = [i for i, p in enumerate(tiles["provinceRecords"]) if p["id"] == hits[0]["seatPlaceId"]]
    if len(prov) != 1:
        raise ValueError(f"county {name_ch!r} seat province not found")
    return {"province": prov[0], "lonlat": (seat[0]["lon"], seat[0]["lat"]) if seat else None}


def haversine(a, b) -> float:
    (lon1, lat1), (lon2, lat2) = a, b
    p1, p2 = math.radians(lat1), math.radians(lat2)
    h = math.sin((p2 - p1) / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(math.radians(lon2 - lon1) / 2) ** 2
    return 2 * 6371.0 * math.asin(math.sqrt(h))


def table(tiles: dict) -> list[dict]:
    g = Graph(tiles)
    out = []
    for a, b in ROUTES:
        ca, cb = county(tiles, a), county(tiles, b)
        src, dst = ca["province"], cb["province"]
        straight = round(haversine(ca["lonlat"], cb["lonlat"]), 1) if ca["lonlat"] and cb["lonlat"] else None
        for f in ROUGH_FACTORS:
            r = g.shortest(src, dst, f)
            if r is None:
                out.append({"from": a, "to": b, "roughFactor": f, "reachable": False})
                continue
            cost, real, hops = r
            out.append({
                "from": a, "to": b, "roughFactor": f, "reachable": True,
                "straightKm": straight, "km": round(real, 1), "costKm": round(cost, 1), "provinces": hops,
                "turns": {str(s): math.ceil(cost / s) for s in SPEEDS_KM_PER_TURN},
            })
    return out


def main(argv=None) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--json", action="store_true")
    args = ap.parse_args(argv)
    rows = table(json.loads(TILES.read_text(encoding="utf-8")))
    if args.json:
        print(json.dumps(rows, ensure_ascii=False, indent=1))
        return 0
    print("| 구간 | 험지 계수 | 직선 km(경위도) | 경로 km(격자 근사) | 간선 수 | " + " | ".join(f"{s} km/순" for s in SPEEDS_KM_PER_TURN) + " |")
    print("|---|---|---|---|---|" + "---|" * len(SPEEDS_KM_PER_TURN))
    for r in rows:
        if not r["reachable"]:
            print(f"| {r['from']}→{r['to']} | {r['roughFactor']} | 도달 불가 | | " + " | ".join("" for _ in SPEEDS_KM_PER_TURN) + " |")
            continue
        print(f"| {r['from']}→{r['to']} | {r['roughFactor']} | {r['straightKm']} | {r['km']} | {r['provinces']} | " + " | ".join(str(r["turns"][str(s)]) for s in SPEEDS_KM_PER_TURN) + " |")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
