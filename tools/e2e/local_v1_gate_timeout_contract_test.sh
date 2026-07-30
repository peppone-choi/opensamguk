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

docker() {
  case "${1:-}" in
    inspect)
      printf 'running healthy\n'
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
  fi
}

export -f docker curl omo pnpm

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
  E2E_ARTIFACT_DIR="$tmp_dir/${label}-artifacts" \
  "$gate"

  [[ -f "$capture_file" ]] || fail "$label did not invoke test:e2e"
  [[ "$(<"$capture_file")" == "$expected_timeout" ]] || {
    fail "$label timeout was $(<"$capture_file"), expected $expected_timeout"
  }
}

run_case default "" 420000
run_case override 510000 510000

printf 'local_v1_gate timeout contract: PASS\n'
