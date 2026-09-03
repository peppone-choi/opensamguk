"""Deterministic province geometry construction for the 220 world map.

Historical masks are immutable ownership.  Modern administrative polygons are a
geometry scaffold only: seeds split a polygon within its own mask, while seedless
polygons merge to an adjacent province in the same historical parent region or
become explicitly named direct territory.
"""

from __future__ import annotations

import hashlib
import heapq
import json
import re
from dataclasses import dataclass
from typing import Any, Mapping, Sequence

import numpy as np

try:
    from tools.map.province_quality import (
        allocate_parent_province_counts,
        balanced_parent_labels,
        repair_label_connectivity,
    )
except ModuleNotFoundError:  # pragma: no cover - direct script compatibility
    from province_quality import (
        allocate_parent_province_counts,
        balanced_parent_labels,
        repair_label_connectivity,
    )


@dataclass(frozen=True)
class ProvinceSeed:
    id: str
    display_name: str
    name_ch: str
    administrative_system: str
    kind: str
    parent_region_id: str
    row: int
    col: int
    city_index: int | None
    geometry_basis: str
    confidence: str


@dataclass(frozen=True)
class ProvinceRecord:
    id: str
    display_name: str
    name_ch: str
    administrative_system: str
    kind: str
    parent_region_id: str
    city_index: int | None
    geometry_basis: str
    confidence: str


@dataclass(frozen=True)
class ParentRegionRecord:
    id: str
    display_name: str
    administrative_system: str
    name_ch: str = ""


@dataclass(frozen=True)
class ProvinceAuditDecision:
    kind: str
    source_feature_id: str
    province_ids: tuple[str, ...]


@dataclass(frozen=True)
class ProvinceAudit:
    decisions: tuple[ProvinceAuditDecision, ...]


@dataclass(frozen=True)
class ProvinceBuildResult:
    owner: np.ndarray
    province_records: tuple[ProvinceRecord, ...]
    parent_regions: tuple[ParentRegionRecord, ...]
    audit: ProvinceAudit

    def index_of(self, province_id: str) -> int:
        for index, record in enumerate(self.province_records):
            if record.id == province_id:
                return index
        raise KeyError(province_id)


@dataclass(frozen=True)
class JurisdictionAssignmentResult:
    province_records: tuple[dict[str, Any], ...]
    jurisdiction_records: tuple[dict[str, Any], ...]
    commandery_records: tuple[dict[str, Any], ...]


JURISDICTION_RECOVERY_SOURCE_REPOSITORY = "https://github.com/peppone-choi/shiliao"
JURISDICTION_RECOVERY_SOURCE_REVISION = "1330cfe46186bc159184a361467563dd3339b38f"
JURISDICTION_RECOVERY_EVIDENCE_SHA256 = (
    "05cb0fa944a9dfe153f5514b03fc8bbc0df74ff4a548a99b4fa2e03a616e005b"
)


def validate_jurisdiction_recovery_document(document: Mapping[str, Any]) -> list[Mapping[str, Any]]:
    """Accept only the recovery evidence reviewed at the pinned shiliao revision."""
    if document.get("schemaVersion") != 1 or document.get("artifactId") != (
        "han-jurisdiction-seat-recoveries-v1"
    ):
        raise ValueError("jurisdiction recovery document identity is invalid")
    if document.get("sourceRepository") != JURISDICTION_RECOVERY_SOURCE_REPOSITORY:
        raise ValueError("jurisdiction recovery source repository is invalid")
    if document.get("sourceRevision") != JURISDICTION_RECOVERY_SOURCE_REVISION:
        raise ValueError("jurisdiction recovery source revision is invalid")
    recoveries = document.get("recoveries")
    if not isinstance(recoveries, list):
        raise ValueError("jurisdiction recovery rows are missing")
    evidence_blob = json.dumps(
        recoveries, ensure_ascii=False, sort_keys=True, separators=(",", ":")
    ).encode("utf-8")
    if hashlib.sha256(evidence_blob).hexdigest() != JURISDICTION_RECOVERY_EVIDENCE_SHA256:
        raise ValueError("jurisdiction recovery evidence digest is invalid")
    return recoveries


def validate_materialized_hierarchy(
    province_records: Sequence[Mapping[str, Any]],
    jurisdiction_records: Sequence[Mapping[str, Any]],
    commandery_records: Sequence[Mapping[str, Any]],
) -> bool:
    """Validate unique IDs and exact forward/inverse hierarchy membership."""
    def records_by_id(records: Sequence[Mapping[str, Any]], label: str) -> dict[str, Mapping[str, Any]]:
        result: dict[str, Mapping[str, Any]] = {}
        for record in records:
            record_id = record.get("id")
            if not isinstance(record_id, str) or not record_id:
                raise ValueError(f"every {label} record requires a stable string id")
            if record_id in result:
                raise ValueError(f"duplicate {label} record id: {record_id}")
            result[record_id] = record
        return result

    provinces = records_by_id(province_records, "province")
    jurisdictions = records_by_id(jurisdiction_records, "jurisdiction")
    commanderies = records_by_id(commandery_records, "commandery")
    inverse_provinces: dict[str, list[str]] = {record_id: [] for record_id in jurisdictions}
    for province_id, province in provinces.items():
        jurisdiction_id = province.get("jurisdictionId")
        jurisdiction = jurisdictions.get(jurisdiction_id)
        if jurisdiction is None:
            raise ValueError(f"province references a missing jurisdiction: {province_id}")
        if jurisdiction.get("commanderyId") != province.get("parentRegionId"):
            raise ValueError(f"province crosses its commandery jurisdiction: {province_id}")
        inverse_provinces[jurisdiction_id].append(province_id)
    for jurisdiction_id, jurisdiction in jurisdictions.items():
        expected = sorted(inverse_provinces[jurisdiction_id])
        if not expected or jurisdiction.get("provinceIds") != expected:
            raise ValueError(f"jurisdiction province inverse membership mismatch: {jurisdiction_id}")

    inverse_jurisdictions: dict[str, list[str]] = {record_id: [] for record_id in commanderies}
    for jurisdiction_id, jurisdiction in jurisdictions.items():
        commandery_id = jurisdiction.get("commanderyId")
        if commandery_id not in commanderies:
            raise ValueError(f"jurisdiction references a missing commandery: {jurisdiction_id}")
        inverse_jurisdictions[commandery_id].append(jurisdiction_id)
    for commandery_id, commandery in commanderies.items():
        expected = sorted(inverse_jurisdictions[commandery_id])
        if not expected or commandery.get("jurisdictionIds") != expected:
            raise ValueError(f"commandery jurisdiction inverse membership mismatch: {commandery_id}")
        if commandery.get("seatJurisdictionId") not in expected:
            raise ValueError(f"commandery seat jurisdiction is not a member: {commandery_id}")
    return True


def infer_commandery_kind(
    parent: Mapping[str, Any], seat_city: Mapping[str, Any]
) -> str:
    """Infer one canonical parent-unit kind from its name and reviewed seat."""
    name_ch = str(parent.get("nameCh", ""))
    if name_ch.endswith("屬國"):
        return "COMMANDERY"
    if seat_city.get("kind") == "KINGDOM" or name_ch.endswith("國"):
        return "KINGDOM"
    if name_ch.endswith("尹"):
        return "METROPOLITAN"
    return "COMMANDERY"


