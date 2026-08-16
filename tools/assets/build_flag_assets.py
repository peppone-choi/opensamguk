#!/usr/bin/env python3
"""도시 깃발 에셋 빌더 (자작 · 결정적).

`web/{gateway,game}/public/flags/flag-{cloth,pole}-{0..3}.png` 16장을 생성한다.
입력 이미지는 없다 — 12x12 픽셀아트를 사인파로 직접 그린다.

    python3 tools/assets/build_flag_assets.py

이 스크립트가 존재하는 이유: 이전 에셋은 devsam `game/fFF0000.gif`에서 PIL로 추출한
파생물이었고 원본 리포에 LICENSE가 없어 권리가 UNKNOWN이었다
(`docs/superpowers/research/2026-08-17-asset-license-audit.md` §1-2). 12x12 스프라이트는
절차적으로 다시 그리는 편이 권리 확인보다 싸다.

런타임 계약은 그대로다(`web/*/lib/flagTint.ts`):
  cloth = 회색조 명도만 담은 천. nation 색으로 캔바스 틴트된다.
  pole  = 정적 깃대. 틴트 없이 천 위에 합성된다.
  프레임 4장이 나부낌 1주기.
"""

from __future__ import annotations

import math
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parents[2]
APPS = ("gateway", "game")

SIZE = 12
FRAMES = 4

POLE_X = 3  # 깃대 기둥 열
CLOTH_X0, CLOTH_X1 = 4, 11  # 천이 걸리는 열 범위(양끝 포함)
CLOTH_Y0, CLOTH_Y1 = 2, 9  # 나부낌 0일 때의 천 행 범위(양끝 포함)

WAVE_AMP = 1.4  # 열마다의 상하 나부낌 진폭(px)
WAVE_K = 0.62  # 열당 위상 증가 — 천 하나에 파형 약 1주기가 보이게
FOLD_LO, FOLD_HI = 0.34, 0.74  # 주름 명도 하한/상한(틴트 앵커가 이 안에서 눌림)
EDGE_L = 0.18  # 천 위/아래 가장자리(어두운 테두리) 명도

POLE_WOOD = (247, 211, 148, 255)  # 깃대 목재
POLE_EDGE = (96, 56, 17, 255)  # 깃대 어두운 외곽선
POLE_TIP = (249, 247, 247, 255)  # 창끝


def cloth_frame(frame: int) -> Image.Image:
    """한 프레임의 천. 알파는 실루엣, RGB는 회색조 명도(주름)만 담는다."""
    img = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    px = img.load()
    phase = frame * (2 * math.pi / FRAMES)
    for x in range(CLOTH_X0, CLOTH_X1 + 1):
        theta = phase + (x - CLOTH_X0) * WAVE_K
        # 깃대에서 멀수록 크게 나부낀다(깃대 쪽 고정단은 거의 움직이지 않는다).
        reach = (x - CLOTH_X0) / (CLOTH_X1 - CLOTH_X0)
        shift = round(math.sin(theta) * WAVE_AMP * reach)
        y0, y1 = CLOTH_Y0 + shift, CLOTH_Y1 + shift
        # 주름: 파형의 기울기가 클수록(=천이 꺾이는 곳) 어둡게.
        fold = (math.cos(theta) + 1) / 2
        lum = FOLD_LO + (FOLD_HI - FOLD_LO) * fold
        for y in range(max(0, y0), min(SIZE - 1, y1) + 1):
            edge = y in (y0, y1)
            v = round((EDGE_L if edge else lum) * 255)
            px[x, y] = (v, v, v, 255)
    return img


def pole_image() -> Image.Image:
    """정적 깃대 한 장. 4프레임 모두 같은 그림을 쓴다(깃대는 나부끼지 않는다)."""
    img = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    px = img.load()
    for y in range(1, SIZE - 1):
        px[POLE_X - 1, y] = POLE_EDGE
        px[POLE_X, y] = POLE_WOOD
    px[POLE_X, 0] = POLE_TIP  # 창끝
    px[POLE_X - 1, SIZE - 1] = POLE_EDGE  # 밑동
    px[POLE_X, SIZE - 1] = POLE_EDGE
    return img


def main() -> int:
    out_dirs = [ROOT / f"web/{app}/public/flags" for app in APPS]
    for d in out_dirs:
        d.mkdir(parents=True, exist_ok=True)
    pole = pole_image()
    for frame in range(FRAMES):
        cloth = cloth_frame(frame)
        for d in out_dirs:
            cloth.save(d / f"flag-cloth-{frame}.png", optimize=True)
            pole.save(d / f"flag-pole-{frame}.png", optimize=True)
    print(f"wrote {FRAMES * 2 * len(out_dirs)} files under {', '.join(str(d) for d in out_dirs)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
