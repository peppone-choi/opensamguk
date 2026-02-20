package com.opensam.engine

import org.springframework.stereotype.Service
import java.util.ArrayDeque

@Service
class DistanceService {
    data class DistanceCity(
        val id: Int,
        val connections: List<Int>,
    )

    data class DistanceMap(
        val cities: List<DistanceCity>,
    )

    fun getCityDistance(map: DistanceMap, startCityId: Int, endCityId: Int): Int {
        if (startCityId == endCityId) {
            return 0
        }

        val byId = map.cities.associateBy { it.id }
        val visited = mutableSetOf<Int>()
        val queue = ArrayDeque<Pair<Int, Int>>()

        queue.add(startCityId to 0)
        visited.add(startCityId)

        while (queue.isNotEmpty()) {
            val (currentId, dist) = queue.removeFirst()
            val cityDef = byId[currentId] ?: continue

            for (neighborId in cityDef.connections) {
                if (neighborId == endCityId) {
                    return dist + 1
                }
                if (visited.add(neighborId)) {
                    queue.add(neighborId to (dist + 1))
                }
            }
        }

        return Int.MAX_VALUE
    }

    fun searchDistance(map: DistanceMap, startCityId: Int, range: Int): Map<Int, Int> {
        val byId = map.cities.associateBy { it.id }
        val result = mutableMapOf<Int, Int>()
        val visited = mutableSetOf<Int>()
        val queue = ArrayDeque<Pair<Int, Int>>()

        queue.add(startCityId to 0)
        visited.add(startCityId)
        result[startCityId] = 0

        while (queue.isNotEmpty()) {
            val (currentId, dist) = queue.removeFirst()
            if (dist >= range) {
                continue
            }

            val cityDef = byId[currentId] ?: continue
            for (neighborId in cityDef.connections) {
                if (visited.add(neighborId)) {
                    val newDist = dist + 1
                    result[neighborId] = newDist
                    queue.add(neighborId to newDist)
                }
            }
        }

        return result
    }
}
