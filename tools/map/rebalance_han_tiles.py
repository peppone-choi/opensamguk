#!/usr/bin/env python3
"""Re-materialize the tracked Han tile SSoT with balanced province areas."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
import sys

import numpy as np

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))

from tools.map.build_terrain_grid import OUT_OF_SCOPE, Proj, adjacency
from tools.map.non_playable_regions import apply_non_playable_regions, load_non_playable_policy
from tools.map.province_quality import measure_province_shapes
from tools.map.world_province_geometry import (
    ParentRegionRecord,
    ProvinceAudit,
    ProvinceBuildResult,
    ProvinceRecord,
    ProvinceSeed,
    rebalance_province_areas,
)


TILES = ROOT / "data" / "map" / "han-tiles.json"
NON_PLAYABLE_REGIONS = ROOT / "data" / "curated" / "map" / "non-playable-regions-v1.json"


def expand_rle(runs: list[list[int]], rows: int, cols: int) -> np.ndarray:
    values = np.empty(rows * cols, dtype=np.int32)
    offset = 0
    for value, count in runs:
        values[offset:offset + count] = value
        offset += count
    if offset != len(values):
        raise ValueError("tile RLE does not cover the canonical grid")
    return values.reshape(rows, cols)


def encode_rle(values: np.ndarray) -> list[list[int]]:
    runs: list[list[int]] = []
    for value in values.ravel():
        number = int(value)
        if runs and runs[-1][0] == number:
            runs[-1][1] += 1
        else:
            runs.append([number, 1])
    return runs


def rebalance_document(document: dict) -> tuple[dict, tuple]:
    rows = document["_meta"]["rows"]
    cols = document["_meta"]["cols"]
    owner = expand_rle(document["owner"], rows, cols)
    parent_owner = expand_rle(document["parentOwner"], rows, cols)
    records = tuple(ProvinceRecord(
        id=row["id"], display_name=row["displayName"], name_ch=row.get("nameCh", ""),
        administrative_system=row["administrativeSystem"], kind=row["kind"],
        parent_region_id=row["parentRegionId"], city_index=row.get("cityIndex"),
        geometry_basis=row["geometryBasis"], confidence=row["confidence"],
    ) for row in document["provinceRecords"])
    parents = tuple(ParentRegionRecord(
        id=row["id"], display_name=row["displayName"], name_ch=row.get("nameCh", ""),
        administrative_system=row["administrativeSystem"],
    ) for row in document["parentRegions"])
    evidence_cells = {row.id: [] for row in parents}
    for record in records:
        if record.city_index is None:
            continue
        city = document["cities"][record.city_index]
        evidence_cells[record.parent_region_id].append(
            (int(city["row"]), int(city["col"]))
        )
    for parent, jun in zip(parents, document["juns"]):
        city = document["cities"][jun["seat"]]
        evidence_cells[parent.id].append((int(city["row"]), int(city["col"])))
    terrain = np.array(
        [[int(value) for value in row] for row in document["terrain"]],
        dtype=np.int8,
    )
    seeds = []
    for record in records:
        if record.kind == "DIRECT_TERRITORY":
            continue
        if record.city_index is None:
            raise ValueError(f"historical province {record.id} has no city index")
        city = document["cities"][record.city_index]
        seeds.append(ProvinceSeed(
            id=record.id, display_name=record.display_name, name_ch=record.name_ch,
            administrative_system=record.administrative_system, kind=record.kind,
            parent_region_id=record.parent_region_id,
            row=int(city["row"]), col=int(city["col"]), city_index=record.city_index,
            geometry_basis=record.geometry_basis, confidence=record.confidence,
        ))
    initial = ProvinceBuildResult(owner, records, parents, ProvinceAudit(()))
    full_result, full_parents = rebalance_province_areas(
        initial, seeds, parent_owner,
        total_provinces=1524, minimum_area=8, maximum_area=620,
    )
    excluded = apply_non_playable_regions(
        terrain,
        full_parents,
        [row.id for row in parents],
        Proj(document["_meta"]["projection"]),
        load_non_playable_policy(NON_PLAYABLE_REGIONS),
        out_of_scope_value=OUT_OF_SCOPE,
        evidence_cells_by_parent=evidence_cells,
        province_owner=full_result.owner,
    )
    full_result.owner[excluded] = -1
    result, balanced_parents = rebalance_province_areas(
        full_result, seeds, full_parents,
        total_provinces=1524, minimum_area=8, maximum_area=620,
    )
    updated = dict(document)
    updated["terrain"] = ["".join(str(int(value)) for value in row) for row in terrain]
    updated["owner"] = encode_rle(result.owner)
    updated["parentOwner"] = encode_rle(balanced_parents)
    updated["provinceRecords"] = [{
        "id": row.id, "displayName": row.display_name, "nameCh": row.name_ch,
        "administrativeSystem": row.administrative_system, "kind": row.kind,
        "parentRegionId": row.parent_region_id, "cityIndex": row.city_index,
        "geometryBasis": row.geometry_basis, "confidence": row.confidence,
    } for row in result.province_records]
    updated["adjacency"] = dict(document["adjacency"])
    updated["adjacency"]["county"] = adjacency(result.owner, min_shared_edges=1)
    updated["_meta"] = dict(document["_meta"])
    updated["_meta"]["terrainLegend"] = dict(document["_meta"]["terrainLegend"])
    updated["_meta"]["terrainLegend"][str(OUT_OF_SCOPE)] = "OUT_OF_SCOPE"
    updated["_meta"]["ownerEncoding"] = (
        "run-length [[provinceIndex, count], …] · row-major · -1 = 바다·비플레이 영역"
    )
    updated["_meta"]["counts"] = dict(document["_meta"]["counts"])
    updated["_meta"]["counts"].update(
        adjCounty=len(updated["adjacency"]["county"]), provinces=len(result.province_records),
    )
    quality = measure_province_shapes(result.owner, result.province_records)
    return updated, quality.metrics


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true")
    parser.add_argument("--source", type=Path, default=TILES)
    args = parser.parse_args()
    original = json.loads(args.source.read_text(encoding="utf-8"))
    updated, metrics = rebalance_document(original)
    blob = json.dumps(updated, ensure_ascii=False, separators=(",", ":"))
    if args.check:
        tracked = json.loads(TILES.read_text(encoding="utf-8"))
        if tracked == updated:
            return 0
        differing = sorted(key for key in set(tracked) | set(updated) if tracked.get(key) != updated.get(key))
        print(f"canonical Han tiles would change top-level keys: {', '.join(differing)}", file=sys.stderr)
        return 1
    TILES.write_text(blob, encoding="utf-8")
    areas = [metric.area for metric in metrics]
    print(
        f"balanced {len(areas)} provinces: min={min(areas)} "
        f"median={int(np.median(areas))} max={max(areas)}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
