#!/usr/bin/env python3
"""★ 지리 재분할 — 郡 안 縣 경계를 城의 실제 위치로 다시 자른다 (GH #806, S1: 스크래치 전용).

사용자 결정(2026-09-17): 지리 우선. 넓이 균형을 포기한다.
규칙 정본: docs/superpowers/specs/2026-09-17-province-geography-first.md §3 (규칙 0–7).

이 도구는 **커밋된 산출물을 쓰지 않는다.** 입력은 커밋된 data/map/han-tiles.json 에서 저지 지형·접기·거점 분할을
벗긴 문서(★ 가 사슬에서 설 자리)이고, 출력은 --output 으로 받은 스크래치 경로다. data/·infra/ 밑 경로는 거부한다.

  python3 tools/map/partition_counties_by_location.py --output build/province-partition/han-tiles.partitioned.json

수치 출처: min_area 8 · max_area 620 은 spec §3 규칙 4·5 의 「현행」 값(`ProvinceQualityPolicy`)을 옮긴 것이다.
10/14 는 spec 규칙 3 의 정수 비용이다. 이 파일은 새 임계를 짓지 않는다.

spec 에 없어 이 구현이 기계적으로 정한 것(보고서 `mechanicalChoices` 에 그대로 실린다, S1.5 에서 확인받는다):
  * 8-이웃 최단경로 영역은 8-연결이지만 省 성분 검사는 4-연결이다. 대각으로만 붙은 조각은 같은 郡의
    4-이웃 관할 중 맞닿은 변이 가장 많은 곳(동률 id)으로 넘긴다.
  * 씨앗 없는 郡 성분·넘길 곳 없는 조각은 min_area 이상이면 城 없는 省 하나가 되고, 미만이면 seat 省에 붙은
    다성분 예외로 남는다(둘 다 `components` 행).
  * 씨앗 충돌은 판정 원장이 없으므로 전부 규칙 2(b)(id 순, 가장 가까운 빈 칸)로 놓고 `UNREVIEWED` 로 표시한다.
"""
from __future__ import annotations

import argparse
import copy
import hashlib
import heapq
import json
import math
import sys
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))

TILES = ROOT / "data/map/han-tiles.json"
MIN_AREA = 8      # spec §3 규칙 4 「현행 8」
MAX_AREA = 620    # spec §3 규칙 5 「현행 620」
STRAIGHT, DIAGONAL = 10, 14  # spec §3 규칙 3
SUBDIVISION_BASIS = "WITHIN_COUNTY_SUBDIVISION"
SUBDIVISION_GEOMETRY = "COUNTY_LOCATION_PARTITION"
FORBIDDEN_OUTPUT_ROOTS = ("data", "infra", "web", "app", "logic", "common")
STEPS8 = ((-1, 0, STRAIGHT), (1, 0, STRAIGHT), (0, -1, STRAIGHT), (0, 1, STRAIGHT),
          (-1, -1, DIAGONAL), (-1, 1, DIAGONAL), (1, -1, DIAGONAL), (1, 1, DIAGONAL))
STEPS4 = ((-1, 0), (1, 0), (0, -1), (0, 1))


def expand(runs, rows: int, cols: int) -> np.ndarray:
    values, counts = zip(*((int(a), int(b)) for a, b in runs))
    grid = np.repeat(np.asarray(values, dtype=np.int32), counts)
    if grid.size != rows * cols:
        raise ValueError("run-length does not fill the grid")
    return grid.reshape(rows, cols)


def encode(grid: np.ndarray) -> list[list[int]]:
    flat = grid.ravel()
    starts = np.flatnonzero(np.r_[True, flat[1:] != flat[:-1]])
    lengths = np.diff(np.r_[starts, flat.size])
    return [[int(v), int(n)] for v, n in zip(flat[starts].tolist(), lengths.tolist())]


def county_adjacency(owner: np.ndarray) -> list[dict]:
    """build_terrain_grid.adjacency(min_shared_edges=1) 와 같은 표 — 맞닿은 격자변 수."""
    pairs: dict[tuple[int, int], int] = {}
    for a, b in ((owner[:, :-1], owner[:, 1:]), (owner[:-1, :], owner[1:, :])):
        m = (a >= 0) & (b >= 0) & (a != b)
        lo, hi = np.minimum(a[m], b[m]), np.maximum(a[m], b[m])
        keys, counts = np.unique(np.stack([lo, hi], axis=1), axis=0, return_counts=True) if lo.size else ([], [])
        for (u, v), n in zip(keys, counts):
            pairs[(int(u), int(v))] = pairs.get((int(u), int(v)), 0) + int(n)
    return [{"a": a, "b": b, "cells": n} for (a, b), n in sorted(pairs.items())]


