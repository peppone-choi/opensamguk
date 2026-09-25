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
                state.copy(people = listOf(actor.copy(meta = actor.meta + ("hwihaRetired" to true)), heir)))).reason)
    }
}
