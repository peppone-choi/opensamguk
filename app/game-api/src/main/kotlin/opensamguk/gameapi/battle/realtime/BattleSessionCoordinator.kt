package opensamguk.gameapi.battle.realtime

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import opensamguk.common.world.WorldId
import opensamguk.infra.battle.realtime.BattleCheckpoint
import opensamguk.infra.battle.realtime.BattleCommandRecord
import opensamguk.infra.battle.realtime.BattleEventRecord
import opensamguk.infra.battle.realtime.BattleResultRecord
import opensamguk.infra.battle.realtime.BattleSessionStore
import opensamguk.infra.battle.realtime.CommandAdmission
import opensamguk.infra.battle.realtime.FrozenBattleParticipant
import opensamguk.infra.battle.realtime.FrozenBattleTicket
import opensamguk.logic.battle.realtime.BattleOrder
import opensamguk.logic.battle.realtime.BattleSide
import opensamguk.logic.battle.realtime.FormationSlot
import opensamguk.logic.battle.realtime.RallyPoint

data class BattleCommandInput(
    val worldId: WorldId,
    val battleId: String,
    val accountId: Int,
    val participantId: Int,
    val clientCommandId: String,
    val expectedEpoch: Long,
    val expectedAuthorityRevision: Long,
    val issuedTick: Int,
    val side: BattleSide,
    val slot: FormationSlot?,
    val order: BattleOrder,
    val rally: RallyPoint,
)

data class BattleRecovery(
    val ticket: FrozenBattleTicket,
    val checkpoint: BattleCheckpoint?,
    val events: List<BattleEventRecord>,
)

/** Authenticated caller identity enters here; only the store can grant a durable command ACK. */
class BattleSessionCoordinator(private val store: BattleSessionStore) {
    fun open(committedHandoff: FrozenBattleTicket): Boolean {
        validateTicket(committedHandoff)
        return store.create(committedHandoff)
    }

    fun submit(input: BattleCommandInput): CommandAdmission {
        val ticket = requireNotNull(store.ticket(input.worldId, input.battleId)) { "battle not found" }
        val participant = ticket.participants.singleOrNull { it.participantId == input.participantId }
            ?: throw SecurityException("battle participant not found")
        if (participant.accountId != input.accountId || participant.side != input.side.name)
            throw SecurityException("battle participant authority mismatch")
        val intent = buildJsonObject {
            put("schemaVersion", 1)
            put("side", input.side.name)
            put("slot", input.slot?.name)
            put("order", input.order.name)
            put("rally", input.rally.name)
        }.toString()
        return store.admit(BattleCommandRecord(
            worldId = input.worldId, battleId = input.battleId, participantId = input.participantId,
            clientCommandId = input.clientCommandId, intentSha256 = sha256(intent),
            expectedEpoch = input.expectedEpoch,
            expectedAuthorityRevision = input.expectedAuthorityRevision,
            issuedTick = input.issuedTick, side = input.side.name, intentJson = intent,
        ))
    }

    fun recover(worldId: WorldId, battleId: String): BattleRecovery {
        val ticket = requireNotNull(store.ticket(worldId, battleId)) { "battle not found" }
        validateTicket(ticket)
        val checkpoint = store.latestCheckpoint(worldId, battleId)
        val events = store.eventsAfter(worldId, battleId, checkpoint?.eventSeq ?: 0)
        require(events.all { sha256(it.payloadJson) == it.payloadSha256 }) { "battle event checksum mismatch" }
        return BattleRecovery(ticket, checkpoint, events)
    }

    fun checkpoint(value: BattleCheckpoint): Boolean = store.checkpoint(value)
    fun publishResult(value: BattleResultRecord): Boolean = store.publishResult(value)

    private fun validateTicket(ticket: FrozenBattleTicket) {
        require(sha256(ticket.payloadJson) == ticket.payloadSha256) { "handoff payload hash mismatch" }
        val root = Json.parseToJsonElement(ticket.payloadJson).jsonObject
        require(root.int("schemaVersion") == 1)
        require(root.string("battleId") == ticket.battleId)
        require(root.int("worldId") == ticket.worldId.value)
        require(root.string("ruleSha256") == ticket.ruleSha256)
        require(root.string("catalogSha256") == ticket.catalogSha256)
        require(root.string("terrainSha256") == ticket.terrainSha256)
        require(root.getValue("seed").jsonPrimitive.content.toLong() == ticket.seed)
        require(root.getValue("lockGeneration").jsonPrimitive.content.toLong() == ticket.lockGeneration)
        require(root.getValue("lockSetRevision").jsonPrimitive.content.toLong() == ticket.lockSetRevision)
        require(Instant.parse(root.string("joinDeadlineAt")).toEpochMilli() == ticket.joinDeadlineAt.toEpochMilli())
        require(Instant.parse(root.string("deadlineAt")).toEpochMilli() == ticket.deadlineAt.toEpochMilli())
        require(root.string("kind") in setOf("ENCOUNTER", "SIEGE", "PERSONAL_DUEL"))
        val participants = root.getValue("participants").jsonArray.map { parseParticipant(it.jsonObject) }
        require(participants == ticket.participants)
        if (root.string("kind") != "PERSONAL_DUEL") {
            require(root.int("battlefieldId") in 0..213)
        }
        require((root.getValue("entityRevisions") as? JsonObject)?.isNotEmpty() == true)
    }

    private fun parseParticipant(value: JsonObject): FrozenBattleParticipant = FrozenBattleParticipant(
        participantId = value.int("participantId"), accountId = value.int("accountId"),
        generalId = value.int("generalId"), side = value.string("side"),
        authorityRevision = value.getValue("authorityRevision").jsonPrimitive.content.toLong(),
    )

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun JsonObject.int(key: String): Int = getValue(key).jsonPrimitive.int
    private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content
}