def project_cell(projection: dict, latitude: float, longitude: float) -> tuple[int, int]:
    """(row, col). measure_province_seat_offset.project_cell 과 같은 식, 칸 색인은 내림."""
    col = (longitude * projection["k"] - projection["x0"] + projection["pad"]) / projection["cell"]
    row = (projection["y1"] + projection["pad"] - latitude) / projection["cell"]
    return math.floor(row), math.floor(col)


def _nearest(cells: np.ndarray, target: tuple[int, int]) -> tuple[tuple[int, int], float]:
    """cells(N×2, row-major 정렬)에서 target 에 유클리드로 가장 가까운 칸. 동률은 (row, col)."""
    d2 = (cells[:, 0] - target[0]) ** 2 + (cells[:, 1] - target[1]) ** 2
    at = int(np.argmin(d2))  # argmin 은 첫 최소 = row-major 첫 칸
    return (int(cells[at, 0]), int(cells[at, 1])), round(math.sqrt(float(d2[at])), 2)


def _components4(labels: np.ndarray, mask: np.ndarray) -> tuple[np.ndarray, list[int]]:
    """같은 라벨끼리 4-연결 성분. (성분 격자, 성분별 라벨). row-major 발견 순이라 결정론적이다."""
    rows, cols = labels.shape
    comp = np.full(labels.shape, -1, dtype=np.int32)
    lab = labels.tolist()
    ok = mask.tolist()
    seen = comp.tolist()
    comp_label: list[int] = []
    for r0 in range(rows):
        row_ok = ok[r0]
        for c0 in range(cols):
            if not row_ok[c0] or seen[r0][c0] >= 0:
                continue
            index, value = len(comp_label), lab[r0][c0]
            comp_label.append(value)
            seen[r0][c0] = index
            stack = [(r0, c0)]
            while stack:
                r, c = stack.pop()
                for dr, dc in STEPS4:
                    nr, nc = r + dr, c + dc
                    if 0 <= nr < rows and 0 <= nc < cols and ok[nr][nc] and seen[nr][nc] < 0 and lab[nr][nc] == value:
                        seen[nr][nc] = index
                        stack.append((nr, nc))
    return np.asarray(seen, dtype=np.int32), comp_label


