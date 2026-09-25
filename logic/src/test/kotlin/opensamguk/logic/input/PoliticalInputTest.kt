package opensamguk.logic.input

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PoliticalInputTest {
    @Test
    fun `political actions accept only their declared argument shape`() {
        PoliticalInput.NO_ARGUMENT_IDS.forEach { id ->
            assertEquals("{}", PoliticalInput.canonicalJson(PoliticalInput.parse(1, id, "{}")!!))
            assertNull(PoliticalInput.parse(1, id, "{\"targetGeneralId\":2}"))
        }
        PoliticalInput.TARGET_IDS.forEach { id ->
            assertEquals("{\"targetGeneralId\":2}",
                PoliticalInput.canonicalJson(PoliticalInput.parse(1, id, "{\"targetGeneralId\":2}")!!))
            for (bad in listOf("{}", "{\"targetGeneralId\":1}", "{\"targetGeneralId\":\"2\"}",
                    "{\"targetGeneralId\":2,\"targetGeneralId\":3}", "{\"targetGeneralId\":2} tail")) {
                assertNull(PoliticalInput.parse(1, id, bad), "$id / $bad")
            }
        }
    }
}
