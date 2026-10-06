package opensamguk.gameapi.battle.realtime

import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import opensamguk.infra.battle.realtime.BattleEventRecord
import opensamguk.logic.battle.realtime.BattleOrder
import opensamguk.logic.battle.realtime.BattleSide
import opensamguk.logic.battle.realtime.FormationSlot
import opensamguk.logic.battle.realtime.RallyPoint
import opensamguk.logic.battle.realtime.TacticalBattle
import opensamguk.logic.battle.realtime.TacticalAiOrder
import opensamguk.logic.battle.realtime.TacticalCommand
import opensamguk.logic.battle.realtime.TacticalRules
import opensamguk.logic.battle.realtime.TacticalState

data class BattleTimelineState(val state: TacticalState, val consumedEventSeq: Long) {
    val stateHash: String get() = TacticalBattle.stateHash(state)
}

/** Rebuilds one durable tick at a time from the ordered, checksummed input log. */
object BattleEventTimeline {
    fun replay(initial: TacticalState, afterEventSeq: Long, events: List<BattleEventRecord>,
               durableTick: Int, requireAutomaticOrders: Boolean = false): BattleTimelineState {
        require(afterEventSeq >= 0 && durableTick in initial.tick..TacticalRules.CANON.battleTicks)
        val decoded = events.mapIndexed { index, event ->
            require(event.eventSeq == afterEventSeq + index + 1) { "battle event sequence gap" }
            require(event.sessionEpoch > 0) { "battle event epoch invalid" }
            require(event.tick in 0..TacticalRules.CANON.battleTicks)
            require(event.effectiveTick == event.tick + 1 ||
                (event.tick == 0 && event.effectiveTick == 0)) {
                "battle event cannot apply retroactively at a checkpoint tick"
            }
            require(event.effectiveTick >= initial.tick) { "event predates checkpoint" }
            require(event.effectiveTick <= TacticalRules.CANON.battleTicks)
            require(sha(event.payloadJson) == event.payloadSha256) { "battle event checksum mismatch" }
            Decoded(event, Json.parseToJsonElement(event.payloadJson).jsonObject)
        }
        val consumed = decoded.takeWhile { it.record.effectiveTick <= durableTick }
        require(decoded.drop(consumed.size).all { it.record.effectiveTick > durableTick }) {
            "battle event effective ticks cross the checkpoint cursor"
        }
        require(consumed.zipWithNext().all { (left, right) ->
            left.record.effectiveTick <= right.record.effectiveTick &&
                left.record.sessionEpoch <= right.record.sessionEpoch
        }) { "battle event tick or epoch goes backwards" }
        var state = initial
        var cursor = afterEventSeq
        var offset = 0
        fun apply(atTick: Int): TickInputs {
            val commands = mutableListOf<TacticalCommand>()
            var aiOrders: List<TacticalAiOrder>? = null
            while (offset < consumed.size && consumed[offset].record.effectiveTick == atTick) {
                val (record, payload) = consumed[offset++]
                when (record.type) {
                    "SESSION_STARTED" -> {
                        require(payload.keys == setOf("schemaVersion", "kind") &&
                            payload.int("schemaVersion") == 1 && payload.string("kind") == record.type)
                        require(atTick == 0)
                    }
                    "HUMAN_JOIN", "HUMAN_LEFT", "AI_TAKEOVER" -> {
                        require(payload.keys == setOf("schemaVersion", "side") &&
                            payload.int("schemaVersion") == 1)
                        val side = enumValue<BattleSide>(payload.string("side"))
                        state = state.copy(humanSides = if (record.type == "HUMAN_JOIN")
                            state.humanSides + side else state.humanSides - side)
                    }
                    "COMMAND_ACCEPTED" -> {
                        require(atTick > 0 && record.effectiveTick == record.tick + 1)
                        require(payload.keys == setOf("schemaVersion", "side", "slot", "order", "rally") &&
                            payload.int("schemaVersion") == 1)
                        val side = enumValue<BattleSide>(payload.string("side"))
                        require(side in state.humanSides) { "command lacks human control" }
                        val slot = payload.getValue("slot").let {
                            if (it == JsonNull) null else enumValue<FormationSlot>(it.jsonPrimitive.content)
                        }
                        commands += TacticalCommand(state.tick, record.eventSeq, side, slot,
                            enumValue(payload.string("order")), enumValue(payload.string("rally")))
                    }
                    "AI_ORDERS" -> {
                        require(atTick > 0 && record.effectiveTick == record.tick + 1)
                        require(payload.keys == setOf("schemaVersion", "orders") &&
                            payload.int("schemaVersion") == 1 && aiOrders == null)
                        aiOrders = payload.getValue("orders").jsonArray.map { value ->
                            val order = value.jsonObject
                            require(order.keys == setOf("side", "slot", "order", "rally"))
                            TacticalAiOrder(enumValue(order.string("side")),
                                enumValue(order.string("slot")), enumValue(order.string("order")),
                                enumValue(order.string("rally")))
                        }
                    }
                    else -> error("unsupported battle event type: ${record.type}")
                }
                cursor = record.eventSeq
            }
            // A takeover later in this same tick supersedes earlier human orders.
            return TickInputs(commands.filter { it.side in state.humanSides }, aiOrders)
        }
        apply(state.tick).also { require(it.commands.isEmpty() && it.aiOrders == null) }
        while (state.tick < durableTick) {
            require(state.outcome == null) { "durable tick extends past battle resolution" }
            val inputs = apply(state.tick + 1)
            if (requireAutomaticOrders) require(inputs.aiOrders != null) {
                "durable battle tick lacks automatic order input"
            }
            state = TacticalBattle.step(state, inputs.commands, inputs.aiOrders).state
        }
        require(offset == consumed.size)
        return BattleTimelineState(state, cursor)
    }

    private data class Decoded(val record: BattleEventRecord, val payload: JsonObject)
    private data class TickInputs(val commands: List<TacticalCommand>,
                                  val aiOrders: List<TacticalAiOrder>?)

    private fun sha(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun JsonObject.int(key: String) = getValue(key).jsonPrimitive.int
    private fun JsonObject.string(key: String) = getValue(key).jsonPrimitive.content
    private inline fun <reified T : Enum<T>> enumValue(value: String): T =
        enumValues<T>().singleOrNull { it.name == value } ?: error("invalid battle event enum: $value")
}
