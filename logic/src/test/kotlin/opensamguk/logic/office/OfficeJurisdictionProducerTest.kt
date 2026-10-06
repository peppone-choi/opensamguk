package opensamguk.logic.office

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import opensamguk.common.world.WorldId
import opensamguk.logic.input.Phase
import opensamguk.logic.world.WorldMapVariant

class OfficeJurisdictionProducerTest {
    private val binding = OfficeSourceBinding(WorldId(1), WorldMapVariant.V3_1428,
        "topology-1", "a".repeat(64), "b".repeat(64), Phase(196, 1, 2))
    private val jurisdictionId = "hhs-group:109:京兆尹"
    private fun <T> known(value: T) = OfficeSourceSection.Known(value, binding)
    private fun missing(reason: OfficeSourceUnavailableReason = OfficeSourceUnavailableReason.MISSING) =
        OfficeSourceSection.Unavailable(reason)
    private val counties = listOf(OfficeJurisdictionCounty(100, jurisdictionId, "tile:100"),
        OfficeJurisdictionCounty(101, jurisdictionId, "tile:101"))
    private val owners = listOf(OfficeLiveCountyOwner(100, "tile:100", 7), OfficeLiveCountyOwner(101, "tile:101", 0))
    private val jurisdiction = OfficeJurisdictionFacts(binding, jurisdictionId, 7,
        known(setOf(100, 101, 200)), known(counties), known(owners), known(100), known(null),
        known(emptySet()), known(emptySet()), known(emptySet()))
    private val actors = OfficeAppointmentActors(1, 7, true, 2, 7, true, true, false)
    private val facts = OfficeAppointmentSourceFacts(jurisdiction, known(actors), known(emptyList()), known(emptySet()))
    private val request = OfficeAppointmentRequest(1, 2, "office.commandery-prefect", jurisdictionId, 100)
    private val catalog = OfficeCatalog.loadClasspath()
    private val rules = OfficeRules.loadClasspath()

    private fun ready(source: OfficeAppointmentSourceFacts = facts) =
        assertIs<OfficeAppointmentContextProjection.Ready>(OfficeJurisdictionProducer.projectAppointmentSources(source))

    private fun denied(source: OfficeAppointmentSourceFacts, key: OfficeSourceKey,
                       reason: OfficeSourceUnavailableReason = OfficeSourceUnavailableReason.INVALID_VALUE) {
        val result = assertIs<OfficeAppointmentContextProjection.Unavailable>(
            OfficeJurisdictionProducer.projectAppointmentSources(source))
        assertEquals(reason, result.sources.unavailable[key])
    }

    @Test
    fun `complete actual facts preserve native context without inventing mutation revisions`() {
        val result = ready()
        assertEquals(binding, result.sources.binding)
        assertEquals(OfficeSourceKey.entries.toSet(), result.sources.knownSections)
        assertEquals(result.sources.knownSections, result.sources.unversionedSections)
        assertTrue(result.sources.mutationRevisions.isEmpty())
        assertTrue(result.sources.unavailable.isEmpty())
        assertEquals(setOf(100, 101), result.context.jurisdiction.countyIds)
        assertEquals(setOf(100), result.context.jurisdiction.ownedCountyIds)
        assertNull(result.context.jurisdiction.holderCountyId)
        assertTrue(result.context.activeTenures.isEmpty())
    }

    @Test
    fun `current seat absence is never replaced with base or requested seat`() {
        for (reason in listOf(OfficeSourceUnavailableReason.MISSING, OfficeSourceUnavailableReason.UNVALIDATED,
            OfficeSourceUnavailableReason.CORRUPT)) {
            denied(facts.copy(jurisdiction = jurisdiction.copy(currentSeatCountyId = missing(reason))),
                OfficeSourceKey.CURRENT_SEAT, reason)
        }
    }

    @Test
    fun `known absent holder location differs from missing position source`() {
        assertNull(ready().context.jurisdiction.holderCountyId)
        denied(facts.copy(jurisdiction = jurisdiction.copy(holderCountyId = missing())),
            OfficeSourceKey.HOLDER_LOCATION, OfficeSourceUnavailableReason.MISSING)
    }

    @Test
    fun `known empty tenures and evidence do not become unavailable or get fabricated when absent`() {
        assertTrue(ready().context.activeTenures.isEmpty())
        assertTrue(ready().context.jurisdiction.stationedCorpsCountyIds.isEmpty())
        denied(facts.copy(tenures = missing()), OfficeSourceKey.TENURES, OfficeSourceUnavailableReason.MISSING)
        denied(facts.copy(jurisdiction = jurisdiction.copy(stationedCorpsCountyIds = missing())),
            OfficeSourceKey.STATIONED_CORPS, OfficeSourceUnavailableReason.MISSING)
    }

