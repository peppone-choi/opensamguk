#!/usr/bin/env python3
"""城 없는 han-tiles 縣 관할을 경로 노드로 올리는 원장을 append-only 로 쓴다.

han-tiles 의 縣 관할 1,032 중 188 곳에는 게임 城이 없었다. 지도는 CHGIS 220 년 단면으로
땅을 나눴는데, 경로 노드 선정은 續漢書 郡國志(140 년경) 식별자에만 城을 세웠기 때문이다
(ADR-LITE-041). 그 땅은 깃발 없는 빈 프로빈스로 보였다. 사용자가 188 곳 전부를 城으로
올리라고 정했다(2026-09-15).

이 縣들은 郡國志 식별자가 없으므로 HHS 결속을 흉내 내지 않는다. 대신 검증기가 이미 갖고
있던 `REVIEWED_SOURCE_CLAIM` 결속(sourceClaimId)으로 묶는다. claim 의 근거는 둘 중 하나다.

  - CHGIS V6 縣 점 기록(SYS_ID·이름·BEG_YR·END_YR) — 175 곳
  - 城 없던 郡의 대리 治所 관할(jurisdiction-seat-recoveries-v1) — CHGIS 縣 층 기록이 없는 곳

**새 城으로 세우지 않는 것**은 원장의 `excluded` 에 이유와 함께 적는다.

  - ALREADY_ROUTE_NODE: 治所 점이 이미 경로 노드다(朔方·西河·定襄 833–835). 省 연결만 빠져
    있었다 — build_han_world 의 대리 治所 省 규칙이 붙인다.
  - SAME_PLACE_AS_ROUTE_NODE: 같은 자리(0.1 km 안)에 이미 같은 실체의 城이 서 있다.
    CHGIS 가 개명·이속을 다른 SYS_ID 로 적은 경우다(杜↔杜陵, 益都↔益侯國 …). 그 관할의 省은
    뒤 단계에서 기존 城 관할로 접는다(followUp).
  - SAME_PLACE_AS_CANDIDATE: 후보끼리 같은 자리의 개명 쌍 — 한 곳만 세운다.

年代는 사실로만 적는다. 이 원장은 城의 시나리오별 존속을 주장하지 않는다.

실행 순서: 이 도구 → materialize_han_route_node_selection → validate_han_route_node_selection →
build_han_world --target han-world-v3 → 이하 재생성 사슬.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import struct
import sys
import uuid
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CURATED = ROOT / "data" / "curated" / "han"
TILES = ROOT / "data" / "map" / "han-tiles.json"
WORLD = ROOT / "infra" / "src" / "main" / "resources" / "map" / "han-world-v3.json"
CHGIS_COUNTY_DBF = ROOT / "data" / "chgis-source" / "v6_time_cnty_pts_utf_wgs84.dbf"
SEAT_RECOVERIES = CURATED / "jurisdiction-seat-recoveries-v1.json"
CLAIMS = CURATED / "route-node-jurisdiction-claims-v1.json"
REGISTRY = CURATED / "route-node-key-registry-v1.json"
POLICY = CURATED / "route-node-review-policy-v1.json"
CONTRACT = CURATED / "route-node-validation-contract-v1.json"

BATCH_ID = "w2-cityless-jurisdiction-route-claim"
ISSUANCE_REASON = "CITYLESS_JURISDICTION_ROUTE_CLAIM_V1_APPEND"
CLAIM_PREFIX = "han-jurisdiction-route-claim-v1-"
SUBJECT_PREFIX = "han-tiles-jurisdiction:"
REVIEW_AUTHORITY = "user-approval-2026-09-15"
# 帶方郡 治所 점(external:v1:X004)은 외부 세력 금지 목록에 묶여 있었다. 帶方郡은 建安 연간 公孫康이
# 세운 漢 郡이라 금지 사유(郡國 밖 세력)에 해당하지 않고, 사용자가 188 곳 전부 승격을 정했다.
LIFTED_FORBIDDEN_PLACES = ("external:v1:X004",)

# 같은 자리의 동일 실체 — 좌표·SYS_ID 로 대조해 판정했다(0.1 km 안, 개명·이속 관계).
SAME_PLACE_AS_ROUTE_NODE = {
    "45748": (411, "贊縣(220–222)과 陰縣은 CHGIS 좌표가 같다(乾德故城)."),
    "70634": (5, "杜(220–264)는 杜陵(23–264)과 같은 자리 杜城이다."),
    "85064": (285, "鄄良縣(14–220)은 鄄城縣(23–434)과 같은 자리다."),
    "85281": (382, "益都縣(220–555)은 益侯國(75–220)의 개명이다."),
    "JURISDICTION-PARENT-0140-SEAT": (80, "新平郡 治所 漆縣은 右扶風에서 이속된 漆縣(0.02 km)이다."),
    "JURISDICTION-PARENT-0145-SEAT": (848, "毗陵典農校尉 治所는 毘陵縣(848)과 경위도가 같다."),
    "JURISDICTION-PARENT-0148-SEAT": (846, "汶山郡 治所 汶山縣은 綿虒道(846)와 같은 자리다(0.02 km)."),
    "JURISDICTION-PARENT-0157-SEAT": (191, "章武郡 治所 東平舒縣은 河閒國 東平舒縣(191)과 1.04 km 다."),
}
# 후보끼리 같은 자리의 개명 쌍 — 郡國志 연대 쪽(侯國)을 세운다.
SAME_PLACE_AS_CANDIDATE = {
    "85377": ("85376", "㡉縣(220–555)은 㡉侯國(30–220)의 개명이다 — 㡉侯國을 세운다."),
}
COUNTY_SUFFIXES = ("侯國", "侯国", "縣", "县", "道", "國", "国")


def _load(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def _dump(path: Path, document: dict) -> None:
    path.write_text(json.dumps(document, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def _sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _rel(path: Path) -> str:
    return path.relative_to(ROOT).as_posix()


def read_county_dbf(path: Path) -> dict[str, list[dict]]:
    """CHGIS V6 縣 점 DBF 전체를 SYS_ID 별로 읽는다(연도 필터 없음)."""
    raw = path.read_bytes()
    count, header, width = struct.unpack("<IHH", raw[4:12])
    fields, offset = [], 32
    while raw[offset] != 0x0D:
        fields.append((raw[offset:offset + 11].split(b"\0", 1)[0].decode("ascii"), raw[offset + 16]))
        offset += 32
    by_id: dict[str, list[dict]] = {}
    for index in range(count):
        start = header + index * width
        record, cursor, row = raw[start:start + width], 1, {}
        for name, size in fields:
            row[name] = record[cursor:cursor + size].decode("utf-8", "replace").strip()
            cursor += size
        by_id.setdefault(row["SYS_ID"], []).append(row)
    return by_id


def stem(name: str) -> str:
    for suffix in COUNTY_SUFFIXES:
        if name.endswith(suffix) and len(name) > len(suffix):
            return name[: -len(suffix)]
    return name


def physical_ref(place_id: str, kind: str, in_county_dbf: bool) -> str:
    if place_id.startswith("X"):
        return f"external:v1:{place_id}"
    if in_county_dbf:
        return f"chgis:v6:cnty:{place_id}"
    if kind == "COMMANDERY":
        return f"chgis:v6:pref:{place_id}"
    raise ValueError(f"physical place {place_id} has no reviewable namespace")


def cityless_jurisdictions(tiles: dict, world: dict) -> list[dict]:
    provinces = tiles["provinceRecords"]
    with_city = {provinces[row["spatialProvinceIndex"]]["jurisdictionId"]
                 for row in world["cities"] if row.get("spatialProvinceIndex") is not None}
    order = {row["id"]: index for index, row in enumerate(tiles["parentRegions"])}
    rows = [row for row in tiles["jurisdictionRecords"]
            if row["kind"] == "COUNTY" and row["id"] not in with_city]
    return sorted(rows, key=lambda row: (order[row["commanderyId"]], row["id"]))


def build_ledger(tiles: dict, world: dict, dbf: dict[str, list[dict]], dbf_sha: str,
                 recoveries_sha: str) -> dict:
    cities = {str(row["id"]): row for row in tiles["cities"]}
    parents = {row["id"]: row for row in tiles["parentRegions"]}
    commanderies = {row["id"]: row for row in tiles["commanderyRecords"]}
    route_places = {row["physicalPlaceRef"].rsplit(":", 1)[-1]: row["id"] for row in world["cities"]}
    seated_commanderies = {row["meta"]["junCh"] for row in world["cities"] if row["meta"].get("isSeat")}
    claims, excluded = [], []
    for jurisdiction in cityless_jurisdictions(tiles, world):
        jid, place_id = jurisdiction["id"], str(jurisdiction["seatPlaceId"])
        place = cities[place_id]
        parent = parents[jurisdiction["commanderyId"]]
        base = {"jurisdictionId": jid, "seatPlaceId": place_id, "nameCh": jurisdiction["nameCh"],
                "commanderyNameCh": parent["nameCh"]}
        if place_id in route_places:
            excluded.append({**base, "reason": "ALREADY_ROUTE_NODE", "routeNodeCityId": route_places[place_id],
                             "detail": "治所 점이 이미 경로 노드다. 省 연결은 build_han_world 대리 治所 省 규칙이 붙인다."})
            continue
        if jid in SAME_PLACE_AS_ROUTE_NODE:
            city_id, detail = SAME_PLACE_AS_ROUTE_NODE[jid]
            excluded.append({**base, "reason": "SAME_PLACE_AS_ROUTE_NODE", "routeNodeCityId": city_id,
                             "detail": detail, "followUp": "FOLD_PROVINCES_INTO_ROUTE_NODE_JURISDICTION"})
            continue
        if jid in SAME_PLACE_AS_CANDIDATE:
            kept, detail = SAME_PLACE_AS_CANDIDATE[jid]
            excluded.append({**base, "reason": "SAME_PLACE_AS_CANDIDATE", "keptJurisdictionId": kept,
                             "detail": detail, "followUp": "FOLD_PROVINCES_INTO_ROUTE_NODE_JURISDICTION"})
            continue
        records = dbf.get(place_id, []) if not place_id.startswith("X") else []
        if records:
            if len(records) != 1:
                raise ValueError(f"{jid}: CHGIS SYS_ID {place_id} is not a single county record")
            record = records[0]
            evidence = {
                "kind": "CHGIS_V6_COUNTY_POINT",
                "datasetPath": _rel(CHGIS_COUNTY_DBF), "datasetSha256": dbf_sha,
                "sysId": record["SYS_ID"], "nameCh": record["NAME_CH"], "nameFt": record["NAME_FT"],
                "beginYear": int(record["BEG_YR"]), "endYear": int(record["END_YR"]),
            }
            canonical = stem(record["NAME_FT"])
        else:
            if not jid.startswith("JURISDICTION-PARENT-"):
                raise ValueError(f"{jid}: neither a CHGIS county record nor a seat recovery")
            evidence = {
                "kind": "JURISDICTION_SEAT_RECOVERY",
                "datasetPath": _rel(SEAT_RECOVERIES), "datasetSha256": recoveries_sha,
                "jurisdictionId": jid,
            }
            canonical = stem(jurisdiction["nameCh"])
        # 郡治는 한 郡에 하나다. 郡國志 郡治 城이 이미 그 郡에 서 있으면(右扶風 槐里·陳國 陳·北地郡 富平)
        # 지도 220 년 단면의 治所 관할이라도 NON_SEAT 로 둔다.
        seat = (commanderies[jurisdiction["commanderyId"]]["seatJurisdictionId"] == jid
                and parent["nameCh"] not in seated_commanderies)
        claims.append({
            "sourceClaimId": f"{CLAIM_PREFIX}{jid}",
            "claimRole": "ROUTE_NODE",
            "reviewState": "APPROVED",
            "subjectType": "ADMINISTRATIVE_PLACE",
            "subjectKey": f"{SUBJECT_PREFIX}{jid}",
            "canonicalName": canonical,
            "nodeClass": "COUNTY_NODE",
            "seatRole": "COMMANDERY_SEAT" if seat else "NON_SEAT",
            "parentName": parent["nameCh"],
            "parentRef": f"han-tiles-commandery:{parent['id']}",
            "physicalPlaceRef": physical_ref(place_id, place["kind"], bool(records)),
            "tileBinding": {"jurisdictionId": jid, "commanderyId": parent["id"], "seatPlaceId": place_id},
            "evidence": evidence,
        })
    return {
        "schemaVersion": 1,
        "claimSetId": "han-w2-route-node-jurisdiction-claims-v1",
        "status": "APPROVED",
        "reviewAuthority": REVIEW_AUTHORITY,
        "reviewedAt": "2026-09-15",
        "policy": {
            "purpose": "郡國志 식별자가 없는 han-tiles 縣 관할에 REVIEWED_SOURCE_CLAIM 경로 노드를 준다.",
            "historicalBindingBasis": "REVIEWED_SOURCE_CLAIM",
            "lifecycleClaimed": False,
            "tilesSha256": _sha256(TILES),
        },
        "claims": claims,
        "excluded": excluded,
    }


def append_registry(registry: dict, claims: list[dict]) -> int:
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
    return added


def lift_forbidden(forbidden: dict) -> None:
    forbidden["physicalPlaceIds"] = [value for value in forbidden["physicalPlaceIds"]
                                     if value not in LIFTED_FORBIDDEN_PLACES]


def update_policy(policy: dict, claims: list[dict]) -> None:
    batches = policy["selectionBatches"]
    batch = next((row for row in batches if row["batchId"] == BATCH_ID), None)
    if batch is None:
        batch = {"batchId": BATCH_ID}
        batches.append(batch)
    batch.update({
        "criteria": "han-tiles COUNTY jurisdiction without a route node, bound by an APPROVED "
                    "route-node-jurisdiction-claims-v1 ROUTE_NODE claim",
        "expectedCount": len(claims),
        "reviewState": "APPROVED",
        "selectionRationale": (
            "지도 縣 관할 가운데 게임 城이 없어 빈 프로빈스로 남던 곳을 사용자 결정(2026-09-15, 188 곳 전부 승격)에 "
            "따라 경로 노드로 올린다. 郡國志 식별자가 없으므로 HHS 결속이 아니라 CHGIS 縣 점 또는 대리 治所 관할을 "
            "근거로 한 source claim 결속이다. 같은 자리의 동일 실체와 이미 경로 노드인 治所는 원장 excluded 에 남긴다."
        ),
        "claimLedger": _rel(CLAIMS),
    })
    expected = policy["expectedSelection"]
    route_count = sum(row["expectedCount"] for row in batches)
    expected["routeNodeCount"] = route_count
    expected["hhsAdministrativeBindingCount"] = route_count - len(claims)
    expected["reviewedSourceClaimBindingCount"] = len(claims)
    lift_forbidden(policy["forbiddenSelections"])
    policy["inputs"]["jurisdictionRouteClaims"] = {"path": _rel(CLAIMS), "sha256": _sha256(CLAIMS)}
    policy["inputs"]["routeNodeKeyRegistry"] = {"path": _rel(REGISTRY), "sha256": _sha256(REGISTRY)}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.parse_args()
    if not CHGIS_COUNTY_DBF.is_file():
        print(f"{_rel(CHGIS_COUNTY_DBF)} is required (gitignored, ADR-LITE-039)", file=sys.stderr)
        return 1
    tiles, world = _load(TILES), _load(WORLD)
    if CLAIMS.is_file():
        print(f"{_rel(CLAIMS)} already exists — append-only; refusing to rebuild claim identities", file=sys.stderr)
        return 1
    ledger = build_ledger(tiles, world, read_county_dbf(CHGIS_COUNTY_DBF), _sha256(CHGIS_COUNTY_DBF),
                          _sha256(SEAT_RECOVERIES))
    _dump(CLAIMS, ledger)
    registry = _load(REGISTRY)
    added = append_registry(registry, ledger["claims"])
    _dump(REGISTRY, registry)
    contract = _load(CONTRACT)
    lift_forbidden(contract["expectedForbiddenSelections"])
    _dump(CONTRACT, contract)
    policy = _load(POLICY)
    update_policy(policy, ledger["claims"])
    _dump(POLICY, policy)
    print(f"claims {len(ledger['claims'])} · excluded {len(ledger['excluded'])} · registry keys +{added} · "
          f"batch {BATCH_ID}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
