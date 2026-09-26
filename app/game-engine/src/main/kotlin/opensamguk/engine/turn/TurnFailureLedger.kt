package opensamguk.engine.turn

/** Stable identity of one independently retryable turn unit. */
sealed interface TurnFailureUnit {
    data class General(val generalId: Int) : TurnFailureUnit
    data class Envelope(val requestId: String) : TurnFailureUnit
    data class MonthlyStep(val stepId: String) : TurnFailureUnit
}

/** A value that can be stored and restored with the world's failure records. */
data class TurnFailureState(
    val consecutiveFailures: Int,
    val retryFromMonth: Int? = null,
)

/**
 * Tracks repeated failures independently per unit. Month numbers are absolute (`year * 12 + month - 1`).
 * The caller persists [snapshot] with the failure result and checks [canExecute] before invoking a unit.
 * A quarantined unit gets a fresh three-attempt window when the next month begins.
 */
class TurnFailureLedger(
    initial: Map<TurnFailureUnit, TurnFailureState> = emptyMap(),
    private val failureLimit: Int = 3,
) {
    init {
        require(failureLimit > 0)
        require(initial.values.all { state ->
            state.consecutiveFailures in 1..failureLimit &&
                (if (state.consecutiveFailures == failureLimit) {
                    state.retryFromMonth != null && state.retryFromMonth >= 0
                } else {
                    state.retryFromMonth == null
                })
        }) { "invalid persisted turn failure state" }
    }

    private val states = LinkedHashMap(initial)

    fun canExecute(unit: TurnFailureUnit, monthNumber: Int): Boolean =
        states[unit]?.retryFromMonth?.let { monthNumber >= it } ?: true

    fun recordFailure(unit: TurnFailureUnit, monthNumber: Int): TurnFailureState {
        require(monthNumber in 0 until Int.MAX_VALUE) { "invalid turn month" }
        require(canExecute(unit, monthNumber)) { "turn unit is quarantined: $unit" }
        val previous = states[unit]
        val continuing = previous?.takeIf { it.retryFromMonth == null }
        val failures = (continuing?.consecutiveFailures ?: 0) + 1
        return TurnFailureState(
            consecutiveFailures = failures,
            retryFromMonth = if (failures >= failureLimit) monthNumber + 1 else null,
        ).also { states[unit] = it }
    }

    fun recordSuccess(unit: TurnFailureUnit) {
        states.remove(unit)
    }

    fun stateOf(unit: TurnFailureUnit): TurnFailureState? = states[unit]

    fun snapshot(): Map<TurnFailureUnit, TurnFailureState> = LinkedHashMap(states)
}
