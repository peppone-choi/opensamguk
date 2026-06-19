# Loop Review: game main legacy parity

**Branch:** `codex/fix-game-main-parity`
**Reviewer:** Codex loop-engineering
**Date:** 2026-06-19

## Scope

This review covers the logged-in game main surface after character entry:

- Global menu links that still arrived from the legacy PHP menu table.
- The main board layout and basic cards rendered on `/game/{serverId}`.
- City-owner values shown by `CityBasicCard`.

README, AGENTS, and CLAUDE do not need source-of-truth edits for this slice because their standing rule already says PHP `legacy/devsam-core` and `hwe/ts` are the grand truth. This document records the specific paths and findings for the PR.

## Legacy evidence

- `legacy/devsam-core/hwe/ts/PageFront.vue:34-81` renders `mapView`, `reservedCommandZone`, `cityInfo`, `nationInfo`, `generalInfo`, and `MainControlBar` inside one `#ingameBoard`.
- `legacy/devsam-core/hwe/ts/PageFront.vue:553-655` defines the desktop board as `500px 200px 300px`, with `cityInfo` spanning the first two columns and `nationInfo` plus `generalInfo` on the final row.
- `legacy/devsam-core/hwe/ts/components/GeneralBasicCard.vue:1-157` and `:289-347` use a dense grid with a 64px portrait column and multiple label/value pairs per row.
- `legacy/devsam-core/hwe/ts/components/NationBasicCard.vue:1-99` and `:158-200` use a dense two-pair grid with a full-width nation type row.
- `legacy/devsam-core/hwe/ts/components/CityBasicCard.vue:1-117` renders city name, occupying nation, gauges, and city officers from the city DTO.
- `legacy/devsam-core/hwe/sammo/API/General/GetFrontInfo.php:489-530` builds `city.nationInfo` from the city occupying nation, not from the current general's nation.

## Findings

### 1. `[P0] Legacy PHP menu links no longer route users into 404 pages` -- CLEARED

`GlobalMenuController` intentionally keeps legacy menu target names such as `v_history.php`, `a_genList.php`, and `battle_simulator.php`. The client now normalizes those targets through `serverGameUrl` before applying the selected server id, so rendered links point at `/game/s1/history`, `/game/s1/rankings/generals`, `/game/s1/simulator`, and the other existing App Router pages.

**Evidence:**
- `web/game/__tests__/serverGameUrl.test.ts` covers legacy target normalization and server-scoped route resolution.
- Production baseline before the fix showed rendered anchors like `https://sam.peppone.dev/game/v_history.php` and `https://sam.peppone.dev/game/battle_simulator.php`.

### 2. `[P0] City card no longer derives occupying nation from the player nation` -- CLEARED

`FrontInfoController.buildCity()` now resolves `city.nationId` to `nationName` and `nationColor`, and `CityBasicCard` consumes those fields directly. This matches PHP `city.nationInfo` and prevents occupied cities from falling back to a generic or player-derived display.

**Evidence:**
- `FrontInfoControllerTest` now asserts same-nation city owner values and a different occupying nation case.
- `CityBasicCard` no longer receives the player nation as a prop.

### 3. `[P1] Main board/card layout is closer to legacy than the key/value stack` -- CLEARED

The main screen now places cards directly in the `#ingameBoard` grid, using the legacy desktop column widths and card order. General and nation cards use dense multi-column label/value grids, and the general card restores a portrait-backed header instead of a plain title-only card.

**Evidence:**
- `GameChrome` renders city, nation, and general cards in the legacy order.
- `globals.css` defines the `500px 200px 300px` board grid and direct `.ib-city`, `.ib-nation`, `.ib-general` placement.
- `pnpm build` completed successfully with only pre-existing lint warnings plus the expected Next image optimization warning for the direct legacy-style portrait image.

## Verification commands

- `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :app:game-api:test --tests opensamguk.gameapi.controller.FrontInfoControllerTest`
- `pnpm test -- serverGameUrl`
- `pnpm typecheck`
- `pnpm build`
- `tools/agent-system/check.py --format json`
- `git diff --check`

## Residual risk

- Production browser verification still has to run after the merged image deploys.
- This slice restores the main board and menu pathing; it does not claim every subpage mutation is legacy-complete.

## Verdict: cleared

No fix-required findings remain for the game main parity slice before production deployment.

**Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>**
