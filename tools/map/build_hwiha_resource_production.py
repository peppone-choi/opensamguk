#!/usr/bin/env python3
"""HWIHA 縣 월간 철·말 생산 원장.

**어느 縣이 생산하는가는 사료가 정하고, 얼마나 생산하는가는 게임 설계가 정한다.**
`resource-sites-v1.json` 은 스스로 「게임 수치가 아니다」라고 적어 둔 근거 원장이라 산출량이 없다.
그래서 이 도구는 산지 **위치**만 그 원장에서 읽고, 산출량은 아래 [RATES] 로 명시해 원장에 함께 적는다.
수치를 바꾸려면 코드가 아니라 이 상수를 고치고 다시 생성한다.

범위:
  - 철: 後漢 항목 중 縣(jurisdictionId) 이 결속된 것. 郡만 결속된 항목은 縣을 특정할 수 없어 뺀다.
  - 말: 後漢 항목은 郡 단위 기록뿐이라 그 郡의 治所 縣에 싣는다. 州만 적힌 항목·출처 없는 항목은 뺀다.
  - 목재: 後漢 항목이 1건뿐이라 「목재는 분산」이라는 설계를 뒷받침하지 못한다. 넣지 않는다.
  - 소금: 5자원에 없다.
"""
import argparse
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SITES = ROOT / "data/curated/han/resource-sites-v1.json"
TILES = ROOT / "data/map/han-tiles.json"
OUT = ROOT / "data/curated/han/hwiha-resource-production-v1.json"

# 게임 설계 수치다. 사료 근거가 없다 — 사료는 「어디에」만 말한다.
# 규모 감각: 전 지도 월 전 5,005만 · 곡 5억 1,302만(초안 규모)에 견주면 철·말은 3 자리 수 더 희소하다.
RATES = {"IRON": 1000, "HORSE": 100}
ERA = "LATER_HAN"


def build() -> dict:
    sites = json.loads(SITES.read_text(encoding="utf-8"))
    tiles = json.loads(TILES.read_text(encoding="utf-8"))
    seat_of = {c["id"]: c.get("seatJurisdictionId") for c in tiles["commanderyRecords"]}
    known = {j["id"] for j in tiles["jurisdictionRecords"]}

    rows, skipped = {}, []
    for entry in sites["entries"]:
        if entry["era"] != ERA or entry["resource"] not in RATES:
            continue
        resource = entry["resource"]
        if resource == "IRON":
            county = entry.get("jurisdictionId")
            basis = "SOURCE_COUNTY"
        else:
            county = seat_of.get(entry.get("commanderyId") or "")
            basis = "COMMANDERY_SEAT"
        if not county or county not in known:
            skipped.append({"id": entry["id"], "resource": resource,
                            "matchStatus": entry["matchStatus"], "reason": "no bound county"})
            continue
        row = rows.setdefault(county, {"jurisdictionId": county, "sites": [], "monthly": {}})
        row["sites"].append({"id": entry["id"], "resource": resource, "basis": basis,
                             "sourceName": entry.get("sourceName"), "matchStatus": entry["matchStatus"]})
        row["monthly"][resource] = row["monthly"].get(resource, 0) + RATES[resource]

    ordered = [rows[k] for k in sorted(rows)]
    for row in ordered:
        row["sites"].sort(key=lambda s: s["id"])
        row["monthly"] = {k: row["monthly"][k] for k in sorted(row["monthly"])}
    totals = {}
    for row in ordered:
        for resource, amount in row["monthly"].items():
            totals[resource] = totals.get(resource, 0) + amount
    return {
        "schemaVersion": 1,
        "ledgerId": "hwiha-resource-production-v1",
        "generator": "tools/map/build_hwiha_resource_production.py",
        "note": "산지 위치는 resource-sites-v1 의 사료 근거, 산출량은 게임 설계 수치다. 목재·소금은 넣지 않는다.",
        "era": ERA,
        "rates": dict(sorted(RATES.items())),
        "source": {"path": "data/curated/han/resource-sites-v1.json",
                   "catalogId": sites["catalogId"]},
        "counts": {"counties": len(ordered), "monthlyTotals": dict(sorted(totals.items())),
                   "skipped": len(skipped)},
        "skipped": sorted(skipped, key=lambda s: s["id"]),
        "counties": ordered,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    built = build()
    text = json.dumps(built, ensure_ascii=False, indent=1) + "\n"
    if args.check:
        if not OUT.is_file() or OUT.read_text(encoding="utf-8") != text:
            print(f"STALE {OUT.relative_to(ROOT)} — python3 {parser.prog}", flush=True)
            return 1
        print(f"OK {OUT.relative_to(ROOT)} {built['counts']}")
        return 0
    OUT.write_text(text, encoding="utf-8")
    print(f"wrote {OUT.relative_to(ROOT)} {built['counts']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
