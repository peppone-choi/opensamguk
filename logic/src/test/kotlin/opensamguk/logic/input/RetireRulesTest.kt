package opensamguk.logic.input

import opensamguk.logic.domestic.DomesticPerson
import opensamguk.logic.domestic.DomesticCard
import opensamguk.logic.domestic.DomesticNation
import opensamguk.logic.domestic.DomesticProjection

import kotlin.test.*

class RetireRulesTest {
    private val actor = DomesticPerson(1, "주인", 1, true, 0, 12, 60, 60, 60, 60, 60,
        "province", false, mapOf(LordStatus.META_KEY to true), age = 60)
    private val heir = actor.copy(id = 2, name = "후계", userOwned = false, npcState = 2,
        officerLevel = 1, meta = mapOf(LordStatus.META_KEY to false))
    private val state = DomesticProjection(RuleProfile.HWIHA, Phase(200, 1, 1),
        listOf(actor, heir), listOf(DomesticCard(10, 1, 2, "guest")), emptyList(),
        listOf(DomesticNation(1, "국", null, emptyMap())), setOf("province"))

    @Test fun `retirement uses the persisted age at the sixty year boundary`() {
        for (age in listOf(0, 20, 59, 60, 61)) {
            val snapshot = state.copy(people = listOf(actor.copy(age = age), heir))
            val result = RetireRules.assess(RetireRequest(1, 2), snapshot)
            if (age >= 60) assertIs<RetireAssessment.Eligible>(result)
            else assertEquals(RetireFailure.AGE_TOO_YOUNG, assertIs<RetireAssessment.Rejected>(result).reason)
            assertEquals(age, snapshot.person(1)!!.age)
            assertEquals(state.cards, snapshot.cards)
        }
        for (age in listOf(null, -1)) {
            val snapshot = state.copy(people = listOf(actor.copy(age = age), heir))
            assertEquals(RetireFailure.STATE_UNAVAILABLE,
                assertIs<RetireAssessment.Rejected>(RetireRules.assess(RetireRequest(1, 2), snapshot)).reason)
        }
    }

    @Test fun `only an active unowned NPC in the same nation can succeed`() {
        val invalid = listOf(heir.copy(nationId = 2), heir.copy(userOwned = true),
            heir.copy(meta = mapOf("retired" to true)), heir.copy(inBattle = true)) +
            listOf(-1, 0, 1, 3, 5, 6, 9, 99).map { heir.copy(npcState = it) }
        for (person in invalid) {
            val snapshot = state.copy(people = listOf(actor, person))
            assertEquals(RetireFailure.SUCCESSOR_UNAVAILABLE_FOR_CONTROL,
                assertIs<RetireAssessment.Rejected>(RetireRules.assess(RetireRequest(1, 2), snapshot)).reason,
                person.toString())
            assertEquals(listOf(actor, person), snapshot.people)
            assertEquals(state.cards, snapshot.cards)
        }
        assertIs<RetireAssessment.Eligible>(RetireRules.assess(RetireRequest(1, 2), state))
    }

    @Test fun `a lord cannot retire against a different bound ruler`() {
        val snapshot = state.copy(nations = listOf(state.nations.single().copy(chiefGeneralId = heir.id)))
        assertEquals(RetireFailure.STATE_UNAVAILABLE,
            assertIs<RetireAssessment.Rejected>(RetireRules.assess(RetireRequest(1, 2), snapshot)).reason)
        assertIs<RetireAssessment.Eligible>(RetireRules.assess(RetireRequest(1, 2),
            state.copy(nations = listOf(state.nations.single().copy(chiefGeneralId = actor.id)))))
    }

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
