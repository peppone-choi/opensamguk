package opensamguk.logic.world

import kotlin.test.*

class BattlefieldGeometryTest {
    private fun index(hash: String = "a".repeat(64)) = ProvinceCellIndex(
        "qa", "b".repeat(64), hash, 10, 10,
        mapOf('1' to "PLAIN", '2' to "MOUNTAIN", '3' to "RIVER"),
        linkedMapOf(
            "battle" to listOf(ProvinceCell(3, 2, '1'), ProvinceCell(4, 2, '2'),
                ProvinceCell(3, 3, '3'), ProvinceCell(7, 6, '1')),
            "approach" to listOf(ProvinceCell(2, 2, '1'), ProvinceCell(2, 3, '1')),
            "diagonal" to listOf(ProvinceCell(2, 1, '1')),
            "empty" to emptyList(),
        ),
    )
    private fun position(col: Int, row: Int) = BattlefieldGeometry.Position(col, row)

    @Test fun `extraction preserves source terrain holes and disconnected cells`() {
        val index = index()
        val grid = BattlefieldGeometry.extract(index, "battle")
        assertEquals(3, grid.originCol)
        assertEquals(2, grid.originRow)
        assertEquals(5, grid.width)
        assertEquals(5, grid.height)
        assertEquals(index.cellsOf("battle"), grid.cells.map { it.source })
        assertEquals(listOf("PLAIN", "MOUNTAIN", "RIVER", "PLAIN"), grid.cells.map { it.terrain })
        assertEquals(position(4, 4), grid.cells.last().position)
        assertNull(grid.cellAt(position(1, 1)))
        assertNull(grid.cellAt(position(-1, 0)))
        assertEquals(index.topologyHash, grid.topologyHash)
        assertEquals(index.tilesContentHash, grid.tilesContentHash)
    }

    @Test fun `neighbors are orthogonal row major without bridging holes or components`() {
        val grid = BattlefieldGeometry.extract(index(), "battle")
        assertEquals(listOf(position(1, 0), position(0, 1)), grid.neighbors(position(0, 0)).map { it.position })
        assertEquals(listOf(position(0, 0)), grid.neighbors(position(1, 0)).map { it.position })
        assertEquals(emptyList(), grid.neighbors(position(4, 4)))
        assertEquals(emptyList(), grid.neighbors(position(1, 1)))
        assertEquals(emptyList(), grid.neighbors(position(Int.MAX_VALUE, Int.MAX_VALUE)))
    }

    @Test fun `physical approach border is deterministic and excludes diagonal contact`() {
        val index = index()
        val grid = BattlefieldGeometry.extract(index, "battle")
        assertEquals(listOf(position(0, 0), position(0, 1)), grid.borderFacing(index, "approach").map { it.position })
        assertEquals(emptyList(), grid.borderFacing(index, "diagonal"))
        assertEquals(emptyList(), grid.borderFacing(index, "empty"))
    }

    @Test fun `unknown empty self approach and stale source pins reject`() {
        val index = index()
        val grid = BattlefieldGeometry.extract(index, "battle")
        assertFailsWith<IllegalArgumentException> { BattlefieldGeometry.extract(index, "unknown") }
        assertFailsWith<IllegalArgumentException> { BattlefieldGeometry.extract(index, "empty") }
        assertFailsWith<IllegalArgumentException> { grid.borderFacing(index, "battle") }
        assertFailsWith<IllegalArgumentException> { grid.borderFacing(index, "unknown") }
        assertFailsWith<IllegalArgumentException> { grid.borderFacing(index("c".repeat(64)), "approach") }
    }

    @Test fun `extracted cells cannot be mutated through a cast`() {
        val grid = BattlefieldGeometry.extract(index(), "battle")
        assertFailsWith<UnsupportedOperationException> { (grid.cells as MutableList).clear() }
        assertEquals(4, grid.cells.size)
    }
}
