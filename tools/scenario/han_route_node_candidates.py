from __future__ import annotations

import hashlib
import json
from collections import Counter, defaultdict
from dataclasses import dataclass
from typing import TypeAlias

JsonValue: TypeAlias = str | int | float | bool | None | list["JsonValue"] | dict[str, "JsonValue"]
JsonObject: TypeAlias = dict[str, JsonValue]
LegacyNode: TypeAlias = tuple[int, JsonObject, str, bool]
FIXED_YEAR = 220
CLASSIFICATIONS = (
    "HHS_RESOLVED", "HHS_AMBIGUOUS", "HHS_UNMAPPED",
    "HHS_ATTRIBUTION_CONFLICT", "EXTERNAL_OR_LATER_OR_MOVING",
)
JOIN_STATUSES = {"RESOLVED_POINT", "AMBIGUOUS_POINT", "NO_COORDINATE_CANDIDATE", "SOURCE_PLACEHOLDER"}
X026_ADMINISTRATIVE_UNIT_ID = "hhs:113:上郡:009"


@dataclass(frozen=True, slots=True)
class CandidateContractError(ValueError):
    message: str

    def __str__(self) -> str:
        return self.message


def required_dict(container: JsonObject, key: str) -> JsonObject:
    value = container.get(key)
    if not isinstance(value, dict):
        raise CandidateContractError(f"{key} must be an object")
    return value


def required_list(container: JsonObject, key: str) -> list[JsonValue]:
    value = container.get(key)
    if not isinstance(value, list):
        raise CandidateContractError(f"{key} must be an array")
    return value


def administrative_unit_id(unit: JsonObject) -> str:
    try:
        return f'hhs:{int(unit["sourceVolume"])}:{unit["canonicalGroup"]}:{int(unit["ordinal"]):03d}'
    except (KeyError, TypeError, ValueError) as error:
        raise CandidateContractError("malformed administrative unit identity") from error


def _catalog_index(
    catalog: JsonObject,
) -> tuple[dict[str, str], dict[str, set[str]], dict[str, str]]:
    groups = required_list(catalog, "groups")
    if catalog.get("catalogId") is None:
        raise CandidateContractError("catalogId is required")
    canonical: dict[str, str] = {}
    aliases: dict[str, set[str]] = defaultdict(set)
    units: dict[str, str] = {}
    for raw_group in groups:
        if not isinstance(raw_group, dict):
            raise CandidateContractError("catalog group must be an object")
        name, alias = raw_group.get("canonicalGroup"), raw_group.get("sourceGroupName")
        if not isinstance(name, str) or not name or not isinstance(alias, str) or not alias:
            raise CandidateContractError("catalog group names must be non-empty strings")
        if name in canonical:
            raise CandidateContractError(f"duplicate canonical group: {name}")
        canonical[name] = name
        aliases[alias].add(name)
        for raw_unit in required_list(raw_group, "units"):
            if not isinstance(raw_unit, dict) or raw_unit.get("canonicalGroup") != name:
                raise CandidateContractError(f"malformed unit in canonical group: {name}")
            unit_id = administrative_unit_id(raw_unit)
            if unit_id in units:
                raise CandidateContractError(f"duplicate administrative unit id: {unit_id}")
            units[unit_id] = name
    expected = (catalog.get("expectedGroupCount"), catalog.get("expectedUnitCount"))
    detected = (catalog.get("detectedGroupCount"), catalog.get("detectedUnitCount"))
    actual = (len(groups), len(units))
    if expected != actual or detected != actual:
        raise CandidateContractError("catalog counts do not match enumerated rows")
    return canonical, aliases, units


def physical_references(row: JsonObject) -> list[str]:
    status = row.get("joinStatus")
    if status not in JOIN_STATUSES:
        raise CandidateContractError(f"invalid joinStatus: {status}")
    if status == "RESOLVED_POINT":
        candidates: list[JsonValue] = [required_dict(row, "selectedCandidate")]
    elif status == "AMBIGUOUS_POINT":
        candidates = required_list(row, "candidates")
    else:
        candidates = []
    refs: list[str] = []
    for candidate in candidates:
        if not isinstance(candidate, dict):
            raise CandidateContractError("overlay candidate must be an object")
        physical_id = candidate.get("physicalPlaceId")
        if not isinstance(physical_id, str) or not physical_id.startswith("chgis:v6:cnty:"):
            raise CandidateContractError("invalid CHGIS physicalPlaceId")
        refs.append(physical_id)
    if len(refs) != len(set(refs)) or row.get("candidateCount") != len(refs):
        raise CandidateContractError("overlay candidate count or identity is malformed")
    return refs


