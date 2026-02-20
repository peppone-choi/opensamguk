package com.opensam.command.general

import com.opensam.command.CommandCost
import com.opensam.command.CommandEnv
import com.opensam.command.CommandResult
import com.opensam.command.GeneralCommand
import com.opensam.command.constraint.*
import com.opensam.entity.General
import kotlin.random.Random

class 접경귀환(general: General, env: CommandEnv, arg: Map<String, Any>? = null)
    : GeneralCommand(general, env, arg) {

    override val actionName = "접경귀환"

    override val fullConditionConstraints: List<Constraint> = listOf(
        NotBeNeutral(),
        NotWanderingNation(),
        NotOccupiedCity(),
    )

    override fun getCost() = CommandCost()
    override fun getPreReqTurn() = 0
    override fun getPostReqTurn() = 0

    override suspend fun run(rng: Random): CommandResult {
        val adjacency = readAdjacency(env.gameStor["mapAdjacency"])
        val cityNationById = readLongMap(env.gameStor["cityNationById"])
        val citySupplyStateById = readIntMap(env.gameStor["citySupplyStateById"])
        val nationId = general.nationId

        if (adjacency.isEmpty() || nationId == 0L) {
            pushLog("3칸 이내에 아국 도시가 없습니다.")
            return CommandResult(success = false, logs = logs)
        }

        val distanceByCity = searchDistance(adjacency, general.cityId, 3)
        val candidates = cityNationById.entries
            .asSequence()
            .filter { (_, ownerNationId) -> ownerNationId == nationId }
            .filter { (cityId, _) -> (citySupplyStateById[cityId] ?: 0) > 0 }
            .mapNotNull { (cityId, _) ->
                val distance = distanceByCity[cityId] ?: return@mapNotNull null
                cityId to distance
            }
            .toList()

        val minDistance = candidates.minOfOrNull { it.second }
        val nearestCities = if (minDistance == null) {
            emptyList()
        } else {
            candidates.filter { it.second == minDistance }.map { it.first }
        }

        if (nearestCities.isEmpty()) {
            pushLog("3칸 이내에 아국 도시가 없습니다.")
            return CommandResult(success = false, logs = logs)
        }

        val destCityId = nearestCities[rng.nextInt(nearestCities.size)]
        pushLog("접경귀환했습니다.")

        return CommandResult(
            success = true,
            logs = logs,
            message = """{"statChanges":{"city":$destCityId}}"""
        )
    }

    private fun searchDistance(adjacency: Map<Long, List<Long>>, startCityId: Long, maxRange: Int): Map<Long, Int> {
        val result = mutableMapOf<Long, Int>()
        val visited = mutableSetOf(startCityId)
        val queue = ArrayDeque<Pair<Long, Int>>()
        queue.addLast(startCityId to 0)

        while (queue.isNotEmpty()) {
            val (cityId, distance) = queue.removeFirst()
            if (distance >= maxRange) {
                continue
            }
            for (next in adjacency[cityId].orEmpty()) {
                if (!visited.add(next)) {
                    continue
                }
                val nextDistance = distance + 1
                result[next] = nextDistance
                if (nextDistance < maxRange) {
                    queue.addLast(next to nextDistance)
                }
            }
        }

        return result
    }

    private fun readAdjacency(raw: Any?): Map<Long, List<Long>> {
        if (raw !is Map<*, *>) {
            return emptyMap()
        }
        val result = mutableMapOf<Long, List<Long>>()
        raw.forEach { (k, v) ->
            val key = asLong(k) ?: return@forEach
            val values = when (v) {
                is Iterable<*> -> v.mapNotNull { asLong(it) }
                else -> emptyList()
            }
            result[key] = values
        }
        return result
    }

    private fun readLongMap(raw: Any?): Map<Long, Long> {
        if (raw !is Map<*, *>) {
            return emptyMap()
        }
        val result = mutableMapOf<Long, Long>()
        raw.forEach { (k, v) ->
            val key = asLong(k) ?: return@forEach
            val value = asLong(v) ?: return@forEach
            result[key] = value
        }
        return result
    }

    private fun readIntMap(raw: Any?): Map<Long, Int> {
        if (raw !is Map<*, *>) {
            return emptyMap()
        }
        val result = mutableMapOf<Long, Int>()
        raw.forEach { (k, v) ->
            val key = asLong(k) ?: return@forEach
            val value = when (v) {
                is Number -> v.toInt()
                is String -> v.toIntOrNull()
                else -> null
            } ?: return@forEach
            result[key] = value
        }
        return result
    }

    private fun asLong(raw: Any?): Long? {
        return when (raw) {
            is Number -> raw.toLong()
            is String -> raw.toLongOrNull()
            else -> null
        }
    }
}
