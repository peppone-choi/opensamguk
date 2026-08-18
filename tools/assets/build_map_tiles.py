#!/usr/bin/env python3
"""아이소메트릭 맵 타일 아틀라스 빌더 (자작 · 절차적 · 결정적).

계절 4종 아틀라스를 `web/{gateway,game}/public/map/tiles/<season>.png` 로 찍어낸다.
입력 이미지는 없다 — 도시 아이콘 빌더와 같은 방식으로 코드가 직접 그린다.

    python3 tools/assets/build_map_tiles.py
    python3 tools/assets/build_map_tiles.py --check

CDN 배경 래스터(`opensamguk-images` `game/map/bg_*.jpg`)를 대체한다. 그 원본은
LICENSE 없는 `devsam/image` 미러라 권리 UNKNOWN이고(에셋 감사 §1-2), 타일을 자작하면
그 의존이 사라진다. 덤으로 확대·축소가 열린다 — 래스터 한 장이 아니라 격자라서.

아틀라스 한 장 = 셀 32x32 를 가로로 19칸: [0]바다 [1]평지 [2]산지 [3..18]도로 16종.
도로 인덱스 = 3 + 비트마스크(N=1 E=2 S=4 W=8) — 이웃과 자동으로 이어진다.
타일 윗면은 2:1 아이소 다이아(32x16), 아래 8px는 흙 두께(측면).
"""

from __future__ import annotations

import argparse
import io
import sys
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parents[2]
APPS = ("gateway", "game")
PREVIEW = ROOT / "assets" / "brand" / "map-tiles" / "preview.png"

TW, TH = 32, 16          # 윗면 다이아 폭/높이 (2:1)
CELL_H = 32              # 셀 세로 — 산지 봉우리와 흙 두께를 담는다
SKIRT = 8                # 흙 두께(측면) px
ROAD_COUNT = 16
TILES = 3 + ROAD_COUNT

# 계절 팔레트 — (평지 윗면, 평지 측면, 산지 바위, 산지 그늘, 바다, 바다 물결, 길, 길 가장자리)
SEASONS = {
    "spring": ((124, 162, 92), (86, 112, 62), (156, 150, 134), (104, 100, 88),
               (58, 104, 140), (96, 148, 184), (168, 142, 96), (128, 104, 68)),
    "summer": ((92, 144, 74), (62, 100, 50), (146, 142, 128), (96, 94, 84),
               (46, 96, 136), (84, 140, 180), (162, 134, 88), (122, 98, 62)),
    "fall":   ((176, 148, 82), (126, 104, 56), (152, 140, 122), (102, 94, 80),
               (54, 98, 132), (90, 140, 176), (154, 124, 78), (116, 92, 56)),
    "winter": ((214, 216, 214), (158, 162, 164), (196, 198, 202), (134, 138, 144),
               (44, 78, 108), (78, 118, 150), (176, 172, 164), (132, 130, 126)),
}


def diamond_rows(hw: int) -> dict[int, int]:
    """2:1 다이아몬드 — y별 half-width."""
    hd = hw // 2
    return {y: hw - 2 * abs(y) for y in range(-hd, hd + 1)}


