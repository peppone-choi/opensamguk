# 2026-06-18 admin server scope review

Verdict: cleared

## Scope

- Fix admin "게임 환경" so the selected game server scopes lock status and entry settings.
- Keep `msg`/운영자 메세지 as a global operator message by writing the changed value to every registered game server.
- Fix server env updates so server-visible values are reflected in the deployer registry, not only in `servers/<id>.env`.

## Legacy Evidence

- `legacy/devsam-core/hwe/_admin1.php:33-59` renders one server's admin form for 운영자메세지, 시작시간, 최대 장수, 최대 국가, 시작 년도, and 턴시간.
- `legacy/devsam-core/hwe/_admin1_submit.php:25-40` writes 운영자메세지 to that server's `game_env` storage.
- `legacy/devsam-core/hwe/_admin1_submit.php:45-77` writes the other entry/server settings to the same server's `game_env` and delegates turn-term changes through `ServerTool::changeServerTerm`.
- `legacy/devsam-core/hwe/func.php:1705-1710` shows `getAdmin()` reads `KVStorage::getStorage($db, 'game_env')->getAll()`, i.e. the currently selected game DB.

opensamguk now has multiple independent game stacks behind one gateway. Therefore admin entry settings must target the selected server's game-api. The only intentional global exception in this change is 운영자메세지: the UI writes `msg` to every registered server to keep one operator notice across the fleet.

## Root Cause

- `web/gateway/app/admin/page.tsx` previously used a fixed `/api/game/api/admin/game-settings` path from `GameSettingsControl`, so the selected server in the "시간 · 봉급 · 환경 설정" area did not scope "입장 설정".
- `web/gateway/app/api/game/[...path]/route.ts` preferred the `sam_server` cookie over the `?server=` query, so even a caller that passed an explicit server could be routed to a stale cookie server.
- `opensamguk-docker-env-admin/deployer/main.go` patched `servers/<id>.env` but did not update `SERVER_REGISTRY_JSON`, so lobby/admin/game surfaces could keep the old server name and generation until another lifecycle action rebuilt the registry.

## Hypothesis

One selected-server state should drive all admin game-environment operations. The `/api/game` proxy should let explicit `?server=` override a stale cookie. Every non-secret server env value should be mirrored into the registry snapshot, while `JWT_SECRET` remains write-only and excluded from registry JSON.

## Verification

- `web/gateway`: `node_modules/.bin/tsc --noEmit` passed.
- `opensamguk-docker-env-admin`: `docker run --rm -v "$PWD":/src -w /src/deployer golang:1.24-alpine go test ./...` passed.

## Remaining Manual QA Gate

- Deploy both repos.
- In production admin, open "게임 환경" and verify the server selector controls lock status, "입장 설정", and server env.
- Temporarily change `SERVER_NAME`, verify `/api/servers` and admin version list update through the registry, then restore the original name.
