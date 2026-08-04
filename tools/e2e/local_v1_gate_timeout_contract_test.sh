#!/usr/bin/env bash
set -Eeuo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
gate="$repo_root/tools/e2e/local_v1_gate.sh"
tmp_dir="$(mktemp -d "${TMPDIR:-/tmp}/local-v1-gate-timeout-contract.XXXXXX")"

cleanup() {
  rm -rf "$tmp_dir"
}
trap cleanup EXIT

fail() {
  printf 'FAIL: %s\n' "$1" >&2
  exit 1
}

mock_list_has() {
  local list="$1"
  local value="$2"
  [[ "|$list|" == *"|$value|"* ]]
}

mock_list_add() {
  local variable_name="$1"
  local value="$2"
  local current="${!variable_name:-}"
  if ! mock_list_has "$current" "$value"; then
    printf -v "$variable_name" '%s|%s' "$current" "$value"
    export "$variable_name"
  fi
}

docker() {
  local image="${3:-}"
  if [[ "${1:-}" == "compose" && -n "${E2E_COMPOSE_CAPTURE_FILE:-}" ]]; then
    printf '%s\n' "$*" >>"$E2E_COMPOSE_CAPTURE_FILE"
  fi
  if [[ "${1:-}" == "image" && -n "${E2E_IMAGE_CAPTURE_FILE:-}" ]]; then
    printf '%s\n' "$*" >>"$E2E_IMAGE_CAPTURE_FILE"
  fi
  if [[ -n "${E2E_DOCKER_CAPTURE_FILE:-}" ]]; then
    printf '%s\n' "$*" >>"$E2E_DOCKER_CAPTURE_FILE"
  fi

  if [[ "${1:-}" == "compose" && " $* " == *" up "* ]]; then
    E2E_MOCK_TARGET_IMAGES=present
    export E2E_MOCK_TARGET_IMAGES
  fi
  if [[ "${1:-}" == "compose" && " $* " == *" down "* && "${E2E_MOCK_FAIL_COMPOSE_DOWN:-false}" == "true" ]]; then
    return 1
  fi

  case "${1:-}:${2:-}" in
    inspect:*)
      printf 'running healthy\n'
      ;;
    image:inspect)
      if [[ "$image" == "${E2E_MOCK_MISSING_SOURCE_IMAGE:-}" ]]; then
        return 1
      fi
      if [[ "$image" == v1-e2e-* ]]; then
        if mock_list_has "${E2E_MOCK_REMOVED_TARGET_IMAGES:-}" "$image"; then
          return 1
        fi
        if [[ "${E2E_MOCK_EXISTING_TARGET:-false}" == "true" ]]; then
          return 0
        fi
        [[ "${E2E_MOCK_TARGET_IMAGES:-}" == "present" ]]
        return
      fi
      return 0
      ;;
    image:tag)
      mock_list_add E2E_MOCK_TAGGED_TARGET_IMAGES "${4:-}"
      E2E_MOCK_TARGET_IMAGES=present
      export E2E_MOCK_TARGET_IMAGES
      return 0
      ;;
    image:rm)
      if [[ "${E2E_MOCK_FAIL_IMAGE_REMOVE:-false}" == "true" ]]; then
        return 1
      fi
      mock_list_add E2E_MOCK_REMOVED_TARGET_IMAGES "$image"
      return 0
      ;;
    volume:inspect)
      [[ "${E2E_MOCK_EXISTING_VOLUME:-false}" == "true" ]]
      return
      ;;
    volume:ls)
      if [[ "${E2E_MOCK_VOLUME_LIST_FAILURE:-false}" == "true" ]]; then
        return 1
      fi
      ;;
    *)
      ;;
  esac
}

curl() {
  :
}

omo() {
  :
}

pnpm() {
  if [[ " $* " == *" test:e2e "* ]]; then
    printf '%s\n' "${E2E_TEST_TIMEOUT_MS:-}" >"$E2E_TIMEOUT_CAPTURE_FILE"
    if [[ -n "${E2E_OPERATIONAL_CAPTURE_FILE:-}" ]]; then
      printf '%s|%s\n' "${E2E_OPERATIONAL_SMOKE:-}" "${SCENARIO_QA_TURNTERM:-}" >"$E2E_OPERATIONAL_CAPTURE_FILE"
    fi
    if [[ "${E2E_MOCK_PLAYWRIGHT_FAIL:-false}" == "true" ]]; then
      return 1
    fi
  fi
}

