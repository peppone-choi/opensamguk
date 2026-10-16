package opensamguk.logic.battle.realtime.space

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import opensamguk.logic.battle.realtime.Battlefield
import opensamguk.logic.battle.realtime.TacticalRules
import opensamguk.logic.battle.realtime.TacticalV2Cell
import opensamguk.logic.battle.realtime.WaryongBoardCatalogResource

class TacticalV2SpaceGridTest {
    private val params = TacticalV2SpaceParams(4, Fx(Fx.ONE / 8), "fixture")
    private val plain = Battlefield(192, "FIELD", List(64) { "P".repeat(64) })
    private fun centre(row: Int, col: Int) = TacticalV2Pos(Fx(row * Fx.ONE + Fx.ONE / 2), Fx(col * Fx.ONE + Fx.ONE / 2))
    private fun grid(field: Battlefield = plain, gate: TacticalV2Cell? = null) = TacticalV2SpaceGrid(field, params, gate)
    private fun squared(a: TacticalV2Pos, b: TacticalV2Pos): Long {
        val dr = a.row.raw - b.row.raw
        val dc = a.col.raw - b.col.raw
        return dr * dr + dc * dc
    }

    @Test
    fun `all 214 boards match terrain table at every centre with closed and open gates`() {
        val boards = WaryongBoardCatalogResource.load().boards
        assertEquals(214, boards.size)
        for (board in boards) {
            val field = board.battlefield
            val gate = (0 until 4096).firstOrNull { field.at(it / 64, it % 64) == 'W' }
                ?.let { TacticalV2Cell(it / 64, it % 64) }
            val space = grid(field, gate)
            for (open in listOf(false, true)) {
                if (gate != null) space.setGateOpen(gate, open)
                for (row in 0..63) for (col in 0..63) {
                    val expected = when (field.at(row, col)) {
                        'P', 'F', 'R' -> true
                        'W' -> open && gate == TacticalV2Cell(row, col)
                        else -> false
                    }
                    assertEquals(expected, space.passable(centre(row, col)), "${field.id}: $row,$col open=$open")
                }
            }
        }
    }

    @Test
    fun `capacity spacing boundary and board bounds are enforced without mutation on rejection`() {
        val space = TacticalV2SpaceGrid(plain, params.copy(cellCapacity = 1))
        space.place(1, centre(2, 2))
        val another = TacticalV2Pos(Fx(2 * Fx.ONE + Fx.ONE / 8), centre(2, 2).col)
        assertFalse(space.canOccupy(another, null))
        assertFailsWith<IllegalArgumentException> { space.place(2, another) }
        assertEquals(null, space.positionOf(2))
        assertTrue(space.canOccupy(centre(2, 2), 1))
        assertFailsWith<IllegalArgumentException> { space.place(1, centre(3, 3)) }
        for (pos in listOf(TacticalV2Pos(Fx(-1), Fx(0)), TacticalV2Pos(Fx(0), Fx(-1)),
                TacticalV2Pos(Fx(64 * Fx.ONE), Fx(0)), TacticalV2Pos(Fx(0), Fx(Long.MAX_VALUE)))) {
            assertFalse(space.passable(pos))
            assertFalse(space.canOccupy(pos, null))
            assertFailsWith<IllegalArgumentException> { space.place(9, pos) }
        }
        val spacing = grid()
        spacing.place(1, centre(2, 2))
        val touching = centre(2, 2).copy(col = Fx(centre(2, 2).col.raw + Fx.ONE / 4))
        assertTrue(spacing.canOccupy(touching, null))
        assertFalse(spacing.canOccupy(touching.copy(col = Fx(touching.col.raw - 1)), null))
        spacing.place(2, touching)
        assertEquals(listOf(1L, 2L), spacing.neighbors(centre(2, 2), Fx(Fx.ONE / 4)))
        assertEquals(listOf(1L), spacing.neighbors(centre(2, 2), Fx(Fx.ONE / 4 - 1)))
    }

