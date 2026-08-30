import json
import re
from pathlib import Path

from refine_officers import load_mapping


ACTIVE_STATUSES = frozenset({"君主", "太守", "都督", "一般"})
NATION_TYPE_ALIASES = frozenset({
    "중립",
    "도적",
    "명가",
    "음양가",
    "종횡가",
    "불가",
    "오두미도",
    "태평도",
    "도가",
    "묵가",
    "덕가",
    "병가",
    "유가",
    "법가",
})
COLOR = re.compile(r"#[0-9A-F]{6}\Z")
HANGUL = re.compile(r"[가-힣]")
STABLE_ID = re.compile(r"[1-9][0-9]*\Z")
YEAR_MONTH = re.compile(r"([1-9][0-9]*)\.([1-9]|1[0-2])\Z")
SCENARIO_DIRECTORY = Path(__file__).resolve().parent
CITY_GRAPH_PATH = SCENARIO_DIRECTORY.parents[1] / "infra/src/main/resources/scenario/cities_1010.json"


def _reject_duplicate_keys(pairs: list[tuple[str, object]]) -> dict:
    result: dict = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"duplicate JSON object key: {key}")
        result[key] = value
    return result


def _is_int(value: object) -> bool:
    return isinstance(value, int) and not isinstance(value, bool)


def _read_json(path: Path) -> object:
    return json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=_reject_duplicate_keys)


def load_manifest(path: Path) -> dict:
    data = _read_json(path)
    if not isinstance(data, dict):
        raise ValueError("manifest root must be an object")
    return data


def _load_defaults() -> dict:
    defaults = _read_json(SCENARIO_DIRECTORY / "defaults.json")
    if not isinstance(defaults, dict):
        raise ValueError("defaults.json must be an object")
    required = {"palette", "scale_by_member_count", "resources_by_scale", "ideology", "diplomacy"}
    if set(defaults) != required:
        raise ValueError("defaults.json has an unexpected contract")
    palette = defaults["palette"]
    if not isinstance(palette, list) or not palette or not all(isinstance(color, str) and COLOR.fullmatch(color) for color in palette):
        raise ValueError("defaults palette is invalid")
    scale_by_member_count = defaults["scale_by_member_count"]
    if not isinstance(scale_by_member_count, list) or not scale_by_member_count:
        raise ValueError("defaults scale_by_member_count is invalid")
    previous_count: int | None = None
    scale_values: set[int] = set()
    for entry in scale_by_member_count:
        if not isinstance(entry, list) or len(entry) != 2 or not all(_is_int(value) and value > 0 for value in entry):
            raise ValueError("defaults scale_by_member_count is invalid")
        member_count, scale = entry
        if previous_count is not None and member_count >= previous_count:
            raise ValueError("defaults scale_by_member_count must descend")
        previous_count = member_count
        scale_values.add(scale)
    resources = defaults["resources_by_scale"]
    if not isinstance(resources, dict) or set(resources) != {str(scale) for scale in scale_values}:
        raise ValueError("defaults resources_by_scale is invalid")
    for values in resources.values():
        if not isinstance(values, list) or len(values) != 3 or not all(_is_int(value) and value >= 0 for value in values):
            raise ValueError("defaults resources_by_scale is invalid")
    if not isinstance(defaults["ideology"], str) or defaults["ideology"] not in NATION_TYPE_ALIASES:
        raise ValueError("defaults ideology is invalid")
    diplomacy = defaults["diplomacy"]
    required_diplomacy = {"neutral_state", "neutral_term", "war_state", "war_term"}
    if not isinstance(diplomacy, dict) or set(diplomacy) != required_diplomacy:
        raise ValueError("defaults diplomacy is invalid")
    if not all(_is_int(value) and value >= 0 for value in diplomacy.values()):
        raise ValueError("defaults diplomacy is invalid")
    return defaults


def derive_nation_defaults(lord_id: int, members: list[dict], ordinal: int) -> dict:
    if not _is_int(lord_id) or lord_id <= 0:
        raise ValueError("lord_id must be a positive integer")
    if not _is_int(ordinal) or ordinal < 0:
        raise ValueError("ordinal must be a non-negative integer")
    lord = next((member for member in members if member.get("id") == lord_id), None)
    if lord is None or not isinstance(lord.get("name_korean"), str) or not lord["name_korean"]:
        raise ValueError(f"lord {lord_id} requires a Korean name")
    defaults = _load_defaults()
    scale = next(
        (value for minimum, value in defaults["scale_by_member_count"] if len(members) >= minimum),
        None,
    )
    if scale is None:
        raise ValueError(f"lord {lord_id} requires at least one member")
    gold, rice, tech = defaults["resources_by_scale"][str(scale)]
    return {
        "lord_id": lord_id,
        "name": lord["name_korean"],
        "color": defaults["palette"][ordinal % len(defaults["palette"])],
        "gold": gold,
        "rice": rice,
        "tech": tech,
        "ideology": defaults["ideology"],
        "scale": scale,
    }


