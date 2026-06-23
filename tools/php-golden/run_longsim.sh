#!/usr/bin/env bash
#
# run_longsim.sh — Phase 2 long-simulation golden capture runner (devsam-core PHP, grand truth).
#
# ONE-SHOT, MANUAL HOST STEP — NEVER CI.
#
# Orchestrates the full long-sim capture pipeline:
#   1. Builds the opensamguk-php-golden image if missing
#   2. Creates docker network (if absent)
#   3. Starts MariaDB container
#   4. Runs install_scenario.php (pristine baseline, fresh DB per run)
#   5. Runs capture_longsim.php (the long-sim loop)
#   6. Copies output to host logic/src/test/resources/golden/longsim/
#
# Usage:
#   tools/php-golden/run_longsim.sh [--months-max=360] [--out-dir=logic/src/test/resources/golden/longsim]
#
# Env forwarded to docker exec:
#   SAMMO_DB_HOST  SAMMO_DB_PORT  SAMMO_DB_USER  SAMMO_DB_PASS  SAMMO_DB_NAME

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
OUT_DIR="${REPO_ROOT}/logic/src/test/resources/golden/longsim"
MONTHS_MAX=360

for arg in "$@"; do
  case "$arg" in
    --months-max=*) MONTHS_MAX="${arg#*=}" ;;
    --out-dir=*)    OUT_DIR="${arg#*=}" ;;
    *) echo "unknown arg: $arg" >&2; exit 64 ;;
  esac
done

# Inside the container the repo is mounted at /work, so convert host paths under
# REPO_ROOT to container paths.
if [[ "$OUT_DIR" == "$REPO_ROOT"* ]]; then
  CONTAINER_OUT_DIR="/work${OUT_DIR#$REPO_ROOT}"
else
  echo "out-dir must be inside repo root: $OUT_DIR" >&2
  exit 64
fi

