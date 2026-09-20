package opensamguk.engine.hwiha

import opensamguk.engine.turn.TurnWorldState

/** Persisted with the same general patch as the action and time advancement. */
internal object HwihaPersonalTurn {
    fun hasNoInput(reserved: opensamguk.infra.persistence.ReservedTurnRepository.ReservedTurn): Boolean =
        !reserved.rowExists && reserved.requestId == null &&
            reserved.actionCode == opensamguk.infra.persistence.ReservedTurnRepository.DEFAULT_TURN_ACTION &&
            reserved.argJson.trim() in setOf("", "{}")

    const val META_KEY = "hwihaLastPersonalTurn"

    fun eligible(meta: Map<String, Any?>, state: TurnWorldState): Boolean {
        if (!meta.containsKey(META_KEY)) return true
        val raw = meta[META_KEY] as? Map<*, *> ?: error("invalid HWIHA personal turn stamp")
        require(raw.keys == setOf("year", "month", "phase")) { "invalid HWIHA personal turn stamp fields" }
        fun integer(key: String): Int = raw[key] as? Int ?: error("invalid HWIHA personal turn stamp $key")
        val year = integer("year")
        val month = integer("month")
        val phase = integer("phase")
        require(month in 1..12 && phase in 1..3) { "invalid HWIHA personal turn stamp date" }
        return compareValuesBy(listOf(state.currentYear, state.currentMonth, state.currentPhase),
            listOf(year, month, phase), { it[0] }, { it[1] }, { it[2] }) > 0
    }

    fun after(meta: Map<String, Any?>, state: TurnWorldState): Map<String, Any?> = meta + (META_KEY to mapOf(
        "year" to state.currentYear, "month" to state.currentMonth, "phase" to state.currentPhase,
    ))
}
