package opensamguk.logic.war

import kotlin.test.*
import opensamguk.logic.world.*

class SiegeAssaultTest {
    private val layout = assertIs<BattlefieldLayout.Result.Ready>(BattlefieldLayout.prepare(
        HanProvinceCellIndex("qa", "a".repeat(64), "b".repeat(64), 12, 2, mapOf('1' to "PLAIN"),
            mapOf("A" to listOf(HanProvinceCell(0, 0, '1'), HanProvinceCell(0, 1, '1')),
                "B" to (0..1).flatMap { row -> (1..10).map { HanProvinceCell(it, row, '1') } })), "B", "A")).layout
    private val infantry = UnitProfile(1100, 1, 1, 100, 120, 20)
    private fun army(troops: Int) = listOf(SiegeAssault.Attacker(7, troops, 50, 50, 0, infantry),
        SiegeAssault.Attacker(8, troops, 50, 50, 0, infantry))

    @Test fun `overwhelming assault breaks every wall token`() {
        val result = SiegeAssault.resolve(layout, army(5000), 70, 400, 100, 0)
        assertEquals(SiegeAssault.Outcome.CAPTURED, result.outcome)
        assertEquals(0, result.garrisonRemaining)
        assertTrue(result.rounds in 1..BattlePlans.MAX_ROUNDS)
        assertTrue(result.attackers.sumOf { it.troops } < 10_000, "the attacker pays for the walls")
    }

    @Test fun `walls repulse a thin assault and strengthen the defence`() {
        val weak = SiegeAssault.resolve(layout, army(100), 50, 2000, 100, 100)
        assertEquals(SiegeAssault.Outcome.REPULSED, weak.outcome)
        assertTrue(weak.garrisonRemaining > 0)
        val bare = SiegeAssault.resolve(layout, army(1000), 50, 1000, 100, 0)
        val walled = SiegeAssault.resolve(layout, army(1000), 50, 1000, 100, 100)
        assertTrue(walled.garrisonRemaining >= bare.garrisonRemaining, "walls never make the defence weaker")
        val fortified = SiegeAssault.resolve(layout, army(1000), 50, 1000, 100, 0,
            defenceBonusPercent = CampaignBalance.ASSAULT_MAX_DEFENCE_BONUS_PERCENT)
        assertTrue(fortified.garrisonRemaining >= bare.garrisonRemaining, "county defence protects the garrison")
    }

    @Test fun `an undefended county falls without a round and results are reproducible`() {
        val empty = SiegeAssault.resolve(layout, army(100), 50, 0, 100, 0)
        assertEquals(SiegeAssault.Outcome.CAPTURED, empty.outcome); assertEquals(0, empty.rounds)
        val a = SiegeAssault.resolve(layout, army(900), 60, 700, 80, 40)
        val b = SiegeAssault.resolve(layout, army(900), 60, 700, 80, 40)
        assertEquals(a.replayHash, b.replayHash); assertEquals(a.attackers, b.attackers)
    }
}
