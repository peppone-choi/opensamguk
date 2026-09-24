package opensamguk.logic.input

import kotlin.test.*

class HwihaPeopleInputTest {
    @Test fun `search has no caller supplied target or cost`() {
        val request = HwihaPeopleInput.parse(7, HwihaPeopleInput.SEARCH, "{}")
        assertEquals(HwihaPeopleRequest(7, HwihaPeopleInput.SEARCH, null), request)
        assertEquals("{}", HwihaPeopleInput.canonicalJson(request!!))
        assertNull(HwihaPeopleInput.parse(7, HwihaPeopleInput.SEARCH, "{\"countyId\":11}"))
    }

    @Test fun `employ and captive target require one numeric identity`() {
        for (inputId in listOf(HwihaPeopleInput.EMPLOY, HwihaPeopleInput.PERSUADE_CAPTIVE)) {
            val request = assertNotNull(HwihaPeopleInput.parse(7, inputId, "{\"targetGeneralId\":8}"))
            assertEquals("{\"targetGeneralId\":8}", HwihaPeopleInput.canonicalJson(request))
            for (raw in listOf("{}", "{\"targetGeneralId\":\"8\"}", "{\"targetGeneralId\":7}",
                "{\"targetGeneralId\":8,\"cost\":1}", "{\"targetGeneralId\":8,\"targetGeneralId\":9}",
                "{\"targetGeneralId\":8} extra")) {
                assertNull(HwihaPeopleInput.parse(7, inputId, raw), "$inputId $raw")
            }
        }
    }
}
