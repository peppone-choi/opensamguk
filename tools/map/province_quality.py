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
    labels: np.ndarray,
    valid_mask: np.ndarray,
    label_count: int,
    *,
    fixed_anchor_by_label: Mapping[int, tuple[int, int]] | None = None,
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
            fixed_anchor = (fixed_anchor_by_label or {}).get(label)
            keep = next(
                (piece for piece in pieces if fixed_anchor in piece),
                max(pieces, key=lambda piece: (len(piece), tuple(piece[0]))),
            )
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
    mask: np.ndarray,
    province_count: int,
    *,
    iterations: int = 320,
    fixed_anchors: Sequence[tuple[int, int]] = (),
    minimum_anchor_area: int = 1,
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
    if type(minimum_anchor_area) is not int or minimum_anchor_area < 1:
        raise ValueError("minimum anchor area must be a positive integer")
    if province_count * minimum_anchor_area > cell_count:
        raise ValueError("minimum anchor areas exceed the parent mask")
    if len(fixed_anchors) > province_count or len(set(fixed_anchors)) != len(fixed_anchors):
        raise ValueError("fixed anchors must be unique and fit the province count")
    if any(
        not (0 <= row < mask.shape[0] and 0 <= col < mask.shape[1] and mask[row, col])
        for row, col in fixed_anchors
    ):
        raise ValueError("fixed anchors must fall inside the parent mask")
    cells = np.argwhere(mask).astype(np.int32)
    component_by_cell = np.full(mask.shape, -1, dtype=np.int32)
    component_sizes: list[int] = []
    rows, cols = mask.shape
    for start_row, start_col in cells:
        if component_by_cell[start_row, start_col] >= 0:
            continue
        component = len(component_sizes)
        pending = [(int(start_row), int(start_col))]
        component_by_cell[start_row, start_col] = component
        size = 0
        while pending:
            row, col = pending.pop()
            size += 1
            for next_row, next_col in (
                (row - 1, col), (row + 1, col), (row, col - 1), (row, col + 1),
            ):
                if (0 <= next_row < rows and 0 <= next_col < cols
                        and mask[next_row, next_col]
                        and component_by_cell[next_row, next_col] < 0):
                    component_by_cell[next_row, next_col] = component
                    pending.append((next_row, next_col))
        component_sizes.append(size)
    component_capacities = np.asarray(
        [size // minimum_anchor_area for size in component_sizes], dtype=np.int32,
    )
    candidate_cells = np.asarray([
        cell for cell in cells
        if component_capacities[component_by_cell[tuple(cell)]] > 0
    ], dtype=np.int32)
    if len(candidate_cells) < province_count:
        raise ValueError("connected components cannot hold all minimum anchor footprints")
    center = candidate_cells.mean(axis=0)
    anchor_rows = [np.asarray(anchor, dtype=np.int32) for anchor in fixed_anchors]
    component_anchor_counts = np.zeros(len(component_sizes), dtype=np.int32)
    for row, col in anchor_rows:
        component = int(component_by_cell[row, col])
        component_anchor_counts[component] += 1
        if component_anchor_counts[component] > component_capacities[component]:
            raise ValueError("fixed anchors exceed connected component capacity")
    if anchor_rows:
        nearest = np.min(
            np.stack([
                np.sum((candidate_cells - anchor) ** 2, axis=1)
                for anchor in anchor_rows
            ]),
            axis=0,
        ).astype(np.float64)
    else:
        first = int(np.argmin(np.sum((candidate_cells - center) ** 2, axis=1)))
        anchor_rows = [candidate_cells[first]]
        component = int(component_by_cell[tuple(candidate_cells[first])])
        component_anchor_counts[component] += 1
        nearest = np.sum(
            (candidate_cells - candidate_cells[first]) ** 2, axis=1
        ).astype(np.float64)
    while len(anchor_rows) < province_count:
        available = np.asarray([
            component_anchor_counts[component_by_cell[tuple(cell)]]
            < component_capacities[component_by_cell[tuple(cell)]]
            for cell in candidate_cells
        ])
        if not np.any(available):
            raise ValueError("connected components cannot hold all province anchors")
        ranked = np.where(available, nearest, -1.0)
        index = int(np.argmax(ranked))
        anchor_rows.append(candidate_cells[index])
        component = int(component_by_cell[tuple(candidate_cells[index])])
        component_anchor_counts[component] += 1
        nearest = np.minimum(
            nearest,
            np.sum((candidate_cells - candidate_cells[index]) ** 2, axis=1),
        )
    anchors = np.asarray(anchor_rows, dtype=np.int32)
    protected = np.full(mask.shape, -1, dtype=np.int32)
    protected_counts = np.ones(province_count, dtype=np.int32)
    for label, (row, col) in enumerate(anchors):
        protected[int(row), int(col)] = label
    frontiers: list[set[tuple[int, int]]] = []
    for row, col in anchors:
        frontiers.append({
            (next_row, next_col)
            for next_row, next_col in (
                (int(row) - 1, int(col)), (int(row) + 1, int(col)),
                (int(row), int(col) - 1), (int(row), int(col) + 1),
            )
            if 0 <= next_row < mask.shape[0] and 0 <= next_col < mask.shape[1]
            and mask[next_row, next_col] and protected[next_row, next_col] < 0
        })
    while int(protected_counts.min()) < minimum_anchor_area:
        options = []
        for label in range(province_count):
            if protected_counts[label] >= minimum_anchor_area:
                continue
            boundary = frontiers[label]
            options.append((len(boundary), int(protected_counts[label]), label, boundary))
        if not options:
            break
        frontier_count, _, label, boundary = min(options, key=lambda row: row[:3])
        if frontier_count == 0:
            raise ValueError("cannot grow connected minimum anchor footprints")
        anchor_row, anchor_col = anchors[label]
        row, col = min(boundary, key=lambda cell: (
            (cell[0] - int(anchor_row)) ** 2 + (cell[1] - int(anchor_col)) ** 2,
            cell,
        ))
        protected[row, col] = label
        protected_counts[label] += 1
        for frontier in frontiers:
            frontier.discard((row, col))
        frontiers[label].update(
            (next_row, next_col)
            for next_row, next_col in (
                (row - 1, col), (row + 1, col), (row, col - 1), (row, col + 1),
            )
            if 0 <= next_row < mask.shape[0] and 0 <= next_col < mask.shape[1]
            and mask[next_row, next_col] and protected[next_row, next_col] < 0
        )
    distances = np.sum((cells[:, None, :] - anchors[None, :, :]) ** 2, axis=2).astype(np.float32)
    targets = np.full(province_count, cell_count // province_count, dtype=np.int32)
    targets[:cell_count % province_count] += 1
    weights = np.zeros(province_count, dtype=np.float32)
    best_score: tuple[int, int] | None = None
    best_labels: np.ndarray | None = None
    optimization_iterations = iterations
    for iteration in range(optimization_iterations):
        labels = np.argmin(distances - weights, axis=1).astype(np.int32)
        counts = np.bincount(labels, minlength=province_count)
        deviations = np.abs(counts - targets)
        score = (int(deviations.max()), int(deviations.sum()))
        if best_score is None or score < best_score:
            best_score, best_labels = score, labels.copy()
        if score == (0, 0):
            break
        rate = 0.05 if iteration < optimization_iterations * 2 // 3 else 0.015
        weights += rate * (targets - counts)
    assert best_labels is not None
    output = np.full(mask.shape, -1, dtype=np.int32)
    output[cells[:, 0], cells[:, 1]] = best_labels
    output[protected >= 0] = protected[protected >= 0]
    for index, (row, col) in enumerate(anchors):
        output[int(row), int(col)] = index
    fixed_anchor_by_label = {
        label: anchor for label, anchor in enumerate(fixed_anchors)
    }
    output = repair_label_connectivity(
        output,
        mask,
        province_count,
        fixed_anchor_by_label=fixed_anchor_by_label,
    )
    for index, (row, col) in enumerate(fixed_anchors):
        if int(output[row, col]) != index:
            raise ValueError("fixed anchor moved during connectivity repair")
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
