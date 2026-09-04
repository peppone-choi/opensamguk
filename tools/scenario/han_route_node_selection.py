from __future__ import annotations

# SIZE_OK: One deterministic assignment transaction shares registry, policy, and migration state.
# noqa: SIZE_OK — splitting the transaction would duplicate fail-closed cross-ledger invariants.

from collections import Counter
from dataclasses import dataclass
import hashlib
import json
from typing import TypeAlias
from uuid import UUID

JsonValue: TypeAlias = str | int | float | bool | None | list["JsonValue"] | dict[str, "JsonValue"]
JsonObject: TypeAlias = dict[str, JsonValue]
NODE_CLASSES = {"COUNTY": "COUNTY_NODE", "DAO": "DAO_NODE", "MARQUISATE": "MARQUISATE_NODE", "TOWN": "TOWN_NODE"}
ALLOWED_NODE_CLASSES = frozenset(NODE_CLASSES.values())
LEGACY_SELECTION_COUNT = 780
EXPECTED_BATCH_COUNTS = {"w0b-overlay-unique-220": 723, "w0c-reviewed-ambiguity": 50, "w0c-hhs-external-location": 8}
EXPECTED_SELECTION = {"routeNodeCount": 781, "hhsAdministrativeBindingCount": 781, "externalHistoricalBindingCount": 0, "overlayUniqueCount": 723, "reviewedAmbiguousCount": 50, "externalLocationClaimCount": 8, "sourcePlaceholderCount": 0, "polityPresenceCount": 0, "remoteGateCount": 0}
EXPECTED_REVIEW_DECISION_ANCHORS: JsonObject = {
    "historicalConflictDecisionSet": {
        "anchor": "historicalConflictDecisionSet:ab4f5ed35a03dfc47070d5dd985845d990cbab77c922480027461912cf44c1c7",
        "assignmentSha256": "ab4f5ed35a03dfc47070d5dd985845d990cbab77c922480027461912cf44c1c7",
        "reviewState": "APPROVED",
        "rowCount": 21,
    },
    "replacementDecisionSet": {
        "anchor": "replacementDecisionSet:639fe3ddf0ecb72d3e70afa5d1693ce0899744f261b2b64bbbf6177a38595ac8",
        "assignmentSha256": "639fe3ddf0ecb72d3e70afa5d1693ce0899744f261b2b64bbbf6177a38595ac8",
        "reviewState": "APPROVED",
        "rowCount": 101,
    },
}
MUTABLE_REFERENCES: list[JsonValue] = ["city.id", "general.city_id", "general.officer_city", "nation.capital_city_id", "v2_city_ledger.city_id", "general_turn.arg", "nation_turn.arg", "general.last_turn", "general.meta.officer_city", "command_inbox.payload"]
IMMUTABLE_AUDIT_REFERENCES: list[JsonValue] = ["command_result.result_payload", "command_outbox.payload", "history", "replay"]
DERIVED_RESEED_REFERENCES: list[JsonValue] = ["scenario.nation.city_ids", "scenario.general.city_id", "HanCityConst", "HanGateIndex", "map.connections"]
EXPECTED_REWRITE_SURFACES: JsonObject = {
    "scenarioResources": {
        "disposition": "RESEED",
        "surfaces": ["scenario.nation.city_ids", "scenario.general.city_id"],
        "otherwise": "BLOCK_UNTIL_TYPED_BINDINGS",
    },
    "derivedArtifacts": {
        "disposition": "REGENERATE",
        "surfaces": ["HanCityConst", "HanGateIndex", "map.connections"],
        "otherwise": "BLOCK_UNTIL_TYPED_BINDINGS",
    },
    "immutableAudit": {
        "disposition": "NO_REWRITE",
        "surfaces": ["command_result.result_payload", "command_outbox.payload", "history", "replay"],
    },
}
EXPECTED_FORBIDDEN_SELECTIONS: JsonObject = {
    "physicalPlaceIds": [
        "external:v1:X004", "external:v1:X028", "external:v1:X029", "external:v1:X030",
        "external:v1:X031", "external:v1:X032", "external:v1:X033", "external:v1:X034",
        "external:v1:X035", "external:v1:X036", "external:v1:X037", "external:v1:X038",
        "external:v1:X040", "external:v1:X041", "external:v1:X042", "external:v1:X043",
        "external:v1:X044", "external:v1:X045", "external:v1:X046", "external:v1:X047",
        "external:v1:X048", "external:v1:X049", "external:v1:X055", "external:v1:X056",
        "external:v1:X057", "external:v1:X058", "external:v1:X059", "external:v1:X060",
        "external:v1:X061", "external:v1:X062", "external:v1:X063", "external:v1:X064",
    ],
    "canonicalNames": [
        "流求", "夷洲", "古寧伽耶", "大伽耶", "星山伽耶", "山越", "白馬氐", "西羌",
        "南匈奴", "烏桓", "鮮卑", "龜茲屬國",
    ],
    "nodeClasses": ["POLITY_PRESENCE", "REMOTE_GATE", "ALIAS_ONLY"],
}


