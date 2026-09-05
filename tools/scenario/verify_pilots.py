"""Build V3 pilots only after their source-city aliases have physical evidence.

The reviewed alias document must declare ``worldVersion: han-world-v3`` and a
``bindings`` list of ``alias``, ``physicalPlaceRef``, ``reviewState: APPROVED``,
and non-empty ``sourceRefs``. Display-name uniqueness and commandery seats do
not establish an alias: e.g. pilot 長沙 is not 潁川郡 長社縣. The default review
document is intentionally absent until that evidence has been adjudicated.
Generation therefore remains blocked even when private refined inputs exist.
"""

import argparse
import csv
import hashlib
import json
from collections import Counter
from pathlib import Path

from build_scenario import (
    AFFILIATED_STATUSES,
    NEUTRAL_STATUS,
    NON_EMITTED_STATUSES,
    RANK_BY_STATUS,
    build,
    dump_scenario,
    validate_scenario_shape,
)
from manifest import load_manifest, validate_manifest
from refine_officers import load_mapping


SCENARIO_DIRECTORY = Path(__file__).resolve().parent
REPOSITORY_ROOT = SCENARIO_DIRECTORY.parents[1]
REFINED_PATH = REPOSITORY_ROOT / "data/scenarios/refined/rtk14-officers.json"
HAN_V3_PATH = REPOSITORY_ROOT / "infra/src/main/resources/map/han-world-v3.json"
PILOT_CITY_BINDINGS_PATH = REPOSITORY_ROOT / "data/curated/han/pilot-city-bindings-v1.json"
NAME_MAP_PATH = SCENARIO_DIRECTORY / "officer-name-map.tsv"
CITY_MAP_PATH = SCENARIO_DIRECTORY / "city_map.json"
REMAP_PATH = SCENARIO_DIRECTORY / "location-remap.yaml"
DEFAULTS_PATH = SCENARIO_DIRECTORY / "defaults.json"
MANIFEST_DIRECTORY = SCENARIO_DIRECTORY / "manifests"
OUTPUT_DIRECTORY = REPOSITORY_ROOT / "data/scenarios"
REPORT_DIRECTORY = OUTPUT_DIRECTORY / "reports"
RANK_SEMANTIC_ORDER = ("君主", "太守", "都督", "一般", "在野")
IMPORTER_ADULT_AGE = 14
IMPORTER_RULER_GAP_REASON = "pending v2 PHP postBuild promotion parity"


