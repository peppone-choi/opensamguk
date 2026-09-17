#!/usr/bin/env python3
"""城 없는 郡國 밖 취락 관할(EXTERNAL_SETTLEMENT)을 경로 노드로 올리고, 같은 縣이 두 번 선 城의 번호를 넘겨준다.

사용자 결정(2026-09-17): 「절대 소속 없는 프로빈스가 있어선 안돼」 · 「3번도 찾아서 넣을 수 있으면 넣어」.
han-tiles 의 郡國 밖 취락 관할 37 곳(夫餘·烏桓·南匈奴·高句麗·三韓 소국·倭 소국 …)에는 게임 城이 없어 그 省들이
영원히 주인 없는 땅으로 남았다. 경로 노드 선정 정책(route-node-review-policy-v1 forbiddenSelections)이 2026-08-23
부터 이 치소 점을 막아 두었는데, 이 결정이 그 금지를 푼다(帶方郡 X004 를 w2 가 푼 것과 같은 길).

근거는 치소 점마다 `data/map/external-places.json` 기록이다 — 비정 근거 문장(basis: 三國志 魏書 東夷傳 등)과
Wikidata 좌표(CC0). 郡國志 식별자가 없으므로 결속은 REVIEWED_SOURCE_CLAIM 이다(w2·w3 와 같은 규약).

같은 縣이 두 번 선 城(巴郡 漢昌 579/977, 北地郡 富平 627/989)은 cityless-jurisdiction-fold-decisions-v1 이 뒤에
선 쪽(977·989)의 관할을 접는다. 경로 노드 번호는 1..N 으로 끊김이 없어야 하므로(build_han_world), 비는 두 번호의
UUID 키를 새 취락 claim 두 곳에 **재결속**한다 — 키 원장 정책 `rebindingChangesKey: false` 가 허용한 길이다.
원래 발급 사유·최초 결합(initialAdministrativeUnitId)은 감사값으로 그대로 두고 `rebinding` 을 덧붙인다.

손대는 원장:
  - route-node-external-settlement-claims-v1.json  claims (새 파일, 한 번만 쓴다)
  - route-node-jurisdiction-claims-v1.json         중복 두 claim 을 excluded(DUPLICATE_ROUTE_NODE)로 옮긴다
  - route-node-key-registry-v1.json                두 행 rebinding + 나머지 취락 새 키(다음 미발급 번호부터)
  - route-node-review-policy-v1.json               w2 기대 수 · w5 batch · 금지 목록 해제
  - route-node-validation-contract-v1.json         allowedNodeClasses += SETTLEMENT_NODE · 금지 목록 해제

실행 순서: fold_cityless_jurisdictions --prepare --output → 이 도구 → materialize_han_route_node_selection → 이하 재생성 사슬.
"""
from __future__ import annotations

import hashlib
import json
import sys
import uuid
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CURATED = ROOT / "data" / "curated" / "han"
TILES = ROOT / "data" / "map" / "han-tiles.json"
EXTERNAL_PLACES = ROOT / "data" / "map" / "external-places.json"
FOLD_DECISIONS = CURATED / "cityless-jurisdiction-fold-decisions-v1.json"
CLAIMS = CURATED / "route-node-external-settlement-claims-v1.json"
JURISDICTION_CLAIMS = CURATED / "route-node-jurisdiction-claims-v1.json"
REGISTRY = CURATED / "route-node-key-registry-v1.json"
POLICY = CURATED / "route-node-review-policy-v1.json"
CONTRACT = CURATED / "route-node-validation-contract-v1.json"

BATCH_ID = "w5-external-settlement-route-claim"
ISSUANCE_REASON = "EXTERNAL_SETTLEMENT_ROUTE_CLAIM_V1_APPEND"
CLAIM_PREFIX = "han-external-settlement-route-claim-v1-"
SUBJECT_PREFIX = "han-tiles-external-settlement:"
JURISDICTION_SUBJECT_PREFIX = "han-tiles-jurisdiction:"
NODE_CLASS = "SETTLEMENT_NODE"
REBINDING_REASON = "DUPLICATE_ROUTE_NODE_SLOT_REUSE"
REVIEW_AUTHORITY = "user-directive-2026-09-17"


