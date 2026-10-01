package opensamguk.infra.persistence

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import java.security.MessageDigest
import java.sql.Timestamp
import java.time.Instant
import opensamguk.infra.battle.realtime.FrozenBattleParticipant
import opensamguk.infra.battle.realtime.FrozenBattleTicket
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.transaction.support.TransactionSynchronizationManager

data class CampaignBattleLockRow(val entityKey: String, val expectedRevision: Long)

/** The caller records this as one typed flush delta; all locks and the handoff commit together. */
data class CampaignBattleHandoffRow(
    val causeEventId: String,
    val ticket: FrozenBattleTicket,
    val locks: List<CampaignBattleLockRow>,
)

/** Invoked only inside the existing campaign JDBC flush transaction; never starts its own transaction. */
class JdbcCampaignBattleHandoffWriter(private val db: NamedParameterJdbcTemplate) {
    private val parser = ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)

    fun writeWithinFlush(row: CampaignBattleHandoffRow): Boolean {
        check(TransactionSynchronizationManager.isActualTransactionActive()) {
            "campaign battle handoff requires the world flush transaction"
        }
        val ticket = row.ticket
        require(ticket.lockGeneration > 0 && ticket.lockSetRevision > 0)
        require(row.causeEventId.isNotBlank() && row.causeEventId == row.causeEventId.trim() &&
            row.causeEventId.length <= 128)
        require(sha(ticket.payloadJson) == ticket.payloadSha256) { "campaign handoff checksum mismatch" }
        val root = parser.readTree(ticket.payloadJson)
        require(root != null && root.isObject && root.path("causeEventId").isTextual &&
            root.path("causeEventId").textValue() == row.causeEventId)
        require(root.path("schemaVersion").intValue() == 1 &&
            root.path("worldId").canConvertToInt() &&
            root.path("worldId").intValue() == ticket.worldId.value &&
            root.path("battleId").textValue() == ticket.battleId &&
            root.path("ruleSha256").textValue() == ticket.ruleSha256 &&
            root.path("catalogSha256").textValue() == ticket.catalogSha256 &&
            root.path("terrainSha256").textValue() == ticket.terrainSha256 &&
            root.path("seed").canConvertToLong() && root.path("seed").longValue() == ticket.seed &&
            root.path("lockGeneration").canConvertToLong() &&
            root.path("lockGeneration").longValue() == ticket.lockGeneration &&
            root.path("lockSetRevision").canConvertToLong() &&
            root.path("lockSetRevision").longValue() == ticket.lockSetRevision &&
            root.path("joinDeadlineAt").isTextual &&
            Instant.parse(root.path("joinDeadlineAt").textValue()) == ticket.joinDeadlineAt &&
            root.path("deadlineAt").isTextual &&
            Instant.parse(root.path("deadlineAt").textValue()) == ticket.deadlineAt) {
            "campaign handoff columns differ from frozen ticket"
        }
        val frozenParticipants = root.path("participants")
        require(frozenParticipants.isArray)
        val participants = frozenParticipants.map { value ->
            require(value.isObject && value.fieldNames().asSequence().toSet() ==
                setOf("participantId", "accountId", "generalId", "side", "authorityRevision"))
            require(listOf("participantId", "accountId", "generalId").all {
                value.path(it).canConvertToInt()
            } && value.path("authorityRevision").canConvertToLong() && value.path("side").isTextual)
            FrozenBattleParticipant(value.path("participantId").intValue(),
                value.path("accountId").intValue(), value.path("generalId").intValue(),
                value.path("side").textValue(), value.path("authorityRevision").longValue())
        }
        require(participants == ticket.participants &&
            root.path("pacingMode").textValue() ==
                (if (participants.isEmpty()) "ACCELERATED_NPC" else "REALTIME")) {
            "campaign handoff participants or pacing differ from frozen ticket"
        }
        val revisions = root.path("entityRevisions")
        require(revisions.isObject && revisions.size() > 0)
        val frozenRevisions = revisions.fields().asSequence().associate { (key, value) ->
            require(key.isNotBlank() && value.isIntegralNumber && value.canConvertToLong())
            key to value.longValue()
        }
        require(row.locks.isNotEmpty() && row.locks.map { it.entityKey }.distinct().size == row.locks.size)
        require(row.locks.all { it.entityKey.isNotBlank() && it.entityKey.length <= 160 &&
            it.expectedRevision >= 0 })
        require(row.locks.associate { it.entityKey to it.expectedRevision } == frozenRevisions) {
            "campaign handoff lock set differs from frozen entity revisions"
        }

        val params = MapSqlParameterSource().addValue("world_id", ticket.worldId.value)
            .addValue("battle_id", ticket.battleId)
            .addValue("cause_event_id", row.causeEventId)
            .addValue("payload", ticket.payloadJson)
            .addValue("payload_sha", ticket.payloadSha256)
            .addValue("rule_sha", ticket.ruleSha256)
            .addValue("catalog_sha", ticket.catalogSha256)
            .addValue("terrain_sha", ticket.terrainSha256)
            .addValue("seed", ticket.seed)
            .addValue("lock_generation", ticket.lockGeneration)
            .addValue("lock_set_revision", ticket.lockSetRevision)
            .addValue("join_deadline", Timestamp.from(ticket.joinDeadlineAt))
            .addValue("deadline", Timestamp.from(ticket.deadlineAt))
        val inserted = db.update("""
            INSERT INTO campaign_battle_handoff (world_id, battle_id, cause_event_id, payload_text,
                payload_sha256, rule_sha256, catalog_sha256, terrain_sha256, seed,
                lock_generation, lock_set_revision, join_deadline_at, deadline_at)
            VALUES (:world_id, :battle_id, :cause_event_id, :payload, :payload_sha,
                :rule_sha, :catalog_sha, :terrain_sha, :seed, :lock_generation,
                :lock_set_revision, :join_deadline, :deadline)
            ON CONFLICT DO NOTHING
        """.trimIndent(), params)
        if (inserted == 1) {
            for (lock in row.locks.sortedBy { it.entityKey }) {
                db.update("""
                    INSERT INTO campaign_battle_lock (world_id, battle_id, entity_key,
                        expected_revision, lock_generation, lock_set_revision)
                    VALUES (:world_id, :battle_id, :entity_key,
                        :expected_revision, :lock_generation, :lock_set_revision)
                """.trimIndent(), params.addValue("entity_key", lock.entityKey)
                    .addValue("expected_revision", lock.expectedRevision))
            }
            return true
        }
        val existing = db.query("""
            SELECT cause_event_id, payload_text, payload_sha256, rule_sha256, catalog_sha256,
                   terrain_sha256, seed, lock_generation, lock_set_revision,
                   join_deadline_at, deadline_at
              FROM campaign_battle_handoff
             WHERE world_id = :world_id AND battle_id = :battle_id
        """.trimIndent(), params) { rs, _ -> ExistingHandoff(
            rs.getString("cause_event_id"), rs.getString("payload_text"), rs.getString("payload_sha256"),
            rs.getString("rule_sha256"), rs.getString("catalog_sha256"), rs.getString("terrain_sha256"),
            rs.getLong("seed"), rs.getLong("lock_generation"), rs.getLong("lock_set_revision"),
            rs.getTimestamp("join_deadline_at").toInstant().toEpochMilli(),
            rs.getTimestamp("deadline_at").toInstant().toEpochMilli()) }.singleOrNull()
        check(existing == ExistingHandoff(row.causeEventId, ticket.payloadJson, ticket.payloadSha256,
            ticket.ruleSha256, ticket.catalogSha256, ticket.terrainSha256, ticket.seed,
            ticket.lockGeneration, ticket.lockSetRevision, ticket.joinDeadlineAt.toEpochMilli(),
            ticket.deadlineAt.toEpochMilli())) { "campaign battle cause or handoff identity conflict" }
        val existingLocks = db.query("""
            SELECT entity_key, expected_revision, lock_generation, lock_set_revision
              FROM campaign_battle_lock WHERE world_id = :world_id AND battle_id = :battle_id
             ORDER BY entity_key
        """.trimIndent(), params) { rs, _ -> StoredLock(rs.getString("entity_key"),
            rs.getLong("expected_revision"), rs.getLong("lock_generation"),
            rs.getLong("lock_set_revision")) }
        val expectedLocks = row.locks.sortedBy { it.entityKey }.map {
            StoredLock(it.entityKey, it.expectedRevision, ticket.lockGeneration, ticket.lockSetRevision)
        }
        check(existingLocks == expectedLocks) { "campaign battle lock identity conflict" }
        return false
    }

    private data class ExistingHandoff(val causeEventId: String, val payload: String, val payloadSha: String,
                                       val ruleSha: String, val catalogSha: String, val terrainSha: String,
                                       val seed: Long, val generation: Long, val setRevision: Long,
                                       val joinMillis: Long, val deadlineMillis: Long)
    private data class StoredLock(val key: String, val revision: Long, val generation: Long,
                                  val setRevision: Long)

    private fun sha(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
