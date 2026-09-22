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
# 변경 縣 51 곳(frontier-counties-v1)은 CHGIS 점이 없는 HHS 단위라 郡治 8 곳과 같은 LOCATION_ONLY claim 으로
# 결합하되, 별도 batch 로 센다 — physicalPlaceId 이름공간이 둘을 가른다(tools/map/materialize_frontier_counties.py).
FRONTIER_COUNTY_BATCH = "w1-frontier-county-location"
# 결손 縣 60 곳(gap-counties-v1). 변경 縣과 같은 LOCATION_ONLY 규약이고 등록 도구는
# tools/scenario/append_gap_county_route_ledgers.py 다. 실체화는 거점 분할 단계의 국소 carve 가 한다.
GAP_COUNTY_BATCH = "w1-gap-county-location"
FRONTIER_COUNTY_PLACE_PREFIX = "curated:frontier-county-v1:"
GAP_COUNTY_PLACE_PREFIX = "curated:gap-county-v1:"
SCRIPT_VARIANT_BATCH = "w1-script-variant-county-join"
EXTERNAL_LOCATION_BATCH = "w0c-hhs-external-location"
# 오결속 城이 비운 발자국에 제 이름의 郡國志 縣을 세우는 batch(2026-09-16). 五原郡 河陰(56)이 河南尹
# 平陰縣의 220 년 개명 기록(CHGIS 82880)을 차지해 平陰이 NO_COORDINATE_CANDIDATE 로 남아 있었다.
# 河陰을 제자리로 옮기면서 옛 발자국을 平陰(CHGIS 82879)에 넘겼다(county-misbinding-rebindings-v1 leaveBehind).
# claim 배치(w2·w3) **뒤에** 번호를 받는다 — 앞 판의 번호를 한 칸도 밀지 않기 위해서다.
VACATED_LOCATION_BATCH = "w4-vacated-county-location"
VACATED_LOCATION_ISSUANCE = "VACATED_COUNTY_LOCATION_V1_APPEND"
VACATED_LOCATION_UNITS = frozenset({"hhs:109:河南尹:011"})
#: 좌표가 없는 HHS 단위를 승인된 점 claim 으로 붙이는 batch. append 행은 여기만 쓴다.
LOCATION_ONLY_BATCHES = frozenset({FRONTIER_COUNTY_BATCH, GAP_COUNTY_BATCH, EXTERNAL_LOCATION_BATCH, VACATED_LOCATION_BATCH})
@dataclass(frozen=True, slots=True)
class ClaimBatch:
    """郡國志 식별자 없이 source claim 으로 경로 노드를 세우는 batch 한 줄.

    subjectKey 는 `subject_prefix + tileBinding.jurisdictionId` 이고, 번호는 앞 batch 뒤에 이어진다.
    """
    batch_id: str
    issuance_reason: str
    subject_prefix: str
    subject_type: str
    node_classes: frozenset[str]
    seat_roles: frozenset[str]
    evidence_kinds: frozenset[str]
    expected_count: int
    policy_input: str
    ledger_path: str


