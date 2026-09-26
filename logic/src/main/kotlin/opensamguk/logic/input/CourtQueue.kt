package opensamguk.logic.input

/** One private queued decision on its issuer's metadata; actor identity is the containing general. */
data class QueuedDispatch(
    val requestId: String,
    val ownerUserId: Int,
    val targetGeneralId: Int,
    val countyId: Int,
) {
    init {
        require(requestId.matches(Regex("[A-Za-z0-9._:-]{1,128}")))
        require(ownerUserId > 0 && targetGeneralId > 0 && countyId > 0)
    }

    fun toMetaValue(): Map<String, Any> = linkedMapOf(
        "requestId" to requestId, "ownerUserId" to ownerUserId,
        "targetGeneralId" to targetGeneralId, "countyId" to countyId,
    )

    companion object {
        const val META_KEY = "queuedDispatch"
        private val fields = setOf("requestId", "ownerUserId", "targetGeneralId", "countyId")

        fun read(meta: Map<String, Any?>): QueuedDispatch? {
            if (META_KEY !in meta) return null
            val value = meta[META_KEY] as? Map<*, *> ?: invalid()
            require(value.keys == fields)
            return QueuedDispatch(
                value["requestId"] as? String ?: invalid(),
                value["ownerUserId"] as? Int ?: invalid(),
                value["targetGeneralId"] as? Int ?: invalid(),
                value["countyId"] as? Int ?: invalid(),
            )
        }

        private fun invalid(): Nothing = throw IllegalArgumentException("invalid HWIHA court queue")
    }
}
