import json
import re


AFFILIATED_STATUSES = frozenset({"君主", "太守", "都督", "一般"})
NEUTRAL_STATUS = "在野"
NON_EMITTED_STATUSES = frozenset({"未発見", "未登場", "死亡"})
KNOWN_STATUSES = AFFILIATED_STATUSES | NON_EMITTED_STATUSES | {NEUTRAL_STATUS}
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
RANK_BY_STATUS = {"君主": 12, "太守": 4, "都督": 0, "一般": 0, "在野": 0}
ROOT_KEYS = (
    "title",
    "startYear",
    "life",
    "fiction",
    "map",
    "cityIdentityVersion",
    "seedContract",
    "const",
    "stored_icons",
    "nation",
    "general",
    "general_ex",
    "general_neutral",
    "diplomacy",
)
NORMALIZED_MANIFEST_KEYS = frozenset({
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
    "city_relocations",
})
NATION_FIELDS = frozenset({"lord_id", "name", "color", "gold", "rice", "tech", "ideology", "scale", "cities"})
RELOCATION_FIELDS = frozenset({"lord_id", "source_city", "assigned_city", "reason"})
HANGUL = re.compile(r"[가-힣]")
KOREAN_TEXT = re.compile(r"[가-힣0-9 .,'()\-:·ㆍ]+\Z")
OFFICER_ID_MIN = 10001
OFFICER_ID_MAX = 11000
ADULT_AGE = 14


def _is_int(value: object) -> bool:
    return isinstance(value, int) and not isinstance(value, bool)


def _required_int(value: object, label: str) -> int:
    if not _is_int(value):
        raise ValueError(f"{label} must be an integer")
    return value


def _stable_officer_id(value: object, label: str) -> int:
    officer_id = _required_int(value, label)
    if not OFFICER_ID_MIN <= officer_id <= OFFICER_ID_MAX:
        raise ValueError(
            f"{label} must be in stable officer id band {OFFICER_ID_MIN}..{OFFICER_ID_MAX}"
        )
    return officer_id


def _required_text(value: object, label: str, *, korean: bool = False) -> str:
    if not isinstance(value, str) or not value.strip():
        raise ValueError(f"{label} must be a non-blank string")
    text = value.strip()
    if korean and (HANGUL.search(text) is None or KOREAN_TEXT.fullmatch(text) is None):
        raise ValueError(f"{label} must be a Korean name or city, not a source label")
    return text


def _active_general_count(rows: object, start_year: int) -> int:
    if not isinstance(rows, list):
        raise ValueError("scenario general must be a list")
    active = 0
    for index, row in enumerate(rows):
        if not isinstance(row, list) or len(row) < 11:
            raise ValueError(f"scenario general {index} lifecycle tuple is invalid")
        birth = _required_int(row[9], f"scenario general {index} birth")
        death = _required_int(row[10], f"scenario general {index} death")
        if death > start_year and birth + ADULT_AGE <= start_year:
            active += 1
    return active


def _string_mapping(value: object, label: str) -> dict[str, str]:
    if not isinstance(value, dict):
        raise ValueError(f"{label} must be a string mapping")
    result: dict[str, str] = {}
    for source, target in value.items():
        source_text = _required_text(source, f"{label} source")
        target_text = _required_text(target, f"{label} target", korean=True)
        result[source_text] = target_text
    return result


def _name_mapping(value: object) -> dict[int, str]:
    if not isinstance(value, dict):
        raise ValueError("name_map must be an id-to-Korean-name mapping")
    result: dict[int, str] = {}
    for officer_id, name in value.items():
        normalized_id = _stable_officer_id(officer_id, "name_map stable id")
        if normalized_id in result:
            raise ValueError(f"name_map contains a duplicate stable id: {normalized_id}")
        result[normalized_id] = _required_text(name, f"name_map Korean name for {normalized_id}", korean=True)
    return result


