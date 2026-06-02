package opensamguk.infra.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import opensamguk.logic.message.MessageType
import java.time.Instant

/**
 * JPA entity for `message` table (PHP `message`, schema.sql:241).
 *
 * `mailbox` routing: 9999 == public, >= 9000 national, < 9000 private (general id).
 * `message` is the polymorphic jsonb body — stored as raw string.
 */
@Entity
@Table(name = "message")
class MessageEntity(
    @Column(name = "mailbox", nullable = false)
    var mailbox: Int,

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false)
    var type: MessageType,

    @Column(name = "src", nullable = false)
    var src: Int,

    @Column(name = "dest", nullable = false)
    var dest: Int,

    @Column(name = "time", nullable = false)
    var time: Instant,

    @Column(name = "valid_until", nullable = false)
    var validUntil: Instant,

    @Column(name = "message", nullable = false, columnDefinition = "jsonb")
    var message: String = "{}",

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Int? = null,
)
