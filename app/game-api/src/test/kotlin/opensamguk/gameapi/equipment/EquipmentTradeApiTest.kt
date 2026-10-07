package opensamguk.gameapi.equipment

import kotlin.test.*
import org.mockito.Mockito.*
import org.mockito.Mockito.`when`
import opensamguk.gameapi.precheck.DirectActionOptionsService
import opensamguk.gameapi.read.DomesticReader
import opensamguk.gameapi.read.DomesticSnapshot
import opensamguk.gameapi.read.DomesticForbidden
import opensamguk.gameapi.reserve.AdmissionDenied
import opensamguk.gameapi.reserve.DirectActionAdmission
import opensamguk.logic.content.ItemCatalogJson
import opensamguk.logic.content.TreasureSlot
import opensamguk.logic.domestic.*
import opensamguk.logic.economy.CountyWarehouse
import opensamguk.logic.economy.Resources
import opensamguk.logic.input.*

class EquipmentTradeApiTest {
    private val gear = ItemCatalogJson.CANON.equipment.first()
    private val actor = DomesticPerson(7, "장수", 1, true, 0, 1, 60, 60, 60, 60, 60,
        "province-a", false, emptyMap(), gold = gear.purchaseCost,
        equipmentSlots = TreasureSlot.entries.associateWith { "None" })
    private val county = DomesticCounty(11, "현", 1, "province-a", "군", mapOf(CountyWarehouse.META_KEY to
        CountyWarehouse(11, 0, Resources(money = gear.purchaseCost.toLong())).toMetaValue()), gear.requiredSecurity)
    private val state = DomesticProjection(RuleProfile.HWIHA, Phase(200, 1, 1), listOf(actor), emptyList(),
        listOf(county), emptyList(), setOf("province-a"))
    private val reader = mock(DomesticReader::class.java)
    private val admission = DirectActionAdmission(reader)
    private val raw = """{"equipmentId":"${gear.id}","side":"BUY"}"""
    private fun snapshot(s: DomesticProjection = state) {
        `when`(reader.snapshot()).thenReturn(DomesticSnapshot(state = s))
    }
    private fun deny(raw: String = this.raw, owner: Int? = 42, turn: Int = 0) =
        assertFailsWith<AdmissionDenied> { admission.canonicalArguments(DirectInput.EQUIPMENT, actor.id, owner, turn, raw) }.code

    @Test fun `actual catalog names and exclusive gear args are returned and admitted`() {
        snapshot()
        val options = DirectActionOptionsService(reader).options(actor.id, 42L, DirectInput.EQUIPMENT)
        assertTrue(options.available)
        assertEquals(ItemCatalogJson.CANON.equipment.associate { it.id to it.name }, options.equipmentNames)
        val buy = options.choices.single { it.arguments == mapOf("equipmentId" to gear.id, "side" to "BUY") }
        assertTrue(buy.available)
        assertTrue(buy.label.contains(gear.name))
        assertTrue(buy.label.contains(gear.purchaseCost.toString()))
        assertEquals(raw, admission.canonicalArguments(DirectInput.EQUIPMENT, actor.id, 42, 0, raw))
        verify(reader, times(2)).requireOwner(actor.id, 42L)
        assertFalse(options.choices.filter { "treasureId" in it.arguments }.any { it.available })
        assertTrue(options.choices.filter { "treasureId" in it.arguments }.all { it.code == "NOT_DELIVERED" })
        val pending = ItemCatalogJson.CANON.treasures.filter { it.issuedCopies == null }.map { it.sourceRowIndex }
        assertFalse(options.choices.any { it.arguments["treasureId"] in pending })
    }

    @Test fun `six unwired consumable variants remain unavailable in options and admission`() {
        snapshot()
        val items = ItemCatalogJson.CANON.equipment.filter { it.consumable }
        val options = DirectActionOptionsService(reader).options(actor.id, 42L, DirectInput.EQUIPMENT)
        assertEquals(6, items.size)
        for (item in items) for (side in TradeSide.entries) {
            val choice = options.choices.single { it.arguments == mapOf("equipmentId" to item.id, "side" to side.name) }
            assertFalse(choice.available)
            assertEquals("EQUIPMENT_UNAVAILABLE", choice.code)
            assertEquals("EQUIPMENT_UNAVAILABLE", deny("""{"equipmentId":"${item.id}","side":"${side.name}"}"""))
        }
    }

    @Test fun `legacy treasure is rejected before state read even after equipment promotion`() {
        val card = ItemCatalogJson.CANON.treasures.first { it.issuedCopies == 1 && it.purchaseCost > 0 }
        assertEquals("NOT_DELIVERED", deny("""{"treasureId":${card.sourceRowIndex},"side":"BUY"}"""))
        verify(reader, never()).snapshot()
    }

    @Test fun `mixed guessed blank and extra gear arguments cannot be admitted`() {
        for (bad in listOf("""{"equipmentId":1,"side":"BUY"}""",
            """{"equipmentId":"${gear.id}","treasureId":1,"side":"BUY"}""",
            """{"equipmentId":"equipment:unknown","side":"BUY"}""",
            """{"equipmentId":"","side":"BUY"}""",
            """{"equipmentId":"${gear.id}","side":"BUY","security":9999}""")) {
            assertEquals("INVALID_INPUT", deny(bad))
        }
        verify(reader, never()).snapshot()
    }

    @Test fun `options and admission share actual occupied security and ownership checks`() {
        val occupied = actor.copy(equipmentSlots = actor.equipmentSlots!! + (gear.slot to gear.sourceCode))
        snapshot(state.copy(people = listOf(occupied)))
        var options = DirectActionOptionsService(reader).options(actor.id, 42L, DirectInput.EQUIPMENT)
        assertEquals("EQUIPMENT_SLOT_OCCUPIED", options.choices.single { it.arguments == mapOf("equipmentId" to gear.id, "side" to "BUY") }.code)
        assertTrue(options.choices.single { it.arguments == mapOf("equipmentId" to gear.id, "side" to "SELL") }.available)
        assertEquals("EQUIPMENT_SLOT_OCCUPIED", deny())
        snapshot(state.copy(counties = listOf(county.copy(security = gear.requiredSecurity - 1))))
        assertEquals("INSUFFICIENT_SECURITY", deny())
        snapshot(state.copy(counties = listOf(county.copy(security = null))))
        options = DirectActionOptionsService(reader).options(actor.id, 42L, DirectInput.EQUIPMENT)
        assertFalse(options.available)
        assertEquals("STATE_UNAVAILABLE", deny())
    }

    @Test fun `authentication owner and reservation slot guards precede mutation`() {
        assertEquals("UNAUTHORIZED", deny(owner = null))
        assertEquals("INVALID_TURN_SLOT", deny(turn = 12))
        doThrow(DomesticForbidden()).`when`(reader).requireOwner(actor.id, 42L)
        assertEquals("FORBIDDEN", deny())
        verify(reader, never()).snapshot()
    }
}
