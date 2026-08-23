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

# 현재 커밋본 실측: 12 성분 · 최대 1131/1145(98.8%) · 고립 10. 재생성본(1230 간선)은
# 356 성분 · 최대 660(57.6%) · 고립 321 로 셋 다 큰 폭으로 위반한다. SEA_LINKS 이식
# 등 정당한 개선으로 고립이 14→9 로 줄어드는 정도는 넉넉히 통과하는 여유를 남긴다.
MIN_MAIN_COMPONENT_RATIO = 0.98
MAX_ISOLATED = 15
MAX_COMPONENTS = 20


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
        n = len(data["cities"])
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


if __name__ == "__main__":
    unittest.main()
