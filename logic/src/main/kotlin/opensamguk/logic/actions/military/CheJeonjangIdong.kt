package opensamguk.logic.actions.military

import opensamguk.logic.actions.GeneralActionDefinition
import opensamguk.logic.actions.GeneralActionResolveContext
import opensamguk.logic.constraints.Constraint
import opensamguk.logic.constraints.ConstraintContext

/** Spatial execution is owned by BattlefieldTurnHandler, never the city draft resolver. */
class CheJeonjangIdong : GeneralActionDefinition {
    override val key = "che_전장이동"
    override val name = "전장 이동"
    override val category = "군사"
    override val argsSchema = linkedMapOf<String, Any?>(
        "siteId" to "string", "catalogHash" to "string", "expectedRevision" to "string",
    )
    override fun buildConstraints(ctx: ConstraintContext): List<Constraint> = emptyList()
    override fun resolve(context: GeneralActionResolveContext) = error("Battlefield movement requires the spatial turn handler")
}
