package com.opensam.service

import com.opensam.dto.ContactInfo
import com.opensam.entity.Message
import com.opensam.repository.GeneralRepository
import com.opensam.repository.MessageRepository
import com.opensam.repository.NationRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime

@Service
class MessageService(
    private val messageRepository: MessageRepository,
    private val generalRepository: GeneralRepository,
    private val nationRepository: NationRepository,
) {
    fun getMessages(destId: Long): List<Message> {
        return messageRepository.findByDestIdOrderBySentAtDesc(destId)
    }

    fun getBoardMessages(worldId: Long): List<Message> {
        return messageRepository.findByWorldIdAndMailboxCodeOrderBySentAtDesc(worldId, "board")
    }

    fun getSecretBoardMessages(worldId: Long, nationId: Long): List<Message> {
        return messageRepository.findByWorldIdAndMailboxCodeAndDestIdOrderBySentAtDesc(worldId, "secret", nationId)
    }

    @Transactional
    fun sendMessage(
        worldId: Long,
        mailboxCode: String,
        messageType: String,
        srcId: Long?,
        destId: Long?,
        payload: Map<String, Any>,
    ): Message {
        return messageRepository.save(
            Message(
                worldId = worldId,
                mailboxCode = mailboxCode,
                messageType = messageType,
                srcId = srcId,
                destId = destId,
                payload = payload.toMutableMap(),
            )
        )
    }

    @Transactional
    fun deleteMessage(id: Long) {
        messageRepository.deleteById(id)
    }

    @Transactional
    fun markAsRead(id: Long) {
        val message = messageRepository.findById(id).orElseThrow {
            IllegalArgumentException("Message not found: $id")
        }
        message.meta["readAt"] = OffsetDateTime.now().toString()
        messageRepository.save(message)
    }

    fun getContacts(worldId: Long): List<ContactInfo> {
        val generals = generalRepository.findByWorldId(worldId)
        val nations = nationRepository.findByWorldId(worldId).associateBy { it.id }
        return generals.map { gen ->
            ContactInfo(
                generalId = gen.id,
                name = gen.name,
                nationId = gen.nationId,
                nationName = nations[gen.nationId]?.name ?: "",
                picture = gen.picture,
            )
        }
    }

    fun getRecentMessages(lastId: Long): List<Message> {
        return messageRepository.findByIdGreaterThanOrderBySentAtDesc(lastId)
    }

    @Transactional
    fun respondDiplomacy(messageId: Long, accept: Boolean) {
        val message = messageRepository.findById(messageId).orElseThrow()
        message.meta["responded"] = true
        message.meta["accepted"] = accept
        messageRepository.save(message)
    }
}
