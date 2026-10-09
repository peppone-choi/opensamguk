package opensamguk.gameapi.domestic

import com.fasterxml.jackson.databind.ObjectMapper
import opensamguk.gameapi.read.*
import opensamguk.logic.domestic.*
import opensamguk.logic.input.*
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import java.util.Optional
import kotlin.test.*

class PlacementCorpsCommanderOptionsTest {
    private val now = Phase(200, 1, 1)
    private val owner = person(1, human = true).copy(meta = mapOf("lord" to true))
    private fun person(id: Int, human: Boolean = false, npc: Int = 2) =
        DomesticPerson(id, "인물$id", 1, human, npc, 0, 60, 60, 60, 60, 60, "p1", false, emptyMap())
    private fun state(card: DomesticCard = DomesticCard(4, 1, 2, "lieutenant"), npc: DomesticPerson = person(2)) =
        DomesticProjection(RuleProfile.HWIHA, now, listOf(owner, npc), listOf(card),
            listOf(DomesticCounty(10, "현", 1, "p1", "군", emptyMap())),
            listOf(DomesticNation(1, "세력", 10, emptyMap())), setOf("p1"))
    private fun posts(state: DomesticProjection) = DomesticViews.posts(1, DomesticSnapshot(state = state))
    private fun assertOption(state: DomesticProjection, failure: DomesticFailure?) {
        val view = posts(state)
        assertEquals("READY", view.status)
        val option = view.cards.single().corpsCommander
        assertEquals(failure == null, option.available)
        assertEquals(failure?.name, option.blocked?.code)
        assertEquals(failure?.message, option.blocked?.reason)
    }

    @Test fun `only lieutenant npc state two is eligible regardless of lord status`() {
        for (relation in listOf("lieutenant", "staff", "guest")) {
            for (npc in listOf(0, 1, 2, 3)) {
                val projection = state(DomesticCard(4, 1, 2, relation), person(2, npc = npc))
                val failure = DomesticFailure.NOT_LIEUTENANT.takeUnless { relation == "lieutenant" && npc == 2 }
                assertOption(projection, failure)
                assertOption(projection.copy(people = listOf(owner.copy(meta = emptyMap()), person(2, npc = npc))), failure)
            }
        }
    }

    @Test fun `staff remains placeable while corps command is denied without changing other posts`() {
        val view = posts(state(DomesticCard(4, 1, 2, "staff")))
        assertTrue(view.cards.single().placeable)
        assertNull(view.cards.single().blocked)
        assertFalse(view.cards.single().corpsCommander.available)
        assertEquals("NOT_LIEUTENANT", view.cards.single().corpsCommander.blocked?.code)
        assertEquals(listOf("MAGISTRATE", "CORPS_COMMANDER", "ENVOY", "SCOUT", "NONE"), view.posts.map { it.post })
        assertTrue(view.posts.single { it.post == "CORPS_COMMANDER" }.available)
        assertNull(view.posts.single { it.post == "CORPS_COMMANDER" }.blocked)
        assertNull(view.posts.single { it.post == "CORPS_COMMANDER" }.targets)
    }

    @Test fun `common card constraints take precedence over commander qualification`() {
        val base = state()
        val deployment = DeploymentState(listOf(DeployedCorps("o1", 1, 2, 4, 1, listOf(7), now)))
        val cases = listOf(
            base.copy(people = listOf(owner, person(2, human = true))) to DomesticFailure.HUMAN_CARD,
            base.copy(people = listOf(owner, person(2).copy(nationId = 2))) to DomesticFailure.DIFFERENT_NATION,
            base.copy(people = listOf(owner, person(2).copy(inBattle = true))) to DomesticFailure.CARD_IN_BATTLE,
            base.copy(people = listOf(owner.copy(meta = owner.meta + (DeploymentState.META_KEY to deployment.toMetaValue())),
                person(2))) to DomesticFailure.CARD_DEPLOYED,
            base.copy(cards = listOf(DomesticCard(4, 1, null, "lieutenant"))) to DomesticFailure.CARD_NOT_ON_MAP,
            base.copy(people = listOf(owner)) to DomesticFailure.CARD_NOT_FOUND,
        )
        for ((projection, failure) in cases) assertOption(projection, failure)
        val duplicates = posts(base.copy(cards = listOf(DomesticCard(4, 1, 2, "lieutenant"), DomesticCard(5, 1, 2, "staff"))))
        assertTrue(duplicates.cards.all { !it.corpsCommander.available && it.corpsCommander.blocked?.code == "STATE_UNAVAILABLE" })
        assertTrue(posts(base.copy(cards = listOf(DomesticCard(4, 99, 2, "lieutenant")))).cards.isEmpty())
    }

