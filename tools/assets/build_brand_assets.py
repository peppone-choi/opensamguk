#!/usr/bin/env python3
"""브랜드 에셋 파생 빌더.

마스터 하나(assets/brand/logo-master.png, 투명 배경 워드마크)에서 두 프런트엔드의
런타임 에셋을 전부 재생성한다. 마스터를 교체했으면 이 스크립트만 다시 돌리면 된다.

    python3 tools/assets/build_brand_assets.py
    python3 tools/assets/build_brand_assets.py --check   # 재생성 vs 디스크 바이트 비교, 드리프트면 비0 종료

산출물:
    web/{gateway,game}/app/icon.png             네이티브 해상도 三國 인장 (Next App Router 자동 배선)
    web/{gateway,game}/app/apple-icon.png       180px 동일 (다운스케일)
    web/{gateway,game}/app/favicon.ico          16/32/48 멀티사이즈 (패딩을 줄인 별도 타일)
    web/gateway/public/logo-wordmark.png       1200px 투명 워드마크 (game 은 소비처 없음)

인장 마크는 마스터 우측의 붉은 三國 낙관만 추출해 어두운 정사각 타일에 올린 것이다.
워드마크 전체를 파비콘 크기로 줄이면 '오픈삼국' 네 글자와 부제가 뭉개지므로 인장을 쓴다.
'픈'의 받침 ㄴ이 붓획에 묻혀 작은 크기에서 '프'로 읽히는 것도 같은 이유로 피한다.

인장 낙관 크롭은 104×167px뿐이다 — 마스터의 실제 해상도 상한이다. icon.png는 이 네이티브
해상도(패딩 포함 241×241)를 그대로 쓴다 — 업스케일하지 않는다(없는 디테일을 만들지 않는다).
favicon은 16px에서 조금이라도 더 읽히도록 패딩을 훨씬 줄인 별도 타일(193×193)에서 뽑는다.
그래도 16px에서 三國 두 글자 자체가 읽히지는 않는다 — assets/brand/README.md 참고.

logo-wordmark-light.png(흰 배경 합성본)는 만들지 않는다. 두 프런트엔드 다 다크 테마뿐이라
소비할 흰 배경 컨텍스트가 코드베이스에 없다 — 없는 소비처를 위해 산출물을 만들지 않는다.
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
# 이 개수 미만의 붉은 픽셀만 있는 행/열은 잡광으로 버린다.
# 현재 마스터에서 정답 상자(104x167)를 내는 구간은 floor 19~32 전 구간이다(전수 탐색 실측).
# 30은 그 구간 끝에 붙어 있어 여유가 -11/+2로 한쪽에 치우쳤었다 — 낙관 외곽선이 몇 px만
# 얇아져도 조용히 상자가 줄어들 수 있는데, xs/ys가 비지 않으니 SystemExit 가드를 안 탄다.
# 24는 여유가 -5/+8로 더 균등하다. seal_bounds()의 자기 검증(floor 여유 assert, 아래)이
# 쓰는 DENSITY_FLOOR+8(=32)는 안전 구간의 정확한 상한 — 여유 0이므로 +9면 지금 마스터에서도
# 죽는다(floor 33 → 상자가 달라진다). 실 파손보다 8단계 앞선 조기경보로서 유효하다.
DENSITY_FLOOR = 24
PAD_RATIO_ICON = 0.22  # icon.png/apple-icon.png 타일 패딩
PAD_RATIO_FAVICON = 0.08  # favicon.ico 타일 패딩 (16px에서 최대한 읽히도록 최소화)


def is_seal_pixel(r: int, g: int, b: int) -> bool:
    """붉은 낙관 획인가.

    `r - g`와 `r - b`만 보면 금박의 어두운 갈색(대표값 220/160/80)도 통과해서 옆 글자
    '국'의 ㄱ 획이 낙관과 함께 붉게 칠해져 들어온다. 낙관 붉은색(대표값 180/20/20)은
    g와 b가 **둘 다 낮고 서로 비슷하다**는 점이 금박과 다르므로, 그 두 조건을 같이 건다.

    이 판정식은 금박(옆 글자 '국')만 배제한다. **낙관과 다른 붉은 요소는 분리하지 못한다**
    — 마스터 중앙의 붉은 태양·깃발 상단도 이 조건을 통과한다(실측 33,898px 중 89.6%가
    낙관이 아닌 그 요소들). 낙관만 남기는 건 이 함수가 아니라 `seal_bounds`의 우측 30%
    탐색 창이다 — 창이 없으면 색만으로는 낙관을 특정하지 못한다.
    """
    return r > 110 and g < 90 and b < 90 and abs(g - b) < 40 and r - g > 60


def seal_bounds(master: Image.Image, floor: int = DENSITY_FLOOR) -> tuple[int, int, int, int]:
    """마스터에서 붉은 낙관의 경계를 찾는다.

    낙관은 워드마크 오른쪽 끝에 있다. `is_seal_pixel`은 낙관색과 다른 붉은 요소(중앙 태양,
    깃발)를 구별하지 못하므로, **우측 30% 탐색 창이 그 분리를 담당한다** — load-bearing이다.
    창 안에서 붉은 픽셀 밀도가 낮은 행/열만 걷어내(잡광 제거) 경계를 잡는다.
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
    xs = [x for x, count in cols.items() if count >= floor]
    ys = [y for y, count in rows.items() if count >= floor]
    if not xs or not ys:
        raise SystemExit("붉은 낙관을 찾지 못했다 — 마스터가 바뀌었으면 임계값을 다시 잡아라")
    bounds = (min(xs), min(ys), max(xs), max(ys))

    if floor == DENSITY_FLOOR:
        # 잡광 혼입 sanity: 낙관 상자는 작고 세로로 긴 직사각형이어야 한다. 이 조건을
        # 어기면(예: 중앙 태양·깃발까지 상자에 섞임) 색만으로는 안 죽으므로 여기서 죽는다.
        x0, y0, x1, y1 = bounds
        cw, ch = x1 - x0 + 1, y1 - y0 + 1
        area_ratio = (cw * ch) / (width * height)
        assert area_ratio < 0.02, f"낙관 상자가 너무 크다 — 잡광 혼입 의심: {cw}x{ch} ({area_ratio:.4f})"
        assert 0.4 < cw / ch < 0.9, f"낙관 종횡비 이탈: {cw / ch:.2f}"
        assert seal_bounds(master, floor=floor + 8) == bounds, "DENSITY_FLOOR 여유 부족 — 임계값을 다시 잡아라"
    return bounds