def assign_province_jurisdictions(
    owner: Sequence[Sequence[int]],
    province_records: Sequence[Mapping[str, Any]],
    parent_regions: Sequence[Mapping[str, Any]],
    cities: Sequence[Mapping[str, Any]],
    parent_seats: Sequence[int] | None = None,
    jurisdiction_recoveries: Sequence[Mapping[str, Any]] = (),
) -> JurisdictionAssignmentResult:
    """Bind direct spatial fragments to a county inside the same parent."""
    rows = [list(row) for row in owner]
    cols = len(rows[0]) if rows else 0
    if any(len(row) != cols for row in rows):
        raise ValueError("owner rows must have equal length")

    candidates_by_parent: dict[str, list[int]] = {}
    for index, record in enumerate(province_records):
        if record.get("kind") in {"COUNTY", "SETTLEMENT"}:
            candidates_by_parent.setdefault(str(record.get("parentRegionId")), []).append(index)

    city_ids = {
        city.get("id")
        for city in cities
        if isinstance(city.get("id"), str) and city.get("id")
    }
    recovery_by_parent: dict[str, Mapping[str, Any]] = {}
    external_recovery_parent_ids: set[str] = set()
    for recovery in jurisdiction_recoveries:
        parent_id = recovery.get("parentRegionId")
        jurisdiction_id = recovery.get("jurisdictionId")
        if not isinstance(parent_id, str) or not parent_id:
            raise ValueError("jurisdiction recovery has no parentRegionId")
        if parent_id in recovery_by_parent:
            raise ValueError(f"duplicate jurisdiction recovery for parent: {parent_id}")
        if not isinstance(jurisdiction_id, str) or not jurisdiction_id:
            raise ValueError(f"jurisdiction recovery has no stable ID: {parent_id}")
        if recovery.get("reviewState") != "REVIEWED":
            raise ValueError(f"jurisdiction recovery is not reviewed: {parent_id}")
        if recovery.get("kind") not in {"COUNTY", "MARQUISATE", "EXTERNAL_SETTLEMENT"}:
            raise ValueError(f"jurisdiction recovery has invalid kind: {parent_id}")
        if recovery.get("seatPlaceId") not in city_ids:
            raise ValueError(f"jurisdiction recovery has unknown seat place: {parent_id}")
        citation = recovery.get("sourceCitation")
        if (
            not isinstance(citation, Mapping)
            or not isinstance(citation.get("corpusPath"), str)
            or not citation.get("corpusPath")
            or type(citation.get("line")) is not int
            or citation["line"] <= 0
            or not isinstance(citation.get("quote"), str)
            or not citation.get("quote")
        ):
            raise ValueError(f"jurisdiction recovery has invalid source citation: {parent_id}")
        recovery_by_parent[parent_id] = recovery
    for parent_index, parent in enumerate(parent_regions):
        parent_id = parent.get("id")
        if (
            not isinstance(parent_id, str)
            or candidates_by_parent.get(parent_id)
            or parent_id in recovery_by_parent
            or parent.get("administrativeSystem") == "HAN_COMMANDERY"
        ):
            continue
        seat_index = (
            parent_seats[parent_index]
            if parent_seats is not None and parent_index < len(parent_seats)
            else None
        )
        if type(seat_index) is not int or not 0 <= seat_index < len(cities):
            raise ValueError(f"external parent has no valid seat city: {parent_id}")
        seat = cities[seat_index]
        seat_place_id = seat.get("id")
        if not isinstance(seat_place_id, str) or not seat_place_id:
            raise ValueError(f"external parent seat has no stable place ID: {parent_id}")
        recovery_by_parent[parent_id] = {
            "parentRegionId": parent_id,
            "jurisdictionId": f"JURISDICTION-{parent_id}-SEAT",
            "displayName": seat.get("name") or parent.get("displayName", ""),
            "nameCh": seat.get("nameCh") or parent.get("nameCh", ""),
            "kind": "EXTERNAL_SETTLEMENT",
            "seatPlaceId": seat_place_id,
        }
        external_recovery_parent_ids.add(parent_id)

    direct_boundaries: dict[int, dict[int, int]] = {}
    cells_by_label: dict[int, list[tuple[int, int]]] = {}
    for row_index, row in enumerate(rows):
        for col_index, label in enumerate(row):
            if type(label) is not int or label < 0:
                continue
            if label >= len(province_records):
                raise ValueError("owner contains an out-of-range province index")
            cells_by_label.setdefault(label, []).append((row_index, col_index))
            if province_records[label].get("kind") != "DIRECT_TERRITORY":
                continue
            for next_row, next_col in (
                (row_index - 1, col_index),
                (row_index + 1, col_index),
                (row_index, col_index - 1),
                (row_index, col_index + 1),
            ):
                if not (0 <= next_row < len(rows) and 0 <= next_col < cols):
                    continue
                neighbour = rows[next_row][next_col]
                if neighbour == label or neighbour < 0:
                    continue
                if neighbour >= len(province_records):
                    raise ValueError("owner contains an out-of-range province index")
                if (
                    province_records[neighbour].get("parentRegionId")
                    != province_records[label].get("parentRegionId")
                    or province_records[neighbour].get("kind")
                    not in {"COUNTY", "SETTLEMENT"}
                ):
                    continue
                counts = direct_boundaries.setdefault(label, {})
                counts[neighbour] = counts.get(neighbour, 0) + 1

    resolved = []
    for index, source in enumerate(province_records):
        record = dict(source)
        if record.get("kind") != "DIRECT_TERRITORY":
            record["jurisdictionId"] = record["id"]
            record["assignmentBasis"] = "HISTORICAL_SEAT"
            record["assignmentConfidence"] = record.get("confidence", "REVIEWED")
            resolved.append(record)
            continue
        parent_id = str(record.get("parentRegionId"))
        candidates = candidates_by_parent.get(parent_id, [])
        if not candidates:
            recovery = recovery_by_parent.get(parent_id)
            if recovery is None:
                raise ValueError(f"parent has no county jurisdiction: {parent_id}")
            record["jurisdictionId"] = recovery["jurisdictionId"]
            record["assignmentBasis"] = (
                "EXISTING_EXTERNAL_PARENT_SEAT"
                if parent_id in external_recovery_parent_ids
                else "REVIEWED_PARENT_SEAT_RECOVERY"
            )
            record["assignmentConfidence"] = "REVIEWED"
            resolved.append(record)
            continue
        boundary_counts = direct_boundaries.get(index, {})
        fragment_cells = cells_by_label.get(index, [])
        if not fragment_cells:
            raise ValueError(f"direct territory has no geometry: {record['id']}")

        def seat_distance(candidate: int) -> int:
            city_index = province_records[candidate].get("cityIndex")
            if type(city_index) is not int or not 0 <= city_index < len(cities):
                raise ValueError(
                    f"county has no valid seat city: {province_records[candidate]['id']}"
                )
            seat = cities[city_index]
            seat_row, seat_col = seat.get("row"), seat.get("col")
            if type(seat_row) is not int or type(seat_col) is not int:
                raise ValueError(
                    f"county seat has no valid grid cell: {province_records[candidate]['id']}"
                )
            return min(
                abs(row - seat_row) + abs(col - seat_col)
                for row, col in fragment_cells
            )

        touching = [candidate for candidate in candidates if candidate in boundary_counts]
        if touching:
            selected = min(
                touching,
                key=lambda candidate: (
                    -boundary_counts[candidate],
                    seat_distance(candidate),
                    str(province_records[candidate]["id"]),
                ),
            )
            assignment_basis = "MAX_SHARED_BOUNDARY"
        else:
            distances = []
            for candidate in candidates:
                distance = seat_distance(candidate)
                distances.append((distance, str(province_records[candidate]["id"]), candidate))
            _, _, selected = min(distances)
            assignment_basis = "NEAREST_SEAT_WITHIN_PARENT"
        record["jurisdictionId"] = province_records[selected]["id"]
        record["assignmentBasis"] = assignment_basis
        record["assignmentConfidence"] = "INFERRED"
        resolved.append(record)

    for record in resolved:
        record["kind"] = "SPATIAL_PROVINCE"

    province_ids_by_jurisdiction: dict[str, list[str]] = {}
    for record in resolved:
        province_ids_by_jurisdiction.setdefault(record["jurisdictionId"], []).append(record["id"])

    jurisdiction_records = []
    for source in province_records:
        if source.get("kind") not in {"COUNTY", "SETTLEMENT"}:
            continue
        city_index = source.get("cityIndex")
        if type(city_index) is not int or not 0 <= city_index < len(cities):
            raise ValueError(f"jurisdiction has no valid seat city: {source['id']}")
        city = cities[city_index]
        seat_place_id = city.get("id")
        if not isinstance(seat_place_id, str) or not seat_place_id:
            raise ValueError(f"jurisdiction seat has no stable place ID: {source['id']}")
        jurisdiction_records.append({
            "id": source["id"],
            "displayName": source["displayName"],
            "nameCh": source.get("nameCh", ""),
            "kind": "COUNTY" if source.get("kind") == "COUNTY" else "EXTERNAL_SETTLEMENT",
            "commanderyId": source["parentRegionId"],
            "seatPlaceId": seat_place_id,
            "provinceIds": sorted(province_ids_by_jurisdiction[source["id"]]),
        })
    for recovery in recovery_by_parent.values():
        jurisdiction_id = recovery["jurisdictionId"]
        province_ids = province_ids_by_jurisdiction.get(jurisdiction_id)
        if not province_ids:
            raise ValueError(
                f"jurisdiction recovery does not own a spatial province: {jurisdiction_id}"
            )
        jurisdiction_records.append({
            "id": jurisdiction_id,
            "displayName": recovery["displayName"],
            "nameCh": recovery["nameCh"],
            "kind": recovery["kind"],
            "commanderyId": recovery["parentRegionId"],
            "seatPlaceId": recovery["seatPlaceId"],
            "provinceIds": sorted(province_ids),
        })
    jurisdiction_records.sort(key=lambda record: record["id"])

    jurisdiction_by_parent: dict[str, list[str]] = {}
    for jurisdiction in jurisdiction_records:
        jurisdiction_by_parent.setdefault(jurisdiction["commanderyId"], []).append(jurisdiction["id"])
    commandery_records = []
    for parent_index, parent in enumerate(parent_regions):
        parent_id = parent.get("id")
        jurisdiction_ids = sorted(jurisdiction_by_parent.get(parent_id, []))
        if not jurisdiction_ids:
            raise ValueError(f"parent has no county jurisdiction: {parent_id}")
        seat_index = (
            parent_seats[parent_index]
            if parent_seats is not None and parent_index < len(parent_seats)
            else None
        )
        if type(seat_index) is not int or not 0 <= seat_index < len(cities):
            raise ValueError(f"parent has no valid seat city: {parent_id}")
        seat = cities[seat_index]
        seat_row, seat_col = seat.get("row"), seat.get("col")
        if (
            type(seat_row) is not int
            or type(seat_col) is not int
            or not 0 <= seat_row < len(rows)
            or not 0 <= seat_col < cols
        ):
            raise ValueError(f"parent seat has no valid grid cell: {parent_id}")
        seat_label = rows[seat_row][seat_col]
        if type(seat_label) is not int or not 0 <= seat_label < len(resolved):
            raise ValueError(f"parent seat is outside playable province geometry: {parent_id}")
        seat_province = resolved[seat_label]
        if seat_province.get("parentRegionId") != parent_id:
            raise ValueError(f"parent seat is inside another parent region: {parent_id}")
        seat_jurisdiction_id = seat_province["jurisdictionId"]
        if seat_jurisdiction_id not in jurisdiction_ids:
            raise ValueError(f"parent seat has an invalid jurisdiction: {parent_id}")
        name_ch = parent.get("nameCh", "")
        parent_kind = parent.get("kind")
        if parent_kind not in {"COMMANDERY", "KINGDOM", "METROPOLITAN"}:
            parent_kind = infer_commandery_kind(parent, seat)
        commandery_records.append({
            "id": parent_id,
            "displayName": parent.get("displayName", ""),
            "nameCh": name_ch,
            "kind": parent_kind,
            "seatJurisdictionId": seat_jurisdiction_id,
            "jurisdictionIds": jurisdiction_ids,
        })
    commandery_records.sort(key=lambda record: record["id"])
    return JurisdictionAssignmentResult(
        tuple(resolved), tuple(jurisdiction_records), tuple(commandery_records)
    )


