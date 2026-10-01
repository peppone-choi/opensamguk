package opensamguk.gameapi.battle.realtime

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import java.security.MessageDigest
import java.time.Instant
import opensamguk.common.world.WorldId
import opensamguk.infra.battle.realtime.FrozenBattleParticipant
import opensamguk.infra.battle.realtime.FrozenBattleTicket
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate

/** A row is visible to this reader only after the campaign transaction commits. */
data class CommittedBattleHandoff(
    val worldId: WorldId,
    val battleId: String,
    val causeEventId: String,
    val payloadJson: String,
    val payloadSha256: String,
    val ruleSha256: String,
    val catalogSha256: String,
    val terrainSha256: String,
    val seed: Long,
    val lockGeneration: Long,
    val lockSetRevision: Long,
    val joinDeadlineAt: Instant,
    val deadlineAt: Instant,
)

interface CommittedBattleHandoffReader {
    fun withoutTicket(worldId: WorldId, limit: Int): List<CommittedBattleHandoff>
}

/** A left anti-join makes battle_ticket itself the durable consumption receipt. */
class JdbcCommittedBattleHandoffReader(private val db: NamedParameterJdbcTemplate) : CommittedBattleHandoffReader {
    override fun withoutTicket(worldId: WorldId, limit: Int): List<CommittedBattleHandoff> {
        require(limit in 1..1000)
        return db.query("""
            SELECT handoff.battle_id, handoff.cause_event_id, handoff.payload_text,
                   handoff.payload_sha256, handoff.rule_sha256, handoff.catalog_sha256,
                   handoff.terrain_sha256, handoff.seed, handoff.lock_generation,
                   handoff.lock_set_revision, handoff.join_deadline_at, handoff.deadline_at
              FROM campaign_battle_handoff AS handoff
             WHERE handoff.world_id = :world_id
               AND NOT EXISTS (
                   SELECT 1 FROM battle_ticket AS ticket
                    WHERE ticket.world_id = handoff.world_id AND ticket.battle_id = handoff.battle_id)
               AND NOT EXISTS (
                   SELECT 1 FROM battle_handoff_rejection AS rejection
                    WHERE rejection.world_id = handoff.world_id AND rejection.battle_id = handoff.battle_id)
             ORDER BY handoff.created_at, handoff.battle_id
             LIMIT :limit
        """.trimIndent(), MapSqlParameterSource().addValue("world_id", worldId.value).addValue("limit", limit)) { rs, _ ->
            CommittedBattleHandoff(worldId, rs.getString("battle_id"), rs.getString("cause_event_id"),
                rs.getString("payload_text"), rs.getString("payload_sha256"), rs.getString("rule_sha256"),
                rs.getString("catalog_sha256"), rs.getString("terrain_sha256"), rs.getLong("seed"),
                rs.getLong("lock_generation"), rs.getLong("lock_set_revision"),
                rs.getTimestamp("join_deadline_at").toInstant(), rs.getTimestamp("deadline_at").toInstant())
        }
    }
}

fun interface BattleHandoffRejectionWriter {
    fun record(handoff: CommittedBattleHandoff, reasonCode: String)
}

/** Battle-owned evidence of a permanently invalid committed handoff. */
class JdbcBattleHandoffRejectionWriter(private val db: NamedParameterJdbcTemplate) : BattleHandoffRejectionWriter {
    override fun record(handoff: CommittedBattleHandoff, reasonCode: String) {
        require(reasonCode in REASONS)
        val params = MapSqlParameterSource().addValue("world_id", handoff.worldId.value)
            .addValue("battle_id", handoff.battleId)
            .addValue("handoff_sha", handoff.payloadSha256)
            .addValue("reason_code", reasonCode)
        val inserted = db.update("""
            INSERT INTO battle_handoff_rejection (world_id, battle_id, handoff_sha256, reason_code)
            VALUES (:world_id, :battle_id, :handoff_sha, :reason_code)
            ON CONFLICT (world_id, battle_id) DO NOTHING
        """.trimIndent(), params)
        if (inserted == 0) {
            val previous = db.query("""
                SELECT handoff_sha256, reason_code FROM battle_handoff_rejection
                 WHERE world_id = :world_id AND battle_id = :battle_id
            """.trimIndent(), params) { rs, _ -> rs.getString("handoff_sha256") to rs.getString("reason_code") }
                .singleOrNull()
            check(previous == (handoff.payloadSha256 to reasonCode)) {
                "battle handoff rejection identity conflict"
            }
        }
    }

