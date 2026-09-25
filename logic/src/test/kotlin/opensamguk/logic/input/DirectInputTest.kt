package opensamguk.logic.input

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DirectInputTest {
    @Test fun `four legacy direct arguments are strict`() {
        assertEquals(DirectRequest.Convert(1, 2, 3),
            DirectInput.parse(1, DirectInput.CONVERT, "{\"bugokId\":2,\"crewTypeId\":3}"))
        assertEquals(DirectRequest.Equipment(1, 2, TradeSide.BUY),
            DirectInput.parse(1, DirectInput.EQUIPMENT, "{\"treasureId\":2,\"side\":\"BUY\"}"))
        assertEquals(DirectRequest.Grain(1, TradeSide.SELL, 300),
            DirectInput.parse(1, DirectInput.GRAIN, "{\"side\":\"SELL\",\"amount\":300}"))
        assertEquals(DirectRequest.Transport(1, 4, Cargo.IRON, 100),
            DirectInput.parse(1, DirectInput.TRANSPORT,
                "{\"targetCountyId\":4,\"cargo\":\"IRON\",\"amount\":100}"))
        assertEquals("{\"side\":\"SELL\",\"amount\":300}", DirectInput.canonicalJson(
            DirectInput.parse(1, DirectInput.GRAIN,
                "{\"amount\":300,\"side\":\"SELL\"}")!!))
        for (bad in listOf("{\"side\":\"SELL\",\"amount\":\"300\"}",
                "{\"side\":\"SELL\",\"amount\":0}", "{\"side\":\"SELL\",\"amount\":300,\"cost\":1}",
                "{\"side\":\"SELL\",\"amount\":300,\"amount\":301}",
                "{\"side\":\"SELL\",\"amount\":300} tail")) {
            assertNull(DirectInput.parse(1, DirectInput.GRAIN, bad), bad)
        }
    }
}