def _normalize_nations(value: object) -> tuple[list[list[object]], dict[int, int], set[str], list[dict]]:
    if not isinstance(value, list) or not value:
        raise ValueError("normalized manifest nations must be a non-empty list")
    parsed: list[dict] = []
    lord_ids: set[int] = set()
    all_cities: set[str] = set()
    for nation in value:
        if not isinstance(nation, dict) or set(nation) != NATION_FIELDS:
            raise ValueError("normalized manifest nation fields are invalid or leak source labels")
        lord_id = _stable_officer_id(nation["lord_id"], "nation lord_id")
        if lord_id in lord_ids:
            raise ValueError("normalized manifest nations require unique stable lord_id values")
        lord_ids.add(lord_id)
        name = _required_text(nation["name"], f"nation {lord_id} name", korean=True)
        color = _required_text(nation["color"], f"nation {lord_id} color")
        gold = _required_int(nation["gold"], f"nation {lord_id} gold")
        rice = _required_int(nation["rice"], f"nation {lord_id} rice")
        tech = _required_int(nation["tech"], f"nation {lord_id} tech")
        ideology = nation["ideology"]
        if not isinstance(ideology, str) or ideology not in NATION_TYPE_ALIASES:
            raise ValueError(f"nation {lord_id} ideology is not an approved runtime alias")
        scale = _required_int(nation["scale"], f"nation {lord_id} scale")
        cities = nation["cities"]
        if not isinstance(cities, list) or not cities:
            raise ValueError(f"nation {lord_id} requires at least one normalized city")
        normalized_cities = [_required_text(city, f"nation {lord_id} city", korean=True) for city in cities]
        if len(normalized_cities) != len(set(normalized_cities)):
            raise ValueError(f"nation {lord_id} has duplicate cities")
        overlap = all_cities & set(normalized_cities)
        if overlap:
            raise ValueError(f"normalized manifest cities overlap: {sorted(overlap)}")
        all_cities.update(normalized_cities)
        parsed.append({
            "lord_id": lord_id,
            "tuple": [name, color, gold, rice, "", tech, ideology, scale, normalized_cities],
            "cities": normalized_cities,
        })
    parsed.sort(key=lambda nation: nation["lord_id"])
    nation_ids = {nation["lord_id"]: index for index, nation in enumerate(parsed, start=1)}
    return [nation["tuple"] for nation in parsed], nation_ids, all_cities, parsed


def _diplomacy_terms(defaults: object) -> dict[int, int]:
    if not isinstance(defaults, dict) or not isinstance(defaults.get("diplomacy"), dict):
        raise ValueError("defaults must contain diplomacy settings")
    diplomacy = defaults["diplomacy"]
    required = {"neutral_state", "neutral_term", "war_state", "war_term"}
    if set(diplomacy) != required:
        raise ValueError("defaults diplomacy settings are invalid")
    neutral_state = _required_int(diplomacy["neutral_state"], "defaults neutral_state")
    neutral_term = _required_int(diplomacy["neutral_term"], "defaults neutral_term")
    war_state = _required_int(diplomacy["war_state"], "defaults war_state")
    war_term = _required_int(diplomacy["war_term"], "defaults war_term")
    if neutral_state == war_state:
        raise ValueError("defaults diplomacy states must be distinct")
    return {neutral_state: neutral_term, war_state: war_term}


