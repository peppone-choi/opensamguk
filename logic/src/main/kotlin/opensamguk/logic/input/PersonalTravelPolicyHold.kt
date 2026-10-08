package opensamguk.logic.input

/** Sidecar marker leaves a legacy order's pinned path, cursor and partial payment untouched. */
data class PersonalTravelPolicyHold(val orderId: String, val reason: TravelFailure, val heldAt: Phase) {
    init { require(orderId.isNotBlank() && orderId.length <= 128 && reason in REASONS) }
    fun toMetaValue(): Map<String, Any> = linkedMapOf("version" to 1, "orderId" to orderId,
        "reason" to reason.name, "heldAt" to heldAt.toMetaValue())

    companion object {
        const val META_KEY = "personalTravelPolicyHold"
        val REASONS = setOf(TravelFailure.TRAVEL_POLICY_CHANGED, TravelFailure.FORCED_ROUTE_TOO_LONG,
            TravelFailure.FORCED_DURATION_EXCEEDED, TravelFailure.FORCED_MARCH_EXHAUSTED)
        fun read(meta: Map<String, Any?>): PersonalTravelPolicyHold? {
            if (META_KEY !in meta) return null
            val raw = meta[META_KEY] as? Map<*, *> ?: throw IllegalArgumentException("Invalid travel policy hold")
            require(raw.keys == setOf("version", "orderId", "reason", "heldAt") && raw["version"] == 1)
            return PersonalTravelPolicyHold(raw["orderId"] as? String ?: throw IllegalArgumentException("Invalid hold order"),
                TravelFailure.entries.singleOrNull { it.name == raw["reason"] }
                    ?: throw IllegalArgumentException("Invalid hold reason"), Phase.read(raw["heldAt"]))
        }
    }
}
