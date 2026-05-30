package opensamguk.logic.actions.founding

import opensamguk.logic.actions.GeneralActionDefinition
import opensamguk.logic.actions.GeneralActionResolveContext
import opensamguk.logic.constraints.Constraint
import opensamguk.logic.constraints.ConstraintContext
import opensamguk.logic.constraints.beLord
import opensamguk.logic.constraints.wanderingNation
import opensamguk.logic.stats.GeneralActionPipeline

/**
 * che_해산 — faithful port of `legacy/devsam-core/hwe/sammo/Command/General/che_해산.php`.
 *
 * Disband a wandering nation (the lord's command), AI-emitted by do해산 (GeneralAI.php:3292).
 *
 * argTest (che_해산.php:30-34): `arg = []` → always true (no args).
 *
 * fullConditionConstraints (che_해산.php:44-47), in PHP ORDER (first-deny-wins):
 *   [BeLord, WanderingNation]
 *
 * run() (che_해산.php:62-119): a < init-turn guard (emits the che_인재탐색 `alternative`), then the
 * gold/rice reset + `deleteNation` cascade (every member general reverts to neutral) + makelimit=12 +
 * the OccupyCity event handler. The deleteNation cascade is a heavy founding/teardown subsystem; the
 * def ports the gate-critical constraint pack + the no-arg argTest, and the cascade resolve is a
 * downstream founding seam (it does not affect the AI SELECTION draw-for-draw gate).
 */
class CheHaesan(@Suppress("UNUSED_PARAMETER") private val pipeline: GeneralActionPipeline) : GeneralActionDefinition {
    override val key: String get() = "che_해산"
    override val name: String get() = "해산"
    override val category: String get() = "국가"

    override fun buildConstraints(ctx: ConstraintContext): List<Constraint> = listOf(
        beLord(),
        wanderingNation(),
    )

    /** che_해산.php:30-34 argTest — no args. */
    override fun parseArgs(raw: Map<String, Any?>): Map<String, Any?> = emptyMap()

    /** Downstream — the deleteNation cascade + makelimit reset (che_해산.php:62-119). */
    override fun resolve(context: GeneralActionResolveContext) { /* downstream: deleteNation cascade */ }
}
