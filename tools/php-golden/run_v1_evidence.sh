#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'USAGE'
Usage: tools/php-golden/run_v1_evidence.sh [--help | --family=<name>]

Captures the remaining v1 PHP evidence families twice against independent,
fresh MariaDB 11.4 scenario_1010 installs. Every capture runs from a disposable
copy of legacy/devsam-core; the shared legacy tree and canonical golden
directories are never mounted into a capture container.

On success, byte-identical artifacts and SHA256SUMS are atomically published to
an ignored build/v1-evidence/evidence-<run-id>/ directory. A mismatch publishes
nothing and reports the differing path, cmp byte/line location, and both hashes.
For diagnosis only, an absolute V1_EVIDENCE_DIAGNOSTIC_DIR under
build/v1-evidence/diagnostic-* retains the first mismatched raw pair and hashes.

Safety:
  - uses unique, labelled opensamguk-v1e-* containers and networks only;
  - removes a Docker resource only after its capture/run labels match;
  - verifies owned-resource removal before publishing evidence;
  - removes only its mktemp directory and its unpublished staging directory;
  - never writes legacy/devsam-core or canonical golden fixtures.

--family may be sortie, conquest, nation-betting, stored-logs, or all. The
default is all; the selector exists for bounded recovery of an independent
family after another family has failed.
USAGE
}

FAMILY_FILTER="all"
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
    --family=sortie|--family=conquest|--family=nation-betting|--family=stored-logs|--family=all)
      FAMILY_FILTER="${1#--family=}"
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
PINNED_HIDDEN_SEED="8ebfeb6fa932a181ec9ef43b7473f4c9"
EVIDENCE_BASE="${REPO_ROOT}/build/v1-evidence"
DIAGNOSTIC_OUTPUT_DIR="${V1_EVIDENCE_DIAGNOSTIC_DIR:-}"

if [[ -n "$DIAGNOSTIC_OUTPUT_DIR" ]]; then
  case "$DIAGNOSTIC_OUTPUT_DIR" in
    "${EVIDENCE_BASE}"/diagnostic-*)
      ;;
    *)
      echo "refusing unsafe v1 evidence diagnostic output path" >&2
      exit 64
      ;;
  esac
  if [[ -L "$DIAGNOSTIC_OUTPUT_DIR" || -e "$DIAGNOSTIC_OUTPUT_DIR" ]]; then
    echo "refusing existing v1 evidence diagnostic output path" >&2
    exit 64
  fi
fi

for required_path in \
  "${REPO_ROOT}/legacy/devsam-core/vendor/autoload.php" \
  "${REPO_ROOT}/tools/php-golden/Dockerfile" \
  "${REPO_ROOT}/tools/php-golden/install_scenario.php" \
  "${REPO_ROOT}/tools/php-golden/capture_sortie_outer.php" \
  "${REPO_ROOT}/tools/php-golden/capture_conquercity.php" \
  "${REPO_ROOT}/tools/php-golden/capture_nation_betting.php" \
  "${REPO_ROOT}/tools/php-golden/capture_stored_logs.php"; do
  if [[ ! -f "$required_path" ]]; then
    echo "v1 evidence prerequisite missing: ${required_path#${REPO_ROOT}/}" >&2
    exit 1
  fi
done

if ! command -v docker >/dev/null 2>&1; then
  echo "Docker is required for v1 PHP evidence capture" >&2
  exit 1
fi
if ! docker version --format '{{.Server.Version}}' >/dev/null 2>&1; then
  echo "Docker daemon is not reachable" >&2
  exit 1
fi

if command -v uuidgen >/dev/null 2>&1; then
  RUN_ID="$(uuidgen | tr '[:upper:]' '[:lower:]' | tr -d '-')"
else
  RUN_ID="$(openssl rand -hex 16)"
fi
RUN_TAG="${RUN_ID:0:12}"
RUN_TMP="$(mktemp -d "${TMPDIR:-/tmp}/opensamguk-v1-evidence.XXXXXX")"
chmod 700 "$RUN_TMP"

declare -a OWNED_CONTAINERS=()
declare -a OWNED_NETWORKS=()
declare -a OWNED_VOLUMES=()
declare -a FAMILIES=()
declare -a ARTIFACT_PATHS=()

if [[ "$FAMILY_FILTER" == "all" || "$FAMILY_FILTER" == "sortie" ]]; then
  FAMILIES+=("sortie")
  ARTIFACT_PATHS+=("sortie/sortie-outer.json")
