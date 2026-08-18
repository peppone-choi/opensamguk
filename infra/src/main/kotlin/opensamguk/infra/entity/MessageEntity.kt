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

    // `message.type`은 Postgres ENUM(`message_type`)이고 라벨은 소문자 `.value`다.
    // 두 가지를 동시에 맞춰야 한다.
    //  - 값: `@Enumerated(STRING)`은 `.name`(PRIVATE)을 쓴다 → converter로 `.value`를 쓴다.
    //  - 타입: varchar로 바인딩하면 `operator does not exist: message_type = character varying`
    //    (42883)으로 읽기 쿼리가 죽는다. `columnDefinition`은 DDL 전용이라 바인딩을 못 바꾼다 →
    //    [PostgresValueEnumJdbcType]이 그 문자열을 Types.OTHER로 보내 PG가 enum으로 추론하게 한다.
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
