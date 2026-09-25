package opensamguk.logic.input

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CourtResourceInputTest {
    @Test fun `court resource requests bind exactly one target and amount`() {
        val confiscate = CourtResourceInput.parse(1, CourtResourceInput.CONFISCATE,
            "{\"targetGeneralId\":2,\"resource\":\"MONEY\",\"amount\":100}")!!
        assertEquals(CourtResourceRequest(1, CourtResourceInput.CONFISCATE, 2,
            TransferResource.MONEY, 100), confiscate)
        assertEquals("{\"targetGeneralId\":2,\"resource\":\"MONEY\",\"amount\":100}",
            CourtResourceInput.canonicalJson(confiscate))
        assertEquals(CourtResourceRequest(1, CourtResourceInput.AID, 3,
            TransferResource.GRAIN, 30), CourtResourceInput.parse(1, CourtResourceInput.AID,
            "{\"targetNationId\":3,\"resource\":\"GRAIN\",\"amount\":30}"))
        for (bad in listOf("{\"targetGeneralId\":1,\"resource\":\"MONEY\",\"amount\":100}",
                "{\"targetGeneralId\":2,\"resource\":\"MONEY\",\"amount\":\"100\"}",
                "{\"targetGeneralId\":2,\"resource\":\"MONEY\",\"amount\":100,\"cost\":1}",
                "{\"targetGeneralId\":2,\"resource\":\"MONEY\",\"amount\":100,\"amount\":101}",
                "{\"targetGeneralId\":2,\"resource\":\"MONEY\",\"amount\":100} tail")) {
            assertNull(CourtResourceInput.parse(1, CourtResourceInput.CONFISCATE, bad), bad)
        }
    }
}
