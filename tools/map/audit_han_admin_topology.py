#!/usr/bin/env python3
"""Derive a deterministic review inventory from the committed Han map hierarchy.

This tool is deliberately read-only with respect to ``han-tiles.json``.  It does
not rebuild geometry, fill gaps, or adjudicate historical legitimacy.  The
output is a pinned candidate ledger for source-backed review.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from collections import Counter, defaultdict
from pathlib import Path
from typing import Iterable


ROOT = Path(__file__).resolve().parents[2]
DEFAULT_TILES = ROOT / "data" / "map" / "han-tiles.json"
DEFAULT_UNITS = ROOT / "data" / "curated" / "han" / "administrative-units.json"
DEFAULT_BINDINGS = (
    ROOT / "data" / "curated" / "han" / "administrative-place-bindings-v1.json"
)
DEFAULT_EXTERNAL_POLICY = (
    ROOT / "data" / "curated" / "han" / "external-region-hierarchy-policy-v1.json"
)
DEFAULT_OUTPUT = (
    ROOT / "data" / "curated" / "han" / "administrative-topology-audit-v1.json"
)


def _sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _decode_owner(document: dict) -> tuple[int, int, list[list[int]]]:
    meta = document.get("_meta")
    if not isinstance(meta, dict):
        raise ValueError("han tiles must contain _meta")
    rows, cols = meta.get("rows"), meta.get("cols")
    if type(rows) is not int or type(cols) is not int or rows <= 0 or cols <= 0:
        raise ValueError("han tiles rows and cols must be positive integers")
    runs = document.get("owner")
    if not isinstance(runs, list):
        raise ValueError("han tiles owner must be RLE runs")
    values: list[int] = []
    for index, run in enumerate(runs):
        if (
            not isinstance(run, list)
            or len(run) != 2
            or type(run[0]) is not int
            or type(run[1]) is not int
            or run[1] <= 0
        ):
            raise ValueError(f"owner[{index}] must be [integer, positive count]")
        values.extend([run[0]] * run[1])
    if len(values) != rows * cols:
        raise ValueError("owner RLE length does not match rows * cols")
    return rows, cols, [values[offset:offset + cols] for offset in range(0, len(values), cols)]


def _require_records(document: dict, key: str) -> list[dict]:
    records = document.get(key)
    if not isinstance(records, list) or not all(isinstance(row, dict) for row in records):
        raise ValueError(f"{key} must be an array of objects")
    return records


def _component_inventory(
    grid: list[list[int]],
    labels_by_province: list[str],
    names_by_id: dict[str, str],
    parent_by_id: dict[str, str] | None = None,
    members_by_province: list[str] | None = None,
) -> dict:
    rows, cols = len(grid), len(grid[0])
    seen: set[tuple[int, int]] = set()
    components_by_id: dict[str, list[dict]] = defaultdict(list)

    for row in range(rows):
        for col in range(cols):
            province_index = grid[row][col]
            if province_index < 0 or (row, col) in seen:
                continue
            if province_index >= len(labels_by_province):
                raise ValueError(f"owner province index out of range: {province_index}")
            label = labels_by_province[province_index]
            stack = [(row, col)]
            seen.add((row, col))
            cell_count = 0
            touches_outside = False
            surrounding_ids: set[str] = set()
            member_ids: set[str] = set()

            while stack:
                current_row, current_col = stack.pop()
                cell_count += 1
                if members_by_province is not None:
                    member_ids.add(members_by_province[grid[current_row][current_col]])
                for delta_row, delta_col in ((-1, 0), (1, 0), (0, -1), (0, 1)):
                    neighbor_row = current_row + delta_row
                    neighbor_col = current_col + delta_col
                    if not (0 <= neighbor_row < rows and 0 <= neighbor_col < cols):
                        touches_outside = True
                        continue
                    neighbor_province = grid[neighbor_row][neighbor_col]
                    if neighbor_province < 0:
                        touches_outside = True
                        continue
                    if neighbor_province >= len(labels_by_province):
                        raise ValueError(f"owner province index out of range: {neighbor_province}")
                    neighbor_label = labels_by_province[neighbor_province]
                    if neighbor_label == label:
                        coordinate = (neighbor_row, neighbor_col)
                        if coordinate not in seen:
                            seen.add(coordinate)
                            stack.append(coordinate)
                    else:
                        surrounding_ids.add(neighbor_label)

            components_by_id[label].append({
                "cellCount": cell_count,
                "memberIds": sorted(member_ids),
                "touchesOutside": touches_outside,
                "surroundingIds": sorted(surrounding_ids),
            })

    disconnected: list[dict] = []
    fully_enclosed: list[dict] = []
    for record_id in sorted(components_by_id):
        components = sorted(
            components_by_id[record_id],
            key=lambda row: (-row["cellCount"], row["surroundingIds"]),
        )
        if len(components) > 1:
            disconnected.append({
                "id": record_id,
                "displayName": names_by_id[record_id],
                "componentCellCounts": [row["cellCount"] for row in components],
                "components": [
                    {"cellCount": row["cellCount"], "memberIds": row["memberIds"]}
                    for row in components
                ],
            })
        for component in components:
            if not component["touchesOutside"] and len(component["surroundingIds"]) == 1:
                surrounding_id = component["surroundingIds"][0]
                row = {
                    "id": record_id,
                    "displayName": names_by_id[record_id],
                    "componentCellCount": component["cellCount"],
                    "surroundingIds": [surrounding_id],
                    "surroundingDisplayNames": [names_by_id[surrounding_id]],
                }
                if parent_by_id is not None:
                    row.update({
                        "commanderyId": parent_by_id[record_id],
                        "surroundingCommanderyId": parent_by_id[surrounding_id],
                        "sameCommandery": parent_by_id[record_id] == parent_by_id[surrounding_id],
                    })
                fully_enclosed.append(row)

    fully_enclosed.sort(
        key=lambda row: (row["id"], -row["componentCellCount"], row["surroundingIds"])
    )
    return {
        "disconnectedCount": len(disconnected),
        "disconnected": disconnected,
        "fullyEnclosedCount": len(fully_enclosed),
        "fullyEnclosed": fully_enclosed,
    }


def _binding_index(bindings_document: dict) -> dict[tuple[int, str, int], dict]:
    records = _require_records(bindings_document, "administrativeUnits")
    result: dict[tuple[int, str, int], dict] = {}
    for index, record in enumerate(records):
        identity = record.get("identity")
        if not isinstance(identity, dict):
            raise ValueError(f"administrativeUnits[{index}].identity must be an object")
        key = (
            identity.get("sourceVolume"),
            identity.get("canonicalGroup"),
            identity.get("ordinal"),
        )
        if type(key[0]) is not int or not isinstance(key[1], str) or type(key[2]) is not int:
            raise ValueError(f"administrativeUnits[{index}] has an invalid identity")
        if key in result:
            raise ValueError(f"duplicate administrative binding identity: {key}")
        result[key] = record
    return result


def _single_commandery_inventory(
    commanderies: list[dict],
    units_document: dict,
    bindings_document: dict,
    jurisdiction_by_id: dict[str, dict],
) -> list[dict]:
    groups = _require_records(units_document, "groups")
    groups_by_name: dict[str, dict] = {}
    for group in groups:
        name = group.get("canonicalGroup")
        if isinstance(name, str):
            if name in groups_by_name:
                raise ValueError(f"duplicate administrative source group: {name}")
            groups_by_name[name] = group
    bindings = _binding_index(bindings_document)

    result: list[dict] = []
    for commandery in sorted(commanderies, key=lambda row: row["id"]):
        jurisdiction_ids = commandery.get("jurisdictionIds")
        if not isinstance(jurisdiction_ids, list):
            raise ValueError(f"commandery {commandery.get('id')} jurisdictionIds must be an array")
        if len(jurisdiction_ids) != 1:
            continue
        row = {
            "id": commandery["id"],
            "displayName": commandery["displayName"],
            "nameCh": commandery["nameCh"],
            "kind": commandery["kind"],
            "jurisdictionId": jurisdiction_ids[0],
            "sourceGroupMatch": False,
        }
        source_group = groups_by_name.get(commandery["nameCh"])
        if source_group is not None:
            source_units = source_group.get("units")
            if not isinstance(source_units, list):
                raise ValueError(f"source group {commandery['nameCh']} units must be an array")
            status_counts: Counter[str] = Counter()
            without_coordinate: list[str] = []
            resolved_physical_places: list[str] = []
            source_names: list[str] = []
            source_unit_rows: list[dict] = []
            for unit in source_units:
                key = (
                    unit.get("sourceVolume"),
                    unit.get("canonicalGroup"),
                    unit.get("ordinal"),
                )
                binding = bindings.get(key)
                if binding is None:
                    raise ValueError(f"missing administrative binding for {key}")
                status = binding.get("joinStatus")
                if not isinstance(status, str):
                    raise ValueError(f"binding {key} joinStatus must be a string")
                status_counts[status] += 1
                source_name = unit.get("sourceName")
                if not isinstance(source_name, str):
                    raise ValueError(f"source unit {key} sourceName must be a string")
                source_names.append(source_name)
                if status == "NO_COORDINATE_CANDIDATE":
                    without_coordinate.append(source_name)
                selected = binding.get("selectedCandidate")
                physical_place_id: str | None = None
                if isinstance(selected, dict):
                    selected_physical_place_id = selected.get("physicalPlaceId")
                    if isinstance(selected_physical_place_id, str):
                        physical_place_id = selected_physical_place_id
                        resolved_physical_places.append(physical_place_id)
                current_jurisdiction_id = (
                    physical_place_id.rsplit(":", 1)[-1]
                    if isinstance(physical_place_id, str)
                    else None
                )
                current_jurisdiction = jurisdiction_by_id.get(current_jurisdiction_id)
                source_unit_row = {
                    "sourceName": source_name,
                    "ordinal": unit.get("ordinal"),
                    "joinStatus": status,
                }
                if physical_place_id is not None:
                    source_unit_row["physicalPlaceId"] = physical_place_id
                if current_jurisdiction is not None:
                    source_unit_row.update({
                        "currentJurisdictionId": current_jurisdiction_id,
                        "currentCommanderyId": current_jurisdiction["commanderyId"],
                    })
                source_unit_rows.append(source_unit_row)
            row.update({
                "sourceGroupMatch": True,
                "sourceVolume": source_group.get("sourceVolume"),
                "sourceCitation": source_group.get("sourceCitation"),
                "traditionalTextCitation": source_group.get("traditionalTextCitation"),
                "sourceEnumeratedUnitCount": len(source_units),
                "sourceUnitNames": source_names,
                "sourceUnits": source_unit_rows,
                "sourceBindingStatusCounts": dict(sorted(status_counts.items())),
                "sourceResolvedPhysicalPlaceIds": sorted(resolved_physical_places),
                "sourceUnitsWithoutCoordinateCandidate": without_coordinate,
                "requiresMissingCountyReview": len(source_units) > 1,
            })
        result.append(row)
    return result


def _historical_parent_census(
    units_document: dict,
    bindings_document: dict,
    jurisdiction_by_id: dict[str, dict],
    commandery_by_id: dict[str, dict],
) -> dict:
    groups = _require_records(units_document, "groups")
    bindings = _binding_index(bindings_document)
    binding_status_counts: Counter[str] = Counter()
    mismatch_rows: list[dict] = []
    source_group_rows: list[dict] = []
    source_unit_count = 0
    resolved_current_count = 0
    parent_match_count = 0
    no_coordinate_count = 0
    resolved_place_absent_count = 0

    for group in sorted(
        groups,
        key=lambda row: (row.get("sourceVolume", 0), row.get("canonicalGroup", "")),
    ):
        canonical_group = group.get("canonicalGroup")
        source_volume = group.get("sourceVolume")
        source_units = group.get("units")
        if not isinstance(canonical_group, str) or type(source_volume) is not int:
            raise ValueError("administrative source group identity is invalid")
        if not isinstance(source_units, list) or not all(isinstance(row, dict) for row in source_units):
            raise ValueError(f"source group {canonical_group} units must be an array")
        group_status_counts: Counter[str] = Counter()
        group_current_commandery_ids: set[str] = set()
        group_match_count = 0
        group_mismatch_count = 0
        group_absent_count = 0

        for unit in source_units:
            source_unit_count += 1
            key = (unit.get("sourceVolume"), unit.get("canonicalGroup"), unit.get("ordinal"))
            binding = bindings.get(key)
            if binding is None:
                raise ValueError(f"missing administrative binding for {key}")
            status = binding.get("joinStatus")
            if not isinstance(status, str):
                raise ValueError(f"binding {key} joinStatus must be a string")
            binding_status_counts[status] += 1
            group_status_counts[status] += 1
            if status == "NO_COORDINATE_CANDIDATE":
                no_coordinate_count += 1

            selected = binding.get("selectedCandidate")
            physical_place_id = selected.get("physicalPlaceId") if isinstance(selected, dict) else None
            if not isinstance(physical_place_id, str):
                continue
            jurisdiction_id = physical_place_id.rsplit(":", 1)[-1]
            jurisdiction = jurisdiction_by_id.get(jurisdiction_id)
            if jurisdiction is None:
                resolved_place_absent_count += 1
                group_absent_count += 1
                continue
            commandery = commandery_by_id[jurisdiction["commanderyId"]]
            resolved_current_count += 1
            group_current_commandery_ids.add(commandery["id"])
            if commandery.get("nameCh") == canonical_group:
                parent_match_count += 1
                group_match_count += 1
                continue
            group_mismatch_count += 1
            mismatch_rows.append({
                "sourceName": unit.get("sourceName"),
                "sourceCommanderyNameCh": canonical_group,
                "sourceVolume": source_volume,
                "ordinal": unit.get("ordinal"),
                "currentJurisdictionId": jurisdiction_id,
                "currentJurisdictionDisplayName": jurisdiction.get("displayName"),
                "currentCommanderyId": commandery["id"],
                "currentCommanderyDisplayName": commandery.get("displayName"),
                "currentCommanderyNameCh": commandery.get("nameCh"),
                "classification": "SOURCE_PARENT_MISMATCH_REQUIRES_PERIOD_REVIEW",
            })

        source_group_rows.append({
            "sourceCommanderyNameCh": canonical_group,
            "sourceVolume": source_volume,
            "sourceUnitCount": len(source_units),
            "bindingStatusCounts": dict(sorted(group_status_counts.items())),
            "resolvedCurrentJurisdictionCount": group_match_count + group_mismatch_count,
            "sourceParentMatchCount": group_match_count,
            "sourceParentMismatchCount": group_mismatch_count,
            "resolvedPlaceAbsentCount": group_absent_count,
            "currentCommanderyIds": sorted(group_current_commandery_ids),
        })

    mismatch_rows.sort(
        key=lambda row: (
            row["sourceVolume"],
            row["sourceCommanderyNameCh"],
            row["ordinal"],
            row["currentJurisdictionId"],
        )
    )
    source_group_by_name = {
        row["sourceCommanderyNameCh"]: row for row in source_group_rows
    }
    current_commandery_rows: list[dict] = []
    source_linked_current_count = 0
    for commandery in sorted(commandery_by_id.values(), key=lambda row: row["id"]):
        jurisdiction_ids = commandery.get("jurisdictionIds")
        if not isinstance(jurisdiction_ids, list):
            raise ValueError(f"commandery {commandery.get('id')} jurisdictionIds must be an array")
        source_group = source_group_by_name.get(commandery.get("nameCh"))
        row = {
            "currentCommanderyId": commandery["id"],
            "currentCommanderyDisplayName": commandery.get("displayName"),
            "currentCommanderyNameCh": commandery.get("nameCh"),
            "kind": commandery.get("kind"),
            "currentJurisdictionCount": len(jurisdiction_ids),
            "classification": "CURRENT_ONLY_REQUIRES_PERIOD_OR_EXTERNAL_REVIEW",
        }
        if source_group is not None:
            source_linked_current_count += 1
            row.update({
                "classification": "SOURCE_GROUP_MATCH",
                "sourceVolume": source_group["sourceVolume"],
                "sourceEnumeratedUnitCount": source_group["sourceUnitCount"],
                "sourceResolvedCurrentJurisdictionCount": source_group[
                    "resolvedCurrentJurisdictionCount"
                ],
                "sourceParentMismatchCount": source_group["sourceParentMismatchCount"],
            })
        current_commandery_rows.append(row)
    return {
        "policy": {
            "mismatchClassification": "candidate only; period change or data error must be source-reviewed",
            "noAutomaticReparenting": True,
        },
        "sourceGroupCount": len(source_group_rows),
        "sourceUnitCount": source_unit_count,
        "bindingStatusCounts": dict(sorted(binding_status_counts.items())),
        "resolvedCurrentJurisdictionCount": resolved_current_count,
        "sourceParentMatchCount": parent_match_count,
        "sourceParentMismatchCount": len(mismatch_rows),
        "noCoordinateCandidateCount": no_coordinate_count,
        "resolvedPlaceAbsentCount": resolved_place_absent_count,
        "sourceGroups": source_group_rows,
        "sourceParentMismatches": mismatch_rows,
        "currentCommanderyCount": len(current_commandery_rows),
        "sourceLinkedCurrentCommanderyCount": source_linked_current_count,
        "currentOnlyCommanderyCount": len(current_commandery_rows) - source_linked_current_count,
        "currentCommanderies": current_commandery_rows,
    }


def _external_region_hierarchy(document: dict, policy_document: dict) -> dict:
    jurisdictions = _require_records(document, "jurisdictionRecords")
    macro_regions = _require_records(policy_document, "macroRegions")
    constraints = policy_document.get("constraints")
    if not isinstance(constraints, dict):
        raise ValueError("external region policy constraints must be an object")
    if constraints.get("politicalOwnershipSeparate") is not True:
        raise ValueError("external region policy must keep political ownership separate")
    if constraints.get("recruitmentEligibilitySeparate") is not True:
        raise ValueError("external region policy must keep recruitment eligibility separate")

    external_ids = {
        row["id"]
        for row in jurisdictions
        if row.get("kind") in {"EXTERNAL_PLACE", "EXTERNAL_SETTLEMENT"}
    }
    assigned: dict[str, str] = {}
    region_rows: list[dict] = []
    for region in macro_regions:
        region_id = region.get("id")
        display_name = region.get("displayName")
        jurisdiction_ids = region.get("jurisdictionIds")
        if not isinstance(region_id, str) or not isinstance(display_name, str):
            raise ValueError("external macro region identity is invalid")
        if not isinstance(jurisdiction_ids, list) or not all(
            isinstance(value, str) for value in jurisdiction_ids
        ):
            raise ValueError(f"external macro region {region_id} jurisdictionIds must be strings")
        if (
            constraints.get("noPanEthnicKoreanPeninsulaUmbrella") is True
            and region.get("geographicScope") == "KOREAN_PENINSULA"
            and display_name in {"동이", "東夷"}
        ):
            raise ValueError("동이 같은 범칭을 한반도 상위 지도명으로 사용할 수 없다")
        if (
            constraints.get("noDirectionalUmbrellaForNamedNorthernOrWesternPeoples") is True
            and region.get("geographicScope")
            in {"NORTHERN_FRONTIER", "NORTHWEST_FRONTIER"}
            and display_name in {"북방", "서방", "서역"}
        ):
            raise ValueError(
                f"{display_name} 방향 범칭 대신 강·저·흉노·선비·오환 같은 실명 집단을 사용해야 한다"
            )
        for jurisdiction_id in jurisdiction_ids:
            if jurisdiction_id in assigned:
                raise ValueError(
                    f"external jurisdiction {jurisdiction_id} appears in multiple macro regions"
                )
            assigned[jurisdiction_id] = region_id
        region_rows.append({
            "id": region_id,
            "displayName": display_name,
            "classification": region.get("classification"),
            "geographicScope": region.get("geographicScope"),
            "jurisdictionIds": jurisdiction_ids,
        })

    unknown = sorted(set(assigned) - external_ids)
    uncovered = sorted(external_ids - set(assigned))
    if unknown:
        raise ValueError(f"external region policy references unknown jurisdictions: {unknown}")
    if uncovered:
        raise ValueError(f"external region policy leaves jurisdictions uncovered: {uncovered}")
    candidates = policy_document.get("sourceBackedCandidates")
    if not isinstance(candidates, list) or not all(isinstance(row, dict) for row in candidates):
        raise ValueError("sourceBackedCandidates must be an array of objects")
    return {
        "policyId": policy_document.get("policyId"),
        "referenceYear": policy_document.get("referenceYear"),
        "politicalOwnershipSeparate": True,
        "recruitmentEligibilitySeparate": True,
        "externalJurisdictionCount": len(external_ids),
        "coveredJurisdictionCount": len(assigned),
        "uncoveredJurisdictionIds": uncovered,
        "macroRegionCount": len(region_rows),
        "macroRegions": region_rows,
        "sourceBackedCandidateCount": len(candidates),
        "sourceBackedCandidates": candidates,
    }


def _jurisdiction_name_audit(
    jurisdictions: list[dict],
    commandery_by_id: dict[str, dict],
) -> dict:
    annotations: list[dict] = []
    repeated_suffixes: list[dict] = []
    by_parent_and_name: dict[tuple[str, str], list[dict]] = defaultdict(list)
    annotation_pattern = re.compile(r"[\[\]（）()【】]")
    repeated_suffix_pattern = re.compile(r"(?:현현|군군|국국)$")

    for jurisdiction in jurisdictions:
        jurisdiction_id = jurisdiction["id"]
        display_name = jurisdiction.get("displayName")
        name_ch = jurisdiction.get("nameCh")
        commandery_id = jurisdiction.get("commanderyId")
        if not isinstance(display_name, str) or not isinstance(name_ch, str):
            raise ValueError(f"jurisdiction {jurisdiction_id} names must be strings")
        if annotation_pattern.search(display_name) or annotation_pattern.search(name_ch):
            annotations.append({
                "jurisdictionId": jurisdiction_id,
                "displayName": display_name,
                "nameCh": name_ch,
                "commanderyId": commandery_id,
            })
        if repeated_suffix_pattern.search(display_name) or re.search(r"(?:县县|郡郡|國國|国国)$", name_ch):
            repeated_suffixes.append({
                "jurisdictionId": jurisdiction_id,
                "displayName": display_name,
                "nameCh": name_ch,
                "commanderyId": commandery_id,
            })
        by_parent_and_name[(commandery_id, name_ch)].append(jurisdiction)

    duplicates: list[dict] = []
    for (commandery_id, name_ch), rows in sorted(by_parent_and_name.items()):
        if len(rows) < 2:
            continue
        commandery = commandery_by_id[commandery_id]
        duplicates.append({
            "commanderyId": commandery_id,
            "commanderyDisplayName": commandery.get("displayName"),
            "nameCh": name_ch,
            "displayNames": sorted({row["displayName"] for row in rows}),
            "jurisdictionIds": sorted(row["id"] for row in rows),
            "classification": "SAME_COMMANDERY_NAME_COLLISION_REQUIRES_REVIEW",
        })

    annotations.sort(key=lambda row: row["jurisdictionId"])
    repeated_suffixes.sort(key=lambda row: row["jurisdictionId"])
    return {
        "sourceAnnotationCount": len(annotations),
        "sourceAnnotations": annotations,
        "repeatedAdministrativeSuffixCount": len(repeated_suffixes),
        "repeatedAdministrativeSuffixes": repeated_suffixes,
        "sameCommanderyCanonicalNameDuplicateCount": len(duplicates),
        "sameCommanderyCanonicalNameDuplicates": duplicates,
    }


def validate_ailao_layers(document: dict) -> dict:
    cities = _require_records(document, "cities")
    jurisdictions = _require_records(document, "jurisdictionRecords")
    commanderies = _require_records(document, "commanderyRecords")
    city_by_id = {row.get("id"): row for row in cities}
    jurisdiction_by_id = {row.get("id"): row for row in jurisdictions}
    commandery_by_id = {row.get("id"): row for row in commanderies}
    ethnic = city_by_id.get("X060")
    county = city_by_id.get("80004")
    ethnic_jurisdiction = jurisdiction_by_id.get("X060")
    county_jurisdiction = jurisdiction_by_id.get("80004")
    if (
        not isinstance(ethnic, dict)
        or ethnic.get("nameCh") != "哀牢"
        or ethnic.get("kind") != "EXTERNAL_PLACE"
        or not isinstance(county, dict)
        or county.get("nameCh") != "哀牢县"
        or county.get("kind") != "COUNTY"
        or not isinstance(ethnic_jurisdiction, dict)
        or ethnic_jurisdiction.get("kind") not in {"EXTERNAL_PLACE", "EXTERNAL_SETTLEMENT"}
        or not isinstance(county_jurisdiction, dict)
        or county_jurisdiction.get("kind") != "COUNTY"
        or ethnic_jurisdiction.get("commanderyId") not in commandery_by_id
        or county_jurisdiction.get("commanderyId") not in commandery_by_id
        or ethnic_jurisdiction.get("commanderyId") == county_jurisdiction.get("commanderyId")
    ):
        raise ValueError("哀牢 ethnic region and 哀牢县 administrative county must remain distinct")
    return {
        "ethnicRegionId": "X060",
        "ethnicNameCh": "哀牢",
        "administrativeCountyId": "80004",
        "administrativeCountyNameCh": "哀牢县",
        "decision": "SAME_LINEAGE_DO_NOT_RENDER_AS_PEERS",
        "recommendedMacroRegionDisplayName": "남만",
        "recommendedJurisdictionDisplayName": "애뢰",
        "politicalOwnershipSeparate": True,
        "recruitmentEligibilitySeparate": True,
        "evidence": {
            "source": "後漢書 卷86 南蠻西南夷列傳",
            "corpusPath": "corpus/hhs-086.txt",
            "line": 84,
            "summary": "永平十二年 哀牢王內屬後 그 땅에 哀牢·博南 두 현을 두고 永昌郡을 설치했다.",
        },
    }


def audit_document(
    document: dict,
    units_document: dict,
    bindings_document: dict,
    external_policy_document: dict | None = None,
) -> dict:
    rows, cols, grid = _decode_owner(document)
    provinces = _require_records(document, "provinceRecords")
    jurisdictions = _require_records(document, "jurisdictionRecords")
    commanderies = _require_records(document, "commanderyRecords")
    province_by_id = {row["id"]: row for row in provinces}
    jurisdiction_by_id = {row["id"]: row for row in jurisdictions}
    commandery_by_id = {row["id"]: row for row in commanderies}
    if len(province_by_id) != len(provinces):
        raise ValueError("provinceRecords contains duplicate IDs")
    if len(jurisdiction_by_id) != len(jurisdictions):
        raise ValueError("jurisdictionRecords contains duplicate IDs")
    if len(commandery_by_id) != len(commanderies):
        raise ValueError("commanderyRecords contains duplicate IDs")

    jurisdiction_labels: list[str] = []
    commandery_labels: list[str] = []
    for province in provinces:
        jurisdiction_id = province.get("jurisdictionId")
        jurisdiction = jurisdiction_by_id.get(jurisdiction_id)
        if jurisdiction is None:
            raise ValueError(f"province {province.get('id')} has unknown jurisdictionId")
        commandery_id = jurisdiction.get("commanderyId")
        if commandery_id not in commandery_by_id:
            raise ValueError(f"jurisdiction {jurisdiction_id} has unknown commanderyId")
        jurisdiction_labels.append(jurisdiction_id)
        commandery_labels.append(commandery_id)

    city_ids = {row.get("id") for row in _require_records(document, "cities")}
    jurisdiction_ids = {row.get("id") for row in jurisdictions}
    ailao_ids_present = {"X060", "80004"} & (city_ids | jurisdiction_ids)
    ailao_lineage: dict | None = None
    if ailao_ids_present:
        ailao_lineage = validate_ailao_layers(document)
    province_topology = _component_inventory(
        grid,
        [row["id"] for row in provinces],
        {row["id"]: row["displayName"] for row in provinces},
        members_by_province=[row["id"] for row in provinces],
    )
    province_cell_counts = Counter(
        province_index
        for grid_row in grid
        for province_index in grid_row
        if province_index >= 0
    )
    below_minimum = [
        {
            "id": province["id"],
            "displayName": province["displayName"],
            "cellCount": province_cell_counts[index],
        }
        for index, province in enumerate(provinces)
        if province_cell_counts[index] < 8
    ]
    province_topology = {
        "minimumCellCount": 8,
        "belowMinimumCount": len(below_minimum),
        "belowMinimum": below_minimum,
        "disconnectedCount": province_topology["disconnectedCount"],
        "disconnected": province_topology["disconnected"],
        "fullyEnclosedCount": province_topology["fullyEnclosedCount"],
        "fullyEnclosed": province_topology["fullyEnclosed"],
    }
    jurisdiction_topology = _component_inventory(
        grid,
        jurisdiction_labels,
        {row["id"]: row["displayName"] for row in jurisdictions},
        {row["id"]: row["commanderyId"] for row in jurisdictions},
        [row["id"] for row in provinces],
    )
    commandery_topology = _component_inventory(
        grid,
        commandery_labels,
        {row["id"]: row["displayName"] for row in commanderies},
        members_by_province=jurisdiction_labels,
    )
    single_commanderies = _single_commandery_inventory(
        commanderies, units_document, bindings_document, jurisdiction_by_id
    )
    historical_parent_census = _historical_parent_census(
        units_document,
        bindings_document,
        jurisdiction_by_id,
        commandery_by_id,
    )
    jurisdiction_name_audit = _jurisdiction_name_audit(jurisdictions, commandery_by_id)
    return {
        "schemaVersion": 1,
        "auditId": "han-administrative-topology-audit-v1",
        "referenceYear": document.get("_meta", {}).get("year"),
        "grid": {"rows": rows, "cols": cols, "connectivity": "FOUR_NEIGHBOR"},
        "policy": {
            "candidateOnly": True,
            "disconnected": "same administrative ID has more than one four-neighbor cell component",
            "fullyEnclosed": "component touches neither outside nor negative owner and has exactly one surrounding administrative ID",
            "singleJurisdiction": "commanderyRecords.jurisdictionIds contains exactly one ID",
            "noAutomaticRepair": True,
        },
        "counts": {
            "province": len(provinces),
            "jurisdiction": len(jurisdictions),
            "commandery": len(commanderies),
        },
        "provinceTopology": province_topology,
        "jurisdictionTopology": jurisdiction_topology,
        "commanderyTopology": commandery_topology,
        "singleJurisdictionCommanderyCount": len(single_commanderies),
        "singleJurisdictionCommanderies": single_commanderies,
        "historicalParentCensus": historical_parent_census,
        "jurisdictionNameAudit": jurisdiction_name_audit,
        "externalRegionHierarchy": (
            _external_region_hierarchy(document, external_policy_document)
            if external_policy_document is not None
            else {
                "externalJurisdictionCount": 0,
                "coveredJurisdictionCount": 0,
                "uncoveredJurisdictionIds": [],
                "macroRegionCount": 0,
                "macroRegions": [],
                "sourceBackedCandidateCount": 0,
                "sourceBackedCandidates": [],
            }
        ),
        "ethnicAdministrativeCoexistence": [ailao_lineage] if ailao_lineage else [],
    }


def _load(path: Path) -> dict:
    document = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(document, dict):
        raise ValueError(f"{path} must contain a JSON object")
    return document


def _render(document: dict) -> str:
    return json.dumps(document, ensure_ascii=False, indent=2, sort_keys=False) + "\n"


def materialize(
    tiles: Path,
    units: Path,
    bindings: Path,
    external_policy: Path | None = None,
) -> dict:
    result = audit_document(
        _load(tiles),
        _load(units),
        _load(bindings),
        _load(external_policy) if external_policy is not None else None,
    )
    result["inputs"] = {
        "hanTiles": {"sha256": _sha256(tiles)},
        "administrativeUnits": {"sha256": _sha256(units)},
        "administrativePlaceBindings": {"sha256": _sha256(bindings)},
    }
    if external_policy is not None:
        result["inputs"]["externalRegionHierarchyPolicy"] = {
            "sha256": _sha256(external_policy)
        }
    return result


def parse_args(argv: Iterable[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tiles", type=Path, default=DEFAULT_TILES)
    parser.add_argument("--units", type=Path, default=DEFAULT_UNITS)
    parser.add_argument("--bindings", type=Path, default=DEFAULT_BINDINGS)
    parser.add_argument("--external-policy", type=Path, default=DEFAULT_EXTERNAL_POLICY)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--check", action="store_true")
    return parser.parse_args(argv)


def main(argv: Iterable[str] | None = None) -> int:
    args = parse_args(argv)
    try:
        result = materialize(
            args.tiles.resolve(),
            args.units.resolve(),
            args.bindings.resolve(),
            args.external_policy.resolve(),
        )
        rendered = _render(result)
        if args.check:
            if not args.output.exists() or args.output.read_text(encoding="utf-8") != rendered:
                raise ValueError(f"audit snapshot drift: regenerate {args.output}")
        else:
            args.output.parent.mkdir(parents=True, exist_ok=True)
            args.output.write_text(rendered, encoding="utf-8")
    except (OSError, ValueError, json.JSONDecodeError) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
