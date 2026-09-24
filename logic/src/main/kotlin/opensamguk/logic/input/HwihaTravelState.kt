package opensamguk.logic.input

import opensamguk.logic.world.LandMarchMetricSnapshot
import opensamguk.logic.world.StrategicNodeRef
import opensamguk.logic.world.StrategicTopologySnapshot

/** Pinned direct travel progress. A changed order must explicitly replace this state. */
data class HwihaTravelState(
    val orderId: String,
    val inputId: String,
    val destination: StrategicNodeRef.LandProvince,
    val checkpoint: HwihaMarchCheckpoint,
    val assignmentIdAtStart: String? = null,
) {
    init {
        require(orderId.isNotBlank() && orderId.length <= 128)
        require(inputId in HwihaTravelInput.INPUT_IDS)
        require(checkpoint.path.nodeKeys.last() == destination.canonicalKey)
        require(assignmentIdAtStart == null || assignmentIdAtStart.matches(Regex("[A-Za-z0-9._:-]{1,128}")))
    }

    fun toMetaValue(): Map<String, Any> = linkedMapOf(
        "version" to 1,
        "orderId" to orderId,
        "inputId" to inputId,
        "destinationProvinceId" to destination.id,
        "checkpoint" to checkpoint.toMetaValue(),
        "assignmentIdAtStart" to assignmentIdAtStart.orEmpty(),
    )

    companion object {
        const val META_KEY = "hwihaDirectTravel"
        private val fields = setOf("version", "orderId", "inputId", "destinationProvinceId", "checkpoint", "assignmentIdAtStart")

        fun read(meta: Map<String, Any?>, topology: StrategicTopologySnapshot,
            metrics: LandMarchMetricSnapshot): HwihaTravelState? {
            if (META_KEY !in meta) return null
            val value = meta[META_KEY] as? Map<*, *> ?: invalid()
            require(value.keys == fields && value["version"] == 1) { "Invalid direct travel metadata schema" }
            val destinationId = value["destinationProvinceId"] as? String ?: invalid()
            require(destinationId.isNotBlank() && destinationId.length <= 128)
            return HwihaTravelState(value["orderId"] as? String ?: invalid(),
                value["inputId"] as? String ?: invalid(), StrategicNodeRef.LandProvince(destinationId),
                HwihaMarchCheckpoint.read(value["checkpoint"], topology, metrics),
                (value["assignmentIdAtStart"] as? String ?: invalid()).ifEmpty { null })
        }

        private fun invalid(): Nothing = throw IllegalArgumentException("Invalid HWIHA direct travel metadata")
    }
}
