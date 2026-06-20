# 2026-06-20 main map title tooltip review

Verdict: cleared

## Skill Chain

- `opensamguk-php-oracle`: legacy `hwe/ts/components/MapViewer.vue` title tooltip source lines were used as the oracle.
- `webapp-testing`: live prod `/game/s1` was measured before the fix.
- `systematic-debugging`: root cause was isolated to the missing const-threaded title tooltip path in `MapViewer`.
- `loop-engineering`: baseline -> one hypothesis -> local gates -> adoption wait.

## Legacy Oracle

- `legacy/devsam-core/hwe/ts/components/MapViewer.vue:15-18` attaches `titleTooltip` to `.map_title`.
- `legacy/devsam-core/hwe/ts/components/MapViewer.vue:273-279` computes the opening-limit tooltip text.
- `legacy/devsam-core/hwe/ts/components/MapViewer.vue:282-303` adds the current technology-level limit text from `gameConst.maxTechLevel`, `initialAllowedTechLevel`, and `techLevelIncYear`.

## Baseline

Live prod before fix at `https://sam.peppone.dev/game/s1`:

- `.map-viewer-title` rendered `184年 11月`.
- `.map-viewer-canvas` was `700x500`.
- `.map-viewer-title` box was `700x20`.
- `.map-viewer-title[title]` was absent.
- `.map-viewer-title[aria-label]` was absent.

## Root Cause

`MapViewer.tsx` had already restored the title row, color rule, live map data, city anchors, and map box size, but only ported `getTitleColor`. The legacy title tooltip depends on static game constants. The current `/api/const` response already exposes those values under `gameConst`, but the TypeScript response type did not declare them and `GameChrome` never passed that bundle into `MapViewer`.

## Fix

- Extend `GameConstResponse` with the `gameConst` fields needed by the map title tooltip.
- Add `mapTitleTooltip()` to `MapViewer.tsx`, preserving the legacy opening-limit and technology-limit formulas.
- Pass `constData.gameConst` from `GameChrome` into the main `MapViewer`.
- Render the computed text on `.map-viewer-title` as `title` and an accessibility label.

## Verification

- `pnpm --dir web/game test -- MapViewer.props.test.tsx`: 17 files / 86 tests passed.
- `pnpm --dir web/game typecheck`: passed.
- `pnpm --dir web/game build`: passed; only pre-existing warnings in `generals`, `tournament`, and `GeneralBasicCard`.
- `git diff --check`: passed.

## Adoption Status

Pending production merge/deploy/promotion and live Playwright remeasure for:

- `.map-viewer-title[title]` contains `초반제한 기간`.
- `.map-viewer-title[title]` contains `기술등급 제한`.
- Map box invariants remain `title 700x20`, `canvas 700x500`, city anchors 94, and first city click commits to `/game/s1/city?id=1`.
