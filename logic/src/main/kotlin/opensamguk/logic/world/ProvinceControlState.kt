package opensamguk.logic.world

import java.util.Collections

/** Campaign-owned province control. Missing rows mean unknown, not neutral control. */
data class ProvinceControlState(
    val topologyRevision: String,
    val topologyHash: String,
    val provinceId: String,
    val nationId: Int,
    val revision: Long,
) {
    init {
        validateProvinceControlIdentity(topologyRevision, topologyHash, provinceId, nationId)
        require(revision > 0) { "Province control revision must be positive" }
    }
}

/** Validated explicit control input; nation zero is neutral control rather than a missing row. */
data class ProvinceControlAssessment(
    val topologyRevision: String,
    val topologyHash: String,
    val provinceId: String,
    val nationId: Int,
) {
    init {
        validateProvinceControlIdentity(topologyRevision, topologyHash, provinceId, nationId)
    }
}

class ProvinceControlSnapshot(
    val topologyRevision: String,
    val topologyHash: String,
    knownProvinceIds: Set<String>,
    states: List<ProvinceControlState> = emptyList(),
) {
    val knownProvinceIds: Set<String> = Collections.unmodifiableSet(LinkedHashSet(knownProvinceIds))
    val statesByProvinceId: Map<String, ProvinceControlState>

    init {
        require(topologyRevision.isNotBlank()) { "Province control requires a topology revision" }
        require(topologyHash.matches(SHA_256_PATTERN)) { "Province control requires a SHA-256 topology hash" }
        require(this.knownProvinceIds.none(String::isBlank)) { "Province IDs must not be blank" }
        require(states.map(ProvinceControlState::provinceId).distinct().size == states.size) {
            "Duplicate province control rows"
        }
        states.forEach { state ->
            require(state.topologyRevision == topologyRevision && state.topologyHash == topologyHash) {
                "Province control topology mismatch"
            }
            require(state.provinceId in this.knownProvinceIds) { "Unknown province ${state.provinceId}" }
        }
        statesByProvinceId = Collections.unmodifiableMap(
            states.associateByTo(linkedMapOf(), ProvinceControlState::provinceId),
        )
    }

    fun stateFor(provinceId: String): ProvinceControlState? = statesByProvinceId[provinceId]

    fun withState(state: ProvinceControlState): ProvinceControlSnapshot = ProvinceControlSnapshot(
        topologyRevision,
        topologyHash,
        knownProvinceIds,
        statesByProvinceId.values.filterNot { it.provinceId == state.provinceId } + state,
    )

    companion object {
        fun fromTopology(
            topology: StrategicTopologySnapshot,
            states: List<ProvinceControlState> = emptyList(),
        ): ProvinceControlSnapshot = ProvinceControlSnapshot(
            topology.topologyRevision,
            topology.contentHash,
            topology.landProvinceIds,
            states,
        )
    }
}

enum class ProvinceControlDenialCode {
    UNSUPPORTED_WORLD,
    TOPOLOGY_MISMATCH,
    UNKNOWN_PROVINCE,
    STALE_REVISION,
    REVISION_EXHAUSTED,
}

sealed interface ProvinceControlChangeResult {
    data class Changed(val expectedRevision: Long?, val state: ProvinceControlState) : ProvinceControlChangeResult
    data class Unchanged(val state: ProvinceControlState) : ProvinceControlChangeResult
    data class Denied(val code: ProvinceControlDenialCode) : ProvinceControlChangeResult
}

/** Pure optimistic projection; persistence is responsible for applying the returned change. */
fun projectProvinceControl(
    snapshot: ProvinceControlSnapshot,
    expectedRevision: Long?,
    assessment: ProvinceControlAssessment,
): ProvinceControlChangeResult {
    fun denied(code: ProvinceControlDenialCode) = ProvinceControlChangeResult.Denied(code)
    if (snapshot.topologyRevision != assessment.topologyRevision || snapshot.topologyHash != assessment.topologyHash) {
        return denied(ProvinceControlDenialCode.TOPOLOGY_MISMATCH)
    }
    if (assessment.provinceId !in snapshot.knownProvinceIds) {
        return denied(ProvinceControlDenialCode.UNKNOWN_PROVINCE)
    }
    val previous = snapshot.stateFor(assessment.provinceId)
    if (expectedRevision != previous?.revision) return denied(ProvinceControlDenialCode.STALE_REVISION)
    if (previous != null && previous.nationId == assessment.nationId) {
        return ProvinceControlChangeResult.Unchanged(previous)
    }
    if (previous?.revision == Long.MAX_VALUE) return denied(ProvinceControlDenialCode.REVISION_EXHAUSTED)
    val nextRevision = (previous?.revision ?: 0L) + 1L
    return ProvinceControlChangeResult.Changed(
        expectedRevision,
        ProvinceControlState(
            assessment.topologyRevision,
            assessment.topologyHash,
            assessment.provinceId,
            assessment.nationId,
            nextRevision,
        ),
    )
}

private val SHA_256_PATTERN = Regex("[0-9a-f]{64}")

private fun validateProvinceControlIdentity(revision: String, hash: String, provinceId: String, nationId: Int) {
    require(revision.isNotBlank() && provinceId.isNotBlank()) {
        "Province control requires exact topology and province IDs"
    }
    require(hash.matches(SHA_256_PATTERN)) { "Province control requires a SHA-256 topology hash" }
    require(nationId >= 0) { "Province control nation ID must not be negative" }
}
