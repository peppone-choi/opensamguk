#!/usr/bin/env bash

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
GRADER="$REPO_ROOT/tools/ops/predeploy_go_check.sh"
WORKFLOW="$REPO_ROOT/.github/workflows/predeploy-go-check.yml"
TEST_ROOT="$(mktemp -d /tmp/opensamguk-predeploy-contract.XXXXXX)"
STACK_DIR="$TEST_ROOT/opensamguk-docker"
STUB_BIN="$TEST_ROOT/bin"
DOCKER_ROOT="$TEST_ROOT/docker-root"
DOCKER_LOG="$TEST_ROOT/docker.log"
DOCKER_VERBS="$TEST_ROOT/docker-verbs.log"
SQL_LOG="$TEST_ROOT/sql.log"
DF_LOG="$TEST_ROOT/df.log"
SUDO_LOG="$TEST_ROOT/sudo.log"
TAG="0123456789abcdef0123456789abcdef01234567"
WORLD_ID=101
OVERFLOW_VALUE=9223372036854775808

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
  [[ "$value" != *"$forbidden"* ]] || fail "unsafe output leaked"
}

assert_success() {
  [[ "$LAST_STATUS" -eq 0 ]] || fail "expected success for $CURRENT_CASE"
  assert_contains "$LAST_OUTPUT" 'GO: predeploy read-only checks passed'
  assert_not_contains "$LAST_OUTPUT" 'secret-sentinel'
  assert_not_contains "$LAST_OUTPUT" 'secret-json-sentinel'
  assert_not_contains "$LAST_OUTPUT" 'JWT_SECRET'
}

assert_no_go() {
  [[ "$LAST_STATUS" -ne 0 ]] || fail "expected No-Go for $CURRENT_CASE"
  assert_contains "$LAST_OUTPUT" 'NO-GO:'
  assert_not_contains "$LAST_OUTPUT" 'secret-sentinel'
  assert_not_contains "$LAST_OUTPUT" 'secret-json-sentinel'
  assert_not_contains "$LAST_OUTPUT" 'JWT_SECRET'
}

write_stub() {
  local path="$1"
  shift
  printf '%s\n' "$@" > "$path"
  chmod +x "$path"
}

