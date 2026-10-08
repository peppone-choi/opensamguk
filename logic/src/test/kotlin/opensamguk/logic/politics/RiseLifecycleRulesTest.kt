package opensamguk.logic.politics

import kotlin.test.*
import opensamguk.logic.domestic.*
import opensamguk.logic.input.*

/** Structural lifecycle guards with the approved zero-renown fixture. */
class RiseLifecycleRulesTest {
    private fun person(id: Int, nationId: Int = 0, lord: Boolean = false) = DomesticPerson(
        id, "장수$id", nationId, id == 1, 0, if (lord) 12 else 0,
        60, 60, 60, 60, 60, "county-province", false,
        mapOf(LordStatus.META_KEY to lord, PersonPolicyState.META_KEY to
            PersonPolicyState(0, true,
                "synthetic-rise-structure", "v1", id).toMetaValue()), troopId = 0, spatialStateAvailable = true)

    private fun state(people: List<DomesticPerson> = listOf(person(1)),
        cards: List<DomesticCard> = emptyList()) = DomesticProjection(
        RuleProfile.HWIHA, Phase(200, 1, 1), people, cards,
        listOf(DomesticCounty(10, "빈 현", 0, "county-province", "郡", emptyMap())),
        emptyList(), setOf("county-province"), troops = emptyList())

    private fun failure(state: DomesticProjection) = assertIs<PoliticalAssessment.Rejected>(
        PoliticalRules.assess(PoliticalRequest(1, PoliticalInput.RISE), state)).reason

    @Test fun `a wandering lord cannot acquire a second lordship by rising`() {
        assertEquals(PoliticalFailure.ALREADY_LORD, failure(state(listOf(person(1, lord = true)))))
    }

    @Test fun `an incoming personal parent prevents a free founder claim`() {
        assertEquals(PoliticalFailure.NOT_FREE, failure(state(listOf(person(1), person(2)),
            listOf(DomesticCard(10, 2, 1, "guest")))))
    }

    @Test fun `ambiguous dangling cyclic and foreign retinues fail closed`() {
        val cases = listOf(
            state(listOf(person(1), person(2), person(3)),
                listOf(DomesticCard(10, 1, 2, "guest"), DomesticCard(11, 3, 2, "guest"))),
            state(listOf(person(1)), listOf(DomesticCard(10, 1, 2, "guest"))),
            state(listOf(person(1), person(2)),
                listOf(DomesticCard(10, 1, 2, "guest"), DomesticCard(11, 2, 1, "guest"))),
            state(listOf(person(1), person(2, nationId = 9)),
                listOf(DomesticCard(10, 1, 2, "guest"))),
        )
        cases.forEach { assertEquals(PoliticalFailure.STATE_UNAVAILABLE, failure(it)) }
    }

    @Test fun `a captured descendant cannot change allegiance through a founder`() {
        val follower = person(2).let { it.copy(meta = it.meta + (CaptiveState.META_KEY to
            CaptiveState(9, "county-province", Phase(200, 1, 1), "encounter-2").toMetaValue())) }
        assertEquals(PoliticalFailure.BATTLE_PENDING, failure(state(listOf(person(1), follower),
            listOf(DomesticCard(10, 1, 2, "guest")))))
    }

    @Test fun `a descendant in a battlefield cannot change allegiance through a founder`() {
        assertEquals(PoliticalFailure.BATTLE_PENDING,
            failure(state(listOf(person(1), person(2).copy(inBattle = true)),
                listOf(DomesticCard(10, 1, 2, "guest")))))
    }

    @Test fun `unknown missing foreign and mixed troop membership fail closed`() {
        val cases = listOf(
            state().copy(troops = null),
            state(listOf(person(1).copy(troopId = null))),
            state(listOf(person(1).copy(troopId = 1))),
            state(listOf(person(1).copy(troopId = 1))).copy(troops = listOf(DomesticTroop(1, 9))),
            state(listOf(person(1).copy(troopId = 2), person(2).copy(troopId = 2)))
                .copy(troops = listOf(DomesticTroop(2, 0))),
            state(listOf(person(1).copy(troopId = 1), person(2).copy(troopId = 1)))
                .copy(troops = listOf(DomesticTroop(1, 0))),
            state().copy(troops = listOf(DomesticTroop(1, 0))),
        )
        cases.forEach { assertEquals(PoliticalFailure.STATE_UNAVAILABLE, failure(it)) }
    }

    @Test fun `a coherent own troop is eligible without changing resources or personal relations`() {
        val snapshot = state(listOf(person(1).copy(troopId = 1), person(2).copy(troopId = 1)),
            listOf(DomesticCard(10, 1, 2, "guest"))).copy(troops = listOf(DomesticTroop(1, 0)))
        val ready = assertIs<PoliticalAssessment.Eligible>(PoliticalRules.assess(
            PoliticalRequest(1, PoliticalInput.RISE), snapshot))
        assertEquals(listOf(1, 2), ready.movingGeneralIds)
        assertEquals(0, snapshot.people[0].nationId)
        assertEquals(0, snapshot.people[1].nationId)
        assertEquals(1, snapshot.cards.single().masterId)
        assertEquals(DomesticTroop(1, 0), snapshot.troops!!.single())
    }

    @Test fun `known nonland descendant state is distinct from an unknown battlefield`() {
        val snapshot = state(listOf(person(1), person(2).copy(node = null)),
            listOf(DomesticCard(10, 1, 2, "guest")))
        assertIs<PoliticalAssessment.Eligible>(PoliticalRules.assess(PoliticalRequest(1, PoliticalInput.RISE), snapshot))
        assertEquals(PoliticalFailure.STATE_UNAVAILABLE, failure(snapshot.copy(
            people = listOf(person(1), person(2).copy(node = null, spatialStateAvailable = false)))))
    }
}
