package opensamguk.logic.input

import opensamguk.logic.world.*

/** Personal MOVE selects one executable edge, never a cheaper indirect route. */
internal object PersonalMovePath {
    fun from(origin: StrategicNodeRef.LandProvince, topology: StrategicTopologySnapshot,
        metrics: LandMarchMetricSnapshot, passage: StrategicEdgeStateSnapshot): Map<String, ResolvedLandMarchPath> {
        require(passage.topologyRevision == topology.topologyRevision && passage.topologyHash == topology.contentHash)
        require(metrics.topologyRevision == topology.topologyRevision && metrics.topologyHash == topology.contentHash)
        require(topology.containsNode(origin))
        val edgeIds = topology.traversalEdges.mapTo(hashSetOf(), TraversalEdge::id)
        require(passage.edgeStates.keys.all { it in edgeIds })
        val graph = StrategicPathSearch(topology, passage, 1)
        val paths = linkedMapOf<String, ResolvedLandMarchPath>()
        for (edge in topology.traversalEdges.filter(LandMarchMetricSnapshot::supports).sortedBy { it.id }) {
            val destination = when {
                edge.from == origin -> edge.to
                !edge.directed && edge.to == origin -> edge.from
                else -> continue
            } as StrategicNodeRef.LandProvince
            // The shared graph checks closures, seasons, capacity and reviewed river crossings.
            if (destination !in graph.reachableNodes(setOf(origin),
                    { it == origin || it == destination }, { it.id == edge.id })) continue
            val cost = metrics.edgesById.getValue(edge.id).costMm
            val previous = paths[destination.id]
            if (previous != null && previous.totalCostMm <= cost) continue
            val nodes = listOf(origin.canonicalKey, destination.canonicalKey)
            val ids = listOf(edge.id)
            val modes = listOf(edge.mode)
            val capacity = minOf(edge.capacity, passage.edgeStates[edge.id]?.availableCapacity ?: edge.capacity)
            paths[destination.id] = ResolvedLandMarchPath(nodes, ids, modes, cost, capacity,
                topology.topologyRevision, topology.contentHash, metrics.contentHash,
                landMarchPathHash(nodes, ids, modes, cost, capacity, topology.topologyRevision,
                    topology.contentHash, metrics.contentHash))
        }
        return paths
    }
}
