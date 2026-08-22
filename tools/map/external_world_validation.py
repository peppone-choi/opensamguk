from __future__ import annotations

import hashlib
from typing import Final

from tools.map.external_world_contract import (
    EXTERNAL_CANDIDATE_SHA,
    PACK_IDS,
    PACK_LEGACY_IDS,
    ROUTE_CONTRACT_SHA,
    RUNTIME_BOUNDARY,
    SUPERSEDE_IDS,
    ContractError,
    ExternalWorldContext,
    GeneratedDocuments,
    JsonObject,
    require_array,
    require_object,
    serialize,
)
from tools.map.external_world_source_validation import validate_source_binding
from tools.map.route_network_contract import require_text

DOCUMENT_NAMES: Final = frozenset({"external-world-pack-index-v1.json", "external-world-legacy-adjudications-v1.json", *(f"{pack_id}-v1.json" for pack_id in PACK_IDS)})
INDEX_KEYS: Final = frozenset({"schemaVersion", "indexId", "reviewState", "sourceCandidateSetId", "sourceCandidateSetSha256", "sourceRouteContractId", "sourceRouteContractSha256", "packs", "summary", "runtimeActivation"})
INDEX_PACK_KEYS: Final = frozenset({"packId", "path", "sha256", "legacyCandidateCount"})
INDEX_SUMMARY_KEYS: Final = frozenset({"packCount", "legacyCandidateCount", "approvedCorridorCount", "entityCount", "claimCount"})
LEDGER_KEYS: Final = frozenset({"schemaVersion", "ledgerId", "reviewState", "sourceCandidateSetId", "sourceCandidateSetSha256", "adjudications", "summary", "runtimeActivation"})
LEDGER_SUMMARY_KEYS: Final = frozenset({"candidateCount", "supersedeCount", "rejectCount", "packCandidateCount", "approvedResultCount"})
ADJUDICATION_KEYS: Final = frozenset({"candidateKey", "legacyRowIndex", "rawLegacySha256", "disposition", "packId", "resultEntityKeys", "supersededByRefs", "reasonCode"})
PACK_KEYS: Final = frozenset({"schemaVersion", "packId", "reviewState", "sourceCandidateSetId", "sourceCandidateSetSha256", "sourceRegistry", "entities", "claims", "relativeItineraries", "coverageLedger", "summary", "runtimeActivation"})
SOURCE_KEYS: Final = frozenset({"sourceRef", "path", "sha256", "lineStart", "lineEnd", "verbatim", "sourceProximity"})
ENTITY_KEYS: Final = frozenset({"entityKey", "canonicalName", "entityType", "locationStatus", "legacyCandidateKeys", "reviewState", "lifecycle", "scenarioStates", "claimRefs", "aliases"})
LIFECYCLE_KEYS: Final = frozenset({"status", "effectiveFrom", "effectiveTo"})
SCENARIO_KEYS: Final = frozenset({"scenarioId", "startYear", "state", "reason"})
CLAIM_KEYS: Final = frozenset({"claimId", "reviewState", "subjectEntityKey", "predicate", "value", "evidenceClass", "sourceRef", "sourceProximity", "subjectPeriod", "interpretationNote"})
PERIOD_KEYS: Final = frozenset({"status", "from", "to"})
ITINERARY_KEYS: Final = frozenset({"itineraryId", "sequence", "legs", "traversable", "corridorRefs", "sourceRefs"})
LEG_KEYS: Final = frozenset({"ordinal", "fromEntityKey", "toEntityKey"})
PACK_SUMMARY_KEYS: Final = frozenset({"entityCount", "approvedEntityCount", "claimCount", "approvedClaimCount", "itineraryCount", "legacyCandidateCount", "scenarioStateCount"})


def _keys(value: JsonObject, expected: frozenset[str], label: str) -> None:
    if frozenset(value) != expected:
        raise ContractError(f"{label} exact schema key drift")


def _schema(pack: JsonObject) -> None:
    _keys(pack, PACK_KEYS, "pack")
    for raw in require_array(pack, "sourceRegistry"):
        _keys(require_object(raw, "source"), SOURCE_KEYS, "source")
    for raw in require_array(pack, "entities"):
        entity = require_object(raw, "entity")
        _keys(entity, ENTITY_KEYS, "entity")
        _keys(require_object(entity.get("lifecycle"), "lifecycle"), LIFECYCLE_KEYS, "lifecycle")
        for state in require_array(entity, "scenarioStates"):
            _keys(require_object(state, "scenario state"), SCENARIO_KEYS, "scenario state")
    for raw in require_array(pack, "claims"):
        claim = require_object(raw, "claim")
        _keys(claim, CLAIM_KEYS, "claim")
        _keys(require_object(claim.get("subjectPeriod"), "subject period"), PERIOD_KEYS, "subject period")
    for raw in require_array(pack, "relativeItineraries"):
        itinerary = require_object(raw, "itinerary")
        _keys(itinerary, ITINERARY_KEYS, "itinerary")
        for leg in require_array(itinerary, "legs"):
            _keys(require_object(leg, "itinerary leg"), LEG_KEYS, "itinerary leg")
    _keys(require_object(pack.get("summary"), "pack summary"), PACK_SUMMARY_KEYS, "pack summary")


