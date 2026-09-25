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
    private val checkpoint = HwihaMarchCheckpoint(path, cursor, lastAdvancedAt, stop)

    fun toMetaValue(): Map<String, Any> = linkedMapOf<String, Any>(
        "version" to 1, "assignment" to assignment.toMetaValue(),
    ) + checkpoint.toMetaValue()

    companion object {
        const val META_KEY = "hwihaMarch"
        private val fields = setOf("version", "assignment", "path", "edgeIndex", "paidMm", "lastAdvancedAt", "stop")
        fun read(meta: Map<String, Any?>, topology: StrategicTopologySnapshot, metrics: LandMarchMetricSnapshot): HwihaMarchState? {
            if (META_KEY !in meta) return null
            val value = meta[META_KEY] as? Map<*, *> ?: invalid()
            require(value.keys == fields && value["version"] == 1) { "Invalid march metadata schema" }
            val assignment = HwihaCountyAssignment.read(mapOf(HwihaCountyAssignment.META_KEY to value["assignment"])) ?: invalid()
            val checkpoint = HwihaMarchCheckpoint.read(value.filterKeys { it in HwihaMarchCheckpoint.fields }, topology, metrics)
            return HwihaMarchState(assignment, checkpoint.path, checkpoint.cursor, checkpoint.lastAdvancedAt, checkpoint.stop)
        }
        private fun invalid(): Nothing = throw IllegalArgumentException("Invalid HWIHA march metadata")
    }
}