    @Test
    fun `seeded fixed point neighbors and occupancy equal exhaustive reference including insertion permutations`() {
        val random = Random(1759)
        val fixture = (1L..256L).map { id ->
            id to TacticalV2Pos(Fx(random.nextLong(64 * Fx.ONE)), Fx(random.nextLong(64 * Fx.ONE)))
        }
        // Keep only mutually separated fixture positions; values are test data, not product decisions.
        val accepted = mutableListOf<Pair<Long, TacticalV2Pos>>()
        fixture.forEach { candidate ->
            if (accepted.none { squared(it.second, candidate.second) < (Fx.ONE / 4) * (Fx.ONE / 4) }) accepted.add(candidate)
        }
        val a = grid()
        val b = grid()
        accepted.forEach { (id, pos) -> a.place(id, pos) }
        accepted.shuffled(Random(32)).forEach { (id, pos) -> b.place(id, pos) }
        repeat(100) {
            val pos = TacticalV2Pos(Fx(random.nextLong(64 * Fx.ONE)), Fx(random.nextLong(64 * Fx.ONE)))
            val range = random.nextLong(10 * Fx.ONE)
            val expected = accepted.filter { squared(pos, it.second) <= range * range }.map { it.first }.sorted()
            assertEquals(expected, a.neighbors(pos, Fx(range)))
            assertEquals(expected, b.neighbors(pos, Fx(range)))
            val count = accepted.count { (it.second.row.raw shr Fx.SHIFT) == (pos.row.raw shr Fx.SHIFT) &&
                (it.second.col.raw shr Fx.SHIFT) == (pos.col.raw shr Fx.SHIFT) }
            val canOccupy = count < params.cellCapacity && accepted.none { squared(pos, it.second) < (Fx.ONE / 4) * (Fx.ONE / 4) }
            assertEquals(canOccupy, a.canOccupy(pos, null))
            assertEquals(canOccupy, b.canOccupy(pos, null))
        }
        for ((id, pos) in accepted) {
            assertEquals(pos, b.positionOf(id))
            assertEquals(a.nextStep(id, TacticalV2Cell(32, 32), Fx(5000)), b.nextStep(id, TacticalV2Cell(32, 32), Fx(5000)))
        }
        assertEquals(accepted.map { it.first }.sorted(), a.neighbors(centre(32, 32), Fx(Long.MAX_VALUE)))
        assertFailsWith<IllegalArgumentException> { a.neighbors(centre(1, 1), Fx(-1)) }
        val hugeRadius = TacticalV2SpaceGrid(plain, params.copy(soldierRadius = Fx(Long.MAX_VALUE)))
        hugeRadius.place(1, centre(0, 0))
        assertFalse(hugeRadius.canOccupy(centre(63, 63), null))
    }

    @Test
    fun `partial steps progress deterministically and queries never mutate the index`() {
        val space = grid()
        space.place(1, centre(5, 5))
        val goal = TacticalV2Cell(5, 8)
        val speed = Fx(Fx.ONE / TacticalRules.CANON.infantryMoveTicks)
        repeat(100) {
            val before = space.positionOf(1)!!
            val step = space.nextStep(1, goal, speed)
            assertEquals(step, space.nextStep(1, goal, speed))
            assertEquals(before, space.positionOf(1))
            assertTrue(space.passable(step))
            assertTrue(squared(before, step) <= speed.raw * speed.raw)
            space.move(1, step)
        }
        assertEquals(centre(5, 8), space.positionOf(1))
        assertEquals(centre(5, 8), space.nextStep(1, goal, Fx(0)))
        assertFailsWith<IllegalArgumentException> { space.nextStep(1, goal, Fx(-1)) }
        space.move(1, centre(4, 4))
        assertEquals(emptyList(), space.neighbors(centre(5, 8), Fx(0)))
        space.remove(1)
        space.remove(1)
        assertEquals(null, space.positionOf(1))
        assertEquals(emptyList(), space.neighbors(centre(4, 4), Fx(0)))
    }

    @Test
    fun `move and nextStep cannot tunnel through walls or cut diagonal corners`() {
        val field = Battlefield(192, "FIELD", List(64) { row ->
            CharArray(64) { col -> if ((row == 0 && col == 1) || (row == 1 && col == 0)) 'M' else 'P' }.concatToString()
        })
        val space = grid(field)
        space.place(1, centre(0, 0))
        assertEquals(centre(0, 0), space.nextStep(1, TacticalV2Cell(2, 2), Fx(Long.MAX_VALUE)))
        assertFailsWith<IllegalArgumentException> { space.move(1, centre(1, 1)) }
        assertFailsWith<IllegalArgumentException> { space.move(1, centre(0, 2)) }
        assertEquals(centre(0, 0), space.positionOf(1))
    }

    @Test
    fun `gate toggle invalidates routes and accepts only the supplied v1 gate`() {
        val field = Battlefield(0, "FORTRESS", List(64) { "PPP" + "W" + "P".repeat(60) })
        val gate = TacticalV2Cell(5, 3)
        val space = grid(field, gate)
        space.place(1, centre(5, 2))
        val goal = TacticalV2Cell(5, 5)
        assertEquals(centre(5, 2), space.nextStep(1, goal, Fx(Fx.ONE)))
        space.setGateOpen(gate, true)
        assertTrue(space.nextStep(1, goal, Fx(Fx.ONE)).col > centre(5, 2).col)
        space.setGateOpen(gate, false)
        assertEquals(centre(5, 2), space.nextStep(1, goal, Fx(Fx.ONE)))
        assertFailsWith<IllegalArgumentException> { space.setGateOpen(TacticalV2Cell(6, 3), true) }
        assertFailsWith<IllegalArgumentException> { grid(plain, TacticalV2Cell(5, 3)) }
    }

    @Test
    fun `occupied destination prevents a step and restored off centre positions do not oscillate`() {
        val space = TacticalV2SpaceGrid(plain, params.copy(cellCapacity = 1))
        space.place(1, TacticalV2Pos(Fx(5 * Fx.ONE + Fx.ONE - 1), centre(5, 5).col))
        space.place(2, centre(6, 5))
        val before = space.positionOf(1)!!
        assertEquals(before, space.nextStep(1, TacticalV2Cell(7, 5), Fx(Fx.ONE)))
        space.remove(2)
        repeat(100) { space.move(1, space.nextStep(1, TacticalV2Cell(7, 5), Fx(10000))) }
        assertEquals(centre(7, 5), space.positionOf(1))
    }
}
