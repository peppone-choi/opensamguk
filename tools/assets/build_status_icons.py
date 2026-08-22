#!/usr/bin/env python3
"""도시 상태/수도별 아이콘 빌더 (자작 · 절차적 · 결정적).

`web/{gateway,game}/public/status/state-<code>.png`(12장) + `star-capital.png`(1장)와
검수 시트 `assets/brand/status-icons/preview.png`를 생성한다. 입력 이미지는 없다 —
`build_city_icons.py`와 같은 방식으로 픽셀아트를 코드로 직접 그린다.

    python3 tools/assets/build_status_icons.py
    python3 tools/assets/build_status_icons.py --check   # 손편집 드리프트 검사, 불일치면 비0 종료

이 스크립트가 존재하는 이유: `MapViewer.tsx`의 도시 재해/사건 배지(`event<state>.gif`)와
수도 별(`event51.gif`)이 아직 CDN(`opensamguk-images` = `devsam/image` 미러)을 본다.
그 출처에는 LICENSE가 없어 권리가 UNKNOWN이다
(`docs/superpowers/research/2026-08-17-asset-license-audit.md` §1-2) — 도시 아이콘·깃발과
같은 판단으로 자작 전환한다.

## state 코드 조사 (추측 아님, 코드 근거)

`MapPreviewController.kt:108-109` 주석: "재해/사건 코드(city.state) — func_map.php:145-147
tuple state자리". `city.state` 는 `func_map.php` 가 원값 그대로 클라이언트에 넘기는
raw DB 컬럼이고, 값의 의미는 그걸 쓰는 로직 쪽에서만 확인된다. 이 저장소에 실제 이식된
쓰기 지점을 전수 조사한 값만 아이콘화한다 — 그 외 코드값은 UNKNOWN 으로 남긴다:

  - `RaiseDisaster.kt` (분기별 재해/풍작 이벤트, `state<=10` 은 매 분기 0으로 리셋):
      1  풍작(호황, 7월) · 2  호황(4월) · 3  혹한/한파/폭설(1·10월)
      4  역병(1월) · 5  지진(전분기) · 6  태풍(4월) · 7  홍수(4월)
      8  흉년/메뚜기(7월) · 9  황건적 출현(1·10월)
  - `CheHwagye.kt:262`/`CheSeondong.kt:257` — 화계(방화)·선동(소요) 성공 시 `state=32`
    (둘 다 같은 코드를 공유 — PHP 원본이 두 명령에 같은 표시를 쓴다).
  - `CheTalchwi.kt:294,301` — 탈취(약탈) 성공 시 `state=34`("탈취 상태").
  - `CheChulbyeong.kt:216` — 출병(진군) 목적지 도시에 `state=43, term=3`.

  51 은 상태 코드가 아니라 수도 배지(`event51.gif`, 위 조사 범위 밖 — 이 빌더가 별도로
  `star-capital.png` 로 만든다).

작게 렌더되므로(성 아이콘 우상단 배지) 색만으로 구분하지 않는다 — 실루엣 자체를 다르게
그린다: 곡물단(풍작) / 동전(호황) / 눈송이(혹한) / 물방울+십자(역병) / 균열(지진) /
소용돌이(태풍) / 파도(홍수) / 시든 이삭(흉년) / 불타는 두건(황건적) / 불꽃(화계·선동) /
자루(탈취) / 창끝(출병). 수도 별은 5각 별로 별도.
"""

from __future__ import annotations

import argparse
import io
import math
import sys
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parents[2]
APPS = ("gateway", "game")
PREVIEW = ROOT / "assets" / "brand" / "status-icons" / "preview.png"
PREVIEW_SCALE = 8
PREVIEW_GAP = 4

SIZE = 15  # 레거시 event*.gif 자연 크기(MapViewer.tsx STATE_PX 계산 기준값).
STAR_SIZE = 10  # 레거시 event51.gif 자연 크기(MapViewer.tsx STAR_PX 계산 기준값).

OUTLINE = (18, 16, 14, 255)