    @Test
    fun `every missing jurisdiction section blocks structural native context`() {
        val cases = listOf(
            OfficeSourceKey.ADMINISTRATIVE_COUNTIES to jurisdiction.copy(administrativeCountyIds = missing()),
            OfficeSourceKey.CANONICAL_JOIN to jurisdiction.copy(canonicalJoin = missing()),
            OfficeSourceKey.LIVE_OWNERS to jurisdiction.copy(liveOwners = missing()),
            OfficeSourceKey.CURRENT_SEAT to jurisdiction.copy(currentSeatCountyId = missing()),
            OfficeSourceKey.HOLDER_LOCATION to jurisdiction.copy(holderCountyId = missing()),
            OfficeSourceKey.WAREHOUSE_CONNECTION to jurisdiction.copy(warehouseConnectedCountyIds = missing()),
            OfficeSourceKey.SEATED_MAGISTRATES to jurisdiction.copy(seatedMagistrateCountyIds = missing()),
            OfficeSourceKey.STATIONED_CORPS to jurisdiction.copy(stationedCorpsCountyIds = missing()),
        )
        for ((key, source) in cases) denied(facts.copy(jurisdiction = source), key, OfficeSourceUnavailableReason.MISSING)
    }

    @Test
    fun `missing actor tenure and central office sources block context independently`() {
        for ((key, source) in listOf(OfficeSourceKey.ACTORS to facts.copy(actors = missing()),
            OfficeSourceKey.TENURES to facts.copy(tenures = missing()),
            OfficeSourceKey.CENTRAL_OFFICES to facts.copy(centralOfficeIds = missing()))) {
            denied(source, key, OfficeSourceUnavailableReason.MISSING)
        }
    }

    @Test
    fun `world variant pin administrative bytes and observation must share one binding`() {
        val variants = listOf(binding.copy(worldId = WorldId(2)), binding.copy(variant = WorldMapVariant.PROVINCE_WORLD),
            binding.copy(topologyRevision = "topology-2"), binding.copy(topologyHash = "c".repeat(64)),
            binding.copy(selectedAdministrativeHash = "d".repeat(64)), binding.copy(asOf = binding.asOf.plus(1)))
        for (foreign in variants) {
            denied(facts.copy(jurisdiction = jurisdiction.copy(liveOwners = OfficeSourceSection.Known(owners, foreign))),
                OfficeSourceKey.LIVE_OWNERS, OfficeSourceUnavailableReason.BINDING_MISMATCH)
            denied(facts.copy(actors = OfficeSourceSection.Known(actors, foreign)),
                OfficeSourceKey.ACTORS, OfficeSourceUnavailableReason.BINDING_MISMATCH)
        }
    }

    @Test
    fun `known sections remain visible while missing supplier facts block native construction`() {
        val source = facts.copy(jurisdiction = jurisdiction.copy(currentSeatCountyId = missing(),
            warehouseConnectedCountyIds = missing(), seatedMagistrateCountyIds = missing(), stationedCorpsCountyIds = missing()))
        val result = assertIs<OfficeAppointmentContextProjection.Unavailable>(
            OfficeJurisdictionProducer.projectAppointmentSources(source))
        assertEquals(setOf(OfficeSourceKey.CURRENT_SEAT, OfficeSourceKey.WAREHOUSE_CONNECTION,
            OfficeSourceKey.SEATED_MAGISTRATES, OfficeSourceKey.STATIONED_CORPS), result.sources.unavailable.keys)
        assertTrue(OfficeSourceKey.ADMINISTRATIVE_COUNTIES in result.sources.knownSections)
        assertTrue(OfficeSourceKey.LIVE_OWNERS in result.sources.knownSections)
    }

    @Test
    fun `runtime commandery text is not a selected canonical jurisdiction join`() {
        denied(facts.copy(jurisdiction = jurisdiction.copy(canonicalJoin = known(counties.map {
            it.copy(canonicalJurisdictionId = "京兆尹")
        }))), OfficeSourceKey.CANONICAL_JOIN)
        denied(facts.copy(jurisdiction = jurisdiction.copy(canonicalJoin = missing(OfficeSourceUnavailableReason.UNVALIDATED))),
            OfficeSourceKey.CANONICAL_JOIN, OfficeSourceUnavailableReason.UNVALIDATED)
    }

