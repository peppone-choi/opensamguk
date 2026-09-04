#!/usr/bin/env python3
"""Fail-closed audit of Han CityConst-vs-spatial supply disagreements."""

from __future__ import annotations

import argparse
import json
from collections import Counter, deque
from dataclasses import dataclass
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[2]
TILES_PATH = ROOT / "data/map/han-tiles.json"
RUNTIME_MAP_PATH = ROOT / "infra/src/main/resources/map/han.json"
OWNERSHIP_PATH = ROOT / "data/map/han-scenario-province-ownership-v1.json"
LEDGER_PATH = ROOT / "data/curated/han/supply-disconnection-adjudications-v1.json"
SOURCE_LEDGER_PATH = ROOT / "data/curated/han/territory-disconnection-adjudications-v1.json"
SCENARIO_DIR = ROOT / "infra/src/main/resources/scenario"

DECISIONS = {
    "PROTECT_GEOMETRY_DEFECT",
    "PROTECT_PARENT_MISASSIGNMENT",
    "UPHOLD_WATER_ROUTE_ONLY",
    "UPHOLD_HISTORICAL_EXCLAVE",
}
PROTECT_DECISIONS = {"PROTECT_GEOMETRY_DEFECT", "PROTECT_PARENT_MISASSIGNMENT"}
UPHOLD_DECISIONS = {"UPHOLD_WATER_ROUTE_ONLY", "UPHOLD_HISTORICAL_EXCLAVE"}
SOURCE_VERDICT_BY_DECISION = {
    "PROTECT_GEOMETRY_DEFECT": "GEOMETRY_DEFECT",
    "PROTECT_PARENT_MISASSIGNMENT": "PARENT_MISASSIGNMENT",
    "UPHOLD_WATER_ROUTE_ONLY": "WATER_SEPARATED",
    "UPHOLD_HISTORICAL_EXCLAVE": "HISTORICAL_EXCLAVE",
}
VERDICTS = (
    "BOTH_SUPPLIED",
    "CITY_ONLY_PROTECTED",
    "SPATIAL_ONLY_SUPPLIED",
    "BOTH_UNSUPPLIED",
    "SPATIAL_CUT_UPHELD",
)


@dataclass(frozen=True)
class AuditResult:
    errors: list[str]
    rows: list[dict[str, Any]]
    summaries: dict[int, dict[str, int]]


def _bfs(seeds: list[int], adjacency: dict[int, list[int]], owners: dict[int, int]) -> set[int]:
    reached: set[int] = set()
    queue: deque[int] = deque()
    for seed in seeds:
        if seed not in owners or seed in reached:
            continue
        reached.add(seed)
        queue.append(seed)
    while queue:
        node = queue.popleft()
        nation_id = owners[node]
        for neighbor in adjacency.get(node, []):
            if neighbor in reached or owners.get(neighbor) != nation_id:
                continue
            reached.add(neighbor)
            queue.append(neighbor)
    return reached


def _scenario_runtime(scenario: dict[str, Any]) -> tuple[dict[int, int], list[tuple[int, int]]]:
    owner_by_city: dict[int, int] = {}
    capitals: list[tuple[int, int]] = []
    for nation_id, nation in enumerate(scenario.get("nation", []), start=1):
        city_ids = [int(city_id) for city_id in nation[8]]
        for city_id in city_ids:
            owner_by_city[city_id] = nation_id
        if nation[7] > 0 and city_ids:
            capitals.append((city_ids[0], nation_id))
    return owner_by_city, capitals


def _active_decisions(
    ledger_rows: list[dict[str, Any]], scenario_code: int, runtime_city_id: int
) -> list[dict[str, Any]]:
    return [
        row for row in ledger_rows
        if row.get("runtimeCityId") == runtime_city_id
        and row.get("effectiveScenarioFrom", -1) <= scenario_code <= row.get("effectiveScenarioTo", -1)
    ]


