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
    return ProvinceTopology(graph, frozenset(exterior), areas)


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

    def visit(node: str) -> None:
        nonlocal clock
        clock += 1
        discovery[node] = low[node] = clock
        children = 0
        for neighbour in sorted(graph.get(node, set()) & nodes):
            if neighbour not in discovery:
                parent[neighbour] = node
                children += 1
                visit(neighbour)
                low[node] = min(low[node], low[neighbour])
                if parent.get(node) is None and children > 1:
                    result.add(node)
                if parent.get(node) is not None and low[neighbour] >= discovery[node]:
                    result.add(node)
            elif neighbour != parent.get(node):
                low[node] = min(low[node], discovery[neighbour])

    for node in sorted(nodes):
        if node not in discovery:
            parent[node] = None
            visit(node)
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
    }
    return ScenarioOwnershipAudit(
        tuple(sorted(errors, key=lambda row: (row.code, row.province_ids))),
        tuple(sorted(allowed, key=lambda row: (row.code, row.province_ids))),
        tuple(sorted(review, key=lambda row: (row.code, row.province_ids))),
        metrics,
    )
