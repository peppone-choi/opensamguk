# Prod Game API Proxy Routing Review — 2026-06-20

Verdict: cleared

## Scope

Restore the production same-origin `/api/game/*` contract so browser requests pass through a Next route handler before reaching game-api.

## Runtime Evidence

- Login through `/api/auth/login` succeeded and `/api/auth/me` returned the user.
- The same cookie jar against production `/api/game/api/front-info` returned a public/anonymous identity (`hasGeneral=false`, `generalId=null`) instead of the logged-in s1 general.
- Production `/game/s1` pages call root-origin `/api/game/*`, while `infra/nginx/nginx.conf` routed that prefix directly to game-api and stripped `/api/game/`.
- `web/gateway/app/api/game/[...path]/route.ts` is the complete proxy owner for browser traffic: it reads `sam_access`, attaches `Authorization: Bearer`, accepts `?server=`, and resolves server registry entries.

## Root Cause

`infra/nginx/nginx.conf` diverged from the frontend contract. The client code assumes `/api/game/*` is a same-origin Next proxy, but production nginx sent it straight to game-api. That bypassed httpOnly-cookie auth bridging and server selection, which made admin game settings and in-game identity-bound pages behave as unauthenticated or wrong-server calls.

## Change

- Route production `/api/game/*` to `web-gateway` without stripping the path.
- Keep buffering disabled on that location so `/api/game/sse/turn` remains stream-friendly through the Next proxy.
- Provide `GAME_API_ORIGIN` to the production `web-gateway` service as the default proxy fallback.
- Update `.env.example` and `README.md` so the documented routing matches the deployed architecture.

## Debugging Audit

- H1 confirmed: direct nginx-to-game-api routing bypassed the cookie-to-Bearer bridge; production `front-info` with valid gateway cookies still resolved as anonymous.
- H2 confirmed: routing `/api/game/*` to `web-game` would fix some in-game GET/POST calls, but would not cover admin `PATCH` or `?server=` settings calls because the web-game proxy only exports GET/POST and reads server selection from `sam_server`.
- H3 refuted: this is not a JWT issuance bug in gateway auth; the gateway session itself was valid, and the break was between browser cookies and game-api authorization.

## Verification

- `git diff --check`
- `tools/agent-system/check.py --strict --base origin/main --format json` is required after adding this review artifact.
- `nginx -t` via `docker run nginx:1.27-alpine` could not run locally because the Docker socket was unavailable.

Deploy verification remains required: after merge and shared-stack deploy, login on production, set/enter `s1`, then confirm `/api/game/api/front-info` resolves the logged-in s1 general and `/api/game/api/nation/chief-reserved` returns six chief command categories without `연구`.
