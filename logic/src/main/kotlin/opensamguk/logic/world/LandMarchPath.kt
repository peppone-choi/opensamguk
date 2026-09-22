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

/** Shared with strict persisted-path restoration; encoding is unchanged from the resolver. */
internal fun landMarchPathHash(nodes: List<String>, edges: List<String>, modes: List<TraversalMode>,
    cost: Long, capacity: Int, revision: String, topologyHash: String, metricHash: String): String {
    val value = StringBuilder()
    fun token(text: String) { value.append(text.toByteArray(Charsets.UTF_8).size).append(':').append(text) }
    fun strings(items: List<String>) { token(items.size.toString()); items.forEach(::token) }
    token("land-march-mm-v1"); token(revision); token(topologyHash); token(metricHash)
    strings(nodes); strings(edges); strings(modes.map(TraversalMode::name))
    token(cost.toString()); token(capacity.toString())
    return java.security.MessageDigest.getInstance("SHA-256").digest(value.toString().toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