export -f mock_list_has mock_list_add docker curl omo pnpm

run_case() {
  local label="$1"
  local configured_timeout="$2"
  local expected_timeout="$3"
  local capture_file="$tmp_dir/${label}-timeout.txt"

  if [[ -n "$configured_timeout" ]]; then
    export E2E_TEST_TIMEOUT_MS="$configured_timeout"
  else
    unset E2E_TEST_TIMEOUT_MS
  fi
  export E2E_TIMEOUT_CAPTURE_FILE="$capture_file"

  OPENSAMGUK_WORLD_ID=1 \
  JWT_SECRET=contract-test-only \
  E2E_OPERATIONAL_SMOKE=false \
  E2E_ARTIFACT_DIR="$tmp_dir/${label}-artifacts" \
  "$gate"

  [[ -f "$capture_file" ]] || fail "$label did not invoke test:e2e"
  [[ "$(<"$capture_file")" == "$expected_timeout" ]] || {
    fail "$label timeout was $(<"$capture_file"), expected $expected_timeout"
  }
}

run_case default "" 420000
run_case override 510000 510000

assert_project_cleanup() {
  local label="$1"
  local artifact_dir="$2"
  local docker_capture="$3"
  local source_prefix="$4"
  local cleanup_artifact="$artifact_dir/cleanup-resources.txt"
  local services=(gateway-api game-api game-engine web-gateway web-game)
  local volumes=(pgdata redisdata profile-icons)
  local service
  local volume

  [[ -f "$cleanup_artifact" ]] || fail "$label did not write cleanup-resources.txt"
  grep -Fq 'compose-down-volumes|success' "$cleanup_artifact" || {
    fail "$label did not record successful compose down --volumes"
  }
  for volume in "${volumes[@]}"; do
    grep -Eq "^volume-absent\\|v1-e2e-.*_${volume}$" "$cleanup_artifact" || {
      fail "$label did not prove project volume ${volume} is absent"
    }
  done
  for service in "${services[@]}"; do
    grep -Eq "^image-alias-removed\\|v1-e2e-.*-${service}:latest$" "$cleanup_artifact" || {
      fail "$label did not remove isolated ${service} image alias"
    }
    grep -Eq "^image rm v1-e2e-.*-${service}:latest$" "$docker_capture" || {
      fail "$label did not invoke exact image removal for ${service}"
    }
    if grep -Fq "image rm ${source_prefix}-${service}:latest" "$docker_capture"; then
      fail "$label removed source image ${source_prefix}-${service}:latest"
    fi
  done
}

run_build_mode_contracts() {
  local default_capture="$tmp_dir/default-compose.txt"
  local sequential_capture="$tmp_dir/sequential-compose.txt"
  local default_docker_capture="$tmp_dir/default-docker.txt"
  local sequential_docker_capture="$tmp_dir/sequential-docker.txt"
  local default_artifact_dir="$tmp_dir/default-build-mode-artifacts"
  local sequential_artifact_dir="$tmp_dir/sequential-build-mode-artifacts"
  local services=(gateway-api game-api game-engine web-gateway web-game)
  local builds=()
  local index

  export E2E_COMPOSE_CAPTURE_FILE="$default_capture"
  export E2E_DOCKER_CAPTURE_FILE="$default_docker_capture"
  unset E2E_MOCK_TARGET_IMAGES E2E_MOCK_REMOVED_TARGET_IMAGES
  unset E2E_BUILD_MODE
  OPENSAMGUK_WORLD_ID=1 \
  JWT_SECRET=contract-test-only \
  E2E_ARTIFACT_DIR="$default_artifact_dir" \
  "$gate"

  grep -Fq ' up -d --build' "$default_capture" || fail "default build mode did not preserve compose up -d --build"
  if grep -Eq ' build (gateway-api|game-api|game-engine|web-gateway|web-game)$' "$default_capture"; then
    fail "default build mode unexpectedly performed sequential service builds"
  fi
  assert_project_cleanup default-build "$default_artifact_dir" "$default_docker_capture" opensamguk

  export E2E_COMPOSE_CAPTURE_FILE="$sequential_capture"
  export E2E_DOCKER_CAPTURE_FILE="$sequential_docker_capture"
  unset E2E_MOCK_TARGET_IMAGES E2E_MOCK_REMOVED_TARGET_IMAGES
  export E2E_BUILD_MODE=sequential
  OPENSAMGUK_WORLD_ID=1 \
  JWT_SECRET=contract-test-only \
  E2E_ARTIFACT_DIR="$sequential_artifact_dir" \
  "$gate"

  mapfile -t builds < <(grep -E ' build (gateway-api|game-api|game-engine|web-gateway|web-game)$' "$sequential_capture")
  [[ "${#builds[@]}" == "${#services[@]}" ]] || fail "sequential build mode did not build each application service exactly once"
  for index in "${!services[@]}"; do
    [[ "${builds[$index]}" == *" build ${services[$index]}" ]] || {
      fail "sequential build order mismatch at index $index: expected ${services[$index]}"
    }
  done
  grep -Fq ' up -d --no-build' "$sequential_capture" || fail "sequential build mode did not start compose with --no-build"
  if grep -Fq ' up -d --build' "$sequential_capture"; then
    fail "sequential build mode fell back to compose up -d --build"
  fi
  assert_project_cleanup sequential-build "$sequential_artifact_dir" "$sequential_docker_capture" opensamguk
  unset E2E_BUILD_MODE E2E_COMPOSE_CAPTURE_FILE E2E_DOCKER_CAPTURE_FILE E2E_MOCK_TARGET_IMAGES E2E_MOCK_REMOVED_TARGET_IMAGES
}