def _load(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def _dump(path: Path, document: dict) -> None:
    path.write_text(json.dumps(document, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def _sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _rel(path: Path) -> str:
    return path.relative_to(ROOT).as_posix()


def build_claims(tiles: dict, places: dict, route_places: set[str]) -> list[dict]:
    """치소 점이 아직 경로 노드가 아닌 취락 관할만(龜茲屬國 704 처럼 이미 선 곳은 뺀다)."""
    parents = {row["id"]: row for row in tiles["parentRegions"]}
    commanderies = {row["id"]: row for row in tiles["commanderyRecords"]}
    records = {row["id"]: row for row in places["places"]}
    places_sha = _sha256(EXTERNAL_PLACES)
    claims = []
    for jurisdiction in sorted((row for row in tiles["jurisdictionRecords"]
                                if row["kind"] == "EXTERNAL_SETTLEMENT"
                                and f"external:v1:{row['seatPlaceId']}" not in route_places),
                               key=lambda row: row["id"]):
        jid, place_id = jurisdiction["id"], jurisdiction["seatPlaceId"]
        record = records.get(place_id)
        if record is None or not record.get("basis") or not record.get("wikidata"):
            raise ValueError(f"{jid}: external-places.json has no sourced record for seat {place_id}")
        parent = parents[jurisdiction["commanderyId"]]
        seat = commanderies[parent["id"]]["seatJurisdictionId"] == jid
        claims.append({
            "sourceClaimId": f"{CLAIM_PREFIX}{jid}",
            "claimRole": "ROUTE_NODE",
            "reviewState": "APPROVED",
            "subjectType": "EXTERNAL_SETTLEMENT",
            "subjectKey": f"{SUBJECT_PREFIX}{jid}",
            "canonicalName": record["nameFt"],
            "nodeClass": NODE_CLASS,
            "seatRole": "COMMANDERY_SEAT" if seat else "NON_SEAT",
            "parentName": parent["nameCh"],
            "parentRef": f"han-tiles-commandery:{parent['id']}",
            "physicalPlaceRef": f"external:v1:{place_id}",
            "tileBinding": {"jurisdictionId": jid, "commanderyId": parent["id"], "seatPlaceId": place_id},
            "evidence": {"kind": "EXTERNAL_PLACE_RECORD", "datasetPath": _rel(EXTERNAL_PLACES),
                         "datasetSha256": places_sha, "recordId": place_id, "wikidataId": record["wikidata"],
                         "confidence": record["conf"]},
        })
    return claims


def main() -> int:
    if CLAIMS.is_file():
        print(f"{_rel(CLAIMS)} already exists — append-only; refusing to rebuild claim identities", file=sys.stderr)
        return 1
    tiles, fold_decisions = _load(TILES), _load(FOLD_DECISIONS)
    selection = _load(CURATED / "route-node-selection-v1.json")
    claims = build_claims(tiles, _load(EXTERNAL_PLACES), {row["physicalPlaceRef"] for row in selection["routeNodes"]})
    duplicates = [row for row in fold_decisions["folds"] if row["reason"] == "DUPLICATE_ROUTE_NODE"]

    # 1) 중복 城 claim 을 w2 원장에서 excluded 로 옮긴다.
    jurisdiction_claims = _load(JURISDICTION_CLAIMS)
    duplicate_subjects = {JURISDICTION_SUBJECT_PREFIX + row["sourceJurisdictionId"]: row for row in duplicates}
    kept, moved = [], []
    for claim in jurisdiction_claims["claims"]:
        (moved if claim["subjectKey"] in duplicate_subjects else kept).append(claim)
    if len(moved) != len(duplicates):
        raise ValueError("every duplicate fold must name an existing w2 jurisdiction claim")
    jurisdiction_claims["claims"] = kept
    for claim in moved:
        decision = duplicate_subjects[claim["subjectKey"]]
        jurisdiction_claims["excluded"].append({
            "jurisdictionId": decision["sourceJurisdictionId"], "seatPlaceId": claim["tileBinding"]["seatPlaceId"],
            "nameCh": claim["evidence"].get("nameCh", claim["canonicalName"]), "commanderyNameCh": claim["parentName"],
            "reason": "DUPLICATE_ROUTE_NODE", "routeNodeCityId": decision["targetRouteNodeCityId"],
            "detail": decision["basis"], "followUp": "FOLD_PROVINCES_INTO_ROUTE_NODE_JURISDICTION",
            "withdrawnSourceClaimId": claim["sourceClaimId"],
        })
    for row in jurisdiction_claims["excluded"]:
        if row.get("followUp") == "FOLD_PROVINCES_INTO_ROUTE_NODE_JURISDICTION":
            row["followUpResolvedBy"] = "data/curated/han/cityless-jurisdiction-folds-v1.json"
    _dump(JURISDICTION_CLAIMS, jurisdiction_claims)

    # 2) 키 원장: 중복 두 번호를 취락 claim 앞 두 곳에 재결속, 나머지는 새 번호.
    registry = _load(REGISTRY)
    keys = registry["keys"]
    by_unit = {row["initialAdministrativeUnitId"]: row for row in keys}
    slots = sorted((by_unit[subject] for subject in duplicate_subjects), key=lambda row: row["numericCityId"])
    used = {row["routeNodeKey"] for row in keys}
    next_id = max(row["numericCityId"] for row in keys if "numericCityId" in row) + 1
    for index, claim in enumerate(claims):
        if index < len(slots):
            slot = slots[index]
            slot["rebinding"] = {"administrativeUnitId": claim["subjectKey"], "reason": REBINDING_REASON,
                                 "withdrawnAdministrativeUnitId": slot["initialAdministrativeUnitId"],
                                 "decisionRef": _rel(FOLD_DECISIONS)}
            continue
        key = str(uuid.uuid4())
        while key in used:
            key = str(uuid.uuid4())
        used.add(key)
        keys.append({"routeNodeKey": key, "initialAdministrativeUnitId": claim["subjectKey"],
                     "issuanceReason": ISSUANCE_REASON, "numericCityId": next_id})
        next_id += 1
    _dump(REGISTRY, registry)

    _dump(CLAIMS, {
        "schemaVersion": 1,
        "claimSetId": "han-w5-route-node-external-settlement-claims-v1",
        "status": "APPROVED",
        "reviewAuthority": REVIEW_AUTHORITY,
        "reviewedAt": "2026-09-17",
        "policy": {
            "purpose": "城 없는 郡國 밖 취락 관할에 REVIEWED_SOURCE_CLAIM 경로 노드를 준다 — 소속 없는 省 0.",
            "historicalBindingBasis": "REVIEWED_SOURCE_CLAIM",
            "lifecycleClaimed": False,
            "reusedDuplicateSlots": [row["numericCityId"] for row in slots],
        },
        "claims": claims,
    })

    # 3) 정책·계약.
    lifted_places = {claim["physicalPlaceRef"] for claim in claims}
    lifted_names = {claim["canonicalName"] for claim in claims}

    def lift(forbidden: dict) -> None:
        forbidden["physicalPlaceIds"] = [value for value in forbidden["physicalPlaceIds"] if value not in lifted_places]
        forbidden["canonicalNames"] = [value for value in forbidden["canonicalNames"] if value not in lifted_names]

    contract = _load(CONTRACT)
    contract["allowedNodeClasses"] = sorted(set(contract["allowedNodeClasses"]) | {NODE_CLASS})
    lift(contract["expectedForbiddenSelections"])
    _dump(CONTRACT, contract)
    policy = _load(POLICY)
    batches = policy["selectionBatches"]
    w2 = next(row for row in batches if row["batchId"] == "w2-cityless-jurisdiction-route-claim")
    w2["expectedCount"] = len(kept)
    batch = next((row for row in batches if row["batchId"] == BATCH_ID), None)
    if batch is None:
        batch = {"batchId": BATCH_ID}
        batches.append(batch)
    batch.update({
        "criteria": "han-tiles EXTERNAL_SETTLEMENT jurisdiction without a route node, bound by an APPROVED "
                    "route-node-external-settlement-claims-v1 ROUTE_NODE claim",
        "expectedCount": len(claims),
        "reviewState": "APPROVED",
        "selectionRationale": (
            "郡國 밖 취락 관할에 城이 없어 그 省이 영원히 주인 없는 땅으로 남던 것을 사용자 결정(2026-09-17, 「절대 소속 "
            "없는 프로빈스가 있어선 안돼」)에 따라 경로 노드로 올린다. 근거는 external-places.json 의 비정 문장과 Wikidata "
            "좌표다. 같은 縣이 두 번 선 城 두 곳의 번호는 키 재결속으로 이 batch 가 이어받는다."
        ),
        "claimLedger": _rel(CLAIMS),
    })
    expected = policy["expectedSelection"]
    expected["routeNodeCount"] = sum(row["expectedCount"] for row in batches)
    expected["reviewedSourceClaimBindingCount"] = expected["reviewedSourceClaimBindingCount"] - len(moved) + len(claims)
    lift(policy["forbiddenSelections"])
    policy["inputs"]["jurisdictionRouteClaims"] = {"path": _rel(JURISDICTION_CLAIMS), "sha256": _sha256(JURISDICTION_CLAIMS)}
    policy["inputs"]["externalSettlementRouteClaims"] = {"path": _rel(CLAIMS), "sha256": _sha256(CLAIMS)}
    policy["inputs"]["routeNodeKeyRegistry"] = {"path": _rel(REGISTRY), "sha256": _sha256(REGISTRY)}
    _dump(POLICY, policy)
    print(f"external-settlement claims {len(claims)} · reused slots {[row['numericCityId'] for row in slots]} · "
          f"new keys {len(claims) - len(slots)} · route nodes {expected['routeNodeCount']}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