def rebalance_province_areas(
    result: ProvinceBuildResult,
    seeds: Sequence[ProvinceSeed],
    parent_owner: np.ndarray,
    *,
    total_provinces: int,
    minimum_area: int,
    maximum_area: int | None = None,
    maximum_historical_reach: int = 80,
) -> tuple[ProvinceBuildResult, np.ndarray]:
    """Replace order-dependent fallback merges with a balanced parent partition."""
    parent_owner = np.asarray(parent_owner)
    if parent_owner.shape != result.owner.shape:
        raise ValueError("parent owner shape must match province owner shape")
    parent_ids = {parent.id for parent in result.parent_regions}
    seed_by_id = {seed.id: seed for seed in seeds}
    historical_by_parent: dict[str, list[ProvinceRecord]] = {
        parent_id: [] for parent_id in parent_ids
    }
    for record in result.province_records:
        if record.kind != "DIRECT_TERRITORY":
            historical_by_parent[record.parent_region_id].append(record)
    required_cells = {
        parent.id: max(1, len(historical_by_parent[parent.id])) * minimum_area
        for parent in result.parent_regions
    }
    balanced_parent_owner = parent_owner.copy()
    area_by_index = {
        index: int(np.count_nonzero(balanced_parent_owner == index))
        for index in range(len(result.parent_regions))
    }
    rows, cols = balanced_parent_owner.shape
    for parent_index, parent in enumerate(result.parent_regions):
        required = required_cells[parent.id]
        while area_by_index[parent_index] < required:
            candidates = []
            for row, col in np.argwhere(balanced_parent_owner == parent_index):
                for next_row, next_col in _neighbors(int(row), int(col), rows, cols):
                    donor = int(balanced_parent_owner[next_row, next_col])
                    if donor < 0 or donor == parent_index:
                        continue
                    donor_parent = result.parent_regions[donor]
                    if area_by_index[donor] <= required_cells[donor_parent.id]:
                        continue
                    candidates.append((next_row, next_col, donor))
            if not candidates:
                raise ValueError(f"cannot guarantee minimum province area for {parent.id}")
            row, col, donor = min(candidates)
            balanced_parent_owner[row, col] = parent_index
            area_by_index[parent_index] += 1
            area_by_index[donor] -= 1

    areas = {
        parent.id: int(np.count_nonzero(balanced_parent_owner == index))
        for index, parent in enumerate(result.parent_regions)
    }
    if any(area == 0 for area in areas.values()):
        raise ValueError("every parent region must own at least one cell")
    counts = allocate_parent_province_counts(
        areas,
        {parent_id: len(historical_by_parent[parent_id]) for parent_id in areas},
        total_provinces=total_provinces,
    )

    local_rows: list[tuple[np.ndarray, list[ProvinceRecord]]] = []
    for parent_index, parent in enumerate(result.parent_regions):
        mask = balanced_parent_owner == parent_index
        historical = sorted(historical_by_parent[parent.id], key=lambda row: row.id)
        fixed_anchors = tuple(
            (seed_by_id[record.id].row, seed_by_id[record.id].col)
            for record in historical
        )
        try:
            labels, anchors = balanced_parent_labels(
                mask,
                counts[parent.id],
                fixed_anchors=fixed_anchors,
                minimum_anchor_area=minimum_area,
            )
        except ValueError as error:
            raise ValueError(f"{parent.id}: {error}") from error
        available = set(range(len(anchors)))
        record_by_label: dict[int, ProvinceRecord] = {
            label: record for label, record in enumerate(historical)
        }
        cells_by_label = [np.argwhere(labels == label) for label in range(len(anchors))]
        distances = {}
        for record in historical:
            seed = seed_by_id[record.id]
            distances[record.id] = [
                int(np.max(
                    (cells[:, 0] - seed.row) ** 2 + (cells[:, 1] - seed.col) ** 2
                ))
                for cells in cells_by_label
            ]

        def match_with_limit(limit: int) -> dict[str, int] | None:
            label_to_record: dict[int, str] = {}

            def place(record_id: str, seen: set[int]) -> bool:
                candidates = sorted(
                    (distance, label)
                    for label, distance in enumerate(distances[record_id])
                    if distance <= limit
                )
                for _, label in candidates:
                    if label in seen:
                        continue
                    seen.add(label)
                    previous = label_to_record.get(label)
                    if previous is None or place(previous, seen):
                        label_to_record[label] = record_id
                        return True
                return False

            for record in historical:
                if not place(record.id, set()):
                    return None
            return {record_id: label for label, record_id in label_to_record.items()}

        if historical and len(record_by_label) != len(historical):
            limits = sorted({distance for rows in distances.values() for distance in rows})
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
                raise ValueError(f"cannot place all historical seeds in {parent.id}")
            for record in historical:
                record_by_label[assignment[record.id]] = record
        available -= set(record_by_label)
        if len(record_by_label) != len(historical):
            raise ValueError(f"cannot place all historical seeds in {parent.id}")
        for ordinal, label in enumerate(sorted(available)):
            digest = hashlib.sha256(
                f"{parent.id}:balanced:{ordinal}".encode("utf-8")
            ).hexdigest()[:12]
            record_by_label[label] = ProvinceRecord(
                id=f"DIRECT-{parent.id}-{digest}",
                display_name=parent.display_name,
                name_ch=parent.name_ch,
                administrative_system=parent.administrative_system,
                kind="DIRECT_TERRITORY",
                parent_region_id=parent.id,
                city_index=None,
                geometry_basis="BALANCED_PARENT_PARTITION",
                confidence="INFERRED",
            )
        grid_rows, grid_cols = np.indices(labels.shape)
        for label, record in sorted(record_by_label.items()):
            if record.kind == "DIRECT_TERRITORY":
                continue
            seed = seed_by_id[record.id]
            distant = np.argwhere(
                (labels == label)
                & (((grid_rows - seed.row) ** 2
                    + (grid_cols - seed.col) ** 2)
                   > maximum_historical_reach ** 2)
            )
            for row, col in distant:
                candidates = []
                for candidate, candidate_record in record_by_label.items():
                    if candidate == label:
                        continue
                    if candidate_record.kind != "DIRECT_TERRITORY":
                        candidate_seed = seed_by_id[candidate_record.id]
                        if ((candidate_seed.row - int(row)) ** 2
                                + (candidate_seed.col - int(col)) ** 2
                                > maximum_historical_reach ** 2):
                            continue
                    anchor_row, anchor_col = anchors[candidate]
                    candidates.append((
                        (anchor_row - int(row)) ** 2 + (anchor_col - int(col)) ** 2,
                        candidate,
                    ))
                if not candidates:
                    raise ValueError(
                        f"cannot keep {record.id} within historical reach contract"
                    )
                labels[int(row), int(col)] = min(candidates)[1]
        fixed_anchor_by_label = {
            label: (seed_by_id[record.id].row, seed_by_id[record.id].col)
            for label, record in record_by_label.items()
            if record.kind != "DIRECT_TERRITORY"
        }
        labels = repair_label_connectivity(
            labels,
            mask,
            len(anchors),
            fixed_anchor_by_label=fixed_anchor_by_label,
        )
        def component_count(grid: np.ndarray, label: int) -> int:
            unseen = grid == label
            count = 0
            for start_row, start_col in np.argwhere(unseen):
                if not unseen[start_row, start_col]:
                    continue
                count += 1
                unseen[start_row, start_col] = False
                pending = [(int(start_row), int(start_col))]
                while pending:
                    row, col = pending.pop()
                    for next_row, next_col in _neighbors(row, col, rows, cols):
                        if unseen[next_row, next_col]:
                            unseen[next_row, next_col] = False
                            pending.append((next_row, next_col))
            return count

        area_counts = np.bincount(labels[mask], minlength=len(anchors))
        if int(area_counts.min()) < minimum_area:
            raise ValueError(f"minimum anchor footprint was lost in {parent.id}")

        if maximum_area is not None:
            while int(area_counts.max()) > maximum_area:
                donor = int(np.argmax(area_counts))
                donor_components = component_count(labels, donor)
                candidates = []
                for row, col in np.argwhere(labels == donor):
                    if (int(row), int(col)) == fixed_anchor_by_label.get(donor):
                        continue
                    for next_row, next_col in _neighbors(int(row), int(col), rows, cols):
                        recipient = int(labels[next_row, next_col])
                        if recipient < 0 or recipient == donor:
                            continue
                        if area_counts[recipient] >= maximum_area:
                            continue
                        recipient_record = record_by_label[recipient]
                        if recipient_record.kind != "DIRECT_TERRITORY":
                            recipient_seed = seed_by_id[recipient_record.id]
                            if ((recipient_seed.row - int(row)) ** 2
                                    + (recipient_seed.col - int(col)) ** 2
                                    > maximum_historical_reach ** 2):
                                continue
                        candidates.append((
                            int(area_counts[recipient]), int(row), int(col), recipient,
                        ))
                moved = False
                for _, row, col, recipient in sorted(set(candidates)):
                    labels[row, col] = recipient
                    if component_count(labels, donor) <= donor_components:
                        area_counts[donor] -= 1
                        area_counts[recipient] += 1
                        moved = True
                        break
                    labels[row, col] = donor
                if not moved:
                    raise ValueError(
                        f"cannot enforce maximum province area in {parent.id}"
                    )
        local_rows.append((labels, [record_by_label[index] for index in range(len(anchors))]))

    records = sorted(
        (record for _, rows in local_rows for record in rows), key=lambda row: row.id,
    )
    index_by_id = {record.id: index for index, record in enumerate(records)}
    owner = np.full(result.owner.shape, -1, dtype=np.int32)
    decisions = list(result.audit.decisions)
    for parent, (labels, rows) in zip(result.parent_regions, local_rows):
        for local_index, record in enumerate(rows):
            owner[labels == local_index] = index_by_id[record.id]
        decisions.append(ProvinceAuditDecision(
            "BALANCE_PARENT_AREAS", parent.id, tuple(sorted(record.id for record in rows)),
        ))
    return ProvinceBuildResult(
        owner=owner,
        province_records=tuple(records),
        parent_regions=result.parent_regions,
        audit=ProvinceAudit(tuple(decisions)),
    ), balanced_parent_owner


