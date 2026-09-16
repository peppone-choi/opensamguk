"""이동 그래프 구조 게이트 (§5).

che_이동은 CityConst 인접 그래프의 1홉이다. 클라이언트 거리 안내(SelectCityField)와
서버 nearCity(1) 제약이 같은 han-world-v3.json connections 를 보므로, 이 파일의
구조가 곧 미리보기=서버 일치의 바닥이다. 개수를 고정하지 않고 구조만 못박는다.

- 모든 간선은 양방향이다(편도는 의도치 않은 지름길·막다른 길이다).
- 자기 자신으로의 간선은 없다.
- 존재하지 않는 도시를 가리키는 간선은 없다.
- 고립(차수 0) 도시가 없고 그래프는 단일 연결 요소다 — 예외 없이 0곳이다.
"""

import json
import unittest
from collections import deque
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
WORLD = ROOT / "infra" / "src" / "main" / "resources" / "map" / "han-world-v3.json"

# 2026-09-15 에는 305 徐县(下邳國, 육지 9칸이 호수에 둘러싸임)·548 鄮县(會稽郡, 육지
# 37칸이 바다에 둘러싸임)을 예외로 핀해 두었다. 판정이 끝나 예외는 없어졌다 — 육교를
# 지어낸 것이 아니라, 續漢書 郡國志가 실은 소속 郡의 治所와 직결한다(縣을 다스리는 곳이다).
# 규칙은 tools/scenario/build_han_world.py 의 「물에 갇힌 縣 보정」이고, 생성기 자체가
# 끊긴 그래프를 내지 않는다. 여기서는 산출물만 다시 잰다.


def load():
    world = json.loads(WORLD.read_text(encoding="utf-8"))
    return {c["id"]: c.get("connections", []) for c in world["cities"]}


class WorldConnectivityTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.adj = load()

    def test_every_edge_is_bidirectional(self):
        asymmetric = sorted(
            (a, b) for a, neighbours in self.adj.items() for b in neighbours
            if a not in self.adj.get(b, []))
        self.assertEqual([], asymmetric)

    def test_no_self_loops_and_no_dangling_targets(self):
        ids = set(self.adj)
        loops = sorted(a for a, neighbours in self.adj.items() if a in neighbours)
        dangling = sorted(
            (a, b) for a, neighbours in self.adj.items() for b in neighbours
            if b not in ids)
        self.assertEqual([], loops)
        self.assertEqual([], dangling)

    def test_no_isolated_cities_and_single_connected_component(self):
        isolated = sorted(a for a, neighbours in self.adj.items() if not neighbours)
        self.assertEqual([], isolated)
        start = next(a for a, neighbours in self.adj.items() if neighbours)
        seen = {start}
        queue = deque([start])
        while queue:
            node = queue.popleft()
            for neighbour in self.adj[node]:
                if neighbour not in seen:
                    seen.add(neighbour)
                    queue.append(neighbour)
        self.assertEqual(set(self.adj), seen)


if __name__ == "__main__":
    unittest.main()
