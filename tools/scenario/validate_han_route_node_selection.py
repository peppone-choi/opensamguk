#!/usr/bin/env python3
# SIZE_OK: One approval transaction validates all correlated artifacts and provenance hashes.
# noqa: SIZE_OK — splitting would permit partial approval across the fail-closed trust boundary.
# /// script
# requires-python = ">=3.11"
# dependencies = []
# ///
# ─── How to run ───
# uv run tools/scenario/validate_han_route_node_selection.py --help
from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from collections import Counter
from dataclasses import dataclass
from pathlib import Path
from typing import NoReturn
from uuid import UUID

ROOT = Path(__file__).resolve().parents[2]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

DEFAULT_CANDIDATE = ROOT / "data/curated/han/route-node-selection-candidates-v1.json"
DEFAULT_CATALOG = ROOT / "data/curated/han/administrative-units.json"
DEFAULT_OVERLAY = ROOT / "data/curated/han/administrative-place-bindings-v1.json"
DEFAULT_CLAIMS = ROOT / "data/curated/han/route-node-source-claims-v1.json"
DEFAULT_SELECTION = ROOT / "data/curated/han/route-node-selection-v1.json"
DEFAULT_MIGRATION = ROOT / "data/curated/han/route-node-migration-v1.json"
DEFAULT_SCENARIOS = ROOT / "infra/src/main/resources/scenario"
PROVENANCE_DEPENDENCIES = {
    "legacyHanMap": Path("infra/src/main/resources/map/han.json"),
    "legacyTileMap": Path("data/map/han-tiles.json"),
    "locationAdjudications": Path("data/curated/han/route-node-location-adjudications-v1.json"),
    "reviewPolicy": Path("data/curated/han/route-node-review-policy-v1.json"),
    "routeNodeKeyRegistry": Path("data/curated/han/route-node-key-registry-v1.json"),
}
VALIDATION_CONTRACT_PATH = ROOT / "data/curated/han/route-node-validation-contract-v1.json"
VALIDATION_CONTRACT = json.loads(VALIDATION_CONTRACT_PATH.read_text(encoding="utf-8"))
EXPECTED_COUNT = VALIDATION_CONTRACT["expectedSelectionCount"]
EXPECTED_SCENARIOS = VALIDATION_CONTRACT["expectedActiveScenarioResourceCount"]
ALLOWED_NODE_CLASSES = frozenset(VALIDATION_CONTRACT["allowedNodeClasses"])
NODE_CLASS_BY_UNIT_TYPE = {
    "COUNTY": "COUNTY_NODE",
    "DAO": "DAO_NODE",
    "MARQUISATE": "MARQUISATE_NODE",
    "TOWN": "TOWN_NODE",
}
EXPECTED_FORBIDDEN_SELECTIONS = VALIDATION_CONTRACT["expectedForbiddenSelections"]
MUTABLE_REFERENCES = VALIDATION_CONTRACT["referenceInventory"]["mutable"]
IMMUTABLE_AUDIT_REFERENCES = VALIDATION_CONTRACT["referenceInventory"]["immutableAudit"]
DERIVED_RESEED_REFERENCES = VALIDATION_CONTRACT["referenceInventory"]["derivedReseed"]
EXPECTED_REWRITE_SURFACES = VALIDATION_CONTRACT["rewriteSurfaces"]
FORBIDDEN_RUNTIME_NODE_FIELDS = frozenset(VALIDATION_CONTRACT["forbiddenRuntimeNodeFields"])
AUTHORITY_EXACT_FIELDS = tuple(VALIDATION_CONTRACT["externalAuthorityExactFields"])
REVIEW_POLICY_ID = "han-w0c-route-node-review-policy-v1"
REVIEW_POLICY_PATH = PROVENANCE_DEPENDENCIES["reviewPolicy"].as_posix()
REVIEW_BATCH_IDS = frozenset(
    {"w0b-overlay-unique-220", "w0c-reviewed-ambiguity", "w0c-hhs-external-location"}
)
GUZI_ADMIN_ID = "hhs:113:上郡:009"
FORBIDDEN_FIELDS = frozenset(
    {"coordinate", "coordinates", "lon", "lat", "presentLocation", "presLoc", "recordIndex"}
)
FORBIDDEN_RATIONALE = re.compile(
    r"nearest|distance|closest|shortest|geographic\s+proximity|nearby|근거리|최단|거리",
    re.IGNORECASE,
)
FORBIDDEN_IDENTITY_LIFECYCLE = re.compile(
    r"\d{3,4}\s*년|W0\s*정적\s*창|자동\s*폐기|이치|이탈|존속|기간|시점|"
    r"시나리오|런타임|활성|유효|lifecycle|runtime|scenario|active|until|effective|period",
    re.IGNORECASE,
)
LOCATION_ONLY_CLAIM_FIELDS = frozenset(
    {
        "aliases", "canonicalName", "claimRole", "conflictDisposition", "locationResolution",
        "reviewEvidenceRefs", "reviewState", "selectionReviewCoverage", "sourceClaimId",
        "sourceRecords", "subjectKey", "subjectType",
    }
)
IDENTITY_CONFLICT_FIELDS = frozenset({"competingRefs", "rationaleCode", "rationale", "status"})
LOCATION_RESOLUTION_FIELDS = frozenset(
    {"coordinateDatasetRef", "kind", "physicalPlaceId", "uncertaintyRadiusKm"}
)
COORDINATE_DATASET_FIELDS = frozenset(
    {"datasetPath", "datasetSha256", "recordId", "wikidataId"}
)
SOURCE_RECORD_FIELDS = frozenset(
    {"corpusPath", "lineEnd", "lineStart", "snapshotSha256", "sourceBook", "verbatim", "volume"}
)
IDENTITY_REVIEW_EVIDENCE_REFS = (
    "data/curated/han/administrative-place-bindings-v1.json",
    "data/curated/han/route-node-external-place-authority-v1.json",
    "data/curated/han/route-node-source-witness-v1.json",
)
PINNED_ROUTE_KEY_REGISTRY_SHA256 = "7f462487d593940e2bbfe51edceea76c74a1dc589d8731e3ab0c7d6b9a267284"
PINNED_SOURCE_WITNESS_SHA256 = "7fe27b667b4066200882f9e1815e07a6adb24d826f09e0605145041897f76ee4"
PINNED_ADMINISTRATIVE_CATALOG_SHA256 = "2ba4bcc50b4cfc8c91e230c9e5c7927a2c9e127e47b21779d0c58cc8c5f8bf6f"
PINNED_REVIEWED_CANDIDATE_SHA256 = "531b377351f3dce9e9b547dbad98a8ee83ff8f823e8914d67ab1f69300551ffa"
PINNED_REVIEW_POLICY_SHA256 = "762d668db19bcf082bf69babd1c8dce0167e685a87492fa144a05b1a524484a8"
PINNED_VALIDATION_CONTRACT_SHA256 = "83c11fc237a6f03f8699a97f56326a9a6dc65990c6460482b024e7a0ee3bef66"
PINNED_LEGACY_HAN_MAP_SHA256 = "a61cbd8aa6fd0dd2f7f794df6d0ebdc026c0b6c351568c60efb8d115f54b3670"
PINNED_LEGACY_TILE_MAP_SHA256 = "1979c193de6774af7c3cf5a9ddfd1c81bf94ead5b8c5b46dafd06bed03c6888d"
PINNED_REPLACEMENT_DECISION_SHA256 = "639fe3ddf0ecb72d3e70afa5d1693ce0899744f261b2b64bbbf6177a38595ac8"
PINNED_CONFLICT_DECISION_SHA256 = "ab4f5ed35a03dfc47070d5dd985845d990cbab77c922480027461912cf44c1c7"
EXPECTED_REVIEW_POLICY_ID = "han-w0c-route-node-review-policy-v1"
EXPECTED_SELECTION_ID = "han-route-node-selection-v1"
EXPECTED_MIGRATION_ID = "han-route-node-migration-v1"
EXPECTED_SELECTION_GENERATOR = "tools/scenario/materialize_han_route_node_selection.py"
EXPECTED_SELECTION_RATIONALE = "승인된 W0-C review batch와 고정 입력 해시에 따른 행별 선정이다."
CANDIDATE_FIELDS = frozenset(
    {"administrativeUnitId", "candidateAdministrativeUnitIds", "candidateKey", "canonicalGroup",
     "classification", "correctionCandidate", "externalPlaceRef", "incompatibleAdministrativeUnitIds",
     "legacyCityId", "legacyIsSeat", "legacyNameCh", "legacyNodeFingerprint", "legacyOwnerGroup",
     "legacyTileId", "origin", "overlayJoinStatus", "ownerGroupResolution",
     "physicalPlaceAdministrativeUnitCount", "physicalPlaceRef", "physicalPlaceRefs",
     "proposedAdministrativeUnitId", "reviewState", "sourceName", "sourceNameStatus", "unitType"}
)
ROUTE_NODE_FIELDS = frozenset(
    {"administrativeUnitId", "canonicalName", "displayName", "historicalBindingBasis",
     "historicalConflictDisposition", "legacyCityId", "legacyDisposition", "legacyNodeFingerprint",
     "locationAdjudication", "locationClaimId", "nodeClass", "numericCityId", "parentName",
     "parentRef", "physicalPlaceCorrection", "physicalPlaceRef", "replacementDisposition",
     "reviewState", "routeNodeKey", "seatRole", "selectionRationale", "sourceClaimId"}
    | FORBIDDEN_RUNTIME_NODE_FIELDS
)


def _allowed_keys(document: JsonObject, allowed: frozenset[str] | set[str], label: str) -> None:
    unknown = set(document) - set(allowed)
    if unknown:
        _fail(f"{label} contains unknown fields: {sorted(unknown)}")


def _require_exact_keys(document: JsonObject, expected: set[str], label: str) -> None:
    actual = set(document)
    if actual != expected:
        _fail(
            f"{label} fields must be exact; missing={sorted(expected - actual)} "
            f"unknown={sorted(actual - expected)}"
        )


