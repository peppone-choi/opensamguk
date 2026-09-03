#!/usr/bin/env python3
"""Materialize county/commandery records onto the committed Han tile artifact."""

from __future__ import annotations

import argparse
import copy
import json
from pathlib import Path
from typing import Any

try:
    from tools.map.world_province_geometry import (
        apply_jurisdiction_parent_adjudications,
        assign_province_jurisdictions,
        infer_commandery_kind,
        validate_jurisdiction_recovery_document,
        validate_materialized_hierarchy,
    )
except ModuleNotFoundError:  # pragma: no cover - direct script compatibility
    from world_province_geometry import (
        apply_jurisdiction_parent_adjudications,
        assign_province_jurisdictions,
        infer_commandery_kind,
        validate_jurisdiction_recovery_document,
        validate_materialized_hierarchy,
    )


ROOT = Path(__file__).resolve().parents[2]
TILES = ROOT / "data" / "map" / "han-tiles.json"
RECOVERIES = ROOT / "data" / "curated" / "han" / "jurisdiction-seat-recoveries-v1.json"
PARENT_ADJUDICATIONS = (
    ROOT / "data" / "curated" / "han" / "jurisdiction-commandery-adjudications-v1.json"
)


def _commandery_kind(parent: dict, seat_city: dict) -> str:
    return infer_commandery_kind(parent, seat_city)


def _expand_owner(runs: Any, cols: int, rows: int) -> list[list[int]]:
    if not isinstance(runs, list):
        raise ValueError("owner must be a run-length array")
    values: list[int] = []
    for index, run in enumerate(runs):
        if (
            not isinstance(run, list)
            or len(run) != 2
            or type(run[0]) is not int
            or type(run[1]) is not int
            or run[1] <= 0
        ):
            raise ValueError(f"owner[{index}] is not a valid run")
        values.extend([run[0]] * run[1])
    if len(values) != cols * rows:
        raise ValueError("owner run length does not match map dimensions")
    return [values[offset:offset + cols] for offset in range(0, len(values), cols)]