def _record(seed: ProvinceSeed) -> ProvinceRecord:
    return ProvinceRecord(
        id=seed.id, display_name=seed.display_name, name_ch=seed.name_ch,
        administrative_system=seed.administrative_system, kind=seed.kind,
        parent_region_id=seed.parent_region_id, city_index=seed.city_index,
        geometry_basis=seed.geometry_basis, confidence=seed.confidence,
    )


def _feature_id(feature: Mapping[str, Any]) -> str:
    properties = feature.get("properties") if isinstance(feature.get("properties"), dict) else {}
    value = feature.get("id") or properties.get("shapeID")
    if not isinstance(value, str) or not value:
        digest = hashlib.sha256(repr(feature.get("geometry")).encode("utf-8")).hexdigest()[:16]
        return f"ADM2-{digest}"
    return value


def _feature_parent(feature: Mapping[str, Any]) -> str:
    properties = feature.get("properties") if isinstance(feature.get("properties"), dict) else {}
    value = feature.get("parentRegionId") or properties.get("parentRegionId")
    return value if isinstance(value, str) and value else "UNASSIGNED"


def _feature_mask(feature: Mapping[str, Any], projection: Any, shape: tuple[int, int]) -> np.ndarray:
    if "mask" in feature:
        if (isinstance(feature["mask"], tuple) and len(feature["mask"]) == 2
                and isinstance(feature["mask"][0], np.ndarray)):
            labels, value = feature["mask"]
            if labels.shape != shape or type(value) is not int:
                raise ValueError("ADM2 label mask shape/value mismatch")
            return labels == value
        mask = np.asarray(feature["mask"], dtype=bool)
        if mask.shape != shape:
            raise ValueError("ADM2 mask shape mismatch")
        return mask
    geometry = feature.get("geometry")
    if projection is None or not isinstance(geometry, dict):
        raise ValueError("ADM2 feature requires a mask or projected geometry")
    from PIL import Image, ImageDraw

    image = Image.new("L", (shape[1], shape[0]), 0)
    draw = ImageDraw.Draw(image)
    polygons = geometry.get("coordinates", [])
    if geometry.get("type") == "Polygon":
        polygons = [polygons]
    if geometry.get("type") not in {"Polygon", "MultiPolygon"}:
        return np.zeros(shape, dtype=bool)
    holes: list[list[tuple[float, float]]] = []
    for polygon in polygons:
        for ring_index, ring in enumerate(polygon):
            points: list[tuple[float, float]] = []
            for lon, lat in ring:
                col, row = projection.to_cell(lon, lat)
                points.append((float(col), float(row)))
            if len(points) < 3:
                continue
            if ring_index == 0:
                draw.polygon(points, fill=1)
            else:
                holes.append(points)
    for points in holes:
        draw.polygon(points, fill=0)
    return np.asarray(image, dtype=bool)