def _overlay_index(
    overlay: JsonObject,
    catalog_id: str,
    catalog_units: dict[str, str],
) -> tuple[dict[str, JsonObject], dict[str, list[str]]]:
    if overlay.get("catalogId") != catalog_id or overlay.get("sourceYear") != FIXED_YEAR:
        raise CandidateContractError("overlay catalog or fixed source year does not match")
    rows: dict[str, JsonObject] = {}
    reverse: dict[str, list[str]] = defaultdict(list)
    for raw_row in required_list(overlay, "administrativeUnits"):
        if not isinstance(raw_row, dict):
            raise CandidateContractError("overlay row must be an object")
        unit_id = raw_row.get("administrativeUnitId")
        if not isinstance(unit_id, str) or unit_id not in catalog_units:
            raise CandidateContractError(f"unknown overlay administrative unit: {unit_id}")
        if unit_id in rows:
            raise CandidateContractError(f"duplicate overlay administrative unit: {unit_id}")
        if required_dict(raw_row, "identity").get("canonicalGroup") != catalog_units[unit_id]:
            raise CandidateContractError(f"overlay identity mismatch: {unit_id}")
        rows[unit_id] = raw_row
        for physical_id in physical_references(raw_row):
            reverse[physical_id].append(unit_id)
    if set(rows) != set(catalog_units):
        raise CandidateContractError("overlay must cover every catalog administrative unit exactly once")
    return rows, {key: sorted(value) for key, value in reverse.items()}


def _owner_group(owner: str, canonical: dict[str, str], aliases: dict[str, set[str]]) -> tuple[str | None, str]:
    if owner in canonical:
        return owner, "CANONICAL_EXACT"
    targets = aliases.get(owner, set())
    if len(targets) == 1:
        return next(iter(targets)), "SOURCE_GROUP_ALIAS"
    return None, "UNRESOLVED"


def _fingerprint(legacy_id: int, tile_id: str, name_ch: str, owner: str, is_seat: bool) -> str:
    payload = json.dumps(
        [legacy_id, tile_id, name_ch, owner, is_seat], ensure_ascii=False, separators=(",", ":"),
    ).encode()
    return "sha256:" + hashlib.sha256(payload).hexdigest()


def _numeric_candidate(
    base: JsonObject,
    physical_id: str,
    owner: str,
    canonical: dict[str, str],
    aliases: dict[str, set[str]],
    catalog_units: dict[str, str],
    overlay_rows: dict[str, JsonObject],
    reverse: dict[str, list[str]],
) -> tuple[JsonObject, bool]:
    owner_group, resolution = _owner_group(owner, canonical, aliases)
    physical_units = reverse.get(physical_id, [])
    compatible = [unit_id for unit_id in physical_units if catalog_units[unit_id] == owner_group]
    base.update({
        "physicalPlaceRef": physical_id,
        "ownerGroupResolution": resolution,
        "physicalPlaceAdministrativeUnitCount": len(physical_units),
    })
    if len(compatible) == 1 and len(physical_units) == 1:
        unit_id = compatible[0]
        if overlay_rows[unit_id]["joinStatus"] == "RESOLVED_POINT":
            base.update({"classification": "HHS_RESOLVED", "proposedAdministrativeUnitId": unit_id})
        else:
            base.update({"classification": "HHS_AMBIGUOUS", "candidateAdministrativeUnitIds": compatible})
    elif compatible:
        base.update({"classification": "HHS_AMBIGUOUS", "candidateAdministrativeUnitIds": compatible})
    elif physical_units:
        base.update({
            "classification": "HHS_ATTRIBUTION_CONFLICT",
            "incompatibleAdministrativeUnitIds": physical_units,
        })
    else:
        base["classification"] = "HHS_UNMAPPED"
    return base, len(compatible) == 1