def _validate_root(data: dict) -> tuple[str, int, int]:
    allowed = {
        "code",
        "number",
        "title",
        "year_month",
        "startYear",
        "map",
        "life",
        "fiction",
        "const",
        "nations",
        "diplomacy",
        "overrides",
    }
    required = {"code", "number", "title", "year_month", "startYear", "map", "life", "fiction", "const", "nations"}
    missing = sorted(required - set(data))
    unexpected = sorted(set(data) - allowed)
    if missing or unexpected:
        raise ValueError(f"manifest fields are invalid: missing={missing}, unexpected={unexpected}")
    code = data["code"]
    number = data["number"]
    start_year = data["startYear"]
    if not isinstance(code, str) or not _is_int(number) or not _is_int(start_year):
        raise ValueError("manifest code, number, and startYear are invalid")
    if number != 3000 + start_year or code != f"scenario_{number}":
        raise ValueError("manifest code, number, and startYear must match")
    year_month = data["year_month"]
    match = YEAR_MONTH.fullmatch(year_month) if isinstance(year_month, str) else None
    if match is None or int(match.group(1)) != start_year:
        raise ValueError("manifest year_month and startYear must match")
    title = data["title"]
    if not isinstance(title, str) or not title.strip() or HANGUL.search(title) is None:
        raise ValueError("manifest title must be a custom Korean title")
    if (
        data["map"] != "han-world-v2"
        or not _is_int(data["life"])
        or data["life"] != 1
        or not _is_int(data["fiction"])
        or data["fiction"] != 0
    ):
        raise ValueError("manifest fixed game settings are invalid")
    const = data["const"]
    if (
        not isinstance(const, dict)
        or set(const) != {"defaultMaxGeneral"}
        or not _is_int(const["defaultMaxGeneral"])
        or const["defaultMaxGeneral"] != 600
    ):
        raise ValueError("manifest const must be defaultMaxGeneral 600")
    return year_month, number, start_year


def _nation_ids(data: dict) -> list[int]:
    nations = data["nations"]
    if not isinstance(nations, list) or not nations:
        raise ValueError("manifest nations must be a non-empty list")
    ids: list[int] = []
    for nation in nations:
        if not isinstance(nation, dict) or set(nation) != {"lord_id"}:
            raise ValueError("each nation entry must contain only lord_id")
        lord_id = nation["lord_id"]
        if not _is_int(lord_id) or lord_id <= 0:
            raise ValueError("nation lord_id must be a positive integer")
        if lord_id in ids:
            raise ValueError(f"duplicate lord_id: {lord_id}")
        ids.append(lord_id)
    return ids


def _city_for_location(location: object, city_names: set[str], city_map: dict[str, str], remap: dict[str, str]) -> str:
    if not isinstance(location, str) or not location:
        raise ValueError("active faction member has no location")
    city = location if location in city_names else city_map.get(location, remap.get(location))
    if city not in city_names:
        raise ValueError(f"active faction member location is unresolved: {location}")
    return city