    @Test
    fun `canonical scope rejects duplicate counties duplicate live jurisdictions and foreign county rows`() {
        for (bad in listOf(counties + counties.first(), counties.map { it.copy(liveJurisdictionId = "same") },
            counties + OfficeJurisdictionCounty(999, jurisdictionId, "tile:999"), emptyList())) {
            denied(facts.copy(jurisdiction = jurisdiction.copy(canonicalJoin = known(bad))), OfficeSourceKey.CANONICAL_JOIN)
        }
    }

    @Test
    fun `binding rejects missing physical pins without synthesizing identity`() {
        assertFailsWith<IllegalArgumentException> { binding.copy(topologyRevision = "") }
        assertFailsWith<IllegalArgumentException> { binding.copy(topologyHash = "unknown") }
        assertFailsWith<IllegalArgumentException> { binding.copy(selectedAdministrativeHash = "") }
        denied(facts.copy(jurisdiction = jurisdiction.copy(administrativeCountyIds = known(setOf(0)))),
            OfficeSourceKey.ADMINISTRATIVE_COUNTIES)
    }

    @Test
    fun `live owner inventory must completely and uniquely join the target counties`() {
        for (bad in listOf(owners.drop(1), owners + owners.first(), owners + OfficeLiveCountyOwner(200, "tile:200", 7),
            owners.map { it.copy(liveJurisdictionId = "other") }, owners.map { it.copy(nationId = -1) })) {
            denied(facts.copy(jurisdiction = jurisdiction.copy(liveOwners = known(bad))), OfficeSourceKey.LIVE_OWNERS)
        }
    }

    @Test
    fun `neutral ownership stays known and changed ownership reaches native denial`() {
        val changed = facts.copy(jurisdiction = jurisdiction.copy(liveOwners = known(owners.map { it.copy(nationId = 0) })))
        val result = ready(changed)
        assertTrue(result.context.jurisdiction.ownedCountyIds.isEmpty())
        assertEquals(OfficeAppointmentAssessment.Denied(OfficeAppointmentFailure.SEAT_NOT_OWNED),
            OfficeAppointmentRules.assess(request, result.context, catalog, rules))
    }

    @Test
    fun `actual current seat may change without being replaced by the requested seat`() {
        val result = ready(facts.copy(jurisdiction = jurisdiction.copy(currentSeatCountyId = known(101))))
        assertEquals(101, result.context.jurisdiction.seatCountyId)
        assertEquals(OfficeAppointmentAssessment.Denied(OfficeAppointmentFailure.SEAT_NOT_IN_JURISDICTION),
            OfficeAppointmentRules.assess(request, result.context, catalog, rules))
        denied(facts.copy(jurisdiction = jurisdiction.copy(currentSeatCountyId = known(999))), OfficeSourceKey.CURRENT_SEAT)
    }

    @Test
    fun `capability evidence is structural input rather than an additional appointment qualification`() {
        val context = ready().context
        assertEquals(OfficeAppointmentAssessment.Allowed(true), OfficeAppointmentRules.assess(request, context, catalog, rules))
        val tenure = OfficeTenure("existing", request.officeId, jurisdictionId, 2, 1, 7,
            OfficeClaimOrigin.POLITY_APPOINTMENT, 10, acceptedTurn = 11)
        val actual = OfficeCapabilityResolver.actualJurisdiction(tenure, context.jurisdiction, rules)
        assertTrue(!actual.effective)
        assertTrue(OfficeEvidence.WAREHOUSE_CONNECTION in actual.missing)
        assertTrue(OfficeEvidence.LOCAL_MAGISTRATE_OR_GARRISON in actual.missing)
    }

    @Test
    fun `native actor policy is neither copied nor used to hide known facts`() {
        for ((value, failure) in listOf(actors.copy(issuerIsRuler = false) to OfficeAppointmentFailure.NOT_RULER,
            actors.copy(candidateNationId = 8) to OfficeAppointmentFailure.CANDIDATE_OUTSIDE_NATION,
            actors.copy(candidateIsLiving = false) to OfficeAppointmentFailure.CANDIDATE_UNAVAILABLE,
            actors.copy(candidateIsRetired = true) to OfficeAppointmentFailure.CANDIDATE_UNAVAILABLE)) {
            val result = ready(facts.copy(actors = known(value)))
            assertEquals(OfficeAppointmentAssessment.Denied(failure),
                OfficeAppointmentRules.assess(request, result.context, catalog, rules))
        }
    }

    @Test
    fun `invalid actors and inconsistent issuer nation are source failures`() {
        for (bad in listOf(actors.copy(issuerId = 0), actors.copy(candidateId = 0),
            actors.copy(candidateNationId = -1), actors.copy(issuerNationId = 8))) {
            denied(facts.copy(actors = known(bad)), OfficeSourceKey.ACTORS)
        }
    }

