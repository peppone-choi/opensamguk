#!/usr/bin/env python3
"""도시 아이콘 빌더 (자작 · 절차적 · 결정적, 아이소메트릭).

`web/{gateway,game}/public/city/cast_{1..11}.png` 22장과 사람이 눈으로 볼 수 있는
확대 시트 `assets/brand/city-icons/preview.png` 1장을 생성한다. 입력 이미지는 없다 —
픽셀아트를 코드로 직접 그린다(`build_flag_assets.py`와 같은 방식).

    python3 tools/assets/build_city_icons.py
    python3 tools/assets/build_city_icons.py --check   # 손편집 드리프트 검사, 불일치면 비0 종료

이 스크립트가 존재하는 이유: 기존 도시 아이콘은 CDN(`opensamguk-images`)의
`game/cast_*.gif`이고 그 출처 `devsam/image`에는 LICENSE가 없어 권리가 UNKNOWN이다
(`docs/superpowers/research/2026-08-17-asset-license-audit.md` §1-2, 부록). 깃발과 같은
판단 — 20px 남짓 픽셀아트는 권리 확인보다 다시 그리는 편이 싸다.

2026-08 맵 개편으로 전 시나리오가 `han` 격자맵(2:1 아이소메트릭, 다이아 128x64, 흙 두께
32px)으로 통일되면서 두 가지가 바뀌었다:
  1. 예전 정면 뷰 픽셀아트는 다이아 타일 위에 얹으면 투영이 어긋난다 — 전부 아이소로
     다시 그린다(`iso_box`/`iso_roof` 헬퍼 — 위쪽 마름모 지붕면 + 좌/우 두 벽면).
  2. `han.json`(780성) 레벨 분포를 세어 보면 5~8(소·중·대·특) 8종 체계로는 부족하다:
       11(장현) 380 · 10(영현) 225 · 9(경) 2 · 8(특) 10 · 7(대) 15 · 6(중) 30 · 5(소) 111 · 4(이) 7
     `_meta.note`(郡治 戶 백분위 · 百官志「萬戶以上為令，不滿為長」 경계)가 출처다.
     9(경)·10(영현)·11(장현) 3종이 없으면 780성 중 607성이 아이콘 없이 깨진다.

레벨 라벨(`MapViewer.LEVEL_TEXT`)에 맞춘 모양 — 시각적 무게는 개수가 많을수록 작게:
  1 수(水 · 항구)   2 진(목책)   3 관(관문)   4 이(이민족 취락)
  5 소 6 중 7 대 8 특 — 성벽+지붕 계단, 대·특은 곁탑이 붙는다.
  9 경(京 · 황도) — 최대. 궁성 — 중앙 대전 + 좌우 곁전 + 곁탑.
  10 영현(令縣) — 만호 이상 현. 소(5)보다 작고 진(2)보다 큰 읍성.
  11 장현(長縣) — 만호 미만 현, 380개로 최다수. 성벽 없는 초가 한 채, 가장 작다.
  순서(작음→큼): 11 < 2 < 10 < 5 < 6 < 7 < 8 < 9.

`수`는 마을이 아니라 물 수(水)다 — 수군의 수. lv1은 `data/extracted/map/che.json` 기준
적벽·파양·탐라·유구 넷뿐이고 전부 항구다. 그래서 부두와 배로 그린다.
"""

from __future__ import annotations

import argparse
import io
import sys
from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[2]
APPS = ("gateway", "game")
PREVIEW = ROOT / "assets" / "brand" / "city-icons" / "preview.png"
PREVIEW_SCALE = 4
PREVIEW_GAP = 6
MARGIN = 4  # 캔버스 가장자리 여백(외곽선이 잘리지 않게).