PILOT_EXPECTATIONS = {
    "scenario_3190": {
        "year_month": "190.1",
        "affiliated_count": 249,
        "neutral_count": 31,
        "importer_eligible_total": 264,
        "importer_lifecycle": {
            "roster_total": 280,
            "active_at_start": 264,
            "deferred_underage": 1,
            "dead_at_start": 15,
        },
        "seed_readiness": {
            "importer_ruler_gap_nation_ids": [],
            "seed_ready": True,
            "reason": None,
        },
        "status_counts": {
            "君主": 21,
            "太守": 13,
            "都督": 1,
            "一般": 214,
            "在野": 31,
            "未発見": 104,
            "未登場": 595,
            "死亡": 21,
        },
        "city_relocations": [{
            "lord_id": 10056,
            "source_city": "북평",
            "assigned_city": "역경",
            "reason": "lord_city_collision",
        }],
        "representatives": (
            (10071, "유비", "君主", "劉備", "平原", "평원", 3, 12),
            (10146, "여포", "太守", "董卓", "虎牢関", "사수", 16, 4),
            (10174, "주유", "一般", "孫堅", "長沙", "장사", 11, 0),
            (10246, "손권", "未登場", None, "呉", "오", None, None),
            (10405, "조조", "君主", "曹操", "陳留", "진류", 15, 12),
            (10732, "원소", "君主", "袁紹", "南皮", "남피", 17, 12),
        ),
    },
    "scenario_3200": {
        "year_month": "200.1",
        "affiliated_count": 304,
        "neutral_count": 32,
        "importer_eligible_total": 316,
        "importer_lifecycle": {
            "roster_total": 336,
            "active_at_start": 316,
            "deferred_underage": 2,
            "dead_at_start": 18,
        },
        "seed_readiness": {
            "importer_ruler_gap_nation_ids": [6],
            "seed_ready": False,
            "reason": IMPORTER_RULER_GAP_REASON,
        },
        "status_counts": {
            "君主": 11,
            "太守": 28,
            "都督": 4,
            "一般": 261,
            "在野": 32,
            "未発見": 83,
            "未登場": 422,
            "死亡": 159,
        },
        "city_relocations": [],
        "representatives": (
            (10071, "유비", "君主", "劉備", "小沛", "패", 2, 12),
            (10146, "여포", "死亡", None, None, None, None, None),
            (10174, "주유", "一般", "孫策", "建業", "건업", 6, 0),
            (10246, "손권", "一般", "孫策", "柴桑", "시상", 6, 0),
            (10405, "조조", "君主", "曹操", "許昌", "허창", 9, 12),
            (10732, "원소", "君主", "袁紹", "鄴", "업", 10, 12),
        ),
    },
    "scenario_3219": {
        "year_month": "219.7",
        "affiliated_count": 370,
        "neutral_count": 13,
        "importer_eligible_total": 366,
        "importer_lifecycle": {
            "roster_total": 383,
            "active_at_start": 366,
            "deferred_underage": 0,
            "dead_at_start": 17,
        },
        "seed_readiness": {
            "importer_ruler_gap_nation_ids": [],
            "seed_ready": True,
            "reason": None,
        },
        "status_counts": {
            "君主": 6,
            "太守": 38,
            "都督": 8,
            "一般": 318,
            "在野": 13,
            "未発見": 31,
            "未登場": 203,
            "死亡": 383,
        },
        "city_relocations": [],
        "representatives": (
            (10071, "유비", "君主", "劉備", "成都", "성도", 2, 12),
            (10146, "여포", "死亡", None, None, None, None, None),
            (10174, "주유", "死亡", None, None, None, None, None),
            (10246, "손권", "君主", "孫権", "建業", "건업", 5, 12),
            (10405, "조조", "君主", "曹操", "許昌", "허창", 6, 12),
            (10732, "원소", "死亡", None, None, None, None, None),
        ),
    },
}


def _is_int(value: object) -> bool:
    return isinstance(value, int) and not isinstance(value, bool)


def _read_json(path: Path) -> object:
    return json.loads(path.read_text(encoding="utf-8"))


def _load_name_map(path: Path) -> dict[int, str]:
    with path.open(encoding="utf-8", newline="") as source:
        rows = csv.DictReader(source, delimiter="\t")
        if rows.fieldnames != ["id", "name_korean"]:
            raise ValueError("officer name map headers are invalid")
        result: dict[int, str] = {}
        for row in rows:
            officer_id = int(row["id"])
            name = row["name_korean"]
            if officer_id in result or not name:
                raise ValueError("officer name map is invalid")
            result[officer_id] = name
    return result


def _matching_rows(refined: list[dict], year_month: str, errors: list[str]) -> dict[int, tuple[dict, dict]]:
    result: dict[int, tuple[dict, dict]] = {}
    for record in refined:
        if not isinstance(record, dict) or not _is_int(record.get("id")):
            errors.append("refined input: every record requires an integer stable id")
            continue
        officer_id = record["id"]
        if officer_id in result:
            errors.append(f"refined input: duplicate stable id {officer_id}")
            continue
        rows = record.get("scenarios")
        if not isinstance(rows, list):
            errors.append(f"refined input: officer {officer_id} scenarios must be a list")
            continue
        matching = [row for row in rows if isinstance(row, dict) and row.get("year_month") == year_month]
        if len(matching) != 1:
            errors.append(f"refined input: officer {officer_id} needs exactly one {year_month} row")
            continue
        result[officer_id] = (record, matching[0])
    return result


def _is_full_refined(refined: list[dict]) -> bool:
    ids = [record.get("id") for record in refined if isinstance(record, dict)]
    return len(refined) == 1000 and set(ids) == set(range(10001, 11001))


def _reported_empty(report: dict, field: str, label: str, errors: list[str]) -> None:
    value = report.get(field)
    if value != []:
        errors.append(f"{label}: expected [], got {value!r}")


