package opensamguk.logic.travel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class PersonalReturnStopTest {
    @Test fun `return stop has a strict round trip schema`() {
        val marker = PersonalReturnStop("return-order", "dispatch-1")
        assertEquals(marker, PersonalReturnStop.read(mapOf(PersonalReturnStop.META_KEY to marker.toMetaValue())))
        assertNull(PersonalReturnStop.read(emptyMap()))
        val row = marker.toMetaValue()
        for (bad in listOf(null, row + ("version" to 2), row + ("extra" to true),
            row - "assignmentId", row + ("orderId" to ""), row + ("assignmentId" to "bad assignment"))) {
            assertFailsWith<IllegalArgumentException> {
                PersonalReturnStop.read(mapOf(PersonalReturnStop.META_KEY to bad))
            }
        }
    }
}