def _validate_closed_schemas(documents: ValidationDocuments) -> None:
    _allowed_keys(documents.candidate, frozenset({"candidatePolicy", "candidates", "fixedYear", "provenance", "scenarioCatalog", "schemaVersion", "selectionId", "summary"}), "candidate")
    _allowed_keys(_mapping(documents.candidate.get("candidatePolicy"), "candidatePolicy"), frozenset({"automaticSelectionCount", "numericPhysicalPlaceBinding", "ownerAgreement", "reviewState"}), "candidatePolicy")
    candidate_summary = _mapping(documents.candidate.get("summary"), "candidate summary")
    _allowed_keys(candidate_summary, frozenset({"candidateCount", "classificationCounts", "correctionCandidateCount", "externalTileCount", "legacyNodeCount", "numericPhysicalPlaceCount", "originCounts", "replacementPoolCount", "reviewStateCounts", "uniqueCompatibleAdministrativeUnitCount"}), "candidate summary")
    for key, allowed in {
        "classificationCounts": {"EXTERNAL_OR_LATER_OR_MOVING", "HHS_AMBIGUOUS", "HHS_ATTRIBUTION_CONFLICT", "HHS_RESOLVED", "HHS_UNMAPPED"},
        "originCounts": {"CURRENT_780", "HHS_REPLACEMENT_POOL"},
        "reviewStateCounts": {"PENDING"},
    }.items():
        if key in candidate_summary:
            _allowed_keys(_mapping(candidate_summary.get(key), f"candidate summary {key}"), frozenset(allowed), f"candidate summary {key}")
    for row in _rows(documents.candidate, "candidates"):
        candidate_row = _mapping(row, "candidate row")
        _allowed_keys(candidate_row, CANDIDATE_FIELDS, "candidate row")
        if "correctionCandidate" in candidate_row:
            _allowed_keys(_mapping(candidate_row.get("correctionCandidate"), "candidate correctionCandidate"), frozenset({"administrativeUnitId", "correctedName", "correctedParent", "kind", "reviewState"}), "candidate correctionCandidate")
    if "provenance" in documents.candidate:
        candidate_provenance = _mapping(documents.candidate.get("provenance"), "candidate provenance")
        _allowed_keys(candidate_provenance, frozenset({"generator", "inputs", "scenarioResourceCount"}), "candidate provenance")
        candidate_inputs = _mapping(candidate_provenance.get("inputs"), "candidate provenance inputs")
        _allowed_keys(candidate_inputs, frozenset({"administrativeCatalog", "administrativePlaceOverlay", "legacyHanMap", "legacyTileMap"}), "candidate provenance inputs")
        for value in candidate_inputs.values():
            _allowed_keys(_mapping(value, "candidate provenance input"), frozenset({"path", "sha256"}), "candidate provenance input")
    for row in _rows(documents.candidate, "scenarioCatalog"):
        _allowed_keys(_mapping(row, "candidate scenario"), frozenset({"code", "resourcePath", "resourceSha256", "startYear"}), "candidate scenario")

    _allowed_keys(documents.catalog, frozenset({"catalogId", "declaredVsEnumeratedMismatches", "detectedGroupCount", "detectedUnitCount", "expectedGroupCount", "expectedUnitCount", "groups", "schemaVersion", "source", "unitTypeCounts"}), "catalog")
    if "source" in documents.catalog:
        _allowed_keys(_mapping(documents.catalog.get("source"), "catalog source"), frozenset({"book", "fetchContract", "rights", "section", "structureWitness", "traditionalTextWitness", "unitNamePolicy", "volumes"}), "catalog source")
    if "unitTypeCounts" in documents.catalog:
        _allowed_keys(_mapping(documents.catalog.get("unitTypeCounts"), "catalog unitTypeCounts"), frozenset({"COUNTY", "DAO", "MARQUISATE", "TOWN"}), "catalog unitTypeCounts")
    if "declaredVsEnumeratedMismatches" in documents.catalog:
        for row in _rows(documents.catalog, "declaredVsEnumeratedMismatches"):
            _allowed_keys(_mapping(row, "catalog declared mismatch"), frozenset({"canonicalGroup", "declaredCities", "enumeratedUnits", "sourceVolume"}), "catalog declared mismatch")
    for raw_group in _rows(documents.catalog, "groups"):
        group = _mapping(raw_group, "catalog group")
        _allowed_keys(group, frozenset({"canonicalGroup", "declaredCities", "enumeratedUnits", "groupType", "sourceCitation", "sourceGroupName", "sourceVolume", "traditionalTextCitation", "units"}), "catalog group")
        if "sourceCitation" in group:
            _allowed_keys(_mapping(group.get("sourceCitation"), "catalog group sourceCitation"), frozenset({"corpusPath", "line", "snapshotSha256", "sourceUrl"}), "catalog group sourceCitation")
        if "traditionalTextCitation" in group:
            _allowed_keys(_mapping(group.get("traditionalTextCitation"), "catalog group traditionalTextCitation"), frozenset({"localWitness", "snapshotSha256", "source", "url"}), "catalog group traditionalTextCitation")
        for row in _rows(group, "units"):
            unit = _mapping(row, "catalog unit")
            _allowed_keys(unit, frozenset({"canonicalGroup", "nameCorrection", "ordinal", "sourceCitation", "sourceName", "sourceNameIssue", "sourceNameStatus", "sourceVolume", "unitType"}), "catalog unit")
            if "sourceCitation" in unit:
                _allowed_keys(_mapping(unit.get("sourceCitation"), "catalog unit sourceCitation"), frozenset({"corpusPath", "line", "snapshotSha256", "sourceUrl"}), "catalog unit sourceCitation")
            if "nameCorrection" in unit:
                correction = _mapping(unit["nameCorrection"], "catalog nameCorrection")
                _allowed_keys(correction, frozenset({"correctedName", "reason", "sourceCitation", "sourceQuote"}), "catalog nameCorrection")
                _allowed_keys(_mapping(correction.get("sourceCitation"), "catalog nameCorrection sourceCitation"), frozenset({"corpusPath", "line", "snapshotSha256", "sourceUrl"}), "catalog nameCorrection sourceCitation")
            if "sourceNameIssue" in unit:
                issue = _mapping(unit.get("sourceNameIssue"), "catalog sourceNameIssue")
                _allowed_keys(issue, frozenset({"resolutionStatus", "traditionalTextCitation", "witnessText"}), "catalog sourceNameIssue")
                citation = _mapping(issue.get("traditionalTextCitation"), "catalog sourceNameIssue traditionalTextCitation")
                _allowed_keys(citation, frozenset({"endLine", "line", "localWitness", "snapshotSha256", "source", "url"}), "catalog sourceNameIssue traditionalTextCitation")

    _allowed_keys(documents.overlay, frozenset({"administrativeUnits", "catalogId", "schemaVersion", "sourceYear"}), "overlay")
    for raw in _rows(documents.overlay, "administrativeUnits"):
        row = _mapping(raw, "overlay row")
        _allowed_keys(row, frozenset({"administrativeUnitId", "candidateCount", "candidates", "identity", "joinStatus", "selectedCandidate"}), "overlay row")
        _allowed_keys(_mapping(row.get("identity"), "overlay identity"), frozenset({"canonicalGroup", "ordinal", "sourceVolume"}), "overlay identity")
        if "selectedCandidate" in row:
            _allowed_keys(
                _mapping(row["selectedCandidate"], "overlay selectedCandidate"),
                frozenset({"physicalPlaceId", "coordinate", "presentLocation", "recordIndex"}),
                "overlay selectedCandidate",
            )
        if "candidates" in row:
            for value in _rows(row, "candidates"):
                _allowed_keys(
                    _mapping(value, "overlay place candidate"),
                    frozenset({"physicalPlaceId", "coordinate", "presentLocation", "recordIndex"}),
                    "overlay place candidate",
                )

    _allowed_keys(
        documents.external_claims,
        frozenset(
            {"claimSetId", "claims", "policy", "reviewAuthority", "reviewedAt", "reviewedBy",
             "schemaVersion", "status"}
        ),
        "external claims",
    )
    if "policy" in documents.external_claims:
        _allowed_keys(_mapping(documents.external_claims.get("policy"), "external claims policy"), frozenset({"coordinateFieldsCopiedIntoThisArtifact", "externalHistoricalBindingCount", "legacyInfiniteLifecycleAccepted", "purpose", "selectionBasis"}), "external claims policy")

    _allowed_keys(documents.selection, frozenset({"baselineYear", "provenance", "reviewPolicy", "reviewState", "routeNodes", "runtimeScenarioActivationEnforcement", "scenarioCatalog", "schemaVersion", "selectionId", "summary"}), "selection")
    for raw in _rows(documents.selection, "routeNodes"):
        node = _mapping(raw, "route node")
        _allowed_keys(node, ROUTE_NODE_FIELDS, "route node")
        _allowed_keys(_mapping(node.get("selectionRationale"), "selectionRationale"), frozenset({"batchId", "evidenceRefs", "method", "rationale", "reviewPolicyId"}), "selectionRationale")
        for key, allowed in {
            "locationAdjudication": {"evidenceRefs", "kind", "rationale", "rationaleCode", "rejectedPhysicalPlaceRefs", "selectedPhysicalPlaceRef", "sourceClaimId"},
            "historicalConflictDisposition": {"evidenceRefs", "rationale", "rejectedAdministrativeUnitIds", "selectedBindingRef"},
            "physicalPlaceCorrection": {"evidenceRefs", "fromPhysicalPlaceRef", "rationale", "toPhysicalPlaceRef"},
            "replacementDisposition": {"evidenceRefs", "rationale"},
        }.items():
            if key in node:
                _allowed_keys(_mapping(node[key], key), frozenset(allowed), key)
    scenario_catalog = _mapping(documents.selection.get("scenarioCatalog"), "scenarioCatalog")
    _allowed_keys(scenario_catalog, frozenset({"resourceCount", "resources"}), "scenarioCatalog")
    for row in _rows(scenario_catalog, "resources"):
        _allowed_keys(_mapping(row, "scenario resource"), frozenset({"resourceName", "resourcePath", "scenarioId", "sha256", "startYear"}), "scenario resource")
    selection_provenance = _mapping(documents.selection.get("provenance"), "selection provenance")
    _allowed_keys(selection_provenance, frozenset({"generator", "inputs"}), "selection provenance")
    selection_inputs = _mapping(selection_provenance.get("inputs"), "selection provenance inputs")
    _allowed_keys(selection_inputs, frozenset({"administrativeCatalog", "administrativePlaceOverlay", "candidate", "candidateConnections", "externalClaims", "legacyHanMap", "legacyTileMap", "locationAdjudications", "reviewPolicy", "routeNodeKeyRegistry"}), "selection provenance inputs")
    for value in selection_inputs.values():
        _allowed_keys(_mapping(value, "selection provenance input"), frozenset({"sha256"}), "selection provenance input")
    selection_summary = _mapping(documents.selection.get("summary"), "selection summary")
    _allowed_keys(selection_summary, frozenset({"approvedCount", "historicalBindingCounts"}), "selection summary")
    if "historicalBindingCounts" in selection_summary:
        _allowed_keys(_mapping(selection_summary.get("historicalBindingCounts"), "historicalBindingCounts"), frozenset({"HHS_ADMINISTRATIVE_UNIT"}), "historicalBindingCounts")
    review_policy = _mapping(documents.selection.get("reviewPolicy"), "selection reviewPolicy")
    _allowed_keys(review_policy, frozenset({"forbiddenSelections", "legacyAttributionCorrections", "numericCityIdChangeAllowed", "policyId", "reviewDecisionAnchors"}), "selection reviewPolicy")
    if "reviewDecisionAnchors" in review_policy:
        anchors = _mapping(review_policy.get("reviewDecisionAnchors"), "reviewDecisionAnchors")
        _allowed_keys(anchors, frozenset({"historicalConflictDecisionSet", "replacementDecisionSet"}), "reviewDecisionAnchors")
        for value in anchors.values():
            _allowed_keys(_mapping(value, "review decision anchor"), frozenset({"anchor", "assignmentSha256", "reviewState", "rowCount"}), "review decision anchor")
    _allowed_keys(_mapping(review_policy.get("forbiddenSelections"), "selection forbiddenSelections"), frozenset({"canonicalNames", "nodeClasses", "physicalPlaceIds"}), "selection forbiddenSelections")

    _allowed_keys(documents.migration, frozenset({"migrationId", "mode", "referenceInventory", "rewriteSurfaces", "rows", "schemaVersion", "sourceCandidateSha256", "sourceSelectionId", "sourceSelectionSha256", "summary"}), "migration")
    _allowed_keys(_mapping(documents.migration.get("summary"), "migration summary"), frozenset({"displayNameChangeCount", "historicalBindingCorrectionCount", "numericCityIdChangeCount", "parentChangeCount", "physicalPlaceCorrectionCount", "routeNodeReplacementCount", "rowCount", "seatRoleChangeCount"}), "migration summary")
    for row in _rows(documents.migration, "rows"):
        _allowed_keys(_mapping(row, "migration row"), frozenset({"disposition", "newCityId", "oldCityId", "oldNodeFingerprint", "routeNodeKey"}), "migration row")
    _allowed_keys(_mapping(documents.migration.get("referenceInventory"), "migration referenceInventory"), frozenset({"derivedReseed", "immutableAudit", "inPlaceRewrite", "mutable", "unknownPayloadPolicy"}), "migration referenceInventory")
    rewrite_surfaces = _mapping(documents.migration.get("rewriteSurfaces"), "migration rewriteSurfaces")
    _allowed_keys(rewrite_surfaces, frozenset({"derivedArtifacts", "immutableAudit", "scenarioResources"}), "migration rewriteSurfaces")
    for name, allowed in {
        "derivedArtifacts": {"disposition", "otherwise", "surfaces"},
        "immutableAudit": {"disposition", "surfaces"},
        "scenarioResources": {"disposition", "otherwise", "surfaces"},
    }.items():
        _allowed_keys(_mapping(rewrite_surfaces.get(name), f"migration rewriteSurfaces {name}"), frozenset(allowed), f"migration rewriteSurfaces {name}")
    _allowed_keys(documents.route_key_registry, frozenset({"issuanceAuthority", "issuedAt", "issuedBy", "keyPolicy", "keys", "registryId", "schemaVersion", "status"}), "route-node key registry")
    _allowed_keys(_mapping(documents.route_key_registry.get("keyPolicy"), "route-node key policy"), frozenset({"derivedFromAdministrativeIdentity", "derivedFromNumericCityId", "derivedFromPhysicalPlace", "derivedFromSourceClaim", "format", "note", "rebindingChangesKey"}), "route-node key policy")
    for row in _rows(documents.route_key_registry, "keys"):
        _allowed_keys(_mapping(row, "route-node key registry row"), frozenset({"initialAdministrativeUnitId", "issuanceReason", "routeNodeKey"}), "route-node key registry row")
POINT_REFERENCE = re.compile(
    r"^(?:chgis:v6:cnty:[^:\s]+|external:v1:[^:\s]+|wikidata:Q[1-9][0-9]*|curated:[a-z0-9][a-z0-9:_-]*)$"
)
SHA256 = re.compile(r"^[0-9a-f]{64}$")
ROUTE_SUBJECT_TYPES = frozenset({"AdministrativePlace", "AnchoredPlace"})
CANDIDATE_CLASSIFICATIONS = frozenset(
    {
        "HHS_RESOLVED",
        "HHS_AMBIGUOUS",
        "HHS_UNMAPPED",
        "HHS_ATTRIBUTION_CONFLICT",
        "EXTERNAL_OR_LATER_OR_MOVING",
    }
)
JsonValue = str | int | float | bool | None | list["JsonValue"] | dict[str, "JsonValue"]
JsonObject = dict[str, JsonValue]


class SelectionContractError(ValueError):
    __slots__ = ("message",)

    def __init__(self, message: str) -> None:
        self.message = message
        super().__init__(message)

    def __str__(self) -> str:
        return self.message


@dataclass(frozen=True, slots=True)
class ScenarioResource:
    scenario_id: str
    resource_name: str
    resource_path: str
    start_year: int
    sha256: str


@dataclass(frozen=True, slots=True)
class CatalogUnit:
    source_name_status: str
    node_class: str
    canonical_name: str
    parent_name: str
    parent_ref: str
    seat_role: str


@dataclass(frozen=True, slots=True)
class ValidationDocuments:
    candidate: JsonObject
    catalog: JsonObject
    overlay: JsonObject
    external_claims: JsonObject
    selection: JsonObject
    migration: JsonObject
    route_key_registry: JsonObject
    scenarios: tuple[ScenarioResource, ...]
    candidate_sha256: str
    catalog_sha256: str
    overlay_sha256: str
    claims_sha256: str
    selection_sha256: str
    migration_sha256: str
    route_key_registry_sha256: str
    source_root: Path
    connections: JsonObject | None
    connections_sha256: str | None
    production_approval_mode: bool = False