prepare_stubs() {
  mkdir -p "$STUB_BIN" "$STACK_DIR/servers" "$DOCKER_ROOT"

  write_stub "$STUB_BIN/sudo" \
    '#!/usr/bin/env bash' \
    'set -euo pipefail' \
    'printf "%s\n" "$*" >> "$SUDO_LOG"' \
    'exec "$@"'

  write_stub "$STUB_BIN/df" \
    '#!/usr/bin/env bash' \
    'set -euo pipefail' \
    'target=""' \
    'for argument in "$@"; do target="$argument"; done' \
    'printf "%s\n" "$target" >> "$DF_LOG"' \
    'if [[ "$PREDEPLOY_STUB_MODE" == df_command_fail ]]; then exit 1; fi' \
    'if [[ "$PREDEPLOY_STUB_MODE" == df_malformed ]]; then printf "Filesystem 1024-blocks Used Available Capacity Mounted on\n"; printf "/dev/stub broken broken nope%% %s\n" "$target"; exit 0; fi' \
    'available=10485760' \
    'used_percent=10' \
    'case "$PREDEPLOY_STUB_MODE:$target" in' \
    '  disk_root_gib_low:/) available=1048575 ;;' \
    '  disk_docker_gib_low:"$DOCKER_ROOT") available=1048575 ;;' \
    '  disk_root_percent_low:/) used_percent=95 ;;' \
    '  disk_docker_percent_low:"$DOCKER_ROOT") used_percent=95 ;;' \
    'esac' \
    'printf "Filesystem 1024-blocks Used Available Capacity Mounted on\n"' \
    'printf "/dev/stub 20971520 1048576 %s %s%% %s\n" "$available" "$used_percent" "$target"'

  write_stub "$STUB_BIN/docker" \
    '#!/usr/bin/env bash' \
    'set -euo pipefail' \
    'printf "%s\n" "$1" >> "$DOCKER_VERBS"' \
    'printf "%s\n" "$*" >> "$DOCKER_LOG"' \
    'joined="$*"' \
    'mode="$PREDEPLOY_STUB_MODE"' \
    'case "$1" in' \
    '  info)' \
    '    printf "%s\n" "$DOCKER_ROOT"' \
    '    ;;' \
    '  inspect)' \
    '    if [[ "$joined" == *".Config.Image"* ]]; then' \
    '      case "$joined" in' \
    '        *s1-game-api*) image="ghcr.io/peppone-choi/opensamguk:game-api-$TEST_TAG" ;;' \
    '        *s1-game-engine*) image="ghcr.io/peppone-choi/opensamguk:game-engine-$TEST_TAG" ;;' \
    '        *s1-web-game*) image="ghcr.io/peppone-choi/opensamguk:web-game-$TEST_TAG" ;;' \
    '        *) exit 1 ;;' \
    '      esac' \
    '      if [[ "$mode" == image_tag_mismatch && "$joined" == *s1-game-engine* ]]; then image="ghcr.io/peppone-choi/opensamguk:game-engine-latest"; fi' \
    '      printf "%s\n" "$image"' \
    '    elif [[ "$mode" == container_down && "$joined" == *s1-game-engine* ]]; then' \
    '      printf "false\n"' \
    '    else' \
    '      printf "true\n"' \
    '    fi' \
    '    ;;' \
    '  exec)' \
    '    last=""' \
    '    for argument in "$@"; do last="$argument"; done' \
    '    if [[ "$joined" == *psql* ]]; then' \
    '      [[ "$joined" == *"env PGOPTIONS=-c default_transaction_read_only=on sh -ceu"* ]] || exit 1' \
    '      printf "%s\n" "$last" >> "$SQL_LOG"' \
    '      case "$last" in' \
    '        *scenario_code*world_state*ng_games*)' \
    '          if [[ "$last" != *"FROM world_state AS w JOIN ng_games AS g ON g.world_id = w.id"* || "$last" != *"WHERE w.id = $TEST_WORLD_ID"* || "$last" != *"g.server_id = w.meta ->>"* || "$last" != *"w.scenario_code ="* || "$last" != *"g.env ->>"* || "$last" == *COALESCE* ]]; then exit 1; fi' \
    '          case "$mode" in' \
    '            scenario_db_mismatch) printf "scenario_1010|scenario_9999\n" ;;' \
    '            scenario_multiple_rows) printf "scenario_1010|scenario_1010\nscenario_1010|scenario_1010\n" ;;' \
    '            *) printf "scenario_1010|scenario_1010\n" ;;' \
    '          esac' \
    '          ;;' \
    '        *success\ IS\ NOT\ TRUE*)' \
    '          if [[ "$mode" == flyway_failed ]]; then printf "1\n"; else printf "0\n"; fi' \
    '          ;;' \
    '        *MAX\(version::integer\)*)' \
    '          if [[ "$mode" == flyway_latest_mismatch ]]; then printf "35\n"; else printf "36\n"; fi' \
    '          ;;' \
    '        *version\ =\ *29*)' \
    '          if [[ "$mode" == flyway_v29_missing ]]; then printf "0\n"; else printf "1\n"; fi' \
    '          ;;' \
    '        *"log_entry_year_month_idx"*)' \
    '          case "$mode" in' \
    '            index_invalid) printf "f|t|CREATE INDEX log_entry_year_month_idx ON public.log_entry USING btree (world_id, year, month, id)\n" ;;' \
    '            index_unready) printf "t|f|CREATE INDEX log_entry_year_month_idx ON public.log_entry USING btree (world_id, year, month, id)\n" ;;' \
    '            index_shape_old) printf "t|t|CREATE INDEX log_entry_year_month_idx ON public.log_entry USING btree (year, month, id)\n" ;;' \
    '            index_shape_partial) printf "t|t|CREATE INDEX log_entry_year_month_idx ON public.log_entry USING btree (world_id, year, month, id) WHERE world_id > 0\n" ;;' \
    '            index_shape_include) printf "t|t|CREATE INDEX log_entry_year_month_idx ON public.log_entry USING btree (world_id, year, month, id) INCLUDE (id)\n" ;;' \
    '            index_shape_unique) printf "t|t|CREATE UNIQUE INDEX log_entry_year_month_idx ON public.log_entry USING btree (world_id, year, month, id)\n" ;;' \
    '            *) printf "t|t|CREATE INDEX log_entry_year_month_idx ON public.log_entry USING btree (world_id, year, month, id)\n" ;;' \
    '          esac' \
    '          ;;' \
    '        *) exit 1 ;;' \
    '      esac' \
    '    elif [[ "$mode" == health_down && "$joined" == *s1-game-engine* ]]; then' \
    '      exit 1' \
    '    elif [[ "$joined" == *redis-cli* ]]; then' \
    '      printf "PONG\\n"' \
    '    elif [[ "$joined" == *actuator/health* ]]; then' \
    '      [[ "$joined" == *" curl -fsS "* ]] || exit 1' \
    '      if [[ "$mode" == health_nested_up_overall_down ]]; then printf "{\\\"status\\\":\\\"DOWN\\\",\\\"components\\\":{\\\"db\\\":{\\\"status\\\":\\\"UP\\\"}}}\\n"; else printf "{\\\"status\\\":\\\"UP\\\"}\\n"; fi' \
    '    else' \
    '      exit 0' \
    '    fi' \
    '    ;;' \
    '  *) exit 1 ;;' \
    'esac'
}

