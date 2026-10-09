package opensamguk.gameapi.reserve

import com.fasterxml.jackson.databind.ObjectMapper
import opensamguk.common.wire.CommandLifecycleResult
import opensamguk.common.wire.TurnDaemonEvent
import opensamguk.common.wire.TurnDaemonEventEnvelope
import opensamguk.common.wire.WireJson
import opensamguk.gameapi.config.GameApiProcessWorld
import opensamguk.infra.persistence.CommandInboxRepository
import opensamguk.infra.persistence.CommandResultRepository
import opensamguk.infra.persistence.CommandResultRow
import opensamguk.infra.persistence.ReservationExecutionFence
import opensamguk.logic.input.RuleProfile
import opensamguk.logic.input.WorldRuleProfile
import opensamguk.logic.world.WorldFormat
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.time.Instant

@Repository
class ReservationCancelRepository(
    private val jdbc: NamedParameterJdbcTemplate,
    private val inbox: CommandInboxRepository,
    private val results: CommandResultRepository,
    private val mapper: ObjectMapper,
    processWorld: GameApiProcessWorld,
) {
    private val world = processWorld.worldId
    private val fence = ReservationExecutionFence(jdbc)

    fun receipt(key: String, owner: Int, fingerprint: String): ReservationCancelDto? = jdbc.query(
        "SELECT i.owner_user_id, i.command_kind, i.intent_fingerprint, i.action_code, r.result_payload::text " +
            "FROM command_inbox i LEFT JOIN command_result r ON r.world_id=i.world_id " +
            "AND r.request_id=i.request_id AND r.result_seq=1 WHERE i.world_id=:world AND i.request_id=:key",
        mapOf("world" to world.value, "key" to key),
    ) { rs, _ ->
        if (rs.getObject("owner_user_id") != owner || rs.getString("command_kind") != "QUEUE_MUTATION" ||
            rs.getString("action_code") != OPERATION || rs.getString("intent_fingerprint") != fingerprint) {
            throw ReservationCancelRejected("IDEMPOTENCY_CONFLICT")
        }
        val json = rs.getString("result_payload") ?: throw ReservationCancelRejected("RECEIPT_UNAVAILABLE", 503, true)
        val envelope = WireJson.decodeFromString(TurnDaemonEventEnvelope.serializer(), json)
        val result = (envelope.event as? TurnDaemonEvent.CommandResult)?.result as? CommandLifecycleResult
            ?: throw ReservationCancelRejected("RECEIPT_UNAVAILABLE", 503, true)
        check(result.type == "reservationCancelled" && result.ok && result.slotEmpty == true)
        ReservationCancelDto(key, result, requireNotNull(envelope.committedWorldVersion))
    }.singleOrNull()

    fun cancel(key: String, owner: Int, actor: Int, slot: Int, revision: String, fingerprint: String): ReservationCancelDto =
        requireNotNull(fence.transactions.execute {
            if (!fence.tryCancellation(world)) throw ReservationCancelRejected("WORLD_EXECUTING", retryable = true)
            val params = mapOf("world" to world.value, "actor" to actor, "slot" to slot, "revision" to revision)
            val body = jdbc.queryForList("SELECT user_id,npc_state,meta::text FROM general " +
                "WHERE world_id=:world AND id=:actor FOR UPDATE", params).singleOrNull()
                ?: throw ReservationCancelRejected("NOT_OWNER", 403)
            // Duplicate success remains readable even after the actor's ownership has changed.
            receipt(key, owner, fingerprint)?.let { return@execute it }
            if (body["user_id"]?.toString()?.toIntOrNull() != owner || (body["npc_state"] as Number).toInt() !in 0..1 ||
                mapper.readTree(body["meta"].toString())["retired"]?.asBoolean() == true) {
                throw ReservationCancelRejected("NOT_OWNER", 403)
            }
            val state = jdbc.queryForList("SELECT config::text,meta::text,world_version FROM world_state WHERE id=:world", params)
                .singleOrNull() ?: throw ReservationCancelRejected("POLICY_UNAVAILABLE", 503)
            val config = mapper.readValue(state["config"].toString(), Map::class.java)
                .entries.associate { it.key.toString() to it.value }
            val profile = runCatching {
                val meta = mapper.readValue(state["meta"].toString(), Map::class.java)
                    .entries.associate { it.key.toString() to it.value }
                WorldFormat.require(config, meta)
                WorldRuleProfile.resolve(config)
            }.getOrNull()
                ?: throw ReservationCancelRejected("POLICY_UNAVAILABLE", 503)
            val maximum = if (profile == RuleProfile.HWIHA) 12 else 30
            if (slot !in 0 until maximum) throw ReservationCancelRejected("INVALID_SLOT", 400)
            val payload = mapper.writeValueAsString(mapOf("operation" to OPERATION, "generalId" to actor,
                "turnIdx" to slot, "reservationRevision" to revision))
            val inserted = inbox.insertAccepted(CommandInboxRepository.AcceptedCommand(world, key,
                commandKind = CommandInboxRepository.CommandKind.QUEUE_MUTATION, intentFingerprint = fingerprint,
                generalId = actor, turnIdx = slot, actionCode = OPERATION, payloadJson = payload, ownerUserId = owner))
            if (inserted != CommandInboxRepository.InsertResult.Inserted) {
                return@execute receipt(key, owner, fingerprint) ?: throw ReservationCancelRejected("IDEMPOTENCY_CONFLICT")
            }
            val deleted = jdbc.queryForList("DELETE FROM general_turn WHERE world_id=:world AND general_id=:actor " +
                "AND turn_idx=:slot AND reservation_revision=CAST(:revision AS uuid) RETURNING reservation_revision::text", params)
            if (deleted.size != 1) throw ReservationCancelRejected("REVISION_MISMATCH")
            check(jdbc.queryForList("SELECT id FROM general_turn WHERE world_id=:world AND general_id=:actor AND turn_idx=:slot",
                params).isEmpty()) { "Cancelled slot must be absent before commit" }
            val version = (state["world_version"] as Number).toLong()
            persistReceipt(key, actor, slot, revision, version)
            requireNotNull(receipt(key, owner, fingerprint))
        })

    private fun persistReceipt(key: String, actor: Int, slot: Int, revision: String, version: Long) {
        val sentAt = Instant.now()
        val result = CommandLifecycleResult("reservationCancelled", true, "QUEUE_MUTATION", OPERATION,
            actor, slot, reservationRevision = revision, slotEmpty = true)
        val envelope = TurnDaemonEventEnvelope(key, sentAt.toString(), TurnDaemonEvent.CommandResult(result),
            committedWorldVersion = version)
        results.insertTerminalResult(world, CommandResultRow(requestId = key, eventId = "command-result:${world.value}:$key:1",
            resultType = result.type, ok = true, committedWorldVersion = version, payloadSchemaVersion = 1,
            envelopeJson = WireJson.encodeToString(TurnDaemonEventEnvelope.serializer(), envelope), sentAt = sentAt),
            expectedInboxStatuses = setOf("ACCEPTED"))
    }

    companion object { const val OPERATION = "cancelReservedTurn" }
}
