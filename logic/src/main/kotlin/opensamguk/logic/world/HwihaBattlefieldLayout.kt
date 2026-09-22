package opensamguk.logic.world

import java.util.Collections
import opensamguk.logic.world.HwihaBattlefieldGeometry.Position

/** Land-battle setup, without unit placement, alliances, combat coefficients, or outcomes. */
class HwihaBattlefieldLayout private constructor(
    val geometry: HwihaBattlefieldGeometry,
    val approachProvinceId: String,
    distances: Map<Position, Int>,
    attackerZone: List<Position>,
    defenderZone: List<Position>,
) {
    val distancesFromEntry: Map<Position, Int> = Collections.unmodifiableMap(LinkedHashMap(distances))
    val attackerZone: List<Position> = Collections.unmodifiableList(ArrayList(attackerZone))
    val defenderZone: List<Position> = Collections.unmodifiableList(ArrayList(defenderZone))

    sealed interface Result {
        data class Ready(val layout: HwihaBattlefieldLayout) : Result
        data class Unavailable(val reason: Reason) : Result
    }

    enum class Reason { EMPTY_PROVINCE, NO_PASSABLE_CELLS, NO_PHYSICAL_CONTACT, NO_PASSABLE_CONTACT, INSUFFICIENT_DEPTH }

    companion object {
        const val RULE_VERSION = 1
        private val positionOrder = compareBy(Position::row, Position::col)

        fun isLandPassable(terrain: String): Boolean = when (terrain) {
            "PLAIN", "RIVER", "DESERT", "PLATEAU", "BASIN", "HILL" -> true
            "SEA", "LAKE", "MOUNTAIN", "OUT_OF_SCOPE" -> false
            else -> throw IllegalArgumentException("Unknown battlefield terrain")
        }

        fun prepare(index: HanProvinceCellIndex, provinceId: String, approachProvinceId: String): Result {
            require(provinceId != approachProvinceId) { "Approach must be a different province" }
            val source = index.cellsOf(provinceId)
            val approach = index.cellsOf(approachProvinceId)
            if (source.isEmpty()) return Result.Unavailable(Reason.EMPTY_PROVINCE)
            val geometry = HwihaBattlefieldGeometry.extract(index, provinceId)
            val passable = geometry.cells.filter { isLandPassable(it.terrain) }.mapTo(linkedSetOf()) { it.position }
            if (passable.isEmpty()) return Result.Unavailable(Reason.NO_PASSABLE_CELLS)
            val contact = geometry.borderFacing(index, approachProvinceId)
            if (contact.isEmpty()) return Result.Unavailable(Reason.NO_PHYSICAL_CONTACT)
            val approachPassable = approach.filter { isLandPassable(index.terrainLegend.getValue(it.terrainCode)) }
                .mapTo(hashSetOf()) { Position(it.col, it.row) }
            val entries = contact.filter { cell ->
                val (col, row) = cell.source
                cell.position in passable && (Position(col, row - 1) in approachPassable ||
                    Position(col - 1, row) in approachPassable || Position(col + 1, row) in approachPassable ||
                    Position(col, row + 1) in approachPassable)
            }.mapTo(hashSetOf()) { it.position }
            if (entries.isEmpty()) return Result.Unavailable(Reason.NO_PASSABLE_CONTACT)

            // Keep the source geometry intact; choose the largest accessible combat component.
            // Equal sizes use the first row-major source cell, never collection insertion order.
            val remaining = passable.toMutableSet()
            val components = mutableListOf<List<Position>>()
            while (remaining.isNotEmpty()) {
                val queue = ArrayDeque<Position>()
                queue.add(remaining.minWith(positionOrder))
                remaining.remove(queue.first())
                val component = mutableListOf<Position>()
                while (queue.isNotEmpty()) {
                    val position = queue.removeFirst()
                    component.add(position)
                    for (neighbor in geometry.neighbors(position)) {
                        if (remaining.remove(neighbor.position)) queue.add(neighbor.position)
                    }
                }
                if (component.any { it in entries }) components.add(component.sortedWith(positionOrder))
            }
            val component = components.sortedWith(compareByDescending<List<Position>> { it.size }
                .thenComparator { left, right -> positionOrder.compare(left.first(), right.first()) }).first()
            val selected = component.toHashSet()
            val distances = linkedMapOf<Position, Int>()
            val queue = ArrayDeque<Position>()
            entries.filter { it in selected }.sortedWith(positionOrder).forEach { distances[it] = 0; queue.add(it) }
            while (queue.isNotEmpty()) {
                val position = queue.removeFirst()
                for (neighbor in geometry.neighbors(position)) {
                    if (neighbor.position in selected && neighbor.position !in distances) {
                        distances[neighbor.position] = distances.getValue(position) + 1
                        queue.add(neighbor.position)
                    }
                }
            }
            val depth = distances.values.max()
            if (depth == 0) return Result.Unavailable(Reason.INSUFFICIENT_DEPTH)
            // Outer thirds leave a separating band whenever the source component permits one.
            val zoneDepth = (depth - 1) / 3
            val orderedDistances = component.associateWith { distances.getValue(it) }
            return Result.Ready(HwihaBattlefieldLayout(geometry, approachProvinceId, orderedDistances,
                component.filter { distances.getValue(it) <= zoneDepth },
                component.filter { distances.getValue(it) >= depth - zoneDepth }))
        }
    }
}
