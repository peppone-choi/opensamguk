package opensamguk.logic.ai

import opensamguk.logic.actions.GeneralActionDefinition
import opensamguk.logic.constraints.ConstraintContext
import opensamguk.logic.constraints.ConstraintMode
import opensamguk.logic.constraints.ConstraintResult
import opensamguk.logic.constraints.StateView
import opensamguk.logic.constraints.evaluateConstraints

/**
 * F-BRIDGE — the AI candidate accept/reject gate (Task FR3).
 *
 * `candidateAllowed` is the *literally identical* gate `ReservedTurnHandler.handle` makes (A13):
 * the AI emits RAW args, the bridge runs the command's `argTest` (PHP `isArgValid`) FIRST then
 * `evaluateConstraints(...FULL...)`. There is NO separate "AI predicate" — the AI's gate IS the
 * execution gate (`hasFullConditionMet()` = `testFullConditionMet() === null`, BaseCommand.php:462).
 *
 * PHP `testFullConditionMet()` (BaseCommand.php:377-413) ORDER:
 *   1. `isArgValid()` — set by the ctor's `argTest()` (BaseCommand.php:77). FALSE → reason
 *      `'인자가 올바르지 않습니다.'` returned BEFORE any constraint runs.
 *   2. `Constraint::testAll($fullConditionConstraints)` — FULL mode, first-deny-wins, LIST ORDER.
 *   3. `testPostReqTurn()` tail — the term-stack tail AFTER constraints. **The entire P5 AI-emitted
 *      set has `getPostReqTurn() == 0` (no term tail), so this is a structural no-op for the gate;
 *      the bridge mirrors the handler's call shape (which likewise omits it for postReqTurn==0).**
 *
 * **Single canonicalization (M2):** the AI emits RAW args; the bridge canonicalizes EXACTLY ONCE via
 * `def.parseArgs(rawArgs)` (the argTest analogue) — NOT in the AI-emit layer AND again in the command.
 * The RAW map the caller passed is NEVER mutated; the canonical map is fed to the FULL-mode ctx.
 *
 * **The argTest gate (M9, R-BRIDGE §4):** PHP `argTest` rejects on a missing required key
 * (`key_exists`). The Kotlin defs canonicalize via `parseArgs`; a key missing from the RAW emit
 * surfaces as an absent/null value in the canonical map. So the gate rejects when ANY key declared in
 * `def.argsSchema` is absent-or-null after canonicalization. This reproduces the CheBallyeong latent
 * bug verbatim: `do부대전방발령` emits the typo `destGenaralID` → the correct `destGeneralID` is missing
 * → canonical `destGeneralID == null` → argTest false → `do부대전방발령` ALWAYS returns null.
 *
 * **FRESH ctx per candidate** (no memo bleed): the caller hands a per-candidate [ConstraintContext];
 * the bridge `.copy()`s it (canonical args + FULL mode) so two candidates never share state.
 *
 * **FULL mode** (PRECHECK's `Unknown` would phantom-deny on an absent preload): the bridge forces
 * `ConstraintMode.FULL` exactly like the daemon executor.
 */

/** PHP `'인자가 올바르지 않습니다.'` — the `isArgValid()==false` deny reason (BaseCommand.php:379). */
const val INVALID_ARG_REASON: String = "인자가 올바르지 않습니다."

/**
 * The gate-EXEMPT `do<한글>` reasons (G14): these are built+returned DIRECTLY by the dispatcher and
 * NEVER pass through `candidateAllowed`.
 *  - `do집합` / `do요양` — built and returned directly inside `chooseGeneralTurn` (no `hasFullConditionMet`).
 *  - `사망` / `재야유저` / `do예약턴` — the chooseGeneralTurn reserved-returns (not `che_*` builds at all).
 * The dispatcher consults [isGateExempt] BEFORE invoking the gate for a candidate.
 */
val GATE_EXEMPT_REASONS: Set<String> = linkedSetOf("do집합", "do요양", "사망", "재야유저", "do예약턴")

/** Whether the firing `do<한글>` reason bypasses [candidateAllowed] (G14). */
fun isGateExempt(reason: String): Boolean = reason in GATE_EXEMPT_REASONS

/**
 * The AI candidate gate — IDENTICAL to `ReservedTurnHandler.handle`'s FULL-mode constraint call,
 * fronted by the `argTest` gate.
 *
 * @param actionCode the `che_*` code the AI emits.
 * @param rawArgs the RAW arg map the AI emitted (NEVER mutated; canonicalized once internally).
 * @param ctx the per-candidate [ConstraintContext] (FRESH per candidate; the bridge copies it).
 * @param view the read-only [StateView] over the world (the daemon's WorldStateViewAdapter / a test view).
 * @param resolve resolves an action code to its [GeneralActionDefinition] (the daemon passes
 *   `registry::resolve`; identical to the handler's `registry.resolve(actionCode)`).
 * @return true iff `argTest` passes AND `evaluateConstraints(FULL) is Allow`.
 */
fun candidateAllowed(
    actionCode: String,
    rawArgs: Map<String, Any?>,
    ctx: ConstraintContext,
    view: StateView,
    resolve: (String) -> GeneralActionDefinition,
): Boolean {
    val def = resolve(actionCode)

    // (1) argTest / isArgValid FIRST — single canonicalization (M2). The RAW map is not mutated.
    val canonical = def.parseArgs(rawArgs)
    if (!argValid(def, canonical)) return false

    // (2) Constraint::testAll(fullConditionConstraints) — FULL mode, fresh ctx, list order.
    val fullCtx = ctx.copy(args = canonical, mode = ConstraintMode.FULL)
    val result = evaluateConstraints(def.buildConstraints(fullCtx), fullCtx, view)

    // (3) testPostReqTurn tail — no-op for the P5 emitted set (every emitted code has postReqTurn==0).
    return result is ConstraintResult.Allow
}

/**
 * PHP `isArgValid()` analogue: every key declared in [GeneralActionDefinition.argsSchema] must be
 * present AND non-null in the canonical map. A no-arg command (empty schema) is always arg-valid
 * (PHP `arg = null; return true`). A reqArg command whose `parseArgs` dropped a missing key (→ null,
 * or an empty canonical map on a typed argTest reject) fails here — reproducing PHP's `key_exists`
 * reject + the CheBallyeong typo always-null (R-BRIDGE §4).
 */
private fun argValid(def: GeneralActionDefinition, canonical: Map<String, Any?>): Boolean {
    for (key in def.argsSchema.keys) {
        if (canonical[key] == null) return false
    }
    return true
}
