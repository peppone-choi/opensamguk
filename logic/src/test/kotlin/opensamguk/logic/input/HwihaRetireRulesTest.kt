package opensamguk.logic.input

import kotlin.test.*

class HwihaRetireRulesTest {
    private val actor = DomesticPerson(1, "주인", 1, true, 0, 12, 60, 60, 60, 60, 60,
        "province", false, mapOf(HwihaLordStatus.META_KEY to true))
    private val heir = actor.copy(id = 2, name = "후계", userOwned = false, npcState = 2,
        officerLevel = 1, meta = mapOf(HwihaLordStatus.META_KEY to false))
    private val state = HwihaDomesticProjection(RuleProfile.HWIHA, HwihaPhase(200, 1, 1),
        listOf(actor, heir), listOf(DomesticCard(10, 1, 2, "guest")), emptyList(),
        listOf(DomesticNation(1, "국", null, emptyMap())), setOf("province"))

    @Test fun `retiring lord chooses one direct retainer and the gate survives state changes`() {
        val req = HwihaRetireRequest(1, 2)
        assertTrue(assertIs<HwihaRetireAssessment.Eligible>(HwihaRetireRules.assess(req, state)).wasLord)
        assertEquals(HwihaRetireFailure.SUCCESSOR_NOT_RETAINER,
            assertIs<HwihaRetireAssessment.Rejected>(HwihaRetireRules.assess(req,
                state.copy(cards = emptyList()))).reason)
        assertEquals(HwihaRetireFailure.SUCCESSOR_UNAVAILABLE_FOR_CONTROL,
            assertIs<HwihaRetireAssessment.Rejected>(HwihaRetireRules.assess(req,
                state.copy(people = listOf(actor, heir.copy(userOwned = true))))).reason)
        assertEquals(HwihaRetireFailure.ALREADY_RETIRED,
            assertIs<HwihaRetireAssessment.Rejected>(HwihaRetireRules.assess(req,
                state.copy(people = listOf(actor.copy(meta = actor.meta + ("hwihaRetired" to true)), heir)))).reason)
    }
}
