"""최소 glTF 2.0 라이터 — 표준 라이브러리만 쓴다.

저폴리 절차 에셋을 굽기 위한 것이라 필요한 만큼만 지원한다:
정점 위치 · 정점 색 · 삼각형 인덱스 · 노드 하나 · 재질 하나(정점 색 사용).

**왜 텍스처가 없나.** 정점 색이면 이미지 파일이 필요 없고, 산출물이 `.gltf` 한 장으로 닫힌다.
지도 타일 크기(화면에서 수십 px)에서는 텍스처가 거의 보이지 않고, 국가색 tint 는 런타임이
재질에 곱해서 넣는 편이 에셋을 국가 수만큼 복제하는 것보다 낫다.

**왜 GLB 가 아닌가.** `.gltf` + base64 data URI 는 텍스트라 diff 가 읽히고 `--check` 로
드리프트를 잡을 수 있다. 크기는 base64 때문에 33% 크지만 이 규모(수 KB)에서는 문제가 아니다.
"""

from __future__ import annotations

import base64
import json
import struct
from typing import Sequence

Vec3 = tuple[float, float, float]
Tri = tuple[int, int, int]

# glTF component / target enums (spec 표 그대로).
_FLOAT = 5126
_UNSIGNED_INT = 5125
_ARRAY_BUFFER = 34962
_ELEMENT_ARRAY_BUFFER = 34963


class Mesh:
    """정점 색을 가진 삼각형 메시 하나를 모은다."""

    def __init__(self) -> None:
        self.positions: list[Vec3] = []
        self.colors: list[Vec3] = []
        self.triangles: list[Tri] = []

    def add_polygon(self, points: Sequence[Vec3], color: Vec3) -> None:
        """볼록 다각형을 팬으로 삼각분할해 넣는다. 정점은 공유하지 않는다.

        정점을 공유하지 않는 이유: 면마다 색이 다른 저폴리 룩이 목표라, 공유하면 색이 보간돼
        면 경계가 뭉갠다. 정점 수가 몇 배가 되지만 이 규모에서는 무의미하다.
        """
        if len(points) < 3:
            raise ValueError("polygon needs at least 3 points")
        base = len(self.positions)
        for point in points:
            self.positions.append(tuple(float(v) for v in point))  # type: ignore[arg-type]
            self.colors.append(color)
        # 감김은 **반시계가 앞면**이다(glTF 규약). 위를 보는 면을 (-x,-z)→(+x,-z)→(+x,+z) 순으로
        # 적으면 법선이 아래로 향해 카메라에서 사라진다 — 첫 판이 그랬고, 타일을 깔아 보기
        # 전까지는 드러나지 않았다(낱개 미리보기에서는 옆면만 보여 멀쩡해 보인다).
        # 입력 순서를 그대로 두고 삼각형 감김만 뒤집어 전 면을 한 번에 맞춘다.
        for index in range(1, len(points) - 1):
            self.triangles.append((base, base + index + 1, base + index))

    def add_box(self, center: Vec3, size: Vec3, color: Vec3, top_color: Vec3 | None = None) -> None:
        """축 정렬 상자. 윗면만 다른 색을 줄 수 있다(빛 방향을 색으로 흉내낸다)."""
        cx, cy, cz = center
        hx, hy, hz = size[0] / 2, size[1] / 2, size[2] / 2
        x0, x1 = cx - hx, cx + hx
        y0, y1 = cy - hy, cy + hy
        z0, z1 = cz - hz, cz + hz
        side = color
        top = top_color if top_color is not None else _lighten(color, 0.18)
        bottom = _darken(color, 0.30)
        self.add_polygon([(x0, y1, z0), (x1, y1, z0), (x1, y1, z1), (x0, y1, z1)], top)
        self.add_polygon([(x0, y0, z1), (x1, y0, z1), (x1, y0, z0), (x0, y0, z0)], bottom)
        self.add_polygon([(x0, y0, z0), (x1, y0, z0), (x1, y1, z0), (x0, y1, z0)], _darken(side, 0.12))
        self.add_polygon([(x1, y0, z1), (x0, y0, z1), (x0, y1, z1), (x1, y1, z1)], side)
        self.add_polygon([(x0, y0, z1), (x0, y0, z0), (x0, y1, z0), (x0, y1, z1)], _darken(side, 0.20))
        self.add_polygon([(x1, y0, z0), (x1, y0, z1), (x1, y1, z1), (x1, y1, z0)], _lighten(side, 0.06))

    def add_pyramid(self, center: Vec3, base_size: float, height: float, color: Vec3) -> None:
        cx, cy, cz = center
        half = base_size / 2
        apex = (cx, cy + height, cz)
        corners = [
            (cx - half, cy, cz - half),
            (cx + half, cy, cz - half),
            (cx + half, cy, cz + half),
            (cx - half, cy, cz + half),
        ]
        shades = (0.12, 0.0, -0.10, -0.20)
        for index in range(4):
            a, b = corners[index], corners[(index + 1) % 4]
            self.add_polygon([a, b, apex], _shade(color, shades[index]))

    @property
    def is_empty(self) -> bool:
        return not self.triangles


