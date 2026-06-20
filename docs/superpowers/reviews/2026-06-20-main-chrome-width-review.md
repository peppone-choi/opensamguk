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

Accepted after production merge, deploy, promotion, and browser remeasurement.

- PR #129 merged to `main` as `eec1b6c91b49b35f9d1e53cde172040e47cee6bf`.
- CI run `27859382738` passed.
- Build + Deploy to EC2 run `27859382732` passed: image builds, shared stack deploy, pin preservation, health, and `s1` turn verification.
- Admin deploy promoted `s1` from `2f30e96ac1c73d9374ca886f4950693a4747b208` to `eec1b6c91b49b35f9d1e53cde172040e47cee6bf`.
- Live desktop `/game/s1` at `1280x900`:
  - `.game-chrome=1000px`
  - `.ingame-board=1000px`
  - `.shell-main > *` is only `game-chrome`
  - `.game-chrome > *` order is `common-toolbar`, `game-info`, `main-status`, `ingame-board`, `main-page-content`, `message-panel`, `common-toolbar`, `toast-container`
  - `.ib-map=700x520`
  - `.map-viewer-title=700x20`
  - `.map-viewer-canvas=700x500`
  - city anchors `94`, city buttons `0`
  - first city href `/game/s1/city?id=1`
  - first city click reaches `/game/s1/city?id=1` and renders `도시 정보`
  - 4xx/5xx responses: `0`
- Live mobile `/game/s1` at `390x844`:
  - `.game-chrome=374px`
  - `.ingame-board=374px`
  - `.ib-map=374x287`
  - `.map-viewer-title=374x20`
  - `.map-viewer-canvas=374x267`
  - `mapOverflowsViewport=false`
  - city anchors `94`, city buttons `0`
  - first city href `/game/s1/city?id=1`
  - city detail renders `도시 정보`
  - 4xx/5xx responses: `0`
