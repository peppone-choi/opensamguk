# Cross-agent review — remove orphaned `DiplomacyMonthProcessor`

**Scope:** `logic/src/main/kotlin/opensamguk/logic/diplomacy/DiplomacyMonthProcessor.kt`, `logic/src/test/kotlin/opensamguk/logic/diplomacy/DiplomacyMonthProcessorTest.kt`, comment update in `DiplomacyState.kt`.
**Author:** Claude Opus 4.8 (loop-parity-2026-06-23 wheel 3b).  
**Reviewer:** same-session adversarial review against the WAVE 1b backlog.

## Change

The WAVE 1b backlog claimed: "`DiplomacyMonthProcessor` 틱 호출 (불가침/정전 term 카운트다운)". Investigation showed:

- `DiplomacyMonthProcessor` exists in `logic/diplomacy/` with its own test suite, but is **never called** from any production path.
- The monthly diplomacy settlement already runs in `MonthlyPostUpdateHook` via `postUpdateMonthlyDiplomacy()` (`logic/world/PostUpdateMonthly.kt`), which faithfully ports PHP `func_gamerule.php:336-421` Q5-Q10:
  - Q5 — war-term extension from casualties,
  - Q6/Q7 — 개전/종전 logs,
  - Q9 — bulk dead reset + term decrement + `state 7→2` (불가침→통상) and `state 1→0` (선포→교전) transitions.
- Keeping an unused alternate implementation is a drift risk: future edits might accidentally call it or duplicate behavior.

The fix deletes the orphaned `DiplomacyMonthProcessor` source and tests, and updates the `DiplomacyState` KDoc comment to point to `postUpdateMonthlyDiplomacy` instead.

## Adversarial checklist

1. **No production caller exists.** Grep confirms `DiplomacyMonthProcessor.process` is referenced only in its own tests. ✅
2. **Equivalent behavior is already live.** `postUpdateMonthlyDiplomacy` Q9 covers non-aggression term countdown (`state 7, term→0 ⇒ state 2`) and declaration expiry (`state 1, term→0 ⇒ state 0, term=6`). ✅
3. **Existing test coverage remains.** `PostUpdateMonthlyDiplomacyTest` covers Q5-Q10, including the non-aggression/declaration expiry paths. ✅
4. **No RNG impact.** The deleted code was unused; the live path is unchanged. ✅
5. **No log impact.** The live path is unchanged. ✅
6. **No flush-delta impact.** The live path is unchanged. ✅
7. **No public API or docs change.** This is an internal cleanup; no user-visible contract changed. ✅

## Risks / deferred work

- None. The deletion only removes dead code.

## Docs-drift rationale

No README/AGENTS/CLAUDE update is required: this is internal code hygiene (removing an unused alternate implementation) with no user-visible behavior or API change. The change is recorded in the loop LEDGER and this review artifact.

## Verification

- `:logic:test` → BUILD SUCCESSFUL.
- `:infra:test` → BUILD SUCCESSFUL.
- `:app:game-engine:test` → BUILD SUCCESSFUL.
- `:app:game-api:test` → BUILD SUCCESSFUL.
- `cd web/game && pnpm typecheck` → no errors.
- `cd web/gateway && pnpm typecheck` → no errors.
- `cd web/game && pnpm test` → 23 files, 107 tests passed.
- `tools/agent-system/check.py --strict` → pending (review artifact + docs-drift note added).

## Verdict

**Verdict: cleared** — dead-code removal that closes a stale backlog item. The monthly diplomacy term countdown is already handled by the live `MonthlyPostUpdateHook` path.
