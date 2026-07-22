package opensamguk.infra.persistence

import opensamguk.common.world.WorldId
import org.postgresql.util.PGobject
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.sql.Timestamp
import java.time.Instant

data class PendingCommandOutboxEvent(
    val eventId: String,
    val requestId: String,
    val payloadJson: String,
)

open class CommandResultRepository(
    private val jdbc: NamedParameterJdbcTemplate,
) {
    open fun findResultPayload(worldId: WorldId, requestId: String): String? =
        jdbc.query(
            """
            SELECT result_payload::text AS result_payload
              FROM command_result
             WHERE world_id = :world_id AND request_id = :request_id
             ORDER BY result_seq DESC, created_at DESC
             LIMIT 1
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("world_id", worldId.value)
                .addValue("request_id", requestId),
        ) { rs, _ -> rs.getString("result_payload") }
            .firstOrNull()

    open fun findPendingCommandResultOutbox(
        worldId: WorldId,
        limit: Int = 100,
    ): List<PendingCommandOutboxEvent> {
        require(limit > 0) { "limit must be positive" }
        return jdbc.query(
            """
            SELECT event_id, request_id, payload::text AS payload
              FROM command_outbox
             WHERE world_id = :world_id
               AND event_type = 'COMMAND_RESULT'
               AND published_at IS NULL
             ORDER BY created_at, event_id
             LIMIT :limit
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("world_id", worldId.value)
                .addValue("limit", limit),
        ) { rs, _ ->
            PendingCommandOutboxEvent(
                eventId = rs.getString("event_id"),
                requestId = rs.getString("request_id"),
                payloadJson = rs.getString("payload"),
            )
        }
    }

    open fun markCommandOutboxPublished(worldId: WorldId, eventId: String, publishedAt: Instant): Boolean =
        jdbc.update(
            """
            UPDATE command_outbox
               SET published_at = :published_at
             WHERE world_id = :world_id
               AND event_id = :event_id
               AND published_at IS NULL
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("world_id", worldId.value)
                .addValue("event_id", eventId)
                .addValue("published_at", Timestamp.from(publishedAt)),
        ) == 1

    open fun insertTerminalResult(
        worldId: WorldId,
        row: CommandResultRow,
        expectedInboxStatuses: Collection<String> = setOf("ACCEPTED", "CLAIMED"),
    ) {
        val terminalStatus = if (row.ok) "APPLIED" else "REJECTED"
        val params = MapSqlParameterSource()
            .addValue("world_id", worldId.value)
            .addValue("request_id", row.requestId)
            .addValue("result_seq", row.resultSeq)
            .addValue("terminal_status", terminalStatus)
            .addValue("result_type", row.resultType)
            .addValue("ok", row.ok)
            .addValue("committed_world_version", row.committedWorldVersion)
            .addValue("payload_schema_version", row.payloadSchemaVersion)
            .addValue("result_payload", jsonb(row.envelopeJson))
            .addValue("sent_at", Timestamp.from(row.sentAt))
            .addValue("event_id", row.eventId)
            .addValue("expected_statuses", expectedInboxStatuses)
        jdbc.update(
            """
            INSERT INTO command_result (
                world_id,
                request_id,
                result_seq,
                terminal_status,
                result_type,
                ok,
                committed_world_version,
                payload_schema_version,
                result_payload,
                sent_at
            ) VALUES (
                :world_id,
                :request_id,
                :result_seq,
                :terminal_status,
                :result_type,
                :ok,
                :committed_world_version,
                :payload_schema_version,
                :result_payload,
                :sent_at
            )
            ON CONFLICT (world_id, request_id, result_seq)
            DO NOTHING
            """.trimIndent(),
            params,
        )
        jdbc.update(
            """
            INSERT INTO command_outbox (
                event_id,
                world_id,
                request_id,
                event_type,
                payload_schema_version,
                payload
            ) VALUES (
                :event_id,
                :world_id,
                :request_id,
                'COMMAND_RESULT',
                :payload_schema_version,
                :result_payload
            )
            ON CONFLICT (world_id, event_id) DO NOTHING
            """.trimIndent(),
            params,
        )
        val updated = jdbc.update(
            """
            UPDATE command_inbox
               SET status = :terminal_status,
                   updated_at = now()
             WHERE world_id = :world_id AND request_id = :request_id
               AND status IN (:expected_statuses)
            """.trimIndent(),
            params,
        )
        check(updated == 1) {
            "command_inbox terminal transition requestId=${row.requestId} affected $updated rows; expected $expectedInboxStatuses"
        }
    }

    private fun jsonb(json: String): PGobject {
        val obj = PGobject()
        obj.type = "jsonb"
        obj.value = json
        return obj
    }
}
