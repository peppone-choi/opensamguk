#!/usr/bin/env python3
"""시나리오를 후한 군현 맵(han, 175郡 · 780城)으로 갈아끼운다.

바꾸는 것은 네 가지뿐이다.
  1. `map.mapName` → "han-world-v2"
  2. `nation[i][8]` (세력 보유 성) → 사료 지배표(`han_ownership.json`)가 준 郡에
     속한 城 전부. 郡을 가지면 그 郡의 縣도 함께 갖는다 — 縣은 郡의 하급 행정구역이지
     별개 세력의 땅이 아니다. 목록의 첫 城이 수도가 되므로 治所를 맨 앞에 둔다.
  2a. 지배표가 `cities` 로 특정 縣을 짚으면(「屯新野」처럼) 그 縣은 郡 배정보다 우선한다 —
     남양군이 조조 것이어도 그 안의 신야현은 유비 것이다.
  2b. 사료상 領有한 郡이 없는 세력은 `nation[i][7]`(scale=nation level)을 **0** 으로
     내려 방랑군으로 둔다(2026-08-19 사용자 결정). 엔진이 `level > 0` 을 실재 세력으로
     보고 방랑군을 따로 처리하므로 새로 만드는 상태가 아니다. 城을 지어내지 않는다.
  3. `general[4]` / `general_ex[4]` / `general_neutral[4]` (장수 주둔지) →
     che 94성 대응표(`che_to_jun.json`)로 옮긴 城

**城은 이름이 아니라 id 로 적는다.** 780城 중에는 서로 다른 漢字가 같은 한글 독음이 되는
城이 있다(零陵郡 零陵縣·梁國 寧陵縣이 둘 다 '영릉'). 이름으로 가리키면 어느 郡의 縣인지가
안 정해진다. `ScenarioImporter` 는 숫자 토큰을 城 id 로, 그 외를 이름으로 푼다 —
che 시나리오(이름 그대로)는 그대로 돌아간다.

장수 위치가 대응표에 없거나, 옮긴 城이 그 세력 소유가 아니면 **null 로 비운다** —
그러면 ScenarioImporter 가 그 세력 영토 안에서 RNG 로 배치한다(원래 대부분이 그 경로다).
없는 이름을 지어내 넣지 않는다.

패러티는 사용자가 이 범위에 한해 면제했다(2026-08-19). 다만 근거 없는 배치는 넣지
않는다 — 지배표에 없는 郡은 공백지로 남는다.

  python3 tools/scenario/apply_han_world.py            # 덮어쓴다
  python3 tools/scenario/apply_han_world.py --check    # 드리프트만 보고, exit 1
"""
from __future__ import annotations

import argparse
import hashlib
import json
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
SCEN = ROOT / "infra/src/main/resources/scenario"
HAN_MAP = ROOT / "infra/src/main/resources/map/han.json"
HAN_V2_MAP = ROOT / "infra/src/main/resources/map/han-world-v2.json"
HAN_V2_MANIFEST = ROOT / "data/map/han-world-v2-manifest-v1.json"
HAN_TILES = ROOT / "data/map/han-tiles.json"
ROUTE_SELECTION = ROOT / "data/curated/han/route-node-selection-v1.json"
ROUTE_MIGRATION = ROOT / "data/curated/han/route-node-migration-v1.json"
ROUTE_CANDIDATES = ROOT / "data/curated/han/route-node-selection-candidates-v1.json"
CHE_TO_JUN = ROOT / "tools/scenario/che_to_jun.json"
OWNERSHIP = ROOT / "tools/scenario/han_ownership.json"
PALETTE = ROOT / "tools/scenario/nation_symbol_colors.json"


def canonical_parent_names() -> frozenset[str]:
    document = json.loads(HAN_TILES.read_text(encoding="utf-8"))
    return frozenset(row["displayName"] for row in document["parentRegions"])

LOC_SLOT = 4          # general 튜플의 주둔 城 이름 자리
NATION_SCALE = 7      # nation 튜플의 scale — 임포터가 nation.level 로 넣는다. 0 = 방랑군
NATION_CITIES = 8     # nation 튜플의 보유 城 목록 자리
GENERAL_KEYS = ("general", "general_ex", "general_neutral")

