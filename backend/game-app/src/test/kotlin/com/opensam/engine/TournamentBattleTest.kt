package com.opensam.engine

import com.opensam.engine.TournamentBattle.TOURNAMENT_STRENGTH
import com.opensam.engine.TournamentBattle.TOURNAMENT_TOTAL
import com.opensam.engine.TournamentBattle.TournamentBattleContext
import com.opensam.engine.TournamentBattle.TournamentBattleInput
import com.opensam.engine.TournamentBattle.TournamentParticipant
import com.opensam.engine.TournamentBattle.TournamentStats
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TournamentBattleTest {

    private fun createInput(
        type: Int = TOURNAMENT_STRENGTH,
        battleType: Int = 1,
        attackerStats: TournamentStats = TournamentStats(70.0, 90.0, 60.0),
        defenderStats: TournamentStats = TournamentStats(60.0, 50.0, 70.0),
        attackerLevel: Int = 10,
        defenderLevel: Int = 10,
    ): TournamentBattleInput {
        return TournamentBattleInput(
            type = type,
            battleType = battleType,
            attacker = TournamentParticipant(1L, "관우", attackerStats, attackerLevel),
            defender = TournamentParticipant(2L, "장비", defenderStats, defenderLevel),
            context = TournamentBattleContext(
                openYear = 200,
                openMonth = 6,
                stage = 1,
                phase = 1,
                matchIndex = 0,
            ),
            baseSeed = "test-seed-fixed",
        )
    }

    @Test
    fun `battle produces deterministic result with same seed`() {
        val input = createInput()

        val result1 = TournamentBattle.resolveTournamentBattle(input)
        val result2 = TournamentBattle.resolveTournamentBattle(input)

        assertEquals(result1.winnerId, result2.winnerId)
        assertEquals(result1.rounds, result2.rounds)
        assertEquals(result1.totalDamage, result2.totalDamage)
        assertEquals(result1.draw, result2.draw)
    }

    @Test
    fun `battle has a winner or draw`() {
        val input = createInput()

        val result = TournamentBattle.resolveTournamentBattle(input)

        if (result.draw) {
            assertNull(result.winnerId)
            assertNull(result.loserId)
        } else {
            assertNotNull(result.winnerId)
            assertNotNull(result.loserId)
            assertNotEquals(result.winnerId, result.loserId)
        }
    }

    @Test
    fun `battle generates log entries`() {
        val input = createInput()

        val result = TournamentBattle.resolveTournamentBattle(input)

        assertTrue(result.log.isNotEmpty(), "Log should not be empty")
        assertTrue(result.logEntries.isNotEmpty(), "Log entries should not be empty")
        assertEquals(result.rounds, result.logEntries.size, "Rounds should match log entries count")
    }

    @Test
    fun `TOURNAMENT_TOTAL uses combined stat`() {
        val stats = TournamentStats(60.0, 60.0, 60.0)
        val input = createInput(
            type = TOURNAMENT_TOTAL,
            attackerStats = stats,
            defenderStats = stats,
        )

        val result = TournamentBattle.resolveTournamentBattle(input)

        assertTrue(result.rounds > 0)
        // With equal stats and total type, it should be close or draw
        assertTrue(result.totalDamage.attacker > 0 || result.totalDamage.defender > 0)
    }

    @Test
    fun `strongly superior attacker tends to win`() {
        val input = createInput(
            attackerStats = TournamentStats(99.0, 99.0, 99.0),
            defenderStats = TournamentStats(10.0, 10.0, 10.0),
            attackerLevel = 20,
            defenderLevel = 1,
        )

        val result = TournamentBattle.resolveTournamentBattle(input)

        // With such a large disparity, the attacker should win
        assertEquals(1L, result.winnerId, "Attacker with vastly superior stats should win")
        assertFalse(result.draw)
    }
}
