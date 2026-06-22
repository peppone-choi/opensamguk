# History Current-Month Selector Review — 2026-06-22

## Scope

Prod `/game/s1/history` rendered a live clock header (`190년 7월 · 1순`) but the history selector fell back to `0년 1월`. The API confirmed the mismatch: `/api/game/api/history` returned `firstYearMonth=0`, `lastYearMonth=0`, and `currentYearMonth=0` while `/api/game/api/front-info` exposed `year=190`, `month=7`.

## Legacy Oracle

- `legacy/devsam-core/hwe/v_history.php:28-36`: for the current server, empty `ng_history` falls back to `Util::joinYearMonth($currentYear, $currentMonth) - 1`, then uses that parsed month as both first and last.
- `legacy/devsam-core/hwe/v_history.php:58-66`: static values still publish `currentYearMonth` from the live game clock.
- `legacy/devsam-core/hwe/ts/PageHistory.vue:120-142`: the selector lists `[first,last]`, then appends the current month option for the current server.
- `legacy/devsam-core/hwe/ts/PageHistory.vue:195-207`: no query selects `staticValues.currentYearMonth`.

## Root Cause

`HistoryController` treated `yearbook_history` empty rows as an absolute empty state and returned a zero selector range. In opensamguk the live clock already exists in `world_state`, so this diverged from the PHP current-server fallback and made the UI display `0년 1월`.

## Fix

`HistoryController` now reads `world_state` once, derives `currentYearMonth` from `currentYear/currentMonth`, and uses `currentYearMonth - 1` as `firstYearMonth`/`lastYearMonth` when `yearbook_history` has no rows. Only a DB with both empty history and no valid world clock returns the old zero range.

## Verification

- `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :app:game-api:test --tests opensamguk.gameapi.controller.F4ReadControllersTest --rerun-tasks`
- Result: `BUILD SUCCESSFUL`; `F4ReadControllersTest` 32 tests, 0 failures, 0 errors.
- `git diff --check`
- Result: clean.

## Broader Check

- `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :app:game-api:test`
- Result: not accepted as a code signal in this local environment. 11 integration tests failed during Testcontainers/Docker client initialization before exercising this change; the targeted MockMvc suite above is the deterministic gate for this controller edit.
