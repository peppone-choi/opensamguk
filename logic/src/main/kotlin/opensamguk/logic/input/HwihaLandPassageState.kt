package opensamguk.logic.input

import opensamguk.logic.world.*

/** Versioned land passability, not a troop or corps count allowance. Callers request capacity one. */
object HwihaLandPassageState {
    const val META_KEY = "hwihaLandPassage"
    private val fields = setOf("version", "topologyRevision", "topologyHash", "edges")
    private val edgeFields = setOf("active", "seasonOpen", "blockaded", "availableCapacity")

    fun initialMetaValue(topology: StrategicTopologySnapshot): Map<String, Any> = linkedMapOf(
        "version" to 1, "topologyRevision" to topology.topologyRevision, "topologyHash" to topology.contentHash,
        "edges" to topology.traversalEdges.filter(LandMarchMetricSnapshot::supports).sortedBy { it.id }
            .associateTo(linkedMapOf()) { edge ->
                edge.id to linkedMapOf<String, Any>(
                    "active" to (edge.seasonalAvailability == SeasonalAvailability.ALWAYS),
                    "seasonOpen" to false, "blockaded" to false, "availableCapacity" to edge.capacity)
            },
    )

    /** Missing authority stays missing; never synthesize an empty snapshot or discard unknown rows. */
    fun read(meta: Map<String, Any?>, topology: StrategicTopologySnapshot): StrategicEdgeStateSnapshot? {
        if (META_KEY !in meta) return null
        val raw = meta[META_KEY] as? Map<*, *> ?: invalid()
        require(raw.keys == fields && raw["version"] == 1) { "Invalid land passage schema" }
        require(raw["topologyRevision"] == topology.topologyRevision && raw["topologyHash"] == topology.contentHash) {
            "Land passage topology mismatch"
        }
        val edges = topology.traversalEdges.filter(LandMarchMetricSnapshot::supports).sortedBy { it.id }
        val rows = raw["edges"] as? Map<*, *> ?: invalid()
        require(rows.keys == edges.map { it.id }.toSet()) { "Land passage must cover exactly supported edges" }
        val states = edges.associateTo(linkedMapOf()) { edge ->
            val row = rows[edge.id] as? Map<*, *> ?: invalid()
            require(row.keys == edgeFields) { "Invalid land passage edge fields" }
            val active = row["active"] as? Boolean ?: invalid()
            val seasonOpen = row["seasonOpen"] as? Boolean ?: invalid()
            val blockaded = row["blockaded"] as? Boolean ?: invalid()
            val capacity = row["availableCapacity"] as? Int ?: invalid()
            require(capacity in 0..edge.capacity) { "Land passage capacity outside infrastructure bounds" }
            edge.id to StrategicEdgeState(active, seasonOpen, blockaded, capacity)
        }
        return StrategicEdgeStateSnapshot(topology.topologyRevision, topology.contentHash, states)
    }
    private fun invalid(): Nothing = throw IllegalArgumentException("Invalid HWIHA land passage state")
}
