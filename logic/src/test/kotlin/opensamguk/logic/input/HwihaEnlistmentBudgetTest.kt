package opensamguk.logic.input

import kotlin.test.*

class HwihaEnlistmentBudgetTest {
    private fun person(id: Int, lord: Boolean = false) = PersonPolicyInput(
        id, if (id == 1) 0 else 1, 50, 50, 50, 50, 50,
        mapOf("hwihaLord" to lord, HwihaPersonPolicyState.META_KEY to
            HwihaPersonPolicyState(30, true, "fixture", "pin", id).toMetaValue()),
    )
    @Test fun `shared budget preserves direct costs and deterministic ordering without mutation`() {
        val persons = listOf(person(1), person(10, true), person(2), person(3))
        val cards = listOf(DirectPersonCard(1, 10, 2), DirectPersonCard(2, 2, 3))
        val result = assertIs<RenownBudgetResult.Ready>(HwihaEnlistmentBudget.assess(1, RuleProfile.HWIHA, persons, cards))
        assertEquals(mapOf(10 to 25), result.freeRenownByLord)
        assertEquals(5, result.actorCardCost)
        assertEquals(setOf(10), result.acceptingLordIds)
        assertEquals(result, HwihaEnlistmentBudget.assess(1, RuleProfile.HWIHA, persons.reversed(), cards.reversed()))
        assertEquals(30, HwihaPersonPolicyState.read(persons[1].meta)!!.renownCapacity)
    }
    @Test fun `shared failures distinguish global invalid state from unsupported target card`() {
        val people = listOf(person(1), person(10, true))
        val result = assertIs<RenownBudgetResult.Ready>(HwihaEnlistmentBudget.assess(1, RuleProfile.HWIHA, people,
            listOf(DirectPersonCard(1, 10, null))))
        assertEquals(mapOf(10 to RenownBudgetFailure.UNSUPPORTED_UNLINKED_CARD), result.unavailableLordReasons)
        assertTrue(result.freeRenownByLord.isEmpty())
        val bad = people + person(3).copy(nationId = 0, meta = mapOf("hwihaLord" to null))
        assertEquals(RenownBudgetResult.Unavailable(RenownBudgetFailure.INVALID_LORD_STATUS),
            HwihaEnlistmentBudget.assess(1, RuleProfile.HWIHA, bad, emptyList()))
    }
}
