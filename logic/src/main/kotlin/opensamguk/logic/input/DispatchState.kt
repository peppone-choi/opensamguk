package opensamguk.logic.input

/**
 * Game-design defaults, measured in world phases rather than wall-clock time.
 *
 * A refusal costs loyalty here, at once (spec §2.4). Its renown cost is not a policy field: it is the
 * monthly assessment's `dispatchRefusal` event (-4, `renown-assessment-v1.json`), recorded at most once per
 * month (2026-09-23 user decision — the former immediate -1 was removed so that renown moves on one path only).
 */
data class DispatchPolicy(
    val responsePhases: Int = 12,
    val refusalLoyaltyLoss: Int = 5,
) {
    init { require(responsePhases > 0 && refusalLoyaltyLoss >= 0) }
}

/** Three phases per month, including year transitions. */
data class Phase(val year: Int, val month: Int, val phase: Int) : Comparable<Phase> {
    init { require(year >= 0 && month in 1..12 && phase in 1..3) }
    private val ordinal: Long get() = year.toLong() * 36 + (month - 1) * 3 + phase - 1
    override fun compareTo(other: Phase): Int = ordinal.compareTo(other.ordinal)
    fun plus(phases: Int): Phase {
        require(phases >= 0)
        val next = ordinal + phases
        require(next / 36 <= Int.MAX_VALUE)
        return Phase((next / 36).toInt(), ((next % 36) / 3 + 1).toInt(), (next % 3 + 1).toInt())
    }
    fun toMetaValue(): Map<String, Any> = linkedMapOf("year" to year, "month" to month, "phase" to phase)
    companion object {
        fun read(raw: Any?): Phase {
            val value = raw as? Map<*, *> ?: invalidDispatchState()
            require(value.keys == setOf("year", "month", "phase"))
            return Phase(value["year"] as? Int ?: invalidDispatchState(),
                value["month"] as? Int ?: invalidDispatchState(), value["phase"] as? Int ?: invalidDispatchState())
        }
    }
}

enum class DispatchStatus { PENDING, ACCEPTED, REFUSED, CANCELLED }

/** Private latest dispatch; the previous accepted assignment is stored separately. */
data class DispatchState(
    val dispatchId: String,
    val issuerId: Int,
    val targetId: Int,
    val nationId: Int,
    val countyId: Int,
    val issuedAt: Phase,
    val dueAt: Phase,
    val status: DispatchStatus = DispatchStatus.PENDING,
) {
    init {
        require(dispatchId.matches(Regex("[A-Za-z0-9._:-]{1,128}")))
        require(issuerId > 0 && targetId > 0 && issuerId != targetId && nationId > 0 && countyId > 0)
        require(dueAt > issuedAt)
    }
    fun toMetaValue(): Map<String, Any> = linkedMapOf(
        "dispatchId" to dispatchId, "issuerId" to issuerId, "targetId" to targetId,
        "nationId" to nationId, "countyId" to countyId, "issuedAt" to issuedAt.toMetaValue(),
        "dueAt" to dueAt.toMetaValue(), "status" to status.name,
    )
    companion object {
        const val META_KEY = "dispatch"
        private val fields = setOf("dispatchId", "issuerId", "targetId", "nationId", "countyId", "issuedAt", "dueAt", "status")
        fun read(meta: Map<String, Any?>): DispatchState? {
            if (META_KEY !in meta) return null
            val value = meta[META_KEY] as? Map<*, *> ?: invalidDispatchState()
            require(value.keys == fields)
            fun int(key: String) = value[key] as? Int ?: invalidDispatchState()
            return DispatchState(value["dispatchId"] as? String ?: invalidDispatchState(), int("issuerId"),
                int("targetId"), int("nationId"), int("countyId"), Phase.read(value["issuedAt"]),
                Phase.read(value["dueAt"]), DispatchStatus.valueOf(value["status"] as? String ?: invalidDispatchState()))
        }
    }
}

/** A destination for subsequent personal-turn marching, never an immediate position change. */
data class CountyAssignment(val dispatchId: String, val issuerId: Int, val nationId: Int, val countyId: Int) {
    init {
        require(dispatchId.matches(Regex("[A-Za-z0-9._:-]{1,128}")))
        require(issuerId > 0 && nationId > 0 && countyId > 0)
    }
    fun toMetaValue(): Map<String, Any> = linkedMapOf("dispatchId" to dispatchId, "issuerId" to issuerId,
        "nationId" to nationId, "countyId" to countyId)
    companion object {
        const val META_KEY = "countyAssignment"
        fun read(meta: Map<String, Any?>): CountyAssignment? {
            if (META_KEY !in meta) return null
            val value = meta[META_KEY] as? Map<*, *> ?: invalidDispatchState()
            require(value.keys == setOf("dispatchId", "issuerId", "nationId", "countyId"))
            return CountyAssignment(value["dispatchId"] as? String ?: invalidDispatchState(),
                value["issuerId"] as? Int ?: invalidDispatchState(), value["nationId"] as? Int ?: invalidDispatchState(),
                value["countyId"] as? Int ?: invalidDispatchState())
        }
    }
}

private fun invalidDispatchState(): Nothing = throw IllegalArgumentException("invalid HWIHA dispatch state")
