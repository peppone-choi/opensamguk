#!/usr/bin/env bash

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
ALERTER="$REPO_ROOT/tools/ops/daemon_health_alert.sh"
WORKFLOW="$REPO_ROOT/.github/workflows/daemon-health-alert.yml"
DEPLOY_WORKFLOW="$REPO_ROOT/.github/workflows/deploy.yml"
PRODUCTION_COMPOSE="$REPO_ROOT/docker-compose.production.yml"
TEST_ROOT="$(mktemp -d /tmp/opensamguk-daemon-alert-contract.XXXXXX)"
STUB_BIN="$TEST_ROOT/bin"
ALERT_PAYLOAD_LOG="$TEST_ROOT/alert-payload.log"
DEPLOY_FUNCTIONS="$TEST_ROOT/deploy-functions.sh"
DEPLOY_GATE_LOG="$TEST_ROOT/deploy-gate.log"
ALERT_WORKFLOW_RUN="$TEST_ROOT/daemon-health-alert-run.sh"
ALERT_WORKFLOW_FROZEN_RUN="$TEST_ROOT/daemon-health-alert-frozen-run.sh"
SECRET_SENTINEL='secret-sentinel'
WEBHOOK_SENTINEL='https://webhook.invalid/secret-sentinel'

cleanup() {
  rm -rf "$TEST_ROOT"
}
trap cleanup EXIT

fail() {
  printf 'FAIL: %s\n' "$*" >&2
  exit 1
}

assert_contains() {
  local value="$1"
  local expected="$2"
  [[ "$value" == *"$expected"* ]] || fail "expected output to contain '$expected'"
}

assert_not_contains() {
  local value="$1"
  local forbidden="$2"
  [[ "$value" != *"$forbidden"* ]] || fail "unsafe value leaked: '$forbidden'"
}

write_stub() {
  local path="$1"
  shift
  printf '%s\n' "$@" > "$path"
  chmod +x "$path"
}