@dataclass(frozen=True, slots=True)
class ValidationReport:
    approved_count: int
    scenario_count: int
    ambiguous_adjudication_count: int
    location_claim_count: int
    external_binding_count: int
    physical_place_correction_count: int
    selection_sha256: str
    migration_sha256: str


@dataclass(frozen=True, slots=True)
class ValidationPaths:
    candidate: Path
    catalog: Path
    overlay: Path
    external_claims: Path
    selection: Path
    migration: Path
    scenarios_dir: Path
    connections: Path | None
    source_root: Path = ROOT


@dataclass(frozen=True, slots=True)
class MigrationDerivation:
    physical_place_correction_count: int
    historical_binding_correction_ids: frozenset[int]


@dataclass(frozen=True, slots=True)
class SelectionIndexes:
    candidates: dict[int, JsonObject]
    nodes: dict[int, JsonObject]
    claims: dict[str, JsonObject]


def _fail(message: str) -> NoReturn:
    raise SelectionContractError(message)


def _require_schema_version_one(document: JsonObject, label: str) -> None:
    if type(document.get("schemaVersion")) is not int or document.get("schemaVersion") != 1:
        _fail(f"{label} schemaVersion must be exact integer 1")


def _mapping(value: JsonValue, label: str) -> JsonObject:
    if not isinstance(value, dict):
        _fail(f"{label} must be an object")
    return value


def _rows(document: JsonObject, key: str) -> list[JsonValue]:
    value = document.get(key)
    if not isinstance(value, list):
        _fail(f"{key} must be an array")
    return value


def _text(document: JsonObject, key: str) -> str:
    value = document.get(key)
    if not isinstance(value, str) or not value:
        _fail(f"{key} must be a non-empty string")
    return value


def _integer(document: JsonObject, key: str) -> int:
    value = document.get(key)
    if not isinstance(value, int) or isinstance(value, bool):
        _fail(f"{key} must be an integer")
    return value


def _sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _load(path: Path) -> JsonObject:
    return _mapping(json.loads(path.read_text(encoding="utf-8")), path.name)


def _resolved_data_path(source_root: Path, relative_path: Path, label: str) -> Path:
    repository_root = source_root.resolve()
    data_root = (source_root / "data").resolve()
    try:
        data_root.relative_to(repository_root)
    except ValueError:
        _fail("source-root data/ escapes repository root")
    target = (source_root / relative_path).resolve()
    try:
        target.relative_to(data_root)
    except ValueError:
        _fail(f"{label} escapes source-root data/")
    return target


def _resolved_repository_path(source_root: Path, relative_path: Path, label: str) -> Path:
    repository_root = source_root.resolve()
    target = (source_root / relative_path).resolve()
    try:
        target.relative_to(repository_root)
    except ValueError:
        _fail(f"{label} escapes repository root")
    return target


def _resolved_corpus_path(source_root: Path, relative_path: Path, label: str) -> Path:
    repository_root = source_root.resolve()
    corpus_root = (source_root / "data/corpus").resolve()
    try:
        corpus_root.relative_to(repository_root)
    except ValueError:
        _fail("source-root data/corpus escapes repository root")
    target = (source_root / relative_path).resolve()
    try:
        target.relative_to(corpus_root)
    except ValueError:
        _fail(f"{label} escapes source-root data/corpus")
    return target


def _forbid_fields(value: JsonValue, path: str = "root") -> None:
    if isinstance(value, dict):
        for key, child in value.items():
            if key in FORBIDDEN_FIELDS:
                _fail(f"forbidden tracked field {key} at {path}")
            _forbid_fields(child, f"{path}.{key}")
        return
    if isinstance(value, list):
        for index, child in enumerate(value):
            _forbid_fields(child, f"{path}[{index}]")


def _review_reason(document: dict, label: str) -> None:
    method = document.get("method")
    rationale = _text(document, "rationale")
    evidence = document.get("evidenceRefs")
    searchable = f"{method or ''} {rationale}"
    match = FORBIDDEN_RATIONALE.search(searchable)
    if match is not None:
        _fail(f"{label} cannot use nearest/distance automation: {match.group(0)}")
    if not isinstance(evidence, list) or not evidence or any(not isinstance(row, str) or not row for row in evidence):
        _fail(f"{label} requires non-empty evidenceRefs")


def _decision_digest(rows_to_hash: list[JsonObject]) -> str:
    payload = json.dumps(
        rows_to_hash, ensure_ascii=False, sort_keys=True, separators=(",", ":")
    ) + "\n"
    return hashlib.sha256(payload.encode("utf-8")).hexdigest()


def _validate_review_decision_anchors(
    nodes: list[JsonObject], review_policy: JsonObject, production: bool
) -> None:
    replacements = [
        {key: node[key] for key in ("legacyCityId", "administrativeUnitId", "physicalPlaceRef", "routeNodeKey")}
        for node in nodes
        if node.get("legacyDisposition") == "REPLACED"
    ]
    conflicts = [
        {
            "legacyCityId": node["legacyCityId"],
            "administrativeUnitId": node["administrativeUnitId"],
            "selectedBindingRef": _mapping(
                node.get("historicalConflictDisposition"), "historicalConflictDisposition"
            )["selectedBindingRef"],
            "rejectedAdministrativeUnitIds": _mapping(
                node.get("historicalConflictDisposition"), "historicalConflictDisposition"
            )["rejectedAdministrativeUnitIds"],
        }
        for node in nodes
        if "historicalConflictDisposition" in node
    ]
    if not replacements and not conflicts and "reviewDecisionAnchors" not in review_policy:
        return
    anchors = _mapping(review_policy.get("reviewDecisionAnchors"), "reviewDecisionAnchors")
    specifications = (
        ("replacementDecisionSet", replacements, PINNED_REPLACEMENT_DECISION_SHA256, "replacementDisposition"),
        ("historicalConflictDecisionSet", conflicts, PINNED_CONFLICT_DECISION_SHA256, "historicalConflictDisposition"),
    )
    for name, decisions, production_digest, disposition_name in specifications:
        anchor = _mapping(anchors.get(name), name)
        digest = _decision_digest(decisions)
        expected_anchor = f"{name}:{digest}"
        if (
            anchor.get("reviewState") != "APPROVED"
            or anchor.get("rowCount") != len(decisions)
            or anchor.get("assignmentSha256") != digest
            or anchor.get("anchor") != expected_anchor
        ):
            _fail(f"{name} must exact-match the reviewed assignment set")
        if production and digest != production_digest:
            _fail(f"{name} does not match the independent production anchor")
        for node in nodes:
            disposition = node.get(disposition_name)
            if isinstance(disposition, dict) and disposition.get("evidenceRefs") != [
                REVIEW_POLICY_PATH,
                expected_anchor,
            ]:
                _fail(f"{disposition_name} evidenceRefs must resolve to its reviewed decision set")


def _validate_selection_rationale(document: JsonObject, expected_batch_id: str) -> None:
    _review_reason(document, "selectionRationale")
    batch_id = document.get("batchId")
    if (
        document.get("method") != "APPROVED_REVIEW_BATCH"
        or document.get("reviewPolicyId") != REVIEW_POLICY_ID
        or batch_id not in REVIEW_BATCH_IDS
        or document.get("evidenceRefs") != [REVIEW_POLICY_PATH, batch_id]
    ):
        _fail("selectionRationale evidenceRefs must exact-match its pinned review policy and batch")
    if document.get("rationale") != EXPECTED_SELECTION_RATIONALE:
        _fail("selectionRationale rationale must match the canonical materializer output")
    if batch_id != expected_batch_id:
        _fail("selectionRationale review batch does not match the validated binding route")


def _route_key_registry_index(registry: JsonObject) -> dict[str, str]:
    _require_schema_version_one(registry, "route-node key registry")
    rows = [_mapping(value, "route-node key registry row") for value in _rows(registry, "keys")]
    if len(rows) != EXPECTED_COUNT:
        _fail("route-node key registry must contain exactly 780 rows")
    indexed: dict[str, str] = {}
    keys: set[str] = set()
    for row in rows:
        administrative_id = _text(row, "initialAdministrativeUnitId")
        route_key = _text(row, "routeNodeKey")
        if administrative_id in indexed or route_key in keys:
            _fail("route-node key registry identities and keys must be unique")
        indexed[administrative_id] = route_key
        keys.add(route_key)
    return indexed


def _catalog_index(catalog: dict, require_full_contract: bool = False) -> dict[str, CatalogUnit]:
    units: dict[str, CatalogUnit] = {}
    groups = [_mapping(value, "catalog group") for value in _rows(catalog, "groups")]
    full_contract = require_full_contract or "expectedGroupCount" in catalog
    if full_contract:
        if not (
            catalog.get("expectedGroupCount")
            == catalog.get("detectedGroupCount")
            == len(groups)
            == 105
        ):
            _fail("catalog declared group counts do not match")
        source = _mapping(catalog.get("source"), "catalog source")
        if source.get("volumes") != [109, 110, 111, 112, 113]:
            _fail("catalog source volumes must be exact 109..113")
    mismatches: list[JsonObject] = []
    unit_type_counts: Counter[str] = Counter()
    group_identities: set[tuple[int, str]] = set()
    for group in groups:
        canonical = _text(group, "canonicalGroup")
        volume = _integer(group, "sourceVolume")
        if volume not in range(109, 114) or (volume, canonical) in group_identities:
            _fail("catalog group identity is invalid or duplicated")
        group_identities.add((volume, canonical))
        group_units = _rows(group, "units")
        if full_contract:
            declared = _integer(group, "declaredCities")
            enumerated = _integer(group, "enumeratedUnits")
            if enumerated != len(group_units):
                _fail("catalog group enumeratedUnits does not match its rows")
            if declared != enumerated:
                mismatches.append({
                    "sourceVolume": volume,
                    "canonicalGroup": canonical,
                    "declaredCities": declared,
                    "enumeratedUnits": enumerated,
                })
            _validate_catalog_citation(group.get("sourceCitation"), "catalog group sourceCitation")
            _validate_catalog_citation(
                group.get("traditionalTextCitation"), "catalog group traditionalTextCitation"
            )
        for expected_ordinal, raw_unit in enumerate(group_units, start=1):
            unit = _mapping(raw_unit, "catalog unit")
            ordinal = _integer(unit, "ordinal")
            if (
                ordinal != expected_ordinal
                or unit.get("canonicalGroup") != canonical
                or unit.get("sourceVolume") != volume
            ):
                _fail("catalog administrative identity is malformed")
            unit_id = f"hhs:{volume}:{canonical}:{ordinal:03d}"
            source_name = unit.get("sourceName")
            if not isinstance(source_name, str) or not source_name:
                _fail(f"catalog sourceName is invalid: {unit_id}")
            source_name_status = unit.get("sourceNameStatus")
            if source_name_status not in {"SOURCE_LITERAL", "SOURCE_PLACEHOLDER"}:
                _fail(f"catalog sourceNameStatus is invalid: {unit_id}")
            unit_type = unit.get("unitType")
            if unit_type not in NODE_CLASS_BY_UNIT_TYPE:
                _fail(f"catalog unitType is invalid: {unit_id}")
            unit_type_counts[unit_type] += 1
            if full_contract:
                _validate_catalog_citation(unit.get("sourceCitation"), "catalog unit sourceCitation")
            correction = unit.get("nameCorrection")
            canonical_name = (
                source_name
                if correction is None
                else _text(_mapping(correction, "catalog nameCorrection"), "correctedName")
            )
            if full_contract and correction is not None:
                _validate_catalog_citation(
                    _mapping(correction, "catalog nameCorrection").get("sourceCitation"),
                    "catalog nameCorrection sourceCitation",
                )
            source_name_issue = unit.get("sourceNameIssue")
            if full_contract and source_name_issue is not None:
                _validate_catalog_citation(
                    _mapping(source_name_issue, "catalog sourceNameIssue").get(
                        "traditionalTextCitation"
                    ),
                    "catalog sourceNameIssue traditionalTextCitation",
                )
            if unit_id in units:
                _fail(f"duplicate administrative unit: {unit_id}")
            units[unit_id] = CatalogUnit(
                source_name_status=source_name_status,
                node_class=NODE_CLASS_BY_UNIT_TYPE[unit_type],
                canonical_name=canonical_name,
                parent_name=canonical,
                parent_ref=f"hhs-group:{volume}:{canonical}",
                seat_role="COMMANDERY_SEAT" if ordinal == 1 else "NON_SEAT",
            )
    if (
        catalog.get("expectedUnitCount") != len(units)
        or catalog.get("detectedUnitCount") != len(units)
        or (full_contract and len(units) != 1180)
    ):
        _fail("catalog declared unit counts do not match")
    if full_contract:
        if catalog.get("unitTypeCounts") != dict(unit_type_counts):
            _fail("catalog unitTypeCounts do not match catalog rows")
        if catalog.get("declaredVsEnumeratedMismatches") != mismatches:
            _fail("catalog declared/enumerated mismatch inventory does not match rows")
    return units