run_build_mode_contracts

run_prebuilt_image_contracts() {
  local default_capture="$tmp_dir/default-prebuilt-images.txt"
  local custom_capture="$tmp_dir/custom-prebuilt-images.txt"
  local default_docker_capture="$tmp_dir/default-prebuilt-docker.txt"
  local custom_docker_capture="$tmp_dir/custom-prebuilt-docker.txt"
  local default_artifact_dir="$tmp_dir/default-prebuilt-artifacts"
  local custom_artifact_dir="$tmp_dir/custom-prebuilt-artifacts"
  local invalid_output="$tmp_dir/invalid-prebuilt-prefix.log"
  local missing_output="$tmp_dir/missing-prebuilt-source.log"
  local existing_output="$tmp_dir/existing-prebuilt-target.log"
  local services=(gateway-api game-api game-engine web-gateway web-game)
  local service

  export E2E_IMAGE_CAPTURE_FILE="$default_capture"
  export E2E_DOCKER_CAPTURE_FILE="$default_docker_capture"
  unset E2E_MOCK_TARGET_IMAGES E2E_MOCK_REMOVED_TARGET_IMAGES
  unset E2E_PREBUILT_IMAGE_PREFIX E2E_MOCK_MISSING_SOURCE_IMAGE E2E_MOCK_EXISTING_TARGET
  OPENSAMGUK_WORLD_ID=1 \
  JWT_SECRET=contract-test-only \
  E2E_SKIP_BUILD=true \
  E2E_ARTIFACT_DIR="$default_artifact_dir" \
  "$gate"
  for service in "${services[@]}"; do
    grep -Eq "^image tag opensamguk-${service}:latest v1-e2e-.*-${service}:latest$" "$default_capture" || {
      fail "default prebuilt source mapping changed for $service"
    }
  done
  assert_project_cleanup default-prebuilt "$default_artifact_dir" "$default_docker_capture" opensamguk

  export E2E_IMAGE_CAPTURE_FILE="$custom_capture"
  export E2E_DOCKER_CAPTURE_FILE="$custom_docker_capture"
  unset E2E_MOCK_TARGET_IMAGES E2E_MOCK_REMOVED_TARGET_IMAGES
  export E2E_PREBUILT_IMAGE_PREFIX=prebuilt-fixture
  OPENSAMGUK_WORLD_ID=1 \
  JWT_SECRET=contract-test-only \
  E2E_SKIP_BUILD=true \
  E2E_ARTIFACT_DIR="$custom_artifact_dir" \
  "$gate"
  for service in "${services[@]}"; do
    grep -Eq "^image tag prebuilt-fixture-${service}:latest v1-e2e-.*-${service}:latest$" "$custom_capture" || {
      fail "custom prebuilt prefix did not supply $service source mapping"
    }
  done
  assert_project_cleanup custom-prebuilt "$custom_artifact_dir" "$custom_docker_capture" prebuilt-fixture

  if OPENSAMGUK_WORLD_ID=1 \
    JWT_SECRET=contract-test-only \
    E2E_SKIP_BUILD=true \
    E2E_PREBUILT_IMAGE_PREFIX='invalid/prefix' \
    E2E_ARTIFACT_DIR="$tmp_dir/invalid-prebuilt-artifacts" \
    "$gate" >"$invalid_output" 2>&1; then
    fail "invalid prebuilt image prefix unexpectedly started the gate"
  fi
  grep -Fq 'E2E_PREBUILT_IMAGE_PREFIX must be a Docker-safe local image prefix' "$invalid_output" || {
    fail "invalid prebuilt image prefix did not fail closed"
  }

  if OPENSAMGUK_WORLD_ID=1 \
    JWT_SECRET=contract-test-only \
    E2E_SKIP_BUILD=true \
    E2E_PREBUILT_IMAGE_PREFIX=prebuilt-fixture \
    E2E_MOCK_MISSING_SOURCE_IMAGE=prebuilt-fixture-game-api:latest \
    E2E_ARTIFACT_DIR="$tmp_dir/missing-prebuilt-artifacts" \
    "$gate" >"$missing_output" 2>&1; then
    fail "missing prebuilt source unexpectedly started the gate"
  fi
  grep -Fq 'E2E_SKIP_BUILD requires prebuilt image prebuilt-fixture-game-api:latest' "$missing_output" || {
    fail "missing prebuilt source did not fail closed"
  }

  if OPENSAMGUK_WORLD_ID=1 \
    JWT_SECRET=contract-test-only \
    E2E_SKIP_BUILD=true \
    E2E_PREBUILT_IMAGE_PREFIX=prebuilt-fixture \
    E2E_MOCK_EXISTING_TARGET=true \
    E2E_ARTIFACT_DIR="$tmp_dir/existing-prebuilt-artifacts" \
    "$gate" >"$existing_output" 2>&1; then
    fail "existing isolated target unexpectedly started the gate"
  fi
  grep -Fq 'refusing to overwrite existing isolated image alias' "$existing_output" || {
    fail "existing isolated target did not fail closed"
  }
  unset E2E_IMAGE_CAPTURE_FILE E2E_DOCKER_CAPTURE_FILE E2E_PREBUILT_IMAGE_PREFIX E2E_MOCK_MISSING_SOURCE_IMAGE E2E_MOCK_EXISTING_TARGET E2E_MOCK_TARGET_IMAGES E2E_MOCK_REMOVED_TARGET_IMAGES
}

