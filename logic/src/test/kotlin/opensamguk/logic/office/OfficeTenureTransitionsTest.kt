package opensamguk.logic.office

import opensamguk.logic.input.DispatchPolicy
import opensamguk.logic.input.Phase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OfficeTenureTransitionsTest {
    private val request = OfficeAppointmentRequest(1, 2, "office.commandery-prefect", "hhs-group:109:京兆尹", 100)
    private val snapshot = OfficeJurisdictionSnapshot(request.jurisdictionId, setOf(100, 101), 100, 100,
        setOf(100, 101), setOf(100), setOf(100), emptySet())
    private val rules = OfficeRules(50, 2)

    @Test
    fun `accepted appointment remains nominal until arrival and loses power with seat`() {
        val issued = Phase(196, 1, 1)
        val offer = OfficeAppointmentFlow.issue("offer-1", request, issued, true, DispatchPolicy())
        assertFailsWith<IllegalArgumentException> { OfficeTenureTransitions.appoint("tenure-1", offer, 7, 10, 11) }
        val accepted = OfficeAppointmentFlow.respond(offer, true, issued.plus(1))
        val appointed = OfficeTenureTransitions.appoint("tenure-1", accepted, 7, 10, 11)
        assertFalse(OfficeCapabilityResolver.actualJurisdiction(appointed, snapshot, rules).effective)
        val arrived = OfficeTenureTransitions.arrive(appointed, snapshot, 12)
        assertEquals(arrived, OfficeTenureTransitions.arrive(arrived, snapshot, 13))
        assertTrue(OfficeCapabilityResolver.actualJurisdiction(arrived, snapshot, rules).effective)
        assertFalse(OfficeCapabilityResolver.actualJurisdiction(arrived,
            snapshot.copy(ownedCountyIds = setOf(101)), rules).effective)
        val ended = OfficeTenureTransitions.end(arrived, 14)
        assertFalse(ended.isActive)
        assertFalse(OfficeCapabilityResolver.actualJurisdiction(ended, snapshot, rules).effective)
    }

    @Test
    fun `arrival requires person at owned seat`() {
        val issued = Phase(196, 1, 1)
        val offer = OfficeAppointmentFlow.issue("offer-1", request, issued, false, DispatchPolicy())
        val appointed = OfficeTenureTransitions.appoint("tenure-1", offer, 7, 10, 10)
        assertFailsWith<IllegalArgumentException> {
            OfficeTenureTransitions.arrive(appointed, snapshot.copy(holderCountyId = 101), 11)
        }
        assertFailsWith<IllegalArgumentException> {
            OfficeTenureTransitions.arrive(appointed, snapshot.copy(ownedCountyIds = setOf(101)), 11)
        }
    }
}