def _validate_catalog_citation(value: JsonValue, label: str) -> None:
    citation = _mapping(value, label)
    if "traditionalTextCitation" in label:
        expected = {"source", "url", "localWitness", "snapshotSha256"}
        if "sourceNameIssue" in label:
            expected.add("line")
            if "endLine" in citation:
                expected.add("endLine")
    else:
        expected = {"corpusPath", "line", "snapshotSha256", "sourceUrl"}
    _require_exact_keys(citation, expected, label)
    snapshot = _text(citation, "snapshotSha256")
    if re.fullmatch(r"[0-9a-f]{64}", snapshot) is None:
        _fail(f"{label} snapshotSha256 is malformed")
    for key in expected - {"snapshotSha256", "line", "endLine"}:
        _text(citation, key)
    if "line" in expected:
        line = _integer(citation, "line")
        if line <= 0:
            _fail(f"{label} line must be positive")
    if "endLine" in expected:
        end_line = _integer(citation, "endLine")
        if end_line < line:
            _fail(f"{label} endLine must not precede line")


def _overlay_index(overlay: dict, catalog: dict[str, CatalogUnit]) -> dict[str, tuple[str, tuple[str, ...]]]:
    if overlay.get("sourceYear") != 220:
        _fail("administrative overlay sourceYear must be 220")
    indexed: dict[str, tuple[str, tuple[str, ...]]] = {}
    for raw in _rows(overlay, "administrativeUnits"):
        row = _mapping(raw, "overlay row")
        unit_id = _text(row, "administrativeUnitId")
        if unit_id not in catalog or unit_id in indexed:
            _fail(f"unknown or duplicate overlay administrative unit: {unit_id}")
        match = re.fullmatch(r"hhs:(\d+):(.+):(\d{3})", unit_id)
        expected_identity = (
            None
            if match is None
            else {
                "sourceVolume": int(match.group(1)),
                "canonicalGroup": match.group(2),
                "ordinal": int(match.group(3)),
            }
        )
        if row.get("identity") != expected_identity:
            _fail(f"overlay identity does not exact-match administrativeUnitId: {unit_id}")
        status = _text(row, "joinStatus")
        base_fields = {"administrativeUnitId", "candidateCount", "identity", "joinStatus"}
        if status == "RESOLVED_POINT":
            _require_exact_keys(row, base_fields | {"selectedCandidate"}, "RESOLVED_POINT overlay row")
            candidates = [_mapping(row.get("selectedCandidate"), "selectedCandidate")]
        elif status == "AMBIGUOUS_POINT":
            _require_exact_keys(row, base_fields | {"candidates"}, "AMBIGUOUS_POINT overlay row")
            candidates = [_mapping(value, "overlay candidate") for value in _rows(row, "candidates")]
        elif status in {"NO_COORDINATE_CANDIDATE", "SOURCE_PLACEHOLDER"}:
            _require_exact_keys(row, base_fields, f"{status} overlay row")
            candidates = []
        else:
            _fail(f"unsupported overlay joinStatus: {status}")
        refs = tuple(_text(value, "physicalPlaceId") for value in candidates)
        if len(refs) != len(set(refs)) or row.get("candidateCount") != len(refs):
            _fail(f"overlay candidate identity/count mismatch: {unit_id}")
        indexed[unit_id] = (status, refs)
    if set(indexed) != set(catalog):
        _fail("administrative overlay must cover the catalog exactly")
    return indexed


def _candidate_index(candidate: dict, catalog: dict[str, CatalogUnit]) -> dict[int, dict]:
    policy = _mapping(candidate.get("candidatePolicy"), "candidatePolicy")
    if policy.get("reviewState") != "PENDING" or policy.get("automaticSelectionCount") != 0:
        _fail("candidate policy must remain PENDING with automaticSelectionCount 0")
    _forbid_fields(candidate, "candidate")
    rows = [_mapping(value, "candidate row") for value in _rows(candidate, "candidates")]
    summary = _mapping(candidate.get("summary"), "candidate summary")
    current_rows = [row for row in rows if row.get("origin") == "CURRENT_780"]
    replacement_rows = [row for row in rows if row.get("origin") == "HHS_REPLACEMENT_POOL"]
    if (
        len(rows) != 1960
        or len(current_rows) != EXPECTED_COUNT
        or len(replacement_rows) != 1180
        or summary.get("candidateCount") != 1960
        or summary.get("legacyNodeCount") != EXPECTED_COUNT
        or summary.get("replacementPoolCount") != 1180
    ):
        _fail("candidate artifact must contain 780 current and 1,180 replacement rows")
    if any(row.get("reviewState") != "PENDING" for row in rows):
        _fail("every candidate row must remain PENDING")
    for row in replacement_rows:
        unit_id = _text(row, "administrativeUnitId")
        if unit_id not in catalog:
            _fail(f"replacement candidate contains a dangling administrative identity: {unit_id}")
        refs = row.get("physicalPlaceRefs")
        join_status = row.get("overlayJoinStatus")
        if refs is None and join_status in {"NO_COORDINATE_CANDIDATE", "SOURCE_PLACEHOLDER"}:
            continue
        if not isinstance(refs, list) or any(not isinstance(ref, str) for ref in refs):
            _fail("replacement candidate physicalPlaceRefs must be an array of point references")
    ids = [_integer(row, "legacyCityId") for row in current_rows]
    if sorted(ids) != list(range(1, EXPECTED_COUNT + 1)):
        _fail("candidate legacyCityId values must be exact 1..780")
    fingerprints = [_text(row, "legacyNodeFingerprint") for row in current_rows]
    if len(fingerprints) != len(set(fingerprints)):
        _fail("candidate legacy fingerprints must be unique")
    if any(re.fullmatch(r"sha256:[0-9a-f]{64}", value) is None for value in fingerprints):
        _fail("candidate legacy fingerprint must be canonical sha256:<hex>")
    for row in current_rows:
        classification = row.get("classification")
        if classification not in CANDIDATE_CLASSIFICATIONS:
            _fail(f"candidate classification is invalid: {classification}")
        references: list[str] = []
        proposed = row.get("proposedAdministrativeUnitId")
        if isinstance(proposed, str):
            references.append(proposed)
        for key in ("candidateAdministrativeUnitIds", "incompatibleAdministrativeUnitIds"):
            value = row.get(key)
            if isinstance(value, list):
                if any(not isinstance(reference, str) for reference in value):
                    _fail(f"candidate {key} must contain administrative ids")
                references.extend(value)
        correction = row.get("correctionCandidate")
        if isinstance(correction, dict):
            references.append(_text(correction, "administrativeUnitId"))
        if any(reference not in catalog for reference in references):
            _fail("candidate contains a dangling administrative identity")
        if classification == "HHS_RESOLVED" and not isinstance(proposed, str):
            _fail("HHS_RESOLVED candidate requires proposedAdministrativeUnitId")
        ambiguous = row.get("candidateAdministrativeUnitIds")
        if classification == "HHS_AMBIGUOUS" and (
            not isinstance(ambiguous, list) or not ambiguous
        ):
            _fail("HHS_AMBIGUOUS candidate requires candidateAdministrativeUnitIds")
        incompatible = row.get("incompatibleAdministrativeUnitIds")
        if classification == "HHS_ATTRIBUTION_CONFLICT" and (
            not isinstance(incompatible, list) or not incompatible
        ):
            _fail("HHS_ATTRIBUTION_CONFLICT candidate requires incompatibleAdministrativeUnitIds")
        if classification == "EXTERNAL_OR_LATER_OR_MOVING" and not isinstance(
            row.get("externalPlaceRef"), str
        ):
            _fail("EXTERNAL_OR_LATER_OR_MOVING candidate requires externalPlaceRef")
    return dict(zip(ids, current_rows, strict=True))


def _verify_source_record(record: JsonObject, claim_id: str, source_root: Path) -> None:
    corpus_path = Path(_text(record, "corpusPath"))
    if corpus_path.is_absolute() or corpus_path.parts[:2] != ("data", "corpus") or ".." in corpus_path.parts:
        _fail(f"external claim source record path must be repo-relative data/corpus: {claim_id}")
    source = _resolved_corpus_path(source_root, corpus_path, f"external claim source record: {claim_id}")
    if not source.is_file():
        witness_path = _resolved_data_path(
            source_root,
            Path("data/curated/han/route-node-source-witness-v1.json"),
            "external claim witness ledger",
        )
        if not witness_path.is_file():
            _fail(f"external claim source record has no source-root witness ledger: {claim_id}")
        expected = {"sourceClaimId": claim_id, **record}
        if expected not in _rows(_load(witness_path), "records"):
            _fail(f"external claim source record has no curated witness: {claim_id}")
        return
    if _text(record, "snapshotSha256") != _sha256(source):
        _fail(f"external claim source record hash does not match: {claim_id}")
    line_start, line_end = _integer(record, "lineStart"), _integer(record, "lineEnd")
    lines = source.read_text(encoding="utf-8").splitlines()
    if line_start <= 0 or line_end < line_start or line_end > len(lines):
        _fail(f"external claim source record line range is invalid: {claim_id}")
    if _text(record, "verbatim") not in "\n".join(lines[line_start - 1:line_end]):
        _fail(f"external claim source record verbatim does not match: {claim_id}")


def _claims_index(claims_document: dict, source_root: Path) -> dict[str, dict]:
    _forbid_fields(claims_document, "externalClaims")
    indexed: dict[str, dict] = {}
    claim_rows = _rows(claims_document, "claims")
    if len(claim_rows) != 8:
        _fail("external claim set must contain exactly 8 LOCATION_ONLY claims")
    for raw in claim_rows:
        claim = _mapping(raw, "external claim")
        claim_id = _text(claim, "sourceClaimId")
        if set(claim) != LOCATION_ONLY_CLAIM_FIELDS:
            _fail(f"LOCATION_ONLY external claim fields must be exact: {claim_id}")
        if claim_id in indexed:
            _fail(f"duplicate sourceClaimId: {claim_id}")
        if claim.get("reviewState") != "APPROVED":
            _fail(f"external claim must be APPROVED: {claim_id}")
        if claim.get("claimRole") != "LOCATION_ONLY":
            _fail(f"external claim must be LOCATION_ONLY: {claim_id}")
        subject_type = claim.get("subjectType")
        if subject_type != "ADMINISTRATIVE_PLACE":
            _fail(f"external claim subjectType {subject_type} cannot be used by LOCATION_ONLY: {claim_id}")
        subject_key = _text(claim, "subjectKey")
        subject_name = _text(claim, "canonicalName")
        if claim.get("selectionReviewCoverage") != "W0_ROUTE_NODE_PLACE_IDENTITY_ONLY":
            _fail(f"external claim must be identity-only W0 review coverage: {claim_id}")
        aliases = claim.get("aliases")
        if not isinstance(aliases, list) or any(not isinstance(value, str) for value in aliases):
            _fail(f"external claim aliases must be a string array: {claim_id}")
        if claim.get("reviewEvidenceRefs") != list(IDENTITY_REVIEW_EVIDENCE_REFS):
            _fail(f"external claim reviewEvidenceRefs must exact-match identity review inputs: {claim_id}")
        resolution = _mapping(claim.get("locationResolution"), "locationResolution")
        if set(resolution) != LOCATION_RESOLUTION_FIELDS:
            _fail(f"LOCATION_ONLY locationResolution fields must be exact: {claim_id}")
        if resolution.get("kind") != "POINT_REF":
            _fail(f"LOCATION_ONLY claim requires POINT_REF: {claim_id}")
        point_ref = _text(resolution, "physicalPlaceId")
        if POINT_REFERENCE.fullmatch(point_ref) is None:
            _fail(f"external claim physicalPlaceRef is not a point reference: {point_ref}")
        dataset_ref = _mapping(resolution.get("coordinateDatasetRef"), "coordinateDatasetRef")
        if set(dataset_ref) != COORDINATE_DATASET_FIELDS:
            _fail(f"LOCATION_ONLY coordinateDatasetRef fields must be exact: {claim_id}")
        dataset_path = Path(_text(dataset_ref, "datasetPath"))
        if dataset_path.is_absolute() or dataset_path.parts[:1] != ("data",) or ".." in dataset_path.parts:
            _fail(f"external claim authority path must be repo-relative data/: {claim_id}")
        authority_path = _resolved_data_path(
            source_root, dataset_path, f"external claim authority: {claim_id}"
        )
        if not authority_path.is_file() or dataset_ref.get("datasetSha256") != _sha256(authority_path):
            _fail(f"external claim authority hash does not match: {claim_id}")
        authority = _load(authority_path)
        record_id = _text(dataset_ref, "recordId")
        matches = [
            _mapping(row, "external authority record")
            for row in _rows(authority, "records")
            if isinstance(row, dict) and row.get("recordId") == record_id
        ]
        if len(matches) != 1:
            _fail(f"external claim authority recordId does not resolve uniquely: {claim_id}")
        authority_row = matches[0]
        claim_authority_values = {
            "wikidataId": dataset_ref.get("wikidataId"), "physicalPlaceId": point_ref,
            "subjectKey": subject_key, "canonicalName": subject_name,
        }
        for field in AUTHORITY_EXACT_FIELDS:
            if authority_row.get(field) != claim_authority_values.get(field):
                label = "Wikidata identity" if field == "wikidataId" else field
                _fail(f"external claim authority {label} does not match: {claim_id}")
        records = [_mapping(value, "source record") for value in _rows(claim, "sourceRecords")]
        if not records:
            _fail(f"external claim requires sourceRecords: {claim_id}")
        for record in records:
            if set(record) != SOURCE_RECORD_FIELDS:
                _fail(f"LOCATION_ONLY sourceRecord fields must be exact: {claim_id}")
            _text(record, "sourceBook")
            _integer(record, "volume")
            _verify_source_record(record, claim_id, source_root)
        conflict = _mapping(claim.get("conflictDisposition"), "conflictDisposition")
        if set(conflict) != IDENTITY_CONFLICT_FIELDS:
            _fail(f"identity-only conflictDisposition fields must be exact: {claim_id}")
        if conflict.get("rationaleCode") != "PLACE_IDENTITY_ONLY":
            _fail(f"identity-only conflictDisposition rationaleCode is invalid: {claim_id}")
        if conflict.get("status") not in {"NONE", "ADJUDICATED"}:
            _fail(f"external conflict disposition is invalid: {claim_id}")
        conflict_rationale = _text(conflict, "rationale")
        if FORBIDDEN_IDENTITY_LIFECYCLE.search(conflict_rationale) is not None:
            _fail(f"identity-only external claim rationale cannot assert lifecycle: {claim_id}")
        indexed[claim_id] = {
            **claim,
            "claimRole": "LOCATION",
            "subjectType": "AdministrativePlace",
            "subjectKey": subject_key,
            "subjectName": subject_name,
            "physicalPlaceRef": point_ref,
        }
    return indexed


