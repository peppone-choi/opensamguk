package opensamguk.logic.input

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import opensamguk.logic.world.StrategicNodeRef

class HwihaTravelReturnTest {
    private val assignment = HwihaCountyAssignment("dispatch-1", 4, 2, 9)
    private val province = StrategicNodeRef.LandProvince("home")

    @Test fun `return uses dispatched county even when current reference city differs`() {
        val result = HwihaTravelReturn.resolve(mapOf(HwihaCountyAssignment.META_KEY to assignment.toMetaValue()), 2) {
            assertEquals(9, it)
            province
        }
        assertEquals(province, assertIs<HwihaReturnDestination.Ready>(result).node)
    }

    @Test fun `missing or stale assignment has an explicit reason`() {
        assertEquals(HwihaTravelFailure.NO_RETURN_ASSIGNMENT,
            assertIs<HwihaReturnDestination.Rejected>(HwihaTravelReturn.resolve(emptyMap(), 2) { province }).reason)
        assertEquals(HwihaTravelFailure.NO_RETURN_ASSIGNMENT,
            assertIs<HwihaReturnDestination.Rejected>(HwihaTravelReturn.resolve(
                mapOf(HwihaCountyAssignment.META_KEY to assignment.toMetaValue()), 3) { province }).reason)
        assertEquals(HwihaTravelFailure.STATE_UNAVAILABLE,
            assertIs<HwihaReturnDestination.Rejected>(HwihaTravelReturn.resolve(
                mapOf(HwihaCountyAssignment.META_KEY to mapOf("bad" to true)), 2) { province }).reason)
    }
}
