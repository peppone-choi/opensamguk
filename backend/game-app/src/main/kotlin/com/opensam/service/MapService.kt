package com.opensam.service

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.opensam.model.CityConst
import jakarta.annotation.PostConstruct
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Service
import java.util.LinkedList

@Service
class MapService {

    private val maps = mutableMapOf<String, List<CityConst>>()
    private val adjacencyIndex = mutableMapOf<String, Map<Int, List<Int>>>()

    @PostConstruct
    fun init() {
        loadMap("che")
    }

    private fun loadMap(mapName: String) {
        val mapper = ObjectMapper().registerModule(KotlinModule.Builder().build())
        val resource = ClassPathResource("data/maps/$mapName.json")
        val data: Map<String, Any> = mapper.readValue(resource.inputStream, object : TypeReference<Map<String, Any>>() {})

        @Suppress("UNCHECKED_CAST")
        val rawCities = data["cities"] as List<Map<String, Any>>

        val cities = rawCities.map { raw ->
            @Suppress("UNCHECKED_CAST")
            CityConst(
                id = (raw["id"] as Number).toInt(),
                name = raw["name"] as String,
                level = (raw["level"] as Number).toInt(),
                region = (raw["region"] as Number).toInt(),
                population = (raw["population"] as Number).toInt(),
                agriculture = (raw["agriculture"] as Number).toInt(),
                commerce = (raw["commerce"] as Number).toInt(),
                security = (raw["security"] as Number).toInt(),
                defence = (raw["defence"] as Number).toInt(),
                wall = (raw["wall"] as Number).toInt(),
                x = (raw["x"] as Number).toInt(),
                y = (raw["y"] as Number).toInt(),
                connections = (raw["connections"] as List<Number>).map { it.toInt() }
            )
        }

        maps[mapName] = cities
        adjacencyIndex[mapName] = cities.associate { it.id to it.connections }
    }

    fun getCities(mapName: String): List<CityConst> {
        if (!maps.containsKey(mapName)) {
            loadMap(mapName)
        }
        return maps[mapName] ?: throw IllegalArgumentException("Unknown map: $mapName")
    }

    fun getCity(mapName: String, cityId: Int): CityConst? {
        return getCities(mapName).find { it.id == cityId }
    }

    fun getAdjacentCities(mapName: String, cityId: Int): List<Int> {
        if (!adjacencyIndex.containsKey(mapName)) {
            loadMap(mapName)
        }
        val adj = adjacencyIndex[mapName] ?: throw IllegalArgumentException("Unknown map: $mapName")
        return adj[cityId] ?: emptyList()
    }

    fun getMapJson(mapName: String): JsonNode? {
        val resource = ClassPathResource("data/maps/$mapName.json")
        if (!resource.exists()) return null
        val mapper = ObjectMapper().registerModule(KotlinModule.Builder().build())
        return mapper.readTree(resource.inputStream)
    }

    fun getDistance(mapName: String, fromCityId: Int, toCityId: Int): Int {
        if (fromCityId == toCityId) return 0

        if (!adjacencyIndex.containsKey(mapName)) {
            loadMap(mapName)
        }
        val adj = adjacencyIndex[mapName] ?: throw IllegalArgumentException("Unknown map: $mapName")

        val visited = mutableSetOf(fromCityId)
        val queue = LinkedList<Pair<Int, Int>>()
        queue.add(fromCityId to 0)

        while (queue.isNotEmpty()) {
            val (current, dist) = queue.poll()
            for (neighbor in adj[current] ?: emptyList()) {
                if (neighbor == toCityId) return dist + 1
                if (visited.add(neighbor)) {
                    queue.add(neighbor to dist + 1)
                }
            }
        }

        return -1
    }
}