def _scenario_catalog(
    selection: dict, candidate: dict, scenarios: tuple[ScenarioResource, ...]
) -> dict[str, ScenarioResource]:
    if len(scenarios) != EXPECTED_SCENARIOS:
        _fail("actual han scenario catalog must contain exactly 15 active resources")
    actual = {row.scenario_id: row for row in scenarios}
    if len(actual) != EXPECTED_SCENARIOS or len({row.resource_name for row in scenarios}) != EXPECTED_SCENARIOS:
        _fail("actual han scenario identifiers/resources must be unique")
    catalog = _mapping(selection.get("scenarioCatalog"), "scenarioCatalog")
    declared_rows = [_mapping(value, "scenario catalog row") for value in _rows(catalog, "resources")]
    if catalog.get("resourceCount") != EXPECTED_SCENARIOS or len(declared_rows) != EXPECTED_SCENARIOS:
        _fail("selection scenario catalog must contain exactly 15 active resources")
    declared = {
        _text(row, "scenarioId"): (
            _text(row, "resourceName"),
            _text(row, "resourcePath"),
            _integer(row, "startYear"),
            _text(row, "sha256"),
        )
        for row in declared_rows
    }
    expected = {
        key: (row.resource_name, row.resource_path, row.start_year, row.sha256)
        for key, row in actual.items()
    }
    if declared != expected:
        _fail("selection scenario catalog does not exact-match actual resource names/dates/hashes")
    if "scenarioCatalog" in candidate:
        candidate_rows = [_mapping(value, "candidate scenario row") for value in _rows(candidate, "scenarioCatalog")]
        candidate_codes = [_text(row, "code") for row in candidate_rows]
        candidate_paths = [_text(row, "resourcePath") for row in candidate_rows]
        if (
            len(candidate_rows) != EXPECTED_SCENARIOS
            or len(set(candidate_codes)) != EXPECTED_SCENARIOS
            or len(set(candidate_paths)) != EXPECTED_SCENARIOS
        ):
            _fail("candidate scenario catalog must contain 15 unique codes and resource paths")
        candidate_declared = {
            _text(row, "code"): (
                _text(row, "resourcePath"),
                _integer(row, "startYear"),
                _text(row, "resourceSha256"),
            )
            for row in candidate_rows
        }
        candidate_expected = {
            key: (row.resource_path, row.start_year, row.sha256) for key, row in actual.items()
        }
        if candidate_declared != candidate_expected:
            _fail("candidate scenario catalog does not exact-match repository scenario resources")
    return {scenario_id: actual[scenario_id] for scenario_id in declared}


def _input_hash(selection: dict, name: str) -> str:
    provenance = _mapping(selection.get("provenance"), "provenance")
    inputs = _mapping(provenance.get("inputs"), "provenance.inputs")
    source = _mapping(inputs.get(name), f"provenance input {name}")
    value = _text(source, "sha256")
    if SHA256.fullmatch(value) is None:
        _fail(f"{name} hash is malformed")
    return value


def _validate_candidate_provenance(documents: ValidationDocuments) -> None:
    provenance = _mapping(documents.candidate.get("provenance"), "candidate provenance")
    if provenance.get("generator") != "tools/scenario/build_han_route_node_candidates.py":
        _fail("candidate provenance generator does not match the reviewed producer")
    if provenance.get("scenarioResourceCount") != EXPECTED_SCENARIOS:
        _fail("candidate provenance scenarioResourceCount does not match the active catalog")
    inputs = _mapping(provenance.get("inputs"), "candidate provenance inputs")
    expected = {
        "administrativeCatalog": (
            Path("data/curated/han/administrative-units.json"),
            documents.catalog_sha256,
        ),
        "administrativePlaceOverlay": (
            Path("data/curated/han/administrative-place-bindings-v1.json"),
            documents.overlay_sha256,
        ),
        "legacyHanMap": (PROVENANCE_DEPENDENCIES["legacyHanMap"], PINNED_LEGACY_HAN_MAP_SHA256),
        "legacyTileMap": (
            PROVENANCE_DEPENDENCIES["legacyTileMap"],
            PINNED_LEGACY_TILE_MAP_SHA256,
        ),
    }
    _require_exact_keys(inputs, set(expected), "candidate provenance inputs")
    for name, (expected_path, expected_hash) in expected.items():
        reference = _mapping(inputs.get(name), f"candidate provenance {name}")
        _require_exact_keys(reference, {"path", "sha256"}, f"candidate provenance {name}")
        if reference.get("path") != expected_path.as_posix():
            _fail(f"candidate provenance {name} path does not match the reviewed input")
        actual_path = _resolved_repository_path(
            documents.source_root, expected_path, f"candidate provenance {name}"
        )
        if not actual_path.is_file() or _sha256(actual_path) != expected_hash:
            _fail(f"candidate provenance {name} repository input does not match its anchor")
        if reference.get("sha256") != expected_hash:
            _fail(f"candidate provenance {name} hash does not match the loaded input")


def _validate_provenance_inputs(
    documents: ValidationDocuments,
) -> dict[str, dict[int, JsonObject]] | None:
    if documents.production_approval_mode:
        if _sha256(VALIDATION_CONTRACT_PATH) != PINNED_VALIDATION_CONTRACT_SHA256:
            _fail("validation contract does not match the independent validation-contract anchor")
        if documents.catalog_sha256 != PINNED_ADMINISTRATIVE_CATALOG_SHA256:
            _fail("administrative catalog does not match the independent administrative-catalog anchor")
        if documents.candidate_sha256 != PINNED_REVIEWED_CANDIDATE_SHA256:
            _fail("candidate manifest does not match the independent reviewed-candidate anchor")
        if documents.route_key_registry_sha256 != PINNED_ROUTE_KEY_REGISTRY_SHA256:
            _fail("route-node key registry hash does not match the independent stable-ID anchor")
        witness_path = _resolved_repository_path(
            documents.source_root,
            Path("data/curated/han/route-node-source-witness-v1.json"),
            "source witness ledger",
        )
        if not witness_path.is_file() or _sha256(witness_path) != PINNED_SOURCE_WITNESS_SHA256:
            _fail("source witness ledger does not match the independent source-witness anchor")
        review_policy_path = _resolved_repository_path(
            documents.source_root, PROVENANCE_DEPENDENCIES["reviewPolicy"], "review policy"
        )
        if (
            not review_policy_path.is_file()
            or _sha256(review_policy_path) != PINNED_REVIEW_POLICY_SHA256
        ):
            _fail("review policy does not match the independent review-policy anchor")
        _validate_candidate_provenance(documents)
    provenance = _mapping(documents.selection.get("provenance"), "provenance")
    if (
        documents.production_approval_mode
        and provenance.get("generator") != EXPECTED_SELECTION_GENERATOR
    ):
        _fail("selection provenance generator does not match the canonical materializer")
    inputs = _mapping(provenance.get("inputs"), "provenance.inputs")
    actual = {
        "candidate": documents.candidate_sha256,
        "administrativeCatalog": documents.catalog_sha256,
        "administrativePlaceOverlay": documents.overlay_sha256,
        "externalClaims": documents.claims_sha256,
        "routeNodeKeyRegistry": documents.route_key_registry_sha256,
    }
    if documents.connections_sha256 is not None:
        actual["candidateConnections"] = documents.connections_sha256
    for name in inputs:
        if name in actual:
            continue
        dependency = PROVENANCE_DEPENDENCIES.get(name)
        if dependency is None:
            _fail(f"unsupported declared provenance input: {name}")
        path = _resolved_repository_path(documents.source_root, dependency, f"provenance input {name}")
        if not path.is_file():
            _fail(f"declared provenance input is missing: {name}")
        actual[name] = _sha256(path)
    required = {"candidate", "administrativeCatalog", "administrativePlaceOverlay", "externalClaims"}
    if documents.production_approval_mode:
        required.update(PROVENANCE_DEPENDENCIES)
    if not required.issubset(inputs):
        _fail("selection provenance is missing required inputs")
    for name, digest in actual.items():
        if name in inputs and _input_hash(documents.selection, name) != digest:
            _fail(f"{name} hash reference does not match")
    if "reviewPolicy" in inputs:
        policy_path = _resolved_repository_path(
            documents.source_root, PROVENANCE_DEPENDENCIES["reviewPolicy"], "review policy"
        )
        policy_document = _mapping(
            json.loads(policy_path.read_text(encoding="utf-8")), "review policy document"
        )
        _validate_review_policy_inputs(documents, policy_document)
        embedded = _mapping(documents.selection.get("reviewPolicy"), "selection reviewPolicy")
        if (
            embedded.get("policyId") != EXPECTED_REVIEW_POLICY_ID
            or embedded.get("policyId") != policy_document.get("policyId")
        ):
            _fail("embedded review policy identity does not match the canonical pinned policy")
        if policy_document.get("reviewDecisionAnchors") != embedded.get("reviewDecisionAnchors"):
            _fail("selection review decision anchors do not resolve to the pinned review policy")
        policy_corrections: dict[int, JsonObject] = {}
        for value in _rows(policy_document, "legacyAttributionCorrections"):
            row = _mapping(value, "review policy legacy attribution correction")
            _require_exact_keys(
                row,
                {
                    "administrativeUnitId", "disposition", "legacyPhysicalPlaceId",
                    "oldCityId",
                },
                "review policy legacy attribution correction",
            )
            old_id = _integer(row, "oldCityId")
            if old_id in policy_corrections:
                _fail("review policy legacyAttributionCorrections must have unique oldCityId values")
            policy_corrections[old_id] = row
        if embedded.get("legacyAttributionCorrections") != list(policy_corrections):
            _fail(
                "selection legacyAttributionCorrections must exact-match the pinned review policy"
            )
        same_node_corrections: dict[int, JsonObject] = {}
        for value in _rows(policy_document, "legacySameNodeCorrections"):
            row = _mapping(value, "review policy legacy same-node correction")
            _require_exact_keys(
                row,
                {
                    "administrativeUnitId", "disposition", "legacyPhysicalPlaceId",
                    "locationClaimId", "oldCityId",
                },
                "review policy legacy same-node correction",
            )
            old_id = _integer(row, "oldCityId")
            if old_id in same_node_corrections:
                _fail("review policy legacySameNodeCorrections must have unique oldCityId values")
            same_node_corrections[old_id] = row
        return {
            "legacyAttributionCorrections": policy_corrections,
            "legacySameNodeCorrections": same_node_corrections,
        }
    return None


def _validate_review_policy_inputs(
    documents: ValidationDocuments, review_policy: JsonObject
) -> None:
    if (
        review_policy.get("policyId") != EXPECTED_REVIEW_POLICY_ID
        or review_policy.get("status") != "APPROVED"
    ):
        _fail("review policy must be the approved W0-C review policy")
    approved = _mapping(review_policy.get("inputs"), "review policy inputs")
    _allowed_keys(
        approved,
        frozenset({
            "administrativeCatalogSha256", "candidateManifest", "coordinateOverlaySha256",
            "locationAdjudications", "locationClaims", "routeNodeKeyRegistry",
        }),
        "review policy inputs",
    )
    if approved.get("administrativeCatalogSha256") != documents.catalog_sha256:
        _fail("review policy administrative catalog hash does not match loaded artifact")
    if approved.get("coordinateOverlaySha256") != documents.overlay_sha256:
        _fail("review policy coordinate overlay hash does not match loaded artifact")
    references = (
        (
            "candidateManifest",
            Path("data/curated/han/route-node-selection-candidates-v1.json"),
            documents.candidate_sha256,
        ),
        ("locationClaims", Path("data/curated/han/route-node-source-claims-v1.json"), documents.claims_sha256),
        (
            "routeNodeKeyRegistry",
            PROVENANCE_DEPENDENCIES["routeNodeKeyRegistry"],
            documents.route_key_registry_sha256,
        ),
        (
            "locationAdjudications",
            PROVENANCE_DEPENDENCIES["locationAdjudications"],
            _sha256(_resolved_repository_path(
                documents.source_root,
                PROVENANCE_DEPENDENCIES["locationAdjudications"],
                "location adjudications",
            )),
        ),
    )
    for name, expected_path, expected_hash in references:
        reference = _mapping(approved.get(name), f"review policy {name}")
        _allowed_keys(reference, frozenset({"path", "sha256"}), f"review policy {name}")
        if reference.get("path") != expected_path.as_posix():
            _fail(f"review policy {name} path does not match the approved repository path")
        if reference.get("sha256") != expected_hash:
            _fail(f"review policy {name} hash does not match loaded artifact")