class Canvas:
    """알파 포함 픽셀 격자. 범위 밖 쓰기는 조용히 버린다."""

    def __init__(self, w: int, h: int) -> None:
        self.w, self.h = w, h
        self.px: dict[tuple[int, int], tuple[int, int, int, int]] = {}

    def set(self, x: int, y: int, color) -> None:
        if 0 <= x < self.w and 0 <= y < self.h:
            self.px[(x, y)] = color

    def rect(self, x0: int, y0: int, x1: int, y1: int, color) -> None:
        for y in range(y0, y1 + 1):
            for x in range(x0, x1 + 1):
                self.set(x, y, color)

    def line(self, x0: int, y0: int, x1: int, y1: int, color) -> None:
        """Bresenham."""
        dx, dy = abs(x1 - x0), -abs(y1 - y0)
        sx, sy = (1 if x0 < x1 else -1), (1 if y0 < y1 else -1)
        err = dx + dy
        x, y = x0, y0
        while True:
            self.set(x, y, color)
            if x == x1 and y == y1:
                break
            e2 = 2 * err
            if e2 >= dy:
                err += dy
                x += sx
            if e2 <= dx:
                err += dx
                y += sy

    def circle(self, cx: int, cy: int, r: int, color, fill=True) -> None:
        for y in range(cy - r, cy + r + 1):
            for x in range(cx - r, cx + r + 1):
                d = math.hypot(x - cx, y - cy)
                if fill and d <= r + 0.3:
                    self.set(x, y, color)
                elif not fill and r - 0.8 <= d <= r + 0.3:
                    self.set(x, y, color)

    def outline(self) -> None:
        """채워진 픽셀에 4-이웃한 빈 픽셀을 외곽선으로 만든다."""
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


# 팔레트.
GREEN, GREEN_D = (150, 186, 66, 255), (98, 128, 38, 255)
GOLD, GOLD_D = (232, 190, 74, 255), (168, 128, 36, 255)
ICE, ICE_D = (168, 220, 240, 255), (110, 170, 208, 255)
PLAGUE, PLAGUE_D = (150, 92, 168, 255), (98, 52, 116, 255)
ROCK, ROCK_D = (150, 116, 78, 255), (98, 72, 46, 255)
TEAL, TEAL_D = (78, 168, 168, 255), (44, 116, 116, 255)
WAVE, WAVE_D = (86, 138, 196, 255), (48, 92, 148, 255)
WILT, WILT_D = (168, 140, 62, 255), (110, 88, 34, 255)
TURBAN, TURBAN_D = (214, 172, 40, 255), (176, 52, 40, 255)
FIRE, FIRE_D = (232, 128, 40, 255), (176, 60, 24, 255)
SACK, SACK_D = (150, 108, 62, 255), (98, 68, 36, 255)
SPEAR, SPEAR_D = (200, 200, 208, 255), (120, 40, 36, 255)
STAR_GOLD, STAR_GOLD_D = (250, 214, 96, 255), (188, 138, 30, 255)


def draw_1_bumper(c: Canvas) -> None:
    """풍작 — 초록 곡물단(부채꼴로 벌어진 이삭 다발 + 허리끈)."""
    base = (7, 12)
    for tip in ((3, 3), (5, 2), (7, 1), (9, 2), (11, 3)):
        c.line(base[0], base[1], tip[0], tip[1], GREEN)
        c.set(tip[0], tip[1], GREEN_D)  # 이삭 머리
    c.rect(4, 10, 10, 11, GREEN_D)  # 허리끈


def draw_2_boom(c: Canvas) -> None:
    """호황 — 금화 세 닢이 쌓인 모양."""
    for cy, r in ((10, 4), (7, 4), (4, 3)):
        c.circle(7, cy, r, GOLD)
    for cy, r in ((10, 4), (7, 4), (4, 3)):
        c.circle(7, cy, r, GOLD_D, fill=False)


def draw_3_cold(c: Canvas) -> None:
    """혹한 — 6방향 눈송이."""
    cx, cy, r = 7, 7, 6
    for k in range(6):
        a = k * math.pi / 3
        x1, y1 = round(cx + r * math.cos(a)), round(cy + r * math.sin(a))
        c.line(cx, cy, x1, y1, ICE)
        mx, my = round(cx + r * 0.6 * math.cos(a)), round(cy + r * 0.6 * math.sin(a))
        for sa in (a + 0.7, a - 0.7):
            sx, sy = round(mx + 2 * math.cos(sa)), round(my + 2 * math.sin(sa))
            c.line(mx, my, sx, sy, ICE_D)
    c.set(cx, cy, ICE)


