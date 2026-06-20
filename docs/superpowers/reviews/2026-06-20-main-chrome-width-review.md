# 2026-06-20 main chrome width review

Verdict: cleared

## Loop

- Baseline: live `/game/s1` desktop showed `.game-chrome=1100px` while `.ingame-board=1000px`.
- Hypothesis: constrain the main chrome itself to the legacy 1000px container so menu, game info, status rows, board, and record zone share one horizontal baseline.
- Merge/revert rule: keep the change only if the existing main map invariants stay green and production remeasurement changes `.game-chrome` to 1000px without shrinking `.ib-map`.

## Legacy Evidence

- `legacy/devsam-core/hwe/ts/PageFront.vue:3-10` wraps the page in one `#container`.
- `legacy/devsam-core/hwe/ts/PageFront.vue:34-49` renders the detail `MapViewer` as the first board child.
- `legacy/devsam-core/hwe/ts/PageFront.vue:613-655` sets the desktop board to `500px 200px 300px`; the map spans columns 1 through 3.
- `legacy/devsam-core/hwe/scss/common/break_500px.scss:26-40` defines the desktop breakpoint and 1000px layout.

## Baseline

Live Playwright against `https://sam.peppone.dev/game/s1` before this change:

- `.game-chrome`: `1100px`
- `.ingame-board`: `1000px`
- `.ib-map`: `700x520`
- `.map-viewer-title`: `700x20`
- `.map-viewer-canvas`: `700x500`
- city anchors: `94`
- first city href: `/game/s1/city?id=1`

Root cause: the global `.shell-main > *` cap allows 1100px children, while the ported legacy board is independently capped at 1000px. That made the visible main chrome wider than the board even though the map itself was already correct.

## Change

`web/game/app/globals.css` now makes `.game-chrome` a 1000px max-width container with auto margins.

This intentionally does not change `MapViewer`, city links, live map fetching, or the 700x520 board map box.

## Local Verification

- `pnpm --dir web/game test -- GameChrome.main-map --run`: passed, 17 files / 86 tests.
- `pnpm --dir web/game typecheck`: passed.
- `pnpm --dir web/game build`: passed. Existing unrelated warnings remained in `generals/page.tsx`, `tournament/page.tsx`, and `GeneralBasicCard.tsx`.
- `git diff --check`: passed.

## Production Status

Pending. After merge/deploy, remeasure:

- `.game-chrome=1000px`
- `.ingame-board=1000px`
- `.ib-map=700x520`
- `.map-viewer-title=700x20`
- `.map-viewer-canvas=700x500`
- city anchors `94`
- first city click reaches `/game/s1/city?id=1`
