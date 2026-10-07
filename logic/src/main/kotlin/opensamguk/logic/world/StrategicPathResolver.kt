package opensamguk.logic.world

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import opensamguk.logic.world.StrategicPathSearch as SearchGraph
import opensamguk.logic.world.StrategicSearchOptions as SearchOptions
import opensamguk.logic.world.StrategicSearchState as SearchState
import java.util.Collections

data class StrategicEdgeState(
    val active: Boolean = true,
    val seasonOpen: Boolean = false,
    val blockaded: Boolean = false,
    val availableCapacity: Int? = null,
) {
    init {
        require(availableCapacity == null || availableCapacity >= 0) {
            "Available edge capacity must be non-negative"
        }
    }
}

class StrategicEdgeStateSnapshot(
    val topologyRevision: String,
    val topologyHash: String,
    edgeStates: Map<String, StrategicEdgeState>,
) {
    val edgeStates: Map<String, StrategicEdgeState> =
        Collections.unmodifiableMap(LinkedHashMap(edgeStates))
}

data class StrategicPathRequest(
    val from: StrategicNodeRef,
    val to: StrategicNodeRef,
    val requiredCapacity: Int,
) {
    init {
        require(requiredCapacity > 0) { "Required transport capacity must be positive" }
    }
}

enum class PathDenialCode {
    NO_LAND_CONNECTION,
    RIVER_CROSSING_REQUIRED,
    NO_EMBARK_POINT,
    NO_TRANSPORT_CAPACITY,
    WATERWAY_BLOCKED,
    TOPOLOGY_REVISION_STALE,
    TOPOLOGY_STATE_INVALID,
    UNKNOWN_NODE,
}

data class ResolvedStrategicPath(
    val nodeKeys: List<String>,
    val edgeIds: List<String>,
    val modes: List<TraversalMode>,
    val totalCost: Long,
    val capacity: Int,
    val topologyRevision: String,
    val topologyHash: String,
    val pathHash: String,
)

sealed interface StrategicPathResult {
    data class Resolved(val path: ResolvedStrategicPath) : StrategicPathResult
    data class Denied(val code: PathDenialCode) : StrategicPathResult
}

object StrategicPathResolver {

    /** Multi-source supply traversal shares the executable path graph, never diagnostic links. */
    fun reachableNodes(
        topology: StrategicTopologySnapshot,
        sources: Set<StrategicNodeRef>,
        state: StrategicEdgeStateSnapshot,
        requiredCapacity: Int,
        nodeAllowed: (StrategicNodeRef) -> Boolean,
        edgeAllowed: (TraversalEdge) -> Boolean,
    ): Set<StrategicNodeRef> {
        require(requiredCapacity > 0)
        require(state.topologyRevision == topology.topologyRevision && state.topologyHash == topology.contentHash) {
            "Supply topology state is stale"
        }
        val edgeIds = topology.traversalEdges.mapTo(hashSetOf(), TraversalEdge::id)
        require(state.edgeStates.keys.all { it in edgeIds }) { "Supply state contains unknown edges" }
        require(sources.all(topology::containsNode)) { "Supply source is outside topology" }
        return SearchGraph(topology, state, requiredCapacity).reachableNodes(sources, nodeAllowed, edgeAllowed)
    }

    fun resolve(
        topology: StrategicTopologySnapshot,
        request: StrategicPathRequest,
        state: StrategicEdgeStateSnapshot,
    ): StrategicPathResult {
        if (state.topologyRevision != topology.topologyRevision || state.topologyHash != topology.contentHash) {
            return StrategicPathResult.Denied(PathDenialCode.TOPOLOGY_REVISION_STALE)
        }
        val edgeIds = topology.traversalEdges.mapTo(hashSetOf(), TraversalEdge::id)
        if (state.edgeStates.keys.any { it !in edgeIds }) {
            return StrategicPathResult.Denied(PathDenialCode.TOPOLOGY_STATE_INVALID)
        }
        if (!topology.containsNode(request.from) || !topology.containsNode(request.to)) {
            return StrategicPathResult.Denied(PathDenialCode.UNKNOWN_NODE)
        }
        if (request.from == request.to) {
            return StrategicPathResult.Resolved(path(topology, listOf(request.from), emptyList(), Int.MAX_VALUE))
        }

        val graph = SearchGraph(topology, state, request.requiredCapacity)
        graph.findPath(request.from, request.to)?.let { found ->
            return StrategicPathResult.Resolved(path(topology, found.nodes, found.edges, found.capacity))
        }

        val denial = when {
            graph.findPath(request.from, request.to, SearchOptions(ignoreCapacity = true)) != null ->
                PathDenialCode.NO_TRANSPORT_CAPACITY
            graph.findPath(request.from, request.to, SearchOptions(ignoreWaterClosures = true)) != null ->
                PathDenialCode.WATERWAY_BLOCKED
            graph.findPath(request.from, request.to, SearchOptions(ignoreBarriers = true)) != null ->
                PathDenialCode.RIVER_CROSSING_REQUIRED
            request.to is StrategicNodeRef.WaterZone && !graph.hasReachableEmbark(request.from) ->
                PathDenialCode.NO_EMBARK_POINT
            else -> PathDenialCode.NO_LAND_CONNECTION
        }
        return StrategicPathResult.Denied(denial)
    }

    /** Physical land distance uses the same executable graph and filters as legacy traversal. */
    fun resolveLandMarch(
        topology: StrategicTopologySnapshot,
        request: StrategicPathRequest,
        state: StrategicEdgeStateSnapshot,
        metrics: LandMarchMetricSnapshot,
    ): LandMarchPathResult = resolveLandMarches(topology, listOf(request), state, metrics).single()

