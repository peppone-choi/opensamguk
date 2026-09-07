#!/usr/bin/env python3
"""郡 내부 보급선을 굽는다 — 같은 郡의 프로빈스가 보급에서 서로 닿게 하는 최소 간선.

**무엇을 고치는가.** `han-world-v3` 런타임 보급은 프로빈스 소유 격자에서 물리적으로 맞닿은
프로빈스끼리만 흐른다. 그런데 그 격자에서 같은 郡의 프로빈스들이 조각으로 끊겨 있다 —
프로덕션에서 공융의 北海國 16城 중 **14城**이 개시 시점부터 수도에 닿지 않았고, 절단된 城은
매턴 10% 쇠퇴 → 민심 30 미만 → 중립화로 잃도록 예정돼 있었다.

**왜 지도를 고치지 않고 규칙을 더하는가.** 소유 격자를 실제로 고쳐도 개선이 금방 멎는다 —
가장 어긋난 프로빈스 31개를 옳은 자리로 옮겨도 scenario_1020 절단이 102 → 90 이고 공융은
4城이 남는다. 남은 절단은 개별 프로빈스가 밀린 게 아니라 래스터 분할 자체가 성겨서 생긴다.
반면 이 보급선은 지도를 전혀 건드리지 않고 102 → 40, 공융 0 이다(같은 모델 위 실측).

**규칙.** 郡은 후한의 행정·병참 단위다. 그래서 **보급**은 물리적 인접뿐 아니라 행정선을 따라서도
흐른다(ADR-LITE-051). **이동(traversal)은 바뀌지 않는다** — 전략 위상의 LAND 간선은 여전히
래스터에서 맞닿은 프로빈스끼리만 허용한다(`_land_owners_are_adjacent`).

**동명이지는 제외한다.** 좌표가 틀린 縣을 이으면 郡을 가로지르는 가짜 보급선이 생긴다.
`county-misbinding-adjudications-v1.json` 이 그 5건을 판정해 두었고 여기서 뺀다. 제외 전에는
보정 간선 최대 길이가 1,190km 였고, 제외 뒤 중앙값 47km · 90% 161km 다 — 실제 인접한 縣들이다.

사용:
    python3 tools/map/build_commandery_supply_links.py
    python3 tools/map/build_commandery_supply_links.py --check
"""

from __future__ import annotations

import argparse
import json
import math
from collections import defaultdict, deque
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[2]
TILES_PATH = ROOT / "data/map/han-tiles.json"
RUNTIME_MAP_PATH = ROOT / "infra/src/main/resources/map/han-world-v3.json"
MISBINDING_PATH = ROOT / "data/curated/han/county-misbinding-adjudications-v1.json"
SUPPLY_ADJUDICATIONS_PATH = ROOT / "data/curated/han/supply-disconnection-adjudications-v3.json"
OUTPUT_PATH = ROOT / "data/map/han-commandery-supply-links-v1.json"


