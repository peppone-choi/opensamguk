package opensamguk.logic.domestic

import opensamguk.logic.input.Phase

/** A county fortification reduction is bound to the completed work seen at intake. */
data class WorkReductionOrder(
    val requestId: String,
    val actorId: Int,
    val ownerUserId: Int,
    val nationId: Int,
    val requestedAt: Phase,
    val completedAt: Phase,
) {
    init {
        require(validReductionRequestId(requestId) && actorId > 0 && ownerUserId > 0 && nationId > 0)
        require(completedAt <= requestedAt)
    }

    fun toMetaValue(): Map<String, Any> = linkedMapOf(
        "requestId" to requestId, "actorId" to actorId, "ownerUserId" to ownerUserId, "nationId" to nationId,
        "requestedAt" to requestedAt.toMetaValue(), "completedAt" to completedAt.toMetaValue(),
    )

    companion object {
        fun read(raw: Any?): WorkReductionOrder {
            val row = raw as? Map<*, *> ?: errorState()
            require(row.keys == setOf("requestId", "actorId", "ownerUserId", "nationId", "requestedAt", "completedAt"))
            return WorkReductionOrder(row["requestId"] as? String ?: errorState(),
                row["actorId"] as? Int ?: errorState(), row["ownerUserId"] as? Int ?: errorState(),
                row["nationId"] as? Int ?: errorState(),
                Phase.read(row["requestedAt"]), Phase.read(row["completedAt"]))
        }
    }
}

data class WorkReductionResolution(val order: WorkReductionOrder, val resolvedAt: Phase, val reason: String? = null) {
    init {
        require(resolvedAt > order.requestedAt)
        require(reason == null || reason.matches(Regex("[A-Z][A-Z0-9_]{0,63}")))
    }

    fun toMetaValue(): Map<String, Any?> = linkedMapOf(
        "order" to order.toMetaValue(), "resolvedAt" to resolvedAt.toMetaValue(), "reason" to reason,
    )

    companion object {
        fun read(raw: Any?): WorkReductionResolution {
            val row = raw as? Map<*, *> ?: errorState()
            require(row.keys == setOf("order", "resolvedAt", "reason"))
            return WorkReductionResolution(WorkReductionOrder.read(row["order"]), Phase.read(row["resolvedAt"]),
                row["reason"]?.let { it as? String ?: errorState() })
        }
    }
}

/** The outcome and handled request ids survive reload, including after another fortification is built. */
data class WorkReductionState(
    val pending: WorkReductionOrder? = null,
    val last: WorkReductionResolution? = null,
    val processedRequestIds: Set<String> = emptySet(),
) {
    init {
        require(processedRequestIds.all(::validReductionRequestId))
        require(pending == null || pending.requestId !in processedRequestIds)
        require(last == null || last.order.requestId in processedRequestIds)
    }

    fun resolve(now: Phase, reason: String?): WorkReductionState {
        val order = requireNotNull(pending)
        return copy(pending = null, last = WorkReductionResolution(order, now, reason),
            processedRequestIds = processedRequestIds + order.requestId)
    }

    fun toMetaValue(): Map<String, Any?> = linkedMapOf(
        "version" to 1, "pending" to pending?.toMetaValue(), "last" to last?.toMetaValue(),
        "processedRequestIds" to processedRequestIds.sorted(),
    )

    companion object {
        const val META_KEY = "workReduction"

        fun read(meta: Map<String, Any?>): WorkReductionState? {
            if (META_KEY !in meta) return null
            val row = meta[META_KEY] as? Map<*, *> ?: errorState()
            require(row.keys == setOf("version", "pending", "last", "processedRequestIds") && row["version"] == 1)
            val ids = (row["processedRequestIds"] as? List<*>)?.map { it as? String ?: errorState() } ?: errorState()
            require(ids == ids.distinct().sorted())
            return WorkReductionState(row["pending"]?.let(WorkReductionOrder::read),
                row["last"]?.let(WorkReductionResolution::read), ids.toSet())
        }
    }
}

private fun validReductionRequestId(value: String) = value.matches(Regex("[A-Za-z0-9._:-]{1,128}"))
private fun errorState(): Nothing = throw IllegalArgumentException("invalid work reduction state")
