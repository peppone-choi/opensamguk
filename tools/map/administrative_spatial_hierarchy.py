"""Fail-closed contracts for spatial provinces, counties, and commanderies."""

from __future__ import annotations

import argparse
from collections import Counter, deque
from dataclasses import asdict, dataclass
import json
from pathlib import Path
from typing import Any


COMMANDERY_GEOMETRY_KINDS = frozenset({"COMMANDERY", "KINGDOM", "METROPOLITAN"})
SPATIAL_PROVINCE_KINDS = frozenset({"SPATIAL_PROVINCE"})
JURISDICTION_KINDS = frozenset({"COUNTY", "MARQUISATE", "EXTERNAL_SETTLEMENT"})
COMMANDERY_KINDS = frozenset({"COMMANDERY", "KINGDOM", "METROPOLITAN"})
SETTLEMENT_ROLES = frozenset({
    "COUNTY_SEAT", "COMMANDERY_SEAT", "PROVINCIAL_SEAT", "IMPERIAL_CAPITAL",
    "FACTION_CAPITAL", "PORT", "FERRY", "PASS", "TRIBAL_SETTLEMENT",
})
CANONICAL_PROVINCE_COUNT = 1_524
CANONICAL_COMMANDERY_COUNT = 172
CANONICAL_MIN_PROVINCE_CELLS = 8


@dataclass(frozen=True)
class HierarchyAudit:
    province_count: int
    jurisdiction_count: int
    commandery_count: int
    unassigned_province_ids: tuple[str, ...]
    direct_territory_ids: tuple[str, ...]
    duplicate_seat_place_ids: tuple[str, ...]
    enclosed_non_playable_land_components: int
    narrow_non_playable_land_tendrils: int


@dataclass(frozen=True)
class TransitionDebtAudit:
    province_count: int
    parent_count: int
    direct_territory_ids: tuple[str, ...]
    parents_without_county_ids: tuple[str, ...]
    city_kind_counts: dict[str, int]
    enclosed_non_playable_land_components: int
    narrow_non_playable_land_tendrils: int


def _require_rows(document: dict[str, Any], key: str) -> list[dict[str, Any]]:
    value = document.get(key)
    if not isinstance(value, list) or any(not isinstance(row, dict) for row in value):
        raise ValueError(f"{key} must be an array of objects")
    return value


def _expand_rle(runs: Any, expected: int) -> list[int]:
    if not isinstance(runs, list):
        raise ValueError("owner must be a run-length array")
    values: list[int] = []
    for run in runs:
        if (
            not isinstance(run, list)
            or len(run) != 2
            or type(run[0]) is not int
            or type(run[1]) is not int
            or run[1] <= 0
        ):
            raise ValueError("owner contains an invalid run")
        values.extend([run[0]] * run[1])
        if len(values) > expected:
            raise ValueError("owner exceeds map dimensions")
    if len(values) != expected:
        raise ValueError("owner does not match map dimensions")
    return values


