package opensamguk.logic.office

import opensamguk.logic.input.DispatchPolicy
import opensamguk.logic.input.Phase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class OfficeAppointmentExecutionTest {
    private val catalog = OfficeCatalog.loadClasspath()
    private val rules = OfficeRules.loadClasspath()
    private val request = OfficeAppointmentRequest(1, 2, "office.commandery-prefect", "hhs-group:109:京兆尹", 100)
    private val snapshot = OfficeJurisdictionSnapshot(request.jurisdictionId, setOf(100, 101), 100, 100,
        setOf(100, 101), setOf(100), setOf(100), emptySet())
    private val context = OfficeAppointmentContext(1, 7, true, 2, 7, true, snapshot, emptyList(), emptySet())
    private val offered = OfficeAppointmentFlow.issue("offer-1", request, Phase(196, 1, 1), true, DispatchPolicy())

    @Test
    fun `execution uses the same denial as precheck on a changed seat`() {
        val stale = context.copy(jurisdiction = snapshot.copy(ownedCountyIds = setOf(101)))
        val accepted = offered.copy(status = OfficeOfferStatus.ACCEPTED)
        val precheck = assertIs<OfficeAppointmentAssessment.Denied>(OfficeAppointmentRules.assess(request, stale, catalog, rules))
        val execution = assertIs<OfficeAppointmentCompletion.Denied>(
            OfficeAppointmentExecution.complete("tenure-1", accepted, stale, catalog, rules, 10, 11))
        assertEquals(precheck.reason, execution.reason)
    }

    @Test
    fun `pending offer cannot create tenure and accepted offer can`() {
        assertEquals(OfficeAppointmentCompletion.Denied(OfficeAppointmentFailure.OFFER_NOT_ACCEPTED),
            OfficeAppointmentExecution.complete("tenure-1", offered, context, catalog, rules, 10, 11))
        val accepted = offered.copy(status = OfficeOfferStatus.ACCEPTED)
        val applied = assertIs<OfficeAppointmentCompletion.Appointed>(
            OfficeAppointmentExecution.complete("tenure-1", accepted, context, catalog, rules, 10, 11))
        assertEquals(2, applied.tenure.holderId)
        assertEquals(7, applied.tenure.nationId)
        assertEquals(OfficeAppointmentCompletion.Denied(OfficeAppointmentFailure.DUPLICATE_TENURE_ID),
            OfficeAppointmentExecution.complete("tenure-1", accepted,
                context.copy(activeTenures = listOf(applied.tenure.copy(endedTurn = 12))), catalog, rules, 13, 14))
    }

    @Test
    fun `dismissal reruns ruler and tenure checks`() {
        val tenure = OfficeTenure("tenure-1", request.officeId, request.jurisdictionId, 2, 1, 7,
            OfficeClaimOrigin.POLITY_APPOINTMENT, 10, acceptedTurn = 11)
        assertEquals(OfficeDismissalCompletion.Denied(OfficeAppointmentFailure.NOT_RULER),
            OfficeAppointmentExecution.dismiss(7, false, tenure.id, listOf(tenure), 12))
        assertEquals(OfficeDismissalCompletion.Denied(OfficeAppointmentFailure.TENURE_NOT_FOUND),
            OfficeAppointmentExecution.dismiss(8, true, tenure.id, listOf(tenure), 12))
        val dismissed = assertIs<OfficeDismissalCompletion.Dismissed>(
            OfficeAppointmentExecution.dismiss(7, true, tenure.id, listOf(tenure), 12))
        assertEquals(12L, dismissed.tenure.endedTurn)
    }
}
