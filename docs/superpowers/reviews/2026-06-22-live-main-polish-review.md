# 2026-06-22 Live Main Polish Review

Verdict: cleared

## Scope

- `FrontInfoController` now resolves `scenarioText` from the committed scenario resource before falling back to raw `scenario_code`.
- `GameInfo` no longer exposes the internal map code (`che`) when a server name is available.
- Main page width rules now only enable the 700/200/300 legacy board when the viewport can actually contain 1200px after shell padding.

## Evidence

- Legacy/display intent: scenario titles are player-facing labels, not raw codes. The local `ScenarioTitleResolver` already documents the legacy source as `ResetHelper` / `getTitle()` and resolves `scenario_1021` to `【역사모드2-2】 반동탁연합 결성(정사)`.
- Live repro before fix: `/game/s1` after reset showed `che 빼섭 0기 scenario_1021`, plus a 1200px board overflowing a 1153px chrome container at a 1200px desktop viewport.
- CSS root cause: `.shell-main > *` capped content while later `.game-chrome` / `.ingame-board` rules reasserted `max-width: 1200px`; the media query also switched to fixed 700/200/300 columns at viewport 1200px, before the content box could contain 1200px.

## Verification

- `:app:game-api:test --tests opensamguk.gameapi.controller.FrontInfoControllerTest`
- `web/game pnpm test -- GameInfo.test.tsx`
- `web/game pnpm typecheck`

## Docs

No README/AGENTS/CLAUDE change is needed: this closes a live UI bug inside the already documented server-metadata and main-screen parity work, and does not change deployment workflow or operator procedure.
