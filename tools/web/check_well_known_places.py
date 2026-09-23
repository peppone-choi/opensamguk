#!/usr/bin/env python3
"""이름만 적는 지명 목록이 실제 데이터와 맞는지 본다.

`web/shared/src/iso/wellKnownPlaces.ts` 의 (郡, 縣) 짝이 서버가 쓰는 han-world-v3 城 표에 **정확히 그 짝으로**
있어야 한다. 짝이 없으면 아무 城 도 안 걸려 조용히 무동작이 되고, 그러면 목록이 썩은 것을 아무도
모른다. 지도 판이 바뀌거나 郡 표기가 바뀔 때 이 검사가 걸린다.

    python3 tools/web/check_well_known_places.py
"""
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
LIST = ROOT / "web/shared/src/iso/wellKnownPlaces.ts"
CITY_TABLE = ROOT / "infra/src/main/resources/map/han-world-v3.json"


def listed_pairs() -> list[tuple[str, str]]:
    text = LIST.read_text()
    body = re.search(r"const WELL_KNOWN = new Set\(\[(.*?)\]\)", text, re.S)
    if body is None:
        raise SystemExit(f"{LIST.name} 에서 목록을 읽지 못했다")
    return [
        (m.group(1), m.group(2))
        for m in re.finditer(r"'([^'\\]+)\\t([^']+)'", body.group(1))
    ]


def data_pairs() -> set[tuple[str, str]]:
    data = json.loads(CITY_TABLE.read_text())
    # meta.displayName = 「郡 縣」(한글). meta.seat 은 **郡 치소**라 이 城 의 縣 이 아니다 — 쓰지 않는다.
    pairs = set()
    for c in data["cities"]:
        parts = str((c.get("meta") or {}).get("displayName") or "").split()
        if len(parts) >= 2:
            pairs.add((parts[0], parts[-1]))
    return pairs


def main() -> int:
    listed = listed_pairs()
    if not listed:
        print("목록이 비었다 — 정규식이나 파일 형식이 바뀌었다", file=sys.stderr)
        return 1
    have = data_pairs()
    missing = [pair for pair in listed if pair not in have]
    for jun, county in missing:
        print(f"MISSING {jun} {county} — 이 짝이 城 표에 없어 아무 城 도 걸리지 않는다", file=sys.stderr)
    print(f"{'FAIL' if missing else 'OK'} 이름만 적는 지명 {len(listed)} 짝 중 {len(listed) - len(missing)} 확인")
    return 1 if missing else 0


if __name__ == "__main__":
    raise SystemExit(main())
