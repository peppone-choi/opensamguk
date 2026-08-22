from __future__ import annotations

import argparse
import hashlib
import sys
from pathlib import Path
from typing import Final

ROOT: Final = Path(__file__).resolve().parents[2]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

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
    load_context,
    require_array,
    require_object,
    serialize,
    source_registry,
    write_documents,
)
from tools.map.route_network_contract import require_text

DEFAULT_OUTPUT: Final = ROOT / "data/curated/han"
ADDITIONAL_NAMES: Final = {
    "east-sea-wa": ("不彌國", "投馬國"),
    "northeast": ("高句麗",),
    "western-regions": ("玉門", "陽關", "鄯善", "伊吾", "車師前部高昌壁", "車師後部金滿城", "大月氏", "安息", "大宛", "康居", "奄蔡"),
    "northern-steppe": (),
    "southern-maritime": ("林邑", "扶南"),
}
ENTITY_TYPES: Final = {
    "帶方郡": "AdministrativePlace", "玉門": "RemoteGate", "陽關": "RemoteGate", "邪馬壹國": "RemoteGate", "夷洲": "RemoteGate", "流求": "RemoteGate",
}
POLITIES: Final = frozenset({"夫餘", "高句麗", "東沃沮", "北沃沮", "濊", "挹婁", "西羌", "烏桓", "鮮卑", "南匈奴", "山越", "林邑", "扶南"})
ALIAS_CANDIDATES: Final = frozenset({"大伽耶", "星山伽耶", "古寧伽耶", "流求", "國內城", "卒本"})
SOURCE_UNKNOWN_NAMES: Final = frozenset({"悉直國", "押督國", "召文國", "于山國", "目支國"})
CLAIMLESS_NAMES: Final = SOURCE_UNKNOWN_NAMES | frozenset({"大伽耶", "星山伽耶", "古寧伽耶", "國內城", "卒本"})
RELATIVE: Final = frozenset({"不彌國", "投馬國"})
CANDIDATE_REGION: Final = frozenset({"邪馬壹國", "夷洲"})
WA_ITINERARY_NAMES: Final = frozenset({"對馬國", "一大國", "末盧國", "伊都國", "奴國", "不彌國", "投馬國", "邪馬壹國"})


def _legacy_rows(context: ExternalWorldContext) -> dict[str, JsonObject]:
    rows = [require_object(row, "external candidate") for row in require_array(context.candidates, "candidates")]
    return {require_text(require_object(row.get("rawLegacy"), "raw legacy"), "id"): row for row in rows}


def _entity(pack_id: str, index: int, name: str, candidate_key: str | None, scenarios: tuple[JsonObject, ...]) -> JsonObject:
    entity_key = f"external:{pack_id}:{index:02d}"
    entity_type = ENTITY_TYPES.get(name, "PolityPresence" if name in POLITIES else "AnchoredPlace")
    status = "INACTIVE_ALIAS_CANDIDATE" if name in ALIAS_CANDIDATES else "INACTIVE_SOURCE_UNKNOWN" if name in SOURCE_UNKNOWN_NAMES else "APPROVED"
    location = "RELATIVE_ITINERARY" if name in RELATIVE else "CANDIDATE_REGION" if name in CANDIDATE_REGION else "REMOTE_ONLY" if entity_type == "PolityPresence" else "SOURCE_TEXT_ONLY"
    known_from = 245 if name == "扶南" else None
    states: list[JsonObject] = []
    for scenario in scenarios:
        start = scenario.get("startYear")
        scenario_id = require_text(scenario, "scenarioId")
        if not isinstance(start, int) or isinstance(start, bool):
            raise ContractError("scenario startYear must be an integer")
        reason = "BEFORE_SOURCE_ATTESTATION_245" if known_from is not None else "UNKNOWN_LIFECYCLE_FAIL_CLOSED"
        states.append({"scenarioId": scenario_id, "startYear": start, "state": "INACTIVE", "reason": reason})
    return {
        "entityKey": entity_key,
        "canonicalName": name,
        "entityType": entity_type,
        "locationStatus": location,
        "legacyCandidateKeys": [] if candidate_key is None else [candidate_key],
        "reviewState": status,
        "lifecycle": {"status": "KNOWN_FROM_SOURCE" if known_from is not None else "UNKNOWN", "effectiveFrom": known_from, "effectiveTo": None},
        "scenarioStates": states,
        "claimRefs": [] if name in CLAIMLESS_NAMES else [f"claim:{pack_id}:{index:02d}"],
        "aliases": [],
    }


