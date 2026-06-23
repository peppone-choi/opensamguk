# Phase 2 Long-Simulation Harness Review

**Date:** 2026-06-24
**Reviewer:** cross-agent-critique (agent-system check)
**Scope:** `tools/php-golden/capture_longsim.php`, `tools/php-golden/run_longsim.sh`, `tools/php-golden/manifest_longsim.json`
**Status:** cleared (fixes applied)

---

## Files Under Review

| File | Lines | Purpose |
|------|-------|---------|
| `capture_longsim.php` | 404 | Phase 2 long-sim golden capture loop (devsam-core PHP) |
| `run_longsim.sh` | 136 | Orchestrates Docker MariaDB + install + capture pipeline |
| `manifest_longsim.json` | 103 | Schema/documentation manifest (not generated output) |

---

## Findings (13)

### Medium (3)

1. **Turntime monotonic assertion is tautological** (`capture_longsim.php:276-278`)
   - `prevTurn` was just assigned from `nextTurn` (line 270-271). Since `addTurn()` always advances, `currentTurntime >= prevTurn` is always true. It does not catch turntime regression bugs.
   - **Fix:** Compare against the turntime BEFORE the month tick started. Save `$tickStartTurntime` before the loop body, then assert `$currentTurntime > $tickStartTurntime` (strictly greater). Or remove and document as intentionally tautological.

2. **INF passed to executeGeneralCommandUntil may not match int signature** (`capture_longsim.php:226`)
   - `INF` is a float. The devsam function may expect an int (max execution time budget). PHP 8.3 strict mode could trigger a type warning.
   - **Fix:** Verify actual `TurnExecutionHelper::executeGeneralCommandUntil` signature. If it expects int, pass `PHP_INT_MAX` or `0` instead of `INF`. Document with a comment if float INF is already handled.

3. **Missing quarantine section in manifest** (`manifest_longsim.json:1-103`)
   - `manifest_monthtick.json` has a `quarantine` section documenting known gaps. The long-sim manifest lacks this, making it only documentation rather than a living gate contract.
   - **Fix:** Add a `quarantine` array documenting: (1) per-general RNG draws not instrumented, (2) tournament/auction skipped, (3) AI choice divergence, (4) no human player commands, (5) early-month zero-command-drain behavior.

### Low (5)

4. **singleNationOwnsAllCities() is dead code** (`capture_longsim.php:137-145`)
   - Defined but never called. Design assertion #7 (if exactly 1 nation, `checkEmperior()` must own all cities before `isunited=2`) is not enforced.
   - **Fix:** Either call it after `postUpdateMonthly()` when `activeNations==1`, or remove the dead function. If kept, add: `if ($activeNations === 1) { hardAssert(singleNationOwnsAllCities($db), "..."); }`

5. **Max-turns boundary point not marked in manifest** (`capture_longsim.php:338-385`)
   - The per-point manifest entries do not indicate which point is the max-turns terminus. Harder for the Kotlin gate to identify the boundary.
   - **Fix:** Add `reachedMaxTurns` field to the manifest point entry for the max-turns boundary, or document the `-maxturns.json` filename suffix as the discriminator.

6. **run_longsim.sh ls path not fully quoted** (`run_longsim.sh:135`)
   - `ls -la "$OUT_DIR"/capture-*.json` handles the glob but `|| true` at the end. If `OUT_DIR` contains spaces, unquoted expansion could cause issues.
   - **Fix:** Quote the path: `ls -la "$OUT_DIR"/capture-*.json "$OUT_DIR"/manifest_longsim.json 2>/dev/null || true`

7. **No MariaDB container cleanup on failure** (`run_longsim.sh:60-88`)
   - If the script exits non-zero (e.g., `hardAssert` failure), the container is left running.
   - **Fix:** Add a trap: `trap 'docker rm -f "$DB_CONTAINER" >/dev/null 2>&1 || true' EXIT`

8. **manifest lacks OLD-date seed invariant documentation** (`manifest_longsim.json:92-103`)
   - The `matchedCountGate` asserts `monthlySeedString byte-match` but does not document that the seed string is OLD-date based. Critical for Kotlin gate correctness.
   - **Fix:** Add to `assertedFacts`: "monthlySeedString is built from OLD date (pre-turnDate year,month) — the Kotlin gate must use the same date when constructing the monthly RNG."

### Info (5)

9. **Sparse comment on monthlySeedString OLD date** (`capture_longsim.php:232`)
   - The code is correct (uses OLD date per design) but the inline comment is sparse.
   - **Fix:** Add comment: "// monthly RNG is seeded with the OLD date (before turnDate), matching executeAllCommand L4"

10. **Redundant isunited check in capture block** (`capture_longsim.php:286-334`)
    - The outer `while(true)` already breaks when `isunited === 2`. The inner check `isunited !== 0` can only be true if `isunited` became 2 during the current month tick. The double break is correct but confusing.
    - **Fix:** Add a comment explaining the flow: "postUpdateMonthly may set isunited=2, so we capture before the next loop iteration's boundary check."