OUTLINE = (24, 20, 18, 255)
STONE = (176, 168, 152, 255)
STONE_L = (214, 206, 190, 255)
STONE_D = (110, 102, 90, 255)
ROOF = (168, 62, 52, 255)
ROOF_L = (198, 88, 74, 255)
ROOF_D = (112, 38, 32, 255)
WOOD = (152, 106, 60, 255)
WOOD_L = (182, 134, 84, 255)
WOOD_D = (98, 66, 36, 255)
THATCH = (196, 168, 88, 255)
THATCH_L = (222, 196, 116, 255)
THATCH_D = (140, 114, 54, 255)
GATE = (44, 36, 32, 255)
TENT = (196, 178, 142, 255)
TENT_L = (222, 206, 172, 255)
TENT_D = (138, 120, 90, 255)
WATER = (58, 104, 140, 255)
WATER_L = (96, 148, 184, 255)
SAIL = (226, 220, 200, 255)
SAIL_D = (176, 168, 148, 255)
# 특(lv8)·경(lv9) 전용 금빛 석재 — 대와 특, 특과 경이 크기만으로는 잘 안 갈려서 재질을 바꾼다.
GSTONE = (206, 172, 88, 255)
GSTONE_L = (240, 212, 132, 255)
GSTONE_D = (148, 116, 50, 255)
PALACE_ROOF = (150, 40, 44, 255)
PALACE_ROOF_L = (188, 66, 62, 255)
PALACE_ROOF_D = (98, 22, 26, 255)


class IsoCanvas:
    """RGBA 캔버스 + 아이소메트릭(2:1) 박스/지붕 프리미티브.

    좌표계: (cx, base_y) = 정면 아래 꼭짓점(벽 두 면이 만나는 바닥 점). 다이아
    지붕면은 그 위 (height + 2*hh) 만큼 떨어진 곳에 놓인다. 광원은 위쪽에서 —
    윗면이 가장 밝고, 좌면은 중간, 우면(정면 오른쪽)이 가장 어둡다.
    """

    def __init__(self, w: int, h: int) -> None:
        self.w, self.h = w, h
        self.img = Image.new("RGBA", (w, h), (0, 0, 0, 0))
        self.draw = ImageDraw.Draw(self.img)

    def box(self, cx: int, base_y: int, hw: int, hh: int, height: int,
            top, left, right, outline=OUTLINE) -> int:
        """returns top_y (지붕면 꼭짓점 y) — 위에 지붕/곁탑을 쌓을 때 쓴다."""
        top_y = base_y - height - 2 * hh
        apex = (cx, top_y)
        rightp = (cx + hw, top_y + hh)
        front = (cx, top_y + 2 * hh)
        leftp = (cx - hw, top_y + hh)
        top_face = [apex, rightp, front, leftp]
        left_face = [leftp, front, (cx, base_y), (cx - hw, base_y - hh)]
        right_face = [front, rightp, (cx + hw, base_y - hh), (cx, base_y)]
        self.draw.polygon(right_face, fill=right, outline=outline)
        self.draw.polygon(left_face, fill=left, outline=outline)
        self.draw.polygon(top_face, fill=top, outline=outline)
        return top_y

    def roof(self, cx: int, top_y: int, hw: int, hh: int, ridge: int,
              left, right, outline=OUTLINE) -> int:
        """박스의 지붕면 위에 얹는 뾰족 지붕(만든 두 사면만 보인다). returns apex y."""
        apex = (cx, top_y - ridge)
        front = (cx, top_y + 2 * hh)
        leftp = (cx - hw, top_y + hh)
        rightp = (cx + hw, top_y + hh)
        self.draw.polygon([front, rightp, apex], fill=right, outline=outline)
        self.draw.polygon([leftp, front, apex], fill=left, outline=outline)
        return apex[1]

    def merlons(self, cx: int, top_y: int, hw: int, hh: int, color, n: int = 5) -> None:
        """총안 — 지붕면 좌/우 능선 위에 작은 사각 돌기를 규칙적으로 얹는다."""
        for i in range(n):
            t = (i + 0.5) / n
            if t < 0.5:
                x = cx - hw + round(hw * (t * 2))
                y = top_y + hh - round(hh * (t * 2))
            else:
                t2 = (t - 0.5) * 2
                x = cx + round(hw * t2)
                y = top_y + round(hh * t2)
            self.draw.rectangle([x - 1, y - 2, x + 1, y], fill=color)

    def to_image(self) -> Image.Image:
        return self.img


