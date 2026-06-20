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

`MapViewer.tsx` had already restored the title row, color rule, live map data, city anchors, and map box size, but only ported `getTitleColor`. The legacy title tooltip depends on static game constants. Those constants existed in `GameConst`, but the API bundle, TypeScript response type, and `GameChrome` prop thread did not expose the full tooltip input set to `MapViewer`.

After PR #127 was promoted to `s1`, live QA exposed a second contract gap: the frontend code was active, but `/api/game/api/const.gameConst` only exposed `maxTechLevel`; it omitted `initialAllowedTechLevel` and `techLevelIncYear`. That made the opening-limit line render while the legacy technology-limit line stayed absent.

## Fix

- Extend `GameConstResponse` with the `gameConst` fields needed by the map title tooltip.
- Add `mapTitleTooltip()` to `MapViewer.tsx`, preserving the legacy opening-limit and technology-limit formulas.
- Pass `constData.gameConst` from `GameChrome` into the main `MapViewer`.
- Render the computed text on `.map-viewer-title` as `title` and an accessibility label.
- Expose `initialAllowedTechLevel` and `techLevelIncYear` from `GetConstController.gameConstBundle()` and pin them in `GetConstControllerTest`.

## Verification

- `pnpm --dir web/game test -- MapViewer.props.test.tsx`: 17 files / 86 tests passed.
- `pnpm --dir web/game typecheck`: passed.
- `pnpm --dir web/game build`: passed; only pre-existing warnings in `generals`, `tournament`, and `GeneralBasicCard`.
- `git diff --check`: passed.
- `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :app:game-api:test --tests 'opensamguk.gameapi.controller.GetConstControllerTest' --no-daemon`: passed.
- Live after PR #127 promotion: `s1 currentTag=d121199ea12ff0c1e1947902aa5e382fddc6b519`, `.map-viewer-title[title]="초반제한 기간 : -1년 1개월 (184년)"`, map `700x520`, title `700x20`, canvas `700x500`, anchors 94, first href `/game/s1/city?id=1`. This confirmed the frontend path but left backend const exposure as the remaining gap.

## Adoption Status

Accepted after second production merge/deploy/promotion.

- PR #128 merged to main as `2f30e96ac1c73d9374ca886f4950693a4747b208`.
- Deploy run `27858476514` passed: JVM image build/push, web image build/push, shared stack deploy, pin preservation, health + `s1` turn verification.
- `s1` promoted to `2f30e96ac1c73d9374ca886f4950693a4747b208`; `/health` returned 200.
- Live `/api/game/api/{const,front-info,map}` returned 200; `gameConst.maxTechLevel=12`, `initialAllowedTechLevel=1`, `techLevelIncYear=5`.
- Live `/game/s1` title: `184年 12月`.
- Live `.map-viewer-title[title]`: `초반제한 기간 : -1년 1개월 (184년)\n기술등급 제한 : 1등급 (186년 해제)`.
- Live `.map-viewer-title[aria-label]` includes both tooltip lines.
- Map invariants: `.ib-map 700x520`, title `700x20`, canvas `700x500`, city anchors 94, city buttons 0, first href `/game/s1/city?id=1`.
- First city click committed to `/game/s1/city?id=1` and rendered `도시 정보`.

Operational note: one manual promotion attempt used an incorrect full SHA and failed at image pull; it was immediately overwritten by the correct tag above and final deploy status confirmed the correct `s1 currentTag`.
