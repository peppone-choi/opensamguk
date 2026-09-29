"""Scope the historical route-node approval to its pinned scenario resources."""

from pathlib import Path


# These current runtime scenarios were added after the 31 historical scenario
# hashes were approved. Keep the pinned review set stable.
ROUTE_NODE_EXCLUDED_RESOURCES = frozenset({"scenario_990002.json", "scenario_3190.json"})


def is_route_node_scenario_resource(path: Path) -> bool:
    return path.name not in ROUTE_NODE_EXCLUDED_RESOURCES
