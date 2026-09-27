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
        assertEquals(listOf(
            DuelRound(0, 17, 7, 30, 27, 143, 130),
            DuelRound(1, 5, 1, 29, 26, 117, 101),
            DuelRound(2, 10, 1, 30, 26, 91, 71),
            DuelRound(3, 7, 10, 29, 27, 64, 42),
            DuelRound(4, 13, 13, 30, 28, 36, 12),
            DuelRound(5, 7, 18, 12, 28, 8, 0),
        ), first.rounds)
        assertEquals(1, first.winnerId)
        assertEquals(mapOf(1 to 25, 2 to -35), first.moraleDeltas)
        assertEquals("1fb5570c0c2f83fe5a1a96d1cf61f59455df9382a728c891358fc32a46ffb0ac", first.replayHash)
        assertNotEquals(first.replayHash, declined.replayHash)
        val otherSeed = TacticalDuel.resolve(124, challenger, respondent, respondentHuman = true, humanAccepted = true)
        assertNotEquals(first.rounds.map { it.challengerRoll to it.respondentRoll },
            otherSeed.rounds.map { it.challengerRoll to it.respondentRoll })
        assertNotEquals(first.replayHash, otherSeed.replayHash)
    }

    @Test
    fun `simultaneous knockout is a draw without morale change`() {
        val first = general(3, 80)
        val second = general(4, 80)
        val result = TacticalDuel.resolve(0, first, second, respondentHuman = true, humanAccepted = true)
        assertEquals(0, result.rounds.last().challengerHealth)
        assertEquals(0, result.rounds.last().respondentHealth)
        assertEquals(null, result.winnerId)
        assertTrue(result.moraleDeltas.isEmpty())
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
