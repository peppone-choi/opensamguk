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
  1 수(水 · 항구)  2 진(목책 요새)  3 관(관문)  4 이(이민족 취락)
  5 소  6 중  7 대  8 특 — 넷 다 성벽을 두르고, 천수각 재료·층수·규모로만 등급을 올린다.

`수`는 마을이 아니라 물 수(水)다 — 수군의 수. lv1은 `data/extracted/map/che.json` 기준
적벽·파양·탐라·유구 넷뿐이고 전부 항구다. 그래서 부두와 배로 그린다.
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
    6: (28, 19),
    7: (32, 22),
    8: (36, 26),
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
TENT = (196, 178, 142, 255)
TENT_D = (138, 120, 90, 255)
WATER = (58, 104, 140, 255)
WATER_L = (96, 148, 184, 255)
SAIL = (226, 220, 200, 255)
SAIL_D = (176, 168, 148, 255)
# 특(lv8) 전용 금빛 석재 — 대와 특은 크기 차이만으로는 구분이 안 돼서 성체 색을 바꾼다.
GSTONE = (206, 172, 88, 255)
GSTONE_L = (240, 212, 132, 255)
GSTONE_D = (148, 116, 50, 255)


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


def wall(c: Canvas, x0: int, x1: int, top: int, bottom: int,
         sc=STONE, sl=STONE_L, sd=STONE_D) -> None:
    """성벽 몸통 — 윗줄은 밝게, 아래·양끝은 어둡게 해서 입체감을 만든다."""
    c.rect(x0, top, x1, bottom, sc)
    c.rect(x0, top, x1, top, sl)
    c.rect(x0, bottom, x1, bottom, sd)
    c.rect(x0, top, x0, bottom, sd)
    c.rect(x1, top, x1, bottom, sd)


def merlons(c: Canvas, x0: int, x1: int, y: int, sl=STONE_L) -> None:
    """총안(凸凹) — 두 칸 걸러 한 칸씩 위로 솟은 돌."""
    for x in range(x0, x1 + 1):
        if (x - x0) % 2 == 0:
            c.set(x, y, sl)


def gate(c: Canvas, cx: int, bottom: int, gw: int, gh: int) -> None:
    """성문 — 위 한 줄은 양옆을 한 칸씩 좁혀 아치로."""
    half = gw // 2
    for i in range(gh):
        y = bottom - i
        shrink = 1 if i == gh - 1 and gw >= 3 else 0
        c.rect(cx - half + shrink, y, cx + half - shrink, y, GATE)


def roof(c: Canvas, cx: int, top: int, rows: int, width: int, tip: bool,
         rc=ROOF, rd=ROOF_D) -> None:
    """지붕 — 아래로 갈수록 넓어지는 사다리꼴. tip=True면 꼭대기에 용마루 한 점."""
    for i in range(rows):
        w = width - 2 * (rows - 1 - i)
        if w < 1:
            continue
        half = w // 2
        color = rc if i < rows - 1 else rd
        c.rect(cx - half, top + i, cx + half, top + i, color)
    if tip:
        c.set(cx, top - 1, rd)


def draw_port(c: Canvas) -> None:
    """1 수(水) — 항구. 물 위에 놓인 부두와 돛단배. 마을이 아니다."""
    base = c.h - 1
    water_top = base - 2
    c.rect(0, water_top, c.w - 1, base, WATER)
    c.rect(0, water_top, c.w - 1, water_top, WATER_L)  # 물결 윗줄

    # 부두 — 물가에서 왼쪽으로 뻗은 널판과 말뚝.
    deck = water_top - 1
    c.rect(0, deck, 7, deck, WOOD)
    for px in (1, 4, 7):
        c.rect(px, deck + 1, px, base - 1, WOOD_D)

    # 배 — 선체 + 돛대 + 돛.
    c.rect(8, deck, c.w - 1, deck, WOOD)
    c.rect(9, deck + 1, c.w - 2, deck + 1, WOOD_D)
    mast = 11
    c.rect(mast, 3, mast, deck - 1, WOOD_D)
    for i in range(6):
        y = 4 + i
        c.rect(mast + 1, y, min(c.w - 1, mast + 2 + i // 2), y, SAIL if i < 5 else SAIL_D)


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


# 5~8 성 — 넷 다 성벽·총안·성문을 두른다(치소는 소부터 성을 쌓는다). 등급은 성벽 높이,
# 천수각 규모, 곁탑, 그리고 재료 색으로 오른다: 소는 목조 지붕, 중·대는 붉은 기와,
# 특은 성체 전체가 금빛 석재다. 색이 1px 크기 차이보다 멀리서 잘 읽힌다 — 대와 특은
# 크기만 다를 땐 구분이 안 됐다.
CASTLE_SPEC = {
    5: dict(wall_h=5, keep_w=7, keep_h=3, towers=False, rc=WOOD, rd=WOOD_D,
            sc=STONE, sl=STONE_L, sd=STONE_D),
    6: dict(wall_h=6, keep_w=9, keep_h=4, towers=False, rc=ROOF, rd=ROOF_D,
            sc=STONE, sl=STONE_L, sd=STONE_D),
    7: dict(wall_h=7, keep_w=11, keep_h=6, towers=True, rc=ROOF, rd=ROOF_D,
            sc=STONE, sl=STONE_L, sd=STONE_D),
    8: dict(wall_h=8, keep_w=13, keep_h=8, towers=True, rc=ROOF, rd=ROOF_D,
            sc=GSTONE, sl=GSTONE_L, sd=GSTONE_D),
}


def draw_castle(c: Canvas, spec: dict) -> None:
    base = c.h - 2
    x0, x1 = 1, c.w - 2
    sc, sl, sd = spec["sc"], spec["sl"], spec["sd"]
    wall_top = base - spec["wall_h"] + 1
    wall(c, x0, x1, wall_top, base, sc, sl, sd)
    merlons(c, x0, x1, wall_top - 1, sl)

    cx = c.w // 2
    gate(c, cx, base, 3 if c.w < 28 else 5, max(3, spec["wall_h"] - 2))

    # 곁탑 — 성벽 양끝에서 한 칸 솟는다.
    if spec["towers"]:
        for tx in (x0, x1 - 2):
            c.rect(tx, wall_top - 3, tx + 2, base, sc)
            c.rect(tx, wall_top - 3, tx + 2, wall_top - 3, sd)
            c.set(tx + 1, wall_top - 1, GATE)  # 총안 구멍

    # 천수각 — 성벽 위 중앙. 층마다 지붕을 얹는다.
    kw, kh = spec["keep_w"], spec["keep_h"]
    khalf = kw // 2
    keep_top = wall_top - kh
    c.rect(cx - khalf, keep_top, cx + khalf, wall_top - 1, sc)
    c.rect(cx - khalf, keep_top, cx - khalf, wall_top - 1, sd)
    c.rect(cx + khalf, keep_top, cx + khalf, wall_top - 1, sd)
    for wy in range(keep_top + 2, wall_top - 1, 3):
        c.set(cx, wy, GATE)  # 창
    rc, rd = spec["rc"], spec["rd"]
    roof(c, cx, keep_top - 2, 2, kw + 2, tip=kh >= 6, rc=rc, rd=rd)
    if kh >= 6:  # 대·특은 중간층에도 처마를 둔다.
        roof(c, cx, keep_top + kh // 2, 1, kw + 2, tip=False, rc=rc, rd=rd)


def build(level: int) -> Image.Image:
    w, h = DIMS[level]
    c = Canvas(w, h)
    if level == 1:
        draw_port(c)
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
