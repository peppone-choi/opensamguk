#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""郡國志 縣 목록과 han-tiles 관할의 차집합 원장 — 이슈 #180.

이름 대조만 한다(판정 아님). 後漢書 郡國志 105 郡國의 縣 이름을 han-tiles `jurisdictionRecords`
와 맞춰, 縣마다 IN_OWN_COMMANDERY / IN_OTHER_COMMANDERY / ABSENT 로 분류한다. 개명·僑置·
220년 단면 차이는 가리지 않으므로 모든 행은 `review: UNREVIEWED_NAME_MATCH` 다.

입력(커밋본): data/curated/han/administrative-units.json, data/map/han-tiles.json,
data/curated/han/hant-hans-placename-chars-v1.json
출력: data/curated/han/junguozhi-county-gaps-v1.json   (`--check` = 재생성 대조)
"""
from __future__ import annotations

import argparse
import collections
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
UNITS = ROOT / "data/curated/han/administrative-units.json"
TILES = ROOT / "data/map/han-tiles.json"
CHARS = ROOT / "data/curated/han/hant-hans-placename-chars-v1.json"
OUTPUT = ROOT / "data/curated/han/junguozhi-county-gaps-v1.json"
SUFFIX = re.compile(r"(县|道|侯国|国|邑)$")


def folder(chars: dict):
    table = str.maketrans({**chars["icu"], **chars["manual"]})

    def fold(name: str) -> str:
        return SUFFIX.sub("", name.translate(table))

    return fold


def build(units: dict, tiles: dict, chars: dict) -> dict:
    fold = folder(chars)
    commandery_name = {c["id"]: c["nameCh"] for c in tiles["commanderyRecords"]}
    by_commandery: dict[str, dict[str, str]] = collections.defaultdict(dict)
    anywhere: dict[str, list[str]] = collections.defaultdict(list)
    for j in tiles["jurisdictionRecords"]:
        com = commandery_name[j["commanderyId"]]
        by_commandery[fold(com)][fold(j["nameCh"])] = j["id"]
        anywhere[fold(j["nameCh"])].append(com)
    groups = []
    totals = collections.Counter()
    for g in units["groups"]:
        own = by_commandery.get(fold(g["canonicalGroup"]), {})
        rows = []
        for u in g["units"]:
            key = fold(u["sourceName"])
            if key in own:
                status, extra = "IN_OWN_COMMANDERY", {"jurisdictionId": own[key]}
            elif key in anywhere:
                status, extra = "IN_OTHER_COMMANDERY", {"foundIn": sorted(set(anywhere[key]))}
            else:
                status, extra = "ABSENT", {}
            totals[status] += 1
            rows.append({"ordinal": u["ordinal"], "sourceName": u["sourceName"], "status": status, **extra})
        first = rows[0] if rows else None
        groups.append({
            "sourceVolume": g["sourceVolume"],
            "commandery": g["canonicalGroup"],
            "inTiles": fold(g["canonicalGroup"]) in by_commandery,
            "declaredCounties": len(rows),
            "absent": sum(1 for r in rows if r["status"] == "ABSENT"),
            "firstListedCounty": first and {"sourceName": first["sourceName"], "status": first["status"]},
            "counties": rows,
        })
    return {
        "schemaVersion": 1,
        "review": "UNREVIEWED_NAME_MATCH",
        "note": "이름 대조 결과다. ABSENT 는 「지도에 없다」의 후보이지 판정이 아니다 — 개명·僑置·220년 단면 차이를 郡별로 심사해야 한다.",
        "totals": {"counties": sum(totals.values()), **{k: totals[k] for k in sorted(totals)},
                   "commanderiesWithAbsentFirstCounty": sum(
                       1 for g in groups if g["firstListedCounty"] and g["firstListedCounty"]["status"] == "ABSENT")},
        "commanderies": groups,
    }


def render(doc: dict) -> str:
    return json.dumps(doc, ensure_ascii=False, indent=1) + "\n"


def _rel(path: Path) -> str:
    try:
        return str(path.relative_to(ROOT))
    except ValueError:
        return str(path)


def main(argv=None) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--check", action="store_true")
    args = ap.parse_args(argv)
    doc = build(*(json.loads(p.read_text(encoding="utf-8")) for p in (UNITS, TILES, CHARS)))
    text = render(doc)
    if args.check:
        if not OUTPUT.exists() or OUTPUT.read_text(encoding="utf-8") != text:
            print(f"DRIFT: {_rel(OUTPUT)} is stale — rerun {Path(__file__).name}", file=sys.stderr)
            return 1
        print(f"OK {_rel(OUTPUT)} {doc['totals']}")
        return 0
    OUTPUT.write_text(text, encoding="utf-8")
    print(f"wrote {_rel(OUTPUT)} {doc['totals']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
