#!/usr/bin/env python3
# /// script
# requires-python = ">=3.11"
# dependencies = []
# ///
# ─── How to run ───
# uv run tools/scenario/validate_han_route_node_selection.py --help
from __future__ import annotations

_SIZE_OK_MARKER = "# noqa: SIZE_OK"

import argparse
import hashlib
import json
import re
import sys
from dataclasses import dataclass
from pathlib import Path
from uuid import UUID

ROOT = Path(__file__).resolve().parents[2]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from tools.scenario.han_route_node_selection import (
    ALLOWED_NODE_CLASSES,
    DERIVED_RESEED_REFERENCES,
    EXPECTED_FORBIDDEN_SELECTIONS,
    EXPECTED_REWRITE_SURFACES,
    IMMUTABLE_AUDIT_REFERENCES,
    MUTABLE_REFERENCES,
)

DEFAULT_CANDIDATE = ROOT / "data/curated/han/route-node-selection-candidates-v1.json"
DEFAULT_CATALOG = ROOT / "data/curated/han/administrative-units.json"
DEFAULT_OVERLAY = ROOT / "data/map/administrative-place-overlay.json"
DEFAULT_CLAIMS = ROOT / "data/curated/han/route-node-source-claims-v1.json"
DEFAULT_SELECTION = ROOT / "data/curated/han/route-node-selection-v1.json"
DEFAULT_MIGRATION = ROOT / "data/curated/han/route-node-migration-v1.json"
DEFAULT_SCENARIOS = ROOT / "infra/src/main/resources/scenario"
EXPECTED_COUNT = 780
EXPECTED_SCENARIOS = 31
GUZI_ADMIN_ID = "hhs:113:上郡:009"
FORBIDDEN_FIELDS = frozenset(
    {"coordinate", "coordinates", "lon", "lat", "presentLocation", "presLoc", "recordIndex"}
)
FORBIDDEN_RATIONALE = re.compile(r"nearest|distance|closest|shortest|근거리|최단|거리", re.IGNORECASE)
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
    start_year: int
    sha256: str


@dataclass(frozen=True, slots=True)
class ValidationDocuments:
    candidate: JsonObject
    catalog: JsonObject
    overlay: JsonObject
    external_claims: JsonObject
    selection: JsonObject
    migration: JsonObject
    scenarios: tuple[ScenarioResource, ...]
    candidate_sha256: str
    catalog_sha256: str
    overlay_sha256: str
    claims_sha256: str
    selection_sha256: str
    migration_sha256: str
    source_root: Path
    connections: JsonObject | None
    connections_sha256: str | None


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


@dataclass(frozen=True, slots=True)
class MigrationDerivation:
    physical_place_correction_count: int
    historical_binding_correction_ids: frozenset[int]


@dataclass(frozen=True, slots=True)
class SelectionIndexes:
    candidates: dict[int, JsonObject]
    nodes: dict[int, JsonObject]
    claims: dict[str, JsonObject]


def _fail(message: str) -> None:
    raise SelectionContractError(message)


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


def _catalog_index(catalog: dict) -> dict[str, str]:
    units: dict[str, str] = {}
    for raw_group in _rows(catalog, "groups"):
        group = _mapping(raw_group, "catalog group")
        canonical = _text(group, "canonicalGroup")
        volume = _integer(group, "sourceVolume")
        for raw_unit in _rows(group, "units"):
            unit = _mapping(raw_unit, "catalog unit")
            ordinal = _integer(unit, "ordinal")
            if unit.get("canonicalGroup") != canonical or unit.get("sourceVolume") != volume:
                _fail("catalog administrative identity is malformed")
            unit_id = f"hhs:{volume}:{canonical}:{ordinal:03d}"
            if unit_id in units:
                _fail(f"duplicate administrative unit: {unit_id}")
            units[unit_id] = _text(unit, "sourceNameStatus")
    if catalog.get("expectedUnitCount") != len(units) or catalog.get("detectedUnitCount") != len(units):
        _fail("catalog declared unit counts do not match")
    return units


