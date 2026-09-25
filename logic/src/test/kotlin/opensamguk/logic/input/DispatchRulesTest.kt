package opensamguk.logic.input

import kotlin.test.*

class DispatchRulesTest {
    private val now = Phase(200, 12, 2)
    private val order = DispatchState("dispatch-1", 1, 2, 1, 10, now, now.plus(12))
    private fun state(targetMeta: Map<String, Any?> = emptyMap()) = DispatchProjection(RuleProfile.HWIHA,
        listOf(DispatchPerson(1, 1, true, false, emptyMap()), DispatchPerson(2, 1, false, true, targetMeta)),
        listOf(DispatchRetainer(1, 1, 2, 50)), listOf(DispatchCounty(10, 1)))
    private val request = DispatchRequest(1, 2, 10)
    private fun failure(value: DispatchAssessment) = assertIs<DispatchAssessment.Rejected>(value).reason

    @Test fun `phase arithmetic crosses month and year exactly`() {
        assertEquals(Phase(201, 4, 2), now.plus(12))
        assertEquals(Phase(201, 1, 1), Phase(200, 12, 3).plus(1))
        assertTrue(now < now.plus(1))
        assertFailsWith<IllegalArgumentException> { Phase(Int.MAX_VALUE, 12, 3).plus(1) }
    }
    @Test fun `private state round trips strictly without coercing corrupt dates or identifiers`() {
        assertEquals(order, DispatchState.read(mapOf(DispatchState.META_KEY to order.toMetaValue())))
        for (bad in listOf(null, order.toMetaValue() + ("unknown" to true), order.toMetaValue() + ("targetId" to 2.0),
            order.toMetaValue() + ("issuedAt" to mapOf("year" to 200, "month" to 13, "phase" to 1)))) {
            assertFailsWith<IllegalArgumentException> { DispatchState.read(mapOf(DispatchState.META_KEY to bad)) }
        }
        assertNull(DispatchState.read(emptyMap()))
    }
    @Test fun `only direct human subordinate and currently owned administrative county qualify`() {
        assertIs<DispatchAssessment.Eligible>(DispatchRules.assess(request, state()))
        assertEquals(DispatchFailure.WRONG_RULE_PROFILE, failure(DispatchRules.assess(request, state().copy(profile=RuleProfile.SAMMO))))
        assertEquals(DispatchFailure.NOT_DIRECT_RETAINER, failure(DispatchRules.assess(request, state().copy(retainers=emptyList()))))
        assertEquals(DispatchFailure.INVALID_COUNTY, failure(DispatchRules.assess(request, state().copy(counties=emptyList()))))
        assertEquals(DispatchFailure.INVALID_COUNTY, failure(DispatchRules.assess(request, state().copy(counties=listOf(DispatchCounty(10,2))))))
        assertEquals(DispatchFailure.TARGET_NOT_HUMAN, failure(DispatchRules.assess(request,
            state().copy(people=state().people.map { if(it.id==2) it.copy(isHuman=false) else it }))))
    }
    @Test fun `pending orders cannot be overwritten and reply rechecks relationship and county`() {
        val pending = state(mapOf(DispatchState.META_KEY to order.toMetaValue()))
        assertEquals(DispatchFailure.ALREADY_PENDING, failure(DispatchRules.assess(request,pending)))
        assertIs<DispatchAssessment.Eligible>(DispatchRules.assessReply(2,"dispatch-1",pending))
        assertEquals(DispatchFailure.NOT_RECIPIENT, failure(DispatchRules.assessReply(2,"forged",pending)))
        assertEquals(DispatchFailure.NOT_DIRECT_RETAINER, failure(DispatchRules.assessReply(2,"dispatch-1",pending.copy(retainers=emptyList()))))
        assertEquals(DispatchFailure.INVALID_COUNTY, failure(DispatchRules.assessReply(2,"dispatch-1",pending.copy(counties=emptyList()))))
        val closed = state(mapOf(DispatchState.META_KEY to order.copy(status=DispatchStatus.REFUSED).toMetaValue()))
        assertEquals(DispatchFailure.ALREADY_RESOLVED, failure(DispatchRules.assessReply(2,"dispatch-1",closed)))
    }
    @Test fun `county already assigned or reserved by another person cannot be promised twice`() {
        val assigned = CountyAssignment("previous",1,1,10)
        for (meta in listOf(mapOf(CountyAssignment.META_KEY to assigned.toMetaValue()),
            mapOf(DispatchState.META_KEY to order.copy(targetId=3).toMetaValue()))) {
            val snapshot=state().let { it.copy(people=it.people+DispatchPerson(3,1,false,true,meta)) }
            assertEquals(DispatchFailure.COUNTY_OCCUPIED,failure(DispatchRules.assess(request,snapshot)))
        }
    }
    @Test fun `captured county no longer belongs to the previous nations assignment`() {
        val old = CountyAssignment("old-order", 7, 2, 10)
        val snapshot = state().let { it.copy(people = it.people + DispatchPerson(3, 2, false, true,
            mapOf(CountyAssignment.META_KEY to old.toMetaValue()))) }
        assertIs<DispatchAssessment.Eligible>(DispatchRules.assess(request, snapshot))
    }

    @Test fun `refusal policy is checked before deadline but never charged by a late refusal`() {
        val pending=state(mapOf(DispatchState.META_KEY to order.toMetaValue()))
        val reply=DispatchReplyRequest(2,"dispatch-1",false)
        assertEquals(DispatchFailure.POLICY_UNAVAILABLE,failure(DispatchRules.assessReply(reply,now,pending)))
        assertIs<DispatchAssessment.Eligible>(DispatchRules.assessReply(reply,order.dueAt,pending))
        val closed=state(mapOf(DispatchState.META_KEY to order.copy(status=DispatchStatus.REFUSED).toMetaValue()))
        assertEquals(DispatchFailure.ALREADY_RESOLVED,failure(DispatchRules.assessReply(reply,now,closed)))
    }

    @Test fun `accepted assignment survives a newer pending order but rechecks current bond and county`() {
        val assignment = CountyAssignment("accepted-old",1,1,10)
        val pending = state(mapOf(DispatchState.META_KEY to order.toMetaValue()))
        assertIs<DispatchAssessment.Eligible>(DispatchRules.assessAssignment(2,assignment,pending))
        assertEquals(DispatchFailure.NOT_DIRECT_RETAINER,failure(DispatchRules.assessAssignment(2,assignment,
            pending.copy(retainers=emptyList()))))
        assertEquals(DispatchFailure.INVALID_COUNTY,failure(DispatchRules.assessAssignment(2,assignment,
            pending.copy(counties=listOf(DispatchCounty(10,2))))))
        assertEquals(DispatchFailure.RELATION_CHANGED,failure(DispatchRules.assessAssignment(2,
            assignment.copy(nationId=2),pending)))
    }

}
