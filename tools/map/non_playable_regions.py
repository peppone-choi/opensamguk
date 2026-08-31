"""Reviewed exclusions for land outside the playable Han-world scope."""

from __future__ import annotations

import json
from collections import deque
from pathlib import Path
from typing import Mapping, Protocol, Sequence

import numpy as np


class CellProjection(Protocol):
    def cell_lonlat(self, col: int, row: int) -> tuple[float, float]: ...


def _boundary_connected(mask: np.ndarray) -> np.ndarray:
    """Return only mask components connected to the outer grid boundary."""
    rows, cols = mask.shape
    connected = np.zeros(mask.shape, dtype=bool)
    pending: deque[tuple[int, int]] = deque()
    for row in range(rows):
        for col in (0, cols - 1):
            if mask[row, col] and not connected[row, col]:
                connected[row, col] = True
                pending.append((row, col))
    for col in range(cols):
        for row in (0, rows - 1):
            if mask[row, col] and not connected[row, col]:
                connected[row, col] = True
                pending.append((row, col))
    while pending:
        row, col = pending.popleft()
        for row_delta in (-1, 0, 1):
            for col_delta in (-1, 0, 1):
                if row_delta == col_delta == 0:
                    continue
                next_row, next_col = row + row_delta, col + col_delta
                if (0 <= next_row < rows and 0 <= next_col < cols
                        and mask[next_row, next_col]
                        and not connected[next_row, next_col]):
                    connected[next_row, next_col] = True
                    pending.append((next_row, next_col))
    return connected


def _smooth_blocked_provinces(
    province_owner: np.ndarray,
    blocked: set[int],
    *,
    forced: set[int],
    protected: set[int],
) -> set[int]:
    """Prune one-province black spikes while preserving reviewed decisions."""
    blocked = set(blocked) | set(forced)
    blocked -= protected
    adjacency: dict[int, set[int]] = {}
    for first, second in (
        (province_owner[:, :-1], province_owner[:, 1:]),
        (province_owner[:-1, :], province_owner[1:, :]),
    ):
        for left, right in zip(first.ravel(), second.ravel()):
            left, right = int(left), int(right)
            if left < 0 or right < 0 or left == right:
                continue
            adjacency.setdefault(left, set()).add(right)
            adjacency.setdefault(right, set()).add(left)
    boundary = {
        int(value)
        for edge in (
            province_owner[0, :], province_owner[-1, :],
            province_owner[:, 0], province_owner[:, -1],
        )
        for value in edge if value >= 0
    }
    changed = True
    while changed:
        removable = {
            province for province in blocked
            if province not in forced and province not in boundary
            and len(adjacency.get(province, set()) & blocked) < 2
        }
        changed = bool(removable)
        blocked -= removable
    return blocked


def load_non_playable_policy(path: str | Path) -> dict:
    document = json.loads(Path(path).read_text(encoding="utf-8"))
    if document.get("schemaVersion") != 1:
        raise ValueError("non-playable region policy schemaVersion must be 1")
    if document.get("mapVersion") != "han-world-v2":
        raise ValueError("non-playable region policy must target han-world-v2")
    if not isinstance(document.get("exclusions"), list):
        raise ValueError("non-playable region policy exclusions must be a list")
    if not isinstance(document.get("maximumEvidenceDistanceCells"), int):
        raise ValueError("non-playable region policy requires maximumEvidenceDistanceCells")
    if not isinstance(document.get("evidenceDistanceExemptParentRegionIds"), list):
        raise ValueError("non-playable region policy requires evidence distance exemptions")
    return document


