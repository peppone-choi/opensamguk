#!/usr/bin/env python3
"""城 하나 없던 郡 3곳의 治所를 W0 경로 노드 원장에 append-only 로 등록한다.

朔方郡·西河郡·定襄郡은 郡國志에 屬縣이 적혀 있는데도 CHGIS v6 county 층에 점이 없어
(`NO_COORDINATE_CANDIDATE`) 城을 하나도 못 받았다. 그래서 그 땅 29 省이 제 郡 안에 治所가
없고, 省→城 귀속이 郡 경계를 넘는 폴백(T5 ADJACENT_COMMANDERY_NEAREST)으로만 이어졌다.
세 곳 모두 han-tiles 에 이미 물리점(external:v1:X011·X023·X024)과 治所 관할
(JURISDICTION-PARENT-0086/0081/0084-SEAT, 임융현·이석현·선무현)이 서 있다 — 없던 것은
그 점을 경로 노드로 인정하는 **원장 행**뿐이다.

변경 郡治 8곳(X000…X007)이 쓴 LOCATION_ONLY claim 경로를 그대로 쓴다. 같은
`external:v1:` 이름공간이라 batch 도 같은 w0c-hhs-external-location 이고, 그 수가 8 → 11 이 된다.

**넣지 않는 것** (여기 적어 둔다 — 나중에 「왜 빠졌나」를 다시 캐지 않도록):
  - 張掖屬國:001 候官 @X027 — 검토 원장이 `conf: DISPUTED` 와 「治所 이름이 안 남아 張掖
    일대로만 잡는다 — 점이 아니라 영역」이라고 적어 두었다. 점이 아닌 것을 점으로 주장하지 않는다.
  - 清河國:001 甘陵 @210369 — han-tiles 의 그 점은 CHGIS **pref** 층 id 다. county 층 ref 만
    쓰는 이 사슬에서 그 이름공간이 유효한지 확인되지 않았다(UNKNOWN).
  - 나머지 46 곳 — 31 곳은 route-node-validation-contract-v1 이 선정을 금지한 외부 세력이고,
    15 곳은 郡國志(140년 단면) 뒤에 생긴 郡이라 결합할 HHS identity 자체가 없다.

손대는 원장 (기존 행은 그대로, 없는 행만 덧붙인다):
  - route-node-external-place-authority-v1.json  records += 3
  - route-node-source-claims-v1.json             claims  += 3 LOCATION_ONLY
  - route-node-source-witness-v1.json            records += 3
  - route-node-key-registry-v1.json              keys    += 3 (UUIDv4 는 한 번만 발급 ·
    numericCityId 는 다음 미발급 번호 833–835)
  - route-node-review-policy-v1.json             batch w0c-hhs-external-location 8 → 11,
    expectedSelection 갱신, inputs 해시 재고정

실행 순서: 이 도구 → materialize_han_route_node_selection → validate_han_route_node_selection →
build_han_world --target han-world-v3 → 이하 재생성 사슬.
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
AUTHORITY = CURATED / "route-node-external-place-authority-v1.json"
CLAIMS = CURATED / "route-node-source-claims-v1.json"
WITNESS = CURATED / "route-node-source-witness-v1.json"
REGISTRY = CURATED / "route-node-key-registry-v1.json"
POLICY = CURATED / "route-node-review-policy-v1.json"
CANDIDATES = CURATED / "route-node-selection-candidates-v1.json"
CATALOG = CURATED / "administrative-units.json"
OVERLAY = CURATED / "administrative-place-bindings-v1.json"
ADJUDICATIONS = CURATED / "route-node-location-adjudications-v1.json"
EXTERNAL_CANDIDATES = CURATED / "external-world-candidates-v1.json"
CORPUS = ROOT / "data" / "corpus" / "hhs-113.txt"

BATCH_ID = "w0c-hhs-external-location"
ISSUANCE_REASON = "CITYLESS_COMMANDERY_SEAT_V1_APPEND"
UNCERTAINTY_RADIUS_KM = 35

#: 승격하는 세 곳. (외부 점 id, HHS unit id) — 이름·좌표·wikidata 는 검토 원장에서 읽는다.
SEATS = (
    ("X011", "hhs:113:朔方郡:001"),
    ("X023", "hhs:113:西河郡:001"),
    ("X024", "hhs:113:定襄郡:001"),
)


def _load(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def _dump(path: Path, document: dict) -> None:
    path.write_text(json.dumps(document, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def _sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _rel(path: Path) -> str:
    return path.relative_to(ROOT).as_posix()


def build_rows(catalog: dict, external: dict) -> list[dict]:
    """원장 세 벌이 같이 쓸 행을 만든다. 값은 전부 기존 원장에서 읽는다 — 손으로 적지 않는다."""
    units = {
        f"hhs:{unit['sourceVolume']}:{unit['canonicalGroup']}:{unit['ordinal']:03d}": unit
        for group in catalog["groups"] for unit in group["units"]
    }
    legacy = {row["rawLegacy"]["id"]: row["rawLegacy"] for row in external["candidates"]}
    lines = CORPUS.read_text(encoding="utf-8").split("\n")
    corpus_sha = _sha256(CORPUS)
    rows = []
    for record_id, unit_id in SEATS:
        unit = units[unit_id]
        raw = legacy[record_id]
        if raw["conf"] != "IDENTIFIED":
            raise ValueError(f"검토 원장이 비정을 확정하지 않았다: {record_id} conf={raw['conf']}")
        citation = unit["sourceCitation"]
        if citation["snapshotSha256"] != corpus_sha:
            raise ValueError(f"코퍼스 스냅샷이 목록과 어긋난다: {unit_id}")
        line = citation["line"]
        rows.append({
            "unitId": unit_id,
            "recordId": record_id,
            "placeRef": f"external:v1:{record_id}",
            "claimId": f"han-location-claim-v1-{record_id.lower()}",
            "canonicalName": unit["sourceName"],
            "commanderyHan": unit["canonicalGroup"],
            "wikidataId": raw["wikidata"] or None,
            # 검토 원장의 현대 지명. `basis` 서술을 그대로 옮기지 않는다 — 그 문장에는 「이치」 같은
            # 연혁 서술이 섞여 있고, 신원 전용 claim 의 rationale 은 연혁을 주장하면 안 된다
            # (validate_han_route_node_selection.FORBIDDEN_IDENTITY_LIFECYCLE).
            "presentLocus": raw["presLoc"],
            "sourceRecord": {
                "corpusPath": _rel(CORPUS),
                "lineEnd": line,
                "lineStart": line,
                "snapshotSha256": corpus_sha,
                "sourceBook": "後漢書",
                "verbatim": lines[line - 1].strip(),
                "volume": 113,
            },
        })
    return rows


def append_authority(document: dict, rows: list[dict]) -> int:
    existing = {row["recordId"] for row in document["records"]}
    added = 0
    for row in rows:
        if row["recordId"] in existing:
            continue
        document["records"].append({
            "canonicalName": row["canonicalName"],
            "physicalPlaceId": row["placeRef"],
            "recordId": row["recordId"],
            "subjectKey": row["unitId"],
            "wikidataId": row["wikidataId"],
        })
        added += 1
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
                    f"{row['commanderyHan']} 치소 {row['canonicalName']}을 "
                    f"{row['presentLocus']} 권역에 비정한 외부 좌표 보조 record다."
                ),
                "status": "NONE",
            },
            "locationResolution": {
                "coordinateDatasetRef": {
                    "datasetPath": _rel(AUTHORITY),
                    "datasetSha256": authority_sha,
                    "recordId": row["recordId"],
                    "wikidataId": row["wikidataId"],
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
    numeric = [row["numericCityId"] for row in keys if "numericCityId" in row]
    next_id = max(numeric + [len([r for r in keys if "numericCityId" not in r])]) + 1
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
    batch = next(row for row in document["selectionBatches"] if row["batchId"] == BATCH_ID)
    if batch["expectedCount"] < 8 + len(rows):
        batch["expectedCount"] = 8 + len(rows)
        batch["selectionRationale"] = (
            "續漢書 郡國志가 적은 郡治 가운데 CHGIS county 층에 점이 없는 곳을 검토된 외부 좌표 "
            "record로 결합한다. 邊郡 8곳(交趾·九真·日南·樂浪·遼東·玄菟·遼東屬國·上郡 龜茲)에 "
            "더해, 城을 하나도 못 받아 제 郡 안에 治所가 없던 朔方·西河·定襄 3곳을 같은 규약으로 얹는다."
        )
    expected = document["expectedSelection"]
    route_count = sum(row["expectedCount"] for row in document["selectionBatches"])
    expected["routeNodeCount"] = route_count
    expected["hhsAdministrativeBindingCount"] = route_count
    expected["externalLocationClaimCount"] = batch["expectedCount"]
    inputs = document["inputs"]
    inputs["administrativeCatalogSha256"] = _sha256(CATALOG)
    inputs["coordinateOverlaySha256"] = _sha256(OVERLAY)
    inputs["candidateManifest"] = {"path": _rel(CANDIDATES), "sha256": _sha256(CANDIDATES)}
    inputs["locationAdjudications"] = {"path": _rel(ADJUDICATIONS), "sha256": _sha256(ADJUDICATIONS)}
    inputs["locationClaims"] = {"path": _rel(CLAIMS), "sha256": _sha256(CLAIMS)}
    inputs["routeNodeKeyRegistry"] = {"path": _rel(REGISTRY), "sha256": _sha256(REGISTRY)}


def main() -> int:
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    parser.parse_args()
    rows = build_rows(_load(CATALOG), _load(EXTERNAL_CANDIDATES))
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
        f"cityless commandery seats {len(rows)}: authority +{added_authority} · "
        f"claims +{added_claims} · witness +{added_witness} · registry keys +{added_keys} · "
        f"policy batch {BATCH_ID} → {8 + len(rows)}"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
