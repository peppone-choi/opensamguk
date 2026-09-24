package opensamguk.logic.input

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HwihaPersonalEncounterBattleTest {
    @Test
    fun `strong lone general can win and weak one retreats without affecting troop counts`() {
        val strong = HwihaPersonalEncounterBattle.Fighter(1, 100, 100, 0, 0, 100)
        val weak = HwihaPersonalEncounterBattle.Fighter(2, 10, 10, 0, 0, 100)
        val victory = HwihaPersonalEncounterBattle.resolve(strong, listOf(weak))
        assertEquals(HwihaPersonalEncounterBattle.Outcome.WON, victory.outcome)
        assertEquals(2, victory.defenderGeneralId)
        assertTrue(victory.attackerInjury > 0)
        val defeat = HwihaPersonalEncounterBattle.resolve(weak, listOf(strong))
        assertEquals(HwihaPersonalEncounterBattle.Outcome.RETREATED, defeat.outcome)
        assertEquals(defeat, HwihaPersonalEncounterBattle.resolve(weak, listOf(strong)))
    }

    @Test
    fun `low morale defeat produces a captive and tie choice is stable`() {
        val attacker = HwihaPersonalEncounterBattle.Fighter(1, 10, 10, 0, 0, 20)
        val defender = HwihaPersonalEncounterBattle.Fighter(3, 100, 100, 0, 0, 100)
        val result = HwihaPersonalEncounterBattle.resolve(attacker,
            listOf(defender, defender.copy(generalId = 2)))
        assertEquals(HwihaPersonalEncounterBattle.Outcome.CAPTURED, result.outcome)
        assertEquals(2, result.defenderGeneralId)
    }
}