prepare_stubs() {
  mkdir -p "$STUB_BIN"

  write_stub "$STUB_BIN/docker" \
    '#!/usr/bin/env bash' \
    'set -euo pipefail' \
    'case "$1" in' \
    '  ps)' \
    '    case "$DAEMON_ALERT_TEST_MODE" in' \
    '      inventory_failed) exit 1 ;;' \
    '      inventory_engine) printf "s1-game-engine\\n" ;;' \
    '      inventory_alpha_engine|inventory_recent_running|inventory_boundary_running|inventory_old_running|inventory_alpha_stopped|inventory_state_inspect_failed|inventory_started_at_invalid|inventory_started_at_future|inventory_started_at_subsecond_future) printf "spep-game-engine\\n" ;;' \
    '      *) exit 0 ;;' \
    '    esac' \
    '    ;;' \
    '  inspect)' \
    '    [[ "${@: -1}" == spep-game-engine ]] || exit 1' \
    '    case "$DAEMON_ALERT_TEST_MODE" in' \
    '      inventory_recent_running) printf "running|%s\\n" "$(python3 -c '\''from datetime import datetime, timezone; print(datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"))'\'')" ;;' \
    '      inventory_boundary_running) printf "running|%s\\n" "$(python3 -c '\''from datetime import datetime, timedelta, timezone; print((datetime.now(timezone.utc) - timedelta(seconds=300)).isoformat().replace("+00:00", "Z"))'\'')" ;;' \
    '      inventory_old_running) printf "running|2020-01-01T00:00:00Z\\n" ;;' \
    '      inventory_alpha_stopped) printf "exited|2020-01-01T00:00:00Z\\n" ;;' \
    '      inventory_state_inspect_failed) exit 1 ;;' \
    '      inventory_started_at_invalid) printf "running|not-a-time\\n" ;;' \
    '      inventory_started_at_future) printf "running|2999-01-01T00:00:00Z\\n" ;;' \
    '      inventory_started_at_subsecond_future) printf "running|2026-08-13T00:00:00.500000Z\\n" ;;' \
    '      *) printf "running|2020-01-01T00:00:00Z\\n" ;;' \
    '    esac' \
    '    ;;' \
    '  exec)' \
    '    [[ "$2" == s1-game-engine || "$2" == spep-game-engine ]] || exit 1' \
    '    shift 2' \
    '    joined="$*"' \
    '    case "$joined" in' \
    '      *"/admin/turn-daemon/status"*)' \
    '        case "$DAEMON_ALERT_TEST_MODE" in' \
    '          status_unreadable|inventory_recent_running|inventory_alpha_stopped) exit 1 ;;' \
    '          paused) printf "%s\\n" "{\"paused\":true,\"recoveryReady\":true,\"recoveryMode\":\"READY\",\"recoveryReason\":\"secret-sentinel\",\"clock\":{\"tickSeconds\":17,\"lastTurnTime\":\"2026-08-05T16:15:45Z\"},\"lastSuccessfulTickAgeSeconds\":999,\"lastTickError\":\"secret-sentinel\"}" ;;' \
    '          paused_clock_down) printf "%s\\n" "{\"paused\":true,\"recoveryReady\":true,\"recoveryMode\":\"READY\",\"recoveryReason\":\"secret-sentinel\",\"clock\":{\"tickSeconds\":17,\"lastTurnTime\":\"2026-08-05T16:15:45Z\"},\"lastSuccessfulTickAgeSeconds\":5,\"lastTickError\":\"secret-sentinel\"}" ;;' \
    '          recovery_gated|dispatch_fail|inventory_alpha_engine|inventory_boundary_running|inventory_old_running) printf "%s\\n" "{\"paused\":false,\"recoveryReady\":false,\"recoveryMode\":\"RELOAD_REQUIRED\",\"recoveryReason\":\"secret-sentinel\",\"clock\":{\"tickSeconds\":17,\"lastTurnTime\":\"2026-08-05T16:15:45Z\"},\"lastSuccessfulTickAgeSeconds\":5,\"lastTickError\":\"secret-sentinel\"}" ;;' \
    '          stalled) printf "%s\\n" "{\"paused\":false,\"recoveryReady\":true,\"recoveryMode\":\"READY\",\"recoveryReason\":\"secret-sentinel\",\"clock\":{\"tickSeconds\":17,\"lastTurnTime\":\"2026-08-05T16:15:45Z\"},\"lastSuccessfulTickAgeSeconds\":52,\"lastTickError\":\"secret-sentinel\"}" ;;' \
    '          *) printf "%s\\n" "{\"paused\":false,\"recoveryReady\":true,\"recoveryMode\":\"READY\",\"recoveryReason\":\"secret-sentinel\",\"clock\":{\"tickSeconds\":17,\"lastTurnTime\":\"2026-08-05T16:15:45Z\"},\"lastSuccessfulTickAgeSeconds\":5,\"lastTickError\":\"secret-sentinel\"}" ;;' \
    '        esac' \
    '        ;;' \
    '      *"/actuator/health"*)' \
    '        case "$DAEMON_ALERT_TEST_MODE" in' \
    '          health_unreadable) printf "%s\\n" "not-json" ;;' \
    '          paused) printf "%s\\n" "{\"status\":\"OUT_OF_SERVICE\"}" ;;' \
    '          paused_clock_down|recovery_gated|dispatch_fail|inventory_alpha_engine|inventory_boundary_running|inventory_old_running|stalled) printf "%s\\n" "{\"status\":\"DOWN\"}" ;;' \
    '          *) printf "%s\\n" "{\"status\":\"UP\"}" ;;' \
    '        esac' \
    '        ;;' \
    '      *) exit 1 ;;' \
    '    esac' \
    '    ;;' \
    '  *) exit 1 ;;' \
    'esac'

  write_stub "$STUB_BIN/curl" \
    '#!/usr/bin/env bash' \
    'set -euo pipefail' \
    'payload=""' \
    'expect_payload=false' \
    'for argument in "$@"; do' \
    '  if [[ "$expect_payload" == true ]]; then payload="$argument"; expect_payload=false; continue; fi' \
    '  case "$argument" in --data|--data-binary) expect_payload=true ;; esac' \
    'done' \
    '[[ -n "$payload" ]] || exit 1' \
    'printf "%s\\n" "$payload" >> "$ALERT_PAYLOAD_LOG"' \
    '[[ "$DAEMON_ALERT_TEST_MODE" != dispatch_fail ]]'
}

