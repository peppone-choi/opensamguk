package opensamguk.logic.input

import kotlin.test.*

class HwihaDispatchRulesTest {
    private val now = HwihaPhase(200, 12, 2)
    private val order = HwihaDispatchState("dispatch-1", 1, 2, 1, 10, now, now.plus(12))
    private fun state(targetMeta: Map<String, Any?> = emptyMap()) = HwihaDispatchProjection(RuleProfile.HWIHA,
        listOf(DispatchPerson(1, 1, true, false, emptyMap()), DispatchPerson(2, 1, false, true, targetMeta)),
        listOf(DispatchRetainer(1, 1, 2, 50)), listOf(DispatchCounty(10, 1)))
    private val request = DispatchRequest(1, 2, 10)
    private fun failure(value: DispatchAssessment) = assertIs<DispatchAssessment.Rejected>(value).reason

    @Test fun `phase arithmetic crosses month and year exactly`() {
        assertEquals(HwihaPhase(201, 4, 2), now.plus(12))
        assertEquals(HwihaPhase(201, 1, 1), HwihaPhase(200, 12, 3).plus(1))
        assertTrue(now < now.plus(1))
        assertFailsWith<IllegalArgumentException> { HwihaPhase(Int.MAX_VALUE, 12, 3).plus(1) }
    }
    @Test fun `private state round trips strictly without coercing corrupt dates or identifiers`() {
        assertEquals(order, HwihaDispatchState.read(mapOf(HwihaDispatchState.META_KEY to order.toMetaValue())))
        for (bad in listOf(null, order.toMetaValue() + ("unknown" to true), order.toMetaValue() + ("targetId" to 2.0),
            order.toMetaValue() + ("issuedAt" to mapOf("year" to 200, "month" to 13, "phase" to 1)))) {
            assertFailsWith<IllegalArgumentException> { HwihaDispatchState.read(mapOf(HwihaDispatchState.META_KEY to bad)) }
        }
        assertNull(HwihaDispatchState.read(emptyMap()))
    }
    @Test fun `only direct human subordinate and currently owned administrative county qualify`() {
        assertIs<DispatchAssessment.Eligible>(HwihaDispatchRules.assess(request, state()))
        assertEquals(DispatchFailure.WRONG_RULE_PROFILE, failure(HwihaDispatchRules.assess(request, state().copy(profile=RuleProfile.SAMMO))))
        assertEquals(DispatchFailure.NOT_DIRECT_RETAINER, failure(HwihaDispatchRules.assess(request, state().copy(retainers=emptyList()))))
        assertEquals(DispatchFailure.INVALID_COUNTY, failure(HwihaDispatchRules.assess(request, state().copy(counties=emptyList()))))
        assertEquals(DispatchFailure.INVALID_COUNTY, failure(HwihaDispatchRules.assess(request, state().copy(counties=listOf(DispatchCounty(10,2))))))
        assertEquals(DispatchFailure.TARGET_NOT_HUMAN, failure(HwihaDispatchRules.assess(request,
            state().copy(people=state().people.map { if(it.id==2) it.copy(isHuman=false) else it }))))
    }
    @Test fun `pending orders cannot be overwritten and reply rechecks relationship and county`() {
        val pending = state(mapOf(HwihaDispatchState.META_KEY to order.toMetaValue()))
        assertEquals(DispatchFailure.ALREADY_PENDING, failure(HwihaDispatchRules.assess(request,pending)))
        assertIs<DispatchAssessment.Eligible>(HwihaDispatchRules.assessReply(2,"dispatch-1",pending))
        assertEquals(DispatchFailure.NOT_RECIPIENT, failure(HwihaDispatchRules.assessReply(2,"forged",pending)))
        assertEquals(DispatchFailure.NOT_DIRECT_RETAINER, failure(HwihaDispatchRules.assessReply(2,"dispatch-1",pending.copy(retainers=emptyList()))))
        assertEquals(DispatchFailure.INVALID_COUNTY, failure(HwihaDispatchRules.assessReply(2,"dispatch-1",pending.copy(counties=emptyList()))))
        val closed = state(mapOf(HwihaDispatchState.META_KEY to order.copy(status=DispatchStatus.REFUSED).toMetaValue()))
        assertEquals(DispatchFailure.ALREADY_RESOLVED, failure(HwihaDispatchRules.assessReply(2,"dispatch-1",closed)))
    }
    @Test fun `county already assigned or reserved by another person cannot be promised twice`() {
        val assigned = HwihaCountyAssignment("previous",1,1,10)
        for (meta in listOf(mapOf(HwihaCountyAssignment.META_KEY to assigned.toMetaValue()),
            mapOf(HwihaDispatchState.META_KEY to order.copy(targetId=3).toMetaValue()))) {
            val snapshot=state().let { it.copy(people=it.people+DispatchPerson(3,1,false,true,meta)) }
            assertEquals(DispatchFailure.COUNTY_OCCUPIED,failure(HwihaDispatchRules.assess(request,snapshot)))
        }
    }
    @Test fun `captured county no longer belongs to the previous nations assignment`() {
        val old = HwihaCountyAssignment("old-order", 7, 2, 10)
        val snapshot = state().let { it.copy(people = it.people + DispatchPerson(3, 2, false, true,
            mapOf(HwihaCountyAssignment.META_KEY to old.toMetaValue()))) }
        assertIs<DispatchAssessment.Eligible>(HwihaDispatchRules.assess(request, snapshot))
    }

    @Test fun `refusal policy is checked before deadline but never charged by a late refusal`() {
        val pending=state(mapOf(HwihaDispatchState.META_KEY to order.toMetaValue()))
        val reply=DispatchReplyRequest(2,"dispatch-1",false)
        assertEquals(DispatchFailure.POLICY_UNAVAILABLE,failure(HwihaDispatchRules.assessReply(reply,now,pending)))
        assertIs<DispatchAssessment.Eligible>(HwihaDispatchRules.assessReply(reply,order.dueAt,pending))
        val closed=state(mapOf(HwihaDispatchState.META_KEY to order.copy(status=DispatchStatus.REFUSED).toMetaValue()))
        assertEquals(DispatchFailure.ALREADY_RESOLVED,failure(HwihaDispatchRules.assessReply(reply,now,closed)))
    }

}