def _decision_digest(rows_to_hash: list[JsonObject]) -> str:
    payload = json.dumps(
        rows_to_hash, ensure_ascii=False, sort_keys=True, separators=(",", ":")
    ) + "\n"
    return hashlib.sha256(payload.encode("utf-8")).hexdigest()
COUNTER_NAMES = ("numericCityIdChangeCount", "routeNodeReplacementCount", "historicalBindingCorrectionCount", "physicalPlaceCorrectionCount", "displayNameChangeCount", "parentChangeCount", "seatRoleChangeCount")


class MaterializationContractError(ValueError):
    __slots__ = ()


@dataclass(frozen=True, slots=True)
class BuildResult:
    selection: JsonObject
    migration: JsonObject


def obj(container: JsonObject, key: str) -> JsonObject:
    if not isinstance(value := container.get(key), dict):
        raise MaterializationContractError(f"{key} must be an object")
    return value


def rows(container: JsonObject, key: str) -> list[JsonObject]:
    if not isinstance(value := container.get(key), list) or not all(isinstance(row, dict) for row in value):
        raise MaterializationContractError(f"{key} must be an object array")
    return [row for row in value if isinstance(row, dict)]


def strings(container: JsonObject, key: str) -> list[str]:
    if not isinstance(value := container.get(key), list) or not all(isinstance(row, str) for row in value):
        raise MaterializationContractError(f"{key} must be a string array")
    return [row for row in value if isinstance(row, str)]


def text(container: JsonObject, key: str) -> str:
    if not isinstance(value := container.get(key), str) or not value:
        raise MaterializationContractError(f"{key} must be a non-empty string")
    return value


def number(container: JsonObject, key: str) -> int:
    if not isinstance(value := container.get(key), int) or isinstance(value, bool):
        raise MaterializationContractError(f"{key} must be an integer")
    return value


def _unit_id(unit: JsonObject) -> str:
    return f"hhs:{number(unit, 'sourceVolume')}:{text(unit, 'canonicalGroup')}:{number(unit, 'ordinal'):03d}"


def _catalog(catalog: JsonObject) -> tuple[list[str], dict[str, JsonObject]]:
    ordered: list[str] = []
    indexed: dict[str, JsonObject] = {}
    for group in rows(catalog, "groups"):
        for unit in rows(group, "units"):
            unit_id = _unit_id(unit)
            unit_type = text(unit, "unitType")
            if unit_type not in NODE_CLASSES:
                raise MaterializationContractError(f"unitType is invalid: {unit_id}")
            if unit_id in indexed:
                raise MaterializationContractError(f"duplicate administrative unit: {unit_id}")
            ordered.append(unit_id)
            indexed[unit_id] = unit
    if len(ordered) != 1180 or catalog.get("detectedUnitCount") != 1180:
        raise MaterializationContractError("catalog must contain exactly 1,180 units")
    return ordered, indexed


def _candidate_index(candidate: JsonObject) -> tuple[dict[int, JsonObject], set[str]]:
    all_rows = rows(candidate, "candidates")
    if candidate.get("candidatePolicy") != {
        "automaticSelectionCount": 0,
        "numericPhysicalPlaceBinding": "chgis:v6:cnty:<legacyTileId>",
        "ownerAgreement": "canonicalGroup exact, then unique sourceGroupName alias",
        "reviewState": "PENDING",
    } or any(row.get("reviewState") != "PENDING" for row in all_rows):
        raise MaterializationContractError("candidate manifest must remain PENDING-only")
    current = [row for row in all_rows if row.get("origin") == "CURRENT_780"]
    pool = {text(row, "administrativeUnitId") for row in all_rows if row.get("origin") == "HHS_REPLACEMENT_POOL"}
    indexed = {number(row, "legacyCityId"): row for row in current}
    if sorted(indexed) != list(range(1, 781)) or len(pool) != 1180:
        raise MaterializationContractError("candidate manifest counts or legacyCityId sequence drifted")
    return indexed, pool


