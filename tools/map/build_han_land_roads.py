#!/usr/bin/env python3
"""Build a deterministic, terrain-aware county road graph for the map4 release.

The candidate graph is every physically dry four-neighbour county boundary.
Its crossing cells are geometry, while the BUILT set starts with a connected
minimum network, short loops, and the links needed to preserve initial scenario
supply. These are inferred game routes, not
claims that a historical road followed any particular cell.
"""
from __future__ import annotations

import argparse
import heapq
import hashlib
import json
from collections import defaultdict
from pathlib import Path

import numpy as np

from audit_province_clearance import owner_grid

ROOT = Path(__file__).resolve().parents[2]
TILES = ROOT / "data/map/han-tiles.json"
OUTPUT = ROOT / "data/map/han-land-roads-v1.json"
OWNERSHIP = ROOT / "data/map/han-scenario-province-ownership-v1.json"
WORLD = ROOT / "infra/src/main/resources/map/han-world-v3.json"
SCENARIOS = ROOT / "infra/src/main/resources/scenario"
CAMPAIGN_SCENARIO = ROOT / "tools/e2e/fixtures/yuzhou/scenario_990002.json"
DRY_COST = {"PLAIN": 1, "BASIN": 2, "HILL": 3, "PLATEAU": 4, "MOUNTAIN": 6, "DESERT": 7}
HISTORICAL_CORRIDORS = (
    {"id": "jingxing", "name": "井陘道", "waypointCityIds": ("gc-g0079-001", "87093", "88410"),
     "sourceRefs": ("https://ctext.org/shiji/huai-yin-hou-lie-zhuan/zhs",
                    "https://ctext.org/text.pl?if=en&node=65227&show=parallel")},
    {"id": "taihang", "name": "太行陘·羊腸坂", "waypointCityIds": ("210688", "95318"),
     "sourceRefs": ("https://ctext.org/han-shu/di-li-zhi-shang/zhs",
                    "https://ctext.org/wiki.pl?chapter=811926&if=en")},
    {"id": "baoxie", "name": "褒斜道", "waypointCityIds": ("200048", "ss-xieguguan", "70930"),
     "sourceRefs": ("https://ctext.org/text.pl?if=en&node=64379&show=parallel",
                    "https://ctext.org/text.pl?if=en&node=9241&show=parallel",
                    "https://ctext.org/wiki.pl?chapter=855186&if=en&remap=gb")},
    {"id": "ziwu", "name": "子午道", "waypointCityIds": ("70633", "70983"),
     "sourceRefs": ("https://ctext.org/text.pl?if=gb&node=68400&remap=gb&show=parallel",
                    "https://ctext.org/text.pl?if=en&node=602537&remap=gb")},
    {"id": "chencang", "name": "陳倉道", "waypointCityIds": ("70920", "200048"),
     "sourceRefs": ("https://www.nature.com/articles/s40494-024-01155-y",
                    "https://qinshuroads.org/docs/PDF/HZBWG_Intro_Preface_Baoxie_Road.pdf")},
    {"id": "zhidao", "name": "秦漢直道", "waypointCityIds": ("70764", "95698"),
     "sourceRefs": ("https://ctext.org/text.pl?if=en&node=8629&remap=gb&show=parallel",
                    "https://discovery.ucl.ac.uk/id/eprint/10186270/")},
    {"id": "wuguan", "name": "武關道", "waypointCityIds": ("70623", "200094", "ss-wuguan", "82612"),
     "sourceRefs": ("https://ctext.org/text.pl?if=en&node=8320&show=parallel",
                    "https://ctext.org/text.pl?if=en&node=569433&show=parallel")},
    {"id": "hexi", "name": "河西驛路", "waypointCityIds": ("211764", "211824", "211885", "70594"),
     "sourceRefs": ("https://ctext.org/text.pl?if=gb&node=68053&show=parallel",
                    "https://ctext.org/shiji/da-wan-lie-zhuan/zh")},
    {"id": "jinniu", "name": "金牛道·劍閣通로", "waypointCityIds": ("70983", "ss-jiange", "96107", "44394"),
     "sourceRefs": ("https://www.nature.com/articles/s40494-024-01155-y",
                    "https://ctext.org/sanguozhi/44/zh")},
    {"id": "southwest", "name": "西南夷道", "waypointCityIds": ("200326", "99004"),
     "sourceRefs": ("https://ctext.org/shiji/xi-nan-yi-lie-zhuan/zhs",
                    "https://ctext.org/tongdian/176/ens")},
    {"id": "hangu", "name": "函谷關 동서 통로", "waypointCityIds": ("70623", "ss-hanguguan", "82828"),
     "sourceRefs": ("https://ctext.org/shiji/qin-shi-huang-ben-ji/zhs",
                    "https://zgld.cbpt.cnki.net/portal/journal/portal/client/paper/06c460ab3f9e4c81c833313e045e1082")},
)


