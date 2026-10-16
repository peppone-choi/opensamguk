package opensamguk.logic.battle.realtime.soldier

import opensamguk.logic.battle.realtime.*
import opensamguk.logic.battle.realtime.space.*

/** Caller must bind these facts to the committed source; no crew-type mapping or general is invented. */
data class TacticalV2SoldierSourceFacts(
    val source: TacticalV2UnitSource,
    val crewTypeId: Int,
    val kind: UnitKind,
    val general: GeneralStats?,
    val training: Int,
    val morale: Int,
    val fatigue: Int,
    val provisions: Int,
) {
    init {
        require(crewTypeId > 0 && training in 0..100 && morale in 0..100 && fatigue in 0..100 && provisions >= 0)
        require(general == null || general.id == source.commanderGeneralId)
        require(source.stationary || general != null)
    }
}

enum class TacticalV2SoldierStatus { ACTIVE, INCAPACITATED }

data class TacticalV2Soldier(
    val id: Long,
    val sourceKey: TacticalV2SourceKey,
    val pos: TacticalV2Pos,
    val hp: Int,
    val morale: Int,
    val status: TacticalV2SoldierStatus = TacticalV2SoldierStatus.ACTIVE,
    val goal: TacticalV2Cell? = null,
    val targetId: Long? = null,
    val attackWait: Int = 0,
) {
    init {
        require(id > 0 && hp >= 0 && morale >= 0 && attackWait >= 0)
        require((hp > 0) == (status == TacticalV2SoldierStatus.ACTIVE))
        require(pos.row.raw in 0 until 64 * Fx.ONE && pos.col.raw in 0 until 64 * Fx.ONE)
        require(targetId == null || targetId > 0)
        require(goal == null || targetId == null)
    }
    val alive: Boolean get() = status == TacticalV2SoldierStatus.ACTIVE
}

/** Commands contain intent, never authoritative positions or combat results. */
sealed interface TacticalV2SoldierOrder {
    data class Move(val cell: TacticalV2Cell) : TacticalV2SoldierOrder
    data class Attack(val soldierId: Long) : TacticalV2SoldierOrder
    data object Stop : TacticalV2SoldierOrder
    data object Retreat : TacticalV2SoldierOrder
    data object Surrender : TacticalV2SoldierOrder
}

data class TacticalV2SoldierCommand(val soldierIds: List<Long>, val order: TacticalV2SoldierOrder)

enum class TacticalV2SoldierRunStatus { RUNNING, BUDGET_EXHAUSTED }

data class TacticalV2SoldierDecision(val state: TacticalV2BattleState, val accepted: Boolean,
                                     val reason: String? = null)

internal fun TacticalV2Pos.cell() = TacticalV2Cell((row.raw shr Fx.SHIFT).toInt(), (col.raw shr Fx.SHIFT).toInt())
internal fun cellCenter(cell: TacticalV2Cell) = TacticalV2Pos(
    Fx(cell.row * Fx.ONE + Fx.ONE / 2), Fx(cell.col * Fx.ONE + Fx.ONE / 2))
internal fun distanceSquared(a: TacticalV2Pos, b: TacticalV2Pos): Long {
    val dr = a.row.raw - b.row.raw
    val dc = a.col.raw - b.col.raw
    return dr * dr + dc * dc
}
