package opensamguk.logic.input

import opensamguk.logic.world.*

/** Stored on the commander; the deployment owner retains live corps composition and allegiance. */
data class CorpsMarchState(
    val deploymentOrderId: String,
    val ownerGeneralId: Int,
    val commanderGeneralId: Int,
    val checkpoint: MarchCheckpoint,
) {
    init {
        require(deploymentOrderId.isNotBlank() && deploymentOrderId.length <= 128)
        require(ownerGeneralId > 0 && commanderGeneralId > 0)
    }

    /** A valid checkpoint cannot be attached to a different or newly issued deployment. */
    fun requireBinding(corps: DeployedCorps, storedOnGeneralId: Int) {
        require(storedOnGeneralId == commanderGeneralId && corps.commanderGeneralId == commanderGeneralId)
        require(corps.orderId == deploymentOrderId && corps.ownerGeneralId == ownerGeneralId)
        require(checkpoint.lastAdvancedAt >= corps.startedAt)
    }

    fun toMetaValue(): Map<String, Any> = linkedMapOf(
        "version" to 1, "deploymentOrderId" to deploymentOrderId,
        "ownerGeneralId" to ownerGeneralId, "commanderGeneralId" to commanderGeneralId,
        "checkpoint" to checkpoint.toMetaValue(),
    )

    companion object {
        const val META_KEY = "corpsMarch"
        private val fields = setOf("version", "deploymentOrderId", "ownerGeneralId", "commanderGeneralId", "checkpoint")

        fun read(meta: Map<String, Any?>, topology: StrategicTopologySnapshot, metrics: LandMarchMetricSnapshot): CorpsMarchState? {
            if (META_KEY !in meta) return null
            val value = meta[META_KEY] as? Map<*, *> ?: invalid()
            require(value.keys == fields && value["version"] == 1) { "Invalid corps march metadata schema" }
            return CorpsMarchState(value["deploymentOrderId"] as? String ?: invalid(),
                value["ownerGeneralId"] as? Int ?: invalid(), value["commanderGeneralId"] as? Int ?: invalid(),
                MarchCheckpoint.read(value["checkpoint"], topology, metrics))
        }

        private fun invalid(): Nothing = throw IllegalArgumentException("Invalid HWIHA corps march metadata")
    }
}
