package opensamguk.logic.battle.realtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class TacticalDuelTest {
    private fun general(id: Int, strength: Int, leadership: Int = 80) =
        GeneralStats(id, leadership, strength, 50, 50, 50)

    @Test
    fun `offer threshold and AI acceptance follow confirmed game ledger`() {
        assertTrue(!TacticalDuel.canOffer(general(1, 69)))
        assertTrue(TacticalDuel.canOffer(general(1, 70)))
        val accepted = TacticalDuel.resolve(17, general(1, 70), general(2, 55), respondentHuman = false)
        assertTrue(accepted.accepted)
        val declined = TacticalDuel.resolve(17, general(1, 70), general(2, 54), respondentHuman = false)
        assertTrue(!declined.accepted)
        assertTrue(declined.rounds.isEmpty())
        assertTrue(declined.moraleDeltas.isEmpty())
    }

    @Test
    fun `human may decline and accepted personal duel has repeatable bounded transcript`() {
        val challenger = general(1, 90)
        val respondent = general(2, 80)
        val declined = TacticalDuel.resolve(123, challenger, respondent, respondentHuman = true, humanAccepted = false)
        assertTrue(!declined.accepted && declined.winnerId == null && declined.rounds.isEmpty())
        val first = TacticalDuel.resolve(123, challenger, respondent, respondentHuman = true, humanAccepted = true)
        val replay = TacticalDuel.resolve(123, challenger, respondent, respondentHuman = true, humanAccepted = true)
        assertEquals(first, replay)
        assertTrue(first.rounds.isNotEmpty() && first.rounds.size <= TacticalRules.CANON.duelRoundLimit)
        assertTrue(first.rounds.all { it.challengerHealth >= 0 && it.respondentHealth >= 0 })
        assertNotEquals(first.replayHash, declined.replayHash)
        assertNotEquals(first.replayHash,
            TacticalDuel.resolve(124, challenger, respondent, respondentHuman = true, humanAccepted = true).replayHash)
    }

    @Test
    fun `human response is required and weak challenger cannot offer`() {
        assertFailsWith<IllegalArgumentException> {
            TacticalDuel.resolve(1, general(1, 80), general(2, 80), respondentHuman = true)
        }
        assertFailsWith<IllegalArgumentException> {
            TacticalDuel.resolve(1, general(1, 69), general(2, 70), respondentHuman = false)
        }
    }
}
