#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'USAGE'
Usage: tools/php-golden/run_ga079_nation_bulk.sh [--help | --self-test-cleanup | --replace-existing]

Runs the GA-079 opt-in historical PHP comparison matrix twice against fresh disposable MariaDB 11.4
scenario_1010 installs, compares the canonical JSON with cmp, and only then
stages, cleanup-verifies, and atomically publishes
docs/loops/cqrs-runtime-safety-2026-07-18/evidence/ga079-nation-bulk-php.json.

Safety:
  - uses unique, labelled opensamguk-ga079-* containers and networks only;
  - never reuses/removes devsam-golden-net or any fixed/user resource;
  - sends SIGKILL only to the capture child PID after a bounded hook handshake;
  - does not print credentials, PIDs, hidden seeds, or generated artifact paths
    containing those values.

--self-test-cleanup runs no Docker capture. It intentionally makes the owned-resource
cleanup helpers fail and must itself exit nonzero, proving both that an EXIT-trap cleanup
failure cannot be masked as a successful wrapper run and that no staged final artifact is
published.

--replace-existing is an explicit, reviewed metadata-refresh escape hatch. It may replace
an existing canonical file only after two matching fresh captures and verified cleanup; the
normal mode refuses a differing existing file.
USAGE
}

SELF_TEST_CLEANUP=0
REPLACE_EXISTING=0

if [[ $# -gt 1 ]]; then
  usage >&2
  exit 64
fi
if [[ $# -eq 1 ]]; then
  case "$1" in
    --help)
      usage
      exit 0
      ;;
    --self-test-cleanup)
      SELF_TEST_CLEANUP=1
      ;;
    --replace-existing)
      REPLACE_EXISTING=1
      ;;
    *)
      usage >&2
      exit 64
      ;;
  esac
fi

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd -P)"
PHP_IMAGE="opensamguk-php-golden:latest"
DB_IMAGE="mariadb:11.4"
OUTPUT_PATH="${REPO_ROOT}/docs/loops/cqrs-runtime-safety-2026-07-18/evidence/ga079-nation-bulk-php.json"
CAPTURE_SCRIPT="/work/tools/php-golden/capture_ga079_nation_bulk.php"

if (( ! SELF_TEST_CLEANUP )); then
  for required_path in \
    "${REPO_ROOT}/legacy/devsam-core/vendor/autoload.php" \
    "${REPO_ROOT}/tools/php-golden/capture_ga079_nation_bulk.php"; do
    if [[ ! -f "$required_path" ]]; then
      echo "GA-079 prerequisite missing" >&2
      exit 1
    fi
  done

  if ! command -v docker >/dev/null 2>&1; then
    echo "Docker is required for GA-079 PHP capture" >&2
    exit 1
  fi
  if ! docker version --format '{{.Server.Version}}' >/dev/null 2>&1; then
    echo "Docker daemon is not reachable" >&2
    exit 1
  fi
fi

if (( SELF_TEST_CLEANUP )); then
  RUN_ID="cleanup-self-test"
elif command -v uuidgen >/dev/null 2>&1; then
  RUN_ID="$(uuidgen | tr '[:upper:]' '[:lower:]' | tr -d '-')"
else
  RUN_ID="$(openssl rand -hex 16)"
fi
RUN_TAG="${RUN_ID:0:12}"
RUN_TMP="$(mktemp -d "${TMPDIR:-/tmp}/opensamguk-ga079.XXXXXX")"
chmod 700 "$RUN_TMP"

declare -a OWNED_CONTAINERS=()
declare -a OWNED_NETWORKS=()
CLEANUP_ATTEMPTED=0
CLEANUP_STATUS=0
STAGED_OUTPUT=""

resource_label_value() {
  local resource_type="$1"
  local resource_name="$2"
  local label_key="$3"
  local label_value label_template
  if [[ "$resource_type" == "container" ]]; then
    label_template="{{ index .Config.Labels \"${label_key}\" }}"
  else
    label_template="{{ index .Labels \"${label_key}\" }}"
  fi
  if ! label_value="$(docker "$resource_type" inspect -f "$label_template" "$resource_name" 2>&1)"; then
    echo "cannot inspect ${resource_type} label during GA-079 cleanup" >&2
    return 1
  fi
  printf '%s' "$label_value"
}

