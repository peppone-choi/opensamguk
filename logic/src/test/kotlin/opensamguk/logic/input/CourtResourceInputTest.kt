package opensamguk.logic.input

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HwihaCourtResourceInputTest {
    @Test fun `court resource requests bind exactly one target and amount`() {
        val confiscate = HwihaCourtResourceInput.parse(1, HwihaCourtResourceInput.CONFISCATE,
            "{\"targetGeneralId\":2,\"resource\":\"MONEY\",\"amount\":100}")!!
        assertEquals(HwihaCourtResourceRequest(1, HwihaCourtResourceInput.CONFISCATE, 2,
            HwihaTransferResource.MONEY, 100), confiscate)
        assertEquals("{\"targetGeneralId\":2,\"resource\":\"MONEY\",\"amount\":100}",
            HwihaCourtResourceInput.canonicalJson(confiscate))
        assertEquals(HwihaCourtResourceRequest(1, HwihaCourtResourceInput.AID, 3,
            HwihaTransferResource.GRAIN, 30), HwihaCourtResourceInput.parse(1, HwihaCourtResourceInput.AID,
            "{\"targetNationId\":3,\"resource\":\"GRAIN\",\"amount\":30}"))
        for (bad in listOf("{\"targetGeneralId\":1,\"resource\":\"MONEY\",\"amount\":100}",
                "{\"targetGeneralId\":2,\"resource\":\"MONEY\",\"amount\":\"100\"}",
                "{\"targetGeneralId\":2,\"resource\":\"MONEY\",\"amount\":100,\"cost\":1}",
                "{\"targetGeneralId\":2,\"resource\":\"MONEY\",\"amount\":100,\"amount\":101}",
                "{\"targetGeneralId\":2,\"resource\":\"MONEY\",\"amount\":100} tail")) {
            assertNull(HwihaCourtResourceInput.parse(1, HwihaCourtResourceInput.CONFISCATE, bad), bad)
        }
    }
}
