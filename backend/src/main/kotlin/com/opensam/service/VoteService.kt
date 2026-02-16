package com.opensam.service

import com.opensam.entity.Message
import com.opensam.repository.MessageRepository
import org.springframework.stereotype.Service

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
}
