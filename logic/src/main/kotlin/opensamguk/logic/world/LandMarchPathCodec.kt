package opensamguk.logic.world

/** Restores the chosen route, never replans against today's edge closures. */
object LandMarchPathCodec {
    private val keys = setOf("version", "nodeKeys", "edgeIds", "modes", "totalCostMm", "capacity",
        "topologyRevision", "topologyHash", "metricHash", "pathHash")

    fun toMetaValue(path: ResolvedLandMarchPath): Map<String, Any> = linkedMapOf(
        "version" to 1, "nodeKeys" to path.nodeKeys.toList(), "edgeIds" to path.edgeIds.toList(),
        "modes" to path.modes.map { it.name }, "totalCostMm" to path.totalCostMm, "capacity" to path.capacity,
        "topologyRevision" to path.topologyRevision, "topologyHash" to path.topologyHash,
        "metricHash" to path.metricHash, "pathHash" to path.pathHash)

    fun restore(raw: Any?, topology: StrategicTopologySnapshot, metrics: LandMarchMetricSnapshot): ResolvedLandMarchPath {
        val row = raw as? Map<*, *> ?: invalid()
        require(row.keys == keys) { "Unexpected land march path fields" }
        fun text(key: String) = row[key] as? String ?: invalid()
        fun strings(key: String) = (row[key] as? List<*>)?.map { it as? String ?: invalid() } ?: invalid()
        require(integer(row["version"]) == 1L) { "Unsupported land march path version" }
        val nodes = strings("nodeKeys"); val edges = strings("edgeIds")
        val modes = strings("modes").map { name -> TraversalMode.entries.find { it.name == name } ?: invalid() }
        val cost = integer(row["totalCostMm"])
        val capacityLong = integer(row["capacity"])
        require(cost >= 0 && capacityLong in 1..Int.MAX_VALUE.toLong()) { "Invalid march cost or capacity" }
        val capacity = capacityLong.toInt()
        require(text("topologyRevision") == topology.topologyRevision && text("topologyHash") == topology.contentHash &&
            metrics.topologyRevision == topology.topologyRevision && metrics.topologyHash == topology.contentHash &&
            text("metricHash") == metrics.contentHash) { "Stale land march pins" }
        require(nodes.size == edges.size + 1 && modes.size == edges.size && nodes.toSet().size == nodes.size) { "Invalid route shape or cycle" }
        val refs = nodes.map { key ->
            require(key.startsWith("land:") && key.removePrefix("land:") in topology.landProvinceIds) { "Unknown land march node" }
            StrategicNodeRef.LandProvince(key.removePrefix("land:"))
        }
        val byId = topology.traversalEdges.associateBy { it.id }
        var total = 0L
        var staticCapacity = Int.MAX_VALUE
        for ((i, id) in edges.withIndex()) {
            val edge = byId[id] ?: invalid()
            require(LandMarchMetricSnapshot.supports(edge) && modes[i] == edge.mode) { "Unsupported march edge" }
            require((edge.from == refs[i] && edge.to == refs[i + 1]) ||
                (!edge.directed && edge.to == refs[i] && edge.from == refs[i + 1])) { "Invalid march direction" }
            val metric = metrics.edgesById[id] ?: invalid()
            require(metric.costMm <= Long.MAX_VALUE - total) { "March total overflow" }
            total += metric.costMm
            staticCapacity = minOf(staticCapacity, edge.capacity)
        }
        // Capacity records the chosen route's then-live minimum; current live state belongs to advance().
        require(total == cost && capacity <= staticCapacity && (edges.isNotEmpty() || capacity == Int.MAX_VALUE)) { "March total or capacity mismatch" }
        val hash = landMarchPathHash(nodes, edges, modes, cost, capacity, topology.topologyRevision, topology.contentHash, metrics.contentHash)
        require(text("pathHash") == hash) { "March path hash mismatch" }
        return ResolvedLandMarchPath(nodes, edges, modes, cost, capacity, topology.topologyRevision,
            topology.contentHash, metrics.contentHash, hash)
    }
    private fun integer(raw: Any?): Long = when (raw) { is Int -> raw.toLong(); is Long -> raw; else -> invalid() }
    private fun invalid(): Nothing = throw IllegalArgumentException("Invalid land march path metadata")
}
