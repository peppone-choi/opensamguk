#!/usr/bin/env python3
"""pnpm audit --json 결과를 잡 요약(Markdown) 한 단락으로 줄인다 — 보고 전용(ci.yml deps-audit 잡, 2026-10-04 K10).

    python3 tools/ci/deps_audit_summary.py <pnpm-audit.json> [pnpm audit 종료 코드] [--counts]

기본은 「감사가 돌았다 · 결과를 읽었다 · 종료 코드」만 적는다(결과 파일은 올리지 않는다). --counts 를 주면 심각도별 개수
표를 더한다(패키지 이름 · 내용은 어느 쪽에도 적지 않는다). 결과를 읽지 못해도(레지스트리 · 네트워크 오류) 그렇다고 적고 종료
코드는 늘 0 이다 — 이 단계는 잡을 실패시키지 않는다.
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

SEVERITIES = ("critical", "high", "moderate", "low", "info")


def summarize(raw: str, exit_code: str | None = None, counts: bool = False) -> str:
    head = "## 의존성 감사 — web 운영 의존성(pnpm audit --prod, 보고만)\n\n"
    tail = f"\npnpm audit 종료 코드 {exit_code}." if exit_code is not None else ""
    try:
        data = json.loads(raw)
    except (json.JSONDecodeError, ValueError):
        return head + "감사 결과를 읽지 못했다(JSON 아님 — 레지스트리 · 네트워크 오류일 수 있다)." + tail + "\n"
    if not isinstance(data, dict) or "metadata" not in data:
        reason = data.get("error", {}).get("message") if isinstance(data, dict) and isinstance(data.get("error"), dict) else None
        return head + f"감사 결과에 metadata 가 없다{f'({reason})' if reason else ''}." + tail + "\n"
    if not counts:
        return head + "감사가 돌았고 결과를 읽었다(개수 · 내용은 적지 않는다)." + tail + "\n"
    found = data["metadata"].get("vulnerabilities", {})
    advisories = data.get("advisories", {})
    deps = data["metadata"].get("dependencies")
    row = " | ".join(str(found.get(s, 0)) for s in SEVERITIES)
    blocking = found.get("critical", 0) + found.get("high", 0)
    return (
        head
        + "| " + " | ".join(SEVERITIES) + " | 권고 | 의존성 |\n"
        + "|" + "---|" * (len(SEVERITIES) + 2) + "\n"
        + f"| {row} | {len(advisories)} | {deps if deps is not None else '?'} |\n\n"
        + f"high 이상 {blocking}건 — 지금은 막지 않는다." + tail + "\n"
    )


def main(argv: list[str]) -> int:
    args = [a for a in argv[1:] if a != "--counts"]
    if not args:
        print("사용: deps_audit_summary.py <pnpm-audit.json> [종료 코드] [--counts]", file=sys.stderr)
        return 0
    path = Path(args[0])
    raw = path.read_text(encoding="utf-8") if path.exists() else ""
    print(summarize(raw, args[1] if len(args) > 1 else None, counts="--counts" in argv[1:]), end="")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
