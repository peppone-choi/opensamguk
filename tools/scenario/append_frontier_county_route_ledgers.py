#!/usr/bin/env python3
"""변경 縣 51 곳을 W0 경로 노드 원장에 append-only 로 등록한다.

`tools/map/materialize_frontier_counties.py` 가 han-tiles 에 세운 물리 지점
(`curated:frontier-county-v1:fc-…`)을 route-node 선정 사슬이 알아보게 하는 원장 갱신이다.
Licheng(781) 선례와 같은 append-only 규약을 따르되, 이 縣들은 CHGIS 점이 없는
`NO_COORDINATE_CANDIDATE` 단위라 8 곳의 변경 郡治(X000…X007)가 쓴 LOCATION_ONLY claim 경로를 쓴다.

손대는 원장 (모두 기존 행은 그대로, 없는 행만 덧붙인다):
  - route-node-external-place-authority-v1.json  records += 51 (wikidataId 는 null — 좌표 출처가
    Wikidata 가 아니라 나무위키 수확본이다. 좌표 자체는 이 원장에 적지 않는다)
  - route-node-source-claims-v1.json             claims  += 51 LOCATION_ONLY (郡國志 縣 줄 인용)
  - route-node-source-witness-v1.json            records += 51 (data/corpus 가 없는 환경의 증인 행)
  - route-node-key-registry-v1.json              keys    += 51 (UUIDv4 는 **한 번만** 발급한다 —
    이미 있는 identity 는 절대 다시 만들지 않는다) · numericCityId 는 다음 미발급 번호부터
  - route-node-review-policy-v1.json             selectionBatches += w1-frontier-county-location,
    expectedSelection 갱신, inputs 해시 재고정

실행 순서: han-tiles materialize → 후보 manifest 재생성(build_han_route_node_candidates) →
이 도구 → materialize_han_route_node_selection → validate_han_route_node_selection(핀 갱신).
"""
from __future__ import annotations

import argparse
import hashlib
import json
import sys
import uuid
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CURATED = ROOT / "data" / "curated" / "han"
LEDGER = CURATED / "frontier-counties-v1.json"
PLACEMENTS = CURATED / "frontier-county-placements-v1.json"
AUTHORITY = CURATED / "route-node-external-place-authority-v1.json"
CLAIMS = CURATED / "route-node-source-claims-v1.json"
WITNESS = CURATED / "route-node-source-witness-v1.json"
REGISTRY = CURATED / "route-node-key-registry-v1.json"
POLICY = CURATED / "route-node-review-policy-v1.json"
CANDIDATES = CURATED / "route-node-selection-candidates-v1.json"
CATALOG = CURATED / "administrative-units.json"
OVERLAY = CURATED / "administrative-place-bindings-v1.json"
ADJUDICATIONS = CURATED / "route-node-location-adjudications-v1.json"

BATCH_ID = "w1-frontier-county-location"
ISSUANCE_REASON = "FRONTIER_COUNTY_V1_APPEND"
CLAIM_PREFIX = "han-frontier-county-claim-v1-"
UNCERTAINTY_RADIUS_KM = 35
CORPUS_PATH = "data/corpus/hhs-113.txt"


