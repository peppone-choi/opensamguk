"""Fail-closed contracts for spatial provinces, counties, and commanderies."""

from __future__ import annotations

import argparse
from collections import Counter, deque
from dataclasses import asdict, dataclass
import json
from pathlib import Path
from typing import Any


COMMANDERY_GEOMETRY_KINDS = frozenset({"COMMANDERY", "KINGDOM", "METROPOLITAN"})


@dataclass(frozen=True)
class HierarchyAudit:
    province_count: int
    jurisdiction_count: int
    parent_count: int
    unassigned_province_ids: tuple[str, ...]
    direct_territory_ids: tuple[str, ...]
    duplicate_seat_place_ids: tuple[str, ...]
    enclosed_non_playable_land_components: int


@dataclass(frozen=True)
class TransitionDebtAudit:
    province_count: int
    parent_count: int
    direct_territory_ids: tuple[str, ...]
    parents_without_county_ids: tuple[str, ...]
    city_kind_counts: dict[str, int]
    enclosed_non_playable_land_components: int


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


def _enclosed_non_playable_land_components(document: dict[str, Any]) -> int:
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

    remaining_land = {
        index
        for index in unowned - outside
        if terrain[index // cols][index % cols] not in water_codes
    }
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
    return components


def audit_hierarchy(document: dict[str, Any]) -> HierarchyAudit:
    provinces = _require_rows(document, "provinceRecords")
    jurisdictions = _require_rows(document, "jurisdictionRecords")
    parents = _require_rows(document, "parentRegions")
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

    return HierarchyAudit(
        province_count=len(provinces),
        jurisdiction_count=len(jurisdictions),
        parent_count=len(parents),
        unassigned_province_ids=tuple(unassigned),
        direct_territory_ids=tuple(direct),
        duplicate_seat_place_ids=tuple(duplicate_seats),
        enclosed_non_playable_land_components=(
            _enclosed_non_playable_land_components(document)
        ),
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
        enclosed_non_playable_land_components=(
            _enclosed_non_playable_land_components(document)
        ),
    )
def validate_hierarchy(document: dict[str, Any]) -> HierarchyAudit:
    audit = audit_hierarchy(document)
    provinces = _require_rows(document, "provinceRecords")
    jurisdictions = _require_rows(document, "jurisdictionRecords")
    parents = _require_rows(document, "parentRegions")

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

    parent_ids = {row.get("id") for row in parents if isinstance(row.get("id"), str)}
    if len(parent_ids) != len(parents):
        raise ValueError("parent region IDs must be unique non-empty strings")
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

    jurisdictions_by_parent: Counter[str] = Counter()
    for jurisdiction_id, jurisdiction in jurisdiction_by_id.items():
        parent_id = jurisdiction.get("parentRegionId")
        if parent_id not in parent_ids:
            raise ValueError(f"jurisdiction {jurisdiction_id} references unknown parent {parent_id}")
        jurisdictions_by_parent[parent_id] += 1
        province_ids = jurisdiction.get("provinceIds")
        if not isinstance(province_ids, list) or not province_ids:
            raise ValueError(f"jurisdiction {jurisdiction_id} has no province")
        if len(set(province_ids)) != len(province_ids):
            raise ValueError(f"jurisdiction {jurisdiction_id} repeats a province")
        for province_id in province_ids:
            province = province_by_id.get(province_id)
            if province is None:
                raise ValueError(f"jurisdiction {jurisdiction_id} references unknown province {province_id}")
            if province.get("jurisdictionId") != jurisdiction_id:
                raise ValueError(f"province {province_id} jurisdiction disagrees")
            if province.get("parentRegionId") != parent_id:
                raise ValueError(
                    f"province {province_id} parent {province.get('parentRegionId')} disagrees "
                    f"with jurisdiction parent {parent_id}"
                )

    for province_id, province in province_by_id.items():
        jurisdiction_id = province["jurisdictionId"]
        jurisdiction = jurisdiction_by_id.get(jurisdiction_id)
        if jurisdiction is None:
            raise ValueError(f"province {province_id} references unknown jurisdiction {jurisdiction_id}")
        if province_id not in jurisdiction.get("provinceIds", []):
            raise ValueError(f"jurisdiction {jurisdiction_id} omits province {province_id}")

    parent_without_jurisdiction = next((
        parent_id for parent_id in sorted(parent_ids) if jurisdictions_by_parent[parent_id] == 0
    ), None)
    if parent_without_jurisdiction is not None:
        raise ValueError(f"parent {parent_without_jurisdiction} has no jurisdiction")
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
    audit = validate_hierarchy(document) if args.strict else audit_transition_debt(document)
    print(json.dumps(asdict(audit), ensure_ascii=False, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
