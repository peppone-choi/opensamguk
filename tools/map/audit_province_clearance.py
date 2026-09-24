#!/usr/bin/env python3
"""Measure city clearance and fully surrounded provinces on the committed Han grid.

The clearance calculation follows buildProvinceVisualAnchors: every land cell next
to another owner, water, or the map edge starts at distance zero; distance then
propagates through eight neighbours of the same owner. A Vitest comparison
checks every resulting province value against that TypeScript implementation.
"""
from __future__ import annotations

import argparse
import json
from collections import Counter
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parents[2]
TILES = ROOT / "data/map/han-tiles.json"
WORLD = ROOT / "infra/src/main/resources/map/han-world-v3.json"
DISPOSITIONS = ROOT / 'data/curated/han/province-dead-end-dispositions-v1.json'
NEIGHBOURS = ((-1, -1), (-1, 0), (-1, 1), (0, -1), (0, 1), (1, -1), (1, 0), (1, 1))
LEVEL_NAME = {1: "수", 2: "진", 3: "관", 4: "이"}


def owner_grid(tiles: dict) -> np.ndarray:
    rows, cols = tiles["_meta"]["rows"], tiles["_meta"]["cols"]
    grid = np.repeat(
        np.array([owner for owner, _ in tiles["owner"]], dtype=np.int32),
        np.array([length for _, length in tiles["owner"]], dtype=np.int32),
    )
    if grid.size != rows * cols:
        raise ValueError("owner grid size does not match map dimensions")
    return grid.reshape(rows, cols)


def visual_clearance(owner: np.ndarray, province_count: int) -> tuple[np.ndarray, np.ndarray]:
    """Eight-neighbour clearance capped at three, the largest city requirement.

    Repeated square erosion is equivalent to the runtime boundary BFS for
    distances 0–3. A TypeScript test compares every province's capped maximum.
    """
    rows, cols = owner.shape
    padded = np.pad(owner, 1, constant_values=-2)
    boundary = np.zeros((rows, cols), dtype=bool)
    for dr, dc in NEIGHBOURS:
        boundary |= owner != padded[1 + dr:1 + dr + rows, 1 + dc:1 + dc + cols]
    boundary &= owner >= 0
    distance = np.full(owner.shape, -1, dtype=np.int8)
    distance[boundary] = 0
    active = (owner >= 0) & ~boundary
    for level in range(1, 4):
        distance[active] = level
        if level == 3:
            break
        padded_active = np.pad(active, 1)
        next_active = active.copy()
        for dr, dc in NEIGHBOURS:
            next_active &= padded_active[1 + dr:1 + dr + rows, 1 + dc:1 + dc + cols]
        active = next_active
    maximum = np.full(province_count, -1, dtype=np.int16)
    flat = owner.ravel()
    np.maximum.at(maximum, flat[flat >= 0], distance.ravel()[flat >= 0])
    if (maximum < 0).any():
        raise ValueError("province record without owner cells")
    return distance, maximum


def grid_neighbours(owner: np.ndarray, province_count: int) -> tuple[list[set[int]], set[int], set[int]]:
    """Eight-direction grid contacts; water and map edge remain separate."""
    rows, cols = owner.shape
    adjacent = [set() for _ in range(province_count)]
    water: set[int] = set()
    for dr, dc in ((1, -1), (1, 0), (1, 1), (0, 1)):
        first = owner[dr:rows, max(dc, 0):cols + min(dc, 0)]
        second = owner[:rows - dr, max(-dc, 0):cols - max(dc, 0)]
        different = first != second
        if not different.any():
            continue
        pairs = np.unique(np.stack((first[different], second[different]), axis=1), axis=0)
        for a, b in pairs.tolist():
            if a >= 0 and b >= 0:
                adjacent[a].add(b)
                adjacent[b].add(a)
            elif a >= 0:
                water.add(a)
            elif b >= 0:
                water.add(b)
    edge = set(int(value) for value in np.concatenate(
        (owner[0], owner[-1], owner[:, 0], owner[:, -1])) if value >= 0)
    return adjacent, water, edge


def required_clearance(level: int) -> int:
    # resolveCityFootprints can shrink a span when markers overlap. The TS
    # comparison test verifies that the actual resolved span still agrees.
    return {9: 3, 8: 2, 7: 2, 6: 1}.get(level, 1)


