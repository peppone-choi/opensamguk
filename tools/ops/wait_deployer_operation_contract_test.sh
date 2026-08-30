#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
HELPER="$ROOT/tools/ops/wait_deployer_operation.sh"
TEST_TMP="$(mktemp -d)"
trap 'rm -rf "$TEST_TMP"' EXIT

if [[ "$(basename "$0")" == curl ]]; then
  output_file=""
  while (($#)); do
    case "$1" in
      --output)
        output_file="$2"
        shift 2
        ;;
      *) shift ;;
    esac
  done
  [[ -n "$output_file" ]] || exit 91
  curl_config="$(sed -n '1,20p')"
  [[ "$curl_config" == *'Authorization: Bearer contract-token'* ]] || exit 92
  count="$(cat "$FAKE_CURL_COUNT")"
  count=$((count + 1))
  printf '%s\n' "$count" >"$FAKE_CURL_COUNT"
  response="$(sed -n "${count}p" "$FAKE_CURL_RESPONSES")"
  [[ -n "$response" ]] || exit 22
  printf '%s\n' "$response" >"$output_file"
  exit 0
fi

if [[ ! -x "$HELPER" ]]; then
  echo "missing executable helper: $HELPER" >&2
  exit 1
fi

mkdir -p "$TEST_TMP/bin"
ln -s "$ROOT/tools/ops/wait_deployer_operation_contract_test.sh" "$TEST_TMP/bin/curl"
export PATH="$TEST_TMP/bin:$PATH"
export CONTRACT_TOKEN="contract-token"
export FAKE_CURL_COUNT="$TEST_TMP/curl-count"
export FAKE_CURL_RESPONSES="$TEST_TMP/curl-responses"
operation_id="0123456789abcdef0123456789abcdef"

run_case() {
  local name="$1"
  local responses="$2"
  local expected_exit="$3"
  local expected_calls="$4"
  local output="$TEST_TMP/$name.output"
  printf '0\n' >"$FAKE_CURL_COUNT"
  printf '%s\n' "$responses" >"$FAKE_CURL_RESPONSES"
  local deadline=$(( $(date +%s) + 30 ))
  set +e
  "$HELPER" "http://deployer.invalid:9000" "env:CONTRACT_TOKEN" \
    "$operation_id" "$deadline" 0 >"$output" 2>&1
  local exit_code=$?
  set -e
  if [[ "$exit_code" -ne "$expected_exit" ]]; then
    echo "$name exit=$exit_code, want $expected_exit" >&2
    sed -n '1,80p' "$output" >&2
    exit 1
  fi
  if [[ "$(cat "$FAKE_CURL_COUNT")" -ne "$expected_calls" ]]; then
    echo "$name curl calls=$(cat "$FAKE_CURL_COUNT"), want $expected_calls" >&2
    exit 1
  fi
  if grep -q 'body-secret' "$output"; then
    echo "$name leaked a response body" >&2
    exit 1
  fi
}

run_case success_after_recovery_required \
  "{\"operationId\":\"$operation_id\",\"status\":\"pending\",\"publicMessage\":\"body-secret-1\"}
{\"operationId\":\"$operation_id\",\"status\":\"running\",\"publicMessage\":\"body-secret-running\"}
{\"operationId\":\"$operation_id\",\"status\":\"recovery_required\",\"publicMessage\":\"body-secret-2\"}
{\"operationId\":\"$operation_id\",\"status\":\"succeeded\",\"publicMessage\":\"body-secret-3\"}" \
  0 4
run_case terminal_failed \
  "{\"operationId\":\"$operation_id\",\"status\":\"failed\",\"publicMessage\":\"body-secret-failed\"}" \
  1 1
run_case malformed_response '{"operationId":"wrong","status":"succeeded","publicMessage":"body-secret-malformed"}' 1 1
run_case malformed_json '{not-json' 1 1
run_case missing_response '{}' 1 1

python3 - "$ROOT/.github/workflows/reset-game-server.yml" <<'PY'
from pathlib import Path
import sys

workflow = Path(sys.argv[1]).read_text(encoding="utf-8")
generated = workflow.find('export OPERATION_ID=')
probe = workflow.find('/operations/$OPERATION_ID')
exact_not_found = workflow.find('set(payload) == {"ok", "operationId", "status"}')
mutation = workflow.find('/servers/reset')
if min(generated, probe, exact_not_found, mutation) < 0:
    raise SystemExit("reset workflow is missing the durable capability probe contract")
if not (generated < probe < exact_not_found < mutation):
    raise SystemExit("reset workflow capability probe must precede the destructive reset POST")
for required in (
    '--request GET',
    'http_status != "404"',
    '"ok": False',
    '"status": "not_found"',
    'deployer does not provide the required durable operation contract; reset was not submitted',
):
    if required not in workflow[generated:mutation]:
        raise SystemExit(f"reset capability probe is missing fail-closed contract marker: {required}")

for required in (
    "create_backup:",
    'default: true',
    'type: boolean',
    'INPUT_CREATE_BACKUP: ${{ inputs.create_backup }}',
    'CREATE_BACKUP="$INPUT_CREATE_BACKUP"',
    'if [[ "$CREATE_BACKUP" == "true" ]]; then',
    'Backup explicitly disabled for $PUBLIC_SERVER',
):
    if required not in workflow:
        raise SystemExit(f"reset workflow is missing optional-backup contract marker: {required}")

backup_guards = []
offset = 0
while True:
    found = workflow.find('if [[ "$CREATE_BACKUP" == "true" ]]; then', offset)
    if found < 0:
        break
    backup_guards.append(found)
    offset = found + 1
if len(backup_guards) != 3:
    raise SystemExit(f"reset workflow expected 3 create-backup guards, found {len(backup_guards)}")

env_guard, data_guard, completion_guard = backup_guards
backup_dir = workflow.find('BACKUP_DIR="$HOME/opensamguk-backups/${PUBLIC_SERVER}/${TS}"')
env_copy = workflow.find('sudo cp "$ENV_FILE" "$BACKUP_DIR/${PUBLIC_SERVER}.env"')
scenario_export = workflow.find('if [[ -n "${RTK14_STATS_JSON_B64:-}" ]]')
pg_dump = workflow.find('pg_dump -U')
redis_save = workflow.find('redis-cli SAVE')
redis_copy = workflow.find('docker cp "${REDIS_CONTAINER}:/data" "$BACKUP_DIR/redis-data"')
backup_disabled = workflow.find('Backup explicitly disabled for $PUBLIC_SERVER')
if min(backup_dir, env_copy, scenario_export, pg_dump, redis_save, redis_copy, backup_disabled) < 0:
    raise SystemExit("reset workflow optional-backup branch is incomplete")
if not (env_guard < backup_dir < env_copy < scenario_export < data_guard):
    raise SystemExit("reset workflow must guard the backup directory and environment-file copy")
if not (data_guard < pg_dump < redis_save < redis_copy < backup_disabled < completion_guard):
    raise SystemExit("reset workflow must guard PostgreSQL and Redis backup mutations before the no-backup branch")
if 'else\n            echo "=== Backup explicitly disabled for $PUBLIC_SERVER ==="\n          fi' not in workflow:
    raise SystemExit("reset workflow no-backup branch must be the alternative to guarded data backups")
PY

echo "wait_deployer_operation contract tests: PASS"