def _neighbors(row: int, col: int, rows: int, cols: int):
    for next_row, next_col in ((row - 1, col), (row, col - 1), (row, col + 1), (row + 1, col)):
        if 0 <= next_row < rows and 0 <= next_col < cols:
            yield next_row, next_col


def _split_mask(
    owner: np.ndarray, mask: np.ndarray, seed_rows: Sequence[tuple[ProvinceSeed, int]],
) -> None:
    rows, cols = owner.shape
    distances = np.full(owner.shape, np.iinfo(np.int32).max, dtype=np.int32)
    queue: list[tuple[int, str, int, int, int]] = []
    for seed, index in sorted(seed_rows, key=lambda item: item[0].id):
        if 0 <= seed.row < rows and 0 <= seed.col < cols and mask[seed.row, seed.col]:
            distances[seed.row, seed.col] = 0
            heapq.heappush(queue, (0, seed.id, index, seed.row, seed.col))
    while queue:
        distance, province_id, index, row, col = heapq.heappop(queue)
        if distance != distances[row, col]:
            continue
        current = owner[row, col]
        if current == -1:
            owner[row, col] = index
        elif current != index:
            # Historical ownership is write-once.  Equal-distance seed ties are
            # resolved by lexical province ID before this branch is reached.
            continue
        for next_row, next_col in _neighbors(row, col, rows, cols):
            if not mask[next_row, next_col] or owner[next_row, next_col] not in {-1, index}:
                continue
            next_distance = distance + 1
            if next_distance < distances[next_row, next_col]:
                distances[next_row, next_col] = next_distance
                heapq.heappush(
                    queue, (next_distance, province_id, index, next_row, next_col)
                )
    unassigned = np.argwhere(mask & (owner == -1))
    if len(unassigned) and seed_rows:
        ordered = sorted(seed_rows, key=lambda item: item[0].id)
        for row, col in unassigned:
            _, index = min(
                ordered,
                key=lambda item: (
                    (item[0].row - row) ** 2 + (item[0].col - col) ** 2,
                    item[0].id,
                ),
            )
            owner[row, col] = index


def _adjacent_indices(owner: np.ndarray, mask: np.ndarray) -> set[int]:
    rows, cols = owner.shape
    result: set[int] = set()
    for row, col in np.argwhere(mask):
        for next_row, next_col in _neighbors(int(row), int(col), rows, cols):
            if not mask[next_row, next_col] and owner[next_row, next_col] >= 0:
                result.add(int(owner[next_row, next_col]))
    return result


def _direct_id(parent_id: str, feature_id: str) -> str:
    safe_parent = re.sub(r"[^A-Za-z0-9_-]+", "-", parent_id).strip("-") or "UNASSIGNED"
    digest = hashlib.sha256(feature_id.encode("utf-8")).hexdigest()[:12]
    return f"DIRECT-{safe_parent}-{digest}"


