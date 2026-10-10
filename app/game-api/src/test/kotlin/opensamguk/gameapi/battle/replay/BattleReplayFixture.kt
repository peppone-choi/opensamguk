package opensamguk.gameapi.battle.replay

import java.lang.reflect.Proxy
import java.security.MessageDigest
import java.time.Instant
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import opensamguk.common.world.WorldId
import opensamguk.gameapi.battle.realtime.BattleFrozenInputCodec
import opensamguk.gameapi.battle.realtime.BattleLeaseKey
import opensamguk.gameapi.battle.realtime.BattleResolutionKind
import opensamguk.gameapi.battle.realtime.BattleSessionResultPublisher
import opensamguk.gameapi.battle.realtime.BattleTickAttempt
import opensamguk.infra.battle.realtime.*
import opensamguk.infra.battle.replay.BattleReplayArchive
import opensamguk.infra.battle.replay.BattleReplayStoredResult
import opensamguk.logic.battle.realtime.TacticalBattle
import opensamguk.logic.battle.realtime.TacticalBoardCatalog
import opensamguk.logic.battle.realtime.TacticalStateCodec

/** Entirely synthetic archive: APPLIED is fixture metadata, never a real campaign ACK. */
internal class BattleReplayFixture {
    val world = WorldId(1)
    private val joinAt = Instant.parse("2026-09-27T00:01:00Z")
    private val deadline = Instant.parse("2026-09-27T00:06:00Z")
    private val ruleBytes = checkNotNull(javaClass.classLoader
        .getResourceAsStream("battle/waryong-tactical-rules-v1.json")).use { it.readBytes() }
    val catalog: TacticalBoardCatalog = TacticalBoardCatalog.parse(catalogPayload())
    val frozen = BattleFrozenInputCodec(catalog)
    val ticket = FrozenBattleTicket(world, "archive-1", payload(), sha(payload()), sha(ruleBytes),
        catalog.catalogSha256, "c".repeat(64), 17, 4, 2, joinAt, deadline,
        listOf(FrozenBattleParticipant(1, 42, 1, "ATTACKER", 3)))
    val initial = frozen.initialState(ticket)
    val aiPayload = buildJsonObject {
        put("schemaVersion", 1)
        put("orders", buildJsonArray {
            TacticalBattle.automaticOrders(initial).forEach { order -> add(buildJsonObject {
                put("side", order.side.name)
                put("slot", order.slot.name)
                put("order", order.order.name)
                put("rally", order.rally.name)
            }) }
        })
    }.toString()
    val inputs = listOf(
        event(1, 0, 0, "SESSION_STARTED", """{"schemaVersion":1,"kind":"SESSION_STARTED"}"""),
        event(2, 0, 1, "AI_ORDERS", aiPayload),
    )
    val stepped = TacticalBattle.step(initial, suppliedAiOrders = TacticalBattle.automaticOrders(initial)).state

    fun archive(): BattleReplayArchive {
        val result = publishedResult()
        val head = BattleSessionHead(world, ticket.battleId, BattleSessionPhase.APPLIED,
            1, 1, 3, 1, "fixture-actor", deadline, joinAt, deadline)
        val checkpoint = BattleCheckpoint(world, ticket.battleId, 1, "fixture-actor", 1, 2,
            TacticalBattle.stateHash(stepped), TacticalStateCodec.encode(stepped))
        val terminal = event(3, 1, 1, "BATTLE_RESOLVED", result.resultJson)
        return BattleReplayArchive(ticket, head,
            listOf(BattleReplayStoredResult(result, "APPLIED", deadline)), inputs + terminal, checkpoint)
    }

    private fun publishedResult(): BattleResultRecord {
        var published: BattleResultRecord? = null
        val store = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(BattleSessionStore::class.java)) { _, method, args ->
            when (method.name.substringBefore('-')) {
                "ticket" -> ticket
                "eventsAfter" -> inputs
                "publishResult" -> { published = args[0] as BattleResultRecord; true }
                else -> error("fixture must not invoke ${method.name}")
            }
        } as BattleSessionStore
        val terminal = stepped.copy(outcome = TacticalBattle.timeoutOutcome(stepped))
        check(BattleSessionResultPublisher(store).publish(
            BattleLeaseKey(world, ticket.battleId, "fixture-actor", 1),
            BattleTickAttempt.Resolved(terminal, 2, BattleResolutionKind.TIMEOUT_SCORE)))
        return checkNotNull(published)
    }

    private fun payload(): String {
        val units = (1..2).map { id -> retinue(id) }
        return """{"schemaVersion":1,"worldId":1,"battleId":"archive-1","kind":"ENCOUNTER","battlefieldId":192,"ruleSha256":"${sha(ruleBytes)}","catalogSha256":"${catalog.catalogSha256}","terrainSha256":"${"c".repeat(64)}","seed":17,"lockGeneration":4,"lockSetRevision":2,"joinDeadlineAt":"$joinAt","deadlineAt":"$deadline","tacticalInput":{"schemaVersion":1,"board":{"id":192,"tileset":0,"terrainRowsSha256":"${sha("P".repeat(4096))}"},"attacker":{"commanderGeneralId":1,"retinues":[${units[0]}]},"defender":{"commanderGeneralId":2,"retinues":[${units[1]}]},"gate":null}}"""
    }

    private fun retinue(id: Int): String =
        """{"id":$id,"generalId":$id,"leadership":80,"strength":70,"intelligence":60,"politics":50,"charisma":40,"troops":123,"kind":"INFANTRY","training":50,"morale":90,"fatigue":10,"supply":80,"accompaniesCorps":true}"""

    private fun catalogPayload(): String {
        val plain = "P".repeat(64)
        val wall = "P".repeat(10) + "W" + "P".repeat(53)
        val boards = (0..213).joinToString(",") { id ->
            val fortress = id < 188
            val rows = (0..63).joinToString(",") { row -> "\"${if (fortress && row == 10) wall else plain}\"" }
            val counts = if (fortress) "\"P\":4095,\"F\":0,\"M\":0,\"R\":0,\"W\":1" else
                "\"P\":4096,\"F\":0,\"M\":0,\"R\":0,\"W\":0"
            """{"id":$id,"kind":"${if (fortress) "FORTRESS" else "FIELD"}","tileset":0,"image":"maps/battle_${id.toString().padStart(3, '0')}_ts0.png","landEligible":${id !in 209..212},"terrainCounts":{$counts},"terrainRows":[$rows]}"""
        }
        return """{"schemaVersion":1,"boardSize":64,"status":"owner-accepted derived catalog","source":{"originalBinaryCommitted":false},"boards":[$boards]}"""
    }

    fun event(seq: Long, tick: Int, effective: Int, type: String, payload: String) =
        BattleEventRecord(seq, 1, tick, effective, type, payload, sha(payload))

    fun sha(text: String) = sha(text.toByteArray(Charsets.UTF_8))
    private fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}