# Independently audited against ScenarioImporter lifecycle rules (adult age 14,
# death exclusive for legacy tuples, explicit appearance year inclusive).  These
# are literals on purpose: deriving the expected count from the same import path
# would let a truncation bug rewrite its own expectation.
ACTIVE_GENERAL_CONTRACTS = {
    "scenario_1010": (175, 230),
    "scenario_1020": (231, 299),
    "scenario_1021": (339, 339),
    "scenario_1030": (250, 327),
    "scenario_1031": (365, 365),
    "scenario_1040": (250, 327),
    "scenario_1041": (363, 363),
    "scenario_1050": (248, 320),
    "scenario_1060": (238, 305),
    "scenario_1070": (252, 317),
    "scenario_1080": (237, 302),
    "scenario_1090": (230, 289),
    "scenario_1100": (206, 260),
    "scenario_1110": (196, 249),
    "scenario_1120": (240, 309),
}


def validate_active_general_contracts(codes: list[str]) -> None:
    missing_contracts = sorted(set(codes) - set(ACTIVE_GENERAL_CONTRACTS))
    if missing_contracts:
        raise ValueError(
            "active-general seed contracts are missing: " + ", ".join(missing_contracts)
        )

# che 城 이름이 郡治가 아니라 특정 縣을 가리키는 경우. `che_to_jun.json` 은 郡까지만
# 대응시켜서, 그대로 두면 그 郡의 治所로 밀려난다. 사료가 縣을 못 박는 것만 여기 적는다.
CITY_OVERRIDE = {
    # 『三國志』呂布傳 「布……使備屯小沛」. 小沛 = 豫州 沛國 沛縣이고 治所 相縣이 아니다.
    "소패": "패",
}