def _binding_token(node: dict) -> tuple[str, str]:
    admin = node.get("administrativeUnitId")
    claim = node.get("sourceClaimId")
    if (isinstance(admin, str) and bool(admin)) == (isinstance(claim, str) and bool(claim)):
        _fail("historical binding requires exactly one administrativeUnitId XOR sourceClaimId")
    if isinstance(admin, str):
        return "HHS", admin
    return "CLAIM", str(claim)


def _reviewed_location_adjudication_index(
    documents: ValidationDocuments,
) -> dict[str, JsonObject]:
    path = _resolved_repository_path(
        documents.source_root,
        PROVENANCE_DEPENDENCIES["locationAdjudications"],
        "location adjudications",
    )
    document = _mapping(json.loads(path.read_text(encoding="utf-8")), "location adjudications")
    _require_exact_keys(
        document,
        {
            "adjudicationSetId", "adjudications", "inputOverlaySha256", "policy",
            "reviewAuthority", "reviewedAt", "reviewedBy", "schemaVersion", "status",
        },
        "location adjudications",
    )
    _require_schema_version_one(document, "location adjudications")
    if (
        document.get("adjudicationSetId")
        != "han-w0c-ambiguous-location-adjudications-v1"
        or document.get("status") != "REVIEWED"
    ):
        _fail("location-adjudication ledger identity/status is invalid")
    if document.get("inputOverlaySha256") != documents.overlay_sha256:
        _fail("location-adjudication ledger inputOverlaySha256 does not match loaded overlay")
    policy = _mapping(document.get("policy"), "location-adjudication policy")
    _require_exact_keys(
        policy,
        {
            "automaticFirstCandidateSelection", "automaticNearestSelection", "note",
            "rejectedCount", "selectedCount",
        },
        "location-adjudication policy",
    )
    if (
        policy.get("automaticFirstCandidateSelection") is not False
        or policy.get("automaticNearestSelection") is not False
        or policy.get("selectedCount") != 50
        or policy.get("rejectedCount") != 5
    ):
        _fail("location-adjudication policy counts or automation flags are invalid")
    approved: dict[str, JsonObject] = {}
    rejected_count = 0
    seen: set[str] = set()
    row_fields = {
        "administrativeUnitId", "evidenceRefs", "rationale", "rationaleCode",
        "rejectedPhysicalPlaceIds", "reviewState", "selectedPhysicalPlaceId", "sourceName",
    }
    for value in _rows(document, "adjudications"):
        row = _mapping(value, "location-adjudication row")
        _require_exact_keys(row, row_fields, "location-adjudication row")
        unit_id = _text(row, "administrativeUnitId")
        _text(row, "sourceName")
        _text(row, "rationaleCode")
        _review_reason(row, "location-adjudication row")
        rejected = row.get("rejectedPhysicalPlaceIds")
        if (
            unit_id in seen
            or not isinstance(rejected, list)
            or any(not isinstance(ref, str) or POINT_REFERENCE.fullmatch(ref) is None for ref in rejected)
            or len(rejected) != len(set(rejected))
        ):
            _fail("location-adjudication ledger row identities or rejected places are invalid")
        seen.add(unit_id)
        state = row.get("reviewState")
        if state == "APPROVED_FOR_SELECTION":
            selected = row.get("selectedPhysicalPlaceId")
            if not isinstance(selected, str) or POINT_REFERENCE.fullmatch(selected) is None:
                _fail("approved location-adjudication ledger row requires a selected place")
            approved[unit_id] = row
        elif state == "REJECTED_FALSE_HOMONYM":
            if row.get("selectedPhysicalPlaceId") is not None:
                _fail("rejected location-adjudication ledger row cannot select a place")
            rejected_count += 1
        else:
            _fail("location-adjudication ledger reviewState is invalid")
    if len(approved) != 50 or rejected_count != 5:
        _fail("location-adjudication ledger must contain exactly 50 approved and 5 rejected rows")
    return approved


def _validate_adjudication(
    node: dict, refs: tuple[str, ...], ledger_row: JsonObject | None = None
) -> None:
    raw = node.get("locationAdjudication")
    if not isinstance(raw, dict):
        _fail("AMBIGUOUS_POINT requires locationAdjudication")
    selected = _text(raw, "selectedPhysicalPlaceRef")
    rejected = raw.get("rejectedPhysicalPlaceRefs")
    if not isinstance(rejected, list) or any(not isinstance(value, str) for value in rejected):
        _fail("locationAdjudication rejectedPhysicalPlaceRefs is malformed")
    if selected != node.get("physicalPlaceRef") or selected not in refs:
        _fail("locationAdjudication selected point must match the route node")
    if len(rejected) != len(set(rejected)) or set(rejected) != set(refs) - {selected}:
        _fail("locationAdjudication rejected points must be exhaustive")
    if raw.get("rationaleCode") not in {
        "GROUP_GEOGRAPHY_AND_TEMPORAL_RECORD_REVIEW",
        "HISTORICAL_LOCATION_CORRECTION",
    }:
        _fail("locationAdjudication rationaleCode is invalid")
    if raw.get("evidenceRefs") != [
        "data/curated/han/administrative-place-bindings-v1.json",
        "docs/superpowers/reviews/2026-08-22-w0b-administrative-place-overlay-review.md",
    ]:
        _fail("locationAdjudication evidenceRefs must exact-match the reviewed inputs")
    _review_reason(raw, "locationAdjudication")
    if ledger_row is not None:
        expected = {
            "kind": "EXPLICIT_AMBIGUITY_REVIEW",
            "selectedPhysicalPlaceRef": ledger_row["selectedPhysicalPlaceId"],
            "rejectedPhysicalPlaceRefs": ledger_row["rejectedPhysicalPlaceIds"],
            "rationaleCode": ledger_row["rationaleCode"],
            "rationale": ledger_row["rationale"],
            "evidenceRefs": ledger_row["evidenceRefs"],
        }
        if raw != expected or node.get("displayName") != ledger_row.get("sourceName"):
            _fail("selection does not exact-match the pinned location-adjudication ledger")


def _validate_conflict(node: dict, candidate: dict, binding: tuple[str, str]) -> None:
    disposition = node.get("historicalConflictDisposition")
    if not isinstance(disposition, dict):
        _fail("HHS_ATTRIBUTION_CONFLICT requires historicalConflictDisposition")
    selected = _text(disposition, "selectedBindingRef")
    expected_selected = binding[1]
    if selected != expected_selected:
        _fail("historicalConflictDisposition selectedBindingRef does not match")
    rejected = disposition.get("rejectedAdministrativeUnitIds")
    incompatible = candidate.get("incompatibleAdministrativeUnitIds")
    if not isinstance(rejected, list) or not isinstance(incompatible, list):
        _fail("historicalConflictDisposition rejected identities are malformed")
    expected_rejected = set(incompatible) - ({selected} if binding[0] == "HHS" else set())
    if len(rejected) != len(set(rejected)) or set(rejected) != expected_rejected:
        _fail("historicalConflictDisposition rejected identities must be exhaustive")
    _review_reason(disposition, "historicalConflictDisposition")


def _prior_binding(candidate: dict) -> str:
    proposed = candidate.get("proposedAdministrativeUnitId")
    if isinstance(proposed, str):
        return f"HHS:{proposed}"
    for key in ("candidateAdministrativeUnitIds", "incompatibleAdministrativeUnitIds"):
        values = candidate.get(key)
        if isinstance(values, list) and len(values) == 1 and isinstance(values[0], str):
            return f"HHS:{values[0]}"
    external = candidate.get("externalPlaceRef")
    return f"LEGACY:{external}" if isinstance(external, str) else "UNRESOLVED"


def _new_binding(node: dict, claims: dict[str, dict]) -> str:
    kind, reference = _binding_token(node)
    if kind == "HHS":
        return f"HHS:{reference}"
    legacy = claims[reference].get("legacyExternalPlaceRef")
    return f"LEGACY:{legacy}" if isinstance(legacy, str) else f"CLAIM:{reference}"