prepare_fixture() {
  local mode="$1"
  local image_tag="$TAG"
  local web_tag="$TAG"
  local scenario_code='scenario_1010'

  case "$mode" in
    env_tag_mismatch) image_tag='ffffffffffffffffffffffffffffffffffffffff' ;;
    env_web_tag_mismatch) web_tag='ffffffffffffffffffffffffffffffffffffffff' ;;
    scenario_env_mismatch) scenario_code='scenario_9999' ;;
  esac

  printf '%s\n' \
    "IMAGE_TAG=$image_tag" \
    "WEB_GAME_TAG=$web_tag" \
    "SCENARIO_CODE=$scenario_code" \
    'JWT_SECRET=secret-sentinel' \
    'UNRELATED_JSON={"token":"secret-json-sentinel"}' \
    > "$STACK_DIR/servers/s1.env"
  : > "$DOCKER_LOG"
  : > "$DOCKER_VERBS"
  : > "$SQL_LOG"
  : > "$DF_LOG"
  : > "$SUDO_LOG"
}

run_grader() {
  local mode="$1"
  shift
  local runner_os='Linux'
  if [[ "$mode" == runner_bad ]]; then
    runner_os='Darwin'
  fi

  prepare_fixture "$mode"
  CURRENT_CASE="$mode $*"
  set +e
  LAST_OUTPUT="$(
    env \
      DOCKER_LOG="$DOCKER_LOG" \
      DOCKER_VERBS="$DOCKER_VERBS" \
      DOCKER_ROOT="$DOCKER_ROOT" \
      DF_LOG="$DF_LOG" \
      GITHUB_ACTIONS='true' \
      OPENSAMGUK_STACK_DIR="$STACK_DIR" \
      PATH="$STUB_BIN:$PATH" \
      PREDEPLOY_STUB_MODE="$mode" \
      RUNNER_ARCH='X64' \
      RUNNER_OS="$runner_os" \
      SQL_LOG="$SQL_LOG" \
      SUDO_LOG="$SUDO_LOG" \
      TEST_TAG="$TAG" \
      TEST_WORLD_ID="$WORLD_ID" \
      bash "$GRADER" "$@" 2>&1
  )"
  LAST_STATUS=$?
  set -e
}

