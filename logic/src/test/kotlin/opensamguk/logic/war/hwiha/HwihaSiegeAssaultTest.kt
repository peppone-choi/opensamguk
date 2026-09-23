package opensamguk.logic.war.hwiha

import kotlin.test.*
import opensamguk.logic.world.*

class HwihaSiegeAssaultTest {
    private val layout = assertIs<HwihaBattlefieldLayout.Result.Ready>(HwihaBattlefieldLayout.prepare(
        HanProvinceCellIndex("qa", "a".repeat(64), "b".repeat(64), 12, 2, mapOf('1' to "PLAIN"),
            mapOf("A" to listOf(HanProvinceCell(0, 0, '1'), HanProvinceCell(0, 1, '1')),
                "B" to (0..1).flatMap { row -> (1..10).map { HanProvinceCell(it, row, '1') } })), "B", "A")).layout
    private val infantry = HwihaUnitProfile(1100, 1, 1, 100, 120, 20)
    private fun army(troops: Int) = listOf(HwihaSiegeAssault.Attacker(7, troops, 50, 50, 0, infantry),
        HwihaSiegeAssault.Attacker(8, troops, 50, 50, 0, infantry))

    @Test fun `overwhelming assault breaks every wall token`() {
        val result = HwihaSiegeAssault.resolve(layout, army(5000), 70, 400, 100, 0)
        assertEquals(HwihaSiegeAssault.Outcome.CAPTURED, result.outcome)
        assertEquals(0, result.garrisonRemaining)
        assertTrue(result.rounds in 1..HwihaBattlePlans.MAX_ROUNDS)
        assertTrue(result.attackers.sumOf { it.troops } < 10_000, "the attacker pays for the walls")
    }

    @Test fun `walls repulse a thin assault and strengthen the defence`() {
        val weak = HwihaSiegeAssault.resolve(layout, army(100), 50, 2000, 100, 100)
        assertEquals(HwihaSiegeAssault.Outcome.REPULSED, weak.outcome)
        assertTrue(weak.garrisonRemaining > 0)
        val bare = HwihaSiegeAssault.resolve(layout, army(1000), 50, 1000, 100, 0)
        val walled = HwihaSiegeAssault.resolve(layout, army(1000), 50, 1000, 100, 100)
        assertTrue(walled.garrisonRemaining >= bare.garrisonRemaining, "walls never make the defence weaker")
    }

    @Test fun `an undefended county falls without a round and results are reproducible`() {
        val empty = HwihaSiegeAssault.resolve(layout, army(100), 50, 0, 100, 0)
        assertEquals(HwihaSiegeAssault.Outcome.CAPTURED, empty.outcome); assertEquals(0, empty.rounds)
        val a = HwihaSiegeAssault.resolve(layout, army(900), 60, 700, 80, 40)
        val b = HwihaSiegeAssault.resolve(layout, army(900), 60, 700, 80, 40)
        assertEquals(a.replayHash, b.replayHash); assertEquals(a.attackers, b.attackers)
    }
}