    /** Request-scoped searches reuse each origin/capacity frontier, never live state across requests. */
    fun resolveLandMarches(
        topology: StrategicTopologySnapshot,
        requests: List<StrategicPathRequest>,
        state: StrategicEdgeStateSnapshot,
        metrics: LandMarchMetricSnapshot,
    ): List<LandMarchPathResult> {
        if (requests.isEmpty()) return emptyList()
        val invalid = if (state.topologyRevision != topology.topologyRevision ||
            state.topologyHash != topology.contentHash || metrics.topologyRevision != topology.topologyRevision ||
            metrics.topologyHash != topology.contentHash) PathDenialCode.TOPOLOGY_REVISION_STALE else {
            val edgeIds = topology.traversalEdges.mapTo(hashSetOf(), TraversalEdge::id)
            if (state.edgeStates.keys.any { it !in edgeIds }) PathDenialCode.TOPOLOGY_STATE_INVALID else null
        }
        if (invalid != null) return requests.map { LandMarchPathResult.Denied(invalid) }
        data class SearchKey(val from: StrategicNodeRef, val capacity: Int,
            val options: SearchOptions, val physical: Boolean)
        val graphs = hashMapOf<Int, SearchGraph>()
        val searches = hashMapOf<SearchKey, SearchGraph.SearchCursor>()
        return requests.map { request ->
            resolveValidLandMarch(topology, request, metrics) { options, physical ->
                val key = SearchKey(request.from, request.requiredCapacity, options, physical)
                val cursor = searches.getOrPut(key) {
                    graphs.getOrPut(request.requiredCapacity) {
                        SearchGraph(topology, state, request.requiredCapacity)
                    }.searchFrom(request.from, options, LandMarchMetricSnapshot::supports,
                        if (physical) ({ edge -> metrics.edgesById.getValue(edge.id).costMm })
                        else ({ edge -> edge.movementCost.toLong() }))
                }
                cursor.findPath(request.to)
            }
        }
    }

    private fun resolveValidLandMarch(
        topology: StrategicTopologySnapshot,
        request: StrategicPathRequest,
        metrics: LandMarchMetricSnapshot,
        search: (SearchOptions, Boolean) -> SearchState?,
    ): LandMarchPathResult {
        if (!topology.containsNode(request.from) || !topology.containsNode(request.to))
            return LandMarchPathResult.Denied(PathDenialCode.UNKNOWN_NODE)
        if (request.from !is StrategicNodeRef.LandProvince || request.to !is StrategicNodeRef.LandProvince)
            return LandMarchPathResult.Denied(PathDenialCode.NO_LAND_CONNECTION)
        val found = try {
            search(SearchOptions(), true)
        } catch (_: ArithmeticException) {
            return LandMarchPathResult.Denied(PathDenialCode.TOPOLOGY_STATE_INVALID)
        }
        if (found != null) {
            val nodes = found.nodes.map(StrategicNodeRef::canonicalKey)
            val ids = found.edges.map(TraversalEdge::id)
            val modes = found.edges.map(TraversalEdge::mode)
            return LandMarchPathResult.Resolved(ResolvedLandMarchPath(nodes, ids, modes, found.cost,
                found.capacity, topology.topologyRevision, topology.contentHash, metrics.contentHash,
                landMarchPathHash(nodes, ids, modes, found.cost, found.capacity,
                    topology.topologyRevision, topology.contentHash, metrics.contentHash)))
        }
        // Diagnostic searches only classify failure. Their synthetic links never produce a march path.
        val denial = when {
            search(SearchOptions(ignoreCapacity = true), false) != null -> PathDenialCode.NO_TRANSPORT_CAPACITY
            search(SearchOptions(ignoreBarriers = true), false) != null -> PathDenialCode.RIVER_CROSSING_REQUIRED
            else -> PathDenialCode.NO_LAND_CONNECTION
        }
        return LandMarchPathResult.Denied(denial)
    }

    private fun path(
        topology: StrategicTopologySnapshot,
        nodes: List<StrategicNodeRef>,
        edges: List<TraversalEdge>,
        capacity: Int,
    ): ResolvedStrategicPath {
        val nodeKeys = nodes.map(StrategicNodeRef::canonicalKey)
        val edgeIds = edges.map(TraversalEdge::id)
        val modes = edges.map(TraversalEdge::mode)
        val totalCost = edges.fold(0L) { total, edge -> Math.addExact(total, edge.movementCost.toLong()) }
        val hashInput = CanonicalEncoding().apply {
            token(topology.topologyRevision)
            token(topology.contentHash)
            strings(nodeKeys)
            strings(edgeIds)
            strings(modes.map(TraversalMode::name))
            token(totalCost.toString())
            token(capacity.toString())
        }.toString()
        return ResolvedStrategicPath(
            nodeKeys = nodeKeys,
            edgeIds = edgeIds,
            modes = modes,
            totalCost = totalCost,
            capacity = capacity,
            topologyRevision = topology.topologyRevision,
            topologyHash = topology.contentHash,
            pathHash = sha256(hashInput),
        )
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private class CanonicalEncoding {
        private val value = StringBuilder()

        fun token(token: String) {
            value.append(token.toByteArray(StandardCharsets.UTF_8).size).append(':').append(token)
        }

        fun strings(tokens: List<String>) {
            token(tokens.size.toString())
            tokens.forEach(::token)
        }

        override fun toString(): String = value.toString()
    }
}
