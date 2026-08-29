#!/usr/bin/env python3
"""한자 지명 → 한글 독음 사전 빌더.

`data/map/han-places.json`(군현·이민족 좌표, `nameCh`/`nameFt`)과
`data/map/junguozhi.json`(郡國志 郡 이름)에 나오는 모든 한자 지명을
`hanja` 패키지로 변환해 `data/map/readings.json` = {한자: 한글} 사전을 낸다.

`hanja` 는 간체·번체·두음법칙(邪→야 등 예외 제외)까지 처리하지만 왜(倭)·삼한
국명 같은 관용 표기는 원음 그대로 뱉는다 — 그런 것만 OVERRIDES 로 손으로 고정한다.
확신 없는 나머지는 hanja 출력을 그대로 믿는다(임의 추정 금지).

    python3 tools/map/build_readings.py
    python3 tools/map/build_readings.py --check   # 드리프트 검사
"""

from __future__ import annotations

import argparse
import json
import sys
import unicodedata
from pathlib import Path

try:
    import hanja
except ImportError:
    sys.exit(
        "hanja 패키지가 없다. `pip install hanja` (PEP668 로 막히면 venv 사용:\n"
        "  python3 -m venv /tmp/hjvenv && /tmp/hjvenv/bin/pip install hanja\n"
        "  /tmp/hjvenv/bin/python tools/map/build_readings.py )"
    )

ROOT = Path(__file__).resolve().parents[2]
MAP = ROOT / "data" / "map"
PLACES = MAP / "han-places.json"
JUNGUO = MAP / "junguozhi.json"
# 지역(태행산·화북평원 …) 이름 — Natural Earth 유래라 han-places/junguozhi 에는 없다.
# terrain-grid.json 이 있으면 곁다리로 읽는다(없어도 필수 입력 두 개만으로 동작).
TERRAIN = MAP / "terrain-grid.json"
OUT = MAP / "readings.json"

# 관용 표기 — hanja 기본 변환과 다르거나(邪→'사'가 아니라 '야'), 근거를 명시해야
# 하는 국명·지명. 확신 없는 것은 넣지 않는다 — hanja 출력을 그대로 둔다.
OVERRIDES: dict[str, str] = {
    # 倭 소국명, 邪 를 '야'로 읽는 관용(俗音) — 위지왜인전 통용 표기.
    "邪馬壹國": "야마일국",
    # 弁辰 安邪國 — 『삼국지』 한전의 국명. 여기서도 邪 는 俗音 '야'다.
    "安邪國": "안야국",
    "一大國": "일대국",
    "對馬國": "대마국",
    "伊都國": "이도국",
    "末盧國": "말로국",
    "奴國": "노국",
    # 유구(오키나와) — '류구'가 아니라 두음법칙 적용 '유구'가 통용 표기.
    "流求": "유구",
    "州胡": "주호",

    # 삼한 소국명 — 韓傳·『삼국유사』 五伽耶條. 邪 는 여기서도 俗音 '야'다.
    # 閒 은 間의 이체자다. hanja 는 「한」으로 읽지만 郡名은 河間國 = 하간국이다.
    "河閒國": "하간국",
    "河間國": "하간국",
    "國內城": "국내성",
    "狗邪國": "구야국",
    "目支國": "목지국",
    "辟卑離國": "벽비리국",
    "斯盧國": "사로국",
    "古資彌凍國": "고자미동국",
    "伯濟國": "백제국",
    # --- devsam che 맵의 이민족 거점 이름을 그대로 쓴다 -----------------------
    # 사료 이름(西羌·哀牢 …)이 더 정확하지만 게임에서 알아보기 어렵다. 원작 che 가
    # 쓰던 이름이 플레이어에게는 곧 그 세력이다(CityConst.kt:172-178 의 lv='이' 7城).
    # 한자 원문은 nameCh 로 남으니 출처는 사라지지 않는다.
    # 예외는 倭 다 — 邪馬壹國은 규슈설/기나이설이 갈리는 미결 비정이라(conf=DISPUTED)
    # '왜'라는 단정적인 이름을 붙이지 않고 사료 표기를 유지한다.
    "西羌": "강",          # che '강'
    "白馬氐": "저",        # che '저'
    "南匈奴": "흉노",      # che '흉노'
    "哀牢": "남만",        # che '남만' — 永昌郡 서쪽 哀牢夷
    "山越": "산월",        # che '산월' (독음 동일)
    "烏桓": "오환",        # che '오환' (독음 동일)
}


def unconverted_hanja(s: str) -> list[str]:
    """번역 후에도 한자로 남은 문자. 두음법칙/약자 처리 실패나 원본 데이터의
    깨진 조각(?, [], 부수만 남은 글자)을 잡아낸다."""
    out = []
    for ch in s:
        try:
            name = unicodedata.name(ch)
        except ValueError:
            continue
        if "CJK UNIFIED IDEOGRAPH" in name or "CJK COMPATIBILITY IDEOGRAPH" in name:
            out.append(ch)
    return out


def build() -> dict[str, str]:
    places = json.loads(PLACES.read_text())["places"]
    junguo = json.loads(JUNGUO.read_text())["places"]

    names: set[str] = set()
    for p in places:
        names.add(p["nameCh"])
        names.add(p["nameFt"])
    for j in junguo:
        names.add(j["name"])
    if TERRAIN.exists():
        try:
            for nm in json.loads(TERRAIN.read_text())["regionNames"]:
                names.add(nm["zh"] or nm["name"])
        except (json.JSONDecodeError, KeyError):
            pass  # 생성 중이라 반쪽 파일일 수 있다 — 다음 실행에서 다시 읽는다.
    names.discard("")

    readings: dict[str, str] = {}
    failures: dict[str, list[str]] = {}
    for name in sorted(names):
        if name in OVERRIDES:
            readings[name] = OVERRIDES[name]
            continue
        reading = hanja.translate(name, "substitution")
        readings[name] = reading
        bad = unconverted_hanja(reading)
        if bad:
            failures[name] = bad

    if failures:
        print(f"미변환 한자 {len(failures)}건:", file=sys.stderr)
        for name, chars in sorted(failures.items()):
            print(f"  {name!r} -> {readings[name]!r} (미변환: {''.join(chars)})", file=sys.stderr)

    return readings


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--check", action="store_true")
    args = ap.parse_args()
    for src in (PLACES, JUNGUO):
        if not src.exists():
            sys.exit(f"{src.relative_to(ROOT)} 가 없다.")
    readings = build()
    blob = json.dumps(readings, ensure_ascii=False, sort_keys=True, indent=2) + "\n"
    if args.check:
        if OUT.exists() and OUT.read_text() == blob:
            print("드리프트 없음.")
            return 0
        print(f"드리프트: {OUT.relative_to(ROOT)}")
        return 1
    OUT.write_text(blob)
    print(f"{OUT.relative_to(ROOT)} · {len(readings)}개 지명")
    return 0


if __name__ == "__main__":
    sys.exit(main())
