package opensamguk.logic.input

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HwihaTransferInputTest {
    @Test fun `gift and donation accept one physical resource and positive amount`() {
        val gift = HwihaTransferInput.parse(1, HwihaTransferInput.GIFT,
            "{\"amount\":12,\"resource\":\"IRON\",\"targetGeneralId\":2}")!!
        assertEquals(HwihaTransferRequest(1, HwihaTransferInput.GIFT, HwihaTransferResource.IRON, 12, 2), gift)
        assertEquals("{\"targetGeneralId\":2,\"resource\":\"IRON\",\"amount\":12}", HwihaTransferInput.canonicalJson(gift))
        assertEquals(HwihaTransferRequest(1, HwihaTransferInput.DONATE, HwihaTransferResource.GRAIN, 1),
            HwihaTransferInput.parse(1, HwihaTransferInput.DONATE, "{\"resource\":\"GRAIN\",\"amount\":1}"))
        for (bad in listOf("{\"resource\":\"GRAIN\",\"amount\":\"1\"}",
                "{\"resource\":\"GRAIN\",\"amount\":0}", "{\"resource\":\"GOLD\",\"amount\":1}",
                "{\"resource\":\"GRAIN\",\"amount\":1,\"amount\":2}",
                "{\"resource\":\"GRAIN\",\"amount\":1} tail")) {
            assertNull(HwihaTransferInput.parse(1, HwihaTransferInput.DONATE, bad), bad)
        }
        assertNull(HwihaTransferInput.parse(1, HwihaTransferInput.GIFT,
            "{\"targetGeneralId\":1,\"resource\":\"MONEY\",\"amount\":1}"))
    }
}
