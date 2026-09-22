#!/usr/bin/env python3
"""郡國志에 실렸는데 지도에 좌표가 없던 縣 가운데, 좌표 원장이 이미 가진 것을 검토 원장으로 세운다.

`materialize_frontier_counties.py` 가 먹는 것과 같은 모양이다 — 그 도구가 변경 7郡 51縣을 그렇게
지도 정본에 올렸다. 이 도구는 그 앞단으로 **원장을 만들기만 한다**. 지도는 건드리지 않는다.

입력은 전부 이미 검증된 저장소 원장이다. 코퍼스를 직접 파싱하지 않는다 —
`administrative-units.json` 이 105群 1,180縣을 卷·順·인용(corpusPath·line·snapshotSha256)까지
달아 둔 정본 카탈로그다. 직접 파싱하면 卷마다 다른 마크업(`〖縣〗` · 괄호 없는 들여쓰기 · `◎ 郡`)에
걸려 城數가 어긋난다 — 실제로 9 郡이 어긋나는 것을 확인했다.

  - `data/curated/han/administrative-units.json` — 縣 정본과 사료 인용
  - `data/curated/han/junguozhi-county-gaps-v1.json` — 결손 후보(ABSENT). **판정이 아니라 후보다**
  - `data/curated/han/namu-place-locations-v1.json` — 좌표(전부 APPROXIMATE)

이름 대조는 `audit_county_coverage.make_normalizer()` 를 쓴다 — 자체 접미사 규칙을 만들면 邑·道·國
을 잘라 허위 부재가 무더기로 나온다(전에 23縣 겪었다).

    python3 tools/map/build_absent_county_ledger.py [--check]
"""
import argparse
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools/map"))
import audit_county_coverage as acc  # noqa: E402

CANON = ROOT / "data/curated/han/administrative-units.json"
GAPS = ROOT / "data/curated/han/junguozhi-county-gaps-v1.json"
NAMU = ROOT / "data/curated/han/namu-place-locations-v1.json"
OUT = ROOT / "data/curated/han/absent-county-placements-v1.json"


def build() -> dict:
    fold = acc.make_normalizer()
    fold_group = acc.make_normalizer(group=True)

    canon = json.loads(CANON.read_text(encoding="utf-8"))
    units = {}
    for group in canon["groups"]:
        for unit in group["units"]:
            units[(fold_group(unit["canonicalGroup"]), fold(unit["sourceName"]))] = (group, unit)

    coords = {
        (fold_group(r["canonicalGroup"]), fold(r["sourceName"])): r
        for r in json.loads(NAMU.read_text(encoding="utf-8"))["rows"]
    }

    gaps = json.loads(GAPS.read_text(encoding="utf-8"))
    counties, unmatched = [], []
    for commandery in gaps["commanderies"]:
        name_han = commandery["commandery"]
        for entry in commandery.get("counties", []):
            if entry.get("status") != "ABSENT":
                continue
            key = (fold_group(name_han), fold(entry["sourceName"]))
            place = coords.get(key)
            if place is None:
                continue  # 좌표가 없으면 세울 수 없다 — 지어내지 않는다
            found = units.get(key)
            if found is None:
                unmatched.append({"commandery": name_han, "name": entry["sourceName"]})
                continue
            group, unit = found
            citation = unit["sourceCitation"]
            counties.append({
                "id": f"hhs:{unit['sourceVolume']}:{name_han}:{unit['ordinal']:03d}",
                "nameHan": unit["sourceName"],
                "nameKo": place.get("nameKo", ""),
                "commanderyHan": name_han,
                "junguozhiOrdinal": unit["ordinal"],
                "role": unit["unitType"],
                "positionStatus": "APPROXIMATE",
                "coordinates": {"latitude": place["lat"], "longitude": place["lon"]},
                "modernLocation": place.get("modernLocation", ""),
                "coordinateSource": place.get("sourceUrl", ""),
                "primaryEvidence": [{
                    "book": f"續漢書·郡國志·{group['sourceGroupName']} {unit['sourceName']}",
                    "volume": unit["sourceVolume"],
                    "sourceRepository": "administrative-units-v1",
                    "sourcePath": citation["corpusPath"],
                    "sha256": citation["snapshotSha256"],
                    "line": citation["line"],
                    "sourceUrl": citation.get("sourceUrl", ""),
                    "evidenceKind": "RECEIVED_TEXT",
                }],
            })
    return {
        "schemaVersion": 1,
        "ledgerId": "absent-county-placements-v1",
        "note": (
            "郡國志에 실렸으나 지도에 좌표가 없던 縣 가운데, 좌표 원장(나무위키 수확본)이 이미 가진 것을 "
            "사료 인용과 함께 모았다. 좌표는 전부 APPROXIMATE 다 — 고증 실측이 아니다. "
            "결손 후보는 판정이 아니므로(개명·僑置·220년 단면 차이) 郡별 심사 뒤에 지도에 올린다. "
            "이 원장은 아직 지도에 올라가 있지 않다."
        ),
        "positionBasis": "NAMU_HARVEST_APPROXIMATE",
        "coverage": {"placeable": len(counties), "unmatchedInCanon": len(unmatched)},
        "unmatchedInCanon": unmatched,
        "counties": sorted(counties, key=lambda r: (r["commanderyHan"], r["junguozhiOrdinal"])),
    }


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args(argv)
    text = json.dumps(build(), ensure_ascii=False, indent=2) + "\n"
    if args.check:
        if not OUT.exists() or OUT.read_text(encoding="utf-8") != text:
            print(f"STALE {OUT.relative_to(ROOT)}")
            return 1
        print(f"OK {OUT.relative_to(ROOT)}")
        return 0
    OUT.write_text(text, encoding="utf-8")
    doc = json.loads(text)
    print(f"wrote {OUT.relative_to(ROOT)} {json.dumps(doc['coverage'], ensure_ascii=False)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
