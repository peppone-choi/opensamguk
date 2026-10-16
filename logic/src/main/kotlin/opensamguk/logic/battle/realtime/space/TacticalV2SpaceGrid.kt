package opensamguk.logic.battle.realtime.space

import kotlin.math.abs
import opensamguk.logic.battle.realtime.Battlefield
import opensamguk.logic.battle.realtime.TacticalRules
import opensamguk.logic.battle.realtime.TacticalV2Cell

/** Rebuildable spatial index. The caller supplies the v1 gate cell and soldier movement ticks. */
class TacticalV2SpaceGrid(
    battlefield: Battlefield,
    private val params: TacticalV2SpaceParams,
    private val gate: TacticalV2Cell? = null,
    private val rules: TacticalRules = TacticalRules.CANON,
    private val moveTicks: Int = rules.infantryMoveTicks,
) : TacticalV2Space {
    private val terrain = battlefield.rows.map { it.toCharArray() }
    private val positions = mutableMapOf<Long, TacticalV2Pos>()
    private val buckets = Array(TacticalV2FlowField.CELLS) { mutableSetOf<Long>() }
    private var gateOpen = false
    private val flow = TacticalV2FlowField(::cellPassable, ::cost)

    init {
        require(params.cellCapacity > 0 && params.soldierRadius.raw > 0)
        require(moveTicks > 0 && rules.forestExtraTicks >= 0 && rules.riverExtraTicks >= 0)
        require(gate == null || terrain[gate.row][gate.col] == 'W')
    }

    override fun passable(pos: TacticalV2Pos): Boolean = inBounds(pos) && cellPassable(cell(pos))

    override fun setGateOpen(cell: TacticalV2Cell, open: Boolean) {
        require(cell == gate) { "Not the battlefield gate" }
        if (open != gateOpen) {
            gateOpen = open
            flow.invalidate()
        }
    }

    override fun canOccupy(pos: TacticalV2Pos, ignore: Long?): Boolean {
        if (!passable(pos)) return false
        if (buckets[cell(pos)].count { it != ignore } >= params.cellCapacity) return false
        // Board-local distances fit Long. Clamp only the search extent, not the spacing rule.
        val diameter = params.soldierRadius.raw.coerceAtMost(BOARD_LIMIT) * 2
        val threshold = diameter * diameter
        return candidates(pos, diameter).none { id ->
            id != ignore && distanceSquared(pos, positions.getValue(id)) < threshold
        }
    }

    override fun neighbors(pos: TacticalV2Pos, range: Fx): List<Long> {
        require(inBounds(pos) && range.raw >= 0)
        val extent = range.raw.coerceAtMost(BOARD_LIMIT * 2)
        val squared = extent * extent
        return candidates(pos, extent).filter { distanceSquared(pos, positions.getValue(it)) <= squared }.sorted()
    }

    override fun positionOf(id: Long): TacticalV2Pos? = positions[id]

    override fun place(id: Long, pos: TacticalV2Pos) {
        require(id > 0 && id !in positions) { "Duplicate or invalid soldier ID" }
        require(canOccupy(pos, null)) { "SPACE_CAPACITY_EXCEEDED" }
        positions[id] = pos
        buckets[cell(pos)].add(id)
    }

    override fun move(id: Long, to: TacticalV2Pos) {
        val from = requireNotNull(positions[id]) { "Unknown soldier ID" }
        require(canOccupy(to, id) && clearSegment(from, to)) { "Illegal soldier move" }
        buckets[cell(from)].remove(id)
        positions[id] = to
        buckets[cell(to)].add(id)
    }

    override fun remove(id: Long) {
        val old = positions.remove(id) ?: return
        buckets[cell(old)].remove(id)
    }

    override fun nextStep(id: Long, goal: TacticalV2Cell, speed: Fx): TacticalV2Pos {
        val from = requireNotNull(positions[id]) { "Unknown soldier ID" }
        require(speed.raw >= 0)
        if (speed.raw == 0L || !passable(from)) return from
        val currentCell = cell(from)
        val nextCell = flow.nextCell(currentCell, goal)
        // Prefer the next centre. If a restored off-centre position would cut a corner,
        // first centre inside its current cell. Do not recenter every partial step.
        val target = centre(nextCell)
        val waypoint = if (clearSegment(from, target)) target else centre(currentCell)
        if (from == waypoint) return from
        val step = minOf(speed.raw, Fx.ONE / cost(nextCell))
        if (step == 0L) return from
        val dr = waypoint.row.raw - from.row.raw
        val dc = waypoint.col.raw - from.col.raw
        val lengthSquared = dr * dr + dc * dc
        val length = ceilSqrt(lengthSquared)
        val to = if (step >= length) waypoint else TacticalV2Pos(
            Fx(from.row.raw + dr * step / length), Fx(from.col.raw + dc * step / length))
        return if (canOccupy(to, id) && clearSegment(from, to)) to else from
    }

    private fun cellPassable(cell: Int): Boolean = when (terrain[cell / 64][cell % 64]) {
        'P', 'F', 'R' -> true
        'W' -> gateOpen && gate != null && cell == gate.row * 64 + gate.col
        else -> false
    }

    private fun cost(cell: Int): Long = moveTicks.toLong() + when (terrain[cell / 64][cell % 64]) {
        'F' -> rules.forestExtraTicks
        'R' -> rules.riverExtraTicks
        else -> 0
    }

    private fun candidates(pos: TacticalV2Pos, extent: Long): Sequence<Long> = sequence {
        val minRow = ((pos.row.raw - extent).coerceAtLeast(0) shr Fx.SHIFT).toInt()
        val maxRow = ((pos.row.raw + extent).coerceAtMost(BOARD_LIMIT - 1) shr Fx.SHIFT).toInt()
        val minCol = ((pos.col.raw - extent).coerceAtLeast(0) shr Fx.SHIFT).toInt()
        val maxCol = ((pos.col.raw + extent).coerceAtMost(BOARD_LIMIT - 1) shr Fx.SHIFT).toInt()
        for (row in minRow..maxRow) for (col in minCol..maxCol) yieldAll(buckets[row * 64 + col])
    }

    /** Integer grid traversal checks every touched cell, including both sides of a corner. */
    private fun clearSegment(from: TacticalV2Pos, to: TacticalV2Pos): Boolean {
        if (!passable(from) || !passable(to)) return false
        var row = (from.row.raw shr Fx.SHIFT).toInt()
        var col = (from.col.raw shr Fx.SHIFT).toInt()
        val endRow = (to.row.raw shr Fx.SHIFT).toInt()
        val endCol = (to.col.raw shr Fx.SHIFT).toInt()
        val dr = to.row.raw - from.row.raw
        val dc = to.col.raw - from.col.raw
        val sr = dr.compareTo(0)
        val sc = dc.compareTo(0)
        var rowDistance = if (sr > 0) (row + 1) * Fx.ONE - from.row.raw else from.row.raw - row * Fx.ONE
        var colDistance = if (sc > 0) (col + 1) * Fx.ONE - from.col.raw else from.col.raw - col * Fx.ONE
        while (row != endRow || col != endCol) {
            val crossRow = if (row == endRow) 1 else if (col == endCol) -1
                else (rowDistance * abs(dc)).compareTo(colDistance * abs(dr))
            if (crossRow == 0 && (!cellPassable((row + sr) * 64 + col) ||
                    !cellPassable(row * 64 + col + sc))) return false
            if (crossRow <= 0) { row += sr; rowDistance += Fx.ONE }
            if (crossRow >= 0) { col += sc; colDistance += Fx.ONE }
            if (!cellPassable(row * 64 + col)) return false
        }
        return true
    }

    private fun ceilSqrt(value: Long): Long {
        var low = 0L
        var high = Fx.ONE * 2 // A waypoint is at most one diagonal cell away.
        while (low < high) {
            val mid = (low + high) / 2
            if (mid * mid >= value) high = mid else low = mid + 1
        }
        return low
    }

    private fun inBounds(pos: TacticalV2Pos) = pos.row.raw in 0 until BOARD_LIMIT && pos.col.raw in 0 until BOARD_LIMIT
    private fun cell(pos: TacticalV2Pos) = (pos.row.raw shr Fx.SHIFT).toInt() * 64 + (pos.col.raw shr Fx.SHIFT).toInt()
    private fun centre(cell: Int) = TacticalV2Pos(Fx(cell / 64 * Fx.ONE + Fx.ONE / 2), Fx(cell % 64 * Fx.ONE + Fx.ONE / 2))
    private fun distanceSquared(a: TacticalV2Pos, b: TacticalV2Pos): Long {
        val dr = a.row.raw - b.row.raw
        val dc = a.col.raw - b.col.raw
        return dr * dr + dc * dc
    }

    companion object { private const val BOARD_LIMIT = 64 * Fx.ONE }
}