def _seeds(document: dict, owner: np.ndarray, parent: np.ndarray, order: list[dict]) -> tuple[dict, list[dict]]:
    """규칙 1·1b·2. {관할 id: (row, col)} 과 예외 행."""
    meta = document["_meta"]
    rows, cols = meta["rows"], meta["cols"]
    provinces = {row["id"]: row for row in document["provinceRecords"]}
    city_by_id = {row["id"]: row for row in document["cities"]}
    parent_index = {row["id"]: i for i, row in enumerate(document["parentRegions"])}
    land = owner >= 0
    parent_cells: dict[int, np.ndarray] = {}
    wanted, exceptions = {}, {}
    for juris in order:
        k = parent_index[juris["commanderyId"]]
        seat_province = provinces.get(juris["seatPlaceId"])
        if seat_province is not None and seat_province.get("cityIndex") is not None:
            city = document["cities"][seat_province["cityIndex"]]
            cell, basis = project_cell(meta["projection"], city["lat"], city["lon"]), "LONLAT"
        else:
            city = city_by_id.get(juris["seatPlaceId"])
            if city is None:
                raise ValueError(f"jurisdiction {juris['id']} has no seat city")
            cell, basis = (int(city["row"]), int(city["col"])), "STAND_IN_SEAT_POINT"  # 규칙 1b: 지어내지 않는다
        inside = 0 <= cell[0] < rows and 0 <= cell[1] < cols
        if not (inside and land[cell] and int(parent[cell]) == k):
            if k not in parent_cells:
                parent_cells[k] = np.argwhere(land & (parent == k))
            if not len(parent_cells[k]):
                raise ValueError(f"parent {juris['commanderyId']} has no land")
            moved, distance = _nearest(parent_cells[k], cell)
            exceptions[juris["id"]] = {
                "jurisdictionId": juris["id"], "nameCh": juris["nameCh"], "commanderyId": juris["commanderyId"],
                "class": "WATER_OR_OFF_GRID" if not (inside and land[cell]) else "SOURCE_PARENT_NOT_RASTER_PARENT",
                "trueCell": {"col": cell[1], "row": cell[0]}, "seedCell": {"col": moved[1], "row": moved[0]},
                "cellDistance": distance, "seedBasis": basis,
            }
            cell = moved
        wanted[juris["id"]] = (cell, k, basis)
    seeds: dict[str, tuple[int, int]] = {}
    taken: set[tuple[int, int]] = set()
    losers = []
    for juris in order:  # id 순: 같은 칸이면 앞 id 가 그 칸을 갖는다
        cell = wanted[juris["id"]][0]
        if cell in taken:
            losers.append(juris)
        else:
            seeds[juris["id"]] = cell
            taken.add(cell)
    for juris in losers:
        cell, k, basis = wanted[juris["id"]]
        if k not in parent_cells:
            parent_cells[k] = np.argwhere(land & (parent == k))
        free = np.asarray([rc for rc in parent_cells[k].tolist() if tuple(rc) not in taken], dtype=np.int64)
        if not len(free):
            raise ValueError(f"no free cell for {juris['id']}")
        moved, distance = _nearest(free, cell)
        previous = exceptions.get(juris["id"])
        exceptions[juris["id"]] = {
            "jurisdictionId": juris["id"], "nameCh": juris["nameCh"], "commanderyId": juris["commanderyId"],
            "class": "SEED_COLLISION", "ruling": "UNREVIEWED",
            "trueCell": previous["trueCell"] if previous else {"col": cell[1], "row": cell[0]},
            "seedCell": {"col": moved[1], "row": moved[0]}, "cellDistance": distance, "seedBasis": basis,
            "sharedWith": sorted(j for j, rc in seeds.items() if rc == cell),
        }
        seeds[juris["id"]] = moved
        taken.add(moved)
    return seeds, [exceptions[key] for key in sorted(exceptions)]


def _dijkstra(land: np.ndarray, parent: np.ndarray, seeds_by_rank: list[tuple[int, int]]) -> np.ndarray:
    """규칙 3: 郡 마스크 안 8-이웃 10/14 최단경로. (거리, 관할 순위) 사전순 최소 — 동률은 관할 id 순."""
    rows, cols = land.shape
    ok, par = land.tolist(), parent.tolist()
    label = [[-1] * cols for _ in range(rows)]
    best: dict[tuple[int, int], tuple[int, int]] = {}
    heap = []
    for rank, (r, c) in enumerate(seeds_by_rank):
        best[(r, c)] = (0, rank)
        heap.append((0, rank, r, c))
    heapq.heapify(heap)
    while heap:
        dist, rank, r, c = heapq.heappop(heap)
        if label[r][c] >= 0 or best.get((r, c)) != (dist, rank):
            continue
        label[r][c] = rank
        k = par[r][c]
        for dr, dc, w in STEPS8:
            nr, nc = r + dr, c + dc
            if 0 <= nr < rows and 0 <= nc < cols and ok[nr][nc] and par[nr][nc] == k and label[nr][nc] < 0:
                cand = (dist + w, rank)
                old = best.get((nr, nc))
                if old is None or cand < old:
                    best[(nr, nc)] = cand
                    heapq.heappush(heap, (cand[0], rank, nr, nc))
    return np.asarray(label, dtype=np.int32)


