#!/usr/bin/env python3
"""지명의 경위도에서 게임 격자(col/row)를 다시 만든다.

**왜 다시 만드는가.** 현행 `han-tiles.json` 의 격자 배치는 지리와 크게 어긋난 곳이 있고,
`connections`(도시 인접)가 **격자에서 파생**되므로 그 어긋남이 그대로 게임 연결로 흘러든다.
실측:

* 극현(劇縣, 北海國 治所)이 자기 郡에서 30열 서쪽에 찍혀 있다. 산둥 권역 132城 회귀에서
  잔차 −5.9σ(그 다음이 −1.2σ). 그 결과 北海國 16城이 **4조각**으로 끊기고, 수도 조각은 2城뿐이라
  나머지 14城이 개시부터 보급 절단 → 공융이 몇 달 만에 증발한다.
* 100郡國 중 **48개**가 지도 위에서 하나로 이어지지 않는다(관련 477城).

**설계.**

1. 경위도를 등적에 가까운 평면으로 투영한다(위도별 cos 보정 — 한 칸의 실제 거리가 남북에서
   크게 달라지지 않게).
2. 목표 해상도로 스케일해 각 지명의 **이상적인 칸**을 구한다.
3. 칸이 겹치면 **가장 가까운 빈 칸**으로 나선 탐색한다. 탐색 반경에 상한을 두고, 상한을 넘으면
   실패로 보고한다 — 조용히 30칸 밖으로 던지지 않는다. 그게 극현에 일어난 일이다.

받아들임 기준은 두 가지이고 둘 다 이 파일이 직접 잰다.

* **변위**: 이상적인 칸에서 얼마나 밀렸나(칸). 상한을 넘으면 실패.
* **郡 연결성**: 같은 郡의 城이 격자 인접 그래프에서 하나로 이어지는가. 이게 게임에서
  보급을 결정하므로 진짜 기준이다.

사용:
    python3 tools/map/build_place_grid.py --report
    python3 tools/map/build_place_grid.py --output /tmp/grid.json
"""

from __future__ import annotations

import argparse
import json
import math
from collections import defaultdict
from pathlib import Path
from typing import Any, Iterable

ROOT = Path(__file__).resolve().parents[2]
TILES_PATH = ROOT / "data/map/han-tiles.json"
RUNTIME_MAP_PATH = ROOT / "infra/src/main/resources/map/han-world-v3.json"

#: 현행 격자와 같은 배율대를 쓴다 — 한 칸이 대략 같은 크기라야 기존 화면 축척이 유지된다.
DEFAULT_COLS_PER_DEGREE_LON = 19.0
#: 위도 1도는 경도 1도보다 항상 길다. 격자 칸을 정사각에 가깝게 두려면 같은 배율을 쓰되
#: 경도 쪽을 cos(위도)로 줄인다(아래 project 참조).
DEFAULT_ROWS_PER_DEGREE_LAT = 19.0
#: 나선 탐색 상한(칸). 이보다 멀리 밀어야 한다면 해상도가 모자란 것이고, 조용히 던지지 않는다.
DEFAULT_MAX_DISPLACEMENT = 6


def load_places() -> list[dict[str, Any]]:
    tiles = json.loads(TILES_PATH.read_text(encoding="utf-8"))
    return [c for c in tiles["cities"] if isinstance(c.get("lon"), (int, float))]


def project(
    places: Iterable[dict[str, Any]],
    cols_per_degree: float,
    rows_per_degree: float,
) -> dict[str, tuple[float, float]]:
    """경위도 → 연속 평면 좌표. 경도는 그 위도의 cos 로 줄여 칸의 실제 크기를 맞춘다."""
    places = list(places)
    mean_lat = sum(p["lat"] for p in places) / len(places)
    scale = math.cos(math.radians(mean_lat))
    return {
        p["id"]: (p["lon"] * cols_per_degree * scale, -p["lat"] * rows_per_degree)
        for p in places
    }


def _spiral(max_radius: int) -> Iterable[tuple[int, int]]:
    """(0,0) 부터 반경 순으로 칸을 훑는다. 같은 반경 안에서는 결정적 순서다."""
    yield 0, 0
    for radius in range(1, max_radius + 1):
        for dx in range(-radius, radius + 1):
            for dy in range(-radius, radius + 1):
                if max(abs(dx), abs(dy)) == radius:
                    yield dx, dy