def _reviewed_selection(overlay: dict[str, JsonObject], adjudications: JsonObject, claims: JsonObject,
                        ) -> tuple[dict[str, tuple[str, str]], dict[str, JsonObject]]:
    selected: dict[str, tuple[str, str]] = {}
    for unit_id, row in overlay.items():
        if row.get("joinStatus") == "RESOLVED_POINT":
            place = obj(row, "selectedCandidate")
            selected[unit_id] = (text(place, "physicalPlaceId"), "w0b-overlay-unique-220")
    ambiguous = {unit_id for unit_id, row in overlay.items() if row.get("joinStatus") == "AMBIGUOUS_POINT"}
    reviewed: set[str] = set()
    for decision in rows(adjudications, "adjudications"):
        unit_id, state = text(decision, "administrativeUnitId"), decision.get("reviewState")
        if unit_id not in ambiguous or unit_id in reviewed:
            raise MaterializationContractError("unreviewed ambiguity or duplicate adjudication")
        reviewed.add(unit_id)
        candidates = rows(overlay[unit_id], "candidates")
        by_place = {text(row, "physicalPlaceId"): row for row in candidates}
        rejected = decision.get("rejectedPhysicalPlaceIds")
        if not isinstance(rejected, list) or not all(isinstance(item, str) for item in rejected):
            raise MaterializationContractError("ambiguous adjudication rejected set is malformed")
        text(decision, "rationaleCode")
        text(decision, "rationale")
        evidence_refs = strings(decision, "evidenceRefs")
        if not evidence_refs or any(not item for item in evidence_refs):
            raise MaterializationContractError("evidenceRefs must be a non-empty string array")
        if state == "APPROVED_FOR_SELECTION":
            place_id = text(decision, "selectedPhysicalPlaceId")
            if place_id not in by_place or set(rejected) != set(by_place) - {place_id}:
                raise MaterializationContractError("ambiguous adjudication is not exhaustive")
            selected[unit_id] = (place_id, "w0c-reviewed-ambiguity")
        elif state != "REJECTED_FALSE_HOMONYM" or set(rejected) != set(by_place):
            raise MaterializationContractError("unreviewed ambiguity disposition")
    if reviewed != ambiguous:
        raise MaterializationContractError("unreviewed ambiguity remains")
    claim_rows = rows(claims, "claims")
    if len(claim_rows) != 8:
        raise MaterializationContractError("location claim set must contain exactly 8 LOCATION_ONLY claims")
    claim_index: dict[str, JsonObject] = {}
    for claim in claim_rows:
        claim_id, unit_id = text(claim, "sourceClaimId"), text(claim, "subjectKey")
        resolution = obj(claim, "locationResolution")
        if claim.get("reviewState") != "APPROVED" or claim.get("claimRole") != "LOCATION_ONLY":
            raise MaterializationContractError("location claim is not approved LOCATION_ONLY")
        if claim.get("selectionReviewCoverage") != "W0_ROUTE_NODE_PLACE_IDENTITY_ONLY":
            raise MaterializationContractError("location claim must be identity-only W0 review coverage")
        if unit_id not in overlay or overlay[unit_id].get("joinStatus") != "NO_COORDINATE_CANDIDATE":
            raise MaterializationContractError(
                f"location-only claim requires NO_COORDINATE_CANDIDATE overlay: {unit_id}"
            )
        if unit_id in selected or unit_id in claim_index or any(row.get("sourceClaimId") == claim_id for row in claim_index.values()):
            raise MaterializationContractError("location claim duplicates a selected binding")
        place_id = text(resolution, "physicalPlaceId")
        selected[unit_id] = (place_id, "w0c-hhs-external-location")
        claim_index[unit_id] = claim
    if len(selected) != 781 or len({value[0] for value in selected.values()}) != 781:
        raise MaterializationContractError("duplicate physicalPlaceRef or selection count drift")
    return selected, claim_index


