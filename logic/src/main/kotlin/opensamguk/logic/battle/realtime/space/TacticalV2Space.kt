package opensamguk.logic.battle.realtime.space

import opensamguk.logic.battle.realtime.TacticalV2Cell

@JvmInline
value class Fx(val raw: Long) : Comparable<Fx> {
    override fun compareTo(other: Fx) = raw.compareTo(other.raw)

    companion object {
        const val SHIFT = 16
        const val ONE = 1L shl SHIFT
    }
}

/** Cell coordinates use fixed-point row then col; the board spans [0, 64 * ONE). */
data class TacticalV2Pos(val row: Fx, val col: Fx)

/** Spatial queries are derived from battle state, not a second source of soldier positions. */
interface TacticalV2Space {
    fun passable(pos: TacticalV2Pos): Boolean
    fun setGateOpen(cell: TacticalV2Cell, open: Boolean)
    fun canOccupy(pos: TacticalV2Pos, ignore: Long?): Boolean
    /** Includes distance-squared equality and returns soldier IDs in ascending order. */
    fun neighbors(pos: TacticalV2Pos, range: Fx): List<Long>
    fun positionOf(id: Long): TacticalV2Pos?
    fun place(id: Long, pos: TacticalV2Pos)
    fun move(id: Long, to: TacticalV2Pos)
    fun remove(id: Long)
    /** Pure query; only move applies a position change, in ascending soldier ID order. */
    fun nextStep(id: Long, goal: TacticalV2Cell, speed: Fx): TacticalV2Pos
}
