#!/usr/bin/env bash
set -Eeuo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"

if [[ -z "${OPENSAMGUK_WORLD_ID:-}" ]]; then
  echo "OPENSAMGUK_WORLD_ID must be supplied by the caller" >&2
  exit 2
fi
if [[ -z "${JWT_SECRET:-}" ]]; then
  echo "JWT_SECRET must be supplied by the caller" >&2
  exit 2
fi

attempt_dir=""
if command -v omo >/dev/null 2>&1; then
  ulw_status="$(omo ulw-loop status --json 2>/dev/null || true)"
  if [[ -n "$ulw_status" ]]; then
    attempt_dir="$(ULW_STATUS="$ulw_status" python3 - <<'PY'
import json
import os

try:
    value = json.loads(os.environ['ULW_STATUS'])
except (KeyError, json.JSONDecodeError):
    value = {}
for key in ('currentAttemptDir', 'attemptDir'):
    candidate = value.get(key)
    if isinstance(candidate, str) and candidate:
        print(candidate)
        break
PY
)"
  fi
fi
artifact_dir="${E2E_ARTIFACT_DIR:-${attempt_dir:-$repo_root/.omo/evidence/local-v1}}"
mkdir -p "$artifact_dir"

compose_project_name="v1-e2e-$(date -u +%Y%m%d%H%M%S)-$$"
if [[ ! "$compose_project_name" =~ ^[a-z0-9][a-z0-9_-]*$ ]]; then
  echo "generated an unsafe Docker Compose project name: $compose_project_name" >&2
  exit 2
fi
printf '%s\n' "$compose_project_name" >"$artifact_dir/compose-project-name.txt"

compose() {
  docker compose --project-name "$compose_project_name" --env-file /dev/null "$@"
}

prepare_no_build_images() {
  local services=(gateway-api game-api game-engine web-gateway web-game)
  local source_image
  local target_image
  local service
  local mapping_file="$artifact_dir/docker-image-aliases.txt"

  : >"$mapping_file"
  for service in "${services[@]}"; do
    source_image="opensamguk-${service}:latest"
    target_image="${compose_project_name}-${service}:latest"
    if ! docker image inspect "$source_image" >/dev/null 2>&1; then
      printf 'missing-source|%s|%s\n' "$source_image" "$target_image" >>"$mapping_file"
      echo "E2E_SKIP_BUILD requires prebuilt image $source_image" >&2
      return 1
    fi
    if docker image inspect "$target_image" >/dev/null 2>&1; then
      printf 'existing-target|%s|%s\n' "$source_image" "$target_image" >>"$mapping_file"
      echo "refusing to overwrite existing isolated image alias $target_image" >&2
      return 1
    fi
  done

  for service in "${services[@]}"; do
    source_image="opensamguk-${service}:latest"
    target_image="${compose_project_name}-${service}:latest"
    if ! docker image tag "$source_image" "$target_image"; then
      printf 'tag-failed|%s|%s\n' "$source_image" "$target_image" >>"$mapping_file"
      echo "could not prepare isolated image alias $target_image" >&2
      return 1
    fi
    printf 'tagged|%s|%s\n' "$source_image" "$target_image" >>"$mapping_file"
  done
}

existing_services="$(compose ps -q 2>/dev/null || true)"
if [[ -n "$existing_services" ]]; then
  printf '%s\n' "$existing_services" >"$artifact_dir/compose-project-collision-services.txt"
  echo "refusing to reuse existing isolated Compose project $compose_project_name" >&2
  exit 2
fi
started_by_lane=1

auth_restore_required=0
auth_prior_allow_join=""
auth_prior_allow_login=""
auth_parsed_allow_join=""
auth_parsed_allow_login=""

