#!/usr/bin/env python3
"""씨앗 충돌 원장 초안 `county-seed-collisions-v1` 을 커밋본에서 다시 뽑는다 (GH #806 S0, spec §3 규칙 2).

실제 경위도 칸(lon/lat 투영, 내림)을 공유하는 城을 모은다. **판정을 짓지 않는다** — 모든 행은
`ruling: null`·`reviewState: NEEDS_HUMAN_REVIEW` 로 나간다. 기존 판정 원장에 같은 쌍이 나오면 그 사실(원장·행)만
`existingAdjudicationMentions` 에 적는다. 그것이 「같은 실체」 판정인지는 사람이 본다.

  python3 tools/map/draft_county_seed_collisions.py           # 원장을 다시 쓴다(사람 판정이 들어간 행은 보존)
  python3 tools/map/draft_county_seed_collisions.py --check   # 커밋된 원장의 기계 필드가 현행 han-tiles 와 맞는가
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from tools.map.partition_counties_by_location import TILES, project_cell, stage_input  # noqa: E402

LEDGER = ROOT / "data/curated/han/county-seed-collisions-v1.json"
CURATED = ROOT / "data/curated/han"
HUMAN_FIELDS = ("ruling", "reviewState", "basis", "evidenceRefs", "reviewedAt")


def _mentions(ids: list[str]) -> list[dict]:
    found = []
    folds = json.loads((CURATED / "cityless-jurisdiction-fold-decisions-v1.json").read_text(encoding="utf-8"))
    for row in folds["folds"]:
        if {row["sourceJurisdictionId"], row["targetJurisdictionId"]} <= set(ids):
            found.append({"ledger": "cityless-jurisdiction-fold-decisions-v1",
                          "row": f"{row['sourceJurisdictionId']}→{row['targetJurisdictionId']}",
                          "reason": row["reason"]})
    for name in ("han-place-duplicate-adjudications-v1", "han-place-merge-adjudications-v1"):
        text = json.loads((CURATED / f"{name}.json").read_text(encoding="utf-8"))
        for row in text["adjudications"]:
            blob = json.dumps(row, ensure_ascii=False)
            hit = [i for i in ids if f'"{i}"' in blob]
            if len(hit) >= 2:
                found.append({"ledger": name, "row": row.get("groupId") or row.get("adjudicationId"),
                              "memberIds": hit})
    return found


def collisions(stage_document: dict) -> list[dict]:
    projection = stage_document["_meta"]["projection"]
    juris_of = {pid: row["id"] for row in stage_document["jurisdictionRecords"] for pid in row["provinceIds"]}
    by_cell: dict[tuple[int, int], list[dict]] = {}
    for province in stage_document["provinceRecords"]:
        if province.get("cityIndex") is None:
            continue
        city = stage_document["cities"][province["cityIndex"]]
        by_cell.setdefault(project_cell(projection, city["lat"], city["lon"]), []).append(
            {"cityId": city["id"], "nameCh": city["nameCh"], "kind": city["kind"],
             "jurisdictionId": juris_of[province["id"]], "commanderyId": province["parentRegionId"],
             "lon": city["lon"], "lat": city["lat"]})
    rows = []
    for (row, col), members in sorted(by_cell.items()):
        if len(members) < 2:
            continue
        members.sort(key=lambda m: m["cityId"])
        ids = [m["jurisdictionId"] for m in members]
        rows.append({
            "cell": {"col": col, "row": row}, "members": members,
            "sameLonLat": len({(m["lon"], m["lat"]) for m in members}) == 1,
            "sameCommandery": len({m["commanderyId"] for m in members}) == 1,
            "existingAdjudicationMentions": _mentions(ids),
            "ruling": None,  # SAME_ENTITY | DISTINCT_COUNTIES — 사람이 채운다
            "reviewState": "NEEDS_HUMAN_REVIEW", "basis": None, "evidenceRefs": [], "reviewedAt": None,
        })
    return rows


def build(previous: dict | None) -> dict:
    rows = collisions(stage_input(json.loads(TILES.read_text(encoding="utf-8"))))
    kept = {json.dumps(r["cell"], sort_keys=True): r for r in (previous or {}).get("rows", [])}
    for row in rows:
        old = kept.get(json.dumps(row["cell"], sort_keys=True))
        if old and [m["cityId"] for m in old["members"]] == [m["cityId"] for m in row["members"]]:
            row.update({key: old[key] for key in HUMAN_FIELDS if key in old})
    return {
        "schemaVersion": 1, "ledgerId": "county-seed-collisions-v1", "status": "DRAFT",
        "authority": "spec 2026-09-17-province-geography-first §3 규칙 2 — 자동 추정 금지",
        "generator": "tools/map/draft_county_seed_collisions.py",
        "scope": "거점 분할 앞 문서(★ 의 입력)의 城 있는 省. 실제 칸 = lon/lat 투영 내림.",
        "unreviewedDefault": "판정 전에는 분할기가 규칙 2(b)(id 순, 가장 가까운 빈 칸)로 놓고 UNREVIEWED 로 표시한다",
        "counts": {"cells": len(rows), "cities": sum(len(r["members"]) for r in rows),
                   "needsHumanReview": sum(r["reviewState"] == "NEEDS_HUMAN_REVIEW" for r in rows)},
        "rows": rows,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    previous = json.loads(LEDGER.read_text(encoding="utf-8")) if LEDGER.is_file() else None
    ledger = build(previous)
    if args.check:
        if previous != ledger:
            print("county-seed-collisions-v1 의 기계 필드가 현행 han-tiles 와 다르다 — 다시 뽑아라", file=sys.stderr)
            return 1
        return 0
    LEDGER.write_text(json.dumps(ledger, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(ledger["counts"]))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
