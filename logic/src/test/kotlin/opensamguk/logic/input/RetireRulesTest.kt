package opensamguk.logic.input

import opensamguk.logic.domestic.DomesticPerson
import opensamguk.logic.domestic.DomesticCard
import opensamguk.logic.domestic.DomesticNation
import opensamguk.logic.domestic.DomesticProjection

import kotlin.test.*

class RetireRulesTest {
    private val actor = DomesticPerson(1, "주인", 1, true, 0, 12, 60, 60, 60, 60, 60,
        "province", false, mapOf(LordStatus.META_KEY to true))
    private val heir = actor.copy(id = 2, name = "후계", userOwned = false, npcState = 2,
        officerLevel = 1, meta = mapOf(LordStatus.META_KEY to false))
    private val state = DomesticProjection(RuleProfile.HWIHA, Phase(200, 1, 1),
        listOf(actor, heir), listOf(DomesticCard(10, 1, 2, "guest")), emptyList(),
        listOf(DomesticNation(1, "국", null, emptyMap())), setOf("province"))

    @Test fun `retiring lord chooses one direct retainer and the gate survives state changes`() {
        val req = RetireRequest(1, 2)
        assertTrue(assertIs<RetireAssessment.Eligible>(RetireRules.assess(req, state)).wasLord)
        assertEquals(RetireFailure.SUCCESSOR_NOT_RETAINER,
            assertIs<RetireAssessment.Rejected>(RetireRules.assess(req,
                state.copy(cards = emptyList()))).reason)
        assertEquals(RetireFailure.SUCCESSOR_UNAVAILABLE_FOR_CONTROL,
            assertIs<RetireAssessment.Rejected>(RetireRules.assess(req,
                state.copy(people = listOf(actor, heir.copy(userOwned = true))))).reason)
        assertEquals(RetireFailure.ALREADY_RETIRED,
            assertIs<RetireAssessment.Rejected>(RetireRules.assess(req,
                state.copy(people = listOf(actor.copy(meta = actor.meta + ("retired" to true)), heir)))).reason)
    }

    @Test fun `retirement rejects a name collision in the successor's cards before any write`() {
        val ownFollower = DomesticCard(11, actor.id, null, "guest", "동명")
        val successorFollower = DomesticCard(12, heir.id, null, "guest", "동명")
        val conflict = state.copy(cards = state.cards + ownFollower + successorFollower)

        assertEquals(RetireFailure.RETAINER_NAME_CONFLICT,
            assertIs<RetireAssessment.Rejected>(RetireRules.assess(RetireRequest(1, 2), conflict)).reason)
    }

    @Test fun `retirement rejects renaming an outer card onto another card's name`() {
        val outerCard = DomesticCard(20, 3, actor.id, "guest", actor.name)
        val sameName = DomesticCard(21, 3, null, "guest", heir.name)
        val conflict = state.copy(cards = state.cards + outerCard + sameName)

        assertEquals(RetireFailure.RETAINER_NAME_CONFLICT,
            assertIs<RetireAssessment.Rejected>(RetireRules.assess(RetireRequest(1, 2), conflict)).reason)
    }

    @Test fun `retirement rejects a reciprocal retainer link in shared assessment`() {
        val reciprocal = DomesticCard(20, heir.id, actor.id, "guest", actor.name)
        val conflict = state.copy(cards = state.cards + reciprocal)

        assertEquals(RetireFailure.STATE_UNAVAILABLE,
            assertIs<RetireAssessment.Rejected>(RetireRules.assess(RetireRequest(1, 2), conflict)).reason)
    }
}