def _load(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def _dump(path: Path, document: dict) -> None:
    path.write_text(json.dumps(document, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def _sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _rel(path: Path) -> str:
    return path.relative_to(ROOT).as_posix()


def county_line_evidence(county: dict) -> dict:
    evidence = [
        row for row in county["primaryEvidence"]
        if "COUNTY_EXISTS_IN_COMMANDERY" in row.get("supports", [])
        and row.get("sourceRepository") == "shiliao" and row.get("volume") == 113
    ]
    if len(evidence) != 1:
        raise ValueError(f"frontier county needs exactly one 郡國志 county-line evidence: {county['id']}")
    return evidence[0]


def build_rows(ledger: dict, placements: dict) -> list[dict]:
    placement_by_id = {row["id"]: row for row in placements["placements"]}
    rows = []
    for county in ledger["counties"]:
        placement = placement_by_id[county["id"]]
        evidence = county_line_evidence(county)
        place_id = placement["physicalPlaceId"]
        claim_id = f"{CLAIM_PREFIX}{place_id}"
        source_record = {
            "corpusPath": CORPUS_PATH,
            "lineEnd": evidence["line"],
            "lineStart": evidence["line"],
            "snapshotSha256": evidence["sha256"],
            "sourceBook": "後漢書",
            "verbatim": evidence["quote"],
            "volume": 113,
        }
        rows.append({
            "unitId": county["id"],
            "placeId": place_id,
            "placeRef": placement["physicalPlaceRef"],
            "claimId": claim_id,
            "canonicalName": county["nameHan"],
            "commanderyHan": county["commanderyHan"],
            "sourceRecord": source_record,
        })
    return rows


def append_authority(document: dict, rows: list[dict]) -> int:
    existing = {row["recordId"] for row in document["records"]}
    added = 0
    for row in rows:
        if row["placeId"] in existing:
            continue
        document["records"].append({
            "canonicalName": row["canonicalName"],
            "physicalPlaceId": row["placeRef"],
            "recordId": row["placeId"],
            "subjectKey": row["unitId"],
            "wikidataId": None,
        })
        added += 1
    if added:
        document["authority"] = (
            "OPENSAM-225 reviewed external place identities · frontier-counties-v1 reviewed 郡國志 county identities"
        )
    return added


def append_claims(document: dict, rows: list[dict], authority_sha: str) -> int:
    existing = {row["sourceClaimId"] for row in document["claims"]}
    added = 0
    for row in rows:
        if row["claimId"] in existing:
            continue
        document["claims"].append({
            "aliases": [],
            "canonicalName": row["canonicalName"],
            "claimRole": "LOCATION_ONLY",
            "conflictDisposition": {
                "rationaleCode": "PLACE_IDENTITY_ONLY",
                "competingRefs": [],
                "rationale": (
                    f"郡國志 {row['commanderyHan']} 屬縣 {row['canonicalName']}을 frontier-counties-v1 검토 원장의 "
                    "좌표(나무위키 「삼국지/지명」 수확본, APPROXIMATE)로 비정한 외부 좌표 보조 record다."
                ),
                "status": "NONE",
            },
            "locationResolution": {
                "coordinateDatasetRef": {
                    "datasetPath": _rel(AUTHORITY),
                    "datasetSha256": authority_sha,
                    "recordId": row["placeId"],
                    "wikidataId": None,
                },
                "kind": "POINT_REF",
                "physicalPlaceId": row["placeRef"],
                "uncertaintyRadiusKm": UNCERTAINTY_RADIUS_KM,
            },
            "reviewEvidenceRefs": [
                _rel(OVERLAY), _rel(AUTHORITY), _rel(WITNESS),
            ],
            "reviewState": "APPROVED",
            "selectionReviewCoverage": "W0_ROUTE_NODE_PLACE_IDENTITY_ONLY",
            "sourceClaimId": row["claimId"],
            "sourceRecords": [dict(row["sourceRecord"])],
            "subjectKey": row["unitId"],
            "subjectType": "ADMINISTRATIVE_PLACE",
        })
        added += 1
    if added:
        document["policy"]["purpose"] = (
            "CHGIS V6 county coverage 밖의 HHS administrative unit 에만 유한 W0 물리 anchor를 제공한다 — "
            "변경 郡治 8건(OPENSAM-225)과 frontier-counties-v1 의 변경 屬縣 51건."
        )
    return added


def append_witness(document: dict, rows: list[dict]) -> int:
    existing = {row["sourceClaimId"] for row in document["records"]}
    added = 0
    for row in rows:
        if row["claimId"] in existing:
            continue
        document["records"].append({"sourceClaimId": row["claimId"], **row["sourceRecord"]})
        added += 1
    return added


def append_registry(document: dict, rows: list[dict]) -> int:
    keys = document["keys"]
    existing = {row["initialAdministrativeUnitId"] for row in keys}
    used_keys = {row["routeNodeKey"] for row in keys}
    next_id = max(
        [row["numericCityId"] for row in keys if "numericCityId" in row] + [len([r for r in keys if "numericCityId" not in r])]
    ) + 1
    added = 0
    for row in rows:
        if row["unitId"] in existing:
            continue
        key = str(uuid.uuid4())
        while key in used_keys:
            key = str(uuid.uuid4())
        used_keys.add(key)
        keys.append({
            "routeNodeKey": key,
            "initialAdministrativeUnitId": row["unitId"],
            "issuanceReason": ISSUANCE_REASON,
            "numericCityId": next_id,
        })
        next_id += 1
        added += 1
    return added


def update_policy(document: dict, rows: list[dict]) -> None:
    batches = document["selectionBatches"]
    if not any(row["batchId"] == BATCH_ID for row in batches):
        batches.append({
            "batchId": BATCH_ID,
            "criteria": (
                "HHS NO_COORDINATE_CANDIDATE frontier county identity (frontier-counties-v1) "
                "with APPROVED W0_ROUTE_NODE_PLACE_IDENTITY_ONLY point claim"
            ),
            "expectedCount": len(rows),
            "reviewState": "APPROVED",
            "selectionRationale": (
                "續漢書 郡國志가 樂浪·遼東·玄菟·遼東屬國·交趾·九真·日南에 적은 屬縣 가운데 CHGIS county 경계 밖이라 "
                "물리점이 없던 51 곳을 frontier-counties-v1 검토 원장의 좌표 claim으로 결합한다. 역사 binding은 모두 HHS identity다."
            ),
        })
    expected = document["expectedSelection"]
    route_count = sum(row["expectedCount"] for row in batches)
    expected["routeNodeCount"] = route_count
    expected["hhsAdministrativeBindingCount"] = route_count
    expected["frontierCountyClaimCount"] = len(rows)
    inputs = document["inputs"]
    inputs["administrativeCatalogSha256"] = _sha256(CATALOG)
    inputs["coordinateOverlaySha256"] = _sha256(OVERLAY)
    inputs["candidateManifest"] = {"path": _rel(CANDIDATES), "sha256": _sha256(CANDIDATES)}
    inputs["locationAdjudications"] = {"path": _rel(ADJUDICATIONS), "sha256": _sha256(ADJUDICATIONS)}
    inputs["locationClaims"] = {"path": _rel(CLAIMS), "sha256": _sha256(CLAIMS)}
    inputs["routeNodeKeyRegistry"] = {"path": _rel(REGISTRY), "sha256": _sha256(REGISTRY)}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.parse_args()
    rows = build_rows(_load(LEDGER), _load(PLACEMENTS))
    authority = _load(AUTHORITY)
    added_authority = append_authority(authority, rows)
    _dump(AUTHORITY, authority)
    authority_sha = _sha256(AUTHORITY)
    claims = _load(CLAIMS)
    for claim in claims["claims"]:
        claim["locationResolution"]["coordinateDatasetRef"]["datasetSha256"] = authority_sha
    added_claims = append_claims(claims, rows, authority_sha)
    _dump(CLAIMS, claims)
    witness = _load(WITNESS)
    added_witness = append_witness(witness, rows)
    _dump(WITNESS, witness)
    registry = _load(REGISTRY)
    added_keys = append_registry(registry, rows)
    _dump(REGISTRY, registry)
    policy = _load(POLICY)
    update_policy(policy, rows)
    _dump(POLICY, policy)
    print(
        f"frontier counties {len(rows)}: authority +{added_authority} · claims +{added_claims} · "
        f"witness +{added_witness} · registry keys +{added_keys} · policy batch {BATCH_ID}"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
