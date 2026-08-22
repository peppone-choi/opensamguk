from __future__ import annotations

from tools.map.external_world_contract import (
    COMPATIBILITY,
    PACK_IDS,
    PACK_LEGACY_IDS,
    SOURCE_SPECS,
    ContractError,
    ExternalWorldContext,
    GeneratedDocuments,
    JsonObject,
    require_array,
    require_object,
    source_registry,
)
from tools.map.route_network_contract import require_text

MOBILE_POLITIES = frozenset({"西羌", "烏桓", "鮮卑", "南匈奴"})
INACTIVE_ALIASES = frozenset({"大伽耶", "星山伽耶", "古寧伽耶", "流求"})


def _entities(pack: JsonObject) -> list[JsonObject]:
    return [require_object(row, "entity") for row in require_array(pack, "entities")]


def _by_name(pack: JsonObject) -> dict[str, JsonObject]:
    return {require_text(entity, "canonicalName"): entity for entity in _entities(pack)}


def _validate_sources(pack: JsonObject, context: ExternalWorldContext, pack_id: str) -> set[str]:
    actual = [require_object(row, "source") for row in require_array(pack, "sourceRegistry")]
    expected = source_registry(context, pack_id)
    if actual != expected:
        raise ContractError("source path, SHA, line range, or verbatim binding drift")
    refs = {require_text(row, "sourceRef") for row in actual}
    if len(refs) != len(SOURCE_SPECS[pack_id]):
        raise ContractError("source registry identity drift")
    return refs


def _validate_claims(pack: JsonObject, source_refs: set[str]) -> None:
    entities = {require_text(entity, "entityKey"): entity for entity in _entities(pack)}
    claim_ids: set[str] = set()
    claim_values: set[str] = set()
    for raw in require_array(pack, "claims"):
        claim = require_object(raw, "claim")
        claim_id = require_text(claim, "claimId")
        evidence = require_text(claim, "evidenceClass")
        proximity = require_text(claim, "sourceProximity")
        if claim_id in claim_ids or claim.get("sourceRef") not in source_refs or claim.get("subjectEntityKey") not in entities:
            raise ContractError("claim source or identity binding drift")
        if evidence not in COMPATIBILITY or proximity not in COMPATIBILITY[evidence]:
            raise ContractError("evidenceClass and sourceProximity are incompatible")
        claim_ids.add(claim_id)
        claim_values.add(require_text(claim, "value"))
    if pack.get("packId") == "east-sea-wa" and not {"邪馬壹國", "邪馬臺國"}.issubset(claim_values):
        raise ContractError("邪馬壹 and 邪馬臺 variant claims must remain separate")
    for entity in entities.values():
        refs = {str(value) for value in require_array(entity, "claimRefs")}
        if entity.get("reviewState") == "APPROVED" and not refs:
            raise ContractError("approved entity requires at least one source-backed claim")
        if not refs.issubset(claim_ids):
            raise ContractError("entity claimRefs drift")


def _validate_scenarios(pack: JsonObject, context: ExternalWorldContext) -> None:
    expected_source = [(row.get("scenarioId"), row.get("startYear")) for row in context.scenarios]
    for entity in _entities(pack):
        lifecycle = require_object(entity.get("lifecycle"), "lifecycle")
        states = [require_object(row, "scenario state") for row in require_array(entity, "scenarioStates")]
        name = require_text(entity, "canonicalName")
        if len(states) != 31 or [(row.get("scenarioId"), row.get("startYear")) for row in states] != expected_source:
            raise ContractError("entity scenarioStates must exact-match all 31 scenario resources")
        if name in INACTIVE_ALIASES and (entity.get("reviewState") != "INACTIVE_ALIAS_CANDIDATE" or any(row.get("state") != "INACTIVE" for row in states)):
            raise ContractError(f"inactive alias drift: {name}")
        expected_lifecycle: JsonObject = {"status": "KNOWN_FROM_SOURCE", "effectiveFrom": 245, "effectiveTo": None} if name == "扶南" else {"status": "UNKNOWN", "effectiveFrom": None, "effectiveTo": None}
        expected_reason = "BEFORE_SOURCE_ATTESTATION_245" if name == "扶南" else "UNKNOWN_LIFECYCLE_FAIL_CLOSED"
        if lifecycle != expected_lifecycle:
            raise ContractError(f"lifecycle source boundary drift: {name}")
        if any(row.get("state") != "INACTIVE" or row.get("reason") != expected_reason for row in states):
            raise ContractError("lifecycle must fail closed to INACTIVE for all current scenarios")


