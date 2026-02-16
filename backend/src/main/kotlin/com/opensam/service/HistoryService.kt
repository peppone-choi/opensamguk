package com.opensam.service

import com.opensam.entity.Message
import com.opensam.repository.MessageRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class HistoryService(
    private val messageRepository: MessageRepository,
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
}