def _non_playable_land_topology(document: dict[str, Any]) -> tuple[int, int]:
    meta = document.get("_meta")
    if not isinstance(meta, dict):
        raise ValueError("_meta must be an object")
    cols, rows = meta.get("cols"), meta.get("rows")
    if type(cols) is not int or type(rows) is not int or cols <= 0 or rows <= 0:
        raise ValueError("map dimensions must be positive integers")
    terrain = document.get("terrain")
    if (
        not isinstance(terrain, list)
        or len(terrain) != rows
        or any(not isinstance(row, str) or len(row) != cols for row in terrain)
    ):
        raise ValueError("terrain does not match map dimensions")
    owner = _expand_rle(document.get("owner"), cols * rows)
    unowned = {index for index, value in enumerate(owner) if value < 0}
    legend = meta.get("terrainLegend")
    if not isinstance(legend, dict):
        raise ValueError("terrainLegend must be an object")
    water_codes = {
        str(code) for code, label in legend.items() if label in {"SEA", "LAKE"}
    }

    outside: set[int] = set()
    queue: deque[int] = deque()
    for row in range(rows):
        for col in range(cols):
            if row not in (0, rows - 1) and col not in (0, cols - 1):
                continue
            index = row * cols + col
            if index in unowned and index not in outside:
                outside.add(index)
                queue.append(index)
    while queue:
        index = queue.popleft()
        row, col = divmod(index, cols)
        for drow, dcol in ((-1, 0), (0, 1), (1, 0), (0, -1)):
            next_row, next_col = row + drow, col + dcol
            if not (0 <= next_row < rows and 0 <= next_col < cols):
                continue
            next_index = next_row * cols + next_col
            if next_index in unowned and next_index not in outside:
                outside.add(next_index)
                queue.append(next_index)

    unowned_land = {
        index
        for index in unowned
        if terrain[index // cols][index % cols] not in water_codes
    }
    remaining_land = set(unowned_land - outside)
    components = 0
    while remaining_land:
        components += 1
        queue.append(remaining_land.pop())
        while queue:
            index = queue.popleft()
            row, col = divmod(index, cols)
            for drow, dcol in ((-1, 0), (0, 1), (1, 0), (0, -1)):
                next_row, next_col = row + drow, col + dcol
                if not (0 <= next_row < rows and 0 <= next_col < cols):
                    continue
                next_index = next_row * cols + next_col
                if next_index in remaining_land:
                    remaining_land.remove(next_index)
                    queue.append(next_index)
    def land_neighbors(index: int) -> list[int]:
        row, col = divmod(index, cols)
        result = []
        for drow, dcol in ((-1, 0), (0, 1), (1, 0), (0, -1)):
            next_row, next_col = row + drow, col + dcol
            if 0 <= next_row < rows and 0 <= next_col < cols:
                next_index = next_row * cols + next_col
                if next_index in unowned_land:
                    result.append(next_index)
        return result

    def touches_outside(index: int) -> bool:
        row, col = divmod(index, cols)
        if row in (0, rows - 1) or col in (0, cols - 1):
            return True
        return any(
            terrain[next_row][next_col] in water_codes
            for next_row, next_col in (
                (row - 1, col), (row, col + 1), (row + 1, col), (row, col - 1)
            )
        )

    tendrils = 0
    unvisited = set(unowned_land)
    while unvisited:
        component = {unvisited.pop()}
        queue.extend(component)
        while queue:
            index = queue.popleft()
            for next_index in land_neighbors(index):
                if next_index in unvisited:
                    unvisited.remove(next_index)
                    component.add(next_index)
                    queue.append(next_index)
        degree = {index: len([n for n in land_neighbors(index) if n in component]) for index in component}
        component_is_tendril = False
        for leaf in sorted(index for index in component if degree[index] <= 1 and not touches_outside(index)):
            previous: int | None = None
            current = leaf
            path_length = 1
            while True:
                if touches_outside(current):
                    if path_length >= 3:
                        component_is_tendril = True
                    break
                next_nodes = [
                    index for index in land_neighbors(current)
                    if index in component and index != previous
                ]
                if len(next_nodes) != 1:
                    break
                next_index = next_nodes[0]
                if degree[next_index] > 2:
                    break
                previous, current = current, next_index
                path_length += 1
            if component_is_tendril:
                break
        if not component_is_tendril:
            boundary = {index for index in component if touches_outside(index)}
            depths = {index: 0 for index in boundary}
            queue.extend(sorted(boundary))
            while queue:
                index = queue.popleft()
                for next_index in land_neighbors(index):
                    if next_index in component and next_index not in depths:
                        depths[next_index] = depths[index] + 1
                        queue.append(next_index)
            # Work with local contour segments, not the width of the entire
            # exterior component. A two-cell branch attached to a broad coast
            # must not be hidden merely because depth zero is very wide.
            previous_segments: list[tuple[frozenset[int], int]] = []
            for depth in range(max(depths.values(), default=-1) + 1):
                remaining_at_depth = {
                    index for index, value in depths.items() if value == depth
                }
                segments: list[frozenset[int]] = []
                while remaining_at_depth:
                    segment = {remaining_at_depth.pop()}
                    segment_queue = deque(segment)
                    while segment_queue:
                        index = segment_queue.popleft()
                        for next_index in land_neighbors(index):
                            if (
                                next_index in remaining_at_depth
                                and depths[next_index] == depth
                            ):
                                remaining_at_depth.remove(next_index)
                                segment.add(next_index)
                                segment_queue.append(next_index)
                    if len(segment) <= 2:
                        segments.append(frozenset(segment))

                current_segments: list[tuple[frozenset[int], int]] = []
                for segment in segments:
                    connected_lengths = [
                        chain_length
                        for previous, chain_length in previous_segments
                        if any(
                            neighbor in previous
                            for index in segment
                            for neighbor in land_neighbors(index)
                        )
                    ]
                    chain_length = 1 + max(connected_lengths, default=0)
                    current_segments.append((segment, chain_length))
                    if chain_length >= 3:
                        component_is_tendril = True
                        break
                if component_is_tendril:
                    break
                previous_segments = current_segments
        if component_is_tendril:
            tendrils += 1
    return components, tendrils


def audit_hierarchy(document: dict[str, Any]) -> HierarchyAudit:
    provinces = _require_rows(document, "provinceRecords")
    jurisdictions = _require_rows(document, "jurisdictionRecords")
    commanderies = _require_rows(document, "commanderyRecords")
    settlements = _require_rows(document, "settlementRecords")

    unassigned = sorted(
        str(row.get("id", ""))
        for row in provinces
        if not isinstance(row.get("jurisdictionId"), str) or not row["jurisdictionId"]
    )
    direct = sorted(
        str(row.get("id", ""))
        for row in provinces
        if row.get("kind") == "DIRECT_TERRITORY"
    )
    seat_counts = Counter(
        row.get("seatPlaceId")
        for row in settlements
        if isinstance(row.get("seatPlaceId"), str) and row["seatPlaceId"]
    )
    duplicate_seats = sorted(seat for seat, count in seat_counts.items() if count > 1)

    enclosed_components, narrow_tendrils = _non_playable_land_topology(document)
    return HierarchyAudit(
        province_count=len(provinces),
        jurisdiction_count=len(jurisdictions),
        commandery_count=len(commanderies),
        unassigned_province_ids=tuple(unassigned),
        direct_territory_ids=tuple(direct),
        duplicate_seat_place_ids=tuple(duplicate_seats),
        enclosed_non_playable_land_components=enclosed_components,
        narrow_non_playable_land_tendrils=narrow_tendrils,
    )


def audit_transition_debt(document: dict[str, Any]) -> TransitionDebtAudit:
    """Describe the legacy mixed hierarchy without accepting it as canonical."""
    provinces = _require_rows(document, "provinceRecords")
    parents = _require_rows(document, "parentRegions")
    cities = _require_rows(document, "cities")
    county_parents = {
        row.get("parentRegionId")
        for row in provinces
        if row.get("kind") == "COUNTY" and isinstance(row.get("parentRegionId"), str)
    }
    parent_ids = {
        row.get("id")
        for row in parents
        if isinstance(row.get("id"), str) and row["id"]
    }
    city_kinds = Counter(
        row.get("kind")
        for row in cities
        if isinstance(row.get("kind"), str) and row["kind"]
    )
    enclosed_components, narrow_tendrils = _non_playable_land_topology(document)
    return TransitionDebtAudit(
        province_count=len(provinces),
        parent_count=len(parents),
        direct_territory_ids=tuple(sorted(
            str(row.get("id", ""))
            for row in provinces
            if row.get("kind") == "DIRECT_TERRITORY"
        )),
        parents_without_county_ids=tuple(sorted(parent_ids - county_parents)),
        city_kind_counts=dict(sorted(city_kinds.items())),
        enclosed_non_playable_land_components=enclosed_components,
        narrow_non_playable_land_tendrils=narrow_tendrils,
    )
def validate_hierarchy(
    document: dict[str, Any],
    *,
    expected_province_count: int | None = None,
    expected_commandery_count: int | None = None,
    expected_min_province_cells: int = 1,
) -> HierarchyAudit:
    audit = audit_hierarchy(document)
    provinces = _require_rows(document, "provinceRecords")
    jurisdictions = _require_rows(document, "jurisdictionRecords")
    commanderies = _require_rows(document, "commanderyRecords")
    settlements = _require_rows(document, "settlementRecords")

    if expected_province_count is not None and audit.province_count != expected_province_count:
        raise ValueError(
            f"province count {audit.province_count} does not match {expected_province_count}"
        )
    if expected_commandery_count is not None and audit.commandery_count != expected_commandery_count:
        raise ValueError(
            f"commandery count {audit.commandery_count} does not match {expected_commandery_count}"
        )

    commandery_geometry = next((
        str(row.get("id", ""))
        for row in provinces
        if row.get("kind") in COMMANDERY_GEOMETRY_KINDS
        or str(row.get("id", "")).startswith("COMMANDERY-")
    ), None)
    if commandery_geometry is not None:
        raise ValueError(f"commandery geometry {commandery_geometry} is forbidden")
    if audit.direct_territory_ids:
        raise ValueError(f"direct territory {audit.direct_territory_ids[0]} is forbidden")
    if audit.unassigned_province_ids:
        raise ValueError(f"unassigned playable province {audit.unassigned_province_ids[0]}")
    if audit.duplicate_seat_place_ids:
        raise ValueError(f"duplicate physical settlement {audit.duplicate_seat_place_ids[0]}")
    if audit.enclosed_non_playable_land_components:
        raise ValueError("enclosed non-playable land is forbidden")
    if audit.narrow_non_playable_land_tendrils:
        raise ValueError("narrow non-playable land tendril is forbidden")

    jurisdiction_by_id = {
        row.get("id"): row
        for row in jurisdictions
        if isinstance(row.get("id"), str) and row.get("id")
    }
    if len(jurisdiction_by_id) != len(jurisdictions):
        raise ValueError("jurisdiction IDs must be unique non-empty strings")
    province_by_id = {
        row.get("id"): row
        for row in provinces
        if isinstance(row.get("id"), str) and row.get("id")
    }
    if len(province_by_id) != len(provinces):
        raise ValueError("province IDs must be unique non-empty strings")
    commandery_by_id = {
        row.get("id"): row
        for row in commanderies
        if isinstance(row.get("id"), str) and row.get("id")
    }
    if len(commandery_by_id) != len(commanderies):
        raise ValueError("commandery IDs must be unique non-empty strings")
    settlement_by_id = {
        row.get("id"): row
        for row in settlements
        if isinstance(row.get("id"), str) and row.get("id")
    }
    if len(settlement_by_id) != len(settlements):
        raise ValueError("settlement IDs must be unique non-empty strings")

    for province_id, province in province_by_id.items():
        kind = province.get("kind")
        if kind not in SPATIAL_PROVINCE_KINDS:
            raise ValueError(f"province {province_id} has invalid kind {kind}")

    meta = document["_meta"]
    owner = _expand_rle(document.get("owner"), meta["cols"] * meta["rows"])
    owner_counts = Counter(index for index in owner if index >= 0)
    unknown_owner = next((index for index in sorted(owner_counts) if index >= len(provinces)), None)
    if unknown_owner is not None:
        raise ValueError(f"owner references unknown province index {unknown_owner}")
    for index, province in enumerate(provinces):
        cell_count = owner_counts[index]
        if cell_count == 0:
            raise ValueError(f"province {province['id']} has no owner cell")
        if cell_count < expected_min_province_cells:
            raise ValueError(
                f"province {province['id']} has {cell_count} cells; "
                f"minimum is {expected_min_province_cells}"
            )

    seat_place_counts = Counter(
        row.get("seatPlaceId")
        for row in jurisdictions
        if isinstance(row.get("seatPlaceId"), str) and row["seatPlaceId"]
    )
    repeated_jurisdiction_seat = next((
        seat for seat, count in sorted(seat_place_counts.items()) if count > 1
    ), None)
    if repeated_jurisdiction_seat is not None:
        raise ValueError(f"seat {repeated_jurisdiction_seat} is assigned to multiple jurisdictions")
    commandery_seat_jurisdiction_ids = {
        row.get("seatJurisdictionId")
        for row in commanderies
        if isinstance(row.get("seatJurisdictionId"), str) and row["seatJurisdictionId"]
    }

    settlement_by_seat: dict[str, dict[str, Any]] = {}
    cols, rows = meta["cols"], meta["rows"]
    for settlement_id, settlement in settlement_by_id.items():
        jurisdiction_id = settlement.get("jurisdictionId")
        if jurisdiction_id not in jurisdiction_by_id:
            raise ValueError(
                f"settlement {settlement_id} references unknown jurisdiction {jurisdiction_id}"
            )
        seat_place_id = settlement.get("seatPlaceId")
        if not isinstance(seat_place_id, str) or not seat_place_id:
            raise ValueError(f"settlement {settlement_id} has no seatPlaceId")
        settlement_by_seat[seat_place_id] = settlement
        col, row = settlement.get("col"), settlement.get("row")
        if type(col) is not int or type(row) is not int or not (0 <= col < cols and 0 <= row < rows):
            raise ValueError(f"settlement {settlement_id} has invalid coordinates")
        roles = settlement.get("roles")
        if not isinstance(roles, list) or not roles or len(set(roles)) != len(roles):
            raise ValueError(f"settlement {settlement_id} has invalid roles")
        invalid_role = next((role for role in roles if role not in SETTLEMENT_ROLES), None)
        if invalid_role is not None:
            raise ValueError(f"settlement {settlement_id} has invalid role {invalid_role}")

    for jurisdiction_id, jurisdiction in jurisdiction_by_id.items():
        kind = jurisdiction.get("kind")
        if kind not in JURISDICTION_KINDS:
            raise ValueError(f"jurisdiction {jurisdiction_id} has invalid kind {kind}")
        commandery_id = jurisdiction.get("commanderyId")
        if commandery_id not in commandery_by_id:
            raise ValueError(
                f"jurisdiction {jurisdiction_id} references unknown commandery {commandery_id}"
            )
        province_ids = jurisdiction.get("provinceIds")
        if not isinstance(province_ids, list) or not province_ids:
            raise ValueError(f"jurisdiction {jurisdiction_id} has no province")
        if len(set(province_ids)) != len(province_ids):
            raise ValueError(f"jurisdiction {jurisdiction_id} repeats a province")
        seat_place_id = jurisdiction.get("seatPlaceId")
        settlement = settlement_by_seat.get(seat_place_id)
        if settlement is None:
            raise ValueError(f"jurisdiction {jurisdiction_id} seat {seat_place_id} is missing")
        if settlement.get("jurisdictionId") != jurisdiction_id:
            raise ValueError(f"jurisdiction {jurisdiction_id} seat {seat_place_id} belongs elsewhere")
        roles = set(settlement["roles"])
        if kind == "EXTERNAL_SETTLEMENT":
            if not roles & {"COUNTY_SEAT", "TRIBAL_SETTLEMENT"}:
                raise ValueError(
                    f"jurisdiction {jurisdiction_id} seat lacks a jurisdiction-seat role"
                )
        elif "COUNTY_SEAT" not in roles:
            raise ValueError(f"jurisdiction {jurisdiction_id} seat lacks COUNTY_SEAT")
        if jurisdiction_id in commandery_seat_jurisdiction_ids:
            if "COMMANDERY_SEAT" not in roles:
                commandery_id = jurisdiction["commanderyId"]
                raise ValueError(f"commandery {commandery_id} seat lacks COMMANDERY_SEAT")
        elif "COMMANDERY_SEAT" in roles:
            raise ValueError(f"jurisdiction {jurisdiction_id} is not a commandery seat")
        for province_id in province_ids:
            province = province_by_id.get(province_id)
            if province is None:
                raise ValueError(f"jurisdiction {jurisdiction_id} references unknown province {province_id}")
            if province.get("jurisdictionId") != jurisdiction_id:
                raise ValueError(f"province {province_id} jurisdiction disagrees")
        seat_owner_index = owner[settlement["row"] * cols + settlement["col"]]
        seat_province_id = (
            provinces[seat_owner_index]["id"] if seat_owner_index >= 0 else None
        )
        if seat_province_id not in province_ids:
            raise ValueError(
                f"settlement {settlement['id']} is outside jurisdiction {jurisdiction_id}"
            )

    for province_id, province in province_by_id.items():
        jurisdiction_id = province["jurisdictionId"]
        jurisdiction = jurisdiction_by_id.get(jurisdiction_id)
        if jurisdiction is None:
            raise ValueError(f"province {province_id} references unknown jurisdiction {jurisdiction_id}")
        if province_id not in jurisdiction.get("provinceIds", []):
            raise ValueError(f"jurisdiction {jurisdiction_id} omits province {province_id}")

    commandery_memberships: Counter[str] = Counter(
        jurisdiction_id
        for commandery in commanderies
        if isinstance(commandery.get("jurisdictionIds"), list)
        for jurisdiction_id in commandery["jurisdictionIds"]
        if isinstance(jurisdiction_id, str)
    )
    repeated_commandery_membership = next((
        jurisdiction_id
        for jurisdiction_id, count in sorted(commandery_memberships.items())
        if count > 1
    ), None)
    if repeated_commandery_membership is not None:
        raise ValueError(
            f"jurisdiction {repeated_commandery_membership} belongs to multiple commanderies"
        )
    for commandery_id, commandery in commandery_by_id.items():
        kind = commandery.get("kind")
        if kind not in COMMANDERY_KINDS:
            raise ValueError(f"commandery {commandery_id} has invalid kind {kind}")
        jurisdiction_ids = commandery.get("jurisdictionIds")
        if not isinstance(jurisdiction_ids, list) or not jurisdiction_ids:
            raise ValueError(f"commandery {commandery_id} has no jurisdiction")
        if len(set(jurisdiction_ids)) != len(jurisdiction_ids):
            raise ValueError(f"commandery {commandery_id} repeats a jurisdiction")
        for jurisdiction_id in jurisdiction_ids:
            jurisdiction = jurisdiction_by_id.get(jurisdiction_id)
            if jurisdiction is None:
                raise ValueError(
                    f"commandery {commandery_id} references unknown jurisdiction {jurisdiction_id}"
                )
            if jurisdiction.get("commanderyId") != commandery_id:
                raise ValueError(f"jurisdiction {jurisdiction_id} commandery disagrees")
        seat_jurisdiction_id = commandery.get("seatJurisdictionId")
        if seat_jurisdiction_id not in jurisdiction_ids:
            raise ValueError(
                f"commandery {commandery_id} seat jurisdiction {seat_jurisdiction_id} is not a member"
            )
    for jurisdiction_id in sorted(jurisdiction_by_id):
        count = commandery_memberships[jurisdiction_id]
        if count == 0:
            raise ValueError(f"jurisdiction {jurisdiction_id} belongs to no commandery")
        if count > 1:
            raise ValueError(f"jurisdiction {jurisdiction_id} belongs to multiple commanderies")
    return audit


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "path",
        nargs="?",
        type=Path,
        default=Path("data/map/han-tiles.json"),
    )
    parser.add_argument(
        "--strict",
        action="store_true",
        help="validate the canonical hierarchy after jurisdiction materialization",
    )
    args = parser.parse_args()
    document = json.loads(args.path.read_text())
    audit = (
        validate_hierarchy(
            document,
            expected_province_count=CANONICAL_PROVINCE_COUNT,
            expected_commandery_count=CANONICAL_COMMANDERY_COUNT,
            expected_min_province_cells=CANONICAL_MIN_PROVINCE_CELLS,
        )
        if args.strict
        else audit_transition_debt(document)
    )
    print(json.dumps(asdict(audit), ensure_ascii=False, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
