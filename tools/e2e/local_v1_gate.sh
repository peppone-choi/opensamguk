#!/usr/bin/env bash
set -Eeuo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"

if [[ -z "${OPENSAMGUK_WORLD_ID:-}" ]]; then
  echo "OPENSAMGUK_WORLD_ID must be supplied by the caller" >&2
  exit 2
fi
if [[ -z "${JWT_PRIVATE_KEY:-}" || -z "${JWT_PUBLIC_KEY:-}" ]]; then
  echo "JWT_PRIVATE_KEY and JWT_PUBLIC_KEY must be supplied by the caller" >&2
  exit 2
fi

operational_smoke="${E2E_OPERATIONAL_SMOKE:-false}"
qa_turnterm="${SCENARIO_QA_TURNTERM:-}"
if [[ "$operational_smoke" == "true" && "$qa_turnterm" != "1" ]]; then
  echo "E2E_OPERATIONAL_SMOKE=true requires SCENARIO_QA_TURNTERM=1 (60-second local QA cadence)" >&2
  exit 2
fi

build_mode="${E2E_BUILD_MODE:-default}"
case "$build_mode" in
  default|sequential) ;;
  *)
    echo "E2E_BUILD_MODE must be default or sequential" >&2
    exit 2
    ;;
esac

prebuilt_image_prefix="${E2E_PREBUILT_IMAGE_PREFIX:-opensamguk}"
if [[ "${E2E_SKIP_BUILD:-false}" == "true" && ! "$prebuilt_image_prefix" =~ ^[a-z0-9][a-z0-9._-]*$ ]]; then
  echo "E2E_PREBUILT_IMAGE_PREFIX must be a Docker-safe local image prefix" >&2
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

application_services=(gateway-api game-api game-engine web-gateway web-game)
owned_image_aliases=()
owned_volume_names=()
cleanup_resources_file="$artifact_dir/cleanup-resources.txt"
: >"$cleanup_resources_file"

compose() {
  docker compose --project-name "$compose_project_name" --env-file /dev/null "$@"
}

reserve_isolated_cleanup_resources() {
  local service
  local image_alias
  local volume

  for service in "${application_services[@]}"; do
    image_alias="${compose_project_name}-${service}:latest"
    if docker image inspect "$image_alias" >/dev/null 2>&1; then
      printf 'existing-target|%s\n' "$image_alias" >>"$cleanup_resources_file"
      echo "refusing to overwrite existing isolated image alias $image_alias" >&2
      return 1
    fi
  done
  for volume in "${compose_project_name}_pgdata" "${compose_project_name}_redisdata" "${compose_project_name}_profile-icons"; do
    if docker volume inspect "$volume" >/dev/null 2>&1; then
      printf 'existing-volume|%s\n' "$volume" >>"$cleanup_resources_file"
      echo "refusing to reuse existing isolated Compose volume $volume" >&2
      return 1
    fi
  done
  for service in "${application_services[@]}"; do
    owned_image_aliases+=("${compose_project_name}-${service}:latest")
  done
  owned_volume_names=(
    "${compose_project_name}_pgdata"
    "${compose_project_name}_redisdata"
    "${compose_project_name}_profile-icons"
  )
}

prepare_no_build_images() {
  local source_image
  local target_image
  local service
  local mapping_file="$artifact_dir/docker-image-aliases.txt"

  : >"$mapping_file"
  for service in "${application_services[@]}"; do
    source_image="${prebuilt_image_prefix}-${service}:latest"
    target_image="${compose_project_name}-${service}:latest"
    if ! docker image inspect "$source_image" >/dev/null 2>&1; then
      printf 'missing-source|%s|%s\n' "$source_image" "$target_image" >>"$mapping_file"
      echo "E2E_SKIP_BUILD requires prebuilt image $source_image" >&2
      return 1
    fi
  done

  for service in "${application_services[@]}"; do
    source_image="${prebuilt_image_prefix}-${service}:latest"
    target_image="${compose_project_name}-${service}:latest"
    if ! docker image tag "$source_image" "$target_image"; then
      printf 'tag-failed|%s|%s\n' "$source_image" "$target_image" >>"$mapping_file"
      echo "could not prepare isolated image alias $target_image" >&2
      return 1
    fi
    printf 'tagged|%s|%s\n' "$source_image" "$target_image" >>"$mapping_file"
  done
}

build_services_sequentially() {
  local service

  for service in "${application_services[@]}"; do
    compose build "$service" >"$artifact_dir/docker-compose-build-${service}.log" 2>&1
  done
}

