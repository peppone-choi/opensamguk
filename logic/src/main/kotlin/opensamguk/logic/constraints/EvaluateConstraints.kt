package opensamguk.logic.constraints

fun evaluateConstraints(
    constraints: List<Constraint>,
    ctx: ConstraintContext,
    view: StateView,
): ConstraintResult {
    for (c in constraints) {
        val missing = c.requires(ctx).filter { !view.has(it) }
        if (missing.isNotEmpty() && ctx.mode == ConstraintMode.PRECHECK) {
            return ConstraintResult.Unknown(missing)
        }
        when (val r = c.test(ctx, view)) {
            is ConstraintResult.Deny -> return r.copy(constraintName = r.constraintName ?: c.name)
            is ConstraintResult.Unknown -> return r
            ConstraintResult.Allow -> continue
        }
    }
    return ConstraintResult.Allow
}

fun collectRequirements(constraints: List<Constraint>, ctx: ConstraintContext): List<RequirementKey> =
    constraints.flatMap { it.requires(ctx) }
