# 2026-06-20 main status panel review

Verdict: cleared

## Scope

- Restore the PageFront status rows above the main board.
- Keep the already-closed main map invariant unchanged: detail/live mode, 700x520 wrapper, 700x500 canvas, path-server city links.

## Legacy Evidence

- `legacy/devsam-core/hwe/ts/PageFront.vue:20-33`: `GameInfo` is followed by `onlineNations`, `onlineUsers`, and `nationNotice` before `#ingameBoard`.
- `legacy/devsam-core/hwe/ts/PageFront.vue:34-60`: the map remains the first `#ingameBoard` child and uses the detail `MapViewer` with click enabled.
- `legacy/devsam-core/hwe/ts/PageFront.vue:613-655`: desktop `#ingameBoard` stays `500px 200px 300px`, with the map spanning columns 1 through 3.

## Root Cause

`FrontInfoController` already exposed `global.onlineNations`, `nation.onlineGen`, and `nation.notice`, but `GameChrome` rendered `GameInfo` directly followed by `ingame-board`. The nation notice had drifted into `NationBasicCard`, which made the main structure differ from PageFront and duplicated a block that belongs above the board.

## Change

- Added `MainStatusPanel` between `GameInfo` and `ingame-board`.
- Added the missing TS contract fields for `onlineNations` and `onlineGen`.
- Removed the duplicate nation-notice block from `NationBasicCard`.
- Locked the structure in `GameChrome.main-map.test.tsx`.

## Verification

- Red baseline: `pnpm --dir web/game test -- GameChrome.main-map --run` failed because `접속중인 국가: 위, 촉` was absent.
- After fix: `pnpm --dir web/game test -- GameChrome.main-map --run` ran all 16 files and passed 83 tests.
- `pnpm --dir web/game typecheck` passed.
- `pnpm --dir web/gateway typecheck` passed.
- Local Playwright via Node REPL and system Chrome confirmed:
  - `.main-status + .ingame-board` count `1`
  - `접속중인 국가: 위, 촉`, `【 접속자 】 3`, and `한실부흥`
  - `.ib-map` `700x520`
  - `.map-viewer-title` `700x20`
  - `.map-viewer-canvas` `700x500`
  - first city link `/game/s1/city?id=1`
- `pnpm --dir web/game build` passed with pre-existing warnings only.
- PR #125 was merged to main, Actions run `27855366232` passed, and `s1` was promoted to `c925a8a712f7e4775453b7b2220c41f808968417`.
- Live Playwright with QA general `코덱스3bpr` confirmed:
  - `.main-status + .ingame-board` count `1`
  - status labels `접속중인 국가:`, `【 접속자 】`, and `【 국가방침 】`
  - `.ib-map` `700x520`
  - `.map-viewer-title` `700x20`
  - `.map-viewer-canvas` `700x500`
  - city anchors `94`
  - first city click reached `/game/s1/city?id=1`
  - `/game/s1` and `/game/s1/my` response-status capture found no 4xx/5xx responses

## Residual Risk

No known residual risk for this loop. The live labels can be empty when the source reports no active nations/users or no nation notice, but the legacy row structure and map behavior are present.
