package opensamguk.gameapi.read

import com.fasterxml.jackson.databind.ObjectMapper
import opensamguk.logic.domestic.DomesticCard
import opensamguk.logic.domestic.DomesticCounty
import opensamguk.logic.domestic.DomesticFailure
import opensamguk.logic.domestic.DomesticNation
import opensamguk.logic.domestic.DomesticPerson
import opensamguk.logic.domestic.DomesticProjection
import opensamguk.logic.input.Phase
import opensamguk.logic.input.RuleProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlacementHumanWireTest {
    private fun person(id: Int, human: Boolean) = DomesticPerson(id, "인물$id", 1, human,
        if (human) 0 else 2, if (id == 7) 12 else 0, 60, 60, 60, 60, 60,
        "province-a", false, mapOf("lord" to (id == 7)))
    private val state = DomesticProjection(RuleProfile.HWIHA, Phase(200, 1, 1),
        listOf(person(7, true), person(8, false), person(9, true)),
        listOf(DomesticCard(11, 7, 8, "guest"), DomesticCard(12, 7, 9, "guest"),
            DomesticCard(13, 7, 10, "guest")),
        listOf(DomesticCounty(20, "縣", 1, "province-a", "郡", emptyMap())),
        listOf(DomesticNation(1, "세력", 20, emptyMap())), setOf("province-a"))

    private fun posts(projection: DomesticProjection = state) =
        DomesticViews.posts(7, DomesticSnapshot(state = projection))

    @Test fun `posts identify the actual npc and human and leave an absent person unknown`() {
        val view = posts()
        assertEquals("READY", view.status)
        val cards = view.cards.associateBy { it.cardId }
        assertEquals(false, cards.getValue(11).isHuman)
        assertTrue(cards.getValue(11).placeable)
        assertNull(cards.getValue(11).blocked)
        assertEquals(true, cards.getValue(12).isHuman)
        assertFalse(cards.getValue(12).placeable)
        assertEquals(DomesticFailure.HUMAN_CARD.name, cards.getValue(12).blocked?.code)
        assertNull(cards.getValue(13).isHuman)
        assertFalse(cards.getValue(13).placeable)
        assertEquals(DomesticFailure.CARD_NOT_FOUND.name, cards.getValue(13).blocked?.code)
    }

    @Test fun `identity does not make a foreign or battling npc placeable`() {
        for ((npc, failure) in listOf(
            person(8, false).copy(nationId = 2) to DomesticFailure.DIFFERENT_NATION,
            person(8, false).copy(inBattle = true) to DomesticFailure.CARD_IN_BATTLE,
        )) {
            val card = posts(state.copy(people = listOf(person(7, true), npc, person(9, true))))
                .cards.single { it.cardId == 11 }
            assertEquals(false, card.isHuman)
            assertFalse(card.placeable)
            assertEquals(failure.name, card.blocked?.code)
        }
    }

    @Test fun `posts serialize the exact isHuman key including false true and null`() {
        val mapper = org.springframework.http.converter.json.Jackson2ObjectMapperBuilder.json()
            .build<ObjectMapper>()
        val json = mapper.readTree(mapper.writeValueAsString(posts()))
        val cards = json.get("cards").associateBy { it.get("cardId").asInt() }
        assertTrue(cards.getValue(11).has("isHuman"))
        assertFalse(cards.getValue(11).get("isHuman").booleanValue())
        assertTrue(cards.getValue(12).get("isHuman").booleanValue())
        assertTrue(cards.getValue(13).get("isHuman").isNull)
        assertTrue(cards.values.none { it.has("human") })
    }
}
