package opensamguk.logic.input

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HwihaPoliticalInputTest {
    @Test
    fun `political actions accept only their declared argument shape`() {
        HwihaPoliticalInput.NO_ARGUMENT_IDS.forEach { id ->
            assertEquals("{}", HwihaPoliticalInput.canonicalJson(HwihaPoliticalInput.parse(1, id, "{}")!!))
            assertNull(HwihaPoliticalInput.parse(1, id, "{\"targetGeneralId\":2}"))
        }
        HwihaPoliticalInput.TARGET_IDS.forEach { id ->
            assertEquals("{\"targetGeneralId\":2}",
                HwihaPoliticalInput.canonicalJson(HwihaPoliticalInput.parse(1, id, "{\"targetGeneralId\":2}")!!))
            for (bad in listOf("{}", "{\"targetGeneralId\":1}", "{\"targetGeneralId\":\"2\"}",
                    "{\"targetGeneralId\":2,\"targetGeneralId\":3}", "{\"targetGeneralId\":2} tail")) {
                assertNull(HwihaPoliticalInput.parse(1, id, bad), "$id / $bad")
            }
        }
    }
}
