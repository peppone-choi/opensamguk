package opensamguk.logic.world

import java.util.Collections

/** Millimetre-weighted path identity; never interchangeable with legacy movementCost totals. */
class ResolvedLandMarchPath internal constructor(
    nodeKeys: List<String>,
    edgeIds: List<String>,
    modes: List<TraversalMode>,
    val totalCostMm: Long,
    val capacity: Int,
    val topologyRevision: String,
    val topologyHash: String,
    val metricHash: String,
    val pathHash: String,
) {
    val nodeKeys: List<String> = Collections.unmodifiableList(ArrayList(nodeKeys))
    val edgeIds: List<String> = Collections.unmodifiableList(ArrayList(edgeIds))
    val modes: List<TraversalMode> = Collections.unmodifiableList(ArrayList(modes))
}

sealed interface LandMarchPathResult {
    data class Resolved(val path: ResolvedLandMarchPath) : LandMarchPathResult
    data class Denied(val code: PathDenialCode) : LandMarchPathResult
}