def _normalize_diplomacy(value: object, nation_ids: dict[int, int], defaults: object) -> list[list[int]]:
    if not isinstance(value, list):
        raise ValueError("normalized manifest diplomacy must be a list")
    terms_by_state = _diplomacy_terms(defaults)
    pairs: dict[tuple[int, int], tuple[int, int]] = {}
    for row in value:
        if not isinstance(row, list) or len(row) != 4:
            raise ValueError("normalized manifest diplomacy must use four-element tuples")
        source_lord, target_lord, state, term = (_required_int(item, "diplomacy value") for item in row)
        if source_lord not in nation_ids or target_lord not in nation_ids or source_lord == target_lord:
            raise ValueError("diplomacy references an unknown or identical nation lord")
        if terms_by_state.get(state) != term:
            raise ValueError("diplomacy state or term is not approved by defaults")
        pair = tuple(sorted((source_lord, target_lord)))
        existing = pairs.get(pair)
        if existing is not None and existing != (state, term):
            raise ValueError("opposite diplomacy rows must agree")
        pairs[pair] = state, term
    result: list[list[int]] = []
    for (first_lord, second_lord), (state, term) in sorted(pairs.items()):
        first_nation = nation_ids[first_lord]
        second_nation = nation_ids[second_lord]
        result.extend((
            [first_nation, second_nation, state, term],
            [second_nation, first_nation, state, term],
        ))
    return sorted(result)


def _normalize_relocations(value: object, nation_ids: dict[int, int]) -> list[dict]:
    if not isinstance(value, list):
        raise ValueError("normalized manifest city_relocations must be a list")
    parsed: list[dict] = []
    seen_lords: set[int] = set()
    for relocation in value:
        if not isinstance(relocation, dict) or set(relocation) != RELOCATION_FIELDS:
            raise ValueError("city relocation fields are invalid or leak source labels")
        lord_id = _stable_officer_id(relocation["lord_id"], "city relocation lord_id")
        if lord_id not in nation_ids or lord_id in seen_lords:
            raise ValueError("city relocation references an unknown or duplicate nation lord")
        seen_lords.add(lord_id)
        source_city = _required_text(relocation["source_city"], "city relocation source_city", korean=True)
        assigned_city = _required_text(relocation["assigned_city"], "city relocation assigned_city", korean=True)
        if relocation["reason"] != "lord_city_collision":
            raise ValueError("city relocation reason is invalid")
        parsed.append({
            "lord_id": lord_id,
            "source_city": source_city,
            "assigned_city": assigned_city,
            "reason": "lord_city_collision",
        })
    return sorted(parsed, key=lambda item: (item["lord_id"], item["source_city"], item["assigned_city"]))


def _normalize_manifest(manifest: object, defaults: object) -> tuple[dict, list[list[object]], dict[int, int], set[str], list[dict], list[list[int]]]:
    if not isinstance(manifest, dict) or set(manifest) != NORMALIZED_MANIFEST_KEYS:
        raise ValueError("manifest must be the normalized manifest contract without source labels")
    title = _required_text(manifest["title"], "manifest title", korean=True)
    year_month = _required_text(manifest["year_month"], "manifest year_month")
    start_year = _required_int(manifest["startYear"], "manifest startYear")
    life = _required_int(manifest["life"], "manifest life")
    fiction = _required_int(manifest["fiction"], "manifest fiction")
    if start_year <= 0 or manifest["map"] != "han-world-v3" or life != 1 or fiction != 0:
        raise ValueError("manifest fixed scenario settings are invalid")
    const = manifest["const"]
    if not isinstance(const, dict) or set(const) != {"defaultMaxGeneral"}:
        raise ValueError("manifest const is invalid")
    if _required_int(const["defaultMaxGeneral"], "manifest defaultMaxGeneral") != 600:
        raise ValueError("manifest const is invalid")
    nation_tuples, nation_ids, owned_cities, nations = _normalize_nations(manifest["nations"])
    relocations = _normalize_relocations(manifest["city_relocations"], nation_ids)
    diplomacy = _normalize_diplomacy(manifest["diplomacy"], nation_ids, defaults)
    return (
        {"title": title, "year_month": year_month, "startYear": start_year},
        nation_tuples,
        nation_ids,
        owned_cities,
        relocations,
        diplomacy,
    )