def draw_4_plague(c: Canvas) -> None:
    """역병 — 자주색 물방울(독기)에 십자(치료 불가 표식)."""
    for y in range(3, 13):
        half = max(1, 5 - abs(y - 8) // 2) if y > 5 else max(0, (y - 3))
        c.rect(7 - half, y, 7 + half, y, PLAGUE)
    c.rect(6, 6, 8, 6, PLAGUE_D)
    c.rect(7, 4, 7, 8, PLAGUE_D)


def draw_5_quake(c: Canvas) -> None:
    """지진 — 갈라진 대지."""
    c.rect(1, 10, 13, 13, ROCK)
    zig = [(1, 10), (4, 6), (6, 9), (9, 4), (11, 8), (13, 5)]
    for (x0, y0), (x1, y1) in zip(zig, zig[1:]):
        c.line(x0, y0, x1, y1, ROCK_D)
        c.line(x0, y0 + 1, x1, y1 + 1, ROCK_D)


def draw_6_typhoon(c: Canvas) -> None:
    """태풍 — 소용돌이."""
    cx, cy = 7, 7
    prev = None
    for i in range(70):
        t = i * 0.28
        r = 0.4 + t * 0.62
        x, y = round(cx + r * math.cos(t)), round(cy + r * math.sin(t))
        if r > 6.4:
            break
        if prev and prev != (x, y):
            c.line(prev[0], prev[1], x, y, TEAL if i % 6 < 3 else TEAL_D)
        prev = (x, y)


def draw_7_flood(c: Canvas) -> None:
    """홍수 — 파도 세 줄."""
    for row, y0 in enumerate((4, 8, 11)):
        for x in range(1, 14):
            up = math.sin(x * 0.9 + row) > 0
            c.set(x, y0 + (0 if up else 1), WAVE if row < 2 else WAVE_D)
    c.rect(1, 12, 13, 13, WAVE_D)


def draw_8_famine(c: Canvas) -> None:
    """흉년/메뚜기 — 고개 꺾여 늘어진 시든 이삭(두꺼운 줄기)."""
    for dx in (0, 1):  # 줄기(아래쪽 곧음) — 2px 굵게
        c.line(6 + dx, 13, 7 + dx, 8, WILT_D)
        c.line(7 + dx, 8, 10 + dx, 5, WILT_D)  # 목이 꺾여 늘어진 부분
    # 늘어진 이삭 머리 — 낟알 덩어리.
    c.rect(9, 3, 12, 6, WILT)
    c.rect(9, 3, 12, 3, WILT_D)
    for (x, y) in ((3, 10), (4, 8)):  # 시들어 처진 잎 — 굵게
        c.rect(x, y, x + 1, y + 1, WILT_D)


def draw_9_turban(c: Canvas) -> None:
    """황건적 출현 — 불타는 두건(황색 띠 + 붉은 화염)."""
    c.rect(3, 7, 11, 9, TURBAN)
    c.rect(3, 9, 11, 9, TURBAN_D)
    for cx in (4, 7, 10):
        c.line(cx, 7, cx - 1, 3, FIRE)
        c.line(cx, 7, cx + 1, 3, FIRE_D)


def draw_32_fire(c: Canvas) -> None:
    """화계/선동 — 불꽃 하나."""
    c.line(7, 13, 7, 9, FIRE_D)
    pts = [(7, 2), (5, 5), (7, 4), (9, 6), (7, 8), (5, 9), (7, 11), (9, 9), (7, 2)]
    for (x0, y0), (x1, y1) in zip(pts, pts[1:]):
        c.line(x0, y0, x1, y1, FIRE)
    c.rect(6, 6, 8, 8, FIRE_D)


def draw_34_plunder(c: Canvas) -> None:
    """탈취 — 묶은 자루."""
    c.line(6, 3, 8, 3, SACK_D)
    for y in range(4, 13):
        half = min(y - 3, 13 - y) // 1
        half = max(1, min(5, half + 2))
        c.rect(7 - half, y, 7 + half, y, SACK)
    c.rect(4, 11, 10, 12, SACK_D)
    c.rect(6, 3, 8, 4, SACK_D)


def draw_43_march(c: Canvas) -> None:
    """출병(진군) — 붉은 창끝."""
    c.line(7, 13, 7, 5, SPEAR_D)
    c.line(7, 1, 4, 6, SPEAR)
    c.line(7, 1, 10, 6, SPEAR)
    c.line(4, 6, 10, 6, SPEAR)
    c.rect(6, 6, 8, 6, SPEAR_D)


STATE_BUILDERS = {
    1: draw_1_bumper,
    2: draw_2_boom,
    3: draw_3_cold,
    4: draw_4_plague,
    5: draw_5_quake,
    6: draw_6_typhoon,
    7: draw_7_flood,
    8: draw_8_famine,
    9: draw_9_turban,
    32: draw_32_fire,
    34: draw_34_plunder,
    43: draw_43_march,
}


def build_state(code: int) -> Image.Image:
    c = Canvas(SIZE, SIZE)
    STATE_BUILDERS[code](c)
    c.outline()
    return c.to_image()


def build_capital_star() -> Image.Image:
    """수도 별 — 금색 5각 별(event51.gif 대체)."""
    c = Canvas(STAR_SIZE, STAR_SIZE)
    cx, cy, r_out, r_in = 5, 5, 4.6, 1.9
    pts = []
    for k in range(10):
        r = r_out if k % 2 == 0 else r_in
        a = -math.pi / 2 + k * math.pi / 5
        pts.append((cx + r * math.cos(a), cy + r * math.sin(a)))
    # 다각형을 스캔라인 채우기.
    ys = [p[1] for p in pts]
    for y in range(math.floor(min(ys)), math.ceil(max(ys)) + 1):
        xs = []
        for (x0, y0), (x1, y1) in zip(pts, pts[1:] + pts[:1]):
            if (y0 <= y < y1) or (y1 <= y < y0):
                t = (y - y0) / (y1 - y0)
                xs.append(x0 + t * (x1 - x0))
        xs.sort()
        for i in range(0, len(xs) - 1, 2):
            c.rect(round(xs[i]), y, round(xs[i + 1]), y, STAR_GOLD)
    c.outline()
    for (x, y) in list(c.px):
        if c.px[(x, y)] == OUTLINE:
            c.px[(x, y)] = STAR_GOLD_D
    return c.to_image()


def preview_sheet(icons: dict[str, Image.Image]) -> Image.Image:
    s, gap = PREVIEW_SCALE, PREVIEW_GAP
    w = sum(i.width * s for i in icons.values()) + gap * (len(icons) + 1)
    h = max(i.height * s for i in icons.values()) + gap * 2
    sheet = Image.new("RGBA", (w, h), (24, 24, 28, 255))
    x = gap
    for key in sorted(icons, key=lambda k: (k != "capital", k)):
        img = icons[key]
        img = img.resize((img.width * s, img.height * s), Image.NEAREST)
        sheet.alpha_composite(img, (x, h - gap - img.height))
        x += img.width + gap
    return sheet


def png_bytes(img: Image.Image) -> bytes:
    buf = io.BytesIO()
    img.save(buf, "PNG", optimize=True)
    return buf.getvalue()


def targets(icons: dict[str, Image.Image]) -> dict[Path, bytes]:
    out: dict[Path, bytes] = {}
    for key, img in icons.items():
        data = png_bytes(img)
        name = "star-capital.png" if key == "capital" else f"state-{key}.png"
        for app in APPS:
            out[ROOT / "web" / app / "public" / "status" / name] = data
    out[PREVIEW] = png_bytes(preview_sheet(icons))
    return out


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--check", action="store_true", help="쓰지 않고 기존 파일과 바이트 비교")
    args = ap.parse_args()

    icons: dict[str, Image.Image] = {str(code): build_state(code) for code in STATE_BUILDERS}
    icons["capital"] = build_capital_star()
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
