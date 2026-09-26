"""Scope the historical route-node approval to its pinned scenario resources."""

from pathlib import Path


# These HWIHA runtime scenarios were added after the 31 historical scenario
# resource hashes were approved for route-node selection. Their map resolves
# against the current Han artifacts at seed time; changing this pinned review
# set would require a separate route-node review.
ROUTE_NODE_EXCLUDED_RESOURCES = frozenset({"scenario_990002.json", "scenario_3190.json"})


def is_route_node_scenario_resource(path: Path) -> bool:
    return path.name not in ROUTE_NODE_EXCLUDED_RESOURCES
