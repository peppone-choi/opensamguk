package opensamguk.gameapi.battle.realtime

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import opensamguk.gameapi.owner.GeneralResolver
import opensamguk.infra.battle.realtime.BattleCommandReceipt
import opensamguk.infra.battle.realtime.BattleSessionHead
import opensamguk.infra.battle.realtime.BattleSessionStore
import opensamguk.infra.battle.realtime.CommandAdmission
import opensamguk.logic.battle.realtime.BattleOrder
import opensamguk.logic.battle.realtime.BattleSide
import opensamguk.logic.battle.realtime.RallyPoint
import opensamguk.logic.battle.realtime.TacticalState
import opensamguk.logic.battle.realtime.TacticalStateCodec

data class BattleSocketField(val boardId: Int, val kind: String, val rows: List<String>,
                             val terrainInputSha256: String)
data class BattleSocketUnit(val side: String, val slot: String, val retinueId: Int,
                            val generalId: Int, val kind: String, val row: Int, val col: Int,
                            val troops: Int, val morale: Int, val order: String, val rally: String)
data class BattleSocketSnapshot(val schemaVersion: Int = 1, val t: String = "SNAPSHOT",
                                val worldId: Int, val battleId: String, val sessionEpoch: Long,
                                val tick: Int, val eventSeq: Long, val pacingMode: String = "REALTIME",
                                val joinDeadlineAt: java.time.Instant, val authorityRevision: Long,
                                val side: String, val field: BattleSocketField,
                                val units: List<BattleSocketUnit>)
data class BattleSocketAck(val schemaVersion: Int = 1, val t: String = "ACK",
                           val battleId: String, val sessionEpoch: Long, val clientCommandId: String,
                           val result: String, val serverTick: Int, val acceptedTick: Int?,
                           val effectiveTick: Int?,
                           val eventSeq: Long?, val reasonCode: String?,
                           val currentAuthorityRevision: Long, val replayed: Boolean)

