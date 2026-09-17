"""羌·氐 변방 실체의 표현 고정 (§6, ADR-LITE-053 → 2026-09-17 ADR-LITE-056 로 점령 가능화).

2026-09-17 사용자 결정(「이민족이나 중국 밖의 거점들… 점령 가능한 거점이어야」)으로 053 의 「현행 장식의
점령 가능화」 기각을 뒤집었다. 좌표를 옮기지 않는다는 결정은 그대로다 — 城은 그 자리(X058·X059)에 선다.

西羌 X058·白馬氐 X059는 EXTERNAL_PLACE(지도 장식 + 장차 변방 상호작용 갈고리)다.
지도 안 국가로 세우지도, 좌표를 옮기지도 않는다. 이 검사는 그 표현이 몰래 바뀌는
것만 막는다 — 수치·이름을 미래까지 고정하는 것이 아니라, 바뀌면 심사하라는 트립와이어다.
"""

import json
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
PLACES = ROOT / "data" / "map" / "external-places.json"
WORLD = ROOT / "infra" / "src" / "main" / "resources" / "map" / "han-world-v3.json"

EXPECTED = {
    "X058": {"nameCh": "西羌", "lon": 101.77866, "lat": 36.62386},
    "X059": {"nameCh": "白馬氐", "lon": 105.349, "lat": 33.535},
}


class FrontierQiangDiTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        places = json.loads(PLACES.read_text(encoding="utf-8"))["places"]
        cls.by_id = {p["id"]: p for p in places}
        cls.world = json.loads(WORLD.read_text(encoding="utf-8"))

    def test_qiang_di_stay_external_places(self):
        for pid, want in EXPECTED.items():
            place = self.by_id[pid]
            self.assertEqual("EXTERNAL_PLACE", place["kind"])
            self.assertIsNone(place["jun"])
            self.assertIsNone(place["prov"])
            self.assertEqual(want["nameCh"], place["nameCh"])

    def test_qiang_di_coordinates_are_not_moved(self):
        for pid, want in EXPECTED.items():
            place = self.by_id[pid]
            self.assertAlmostEqual(want["lon"], place["lon"], places=5)
            self.assertAlmostEqual(want["lat"], place["lat"], places=5)

    def test_qiang_di_are_playable_cities_at_their_own_place(self):
        for pid, want in EXPECTED.items():
            cities = [c for c in self.world["cities"] if c.get("physicalPlaceRef") == f"external:v1:{pid}"]
            self.assertEqual(1, len(cities), pid)
            self.assertEqual(want["nameCh"], cities[0]["meta"]["nameCh"])
            # 이민족 등급 이(4).
            self.assertEqual(4, cities[0]["level"], pid)


if __name__ == "__main__":
    unittest.main()