def _assign_seedless(label, land, parent, order, seeds, parent_index) -> list[dict]:
    """규칙 3 둘째 문단: 씨앗 없는 郡 성분은 같은 郡에서 유클리드로 가장 가까운 관할에 간다."""
    pending = land & (label < 0)
    rows_out = []
    if not pending.any():
        return rows_out
    comp, _ = _components4(np.where(pending, parent, -1), pending)
    by_parent: dict[int, list[int]] = {}
    for rank, juris in enumerate(order):
        by_parent.setdefault(parent_index[juris["commanderyId"]], []).append(rank)
    for index in range(int(comp.max()) + 1):
        cells = np.argwhere(comp == index)
        k = int(parent[tuple(cells[0])])
        ranks = by_parent.get(k)
        if not ranks:
            raise ValueError(f"parent index {k} has land but no jurisdiction")
        def distance(rank):
            seed = seeds[order[rank]["id"]]
            return int(((cells[:, 0] - seed[0]) ** 2 + (cells[:, 1] - seed[1]) ** 2).min())
        winner = min(ranks, key=lambda rank: (distance(rank), rank))
        label[cells[:, 0], cells[:, 1]] = winner
        rows_out.append({"jurisdictionId": order[winner]["id"], "cells": int(len(cells)),
                         "firstCell": {"col": int(cells[0, 1]), "row": int(cells[0, 0])}})
    return rows_out


def _repair_diagonal_fragments(label, land, parent, seeds_by_rank) -> int:
    """대각으로만 붙은 조각을 4-이웃 관할로 넘긴다. 넘긴 칸 수를 돌려준다."""
    moved = 0
    for _ in range(64):
        comp, comp_label = _components4(label, land)
        main = {int(comp[seed]) for seed in seeds_by_rank}
        sizes = np.bincount(comp[comp >= 0], minlength=len(comp_label))
        votes: dict[int, dict[int, int]] = {}
        pairs = (((slice(None), slice(None, -1)), (slice(None), slice(1, None))),
                 ((slice(None, -1), slice(None)), (slice(1, None), slice(None))))
        for left, right in pairs:
            ca, cb = comp[left], comp[right]
            m = (ca >= 0) & (cb >= 0) & (ca != cb) & (parent[left] == parent[right])
            for u, v in zip(ca[m].tolist(), cb[m].tolist()):
                for fragment, other in ((u, v), (v, u)):
                    if fragment not in main and other in main:
                        tally = votes.setdefault(fragment, {})
                        tally[comp_label[other]] = tally.get(comp_label[other], 0) + 1
        if not votes:
            return moved
        for fragment in sorted(votes):
            winner = min(votes[fragment], key=lambda rank: (-votes[fragment][rank], rank))
            label[comp == fragment] = winner
            moved += int(sizes[fragment])
    raise ValueError("diagonal fragment repair did not converge")


def _connected_without(cells: set[tuple[int, int]], removed: tuple[int, int]) -> bool:
    rest = cells - {removed}
    if not rest:
        return False
    start = next(iter(sorted(rest)))
    seen, stack = {start}, [start]
    while stack:
        r, c = stack.pop()
        for dr, dc in STEPS4:
            nxt = (r + dr, c + dc)
            if nxt in rest and nxt not in seen:
                seen.add(nxt)
                stack.append(nxt)
    return len(seen) == len(rest)


def _fill_minimum(label, land, parent, order, seeds, min_area) -> list[dict]:
    """규칙 4: min_area 미만이면 이웃 縣에서 가장 가까운 칸을 빌린다."""
    rows, cols = label.shape
    seed_cells = set(seeds.values())
    borrowed = []
    areas = np.bincount(label[label >= 0], minlength=len(order)).tolist()
    for rank, juris in enumerate(order):
        if areas[rank] >= min_area:
            continue
        seed = seeds[juris["id"]]
        comp, _ = _components4(np.where(label == rank, 1, -1), label == rank)
        own = {tuple(rc) for rc in np.argwhere(comp == comp[seed]).tolist()}
        took, donors = 0, {}
        while len(own) < min_area:
            candidates = set()
            for r, c in own:
                for dr, dc in STEPS4:
                    n = (r + dr, c + dc)
                    if (0 <= n[0] < rows and 0 <= n[1] < cols and land[n] and parent[n] == parent[seed]
                            and label[n] != rank and n not in seed_cells and areas[int(label[n])] > min_area):
                        candidates.add(n)
            chosen = None
            for n in sorted(candidates, key=lambda n: ((n[0] - seed[0]) ** 2 + (n[1] - seed[1]) ** 2, n)):
                donor = int(label[n])
                donor_cells = {tuple(rc) for rc in np.argwhere(label == donor).tolist()}
                if _connected_without(donor_cells, n):
                    chosen = (n, donor)
                    break
            if chosen is None:
                break
            n, donor = chosen
            label[n] = rank
            areas[donor] -= 1
            areas[rank] += 1
            own.add(n)
            took += 1
            donors[order[donor]["id"]] = donors.get(order[donor]["id"], 0) + 1
        borrowed.append({"jurisdictionId": juris["id"], "nameCh": juris["nameCh"], "borrowedCells": took,
                         "donors": dict(sorted(donors.items())), "areaAfter": areas[rank],
                         "satisfied": areas[rank] >= min_area})
    return borrowed


