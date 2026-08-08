#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
compose_file="$repo_root/docker-compose.v2-sandbox.yml"
workflow_file="$repo_root/.github/workflows/ci.yml"
test_root="$(mktemp -d /tmp/opensamguk-v2-compose-contract.XXXXXX)"
env_file="$test_root/v2-sandbox.env"
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
  'V2_SCENARIO_CODE=scenario_v2_probe' \
  'V2_SCENARIO_HOST_DIR=./data/scenarios' \
  'V2_POSTGRES_PASSWORD=placeholder-v2-postgres-password' \
  'SHARED_GATEWAY_NETWORK=opensamguk-shared-gateway' \
  'SHARED_GATEWAY_UPSTREAM=opensamguk-gateway-api:8080' \
  'SHARED_GATEWAY_PROFILE_ICONS_VOLUME=opensamguk-profile-icons' \
  'JWT_SECRET=placeholder-jwt-secret' > "$env_file"

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
agent_system_job = re.search(
    r"(?ms)^  agent-system:\n(?P<body>.*?)(?=^  [A-Za-z0-9_-]+:\n|\Z)",
    workflow,
)
if agent_system_job is None:
    raise SystemExit("FAIL: ci.yml is missing the agent-system job")

invocation = "run: bash tools/ops/v2_sandbox_compose_contract_test.sh"
if invocation not in agent_system_job.group("body"):
    raise SystemExit("FAIL: agent-system job must run the v2 sandbox compose contract")

print("PASS: rendered v2 web-game build passes ASSET_PREFIX=/game and CI invokes the contract")
PY