def _source_ref(pack_id: str, name: str, registry: list[JsonObject]) -> str:
    index = 0
    if pack_id == "east-sea-wa" and name in WA_ITINERARY_NAMES: index = 1
    if pack_id == "northeast" and name != "帶方郡": index = 1
    if pack_id == "western-regions": index = 2 if name == "西羌" else 3 if name == "白馬氐" else 0 if name in {"玉門", "陽關", "鄯善", "伊吾", "車師前部高昌壁", "車師後部金滿城"} else 1
    if pack_id == "northern-steppe" and name == "南匈奴": index = 1
    if pack_id == "southern-maritime": index = {"山越": 0, "林邑": 1, "夷洲": 2, "扶南": 3, "流求": 4}[name]
    return require_text(registry[index], "sourceRef")


def _pack(context: ExternalWorldContext, pack_id: str, legacy: dict[str, JsonObject]) -> JsonObject:
    names: list[tuple[str, str | None]] = []
    for source_id in PACK_LEGACY_IDS[pack_id]:
        raw = require_object(legacy[source_id].get("rawLegacy"), "raw legacy")
        names.append((require_text(raw, "nameFt"), require_text(legacy[source_id], "candidateKey")))
    names.extend((name, None) for name in ADDITIONAL_NAMES[pack_id])
    registry = source_registry(context, pack_id)
    entities = [_entity(pack_id, index, name, candidate, context.scenarios) for index, (name, candidate) in enumerate(names)]
    claims: list[JsonObject] = []
    for entity in entities:
        entity_key = require_text(entity, "entityKey")
        name = require_text(entity, "canonicalName")
        claim_refs = require_array(entity, "claimRefs")
        if not claim_refs:
            continue
        claim_id = str(claim_refs[0])
        claims.append({"claimId": claim_id, "reviewState": "APPROVED", "subjectEntityKey": entity_key, "predicate": "HISTORICAL_ATTESTATION", "value": name, "evidenceClass": "PRIMARY_ATTESTED", "sourceRef": _source_ref(pack_id, name, registry), "sourceProximity": "OFFICIAL_HISTORY", "subjectPeriod": {"status": "UNKNOWN", "from": None, "to": None}, "interpretationNote": "No lifecycle date inferred from attestation."})
    if pack_id == "east-sea-wa":
        target = next(entity for entity in entities if entity.get("canonicalName") == "邪馬壹國")
        claims.append({"claimId": "claim:east-sea-wa:yamatai-variant", "reviewState": "APPROVED", "subjectEntityKey": target["entityKey"], "predicate": "NAME_VARIANT", "value": "邪馬臺國", "evidenceClass": "PRIMARY_ATTESTED", "sourceRef": "sgz-30-wa-itinerary", "sourceProximity": "OFFICIAL_HISTORY", "subjectPeriod": {"status": "UNKNOWN", "from": None, "to": None}, "interpretationNote": "Variant is a separate claim; no location equivalence is asserted."})
        target["claimRefs"] = [*require_array(target, "claimRefs"), "claim:east-sea-wa:yamatai-variant"]
    if pack_id == "northern-steppe":
        xianbei = next(entity for entity in entities if entity.get("canonicalName") == "鮮卑")
        claims.append({"claimId": "claim:northern-steppe:xianbei-three-divisions", "reviewState": "APPROVED", "subjectEntityKey": xianbei["entityKey"], "predicate": "SPATIAL_DIVISIONS", "value": "東部|中部|西部", "evidenceClass": "PRIMARY_ATTESTED", "sourceRef": "hhs-90-xianbei", "sourceProximity": "OFFICIAL_HISTORY", "subjectPeriod": {"status": "UNKNOWN", "from": None, "to": None}, "interpretationNote": "Three divisions are a claim, not fixed entities or cities."})
        xianbei["claimRefs"] = [*require_array(xianbei, "claimRefs"), "claim:northern-steppe:xianbei-three-divisions"]
    itineraries: list[JsonObject] = []
    if pack_id == "east-sea-wa":
        sequence_names = ("對馬國", "一大國", "末盧國", "伊都國", "奴國", "不彌國", "投馬國", "邪馬壹國")
        by_name = {require_text(entity, "canonicalName"): require_text(entity, "entityKey") for entity in entities}
        sequence = [by_name[name] for name in sequence_names]
        itineraries.append({"itineraryId": "wa-eight-stop-relative-itinerary", "sequence": sequence, "legs": [{"ordinal": index + 1, "fromEntityKey": sequence[index], "toEntityKey": sequence[index + 1]} for index in range(len(sequence) - 1)], "traversable": False, "corridorRefs": [], "sourceRefs": ["sgz-30-wa-itinerary"]})
    approved_entities = sum(entity.get("reviewState") == "APPROVED" for entity in entities)
    approved_claims = sum(claim.get("reviewState") == "APPROVED" for claim in claims)
    return {"schemaVersion": 1, "packId": pack_id, "reviewState": "APPROVED_DATA_CONTRACT", "sourceCandidateSetId": "han-external-world-candidates-v1", "sourceCandidateSetSha256": EXTERNAL_CANDIDATE_SHA, "sourceRegistry": registry, "entities": entities, "claims": claims, "relativeItineraries": itineraries, "coverageLedger": [f"legacy-external:{source_id}" for source_id in PACK_LEGACY_IDS[pack_id]], "summary": {"entityCount": len(entities), "approvedEntityCount": approved_entities, "claimCount": len(claims), "approvedClaimCount": approved_claims, "itineraryCount": len(itineraries), "legacyCandidateCount": len(PACK_LEGACY_IDS[pack_id]), "scenarioStateCount": len(entities) * 31}, "runtimeActivation": RUNTIME_BOUNDARY}


