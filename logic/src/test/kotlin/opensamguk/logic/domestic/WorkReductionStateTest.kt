package opensamguk.logic.domestic

import kotlin.test.*
import opensamguk.logic.input.Phase

class WorkReductionStateTest {
    private val now = Phase(200, 1, 2)
    private val order = WorkReductionOrder("reduce-1", 1, 42, 1, now, now.plus(0))

    @Test fun `pending and resolved reductions round trip with their replay ids`() {
        val pending = WorkReductionState(pending = order)
        assertEquals(pending, WorkReductionState.read(mapOf(WorkReductionState.META_KEY to pending.toMetaValue())))
        val done = pending.resolve(now.plus(1), null)
        assertNull(done.pending)
        assertEquals(setOf("reduce-1"), done.processedRequestIds)
        assertEquals(done, WorkReductionState.read(mapOf(WorkReductionState.META_KEY to done.toMetaValue())))
        val next = done.copy(pending = order.copy(requestId = "reduce-2", requestedAt = now.plus(1)))
        assertEquals(setOf("reduce-1", "reduce-2"), next.resolve(now.plus(2), "NOT_COUNTY_AUTHORITY").processedRequestIds)
    }

    @Test fun `invalid state and same phase resolution fail closed`() {
        assertFailsWith<IllegalArgumentException> { WorkReductionState(pending = order).resolve(now, null) }
        assertFailsWith<IllegalArgumentException> { order.copy(completedAt = now.plus(1)) }
        assertFailsWith<IllegalArgumentException> { WorkReductionState(pending = order, processedRequestIds = setOf(order.requestId)) }
        val valid = WorkReductionState(pending = order).toMetaValue()
        for (invalid in listOf(valid + ("version" to 2), valid + ("unknown" to true),
            valid + ("processedRequestIds" to listOf("duplicate", "duplicate")))) {
            assertFailsWith<IllegalArgumentException> { WorkReductionState.read(mapOf(WorkReductionState.META_KEY to invalid)) }
        }
    }

    @Test fun `reduce arguments contain only the actual county fortification`() {
        val parsed = assertNotNull(DomesticInput.parseReduce(1, """{"work":"FORTIFICATION","countyId":10}"""))
        assertEquals("""{"countyId":10,"work":"FORTIFICATION"}""", DomesticInput.canonicalJson(parsed))
        for (raw in listOf("""{"countyId":10,"work":"ROAD"}""", """{"countyId":"10","work":"FORTIFICATION"}""",
            """{"countyId":10,"work":"FORTIFICATION","edgeId":"road"}""",
            """{"countyId":10,"work":"FORTIFICATION","row":1,"col":2}""",
            """{"countyId":10,"countyId":11,"work":"FORTIFICATION"}""", """{"countyId":0,"work":"FORTIFICATION"}""")) {
            assertNull(DomesticInput.parseReduce(1, raw), raw)
        }
        // The general work producer still accepts its existing road-fort arguments.
        assertNotNull(DomesticInput.parseWork(1, """{"countyId":10,"work":"FORTIFICATION","edgeId":"road","row":1,"col":2}"""))
    }
}
