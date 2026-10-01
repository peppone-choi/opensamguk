package opensamguk.logic.battle.realtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TacticalWallConnectivityTest {
    private val catalog by lazy { WaryongBoardCatalogResource.load() }

    @Test
    fun `v1 board three has a blocked gate bypass through confirmed wall top`() {
        val field = catalog.boards.single { it.battlefield.id == 3 }.battlefield
        val blocked = assertNotNull(route(field, 31 to 28, 31 to 30, 31 to 29, gateHp = 1))
        assertEquals(listOf(31 to 28, 30 to 28, 30 to 29, 30 to 30, 31 to 30), blocked)
        assertFalse((31 to 29) in blocked)

        // The provisional K2 classification of record (tileset 1, id 152) changes this one
        // wall-top cell. This is a local regression fixture, not a closed-fortress acceptance test.
        assertEquals('P', field.at(30, 29))
        val candidateRows = field.rows.toMutableList()
        candidateRows[30] = candidateRows[30].replaceRange(29, 30, "W")
        val candidate = Battlefield(field.id, field.kind, candidateRows)
        assertNull(route(candidate, 31 to 28, 31 to 30, 31 to 29, gateHp = 1))
        assertEquals(listOf(31 to 28, 31 to 29, 31 to 30),
            route(candidate, 31 to 28, 31 to 30, 31 to 29, gateHp = 0))
    }

    @Test
    fun `v1 tileset zero closed door remains passable with a blocked W gate`() {
        val board = catalog.boards.single { it.battlefield.id == 0 }
        assertEquals(0, board.tileset)
        assertEquals('P', board.battlefield.at(31, 36))
        assertTrue(terrainPassable(board.battlefield, 31, 36, 31, 38, 1))
        assertFalse(terrainPassable(board.battlefield, 31, 38, 31, 38, 1))
    }

    @Test
    fun `K2 closed region representatives expose only board three as a v1 bypass`() {
        // Historical v1 diagnostic. KI.EXE's record-0 rule superseded this 23-board
        // classification; it is not the v2 188-board acceptance fixture.
        // Pinned to k2-castle-regions-evidence.json SHA-256
        // 7caef3ee9bd99cd4712e9f6a7a50ea3811126e288dee7c6a6d3d913ce5aa604a.
        // K2's barrier selected these points; only the engine predicate below determines a route.
        val closed = listOf(
            ClosedRegion(3, 31 to 28, 32 to 32),
            ClosedRegion(27, 31 to 23, 32 to 31),
            ClosedRegion(28, 31 to 30, 32 to 40),
            ClosedRegion(29, 31 to 23, 32 to 31),
            ClosedRegion(33, 31 to 30, 32 to 40),
            ClosedRegion(49, 31 to 30, 32 to 40),
            ClosedRegion(55, 30 to 27, 32 to 31),
            ClosedRegion(56, 31 to 30, 32 to 41),
            ClosedRegion(57, 31 to 29, 32 to 40),
            ClosedRegion(62, 32 to 24, 32 to 30),
            ClosedRegion(73, 31 to 30, 10 to 44),
            ClosedRegion(79, 31 to 24, 32 to 30),
            ClosedRegion(81, 32 to 24, 32 to 30),
            ClosedRegion(88, 31 to 24, 32 to 30),
            ClosedRegion(95, 32 to 26, 32 to 31),
            ClosedRegion(98, 31 to 28, 32 to 32),
            ClosedRegion(102, 31 to 30, 32 to 40),
            ClosedRegion(117, 31 to 27, 32 to 31),
            ClosedRegion(118, 31 to 29, 32 to 32),
            ClosedRegion(120, 31 to 30, 32 to 40),
            ClosedRegion(137, 31 to 26, 32 to 31),
            ClosedRegion(153, 31 to 30, 32 to 41),
            ClosedRegion(169, 31 to 30, 32 to 42),
        )
        assertEquals(23, closed.size)
        val bypass = closed.filter { fixture ->
            val field = catalog.boards.single { it.battlefield.id == fixture.boardId }.battlefield
            assertTrue(terrainPassable(field, fixture.outside.first, fixture.outside.second,
                null, null, 1))
            assertTrue(terrainPassable(field, fixture.inside.first, fixture.inside.second,
                null, null, 1))
            route(field, fixture.outside, fixture.inside, 0 to 0, gateHp = 1) != null
        }.map { it.boardId }
        assertEquals(listOf(3), bypass)
    }

    private data class ClosedRegion(val boardId: Int, val outside: Pair<Int, Int>,
                                    val inside: Pair<Int, Int>)

    private fun route(field: Battlefield, start: Pair<Int, Int>, goal: Pair<Int, Int>,
                      gate: Pair<Int, Int>, gateHp: Int): List<Pair<Int, Int>>? {
        val queue = ArrayDeque<Pair<Int, Int>>()
        val previous = mutableMapOf<Pair<Int, Int>, Pair<Int, Int>?>()
        queue.add(start)
        previous[start] = null
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (current == goal) {
                val reversed = mutableListOf<Pair<Int, Int>>()
                var position: Pair<Int, Int>? = current
                while (position != null) {
                    reversed += position
                    position = previous[position]
                }
                return reversed.asReversed()
            }
            val (row, col) = current
            for (next in listOf((row - 1) to col, (row + 1) to col,
                row to (col - 1), row to (col + 1))) {
                if (next in previous || !terrainPassable(field, next.first, next.second,
                        gate.first, gate.second, gateHp)) continue
                previous[next] = current
                queue.add(next)
            }
        }
        return null
    }
}