def _split(mask: np.ndarray, seed: tuple[int, int] | None, max_area: int) -> tuple[np.ndarray, int]:
    """규칙 5: n = ceil(area / max_area), 어느 조각이 상한을 넘으면 n += 1."""
    from tools.map.province_quality import balanced_parent_labels
    rr, cc = np.nonzero(mask)
    r0, r1, c0, c1 = rr.min(), rr.max() + 1, cc.min(), cc.max() + 1
    window = mask[r0:r1, c0:c1]
    anchors = [(seed[0] - int(r0), seed[1] - int(c0))] if seed is not None else []
    area = int(window.sum())
    n = math.ceil(area / max_area)
    while True:
        pieces, _ = balanced_parent_labels(window, n, fixed_anchors=anchors)
        if int(np.bincount(pieces[pieces >= 0]).max()) <= max_area:
            break
        n += 1
    out = np.full(mask.shape, -1, dtype=np.int32)
    out[r0:r1, c0:c1] = pieces
    return out, n


def _sub_id(juris: dict, stand_in: bool, ordinal: int) -> str:
    token = hashlib.sha256(f"{juris['id']}:geo:{ordinal}".encode()).hexdigest()[:12]
    # 규칙 6: DIRECT- 접두어는 대리 治所 관할의 省에만 남긴다.
    return f"DIRECT-{juris['commanderyId']}-{token}" if stand_in else f"SUB-{juris['id']}-{token}"


def _jurisdiction_order(document: dict) -> list[dict]:
    """관할 순위 = id 사전순. 입력 배열 순서를 쓰면 Q5 가 깨진다(테스트의 적색 프로브가 이 함수를 바꿔 끼운다)."""
    return sorted(document["jurisdictionRecords"], key=lambda row: row["id"])


