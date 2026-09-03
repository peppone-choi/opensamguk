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


def _valid_digest(value: object) -> bool:
    return (
        isinstance(value, str)
        and len(value) == 64
        and all(character in "0123456789abcdef" for character in value)
    )


def _validate_cells(value: object, field: str) -> list[list[int]]:
    if (
        not isinstance(value, list)
        or not value
        or any(
            not isinstance(cell, list)
            or len(cell) != 2
            or any(type(coordinate) is not int for coordinate in cell)
            for cell in value
        )
    ):
        raise ValueError(f"{field} must be nonempty [col, row] pairs")
    if len({(cell[1], cell[0]) for cell in value}) != len(value):
        raise ValueError(f"{field} contains duplicates")
    return value


def _validate_ledger(ledger: dict) -> tuple[list[dict], list[dict], list[dict]]:
    if ledger.get("contractId") != "han-province-fragment-adjudications-v1":
        raise ValueError("unexpected fragment adjudication contractId")
    minimum = ledger.get("minimumProvinceArea")
    if type(minimum) is not int or minimum <= 0:
        raise ValueError("minimumProvinceArea must be a positive integer")
    for field in (
        "inputOwnerSha256",
        "outputOwnerSha256",
        "inputCitiesSha256",
        "outputCitiesSha256",
        "inputJunsSha256",
        "outputJunsSha256",
        "seatOwnerSha256",
    ):
        if not _valid_digest(ledger.get(field)):
            raise ValueError(f"{field} must be a lowercase SHA-256 digest")
    decisions = ledger.get("reassignments")
    anchored = ledger.get("anchoredReassignments")
    deferred = ledger.get("deferred")
    preservations = ledger.get("preservations")
    if not isinstance(decisions, list) or not all(isinstance(row, dict) for row in decisions):
        raise ValueError("reassignments must be an array of objects")
    if not isinstance(anchored, list) or not all(isinstance(row, dict) for row in anchored):
        raise ValueError("anchoredReassignments must be an array of objects")
    if not isinstance(deferred, list) or not all(isinstance(row, dict) for row in deferred):
        raise ValueError("deferred must be an array of objects")
    if not isinstance(preservations, list) or not all(isinstance(row, dict) for row in preservations):
        raise ValueError("preservations must be an array of objects")
    pre_applied = ledger.get("preAppliedReassignmentCount")
    if type(pre_applied) is not int or not 0 <= pre_applied <= len(decisions):
        raise ValueError("preAppliedReassignmentCount must index the reassignments prefix")
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
        cells = _validate_cells(decision.get("cells"), f"reassignments[{index}].cells")
        coordinates = {(cell[1], cell[0]) for cell in cells}
        if len(coordinates) != len(cells):
            raise ValueError(f"reassignments[{index}].cells contains duplicates")
        overlap = seen_cells & coordinates
        if overlap:
            raise ValueError(f"reassignments[{index}].cells overlaps another decision")
        seen_cells.update(coordinates)
        if not isinstance(decision.get("evidence"), dict):
            raise ValueError(f"reassignments[{index}].evidence must be an object")
    anchored_sources: set[str] = set()
    for index, decision in enumerate(anchored):
        if decision.get("classification") != "COMMANDERY_ANCHOR_NORMALIZATION":
            raise ValueError(f"anchoredReassignments[{index}] has an unsupported classification")
        if decision.get("assignmentMethod") != "NEAREST_TARGET_SEAT_EUCLIDEAN_SQUARED":
            raise ValueError(f"anchoredReassignments[{index}] has an unsupported assignmentMethod")
        source_id = decision.get("sourceProvinceId")
        if not isinstance(source_id, str) or not source_id or source_id in anchored_sources:
            raise ValueError(f"anchoredReassignments[{index}] has an invalid sourceProvinceId")
        anchored_sources.add(source_id)
        if not isinstance(decision.get("anchorPlaceId"), str) or not decision["anchorPlaceId"]:
            raise ValueError(f"anchoredReassignments[{index}] has an invalid anchorPlaceId")
        for field in ("anchorFrom", "anchorTo"):
            value = decision.get(field)
            if (
                not isinstance(value, list)
                or len(value) != 2
                or any(type(coordinate) is not int for coordinate in value)
            ):
                raise ValueError(f"anchoredReassignments[{index}].{field} must be [col, row]")
        component_cells = _validate_cells(
            decision.get("componentCells"),
            f"anchoredReassignments[{index}].componentCells",
        )
        component_coordinates = {(cell[1], cell[0]) for cell in component_cells}
        overlap = seen_cells & component_coordinates
        if overlap:
            raise ValueError(f"anchoredReassignments[{index}].componentCells overlaps another decision")
        seen_cells.update(component_coordinates)
        targets = decision.get("allowedTargetProvinceIds")
        if (
            not isinstance(targets, list)
            or not targets
            or any(not isinstance(target, str) or not target for target in targets)
            or len(set(targets)) != len(targets)
            or targets != sorted(targets)
        ):
            raise ValueError(
                f"anchoredReassignments[{index}].allowedTargetProvinceIds must be sorted unique IDs"
            )
        assignments = decision.get("targetAssignments")
        if not isinstance(assignments, list) or not assignments:
            raise ValueError(f"anchoredReassignments[{index}].targetAssignments must be nonempty")
        assigned: set[tuple[int, int]] = set()
        for assignment_index, assignment in enumerate(assignments):
            if not isinstance(assignment, dict) or assignment.get("targetProvinceId") not in targets:
                raise ValueError(
                    f"anchoredReassignments[{index}].targetAssignments[{assignment_index}] has an invalid target"
                )
            cells = _validate_cells(
                assignment.get("cells"),
                f"anchoredReassignments[{index}].targetAssignments[{assignment_index}].cells",
            )
            coordinates = {(cell[1], cell[0]) for cell in cells}
            if assigned & coordinates:
                raise ValueError(f"anchoredReassignments[{index}] assigns a cell twice")
            assigned.update(coordinates)
        if assigned != component_coordinates:
            raise ValueError(f"anchoredReassignments[{index}] must assign the exact componentCells")
        if not isinstance(decision.get("evidence"), dict):
            raise ValueError(f"anchoredReassignments[{index}].evidence must be an object")
        if not isinstance(decision.get("reason"), str) or not decision["reason"].strip():
            raise ValueError(f"anchoredReassignments[{index}].reason must be nonempty")
    preservation_ids: set[str] = set()
    for index, row in enumerate(preservations):
        province_id = row.get("provinceId")
        if not isinstance(province_id, str) or not province_id or province_id in preservation_ids:
            raise ValueError(f"preservations[{index}] has an invalid provinceId")
        preservation_ids.add(province_id)
        if row.get("classification") != "MARITIME_ANCHORED_COMPONENT_PRESERVED":
            raise ValueError(f"preservations[{index}] has an unsupported classification")
        counts = row.get("componentCellCounts")
        if (
            not isinstance(counts, list)
            or len(counts) < 2
            or any(type(value) is not int or value <= 0 for value in counts)
            or counts != sorted(counts, reverse=True)
        ):
            raise ValueError(f"preservations[{index}].componentCellCounts must be descending")
        if not _valid_digest(row.get("cellSetSha256")):
            raise ValueError(f"preservations[{index}].cellSetSha256 must be a SHA-256 digest")
        if not isinstance(row.get("reason"), str) or not row["reason"].strip():
            raise ValueError(f"preservations[{index}].reason must be nonempty")
    if deferred_ids & preservation_ids:
        raise ValueError("a province cannot be both deferred and preserved")
    return decisions, anchored, preservations


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


