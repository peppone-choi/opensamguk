#!/usr/bin/env python3
"""pnpm audit --json 결과를 잡 요약(Markdown)으로 줄인다 — 보고 전용(ci.yml deps-audit 잡, 2026-10-04 K10).

    python3 tools/ci/deps_audit_summary.py <pnpm-audit.json> [pnpm audit 종료 코드]

심각도별 개수와 권고 수만 적는다(패키지 이름 · 내용은 artifact 의 JSON 에 있다). 결과를 읽지 못해도(레지스트리 · 네트워크 오류)
그렇다고 적고 종료 코드는 늘 0 이다 — 이 단계는 잡을 실패시키지 않는다. 막을지는 기준선을 본 뒤 정한다.
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

SEVERITIES = ("critical", "high", "moderate", "low", "info")


def summarize(raw: str, exit_code: str | None = None) -> str:
    head = "## 의존성 감사 — web 운영 의존성(pnpm audit --prod, 보고만)\n\n"
    tail = f"\npnpm audit 종료 코드 {exit_code}." if exit_code is not None else ""
    try:
        data = json.loads(raw)
    except (json.JSONDecodeError, ValueError):
        return head + "감사 결과를 읽지 못했다(JSON 아님 — 레지스트리 · 네트워크 오류일 수 있다). artifact 의 pnpm-audit.err 를 본다." + tail + "\n"
    if not isinstance(data, dict) or "metadata" not in data:
        reason = data.get("error", {}).get("message") if isinstance(data, dict) and isinstance(data.get("error"), dict) else None
        return head + f"감사 결과에 metadata 가 없다{f'({reason})' if reason else ''}. artifact 를 본다." + tail + "\n"
    counts = data["metadata"].get("vulnerabilities", {})
    advisories = data.get("advisories", {})
    deps = data["metadata"].get("dependencies")
    row = " | ".join(str(counts.get(s, 0)) for s in SEVERITIES)
    blocking = counts.get("critical", 0) + counts.get("high", 0)
    return (
        head
        + "| " + " | ".join(SEVERITIES) + " | 권고 | 의존성 |\n"
        + "|" + "---|" * (len(SEVERITIES) + 2) + "\n"
        + f"| {row} | {len(advisories)} | {deps if deps is not None else '?'} |\n\n"
        + f"high 이상 {blocking}건 — 지금은 막지 않는다. 자세한 내용은 artifact `deps-audit-*` 의 pnpm-audit.json." + tail + "\n"
    )


def main(argv: list[str]) -> int:
    if len(argv) < 2:
        print("사용: deps_audit_summary.py <pnpm-audit.json> [종료 코드]", file=sys.stderr)
        return 0
    path = Path(argv[1])
    raw = path.read_text(encoding="utf-8") if path.exists() else ""
    print(summarize(raw, argv[2] if len(argv) > 2 else None), end="")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
