package opensamguk.logic.input

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import opensamguk.logic.world.StrategicNodeRef

class TravelReturnTest {
    private val assignment = CountyAssignment("dispatch-1", 4, 2, 9)
    private val province = StrategicNodeRef.LandProvince("home")

    @Test fun `return uses dispatched county even when current reference city differs`() {
        val result = TravelReturn.resolve(mapOf(CountyAssignment.META_KEY to assignment.toMetaValue()), 2) {
            assertEquals(9, it)
            province
        }
        assertEquals(province, assertIs<ReturnDestination.Ready>(result).node)
    }

    @Test fun `missing or stale assignment has an explicit reason`() {
        assertEquals(TravelFailure.NO_RETURN_ASSIGNMENT,
            assertIs<ReturnDestination.Rejected>(TravelReturn.resolve(emptyMap(), 2) { province }).reason)
        assertEquals(TravelFailure.NO_RETURN_ASSIGNMENT,
            assertIs<ReturnDestination.Rejected>(TravelReturn.resolve(
                mapOf(CountyAssignment.META_KEY to assignment.toMetaValue()), 3) { province }).reason)
        assertEquals(TravelFailure.STATE_UNAVAILABLE,
            assertIs<ReturnDestination.Rejected>(TravelReturn.resolve(
                mapOf(CountyAssignment.META_KEY to mapOf("bad" to true)), 2) { province }).reason)
    }
}
