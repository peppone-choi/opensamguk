package opensamguk.infra.persistence

import java.sql.ResultSet
import java.time.Instant

/**
 * One `message` row (PHP `message`, schema.sql:241). The `message` jsonb body is the polymorphic
 * Message payload — kept as the raw byte-faithful jsonb STRING here (the logic `Message`/`buildFromArray`
 * polymorphic codec is T0.5; this row type is the stable persistence seam the flush channel + the
 * daemon rehydrate consume). `validUntil` defaults to the PHP sentinel `9999-12-31 23:59:59`.
 *
 * `time`/`validUntil` are wall-clock fields (QUARANTINED — see research §11). They are carried as
 * [Instant] but a parity gate asserts only the interval MATH + Y-m-d H:i:s FORMAT, never the instant.
 */
data class MessageRow(
    val id: Int?,
    val mailbox: Int,
    val type: String,
    val src: Int,
    val dest: Int,
    val time: Instant,
    val validUntil: Instant,
    val messageJson: String,
)

/**
 * `message` row <-> [MessageRow] mapper. `type` binds through `CAST(:type AS message_type)`; the
 * jsonb body binds byte-faithfully via [MetaJson] (insertion-order preserved, never re-keyed).
 */
object MessageRowMapper {

    val VALID_UNTIL_SENTINEL: Instant = Instant.parse("9999-12-31T23:59:59Z")

    fun fromRow(row: Map<String, Any?>): MessageRow = MessageRow(
        id = nullableIntOf(row["id"]),
        mailbox = intOf(row["mailbox"]),
        type = stringOf(row["type"]) ?: error("message.type null"),
        src = intOf(row["src"]),
        dest = intOf(row["dest"]),
        time = instantOf(row["time"]),
        validUntil = instantOf(row["valid_until"]),
        messageJson = stringOf(row["message"]) ?: "{}",
    )

    fun fromResultSet(rs: ResultSet): MessageRow = MessageRow(
        id = rs.getInt("id"),
        mailbox = rs.getInt("mailbox"),
        type = rs.getString("type"),
        src = rs.getInt("src"),
        dest = rs.getInt("dest"),
        time = rs.getTimestamp("time").toInstant(),
        validUntil = rs.getTimestamp("valid_until").toInstant(),
        messageJson = rs.getString("message") ?: "{}",
    )

    /** Column map for INSERT (id omitted — assigned by SERIAL/in-memory monotonic sequence). */
    fun toColumns(m: MessageRow): Map<String, Any?> = linkedMapOf(
        "mailbox" to m.mailbox,
        "type" to m.type,
        "src" to m.src,
        "dest" to m.dest,
        "time" to m.time,
        "valid_until" to m.validUntil,
        "message" to m.messageJson,
    )
}

internal fun instantOf(v: Any?): Instant = when (v) {
    null -> Instant.EPOCH
    is Instant -> v
    is java.sql.Timestamp -> v.toInstant()
    is java.util.Date -> v.toInstant()
    is String -> Instant.parse(v)
    else -> error("not a timestamp: $v")
}
