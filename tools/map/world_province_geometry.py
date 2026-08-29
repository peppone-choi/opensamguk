"""Deterministic province geometry construction for the 220 world map.

Historical masks are immutable ownership.  Modern administrative polygons are a
geometry scaffold only: seeds split a polygon within its own mask, while seedless
polygons merge to an adjacent province in the same historical parent region or
become explicitly named direct territory.
"""

from __future__ import annotations

import hashlib
import heapq
import re
from dataclasses import dataclass
from typing import Any, Mapping, Sequence

import numpy as np


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
