#!/usr/bin/env python3
"""Compare canonical scenario province ownership with the current runtime fill path.

The runtime map resource stores ``provinceId`` as an integer array index.  The
scenario ownership artifact stores the stable string ``provinceRecords[].id``.
This audit makes that conversion explicit and models the current web behaviour:
only provinces linked from a city listed in a scenario nation's city array are
politically coloured.
"""

from __future__ import annotations

import argparse
from dataclasses import asdict, dataclass
import json
from pathlib import Path
import re
from typing import Any, Mapping, Sequence


_SCENARIO_RESOURCE_CODE = re.compile(r"^scenario_(\d+)$")


@dataclass(frozen=True)
class RuntimeProvinceFillAudit:
    scenario_code: int
    canonical_owned_count: int
    runtime_colored_count: int
    missing_owned_province_record_ids: tuple[str, ...]
    extra_runtime_province_record_ids: tuple[str, ...]
    owner_mismatches: dict[str, tuple[str, str]]
    owned_city_ids_without_province_index: tuple[int, ...]

    def to_json_dict(self) -> dict[str, Any]:
        result = asdict(self)
        result["owner_mismatches"] = {
            province_id: list(owners)
            for province_id, owners in self.owner_mismatches.items()
        }
        return result


def normalize_scenario_code(resource_stem: str) -> int:
    """Convert a resource stem such as ``scenario_1010`` to numeric code."""
    match = _SCENARIO_RESOURCE_CODE.fullmatch(resource_stem)
    if match is None:
        raise ValueError(f"invalid scenario resource code: {resource_stem}")
    return int(match.group(1))


def _owned_city_nations(scenario: Mapping[str, Any]) -> dict[int, str]:
    result: dict[int, str] = {}
    for nation in scenario.get("nation", []):
        if not isinstance(nation, Sequence) or isinstance(nation, (str, bytes)):
            raise ValueError("scenario nation must be an array")
        if len(nation) <= 8:
            raise ValueError("scenario nation is missing its city array at index 8")
        nation_name = str(nation[0])
        for raw_city_id in nation[8]:
            city_id = int(raw_city_id)
            previous = result.setdefault(city_id, nation_name)
            if previous != nation_name:
                raise ValueError(
                    f"city {city_id} belongs to multiple nations: {previous}, {nation_name}"
                )
    return result


