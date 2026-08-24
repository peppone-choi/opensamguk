"""han-tiles.json 의 KINGDOM 태그가 하위 개체(侯國/屬國)나 郡 오탈을 승격시키지 않았는지 본다.

배경: build_han_places.py 의 TIER 딕셔너리는 CHGIS 원시 라벨 문자열 매칭으로
KINGDOM 을 판정한다. 실측(2026-08-24, OPENSAM-226 조사)으로 이 매칭이 縣級
侯國(예: 安眾侯國)과 屬國(예: 犍為屬國, #507 자체 근거로 COMMANDERY 여야 함),
심지어 이름이 그대로 郡 인 항목(樂安郡)까지 KINGDOM 으로 잘못 승격시키는 걸
확인했다. 재현 명령: build_han_places.py --year 220 --grid 768 →
build_terrain_grid.py --grid 768 → build_tile_grid.py, 결과 KINGDOM 13개 중
9개가 이 패턴에 걸렸다(진짜 20개 KINGDOM 群 과 겹치는 건 4개뿐).
**2026-08-24 갱신 — 이 전제가 뒤집혔다.** `CANON_105`(續漢書 郡國志, CHGIS 와 출처가
다른 축)로 재판정해 20건을 손편집했다: COMMANDERY→KINGDOM 16 · COMMANDERY→COUNTY 4
(新成侯國·安眾侯國·征羌侯國·衛國 — 全部 CANON_105 에 없는 縣級 侯國이다).
이제 KINGDOM 이 16개 실재하므로 **아래 승격 회귀는 진공이 아니라 살아 있는 검사**다.
""" 
from __future__ import annotations

import json
import unittest
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
TILES = ROOT / "data/map/han-tiles.json"
# 이 파일의 nameCh 는 繁簡이 섞여 있다 — 실측: 侯国 18 · 屬國 6 · 属国 1 · 國 18 · 国 21.
# 繁體만 적으면 簡體 侯国 18건이 통째로 검사 밖으로 샌다(아래 RED 프로브로 확인).
# 두 표기를 다 넣는다 — 데이터를 한쪽으로 정규화하는 건 별개 결정이고, 검사는
# 지금 있는 데이터를 검사해야 한다.
DEMOTED_SUFFIXES = ("侯國", "侯国", "屬國", "属国", "郡")


class TileKindSanityTest(unittest.TestCase):
    def test_committed_tiles_kind_distribution_is_pinned(self) -> None:
        """**등급 분포를 단언으로 고정한다.** 1,138개 중 COUNTY 962 · COMMANDERY 120 ·
        KINGDOM 16 · EXTERNAL_PLACE 37 · PROVINCE 3.

        직전 값은 COUNTY 958 · COMMANDERY 140 · KINGDOM **0** 이었고, 그 0 때문에
        아래 승격 회귀가 **진공**이었다. CANON_105 재판정 20건(→KINGDOM 16, →COUNTY 4)
        으로 KINGDOM 이 실재하게 되면서 아래 검사가 살아났다. COUNTY 958→962 는
        侯國 4건 강등분이고, COMMANDERY 140→120 은 16+4 가 빠진 것이다 — 합은 보존된다.

        빨개지면 값을 맞추지 말고 무엇이 등급을 바꿨는지 먼저 봐라."""
        cities = json.loads(TILES.read_text())["cities"]
        kinds = Counter(c["kind"] for c in cities)
        self.assertEqual(
            {"COUNTY": 962, "COMMANDERY": 120, "KINGDOM": 16,
             "EXTERNAL_PLACE": 37, "PROVINCE": 3},
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
