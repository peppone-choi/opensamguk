# Main Map Box Structure Review — 2026-06-19

Verdict: cleared

## Scope

Make the game main page map use the same visible box structure and native map-body size as legacy `PageFront`.

## Legacy Evidence

- `legacy/devsam-core/hwe/ts/PageFront.vue:34-49`: main `#ingameBoard` renders `MapViewer` first in `.mapView`, in detail mode, with click enabled.
- `legacy/devsam-core/hwe/ts/components/MapViewer.vue:15-30`: the map title is rendered before `.map_body`; the button stack is inside the body.
- `legacy/devsam-core/hwe/scss/map.scss:191-208`: full-width map is `700px`; `.map_title` is `700px × 20px`; `.map_body` is `700px × 500px`.
- `legacy/devsam-core/hwe/ts/components/MapViewer.vue:256-268`: early-year title color is magenta, orange, yellow for the first three years.

## Baseline

Live Playwright against `https://sam.peppone.dev/game/s1` before this change:

- `.ib-map`: `700×528`
- `.map-viewer-canvas`: `698×499`
- `.map-viewer-cap`: bottom caption, text `scenario_1010 · 183年 12月`
- city markers: 94
- first city href: `/game/s1/city?id=1`
- bad HTTP responses: none

## Root Cause

The current `MapViewer` wrapped the canvas in a bordered card and placed the year/month text in a bottom caption. The outer width was 700px, but the border consumed two pixels, so the actual map body rendered as 698px wide. That drifted from legacy, where the title is above the body and the body itself keeps the native 700×500 coordinate surface.

## Change

- Move the year/month title above the canvas as `.map-viewer-title`.
- Remove the outer border/card treatment that shrank the canvas.
- Preserve existing live map data, city links, button stack, hide-city-name toggle, and touch behavior.
- Add a regression test pinning title-before-canvas structure and legacy early-year title colors.

## Verification

- `pnpm --dir web/game test -- MapViewer.props MapViewer.interaction GameChrome.main-map --run`
- `pnpm --dir web/game typecheck`
- `pnpm --dir web/game build`
- `tools/agent-system/check.py --strict --base origin/main --format json`
- GitHub Actions: main CI success; Build + Deploy to EC2 success.
- Admin deploy: s1 promoted to `34e092476dadcb230357ac8fe8b9b20ead03d7bc`.
- Live Playwright after promotion:
  - desktop `/game/s1`: `.ib-map=700×520`, `.map-viewer-title=700×20`, `.map-viewer-canvas=700×500`, 94 city anchors, first city click commits `/game/s1/city?id=1`.
  - mobile `/game/s1`: title above map, no bottom caption, 94 city anchors.
  - screenshots: `/tmp/opensamguk-main-after-map-box-desktop-82252750.png`, `/tmp/opensamguk-main-after-map-box-mobile-82252750.png`.
