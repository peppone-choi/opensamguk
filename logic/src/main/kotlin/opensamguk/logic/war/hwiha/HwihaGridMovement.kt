package opensamguk.logic.war.hwiha

import java.util.Collections
import opensamguk.logic.world.HwihaBattlefieldGeometry.Position
import opensamguk.logic.world.HwihaBattlefieldLayout

/** One simultaneous orthogonal step. The caller supplies sealed initiative and movement intents. */
object HwihaGridMovement {
    const val RULE_VERSION = 1

    data class UnitPosition(val bugokId: Int, val position: Position?, val initiative: Int) {
        init { require(bugokId > 0 && initiative >= 0) }
    }
    data class Intent(val bugokId: Int, val destination: Position) {
        init { require(bugokId > 0) }
    }
    enum class Outcome { MOVED, HELD, INACTIVE, RESERVE, OUTSIDE_COMBAT_AREA, NOT_ADJACENT, OCCUPIED, CONTESTED }
    data class Step(val bugokId: Int, val from: Position?, val to: Position?, val outcome: Outcome)

    /** Invalid state/identities reject the batch; an impossible tactical request leaves its unit in place. */
    fun resolve(layout: HwihaBattlefieldLayout, units: List<UnitPosition>, intents: List<Intent>): List<Step> {
        require(units.isNotEmpty() && units.map { it.bugokId }.distinct().size == units.size)
        val byId = units.associateBy { it.bugokId }
        require(intents.map { it.bugokId }.distinct().size == intents.size && intents.all { it.bugokId in byId })
        val occupied = units.mapNotNull { it.position }
        require(occupied.distinct().size == occupied.size && occupied.all { it in layout.distancesFromEntry })
        val occupiedSet = occupied.toHashSet()
        val requested = intents.associateBy { it.bugokId }
        val results = linkedMapOf<Int, Step>()
        val candidates = mutableListOf<UnitPosition>()
        for (unit in units.sortedBy { it.bugokId }) {
            val target = requested[unit.bugokId]?.destination
            val outcome = when {
                target == null -> Outcome.HELD
                unit.position == null -> Outcome.RESERVE
                target == unit.position -> Outcome.HELD
                target !in layout.distancesFromEntry -> Outcome.OUTSIDE_COMBAT_AREA
                layout.geometry.neighbors(unit.position).none { it.position == target } -> Outcome.NOT_ADJACENT
                target in occupiedSet -> Outcome.OCCUPIED
                else -> null
            }
            if (outcome == null) candidates.add(unit)
            else results[unit.bugokId] = Step(unit.bugokId, unit.position, unit.position, outcome)
        }
        val claimed = hashSetOf<Position>()
        for (unit in candidates.sortedWith(compareByDescending<UnitPosition> { it.initiative }.thenBy { it.bugokId })) {
            val target = requested.getValue(unit.bugokId).destination
            val moved = claimed.add(target)
            results[unit.bugokId] = Step(unit.bugokId, unit.position, if (moved) target else unit.position,
                if (moved) Outcome.MOVED else Outcome.CONTESTED)
        }
        return Collections.unmodifiableList(results.values.sortedBy { it.bugokId })
    }
}
