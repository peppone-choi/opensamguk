package com.opensam.service

import com.opensam.entity.WorldHistory
import com.opensam.repository.WorldHistoryRepository
import com.opensam.repository.CityRepository
import com.opensam.repository.GeneralRepository
import com.opensam.repository.NationRepository
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEvent
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime

// ── Typed Game Events ──

/** Base class for all game events. */
abstract class GameEvent(
    source: Any,
    val worldId: Long,
    val year: Short,
    val month: Short,
    val eventType: String,
) : ApplicationEvent(source) {
    open fun toPayload(): MutableMap<String, Any> = mutableMapOf(
        "worldId" to worldId,
        "year" to year.toInt(),
        "month" to month.toInt(),
        "eventType" to eventType,
    )
}

class BattleEvent(
    source: Any, worldId: Long, year: Short, month: Short,
    val attackerGeneralId: Long,
    val defenderGeneralId: Long,
    val attackerNationId: Long,
    val defenderNationId: Long,
    val cityId: Long,
    val result: String, // "attacker_win", "defender_win", "draw"
    val attackerLoss: Int = 0,
    val defenderLoss: Int = 0,
    val detail: Map<String, Any> = emptyMap(),
) : GameEvent(source, worldId, year, month, "battle") {
    override fun toPayload() = super.toPayload().apply {
        put("attackerGeneralId", attackerGeneralId)
        put("defenderGeneralId", defenderGeneralId)
        put("attackerNationId", attackerNationId)
        put("defenderNationId", defenderNationId)
        put("cityId", cityId)
        put("result", result)
        put("attackerLoss", attackerLoss)
        put("defenderLoss", defenderLoss)
        putAll(detail)
    }
}

class DiplomacyEvent(
    source: Any, worldId: Long, year: Short, month: Short,
    val fromNationId: Long,
    val toNationId: Long,
    val diplomacyType: String, // "war_declare", "cease_fire", "alliance", "tribute", "subjugate", "merge"
    val detail: Map<String, Any> = emptyMap(),
) : GameEvent(source, worldId, year, month, "diplomacy") {
    override fun toPayload() = super.toPayload().apply {
        put("fromNationId", fromNationId)
        put("toNationId", toNationId)
        put("diplomacyType", diplomacyType)
        putAll(detail)
    }
}

class TurnEvent(
    source: Any, worldId: Long, year: Short, month: Short,
    val phase: String = "turn_advance", // "turn_advance", "year_start", "year_end", "season_change"
    val detail: Map<String, Any> = emptyMap(),
) : GameEvent(source, worldId, year, month, "turn") {
    override fun toPayload() = super.toPayload().apply {
        put("phase", phase)
        putAll(detail)
    }
}

class NationEvent(
    source: Any, worldId: Long, year: Short, month: Short,
    val nationId: Long,
    val nationEventType: String, // "founded", "destroyed", "level_up", "capital_moved", "policy_changed"
    val detail: Map<String, Any> = emptyMap(),
) : GameEvent(source, worldId, year, month, "nation") {
    override fun toPayload() = super.toPayload().apply {
        put("nationId", nationId)
        put("nationEventType", nationEventType)
        putAll(detail)
    }
}

class GeneralEvent(
    source: Any, worldId: Long, year: Short, month: Short,
    val generalId: Long,
    val nationId: Long = 0,
    val generalEventType: String, // "joined", "defected", "died", "promoted", "level_up", "command_executed"
    val detail: Map<String, Any> = emptyMap(),
) : GameEvent(source, worldId, year, month, "general") {
    override fun toPayload() = super.toPayload().apply {
        put("generalId", generalId)
        put("nationId", nationId)
        put("generalEventType", generalEventType)
        putAll(detail)
    }
}