parse_auth_flags() {
  local flags_file="$1"
  local line_count

  if ! line_count="$(awk 'END { print NR }' "$flags_file")"; then
    echo "could not read system_flag output; see $flags_file" >&2
    return 1
  fi
  if [[ "$line_count" != "1" ]]; then
    echo "system_flag query must return exactly one row; see $flags_file" >&2
    return 1
  fi
  if ! IFS='|' read -r auth_parsed_allow_join auth_parsed_allow_login <"$flags_file"; then
    echo "could not parse system_flag output; see $flags_file" >&2
    return 1
  fi
  case "$auth_parsed_allow_join:$auth_parsed_allow_login" in
    true:true|true:false|false:true|false:false) ;;
    *)
      echo "system_flag output is not a boolean pair; see $flags_file" >&2
      return 1
      ;;
  esac
}

update_auth_flags() {
  local allow_join="$1"
  local allow_login="$2"
  local flags_file="$3"

  case "$allow_join:$allow_login" in
    true:true|true:false|false:true|false:false) ;;
    *)
      echo "refusing to write an invalid system_flag boolean pair" >&2
      return 1
      ;;
  esac

  if ! compose exec -T postgres sh -ceu \
    'case "$1:$2" in
      true:true) sql="UPDATE system_flag SET allow_join = TRUE, allow_login = TRUE WHERE id = 1 RETURNING allow_join::text, allow_login::text;" ;;
      true:false) sql="UPDATE system_flag SET allow_join = TRUE, allow_login = FALSE WHERE id = 1 RETURNING allow_join::text, allow_login::text;" ;;
      false:true) sql="UPDATE system_flag SET allow_join = FALSE, allow_login = TRUE WHERE id = 1 RETURNING allow_join::text, allow_login::text;" ;;
      false:false) sql="UPDATE system_flag SET allow_join = FALSE, allow_login = FALSE WHERE id = 1 RETURNING allow_join::text, allow_login::text;" ;;
      *) exit 64 ;;
    esac
    psql -qAt -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" -F "|" -c "$sql"' \
    sh "$allow_join" "$allow_login" >"$flags_file" 2>&1; then
    echo "system_flag update failed; see $flags_file" >&2
    return 1
  fi
  if ! parse_auth_flags "$flags_file"; then
    return 1
  fi
  if [[ "$auth_parsed_allow_join" != "$allow_join" || "$auth_parsed_allow_login" != "$allow_login" ]]; then
    echo "system_flag update did not persist the requested values; see $flags_file" >&2
    return 1
  fi
}

enable_auth_fixture() {
  local read_log="$artifact_dir/auth-system-flag-read.log"

  if ! compose exec -T postgres sh -ceu \
    'psql -qAt -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" -F "|" -c "SELECT allow_join::text, allow_login::text FROM system_flag WHERE id = 1;"' \
    >"$read_log" 2>&1; then
    echo "system_flag read failed; see $read_log" >&2
    return 1
  fi
  if ! parse_auth_flags "$read_log"; then
    return 1
  fi

  auth_prior_allow_join="$auth_parsed_allow_join"
  auth_prior_allow_login="$auth_parsed_allow_login"
  auth_restore_required=1
  printf '%s|%s\n' "$auth_prior_allow_join" "$auth_prior_allow_login" >"$artifact_dir/auth-system-flag-before.txt"

  if ! update_auth_flags true true "$artifact_dir/auth-system-flag-enable.log"; then
    return 1
  fi
}

restore_auth_fixture() {
  if [[ "$auth_restore_required" != "1" ]]; then
    return 0
  fi

  if ! update_auth_flags "$auth_prior_allow_join" "$auth_prior_allow_login" "$artifact_dir/auth-system-flag-restore.log"; then
    echo "system_flag restore failed; local E2E gate is failing closed" >&2
    return 1
  fi
  auth_restore_required=0
}

cleanup() {
  local exit_code=$?
  if ! restore_auth_fixture; then
    exit_code=1
  fi
  if [[ "$started_by_lane" == 1 ]]; then
    compose down >"$artifact_dir/docker-compose-down.log" 2>&1 || {
      echo "docker compose down failed; see $artifact_dir/docker-compose-down.log" >&2
      exit_code=1
    }
  fi
  exit "$exit_code"
}
trap cleanup EXIT

