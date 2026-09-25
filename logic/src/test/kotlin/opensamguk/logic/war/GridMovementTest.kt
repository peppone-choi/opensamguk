package opensamguk.logic.war

import kotlin.test.*
import opensamguk.logic.world.*
import opensamguk.logic.world.BattlefieldGeometry.Position
import opensamguk.logic.war.GridMovement.UnitPosition
import opensamguk.logic.war.GridMovement.Intent
import opensamguk.logic.war.GridMovement.Outcome

class GridMovementTest {
    private val layout = (BattlefieldLayout.prepare(HanProvinceCellIndex(
        "qa", "a".repeat(64), "b".repeat(64), 10, 5,
        mapOf('1' to "PLAIN", '2' to "MOUNTAIN", '3' to "RIVER"),
        mapOf("a" to listOf(HanProvinceCell(0, 1, '1')),
            "b" to listOf(HanProvinceCell(1, 1, '1'), HanProvinceCell(2, 1, '3'),
                HanProvinceCell(3, 1, '1'), HanProvinceCell(4, 1, '2'), HanProvinceCell(6, 1, '1'),
                HanProvinceCell(1, 2, '1'), HanProvinceCell(2, 2, '1'), HanProvinceCell(3, 2, '1')))),
        "b", "a") as BattlefieldLayout.Result.Ready).layout
    private fun unit(id: Int, col: Int, row: Int = 0, initiative: Int = 1) = UnitPosition(id, Position(col, row), initiative)
    private fun move(id: Int, col: Int, row: Int = 0) = Intent(id, Position(col, row))
    private fun resolve(units: List<UnitPosition>, vararg intents: Intent) = GridMovement.resolve(layout, units, intents.toList())

    @Test fun `orthogonal river movement and unchanged holds preserve all units`() {
        val result = resolve(listOf(unit(2, 2), unit(1, 0)), move(1, 1))
        assertEquals(listOf(1, 2), result.map { it.bugokId })
        assertEquals(Outcome.MOVED, result[0].outcome)
        assertEquals(Position(1, 0), result[0].to)
        assertEquals(Outcome.HELD, result[1].outcome)
        assertFailsWith<UnsupportedOperationException> { (result as MutableList<*>).clear() }
    }
    @Test fun `simultaneous swaps and following into a vacated cell cannot overlap`() {
        val result = resolve(listOf(unit(1, 0), unit(2, 1)), move(1, 1), move(2, 0))
        assertTrue(result.all { it.outcome == Outcome.OCCUPIED && it.from == it.to })
        val following = resolve(listOf(unit(1, 0), unit(2, 1)), move(1, 1), move(2, 2))
        assertEquals(listOf(Outcome.OCCUPIED, Outcome.MOVED), following.map { it.outcome })
    }
    @Test fun `contested empty cell uses initiative then identity independently of input ordering`() {
        val units = listOf(unit(1, 0), unit(2, 2, initiative=2))
        val intents = listOf(move(1, 1), move(2, 1))
        val expected = GridMovement.resolve(layout, units, intents)
        assertEquals(listOf(Outcome.CONTESTED, Outcome.MOVED), expected.map { it.outcome })
        assertEquals(expected, GridMovement.resolve(layout, units.reversed(), intents.reversed()))
        val tied = resolve(units.map { it.copy(initiative=1) }, *intents.toTypedArray())
        assertEquals(listOf(Outcome.MOVED, Outcome.CONTESTED), tied.map { it.outcome })
        assertEquals(2, tied.map { it.to }.distinct().size)
    }
    @Test fun `holes mountains and unselected components cannot become tactical destinations`() {
        for (target in listOf(3, 4, 5, -1, Int.MAX_VALUE)) {
            val result = resolve(listOf(unit(1, 2)), move(1, target)).single()
            assertEquals(Outcome.OUTSIDE_COMBAT_AREA, result.outcome)
            assertEquals(result.from, result.to)
        }
        assertEquals(Outcome.NOT_ADJACENT, resolve(listOf(unit(1, 0)), move(1, 1, 1)).single().outcome)
        assertEquals(Outcome.NOT_ADJACENT, resolve(listOf(unit(1, 0)), move(1, 2)).single().outcome)
    }
    @Test fun `reserve cannot enter through ordinary movement and no-op stays held`() {
        assertEquals(Outcome.RESERVE, resolve(listOf(UnitPosition(1, null, 1)), move(1, 0)).single().outcome)
        val idleReserve = resolve(listOf(UnitPosition(1, null, 1))).single()
        assertEquals(Outcome.HELD, idleReserve.outcome)
        assertNull(idleReserve.to)
        assertEquals(Outcome.HELD, resolve(listOf(unit(1, 0)), move(1, 0)).single().outcome)
    }
    @Test fun `invalid state and ambiguous or unknown intents reject entire batch`() {
        for (units in listOf(emptyList(), listOf(unit(1, 0), unit(1, 1)),
            listOf(unit(1, 0), unit(2, 0)), listOf(unit(1, 3)))) {
            assertFailsWith<IllegalArgumentException> { resolve(units) }
        }
        assertFailsWith<IllegalArgumentException> { resolve(listOf(unit(1, 0)), move(2, 1)) }
        assertFailsWith<IllegalArgumentException> { resolve(listOf(unit(1, 0)), move(1, 1), move(1, 1)) }
        assertFailsWith<IllegalArgumentException> { unit(0, 0) }
        assertFailsWith<IllegalArgumentException> { unit(1, 0, initiative=-1) }
    }
}
