#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""같은 郡 안에서 한글 표시명이 겹치는 관할 목록 — 이슈 #838.

han-tiles `jurisdictionRecords[]` 를 (commanderyId, displayName) 으로 묶어 둘 이상이 한 이름을 쓰면 목록에 올린다
(영천군 「양성현」 = 陽城·襄城). 표시명 자체는 바꾸지 않는다(2026-09-18 결정) — 웹이 이 목록의 관할에만
작은 漢字 병기(「양성현 陽城」)를 붙인다.

병기 글자는 nameCh 에서 县/縣 꼬리를 떼고 繁體로 올린 것이다. 繁體는 `han-name-simplification-v1.json`
(繁→簡 표)을 뒤집어 얻는다. 한 簡體 글자에 繁體 후보가 둘 이상이면(宁 → 寧·甯) 정본
`administrative-units.json` 의 縣 이름에 실린 쪽을 쓰고, 거기도 없으면 아래 REVIEWED_TRADITIONAL 에 사료
증거와 함께 적힌 것만 쓴다. 그 밖의 모호함은 빌드가 실패한다 — 지어내지 않는다.

`classification` 은 두 번째 축(런타임 지도 han-world-v3 의 같은 관할 城 표시명)과 대조한 결과다.
  HOMOPHONE                        런타임도 같은 독음 — 진짜 同音異字(陽城·襄城)
  TILES_READING_DIVERGES_FROM_RUNTIME  런타임 城 이름은 서로 다르다 — han-tiles 쪽 독음이 의심된다(판정 아님)

    python3 tools/map/build_county_display_name_collisions.py          # 목록·웹 생성물 쓰기
    python3 tools/map/build_county_display_name_collisions.py --check  # 재생성 대조(새 충돌이 생기면 적색)

입력(커밋본): data/map/han-tiles.json, infra/src/main/resources/map/han-world-v3.json,
             data/curated/han/han-name-simplification-v1.json, data/curated/han/administrative-units.json
출력: data/curated/han/county-display-name-collisions-v1.json
      web/shared/src/iso/countyNameGloss.generated.ts (웹이 읽는 병기 표 — 같은 목록의 사본)
