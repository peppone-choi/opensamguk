#!/usr/bin/env bash
#
# dump_golden_db.sh — P1 golden DB-fragment dump (devsam capture env, grand truth).
#
# ONE-SHOT, MANUAL HOST STEP — NEVER CI.
#
# Run AFTER capture_che.php has stepped the captured tick. Dumps the row-level
# state of the three tables the P1 vertical slice is allowed to touch
# (general / city / log_entry) into a byte-exact JSON golden fragment:
#
#   logic/src/test/resources/golden/p1/che-golden-db.json
#
# G4 step 5 byte-compares the flushed Kotlin rows against this fragment:
#   general gold/exp/ded/meta jsonb (KEY ORDER!), city agriculture, log_entry text.
# G4 step 6 asserts ONLY general+city+log_entry changed (TruncateContract: no
# inheritance/storage/hall/dynasty writes).
#
# The jsonb (general.meta) MUST be emitted in PHP `Json::encode` key insertion
# order — compact, UTF-8 literal Korean (no \uXXXX), unescaped forward slashes,
# integers without `.0`. devsam stores MySQL; we emit a DB-agnostic row→JSON
# fragment so the Kotlin gate can compare regardless of the V1 Postgres baseline.
#
# Prereq + quirks: see README.md (devsam capture env; reflection credentials;
# install not idempotent; dumps are byte-identical across reruns of the SAME
# stepped state).
#
# Usage:
#   tools/php-golden/dump_golden_db.sh --serverID=<server> \
#       [--generals=ID1,ID2,...] [--cities=ID1,ID2,...] \
#       [--out=logic/src/test/resources/golden/p1/che-golden-db.json]
#
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
DEVSAM_HWE="${REPO_ROOT}/legacy/devsam-core/hwe"
OUT="${REPO_ROOT}/logic/src/test/resources/golden/p1/che-golden-db.json"
SERVER_ID=""
GENERALS=""
CITIES=""

for arg in "$@"; do
  case "$arg" in
    --serverID=*) SERVER_ID="${arg#*=}" ;;   # getopt `=` form (capture-env quirk)
    --generals=*) GENERALS="${arg#*=}" ;;
    --cities=*)   CITIES="${arg#*=}" ;;
    --out=*)      OUT="${arg#*=}" ;;
    *) echo "unknown arg: $arg" >&2; exit 64 ;;
  esac
done

if [[ -z "$SERVER_ID" ]]; then
  echo "dump_golden_db.sh: --serverID required" >&2
  exit 64
fi
if ! command -v php >/dev/null 2>&1; then
  echo "dump_golden_db.sh: php not found — this is the devsam capture-env step (never CI)" >&2
  exit 69   # EX_UNAVAILABLE
fi

# Row-level SELECT → JSON via the SAME PHP runtime that produced the rows, so
# meta jsonb key order + number formatting match Json::encode exactly. We avoid
# `pg_dump`/`mysqldump` precisely because they do not honor PHP's jsonb key
# insertion order; the row→Json::encode path is the byte oracle.
SAMMO_SERVER_ID="$SERVER_ID" \
SAMMO_GOLDEN_GENERALS="$GENERALS" \
SAMMO_GOLDEN_CITIES="$CITIES" \
SAMMO_GOLDEN_OUT="$OUT" \
  php "${DEVSAM_HWE}/../../tools/php-golden/dump_golden_db.php"

echo "wrote $(basename "$OUT")" >&2
