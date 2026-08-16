#!/usr/bin/env python3
"""브랜드 에셋 파생 빌더.

마스터 하나(assets/brand/logo-master.png, 투명 배경 워드마크)에서 두 프런트엔드의
런타임 에셋을 전부 재생성한다. 마스터를 교체했으면 이 스크립트만 다시 돌리면 된다.

    python3 tools/assets/build_brand_assets.py

산출물:
    web/{gateway,game}/app/icon.png                   512px 三國 인장 (Next App Router 자동 배선)
    web/{gateway,game}/app/apple-icon.png             180px 동일
    web/{gateway,game}/app/favicon.ico                16/32/48 멀티사이즈
    web/{gateway,game}/public/logo-wordmark.png       1200px 투명 워드마크
    web/{gateway,game}/public/logo-wordmark-light.png 흰 배경 합성본

인장 마크는 마스터 우측의 붉은 三國 낙관만 추출해 어두운 정사각 타일에 올린 것이다.
워드마크 전체를 파비콘 크기로 줄이면 '오픈삼국' 네 글자와 부제가 뭉개지므로 인장을 쓴다.
'픈'의 받침 ㄴ이 붓획에 묻혀 작은 크기에서 '프'로 읽히는 것도 같은 이유로 피한다.
"""

from __future__ import annotations

import sys
from collections import Counter
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parents[2]
MASTER = ROOT / "assets/brand/logo-master.png"
APPS = ("gateway", "game")

PLATE = (15, 13, 12, 255)  # 인장 타일 배경 (거의 검정)
SEAL_RGB = (198, 32, 38)  # 낙관 붉은색 정규화 값
DENSITY_FLOOR = 30  # 이 개수 미만의 붉은 픽셀만 있는 행/열은 잡광으로 버린다


def is_seal_pixel(r: int, g: int, b: int) -> bool:
    """붉은 낙관 획인가.

    `r - g`와 `r - b`만 보면 금박의 어두운 갈색(대표값 220/160/80)도 통과해서 옆 글자
    '국'의 ㄱ 획이 낙관과 함께 붉게 칠해져 들어온다. 낙관 붉은색(대표값 180/20/20)은
    g와 b가 **둘 다 낮고 서로 비슷하다**는 점이 금박과 다르므로, 그 두 조건을 같이 건다.
    """
    return r > 110 and g < 90 and b < 90 and abs(g - b) < 40 and r - g > 60


def seal_bounds(master: Image.Image) -> tuple[int, int, int, int]:
    """마스터에서 붉은 낙관의 경계를 찾는다.

    낙관은 워드마크 오른쪽 끝에 있다. 우측 30% 안에서 붉은 픽셀 밀도가 높은 행/열만
    남겨 경계를 잡는다. 밀도 필터가 칼날 반사나 배경 잔불 같은 붉은 잡광을 걸러낸다.
    """
    width, height = master.size
    px = master.load()
    search_from = int(width * 0.7)
    cols: Counter[int] = Counter()
    rows: Counter[int] = Counter()
    for y in range(height):
        for x in range(search_from, width):
            r, g, b, a = px[x, y]
            if a > 8 and is_seal_pixel(r, g, b):
                cols[x] += 1
                rows[y] += 1
    xs = [x for x, count in cols.items() if count >= DENSITY_FLOOR]
    ys = [y for y, count in rows.items() if count >= DENSITY_FLOOR]
    if not xs or not ys:
        raise SystemExit("붉은 낙관을 찾지 못했다 — 마스터가 바뀌었으면 임계값을 다시 잡아라")
    return min(xs), min(ys), max(xs), max(ys)


def build_seal_tile(master: Image.Image) -> Image.Image:
    """낙관 획만 평탄한 붉은색으로 다시 칠해 어두운 정사각 타일에 올린다.

    원본 주변의 먹 번짐·금박 잡티를 함께 들고 오면 작은 크기에서 지저분해진다.
    """
    x0, y0, x1, y1 = seal_bounds(master)
    crop = master.crop((x0, y0, x1 + 1, y1 + 1))
    crop_w, crop_h = crop.size
    pad = round(max(crop_w, crop_h) * 0.3)
    side = max(crop_w, crop_h) + pad * 2

    glyph = Image.new("RGBA", (crop_w, crop_h), (0, 0, 0, 0))
    src = crop.load()
    dst = glyph.load()
    for y in range(crop_h):
        for x in range(crop_w):
            r, g, b, a = src[x, y]
            if a <= 8 or not is_seal_pixel(r, g, b):
                continue
            dst[x, y] = (*SEAL_RGB, min(255, round((r - max(g, b)) * 2.2)))

    tile = Image.new("RGBA", (side, side), PLATE)
    tile.alpha_composite(glyph, ((side - crop_w) // 2, (side - crop_h) // 2))
    return tile.resize((512, 512), Image.LANCZOS).convert("RGB")


def main() -> int:
    if not MASTER.exists():
        raise SystemExit(f"마스터가 없다: {MASTER}")
    master = Image.open(MASTER).convert("RGBA")
    seal = build_seal_tile(master)

    wordmark = master.resize((1200, round(1200 * master.height / master.width)), Image.LANCZOS)
    wordmark_light = Image.new("RGBA", wordmark.size, (255, 255, 255, 255))
    wordmark_light.alpha_composite(wordmark)

    for app in APPS:
        app_dir = ROOT / "web" / app / "app"
        public_dir = ROOT / "web" / app / "public"
        if not app_dir.is_dir():
            raise SystemExit(f"앱 디렉터리가 없다: {app_dir}")
        public_dir.mkdir(parents=True, exist_ok=True)

        seal.save(app_dir / "icon.png")
        seal.resize((180, 180), Image.LANCZOS).save(app_dir / "apple-icon.png")
        seal.save(app_dir / "favicon.ico", sizes=[(16, 16), (32, 32), (48, 48)])
        wordmark.save(public_dir / "logo-wordmark.png")
        wordmark_light.convert("RGB").save(public_dir / "logo-wordmark-light.png")

    print(f"brand assets rebuilt for: {', '.join(APPS)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
