package opensamguk.logic.travel

/** Explicit return intent survives encounter settlement removing the direct travel checkpoint. */
data class PersonalReturnStop(val orderId: String, val assignmentId: String) {
    init {
        require(orderId.isNotBlank() && orderId.length <= 128)
        require(assignmentId.matches(Regex("[A-Za-z0-9._:-]{1,128}")))
    }

    fun toMetaValue(): Map<String, Any> = linkedMapOf("version" to 1,
        "orderId" to orderId, "assignmentId" to assignmentId)

    companion object {
        const val META_KEY = "personalReturnStop"
        const val RECOVERY_AT_KEY = "personalTravelRecoveryAt"

        fun read(meta: Map<String, Any?>): PersonalReturnStop? {
            if (META_KEY !in meta) return null
            val row = meta[META_KEY] as? Map<*, *> ?: invalid()
            require(row.keys == setOf("version", "orderId", "assignmentId") && row["version"] == 1)
            return PersonalReturnStop(row["orderId"] as? String ?: invalid(),
                row["assignmentId"] as? String ?: invalid())
        }

        private fun invalid(): Nothing = throw IllegalArgumentException("Invalid personal return stop")
    }
}