def _general_rows(scenario: dict) -> dict[int, list[object]]:
    return {row[2]: row for row in scenario["general"]}


def _is_importer_eligible(row: list[object], start_year: int) -> bool:
    return row[10] > start_year and row[9] + IMPORTER_ADULT_AGE <= start_year


def _importer_lifecycle(general_rows: list[list[object]], start_year: int) -> dict[str, int]:
    active_at_start = sum(_is_importer_eligible(row, start_year) for row in general_rows)
    dead_at_start = sum(row[10] <= start_year for row in general_rows)
    deferred_underage = sum(
        row[10] > start_year and row[9] + IMPORTER_ADULT_AGE > start_year
        for row in general_rows
    )
    return {
        "roster_total": len(general_rows),
        "active_at_start": active_at_start,
        "deferred_underage": deferred_underage,
        "dead_at_start": dead_at_start,
    }


def _seed_readiness(scenario: dict) -> dict[str, object]:
    start_year = scenario["startYear"]
    missing_nation_ids = [
        nation_id
        for nation_id in range(1, len(scenario["nation"]) + 1)
        if not any(
            row[3] == nation_id and row[8] == 12 and _is_importer_eligible(row, start_year)
            for row in scenario["general"]
        )
    ]
    return {
        "importer_ruler_gap_nation_ids": missing_nation_ids,
        "seed_ready": not missing_nation_ids,
        "reason": None if not missing_nation_ids else IMPORTER_RULER_GAP_REASON,
    }


def _verify_report_officers(report: dict, general_by_id: dict[int, list[object]], errors: list[str]) -> None:
    officers = report.get("officers")
    if not isinstance(officers, list):
        errors.append("report officers: expected a list")
        return
    by_id: dict[int, dict] = {}
    for officer in officers:
        if not isinstance(officer, dict) or not _is_int(officer.get("id")):
            errors.append("report officers: each row requires an integer id")
            continue
        if officer["id"] in by_id:
            errors.append(f"report officers: duplicate id {officer['id']}")
            continue
        by_id[officer["id"]] = officer
    if set(by_id) != set(general_by_id):
        errors.append("report officers: ids must exactly match scenario general picture ids")
        return
    for officer_id, row in general_by_id.items():
        officer = by_id[officer_id]
        expected_kind = "unaffiliated" if row[3] == 0 else "affiliated"
        expected = {
            "id": officer_id,
            "kind": expected_kind,
            "nation_id": row[3],
            "city": row[4],
            "officer_level": row[8],
            "picture_id": officer_id,
        }
        if officer != expected:
            errors.append(f"report officers: id {officer_id} does not match emitted tuple")


def _verify_runtime_nations(scenario: dict, source_by_id: dict[int, tuple[dict, dict]], errors: list[str]) -> None:
    for nation_id in range(1, len(scenario["nation"]) + 1):
        rulers = [row for row in scenario["general"] if row[3] == nation_id and row[8] == 12]
        if len(rulers) != 1:
            errors.append(f"runtime nation {nation_id}: requires exactly one level-12 ruler")
            continue
        picture_id = rulers[0][2]
        source = source_by_id.get(picture_id)
        if source is None or source[1].get("status") != "君主":
            errors.append(f"runtime nation {nation_id}: level-12 ruler {picture_id} is not a source 君主")


def _verify_representatives(
    expectation: dict,
    source_by_id: dict[int, tuple[dict, dict]],
    general_by_id: dict[int, list[object]],
    runtime_city_ids: dict[str, int],
    errors: list[str],
) -> None:
    for officer_id, name, status, faction, location, city, nation_id, level in expectation["representatives"]:
        source = source_by_id.get(officer_id)
        if source is None:
            errors.append(f"representative {officer_id}: source row is missing")
            continue
        record, source_row = source
        actual_source = (record.get("name_korean"), source_row.get("status"), source_row.get("faction"), source_row.get("location"))
        expected_source = (name, status, faction, location)
        if actual_source != expected_source:
            errors.append(
                f"representative {officer_id}: source expected {expected_source!r}, got {actual_source!r}"
            )
        emitted = general_by_id.get(officer_id)
        if nation_id is None:
            if emitted is not None:
                errors.append(f"representative {officer_id}: excluded status {status} must not be emitted")
            continue
        if emitted is None:
            errors.append(f"representative {officer_id}: expected an emitted general tuple")
            continue
        if emitted[3] != nation_id:
            errors.append(
                f"representative {officer_id}: runtime nation expected {nation_id}, got {emitted[3]!r}"
            )
        expected_city_id = runtime_city_ids.get(city)
        if emitted[4] != expected_city_id:
            errors.append(
                f"representative {officer_id}: emitted city expected {city}/{expected_city_id}, "
                f"got {emitted[4]!r}"
            )
        if emitted[8] != level:
            errors.append(f"representative {officer_id}: level expected {level}, got {emitted[8]!r}")