existing_services="$(compose ps -q 2>/dev/null || true)"
if [[ -n "$existing_services" ]]; then
  printf '%s\n' "$existing_services" >"$artifact_dir/compose-project-collision-services.txt"
  echo "refusing to reuse existing isolated Compose project $compose_project_name" >&2
  exit 2
fi
started_by_lane=0

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

record_cleanup() {
  printf '%s\n' "$1" >>"$cleanup_resources_file"
}

cleanup() {
  local exit_code=$?
  local image_alias
  local service
  local volume
  if ! restore_auth_fixture; then
    record_cleanup 'auth-restore|failed'
    exit_code=1
  fi
  if [[ "$started_by_lane" == 1 ]]; then
    if compose down --volumes >"$artifact_dir/docker-compose-down.log" 2>&1; then
      record_cleanup 'compose-down-volumes|success'
    else
      record_cleanup 'compose-down-volumes|failed'
      echo "docker compose down --volumes failed; see $artifact_dir/docker-compose-down.log" >&2
      exit_code=1
    fi
    for volume in "${owned_volume_names[@]}"; do
      if docker volume inspect "$volume" >"$artifact_dir/cleanup-volume-${volume##*_}.log" 2>&1; then
        record_cleanup "volume-still-present|$volume"
        echo "project-owned Compose volume still exists after cleanup: $volume" >&2
        exit_code=1
      else
        record_cleanup "volume-absent|$volume"
      fi
    done
    for image_alias in "${owned_image_aliases[@]}"; do
      service="${image_alias#${compose_project_name}-}"
      service="${service%:latest}"
      if docker image inspect "$image_alias" >/dev/null 2>&1; then
        if docker image rm "$image_alias" >"$artifact_dir/cleanup-image-alias-${service}.log" 2>&1; then
          record_cleanup "image-alias-removed|$image_alias"
        else
          record_cleanup "image-alias-remove-failed|$image_alias"
          echo "could not remove project-owned image alias $image_alias" >&2
          exit_code=1
        fi
      else
        record_cleanup "image-alias-already-absent|$image_alias"
      fi
      if docker image inspect "$image_alias" >/dev/null 2>&1; then
        record_cleanup "image-alias-still-present|$image_alias"
        echo "project-owned image alias still exists after cleanup: $image_alias" >&2
        exit_code=1
      else
        record_cleanup "image-alias-absent|$image_alias"
      fi
    done
  fi
  exit "$exit_code"
}
trap cleanup EXIT

reserve_isolated_cleanup_resources
started_by_lane=1

compose config --quiet >"$artifact_dir/docker-compose-config.log" 2>&1
compose_up_args=(-d --build)
if [[ "${E2E_SKIP_BUILD:-false}" == "true" ]]; then
  compose_up_args=(-d --no-build)
  prepare_no_build_images
elif [[ "$build_mode" == "sequential" ]]; then
  build_services_sequentially
  compose_up_args=(-d --no-build)
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

if [[ -n "${E2E_TEST_TIMEOUT_MS:-}" ]]; then
  playwright_timeout_ms="$E2E_TEST_TIMEOUT_MS"
elif [[ "$operational_smoke" == "true" ]]; then
  playwright_timeout_ms=600000
else
  playwright_timeout_ms=420000
fi

E2E_COMPOSE_PROJECT_NAME="$compose_project_name" \
E2E_REPO_ROOT="$repo_root" \
E2E_ARTIFACT_DIR="$artifact_dir" \
E2E_OPENSAMGUK_WORLD_ID="$OPENSAMGUK_WORLD_ID" \
E2E_TURN_PROFILE_NAME="${TURN_PROFILE_NAME:-che:scenario_2}" \
E2E_GATEWAY_URL="http://localhost:${web_gateway_port}" \
E2E_GAME_URL="http://localhost:${web_game_port}" \
E2E_GAME_ENGINE_HEALTH_URL="http://localhost:${game_engine_port}/actuator/health" \
E2E_PLAYWRIGHT_JSON="$artifact_dir/playwright-results.json" \
E2E_PLAYWRIGHT_OUTPUT_DIR="$artifact_dir/playwright-output" \
E2E_TEST_TIMEOUT_MS="$playwright_timeout_ms" \
E2E_OPERATIONAL_SMOKE="$operational_smoke" \
SCENARIO_QA_TURNTERM="$qa_turnterm" \
pnpm --dir web/game test:e2e >"$artifact_dir/playwright.log" 2>&1

echo "local v1 gate passed; artifacts: $artifact_dir"
