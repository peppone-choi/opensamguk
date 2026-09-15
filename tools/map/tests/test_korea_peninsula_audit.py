"""한반도 외부거점 감사 (§7).

타일 한반도 권역(lon 124–131, lat 33–43)의 외부 실체는 원장에 근거·확실도 등급과
함께 있다. 이 검사는 감사 상태를 못박는다 — 새 실체가 등급 없이 들어오거나,
DISPUTED(비정 갈림) 실체가 도시로 승격되거나, 도시와 실체가 중복되면 실패한다.
도시·세력 승격 자체는 별도 기구 작업이다(본 작업에서 신규 도시를 만들지 않는다).
"""

import json
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
PLACES = ROOT / "data" / "map" / "external-places.json"
WORLD = ROOT / "infra" / "src" / "main" / "resources" / "map" / "han-world-v3.json"

LON = (124.0, 131.0)
LAT = (33.0, 43.0)

# 비정이 갈리는 실체 (원장 conf=DISPUTED). 도시 승격 금지 — 바뀌면 심사해야 풀린다.
DISPUTED_KOREA_PLACES = frozenset({"X030", "X040", "X041", "X047", "X055"})

# 도시가 물리 참조로 거는 외부 실체 (郡治 좌표). 720/X003 등 13곳. 새 연결이
# 생기면 심사해야 풀린다(승격 기구 작업).
# X004 帶方郡 治所는 2026-09-15 城 없던 縣 관할 승격(w2, 사용자 승인)으로 연결됐다 — 帶方郡은
# 建安 연간 公孫康이 세운 漢 郡이고 DISPUTED 가 아니다(route-node-jurisdiction-claims-v1).
# X027 張掖屬國 候官도 같은 승격으로 연결됐다(한반도 밖이지만 이 집합은 전역 external 연결이다).
LINKED_COMMANDERY_EXTERNALS = frozenset({
    "X000", "X001", "X002", "X003", "X004", "X005", "X006", "X007",
    "X011", "X023", "X024", "X026", "X027",
})


def korea_externals():
    places = json.loads(PLACES.read_text(encoding="utf-8"))["places"]
    return [p for p in places
            if p.get("lon") is not None and LON[0] <= p["lon"] <= LON[1]
            and p.get("lat") is not None and LAT[0] <= p["lat"] <= LAT[1]]


class KoreaPeninsulaAuditTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.externals = korea_externals()
        cls.world = json.loads(WORLD.read_text(encoding="utf-8"))

    def test_every_korea_external_carries_evidence_grade(self):
        self.assertGreater(len(self.externals), 0, "korea externals must exist")
        for place in self.externals:
            with self.subTest(place=place["id"]):
                self.assertIn(place.get("conf"), ("IDENTIFIED", "DISPUTED"))
                self.assertTrue((place.get("basis") or "").strip())

    def test_disputed_set_changes_require_review(self):
        disputed = {p["id"] for p in self.externals if p.get("conf") == "DISPUTED"}
        self.assertEqual(DISPUTED_KOREA_PLACES, disputed)

    def test_no_world_city_duplicates_an_external_entity(self):
        cities = self.world["cities"]
        city_name_ch = {c["meta"]["nameCh"] for c in cities}
        linked = {c.get("physicalPlaceRef") for c in cities
                  if str(c.get("physicalPlaceRef") or "").startswith("external:v1:")}
        linked_ids = {ref.rsplit(":", 1)[-1] for ref in linked}
        for place in self.externals:
            with self.subTest(place=place["id"]):
                self.assertTrue(place.get("nameCh"))
                # 한자 원명이 도시 원명과 겹치면 중복 실체다. 소속 郡 한자명(junCh)은
                # 비교하지 않는다 — 외부 郡 기록(X003 樂浪郡 등)과 그 郡 도시들의 junCh는
                # 정상적으로 겹친다.
                self.assertNotIn(place["nameCh"], city_name_ch)
        # 도시가 거는 외부 실체는 핀된 郡治 13곳뿐이다. 새 연결(승격)은 심사 대상이다.
        self.assertEqual(LINKED_COMMANDERY_EXTERNALS, linked_ids)

    def test_disputed_places_are_not_playable_cities(self):
        cities = self.world["cities"]
        world_name_ch = {c["meta"]["nameCh"] for c in cities}
        world_spatial = {c.get("spatialProvinceId") for c in cities}
        places = json.loads(PLACES.read_text(encoding="utf-8"))["places"]
        by_id = {p["id"]: p for p in places}
        for pid in DISPUTED_KOREA_PLACES:
            with self.subTest(place=pid):
                self.assertNotIn(by_id[pid].get("nameCh"), world_name_ch)
                self.assertNotIn(pid, world_spatial)
                self.assertFalse(
                    any((c.get("physicalPlaceRef") or "").endswith(":" + pid)
                        for c in cities), pid)


if __name__ == "__main__":
    unittest.main()
