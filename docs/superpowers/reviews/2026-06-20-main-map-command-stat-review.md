# 2026-06-20 main map command stat review

## Loop scope

- opensamguk-php-oracle: `legacy/devsam-core/hwe/ts/components/SammoBar.vue:1-59`, `legacy/devsam-core/hwe/ts/components/MapCityDetail.vue:16-47`, `legacy/devsam-core/hwe/scss/map.scss:231-270`, `legacy/devsam-core/hwe/ts/PageFront.vue:34-70,613-655`.
- webapp-testing baseline: production `/game/s1` still served the old map DOM: board 1000px, map 700px, `.city-img.my-city=1`, `.city-filler.my-city=0`, reserved command actions included the extra `명령` button.
- systematic-debugging root causes:
  - Current-city outline was attached to the image box instead of the legacy `city_filler` layer, so hover/focus could expose an anchor-sized square.
  - Reserved-command read trusted principal inference only; the main page already knows `front-info.general.generalId`, so the request should carry it as fallback.
  - General stat display lacked the real `*_exp` meta bars and signed onCalcStat display deltas, leaving 통/무/지/정/매 visually flatter than legacy.
  - Gauge-like rows reused local CSS bars instead of the legacy SammoBar image asset contract.
- loop-engineering hypothesis: move current-city outline to `city-filler`, pass `generalId` for reserved command reads, expose stat exp/bonus fields from game-api, and consolidate gauge rendering through a React `SammoBar`.

## Local verification

- `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :app:game-api:test --tests opensamguk.gameapi.controller.FrontInfoControllerTest`
- `pnpm test -- GeneralBasicCard.test.tsx MapViewer.props.test.tsx api-intake.test.ts` in `web/game` -> 18 files, 88 tests passed.
- `pnpm typecheck` in `web/game`.
- `pnpm typecheck` in `web/gateway`.
- `pnpm build` in `web/game`.
- `pnpm build` in `web/gateway`; existing `app/admin/page.tsx` hook dependency warning remains.
- `git diff --check`.

## Expected production checks

- `/game/s1` desktop map grows with the 1200px main board, current-city marker is `.city-filler.my-city`, and `.city-img.my-city` is absent.
- Reserved-command panel no longer shows a separate `명령` button; slot editing stays on the per-row pencil control.
- `/api/game/api/reserved-commands?generalId=<front-info.general.generalId>` returns slots.
- A harmless `휴식` command reservation request returns `202 AVAILABLE`.
- General card shows signed stat deltas and SammoBar-backed stat/level bars.
