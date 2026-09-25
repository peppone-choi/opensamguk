package opensamguk.logic.input

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TravelInputTest {
    @Test fun `move and forced march accept only a destination province`() {
        for (id in listOf(TravelInput.MOVE, TravelInput.FORCED_MARCH)) {
            val request = TravelInput.parse(7, id, """{"destinationProvinceId":"han-17"}""")!!
            assertEquals("han-17", request.destination!!.id)
            assertEquals("""{"destinationProvinceId":"han-17"}""", TravelInput.canonicalJson(request))
            for (body in listOf("{}", """{"destinationProvinceId":17}""", """{"destinationProvinceId":"han-17","actorId":8}""",
                """{"destinationProvinceId":"han-17","destinationProvinceId":"han-18"}""", """{"destinationProvinceId":"han-17"} trailing""")) {
                assertNull(TravelInput.parse(7, id, body), body)
            }
        }
    }

    @Test fun `return accepts no client chosen destination`() {
        val request = TravelInput.parse(7, TravelInput.RETURN, "{}")!!
        assertNull(request.destination)
        assertEquals("{}", TravelInput.canonicalJson(request))
        assertNull(TravelInput.parse(7, TravelInput.RETURN, """{"destinationProvinceId":"han-17"}"""))
    }
}