def _rank_semantics(source_by_id: dict[int, tuple[dict, dict]], general_by_id: dict[int, list[object]]) -> list[dict]:
    source_counts = Counter(row.get("status") for _, row in source_by_id.values())
    emitted_counts = Counter(
        source_by_id[officer_id][1].get("status")
        for officer_id in general_by_id
        if officer_id in source_by_id
    )
    semantics = {
        "君主": {"tuple_level": 12, "runtime_semantics": "ruler"},
        "太守": {
            "tuple_level": 4,
            "runtime_semantics": "taesu_level_4",
            "officer_city_semantics": "not_populated_by_importer",
        },
        "都督": {
            "tuple_level": 0,
            "runtime_semantics": "semantic_downgrade_to_general",
        },
        "一般": {"tuple_level": 0, "runtime_semantics": "general"},
        "在野": {"tuple_level": 0, "runtime_semantics": "unaffiliated_general"},
    }
    return [
        {
            "status": status,
            "source_count": source_counts[status],
            "emitted_count": emitted_counts[status],
            **semantics[status],
        }
        for status in RANK_SEMANTIC_ORDER
    ]


def _mapped_city(location: object, city_map: dict[str, str], remap: dict[str, str], che_cities: set[str]) -> str | None:
    if not isinstance(location, str) or not location:
        return None
    if location in city_map:
        return city_map[location]
    if location in remap:
        return remap[location]
    return location if location in che_cities else None


def _representative_evidence(
    expectation: dict,
    source_by_id: dict[int, tuple[dict, dict]],
    general_by_id: dict[int, list[object]],
    city_map: dict[str, str],
    remap: dict[str, str],
    che_cities: set[str],
    runtime_city_ids: dict[str, int],
) -> list[dict]:
    result: list[dict] = []
    for officer_id, _, _, _, _, expected_city, _, _ in expectation["representatives"]:
        record, source_row = source_by_id[officer_id]
        emitted = general_by_id.get(officer_id)
        mapped_city = _mapped_city(source_row.get("location"), city_map, remap, che_cities)
        if mapped_city != expected_city:
            raise ValueError(f"representative {officer_id} mapped city drifted: {mapped_city!r}")
        result.append({
            "id": officer_id,
            "name": record["name_korean"],
            "source_status": source_row.get("status"),
            "source_faction": source_row.get("faction"),
            "source_location": source_row.get("location"),
            "mapped_city": mapped_city,
            "runtime_city_id": runtime_city_ids[mapped_city] if mapped_city is not None else None,
            "runtime_nation_id": emitted[3] if emitted is not None else None,
            "officer_level": emitted[8] if emitted is not None else None,
            "emitted": emitted is not None,
        })
    return result


def _verify_report_representatives(
    report: dict,
    expectation: dict,
    source_by_id: dict[int, tuple[dict, dict]],
    general_by_id: dict[int, list[object]],
    che_cities: set[str],
    runtime_city_ids: dict[str, int],
    errors: list[str],
) -> None:
    try:
        expected = _representative_evidence(
            expectation,
            source_by_id,
            general_by_id,
            load_mapping(CITY_MAP_PATH),
            load_mapping(REMAP_PATH),
            che_cities,
            runtime_city_ids,
        )
    except (KeyError, OSError, ValueError) as error:
        errors.append(f"report representatives: cannot derive expected evidence: {error}")
        return
    if report.get("representatives") != expected:
        errors.append("report representatives: expected exact source/runtime evidence")


