#!/usr/bin/env python3
"""Apply explicit, evidence-checked repairs to disconnected Han province cells.

This is a narrow patcher over the committed ``han-tiles.json`` artifact.  It
must not invoke the historical map builders or infer replacements for deferred
components.
"""

from __future__ import annotations

import argparse
import copy
import hashlib
import json
import sys
from collections import Counter
from pathlib import Path

import numpy as np


ROOT = Path(__file__).resolve().parents[2]
DEFAULT_TILES = ROOT / "data" / "map" / "han-tiles.json"
DEFAULT_LEDGER = (
    ROOT / "data" / "curated" / "han" / "province-fragment-adjudications-v1.json"
)

sys.path.insert(0, str(ROOT))

from tools.map.build_terrain_grid import derive_world_adjacency  # noqa: E402


def expand_rle(runs: object, rows: int, cols: int) -> list[list[int]]:
    if not isinstance(runs, list):
        raise ValueError("RLE value must be an array")
    values: list[int] = []
    for index, run in enumerate(runs):
        if (
            not isinstance(run, list)
            or len(run) != 2
            or type(run[0]) is not int
            or type(run[1]) is not int
            or run[1] <= 0
        ):
            raise ValueError(f"RLE run {index} must be [integer, positive count]")
        values.extend([run[0]] * run[1])
    if len(values) != rows * cols:
        raise ValueError("RLE length does not match rows * cols")
    return [values[offset:offset + cols] for offset in range(0, len(values), cols)]


def encode_rle(grid: list[list[int]]) -> list[list[int]]:
    runs: list[list[int]] = []
    for row in grid:
        for value in row:
            number = int(value)
            if runs and runs[-1][0] == number:
                runs[-1][1] += 1
            else:
                runs.append([number, 1])
    return runs


def _grid_digest(grid: list[list[int]]) -> str:
    return hashlib.sha256(
        json.dumps(grid, separators=(",", ":")).encode("utf-8")
    ).hexdigest()


def _json_digest(value: object) -> str:
    return hashlib.sha256(
        json.dumps(value, separators=(",", ":")).encode("utf-8")
    ).hexdigest()


def _require_unique_records(document: dict, key: str) -> tuple[list[dict], dict[str, int]]:
    records = document.get(key)
    if not isinstance(records, list) or not all(isinstance(row, dict) for row in records):
        raise ValueError(f"{key} must be an array of objects")
    ids = [row.get("id") for row in records]
    if any(not isinstance(record_id, str) or not record_id for record_id in ids):
        raise ValueError(f"{key} IDs must be nonempty strings")
    if len(set(ids)) != len(ids):
        raise ValueError(f"{key} contains duplicate IDs")
    return records, {record_id: index for index, record_id in enumerate(ids)}


def _terrain_grid(document: dict, rows: int, cols: int) -> tuple[list[list[int]], dict[int, str]]:
    raw_rows = document.get("terrain")
    legend = document.get("_meta", {}).get("terrainLegend")
    if not isinstance(raw_rows, list) or len(raw_rows) != rows:
        raise ValueError("terrain must match canonical rows")
    if not isinstance(legend, dict):
        raise ValueError("terrainLegend must be an object")
    try:
        decoded_legend = {int(key): value for key, value in legend.items()}
    except (TypeError, ValueError) as error:
        raise ValueError("terrainLegend keys must be integers") from error
    terrain: list[list[int]] = []
    for index, raw_row in enumerate(raw_rows):
        if not isinstance(raw_row, str) or len(raw_row) != cols:
            raise ValueError(f"terrain[{index}] must contain exactly {cols} cells")
        row = [int(value) for value in raw_row]
        if any(value not in decoded_legend for value in row):
            raise ValueError(f"terrain[{index}] references an unknown class")
        terrain.append(row)
    return terrain, decoded_legend


def _component(
    owner: list[list[int]], source_index: int, start: tuple[int, int]
) -> set[tuple[int, int]]:
    rows, cols = len(owner), len(owner[0])
    row, col = start
    if not (0 <= row < rows and 0 <= col < cols) or owner[row][col] != source_index:
        return set()
    pending = [start]
    seen = {start}
    while pending:
        current_row, current_col = pending.pop()
        for delta_row, delta_col in ((-1, 0), (1, 0), (0, -1), (0, 1)):
            next_row = current_row + delta_row
            next_col = current_col + delta_col
            coordinate = (next_row, next_col)
            if (
                0 <= next_row < rows
                and 0 <= next_col < cols
                and coordinate not in seen
                and owner[next_row][next_col] == source_index
            ):
                seen.add(coordinate)
                pending.append(coordinate)
    return seen