run_alert() {
  local mode="$1"
  local server="${2:-s1}"
  local container="${3:-s1-game-engine}"
  : > "$ALERT_PAYLOAD_LOG"
  CURRENT_CASE="$mode $server $container"
  set +e
  LAST_OUTPUT="$(
    env \
      ALERT_PAYLOAD_LOG="$ALERT_PAYLOAD_LOG" \
      DAEMON_ALERT_TEST_MODE="$mode" \
      DAEMON_ALERT_WEBHOOK_URL="$WEBHOOK_SENTINEL" \
      PATH="$STUB_BIN:$PATH" \
      bash "$ALERTER" "$server" "$container" 2>&1
  )"
  LAST_STATUS=$?
  set -e
}

run_alert_without_webhook() {
  : > "$ALERT_PAYLOAD_LOG"
  CURRENT_CASE='missing webhook'
  set +e
  LAST_OUTPUT="$(
    env \
      ALERT_PAYLOAD_LOG="$ALERT_PAYLOAD_LOG" \
      DAEMON_ALERT_TEST_MODE=healthy \
      PATH="$STUB_BIN:$PATH" \
      bash "$ALERTER" s1 s1-game-engine 2>&1
  )"
  LAST_STATUS=$?
  set -e
}

assert_safe_output() {
  assert_not_contains "$LAST_OUTPUT" "$SECRET_SENTINEL"
  assert_not_contains "$LAST_OUTPUT" "$WEBHOOK_SENTINEL"
  assert_not_contains "$(<"$ALERT_PAYLOAD_LOG")" "$SECRET_SENTINEL"
  assert_not_contains "$(<"$ALERT_PAYLOAD_LOG")" "$WEBHOOK_SENTINEL"
}

assert_healthy() {
  [[ "$LAST_STATUS" -eq 0 ]] || fail "expected healthy success for $CURRENT_CASE"
  [[ ! -s "$ALERT_PAYLOAD_LOG" ]] || fail "healthy daemon dispatched an alert for $CURRENT_CASE"
  assert_contains "$LAST_OUTPUT" 'state=UP'
  assert_contains "$LAST_OUTPUT" 'lastTurnTime=2026-08-05T16:15:45Z'
  assert_safe_output
}

assert_alert() {
  local expected_state="$1"
  local expected_reason="$2"
  local expected_diagnostics="${3:-true}"
  [[ "$LAST_STATUS" -ne 0 ]] || fail "expected non-UP alert exit for $CURRENT_CASE"
  [[ -s "$ALERT_PAYLOAD_LOG" ]] || fail "expected webhook dispatch for $CURRENT_CASE"
  local payload
  payload="$(<"$ALERT_PAYLOAD_LOG")"
  assert_contains "$payload" "\"state\":\"$expected_state\""
  assert_contains "$payload" "\"reason\":\"$expected_reason\""
  if [[ "$expected_diagnostics" == true ]]; then
    assert_contains "$payload" '"tickSeconds":17'
    assert_contains "$payload" '"lastTurnTime":"2026-08-05T16:15:45Z"'
  else
    assert_contains "$payload" '"tickSeconds":0'
    assert_contains "$payload" '"lastTurnTime":"unavailable"'
  fi
  assert_safe_output
}

assert_workflow_contract() {
  [[ -f "$WORKFLOW" ]] || fail 'dedicated daemon-health alert workflow is missing'
  bash -n "$ALERTER"
  ruby -e 'require "yaml"; YAML.load_file(ARGV.fetch(0))' "$WORKFLOW" >/dev/null
  grep -Fq 'schedule:' "$WORKFLOW" || fail 'alert workflow has no scheduled trigger'
  grep -Fq 'workflow_dispatch:' "$WORKFLOW" || fail 'alert workflow has no manual trigger'
  grep -Fq 'DAEMON_ALERT_WEBHOOK_URL: ${{ secrets.DAEMON_ALERT_WEBHOOK_URL }}' "$WORKFLOW" ||
    fail 'alert workflow does not bind the dedicated webhook secret'
  grep -Fq 'bash tools/ops/daemon_health_alert.sh' "$WORKFLOW" ||
    fail 'alert workflow does not invoke the independently testable script'
  grep -Fq "docker ps -a --format '{{.Names}}'" "$WORKFLOW" ||
    fail 'alert workflow does not include stopped game-engine containers'
  ! grep -Fq 'deploy.yml' "$WORKFLOW" || fail 'alert workflow must not call the deploy pipeline'
}

