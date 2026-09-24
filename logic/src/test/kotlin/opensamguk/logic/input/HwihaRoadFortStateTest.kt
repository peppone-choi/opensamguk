package opensamguk.logic.input

import kotlin.test.*
import opensamguk.logic.world.*

class HwihaRoadFortStateTest {
    private val passage = StrategicEdgeStateSnapshot("qa", "a".repeat(64), mapOf(
        "road-a" to StrategicEdgeState(true, false, false, 1),
        "road-b" to StrategicEdgeState(true, false, false, 1)))
    private val fort = HwihaRoadFort(HwihaRoadFort.siteId("road-a", 7, 8), "road-a", "A", 7, 8,
        ownerNationId = 2, wall = 100, garrison = 0)

    @Test fun `hostile fort closes only its road and ownership is independent of county`() {
        val occupied = HwihaRoadFortState.forNation(passage, listOf(fort), setOf(2))
        assertFalse(occupied.edgeStates.getValue("road-a").active)
        assertTrue(occupied.edgeStates.getValue("road-b").active)
        assertEquals(passage, HwihaRoadFortState.forNation(passage, listOf(fort), emptySet()))
        val captured = fort.copy(ownerNationId = 1, wall = 50)
        assertEquals(passage, HwihaRoadFortState.forNation(passage, listOf(captured), setOf(2)))
    }

    @Test fun `siege and captured fort survive a strict state round trip`() {
        val besieged = fort.copy(besiegerNationId = 1, besiegerGeneralId = 12, siegeProgress = 68)
        val value = HwihaRoadFortState.toMetaValue(listOf(besieged))
        assertEquals(listOf(besieged), HwihaRoadFortState.read(mapOf(HwihaRoadFortState.META_KEY to value)))
        assertFailsWith<IllegalArgumentException> {
            HwihaRoadFortState.read(mapOf(HwihaRoadFortState.META_KEY to
                (value + ("forts" to listOf(mapOf("id" to fort.id))))))
        }
    }
}
