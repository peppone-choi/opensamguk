"""Fail-closed shape metrics for generated province identity grids."""

from __future__ import annotations

import heapq
import math
from collections import defaultdict, deque
from dataclasses import dataclass
from fractions import Fraction
from typing import Any, Mapping, Sequence

import numpy as np


def allocate_parent_province_counts(
    parent_areas: Mapping[str, int], seed_counts: Mapping[str, int], *, total_provinces: int,
) -> dict[str, int]:
    """Allocate synthetic provinces to minimize the greatest parent mean area."""
    if set(parent_areas) != set(seed_counts):
        raise ValueError("parent area and seed count IDs must match")
    if any(type(area) is not int or area < 1 for area in parent_areas.values()):
        raise ValueError("parent areas must be positive integers")
    if any(type(count) is not int or count < 0 for count in seed_counts.values()):
        raise ValueError("parent seed counts must be non-negative integers")
    if type(total_provinces) is not int:
        raise ValueError("total province count must be an integer")
    counts = {parent_id: max(1, seed_counts[parent_id]) for parent_id in parent_areas}
    required = sum(counts.values())
    if total_provinces < required:
        raise ValueError(
            f"total province count {total_provinces} is below required seed count {required}"
        )
    heap: list[tuple[Fraction, str]] = [
        (-Fraction(parent_areas[parent_id], counts[parent_id]), parent_id)
        for parent_id in sorted(parent_areas)
    ]
    heapq.heapify(heap)
    for _ in range(total_provinces - required):
        _, parent_id = heapq.heappop(heap)
        counts[parent_id] += 1
        heapq.heappush(heap, (-Fraction(parent_areas[parent_id], counts[parent_id]), parent_id))
    return counts


def repair_label_connectivity(
    labels: np.ndarray, valid_mask: np.ndarray, label_count: int,
) -> np.ndarray:
    """Move detached pieces across a shared edge while preserving isolated islands."""
    output = np.asarray(labels, dtype=np.int32).copy()
    valid_mask = np.asarray(valid_mask, dtype=bool)
    if output.shape != valid_mask.shape:
        raise ValueError("label and validity grids must have the same shape")
    rows, cols = output.shape

    def components(label: int) -> list[list[tuple[int, int]]]:
        unseen = valid_mask & (output == label)
        found: list[list[tuple[int, int]]] = []
        for start_row, start_col in np.argwhere(unseen):
            if not unseen[start_row, start_col]:
                continue
            unseen[start_row, start_col] = False
            pending = [(int(start_row), int(start_col))]
            component: list[tuple[int, int]] = []
            while pending:
                row, col = pending.pop()
                component.append((row, col))
                for next_row, next_col in (
                    (row - 1, col), (row + 1, col), (row, col - 1), (row, col + 1),
                ):
                    if (0 <= next_row < rows and 0 <= next_col < cols
                            and unseen[next_row, next_col]):
                        unseen[next_row, next_col] = False
                        pending.append((next_row, next_col))
            found.append(component)
        return found

    changed = True
    while changed:
        changed = False
        areas = np.bincount(output[valid_mask], minlength=label_count)
        for label in range(label_count):
            pieces = components(label)
            if len(pieces) <= 1:
                continue
            keep = max(pieces, key=lambda piece: (len(piece), tuple(piece[0])))
            for piece in pieces:
                if piece is keep:
                    continue
                neighbors: set[int] = set()
                for row, col in piece:
                    for next_row, next_col in (
                        (row - 1, col), (row + 1, col), (row, col - 1), (row, col + 1),
                    ):
                        if 0 <= next_row < rows and 0 <= next_col < cols:
                            neighbor = int(output[next_row, next_col])
                            if 0 <= neighbor < label_count and neighbor != label:
                                neighbors.add(neighbor)
                if not neighbors:
                    continue
                recipient = min(neighbors, key=lambda value: (int(areas[value]), value))
                for row, col in piece:
                    output[row, col] = recipient
                areas[label] -= len(piece)
                areas[recipient] += len(piece)
                changed = True
    return output


