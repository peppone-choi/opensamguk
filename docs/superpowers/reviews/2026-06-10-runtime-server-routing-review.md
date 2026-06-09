# Runtime Server Routing Review

## Scope

- `web/gateway` lobby server rows and `ServerBoard` now come from `SERVER_REGISTRY_JSON` at request time through `/api/servers`.
- `infra/nginx/default.conf` routes `/game/s<id>` to `<id>-web-game:3001` and rewrites the request to `/game/...?...server=<id>`.
- `web/game/middleware.ts` accepts the same server id character set as the deployer registry.

## Source Of Truth

- The live admin/deployer registry is the source of truth for created servers in production.
- `web/gateway/config/servers.json` remains a development fallback only.
- Per-row game status still comes from live `GET /api/server-basic-info/<id>` fan-out, preserving the existing entrance-state behavior instead of baking turn state in the frontend.

## Parity Evidence

- Legacy entrance behavior is preserved at the state-machine level: lobby rows continue to decide between enter, registration, registration-closed, and closed from live server basic info.
- The routing change does not alter game logic, RNG, logs, database writes, or command execution paths.
- `/game/s<id>` injects the existing `?server=<id>` selector expected by `web/game` middleware, so downstream `/api/game/*` resolution continues through the existing `sam_server` cookie path.

## Critical Review

Verdict: cleared

- Risk: `/api/servers` must be routed to `gateway-frontend`, otherwise nginx `/api/` catch-all sends it to game-api. Mitigation: both HTTP and HTTPS server blocks include exact `location = /api/servers`.
- Risk: `/game/_next/*` could be interpreted as a server id by the regex location. Mitigation: asset location is now `^~ /game/_next/`.
- Risk: unknown server ids can resolve to missing Docker DNS names. This matches existing closed-server behavior: nginx returns upstream failure, while lobby only renders registry entries.
- Risk: non-`s<id>` registry entries can produce URLs that nginx does not dynamically route. Mitigation: generated game URLs use `/game/<id>` only for `s<id>` ids and otherwise use the existing `/game?server=<id>` selector.

## Verification

- `/usr/local/bin/pnpm --dir web/gateway typecheck`
- `/usr/local/bin/pnpm --dir web/game typecheck`
- `git diff --check`