class Tile:
    """셀 하나. 원점은 좌상단, 윗면 다이아 중심은 (TW/2, CELL_H - SKIRT - TH/2)."""

    def __init__(self) -> None:
        self.px: dict[tuple[int, int], tuple[int, int, int, int]] = {}
        self.cx = TW // 2
        self.cy = CELL_H - SKIRT - TH // 2

    def set(self, x: int, y: int, c) -> None:
        if 0 <= x < TW and 0 <= y < CELL_H:
            self.px[(x, y)] = c

    def top(self, color, shade=None, dy: int = 0) -> None:
        """윗면 다이아. shade 를 주면 아래쪽 절반을 그 색으로 깔아 입체감을 준다."""
        for y, rw in diamond_rows(TW // 2).items():
            for x in range(-rw, rw + 1):
                self.set(self.cx + x, self.cy + y + dy, color if (shade is None or y <= 0) else shade)

    def skirt(self, color) -> None:
        """흙 두께 — 다이아 아래 가장자리에서 SKIRT 만큼 내린다."""
        hw = TW // 2
        for x in range(-hw, hw + 1):
            by = (hw - abs(x)) // 2
            for k in range(1, SKIRT + 1):
                self.set(self.cx + x, self.cy + by + k, color)

    def image(self) -> Image.Image:
        im = Image.new("RGBA", (TW, CELL_H), (0, 0, 0, 0))
        for p, c in self.px.items():
            im.putpixel(p, c)
        return im


def a(c, alpha=255):
    return (c[0], c[1], c[2], alpha)


def tile_sea(p) -> Tile:
    t = Tile()
    t.top(a(p[4]), a(tuple(int(v * 0.86) for v in p[4])))
    # 물결 — 결정적 격자무늬 두 줄.
    for y, rw in diamond_rows(TW // 2).items():
        if y in (-3, 2):
            for x in range(-rw + 2, rw - 1, 4):
                t.set(t.cx + x, t.cy + y, a(p[5]))
    t.skirt(a(tuple(int(v * 0.7) for v in p[4])))
    return t


def tile_plain(p) -> Tile:
    t = Tile()
    t.top(a(p[0]), a(tuple(int(v * 0.9) for v in p[0])))
    t.skirt(a(p[1]))
    return t


def tile_mountain(p) -> Tile:
    t = Tile()
    t.top(a(p[0]), a(tuple(int(v * 0.9) for v in p[0])))
    t.skirt(a(p[1]))
    # 봉우리 — 윗면 위로 솟은 삼각뿔. 왼쪽 밝은 면 / 오른쪽 그늘.
    peak_h = 13
    for i in range(peak_h):
        rw = round((TW // 2 - 3) * (i + 1) / peak_h)
        y = t.cy - peak_h + 1 + i
        for x in range(-rw, rw + 1):
            t.set(t.cx + x, y, a(p[2]) if x < 0 else a(p[3]))
    for y, rw in diamond_rows(TW // 2 - 3).items():
        if y < 0:
            continue
        for x in range(-rw, rw + 1):
            t.set(t.cx + x, t.cy + y, a(p[3]))
    return t


# 비트 → 다이아 위에서의 방향 벡터. 격자 N/E/S/W 가 아이소에서 네 꼭짓점으로 간다.
DIRS = {1: (-1, -1), 2: (1, -1), 4: (1, 1), 8: (-1, 1)}


def tile_road(p, mask: int) -> Tile:
    t = tile_plain(p)
    hw = TW // 2

    def stamp(x: int, y: int) -> None:
        for dx in (-2, -1, 0, 1, 2):
            for dy in (-1, 0, 1):
                if abs(dx) + abs(dy) * 2 <= 3:
                    px, py = t.cx + x + dx, t.cy + y + dy
                    if (px, py) in t.px:
                        t.set(px, py, a(p[6]))

    stamp(0, 0)
    for bit, (ux, uy) in DIRS.items():
        if not mask & bit:
            continue
        for s in range(1, hw + 1):
            stamp(ux * s, uy * s // 2)
    if mask == 0:  # 고립된 길 — 점 하나로만 남으면 안 보인다.
        stamp(0, 0)
    return t


def atlas(season: str) -> Image.Image:
    p = SEASONS[season]
    tiles = [tile_sea(p), tile_plain(p), tile_mountain(p)]
    tiles += [tile_road(p, m) for m in range(ROAD_COUNT)]
    im = Image.new("RGBA", (TW * TILES, CELL_H), (0, 0, 0, 0))
    for i, t in enumerate(tiles):
        im.paste(t.image(), (i * TW, 0))
    return im


def png_bytes(img: Image.Image) -> bytes:
    buf = io.BytesIO()
    img.save(buf, format="PNG", optimize=True)
    return buf.getvalue()


def targets() -> dict[Path, bytes]:
    out: dict[Path, bytes] = {}
    for season in SEASONS:
        blob = png_bytes(atlas(season))
        for app in APPS:
            out[ROOT / "web" / app / "public" / "map" / "tiles" / f"{season}.png"] = blob
    sheet = Image.new("RGBA", (TW * TILES, CELL_H * len(SEASONS)), (24, 22, 26, 255))
    for i, season in enumerate(SEASONS):
        sheet.paste(atlas(season), (0, i * CELL_H), atlas(season))
    out[PREVIEW] = png_bytes(sheet.resize((sheet.width * 3, sheet.height * 3), Image.NEAREST))
    return out


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--check", action="store_true")
    args = ap.parse_args()
    bad = 0
    for path, blob in targets().items():
        if args.check:
            if not path.exists() or path.read_bytes() != blob:
                print(f"드리프트: {path.relative_to(ROOT)}")
                bad += 1
        else:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(blob)
            print(f"wrote {path.relative_to(ROOT)}")
    if args.check:
        print(f"{len(targets())}개 산출물 일치." if not bad else f"{bad}개 불일치.")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
