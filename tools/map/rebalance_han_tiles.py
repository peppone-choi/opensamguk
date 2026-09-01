#!/usr/bin/env python3
"""Re-materialize the tracked Han tile SSoT with balanced province areas."""

from __future__ import annotations

import argparse
import copy
import json
from pathlib import Path
import sys

import numpy as np

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))

from tools.map.build_terrain_grid import OUT_OF_SCOPE, Proj, derive_world_adjacency
from tools.map.non_playable_regions import apply_non_playable_regions, load_non_playable_policy
from tools.map.province_quality import balanced_parent_labels, measure_province_shapes
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


def jun_seat_coordinates(document: dict, cities: list[dict]) -> list[tuple[int, int]]:
    """Return commandery path endpoints from the adjusted seat-city SSoT."""
    return [
        (int(cities[jun["seat"]]["col"]), int(cities[jun["seat"]]["row"]))
        for jun in document["juns"]
    ]


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


def adapt_historical_city_seeds(
    cities: list[dict], records: list[dict], parent_owner: np.ndarray,
    *, minimum_area: int = 1,
) -> list[dict]:
    """Place historical seats in distinct balanced cells of their own parent."""
    adapted = copy.deepcopy(cities)
    records_by_parent: dict[int, list[dict]] = {}
    for record in records:
        if record.get("kind") == "DIRECT_TERRITORY" or record.get("cityIndex") is None:
            continue
        parent_index = int(record["parentRegionId"].removeprefix("PARENT-"))
        records_by_parent.setdefault(parent_index, []).append(record)

    for parent_index, parent_records in sorted(records_by_parent.items()):
        parent_records.sort(key=lambda row: row["id"])
        mask = parent_owner == parent_index
        if int(np.count_nonzero(mask)) < len(parent_records):
            raise ValueError(f"historical city seeds exceed parent cells: PARENT-{parent_index:04d}")
        labels, anchors = balanced_parent_labels(
            mask, len(parent_records), minimum_anchor_area=minimum_area,
        )
        cells_by_label = []
        for label, anchor in enumerate(anchors):
            pending = [anchor]
            seen = {anchor}
            while pending:
                row, col = pending.pop()
                for next_row, next_col in (
                    (row - 1, col), (row + 1, col), (row, col - 1), (row, col + 1),
                ):
                    candidate = (next_row, next_col)
                    if (0 <= next_row < labels.shape[0]
                            and 0 <= next_col < labels.shape[1]
                            and candidate not in seen
                            and int(labels[next_row, next_col]) == label):
                        seen.add(candidate)
                        pending.append(candidate)
            cells_by_label.append(np.asarray(sorted(seen), dtype=np.int32))
        distances: dict[str, list[int]] = {}
        for record in parent_records:
            city = adapted[record["cityIndex"]]
            row, col = int(city["row"]), int(city["col"])
            distances[record["id"]] = [
                int(np.min((cells[:, 0] - row) ** 2 + (cells[:, 1] - col) ** 2))
                for cells in cells_by_label
            ]

        def match_with_limit(limit: int) -> dict[str, int] | None:
            label_to_record: dict[int, str] = {}

            def place(record_id: str, seen: set[int]) -> bool:
                for _, label in sorted(
                    (distance, label)
                    for label, distance in enumerate(distances[record_id])
                    if distance <= limit
                ):
                    if label in seen:
                        continue
                    seen.add(label)
                    previous = label_to_record.get(label)
                    if previous is None or place(previous, seen):
                        label_to_record[label] = record_id
                        return True
                return False

            for record in parent_records:
                if not place(record["id"], set()):
                    return None
            return {record_id: label for label, record_id in label_to_record.items()}

        limits = sorted({distance for values in distances.values() for distance in values})
        low, high = 0, len(limits) - 1
        assignment = None
        while low <= high:
            middle = (low + high) // 2
            candidate = match_with_limit(limits[middle])
            if candidate is None:
                low = middle + 1
            else:
                assignment = candidate
                high = middle - 1
        if assignment is None:
            raise ValueError(f"cannot distribute historical city seeds: PARENT-{parent_index:04d}")

        for record in parent_records:
            city = adapted[record["cityIndex"]]
            original_row, original_col = int(city["row"]), int(city["col"])
            cells = cells_by_label[assignment[record["id"]]]
            index = int(np.argmin(
                (cells[:, 0] - original_row) ** 2 + (cells[:, 1] - original_col) ** 2
            ))
            city["row"], city["col"] = map(int, cells[index])
    return adapted


def rebalance_document(document: dict) -> tuple[dict, tuple]:
    rows = document["_meta"]["rows"]
    cols = document["_meta"]["cols"]
    owner = expand_rle(document["owner"], rows, cols)
    parent_owner = expand_rle(document["parentOwner"], rows, cols)
    record_rows = document["provinceRecords"]
    cities = adapt_historical_city_seeds(
        document["cities"], record_rows, parent_owner, minimum_area=8,
    )
    records = tuple(ProvinceRecord(
        id=row["id"], display_name=row["displayName"], name_ch=row.get("nameCh", ""),
        administrative_system=row["administrativeSystem"], kind=row["kind"],
        parent_region_id=row["parentRegionId"], city_index=row.get("cityIndex"),
        geometry_basis=row["geometryBasis"], confidence=row["confidence"],
    ) for row in record_rows)
    parents = tuple(ParentRegionRecord(
        id=row["id"], display_name=row["displayName"], name_ch=row.get("nameCh", ""),
        administrative_system=row["administrativeSystem"],
    ) for row in document["parentRegions"])
    evidence_cells = {row.id: [] for row in parents}
    for record in records:
        if record.city_index is None:
            continue
        city = cities[record.city_index]
        evidence_cells[record.parent_region_id].append(
            (int(city["row"]), int(city["col"]))
        )
    for parent, jun in zip(parents, document["juns"]):
        city = cities[jun["seat"]]
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
        city = cities[record.city_index]
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
    updated["cities"] = cities
    updated["owner"] = encode_rle(result.owner)
    updated["parentOwner"] = encode_rle(balanced_parents)
    updated["provinceRecords"] = [{
        "id": row.id, "displayName": row.display_name, "nameCh": row.name_ch,
        "administrativeSystem": row.administrative_system, "kind": row.kind,
        "parentRegionId": row.parent_region_id, "cityIndex": row.city_index,
        "geometryBasis": row.geometry_basis, "confidence": row.confidence,
    } for row in result.province_records]
    county_adjacency, commandery_adjacency = derive_world_adjacency(
        terrain,
        jun_seat_coordinates(document, cities),
        result.owner,
        balanced_parents,
    )
    updated["adjacency"] = {
        "county": county_adjacency,
        "commandery": commandery_adjacency,
    }
    updated["_meta"] = dict(document["_meta"])
    updated["_meta"]["terrainLegend"] = dict(document["_meta"]["terrainLegend"])
    updated["_meta"]["terrainLegend"][str(OUT_OF_SCOPE)] = "OUT_OF_SCOPE"
    updated["_meta"]["ownerEncoding"] = (
        "run-length [[provinceIndex, count], …] · row-major · -1 = 바다·비플레이 영역"
    )
    updated["_meta"]["counts"] = dict(document["_meta"]["counts"])
    updated["_meta"]["counts"].update(
        adjCounty=len(updated["adjacency"]["county"]),
        adjCommandery=len(updated["adjacency"]["commandery"]),
        provinces=len(result.province_records),
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