run_prebuilt_image_contracts

run_failure_cleanup_contracts() {
  local playwright_output="$tmp_dir/playwright-failure-cleanup.log"
  local cleanup_output="$tmp_dir/cleanup-failure.log"
  local playwright_artifact_dir="$tmp_dir/playwright-failure-artifacts"
  local cleanup_artifact_dir="$tmp_dir/cleanup-failure-artifacts"
  local playwright_docker_capture="$tmp_dir/playwright-failure-docker.txt"
  local cleanup_docker_capture="$tmp_dir/cleanup-failure-docker.txt"
  local services=(gateway-api game-api game-engine web-gateway web-game)
  local service

  export E2E_DOCKER_CAPTURE_FILE="$playwright_docker_capture"
  unset E2E_MOCK_TARGET_IMAGES E2E_MOCK_REMOVED_TARGET_IMAGES E2E_MOCK_FAIL_COMPOSE_DOWN E2E_MOCK_FAIL_IMAGE_REMOVE
  if OPENSAMGUK_WORLD_ID=1 \
    JWT_SECRET=contract-test-only \
    E2E_OPERATIONAL_SMOKE=false \
    E2E_SKIP_BUILD=true \
    E2E_MOCK_PLAYWRIGHT_FAIL=true \
    E2E_ARTIFACT_DIR="$playwright_artifact_dir" \
    "$gate" >"$playwright_output" 2>&1; then
    fail "Playwright failure unexpectedly allowed the gate to pass"
  fi
  assert_project_cleanup playwright-failure "$playwright_artifact_dir" "$playwright_docker_capture" opensamguk

  export E2E_DOCKER_CAPTURE_FILE="$cleanup_docker_capture"
  unset E2E_MOCK_TARGET_IMAGES E2E_MOCK_REMOVED_TARGET_IMAGES E2E_MOCK_PLAYWRIGHT_FAIL E2E_MOCK_FAIL_IMAGE_REMOVE
  if OPENSAMGUK_WORLD_ID=1 \
    JWT_SECRET=contract-test-only \
    E2E_OPERATIONAL_SMOKE=false \
    E2E_SKIP_BUILD=true \
    E2E_MOCK_FAIL_COMPOSE_DOWN=true \
    E2E_ARTIFACT_DIR="$cleanup_artifact_dir" \
    "$gate" >"$cleanup_output" 2>&1; then
    fail "Compose cleanup failure unexpectedly allowed the gate to pass"
  fi
  grep -Fq 'compose-down-volumes|failed' "$cleanup_artifact_dir/cleanup-resources.txt" || {
    fail "Compose cleanup failure was not recorded in cleanup-resources.txt"
  }
  for service in "${services[@]}"; do
    grep -Eq "^image-alias-removed\\|v1-e2e-.*-${service}:latest$" "$cleanup_artifact_dir/cleanup-resources.txt" || {
      fail "Compose cleanup failure did not continue alias cleanup for ${service}"
    }
  done
  unset E2E_DOCKER_CAPTURE_FILE E2E_MOCK_TARGET_IMAGES E2E_MOCK_REMOVED_TARGET_IMAGES E2E_MOCK_PLAYWRIGHT_FAIL E2E_MOCK_FAIL_COMPOSE_DOWN E2E_MOCK_FAIL_IMAGE_REMOVE
}

