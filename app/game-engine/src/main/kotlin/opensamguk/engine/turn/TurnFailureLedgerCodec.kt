package opensamguk.engine.turn

/** Stable world_state.meta representation of the operational failure ledger. */
object TurnFailureLedgerCodec {
    const val META_KEY = "turnFailureLedger"
    private const val SCHEMA_VERSION = 1

    fun encode(states: Map<TurnFailureUnit, TurnFailureState>): Map<String, Any?>? {
        if (states.isEmpty()) return null
        TurnFailureLedger(states) // Reject invalid in-memory state before a flush can retain it.
        val units = states.entries.sortedWith(compareBy({ kind(it.key) }, { id(it.key) })).map { (unit, state) ->
            require(id(unit).isNotBlank() && (unit !is TurnFailureUnit.General || unit.generalId > 0)) {
                "invalid turn failure unit identity"
            }
            linkedMapOf<String, Any?>(
                "kind" to kind(unit),
                "id" to id(unit),
                "consecutiveFailures" to state.consecutiveFailures,
                "retryFromMonth" to state.retryFromMonth,
            )
        }
        return linkedMapOf("schemaVersion" to SCHEMA_VERSION, "units" to units)
    }

    fun decode(meta: Map<String, Any?>): Map<TurnFailureUnit, TurnFailureState> =
        decodeValue(meta[META_KEY])

    fun decodeValue(value: Any?): Map<TurnFailureUnit, TurnFailureState> {
        if (value == null) return emptyMap()
        val root = value as? Map<*, *> ?: error("invalid turn failure ledger root")
        require(number(root["schemaVersion"]) == SCHEMA_VERSION) { "unsupported turn failure ledger schema" }
        val units = root["units"] as? List<*> ?: error("invalid turn failure ledger units")
        val result = LinkedHashMap<TurnFailureUnit, TurnFailureState>()
        for (entry in units) {
            val row = entry as? Map<*, *> ?: error("invalid turn failure ledger row")
            val rowKind = row["kind"] as? String ?: error("missing turn failure unit kind")
            val rowId = row["id"] as? String ?: error("missing turn failure unit id")
            require(rowId.isNotBlank()) { "blank turn failure unit id" }
            val unit = when (rowKind) {
                "general" -> TurnFailureUnit.General(rowId.toIntOrNull()?.takeIf { it > 0 }
                    ?: error("invalid turn failure general id"))
                "envelope" -> TurnFailureUnit.Envelope(rowId)
                "monthlyStep" -> TurnFailureUnit.MonthlyStep(rowId)
                else -> error("unknown turn failure unit kind")
            }
            val state = TurnFailureState(
                consecutiveFailures = number(row["consecutiveFailures"]),
                retryFromMonth = row["retryFromMonth"]?.let(::number),
            )
            require(result.putIfAbsent(unit, state) == null) { "duplicate turn failure unit" }
        }
        return TurnFailureLedger(result).snapshot()
    }

    private fun number(value: Any?): Int {
        val n = value as? Number ?: error("invalid turn failure number")
        val long = n.toLong()
        require(long in Int.MIN_VALUE..Int.MAX_VALUE && n.toDouble() == long.toDouble()) {
            "non-integral turn failure number"
        }
        return long.toInt()
    }

    private fun kind(unit: TurnFailureUnit): String = when (unit) {
        is TurnFailureUnit.General -> "general"
        is TurnFailureUnit.Envelope -> "envelope"
        is TurnFailureUnit.MonthlyStep -> "monthlyStep"
    }

    private fun id(unit: TurnFailureUnit): String = when (unit) {
        is TurnFailureUnit.General -> unit.generalId.toString()
        is TurnFailureUnit.Envelope -> unit.requestId
        is TurnFailureUnit.MonthlyStep -> unit.stepId
    }
}