    @Test
    fun `corrupted duplicate tenure source is not normalized and ended records are retained`() {
        val ended = OfficeTenure("ended", request.officeId, jurisdictionId, 2, 1, 7,
            OfficeClaimOrigin.POLITY_APPOINTMENT, 10, acceptedTurn = 11, endedTurn = 12)
        denied(facts.copy(tenures = known(listOf(ended, ended))), OfficeSourceKey.TENURES)
        assertEquals(listOf(ended), ready(facts.copy(tenures = known(listOf(ended)))).context.activeTenures)
        denied(facts.copy(centralOfficeIds = known(setOf(""))), OfficeSourceKey.CENTRAL_OFFICES)
    }

    @Test
    fun `supplied mutation revision is preserved while unknown revisions stay absent`() {
        val source = facts.copy(jurisdiction = jurisdiction.copy(currentSeatCountyId =
            OfficeSourceSection.Known(100, binding, mutationRevision = 9)))
        val result = ready(source)
        assertEquals(mapOf(OfficeSourceKey.CURRENT_SEAT to 9L), result.sources.mutationRevisions)
        assertTrue(OfficeSourceKey.CURRENT_SEAT !in result.sources.unversionedSections)
        assertTrue(OfficeSourceKey.LIVE_OWNERS in result.sources.unversionedSections)
        assertFailsWith<IllegalArgumentException> { OfficeSourceSection.Known(100, binding, -1) }
    }

    @Test
    fun `invalid evidence county sets and holder values fail closed`() {
        for ((key, source) in listOf(OfficeSourceKey.WAREHOUSE_CONNECTION to jurisdiction.copy(warehouseConnectedCountyIds = known(setOf(999))),
            OfficeSourceKey.SEATED_MAGISTRATES to jurisdiction.copy(seatedMagistrateCountyIds = known(setOf(0))),
            OfficeSourceKey.STATIONED_CORPS to jurisdiction.copy(stationedCorpsCountyIds = known(setOf(-1))),
            OfficeSourceKey.HOLDER_LOCATION to jurisdiction.copy(holderCountyId = known(0)))) {
            denied(facts.copy(jurisdiction = source), key)
        }
    }

    @Test
    fun `projection snapshots cannot change when the caller mutates source collections`() {
        val admins = mutableSetOf(100, 101)
        val join = counties.toMutableList()
        val live = owners.toMutableList()
        val warehouse = mutableSetOf(100)
        val credentials = mutableListOf("credential-1")
        val tenure = OfficeTenure("tenure-1", request.officeId, jurisdictionId, 2, 1, 7,
            OfficeClaimOrigin.POLITY_APPOINTMENT, 10, acceptedTurn = 11, credentialIds = credentials)
        val tenures = mutableListOf(tenure)
        val central = mutableSetOf("central-1")
        val result = ready(facts.copy(jurisdiction = jurisdiction.copy(administrativeCountyIds = known(admins),
            canonicalJoin = known(join), liveOwners = known(live), warehouseConnectedCountyIds = known(warehouse)),
            tenures = known(tenures), centralOfficeIds = known(central)))
        admins.clear()
        join.clear()
        live.clear()
        warehouse.clear()
        credentials.clear()
        tenures.clear()
        central.clear()
        assertEquals(setOf(100, 101), result.context.jurisdiction.countyIds)
        assertEquals(setOf(100), result.context.jurisdiction.warehouseConnectedCountyIds)
        assertEquals(listOf("credential-1"), result.context.activeTenures.single().credentialIds)
        assertEquals(setOf("central-1"), result.context.centralOfficeIds)
        assertFailsWith<UnsupportedOperationException> { (result.context.jurisdiction.countyIds as MutableSet<Int>).clear() }
        assertFailsWith<UnsupportedOperationException> { (result.context.activeTenures as MutableList<OfficeTenure>).clear() }
    }

    @Test
    fun `capability projection shares the exact jurisdiction values and does not write any source`() {
        val before = facts.copy()
        val capability = assertIs<OfficeJurisdictionProjection.Ready>(OfficeJurisdictionProducer.projectCapabilitySources(jurisdiction))
        assertEquals(ready().context.jurisdiction, capability.snapshot)
        assertEquals(before, facts)
        val unavailable = assertIs<OfficeJurisdictionProjection.Unavailable>(
            OfficeJurisdictionProducer.projectCapabilitySources(jurisdiction.copy(currentSeatCountyId = missing())))
        assertEquals(OfficeSourceUnavailableReason.MISSING, unavailable.sources.unavailable[OfficeSourceKey.CURRENT_SEAT])
    }
}
