"""현-없는 관할의 지도 공백 원인을 데이터에 대조해 분류한다(§3).

스크린샷의 빈틈은 단일 원인이 아니다. 이 검사는 커밋된 원장만으로 모든 관할의
정적 렌더 원인을 다음 중 하나로만 판정한다 — UNKNOWN(원인 불명 공백)이 하나라도
나오면 실패한다. 인접 도시 소유권 보간은 어디에도 쓰지 않는다.

- HAS_CITY: 실제 도시 대응 (live 점령은 R1 규칙을 따름)
- STATIC_NEUTRAL: 대응 도시 없이 기준 소유 0 (중립색, 설계상 정상)
- STATIC_OWNED: 대응 도시 없이 기준 소유 균일 (색칠됨, 공백 아님)
- STATIC_SPLIT_ALLOWLIST: 대응 도시 없이 혼합 + 충돌 허용 원장 등재 (심사 분할)
- EXTERNAL: 타일 관할 자체가 없는 외부 명의 — 2026-09-15 부로 0건이다
- ORPHAN_LAND: R2 위반 (육지 무소속) — 별도 게이트와 이중으로 잡는다

개수·이름을 고정하지 않는다. 새로 들어오는 도시·관할은 위 분류 중 하나에 반드시
걸려야 하며, 분류에 안 걸리는 실체(UNKNOWN)나 핀 밖 외부 도시가 생기면 실패한다.
시나리오는 live인 scenario_1020 기준이다.
"""

import json
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
TILES = ROOT / "data" / "map" / "han-tiles.json"
OWNERSHIP = ROOT / "data" / "map" / "han-scenario-province-ownership-v1.json"
ALLOWLIST = ROOT / "data" / "map" / "han-scenario-jurisdiction-conflict-allowlist-v1.json"
WORLD = ROOT / "infra" / "src" / "main" / "resources" / "map" / "han-world-v3.json"

# 외부 명의로 타일 관할이 없는 도시 (routeNodeKey 핀). 704 龜茲屬國과 城 없던 郡治
# 833–835(朔方·西河·定襄)는 2026-09-15 대리 治所 省 규칙(build_han_world.stand_in_seat_provinces)으로
# 제 직할 省을 얻어 이 목록에서 빠졌다. 새 외부 도시가 생기면 분류에 추가해야 실패가 풀린다.
KNOWN_EXTERNAL_ROUTE_KEYS = frozenset()


def classify():
    tiles = json.loads(TILES.read_text(encoding="utf-8"))
    ownership = json.loads(OWNERSHIP.read_text(encoding="utf-8"))
    allowlist = json.loads(ALLOWLIST.read_text(encoding="utf-8"))
    world = json.loads(WORLD.read_text(encoding="utf-8"))

    provinces = tiles["provinceRecords"]
    jurisdictions = {j["id"]: j["provinceIds"] for j in tiles["jurisdictionRecords"]}
    scenario = next(s for s in ownership["scenarios"] if s["scenarioCode"] == 1020)
    owners = {a["provinceId"]: (a["ownerNationId"] or 0) for a in scenario["assignments"]}
    allowed = {(e["scenarioCode"], e["jurisdictionId"]) for e in allowlist["entries"]}

    city_jurisdictions = {}
    external = []
    for city in world["cities"]:
        province_index = city.get("spatialProvinceIndex")
        if province_index is None:
            external.append(city)
            continue
        jurisdiction_id = provinces[province_index]["jurisdictionId"]
        city_jurisdictions.setdefault(jurisdiction_id, []).append(city["id"])

    report = {"HAS_CITY": 0, "STATIC_NEUTRAL": 0, "STATIC_OWNED": 0,
              "STATIC_SPLIT_ALLOWLIST": 0, "UNKNOWN": []}
    for jid, province_ids in jurisdictions.items():
        if jid in city_jurisdictions:
            report["HAS_CITY"] += 1
            continue
        nation_set = {owners[pid] for pid in province_ids}
        if len(nation_set) == 1:
            nation = next(iter(nation_set))
            report["STATIC_NEUTRAL" if nation == 0 else "STATIC_OWNED"] += 1
        elif (1020, jid) in allowed:
            report["STATIC_SPLIT_ALLOWLIST"] += 1
        else:
            report["UNKNOWN"].append(jid)
    return report, external


class JurisdictionGapClassificationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.report, cls.external = classify()

    def test_every_jurisdiction_has_a_classified_render_cause(self):
        self.assertEqual([], self.report["UNKNOWN"])

    def test_unmapped_jurisdictions_are_neutral_owned_or_allowlisted(self):
        total = (self.report["STATIC_NEUTRAL"] + self.report["STATIC_OWNED"] +
                 self.report["STATIC_SPLIT_ALLOWLIST"] + len(self.report["UNKNOWN"]))
        mapped = self.report["HAS_CITY"]
        self.assertGreater(total, 0, "unmapped jurisdictions must exist in current data")
        self.assertGreater(mapped, 0, "mapped jurisdictions must exist in current data")

    def test_cities_without_tile_province_are_only_known_externals(self):
        keys = {c.get("routeNodeKey") for c in self.external}
        self.assertEqual(KNOWN_EXTERNAL_ROUTE_KEYS, keys)
        for city in self.external:
            self.assertTrue(str(city.get("physicalPlaceRef", "")).startswith("external:v1:"),
                            city["id"])

    def test_no_orphan_land_cells(self):
        from tools.map import build_tile_grid as tile_builder

        tiles = json.loads(TILES.read_text(encoding="utf-8"))
        tile_builder.assert_no_orphan_land(
            tiles["owner"], tiles["terrain"], tiles["_meta"]["cols"])


if __name__ == "__main__":
    unittest.main()