def _overlay_index(overlay: dict, catalog: dict[str, str]) -> dict[str, tuple[str, tuple[str, ...]]]:
    if overlay.get("sourceYear") != 220:
        _fail("administrative overlay sourceYear must be 220")
    indexed: dict[str, tuple[str, tuple[str, ...]]] = {}
    for raw in _rows(overlay, "administrativeUnits"):
        row = _mapping(raw, "overlay row")
        unit_id = _text(row, "administrativeUnitId")
        if unit_id not in catalog or unit_id in indexed:
            _fail(f"unknown or duplicate overlay administrative unit: {unit_id}")
        status = _text(row, "joinStatus")
        if status == "RESOLVED_POINT":
            candidates = [_mapping(row.get("selectedCandidate"), "selectedCandidate")]
        elif status == "AMBIGUOUS_POINT":
            candidates = [_mapping(value, "overlay candidate") for value in _rows(row, "candidates")]
        elif status in {"NO_COORDINATE_CANDIDATE", "SOURCE_PLACEHOLDER"}:
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


def _candidate_index(candidate: dict, catalog: dict[str, str]) -> dict[int, dict]:
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
    source = (source_root / corpus_path).resolve()
    try:
        source.relative_to(source_root.resolve())
    except ValueError:
        _fail(f"external claim source record escapes repository root: {claim_id}")
    if not source.is_file() or _text(record, "snapshotSha256") != _sha256(source):
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
        period = _mapping(claim.get("subjectPeriod"), "subjectPeriod")
        start = _integer(period, "effectiveFromYear")
        end = _integer(period, "effectiveToYear")
        resolution = _mapping(claim.get("locationResolution"), "locationResolution")
        if resolution.get("kind") != "POINT_REF":
            _fail(f"LOCATION_ONLY claim requires POINT_REF: {claim_id}")
        point_ref = _text(resolution, "physicalPlaceId")
        if POINT_REFERENCE.fullmatch(point_ref) is None:
            _fail(f"external claim physicalPlaceRef is not a point reference: {point_ref}")
        if start > end or start in {-9999, 9999} or end in {-9999, 9999}:
            _fail(f"external claim requires a finite lifecycle: {claim_id}")
        records = [_mapping(value, "source record") for value in _rows(claim, "sourceRecords")]
        if not records:
            _fail(f"external claim requires sourceRecords: {claim_id}")
        for record in records:
            _text(record, "sourceBook")
            _integer(record, "volume")
            _verify_source_record(record, claim_id, source_root)
        conflict = _mapping(claim.get("conflictDisposition"), "conflictDisposition")
        if conflict.get("status") not in {"NONE", "ADJUDICATED"}:
            _fail(f"external conflict disposition is invalid: {claim_id}")
        _text(conflict, "rationale")
        indexed[claim_id] = {
            **claim,
            "claimRole": "LOCATION",
            "subjectType": "AdministrativePlace",
            "subjectKey": subject_key,
            "subjectName": subject_name,
            "physicalPlaceRef": point_ref,
            "effectiveFrom": start,
            "effectiveTo": end,
        }
    return indexed


def _scenario_catalog(selection: dict, scenarios: tuple[ScenarioResource, ...]) -> dict[str, ScenarioResource]:
    if len(scenarios) != EXPECTED_SCENARIOS:
        _fail("actual han scenario catalog must contain exactly 31 resources")
    actual = {row.scenario_id: row for row in scenarios}
    if len(actual) != EXPECTED_SCENARIOS or len({row.resource_name for row in scenarios}) != EXPECTED_SCENARIOS:
        _fail("actual han scenario identifiers/resources must be unique")
    catalog = _mapping(selection.get("scenarioCatalog"), "scenarioCatalog")
    declared_rows = [_mapping(value, "scenario catalog row") for value in _rows(catalog, "resources")]
    if catalog.get("resourceCount") != EXPECTED_SCENARIOS or len(declared_rows) != EXPECTED_SCENARIOS:
        _fail("selection scenario catalog must contain exactly 31 resources")
    declared = {
        _text(row, "scenarioId"): (
            _text(row, "resourceName"),
            _integer(row, "startYear"),
            _text(row, "sha256"),
        )
        for row in declared_rows
    }
    expected = {key: (row.resource_name, row.start_year, row.sha256) for key, row in actual.items()}
    if declared != expected:
        _fail("selection scenario catalog does not exact-match actual resource names/dates/hashes")
    return {scenario_id: actual[scenario_id] for scenario_id in declared}


