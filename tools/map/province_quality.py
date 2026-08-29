"""Fail-closed shape metrics for generated province identity grids."""

from __future__ import annotations

import math
from collections import defaultdict, deque
from dataclasses import dataclass
from typing import Any, Mapping, Sequence

import numpy as np


@dataclass(frozen=True)
class ProvinceQualityPolicy:
    max_components: int = 1
    max_aspect_ratio: float = 4.0
    min_aspect_area: int = 0
    min_fill_ratio: float = 0.20
    min_fill_area: int = 0
    max_area: int | None = None
    max_parent_median_ratio: float = 8.0
    corridor_max_width: int = 2
    corridor_min_length: int = 8


@dataclass(frozen=True)
class ProvinceShapeMetric:
    province_id: str
    parent_region_id: str
    area: int
    component_count: int
    aspect_ratio: float
    fill_ratio: float
    perimeter: int
    compactness: float
    corridor_length: int


@dataclass(frozen=True)
class ProvinceQualityReport:
    metrics: tuple[ProvinceShapeMetric, ...]


def _value(record: Any, snake: str, camel: str) -> Any:
    if isinstance(record, Mapping):
        return record.get(camel, record.get(snake))
    return getattr(record, snake)


def _components(mask: np.ndarray) -> int:
    seen = np.zeros(mask.shape, dtype=bool)
    count = 0
    rows, cols = mask.shape
    for start_row, start_col in np.argwhere(mask):
        if seen[start_row, start_col]:
            continue
        count += 1
        queue = deque([(int(start_row), int(start_col))])
        seen[start_row, start_col] = True
        while queue:
            row, col = queue.popleft()
            for next_row, next_col in ((row - 1, col), (row + 1, col), (row, col - 1), (row, col + 1)):
                if (0 <= next_row < rows and 0 <= next_col < cols
                        and mask[next_row, next_col] and not seen[next_row, next_col]):
                    seen[next_row, next_col] = True
                    queue.append((next_row, next_col))
    return count


def _perimeter(mask: np.ndarray) -> int:
    padded = np.pad(mask, 1, constant_values=False)
    center = padded[1:-1, 1:-1]
    return int(sum(np.count_nonzero(center & ~neighbor) for neighbor in (
        padded[:-2, 1:-1], padded[2:, 1:-1],
        padded[1:-1, :-2], padded[1:-1, 2:],
    )))


def _max_corridor(mask: np.ndarray, width: int) -> int:
    rows, cols = mask.shape
    best = 0
    for row in range(rows):
        run = 0
        for col in range(cols):
            thickness = 0
            if mask[row, col]:
                top = row
                while top >= 0 and mask[top, col]:
                    thickness += 1
                    top -= 1
                bottom = row + 1
                while bottom < rows and mask[bottom, col]:
                    thickness += 1
                    bottom += 1
            run = run + 1 if mask[row, col] and thickness <= width else 0
            best = max(best, run)
    for col in range(cols):
        run = 0
        for row in range(rows):
            thickness = 0
            if mask[row, col]:
                left = col
                while left >= 0 and mask[row, left]:
                    thickness += 1
                    left -= 1
                right = col + 1
                while right < cols and mask[row, right]:
                    thickness += 1
                    right += 1
            run = run + 1 if mask[row, col] and thickness <= width else 0
            best = max(best, run)
    return best


def measure_province_shapes(
    owner: np.ndarray, records: Sequence[Any]
) -> ProvinceQualityReport:
    owner = np.asarray(owner)
    if owner.ndim != 2:
        raise ValueError("owner must be a two-dimensional grid")
    metrics: list[ProvinceShapeMetric] = []
    for index, record in enumerate(records):
        province_id = _value(record, "id", "id")
        parent_id = _value(record, "parent_region_id", "parentRegionId")
        mask = owner == index
        area = int(np.count_nonzero(mask))
        if area == 0:
            metrics.append(ProvinceShapeMetric(
                province_id, parent_id, 0, 0, 0.0, 0.0, 0, 0.0, 0
            ))
            continue
        coordinates = np.argwhere(mask)
        min_row, min_col = coordinates.min(axis=0)
        max_row, max_col = coordinates.max(axis=0)
        height = int(max_row - min_row + 1)
        width = int(max_col - min_col + 1)
        local = mask[min_row:max_row + 1, min_col:max_col + 1]
        aspect = max(height, width) / min(height, width)
        perimeter = _perimeter(local)
        compactness = 4.0 * math.pi * area / (perimeter * perimeter)
        metrics.append(ProvinceShapeMetric(
            province_id=province_id, parent_region_id=parent_id, area=area,
            component_count=_components(local), aspect_ratio=aspect,
            fill_ratio=area / (height * width), perimeter=perimeter,
            compactness=compactness, corridor_length=_max_corridor(local, 2),
        ))
    return ProvinceQualityReport(tuple(metrics))


def _exception_keys(
    exceptions: Sequence[Mapping[str, Any]], map_version: str,
) -> set[tuple[str, str]]:
    expected = {"provinceId", "metric", "reason", "evidence", "effectiveMapVersion"}
    result: set[tuple[str, str]] = set()
    for index, row in enumerate(exceptions):
        if not isinstance(row, Mapping) or set(row) != expected:
            raise ValueError(f"quality exception {index} requires exact keys")
        if row["effectiveMapVersion"] != map_version:
            continue
        if row["provinceId"] in {"*", "ALL"} or "*" in row["provinceId"]:
            raise ValueError("quality exception wildcard province IDs are forbidden")
        if not row["reason"] or not row["evidence"]:
            raise ValueError("quality exception requires reason and evidence")
        result.add((row["provinceId"], row["metric"]))
    return result


def validate_province_quality(
    report: ProvinceQualityReport,
    policy: ProvinceQualityPolicy,
    exceptions: Sequence[Mapping[str, Any]],
    *,
    map_version: str = "han-world-v2",
) -> None:
    waived = _exception_keys(exceptions, map_version)
    areas: dict[str, list[int]] = defaultdict(list)
    for metric in report.metrics:
        if metric.area:
            areas[metric.parent_region_id].append(metric.area)
    medians = {
        parent: float(np.median(values)) for parent, values in areas.items()
    }
    failures: list[str] = []
    for metric in report.metrics:
        checks = (
            ("empty", metric.area == 0, "has no cells"),
            ("componentCount", metric.component_count > policy.max_components, "is disconnected"),
            ("aspectRatio", metric.area >= policy.min_aspect_area and metric.aspect_ratio > policy.max_aspect_ratio, "has excessive aspect ratio"),
            ("fillRatio", metric.area >= policy.min_fill_area and metric.fill_ratio < policy.min_fill_ratio, "has low fill ratio"),
            ("absoluteArea", policy.max_area is not None and metric.area > policy.max_area, "exceeds absolute area cap"),
            ("areaOutlier", metric.area > policy.max_parent_median_ratio * medians.get(metric.parent_region_id, metric.area), "is an area outlier"),
            ("corridor", metric.corridor_length >= policy.corridor_min_length, "contains a narrow corridor"),
        )
        for key, failed, message in checks:
            if failed and (metric.province_id, key) not in waived:
                failures.append(f"{metric.province_id} {message}")
    if failures:
        raise ValueError("province quality failed: " + "; ".join(failures))