def _matching_rows(record: dict, year_month: str, officer_id: int) -> list[dict]:
    scenarios = record.get("scenarios")
    if not isinstance(scenarios, list):
        raise ValueError(f"refined officer {officer_id} scenarios must be a list")
    matching = [row for row in scenarios if isinstance(row, dict) and row.get("year_month") == year_month]
    if len(matching) > 1:
        raise ValueError(f"refined officer {officer_id} has duplicate {year_month} rows")
    return matching


def _resolve_city(
    location: object,
    city_map: dict[str, str],
    remap: dict[str, str],
    known_cities: set[str],
    officer_id: int,
) -> str:
    source_city = _required_text(location, f"refined officer {officer_id} location")
    if source_city in city_map:
        city = city_map[source_city]
    elif source_city in remap:
        city = remap[source_city]
    elif source_city in known_cities:
        city = source_city
    else:
        raise ValueError(f"refined officer {officer_id} location is unresolved: {source_city}")
    return _required_text(city, f"resolved city for officer {officer_id}", korean=True)


def _general_tuple(record: dict, row: dict, name: str, nation_id: int, city: int, officer_id: int) -> list[object]:
    status = row["status"]
    rank = RANK_BY_STATUS[status]
    if "v1_rank" in row and row["v1_rank"] != rank:
        raise ValueError(f"refined officer {officer_id} has an unsupported rank mapping")
    return [
        0,
        name,
        officer_id,
        nation_id,
        city,
        _required_int(record.get("leadership"), f"refined officer {officer_id} leadership"),
        _required_int(record.get("strength"), f"refined officer {officer_id} strength"),
        _required_int(record.get("intelligence"), f"refined officer {officer_id} intelligence"),
        rank,
        _required_int(record.get("birth"), f"refined officer {officer_id} birth"),
        _required_int(record.get("death"), f"refined officer {officer_id} death"),
        None,
        None,
        None,
        _required_int(record.get("politics"), f"refined officer {officer_id} politics"),
        _required_int(record.get("charm"), f"refined officer {officer_id} charm"),
    ]