def _forbidden_policy(policy: JsonObject) -> JsonObject:
    forbidden = obj(policy, "forbiddenSelections")
    if forbidden != EXPECTED_FORBIDDEN_SELECTIONS:
        raise MaterializationContractError("forbidden selection policy drift")
    return forbidden


def _enforce_forbidden_selection(nodes: list[JsonValue], forbidden: JsonObject) -> None:
    physical = set(strings(forbidden, "physicalPlaceIds"))
    names = set(strings(forbidden, "canonicalNames"))
    classes = set(strings(forbidden, "nodeClasses"))
    for raw in nodes:
        if not isinstance(raw, dict):
            raise MaterializationContractError("route node must be an object")
        if raw.get("physicalPlaceRef") in physical:
            raise MaterializationContractError("forbidden physicalPlaceRef selected")
        if raw.get("canonicalName") in names or raw.get("displayName") in names:
            raise MaterializationContractError("forbidden canonical name selected")
        if raw.get("nodeClass") in classes:
            raise MaterializationContractError("forbidden nodeClass selected")


def _legacy_place(candidate: JsonObject) -> str:
    physical = candidate.get("physicalPlaceRef")
    if isinstance(physical, str):
        return physical
    external = text(candidate, "externalPlaceRef")
    return "external:v1:" + external.removeprefix("han-tiles:")


def _policy_corrections(policy: JsonObject, current: dict[int, JsonObject], selected: dict[str, tuple[str, str]],
                        ) -> tuple[dict[str, tuple[int, str]], set[int], set[int]]:
    corrected: dict[str, tuple[int, str]] = {}
    binding_ids: set[int] = set()
    physical_ids: set[int] = set()
    same_node = rows(policy, "legacySameNodeCorrections")
    x026 = next((row for row in same_node if row.get("oldCityId") == 704), None)
    if (x026 is None or x026.get("administrativeUnitId") != "hhs:113:上郡:009"
            or 704 not in current or current[704].get("legacyTileId") != "X026"):
        raise MaterializationContractError("X026 correction must bind hhs:113:上郡:009")
    for key in ("legacySameNodeCorrections", "legacyAttributionCorrections"):
        corrections = same_node if key == "legacySameNodeCorrections" else rows(policy, key)
        if len(corrections) != (8 if key == "legacySameNodeCorrections" else 17):
            raise MaterializationContractError("binding correction policy count drift")
        for correction in corrections:
            old_id, unit_id = number(correction, "oldCityId"), text(correction, "administrativeUnitId")
            legacy_place = text(correction, "legacyPhysicalPlaceId")
            if (correction.get("disposition") != "CORRECTED_BINDING_SAME_NODE" or old_id not in current
                    or old_id in binding_ids
                    or unit_id in corrected or unit_id not in selected or _legacy_place(current[old_id]) != legacy_place
                    or selected[unit_id][0] != legacy_place):
                raise MaterializationContractError("binding correction policy is malformed")
            corrected[unit_id] = (old_id, "CORRECTED_BINDING_SAME_NODE")
            binding_ids.add(old_id)
    if len(rows(policy, "legacyLocationCorrections")) != 1:
        raise MaterializationContractError("location correction policy count drift")
    for correction in rows(policy, "legacyLocationCorrections"):
        old_id, unit_id = number(correction, "oldCityId"), text(correction, "administrativeUnitId")
        if (old_id not in current or _legacy_place(current[old_id]) != correction.get("oldPhysicalPlaceId")
                or selected.get(unit_id, (None,))[0] != correction.get("selectedPhysicalPlaceId")):
            raise MaterializationContractError("location correction policy is malformed")
        corrected[unit_id] = (old_id, "CORRECTED_LOCATION_SAME_NODE")
        physical_ids.add(old_id)
    return corrected, binding_ids, physical_ids


def _existing_matches(selected: dict[str, tuple[str, str]], current: dict[int, JsonObject]) -> dict[str, tuple[int, str]]:
    matched: dict[str, tuple[int, str]] = {}
    for unit_id, (place_id, _) in selected.items():
        for old_id, candidate in current.items():
            proposed = candidate.get("proposedAdministrativeUnitId")
            options = candidate.get("candidateAdministrativeUnitIds", [])
            if candidate.get("physicalPlaceRef") == place_id and (proposed == unit_id or isinstance(options, list) and unit_id in options):
                if unit_id in matched:
                    raise MaterializationContractError("multiple legacy nodes match one selection")
                matched[unit_id] = (old_id, "RETAINED_SAME_NODE")
    return matched