load_alert_workflow_run() {
  awk '
    /^        run: \|$/ { capture = 1; next }
    capture {
      sub(/^          /, "")
      print
    }
  ' "$WORKFLOW" > "$ALERT_WORKFLOW_RUN"
  [[ -s "$ALERT_WORKFLOW_RUN" ]] || fail 'could not extract daemon alert workflow run block'
  bash -n "$ALERT_WORKFLOW_RUN"
  sed 's/datetime.now(timezone.utc)/datetime.fromisoformat("2026-08-13T00:00:00+00:00")/' \
    "$ALERT_WORKFLOW_RUN" > "$ALERT_WORKFLOW_FROZEN_RUN"
  bash -n "$ALERT_WORKFLOW_FROZEN_RUN"
}

run_alert_workflow_inventory_case() {
  local mode="$1"
  local workflow_run="${2:-$ALERT_WORKFLOW_RUN}"
  : > "$ALERT_PAYLOAD_LOG"
  CURRENT_CASE="workflow inventory $mode"
  set +e
  WORKFLOW_OUTPUT="$(
    cd "$REPO_ROOT"
    env \
      ALERT_PAYLOAD_LOG="$ALERT_PAYLOAD_LOG" \
      DAEMON_ALERT_TEST_MODE="$mode" \
      DAEMON_ALERT_WEBHOOK_URL="$WEBHOOK_SENTINEL" \
      PATH="$STUB_BIN:$PATH" \
      bash "$workflow_run" 2>&1
  )"
  WORKFLOW_STATUS=$?
  set -e
}

