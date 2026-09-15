#!/usr/bin/env python3
"""han-tiles 에 省을 떼어 받은 수·진·관 거점을 경로 노드 원장에 append-only 로 쓴다.

`tools/map/carve_strategic_site_provinces.py` 가 거점마다 城 점(`ss-<id>`)·관할·省을 세운 뒤에 돈다.
그 단계 원장의 placements 가 입력이고, 근거는 거점 원장(strategic-strongholds-v1 · strategic-passes-v1)의
正史 인용이다. 郡國志 식별자가 없으므로 결속은 REVIEWED_SOURCE_CLAIM 이다(w2 와 같은 규약).

손대는 원장 (기존 행은 그대로):
  - route-node-strategic-site-claims-v1.json  claims (새 파일, 한 번만 쓴다)
  - route-node-key-registry-v1.json           keys += 거점 수 (UUIDv4 한 번만 발급, 다음 미발급 번호부터)
  - route-node-review-policy-v1.json          selectionBatches += w3-strategic-site-route-claim
  - route-node-validation-contract-v1.json    allowedNodeClasses += FERRY_NODE·FORT_NODE·PASS_NODE

실행 순서: carve --prepare --output → 이 도구 → materialize_han_route_node_selection → 이하 재생성 사슬.
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
CARVES = CURATED / "strategic-site-province-carves-v1.json"
STRONGHOLDS = CURATED / "strategic-strongholds-v1.json"
PASSES = CURATED / "strategic-passes-v1.json"
CLAIMS = CURATED / "route-node-strategic-site-claims-v1.json"
REGISTRY = CURATED / "route-node-key-registry-v1.json"
POLICY = CURATED / "route-node-review-policy-v1.json"
CONTRACT = CURATED / "route-node-validation-contract-v1.json"

BATCH_ID = "w3-strategic-site-route-claim"
ISSUANCE_REASON = "STRATEGIC_SITE_ROUTE_CLAIM_V1_APPEND"
CLAIM_PREFIX = "han-strategic-site-route-claim-v1-"
SUBJECT_PREFIX = "strategic-site:"
PLACE_NAMESPACE = "curated:strategic-site-v1:"
NODE_CLASS = {"FERRY": "FERRY_NODE", "FORT": "FORT_NODE", "PASS": "PASS_NODE"}


def _load(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def _dump(path: Path, document: dict) -> None:
    path.write_text(json.dumps(document, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def _sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _rel(path: Path) -> str:
    return path.relative_to(ROOT).as_posix()


def build_claims(carves: dict, tiles: dict) -> list[dict]:
    parents = {row["id"]: row for row in tiles["parentRegions"]}
    source = {"PASS": (PASSES, _sha256(PASSES))}
    source["FERRY"] = source["FORT"] = (STRONGHOLDS, _sha256(STRONGHOLDS))
    stage = carves["geometry"]["stages"][0]
    claims = []
    for placement in sorted(stage["placements"], key=lambda row: row["siteId"]):
        place_id = placement["placeId"]
        parent = parents[placement["commanderyId"]]
        ledger_path, ledger_sha = source[placement["role"]]
        claims.append({
            "sourceClaimId": f"{CLAIM_PREFIX}{placement['siteId']}",
            "claimRole": "ROUTE_NODE",
            "reviewState": "APPROVED",
            "subjectType": "STRATEGIC_SITE",
            "subjectKey": f"{SUBJECT_PREFIX}{place_id}",
            "canonicalName": placement["nameHan"],
            "nodeClass": NODE_CLASS[placement["role"]],
            "seatRole": "NON_SEAT",
            "parentName": parent["nameCh"],
            "parentRef": f"han-tiles-commandery:{parent['id']}",
            "physicalPlaceRef": f"{PLACE_NAMESPACE}{place_id}",
            "tileBinding": {"jurisdictionId": place_id, "commanderyId": parent["id"], "seatPlaceId": place_id},
            "evidence": {"kind": "STRATEGIC_SITE_LEDGER", "datasetPath": _rel(ledger_path),
                         "datasetSha256": ledger_sha, "siteId": placement["siteId"], "role": placement["role"]},
        })
    return claims


def main() -> int:
    if CLAIMS.is_file():
        print(f"{_rel(CLAIMS)} already exists — append-only; refusing to rebuild claim identities", file=sys.stderr)
        return 1
    claims = build_claims(_load(CARVES), _load(TILES))
    _dump(CLAIMS, {
        "schemaVersion": 1,
        "claimSetId": "han-w3-route-node-strategic-site-claims-v1",
        "status": "APPROVED",
        "reviewAuthority": "user-approval-2026-09-15",
        "reviewedAt": "2026-09-15",
        "policy": {
            "purpose": "縣이 아닌 거점(수·진·관)에 REVIEWED_SOURCE_CLAIM 경로 노드를 준다(ADR-LITE-052).",
            "historicalBindingBasis": "REVIEWED_SOURCE_CLAIM",
            "lifecycleClaimed": False,
            "carveLedgerSha256": _sha256(CARVES),
        },
        "claims": claims,
    })
    registry = _load(REGISTRY)
    keys = registry["keys"]
    existing = {row["initialAdministrativeUnitId"] for row in keys}
    used = {row["routeNodeKey"] for row in keys}
    next_id = max(row["numericCityId"] for row in keys if "numericCityId" in row) + 1
    added = 0
    for claim in claims:
        if claim["subjectKey"] in existing:
            continue
        key = str(uuid.uuid4())
        while key in used:
            key = str(uuid.uuid4())
        used.add(key)
        keys.append({"routeNodeKey": key, "initialAdministrativeUnitId": claim["subjectKey"],
                     "issuanceReason": ISSUANCE_REASON, "numericCityId": next_id})
        next_id += 1
        added += 1
    _dump(REGISTRY, registry)
    contract = _load(CONTRACT)
    contract["allowedNodeClasses"] = sorted(set(contract["allowedNodeClasses"]) | set(NODE_CLASS.values()))
    _dump(CONTRACT, contract)
    policy = _load(POLICY)
    batches = policy["selectionBatches"]
    batch = next((row for row in batches if row["batchId"] == BATCH_ID), None)
    if batch is None:
        batch = {"batchId": BATCH_ID}
        batches.append(batch)
    batch.update({
        "criteria": "strategic-site-province-carves-v1 placement with an APPROVED "
                    "route-node-strategic-site-claims-v1 ROUTE_NODE claim",
        "expectedCount": len(claims),
        "reviewState": "APPROVED",
        "selectionRationale": (
            "ADR-LITE-052 비현 거점(關·津·鎭)을 기존 城 등급 체계(수 1·진 2·관 3) 아래 경로 노드로 올린다. "
            "正史 근거는 거점 원장에, 省 기하는 縣 省에서 떼어 낸 분할 원장에 있다(2026-09-15 사용자 결정)."
        ),
        "claimLedger": _rel(CLAIMS),
    })
    expected = policy["expectedSelection"]
    expected["routeNodeCount"] = sum(row["expectedCount"] for row in batches)
    expected["reviewedSourceClaimBindingCount"] = expected.get("reviewedSourceClaimBindingCount", 0) + len(claims)
    policy["inputs"]["strategicSiteRouteClaims"] = {"path": _rel(CLAIMS), "sha256": _sha256(CLAIMS)}
    policy["inputs"]["routeNodeKeyRegistry"] = {"path": _rel(REGISTRY), "sha256": _sha256(REGISTRY)}
    _dump(POLICY, policy)
    print(f"strategic-site claims {len(claims)} · registry keys +{added} · batch {BATCH_ID}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
