package opensamguk.logic.input

import opensamguk.logic.domestic.PlacementPost
import opensamguk.logic.domestic.DomesticWork
import opensamguk.logic.domestic.PlacementTarget

import kotlin.test.*
import opensamguk.logic.economy.HwihaResources

class HwihaDomesticStateTest {
    private val now = HwihaPhase(200, 1, 1)
    private fun <T> roundTrip(key: String, value: Map<String, Any?>, read: (Map<String, Any?>) -> T?): T? = read(mapOf(key to value))

    @Test fun `placement codec round trips and rejects corrupt shapes`() {
        val order = HwihaPlacementOrder("r1", 1, 4, PlacementPost.MAGISTRATE, PlacementTarget.County(10), now)
        val state = HwihaPlacementState(HwihaActivePlacement(order, now, now.plus(2)),
            HwihaPlacementOrder("r2", 1, 4, PlacementPost.SCOUT, PlacementTarget.Province("p9"), now.plus(3)))
        assertEquals(state, roundTrip(HwihaPlacementState.META_KEY, state.toMetaValue(), HwihaPlacementState::read))
        assertTrue(state.claimsMagistracy(10))
        assertFalse(state.claimsMagistracy(11))
        assertNull(HwihaPlacementState.read(emptyMap()))
        val valid = state.toMetaValue()
        for (bad in listOf(valid + ("version" to 2), valid - "pending", valid + ("x" to 1), mapOf("version" to 1, "active" to null, "pending" to null)))
            assertFailsWith<IllegalArgumentException> { HwihaPlacementState.read(mapOf(HwihaPlacementState.META_KEY to bad)) }
        // A release never becomes an active placement.
        assertFailsWith<IllegalArgumentException> {
            HwihaActivePlacement(HwihaPlacementOrder("r1", 1, 4, PlacementPost.NONE, PlacementTarget.None, now), now, null)
        }
    }

    @Test fun `policy slots and commandery or corps collections stay sorted and typed`() {
        val slot = HwihaPolicySlot(HwihaPolicySetting("AGRICULTURE", "r1", 1, now), HwihaPolicyOrder(null, "r2", 1, now.plus(1)))
        val county = HwihaCountyPolicyState(slot, HwihaPolicyApplication(now, "AGRICULTURE", "EMPTY", "APPLIED"))
        assertEquals(county, roundTrip(HwihaCountyPolicyState.META_KEY, county.toMetaValue(), HwihaCountyPolicyState::read))
        assertEquals(HwihaPolicySlot(null, null), slot.activate(now.plus(2)))
        val commanderies = HwihaCommanderyPolicies(emptyList()).with("乙郡", slot).with("甲郡", slot)
        assertEquals(listOf("乙郡", "甲郡").sorted(), commanderies.entries.map { it.commanderyId })
        assertEquals(commanderies, roundTrip(HwihaCommanderyPolicies.META_KEY, commanderies.toMetaValue(), HwihaCommanderyPolicies::read))
        assertEquals(1, commanderies.with("甲郡", HwihaPolicySlot(null, null)).entries.size)
        val corps = HwihaCorpsPolicies(emptyList()).with("o2", 9, HwihaPolicySlot(null, HwihaPolicyOrder("INTERCEPT", "r3", 1, now)))
            .with("o1", 3, HwihaPolicySlot(HwihaPolicySetting("EVADE", "r4", 1, now), null))
        assertEquals(listOf(3, 9), corps.entries.map { it.commanderGeneralId })
        assertEquals(corps, roundTrip(HwihaCorpsPolicies.META_KEY, corps.toMetaValue(), HwihaCorpsPolicies::read))
        // A county policy name cannot be stored as a corps policy (or the reverse).
        assertFailsWith<IllegalArgumentException> { HwihaCorpsPolicy("o1", 3, HwihaPolicySlot(HwihaPolicySetting("LEVY", "r", 1, now), null)) }
        assertFailsWith<IllegalArgumentException> { HwihaCommanderyPolicy("甲郡", HwihaPolicySlot(HwihaPolicySetting("EVADE", "r", 1, now), null)) }
    }

    @Test fun `works codec keeps long resources and refuses duplicate completion`() {
        val active = HwihaActiveWork(DomesticWork.ROAD, "w1", 1, now, 100, 600, HwihaResources(3_000_000_000L, 0, 0, 2000, 0),
            HwihaResources(500_000_000L, 0, 0, 333, 0), now, null)
        val works = HwihaCountyWorks(active, listOf(HwihaCompletedWork(DomesticWork.IRRIGATION, now)))
        assertEquals(works, roundTrip(HwihaCountyWorks.META_KEY, works.toMetaValue(), HwihaCountyWorks::read))
        assertFailsWith<IllegalArgumentException> { HwihaCountyWorks(active.copy(work = DomesticWork.IRRIGATION),
            listOf(HwihaCompletedWork(DomesticWork.IRRIGATION, now))) }
        assertFailsWith<IllegalArgumentException> { active.copy(charged = HwihaResources(3_000_000_001L, 0, 0, 0, 0)) }
        val monthly = HwihaCountyMonthly("0200-01", HwihaCountyIndicators(1, 2, 3, 4, 5, 6, 7))
        assertEquals(monthly, roundTrip(HwihaCountyMonthly.META_KEY, monthly.toMetaValue(), HwihaCountyMonthly::read))
        assertEquals(listOf("agriculture", "trust"), HwihaCountyIndicators(1, 3, 3, 4, 9, 6, 7).risenSince(monthly.indicators))
    }
}
