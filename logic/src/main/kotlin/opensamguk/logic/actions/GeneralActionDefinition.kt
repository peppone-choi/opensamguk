package opensamguk.logic.actions

interface GeneralActionDefinition {
    val key: String
    val name: String
    fun buildConstraints(ctx: opensamguk.logic.constraints.ConstraintContext): List<opensamguk.logic.constraints.Constraint>
    fun resolve(context: GeneralActionResolveContext)
}
