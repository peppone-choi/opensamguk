#!/usr/bin/env bash

set -euo pipefail

readonly NO_GO_EXIT=1
readonly INPUT_EXIT=2
readonly MAX_POSITIVE_LONG=9223372036854775807
readonly EXPECTED_LOG_ENTRY_INDEX_DEFINITION='create index log_entry_year_month_idx on public.log_entry using btree (world_id, year, month, id)'

no_go() {
  printf 'NO-GO: %s\n' "$*" >&2
  exit "$NO_GO_EXIT"
}

invalid_input() {
  printf 'NO-GO: invalid input: %s\n' "$*" >&2
  exit "$INPUT_EXIT"
}

immutable_tag() {
  local tag="$1"
  case "$tag" in
    [Ll][Aa][Tt][Ee][Ss][Tt]) return 1 ;;
  esac
  [[ "$tag" =~ ^[0-9a-fA-F]{40}$ || "$tag" =~ ^v[0-9]+\.[0-9]+\.[0-9]+([-+][0-9A-Za-z.-]+)?$ ]]
}

positive_decimal_at_most() {
  local value="$1"
  local upper_bound="$2"
  [[ "$value" =~ ^[1-9][0-9]*$ ]] || return 1
  if [[ ${#value} -lt ${#upper_bound} ]]; then
    return 0
  fi
  [[ ${#value} -eq ${#upper_bound} ]] &&
    [[ "$value" == "$upper_bound" || "$value" < "$upper_bound" ]]
}

safe_env_value() {
  local key="$1"
  sudo awk -F= -v key="$key" '
    $1 == key {
      if (found++) exit 2
      sub(/^[^=]*=/, "")
      sub(/\r$/, "")
      print
    }
    END { if (!found) exit 1 }
  ' "$ENV_FILE" 2>/dev/null
}

require_running() {
  local container="$1"
  local running
  running="$(docker inspect --format '{{.State.Running}}' "$container" 2>/dev/null)" ||
    no_go 'D4-33 expected container is unavailable'
  [[ "$running" == true ]] || no_go 'D4-33 expected container is not running'
}

require_actuator_health() {
  local container="$1"
  local port="$2"
  docker exec "$container" curl -fsS "http://localhost:$port/actuator/health" 2>/dev/null |
    grep -Eq '^[[:space:]]*\{"status"[[:space:]]*:[[:space:]]*"UP"([[:space:]]*,.*)?\}[[:space:]]*$' ||
    no_go 'D4-33 actuator health check failed'
}

psql_read() {
  local sql="$1"
  docker exec "$POSTGRES_CONTAINER" env 'PGOPTIONS=-c default_transaction_read_only=on' sh -ceu \
    'exec psql -v ON_ERROR_STOP=1 -U "${POSTGRES_USER:-sammo}" -d "${POSTGRES_DB:-sammo}" -tAc "$1"' \
    sh "$sql" 2>/dev/null
}

highest_checkout_migration() {
  local migration
  local name
  local version
  local highest=0
  shopt -s nullglob
  for migration in "$MIGRATIONS_DIR"/V*__*.sql "$KOTLIN_MIGRATIONS_DIR"/V*__*.kt; do
    name="$(basename "$migration")"
    version="$(printf '%s' "$name" | sed -E 's/^V([0-9]+)__.*/\1/')"
    [[ "$version" =~ ^[0-9]+$ ]] || continue
    (( version > highest )) && highest="$version"
  done
  shopt -u nullglob
  (( highest > 0 )) || no_go 'D4-35 checkout has no numeric Flyway migrations'
  printf '%s' "$highest"
}

disk_headroom() {
  local label="$1"
  local path="$2"
  local available_kib
  local used_percent
  local free_gib
  local free_percent
  local df_output
  local df_values
  df_output="$(df -Pk "$path" 2>/dev/null)" ||
    no_go 'D4-34 df -Pk failed'
  df_values="$(printf '%s\n' "$df_output" | awk 'NR == 2 { gsub(/%$/, "", $5); print $4 "|" $5; exit }')" ||
    no_go 'D4-34 df -Pk output is invalid'
  IFS='|' read -r available_kib used_percent <<< "$df_values"
  [[ "$available_kib" =~ ^[0-9]+$ && "$used_percent" =~ ^[0-9]+$ ]] ||
    no_go 'D4-34 df -Pk output is invalid'
  (( used_percent <= 100 )) || no_go 'D4-34 df -Pk output is invalid'
  free_gib="$((available_kib / 1024 / 1024))"
  free_percent="$((100 - used_percent))"
  (( free_gib >= MIN_FREE_GIB && free_percent >= MIN_FREE_PERCENT )) ||
    no_go "D4-34 $label disk headroom is below the operator-approved threshold"
  printf 'PASS: D4-34 %s disk headroom meets approved thresholds\n' "$label"
}

if (( $# != 6 )); then
  invalid_input 'expected server, immutable tag, canonical scenario code, world ID, min free GiB, and min free percent'
fi

SERVER="$1"
EXPECTED_TAG="$2"
EXPECTED_SCENARIO_CODE="$3"
EXPECTED_WORLD_ID="$4"
MIN_FREE_GIB="$5"
MIN_FREE_PERCENT="$6"

[[ "$SERVER" =~ ^s[0-9][A-Za-z0-9_-]*$ ]] ||
  invalid_input 'server must use the s<number> multi-server identifier'
immutable_tag "$EXPECTED_TAG" ||
  invalid_input 'expected tag must be a full commit SHA or immutable vX.Y.Z tag, never latest'
[[ "$EXPECTED_SCENARIO_CODE" =~ ^scenario_(0|[1-9][0-9]*)$ ]] ||
  invalid_input 'expected scenario code must be canonical scenario_<number>'
positive_decimal_at_most "$EXPECTED_WORLD_ID" "$MAX_POSITIVE_LONG" ||
  invalid_input 'world ID must be a positive integer'
positive_decimal_at_most "$MIN_FREE_GIB" "$MAX_POSITIVE_LONG" ||
  invalid_input 'minimum free GiB must be a positive integer'
positive_decimal_at_most "$MIN_FREE_PERCENT" 100 ||
  invalid_input 'minimum free percent must be an integer from 1 through 100'

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
MIGRATIONS_DIR="$REPO_ROOT/infra/src/main/resources/db/migration"
KOTLIN_MIGRATIONS_DIR="$REPO_ROOT/infra/src/main/kotlin/db/migration"
STACK_DIR="${OPENSAMGUK_STACK_DIR:-$HOME/opensamguk-docker}"
ENV_FILE="$STACK_DIR/servers/$SERVER.env"
API_CONTAINER="$SERVER-game-api"
ENGINE_CONTAINER="$SERVER-game-engine"
WEB_GAME_CONTAINER="$SERVER-web-game"
POSTGRES_CONTAINER="$SERVER-game-postgres"
REDIS_CONTAINER="$SERVER-game-redis"

[[ -d "$MIGRATIONS_DIR" ]] || no_go 'D4-35 checkout migrations are unavailable'
[[ -d "$KOTLIN_MIGRATIONS_DIR" ]] || no_go 'D4-35 checkout Kotlin migrations are unavailable'
[[ -f "$ENV_FILE" ]] || no_go 'safe server pin file is unavailable'
[[ "${GITHUB_ACTIONS:-}" == true ]] || no_go 'D4-33 GitHub Actions runner metadata is unavailable'
[[ "${RUNNER_OS:-}" == Linux ]] || no_go 'D4-33 runner OS is not Linux'
[[ "${RUNNER_ARCH:-}" == X64 ]] || no_go 'D4-33 runner architecture is not X64'
printf 'PASS: D4-33 GitHub Actions Linux/X64 runner metadata accepted\n'

for container in "$API_CONTAINER" "$ENGINE_CONTAINER" "$WEB_GAME_CONTAINER" "$POSTGRES_CONTAINER" "$REDIS_CONTAINER"; do
  require_running "$container"
done
require_actuator_health "$API_CONTAINER" 8081
require_actuator_health "$ENGINE_CONTAINER" 8082
docker exec "$WEB_GAME_CONTAINER" wget -qO /dev/null http://localhost:3001/ 2>/dev/null ||
  no_go 'D4-33 web health check failed'
docker exec "$POSTGRES_CONTAINER" sh -ceu \
  'exec pg_isready -U "${POSTGRES_USER:-sammo}" -d "${POSTGRES_DB:-sammo}"' 2>/dev/null ||
  no_go 'D4-33 PostgreSQL health check failed'
docker exec "$REDIS_CONTAINER" redis-cli ping 2>/dev/null | grep -Fxq PONG ||
  no_go 'D4-33 Redis health check failed'
printf 'PASS: D4-33 expected server containers and health checks passed\n'

IMAGE_TAG="$(safe_env_value IMAGE_TAG)" || no_go 'D4-31 safe IMAGE_TAG key is unavailable'
WEB_GAME_TAG="$(safe_env_value WEB_GAME_TAG)" || no_go 'D4-31 safe WEB_GAME_TAG key is unavailable'
immutable_tag "$IMAGE_TAG" || no_go 'D4-31 IMAGE_TAG is mutable or invalid'
immutable_tag "$WEB_GAME_TAG" || no_go 'D4-31 WEB_GAME_TAG is mutable or invalid'
[[ "$IMAGE_TAG" == "$EXPECTED_TAG" ]] || no_go 'D4-31 IMAGE_TAG does not match the expected immutable tag'
[[ "$WEB_GAME_TAG" == "$EXPECTED_TAG" ]] || no_go 'D4-31 WEB_GAME_TAG does not match the expected immutable tag'

for pair in "$API_CONTAINER|game-api" "$ENGINE_CONTAINER|game-engine" "$WEB_GAME_CONTAINER|web-game"; do
  container="$(printf '%s' "$pair" | cut -d'|' -f1)"
  component="$(printf '%s' "$pair" | cut -d'|' -f2)"
  image_ref="$(docker inspect --format '{{.Config.Image}}' "$container" 2>/dev/null)" ||
    no_go 'D4-31 running image reference is unavailable'
  [[ "$image_ref" == *":$component-$EXPECTED_TAG" ]] ||
    no_go 'D4-31 running image reference does not match the expected immutable tag'
done
printf 'PASS: D4-31 safe pins and running image references match the immutable tag\n'

SCENARIO_CODE="$(safe_env_value SCENARIO_CODE)" || no_go 'D4-32 safe SCENARIO_CODE key is unavailable'
[[ "$SCENARIO_CODE" =~ ^scenario_(0|[1-9][0-9]*)$ ]] || no_go 'D4-32 SCENARIO_CODE is not canonical'
[[ "$SCENARIO_CODE" == "$EXPECTED_SCENARIO_CODE" ]] || no_go 'D4-32 SCENARIO_CODE does not match the expected canonical scenario'
DB_SCENARIO_CODES="$(psql_read "SELECT w.scenario_code || '|' || (g.env ->> 'scenario_code') FROM world_state AS w JOIN ng_games AS g ON g.world_id = w.id WHERE w.id = $EXPECTED_WORLD_ID AND g.server_id = w.meta ->> 'serverId' AND w.scenario_code = '$EXPECTED_SCENARIO_CODE' AND g.env ->> 'scenario_code' = '$EXPECTED_SCENARIO_CODE';")" ||
  no_go 'D4-32 authoritative scenario query failed'
[[ -n "$DB_SCENARIO_CODES" && "$DB_SCENARIO_CODES" != *$'\n'* ]] ||
  no_go 'D4-32 authoritative scenario query did not return exactly one configured world'
[[ "$DB_SCENARIO_CODES" == "$EXPECTED_SCENARIO_CODE|$EXPECTED_SCENARIO_CODE" ]] ||
  no_go 'D4-32 world-state and ng-game scenario codes do not both match the expected canonical scenario'
printf 'PASS: D4-32 safe and authoritative scenario codes match\n'

DOCKER_ROOT="$(docker info --format '{{.DockerRootDir}}' 2>/dev/null)" ||
  no_go 'D4-34 Docker root directory is unavailable'
[[ "$DOCKER_ROOT" == /* && "$DOCKER_ROOT" != *$'\n'* ]] ||
  no_go 'D4-34 Docker root directory is invalid'
disk_headroom root /
disk_headroom 'Docker root' "$DOCKER_ROOT"

FAILED_FLYWAY_ROWS="$(psql_read 'SELECT count(*) FROM flyway_schema_history WHERE success IS NOT TRUE;')" ||
  no_go 'D4-35 Flyway success query failed'
[[ "$FAILED_FLYWAY_ROWS" =~ ^[0-9]+$ ]] || no_go 'D4-35 Flyway success query did not return an integer'
(( FAILED_FLYWAY_ROWS == 0 )) || no_go 'D4-35 Flyway history contains unsuccessful rows'

EXPECTED_MIGRATION_VERSION="$(highest_checkout_migration)"
LATEST_FLYWAY_VERSION="$(psql_read "SELECT COALESCE(MAX(version::integer), -1) FROM flyway_schema_history WHERE success IS TRUE AND version ~ '^[0-9]+$';")" ||
  no_go 'D4-35 latest Flyway version query failed'
[[ "$LATEST_FLYWAY_VERSION" =~ ^[0-9]+$ ]] || no_go 'D4-35 latest Flyway version query did not return an integer'
[[ "$LATEST_FLYWAY_VERSION" == "$EXPECTED_MIGRATION_VERSION" ]] ||
  no_go 'D4-35 latest successful Flyway version does not match the checkout'

V29_SUCCESS_ROWS="$(psql_read "SELECT count(*) FROM flyway_schema_history WHERE version = '29' AND success IS TRUE;")" ||
  no_go 'D4-35 V29 Flyway query failed'
[[ "$V29_SUCCESS_ROWS" =~ ^[0-9]+$ ]] || no_go 'D4-35 V29 Flyway query did not return an integer'
(( V29_SUCCESS_ROWS == 1 )) || no_go 'D4-35 Flyway V29 is not successful exactly once'

INDEX_ROW="$(psql_read "SELECT i.indisvalid::text || '|' || i.indisready::text || '|' || pg_get_indexdef(i.indexrelid) FROM pg_index AS i JOIN pg_class AS c ON c.oid = i.indexrelid JOIN pg_namespace AS n ON n.oid = c.relnamespace WHERE c.relname = 'log_entry_year_month_idx' AND n.nspname = 'public';")" ||
  no_go 'D4-35 log_entry index query failed'
[[ -n "$INDEX_ROW" && "$INDEX_ROW" != *$'\n'* ]] ||
  no_go 'D4-35 log_entry index query did not return exactly one index'
IFS='|' read -r INDEX_VALID INDEX_READY INDEX_DEFINITION <<< "$INDEX_ROW"
case "$INDEX_VALID" in t|true) ;; *) no_go 'D4-35 log_entry index is not valid' ;; esac
case "$INDEX_READY" in t|true) ;; *) no_go 'D4-35 log_entry index is not ready' ;; esac
INDEX_DEFINITION_NORMALIZED="$(printf '%s' "$INDEX_DEFINITION" | tr '[:upper:]' '[:lower:]' | tr -d '"' | tr -s '[:space:]' ' ' | sed -E 's/^ //; s/ $//')"
[[ "$INDEX_DEFINITION_NORMALIZED" == "$EXPECTED_LOG_ENTRY_INDEX_DEFINITION" ]] ||
  no_go 'D4-35 log_entry index definition is not the current world-scoped shape'
printf 'PASS: D4-35 Flyway and current log-entry index checks passed\n'

printf 'GO: predeploy read-only checks passed for %s\n' "$SERVER"
