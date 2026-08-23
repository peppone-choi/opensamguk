"""administrative-units.json(정본 郡/國 카탈로그) 과 han-tiles.json(서빙 타일) 이
같은 郡/國 분류를 말하는지 대조한다.

han-tiles.json 재생성은 CHGIS 파생 입력(han-places.json, terrain-grid.json)이
있어야 하고 그건 ADR-LITE-039 로 gitignore 돼 있어 CI 에서 재생성할 수 없다.
그래서 입력 해시 드리프트 가드는 성립하지 않는다 — 두 파일은 파이프라인
의존관계가 아니라 같은 郡/國 원천을 각자 독립적으로 재분류한 결과다
(administrative-units.json 은 junguozhi_contract.py 의 group_type(), han-tiles.json
은 build_han_places.py 의 TIER 딕셔너리). 이 테스트는 둘 다 이미 커밋된 파일이라
gitignored 입력도 재생성도 없이, 두 산출물이 실제로 같은 답을 말하는지만 본다.
"""
from __future__ import annotations

import json
import unittest
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
CATALOG = ROOT / "data/curated/han/administrative-units.json"
TILES = ROOT / "data/map/han-tiles.json"


class TileCatalogDriftTest(unittest.TestCase):
    def test_commandery_kingdom_counts_match(self) -> None:
        catalog = json.loads(CATALOG.read_text())
        tiles = json.loads(TILES.read_text())

        catalog_counts = Counter(g["groupType"] for g in catalog["groups"])
        tile_counts = tiles["_meta"]["counts"]

        expected = {"COMMANDERY": catalog_counts["COMMANDERY"], "KINGDOM": catalog_counts["KINGDOM"]}
        actual = {"COMMANDERY": tile_counts.get("COMMANDERY", 0), "KINGDOM": tile_counts.get("KINGDOM", 0)}

        if expected != actual:
            kingdom_names = sorted(g["canonicalGroup"] for g in catalog["groups"] if g["groupType"] == "KINGDOM")
            jun_names = {j["nameCh"] for j in tiles["juns"]}
            present_but_misclassified = sorted(n for n in kingdom_names if n in jun_names)
            self.fail(
                "han-tiles.json 이 stale 하다 — administrative-units.json(정본) 은 "
                f"COMMANDERY {expected['COMMANDERY']}/KINGDOM {expected['KINGDOM']} 인데 "
                f"han-tiles.json._meta.counts 는 COMMANDERY {actual['COMMANDERY']}/KINGDOM {actual['KINGDOM']} 다. "
                "재생성이 필요하다(CHGIS gitignored 입력 필요, 별도 티켓). "
                "카탈로그를 산출물에 맞춰 고치지 마라. "
                f"지도에 존재하지만 KINGDOM 으로 분류되지 않은 정본 KINGDOM 群 "
                f"({len(present_but_misclassified)}/{len(kingdom_names)}): {present_but_misclassified}"
            )


if __name__ == "__main__":
    unittest.main()
