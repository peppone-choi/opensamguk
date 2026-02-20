package com.opensam.service

import com.opensam.dto.VoteCommentResponse
import com.opensam.entity.Message
import com.opensam.repository.MessageRepository
import org.springframework.stereotype.Service
import java.time.OffsetDateTime

@Service
class VoteService(
    private val messageRepository: MessageRepository,
) {
    fun listVotes(worldId: Long): List<Message> {
        return messageRepository.findByWorldIdAndMailboxCodeOrderBySentAtDesc(worldId, "vote")
    }

    fun createVote(worldId: Long, creatorId: Long, title: String, options: List<String>): Message {
        return messageRepository.save(Message(
            worldId = worldId,
            mailboxCode = "vote",
            messageType = "vote",
            srcId = creatorId,
            payload = mutableMapOf(
                "title" to title,
                "options" to options,
                "ballots" to mutableMapOf<String, Any>(),
                "state" to "open",
            ),
        ))
    }

    fun castVote(id: Long, voterId: Long, optionIndex: Int): Boolean {
        val vote = messageRepository.findById(id).orElse(null) ?: return false
        val ballots = (vote.payload["ballots"] as? MutableMap<String, Any>) ?: mutableMapOf()
        ballots[voterId.toString()] = optionIndex
        vote.payload["ballots"] = ballots
        messageRepository.save(vote)
        return true
    }

    fun closeVote(id: Long): Boolean {
        val vote = messageRepository.findById(id).orElse(null) ?: return false
        vote.payload["state"] = "closed"
        messageRepository.save(vote)
        return true
    }

    fun getVoteComments(voteId: Long): List<VoteCommentResponse> {
        val vote = messageRepository.findById(voteId).orElse(null) ?: return emptyList()
        return parseVoteComments(vote)
    }

    fun createVoteComment(voteId: Long, authorGeneralId: Long, content: String): VoteCommentResponse? {
        val trimmed = content.trim()
        if (trimmed.isEmpty()) return null

        val vote = messageRepository.findById(voteId).orElse(null) ?: return null
        val comments = parseVoteComments(vote).toMutableList()
        val nextId = (comments.maxOfOrNull { it.id } ?: 0L) + 1L
        val comment = VoteCommentResponse(
            id = nextId,
            authorGeneralId = authorGeneralId,
            content = trimmed,
            createdAt = OffsetDateTime.now(),
        )

        vote.payload["comments"] = comments.plus(comment).map { existing ->
            mapOf(
                "id" to existing.id,
                "authorGeneralId" to existing.authorGeneralId,
                "content" to existing.content,
                "createdAt" to existing.createdAt.toString(),
            )
        }

        messageRepository.save(vote)
        return comment
    }

    fun deleteVoteComment(voteId: Long, commentId: Long, generalId: Long): Boolean {
        val vote = messageRepository.findById(voteId).orElse(null) ?: return false
        val comments = parseVoteComments(vote).toMutableList()
        val index = comments.indexOfFirst { it.id == commentId }
        if (index < 0) return false
        if (comments[index].authorGeneralId != generalId) return false

        comments.removeAt(index)
        vote.payload["comments"] = comments.map { existing ->
            mapOf(
                "id" to existing.id,
                "authorGeneralId" to existing.authorGeneralId,
                "content" to existing.content,
                "createdAt" to existing.createdAt.toString(),
            )
        }

        messageRepository.save(vote)
        return true
    }

    private fun parseVoteComments(vote: Message): List<VoteCommentResponse> {
        val raw = vote.payload["comments"] as? List<*> ?: return emptyList()
        return raw.mapNotNull { item ->
            val map = item as? Map<*, *> ?: return@mapNotNull null
            val id = (map["id"] as? Number)?.toLong() ?: return@mapNotNull null
            val authorGeneralId = (map["authorGeneralId"] as? Number)?.toLong() ?: return@mapNotNull null
            val content = map["content"] as? String ?: return@mapNotNull null
            val createdAtRaw = map["createdAt"] as? String
            val createdAt = createdAtRaw?.let { rawTime ->
                runCatching { OffsetDateTime.parse(rawTime) }.getOrNull()
            } ?: OffsetDateTime.now()

            VoteCommentResponse(
                id = id,
                authorGeneralId = authorGeneralId,
                content = content,
                createdAt = createdAt,
            )
        }.sortedBy { it.createdAt }
    }
}