def _validate_migration(
    documents: ValidationDocuments,
    indexes: SelectionIndexes,
    policy_corrections: dict[str, dict[int, JsonObject]] | None,
    adjudications: dict[str, JsonObject] | None,
) -> MigrationDerivation:
    migration = documents.migration
    selection = documents.selection
    candidates = indexes.candidates
    nodes = indexes.nodes
    claims = indexes.claims
    if migration.get("migrationId") != EXPECTED_MIGRATION_ID:
        _fail(f"migrationId must be canonical {EXPECTED_MIGRATION_ID}")
    if migration.get("mode") != "NEW_WORLD_ONLY":
        _fail("migration mode must be NEW_WORLD_ONLY")
    if migration.get("sourceSelectionId") != EXPECTED_SELECTION_ID:
        _fail(f"migration sourceSelectionId must be canonical {EXPECTED_SELECTION_ID}")
    if migration.get("sourceSelectionSha256") != documents.selection_sha256:
        _fail("migration selection hash reference does not match")
    if migration.get("sourceCandidateSha256") != documents.candidate_sha256:
        _fail("migration candidate hash reference does not match")
    inventory = _mapping(migration.get("referenceInventory"), "referenceInventory")
    mutable = inventory.get("mutable")
    immutable = inventory.get("immutableAudit")
    derived = inventory.get("derivedReseed")
    if not isinstance(mutable, list) or any(not isinstance(value, str) for value in mutable):
        _fail("migration mutable reference inventory is malformed")
    if len(mutable) != len(set(mutable)) or set(mutable) != set(MUTABLE_REFERENCES):
        _fail("migration mutable reference inventory is incomplete")
    if not isinstance(immutable, list) or any(not isinstance(value, str) for value in immutable):
        _fail("migration immutable audit reference inventory is malformed")
    if len(immutable) != len(set(immutable)) or set(immutable) != set(IMMUTABLE_AUDIT_REFERENCES):
        _fail("migration immutable audit reference inventory must be exact")
    if not isinstance(derived, list) or any(not isinstance(value, str) for value in derived):
        _fail("migration derived/reseed reference inventory is malformed")
    if len(derived) != len(set(derived)) or set(derived) != set(DERIVED_RESEED_REFERENCES):
        _fail("migration derived/reseed reference inventory must be exact")
    if set(inventory) != {"mutable", "immutableAudit", "derivedReseed", "unknownPayloadPolicy", "inPlaceRewrite"}:
        _fail("migration reference inventory categories must be exact")
    if inventory.get("unknownPayloadPolicy") != "REJECT_UNKNOWN":
        _fail("migration unknown payload policy must be REJECT_UNKNOWN")
    if inventory.get("inPlaceRewrite") is not False:
        _fail("migration inPlaceRewrite must be false")
    if migration.get("rewriteSurfaces") != EXPECTED_REWRITE_SURFACES:
        _fail("migration rewriteSurfaces must exact-match RESEED, REGENERATE, and NO_REWRITE policy")
    review_policy = _mapping(selection.get("reviewPolicy"), "reviewPolicy")
    if review_policy.get("numericCityIdChangeAllowed") is not False:
        _fail("reviewPolicy numericCityIdChangeAllowed must be false")
    rows = [_mapping(value, "migration row") for value in _rows(migration, "rows")]
    if len(rows) != EXPECTED_COUNT:
        _fail("migration must contain exactly 780 rows")
    old_ids = [_integer(row, "oldCityId") for row in rows]
    new_ids = [_integer(row, "newCityId") for row in rows]
    if sorted(old_ids) != list(range(1, EXPECTED_COUNT + 1)):
        _fail("migration oldCityId values must be exact 1..780")
    if sorted(new_ids) != list(range(1, EXPECTED_COUNT + 1)):
        _fail("migration newCityId values must be exact 1..780")
    by_key = {_text(node, "routeNodeKey"): node for node in nodes.values()}
    counters = {
        "numericCityIdChangeCount": 0,
        "routeNodeReplacementCount": 0,
        "historicalBindingCorrectionCount": 0,
        "physicalPlaceCorrectionCount": 0,
        "displayNameChangeCount": 0,
        "parentChangeCount": 0,
        "seatRoleChangeCount": 0,
    }
    used_keys: set[str] = set()
    historical_correction_ids: set[int] = set()
    for row in rows:
        old_id = _integer(row, "oldCityId")
        new_id = _integer(row, "newCityId")
        if new_id != old_id:
            _fail("migration newCityId must equal oldCityId when numericCityIdChangeAllowed is false")
        key = _text(row, "routeNodeKey")
        candidate = candidates[old_id]
        node = by_key.get(key)
        if node is None or key in used_keys or _integer(node, "numericCityId") != new_id:
            _fail("migration routeNodeKey/newCityId mapping is not bijective")
        used_keys.add(key)
        if row.get("oldNodeFingerprint") != candidate.get("legacyNodeFingerprint"):
            _fail(f"migration fingerprint mismatch for old city {old_id}")
        if _integer(node, "legacyCityId") != old_id:
            _fail("migration oldCityId/fingerprint/routeNodeKey chain does not match")
        counters["numericCityIdChangeCount"] += old_id != new_id
        disposition = node.get("legacyDisposition")
        if disposition not in {"RETAINED", "REPLACED"}:
            _fail("legacyDisposition must be RETAINED or REPLACED")
        migration_disposition = row.get("disposition")
        if migration_disposition not in {
            "RETAINED_SAME_NODE",
            "CORRECTED_BINDING_SAME_NODE",
            "CORRECTED_LOCATION_SAME_NODE",
            "REPLACED_UNRELATED_NODE",
        }:
            _fail("migration disposition is invalid")
        name_changed = candidate.get("legacyNameCh") != node.get("displayName")
        parent_changed = candidate.get("legacyOwnerGroup") != node.get("parentName")
        binding_changed = (
            _prior_binding(candidate) != _new_binding(node, claims) or parent_changed
        )
        expected_seat = "COMMANDERY_SEAT" if candidate.get("legacyIsSeat") is True else "NON_SEAT"
        seat_changed = expected_seat != node.get("seatRole")
        old_physical = candidate.get("physicalPlaceRef")
        physical_changed = isinstance(old_physical, str) and old_physical != node.get("physicalPlaceRef")
        if disposition == "REPLACED":
            if migration_disposition != "REPLACED_UNRELATED_NODE":
                _fail("REPLACED node must use REPLACED_UNRELATED_NODE migration disposition")
            replacement = node.get("replacementDisposition")
            if not isinstance(replacement, dict):
                _fail("REPLACED route node requires replacementDisposition")
            _review_reason(replacement, "replacementDisposition")
            if not any((binding_changed, name_changed, parent_changed, seat_changed, physical_changed)):
                _fail("REPLACED route node must differ from its legacy candidate")
            counters["routeNodeReplacementCount"] += 1
            if "physicalPlaceCorrection" in node:
                _fail("REPLACED route node cannot also claim physicalPlaceCorrection")
        elif "replacementDisposition" in node:
            _fail("RETAINED route node cannot contain replacementDisposition")
        correction = node.get("physicalPlaceCorrection")
        if disposition == "RETAINED" and physical_changed:
            if not isinstance(correction, dict):
                _fail("retained physical-place change requires physicalPlaceCorrection")
            if migration_disposition != "CORRECTED_LOCATION_SAME_NODE":
                _fail("retained physical-place correction must use CORRECTED_LOCATION_SAME_NODE")
            if correction.get("fromPhysicalPlaceRef") != old_physical:
                _fail("physicalPlaceCorrection fromPhysicalPlaceRef does not match candidate")
            if correction.get("toPhysicalPlaceRef") != node.get("physicalPlaceRef"):
                _fail("physicalPlaceCorrection toPhysicalPlaceRef does not match selection")
            _review_reason(correction, "physicalPlaceCorrection")
            if adjudications is not None:
                unit_id = _text(node, "administrativeUnitId")
                expected_refs = [
                    PROVENANCE_DEPENDENCIES["locationAdjudications"].as_posix(),
                    unit_id,
                ]
                ledger_row = adjudications.get(unit_id)
                if correction.get("evidenceRefs") != expected_refs:
                    _fail(
                        "physicalPlaceCorrection evidenceRefs must resolve to its pinned "
                        "location-adjudication row"
                    )
                if (
                    ledger_row is None
                    or ledger_row.get("selectedPhysicalPlaceId") != node.get("physicalPlaceRef")
                    or old_physical not in ledger_row.get("rejectedPhysicalPlaceIds", [])
                ):
                    _fail("physicalPlaceCorrection does not match its pinned adjudication row")
            counters["physicalPlaceCorrectionCount"] += 1
        elif correction is not None:
            _fail("physicalPlaceCorrection exists without a retained physical-place change")
        if disposition == "RETAINED" and not physical_changed:
            expected_disposition = (
                "CORRECTED_BINDING_SAME_NODE" if binding_changed else "RETAINED_SAME_NODE"
            )
            if migration_disposition != expected_disposition:
                if binding_changed:
                    _fail(
                        "retained binding change must use CORRECTED_BINDING_SAME_NODE: "
                        f"{old_id}"
                    )
                _fail(f"retained unchanged node must use RETAINED_SAME_NODE: {old_id}")
        is_binding_correction = migration_disposition == "CORRECTED_BINDING_SAME_NODE"
        counters["historicalBindingCorrectionCount"] += is_binding_correction
        if is_binding_correction:
            historical_correction_ids.add(old_id)
        counters["displayNameChangeCount"] += candidate.get("legacyNameCh") != node.get("displayName")
        counters["parentChangeCount"] += candidate.get("legacyOwnerGroup") != node.get("parentName")
        counters["seatRoleChangeCount"] += expected_seat != node.get("seatRole")
    if used_keys != set(by_key):
        _fail("migration does not cover every routeNodeKey exactly once")
    summary = _mapping(migration.get("summary"), "migration summary")
    if summary.get("rowCount") != EXPECTED_COUNT:
        _fail("migration summary rowCount must be 780")
    for name, actual in counters.items():
        if summary.get(name) != actual:
            _fail(f"migration {name} must equal recomputed value {actual}")
    attribution_rows = review_policy.get("legacyAttributionCorrections")
    if not isinstance(attribution_rows, list) or any(
        not isinstance(value, int) or isinstance(value, bool) for value in attribution_rows
    ):
        _fail("reviewPolicy legacyAttributionCorrections must be an integer array")
    if len(attribution_rows) != len(set(attribution_rows)):
        _fail("reviewPolicy legacyAttributionCorrections must be unique")
    if not set(attribution_rows).issubset(historical_correction_ids):
        _fail("reviewPolicy legacyAttributionCorrections must reference actual binding changes")
    if policy_corrections is not None:
        migration_rows = {_integer(row, "oldCityId"): row for row in rows}
        for old_id, expected in policy_corrections["legacyAttributionCorrections"].items():
            candidate = candidates.get(old_id)
            node = nodes.get(old_id)
            migration_row = migration_rows.get(old_id)
            if candidate is None or node is None or migration_row is None:
                _fail("pinned legacy attribution correction has a dangling oldCityId")
            actual = {
                "oldCityId": old_id,
                "legacyPhysicalPlaceId": candidate.get("physicalPlaceRef"),
                "administrativeUnitId": node.get("administrativeUnitId"),
                "disposition": migration_row.get("disposition"),
            }
            if actual != expected:
                _fail(
                    "legacy attribution correction content must exact-match the pinned review policy"
                )
        for old_id, expected in policy_corrections["legacySameNodeCorrections"].items():
            node = nodes.get(old_id)
            migration_row = migration_rows.get(old_id)
            if node is None or migration_row is None:
                _fail("pinned legacy same-node correction has a dangling oldCityId")
            actual = {
                "oldCityId": old_id,
                "administrativeUnitId": node.get("administrativeUnitId"),
                "legacyPhysicalPlaceId": node.get("physicalPlaceRef"),
                "locationClaimId": node.get("locationClaimId"),
                "disposition": migration_row.get("disposition"),
            }
            if actual != expected:
                _fail(
                    "legacy same-node correction content must exact-match the pinned review policy"
                )
    return MigrationDerivation(
        physical_place_correction_count=counters["physicalPlaceCorrectionCount"],
        historical_binding_correction_ids=frozenset(historical_correction_ids),
    )


def _validate_connections(connections: dict, route_keys: set[str]) -> None:
    _allowed_keys(connections, frozenset({"schemaVersion", "lifecycle", "connections"}), "candidateConnections")
    _forbid_fields(connections, "candidateConnections")
    if connections.get("lifecycle") != "CANDIDATE_ONLY":
        _fail("connection lifecycle must remain CANDIDATE_ONLY")
    edges: list[tuple[str, str]] = []
    for raw in _rows(connections, "connections"):
        edge = _mapping(raw, "connection")
        _allowed_keys(edge, frozenset({"fromRouteNodeKey", "toRouteNodeKey"}), "connection")
        source = _text(edge, "fromRouteNodeKey")
        target = _text(edge, "toRouteNodeKey")
        if source not in route_keys or target not in route_keys:
            _fail("connection contains a dangling routeNodeKey")
        if source == target:
            _fail("connection graph contains a self edge")
        edges.append((source, target))
    if len(edges) != len(set(edges)):
        _fail("connection graph contains duplicate directed edges")
    edge_set = set(edges)
    if any((target, source) not in edge_set for source, target in edges):
        _fail("connection graph contains an asymmetric edge")


def _validate_forbidden_policy(selection: JsonObject, nodes: list[JsonObject]) -> None:
    review_policy = _mapping(selection.get("reviewPolicy"), "reviewPolicy")
    forbidden = _mapping(review_policy.get("forbiddenSelections"), "reviewPolicy.forbiddenSelections")
    if forbidden != EXPECTED_FORBIDDEN_SELECTIONS:
        _fail("embedded forbidden selection policy must exact-match the reviewed policy")
    physical = set(_rows(forbidden, "physicalPlaceIds"))
    names = set(_rows(forbidden, "canonicalNames"))
    classes = set(_rows(forbidden, "nodeClasses"))
    for node in nodes:
        if node.get("physicalPlaceRef") in physical:
            _fail(f"forbidden physicalPlaceRef selected: {node.get('physicalPlaceRef')}")
        if node.get("canonicalName") in names or node.get("displayName") in names:
            selected_name = node.get("canonicalName") if node.get("canonicalName") in names else node.get("displayName")
            _fail(f"forbidden canonical name selected: {selected_name}")
        if node.get("nodeClass") in classes:
            _fail(f"forbidden nodeClass selected: {node.get('nodeClass')}")