def build_province_geometry(
    terrain: np.ndarray,
    projection: Any,
    seeds: Sequence[ProvinceSeed],
    parent_regions: Sequence[ParentRegionRecord],
    admin_features: Sequence[Mapping[str, Any]],
    historical_masks: Mapping[str, np.ndarray],
) -> ProvinceBuildResult:
    terrain = np.asarray(terrain)
    if terrain.ndim != 2:
        raise ValueError("terrain must be a two-dimensional grid")
    if len({seed.id for seed in seeds}) != len(seeds):
        raise ValueError("province seed IDs must be unique")
    if len({parent.id for parent in parent_regions}) != len(parent_regions):
        raise ValueError("parent region IDs must be unique")
    seed_by_id = {seed.id: seed for seed in seeds}
    records = [_record(seed) for seed in sorted(seeds, key=lambda row: row.id)]
    index_by_id = {record.id: index for index, record in enumerate(records)}
    owner = np.full(terrain.shape, -1, dtype=np.int32)
    land = terrain != 0
    decisions: list[ProvinceAuditDecision] = []

    for province_id in sorted(historical_masks):
        if province_id not in index_by_id:
            raise ValueError(f"historical mask has no province seed: {province_id}")
        mask = np.asarray(historical_masks[province_id], dtype=bool)
        if mask.shape != terrain.shape:
            raise ValueError("historical mask shape mismatch")
        if np.any(mask & ~land):
            raise ValueError("historical mask includes water")
        overlap = mask & (owner >= 0)
        if np.any(overlap & (owner != index_by_id[province_id])):
            raise ValueError("historical masks overlap")
        owner[mask] = index_by_id[province_id]
        decisions.append(ProvinceAuditDecision(
            "PRESERVE_HISTORICAL", province_id, (province_id,)
        ))

    area_by_index = {
        index: int(np.count_nonzero(owner == index)) for index in range(len(records))
    }

    feature_rows = sorted(
        admin_features,
        key=lambda feature: (int(feature.get("order", 0)), _feature_id(feature)),
    )
    for feature in feature_rows:
        source_id = _feature_id(feature)
        parent_id = _feature_parent(feature)
        mask = _feature_mask(feature, projection, terrain.shape) & land
        if not np.any(mask):
            continue
        contained = [
            seed for seed in seeds
            if seed.parent_region_id == parent_id
            and 0 <= seed.row < terrain.shape[0] and 0 <= seed.col < terrain.shape[1]
            and mask[seed.row, seed.col]
        ]
        remaining = mask & (owner == -1)
        if contained:
            _split_mask(owner, mask, [(seed, index_by_id[seed.id]) for seed in contained])
            for seed in contained:
                index = index_by_id[seed.id]
                area_by_index[index] += int(np.count_nonzero(remaining & (owner == index)))
            kind = "ASSIGN_SINGLE_SEED" if len(contained) == 1 else "SPLIT_MULTI_SEED"
            decisions.append(ProvinceAuditDecision(
                kind, source_id, tuple(sorted(seed.id for seed in contained))
            ))
            continue
        adjacent = [
            index for index in sorted(_adjacent_indices(owner, mask))
            if records[index].parent_region_id == parent_id
        ]
        merge_cap = feature.get("mergeAreaCap")
        if merge_cap is not None:
            if type(merge_cap) is not int or merge_cap < 1:
                raise ValueError("ADM2 mergeAreaCap must be a positive integer")
            zone_area = int(np.count_nonzero(remaining))
            adjacent = [
                index for index in adjacent
                if area_by_index.get(index, 0) + zone_area <= merge_cap
            ]
        if adjacent:
            selected = min(adjacent, key=lambda index: records[index].id)
            owner[remaining] = selected
            area_by_index[selected] = area_by_index.get(selected, 0) + int(np.count_nonzero(remaining))
            decisions.append(ProvinceAuditDecision(
                "MERGE_SEEDLESS", source_id, (records[selected].id,)
            ))
            continue
        direct_id = _direct_id(parent_id, source_id)
        if direct_id not in index_by_id:
            parent = next((row for row in parent_regions if row.id == parent_id), None)
            display_name = f"{parent.display_name} 직할지" if parent else "직할지"
            record = ProvinceRecord(
                id=direct_id, display_name=display_name, name_ch="",
                administrative_system=(parent.administrative_system if parent else "EXTERNAL_POLITY"),
                kind="DIRECT_TERRITORY", parent_region_id=parent_id, city_index=None,
                geometry_basis="MODERN_ADMIN_FALLBACK", confidence="INFERRED",
            )
            index_by_id[direct_id] = len(records)
            records.append(record)
        owner[remaining] = index_by_id[direct_id]
        area_by_index[index_by_id[direct_id]] = (
            area_by_index.get(index_by_id[direct_id], 0) + int(np.count_nonzero(remaining))
        )
        decisions.append(ProvinceAuditDecision(
            "CREATE_DIRECT_TERRITORY", source_id, (direct_id,)
        ))

    # Processing order can leave a very small seedless coastal sliver as a
    # direct territory before its same-parent neighbour is painted.  Collapse
    # those artifacts after the complete surface is known; real islands with
    # no touching same-parent neighbour remain explicit direct territories.
    for index, record in list(enumerate(records)):
        if record.kind != "DIRECT_TERRITORY":
            continue
        mask = owner == index
        area = int(np.count_nonzero(mask))
        if not 0 < area < 32:
            continue
        adjacent = [
            candidate for candidate in sorted(_adjacent_indices(owner, mask))
            if records[candidate].parent_region_id == record.parent_region_id
            and records[candidate].kind != "DIRECT_TERRITORY"
        ]
        if not adjacent:
            continue
        selected = min(adjacent, key=lambda candidate: records[candidate].id)
        owner[mask] = selected
        decisions.append(ProvinceAuditDecision(
            "MERGE_TINY_DIRECT", record.id, (records[selected].id,)
        ))

    # A reviewed physical seed must never disappear because its recorded point
    # lands on a lake edge, a modern-boundary sliver, or another rasterized seed.
    # Restore a small compact footprint from the same historical parent only.
    for seed in sorted(seeds, key=lambda row: row.id):
        index = index_by_id[seed.id]
        if np.any(owner == index):
            continue
        candidates = []
        for row, col in np.argwhere(land & (owner >= 0)):
            donor = int(owner[row, col])
            if records[donor].parent_region_id != seed.parent_region_id:
                continue
            candidates.append((
                (int(row) - seed.row) ** 2 + (int(col) - seed.col) ** 2,
                int(row), int(col), donor,
            ))
        donor_areas = {
            donor: int(np.count_nonzero(owner == donor))
            for _, _, _, donor in candidates
        }
        restored = 0
        for _, row, col, donor in sorted(candidates):
            if donor_areas[donor] <= 1:
                continue
            owner[row, col] = index
            donor_areas[donor] -= 1
            restored += 1
            if restored == 9:
                break
        if restored == 0:
            raise ValueError(f"province seed cannot receive a footprint: {seed.id}")
        decisions.append(ProvinceAuditDecision(
            "RESTORE_SEED_FOOTPRINT", seed.id, (seed.id,)
        ))

    # Direct records are created in sorted feature order, while seed records are
    # lexical. Reindex everything once so caller input order never affects bytes.
    live_indices = {
        int(index) for index in np.unique(owner) if int(index) >= 0
    }
    sorted_records = sorted(
        (record for index, record in enumerate(records) if index in live_indices),
        key=lambda row: row.id,
    )
    old_by_id = {
        record.id: index for index, record in enumerate(records)
        if index in live_indices
    }
    new_by_id = {record.id: index for index, record in enumerate(sorted_records)}
    remapped = np.full(owner.shape, -1, dtype=np.int32)
    for province_id, old_index in old_by_id.items():
        remapped[owner == old_index] = new_by_id[province_id]
    return ProvinceBuildResult(
        owner=remapped,
        province_records=tuple(sorted_records),
        parent_regions=tuple(sorted(parent_regions, key=lambda row: row.id)),
        audit=ProvinceAudit(tuple(decisions)),
    )


