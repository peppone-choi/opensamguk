#!/usr/bin/env python3
"""Materialize the reviewed Han waterway network (#826, slice 1) — evidence first, non-activating.

Rivers are waterways and the waterway NODES are the ports.  This builder turns the adjudication
ledger ``data/curated/han/waterway-network-adjudications-v1.json`` into
``data/map/han-waterway-network-v1.json``.  It never infers a reach, a crossing or a port: every
row must cite a source, and geometry is only *checked* against ``han-tiles.json``, never carved.

Why a separate artifact: ``han-water-topology-v1.json`` and its ledger are runtime loader inputs
whose bytes feed ``StrategicTopology.contentHash`` and the frozen 1133 bundle.  Touching them makes
every world pinned to 1133 fail to load.  This overlay is read by no runtime, so nothing activates;
folding it into the runtime artifact is a later, separately approved slice.

Structural guarantees (each has a red probe in tests/test_build_han_waterway_network.py):
  * a port's cell is within one cell of a water cell of ITS reach — an inland port cannot be built;
  * a crossing's two bank cells are dry, owned by the two named provinces, within reach of the node,
    and lie in different land components once the reach is removed — i.e. on opposite banks;
  * every reach, node, flow link and blocked row cites at least one known source, and every source
    taken from the stronghold ledger must still match that ledger's primaryEvidence verbatim;
  * every FERRY stronghold is accounted for exactly once (node or blocked) and blocked rows carry
    the distance the builder itself measures;
  * ``--check`` fails on any byte drift of the committed artifact.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
from collections import deque
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
TILES = ROOT / "data" / "map" / "han-tiles.json"
STRONGHOLDS = ROOT / "data" / "curated" / "han" / "strategic-strongholds-v1.json"
LEDGER = ROOT / "data" / "curated" / "han" / "waterway-network-adjudications-v1.json"
OUTPUT = ROOT / "data" / "map" / "han-waterway-network-v1.json"

LEDGER_ID = "han-waterway-network-adjudications-v1"
ARTIFACT_ID = "han-waterway-network-v1"
RIVER, LAKE = "3", "4"
WATER = {RIVER, LAKE}
NEIGH8 = [(a, b) for a in (-1, 0, 1) for b in (-1, 0, 1) if (a, b) != (0, 0)]
NEIGH4 = [(-1, 0), (1, 0), (0, -1), (0, 1)]
BANK_WINDOW = 6          # half-size of the window in which banks are separated
BANK_MAX_FROM_NODE = 4   # moved ferry markers may be one cell from their projected water node
ROLES = {"CROSSING", "PORT"}
BLOCK_CODES = {
    "RIVER_COURSE_NOT_IN_GRID",        # nearest water >= 2 cells away: needs river-course adjudication
    "PORT_CELL_NOT_ADJACENT_TO_WATER",  # evidence exists, but no water cell within 1 of the cell
    "PORT_CELL_NOT_ADJACENT_TO_REACH",  # evidence exists, water nearby is not the attested reach
    "FERRY_ANCHOR_NOT_CONNECTED_TO_ATTESTED_REACH",  # a local river cell exists but not the reviewed reach
    "SITE_PROVINCE_DOES_NOT_TOUCH_REACH",  # node is on water, its carved province is not
    "ROLE_NOT_ESTABLISHED_BY_EVIDENCE",  # on water, but no quote shows crossing or embarkation
    "BOTH_BANKS_IN_ONE_PROVINCE",       # crossing attested, but one province owns both banks
    "OUT_OF_SLICE_WATER_SYSTEM",        # on water that is not 長江/黃河 (later slice)
}


def canonical_json_bytes(value: object) -> bytes:
    return (json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"),
                       allow_nan=False) + "\n").encode("utf-8")


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def fail(message: str):
    raise ValueError(message)


def need(condition: bool, message: str) -> None:
    if not condition:
        fail(message)


def decode_owner(tiles: dict) -> list[list[int]]:
    rows, cols = tiles["_meta"]["rows"], tiles["_meta"]["cols"]
    flat: list[int] = []
    for value, count in tiles["owner"]:
        flat.extend([value] * count)
    need(len(flat) == rows * cols, "han-tiles owner RLE length mismatch")
    return [flat[i:i + cols] for i in range(0, len(flat), cols)]


def flood(terrain: list[str], seed: tuple[int, int], allowed: set[str],
          box: tuple[int, int, int, int] | None = None) -> set[tuple[int, int]]:
    rows, cols = len(terrain), len(terrain[0])
    r0, r1, c0, c1 = box if box else (0, rows - 1, 0, cols - 1)
    need(r0 <= seed[0] <= r1 and c0 <= seed[1] <= c1, f"seed {seed} outside its box")
    need(terrain[seed[0]][seed[1]] in allowed, f"seed {seed} is not a water cell of the expected kind")
    seen = {seed}
    queue = deque([seed])
    while queue:
        r, c = queue.popleft()
        for dr, dc in NEIGH8:
            p = (r + dr, c + dc)
            if (r0 <= p[0] <= r1 and c0 <= p[1] <= c1 and 0 <= p[0] < rows and 0 <= p[1] < cols
                    and p not in seen and terrain[p[0]][p[1]] in allowed):
                seen.add(p)
                queue.append(p)
    return seen


def chebyshev_to(cell: tuple[int, int], cells: set[tuple[int, int]], limit: int = 64) -> int | None:
    for d in range(limit + 1):
        for r in range(cell[0] - d, cell[0] + d + 1):
            for c in range(cell[1] - d, cell[1] + d + 1):
                if max(abs(r - cell[0]), abs(c - cell[1])) == d and (r, c) in cells:
                    return d
    return None


def encode_runs(cells: set[tuple[int, int]]) -> list[list[int]]:
    runs: list[list[int]] = []
    for r, c in sorted(cells):
        if runs and runs[-1][0] == r and runs[-1][2] == c - 1:
            runs[-1][2] = c
        else:
            runs.append([r, c, c])
    return runs


def land_component(terrain, start, center, blocked):
    """4-connected dry-or-unclaimed cells around ``center`` with ``blocked`` (the reach) removed.

    The reach is 8-connected, so a diagonal river step still separates 4-connected land.
    """
    rows, cols = len(terrain), len(terrain[0])
    seen = {start}
    queue = deque([start])
    while queue:
        r, c = queue.popleft()
        for dr, dc in NEIGH4:
            p = (r + dr, c + dc)
            if (abs(p[0] - center[0]) <= BANK_WINDOW and abs(p[1] - center[1]) <= BANK_WINDOW
                    and 0 <= p[0] < rows and 0 <= p[1] < cols and p not in seen
                    and p not in blocked and terrain[p[0]][p[1]] not in WATER):
                seen.add(p)
                queue.append(p)
    return seen


def _refs(row: dict, sources: dict, where: str) -> list[str]:
    refs = row.get("sourceRefs")
    need(isinstance(refs, list) and refs and len(set(refs)) == len(refs), f"{where} has no source")
    unknown = sorted(set(refs) - set(sources))
    need(not unknown, f"{where} cites unknown sources {unknown}")
    return sorted(refs)


def build(tiles: dict, tiles_bytes: bytes, strongholds: dict, strongholds_bytes: bytes,
          ledger: dict) -> dict:
    need(ledger.get("schemaVersion") == 1 and ledger.get("ledgerId") == LEDGER_ID, "ledger identity")
    need(ledger.get("activation") == "NON_ACTIVATING",
         "slice 1 must stay NON_ACTIVATING: no runtime may consume these edges yet")
    base = ledger["base"]
    need(base["hanTiles"]["sha256"] == sha256(tiles_bytes), "han-tiles base pin drift")
    need(base["strongholds"]["sha256"] == sha256(strongholds_bytes), "stronghold ledger base pin drift")

    # This evidence ledger is a non-activating historical witness in the
    # original 768×669 geographic frame. Display-grid refinement preserves
    # those physical cells, so inspect that frame while pinning the full map.
    if tiles['_meta'].get('resolutionScale', 1) > 1:
        from tools.map.korea_map_extension import base_frame
        tiles = base_frame(tiles)

    terrain = tiles["terrain"]
    owner = decode_owner(tiles)
    provinces = tiles["provinceRecords"]
    province_index = {p["id"]: i for i, p in enumerate(provinces)}
    all_water = {(r, c) for r, row in enumerate(terrain) for c, ch in enumerate(row) if ch in WATER}
    ferries = {s["id"]: s for s in strongholds["strongholds"] if s.get("role") == "FERRY"}
    cities = {c["id"]: c for c in tiles["cities"]}

    # --- sources -----------------------------------------------------------------------------
    sources: dict[str, dict] = {}
    for row in ledger["sources"]:
        sid = row["sourceId"]
        need(sid not in sources, f"duplicate source {sid}")
        for field in ("book", "chapter", "quote", "claim"):
            need(isinstance(row.get(field), str) and row[field].strip(), f"source {sid} lacks {field}")
        origin = row["origin"]
        if origin["kind"] == "STRONGHOLD_PRIMARY_EVIDENCE":
            site = ferries.get(origin["strongholdId"]) or fail(f"source {sid}: unknown stronghold")
            need(any(e["quote"] == row["quote"] and e["book"] == row["book"]
                     for e in site["primaryEvidence"]),
                 f"source {sid} no longer matches {origin['strongholdId']} primaryEvidence verbatim")
        else:
            need(origin["kind"] == "SHILIAO_QUERY" and origin.get("query"),
                 f"source {sid} has unsupported origin")
        sources[sid] = row

    # --- systems and reaches -----------------------------------------------------------------
    systems: dict[str, set] = {}
    for row in ledger["systems"]:
        cells = flood(terrain, (row["seedRow"], row["seedCol"]), WATER)
        need(len(cells) == row["expectedCellCount"],
             f"system {row['stableKey']} cell count {len(cells)} != {row['expectedCellCount']}")
        systems[row["stableKey"]] = cells
    reach_cells: dict[str, set] = {}
    reach_rows = []
    for row in ledger["reaches"]:
        key = row["stableKey"]
        need(key not in reach_cells, f"duplicate reach {key}")
        need(row["system"] in systems, f"reach {key} names unknown system")
        sel = row["selector"]
        box = (sel["rowMin"], sel["rowMax"], sel["colMin"], sel["colMax"])
        cells = flood(terrain, (sel["seedRow"], sel["seedCol"]), {RIVER}, box)
        need(cells <= systems[row["system"]], f"reach {key} leaves its water system")
        need(len(cells) == sel["expectedCellCount"],
             f"reach {key} cell count {len(cells)} != {sel['expectedCellCount']}")
        need(len(cells) >= 2, f"reach {key}: per-water-tile nodes are forbidden")
        for other, other_cells in reach_cells.items():
            need(not (cells & other_cells), f"reaches {key} and {other} overlap")
        for field in ("nameHan", "upstreamJunction", "downstreamJunction"):
            need(isinstance(row.get(field), str) and row[field], f"reach {key} lacks {field}")
        reach_cells[key] = cells
        reach_rows.append({
            "id": f"water-zone:{key}", "kind": "RIVER_REACH", "system": row["system"],
            "nameHan": row["nameHan"], "flowDirection": row.get("flowDirection"),
            "upstreamJunction": row["upstreamJunction"],
            "downstreamJunction": row["downstreamJunction"],
            "geometryReview": row["geometryReview"], "cellCount": len(cells),
            "cellRuns": encode_runs(cells), "sourceRefs": _refs(row, sources, f"reach {key}"),
        })

    # --- nodes -------------------------------------------------------------------------------
    def site_cell(ref: dict, where: str) -> tuple[int, int]:
        if ref["kind"] == "STRONGHOLD":
            site = ferries.get(ref["id"]) or fail(f"{where}: unknown FERRY stronghold {ref['id']}")
            # A reviewed strategic carve may move the actual ferry marker a
            # few cells while preserving the source's projected tileAnchor.
            # Water nodes must follow the committed playable marker.
            placed = cities.get(f"ss-{ref['id']}") or fail(f"{where}: stronghold has no placed city")
            return placed["row"], placed["col"]
        need(ref["kind"] == "CITY", f"{where}: unsupported site kind")
        city = cities.get(ref["id"]) or fail(f"{where}: unknown city {ref['id']}")
        return city["row"], city["col"]

    node_rows, edges, seen_sites = [], [], set()
    for row in ledger["nodes"]:
        key = row["stableKey"]
        where = f"node {key}"
        refs = _refs(row, sources, where)
        site = (row["siteRef"]["kind"], row["siteRef"]["id"])
        need(site not in seen_sites, f"{where}: site listed twice")
        seen_sites.add(site)
        cell = site_cell(row["siteRef"], where)
        need([cell[0], cell[1]] == [row["cell"]["row"], row["cell"]["col"]],
             f"{where}: ledger cell differs from the site's recorded cell — sites are never moved")
        need(row["reach"] in reach_cells, f"{where}: unknown reach")
        reach = reach_cells[row["reach"]]
        roles = row["roles"]
        need(roles and set(roles) <= ROLES and len(set(roles)) == len(roles), f"{where}: bad roles")
        distance = chebyshev_to(cell, reach)
        need(distance is not None and distance <= 1,
             f"{where}: cell is {distance} cells from reach {row['reach']}; "
             "a node must be within 1 cell of a water cell of its reach (no inland port)")
        out = {"id": f"waterway-node:{key}", "nameHan": row["nameHan"], "siteRef": row["siteRef"],
               "cell": row["cell"], "reachId": f"water-zone:{row['reach']}", "roles": sorted(roles),
               "cellDistanceToReach": distance, "sourceRefs": refs}
        need(("PORT" in roles) == (row.get("port") is not None), f"{where}: port block/role mismatch")
        need(("CROSSING" in roles) == (row.get("crossing") is not None),
             f"{where}: crossing block/role mismatch")
        if "PORT" in roles:
            port = row["port"]
            port_refs = _refs(port, sources, f"{where} port")
            pid = port["landProvinceId"]
            need(pid in province_index, f"{where}: unknown port province")
            touches = any(owner[cell[0] + dr][cell[1] + dc] == province_index[pid]
                          for dr in (-1, 0, 1) for dc in (-1, 0, 1))
            need(touches, f"{where}: port province {pid} does not own a cell at the node")
            for mode, a, b in (("EMBARK", "LAND_PROVINCE", "WATER_ZONE"),
                               ("DISEMBARK", "WATER_ZONE", "LAND_PROVINCE")):
                ends = {"LAND_PROVINCE": pid, "WATER_ZONE": f"water-zone:{row['reach']}"}
                edges.append({"id": f"traversal-edge:{mode.lower()}-{key}", "mode": mode,
                              "from": {"kind": a, "id": ends[a]}, "to": {"kind": b, "id": ends[b]},
                              "nodeId": out["id"], "sourceRefs": port_refs,
                              "status": "PROPOSED_NOT_ACTIVATED"})
        if "CROSSING" in roles:
            crossing = row["crossing"]
            need(crossing["mode"] == "FERRY", f"{where}: slice 1 only adjudicates FERRY crossings")
            crossing_refs = _refs(crossing, sources, f"{where} crossing")
            banks = []
            for side in ("bankA", "bankB"):
                bank = crossing[side]
                b = (bank["row"], bank["col"])
                need(terrain[b[0]][b[1]] not in WATER, f"{where}: {side} cell is water")
                need(bank["landProvinceId"] in province_index
                     and owner[b[0]][b[1]] == province_index[bank["landProvinceId"]],
                     f"{where}: {side} cell is not owned by {bank['landProvinceId']}")
                need(max(abs(b[0] - cell[0]), abs(b[1] - cell[1])) <= BANK_MAX_FROM_NODE,
                     f"{where}: {side} is too far from the node")
                need((chebyshev_to(b, reach) or 0) <= 1 and chebyshev_to(b, reach) is not None,
                     f"{where}: {side} does not touch the reach")
                banks.append((bank, b))
            need(banks[0][0]["landProvinceId"] != banks[1][0]["landProvinceId"],
                 f"{where}: both banks belong to one province; a province-to-province FERRY "
                 "cannot express it")
            side_a = land_component(terrain, banks[0][1], cell, reach)
            need(banks[1][1] not in side_a,
                 f"{where}: bank cells are on the SAME bank of {row['reach']} "
                 "(connected by land without crossing the reach)")
            edges.append({"id": f"traversal-edge:ferry-{key}", "mode": "FERRY",
                          "from": {"kind": "LAND_PROVINCE", "id": banks[0][0]["landProvinceId"]},
                          "to": {"kind": "LAND_PROVINCE", "id": banks[1][0]["landProvinceId"]},
                          "acrossReachId": f"water-zone:{row['reach']}", "nodeId": out["id"],
                          "bankCells": [[b[0], b[1]] for _, b in banks],
                          "sourceRefs": crossing_refs, "status": "PROPOSED_NOT_ACTIVATED"})
        node_rows.append(out)

    # --- flow links --------------------------------------------------------------------------
    for row in ledger["flowLinks"]:
        key = row["stableKey"]
        up, down = row["upstreamReach"], row["downstreamReach"]
        need(up in reach_cells and down in reach_cells and up != down, f"flow {key}: bad reaches")
        touching = any((r + dr, c + dc) in reach_cells[down]
                       for r, c in reach_cells[up] for dr, dc in NEIGH8)
        need(touching, f"flow {key}: reaches do not touch — a route cannot jump between reaches")
        refs = _refs(row, sources, f"flow {key}")
        for mode, a, b in (("RIVER_DOWN", up, down), ("RIVER_UP", down, up)):
            edges.append({"id": f"traversal-edge:{mode.lower().replace('_', '-')}-{key}",
                          "mode": mode, "from": {"kind": "WATER_ZONE", "id": f"water-zone:{a}"},
                          "to": {"kind": "WATER_ZONE", "id": f"water-zone:{b}"},
                          "directionPairKey": key, "sourceRefs": refs,
                          "status": "PROPOSED_NOT_ACTIVATED"})

    # --- port links --------------------------------------------------------------------------
    # 항구와 항구를 잇는 물길. han-world-v3 의 城↔城 강 뱃길(build_han_world.py)이 이 표만 읽는다.
    # 원장은 출처를 달 뿐 쌍을 고르지 못한다 — 쌍은 구간·흐름에서 아래 규칙으로 유도되고, 원장의 쌍
    # 집합이 유도된 집합과 정확히 같아야 한다(빠뜨려도, 건너뛰어도, 끊긴 구간을 넘어도 죽는다).
    reach_graph: dict[str, set[str]] = {key: set() for key in reach_cells}
    for row in ledger["flowLinks"]:
        reach_graph[row["upstreamReach"]].add(row["downstreamReach"])
        reach_graph[row["downstreamReach"]].add(row["upstreamReach"])

    def reach_path(start: str, goal: str) -> list[str] | None:
        previous: dict[str, str | None] = {start: None}
        queue = deque([start])
        while queue:
            current = queue.popleft()
            if current == goal:
                path = []
                while current is not None:
                    path.append(current)
                    current = previous[current]
                return path[::-1]
            for nxt in sorted(reach_graph[current]):
                if nxt not in previous:
                    previous[nxt] = current
                    queue.append(nxt)
        return None

    def water_distance(a: tuple[int, int], b: tuple[int, int], cells: set) -> int | None:
        starts = [cell for cell in cells if max(abs(cell[0] - a[0]), abs(cell[1] - a[1])) <= 1]
        goals = {cell for cell in cells if max(abs(cell[0] - b[0]), abs(cell[1] - b[1])) <= 1}
        seen = {cell: 0 for cell in starts}
        queue = deque(starts)
        while queue:
            current = queue.popleft()
            if current in goals:
                return seen[current]
            for dr, dc in NEIGH8:
                nxt = (current[0] + dr, current[1] + dc)
                if nxt in cells and nxt not in seen:
                    seen[nxt] = seen[current] + 1
                    queue.append(nxt)
        return None

    ports = {row["stableKey"]: row for row in ledger["nodes"] if "PORT" in row["roles"]}
    port_cell = {key: (row["cell"]["row"], row["cell"]["col"]) for key, row in ports.items()}
    derived: dict[tuple[str, str], tuple[list[str], int]] = {}
    for a in sorted(ports):
        for b in sorted(ports):
            if a >= b:
                continue
            path = reach_path(ports[a]["reach"], ports[b]["reach"])
            if path is None:
                continue
            cells = set().union(*(reach_cells[key] for key in path))
            span = water_distance(port_cell[a], port_cell[b], cells)
            need(span is not None, f"port pair {a}/{b}: flow-linked reaches are not one water body")
            between = [c for c in ports if c not in (a, b) and ports[c]["reach"] in path
                       and (water_distance(port_cell[a], port_cell[c], cells) or 0) < span
                       and (water_distance(port_cell[c], port_cell[b], cells) or 0) < span]
            if not between:
                derived[(a, b)] = (path, span)
    port_link_rows = []
    listed: set[tuple[str, str]] = set()
    for row in ledger.get("portLinks", []):
        key = row["stableKey"]
        a, b = row["fromNode"], row["toNode"]
        need(a in ports and b in ports, f"port link {key}: endpoints must be reviewed PORT nodes")
        pair = (min(a, b), max(a, b))
        need(a != b and pair not in listed, f"port link {key}: duplicate or self link")
        listed.add(pair)
        need(pair in derived,
             f"port link {key}: {a}/{b} are not consecutive ports on flow-linked reaches "
             "(a route cannot jump between reaches or skip a port)")
        path, span = derived[pair]
        port_link_rows.append({
            "id": f"port-link:{key}", "fromNodeId": f"waterway-node:{a}", "toNodeId": f"waterway-node:{b}",
            "fromSiteRef": ports[a]["siteRef"], "toSiteRef": ports[b]["siteRef"],
            "reachPath": [f"water-zone:{r}" for r in path], "waterCellDistance": span,
            "sourceRefs": _refs(row, sources, f"port link {key}"),
            "status": "CITY_CONNECTION_ONLY",
        })
    need(listed == set(derived),
         f"port links missing for consecutive ports: {sorted(set(derived) - listed)}")

    # --- blocked -----------------------------------------------------------------------------
    blocked_rows = []
    for row in ledger["blocked"]:
        key = row["stableKey"]
        where = f"blocked {key}"
        need(row["reasonCode"] in BLOCK_CODES, f"{where}: unknown reasonCode")
        site = (row["siteRef"]["kind"], row["siteRef"]["id"])
        need(site not in seen_sites, f"{where}: site listed twice")
        seen_sites.add(site)
        cell = site_cell(row["siteRef"], where)
        measured = chebyshev_to(cell, all_water)
        need(measured == row["measuredCellDistanceToWater"],
             f"{where}: ledger distance {row['measuredCellDistanceToWater']} != measured {measured}")
        if row["reasonCode"] in {"RIVER_COURSE_NOT_IN_GRID", "PORT_CELL_NOT_ADJACENT_TO_WATER"}:
            need(measured is None or measured >= 2, f"{where}: site IS adjacent to water")
        elif row["reasonCode"] in {"PORT_CELL_NOT_ADJACENT_TO_REACH", "FERRY_ANCHOR_NOT_CONNECTED_TO_ATTESTED_REACH"}:
            if row["reasonCode"] == "FERRY_ANCHOR_NOT_CONNECTED_TO_ATTESTED_REACH":
                need(row["siteRef"]["kind"] == "STRONGHOLD" and row["siteRef"]["id"] in ferries,
                     f"{where}: disconnected ferry reason requires a FERRY stronghold")
            need(row.get("reach") in reach_cells, f"{where}: names no known reach")
            to_reach = chebyshev_to(cell, reach_cells[row["reach"]])
            need(to_reach == row.get("measuredCellDistanceToReach") and to_reach >= 2,
                 f"{where}: reach distance {to_reach} does not justify the block")
        elif row["reasonCode"] == "SITE_PROVINCE_DOES_NOT_TOUCH_REACH":
            need(measured is not None and measured >= 2,
                 f"{where}: moved site is now adjacent to water; review its province against the reach")
        elif row["reasonCode"] in {"ROLE_NOT_ESTABLISHED_BY_EVIDENCE", "OUT_OF_SLICE_WATER_SYSTEM"}:
            need(measured is not None, f"{where}: measured water distance unavailable")
        else:
            need(measured is not None and measured <= 1, f"{where}: site is not on water")
        blocked_rows.append({"id": f"blocked-node:{key}", "nameHan": row["nameHan"],
                             "siteRef": row["siteRef"], "cell": [cell[0], cell[1]],
                             "reasonCode": row["reasonCode"],
                             "measuredCellDistanceToWater": measured,
                             "reach": row.get("reach"),
                             "measuredCellDistanceToReach": row.get("measuredCellDistanceToReach"),
                             "note": row["note"],
                             "sourceRefs": _refs(row, sources, where),
                             "status": "BLOCKED_PENDING_ADJUDICATION"})
    ferry_sites = {sid for kind, sid in seen_sites if kind == "STRONGHOLD"}
    need(ferry_sites == set(ferries),
         f"FERRY strongholds unaccounted: missing={sorted(set(ferries) - ferry_sites)}")

    need(len({e["id"] for e in edges}) == len(edges), "duplicate edge id")
    used = {ref for coll in (reach_rows, node_rows, edges, blocked_rows, port_link_rows) for r in coll
            for ref in r["sourceRefs"]}
    need(used == set(sources), f"unused sources: {sorted(set(sources) - used)}")
    return {
        "schemaVersion": 1, "artifactId": ARTIFACT_ID, "activation": "NON_ACTIVATING",
        "base": base,
        "sources": sorted(sources.values(), key=lambda s: s["sourceId"]),
        "waterZones": sorted(reach_rows, key=lambda r: r["id"]),
        "nodes": sorted(node_rows, key=lambda r: r["id"]),
        "proposedTraversalEdges": sorted(edges, key=lambda r: r["id"]),
        "blockedNodes": sorted(blocked_rows, key=lambda r: r["id"]),
        "portLinks": sorted(port_link_rows, key=lambda r: r["id"]),
        "counts": {
            "reaches": len(reach_rows), "nodes": len(node_rows),
            "crossings": sum(e["mode"] == "FERRY" for e in edges),
            "ports": sum(e["mode"] == "EMBARK" for e in edges),
            "flowLinks": len(ledger["flowLinks"]), "blocked": len(blocked_rows),
            "portLinks": len(port_link_rows),
        },
    }


def render(ledger_bytes: bytes | None = None) -> bytes:
    tiles_bytes = TILES.read_bytes()
    strongholds_bytes = STRONGHOLDS.read_bytes()
    ledger = json.loads(ledger_bytes if ledger_bytes is not None else LEDGER.read_bytes())
    return canonical_json_bytes(build(json.loads(tiles_bytes), tiles_bytes,
                                      json.loads(strongholds_bytes), strongholds_bytes, ledger))


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--write", action="store_true")
    mode.add_argument("--check", action="store_true")
    args = parser.parse_args(argv)
    data = render()
    if args.check:
        ok = OUTPUT.is_file() and OUTPUT.read_bytes() == data
        print(f"Han waterway network check {'passed' if ok else 'FAILED: committed artifact drifted'}")
        return 0 if ok else 1
    OUTPUT.write_bytes(data)
    print(f"generated {OUTPUT.relative_to(ROOT)} ({len(data)} bytes)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