fi
if [[ "$FAMILY_FILTER" == "all" || "$FAMILY_FILTER" == "conquest" ]]; then
  FAMILIES+=("conquest")
  ARTIFACT_PATHS+=(
    "conquest/conquercity-survive-01.json"
    "conquest/conquercity-collapse-full-01.json"
    "conquest/conquercity-collapse-only-random-01.json"
    "conquest/conquercity-capital-01.json"
    "conquest/conflict-01.json"
  )
fi
if [[ "$FAMILY_FILTER" == "all" || "$FAMILY_FILTER" == "nation-betting" ]]; then
  FAMILIES+=("nation-betting")
  ARTIFACT_PATHS+=("nation-betting/nation-betting.json")
fi
if [[ "$FAMILY_FILTER" == "all" || "$FAMILY_FILTER" == "stored-logs" ]]; then
  FAMILIES+=("stored-logs")
  ARTIFACT_PATHS+=("stored-logs/stored-logs.json")
fi

CLEANUP_ATTEMPTED=0
CLEANUP_STATUS=0
STAGE_DIR=""
FINAL_OUTPUT_DIR="${EVIDENCE_BASE}/evidence-${RUN_TAG}"

resource_label_value() {
  local resource_type="$1"
  local resource_name="$2"
  local label_key="$3"
  local label_template
  if [[ "$resource_type" == "container" ]]; then
    label_template="{{ index .Config.Labels \"${label_key}\" }}"
  else
    label_template="{{ index .Labels \"${label_key}\" }}"
  fi
  docker "$resource_type" inspect -f "$label_template" "$resource_name"
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
        "$diagnostic" == *"no such volume"* ||
        "$diagnostic" == *"network ${resource_name} not found"* ]]; then
    return 1
  fi
  echo "cannot verify ${resource_type} cleanup: ${diagnostic}" >&2
  return 2
}

remove_owned_container() {
  local container_name="$1"
  local expected_run="$2"
  local inspect_status capture_label run_label

  if resource_is_present container "$container_name"; then
    :
  else
    inspect_status=$?
    if (( inspect_status == 1 )); then
      return 0
    fi
    return 1
  fi

  capture_label="$(resource_label_value container "$container_name" opensamguk.capture)" || return 1
  run_label="$(resource_label_value container "$container_name" opensamguk.run)" || return 1
  if [[ "$capture_label" != "v1-evidence" || "$run_label" != "$expected_run" ]]; then
    echo "refusing to remove non-owned container: ${container_name}" >&2
    return 1
  fi

  docker rm -f "$container_name" >/dev/null || return 1
  if resource_is_present container "$container_name"; then
    echo "owned container remains after cleanup: ${container_name}" >&2
    return 1
  else
    inspect_status=$?
    (( inspect_status == 1 )) || return 1
  fi
}

remove_owned_network() {
  local network_name="$1"
  local expected_run="$2"
  local inspect_status capture_label run_label

  if resource_is_present network "$network_name"; then
    :
  else
    inspect_status=$?
    if (( inspect_status == 1 )); then
      return 0
    fi
    return 1
  fi

  capture_label="$(resource_label_value network "$network_name" opensamguk.capture)" || return 1
  run_label="$(resource_label_value network "$network_name" opensamguk.run)" || return 1
  if [[ "$capture_label" != "v1-evidence" || "$run_label" != "$expected_run" ]]; then
    echo "refusing to remove non-owned network: ${network_name}" >&2
    return 1
  fi

  docker network rm "$network_name" >/dev/null || return 1
  if resource_is_present network "$network_name"; then
    echo "owned network remains after cleanup: ${network_name}" >&2
    return 1
  else
    inspect_status=$?
    (( inspect_status == 1 )) || return 1
  fi
}

remove_owned_volume() {
  local volume_name="$1"
  local expected_run="$2"
  local inspect_status capture_label run_label

  if resource_is_present volume "$volume_name"; then
    :
  else
    inspect_status=$?
    if (( inspect_status == 1 )); then
      return 0
    fi
    return 1
  fi

  capture_label="$(resource_label_value volume "$volume_name" opensamguk.capture)" || return 1
  run_label="$(resource_label_value volume "$volume_name" opensamguk.run)" || return 1
  if [[ "$capture_label" != "v1-evidence" || "$run_label" != "$expected_run" ]]; then
    echo "refusing to remove non-owned volume: ${volume_name}" >&2
    return 1
  fi

  docker volume rm "$volume_name" >/dev/null || return 1
  if resource_is_present volume "$volume_name"; then
    echo "owned volume remains after cleanup: ${volume_name}" >&2
    return 1
  else
    inspect_status=$?
    (( inspect_status == 1 )) || return 1
  fi
}

