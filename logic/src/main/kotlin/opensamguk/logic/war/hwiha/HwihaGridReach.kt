package opensamguk.logic.war.hwiha

import opensamguk.logic.world.HwihaBattlefieldGeometry.Position
import opensamguk.logic.world.HwihaBattlefieldLayout

/** Center-to-center supercover visibility; units themselves do not obstruct this geometric query. */
object HwihaGridReach {
    const val RULE_VERSION = 1
    fun canStrike(layout: HwihaBattlefieldLayout, from: Position, to: Position, range: Int): Boolean {
        require(range > 0)
        fun allowed(p: Position): Boolean = p in layout.distancesFromEntry &&
            layout.geometry.cellAt(p)?.let { HwihaBattlefieldLayout.isLandPassable(it.terrain) } == true
        if (from == to || !allowed(from) || !allowed(to)) return false
        // Coordinates are validated against the source geometry before subtraction/multiplication.
        val dx = kotlin.math.abs(to.col.toLong() - from.col)
        val dy = kotlin.math.abs(to.row.toLong() - from.row)
        if (dx + dy > range.toLong()) return false
        val sx = to.col.compareTo(from.col)
        val sy = to.row.compareTo(from.row)
        var x = from.col
        var y = from.row
        var ix = 0L
        var iy = 0L
        while (ix < dx || iy < dy) {
            val vertical = (2 * ix + 1) * dy
            val horizontal = (2 * iy + 1) * dx
            when {
                vertical == horizontal -> {
                    // A corner touches both adjacent cells as well as the diagonal cell.
                    if (!allowed(Position(x + sx, y)) || !allowed(Position(x, y + sy))) return false
                    x += sx; y += sy; ix++; iy++
                }
                vertical < horizontal -> { x += sx; ix++ }
                else -> { y += sy; iy++ }
            }
            if (!allowed(Position(x,y))) return false
        }
        return true
    }
}
