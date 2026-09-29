package opensamguk.logic.office

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OfficeLifecycleTest {
    private fun tenure(id: String, holderId: Int, seat: Int) = OfficeTenure(
        id, "office.commandery-prefect", "hhs-group:109:京兆尹", holderId, 1, 7,
        OfficeClaimOrigin.POLITY_APPOINTMENT, 1, acceptedTurn = 2, assumedTurn = 3, seatCountyId = seat)

    @Test
    fun `death retirement and nation departure close tenure while seat loss stays nominal`() {
        val tenures = listOf(tenure("death", 2, 100), tenure("retired", 3, 101),
            tenure("departure", 4, 102), tenure("lost-seat", 5, 103))
        val facts = OfficeLifecycleFacts(
            livingGeneralIds = setOf(3, 4, 5), retiredGeneralIds = setOf(3),
            nationByGeneralId = mapOf(2 to 7, 3 to 7, 4 to 8, 5 to 7),
            countyNationById = mapOf(100 to 7, 101 to 7, 102 to 7, 103 to 8),
            previousCountyNationById = mapOf(100 to 7, 101 to 7, 102 to 7, 103 to 7),
        )
        val result = OfficeLifecycle.reconcile(tenures.reversed(), facts, 10)
        assertEquals(listOf(OfficeLifecycleReason.HOLDER_DEAD, OfficeLifecycleReason.NATION_CHANGED,
            OfficeLifecycleReason.SEAT_LOST, OfficeLifecycleReason.HOLDER_RETIRED), result.changes.map { it.reason })
        assertEquals(3, result.changes.count { it.ended })
        assertTrue(result.tenures.single { it.id == "lost-seat" }.isActive)
        assertFalse(result.tenures.single { it.id == "death" }.isActive)
        assertEquals(emptyList(), OfficeLifecycle.reconcile(result.tenures,
            facts.copy(previousCountyNationById = facts.countyNationById), 11).changes)
    }
}
