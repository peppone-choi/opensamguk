# 2026-06-09 GameInfo Parity Critique

## Scope

- `app/game-api/src/main/kotlin/opensamguk/gameapi/dto/IdentityDto.kt`
- `app/game-api/src/main/kotlin/opensamguk/gameapi/controller/FrontInfoController.kt`
- `app/game-api/src/test/kotlin/opensamguk/gameapi/controller/FrontInfoControllerTest.kt`
- `web/game/components/game/GameInfo.tsx`
- `web/game/lib/types.ts`
- `web/game/__tests__/GameInfo.test.tsx`
- `docs/superpowers/gap/HARDCODE_INVENTORY.md`

## Verdict: cleared

The GameInfo header no longer fabricates `기타 설정: 자동`, and tournament duration no longer bypasses the legacy clamp. The backend front-info contract now preserves the legacy `autorunUser` object shape (`limit_minutes`, `options`) instead of collapsing it to a boolean, so the frontend can render the same visible condition as `AutorunInfo.vue`: show `자율행동` only when `limit_minutes > 0`, otherwise render nothing after the label.

## Adversarial Checks

- **Tournament clamp parity:** Legacy `hwe/ts/utilGame/tournament.ts` clamps to `5..120`; `GameInfo.tsx` now imports the already-ported `calcTournamentTerm` helper rather than maintaining a private passthrough.
- **No baked other-setting text:** The old literal `기타 설정: 자동` was removed and is locked by `GameInfo.test.tsx`.
- **Backend contract shape:** Legacy `GetFrontInfoResponse.global.autorunUser` is an object. `FrontInfoController` now accepts only a config object with `limit_minutes`; absent or malformed values remain `null` rather than being invented.
- **No tooltip overreach:** The React header renders the visible legacy text only. The full Vue tooltip detail is not added because current design has no tooltip component in this compact header and no user-visible parity gap requires inventing one.

## Residual Risk

`world_state.config["autorun_user"]` is still absent in the empty-server production state. That is correct for the current admin-created-server flow: no value means no `자율행동` marker, not a fabricated default.