def partition(source: dict, *, min_area: int = MIN_AREA, max_area: int = MAX_AREA) -> tuple[dict, dict]:
    """순수 함수. (새 문서, 보고서). 입력의 배열 순서(cities·jurisdictionRecords)에 의존하지 않는다."""
    document = copy.deepcopy(source)
    meta = document["_meta"]
    rows, cols = meta["rows"], meta["cols"]
    owner_in = expand(document["owner"], rows, cols)
    parent = expand(document["parentOwner"], rows, cols)
    land = owner_in >= 0
    if ((parent >= 0) != land).any():
        raise ValueError("parentOwner and owner disagree on land")
    order = _jurisdiction_order(document)
    parent_index = {row["id"]: i for i, row in enumerate(document["parentRegions"])}
    provinces_in = {row["id"]: row for row in document["provinceRecords"]}
    seeds, exceptions = _seeds(document, owner_in, parent, order)
    seeds_by_rank = [seeds[j["id"]] for j in order]

    label = _dijkstra(land, parent, seeds_by_rank)
    seedless = _assign_seedless(label, land, parent, order, seeds, parent_index)
    repaired = _repair_diagonal_fragments(label, land, parent, seeds_by_rank)
    borrowed = _fill_minimum(label, land, parent, order, seeds, min_area)
    if (label[land] < 0).any():
        raise ValueError("unassigned land after partition")

    # 省 조각 만들기
    comp, comp_label = _components4(label, land)
    comp_sizes = np.bincount(comp[comp >= 0], minlength=len(comp_label)).tolist()
    comps_of: dict[int, list[int]] = {}
    for index, rank in enumerate(comp_label):
        comps_of.setdefault(rank, []).append(index)
    seat_records = [row for row in document["provinceRecords"] if row.get("cityIndex") is not None]
    piece_grid = np.full((rows, cols), -1, dtype=np.int32)  # 임시 조각 번호
    pieces: list[dict] = []
    components_ledger, subdivisions = [], []
    for rank, juris in enumerate(order):
        seed = seeds[juris["id"]]
        seat_province = provinces_in.get(juris["seatPlaceId"])
        stand_in = seat_province is None or seat_province.get("cityIndex") is None
        main = int(comp[seed])
        seat_piece = None
        extra = []
        for index in comps_of[rank]:
            cmask = comp == index
            holds_seed = index == main
            if comp_sizes[index] > max_area:
                split, n = _split(cmask, seed if holds_seed else None, max_area)
                subdivisions.append({"jurisdictionId": juris["id"], "nameCh": juris["nameCh"],
                                     "componentCells": comp_sizes[index], "pieces": n})
                for value in range(n):
                    pmask = split == value
                    piece = {"rank": rank, "mask": pmask, "seat": bool(holds_seed and pmask[seed])}
                    if piece["seat"]:
                        seat_piece = piece
                    else:
                        extra.append(piece)
            elif holds_seed:
                seat_piece = {"rank": rank, "mask": cmask, "seat": True}
            else:
                extra.append({"rank": rank, "mask": cmask, "seat": False, "structural": True})
        assert seat_piece is not None
        if len(comps_of[rank]) > 1:
            for index in comps_of[rank]:
                if index != main:
                    cells = np.argwhere(comp == index)
                    touches_foreign = False
                    for r, c in cells.tolist():
                        for dr, dc in STEPS4:
                            nr, nc = r + dr, c + dc
                            if 0 <= nr < rows and 0 <= nc < cols and land[nr, nc] and parent[nr, nc] != parent[r, c]:
                                touches_foreign = True
                    components_ledger.append({
                        "jurisdictionId": juris["id"], "nameCh": juris["nameCh"], "cells": comp_sizes[index],
                        "firstCell": {"col": int(cells[0, 1]), "row": int(cells[0, 0])},
                        "reason": "PARENT_MASK_SPLIT" if touches_foreign else "WATER_SEPARATED",
                        "reasonBasis": "MECHANICAL_UNREVIEWED",
                        "disposition": ("OWN_PROVINCE" if comp_sizes[index] >= min_area else "KEPT_IN_SEAT_PROVINCE"),
                    })
        kept = []
        for piece in extra:
            if piece.get("structural") and int(piece["mask"].sum()) < min_area:
                seat_piece["mask"] = seat_piece["mask"] | piece["mask"]
                seat_piece["multiComponent"] = True
            else:
                kept.append(piece)
        kept.sort(key=lambda piece: tuple(np.argwhere(piece["mask"])[0].tolist()))
        old_ids = list(juris["provinceIds"])
        if stand_in:
            group = [seat_piece] + kept
            for ordinal, piece in enumerate(group):
                piece["id"] = (old_ids[0] if len(group) == 1 and len(old_ids) == 1
                               else _sub_id(juris, True, ordinal))
                piece["template"] = provinces_in[old_ids[0]]
        else:
            seat_piece["id"] = seat_province["id"]
            for ordinal, piece in enumerate(kept, start=1):
                piece["id"] = _sub_id(juris, False, ordinal)
                piece["template"] = seat_province
        for piece in [seat_piece] + kept:
            piece["juris"] = juris
            piece["standIn"] = stand_in
            pieces.append(piece)

    # 省 행: 城 있는 省은 입력 순서 그대로, 城 없는 省은 id 순으로 뒤에 붙인다.
    cityless = sorted((p for p in pieces if p["standIn"] or not p["seat"]), key=lambda p: p["id"])
    records, index_of = [], {}
    for row in seat_records:
        index_of[row["id"]] = len(records)
        records.append(row)
    for piece in cityless:
        template = piece["template"]
        juris = piece["juris"]
        keep_old = piece["id"] in provinces_in
        record = dict(provinces_in[piece["id"]]) if keep_old else {
            "id": piece["id"], "displayName": juris["displayName"], "nameCh": juris["nameCh"],
            "administrativeSystem": template["administrativeSystem"], "kind": "SPATIAL_PROVINCE",
            "parentRegionId": template["parentRegionId"], "cityIndex": None,
            "geometryBasis": SUBDIVISION_GEOMETRY, "confidence": "INFERRED",
            "jurisdictionId": juris["id"], "assignmentBasis": SUBDIVISION_BASIS,
            "assignmentConfidence": "INFERRED",
        }
        index_of[piece["id"]] = len(records)
        records.append(record)
    if len(index_of) != len(records):
        raise ValueError("duplicate province id")
    owner = np.full((rows, cols), -1, dtype=np.int32)
    for piece in pieces:
        owner[piece["mask"]] = index_of[piece["id"]]
    if ((owner >= 0) != land).any():
        raise ValueError("orphan or invented land")

    by_juris: dict[str, list[str]] = {}
    for piece in pieces:
        by_juris.setdefault(piece["juris"]["id"], []).append(piece["id"])
    for juris in document["jurisdictionRecords"]:
        juris["provinceIds"] = sorted(by_juris[juris["id"]])  # validate_materialized_hierarchy 의 계약: 정렬
    for juris in order:  # 규칙 7
        seat_province = provinces_in.get(juris["seatPlaceId"])
        if seat_province is not None and seat_province.get("cityIndex") is not None:
            city = document["cities"][seat_province["cityIndex"]]
            city["row"], city["col"] = int(seeds[juris["id"]][0]), int(seeds[juris["id"]][1])
    document["provinceRecords"] = records
    document["owner"] = encode(owner)
    document["adjacency"] = {**document["adjacency"], "county": county_adjacency(owner)}
    counts = meta.get("counts", {})
    counts["provinces"] = len(records)
    counts["adjCounty"] = len(document["adjacency"]["county"])

    new_ids = {row["id"] for row in records}
    areas = np.bincount(owner[owner >= 0], minlength=len(records)).tolist()
    report = {
        "rules": {"minArea": min_area, "maxArea": max_area, "straight": STRAIGHT, "diagonal": DIAGONAL,
                  "source": "spec 2026-09-17-province-geography-first §3 (현행 값을 옮김, 이 도구가 지은 임계 없음)"},
        "mechanicalChoices": ["DIAGONAL_FRAGMENT_TO_4_NEIGHBOUR", "STRUCTURAL_COMPONENT_OWN_PROVINCE_IF_GE_MIN_AREA",
                              "SEED_COLLISION_DEFAULT_RULE_2B_UNREVIEWED"],
        "counts": {"jurisdictions": len(order), "provinces": len(records),
                   "seatProvinces": len(seat_records), "citylessProvinces": len(cityless),
                   "provincesBefore": len(provinces_in), "adjCounty": counts.get("adjCounty"),
                   "diagonalFragmentCellsReassigned": repaired,
                   "seedlessComponents": len(seedless), "seedlessCells": sum(r["cells"] for r in seedless)},
        "seedExceptions": exceptions,
        "seedlessComponents": seedless,
        "components": components_ledger,
        "minAreaBorrowed": borrowed,
        "subdivisions": subdivisions,
        "areaViolations": [{"provinceId": row["id"], "cells": areas[i],
                            "class": "BELOW_MIN" if areas[i] < min_area else "ABOVE_MAX"}
                           for i, row in enumerate(records) if not min_area <= areas[i] <= max_area],
        "retiredProvinceIds": [{"id": pid, "jurisdictionId": provinces_in[pid]["jurisdictionId"]}
                               for pid in sorted(provinces_in) if pid not in new_ids],
        "multiComponentProvinceIds": sorted(p["id"] for p in pieces if p.get("multiComponent")),
    }
    return document, report