/** Authenticated socket projection and narrow one-retinue COMMAND admission. */
class BattleWebSocketProtocol(
    private val tickets: BattleJoinTicketService,
    private val generals: GeneralResolver,
    private val store: BattleSessionStore,
    private val frozen: BattleFrozenInputCodec,
    private val coordinator: BattleSessionCoordinator,
    private val mapper: ObjectMapper,
) {
    private data class View(val head: BattleSessionHead, val state: TacticalState,
                            val consumedEventSeq: Long, val terrainSha256: String)

    fun snapshot(identity: BattleJoinIdentity): String {
        checkCurrent(identity)
        val view = readView(identity)
        val state = view.state
        val side = BattleSide.valueOf(identity.side)
        val units = state.units.filter { it.side == side }.map { unit ->
            BattleSocketUnit(unit.side.name, unit.slot.name, unit.retinue.id, unit.retinue.general.id,
                unit.retinue.kind.name, unit.row, unit.col, unit.troops, unit.morale,
                unit.order.name, unit.rally.name)
        }
        return mapper.writeValueAsString(BattleSocketSnapshot(
            worldId = identity.worldId.value, battleId = identity.battleId,
            sessionEpoch = view.head.sessionEpoch, tick = state.tick,
            eventSeq = view.consumedEventSeq, joinDeadlineAt = view.head.joinDeadlineAt,
            authorityRevision = identity.authorityRevision, side = identity.side,
            field = BattleSocketField(state.battlefield.id, state.battlefield.kind,
                state.battlefield.rows, view.terrainSha256), units = units))
    }

    fun command(identity: BattleJoinIdentity, text: String): String {
        checkCurrent(identity)
        val root = Json.parseToJsonElement(text).jsonObject
        require(root.keys == setOf("schemaVersion", "t", "clientCommandId", "expectedEpoch",
            "expectedAuthorityRevision", "issuedTick", "scope", "intentType", "intentPayload"))
        require(root.number("schemaVersion") == 1 && root.string("t") == "COMMAND")
        val clientId = root.string("clientCommandId")
        require(clientId.isNotBlank() && clientId.length <= 128)
        val scope = root.getValue("scope").jsonObject
        require(scope.keys == setOf("retinueId")) { "only retinue scope is supported" }
        val retinueId = scope.number("retinueId").also { require(it > 0) }
        val order = BattleOrder.valueOf(root.string("intentType"))
        val intentPayload = root.getValue("intentPayload").jsonObject
        require(intentPayload.keys == setOf("rally"))
        val rally = RallyPoint.valueOf(intentPayload.string("rally"))
        val epoch = root.long("expectedEpoch")
        val revision = root.long("expectedAuthorityRevision")
        val issuedTick = root.number("issuedTick")
        require(epoch >= 0 && revision >= 0 && issuedTick >= 0)
        val view = readView(identity)
        val side = BattleSide.valueOf(identity.side)
        val target = view.state.units.singleOrNull { it.retinue.id == retinueId }
        val authorized = target != null && target.side == side &&
            target.retinue.general.id == identity.generalId && side in view.state.humanSides
        val input = BattleCommandInput(identity.worldId, identity.battleId, identity.accountId,
            identity.participantId, clientId, epoch, revision, issuedTick, side,
            if (authorized) target!!.slot else null, order, rally,
            retinueId = retinueId, preflightReasonCode = if (authorized) null else "UNAUTHORIZED",
            mappedAtTick = view.head.currentTick, mappedAtEventSeq = view.head.latestEventSeq)
        val admission = coordinator.submit(input)
        val receipt = when (admission) {
            is CommandAdmission.Receipt -> admission.value
            CommandAdmission.IdempotencyConflict -> BattleCommandReceipt(clientId,
                opensamguk.infra.battle.realtime.BattleCommandVerdict.REJECTED,
                "IDEMPOTENCY_CONFLICT", view.head.currentTick, null, null, revision)
        }
        return mapper.writeValueAsString(BattleSocketAck(
            battleId = identity.battleId, sessionEpoch = identity.sessionEpoch,
            clientCommandId = receipt.clientCommandId, result = receipt.verdict.name,
            serverTick = receipt.serverTick,
            acceptedTick = if (receipt.verdict == opensamguk.infra.battle.realtime.BattleCommandVerdict.ACCEPTED)
                receipt.serverTick else null,
            effectiveTick = receipt.effectiveTick,
            eventSeq = receipt.eventSeq, reasonCode = receipt.reasonCode,
            currentAuthorityRevision = receipt.authorityRevision, replayed = receipt.replayed))
    }

    private fun checkCurrent(identity: BattleJoinIdentity) {
        if (!tickets.isCurrent(identity) ||
            generals.resolveGeneralId(identity.accountId.toLong()) != identity.generalId)
            throw SecurityException("battle socket authority expired")
    }

    private fun readView(identity: BattleJoinIdentity): View {
        val ticket = requireNotNull(store.ticket(identity.worldId, identity.battleId))
        require(ticket.participants.singleOrNull { it.participantId == identity.participantId &&
            it.accountId == identity.accountId && it.generalId == identity.generalId &&
            it.side == identity.side && it.authorityRevision == identity.authorityRevision } != null)
        val initial = frozen.initialState(ticket)
        repeat(3) {
            val head = requireNotNull(store.head(identity.worldId, identity.battleId))
            require(head.sessionEpoch == identity.sessionEpoch)
            val checkpoint = store.latestCheckpoint(identity.worldId, identity.battleId)
            if (checkpoint != null &&
                (checkpoint.tick > head.currentTick || checkpoint.eventSeq > head.latestEventSeq)) return@repeat
            val base = if (checkpoint == null) initial else
                TacticalStateCodec.decode(checkpoint.compressedState, checkpoint.stateHash).also { state ->
                    require(state.tick == checkpoint.tick && state.seed == initial.seed &&
                        state.battlefield == initial.battlefield &&
                        state.gateRow == initial.gateRow && state.gateCol == initial.gateCol &&
                        state.units.map { it.side to it.retinue }.sortedBy { it.second.id } ==
                        initial.units.map { it.side to it.retinue }.sortedBy { it.second.id })
                }
            val after = checkpoint?.eventSeq ?: 0L
            val events = store.eventsAfter(identity.worldId, identity.battleId, after)
                .filter { it.eventSeq <= head.latestEventSeq }
            val state = BattleEventTimeline.replay(base, after, events, head.currentTick)
            val stable = store.head(identity.worldId, identity.battleId)
            if (stable != null && stable.sessionEpoch == head.sessionEpoch &&
                stable.currentTick == head.currentTick && stable.latestEventSeq == head.latestEventSeq) {
                return View(head, state.state, state.consumedEventSeq, ticket.terrainSha256)
            }
        }
        error("battle state changed during projection")
    }

    private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.also {
        require(it.isString)
    }.content
    private fun JsonObject.number(key: String): Int = getValue(key).jsonPrimitive.also {
        require(!it.isString)
    }.int
    private fun JsonObject.long(key: String): Long = getValue(key).jsonPrimitive.also {
        require(!it.isString)
    }.content.toLong()
}
