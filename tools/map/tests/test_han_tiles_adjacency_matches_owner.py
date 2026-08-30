"""han-tiles.json 의 `adjacency.county` 를 **다른 축**에서 재유도해 대조한다.

`adjacency.county` 는 `owner`(城 소유 격자, 런렝스) 에서 완전히 결정된다 —
4-이웃으로 서로 다른 소유자가 맞닿은 격자변을 세고, 양끝이 바다(-1)가 아닌
모든 쌍을 남긴다. 공유 변이 하나라도 있으면 두 프로빈스는 실제로 맞닿아 있고,
육상 이동은 별도의 육로가 아니라 이 접경 그래프를 그대로 사용한다.

**왜 필요한가.** #548 의 郡 phantom 노드 병합에서 삭제된 城의 간선·격자 소유가
「흡수하는 郡의 *jun 인덱스*」로 재지정됐다 — city 인덱스여야 하는 자리다.
두 배열의 인덱스 공간이 우연히 겹쳐서 조용히 통과했다:

    北平郡 → 右北平郡(juns[93])  ⇒ cities[93] = 乐平郡   (186 격자 · 간선 5)
    西陵郡 → 江夏郡(juns[43])    ⇒ cities[43] = 东牟县   ( 63 격자 · 간선 3)
    冯翊郡 → 左馮翊(juns[5])     ⇒ cities[5]  = 上党郡   (  3 격자 · 간선 1)

`东牟县`(산둥반도)–`下雉县`(장강 중류) 179.3 단위 간선이 그 산물이다. 城 수
게이트(780→774)도, `--check` 드리프트 게이트도, 인접 개수도 이걸 못 잡았다 —
전부 같은 손상된 산출물을 보고 있었기 때문이다. 이 검사는 `owner` 라는 다른
축에서 답을 다시 만들어 대조하므로 같은 버그를 공유하지 않는다.

이 검사는 `adjacency` 를 `owner` 로 되돌려 계산할 뿐이고 `owner` 자체가 옳은지는
말하지 않는다. `owner` 손상은 origin/main 대비 격자 델타로 따로 봐야 한다.
"""
import json
import unittest
from collections import Counter
from pathlib import Path

TILES = Path(__file__).resolve().parents[3] / "data" / "map" / "han-tiles.json"

MIN_SHARED_CELLS = 1


def derive_county_adjacency(tiles: dict) -> dict[frozenset, int]:
    cols, rows = tiles["_meta"]["cols"], tiles["_meta"]["rows"]
    owner: list[int] = []
    for city_index, run in tiles["owner"]:
        owner.extend([city_index] * run)
    assert len(owner) == cols * rows, f"owner 격자 길이 {len(owner)} != {cols}x{rows}"
    shared: Counter = Counter()
    for r in range(rows):
        base = r * cols
        for c in range(cols):
            a = owner[base + c]
            if c + 1 < cols:
                b = owner[base + c + 1]
                if a != b:
                    shared[frozenset((a, b))] += 1
            if r + 1 < rows:
                b = owner[base + cols + c]
                if a != b:
                    shared[frozenset((a, b))] += 1
    return {e: n for e, n in shared.items()
            if n >= MIN_SHARED_CELLS and all(i >= 0 for i in e)}


class TestCountyAdjacencyIsDerivableFromOwnerGrid(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.tiles = json.loads(TILES.read_text())
        cls.names = [c["nameCh"] for c in cls.tiles["provinceRecords"]]
        cls.committed = {frozenset((e["a"], e["b"])): e["cells"]
                         for e in cls.tiles["adjacency"]["county"]}
        cls.derived = derive_county_adjacency(cls.tiles)

    def label(self, edge):
        return " – ".join(sorted(self.names[i] for i in edge))

    def test_no_self_loops(self):
        loops = [e for e in self.tiles["adjacency"]["county"] if e["a"] == e["b"]]
        self.assertEqual([], loops, "縣 인접에 자기 자신 간선이 있다")

    def test_edge_set_matches_owner_grid(self):
        extra = sorted(self.label(e) for e in set(self.committed) - set(self.derived))
        missing = sorted(self.label(e) for e in set(self.derived) - set(self.committed))
        self.assertEqual(
            ([], []), (extra, missing),
            "adjacency.county 가 owner 격자에서 유도되지 않는다 — "
            "커밋본에만 있는 간선(extra) / 격자에만 있는 간선(missing)",
        )

    def test_shared_cell_counts_match_owner_grid(self):
        wrong = {self.label(e): (self.committed[e], self.derived[e])
                 for e in set(self.committed) & set(self.derived)
                 if self.committed[e] != self.derived[e]}
        self.assertEqual({}, wrong,
                         "cells 값이 owner 격자의 실제 공유 변 수와 다르다 (커밋값, 격자값)")


if __name__ == "__main__":
    unittest.main()