    @Test fun `commander assessment preserves unchanged and does not depend on a scout target`() {
        val order = PlacementOrder("r1", 1, 4, PlacementPost.CORPS_COMMANDER, PlacementTarget.None, now)
        val active = PlacementState(ActivePlacement(order, now, now), null)
        assertOption(state(npc = person(2).copy(meta = mapOf(PlacementState.META_KEY to active.toMetaValue()))),
            DomesticFailure.UNCHANGED)
        assertOption(state(npc = person(2).copy(meta = mapOf(PlacementState.META_KEY to
            PlacementState(null, order).toMetaValue()))), null)
        assertOption(state().copy(landProvinceIds = null), null)
        assertOption(state().copy(landProvinceIds = emptySet()), null)
    }

    @Test fun `wire has a non null commander option and exact reason without exposing npc state`() {
        val mapper = org.springframework.http.converter.json.Jackson2ObjectMapperBuilder.json().build<ObjectMapper>()
        val projection = state().copy(people = listOf(owner, person(2), person(3), person(4, human = true)),
            cards = listOf(DomesticCard(4, 1, 2, "lieutenant"), DomesticCard(5, 1, 3, "staff"),
                DomesticCard(6, 1, 4, "lieutenant")))
        val json = mapper.readTree(mapper.writeValueAsString(posts(projection)))
        val cards = json["cards"].associateBy { it["cardId"].asInt() }
        assertTrue(cards.getValue(4)["corpsCommander"]["available"].asBoolean())
        assertTrue(cards.getValue(4)["corpsCommander"]["blocked"].isNull)
        assertEquals("NOT_LIEUTENANT", cards.getValue(5)["corpsCommander"]["blocked"]["code"].asText())
        assertEquals(DomesticFailure.NOT_LIEUTENANT.message, cards.getValue(5)["corpsCommander"]["blocked"]["reason"].asText())
        assertEquals("HUMAN_CARD", cards.getValue(6)["corpsCommander"]["blocked"]["code"].asText())
        assertTrue(cards.getValue(6)["isHuman"].asBoolean())
        for (card in cards.values) {
            assertEquals(setOf("available", "blocked"), card["corpsCommander"].fieldNames().asSequence().toSet())
            assertFalse(card.has("npcState"))
        }
    }

    @Test fun `foreign and invalid owners cannot read placement options or the world snapshot`() {
        val generals = mock(GeneralReadRepository::class.java)
        val retainers = mock(RetainerReadRepository::class.java)
        val nations = mock(NationReadRepository::class.java)
        val artifacts = mock(ActiveWorldArtifactResolver::class.java)
        val spatial = mock(SpatialStateReadRepository::class.java)
        val geography = mock(CityGeography::class.java)
        val kv = mock(GameKvReadRepository::class.java)
        val diplomacy = mock(DiplomacyReadRepository::class.java)
        val sieges = mock(SiegeReadRepository::class.java)
        `when`(generals.findById(1)).thenReturn(Optional.of(GeneralReadEntity(id = 1, userId = "42")))
        val reader = DomesticReader(generals, retainers, nations, artifacts, spatial, geography, kv,
            ObjectMapper(), diplomacy, sieges)
        for (user in listOf(0L, 43L, Int.MAX_VALUE.toLong() + 1)) {
            assertFailsWith<DomesticForbidden> { reader.posts(1, user) }
        }
        verifyNoInteractions(retainers, nations, artifacts, spatial, geography, kv, diplomacy, sieges)
    }
}
