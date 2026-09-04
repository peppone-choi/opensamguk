package opensamguk.logic.world

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Collections
import java.util.PriorityQueue

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

    private data class SearchOptions(
        val ignoreCapacity: Boolean = false,
        val ignoreWaterClosures: Boolean = false,
        val ignoreBarriers: Boolean = false,
    )

    private class SearchGraph(
        private val topology: StrategicTopologySnapshot,
        private val state: StrategicEdgeStateSnapshot,
        private val requiredCapacity: Int,
    ) {
        private val barrierKeys = topology.riverBarriers.mapTo(hashSetOf()) { it.canonicalBoundaryKey }
        private val adjacency: Map<String, List<Step>> = run {
            val mutable = linkedMapOf<String, MutableList<Step>>()
            topology.traversalEdges.sortedBy(TraversalEdge::id).forEach { edge ->
                mutable.getOrPut(edge.from.canonicalKey) { mutableListOf() }.add(Step(edge, edge.to))
                if (!edge.directed && edge.mode.hasSymmetricEndpoints()) {
                    mutable.getOrPut(edge.to.canonicalKey) { mutableListOf() }.add(Step(edge, edge.from))
                }
            }
            mutable.values.forEach { steps -> steps.sortBy { it.edge.id } }
            mutable.mapValues { (_, steps) -> steps.toList() }
        }

        fun findPath(
            from: StrategicNodeRef,
            to: StrategicNodeRef,
            options: SearchOptions = SearchOptions(),
        ): SearchState? {
            val queue = PriorityQueue<SearchState> { first, second ->
                compareValues(first.cost, second.cost)
                    .takeIf { it != 0 }
                    ?: compareEdgeIdSequences(first.edgeIds, second.edgeIds)
                        .takeIf { it != 0 }
                    ?: first.node.canonicalKey.compareTo(second.node.canonicalKey)
            }
            queue += SearchState(from, 0L, emptyList(), listOf(from), Int.MAX_VALUE)
            val best = hashMapOf<String, Best>()

            while (queue.isNotEmpty()) {
                val current = queue.remove()
                val previous = best[current.node.canonicalKey]
                if (previous != null && !current.isBetterThan(previous)) continue
                best[current.node.canonicalKey] = Best(current.cost, current.edgeIds)
                if (current.node == to) return current

                for (step in adjacency[current.node.canonicalKey].orEmpty()) {
                    if (!isUsable(step.edge, options)) continue
                    val available = availableCapacity(step.edge)
                    val candidate = SearchState(
                        node = step.to,
                        cost = Math.addExact(current.cost, step.edge.movementCost.toLong()),
                        edges = current.edges + step.edge,
                        nodes = current.nodes + step.to,
                        capacity = minOf(current.capacity, available),
                    )
                    val known = best[step.to.canonicalKey]
                    if (known == null || candidate.isBetterThan(known)) queue += candidate
                }
            }
            return null
        }

        fun hasReachableEmbark(from: StrategicNodeRef): Boolean {
            if (from !is StrategicNodeRef.LandProvince) return true
            val reachableLand = linkedSetOf(from.canonicalKey)
            val queue = ArrayDeque<StrategicNodeRef>()
            queue += from
            while (queue.isNotEmpty()) {
                val current = queue.removeFirst()
                for (step in adjacency[current.canonicalKey].orEmpty()) {
                    if (step.to !is StrategicNodeRef.LandProvince || step.edge.mode == TraversalMode.EMBARK) continue
                    if (!isUsable(step.edge, SearchOptions())) continue
                    if (reachableLand.add(step.to.canonicalKey)) queue += step.to
                }
            }
            return topology.traversalEdges.any { edge ->
                edge.mode == TraversalMode.EMBARK && edge.from.canonicalKey in reachableLand &&
                    isUsable(edge, SearchOptions())
            }
        }

        private fun isUsable(edge: TraversalEdge, options: SearchOptions): Boolean {
            if (!options.ignoreBarriers && edge.mode == TraversalMode.LAND && edge.crossesBarrier(barrierKeys)) {
                return false
            }
            val live = state.edgeStates[edge.id] ?: StrategicEdgeState()
            if (!live.active) return false
            val seasonClosed = edge.seasonalAvailability == SeasonalAvailability.CLOSED ||
                (edge.seasonalAvailability == SeasonalAvailability.SEASONAL && !live.seasonOpen)
            if ((seasonClosed || live.blockaded) &&
                !(options.ignoreWaterClosures && edge.mode.isWaterTraversal())
            ) {
                return false
            }
            return options.ignoreCapacity || availableCapacity(edge) >= requiredCapacity
        }

        private fun availableCapacity(edge: TraversalEdge): Int {
            val live = state.edgeStates[edge.id]
            return minOf(edge.capacity, live?.availableCapacity ?: edge.capacity)
        }
    }

    private data class Step(val edge: TraversalEdge, val to: StrategicNodeRef)
    private data class Best(val cost: Long, val edgeIds: List<String>)

    private data class SearchState(
        val node: StrategicNodeRef,
        val cost: Long,
        val edges: List<TraversalEdge>,
        val nodes: List<StrategicNodeRef>,
        val capacity: Int,
    ) {
        val edgeIds: List<String> = edges.map(TraversalEdge::id)

        fun isBetterThan(other: Best): Boolean =
            cost < other.cost || (cost == other.cost && compareEdgeIdSequences(edgeIds, other.edgeIds) < 0)
    }

    private fun TraversalMode.isWaterTraversal(): Boolean = this != TraversalMode.LAND &&
        this != TraversalMode.FORD && this != TraversalMode.BRIDGE

    private fun TraversalEdge.crossesBarrier(barrierKeys: Set<String>): Boolean {
        val fromLand = from as? StrategicNodeRef.LandProvince ?: return false
        val toLand = to as? StrategicNodeRef.LandProvince ?: return false
        return boundaryKey(fromLand.id, toLand.id) in barrierKeys
    }

    private fun boundaryKey(a: Int, b: Int): String = listOf(a, b).sorted().joinToString(":")

    private fun compareEdgeIdSequences(first: List<String>, second: List<String>): Int {
        val shared = minOf(first.size, second.size)
        for (index in 0 until shared) {
            val compared = first[index].compareTo(second[index])
            if (compared != 0) return compared
        }
        return first.size.compareTo(second.size)
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
