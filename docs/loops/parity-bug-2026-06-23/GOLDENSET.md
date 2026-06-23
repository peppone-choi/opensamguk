# GOLDENSET — parity-bug-2026-06-23

Loop goal: close the next high-value parity gap or user-reported bug with measured before/after gates.

## Deterministic gates (run every wheel)

1. `tools/parity/gate.sh backend` — must report `XML gate green` with test count non-decreasing.
2. `cd web/game && pnpm tsc --noEmit && pnpm test run` — must pass (FE-only changes).
3. `cd web/gateway && pnpm tsc --noEmit` — must pass.

## Targeted gate (chosen per hypothesis)

- WAVE-1 diplomacy expiry: `:logic:test --tests '*DiplomacyMonthProcessor*'` + `:app:game-engine:test --tests '*Diplomacy*'`.
- Long-sim Phase 1: `:logic:test --tests '*CheckEmperior*'`.
- User-reported UI bug: Playwright/visual diff or manual screenshot of affected page.

## Golden rule

Never weaken a test or edit a golden fixture. On mismatch, fix Kotlin/Next implementation.
