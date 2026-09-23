#!/usr/bin/env python3
"""결손 縣 56 곳을 W0 경로 노드 원장에 append-only 로 등록한다.

`tools/map/carve_strategic_site_provinces.py` 가 han-tiles 에 국소 carve 로 세운 물리 지점
(`curated:gap-county-v1:gc-…`)을 route-node 선정 사슬이 알아보게 하는 원장 갱신이다.
변경 縣 51 곳의 `append_frontier_county_route_ledgers.py` 와 **같은 규약**이고 공용 헬퍼를 그대로
가져다 쓴다 — 다르게 하는 것은 원장 출처·배치 id·claim 접두사·발급 사유뿐이다.

왜 필요한가: 귀속 규칙이 「모든 省은 제 관할 治所가 게임 城이어야 한다」(사용자 결정 2026-09-17)다.
등록하지 않으면 `build_province_city_attribution` 이 60 省을 SAME_COMMANDERY_SEAT 폴백으로 떨어뜨리고
「소속 없는 省 60곳」 으로 멈춘다.

손대는 원장 (기존 행은 그대로, 없는 행만 덧붙인다):
  - route-node-external-place-authority-v1.json  records += 60 (wikidataId null — 좌표 출처가
    Wikidata 가 아니라 나무위키 수확본/TGAZ 다)
  - route-node-source-claims-v1.json             claims  += 60 LOCATION_ONLY (郡國志 縣 줄 인용)
  - route-node-source-witness-v1.json            records += 60
  - route-node-key-registry-v1.json              keys    += 60 (UUIDv4 는 **한 번만** 발급한다)
  - route-node-review-policy-v1.json             selectionBatches += w1-gap-county-location

실행 순서: 거점 분할(--prepare) → 후보 manifest 재생성 → 이 도구 →
materialize_han_route_node_selection → validate_han_route_node_selection.

    /usr/local/bin/python3 tools/scenario/append_gap_county_route_ledgers.py
"""
from __future__ import annotations

import argparse
import json
import sys
import uuid
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from tools.scenario.append_frontier_county_route_ledgers import (  # noqa: E402
    AUTHORITY, CANDIDATES, CATALOG, CLAIMS, OVERLAY, POLICY, REGISTRY, WITNESS,
    ADJUDICATIONS, UNCERTAINTY_RADIUS_KM, _dump, _load, _rel, _sha256,
)

CURATED = ROOT / "data" / "curated" / "han"
GAP_LEDGER = CURATED / "gap-counties-v1.json"
CARVES = CURATED / "strategic-site-province-carves-v1.json"

BATCH_ID = "w1-gap-county-location"
ISSUANCE_REASON = "GAP_COUNTY_V1_APPEND"
CLAIM_PREFIX = "han-gap-county-claim-v1-"
PLACE_NAMESPACE = "curated:gap-county-v1"


