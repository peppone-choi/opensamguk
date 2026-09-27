package opensamguk.logic.input

import opensamguk.logic.world.StrategicNodeRef
import opensamguk.logic.world.StrategicTopologySnapshot

/** Persistent destination intent, stored on the commander even before any movement succeeds. */
data class CorpsOrder(
    val orderId: String,
    val ownerGeneralId: Int,
    val commanderGeneralId: Int,
    val destination: StrategicNodeRef.LandProvince,
    val topologyRevision: String,
    val topologyHash: String,
) {
    init {
        require(orderId.isNotBlank() && orderId.length <= 128)
        require(ownerGeneralId > 0 && commanderGeneralId > 0)
        require(destination.id.isNotBlank() && topologyRevision.isNotBlank())
        require(topologyHash.matches(Regex("[0-9a-f]{64}")))
    }

    fun requireBinding(corps: DeployedCorps, storageGeneralId: Int) {
        require(orderId == corps.orderId && ownerGeneralId == corps.ownerGeneralId &&
            commanderGeneralId == corps.commanderGeneralId && storageGeneralId == commanderGeneralId) {
            "Corps destination order binding mismatch"
        }
    }

    fun toMetaValue(): Map<String, Any> = linkedMapOf(
        "version" to 1, "orderId" to orderId, "ownerGeneralId" to ownerGeneralId,
        "commanderGeneralId" to commanderGeneralId, "destination" to destination.id,
        "topologyRevision" to topologyRevision, "topologyHash" to topologyHash,
    )

    companion object {
        const val META_KEY = "corpsOrder"
        private val fields = setOf("version", "orderId", "ownerGeneralId", "commanderGeneralId",
            "destination", "topologyRevision", "topologyHash")

        fun read(meta: Map<String, Any?>, topology: StrategicTopologySnapshot): CorpsOrder? {
            if (META_KEY !in meta) return null
            val raw = meta[META_KEY] as? Map<*, *> ?: invalid()
            require(raw.keys == fields && raw["version"] == 1) { "Invalid corps destination order schema" }
            val order = CorpsOrder(raw["orderId"] as? String ?: invalid(),
                raw["ownerGeneralId"] as? Int ?: invalid(), raw["commanderGeneralId"] as? Int ?: invalid(),
                StrategicNodeRef.LandProvince(raw["destination"] as? String ?: invalid()),
                raw["topologyRevision"] as? String ?: invalid(), raw["topologyHash"] as? String ?: invalid())
            require(order.topologyRevision == topology.topologyRevision && order.topologyHash == topology.contentHash) {
                "Corps destination topology mismatch"
            }
            require(topology.containsNode(order.destination)) { "Unknown corps land destination" }
            return order
        }
        private fun invalid(): Nothing = throw IllegalArgumentException("Invalid HWIHA corps destination order")
    }
}
