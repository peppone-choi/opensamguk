package opensamguk.logic.input

/** Metadata supplied by a source-validated seed. This codec does not verify source identity. */
data class HwihaPersonPolicyState(
    val renownCapacity: Int,
    val acceptsEnlistment: Boolean,
    val statSourceId: String,
    val statSourceRevision: String,
    val officerId: Int,
) {
    init {
        require(renownCapacity >= 0 && officerId >= 0)
        require(statSourceId.isNotBlank() && statSourceRevision.isNotBlank())
    }

    fun toMetaValue(): Map<String, Any> = linkedMapOf(
        "renownCapacity" to renownCapacity, "acceptsEnlistment" to acceptsEnlistment,
        "statSourceId" to statSourceId, "statSourceRevision" to statSourceRevision,
        "officerId" to officerId,
    )

    companion object {
        const val META_KEY = "hwihaPersonPolicy"
        private val fields = setOf("renownCapacity", "acceptsEnlistment", "statSourceId", "statSourceRevision", "officerId")

        /** Absence is unavailable, never an implicit initial capacity or acceptance. */
        fun read(meta: Map<String, Any?>): HwihaPersonPolicyState? {
            if (META_KEY !in meta) return null
            val value = meta[META_KEY] as? Map<*, *> ?: errorValue()
            require(value.keys == fields) { "hwihaPersonPolicy fields must match the contract" }
            return HwihaPersonPolicyState(
                value["renownCapacity"] as? Int ?: errorValue(),
                value["acceptsEnlistment"] as? Boolean ?: errorValue(),
                value["statSourceId"] as? String ?: errorValue(),
                value["statSourceRevision"] as? String ?: errorValue(),
                value["officerId"] as? Int ?: errorValue(),
            )
        }

        private fun errorValue(): Nothing = throw IllegalArgumentException("invalid hwihaPersonPolicy metadata")
    }
}
