#!/usr/bin/env python3
"""Migrate the reviewed legacy territory table into typed province claims."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
import sys
from typing import Any, Mapping

ROOT = Path(__file__).resolve().parents[2]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from tools.scenario.province_ownership_contract import OwnershipContractError


LEGACY_PATH = ROOT / "tools/scenario/han_ownership.json"
MAP_PATH = ROOT / "data/map/han-tiles.json"
OUTPUT_PATH = ROOT / "data/curated/han/scenario-province-claims-v1.json"


def canonical_bytes(document: Mapping[str, Any]) -> bytes:
    return (json.dumps(document, ensure_ascii=False, indent=2) + "\n").encode("utf-8")


def _scenario_rows(legacy: Mapping[str, Any]) -> list[tuple[str, Mapping[str, Any]]]:
    return sorted(
        ((key, value) for key, value in legacy.items() if key.startswith("scenario_")),
        key=lambda item: int(item[0].removeprefix("scenario_")),
    )


def _map_indexes(
    map_doc: Mapping[str, Any],
) -> tuple[dict[str, str], dict[str, str], dict[str, list[str]]]:
    parent_name_to_id: dict[str, str] = {}
    parent_id_to_name: dict[str, str] = {}
    for row in map_doc["parentRegions"]:
        name, parent_id = row["displayName"], row["id"]
        if name in parent_name_to_id:
            raise OwnershipContractError("AMBIGUOUS_PARENT_REGION_NAME", parent_name=name)
        parent_name_to_id[name] = parent_id
        parent_id_to_name[parent_id] = name

    province_name_to_ids: dict[str, list[str]] = {}
    for row in map_doc["provinceRecords"]:
        display_name = row["displayName"]
        legacy_name = display_name[:-1] if display_name.endswith("현") else display_name
        province_name_to_ids.setdefault(legacy_name, []).append(row["id"])
    return parent_name_to_id, parent_id_to_name, province_name_to_ids


def _source_type_and_work(basis: str, placement_basis: str) -> tuple[str, str]:
    if placement_basis == "IF_SCENARIO":
        return "IF_DESIGN", "OpenSamguk IF scenario design"
    standard_histories = [name for name in ("三國志", "後漢書", "晉書") if name in basis]
    chronicles = [name for name in ("資治通鑑", "華陽國志") if name in basis]
    if standard_histories:
        return "STANDARD_HISTORY", " / ".join(standard_histories + chronicles)
    if chronicles:
        return "CHRONICLE", " / ".join(chronicles)
    return "PROJECT_POLICY", "OpenSamguk reviewed scenario placement"


def migrate(legacy: Mapping[str, Any], map_doc: Mapping[str, Any]) -> dict[str, Any]:
    parent_name_to_id, _, province_name_to_ids = _map_indexes(map_doc)
    known_province_ids = {row["id"] for row in map_doc["provinceRecords"]}
    continuity = legacy.get("_interiorContinuity") or {}
    continuity_assignments = list(continuity.get("assignments") or [])
    continuity_allowlists = list(continuity.get("allowlists") or [])
    scenarios: list[dict[str, Any]] = []
    evidence: list[dict[str, Any]] = []
    active_codes: list[int] = []

    for scenario_key, scenario in _scenario_rows(legacy):
        code = int(scenario_key.removeprefix("scenario_"))
        active_codes.append(code)
        placement_basis = scenario.get("placementBasis", "HISTORICAL")
        baseline_evidence_id = f"S{code}-BASELINE-EVIDENCE"
        baseline_claim_id = f"S{code}-BASELINE"
        evidence.append({
            "evidenceId": baseline_evidence_id,
            "sourceType": "PROJECT_POLICY" if placement_basis == "HISTORICAL" else "IF_DESIGN",
            "work": "OpenSamguk scenario placement policy",
            "section": f"scenario {code} unowned baseline",
            "locator": f"effective year {scenario['startYear']}",
            "excerpt": "Legal title, neighbouring colour, and missing evidence do not create effective territorial control.",
        })

        nations = list((scenario.get("nations") or {}).items())
        nation_refs: list[dict[str, Any]] = []
        nation_key_by_name: dict[str, str] = {}
        pending: list[dict[str, Any]] = []
        admin_claim_by_parent: dict[str, list[str]] = {}
        for index, (nation_name, nation) in enumerate(nations, start=1):
            nation_key = f"S{code}-N{index:03d}"
            nation_key_by_name[nation_name] = nation_key
            evidence_id = f"{nation_key}-PLACEMENT-EVIDENCE"
            basis = str(nation.get("basis") or "").strip()
            if not basis:
                raise OwnershipContractError("NATION_WITHOUT_BASIS", scenario_code=code, nation=nation_name)
            source_type, work = _source_type_and_work(basis, placement_basis)
            evidence_row = {
                "evidenceId": evidence_id,
                "sourceType": source_type,
                "work": work,
                "section": f"scenario {code} / {nation_name}",
                "locator": f"effective year {scenario['startYear']}",
                "excerpt": basis,
            }
            if isinstance(nation.get("sourceUrl"), str):
                evidence_row["url"] = nation["sourceUrl"]
            evidence.append(evidence_row)
            nation_refs.append({
                "nationKey": nation_key,
                "scenarioNationName": nation_name,
                "displayNationName": nation_name,
                "placementEvidenceIds": [evidence_id],
                "placementRationale": basis,
            })

            parent_ids: list[str] = []
            for parent_name in nation.get("juns") or []:
                parent_id = parent_name_to_id.get(parent_name)
                if parent_id is None:
                    raise OwnershipContractError(
                        "UNKNOWN_PARENT_REGION", scenario_code=code, nation=nation_name, parent_region=parent_name,
                    )
                parent_ids.append(parent_id)
            if parent_ids:
                claim_id = f"{nation_key}-ADMIN"
                pending.append({
                    "claimId": claim_id,
                    "claimKind": "IF_SCENARIO" if placement_basis == "IF_SCENARIO" else "ADMIN_REGION_CONTROL",
                    "ownerNationKey": nation_key,
                    "target": {"parentRegionIds": parent_ids},
                    "evidenceIds": [evidence_id],
                    "overridesClaimIds": [baseline_claim_id],
                    "rationale": basis,
                    "legacyGrantKind": "ADMIN_REGION_CONTROL",
                })
                for parent_id in parent_ids:
                    admin_claim_by_parent.setdefault(parent_id, []).append(claim_id)

            province_ids: list[str] = []
            for city_name in nation.get("cities") or []:
                matches = province_name_to_ids.get(city_name, [])
                if not matches:
                    raise OwnershipContractError(
                        "UNKNOWN_PROVINCE", scenario_code=code, nation=nation_name, province_name=city_name,
                    )
                if len(matches) != 1:
                    raise OwnershipContractError(
                        "AMBIGUOUS_PROVINCE_NAME",
                        scenario_code=code,
                        nation=nation_name,
                        province_name=city_name,
                        province_ids=matches,
                    )
                province_ids.append(matches[0])
            if province_ids:
                pending.append({
                    "claimId": f"{nation_key}-DIRECT",
                    "claimKind": "IF_SCENARIO" if placement_basis == "IF_SCENARIO" else "PROVINCE_DIRECT",
                    "ownerNationKey": nation_key,
                    "target": {"provinceIds": province_ids},
                    "evidenceIds": [evidence_id],
                    "overridesClaimIds": [baseline_claim_id],
                    "rationale": basis,
                    "legacyGrantKind": "PROVINCE_DIRECT",
                })

        scenario_continuity = [
            row for row in continuity_assignments if row.get("scenarioCode") == code
        ]
        for index, row in enumerate(scenario_continuity, start=1):
            nation_name = str(row.get("nation") or "")
            nation_key = nation_key_by_name.get(nation_name)
            if nation_key is None:
                raise OwnershipContractError(
                    "UNKNOWN_CONTINUITY_NATION", scenario_code=code, nation=nation_name,
                )
            basis = str(row.get("basis") or "").strip()
            if not basis:
                raise OwnershipContractError(
                    "CONTINUITY_WITHOUT_BASIS", scenario_code=code, continuity_index=index,
                )
            parent_ids: list[str] = []
            for parent_name in row.get("juns") or []:
                parent_id = parent_name_to_id.get(parent_name)
                if parent_id is None:
                    raise OwnershipContractError(
                        "UNKNOWN_PARENT_REGION",
                        scenario_code=code,
                        nation=nation_name,
                        parent_region=parent_name,
                    )
                parent_ids.append(parent_id)
            province_ids = list(row.get("provinceIds") or [])
            unknown_province_ids = sorted(set(province_ids) - known_province_ids)
            if unknown_province_ids:
                raise OwnershipContractError(
                    "UNKNOWN_PROVINCE",
                    scenario_code=code,
                    nation=nation_name,
                    province_ids=unknown_province_ids,
                )
            if not parent_ids and not province_ids:
                raise OwnershipContractError(
                    "EMPTY_CONTINUITY_TARGET", scenario_code=code, nation=nation_name,
                )
            evidence_id = f"S{code}-CONTINUITY-{index:03d}-EVIDENCE"
            source_type, work = _source_type_and_work(basis, placement_basis)
            evidence_row = {
                "evidenceId": evidence_id,
                "sourceType": source_type,
                "work": work,
                "section": f"scenario {code} interior continuity / {nation_name}",
                "locator": f"effective year {scenario['startYear']}",
                "excerpt": basis,
            }
            if isinstance(row.get("sourceUrl"), str):
                evidence_row["url"] = row["sourceUrl"]
            evidence.append(evidence_row)
            target = {}
            if parent_ids:
                target["parentRegionIds"] = parent_ids
            if province_ids:
                target["provinceIds"] = province_ids
            pending.append({
                "claimId": f"S{code}-CONTINUITY-{index:03d}",
                "claimKind": (
                    "IF_SCENARIO" if placement_basis == "IF_SCENARIO"
                    else "PROVINCE_DIRECT" if province_ids
                    else "ADMIN_REGION_CONTROL"
                ),
                "ownerNationKey": nation_key,
                "target": target,
                "evidenceIds": [evidence_id],
                "overridesClaimIds": [baseline_claim_id],
                "rationale": basis,
                "legacyGrantKind": "PROVINCE_DIRECT" if province_ids else "ADMIN_REGION_CONTROL",
            })

        province_parent = {row["id"]: row["parentRegionId"] for row in map_doc["provinceRecords"]}
        for claim in pending:
            if claim["legacyGrantKind"] != "PROVINCE_DIRECT":
                continue
            broad_claims = {
                broad
                for province_id in claim["target"]["provinceIds"]
                for broad in admin_claim_by_parent.get(province_parent[province_id], [])
            }
            claim["overridesClaimIds"] = [baseline_claim_id, *sorted(broad_claims)]

        claims = [{
            "claimId": baseline_claim_id,
            "claimKind": "SCENARIO_BASELINE_UNOWNED",
            "ownerNationKey": None,
            "target": {"allProvinces": True},
            "evidenceIds": [baseline_evidence_id],
            "overridesClaimIds": [],
            "rationale": "Only reviewed effective-control claims create ownership.",
        }]
        for claim in pending:
            claim.pop("legacyGrantKind")
            claims.append(claim)

        audit_allowlist: list[dict[str, Any]] = []
        scenario_allowlists = [
            *(scenario.get("auditAllowlist") or []),
            *(row for row in continuity_allowlists if row.get("scenarioCode") == code),
        ]
        for index, row in enumerate(scenario_allowlists, start=1):
            basis = str(row.get("basis") or "").strip()
            if not basis:
                raise OwnershipContractError(
                    "ALLOWLIST_WITHOUT_BASIS", scenario_code=code, allowlist_index=index,
                )
            province_ids = list(row.get("provinceIds") or [])
            unknown_ids = sorted(set(province_ids) - known_province_ids)
            if unknown_ids:
                raise OwnershipContractError(
                    "UNKNOWN_PROVINCE", scenario_code=code, province_id=unknown_ids[0],
                )
            evidence_id = f"S{code}-ALLOWLIST-{index:03d}-EVIDENCE"
            source_type, work = _source_type_and_work(basis, placement_basis)
            evidence_row = {
                "evidenceId": evidence_id,
                "sourceType": source_type,
                "work": work,
                "section": f"scenario {code} topology allowlist {index}",
                "locator": f"effective year {scenario['startYear']}",
                "excerpt": basis,
            }
            if isinstance(row.get("sourceUrl"), str):
                evidence_row["url"] = row["sourceUrl"]
            evidence.append(evidence_row)
            audit_allowlist.append({
                "scenarioCode": code,
                "auditKind": row["auditKind"],
                "provinceIds": province_ids,
                "evidenceIds": [evidence_id],
                "rationale": basis,
                "reviewState": "APPROVED",
            })
        scenarios.append({
            "scenarioCode": code,
            "effectiveYear": scenario["startYear"],
            "placementBasis": placement_basis,
            "nationRefs": nation_refs,
            "claims": claims,
            "auditAllowlist": audit_allowlist,
        })

    return {
        "schemaVersion": 1,
        "mapId": "han-world-v2",
        "unitSet": "han",
        "activeScenarioCodes": active_codes,
        "evidence": evidence,
        "scenarios": scenarios,
    }


def legacy_political_view(
    legacy: Mapping[str, Any], map_doc: Mapping[str, Any] | None = None,
) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for scenario_key, scenario in _scenario_rows(legacy):
        result[scenario_key] = {
            "placementBasis": scenario.get("placementBasis", "HISTORICAL"),
            "startYear": scenario["startYear"],
            "nations": {
                nation_name: {
                    "juns": list(nation.get("juns") or []),
                    "cities": list(nation.get("cities") or []),
                    "basis": nation.get("basis"),
                }
                for nation_name, nation in (scenario.get("nations") or {}).items()
            },
        }
    for row in (legacy.get("_interiorContinuity") or {}).get("assignments") or []:
        scenario = result[f"scenario_{row['scenarioCode']}"]
        nation = scenario["nations"][row["nation"]]
        juns = nation["juns"]
        juns.extend(name for name in row.get("juns") or [] if name not in juns)
        if row.get("provinceIds"):
            if map_doc is None:
                raise ValueError("map document is required for province continuity projection")
            name_by_id = {
                item["id"]: (
                    item["displayName"][:-1]
                    if item["displayName"].endswith("현") else item["displayName"]
                )
                for item in map_doc["provinceRecords"]
            }
            cities = nation["cities"]
            cities.extend(
                name_by_id[province_id]
                for province_id in row["provinceIds"]
                if name_by_id[province_id] not in cities
            )
    return result


def project_legacy_political_view(curated: Mapping[str, Any], map_doc: Mapping[str, Any]) -> dict[str, Any]:
    _, parent_id_to_name, _ = _map_indexes(map_doc)
    province_id_to_name = {
        row["id"]: row["displayName"][:-1] if row["displayName"].endswith("현") else row["displayName"]
        for row in map_doc["provinceRecords"]
    }
    result: dict[str, Any] = {}
    for scenario in curated["scenarios"]:
        code = scenario["scenarioCode"]
        claims_by_nation: dict[str, list[Mapping[str, Any]]] = {}
        for claim in scenario["claims"]:
            owner = claim.get("ownerNationKey")
            if owner is not None:
                claims_by_nation.setdefault(owner, []).append(claim)
        nation_rows: dict[str, Any] = {}
        for nation_ref in scenario["nationRefs"]:
            claims = claims_by_nation.get(nation_ref["nationKey"], [])
            juns: list[str] = []
            cities: list[str] = []
            for claim in claims:
                if "parentRegionIds" in claim["target"]:
                    juns.extend(parent_id_to_name[value] for value in claim["target"]["parentRegionIds"])
                if "provinceIds" in claim["target"]:
                    cities.extend(province_id_to_name[value] for value in claim["target"]["provinceIds"])
            nation_rows[nation_ref.get("displayNationName", nation_ref["scenarioNationName"])] = {
                "juns": juns,
                "cities": cities,
                "basis": nation_ref["placementRationale"],
            }
        result[f"scenario_{code}"] = {
            "placementBasis": scenario["placementBasis"],
            "startYear": scenario["effectiveYear"],
            "nations": nation_rows,
        }
    return result


def count_parent_grants(curated: Mapping[str, Any]) -> int:
    return sum(
        len(claim["target"].get("parentRegionIds", []))
        for scenario in curated["scenarios"]
        for claim in scenario["claims"]
    )


def count_direct_grants(curated: Mapping[str, Any]) -> int:
    return sum(
        len(claim["target"].get("provinceIds", []))
        for scenario in curated["scenarios"]
        for claim in scenario["claims"]
    )


def _load(path: Path) -> dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8"))


def main() -> int:
    parser = argparse.ArgumentParser()
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--write", action="store_true")
    mode.add_argument("--check", action="store_true")
    args = parser.parse_args()

    generated = canonical_bytes(migrate(_load(LEGACY_PATH), _load(MAP_PATH)))
    if args.write:
        OUTPUT_PATH.parent.mkdir(parents=True, exist_ok=True)
        OUTPUT_PATH.write_bytes(generated)
        print(f"wrote {OUTPUT_PATH.relative_to(ROOT)}")
        return 0
    if not OUTPUT_PATH.exists() or OUTPUT_PATH.read_bytes() != generated:
        print(f"drift: {OUTPUT_PATH.relative_to(ROOT)}")
        return 1
    print(f"clean: {OUTPUT_PATH.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