def _replacement_pool(catalog: JsonObject, overlay_rows: dict[str, JsonObject]) -> list[JsonObject]:
    pool: list[JsonObject] = []
    for raw_group in required_list(catalog, "groups"):
        if not isinstance(raw_group, dict):
            raise CandidateContractError("catalog group must be an object")
        for raw_unit in required_list(raw_group, "units"):
            if not isinstance(raw_unit, dict):
                raise CandidateContractError("catalog unit must be an object")
            unit_id = administrative_unit_id(raw_unit)
            overlay_row = overlay_rows[unit_id]
            row: JsonObject = {
                "candidateKey": f"replacement:{unit_id}", "origin": "HHS_REPLACEMENT_POOL",
                "reviewState": "PENDING", "administrativeUnitId": unit_id,
                "canonicalGroup": raw_unit["canonicalGroup"], "sourceName": raw_unit["sourceName"],
                "sourceNameStatus": raw_unit["sourceNameStatus"], "unitType": raw_unit["unitType"],
                "overlayJoinStatus": overlay_row["joinStatus"],
            }
            refs = physical_references(overlay_row)
            if refs:
                row["physicalPlaceRefs"] = refs
            pool.append(row)
    return pool


def build_candidates(
    catalog: JsonObject,
    overlay: JsonObject,
    legacy_nodes: list[LegacyNode],
    scenario_catalog: list[JsonObject] | None = None,
) -> JsonObject:
    canonical, aliases, catalog_units = _catalog_index(catalog)
    overlay_rows, reverse = _overlay_index(overlay, str(catalog["catalogId"]), catalog_units)
    current: list[JsonObject] = []
    unique_compatible = numeric_count = 0
    for legacy_id, tile_city, owner, is_seat in legacy_nodes:
        tile_id, name_ch = str(tile_city["id"]), str(tile_city["nameCh"])
        fingerprint = _fingerprint(legacy_id, tile_id, name_ch, owner, is_seat)
        base: JsonObject = {
            "candidateKey": f"current:{fingerprint}", "origin": "CURRENT_780",
            "legacyCityId": legacy_id, "legacyNodeFingerprint": fingerprint,
            "legacyTileId": tile_id, "legacyNameCh": name_ch, "legacyOwnerGroup": owner,
            "legacyIsSeat": is_seat, "reviewState": "PENDING",
        }
        if tile_id.isdecimal():
            numeric_count += 1
            base, uniquely_compatible = _numeric_candidate(
                base, f"chgis:v6:cnty:{tile_id}", owner, canonical, aliases,
                catalog_units, overlay_rows, reverse,
            )
            unique_compatible += uniquely_compatible
        elif tile_id.startswith("X") and tile_id[1:].isdecimal():
            base.update({
                "classification": "EXTERNAL_OR_LATER_OR_MOVING",
                "externalPlaceRef": f"han-tiles:{tile_id}",
            })
            if tile_id == "X026":
                if X026_ADMINISTRATIVE_UNIT_ID not in catalog_units:
                    raise CandidateContractError("X026 correction administrative unit is missing")
                base["correctionCandidate"] = {
                    "kind": "HISTORICAL_BINDING_CORRECTION",
                    "administrativeUnitId": X026_ADMINISTRATIVE_UNIT_ID,
                    "correctedName": "龜茲", "correctedParent": "上郡", "reviewState": "PENDING",
                }
        else:
            raise CandidateContractError(f"unsupported legacy tile id: {tile_id}")
        current.append(base)
    candidates = current + _replacement_pool(catalog, overlay_rows)
    keys = [str(row["candidateKey"]) for row in candidates]
    if len(keys) != len(set(keys)):
        raise CandidateContractError("duplicate candidateKey")
    counts = Counter(str(row["classification"]) for row in current)
    return {
        "schemaVersion": 1, "selectionId": "han-route-node-selection-candidates-v1",
        "fixedYear": FIXED_YEAR,
        "candidatePolicy": {
            "reviewState": "PENDING",
            "ownerAgreement": "canonicalGroup exact, then unique sourceGroupName alias",
            "numericPhysicalPlaceBinding": "chgis:v6:cnty:<legacyTileId>",
            "automaticSelectionCount": 0,
        },
        "summary": {
            "candidateCount": len(candidates), "legacyNodeCount": len(current),
            "replacementPoolCount": len(candidates) - len(current),
            "originCounts": {"CURRENT_780": len(current), "HHS_REPLACEMENT_POOL": len(candidates) - len(current)},
            "numericPhysicalPlaceCount": numeric_count, "externalTileCount": len(current) - numeric_count,
            "uniqueCompatibleAdministrativeUnitCount": unique_compatible,
            "reviewStateCounts": {"PENDING": len(candidates)},
            "classificationCounts": {name: counts[name] for name in CLASSIFICATIONS},
            "correctionCandidateCount": sum("correctionCandidate" in row for row in current),
        },
        "scenarioCatalog": scenario_catalog or [], "candidates": candidates,
    }