def _active_factions(refined: list[dict], year_month: str, city_names: set[str]) -> tuple[dict[int, list[dict]], dict[int, str]]:
    if not isinstance(refined, list):
        raise ValueError("refined input must be a list")
    city_map = load_mapping(SCENARIO_DIRECTORY / "city_map.json")
    remap = load_mapping(SCENARIO_DIRECTORY / "location-remap.yaml")
    members_by_faction: dict[str, list[dict]] = {}
    rulers_by_faction: dict[str, list[int]] = {}
    seen_ids: set[int] = set()
    for record in refined:
        if not isinstance(record, dict) or not _is_int(record.get("id")) or record["id"] <= 0:
            raise ValueError("refined officer requires a positive stable id")
        officer_id = record["id"]
        if officer_id in seen_ids:
            raise ValueError(f"refined input has a duplicate stable id: {officer_id}")
        seen_ids.add(officer_id)
        scenarios = record.get("scenarios")
        if not isinstance(scenarios, list):
            raise ValueError(f"refined officer {officer_id} scenarios must be a list")
        matching = [entry for entry in scenarios if isinstance(entry, dict) and entry.get("year_month") == year_month]
        if len(matching) > 1:
            raise ValueError(f"refined officer {officer_id} has duplicate {year_month} rows")
        if not matching:
            continue
        scenario = matching[0]
        if scenario.get("status") not in ACTIVE_STATUSES:
            continue
        faction = scenario.get("faction")
        if not isinstance(faction, str) or not faction.strip():
            raise ValueError(f"refined officer {officer_id} has a missing active faction")
        name_kanji = record.get("name_kanji")
        name_korean = record.get("name_korean")
        if not isinstance(name_kanji, str) or not name_kanji or not isinstance(name_korean, str) or not name_korean:
            raise ValueError(f"refined officer {officer_id} has an incomplete identity")
        member = {
            "id": officer_id,
            "name_korean": name_korean,
            "city": _city_for_location(scenario.get("location"), city_names, city_map, remap),
        }
        members_by_faction.setdefault(faction, []).append(member)
        if scenario.get("status") == "君主" and faction == name_kanji:
            rulers_by_faction.setdefault(faction, []).append(officer_id)
    members_by_lord: dict[int, list[dict]] = {}
    faction_by_lord: dict[int, str] = {}
    for faction, members in members_by_faction.items():
        rulers = sorted(rulers_by_faction.get(faction, []))
        if len(rulers) != 1:
            raise ValueError(f"active faction has no unique valid ruler: {faction}")
        lord_id = rulers[0]
        members_by_lord[lord_id] = sorted(members, key=lambda member: member["id"])
        faction_by_lord[lord_id] = faction
    if not members_by_lord:
        raise ValueError("manifest year_month has no active factions")
    return members_by_lord, faction_by_lord


def _load_city_graph() -> tuple[dict[str, int], dict[str, set[str]]]:
    raw = _read_json(CITY_GRAPH_PATH)
    if not isinstance(raw, dict) or not isinstance(raw.get("cities"), list):
        raise ValueError("city graph must contain a cities list")
    city_ids: dict[str, int] = {}
    declared_connections: dict[str, list[str]] = {}
    for city in raw["cities"]:
        if not isinstance(city, dict) or not _is_int(city.get("id")) or city["id"] <= 0 or not isinstance(city.get("name"), str) or not city["name"]:
            raise ValueError("city graph contains an invalid city")
        name = city["name"]
        if name in city_ids or city["id"] in city_ids.values():
            raise ValueError("city graph has duplicate city ids or names")
        connections = city.get("connected", city.get("connections"))
        if not isinstance(connections, list) or not all(isinstance(target, str) and target for target in connections):
            raise ValueError(f"city graph has invalid connections for {name}")
        city_ids[name] = city["id"]
        declared_connections[name] = connections
    neighbors: dict[str, set[str]] = {name: set() for name in city_ids}
    for name, connections in declared_connections.items():
        for target in connections:
            if target not in city_ids:
                raise ValueError(f"city graph has an unknown connection from {name}")
            neighbors[name].add(target)
            neighbors[target].add(name)
    return city_ids, neighbors


def _nearest_unowned_city(
    source_city: str,
    unavailable: set[str],
    city_ids: dict[str, int],
    neighbors: dict[str, set[str]],
) -> str:
    visited = {source_city}
    frontier = [source_city]
    while frontier:
        next_frontier = sorted(
            {neighbor for city in frontier for neighbor in neighbors[city] if neighbor not in visited},
            key=lambda city: (city_ids[city], city),
        )
        if not next_frontier:
            break
        candidates = [city for city in next_frontier if city not in unavailable]
        if candidates:
            return min(candidates, key=lambda city: (city_ids[city], city))
        visited.update(next_frontier)
        frontier = next_frontier
    raise ValueError(f"no unowned city is reachable from contested source city: {source_city}")