def _input_hash(selection: dict, name: str) -> str:
    provenance = _mapping(selection.get("provenance"), "provenance")
    inputs = _mapping(provenance.get("inputs"), "provenance.inputs")
    source = _mapping(inputs.get(name), f"provenance input {name}")
    value = _text(source, "sha256")
    if SHA256.fullmatch(value) is None:
        _fail(f"{name} hash is malformed")
    return value


def _binding_token(node: dict) -> tuple[str, str]:
    admin = node.get("administrativeUnitId")
    claim = node.get("sourceClaimId")
    if (isinstance(admin, str) and bool(admin)) == (isinstance(claim, str) and bool(claim)):
        _fail("historical binding requires exactly one administrativeUnitId XOR sourceClaimId")
    if isinstance(admin, str):
        return "HHS", admin
    return "CLAIM", str(claim)


def _validate_lifecycle(node: dict, scenarios: dict[str, ScenarioResource], claim: dict | None) -> None:
    start = _integer(node, "effectiveFrom")
    end = _integer(node, "effectiveTo")
    if start > end or start in {-9999, 9999} or end in {-9999, 9999}:
        _fail("route node requires a finite lifecycle")
    active = node.get("activeScenarioIds")
    if not isinstance(active, list) or any(not isinstance(value, str) for value in active):
        _fail("activeScenarioIds must be an array of scenario ids")
    expected_active = [
        scenario_id for scenario_id, resource in scenarios.items()
        if start <= resource.start_year <= end
    ]
    if active != expected_active:
        _fail("activeScenarioIds must exactly match the lifecycle-derived scenario set")
    expected_states: list[JsonValue] = [
        {
            "scenarioId": scenario_id,
            "startYear": resource.start_year,
            "state": "ACTIVE" if scenario_id in expected_active else "INACTIVE",
        }
        for scenario_id, resource in scenarios.items()
    ]
    if node.get("scenarioStates") != expected_states:
        _fail("scenarioStates must exact-match all 31 scenario ids, years, and lifecycle states")
    if claim is not None:
        claim_start, claim_end = _integer(claim, "effectiveFrom"), _integer(claim, "effectiveTo")
        if start != claim_start or end != claim_end:
            _fail("route-node lifecycle must exact-match its LOCATION_ONLY claim")
    name = _text(node, "displayName")
    if name == "流求" and active:
        _fail("流求 must have zero active scenarios")
    if name == "帶方郡" and any(scenarios[value].start_year < 204 for value in active):
        _fail("帶方郡 must be inactive before 204")


