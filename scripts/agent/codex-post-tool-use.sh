#!/usr/bin/env bash
set -euo pipefail

ROOT="$(git rev-parse --show-toplevel)"
cd "$ROOT"

SUMMARY="$(scripts/agent/verify-changes.sh | sed -n '/== 필요한 최소 검증/,$p')"
printf '%s' "$SUMMARY" | python3 -c '
import json
import sys

summary = sys.stdin.read()
message = (
    "Codex Agent OS verification reminder. Run $os-verify or "
    "scripts/agent/verify-changes.sh --run before completion.\n"
    + summary[:3500]
)
print(json.dumps({"systemMessage": message}, ensure_ascii=False))
'
