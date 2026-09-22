#!/usr/bin/env python3
"""목 작전실의 군국 표를 저장소 실측에서 만든다.

전장의 안개와 8방향 이동이 군국 단위이므로 화면은 「군국 번호 → 이름 · 치소 칸」을 알아야 한다.
번호는 프로빈스 식별 PNG 가 쓰는 색인과 같다 — `han-tiles.json` 의 `juns` 순서에 1을 더한 값이다
(0 은 덮이지 않은 칸을 뜻한다, `build_province_map.py` 의 codec).

    python3 tools/web/build_hwiha_commanderies.py [--check]
"""
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
TILES = ROOT / "data/map/han-tiles.json"
MAP_MOCK = ROOT / "web/game/lib/hwiha-map-mock.ts"
OUT = ROOT / "web/game/lib/hwiha-commanderies.ts"


def game_city_ids() -> tuple[dict[tuple[str, str], int], dict[str, int]]:
    """(郡, 縣) → 게임 城 번호.

    `juns[].seat` 은 han-tiles 의 `cities` 배열 **색인**이고 게임 城 번호가 아니다(양적현은 색인
    1056, 게임 번호 122). 지도 초점은 게임 번호로 잡으므로 이름으로 이어 준다. 縣 이름은 동명이지가
    있어 郡 과 짝으로 맞춘다.
    """
    import re

    text = MAP_MOCK.read_text()
    match = re.search(r"= (\{.*\}) as unknown", text, re.S)
    if match is None:
        raise SystemExit(f"{MAP_MOCK.name} 에서 목 지도를 읽지 못했다 — 먼저 build_hwiha_map_mock.py 를 돌려라")
    data = json.loads(match.group(1))
    pairs = {(c["commandery"], c["county"]): c["id"] for c in data["cities"]}
    # 치소 이름이 郡 이름과 같은 군국(城 없는 郡)은 (郡, 縣) 짝으로 안 걸린다. 縣 이름만으로도
    # 찾을 수 있게 하되, **이름이 유일할 때만** 쓴다 — 동명이지를 아무 데나 이으면 초점이 엉뚱해진다.
    seen: dict[str, list[int]] = {}
    for c in data["cities"]:
        seen.setdefault(c["county"], []).append(c["id"])
    unique = {name: ids[0] for name, ids in seen.items() if len(ids) == 1}
    return pairs, unique


def build() -> list[dict]:
    tiles = json.loads(TILES.read_text())
    juns = tiles["juns"]
    tile_cities = tiles["cities"]
    by_pair, by_unique_name = game_city_ids()
    rows = []
    missing = []
    for index, jun in enumerate(juns):
        col, row = jun.get("col"), jun.get("row")
        if col is None or row is None:
            continue  # 치소 칸이 없는 군국은 8방향 기준점이 없다 — 지어내지 않고 건너뛴다
        seat_index = jun.get("seat")
        seat_name = tile_cities[seat_index]["name"] if isinstance(seat_index, int) and seat_index < len(tile_cities) else None
        seat_city_id = (by_pair.get((jun["name"], seat_name)) or by_unique_name.get(seat_name)) if seat_name else None
        if seat_city_id is None:
            missing.append(f"{jun['name']}/{seat_name}")
            continue  # 초점 잡을 城 을 못 찾으면 넣지 않는다 — 엉뚱한 곳을 비추는 것보다 낫다
        rows.append(
            {
                # `provinceMap.commanderies` 와 같은 번호다 — 클라이언트 디코더가 1 을 빼므로
                # juns 색인 그대로(0-based)이고, 덮이지 않은 칸은 음수로 온다.
                "no": index,
                "name": jun["name"],
                "col": int(col),
                "row": int(row),
                # 치소의 게임 城 번호 — 지도를 그 군국으로 옮길 때 초점으로 쓴다.
                "seat": seat_city_id,
            }
        )
    if not rows:
        raise SystemExit("juns 에서 군국을 하나도 읽지 못했다")
    if missing:
        print(f"치소 城 을 못 이은 군국 {len(missing)}: {', '.join(missing[:6])}", file=sys.stderr)
    return rows


def render(rows: list[dict]) -> str:
    body = json.dumps(rows, ensure_ascii=False, separators=(",", ":"))
    return (
        "// GENERATED — `python3 tools/web/build_hwiha_commanderies.py` 산출물이다. 손으로 고치지 마라.\n"
        "//\n"
        "// 군국 번호는 프로빈스 식별 PNG 의 색인과 같다(han-tiles.json 의 juns 순서 + 1).\n"
        "// 전장의 안개와 8방향 이동이 군국 단위이므로 화면이 이 표를 본다.\n\n"
        "export interface HwihaCommandery {\n"
        "    /** `provinceMap.commanderies` 와 같은 군국 번호(juns 색인, 0-based). */\n"
        "    readonly no: number;\n"
        "    readonly name: string;\n"
        "    /** 치소가 선 칸 — 8방향에서 어느 군국이 이웃인지 고르는 기준점이다. */\n"
        "    readonly col: number;\n"
        "    readonly row: number;\n"
        "    /** 치소 城 번호 — 지도 초점으로 쓴다. */\n"
        "    readonly seat: number;\n"
        "}\n\n"
        f"export const HWIHA_COMMANDERIES: readonly HwihaCommandery[] = {body};\n"
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
