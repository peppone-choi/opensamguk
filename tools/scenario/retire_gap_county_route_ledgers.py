#!/usr/bin/env python3
"""은퇴한 합성 결손 城을 W0 경로 노드 원장에서 거둔다 (2026-09-27 사용자 승인 D1).

`gap-counties-v1.json` 의 `retiredAsDuplicate` 행은 지도에 다른 이름으로 이미 있는 縣의 합성 城이다.
조선반도 취락 은퇴(korea-retired-settlements-v1)와 같은 규약을 쓴다 — 원장 행을 지우되,
발급했던 routeNodeKey 와 번호는 은퇴 원장에 옮겨 적고 번호는 `map_active_city_ids` 가 예약한다.

손대는 원장 (그 합성 城의 행만 지운다):
  - route-node-external-place-authority-v1.json  records
  - route-node-source-claims-v1.json             claims (+ authority 해시 갱신)
  - route-node-source-witness-v1.json            records
  - route-node-key-registry-v1.json              keys  → gap-county-duplicate-retirements-v1.json
실행 순서: 이 도구 → append_gap_county_route_ledgers → materialize_map_route_node_selection → validate.

    /usr/local/bin/python3 tools/scenario/retire_gap_county_route_ledgers.py
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from tools.scenario.append_frontier_county_route_ledgers import (  # noqa: E402
    AUTHORITY, CLAIMS, REGISTRY, WITNESS, _dump, _load, _sha256,
)
from tools.scenario.append_gap_county_route_ledgers import (  # noqa: E402
    CLAIM_PREFIX, GAP_LEDGER, ISSUANCE_REASON, refresh_authority_hashes,
)
from tools.scenario.map_active_city_ids import RETIRED_DUPLICATE_GAP_COUNTY_IDS  # noqa: E402

RETIREMENTS = ROOT / "data" / "curated" / "han" / "gap-county-duplicate-retirements-v1.json"
JURISDICTION_CLAIMS = ROOT / "data" / "curated" / "han" / "route-node-jurisdiction-claims-v1.json"


def retire() -> dict:
    gap = _load(GAP_LEDGER)
    retired = {row["id"]: row for row in gap.get("retiredAsDuplicate", [])}
    place_ids = {row["placeId"] for row in retired.values()}
    claim_ids = {f"{CLAIM_PREFIX}{place_id}" for place_id in place_ids}
    ledger = _load(RETIREMENTS) if RETIREMENTS.exists() else {
        "schemaVersion": 1,
        "ledgerId": "gap-county-duplicate-retirements-v1",
        "authority": "user-approval-2026-09-27-D1-retire-duplicate-synthetic-counties",
        "status": "REMOVED_FROM_ACTIVE_MAP",
        "reason": "지도에 개명·이체자·조합 표기·같은 점으로 이미 있는 縣에 2026-09-23 일괄 배치가 합성 城을 또 세웠다. "
                  "郡國志 행은 junguozhi-county-aliases-v1 로 기존 관할에 붙는다.",
        "previousRuntime": "han-world-v3-1447-map4",
        "replacementRuntime": "han-world-v3-1428",
        "evidenceLedger": "data/curated/han/gap-county-source-recheck-v1.json",
        "numericIdsReserved": [], "routeNodeKeys": [], "places": [],
    }

    registry = _load(REGISTRY)
    moved = [row for row in registry["keys"]
             if row["initialAdministrativeUnitId"] in retired and row.get("issuanceReason") == ISSUANCE_REASON]
    registry["keys"] = [row for row in registry["keys"] if row not in moved]
    authority = _load(AUTHORITY)
    places = [row for row in authority["records"] if row["recordId"] in place_ids]
    authority["records"] = [row for row in authority["records"] if row["recordId"] not in place_ids]
    claims = _load(CLAIMS)
    removed_claims = [row for row in claims["claims"] if row["sourceClaimId"] in claim_ids]
    claims["claims"] = [row for row in claims["claims"] if row["sourceClaimId"] not in claim_ids]
    witness = _load(WITNESS)
    removed_witness = [row for row in witness["records"] if row["sourceClaimId"] in claim_ids]
    witness["records"] = [row for row in witness["records"] if row["sourceClaimId"] not in claim_ids]

    known_keys = {row["routeNodeKey"] for row in ledger["routeNodeKeys"]}
    for row in moved:
        if row["routeNodeKey"] not in known_keys:
            ledger["routeNodeKeys"].append(row)
    known_places = {row["recordId"] for row in ledger["places"]}
    for row in places:
        if row["recordId"] not in known_places:
            kept = next(r for r in retired.values() if r["placeId"] == row["recordId"])
            ledger["places"].append({**row, "keptJurisdictionId": kept["keptJurisdictionId"],
                                     "keptJurisdictionNameCh": kept["keptJurisdictionNameCh"], "cause": kept["cause"]})
    ledger["routeNodeKeys"].sort(key=lambda row: row["numericCityId"])
    ledger["places"].sort(key=lambda row: row["recordId"])
    ledger["numericIdsReserved"] = sorted(row["numericCityId"] for row in ledger["routeNodeKeys"])
    if set(ledger["numericIdsReserved"]) != set(RETIRED_DUPLICATE_GAP_COUNTY_IDS):
        raise ValueError("retired numeric ids differ from map_active_city_ids.RETIRED_DUPLICATE_GAP_COUNTY_IDS")
    if len(ledger["routeNodeKeys"]) != len(retired) or len(ledger["places"]) != len(retired):
        raise ValueError("every retired gap county needs exactly one key and one place record")

    # 郡國志 첫 縣(순번 001)은 郡 治所다. 은퇴한 합성 城이 그 역할을 맡고 있었으면, 같은 縣인 기존 城의
    # 관할 claim 이 치소 역할을 이어받는다(월드 isSeat 는 route node seatRole 에서 온다).
    seat_claims = _load(JURISDICTION_CLAIMS)
    by_subject = {row["subjectKey"]: row for row in seat_claims["claims"]}
    inherited = []
    for row in retired.values():
        if not row["id"].endswith(":001"):
            continue
        claim = by_subject.get(f"han-tiles-jurisdiction:{row['keptJurisdictionId']}")
        if claim is None:
            raise ValueError(f"retired seat county has no jurisdiction claim to inherit the seat: {row['id']}")
        claim["seatRole"] = "COMMANDERY_SEAT"
        inherited.append(row["keptJurisdictionId"])
    for place in ledger["places"]:
        place["inheritsCommanderySeat"] = place["keptJurisdictionId"] in inherited
    (JURISDICTION_CLAIMS).write_text(json.dumps(seat_claims, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    _dump(AUTHORITY, authority)
    refreshed = refresh_authority_hashes(claims, _sha256(AUTHORITY))
    _dump(CLAIMS, claims)
    _dump(WITNESS, witness)
    _dump(REGISTRY, registry)
    RETIREMENTS.write_text(json.dumps(ledger, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return {"keys": len(moved), "places": len(places), "claims": len(removed_claims),
            "witness": len(removed_witness), "authorityHashesRefreshed": refreshed,
            "inheritedCommanderySeats": sorted(inherited)}


def main() -> int:
    argparse.ArgumentParser(description=__doc__,
                            formatter_class=argparse.RawDescriptionHelpFormatter).parse_args()
    print(json.dumps(retire(), ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