CLAIM_BATCHES: tuple[ClaimBatch, ...] = (
    # 郡國志 식별자가 없는 han-tiles 縣 관할(tools/scenario/append_cityless_jurisdiction_route_claims.py).
    ClaimBatch(
        "w2-cityless-jurisdiction-route-claim", "CITYLESS_JURISDICTION_ROUTE_CLAIM_V1_APPEND",
        "han-tiles-jurisdiction:", "ADMINISTRATIVE_PLACE", frozenset({"COUNTY_NODE"}),
        frozenset({"COMMANDERY_SEAT", "NON_SEAT"}), frozenset({"CHGIS_V6_COUNTY_POINT", "JURISDICTION_SEAT_RECOVERY"}),
        174, "jurisdictionRouteClaims", "data/curated/han/route-node-jurisdiction-claims-v1.json",
    ),
    # 縣이 아닌 수·진·관 거점(ADR-LITE-052, tools/scenario/append_strategic_site_route_claims.py).
    ClaimBatch(
        "w3-strategic-site-route-claim", "STRATEGIC_SITE_ROUTE_CLAIM_V1_APPEND",
        "strategic-site:", "STRATEGIC_SITE", frozenset({"FERRY_NODE", "FORT_NODE", "PASS_NODE"}),
        frozenset({"NON_SEAT"}), frozenset({"STRATEGIC_SITE_LEDGER"}),
        73, "strategicSiteRouteClaims", "data/curated/han/route-node-strategic-site-claims-v1.json",
    ),
    # 城 없던 郡國 밖 취락 관할(tools/scenario/append_external_settlement_route_claims.py, 2026-09-17).
    ClaimBatch(
        "w5-external-settlement-route-claim", "EXTERNAL_SETTLEMENT_ROUTE_CLAIM_V1_APPEND",
        "han-tiles-external-settlement:", "EXTERNAL_SETTLEMENT", frozenset({"SETTLEMENT_NODE"}),
        frozenset({"COMMANDERY_SEAT", "NON_SEAT"}), frozenset({"EXTERNAL_PLACE_RECORD"}),
        72, "externalSettlementRouteClaims", "data/curated/han/route-node-external-settlement-claims-v1.json",
    ),
)
#: 같은 縣이 두 번 선 城의 번호를 다른 claim 이 이어받는 키 재결속 사유(registry row 의 rebinding).
REBINDING_REASONS = frozenset({"DUPLICATE_ROUTE_NODE_SLOT_REUSE"})


def registry_unit_id(row: JsonObject) -> str:
    """키 원장 행이 지금 가리키는 결합 — 재결속이 있으면 그 결합, 없으면 최초 결합."""
    rebinding = row.get("rebinding")
    if rebinding is None:
        return text(row, "initialAdministrativeUnitId")
    if not isinstance(rebinding, dict) or rebinding.get("reason") not in REBINDING_REASONS or (
        rebinding.get("withdrawnAdministrativeUnitId") != row.get("initialAdministrativeUnitId")
    ):
        raise MaterializationContractError("registry rebinding must name its reason and withdrawn identity")
    return text(rebinding, "administrativeUnitId")
JURISDICTION_CLAIM_BATCH = CLAIM_BATCHES[0].batch_id
CLAIM_BATCH_BY_ID = {batch.batch_id: batch for batch in CLAIM_BATCHES}
APPEND_ISSUANCE_REASONS = {"LICHENG_MOVEMENT_V2_APPEND", "FRONTIER_COUNTY_V1_APPEND", "CITYLESS_COMMANDERY_SEAT_V1_APPEND", "SCRIPT_VARIANT_COUNTY_JOIN_V1_APPEND", "GAP_COUNTY_V1_APPEND", VACATED_LOCATION_ISSUANCE} | {batch.issuance_reason for batch in CLAIM_BATCHES}
# 邊郡 8곳 + 城을 하나도 못 받던 朔方·西河·定襄 3곳 = 11. 셋 다 같은 external:v1 이름공간이라
# 같은 batch 로 센다(tools/scenario/append_cityless_commandery_seat_ledgers.py).
EXPECTED_HHS_BATCH_COUNTS = {"w0b-overlay-unique-220": 723, "w0c-reviewed-ambiguity": 50, EXTERNAL_LOCATION_BATCH: 11, FRONTIER_COUNTY_BATCH: 51, GAP_COUNTY_BATCH: 60, SCRIPT_VARIANT_BATCH: 13, VACATED_LOCATION_BATCH: len(VACATED_LOCATION_UNITS)}
EXPECTED_JURISDICTION_CLAIM_COUNT = sum(batch.expected_count for batch in CLAIM_BATCHES)
EXPECTED_BATCH_COUNTS = {**EXPECTED_HHS_BATCH_COUNTS, **{batch.batch_id: batch.expected_count for batch in CLAIM_BATCHES}}
EXPECTED_LOCATION_CLAIM_COUNT = (EXPECTED_BATCH_COUNTS[EXTERNAL_LOCATION_BATCH] + EXPECTED_BATCH_COUNTS[FRONTIER_COUNTY_BATCH]
                                 + EXPECTED_BATCH_COUNTS[GAP_COUNTY_BATCH]
                                 + EXPECTED_BATCH_COUNTS[VACATED_LOCATION_BATCH])
