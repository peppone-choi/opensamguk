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

    @Test fun `retirement rejects a name collision in the successor's cards before any write`() {
        val ownFollower = DomesticCard(11, actor.id, null, "guest", "동명")
        val successorFollower = DomesticCard(12, heir.id, null, "guest", "동명")
        val conflict = state.copy(cards = state.cards + ownFollower + successorFollower)

        assertEquals(HwihaRetireFailure.RETAINER_NAME_CONFLICT,
            assertIs<HwihaRetireAssessment.Rejected>(HwihaRetireRules.assess(HwihaRetireRequest(1, 2), conflict)).reason)
    }

    @Test fun `retirement rejects renaming an outer card onto another card's name`() {
        val outerCard = DomesticCard(20, 3, actor.id, "guest", actor.name)
        val sameName = DomesticCard(21, 3, null, "guest", heir.name)
        val conflict = state.copy(cards = state.cards + outerCard + sameName)

        assertEquals(HwihaRetireFailure.RETAINER_NAME_CONFLICT,
            assertIs<HwihaRetireAssessment.Rejected>(HwihaRetireRules.assess(HwihaRetireRequest(1, 2), conflict)).reason)
    }

    @Test fun `retirement rejects a reciprocal retainer link in shared assessment`() {
        val reciprocal = DomesticCard(20, heir.id, actor.id, "guest", actor.name)
        val conflict = state.copy(cards = state.cards + reciprocal)

        assertEquals(HwihaRetireFailure.STATE_UNAVAILABLE,
            assertIs<HwihaRetireAssessment.Rejected>(HwihaRetireRules.assess(HwihaRetireRequest(1, 2), conflict)).reason)
    }
}
