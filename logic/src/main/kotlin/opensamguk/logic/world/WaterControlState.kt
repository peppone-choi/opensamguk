package opensamguk.logic.world

import java.util.Collections

enum class WaterBlockadeState { OPEN, CONTESTED, BLOCKED }

/** Campaign-owned state, never scenario/shore ownership. Missing rows mean unknown, not OPEN. */
class WaterControlState(
    val topologyRevision: String,
    val topologyHash: String,
    val waterZoneId: String,
    val controllingNationId: Long?,
    contestingNationIds: List<Long>,
    val blockadeState: WaterBlockadeState,
    val revision: Long,
) {
    val contestingNationIds: List<Long> = Collections.unmodifiableList(ArrayList(contestingNationIds))

    init {
        validateControlIdentity(topologyRevision, topologyHash, waterZoneId, controllingNationId, this.contestingNationIds)
        require(this.contestingNationIds == this.contestingNationIds.distinct().sorted()) {
            "Persisted water control contesting nation IDs must be sorted and unique"
        }
        require(revision > 0) { "Water control revision must be positive" }
    }

    override fun equals(other: Any?): Boolean = other is WaterControlState &&
        topologyRevision == other.topologyRevision && topologyHash == other.topologyHash &&
        waterZoneId == other.waterZoneId && controllingNationId == other.controllingNationId &&
        contestingNationIds == other.contestingNationIds && blockadeState == other.blockadeState && revision == other.revision

    override fun hashCode(): Int = listOf(topologyRevision, topologyHash, waterZoneId, controllingNationId,
        contestingNationIds, blockadeState, revision).hashCode()
}

/** Validated explicit input. This normalizes supplied IDs, but infers no control from land or fleets. */
class WaterControlAssessment(
    val topologyRevision: String,
    val topologyHash: String,
    val waterZoneId: String,
    val controllingNationId: Long?,
    contestingNationIds: List<Long>,
    val blockadeState: WaterBlockadeState,
) {
    val contestingNationIds: List<Long> = Collections.unmodifiableList(contestingNationIds.distinct().sorted())

    init {
        validateControlIdentity(topologyRevision, topologyHash, waterZoneId, controllingNationId, this.contestingNationIds)
    }
}

class WaterControlSnapshot(
    val topologyRevision: String,
    val topologyHash: String,
    knownWaterZoneIds: Set<String>,
    states: List<WaterControlState> = emptyList(),
) {
    val knownWaterZoneIds: Set<String> = Collections.unmodifiableSet(LinkedHashSet(knownWaterZoneIds))
    val statesByZoneId: Map<String, WaterControlState>

    init {
        require(topologyRevision.isNotBlank()) { "Water control requires a topology revision" }
        require(topologyHash.matches(Regex("[0-9a-f]{64}"))) { "Water control requires a SHA-256 topology hash" }
        require(this.knownWaterZoneIds.none(String::isBlank)) { "Water zone IDs must not be blank" }
        require(states.map { it.waterZoneId }.distinct().size == states.size) { "Duplicate water control rows" }
        states.forEach {
            require(it.topologyRevision == topologyRevision && it.topologyHash == topologyHash) { "Water control topology mismatch" }
            require(it.waterZoneId in this.knownWaterZoneIds) { "Unknown water zone ${it.waterZoneId}" }
        }
        statesByZoneId = Collections.unmodifiableMap(states.associateByTo(linkedMapOf()) { it.waterZoneId })
    }

    fun stateFor(waterZoneId: String): WaterControlState? = statesByZoneId[waterZoneId]

    fun withState(state: WaterControlState): WaterControlSnapshot = WaterControlSnapshot(
        topologyRevision, topologyHash, knownWaterZoneIds,
        statesByZoneId.values.filterNot { it.waterZoneId == state.waterZoneId } + state,
    )

    companion object {
        fun fromTopology(topology: StrategicTopologySnapshot, states: List<WaterControlState> = emptyList()) =
            WaterControlSnapshot(topology.topologyRevision, topology.contentHash,
                topology.waterZones.mapTo(linkedSetOf()) { it.id }, states)
    }
}

enum class WaterControlDenialCode { UNSUPPORTED_WORLD, TOPOLOGY_MISMATCH, UNKNOWN_ZONE, STALE_REVISION, REVISION_EXHAUSTED }

sealed interface WaterControlChangeResult {
    data class Changed(val expectedRevision: Long?, val state: WaterControlState) : WaterControlChangeResult
    data class Unchanged(val state: WaterControlState) : WaterControlChangeResult
    data class Denied(val code: WaterControlDenialCode) : WaterControlChangeResult
}

/** Pure optimistic projection; the engine recorder is the only component that applies it. */
fun projectWaterControl(
    snapshot: WaterControlSnapshot,
    expectedRevision: Long?,
    assessment: WaterControlAssessment,
): WaterControlChangeResult {
    fun denied(code: WaterControlDenialCode) = WaterControlChangeResult.Denied(code)
    if (snapshot.topologyRevision != assessment.topologyRevision || snapshot.topologyHash != assessment.topologyHash) {
        return denied(WaterControlDenialCode.TOPOLOGY_MISMATCH)
    }
    if (assessment.waterZoneId !in snapshot.knownWaterZoneIds) return denied(WaterControlDenialCode.UNKNOWN_ZONE)
    val previous = snapshot.stateFor(assessment.waterZoneId)
    if (expectedRevision != previous?.revision) return denied(WaterControlDenialCode.STALE_REVISION)
    if (previous != null && previous.controllingNationId == assessment.controllingNationId &&
        previous.contestingNationIds == assessment.contestingNationIds && previous.blockadeState == assessment.blockadeState
    ) return WaterControlChangeResult.Unchanged(previous)
    if (previous?.revision == Long.MAX_VALUE) return denied(WaterControlDenialCode.REVISION_EXHAUSTED)
    return WaterControlChangeResult.Changed(expectedRevision, WaterControlState(
        assessment.topologyRevision, assessment.topologyHash, assessment.waterZoneId,
        assessment.controllingNationId, assessment.contestingNationIds, assessment.blockadeState,
        (previous?.revision ?: 0L) + 1L,
    ))
}

private fun validateControlIdentity(revision: String, hash: String, zone: String, controller: Long?, contestants: List<Long>) {
    require(revision.isNotBlank() && zone.isNotBlank()) { "Water control requires exact topology and zone IDs" }
    require(hash.matches(Regex("[0-9a-f]{64}"))) { "Water control requires a SHA-256 topology hash" }
    require(controller == null || controller > 0) { "Controlling nation ID must be positive" }
    require(contestants.all { it > 0 }) { "Contesting nation IDs must be positive" }
}
