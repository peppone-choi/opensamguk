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
        officerLevel = 1, meta = policy(2, 30))
    private fun policy(id: Int, capacity: Int) = mapOf(LordStatus.META_KEY to false,
        PersonPolicyState.META_KEY to PersonPolicyState(capacity, false, "synthetic-test", "1", id).toMetaValue())
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

    private fun withFollowers(capacity: Int): DomesticProjection {
        val inherited = heir.copy(id = 3, name = "승계 휘하", meta = policy(3, 0))
        val existing = heir.copy(id = 4, name = "기존 휘하", meta = policy(4, 0))
        return state.copy(people = listOf(actor, heir.copy(meta = policy(2, capacity)), inherited, existing),
            cards = state.cards + DomesticCard(11, actor.id, inherited.id, "lieutenant") +
                DomesticCard(12, heir.id, existing.id, "staff"))
    }

    @Test fun `post succession renown includes existing and inherited direct cards at the exact boundary`() {
        // Each linked person costs 6 under the existing rule; the successor's consumed card is excluded.
        for (capacity in listOf(0, 11, 12, 13)) {
            val snapshot = withFollowers(capacity)
            val result = RetireRules.assess(RetireRequest(1, 2), snapshot)
            if (capacity >= 12) assertIs<RetireAssessment.Eligible>(result)
            else assertEquals(RetireFailure.SUCCESSOR_RENOWN_EXCEEDED,
                assertIs<RetireAssessment.Rejected>(result).reason)
            assertEquals(capacity, PersonPolicyState.read(snapshot.person(2)!!.meta)!!.renownCapacity)
            assertEquals(3, snapshot.cards.size)
        }
    }

    @Test fun `zero capacity permits an empty resulting retinue without counting actor or successor`() {
        val snapshot = state.copy(people = listOf(actor.copy(meta = actor.meta +
            (PersonPolicyState.META_KEY to policy(1, Int.MAX_VALUE).getValue(PersonPolicyState.META_KEY))),
            heir.copy(meta = policy(2, 0))))
        assertIs<RetireAssessment.Eligible>(RetireRules.assess(RetireRequest(1, 2), snapshot))
        val one = withFollowers(0).let { it.copy(cards = it.cards.filter { card -> card.id != 12 }) }
        assertEquals(RetireFailure.SUCCESSOR_RENOWN_EXCEEDED,
            assertIs<RetireAssessment.Rejected>(RetireRules.assess(RetireRequest(1, 2), one)).reason)
    }

    @Test fun `renown uses persisted linked stats and the successors cap regardless of enlistment flag`() {
        val snapshot = withFollowers(8).let { it.copy(people = it.people.map { person ->
            if (person.id == 3) person.copy(leadership = 0, strength = 0, intelligence = 0, politics = 0, charm = 0)
            else person
        }) }
        // Minimum person cost 1 plus existing follower cost 6 fits capacity 8.
        assertIs<RetireAssessment.Eligible>(RetireRules.assess(RetireRequest(1, 2), snapshot))
        val decreasedCap = snapshot.copy(people = snapshot.people.map {
            if (it.id == 2) it.copy(meta = policy(2, 6)) else it
        })
        assertEquals(RetireFailure.SUCCESSOR_RENOWN_EXCEEDED,
            assertIs<RetireAssessment.Rejected>(RetireRules.assess(RetireRequest(1, 2), decreasedCap)).reason)
    }

    @Test fun `missing corrupt or unsupported renown state fails closed without a fallback cap`() {
        val snapshot = withFollowers(30)
        val invalid = listOf(
            snapshot.copy(people = snapshot.people.map { if (it.id == 2) it.copy(meta = emptyMap()) else it }),
            snapshot.copy(people = snapshot.people.map { if (it.id == 2)
                it.copy(meta = policy(2, 30) + (PersonPolicyState.META_KEY to mapOf("renownCapacity" to 30))) else it }),
            snapshot.copy(people = snapshot.people.map { if (it.id == 3) it.copy(strength = -1) else it }),
            snapshot.copy(people = snapshot.people.map { if (it.id == 3) it.copy(meta = emptyMap()) else it }),
            snapshot.copy(people = snapshot.people.filter { it.id != 3 },
                cards = snapshot.cards.map { if (it.id == 11) it.copy(name = "승계 휘하") else it }),
            snapshot.copy(cards = snapshot.cards + DomesticCard(13, 1, null, "guest", "미연결")),
            snapshot.copy(cards = snapshot.cards + DomesticCard(13, 2, 3, "guest", "중복 연결")),
            snapshot.copy(cards = snapshot.cards + DomesticCard(13, 2, 2, "guest", "자기 연결")),
        )
        for (case in invalid) assertEquals(RetireFailure.STATE_UNAVAILABLE,
            assertIs<RetireAssessment.Rejected>(RetireRules.assess(RetireRequest(1, 2), case)).reason, case.toString())
    }

    @Test fun `overflowing occupied cost never wraps into an eligible budget`() {
        val people = (3..13).map { heir.copy(id = it, name = "G$it", meta = policy(it, 0),
            leadership = Int.MAX_VALUE, strength = Int.MAX_VALUE, intelligence = Int.MAX_VALUE,
            politics = Int.MAX_VALUE, charm = Int.MAX_VALUE) }
        val snapshot = state.copy(people = listOf(actor, heir.copy(meta = policy(2, Int.MAX_VALUE))) + people,
            cards = state.cards + people.map { DomesticCard(it.id + 20, 1, it.id, "guest") })
        assertEquals(RetireFailure.STATE_UNAVAILABLE,
            assertIs<RetireAssessment.Rejected>(RetireRules.assess(RetireRequest(1, 2), snapshot)).reason)
    }
}
