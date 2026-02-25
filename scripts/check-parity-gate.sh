#!/usr/bin/env bash
set -euo pipefail

# Full parity gate checker (host-run intended)
# Scope:
# - OAuth login/register/link route readiness
# - Lobby -> world entry readiness
# - Admin critical read-only flows
#
# Non-destructive by default for world/admin state:
# - No world create/reset/delete
# - No admin mutation endpoints
# - Account phase creates one disposable user (unless --skip-account-write)

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FRONTEND_DIR="$ROOT_DIR/frontend"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
ARTIFACT_DIR="${PARITY_ARTIFACT_DIR:-$ROOT_DIR/qa/results/parity-gate/$STAMP}"
LOG_DIR="$ARTIFACT_DIR/logs"
HTTP_DIR="$ARTIFACT_DIR/http"
mkdir -p "$LOG_DIR" "$HTTP_DIR"

BASE_URL="${PARITY_BASE_URL:-http://localhost:3000}"
API_URL="${PARITY_API_URL:-http://localhost:8080/api}"
ADMIN_TOKEN="${PARITY_ADMIN_TOKEN:-}"
ALLOW_MISSING_ADMIN=0
SKIP_PLAYWRIGHT=0
SKIP_ACCOUNT_WRITE=0
SKIP_OAUTH_PREFLIGHT=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --base-url) BASE_URL="$2"; shift 2 ;;
    --api-url) API_URL="$2"; shift 2 ;;
    --admin-token) ADMIN_TOKEN="$2"; shift 2 ;;
    --allow-missing-admin) ALLOW_MISSING_ADMIN=1; shift ;;
    --skip-playwright) SKIP_PLAYWRIGHT=1; shift ;;
    --skip-account-write) SKIP_ACCOUNT_WRITE=1; shift ;;
    --skip-oauth-preflight) SKIP_OAUTH_PREFLIGHT=1; shift ;;
    -h|--help)
      cat <<'USAGE'
Usage: scripts/check-parity-gate.sh [options]