# Normalize OUT_DIR to absolute path
if [[ ! "$OUT_DIR" = /* ]]; then
  OUT_DIR="${REPO_ROOT}/${OUT_DIR}"
fi

# Re-validate after normalization
if [[ "$OUT_DIR" != "$REPO_ROOT"* ]]; then
  echo "out-dir must resolve inside repo root: $OUT_DIR" >&2
  exit 64
fi
CONTAINER_OUT_DIR="/work${OUT_DIR#$REPO_ROOT}"

NETWORK="devsam-golden-net"
DB_CONTAINER="devsam-golden-db"
DB_IMAGE="mariadb:11.4"
PHP_IMAGE="opensamguk-php-golden:latest"

# Build the PHP capture image if it does not exist.
if ! docker image inspect "$PHP_IMAGE" >/dev/null 2>&1; then
  echo "Building PHP capture image ($PHP_IMAGE)..." >&2
  docker build -t "$PHP_IMAGE" -f "${REPO_ROOT}/tools/php-golden/Dockerfile" "$REPO_ROOT"
fi

DB_HOST="${SAMMO_DB_HOST:-$DB_CONTAINER}"
DB_PORT="${SAMMO_DB_PORT:-3306}"
DB_USER="${SAMMO_DB_USER:-root}"
DB_PASS="${SAMMO_DB_PASS:-rootpw}"
DB_NAME="${SAMMO_DB_NAME:-samdb}"

# Ensure cleanup of the MariaDB container on exit, even on failure.
cleanup() {
  docker rm -f "$DB_CONTAINER" >/dev/null 2>&1 || true
}
trap cleanup EXIT

# Ensure output directory exists on host and is clean of stale captures
mkdir -p "$OUT_DIR"
rm -f "$OUT_DIR"/capture-*.json "$OUT_DIR"/manifest_longsim.json

# ── 1. Docker network ───────────────────────────────────────────────────────
if ! docker network inspect "$NETWORK" >/dev/null 2>&1; then
  echo "Creating docker network: $NETWORK" >&2
  docker network create "$NETWORK"
fi

# ── 2. Start MariaDB (fresh per run) ────────────────────────────────────────
echo "Starting MariaDB container..." >&2
if docker ps -a --format '{{.Names}}' | grep -qx "$DB_CONTAINER"; then
  docker rm -f "$DB_CONTAINER" >/dev/null 2>&1
fi

docker run -d \
  --name "$DB_CONTAINER" \
  --network "$NETWORK" \
  -e MARIADB_ROOT_PASSWORD="$DB_PASS" \
  -e MARIADB_DATABASE="$DB_NAME" \
  -p "${DB_PORT}:3306" \
  "$DB_IMAGE" \
  --character-set-server=utf8mb4 \
  --collation-server=utf8mb4_bin \
  >/dev/null

# Wait for MariaDB to be ready
echo "Waiting for MariaDB..." >&2
for i in {1..60}; do
  # TCP readiness check: socket ping reports alive before TCP is listening on 3306,
  # and PHP/PDO connects via TCP, so check the TCP port explicitly.
  if docker exec "$DB_CONTAINER" mariadb-admin ping -h127.0.0.1 --protocol=tcp -u"$DB_USER" -p"$DB_PASS" --silent 2>/dev/null; then
    echo "MariaDB ready" >&2
    break
  fi
  if [[ $i -eq 60 ]]; then
    echo "MariaDB failed to start" >&2
    exit 1
  fi
  sleep 1
done

# ── 3. Install scenario (pristine baseline) ───────────────────────────────────
echo "Installing scenario_1010..." >&2
# turnterm=120 is the install default; capture_longsim.php reads the actual turnterm
# from game_env after install, so the value here only needs to match the devsam default.
docker run --rm \
  --network "$NETWORK" \
  -v "${REPO_ROOT}:/work:rw" \
  -w /work \
  -e SAMMO_DB_HOST="$DB_HOST" \
  -e SAMMO_DB_PORT="$DB_PORT" \
  -e SAMMO_DB_USER="$DB_USER" \
  -e SAMMO_DB_PASS="$DB_PASS" \
  -e SAMMO_DB_NAME="$DB_NAME" \
  "$PHP_IMAGE" \
  php tools/php-golden/install_scenario.php --scenario=1010 --turnterm=120

# ── 4. Run long-sim capture ─────────────────────────────────────────────────
echo "Running long-sim capture (months-max=${MONTHS_MAX})..." >&2
docker run --rm \
  --network "$NETWORK" \
  -v "${REPO_ROOT}:/work:rw" \
  -w /work \
  -e SAMMO_DB_HOST="$DB_HOST" \
  -e SAMMO_DB_PORT="$DB_PORT" \
  -e SAMMO_DB_USER="$DB_USER" \
  -e SAMMO_DB_PASS="$DB_PASS" \
  -e SAMMO_DB_NAME="$DB_NAME" \
  "$PHP_IMAGE" \
  php tools/php-golden/capture_longsim.php --months-max="$MONTHS_MAX" --out-dir="$CONTAINER_OUT_DIR"

echo "Long-sim capture complete." >&2

# ── 5. Report ─────────────────────────────────────────────────────────────────
MANIFEST="$OUT_DIR/manifest_longsim.json"
if [[ -f "$MANIFEST" ]]; then
  echo "Manifest: $MANIFEST" >&2
  # Pretty-print the manifest summary
  if command -v jq >/dev/null 2>&1; then
    jq '{scenario, startYear, turnterm, maxTurns, reachedMaxTurns, totalMonths, pointCount: (.points | length)}' "$MANIFEST"
  else
    echo "(install jq for manifest summary)" >&2
  fi
else
  echo "WARNING: manifest not found at $MANIFEST" >&2
fi

# List all capture files
ls -la "$OUT_DIR"/capture-*.json "$OUT_DIR"/manifest_longsim.json 2>/dev/null || true
