#!/usr/bin/env python3
"""che 풀맵(94도시) 시드 생성기 — 공백지 포함.

소스:
  - 맵 좌표/스탯 동결 역사 참고(ADR-LITE-042; 현재 제품 정본 아님): legacy theme_che.js → core2026 map_che.json(동일 추출, 좌표 검증됨).
  - 소유: scenario_1010.json nation[].cities (후한=nation 1, 황건적=nation 2).
산출: infra/src/main/resources/scenario/cities_1010.json (94도시).

규칙(패리티):
  - 기존 24 소유 도시는 현 cities_1010.json 값 그대로 보존(P1에서 PHP 대조 검증된 값) — 절대 안 건드림.
  - 나머지 70 도시는 공백지(nation_id=0)로 추가. 스탯은 map.max + map.initial(CityConstBase 베이스).
    공백지 초기 스탯 = 베이스 initial(점령지 70%max 부스트는 scenario initialEvents가 점령지에만 적용 →
    공백지는 부스트 없음). importer가 nation_id==0이면 *_init를, 아니면 ratio70(*_max)를 쓴다.
재실행 멱등. map_che.json 변경 후 재생성하면 됨(data-driven).
"""
import json, os, sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MAP_CHE = os.path.join(ROOT, "legacy/devsam-core2026/resources/map/map_che.json")
CITIES = os.path.join(ROOT, "infra/src/main/resources/scenario/cities_1010.json")
SCENARIO = os.path.join(ROOT, "infra/src/main/resources/scenario/scenario_1010.json")

LEVEL_GLYPH = {1: "수", 2: "진", 3: "관", 4: "이", 5: "소", 6: "중", 7: "대", 8: "특"}
REGION_NAME = {1: "하북", 2: "중원", 3: "서북", 4: "서촉", 5: "남중", 6: "초", 7: "오월", 8: "동이"}


def main():
    mp = json.load(open(MAP_CHE, encoding="utf-8"))
    cur = json.load(open(CITIES, encoding="utf-8"))
    scn = json.load(open(SCENARIO, encoding="utf-8"))

    map_cities = mp["cities"]
    id2name = {c["id"]: c["name"] for c in map_cities}

    # 기존 24 소유 도시(보존). 이름 집합으로 중복 판정.
    existing = cur["cities"]
    owned_names = {c["name"] for c in existing}

    # 소유명 → nation_id (검증용). scenario nation 순서 = importer id 부여 순서(1,2,...).
    name_to_nation = {}
    for idx, nation in enumerate(scn["nation"], start=1):
        for cname in nation[8]:  # nation[8] = 소유 도시명 리스트
            name_to_nation[cname] = idx

    # 정합성 점검: 기존 24가 전부 맵에 있고 nation_id가 scenario 소유와 일치하는지.
    map_by_name = {c["name"]: c for c in map_cities}
    warns = []
    for c in existing:
        if c["name"] not in map_by_name:
            warns.append(f"기존도시 '{c['name']}' 가 map_che에 없음")
        exp = name_to_nation.get(c["name"])
        if exp is not None and exp != c["nation_id"]:
            warns.append(f"'{c['name']}' nation_id {c['nation_id']} != scenario {exp}")

    # 70 공백지 추가: map에 있으나 기존 24에 없는 도시.
    MAXK = [("pop_max", "population"), ("agri_max", "agriculture"), ("comm_max", "commerce"),
            ("secu_max", "security"), ("def_max", "defence"), ("wall_max", "wall")]
    INITK = [("pop_init", "population"), ("agri_init", "agriculture"), ("comm_init", "commerce"),
             ("secu_init", "security"), ("def_init", "defence"), ("wall_init", "wall")]
    neutrals = []
    for c in map_cities:
        if c["name"] in owned_names:
            continue
        pos = c["position"]
        entry = {
            "id": c["id"], "name": c["name"], "level": c["level"],
            "level_glyph": LEVEL_GLYPH.get(c["level"], "?"),
            "region": c["region"], "region_name": REGION_NAME.get(c["region"], "?"),
            "nation_id": 0,  # 공백지
        }
        for jk, mk in MAXK:
            entry[jk] = c["max"][mk]
        for jk, mk in INITK:
            entry[jk] = c["initial"][mk]
        entry["x"], entry["y"] = pos["x"], pos["y"]
        entry["connected"] = [id2name[i] for i in c.get("connections", []) if i in id2name]
        neutrals.append(entry)

    # id 충돌 점검(기존 24 id vs 70 공백지 id — map id가 유일하면 충돌 없음).
    ids = {c["id"] for c in existing}
    dup = [n["id"] for n in neutrals if n["id"] in ids]
    if dup:
        warns.append(f"id 충돌(기존24 vs 공백지): {dup}")

    merged = existing + neutrals
    merged.sort(key=lambda c: c["id"])
    cur["cities"] = merged
    cur["_comment"] = ("che 풀맵 94도시 — 소유 24(보존) + 공백지 70(nation_id=0). x/y=표시좌표(theme_che.js). "
                       "공백지 *_init=CityConstBase 베이스, 점령지는 importer가 ratio70(*_max) 적용. "
                       "재생성: tools/gen_cities_seed.py")

    json.dump(cur, open(CITIES, "w", encoding="utf-8"), ensure_ascii=False, indent=2)
    print(f"wrote {len(merged)} cities ({len(existing)} owned + {len(neutrals)} neutral)")
    by_nat = {}
    for c in merged:
        by_nat[c["nation_id"]] = by_nat.get(c["nation_id"], 0) + 1
    print("by nation_id:", dict(sorted(by_nat.items())))
    if warns:
        print("WARNINGS:")
        for w in warns:
            print("  -", w)
    else:
        print("정합성 OK (기존24 보존, nation_id 일치, id 충돌 없음)")


if __name__ == "__main__":
    main()
