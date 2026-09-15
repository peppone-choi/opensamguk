"""羌·氐 변방 실체의 표현 고정 (§6, ADR-LITE-053).

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

    def test_qiang_di_are_not_playable_cities(self):
        names = {c["name"] for c in self.world["cities"]}
        metas = [json.dumps(c.get("meta"), ensure_ascii=False) for c in self.world["cities"]]
        for pid in EXPECTED:
            name = self.by_id[pid]["nameCh"]
            self.assertNotIn(name, names)
            self.assertFalse(any(name in meta for meta in metas), pid)


if __name__ == "__main__":
    unittest.main()