HHS_SELECTION_COUNT = sum(EXPECTED_HHS_BATCH_COUNTS.values())
SELECTION_COUNT = sum(EXPECTED_BATCH_COUNTS.values())
EXPECTED_SELECTION = {"routeNodeCount": SELECTION_COUNT, "hhsAdministrativeBindingCount": HHS_SELECTION_COUNT, "externalHistoricalBindingCount": 0, "overlayUniqueCount": 723, "reviewedAmbiguousCount": 50, "externalLocationClaimCount": 11, "sourcePlaceholderCount": 0, "polityPresenceCount": 0, "remoteGateCount": 0, "frontierCountyClaimCount": 51, "gapCountyClaimCount": 60, "vacatedCountyLocationClaimCount": len(VACATED_LOCATION_UNITS), "reviewedSourceClaimBindingCount": EXPECTED_JURISDICTION_CLAIM_COUNT}
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
# 2026-09-17: 郡國 밖 취락 치소 31 곳과 그 이름 11 개의 금지를 w5(external-settlement route claim)가 풀었다 —
# 「절대 소속 없는 프로빈스가 있어선 안돼」(사용자 결정). 남는 금지는 龜茲屬國 이름과 비행정 노드 종류뿐이다.
EXPECTED_FORBIDDEN_SELECTIONS: JsonObject = {
    "physicalPlaceIds": [],
    "canonicalNames": ["龜茲屬國"],
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


def _location_claim_eligible(unit: JsonObject, overlay_row: JsonObject, rejected_homonyms: set[str]) -> bool:
    """LOCATION_ONLY claim 이 붙을 수 있는 단위 — CHGIS 점이 하나도 남지 않은 HHS 단위다.

    NO_COORDINATE_CANDIDATE 그대로이거나, 유일 후보가 REJECTED_FALSE_HOMONYM 으로 전부 기각된
    AMBIGUOUS_POINT 이거나, 자리표시자 縣名이 독립 인용 nameCorrection 으로 복원된 SOURCE_PLACEHOLDER 다.
    """
    status = overlay_row.get("joinStatus")
    if status == "NO_COORDINATE_CANDIDATE":
        return True
    if status == "AMBIGUOUS_POINT":
        return text(overlay_row, "administrativeUnitId") in rejected_homonyms
    if status == "SOURCE_PLACEHOLDER":
        return isinstance(unit.get("nameCorrection"), dict)
    return False


def _reviewed_selection(overlay: dict[str, JsonObject], adjudications: JsonObject, claims: JsonObject,
                        units: dict[str, JsonObject], script_variant_members: frozenset[str],
                        deferred_commandery: frozenset[str] = frozenset(),
                        ) -> tuple[dict[str, tuple[str, str]], dict[str, JsonObject]]:
    selected: dict[str, tuple[str, str]] = {}
    rejected_homonyms: set[str] = set()
    for unit_id, row in overlay.items():
        if row.get("joinStatus") == "RESOLVED_POINT":
            if unit_id in deferred_commandery:
                # 귀속 심사 대기: 결합은 유지하되 선정에서 제외한다.
                continue
            place = obj(row, "selectedCandidate")
            batch = SCRIPT_VARIANT_BATCH if unit_id in script_variant_members else "w0b-overlay-unique-220"
            selected[unit_id] = (text(place, "physicalPlaceId"), batch)
    if script_variant_members - set(selected) or any(
        selected[unit_id][1] != SCRIPT_VARIANT_BATCH for unit_id in script_variant_members
    ):
        raise MaterializationContractError("script-variant batch members must all resolve to overlay points")
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
        else:
            rejected_homonyms.add(unit_id)
    if reviewed != ambiguous:
        raise MaterializationContractError("unreviewed ambiguity remains")
    claim_rows = rows(claims, "claims")
    if len(claim_rows) != EXPECTED_LOCATION_CLAIM_COUNT:
        raise MaterializationContractError(
            f"location claim set must contain exactly {EXPECTED_LOCATION_CLAIM_COUNT} LOCATION_ONLY claims"
        )
    claim_index: dict[str, JsonObject] = {}
    for claim in claim_rows:
        claim_id, unit_id = text(claim, "sourceClaimId"), text(claim, "subjectKey")
        resolution = obj(claim, "locationResolution")
        if claim.get("reviewState") != "APPROVED" or claim.get("claimRole") != "LOCATION_ONLY":
            raise MaterializationContractError("location claim is not approved LOCATION_ONLY")
        if claim.get("selectionReviewCoverage") != "W0_ROUTE_NODE_PLACE_IDENTITY_ONLY":
            raise MaterializationContractError("location claim must be identity-only W0 review coverage")
        if unit_id not in overlay or unit_id not in units or not _location_claim_eligible(
            units[unit_id], overlay[unit_id], rejected_homonyms
        ):
            raise MaterializationContractError(
                f"location-only claim requires an HHS unit without any surviving coordinate candidate: {unit_id}"
            )
        if unit_id in selected or unit_id in claim_index or any(row.get("sourceClaimId") == claim_id for row in claim_index.values()):
            raise MaterializationContractError("location claim duplicates a selected binding")
        place_id = text(resolution, "physicalPlaceId")
        if unit_id in VACATED_LOCATION_UNITS:
            batch_id = VACATED_LOCATION_BATCH
        elif place_id.startswith(FRONTIER_COUNTY_PLACE_PREFIX):
            batch_id = FRONTIER_COUNTY_BATCH
        elif place_id.startswith(GAP_COUNTY_PLACE_PREFIX):
            batch_id = GAP_COUNTY_BATCH
        else:
            batch_id = EXTERNAL_LOCATION_BATCH
        selected[unit_id] = (place_id, batch_id)
        claim_index[unit_id] = claim
    if len(selected) != HHS_SELECTION_COUNT or len({value[0] for value in selected.values()}) != HHS_SELECTION_COUNT:
        raise MaterializationContractError("duplicate physicalPlaceRef or selection count drift")
    return selected, claim_index


JURISDICTION_CLAIM_FIELDS = frozenset({
    "sourceClaimId", "claimRole", "reviewState", "subjectType", "subjectKey", "canonicalName", "nodeClass",
    "seatRole", "parentName", "parentRef", "physicalPlaceRef", "tileBinding", "evidence",
})


def _jurisdiction_claims(document: JsonObject, policy: JsonObject, batch: ClaimBatch = CLAIM_BATCHES[0]) -> list[JsonObject]:
    """source claim batch 하나의 ROUTE_NODE claim — 필드·결속 규약을 fail-closed 로 본다."""
    batches = [row for row in rows(policy, "selectionBatches") if text(row, "batchId") == batch.batch_id]
    if len(batches) != 1 or document.get("status") != "APPROVED":
        raise MaterializationContractError(f"{batch.batch_id} must appear once and its ledger must be APPROVED")
    claims = rows(document, "claims")
    if len(claims) != batch.expected_count or number(batches[0], "expectedCount") != len(claims):
        raise MaterializationContractError(f"{batch.batch_id} claim count drift")
    seen_claims: set[str] = set()
    seen_subjects: set[str] = set()
    for claim in claims:
        claim_id = text(claim, "sourceClaimId")
        if set(claim) != JURISDICTION_CLAIM_FIELDS:
            raise MaterializationContractError(f"jurisdiction claim fields must be exact: {claim_id}")
        if (claim.get("claimRole") != "ROUTE_NODE" or claim.get("reviewState") != "APPROVED"
                or claim.get("subjectType") != batch.subject_type or claim.get("nodeClass") not in batch.node_classes
                or claim.get("seatRole") not in batch.seat_roles):
            raise MaterializationContractError(f"claim is not an approved {batch.batch_id} ROUTE_NODE claim: {claim_id}")
        subject = text(claim, "subjectKey")
        binding = obj(claim, "tileBinding")
        if subject != batch.subject_prefix + text(binding, "jurisdictionId"):
            raise MaterializationContractError(f"jurisdiction claim subjectKey must name its tile jurisdiction: {claim_id}")
        if text(obj(claim, "evidence"), "kind") not in batch.evidence_kinds:
            raise MaterializationContractError(f"jurisdiction claim evidence kind is not reviewable: {claim_id}")
        if claim_id in seen_claims or subject in seen_subjects:
            raise MaterializationContractError(f"duplicate jurisdiction claim: {claim_id}")
        seen_claims.add(claim_id)
        seen_subjects.add(subject)
    return claims


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
    # 2026-09-17: + 579 巴郡 漢昌 蒼溪(蕭齊 개치) → 巴中(讀史方輿紀要 卷68 「漢昌城，今州治」) = 2.
    if len(rows(policy, "legacyLocationCorrections")) != 2:
        raise MaterializationContractError("location correction policy count drift")
    for correction in rows(policy, "legacyLocationCorrections"):
        old_id, unit_id = number(correction, "oldCityId"), text(correction, "administrativeUnitId")
        if (old_id not in current or _legacy_place(current[old_id]) != correction.get("oldPhysicalPlaceId")
                or selected.get(unit_id, (None,))[0] != correction.get("selectedPhysicalPlaceId")):
            raise MaterializationContractError("location correction policy is malformed")
        corrected[unit_id] = (old_id, "CORRECTED_LOCATION_SAME_NODE")
        physical_ids.add(old_id)
    return corrected, binding_ids, physical_ids


def _existing_matches(selected: dict[str, tuple[str, str]], current: dict[int, JsonObject],
                      append_only_units: frozenset[str] = frozenset()) -> dict[str, tuple[int, str]]:
    matched: dict[str, tuple[int, str]] = {}
    for unit_id, (place_id, _) in selected.items():
        if unit_id in append_only_units:
            # 승인된 append 전용 단위는 legacy 슬롯을 차지하지 않는다. 기존 780 슬롯 균형을
            # 건드리지 않고 새 numericCityId로만 편입된다.
            continue
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
        unit_id, key = registry_unit_id(row), text(row, "routeNodeKey")
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
    """append 번호. 앞선 HHS append 는 781 부터 끊김 없이, claim 배치는 그 뒤, 비운 자리 縣은 맨 뒤다.

    끊김 없음은 **전체 append 합집합**으로 본다 — 비운 자리 縣(w4)이 claim 배치 뒤에 붙어도
    어떤 번호도 건너뛰거나 겹치지 않는다."""
    appended: dict[str, int] = {}
    issued_order: list[str] = []
    for row in rows(registry, "keys"):
        if "numericCityId" not in row:
            continue
        unit_id = registry_unit_id(row)
        numeric_id = number(row, "numericCityId")
        # 재결속 행은 발급 사유(최초 결합의 batch)와 지금 결합의 batch 가 다르다 — 최초 결합으로 사유를 대조한다.
        issued_unit = text(row, "initialAdministrativeUnitId")
        if (
            unit_id not in selected_ids
            or row.get("issuanceReason") not in APPEND_ISSUANCE_REASONS
            or any((row.get("issuanceReason") == batch.issuance_reason)
                   != issued_unit.startswith(batch.subject_prefix) for batch in CLAIM_BATCHES)
            or ("rebinding" in row and not any(unit_id.startswith(batch.subject_prefix) for batch in CLAIM_BATCHES))
            or unit_id in appended
        ):
            raise MaterializationContractError("append-only numeric registry row is malformed")
        appended[unit_id] = numeric_id
        issued_order.append(unit_id)
    from tools.scenario.han_active_city_ids import active_numeric_ids
    expected = [i for i in active_numeric_ids(LEGACY_SELECTION_COUNT + len(appended)) if i > LEGACY_SELECTION_COUNT]
    if sorted(appended.values()) != expected:
        raise MaterializationContractError("append-only numeric IDs must be next never-issued sequence")
    late = {unit_id for unit_id in appended if unit_id in VACATED_LOCATION_UNITS}
    first_late = min((issued_order.index(unit_id) for unit_id in late), default=len(issued_order))
    if late and min(appended[unit_id] for unit_id in late) <= max(
        (appended[unit_id] for unit_id in issued_order[:first_late]), default=0
    ):
        raise MaterializationContractError("vacated-county append IDs must follow every earlier append")
    return appended


def build_outputs(
    candidate: JsonObject, catalog: JsonObject, overlay_doc: JsonObject, policy: JsonObject,
    adjudications: JsonObject, claims: JsonObject, registry: JsonObject,
    scenarios: list[JsonObject], provenance: JsonObject, claim_documents: dict[str, JsonObject],
) -> BuildResult:
    ordered, units = _catalog(catalog)
    overlay = {text(row, "administrativeUnitId"): row for row in rows(overlay_doc, "administrativeUnits")}
    if len(overlay) != 1180 or overlay_doc.get("sourceYear") != 220:
        raise MaterializationContractError("overlay must cover 1,180 identities at year 220")
    current, pool = _candidate_index(candidate)
    script_variant_batches = [row for row in rows(policy, "selectionBatches") if text(row, "batchId") == SCRIPT_VARIANT_BATCH]
    if len(script_variant_batches) != 1:
        raise MaterializationContractError("script-variant batch must appear exactly once in policy")
    script_variant_members = frozenset(strings(script_variant_batches[0], "memberAdministrativeUnitIds"))
    if len(script_variant_members) != number(script_variant_batches[0], "expectedCount"):
        raise MaterializationContractError("script-variant batch members must match its expected count")
    deferred = obj(policy, "deferredCommanderyAdjudication") if "deferredCommanderyAdjudication" in policy else None
    deferred_commandery = frozenset()
    if deferred is not None:
        if deferred.get("reviewState") != "DEFERRED":
            raise MaterializationContractError("deferred commandery adjudication must stay DEFERRED")
        deferred_commandery = frozenset(text(row, "administrativeUnitId") for row in rows(deferred, "members"))
        if len(deferred_commandery) != 5 or not deferred_commandery.isdisjoint(script_variant_members):
            raise MaterializationContractError("deferred members must be exactly the 5 reviewed holds outside w1")
        for unit_id in deferred_commandery:
            if overlay.get(unit_id, {}).get("joinStatus") != "RESOLVED_POINT":
                raise MaterializationContractError("deferred members must keep their resolved overlay joins")
    selected, claim_index = _reviewed_selection(overlay, adjudications, claims, units, script_variant_members, deferred_commandery)
    if set(selected) - pool or policy.get("status") != "APPROVED":
        raise MaterializationContractError("approved selection is outside the candidate pool")
    if (any(row.get("reviewState") != "APPROVED" for row in rows(policy, "selectionBatches"))
            or {text(row, "batchId"): number(row, "expectedCount") for row in rows(policy, "selectionBatches")} != EXPECTED_BATCH_COUNTS or Counter(value[1] for value in selected.values()) != Counter(EXPECTED_HHS_BATCH_COUNTS)):
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
    if set(claim_documents) != set(CLAIM_BATCH_BY_ID):
        raise MaterializationContractError("every source-claim batch requires exactly its claim ledger")
    route_claims = [(batch, claim) for batch in CLAIM_BATCHES
                    for claim in _jurisdiction_claims(claim_documents[batch.batch_id], policy, batch)]
    claim_subjects = {text(claim, "subjectKey") for _, claim in route_claims}
    if len(claim_subjects) != len(route_claims):
        raise MaterializationContractError("source-claim subjects must be unique across batches")
    if claim_subjects & set(selected):
        raise MaterializationContractError("jurisdiction claim subject collides with an HHS binding")
    keys = _uuid_keys(registry, set(selected) | claim_subjects)
    matched = _existing_matches(selected, current, script_variant_members)
    corrections, binding_ids, physical_ids = _policy_corrections(policy, current, selected)
    matched.update(corrections)
    if len({value[0] for value in matched.values()}) != len(matched):
        raise MaterializationContractError("same-node corrections reuse a legacy slot")
    appended_ids = _appended_numeric_ids(registry, set(selected) | claim_subjects)
    claim_numeric_ids = {subject: appended_ids.pop(subject) for subject in claim_subjects}
    # 늦게 덧붙인 HHS 추가분은 floor 에서 뺀다. 이 계약은 「HHS 추가분이 claim 배치보다 먼저 번호를
    # 받는다」를 가정하는데, 나중에 추가된 것은 그럴 수 없다 — 은퇴 번호를 되쓸 수 없기 때문이다.
    # VACATED_LOCATION_UNITS 가 이미 같은 이유로 빠져 있고, 결손 縣 60 곳(GAP_COUNTY_BATCH)도 같다.
    # 대신 아래에서 「늦은 추가분은 앞선 모든 추가분 뒤에 온다」를 따로 단언한다.
    late_batches = {GAP_COUNTY_BATCH}
    late_units = {unit_id for unit_id, value in selected.items() if value[1] in late_batches}
    floor = max(value for unit_id, value in appended_ids.items()
                if unit_id not in VACATED_LOCATION_UNITS and unit_id not in late_units)
    if late_units:
        earlier = [value for unit_id, value in appended_ids.items() if unit_id not in late_units]
        if earlier and min(appended_ids[unit_id] for unit_id in late_units) <= max(earlier):
            raise MaterializationContractError("late HHS append IDs must follow every earlier append")
    rebound = {registry_unit_id(row) for row in rows(registry, "keys") if "rebinding" in row}
    for batch in CLAIM_BATCHES:
        # 재결속으로 앞 번호를 이어받은 claim 은 번호 순서 대조에서 뺀다(그 번호는 앞 batch 가 발급했다).
        batch_ids = [claim_numeric_ids[text(claim, "subjectKey")] for owner, claim in route_claims
                     if owner is batch and text(claim, "subjectKey") not in rebound]
        if min(batch_ids) <= floor:
            raise MaterializationContractError(f"{batch.batch_id} numeric IDs must follow every earlier append")
        floor = max(batch_ids)
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
        if batch_id == "w0b-overlay-unique-220":
            location_review = {"kind": "W0B_GLOBAL_UNIQUE_220"}
            location_claim_id = None
        elif batch_id == SCRIPT_VARIANT_BATCH:
            # 정책 명시 11곳: 오버레이 단일·무경합 결합이라 claim 없이 W0B 상당 판정으로 붙는다.
            location_review = {"kind": "W0B_GLOBAL_UNIQUE_220"}
            location_claim_id = None
        elif batch_id in LOCATION_ONLY_BATCHES and unit_id in claim_index:
            # 비운 자리 縣(w4)도 CHGIS 점이 220 년 단면에 없어 같은 LOCATION_ONLY 규약으로 붙는다.
            # 변경 縣 51곳과 城 없던 郡治 3곳은 둘 다 CHGIS 점이 없어 승인된 LOCATION_ONLY claim 으로
            # 결합한다. 이름공간만 다르고(curated:frontier-county-v1 / external:v1) 규약은 같다.
            location_claim_id = text(claim_index[unit_id], "sourceClaimId")
            location_review = {"kind": "APPROVED_LOCATION_ONLY_CLAIM", "sourceClaimId": location_claim_id}
        else:
            raise MaterializationContractError("append-only node must use reviewed overlay binding or an approved location-only claim")
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
        route_nodes.append(node)
        appended_rows.append({
            "newCityId": numeric_id,
            "routeNodeKey": keys[unit_id],
            "administrativeUnitId": unit_id,
            "physicalPlaceRef": place_id,
            "disposition": "APPENDED_NEW_WORLD_IDENTITY",
        })
    for batch, claim in sorted(route_claims, key=lambda row: claim_numeric_ids[text(row[1], "subjectKey")]):
        subject, claim_id = text(claim, "subjectKey"), text(claim, "sourceClaimId")
        numeric_id = claim_numeric_ids[subject]
        route_nodes.append({
            "numericCityId": numeric_id,
            "routeNodeKey": keys[subject],
            "reviewState": "APPROVED",
            "nodeClass": text(claim, "nodeClass"),
            "displayName": text(claim, "canonicalName"),
            "canonicalName": text(claim, "canonicalName"),
            "seatRole": text(claim, "seatRole"),
            "parentName": text(claim, "parentName"),
            "parentRef": text(claim, "parentRef"),
            "physicalPlaceRef": text(claim, "physicalPlaceRef"),
            "historicalBindingBasis": "REVIEWED_SOURCE_CLAIM",
            "sourceClaimId": claim_id,
            "locationAdjudication": {"kind": "APPROVED_ROUTE_NODE_CLAIM", "sourceClaimId": claim_id},
            "selectionRationale": {
                "method": "APPROVED_REVIEW_BATCH",
                "batchId": batch.batch_id,
                "reviewPolicyId": text(policy, "policyId"),
                "rationale": "승인된 W0-C review batch와 고정 입력 해시에 따른 행별 선정이다.",
                "evidenceRefs": ["data/curated/han/route-node-review-policy-v1.json", batch.batch_id],
            },
        })
        appended_rows.append({
            "newCityId": numeric_id,
            "routeNodeKey": keys[subject],
            "sourceClaimId": claim_id,
            "physicalPlaceRef": text(claim, "physicalPlaceRef"),
            "disposition": "APPENDED_NEW_WORLD_IDENTITY",
        })
    places = [node["physicalPlaceRef"] for node in route_nodes if isinstance(node, dict)]
    if len(places) != SELECTION_COUNT or len(set(places)) != SELECTION_COUNT:
        raise MaterializationContractError("route nodes must carry unique physicalPlaceRef values")
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
    # HHS append 가 claim 배치 뒤에 번호를 받을 수 있으므로(w4) 출력은 번호 순으로 고정한다.
    route_nodes.sort(key=lambda node: number(node, "numericCityId"))
    appended_rows.sort(key=lambda row: number(row, "newCityId"))
    selection: JsonObject = {"schemaVersion": 1, "selectionId": "han-route-node-selection-v1", "worldVersion": "han-world-v3", "reviewState": "APPROVED", "baselineYear": 220,
                             "runtimeScenarioActivationEnforcement": "NOT_CLAIMED_BY_W0_DATA_CONTRACT", "scenarioCatalog": {"resourceCount": len(scenarios), "resources": list[JsonValue](scenarios)},
                             "reviewPolicy": {"policyId": text(policy, "policyId"), "forbiddenSelections": forbidden,
                                              "reviewDecisionAnchors": decision_anchors,
                                              "numericCityIdChangeAllowed": False,
                                              "legacyAttributionCorrections": [number(row, "oldCityId") for row in rows(policy, "legacyAttributionCorrections")]},
                             "provenance": provenance, "summary": {"approvedCount": SELECTION_COUNT, "historicalBindingCounts": {"HHS_ADMINISTRATIVE_UNIT": HHS_SELECTION_COUNT, "REVIEWED_SOURCE_CLAIM": EXPECTED_JURISDICTION_CLAIM_COUNT}}, "routeNodes": route_nodes}
    migration: JsonObject = {"schemaVersion": 1, "migrationId": "han-route-node-migration-v1", "mode": "NEW_WORLD_ONLY", "targetWorldVersion": "han-world-v3", "sourceSelectionId": "han-route-node-selection-v1",
                             "referenceInventory": {"mutable": MUTABLE_REFERENCES, "immutableAudit": IMMUTABLE_AUDIT_REFERENCES,
                                                    "derivedReseed": DERIVED_RESEED_REFERENCES,
                                                    "unknownPayloadPolicy": "REJECT_UNKNOWN", "inPlaceRewrite": False},
                             "rewriteSurfaces": EXPECTED_REWRITE_SURFACES,
                             "summary": {"rowCount": 780, "appendedIdentityCount": len(appended_rows), **{name: counters[name] for name in COUNTER_NAMES}}, "rows": migration_rows,
                             "appendedRows": appended_rows}
    return BuildResult(selection=selection, migration=migration)