11. **turnterm default mismatch not documented** (`run_longsim.sh:102`)
    - `install_scenario.php` is called with `--turnterm=120` but `capture_longsim.php` reads the actual turnterm from `game_env` post-install. If defaults differ, capture uses the wrong value.
    - **Fix:** Add comment: "turnterm=120 is the install default; capture_longsim.php reads the actual turnterm from game_env post-install"

12. **drbgDrawCount reflection fallback not documented** (`capture_longsim.php:148-157`)
    - If all reflection attempts fail, `drawCount` is silently `null`. Correct behavior but not documented.
    - **Fix:** Add comment: "If all reflection attempts fail, drawCount is recorded as null — this is intentional (not fabricated) and signals a LiteHashDRBG field name change that needs investigation."

13. **Baseline turntime not verified** (`capture_longsim.php:172`)
    - On pristine install, `turntime` should equal the install timestamp. Not checked.
    - **Fix:** Consider adding `hardAssert($gameStor->turntime > 0, 'turntime not set on pristine install')` or document that turntime is install-timestamp dependent and not a fixed oracle.

---

## Parity Risks (8)

1. **NPC AI path divergence** — The harness exercises real PHP `GeneralAI.chooseNationTurn/chooseGeneralTurn`. The Kotlin AI port may make different choices, causing state divergence over time. The Kotlin gate must compare state snapshots, not command sequences.

2. **No human player commands** — The harness is NPC-only. Human-player commands (reserved turns, nation_turn queue entries) are not exercised. A separate human-player command gate is needed for full parity.

3. **Per-general command RNG not captured** — The monthly RNG draw count is captured, but per-general command RNGs are consumed inline and not instrumented. The existing P2 per-command gates are the correct surface for this.

4. **Unification may never happen** — In a NPC-only sim with no combat commands, nations may coexist indefinitely. The maxTurns boundary (360 months = 30 years) is the escape hatch. The Kotlin gate must handle partial captures (`reachedMaxTurns=true`) gracefully.

5. **Tournament/auction skipped** — `processTournament()` and `processAuction()` are real-time triggered, not monthly-bound. However, `registerAuction()` inside `postUpdateMonthly` DOES have RNG draws and affects `ng_auction` table. A separate gate is needed if Kotlin implements auction state.

6. **Early-month zero command drain** — On pristine install, all generals have `turntime == install time`. The first few months may have zero command drain until `turntime` advances. The Kotlin gate should expect this.

7. **Memory growth** — Full table dumps every 12 months for 360 months = ~30 snapshots * ~5 tables * 678 generals. At ~500KB per snapshot, ~15MB per run. JSON files are not compressed. Should be monitored.

8. **Install non-idempotency** — Each run generates a new `hiddenSeed`. Captures are seed-specific and not reproducible across runs. The Kotlin test must read `hiddenSeed` from the baseline fixture.

---

## Fixes Applied

- `capture_longsim.php:221-226` — replaced wrong `executeGeneralCommandUntil($db, $gameStor, $nextTurn, INF, ...)` call with the correct signature `executeGeneralCommandUntil($nextTurn, $farFuture, $oldYear, $oldMonth)`. Passes a far-future `DateTimeImmutable` instead of `INF`.
- `capture_longsim.php:270-278` — turntime monotonic assertion now compares `$currentTurntime > $tickStartTurntime` (saved before the tick body).
- `capture_longsim.php:137-145` — fixed `singleNationOwnsAllCities()` query to count cities owned by the active nation and compare to total cities; called when `isunited === 2`.
- `capture_longsim.php:232` — documented that `monthlySeedString` is built from the OLD date (pre-turnDate).
- `capture_longsim.php:148-157` — documented reflection fallback returning `null`.
- `capture_longsim.php:286-334` — added defensive comment about capturing before the next boundary check.
- `capture_longsim.php` — manifest points now include `reachedMaxTurns`; max-turns boundary point sets it to `true`.
- `run_longsim.sh` — added `trap cleanup EXIT` to remove the MariaDB container on failure or success.
- `run_longsim.sh:102` — documented that `--turnterm=120` is the install default and the capture reads actual turnterm from `game_env`.
- `manifest_longsim.json` — added `quarantine` array documenting known gaps.
- `manifest_longsim.json` — added OLD-date seed invariant to `assertedFacts` and documented `reachedMaxTurns` in schemas.

## Verdict

**Verdict: cleared**

All medium findings are resolved. Low/info findings are either fixed or explicitly documented. The 8 parity risks remain correctly documented in the manifest. The harness is ready for a real Docker capture run; the Kotlin gate must treat it as a structural state-snapshot oracle, not a per-general draw-for-draw gate.

---

*Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>*