run_failure_cleanup_contracts

run_operational_fail_closed_case() {
  local label="$1"
  local cadence="$2"
  local output="$tmp_dir/${label}.log"

  if [[ -n "$cadence" ]]; then
    export SCENARIO_QA_TURNTERM="$cadence"
  else
    unset SCENARIO_QA_TURNTERM
  fi

  if OPENSAMGUK_WORLD_ID=1 \
    JWT_SECRET=contract-test-only \
    E2E_OPERATIONAL_SMOKE=true \
    E2E_ARTIFACT_DIR="$tmp_dir/${label}-artifacts" \
    "$gate" >"$output" 2>&1; then
    fail "$label operational smoke unexpectedly started without the required one-minute cadence"
  fi
  if ! grep -Fq 'SCENARIO_QA_TURNTERM=1' "$output"; then
    fail "$label did not explain the one-minute cadence fail-closed contract"
  fi
}

run_operational_case() {
  local capture_file="$tmp_dir/operational-env.txt"
  local timeout_capture_file="$tmp_dir/operational-timeout.txt"

  export SCENARIO_QA_TURNTERM=1
  export E2E_OPERATIONAL_CAPTURE_FILE="$capture_file"
  export E2E_TIMEOUT_CAPTURE_FILE="$timeout_capture_file"
  unset E2E_TEST_TIMEOUT_MS
  OPENSAMGUK_WORLD_ID=1 \
  JWT_SECRET=contract-test-only \
  E2E_OPERATIONAL_SMOKE=true \
  E2E_ARTIFACT_DIR="$tmp_dir/operational-artifacts" \
  "$gate"

  [[ -f "$capture_file" ]] || fail "operational smoke did not invoke test:e2e"
  [[ "$(<"$capture_file")" == "true|1" ]] || {
    fail "operational smoke did not pass the opt-in and cadence to Playwright"
  }
  [[ "$(<"$timeout_capture_file")" == "600000" ]] || {
    fail "operational smoke timeout was $(<"$timeout_capture_file"), expected 600000"
  }
  unset E2E_OPERATIONAL_CAPTURE_FILE E2E_TIMEOUT_CAPTURE_FILE
}

run_operational_timeout_override_case() {
  local timeout_capture_file="$tmp_dir/operational-timeout-override.txt"

  export SCENARIO_QA_TURNTERM=1
  export E2E_TIMEOUT_CAPTURE_FILE="$timeout_capture_file"
  OPENSAMGUK_WORLD_ID=1 \
  JWT_SECRET=contract-test-only \
  E2E_OPERATIONAL_SMOKE=true \
  E2E_TEST_TIMEOUT_MS=710000 \
  E2E_ARTIFACT_DIR="$tmp_dir/operational-override-artifacts" \
  "$gate"

  [[ "$(<"$timeout_capture_file")" == "710000" ]] || {
    fail "operational timeout override was $(<"$timeout_capture_file"), expected 710000"
  }
  unset E2E_TIMEOUT_CAPTURE_FILE
}

run_operational_fail_closed_case missing ""
run_operational_fail_closed_case wrong-value 2
run_operational_case
run_operational_timeout_override_case
unset SCENARIO_QA_TURNTERM

printf 'local_v1_gate timeout contract: PASS\n'
