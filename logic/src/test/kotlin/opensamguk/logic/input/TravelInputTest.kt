package opensamguk.logic.input

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HwihaTravelInputTest {
    @Test fun `move and forced march accept only a destination province`() {
        for (id in listOf(HwihaTravelInput.MOVE, HwihaTravelInput.FORCED_MARCH)) {
            val request = HwihaTravelInput.parse(7, id, """{"destinationProvinceId":"han-17"}""")!!
            assertEquals("han-17", request.destination!!.id)
            assertEquals("""{"destinationProvinceId":"han-17"}""", HwihaTravelInput.canonicalJson(request))
            for (body in listOf("{}", """{"destinationProvinceId":17}""", """{"destinationProvinceId":"han-17","actorId":8}""",
                """{"destinationProvinceId":"han-17","destinationProvinceId":"han-18"}""", """{"destinationProvinceId":"han-17"} trailing""")) {
                assertNull(HwihaTravelInput.parse(7, id, body), body)
            }
        }
    }

    @Test fun `return accepts no client chosen destination`() {
        val request = HwihaTravelInput.parse(7, HwihaTravelInput.RETURN, "{}")!!
        assertNull(request.destination)
        assertEquals("{}", HwihaTravelInput.canonicalJson(request))
        assertNull(HwihaTravelInput.parse(7, HwihaTravelInput.RETURN, """{"destinationProvinceId":"han-17"}"""))
    }
}