def movement_graph(tiles: dict, world: dict) -> tuple[list[set[int]], list[dict]]:
    """Committed dry-land adjacency plus the 19 activated world water routes.

    The owner-grid's eight-direction contacts are kept separate. The runtime
    route projection consumes the committed dry-border adjacency, which can
    differ from diagonal contacts on the visual grid.
    """
    provinces = tiles["provinceRecords"]
    province_by_id = {province["id"]: i for i, province in enumerate(provinces)}
    graph = [set() for _ in provinces]
    for edge in tiles["adjacency"]["county"]:
        a, b = edge["a"], edge["b"]
        graph[a].add(b)
        graph[b].add(a)
    city_by_id = {city["id"]: city for city in world["cities"]}
    water_edges = []
    for route in world["seaRoutes"]:
        a = province_by_id[city_by_id[route["from"]]["spatialProvinceId"]]
        b = province_by_id[city_by_id[route["to"]]["spatialProvinceId"]]
        graph[a].add(b)
        graph[b].add(a)
        water_edges.append({"from": provinces[a]["id"], "to": provinces[b]["id"], "kind": route["kind"]})
    return graph, water_edges


def audit(tiles: dict, world: dict) -> dict:
    owner = owner_grid(tiles)
    provinces = tiles["provinceRecords"]
    distance, maximum = visual_clearance(owner, len(provinces))
    adjacent, water, edge = grid_neighbours(owner, len(provinces))
    movement, water_edges = movement_graph(tiles, world)
    area = np.bincount(owner[owner >= 0], minlength=len(provinces))
    province_by_id = {province["id"]: i for i, province in enumerate(provinces)}
    cities_by_province = {province_by_id[city["spatialProvinceId"]]: city for city in world["cities"]}
    if len(cities_by_province) != len(world["cities"]):
        raise ValueError("two game cities map to one province")
    if set(range(1, 12)) != {city["level"] for city in world["cities"]}:
        raise ValueError("game city levels 1 through 11 are not all covered")
    narrow = []
    growth_space_missing = []
    surrounded = []
    dead_ends = []
    diagonal_only = []
    for index, contacts in enumerate(adjacent):
        for other in contacts - movement[index]:
            if other > index:
                diagonal_only.append([provinces[index]["id"], provinces[other]["id"]])
    strategic = []
    for index, province in enumerate(provinces):
        city = cities_by_province.get(index)
        base = {
            "provinceIndex": index,
            "provinceId": province["id"],
            "commanderyId": province["parentRegionId"],
            "provinceName": province["displayName"],
            "cityId": city["id"] if city else None,
            "cityName": city["name"] if city else None,
            "level": city["level"] if city else None,
            "levelName": LEVEL_NAME.get(city["level"], "성") if city else None,
            "span": {9: 7, 8: 5, 7: 5, 6: 3}.get(city["level"], 1) if city else None,
            "clearance": int(maximum[index]),
            "area": int(area[index]),
            "geometryBasis": province["geometryBasis"],
            "neighbours": [provinces[other]["id"] for other in sorted(adjacent[index])],
            "movementNeighbours": [provinces[other]["id"] for other in sorted(movement[index])],
        }
        if city and maximum[index] < required_clearance(city["level"]):
            narrow.append({**base, "required": required_clearance(city["level"])})
        if maximum[index] < 3:
            growth_space_missing.append(base)
        if len(adjacent[index]) == 1 and index not in water and index not in edge:
            other = next(iter(adjacent[index]))
            surrounded.append({**base, "surroundingProvinceId": provinces[other]["id"],
                               "surroundingCityId": cities_by_province.get(other, {}).get("id")})
        if len(movement[index]) <= 1:
            dead_ends.append({**base, "touchesWater": index in water, "touchesMapEdge": index in edge})
        if city and city["level"] <= 4:
            neighbours = sorted(movement[index])
            through_pairs = [[provinces[a]["id"], provinces[b]["id"]]
                             for ai, a in enumerate(neighbours) for b in neighbours[ai + 1:]
                             if b not in movement[a]]
            strategic.append({**base, "throughShortestNeighbourPairs": through_pairs,
                              "hasThroughPair": bool(through_pairs)})
    by_level = Counter(item["levelName"] for item in narrow)
    enclosed_by_level = Counter(item["levelName"] or "빈 구역" for item in surrounded)
    return {
        "provinceCount": len(provinces),
        "cityCount": len(world["cities"]),
        "clearanceByProvince": maximum.tolist(),
        "narrowByLevel": dict(sorted(by_level.items())),
        "narrow": narrow,
        "growthSpaceMissing": growth_space_missing,
        "surroundedByLevel": dict(sorted(enclosed_by_level.items())),
        "surrounded": surrounded,
        "movementDeadEnds": dead_ends,
        "strategicDeadEnds": [x for x in dead_ends if x['level'] is not None and x['level'] <= 4],
        "emptyDeadEnds": [x for x in dead_ends if x['level'] is None],
        "movementDeadEndsByLevel": dict(sorted(Counter(x["levelName"] or "빈 구역" for x in dead_ends).items())),
        "diagonalOnlyContacts": sorted(diagonal_only),
        "activatedWaterRoutes": water_edges,
        "strategicSites": strategic,
        "strategicSitesWithoutThroughPairByLevel": dict(sorted(Counter(
            x["levelName"] for x in strategic if not x["hasThroughPair"]).items())),
    }


