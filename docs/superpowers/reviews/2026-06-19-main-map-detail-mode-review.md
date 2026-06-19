# 2026-06-19 main map detail mode review

Verdict: cleared

## Scope

- Target: `web/game/components/game/GameChrome.tsx`, `web/game/components/game/MapViewer.tsx`,
  `web/game/__tests__/GameChrome.main-map.test.tsx`, `web/game/__tests__/MapViewer.props.test.tsx`.
- User request: make the main screen cleaner, align its structure, and make the main map the same size and function as the game map.

## Legacy Evidence

- `legacy/devsam-core/hwe/ts/PageFront.vue:34-55` places `MapViewer` first in `#ingameBoard`, before the reserved command panel.
- `legacy/devsam-core/hwe/ts/PageFront.vue:37-49` passes detail-map data, enables city click, and wires `genHref`/`city-click`.
- `legacy/devsam-core/hwe/ts/PageFront.vue:553-660` defines the 1000px desktop grid as `500px 200px 300px`, with the map spanning columns `1 / 3`.
- `web/game/components/game/MapViewer.tsx:116-121` already exposes `live`, `showMe`, and `refreshKey` to mirror legacy `GetMap`.
- `web/game/components/game/MapViewer.tsx:219-220` uses `currentCityId` or live `myCity` for the current-city blink marker.

## Baseline

- `GameChrome` used `<MapViewer />` with no props.
- That default renders the cached preview map first, then optionally merges live state only when `live` is true.
- The desktop grid already matches the legacy `500 / 200 / 300` layout, so the parity gap is functional rather than a new layout primitive.

## Root Cause

The main board assembled the right slot order but called the map in preview mode. That made the main page diverge from legacy `PageFront.vue`, where the map is the interactive detail map tied to the player state and refresh cycle.

## Change

- `GameChrome` now calls `MapViewer` with `live`, `showMe={1}`, `refreshKey`, and `currentCityId={city?.id ?? null}`.
- `MapViewer` keeps the previous map while a `refreshKey` reload is in flight, matching legacy `map.value = await GetMap(...)` replacement timing.
- Stale comments that described the main board as a placeholder were removed.
- `GameChrome.main-map.test.tsx` locks the main board map call and asserts click is not disabled.
- `MapViewer.props.test.tsx` locks the refresh behavior so the canvas does not fall back to the loading placeholder during a live refresh.

## Verification

- Independent reviewer (`ce-correctness-reviewer`) found two issues: refresh flicker and a missing click-enabled assertion. Both were fixed.
- `pnpm --dir web/game test -- MapViewer.props.test.tsx GameChrome.main-map.test.tsx` passed. Vitest ran the full suite: 13 files, 72 tests.
- `pnpm --dir web/game test` passed: 13 files, 72 tests.
- `pnpm --dir web/game typecheck` passed.
- `pnpm --dir web/game build` passed with existing unrelated warnings in `generals`, `tournament`, and `GeneralBasicCard`.

## Remaining Risk

The visual/live-server check still needs to be run after this branch is merged, promoted to `s1`, and deployed. The existing CSS grid was not changed in this loop.
