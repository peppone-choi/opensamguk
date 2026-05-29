package opensamguk.logic.constraints

sealed interface RequirementKey {
    data class General(val id: Int) : RequirementKey
    data class City(val id: Int) : RequirementKey
    data class Nation(val id: Int) : RequirementKey
    data class Env(val key: String) : RequirementKey
    data class Arg(val key: String) : RequirementKey
}

enum class ConstraintMode { FULL, PRECHECK }

data class ConstraintContext(
    val actorId: Int,
    val cityId: Int? = null,
    val nationId: Int? = null,
    val args: Map<String, Any?> = emptyMap(),
    val env: Map<String, Any?> = emptyMap(),
    val mode: ConstraintMode,
)

interface StateView {
    fun has(req: RequirementKey): Boolean
    fun get(req: RequirementKey): Any?
}

sealed interface ConstraintResult {
    data object Allow : ConstraintResult
    data class Deny(val reason: String, val constraintName: String? = null) : ConstraintResult
    data class Unknown(val missing: List<RequirementKey>) : ConstraintResult
}

interface Constraint {
    val name: String
    fun requires(ctx: ConstraintContext): List<RequirementKey>
    fun test(ctx: ConstraintContext, view: StateView): ConstraintResult
}