def validate_documents(documents: ValidationDocuments) -> ValidationReport:
    versioned = {
        "candidate": documents.candidate,
        "catalog": documents.catalog,
        "overlay": documents.overlay,
        "external claims": documents.external_claims,
        "selection": documents.selection,
        "migration": documents.migration,
        "route-node key registry": documents.route_key_registry,
    }
    for label, document in versioned.items():
        _require_schema_version_one(document, label)
    _validate_closed_schemas(documents)
    if documents.connections is not None:
        _require_schema_version_one(documents.connections, "candidate connections")
    if documents.selection.get("runtimeScenarioActivationEnforcement") != "NOT_CLAIMED_BY_W0_DATA_CONTRACT":
        _fail("runtimeScenarioActivationEnforcement must be NOT_CLAIMED_BY_W0_DATA_CONTRACT")
    if documents.selection.get("baselineYear") != 220:
        _fail("selection baselineYear must be 220")
    if documents.selection.get("selectionId") != EXPECTED_SELECTION_ID:
        _fail(f"selectionId must be canonical {EXPECTED_SELECTION_ID}")
    policy_corrections = _validate_provenance_inputs(documents)
    _forbid_fields(documents.selection, "selection")
    _forbid_fields(documents.migration, "migration")
    catalog = _catalog_index(documents.catalog, documents.production_approval_mode)
    route_key_registry = _route_key_registry_index(documents.route_key_registry)
    if documents.overlay.get("catalogId") != documents.catalog.get("catalogId"):
        _fail("overlay catalogId must match the administrative catalog")
    overlay = _overlay_index(documents.overlay, catalog)
    candidates = _candidate_index(documents.candidate, catalog)
    claims = _claims_index(documents.external_claims, documents.source_root)
    adjudications = (
        _reviewed_location_adjudication_index(documents)
        if documents.production_approval_mode
        else None
    )
    scenarios = _scenario_catalog(documents.selection, documents.candidate, documents.scenarios)
    if documents.selection.get("reviewState") != "APPROVED":
        _fail("selection artifact must be APPROVED")
    raw_nodes = [_mapping(value, "route node") for value in _rows(documents.selection, "routeNodes")]
    _validate_review_decision_anchors(
        raw_nodes,
        _mapping(documents.selection.get("reviewPolicy"), "selection reviewPolicy"),
        documents.production_approval_mode,
    )
    selection_summary = _mapping(documents.selection.get("summary"), "selection summary")
    if len(raw_nodes) != EXPECTED_COUNT or selection_summary.get("approvedCount") != EXPECTED_COUNT:
        _fail("approved route-node selection must contain exactly 780 rows")
    _validate_forbidden_policy(documents.selection, raw_nodes)
    ids = [_integer(node, "numericCityId") for node in raw_nodes]
    legacy_ids = [_integer(node, "legacyCityId") for node in raw_nodes]
    if sorted(ids) != list(range(1, EXPECTED_COUNT + 1)):
        _fail("numericCityId values must be exact 1..780")
    if sorted(legacy_ids) != list(range(1, EXPECTED_COUNT + 1)):
        _fail("selection legacyCityId values must be exact 1..780")
    nodes = dict(zip(legacy_ids, raw_nodes, strict=True))
    route_keys = [_text(node, "routeNodeKey") for node in raw_nodes]
    if len(route_keys) != len(set(route_keys)):
        _fail("routeNodeKey values must be unique")
    for key in route_keys:
        try:
            parsed = UUID(key)
        except ValueError as error:
            raise SelectionContractError(f"routeNodeKey must be a literal UUIDv4: {key}") from error
        if parsed.version != 4 or str(parsed) != key:
            _fail(f"routeNodeKey must be a canonical literal UUIDv4: {key}")
    physical_refs = [_text(node, "physicalPlaceRef") for node in raw_nodes]
    if any(POINT_REFERENCE.fullmatch(reference) is None for reference in physical_refs):
        _fail("every route-node physicalPlaceRef must be a valid point reference")
    if len(physical_refs) != len(set(physical_refs)):
        _fail("physicalPlaceRef values must be unique")
    bindings = [_binding_token(node) for node in raw_nodes]
    if sum(binding == ("HHS", GUZI_ADMIN_ID) for binding in bindings) != 1:
        _fail(f"{GUZI_ADMIN_ID} must bind exactly one approved route node")
    if len(bindings) != len(set(bindings)):
        _fail("historical binding references must be unique")
    binding_counts = Counter(
        "HHS_ADMINISTRATIVE_UNIT" if binding[0] == "HHS" else "REVIEWED_SOURCE_CLAIM"
        for binding in bindings
    )
    if selection_summary.get("historicalBindingCounts") != dict(binding_counts):
        _fail("selection summary historicalBindingCounts must equal validated bindings")
    used_claims: set[str] = set()
    ambiguous_count = 0
    used_adjudications: set[str] = set()
    location_count = 0
    external_count = 0
    for node in raw_nodes:
        if node.get("reviewState") != "APPROVED":
            _fail("every route node must be APPROVED")
        if node.get("nodeClass") not in ALLOWED_NODE_CLASSES:
            _fail("nodeClass must be COUNTY_NODE, DAO_NODE, MARQUISATE_NODE, or TOWN_NODE")
        legacy_id = _integer(node, "legacyCityId")
        candidate = candidates[legacy_id]
        if node.get("legacyNodeFingerprint") != candidate.get("legacyNodeFingerprint"):
            _fail(f"selection fingerprint does not match candidate: {legacy_id}")
        _text(node, "displayName")
        _text(node, "parentName")
        if node.get("seatRole") not in {"COMMANDERY_SEAT", "NON_SEAT"}:
            _fail("seatRole must be COMMANDERY_SEAT or NON_SEAT")
        rationale = _mapping(node.get("selectionRationale"), "selectionRationale")
        binding = _binding_token(node)
        expected_binding_basis = (
            "HHS_ADMINISTRATIVE_UNIT" if binding[0] == "HHS" else "REVIEWED_SOURCE_CLAIM"
        )
        if node.get("historicalBindingBasis") != expected_binding_basis:
            _fail("historicalBindingBasis does not match the derived binding kind")
        claim: dict | None = None
        if binding[0] == "HHS":
            unit_id = binding[1]
            catalog_unit = catalog.get(unit_id)
            if catalog_unit is None:
                _fail(f"unknown administrative binding: {unit_id}")
            if route_key_registry.get(unit_id) != node.get("routeNodeKey"):
                _fail(f"route-node key registry mismatch for {unit_id}")
            if catalog_unit.source_name_status == "SOURCE_PLACEHOLDER":
                _fail(f"source placeholder cannot bind a route node: {unit_id}")
            expected_metadata = {
                "nodeClass": catalog_unit.node_class,
                "displayName": catalog_unit.canonical_name,
                "canonicalName": catalog_unit.canonical_name,
                "parentName": catalog_unit.parent_name,
                "parentRef": catalog_unit.parent_ref,
                "seatRole": catalog_unit.seat_role,
            }
            for field, expected in expected_metadata.items():
                if node.get(field) != expected:
                    _fail(f"HHS catalog metadata mismatch for {field}: {unit_id}")
            join_status, refs = overlay[unit_id]
            if join_status == "RESOLVED_POINT":
                expected_review_batch = "w0b-overlay-unique-220"
                if node.get("physicalPlaceRef") not in refs:
                    _fail(f"resolved HHS binding physicalPlaceRef mismatch: {unit_id}")
                adjudication = _mapping(node.get("locationAdjudication"), "locationAdjudication")
                if adjudication != {"kind": "W0B_GLOBAL_UNIQUE_220"}:
                    _fail("resolved HHS binding must cite the W0-B global-unique review batch")
            if join_status == "AMBIGUOUS_POINT":
                expected_review_batch = "w0c-reviewed-ambiguity"
                ledger_row = None if adjudications is None else adjudications.get(unit_id)
                if adjudications is not None and ledger_row is None:
                    _fail(f"ambiguous selection is absent from pinned location-adjudication ledger: {unit_id}")
                _validate_adjudication(node, refs, ledger_row)
                used_adjudications.add(unit_id)
                ambiguous_count += 1
            if join_status == "SOURCE_PLACEHOLDER":
                _fail(f"source placeholder overlay cannot bind a route node: {unit_id}")
            if join_status == "NO_COORDINATE_CANDIDATE":
                expected_review_batch = "w0c-hhs-external-location"
                location_id = node.get("locationClaimId")
                if not isinstance(location_id, str) or location_id not in claims:
                    _fail(f"NO_COORDINATE_CANDIDATE requires approved locationClaimId: {unit_id}")
                location_claim = claims[location_id]
                if location_claim.get("claimRole") != "LOCATION":
                    _fail(f"locationClaimId must reference a LOCATION claim: {location_id}")
                if location_claim.get("subjectKey") != unit_id:
                    _fail(f"location claim subjectKey mismatch: {location_id}")
                if location_claim.get("subjectType") not in ROUTE_SUBJECT_TYPES:
                    _fail(f"location claim cannot use {location_claim.get('subjectType')}: {location_id}")
                if location_claim.get("physicalPlaceRef") != node.get("physicalPlaceRef"):
                    _fail(f"location claim physicalPlaceRef mismatch: {location_id}")
                adjudication = _mapping(node.get("locationAdjudication"), "locationAdjudication")
                if adjudication != {"kind": "APPROVED_LOCATION_ONLY_CLAIM", "sourceClaimId": location_id}:
                    _fail("NO_COORDINATE_CANDIDATE must cite its approved location claim")
                if location_id in used_claims:
                    _fail(f"external claim reference must be unique: {location_id}")
                used_claims.add(location_id)
                claim = location_claim
                location_count += 1
            elif "locationClaimId" in node:
                _fail("locationClaimId is only valid for NO_COORDINATE_CANDIDATE")
        else:
            expected_review_batch = "w0c-hhs-external-location"
            claim_id = binding[1]
            claim = claims.get(claim_id)
            if claim is None:
                _fail(f"dangling external sourceClaimId: {claim_id}")
            if claim.get("claimRole") != "ROUTE_NODE":
                _fail(f"route-node binding must reference a ROUTE_NODE claim: {claim_id}")
            if claim.get("subjectType") not in ROUTE_SUBJECT_TYPES:
                _fail(f"{claim.get('subjectType')} cannot bind a RouteNode")
            if claim.get("physicalPlaceRef") != node.get("physicalPlaceRef"):
                _fail(f"external binding physicalPlaceRef mismatch: {claim_id}")
            if claim.get("subjectName") != node.get("displayName"):
                _fail(f"external binding subjectName mismatch: {claim_id}")
            if claim_id in used_claims:
                _fail(f"external claim reference must be unique: {claim_id}")
            used_claims.add(claim_id)
            external_count += 1
            if "locationClaimId" in node:
                _fail("external RouteNode binding cannot also carry locationClaimId")
        _validate_selection_rationale(rationale, expected_review_batch)
        if candidate.get("classification") == "HHS_ATTRIBUTION_CONFLICT":
            _validate_conflict(node, candidate, binding)
        elif "historicalConflictDisposition" in node:
            _fail("historicalConflictDisposition is only valid for attribution conflicts")
        present_runtime_fields = FORBIDDEN_RUNTIME_NODE_FIELDS.intersection(node)
        if present_runtime_fields:
            _fail(f"W0 route node must not claim runtime lifecycle fields: {sorted(present_runtime_fields)}")
    if used_claims != set(claims) or location_count != 8 or external_count != 0:
        _fail("all exactly 8 LOCATION_ONLY claims must be used by one route node each; external bindings are forbidden")
    if adjudications is not None and used_adjudications != set(adjudications):
        _fail("selection must exhaust the 50-row pinned location-adjudication ledger")
    migration = _validate_migration(
        documents,
        SelectionIndexes(candidates=candidates, nodes=nodes, claims=claims),
        policy_corrections,
        adjudications,
    )
    if documents.connections is not None:
        _validate_connections(documents.connections, set(route_keys))
        if (
            documents.connections_sha256 is not None
            and _input_hash(documents.selection, "candidateConnections") != documents.connections_sha256
        ):
            _fail("selection candidate connections hash reference does not match")
    return ValidationReport(
        approved_count=EXPECTED_COUNT,
        scenario_count=EXPECTED_SCENARIOS,
        ambiguous_adjudication_count=ambiguous_count,
        location_claim_count=location_count,
        external_binding_count=external_count,
        physical_place_correction_count=migration.physical_place_correction_count,
        selection_sha256=documents.selection_sha256,
        migration_sha256=documents.migration_sha256,
    )


def _load_scenarios(directory: Path) -> tuple[ScenarioResource, ...]:
    resources: list[ScenarioResource] = []
    for path in sorted(directory.glob("scenario_*.json")):
        document = _load(path)
        map_info = document.get("map")
        if not isinstance(map_info, dict) or map_info.get("mapName") != "han":
            continue
        match = re.fullmatch(r"scenario_(.+)\.json", path.name)
        if match is None:
            _fail(f"invalid scenario resource name: {path.name}")
        resources.append(
            ScenarioResource(
                scenario_id=match.group(1),
                resource_name=path.name,
                resource_path=f"infra/src/main/resources/scenario/{path.name}",
                start_year=_integer(document, "startYear"),
                sha256=_sha256(path),
            )
        )
    return tuple(resources)


def load_documents(paths: ValidationPaths) -> ValidationDocuments:
    connections = _load(paths.connections) if paths.connections is not None else None
    defaults = ValidationPaths(
        candidate=DEFAULT_CANDIDATE, catalog=DEFAULT_CATALOG, overlay=DEFAULT_OVERLAY,
        external_claims=DEFAULT_CLAIMS, selection=DEFAULT_SELECTION, migration=DEFAULT_MIGRATION,
        scenarios_dir=DEFAULT_SCENARIOS, connections=None, source_root=ROOT,
    )
    production_approval_mode = all(
        getattr(paths, field).absolute() == getattr(defaults, field).absolute()
        for field in ("candidate", "catalog", "overlay", "external_claims", "selection", "migration", "scenarios_dir", "source_root")
    ) and paths.connections is None
    registry_path = _resolved_repository_path(
        paths.source_root,
        PROVENANCE_DEPENDENCIES["routeNodeKeyRegistry"],
        "route-node key registry",
    )
    return ValidationDocuments(
        candidate=_load(paths.candidate),
        catalog=_load(paths.catalog),
        overlay=_load(paths.overlay),
        external_claims=_load(paths.external_claims),
        selection=_load(paths.selection),
        migration=_load(paths.migration),
        route_key_registry=_load(registry_path),
        scenarios=_load_scenarios(paths.scenarios_dir),
        candidate_sha256=_sha256(paths.candidate),
        catalog_sha256=_sha256(paths.catalog),
        overlay_sha256=_sha256(paths.overlay),
        claims_sha256=_sha256(paths.external_claims),
        selection_sha256=_sha256(paths.selection),
        migration_sha256=_sha256(paths.migration),
        route_key_registry_sha256=_sha256(registry_path),
        source_root=paths.source_root,
        connections=connections,
        connections_sha256=_sha256(paths.connections) if paths.connections is not None else None,
        production_approval_mode=production_approval_mode,
    )


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--candidate", type=Path, default=DEFAULT_CANDIDATE)
    parser.add_argument("--catalog", type=Path, default=DEFAULT_CATALOG)
    parser.add_argument("--overlay", type=Path, default=DEFAULT_OVERLAY)
    parser.add_argument("--external-claims", type=Path, default=DEFAULT_CLAIMS)
    parser.add_argument("--selection", type=Path, default=DEFAULT_SELECTION)
    parser.add_argument("--migration", type=Path, default=DEFAULT_MIGRATION)
    parser.add_argument("--scenarios-dir", type=Path, default=DEFAULT_SCENARIOS)
    parser.add_argument("--connections", type=Path)
    parser.add_argument("--source-root", type=Path, default=ROOT)
    args = parser.parse_args()
    paths = ValidationPaths(
        candidate=args.candidate,
        catalog=args.catalog,
        overlay=args.overlay,
        external_claims=args.external_claims,
        selection=args.selection,
        migration=args.migration,
        scenarios_dir=args.scenarios_dir,
        connections=args.connections,
        source_root=args.source_root,
    )
    try:
        documents = load_documents(paths)
        report = validate_documents(documents)
    except (SelectionContractError, FileNotFoundError, OSError, UnicodeError, json.JSONDecodeError) as error:
        print(f"han route-node selection validation failed: {error}", file=sys.stderr)
        return 2
    if documents.production_approval_mode:
        status = (
            "approved production manifest "
            "(curated provenance snapshot validated; live corpus refresh not claimed)"
        )
        count_label = "approved"
    else:
        status = "synthetic/internal-consistency only"
        count_label = "consistentNodes"
    print(
        f"han route-node selection {status}: "
        f"{count_label}={report.approved_count} scenarios={report.scenario_count} "
        f"selectionSha256={report.selection_sha256} migrationSha256={report.migration_sha256}"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