cleanup_owned_resources() {
  local cleanup_status=0
  local entry name run_label idx

  if (( CLEANUP_ATTEMPTED )); then
    return "$CLEANUP_STATUS"
  fi
  CLEANUP_ATTEMPTED=1

  for ((idx=${#OWNED_CONTAINERS[@]} - 1; idx >= 0; idx--)); do
    entry="${OWNED_CONTAINERS[$idx]}"
    name="${entry%%|*}"
    run_label="${entry#*|}"
    if ! remove_owned_container "$name" "$run_label"; then
      cleanup_status=1
    fi
  done
  for ((idx=${#OWNED_VOLUMES[@]} - 1; idx >= 0; idx--)); do
    entry="${OWNED_VOLUMES[$idx]}"
    name="${entry%%|*}"
    run_label="${entry#*|}"
    if ! remove_owned_volume "$name" "$run_label"; then
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

  if [[ "$RUN_TMP" == "${TMPDIR:-/tmp}/opensamguk-v1-evidence."* ]]; then
    if ! rm -rf -- "$RUN_TMP" || [[ -e "$RUN_TMP" ]]; then
      echo "failed to remove v1 evidence temporary directory" >&2
      cleanup_status=1
    fi
  else
    echo "refusing to remove unexpected v1 evidence temporary directory" >&2
    cleanup_status=1
  fi

  CLEANUP_STATUS=$cleanup_status
  return "$cleanup_status"
}

discard_stage() {
  if [[ -z "$STAGE_DIR" ]]; then
    return 0
  fi
  if [[ "$STAGE_DIR" != "${EVIDENCE_BASE}/.stage-${RUN_TAG}."* ]]; then
    echo "refusing to remove unexpected v1 evidence staging directory" >&2
    return 1
  fi
  if [[ -e "$STAGE_DIR" || -L "$STAGE_DIR" ]]; then
    rm -rf -- "$STAGE_DIR" || return 1
  fi
  if [[ -e "$STAGE_DIR" || -L "$STAGE_DIR" ]]; then
    echo "v1 evidence staging directory remains after discard" >&2
    return 1
  fi
  STAGE_DIR=""
}

preserve_mismatch_diagnostic() {
  local relative_path="$1"
  local first_path="$2"
  local second_path="$3"
  local first_hash="$4"
  local second_hash="$5"

  if [[ -z "$DIAGNOSTIC_OUTPUT_DIR" ]]; then
    return 0
  fi
  if [[ -L "$DIAGNOSTIC_OUTPUT_DIR" || -e "$DIAGNOSTIC_OUTPUT_DIR" ]]; then
    echo "v1 evidence diagnostic output path appeared during capture" >&2
    return 1
  fi

  mkdir -p "${DIAGNOSTIC_OUTPUT_DIR}/run-1/$(dirname "$relative_path")" \
    "${DIAGNOSTIC_OUTPUT_DIR}/run-2/$(dirname "$relative_path")" || return 1
  install -m 0644 "$first_path" "${DIAGNOSTIC_OUTPUT_DIR}/run-1/${relative_path}" || return 1
  install -m 0644 "$second_path" "${DIAGNOSTIC_OUTPUT_DIR}/run-2/${relative_path}" || return 1
  {
    printf '%s  run-1/%s\n' "$first_hash" "$relative_path"
    printf '%s  run-2/%s\n' "$second_hash" "$relative_path"
  } >"${DIAGNOSTIC_OUTPUT_DIR}/RAW_SHA256SUMS" || return 1
  echo "v1 evidence mismatch diagnostic=${DIAGNOSTIC_OUTPUT_DIR#${REPO_ROOT}/}" >&2
}

on_exit() {
  local original_status=$?
  local cleanup_status=0
  trap - EXIT

  cleanup_owned_resources || cleanup_status=1
  discard_stage || cleanup_status=1
  if (( original_status == 0 && cleanup_status != 0 )); then
    exit 1
  fi
  exit "$original_status"
}
trap on_exit EXIT

if ! docker image inspect "$PHP_IMAGE" >/dev/null 2>&1; then
  echo "Building PHP golden capture image..." >&2
  docker build -t "$PHP_IMAGE" -f "${REPO_ROOT}/tools/php-golden/Dockerfile" "$REPO_ROOT"
fi

print_mariadb_diagnostics() {
  local db_container="$1"
  docker container inspect \
    --format 'status={{.State.Status}} exit={{.State.ExitCode}} oom={{.State.OOMKilled}} error={{.State.Error}}' \
    "$db_container" >&2 || true
  docker logs --tail 80 "$db_container" >&2 || true
}

wait_for_mariadb() {
  local db_container="$1"
  local db_password="$2"
  local attempt container_status
  for ((attempt=1; attempt<=180; attempt++)); do
    if docker exec "$db_container" \
      mariadb-admin ping -h127.0.0.1 --protocol=tcp -uroot -p"$db_password" --silent \
      >/dev/null 2>&1; then
      return 0
    fi
    container_status="$(docker container inspect --format '{{.State.Status}}' "$db_container" 2>/dev/null || true)"
    if [[ "$container_status" == "exited" || "$container_status" == "dead" ]]; then
      echo "MariaDB exited before becoming ready for v1 evidence capture" >&2
      print_mariadb_diagnostics "$db_container"
      return 1
    fi
    sleep 1
  done
  echo "MariaDB did not become ready for v1 evidence capture" >&2
  print_mariadb_diagnostics "$db_container"
  return 1
}

print_capture_failure() {
  local family="$1"
  local ordinal="$2"
  local log_path="$3"
  echo "v1 evidence ${family} run ${ordinal} failed:" >&2
  tail -80 "$log_path" >&2
}

ACTIVE_DB_CONTAINER=""
ACTIVE_PHP_CONTAINER=""
ACTIVE_DB_PASSWORD=""
RUN_NETWORK_NAME="opensamguk-v1e-net-${RUN_TAG}"

create_capture_database() {
  local db_container="$1"
  local db_password="$2"
  local database_name="$3"

  if [[ ! "$database_name" =~ ^samdb_v1e_[a-z0-9_]+$ ]]; then
    echo "refusing unsafe v1 evidence database name" >&2
    return 1
  fi
  if ! docker exec -e "MYSQL_PWD=${db_password}" "$db_container" \
    mariadb --protocol=tcp -h127.0.0.1 -uroot \
    -e "CREATE DATABASE ${database_name} CHARACTER SET utf8mb4 COLLATE utf8mb4_bin" \
    >/dev/null; then
    echo "failed to create fresh v1 evidence capture database" >&2
    print_mariadb_diagnostics "$db_container"
    return 1
  fi
}

start_run_ordinal() {
  local ordinal="$1"
  local run_label="${RUN_TAG}-run-${ordinal}"
  local db_volume="opensamguk-v1e-dbdata-${RUN_TAG}-${ordinal}"
  local db_container="opensamguk-v1e-db-${RUN_TAG}-${ordinal}"
  local php_container="opensamguk-v1e-php-${RUN_TAG}-${ordinal}"
  local db_password="v1e_${RUN_ID:0:20}_${ordinal}"
  local run_root="${RUN_TMP}/runs/run-${ordinal}"
  local output_root="${RUN_TMP}/outputs/run-${ordinal}"

  mkdir -p "$run_root" "$output_root"

  docker volume create \
    --label opensamguk.capture=v1-evidence \
    --label "opensamguk.run=${run_label}" \
    "$db_volume" >/dev/null
  OWNED_VOLUMES+=("${db_volume}|${run_label}")

  docker run -d \
    --name "$db_container" \
    --network "$RUN_NETWORK_NAME" \
    --label opensamguk.capture=v1-evidence \
    --label "opensamguk.run=${run_label}" \
    --mount "type=volume,source=${db_volume},target=/var/lib/mysql" \
    -e MARIADB_ROOT_PASSWORD="$db_password" \
    "$DB_IMAGE" \
    --character-set-server=utf8mb4 \
    --collation-server=utf8mb4_bin >/dev/null
  OWNED_CONTAINERS+=("${db_container}|${run_label}")
  wait_for_mariadb "$db_container" "$db_password"

  docker run -d \
    --name "$php_container" \
    --network "$RUN_NETWORK_NAME" \
    --label opensamguk.capture=v1-evidence \
    --label "opensamguk.run=${run_label}" \
    --user "$(id -u):$(id -g)" \
    -v "${run_root}:/work:rw" \
    -v "${output_root}:/out:rw" \
    -w /work \
    -e "SAMMO_DB_HOST=${db_container}" \
    -e SAMMO_DB_PORT=3306 \
    -e SAMMO_DB_USER=root \
    -e "SAMMO_DB_PASS=${db_password}" \
    -e "SAMMO_EVIDENCE_HIDDEN_SEED=${PINNED_HIDDEN_SEED}" \
    "$PHP_IMAGE" sleep infinity >/dev/null
  OWNED_CONTAINERS+=("${php_container}|${run_label}")

  ACTIVE_DB_CONTAINER="$db_container"
  ACTIVE_PHP_CONTAINER="$php_container"
  ACTIVE_DB_PASSWORD="$db_password"
}

run_family_once() {
  local family="$1"
  local ordinal="$2"
  local db_container="$3"
  local php_container="$4"
  local db_password="$5"
  local database_family="${family//-/_}"
  local database_name="samdb_v1e_${RUN_TAG:0:8}_${ordinal}_${database_family}"
  local run_dir="${RUN_TMP}/runs/run-${ordinal}/${family}"
  local work_dir="${RUN_TMP}/runs/run-${ordinal}/work/${family}"
  local output_dir="${RUN_TMP}/outputs/run-${ordinal}/${family}"
  local install_log="${run_dir}/install.log"
  local capture_log="${run_dir}/capture.log"
  local capture_script capture_args

  create_capture_database "$db_container" "$db_password" "$database_name"
  mkdir -p "$run_dir" "${work_dir}/legacy" "${work_dir}/tools" "$output_dir"
  cp -R "${REPO_ROOT}/legacy/devsam-core" "${work_dir}/legacy/devsam-core"
  cp -R "${REPO_ROOT}/tools/php-golden" "${work_dir}/tools/php-golden"

  case "$family" in
    sortie)
      capture_script="tools/php-golden/capture_sortie_outer.php"
      capture_args=(--out=/out/sortie/sortie-outer.json)
      ;;
    conquest)
      capture_script="tools/php-golden/capture_conquercity.php"
      capture_args=(--out-dir=/out/conquest)
      ;;
    nation-betting)
      capture_script="tools/php-golden/capture_nation_betting.php"
      capture_args=(--out=/out/nation-betting/nation-betting.json)
      ;;
    stored-logs)
      capture_script="tools/php-golden/capture_stored_logs.php"
      capture_args=(--out=/out/stored-logs/stored-logs.json)
      ;;
    *)
      echo "unknown v1 evidence family: ${family}" >&2
      return 1
      ;;
  esac

  if ! docker exec -w "/work/work/${family}" "$php_container" php -l "$capture_script" >"$capture_log" 2>&1; then
    print_capture_failure "$family" "$ordinal" "$capture_log"
    return 1
  fi

  echo "v1 evidence ${family} run ${ordinal}: fresh scenario_1010 install" >&2
  if ! docker exec -w "/work/work/${family}" -e "SAMMO_DB_NAME=${database_name}" "$php_container" \
    php tools/php-golden/install_scenario.php --scenario=1010 --turnterm=120 --sync=0 \
    >"$install_log" 2>&1; then
    echo "v1 evidence ${family} run ${ordinal} install failed; sensitive install log was not printed" >&2
    return 1
  fi

  echo "v1 evidence ${family} run ${ordinal}: capture" >&2
  if ! docker exec -w "/work/work/${family}" -e "SAMMO_DB_NAME=${database_name}" "$php_container" \
    php "$capture_script" "${capture_args[@]}" >"$capture_log" 2>&1; then
    print_capture_failure "$family" "$ordinal" "$capture_log"
    return 1
  fi
}

docker network create \
  --label opensamguk.capture=v1-evidence \
  --label "opensamguk.run=${RUN_TAG}" \
  "$RUN_NETWORK_NAME" >/dev/null
OWNED_NETWORKS+=("${RUN_NETWORK_NAME}|${RUN_TAG}")

for ordinal in 1 2; do
  start_run_ordinal "$ordinal"
  for family in "${FAMILIES[@]}"; do
    run_family_once "$family" "$ordinal" "$ACTIVE_DB_CONTAINER" "$ACTIVE_PHP_CONTAINER" "$ACTIVE_DB_PASSWORD"
  done
done

RUN_ONE_OUTPUT="${RUN_TMP}/outputs/run-1"
RUN_TWO_OUTPUT="${RUN_TMP}/outputs/run-2"
for relative_path in "${ARTIFACT_PATHS[@]}"; do
  first_path="${RUN_ONE_OUTPUT}/${relative_path}"
  second_path="${RUN_TWO_OUTPUT}/${relative_path}"
  if [[ ! -s "$first_path" || ! -s "$second_path" ]]; then
    echo "v1 evidence artifact missing or empty: ${relative_path}" >&2
    exit 1
  fi
  if ! cmp "$first_path" "$second_path"; then
    first_hash="$(shasum -a 256 "$first_path" | awk '{print $1}')"
    second_hash="$(shasum -a 256 "$second_path" | awk '{print $1}')"
    echo "v1 evidence nondeterminism: ${relative_path}" >&2
    echo "run-1 sha256=${first_hash}" >&2
    echo "run-2 sha256=${second_hash}" >&2
    if ! preserve_mismatch_diagnostic "$relative_path" "$first_path" "$second_path" "$first_hash" "$second_hash"; then
      echo "v1 evidence failed to preserve mismatch diagnostic" >&2
    fi
    exit 1
  fi
done

artifact_count_one="$(find "$RUN_ONE_OUTPUT" -type f | wc -l | tr -d ' ')"
artifact_count_two="$(find "$RUN_TWO_OUTPUT" -type f | wc -l | tr -d ' ')"
expected_count="${#ARTIFACT_PATHS[@]}"
if [[ "$artifact_count_one" != "$expected_count" || "$artifact_count_two" != "$expected_count" ]]; then
  echo "unexpected v1 evidence artifact count: run-1=${artifact_count_one} run-2=${artifact_count_two} expected=${expected_count}" >&2
  exit 1
fi

mkdir -p "$EVIDENCE_BASE"
if [[ -L "$EVIDENCE_BASE" || -e "$FINAL_OUTPUT_DIR" || -L "$FINAL_OUTPUT_DIR" ]]; then
  echo "refusing unsafe or existing v1 evidence output path" >&2
  exit 1
fi
STAGE_DIR="$(mktemp -d "${EVIDENCE_BASE}/.stage-${RUN_TAG}.XXXXXX")"

for relative_path in "${ARTIFACT_PATHS[@]}"; do
  mkdir -p "${STAGE_DIR}/$(dirname "$relative_path")"
  install -m 0644 "${RUN_ONE_OUTPUT}/${relative_path}" "${STAGE_DIR}/${relative_path}"
  cmp -s "${RUN_ONE_OUTPUT}/${relative_path}" "${STAGE_DIR}/${relative_path}"
done

write_manifest() {
  local index separator relative_path
  {
    printf '{\n'
    printf '  "schema": "opensamguk-v1-evidence-v1",\n'
    printf '  "comparedRuns": 2,\n'
    printf '  "comparison": "byte-identical",\n'
    printf '  "families": ['
    separator=""
    for ((index = 0; index < ${#FAMILIES[@]}; index++)); do
      printf '%s"%s"' "$separator" "${FAMILIES[$index]}"
      separator=", "
    done
    printf '],\n'
    printf '  "artifacts": [\n'
    for ((index = 0; index < ${#ARTIFACT_PATHS[@]}; index++)); do
      relative_path="${ARTIFACT_PATHS[$index]}"
      if (( index + 1 < ${#ARTIFACT_PATHS[@]} )); then
        printf '    "%s",\n' "$relative_path"
      else
        printf '    "%s"\n' "$relative_path"
      fi
    done
    printf '  ]\n'
    printf '}\n'
  } >"${STAGE_DIR}/MANIFEST.json"
}

write_manifest

(
  cd "$STAGE_DIR"
  for relative_path in "${ARTIFACT_PATHS[@]}" MANIFEST.json; do
    checksum="$(shasum -a 256 "$relative_path" | awk '{print $1}')"
    printf '%s  %s\n' "$checksum" "$relative_path"
  done >SHA256SUMS
)

if ! cleanup_owned_resources; then
  echo "v1 evidence cleanup failed; staged output was not published" >&2
  exit 1
fi

mv -- "$STAGE_DIR" "$FINAL_OUTPUT_DIR"
STAGE_DIR=""
echo "v1 evidence captures matched byte-for-byte and owned cleanup was verified." >&2
echo "output=${FINAL_OUTPUT_DIR#${REPO_ROOT}/}"
sed -n '1,20p' "${FINAL_OUTPUT_DIR}/SHA256SUMS"
