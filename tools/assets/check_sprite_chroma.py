#!/usr/bin/env python3
"""생성 raw 의 크로마 배경 검사 (OPENSAM-209).

생성기가 요청한 마젠타 대신 검정/그라디언트 배경을 돌려주는 일이 실측으로 있었다
(2026-08-21 부곡 노병). 그 raw 는 추출 단계에서 배경이 안 지워져 통째로 불량이 되므로,
126장을 눈으로 넘기지 말고 여기서 걸러 재생성 목록을 만든다.

    python3 tools/assets/check_sprite_chroma.py <raw-dir>          # 불량 키를 stdout 에
"""
import sys, pathlib
from PIL import Image

# 네 모서리 표본이 전부 마젠타여야 통과. 순수 #FF00FF 를 요구하면 생성기의 미세한
# 색 흔들림에 걸리므로 R/B 높고 G 낮음이라는 색상 조건으로 본다.
def is_magenta(px) -> bool:
    r, g, b = px[:3]
    return r > 180 and b > 180 and g < 90


def bad(path: pathlib.Path) -> str | None:
    im = Image.open(path).convert("RGB")
    w, h = im.size
    pts = [(2, 2), (w - 3, 2), (2, h - 3), (w - 3, h - 3), (w // 2, 2), (w // 2, h - 3)]
    miss = [p for p in pts if not is_magenta(im.getpixel(p))]
    return f"배경 비마젠타 {len(miss)}/{len(pts)}점 (예: {im.getpixel(miss[0])})" if miss else None


def main() -> int:
    raw = pathlib.Path(sys.argv[1])
    fails = 0
    for f in sorted(raw.glob("*.png")):
        if f.name.endswith(".raw.png"):
            continue
        why = bad(f)
        if why:
            fails += 1
            print(f"{f.stem}\t{why}", file=sys.stderr)
            print(f.stem)
    print(f"검사 {len(list(raw.glob('*.png')))//2}장 · 불량 {fails}장", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