def build(
    refined: list[dict],
    manifest: dict,
    city_map: dict[str, str],
    remap: dict[str, str],
    name_map: dict[int, str],
    defaults: dict,
    *,
    runtime_city_ids: dict[str, int],
) -> tuple[dict, dict]:
    if not isinstance(refined, list):
        raise ValueError("refined input must be a list")
    normalized_city_map = _string_mapping(city_map, "city_map")
    normalized_remap = _string_mapping(remap, "remap")
    if set(normalized_city_map) & set(normalized_remap):
        raise ValueError("city_map and remap cannot share source locations")
    names_by_id = _name_mapping(name_map)
    header, nation_tuples, nation_ids, owned_cities, relocations, diplomacy = _normalize_manifest(manifest, defaults)
    known_cities = owned_cities | set(normalized_city_map.values()) | set(normalized_remap.values())
    if not isinstance(runtime_city_ids, dict) or not runtime_city_ids:
        raise ValueError("runtime_city_ids must be a non-empty reviewed V3 city mapping")
    normalized_runtime_city_ids: dict[str, int] = {}
    for city_name, city_id in runtime_city_ids.items():
        name = _required_text(city_name, "runtime city name", korean=True)
        normalized_runtime_city_ids[name] = _required_int(city_id, f"runtime city id for {name}")
        if normalized_runtime_city_ids[name] <= 0:
            raise ValueError(f"runtime city id for {name} must be positive")
    if len(set(normalized_runtime_city_ids.values())) != len(normalized_runtime_city_ids):
        raise ValueError("runtime_city_ids must map names to unique V3 city ids")
    missing_runtime_cities = sorted(known_cities - set(normalized_runtime_city_ids))
    if missing_runtime_cities:
        raise ValueError(f"V3 runtime city mapping is missing {missing_runtime_cities}")
    for nation_tuple in nation_tuples:
        nation_tuple[8] = [normalized_runtime_city_ids[city] for city in nation_tuple[8]]

    matching: list[tuple[int, dict, dict]] = []
    seen_ids: set[int] = set()
    for record in refined:
        if not isinstance(record, dict):
            raise ValueError("refined input must contain objects")
        officer_id = _stable_officer_id(record.get("id"), "refined officer id")
        if officer_id in seen_ids:
            raise ValueError("refined input requires unique stable ids")
        seen_ids.add(officer_id)
        rows = _matching_rows(record, header["year_month"], officer_id)
        if rows:
            matching.append((officer_id, record, rows[0]))

    if set(names_by_id) != seen_ids:
        missing_ids = sorted(seen_ids - set(names_by_id))
        extra_ids = sorted(set(names_by_id) - seen_ids)
        raise ValueError(
            "name_map stable-id identity set must exactly match refined stable ids: "
            f"missing={missing_ids}, extra={extra_ids}"
        )

    by_id = {officer_id: (record, row) for officer_id, record, row in matching}
    faction_to_lord: dict[str, int] = {}
    for lord_id in sorted(nation_ids):
        ruler = by_id.get(lord_id)
        if ruler is None:
            raise ValueError(f"manifest lord {lord_id} has no matching scenario ruler")
        record, row = ruler
        if row.get("status") != "君主":
            raise ValueError(f"manifest lord {lord_id} is not a ruler")
        faction = _required_text(row.get("faction"), f"ruler {lord_id} faction")
        name_kanji = _required_text(record.get("name_kanji"), f"ruler {lord_id} source identity")
        if faction != name_kanji or faction in faction_to_lord:
            raise ValueError("each nation must have exactly one unique ruler faction")
        faction_to_lord[faction] = lord_id

    general_rows: list[tuple[int, list[object], dict]] = []
    neutral_count = 0
    for officer_id, record, row in matching:
        status = row.get("status")
        if not isinstance(status, str) or not status.strip() or status not in KNOWN_STATUSES:
            raise ValueError(f"refined officer {officer_id} has an unknown or missing status")
        if status in NON_EMITTED_STATUSES:
            continue
        record_name = _required_text(record.get("name_korean"), f"refined officer {officer_id} Korean name", korean=True)
        mapped_name = names_by_id.get(officer_id)
        if mapped_name is None or mapped_name != record_name:
            raise ValueError(f"refined officer {officer_id} requires an exact Korean name mapping")
        city = _resolve_city(row.get("location"), normalized_city_map, normalized_remap, known_cities, officer_id)
        if status == NEUTRAL_STATUS:
            tuple_row = _general_tuple(
                record, row, mapped_name, 0, normalized_runtime_city_ids[city], officer_id
            )
            general_rows.append((officer_id, tuple_row, {
                "id": officer_id,
                "kind": "unaffiliated",
                "nation_id": 0,
                "city": normalized_runtime_city_ids[city],
                "officer_level": tuple_row[8],
                "picture_id": officer_id,
            }))
            neutral_count += 1
            continue
        faction = _required_text(row.get("faction"), f"refined officer {officer_id} faction")
        lord_id = faction_to_lord.get(faction)
        if lord_id is None:
            raise ValueError(f"refined officer {officer_id} has an unknown active faction")
        if status == "君主" and officer_id != lord_id:
            raise ValueError("each nation must have exactly one manifest ruler")
        tuple_row = _general_tuple(
            record, row, mapped_name, nation_ids[lord_id], normalized_runtime_city_ids[city], officer_id
        )
        general_rows.append((officer_id, tuple_row, {
            "id": officer_id,
            "kind": "affiliated",
            "nation_id": nation_ids[lord_id],
            "city": normalized_runtime_city_ids[city],
            "officer_level": tuple_row[8],
            "picture_id": officer_id,
        }))

    general_rows.sort(key=lambda row: row[0])
    general_tuples = [tuple_row for _, tuple_row, _ in general_rows]
    active_general_count = _active_general_count(general_tuples, header["startYear"])
    scenario = {
        "title": header["title"],
        "startYear": header["startYear"],
        "life": 1,
        "fiction": 0,
        "map": {"mapName": "han-world-v3", "unitSet": "han"},
        "cityIdentityVersion": "han-world-v3",
        "seedContract": {
            "activeGenerals": {
                "base": active_general_count,
                "extended": active_general_count,
            },
        },
        "const": {"defaultMaxGeneral": 600},
        "stored_icons": {".": {
            str(officer_id): f"{officer_id}.png"
            for officer_id, _, _ in general_rows
        }},
        "nation": nation_tuples,
        "general": general_tuples,
        "general_ex": [],
        "general_neutral": [],
        "diplomacy": diplomacy,
    }
    validate_scenario_shape(scenario)
    report = {
        "nation_ids": [
            {"lord_id": lord_id, "nation_id": nation_id}
            for lord_id, nation_id in sorted(nation_ids.items())
        ],
        "affiliated_count": len(general_rows) - neutral_count,
        "neutral_count": neutral_count,
        "unresolved_locations": [],
        "korean_name_fallbacks": [],
        "city_relocations": relocations,
        "officers": [mapping for _, _, mapping in general_rows],
    }
    return scenario, report