compose config --quiet >"$artifact_dir/docker-compose-config.log" 2>&1
compose_up_args=(-d --build)
if [[ "${E2E_SKIP_BUILD:-false}" == "true" ]]; then
  compose_up_args=(-d --no-build)
  prepare_no_build_images
fi
compose up "${compose_up_args[@]}" >"$artifact_dir/docker-compose-up.log" 2>&1

gateway_port="${GATEWAY_API_PORT:-8080}"
game_api_port="${GAME_API_PORT:-8081}"
game_engine_port="${GAME_ENGINE_PORT:-8082}"
web_gateway_port="${WEB_GATEWAY_PORT:-3000}"
web_game_port="${WEB_GAME_PORT:-3001}"
nginx_port="${NGINX_HTTP_PORT:-80}"

health_url() {
  local name="$1"
  local url="$2"
  local output="$artifact_dir/health-${name}.http"
  if ! curl -i --fail --silent --show-error --max-time 8 "$url" >"$output" 2>&1; then
    return 1
  fi
}

container_health() {
  local name="$1"
  local container="$2"
  local output="$artifact_dir/health-${name}.container"
  docker inspect --format '{{.State.Status}} {{if .State.Health}}{{.State.Health.Status}}{{end}}' "$container" >"$output" 2>&1
  grep -Eq '^running healthy$' "$output"
}

wait_for_health() {
  local deadline=$((SECONDS + ${E2E_HEALTH_TIMEOUT_SEC:-360}))
  while (( SECONDS < deadline )); do
    if container_health postgres opensamguk-postgres \
      && container_health redis opensamguk-redis \
      && health_url gateway-api "http://localhost:${gateway_port}/actuator/health" \
      && health_url game-api "http://localhost:${game_api_port}/actuator/health" \
      && health_url game-engine "http://localhost:${game_engine_port}/actuator/health" \
      && health_url web-gateway "http://localhost:${web_gateway_port}/api/health" \
      && health_url web-game "http://localhost:${web_game_port}/api/health" \
      && health_url nginx "http://localhost:${nginx_port}/health"; then
      return 0
    fi
    sleep 5
  done
  echo "stack health timeout; see $artifact_dir/health-* and docker-compose-up.log" >&2
  return 1
}

wait_for_health

if [[ "${E2E_ENABLE_AUTH:-false}" == "true" ]]; then
  enable_auth_fixture
fi

if ! command -v pnpm >/dev/null 2>&1; then
  echo "pnpm is required to run the Playwright lane" >&2
  exit 2
fi
pnpm --dir web/game install --frozen-lockfile >"$artifact_dir/pnpm-install.log" 2>&1
pnpm --dir web/game exec playwright install chromium >"$artifact_dir/playwright-install.log" 2>&1

E2E_COMPOSE_PROJECT_NAME="$compose_project_name" \
E2E_REPO_ROOT="$repo_root" \
E2E_ARTIFACT_DIR="$artifact_dir" \
E2E_GATEWAY_URL="http://localhost:${web_gateway_port}" \
E2E_GAME_URL="http://localhost:${web_game_port}" \
E2E_GAME_ENGINE_HEALTH_URL="http://localhost:${game_engine_port}/actuator/health" \
E2E_PLAYWRIGHT_JSON="$artifact_dir/playwright-results.json" \
E2E_PLAYWRIGHT_OUTPUT_DIR="$artifact_dir/playwright-output" \
E2E_TEST_TIMEOUT_MS="${E2E_TEST_TIMEOUT_MS:-420000}" \
pnpm --dir web/game test:e2e >"$artifact_dir/playwright.log" 2>&1

echo "local v1 gate passed; artifacts: $artifact_dir"
