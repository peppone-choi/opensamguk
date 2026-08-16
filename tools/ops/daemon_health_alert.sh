#!/usr/bin/env bash

set -euo pipefail

readonly STALE_TICK_MULTIPLIER=3

invalid_input() {
  printf 'ERROR: invalid daemon alert input: %s\n' "$1" >&2
  exit 2
}

webhook_configured() {
  [[ -n "${DAEMON_ALERT_WEBHOOK_URL:-}" ]]
}

# Visible in the Actions log (annotation) and in the job summary, so a missing
# webhook cannot silently swallow alerts.
warn_undelivered() {
  printf '::warning title=Daemon alert webhook missing::%s\n' "$1"
  if [[ -n "${GITHUB_STEP_SUMMARY:-}" ]]; then
    printf -- '- :warning: %s\n' "$1" >> "$GITHUB_STEP_SUMMARY"
  fi
}

# A broken delivery channel must never be silent, but it must also never be the
# only reason a run is red — the judgement exit code is decided by the daemon
# state alone.
warn_dispatch_failed() {
  printf '::warning title=Daemon alert dispatch failed::%s\n' "$1"
  if [[ -n "${GITHUB_STEP_SUMMARY:-}" ]]; then
    printf -- '- :warning: %s\n' "$1" >> "$GITHUB_STEP_SUMMARY"
  fi
}

parse_status() {
  python3 -c '
import json
import re
import sys

try:
    value = json.load(sys.stdin)
except (json.JSONDecodeError, TypeError, ValueError):
    raise SystemExit(1)

if not isinstance(value, dict):
    raise SystemExit(1)

paused = value.get("paused")
recovery_ready = value.get("recoveryReady")
if type(paused) is not bool or type(recovery_ready) is not bool:
    raise SystemExit(1)

mode = value.get("recoveryMode")
if mode not in {"READY", "FLUSH_RETRY", "RELOAD_REQUIRED"}:
    mode = "UNKNOWN"

clock = value.get("clock")
tick_seconds = clock.get("tickSeconds") if isinstance(clock, dict) else 0
if type(tick_seconds) is not int or not 0 < tick_seconds <= 2_147_483_647:
    tick_seconds = 0

age_seconds = value.get("lastSuccessfulTickAgeSeconds")
if type(age_seconds) is not int or not 0 <= age_seconds <= 2_147_483_647:
    age_seconds = 0

last_turn_time = clock.get("lastTurnTime") if isinstance(clock, dict) else None
if not isinstance(last_turn_time, str) or not re.fullmatch(r"[-0-9T:+.Z]{1,64}", last_turn_time):
    last_turn_time = "unavailable"

print("|".join((str(paused).lower(), str(recovery_ready).lower(), mode, str(tick_seconds), str(age_seconds), last_turn_time)))
'
}

parse_health() {
  python3 -c '
import json
import sys

try:
    value = json.load(sys.stdin)
except (json.JSONDecodeError, TypeError, ValueError):
    raise SystemExit(1)

status = value.get("status") if isinstance(value, dict) else None
if status not in {"UP", "DOWN", "OUT_OF_SERVICE"}:
    raise SystemExit(1)
print(status)
'
}

