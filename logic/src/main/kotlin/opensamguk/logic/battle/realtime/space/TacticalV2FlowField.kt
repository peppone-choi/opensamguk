package opensamguk.logic.battle.realtime.space

import java.util.PriorityQueue
import opensamguk.logic.battle.realtime.TacticalV2Cell

/** Derived terrain-only routing. Occupancy is checked by the grid when applying a step. */
internal class TacticalV2FlowField(
    private val passable: (Int) -> Boolean,
    private val cost: (Int) -> Long,
) {
    private data class Visit(val cell: Int, val distance: Long)
    private var cachedGoal = -1
    private var cachedDistances: LongArray? = null

    fun invalidate() {
        cachedGoal = -1
        cachedDistances = null
    }

    fun nextCell(start: Int, goal: TacticalV2Cell): Int {
        val requested = goal.row * SIZE + goal.col
        var distances = toward(requested)
        val target = if (distances[start] != Long.MAX_VALUE) requested else {
            val fromStart = distances(start, reverse = false)
            (0 until CELLS).asSequence().filter { fromStart[it] != Long.MAX_VALUE }
                .minWith(compareBy<Int> {
                    val row = it / SIZE - goal.row
                    val col = it % SIZE - goal.col
                    row.toLong() * row + col.toLong() * col
                }.thenBy { fromStart[it] }.thenBy { it / SIZE }.thenBy { it % SIZE })
        }
        if (start == target) return start
        if (target != requested) distances = toward(target)
        var best = start
        var bestCost = Long.MAX_VALUE
        for (next in adjacent(start)) {
            if (distances[next] == Long.MAX_VALUE) continue
            val candidate = cost(next) + distances[next]
            // Strict comparison preserves the fixed direction order on ties.
            if (candidate < bestCost) {
                bestCost = candidate
                best = next
            }
        }
        return best
    }

    private fun toward(goal: Int): LongArray {
        if (cachedGoal != goal) {
            cachedDistances = distances(goal, reverse = true)
            cachedGoal = goal
        }
        return checkNotNull(cachedDistances)
    }

    private fun distances(origin: Int, reverse: Boolean): LongArray {
        val result = LongArray(CELLS) { Long.MAX_VALUE }
        if (!passable(origin)) return result
        val queue = PriorityQueue(compareBy<Visit> { it.distance }.thenBy { it.cell })
        result[origin] = 0
        queue.add(Visit(origin, 0))
        while (queue.isNotEmpty()) {
            val visit = queue.remove()
            if (visit.distance != result[visit.cell]) continue
            for (next in adjacent(visit.cell)) {
                // Forward edges pay the destination's terrain, including in reverse search.
                val distance = visit.distance + cost(if (reverse) visit.cell else next)
                if (distance < result[next]) {
                    result[next] = distance
                    queue.add(Visit(next, distance))
                }
            }
        }
        return result
    }

    private fun adjacent(cell: Int): List<Int> {
        val row = cell / SIZE
        val col = cell % SIZE
        return DIRECTIONS.mapNotNull { (dr, dc) ->
            val nr = row + dr
            val nc = col + dc
            if (nr !in 0 until SIZE || nc !in 0 until SIZE || !passable(nr * SIZE + nc) ||
                (dr != 0 && dc != 0 && (!passable(nr * SIZE + col) || !passable(row * SIZE + nc)))) null
            else nr * SIZE + nc
        }
    }

    companion object {
        const val SIZE = 64
        const val CELLS = SIZE * SIZE
        // North, west, east, south, then diagonals in row-major order; never hash iteration order.
        private val DIRECTIONS = listOf(-1 to 0, 0 to -1, 0 to 1, 1 to 0,
            -1 to -1, -1 to 1, 1 to -1, 1 to 1)
    }
}