def _city_overlap_count(scenario: dict) -> int:
    cities = [city for nation in scenario["nation"] for city in nation[8]]
    return len(cities) - len(set(cities))


def verify(
    scenario: dict,
    report: dict,
    refined: list[dict],
    manifest: dict,
    che_cities: set[str],
    runtime_city_ids: dict[str, int],
) -> list[str]:
    errors: list[str] = []
    if not isinstance(manifest, dict):
        return ["manifest: expected an object"]
    code = manifest.get("code")
    expectation = PILOT_EXPECTATIONS.get(code)
    if expectation is None:
        return [f"manifest: unsupported pilot code {code!r}"]
    if manifest.get("year_month") != expectation["year_month"]:
        errors.append(f"manifest: expected year_month {expectation['year_month']}, got {manifest.get('year_month')!r}")
    if not isinstance(report, dict):
        return [*errors, "report: expected an object"]
    _reported_empty(report, "unresolved_locations", "unresolved locations", errors)
    _reported_empty(report, "korean_name_fallbacks", "Korean name fallbacks", errors)
    if not isinstance(scenario, dict):
        return [*errors, "scenario schema: expected an object"]
    try:
        validate_scenario_shape(scenario)
    except (TypeError, ValueError) as error:
        return [*errors, f"scenario schema: {error}"]
    if not isinstance(refined, list):
        return [*errors, "refined input: expected a list"]
    if not isinstance(che_cities, set) or not all(isinstance(city, str) for city in che_cities):
        return [*errors, "che cities: expected a set of city names"]
    if (
        not isinstance(runtime_city_ids, dict)
        or set(runtime_city_ids) != che_cities
        or not all(_is_int(city_id) and city_id > 0 for city_id in runtime_city_ids.values())
        or len(set(runtime_city_ids.values())) != len(runtime_city_ids)
    ):
        return [*errors, "runtime city ids: expected an exact unique V3 projection"]
    known_runtime_city_ids = set(runtime_city_ids.values())

    source_by_id = _matching_rows(refined, expectation["year_month"], errors)
    general_by_id = _general_rows(scenario)
    affiliated_count = sum(row[3] != 0 for row in scenario["general"])
    neutral_count = sum(row[3] == 0 for row in scenario["general"])
    importer_lifecycle = _importer_lifecycle(scenario["general"], scenario["startYear"])
    importer_eligible_total = importer_lifecycle["active_at_start"]
    seed_readiness = _seed_readiness(scenario)
    if affiliated_count != expectation["affiliated_count"]:
        errors.append(
            f"affiliated count: expected {expectation['affiliated_count']}, emitted {affiliated_count}"
        )
    for field, actual in (
        ("affiliated_count", affiliated_count),
        ("neutral_count", neutral_count),
        ("importer_eligible_total", importer_eligible_total),
    ):
        if report.get(field) != actual:
            errors.append(f"report {field}: expected {actual}, got {report.get(field)!r}")
    if report.get("importer_lifecycle") != importer_lifecycle:
        errors.append("report importer_lifecycle: expected exact emitted lifecycle evidence")
    if report.get("seed_readiness") != seed_readiness:
        errors.append("report seed_readiness: expected exact importer lifecycle evidence")
    if report.get("city_overlap_count", 0) != _city_overlap_count(scenario):
        errors.append("report city_overlap_count does not match emitted nation cities")

    for nation in scenario["nation"]:
        for city in nation[8]:
            if city not in known_runtime_city_ids:
                errors.append(f"city catalog: nation city id {city!r} is not in V3")
    for officer_id, row in general_by_id.items():
        if row[4] not in known_runtime_city_ids:
            errors.append(f"city catalog: general {officer_id} city id {row[4]!r} is not in V3")
        source = source_by_id.get(officer_id)
        if source is None:
            errors.append(f"cross reference: general {officer_id} has no refined source row")
            continue
        status = source[1].get("status")
        if status in AFFILIATED_STATUSES:
            if row[3] == 0:
                errors.append(f"cross reference: affiliated general {officer_id} has neutral nation id")
        elif status == NEUTRAL_STATUS:
            if row[3] != 0:
                errors.append(f"cross reference: 在野 general {officer_id} must use nation id 0")
        else:
            errors.append(f"cross reference: non-emittable source status {status!r} emitted for {officer_id}")
        expected_level = RANK_BY_STATUS.get(status)
        if expected_level is None or row[8] != expected_level:
            errors.append(f"cross reference: general {officer_id} level does not match source status {status!r}")
    source_emitted_ids = {
        officer_id
        for officer_id, (_, row) in source_by_id.items()
        if row.get("status") in AFFILIATED_STATUSES or row.get("status") == NEUTRAL_STATUS
    }
    if source_emitted_ids != set(general_by_id):
        errors.append("cross reference: emitted stable ids do not exactly match eligible refined rows")

    _verify_report_officers(report, general_by_id, errors)
    _verify_runtime_nations(scenario, source_by_id, errors)
    _verify_representatives(expectation, source_by_id, general_by_id, runtime_city_ids, errors)
    _verify_report_representatives(
        report, expectation, source_by_id, general_by_id, che_cities, runtime_city_ids, errors
    )

    if _is_full_refined(refined):
        source_counts = dict(Counter(row.get("status") for _, row in source_by_id.values()))
        if source_counts != expectation["status_counts"]:
            errors.append(f"source status counts: expected {expectation['status_counts']!r}, got {source_counts!r}")
        for field in ("neutral_count", "importer_eligible_total"):
            if report.get(field) != expectation[field]:
                errors.append(f"pilot {field}: expected {expectation[field]}, got {report.get(field)!r}")
        if importer_lifecycle != expectation["importer_lifecycle"]:
            errors.append("pilot importer_lifecycle: expected exact lifecycle partition")
        if seed_readiness != expectation["seed_readiness"]:
            errors.append("pilot seed_readiness: expected exact importer ruler-gap quarantine")
        if report.get("city_relocations") != expectation["city_relocations"]:
            errors.append("city relocations: report does not preserve required pilot provenance")
        expected_semantics = _rank_semantics(source_by_id, general_by_id)
        if report.get("rank_semantics") != expected_semantics:
            errors.append("rank semantics: report does not preserve emitted level evidence")
        if report.get("source_status_counts") != expectation["status_counts"]:
            errors.append("source status counts: report does not match the pilot expectation")
        excluded_counts = {status: expectation["status_counts"][status] for status in sorted(NON_EMITTED_STATUSES)}
        if report.get("excluded_status_counts") != excluded_counts:
            errors.append("excluded status counts: report does not separate non-emitted records")
        if report.get("scenario_sha256") != hashlib.sha256(dump_scenario(scenario)).hexdigest():
            errors.append("scenario SHA-256: report does not match deterministic scenario bytes")
    return errors


