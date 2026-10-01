package opensamguk.logic.creation

/** Pure precheck reused for options and the final writer check against fresh world state. */
object CreationAdmission {
    enum class Failure {
        WORLD_CHANGED,
        GENERAL_ALREADY_OWNED,
        CREATION_POLICY_UNAVAILABLE,
        INVALID_REQUEST,
        INVALID_NAME,
        NAME_ALREADY_USED,
        INVALID_NATIVE_COUNTY,
        INVALID_STATS,
        INVALID_IDEOLOGY,
        INVALID_TRAIT,
        HISTORICAL_PERSON_NOT_APPEARED,
        HISTORICAL_PERSON_UNAVAILABLE,
    }

    data class Gate(
        val expectedWorldId: Int,
        val routedWorldId: Int,
        val isHwiha: Boolean,
        val serverOpen: Boolean,
        val seasonRunning: Boolean,
        val alreadyOwnsGeneral: Boolean,
        val kind: CreationKind,
        val sourceReady: Boolean,
        val capacityAvailable: Boolean,
    )

    data class Stats(val leadership: Int, val strength: Int, val intel: Int,
        val politics: Int, val charm: Int) {
        fun values(): List<Int> = listOf(leadership, strength, intel, politics, charm)
    }

    data class Custom(val name: String, val nativeCountyId: Int, val stats: Stats,
        val ideologyId: String, val traitId: String)

    data class Historical(val generalId: Int)

    /** Live facts must be loaded again in the writer. A missing native county is intentionally allowed. */
    data class HistoricalState(val appeared: Boolean, val alive: Boolean,
        val alreadyClaimed: Boolean, val affiliationSelectable: Boolean,
        val locationValid: Boolean, val nativeCountyId: Int?)

    fun gate(gate: Gate, policy: CreationSelectionPolicy?): Failure? = when {
        gate.expectedWorldId != gate.routedWorldId -> Failure.WORLD_CHANGED
        policy == null || !gate.isHwiha -> Failure.CREATION_POLICY_UNAVAILABLE
        gate.alreadyOwnsGeneral -> Failure.GENERAL_ALREADY_OWNED
        !gate.serverOpen || !gate.seasonRunning || !gate.sourceReady || !gate.capacityAvailable ||
            policy.modes.none { it.kind == gate.kind && it.allowed } -> Failure.INVALID_REQUEST
        else -> null
    }

    /** normalizeName is a single injected server rule shared by options and writer. */
    fun custom(choice: Custom, policy: CreationSelectionPolicy,
        selectableCountyIds: Set<Int>, normalizeName: (String) -> String?): Failure? = when {
        normalizeName(choice.name) == null -> Failure.INVALID_NAME
        choice.nativeCountyId !in selectableCountyIds -> Failure.INVALID_NATIVE_COUNTY
        choice.stats.values().any { it !in policy.statRule.minimum..policy.statRule.maximum } ||
            choice.stats.values().sum() != policy.statRule.exactTotal -> Failure.INVALID_STATS
        policy.ideologies.none { it.id == choice.ideologyId } -> Failure.INVALID_IDEOLOGY
        policy.traits.none { it.id == choice.traitId } -> Failure.INVALID_TRAIT
        else -> null
    }

    fun historical(choice: Historical, state: HistoricalState?): Failure? = when {
        choice.generalId <= 0 -> Failure.INVALID_REQUEST
        state == null || !state.appeared -> Failure.HISTORICAL_PERSON_NOT_APPEARED
        !state.alive || state.alreadyClaimed || !state.affiliationSelectable || !state.locationValid ->
            Failure.HISTORICAL_PERSON_UNAVAILABLE
        else -> null
    }
}