def build_castle(hw: int, hh: int, height: int, ridge: int,
                  sc, sl, sd, rc, rl, rd, towers: bool) -> Image.Image:
    """5~9 — 성벽 박스 + 지붕 얹은 천수각. towers=True면 곁탑 두 개를 앞귀에 세운다."""
    keep_hw, keep_hh, keep_h = round(hw * 0.5), round(hh * 0.5), round(height * 0.55)
    w = 2 * hw + 2 * MARGIN
    h = height + 2 * hh + keep_h + 2 * keep_hh + ridge + 2 * MARGIN
    c = IsoCanvas(w, h)
    cx, base_y = w // 2, h - MARGIN
    top_y = c.box(cx, base_y, hw, hh, height, sl, sc, sd)
    c.merlons(cx, top_y, hw, hh, sl, n=7 if hw > 20 else 5)
    if towers:
        for side in (-1, 1):
            tx = cx + side * round(hw * 0.72)
            tb = base_y - hh + round(side * -0 + hh * 0.35)  # roughly on the wall slope
            tb = base_y - round(hh * 0.55)
            tt = c.box(tx, tb, 4, 2, round(height * 0.75), sl, sc, sd)
            c.roof(tx, tt, 4, 2, 5, rl, rd)
    keep_top = c.box(cx, top_y + hh, keep_hw, keep_hh, keep_h, rl if False else sl, sc, sd)
    c.roof(cx, keep_top, keep_hw, keep_hh, ridge, rl, rd)
    if height >= 20:
        mid = keep_top + round(keep_h * 0.5)
        c.roof(cx, mid, keep_hw, keep_hh, round(ridge * 0.4), rc, rd)
    return c.to_image()


def build_palace() -> Image.Image:
    """9 경 — 황도. 넓은 궁성 담장 + 중앙 대전(2단 지붕) + 좌우 곁전. 최대 규모."""
    hw, hh, height, ridge = 40, 18, 26, 12
    w = 2 * hw + 2 * MARGIN + 24
    h = height + 2 * hh + 30 + ridge + 2 * MARGIN
    c = IsoCanvas(w, h)
    cx, base_y = w // 2, h - MARGIN
    top_y = c.box(cx, base_y, hw, hh, height, GSTONE_L, GSTONE, GSTONE_D)
    c.merlons(cx, top_y, hw, hh, GSTONE_L, n=9)
    # 좌우 곁전 — 담장 위 앞귀에 얹은 작은 지붕 건물.
    for side in (-1, 1):
        wx = cx + side * round(hw * 0.6)
        wb = base_y - round(hh * 0.5)
        wt = c.box(wx, wb, 7, 4, 12, GSTONE_L, GSTONE, GSTONE_D)
        c.roof(wx, wt, 7, 4, 6, PALACE_ROOF_L, PALACE_ROOF_D)
    # 중앙 대전 — 2단 지붕의 가장 높은 건물.
    kt = c.box(cx, top_y + hh, 14, 7, 22, GSTONE_L, GSTONE, GSTONE_D)
    c.roof(cx, kt, 14, 7, round(ridge * 0.5), PALACE_ROOF_L, PALACE_ROOF_D)
    c.roof(cx, kt + 11, 16, 8, ridge, PALACE_ROOF, PALACE_ROOF_D)
    return c.to_image()


