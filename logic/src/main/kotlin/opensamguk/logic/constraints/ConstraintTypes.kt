package opensamguk.logic.constraints

sealed interface RequirementKey {
    data class General(val id: Int) : RequirementKey
    data class City(val id: Int) : RequirementKey
    data class Nation(val id: Int) : RequirementKey
    data class Env(val key: String) : RequirementKey
    data class Arg(val key: String) : RequirementKey

    // CD1 — dest-* family + collection/diplomacy keys. The DB-backed dest constraints NEVER call the
    // DB inside test(): the two adapters (precheck PrecheckStateViewFactory / daemon
    // WorldStateViewAdapter) PRELOAD these. DestGeneral/DestCity/DestNation resolve a preloaded
    // General/City/Nation row by id; NationList/GeneralList return the FULL collection
    // (CheckNationNameDuplicate scans NationList, ReqNationValue('gennum') reads a row); Diplomacy
    // resolves the directional (me,you) row (AllowDiplomacyStatus / battleground at-war existence).
    data class DestGeneral(val id: Int) : RequirementKey
    data class DestCity(val id: Int) : RequirementKey
    data class DestNation(val id: Int) : RequirementKey
    data object NationList : RequirementKey
    data object GeneralList : RequirementKey
    data class Diplomacy(val me: Int, val you: Int) : RequirementKey
}

enum class ConstraintMode { FULL, PRECHECK }

data class ConstraintContext(
    val actorId: Int,
    val cityId: Int? = null,
    val nationId: Int? = null,
    // CD1 — dest-* target ids (the dest constraints read DestGeneral/DestCity/DestNation by these).
    val destGeneralId: Int? = null,
    val destCityId: Int? = null,
    val destNationId: Int? = null,
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
