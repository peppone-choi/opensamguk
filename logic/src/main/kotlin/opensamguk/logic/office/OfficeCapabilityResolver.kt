package opensamguk.logic.office

enum class OfficeEvidence {
    LIVING_CLAIM,
    ACCEPTED_TENURE,
    ASSUMED_SEAT,
    SEAT_OWNED,
    HOLDER_AT_SEAT,
    COUNTY_MAJORITY,
    WAREHOUSE_CONNECTION,
    LOCAL_MAGISTRATE_OR_GARRISON,
}

enum class OfficeCapability { COMMANDERY_POLICY, COUNTY_WORK_START, COUNTY_WORK_REDUCE, PROVINCIAL_INSPECTION }

enum class OfficeDenial { OFFICE_MISMATCH, COUNTY_PLACEMENT_ONLY, WRONG_JURISDICTION, NOMINAL_ONLY, CAPABILITY_UNAVAILABLE, TARGET_OUTSIDE_CONTROL }

data class OfficeJurisdictionSnapshot(
    val jurisdictionId: String,
    val countyIds: Set<Int>,
    val seatCountyId: Int,
    val holderCountyId: Int?,
    val ownedCountyIds: Set<Int>,
    val warehouseConnectedCountyIds: Set<Int>,
    val seatedMagistrateCountyIds: Set<Int>,
    val stationedCorpsCountyIds: Set<Int>,
) {
    init {
        require(jurisdictionId.isNotBlank() && countyIds.isNotEmpty() && countyIds.all { it > 0 })
        require(seatCountyId in countyIds)
        require(ownedCountyIds.all { it in countyIds })
        require(warehouseConnectedCountyIds.all { it in countyIds })
        require(seatedMagistrateCountyIds.all { it in countyIds })
        require(stationedCorpsCountyIds.all { it in countyIds })
    }
}

data class ActualJurisdiction(val countyIds: List<Int>, val missing: List<OfficeEvidence>) {
    val effective: Boolean get() = missing.isEmpty()
}

sealed interface OfficeCapabilityDecision {
    data class Allow(val capability: OfficeCapability, val jurisdictionId: String, val countyIds: List<Int>) : OfficeCapabilityDecision
    data class Deny(val reason: OfficeDenial, val missing: List<OfficeEvidence> = emptyList()) : OfficeCapabilityDecision
}

/** One calculation shared by handler prechecks, execution, and NPC choices. */
object OfficeCapabilityResolver {
    fun actualJurisdiction(
        tenure: OfficeTenure,
        snapshot: OfficeJurisdictionSnapshot,
        rules: OfficeRules,
    ): ActualJurisdiction {
        require(tenure.jurisdictionId == snapshot.jurisdictionId) { "jurisdiction snapshot mismatch" }
        val missing = mutableListOf<OfficeEvidence>()
        if (tenure.origin == OfficeClaimOrigin.POSTHUMOUS) missing += OfficeEvidence.LIVING_CLAIM
        if (!tenure.isActive) missing += OfficeEvidence.ACCEPTED_TENURE
        if (!tenure.isAssumed || tenure.seatCountyId != snapshot.seatCountyId) missing += OfficeEvidence.ASSUMED_SEAT
        if (snapshot.seatCountyId !in snapshot.ownedCountyIds) missing += OfficeEvidence.SEAT_OWNED
        if (snapshot.holderCountyId != snapshot.seatCountyId) missing += OfficeEvidence.HOLDER_AT_SEAT
        if (snapshot.ownedCountyIds.size.toLong() * 100 < snapshot.countyIds.size.toLong() * rules.minimumOwnedPercent) {
            missing += OfficeEvidence.COUNTY_MAJORITY
        }
        val supplied = snapshot.ownedCountyIds intersect snapshot.warehouseConnectedCountyIds
        if (snapshot.seatCountyId !in supplied) missing += OfficeEvidence.WAREHOUSE_CONNECTION
        if ((snapshot.seatedMagistrateCountyIds + snapshot.stationedCorpsCountyIds).none { it in snapshot.ownedCountyIds }) {
            missing += OfficeEvidence.LOCAL_MAGISTRATE_OR_GARRISON
        }
        return ActualJurisdiction(if (missing.isEmpty()) supplied.sorted() else emptyList(), missing)
    }