    companion object {
        val REASONS = setOf("HASH_MISMATCH", "INVALID_SCHEMA", "INVALID_TICKET", "TICKET_CONFLICT")
    }
}

data class BattleHandoffIntakeResult(val scanned: Int, val created: Int, val alreadyCreated: Int,
                                     val rejected: Int)

/** This scan never writes campaign state and can be retried after any crash. */
class BattleHandoffIntake(
    private val reader: CommittedBattleHandoffReader,
    private val rejections: BattleHandoffRejectionWriter,
    private val openTicket: (FrozenBattleTicket) -> Boolean,
) {
    constructor(reader: CommittedBattleHandoffReader, coordinator: BattleSessionCoordinator,
                rejections: BattleHandoffRejectionWriter) : this(reader, rejections, coordinator::open)

    private val parser = ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)

    fun scan(worldId: WorldId, limit: Int = 100): BattleHandoffIntakeResult {
        var created = 0
        var alreadyCreated = 0
        var rejected = 0
        val rows = reader.withoutTicket(worldId, limit)
        for (row in rows) {
            require(row.worldId == worldId) { "cross-world campaign handoff" }
            val ticket = try { decode(row) } catch (invalid: InvalidHandoff) {
                rejections.record(row, invalid.reasonCode)
                rejected++
                continue
            }
            try {
                if (openTicket(ticket)) created++ else alreadyCreated++
            } catch (_: IllegalArgumentException) {
                rejections.record(row, "INVALID_TICKET")
                rejected++
            } catch (conflict: IllegalStateException) {
                if (conflict.message != "battle handoff identity conflict") throw conflict
                rejections.record(row, "TICKET_CONFLICT")
                rejected++
            }
        }
        return BattleHandoffIntakeResult(rows.size, created, alreadyCreated, rejected)
    }

    private fun decode(row: CommittedBattleHandoff): FrozenBattleTicket {
        if (sha(row.payloadJson) != row.payloadSha256) throw InvalidHandoff("HASH_MISMATCH")
        try {
            val root = parser.readTree(row.payloadJson)
            require(root != null && root.isObject)
            require(root.path("causeEventId").isTextual &&
                root.path("causeEventId").textValue() == row.causeEventId)
            val raw = root.path("participants")
            require(raw.isArray)
            val participants = raw.map { item ->
                require(item.isObject && item.fieldNames().asSequence().toSet() ==
                    setOf("participantId", "accountId", "generalId", "side", "authorityRevision"))
                fun positiveInt(key: String): Int = item.path(key).let { value ->
                    require(value.isIntegralNumber && value.canConvertToInt())
                    value.intValue()
                }
                val revision = item.path("authorityRevision").also {
                    require(it.isIntegralNumber && it.canConvertToLong())
                }.longValue()
                require(item.path("side").isTextual)
                FrozenBattleParticipant(positiveInt("participantId"), positiveInt("accountId"),
                    positiveInt("generalId"), item.path("side").textValue(), revision)
            }
            require(participants == participants.sortedBy { it.participantId })
            val pacingMode = if (participants.isEmpty()) "ACCELERATED_NPC" else "REALTIME"
            require(root.path("pacingMode").isTextual && root.path("pacingMode").textValue() == pacingMode)
            return FrozenBattleTicket(row.worldId, row.battleId, row.payloadJson, row.payloadSha256,
                row.ruleSha256, row.catalogSha256, row.terrainSha256, row.seed,
                row.lockGeneration, row.lockSetRevision, row.joinDeadlineAt, row.deadlineAt, participants)
        } catch (_: Exception) {
            throw InvalidHandoff("INVALID_SCHEMA")
        }
    }

    private class InvalidHandoff(val reasonCode: String) : IllegalArgumentException(reasonCode)

    private fun sha(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
