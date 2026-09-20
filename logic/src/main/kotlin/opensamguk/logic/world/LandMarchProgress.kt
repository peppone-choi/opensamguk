package opensamguk.logic.world

import java.util.Collections

/** Position remains the last reached node while the next edge is only partly paid. */
data class LandMarchCursor(val pathHash: String, val edgeIndex: Int = 0, val paidMm: Long = 0) {
    init {
        require(pathHash.matches(Regex("[0-9a-f]{64}")))
        require(edgeIndex >= 0 && paidMm >= 0)
    }
}

enum class LandMarchStop { BUDGET_EXHAUSTED, EDGE_BLOCKED, ENCOUNTER_UNAVAILABLE, ENCOUNTER, ARRIVED }
enum class LandMarchEntry { CLEAR, ENCOUNTER, UNAVAILABLE }
enum class LandMarchRejection { STALE_PIN, INVALID_ROUTE, INVALID_CURSOR, POSITION_MISMATCH }

sealed interface LandMarchAdvance {
    data class Rejected(val reason: LandMarchRejection) : LandMarchAdvance
    class Advanced internal constructor(
        val cursor: LandMarchCursor,
        reached: List<StrategicNodeRef.LandProvince>,
        val spentMm: Long,
        val unusedMm: Long,
        val stop: LandMarchStop,
    ) : LandMarchAdvance {
        val reachedNodes: List<StrategicNodeRef.LandProvince> = Collections.unmodifiableList(ArrayList(reached))
    }
}

/** Pure one-turn progress. Encounter knowledge is mandatory; this does not resolve combat. */
object LandMarchProgress {
    fun advance(
        topology: StrategicTopologySnapshot,
        metrics: LandMarchMetricSnapshot,
        state: StrategicEdgeStateSnapshot,
        path: ResolvedLandMarchPath,
        cursor: LandMarchCursor,
        currentPosition: StrategicNodeRef,
        requiredCapacity: Int,
        budgetMm: Long,
        entryAt: (StrategicNodeRef.LandProvince) -> LandMarchEntry,
    ): LandMarchAdvance {
        require(requiredCapacity > 0 && budgetMm >= 0)
        fun reject(reason: LandMarchRejection) = LandMarchAdvance.Rejected(reason)
        if (path.topologyRevision != topology.topologyRevision || path.topologyHash != topology.contentHash ||
            metrics.topologyRevision != topology.topologyRevision || metrics.topologyHash != topology.contentHash ||
            state.topologyRevision != topology.topologyRevision || state.topologyHash != topology.contentHash ||
            path.metricHash != metrics.contentHash) return reject(LandMarchRejection.STALE_PIN)
        val byId = topology.traversalEdges.associateBy { it.id }
        if (state.edgeStates.keys.any { it !in byId } || path.nodeKeys.size != path.edgeIds.size + 1 ||
            path.modes.size != path.edgeIds.size || path.nodeKeys.any { !it.startsWith("land:") || it.removePrefix("land:").isBlank() })
            return reject(LandMarchRejection.INVALID_ROUTE)
        val nodes = path.nodeKeys.map { StrategicNodeRef.LandProvince(it.removePrefix("land:")) }
        if (nodes.any { !topology.containsNode(it) }) return reject(LandMarchRejection.INVALID_ROUTE)
        var total = 0L
        for ((index, id) in path.edgeIds.withIndex()) {
            val edge = byId[id] ?: return reject(LandMarchRejection.INVALID_ROUTE)
            val cost = metrics.edgesById[id]?.costMm ?: return reject(LandMarchRejection.INVALID_ROUTE)
            val forward = edge.from == nodes[index] && edge.to == nodes[index + 1]
            val backward = !edge.directed && edge.to == nodes[index] && edge.from == nodes[index + 1]
            if (!LandMarchMetricSnapshot.supports(edge) || path.modes[index] != edge.mode || (!forward && !backward))
                return reject(LandMarchRejection.INVALID_ROUTE)
            if (cost > Long.MAX_VALUE - total) return reject(LandMarchRejection.INVALID_ROUTE)
            total += cost
        }
        if (total != path.totalCostMm) return reject(LandMarchRejection.INVALID_ROUTE)
        if (cursor.pathHash != path.pathHash || cursor.edgeIndex > path.edgeIds.size ||
            (cursor.edgeIndex == path.edgeIds.size && cursor.paidMm != 0L) ||
            (cursor.edgeIndex < path.edgeIds.size && cursor.paidMm >= metrics.edgesById.getValue(path.edgeIds[cursor.edgeIndex]).costMm))
            return reject(LandMarchRejection.INVALID_CURSOR)
        if (currentPosition != nodes[cursor.edgeIndex]) return reject(LandMarchRejection.POSITION_MISMATCH)
        var index = cursor.edgeIndex
        var paid = cursor.paidMm
        var remaining = budgetMm
        val reached = mutableListOf<StrategicNodeRef.LandProvince>()
        fun finish(stop: LandMarchStop) = LandMarchAdvance.Advanced(
            LandMarchCursor(path.pathHash, index, paid), reached, budgetMm - remaining, remaining, stop)
        while (index < path.edgeIds.size && remaining > 0) {
            val id = path.edgeIds[index]
            val from = nodes[index]
            val to = nodes[index + 1]
            // The exact edge is rechecked through the existing executable graph; no detour substitutes for it.
            val reachable = StrategicPathResolver.reachableNodes(topology, setOf(from), state, requiredCapacity,
                nodeAllowed = { it == from || it == to }, edgeAllowed = { it.id == id })
            if (to !in reachable) return finish(LandMarchStop.EDGE_BLOCKED)
            val entry = entryAt(to)
            if (entry == LandMarchEntry.UNAVAILABLE) return finish(LandMarchStop.ENCOUNTER_UNAVAILABLE)
            val unpaid = metrics.edgesById.getValue(id).costMm - paid
            val spent = minOf(remaining, unpaid)
            remaining -= spent
            paid += spent
            if (spent < unpaid) return finish(LandMarchStop.BUDGET_EXHAUSTED)
            index++
            paid = 0
            reached += to
            if (entry == LandMarchEntry.ENCOUNTER) return finish(LandMarchStop.ENCOUNTER)
        }
        return finish(if (index == path.edgeIds.size) LandMarchStop.ARRIVED else LandMarchStop.BUDGET_EXHAUSTED)
    }
}