def build_rows(ledger: dict, carves: dict) -> list[dict]:
    stage = carves["geometry"]["stages"][0]
    placement_by_unit = {row["countyId"]: row for row in stage["gapCountyPlacements"]}
    rows = []
    for county in ledger["counties"]:
        placement = placement_by_unit.get(county["id"])
        if placement is None:
            raise ValueError(f"gap county has no carve placement: {county['id']}")
        evidence = [row for row in county["primaryEvidence"]
                    if "COUNTY_EXISTS_IN_COMMANDERY" in row.get("supports", [])]
        if len(evidence) != 1:
            raise ValueError(f"gap county needs exactly one 郡國志 county-line evidence: {county['id']}")
        evidence = evidence[0]
        place_id = placement["placeId"]
        rows.append({
            "unitId": county["id"],
            "placeId": place_id,
            "placeRef": f"{PLACE_NAMESPACE}:{place_id}",
            "claimId": f"{CLAIM_PREFIX}{place_id}",
            "canonicalName": county["nameHan"],
            "commanderyHan": county["commanderyHan"],
            "worldCommanderyHan": placement["worldCommanderyHan"],
            "coordinateBasis": county["coordinateBasis"],
            "sourceRecord": {
                "corpusPath": f"data/corpus/hhs-{evidence['volume']}.txt",
                "lineEnd": evidence["line"],
                "lineStart": evidence["line"],
                "snapshotSha256": evidence["sha256"],
                "sourceBook": "後漢書",
                "verbatim": evidence["quote"],
                "volume": evidence["volume"],
            },
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
    return added


def append_claims(document: dict, rows: list[dict], authority_sha: str) -> int:
    existing = {row["sourceClaimId"] for row in document["claims"]}
    added = 0
    for row in rows:
        if row["claimId"] in existing:
            continue
        # rationale 은 **place identity 만** 말해야 한다 — 검증기의 FORBIDDEN_IDENTITY_LIFECYCLE 가
        # 연도(\d{3,4}년)·존속·시점 같은 생애 주장을 막는다. 사료 판정은 심사 원장에만 산다.
        reassigned = ("" if row["worldCommanderyHan"] == row["commanderyHan"]
                      else f" 투영 칸의 세계 소속 郡은 {row['worldCommanderyHan']} 이다.")
        document["claims"].append({
            "aliases": [],
            "canonicalName": row["canonicalName"],
            "claimRole": "LOCATION_ONLY",
            "conflictDisposition": {
                "rationaleCode": "PLACE_IDENTITY_ONLY",
                "competingRefs": [],
                "rationale": (
                    f"郡國志 {row['commanderyHan']} 屬縣 {row['canonicalName']}을 gap-counties-v1 검토 원장의 "
                    f"좌표({row['coordinateBasis']}, APPROXIMATE)로 비정한 외부 좌표 보조 record다. "
                    "좌표 출처와 同名異地 검사는 gap-placement-readiness-v1 에 있다." + reassigned
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
            "reviewEvidenceRefs": [_rel(OVERLAY), _rel(AUTHORITY), _rel(WITNESS)],
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
            "변경 郡治 8건(OPENSAM-225)과 frontier-counties-v1 의 변경 屬縣 51건, "
            "gap-counties-v1 의 결손 屬縣 60건."
        )
    return added


def refresh_authority_hashes(document: dict, authority_sha: str) -> int:
    """모든 LOCATION_ONLY claim 의 `coordinateDatasetRef.datasetSha256` 를 현재 authority 해시로 맞춘다.

    materialize 는 claim 마다 그 해시가 **현재** 파일 해시와 같은지 본다
    (`{claim} external authority hash does not match`). authority 원장에 행을 덧붙이면 해시가 바뀌므로
    기존 claim 의 것도 같이 갱신해야 한다 — 파생 핀이라 내용의 append-only 규율을 깨지 않는다.
    """
    changed = 0
    for claim in document["claims"]:
        ref = claim.get("locationResolution", {}).get("coordinateDatasetRef")
        if not isinstance(ref, dict) or ref.get("datasetPath") != _rel(AUTHORITY):
            continue
        if ref.get("datasetSha256") != authority_sha:
            ref["datasetSha256"] = authority_sha
            changed += 1
    return changed


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
    # numericCityId 는 **정본 순서**에서 아직 안 쓴 번호를 차례로 준다 — max+1 은 틀린다.
    # 계약: `[i for i in active_numeric_ids(LEGACY + 덧붙인 수) if i > LEGACY]` 와 정확히 같아야 한다
    # (han_route_node_selection: 「append-only numeric IDs must be next never-issued sequence」).
    from tools.scenario.han_active_city_ids import active_numeric_ids
    from tools.scenario.han_route_node_selection import APPEND_ISSUANCE_REASONS, LEGACY_SELECTION_COUNT
    appended = [row for row in keys
                if row.get("issuanceReason") in APPEND_ISSUANCE_REASONS and "numericCityId" in row]
    pending = [row for row in rows if row["unitId"] not in existing]
    sequence = [i for i in active_numeric_ids(LEGACY_SELECTION_COUNT + len(appended) + len(pending))
                if i > LEGACY_SELECTION_COUNT]
    taken = {row["numericCityId"] for row in appended}
    free = iter([i for i in sequence if i not in taken])
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
            "numericCityId": next(free),
        })
        added += 1
    return added


def update_policy(document: dict, rows: list[dict]) -> None:
    batches = document["selectionBatches"]
    if not any(row["batchId"] == BATCH_ID for row in batches):
        batches.append({
            "batchId": BATCH_ID,
            "criteria": ("HHS gap county identity (gap-counties-v1) with APPROVED "
                         "W0_ROUTE_NODE_PLACE_IDENTITY_ONLY point claim"),
            "expectedCount": len(rows),
            "reviewState": "APPROVED",
            "selectionRationale": (
                "續漢書 郡國志가 적었는데 지도에 없던 縣 가운데 220 년 존속이 사료로 확정되고 좌표까지 확보된 "
                "60 곳이다(확정 실결손 110 중 60). 존속 판정은 晉書 地理志·讀史方輿紀要·宋書 州郡志·魏書 地形志 "
                "네 축의 여섯 원장에, 좌표 확보와 同名異地 기각은 gap-placement-readiness-v1 에 있다. "
                "역사 binding 은 모두 HHS identity 다."
            ),
        })
    # expectedSelection 의 정본은 코드의 EXPECTED_SELECTION 이다 — 배치 합으로 직접 계산하면
    # HHS 결합 수(909)와 총 노드 수(1224)를 구분하지 못해 「policy count drift」 로 걸린다.
    from tools.scenario.han_route_node_selection import EXPECTED_SELECTION
    document["expectedSelection"] = dict(EXPECTED_SELECTION)
    inputs = document["inputs"]
    inputs["administrativeCatalogSha256"] = _sha256(CATALOG)
    inputs["coordinateOverlaySha256"] = _sha256(OVERLAY)
    inputs["candidateManifest"] = {"path": _rel(CANDIDATES), "sha256": _sha256(CANDIDATES)}
    inputs["locationAdjudications"] = {"path": _rel(ADJUDICATIONS), "sha256": _sha256(ADJUDICATIONS)}
    inputs["locationClaims"] = {"path": _rel(CLAIMS), "sha256": _sha256(CLAIMS)}
    inputs["routeNodeKeyRegistry"] = {"path": _rel(REGISTRY), "sha256": _sha256(REGISTRY)}


def main() -> int:
    argparse.ArgumentParser(description=__doc__,
                            formatter_class=argparse.RawDescriptionHelpFormatter).parse_args()
    rows = build_rows(_load(GAP_LEDGER), _load(CARVES))
    authority = _load(AUTHORITY)
    added_authority = append_authority(authority, rows)
    _dump(AUTHORITY, authority)
    claims = _load(CLAIMS)
    authority_sha = _sha256(AUTHORITY)
    added_claims = append_claims(claims, rows, authority_sha)
    refreshed = refresh_authority_hashes(claims, authority_sha)
    _dump(CLAIMS, claims)
    witness = _load(WITNESS)
    added_witness = append_witness(witness, rows)
    _dump(WITNESS, witness)
    registry = _load(REGISTRY)
    added_registry = append_registry(registry, rows)
    _dump(REGISTRY, registry)
    policy = _load(POLICY)
    update_policy(policy, rows)
    _dump(POLICY, policy)
    print(f"gap counties {len(rows)} · authority +{added_authority} · claims +{added_claims} · "
          f"authority 해시 갱신 {refreshed} · witness +{added_witness} · registry +{added_registry} · batch {BATCH_ID}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