# ---------------------------------------------------------------------------
# Reviewed parent (commandery) adjudications
#
# A county's parent commandery is a historical judgment, not a geometric one.
# The ledger moves a whole jurisdiction — every spatial province bound to it —
# under a different parent, and every parent-bearing surface of the artifact is
# re-derived from that single fact: provinceRecords.parentRegionId, the
# commandery back-references, the parentOwner grid, and the commandery
# adjacency graph. The same function runs inside the protected generator and
# the materializer so a full regeneration cannot silently drop the review.
# ---------------------------------------------------------------------------

PARENT_ADJUDICATION_LEDGER_ID = "han-jurisdiction-commandery-adjudications-v1"
PARENT_ADJUDICATION_REFERENCE_YEAR = 220
PARENT_ADJUDICATION_REVIEW_STATE = "APPROVED_EXACT_PARENT"
_PARENT_ADJUDICATION_DOCUMENT_KEYS = frozenset({
    "schemaVersion", "ledgerId", "referenceYear", "adjudications",
})
_PARENT_ADJUDICATION_ROW_KEYS = frozenset({
    "jurisdictionId", "jurisdictionNameCh",
    "fromCommanderyId", "fromCommanderyNameCh",
    "toCommanderyId", "toCommanderyNameCh",
    "reviewState", "evidenceRefs",
})
_PARENT_ADJUDICATION_STRING_KEYS = _PARENT_ADJUDICATION_ROW_KEYS - {"evidenceRefs"}


def validate_jurisdiction_parent_adjudication_document(document: Any) -> list[dict[str, Any]]:
    """Fail closed on anything but an approved, evidenced, non-trivial ledger."""
    if not isinstance(document, Mapping) or set(document) != _PARENT_ADJUDICATION_DOCUMENT_KEYS:
        raise ValueError("parent adjudication document has invalid keys")
    if document["schemaVersion"] != 1:
        raise ValueError("parent adjudication schemaVersion must be 1")
    if document["ledgerId"] != PARENT_ADJUDICATION_LEDGER_ID:
        raise ValueError("parent adjudication ledgerId mismatch")
    if document["referenceYear"] != PARENT_ADJUDICATION_REFERENCE_YEAR:
        raise ValueError("parent adjudication referenceYear must be 220")
    rows = document["adjudications"]
    if not isinstance(rows, list):
        raise ValueError("parent adjudications must be an array")
    seen: set[str] = set()
    for index, row in enumerate(rows):
        if not isinstance(row, Mapping) or set(row) != _PARENT_ADJUDICATION_ROW_KEYS:
            raise ValueError(f"parent adjudications[{index}] has invalid keys")
        if any(
            not isinstance(row[key], str) or not row[key]
            for key in _PARENT_ADJUDICATION_STRING_KEYS
        ):
            raise ValueError(f"parent adjudications[{index}] has an invalid string")
        if row["reviewState"] != PARENT_ADJUDICATION_REVIEW_STATE:
            raise ValueError(f"parent adjudications[{index}] is not approved")
        if row["fromCommanderyId"] == row["toCommanderyId"]:
            raise ValueError(f"parent adjudications[{index}] does not change parent")
        evidence = row["evidenceRefs"]
        if (
            not isinstance(evidence, list)
            or not evidence
            or any(not isinstance(ref, str) or not ref for ref in evidence)
        ):
            raise ValueError(f"parent adjudications[{index}] requires evidence")
        jurisdiction_id = row["jurisdictionId"]
        if jurisdiction_id in seen:
            raise ValueError(f"duplicate parent adjudication: {jurisdiction_id}")
        seen.add(jurisdiction_id)
    return [dict(row) for row in rows]


def _expand_runs(runs: Any, rows: int, cols: int, label: str) -> np.ndarray:
    if not isinstance(runs, list) or not runs:
        raise ValueError(f"{label} must be a non-empty run-length array")
    values: list[int] = []
    counts: list[int] = []
    for index, run in enumerate(runs):
        if (
            not isinstance(run, list)
            or len(run) != 2
            or type(run[0]) is not int
            or type(run[1]) is not int
            or run[1] <= 0
        ):
            raise ValueError(f"{label}[{index}] must be [integer, positive count]")
        values.append(run[0])
        counts.append(run[1])
    if sum(counts) != rows * cols:
        raise ValueError(f"{label} run length does not match rows * cols")
    return np.repeat(np.asarray(values, dtype=np.int64), counts).reshape(rows, cols)


def _encode_runs(grid: np.ndarray) -> list[list[int]]:
    """Row-major run-length encoding; runs merge across row boundaries."""
    flat = np.asarray(grid, dtype=np.int64).ravel()
    if flat.size == 0:
        return []
    starts = np.concatenate(([0], np.flatnonzero(np.diff(flat)) + 1))
    lengths = np.diff(np.append(starts, flat.size))
    return [[int(flat[start]), int(length)] for start, length in zip(starts, lengths)]


def _terrain_array(document: Mapping[str, Any], rows: int, cols: int) -> np.ndarray:
    raw_rows = document.get("terrain")
    if not isinstance(raw_rows, list) or len(raw_rows) != rows:
        raise ValueError("terrain must contain exactly rows strings")
    terrain = np.zeros((rows, cols), dtype=np.int8)
    for index, raw in enumerate(raw_rows):
        if not isinstance(raw, str) or len(raw) != cols or not raw.isdigit():
            raise ValueError(f"terrain[{index}] must contain exactly {cols} class digits")
        terrain[index] = [int(value) for value in raw]
    return terrain


def _jun_seat_points(document: Mapping[str, Any]) -> list[tuple[int, int]]:
    cities = document.get("cities")
    juns = document.get("juns")
    if not isinstance(cities, list) or not isinstance(juns, list):
        raise ValueError("cities and juns must be arrays")
    points: list[tuple[int, int]] = []
    for index, jun in enumerate(juns):
        seat = jun.get("seat") if isinstance(jun, Mapping) else None
        if type(seat) is not int or not 0 <= seat < len(cities):
            raise ValueError(f"juns[{index}].seat is invalid")
        city = cities[seat]
        col, row = city.get("col"), city.get("row")
        if type(col) is not int or type(row) is not int:
            raise ValueError(f"juns[{index}] seat city has invalid coordinates")
        points.append((col, row))
    return points