def build(tiles: dict, ownership: dict) -> dict:
    meta = tiles["_meta"]
    if meta.get("resolutionScale") != 4:
        raise ValueError("Road release requires the map4 terrain")
    rows, cols = meta["rows"], meta["cols"]
    owner = owner_grid(tiles)
    legend = meta["terrainLegend"]
    codes = {code: DRY_COST.get(name, 0) for code, name in legend.items()}
    terrain = np.frombuffer("".join(tiles["terrain"]).encode("ascii"), dtype=np.uint8).reshape(rows, cols)
    friction = np.zeros(256, dtype=np.uint8)
    for code, cost in codes.items():
        friction[ord(code)] = cost
    cost = friction[terrain]
    # A road may reach another dry bank through a river cell owned by the same
    # county. Such cells represent an internal ford/bridge with a high cost;
    # they never become a road gate between counties or a fort building site.
    path_cost = cost.copy()
    path_cost[terrain == ord('3')] = 10
    provinces = tiles["provinceRecords"]
    ids = [row["id"] for row in provinces]
    cities = tiles["cities"]
    seat = {}
    for index, province in enumerate(provinces):
        city_index = province.get("cityIndex")
        if city_index is not None:
            city = cities[city_index]
            seat[index] = (city["row"], city["col"])

    best: dict[tuple[int, int], tuple[tuple[int, int, int, int], tuple[int, int], tuple[int, int]]] = {}
    for dr, dc in ((0, 1), (1, 0)):
        a = owner[:rows - dr, :cols - dc]
        b = owner[dr:, dc:]
        ca = cost[:rows - dr, :cols - dc]
        cb = cost[dr:, dc:]
        mask = (a >= 0) & (b >= 0) & (a != b) & (ca > 0) & (cb > 0)
        for row, col in zip(*np.nonzero(mask), strict=True):
            first, second = int(a[row, col]), int(b[row, col])
            key = tuple(sorted((first, second)))
            row, col = int(row), int(col)
            left, right = (row, col), (row + dr, col + dc)
            # Terrain cost dominates. Distances to the physical seats break
            # ties, then coordinates make the chosen gate deterministic.
            point_a = seat.get(first, left)
            point_b = seat.get(second, right)
            distance = (abs(point_a[0] - left[0]) + abs(point_a[1] - left[1]) +
                        abs(point_b[0] - right[0]) + abs(point_b[1] - right[1]))
            score = (int(ca[row, col]) + int(cb[row, col]), distance, row, col)
            previous = best.get(key)
            if previous is None or score < previous[0]:
                best[key] = score, left if first == key[0] else right, right if first == key[0] else left

    # A text can establish a named corridor without locating every surviving
    # paving stone. Find its preferred *game* alignment over physical dry gates.
    graph: dict[int, list[tuple[int, tuple[int, int]]]] = defaultdict(list)
    for pair in best:
        graph[pair[0]].append((pair[1], pair))
        graph[pair[1]].append((pair[0], pair))
    def edge_id(pair: tuple[int, int]) -> str:
        key = "".join(f"{len(value)}:{value}" for value in sorted((ids[pair[0]], ids[pair[1]])))
        return f"land-boundary:{key}"
    city_by_id = {city["id"]: city for city in cities}
    historical: dict[tuple[int, int], set[str]] = defaultdict(set)
    corridors = []
    for spec in HISTORICAL_CORRIDORS:
        waypoints = [city_by_id[key] for key in spec["waypointCityIds"]]
        used: set[tuple[int, int]] = set()
        ordered_route: list[tuple[int, int]] = []
        for start, end in zip(waypoints, waypoints[1:]):
            source = int(owner[start["row"], start["col"]])
            target = int(owner[end["row"], end["col"]])
            if source < 0 or target < 0:
                raise ValueError(f"historical corridor seat outside county: {spec['id']}")
            heap = [(0, source)]
            distance = {source: 0}
            previous: dict[int, tuple[int, tuple[int, int]]] = {}
            while heap:
                current_cost, node = heapq.heappop(heap)
                if current_cost != distance[node]:
                    continue
                if node == target:
                    break
                for neighbour, pair in sorted(graph[node]):
                    score, first, second = best[pair]
                    gate_row = (first[0] + second[0]) / 2
                    gate_col = (first[1] + second[1]) / 2
                    dy = end["row"] - start["row"]
                    dx = end["col"] - start["col"]
                    deviation = abs(dx * (start["row"] - gate_row) -
                                    dy * (start["col"] - gate_col)) / max(1, abs(dx) + abs(dy))
                    step = score[0] * 10 + 5 + min(60, int(deviation / 2))
                    proposal = current_cost + step
                    if proposal < distance.get(neighbour, 1 << 60):
                        distance[neighbour] = proposal
                        previous[neighbour] = (node, pair)
                        heapq.heappush(heap, (proposal, neighbour))
            if target not in distance:
                raise ValueError(f"historical corridor has no dry alignment: {spec['id']}")
            node = target
            segment: list[tuple[int, int]] = []
            while node != source:
                node, pair = previous[node]
                used.add(pair)
                historical[pair].add(spec["id"])
                segment.append(pair)
            ordered_route.extend(reversed(segment))
        corridors.append({**spec, "waypointCityIds": list(spec["waypointCityIds"]),
                          "sourceRefs": list(spec["sourceRefs"]),
                          "alignment": "TERRAIN_INFERRED_BETWEEN_DOCUMENTED_WAYPOINTS",
                          "selectedEdges": len(used),
                          "edgeIds": [edge_id(pair) for pair in ordered_route]})

    ordered = sorted(best.items(), key=lambda item: (0 if item[0] in historical else 1,
                     item[1][0], ids[item[0][0]], ids[item[0][1]]))
    # Turn each crossing into a continuous terrain path to the local seat.
    # One search per province shares the interior trunk among neighbouring
    # gates; it stops as soon as every gate of that province has been reached.
    targets_by_province: dict[int, set[tuple[int, int]]] = defaultdict(set)
    for (a, b), (_, first, second) in best.items():
        targets_by_province[a].add(first)
        targets_by_province[b].add(second)
    road_paths: dict[tuple[int, tuple[int, int]], list[list[int]]] = {}
    origins: dict[int, list[int]] = {}
    for province, targets in sorted(targets_by_province.items()):
        start = seat.get(province)
        if start is None or owner[start] != province or path_cost[start] == 0:
            start = min(targets)
        origins[province] = [start[0], start[1]]
        source = start[0] * cols + start[1]
        remaining = {r * cols + c for r, c in targets}
        distance = {source: 0}
        previous: dict[int, int] = {}
        heap = [(0, source)]
        while heap and remaining:
            effort, flat = heapq.heappop(heap)
            if effort != distance[flat]:
                continue
            remaining.discard(flat)
            row, col = divmod(flat, cols)
            for dr, dc in ((-1, 0), (-1, -1), (-1, 1), (0, -1), (0, 1),
                           (1, 0), (1, -1), (1, 1)):
                nr, nc = row + dr, col + dc
                if nr < 0 or nr >= rows or nc < 0 or nc >= cols or owner[nr, nc] != province or path_cost[nr, nc] == 0:
                    continue
                if dr and dc and (owner[row + dr, col] != province or owner[row, col + dc] != province or
                                  path_cost[row + dr, col] == 0 or path_cost[row, col + dc] == 0):
                    continue  # do not cut across a foreign or flooded corner
                next_flat = nr * cols + nc
                next_effort = effort + int(path_cost[nr, nc]) * (141 if dr and dc else 100)
                if next_effort < distance.get(next_flat, 1 << 60):
                    distance[next_flat] = next_effort
                    previous[next_flat] = flat
                    heapq.heappush(heap, (next_effort, next_flat))
        for target in sorted(targets):
            flat = target[0] * cols + target[1]
            if flat not in distance:
                # A province may have a detached island; do not draw a false
                # straight line over another province or water.
                road_paths[(province, target)] = [[target[0], target[1]]]
                continue
            path = []
            while True:
                row, col = divmod(flat, cols)
                path.append([row, col])
                if flat == source:
                    break
                flat = previous[flat]
            road_paths[(province, target)] = list(reversed(path))
    # A dry contact on a detached island is a candidate, but it is not a road
    # connected to either county seat. Build the initial network only from
    # crossings whose two interior paths reach their anchors.
    accessible = {pair for pair, (_, first, second) in best.items()
                  if road_paths[(pair[0], first)][0] == origins[pair[0]] and
                  road_paths[(pair[1], second)][0] == origins[pair[1]]}
    parent = list(range(len(ids)))

    def root(i: int) -> int:
        while parent[i] != i:
            parent[i] = parent[parent[i]]
            i = parent[i]
        return i

    built = set()
    tree_edges = set()
    degree = defaultdict(int)
    for pair, _ in ordered:
        if pair not in accessible:
            continue
        a, b = pair
        ra, rb = root(a), root(b)
        if ra == rb:
            continue
        parent[rb] = ra
        built.add(pair)
        tree_edges.add(pair)
        degree[a] += 1
        degree[b] += 1
    for pair in historical:
        if pair in accessible and pair not in built:
            built.add(pair)
            degree[pair[0]] += 1
            degree[pair[1]] += 1
    site_nodes = {i for i, province in enumerate(provinces)
                  if province.get("assignmentBasis") == "STRATEGIC_SITE_LOCAL_CARVE" or
                  province.get("geometryBasis") == "STRATEGIC_SITE_LOCAL_CARVE"}
    extra_budget = max(1, len(built) // 5)
    for pair, (score, _, _) in ordered:
        if extra_budget == 0:
            break
        if pair not in accessible or pair in built or score[0] > 8:
            continue
        if degree[pair[0]] > 2 and degree[pair[1]] > 2 and not set(pair) & site_nodes:
            continue
        built.add(pair)
        degree[pair[0]] += 1
        degree[pair[1]] += 1
        extra_budget -= 1
    # A minimum spanning tree creates many arbitrary cul-de-sacs even where
    # the terrain offers a cheap second crossing. Close those local loops so
    # the road system reads and plays as a connected network. Mountain and
    # desert detours stay unbuilt unless justified by another rule above.
    for pair, (score, _, _) in ordered:
        if pair not in accessible or pair in built or score[0] > 8:
            continue
        if degree[pair[0]] != 1 and degree[pair[1]] != 1:
            continue
        built.add(pair)
        degree[pair[0]] += 1
        degree[pair[1]] += 1
    # The 郡 is also a supply unit. Keep its inexpensive dry county contacts
    # connected in the initial road graph, even when the global spanning tree
    # joined those counties by a longer route outside the 郡.
    local_parent = list(range(len(ids)))
    def local_root(index: int) -> int:
        while local_parent[index] != index:
            local_parent[index] = local_parent[local_parent[index]]
            index = local_parent[index]
        return index
    def same_commandery(pair: tuple[int, int]) -> bool:
        return provinces[pair[0]]["parentRegionId"] == provinces[pair[1]]["parentRegionId"]
    for a, b in built:
        if same_commandery((a, b)):
            local_parent[local_root(a)] = local_root(b)
    for pair, (score, _, _) in sorted(best.items(), key=lambda item:
                                      (item[1][0], ids[item[0][0]], ids[item[0][1]])):
        if pair not in accessible or pair in built or score[0] > 8 or not same_commandery(pair):
            continue
        a, b = pair
        ra, rb = local_root(a), local_root(b)
        if ra == rb:
            continue
        local_parent[ra] = rb
        built.add(pair)
        degree[a] += 1
        degree[b] += 1
    # A strategic site at an accessible junction needs both branches open at
    # the start. High-cost passes are intentional here: their terrain, rather
    # than an arbitrary loop budget, determines the route cost.
    for node in sorted(site_nodes):
        if degree[node] >= 2:
            continue
        options = sorted((pair for pair in best if node in pair and pair in accessible and pair not in built),
                         key=lambda pair: (best[pair][0], ids[pair[0]], ids[pair[1]]))
        for pair in options:
            if degree[node] >= 2:
                break
            built.add(pair)
            degree[pair[0]] += 1
            degree[pair[1]] += 1
    # An initially owned territory must retain every supply connection that
    # its dry boundary graph already has. The global MST can otherwise route
    # through a different nation's land and strand many counties at start.
    # Add only the cheapest accessible links needed by the reviewed scenario
    # ownerships; roads remain meaningful for later construction and blockade.
    id_index = {province_id: index for index, province_id in enumerate(ids)}
    world_cities = {int(city["id"]): city for city in json.loads(WORLD.read_text())["cities"]}
    supply_links = 0
    reviewed_scenarios = [
        (row["scenarioCode"], row["assignments"], SCENARIOS / f"scenario_{row['scenarioCode']}.json")
        for row in sorted(ownership["scenarios"], key=lambda row: row["scenarioCode"])
    ]
    # The campaign fixture exercises live military blocks against this same
    # release. Its complete city occupancy supplies the jurisdiction owners.
    reviewed_scenarios.append((990002, None, CAMPAIGN_SCENARIO))
    for scenario_code, assignments, scenario_path in reviewed_scenarios:
        if assignments is not None:
            if len(assignments) != len(ids) or {row["provinceId"] for row in assignments} != set(ids):
                raise ValueError(f"incomplete scenario ownership: {scenario_code}")
            owner_by_index = {id_index[row["provinceId"]]: row["ownerNationId"] for row in assignments}
        else:
            owner_by_index = {index: 0 for index in range(len(ids))}
        scenario_doc = json.loads(scenario_path.read_text())
        occupied_cities = {}
        for nation, nation_row in enumerate(scenario_doc["nation"], start=1):
            for city_id in nation_row[8]:
                occupied_cities[int(city_id)] = nation
        live_jurisdictions = {}
        for city_id, city in world_cities.items():
            seat_id = city["spatialProvinceId"]
            jurisdiction = provinces[id_index[seat_id]]["jurisdictionId"]
            nation = occupied_cities.get(city_id, 0)
            previous = live_jurisdictions.setdefault(jurisdiction, nation)
            if previous != nation:
                raise ValueError(f"conflicting live jurisdiction owner: {jurisdiction}")
        # Runtime city occupancy, including neutral cities, overrides the static
        # assignment for every province in that city's jurisdiction, not just its seat.
        for index, province in enumerate(provinces):
            override = live_jurisdictions.get(province["jurisdictionId"])
            if override is not None:
                owner_by_index[index] = override
        by_nation: dict[int, set[int]] = defaultdict(set)
        for index, nation in owner_by_index.items():
            if nation is not None and nation > 0:
                by_nation[nation].add(index)
        for nation in sorted(by_nation):
            owned = by_nation[nation]
            supply_parent = {node: node for node in owned}
            def supply_root(node: int) -> int:
                while supply_parent[node] != node:
                    supply_parent[node] = supply_parent[supply_parent[node]]
                    node = supply_parent[node]
                return node
            for a, b in built:
                if a in owned and b in owned:
                    supply_parent[supply_root(a)] = supply_root(b)
            for (a, b), _ in ordered:
                if (a, b) not in accessible or a not in owned or b not in owned:
                    continue
                ra, rb = supply_root(a), supply_root(b)
                if ra == rb:
                    continue
                supply_parent[ra] = rb
                if (a, b) not in built:
                    built.add((a, b))
                    degree[a] += 1
                    degree[b] += 1
                    supply_links += 1
    # The long-distance overview shows the part of the initial tree that
    # actually joins substantial groups of counties. Local terminal streets
    # remain available at closer zoom, without cluttering the world picture.
    tree = defaultdict(list)
    for a, b in tree_edges:
        tree[a].append(b)
        tree[b].append(a)
    trunk = set()
    seen_nodes = set()
    for start in range(len(ids)):
        if start in seen_nodes:
            continue
        parents = {start: -1}
        order = [start]
        seen_nodes.add(start)
        for node in order:
            for neighbor in tree[node]:
                if neighbor == parents[node]:
                    continue
                parents[neighbor] = node
                seen_nodes.add(neighbor)
                order.append(neighbor)
        subtree = {node: 1 for node in order}
        for node in reversed(order[1:]):
            upstream = parents[node]
            if min(subtree[node], len(order) - subtree[node]) >= 8:
                trunk.add(tuple(sorted((node, upstream))))
            subtree[upstream] += subtree[node]
    trunk_nodes = {node for pair in trunk for node in pair}
    # Show the already-built local alternatives between trunk nodes as loops.
    # This makes the overview legible as a network rather than a bare tree.
    trunk.update(pair for pair in built if pair[0] in trunk_nodes and pair[1] in trunk_nodes)
    edges = []
    for pair in sorted(best, key=lambda p: (ids[p[0]], ids[p[1]])):
        score, first, second = best[pair]
        a, b = pair
        # Runtime dry-boundary edges are oriented by stable province ID, not
        # owner-grid ordinal. Keep crossing cells and trails in that order.
        if ids[a] > ids[b]:
            a, b = b, a
            first, second = second, first
        fort_cells = []
        for province, (gr, gc) in ((a, first), (b, second)):
            for fr in range(max(0, gr - 1), min(rows, gr + 2)):
                for fc in range(max(0, gc - 1), min(cols, gc + 2)):
                    if owner[fr, fc] == province and cost[fr, fc] > 0:
                        fort_cells.append({"provinceId": ids[province], "row": fr, "col": fc})
        edges.append({"id": edge_id(pair),
                      "fromProvinceId": ids[a], "toProvinceId": ids[b],
                      "fromCell": {"row": first[0], "col": first[1]},
                      "toCell": {"row": second[0], "col": second[1]},
                      "terrainCost": score[0], "status": "BUILT" if pair in built else
                      "UNBUILT" if pair in accessible else "INACCESSIBLE",
                      "overviewTrunk": pair in trunk or pair in historical,
                      "evidence": "DOCUMENTED_CORRIDOR_INFERRED_ALIGNMENT" if pair in historical else "TERRAIN_INFERRED",
                      "historicalRouteIds": sorted(historical.get(pair, ())),
                      "routeWeightPermille": 700 if pair in historical else 1000,
                      "fromTrail": road_paths[(a, first)], "toTrail": road_paths[(b, second)],
                      "fortCells": fort_cells})
    return {"schemaVersion": 1, "artifactId": "han-land-roads-v1", "resolutionScale": 4,
            "rows": rows, "cols": cols, "provinceCount": len(ids),
            "policy": "dry-four-neighbour-terrain-mst-with-scenario-supply-links-v3",
            "historicalStatus": "INFERRED_ROUTE_NOT_ATTESTED_ROAD",
            "historicalCorridors": corridors,
            "counts": {"candidateEdges": len(edges), "builtEdges": len(built),
                       "unbuiltEdges": len(accessible) - len(built),
                       "inaccessibleEdges": len(edges) - len(accessible),
                       "overviewTrunkEdges": sum(edge["overviewTrunk"] for edge in edges),
                       "dryComponents": len({root(i) for i in range(len(ids))}),
                       "scenarioSupplyLinks": supply_links},
            "edges": edges}


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    tile_bytes = TILES.read_bytes()
    ownership = json.loads(OWNERSHIP.read_text(encoding="utf-8"))
    if ownership["sources"]["mapSha256"] != hashlib.sha256(tile_bytes).hexdigest():
        raise ValueError("scenario ownership must be regenerated before land roads")
    result = build(json.loads(tile_bytes), ownership)
    payload = json.dumps(result, ensure_ascii=False, separators=(",", ":")) + "\n"
    if args.check:
        if OUTPUT.read_text(encoding="utf-8") != payload:
            raise SystemExit("Han land road artifact drifted")
    else:
        OUTPUT.write_text(payload, encoding="utf-8")
    print(result["counts"])


if __name__ == "__main__":
    main()
