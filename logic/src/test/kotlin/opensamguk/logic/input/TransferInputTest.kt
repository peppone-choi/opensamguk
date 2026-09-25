package opensamguk.logic.input

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TransferInputTest {
    @Test fun `gift and donation accept one physical resource and positive amount`() {
        val gift = TransferInput.parse(1, TransferInput.GIFT,
            "{\"amount\":12,\"resource\":\"IRON\",\"targetGeneralId\":2}")!!
        assertEquals(TransferRequest(1, TransferInput.GIFT, TransferResource.IRON, 12, 2), gift)
        assertEquals("{\"targetGeneralId\":2,\"resource\":\"IRON\",\"amount\":12}", TransferInput.canonicalJson(gift))
        assertEquals(TransferRequest(1, TransferInput.DONATE, TransferResource.GRAIN, 1),
            TransferInput.parse(1, TransferInput.DONATE, "{\"resource\":\"GRAIN\",\"amount\":1}"))
        for (bad in listOf("{\"resource\":\"GRAIN\",\"amount\":\"1\"}",
                "{\"resource\":\"GRAIN\",\"amount\":0}", "{\"resource\":\"GOLD\",\"amount\":1}",
                "{\"resource\":\"GRAIN\",\"amount\":1,\"amount\":2}",
                "{\"resource\":\"GRAIN\",\"amount\":1} tail")) {
            assertNull(TransferInput.parse(1, TransferInput.DONATE, bad), bad)
        }
        assertNull(TransferInput.parse(1, TransferInput.GIFT,
            "{\"targetGeneralId\":1,\"resource\":\"MONEY\",\"amount\":1}"))
    }
}
