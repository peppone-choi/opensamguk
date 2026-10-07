package opensamguk.logic.items

import kotlin.test.*
import opensamguk.logic.content.ItemCatalogJson
import opensamguk.logic.content.TreasureSlot
import opensamguk.logic.domestic.*
import opensamguk.logic.economy.CountyWarehouse
import opensamguk.logic.economy.Resources
import opensamguk.logic.input.*

class EquipmentTradeRulesTest {
    private val gear = ItemCatalogJson.CANON.equipment.first { it.slot == TreasureSlot.HORSE }
    private val actor = DomesticPerson(7, "장수", 1, true, 0, 1, 60, 60, 60, 60, 60,
        "province-a", false, emptyMap(), gold = gear.purchaseCost,
        equipmentSlots = TreasureSlot.entries.associateWith { "None" })
    private val county = DomesticCounty(11, "현", 1, "province-a", "군", warehouse(gear.purchaseCost.toLong()),
        security = gear.requiredSecurity)
    private val state = DomesticProjection(RuleProfile.HWIHA, Phase(200, 1, 1), listOf(actor), emptyList(),
        listOf(county), emptyList(), setOf("province-a"))
    private fun warehouse(money: Long) = mapOf(CountyWarehouse.META_KEY to
        CountyWarehouse(11, 0, Resources(money = money)).toMetaValue())
    private fun request(side: TradeSide = TradeSide.BUY) = DirectRequest.Equipment(actor.id, null, side, gear.id)
    private fun failure(s: DomesticProjection, side: TradeSide = TradeSide.BUY) =
        assertIs<DirectAssessment.Rejected>(DirectRules.assess(request(side), s)).reason
    private fun person(p: DomesticPerson) = state.copy(people = listOf(p))
    private fun worn(code: String?) = actor.copy(equipmentSlots = actor.equipmentSlots!! + (gear.slot to code))

    @Test fun `buy uses exact catalog security price and empty slot without treasure inventory`() {
        val result = assertIs<DirectAssessment.Eligible>(DirectRules.assess(request(), state))
        assertEquals(gear, result.equipment)
        assertEquals(gear.purchaseCost.toLong(), result.actorStock!!.money)
        assertNull(result.treasure)
        assertEquals(emptySet(), result.inventory)
    }

    @Test fun `all unlimited canonical equipment trades use their own actual slot and threshold`() {
        val supported = ItemCatalogJson.CANON.equipment.filter { !it.consumable }
        assertEquals(18, supported.size)
        for (item in supported) {
            assertNotNull(ItemRegistry().resolve(item.sourceCode), item.id)
            val s = state.copy(people = listOf(actor.copy(gold = item.purchaseCost)),
                counties = listOf(county.copy(security = item.requiredSecurity)))
            assertEquals(item, assertIs<DirectAssessment.Eligible>(DirectRules.assess(
                DirectRequest.Equipment(actor.id, null, TradeSide.BUY, item.id), s)).equipment)
        }
    }

    @Test fun `unwired consumable item variants fail closed for both trade sides`() {
        val items = ItemCatalogJson.CANON.equipment.filter { it.consumable }
        assertEquals(6, items.size)
        for (item in items) {
            assertNull(ItemRegistry().resolve(item.sourceCode), item.id)
            for (side in TradeSide.entries) {
                assertEquals(DirectFailure.EQUIPMENT_UNAVAILABLE, assertIs<DirectAssessment.Rejected>(DirectRules.assess(
                    DirectRequest.Equipment(actor.id, null, side, item.id), state)).reason)
            }
        }
    }

    @Test fun `sell requires same equipped source and sufficient real warehouse funds`() {
        val s = person(worn(gear.sourceCode))
        assertEquals(gear, assertIs<DirectAssessment.Eligible>(DirectRules.assess(request(TradeSide.SELL), s)).equipment)
        assertEquals(DirectFailure.INSUFFICIENT_STOCK, failure(s.copy(counties = listOf(
            county.copy(meta = warehouse(gear.purchaseCost.toLong() - 1)))), TradeSide.SELL))
        assertEquals(DirectFailure.EQUIPMENT_NOT_OWNED, failure(state, TradeSide.SELL))
    }