def disposition_problems(result: dict, ledger: dict) -> list[str]:
    """Every ordinary-city dead end needs its existing, measured value recorded."""
    measured = {row['provinceId']: row for row in result['movementDeadEnds'] if row['level'] is not None}
    rows = ledger['dispositions']
    recorded = {row['provinceId']: row for row in rows}
    problems = []
    if len(recorded) != len(rows):
        problems.append('duplicate dead-end disposition')
    if set(recorded) != set(measured):
        problems.append(f'dead-end disposition ids differ: missing={sorted(set(measured)-set(recorded))} '
                        f'stale={sorted(set(recorded)-set(measured))}')
    water_pairs = {}
    for route in result['activatedWaterRoutes']:
        water_pairs.setdefault(route['from'], set()).add(route['to'])
        water_pairs.setdefault(route['to'], set()).add(route['from'])
    for pid in sorted(set(recorded) & set(measured)):
        row, actual = recorded[pid], measured[pid]
        if (row['cityId'], row['level'], row['coastal']) != (
            actual['cityId'], actual['level'], actual['touchesWater']
        ) or row['level'] < 5 or row['disposition'] != 'EXISTING_CITY_VALUE':
            problems.append(f'{pid}: existing city value changed')
        if row['existingWaterRouteTo'] != sorted(water_pairs.get(pid, set())):
            problems.append(f'{pid}: existing water routes changed')
    return problems


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--tiles", type=Path, default=TILES)
    parser.add_argument("--world", type=Path, default=WORLD)
    parser.add_argument("--json", action="store_true")
    parser.add_argument("--check", action="store_true")
    parser.add_argument('--dispositions', type=Path, default=DISPOSITIONS)
    args = parser.parse_args()
    result = audit(json.loads(args.tiles.read_text()), json.loads(args.world.read_text()))
    if args.json:
        print(json.dumps(result, ensure_ascii=False, separators=(",", ":")))
    else:
        print(f"cities={result['cityCount']} provinces={result['provinceCount']}")
        print(f"narrow={len(result['narrow'])} {result['narrowByLevel']}")
        print(f"growthSpaceMissing={len(result['growthSpaceMissing'])}")
        print(f"surrounded={len(result['surrounded'])} {result['surroundedByLevel']}")
        print(f"movementDeadEnds={len(result['movementDeadEnds'])} {result['movementDeadEndsByLevel']}")
        print(f"strategicDeadEnds={len(result['strategicDeadEnds'])} emptyDeadEnds={len(result['emptyDeadEnds'])}")
        print(f"diagonalOnlyContacts={len(result['diagonalOnlyContacts'])}")
        print(f"sitesWithoutThroughPair={result['strategicSitesWithoutThroughPairByLevel']}")
    if not args.check:
        return 0
    problems = disposition_problems(result, json.loads(args.dispositions.read_text()))
    for problem in problems:
        print(problem)
    return int(bool(result["narrow"] or result["growthSpaceMissing"]
                    or result["surrounded"] or result['strategicDeadEnds']
                    or result['emptyDeadEnds'] or problems))


if __name__ == "__main__":
    raise SystemExit(main())
