package opensamguk.logic.world

import java.util.Collections
import java.util.PriorityQueue

internal data class StrategicSearchOptions(
    val ignoreCapacity: Boolean = false,
    val ignoreWaterClosures: Boolean = false,
    val ignoreBarriers: Boolean = false,
)

internal class StrategicPathSearch(
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
        // Diagnostic-only links explain a missing reviewed crossing even when the dry-land
        // projection correctly omits that boundary. They are never executable path edges.
        topology.riverBarriers.sortedBy(RiverBarrier::id).forEach { barrier ->
            val first = StrategicNodeRef.LandProvince(barrier.firstLandProvinceId)
            val second = StrategicNodeRef.LandProvince(barrier.secondLandProvinceId)
            val diagnostic = TraversalEdge(
                "diagnostic-barrier:${barrier.id}", first, second, TraversalMode.LAND,
                false, 1, Int.MAX_VALUE, RiskBand.LOW, SeasonalAvailability.ALWAYS,
                false, barrier.sourceRefs, barrier.confidence,
            )
            mutable.getOrPut(first.canonicalKey) { mutableListOf() }.add(Step(diagnostic, second, true))
            mutable.getOrPut(second.canonicalKey) { mutableListOf() }.add(Step(diagnostic, first, true))
        }
        mutable.values.forEach { steps -> steps.sortBy { it.edge.id } }
        mutable.mapValues { (_, steps) -> steps.toList() }
    }

    fun reachableNodes(
        sources: Set<StrategicNodeRef>,
        nodeAllowed: (StrategicNodeRef) -> Boolean,
        edgeAllowed: (TraversalEdge) -> Boolean,
    ): Set<StrategicNodeRef> {
        val reached = linkedSetOf<StrategicNodeRef>()
        val queue = ArrayDeque<StrategicNodeRef>()
        sources.sortedBy { it.canonicalKey }.filter(nodeAllowed).forEach {
            if (reached.add(it)) queue += it
        }
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            for (step in adjacency[current.canonicalKey].orEmpty()) {
                if (step.diagnosticOnly || !edgeAllowed(step.edge) || !nodeAllowed(step.to)) continue
                if (!isUsable(step.edge, StrategicSearchOptions())) continue
                if (reached.add(step.to)) queue += step.to
            }
        }
        return Collections.unmodifiableSet(reached)
    }

    fun findPath(
        from: StrategicNodeRef,
        to: StrategicNodeRef,
        options: StrategicSearchOptions = StrategicSearchOptions(),
        edgeAllowed: (TraversalEdge) -> Boolean = { true },
        edgeCost: (TraversalEdge) -> Long = { it.movementCost.toLong() },
    ): StrategicSearchState? = searchFrom(from, options, edgeAllowed, edgeCost).findPath(to)

    fun searchFrom(
        from: StrategicNodeRef,
        options: StrategicSearchOptions,
        edgeAllowed: (TraversalEdge) -> Boolean,
        edgeCost: (TraversalEdge) -> Long,
    ) = SearchCursor(from, options, edgeAllowed, edgeCost)

    inner class SearchCursor(
        from: StrategicNodeRef,
        private val options: StrategicSearchOptions,
        private val edgeAllowed: (TraversalEdge) -> Boolean,
        private val edgeCost: (TraversalEdge) -> Long,
    ) {
        private val queue = PriorityQueue<StrategicSearchState> { first, second ->
            compareValues(first.cost, second.cost)
                .takeIf { it != 0 }
                ?: compareEdgeIdSequences(first.edgeIds, second.edgeIds)
                    .takeIf { it != 0 }
                ?: first.node.canonicalKey.compareTo(second.node.canonicalKey)
        }
        private val best = hashMapOf<String, StrategicSearchState>()
        private val settled = hashMapOf<String, StrategicSearchState>()
        private var pendingExpansion: StrategicSearchState? = null
        private var overflow: ArithmeticException? = null

        init {
            val origin = StrategicSearchState(from, 0L, emptyList(), listOf(from), Int.MAX_VALUE)
            queue += origin
            best[from.canonicalKey] = origin
        }

        fun findPath(to: StrategicNodeRef): StrategicSearchState? {
            // A later overflow cannot invalidate a destination the original early-return search reached.
            settled[to.canonicalKey]?.let { return it }
            overflow?.let { throw it }
            try {
                expandPending()
                while (queue.isNotEmpty()) {
                    val current = queue.remove()
                    if (best[current.node.canonicalKey] !== current) continue
                    settled[current.node.canonicalKey] = current
                    pendingExpansion = current
                    // Defer this expansion until another destination needs the remaining frontier.
                    if (current.node == to) return current
                    expandPending()
                }
                return null
            } catch (error: ArithmeticException) {
                overflow = error
                throw error
            }
        }

        private fun expandPending() {
            val current = pendingExpansion ?: return
            for (step in adjacency[current.node.canonicalKey].orEmpty()) {
                if (step.diagnosticOnly && !options.ignoreBarriers) continue
                if (!edgeAllowed(step.edge) || !isUsable(step.edge, options)) continue
                // Keep the original overflow check even for an already dominated return candidate.
                val cost = Math.addExact(current.cost, edgeCost(step.edge))
                val known = best[step.to.canonicalKey]
                if (known != null && cost > known.cost) continue
                val candidate = StrategicSearchState(step.to, cost, current.edges + step.edge,
                    current.nodes + step.to, minOf(current.capacity, availableCapacity(step.edge)))
                if (known == null || candidate.isBetterThan(Best(known.cost, known.edgeIds))) {
                    best[step.to.canonicalKey] = candidate
                    queue += candidate
                }
            }
            pendingExpansion = null
        }
    }

    fun hasReachableEmbark(from: StrategicNodeRef): Boolean {
        if (from !is StrategicNodeRef.LandProvince) return true
        val reachableLand = linkedSetOf(from.canonicalKey)
        val queue = ArrayDeque<StrategicNodeRef>()
        queue += from
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            for (step in adjacency[current.canonicalKey].orEmpty()) {
                if (step.diagnosticOnly) continue
                if (step.to !is StrategicNodeRef.LandProvince || step.edge.mode == TraversalMode.EMBARK) continue
                if (!isUsable(step.edge, StrategicSearchOptions())) continue
                if (reachableLand.add(step.to.canonicalKey)) queue += step.to
            }
        }
        return topology.traversalEdges.any { edge ->
            edge.mode == TraversalMode.EMBARK && edge.from.canonicalKey in reachableLand &&
                isUsable(edge, StrategicSearchOptions())
        }
    }

    private fun isUsable(edge: TraversalEdge, options: StrategicSearchOptions): Boolean {
        if (!options.ignoreBarriers && edge.mode == TraversalMode.LAND && edge.crossesBarrier(barrierKeys)) {
            return false
        }
        val live = state.edgeStates[edge.id] ?: StrategicEdgeState(active = edge.initiallyOpen)
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

private data class Step(val edge: TraversalEdge, val to: StrategicNodeRef, val diagnosticOnly: Boolean = false)
private data class Best(val cost: Long, val edgeIds: List<String>)

internal data class StrategicSearchState(
    val node: StrategicNodeRef,
    val cost: Long,
    val edges: List<TraversalEdge>,
    val nodes: List<StrategicNodeRef>,
    val capacity: Int,
) {
    val edgeIds: List<String> = edges.map(TraversalEdge::id)

}

private fun TraversalMode.isWaterTraversal(): Boolean = this != TraversalMode.LAND &&
    this != TraversalMode.FORD && this != TraversalMode.BRIDGE

private fun TraversalEdge.crossesBarrier(barrierKeys: Set<String>): Boolean {
    val fromLand = from as? StrategicNodeRef.LandProvince ?: return false
    val toLand = to as? StrategicNodeRef.LandProvince ?: return false
    return strategicLandBoundaryKey(fromLand.id, toLand.id) in barrierKeys
}

private fun compareEdgeIdSequences(first: List<String>, second: List<String>): Int {
    val shared = minOf(first.size, second.size)
    for (index in 0 until shared) {
        val compared = first[index].compareTo(second[index])
        if (compared != 0) return compared
    }
    return first.size.compareTo(second.size)
}

private fun StrategicSearchState.isBetterThan(other: Best): Boolean =
    cost < other.cost || (cost == other.cost && compareEdgeIdSequences(edgeIds, other.edgeIds) < 0)
