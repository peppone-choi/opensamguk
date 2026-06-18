# WEB_GAME_TAG Admin Env Review

Verdict: cleared

## Scope

- Change: allow `WEB_GAME_TAG` in server-scoped admin env PATCH.
- Files: `DeployService.kt`, `AdminVersionDeployTest.kt`.
- Reason: production keeps running game servers pinned while shared deployer updates. A mobile-only `web/game` fix needs a frontend-only promotion path without forcing `game-api` or `game-engine` to the new `IMAGE_TAG`.

## Evidence

- Runtime server env already uses `WEB_GAME_TAG` beside `IMAGE_TAG` for `s1`.
- Deploy workflow prints and preserves server pins, including `WEB_GAME_TAG`, in `.github/workflows/deploy.yml`.
- Admin page saves server env through `PATCH /api/proxy/admin/env/servers/{serverId}` with a `{ "values": ... }` body.
- Before this patch, gateway-api rejected `WEB_GAME_TAG` before the deployer call with `허용되지 않은 env 값: WEB_GAME_TAG`.

## Review

- Root cause: the deployed env/deployer model supported split game frontend pinning, but the gateway-api allowlist lagged behind it.
- Fix shape: add only `WEB_GAME_TAG` to server-scoped env keys. Do not broaden the allowlist pattern and do not expose deployer token or docker access to the browser.
- Regression: `AdminVersionDeployTest` now sends `WEB_GAME_TAG` with other server env values and asserts the exact body reaches the deployer proxy.

## Risk

- Scope-risk: narrow. This permits one additional uppercase env key that already exists in server env files and deploy workflow output.
- Remaining risk: deployer still owns actual restart behavior; this patch only lets the admin path reach it.