def _pack_semantics(pack: JsonObject, pack_id: str) -> None:
    if pack.get("schemaVersion") != 1 or pack.get("packId") != pack_id or pack.get("reviewState") != "APPROVED_DATA_CONTRACT":
        raise ContractError("pack identity or review state drift")
    if pack.get("sourceCandidateSetId") != "han-external-world-candidates-v1" or pack.get("sourceCandidateSetSha256") != EXTERNAL_CANDIDATE_SHA or pack.get("runtimeActivation") != RUNTIME_BOUNDARY:
        raise ContractError("pack source or runtime boundary drift")
    entities = [require_object(row, "entity") for row in require_array(pack, "entities")]
    claims = [require_object(row, "claim") for row in require_array(pack, "claims")]
    entity_keys = [require_text(entity, "entityKey") for entity in entities]
    claim_ids = [require_text(claim, "claimId") for claim in claims]
    if len(entity_keys) != len(set(entity_keys)) or len(claim_ids) != len(set(claim_ids)):
        raise ContractError("duplicate entity or claim identity")
    if any(entity.get("entityType") not in {"AdministrativePlace", "AnchoredPlace", "PolityPresence", "RemoteGate"} for entity in entities):
        raise ContractError("external entity type drift")
    if any(entity.get("locationStatus") not in {"RELATIVE_ITINERARY", "CANDIDATE_REGION", "REMOTE_ONLY", "SOURCE_TEXT_ONLY"} or require_array(entity, "aliases") for entity in entities):
        raise ContractError("entity location status or alias payload drift")
    if any(claim.get("subjectEntityKey") not in entity_keys for claim in claims):
        raise ContractError("claim subject entity drift")
    expected_summary = {"entityCount": len(entities), "approvedEntityCount": sum(entity.get("reviewState") == "APPROVED" for entity in entities), "claimCount": len(claims), "approvedClaimCount": sum(claim.get("reviewState") == "APPROVED" for claim in claims), "itineraryCount": len(require_array(pack, "relativeItineraries")), "legacyCandidateCount": len(PACK_LEGACY_IDS[pack_id]), "scenarioStateCount": len(entities) * 31}
    if require_object(pack.get("summary"), "summary") != expected_summary:
        raise ContractError("pack summary must equal derived counts")


def _ledger(documents: GeneratedDocuments, context: ExternalWorldContext) -> None:
    ledger = documents["external-world-legacy-adjudications-v1.json"]
    _keys(ledger, LEDGER_KEYS, "legacy ledger")
    _keys(require_object(ledger.get("summary"), "ledger summary"), LEDGER_SUMMARY_KEYS, "ledger summary")
    if ledger.get("schemaVersion") != 1 or ledger.get("ledgerId") != "han-external-world-legacy-adjudications-v1" or ledger.get("reviewState") != "APPROVED_DATA_CONTRACT" or ledger.get("sourceCandidateSetId") != "han-external-world-candidates-v1" or ledger.get("sourceCandidateSetSha256") != EXTERNAL_CANDIDATE_SHA or ledger.get("runtimeActivation") != RUNTIME_BOUNDARY:
        raise ContractError("legacy ledger identity, source, or runtime boundary drift")
    source_rows = [require_object(row, "candidate") for row in require_array(context.candidates, "candidates")]
    rows = [require_object(row, "adjudication") for row in require_array(ledger, "adjudications")]
    if len(rows) != 65:
        raise ContractError("legacy ledger must contain exactly 65 rows")
    pack_by_source_id = {source_id: pack_id for pack_id, source_ids in PACK_LEGACY_IDS.items() for source_id in source_ids}
    source_id_by_candidate = {
        require_text(source, "candidateKey"): require_text(require_object(source.get("rawLegacy"), "source raw legacy"), "id")
        for source in source_rows
    }
    results_by_candidate: dict[str, list[str]] = {}
    for pack_id in PACK_IDS:
        for entity in [require_object(value, "entity") for value in require_array(documents[f"{pack_id}-v1.json"], "entities")]:
            entity_key = require_text(entity, "entityKey")
            for candidate_key in require_array(entity, "legacyCandidateKeys"):
                if not isinstance(candidate_key, str):
                    raise ContractError("entity legacy candidate key must be text")
                source_id = source_id_by_candidate.get(candidate_key)
                if source_id is None or pack_by_source_id.get(source_id) != pack_id:
                    raise ContractError("entity legacy candidate pack partition drift")
                results_by_candidate.setdefault(candidate_key, []).append(entity_key)
    packed: set[str] = set()
    for index, (row, source) in enumerate(zip(rows, source_rows, strict=True)):
        _keys(row, ADJUDICATION_KEYS, "adjudication")
        raw = require_object(source.get("rawLegacy"), "source raw legacy")
        source_id = require_text(raw, "id")
        disposition = row.get("disposition")
        results = require_array(row, "resultEntityKeys")
        superseded = require_array(row, "supersededByRefs")
        raw_fingerprint = hashlib.sha256(serialize(raw).encode()).hexdigest()
        if row.get("candidateKey") != source.get("candidateKey") or row.get("legacyRowIndex") != index or row.get("rawLegacySha256") != raw_fingerprint:
            raise ContractError("legacy raw source binding drift")
        expected_pack = pack_by_source_id.get(source_id)
        if row.get("packId") != expected_pack:
            raise ContractError("legacy candidate pack partition drift")
        if source_id in SUPERSEDE_IDS and (disposition != "SUPERSEDE" or superseded != ["han-route-node-selection-v1"] or results):
            raise ContractError("SUPERSEDE disposition semantics drift")
        if source_id == "X026" and (disposition != "REJECT" or results or superseded):
            raise ContractError("REJECT disposition semantics drift")
        if expected_pack is not None:
            packed.add(require_text(row, "candidateKey"))
            expected_results = results_by_candidate.get(require_text(row, "candidateKey"), [])
            expected_disposition = "APPROVE" if len(expected_results) == 1 else "SPLIT" if len(expected_results) >= 2 else None
            if results != expected_results:
                raise ContractError("legacy candidate result entity binding drift")
            if disposition != expected_disposition or superseded:
                raise ContractError("pack disposition semantics drift")
    if len(packed) != 37:
        raise ContractError("legacy pack membership must contain exactly 37 candidates")
    expected_summary = {"candidateCount": 65, "supersedeCount": 27, "rejectCount": 1, "packCandidateCount": 37, "approvedResultCount": 37}
    if ledger.get("summary") != expected_summary:
        raise ContractError("legacy ledger summary drift")


