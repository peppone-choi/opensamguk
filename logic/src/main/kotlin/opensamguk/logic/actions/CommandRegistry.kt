package opensamguk.logic.actions

import opensamguk.logic.constraints.*
import opensamguk.logic.stats.GeneralActionPipeline

/**
 * The 휴식 fallback: a no-op rest turn. No constraints, no mutation, no log beyond the rest log
 * (which is empty in P1 — a rest turn produces no mutation/log). Used by the handler whenever the
 * reserved action-code is unknown or denied.
 */
object RestAction : GeneralActionDefinition {
    override val key = "휴식"
    override val name = "휴식"
    override fun buildConstraints(ctx: ConstraintContext): List<Constraint> = emptyList()
    override fun resolve(context: GeneralActionResolveContext) { /* no-op: a rest turn produces no mutation/log in P1 */ }
}

/**
 * action-code → definition. Unknown / deny → the 휴식 fallback. The handler uses this to resolve the
 * reserved action-code and the fallback.
 */
class CommandRegistry(private val pipeline: GeneralActionPipeline, private val maxLevel: Int = 255) {
    fun resolve(actionCode: String): GeneralActionDefinition = when (actionCode) {
        "che_상업투자" -> cheSangeobTuja(pipeline, maxLevel)
        "che_농지개간" -> cheNongjigaegan(pipeline, maxLevel)
        "che_성벽보수" -> cheSeongbyeokBosu(pipeline, maxLevel)
        "che_수비강화" -> cheSubiGanghwa(pipeline, maxLevel)
        "che_치안강화" -> cheChianGanghwa(pipeline, maxLevel)
        else -> RestAction
    }
    val fallback: GeneralActionDefinition get() = RestAction
}
