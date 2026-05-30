package opensamguk.logic.actions.develop

import opensamguk.logic.actions.GeneralActionDefinition
import opensamguk.logic.actions.GeneralActionResolveContext
import opensamguk.logic.constraints.Constraint
import opensamguk.logic.constraints.ConstraintContext
import opensamguk.logic.stats.GeneralActionPipeline

/**
 * che_견문 — faithful port of `legacy/devsam-core/hwe/sammo/Command/General/che_견문.php`.
 *
 * Sightseeing, AI-emitted by 사망대비/중립 (GeneralAI.php:3414/3442/3462).
 *
 * argTest (che_견문.php:23-26): `arg = null` → always true (no args).
 *
 * fullConditionConstraints (che_견문.php:33-35): **EMPTY** → the AI's full-condition gate passes
 * unconditionally (gate ≈ argValid).
 *
 * run() (che_견문.php:55-122): a `SightseeingMessage::pickAction()` content draw + the per-outcome
 * exp/gold/rice/injury increments and the action log. The outcome content (the SightseeingMessage
 * table + the wound `nextRangeInt` draws) is its own downstream content subsystem; the def ports the
 * gate-critical EMPTY pack + the no-arg argTest, and the resolve is a downstream seam (it does not
 * affect the AI SELECTION draw-for-draw gate).
 */
fun cheGyeonmun(@Suppress("UNUSED_PARAMETER") pipeline: GeneralActionPipeline): CheGyeonmun = CheGyeonmun(pipeline)

class CheGyeonmun(@Suppress("UNUSED_PARAMETER") private val pipeline: GeneralActionPipeline) : GeneralActionDefinition {
    override val key: String get() = "che_견문"
    override val name: String get() = "견문"
    override val category: String get() = "군사"

    /** EMPTY fullConditionConstraints (che_견문.php:33-35). */
    override fun buildConstraints(ctx: ConstraintContext): List<Constraint> = emptyList()

    /** che_견문.php:23-26 argTest — no args. */
    override fun parseArgs(raw: Map<String, Any?>): Map<String, Any?> = emptyMap()

    /** Downstream — the SightseeingMessage outcome (che_견문.php:55-122). */
    override fun resolve(context: GeneralActionResolveContext) { /* downstream: sightseeing content */ }
}
