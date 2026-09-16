"""도시 표기 규약 고정 (§8).

- 현: 정식 "○○군 ○○현". 서버 displayName 이 정본이며 지도·로그가 그대로 쓴다.
  일반 현은 이름 어간, 郡治 표시점(720 등 7곳)은 치소명이다.
- 현이 아닌 외부·관문·거점: 현재 표기 유지(강제 변환 금지).
- 같은 郡 안 同音異字 6곳은 漢字 어간을 뒤에 단다(떼면 같은 郡 안 쌍둥이가 된다).
  새 충돌이 생기면 이 집합에 추가해야 실패가 풀린다.
- 내부 식별 한정자(郡) 괄호·#번호는 displayName 에 새지 않는다(6곳 어간 제외).

개수·이름을 미래까지 고정하지 않는다. 새 도시·관할은 위 규약 중 하나에 반드시 맞는다.
"""

import json
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
WORLD = ROOT / "infra" / "src" / "main" / "resources" / "map" / "han-world-v3.json"

COUNTY_UNITS = ("县", "縣", "侯国", "侯國")
NON_COUNTY_UNITS = ("属国", "屬國", "郡", "国", "國")
COUNTY_LEVELS = (10, 11)

# 같은 郡 안 同音異字로 漢字 어간이 붙는 6곳 (id: displayName).
HANJA_STEM_DISPLAYS = {
    129: "영천군 양성현(襄城)",
    134: "영천군 양성현(阳城)",
    490: "영릉군 영도현(泠道)",
    495: "영릉군 영도현(营道)",
    527: "여강군 안풍현(安丰)",
    528: "여강군 안풍현(安风)",
    # 2026-09-16 한 세계 1098 에서 더해진 넷.
    # 수·진·관 거점은 郡 접두가 없어 다른 郡끼리도 겹친다 — 九江郡 渦口 · 巴郡 瓦口.
    1039: "와구(渦口)",
    1080: "와구(瓦口)",
    # 아래 둘은 한자까지 같아 어간이 **구분이 되지 못한다**. CHGIS 에 같은 이름 縣 기록이 둘씩 있고
    # (漢昌 44580 蒼溪 / 44621 巴中, 78 km · 富平 70524 靈武 / 70523 涇陽, 439 km — 後者는 北地郡 僑置로 보인다)
    # 1097 w2 가 다른 자리라 별개 城으로 세웠다. 한 縣의 두 城인지는 사료 판정이 필요하다(후속 과제).
    977: "파군 한창현(汉昌)",
    989: "북지군 부평현(富平)",
}

# 내부 식별 한정자: (郡)·#번호. 6곳 어간은 한자 한 글자라 따로 잰다.
IDENTIFIER_QUALIFIER = re.compile(r"(?:\([^()]*\)|#\d+)+$")


def is_han_county(city):
    if city["id"] < 0:
        return False
    name_ch = (city.get("meta") or {}).get("nameCh", "")
    if name_ch.endswith(COUNTY_UNITS):
        return True
    if name_ch.endswith(NON_COUNTY_UNITS):
        return False
    return city.get("level") in COUNTY_LEVELS


def county_stem(name):
    stem = IDENTIFIER_QUALIFIER.sub("", name)
    return stem if stem.endswith("현") else stem + "현"


class DisplayNotationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.cities = json.loads(WORLD.read_text(encoding="utf-8"))["cities"]

    def test_county_formal_names_cover_jun_and_hyeon(self):
        for city in self.cities:
            if not is_han_county(city):
                continue
            with self.subTest(city=city["id"]):
                meta = city.get("meta") or {}
                display = meta.get("displayName", "")
                jun = meta.get("jun", "")
                self.assertTrue(display.startswith(jun + " "))
                county_part = IDENTIFIER_QUALIFIER.sub("", display[len(jun) + 1:])
                # 정식 분기: 일반 현은 이름 어간, 郡治 표시점(720 등 7곳)은 치소명.
                # 치소 분기는 isSeat 도시의 meta.seat 과만 일치해야 한다.
                if county_part == county_stem(city["name"]):
                    continue
                self.assertEqual(meta.get("seat"), county_part)
                self.assertTrue(meta.get("isSeat"))

    def test_hanja_stem_set_changes_require_review(self):
        stems = {c["id"]: (c.get("meta") or {}).get("displayName", "")
                 for c in self.cities
                 if (c.get("meta") or {}).get("displayName", "").endswith(")")}
        self.assertEqual(HANJA_STEM_DISPLAYS, stems)

    def test_no_identifier_qualifier_leaks_into_display_names(self):
        for city in self.cities:
            with self.subTest(city=city["id"]):
                display = (city.get("meta") or {}).get("displayName", "")
                self.assertTrue(display)
                if city["id"] in HANJA_STEM_DISPLAYS:
                    continue
                self.assertIsNone(IDENTIFIER_QUALIFIER.search(display), display)

    def test_non_county_places_keep_current_notation(self):
        non_county = [c for c in self.cities if not is_han_county(c)]
        self.assertGreater(len(non_county), 0)
        for city in non_county:
            with self.subTest(city=city["id"]):
                self.assertTrue((city.get("meta") or {}).get("displayName"))


if __name__ == "__main__":
    unittest.main()