assert_docker_command_shapes() {
  local command
  while IFS= read -r command; do
    case "$command" in
      'inspect --format {{.State.Running}} s1-game-api' | \
      'inspect --format {{.State.Running}} s1-game-engine' | \
      'inspect --format {{.State.Running}} s1-web-game' | \
      'inspect --format {{.State.Running}} s1-game-postgres' | \
      'inspect --format {{.State.Running}} s1-game-redis' | \
      'inspect --format {{.Config.Image}} s1-game-api' | \
      'inspect --format {{.Config.Image}} s1-game-engine' | \
      'inspect --format {{.Config.Image}} s1-web-game' | \
      'info --format {{.DockerRootDir}}' | \
      'exec s1-game-api curl -fsS http://localhost:8081/actuator/health' | \
      'exec s1-game-engine curl -fsS http://localhost:8082/actuator/health' | \
      'exec s1-web-game wget -qO /dev/null http://localhost:3001/' | \
      'exec s1-game-redis redis-cli ping') ;;
      exec\ s1-game-postgres\ sh\ -ceu\ exec\ pg_isready\ *) ;;
      exec\ s1-game-postgres\ env\ PGOPTIONS=-c\ default_transaction_read_only=on\ sh\ -ceu\ exec\ psql\ *) ;;
      *) fail "grader invoked an unsafe docker command shape: $command" ;;
    esac
  done < "$DOCKER_LOG"
}

assert_read_only_commands() {
  if grep -E -v '^(inspect|exec|info)$' "$DOCKER_VERBS" >/dev/null; then
    fail 'grader invoked a mutating or unsupported docker verb'
  fi
  assert_docker_command_shapes
  if grep -E -i '(^|[[:space:];])(alter|analyze|call|copy|create|delete|do|drop|grant|insert|reindex|revoke|set|truncate|update|vacuum)([[:space:];]|$)' "$SQL_LOG" >/dev/null; then
    fail 'grader issued a mutating SQL verb'
  fi
  if grep -E -v '^(\/|'"$DOCKER_ROOT"')$' "$DF_LOG" >/dev/null; then
    fail 'grader did not use df -Pk only for root and Docker root'
  fi
  if ! grep -Fxq '/' "$DF_LOG" || ! grep -Fxq "$DOCKER_ROOT" "$DF_LOG"; then
    fail 'grader did not inspect both root and Docker root'
  fi
  if grep -F 'actuator/health' "$DOCKER_LOG" | grep -Fq wget; then
    fail 'grader used wget for an actuator health check'
  fi
  grep -Fq 'exec s1-game-api curl -fsS http://localhost:8081/actuator/health' "$DOCKER_LOG" ||
    fail 'grader did not use curl -fsS for game-api health'
  grep -Fq 'exec s1-game-engine curl -fsS http://localhost:8082/actuator/health' "$DOCKER_LOG" ||
    fail 'grader did not use curl -fsS for game-engine health'
  grep -Fq 'exec s1-game-postgres env PGOPTIONS=-c default_transaction_read_only=on sh -ceu exec psql' "$DOCKER_LOG" ||
    fail 'grader did not enforce a read-only PostgreSQL session'
  assert_not_contains "$(<"$SUDO_LOG")" 'JWT_SECRET'
  assert_not_contains "$(<"$SUDO_LOG")" 'UNRELATED_JSON'
}

assert_world_query_contract() {
  local scenario_query
  scenario_query="$(grep -F "FROM world_state AS w JOIN ng_games AS g ON g.world_id = w.id" "$SQL_LOG")" ||
    fail 'grader did not issue the world-scoped scenario query'
  [[ "$scenario_query" != *$'\n'* ]] || fail 'grader scenario query was not unique'
  assert_contains "$scenario_query" "WHERE w.id = $WORLD_ID"
  assert_contains "$scenario_query" "g.server_id = w.meta ->> 'serverId'"
  assert_not_contains "$scenario_query" 'COALESCE'
  assert_not_contains "$scenario_query" "->> 'server_id'"
  assert_contains "$scenario_query" "w.scenario_code = 'scenario_1010'"
  assert_contains "$scenario_query" "g.env ->> 'scenario_code' = 'scenario_1010'"
  assert_not_contains "$scenario_query" "server_id = 's1'"
}

