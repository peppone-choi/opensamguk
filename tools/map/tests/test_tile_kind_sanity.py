"""han-tiles.json 의 KINGDOM 태그가 하위 개체(侯國/屬國)나 郡 오탈을 승격시키지 않았는지 본다.

배경: build_han_places.py 의 TIER 딕셔너리는 CHGIS 원시 라벨 문자열 매칭으로
KINGDOM 을 판정한다. 실측(2026-08-24, OPENSAM-226 조사)으로 이 매칭이 縣級
侯國(예: 安眾侯國)과 屬國(예: 犍為屬國, #507 자체 근거로 COMMANDERY 여야 함),
심지어 이름이 그대로 郡 인 항목(樂安郡)까지 KINGDOM 으로 잘못 승격시키는 걸
확인했다. 재현 명령: build_han_places.py --year 220 --grid 768 →
build_terrain_grid.py --grid 768 → build_tile_grid.py, 결과 KINGDOM 13개 중
9개가 이 패턴에 걸렸다(진짜 20개 KINGDOM 群 과 겹치는 건 4개뿐).
TIER 세분류 수정은 별도 티켓(사료 근거 필요, historical-sources 로 확인) —
여기선 코드 없이도 지금 커밋된 han-tiles.json 에 대해 green 이다 — 다만 그
green 은 **KINGDOM 이 0개라서** green 이다(아래 첫 테스트가 그 전제를 단언으로
고정한다). 재생성으로 KINGDOM 이 돌아오면 그때 승격 결함을 잡는다.
"""
from __future__ import annotations

import json
import unittest
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
TILES = ROOT / "data/map/han-tiles.json"
DEMOTED_SUFFIXES = ("侯國", "屬國", "郡")


class TileKindSanityTest(unittest.TestCase):
    def test_committed_tiles_carry_no_KINGDOM_at_all(self) -> None:
        """**이 파일의 숨은 전제를 드러낸다.** 지금 커밋된 han-tiles.json 에는
        KINGDOM 이 단 하나도 없다(1,138개 중 COUNTY 958 · COMMANDERY 140 ·
        EXTERNAL_PLACE 37 · PROVINCE 3 — #548 이 phantom 郡 노드 6개를 빼면서
        1,144/146 에서 내려왔다. COUNTY 는 안 움직였다). 그래서 아래 승격 회귀 테스트는 **오늘의
        산출물에 대해 아무것도 단언하지 않는다** — 재생성으로 KINGDOM 이 다시
        들어올 때만 살아난다. 그 사실을 주석이 아니라 단언으로 고정해서, 등급
        어휘가 조용히 바뀌면 여기서 먼저 걸리게 한다."""
        cities = json.loads(TILES.read_text())["cities"]
        kinds = Counter(c["kind"] for c in cities)
        self.assertEqual(
            {"COUNTY": 958, "COMMANDERY": 140, "EXTERNAL_PLACE": 37, "PROVINCE": 3},
            dict(kinds),
            "han-tiles.json 의 등급 분포가 바뀌었다 — 아래 KINGDOM 회귀의 전제가 달라졌다",
        )

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