# ── 게이트 (불변식). 문제 목록을 돌려준다 — 빈 목록이 통과다. ────────────────────────────────────────────

def check_parent_unchanged(source: dict, output: dict) -> list[str]:
    """Q2."""
    return [] if source["parentOwner"] == output["parentOwner"] else ["Q2 parentOwner changed"]


def check_cover(source: dict, output: dict, report: dict) -> list[str]:
    """Q3: 무소속 육지 0, 省당 4-연결 성분 1 (예외는 보고서 multiComponentProvinceIds 뿐)."""
    meta = output["_meta"]
    before = expand(source["owner"], meta["rows"], meta["cols"])
    after = expand(output["owner"], meta["rows"], meta["cols"])
    problems = []
    orphan = int(((before >= 0) & (after < 0)).sum())
    if orphan:
        problems.append(f"Q3 orphan land cells: {orphan}")
    if int(((before < 0) & (after >= 0)).sum()):
        problems.append("Q3 land invented outside the input mask")
    if int(after.max()) >= len(output["provinceRecords"]):
        problems.append("Q3 owner index out of range")
        return problems
    _, comp_label = _components4(after, after >= 0)
    per = np.bincount(np.asarray(comp_label), minlength=len(output["provinceRecords"]))
    allowed = set(report.get("multiComponentProvinceIds", []))
    for index, row in enumerate(output["provinceRecords"]):
        if per[index] == 0:
            problems.append(f"Q3 province without cells: {row['id']}")
        elif per[index] > 1 and row["id"] not in allowed:
            problems.append(f"Q3 province {row['id']} has {int(per[index])} components")
    return problems


