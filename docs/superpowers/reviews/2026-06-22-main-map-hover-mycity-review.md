# Main Map Hover And My-City Review

## Verdict

Accepted locally. The main map hover tooltip drift is a CSS parity bug, not a backend map-data bug. The current-city marker data path is already present in production, but the marker filler missed the legacy rounded-ring basis.

## Evidence

- PHP/hwe source: `legacy/devsam-core/hwe/func_map.php:78-95,157-168` sets and returns `myCity`/`myNation` from the current general when `showMe` is enabled.
- PHP/hwe UI: `legacy/devsam-core/hwe/ts/components/MapViewer.vue:56-83` passes `city.id === drawableMap.myCity` into city marker components.
- PHP/hwe CSS: `legacy/devsam-core/hwe/scss/map.scss:60-83` defines a flat two-row `.city_tooltip`, while `:231-262` defines `.my_city` with a rounded blinking outline.
- Production baseline on `https://sam.peppone.dev/game/s1` with QA general `공손속`: map API returned `myCity=8,myNation=4`; DOM had one `.city-filler.my-city`; hover tooltip rendered as a modern card with dark background, radius, and shadow.

## Root Cause

`web/game` and `web/gateway` had modern card styling on `.map-tooltip`/`.map-preview-tooltip`. That made hover produce a rectangular card over the map instead of the legacy `city_tooltip` slab. Separately, the current-city filler had the pseudo-element outline but lacked the legacy `border-radius: 33%`, making the my-city ring basis less faithful.

## Change

- `web/game/app/globals.css` restores legacy flat tooltip dimensions, gray border, blue text rows, no shadow, and no radius.
- `web/gateway/app/globals.css` mirrors the same map preview tooltip styling.
- Both map CSS surfaces add `.city-filler.my-city { border-radius: 33%; }`.
- `web/game/__tests__/MapViewer.props.test.tsx` pins the tooltip block and my-city filler styling.

## Verification

- `cd web/game && pnpm exec vitest run __tests__/MapViewer.props.test.tsx` — 19 tests passed.
- `cd web/game && pnpm typecheck` — passed.
- `cd web/gateway && pnpm typecheck` — passed.
- `cd web/game && pnpm build` — passed with pre-existing lint warnings.
- `cd web/gateway && pnpm build` — passed with one pre-existing lint warning.
- `git diff --check` — passed.

## Remaining Risk

Production still needs the merged image promoted to `s1` and the browser tooltip CSS remeasured after deploy.
