"""Scope the historical route-node approval to its pinned scenario resources."""

from pathlib import Path


# The Yuzhou QA slice is a runtime scenario, not one of the 31 historical
# scenarios whose resource hashes were approved for the route-node selection.
ROUTE_NODE_EXCLUDED_RESOURCES = frozenset({"scenario_990002.json"})


def is_route_node_scenario_resource(path: Path) -> bool:
    return path.name not in ROUTE_NODE_EXCLUDED_RESOURCES