def balanced_parent_labels(
    mask: np.ndarray, province_count: int, *, iterations: int = 320,
) -> tuple[np.ndarray, tuple[tuple[int, int], ...]]:
    """Make deterministic, connected parent partitions with bounded area tails."""
    mask = np.asarray(mask, dtype=bool)
    if mask.ndim != 2 or not np.any(mask):
        raise ValueError("parent mask must be a non-empty two-dimensional grid")
    cell_count = int(np.count_nonzero(mask))
    if type(province_count) is not int or not 1 <= province_count <= cell_count:
        raise ValueError("province count must fit the parent cell count")
    if type(iterations) is not int or iterations < 1:
        raise ValueError("iterations must be a positive integer")
    cells = np.argwhere(mask).astype(np.int32)
    center = cells.mean(axis=0)
    first = int(np.argmin(np.sum((cells - center) ** 2, axis=1)))
    anchor_rows = [cells[first]]
    nearest = np.sum((cells - cells[first]) ** 2, axis=1).astype(np.float64)
    while len(anchor_rows) < province_count:
        index = int(np.argmax(nearest))
        anchor_rows.append(cells[index])
        nearest = np.minimum(nearest, np.sum((cells - cells[index]) ** 2, axis=1))
    anchors = np.asarray(anchor_rows, dtype=np.int32)
    distances = np.sum((cells[:, None, :] - anchors[None, :, :]) ** 2, axis=2).astype(np.float32)
    targets = np.full(province_count, cell_count // province_count, dtype=np.int32)
    targets[:cell_count % province_count] += 1
    weights = np.zeros(province_count, dtype=np.float32)
    best_score: tuple[int, int] | None = None
    best_labels: np.ndarray | None = None
    for iteration in range(iterations):
        labels = np.argmin(distances - weights, axis=1).astype(np.int32)
        counts = np.bincount(labels, minlength=province_count)
        deviations = np.abs(counts - targets)
        score = (int(deviations.max()), int(deviations.sum()))
        if best_score is None or score < best_score:
            best_score, best_labels = score, labels.copy()
        if score == (0, 0):
            break
        rate = 0.05 if iteration < iterations * 2 // 3 else 0.015
        weights += rate * (targets - counts)
    assert best_labels is not None
    output = np.full(mask.shape, -1, dtype=np.int32)
    output[cells[:, 0], cells[:, 1]] = best_labels
    for index, (row, col) in enumerate(anchors):
        output[int(row), int(col)] = index
    output = repair_label_connectivity(output, mask, province_count)
    for label, anchor in enumerate(anchors):
        own_cells = np.argwhere(output == label)
        anchors[label] = own_cells[int(np.argmin(
            np.sum((own_cells - anchor) ** 2, axis=1)
        ))]
    return output, tuple((int(row), int(col)) for row, col in anchors)


@dataclass(frozen=True)
class ProvinceQualityPolicy:
    max_components: int = 1
    max_aspect_ratio: float = 4.0
    min_aspect_area: int = 0
    min_fill_ratio: float = 0.20
    min_fill_area: int = 0
    min_area: int | None = None
    max_area: int | None = None
    min_parent_median_ratio: float = 0.0
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
            ("minimumArea", policy.min_area is not None and 0 < metric.area < policy.min_area, "is below absolute area floor"),
            ("absoluteArea", policy.max_area is not None and metric.area > policy.max_area, "exceeds absolute area cap"),
            ("areaBelowParentMedian", metric.area < policy.min_parent_median_ratio * medians.get(metric.parent_region_id, metric.area), "is below parent median area band"),
            ("areaOutlier", metric.area > policy.max_parent_median_ratio * medians.get(metric.parent_region_id, metric.area), "exceeds parent median area band"),
            ("corridor", metric.corridor_length >= policy.corridor_min_length, "contains a narrow corridor"),
        )
        for key, failed, message in checks:
            if failed and (metric.province_id, key) not in waived:
                failures.append(f"{metric.province_id} {message}")
    if failures:
        raise ValueError("province quality failed: " + "; ".join(failures))
