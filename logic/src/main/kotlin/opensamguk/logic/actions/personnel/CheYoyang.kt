package opensamguk.logic.actions.personnel

import opensamguk.logic.actions.GeneralActionDefinition
import opensamguk.logic.actions.GeneralActionResolveContext
import opensamguk.logic.constraints.Constraint
import opensamguk.logic.constraints.ConstraintContext
import opensamguk.logic.domain.LastTurn
import opensamguk.logic.stats.GeneralActionPipeline

/**
 * che_요양 — faithful port of `legacy/devsam-core/hwe/sammo/Command/General/che_요양.php`.
 *
 * Recuperate (clear injury), built+returned DIRECTLY at chooseGeneralTurn step-6 (GeneralAI.php:3773)
 * when injury > cureThreshold — there is NO `do<한글>` for it and it is GATE-EXEMPT (G14): the
 * dispatcher returns it without the [opensamguk.logic.ai] CandidateBridge gate. The def is still
 * needed so the EXECUTION resolve path can run it.
 *
 * argTest (che_요양.php:20-23): `arg = null` → always true (no args).
 *
 * fullConditionConstraints (che_요양.php:31-33): **EMPTY**.
 *
 * run() (che_요양.php:48-77): injury → 0, exp 10, ded 7, the action log.
 */
class CheYoyang(@Suppress("UNUSED_PARAMETER") private val pipeline: GeneralActionPipeline) : GeneralActionDefinition {
    override val key: String get() = "che_요양"
    override val name: String get() = "요양"
    override val category: String get() = "인사"

    /**
     * G14 — che_요양 is dispatcher-direct (chooseGeneralTurn step-6) and BYPASSES `candidateAllowed`.
     * FR3's gate-exempt list reads this flag so the bridge does NOT gate it.
     */
    val gateExempt: Boolean get() = true

    /** EMPTY fullConditionConstraints (che_요양.php:31-33). */
    override fun buildConstraints(ctx: ConstraintContext): List<Constraint> = emptyList()

    /** che_요양.php:20-23 argTest — no args. */
    override fun parseArgs(raw: Map<String, Any?>): Map<String, Any?> = emptyMap()

    override fun resolve(context: GeneralActionResolveContext) {
        val d = context.draft
        val g0 = d.general
        context.addLog("건강 회복을 위해 요양합니다. <1>${context.date}</>")
        d.general = g0.copy(
            injury = 0,
            experience = g0.experience + 10.0,
            dedication = g0.dedication + 7.0,
            lastTurn = LastTurn(name),
        )
    }
}
