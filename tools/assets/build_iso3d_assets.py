#!/usr/bin/env python3
"""아이소메트릭 지도용 저폴리 3D 에셋을 굽는다 — 지형 9종 · 건물 4단 · 유닛 6종.

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
OUT_DIR = ROOT / "web/game/public/models/iso3d"
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


#: 도시 등급(level 4~11) → 건물 단계. 등급이 8개인데 모델을 8개 만들면 축소 화면에서 구분이
#: 안 된다. 실루엣으로 구분되는 4단으로 묶는다.
CITY_TIERS = {
    "capital": (9, 11),      # 都 — 큰 성 + 망루 넷 + 깃발
    "commandery": (7, 8),    # 郡治 — 성벽 + 망루 둘
    "county": (5, 6),        # 縣城 — 성벽 하나
    "hamlet": (4, 4),        # 작은 취락 — 담장 없음
}


def build_building(tier: str) -> Mesh:
    mesh = Mesh()
    if tier == "hamlet":
        for x, z in ((-0.14, -0.10), (0.16, 0.06), (0.0, 0.22)):
            mesh.add_box((x, 0.09, z), (0.20, 0.18, 0.18), PALETTE["wood"])
            mesh.add_pyramid((x, 0.18, z), 0.24, 0.12, PALETTE["roof"])
        return mesh

    wall_height = {"county": 0.26, "commandery": 0.34, "capital": 0.44}[tier]
    half = {"county": 0.34, "commandery": 0.40, "capital": 0.46}[tier]
    thickness = 0.07
    for x, z, sx, sz in (
        (0.0, -half, half * 2, thickness),
        (0.0, half, half * 2, thickness),
        (-half, 0.0, thickness, half * 2),
        (half, 0.0, thickness, half * 2),
    ):
        mesh.add_box((x, wall_height / 2, z), (sx, wall_height, sz), PALETTE["wall"])

    towers = {"county": 1, "commandery": 2, "capital": 4}[tier]
    corners = [(-half, -half), (half, half), (half, -half), (-half, half)][:towers]
    for x, z in corners:
        mesh.add_box((x, wall_height * 0.72, z), (0.16, wall_height * 1.44, 0.16), PALETTE["wall"])
        mesh.add_pyramid((x, wall_height * 1.44, z), 0.22, 0.14, PALETTE["roof"])

    keep_height = wall_height * (1.5 if tier == "capital" else 1.15)
    mesh.add_box((0.0, keep_height / 2, 0.0), (0.34, keep_height, 0.30), PALETTE["wall"])
    mesh.add_pyramid((0.0, keep_height, 0.0), 0.44, 0.22, PALETTE["roof"])
    if tier == "capital":
        # 깃대. 국가색은 런타임이 이 재질에 곱한다.
        mesh.add_box((0.0, keep_height + 0.30, 0.0), (0.02, 0.34, 0.02), PALETTE["wood"])
        mesh.add_box((0.09, keep_height + 0.40, 0.0), (0.18, 0.12, 0.01), PALETTE["banner"])
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
            "정점 색만 쓰고 텍스처가 없으며, 국가색은 런타임이 재질 baseColorFactor 로 곱한다."
        ),
        "rasterGroup": RASTER_GROUP,
        "tileGrid": {"cols": 768 // RASTER_GROUP, "rows": 669 // RASTER_GROUP},
        "terrain": sorted(playable),
        "skirt": "terrain/skirt.gltf",
        "buildingTiers": {tier: {"cityLevelFrom": lo, "cityLevelTo": hi} for tier, (lo, hi) in sorted(CITY_TIERS.items())},
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
            name for name, text in sorted(files.items())
            if not (OUT_DIR / name).is_file() or (OUT_DIR / name).read_text(encoding="utf-8") != text
        ]
        if drift:
            print("드리프트: " + ", ".join(drift[:8]) + (f" 외 {len(drift) - 8}건" if len(drift) > 8 else ""))
            return 1
        print(f"드리프트 없음 (iso3d 에셋 {len(files)}개).")
        return 0

    for name, text in sorted(files.items()):
        path = OUT_DIR / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")
    total = sum(len(t.encode("utf-8")) for t in files.values())
    print(f"에셋 {len(files)}개 · {total / 1024:.0f} KB → {OUT_DIR.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