assert_workflow_contract() {
  local validator_line
  local checkout_line
  local sha_pattern='^[0-9a-fA-F]{40}$'
  local semver_pattern='^v[0-9]+\.[0-9]+\.[0-9]+([-+][0-9A-Za-z.-]+)?$'
  local unsafe_tag

  [[ -f "$WORKFLOW" ]] || fail 'predeploy workflow is missing'
  grep -Fqx '  workflow_dispatch:' "$WORKFLOW" || fail 'workflow is not manual-only'
  if grep -Eq '^  (push|pull_request|schedule):' "$WORKFLOW"; then
    fail 'workflow has a non-manual trigger'
  fi
  grep -Fq 'runs-on: [self-hosted, Linux, X64, ec2-prod]' "$WORKFLOW" || fail 'runner labels drifted'
  for input in server expected_tag expected_scenario_code world_id min_free_gib min_free_percent; do
    grep -Fq "      $input:" "$WORKFLOW" || fail "workflow input missing: $input"
  done
  if grep -Fq 'default:' "$WORKFLOW"; then
    fail 'workflow silently defaults an operator input'
  fi
  grep -Fq 'uses: actions/checkout@11bd71901bbe5b1630ceea73d27597364c9af683' "$WORKFLOW" ||
    fail 'workflow does not pin actions/checkout'
  validator_line="$(grep -n -F '      - id: validate-ref' "$WORKFLOW" | cut -d: -f1 || true)"
  checkout_line="$(grep -n -F 'uses: actions/checkout@' "$WORKFLOW" | cut -d: -f1 || true)"
  [[ "$validator_line" =~ ^[0-9]+$ && "$checkout_line" =~ ^[0-9]+$ ]] ||
    fail 'workflow must have one validator and one checkout step'
  (( validator_line < checkout_line )) ||
    fail 'workflow validates the checkout ref after checkout'
  grep -Fq '          VALIDATE_EXPECTED_TAG: ${{ inputs.expected_tag }}' "$WORKFLOW" ||
    fail 'workflow validator does not use step-level expected-tag env'
  grep -Fq '          if [[ "$tag" =~ ^[0-9a-fA-F]{40}$ ]]; then' "$WORKFLOW" ||
    fail 'workflow validator lacks the full SHA regex'
  grep -Fq '          elif [[ "$tag" =~ ^v[0-9]+\.[0-9]+\.[0-9]+([-+][0-9A-Za-z.-]+)?$ ]]; then' "$WORKFLOW" ||
    fail 'workflow validator lacks the canonical semver regex'
  grep -Fq '            ref="refs/tags/$tag"' "$WORKFLOW" ||
    fail 'workflow validator does not namespace semver refs under refs/tags'
  grep -Fq '          printf "ref=%s\n" "$ref" >> "$GITHUB_OUTPUT"' "$WORKFLOW" ||
    fail 'workflow validator does not write the ref output safely'
  grep -Fq '          ref: ${{ steps.validate-ref.outputs.ref }}' "$WORKFLOW" ||
    fail 'workflow checkout is not bound to the validated ref output'
  if grep -Fq '          ref: ${{ inputs.expected_tag }}' "$WORKFLOW"; then
    fail 'workflow checkout still uses the raw expected-tag input'
  fi
  grep -Fq '          persist-credentials: false' "$WORKFLOW" ||
    fail 'workflow checkout persists credentials'
  grep -Fqx '        env:' "$WORKFLOW" || fail 'workflow grader step lacks step-level env'
  grep -Fq '          PREDEPLOY_SERVER: ${{ inputs.server }}' "$WORKFLOW" ||
    fail 'workflow does not map server into step env'
  grep -Fq '          PREDEPLOY_EXPECTED_TAG: ${{ inputs.expected_tag }}' "$WORKFLOW" ||
    fail 'workflow does not map expected tag into step env'
  grep -Fq '          PREDEPLOY_EXPECTED_SCENARIO_CODE: ${{ inputs.expected_scenario_code }}' "$WORKFLOW" ||
    fail 'workflow does not map scenario code into step env'
  grep -Fq '          PREDEPLOY_WORLD_ID: ${{ inputs.world_id }}' "$WORKFLOW" ||
    fail 'workflow does not map world ID into step env'
  grep -Fq '          PREDEPLOY_MIN_FREE_GIB: ${{ inputs.min_free_gib }}' "$WORKFLOW" ||
    fail 'workflow does not map minimum free GiB into step env'
  grep -Fq '          PREDEPLOY_MIN_FREE_PERCENT: ${{ inputs.min_free_percent }}' "$WORKFLOW" ||
    fail 'workflow does not map minimum free percent into step env'
  if ! awk '
    /^        run: \|$/ { in_run = 1; next }
    /^      - / { in_run = 0 }
    in_run && index($0, "$" "{{ inputs.") { unsafe = 1 }
    END { exit unsafe }
  ' "$WORKFLOW"; then
    fail 'workflow interpolates inputs directly in a shell run block'
  fi
  for unsafe_tag in evil-branch '$(touch /tmp/opensamguk-invalid-ref)' $'v1.2.3\nrefs/heads/main'; do
    [[ ! "$unsafe_tag" =~ $sha_pattern && ! "$unsafe_tag" =~ $semver_pattern ]] ||
      fail 'validator regex accepts an unsafe checkout ref'
  done
  [[ 'v1.2.3' =~ $semver_pattern ]] || fail 'validator regex rejects canonical semver'
  grep -Fq 'bash tools/ops/predeploy_go_check.sh "$PREDEPLOY_SERVER" "$PREDEPLOY_EXPECTED_TAG" "$PREDEPLOY_EXPECTED_SCENARIO_CODE" "$PREDEPLOY_WORLD_ID" "$PREDEPLOY_MIN_FREE_GIB" "$PREDEPLOY_MIN_FREE_PERCENT"' "$WORKFLOW" ||
    fail 'workflow grader call is not exclusively double-quoted step env variables'
  grep -Fq 'bash -n tools/ops/predeploy_go_check.sh' "$WORKFLOW" || fail 'workflow omits bash syntax validation'
  grep -Fq 'bash tools/ops/predeploy_go_check_contract_test.sh' "$WORKFLOW" || fail 'workflow omits hermetic contract validation'
}