"""
from __future__ import annotations

import argparse
import collections
import hashlib
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
TILES = ROOT / "data/map/han-tiles.json"
WORLD = ROOT / "infra/src/main/resources/map/han-world-v3.json"
TABLE = ROOT / "data/curated/han/han-name-simplification-v1.json"
UNITS = ROOT / "data/curated/han/administrative-units.json"
OUTPUT = ROOT / "data/curated/han/county-display-name-collisions-v1.json"
WEB_OUTPUT = ROOT / "web/shared/src/iso/countyNameGloss.generated.ts"

COUNTY_SUFFIXES = ("县", "縣")
# 런타임 城 이름의 한정자(「양성현(襄城)」·「영릉#123」) — 독음 비교 전에 뗀다. cityName.ts QUALIFIER 와 같은 꼴.
QUALIFIER = re.compile(r"(?:\([^()]*\)|#\d+)+$")

# 繁體 후보가 둘 이상인데 정본 縣 이름에 없는 경우의 사람 판정. 키 = 簡體 어간.
REVIEWED_TRADITIONAL: dict[str, dict[str, str]] = {
    "始宁": {
        "traditional": "始寧",
        "witness": "宋書 卷067 謝靈運傳 「自始寧至會稽」 — 會稽 屬縣으로 寧 자를 쓴다(甯 아님)",
    },
}


def _load(path: Path):
    return json.loads(path.read_text(encoding="utf-8"))


def _sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _stem(name_ch: str) -> str:
    for suffix in COUNTY_SUFFIXES:
        if name_ch.endswith(suffix) and len(name_ch) > len(suffix):
            return name_ch[: -len(suffix)]
    return name_ch


def traditional_resolver(table_doc: dict, units: dict):
    """簡體 어간 → (繁體 어간, 근거). 표의 원래 繁→簡 쌍만 뒤집는다(異體字 추가분은 繁體가 아니라서 뺀다)."""
    inverse: dict[str, set[str]] = collections.defaultdict(set)
    for trad, simp in table_doc["table"].items():
        inverse[simp].add(trad)
    canon_names = {u["sourceName"] for g in units["groups"] for u in g["units"]}

    def resolve(stem: str) -> tuple[str, str]:
        choices = [""]
        for ch in stem:
            options = sorted(inverse.get(ch, set())) or [ch]
            choices = [prefix + option for prefix in choices for option in options]
        if len(choices) == 1:
            return choices[0], "SIMPLIFICATION_TABLE" if choices[0] != stem else "UNCHANGED"
        attested = [c for c in choices if c in canon_names]
        if len(attested) == 1:
            return attested[0], "CANON_ATTESTED"
        reviewed = REVIEWED_TRADITIONAL.get(stem)
        if reviewed and reviewed["traditional"] in choices:
            return reviewed["traditional"], "REVIEWED"
        raise ValueError(f"繁體 후보가 모호하다: {stem} → {choices} — REVIEWED_TRADITIONAL 에 사료 증거와 함께 적어라")

    return resolve


def build(tiles: dict, world: dict, table_doc: dict, units: dict) -> dict:
    resolve = traditional_resolver(table_doc, units)
    commanderies = {c["id"]: c for c in tiles["commanderyRecords"]}
    runtime = {c.get("spatialProvinceId"): c for c in world["cities"] if c.get("spatialProvinceId")}

    groups: dict[tuple[str, str], list[dict]] = collections.defaultdict(list)
    commanderies_by_name: dict[str, set[str]] = collections.defaultdict(set)
    for record in tiles["jurisdictionRecords"]:
        groups[(record["commanderyId"], record["displayName"])].append(record)
        commanderies_by_name[record["displayName"]].add(record["commanderyId"])

    collisions = []
    for (commandery_id, display_name), members in sorted(groups.items()):
        if len(members) < 2:
            continue
        rows = []
        for record in sorted(members, key=lambda r: r["id"]):
            stem = _stem(record["nameCh"])
            gloss, basis = resolve(stem)
            city = runtime.get(record["id"])
            rows.append({
                "jurisdictionId": record["id"],
                "kind": record["kind"],
                "nameCh": record["nameCh"],
                "simplifiedStem": stem,
                "gloss": gloss,
                "glossBasis": basis,
                "runtimeCityId": city["id"] if city else None,
                "runtimeDisplayName": (city.get("meta") or {}).get("displayName") if city else None,
            })
        runtime_readings = {QUALIFIER.sub("", r["runtimeDisplayName"]) for r in rows if r["runtimeDisplayName"]}
        glosses = [r["gloss"] for r in rows]
        if len(set(glosses)) != len(glosses):
            raise ValueError(f"병기로도 안 갈린다: {commandery_id} {display_name} {glosses}")
        commandery = commanderies.get(commandery_id, {})
        collisions.append({
            "commanderyId": commandery_id,
            "commanderyDisplayName": commandery.get("displayName"),
            "commanderyNameCh": commandery.get("nameCh"),
            "displayName": display_name,
            "classification": "TILES_READING_DIVERGES_FROM_RUNTIME" if len(runtime_readings) > 1 else "HOMOPHONE",
            "members": rows,
        })

    cross = {name: ids for name, ids in commanderies_by_name.items() if len(ids) > 1}
    return {
        "schemaVersion": 1,
        "listId": "county-display-name-collisions-v1",
        "issue": "https://github.com/peppone-choi/opensamguk/issues/838",
        "note": ("같은 郡 안에서 han-tiles jurisdictionRecords[].displayName 이 겹치는 관할. 표시명은 바꾸지 않고, 웹이 "
                 "이 목록의 관할에만 작은 漢字 병기(gloss)를 붙인다. 생성물이다 — 손으로 고치지 말고 빌더를 돌려라."),
        "generator": "tools/map/build_county_display_name_collisions.py",
        "inputs": {
            "hanTilesSha256": _sha256(TILES),
            "hanWorldV3Sha256": _sha256(WORLD),
            "simplificationTableSha256": _sha256(TABLE),
            "administrativeUnitsSha256": _sha256(UNITS),
        },
        "rule": "key = (commanderyId, displayName) over jurisdictionRecords[]; gloss = traditional(nameCh minus 县/縣)",
        "summary": {
            "jurisdictions": len(tiles["jurisdictionRecords"]),
            "sameCommanderyCollisionGroups": len(collisions),
            "sameCommanderyCollidingJurisdictions": sum(len(c["members"]) for c in collisions),
            "commanderiesWithCollisions": len({c["commanderyId"] for c in collisions}),
            "byClassification": dict(sorted(collections.Counter(c["classification"] for c in collisions).items())),
            # 참고용: 郡이 다르면 표시명이 겹쳐도 「뭐뭐군 뭐뭐현」에서 갈린다. 병기 대상이 아니다.
            "displayNamesSharedAcrossCommanderies": len(cross),
        },
        "collisions": collisions,
    }


def render_web(doc: dict) -> str:
    by_id = {m["jurisdictionId"]: m["gloss"] for c in doc["collisions"] for m in c["members"]}
    by_stem = {m["simplifiedStem"]: m["gloss"] for c in doc["collisions"] for m in c["members"]}

    def obj(mapping: dict[str, str]) -> str:
        return "\n".join(f"  {json.dumps(k, ensure_ascii=False)}: {json.dumps(v, ensure_ascii=False)}," for k, v in sorted(mapping.items()))

    return (
        "// 생성물 — tools/map/build_county_display_name_collisions.py 가 쓴다. 손으로 고치지 마라(--check 가 적색이 된다).\n"
        "// 원본 목록: data/curated/han/county-display-name-collisions-v1.json (이슈 #838).\n"
        f"// han-tiles sha256 {doc['inputs']['hanTilesSha256']}\n\n"
        "/** 같은 郡 안에서 한글 표시명이 겹치는 관할(jurisdictionId) → 漢字 병기(繁體, 县/縣 꼬리 없음). */\n"
        "export const COUNTY_GLOSS_BY_JURISDICTION_ID: Readonly<Record<string, string>> = {\n"
        f"{obj(by_id)}\n"
        "};\n\n"
        "/** 같은 목록의 簡體 어간 → 繁體 병기. 서버 표시명 꼬리 「(阳城)」를 병기로 바꿀 때 쓴다. */\n"
        "export const COUNTY_GLOSS_BY_SIMPLIFIED_STEM: Readonly<Record<string, string>> = {\n"
        f"{obj(by_stem)}\n"
        "};\n"
    )


def render_json(doc: dict) -> str:
    return json.dumps(doc, ensure_ascii=False, indent=2) + "\n"


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--check", action="store_true", help="재생성해 커밋본과 대조(쓰지 않는다)")
    args = ap.parse_args()
    doc = build(_load(TILES), _load(WORLD), _load(TABLE), _load(UNITS))
    outputs = {OUTPUT: render_json(doc), WEB_OUTPUT: render_web(doc)}
    summary = doc["summary"]
    print(f"같은 郡 안 표시명 충돌 {summary['sameCommanderyCollisionGroups']}쌍 "
          f"({summary['sameCommanderyCollidingJurisdictions']} 관할, {summary['commanderiesWithCollisions']} 郡) "
          f"{summary['byClassification']}")
    if args.check:
        stale = [p for p, text in outputs.items() if not p.exists() or p.read_text(encoding="utf-8") != text]
        if stale:
            for p in stale:
                print(f"STALE {p.relative_to(ROOT)} — python3 tools/map/build_county_display_name_collisions.py", file=sys.stderr)
            return 1
        print("county-display-name-collisions: 최신")
        return 0
    for p, text in outputs.items():
        p.write_text(text, encoding="utf-8")
        print(f"wrote {p.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