resource_is_present() {
  local resource_type="$1"
  local resource_name="$2"
  local diagnostic
  if diagnostic="$(docker "$resource_type" inspect "$resource_name" 2>&1)"; then
    return 0
  fi
  if [[ "$diagnostic" == *"No such object"* ||
        "$diagnostic" == *"No such container"* ||
        "$diagnostic" == *"No such network"* ||
        "$diagnostic" == *"network ${resource_name} not found"* ]]; then
    return 1
  fi
  echo "cannot verify ${resource_type} cleanup: ${diagnostic}" >&2
  return 2
}

remove_owned_container() {
  local container_name="$1"
  local expected_run="$2"
  local inspect_status capture_label actual_run_label
  if (( SELF_TEST_CLEANUP )); then
    echo "GA-079 cleanup self-test: synthetic owned container cleanup failure: ${container_name}" >&2
    return 1
  fi
  if resource_is_present container "$container_name"; then
    :
  else
    inspect_status=$?
    if (( inspect_status == 1 )); then
      return 0
    fi
    return 1
  fi
  if ! capture_label="$(resource_label_value container "$container_name" opensamguk.capture)"; then
    return 1
  fi
  if ! actual_run_label="$(resource_label_value container "$container_name" opensamguk.run)"; then
    return 1
  fi
  if [[ "$capture_label" != "ga079" || "$actual_run_label" != "$expected_run" ]]; then
    echo "refusing to remove non-owned container" >&2
    return 1
  fi
  if ! docker rm -f "$container_name" >/dev/null; then
    echo "failed to remove owned container" >&2
    return 1
  fi
  if resource_is_present container "$container_name"; then
    echo "owned container remains after cleanup" >&2
    return 1
  else
    inspect_status=$?
    if (( inspect_status != 1 )); then
      return 1
    fi
  fi
  return 0
}

remove_owned_network() {
  local network_name="$1"
  local expected_run="$2"
  local inspect_status capture_label actual_run_label
  if (( SELF_TEST_CLEANUP )); then
    echo "GA-079 cleanup self-test: synthetic owned network cleanup failure: ${network_name}" >&2
    return 1
  fi
  if resource_is_present network "$network_name"; then
    :
  else
    inspect_status=$?
    if (( inspect_status == 1 )); then
      return 0
    fi
    return 1
  fi
  if ! capture_label="$(resource_label_value network "$network_name" opensamguk.capture)"; then
    return 1
  fi
  if ! actual_run_label="$(resource_label_value network "$network_name" opensamguk.run)"; then
    return 1
  fi
  if [[ "$capture_label" != "ga079" || "$actual_run_label" != "$expected_run" ]]; then
    echo "refusing to remove non-owned network" >&2
    return 1
  fi
  if ! docker network rm "$network_name" >/dev/null; then
    echo "failed to remove owned network" >&2
    return 1
  fi
  if resource_is_present network "$network_name"; then
    echo "owned network remains after cleanup" >&2
    return 1
  else
    inspect_status=$?
    if (( inspect_status != 1 )); then
      return 1
    fi
  fi
  return 0
}

