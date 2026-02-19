package com.opensam.service

import com.opensam.dto.PublicCachedMapCityResponse
import com.opensam.dto.PublicCachedMapHistoryResponse
import com.opensam.dto.PublicCachedMapResponse
import com.opensam.repository.CityRepository
import com.opensam.repository.MessageRepository
import com.opensam.repository.NationRepository
import com.opensam.repository.WorldStateRepository
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant

@Service
class PublicCachedMapService(
    private val worldStateRepository: WorldStateRepository,
    private val cityRepository: CityRepository,
    private val nationRepository: NationRepository,
    private val messageRepository: MessageRepository,
    private val mapService: MapService,
) {
    private data class CacheEntry(
        val expiresAt: Instant,
        val payload: PublicCachedMapResponse,
    )

    @Volatile
    private var cacheEntry: CacheEntry? = null

    fun getCachedMap(): PublicCachedMapResponse {
        val now = Instant.now()
        val current = cacheEntry
        if (current != null && now.isBefore(current.expiresAt)) {
            return current.payload
        }

        val payload = buildPayload()
        cacheEntry = CacheEntry(
            expiresAt = now.plus(Duration.ofMinutes(10)),
            payload = payload,
        )
        return payload
    }

    private fun buildPayload(): PublicCachedMapResponse {
        val world = worldStateRepository.findAll().maxByOrNull { it.updatedAt }
            ?: return PublicCachedMapResponse(
                available = false,
                worldId = null,
                worldName = null,
                mapCode = null,
                cities = emptyList(),
                history = emptyList(),
            )

        val worldId = world.id.toLong()
        val mapCode = (world.config["mapCode"] as? String) ?: "che"
        val mapCityByName = mapService.getCities(mapCode).associateBy { it.name }

        val nationById = nationRepository.findByWorldId(worldId).associateBy { it.id }
        val cities = cityRepository.findByWorldId(worldId).mapNotNull { city ->
            val mapCity = mapCityByName[city.name] ?: return@mapNotNull null
            val nation = nationById[city.nationId]
            PublicCachedMapCityResponse(
                id = city.id,
                name = city.name,
                x = mapCity.x,
                y = mapCity.y,
                nationName = nation?.name ?: "중립",
                nationColor = nation?.color ?: "#4b5563",
            )
        }

        val history = messageRepository.findByWorldIdAndMailboxCodeOrderBySentAtDesc(worldId, "world_history")
            .take(10)
            .map { message ->
                PublicCachedMapHistoryResponse(
                    id = message.id,
                    sentAt = message.sentAt,
                    text = message.payload["message"]?.toString() ?: "",
                )
            }

        return PublicCachedMapResponse(
            available = true,
            worldId = worldId,
            worldName = world.name,
            mapCode = mapCode,
            cities = cities,
            history = history,
        )
    }
}
