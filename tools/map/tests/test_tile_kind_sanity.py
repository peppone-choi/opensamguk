"""han-tiles.json 의 KINGDOM 태그가 하위 개체(侯國/屬國)나 郡 오탈을 승격시키지 않았는지 본다.

배경: build_han_places.py 의 TIER 딕셔너리는 CHGIS 원시 라벨 문자열 매칭으로
KINGDOM 을 판정한다. 실측(2026-08-24, OPENSAM-226 조사)으로 이 매칭이 縣級
侯國(예: 安眾侯國)과 屬國(예: 犍為屬國, #507 자체 근거로 COMMANDERY 여야 함),
심지어 이름이 그대로 郡 인 항목(樂安郡)까지 KINGDOM 으로 잘못 승격시키는 걸
확인했다. 재현 명령: build_han_places.py --year 220 --grid 768 →
build_terrain_grid.py --grid 768 → build_tile_grid.py, 결과 KINGDOM 13개 중
9개가 이 패턴에 걸렸다(진짜 20개 KINGDOM 群 과 겹치는 건 4개뿐).
TIER 세분류 수정은 별도 티켓(사료 근거 필요, historical-sources 로 확인) —
여기선 코드 없이도 지금 커밋된 han-tiles.json 에 대해 green 이고, 그 결함이
재발하면 즉시 잡는다.
"""
from __future__ import annotations

import json
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
TILES = ROOT / "data/map/han-tiles.json"
DEMOTED_SUFFIXES = ("侯國", "屬國", "郡")


class TileKindSanityTest(unittest.TestCase):
    def test_kingdom_cities_are_not_demoted_units(self) -> None:
        cities = json.loads(TILES.read_text())["cities"]
        offenders = [
            c["nameCh"]
            for c in cities
            if c["kind"] == "KINGDOM" and c["nameCh"].endswith(DEMOTED_SUFFIXES)
        ]
        if offenders:
            self.fail(
                "han-tiles.json 이 侯國/屬國/郡 을 KINGDOM 으로 잘못 승격시켰다: "
                f"{sorted(offenders)}. build_han_places.py 의 TIER 원시 라벨 매칭이 "
                "하위 개체를 상위 등급으로 잘못 승격시키는 알려진 결함이다 — "
                "TIER 세분류 수정 티켓 참조. 카탈로그가 아니라 build_han_places.py 를 고쳐라."
            )


if __name__ == "__main__":
    unittest.main()
