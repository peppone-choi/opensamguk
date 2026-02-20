package com.opensam.command.constraint

/**
 * Evaluates a list of constraints sequentially, returning the first failure
 * or Pass if all constraints are satisfied.
 */
object ConstraintChain {

    fun testAll(constraints: List<Constraint>, ctx: ConstraintContext): ConstraintResult {
        for (constraint in constraints) {
            val result = constraint.test(ctx)
            if (result is ConstraintResult.Fail) return result
        }
        return ConstraintResult.Pass
    }
}
