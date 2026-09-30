#!/usr/bin/env python3
"""gap-county-source-recheck-v1 판정을 gap-counties-v1 에 적용한다 (2026-09-27 사용자 승인 D1·좌표 교체).

두 가지만 한다. 둘 다 몇 번을 돌려도 결과가 같다.
  1. 은퇴: junguozhi-county-aliases-v1 의 `retiredGapCountyId` 행을 counties 에서 빼 `retiredAsDuplicate` 로 옮긴다.
     지도에 다른 이름으로 이미 있는 縣의 합성 城이다 — 그 郡國志 행은 별칭으로 기존 관할에 붙는다.
  2. 좌표 교체: 판정 원장 `sourcedCoordinates` 의 합성 행을 `applied` 좌표로 바꾼다. 합성 칸은
     `supersededSyntheticPlacement` 로 남긴다.

한글 읽기(`append_all_gap_counties.py --refresh-readings`)와 실결손 추가(`append_all_gap_counties.py`)는
각 도구가 맡는다. 순서: 이 도구 → 읽기 갱신 → 추가 → han-tiles 꼬리 재구움.

    python3 tools/map/apply_gap_county_recheck.py          # 적용
    python3 tools/map/apply_gap_county_recheck.py --check  # 적용된 상태인지
"""
from __future__ import annotations

import argparse
import collections
import copy
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CURATED = ROOT / "data/curated/han"
LEDGER = CURATED / "gap-counties-v1.json"
RECHECK = CURATED / "gap-county-source-recheck-v1.json"
ALIASES = CURATED / "junguozhi-county-aliases-v1.json"
SYNTHETIC = "SYNTHETIC_COMMANDERY_CELL"
NOTE = ("2026-09-27: 합성 城 중 지도에 다른 이름으로 이미 있는 縣 23곳을 retiredAsDuplicate 로 옮기고"
        "(junguozhi-county-aliases-v1), 출처 좌표를 확보한 합성 4곳의 좌표를 바꿨다(gap-county-source-recheck-v1).")


def _read(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def _render(doc: dict) -> str:
    return json.dumps(doc, ensure_ascii=False, indent=2) + "\n"


def apply(ledger: dict, recheck: dict, aliases: dict) -> dict:
    doc = copy.deepcopy(ledger)
    retire = {row["retiredGapCountyId"]: row for row in aliases["aliases"] if "retiredGapCountyId" in row}
    retired = doc.setdefault("retiredAsDuplicate", [])
    already = {row["id"] for row in retired}
    kept = []
    for row in doc["counties"]:
        alias = retire.get(row["id"])
        if alias is None:
            kept.append(row)
            continue
        if row["coordinateBasis"] != SYNTHETIC:
            raise ValueError(f"only synthetic rows are retired as duplicates: {row['id']}")
        if row["id"] not in already:
            retired.append({
                "id": row["id"], "placeId": alias["retiredPlaceId"], "nameHan": row["nameHan"],
                "nameKo": row["nameKo"], "commanderyHan": row["commanderyHan"],
                "keptJurisdictionId": alias["jurisdictionId"], "keptJurisdictionNameCh": alias["jurisdictionNameCh"],
                "cause": alias["cause"], "retiredAt": "2026-09-27",
                "evidenceLedger": "data/curated/han/gap-county-source-recheck-v1.json",
            })
    missing = set(retire) - {row["id"] for row in retired}
    if missing:
        raise ValueError(f"retirement rows not found: {sorted(missing)}")
    doc["counties"] = kept
    by_id = {row["id"]: row for row in doc["counties"]}
    for source in recheck["sourcedCoordinates"]:
        row = by_id[source["gapCountyId"]]
        applied = source["applied"]
        lon, lat = applied["lonLat"]
        if row["coordinateBasis"] == SYNTHETIC:
            row["supersededSyntheticPlacement"] = row.pop("syntheticPlacement")
        row["coordinates"] = {"latitude": lat, "longitude": lon}
        row["coordinateBasis"] = applied["coordinateBasis"]
        row["positionStatus"] = "APPROXIMATE"
        row["coordinateEvidence"] = {
            "ledger": "data/curated/han/gap-county-source-recheck-v1.json",
            "represents": applied["represents"], "sources": applied["sources"], "errorKm": source["errorKm"],
        }
    doc["totals"]["counties"] = len(doc["counties"])
    doc["totals"]["byCoordinateBasis"] = dict(collections.Counter(r["coordinateBasis"] for r in doc["counties"]))
    doc["totals"]["byNameScript"] = dict(collections.Counter(r["nameScript"] for r in doc["counties"]))
    doc["totals"]["commanderies"] = len({r["commanderyHan"] for r in doc["counties"]})
    doc["totals"]["retiredAsDuplicate"] = len(retired)
    if NOTE not in doc["note"]:
        doc["note"] += " " + NOTE
    return doc


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    current = _read(LEDGER)
    expected = _render(apply(current, _read(RECHECK), _read(ALIASES)))
    if args.check:
        if LEDGER.read_text(encoding="utf-8") != expected:
            print(f"DRIFT: {LEDGER.relative_to(ROOT)} — rerun {Path(__file__).name}")
            return 1
        print(f"OK {LEDGER.relative_to(ROOT)}")
        return 0
    LEDGER.write_text(expected, encoding="utf-8")
    print(f"wrote {LEDGER.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