def _validate_adjudication(node: dict, refs: tuple[str, ...]) -> None:
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
    _review_reason(raw, "locationAdjudication")


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
) -> MigrationDerivation:
    migration = documents.migration
    selection = documents.selection
    candidates = indexes.candidates
    nodes = indexes.nodes
    claims = indexes.claims
    if migration.get("mode") != "NEW_WORLD_ONLY":
        _fail("migration mode must be NEW_WORLD_ONLY")
    if migration.get("sourceSelectionId") != selection.get("selectionId"):
        _fail("migration source selection id does not match")
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
        binding_changed = _prior_binding(candidate) != _new_binding(node, claims)
        name_changed = candidate.get("legacyNameCh") != node.get("displayName")
        parent_changed = candidate.get("legacyOwnerGroup") != node.get("parentName")
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
            counters["physicalPlaceCorrectionCount"] += 1
        elif correction is not None:
            _fail("physicalPlaceCorrection exists without a retained physical-place change")
        if disposition == "RETAINED" and not physical_changed:
            if migration_disposition == "RETAINED_SAME_NODE" and binding_changed:
                _fail("retained node cannot hide a historical binding change")
            if migration_disposition == "CORRECTED_BINDING_SAME_NODE" and not (
                binding_changed or candidate.get("classification") == "HHS_ATTRIBUTION_CONFLICT"
            ):
                _fail("binding correction disposition requires an observable or reviewed attribution change")
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
    review_policy = _mapping(selection.get("reviewPolicy"), "reviewPolicy")
    attribution_rows = review_policy.get("legacyAttributionCorrections")
    if not isinstance(attribution_rows, list) or any(
        not isinstance(value, int) or isinstance(value, bool) for value in attribution_rows
    ):
        _fail("reviewPolicy legacyAttributionCorrections must be an integer array")
    if len(attribution_rows) != len(set(attribution_rows)):
        _fail("reviewPolicy legacyAttributionCorrections must be unique")
    if not set(attribution_rows).issubset(historical_correction_ids):
        _fail("reviewPolicy legacyAttributionCorrections must reference actual binding changes")
    return MigrationDerivation(
        physical_place_correction_count=counters["physicalPlaceCorrectionCount"],
        historical_binding_correction_ids=frozenset(historical_correction_ids),
    )


