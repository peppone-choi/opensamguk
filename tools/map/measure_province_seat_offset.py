#!/usr/bin/env python3
"""城의 실제 경위도 칸과 제 省 기하 사이의 밀림을 잰다 (읽기 전용, GH #806).

커밋된 data/map/han-tiles.json 만 읽는다. 임계값을 갖지 않는다 — 순위표와 분포만 낸다.

  python3 tools/map/measure_province_seat_offset.py            # 요약
  python3 tools/map/measure_province_seat_offset.py --tsv out  # 전수 TSV
  python3 tools/map/measure_province_seat_offset.py --check    # Q1·Q1b 게이트 (현행 커밋본은 적색이다)

--check 는 아직 CI 에 걸지 않는다. 현행 커밋본이 Q1 480건·Q1b 24건으로 빨갛기 때문이다(계획 §6).
지리 재분할(★)이 han-tiles 에 들어오는 PR 이 이 모드를 차단 단계로 켠다. 예외는 --exceptions 로 받은
원장 행(관할 id)뿐이고, 이 도구는 임계값을 갖지 않는다 — 통과 조건은 「0건」이다.
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
        juris = jurisdiction_of.get(record["id"])
        flat = cells_of.get(index)
        if flat is None or flat.size == 0:
            out.append({"id": record["id"], "nameCh": record["nameCh"], "jurisdictionId": juris, "area": 0})
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
        true_parent = int(parent[irow, icol]) if inside_grid else -2
        out.append({
            "id": record["id"],
            "nameCh": record["nameCh"],
            "jurisdictionId": juris,
            "kind": city["kind"],
            "seat": bool(city.get("seat")),
            "parent": record["parentRegionId"],
            "basis": record.get("geometryBasis"),
            "coordinateBasis": city.get("locationBasis"),
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


def gate(rows: list[dict], exception_ids: frozenset[str] = frozenset()) -> dict[str, list[dict]]:
    """Q1(실제 칸 ∈ 제 관할)·Q1b(실제 칸이 저지면 제 省에 저지 ≥ 1칸) 위반 행. 빈 목록이 통과다.

    exception_ids 는 원장이 사유를 적은 관할 id 다(물·격자 밖 / 사료 郡 ≠ 래스터 郡 / 씨앗 충돌).
    예외는 Q1 만 면제한다 — Q1b 는 省 안 지형을 보므로 씨앗이 옮겨져도 그대로 잰다.
    """
    measured = [r for r in rows if r.get("area")]
    landless = [r for r in rows if not r.get("area")]  # 칸이 하나도 없는 城 있는 省 — 제자리일 수 없다
    return {
        "Q1": landless + [r for r in measured
                          if not r["trueCellInJurisdiction"] and r["jurisdictionId"] not in exception_ids],
        # A synthetic commandery point has no historical terrain claim. Its
        # projected cell is a placement witness, not evidence of a lowland.
        "Q1b": [r for r in measured if r.get("coordinateBasis") != "SYNTHETIC_COMMANDERY_CELL"
                and r["terrainAtTrue"] in ("PLAIN", "BASIN") and r["lowlandCells"] == 0],
    }


def load_exception_ids(*paths: Path) -> frozenset[str]:
    """예외 원장들의 관할 id. 읽는 꼴은 셋이다:
      * ★ 원장·분할기 보고서의 `seedExceptions`(물·격자 밖 / 사료 郡 ≠ 래스터 郡 / 씨앗 충돌)
      * 일반 원장의 `rows`
      * 거점 분할 원장(`strategic-site-province-carves-v1`)에서 `displacedFrom` 이 적힌 placements — carve 규칙 5 가
        앵커를 옮긴 거점이다(城 점이 선 칸·기증 省이 갈라지는 칸·물). 옮긴 사유와 거리는 그 원장 행에 있다.
        같은 원장의 `gapCountyPlacements` 도 같게 읽는다 — 그 단계가 결손 縣 省도 떼어 내고(2026-09-23),
        郡 경계 기하가 거칠어 투영 칸이 옆 郡에 떨어진 縣은 자기 郡으로 당겼다(PROJECTED_CELL_IN_OTHER_COMMANDERY).
    """
    ids: set[str] = set()
    for path in paths:
        document = json.loads(path.read_text(encoding="utf-8"))
        rows = document.get("seedExceptions", document.get("rows", []))
        ids |= {row["jurisdictionId"] for row in rows}
        for stage in document.get("geometry", {}).get("stages", []):
            for key in ("placements", "gapCountyPlacements"):
                ids |= {row["placeId"] for row in stage.get(key, []) if "displacedFrom" in row}
    return frozenset(ids)


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
    parser.add_argument("--check", action="store_true", help="Q1·Q1b 위반이 있으면 exit 1")
    parser.add_argument("--exceptions", type=Path, nargs="+", default=[],
                        help="Q1 예외 원장들: ★ 원장(seedExceptions)·거점 분할 원장(displacedFrom)")
    args = parser.parse_args()
    every = measure(json.loads(args.tiles.read_text()))
    rows = [r for r in every if r.get("area")]
    if args.check:
        rows = every
        failures = gate(rows, load_exception_ids(*args.exceptions))
        for name, failed in failures.items():
            print(f"{name}: {len(failed)} / {len(rows)}", file=sys.stderr if failed else sys.stdout)
            for r in sorted(failed, key=lambda r: (-r.get("nearestCell", math.inf), r["id"]))[:args.top]:
                print(f"  {name} {r['id']} {r['nameCh']} nearestCell={r.get('nearestCell', 'NO_CELLS')} "
                      f"terrainAtTrue={r.get('terrainAtTrue')} lowlandCells={r.get('lowlandCells')}",
                      file=sys.stderr)
        return 1 if any(failures.values()) else 0
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