def _city_index_by_id(cities: list[dict]) -> dict[str, int]:
    result: dict[str, int] = {}
    for index, city in enumerate(cities):
        place_id = city.get("id")
        if not isinstance(place_id, str) or not place_id:
            raise ValueError(f"cities[{index}] has an invalid ID")
        if place_id in result:
            raise ValueError(f"cities contains duplicate ID {place_id}")
        result[place_id] = index
    return result


def _nearest_target_partition(
    component: set[tuple[int, int]],
    target_ids: list[str],
    province_index_by_id: dict[str, int],
    provinces: list[dict],
    cities: list[dict],
) -> dict[str, set[tuple[int, int]]]:
    target_seats: dict[str, tuple[int, int]] = {}
    for target_id in target_ids:
        target_index = province_index_by_id.get(target_id)
        if target_index is None:
            raise ValueError(f"anchor decision references unknown target province {target_id}")
        seat = _seat_for(provinces[target_index], cities)
        if seat is None:
            raise ValueError(f"anchor target province {target_id} has no canonical seat")
        target_seats[target_id] = seat
    result = {target_id: set() for target_id in target_ids}
    for row, col in component:
        selected = min(
            target_ids,
            key=lambda target_id: (
                (row - target_seats[target_id][0]) ** 2
                + (col - target_seats[target_id][1]) ** 2,
                target_id,
            ),
        )
        result[selected].add((row, col))
    return {target_id: cells for target_id, cells in result.items() if cells}