def _pilot_report(
    scenario: dict,
    base_report: dict,
    refined: list[dict],
    manifest: dict,
    che_cities: set[str],
    city_map: dict[str, str],
    remap: dict[str, str],
    runtime_city_ids: dict[str, int],
) -> dict:
    expectation = PILOT_EXPECTATIONS[manifest["code"]]
    errors: list[str] = []
    source_by_id = _matching_rows(refined, manifest["year_month"], errors)
    if errors:
        raise ValueError("; ".join(errors))
    general_by_id = _general_rows(scenario)
    source_counts = Counter(row.get("status") for _, row in source_by_id.values())
    report = dict(base_report)
    importer_lifecycle = _importer_lifecycle(scenario["general"], scenario["startYear"])
    report["importer_eligible_total"] = importer_lifecycle["active_at_start"]
    report["importer_lifecycle"] = importer_lifecycle
    report["seed_readiness"] = _seed_readiness(scenario)
    report["city_overlap_count"] = _city_overlap_count(scenario)
    report["source_status_counts"] = {
        status: source_counts[status]
        for status in ("君主", "太守", "都督", "一般", "在野", "未発見", "未登場", "死亡")
    }
    report["excluded_status_counts"] = {
        status: source_counts[status]
        for status in sorted(NON_EMITTED_STATUSES)
    }
    report["rank_semantics"] = _rank_semantics(source_by_id, general_by_id)
    report["representatives"] = _representative_evidence(
        expectation,
        source_by_id,
        general_by_id,
        city_map,
        remap,
        che_cities,
        runtime_city_ids,
    )
    report["scenario_sha256"] = hashlib.sha256(dump_scenario(scenario)).hexdigest()
    return report


