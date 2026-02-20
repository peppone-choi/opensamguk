package com.opensam.service

import com.opensam.entity.Message
import com.opensam.repository.MessageRepository
import org.springframework.stereotype.Service

@Service
class DiplomacyLetterService(
    private val messageRepository: MessageRepository,
) {
    fun listLetters(nationId: Long): List<Message> {
        return messageRepository.findByDestIdAndMailboxCodeOrderBySentAtDesc(nationId, "diplomacy_letter")
    }

    fun sendLetter(worldId: Long, srcNationId: Long, destNationId: Long, type: String, content: String?): Message {
        return messageRepository.save(Message(
            worldId = worldId,
            mailboxCode = "diplomacy_letter",
            messageType = type,
            srcId = srcNationId,
            destId = destNationId,
            payload = mutableMapOf(
                "content" to (content ?: ""),
                "state" to "pending",
            ),
        ))
    }

    fun respondLetter(id: Long, accept: Boolean): Boolean {
        val letter = messageRepository.findById(id).orElse(null) ?: return false
        letter.payload["state"] = if (accept) "accepted" else "rejected"
        messageRepository.save(letter)
        return true
    }

    fun rollbackLetter(id: Long): Boolean {
        val letter = messageRepository.findById(id).orElse(null) ?: return false
        letter.payload["state"] = "rolled_back"
        messageRepository.save(letter)
        return true
    }

    fun destroyLetter(id: Long): Boolean {
        if (!messageRepository.existsById(id)) return false
        messageRepository.deleteById(id)
        return true
    }
}
