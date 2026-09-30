package opensamguk.engine.campaign

/** Authenticated owner activity, persisted on the owned general through the turn recorder. */
internal data class OfflineDelegationLease(
    val worldId: Int,
    val generalId: Int,
    val ownerUserId: Int,
    val lastActive: DelegationPhase,
) {
    init {
        require(worldId > 0 && generalId > 0 && ownerUserId > 0)
    }

    fun toMetaValue(): Map<String, Int> = linkedMapOf(
        "version" to VERSION,
        "worldId" to worldId,
        "generalId" to generalId,
        "ownerUserId" to ownerUserId,
        "year" to lastActive.year,
        "month" to lastActive.month,
        "phase" to lastActive.phase,
    )

    /** Repeated and stale pulses cannot move the last authenticated phase backwards. */
    fun refreshedAt(phase: DelegationPhase): OfflineDelegationLease =
        if (phase.ordinal > lastActive.ordinal) copy(lastActive = phase) else this

    companion object {
        const val META_KEY = "offlineDelegationActivity"
        private const val VERSION = 1
        private val FIELDS = setOf("version", "worldId", "generalId", "ownerUserId", "year", "month", "phase")

        /** Missing or corrupt evidence is unavailable; neither state authorizes delegation. */
        fun read(meta: Map<String, Any?>): OfflineDelegationLease? {
            if (META_KEY !in meta) return null
            val raw = meta[META_KEY] as? Map<*, *> ?: return null
            if (raw.keys != FIELDS || raw["version"] != VERSION) return null
            return runCatching {
                OfflineDelegationLease(
                    worldId = raw["worldId"] as? Int ?: return null,
                    generalId = raw["generalId"] as? Int ?: return null,
                    ownerUserId = raw["ownerUserId"] as? Int ?: return null,
                    lastActive = DelegationPhase(
                        raw["year"] as? Int ?: return null,
                        raw["month"] as? Int ?: return null,
                        raw["phase"] as? Int ?: return null,
                    ),
                )
            }.getOrNull()
        }

        fun activeIn(
            meta: Map<String, Any?>,
            worldId: Int,
            generalId: Int,
            ownerUserId: Int,
            current: DelegationPhase,
        ): OfflineDelegationLease? = read(meta)?.takeIf {
            it.worldId == worldId && it.generalId == generalId && it.ownerUserId == ownerUserId &&
                it.lastActive.ordinal <= current.ordinal
        }

        /** Two entire phases after activity must finish before the next execution may delegate. */
        fun mayDelegate(
            meta: Map<String, Any?>,
            worldId: Int,
            generalId: Int,
            ownerUserId: Int,
            current: DelegationPhase,
        ): Boolean = activeIn(meta, worldId, generalId, ownerUserId, current)?.let {
            current.ordinal - it.lastActive.ordinal >= 3L
        } ?: false
    }
}

internal data class DelegationPhase(val year: Int, val month: Int, val phase: Int) {
    init {
        require(year >= 1 && month in 1..12 && phase in 1..3)
    }

    val ordinal: Long get() = (year.toLong() * 12 + month - 1) * 3 + phase - 1
}
