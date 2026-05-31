# P5 NPC-AI Plan — Adversarial Re-Review (synthesis)

**Reviewed:** `docs/superpowers/plans/2026-05-30-p5-npc-ai.md`
**Reviewers (re-attack round 2):** parity-adversary · feasibility (deduped + severity-ranked below)
**Verdict:** **READY-TO-EXECUTE** — 0 blockers remain. All 7 prior blockers (B1 parity-fatal + B2–B7 structural) are source-verified CLOSED. Only LOW/FYI residuals, none gating Tier-0 kickoff.

---

## VERDICT

**READY-TO-EXECUTE.** The revised plan is parity-sound and feasibility-clean. Both reviewers independently re-verified every prior blocker against live PHP source (`GeneralAI.php`, `AutorunGeneralPolicy.php`, `AutorunNationPolicy.php`) and the live Kotlin seam (`ReservedTurnHandler.kt`), and confirmed each maps to a concrete HARD step with file:line citations, tests, and dependency enforcement. The fan-out may begin. The residual items below are documentation-hygiene / one-deferred-architectural-choice and do not block — they should be folded into the relevant task notes as the executor reaches them.

**✅ B1 (parity-fatal inversion) is now CORRECTLY FIXED.** The KV `priority` override is LIVE for BOTH the general AND nation policy — verified directly against `AutorunGeneralPolicy.php:7-39` (bare-action-name statics: `static $귀환='귀환'` … covering all 14 `$default_priority` names at :42-58, with NPC증여 commented) → `property_exists($this,'귀환')` returns TRUE → valid items KEPT → `if($priority) $this->priority=$priority` REPLACES the default; only unknown/typo names are dropped. Decision #5, FP1 (steps 1/3/4), FP2 (steps 1/4), and the B1 test assertions are all correctly inverted ("valid-name REPLACES / typo DROPS / empty leaves default"). No residual "dead-KV" framing survives anywhere except the FP1 task note that explains the inversion (appropriate). The contradicting `.context/p5-research/G1-general-policy.md` §2 is the stale/wrong artifact — the PLAN is sound, and FP1:221 already flags the old G1 claim as proof the class was not read end-to-end.

---

## PRIOR BLOCKERS — RE-VERIFICATION (all CLOSED)

