package opensamguk.infra.persistence

import opensamguk.common.world.WorldId
import opensamguk.common.wire.TurnDaemonCommandEnvelope
import opensamguk.common.wire.decodeCommandEnvelope
import org.postgresql.util.PGobject
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant

open class CommandInboxRepository(
    private val jdbc: NamedParameterJdbcTemplate,
) {
    enum class CommandKind {
        IMMEDIATE,
        RESERVED_TURN,
        QUEUE_MUTATION,
    }

    data class AcceptedCommand(
        val worldId: WorldId,
        val requestId: String,
        val payloadSchemaVersion: Int = CURRENT_PAYLOAD_SCHEMA_VERSION,
        val commandKind: CommandKind,
        val intentFingerprint: String,
        val generalId: Int?,
        val turnIdx: Int?,
        val actionCode: String?,
        val payloadJson: String,
        /**
         * OPENSAM-197 — 제출 계정(JWT subject). 결과 조회 소유권 검사의 두 근거 중 하나다.
         * 장수선택은 아직 소유하지 않은 장수를 대상으로 내므로 [generalId]만으로는 제출자를 못 가린다.
         */
        val ownerUserId: Int? = null,
    )

    sealed class InsertResult {
        data object Inserted : InsertResult()
        data object ExistingSame : InsertResult()
        data class Conflict(val existingFingerprint: String) : InsertResult()
    }

    data class ClaimedCommand(
        val requestId: String,
        val payloadJson: String,
        val envelope: TurnDaemonCommandEnvelope,
        val commandKind: CommandKind,
        val generalId: Int?,
        val turnIdx: Int?,
        val actionCode: String?,
    )

    open fun insertAccepted(command: AcceptedCommand): InsertResult {
        val updated = jdbc.update(
            """
            INSERT INTO command_inbox (
                world_id,
                request_id,
                payload_schema_version,
                command_kind,
                status,
                intent_fingerprint,
                general_id,
                turn_idx,
                action_code,
                payload,
                owner_user_id
            ) VALUES (
                :world_id,
                :request_id,
                :payload_schema_version,
                :command_kind,
                'ACCEPTED',
                :intent_fingerprint,
                :general_id,
                :turn_idx,
                :action_code,
                :payload,
                :owner_user_id
            )
            ON CONFLICT (world_id, request_id) DO NOTHING
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("world_id", command.worldId.value)
                .addValue("request_id", command.requestId)
                .addValue("payload_schema_version", command.payloadSchemaVersion)
                .addValue("command_kind", command.commandKind.name)
                .addValue("intent_fingerprint", command.intentFingerprint)
                .addValue("general_id", command.generalId)
                .addValue("turn_idx", command.turnIdx)
                .addValue("action_code", command.actionCode)
                .addValue("payload", jsonb(command.payloadJson))
                .addValue("owner_user_id", command.ownerUserId),
        )
        if (updated == 1) return InsertResult.Inserted
        val existing = jdbc.query(
            """
            SELECT intent_fingerprint
              FROM command_inbox
             WHERE world_id = :world_id AND request_id = :request_id
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("world_id", command.worldId.value)
                .addValue("request_id", command.requestId),
        ) { rs, _ -> rs.getString("intent_fingerprint") }
            .single()
        return if (existing == command.intentFingerprint) {
            InsertResult.ExistingSame
        } else {
            InsertResult.Conflict(existing)
        }
    }

    /** OPENSAM-197 — 결과 조회 소유권 검사의 근거. 인테이크가 202 **전에** 기록한 값이다. */
    data class RequestOwner(val generalId: Int?, val ownerUserId: Int?)

    /**
     * 인테이크 원장이 기록한 제출자. 행이 없으면 null — 호출자는 그것을 "확인 불가 = 거절"로 다뤄야
     * 한다. 여기서 결과 payload를 함께 읽지 않는 이유는, 소유권 판단이 결과 존재 여부와 독립이어야
     * 남의 requestId로 존재 여부를 떠보는 것조차 막히기 때문이다.
     */
    open fun findRequestOwner(worldId: WorldId, requestId: String): RequestOwner? =
        jdbc.query(
            """
            SELECT general_id, owner_user_id
              FROM command_inbox
             WHERE world_id = :world_id AND request_id = :request_id
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("world_id", worldId.value)
                .addValue("request_id", requestId),
        ) { rs, _ ->
            RequestOwner(
                generalId = rs.getObject("general_id") as Int?,
                ownerUserId = rs.getObject("owner_user_id") as Int?,
            )
        }.firstOrNull()

    open fun markRedisWakePublished(worldId: WorldId, requestId: String, publishedAt: Instant) {
        val updated = jdbc.update(
            """
            UPDATE command_inbox
               SET redis_wake_published_at = :published_at,
                   updated_at = now()
             WHERE world_id = :world_id AND request_id = :request_id
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("world_id", worldId.value)
                .addValue("request_id", requestId)
                .addValue("published_at", Timestamp.from(publishedAt)),
        )
        require(updated == 1) {
            "Expected exactly one command inbox row to be marked as Redis-wake published, but updated $updated"
        }
    }

    open fun claimForExecution(
        worldId: WorldId,
        requestIds: List<String>,
        now: Instant,
        lease: Duration = DEFAULT_CLAIM_LEASE,
    ): List<ClaimedCommand> {
        if (requestIds.isEmpty()) return emptyList()
        val rows = claimRows(
            worldId = worldId,
            requestIds = requestIds.distinct(),
            now = now,
            lease = lease,
        ).associateBy { it.requestId }
        return requestIds.mapNotNull { rows[it] }
    }

    open fun claimPendingForExecution(
        worldId: WorldId,
        now: Instant,
        limit: Int = DEFAULT_CLAIM_LIMIT,
        lease: Duration = DEFAULT_CLAIM_LEASE,
    ): List<ClaimedCommand> {
        val requestIds = jdbc.query(
            """
            SELECT request_id
              FROM command_inbox
             WHERE world_id = :world_id
               AND command_kind IN ('IMMEDIATE', 'RESERVED_TURN')
               AND (
                    status = 'ACCEPTED'
                    OR (status = 'CLAIMED' AND claim_expires_at <= :now)
               )
             ORDER BY created_at ASC, request_id ASC
             LIMIT :limit
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("world_id", worldId.value)
                .addValue("now", Timestamp.from(now))
                .addValue("limit", limit),
        ) { rs, _ -> rs.getString("request_id") }
        return claimForExecution(worldId, requestIds, now, lease)
    }

    open fun terminalRequestIds(worldId: WorldId, requestIds: List<String>): Set<String> {
        if (requestIds.isEmpty()) return emptySet()
        return jdbc.query(
            """
            SELECT request_id
              FROM command_inbox
             WHERE world_id = :world_id
               AND request_id IN (:request_ids)
               AND status IN ('APPLIED', 'REJECTED')
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("world_id", worldId.value)
                .addValue("request_ids", requestIds),
        ) { rs, _ -> rs.getString("request_id") }.toSet()
    }

    private fun claimRows(
        worldId: WorldId,
        requestIds: List<String>,
        now: Instant,
        lease: Duration,
    ): List<ClaimedCommand> =
        jdbc.query(
            """
            UPDATE command_inbox
               SET status = 'CLAIMED',
                   claimed_at = :now,
                   claim_expires_at = :claim_expires_at,
                   updated_at = now()
             WHERE world_id = :world_id
               AND request_id IN (:request_ids)
               AND (
                    status = 'ACCEPTED'
                    OR (status = 'CLAIMED' AND claim_expires_at <= :now)
               )
             RETURNING request_id, payload::text AS payload, command_kind, general_id, turn_idx, action_code
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("world_id", worldId.value)
                .addValue("request_ids", requestIds)
                .addValue("now", Timestamp.from(now))
                .addValue("claim_expires_at", Timestamp.from(now.plus(lease))),
        ) { rs, _ ->
            val requestId = rs.getString("request_id")
            val payload = rs.getString("payload")
            ClaimedCommand(
                requestId = requestId,
                payloadJson = payload,
                envelope = decodeCommandEnvelope(payload),
                commandKind = CommandKind.valueOf(rs.getString("command_kind")),
                generalId = rs.getObject("general_id") as? Int,
                turnIdx = rs.getObject("turn_idx") as? Int,
                actionCode = rs.getString("action_code"),
            )
        }

    private fun jsonb(json: String): PGobject {
        val obj = PGobject()
        obj.type = "jsonb"
        obj.value = json
        return obj
    }

    companion object {
        const val CURRENT_PAYLOAD_SCHEMA_VERSION = 1
        val DEFAULT_CLAIM_LEASE: Duration = Duration.ofMinutes(5)
        const val DEFAULT_CLAIM_LIMIT: Int = 100
    }
}