def _components_for(owner: list[list[int]], province_index: int) -> list[set[tuple[int, int]]]:
    cells = {
        (row, col)
        for row, values in enumerate(owner)
        for col, value in enumerate(values)
        if value == province_index
    }
    components: list[set[tuple[int, int]]] = []
    while cells:
        component = _component(owner, province_index, min(cells))
        components.append(component)
        cells.difference_update(component)
    return sorted(components, key=lambda component: (-len(component), min(component)))


def _cell_set_digest(components: list[set[tuple[int, int]]]) -> str:
    cells = sorted([col, row] for component in components for row, col in component)
    return hashlib.sha256(
        json.dumps(cells, separators=(",", ":")).encode("utf-8")
    ).hexdigest()


def _deferred_evidence(
    *,
    components: list[set[tuple[int, int]]],
    owner: list[list[int]],
    terrain: list[list[int]],
    terrain_legend: dict[int, str],
    province_ids: list[str],
    province_index: int,
    place_anchors: list[tuple[tuple[int, int], str]],
) -> dict:
    rows, cols = len(owner), len(owner[0])
    secondary_cells = set().union(*components[1:])
    negative_types: set[str] = set()
    surrounding: set[int] = set()
    for row, col in secondary_cells:
        for delta_row, delta_col in ((-1, 0), (1, 0), (0, -1), (0, 1)):
            next_row = row + delta_row
            next_col = col + delta_col
            if not (0 <= next_row < rows and 0 <= next_col < cols):
                negative_types.add("OUT_OF_MAP")
                continue
            neighbor = owner[next_row][next_col]
            if neighbor < 0:
                negative_types.add(terrain_legend[terrain[next_row][next_col]])
            elif neighbor != province_index:
                surrounding.add(neighbor)
    contained_place_ids = sorted(
        place_id for coordinate, place_id in place_anchors if coordinate in secondary_cells
    )
    if contained_place_ids:
        classification = "ANCHOR_CONTAINING_REVIEW_REQUIRED"
    elif "SEA" in negative_types or "OUT_OF_MAP" in negative_types:
        classification = "MARITIME_REVIEW_REQUIRED"
    elif "LAKE" in negative_types:
        classification = "LACUSTRINE_REVIEW_REQUIRED"
    else:
        classification = "INLAND_MULTI_NEIGHBOR_REVIEW_REQUIRED"
    return {
        "classification": classification,
        "secondaryNegativeBoundaryTypes": sorted(negative_types),
        "secondarySurroundingProvinceIds": sorted(
            province_ids[index] for index in surrounding
        ),
        "containedRuntimePlaceIds": contained_place_ids,
    }


def _component_evidence(
    *,
    component: set[tuple[int, int]],
    owner: list[list[int]],
    terrain: list[list[int]],
    terrain_legend: dict[int, str],
    province_ids: list[str],
    source_seat: tuple[int, int] | None,
) -> dict:
    rows, cols = len(owner), len(owner[0])
    surrounding: set[int] = set()
    touches_negative = False
    for row, col in component:
        for delta_row, delta_col in ((-1, 0), (1, 0), (0, -1), (0, 1)):
            next_row = row + delta_row
            next_col = col + delta_col
            if not (0 <= next_row < rows and 0 <= next_col < cols):
                touches_negative = True
                continue
            neighbor = owner[next_row][next_col]
            if neighbor < 0:
                touches_negative = True
            elif (next_row, next_col) not in component:
                surrounding.add(neighbor)
    return {
        "componentCellCount": len(component),
        "terrainClasses": sorted({terrain_legend[terrain[row][col]] for row, col in component}),
        "surroundingProvinceIds": sorted(province_ids[index] for index in surrounding),
        "containsSourceSeat": source_seat in component if source_seat is not None else False,
        "touchesNegative": touches_negative,
    }


