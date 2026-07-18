# OPENSAM-90 gateway portrait GOLDENSET

- Status: `frozen`
- Approval: `docs/superpowers/plans/2026-07-17-opensam-90-91-102-109-113-execution-contract.md:96-149` (`A0 + A1 bounded lane`)
- Scope: `web/gateway` account/lobby portrait resolution only
- Grader: dedicated Vitest cases listed below, followed by gateway typecheck, full test, build, and browser observation

## Frozen behavior

1. Shared bare code `1001` resolves to `${IMAGE_CDN_BASE}/icons/1001.jpg`.
2. Supported canonical extensions (`jpg`, `jpeg`, `png`, `gif`, `webp`, case-insensitive) are preserved instead of receiving a second `.jpg` suffix.
3. Missing/blank `picture` resolves to `${IMAGE_CDN_BASE}/icons/default.jpg`.
4. `imageServer=1` resolves to the same default until OPENSAM-93 provides a serving path.
5. A failed non-default image changes to the default exactly once; an error on the default performs no second write.
6. Account and lobby consume the same gateway helper and do not retain page-local `d_shared`/`d_pic` builders.
7. Account and lobby both render the default for missing portrait data rather than hiding/breaking the image.

## Dedicated score

The deterministic loop score is the number of passing OPENSAM-90 regression cases out of seven:

1. account path-state contract
2. account guarded error fallback
3. lobby bare-code path
4. lobby canonical-extension path
5. lobby missing-picture default
6. lobby `imageServer=1` default
7. lobby failed-load fallback

The test expectations and this scoring contract may not be weakened during the loop.

## Legacy evidence

- `legacy/devsam-core/hwe/ts/util/getIconPath.ts:1-8`: shared versus uploaded legacy roots.
- `legacy/devsam-core/hwe/j_server_basic_info.php:119-127`: lobby response chooses the shared or uploaded web path from `imgsvr`.
- `legacy/devsam-core/hwe/ts/gateway/entrance.ts:62-69,260-265`: lobby renders the server-provided portrait in the owned-character row.
- `legacy/devsam-core/hwe/ts/gateway/user_info.ts:29-40,336-345`: account renders the returned portrait and seeds both icon slots with `sharedIcon/default.jpg`.
- `web/game/lib/portrait.ts:15-40`: established opensamguk mapping from the legacy roots to `icons/`, extension handling, OPENSAM-93 fence, and guarded default fallback.

## Browser evidence contract

For one run ID, observe account and lobby DOM `src` plus image request URL/status for normal, missing, `imageServer=1`, and failed-load cases. If authentication or fixture control prevents a real route observation, record `채점대기` with the exact boundary and keep the closest rendered Vitest evidence separate.