def audit_documents(
    tiles: dict[str, Any],
    runtime_map: dict[str, Any],
    ownership: dict[str, Any],
    scenarios: dict[int, dict[str, Any]],
    ledger: dict[str, Any],
    source_ledger: dict[str, Any],
) -> AuditResult:
    errors: list[str] = []
    rows: list[dict[str, Any]] = []
    summaries: dict[int, dict[str, int]] = {}

    provinces = tiles.get("provinceRecords", [])
    province_index_by_id = {row.get("id"): index for index, row in enumerate(provinces)}
    if len(province_index_by_id) != len(provinces):
        errors.append("provinceRecords contains duplicate IDs")

    province_adjacency: dict[int, list[int]] = {index: [] for index in range(len(provinces))}
    for edge in tiles.get("adjacency", {}).get("county", []):
        a, b = edge.get("a"), edge.get("b")
        if not isinstance(a, int) or not isinstance(b, int) or a not in province_adjacency or b not in province_adjacency:
            errors.append(f"invalid province adjacency {a}-{b}")
            continue
        province_adjacency[a].append(b)
        province_adjacency[b].append(a)

    runtime_cities = sorted(runtime_map.get("cities", []), key=lambda row: row.get("id", -1))
    runtime_by_id: dict[int, dict[str, Any]] = {}
    city_adjacency: dict[int, list[int]] = {}
    for city in runtime_cities:
        city_id = city.get("id")
        province_index = city.get("provinceId")
        if province_index is None:
            continue
        if city_id in runtime_by_id:
            errors.append(f"duplicate runtime city {city_id}")
        runtime_by_id[city_id] = city
        city_adjacency[city_id] = [int(neighbor) for neighbor in city.get("connections", [])]
        if not isinstance(province_index, int) or province_index not in province_adjacency:
            errors.append(f"runtime city {city_id} has invalid provinceId {province_index}")

    source_by_key = {
        row.get("componentKey"): row for row in source_ledger.get("adjudications", [])
        if isinstance(row.get("componentKey"), str)
    }
    ledger_rows = ledger.get("decisions", [])
    if ledger.get("schemaVersion") != 1 or not isinstance(ledger_rows, list):
        errors.append("ledger must use schemaVersion 1 and a decisions array")
        ledger_rows = []

    scenario_codes = sorted(scenarios)
    for index, decision_row in enumerate(ledger_rows):
        prefix = f"decision[{index}] city {decision_row.get('runtimeCityId')}"
        decision = decision_row.get("decision")
        if decision not in DECISIONS:
            errors.append(f"{prefix} has unknown decision {decision!r}")
        rationale = decision_row.get("rationale")
        if not isinstance(rationale, str) or not rationale.strip():
            errors.append(f"{prefix} is missing rationale")
        expected = decision_row.get("expectedCurrentReachability")
        if expected != "CITY_ONLY":
            errors.append(f"{prefix} expectedCurrentReachability must be CITY_ONLY")
        source_key = decision_row.get("sourceLedgerRow")
        if not isinstance(source_key, str) or not source_key:
            errors.append(f"{prefix} is missing sourceLedgerRow")
        elif source_key not in source_by_key:
            errors.append(f"{prefix} references unknown sourceLedgerRow {source_key}")
        city = runtime_by_id.get(decision_row.get("runtimeCityId"))
        if city is None:
            errors.append(f"{prefix} references unknown runtime city")
            continue
        if decision_row.get("physicalPlaceId") != city.get("physicalPlaceId"):
            errors.append(f"{prefix} physicalPlaceId drift")
        province_index = city.get("provinceId")
        if isinstance(province_index, int) and province_index in province_adjacency:
            province = provinces[province_index]
            if decision_row.get("jurisdictionId") != province.get("jurisdictionId"):
                errors.append(f"{prefix} jurisdictionId drift")
            source = source_by_key.get(source_key)
            if source and (
                source.get("unitId") != province.get("parentRegionId")
                or province.get("jurisdictionId") not in source.get("memberIds", [])
            ):
                errors.append(f"{prefix} sourceLedgerRow does not cover its jurisdiction component")
            if source and decision in SOURCE_VERDICT_BY_DECISION and (
                source.get("verdict") != SOURCE_VERDICT_BY_DECISION[decision]
            ):
                errors.append(
                    f"{prefix} decision {decision} does not match source verdict {source.get('verdict')!r}"
                )
        start, end = decision_row.get("effectiveScenarioFrom"), decision_row.get("effectiveScenarioTo")
        if not isinstance(start, int) or not isinstance(end, int) or start > end:
            errors.append(f"{prefix} has invalid effective scenario range")

    for city_id in sorted(runtime_by_id):
        for scenario_code in scenario_codes:
            active = _active_decisions(ledger_rows, scenario_code, city_id)
            if len(active) > 1:
                errors.append(f"city {city_id} scenario {scenario_code} has overlapping active decision rows")

    ownership_by_scenario = {
        row.get("scenarioCode"): row for row in ownership.get("scenarios", [])
    }
    city_only_keys: set[tuple[int, int]] = set()
    for scenario_code in scenario_codes:
        scenario = scenarios[scenario_code]
        owner_by_city, capitals = _scenario_runtime(scenario)
        owned_city_ids = sorted(city_id for city_id in runtime_by_id if owner_by_city.get(city_id, 0) != 0)

        city_owners = {city_id: owner_by_city[city_id] for city_id in owned_city_ids}
        city_seeds = [city_id for city_id, nation_id in capitals if city_owners.get(city_id) == nation_id]
        city_supplied = _bfs(city_seeds, city_adjacency, city_owners)

        ownership_row = ownership_by_scenario.get(scenario_code)
        if ownership_row is None:
            errors.append(f"scenario {scenario_code} has no spatial ownership")
            continue
        owner_by_province_id = {
            assignment.get("provinceId"): assignment.get("ownerNationId") or 0
            for assignment in ownership_row.get("assignments", [])
        }
        if set(owner_by_province_id) != set(province_index_by_id):
            errors.append(f"scenario {scenario_code} spatial ownership coverage drift")
            continue
        province_owners = {
            index: int(owner_by_province_id.get(province.get("id"), 0))
            for index, province in enumerate(provinces)
        }
        for city_id, city in runtime_by_id.items():
            province_index = city.get("provinceId")
            if isinstance(province_index, int) and province_index in province_owners:
                province_owners[province_index] = owner_by_city.get(city_id, 0)
        spatial_seeds = [
            runtime_by_id[city_id]["provinceId"]
            for city_id, nation_id in capitals
            if city_id in runtime_by_id
            and owner_by_city.get(city_id) == nation_id
            and province_owners.get(runtime_by_id[city_id].get("provinceId")) == nation_id
        ]
        spatial_reached = _bfs(spatial_seeds, province_adjacency, province_owners)
        spatial_supplied = {
            city_id for city_id in owned_city_ids
            if province_owners.get(runtime_by_id[city_id].get("provinceId")) == owner_by_city[city_id]
            and runtime_by_id[city_id].get("provinceId") in spatial_reached
        }

        counts = Counter({verdict: 0 for verdict in VERDICTS})
        for city_id in owned_city_ids:
            by_city, by_spatial = city_id in city_supplied, city_id in spatial_supplied
            active = _active_decisions(ledger_rows, scenario_code, city_id)
            policy = active[0] if len(active) == 1 else None
            if by_city and by_spatial:
                verdict = "BOTH_SUPPLIED"
            elif by_city:
                city_only_keys.add((scenario_code, city_id))
                if policy and policy.get("decision") in UPHOLD_DECISIONS:
                    verdict = "SPATIAL_CUT_UPHELD"
                else:
                    verdict = "CITY_ONLY_PROTECTED"
                if policy is None:
                    errors.append(f"unclassified city-only mismatch: scenario {scenario_code} city {city_id}")
            elif by_spatial:
                verdict = "SPATIAL_ONLY_SUPPLIED"
            else:
                verdict = "BOTH_UNSUPPLIED"
            counts[verdict] += 1
            if by_city != by_spatial:
                city = runtime_by_id[city_id]
                province = provinces[city["provinceId"]]
                rows.append({
                    "scenarioCode": scenario_code,
                    "runtimeCityId": city_id,
                    "physicalPlaceId": city.get("physicalPlaceId"),
                    "jurisdictionId": province.get("jurisdictionId"),
                    "cityGraphSupplied": by_city,
                    "spatialGraphSupplied": by_spatial,
                    "verdict": verdict,
                    "decision": policy.get("decision") if policy else None,
                    "sourceLedgerRow": policy.get("sourceLedgerRow") if policy else None,
                })
        summaries[scenario_code] = {verdict: counts[verdict] for verdict in VERDICTS}

    for index, decision_row in enumerate(ledger_rows):
        city_id = decision_row.get("runtimeCityId")
        for scenario_code in scenario_codes:
            if _active_decisions([decision_row], scenario_code, city_id) and (scenario_code, city_id) not in city_only_keys:
                errors.append(f"stale decision[{index}]: scenario {scenario_code} city {city_id} is no longer city-only")

    owned_scenarios_by_city: dict[int, list[int]] = {}
    for scenario_code, scenario in scenarios.items():
        for city_id, nation_id in _scenario_runtime(scenario)[0].items():
            if nation_id != 0:
                owned_scenarios_by_city.setdefault(city_id, []).append(scenario_code)
    for city_id, city in runtime_by_id.items():
        province_index = city.get("provinceId")
        if (
            city_id in owned_scenarios_by_city
            and isinstance(province_index, int)
            and province_index in province_adjacency
            and not province_adjacency[province_index]
        ):
            for scenario_code in sorted(owned_scenarios_by_city[city_id]):
                active_protection = [
                    row for row in _active_decisions(ledger_rows, scenario_code, city_id)
                    if row.get("decision") in PROTECT_DECISIONS
                ]
                if len(active_protection) != 1:
                    errors.append(
                        f"degree-zero city province: city {city_id} province {province_index} "
                        f"scenario {scenario_code} has no active protection"
                    )

    return AuditResult(
        errors=sorted(set(errors)),
        rows=sorted(rows, key=lambda row: (row["scenarioCode"], row["runtimeCityId"])),
        summaries={code: summaries[code] for code in sorted(summaries)},
    )