def _uuid_keys(registry: JsonObject, selected_ids: set[str]) -> dict[str, str]:
    key_policy = obj(registry, "keyPolicy")
    derived_flags = ("derivedFromNumericCityId", "derivedFromAdministrativeIdentity", "derivedFromPhysicalPlace",
                     "derivedFromSourceClaim", "rebindingChangesKey")
    if registry.get("status") != "ISSUED" or any(key_policy.get(flag) is not False for flag in derived_flags):
        raise MaterializationContractError("registry must contain issued opaque non-derived keys")
    indexed: dict[str, str] = {}
    seen: set[str] = set()
    for row in rows(registry, "keys"):
        unit_id, key = text(row, "initialAdministrativeUnitId"), text(row, "routeNodeKey")
        try:
            parsed = UUID(key)
        except ValueError as error:
            raise MaterializationContractError("registry routeNodeKey is not a UUID") from error
        if parsed.version != 4 or key != str(parsed) or unit_id in indexed or key in seen:
            raise MaterializationContractError("registry keys must be unique literal UUIDv4 values")
        indexed[unit_id] = key
        seen.add(key)
    if set(indexed) != selected_ids:
        raise MaterializationContractError("registry must have exactly one key for every selection")
    return indexed


def _appended_numeric_ids(registry: JsonObject, selected_ids: set[str]) -> dict[str, int]:
    appended: dict[str, int] = {}
    for row in rows(registry, "keys"):
        if "numericCityId" not in row:
            continue
        unit_id = text(row, "initialAdministrativeUnitId")
        numeric_id = number(row, "numericCityId")
        if (
            unit_id not in selected_ids
            or row.get("issuanceReason") != "LICHENG_MOVEMENT_V2_APPEND"
            or unit_id in appended
        ):
            raise MaterializationContractError("append-only numeric registry row is malformed")
        appended[unit_id] = numeric_id
    expected = list(range(LEGACY_SELECTION_COUNT + 1, LEGACY_SELECTION_COUNT + len(appended) + 1))
    if sorted(appended.values()) != expected:
        raise MaterializationContractError("append-only numeric IDs must be next never-issued sequence")
    return appended