def build_county(level: int) -> Image.Image:
    """10 영현 / 11 장현 — 성벽 없는 초가 한두 채. 5(소)보다 작다.

    11(장현)이 훨씬 작다 — 380개로 최다수라 지도를 뒤덮으면 안 된다.
    """
    if level == 11:
        hw, hh, height, ridge = 8, 4, 5, 5
    else:  # 10 영현 — 11보다 크고 5보다 작다, 담장은 없지만 기단(基壇)이 있다.
        hw, hh, height, ridge = 12, 6, 8, 7
    w = 2 * hw + 2 * MARGIN
    h = height + 2 * hh + ridge + 2 * MARGIN
    c = IsoCanvas(w, h)
    cx, base_y = w // 2, h - MARGIN
    top_y = c.box(cx, base_y, hw, hh, height, STONE_L, STONE, STONE_D)
    c.roof(cx, top_y, hw, hh, ridge, THATCH_L if level == 11 else ROOF_L,
           THATCH_D if level == 11 else ROOF_D)
    return c.to_image()


def build_palisade() -> Image.Image:
    """2 진 — 뾰족한 통나무 목책 박스(지붕 없음) + 정면 성문."""
    hw, hh, height = 13, 7, 11
    w = 2 * hw + 2 * MARGIN
    h = height + 2 * hh + 4 + 2 * MARGIN
    c = IsoCanvas(w, h)
    cx, base_y = w // 2, h - MARGIN
    top_y = c.box(cx, base_y, hw, hh, height, WOOD_L, WOOD, WOOD_D)
    # 목책 끝을 톱니처럼 — 능선 위에 교대로 뾰족한 말뚝.
    for i in range(9):
        t = (i + 0.5) / 9
        if t < 0.5:
            x = cx - hw + round(hw * (t * 2))
            y = top_y + hh - round(hh * (t * 2))
        else:
            t2 = (t - 0.5) * 2
            x = cx + round(hw * t2)
            y = top_y + round(hh * t2)
        if i % 2 == 0:
            c.draw.line([(x, y), (x, y - 4)], fill=WOOD_D, width=1)
    gx, gy = cx, base_y
    c.draw.rectangle([gx - 2, gy - 6, gx + 2, gy], fill=GATE)
    return c.to_image()


def build_pass() -> Image.Image:
    """3 관 — 좌우 절벽 사이 관문. 폭 좁고 세로로 선 실루엣."""
    hw, hh, height = 14, 7, 16
    w = 2 * hw + 2 * MARGIN + 14
    h = height + 2 * hh + 8 + 2 * MARGIN
    c = IsoCanvas(w, h)
    cx, base_y = w // 2, h - MARGIN
    for side in (-1, 1):
        cxs = cx + side * round(hw * 1.15)
        c.box(cxs, base_y - 2, 6, 3, height + 4, STONE_D, STONE_D, (80, 74, 66, 255))
    top_y = c.box(cx, base_y, hw, hh, height, STONE_L, STONE, STONE_D)
    c.draw.rectangle([cx - 3, base_y - 7, cx + 3, base_y], fill=GATE)
    c.roof(cx, top_y, hw, hh, 8, ROOF_L, ROOF_D)
    return c.to_image()


def build_camp() -> Image.Image:
    """4 이 — 이민족 취락. 천막 두 채(성벽 없음) + 토템 기둥."""
    w, h = 46, 34
    c = IsoCanvas(w, h)
    base_y = h - MARGIN
    for cx, hw, hh, height in ((13, 8, 4, 6), (32, 6, 3, 5)):
        top_y = c.box(cx, base_y, hw, hh, height, TENT_L, TENT, TENT_D)
        c.roof(cx, top_y, hw, hh, height + 2, TENT, TENT_D)
    tx = 22
    c.draw.line([(tx, base_y - 26), (tx, base_y)], fill=WOOD_D, width=2)
    c.draw.rectangle([tx - 3, base_y - 26, tx + 3, base_y - 23], fill=WOOD)
    return c.to_image()