def _derive_cities(members_by_lord: dict[int, list[dict]]) -> tuple[dict[int, list[str]], list[dict]]:
    lord_cities: dict[int, str] = {}
    city_members: dict[str, dict[int, int]] = {}
    for lord_id, members in members_by_lord.items():
        lord_members = [member for member in members if member["id"] == lord_id]
        if len(lord_members) != 1:
            raise ValueError(f"lord {lord_id} is missing from its active faction")
        lord_cities[lord_id] = lord_members[0]["city"]
        for member in members:
            city = member["city"]
            counts = city_members.setdefault(city, {})
            counts[lord_id] = counts.get(lord_id, 0) + 1
    claimants_by_city: dict[str, list[int]] = {}
    for lord_id, city in lord_cities.items():
        claimants_by_city.setdefault(city, []).append(lord_id)
    owned: dict[int, set[str]] = {lord_id: set() for lord_id in members_by_lord}
    collision_losers: list[tuple[int, str]] = []
    for city, claimants in sorted(claimants_by_city.items()):
        ranked = sorted(claimants, key=lambda lord_id: (-len(members_by_lord[lord_id]), lord_id))
        owned[ranked[0]].add(city)
        collision_losers.extend((lord_id, city) for lord_id in ranked[1:])
    relocations: list[dict] = []
    if collision_losers:
        city_ids, neighbors = _load_city_graph()
        missing_source_cities = sorted(set(lord_cities.values()) - set(city_ids))
        if missing_source_cities:
            raise ValueError("a source city is absent from the city graph")
        reserved = set(claimants_by_city)
        for lord_id, source_city in collision_losers:
            already_owned = {city for cities in owned.values() for city in cities}
            assigned_city = _nearest_unowned_city(source_city, reserved | already_owned, city_ids, neighbors)
            owned[lord_id].add(assigned_city)
            relocations.append({
                "lord_id": lord_id,
                "source_city": source_city,
                "assigned_city": assigned_city,
                "reason": "lord_city_collision",
            })
    assigned_cities = {city for cities in owned.values() for city in cities}
    for city, counts in city_members.items():
        if city in assigned_cities:
            continue
        owner = min(counts, key=lambda lord_id: (-counts[lord_id], lord_id))
        owned[owner].add(city)
        assigned_cities.add(city)
    result: dict[int, list[str]] = {}
    for lord_id, cities in owned.items():
        relocation = next((entry for entry in relocations if entry["lord_id"] == lord_id), None)
        capital = relocation["assigned_city"] if relocation is not None else lord_cities[lord_id]
        if capital not in cities:
            raise ValueError(f"lord {lord_id} cannot own its capital city")
        result[lord_id] = [capital, *sorted(city for city in cities if city != capital)]
    return result, relocations


def _overrides(data: dict, lord_ids: set[int], city_names: set[str]) -> dict[int, dict]:
    raw = data.get("overrides", {})
    if not isinstance(raw, dict):
        raise ValueError("manifest overrides must be an object")
    allowed = {"name", "color", "gold", "rice", "tech", "ideology", "scale", "cities"}
    parsed: dict[int, dict] = {}
    for stable_id, values in raw.items():
        if not isinstance(stable_id, str) or STABLE_ID.fullmatch(stable_id) is None:
            raise ValueError("overrides must be keyed by stable lord id")
        lord_id = int(stable_id)
        if lord_id not in lord_ids:
            raise ValueError(f"override references an unknown or inactive ruler: {lord_id}")
        if not isinstance(values, dict) or not values or set(values) - allowed:
            raise ValueError(f"override for lord {lord_id} is invalid")
        if lord_id in parsed:
            raise ValueError(f"duplicate override for lord {lord_id}")
        if "name" in values and (not isinstance(values["name"], str) or not values["name"].strip() or HANGUL.search(values["name"]) is None):
            raise ValueError(f"override name for lord {lord_id} must be Korean")
        if "color" in values and (not isinstance(values["color"], str) or COLOR.fullmatch(values["color"]) is None):
            raise ValueError(f"override color for lord {lord_id} is invalid")
        for resource in ("gold", "rice", "tech"):
            if resource in values and (not _is_int(values[resource]) or values[resource] < 0):
                raise ValueError(f"override {resource} for lord {lord_id} is invalid")
        if "ideology" in values and (
            not isinstance(values["ideology"], str)
            or values["ideology"] not in NATION_TYPE_ALIASES
        ):
            raise ValueError(f"override ideology for lord {lord_id} is invalid")
        if "scale" in values and (not _is_int(values["scale"]) or values["scale"] not in range(1, 9)):
            raise ValueError(f"override scale for lord {lord_id} is invalid")
        if "cities" in values:
            cities = values["cities"]
            if not isinstance(cities, list) or not cities:
                raise ValueError(f"override cities for lord {lord_id} must declare a capital")
            if not all(isinstance(city, str) and city in city_names for city in cities) or len(cities) != len(set(cities)):
                raise ValueError(f"override cities for lord {lord_id} are invalid")
        parsed[lord_id] = values
    return parsed


