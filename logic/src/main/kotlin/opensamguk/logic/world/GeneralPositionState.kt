package opensamguk.logic.world

import java.util.Collections

data class GeneralPositionState(
    val topologyRevision: String,
    val topologyHash: String,
    val generalId: Int,
    val node: StrategicNodeRef,
    val revision: Long,
) {
    init {
        validateGeneralPositionIdentity(topologyRevision, topologyHash, generalId)
        require(revision > 0) { "General position revision must be positive" }
    }
}

/** Validated explicit position input. General existence remains the recorder's responsibility. */
data class GeneralPositionAssessment(
    val topologyRevision: String,
    val topologyHash: String,
    val generalId: Int,
    val node: StrategicNodeRef,
) {
    init {
        validateGeneralPositionIdentity(topologyRevision, topologyHash, generalId)
    }
}

class GeneralPositionSnapshot(
    val topologyRevision: String,
    val topologyHash: String,
    knownLandProvinceIds: Set<String>,
    knownWaterZoneIds: Set<String>,
    states: List<GeneralPositionState> = emptyList(),
) {
    val knownLandProvinceIds: Set<String> = Collections.unmodifiableSet(LinkedHashSet(knownLandProvinceIds))
    val knownWaterZoneIds: Set<String> = Collections.unmodifiableSet(LinkedHashSet(knownWaterZoneIds))
    val statesByGeneralId: Map<Int, GeneralPositionState>

    init {
        require(topologyRevision.isNotBlank()) { "General positions require a topology revision" }
        require(topologyHash.matches(GENERAL_SHA_256_PATTERN)) {
            "General positions require a SHA-256 topology hash"
        }
        require(this.knownLandProvinceIds.none(String::isBlank)) { "Land province IDs must not be blank" }
        require(this.knownWaterZoneIds.none(String::isBlank)) { "Water zone IDs must not be blank" }
        require(states.map(GeneralPositionState::generalId).distinct().size == states.size) {
            "Duplicate general position rows"
        }
        states.forEach { state ->
            require(state.topologyRevision == topologyRevision && state.topologyHash == topologyHash) {
                "General position topology mismatch"
            }
            require(containsNode(state.node)) { "Unknown strategic node ${state.node.canonicalKey}" }
        }
        statesByGeneralId = Collections.unmodifiableMap(
            states.associateByTo(linkedMapOf(), GeneralPositionState::generalId),
        )
    }

    fun stateFor(generalId: Int): GeneralPositionState? = statesByGeneralId[generalId]

    fun withState(state: GeneralPositionState): GeneralPositionSnapshot = GeneralPositionSnapshot(
        topologyRevision,
        topologyHash,
        knownLandProvinceIds,
        knownWaterZoneIds,
        statesByGeneralId.values.filterNot { it.generalId == state.generalId } + state,
    )

    fun withoutGeneral(generalId: Int): GeneralPositionSnapshot = GeneralPositionSnapshot(
        topologyRevision,
        topologyHash,
        knownLandProvinceIds,
        knownWaterZoneIds,
        statesByGeneralId.values.filterNot { it.generalId == generalId },
    )

    private fun containsNode(node: StrategicNodeRef): Boolean = when (node) {
        is StrategicNodeRef.LandProvince -> node.id in knownLandProvinceIds
        is StrategicNodeRef.WaterZone -> node.id in knownWaterZoneIds
    }

    companion object {
        fun fromTopology(
            topology: StrategicTopologySnapshot,
            states: List<GeneralPositionState> = emptyList(),
        ): GeneralPositionSnapshot = GeneralPositionSnapshot(
            topology.topologyRevision,
            topology.contentHash,
            topology.landProvinceIds,
            topology.waterZones.mapTo(linkedSetOf(), WaterZoneRecord::id),
            states,
        )
    }
}

enum class GeneralPositionDenialCode {
    UNSUPPORTED_WORLD,
    TOPOLOGY_MISMATCH,
    UNKNOWN_NODE,
    UNKNOWN_GENERAL,
    STALE_REVISION,
    REVISION_EXHAUSTED,
}

sealed interface GeneralPositionChangeResult {
    data class Changed(val expectedRevision: Long?, val state: GeneralPositionState) : GeneralPositionChangeResult
    data class Unchanged(val state: GeneralPositionState) : GeneralPositionChangeResult
    data class Denied(val code: GeneralPositionDenialCode) : GeneralPositionChangeResult
}

/** Pure optimistic projection; persistence is responsible for applying the returned change. */
fun projectGeneralPosition(
    snapshot: GeneralPositionSnapshot,
    expectedRevision: Long?,
    assessment: GeneralPositionAssessment,
): GeneralPositionChangeResult {
    fun denied(code: GeneralPositionDenialCode) = GeneralPositionChangeResult.Denied(code)
    if (snapshot.topologyRevision != assessment.topologyRevision || snapshot.topologyHash != assessment.topologyHash) {
        return denied(GeneralPositionDenialCode.TOPOLOGY_MISMATCH)
    }
    val knownNode = when (val node = assessment.node) {
        is StrategicNodeRef.LandProvince -> node.id in snapshot.knownLandProvinceIds
        is StrategicNodeRef.WaterZone -> node.id in snapshot.knownWaterZoneIds
    }
    if (!knownNode) return denied(GeneralPositionDenialCode.UNKNOWN_NODE)
    val previous = snapshot.stateFor(assessment.generalId)
    if (expectedRevision != previous?.revision) return denied(GeneralPositionDenialCode.STALE_REVISION)
    if (previous != null && previous.node == assessment.node) {
        return GeneralPositionChangeResult.Unchanged(previous)
    }
    if (previous?.revision == Long.MAX_VALUE) return denied(GeneralPositionDenialCode.REVISION_EXHAUSTED)
    val nextRevision = (previous?.revision ?: 0L) + 1L
    return GeneralPositionChangeResult.Changed(
        expectedRevision,
        GeneralPositionState(
            assessment.topologyRevision,
            assessment.topologyHash,
            assessment.generalId,
            assessment.node,
            nextRevision,
        ),
    )
}

private val GENERAL_SHA_256_PATTERN = Regex("[0-9a-f]{64}")

private fun validateGeneralPositionIdentity(revision: String, hash: String, generalId: Int) {
    require(revision.isNotBlank()) { "General position requires an exact topology revision" }
    require(hash.matches(GENERAL_SHA_256_PATTERN)) { "General position requires a SHA-256 topology hash" }
    require(generalId > 0) { "General ID must be positive" }
}
