package opensamguk.logic.input

import kotlin.test.*

class PeopleInputTest {
    @Test fun `search has no caller supplied target or cost`() {
        val request = PeopleInput.parse(7, PeopleInput.SEARCH, "{}")
        assertEquals(PeopleRequest(7, PeopleInput.SEARCH, null), request)
        assertEquals("{}", PeopleInput.canonicalJson(request!!))
        assertNull(PeopleInput.parse(7, PeopleInput.SEARCH, "{\"countyId\":11}"))
    }

    @Test fun `employ and captive target require one numeric identity`() {
        for (inputId in listOf(PeopleInput.EMPLOY, PeopleInput.PERSUADE_CAPTIVE)) {
            val request = assertNotNull(PeopleInput.parse(7, inputId, "{\"targetGeneralId\":8}"))
            assertEquals("{\"targetGeneralId\":8}", PeopleInput.canonicalJson(request))
            for (raw in listOf("{}", "{\"targetGeneralId\":\"8\"}", "{\"targetGeneralId\":7}",
                "{\"targetGeneralId\":8,\"cost\":1}", "{\"targetGeneralId\":8,\"targetGeneralId\":9}",
                "{\"targetGeneralId\":8} extra")) {
                assertNull(PeopleInput.parse(7, inputId, raw), "$inputId $raw")
            }
        }
    }
}