assert_alert_workflow_inventory_fails_closed() {
  load_alert_workflow_run

  run_alert_workflow_inventory_case inventory_empty
  [[ "$WORKFLOW_STATUS" -eq 0 ]] || fail 'empty Docker inventory should be a successful no-op'
  assert_contains "$WORKFLOW_OUTPUT" 'no game-engine containers found; daemon alert scan skipped'

  run_alert_workflow_inventory_case inventory_failed
  [[ "$WORKFLOW_STATUS" -ne 0 ]] || fail 'unreadable Docker inventory must fail closed'
  assert_contains "$WORKFLOW_OUTPUT" 'ERROR: Docker inventory query failed'
  assert_not_contains "$WORKFLOW_OUTPUT" "$WEBHOOK_SENTINEL"

  run_alert_workflow_inventory_case inventory_alpha_engine
  [[ "$WORKFLOW_STATUS" -ne 0 ]] || fail 'alphanumeric game-engine inventory was silently skipped'
  [[ -s "$ALERT_PAYLOAD_LOG" ]] || fail 'alphanumeric game-engine inventory did not dispatch an alert'
  assert_contains "$(<"$ALERT_PAYLOAD_LOG")" '"server":"spep"'
  assert_contains "$(<"$ALERT_PAYLOAD_LOG")" '"state":"DOWN"'
  assert_contains "$(<"$ALERT_PAYLOAD_LOG")" '"reason":"recovery_gated"'
  assert_not_contains "$WORKFLOW_OUTPUT" "$SECRET_SENTINEL"
  assert_not_contains "$WORKFLOW_OUTPUT" "$WEBHOOK_SENTINEL"
  assert_not_contains "$(<"$ALERT_PAYLOAD_LOG")" "$SECRET_SENTINEL"
  assert_not_contains "$(<"$ALERT_PAYLOAD_LOG")" "$WEBHOOK_SENTINEL"

  run_alert_workflow_inventory_case inventory_recent_running
  [[ "$WORKFLOW_STATUS" -eq 0 ]] || fail 'recent running container must remain inside startup grace'
  [[ ! -s "$ALERT_PAYLOAD_LOG" ]] || fail 'startup grace dispatched a false incident alert'
  assert_contains "$WORKFLOW_OUTPUT" 'startupAgeSeconds='
  assert_contains "$WORKFLOW_OUTPUT" 'daemon alert scan deferred'

  run_alert_workflow_inventory_case inventory_boundary_running
  [[ "$WORKFLOW_STATUS" -ne 0 ]] || fail 'five-minute boundary was incorrectly granted startup grace'
  assert_contains "$(<"$ALERT_PAYLOAD_LOG")" '"reason":"recovery_gated"'

  run_alert_workflow_inventory_case inventory_old_running
  [[ "$WORKFLOW_STATUS" -ne 0 ]] || fail 'old running engine was incorrectly granted startup grace'
  assert_contains "$(<"$ALERT_PAYLOAD_LOG")" '"reason":"recovery_gated"'

  run_alert_workflow_inventory_case inventory_alpha_stopped
  [[ "$WORKFLOW_STATUS" -ne 0 ]] || fail 'stopped engine was incorrectly granted startup grace'
  assert_contains "$(<"$ALERT_PAYLOAD_LOG")" '"reason":"status_unreadable"'

  run_alert_workflow_inventory_case inventory_state_inspect_failed
  [[ "$WORKFLOW_STATUS" -ne 0 ]] || fail 'unreadable Docker state must fail closed'
  [[ ! -s "$ALERT_PAYLOAD_LOG" ]] || fail 'state inspect failure must not fabricate daemon diagnostics'
  assert_contains "$WORKFLOW_OUTPUT" 'ERROR: Docker state query failed for spep-game-engine'

  run_alert_workflow_inventory_case inventory_started_at_invalid
  [[ "$WORKFLOW_STATUS" -ne 0 ]] || fail 'invalid Docker StartedAt must fail closed'
  [[ ! -s "$ALERT_PAYLOAD_LOG" ]] || fail 'invalid StartedAt must not fabricate daemon diagnostics'
  assert_contains "$WORKFLOW_OUTPUT" 'ERROR: invalid Docker startup state for spep-game-engine'

  run_alert_workflow_inventory_case inventory_started_at_future
  [[ "$WORKFLOW_STATUS" -ne 0 ]] || fail 'future Docker StartedAt must fail closed'
  [[ ! -s "$ALERT_PAYLOAD_LOG" ]] || fail 'future StartedAt must not fabricate daemon diagnostics'
  assert_contains "$WORKFLOW_OUTPUT" 'ERROR: invalid Docker startup state for spep-game-engine'

  run_alert_workflow_inventory_case inventory_started_at_subsecond_future "$ALERT_WORKFLOW_FROZEN_RUN"
  [[ "$WORKFLOW_STATUS" -ne 0 ]] || fail 'subsecond future Docker StartedAt must fail closed before integer conversion'
  [[ ! -s "$ALERT_PAYLOAD_LOG" ]] || fail 'subsecond future StartedAt must not fabricate daemon diagnostics'
  assert_contains "$WORKFLOW_OUTPUT" 'ERROR: invalid Docker startup state for spep-game-engine'
}

assert_production_compose_healthcheck_contract() {
  ruby -ryaml -e '
    compose = YAML.load_file(ARGV.fetch(0))
    engine = compose.fetch("services").fetch("game-engine")
    healthcheck = engine["healthcheck"] or abort "production game-engine healthcheck is missing"
    expected_probe = ["CMD", "curl", "-sf", "http://localhost:8082/actuator/health"]

    abort "production game-engine healthcheck probe mismatch" unless healthcheck.fetch("test") == expected_probe
    abort "production game-engine healthcheck interval mismatch" unless healthcheck.fetch("interval") == "10s"
    abort "production game-engine healthcheck timeout mismatch" unless healthcheck.fetch("timeout") == "5s"
    abort "production game-engine healthcheck retries mismatch" unless healthcheck.fetch("retries") == 3
    abort "production game-engine startup deadline must remain below five minutes" unless healthcheck.fetch("start_period") == "4m"
    abort "production game-engine restart policy changed" unless engine.fetch("restart") == "unless-stopped"
  ' "$PRODUCTION_COMPOSE"
}

