package com.opensam.gateway.service

import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap

@Service
class WorldRouteRegistry {
    private val routes = ConcurrentHashMap<Long, String>()

    fun attach(worldId: Long, baseUrl: String) {
        routes[worldId] = normalizeBaseUrl(baseUrl)
    }

    fun detach(worldId: Long) {
        routes.remove(worldId)
    }

    fun resolve(worldId: Long): String? {
        return routes[worldId]
    }

    fun snapshot(): Map<Long, String> {
        return routes.toSortedMap()
    }

    private fun normalizeBaseUrl(baseUrl: String): String {
        return baseUrl.trim().trimEnd('/')
    }
}
