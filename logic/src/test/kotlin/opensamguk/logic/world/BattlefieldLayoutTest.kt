package opensamguk.logic.world

import kotlin.test.*
import opensamguk.logic.world.BattlefieldGeometry.Position

class BattlefieldLayoutTest {
    private fun source(battle: List<ProvinceCell>, approach: List<ProvinceCell>) = ProvinceCellIndex(
        "qa", "a".repeat(64), "b".repeat(64), 20, 20,
        mapOf('0' to "SEA", '1' to "PLAIN", '2' to "MOUNTAIN", '3' to "RIVER", '4' to "LAKE"),
        mapOf("battle" to battle.sortedWith(compareBy(ProvinceCell::row, ProvinceCell::col)),
            "approach" to approach.sortedWith(compareBy(ProvinceCell::row, ProvinceCell::col))))
    private fun cell(col: Int, row: Int = 1, terrain: Char = '1') = ProvinceCell(col, row, terrain)
    private fun prepare(battle: List<ProvinceCell>, approach: List<ProvinceCell> = listOf(cell(0))) =
        BattlefieldLayout.prepare(source(battle, approach), "battle", "approach")
    private fun ready(result: BattlefieldLayout.Result) = assertIs<BattlefieldLayout.Result.Ready>(result).layout
    private fun reason(result: BattlefieldLayout.Result) = assertIs<BattlefieldLayout.Result.Unavailable>(result).reason

    @Test fun `corridor outer thirds leave a separating band and preserve source pins`() {
        val grid = ready(prepare((1..7).map { cell(it) }))
        assertEquals(listOf(Position(0, 0), Position(1, 0)), grid.attackerZone)
        assertEquals(listOf(Position(5, 0), Position(6, 0)), grid.defenderZone)
        assertEquals((0..6).toList(), grid.distancesFromEntry.values.toList())
        assertEquals("b".repeat(64), grid.geometry.tilesContentHash)
        assertEquals("approach", grid.approachProvinceId)
    }

    @Test fun `both sides of entry must be passable and diagonal contact is insufficient`() {
        assertEquals(BattlefieldLayout.Reason.NO_PASSABLE_CONTACT,
            reason(prepare(listOf(cell(1), cell(2)), listOf(cell(0, terrain='2')))))
        assertEquals(BattlefieldLayout.Reason.NO_PASSABLE_CONTACT,
            reason(prepare(listOf(cell(1, terrain='2'), cell(2)))))
        assertEquals(BattlefieldLayout.Reason.NO_PHYSICAL_CONTACT,
            reason(prepare(listOf(cell(1), cell(2)), listOf(cell(0, row=0)))))
    }

    @Test fun `river is traversable while mountains and water never connect regions`() {
        val grid = ready(prepare(listOf(cell(1), cell(2, terrain='3'), cell(3), cell(4, terrain='2'),
            cell(5), cell(6, terrain='0'), cell(7), cell(8, terrain='4'))))
        assertEquals(setOf(Position(0, 0), Position(1, 0), Position(2, 0)), grid.distancesFromEntry.keys)
        assertEquals(8, grid.geometry.cells.size)
        assertEquals("RIVER", grid.geometry.cellAt(Position(1, 0))?.terrain)
    }

    @Test fun `largest accessible component wins with row major ties and no imaginary bridge`() {
        val battle = listOf(cell(1, 1), cell(2, 1), cell(1, 3), cell(2, 3), cell(3, 3),
            cell(10, 10), cell(11, 10), cell(12, 10), cell(13, 10))
        val approach = listOf(cell(0, 1), cell(0, 3))
        val grid = ready(prepare(battle, approach))
        assertEquals(setOf(Position(0, 2), Position(1, 2), Position(2, 2)), grid.distancesFromEntry.keys)
        val tied = ready(prepare(battle.filterNot { it == cell(3, 3) }, approach))
        assertEquals(setOf(Position(0, 0), Position(1, 0)), tied.distancesFromEntry.keys)
        val reordered = ready(prepare(battle.reversed(), approach.reversed()))
        assertEquals(grid.distancesFromEntry, reordered.distancesFromEntry)
        assertEquals(grid.attackerZone, reordered.attackerZone)
        assertEquals(grid.defenderZone, reordered.defenderZone)
    }

    @Test fun `geodesic distance follows detour around mountain not straight line`() {
        val battle = listOf(cell(1,1), cell(2,1,'2'), cell(3,1), cell(1,2), cell(2,2), cell(3,2))
        val grid = ready(prepare(battle))
        assertEquals(4, grid.distancesFromEntry[Position(2,0)])
        assertNull(grid.distancesFromEntry[Position(1,0)])
    }

    @Test fun `empty blocked single cell and one depth cases stay distinct`() {
        assertEquals(BattlefieldLayout.Reason.EMPTY_PROVINCE, reason(prepare(emptyList())))
        assertEquals(BattlefieldLayout.Reason.NO_PASSABLE_CELLS, reason(prepare(listOf(cell(1, terrain='2')))))
        assertEquals(BattlefieldLayout.Reason.INSUFFICIENT_DEPTH, reason(prepare(listOf(cell(1)))))
        val small = ready(prepare(listOf(cell(1), cell(2))))
        assertEquals(listOf(Position(0,0)), small.attackerZone)
        assertEquals(listOf(Position(1,0)), small.defenderZone)
    }

    @Test fun `unknown terrain rejects and all known land categories are explicit`() {
        for (terrain in listOf("PLAIN", "RIVER", "DESERT", "PLATEAU", "BASIN", "HILL"))
            assertTrue(BattlefieldLayout.isLandPassable(terrain))
        for (terrain in listOf("SEA", "LAKE", "MOUNTAIN", "OUT_OF_SCOPE"))
            assertFalse(BattlefieldLayout.isLandPassable(terrain))
        assertFailsWith<IllegalArgumentException> { BattlefieldLayout.isLandPassable("UNKNOWN") }
    }

    @Test fun `layout collections are immutable and invalid province inputs reject`() {
        val grid = ready(prepare(listOf(cell(1), cell(2))))
        assertFailsWith<UnsupportedOperationException> { (grid.attackerZone as MutableList).clear() }
        assertFailsWith<UnsupportedOperationException> { (grid.defenderZone as MutableList).clear() }
        assertFailsWith<UnsupportedOperationException> { (grid.distancesFromEntry as MutableMap).clear() }
        val index = source(listOf(cell(1)), listOf(cell(0)))
        assertFailsWith<IllegalArgumentException> { BattlefieldLayout.prepare(index, "battle", "battle") }
        assertFailsWith<IllegalArgumentException> { BattlefieldLayout.prepare(index, "battle", "missing") }
    }
}