def apply_non_playable_regions(
    terrain: np.ndarray,
    parent_owner: np.ndarray,
    parent_region_ids: Sequence[str],
    projection: CellProjection,
    policy: dict,
    *,
    out_of_scope_value: int,
    evidence_cells_by_parent: Mapping[str, Sequence[tuple[int, int]]] | None = None,
    province_owner: np.ndarray | None = None,
) -> np.ndarray:
    """Mutate terrain/ownership and return the reviewed OUT_OF_SCOPE mask."""
    if terrain.shape != parent_owner.shape:
        raise ValueError("terrain and parent ownership shapes differ")
    parent_index = {region_id: index for index, region_id in enumerate(parent_region_ids)}
    existing_out_of_scope = terrain == out_of_scope_value
    excluded = existing_out_of_scope.copy()
    forced_cells = existing_out_of_scope.copy()
    row_latitudes = np.array([
        projection.cell_lonlat(0, row)[1] for row in range(terrain.shape[0])
    ])
    for rule in policy["exclusions"]:
        required = {
            "id", "parentRegionId", "southOfLatitude", "terrain", "reason", "evidence",
        }
        missing = required - set(rule)
        if missing:
            raise ValueError(f"non-playable exclusion is missing {sorted(missing)}")
        if rule["terrain"] != "OUT_OF_SCOPE":
            raise ValueError(f"unsupported exclusion terrain {rule['terrain']!r}")
        region_id = rule["parentRegionId"]
        if region_id not in parent_index:
            raise ValueError(f"unknown parent region {region_id!r}")
        south = row_latitudes[:, None] < float(rule["southOfLatitude"])
        mask = (
            ((parent_owner == parent_index[region_id]) | (terrain == out_of_scope_value))
            & south
        )
        if not np.any(mask):
            raise ValueError(f"non-playable exclusion {rule['id']!r} matched no cells")
        excluded |= mask
        forced_cells |= mask
    maximum_distance = policy.get("maximumEvidenceDistanceCells")
    if maximum_distance is not None and not np.any(existing_out_of_scope):
        if evidence_cells_by_parent is None:
            raise ValueError("evidence cells are required by the non-playable region policy")
        exempt = set(policy.get("evidenceDistanceExemptParentRegionIds", []))
        unknown_exempt = exempt - set(parent_region_ids)
        if unknown_exempt:
            raise ValueError(f"unknown evidence distance exemptions {sorted(unknown_exempt)}")
        maximum_distance_squared = int(maximum_distance) ** 2
        distance_candidates = np.zeros(terrain.shape, dtype=bool)
        for region_id, index in parent_index.items():
            if region_id in exempt:
                continue
            parent_cells = np.argwhere(parent_owner == index)
            if not len(parent_cells):
                continue
            evidence = np.asarray(evidence_cells_by_parent.get(region_id, ()), dtype=np.int32)
            if not len(evidence):
                raise ValueError(f"parent region {region_id!r} has no recorded evidence cell")
            distance_squared = np.min(
                np.sum((parent_cells[:, None, :] - evidence[None, :, :]) ** 2, axis=2),
                axis=1,
            )
            unsupported_cells = parent_cells[distance_squared > maximum_distance_squared]
            if len(unsupported_cells):
                distance_candidates[unsupported_cells[:, 0], unsupported_cells[:, 1]] = True
        excluded |= _boundary_connected(distance_candidates)
    if province_owner is not None:
        if province_owner.shape != terrain.shape:
            raise ValueError("province ownership shape differs from terrain")
        snapped = existing_out_of_scope.copy()
        protected_provinces = set()
        if evidence_cells_by_parent is not None:
            for cells in evidence_cells_by_parent.values():
                for row, col in cells:
                    if 0 <= row < terrain.shape[0] and 0 <= col < terrain.shape[1]:
                        province = int(province_owner[row, col])
                        if province >= 0:
                            protected_provinces.add(province)
        blocked_provinces: set[int] = set()
        forced_provinces: set[int] = set()
        for province in np.unique(province_owner[province_owner >= 0]):
            province = int(province)
            cells = province_owner == province
            if province in protected_provinces:
                continue
            if np.any(forced_cells & cells):
                forced_provinces.add(province)
            if np.count_nonzero(excluded & cells) * 2 >= np.count_nonzero(cells):
                blocked_provinces.add(province)
        blocked_provinces = _smooth_blocked_provinces(
            province_owner,
            blocked_provinces,
            forced=forced_provinces,
            protected=protected_provinces,
        )
        for province in blocked_provinces:
            snapped |= province_owner == province
        excluded = snapped
    elif maximum_distance is not None and not np.any(existing_out_of_scope):
        raise ValueError("province ownership is required by the evidence distance policy")
    terrain[excluded] = out_of_scope_value
    parent_owner[excluded] = -1
    return excluded
