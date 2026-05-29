#!/usr/bin/env bash
#
# dump_golden_db.sh — P1 golden DB-fragment dump (devsam capture env, grand truth).
#
# ONE-SHOT, MANUAL HOST STEP — NEVER CI.
#
# Run AFTER capture_che.php has left the DB in the canonical commerce_success
# post-turn state. Dumps the row-level state of the three tables the P1 vertical
# slice is allowed to touch (general / city / log_entry) into a byte-exact JSON
# golden fragment:
#
#   logic/src/test/resources/golden/p1/che-golden-db.json
#
# G4 step 5 byte-compares the flushed Kotlin rows against this fragment:
#   general gold/exp/ded/aux jsonb (KEY ORDER!), city commerce/agriculture, log text.
# G4 step 6 asserts ONLY general+city+log_entry changed (TruncateContract: no
# inheritance/storage/hall/dynasty writes).
#
# The jsonb (general.aux) MUST be emitted in PHP `Json::encode` key insertion order
# — compact, UTF-8 literal Korean (no \uXXXX), unescaped forward slashes, integers
# without `.0`. devsam stores MariaDB; we emit a DB-agnostic row→JSON fragment so
# the Kotlin gate can compare regardless of the V1 Postgres baseline.
#
# Boot/DB binding is via _boot.php (the install-time d_setting/DB.php substitution
# the live game uses — there is NO reflection setSelfConnInfo in this revision).
# The DB connection comes from SAMMO_DB_* env (see _boot.php).
#
# Prereq + quirks: see README.md (devsam capture env; install not idempotent;
# dumps are byte-identical across reruns of the SAME stepped state).
#
# Usage (inside the php capture container, repo mounted at /work):
#   SAMMO_DB_HOST=… SAMMO_DB_USER=… SAMMO_DB_PASS=… SAMMO_DB_NAME=… \
#   tools/php-golden/dump_golden_db.sh \
#       --generals=ID1,ID2,... --cities=ID1,ID2,... \
#       [--out=logic/src/test/resources/golden/p1/che-golden-db.json]
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
OUT="${REPO_ROOT}/logic/src/test/resources/golden/p1/che-golden-db.json"
GENERALS=""
CITIES=""

for arg in "$@"; do
  case "$arg" in
    --generals=*) GENERALS="${arg#*=}" ;;   # getopt `=` form (capture-env quirk)
    --cities=*)   CITIES="${arg#*=}" ;;
    --out=*)      OUT="${arg#*=}" ;;
    *) echo "unknown arg: $arg" >&2; exit 64 ;;
  esac
done

if ! command -v php >/dev/null 2>&1; then
  echo "dump_golden_db.sh: php not found — this is the devsam capture-env step (never CI)" >&2
  exit 69   # EX_UNAVAILABLE
fi

# Row-level SELECT → JSON via the SAME PHP runtime that produced the rows, so the
# aux jsonb key order + number formatting match Json::encode exactly. We avoid
# mysqldump precisely because it does not honor PHP's jsonb key insertion order;
# the row→Json::encode path is the byte oracle.
SAMMO_GOLDEN_GENERALS="$GENERALS" \
SAMMO_GOLDEN_CITIES="$CITIES" \
SAMMO_GOLDEN_OUT="$OUT" \
  php "${REPO_ROOT}/tools/php-golden/dump_golden_db.php"

echo "wrote $(basename "$OUT")" >&2
