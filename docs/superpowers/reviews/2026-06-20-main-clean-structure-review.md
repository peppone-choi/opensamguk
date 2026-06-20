# 2026-06-20 main clean structure review

Verdict: cleared

## Skill Chain

- `opensamguk-php-oracle`: legacy `hwe/ts/PageFront.vue` 구조를 source path+line으로 대조.
- `webapp-testing`: live Playwright로 prod `/game/s1` DOM, 맵 치수, 도시 링크를 측정.
- `systematic-debugging`: 맵 기능 문제가 아니라 메인 chrome composition 문제로 원인 수렴.
- `loop-engineering`: baseline -> one hypothesis -> local gates -> prod adoption pending.

## Legacy Oracle

- `legacy/devsam-core/hwe/ts/PageFront.vue:20-33`: `GameInfo` 다음에 `onlineNations` / `onlineUsers` / `nationNotice` 3줄.
- `legacy/devsam-core/hwe/ts/PageFront.vue:34-112`: `#ingameBoard` 내부 순서 = map, reserved command, action mini plate, city/nation/general info, command toolbar.
- `legacy/devsam-core/hwe/ts/PageFront.vue:113-135`: `RecordZone`은 `#ingameBoard` 밖의 다음 섹션.
- `legacy/devsam-core/hwe/ts/PageFront.vue:618-655`: desktop grid = `500px 200px 300px`, map spans columns 1-2, reserved command column 3, city spans columns 1-2, command toolbar spans 1-3, nation/general share the next row.

## Baseline

Live prod before fix, using a fresh QA account and a claimed NPC general:

- `/game/s1` rendered `BackBar` buttons `← 돌아가기`, `⟳ 갱신` before `GameChrome`.
- `.shell-main > *` was `div.back-bar`, `div.game-chrome`; legacy main has no top `BackBar`.
- `.main-record-zone` parent was `ib-content`, so `RecordZone` was inside `.ingame-board` instead of following it.
- `.ingame-board` next sibling was `message-panel`, not a record-zone wrapper.
- Existing map invariant was already correct and must stay unchanged: `.ib-map=700x520`, `.map-viewer-title=700x20`, `.map-viewer-canvas=700x500`, `a.city-base=94`, first city href `/game/s1/city?id=1`, bad responses `0`.

## Root Cause

`Shell` always rendered `BackBar`, despite the local comment saying main pages skip it. Since `/game/s1` normalizes to `/game`, this added a non-legacy subpage control row to the main first viewport.

Separately, `GameChrome` mounted its `children` inside `.ingame-board` as `.ib-content`. That made the main page `RecordZone` part of the board grid, while legacy `PageFront.vue` keeps records after the board. The map implementation itself was not the source: its live mode, 700x520 box, city anchors, and server-scoped hrefs were already correct.

## Fix

- `Shell` now normalizes path-server URLs with `normalizeGamePathname()` and hides `BackBar` only on the main route.
- `GameChrome` moves function children into `.main-page-content`, a board sibling, preserving the same `frontInfo` data flow.
- Main CSS removes board gaps, gives `.main-page-content` the same 1000px cap, and makes mobile order map-first so the main screen remains map-centered.
- Removed stale `.ib-content` CSS.

## Verification

- `./node_modules/.bin/vitest run GameChrome.main-map Shell.main-route MainRecordZone`: 8 passed.
- `pnpm --dir web/game test`: 17 files / 85 tests passed.
- `pnpm --dir web/game typecheck`: passed.
- `pnpm --dir web/game build`: passed; only pre-existing warnings in `generals`, `tournament`, and `GeneralBasicCard`.
- `git diff --check`: passed.

## Pending Prod Adoption

After merge/deploy/s1 promotion, remeasure live `/game/s1`:

- `BackBar` buttons absent on main.
- `.main-status + .ingame-board` still holds.
- `.ingame-board` next sibling is `.main-page-content`.
- `.main-record-zone` parent is `.main-page-content`.
- Map invariant remains `.ib-map=700x520`, `.map-viewer-title=700x20`, `.map-viewer-canvas=700x500`, city anchors `94`, first city href `/game/s1/city?id=1`, bad responses `0`.