def _rederive_parent_surfaces(document: dict[str, Any]) -> None:
    """Recompute parentOwner and the commandery graph from owner + parentRegionId.

    Shared edges are recounted from the grid. A pair that still touches keeps its
    crossing verdict and ford (they are a function of the two seats, not of the
    boundary). A pair that stops touching is dropped. A pair that newly touches is
    judged by the same seat-to-seat path the terrain generator uses.

    Precondition: the incoming ``adjacency.commandery`` must already be canonical
    for the current seats and terrain — the carry-over does not re-judge surviving
    pairs. ``adjudicate_han_province_fragments.py --check`` (full re-derivation,
    byte-identical) is the gate that proves the committed graph satisfies it.
    """
    try:
        from tools.map import build_terrain_grid as terrain_builder
    except ModuleNotFoundError:  # pragma: no cover - direct script compatibility
        import build_terrain_grid as terrain_builder  # type: ignore[no-redef]

    meta = document.get("_meta")
    if not isinstance(meta, Mapping):
        raise ValueError("parent adjudication requires _meta")
    cols, rows = meta.get("cols"), meta.get("rows")
    if type(cols) is not int or type(rows) is not int or cols <= 0 or rows <= 0:
        raise ValueError("parent adjudication requires positive cols and rows")
    provinces = document["provinceRecords"]
    parents = document.get("parentRegions")
    if not isinstance(parents, list):
        raise ValueError("parent adjudication requires parentRegions")
    parent_index: dict[str, int] = {}
    for index, parent in enumerate(parents):
        parent_id = parent.get("id") if isinstance(parent, Mapping) else None
        if not isinstance(parent_id, str) or not parent_id or parent_id in parent_index:
            raise ValueError(f"parentRegions[{index}] has an invalid or duplicate id")
        parent_index[parent_id] = index
    parent_of_province: list[int] = []
    for province in provinces:
        parent_id = province.get("parentRegionId")
        if parent_id not in parent_index:
            raise ValueError(f"province references an unknown parent: {province.get('id')}")
        parent_of_province.append(parent_index[parent_id])
    if "parentOwner" not in document:
        raise ValueError("parent adjudication requires a parentOwner grid")
    owner = _expand_runs(document.get("owner"), rows, cols, "owner")
    if int(owner.max()) >= len(provinces):
        raise ValueError("owner contains an out-of-range province index")
    lookup = np.asarray(parent_of_province, dtype=np.int64)
    parent_owner = np.where(owner >= 0, lookup[np.maximum(owner, 0)], -1)
    document["parentOwner"] = _encode_runs(parent_owner)

    adjacency = document.get("adjacency")
    if (
        not isinstance(adjacency, Mapping)
        or not isinstance(adjacency.get("county"), list)
        or not isinstance(adjacency.get("commandery"), list)
    ):
        raise ValueError("parent adjudication requires county and commandery adjacency")
    existing: dict[tuple[int, int], Mapping[str, Any]] = {}
    for edge in adjacency["commandery"]:
        a, b = edge.get("a"), edge.get("b")
        if type(a) is not int or type(b) is not int or not a < b or (a, b) in existing:
            raise ValueError("commandery adjacency has an invalid or duplicate edge")
        existing[(a, b)] = edge
    merged: list[dict[str, Any]] = []
    fresh: list[dict[str, Any]] = []
    for (a, b), cells in sorted(terrain_builder.touching_pairs(parent_owner).items()):
        edge = existing.get((int(a), int(b)))
        if edge is None:
            fresh.append({"a": int(a), "b": int(b), "cells": int(cells)})
        else:
            merged.append({**edge, "cells": int(cells)})
    if fresh:
        terrain = _terrain_array(document, rows, cols)
        points = _jun_seat_points(document)
        land_field = terrain_builder.cost_field(terrain, terrain_builder.LAND_COST)
        for x, y in points:
            if land_field[y, x] == terrain_builder.INF:
                land_field[y, x] = terrain_builder.LAND_COST[terrain_builder.PLAIN]
        terrain_builder.cross_by_path(land_field, points, fresh, terrain)
        merged.extend(fresh)
    merged.sort(key=lambda edge: (edge["a"], edge["b"]))
    document["adjacency"] = {**adjacency, "commandery": merged}
    counts = meta.get("counts")
    if not isinstance(counts, dict):
        raise ValueError("_meta.counts must be an object")
    counts["adjCounty"] = len(adjacency["county"])
    counts["adjCommandery"] = len(merged)


def apply_jurisdiction_parent_adjudications(
    document: dict[str, Any], adjudications_document: Any,
) -> list[str]:
    """Re-parent reviewed jurisdictions in place; returns the moved jurisdiction IDs.

    Idempotent: a jurisdiction already under its approved parent is accepted as
    long as it is under exactly the ledger's source or target. Anything else —
    unknown IDs, name drift, a third parent, or a commandery seat — fails closed.
    """
    rows = validate_jurisdiction_parent_adjudication_document(adjudications_document)
    jurisdictions = document.get("jurisdictionRecords")
    commanderies = document.get("commanderyRecords")
    provinces = document.get("provinceRecords")
    if not all(isinstance(value, list) for value in (jurisdictions, commanderies, provinces)):
        raise ValueError("parent adjudication requires materialized hierarchy arrays")
    jurisdiction_by_id = {row.get("id"): row for row in jurisdictions}
    commandery_by_id = {row.get("id"): row for row in commanderies}
    if len(jurisdiction_by_id) != len(jurisdictions) or len(commandery_by_id) != len(commanderies):
        raise ValueError("parent adjudication requires unique hierarchy ids")
    seat_owner = {
        row.get("seatJurisdictionId"): row.get("id")
        for row in commanderies
        if isinstance(row.get("seatJurisdictionId"), str)
    }
    moved: list[str] = []
    for row in rows:
        jurisdiction_id = row["jurisdictionId"]
        jurisdiction = jurisdiction_by_id.get(jurisdiction_id)
        if jurisdiction is None:
            raise ValueError(f"parent adjudication has unknown jurisdiction: {jurisdiction_id}")
        if jurisdiction.get("nameCh") != row["jurisdictionNameCh"]:
            raise ValueError(f"parent adjudication jurisdiction name drift: {jurisdiction_id}")
        source = commandery_by_id.get(row["fromCommanderyId"])
        target = commandery_by_id.get(row["toCommanderyId"])
        if source is None or target is None:
            raise ValueError(f"parent adjudication has unknown commandery: {jurisdiction_id}")
        if source.get("nameCh") != row["fromCommanderyNameCh"]:
            raise ValueError(f"parent adjudication source name drift: {jurisdiction_id}")
        if target.get("nameCh") != row["toCommanderyNameCh"]:
            raise ValueError(f"parent adjudication target name drift: {jurisdiction_id}")
        if jurisdiction_id in seat_owner:
            raise ValueError(
                "parent adjudication moves a commandery seat jurisdiction: "
                f"{jurisdiction_id} (seat of {seat_owner[jurisdiction_id]})"
            )
        current = jurisdiction.get("commanderyId")
        if current not in {source["id"], target["id"]}:
            raise ValueError(f"parent adjudication current parent drift: {jurisdiction_id}")
        jurisdiction["commanderyId"] = target["id"]
        for province in provinces:
            if province.get("jurisdictionId") == jurisdiction_id:
                province["parentRegionId"] = target["id"]
        moved.append(jurisdiction_id)
    for commandery in commanderies:
        commandery["jurisdictionIds"] = sorted(
            jurisdiction["id"]
            for jurisdiction in jurisdictions
            if jurisdiction.get("commanderyId") == commandery.get("id")
        )
    _rederive_parent_surfaces(document)
    return moved