def assign(
    places: list[dict[str, Any]],
    cols_per_degree: float = DEFAULT_COLS_PER_DEGREE_LON,
    rows_per_degree: float = DEFAULT_ROWS_PER_DEGREE_LAT,
    max_displacement: int = DEFAULT_MAX_DISPLACEMENT,
) -> tuple[dict[str, tuple[int, int]], list[str]]:
    """각 지명에 격자 칸을 준다. 겹치면 가장 가까운 빈 칸으로, 상한을 넘으면 실패로 남긴다."""
    planar = project(places, cols_per_degree, rows_per_degree)
    min_x = min(x for x, _ in planar.values())
    min_y = min(y for _, y in planar.values())

    ideal = {
        pid: (round(x - min_x), round(y - min_y)) for pid, (x, y) in planar.items()
    }
    # 배치 순서는 결정적이어야 한다 — 이상적인 칸, 그 다음 id 사전순.
    order = sorted(places, key=lambda p: (ideal[p["id"]][1], ideal[p["id"]][0], p["id"]))

    taken: set[tuple[int, int]] = set()
    placed: dict[str, tuple[int, int]] = {}
    errors: list[str] = []
    offsets = list(_spiral(max_displacement))
    for place in order:
        cx, cy = ideal[place["id"]]
        for dx, dy in offsets:
            cell = (cx + dx, cy + dy)
            if cell not in taken:
                taken.add(cell)
                placed[place["id"]] = cell
                break
        else:
            errors.append(
                f"{place['name']}({place.get('nameCh')}) could not be placed within "
                f"{max_displacement} cells of its projected position"
            )
    return placed, errors


def displacement_report(
    places: list[dict[str, Any]],
    placed: dict[str, tuple[int, int]],
    cols_per_degree: float,
    rows_per_degree: float,
) -> list[tuple[float, dict[str, Any]]]:
    planar = project(places, cols_per_degree, rows_per_degree)
    min_x = min(x for x, _ in planar.values())
    min_y = min(y for _, y in planar.values())
    out = []
    for place in places:
        if place["id"] not in placed:
            continue
        ix, iy = planar[place["id"]][0] - min_x, planar[place["id"]][1] - min_y
        cx, cy = placed[place["id"]]
        out.append((math.hypot(cx - ix, cy - iy), place))
    out.sort(key=lambda r: -r[0])
    return out


def gabriel_edges(pos: dict[str, tuple[int, int]], candidates: int = 24) -> set[tuple[str, str]]:
    """가브리엘 그래프 — 두 점을 지름으로 하는 원 안에 다른 점이 없으면 잇는다.

    현행 지도의 연결 규칙을 실측해 맞춘 것이다: 평균 차수 4.10 · 완전 대칭 · 간선 길이
    중앙값 8.1. 가브리엘 그래프가 그 특성을 그대로 낸다(실측 평균 차수 4.05).
    후보를 최근접 `candidates` 개로 좁히는 것은 O(n^3) 을 피하기 위해서다 — 가브리엘 조건은
    가까운 점만 깨뜨릴 수 있으므로 결과가 달라지지 않는다.
    """
    points = [(cell[0], cell[1], pid) for pid, cell in pos.items()]
    edges: set[tuple[str, str]] = set()
    for x, y, i in points:
        near = sorted(points, key=lambda q: (q[0] - x) ** 2 + (q[1] - y) ** 2)[1 : candidates + 1]
        for xj, yj, j in near:
            mx, my = (x + xj) / 2, (y + yj) / 2
            radius2 = ((x - xj) ** 2 + (y - yj) ** 2) / 4
            if all(
                (xk - mx) ** 2 + (yk - my) ** 2 >= radius2 - 1e-9
                for xk, yk, kk in near
                if kk not in (i, j)
            ):
                edges.add(tuple(sorted((i, j))))
    return edges


def complete_commanderies(
    pos: dict[str, tuple[int, int]],
    edges: set[tuple[str, str]],
    members_by_jun: dict[str, list[str]],
) -> list[tuple[float, str, str, str]]:
    """같은 郡의 城이 하나로 이어질 때까지 **가장 짧은** 간선을 더한다.

    후한의 郡은 연속된 행정 구역이므로 그 안의 縣들은 郡을 벗어나지 않고 서로 닿아야 한다.
    근접 그래프만으로는 그걸 모른다 — 다른 郡의 縣이 더 가까우면 같은 郡의 두 縣 사이 간선이
    막힌다(실측 70郡國이 끊겼다).

    그래서 연결성은 **구성 불변식**으로 세운다. 대신 검사는 축을 바꿔서 「더한 간선이 짧은가」를
    본다 — 연결성 자체를 강제해 놓고 연결성을 세는 검사는 아무것도 증명하지 않는다.
    """
    adjacency: dict[str, set[str]] = defaultdict(set)
    for a, b in edges:
        adjacency[a].add(b)
        adjacency[b].add(a)

    added: list[tuple[float, str, str, str]] = []
    for jun, members in sorted(members_by_jun.items()):
        scope = set(members)
        while True:
            seen: set[str] = set()
            components: list[set[str]] = []
            for member in members:
                if member in seen:
                    continue
                stack, component = [member], {member}
                seen.add(member)
                while stack:
                    current = stack.pop()
                    for neighbour in adjacency[current]:
                        if neighbour in scope and neighbour not in component:
                            component.add(neighbour)
                            seen.add(neighbour)
                            stack.append(neighbour)
                components.append(component)
            if len(components) < 2:
                break
            best = min(
                (
                    (math.dist(pos[a], pos[b]), a, b)
                    for i, left in enumerate(components)
                    for right in components[i + 1 :]
                    for a in left
                    for b in right
                ),
                key=lambda t: t[0],
            )
            distance, a, b = best
            adjacency[a].add(b)
            adjacency[b].add(a)
            edges.add(tuple(sorted((a, b))))
            added.append((distance, jun, a, b))
    added.sort(reverse=True)
    return added


