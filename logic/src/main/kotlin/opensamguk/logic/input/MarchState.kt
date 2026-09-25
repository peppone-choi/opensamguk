package opensamguk.logic.input

import opensamguk.logic.world.*

/** Private durable progress; no phase budget remainder is banked here. */
data class MarchState(
    val assignment: CountyAssignment,
    val path: ResolvedLandMarchPath,
    val cursor: LandMarchCursor,
    val lastAdvancedAt: Phase,
    val stop: LandMarchStop,
) {
    private val checkpoint = MarchCheckpoint(path, cursor, lastAdvancedAt, stop)

    fun toMetaValue(): Map<String, Any> = linkedMapOf<String, Any>(
        "version" to 1, "assignment" to assignment.toMetaValue(),
    ) + checkpoint.toMetaValue()

    companion object {
        const val META_KEY = "hwihaMarch"
        private val fields = setOf("version", "assignment", "path", "edgeIndex", "paidMm", "lastAdvancedAt", "stop")
        fun read(meta: Map<String, Any?>, topology: StrategicTopologySnapshot, metrics: LandMarchMetricSnapshot): MarchState? {
            if (META_KEY !in meta) return null
            val value = meta[META_KEY] as? Map<*, *> ?: invalid()
            require(value.keys == fields && value["version"] == 1) { "Invalid march metadata schema" }
            val assignment = CountyAssignment.read(mapOf(CountyAssignment.META_KEY to value["assignment"])) ?: invalid()
            val checkpoint = MarchCheckpoint.read(value.filterKeys { it in MarchCheckpoint.fields }, topology, metrics)
            return MarchState(assignment, checkpoint.path, checkpoint.cursor, checkpoint.lastAdvancedAt, checkpoint.stop)
        }
        private fun invalid(): Nothing = throw IllegalArgumentException("Invalid HWIHA march metadata")
    }
}
