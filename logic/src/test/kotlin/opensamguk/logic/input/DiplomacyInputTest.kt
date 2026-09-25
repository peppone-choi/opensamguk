package opensamguk.logic.input

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DiplomacyInputTest {
    @Test fun `all diplomatic modes share one strict nation target contract`() {
        DiplomacyInput.INPUT_IDS.forEach { id ->
            val request = DiplomacyInput.parse(1, id, "{\"targetNationId\":2}")!!
            assertEquals(DiplomacyRequest(1, id, 2), request)
            assertEquals("{\"targetNationId\":2}", DiplomacyInput.canonicalJson(request))
            for (bad in listOf("{}", "{\"targetNationId\":\"2\"}", "{\"targetNationId\":0}",
                    "{\"targetNationId\":2,\"ownerUserId\":1}",
                    "{\"targetNationId\":2,\"targetNationId\":3}", "{\"targetNationId\":2} tail")) {
                assertNull(DiplomacyInput.parse(1, id, bad), "$id / $bad")
            }
        }
    }
}
