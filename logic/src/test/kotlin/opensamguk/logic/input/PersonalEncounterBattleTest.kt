package opensamguk.logic.input

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PersonalEncounterBattleTest {
    @Test
    fun `strong lone general can win and weak one retreats without affecting troop counts`() {
        val strong = PersonalEncounterBattle.Fighter(1, 100, 100, 0, 0, 100)
        val weak = PersonalEncounterBattle.Fighter(2, 10, 10, 0, 0, 100)
        val victory = PersonalEncounterBattle.resolve(strong, listOf(weak))
        assertEquals(PersonalEncounterBattle.Outcome.WON, victory.outcome)
        assertEquals(2, victory.defenderGeneralId)
        assertTrue(victory.attackerInjury > 0)
        val defeat = PersonalEncounterBattle.resolve(weak, listOf(strong))
        assertEquals(PersonalEncounterBattle.Outcome.RETREATED, defeat.outcome)
        assertEquals(defeat, PersonalEncounterBattle.resolve(weak, listOf(strong)))
    }

    @Test
    fun `low morale defeat produces a captive and tie choice is stable`() {
        val attacker = PersonalEncounterBattle.Fighter(1, 10, 10, 0, 0, 20)
        val defender = PersonalEncounterBattle.Fighter(3, 100, 100, 0, 0, 100)
        val result = PersonalEncounterBattle.resolve(attacker,
            listOf(defender, defender.copy(generalId = 2)))
        assertEquals(PersonalEncounterBattle.Outcome.CAPTURED, result.outcome)
        assertEquals(2, result.defenderGeneralId)
    }
}
