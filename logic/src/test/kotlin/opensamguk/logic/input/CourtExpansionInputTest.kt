package opensamguk.logic.input

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CourtExpansionInputTest {
    @Test fun `county and corps court arguments reject client authority and malformed JSON`() {
        assertEquals(CourtExpansionRequest.County(1, CourtExpansionInput.MOVE_CAPITAL, 3),
            CourtExpansionInput.parse(1, CourtExpansionInput.MOVE_CAPITAL, "{\"countyId\":3}"))
        assertEquals(CourtExpansionRequest.ReleaseCorps(1, 3),
            CourtExpansionInput.parse(1, CourtExpansionInput.RELEASE_CORPS, "{\"targetGeneralId\":3}"))
        assertNull(CourtExpansionInput.parse(1, CourtExpansionInput.RELEASE_CORPS,
            "{\"targetGeneralId\":1}"))
        assertEquals("{\"countyId\":3}", CourtExpansionInput.canonicalJson(
            CourtExpansionInput.parse(1, CourtExpansionInput.ABANDON_COUNTY, "{\"countyId\":3}")!!))
        for (bad in listOf("{\"countyId\":\"3\"}", "{\"countyId\":0}",
                "{\"countyId\":3,\"ownerUserId\":1}", "{\"countyId\":3,\"countyId\":4}",
                "{\"countyId\":3} tail")) {
            assertNull(CourtExpansionInput.parse(1, CourtExpansionInput.MOVE_CAPITAL, bad), bad)
        }
    }
}