def build_seal_tile(master: Image.Image, pad_ratio: float) -> Image.Image:
    """낙관 획만 평탄한 붉은색으로 다시 칠해 어두운 정사각 타일에 올린다.

    원본 주변의 먹 번짐·금박 잡티를 함께 들고 오면 작은 크기에서 지저분해진다.
    타일은 크롭 네이티브 해상도 + 패딩 그대로 반환한다 — 호출부가 필요하면 다운스케일한다.
    업스케일은 절대 하지 않는다(없는 디테일을 만들지 않는다).
    """
    x0, y0, x1, y1 = seal_bounds(master)
    crop = master.crop((x0, y0, x1 + 1, y1 + 1))
    crop_w, crop_h = crop.size
    pad = round(max(crop_w, crop_h) * pad_ratio)
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
    return tile.convert("RGB")


def build() -> dict[Path, Image.Image | bytes]:
    """전체 산출물을 메모리에서 만든다. 저장 여부와 무관하게 재사용한다(--check용)."""
    if not MASTER.exists():
        raise SystemExit(f"마스터가 없다: {MASTER}")
    master = Image.open(MASTER).convert("RGBA")
    icon_tile = build_seal_tile(master, PAD_RATIO_ICON)
    favicon_tile = build_seal_tile(master, PAD_RATIO_FAVICON)
    apple_icon = icon_tile.resize((180, 180), Image.LANCZOS)
    wordmark = master.resize((1200, round(1200 * master.height / master.width)), Image.LANCZOS)

    outputs: dict[Path, Image.Image | bytes] = {}
    for app in APPS:
        app_dir = ROOT / "web" / app / "app"
        public_dir = ROOT / "web" / app / "public"
        if not app_dir.is_dir():
            raise SystemExit(f"앱 디렉터리가 없다: {app_dir}")
        outputs[app_dir / "icon.png"] = icon_tile
        outputs[app_dir / "apple-icon.png"] = apple_icon
        outputs[app_dir / "favicon.ico"] = favicon_tile
        # 워드마크는 gateway 의 로그인·가입 내비바에만 배선돼 있다. game 에는 소비처가
        # 없어서, 두 앱에 무조건 쓰면 700KB 짜리 미사용 에셋이 standalone 빌드에 실린다.
        if app == "gateway":
            outputs[public_dir / "logo-wordmark.png"] = wordmark
    return outputs


def _encode(path: Path, image: Image.Image) -> bytes:
    import io

    buf = io.BytesIO()
    if path.suffix == ".ico":
        image.save(buf, format="ICO", sizes=[(16, 16), (32, 32), (48, 48)])
    else:
        image.save(buf, format="PNG", optimize=True)
    return buf.getvalue()


def main() -> int:
    check = "--check" in sys.argv[1:]
    outputs = build()

    if check:
        drift = []
        for path, image in outputs.items():
            want = _encode(path, image)
            have = path.read_bytes() if path.exists() else None
            if have != want:
                drift.append(path)
        if drift:
            for path in drift:
                print(f"DRIFT: {path.relative_to(ROOT)}", file=sys.stderr)
            raise SystemExit(f"{len(drift)}개 산출물이 빌더 재생성 결과와 다르다 — 손편집됐거나 빌더를 안 돌렸다")
        print(f"brand assets check OK: {len(outputs)} files byte-match")
        return 0

    for path, image in outputs.items():
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(_encode(path, image))
    print(f"brand assets rebuilt for: {', '.join(APPS)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
