"""Re-key the materialized 3190 seed's warehouses to the current map template.

The earlier 280-person pilot promoter is retired. Full 3190 source rows are
materialized by tools/rtk14/build_rtk14_stats.py from the private workbook.
"""

import argparse
import copy
import json
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
MAP_PATH = REPO / "infra/src/main/resources/map/han-world-v3.json"
TEMPLATE_PATH = REPO / "infra/src/main/resources/scenario/scenario_990002.json"
OUTPUT_PATH = REPO / "infra/src/main/resources/scenario/scenario_3190.json"
# Current fresh-seed warehouse topology pin; archive pins remain unchanged.
TEMPLATE_TOPOLOGY_HASH = "da0189f7dc4f65df4d7fd2d9fa945b44bc1c575537bb0ea42b7378fb7439b00c"


def rewarehouse(existing: dict, template: dict, map_cities: set[int]) -> dict:
    """Re-key the promoted seed's county warehouses to the current template without the pilot.

    The gitignored pilot is not needed to follow a roster change: only county warehouses name the
    administrative county set. Non-zero stocks (capital treasuries) move over unchanged; every
    other current administrative county starts empty, as promote() seeds it.
    """
    seed = copy.deepcopy(existing)
    warehouse = copy.deepcopy(template["warehouses"])
    if warehouse["topologyHash"] != TEMPLATE_TOPOLOGY_HASH:
        raise ValueError("warehouse template is not pinned to the current release topology")
    rows = {row["countyId"]: row for row in warehouse["warehouses"]}
    if len(rows) != len(warehouse["warehouses"]):
        raise ValueError("duplicate county warehouse")
    for row in rows.values():
        for resource in ("money", "grain", "iron", "timber", "horses"):
            row["stock"][resource] = 0
    for row in seed["warehouses"]["warehouses"]:
        if any(row["stock"].values()):
            if row["countyId"] not in rows:
                raise ValueError(f"stocked county {row['countyId']} is not a current administrative county")
            rows[row["countyId"]]["stock"] = dict(row["stock"])
    references = {city for nation in seed["nation"] for city in nation[8]}
    references.update(row[4] for row in seed["general"] if row[4] is not None)
    if not references <= map_cities:
        raise ValueError(f"190 seed references cities outside the current map: {sorted(references - map_cities)}")
    seed["warehouses"] = warehouse
    return seed


def render(seed: dict) -> str:
    """Keep each officer and county on one line so the reviewed roster stays diffable."""
    def compact(value: object) -> str:
        return json.dumps(value, ensure_ascii=False, separators=(",", ":"))

    lines = ["{"]
    items = list(seed.items())
    for index, (key, value) in enumerate(items):
        suffix = "," if index < len(items) - 1 else ""
        if key == "warehouses":
            lines.append('  "warehouses": {')
            parts = list(value.items())
            for part_index, (field, body) in enumerate(parts):
                inner_suffix = "," if part_index < len(parts) - 1 else ""
                if field == "warehouses":
                    lines.append('    "warehouses": [')
                    rows = body
                    lines.extend(f"      {compact(row)}{',' if row_index < len(rows)-1 else ''}"
                                 for row_index, row in enumerate(rows))
                    lines.append(f"    ]{inner_suffix}")
                else:
                    lines.append(f"    {compact(field)}: {compact(body)}{inner_suffix}")
            lines.append(f"  }}{suffix}")
        elif isinstance(value, list):
            lines.append(f"  {compact(key)}: [")
            lines.extend(f"    {compact(row)}{',' if row_index < len(value)-1 else ''}"
                         for row_index, row in enumerate(value))
            lines.append(f"  ]{suffix}")
        else:
            lines.append(f"  {compact(key)}: {compact(value)}{suffix}")
    lines.append("}")
    return "\n".join(lines) + "\n"


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=OUTPUT_PATH)
    parser.add_argument("--rewarehouse", action="store_true",
                        help="re-key the committed seed's warehouses to the current template")
    args = parser.parse_args()
    if not args.rewarehouse:
        parser.error("this tool only supports --rewarehouse; use build_rtk14_stats.py for the roster")
    template = json.loads(TEMPLATE_PATH.read_text(encoding="utf-8"))
    map_cities = {city["id"] for city in json.loads(MAP_PATH.read_text(encoding="utf-8"))["cities"]}
    result = rewarehouse(json.loads(args.output.read_text(encoding="utf-8")), template, map_cities)
    rendered = render(result)
    if json.loads(rendered) != result:
        raise ValueError("rendered 190 scenario differs from the re-keyed model")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(rendered, encoding="utf-8")


if __name__ == "__main__":
    main()
