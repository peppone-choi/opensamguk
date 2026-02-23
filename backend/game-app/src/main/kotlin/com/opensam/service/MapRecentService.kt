package com.opensam.service

import com.opensam.repository.CityRepository
import com.opensam.repository.MessageRepository
import com.opensam.repository.NationRepository
import com.opensam.repository.WorldStateRepository
import org.springframework.stereotype.Service
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant

/**
 * Implements the map-recent endpoint logic from legacy j_map_recent.php.
 *
 * Returns a cached snapshot of the current world map state including:
 * - All city ownership/nation data
 * - Recent global history entries (last 10)
 * - Map theme name
 *
 * The result is cached for 10 minutes with ETag support for HTTP 304 responses.
 */
@Service
class MapRecentService(
    private val worldStateRepository: WorldStateRepository,
    private val cityRepository: CityRepository,
    private val nationRepository: NationRepository,
    private val messageRepository: MessageRepository,
    private val mapService: MapService,
) {
    data class MapRecentCacheEntry(
        val etag: String,
        val timestamp: Long,
        val data: Map<String, Any>,
    )

    @Volatile
    private var cacheByWorld: MutableMap<Long, MapRecentCacheEntry> = mutableMapOf()

    /**
     * Get the recent map snapshot for a world. Returns cached data if fresh (< 10 min).
     *
     * @param worldId the world to query
     * @param clientEtag optional ETag from client for conditional response
     * @return a pair of (data map, isNotModified). If isNotModified is true, caller should return 304.
     */
    fun getMapRecent(worldId: Long, clientEtag: String?): Pair<MapRecentCacheEntry, Boolean> {
        val now = Instant.now().epochSecond
        val cached = cacheByWorld[worldId]

        if (cached != null && (now - cached.timestamp) < 600) {
            // Cache is fresh
            if (clientEtag != null && clientEtag == cached.etag) {
                return cached to true // 304 Not Modified
            }
            return cached to false
        }

        // Build fresh map data
        val entry = buildMapData(worldId, now)
        cacheByWorld[worldId] = entry

        if (clientEtag != null && clientEtag == entry.etag) {
            return entry to true
        }
        return entry to false
    }

    private fun buildMapData(worldId: Long, nowEpoch: Long): MapRecentCacheEntry {
        val world = worldStateRepository.findById(worldId.toShort()).orElse(null)
            ?: return MapRecentCacheEntry(
                etag = "",
                timestamp = nowEpoch,
                data = mapOf("result" to false, "reason" to "서버 초기화되지 않음"),
            )

        val mapCode = (world.config["mapCode"] as? String) ?: "che"
        val mapCities = try {
            mapService.getCities(mapCode)
        } catch (_: Exception) {
            mapService.getCities("che")
        }
        val mapCityByName = mapCities.associateBy { it.name }

        val nations = nationRepository.findByWorldId(worldId)
        val nationById = nations.associateBy { it.id }

        val cities = cityRepository.findByWorldId(worldId)
        val cityDataList = cities.mapNotNull { city ->
            val mapCity = mapCityByName[city.name] ?: return@mapNotNull null
            val nation = nationById[city.nationId]
            mapOf(
                "id" to city.id,
                "name" to city.name,
                "x" to mapCity.x,
                "y" to mapCity.y,
                "level" to city.level.toInt(),
                "region" to city.region.toInt(),
                "nationId" to city.nationId,
                "nationName" to (nation?.name ?: ""),
                "nationColor" to (nation?.color ?: "#4b5563"),
                "pop" to city.pop,
                "popMax" to city.popMax,
                "agri" to city.agri,
                "comm" to city.comm,
                "secu" to city.secu,
                "def" to city.def,
                "wall" to city.wall,
                "trust" to city.trust,
                "state" to city.state.toInt(),
                "supply" to city.supplyState.toInt(),
            )
        }

        // Get recent history (last 10 entries)
        val historyMessages = messageRepository.findByWorldIdAndMailboxCodeOrderBySentAtDesc(worldId, "world_history")
            .take(10)
        val history = historyMessages.map { msg ->
            mapOf(
                "id" to msg.id,
                "message" to (msg.payload["message"]?.toString() ?: ""),
                "year" to (msg.payload["year"] ?: 0),
                "month" to (msg.payload["month"] ?: 0),
                "sentAt" to msg.sentAt.toString(),
            )
        }

        val rawMap = mapOf(
            "result" to true,
            "worldId" to worldId,
            "year" to world.currentYear.toInt(),
            "month" to world.currentMonth.toInt(),
            "cities" to cityDataList,
            "nations" to nations.map { n ->
                mapOf(
                    "id" to n.id,
                    "name" to n.name,
                    "color" to n.color,
                    "level" to n.level.toInt(),
                    "gold" to n.gold,
                    "rice" to n.rice,
                )
            },
            "history" to history,
            "theme" to mapCode,
        )

        // Generate ETag from world ID + timestamp
        val digest = MessageDigest.getInstance("SHA-256")
        val etagInput = "${world.id}$nowEpoch"
        val etagBytes = digest.digest(etagInput.toByteArray())
        val etag = etagBytes.joinToString("") { "%02x".format(it) }

        return MapRecentCacheEntry(
            etag = etag,
            timestamp = nowEpoch,
            data = rawMap,
        )
    }
}
