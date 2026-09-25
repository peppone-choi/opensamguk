package opensamguk.logic.office

import opensamguk.logic.input.DispatchPolicy
import opensamguk.logic.input.Phase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class OfficeAppointmentFlowTest {
    private val catalog = OfficeCatalog.loadClasspath()
    private val rules = OfficeRules.loadClasspath()
    private val request = OfficeAppointmentRequest(1, 2, "office.commandery-prefect", "hhs-group:109:京兆尹", 100)
    private val snapshot = OfficeJurisdictionSnapshot(
        request.jurisdictionId, setOf(100, 101), 100, 100, setOf(100, 101), setOf(100), setOf(100), emptySet())
    private val context = OfficeAppointmentContext(1, 7, true, 2, 7, true, snapshot, emptyList(), setOf("office.central.chancellor"))

    @Test
    fun `ruler can offer local office to human candidate`() {
        assertEquals(OfficeAppointmentAssessment.Allowed(true), OfficeAppointmentRules.assess(request, context, catalog, rules))
        assertEquals(OfficeAppointmentAssessment.Denied(OfficeAppointmentFailure.NOT_RULER),
            OfficeAppointmentRules.assess(request, context.copy(issuerIsRuler = false), catalog, rules))
        assertEquals(OfficeAppointmentAssessment.Denied(OfficeAppointmentFailure.CANDIDATE_OUTSIDE_NATION),
            OfficeAppointmentRules.assess(request, context.copy(candidateNationId = 8), catalog, rules))
    }

    @Test
    fun `central title requires edict and county office stays a placement`() {
        val central = request.copy(officeId = "office.central.chancellor")
        assertEquals(OfficeAppointmentAssessment.Denied(OfficeAppointmentFailure.CENTRAL_REQUIRES_EDICT),
            OfficeAppointmentRules.assess(central, context, catalog, rules))
        val county = request.copy(officeId = "office.county-magistrate")
        assertEquals(OfficeAppointmentAssessment.Denied(OfficeAppointmentFailure.COUNTY_OFFICE_USES_PLACEMENT),
            OfficeAppointmentRules.assess(county, context, catalog, rules))
    }

    @Test
    fun `unowned seat and occupied office fail before expense`() {
        assertEquals(OfficeAppointmentAssessment.Denied(OfficeAppointmentFailure.SEAT_NOT_OWNED),
            OfficeAppointmentRules.assess(request, context.copy(jurisdiction = snapshot.copy(ownedCountyIds = setOf(101))), catalog, rules))
        val occupied = OfficeTenure("existing", request.officeId, request.jurisdictionId, 3, 1, 7,
            OfficeClaimOrigin.POLITY_APPOINTMENT, 1, acceptedTurn = 2)
        assertEquals(OfficeAppointmentAssessment.Denied(OfficeAppointmentFailure.OFFICE_OCCUPIED),
            OfficeAppointmentRules.assess(request, context.copy(activeTenures = listOf(occupied)), catalog, rules))
    }

    @Test
    fun `human reply and deadline follow dispatch response timing`() {
        val policy = DispatchPolicy()
        val issued = Phase(196, 1, 1)
        val offer = OfficeAppointmentFlow.issue("request-1", request, issued, true, policy)
        assertEquals(OfficeOfferStatus.PENDING, offer.status)
        assertEquals(issued.plus(policy.responsePhases), offer.dueAt)
        assertEquals(OfficeOfferStatus.REFUSED, OfficeAppointmentFlow.respond(offer, false, issued.plus(1)).status)
        assertEquals(OfficeOfferStatus.ACCEPTED, OfficeAppointmentFlow.respond(offer, false, offer.dueAt).status)
        assertEquals(OfficeOfferStatus.ACCEPTED, OfficeAppointmentFlow.issue("request-2", request, issued, false, policy).status)
    }

    @Test
    fun `offer meta roundtrip is strict`() {
        val offer = OfficeAppointmentFlow.issue("request-1", request, Phase(196, 1, 1), true, DispatchPolicy())
        assertEquals(offer, OfficeAppointmentOffer.read(mapOf(OfficeAppointmentOffer.META_KEY to offer.toMetaValue())))
        val damaged = offer.toMetaValue() + ("version" to 9)
        assertFailsWith<IllegalArgumentException> {
            OfficeAppointmentOffer.read(mapOf(OfficeAppointmentOffer.META_KEY to damaged))
        }
    }
}
