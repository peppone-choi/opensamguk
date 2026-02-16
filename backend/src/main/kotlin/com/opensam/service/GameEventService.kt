package com.opensam.service

import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.stereotype.Service

@Service
class GameEventService(
    private val messagingTemplate: SimpMessagingTemplate
) {
    fun broadcastWorldUpdate(worldId: Long, data: Any) {
        messagingTemplate.convertAndSend("/topic/world/$worldId/update", data)
    }

    fun broadcastCityUpdate(worldId: Long, cityId: Long, data: Any) {
        messagingTemplate.convertAndSend("/topic/world/$worldId/city/$cityId", data)
    }

    fun broadcastBattle(worldId: Long, data: Any) {
        messagingTemplate.convertAndSend("/topic/world/$worldId/battle", data)
    }

    fun sendToGeneral(generalId: Long, data: Any) {
        messagingTemplate.convertAndSend("/topic/general/$generalId", data)
    }

    fun broadcastTurnAdvance(worldId: Long, year: Int, month: Int) {
        messagingTemplate.convertAndSend(
            "/topic/world/$worldId/turn",
            mapOf("year" to year, "month" to month)
        )
    }
}
