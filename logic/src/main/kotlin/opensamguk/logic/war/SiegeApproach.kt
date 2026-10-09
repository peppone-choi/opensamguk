package opensamguk.logic.war

import opensamguk.logic.world.BattlefieldGeometry.Position
import opensamguk.logic.world.BattlefieldLayout

/** Existing siege placement and a conservative impossibility check; acceptance never promises contact or victory. */
object SiegeApproach {
    data class Placement(val attackers: Map<Int, Position?>, val walls: List<Position>)

    fun placement(layout: BattlefieldLayout, attackerIds: Collection<Int>, garrison: Int): Placement {
        require(attackerIds.isNotEmpty() && attackerIds.toSet().size == attackerIds.size && garrison >= 0)
        val order = compareBy<Position> { it.row }.thenBy { it.col }
        val attackerCells = layout.attackerZone.sortedWith(
            compareBy<Position> { layout.distancesFromEntry.getValue(it) }.then(order))
        val defenderCells = layout.defenderZone.sortedWith(
            compareByDescending<Position> { layout.distancesFromEntry.getValue(it) }.then(order))
        val wallCount = minOf(defenderCells.size, CampaignBalance.ASSAULT_MAX_WALL_TOKENS, garrison)
        return Placement(attackerIds.sorted().mapIndexed { index, id -> id to attackerCells.getOrNull(index) }.toMap(),
            defenderCells.take(wallCount))
    }

    fun isUnreachable(layout: BattlefieldLayout, profiles: Map<Int, UnitProfile>, garrison: Int): Boolean {
        val placement = placement(layout, profiles.keys, garrison)
        if (garrison == 0) return false
        return profiles.all { (id, profile) ->
            val start = placement.attackers[id]
            val reach = BattlePlans.MAX_ROUNDS.toLong() * profile.movementSteps +
                maxOf(profile.attackRange, CampaignBalance.ASSAULT_GARRISON_RANGE)
            start == null || placement.walls.all { wall ->
                // Cardinal moves cannot reduce Manhattan distance by more than one. Obstacles, LOS,
                // occupancy and early retreat can only reduce reach; ignoring them avoids false rejection.
                kotlin.math.abs(start.col.toLong() - wall.col) +
                    kotlin.math.abs(start.row.toLong() - wall.row) > reach
            }
        }
    }
}
