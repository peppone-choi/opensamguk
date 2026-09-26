package opensamguk.engine.campaign

/** One durable per-person cursor for NPC fairness and distinct reward receipts. */
internal data class RewardHistory(val count: Int, val year: Int, val month: Int, val lastId: String) {
    init {
        require(count > 0 && year in 1..9999 && month in 1..12)
        require(lastId.isEmpty() || lastId.matches(Regex("[A-Za-z0-9._:-]{1,128}")))
    }

    fun toMetaValue(): Map<String, Any> = mapOf(
        "count" to count, "year" to year, "month" to month, "lastId" to lastId,
    )

    companion object {
        const val META_KEY = "courtRewardHistory"
        const val NPC_TURN_KEY = "npcRewardTurn"
        fun turnStamp(year: Int, month: Int, phase: Int): String = "%04d-%02d-%d".format(year, month, phase)

        fun read(meta: Map<String, Any?>): RewardHistory? {
            if (META_KEY !in meta) return null
            val value = meta[META_KEY] as? Map<*, *> ?: invalid()
            require(value.keys == setOf("count", "year", "month", "lastId"))
            return RewardHistory(value["count"] as? Int ?: invalid(), value["year"] as? Int ?: invalid(),
                value["month"] as? Int ?: invalid(), value["lastId"] as? String ?: invalid())
        }

        private fun invalid(): Nothing = throw IllegalArgumentException("invalid HWIHA reward history")
    }
}