def dump_report(report: dict) -> bytes:
    return (json.dumps(report, ensure_ascii=False, indent=2) + "\n").encode("utf-8")


def build_pilot(
    manifest_path: Path,
    refined: list[dict],
    che_cities: set[str],
    city_map: dict[str, str],
    remap: dict[str, str],
    name_map: dict[int, str],
    defaults: dict,
    runtime_city_ids: dict[str, int],
) -> tuple[dict, dict, dict]:
    raw_manifest = load_manifest(manifest_path)
    manifest = validate_manifest(raw_manifest, refined, che_cities)
    scenario, base_report = build(
        refined,
        manifest,
        city_map,
        remap,
        name_map,
        defaults,
        runtime_city_ids=runtime_city_ids,
    )
    report = _pilot_report(
        scenario, base_report, refined, manifest, che_cities, city_map, remap, runtime_city_ids
    )
    errors = verify(scenario, report, refined, manifest, che_cities, runtime_city_ids)
    if errors:
        raise ValueError("pilot verification failed: " + "; ".join(errors))
    return scenario, report, manifest


def build_runtime_city_ids(world: dict, bindings: dict, required_aliases: set[str]) -> dict[str, int]:
    """Resolve reviewed physical aliases without consulting display names."""
    if (
        not isinstance(world, dict)
        or not isinstance(world.get("_meta"), dict)
        or world["_meta"].get("map") != "han-world-v3"
        or not isinstance(world.get("cities"), list)
        or not world["cities"]
        or not isinstance(bindings, dict)
        or bindings.get("worldVersion") != "han-world-v3"
        or not isinstance(bindings.get("bindings"), list)
    ):
        raise ValueError("pilot city bindings require a V3 world and binding document")
    if not isinstance(required_aliases, set) or not required_aliases or not all(
        isinstance(alias, str) and alias.strip() for alias in required_aliases
    ):
        raise ValueError("pilot city bindings require a non-empty alias set")
    by_place: dict[str, int] = {}
    city_ids: set[int] = set()
    for city in world["cities"]:
        if not isinstance(city, dict) or not _is_int(city.get("id")) or city["id"] <= 0 or city["id"] in city_ids:
            raise ValueError("V3 world has duplicate or invalid numeric city ids")
        place = city.get("physicalPlaceRef")
        if not isinstance(place, str) or not place.strip() or place in by_place:
            raise ValueError("V3 world has duplicate or invalid physical city references")
        by_place[place] = city["id"]
        city_ids.add(city["id"])
    aliases: dict[str, int] = {}
    used_places: set[str] = set()
    for binding in bindings["bindings"]:
        if not isinstance(binding, dict):
            raise ValueError("pilot city alias requires approved physical evidence")
        alias, place = binding.get("alias"), binding.get("physicalPlaceRef")
        sources = binding.get("sourceRefs")
        if (
            not isinstance(alias, str) or not alias.strip()
            or not isinstance(place, str) or not place.strip()
            or binding.get("reviewState") != "APPROVED"
            or not isinstance(sources, list) or not sources
            or not all(isinstance(source, str) and source.strip() for source in sources)
        ):
            raise ValueError("pilot city alias requires approved physical evidence")
        if alias in aliases or place in used_places:
            raise ValueError(f"duplicate pilot alias or physical city: {alias}")
        if place not in by_place:
            raise ValueError(f"pilot city alias {alias} has unknown physical reference: {place}")
        aliases[alias] = by_place[place]
        used_places.add(place)
    missing = sorted(required_aliases - aliases.keys())
    if missing:
        raise ValueError(f"reviewed physical pilot city aliases are missing: {missing}")
    return {alias: aliases[alias] for alias in sorted(required_aliases)}