def build_documents(context: ExternalWorldContext) -> GeneratedDocuments:
    legacy = _legacy_rows(context)
    packs = {pack_id: _pack(context, pack_id, legacy) for pack_id in PACK_IDS}
    adjudications: list[JsonObject] = []
    for position, row in enumerate(require_array(context.candidates, "candidates")):
        candidate = require_object(row, "external candidate")
        raw = require_object(candidate.get("rawLegacy"), "raw legacy")
        source_id = require_text(raw, "id")
        pack_id = next((value for value, ids in PACK_LEGACY_IDS.items() if source_id in ids), None)
        disposition = "SUPERSEDE" if source_id in SUPERSEDE_IDS else "REJECT" if source_id == "X026" else "APPROVE"
        result_keys: list[str] = []
        if pack_id is not None:
            pack = packs[pack_id]
            for raw_entity in require_array(pack, "entities"):
                entity = require_object(raw_entity, "entity")
                if f"legacy-external:{source_id}" in require_array(entity, "legacyCandidateKeys"):
                    result_keys.append(require_text(entity, "entityKey"))
        raw_fingerprint = hashlib.sha256(serialize(raw).encode()).hexdigest()
        adjudications.append({"candidateKey": require_text(candidate, "candidateKey"), "legacyRowIndex": position, "rawLegacySha256": raw_fingerprint, "disposition": disposition, "packId": pack_id, "resultEntityKeys": result_keys, "supersededByRefs": ["han-route-node-selection-v1"] if disposition == "SUPERSEDE" else [], "reasonCode": "ADMINISTRATIVE_ROUTE_NODE_AUTHORITY" if disposition == "SUPERSEDE" else "FAKE_INDEPENDENT_GUIZI_SUBORDINATE" if disposition == "REJECT" else "PACK_ENTITY_APPROVED"})
    pack_documents = {f"{pack_id}-v1.json": document for pack_id, document in packs.items()}
    pack_rows = [{"packId": pack_id, "path": f"data/curated/han/external-world-packs/{pack_id}-v1.json", "sha256": hashlib.sha256(serialize(packs[pack_id]).encode()).hexdigest(), "legacyCandidateCount": len(PACK_LEGACY_IDS[pack_id])} for pack_id in PACK_IDS]
    ledger: JsonObject = {"schemaVersion": 1, "ledgerId": "han-external-world-legacy-adjudications-v1", "reviewState": "APPROVED_DATA_CONTRACT", "sourceCandidateSetId": "han-external-world-candidates-v1", "sourceCandidateSetSha256": EXTERNAL_CANDIDATE_SHA, "adjudications": adjudications, "summary": {"candidateCount": 65, "supersedeCount": 27, "rejectCount": 1, "packCandidateCount": 37, "approvedResultCount": 37}, "runtimeActivation": RUNTIME_BOUNDARY}
    index_document: JsonObject = {"schemaVersion": 1, "indexId": "han-external-world-pack-index-v1", "reviewState": "APPROVED_DATA_CONTRACT", "sourceCandidateSetId": "han-external-world-candidates-v1", "sourceCandidateSetSha256": EXTERNAL_CANDIDATE_SHA, "sourceRouteContractId": "han-route-network-contract-v1", "sourceRouteContractSha256": ROUTE_CONTRACT_SHA, "packs": pack_rows, "summary": {"packCount": 5, "legacyCandidateCount": 37, "approvedCorridorCount": 0, "entityCount": sum(len(require_array(pack, "entities")) for pack in packs.values()), "claimCount": sum(len(require_array(pack, "claims")) for pack in packs.values())}, "runtimeActivation": RUNTIME_BOUNDARY}
    return {"external-world-pack-index-v1.json": index_document, "external-world-legacy-adjudications-v1.json": ledger, **pack_documents}


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output-dir", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    try:
        documents = build_documents(load_context(ROOT))
        from tools.map.external_world_validation import validate_documents
        validate_documents(documents, load_context(ROOT))
        if args.check:
            for name, document in documents.items():
                path = args.output_dir / "external-world-packs" / name if name.startswith(tuple(PACK_IDS)) else args.output_dir / name
                if not path.is_file() or path.read_text(encoding="utf-8") != serialize(document):
                    raise ContractError(f"generated artifact drift: {path}")
        else:
            write_documents(args.output_dir, documents)
    except ContractError as error:
        print(error, file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
