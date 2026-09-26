package opensamguk.logic.input

/** A deployed corps references live unit resources and the commander's authoritative position. */
data class DeployedCorps(
    val orderId: String,
    val ownerGeneralId: Int,
    val commanderGeneralId: Int,
    val commanderRetainerId: Int?,
    val nationId: Int,
    val bugokIds: List<Int>,
    val startedAt: Phase,
) {
    init {
        require(orderId.isNotBlank() && orderId.length <= 128)
        require(ownerGeneralId > 0 && commanderGeneralId > 0 && nationId >= 0)
        require(commanderRetainerId == null || commanderRetainerId > 0)
        require((commanderRetainerId == null) == (ownerGeneralId == commanderGeneralId))
        require(bugokIds.isNotEmpty() && bugokIds.all { it > 0 })
        require(bugokIds == bugokIds.distinct().sorted())
    }
    fun toMetaValue(): Map<String, Any?> = linkedMapOf(
        "orderId" to orderId, "ownerGeneralId" to ownerGeneralId,
        "commanderGeneralId" to commanderGeneralId, "commanderRetainerId" to commanderRetainerId,
        "nationId" to nationId, "bugokIds" to bugokIds, "startedAt" to startedAt.toMetaValue(),
    )
}

data class DeploymentState(val corps: List<DeployedCorps>) {
    init {
        require(corps.map { it.commanderGeneralId } == corps.map { it.commanderGeneralId }.distinct().sorted())
        require(corps.map { it.orderId }.distinct().size == corps.size)
        require(corps.flatMap { it.bugokIds }.distinct().size == corps.sumOf { it.bugokIds.size })
        require(corps.map { it.ownerGeneralId }.distinct().size <= 1)
    }
    fun toMetaValue(): Map<String, Any> = linkedMapOf("version" to 1, "corps" to corps.map { it.toMetaValue() })

    companion object {
        const val META_KEY = "deployment"
        private val fields = setOf("orderId", "ownerGeneralId", "commanderGeneralId", "commanderRetainerId", "nationId", "bugokIds", "startedAt")
        fun read(meta: Map<String, Any?>): DeploymentState? {
            if (META_KEY !in meta) return null
            val raw = meta[META_KEY] as? Map<*, *> ?: invalid()
            require(raw.keys == setOf("version", "corps") && raw["version"] == 1)
            val corps = raw["corps"] as? List<*> ?: invalid()
            return DeploymentState(corps.map { item ->
                val row = item as? Map<*, *> ?: invalid()
                require(row.keys == fields)
                val units = row["bugokIds"] as? List<*> ?: invalid()
                DeployedCorps(row["orderId"] as? String ?: invalid(),
                    row["ownerGeneralId"] as? Int ?: invalid(), row["commanderGeneralId"] as? Int ?: invalid(),
                    row["commanderRetainerId"]?.let { it as? Int ?: invalid() },
                    row["nationId"] as? Int ?: invalid(), units.map { it as? Int ?: invalid() },
                    Phase.read(row["startedAt"]))
            })
        }
        private fun invalid(): Nothing = throw IllegalArgumentException("Invalid HWIHA deployment metadata")
    }
}