def _load_inputs(city_bindings_path: Path | None = None) -> tuple[list[dict], set[str], dict[str, str], dict[str, str], dict[int, str], dict, dict[str, int]]:
    bindings_path = city_bindings_path or PILOT_CITY_BINDINGS_PATH
    if not bindings_path.is_file():
        raise ValueError(
            "reviewed physical city bindings are required for V3 pilots; "
            f"adjudicate aliases in {bindings_path} before generation"
        )
    city_map = load_mapping(CITY_MAP_PATH)
    remap = load_mapping(REMAP_PATH)
    city_aliases = set(city_map.values()) | set(remap.values())
    for expectation in PILOT_EXPECTATIONS.values():
        for relocation in expectation["city_relocations"]:
            city_aliases.update((relocation["source_city"], relocation["assigned_city"]))
    runtime_city_ids = build_runtime_city_ids(
        _read_json(HAN_V3_PATH), _read_json(bindings_path), city_aliases
    )
    refined = _read_json(REFINED_PATH)
    defaults = _read_json(DEFAULTS_PATH)
    if not isinstance(refined, list) or not isinstance(defaults, dict):
        raise ValueError("pilot input files have an invalid root shape")
    return (
        refined,
        city_aliases,
        city_map,
        remap,
        _load_name_map(NAME_MAP_PATH),
        defaults,
        runtime_city_ids,
    )


def generate_pilots(
    output_directory: Path = OUTPUT_DIRECTORY,
    report_directory: Path = REPORT_DIRECTORY,
    city_bindings_path: Path | None = None,
) -> list[dict]:
    refined, che_cities, city_map, remap, name_map, defaults, runtime_city_ids = _load_inputs(city_bindings_path)
    output_directory.mkdir(parents=True, exist_ok=True)
    report_directory.mkdir(parents=True, exist_ok=True)
    generated: list[dict] = []
    for code in sorted(PILOT_EXPECTATIONS):
        manifest_path = MANIFEST_DIRECTORY / f"{code}.yaml"
        first_scenario, first_report, _ = build_pilot(
            manifest_path, refined, che_cities, city_map, remap, name_map, defaults, runtime_city_ids
        )
        second_scenario, second_report, _ = build_pilot(
            manifest_path, refined, che_cities, city_map, remap, name_map, defaults, runtime_city_ids
        )
        scenario_bytes = dump_scenario(first_scenario)
        report_bytes = dump_report(first_report)
        if scenario_bytes != dump_scenario(second_scenario):
            raise ValueError(f"{code} scenario bytes changed across repeated generation")
        if report_bytes != dump_report(second_report):
            raise ValueError(f"{code} report bytes changed across repeated generation")
        scenario_path = output_directory / f"{code}.json"
        report_path = report_directory / f"{code}-report.json"
        scenario_path.write_bytes(scenario_bytes)
        report_path.write_bytes(report_bytes)
        generated.append({
            "code": code,
            "scenario_path": scenario_path,
            "report_path": report_path,
            "scenario_sha256": hashlib.sha256(scenario_bytes).hexdigest(),
            "report_sha256": hashlib.sha256(report_bytes).hexdigest(),
            "scenario_bytes": len(scenario_bytes),
            "report_bytes": len(report_bytes),
            "affiliated_count": first_report["affiliated_count"],
            "neutral_count": first_report["neutral_count"],
            "importer_eligible_total": first_report["importer_eligible_total"],
        })
    return generated


def main(arguments: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Generate and verify deterministic RTK14 pilot scenarios.")
    parser.add_argument("--output-dir", type=Path, default=OUTPUT_DIRECTORY)
    parser.add_argument("--report-dir", type=Path, default=REPORT_DIRECTORY)
    parser.add_argument("--city-bindings", type=Path, help="reviewed physical alias document for V3 pilots")
    args = parser.parse_args(arguments)
    try:
        generated = generate_pilots(args.output_dir, args.report_dir, args.city_bindings)
    except (OSError, ValueError, json.JSONDecodeError) as error:
        print(f"pilot generation failed: {error}")
        return 1
    for result in generated:
        print(
            f"{result['code']} affiliated={result['affiliated_count']} neutral={result['neutral_count']} "
            f"eligible={result['importer_eligible_total']} scenario_sha256={result['scenario_sha256']} "
            f"report_sha256={result['report_sha256']}"
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