def _validate_ledger(ledger: dict) -> list[dict]:
    if ledger.get("contractId") != "han-province-fragment-adjudications-v1":
        raise ValueError("unexpected fragment adjudication contractId")
    minimum = ledger.get("minimumProvinceArea")
    if type(minimum) is not int or minimum <= 0:
        raise ValueError("minimumProvinceArea must be a positive integer")
    for field in ("inputOwnerSha256", "outputOwnerSha256", "seatOwnerSha256"):
        value = ledger.get(field)
        if (
            not isinstance(value, str)
            or len(value) != 64
            or any(character not in "0123456789abcdef" for character in value)
        ):
            raise ValueError(f"{field} must be a lowercase SHA-256 digest")
    decisions = ledger.get("reassignments")
    deferred = ledger.get("deferred")
    if not isinstance(decisions, list) or not all(isinstance(row, dict) for row in decisions):
        raise ValueError("reassignments must be an array of objects")
    if not isinstance(deferred, list) or not all(isinstance(row, dict) for row in deferred):
        raise ValueError("deferred must be an array of objects")
    allowed_deferred = {
        "ANCHOR_CONTAINING_REVIEW_REQUIRED",
        "MARITIME_REVIEW_REQUIRED",
        "LACUSTRINE_REVIEW_REQUIRED",
        "INLAND_MULTI_NEIGHBOR_REVIEW_REQUIRED",
    }
    deferred_ids: set[str] = set()
    for index, row in enumerate(deferred):
        province_id = row.get("provinceId")
        if not isinstance(province_id, str) or not province_id:
            raise ValueError(f"deferred[{index}].provinceId must be a nonempty string")
        if province_id in deferred_ids:
            raise ValueError(f"deferred contains duplicate provinceId {province_id}")
        deferred_ids.add(province_id)
        if row.get("classification") not in allowed_deferred:
            raise ValueError(f"deferred[{index}] has an unsupported classification")
        counts = row.get("componentCellCounts")
        if (
            not isinstance(counts, list)
            or len(counts) < 2
            or any(type(value) is not int or value <= 0 for value in counts)
            or counts != sorted(counts, reverse=True)
        ):
            raise ValueError(f"deferred[{index}].componentCellCounts must be descending")
        digest = row.get("cellSetSha256")
        if (
            not isinstance(digest, str)
            or len(digest) != 64
            or any(character not in "0123456789abcdef" for character in digest)
        ):
            raise ValueError(f"deferred[{index}].cellSetSha256 must be a SHA-256 digest")
        if not isinstance(row.get("reason"), str) or not row["reason"].strip():
            raise ValueError(f"deferred[{index}].reason must be nonempty")
    seen_cells: set[tuple[int, int]] = set()
    for index, decision in enumerate(decisions):
        if decision.get("classification") != "INLAND_FULLY_ENCLOSED_FRAGMENT":
            raise ValueError(f"reassignments[{index}] has an unsupported classification")
        cells = decision.get("cells")
        if (
            not isinstance(cells, list)
            or not cells
            or any(
                not isinstance(cell, list)
                or len(cell) != 2
                or any(type(value) is not int for value in cell)
                for cell in cells
            )
        ):
            raise ValueError(f"reassignments[{index}].cells must be nonempty [col, row] pairs")
        coordinates = {(cell[1], cell[0]) for cell in cells}
        if len(coordinates) != len(cells):
            raise ValueError(f"reassignments[{index}].cells contains duplicates")
        overlap = seen_cells & coordinates
        if overlap:
            raise ValueError(f"reassignments[{index}].cells overlaps another decision")
        seen_cells.update(coordinates)
        if not isinstance(decision.get("evidence"), dict):
            raise ValueError(f"reassignments[{index}].evidence must be an object")
    return decisions


def _seat_for(record: dict, cities: list[dict]) -> tuple[int, int] | None:
    city_index = record.get("cityIndex")
    if city_index is None:
        return None
    if type(city_index) is not int or not 0 <= city_index < len(cities):
        raise ValueError(f"province {record.get('id')} has an invalid cityIndex")
    city = cities[city_index]
    row, col = city.get("row"), city.get("col")
    if type(row) is not int or type(col) is not int:
        raise ValueError(f"province {record.get('id')} city has invalid coordinates")
    return row, col


def _jun_seat_coordinates(document: dict) -> list[tuple[int, int]]:
    cities = document.get("cities")
    juns = document.get("juns")
    if not isinstance(cities, list) or not isinstance(juns, list):
        raise ValueError("cities and juns must be arrays")
    result: list[tuple[int, int]] = []
    for index, jun in enumerate(juns):
        if not isinstance(jun, dict):
            raise ValueError(f"juns[{index}] must be an object")
        seat = jun.get("seat")
        if type(seat) is not int or not 0 <= seat < len(cities):
            raise ValueError(f"juns[{index}].seat is invalid")
        city = cities[seat]
        result.append((int(city["col"]), int(city["row"])))
    return result


