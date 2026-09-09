#!/usr/bin/env python3
"""아이소메트릭 지도용 저폴리 3D 에셋을 굽는다 — 지형 9종 · 城 11단 · 유닛 6종.

**왜 절차 생성인가.** 이 에셋들은 게임 데이터에서 종류가 정해진다(지형 범례 9종, 도시 등급,
병종 armType 6종). 손으로 만든 모델은 데이터가 늘 때마다 따로 만들어야 하고 저장소가 검증할
수 없다. 코드로 구우면 결정론적이라 `--check` 로 드리프트를 막고, 팔레트·비율을 한 곳에서
바꾸면 전부가 따라온다. 브랜드 에셋(`build_brand_assets.py`)과 같은 규약이다.

**규격.** 타일은 XZ 평면의 1×1 정사각형이고 Y 가 위다. 렌더러가 아이소 카메라로 보면 2:1
다이아몬드가 된다 — 다이아몬드 모양을 메시에 굽지 않는 이유는, 그러면 카메라 각도를 바꿀 때
모델을 다시 구워야 하기 때문이다. 높이는 타일 한 변을 1.0 으로 둔 상대값이다.

**지형 타일에는 테두리가 없다.** 롤러코스터 타이쿤이 그렇듯 같은 높이의 인접 타일은 이음매 없이
이어져야 한다. 그래서 지형 타일은 **윗면만** 굽는다 — 닫힌 상자로 구우면 사방 측면이 남아 깔았을 때
격자마다 어두운 선이 생긴다(첫 판이 그랬다). 흙벽은 높이가 바뀌는 곳·물가·맵 가장자리에만
필요하므로 `terrain/skirt.gltf` 한 장으로 따로 내보내고 렌더러가 거기에만 세운다.
지형의 기복(산봉우리·강둑)은 윗면 위에 얹는 **별도 프롭**이라 이음매를 만들지 않는다.

**타일 단위.** 지형 래스터 768×669 를 4×4 로 묶어 192×167 타일이다(사용자 결정 2026-09-08).
그래야 한 칸이 지도상 약 12km 로 縣 사이 질감이 살고, 3만 2천 인스턴스라 브라우저가 감당한다.

사용:
    python3 tools/assets/build_iso3d_assets.py
    python3 tools/assets/build_iso3d_assets.py --check
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from gltf import Mesh, write_gltf  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]
#: 산출물은 두 프런트 양쪽에 같은 바이트로 깔린다. 게임의 public 에셋은 절대 경로로 참조되고
#: 실제로는 게이트웨이가 서빙하므로, 한쪽만 갱신하면 프로덕션에서만 404 가 난다.
OUT_DIRS = (
    ROOT / "web/game/public/models/iso3d",
    ROOT / "web/gateway/public/models/iso3d",
)
OUT_DIR = OUT_DIRS[0]
TILES_PATH = ROOT / "data/map/han-tiles.json"

#: 래스터 몇 칸을 한 타일로 묶는가. 사용자 결정(2026-09-08) — 4×4.
RASTER_GROUP = 4

# ── 팔레트 ────────────────────────────────────────────────────────────────────
# Concept A(ADR-LITE-049)의 흙빛 팔레트를 3D 로 옮긴 것이다. 채도를 낮게 두어 국가색 깃발과
# 부대 색이 지도 위에서 튀도록 남겨 둔다 — 지형이 화려하면 전황이 안 읽힌다.
PALETTE: dict[str, tuple[float, float, float]] = {
    "grass": (0.38, 0.42, 0.24),
    "grass_dry": (0.52, 0.50, 0.30),
    "soil": (0.40, 0.33, 0.22),
    "rock": (0.44, 0.43, 0.41),
    "rock_dark": (0.31, 0.30, 0.29),
    "snow": (0.86, 0.87, 0.85),
    "water": (0.20, 0.36, 0.42),
    "water_deep": (0.13, 0.26, 0.33),
    "sand": (0.72, 0.63, 0.44),
    "plateau": (0.50, 0.44, 0.31),
    "basin": (0.34, 0.38, 0.26),
    "hill": (0.44, 0.45, 0.27),
    "wood": (0.35, 0.25, 0.16),
    "wall": (0.55, 0.51, 0.44),
    "roof": (0.30, 0.20, 0.16),
    "banner": (0.72, 0.24, 0.20),
    "iron": (0.46, 0.47, 0.50),
    "leather": (0.42, 0.30, 0.20),
    "horse": (0.36, 0.26, 0.19),
    # 城 11단이 쓰는 재료. 성벽·기와만으로는 수채·영채·관성·이민족 야영이 구분되지 않는다.
    "stone": (0.48, 0.46, 0.42),      # 關城 의 다듬돌
    "canvas": (0.74, 0.71, 0.62),     # 軍幕 의 천
    "hide": (0.60, 0.55, 0.46),       # 이민족 천막의 모전
    "hide_dark": (0.47, 0.42, 0.35),  # 그 천막의 지붕
    "plank": (0.47, 0.39, 0.29),      # 水寨 의 부교 널
    "foliage": (0.26, 0.33, 0.19),    # 성내 수목
    "ember": (0.74, 0.44, 0.22),      # 화톳불
}


def _terrain_legend() -> dict[int, str]:
    legend = json.loads(TILES_PATH.read_text(encoding="utf-8"))["_meta"]["terrainLegend"]
    return {int(k): v for k, v in legend.items()}


def _top(mesh: Mesh, color: tuple[float, float, float], height: float = 0.0) -> None:
    """타일 윗면 1×1 한 장. **측면을 만들지 않는다** — 그래야 인접 타일과 이음매가 없다.

    높이 차는 렌더러가 타일 노드의 Y 를 옮겨 표현하고, 그 단차에만 `skirt` 를 세운다.
    여기서 상자를 구우면 평지끼리도 어두운 테두리가 생긴다.
    """
    mesh.add_polygon(
        [(-0.5, height, -0.5), (0.5, height, -0.5), (0.5, height, 0.5), (-0.5, height, 0.5)],
        color,
    )


def build_terrain(kind: str) -> Mesh:
    """지형 종류 하나의 타일. 윗면 한 장 + 그 위에 얹는 기복만 — 측면은 만들지 않는다."""
    mesh = Mesh()
    if kind == "SEA":
        _top(mesh, PALETTE["water_deep"])
    elif kind == "PLAIN":
        _top(mesh, PALETTE["grass"])
        # 풀 무더기. 윗면 위에 얹히므로 이음매를 만들지 않는다. 위치는 고정값이고
        # 변종은 런타임이 타일을 90도 단위로 돌려서 낸다.
        for x, z in ((-0.24, -0.18), (0.20, 0.26), (0.06, -0.30)):
            mesh.add_box((x, 0.025, z), (0.10, 0.05, 0.10), PALETTE["grass_dry"])
    elif kind == "MOUNTAIN":
        _top(mesh, PALETTE["rock_dark"])
        mesh.add_pyramid((0.0, 0.0, 0.0), 0.94, 0.72, PALETTE["rock"])
        mesh.add_pyramid((0.0, 0.54, 0.0), 0.26, 0.20, PALETTE["snow"])
    elif kind == "HILL":
        _top(mesh, PALETTE["hill"])
        mesh.add_pyramid((0.0, 0.0, 0.0), 0.94, 0.26, PALETTE["grass_dry"])
    elif kind == "RIVER":
        # 물길이 타일을 가로지른다. 둑은 윗면 위에 얹는 낮은 둔덕이라 이웃 평지와 이어진다.
        _top(mesh, PALETTE["water"], -0.04)
        for z in (-0.40, 0.40):
            mesh.add_box((0.0, 0.02, z), (1.0, 0.06, 0.20), PALETTE["soil"])
    elif kind == "LAKE":
        _top(mesh, PALETTE["water"], -0.05)
    elif kind == "DESERT":
        _top(mesh, PALETTE["sand"])
        mesh.add_pyramid((-0.14, 0.0, 0.10), 0.54, 0.12, PALETTE["sand"])
    elif kind == "PLATEAU":
        # 고원은 높이 자체가 성격이다. 단차는 렌더러가 skirt 로 세운다.
        _top(mesh, PALETTE["plateau"])
    elif kind == "BASIN":
        _top(mesh, PALETTE["basin"])
    else:
        raise ValueError(f"unknown terrain kind: {kind}")
    return mesh


def build_skirt() -> Mesh:
    """단차·물가·맵 가장자리에 세우는 흙벽 한 장.

    렌더러가 높이 차가 있는 변에만 세운다. 타일마다 굽지 않는 이유가 이것이다 —
    타일이 자기 측면을 들고 있으면 평지끼리도 테두리가 생긴다.
    """
    mesh = Mesh()
    mesh.add_polygon(
        [(-0.5, 0.0, 0.0), (0.5, 0.0, 0.0), (0.5, 1.0, 0.0), (-0.5, 1.0, 0.0)],
        PALETTE["soil"],
    )
    return mesh


#: 城 등급 → 건물 모델. **1:1, 11단이다.** 정본 대응표는 iso2d manifest 의 `cityLevelTiers`
#: 이고 여기 표는 그 사본이다.
#:
#: 예전 표는 등급 숫자를 크기 순서로 읽어 네 단으로 묶었다. 그게 틀렸다 — 9 京·10 영현·
#: 11 장현은 한나라 세계용으로 **뒤에 덧붙인** 값이고 10·11 은 縣, 즉 가장 작은 단위다.
#: 그래서 `capital` 이 9..11 을 먹으면 774 성 중 604 개(78%)가 도성으로 그려졌다.
#: 1 수(水)·2 진(鎭)·3 관(關)·4 이(夷)는 애초에 크기가 아니라 **종류**다 — 강·섬의 水寨,
#: 전장의 營寨, 길목의 關城, 이민족 야영이며 엔진도 그렇게 본다(`WarUnitCity.kt:50-51` 이
#: 1·3 등급에만 훈련 보너스를 준다). 크기 사다리에 욱여넣지 않고 형태를 따로 만든다.
#:
#: 축소 화면에서 11단이 구분되는가 — 그것이 예전에 4단으로 묶은 이유였다. 색이 아니라
#: **실루엣**으로 벌린다: 망루 수(0·1·2·3·4), 기단 유무, 문루 층수(민문·지붕·門樓),
#: 해자, 雙闕, 깃대. 성벽 반폭은 0.30→0.45 로만 벌어지므로 크기 하나에 기대지 않는다.
CITY_TIERS: dict[str, int] = {
    "water": 1,               # 水寨 — 물 위 부교와 매어 둔 배
    "garrison": 2,            # 營寨 — 목책과 軍幕
    "pass": 3,                # 關城 — 길을 가로지른 성벽과 관문루
    "tribal": 4,              # 이민족 야영 — 담장 없는 모전 천막
    "county-small": 11,       # 장현 — 가장 작은 縣, 망루 없음
    "county": 10,             # 영현 — 縣, 망루 하나
    "commandery": 5,          # 소 — 郡治, 망루 둘 + 기단
    "commandery-mid": 6,      # 중 — 망루 셋
    "commandery-major": 7,    # 대 — 망루 넷 + 門樓
    "commandery-grand": 8,    # 특 — 해자 + 2층 전각
    "capital": 9,             # 경 — 雙闕 + 궁성 단 + 깃대
}

#: 타일 한 변은 1.0 이고 모델은 그 안에 서야 한다. 城 이 자기 타일 밖으로 삐져나오면 앞 타일
#: 위에 겹쳐 그려진다 — 2D 에서 아이콘 넷이 제각각으로 보이던 원인이 정확히 이것이었다.
FOOTPRINT_HALF = 0.5


def _footprint(mesh: Mesh) -> float:
    """모델이 타일 밖으로 나간 거리. 0 이하면 자기 칸 안에 선 것이다."""
    return max((max(abs(x), abs(z)) for x, _, z in mesh.positions), default=0.0) - FOOTPRINT_HALF


def _ring(mesh: Mesh, half: float, thickness: float, height: float, base: float,
          gap: float, color: tuple[float, float, float]) -> None:
    """성벽 네 면. 남쪽(+Z)만 성문 폭 `gap` 만큼 벌려 둔다."""
    outer = half + thickness / 2
    mesh.add_box((0.0, base + height / 2, -half), (outer * 2, height, thickness), color)
    for x in (-half, half):
        mesh.add_box((x, base + height / 2, 0.0), (thickness, height, half * 2 - thickness), color)
    run = (outer * 2 - gap) / 2
    for sign in (-1.0, 1.0):
        mesh.add_box((sign * (gap + run) / 2, base + height / 2, half), (run, height, thickness), color)


def _crenels(mesh: Mesh, half: float, thickness: float, wall_h: float, base: float,
             gap: float, count: int = 5) -> None:
    """城堞. 성벽 윗변에 이 한 줄이 있고 없고가 縣城과 郡治를 가른다 — 축소 화면에서 색보다
    윤곽이 먼저 읽히기 때문이다."""
    mh, mw, y = 0.035, 0.055, base + wall_h + 0.0175
    for i in range(count):
        t = -half + 2 * half * (i + 0.5) / count
        for x, z, sx, sz in ((t, -half, mw, thickness), (t, half, mw, thickness),
                             (-half, t, thickness, mw), (half, t, thickness, mw)):
            if z == half and abs(t) < gap / 2 + 0.03:
                continue  # 성문 위는 문루가 맡는다
            mesh.add_box((x, y, z), (sx, mh, sz), PALETTE["stone"])


def _tower(mesh: Mesh, x: float, z: float, size: float, height: float, base: float,
           roof: bool) -> None:
    mesh.add_box((x, base + height / 2, z), (size, height, size), PALETTE["stone"])
    if roof:
        mesh.add_pyramid((x, base + height, z), size * 1.35, size * 0.85, PALETTE["roof"])


def _gate(mesh: Mesh, half: float, thickness: float, wall_h: float, gap: float, base: float,
          storeys: int) -> None:
    """민문(0) · 지붕만 얹은 문(1) · 門樓(2)."""
    if storeys == 0:
        return
    z = half
    mesh.add_box((0.0, base + wall_h + 0.015, z), (gap + 0.08, 0.03, thickness + 0.03), PALETTE["wall"])
    top = base + wall_h + 0.03
    if storeys >= 2:
        loft = wall_h * 0.5
        mesh.add_box((0.0, top + loft / 2, z), (gap + 0.04, loft, thickness + 0.01), PALETTE["wall"])
        top += loft
    # 지붕은 상자 두 겹이다. 정사각 피라미드를 쓰면 폭에 맞춘 밑변이 깊이로도 퍼져 성벽
    # 바깥으로 나간다 — 실제로 capital 이 0.100 나가 빌더 검사에 걸렸다.
    mesh.add_box((0.0, top + 0.014, z), (gap + 0.14, 0.028, thickness + 0.05), PALETTE["roof"])
    mesh.add_box((0.0, top + 0.042, z), (gap + 0.06, 0.028, thickness + 0.01), PALETTE["roof"])


#: 성내 전각 자리. 중앙은 본전이 쓰므로 비워 두고, 남쪽 성문 앞 축선(x≈0, z>0)도 비운다.
_HALL_SPOTS = (
    (-0.62, -0.62), (0.62, -0.62), (-0.62, 0.05), (0.62, 0.05),
    (0.0, -0.62), (-0.62, 0.66), (0.62, 0.66),
)
_TREE_SPOTS = ((-0.30, 0.62), (0.30, 0.62), (-0.30, -0.20), (0.30, -0.20))


def _halls(mesh: Mesh, inner: float, count: int, base: float) -> None:
    span_x, span_z = inner - 0.075, inner - 0.065
    for nx, nz in _HALL_SPOTS[:count]:
        x, z = nx * span_x, nz * span_z
        mesh.add_box((x, base + 0.045, z), (0.13, 0.09, 0.10), PALETTE["wall"])
        mesh.add_pyramid((x, base + 0.09, z), 0.17, 0.055, PALETTE["roof"])


def _trees(mesh: Mesh, inner: float, count: int, base: float) -> None:
    for nx, nz in _TREE_SPOTS[:count]:
        x, z = nx * (inner - 0.05), nz * (inner - 0.05)
        mesh.add_box((x, base + 0.025, z), (0.02, 0.05, 0.02), PALETTE["wood"])
        mesh.add_pyramid((x, base + 0.05, z), 0.11, 0.09, PALETTE["foliage"])


def _walled(half: float, thickness: float, wall_h: float, towers: int, gap: float,
            gate_storeys: int, halls: int, trees: int, plinth: float = 0.0,
            storeys: int = 1, moat: bool = False, que: bool = False,
            flag: bool = False, crenels: bool = True) -> Mesh:
    """담장을 두른 城 한 채. 7 단(장현·영현·소·중·대·특·경)이 이 한 함수의 인자 차이다."""
    mesh = Mesh()
    outer = half + thickness / 2
    if plinth > 0.0:
        mesh.add_box((0.0, plinth / 2, 0.0), (outer * 2 + 0.025, plinth, outer * 2 + 0.025),
                     PALETTE["stone"])
    if moat:
        # 해자는 성벽 **밖**에 두른 물띠다. 타일 반폭 0.5 를 넘지 않도록 성벽을 한 치 줄였다.
        inner_edge, outer_edge = outer + 0.012, 0.495
        band = outer_edge - inner_edge
        mid = (inner_edge + outer_edge) / 2
        for x, z, sx, sz in (
            (0.0, -mid, outer_edge * 2, band), (0.0, mid, outer_edge * 2, band),
            (-mid, 0.0, band, inner_edge * 2), (mid, 0.0, band, inner_edge * 2),
        ):
            mesh.add_box((x, -0.03, z), (sx, 0.06, sz), PALETTE["water_deep"])

    _ring(mesh, half, thickness, wall_h, plinth, gap, PALETTE["wall"])
    if crenels:
        _crenels(mesh, half, thickness, wall_h, plinth, gap)
    corners = [(half, -half), (-half, half), (-half, -half), (half, half)][:towers]
    tower_size = 0.12
    for cx, cz in corners:
        x = (outer - tower_size / 2) * (1.0 if cx > 0 else -1.0)
        z = (outer - tower_size / 2) * (1.0 if cz > 0 else -1.0)
        # 성벽의 1.8 배. 1.35 배로 뒀더니 아이소 각도에서 망루 꼭지만 성벽 위로 나와
        # 0·1·2·3·4 를 셀 수가 없었다 — 망루 수가 이 사다리의 주 신호다.
        _tower(mesh, x, z, tower_size, wall_h * 1.8, plinth, roof=True)
    _gate(mesh, half, thickness, wall_h, gap, plinth, gate_storeys)

    inner = half - thickness / 2 - 0.015
    _halls(mesh, inner, halls, plinth)
    _trees(mesh, inner, trees, plinth)

    if que:
        # 雙闕 — 성문 양옆에 세우는 한 쌍의 望樓. 도성에만 선다.
        for sign in (-1.0, 1.0):
            x = sign * (gap / 2 + 0.055)
            mesh.add_box((x, plinth + wall_h * 0.85, half - 0.01), (0.07, wall_h * 1.7, 0.07),
                         PALETTE["wall"])
            mesh.add_pyramid((x, plinth + wall_h * 1.7, half - 0.01), 0.12, 0.07, PALETTE["roof"])

    # 본전. 2 층 城 은 한 겹 더 올려 실루엣을 키운다.
    # 높이는 산(0.92)을 넘지 않게 잡는다. 실루엣이 커야 하는 것은 맞지만 城 이 뒤 타일을
    # 가리기 시작하면 지형이 안 읽힌다 — 사다리는 층수·雙闕·해자가 이미 만들고 있다.
    keep_h = wall_h * (1.20 if storeys >= 2 else 1.15)
    mesh.add_box((0.0, plinth + keep_h / 2, -0.04), (0.24, keep_h, 0.20), PALETTE["wall"])
    mesh.add_pyramid((0.0, plinth + keep_h, -0.04), 0.32, 0.11, PALETTE["roof"])
    top = plinth + keep_h + 0.11
    if storeys >= 2:
        mesh.add_box((0.0, top + keep_h * 0.225, -0.04), (0.17, keep_h * 0.45, 0.14), PALETTE["wall"])
        mesh.add_pyramid((0.0, top + keep_h * 0.45, -0.04), 0.24, 0.07, PALETTE["roof"])
        top += keep_h * 0.45 + 0.07
    if flag:
        # 깃대. 국가색은 런타임이 이 재질에 곱한다.
        mesh.add_box((0.0, top + 0.10, -0.04), (0.02, 0.20, 0.02), PALETTE["wood"])
        mesh.add_box((0.08, top + 0.15, -0.04), (0.16, 0.09, 0.01), PALETTE["banner"])
    return mesh


def _water() -> Mesh:
    """1 수(水) — 강·섬의 水寨. 물 위에 띄운 부교와 매어 둔 배가 성벽 대신 성격을 말한다."""
    mesh = Mesh()
    # 물을 이 모델이 직접 깐다. 城 은 뭍 타일에만 서므로(#689) 밑 타일은 평지다 —
    # 여울을 그리지 않으면 부교가 맨땅에 놓인 널판으로 읽힌다.
    mesh.add_box((0.0, -0.02, 0.0), (0.98, 0.04, 0.98), PALETTE["water"])
    deck, y = 0.30, 0.06
    for x in (-deck + 0.05, deck - 0.05):
        for z in (-deck + 0.05, deck - 0.05):
            mesh.add_box((x, y / 2, z), (0.04, y, 0.04), PALETTE["wood"])
    mesh.add_box((0.0, y + 0.015, 0.0), (deck * 2, 0.03, deck * 2), PALETTE["plank"])
    base = y + 0.03
    mesh.add_box((-0.08, base + 0.07, -0.06), (0.20, 0.14, 0.16), PALETTE["hide"])
    mesh.add_pyramid((-0.08, base + 0.14, -0.06), 0.26, 0.09, PALETTE["roof"])
    # 망대. 물에서는 높이가 곧 시야다.
    mesh.add_box((0.16, base + 0.11, 0.14), (0.07, 0.22, 0.07), PALETTE["wood"])
    mesh.add_box((0.16, base + 0.24, 0.14), (0.13, 0.03, 0.13), PALETTE["plank"])
    # 매어 둔 배 둘. 부교 밖 물 위라 타일 가장자리에 붙는다.
    mesh.add_box((0.40, 0.03, -0.04), (0.13, 0.05, 0.34), PALETTE["wood"])
    mesh.add_box((0.40, 0.08, -0.04), (0.03, 0.06, 0.10), PALETTE["canvas"])
    mesh.add_box((-0.05, 0.03, 0.40), (0.32, 0.05, 0.12), PALETTE["wood"])
    return mesh


def _garrison() -> Mesh:
    """2 진(鎭) — 전장의 營寨. 목책과 軍幕, 화톳불."""
    mesh = Mesh()
    half, post = 0.40, 0.035
    steps = 9
    for i in range(steps):
        t = -half + (2 * half) * i / (steps - 1)
        for x, z in ((t, -half), (t, half), (-half, t), (half, t)):
            if abs(t) < 0.09 and z == half:
                continue  # 영문(營門)
            mesh.add_box((x, 0.07, z), (post, 0.14, post), PALETTE["wood"])
    for x, z, dx, dz in ((-0.15, -0.14, 0.20, 0.13), (0.15, -0.09, 0.18, 0.12),
                         (-0.03, 0.15, 0.19, 0.12)):
        mesh.add_box((x, 0.045, z), (dx, 0.09, dz), PALETTE["canvas"])
        mesh.add_pyramid((x, 0.09, z), max(dx, dz) * 1.15, 0.07, PALETTE["canvas"])
    mesh.add_box((0.22, 0.02, 0.20), (0.09, 0.04, 0.09), PALETTE["ember"])
    mesh.add_box((-0.30, 0.15, 0.02), (0.02, 0.30, 0.02), PALETTE["wood"])
    mesh.add_box((-0.23, 0.25, 0.02), (0.14, 0.09, 0.01), PALETTE["banner"])
    return mesh


def _pass_fort() -> Mesh:
    """3 관(關) — 길목을 가로지른 關城. 성이 아니라 **막은 선**이라 담장이 아니라 벽이다."""
    mesh = Mesh()
    wall_h, thickness = 0.30, 0.11
    gap = 0.13
    run = (0.96 - gap) / 2
    for sign in (-1.0, 1.0):
        mesh.add_box((sign * (gap + run) / 2, wall_h / 2, 0.0), (run, wall_h, thickness),
                     PALETTE["stone"])
    # 관문루. 문 위로 올라앉아 이 타일에서 제일 높다.
    mesh.add_box((0.0, wall_h + 0.09, 0.0), (gap + 0.10, 0.18, thickness + 0.04), PALETTE["wall"])
    mesh.add_pyramid((0.0, wall_h + 0.18, 0.0), gap + 0.22, 0.09, PALETTE["roof"])
    for x in (-0.30, 0.30):
        _tower(mesh, x, 0.0, 0.11, wall_h * 1.45, 0.0, roof=True)
    # 양 끝을 물고 있는 벼랑. 길이 여기 말고 없다는 것을 형태로 말한다.
    for x in (-0.39, 0.39):
        mesh.add_pyramid((x, 0.0, 0.0), 0.20, 0.34, PALETTE["rock"])
    return mesh


def _tribal() -> Mesh:
    """4 이(夷) — 담장 없는 모전 천막 야영. 성벽이 없다는 것 자체가 등급 표시다."""
    mesh = Mesh()
    # 셋을 붙여 놓으면 처마가 겹쳐 한 덩어리로 뭉친다 — 2D 에서 똑같이 겪었다.
    for x, z, size, height in ((-0.21, -0.17, 0.24, 0.25), (0.20, -0.10, 0.20, 0.21),
                               (-0.03, 0.21, 0.22, 0.23)):
        # 원추 지붕을 얹은 낮은 원통 — 상자 + 피라미드로 흉내낸다. 지붕을 몸통보다 크게 잡아
        # 처마가 지도록 두면 담장 없는 야영이라는 것이 위에서도 읽힌다.
        mesh.add_box((x, height * 0.20, z), (size * 0.86, height * 0.40, size * 0.80), PALETTE["hide"])
        mesh.add_pyramid((x, height * 0.40, z), size * 1.10, height * 0.72, PALETTE["hide_dark"])
    mesh.add_box((0.24, 0.02, 0.24), (0.09, 0.04, 0.09), PALETTE["ember"])
    # 마구간 삼아 세운 말뚝 줄. 담장이 아니라 줄이라는 것이 요점이다.
    for x in (-0.34, -0.22, -0.10):
        mesh.add_box((x, 0.05, 0.34), (0.02, 0.10, 0.02), PALETTE["wood"])
    mesh.add_box((-0.22, 0.10, 0.34), (0.26, 0.015, 0.015), PALETTE["wood"])
    return mesh


#: 담장 城 7 단의 인자표. 실루엣이 벌어지는 축을 한 줄에 모아 둔다.
_WALLED: dict[str, dict[str, float | int | bool]] = {
    "county-small":      dict(half=0.30, thickness=0.050, wall_h=0.17, towers=0, gap=0.11,
                              gate_storeys=0, halls=1, trees=1, crenels=False),
    "county":            dict(half=0.33, thickness=0.055, wall_h=0.21, towers=1, gap=0.12,
                              gate_storeys=1, halls=2, trees=2, crenels=False),
    "commandery":        dict(half=0.36, thickness=0.060, wall_h=0.25, towers=2, gap=0.13,
                              gate_storeys=1, halls=3, trees=2, plinth=0.02),
    "commandery-mid":    dict(half=0.39, thickness=0.065, wall_h=0.29, towers=3, gap=0.14,
                              gate_storeys=1, halls=4, trees=3, plinth=0.03),
    "commandery-major":  dict(half=0.42, thickness=0.070, wall_h=0.33, towers=4, gap=0.15,
                              gate_storeys=2, halls=5, trees=3, plinth=0.04),
    "commandery-grand":  dict(half=0.41, thickness=0.075, wall_h=0.37, towers=4, gap=0.15,
                              gate_storeys=2, halls=6, trees=4, plinth=0.05, storeys=2, moat=True),
    "capital":           dict(half=0.435, thickness=0.075, wall_h=0.43, towers=4, gap=0.16,
                              gate_storeys=2, halls=7, trees=4, plinth=0.06, storeys=2, que=True,
                              flag=True),
}

_SPECIAL = {"water": _water, "garrison": _garrison, "pass": _pass_fort, "tribal": _tribal}


def build_building(tier: str) -> Mesh:
    mesh = _SPECIAL[tier]() if tier in _SPECIAL else _walled(**_WALLED[tier])  # type: ignore[arg-type]
    over = _footprint(mesh)
    if over > 0.0:
        raise ValueError(f"{tier}: 타일 밖으로 {over:.3f} 나갔다 (반폭 {FOOTPRINT_HALF})")
    return mesh


#: 병종 armType(`data/unitset/units.json` crewTypes) → 유닛 모델.
UNIT_ARM_TYPES = {0: "wall", 1: "infantry", 2: "archer", 3: "cavalry", 4: "siege", 5: "tower"}


def build_unit(arm: str) -> Mesh:
    mesh = Mesh()
    if arm == "wall":
        mesh.add_box((0.0, 0.16, 0.0), (0.86, 0.32, 0.18), PALETTE["wall"])
        for x in (-0.30, 0.0, 0.30):
            mesh.add_box((x, 0.36, 0.0), (0.12, 0.08, 0.18), PALETTE["wall"])
        return mesh
    if arm == "tower":
        mesh.add_box((0.0, 0.24, 0.0), (0.30, 0.48, 0.30), PALETTE["wood"])
        mesh.add_box((0.0, 0.54, 0.0), (0.40, 0.12, 0.40), PALETTE["wood"])
        mesh.add_box((0.0, 0.03, -0.16), (0.34, 0.06, 0.06), PALETTE["iron"])
        return mesh
    if arm == "siege":
        mesh.add_box((0.0, 0.12, 0.0), (0.44, 0.20, 0.28), PALETTE["wood"])
        mesh.add_box((0.0, 0.30, 0.06), (0.10, 0.28, 0.10), PALETTE["wood"])
        mesh.add_box((0.0, 0.44, -0.10), (0.34, 0.06, 0.06), PALETTE["iron"])
        return mesh

    # 보병·궁병·기병은 같은 인체 실루엣을 공유하고 장비·탈것으로 구분한다.
    if arm == "cavalry":
        mesh.add_box((0.0, 0.16, 0.0), (0.44, 0.18, 0.18), PALETTE["horse"])
        for x, z in ((-0.16, -0.06), (-0.16, 0.06), (0.16, -0.06), (0.16, 0.06)):
            mesh.add_box((x, 0.035, z), (0.05, 0.07, 0.05), PALETTE["horse"])
        mesh.add_box((0.22, 0.26, 0.0), (0.12, 0.14, 0.12), PALETTE["horse"])
        body_base = 0.25
    else:
        body_base = 0.0

    mesh.add_box((0.0, body_base + 0.13, 0.0), (0.16, 0.26, 0.12), PALETTE["leather"])
    mesh.add_box((0.0, body_base + 0.31, 0.0), (0.11, 0.10, 0.11), PALETTE["iron"])
    if arm == "infantry":
        mesh.add_box((0.11, body_base + 0.22, 0.0), (0.02, 0.34, 0.02), PALETTE["wood"])
        mesh.add_box((0.11, body_base + 0.38, 0.0), (0.03, 0.10, 0.01), PALETTE["iron"])
        mesh.add_box((-0.12, body_base + 0.15, 0.0), (0.03, 0.18, 0.14), PALETTE["wall"])
    elif arm == "archer":
        mesh.add_box((0.10, body_base + 0.20, 0.0), (0.02, 0.28, 0.02), PALETTE["wood"])
        mesh.add_box((-0.09, body_base + 0.24, 0.05), (0.05, 0.16, 0.05), PALETTE["leather"])
    elif arm == "cavalry":
        mesh.add_box((0.12, body_base + 0.20, 0.0), (0.02, 0.30, 0.02), PALETTE["wood"])
        mesh.add_box((0.12, body_base + 0.36, 0.0), (0.03, 0.10, 0.01), PALETTE["iron"])
    return mesh


def build_all() -> dict[str, str]:
    legend = _terrain_legend()
    playable = {name for name in legend.values() if name != "OUT_OF_SCOPE"}
    out: dict[str, str] = {}
    for kind in sorted(playable):
        out[f"terrain/{kind.lower()}.gltf"] = write_gltf(build_terrain(kind), f"terrain-{kind.lower()}")
    out["terrain/skirt.gltf"] = write_gltf(build_skirt(), "terrain-skirt")
    for tier in sorted(CITY_TIERS):
        out[f"building/{tier}.gltf"] = write_gltf(build_building(tier), f"building-{tier}")
    for arm in sorted(UNIT_ARM_TYPES.values()):
        out[f"unit/{arm}.gltf"] = write_gltf(build_unit(arm), f"unit-{arm}")

    manifest = {
        "schemaVersion": 1,
        "artifactId": "iso3d-assets-v1",
        "generator": "tools/assets/build_iso3d_assets.py",
        "note": (
            "저폴리 절차 3D 에셋. 타일은 XZ 평면 1×1, Y 가 위다 — 아이소 다이아몬드는 카메라가 만든다. "
            "지형 타일은 **윗면만** 있고 측면이 없다(같은 높이 인접 타일이 이음매 없이 이어지도록). "
            "흙벽은 terrain/skirt.gltf 한 장이며 렌더러가 높이 단차·물가·맵 가장자리에만 세운다. "
            "정점 색만 쓰고 텍스처가 없으며, 국가색은 런타임이 재질 baseColorFactor 로 곱한다. "
            "城 은 등급 1:1 로 11 단이고 모두 타일 반폭 0.5 안에 선다(빌더가 검사한다)."
        ),
        "rasterGroup": RASTER_GROUP,
        "tileGrid": {"cols": 768 // RASTER_GROUP, "rows": 669 // RASTER_GROUP},
        "terrain": sorted(playable),
        "skirt": "terrain/skirt.gltf",
        "buildingTiers": {tier: {"cityLevelFrom": lv, "cityLevelTo": lv} for tier, lv in sorted(CITY_TIERS.items())},
        "cityLevelTiers": {str(lv): tier for lv, tier in sorted((lv, tier) for tier, lv in CITY_TIERS.items())},
        "unitArmTypes": {str(k): v for k, v in sorted(UNIT_ARM_TYPES.items())},
        "files": sorted(out),
    }
    out["manifest.json"] = json.dumps(manifest, ensure_ascii=False, indent=1) + "\n"
    return out


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true", help="드리프트가 있으면 exit 1")
    args = parser.parse_args()

    files = build_all()
    if args.check:
        drift = [
            f"{out.parents[2].name}:{name}"
            for out in OUT_DIRS for name, text in sorted(files.items())
            if not (out / name).is_file() or (out / name).read_text(encoding="utf-8") != text
        ]
        # 두 벌 중 한 벌에만 있는 파일도 드리프트다 — 게이트웨이에 옛 모델이 남아 있으면
        # 로컬은 멀쩡하고 프로덕션만 옛 城 을 그린다.
        for out in OUT_DIRS:
            stale = sorted(
                str(path.relative_to(out)) for path in out.rglob("*")
                if path.is_file() and str(path.relative_to(out)) not in files
            )
            drift += [f"{out.parents[2].name}:잔여 {name}" for name in stale]
        if drift:
            print("드리프트: " + ", ".join(drift[:8]) + (f" 외 {len(drift) - 8}건" if len(drift) > 8 else ""))
            return 1
        print(f"드리프트 없음 (iso3d 에셋 {len(files)}개 × {len(OUT_DIRS)}벌).")
        return 0

    for out in OUT_DIRS:
        for name, text in sorted(files.items()):
            path = out / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(text, encoding="utf-8")
        for path in sorted(out.rglob("*")):
            if path.is_file() and str(path.relative_to(out)) not in files:
                path.unlink()
    total = sum(len(t.encode("utf-8")) for t in files.values())
    print(f"에셋 {len(files)}개 · {total / 1024:.0f} KB → " + ", ".join(str(o.relative_to(ROOT)) for o in OUT_DIRS))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