def audit_runtime_fill(
    *,
    scenario_code: int,
    tiles: Mapping[str, Any],
    map_resource: Mapping[str, Any],
    scenario: Mapping[str, Any],
    ownership: Mapping[str, Any],
    nation_name_by_key: Mapping[str, str],
) -> RuntimeProvinceFillAudit:
    records = tiles.get("provinceRecords", [])
    province_record_ids = [str(record["id"]) for record in records]
    if len(province_record_ids) != len(set(province_record_ids)):
        raise ValueError("duplicate stable province record id")
    province_record_id_set = set(province_record_ids)

    city_nation = _owned_city_nations(scenario)
    runtime_owner_by_province: dict[str, str] = {}
    owned_without_index: list[int] = []
    map_city_ids: set[int] = set()

    for city in map_resource.get("cities", []):
        city_id = int(city["id"])
        map_city_ids.add(city_id)
        nation_name = city_nation.get(city_id)
        if nation_name is None:
            continue
        province_index = city.get("provinceId")
        if province_index is None:
            owned_without_index.append(city_id)
            continue
        if not isinstance(province_index, int) or isinstance(province_index, bool):
            raise ValueError(f"city {city_id} has non-integer province index {province_index!r}")
        if not 0 <= province_index < len(province_record_ids):
            raise ValueError(
                f"city {city_id} references unknown province index {province_index}"
            )
        province_record_id = province_record_ids[province_index]
        previous = runtime_owner_by_province.setdefault(province_record_id, nation_name)
        if previous != nation_name:
            raise ValueError(
                f"province {province_record_id} is coloured by multiple nations: "
                f"{previous}, {nation_name}"
            )

    # A scenario may reference a city absent from the runtime map. It is equally
    # incapable of colouring a province, so retain it in the unmapped diagnostic.
    owned_without_index.extend(set(city_nation) - map_city_ids)

    canonical_owner_by_province: dict[str, str] = {}
    for assignment in ownership.get("assignments", []):
        assignment_code = assignment.get("scenarioCode")
        if assignment_code is not None and int(assignment_code) != scenario_code:
            raise ValueError(
                f"ownership assignment scenario {assignment_code} does not match {scenario_code}"
            )
        owner_key = assignment.get("ownerNationKey")
        if owner_key is None:
            continue
        province_record_id = str(assignment["provinceId"])
        if province_record_id not in province_record_id_set:
            raise ValueError(f"ownership references unknown province record {province_record_id}")
        try:
            owner_name = nation_name_by_key[str(owner_key)]
        except KeyError as error:
            raise ValueError(f"unknown canonical nation key {owner_key}") from error
        canonical_owner_by_province[province_record_id] = owner_name

    canonical_ids = set(canonical_owner_by_province)
    runtime_ids = set(runtime_owner_by_province)
    mismatches = {
        province_record_id: (
            runtime_owner_by_province[province_record_id],
            canonical_owner_by_province[province_record_id],
        )
        for province_record_id in sorted(canonical_ids & runtime_ids)
        if runtime_owner_by_province[province_record_id]
        != canonical_owner_by_province[province_record_id]
    }

    return RuntimeProvinceFillAudit(
        scenario_code=scenario_code,
        canonical_owned_count=len(canonical_ids),
        runtime_colored_count=len(runtime_ids),
        missing_owned_province_record_ids=tuple(sorted(canonical_ids - runtime_ids)),
        extra_runtime_province_record_ids=tuple(sorted(runtime_ids - canonical_ids)),
        owner_mismatches=mismatches,
        owned_city_ids_without_province_index=tuple(sorted(set(owned_without_index))),
    )


def _load_json(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def audit_repository(root: Path) -> list[RuntimeProvinceFillAudit]:
    tiles = _load_json(root / "data/map/han-tiles.json")
    map_resource = _load_json(root / "infra/src/main/resources/map/han.json")
    ownership_document = _load_json(
        root / "data/map/han-scenario-province-ownership-v1.json"
    )
    claims_document = _load_json(
        root / "data/curated/han/scenario-province-claims-v1.json"
    )
    ownership_by_code = {
        int(row["scenarioCode"]): row for row in ownership_document["scenarios"]
    }
    claims_by_code = {
        int(row["scenarioCode"]): row for row in claims_document["scenarios"]
    }

    audits: list[RuntimeProvinceFillAudit] = []
    for scenario_code in sorted(ownership_by_code):
        resource_path = root / f"infra/src/main/resources/scenario/scenario_{scenario_code}.json"
        normalized_code = normalize_scenario_code(resource_path.stem)
        claims = claims_by_code[normalized_code]
        nation_names = {
            str(row["nationKey"]): str(row["displayNationName"])
            for row in claims["nationRefs"]
        }
        audits.append(
            audit_runtime_fill(
                scenario_code=normalized_code,
                tiles=tiles,
                map_resource=map_resource,
                scenario=_load_json(resource_path),
                ownership=ownership_by_code[normalized_code],
                nation_name_by_key=nation_names,
            )
        )
    return audits


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument("--summary", action="store_true")
    args = parser.parse_args()
    audits = audit_repository(args.root)
    if args.summary:
        payload = [
            {
                "scenarioCode": row.scenario_code,
                "canonicalOwned": row.canonical_owned_count,
                "runtimeColored": row.runtime_colored_count,
                "missingOwned": len(row.missing_owned_province_record_ids),
                "extraRuntime": len(row.extra_runtime_province_record_ids),
                "ownerMismatches": len(row.owner_mismatches),
                "ownedCitiesWithoutProvinceIndex": len(
                    row.owned_city_ids_without_province_index
                ),
            }
            for row in audits
        ]
    else:
        payload = [row.to_json_dict() for row in audits]
    print(json.dumps(payload, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
