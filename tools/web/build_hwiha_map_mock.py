#!/usr/bin/env python3
"""목 작전실 지도용 MapPreviewResponse 를 저장소의 실제 城 표에서 만든다.

손으로 좌표를 쓰지 않으려고 둔 생성기다. 출처는 동결된 1133 판 城 표
`common/src/main/kotlin/opensamguk/common/constants/HanWorldV31133CityConst.kt` 이고,
등급 라벨은 `CityLevelList.kt` 의 정본 표로 푼다.

세력 배정은 **목 데이터**다. 200년 관도 무렵을 대충 흉내낸 것이며(조조: 사예·연주·예주·서주,
원소: 기주·청주·병주·유주) 고증이 아니다. 화면이 세력색·범례를 그리는지 보려고 둔 것이다.

    python3 tools/web/build_hwiha_map_mock.py            # 생성
    python3 tools/web/build_hwiha_map_mock.py --check     # 드리프트 검사
"""
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SRC = ROOT / "common/src/main/kotlin/opensamguk/common/constants/HanWorldV31133CityConst.kt"
LEVELS = ROOT / "common/src/main/kotlin/opensamguk/common/constants/CityLevelList.kt"
OUT = ROOT / "web/game/lib/hwiha-map-mock.ts"

# 목 세력 배정 — 고증이 아니다.
CAO = {"사예", "연주", "예주", "서주"}
YUAN = {"기주", "청주", "병주", "유주"}

# RawCity(id, "이름", "등급", n×6, "州", x, y, listOf(이웃…), "郡 縣")
#
# 정규식으로 한 번에 긁으면 이웃 목록 안의 괄호("장릉(京兆尹)")에 걸려 城 을 조용히 잃는다
# (1133 → 454 로 떨어진 적이 있다). 괄호 균형을 세어 호출 하나를 통째로 떼고 필드를 센다.
QUOTED = re.compile(r'"((?:[^"\\]|\\.)*)"')


def raw_city_calls(src: str):
    """`RawCity(` 하나하나를 괄호 균형으로 떼어 인자 문자열을 넘긴다."""
    needle = "RawCity("
    at = src.find(needle)
    while at != -1:
        i = at + len(needle)
        depth = 1
        while i < len(src) and depth:
            ch = src[i]
            if ch == '"':  # 문자열 안의 괄호는 세지 않는다
                i += 1
                while i < len(src) and src[i] != '"':
                    i += 2 if src[i] == "\\" else 1
            elif ch == "(":
                depth += 1
            elif ch == ")":
                depth -= 1
            i += 1
        yield src[at + len(needle) : i - 1]
        at = src.find(needle, i)


def parse_city(args: str):
    """한 RawCity 인자에서 (id, 이름, 등급, 州, x, y, 「郡 縣」) 를 뽑는다."""
    head = args.split("listOf(", 1)[0]
    strings = QUOTED.findall(head)
    if len(strings) < 3:
        return None
    name, label, region = strings[0], strings[1], strings[2]
    nums = re.findall(r"(?<![\w.])(-?\d+)", head)
    if len(nums) < 9:
        return None
    cid, x, y = int(nums[0]), int(nums[-2]), int(nums[-1])
    tail = QUOTED.findall(args[args.rindex(")") :]) if ")" in args else []
    admin = tail[-1] if tail else ""
    return cid, name, label, region, x, y, admin


def level_map() -> dict[str, int]:
    text = LEVELS.read_text()
    return {label: int(num) for num, label in re.findall(r'(\d+) to "([^"]+)"', text)}


def build() -> dict:
    levels = level_map()
    src = SRC.read_text()
    cities = []
    for args in raw_city_calls(src):
        parsed = parse_city(args)
        if parsed is None:
            continue
        cid, name, label, region, x, y, admin = parsed
        if label not in levels:
            raise SystemExit(f"등급 라벨 '{label}' 이 CityLevelList 에 없다 — 표가 갈라졌다")
        nation = 1 if region in CAO else 2 if region in YUAN else 0
        # 「경조윤 장안현」 → 郡 = 경조윤, 縣 = 장안현. 한 낱말만 있으면 縣 으로 본다.
        parts = admin.split()
        commandery = parts[0] if len(parts) > 1 else ""
        county = parts[-1] if parts else name
        cities.append(
            {
                "id": cid,
                # 지도에는 縣 이름만 적는다(사용자 지시) — 동명이지 구분 郡 은 이름에 넣지 않는다.
                "name": county,
                "level": levels[label],
                "nationId": nation,
                "x": x,
                "y": y,
                "state": 0,
                "supply": nation != 0,
                # 州 → 郡 → 縣. 상세 화면은 이 세 단계를 적는다.
                "province": region,
                "commandery": commandery,
                "county": county,
            }
        )
    if not cities:
        raise SystemExit("城 을 하나도 파싱하지 못했다 — 표 형식이 바뀌었다")
    return {
        "serverName": "휘하 목 서버",
        "year": 200,
        "month": 3,
        "turnPhase": 2,
        "turnPhaseText": "중순",
        "mapCode": "han-world-v3",
        "width": max(c["x"] for c in cities) + 32,
        "height": max(c["y"] for c in cities) + 32,
        "cities": cities,
        "nations": [
            {"id": 1, "name": "조조", "color": "#c9a656"},
            {"id": 2, "name": "원소", "color": "#7aa7c7"},
        ],
    }


def render(data: dict) -> str:
    body = json.dumps(data, ensure_ascii=False, separators=(",", ":"))
    return (
        "// GENERATED — `python3 tools/web/build_hwiha_map_mock.py` 산출물이다. 손으로 고치지 마라.\n"
        "//\n"
        "// 출처: 동결된 1133 판 城 표(HanWorldV31133CityConst.kt) + 등급 정본(CityLevelList.kt).\n"
        "// **세력 배정은 목 데이터다** — 200년 관도 무렵을 흉내낸 것이고 고증이 아니다.\n"
        "// API 가 붙으면 화면은 그대로 두고 이 파일을 걷어낸다.\n"
        "import type { MapPreviewResponse } from './types';\n\n"
        f"export const HWIHA_MAP_MOCK = {body} as unknown as MapPreviewResponse;\n"
    )


def main() -> int:
    text = render(build())
    if "--check" in sys.argv:
        if not OUT.exists() or OUT.read_text() != text:
            print(f"STALE {OUT.relative_to(ROOT)}")
            return 1
        print(f"OK {OUT.relative_to(ROOT)}")
        return 0
    OUT.write_text(text)
    print(f"wrote {OUT.relative_to(ROOT)} ({len(text)} bytes)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