def _validate_diplomacy(data: dict, lord_ids: set[int], defaults: dict) -> list[list[int]]:
    diplomacy = data.get("diplomacy", [])
    if not isinstance(diplomacy, list):
        raise ValueError("manifest diplomacy must be a list")
    terms_by_state = {
        defaults["diplomacy"]["neutral_state"]: defaults["diplomacy"]["neutral_term"],
        defaults["diplomacy"]["war_state"]: defaults["diplomacy"]["war_term"],
    }
    result: list[list[int]] = []
    seen: set[tuple[int, int]] = set()
    for entry in diplomacy:
        if not isinstance(entry, list) or len(entry) != 4 or not all(_is_int(value) for value in entry):
            raise ValueError("diplomacy entries must be [source_lord_id, target_lord_id, state, term]")
        source_lord_id, target_lord_id, state, term = entry
        if source_lord_id not in lord_ids or target_lord_id not in lord_ids or source_lord_id == target_lord_id:
            raise ValueError("diplomacy references an unknown or identical stable lord id")
        if state not in terms_by_state or term != terms_by_state[state]:
            raise ValueError("diplomacy state or term is invalid")
        pair = source_lord_id, target_lord_id
        if pair in seen:
            raise ValueError("diplomacy contains a duplicate directed pair")
        seen.add(pair)
        result.append([source_lord_id, target_lord_id, state, term])
    return sorted(result)


def validate_manifest(data: dict, refined: list[dict], city_names: set[str]) -> dict:
    if not isinstance(data, dict):
        raise ValueError("manifest root must be an object")
    if not isinstance(city_names, set) or not city_names or not all(isinstance(city, str) and city for city in city_names):
        raise ValueError("city_names must be a non-empty set of city names")
    year_month, _, _ = _validate_root(data)
    input_lord_ids = _nation_ids(data)
    members_by_lord, _ = _active_factions(refined, year_month, city_names)
    valid_lord_ids = set(members_by_lord)
    unknown_lords = sorted(set(input_lord_ids) - valid_lord_ids)
    if unknown_lords:
        raise ValueError(f"unknown or inactive ruler lord_id: {unknown_lords}")
    uncovered_lords = sorted(valid_lord_ids - set(input_lord_ids))
    if uncovered_lords:
        raise ValueError(f"uncovered active factions for lord ids: {uncovered_lords}")
    cities_by_lord, city_relocations = _derive_cities(members_by_lord)
    overrides = _overrides(data, valid_lord_ids, city_names)
    nations: list[dict] = []
    all_cities: dict[str, int] = {}
    for ordinal, lord_id in enumerate(sorted(valid_lord_ids)):
        nation = derive_nation_defaults(lord_id, members_by_lord[lord_id], ordinal)
        owned_cities = cities_by_lord[lord_id]
        override = overrides.get(lord_id, {})
        if "cities" in override:
            override_cities = override["cities"]
            if override_cities[0] != owned_cities[0]:
                raise ValueError(f"lord {lord_id} has a missing or non-owned capital")
            if set(override_cities) != set(owned_cities):
                raise ValueError(f"lord {lord_id} cities must retain deterministic ownership")
            nation["cities"] = [owned_cities[0], *sorted(city for city in override_cities if city != owned_cities[0])]
        else:
            nation["cities"] = owned_cities
        for key, value in override.items():
            if key != "cities":
                nation[key] = value
        for city in nation["cities"]:
            if city in all_cities:
                raise ValueError(f"city overlap: {city}")
            all_cities[city] = lord_id
        if nation["cities"][0] != owned_cities[0]:
            raise ValueError(f"lord {lord_id} has a missing or non-owned capital")
        if any(all_cities.get(city) != lord_id for city in nation["cities"]):
            raise ValueError(f"lord {lord_id} has a non-owned city")
        nations.append(nation)
    defaults = _load_defaults()
    return {
        "code": data["code"],
        "number": data["number"],
        "title": data["title"],
        "year_month": data["year_month"],
        "startYear": data["startYear"],
        "map": data["map"],
        "life": data["life"],
        "fiction": data["fiction"],
        "const": data["const"],
        "nations": nations,
        "diplomacy": _validate_diplomacy(data, valid_lord_ids, defaults),
        "city_relocations": city_relocations,
    }