def _validate_special(documents: GeneratedDocuments) -> None:
    east = documents["east-sea-wa-v1.json"]
    east_names = _by_name(east)
    expected_names = ("對馬國", "一大國", "末盧國", "伊都國", "奴國", "不彌國", "投馬國", "邪馬壹國")
    expected_sequence = [require_text(east_names[name], "entityKey") for name in expected_names]
    itineraries = [require_object(row, "itinerary") for row in require_array(east, "relativeItineraries")]
    if len(itineraries) != 1:
        raise ContractError("Wa itinerary must be unique")
    itinerary = itineraries[0]
    legs = [{"ordinal": index + 1, "fromEntityKey": expected_sequence[index], "toEntityKey": expected_sequence[index + 1]} for index in range(7)]
    if itinerary.get("sequence") != expected_sequence or itinerary.get("legs") != legs or itinerary.get("traversable") is not False or itinerary.get("corridorRefs") != []:
        raise ContractError("Wa itinerary sequence or direct corridor drift")
    claims = [require_object(row, "claim") for row in require_array(east, "claims")]
    if not any(claim.get("value") == "邪馬壹國" for claim in claims) or not any(claim.get("value") == "邪馬臺國" and claim.get("predicate") == "NAME_VARIANT" for claim in claims):
        raise ContractError("邪馬壹 and 邪馬臺 variant claims must remain separate")
    for pack_id in PACK_IDS:
        for name, entity in _by_name(documents[f"{pack_id}-v1.json"]).items():
            states = [require_object(row, "scenario state") for row in require_array(entity, "scenarioStates")]
            if name in INACTIVE_ALIASES and (entity.get("reviewState") != "INACTIVE_ALIAS_CANDIDATE" or any(row.get("state") != "INACTIVE" for row in states)):
                raise ContractError(f"inactive alias drift: {name}")
            if name in MOBILE_POLITIES and entity.get("entityType") != "PolityPresence":
                raise ContractError(f"mobile polity must remain PolityPresence, not anchored: {name}")
            if name == "夷洲" and entity.get("locationStatus") not in {"CANDIDATE_REGION", "REMOTE_ONLY"}:
                raise ContractError("夷洲 location cannot be a point")
    north_names = set(_by_name(documents["northern-steppe-v1.json"]))
    if north_names != {"烏桓", "鮮卑", "南匈奴"}:
        raise ContractError("鮮卑 spatial divisions are claims, not entities")


def validate_source_binding(documents: GeneratedDocuments, context: ExternalWorldContext) -> None:
    _validate_special(documents)
    coverage: set[str] = set()
    for pack_id in PACK_IDS:
        pack = documents[f"{pack_id}-v1.json"]
        source_refs = _validate_sources(pack, context, pack_id)
        _validate_claims(pack, source_refs)
        _validate_scenarios(pack, context)
        expected_coverage = [f"legacy-external:{source_id}" for source_id in PACK_LEGACY_IDS[pack_id]]
        if pack.get("coverageLedger") != expected_coverage:
            raise ContractError("pack coverage source binding drift")
        coverage.update(expected_coverage)
    if len(coverage) != 37:
        raise ContractError("external pack source coverage must equal 37")