def _load_json(path: Path) -> dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8"))


def audit_repository() -> AuditResult:
    ownership = _load_json(OWNERSHIP_PATH)
    scenario_codes = sorted(row["scenarioCode"] for row in ownership["scenarios"])
    scenarios = {
        code: _load_json(SCENARIO_DIR / f"scenario_{code}.json") for code in scenario_codes
    }
    return audit_documents(
        _load_json(TILES_PATH),
        _load_json(RUNTIME_MAP_PATH),
        ownership,
        scenarios,
        _load_json(LEDGER_PATH),
        _load_json(SOURCE_LEDGER_PATH),
    )


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true", help="exit non-zero on any audit error")
    parser.add_argument("--json", action="store_true", help="print deterministic JSON inventory")
    args = parser.parse_args()
    result = audit_repository()
    payload = {"summaries": result.summaries, "rows": result.rows, "errors": result.errors}
    if args.json:
        print(json.dumps(payload, ensure_ascii=False, indent=2, sort_keys=True))
    else:
        for scenario_code, counts in result.summaries.items():
            print(f"{scenario_code}: " + " ".join(f"{key}={counts[key]}" for key in VERDICTS))
        print(f"mismatches={len(result.rows)} errors={len(result.errors)}")
        for error in result.errors:
            print(f"ERROR: {error}")
    return 1 if args.check and result.errors else 0


if __name__ == "__main__":
    raise SystemExit(main())
