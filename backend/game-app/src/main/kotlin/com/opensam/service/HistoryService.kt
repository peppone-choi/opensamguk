package com.opensam.service

import com.opensam.entity.Message
import com.opensam.entity.YearbookHistory
import com.opensam.repository.MessageRepository
import com.opensam.repository.YearbookHistoryRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class HistoryService(
    private val messageRepository: MessageRepository,
    private val yearbookHistoryRepository: YearbookHistoryRepository,
) {
    @Transactional
    fun logWorldHistory(worldId: Long, message: String, year: Int, month: Int) {
        messageRepository.save(
            Message(
                worldId = worldId,
                mailboxCode = "world_history",
                messageType = "history",
                payload = mutableMapOf(
                    "message" to message,
                    "year" to year,
                    "month" to month,
                ),
            )
        )
    }

    @Transactional
    fun logNationHistory(worldId: Long, nationId: Long, message: String, year: Int, month: Int) {
        messageRepository.save(
            Message(
                worldId = worldId,
                mailboxCode = "nation_history",
                messageType = "history",
                destId = nationId,
                payload = mutableMapOf(
                    "message" to message,
                    "year" to year,
                    "month" to month,
                ),
            )
        )
    }

    fun getWorldHistory(worldId: Long): List<Message> {
        return messageRepository.findByWorldIdAndMailboxCodeOrderBySentAtDesc(worldId, "world_history")
    }

    fun getWorldRecords(worldId: Long): List<Message> {
        return messageRepository.findByWorldIdAndMailboxCodeOrderBySentAtDesc(worldId, "general_record")
    }

    fun getGeneralRecords(generalId: Long): List<Message> {
        return messageRepository.findBySrcIdAndMailboxCodeOrderBySentAtDesc(generalId, "general_record")
    }

    fun getByYearMonth(worldId: Long, year: Int, month: Int): List<Message> {
        return messageRepository.findByWorldIdAndYearAndMonthOrderBySentAtAsc(worldId, year, month)
    }

    fun getYearbook(worldId: Long, year: Int): YearbookHistory? {
        val yearShort = year.toShort()
        for (month in 12 downTo 1) {
            val snapshot = yearbookHistoryRepository.findByWorldIdAndYearAndMonth(worldId, yearShort, month.toShort())
            if (snapshot != null) {
                return snapshot
            }
        }
        return null
    }

    fun getYearKeyEvents(worldId: Long, year: Int, limit: Int = 10): List<Message> {
        return messageRepository.findByWorldIdAndYearOrderBySentAtDesc(worldId, year).take(limit)
    }
}
