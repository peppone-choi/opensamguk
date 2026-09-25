package opensamguk.logic.input

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HwihaLegacyDirectInputTest {
    @Test fun `four legacy direct arguments are strict`() {
        assertEquals(HwihaLegacyDirectRequest.Convert(1, 2, 3),
            HwihaLegacyDirectInput.parse(1, HwihaLegacyDirectInput.CONVERT, "{\"bugokId\":2,\"crewTypeId\":3}"))
        assertEquals(HwihaLegacyDirectRequest.Equipment(1, 2, HwihaTradeSide.BUY),
            HwihaLegacyDirectInput.parse(1, HwihaLegacyDirectInput.EQUIPMENT, "{\"treasureId\":2,\"side\":\"BUY\"}"))
        assertEquals(HwihaLegacyDirectRequest.Grain(1, HwihaTradeSide.SELL, 300),
            HwihaLegacyDirectInput.parse(1, HwihaLegacyDirectInput.GRAIN, "{\"side\":\"SELL\",\"amount\":300}"))
        assertEquals(HwihaLegacyDirectRequest.Transport(1, 4, HwihaCargo.IRON, 100),
            HwihaLegacyDirectInput.parse(1, HwihaLegacyDirectInput.TRANSPORT,
                "{\"targetCountyId\":4,\"cargo\":\"IRON\",\"amount\":100}"))
        assertEquals("{\"side\":\"SELL\",\"amount\":300}", HwihaLegacyDirectInput.canonicalJson(
            HwihaLegacyDirectInput.parse(1, HwihaLegacyDirectInput.GRAIN,
                "{\"amount\":300,\"side\":\"SELL\"}")!!))
        for (bad in listOf("{\"side\":\"SELL\",\"amount\":\"300\"}",
                "{\"side\":\"SELL\",\"amount\":0}", "{\"side\":\"SELL\",\"amount\":300,\"cost\":1}",
                "{\"side\":\"SELL\",\"amount\":300,\"amount\":301}",
                "{\"side\":\"SELL\",\"amount\":300} tail")) {
            assertNull(HwihaLegacyDirectInput.parse(1, HwihaLegacyDirectInput.GRAIN, bad), bad)
        }
    }
}
