# No-General Join Route Review

## Verdict: cleared

The production failure is a frontend route ownership bug. `/game/s1` should not own character creation when the logged-in user has no general; it should hand off to the current server-scoped join route. The patch is narrow and keeps the game shell behavior unchanged for users who already have a general.

## Evidence

- Production baseline on `https://sam.peppone.dev/game/s1`: `front-info` returned `general.hasGeneral=false` and `serverId=s1`, but the page rendered the retired inline `장수 등록` surface with `장수 생성`, `NPC 빙의`, and `장수 선택`.
- PHP/hwe entrance separates no-general actions from the game main page: `legacy/devsam-core/hwe/ts/gateway/entrance.ts:48-55` links unregistered users to `<serverPath>/v_join.php` and `<serverPath>/select_npc.php`.
- PHP/hwe join page is its own page: `legacy/devsam-core/hwe/v_join.php:56-62` mounts the `v_join` Vue bundle, and `legacy/devsam-core/hwe/ts/PageJoin.vue:2,59,234` owns the `장수 생성` UI.
- Current Next route already has the intended replacement: `web/game/app/game/join/page.tsx` implements the join UX and redirects users who already have a general back home.

## Root Cause

`web/game/components/game/GameChrome.tsx` kept a stale no-general branch that directly rendered `CharacterClaim`. After the server-scoped `/game/{serverId}/join` route was introduced, this branch became a second registration surface on the main game route. That is why `/game/s1` showed a page we no longer use.

## Change

- `GameChrome` now computes the active server id from `front-info.global.serverId` and redirects no-general users to `/game/{serverId}/join`.
- The retired `CharacterClaim` render path was removed from the game shell.
- `GameChrome.main-map.test.tsx` pins the redirect to `/game/s1/join` and asserts that the old `장수 등록` UI is not rendered.
- The loop ledger records the production baseline, local verification, and pending production remeasure.

## Verification

- `git diff --check` passed.
- `pnpm --dir web/game test -- GameChrome.main-map.test.tsx` passed with the full web-game suite: 22 files, 102 tests.
- `pnpm --dir web/game typecheck` passed.
- `pnpm --dir web/game build` passed with only pre-existing warnings.
- GitHub Actions for PR #136 passed `web (game)`, `web (gateway)`, and `jvm` before this review artifact was added.

## Remaining Risk

Production still needs the merged image promoted to `s1`, then `/game/s1` must be remeasured in a no-general session to confirm it lands on `/game/s1/join` instead of the retired inline registration page.