def _validate_connections(connections: dict, route_keys: set[str]) -> None:
    _forbid_fields(connections, "candidateConnections")
    if connections.get("lifecycle") != "CANDIDATE_ONLY":
        _fail("connection lifecycle must remain CANDIDATE_ONLY")
    edges: list[tuple[str, str]] = []
    for raw in _rows(connections, "connections"):
        edge = _mapping(raw, "connection")
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
    }
    if any(document.get("schemaVersion") != 1 for document in versioned.values()):
        _fail("every W0-C input schemaVersion must be 1")
    if documents.connections is not None and documents.connections.get("schemaVersion") != 1:
        _fail("candidate connections schemaVersion must be 1")
    if documents.selection.get("runtimeScenarioActivationEnforcement") != "NOT_CLAIMED_BY_W0_DATA_CONTRACT":
        _fail("runtimeScenarioActivationEnforcement must be NOT_CLAIMED_BY_W0_DATA_CONTRACT")
    if _input_hash(documents.selection, "candidate") != documents.candidate_sha256:
        _fail("selection candidate hash reference does not match")
    if _input_hash(documents.selection, "administrativeCatalog") != documents.catalog_sha256:
        _fail("selection catalog hash reference does not match")
    if _input_hash(documents.selection, "administrativePlaceOverlay") != documents.overlay_sha256:
        _fail("selection overlay hash reference does not match")
    if _input_hash(documents.selection, "externalClaims") != documents.claims_sha256:
        _fail("selection external claims hash reference does not match")
    _forbid_fields(documents.selection, "selection")
    _forbid_fields(documents.migration, "migration")
    catalog = _catalog_index(documents.catalog)
    if documents.overlay.get("catalogId") != documents.catalog.get("catalogId"):
        _fail("overlay catalogId must match the administrative catalog")
    overlay = _overlay_index(documents.overlay, catalog)
    candidates = _candidate_index(documents.candidate, catalog)
    claims = _claims_index(documents.external_claims, documents.source_root)
    scenarios = _scenario_catalog(documents.selection, documents.scenarios)
    if documents.selection.get("reviewState") != "APPROVED":
        _fail("selection artifact must be APPROVED")
    raw_nodes = [_mapping(value, "route node") for value in _rows(documents.selection, "routeNodes")]
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
    if len(physical_refs) != len(set(physical_refs)):
        _fail("physicalPlaceRef values must be unique")
    bindings = [_binding_token(node) for node in raw_nodes]
    if sum(binding == ("HHS", GUZI_ADMIN_ID) for binding in bindings) != 1:
        _fail(f"{GUZI_ADMIN_ID} must bind exactly one approved route node")
    if len(bindings) != len(set(bindings)):
        _fail("historical binding references must be unique")
    used_claims: set[str] = set()
    ambiguous_count = 0
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
        _review_reason(rationale, "selectionRationale")
        binding = _binding_token(node)
        claim: dict | None = None
        if binding[0] == "HHS":
            unit_id = binding[1]
            status = catalog.get(unit_id)
            if status is None:
                _fail(f"unknown administrative binding: {unit_id}")
            if status == "SOURCE_PLACEHOLDER":
                _fail(f"source placeholder cannot bind a route node: {unit_id}")
            join_status, refs = overlay[unit_id]
            if join_status == "RESOLVED_POINT":
                if node.get("physicalPlaceRef") not in refs:
                    _fail(f"resolved HHS binding physicalPlaceRef mismatch: {unit_id}")
                adjudication = _mapping(node.get("locationAdjudication"), "locationAdjudication")
                if adjudication != {"kind": "W0B_GLOBAL_UNIQUE_220"}:
                    _fail("resolved HHS binding must cite the W0-B global-unique review batch")
            if join_status == "AMBIGUOUS_POINT":
                _validate_adjudication(node, refs)
                ambiguous_count += 1
            if join_status == "SOURCE_PLACEHOLDER":
                _fail(f"source placeholder overlay cannot bind a route node: {unit_id}")
            if join_status == "NO_COORDINATE_CANDIDATE":
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
        if candidate.get("classification") == "HHS_ATTRIBUTION_CONFLICT":
            _validate_conflict(node, candidate, binding)
        elif "historicalConflictDisposition" in node:
            _fail("historicalConflictDisposition is only valid for attribution conflicts")
        _validate_lifecycle(node, scenarios, claim)
    if used_claims != set(claims) or location_count != 8 or external_count != 0:
        _fail("all exactly 8 LOCATION_ONLY claims must be used by one route node each; external bindings are forbidden")
    migration = _validate_migration(
        documents,
        SelectionIndexes(candidates=candidates, nodes=nodes, claims=claims),
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
        map_info = _mapping(document.get("map"), f"{path.name}.map")
        if map_info.get("mapName") != "han":
            continue
        match = re.fullmatch(r"scenario_(.+)\.json", path.name)
        if match is None:
            _fail(f"invalid scenario resource name: {path.name}")
        resources.append(
            ScenarioResource(
                scenario_id=match.group(1),
                resource_name=path.name,
                start_year=_integer(document, "startYear"),
                sha256=_sha256(path),
            )
        )
    return tuple(resources)


def load_documents(paths: ValidationPaths) -> ValidationDocuments:
    connections = _load(paths.connections) if paths.connections is not None else None
    return ValidationDocuments(
        candidate=_load(paths.candidate),
        catalog=_load(paths.catalog),
        overlay=_load(paths.overlay),
        external_claims=_load(paths.external_claims),
        selection=_load(paths.selection),
        migration=_load(paths.migration),
        scenarios=_load_scenarios(paths.scenarios_dir),
        candidate_sha256=_sha256(paths.candidate),
        catalog_sha256=_sha256(paths.catalog),
        overlay_sha256=_sha256(paths.overlay),
        claims_sha256=_sha256(paths.external_claims),
        selection_sha256=_sha256(paths.selection),
        migration_sha256=_sha256(paths.migration),
        source_root=ROOT,
        connections=connections,
        connections_sha256=_sha256(paths.connections) if paths.connections is not None else None,
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
    )
    try:
        report = validate_documents(load_documents(paths))
    except (SelectionContractError, FileNotFoundError, OSError, UnicodeError, json.JSONDecodeError) as error:
        print(f"han route-node selection validation failed: {error}", file=sys.stderr)
        return 2
    print(
        "han route-node selection valid: "
        f"approved={report.approved_count} scenarios={report.scenario_count} "
        f"selectionSha256={report.selection_sha256} migrationSha256={report.migration_sha256}"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