@Service
class GameEventService(
    private val messagingTemplate: SimpMessagingTemplate,
    private val worldHistoryRepository: WorldHistoryRepository,
    private val applicationEventPublisher: ApplicationEventPublisher,
) {
    private val log = LoggerFactory.getLogger(GameEventService::class.java)

    // ── WebSocket Broadcasting ──

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

    // ── Event Publishing (Spring ApplicationEvent system) ──

    /**
     * Publish a typed game event. This triggers:
     * 1. Spring @EventListener handlers (including our own logging handler)
     * 2. WebSocket broadcast to connected clients
     */
    fun publish(event: GameEvent) {
        applicationEventPublisher.publishEvent(event)
    }

    /**
     * Convenience: fire a battle event.
     */
    fun fireBattle(
        worldId: Long, year: Short, month: Short,
        attackerGeneralId: Long, defenderGeneralId: Long,
        attackerNationId: Long, defenderNationId: Long,
        cityId: Long, result: String,
        attackerLoss: Int = 0, defenderLoss: Int = 0,
        detail: Map<String, Any> = emptyMap(),
    ) {
        publish(BattleEvent(
            this, worldId, year, month,
            attackerGeneralId, defenderGeneralId,
            attackerNationId, defenderNationId,
            cityId, result, attackerLoss, defenderLoss, detail,
        ))
    }

    /**
     * Convenience: fire a diplomacy event.
     */
    fun fireDiplomacy(
        worldId: Long, year: Short, month: Short,
        fromNationId: Long, toNationId: Long,
        diplomacyType: String, detail: Map<String, Any> = emptyMap(),
    ) {
        publish(DiplomacyEvent(
            this, worldId, year, month,
            fromNationId, toNationId, diplomacyType, detail,
        ))
    }

    /**
     * Convenience: fire a turn event.
     */
    fun fireTurn(worldId: Long, year: Short, month: Short, phase: String = "turn_advance", detail: Map<String, Any> = emptyMap()) {
        publish(TurnEvent(this, worldId, year, month, phase, detail))
    }

    /**
     * Convenience: fire a nation event.
     */
    fun fireNation(
        worldId: Long, year: Short, month: Short,
        nationId: Long, nationEventType: String,
        detail: Map<String, Any> = emptyMap(),
    ) {
        publish(NationEvent(this, worldId, year, month, nationId, nationEventType, detail))
    }

    /**
     * Convenience: fire a general event.
     */
    fun fireGeneral(
        worldId: Long, year: Short, month: Short,
        generalId: Long, nationId: Long = 0,
        generalEventType: String, detail: Map<String, Any> = emptyMap(),
    ) {
        publish(GeneralEvent(this, worldId, year, month, generalId, nationId, generalEventType, detail))
    }

    // ── Event Listener: Persist to DB + broadcast via WebSocket ──

    @EventListener
    @Transactional
    fun onGameEvent(event: GameEvent) {
        // 1. Persist to world_history table
        val history = WorldHistory(
            worldId = event.worldId,
            year = event.year,
            month = event.month,
            eventType = event.eventType,
            payload = event.toPayload(),
        )
        worldHistoryRepository.save(history)

        // 2. Broadcast via WebSocket
        val payload = event.toPayload().apply { put("historyId", history.id) }
        when (event) {
            is BattleEvent -> {
                broadcastBattle(event.worldId, payload)
                sendToGeneral(event.attackerGeneralId, payload)
                sendToGeneral(event.defenderGeneralId, payload)
            }
            is TurnEvent -> {
                broadcastTurnAdvance(event.worldId, event.year.toInt(), event.month.toInt())
                broadcastWorldUpdate(event.worldId, payload)
            }
            is DiplomacyEvent -> {
                broadcastWorldUpdate(event.worldId, payload)
            }
            is NationEvent -> {
                broadcastWorldUpdate(event.worldId, payload)
            }
            is GeneralEvent -> {
                sendToGeneral(event.generalId, payload)
                broadcastWorldUpdate(event.worldId, payload)
            }
            else -> {
                broadcastWorldUpdate(event.worldId, payload)
            }
        }

        log.debug("[World {}] Event logged: type={}, id={}", event.worldId, event.eventType, history.id)
    }

    // ── Event Query API ──

    /**
     * Get all history events for a world.
     */
    fun getWorldHistory(worldId: Long): List<WorldHistory> {
        return worldHistoryRepository.findByWorldIdOrderByCreatedAtDesc(worldId)
    }

    /**
     * Get history events by type.
     */
    fun getWorldHistoryByType(worldId: Long, eventType: String): List<WorldHistory> {
        return worldHistoryRepository.findByWorldIdAndEventType(worldId, eventType)
    }

    /**
     * Get history events for a specific year/month.
     */
    fun getWorldHistoryByDate(worldId: Long, year: Short, month: Short): List<WorldHistory> {
        return worldHistoryRepository.findByWorldIdAndYearAndMonth(worldId, year, month)
    }

    /**
     * Query events by general ID (searches payload JSONB).
     */
    fun getEventsByGeneral(worldId: Long, generalId: Long): List<WorldHistory> {
        return worldHistoryRepository.findByWorldIdAndEventType(worldId, "general")
            .filter { (it.payload["generalId"] as? Number)?.toLong() == generalId }
    }

    /**
     * Query events by nation ID (searches payload JSONB).
     */
    fun getEventsByNation(worldId: Long, nationId: Long): List<WorldHistory> {
        return worldHistoryRepository.findByWorldId(worldId)
            .filter {
                (it.payload["nationId"] as? Number)?.toLong() == nationId ||
                (it.payload["fromNationId"] as? Number)?.toLong() == nationId ||
                (it.payload["toNationId"] as? Number)?.toLong() == nationId ||
                (it.payload["attackerNationId"] as? Number)?.toLong() == nationId ||
                (it.payload["defenderNationId"] as? Number)?.toLong() == nationId
            }
    }

    /**
     * Query events by time range (year/month based).
     */
    fun getEventsByTimeRange(worldId: Long, startYear: Short, startMonth: Short, endYear: Short, endMonth: Short): List<WorldHistory> {
        val startTotal = startYear.toInt() * 12 + startMonth.toInt()
        val endTotal = endYear.toInt() * 12 + endMonth.toInt()
        return worldHistoryRepository.findByWorldId(worldId).filter {
            val total = it.year.toInt() * 12 + it.month.toInt()
            total in startTotal..endTotal
        }
    }

    /**
     * Get recent events (last N).
     */
    fun getRecentEvents(worldId: Long, limit: Int = 50): List<WorldHistory> {
        return worldHistoryRepository.findByWorldIdOrderByCreatedAtDesc(worldId).take(limit)
    }

    /**
     * Log a custom event directly (for legacy compatibility / manual event injection).
     */
    @Transactional
    fun logEvent(
        worldId: Long, year: Short, month: Short,
        eventType: String, payload: MutableMap<String, Any>,
    ): WorldHistory {
        val history = WorldHistory(
            worldId = worldId,
            year = year,
            month = month,
            eventType = eventType,
            payload = payload,
        )
        return worldHistoryRepository.save(history)
    }
}