Options:
  --base-url URL            Frontend URL (default: http://localhost:3000)
  --api-url URL             API URL (default: http://localhost:8080/api)
  --admin-token TOKEN       Bearer token for admin read-only checks
  --allow-missing-admin     Do not fail when admin token is absent
  --skip-playwright         Skip Playwright OAuth/lobby mocked flow
  --skip-account-write      Skip disposable register/login API check
  --skip-oauth-preflight    Skip frontend verify:oauth-gate --probe

Env aliases:
  PARITY_BASE_URL, PARITY_API_URL, PARITY_ADMIN_TOKEN, PARITY_ARTIFACT_DIR
USAGE
      exit 0
      ;;
    *)
      echo "Unknown option: $1" >&2
      exit 2
      ;;
  esac
done

PASS=0
FAIL=0
SKIP=0
INCOMPLETE=0
RESULT_LINES=()

note() { echo "[$(date -u +%H:%M:%S)] $*" | tee -a "$LOG_DIR/run.log"; }
mark_pass() { PASS=$((PASS+1)); RESULT_LINES+=("PASS | $1"); note "PASS: $1"; }
mark_fail() { FAIL=$((FAIL+1)); RESULT_LINES+=("FAIL | $1"); note "FAIL: $1"; }
mark_skip() { SKIP=$((SKIP+1)); RESULT_LINES+=("SKIP | $1"); note "SKIP: $1"; }
mark_incomplete() { INCOMPLETE=$((INCOMPLETE+1)); RESULT_LINES+=("INCOMPLETE | $1"); note "INCOMPLETE: $1"; }

require_cmd() {
  if command -v "$1" >/dev/null 2>&1; then
    mark_pass "command available: $1"
  else
    mark_fail "missing command: $1"
  fi
}

request() {
  local name="$1" method="$2" url="$3" data="${4:-}" auth="${5:-}"
  local base="$HTTP_DIR/$name"
  local body_file="${base}.body.json"
  local meta_file="${base}.meta.txt"
  local status

  if [[ -n "$data" ]]; then
    if [[ -n "$auth" ]]; then
      status=$(curl -sS -o "$body_file" -w "%{http_code}" -X "$method" "$url" \
        -H "content-type: application/json" -H "authorization: Bearer $auth" \
        --data "$data")
    else
      status=$(curl -sS -o "$body_file" -w "%{http_code}" -X "$method" "$url" \
        -H "content-type: application/json" --data "$data")
    fi
  else
    if [[ -n "$auth" ]]; then
      status=$(curl -sS -o "$body_file" -w "%{http_code}" -X "$method" "$url" \
        -H "authorization: Bearer $auth")
    else
      status=$(curl -sS -o "$body_file" -w "%{http_code}" -X "$method" "$url")
    fi
  fi

  {
    echo "name=$name"
    echo "method=$method"
    echo "url=$url"
    echo "status=$status"
  } > "$meta_file"

  echo "$status"
}

extract_json_field() {
  local key="$1" file="$2"
  node -e '
    const fs=require("fs");
    const key=process.argv[1];
    const file=process.argv[2];
    try {
      const data=JSON.parse(fs.readFileSync(file,"utf8"));
      const v=data?.[key];
      if (typeof v === "string" || typeof v === "number") process.stdout.write(String(v));
    } catch {}
  ' "$key" "$file"
}

note "Artifacts: $ARTIFACT_DIR"
note "BASE_URL=$BASE_URL"
note "API_URL=$API_URL"

require_cmd curl
require_cmd node
require_cmd pnpm

if [[ $FAIL -gt 0 ]]; then
  note "Critical preflight failed."
fi

if [[ $SKIP_OAUTH_PREFLIGHT -eq 0 ]]; then
  note "Running frontend OAuth preflight probe..."
  if (cd "$FRONTEND_DIR" && NEXT_PUBLIC_API_URL="$API_URL" pnpm verify:oauth-gate --probe) >"$LOG_DIR/oauth-preflight.log" 2>&1; then
    mark_pass "frontend verify:oauth-gate --probe"
  else
    mark_fail "frontend verify:oauth-gate --probe"
  fi
else
  mark_skip "oauth preflight skipped"
fi

if [[ $SKIP_PLAYWRIGHT -eq 0 ]]; then
  note "Running Playwright OAuth gate spec (mocked flow)..."
  if (cd "$FRONTEND_DIR" && PLAYWRIGHT_BASE_URL="$BASE_URL" pnpm playwright test e2e/oauth-gate.spec.ts --project=chromium --reporter=line) >"$LOG_DIR/playwright-oauth.log" 2>&1; then
    mark_pass "playwright e2e/oauth-gate.spec.ts"
  else
    mark_fail "playwright e2e/oauth-gate.spec.ts"
  fi
else
  mark_skip "playwright OAuth spec skipped"
fi

TOKEN=""
if [[ $SKIP_ACCOUNT_WRITE -eq 0 ]]; then
  uid="parity_$(date -u +%s)_$RANDOM"
  register_payload="{\"loginId\":\"$uid\",\"displayName\":\"$uid\",\"password\":\"test1234!\"}"
  note "Checking auth register/login with disposable account: $uid"
  s=$(request "auth-register" POST "$API_URL/auth/register" "$register_payload")
  if [[ "$s" == "200" || "$s" == "201" || "$s" == "409" ]]; then
    mark_pass "POST /auth/register reachable (status $s)"
  else
    mark_fail "POST /auth/register unexpected status $s"
  fi

  login_payload="{\"loginId\":\"$uid\",\"password\":\"test1234!\"}"
  s=$(request "auth-login" POST "$API_URL/auth/login" "$login_payload")
  if [[ "$s" == "200" ]]; then
    TOKEN="$(extract_json_field token "$HTTP_DIR/auth-login.body.json")"
    if [[ -n "$TOKEN" ]]; then
      mark_pass "POST /auth/login token issued"
    else
      mark_fail "POST /auth/login missing token field"
    fi
  else
    mark_fail "POST /auth/login unexpected status $s"
  fi
else
  mark_skip "account write phase skipped"
fi

note "Checking OAuth link route readiness..."
s=$(request "oauth-link-unauth" POST "$API_URL/account/oauth/kakao/link" "{}")
if [[ "$s" == "401" || "$s" == "403" ]]; then
  mark_pass "POST /account/oauth/kakao/link protected (status $s)"
elif [[ "$s" == "404" || "$s" == "405" ]]; then
  mark_fail "POST /account/oauth/kakao/link missing (status $s)"
else
  mark_pass "POST /account/oauth/kakao/link reachable (status $s)"
fi

if [[ -n "$TOKEN" ]]; then
  s=$(request "oauth-providers-auth" GET "$API_URL/account/oauth" "" "$TOKEN")
  if [[ "$s" == "200" ]]; then
    mark_pass "GET /account/oauth authenticated"
  else
    mark_fail "GET /account/oauth expected 200, got $s"
  fi
else
  mark_incomplete "OAuth authenticated link checks skipped (no user token)"
fi

note "Checking lobby -> world entry route readiness..."
s=$(request "worlds-list" GET "$API_URL/worlds")
if [[ "$s" == "200" ]]; then
  mark_pass "GET /worlds"
else
  mark_fail "GET /worlds unexpected status $s"
fi

if [[ -n "$TOKEN" ]]; then
  world_id=$(node -e '
    const fs=require("fs");
    try {
      const raw=JSON.parse(fs.readFileSync(process.argv[1],"utf8"));
      const arr=Array.isArray(raw)?raw:raw?.data;
      const id=Array.isArray(arr)&&arr.length?arr[0]?.id:undefined;
      if (id!==undefined) process.stdout.write(String(id));
    } catch {}
  ' "$HTTP_DIR/worlds-list.body.json")

  if [[ -n "${world_id:-}" ]]; then
    s=$(request "world-me" GET "$API_URL/worlds/$world_id/generals/me" "" "$TOKEN")
    if [[ "$s" == "200" || "$s" == "400" || "$s" == "404" ]]; then
      mark_pass "GET /worlds/$world_id/generals/me route reachable (status $s)"
    else
      mark_fail "GET /worlds/$world_id/generals/me unexpected status $s"
    fi
  else
    mark_incomplete "No world id found in /worlds response"
  fi
else
  mark_incomplete "World authenticated checks skipped (no user token)"
fi

note "Checking admin critical read-only flows..."
if [[ -z "$ADMIN_TOKEN" ]]; then
  if [[ $ALLOW_MISSING_ADMIN -eq 1 ]]; then
    mark_skip "admin checks skipped (PARITY_ADMIN_TOKEN not provided)"
  else
    mark_incomplete "admin checks not run (set PARITY_ADMIN_TOKEN or --admin-token)"
  fi
else
  for ep in /admin/dashboard /admin/worlds /admin/users /admin/system-flags; do
    safe_name="admin$(echo "$ep" | tr '/' '_')"
    s=$(request "$safe_name" GET "$API_URL$ep" "" "$ADMIN_TOKEN")
    if [[ "$s" == "200" ]]; then
      mark_pass "GET $ep"
    else
      mark_fail "GET $ep expected 200, got $s"
    fi
  done
fi

summary_file="$ARTIFACT_DIR/summary.md"
{
  echo "# Parity Gate Check Summary"
  echo
  echo "- Timestamp (UTC): $STAMP"
  echo "- Base URL: $BASE_URL"
  echo "- API URL: $API_URL"
  echo "- Artifacts: $ARTIFACT_DIR"
  echo
  echo "## Results"
  echo
  echo "| Status | Check |"
  echo "|---|---|"
  for line in "${RESULT_LINES[@]}"; do
    status="${line%%|*}"
    check="${line#*| }"
    echo "| ${status} | ${check} |"
  done
  echo
  echo "## Totals"
  echo
  echo "- PASS: $PASS"
  echo "- FAIL: $FAIL"
  echo "- SKIP: $SKIP"
  echo "- INCOMPLETE: $INCOMPLETE"
} > "$summary_file"

note "Summary written: $summary_file"

if [[ $FAIL -gt 0 ]]; then
  note "FINAL: FAIL"
  exit 1
fi

if [[ $INCOMPLETE -gt 0 ]]; then
  note "FINAL: INCOMPLETE"
  exit 2
fi

note "FINAL: PASS"
exit 0