def _clamp01(value: float) -> float:
    return 0.0 if value < 0.0 else 1.0 if value > 1.0 else value


def _shade(color: Vec3, amount: float) -> Vec3:
    return _lighten(color, amount) if amount >= 0 else _darken(color, -amount)


def _lighten(color: Vec3, amount: float) -> Vec3:
    return tuple(_clamp01(c + (1.0 - c) * amount) for c in color)  # type: ignore[return-value]


def _darken(color: Vec3, amount: float) -> Vec3:
    return tuple(_clamp01(c * (1.0 - amount)) for c in color)  # type: ignore[return-value]


def write_gltf(mesh: Mesh, name: str) -> str:
    """메시 하나를 담은 `.gltf` 문서를 결정론적 JSON 문자열로 만든다."""
    if mesh.is_empty:
        raise ValueError(f"mesh {name!r} has no triangles")

    position_bytes = b"".join(struct.pack("<3f", *p) for p in mesh.positions)
    color_bytes = b"".join(struct.pack("<3f", *c) for c in mesh.colors)
    index_bytes = b"".join(struct.pack("<3I", *t) for t in mesh.triangles)
    # 버퍼 뷰는 4바이트 정렬을 요구한다. 위 세 배열은 모두 4의 배수라 패딩이 필요없지만,
    # 계약을 코드로 못박아 둔다 — 나중에 다른 타입을 더할 때 조용히 깨지는 곳이다.
    for label, blob in (("position", position_bytes), ("color", color_bytes), ("index", index_bytes)):
        if len(blob) % 4:
            raise AssertionError(f"{label} buffer is not 4-byte aligned")

    buffer = position_bytes + color_bytes + index_bytes
    xs = [p[0] for p in mesh.positions]
    ys = [p[1] for p in mesh.positions]
    zs = [p[2] for p in mesh.positions]

    document = {
        "asset": {"version": "2.0", "generator": "opensamguk tools/assets/build_iso3d_assets.py"},
        "scene": 0,
        "scenes": [{"name": name, "nodes": [0]}],
        "nodes": [{"name": name, "mesh": 0}],
        "meshes": [{
            "name": name,
            "primitives": [{
                "attributes": {"POSITION": 0, "COLOR_0": 1},
                "indices": 2,
                "material": 0,
                "mode": 4,
            }],
        }],
        # 정점 색을 그대로 쓰는 무광 재질. baseColorFactor 를 흰색으로 두어 런타임이 국가색을
        # 곱해 넣을 자리를 비워 둔다.
        "materials": [{
            "name": "vertex-color",
            "pbrMetallicRoughness": {
                "baseColorFactor": [1.0, 1.0, 1.0, 1.0],
                "metallicFactor": 0.0,
                "roughnessFactor": 0.95,
            },
            "doubleSided": False,
        }],
        "accessors": [
            {
                "bufferView": 0, "componentType": _FLOAT, "count": len(mesh.positions), "type": "VEC3",
                "min": [min(xs), min(ys), min(zs)], "max": [max(xs), max(ys), max(zs)],
            },
            {"bufferView": 1, "componentType": _FLOAT, "count": len(mesh.colors), "type": "VEC3"},
            {"bufferView": 2, "componentType": _UNSIGNED_INT, "count": len(mesh.triangles) * 3, "type": "SCALAR"},
        ],
        "bufferViews": [
            {"buffer": 0, "byteOffset": 0, "byteLength": len(position_bytes), "target": _ARRAY_BUFFER},
            {"buffer": 0, "byteOffset": len(position_bytes), "byteLength": len(color_bytes), "target": _ARRAY_BUFFER},
            {
                "buffer": 0, "byteOffset": len(position_bytes) + len(color_bytes),
                "byteLength": len(index_bytes), "target": _ELEMENT_ARRAY_BUFFER,
            },
        ],
        "buffers": [{
            "byteLength": len(buffer),
            "uri": "data:application/octet-stream;base64," + base64.b64encode(buffer).decode("ascii"),
        }],
    }
    return json.dumps(document, indent=1, sort_keys=False) + "\n"