def _sha256(path: pathlib.Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _load_verified_v2_world() -> dict:
    manifest = json.loads(HAN_V2_MANIFEST.read_text(encoding="utf-8"))
    expected_inputs = {
        "selectionSha256": ROUTE_SELECTION,
        "migrationSha256": ROUTE_MIGRATION,
        "hanTilesSha256": HAN_TILES,
    }
    for field, path in expected_inputs.items():
        if manifest["inputs"].get(field) != _sha256(path):
            raise ValueError(f"han-world-v2 manifest input hash mismatch: {field}")
    if manifest["outputs"].get("worldJsonSha256") != _sha256(HAN_V2_MAP):
        raise ValueError("han-world-v2 manifest output hash mismatch: worldJsonSha256")
    world = json.loads(HAN_V2_MAP.read_text(encoding="utf-8"))
    manifest_nodes = {
        (row["routeNodeKey"], row["numericCityId"], row["physicalPlaceRef"])
        for row in manifest["routeNodes"]
    }
    world_nodes = {
        (row["routeNodeKey"], row["id"], row["physicalPlaceRef"])
        for row in world["cities"]
    }
    if manifest_nodes != world_nodes or len(world_nodes) != 781:
        raise ValueError("han-world-v2 manifest route-node set mismatch")
    return world


def build_physical_id_migration(
    old_cities: list[dict], new_cities: list[dict],
    candidates: list[dict], migration_rows: list[dict],
) -> dict[int, int]:
    def unique(rows: list[dict], id_field: str, place_field: str, *, prefixed: bool) -> dict[str, int]:
        result: dict[str, int] = {}
        seen_ids: set[int] = set()
        for row in rows:
            city_id = row.get(id_field)
            place = row.get(place_field)
            if not isinstance(city_id, int) or city_id in seen_ids:
                raise ValueError("duplicate or invalid numeric city ID")
            seen_ids.add(city_id)
            if prefixed:
                if not isinstance(place, str) or ":" not in place:
                    raise ValueError("invalid physicalPlaceRef")
                place = place.rsplit(":", 1)[-1]
            else:
                place = str(place)
            if place in result:
                raise ValueError(f"duplicate physical place: {place}")
            result[place] = city_id
        return result

    old_by_place = unique(old_cities, "id", "physicalPlaceId", prefixed=False)
    new_by_place = unique(new_cities, "id", "physicalPlaceRef", prefixed=True)
    legacy_by_place: dict[str, int] = {}
    for row in candidates:
        if row.get("origin") != "CURRENT_780":
            continue
        place, old_id = str(row.get("legacyTileId")), row.get("legacyCityId")
        if not isinstance(old_id, int) or place in legacy_by_place:
            raise ValueError(f"duplicate physical place in legacy candidates: {place}")
        legacy_by_place[place] = old_id
    explicit = {row.get("oldCityId"): row for row in migration_rows}
    result: dict[int, int] = {}
    valid_new_ids = set(new_by_place.values())
    for place, old_runtime_id in old_by_place.items():
        if place in new_by_place:
            result[old_runtime_id] = new_by_place[place]
            continue
        legacy_id = legacy_by_place.get(place)
        row = explicit.get(legacy_id)
        if row is None or row.get("newCityId") not in valid_new_ids:
            raise ValueError(
                f"old city {old_runtime_id} physical place {place} has no explicit migration"
            )
        result[old_runtime_id] = row["newCityId"]
    return result


def load_world(map_name: str = "han") -> tuple[dict[str, list[int]], dict[str, int], dict[str, int]]:
    """郡 한글명 → 그 郡의 城 id 전부(治所가 맨 앞), 城 이름 → id, 郡 → 治所 id."""
    cities = (
        _load_verified_v2_world()["cities"]
        if map_name == "han-world-v2"
        else json.loads(HAN_MAP.read_text(encoding="utf-8"))["cities"]
    )
    by_jun: dict[str, list[int]] = {}
    seat_of: dict[str, int] = {}
    # 이름은 겹칠 수 있다(零陵縣·寧陵縣 둘 다 '영릉'). 겹치는 이름은 아예 빼서 지배표가
    # 그 이름으로 縣을 짚으면 조용히 엉뚱한 城에 가지 않고 경고가 나게 한다.
    id_of: dict[str, int] = {}
    dup: set[str] = set()
    for c in cities:
        jun, cid = c["meta"]["jun"], c["id"]
        if c["name"] in id_of:
            dup.add(c["name"])
        id_of[c["name"]] = cid
        by_jun.setdefault(jun, [])
        if c["meta"].get("isSeat"):
            seat_of[jun] = cid
            by_jun[jun].insert(0, cid)
        else:
            by_jun[jun].append(cid)
    for name in dup:
        id_of.pop(name, None)
    for jun in by_jun:
        if jun not in seat_of:
            seat_of[jun] = by_jun[jun][0]
    return by_jun, id_of, seat_of


def rewrite(doc: dict, code: str, by_jun: dict[str, list[int]], id_of: dict[str, int],
            seat_of: dict[str, int], che2jun: dict[str, str], own: dict,
            city_id_migration: dict[int, int] | None = None) -> tuple[dict, list[str]]:
    warn: list[str] = []
    doc = json.loads(json.dumps(doc))            # 깊은 복사 — 원본을 건드리지 않는다
    already_v2 = doc.get("cityIdentityVersion") == "han-world-v2"
    # 맵과 병종 세트는 한 몸이다 — han 맵의 城 게이트 키(州·郡·부족 漢字)를 읽는 건
    # han 병종표뿐이라, 맵만 바꾸고 병종을 che 로 두면 지역 병종이 통째로 죽는다.
    doc["map"] = {"mapName": "han-world-v2", "unitSet": "han"}
    if city_id_migration is not None:
        doc["cityIdentityVersion"] = "han-world-v2"
    doc["placementBasis"] = (own.get(code) or {}).get("placementBasis", "HISTORICAL")
    if code not in ACTIVE_GENERAL_CONTRACTS:
        raise ValueError(f"{code}: active-general seed contract is missing")
    base_generals, extended_generals = ACTIVE_GENERAL_CONTRACTS[code]
    doc["seedContract"] = {
        "activeGenerals": {"base": base_generals, "extended": extended_generals},
    }

    scenario_ownership = own.get(code) or {}
    table = scenario_ownership.get("nations") or {}
    nation_renames = scenario_ownership.get("nationRenames") or {}
    general_renames = scenario_ownership.get("generalRenames") or {}
    doc["imperialGenerals"] = list(scenario_ownership.get("imperialGenerals") or [])
    for key in GENERAL_KEYS:
        for general in doc.get(key) or []:
            if len(general) > 1 and general[1] in general_renames:
                general[1] = general_renames[general[1]]
    existing_general_names = {
        general[1]
        for key in GENERAL_KEYS
        for general in (doc.get(key) or [])
        if len(general) > 1
    }
    for general in scenario_ownership.get("generalAdditions") or []:
        if len(general) <= 1:
            continue
        if general[1] in existing_general_names:
            warn.append(f"{code}: 장수 추가 '{general[1]}' 이 이미 로스터에 있어 건너뛴다")
            continue
        doc.setdefault("general", []).append(general)
        existing_general_names.add(general[1])
    general_overrides = scenario_ownership.get("generalOverrides") or {}
    override_slots = {"officerLevel": 8}
    for key in GENERAL_KEYS:
        for general in doc.get(key) or []:
            overrides = general_overrides.get(general[1]) if len(general) > 1 else None
            for field, value in (overrides or {}).items():
                if field not in override_slots:
                    raise ValueError(f"{code}/{general[1]}: unsupported general override '{field}'")
                general[override_slots[field]] = value
    palette = json.loads(PALETTE.read_text(encoding="utf-8"))["colors"]
    province_only_parents = canonical_parent_names() - by_jun.keys()
    for row in doc.get("nation") or []:
        if row and row[0] in nation_renames:
            row[0] = nation_renames[row[0]]
        if row and row[0] in palette:
            row[1] = palette[row[0]]["hex"]
    taken: dict[int, str] = {}                   # 城 id → 세력. 중복 소유 차단.
    owner_of: dict[int, str] = {}

    # 縣 지정(`cities`)을 郡 확장보다 먼저 잡는다 — 지배표의 규칙이 「縣 배정이 郡 배정보다
    # 우선」이라, 남양군이 조조 것이어도 사료가 「使備屯新野」로 짚은 신야현은 유비 것이다.
    pinned: dict[str, list[int]] = {}
    for nation, d in table.items():
        for name in (d.get("cities") or []):
            city = id_of.get(name)
            if city is None:
                warn.append(f"{code}/{nation}: 縣 '{name}' 이 새 맵에 없다 — 건너뛴다")
                continue
            if city in taken:
                warn.append(f"{code}: 城 '{city}' 을 {taken[city]}·{nation} 이 겹쳐 짚는다 — 뒤를 버린다")
                continue
            taken[city] = nation
            owner_of[city] = nation
            pinned.setdefault(nation, []).append(city)

    for row in doc.get("nation") or []:
        if len(row) <= NATION_CITIES:
            continue
        nation = row[0]
        juns = (table.get(nation) or {}).get("juns")
        if juns is None and not pinned.get(nation):
            warn.append(f"{code}: 세력 '{nation}' 이 지배표에 없다 — 방랑군으로 뒀다")
            row[NATION_CITIES] = []
            if len(row) > NATION_SCALE:
                row[NATION_SCALE] = 0
            continue
        kept: list[int] = list(pinned.get(nation) or [])
        for jun in (juns or []):
            group = by_jun.get(jun)
            if group is None:
                # The canonical province map contains parent regions that have no
                # legacy city row. Their political colour comes from the province
                # ownership artifact; the old city tuple cannot represent them.
                if jun in province_only_parents:
                    continue
                warn.append(f"{code}/{nation}: 郡 '{jun}' 이 새 맵에 없다 — 건너뛴다")
                continue
            for city in group:
                if city in taken:
                    # 縣 지정에 이미 뺏긴 城은 규칙대로 넘어간 것이라 경고하지 않는다.
                    if city not in (pinned.get(taken[city]) or []):
                        warn.append(f"{code}: 城 '{city}' 을 {taken[city]}·{nation} 이 겹쳐 갖는다 — 뒤를 버린다")
                    continue
                taken[city] = nation
                owner_of[city] = nation
                kept.append(city)
        row[NATION_CITIES] = kept
        if not kept:
            # 사료상 領有한 郡이 없다 → 城을 지어내지 않고 방랑군으로 내린다.
            if len(row) > NATION_SCALE:
                row[NATION_SCALE] = 0
            warn.append(f"{code}: 세력 '{nation}' 은 領有 郡이 없어 방랑군(level 0)으로 뒀다")

    for key in GENERAL_KEYS:
        for g in doc.get(key) or []:
            if len(g) <= LOC_SLOT or not g[LOC_SLOT]:
                continue
            old = g[LOC_SLOT]
            # 이미 id 로 바뀐 입력(= 이 스크립트를 두 번째 돌리는 경우)은 건드리지 않는다.
            # 대응표는 che **이름**만 알아서, id 를 다시 먹이면 못 찾고 주둔지를 통째로 비운다.
            if isinstance(old, int) or (isinstance(old, str) and old.isdigit()):
                old_id = int(old)
                if city_id_migration is not None and not already_v2:
                    if old_id not in city_id_migration:
                        raise ValueError(f"{code}: unknown old numeric city ID {old_id}")
                    old_id = city_id_migration[old_id]
                g[LOC_SLOT] = old_id
                continue
            name = CITY_OVERRIDE.get(old)
            city = id_of.get(name) if name else None
            if city is None:
                jun = che2jun.get(old)
                city = seat_of.get(jun) if jun else None
            if city is None:
                warn.append(f"{code}: 장수 주둔지 '{old}' 를 옮길 수 없어 비웠다")
                g[LOC_SLOT] = None
                continue
            # 그 城이 이 장수의 세력 소유가 아니면 비운다 — 남의 성에 서 있게 두지 않는다.
            nation_ref = g[3] if len(g) > 3 else None
            if isinstance(nation_ref, str) and nation_ref not in ("재야", "") \
                    and owner_of.get(city) not in (None, nation_ref):
                g[LOC_SLOT] = None
                continue
            g[LOC_SLOT] = city
    return doc, warn


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--check", action="store_true")
    ap.add_argument("--map", choices=("han-world-v2",))
    args = ap.parse_args()

    inputs = [HAN_MAP, HAN_TILES, CHE_TO_JUN, OWNERSHIP, PALETTE]
    if args.map == "han-world-v2":
        inputs += [HAN_V2_MAP, HAN_V2_MANIFEST, ROUTE_SELECTION, ROUTE_MIGRATION, ROUTE_CANDIDATES]
    for path in inputs:
        if not path.exists():
            print(f"없는 입력: {path.relative_to(ROOT)}", file=sys.stderr)
            return 2

    by_jun, id_of, seat_of = load_world(args.map or "han")
    city_id_migration = None
    if args.map == "han-world-v2":
        old_cities = json.loads(HAN_MAP.read_text(encoding="utf-8"))["cities"]
        new_cities = _load_verified_v2_world()["cities"]
        candidates = json.loads(ROUTE_CANDIDATES.read_text(encoding="utf-8"))["candidates"]
        migration_rows = json.loads(ROUTE_MIGRATION.read_text(encoding="utf-8"))["rows"]
        city_id_migration = build_physical_id_migration(
            old_cities, new_cities, candidates, migration_rows
        )
    che2jun = {k: v["jun"] for k, v in
               json.loads(CHE_TO_JUN.read_text(encoding="utf-8"))["map"].items()}
    own = json.loads(OWNERSHIP.read_text(encoding="utf-8"))

    codes = [c for c in own if c.startswith("scenario_")]
    validate_active_general_contracts(codes)
    drift, warns = [], []
    for code in sorted(codes):
        path = SCEN / f"{code}.json"
        if not path.exists():
            warns.append(f"{code}: 시나리오 파일이 없다")
            continue
        raw = path.read_text(encoding="utf-8")
        doc = json.loads(raw)
        out, w = rewrite(
            doc, code, by_jun, id_of, seat_of, che2jun, own, city_id_migration
        )
        warns += w
        blob = json.dumps(out, ensure_ascii=False, indent=2) + "\n"
        if blob != raw:
            drift.append(code)
            if not args.check:
                path.write_text(blob, encoding="utf-8")

    for w in warns:
        print(f"  경고  {w}", file=sys.stderr)
    print(f"시나리오 {len(codes)}개 · 바뀐 파일 {len(drift)} · 경고 {len(warns)}", file=sys.stderr)
    if args.check and drift:
        print("드리프트: " + " ".join(drift), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
