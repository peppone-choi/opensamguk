package opensamguk.logic.battle.realtime.space

import kotlin.test.Test
import kotlin.test.assertEquals
import opensamguk.logic.battle.realtime.TacticalRules
import opensamguk.logic.battle.realtime.TacticalV2Cell

class TacticalV2FlowFieldTest {
    private fun index(row: Int, col: Int) = row * 64 + col
    private fun flow(cells: Map<Int, Char>, base: Int = TacticalRules.CANON.infantryMoveTicks,
        forest: Int = TacticalRules.CANON.forestExtraTicks, river: Int = TacticalRules.CANON.riverExtraTicks) =
        TacticalV2FlowField({ it in cells }) { base.toLong() + when (cells[it]) {
            'F' -> forest
            'R' -> river
            else -> 0
        } }
    private fun destination(flow: TacticalV2FlowField, start: Int, goal: TacticalV2Cell): Int {
        var at = start
        repeat(4096) {
            val next = flow.nextCell(at, goal)
            if (next == at) return at
            at = next
        }
        error("Fixture route did not converge")
    }

    @Test
    fun `unreachable mountain goal chooses nearest target cell not cheapest current cell`() {
        val cells = (1..3).associate { index(5, it) to 'P' }
        assertEquals(index(5, 2), flow(cells).nextCell(index(5, 1), TacticalV2Cell(5, 5)))
        assertEquals(index(5, 3), destination(flow(cells), index(5, 1), TacticalV2Cell(5, 5)))
    }

    @Test
    fun `fallback equal target distance uses path cost then row and column`() {
        val start = index(4, 4)
        val cells = mapOf(start to 'P', index(4, 5) to 'F', index(5, 4) to 'P')
        assertEquals(index(5, 4), destination(flow(cells, forest = 100), start, TacticalV2Cell(5, 5)))
        assertEquals(index(4, 5), destination(flow(cells, forest = 0), start, TacticalV2Cell(5, 5)))
        val columnTie = mapOf(index(2, 5) to 'P', index(2, 4) to 'P', index(2, 6) to 'P', index(3, 4) to 'P', index(3, 6) to 'P',
            index(4, 4) to 'P', index(4, 6) to 'P')
        assertEquals(index(4, 4), destination(flow(columnTie), index(2, 5), TacticalV2Cell(4, 5)))
    }

    @Test
    fun `terrain entry cost favors cheap detour and direction ties are stable`() {
        val cells = (9..11).flatMap { row -> (10..12).map { col -> index(row, col) to 'P' } }.toMap() +
            (index(10, 11) to 'R')
        val weighted = flow(cells, river = 100)
        assertEquals(index(9, 11), weighted.nextCell(index(10, 10), TacticalV2Cell(10, 12)))
        val flat = flow(cells, river = 0)
        // East and the two diagonals cost the same; fixed cardinal-first order selects east.
        assertEquals(index(10, 11), flat.nextCell(index(10, 10), TacticalV2Cell(10, 12)))
        repeat(5) { assertEquals(index(9, 11), weighted.nextCell(index(10, 10), TacticalV2Cell(10, 12))) }
    }

    @Test
    fun `diagonal movement requires both side cells and cannot join disconnected corners`() {
        val cells = mapOf(index(0, 0) to 'P', index(1, 1) to 'P')
        assertEquals(index(0, 0), flow(cells).nextCell(index(0, 0), TacticalV2Cell(1, 1)))
    }
}
