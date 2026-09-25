package opensamguk.logic.domestic

import kotlin.test.*
import opensamguk.logic.economy.HwihaResources
import opensamguk.logic.input.HwihaPhase

class DomesticStateTest {
    private val now = HwihaPhase(200, 1, 1)
    private fun <T> roundTrip(key: String, value: Map<String, Any?>, read: (Map<String, Any?>) -> T?): T? = read(mapOf(key to value))

    @Test fun `placement codec round trips and rejects corrupt shapes`() {
        val order = PlacementOrder("r1", 1, 4, PlacementPost.MAGISTRATE, PlacementTarget.County(10), now)
        val state = PlacementState(ActivePlacement(order, now, now.plus(2)),
            PlacementOrder("r2", 1, 4, PlacementPost.SCOUT, PlacementTarget.Province("p9"), now.plus(3)))
        assertEquals(state, roundTrip(PlacementState.META_KEY, state.toMetaValue(), PlacementState::read))
        assertTrue(state.claimsMagistracy(10))
        assertFalse(state.claimsMagistracy(11))
        assertNull(PlacementState.read(emptyMap()))
        val valid = state.toMetaValue()
        for (bad in listOf(valid + ("version" to 2), valid - "pending", valid + ("x" to 1), mapOf("version" to 1, "active" to null, "pending" to null)))
            assertFailsWith<IllegalArgumentException> { PlacementState.read(mapOf(PlacementState.META_KEY to bad)) }
        // A release never becomes an active placement.
        assertFailsWith<IllegalArgumentException> {
            ActivePlacement(PlacementOrder("r1", 1, 4, PlacementPost.NONE, PlacementTarget.None, now), now, null)
        }
    }

    @Test fun `policy slots and commandery or corps collections stay sorted and typed`() {
        val slot = PolicySlot(PolicySetting("AGRICULTURE", "r1", 1, now), PolicyOrder(null, "r2", 1, now.plus(1)))
        val county = CountyPolicyState(slot, PolicyApplication(now, "AGRICULTURE", "EMPTY", "APPLIED"))
        assertEquals(county, roundTrip(CountyPolicyState.META_KEY, county.toMetaValue(), CountyPolicyState::read))
        assertEquals(PolicySlot(null, null), slot.activate(now.plus(2)))
        val commanderies = CommanderyPolicies(emptyList()).with("乙郡", slot).with("甲郡", slot)
        assertEquals(listOf("乙郡", "甲郡").sorted(), commanderies.entries.map { it.commanderyId })
        assertEquals(commanderies, roundTrip(CommanderyPolicies.META_KEY, commanderies.toMetaValue(), CommanderyPolicies::read))
        assertEquals(1, commanderies.with("甲郡", PolicySlot(null, null)).entries.size)
        val corps = CorpsPolicyAssignments(emptyList()).with("o2", 9, PolicySlot(null, PolicyOrder("INTERCEPT", "r3", 1, now)))
            .with("o1", 3, PolicySlot(PolicySetting("EVADE", "r4", 1, now), null))
        assertEquals(listOf(3, 9), corps.entries.map { it.commanderGeneralId })
        assertEquals(corps, roundTrip(CorpsPolicyAssignments.META_KEY, corps.toMetaValue(), CorpsPolicyAssignments::read))
        // A county policy name cannot be stored as a corps policy (or the reverse).
        assertFailsWith<IllegalArgumentException> { CorpsPolicyAssignment("o1", 3, PolicySlot(PolicySetting("LEVY", "r", 1, now), null)) }
        assertFailsWith<IllegalArgumentException> { CommanderyPolicy("甲郡", PolicySlot(PolicySetting("EVADE", "r", 1, now), null)) }
    }

    @Test fun `works codec keeps long resources and refuses duplicate completion`() {
        val active = ActiveWork(DomesticWork.ROAD, "w1", 1, now, 100, 600, HwihaResources(3_000_000_000L, 0, 0, 2000, 0),
            HwihaResources(500_000_000L, 0, 0, 333, 0), now, null)
        val works = CountyWorks(active, listOf(CompletedWork(DomesticWork.IRRIGATION, now)))
        assertEquals(works, roundTrip(CountyWorks.META_KEY, works.toMetaValue(), CountyWorks::read))
        assertFailsWith<IllegalArgumentException> { CountyWorks(active.copy(work = DomesticWork.IRRIGATION),
            listOf(CompletedWork(DomesticWork.IRRIGATION, now))) }
        assertFailsWith<IllegalArgumentException> { active.copy(charged = HwihaResources(3_000_000_001L, 0, 0, 0, 0)) }
        val monthly = CountyMonthly("0200-01", CountyIndicators(1, 2, 3, 4, 5, 6, 7))
        assertEquals(monthly, roundTrip(CountyMonthly.META_KEY, monthly.toMetaValue(), CountyMonthly::read))
        assertEquals(listOf("agriculture", "trust"), CountyIndicators(1, 3, 3, 4, 9, 6, 7).risenSince(monthly.indicators))
    }
}
