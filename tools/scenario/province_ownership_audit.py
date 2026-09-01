"""Topology audits for exact scenario province assignments.

The audit reports geometry; it never chooses or rewrites an owner.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Mapping, Sequence

from tools.scenario.province_ownership_contract import AuditAllowlistEntry
from tools.scenario.province_ownership_materializer import ProvinceAssignment


@dataclass(frozen=True)
class AuditFinding:
    code: str
    province_ids: tuple[str, ...]
    owner_nation_key: str | None
    detail: str


@dataclass(frozen=True)
class ScenarioOwnershipAudit:
    errors: tuple[AuditFinding, ...]
    allowed_findings: tuple[AuditFinding, ...]
    review_findings: tuple[AuditFinding, ...]
    metrics: Mapping[str, int | float]


@dataclass(frozen=True)
class ProvinceTopology:
    graph: Mapping[str, set[str]]
    exterior_province_ids: frozenset[str]
    province_areas: Mapping[str, int]
    parent_by_province: Mapping[str, str]
    parent_graph: Mapping[str, set[str]]
    han_commandery_parent_ids: frozenset[str]


def topology_from_map(map_doc: Mapping[str, Any]) -> ProvinceTopology:
    """Decode stable IDs, edge adjacency, RLE area, and map-border contact."""
    records = map_doc["provinceRecords"]
    ids = [row["id"] for row in records]
    graph = {province_id: set() for province_id in ids}
    for edge in map_doc["adjacency"]["county"]:
        left, right = ids[edge["a"]], ids[edge["b"]]
        graph[left].add(right)
        graph[right].add(left)

    cols = int(map_doc["_meta"]["cols"])
    rows = int(map_doc["_meta"]["rows"])
    areas = {province_id: 0 for province_id in ids}
    exterior: set[str] = set()
    offset = 0
    owner_grid: list[int] = []
    for province_index, count in map_doc["owner"]:
        owner_grid.extend([province_index] * count)
        if province_index >= 0:
            province_id = ids[province_index]
            areas[province_id] += count
        offset += count
    if offset != cols * rows:
        raise ValueError(f"owner RLE covers {offset} cells, expected {cols * rows}")
    for position, province_index in enumerate(owner_grid):
        if province_index < 0:
            continue
        row, col = divmod(position, cols)
        if row in {0, rows - 1} or col in {0, cols - 1}:
            exterior.add(ids[province_index])
            continue
        neighbours = (position - 1, position + 1, position - cols, position + cols)
        if any(owner_grid[neighbour] < 0 for neighbour in neighbours):
            exterior.add(ids[province_index])
    parent_rows = map_doc.get("parentRegions", [])
    parent_ids = [row["id"] for row in parent_rows]
    if not parent_ids:
        parent_ids = sorted({row["parentRegionId"] for row in records})
    parent_graph = {parent_id: set() for parent_id in parent_ids}
    for edge in map_doc["adjacency"].get("commandery", []):
        left, right = parent_ids[edge["a"]], parent_ids[edge["b"]]
        parent_graph[left].add(right)
        parent_graph[right].add(left)
    parent_by_province = {row["id"]: row["parentRegionId"] for row in records}
    han_commandery_parent_ids = frozenset(
        row["id"] for row in parent_rows
        if row["administrativeSystem"] == "HAN_COMMANDERY"
    )
    return ProvinceTopology(
        graph,
        frozenset(exterior),
        areas,
        parent_by_province,
        parent_graph,
        han_commandery_parent_ids,
    )


def _components(nodes: set[str], graph: Mapping[str, set[str]]) -> list[tuple[str, ...]]:
    unseen = set(nodes)
    result: list[tuple[str, ...]] = []
    while unseen:
        start = min(unseen)
        stack = [start]
        unseen.remove(start)
        component: list[str] = []
        while stack:
            current = stack.pop()
            component.append(current)
            for neighbour in sorted(graph.get(current, set()), reverse=True):
                if neighbour in unseen:
                    unseen.remove(neighbour)
                    stack.append(neighbour)
        result.append(tuple(sorted(component)))
    return sorted(result, key=lambda values: (-len(values), values))


def _articulation_points(nodes: set[str], graph: Mapping[str, set[str]]) -> set[str]:
    discovery: dict[str, int] = {}
    low: dict[str, int] = {}
    parent: dict[str, str | None] = {}
    result: set[str] = set()
    clock = 0

    for root in sorted(nodes):
        if root in discovery:
            continue
        parent[root] = None
        clock += 1
        discovery[root] = low[root] = clock
        # Each frame holds node, ordered neighbours, next index, and DFS-tree child count.
        stack: list[list[Any]] = [[root, sorted(graph.get(root, set()) & nodes), 0, 0]]
        while stack:
            node, neighbours, next_index, child_count = stack[-1]
            if next_index < len(neighbours):
                neighbour = neighbours[next_index]
                stack[-1][2] += 1
                if neighbour not in discovery:
                    stack[-1][3] += 1
                    parent[neighbour] = node
                    clock += 1
                    discovery[neighbour] = low[neighbour] = clock
                    stack.append([
                        neighbour,
                        sorted(graph.get(neighbour, set()) & nodes),
                        0,
                        0,
                    ])
                elif neighbour != parent.get(node):
                    low[node] = min(low[node], discovery[neighbour])
                continue

            stack.pop()
            ancestor = parent.get(node)
            if ancestor is None:
                if child_count > 1:
                    result.add(node)
                continue
            low[ancestor] = min(low[ancestor], low[node])
            if parent.get(ancestor) is not None and low[node] >= discovery[ancestor]:
                result.add(ancestor)
    return result


def _is_allowed(finding: AuditFinding, allowlist: Sequence[AuditAllowlistEntry]) -> bool:
    target = frozenset(finding.province_ids)
    return any(
        row.audit_kind == finding.code and frozenset(row.province_ids) == target
        for row in allowlist
    )


def audit_assignments(
    assignments: Sequence[ProvinceAssignment],
    graph: Mapping[str, set[str]],
    *,
    exterior_province_ids: frozenset[str] = frozenset(),
    allowlist: Sequence[AuditAllowlistEntry] = (),
    province_areas: Mapping[str, int] | None = None,
    parent_by_province: Mapping[str, str] | None = None,
    parent_graph: Mapping[str, set[str]] | None = None,
    han_commandery_parent_ids: frozenset[str] = frozenset(),
) -> ScenarioOwnershipAudit:
    errors: list[AuditFinding] = []
    allowed: list[AuditFinding] = []
    review: list[AuditFinding] = []
    by_id = {row.province_id: row for row in assignments}
    if len(by_id) != len(assignments):
        errors.append(AuditFinding("DUPLICATE_ASSIGNMENT", (), None, "Province assignment IDs are not unique."))
    missing = tuple(sorted(set(graph) - by_id.keys()))
    extra = tuple(sorted(by_id.keys() - set(graph)))
    if missing:
        errors.append(AuditFinding("MISSING_ASSIGNMENT", missing, None, "Graph provinces lack assignment rows."))
    if extra:
        errors.append(AuditFinding("UNKNOWN_ASSIGNMENT", extra, None, "Assignment rows are outside the graph."))
    if errors:
        return ScenarioOwnershipAudit(tuple(errors), (), (), {"provinceCount": len(by_id)})

    owner_by_id = {province_id: row.owner_nation_key for province_id, row in by_id.items()}
    unowned = {province_id for province_id, owner in owner_by_id.items() if owner is None}
    for component in _components(unowned, graph):
        if set(component) & exterior_province_ids:
            continue
        boundary_owners = {
            owner_by_id[neighbour]
            for province_id in component
            for neighbour in graph.get(province_id, set())
            if neighbour not in component and owner_by_id[neighbour] is not None
        }
        if len(boundary_owners) == 1:
            finding = AuditFinding(
                "UNALLOWLISTED_HOLE",
                component,
                next(iter(boundary_owners)),
                "An interior unowned component is bounded by one owner.",
            )
            (allowed if _is_allowed(finding, allowlist) else errors).append(finding)

    commandery_review_count = 0
    if parent_by_province is not None and parent_graph is not None:
        provinces_by_parent: dict[str, set[str]] = {}
        for province_id, parent_id in parent_by_province.items():
            provinces_by_parent.setdefault(parent_id, set()).add(province_id)
        owner_by_parent: dict[str, str | None] = {}
        for parent_id, province_ids in provinces_by_parent.items():
            owners = {owner_by_id[province_id] for province_id in province_ids}
            owner_by_parent[parent_id] = next(iter(owners)) if len(owners) == 1 else None
        for parent_id in sorted(han_commandery_parent_ids):
            province_ids = provinces_by_parent.get(parent_id, set())
            if not province_ids or any(owner_by_id[province_id] is not None for province_id in province_ids):
                continue
            neighbouring_owners = [
                owner_by_parent.get(neighbour)
                for neighbour in parent_graph.get(parent_id, set())
            ]
            candidates = {
                owner for owner in neighbouring_owners
                if owner is not None and neighbouring_owners.count(owner) >= 2
            }
            for owner in sorted(candidates):
                commandery_review_count += 1
                review.append(AuditFinding(
                    "UNOWNED_COMMANDERY_REVIEW",
                    tuple(sorted(province_ids)),
                    owner,
                    "An unowned Han commandery borders at least two commanderies held by one owner.",
                ))

    owners = sorted({owner for owner in owner_by_id.values() if owner is not None})
    articulation_count = 0
    spike_count = 0
    component_count = 0
    for owner in owners:
        nodes = {province_id for province_id, value in owner_by_id.items() if value == owner}
        components = _components(nodes, graph)
        component_count += len(components)
        for component in components[1:]:
            attested = any(by_id[province_id].confidence in {"DIRECT", "IF"} for province_id in component)
            if not attested:
                finding = AuditFinding(
                    "UNEXPLAINED_ISOLATED_COMPONENT",
                    component,
                    owner,
                    "A secondary owner component has no direct or IF claim.",
                )
                (allowed if _is_allowed(finding, allowlist) else errors).append(finding)
        articulations = _articulation_points(nodes, graph)
        articulation_count += len(articulations)
        for province_id in sorted(articulations):
            review.append(AuditFinding(
                "NARROW_CONNECTOR_REVIEW",
                (province_id,),
                owner,
                "Removing this province disconnects the owner's territory graph.",
            ))
        for province_id in sorted(nodes):
            same_owner_degree = len(graph.get(province_id, set()) & nodes)
            foreign_degree = len(graph.get(province_id, set()) - nodes)
            if same_owner_degree <= 1 and foreign_degree >= 2:
                spike_count += 1
                review.append(AuditFinding(
                    "ONE_PROVINCE_SPIKE_REVIEW",
                    (province_id,),
                    owner,
                    "A low-degree owned province projects into other ownership or unowned space.",
                ))

    areas = province_areas or {}
    owned_area = sum(areas.get(pid, 1) for pid, owner in owner_by_id.items() if owner is not None)
    boundary_edges = sum(
        1
        for province_id, neighbours in graph.items()
        for neighbour in neighbours
        if province_id < neighbour and owner_by_id[province_id] != owner_by_id[neighbour]
    )
    metrics: dict[str, int | float] = {
        "provinceCount": len(by_id),
        "ownedProvinceCount": len(by_id) - len(unowned),
        "unownedProvinceCount": len(unowned),
        "ownerComponentCount": component_count,
        "articulationProvinceCount": articulation_count,
        "spikeProvinceCount": spike_count,
        "politicalBoundaryEdgeCount": boundary_edges,
        "ownedAreaCells": owned_area,
        "boundaryEdgesPerOwnedArea": 0.0 if owned_area == 0 else boundary_edges / owned_area,
        "unownedCommanderyReviewCount": commandery_review_count,
    }
    return ScenarioOwnershipAudit(
        tuple(sorted(errors, key=lambda row: (row.code, row.province_ids))),
        tuple(sorted(allowed, key=lambda row: (row.code, row.province_ids))),
        tuple(sorted(review, key=lambda row: (row.code, row.province_ids))),
        metrics,
    )
