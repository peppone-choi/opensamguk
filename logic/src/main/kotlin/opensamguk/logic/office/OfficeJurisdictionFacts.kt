package opensamguk.logic.office

import opensamguk.common.world.WorldId
import opensamguk.logic.input.Phase
import opensamguk.logic.world.WorldMapVariant

/** An observation binding, not a mutation revision, lease, or authorization receipt. */
data class OfficeSourceBinding(
    val worldId: WorldId,
    val variant: WorldMapVariant,
    val topologyRevision: String,
    val topologyHash: String,
    val selectedAdministrativeHash: String,
    val asOf: Phase,
) {
    init {
        require(topologyRevision.isNotBlank())
        require(topologyHash.matches(Regex("[0-9a-f]{64}")))
        require(selectedAdministrativeHash.matches(Regex("[0-9a-f]{64}")))
    }
}

enum class OfficeSourceUnavailableReason {
    MISSING, CORRUPT, UNVALIDATED, BINDING_MISMATCH, INVALID_VALUE,
}

/** Known null/empty values are facts; an unavailable section never supplies a replacement value. */
sealed interface OfficeSourceSection<out T> {
    data class Known<T>(
        val value: T,
        val binding: OfficeSourceBinding,
        val mutationRevision: Long? = null,
    ) : OfficeSourceSection<T> {
        init { require(mutationRevision == null || mutationRevision >= 0) }
    }
    data class Unavailable(val reason: OfficeSourceUnavailableReason) : OfficeSourceSection<Nothing>
}

enum class OfficeSourceKey {
    ADMINISTRATIVE_COUNTIES, CANONICAL_JOIN, LIVE_OWNERS, CURRENT_SEAT, HOLDER_LOCATION,
    WAREHOUSE_CONNECTION, SEATED_MAGISTRATES, STATIONED_CORPS, ACTORS, TENURES, CENTRAL_OFFICES,
}

/** A complete target-scope join validated against the selected variant by its source adapter. */
data class OfficeJurisdictionCounty(
    val countyId: Int,
    val canonicalJurisdictionId: String,
    val liveJurisdictionId: String,
)

/** An actual live row, never a scenario fallback or a commandery majority projection. */
data class OfficeLiveCountyOwner(val countyId: Int, val liveJurisdictionId: String, val nationId: Int)

data class OfficeJurisdictionFacts(
    val binding: OfficeSourceBinding,
    val jurisdictionId: String,
    val nationId: Int,
    val administrativeCountyIds: OfficeSourceSection<Set<Int>>,
    val canonicalJoin: OfficeSourceSection<List<OfficeJurisdictionCounty>>,
    val liveOwners: OfficeSourceSection<List<OfficeLiveCountyOwner>>,
    val currentSeatCountyId: OfficeSourceSection<Int>,
    val holderCountyId: OfficeSourceSection<Int?>,
    val warehouseConnectedCountyIds: OfficeSourceSection<Set<Int>>,
    val seatedMagistrateCountyIds: OfficeSourceSection<Set<Int>>,
    val stationedCorpsCountyIds: OfficeSourceSection<Set<Int>>,
)

/** Raw server facts only. Ruler/living/retired are not inferred from rank or another actor's claims. */
data class OfficeAppointmentActors(
    val issuerId: Int,
    val issuerNationId: Int,
    val issuerIsRuler: Boolean,
    val candidateId: Int,
    val candidateNationId: Int,
    val candidateIsHuman: Boolean,
    val candidateIsLiving: Boolean,
    val candidateIsRetired: Boolean,
)

data class OfficeAppointmentSourceFacts(
    val jurisdiction: OfficeJurisdictionFacts,
    val actors: OfficeSourceSection<OfficeAppointmentActors>,
    val tenures: OfficeSourceSection<List<OfficeTenure>>,
    val centralOfficeIds: OfficeSourceSection<Set<String>>,
)

/** Missing mutation revisions remain explicit, even when the observed value is known. */
data class OfficeSourceReport(
    val binding: OfficeSourceBinding,
    val knownSections: Set<OfficeSourceKey>,
    val unversionedSections: Set<OfficeSourceKey>,
    val mutationRevisions: Map<OfficeSourceKey, Long>,
    val unavailable: Map<OfficeSourceKey, OfficeSourceUnavailableReason>,
)

sealed interface OfficeJurisdictionProjection {
    val sources: OfficeSourceReport
    data class Ready(val snapshot: OfficeJurisdictionSnapshot, override val sources: OfficeSourceReport) :
        OfficeJurisdictionProjection
    data class Unavailable(override val sources: OfficeSourceReport) : OfficeJurisdictionProjection
}

/** Ready means a complete native context, not a granted office, approved cost, or delivered input. */
sealed interface OfficeAppointmentContextProjection {
    val sources: OfficeSourceReport
    data class Ready(val context: OfficeAppointmentContext, override val sources: OfficeSourceReport) :
        OfficeAppointmentContextProjection
    data class Unavailable(override val sources: OfficeSourceReport) : OfficeAppointmentContextProjection
}