def check_area(output: dict, *, min_area: int = MIN_AREA, max_area: int = MAX_AREA) -> list[str]:
    """Q4."""
    meta = output["_meta"]
    owner = expand(output["owner"], meta["rows"], meta["cols"])
    areas = np.bincount(owner[owner >= 0], minlength=len(output["provinceRecords"]))
    return [f"Q4 {row['id']} {row['nameCh']} area {int(areas[i])}"
            for i, row in enumerate(output["provinceRecords"]) if not min_area <= int(areas[i]) <= max_area]


def geometry_digest(document: dict) -> str:
    """배열 순서에 무관한 기하 지문: owner(省 id 로 풀어서)·省 id 순서·城 칸."""
    body = {"owner": document["owner"], "provinces": [row["id"] for row in document["provinceRecords"]],
            "cities": sorted((row["id"], row["col"], row["row"]) for row in document["cities"]),
            "jurisdictions": sorted((row["id"], row["provinceIds"]) for row in document["jurisdictionRecords"])}
    return hashlib.sha256(json.dumps(body, ensure_ascii=False, sort_keys=True).encode()).hexdigest()


def dumps(document: dict) -> str:
    return json.dumps(document, ensure_ascii=False, separators=(",", ":")) + "\n"


def stage_input(committed: dict) -> dict:
    """커밋본에서 저지 지형·접기·거점 분할을 벗겨 ★ 가 설 자리의 문서를 만든다 (읽기 전용)."""
    from tools.map import carve_strategic_site_provinces as carving
    from tools.map import fold_cityless_jurisdictions as folding
    document, folded = folding.peel(committed)
    if folded is None:
        raise ValueError("fold stage does not match the committed tiles")
    document, carved = carving.peel(document)
    if carved is None:
        raise ValueError("carve stage does not match the committed tiles")
    return document


def _refuse_committed_path(path: Path) -> None:
    resolved = path.resolve()
    try:
        relative = resolved.relative_to(ROOT)
    except ValueError:
        return
    if relative.parts and relative.parts[0] in FORBIDDEN_OUTPUT_ROOTS:
        raise SystemExit(f"refusing to write under {relative.parts[0]}/ — S1 은 스크래치 전용이다: {resolved}")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--source", type=Path, default=TILES)
    parser.add_argument("--source-is-stage-input", action="store_true",
                        help="--source 가 이미 거점 분할 앞 문서다 (벗기지 않는다)")
    parser.add_argument("--output", type=Path, required=True, help="스크래치 경로 (data/·infra/ 밑은 거부)")
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()
    _refuse_committed_path(args.output)
    if args.report:
        _refuse_committed_path(args.report)
    source = json.loads(args.source.read_text(encoding="utf-8"))
    if not args.source_is_stage_input:
        source = stage_input(source)
    document, report = partition(source)
    problems = check_parent_unchanged(source, document) + check_cover(source, document, report)
    report["gates"] = {"Q2": not any(p.startswith("Q2") for p in problems),
                       "Q3": not any(p.startswith("Q3") for p in problems),
                       "Q4violations": len(check_area(document)),
                       "geometryDigest": geometry_digest(document)}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(dumps(document), encoding="utf-8")
    report_path = args.report or args.output.with_suffix(".report.json")
    report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({**report["counts"], **report["gates"],
                      "seedExceptions": len(report["seedExceptions"]),
                      "minAreaBorrowed": len(report["minAreaBorrowed"]),
                      "subdivided": len(report["subdivisions"]),
                      "retiredProvinceIds": len(report["retiredProvinceIds"])}, ensure_ascii=False, indent=1))
    for problem in problems:
        print(problem, file=sys.stderr)
    return 1 if problems else 0


if __name__ == "__main__":
    raise SystemExit(main())
