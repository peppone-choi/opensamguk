package opensamguk.logic.content

import kotlin.test.*
import org.junit.jupiter.api.Test

class HwihaTreasureCardsTest {
    @Test fun `packaged active ledgers use the common card header and keep legacy two unissued`() {
        val catalog = HwihaItemCatalogJson.CANON
        assertTrue(catalog.treasures.isNotEmpty() && catalog.equipment.isNotEmpty())
        assertTrue(catalog.treasures.all { it.header.kind == CardKind.TREASURE &&
            it.header.provenanceBadges == listOf("게임 용어") })
        assertTrue(catalog.equipment.all { it.purchaseCost >= 0 })
        assertTrue(catalog.treasures.any { it.issuedCopies == null })
        assertTrue(catalog.treasures.map { it.sourceCode }.toSet().intersect(
            catalog.equipment.map { it.sourceCode }.toSet()).isEmpty())
    }

    private fun card(id: String, slot: TreasureSlot, copies: Int? = 1) = HwihaTreasureDefinition(
        CardHeader(id, id, CardKind.TREASURE, CardAvailability.UNIQUE,
            listOf(CardProvenance.GameTerm), 0, emptySet(), emptySet()),
        "source:$id", slot, copies)

    private val owner = TreasurePerson(1, 1, "province:a", npc = false)
    private val npc = TreasurePerson(2, 1, "province:a", npc = true, directHolderId = 1)
    private val human = TreasurePerson(3, 1, "province:a", npc = false, directHolderId = null)
    private val enemy = TreasurePerson(4, 2, "province:a", npc = true)
    private val distant = TreasurePerson(5, 1, "province:b", npc = false)
    private val horse = card("horse", TreasureSlot.HORSE)
    private val weapon = card("weapon", TreasureSlot.WEAPON)
    private val book = card("book", TreasureSlot.BOOK)
    private val item = card("item", TreasureSlot.ITEM)

    private fun state(vararg instances: TreasureInstance, cards: List<HwihaTreasureDefinition> =
        listOf(horse, weapon, book, item)): HwihaTreasureState = HwihaTreasureState(
        listOf(owner, npc, human, enemy, distant), cards, instances.toList())

    @Test fun `one treasure per legacy position with four total positions and equipment conflicts`() {
        val four = listOf(horse, weapon, book, item).map { TreasureInstance(it.header.id, it.header.id, 1, 1) }
        assertEquals(4, state(*four.toTypedArray()).instances.count { it.bearerGeneralId == 1 })
        val secondHorse = card("horse2", TreasureSlot.HORSE)
        assertFailsWith<IllegalArgumentException> {
            state(*(four + TreasureInstance("horse2", "horse2", 1, 1)).toTypedArray(),
                cards = listOf(horse, secondHorse, weapon, book, item))
        }
        assertFailsWith<IllegalArgumentException> {
            state(TreasureInstance("horse", "horse", 1, 1)).copy(
                occupiedEquipmentSlots = mapOf(1 to setOf(TreasureSlot.HORSE)))
        }
    }

    @Test fun `owner or direct npc may carry treasure but human and foreign people may not`() {
        val loose = state(TreasureInstance("horse", "horse", 1))
        assertEquals(2, loose.attach("horse", 1, 2).instances.single().bearerGeneralId)
        assertFailsWith<IllegalArgumentException> { loose.attach("horse", 1, 3) }
        assertFailsWith<IllegalArgumentException> { loose.attach("horse", 1, 4) }
        assertFailsWith<IllegalArgumentException> { loose.attach("horse", 1, 5) }
        assertFailsWith<IllegalArgumentException> {
            HwihaTreasureState(listOf(owner, npc.copy(provinceId = "province:b")), listOf(horse),
                listOf(TreasureInstance("horse", "horse", 1, 2)))
        }
    }

    @Test fun `voluntary transfer requires detachment same nation and co-location`() {
        val worn = state(TreasureInstance("horse", "horse", 1, 2))
        assertFailsWith<IllegalArgumentException> { worn.transfer("horse", 1, 3) }
        val loose = worn.detach("horse", 1)
        assertFailsWith<IllegalArgumentException> { loose.transfer("horse", 1, 4) }
        assertFailsWith<IllegalArgumentException> { loose.transfer("horse", 1, 5) }
        val moved = loose.transfer("horse", 1, 3)
        assertEquals(3, moved.instances.single().ownerGeneralId)
        assertNull(moved.instances.single().bearerGeneralId)
        assertFailsWith<IllegalArgumentException> { moved.transfer("horse", 1, 3) }
    }

    @Test fun `battle seizure removes the defeated attachment and cannot replay from stale owner`() {
        val worn = state(TreasureInstance("horse", "horse", 1, 2))
        assertFailsWith<IllegalArgumentException> { worn.seize("horse", 3, 4) }
        val seized = worn.seize("horse", 2, 4)
        assertEquals(4, seized.instances.single().ownerGeneralId)
        assertNull(seized.instances.single().bearerGeneralId)
        assertFailsWith<IllegalArgumentException> { seized.seize("horse", 2, 4) }
    }

    @Test fun `pending availability two cannot issue a card and one copy cannot duplicate`() {
        val pending = card("pending", TreasureSlot.HORSE, copies = null)
        assertFailsWith<IllegalArgumentException> {
            state(TreasureInstance("p1", "pending", 1), cards = listOf(pending))
        }
        assertFailsWith<IllegalArgumentException> {
            state(TreasureInstance("h1", "horse", 1), TreasureInstance("h2", "horse", 3), cards = listOf(horse))
        }
    }
}
