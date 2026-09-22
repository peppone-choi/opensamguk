package opensamguk.logic.world

import java.security.MessageDigest
import java.util.Collections

/** Distances are millimetres, not the legacy traversal movementCost unit. */
data class LandMarchEdgeMetric(val edgeId: String, val distanceMm: Long, val costMm: Long) {
    init {
        require(edgeId.isNotBlank())
        require(distanceMm > 0 && costMm >= distanceMm)
    }
}

/** Immutable costs for precisely the land crossings in a pinned topology. No new adjacency is created. */
class LandMarchMetricSnapshot(
    topology: StrategicTopologySnapshot,
    val tilesHash: String,
    metrics: List<LandMarchEdgeMetric>,
) {
    val topologyRevision = topology.topologyRevision
    val topologyHash = topology.contentHash
    val policyVersion = "province-centroid-rough-3-over-2-mm-v1"
    val edgesById: Map<String, LandMarchEdgeMetric>
    val contentHash: String

    init {
        require(tilesHash.matches(Regex("[0-9a-f]{64}"))) { "March metrics require a tiles SHA-256" }
        require(topology.artifactHashes[TILES_PATH] == tilesHash) { "March tiles differ from topology pin" }
        val expected = topology.traversalEdges.filter(::supports).mapTo(sortedSetOf()) { it.id }
        require(metrics.map { it.edgeId }.distinct().size == metrics.size) { "Duplicate march edge metric" }
        require(metrics.mapTo(sortedSetOf()) { it.edgeId } == expected) { "March metrics must cover exactly supported topology edges" }
        edgesById = Collections.unmodifiableMap(metrics.sortedBy { it.edgeId }.associateByTo(linkedMapOf()) { it.edgeId })
        val digest = MessageDigest.getInstance("SHA-256")
        fun token(value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            digest.update(bytes.size.toString().toByteArray(Charsets.UTF_8))
            digest.update(':'.code.toByte()); digest.update(bytes)
        }
        listOf(policyVersion, topologyRevision, topologyHash, tilesHash).forEach(::token)
        edgesById.values.forEach { token(it.edgeId); token(it.distanceMm.toString()); token(it.costMm.toString()) }
        contentHash = digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val TILES_PATH = "data/map/han-tiles.json"
        const val NORMAL_BUDGET_MM = 30_000_000L
        /** Ferries and water legs require their own transport policy; no implicit land fallback. */
        fun supports(edge: TraversalEdge): Boolean = edge.mode in setOf(TraversalMode.LAND, TraversalMode.FORD, TraversalMode.BRIDGE) &&
            edge.from is StrategicNodeRef.LandProvince && edge.to is StrategicNodeRef.LandProvince
    }
}
