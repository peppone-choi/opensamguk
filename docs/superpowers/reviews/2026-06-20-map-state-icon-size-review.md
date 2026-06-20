# 2026-06-20 map state icon size review

Verdict: cleared

## Skill Chain

- `opensamguk-php-oracle`: legacy `hwe/ts/components/MapCityDetail.vue` and `hwe/scss/map.scss` state-icon rendering were used as the oracle.
- `webapp-testing`: production `/game/s1/map` was remeasured after promotion; current world data has no `state > 0` cities, so live state-icon bounding boxes were not observable.
- `systematic-debugging`: root cause was isolated to the state icon missing the existing map icon scale path.
- `loop-engineering`: baseline evidence -> one hypothesis -> local gates -> production adoption wait.

## Legacy Oracle

- `legacy/devsam-core/hwe/ts/components/MapCityDetail.vue:44-45` renders `event<state>.gif` whenever `city.state > 0`.
- `legacy/devsam-core/hwe/scss/map.scss:540-549` shows the responsive map state icon uses a constrained `10px` image, while the detail map keeps the same absolute slot.
- `legacy/devsam-image/game/event6.gif` is `15x15`; `legacy/devsam-image/game/cast_3.gif` is `14x14`.

## Baseline

Current source before this loop scaled city cast icons with `ICON_SCALE=0.72`, but rendered `.city-state` without `width` or `height`.

- `cast_3.gif`: `14x14` -> `10x10` after `ICON_SCALE`.
- `event6.gif`: natural `15x15` because `.city-state` had no size attributes.
- Result: disaster/state icons looked larger than the city icon they sit on.

## Root Cause

`MapViewer.tsx` and `MapPreview.tsx` already share one visual scale for cast, flag, and capital icons. The state icon path was left out of that scale thread and therefore used the raw asset size. Because `event*.gif` is larger than small city cast assets, the mismatch is visible on the main map.

## Fix

- Add `STATE_PX = Math.round(15 * ICON_SCALE)` beside the existing map icon scale constants.
- Apply `width={STATE_PX}` and `height={STATE_PX}` to `.city-state` in both `web/game` and `web/gateway`.
- Pin `state=6` in `MapViewer.props.test.tsx` to `width=11` and `height=11`.

## Verification

- `/usr/local/bin/pnpm --dir web/game test -- MapViewer.props --run`: 17 files / 86 tests passed.
- `/usr/local/bin/pnpm --dir web/game typecheck`: passed.
- `/usr/local/bin/pnpm --dir web/gateway typecheck`: passed.
- `/usr/local/bin/pnpm --dir web/game build`: passed; only pre-existing warnings in `generals`, `tournament`, and `GeneralBasicCard`.
- `/usr/local/bin/pnpm --dir web/gateway build`: passed; only pre-existing `admin/page.tsx` hook dependency warning.

## Adoption Status

Accepted after production merge/deploy/promotion, with the data-condition caveat above.

- PR #130 merged to main as `c7fafb4a485aec6c1c197d8b7954eb8f6e590e52`.
- Deploy run `27860548398` passed: JVM image build/push, web image build/push, shared stack deploy, pin preservation, health + `s1` turn verification.
- Admin deploy promoted `s1` from `eec1b6c91b49b35f9d1e53cde172040e47cee6bf` to `c7fafb4a485aec6c1c197d8b7954eb8f6e590e52`.
- `/health` returned `status=up`, `nginx=ok`.
- Live `/api/game/api/map?server=s1&neutralView=0&showMe=1` returned 94 cities and `state > 0` count 0.
- Live `/game/s1/map` rendered map title `187年 1月`, city anchors 94, city buttons 0, first href `/game/s1/city?id=1`.
- Because current production data has no state icons, `.city-state` live count was 0. The state icon size itself is pinned by `MapViewer.props.test.tsx` with `state=6` expecting `width=11` and `height=11`.
- First city click committed to `/game/s1/city?id=1` and rendered `도시 정보`.

## 2026-06-20 Follow-Up

Verdict: cleared

User re-evaluation found the 11px state icon still visually too large on the current map. The root cause after the first fix was subtler: `cast_3.gif` is `14x14` and scales to `10x10`, so the 11px state icon remained larger than a low-level city marker.

The follow-up hypothesis changes only the state icon scale:

- Keep city cast, flag, and capital icon sizing unchanged.
- Add `STATE_ICON_SCALE = 0.54`.
- Render `.city-state` as `8x8` in both `web/game` `MapViewer` and `web/gateway` `MapPreview`.
- Update the `state=6` fixture pin from `11x11` to `8x8`.

Verification:

- `/usr/local/bin/pnpm --dir web/game test -- MapViewer.props --run`: 17 files / 86 tests passed.
- `/usr/local/bin/pnpm --dir web/game typecheck`: passed.
- `/usr/local/bin/pnpm --dir web/gateway typecheck`: passed.