def members_by_jun(placed: dict[str, tuple[int, int]]) -> dict[str, list[str]]:
    """런타임 지도의 (郡 → 물리 장소 id) 소속표. 城이 아니라 물리 장소 기준이다."""
    runtime = json.loads(RUNTIME_MAP_PATH.read_text(encoding="utf-8"))
    out: dict[str, list[str]] = defaultdict(list)
    for city in runtime["cities"]:
        ref = (city.get("physicalPlaceRef") or "").split(":")[-1]
        if ref in placed:
            out[city["meta"].get("junCh")].append(ref)
    return dict(out)


def commandery_components(placed: dict[str, tuple[int, int]]) -> list[tuple[str, list[int]]]:
    """같은 郡의 城이 격자 8방 인접으로 하나로 이어지는지. 게임 보급이 여기서 나온다."""
    runtime = json.loads(RUNTIME_MAP_PATH.read_text(encoding="utf-8"))
    by_jun: dict[str, list[tuple[int, int]]] = defaultdict(list)
    for city in runtime["cities"]:
        ref = (city.get("physicalPlaceRef") or "").split(":")[-1]
        cell = placed.get(ref)
        if cell is not None:
            by_jun[city["meta"].get("junCh")].append(cell)

    broken: list[tuple[str, list[int]]] = []
    for jun, cells in by_jun.items():
        remaining = set(cells)
        sizes = []
        while remaining:
            seed = remaining.pop()
            component = {seed}
            stack = [seed]
            while stack:
                x, y = stack.pop()
                for dx in (-1, 0, 1):
                    for dy in (-1, 0, 1):
                        neighbour = (x + dx, y + dy)
                        if neighbour in remaining:
                            remaining.discard(neighbour)
                            component.add(neighbour)
                            stack.append(neighbour)
            sizes.append(len(component))
        if len(sizes) > 1:
            broken.append((jun, sorted(sizes, reverse=True)))
    broken.sort(key=lambda r: -len(r[1]))
    return broken


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--cols-per-degree", type=float, default=DEFAULT_COLS_PER_DEGREE_LON)
    parser.add_argument("--rows-per-degree", type=float, default=DEFAULT_ROWS_PER_DEGREE_LAT)
    parser.add_argument("--max-displacement", type=int, default=DEFAULT_MAX_DISPLACEMENT)
    parser.add_argument("--report", action="store_true")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()

    places = load_places()
    placed, errors = assign(
        places, args.cols_per_degree, args.rows_per_degree, args.max_displacement
    )

    if args.output:
        args.output.write_text(
            json.dumps(
                {pid: {"col": c[0], "row": c[1]} for pid, c in sorted(placed.items())},
                ensure_ascii=False,
                indent=1,
            )
            + "\n",
            encoding="utf-8",
        )

    if args.report:
        disp = displacement_report(places, placed, args.cols_per_degree, args.rows_per_degree)
        values = sorted(d for d, _ in disp)
        print(f"지명 {len(places)}개, 배치 {len(placed)}개, 배치 실패 {len(errors)}개")
        if values:
            print(
                "변위(칸)  중앙값 %.2f  90%% %.2f  99%% %.2f  최대 %.2f"
                % (
                    values[len(values) // 2],
                    values[int(len(values) * 0.9)],
                    values[int(len(values) * 0.99)],
                    values[-1],
                )
            )
        members = members_by_jun(placed)
        edges = gabriel_edges(placed)
        print("가브리엘 연결: 간선 %d, 평균 차수 %.2f" % (len(edges), 2 * len(edges) / len(placed)))
        added = complete_commanderies(placed, edges, members)
        lengths = sorted(d for d, _, _, _ in added)
        print("郡 연결 보정 간선 %d개" % len(added))
        if lengths:
            print(
                "  길이 중앙값 %.1f  90%% %.1f  최대 %.1f"
                % (lengths[len(lengths) // 2], lengths[int(len(lengths) * 0.9)], lengths[-1])
            )
            print("  가장 긴 8건: " + ", ".join(f"{jun} {d:.0f}" for d, jun, _, _ in added[:8]))
        for message in errors[:10]:
            print(f"  배치 실패: {message}")
        if len(errors) > 10:
            print(f"  … and {len(errors) - 10} more")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
