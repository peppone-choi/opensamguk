package com.opensam.service

import com.opensam.dto.ContactInfo
import com.opensam.dto.BoardCommentResponse
import com.opensam.entity.BoardComment
import com.opensam.entity.Message
import com.opensam.repository.BoardCommentRepository
import com.opensam.repository.GeneralRepository
import com.opensam.repository.MessageRepository
import com.opensam.repository.NationRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime

@Service
class MessageService(
    private val messageRepository: MessageRepository,
    private val boardCommentRepository: BoardCommentRepository,
    private val generalRepository: GeneralRepository,
    private val nationRepository: NationRepository,
) {
    companion object {
        const val MAILBOX_PUBLIC = "PUBLIC"
        const val MAILBOX_NATIONAL = "NATIONAL"
        const val MAILBOX_PRIVATE = "PRIVATE"
        const val MAILBOX_DIPLOMACY = "DIPLOMACY"
    }

    fun getMessages(destId: Long): List<Message> {
        return messageRepository.findByDestIdOrderBySentAtDesc(destId)
    }

    fun getPublicMessages(worldId: Long): List<Message> {
        return messageRepository.findByWorldIdAndMailboxTypeOrderBySentAtDesc(worldId, MAILBOX_PUBLIC)
    }

    fun getNationalMessages(nationId: Long): List<Message> {
        return messageRepository.findByDestIdAndMailboxTypeOrderBySentAtDesc(nationId, MAILBOX_NATIONAL)
    }

    fun getPrivateMessages(generalId: Long): List<Message> {
        return messageRepository.findConversationByMailboxTypeAndOwnerId(MAILBOX_PRIVATE, generalId)
    }

    fun getDiplomacyMessages(nationId: Long, officerLevel: Short): List<Message> {
        require(officerLevel >= 4) { "Diplomacy mailbox requires officer level 4 or higher" }
        return messageRepository.findConversationByMailboxTypeAndOwnerId(MAILBOX_DIPLOMACY, nationId)
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
        mailboxType: String? = null,
        messageType: String,
        srcId: Long?,
        destId: Long?,
        officerLevel: Short? = null,
        payload: Map<String, Any>,
    ): Message {
        val resolvedMailboxType = resolveMailboxType(mailboxType, mailboxCode)

        if (resolvedMailboxType == MAILBOX_DIPLOMACY) {
            val resolvedOfficerLevel = officerLevel ?: srcId
                ?.let { senderId -> generalRepository.findById(senderId).orElse(null)?.officerLevel }
            require((resolvedOfficerLevel ?: 0) >= 4) { "Diplomacy mailbox requires officer level 4 or higher" }
        }

        if (resolvedMailboxType == MAILBOX_NATIONAL && mailboxCode == "national" && srcId != null && destId != null) {
            val recipientNationIds = linkedSetOf(srcId, destId)
            val copies = recipientNationIds.map { nationId ->
                Message(
                    worldId = worldId,
                    mailboxCode = mailboxCode,
                    mailboxType = resolvedMailboxType,
                    messageType = messageType,
                    srcId = srcId,
                    destId = nationId,
                    payload = payload.toMutableMap(),
                )
            }
            return messageRepository.saveAll(copies).first()
        }

        return messageRepository.save(
            Message(
                worldId = worldId,
                mailboxCode = mailboxCode,
                mailboxType = resolvedMailboxType,
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

    fun getBoardComments(postId: Long): List<BoardCommentResponse> {
        val post = getBoardPost(postId)
        migrateLegacyPayloadComments(post)
        return boardCommentRepository.findByBoardIdOrderByCreatedAtAsc(postId).map(::toBoardCommentResponse)
    }

    @Transactional
    fun createBoardComment(postId: Long, authorGeneralId: Long, content: String): BoardCommentResponse {
        val post = getBoardPost(postId)
        migrateLegacyPayloadComments(post)

        val saved = boardCommentRepository.save(
            BoardComment(
                boardId = postId,
                authorGeneralId = authorGeneralId,
                content = content,
                createdAt = OffsetDateTime.now(),
            )
        )

        return BoardCommentResponse(
            id = saved.id,
            authorGeneralId = authorGeneralId,
            content = content,
            createdAt = saved.createdAt,
        )
    }

    @Transactional
    fun deleteBoardComment(postId: Long, commentId: Long, generalId: Long): Boolean {
        val post = getBoardPost(postId)
        migrateLegacyPayloadComments(post)

        val comment = boardCommentRepository.findById(commentId).orElse(null) ?: return false
        if (comment.boardId != postId) return false
        if (comment.authorGeneralId != generalId) return false

        boardCommentRepository.delete(comment)
        return true
    }

    @Transactional
    fun respondDiplomacy(messageId: Long, accept: Boolean) {
        val message = messageRepository.findById(messageId).orElseThrow()
        message.meta["responded"] = true
        message.meta["accepted"] = accept
        messageRepository.save(message)
    }

    private fun getBoardPost(postId: Long): Message {
        val post = messageRepository.findById(postId).orElseThrow {
            IllegalArgumentException("Board post not found: $postId")
        }
        require(post.mailboxCode == "board" || post.mailboxCode == "secret") {
            "Not a board post: $postId"
        }
        return post
    }

    private fun parseLegacyBoardComments(post: Message): List<BoardCommentResponse> {
        val raw = post.payload["comments"] as? List<*> ?: return emptyList()
        return raw.mapNotNull { item ->
            val map = item as? Map<*, *> ?: return@mapNotNull null
            val id = (map["id"] as? Number)?.toLong() ?: return@mapNotNull null
            val authorGeneralId = (map["authorGeneralId"] as? Number)?.toLong() ?: return@mapNotNull null
            val content = map["content"]?.toString() ?: return@mapNotNull null
            val createdAtRaw = map["createdAt"]?.toString() ?: return@mapNotNull null
            val createdAt = runCatching { OffsetDateTime.parse(createdAtRaw) }.getOrNull() ?: return@mapNotNull null

            BoardCommentResponse(
                id = id,
                authorGeneralId = authorGeneralId,
                content = content,
                createdAt = createdAt,
            )
        }
    }

    private fun migrateLegacyPayloadComments(post: Message) {
        val existing = boardCommentRepository.findByBoardIdOrderByCreatedAtAsc(post.id)
        if (existing.isNotEmpty()) return

        val legacy = parseLegacyBoardComments(post)
        if (legacy.isEmpty()) return

        val entities = legacy.map {
            BoardComment(
                boardId = post.id,
                authorGeneralId = it.authorGeneralId,
                content = it.content,
                createdAt = it.createdAt,
            )
        }
        boardCommentRepository.saveAll(entities)
        post.payload.remove("comments")
        messageRepository.save(post)
    }

    private fun toBoardCommentResponse(comment: BoardComment): BoardCommentResponse {
        return BoardCommentResponse(
            id = comment.id,
            authorGeneralId = comment.authorGeneralId,
            content = comment.content,
            createdAt = comment.createdAt,
        )
    }

    private fun resolveMailboxType(mailboxType: String?, mailboxCode: String): String {
        val normalizedType = mailboxType?.trim()?.uppercase()
        if (normalizedType in setOf(MAILBOX_PUBLIC, MAILBOX_NATIONAL, MAILBOX_PRIVATE, MAILBOX_DIPLOMACY)) {
            return normalizedType!!
        }

        return when (mailboxCode) {
            "secret", "national", "nation" -> MAILBOX_NATIONAL
            "personal", "message", "private" -> MAILBOX_PRIVATE
            "diplomacy", "diplomacy_letter" -> MAILBOX_DIPLOMACY
            else -> MAILBOX_PUBLIC
        }
    }
}
