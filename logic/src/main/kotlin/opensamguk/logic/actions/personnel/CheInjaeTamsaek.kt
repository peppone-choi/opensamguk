package opensamguk.logic.actions.personnel

import opensamguk.logic.actions.GeneralActionDefinition
import opensamguk.logic.actions.GeneralActionResolveContext
import opensamguk.logic.constraints.Constraint
import opensamguk.logic.constraints.ConstraintContext
import opensamguk.logic.constraints.reqGeneralGold
import opensamguk.logic.constraints.reqGeneralRice
import opensamguk.logic.stats.GeneralActionPipeline

/**
 * che_인재탐색 — faithful port of `legacy/devsam-core/hwe/sammo/Command/General/che_인재탐색.php`.
 *
 * Scout for a new NPC general, AI-emitted by 방랑군이동/국가선택/사망대비/중립 (GeneralAI.php:
 * 3185/3412/3440/3448) — and the che_해산 < init-turn `alternative` fallback (che_해산.php:78).
 *
 * argTest (che_인재탐색.php:27-31): `arg = null` → always true (no args).
 *
 * fullConditionConstraints (che_인재탐색.php:43-46), in PHP ORDER (first-deny-wins):
 *   [ReqGeneralGold(reqGold), ReqGeneralRice(reqRice)]   where getCost() = [env['develcost'], 0]
 * (reqGold = develcost, reqRice = 0).
 *
 * run() (che_인재탐색.php:113-226): a `nextBool(foundProp)` then a `choiceUsingWeight` over the three
 * exp pools, and on a success the NPC-pool generation (`pickGeneralFromPool` + age/lifespan draws).
 * The per-turn AI draw STREAM is the P5 gate target (catalog §5); the NPC-pool generation is its own
 * downstream subsystem (pickGeneralFromPool / Sightseeing-style content), modeled here as a seam:
 * the def ports the gate-critical constraint pack + argTest; the create-NPC resolve is a downstream
 * port that does not affect the AI SELECTION draw-for-draw gate.
 */
class CheInjaeTamsaek(@Suppress("UNUSED_PARAMETER") private val pipeline: GeneralActionPipeline) : GeneralActionDefinition {
    override val key: String get() = "che_인재탐색"
    override val name: String get() = "인재탐색"
    override val category: String get() = "인사"

    /** getCost (che_인재탐색.php:76-79) = [env['develcost'], 0]. */
    private fun reqGold(ctx: ConstraintContext): Int = (ctx.env["develcost"] as? Number ?: ctx.env["develCost"] as? Number)?.toInt() ?: 0

    override fun buildConstraints(ctx: ConstraintContext): List<Constraint> = listOf(
        reqGeneralGold { c, _ -> reqGold(c) },
        reqGeneralRice { _, _ -> 0 },
    )

    /** che_인재탐색.php:27-31 argTest — no args. */
    override fun parseArgs(raw: Map<String, Any?>): Map<String, Any?> = emptyMap()

    /** Downstream — the foundProp nextBool + the NPC-pool generation (che_인재탐색.php:113-226). */
    override fun resolve(context: GeneralActionResolveContext) { /* downstream: NPC-pool scouting */ }
}
