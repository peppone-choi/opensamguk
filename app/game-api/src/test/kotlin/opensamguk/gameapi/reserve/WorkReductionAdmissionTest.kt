package opensamguk.gameapi.reserve

import kotlin.test.*
import opensamguk.gameapi.read.*
import opensamguk.logic.domestic.*
import opensamguk.logic.economy.CountyWarehouse
import opensamguk.logic.economy.Resources
import opensamguk.logic.input.Phase
import opensamguk.logic.input.RuleProfile
import org.mockito.Mockito.*

class WorkReductionAdmissionTest {
    private val now = Phase(200, 1, 2)
    private val actor = DomesticPerson(1, "Lord", 1, true, 0, 12, 60, 60, 60, 60, 60, "A", false,
        mapOf("lord" to true))
    private val completed = CompletedWork(DomesticWork.FORTIFICATION, Phase(200, 1, 1))
    private val stock = Resources(50, 60, 70, 80, 90)
    private val meta = mapOf(CountyWarehouse.META_KEY to CountyWarehouse(10, 0, stock).toMetaValue(),
        CountyWorks.META_KEY to CountyWorks(null, listOf(completed)).toMetaValue())

    private fun snapshot(value: Map<String, Any?> = meta, person: DomesticPerson = actor) = DomesticSnapshot(
        DomesticProjection(RuleProfile.HWIHA, now, listOf(person), emptyList(),
            listOf(DomesticCounty(10, "County", 1, "A", "郡", value)),
            listOf(DomesticNation(1, "Nation", 10, emptyMap())), setOf("A")), warehouseStocks = mapOf(10 to stock))

    @Test fun `admission canonicalizes only the completed county fort and rejects foreign or busy requests`() {
        val reader = mock(DomesticReader::class.java)
        `when`(reader.snapshot()).thenReturn(snapshot())
        val admission = DomesticAdmission(reader)
        assertEquals("""{"countyId":10,"work":"FORTIFICATION"}""", admission.canonicalArguments(1, 42,
            "work.reduce", """{"work":"FORTIFICATION","countyId":10}"""))
        verify(reader).requireOwner(1, 42L)
        doThrow(DomesticForbidden()).`when`(reader).requireOwner(1, 43L)
        assertFailsWith<DomesticForbidden> { admission.canonicalArguments(1, 43, "work.reduce",
            """{"countyId":10,"work":"FORTIFICATION"}""") }
        assertEquals("INVALID_REQUEST", assertFailsWith<AdmissionDenied> {
            admission.canonicalArguments(1, 42, "work.reduce", """{"countyId":10,"work":"FORTIFICATION","edgeId":"road"}""")
        }.code)
        val pending = WorkReductionState(pending = WorkReductionOrder("queued", 1, 42, 1, now, completed.completedAt))
        `when`(reader.snapshot()).thenReturn(snapshot(meta + (WorkReductionState.META_KEY to pending.toMetaValue())))
        assertEquals("WORK_IN_PROGRESS", assertFailsWith<AdmissionDenied> {
            admission.canonicalArguments(1, 42, "work.reduce", """{"countyId":10,"work":"FORTIFICATION"}""")
        }.code)
        `when`(reader.snapshot()).thenReturn(snapshot(person = actor.copy(officerLevel = 1, meta = emptyMap())))
        assertEquals("NOT_COUNTY_AUTHORITY", assertFailsWith<AdmissionDenied> {
            admission.canonicalArguments(1, 42, "work.reduce", """{"countyId":10,"work":"FORTIFICATION"}""")
        }.code)
    }

    @Test fun `works options and persisted resolution use the same reduction rule`() {
        val available = DomesticViews.works(1, snapshot()).counties.single()
        assertTrue(available.reducible); assertNull(available.reduceBlocked); assertNull(available.reduction)
        val pending = WorkReductionState(pending = WorkReductionOrder("queued", 1, 42, 1, now, completed.completedAt))
        val waiting = DomesticViews.works(1, snapshot(meta + (WorkReductionState.META_KEY to pending.toMetaValue()))).counties.single()
        assertFalse(waiting.reducible); assertEquals("WORK_IN_PROGRESS", waiting.reduceBlocked!!.code)
        assertEquals("queued", waiting.reduction!!.requestId); assertEquals("PENDING", waiting.reduction!!.status)
        val done = pending.resolve(now.plus(1), null)
        val doneMeta = meta + (WorkReductionState.META_KEY to done.toMetaValue()) +
            (CountyWorks.META_KEY to CountyWorks(null, emptyList()).toMetaValue())
        val read = DomesticViews.works(1, snapshot(doneMeta)).counties.single()
        assertTrue(read.completed.isEmpty()); assertFalse(read.reducible)
        assertEquals("WORK_NOT_COMPLETED", read.reduceBlocked!!.code)
        assertEquals("APPLIED", read.reduction!!.status); assertEquals(now.plus(1), read.reduction!!.resolvedAt)
        assertEquals(available.warehouse, read.warehouse)
        val rejected = pending.resolve(now.plus(1), "NOT_COUNTY_AUTHORITY")
        val denied = DomesticViews.works(1, snapshot(meta + (WorkReductionState.META_KEY to rejected.toMetaValue()))).counties.single()
        assertEquals("REJECTED", denied.reduction!!.status)
        assertEquals("NOT_COUNTY_AUTHORITY", denied.reduction!!.reason!!.code)
    }
}
