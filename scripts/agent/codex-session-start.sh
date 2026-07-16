#!/usr/bin/env bash
set -euo pipefail

ROOT="$(git rev-parse --show-toplevel)"
cd "$ROOT"

scripts/agent/project-skills.sh restore --soft

cat <<'EOF'
OpenSamguk Agent OS is active for Codex.
Before non-trivial work, read .ai/task.md, .ai/decisions.md, and docs/agent/project-overview.md, then route through docs/agent/README.md.
Use the project $os-* skills for workflow entry points. Never commit, push, merge, deploy, delete data, or access secrets without explicit user approval.
Before completion, run $os-verify or scripts/agent/verify-changes.sh --run and report executed versus unexecuted checks.
EOF