| # | Blocker | Status | Proof |
| --- | --- | --- | --- |
| **B1** | decision #5 KV-override inversion (PARITY-FATAL) | **✅ CLOSED** | `AutorunGeneralPolicy.php:7-39` bare-name statics → `property_exists` TRUE → override LIVE for BOTH policies. Decision #5 / FP1 / FP2 / B1 tests all correctly inverted. Both reviewers confirm against PHP source. |
| **B2** | F-SEAM real `handle()` signature + pass-order | **✅ CLOSED** | FM0 step 1 pins live sig `handle(generalId, actionCode: String, year, month, date): HandledTurn` (`ReservedTurnHandler.kt:93`), `definition.key` seed component (:137), `reservedActionOf:(Int)->String` arg-drop. FM1 widens to `(actionCode, argJson)`. FM0 step 2 pins NATION-pass-before-GENERAL-pass under one `processBlocked()` (`TurnExecutionHelper.php:299-348`), distinct `'nationCommand'`/`'generalCommand'` re-seed. |
| **B3** | `chooseInstantNationTurn` dead-infra + missing `processNationCommand` path | **✅ CLOSED** | `chooseInstantNationTurn` explicitly NOT wired (decision #3 / FD2 / FM2). FM2 ports `ProcessNationCommand` reusing the green `processCommand` while-loop, wired before the general pass. |
| **B4** | FP1/FP2 must START with full end-to-end policy source-read | **✅ CLOSED** | FP1/FP2 begin with explicit full-read steps; merge behavior gated on a crafted valid-name-REPLACES / typo-DROPS golden before F-DISPATCH consumes the order. The B1 inversion proves the read was done this pass. |
| **B5** | F-FACADE PK-order despite no `ORDER BY` | **✅ CLOSED** | FC1 step 1 / FC2 step 1 both labeled HARD REQUIREMENT: `.sortedBy{it.cityId}` / `.sortedBy{it.no}` + `LinkedHashMap`, citing R-FACADE §1 no-`ORDER BY` proof; tests assert shuffled-insertion → ascending. F-HELPERS (FH1) is a real source-read gating F-FACADE. |
| **B6** | F-BFS reuse-vs-rebuild + F-BFS→F-FACADE sequencing | **✅ CLOSED** | FB1 step 1 forbids leaving reuse-vs-rebuild to the implementer; mandates REUSE of P4 `CityConst.path` name-order (verified: `CityConst.kt:84-88` LinkedHashMap byte-identical to PHP `_generate` :206-210; `SearchDistanceListToDest.kt:34/58` iterates `path.keys` name-order). F-BFS→F-FACADE sequencing enforced (dep graph line 145, Wave-0 line 165, FC gate "Sequential after F-BFS, R-BFS §4"). |
| **B7** | `calcRecentWarTurn` helper read before FC2 | **✅ CLOSED** | F-HELPERS (FH1) pins helper bodies with file:line (`calcRecentWarTurn` 12000 sentinel, `joinYearMonth` −1, `getOutcome` half-away) and gates F-FACADE. |

**Additional confirmations from the re-attack (beyond the 7 blockers):**

- **Draw counts — all 5 source-confirmed.** 선전포고 = **3** draws (`:1923` `nextBool(trialProp**6)` A [0-or-1 at ≥1/≤0 boundary], `:1959` `nextBool(1/count(lowTargetNations))` B [empty-`nations` fallback only], `:1966` `choiceUsingWeight(nations)` C; M3 correctly mandates "verify Kotlin emits 3 not the TS 2"). doNPC헌납 = per-resource (`:2841` inside `foreach(resourceMap)` rice-then-gold, `$reqRes>0 &&` short-circuit → 0–2 draws + terminal `:2858` `choiceUsingWeightPair`; m2). 출병 `:2720` draw-before-guards (fires after 2 early-returns, before the train/atmos/crew/front guards; m1). choosePromotion `:4102` phantom (NEVER draws; `:4099` `nextBool(0.1)` draws once per OCCUPIED slot only; decision #9). calcGenType conditional-first (`:185`/`:193` `nextBool` 0-or-1, only in near-balance band; decision #7/FI2).
- **ORDER BY RAND quarantine SOUND.** Both sites MySQL-side RAND, 0 DRBG draws (`:3324` do선양, `:3345` 오랑캐임관). 1010 census: npc 2/6 only → 0 npc==5, 0 npc==9 → neither path reachable; GT1 emits live `SELECT npc,COUNT(*)` (expect 678/0/0), ABORTS if non-zero, commits `npc-census-1010.json`.
- **Priority orders match grand truth.** General `default_priority` (14) = `AutorunGeneralPolicy.php:42-58`; Nation `defaultPriority` (20) = `AutorunNationPolicy.php:38-68`; `availableInstantTurn` (12) = :71-84; chief-gate 18-flag (npcType<2) = :263-288.
- **Reserved-honor asymmetry CONFIRMED.** GENERAL `:3767` honors non-휴식 reserved WITHOUT gate/log; NATION `:3650-3659` gates `hasFullConditionMet()`, fail-logs `"{failString} <1>{date}</>"` (now source-confirmed at :3656-3657), falls through. Decision #4 correct.
- **Bridge pack-map CONFIRMED.** 35 emitted codes → 29 green / 9 missing-def / 1 divergent (che_출병). FR1 = 5 presets ("5 NOT 4", both `hasRoute` AND `hasRouteWithEnemy` absent). che_몰수 = EXISTS-GREEN (not re-created). CheBallyeong `destGenaralID` typo → always-null via argTest, port verbatim (M9). Single-canonicalization, argTest-first (M2/FR3).
- **No new PHP-vs-TS trap.** Every TS-divergence flagged PHP-wins (선전포고 3-vs-2, 징병 choiceUsingWeight-vs-deterministic-armType, 천도 TS-drops, last_attackable static-Map-vs-ChangeRecorder-delta, roundTo half-up-vs-PhpRound, buildSeedBase-vs-hiddenSeed-direct).

---

## RESIDUAL ITEMS (non-blocking)

### LOW / architectural-choice — che_출병 FULL-pack repair leaves a deferred fork
*(feasibility, confidence 75)* FR1 (line 369) defers an architectural decision to implementation: the shared `CheChulbyeong.buildConstraints` is consumed by BOTH the green P4 battle resolve AND the new AI bridge. R-BRIDGE §2 warns P4 may have intentionally trimmed the pack for the BO3 gate. An unscoped global repair could regress the closed P4 battle gate. **Recommend:** decide now in FR0/FR1 — add the 5 absent constraints (NotOpeningPart / NotSameDestCity / ReqGeneralRice / AllowWar / HasRouteWithEnemy) under a ctx-mode/flag scoped to the AI-bridge FULL path (or a separate `buildAiConstraints`), leaving the P4 resolve pack untouched; then assert BOTH the P4 battle gate AND the AI-bridge boolean green. This is the only residual that touches a closed gate — fold the decision into FR1 before that task starts.

### LOW — stale G1 research doc should be annotated
*(parity-adversary)* `.context/p5-research/G1-general-policy.md` §2/§Summary is WRONG (claims the general KV-override is "effectively DEAD", missing `AutorunGeneralPolicy.php:7-39`). The PLAN already overrides it, but the stale doc should be annotated/corrected so a future executor doesn't trust it.

### LOW — add a "survives-ctor-dies-in-loop" KV test vector
*(parity-adversary)* FP1:223 should add an explicit test case: a KV item naming a `can*`/`priority` prop (e.g. `"can귀환"`) PASSES `property_exists` in the ctor but DIES in the dispatch loop at `GeneralAI.php:3830` (`'can'+'can귀환'='cancan귀환'` → notice+skip). Harmless (never fires) but the matrix should cover it so the impl doesn't accidentally drop it at the ctor.

### LOW — pick ONE deterministic 오랑캐 substitute rule
*(parity-adversary)* F-QUAR:459 / G4 §4 is self-inconsistent: it says `min(nation)` but parenthetically "the nation of the smallest `no`" (two different values). Path is unreachable in the gate, but pick one rule — recommend **nation-of-min(no)** (matches the row-projection `ORDER BY RAND()` semantics + G13 insertion order).

### FYI — wording / notes
- m1 / `:201` & `:515`: "4 early-returns" → "the train/atmos/crew/front guards" (there are actually 5 returns after `:2720`; cosmetic).
- FC2: explicitly note `categorizeNationGeneral` must itself invoke `categorizeNationCities` first (the `:3533` internal call), not rely on caller order — makes the FC1-before-FC2 dependency robust to call-site order.
- FR3:387 / Residual #1: the deny-log format `"{failString} <1>{date}</>"` is now SOURCE-CONFIRMED (`:3656-3657`); optional confidence bump from UNCERTAIN to CONFIRMED (only `getFailString()`'s internal shape remains a separate-method unknown).

### FYI — long-sim window depth N is a capture-time output
*(feasibility, confidence 50)* GT3 dimension (c) (line 576, residual UNCERTAIN #2): the long-sim turn-count N is golden-gated and resolved by the harness at capture time, not a pre-decided constant. Correctly deferred — flagged only so the executor knows N is an output, not a blocker.

---

## Summary counts

- **Blockers: 0** (all 7 prior — B1 parity-fatal + B2–B7 — source-verified CLOSED)
- **Residual LOW: 4** (che_출병 pack scoping fork [architectural, decide in FR1]; annotate stale G1 doc; add survives-ctor KV test vector; pick one 오랑캐-substitute rule)
- **Residual FYI: 4** (wording fix; categorizeNationGeneral internal-call note; deny-log confidence bump; long-sim N is a capture output)

**The plan is READY-TO-EXECUTE. B1 — the parity-fatal inversion — is correctly fixed (KV `priority` override LIVE for BOTH policies, valid-name REPLACES / typo DROPS, verified against PHP source).**