def materialize_document(document: dict, ledger: dict) -> dict:
    """Return an idempotently patched copy after validating every decision."""
    updated = copy.deepcopy(document)
    meta = updated.get("_meta")
    if not isinstance(meta, dict):
        raise ValueError("han tiles must contain _meta")
    rows, cols = meta.get("rows"), meta.get("cols")
    if type(rows) is not int or type(cols) is not int or rows <= 0 or cols <= 0:
        raise ValueError("rows and cols must be positive integers")
    decisions = _validate_ledger(ledger)
    provinces, province_index_by_id = _require_unique_records(updated, "provinceRecords")
    parents, parent_index_by_id = _require_unique_records(updated, "parentRegions")
    province_ids = [row["id"] for row in provinces]
    cities = updated.get("cities")
    if not isinstance(cities, list) or not all(isinstance(row, dict) for row in cities):
        raise ValueError("cities must be an array of objects")
    place_anchors: list[tuple[tuple[int, int], str]] = []
    for index, city in enumerate(cities):
        row, col = city.get("row"), city.get("col")
        if type(row) is not int or type(col) is not int:
            raise ValueError(f"cities[{index}] has invalid coordinates")
        place_id = city.get("id")
        if not isinstance(place_id, str) or not place_id:
            raise ValueError(f"cities[{index}] has an invalid ID")
        place_anchors.append(((row, col), place_id))
    owner = expand_rle(updated.get("owner"), rows, cols)
    parent_owner = expand_rle(updated.get("parentOwner"), rows, cols)
    terrain, terrain_legend = _terrain_grid(updated, rows, cols)
    if _json_digest(updated.get("seatOwner")) != ledger["seatOwnerSha256"]:
        raise ValueError("seatOwner digest disagrees with the adjudication ledger")
    owner_digest = _grid_digest(owner)
    if owner_digest == ledger["inputOwnerSha256"]:
        state = "input"
    elif owner_digest == ledger["outputOwnerSha256"]:
        state = "output"
    else:
        raise ValueError("owner grid is neither the adjudicated input nor output state")

    parent_index_by_province: list[int] = []
    for province in provinces:
        parent_id = province.get("parentRegionId")
        if parent_id not in parent_index_by_id:
            raise ValueError(f"province {province.get('id')} has an unknown parentRegionId")
        parent_index_by_province.append(parent_index_by_id[parent_id])

    pending: list[tuple[dict, int, int, set[tuple[int, int]]]] = []
    for decision in decisions:
        source_id = decision.get("sourceProvinceId")
        target_id = decision.get("targetProvinceId")
        if source_id not in province_index_by_id or target_id not in province_index_by_id:
            raise ValueError("fragment decision references an unknown province")
        source_index = province_index_by_id[source_id]
        target_index = province_index_by_id[target_id]
        coordinates = {(cell[1], cell[0]) for cell in decision["cells"]}
        if any(not (0 <= row < rows and 0 <= col < cols) for row, col in coordinates):
            raise ValueError("fragment decision contains an out-of-range cell")
        values = {owner[row][col] for row, col in coordinates}
        if state == "output" and values == {target_index}:
            target_parent = parent_index_by_province[target_index]
            if any(parent_owner[row][col] != target_parent for row, col in coordinates):
                raise ValueError("parentOwner disagrees with the adjudicated target province")
            continue
        if values != {source_index}:
            raise ValueError("fragment cells do not identify the exact source component")
        start = min(coordinates)
        component = _component(owner, source_index, start)
        if component != coordinates:
            raise ValueError("fragment cells do not identify the exact source component")
        source_parent = parent_index_by_province[source_index]
        if any(parent_owner[row][col] != source_parent for row, col in component):
            raise ValueError("parentOwner disagrees with the source province before patch")
        source_seat = _seat_for(provinces[source_index], cities)
        actual_evidence = _component_evidence(
            component=component,
            owner=owner,
            terrain=terrain,
            terrain_legend=terrain_legend,
            province_ids=province_ids,
            source_seat=source_seat,
        )
        if actual_evidence["containsSourceSeat"]:
            raise ValueError("fragment component contains the source seat")
        contained_place_ids = sorted(
            place_id for coordinate, place_id in place_anchors if coordinate in component
        )
        if contained_place_ids:
            raise ValueError(
                f"fragment component contains a runtime place anchor: {contained_place_ids}"
            )
        if actual_evidence["touchesNegative"]:
            raise ValueError("fragment component touches negative terrain")
        if actual_evidence["surroundingProvinceIds"] != [target_id]:
            raise ValueError("fragment component is not surrounded by a single target province")
        if decision["evidence"] != actual_evidence:
            raise ValueError(
                f"fragment evidence drift for {source_id}: "
                f"expected {decision['evidence']!r}, got {actual_evidence!r}"
            )
        pending.append((decision, source_index, target_index, component))

    for _decision, _source_index, target_index, component in pending:
        target_parent = parent_index_by_province[target_index]
        for row, col in component:
            owner[row][col] = target_index
            parent_owner[row][col] = target_parent

    if _grid_digest(owner) != ledger["outputOwnerSha256"]:
        raise ValueError("materialized owner grid disagrees with outputOwnerSha256")

    areas = Counter(value for row in owner for value in row if value >= 0)
    minimum = ledger["minimumProvinceArea"]
    under_minimum = [
        provinces[index]["id"]
        for index in range(len(provinces))
        if areas[index] < minimum
    ]
    if under_minimum:
        raise ValueError(f"fragment patch violates minimum province area: {under_minimum[:5]}")

    actual_deferred: dict[str, list[set[tuple[int, int]]]] = {}
    for index, province in enumerate(provinces):
        components = _components_for(owner, index)
        if len(components) > 1:
            actual_deferred[province["id"]] = components
    ledger_deferred = {row["provinceId"]: row for row in ledger["deferred"]}
    if set(actual_deferred) != set(ledger_deferred):
        missing = sorted(set(actual_deferred) - set(ledger_deferred))
        extra = sorted(set(ledger_deferred) - set(actual_deferred))
        raise ValueError(
            f"deferred ledger does not match disconnected provinces: missing={missing}, extra={extra}"
        )
    for province_id, components in actual_deferred.items():
        deferred = ledger_deferred[province_id]
        province_index = province_index_by_id[province_id]
        actual_counts = [len(component) for component in components]
        if deferred["componentCellCounts"] != actual_counts:
            raise ValueError(f"deferred component counts drift for {province_id}")
        if deferred["cellSetSha256"] != _cell_set_digest(components):
            raise ValueError(f"deferred cell fingerprint drift for {province_id}")
        actual_evidence = _deferred_evidence(
            components=components,
            owner=owner,
            terrain=terrain,
            terrain_legend=terrain_legend,
            province_ids=province_ids,
            province_index=province_index,
            place_anchors=place_anchors,
        )
        if deferred["classification"] != actual_evidence["classification"]:
            raise ValueError(
                f"deferred classification drift for {province_id}: "
                f"expected {deferred['classification']}, "
                f"got {actual_evidence['classification']}"
            )
        for field in (
            "secondaryNegativeBoundaryTypes",
            "secondarySurroundingProvinceIds",
        ):
            if deferred.get(field) != actual_evidence[field]:
                raise ValueError(f"deferred {field} drift for {province_id}")
        if deferred.get("containedRuntimePlaceIds", []) != actual_evidence[
            "containedRuntimePlaceIds"
        ]:
            raise ValueError(f"deferred containedRuntimePlaceIds drift for {province_id}")
    for row in range(rows):
        for col in range(cols):
            province_index = owner[row][col]
            expected_parent = -1 if province_index < 0 else parent_index_by_province[province_index]
            if parent_owner[row][col] != expected_parent:
                raise ValueError(f"owner and parentOwner disagree at ({col}, {row})")

    county_adjacency, commandery_adjacency = derive_world_adjacency(
        np.asarray(terrain, dtype=np.int8),
        _jun_seat_coordinates(updated),
        np.asarray(owner, dtype=np.int32),
        np.asarray(parent_owner, dtype=np.int32),
    )
    updated["owner"] = encode_rle(owner)
    updated["parentOwner"] = encode_rle(parent_owner)
    updated["adjacency"] = {
        "county": county_adjacency,
        "commandery": commandery_adjacency,
    }
    counts = updated["_meta"].get("counts")
    if not isinstance(counts, dict):
        raise ValueError("_meta.counts must be an object")
    counts["adjCounty"] = len(county_adjacency)
    counts["adjCommandery"] = len(commandery_adjacency)
    return updated


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", type=Path, default=DEFAULT_TILES)
    parser.add_argument("--ledger", type=Path, default=DEFAULT_LEDGER)
    parser.add_argument("--output", type=Path, default=DEFAULT_TILES)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    document = json.loads(args.source.read_text(encoding="utf-8"))
    ledger = json.loads(args.ledger.read_text(encoding="utf-8"))
    updated = materialize_document(document, ledger)
    if args.check:
        if updated != document:
            print("canonical Han tiles do not match fragment adjudications", file=sys.stderr)
            return 1
        return 0
    args.output.write_text(
        json.dumps(updated, ensure_ascii=False, separators=(",", ":")) + "\n",
        encoding="utf-8",
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
