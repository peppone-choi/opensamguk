package opensamguk.logic.record

/** One active turn's ordinal counter; bootstrap with the committed maximum loaded for that turn. */
class EventOrdinalAllocator(initialTurn: EventTurn, lastCommittedOrdinal: Int? = null) {
    private var turn = initialTurn
    private var lastOrdinal = lastCommittedOrdinal ?: -1

    init { require(lastOrdinal >= -1) }

    fun allocate(currentTurn: EventTurn): OccurredAt {
        if (currentTurn != turn) {
            require(currentTurn > turn) { "Event turn moved backwards" }
            turn = currentTurn
            lastOrdinal = -1
        }
        lastOrdinal = Math.addExact(lastOrdinal, 1)
        return OccurredAt(turn.year, turn.month, turn.phase, lastOrdinal)
    }
}

data class EventTurn(val year: Int, val month: Int, val phase: Int) : Comparable<EventTurn> {
    init { OccurredAt(year, month, phase, 0) }

    override fun compareTo(other: EventTurn): Int = compareValuesBy(this, other,
        EventTurn::year, EventTurn::month, EventTurn::phase)
}