load_deploy_recovery_functions() {
  awk '
    /^          read_daemon_gate_state\(\) \{/ { capture = 1 }
    /^          declare -a RUNNING_INTERNAL_SERVERS=/ { exit }
    capture {
      sub(/^          /, "")
      print
    }
  ' "$DEPLOY_WORKFLOW" > "$DEPLOY_FUNCTIONS"
  [[ -s "$DEPLOY_FUNCTIONS" ]] || fail 'could not extract deploy recovery functions'
  bash -n "$DEPLOY_FUNCTIONS"
  source "$DEPLOY_FUNCTIONS"
}

run_deploy_recovery_case() {
  local mode="$1"
  : > "$DEPLOY_GATE_LOG"
  read_world_clock() {
    printf '19003|1|1|1\n'
  }
  docker() {
    local query_count
    printf 'query\n' >> "$DEPLOY_GATE_LOG"
    query_count="$(awk 'END { print NR }' "$DEPLOY_GATE_LOG")"
    case "$mode:$query_count" in
      polling_recovery:1) printf '{"paused":false,"recoveryReady":true}\n' ;;
      paused_recovery:*) printf '{"paused":true,"recoveryReady":false}\n' ;;
      *) printf '{"paused":false,"recoveryReady":false}\n' ;;
    esac
  }
  set +e
  DEPLOY_OUTPUT="$(verify_world_clock_advance s1 s1-game-postgres s1-game-engine 2>&1)"
  DEPLOY_STATUS=$?
  set -e
}

assert_deploy_recovery_fails_closed() {
  load_deploy_recovery_functions

  run_deploy_recovery_case paused_recovery
  [[ "$DEPLOY_STATUS" -ne 0 ]] || fail 'deploy workflow silently skipped a paused recovery gate'
  assert_contains "$DEPLOY_OUTPUT" 'ERROR: s1 daemon recovery-gated; refusing to skip turn-advance verification'

  run_deploy_recovery_case immediate_recovery
  [[ "$DEPLOY_STATUS" -ne 0 ]] || fail 'deploy workflow silently skipped initial recovery gate'
  assert_contains "$DEPLOY_OUTPUT" 'ERROR: s1 daemon recovery-gated; refusing to skip turn-advance verification'

  run_deploy_recovery_case polling_recovery
  [[ "$DEPLOY_STATUS" -ne 0 ]] || fail 'deploy workflow silently skipped recovery gate while polling'
  assert_contains "$DEPLOY_OUTPUT" 'ERROR: s1 daemon recovery-gated while polling; refusing to skip turn-advance verification'
}

prepare_stubs
assert_workflow_contract
assert_alert_workflow_inventory_fails_closed
assert_production_compose_healthcheck_contract
assert_deploy_recovery_fails_closed

run_alert healthy
assert_healthy

run_alert catchup
assert_healthy

run_alert paused
assert_alert OUT_OF_SERVICE paused

run_alert paused_clock_down
assert_alert DOWN health_down

run_alert recovery_gated
assert_alert DOWN recovery_gated

run_alert stalled
assert_alert DOWN turn_stalled
assert_contains "$(<"$ALERT_PAYLOAD_LOG")" '"allowedSeconds":51'

run_alert health_unreadable
assert_alert DOWN health_unreadable

run_alert status_unreadable
assert_alert DOWN status_unreadable false

run_alert dispatch_fail
[[ "$LAST_STATUS" -ne 0 ]] || fail 'failed webhook dispatch must fail closed'
assert_contains "$LAST_OUTPUT" 'alert dispatch failed'
assert_safe_output

run_alert_without_webhook
[[ "$LAST_STATUS" -ne 0 ]] || fail 'missing webhook configuration must fail closed'
assert_contains "$LAST_OUTPUT" 'alert webhook is not configured'
[[ ! -s "$ALERT_PAYLOAD_LOG" ]] || fail 'missing webhook configuration must not dispatch an alert'

run_alert healthy bad/server s1-game-engine
[[ "$LAST_STATUS" -ne 0 ]] || fail 'unsafe server label must be rejected'
[[ ! -s "$ALERT_PAYLOAD_LOG" ]] || fail 'invalid input must not dispatch an alert'
assert_safe_output

printf 'PASS: daemon health alert workflow and script contracts\n'
