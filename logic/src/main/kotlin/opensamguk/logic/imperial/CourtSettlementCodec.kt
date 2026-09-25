package opensamguk.logic.imperial

/** Versioned court-policy history kept apart from imperial ownership and nation-ruler state. */
object CourtSettlementCodec {
    const val META_KEY = "courtSettlements"

    fun read(meta: Map<String, Any?>): List<CourtSettlementState>? {
        if (META_KEY !in meta) return null
        val root = meta[META_KEY].record(setOf("schemaVersion", "settlements"))
        require(root.int("schemaVersion") == 1)
        val states = root.list("settlements").map { it.readState() }
        require(states.map { it.lineCode }.distinct().size == states.size)
        return states
    }

    fun write(states: Collection<CourtSettlementState>): Map<String, Any> {
        require(states.map { it.lineCode }.distinct().size == states.size)
        return linkedMapOf(
            "schemaVersion" to 1,
            "settlements" to states.sortedBy { it.lineCode }.map { state ->
                linkedMapOf<String, Any>(
                    "lineCode" to state.lineCode,
                    "protectorNationId" to state.protectorNationId,
                    "courtCityId" to state.courtCityId,
                    "stance" to state.stance.name,
                    "history" to state.history.map { event ->
                        linkedMapOf<String, Any?>(
                            "requestId" to event.requestId, "turn" to event.turn,
                            "from" to event.from?.name, "to" to event.to.name,
                        )
                    },
                )
            },
        )
    }

    private fun Any?.readState(): CourtSettlementState {
        val r = record(setOf("lineCode", "protectorNationId", "courtCityId", "stance", "history"))
        return CourtSettlementState(r.text("lineCode"), r.int("protectorNationId"), r.int("courtCityId"),
            enumValueOf(r.text("stance")), r.list("history").map { it.readEvent() })
    }

    private fun Any?.readEvent(): CourtSettlementEvent {
        val r = record(setOf("requestId", "turn", "from", "to"))
        return CourtSettlementEvent(r.text("requestId"), r.long("turn"),
            r.optionalText("from")?.let { enumValueOf<CourtSettlementStance>(it) }, enumValueOf(r.text("to")))
    }

    private fun Any?.record(fields: Set<String>): Map<*, *> {
        val r = this as? Map<*, *> ?: invalid()
        require(r.keys == fields)
        return r
    }

    private fun Map<*, *>.text(key: String): String = (this[key] as? String)?.takeIf(String::isNotBlank) ?: invalid()
    private fun Map<*, *>.optionalText(key: String): String? = this[key]?.let { (it as? String)?.takeIf(String::isNotBlank) ?: invalid() }
    private fun Map<*, *>.int(key: String): Int = this[key] as? Int ?: invalid()
    private fun Map<*, *>.long(key: String): Long = when (val value = this[key]) {
        is Long -> value
        is Int -> value.toLong()
        else -> invalid()
    }
    private fun Map<*, *>.list(key: String): List<*> = this[key] as? List<*> ?: invalid()
    private fun invalid(): Nothing = throw IllegalArgumentException("Invalid court settlement meta")
}
