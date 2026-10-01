package opensamguk.logic.battle.realtime

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TacticalGroundTraversalV2Test {
    @Test
    fun `a breached door opens its whole selected record run and leaves other runs closed`() {
        val height = ByteArray(4096) { 0x80.toByte() }
        val links = ByteArray(4096)
        val solid = ByteArray(4096)
        for (row in 31..33) for (col in 28..32) {
            val cell = row * 64 + col
            height[cell] = 0
            links[cell] = ((if (col > 28) 0x10 else 0) or
                (if (col < 32) 0x20 else 0)).toByte()
        }
        val opened = mutableListOf(false, false, false)
        val first = TacticalDoorRun(30, 31, listOf(208, 209, 210), opened)
        val second = TacticalDoorRun(40, 31, listOf(208, 209, 210), listOf(false, false, false))
        for (row in 31..33) {
            height[row * 64 + 40] = 1
        }
        val runs = mutableListOf(first, second)
        val topology = TacticalGroundTraversalV2(height, links, solid, runs)
        height[32 * 64 + 30] = 0x80.toByte() // caller cannot mutate a pinned topology
        opened[1] = true
        runs.clear()
        assertNull(route(topology, 32 to 28, 32 to 32, selectedDoor = 0, breached = false))
        val breachedPath = assertNotNull(route(topology, 32 to 28, 32 to 32,
            selectedDoor = 0, breached = true))
        assertTrue(breachedPath.any { (row, col) -> col == 30 && row in 31..33 })
        assertFalse(topology.canStep(32, 29, 32, 30, selectedDoor = 0, breached = false))
        for (row in 31..33) {
            assertTrue(topology.passable(row, 30, selectedDoor = 0, breached = true))
            assertFalse(topology.passable(row, 40, selectedDoor = 0, breached = true))
        }
        assertTrue(topology.canStep(32, 29, 32, 30, selectedDoor = 0, breached = true))
        assertTrue(topology.canStep(32, 30, 32, 31, selectedDoor = 0, breached = true))
        assertFalse(topology.passable(0, 0, selectedDoor = 0, breached = true))
    }

    @Test
    fun `invalid height link or overlapping door records are rejected`() {
        val height = ByteArray(4096) { 0x80.toByte() }
        val links = ByteArray(4096)
        val solid = ByteArray(4096)
        height[32 * 64 + 29] = 0
        height[32 * 64 + 30] = 3
        links[32 * 64 + 29] = 0x20.toByte()
        assertFailsWith<IllegalArgumentException> {
            TacticalGroundTraversalV2(height, links, solid, emptyList())
        }
        links[32 * 64 + 29] = 0.toByte()
        val run = TacticalDoorRun(30, 32, listOf(208), listOf(false))
        assertFailsWith<IllegalArgumentException> {
            TacticalGroundTraversalV2(height, links, solid, listOf(run, run))
        }
    }

    private fun route(topology: TacticalGroundTraversalV2, start: Pair<Int, Int>,
                      goal: Pair<Int, Int>, selectedDoor: Int?, breached: Boolean): List<Pair<Int, Int>>? {
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
                if (next in previous || !topology.canStep(row, col, next.first, next.second,
                        selectedDoor, breached)) continue
                previous[next] = current
                queue.add(next)
            }
        }
        return null
    }
}
