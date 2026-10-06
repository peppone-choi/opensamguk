#!/usr/bin/env python3
"""입력 원장의 전달 상태 스냅숏을 만든다 — web/game/lib/input-delivery.generated.ts.

서버 입력 가능 여부(계약판 K6-01 `GET /api/inputs/availability`)가 생기기 전까지 화면은 이 스냅숏으로
원장 PLANNED 입력을 「준비 중」(NOT_DELIVERED)으로 가린다. 원장에 없는 입력은 그리지 않는다.

원장(data/commands/input-catalog.json)이 바뀌면 한 번 돌린다:
    python3 tools/web/gen_input_delivery.py
어긋남은 web/game/__tests__/input-availability.test.ts 가 CI 에서 잡는다. --check 는 파일을 쓰지 않고 비교만 한다.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CATALOG = ROOT / "data/commands/input-catalog.json"
OUT = ROOT / "web/game/lib/input-delivery.generated.ts"


def render() -> str:
    rows = json.loads(CATALOG.read_text(encoding="utf-8"))["inputs"]
    lines = [
        "// 생성 파일 — 손으로 고치지 않는다. 원장이 바뀌면: python3 tools/web/gen_input_delivery.py",
        "// 출처: data/commands/input-catalog.json (inputId → deliveryState). 대조: __tests__/input-availability.test.ts",
        "export type DeliveryState = 'PLANNED' | 'HANDLER_READY' | 'UI_READY';",
        "",
        "export const INPUT_DELIVERY: Readonly<Record<string, DeliveryState>> = {",
    ]
    for row in sorted(rows, key=lambda r: r["inputId"]):
        lines.append(f"    '{row['inputId']}': '{row['deliveryState']}',")
    lines += ["};", ""]
    return "\n".join(lines)


def main() -> int:
    text = render()
    if "--check" in sys.argv[1:]:
        if not OUT.exists() or OUT.read_text(encoding="utf-8") != text:
            print(f"{OUT.relative_to(ROOT)} 이 원장과 다릅니다 — python3 tools/web/gen_input_delivery.py 를 돌리세요.", file=sys.stderr)
            return 1
        return 0
    OUT.write_text(text, encoding="utf-8")
    print(f"wrote {OUT.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