def build_outputs(
    candidate: JsonObject, catalog: JsonObject, overlay_doc: JsonObject, policy: JsonObject,
    adjudications: JsonObject, claims: JsonObject, registry: JsonObject,
    scenarios: list[JsonObject], provenance: JsonObject,
) -> BuildResult:
    ordered, units = _catalog(catalog)
    overlay = {text(row, "administrativeUnitId"): row for row in rows(overlay_doc, "administrativeUnits")}
    if len(overlay) != 1180 or overlay_doc.get("sourceYear") != 220:
        raise MaterializationContractError("overlay must cover 1,180 identities at year 220")
    current, pool = _candidate_index(candidate)
    selected, claim_index = _reviewed_selection(overlay, adjudications, claims)
    if set(selected) - pool or policy.get("status") != "APPROVED":
        raise MaterializationContractError("approved selection is outside the candidate pool")
    if (any(row.get("reviewState") != "APPROVED" for row in rows(policy, "selectionBatches"))
            or {text(row, "batchId"): number(row, "expectedCount") for row in rows(policy, "selectionBatches")} != EXPECTED_BATCH_COUNTS or Counter(value[1] for value in selected.values()) != Counter(EXPECTED_BATCH_COUNTS)):
        raise MaterializationContractError("policy count drift")
    if obj(policy, "expectedSelection") != EXPECTED_SELECTION:
        raise MaterializationContractError("policy count drift")
    decision_anchors = obj(policy, "reviewDecisionAnchors")
    if decision_anchors != EXPECTED_REVIEW_DECISION_ANCHORS:
        raise MaterializationContractError("review decision anchor policy drift")
    replacement_anchor = text(obj(decision_anchors, "replacementDecisionSet"), "anchor")
    conflict_anchor = text(obj(decision_anchors, "historicalConflictDecisionSet"), "anchor")
    forbidden = _forbidden_policy(policy)
    activation_policy = obj(policy, "scenarioActivationPolicy")
    if (
        number(activation_policy, "expectedScenarioCount") != len(scenarios)
        or activation_policy.get("runtimeEnforcement")
        != "NOT_CLAIMED_BY_W0_DATA_CONTRACT"
    ):
        raise MaterializationContractError("scenario activation policy drift")
    keys = _uuid_keys(registry, set(selected))
    matched = _existing_matches(selected, current)
    corrections, binding_ids, physical_ids = _policy_corrections(policy, current, selected)
    matched.update(corrections)
    if len({value[0] for value in matched.values()}) != len(matched):
        raise MaterializationContractError("same-node corrections reuse a legacy slot")
    appended_ids = _appended_numeric_ids(registry, set(selected))
    retired = sorted(set(range(1, LEGACY_SELECTION_COUNT + 1)) - {value[0] for value in matched.values()})
    replacements = [
        unit_id
        for unit_id in ordered
        if unit_id in selected and unit_id not in matched and unit_id not in appended_ids
    ]
    if len(retired) != 101 or len(replacements) != 101:
        raise MaterializationContractError("route replacement count must be 101")
    assignment = matched | {unit_id: (old_id, "REPLACED_UNRELATED_NODE") for unit_id, old_id in zip(replacements, retired, strict=True)}
    route_nodes: list[JsonValue] = []
    migration_rows: list[JsonValue] = []
    counters = Counter[str]()
    for unit_id, (old_id, disposition) in sorted(assignment.items(), key=lambda item: item[1][0]):
        unit, old = units[unit_id], current[old_id]
        place_id, batch_id = selected[unit_id]
        if unit_id in claim_index:
            location_claim_id = text(claim_index[unit_id], "sourceClaimId")
            location_review: JsonObject = {"kind": "APPROVED_LOCATION_ONLY_CLAIM", "sourceClaimId": location_claim_id}
        else:
            location_claim_id = None
            location_review = {"kind": "W0B_GLOBAL_UNIQUE_220"}
            if batch_id == "w0c-reviewed-ambiguity":
                decision = next(row for row in rows(adjudications, "adjudications") if row.get("administrativeUnitId") == unit_id)
                rejected_place_ids = strings(decision, "rejectedPhysicalPlaceIds")
                rationale_code = text(decision, "rationaleCode")
                rationale = text(decision, "rationale")
                evidence_refs = strings(decision, "evidenceRefs")
                location_review = {"kind": "EXPLICIT_AMBIGUITY_REVIEW", "selectedPhysicalPlaceRef": place_id,
                                   "rejectedPhysicalPlaceRefs": rejected_place_ids,
                                   "rationaleCode": rationale_code, "rationale": rationale,
                                   "evidenceRefs": evidence_refs}
        correction = unit.get("nameCorrection")
        canonical = text(correction, "correctedName") if isinstance(correction, dict) else text(unit, "sourceName")
        seat_role = "COMMANDERY_SEAT" if number(unit, "ordinal") == 1 else "NON_SEAT"
        unit_type = text(unit, "unitType")
        if unit_type not in NODE_CLASSES:
            raise MaterializationContractError(f"unitType is invalid: {unit_id}")
        node_class = "COUNTY_NODE" if unit_id == "hhs:113:上郡:009" else NODE_CLASSES[unit_type]
        if node_class not in ALLOWED_NODE_CLASSES:
            raise MaterializationContractError("nodeClass must be one of the four route-node classes")
        node: JsonObject = {
            "legacyCityId": old_id,
            "legacyNodeFingerprint": text(old, "legacyNodeFingerprint"),
            "legacyDisposition": "REPLACED" if disposition == "REPLACED_UNRELATED_NODE" else "RETAINED",
            "numericCityId": old_id,
            "routeNodeKey": keys[unit_id],
            "reviewState": "APPROVED",
            "nodeClass": node_class,
            "displayName": canonical,
            "canonicalName": canonical,
            "seatRole": seat_role,
            "parentName": text(unit, "canonicalGroup"),
            "parentRef": f"hhs-group:{number(unit, 'sourceVolume')}:{text(unit, 'canonicalGroup')}",
            "physicalPlaceRef": place_id,
            "historicalBindingBasis": "HHS_ADMINISTRATIVE_UNIT",
            "administrativeUnitId": unit_id,
            "locationAdjudication": location_review,
            "selectionRationale": {
                "method": "APPROVED_REVIEW_BATCH",
                "batchId": batch_id,
                "reviewPolicyId": text(policy, "policyId"),
                "rationale": "승인된 W0-C review batch와 고정 입력 해시에 따른 행별 선정이다.",
                "evidenceRefs": ["data/curated/han/route-node-review-policy-v1.json", batch_id],
            },
        }
        if location_claim_id is not None:
            node["locationClaimId"] = location_claim_id
        if disposition == "REPLACED_UNRELATED_NODE":
            node["replacementDisposition"] = {
                "rationale": "기존 슬롯의 장소 identity를 승계하지 않고 승인된 HHS RouteNode로 명시 교체한다.",
                "evidenceRefs": ["data/curated/han/route-node-review-policy-v1.json", replacement_anchor],
            }
        if old_id in physical_ids:
            node["physicalPlaceCorrection"] = {
                "fromPhysicalPlaceRef": _legacy_place(old),
                "toPhysicalPlaceRef": place_id,
                "rationale": "모호 위치 행별 심사에서 기존 물리점을 기각하고 승인 물리점으로 교정했다.",
                "evidenceRefs": ["data/curated/han/route-node-location-adjudications-v1.json", unit_id],
            }
        if old.get("classification") == "HHS_ATTRIBUTION_CONFLICT":
            incompatible = old.get("incompatibleAdministrativeUnitIds")
            if not isinstance(incompatible, list) or not all(isinstance(value, str) for value in incompatible):
                raise MaterializationContractError("attribution conflict candidate is malformed")
            node["historicalConflictDisposition"] = {
                "selectedBindingRef": unit_id,
                "rejectedAdministrativeUnitIds": [value for value in incompatible if value != unit_id],
                "rationale": "legacy 물리점의 타 군국 귀속을 숨기지 않고 승인 정책의 HHS 결속으로 판정했다.",
                "evidenceRefs": ["data/curated/han/route-node-review-policy-v1.json", conflict_anchor],
            }
        route_nodes.append(node)
        migration_rows.append({"oldCityId": old_id, "oldNodeFingerprint": text(old, "legacyNodeFingerprint"),
                               "routeNodeKey": keys[unit_id], "newCityId": old_id, "disposition": disposition})
        counters["routeNodeReplacementCount"] += disposition == "REPLACED_UNRELATED_NODE"
        counters["historicalBindingCorrectionCount"] += old_id in binding_ids
        counters["physicalPlaceCorrectionCount"] += old_id in physical_ids
        counters["displayNameChangeCount"] += canonical != old.get("legacyNameCh")
        counters["parentChangeCount"] += text(unit, "canonicalGroup") != old.get("legacyOwnerGroup")
        counters["seatRoleChangeCount"] += (seat_role == "COMMANDERY_SEAT") != old.get("legacyIsSeat")
    appended_rows: list[JsonValue] = []
    for unit_id, numeric_id in sorted(appended_ids.items(), key=lambda item: item[1]):
        unit = units[unit_id]
        place_id, batch_id = selected[unit_id]
        if batch_id != "w0b-overlay-unique-220":
            raise MaterializationContractError("append-only node must use reviewed overlay binding")
        correction = unit.get("nameCorrection")
        canonical = text(correction, "correctedName") if isinstance(correction, dict) else text(unit, "sourceName")
        node_class = NODE_CLASSES[text(unit, "unitType")]
        node = {
            "numericCityId": numeric_id,
            "routeNodeKey": keys[unit_id],
            "reviewState": "APPROVED",
            "nodeClass": node_class,
            "displayName": canonical,
            "canonicalName": canonical,
            "seatRole": "COMMANDERY_SEAT" if number(unit, "ordinal") == 1 else "NON_SEAT",
            "parentName": text(unit, "canonicalGroup"),
            "parentRef": f"hhs-group:{number(unit, 'sourceVolume')}:{text(unit, 'canonicalGroup')}",
            "physicalPlaceRef": place_id,
            "historicalBindingBasis": "HHS_ADMINISTRATIVE_UNIT",
            "administrativeUnitId": unit_id,
            "locationAdjudication": {"kind": "W0B_GLOBAL_UNIQUE_220"},
            "selectionRationale": {
                "method": "APPROVED_REVIEW_BATCH",
                "batchId": batch_id,
                "reviewPolicyId": text(policy, "policyId"),
                "rationale": "승인된 W0-C review batch와 고정 입력 해시에 따른 행별 선정이다.",
                "evidenceRefs": ["data/curated/han/route-node-review-policy-v1.json", batch_id],
            },
        }
        route_nodes.append(node)
        appended_rows.append({
            "newCityId": numeric_id,
            "routeNodeKey": keys[unit_id],
            "administrativeUnitId": unit_id,
            "physicalPlaceRef": place_id,
            "disposition": "APPENDED_NEW_WORLD_IDENTITY",
        })
    _enforce_forbidden_selection(route_nodes, forbidden)
    replacement_decisions = [
        {key: node[key] for key in ("legacyCityId", "administrativeUnitId", "physicalPlaceRef", "routeNodeKey")}
        for node in route_nodes
        if isinstance(node, dict) and node.get("legacyDisposition") == "REPLACED"
    ]
    conflict_decisions = [
        {
            "legacyCityId": node["legacyCityId"],
            "administrativeUnitId": node["administrativeUnitId"],
            "selectedBindingRef": obj(node, "historicalConflictDisposition")["selectedBindingRef"],
            "rejectedAdministrativeUnitIds": obj(node, "historicalConflictDisposition")["rejectedAdministrativeUnitIds"],
        }
        for node in route_nodes
        if isinstance(node, dict) and "historicalConflictDisposition" in node
    ]
    for name, decisions in (
        ("replacementDecisionSet", replacement_decisions),
        ("historicalConflictDecisionSet", conflict_decisions),
    ):
        anchor = obj(decision_anchors, name)
        if anchor.get("rowCount") != len(decisions) or anchor.get("assignmentSha256") != _decision_digest(decisions):
            raise MaterializationContractError(f"{name} does not match reviewed assignments")
    numeric_policy = obj(policy, "numericAssignmentPolicy")
    if numeric_policy != {
        "appendOnlyNewWorldNodeAllowed": True,
        "appendOnlyNumericIdStart": 781,
        "legacyNumericIdsImmutableThrough": 780,
        "numericCityIdChangeAllowed": False,
        "replacementNodeOrder": "W0-A sourceVolume, canonicalGroup source order, ordinal",
        "replacementNodesUseRetiredSlotsInAscendingOrder": True,
        "retainedAndCorrectedNodesKeepOldCityId": True,
        "routeNodeKeySource": "opaque UUID literals from route-node-key-registry-v1; never derived from numeric id, HHS identity, physical place, or claim",
    }:
        raise MaterializationContractError("numeric assignment policy drift")
    selection: JsonObject = {"schemaVersion": 1, "selectionId": "han-route-node-selection-v1", "worldVersion": "han-world-v2", "reviewState": "APPROVED", "baselineYear": 220,
                             "runtimeScenarioActivationEnforcement": "NOT_CLAIMED_BY_W0_DATA_CONTRACT", "scenarioCatalog": {"resourceCount": len(scenarios), "resources": list[JsonValue](scenarios)},
                             "reviewPolicy": {"policyId": text(policy, "policyId"), "forbiddenSelections": forbidden,
                                              "reviewDecisionAnchors": decision_anchors,
                                              "numericCityIdChangeAllowed": False,
                                              "legacyAttributionCorrections": [number(row, "oldCityId") for row in rows(policy, "legacyAttributionCorrections")]},
                             "provenance": provenance, "summary": {"approvedCount": 781, "historicalBindingCounts": {"HHS_ADMINISTRATIVE_UNIT": 781}}, "routeNodes": route_nodes}
    migration: JsonObject = {"schemaVersion": 1, "migrationId": "han-route-node-migration-v1", "mode": "NEW_WORLD_ONLY", "targetWorldVersion": "han-world-v2", "sourceSelectionId": "han-route-node-selection-v1",
                             "referenceInventory": {"mutable": MUTABLE_REFERENCES, "immutableAudit": IMMUTABLE_AUDIT_REFERENCES,
                                                    "derivedReseed": DERIVED_RESEED_REFERENCES,
                                                    "unknownPayloadPolicy": "REJECT_UNKNOWN", "inPlaceRewrite": False},
                             "rewriteSurfaces": EXPECTED_REWRITE_SURFACES,
                             "summary": {"rowCount": 780, "appendedIdentityCount": len(appended_rows), **{name: counters[name] for name in COUNTER_NAMES}}, "rows": migration_rows,
                             "appendedRows": appended_rows}
    return BuildResult(selection=selection, migration=migration)
