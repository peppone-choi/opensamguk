package com.opensam.repository

import com.opensam.entity.Message
import org.springframework.data.jpa.repository.JpaRepository

interface MessageRepository : JpaRepository<Message, Long> {
    fun findByDestIdOrderBySentAtDesc(destId: Long): List<Message>
    fun findByWorldIdAndMailboxCodeOrderBySentAtDesc(worldId: Long, mailboxCode: String): List<Message>
    fun findBySrcIdAndMailboxCodeOrderBySentAtDesc(srcId: Long, mailboxCode: String): List<Message>
    fun findByWorldIdAndMailboxCodeAndIdGreaterThanOrderBySentAtDesc(worldId: Long, mailboxCode: String, id: Long): List<Message>
    fun findByIdGreaterThanOrderBySentAtDesc(id: Long): List<Message>
    fun findByDestIdAndMailboxCodeOrderBySentAtDesc(destId: Long, mailboxCode: String): List<Message>
    fun findByWorldIdAndMailboxCodeAndDestIdOrderBySentAtDesc(worldId: Long, mailboxCode: String, destId: Long): List<Message>
}
