package opensamguk.logic.input

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import opensamguk.logic.content.ItemCatalogJson

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
    @Test fun `ordinary equipment uses exact registered id and exclusive strict arguments`() {
        val id = ItemCatalogJson.CANON.equipment.first().id
        for (side in TradeSide.entries) {
            val request = DirectRequest.Equipment(7, null, side, id)
            val raw = """{"equipmentId":"$id","side":"${side.name}"}"""
            assertEquals(request, DirectInput.parse(7, DirectInput.EQUIPMENT, raw))
            assertEquals(raw, DirectInput.canonicalJson(request))
            assertTrue(DirectInput.deliveredVariant(request))
        }
        val invalid = listOf(
            """{"equipmentId":"$id","treasureId":1,"side":"BUY"}""",
            """{"equipmentId":1,"side":"BUY"}""",
            """{"equipmentId":"1","side":"BUY"}""",
            """{"equipmentId":"equipment:unknown","side":"BUY"}""",
            """{"equipmentId":"","side":"BUY"}""",
            """{"equipmentId":"$id","side":"buy"}""",
            """{"equipmentId":"$id","side":"BUY","cost":0}""",
            """{"equipmentId":"$id","equipmentId":"$id","side":"BUY"}""",
            """{"equipmentId":"$id","side":"BUY"} tail""",
        )
        invalid.forEach { assertNull(DirectInput.parse(7, DirectInput.EQUIPMENT, it), it) }
        assertFailsWith<IllegalArgumentException> {
            DirectInput.canonicalJson(DirectRequest.Equipment(7, 1, TradeSide.BUY, id))
        }
        val treasure = DirectRequest.Equipment(7, 2, TradeSide.BUY)
        assertEquals("""{"treasureId":2,"side":"BUY"}""", DirectInput.canonicalJson(treasure))
        assertFalse(DirectInput.deliveredVariant(treasure))
    }

}
