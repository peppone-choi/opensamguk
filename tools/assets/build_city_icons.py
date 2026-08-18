#!/usr/bin/env python3
"""도시 아이콘 빌더 (자작 · 절차적 · 결정적).

`web/{gateway,game}/public/city/cast_{1..8}.png` 16장과 사람이 눈으로 볼 수 있는
확대 시트 `assets/brand/city-icons/preview.png` 1장을 생성한다. 입력 이미지는 없다 —
픽셀아트를 코드로 직접 그린다(`build_flag_assets.py`와 같은 방식).

    python3 tools/assets/build_city_icons.py
    python3 tools/assets/build_city_icons.py --check   # 손편집 드리프트 검사, 불일치면 비0 종료

이 스크립트가 존재하는 이유: 기존 도시 아이콘은 CDN(`opensamguk-images`)의
`game/cast_*.gif`이고 그 출처 `devsam/image`에는 LICENSE가 없어 권리가 UNKNOWN이다
(`docs/superpowers/research/2026-08-17-asset-license-audit.md` §1-2, 부록). 깃발과 같은
판단 — 20px 남짓 픽셀아트는 권리 확인보다 다시 그리는 편이 싸다.

캔버스 크기는 레거시 자산의 자연 크기(`MapViewer.DETAIL_SIZES`의 iconW/iconH)와 같다.
그래야 `.city-cast`(width/height 100% + image-rendering: pixelated) 렌더 결과의 배율이
그대로 유지돼 레이아웃이 바뀌지 않는다.

레벨 라벨(`MapViewer.LEVEL_TEXT`)에 맞춘 모양:
  1 수(마을)  2 진(목책 요새)  3 관(관문)  4 이(이민족 취락)
  5 소  6 중  7 대  8 특 — 5~8은 같은 성 실루엣을 규모만 키운다.
"""

from __future__ import annotations

import argparse
import io
import sys
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parents[2]
APPS = ("gateway", "game")
PREVIEW = ROOT / "assets" / "brand" / "city-icons" / "preview.png"
PREVIEW_SCALE = 8
PREVIEW_GAP = 4

# 레거시 자산의 자연 크기 = MapViewer.DETAIL_SIZES 의 (iconW, iconH).
DIMS = {
    1: (16, 15),
    2: (20, 14),
    3: (14, 14),
    4: (20, 15),
    5: (24, 16),
    6: (26, 18),
    7: (28, 20),
    8: (32, 24),
}

OUTLINE = (26, 22, 20, 255)
STONE = (176, 168, 152, 255)
STONE_L = (218, 212, 198, 255)
STONE_D = (116, 108, 96, 255)
ROOF = (168, 62, 52, 255)
ROOF_D = (112, 38, 32, 255)
WOOD = (152, 106, 60, 255)
WOOD_D = (98, 66, 36, 255)
GATE = (48, 40, 34, 255)
GOLD = (238, 198, 92, 255)
STRAW = (198, 170, 104, 255)
STRAW_D = (146, 122, 70, 255)
TENT = (196, 178, 142, 255)
TENT_D = (138, 120, 90, 255)


class Canvas:
    """알파 포함 픽셀 격자. 범위 밖 쓰기는 조용히 버린다(모양 계산을 단순하게 두려고)."""

    def __init__(self, w: int, h: int) -> None:
        self.w, self.h = w, h
        self.px: dict[tuple[int, int], tuple[int, int, int, int]] = {}

    def set(self, x: int, y: int, color) -> None:
        if 0 <= x < self.w and 0 <= y < self.h:
            self.px[(x, y)] = color

    def get(self, x: int, y: int):
        return self.px.get((x, y))

    def rect(self, x0: int, y0: int, x1: int, y1: int, color) -> None:
        for y in range(y0, y1 + 1):
            for x in range(x0, x1 + 1):
                self.set(x, y, color)

    def outline(self) -> None:
        """채워진 픽셀에 4-이웃한 빈 픽셀을 외곽선으로 만든다. 어떤 배경 위에서도 읽히게."""
        edge = set()
        for (x, y) in self.px:
            for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                p = (x + dx, y + dy)
                if p not in self.px and 0 <= p[0] < self.w and 0 <= p[1] < self.h:
                    edge.add(p)
        for p in edge:
            self.px[p] = OUTLINE

    def to_image(self) -> Image.Image:
        img = Image.new("RGBA", (self.w, self.h), (0, 0, 0, 0))
        for (x, y), color in self.px.items():
            img.putpixel((x, y), color)
        return img


