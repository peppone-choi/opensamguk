#!/usr/bin/env python3
"""Derive a deterministic review inventory from the committed Han map hierarchy.

This tool is deliberately read-only with respect to ``han-tiles.json``.  It does
not rebuild geometry, fill gaps, or adjudicate historical legitimacy.  The
output is a pinned candidate ledger for source-backed review.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
from collections import Counter, defaultdict
from pathlib import Path
from typing import Iterable


ROOT = Path(__file__).resolve().parents[2]
DEFAULT_TILES = ROOT / "data" / "map" / "han-tiles.json"
DEFAULT_UNITS = ROOT / "data" / "curated" / "han" / "administrative-units.json"
DEFAULT_BINDINGS = (
    ROOT / "data" / "curated" / "han" / "administrative-place-bindings-v1.json"
)
DEFAULT_OUTPUT = (
    ROOT / "data" / "curated" / "han" / "administrative-topology-audit-v1.json"
)


def _sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _decode_owner(document: dict) -> tuple[int, int, list[list[int]]]:
    meta = document.get("_meta")
    if not isinstance(meta, dict):
        raise ValueError("han tiles must contain _meta")
    rows, cols = meta.get("rows"), meta.get("cols")
    if type(rows) is not int or type(cols) is not int or rows <= 0 or cols <= 0:
        raise ValueError("han tiles rows and cols must be positive integers")
    runs = document.get("owner")
    if not isinstance(runs, list):
        raise ValueError("han tiles owner must be RLE runs")
    values: list[int] = []
    for index, run in enumerate(runs):
        if (
            not isinstance(run, list)
            or len(run) != 2
            or type(run[0]) is not int
            or type(run[1]) is not int
            or run[1] <= 0
        ):
            raise ValueError(f"owner[{index}] must be [integer, positive count]")
        values.extend([run[0]] * run[1])
    if len(values) != rows * cols:
        raise ValueError("owner RLE length does not match rows * cols")
    return rows, cols, [values[offset:offset + cols] for offset in range(0, len(values), cols)]


def _require_records(document: dict, key: str) -> list[dict]:
    records = document.get(key)
    if not isinstance(records, list) or not all(isinstance(row, dict) for row in records):
        raise ValueError(f"{key} must be an array of objects")
    return records


def _component_inventory(
    grid: list[list[int]],
    labels_by_province: list[str],
    names_by_id: dict[str, str],
    parent_by_id: dict[str, str] | None = None,
) -> dict:
    rows, cols = len(grid), len(grid[0])
    seen: set[tuple[int, int]] = set()
    components_by_id: dict[str, list[dict]] = defaultdict(list)

    for row in range(rows):
        for col in range(cols):
            province_index = grid[row][col]
            if province_index < 0 or (row, col) in seen:
                continue
            if province_index >= len(labels_by_province):
                raise ValueError(f"owner province index out of range: {province_index}")
            label = labels_by_province[province_index]
            stack = [(row, col)]
            seen.add((row, col))
            cell_count = 0
            touches_outside = False
            surrounding_ids: set[str] = set()

            while stack:
                current_row, current_col = stack.pop()
                cell_count += 1
                for delta_row, delta_col in ((-1, 0), (1, 0), (0, -1), (0, 1)):
                    neighbor_row = current_row + delta_row
                    neighbor_col = current_col + delta_col
                    if not (0 <= neighbor_row < rows and 0 <= neighbor_col < cols):
                        touches_outside = True
                        continue
                    neighbor_province = grid[neighbor_row][neighbor_col]
                    if neighbor_province < 0:
                        touches_outside = True
                        continue
                    if neighbor_province >= len(labels_by_province):
                        raise ValueError(f"owner province index out of range: {neighbor_province}")
                    neighbor_label = labels_by_province[neighbor_province]
                    if neighbor_label == label:
                        coordinate = (neighbor_row, neighbor_col)
                        if coordinate not in seen:
                            seen.add(coordinate)
                            stack.append(coordinate)
                    else:
                        surrounding_ids.add(neighbor_label)

            components_by_id[label].append({
                "cellCount": cell_count,
                "touchesOutside": touches_outside,
                "surroundingIds": sorted(surrounding_ids),
            })

    disconnected: list[dict] = []
    fully_enclosed: list[dict] = []
    for record_id in sorted(components_by_id):
        components = sorted(
            components_by_id[record_id],
            key=lambda row: (-row["cellCount"], row["surroundingIds"]),
        )
        if len(components) > 1:
            disconnected.append({
                "id": record_id,
                "displayName": names_by_id[record_id],
                "componentCellCounts": [row["cellCount"] for row in components],
            })
        for component in components:
            if not component["touchesOutside"] and len(component["surroundingIds"]) == 1:
                surrounding_id = component["surroundingIds"][0]
                row = {
                    "id": record_id,
                    "displayName": names_by_id[record_id],
                    "componentCellCount": component["cellCount"],
                    "surroundingIds": [surrounding_id],
                    "surroundingDisplayNames": [names_by_id[surrounding_id]],
                }
                if parent_by_id is not None:
                    row.update({
                        "commanderyId": parent_by_id[record_id],
                        "surroundingCommanderyId": parent_by_id[surrounding_id],
                        "sameCommandery": parent_by_id[record_id] == parent_by_id[surrounding_id],
                    })
                fully_enclosed.append(row)

    fully_enclosed.sort(
        key=lambda row: (row["id"], -row["componentCellCount"], row["surroundingIds"])
    )
    return {
        "disconnectedCount": len(disconnected),
        "disconnected": disconnected,
        "fullyEnclosedCount": len(fully_enclosed),
        "fullyEnclosed": fully_enclosed,
    }


def _binding_index(bindings_document: dict) -> dict[tuple[int, str, int], dict]:
    records = _require_records(bindings_document, "administrativeUnits")
    result: dict[tuple[int, str, int], dict] = {}
    for index, record in enumerate(records):
        identity = record.get("identity")
        if not isinstance(identity, dict):
            raise ValueError(f"administrativeUnits[{index}].identity must be an object")
        key = (
            identity.get("sourceVolume"),
            identity.get("canonicalGroup"),
            identity.get("ordinal"),
        )
        if type(key[0]) is not int or not isinstance(key[1], str) or type(key[2]) is not int:
            raise ValueError(f"administrativeUnits[{index}] has an invalid identity")
        if key in result:
            raise ValueError(f"duplicate administrative binding identity: {key}")
        result[key] = record
    return result


def _single_commandery_inventory(
    commanderies: list[dict],
    units_document: dict,
    bindings_document: dict,
    jurisdiction_by_id: dict[str, dict],
) -> list[dict]:
    groups = _require_records(units_document, "groups")
    groups_by_name: dict[str, dict] = {}
    for group in groups:
        name = group.get("canonicalGroup")
        if isinstance(name, str):
            if name in groups_by_name:
                raise ValueError(f"duplicate administrative source group: {name}")
            groups_by_name[name] = group
    bindings = _binding_index(bindings_document)

    result: list[dict] = []
    for commandery in sorted(commanderies, key=lambda row: row["id"]):
        jurisdiction_ids = commandery.get("jurisdictionIds")
        if not isinstance(jurisdiction_ids, list):
            raise ValueError(f"commandery {commandery.get('id')} jurisdictionIds must be an array")
        if len(jurisdiction_ids) != 1:
            continue
        row = {
            "id": commandery["id"],
            "displayName": commandery["displayName"],
            "nameCh": commandery["nameCh"],
            "kind": commandery["kind"],
            "jurisdictionId": jurisdiction_ids[0],
            "sourceGroupMatch": False,
        }
        source_group = groups_by_name.get(commandery["nameCh"])
        if source_group is not None:
            source_units = source_group.get("units")
            if not isinstance(source_units, list):
                raise ValueError(f"source group {commandery['nameCh']} units must be an array")
            status_counts: Counter[str] = Counter()
            without_coordinate: list[str] = []
            resolved_physical_places: list[str] = []
            source_names: list[str] = []
            source_unit_rows: list[dict] = []
            for unit in source_units:
                key = (
                    unit.get("sourceVolume"),
                    unit.get("canonicalGroup"),
                    unit.get("ordinal"),
                )
                binding = bindings.get(key)
                if binding is None:
                    raise ValueError(f"missing administrative binding for {key}")
                status = binding.get("joinStatus")
                if not isinstance(status, str):
                    raise ValueError(f"binding {key} joinStatus must be a string")
                status_counts[status] += 1
                source_name = unit.get("sourceName")
                if not isinstance(source_name, str):
                    raise ValueError(f"source unit {key} sourceName must be a string")
                source_names.append(source_name)
                if status == "NO_COORDINATE_CANDIDATE":
                    without_coordinate.append(source_name)
                selected = binding.get("selectedCandidate")
                physical_place_id: str | None = None
                if isinstance(selected, dict):
                    selected_physical_place_id = selected.get("physicalPlaceId")
                    if isinstance(selected_physical_place_id, str):
                        physical_place_id = selected_physical_place_id
                        resolved_physical_places.append(physical_place_id)
                current_jurisdiction_id = (
                    physical_place_id.rsplit(":", 1)[-1]
                    if isinstance(physical_place_id, str)
                    else None
                )
                current_jurisdiction = jurisdiction_by_id.get(current_jurisdiction_id)
                source_unit_row = {
                    "sourceName": source_name,
                    "ordinal": unit.get("ordinal"),
                    "joinStatus": status,
                }
                if physical_place_id is not None:
                    source_unit_row["physicalPlaceId"] = physical_place_id
                if current_jurisdiction is not None:
                    source_unit_row.update({
                        "currentJurisdictionId": current_jurisdiction_id,
                        "currentCommanderyId": current_jurisdiction["commanderyId"],
                    })
                source_unit_rows.append(source_unit_row)
            row.update({
                "sourceGroupMatch": True,
                "sourceVolume": source_group.get("sourceVolume"),
                "sourceCitation": source_group.get("sourceCitation"),
                "traditionalTextCitation": source_group.get("traditionalTextCitation"),
                "sourceEnumeratedUnitCount": len(source_units),
                "sourceUnitNames": source_names,
                "sourceUnits": source_unit_rows,
                "sourceBindingStatusCounts": dict(sorted(status_counts.items())),
                "sourceResolvedPhysicalPlaceIds": sorted(resolved_physical_places),
                "sourceUnitsWithoutCoordinateCandidate": without_coordinate,
                "requiresMissingCountyReview": len(source_units) > 1,
            })
        result.append(row)
    return result


def validate_ailao_layers(document: dict) -> None:
    cities = _require_records(document, "cities")
    jurisdictions = _require_records(document, "jurisdictionRecords")
    commanderies = _require_records(document, "commanderyRecords")
    city_by_id = {row.get("id"): row for row in cities}
    jurisdiction_by_id = {row.get("id"): row for row in jurisdictions}
    commandery_by_id = {row.get("id"): row for row in commanderies}
    ethnic = city_by_id.get("X060")
    county = city_by_id.get("80004")
    ethnic_jurisdiction = jurisdiction_by_id.get("X060")
    county_jurisdiction = jurisdiction_by_id.get("80004")
    if (
        not isinstance(ethnic, dict)
        or ethnic.get("nameCh") != "哀牢"
        or ethnic.get("kind") != "EXTERNAL_PLACE"
        or not isinstance(county, dict)
        or county.get("nameCh") != "哀牢县"
        or county.get("kind") != "COUNTY"
        or not isinstance(ethnic_jurisdiction, dict)
        or ethnic_jurisdiction.get("kind") not in {"EXTERNAL_PLACE", "EXTERNAL_SETTLEMENT"}
        or not isinstance(county_jurisdiction, dict)
        or county_jurisdiction.get("kind") != "COUNTY"
        or ethnic_jurisdiction.get("commanderyId") not in commandery_by_id
        or county_jurisdiction.get("commanderyId") not in commandery_by_id
        or ethnic_jurisdiction.get("commanderyId") == county_jurisdiction.get("commanderyId")
    ):
        raise ValueError("哀牢 ethnic region and 哀牢县 administrative county must remain distinct")


def audit_document(document: dict, units_document: dict, bindings_document: dict) -> dict:
    rows, cols, grid = _decode_owner(document)
    provinces = _require_records(document, "provinceRecords")
    jurisdictions = _require_records(document, "jurisdictionRecords")
    commanderies = _require_records(document, "commanderyRecords")
    province_by_id = {row["id"]: row for row in provinces}
    jurisdiction_by_id = {row["id"]: row for row in jurisdictions}
    commandery_by_id = {row["id"]: row for row in commanderies}
    if len(province_by_id) != len(provinces):
        raise ValueError("provinceRecords contains duplicate IDs")
    if len(jurisdiction_by_id) != len(jurisdictions):
        raise ValueError("jurisdictionRecords contains duplicate IDs")
    if len(commandery_by_id) != len(commanderies):
        raise ValueError("commanderyRecords contains duplicate IDs")

    jurisdiction_labels: list[str] = []
    commandery_labels: list[str] = []
    for province in provinces:
        jurisdiction_id = province.get("jurisdictionId")
        jurisdiction = jurisdiction_by_id.get(jurisdiction_id)
        if jurisdiction is None:
            raise ValueError(f"province {province.get('id')} has unknown jurisdictionId")
        commandery_id = jurisdiction.get("commanderyId")
        if commandery_id not in commandery_by_id:
            raise ValueError(f"jurisdiction {jurisdiction_id} has unknown commanderyId")
        jurisdiction_labels.append(jurisdiction_id)
        commandery_labels.append(commandery_id)

    city_ids = {row.get("id") for row in _require_records(document, "cities")}
    jurisdiction_ids = {row.get("id") for row in jurisdictions}
    ailao_ids_present = {"X060", "80004"} & (city_ids | jurisdiction_ids)
    ailao_validated = bool(ailao_ids_present)
    if ailao_validated:
        validate_ailao_layers(document)
    province_topology = _component_inventory(
        grid,
        [row["id"] for row in provinces],
        {row["id"]: row["displayName"] for row in provinces},
    )
    province_cell_counts = Counter(
        province_index
        for grid_row in grid
        for province_index in grid_row
        if province_index >= 0
    )
    below_minimum = [
        {
            "id": province["id"],
            "displayName": province["displayName"],
            "cellCount": province_cell_counts[index],
        }
        for index, province in enumerate(provinces)
        if province_cell_counts[index] < 8
    ]
    province_topology = {
        "minimumCellCount": 8,
        "belowMinimumCount": len(below_minimum),
        "belowMinimum": below_minimum,
        "disconnectedCount": province_topology["disconnectedCount"],
        "disconnected": province_topology["disconnected"],
        "fullyEnclosedCount": province_topology["fullyEnclosedCount"],
        "fullyEnclosed": province_topology["fullyEnclosed"],
    }
    jurisdiction_topology = _component_inventory(
        grid,
        jurisdiction_labels,
        {row["id"]: row["displayName"] for row in jurisdictions},
        {row["id"]: row["commanderyId"] for row in jurisdictions},
    )
    commandery_topology = _component_inventory(
        grid,
        commandery_labels,
        {row["id"]: row["displayName"] for row in commanderies},
    )
    single_commanderies = _single_commandery_inventory(
        commanderies, units_document, bindings_document, jurisdiction_by_id
    )
    return {
        "schemaVersion": 1,
        "auditId": "han-administrative-topology-audit-v1",
        "referenceYear": document.get("_meta", {}).get("year"),
        "grid": {"rows": rows, "cols": cols, "connectivity": "FOUR_NEIGHBOR"},
        "policy": {
            "candidateOnly": True,
            "disconnected": "same administrative ID has more than one four-neighbor cell component",
            "fullyEnclosed": "component touches neither outside nor negative owner and has exactly one surrounding administrative ID",
            "singleJurisdiction": "commanderyRecords.jurisdictionIds contains exactly one ID",
            "noAutomaticRepair": True,
        },
        "counts": {
            "province": len(provinces),
            "jurisdiction": len(jurisdictions),
            "commandery": len(commanderies),
        },
        "provinceTopology": province_topology,
        "jurisdictionTopology": jurisdiction_topology,
        "commanderyTopology": commandery_topology,
        "singleJurisdictionCommanderyCount": len(single_commanderies),
        "singleJurisdictionCommanderies": single_commanderies,
        "ethnicAdministrativeCoexistence": ([{
                "ethnicRegionId": "X060",
                "ethnicNameCh": "哀牢",
                "administrativeCountyId": "80004",
                "administrativeCountyNameCh": "哀牢县",
                "decision": "DISTINCT_AXES_NO_AUTOMATIC_REPLACEMENT",
            }]
            if ailao_validated
            else []
        ),
    }


def _load(path: Path) -> dict:
    document = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(document, dict):
        raise ValueError(f"{path} must contain a JSON object")
    return document


def _render(document: dict) -> str:
    return json.dumps(document, ensure_ascii=False, indent=2, sort_keys=False) + "\n"


def materialize(tiles: Path, units: Path, bindings: Path) -> dict:
    result = audit_document(_load(tiles), _load(units), _load(bindings))
    result["inputs"] = {
        "hanTiles": {"sha256": _sha256(tiles)},
        "administrativeUnits": {"sha256": _sha256(units)},
        "administrativePlaceBindings": {"sha256": _sha256(bindings)},
    }
    return result


def parse_args(argv: Iterable[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tiles", type=Path, default=DEFAULT_TILES)
    parser.add_argument("--units", type=Path, default=DEFAULT_UNITS)
    parser.add_argument("--bindings", type=Path, default=DEFAULT_BINDINGS)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--check", action="store_true")
    return parser.parse_args(argv)


def main(argv: Iterable[str] | None = None) -> int:
    args = parse_args(argv)
    try:
        result = materialize(args.tiles.resolve(), args.units.resolve(), args.bindings.resolve())
        rendered = _render(result)
        if args.check:
            if not args.output.exists() or args.output.read_text(encoding="utf-8") != rendered:
                raise ValueError(f"audit snapshot drift: regenerate {args.output}")
        else:
            args.output.parent.mkdir(parents=True, exist_ok=True)
            args.output.write_text(rendered, encoding="utf-8")
    except (OSError, ValueError, json.JSONDecodeError) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