cleanup_owned_resources() {
  if (( CLEANUP_ATTEMPTED )); then
    return "$CLEANUP_STATUS"
  fi

  CLEANUP_ATTEMPTED=1
  local cleanup_status=0
  local entry name run_label
  for ((idx=${#OWNED_CONTAINERS[@]} - 1; idx >= 0; idx--)); do
    entry="${OWNED_CONTAINERS[$idx]}"
    name="${entry%%|*}"
    run_label="${entry#*|}"
    if ! remove_owned_container "$name" "$run_label"; then
      cleanup_status=1
    fi
  done
  for ((idx=${#OWNED_NETWORKS[@]} - 1; idx >= 0; idx--)); do
    entry="${OWNED_NETWORKS[$idx]}"
    name="${entry%%|*}"
    run_label="${entry#*|}"
    if ! remove_owned_network "$name" "$run_label"; then
      cleanup_status=1
    fi
  done
  if [[ "$RUN_TMP" == "${TMPDIR:-/tmp}/opensamguk-ga079."* ]]; then
    if ! rm -rf -- "$RUN_TMP"; then
      echo "failed to remove GA-079 temporary directory" >&2
      cleanup_status=1
    elif [[ -e "$RUN_TMP" ]]; then
      echo "GA-079 temporary directory remains after cleanup" >&2
      cleanup_status=1
    fi
  fi
  CLEANUP_STATUS=$cleanup_status
  return "$cleanup_status"
}

discard_staged_output() {
  local staged_path="${STAGED_OUTPUT:-}"
  if [[ -z "$staged_path" ]]; then
    return 0
  fi
  if [[ "$(basename "$staged_path")" != .ga079-stage.* ]]; then
    echo "refusing to remove an unexpected GA-079 staging path" >&2
    return 1
  fi

  if [[ -e "$staged_path" || -L "$staged_path" ]]; then
    if ! rm -f -- "$staged_path"; then
      echo "failed to discard GA-079 staged evidence" >&2
      return 1
    fi
  fi
  if [[ -e "$staged_path" || -L "$staged_path" ]]; then
    echo "GA-079 staged evidence remains after discard" >&2
    return 1
  fi
  STAGED_OUTPUT=""
  return 0
}

stage_cleanup_and_publish() {
  local source_path="$1"
  local target_path="$2"
  local target_dir stage_path

  [[ -s "$source_path" ]] || { echo "GA-079 final capture missing before staging" >&2; return 1; }
  target_dir="$(dirname "$target_path")"
  mkdir -p "$target_dir"
  if [[ -L "$target_path" ]]; then
    echo "refusing to publish GA-079 evidence through a symlink" >&2
    return 1
  fi
  if [[ -e "$target_path" ]]; then
    if ! cmp -s "$target_path" "$source_path" && (( ! REPLACE_EXISTING )); then
      echo "existing GA-079 evidence differs; refusing to overwrite it without --replace-existing" >&2
      return 1
    fi
  fi

  if ! stage_path="$(mktemp "${target_dir}/.ga079-stage.XXXXXX")"; then
    echo "cannot create GA-079 evidence staging file" >&2
    return 1
  fi
  STAGED_OUTPUT="$stage_path"
  if ! install -m 0644 "$source_path" "$STAGED_OUTPUT" || ! cmp -s "$source_path" "$STAGED_OUTPUT"; then
    echo "cannot verify GA-079 staged evidence" >&2
    discard_staged_output || true
    return 1
  fi

  # The source lives in RUN_TMP; cleanup must happen after staging but before the
  # atomic rename, otherwise a failed cleanup could leave a new canonical fixture.
  if cleanup_owned_resources; then
    :
  else
    echo "GA-079 cleanup failed; staged evidence will not be published" >&2
    discard_staged_output || true
    return 1
  fi

  if ! mv -f -- "$STAGED_OUTPUT" "$target_path"; then
    echo "cannot atomically publish GA-079 staged evidence" >&2
    return 1
  fi
  STAGED_OUTPUT=""
  echo "GA-079 fresh captures matched; owned cleanup verified before atomic evidence publish." >&2
  return 0
}

on_exit() {
  local original_status=$?
  local cleanup_status=0
  trap - EXIT

  if cleanup_owned_resources; then
    :
  else
    cleanup_status=$?
  fi
  if discard_staged_output; then
    :
  else
    cleanup_status=1
  fi
  if (( cleanup_status != 0 )); then
    echo "GA-079 cleanup failed after wrapper status ${original_status}" >&2
  fi
  if (( original_status == 0 && cleanup_status != 0 )); then
    exit "$cleanup_status"
  fi
  exit "$original_status"
}
trap on_exit EXIT

if (( SELF_TEST_CLEANUP )); then
  SELF_TEST_OUTPUT_DIR="$(mktemp -d "${TMPDIR:-/tmp}/opensamguk-ga079-self-test-output.XXXXXX")"
  chmod 700 "$SELF_TEST_OUTPUT_DIR"
  SELF_TEST_SOURCE="${RUN_TMP}/self-test-final.json"
  SELF_TEST_TARGET="${SELF_TEST_OUTPUT_DIR}/final.json"
  printf '%s\n' '{"ga079CleanupSelfTest":true}' >"$SELF_TEST_SOURCE"
  OWNED_CONTAINERS=(
    "ga079-cleanup-self-test-container-a|cleanup-self-test"
    "ga079-cleanup-self-test-container-b|cleanup-self-test"
  )
  OWNED_NETWORKS=("ga079-cleanup-self-test-network|cleanup-self-test")
  echo "GA-079 cleanup self-test: forcing owned cleanup failures" >&2
  if stage_cleanup_and_publish "$SELF_TEST_SOURCE" "$SELF_TEST_TARGET"; then
    echo "GA-079 cleanup self-test unexpectedly published an artifact" >&2
    self_test_status=1
  else
    self_test_status=$?
  fi
  if [[ -e "$SELF_TEST_TARGET" || -L "$SELF_TEST_TARGET" ]]; then
    echo "GA-079 cleanup self-test: final artifact exists after failed cleanup" >&2
    self_test_status=1
  fi
  for self_test_stage in "${SELF_TEST_OUTPUT_DIR}"/.ga079-stage.*; do
    if [[ -e "$self_test_stage" || -L "$self_test_stage" ]]; then
      echo "GA-079 cleanup self-test: staged artifact remains after failed cleanup" >&2
      self_test_status=1
    fi
  done
  if [[ "$SELF_TEST_OUTPUT_DIR" == "${TMPDIR:-/tmp}/opensamguk-ga079-self-test-output."* ]]; then
    rm -rf -- "$SELF_TEST_OUTPUT_DIR"
  else
    echo "GA-079 cleanup self-test: refusing to remove unexpected output directory" >&2
    self_test_status=1
  fi
  if (( self_test_status == 0 )); then
    echo "GA-079 cleanup self-test unexpectedly succeeded" >&2
    exit 1
  fi
  echo "GA-079 cleanup self-test: confirmed no final artifact was published" >&2
  exit 1
fi

if ! docker image inspect "$PHP_IMAGE" >/dev/null 2>&1; then
  echo "Building PHP golden capture image..." >&2
  docker build -t "$PHP_IMAGE" -f "${REPO_ROOT}/tools/php-golden/Dockerfile" "$REPO_ROOT"
fi

wait_for_mariadb() {
  local db_container="$1"
  local db_password="$2"
  local attempt
  for ((attempt = 1; attempt <= 90; attempt++)); do
    if docker exec "$db_container" mariadb-admin ping -h127.0.0.1 --protocol=tcp -uroot -p"$db_password" --silent >/dev/null 2>&1; then
      return 0
    fi
    sleep 1
  done
  echo "GA-079 MariaDB did not become ready" >&2
  return 1
}

read_handshake_pid() {
  local handshake_path="$1"
  local attempt pid stage
  for ((attempt = 1; attempt <= 120; attempt++)); do
    if [[ -s "$handshake_path" ]]; then
      pid="$(awk -F= '$1 == "pid" { print $2 }' "$handshake_path")"
      stage="$(awk -F= '$1 == "stage" { print $2 }' "$handshake_path")"
      if [[ "$stage" != "after_nation_turn_success" || ! "$pid" =~ ^[1-9][0-9]*$ ]]; then
        echo "GA-079 crash handshake was malformed" >&2
        return 1
      fi
      printf '%s' "$pid"
      return 0
    fi
    sleep 0.1
  done
  echo "GA-079 timed out waiting for the post-nation_turn crash handshake" >&2
  return 1
}

run_once() {
  local ordinal="$1"
  local run_dir="$2"
  local run_label="${RUN_TAG}-${ordinal}"
  local network_name="opensamguk-ga079-${run_label}"
  local db_container="opensamguk-ga079-db-${run_label}"
  local php_container="opensamguk-ga079-php-${run_label}"
  local db_password="ga079_${RUN_ID:0:24}_${ordinal}"
  local handshake_path="${run_dir}/crash-handshake.txt"
  local install_log="${run_dir}/install.log"
  local work_dir="${run_dir}/work"
  local child_pid

  mkdir -p "$run_dir"
  # _boot.php and install_scenario.php generate legacy d_setting files. The
  # capture needs /work:rw, but never mutates the shared workspace/legacy tree.
  mkdir -p "${work_dir}/tools"
  cp -R "${REPO_ROOT}/legacy" "${work_dir}/legacy"
  cp -R "${REPO_ROOT}/tools/php-golden" "${work_dir}/tools/php-golden"

  docker network create \
    --label opensamguk.capture=ga079 \
    --label "opensamguk.run=${run_label}" \
    "$network_name" >/dev/null
  OWNED_NETWORKS+=("${network_name}|${run_label}")

  docker run -d \
    --name "$db_container" \
    --network "$network_name" \
    --label opensamguk.capture=ga079 \
    --label "opensamguk.run=${run_label}" \
    --tmpfs /var/lib/mysql:rw,noexec,nosuid,size=512m \
    -e MARIADB_ROOT_PASSWORD="$db_password" \
    -e MARIADB_DATABASE=samdb \
    "$DB_IMAGE" \
    --character-set-server=utf8mb4 \
    --collation-server=utf8mb4_bin >/dev/null
  OWNED_CONTAINERS+=("${db_container}|${run_label}")
  wait_for_mariadb "$db_container" "$db_password"

  docker run -d \
    --name "$php_container" \
    --network "$network_name" \
    --label opensamguk.capture=ga079 \
    --label "opensamguk.run=${run_label}" \
    --user "$(id -u):$(id -g)" \
    -v "${work_dir}:/work:rw" \
    -v "${run_dir}:/out:rw" \
    -w /work \
    -e "SAMMO_DB_HOST=${db_container}" \
    -e SAMMO_DB_PORT=3306 \
    -e SAMMO_DB_USER=root \
    -e "SAMMO_DB_PASS=${db_password}" \
    -e SAMMO_DB_NAME=samdb \
    "$PHP_IMAGE" sleep infinity >/dev/null
  OWNED_CONTAINERS+=("${php_container}|${run_label}")

  echo "GA-079 run ${ordinal}: installing fresh scenario_1010..." >&2
  if ! docker exec -w /work "$php_container" php tools/php-golden/install_scenario.php --scenario=1010 --turnterm=120 --sync=0 >"$install_log" 2>&1; then
    echo "GA-079 scenario install failed; owned temporary log will be cleaned" >&2
    return 1
  fi

  echo "GA-079 run ${ordinal}: capturing pre-crash matrix..." >&2
  docker exec -w /work "$php_container" php "$CAPTURE_SCRIPT" --mode=precrash --out=/out/precrash.json

  echo "GA-079 run ${ordinal}: arming post-ring crash checkpoint..." >&2
  docker exec -d -w /work "$php_container" php "$CAPTURE_SCRIPT" \
    --mode=crash-child \
    --handshake=/out/crash-handshake.txt \
    --crash-before=/out/crash-before.json
  child_pid="$(read_handshake_pid "$handshake_path")"
  echo "GA-079 run ${ordinal}: terminating only the acknowledged capture child..." >&2
  docker exec "$php_container" sh -c 'kill -KILL "$1"' sh "$child_pid"
  sleep 0.2

  echo "GA-079 run ${ordinal}: reconnecting to verify persisted ring / old killturn..." >&2
  docker exec -w /work "$php_container" php "$CAPTURE_SCRIPT" \
    --mode=finalize \
    --precrash=/out/precrash.json \
    --crash-before=/out/crash-before.json \
    --parent-signal=SIGKILL \
    --out=/out/final.json
  [[ -s "${run_dir}/final.json" ]] || { echo "GA-079 final capture missing" >&2; return 1; }
}

RUN_ONE_DIR="${RUN_TMP}/run-1"
RUN_TWO_DIR="${RUN_TMP}/run-2"
run_once 1 "$RUN_ONE_DIR"
run_once 2 "$RUN_TWO_DIR"

if ! cmp -s "${RUN_ONE_DIR}/final.json" "${RUN_TWO_DIR}/final.json"; then
  echo "GA-079 fresh scenario captures differ; evidence was not staged" >&2
  exit 1
fi

stage_cleanup_and_publish "${RUN_ONE_DIR}/final.json" "$OUTPUT_PATH"
