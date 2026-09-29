#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
compose_file="$repo_root/docker-compose.sandbox.yml"
workflow_file="${SANDBOX_CI_WORKFLOW_FILE:-$repo_root/.github/workflows/ci.yml}"
test_root="$(mktemp -d /tmp/opensamguk-sandbox-compose-contract.XXXXXX)"
env_file="$test_root/sandbox.env"
rendered_file="$test_root/rendered.json"

cleanup() {
  if [[ -e "$env_file" ]]; then
    unlink "$env_file"
  fi
  if [[ -e "$rendered_file" ]]; then
    unlink "$rendered_file"
  fi
  rmdir "$test_root"
}
trap cleanup EXIT

printf '%s\n' \
  'SANDBOX_SCENARIO_CODE=scenario_990002' \
  'SANDBOX_SCENARIO_HOST_DIR=./data/scenarios' \
  'SANDBOX_POSTGRES_PASSWORD=placeholder-sandbox-postgres-password' \
  'SHARED_GATEWAY_NETWORK=opensamguk-shared-gateway' \
  'SHARED_GATEWAY_UPSTREAM=opensamguk-gateway-api:8080' \
  'SHARED_GATEWAY_PROFILE_ICONS_VOLUME=opensamguk-profile-icons' \
  'JWT_PUBLIC_KEY=placeholder-public-key' > "$env_file"

docker compose --env-file "$env_file" -f "$compose_file" config --format json > "$rendered_file"

python3 - "$rendered_file" "$workflow_file" <<'PY'
import json
import re
import sys
from pathlib import Path

rendered = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
web_game = rendered.get("services", {}).get("web-game")
if web_game is None:
    raise SystemExit("FAIL: rendered compose is missing web-game")

asset_prefix = web_game.get("build", {}).get("args", {}).get("ASSET_PREFIX")
if asset_prefix != "/game":
    raise SystemExit(
        f"FAIL: web-game build.args.ASSET_PREFIX must be '/game', got {asset_prefix!r}"
    )

workflow = Path(sys.argv[2]).read_text(encoding="utf-8")
# 잡 **이름**을 박아 두면 이름을 바꾸는 순간 계약이 깨진다(2026-08-24: agent-system
# -> contracts 개명에 이 검사가 걸렸다). 지키려던 건 이름이 아니라 「CI 의 어떤
# 잡이 이 계약을 실제로 돌린다」이므로 그걸 그대로 검사한다 — 이름은 자유롭게
# 두되 **정확히 한 잡**이 돌려야 한다(0 이면 안 돌고, 2 면 어느 쪽이 정본인지
# 모른다).
invocation = re.compile(
    r"(?m)^        run:[ \t]+bash[ \t]+tools/ops/sandbox_compose_contract_test\.sh[ \t]*(?:#[^\r\n]*)?$"
)
jobs = re.findall(
    r"(?ms)^  (?P<name>[A-Za-z0-9_-]+):\n(?P<body>.*?)(?=^  [A-Za-z0-9_-]+:\n|\Z)",
    workflow,
)
runners = [name for name, body in jobs if invocation.search(body)]
if not runners:
    raise SystemExit(
        "FAIL: no ci.yml job runs bash tools/ops/sandbox_compose_contract_test.sh"
    )
if len(runners) > 1:
    raise SystemExit(
        "FAIL: more than one ci.yml job runs the sandbox compose contract: "
        + ", ".join(runners)
    )

print("PASS: rendered sandbox web-game build passes ASSET_PREFIX=/game and CI invokes the contract")
PY
