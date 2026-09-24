package opensamguk.logic.input

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HwihaCourtExpansionInputTest {
    @Test fun `county and corps court arguments reject client authority and malformed JSON`() {
        assertEquals(HwihaCourtExpansionRequest.County(1, HwihaCourtExpansionInput.MOVE_CAPITAL, 3),
            HwihaCourtExpansionInput.parse(1, HwihaCourtExpansionInput.MOVE_CAPITAL, "{\"countyId\":3}"))
        assertEquals(HwihaCourtExpansionRequest.ReleaseCorps(1, 3),
            HwihaCourtExpansionInput.parse(1, HwihaCourtExpansionInput.RELEASE_CORPS, "{\"targetGeneralId\":3}"))
        assertNull(HwihaCourtExpansionInput.parse(1, HwihaCourtExpansionInput.RELEASE_CORPS,
            "{\"targetGeneralId\":1}"))
        assertEquals("{\"countyId\":3}", HwihaCourtExpansionInput.canonicalJson(
            HwihaCourtExpansionInput.parse(1, HwihaCourtExpansionInput.ABANDON_COUNTY, "{\"countyId\":3}")!!))
        for (bad in listOf("{\"countyId\":\"3\"}", "{\"countyId\":0}",
                "{\"countyId\":3,\"ownerUserId\":1}", "{\"countyId\":3,\"countyId\":4}",
                "{\"countyId\":3} tail")) {
            assertNull(HwihaCourtExpansionInput.parse(1, HwihaCourtExpansionInput.MOVE_CAPITAL, bad), bad)
        }
    }
}