    fun resolve(
        definition: OfficeDefinition,
        tenure: OfficeTenure,
        snapshot: OfficeJurisdictionSnapshot,
        rules: OfficeRules,
        capability: OfficeCapability,
        targetCountyId: Int? = null,
    ): OfficeCapabilityDecision {
        if (definition.id != tenure.officeId) return OfficeCapabilityDecision.Deny(OfficeDenial.OFFICE_MISMATCH)
        if (definition.countyPlacementOnly) return OfficeCapabilityDecision.Deny(OfficeDenial.COUNTY_PLACEMENT_ONLY)
        if (tenure.jurisdictionId in definition.excludedJurisdictions) return OfficeCapabilityDecision.Deny(OfficeDenial.WRONG_JURISDICTION)
        if (snapshot.jurisdictionId != tenure.jurisdictionId) return OfficeCapabilityDecision.Deny(OfficeDenial.WRONG_JURISDICTION)
        val actual = actualJurisdiction(tenure, snapshot, rules)
        if (!actual.effective) return OfficeCapabilityDecision.Deny(OfficeDenial.NOMINAL_ONLY, actual.missing)
        val allowed = when (definition.jurisdiction) {
            OfficeJurisdiction.JUN -> capability in setOf(OfficeCapability.COMMANDERY_POLICY, OfficeCapability.COUNTY_WORK_START, OfficeCapability.COUNTY_WORK_REDUCE)
            OfficeJurisdiction.ZHOU -> capability == OfficeCapability.PROVINCIAL_INSPECTION
            OfficeJurisdiction.COUNTY -> false
        }
        if (!allowed) return OfficeCapabilityDecision.Deny(OfficeDenial.CAPABILITY_UNAVAILABLE)
        if (capability in setOf(OfficeCapability.COUNTY_WORK_START, OfficeCapability.COUNTY_WORK_REDUCE) &&
            (targetCountyId == null || targetCountyId !in actual.countyIds)) {
            return OfficeCapabilityDecision.Deny(OfficeDenial.TARGET_OUTSIDE_CONTROL)
        }
        return OfficeCapabilityDecision.Allow(capability, tenure.jurisdictionId, actual.countyIds)
    }

    fun validateTenures(
        tenures: Collection<OfficeTenure>,
        catalog: OfficeCatalog,
        rules: OfficeRules,
        credentials: Collection<OfficeCredential> = emptyList(),
    ) {
        require(tenures.map { it.id }.distinct().size == tenures.size) { "duplicate tenure id" }
        require(credentials.map { it.id }.distinct().size == credentials.size) { "duplicate office credential" }
        val credentialIds = credentials.map { it.id }.toSet()
        require(tenures.all { tenure -> tenure.credentialIds.all { it in credentialIds } }) { "dangling office credential" }
        val active = tenures.filter { it.isActive }
        active.forEach { tenure ->
            val definition = requireNotNull(catalog.definition(tenure.officeId)) { "unknown local office" }
            require(!definition.countyPlacementOnly) { "county office tenure duplicates magistrate placement" }
            require(tenure.jurisdictionId !in definition.excludedJurisdictions) { "excluded local jurisdiction" }
        }
        require(active.groupBy { it.holderId }.values.all { it.size <= rules.maximumConcurrentLocalTenures }) { "local office concurrency exceeded" }
        require(active.groupBy { it.nationId to it.jurisdictionId }.values.all { samePlace ->
            samePlace.map { catalog.definition(it.officeId)!!.jurisdiction }.distinct().size == samePlace.size
        }) { "conflicting local jurisdiction tenure" }
    }
}
