# Prod Game API Proxy Routing Review — 2026-06-20

Verdict: cleared

## Scope

Restore the production same-origin `/api/game/*` contract so browser requests pass through a Next route handler before reaching game-api.

## Runtime Evidence

- Login through `/api/auth/login` succeeded and `/api/auth/me` returned the user.
- The same cookie jar against production `/api/game/api/front-info` returned a public/anonymous identity (`hasGeneral=false`, `generalId=null`) instead of the logged-in s1 general.
- Production `/game/s1` pages call root-origin `/api/game/*`, while `infra/nginx/nginx.conf` routed that prefix directly to game-api and stripped `/api/game/`.
- `web/gateway/app/api/game/[...path]/route.ts` is the complete proxy owner for browser traffic: it reads `sam_access`, attaches `Authorization: Bearer`, accepts `?server=`, and resolves server registry entries.
- After PR #123 deployed, `/api/game/*` carried Next response headers and selected s1 correctly, but the existing s1 env still rejected gateway JWTs because its `JWT_SECRET` had been copied with the surrounding quotes from shared `.env`.
- EC2 repair copied the quote-stripped shared gateway container JWT into `servers/s1.env`, then force-recreated only s1 `game-api` and `web-game` with the existing `IMAGE_TAG=5961295038cec09468afec2cc35c3091deb32999`.

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
- H4 confirmed after deploy: server-specific env values must strip shell quotes when copied into `servers/<id>.env`; otherwise the gateway and server containers hold different effective `JWT_SECRET` values even when the file visually appears synchronized.

## Verification

- `git diff --check`
- `tools/agent-system/check.py --strict --base origin/main --format json`
- PR #123 CI: agent-system, jvm, web-gateway, and web-game all passed.
- Main deploy run `27852568952`: Build + Deploy to EC2 completed successfully; deploy step preserved game server version pins and verified health + s1 turn-advance.
- Production API QA: login 200, `claimable` 200, join 202, `front-info` returned `hasGeneral=true/generalId=1679`, and `chief-reserved` returned HTTP 200 with six command categories and no `연구`.
- Production browser QA: `/game/s1` rendered `.ib-map=700×520`, `.map-viewer-title=700×20`, `.map-viewer-canvas=700×500`, `a.city-base=94`; first city click committed `https://sam.peppone.dev/game/s1/city?id=1`.
- `nginx -t` via `docker run nginx:1.27-alpine` could not run locally because the Docker socket was unavailable.
