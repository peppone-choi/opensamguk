# 이 파일은 커밋된 data/map/han-tiles.json 만 입력으로 쓴다 — junguozhi.json 같은
# gitignored 산출물이 필요 없어 CI 에서 조건 없이 돈다(#534 처럼 skip 으로 죽지 않는다).
#
# 배경(#536): build_han_places.py/build_terrain_grid.py 를 지금 워크트리에서 그대로
# 재실행하면 인접 縣 그래프가 2662 간선(연결 98.8%)에서 1230 간선(연결 57.6%)으로
# 반토막난다. 세 가설(kind 필드 분리 #524, junguozhi.json 소스 갱신, #507 스크립트
# 변경)을 전부 실측으로 기각했고 원인은 UNKNOWN 이다 — 즉 커밋된 han-tiles.json 을
# 만든 정확한 조합이 저장소 어디에도 재현 가능한 형태로 없다. 최선의 방어는 "맞는
# 산출물로 재생성하기"가 아니라 "틀린 산출물이 몰래 커밋되는 걸 막기"다.
import json
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
TILES = ROOT / "data/map/han-tiles.json"

# 프로빈스는 공유 변이 하나라도 있으면 인접한다. 이하 문턱은 손상 방지용
# 보수적 최저선이며 정확한 간선 집합은 test_han_tiles_adjacency_matches_owner.py 가
# owner 격자에서 재유도해 검사한다. 기존 커밋본 실측: 城 1138 · 縣 간선 2649 ·
# 12 성분 · 최대 1124/1138(98.8%) · 고립 10. 郡 172 · 郡 간선 417.
# (#548 이 郡治 phantom 노드를 병합해 1145→1138 로 줄었다.) 재생성본(1230 간선)은
# 356 성분 · 최대 660(57.6%) · 고립 321 로 셋 다 큰 폭으로 위반한다.
#
# 진짜 병목은 MAX_ISOLATED 다. 고립이 느는 방향으로는 여유가 딱 4개뿐이다 — 14개
# 까지는 통과하고 15개째(추가 5개)에서 죽는데, 그 시점 main_ratio 는 98.34%로 아직
# 여유가 있다(0.98 문턱을 안 건드린다). 그러니 SEA_LINKS(#529) 같은 정당한 개선이
# 고립을 줄이는 방향이면 넉넉히 통과하지만, 반대로 고립을 늘리는 변경은 4개 넘게
# 겹치면 이 게이트가 먼저 죽는다 — 숫자는 그때그때 실측해라, 여기 옮겨 적지 마라.
MIN_MAIN_COMPONENT_RATIO = 0.85
MAX_ISOLATED = 120
MAX_COMPONENTS = 130

# adjacency["commandery"] 는 郡治(juns, n=174) 사이 간선이다. critic-conn-536 이
# commandery=[] 로 비워도 위 縣 검사는 통과한다는 사각지대를 짚었다. 縣 쪽과 같은
# union-find 연결성 문턱을 여기 그대로 쓸 수는 없다 — 실측하면 커밋본도 이미
# 8 성분·최대 167/174(96.0%)·고립 7 이고, 재생성본(간선 366)은 9 성분·최대
# 166(95.4%)·고립 8 이라 **차이가 1 뿐이다**(縣 쪽은 10 대 321, 두 자리가 다르다).
# 이 문턱으로는 재생성을 못 잡고, 郡 하나가 도서·소국 편입으로 갈리는 정상적인
# 변경에도 오탐한다 — 그래서 연결성 문턱은 넣지 않는다(이게 팀리드가 미리 승인한
# "의미 있는 임계값을 못 잡으면 빼라" 케이스다).
# 대신 critic 이 실제로 보인 반례(필드를 통째로 비워도 안 걸린다)만 확실히 잡는다:
# 커밋본 417·재생성본 366 둘 다 한참 위인, 정상 그래프라면 절대 못 미칠 바닥값.
MIN_COMMANDERY_EDGES = 150


def _components(n, edges):
    parent = list(range(n))

    def find(x):
        while parent[x] != x:
            parent[x] = parent[parent[x]]
            x = parent[x]
        return x

    for e in edges:
        ra, rb = find(e["a"]), find(e["b"])
        if ra != rb:
            parent[ra] = rb
    sizes = {}
    for i in range(n):
        r = find(i)
        sizes[r] = sizes.get(r, 0) + 1
    return sorted(sizes.values(), reverse=True)


class HanTilesAdjacencyConnectivityTest(unittest.TestCase):
    """data/map/han-tiles.json 의 縣 인접 그래프가 파편화되지 않았는지 확인한다.

    이 테스트가 죽으면 이 파일이 재생성·재커밋됐다는 뜻이다 — 원인 불명의 파편화
    버그(#536)가 아직 안 고쳐졌으니 그대로 되돌려라. 다시 만들지 마라, 되돌려라.
    """

    def test_county_adjacency_stays_connected(self):
        data = json.loads(TILES.read_text(encoding="utf-8"))
        n = len(data.get("provinceRecords", data["cities"]))
        sizes = _components(n, data["adjacency"]["county"])
        main_ratio = sizes[0] / n
        isolated = sum(1 for s in sizes if s == 1)

        detail = (
            f"연결 성분 {len(sizes)}개 · 최대 성분 {sizes[0]}/{n}({main_ratio:.1%}) · "
            f"고립 노드 {isolated}개. "
            "data/map/han-tiles.json 이 build_han_places.py/build_terrain_grid.py 로 "
            "재생성됐을 가능성이 높다 — 이 워크트리에서 재생성하면 인접 그래프가 "
            "2662→1230 간선으로 반토막나며 이 불변식을 깬다(#536, 원인 UNKNOWN, "
            "세 가설 kind 분리/junguozhi 갱신/#507 스크립트 변경 전부 실측 기각). "
            "이 파일은 재생성 금지다 — git checkout 으로 커밋본을 되돌려라."
        )
        self.assertGreaterEqual(main_ratio, MIN_MAIN_COMPONENT_RATIO, detail)
        self.assertLess(isolated, MAX_ISOLATED, detail)
        self.assertLess(len(sizes), MAX_COMPONENTS, detail)

    def test_commandery_adjacency_is_populated(self):
        """郡 인접이 통째로 비어 있지 않은지만 본다 — 연결성 문턱은 위 주석 참고."""
        data = json.loads(TILES.read_text(encoding="utf-8"))
        edges = data["adjacency"]["commandery"]
        self.assertGreaterEqual(
            len(edges), MIN_COMMANDERY_EDGES,
            f"郡 인접 간선 {len(edges)}개. adjacency.commandery 가 비었거나 크게 "
            "부족하다 — data/map/han-tiles.json 재생성/손상 의심(#536). 되돌려라."
        )


if __name__ == "__main__":
    unittest.main()