def wall(c: Canvas, x0: int, x1: int, top: int, bottom: int) -> None:
    """성벽 몸통 — 윗줄은 밝게, 아래·양끝은 어둡게 해서 입체감을 만든다."""
    c.rect(x0, top, x1, bottom, STONE)
    c.rect(x0, top, x1, top, STONE_L)
    c.rect(x0, bottom, x1, bottom, STONE_D)
    c.rect(x0, top, x0, bottom, STONE_D)
    c.rect(x1, top, x1, bottom, STONE_D)


def merlons(c: Canvas, x0: int, x1: int, y: int) -> None:
    """총안(凸凹) — 두 칸 걸러 한 칸씩 위로 솟은 돌."""
    for x in range(x0, x1 + 1):
        if (x - x0) % 2 == 0:
            c.set(x, y, STONE_L)


def gate(c: Canvas, cx: int, bottom: int, gw: int, gh: int) -> None:
    """성문 — 위 한 줄은 양옆을 한 칸씩 좁혀 아치로."""
    half = gw // 2
    for i in range(gh):
        y = bottom - i
        shrink = 1 if i == gh - 1 and gw >= 3 else 0
        c.rect(cx - half + shrink, y, cx + half - shrink, y, GATE)


def roof(c: Canvas, cx: int, top: int, rows: int, width: int, tip: bool) -> None:
    """지붕 — 아래로 갈수록 넓어지는 사다리꼴. tip=True면 꼭대기에 용마루 한 점."""
    for i in range(rows):
        w = width - 2 * (rows - 1 - i)
        if w < 1:
            continue
        half = w // 2
        color = ROOF if i < rows - 1 else ROOF_D
        c.rect(cx - half, top + i, cx + half, top + i, color)
    if tip:
        c.set(cx, top - 1, ROOF_D)


def hut(c: Canvas, cx: int, bottom: int, w: int, body_h: int) -> None:
    """초가 — 나무 벽 + 짚 지붕. 마을(lv1)의 단위."""
    half = w // 2
    c.rect(cx - half, bottom - body_h + 1, cx + half, bottom, WOOD)
    c.rect(cx - half, bottom, cx + half, bottom, WOOD_D)
    c.set(cx, bottom, GATE)  # 출입구
    top = bottom - body_h
    for i in range(2):
        y = top - i
        half_r = half + 1 - i
        c.rect(cx - half_r, y, cx + half_r, y, STRAW if i == 0 else STRAW_D)


def draw_village(c: Canvas) -> None:
    """1 수 — 담장 없는 마을. 뒤쪽 언덕의 초가 한 채 + 앞줄 두 채."""
    base = c.h - 2
    hut(c, 8, base - 6, 5, 4)   # 뒤 — 먼저 그려 앞줄이 덮게 한다
    hut(c, 3, base, 5, 4)
    hut(c, 12, base, 5, 4)


