# 2026-06-19 join registered general route review

Verdict: cleared

## Scope

- Bug: a user who already owns a created general must enter the game from the character-registration surface instead of seeing registration/lobby again.
- User-facing surfaces: `/game/{server}/join`, `/game/{server}`, and gateway lobby server row.

## Legacy Evidence

- `legacy/devsam-core/hwe/ts/PageJoin.vue:420-428` submits `SammoAPI.General.Join`, alerts success, then navigates to `./` (the game root), not back to the lobby.
- `legacy/devsam-core/hwe/j_basic_info.php:14-29` resolves the active in-game general from the logged-in user's `general.owner`.
- `legacy/devsam-core/hwe/j_server_basic_info.php:119-126` fills the lobby character cell from the same owner-backed general row.

## Root Cause

The previous `web/game` join page waited for `front-info` after a successful create and pushed to the game root, but it did not guard the registration URL itself. If a registered user reached `/game/{server}/join` again, the form could still render during the transition instead of treating the owned general as the entry target.

## Patch

- `web/game/app/game/join/page.tsx` now derives the game root from either the selected-server cookie or `frontInfo.global.serverId`.
- Once `front-info` says `general.hasGeneral=true`, the join page `replace`s to that server's game root.
- After successful create, the page still pushes to the game root and now also calls `router.refresh()` so the newly created general's game screen re-reads fresh `front-info`.

## Verification

- `pnpm --dir web/game test -- join-route.test.tsx`: 10 files / 66 tests passed, including the new join route guard test.
- `pnpm --dir web/game typecheck`: passed.
- Production browser baseline before this patch already confirmed the earlier join ownership fix: new account `codex39011139` created general `코덱스011139`, landed on `https://sam.peppone.dev/game/s1`, saw the game main screen, and the lobby row showed `코덱스011139` with an `입장` link to `/game/s1`.

## Docs Drift

No README/AGENTS/CLAUDE behavior change is required: this is a route-guard reinforcement of the existing join-entry contract already documented in `docs/superpowers/reviews/2026-06-18-join-created-general-entry-review.md`.
