#!/usr/bin/env python3
"""Register gameplay 城 whose 郡國志 unit already owns another route node.

This addition uses REVIEWED_SOURCE_CLAIM instead of reusing a historical
administrative identity. Their claim evidence explicitly records synthetic
placement and the source catalog row that prompted the extra game city.
"""
from __future__ import annotations

import hashlib
import json
import sys
import uuid
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from tools.scenario.han_active_city_ids import active_numeric_ids  # noqa: E402
from tools.scenario.han_route_node_selection import EXPECTED_SELECTION, LEGACY_SELECTION_COUNT  # noqa: E402

CURATED = ROOT / "data/curated/han"
GAPS = CURATED / "gap-counties-v1.json"
CARVES = CURATED / "strategic-site-province-carves-v1.json"
CLAIMS = CURATED / "route-node-jurisdiction-claims-v1.json"
REGISTRY = CURATED / "route-node-key-registry-v1.json"
POLICY = CURATED / "route-node-review-policy-v1.json"
TILES = ROOT / "data/map/han-tiles.json"
ISSUANCE = "CITYLESS_JURISDICTION_ROUTE_CLAIM_V1_APPEND"
BATCH = "w2-cityless-jurisdiction-route-claim"


def read(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def write(path: Path, document: dict) -> None:
    path.write_text(json.dumps(document, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main() -> int:
    gap, carves, claims, registry, policy, tiles = map(read, (GAPS, CARVES, CLAIMS, REGISTRY, POLICY, TILES))
    placement = {row["countyId"]: row for row in carves["geometry"]["stages"][0]["gapCountyPlacements"]}
    jurisdictions = {row["id"]: row for row in tiles["jurisdictionRecords"]}
    parents = {row["id"]: row for row in tiles["parentRegions"]}
    occupied_hhs = {row["initialAdministrativeUnitId"] for row in registry["keys"]
                    if row["initialAdministrativeUnitId"].startswith("hhs:")
                    and row.get("issuanceReason") != "GAP_COUNTY_V1_APPEND"}
    already = {row["subjectKey"] for row in claims["claims"]}
    used_keys = {row["routeNodeKey"] for row in registry["keys"]}
    new = []
    for county in gap["counties"]:
        if county["id"] not in occupied_hhs:
            continue
        if county["positionStatus"] != "SYNTHETIC":
            raise ValueError(f"already-bound county must be synthetic: {county['id']}")
        place_id = placement[county["id"]]["placeId"]
        subject = f"han-tiles-jurisdiction:{place_id}"
        if subject in already:
            continue
        jurisdiction = jurisdictions[place_id]
        parent = parents[jurisdiction["commanderyId"]]
        claim = {
            "sourceClaimId": f"han-jurisdiction-route-claim-v1-{place_id}",
            "claimRole": "ROUTE_NODE", "reviewState": "APPROVED",
            "subjectType": "ADMINISTRATIVE_PLACE", "subjectKey": subject,
            "canonicalName": county["nameHan"], "nodeClass": "COUNTY_NODE",
            "seatRole": "NON_SEAT", "parentName": parent["nameCh"],
            "parentRef": f"han-tiles-commandery:{parent['id']}",
            "physicalPlaceRef": f"curated:gap-county-v1:{place_id}",
            "tileBinding": {"jurisdictionId": place_id, "commanderyId": parent["id"],
                            "seatPlaceId": place_id},
            "evidence": {"kind": "USER_APPROVED_SYNTHETIC_GAME_CITY",
                         "datasetPath": "data/curated/han/gap-counties-v1.json",
                         "datasetSha256": sha(GAPS), "countyId": county["id"],
                         "jurisdictionId": place_id},
        }
        claims["claims"].append(claim)
        new.append(claim)
        already.add(subject)
    if len(new) != 1 or len(claims["claims"]) != 175:
        raise ValueError(f"expected exactly one synthetic duplicate claim, got {len(new)}")
    claims["reviewAuthority"] = "user-approval-2026-09-23"
    claims["reviewedAt"] = "2026-09-23"
    claims["policy"]["purpose"] += " 추가 1건은 기존 郡國志 식별자와 별개인 사용자 승인 합성 게임 城이다."
    claims["policy"]["tilesSha256"] = sha(TILES)
    write(CLAIMS, claims)
    appended_count = sum("numericCityId" in row for row in registry["keys"])
    expected = [value for value in active_numeric_ids(LEGACY_SELECTION_COUNT + appended_count + len(new))
                if value > LEGACY_SELECTION_COUNT]
    used_numbers = {row["numericCityId"] for row in registry["keys"] if "numericCityId" in row}
    free = iter(value for value in expected if value not in used_numbers)
    for claim in new:
        key = str(uuid.uuid4())
        while key in used_keys:
            key = str(uuid.uuid4())
        used_keys.add(key)
        registry["keys"].append({"routeNodeKey": key,
                                 "initialAdministrativeUnitId": claim["subjectKey"],
                                 "issuanceReason": ISSUANCE,
                                 "numericCityId": next(free)})
    write(REGISTRY, registry)
    batch = next(row for row in policy["selectionBatches"] if row["batchId"] == BATCH)
    batch["expectedCount"] = 175
    batch["selectionRationale"] += " 추가 1건은 원래 HHS 식별자가 기존 城에 사용되어 별도 source claim 으로 묶은 사용자 승인 합성 게임 城이다."
    policy["expectedSelection"] = dict(EXPECTED_SELECTION)
    policy["inputs"]["jurisdictionRouteClaims"] = {"path": "data/curated/han/route-node-jurisdiction-claims-v1.json",
                                                    "sha256": sha(CLAIMS)}
    policy["inputs"]["routeNodeKeyRegistry"] = {"path": "data/curated/han/route-node-key-registry-v1.json",
                                                  "sha256": sha(REGISTRY)}
    write(POLICY, policy)
    print("registered one synthetic duplicate county as a W2 source claim")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