def validate_scenario_shape(scenario: dict) -> None:
    if not isinstance(scenario, dict) or tuple(scenario) != ROOT_KEYS:
        raise ValueError("scenario root keys or ordering are invalid")
    _required_text(scenario["title"], "scenario title", korean=True)
    _required_int(scenario["startYear"], "scenario startYear")
    if _required_int(scenario["life"], "scenario life") != 1 or _required_int(scenario["fiction"], "scenario fiction") != 0:
        raise ValueError("scenario life or fiction is invalid")
    if (
        not isinstance(scenario["map"], dict)
        or tuple(scenario["map"]) != ("mapName", "unitSet")
        or scenario["map"] != {"mapName": "han-world-v3", "unitSet": "han"}
        or scenario["cityIdentityVersion"] != "han-world-v3"
    ):
        raise ValueError("scenario map is invalid")
    if not isinstance(scenario["nation"], list) or not isinstance(scenario["general"], list):
        raise ValueError("scenario nation and general must be lists")
    seed_contract = scenario["seedContract"]
    if (
        not isinstance(seed_contract, dict)
        or tuple(seed_contract) != ("activeGenerals",)
        or not isinstance(seed_contract["activeGenerals"], dict)
        or tuple(seed_contract["activeGenerals"]) != ("base", "extended")
    ):
        raise ValueError("scenario active general seed contract is invalid")
    contract_base = _required_int(
        seed_contract["activeGenerals"]["base"],
        "scenario active general seed contract base",
    )
    contract_extended = _required_int(
        seed_contract["activeGenerals"]["extended"],
        "scenario active general seed contract extended",
    )
    if not isinstance(scenario["const"], dict) or tuple(scenario["const"]) != ("defaultMaxGeneral",):
        raise ValueError("scenario const is invalid")
    if _required_int(scenario["const"]["defaultMaxGeneral"], "scenario defaultMaxGeneral") != 600:
        raise ValueError("scenario map or const is invalid")
    if scenario["general_ex"] != [] or scenario["general_neutral"] != [] or not isinstance(scenario["diplomacy"], list):
        raise ValueError("scenario general roster sections are invalid")

    stored_icons = scenario["stored_icons"]
    if not isinstance(stored_icons, dict) or tuple(stored_icons) != (".",) or not isinstance(stored_icons["."], dict):
        raise ValueError("scenario stored_icons is invalid")
    icon_map = stored_icons["."]

    city_owner: dict[int, int] = {}
    for nation_id, tuple_row in enumerate(scenario["nation"], start=1):
        if not isinstance(tuple_row, list) or len(tuple_row) != 9:
            raise ValueError("scenario nation tuple must contain exactly 9 values")
        _required_text(tuple_row[0], f"nation {nation_id} name", korean=True)
        _required_text(tuple_row[1], f"nation {nation_id} color")
        for index, label in ((2, "gold"), (3, "rice"), (5, "tech"), (7, "scale")):
            _required_int(tuple_row[index], f"nation {nation_id} {label}")
        if not isinstance(tuple_row[4], str):
            raise ValueError("scenario nation desc must be a string")
        _required_text(tuple_row[6], f"nation {nation_id} ideology", korean=True)
        if not isinstance(tuple_row[8], list) or not tuple_row[8]:
            raise ValueError("scenario nation cities must be a non-empty list")
        for city in tuple_row[8]:
            city_id = _required_int(city, f"nation {nation_id} city")
            if city_id <= 0 or city_id in city_owner:
                raise ValueError("scenario nation cities must not overlap")
            city_owner[city_id] = nation_id

    def validate_general(tuple_row: object) -> int:
        if not isinstance(tuple_row, list) or len(tuple_row) != 16:
            raise ValueError("scenario general tuple must contain exactly 16 values")
        _required_int(tuple_row[0], "general affinity")
        _required_text(tuple_row[1], "general Korean name", korean=True)
        picture_id = _stable_officer_id(tuple_row[2], "general picture")
        nation_id = _required_int(tuple_row[3], "general nation id")
        if nation_id < 0 or nation_id > len(scenario["nation"]):
            raise ValueError("general nation id is outside the scenario nation range")
        if _required_int(tuple_row[4], "general located city") <= 0:
            raise ValueError("general located city must be a positive V3 city id")
        for index, label in ((5, "leadership"), (6, "strength"), (7, "intelligence"), (8, "officer level"), (9, "birth"), (10, "death"), (14, "politics"), (15, "charm")):
            _required_int(tuple_row[index], f"general {label}")
        if tuple_row[8] not in {0, 4, 12}:
            raise ValueError("general officer level is outside the approved mapping")
        for index, label in ((11, "ego"), (12, "special"), (13, "text")):
            if tuple_row[index] is not None and not isinstance(tuple_row[index], str):
                raise ValueError(f"general {label} must be null or string")
        return picture_id

    picture_ids = [validate_general(tuple_row) for tuple_row in scenario["general"]]
    if picture_ids != sorted(picture_ids) or len(picture_ids) != len(set(picture_ids)):
        raise ValueError("scenario general rows must have stable, ascending picture ids")
    expected_icon_keys = [str(picture_id) for picture_id in picture_ids]
    active_general_count = _active_general_count(scenario["general"], scenario["startYear"])
    if contract_base != active_general_count or contract_extended != active_general_count:
        raise ValueError("scenario active general seed contract is invalid")
    if list(icon_map) != expected_icon_keys or any(icon_map[key] != f"{key}.png" for key in expected_icon_keys):
        raise ValueError("scenario stored_icons must map each stable id to its serving copy")
    diplomacy_rows: dict[tuple[int, int], tuple[int, int]] = {}
    for tuple_row in scenario["diplomacy"]:
        if not isinstance(tuple_row, list) or len(tuple_row) != 4:
            raise ValueError("scenario diplomacy tuple must contain exactly 4 values")
        source, target, state, term = (_required_int(value, "diplomacy value") for value in tuple_row)
        if source not in range(1, len(scenario["nation"]) + 1) or target not in range(1, len(scenario["nation"]) + 1) or source == target:
            raise ValueError("scenario diplomacy nation ids are invalid")
        if (source, target) in diplomacy_rows:
            raise ValueError("scenario diplomacy contains duplicate directed pairs")
        diplomacy_rows[source, target] = state, term
    for (source, target), state_term in diplomacy_rows.items():
        if diplomacy_rows.get((target, source)) != state_term:
            raise ValueError("scenario diplomacy rows must be bilateral")


def dump_scenario(scenario: dict) -> bytes:
    validate_scenario_shape(scenario)
    return (json.dumps(scenario, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
