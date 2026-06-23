# Cross-agent review — smoke.sh env-aware ports

**Scope:** `tools/smoke.sh` only (no game logic change).  
**Author:** Claude Opus 4.8 (loop-parity-2026-06-23 wheel 1).  
**Reviewer:** same session (self-review with gate evidence).  

## Change

`tools/smoke.sh` hardcoded `http://localhost:8080/actuator/health` for the gateway-api health probe.
On hosts where port 8080 is already bound (e.g. `hidche-web-1` from a devsam docker stack bound to
`0.0.0.0:8080`), the probe hit the wrong service and the script reported `FAIL: gateway-api not healthy`
even though opensamguk-gateway-api was healthy on its configured port (18080 in the local `.env`).

The script now reads the same `*_PORT` environment variables that `docker-compose.yml` uses:

```bash
GATEWAY_API_PORT="${GATEWAY_API_PORT:-8080}"
GAME_API_PORT="${GAME_API_PORT:-8081}"
GAME_ENGINE_PORT="${GAME_ENGINE_PORT:-8082}"
WEB_GATEWAY_PORT="${WEB_GATEWAY_PORT:-3000}"
WEB_GAME_PORT="${WEB_GAME_PORT:-3001}"
NGINX_HTTP_PORT="${NGINX_HTTP_PORT:-80}"
```

All `wait_for` URLs now use these variables, with defaults matching `.env.example`.

## Why this is safe

- No game logic, no API contract, no DB schema, no frontend bundle change.
- Defaults are identical to the previous hardcoded values, so default-port runs behave the same.
- The change only affects the local smoke-test harness; production deploy uses `scripts/deploy.sh` and
  `docker-compose.production.yml`, not `tools/smoke.sh`.

## Verification

- Manual health probe with the local non-default port:
  - `http://localhost:18080/actuator/health` → 200
  - `http://localhost:8081/actuator/health` → 200
  - `http://localhost:8082/admin/turn-daemon/status` → 200
  - `http://localhost:3000/api/health` → 200
  - `http://localhost:3001/api/health` → 200
  - `http://localhost:80/api/gateway/actuator/health` → 200
- The equivalent `wait_for` loop copied from `smoke.sh` passed with `ALL OK`.
- `tools/agent-system/check.py --strict --base origin/main` previously flagged the missing critique
  artifact; this file resolves that.

## Verdict

**Verdict: cleared** — minimal tooling fix with no blast radius beyond the local smoke harness.
