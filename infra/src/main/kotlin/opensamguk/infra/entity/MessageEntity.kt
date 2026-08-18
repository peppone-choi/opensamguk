package opensamguk.infra.entity

import jakarta.persistence.AttributeConverter
import jakarta.persistence.Column
import jakarta.persistence.Convert
import jakarta.persistence.Converter
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import opensamguk.logic.message.MessageType
import org.hibernate.annotations.JdbcType
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
    @Column(name = "world_id", nullable = false)
    var worldId: Int = 0,

    @Column(name = "mailbox", nullable = false)
    var mailbox: Int,

    // PG ENUM `message_type` stores lowercase `.value` labels (`private`, not `PRIVATE`).
    // Two things must match at once:
    //  - value: `@Enumerated(STRING)` binds `.name` (PRIVATE) — the converter binds `.value`.
    //  - type: a varchar bind yields `operator does not exist: message_type = character varying`
    //    (42883). `columnDefinition` is DDL-only, so [PostgresValueEnumJdbcType] sends the
    //    converter string as Types.OTHER and lets PG infer the enum.
    @Convert(converter = MessageTypeValueConverter::class)
    @JdbcType(PostgresValueEnumJdbcType::class)
    @Column(name = "type", nullable = false, columnDefinition = "message_type")
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

@Converter
class MessageTypeValueConverter : AttributeConverter<MessageType, String> {
    override fun convertToDatabaseColumn(attribute: MessageType?): String? = attribute?.value

    override fun convertToEntityAttribute(dbData: String?): MessageType? =
        dbData?.let(MessageType::from)
}