def materialize_document(
    document: dict,
    recoveries_document: dict,
    *,
    parent_adjudications_document: dict[str, Any] | None = None,
) -> dict:
    if not isinstance(document, dict) or not isinstance(recoveries_document, dict):
        raise ValueError("tile and recovery documents must be objects")
    result = copy.deepcopy(document)
    meta = result.get("_meta")
    if not isinstance(meta, dict):
        raise ValueError("tile document has no metadata")
    cols, rows = meta.get("cols"), meta.get("rows")
    if type(cols) is not int or type(rows) is not int or cols <= 0 or rows <= 0:
        raise ValueError("tile metadata has invalid dimensions")
    provinces = result.get("provinceRecords")
    parents = result.get("parentRegions")
    cities = result.get("cities")
    juns = result.get("juns")
    recoveries = validate_jurisdiction_recovery_document(recoveries_document)
    if not all(isinstance(value, list) for value in (provinces, parents, cities, juns, recoveries)):
        raise ValueError("tile hierarchy and recovery arrays are required")
    if len(parents) != len(juns):
        raise ValueError("parentRegions and juns must align")

    spatial_flags = [record.get("kind") == "SPATIAL_PROVINCE" for record in provinces]
    if any(spatial_flags):
        if not all(spatial_flags):
            raise ValueError("provinceRecords mixes legacy and spatial hierarchy kinds")
        jurisdictions = result.get("jurisdictionRecords")
        commanderies = result.get("commanderyRecords")
        if not isinstance(jurisdictions, list) or not isinstance(commanderies, list):
            raise ValueError("materialized hierarchy arrays are required")
        jurisdiction_ids = {
            record.get("id") for record in jurisdictions
            if isinstance(record.get("id"), str) and record.get("id")
        }
        if len(jurisdiction_ids) != len(jurisdictions):
            raise ValueError("materialized jurisdiction IDs must be unique")
        unknown = next((
            record.get("jurisdictionId")
            for record in provinces
            if record.get("jurisdictionId") not in jurisdiction_ids
        ), None)
        if unknown is not None:
            raise ValueError(f"spatial province references unknown jurisdiction: {unknown}")
        if len(commanderies) != len(parents):
            raise ValueError("materialized commandery count must match parentRegions")
        commandery_by_id = {record.get("id"): record for record in commanderies}
        if len(commandery_by_id) != len(commanderies):
            raise ValueError("materialized commandery IDs must be unique")
        owner = _expand_owner(result.get("owner"), cols, rows)
        for index, parent in enumerate(parents):
            parent_id = parent.get("id")
            commandery = commandery_by_id.get(parent_id)
            if commandery is None:
                raise ValueError(f"parent has no materialized commandery: {parent_id}")
            seat_index = juns[index].get("seat")
            if type(seat_index) is not int or not 0 <= seat_index < len(cities):
                raise ValueError(f"parent has invalid seat city: {parent_id}")
            seat_city = cities[seat_index]
            seat_row, seat_col = seat_city.get("row"), seat_city.get("col")
            if (
                type(seat_row) is not int
                or type(seat_col) is not int
                or not 0 <= seat_row < rows
                or not 0 <= seat_col < cols
            ):
                raise ValueError(f"parent seat has invalid grid cell: {parent_id}")
            seat_label = owner[seat_row][seat_col]
            if type(seat_label) is not int or not 0 <= seat_label < len(provinces):
                raise ValueError(f"parent seat is outside playable province geometry: {parent_id}")
            seat_province = provinces[seat_label]
            if seat_province.get("parentRegionId") != parent_id:
                raise ValueError(f"parent seat is inside another parent region: {parent_id}")
            seat_jurisdiction_id = seat_province.get("jurisdictionId")
            if seat_jurisdiction_id not in commandery.get("jurisdictionIds", []):
                raise ValueError(f"parent seat has invalid jurisdiction: {parent_id}")
            commandery["kind"] = _commandery_kind(parent, seat_city)
            commandery["seatJurisdictionId"] = seat_jurisdiction_id
        recovery_by_id = {
            recovery.get("jurisdictionId"): recovery for recovery in recoveries
        }
        for jurisdiction in jurisdictions:
            recovery = recovery_by_id.get(jurisdiction.get("id"))
            if recovery is None:
                continue
            for key in ("displayName", "nameCh", "kind", "seatPlaceId"):
                jurisdiction[key] = recovery[key]
        if parent_adjudications_document is not None:
            apply_jurisdiction_parent_adjudications(result, parent_adjudications_document)
        validate_materialized_hierarchy(provinces, jurisdictions, commanderies)
        return result

    parent_seats = []
    enriched_parents = []
    for index, parent in enumerate(parents):
        seat_index = juns[index].get("seat")
        if type(seat_index) is not int or not 0 <= seat_index < len(cities):
            raise ValueError(f"parent has invalid seat city: {parent.get('id')}")
        parent_seats.append(seat_index)
        kind = _commandery_kind(parent, cities[seat_index])
        enriched_parents.append({**parent, "kind": kind})

    assigned = assign_province_jurisdictions(
        _expand_owner(result.get("owner"), cols, rows),
        provinces,
        enriched_parents,
        cities,
        parent_seats=parent_seats,
        jurisdiction_recoveries=recoveries,
    )
    result["provinceRecords"] = list(assigned.province_records)
    result["jurisdictionRecords"] = list(assigned.jurisdiction_records)
    result["commanderyRecords"] = list(assigned.commandery_records)
    if parent_adjudications_document is not None:
        apply_jurisdiction_parent_adjudications(result, parent_adjudications_document)
    validate_materialized_hierarchy(
        result["provinceRecords"], result["jurisdictionRecords"], result["commanderyRecords"]
    )
    counts = meta.get("counts")
    if not isinstance(counts, dict):
        raise ValueError("tile metadata has no counts object")
    counts["provinces"] = len(assigned.province_records)
    counts["jurisdictions"] = len(assigned.jurisdiction_records)
    counts["commanderies"] = len(assigned.commandery_records)
    return result


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    document = json.loads(TILES.read_text(encoding="utf-8"))
    recoveries = json.loads(RECOVERIES.read_text(encoding="utf-8"))
    parent_adjudications = json.loads(PARENT_ADJUDICATIONS.read_text(encoding="utf-8"))
    blob = json.dumps(
        materialize_document(
            document,
            recoveries,
            parent_adjudications_document=parent_adjudications,
        ),
        ensure_ascii=False,
        separators=(",", ":"),
    ) + "\n"
    if args.check:
        if TILES.read_text(encoding="utf-8") == blob:
            print("드리프트 없음.")
            return 0
        print(f"드리프트: {TILES.relative_to(ROOT)}")
        return 1
    TILES.write_text(blob, encoding="utf-8")
    print(f"{TILES.relative_to(ROOT)} · jurisdiction hierarchy materialized")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