dispatch_alert() {
  local server="$1"
  local state="$2"
  local reason="$3"
  local recovery_mode="$4"
  local tick_seconds="$5"
  local allowed_seconds="$6"
  local last_turn_time="$7"
  local payload

  if ! webhook_configured; then
    warn_undelivered "alert webhook is not configured; alert NOT delivered server=${server} state=${state} reason=${reason}"
    printf 'daemon alert undelivered server=%s state=%s reason=%s tickSeconds=%s allowedSeconds=%s lastTurnTime=%s\n' \
      "$server" "$state" "$reason" "$tick_seconds" "$allowed_seconds" "$last_turn_time"
    return 0
  fi

  # Discord rejects a bare custom object with 400 (50006 "Cannot send an empty
  # message"): Execute Webhook requires at least one of content/embeds/
  # components/file/poll. The structural diagnostics are unchanged — they are
  # rendered from one dict into the embed fields, so nothing is lost.
  payload="$(
    python3 - "$server" "$state" "$reason" "$recovery_mode" "$tick_seconds" "$allowed_seconds" "$last_turn_time" <<'PY'
import json
import sys

server, state, reason, recovery_mode, tick_seconds, allowed_seconds, last_turn_time = sys.argv[1:]
diagnostics = {
    "source": "opensamguk-daemon-health",
    "server": server,
    "state": state,
    "reason": reason,
    "recoveryMode": recovery_mode,
    "tickSeconds": int(tick_seconds),
    "allowedSeconds": int(allowed_seconds),
    "lastTurnTime": last_turn_time,
}
color = {"DOWN": 0xE74C3C, "OUT_OF_SERVICE": 0xF1C40F}.get(state, 0x2ECC71)
print(json.dumps({
    "username": diagnostics["source"],
    "embeds": [{
        "title": f"[{state}] {server} / {reason}",
        "color": color,
        "fields": [
            {"name": key, "value": str(value), "inline": key != "lastTurnTime"}
            for key, value in diagnostics.items()
        ],
    }],
}, separators=(",", ":")))
PY
  )"

  # --retry covers Discord's 429 and transient 5xx (curl honours Retry-After);
  # a real outage still fails, and a failed dispatch never decides the exit code.
  if ! curl --fail --silent --show-error --max-time 10 \
    --retry 3 --retry-delay 2 --retry-max-time 40 \
    -H 'Content-Type: application/json' \
    --data-binary "$payload" \
    "$DAEMON_ALERT_WEBHOOK_URL" >/dev/null 2>&1; then
    warn_dispatch_failed "alert dispatch failed; alert NOT delivered server=${server} state=${state} reason=${reason}"
    printf 'ERROR: alert dispatch failed for server=%s state=%s reason=%s\n' "$server" "$state" "$reason" >&2
    return 1
  fi
  printf 'daemon alert dispatched server=%s state=%s reason=%s tickSeconds=%s allowedSeconds=%s lastTurnTime=%s\n' \
    "$server" "$state" "$reason" "$tick_seconds" "$allowed_seconds" "$last_turn_time"
}

if (( $# != 2 )); then
  invalid_input 'expected server and game-engine container'
fi

server="$1"
engine_container="$2"
[[ "$server" =~ ^s[a-z0-9]{1,48}$ ]] || invalid_input 'server must be a canonical internal game-server identifier'
[[ "$engine_container" == "${server}-game-engine" ]] || invalid_input 'container must match the bounded server identifier'

status_json="$(docker exec "$engine_container" curl --silent --show-error --max-time 5 \
  http://localhost:8082/admin/turn-daemon/status 2>/dev/null)" || status_json=''
if ! summary="$(printf '%s' "$status_json" | parse_status 2>/dev/null)"; then
  dispatch_alert "$server" DOWN status_unreadable UNKNOWN 0 0 unavailable || exit 1
  exit 1
fi

IFS='|' read -r paused recovery_ready recovery_mode tick_seconds age_seconds last_turn_time <<< "$summary"
allowed_seconds=$((tick_seconds * STALE_TICK_MULTIPLIER))
health_json="$(docker exec "$engine_container" curl --silent --show-error --max-time 5 \
  http://localhost:8082/actuator/health 2>/dev/null)" || health_json=''
if ! health_status="$(printf '%s' "$health_json" | parse_health 2>/dev/null)"; then
  dispatch_alert "$server" DOWN health_unreadable "$recovery_mode" "$tick_seconds" "$allowed_seconds" "$last_turn_time" || exit 1
  exit 1
fi

if [[ "$recovery_ready" != true ]]; then
  state=DOWN
  reason=recovery_gated
elif [[ "$health_status" == DOWN && "$tick_seconds" -gt 0 && "$age_seconds" -gt "$allowed_seconds" ]]; then
  state=DOWN
  reason=turn_stalled
elif [[ "$health_status" == DOWN ]]; then
  state=DOWN
  reason=health_down
elif [[ "$paused" == true ]]; then
  state=OUT_OF_SERVICE
  reason=paused
elif [[ "$health_status" == UP ]]; then
  webhook_configured ||
    warn_undelivered "alert webhook is not configured; health checks still run but alerts will not be delivered server=${server}"
  printf 'daemon health server=%s state=UP reason=running tickSeconds=%s allowedSeconds=%s lastTurnTime=%s\n' \
    "$server" "$tick_seconds" "$allowed_seconds" "$last_turn_time"
  exit 0
else
  state=OUT_OF_SERVICE
  reason=health_out_of_service
fi

dispatch_alert "$server" "$state" "$reason" "$recovery_mode" "$tick_seconds" "$allowed_seconds" "$last_turn_time" || exit 1
exit 1