def materialize_document(document: dict, ledger: dict) -> dict:
    """Return an idempotently patched copy after validating every decision."""
    updated = copy.deepcopy(document)
    meta = updated.get("_meta")
    if not isinstance(meta, dict):
        raise ValueError("han tiles must contain _meta")
    rows, cols = meta.get("rows"), meta.get("cols")
    if type(rows) is not int or type(cols) is not int or rows <= 0 or cols <= 0:
        raise ValueError("rows and cols must be positive integers")
    decisions, anchored_decisions, preservations = _validate_ledger(ledger)
    provinces, province_index_by_id = _require_unique_records(updated, "provinceRecords")
    parents, parent_index_by_id = _require_unique_records(updated, "parentRegions")
    province_ids = [row["id"] for row in provinces]
    cities = updated.get("cities")
    if not isinstance(cities, list) or not all(isinstance(row, dict) for row in cities):
        raise ValueError("cities must be an array of objects")
    city_index_by_id = _city_index_by_id(cities)
    place_anchors: list[tuple[tuple[int, int], str]] = []
    for index, city in enumerate(cities):
        row, col = city.get("row"), city.get("col")
        if type(row) is not int or type(col) is not int:
            raise ValueError(f"cities[{index}] has invalid coordinates")
        place_id = city["id"]
        place_anchors.append(((row, col), place_id))
    juns = updated.get("juns")
    if not isinstance(juns, list) or not all(isinstance(row, dict) for row in juns):
        raise ValueError("juns must be an array of objects")
    owner = expand_rle(updated.get("owner"), rows, cols)
    parent_owner = expand_rle(updated.get("parentOwner"), rows, cols)
    terrain, terrain_legend = _terrain_grid(updated, rows, cols)
    if _json_digest(updated.get("seatOwner")) != ledger["seatOwnerSha256"]:
        raise ValueError("seatOwner digest disagrees with the adjudication ledger")
    owner_digest = _grid_digest(owner)
    cities_digest = _json_digest(cities)
    juns_digest = _json_digest(juns)
    input_state = (
        owner_digest == ledger["inputOwnerSha256"]
        and cities_digest == ledger["inputCitiesSha256"]
        and juns_digest == ledger["inputJunsSha256"]
    )
    output_state = (
        owner_digest == ledger["outputOwnerSha256"]
        and cities_digest == ledger["outputCitiesSha256"]
        and juns_digest == ledger["outputJunsSha256"]
    )
    if input_state:
        state = "input"
    elif output_state:
        state = "output"
    else:
        raise ValueError("owner, cities, and juns are neither the adjudicated input nor output state")

    parent_index_by_province: list[int] = []
    for province in provinces:
        parent_id = province.get("parentRegionId")
        if parent_id not in parent_index_by_id:
            raise ValueError(f"province {province.get('id')} has an unknown parentRegionId")
        parent_index_by_province.append(parent_index_by_id[parent_id])

    pending: list[tuple[dict, int, int, set[tuple[int, int]]]] = []
    for decision_index, decision in enumerate(decisions):
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
        if values == {target_index} and (
            state == "output" or decision_index < ledger["preAppliedReassignmentCount"]
        ):
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

    if anchored_decisions:
        jurisdiction_records, jurisdiction_index_by_id = _require_unique_records(
            updated, "jurisdictionRecords"
        )
        commandery_records, commandery_index_by_id = _require_unique_records(
            updated, "commanderyRecords"
        )
        for decision in anchored_decisions:
            source_id = decision["sourceProvinceId"]
            source_index = province_index_by_id.get(source_id)
            if source_index is None:
                raise ValueError(f"anchor decision references unknown source province {source_id}")
            source = provinces[source_index]
            jurisdiction_id = source.get("jurisdictionId")
            jurisdiction_index = jurisdiction_index_by_id.get(jurisdiction_id)
            if jurisdiction_index is None:
                raise ValueError(f"anchor source {source_id} has no canonical jurisdiction")
            jurisdiction = jurisdiction_records[jurisdiction_index]
            commandery_id = jurisdiction.get("commanderyId")
            commandery_index = commandery_index_by_id.get(commandery_id)
            if commandery_index is None:
                raise ValueError(f"anchor source {source_id} has no canonical commandery")
            commandery = commandery_records[commandery_index]
            if commandery.get("seatJurisdictionId") != jurisdiction_id:
                raise ValueError(
                    f"anchor source {source_id} disagrees with commandery seatJurisdictionId"
                )
            seat_place_id = jurisdiction.get("seatPlaceId")
            seat_city_index = city_index_by_id.get(seat_place_id)
            anchor_city_index = city_index_by_id.get(decision["anchorPlaceId"])
            if seat_city_index is None or anchor_city_index is None:
                raise ValueError(f"anchor source {source_id} references an unknown place")
            if decision["anchorPlaceId"] == seat_place_id:
                raise ValueError("commandery anchor must be distinct from the jurisdiction seat place")
            anchor_city = cities[anchor_city_index]
            if anchor_city.get("kind") != "COMMANDERY":
                raise ValueError(f"anchor place {decision['anchorPlaceId']} is not a commandery marker")
            seat_city = cities[seat_city_index]
            canonical_to = [seat_city.get("col"), seat_city.get("row")]
            if canonical_to != decision["anchorTo"]:
                raise ValueError(f"anchor source {source_id} canonical seat coordinates drifted")
            source_parent_id = source.get("parentRegionId")
            parent_index = parent_index_by_id.get(source_parent_id)
            if parent_index is None or parent_index >= len(juns):
                raise ValueError(f"anchor source {source_id} has no aligned jun")
            if juns[parent_index].get("seat") != anchor_city_index:
                raise ValueError(f"anchor source {source_id} jun seat is not the commandery marker")
            coordinates = {
                (cell[1], cell[0]) for cell in decision["componentCells"]
            }
            target_by_coordinate = {
                (cell[1], cell[0]): assignment["targetProvinceId"]
                for assignment in decision["targetAssignments"]
                for cell in assignment["cells"]
            }
            if state == "output":
                for coordinate, target_id in target_by_coordinate.items():
                    target_index = province_index_by_id[target_id]
                    if owner[coordinate[0]][coordinate[1]] != target_index:
                        raise ValueError("anchor target assignment drifted in output state")
                if [anchor_city.get("col"), anchor_city.get("row")] != decision["anchorTo"]:
                    raise ValueError("commandery anchor output coordinates drifted")
                if [juns[parent_index].get("col"), juns[parent_index].get("row")] != decision["anchorTo"]:
                    raise ValueError("jun anchor output coordinates drifted")
                continue
            if [anchor_city.get("col"), anchor_city.get("row")] != decision["anchorFrom"]:
                raise ValueError("commandery anchor input coordinates drifted")
            if any(not (0 <= row < rows and 0 <= col < cols) for row, col in coordinates):
                raise ValueError("anchor component contains an out-of-range cell")
            if {owner[row][col] for row, col in coordinates} != {source_index}:
                raise ValueError("anchor cells do not identify the source province")
            component = _component(owner, source_index, min(coordinates))
            if component != coordinates:
                raise ValueError("anchor cells do not identify the exact source component")
            source_parent = parent_index_by_province[source_index]
            if any(parent_owner[row][col] != source_parent for row, col in component):
                raise ValueError("parentOwner disagrees with the anchor source component")
            actual_component = _component_evidence(
                component=component,
                owner=owner,
                terrain=terrain,
                terrain_legend=terrain_legend,
                province_ids=province_ids,
                source_seat=(int(seat_city["row"]), int(seat_city["col"])),
            )
            contained_place_ids = sorted(
                place_id for coordinate, place_id in place_anchors if coordinate in component
            )
            actual_evidence = {
                "sourceComponentCellCount": actual_component["componentCellCount"],
                "terrainClasses": actual_component["terrainClasses"],
                "surroundingProvinceIds": actual_component["surroundingProvinceIds"],
                "containsJurisdictionSeat": actual_component["containsSourceSeat"],
                "containedRuntimePlaceIds": contained_place_ids,
                "commanderyId": commandery_id,
                "seatJurisdictionId": jurisdiction_id,
                "canonicalSeatPlaceId": seat_place_id,
            }
            if actual_component["touchesNegative"]:
                raise ValueError("anchor source component touches negative terrain")
            if actual_evidence != decision["evidence"]:
                raise ValueError(
                    f"anchor evidence drift for {source_id}: expected {decision['evidence']!r}, "
                    f"got {actual_evidence!r}"
                )
            if actual_evidence["containsJurisdictionSeat"]:
                raise ValueError("anchor component contains the canonical jurisdiction seat")
            if contained_place_ids != [decision["anchorPlaceId"]]:
                raise ValueError("anchor component must contain exactly its reviewed commandery marker")
            if decision["allowedTargetProvinceIds"] != actual_component["surroundingProvinceIds"]:
                raise ValueError("anchor allowed targets disagree with surrounding provinces")
            expected_partition = _nearest_target_partition(
                component,
                decision["allowedTargetProvinceIds"],
                province_index_by_id,
                provinces,
                cities,
            )
            actual_partition: dict[str, set[tuple[int, int]]] = {}
            for coordinate, target_id in target_by_coordinate.items():
                actual_partition.setdefault(target_id, set()).add(coordinate)
            if actual_partition != expected_partition:
                raise ValueError("anchor component target assignments do not match the nearest target seat")
            for coordinate, target_id in target_by_coordinate.items():
                target_index = province_index_by_id[target_id]
                owner[coordinate[0]][coordinate[1]] = target_index
                parent_owner[coordinate[0]][coordinate[1]] = parent_index_by_province[target_index]
            anchor_city["col"], anchor_city["row"] = decision["anchorTo"]
            juns[parent_index]["col"], juns[parent_index]["row"] = decision["anchorTo"]

    if _grid_digest(owner) != ledger["outputOwnerSha256"]:
        raise ValueError("materialized owner grid disagrees with outputOwnerSha256")
    if _json_digest(cities) != ledger["outputCitiesSha256"]:
        raise ValueError("materialized cities disagree with outputCitiesSha256")
    if _json_digest(juns) != ledger["outputJunsSha256"]:
        raise ValueError("materialized juns disagree with outputJunsSha256")

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
    ledger_preserved = {row["provinceId"]: row for row in preservations}
    reviewed_disconnected = set(ledger_deferred) | set(ledger_preserved)
    if set(actual_deferred) != reviewed_disconnected:
        missing = sorted(set(actual_deferred) - reviewed_disconnected)
        extra = sorted(reviewed_disconnected - set(actual_deferred))
        raise ValueError(
            f"deferred ledger does not match disconnected provinces: missing={missing}, extra={extra}"
        )
    for province_id, components in actual_deferred.items():
        deferred = ledger_deferred.get(province_id)
        preservation = ledger_preserved.get(province_id)
        reviewed = deferred or preservation
        assert reviewed is not None
        province_index = province_index_by_id[province_id]
        actual_counts = [len(component) for component in components]
        category = "deferred" if deferred else "preservation"
        if reviewed["componentCellCounts"] != actual_counts:
            raise ValueError(f"{category} component counts drift for {province_id}")
        if reviewed["cellSetSha256"] != _cell_set_digest(components):
            raise ValueError(f"{category} cell fingerprint drift for {province_id}")
        actual_evidence = _deferred_evidence(
            components=components,
            owner=owner,
            terrain=terrain,
            terrain_legend=terrain_legend,
            province_ids=province_ids,
            province_index=province_index,
            place_anchors=place_anchors,
        )
        if preservation:
            jurisdiction_records = updated.get("jurisdictionRecords")
            matching_jurisdictions = [
                row for row in jurisdiction_records or []
                if isinstance(row, dict) and row.get("id") == provinces[province_index].get("jurisdictionId")
            ]
            if (
                actual_evidence["classification"] != "ANCHOR_CONTAINING_REVIEW_REQUIRED"
                or "SEA" not in actual_evidence["secondaryNegativeBoundaryTypes"]
                or min(actual_counts) < minimum
                or provinces[province_index].get("administrativeSystem") == "HAN_COMMANDERY"
                or len(matching_jurisdictions) != 1
                or matching_jurisdictions[0].get("kind") == "COUNTY"
                or actual_evidence["containedRuntimePlaceIds"] != [province_id]
            ):
                raise ValueError(f"preservation contract drift for {province_id}")
        elif deferred["classification"] != actual_evidence["classification"]:
            raise ValueError(
                f"deferred classification drift for {province_id}: "
                f"expected {deferred['classification']}, "
                f"got {actual_evidence['classification']}"
            )
        for field in (
            "secondaryNegativeBoundaryTypes",
            "secondarySurroundingProvinceIds",
        ):
            if reviewed.get(field) != actual_evidence[field]:
                raise ValueError(f"{category} {field} drift for {province_id}")
        if reviewed.get("containedRuntimePlaceIds", []) != actual_evidence[
            "containedRuntimePlaceIds"
        ]:
            raise ValueError(f"{category} containedRuntimePlaceIds drift for {province_id}")
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