    @Test fun `occupied equipment and treasure slots never imply replacement or refund`() {
        val sameSlot = ItemCatalogJson.CANON.equipment.first { it.slot == gear.slot && it.id != gear.id }
        val treasure = ItemCatalogJson.CANON.treasures.first { it.slot == gear.slot }
        for (code in listOf(gear.sourceCode, sameSlot.sourceCode, treasure.sourceCode)) {
            assertEquals(DirectFailure.EQUIPMENT_SLOT_OCCUPIED, failure(person(worn(code))))
        }
        assertEquals(DirectFailure.EQUIPMENT_NOT_OWNED, failure(person(worn(sameSlot.sourceCode)), TradeSide.SELL))
    }

    @Test fun `missing blank and unknown slots fail closed rather than becoming empty`() {
        for (p in listOf(actor.copy(equipmentSlots = null), actor.copy(equipmentSlots = emptyMap()),
            worn(null), worn(""), worn(" "), worn("unregistered"))) {
            assertEquals(DirectFailure.STATE_UNAVAILABLE, failure(person(p)))
        }
    }

    @Test fun `missing or insufficient county security cannot be inferred`() {
        for (security in listOf(null, -1)) assertEquals(DirectFailure.STATE_UNAVAILABLE,
            failure(state.copy(counties = listOf(county.copy(security = security)))))
        assertEquals(DirectFailure.INSUFFICIENT_SECURITY, failure(state.copy(counties = listOf(
            county.copy(security = gear.requiredSecurity - 1)))))
    }

    @Test fun `buy funds and both receiving resource overflow checks remain atomic`() {
        assertEquals(DirectFailure.INSUFFICIENT_STOCK, failure(person(actor.copy(gold = gear.purchaseCost - 1))))
        assertEquals(DirectFailure.STOCK_OVERFLOW, failure(state.copy(counties = listOf(
            county.copy(meta = warehouse(Long.MAX_VALUE))))))
        assertEquals(DirectFailure.STOCK_OVERFLOW, failure(person(worn(gear.sourceCode).copy(gold = Int.MAX_VALUE)), TradeSide.SELL))
    }

    @Test fun `position profile battle and allied ownership gates remain in force`() {
        assertEquals(DirectFailure.WRONG_RULE_PROFILE, failure(state.copy(profile = RuleProfile.entries.first { it != RuleProfile.HWIHA })))
        assertEquals(DirectFailure.BATTLE_PENDING, failure(person(actor.copy(inBattle = true))))
        assertEquals(DirectFailure.POSITION_UNAVAILABLE, failure(person(actor.copy(node = null))))
        assertEquals(DirectFailure.FOREIGN_COUNTY, failure(state.copy(counties = listOf(county.copy(nationId = 2)))))
        assertEquals(DirectFailure.WAREHOUSE_NOT_READY, failure(state.copy(counties = listOf(county.copy(meta = emptyMap())))))
    }

    @Test fun `programmatic mixed or unregistered ids also fail`() {
        assertEquals(DirectFailure.INVALID_INPUT, assertIs<DirectAssessment.Rejected>(DirectRules.assess(
            request().copy(treasureId = 1), state)).reason)
        assertEquals(DirectFailure.EQUIPMENT_UNAVAILABLE, assertIs<DirectAssessment.Rejected>(DirectRules.assess(
            request().copy(equipmentId = "equipment:unknown"), state)).reason)
    }

    @Test fun `legacy treasure pending issuance and quantity rules are retained but variant delivery is closed`() {
        val pending = ItemCatalogJson.CANON.treasures.first { it.issuedCopies == null }
        val request = DirectRequest.Equipment(actor.id, pending.sourceRowIndex, TradeSide.BUY)
        assertFalse(DirectInput.deliveredVariant(request))
        assertEquals(DirectFailure.TREASURE_UNAVAILABLE,
            assertIs<DirectAssessment.Rejected>(DirectRules.assess(request, state)).reason)
        val issued = ItemCatalogJson.CANON.treasures.first { it.issuedCopies == 1 && it.purchaseCost > 0 }
        val owner = actor.copy(gold = issued.purchaseCost, meta = TreasureInventory.withCards(actor.meta, setOf(issued.header.id)))
        assertEquals(DirectFailure.TREASURE_ISSUED, assertIs<DirectAssessment.Rejected>(DirectRules.assess(
            DirectRequest.Equipment(actor.id, issued.sourceRowIndex, TradeSide.BUY), person(owner))).reason)
    }
}
