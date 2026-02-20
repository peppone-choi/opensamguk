package com.opensam.entity

import jakarta.persistence.*
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.OffsetDateTime

@Entity
@Table(name = "message")
class Message(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0,

    @Column(name = "world_id", nullable = false)
    var worldId: Long = 0,

    @Column(name = "mailbox_code", nullable = false)
    var mailboxCode: String = "",

    @Column(name = "mailbox_type", nullable = false)
    var mailboxType: String = "",

    @Column(name = "message_type", nullable = false)
    var messageType: String = "",

    @Column(name = "src_id")
    var srcId: Long? = null,

    @Column(name = "dest_id")
    var destId: Long? = null,

    @Column(name = "sent_at", nullable = false)
    var sentAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "valid_until")
    var validUntil: OffsetDateTime? = null,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    var payload: MutableMap<String, Any> = mutableMapOf(),

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    var meta: MutableMap<String, Any> = mutableMapOf(),
) {
    @PrePersist
    fun applyMailboxTypeDefault() {
        if (mailboxType.isNotBlank()) {
            mailboxType = mailboxType.uppercase()
            return
        }

        mailboxType = when (mailboxCode) {
            "secret", "national", "nation" -> "NATIONAL"
            "personal", "message", "private" -> "PRIVATE"
            "diplomacy", "diplomacy_letter" -> "DIPLOMACY"
            else -> "PUBLIC"
        }
    }
}
