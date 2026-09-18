#!/usr/bin/env python3
"""城의 실제 경위도 칸과 제 省 기하 사이의 밀림을 잰다 (읽기 전용, GH #806).

커밋된 data/map/han-tiles.json 만 읽는다. 임계값을 갖지 않는다 — 순위표와 분포만 낸다.

  python3 tools/map/measure_province_seat_offset.py            # 요약
  python3 tools/map/measure_province_seat_offset.py --tsv out  # 전수 TSV
"""
from __future__ import annotations

import argparse
import json
import math
import sys
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parents[2]
TILES = ROOT / "data" / "map" / "han-tiles.json"
LOWLAND = {1, 7}  # PLAIN, BASIN


def expand_rle(runs, rows: int, cols: int) -> np.ndarray:
    flat = np.empty(rows * cols, dtype=np.int32)
    at = 0
    for value, count in runs:
        flat[at:at + count] = value
        at += count
    if at != rows * cols:
        raise ValueError(f"RLE length {at} != {rows * cols}")
    return flat.reshape(rows, cols)


def project_cell(projection: dict, latitude: float, longitude: float) -> tuple[float, float]:
    """materialize_frontier_counties.project_cell 과 같은 식 (han-tiles col/row 축)."""
    col = (longitude * projection["k"] - projection["x0"] + projection["pad"]) / projection["cell"]
    row = (projection["y1"] + projection["pad"] - latitude) / projection["cell"]
    return col, row


def measure(document: dict) -> list[dict]:
    meta = document["_meta"]
    rows, cols = meta["rows"], meta["cols"]
    owner = expand_rle(document["owner"], rows, cols)
    parent = expand_rle(document["parentOwner"], rows, cols)
    terrain = np.array([[int(ch) for ch in line] for line in document["terrain"]], dtype=np.int8)
    legend = meta["terrainLegend"]
    jurisdiction_of = {}
    for row in document["jurisdictionRecords"]:
        for province_id in row["provinceIds"]:
            jurisdiction_of[province_id] = row["id"]
    index_of = {row["id"]: i for i, row in enumerate(document["provinceRecords"])}
    juris_indices: dict[str, list[int]] = {}
    for province_id, juris in jurisdiction_of.items():
        juris_indices.setdefault(juris, []).append(index_of[province_id])
    cells_of: dict[int, np.ndarray] = {}
    order = np.argsort(owner, axis=None, kind="stable")
    sorted_owner = owner.reshape(-1)[order]
    starts = np.flatnonzero(np.r_[True, sorted_owner[1:] != sorted_owner[:-1]])
    for start, end in zip(starts, np.r_[starts[1:], sorted_owner.size]):
        cells_of[int(sorted_owner[start])] = order[start:end]

    out = []
    for index, record in enumerate(document["provinceRecords"]):
        city_index = record.get("cityIndex")
        if city_index is None:
            continue
        city = document["cities"][city_index]
        if city.get("lon") is None or city.get("lat") is None:
            continue
        flat = cells_of.get(index)
        if flat is None or flat.size == 0:
            out.append({"id": record["id"], "nameCh": record["nameCh"], "area": 0})
            continue
        prow, pcol = np.divmod(flat, cols)
        tcol, trow = project_cell(meta["projection"], city["lat"], city["lon"])
        icol, irow = int(tcol), int(trow)  # 칸 색인은 내림 (materialize_frontier_counties 와 같다)
        inside_grid = 0 <= irow < rows and 0 <= icol < cols
        distance = np.hypot(prow - irow, pcol - icol)
        near = float(distance.min())
        centroid = float(math.hypot(prow.mean() - irow, pcol.mean() - icol))
        seed = float(math.hypot(city["row"] - irow, city["col"] - icol))
        area = int(flat.size)
        radius = math.sqrt(area / math.pi)
        terr = terrain[prow, pcol]
        true_owner = int(owner[irow, icol]) if inside_grid else -2
        juris = jurisdiction_of.get(record["id"])
        true_parent = int(parent[irow, icol]) if inside_grid else -2
        out.append({
            "id": record["id"],
            "nameCh": record["nameCh"],
            "kind": city["kind"],
            "seat": bool(city.get("seat")),
            "parent": record["parentRegionId"],
            "basis": record.get("geometryBasis"),
            "area": area,
            "radius": round(radius, 2),
            "seedOffset": round(seed, 2),
            "nearestCell": round(near, 2),
            "centroidOffset": round(centroid, 2),
            "centroidOverRadius": round(centroid / radius, 2),
            "trueCellInProvince": true_owner == index,
            "trueCellInJurisdiction": true_owner in juris_indices.get(juris, []),
            "trueCellInParent": true_parent == int(record["parentRegionId"].removeprefix("PARENT-")),
            "trueCellOwner": (document["provinceRecords"][true_owner]["nameCh"]
                              if true_owner >= 0 else ("GRID_OUT" if true_owner == -2 else "UNOWNED")),
            "terrainAtTrue": legend[str(int(terrain[irow, icol]))] if inside_grid else "GRID_OUT",
            "terrainAtSeed": legend[str(int(terrain[city["row"], city["col"]]))],
            "lowlandCells": int(np.isin(terr, list(LOWLAND)).sum()),
            "mountainShare": round(float(np.isin(terr, [2, 6, 8]).mean()), 3),
        })
    return out


def percentiles(values, points=(50, 75, 90, 95, 99, 100)):
    array = np.asarray(sorted(values), dtype=float)
    return {p: round(float(np.percentile(array, p)), 2) for p in points}


def histogram(values, edges=(0, 0.5, 1, 2, 3, 4, 6, 8, 10, 12, 15, 20, 30, 40, 60)):
    counts, _ = np.histogram(values, bins=list(edges) + [float("inf")])
    return [(f"{lo}–{hi}", int(n)) for lo, hi, n in zip(edges, list(edges[1:]) + ["∞"], counts)]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tiles", type=Path, default=TILES)
    parser.add_argument("--tsv", type=Path)
    parser.add_argument("--top", type=int, default=40)
    args = parser.parse_args()
    rows = [r for r in measure(json.loads(args.tiles.read_text())) if r.get("area")]
    rows.sort(key=lambda r: (-r["nearestCell"], r["id"]))
    print(f"seat provinces measured: {len(rows)}")
    for key in ("trueCellInProvince", "trueCellInJurisdiction", "trueCellInParent"):
        print(f"  {key}: {sum(r[key] for r in rows)}")
    for key in ("nearestCell", "centroidOffset", "seedOffset", "centroidOverRadius"):
        print(f"  {key} percentiles: {percentiles([r[key] for r in rows])}")
    print("  nearestCell histogram:", histogram([r["nearestCell"] for r in rows]))
    columns = list(rows[0])
    if args.tsv:
        args.tsv.write_text("\n".join(
            ["\t".join(columns)] + ["\t".join(str(r[c]) for c in columns) for r in rows]) + "\n")
    for r in rows[:args.top]:
        print("\t".join(str(r[c]) for c in (
            "nameCh", "id", "parent", "seat", "area", "nearestCell", "centroidOffset",
            "seedOffset", "terrainAtTrue", "terrainAtSeed", "lowlandCells", "mountainShare",
            "trueCellOwner")))
    return 0


if __name__ == "__main__":
    sys.exit(main())