[[ -f "$GRADER" ]] || fail "grader is missing: $GRADER"
prepare_stubs

run_grader success s1 "$TAG" scenario_1010 "$WORLD_ID" 2 10
assert_success
assert_read_only_commands
assert_world_query_contract

for mode in \
  env_tag_mismatch \
  env_web_tag_mismatch \
  image_tag_mismatch \
  scenario_env_mismatch \
  scenario_db_mismatch \
  scenario_multiple_rows \
  runner_bad \
  container_down \
  health_down \
  health_nested_up_overall_down \
  df_command_fail \
  df_malformed \
  disk_root_gib_low \
  disk_docker_gib_low \
  disk_root_percent_low \
  disk_docker_percent_low \
  flyway_failed \
  flyway_latest_mismatch \
  flyway_v29_missing \
  index_invalid \
  index_unready \
  index_shape_old \
  index_shape_partial \
  index_shape_include \
  index_shape_unique; do
  run_grader "$mode" s1 "$TAG" scenario_1010 "$WORLD_ID" 2 10
  assert_no_go
done

run_grader df_command_fail s1 "$TAG" scenario_1010 "$WORLD_ID" 2 10
assert_no_go
assert_contains "$LAST_OUTPUT" 'D4-34 df -Pk failed'

run_grader df_malformed s1 "$TAG" scenario_1010 "$WORLD_ID" 2 10
assert_no_go
assert_contains "$LAST_OUTPUT" 'D4-34 df -Pk output is invalid'

run_grader success '../s1' "$TAG" scenario_1010 "$WORLD_ID" 2 10
assert_no_go

run_grader success s1 latest scenario_1010 "$WORLD_ID" 2 10
assert_no_go

run_grader success s1 "$TAG" scenario_010 "$WORLD_ID" 2 10
assert_no_go

run_grader success s1 "$TAG" scenario_1010 0 2 10
assert_no_go

run_grader success s1 "$TAG" scenario_1010 "$WORLD_ID" 0 10
assert_no_go

run_grader success s1 "$TAG" scenario_1010 "$WORLD_ID" 2 101
assert_no_go

run_grader success s1 "$TAG" scenario_1010 "$OVERFLOW_VALUE" 2 10
assert_no_go

run_grader success s1 "$TAG" scenario_1010 "$WORLD_ID" "$OVERFLOW_VALUE" 10
assert_no_go

run_grader success s1 "$TAG" scenario_1010 "$WORLD_ID" 2 "$OVERFLOW_VALUE"
assert_no_go

run_grader success s1 "$TAG" scenario_1010 "$WORLD_ID" 2
assert_no_go

assert_workflow_contract
printf 'PASS: predeploy-go-check hermetic contract\n'