def _index(documents: GeneratedDocuments) -> None:
    index = documents["external-world-pack-index-v1.json"]
    _keys(index, INDEX_KEYS, "pack index")
    _keys(require_object(index.get("summary"), "index summary"), INDEX_SUMMARY_KEYS, "index summary")
    if index.get("schemaVersion") != 1 or index.get("indexId") != "han-external-world-pack-index-v1" or index.get("reviewState") != "APPROVED_DATA_CONTRACT" or index.get("sourceCandidateSetId") != "han-external-world-candidates-v1" or index.get("sourceRouteContractId") != "han-route-network-contract-v1":
        raise ContractError("pack index identity or source id drift")
    rows = [require_object(row, "pack index row") for row in require_array(index, "packs")]
    if len(rows) != 5 or tuple(row.get("packId") for row in rows) != PACK_IDS:
        raise ContractError("pack index must contain exactly five packs")
    for row, pack_id in zip(rows, PACK_IDS, strict=True):
        _keys(row, INDEX_PACK_KEYS, "pack index row")
        pack = documents[f"{pack_id}-v1.json"]
        expected_path = f"data/curated/han/external-world-packs/{pack_id}-v1.json"
        if row.get("path") != expected_path or row.get("sha256") != hashlib.sha256(serialize(pack).encode()).hexdigest() or row.get("legacyCandidateCount") != len(PACK_LEGACY_IDS[pack_id]):
            raise ContractError("pack index path or SHA binding drift")
    if index.get("sourceCandidateSetSha256") != EXTERNAL_CANDIDATE_SHA or index.get("sourceRouteContractSha256") != ROUTE_CONTRACT_SHA or index.get("runtimeActivation") != RUNTIME_BOUNDARY:
        raise ContractError("pack index source or runtime boundary drift")
    packs = [documents[f"{pack_id}-v1.json"] for pack_id in PACK_IDS]
    expected_summary = {"packCount": 5, "legacyCandidateCount": 37, "approvedCorridorCount": 0, "entityCount": sum(len(require_array(pack, "entities")) for pack in packs), "claimCount": sum(len(require_array(pack, "claims")) for pack in packs)}
    if index.get("summary") != expected_summary:
        raise ContractError("pack index summary must equal derived counts")


def validate_documents(documents: GeneratedDocuments, context: ExternalWorldContext) -> None:
    if frozenset(documents) != DOCUMENT_NAMES:
        raise ContractError("exactly five packs plus index and ledger are required")
    _ledger(documents, context)
    coverage: list[str] = []
    for pack_id in PACK_IDS:
        pack = documents[f"{pack_id}-v1.json"]
        _schema(pack)
        coverage.extend(str(value) for value in require_array(pack, "coverageLedger"))
    if len(coverage) != 37 or len(set(coverage)) != 37:
        raise ContractError("pack coverage membership must be exact and disjoint")
    validate_source_binding(documents, context)
    for pack_id in PACK_IDS:
        _pack_semantics(documents[f"{pack_id}-v1.json"], pack_id)
    _index(documents)
