"""Promote the locally verified 190.1 pilot into an explicit HWIHA seed.

Input is the gitignored output of verify_pilots.py. No wiki page, refined table,
or source label is read by this step. The hashes pin the reviewed offline inputs.
"""

import argparse
import copy
import hashlib
import json
from pathlib import Path


SOURCE_SHA256 = "e2d2792fcd5780a105e13626e9a032df1efd3010b906c21535294270d337baab"
REFINED_REVISION = "sha256:5f511438e36bd5b673370928365c8cef78d464a7683ec78105280d310e4a68fd"
SOURCE_ID = "rtk14-wikiwiki:190.1"
WORLD_FORMAT = "GENERAL_RETAINER_CAMPAIGN"
STAT_KEYS = ("leadership", "strength", "intelligence", "politics", "charm")
STAT_INDICES = (5, 6, 7, 14, 15)
REPO = Path(__file__).resolve().parents[2]
MAP_PATH = REPO / "infra/src/main/resources/map/han-world-v3.json"
TEMPLATE_PATH = REPO / "infra/src/main/resources/scenario/scenario_990002.json"
OUTPUT_PATH = REPO / "infra/src/main/resources/scenario/scenario_3190.json"


def promote(source_bytes: bytes, template: dict, map_cities: set[int]) -> dict:
    if hashlib.sha256(source_bytes).hexdigest() != SOURCE_SHA256:
        raise ValueError("190 pilot bytes differ from the reviewed generation")
    seed = json.loads(source_bytes)
    if seed["startYear"] != 190 or len(seed["nation"]) != 21 or len(seed["general"]) != 280:
        raise ValueError("190 pilot roster differs from the reviewed generation")
    if sum(row[3] > 0 for row in seed["general"]) != 249:
        raise ValueError("190 pilot affiliated roster must contain 249 officers")

    warehouse = copy.deepcopy(template["warehouses"])
    if warehouse["topologyHash"] != "eaf06460f978cbfb16a08cbaa65edf6ba71bc82cd12426a7a823baaba847db14":
        raise ValueError("warehouse template is not pinned to map4")
    stocks = {row["countyId"]: row["stock"] for row in warehouse["warehouses"]}
    if len(stocks) != len(warehouse["warehouses"]):
        raise ValueError("duplicate county warehouse")
    for stock in stocks.values():
        for resource in ("money", "grain", "iron", "timber", "horses"):
            stock[resource] = 0
    lords = []
    lord_by_nation = {}
    units = []
    for nation_id, nation in enumerate(seed["nation"], 1):
        rulers = [row for row in seed["general"] if row[3] == nation_id and row[8] == 12]
        if len(rulers) != 1:
            raise ValueError(f"nation {nation_id} needs exactly one active ruler")
        lord = rulers[0][1]
        capital = nation[8][0]
        if capital not in stocks:
            raise ValueError(f"capital {capital} is not an administrative county seat")
        stocks[capital]["money"] += nation[2]
        stocks[capital]["grain"] += nation[3]
        nation[2] = nation[3] = 0
        lords.append(lord)
        lord_by_nation[nation_id] = lord
        # PROPOSED initial game units: the existing S3 slice uses these values.
        for number in (1, 2):
            units.append({"general": lord, "name": f"{lord} 부곡 {number}", "troops": 4000,
                          "crewTypeId": 1100, "training": 50, "morale": 60, "provisions": 24000})

    names = set()
    policies = []
    for row in seed["general"]:
        name, officer_id = row[1], row[2]
        if name in names or not isinstance(officer_id, int) or not 10001 <= officer_id <= 11000:
            raise ValueError("duplicate name or invalid stable officer id")
        names.add(name)
        policies.append({"name": name, "statSourceId": SOURCE_ID,
                         "statSourceRevision": REFINED_REVISION, "officerId": officer_id,
                         "acceptsEnlistment": True,
                         "stats": dict(zip(STAT_KEYS, (row[index] for index in STAT_INDICES)))})

    references = {city for nation in seed["nation"] for city in nation[8]}
    references.update(row[4] for row in seed["general"] if row[4] is not None)
    if not references <= map_cities:
        raise ValueError(f"190 pilot references unknown map4 cities: {sorted(references - map_cities)}")
    seed["worldFormat"] = WORLD_FORMAT
    seed["lords"] = lords
    # PROVISIONAL gameplay ownership: affiliation is source-backed, personal hierarchy is not.
    # Future officers are declared here but receive a DB card only when their general exists.
    seed["retainers"] = [
        {"general": row[1], "master": lord_by_nation[row[3]]}
        for row in seed["general"] if row[3] > 0 and row[1] != lord_by_nation[row[3]]
    ]
    if len(seed["retainers"]) != 228 or len({row["general"] for row in seed["retainers"]}) != 228:
        raise ValueError("190 pilot must declare exactly 228 distinct affiliated retainers")
    seed["personPolicies"] = policies
    seed["warehouses"] = warehouse
    seed["units"] = units
    # The oath is a Romance contribution. It is deliberately not classified as a history claim.
    oath = (("유비", 10071), ("관우", 10853), ("장비", 10357))
    if any(next((row[2] for row in seed["general"] if row[1] == name), None) != officer_id
           for name, officer_id in oath):
        raise ValueError("Romance oath identity differs from the reviewed roster")
    seed["personBonds"] = [
        {"name": name, "kind": "OATH", "targetOfficerId": other_id,
         "evidenceIds": ["novel:三國演義:第一回"]}
        for name, officer_id in oath for other_name, other_id in oath if other_id != officer_id
    ]
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
    parser.add_argument("source", type=Path, help="gitignored verify_pilots.py scenario_3190.json")
    parser.add_argument("--output", type=Path, default=OUTPUT_PATH)
    args = parser.parse_args()
    template = json.loads(TEMPLATE_PATH.read_text(encoding="utf-8"))
    map_cities = {city["id"] for city in json.loads(MAP_PATH.read_text(encoding="utf-8"))["cities"]}
    result = promote(args.source.read_bytes(), template, map_cities)
    rendered = render(result)
    if json.loads(rendered) != result:
        raise ValueError("rendered 190 scenario differs from the promoted model")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(rendered, encoding="utf-8")


if __name__ == "__main__":
    main()