def _load(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def _grid_adjacency(tiles: dict[str, Any]) -> dict[int, set[int]]:
    """소유 격자에서 4방으로 맞닿은 프로빈스 쌍. 런타임 보급망과 같은 기준이다."""
    meta = tiles["_meta"]
    cols, rows = meta["cols"], meta["rows"]
    values: list[int] = []
    for value, count in tiles["owner"]:
        values.extend([value] * count)
    if len(values) != cols * rows:
        raise ValueError("han-tiles owner RLE length does not match dimensions")

    adjacency: dict[int, set[int]] = defaultdict(set)

    def link(a: int, b: int) -> None:
        if a >= 0 and b >= 0 and a != b:
            adjacency[a].add(b)
            adjacency[b].add(a)

    for row in range(rows):
        base = row * cols
        for col in range(cols - 1):
            link(values[base + col], values[base + col + 1])
    for row in range(rows - 1):
        base, below = row * cols, (row + 1) * cols
        for col in range(cols):
            link(values[base + col], values[below + col])
    return adjacency


def _distance_km(a: tuple[float, float], b: tuple[float, float]) -> float:
    mean_lat = math.radians((a[1] + b[1]) / 2)
    return math.hypot((a[0] - b[0]) * math.cos(mean_lat), a[1] - b[1]) * 111.0


def build() -> dict[str, Any]:
    tiles = _load(TILES_PATH)
    runtime = _load(RUNTIME_MAP_PATH)
    excluded_names = {
        (row["canonicalGroup"], row["runtimeCityName"])
        for row in _load(MISBINDING_PATH)["adjudications"]
    }

    # 이미 심사를 거쳐 **보호**된 기하 결함은 건드리지 않는다. 그 城들은 「두 보급 모델이 함께
    # 끊겼다고 본다」는 상태로 판정돼 있고, 이 규칙이 그걸 조용히 덮으면 심사 결과를 무효화한다.
    # (실측: 덮었더니 305·548 의 보호 행이 낡아졌고, 그 두 행에 묶인 계약·엔진 테스트가 깨졌다.)
    protected_cities = {
        row["runtimeCityId"]
        for row in _load(SUPPLY_ADJUDICATIONS_PATH).get("decisions", [])
        if row.get("decision", "").startswith("PROTECT_")
    }

    tile_by_id = {str(city["id"]): city for city in tiles["cities"]}
    coordinate: dict[int, tuple[float, float]] = {}
    members: dict[str, list[int]] = defaultdict(list)
    label: dict[int, str] = {}
    for city in runtime["cities"]:
        province = city.get("provinceId")
        tile = tile_by_id.get((city.get("physicalPlaceRef") or "").split(":")[-1])
        if not isinstance(province, int) or tile is None:
            continue
        jun = city["meta"].get("junCh")
        coordinate[province] = (tile["lon"], tile["lat"])
        label[province] = city["name"]
        if (jun, city["name"]) not in excluded_names and city["id"] not in protected_cities:
            members[jun].append(province)

    adjacency = _grid_adjacency(tiles)
    links: list[dict[str, Any]] = []
    for jun, provinces in sorted(members.items()):
        scope = set(provinces)
        while True:
            seen: set[int] = set()
            components: list[set[int]] = []
            for province in provinces:
                if province in seen:
                    continue
                queue, component = deque([province]), {province}
                seen.add(province)
                while queue:
                    current = queue.popleft()
                    for neighbour in adjacency[current]:
                        if neighbour in scope and neighbour not in component:
                            component.add(neighbour)
                            seen.add(neighbour)
                            queue.append(neighbour)
                components.append(component)
            if len(components) < 2:
                break
            best = min(
                (
                    (_distance_km(coordinate[a], coordinate[b]), a, b)
                    for index, left in enumerate(components)
                    for right in components[index + 1:]
                    for a in left
                    for b in right
                ),
                key=lambda item: item[0],
            )
            km, a, b = best
            adjacency[a].add(b)
            adjacency[b].add(a)
            links.append({
                "canonicalGroup": jun,
                "fromProvinceIndex": min(a, b),
                "toProvinceIndex": max(a, b),
                "fromCityName": label[min(a, b)],
                "toCityName": label[max(a, b)],
                "distanceKm": round(km, 1),
            })
    links.sort(key=lambda row: (row["canonicalGroup"], row["fromProvinceIndex"], row["toProvinceIndex"]))
    distances = sorted(row["distanceKm"] for row in links)
    return {
        "schemaVersion": 1,
        "artifactId": "han-commandery-supply-links-v1",
        "note": (
            "郡 내부 보급선. 같은 郡의 프로빈스가 보급에서 서로 닿게 하는 최소 간선이며, "
            "**보급에만** 더해진다 — 이동(전략 위상 LAND 간선)은 바뀌지 않는다. ADR-LITE-051."
        ),
        "generator": "tools/map/build_commandery_supply_links.py",
        "excludedByMisbinding": sorted(f"{group}:{name}" for group, name in excluded_names),
        "excludedByReviewedProtection": sorted(protected_cities),
        "stats": {
            "linkCount": len(links),
            "medianKm": distances[len(distances) // 2] if distances else 0,
            "p90Km": distances[int(len(distances) * 0.9)] if distances else 0,
            "maxKm": distances[-1] if distances else 0,
        },
        "links": links,
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true", help="드리프트가 있으면 exit 1")
    args = parser.parse_args()

    payload = build()
    serialized = json.dumps(payload, ensure_ascii=False, indent=1, sort_keys=False) + "\n"
    if args.check:
        if not OUTPUT_PATH.is_file() or OUTPUT_PATH.read_text(encoding="utf-8") != serialized:
            print(f"드리프트: {OUTPUT_PATH.relative_to(ROOT)}")
            return 1
        print("드리프트 없음 (han-commandery-supply-links).")
        return 0
    OUTPUT_PATH.write_text(serialized, encoding="utf-8")
    stats = payload["stats"]
    print(
        "보급선 %d개 · 길이 중앙값 %.0fkm · 90%% %.0fkm · 최대 %.0fkm"
        % (stats["linkCount"], stats["medianKm"], stats["p90Km"], stats["maxKm"])
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
