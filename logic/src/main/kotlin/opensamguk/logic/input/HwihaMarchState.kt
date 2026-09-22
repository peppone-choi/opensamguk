package opensamguk.logic.input

import opensamguk.logic.world.*

/** Private durable progress; no phase budget remainder is banked here. */
data class HwihaMarchState(
    val assignment: HwihaCountyAssignment,
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
        "version" to 1, "assignment" to assignment.toMetaValue(), "path" to LandMarchPathCodec.toMetaValue(path),
        "edgeIndex" to cursor.edgeIndex, "paidMm" to cursor.paidMm,
        "lastAdvancedAt" to lastAdvancedAt.toMetaValue(), "stop" to stop.name,
    )

    companion object {
        const val META_KEY = "hwihaMarch"
        private val fields = setOf("version", "assignment", "path", "edgeIndex", "paidMm", "lastAdvancedAt", "stop")
        fun read(meta: Map<String, Any?>, topology: StrategicTopologySnapshot, metrics: LandMarchMetricSnapshot): HwihaMarchState? {
            if (META_KEY !in meta) return null
            val value = meta[META_KEY] as? Map<*, *> ?: invalid()
            require(value.keys == fields && value["version"] == 1) { "Invalid march metadata schema" }
            val assignment = HwihaCountyAssignment.read(mapOf(HwihaCountyAssignment.META_KEY to value["assignment"])) ?: invalid()
            val path = LandMarchPathCodec.restore(value["path"], topology, metrics)
            val index = value["edgeIndex"] as? Int ?: invalid()
            val paid = when (val raw = value["paidMm"]) { is Int -> raw.toLong(); is Long -> raw; else -> invalid() }
            val cursor = LandMarchCursor(path.pathHash, index, paid)
            val result = HwihaMarchState(assignment, path, cursor, HwihaPhase.read(value["lastAdvancedAt"]),
                LandMarchStop.valueOf(value["stop"] as? String ?: invalid()))
            if (index < path.edgeIds.size) require(paid < metrics.edgesById.getValue(path.edgeIds[index]).costMm)
            return result
        }
        private fun invalid(): Nothing = throw IllegalArgumentException("Invalid HWIHA march metadata")
    }
}
