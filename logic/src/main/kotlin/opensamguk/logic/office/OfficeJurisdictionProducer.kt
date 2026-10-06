package opensamguk.logic.office

import java.util.Collections

/** Pure source projection. Policy assessment remains exclusively in the existing native rules. */
object OfficeJurisdictionProducer {
    fun projectCapabilitySources(facts: OfficeJurisdictionFacts): OfficeJurisdictionProjection {
        val sources = Sources(facts.binding)
        val snapshot = snapshot(facts, sources)
        return if (snapshot == null) OfficeJurisdictionProjection.Unavailable(sources.report())
        else OfficeJurisdictionProjection.Ready(snapshot, sources.report())
    }

    fun projectAppointmentSources(facts: OfficeAppointmentSourceFacts): OfficeAppointmentContextProjection {
        val sources = Sources(facts.jurisdiction.binding)
        val actors = sources.read(OfficeSourceKey.ACTORS, facts.actors)
        val tenures = sources.read(OfficeSourceKey.TENURES, facts.tenures)
        val central = sources.read(OfficeSourceKey.CENTRAL_OFFICES, facts.centralOfficeIds)
        val jurisdiction = snapshot(facts.jurisdiction, sources)
        if (actors != null && (actors.issuerId <= 0 || actors.candidateId <= 0 ||
                actors.issuerNationId <= 0 || actors.candidateNationId < 0 ||
                actors.issuerNationId != facts.jurisdiction.nationId)) sources.invalid(OfficeSourceKey.ACTORS)
        if (tenures != null && tenures.map { it.id }.distinct().size != tenures.size)
            sources.invalid(OfficeSourceKey.TENURES)
        if (central != null && central.any(String::isBlank)) sources.invalid(OfficeSourceKey.CENTRAL_OFFICES)
        if (sources.failed || jurisdiction == null) return OfficeAppointmentContextProjection.Unavailable(sources.report())
        checkNotNull(actors)
        val context = OfficeAppointmentContext(
            actors.issuerId, actors.issuerNationId, actors.issuerIsRuler,
            actors.candidateId, actors.candidateNationId, actors.candidateIsHuman,
            actors.candidateIsLiving, actors.candidateIsRetired, jurisdiction,
            frozenList(checkNotNull(tenures).map { it.copy(credentialIds = frozenList(it.credentialIds)) }),
            frozenSet(checkNotNull(central)),
        )
        return OfficeAppointmentContextProjection.Ready(context, sources.report())
    }

    private fun snapshot(facts: OfficeJurisdictionFacts, sources: Sources): OfficeJurisdictionSnapshot? {
        val administrative = sources.read(OfficeSourceKey.ADMINISTRATIVE_COUNTIES, facts.administrativeCountyIds)
        val joined = sources.read(OfficeSourceKey.CANONICAL_JOIN, facts.canonicalJoin)
        val owners = sources.read(OfficeSourceKey.LIVE_OWNERS, facts.liveOwners)
        val seat = sources.read(OfficeSourceKey.CURRENT_SEAT, facts.currentSeatCountyId)
        val holder = sources.read(OfficeSourceKey.HOLDER_LOCATION, facts.holderCountyId)
        val warehouse = sources.read(OfficeSourceKey.WAREHOUSE_CONNECTION, facts.warehouseConnectedCountyIds)
        val magistrates = sources.read(OfficeSourceKey.SEATED_MAGISTRATES, facts.seatedMagistrateCountyIds)
        val corps = sources.read(OfficeSourceKey.STATIONED_CORPS, facts.stationedCorpsCountyIds)
        if (administrative != null && administrative.any { it <= 0 }) sources.invalid(OfficeSourceKey.ADMINISTRATIVE_COUNTIES)
        if (joined != null && (facts.jurisdictionId.isBlank() || joined.isEmpty() ||
                joined.map { it.countyId }.distinct().size != joined.size ||
                joined.map { it.liveJurisdictionId }.distinct().size != joined.size ||
                joined.any { it.countyId <= 0 || it.canonicalJurisdictionId != facts.jurisdictionId ||
                    it.liveJurisdictionId.isBlank() || administrative != null && it.countyId !in administrative }))
            sources.invalid(OfficeSourceKey.CANONICAL_JOIN)
        val counties = joined?.map { it.countyId }?.toSet()
        if (owners != null && (facts.nationId <= 0 || owners.map { it.countyId }.distinct().size != owners.size ||
                owners.any { it.countyId <= 0 || it.nationId < 0 || it.liveJurisdictionId.isBlank() } ||
                counties != null && owners.map { it.countyId }.toSet() != counties ||
                joined != null && owners.any { owner -> joined.none {
                    it.countyId == owner.countyId && it.liveJurisdictionId == owner.liveJurisdictionId
                } })) sources.invalid(OfficeSourceKey.LIVE_OWNERS)
        if (seat != null && (seat <= 0 || counties != null && seat !in counties)) sources.invalid(OfficeSourceKey.CURRENT_SEAT)
        if (holder != null && holder <= 0) sources.invalid(OfficeSourceKey.HOLDER_LOCATION)
        for ((key, value) in listOf(OfficeSourceKey.WAREHOUSE_CONNECTION to warehouse,
                OfficeSourceKey.SEATED_MAGISTRATES to magistrates, OfficeSourceKey.STATIONED_CORPS to corps)) {
            if (value != null && value.any { it <= 0 || counties != null && it !in counties }) sources.invalid(key)
        }
        if (sources.failed) return null
        return OfficeJurisdictionSnapshot(facts.jurisdictionId, frozenSet(checkNotNull(counties)), checkNotNull(seat), holder,
            frozenSet(checkNotNull(owners).filter { it.nationId == facts.nationId }.map { it.countyId }),
            frozenSet(checkNotNull(warehouse)), frozenSet(checkNotNull(magistrates)), frozenSet(checkNotNull(corps)))
    }

    private class Sources(private val binding: OfficeSourceBinding) {
        private val known = linkedSetOf<OfficeSourceKey>()
        private val unversioned = linkedSetOf<OfficeSourceKey>()
        private val revisions = linkedMapOf<OfficeSourceKey, Long>()
        private val unavailable = linkedMapOf<OfficeSourceKey, OfficeSourceUnavailableReason>()
        val failed: Boolean get() = unavailable.isNotEmpty()

        fun <T> read(key: OfficeSourceKey, section: OfficeSourceSection<T>): T? = when (section) {
            is OfficeSourceSection.Unavailable -> {
                unavailable[key] = section.reason
                null
            }
            is OfficeSourceSection.Known -> {
                if (section.binding != binding) {
                    unavailable[key] = OfficeSourceUnavailableReason.BINDING_MISMATCH
                    null
                } else {
                    known += key
                    if (section.mutationRevision == null) unversioned += key
                    else revisions[key] = section.mutationRevision
                    section.value
                }
            }
        }

        fun invalid(key: OfficeSourceKey) {
            known -= key
            unversioned -= key
            revisions -= key
            unavailable[key] = OfficeSourceUnavailableReason.INVALID_VALUE
        }

        fun report() = OfficeSourceReport(binding, frozenSet(known), frozenSet(unversioned),
            Collections.unmodifiableMap(LinkedHashMap(revisions)),
            Collections.unmodifiableMap(LinkedHashMap(unavailable)))
    }

    private fun <T> frozenSet(values: Collection<T>): Set<T> =
        Collections.unmodifiableSet(LinkedHashSet(values))

    private fun <T> frozenList(values: Collection<T>): List<T> =
        Collections.unmodifiableList(ArrayList(values))
}