def build_port() -> Image.Image:
    """1 수(水) — 항구. 물 위 부두 + 돛단배. 성이 아니다."""
    w, h = 46, 30
    c = IsoCanvas(w, h)
    base_y = h - MARGIN
    # 물 — 넓적한 다이아 판.
    hw, hh = 22, 6
    c.draw.polygon(
        [(w // 2, base_y - 2 * hh), (w // 2 + hw, base_y - hh),
         (w // 2, base_y), (w // 2 - hw, base_y - hh)],
        fill=WATER, outline=OUTLINE,
    )
    c.draw.line([(w // 2 - hw + 3, base_y - hh), (w // 2 - 3, base_y - 2 * hh + 2)],
                fill=WATER_L, width=1)
    # 부두 — 물 왼쪽 위에 낮게 뜬 나무 판.
    dock_top = c.box(w // 2 - 8, base_y - hh - 1, 9, 4, 3, WOOD_L, WOOD, WOOD_D)
    # 배 — 부두 옆, 선체 + 돛대 + 돛.
    boat_cx = w // 2 + 6
    c.box(boat_cx, base_y - hh - 3, 8, 3, 3, WOOD_L, WOOD, WOOD_D)
    mast_x = boat_cx
    mast_top = base_y - hh - 3 - 6 - 10
    c.draw.line([(mast_x, mast_top), (mast_x, base_y - hh - 6)], fill=WOOD_D, width=1)
    for i in range(5):
        y = mast_top + 2 + i
        xr = mast_x + 2 + i
        c.draw.line([(mast_x + 1, y), (xr, y)], fill=SAIL if i < 4 else SAIL_D)
    return c.to_image()


CASTLE_SPEC = {
    5: dict(hw=17, hh=9, height=13, ridge=7, sc=STONE, sl=STONE_L, sd=STONE_D,
            rc=WOOD, rl=WOOD_L, rd=WOOD_D, towers=False),
    6: dict(hw=20, hh=10, height=17, ridge=8, sc=STONE, sl=STONE_L, sd=STONE_D,
            rc=ROOF, rl=ROOF_L, rd=ROOF_D, towers=False),
    7: dict(hw=23, hh=12, height=21, ridge=9, sc=STONE, sl=STONE_L, sd=STONE_D,
            rc=ROOF, rl=ROOF_L, rd=ROOF_D, towers=True),
    8: dict(hw=26, hh=13, height=25, ridge=10, sc=GSTONE, sl=GSTONE_L, sd=GSTONE_D,
            rc=ROOF, rl=ROOF_L, rd=ROOF_D, towers=True),
}


def build(level: int) -> Image.Image:
    if level == 1:
        return build_port()
    if level == 2:
        return build_palisade()
    if level == 3:
        return build_pass()
    if level == 4:
        return build_camp()
    if level in (10, 11):
        return build_county(level)
    if level == 9:
        return build_palace()
    spec = CASTLE_SPEC[level]
    return build_castle(spec["hw"], spec["hh"], spec["height"], spec["ridge"],
                         spec["sc"], spec["sl"], spec["sd"],
                         spec["rc"], spec["rl"], spec["rd"], spec["towers"])


LEVELS = (1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11)
# 미리보기는 시각적 무게 순서(작음→큼)로 늘어놓는다: 11 < 2 < 10 < 5 < 6 < 7 < 8 < 9,
# 1·3·4는 그 사이 어딘가에 형태별로 끼워 넣는다.
PREVIEW_ORDER = (11, 4, 1, 3, 2, 10, 5, 6, 7, 8, 9)


def preview_sheet(icons: dict[int, Image.Image]) -> Image.Image:
    s, gap = PREVIEW_SCALE, PREVIEW_GAP
    ordered = [icons[lv] for lv in PREVIEW_ORDER]
    w = sum(i.width * s for i in ordered) + gap * (len(ordered) + 1)
    h = max(i.height * s for i in ordered) + gap * 2
    sheet = Image.new("RGBA", (w, h), (30, 34, 30, 255))
    x = gap
    for img in ordered:
        scaled = img.resize((img.width * s, img.height * s), Image.NEAREST)
        sheet.alpha_composite(scaled, (x, h - gap - scaled.height))
        x += scaled.width + gap
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

    icons = {level: build(level) for level in LEVELS}
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