def draw_palisade(c: Canvas) -> None:
    """2 진 — 끝을 뾰족하게 깎은 통나무 목책과 망루."""
    base = c.h - 2
    top = base - 8
    for x in range(1, c.w - 1):
        c.rect(x, top + 1, x, base, WOOD if x % 2 else WOOD_D)
        c.set(x, top, WOOD_D)  # 뾰족한 끝
    gate(c, c.w // 2, base, 3, 3)
    # 망루 — 목책 오른쪽 뒤로 솟은 나무 단.
    tx = c.w - 5
    c.rect(tx, top - 3, tx + 3, top - 1, WOOD)
    c.rect(tx, top - 3, tx + 3, top - 3, WOOD_D)


def draw_pass(c: Canvas) -> None:
    """3 관 — 좌우 절벽 사이에 낀 관문. 폭이 좁고 세로로 선 실루엣."""
    base = c.h - 2
    c.rect(1, 3, 3, base, STONE_D)
    c.rect(c.w - 4, 3, c.w - 2, base, STONE_D)
    wall(c, 4, c.w - 5, 5, base)
    cx = c.w // 2
    gate(c, cx, base, 3, 4)
    roof(c, cx, 2, 3, c.w - 4, tip=False)


def draw_camp(c: Canvas) -> None:
    """4 이 — 이민족 취락. 천막 두 채와 토템 기둥(성벽 없음)."""
    base = c.h - 2
    for cx, half_w, hgt in ((4, 3, 6), (15, 3, 7)):
        for i in range(hgt):
            half = round(half_w * i / (hgt - 1))
            y = base - hgt + 1 + i
            c.rect(cx - half, y, cx + half, y, TENT if i < hgt - 1 else TENT_D)
        c.set(cx, base, GATE)  # 입구
        c.set(cx, base - hgt, WOOD_D)  # 천막 꼭대기 장대
    tx = 10
    c.rect(tx, base - 9, tx, base, WOOD_D)
    c.rect(tx - 1, base - 9, tx + 1, base - 9, WOOD)
    c.rect(tx - 1, base - 6, tx + 1, base - 6, WOOD)


# 5~8 성 — 같은 실루엣의 규모 차이. (성벽 높이, 천수각 폭, 천수각 높이, 곁탑, 금장)
CASTLE_SPEC = {
    5: dict(wall_h=5, keep_w=7, keep_h=3, towers=False, gold=False),
    6: dict(wall_h=6, keep_w=9, keep_h=4, towers=True, gold=False),
    7: dict(wall_h=6, keep_w=9, keep_h=6, towers=True, gold=False),
    8: dict(wall_h=7, keep_w=11, keep_h=8, towers=True, gold=True),
}


def draw_castle(c: Canvas, spec: dict) -> None:
    base = c.h - 2
    x0, x1 = 1, c.w - 2
    wall_top = base - spec["wall_h"] + 1
    wall(c, x0, x1, wall_top, base)
    merlons(c, x0, x1, wall_top - 1)

    cx = c.w // 2
    gate(c, cx, base, 3 if c.w < 28 else 5, max(3, spec["wall_h"] - 2))

    # 곁탑 — 성벽 양끝에서 한 칸 솟는다.
    if spec["towers"]:
        for tx in (x0, x1 - 2):
            c.rect(tx, wall_top - 3, tx + 2, base, STONE)
            c.rect(tx, wall_top - 3, tx + 2, wall_top - 3, STONE_D)
            c.set(tx + 1, wall_top - 1, GATE)  # 총안 구멍

    # 천수각 — 성벽 위 중앙. 층마다 지붕을 얹는다.
    kw, kh = spec["keep_w"], spec["keep_h"]
    khalf = kw // 2
    keep_top = wall_top - kh
    c.rect(cx - khalf, keep_top, cx + khalf, wall_top - 1, STONE)
    c.rect(cx - khalf, keep_top, cx - khalf, wall_top - 1, STONE_D)
    c.rect(cx + khalf, keep_top, cx + khalf, wall_top - 1, STONE_D)
    for wy in range(keep_top + 2, wall_top - 1, 3):
        c.set(cx, wy, GATE)  # 창
    if spec["gold"]:
        c.rect(cx - khalf + 1, keep_top + 1, cx + khalf - 1, keep_top + 1, GOLD)
    roof(c, cx, keep_top - 2, 2, kw + 2, tip=kh >= 6)
    if kh >= 6:  # 대·특은 중간층에도 처마를 둔다.
        roof(c, cx, keep_top + kh // 2, 1, kw + 2, tip=False)


def build(level: int) -> Image.Image:
    w, h = DIMS[level]
    c = Canvas(w, h)
    if level == 1:
        draw_village(c)
    elif level == 2:
        draw_palisade(c)
    elif level == 3:
        draw_pass(c)
    elif level == 4:
        draw_camp(c)
    else:
        draw_castle(c, CASTLE_SPEC[level])
    c.outline()
    return c.to_image()


def preview_sheet(icons: dict[int, Image.Image]) -> Image.Image:
    s, gap = PREVIEW_SCALE, PREVIEW_GAP
    w = sum(i.width * s for i in icons.values()) + gap * (len(icons) + 1)
    h = max(i.height * s for i in icons.values()) + gap * 2
    sheet = Image.new("RGBA", (w, h), (24, 24, 28, 255))
    x = gap
    for level in sorted(icons):
        img = icons[level].resize(
            (icons[level].width * s, icons[level].height * s), Image.NEAREST
        )
        sheet.alpha_composite(img, (x, h - gap - img.height))
        x += img.width + gap
    return sheet


def png_bytes(img: Image.Image) -> bytes:
    buf = io.BytesIO()
    img.save(buf, "PNG", optimize=True)
    return buf.getvalue()


def targets(icons: dict[int, Image.Image]) -> dict[Path, bytes]:
    out: dict[Path, bytes] = {}
    for level, img in icons.items():
        data = png_bytes(img)
        for app in APPS:
            out[ROOT / "web" / app / "public" / "city" / f"cast_{level}.png"] = data
    out[PREVIEW] = png_bytes(preview_sheet(icons))
    return out


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--check", action="store_true", help="쓰지 않고 기존 파일과 바이트 비교")
    args = ap.parse_args()

    icons = {level: build(level) for level in sorted(DIMS)}
    files = targets(icons)

    if args.check:
        bad = [
            p for p, data in files.items()
            if not p.exists() or p.read_bytes() != data
        ]
        for p in bad:
            print(f"DRIFT {p.relative_to(ROOT)}", file=sys.stderr)
        if bad:
            print(f"{len(bad)}개 산출물이 빌더 출력과 다르다.", file=sys.stderr)
            return 1
        print(f"{len(files)}개 산출물 일치.")
        return 0

    for p, data in files.items():
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_bytes(data)
        print(f"wrote {p.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
