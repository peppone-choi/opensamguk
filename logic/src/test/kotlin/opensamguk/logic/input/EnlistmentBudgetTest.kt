package opensamguk.logic.input

import kotlin.test.*

class EnlistmentBudgetTest {
    private fun person(id: Int, lord: Boolean = false) = PersonPolicyInput(
        id, if (id == 1) 0 else 1, 50, 50, 50, 50, 50,
        mapOf("lord" to lord, PersonPolicyState.META_KEY to
            PersonPolicyState(30, true, "fixture", "pin", id).toMetaValue()),
    )
    @Test fun `shared budget preserves direct costs and deterministic ordering without mutation`() {
        val persons = listOf(person(1), person(10, true), person(2), person(3))
        val cards = listOf(DirectPersonCard(1, 10, 2), DirectPersonCard(2, 2, 3))
        val result = assertIs<RenownBudgetResult.Ready>(EnlistmentBudget.assess(1, RuleProfile.HWIHA, persons, cards))
        assertEquals(mapOf(10 to 25), result.freeRenownByLord)
        assertEquals(25, result.freeRenownByOwner[2], "a nested general pays for their own direct NPC")
        assertEquals(25, result.freeRenownByOwner[10], "the lord pays only for the general card")
        assertEquals(5, result.actorCardCost)
        assertEquals(setOf(10), result.acceptingLordIds)
        assertEquals(result, EnlistmentBudget.assess(1, RuleProfile.HWIHA, persons.reversed(), cards.reversed()))
        assertEquals(30, PersonPolicyState.read(persons[1].meta)!!.renownCapacity)
    }
    @Test fun `over capacity personal retinue blocks its owner without charging the upper lord`() {
        val people = listOf(person(1), person(10, true), person(2).copy(meta = mapOf(
            "lord" to false, PersonPolicyState.META_KEY to
                PersonPolicyState(4, true, "fixture", "pin", 2).toMetaValue())), person(3))
        val result = assertIs<RenownBudgetResult.Ready>(EnlistmentBudget.assess(1, RuleProfile.HWIHA, people,
            listOf(DirectPersonCard(1, 10, 2), DirectPersonCard(2, 2, 3))))
        assertEquals(25, result.freeRenownByLord[10])
        assertEquals(RenownBudgetFailure.CAPACITY_EXCEEDED, result.unavailableOwnerReasons[2])
    }
    @Test fun `shared failures distinguish global invalid state from unsupported target card`() {
        val people = listOf(person(1), person(10, true))
        val result = assertIs<RenownBudgetResult.Ready>(EnlistmentBudget.assess(1, RuleProfile.HWIHA, people,
            listOf(DirectPersonCard(1, 10, null))))
        assertEquals(mapOf(10 to RenownBudgetFailure.UNSUPPORTED_UNLINKED_CARD), result.unavailableLordReasons)
        assertTrue(result.freeRenownByLord.isEmpty())
        val bad = people + person(3).copy(nationId = 0, meta = mapOf("lord" to null))
        assertEquals(RenownBudgetResult.Unavailable(RenownBudgetFailure.INVALID_LORD_STATUS),
            EnlistmentBudget.assess(1, RuleProfile.HWIHA, bad, emptyList()))
    }
}
