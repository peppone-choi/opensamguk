package com.opensam.engine

import com.opensam.entity.Message
import com.opensam.entity.WorldState
import com.opensam.repository.EventRepository
import com.opensam.repository.MessageRepository
import com.opensam.repository.NationRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class EventService(
    private val eventRepository: EventRepository,
    private val nationRepository: NationRepository,
    private val messageRepository: MessageRepository,
) {
    private val log = LoggerFactory.getLogger(EventService::class.java)

    @Transactional
    fun dispatchEvents(world: WorldState, targetCode: String) {
        val events = eventRepository.findByWorldIdAndTargetCodeOrderByPriorityDescIdAsc(
            world.id.toLong(), targetCode
        )

        for (event in events) {
            if (evaluateCondition(event.condition, world)) {
                log.info("Event #{} triggered (target={}, priority={})", event.id, targetCode, event.priority)
                executeAction(event.action, world)
            }
        }
    }

    private fun evaluateCondition(condition: Map<String, Any>, world: WorldState): Boolean {
        return when (val type = condition["type"] as? String) {
            "always_true" -> true
            "always_false" -> false

            "date" -> {
                val year = (condition["year"] as? Number)?.toShort() ?: return false
                val month = (condition["month"] as? Number)?.toShort() ?: return false
                world.currentYear == year && world.currentMonth == month
            }

            "date_after" -> {
                val year = (condition["year"] as? Number)?.toShort() ?: return false
                val month = (condition["month"] as? Number)?.toShort() ?: return false
                world.currentYear > year || (world.currentYear == year && world.currentMonth >= month)
            }

            "remain_nation" -> {
                val count = (condition["count"] as? Number)?.toInt() ?: return false
                val nationCount = nationRepository.findByWorldId(world.id.toLong()).size
                nationCount <= count
            }

            "and" -> {
                @Suppress("UNCHECKED_CAST")
                val conditions = condition["conditions"] as? List<Map<String, Any>> ?: return false
                conditions.all { evaluateCondition(it, world) }
            }

            "or" -> {
                @Suppress("UNCHECKED_CAST")
                val conditions = condition["conditions"] as? List<Map<String, Any>> ?: return false
                conditions.any { evaluateCondition(it, world) }
            }

            "not" -> {
                @Suppress("UNCHECKED_CAST")
                val sub = condition["condition"] as? Map<String, Any> ?: return false
                !evaluateCondition(sub, world)
            }

            else -> {
                log.warn("Unknown condition type: {}", type)
                false
            }
        }
    }

    private fun executeAction(action: Map<String, Any>, world: WorldState) {
        when (val type = action["type"] as? String) {
            "log" -> {
                val message = action["message"] as? String ?: ""
                log.info("[World {}] History: {}", world.id, message)
                messageRepository.save(
                    Message(
                        worldId = world.id.toLong(),
                        mailboxCode = "world_history",
                        messageType = "history",
                        payload = mutableMapOf(
                            "message" to message,
                            "year" to world.currentYear.toInt(),
                            "month" to world.currentMonth.toInt(),
                        ),
                    )
                )
            }

            "delete_event" -> {
                val eventId = (action["eventId"] as? Number)?.toLong() ?: return
                eventRepository.deleteById(eventId)
                log.info("[World {}] Deleted event #{}", world.id, eventId)
            }

            "notice" -> {
                val message = action["message"] as? String ?: ""
                messageRepository.save(
                    Message(
                        worldId = world.id.toLong(),
                        mailboxCode = "notice",
                        messageType = "notice",
                        payload = mutableMapOf(
                            "message" to message,
                            "year" to world.currentYear.toInt(),
                            "month" to world.currentMonth.toInt(),
                        ),
                    )
                )
                log.info("[World {}] Notice: {}", world.id, message)
            }

            else -> {
                log.warn("[World {}] Unknown action type: {}", world.id, type)
            }
        }
    }
}
