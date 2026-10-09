package opensamguk.logic.war

import kotlin.test.*
import opensamguk.logic.world.*

class SiegeApproachTest {
    private val infantry = UnitProfile(1100, 1, 1, 100, 120, 20)
    private val archer = UnitProfile(1200, 1, 3, 80, 80, 10)
    private val cavalry = UnitProfile(1300, 2, 1, 110, 100, 30)

    private fun layout(distance: Int, direction: Int = 0): BattlefieldLayout {
        fun cell(step: Int): ProvinceCell = when (direction) {
            0 -> ProvinceCell(step, 0, '1')
            1 -> ProvinceCell(distance + 1 - step, 0, '1')
            2 -> ProvinceCell(0, step, '1')
            else -> ProvinceCell(0, distance + 1 - step, '1')
        }
        val source = ProvinceCellIndex("test", "a".repeat(64), "b".repeat(64), distance + 2, distance + 2,
            mapOf('1' to "PLAIN"), mapOf("approach" to listOf(cell(0)),
                "battle" to (1..distance + 1).map(::cell).sortedWith(compareBy(ProvinceCell::row, ProvinceCell::col))))
        return requireNotNull(SiegeRules.assaultLayout(source, "battle", "approach"))
    }

    @Test fun `all four entry directions and approved unit speeds keep exact reach boundaries`() {
        for (direction in 0..3) for (profile in listOf(infantry, archer, cavalry)) {
            val budget = BattlePlans.MAX_ROUNDS * profile.movementSteps + profile.attackRange
            assertNull(SiegeRules.assaultApproachReadiness(layout(budget, direction), mapOf(7 to profile), 1))
            assertEquals(SiegeRules.AssaultBlock.ASSAULT_APPROACH_UNREACHABLE,
                SiegeRules.assaultApproachReadiness(layout(budget + 1, direction), mapOf(7 to profile), 1))
        }
    }

    @Test fun `exact wall count and sorted unit placement determine the closest reachable pair`() {
        val layout = layout(47)
        val placement = SiegeApproach.placement(layout, listOf(9, 7), 900)
        assertEquals(listOf(7, 9), placement.attackers.keys.toList())
        assertEquals(listOf(0, 1), placement.attackers.values.map { it!!.col })
        assertEquals(listOf(47, 46, 45, 44), placement.walls.map { it.col })
        assertTrue(SiegeApproach.isUnreachable(layout, mapOf(9 to infantry, 7 to infantry), 900))
        assertFalse(SiegeApproach.isUnreachable(layout, mapOf(9 to cavalry, 7 to infantry), 900),
            "one actually placed faster unit is sufficient to avoid rejection")
    }

    @Test fun `unplaced fast units cannot lend their budget to placed infantry`() {
        val layout = layout(100)
        val capacity = layout.attackerZone.size
        val profiles = (1..capacity).associateWith { infantry } + (999 to UnitProfile(1300, 100, 1, 110, 100, 30))
        assertNull(SiegeApproach.placement(layout, profiles.keys, 1).attackers[999])
        assertTrue(SiegeApproach.isUnreachable(layout, profiles, 1))
    }

    @Test fun `undefended county bypasses the contact bound`() {
        assertFalse(SiegeApproach.isUnreachable(layout(100), mapOf(7 to infantry), 0))
        assertNull(SiegeRules.assaultApproachReadiness(layout(100), mapOf(7 to infantry), 0))
    }

    @Test fun `large movement values use long arithmetic without overflow`() {
        assertFalse(SiegeApproach.isUnreachable(layout(44),
            mapOf(7 to UnitProfile(1100, Int.MAX_VALUE, 1, 100, 120, 20)), 1))
    }

    @Test fun `passing the lower bound does not claim a full terrain or LOS path is feasible`() {
        val cells = ((0..20).map { ProvinceCell(1, it, '1') } +
            (0..20).map { ProvinceCell(3, it, '1') } + ProvinceCell(2, 20, '1'))
            .sortedWith(compareBy(ProvinceCell::row, ProvinceCell::col))
        val source = ProvinceCellIndex("test", "a".repeat(64), "b".repeat(64), 4, 21,
            mapOf('1' to "PLAIN"), mapOf("approach" to listOf(ProvinceCell(0, 0, '1')), "battle" to cells))
        val layout = requireNotNull(SiegeRules.assaultLayout(source, "battle", "approach"))
        assertEquals(42, layout.distancesFromEntry.values.max())
        assertNull(SiegeRules.assaultApproachReadiness(layout, mapOf(7 to infantry), 1),
            "short Manhattan distance passes although the source detour is longer; acceptance is conservative")
        val result = SiegeAssault.resolve(layout,
            listOf(SiegeAssault.Attacker(7, 3000, 70, 60, 0, infantry)), 70, 1, 100, 0)
        assertEquals(1, result.garrisonRemaining)
    }

    @Test fun `placement extraction preserves the before diagnostic replay bytes`() {
        val attacker = SiegeAssault.Attacker(7, 5000, 50, 100, 0, infantry)
        val result = SiegeAssault.resolve(layout(44), listOf(attacker), 70, 1, 100, 0)
        assertEquals("f3f1a2181262edfa073b70cd2aac85d8d0f4ad49ce6cfb4984924310ec40ba02", result.replayHash)
        assertEquals(24, result.rounds)
        assertEquals(1, result.garrisonRemaining)
    }

    @Test fun `rejected source layouts never exchange in the unchanged resolver`() {
        for (distance in 1..75) for (direction in 0..3) for (profile in listOf(infantry, archer, cavalry)) {
            val layout = layout(distance, direction)
            val profiles = mapOf(7 to profile, 9 to infantry)
            if (!SiegeApproach.isUnreachable(layout, profiles, 6)) continue
            val army = profiles.map { (id, unitProfile) -> SiegeAssault.Attacker(id, 3000, 70, 60, 0, unitProfile) }
            val result = SiegeAssault.resolve(layout, army, 70, 6, 100, 0)
            assertEquals(6, result.garrisonRemaining)
            assertTrue(result.attackers.all { it.troops == 3000 && it.morale == 60 && it.fatigue == 0 })
        }
    }
}
