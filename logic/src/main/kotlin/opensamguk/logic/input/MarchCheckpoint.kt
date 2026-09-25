package opensamguk.logic.input

import opensamguk.logic.world.*

/** Shared durable progress for assignment and corps orders, without a banked turn budget. */
data class HwihaMarchCheckpoint(
    val path: ResolvedLandMarchPath,
    val cursor: LandMarchCursor,
    val lastAdvancedAt: HwihaPhase,
    val stop: LandMarchStop,
) {
    init {
        require(cursor.pathHash == path.pathHash && cursor.edgeIndex <= path.edgeIds.size)
        require(cursor.edgeIndex < path.edgeIds.size || cursor.paidMm == 0L)
        when (stop) {
            LandMarchStop.ARRIVED -> require(cursor.edgeIndex == path.edgeIds.size)
            LandMarchStop.ENCOUNTER -> require(cursor.edgeIndex > 0 && cursor.paidMm == 0L)
            else -> require(cursor.edgeIndex < path.edgeIds.size)
        }
    }

    fun toMetaValue(): Map<String, Any> = linkedMapOf(
        "path" to LandMarchPathCodec.toMetaValue(path), "edgeIndex" to cursor.edgeIndex,
        "paidMm" to cursor.paidMm, "lastAdvancedAt" to lastAdvancedAt.toMetaValue(), "stop" to stop.name,
    )

    companion object {
        internal val fields = setOf("path", "edgeIndex", "paidMm", "lastAdvancedAt", "stop")

        fun read(raw: Any?, topology: StrategicTopologySnapshot, metrics: LandMarchMetricSnapshot): HwihaMarchCheckpoint {
            val value = raw as? Map<*, *> ?: invalid()
            require(value.keys == fields) { "Invalid march checkpoint schema" }
            val path = LandMarchPathCodec.restore(value["path"], topology, metrics)
            val index = value["edgeIndex"] as? Int ?: invalid()
            val paid = when (val number = value["paidMm"]) { is Int -> number.toLong(); is Long -> number; else -> invalid() }
            val checkpoint = HwihaMarchCheckpoint(path, LandMarchCursor(path.pathHash, index, paid),
                HwihaPhase.read(value["lastAdvancedAt"]), LandMarchStop.valueOf(value["stop"] as? String ?: invalid()))
            if (index < path.edgeIds.size) require(paid < metrics.edgesById.getValue(path.edgeIds[index]).costMm)
            return checkpoint
        }

        private fun invalid(): Nothing = throw IllegalArgumentException("Invalid HWIHA march checkpoint")
    }
}
