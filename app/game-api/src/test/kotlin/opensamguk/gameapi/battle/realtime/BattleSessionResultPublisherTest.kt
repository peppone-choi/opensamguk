package opensamguk.gameapi.battle.realtime

import java.security.MessageDigest
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import opensamguk.common.world.WorldId
import opensamguk.infra.battle.realtime.*
import opensamguk.logic.battle.realtime.*

class BattleSessionResultPublisherTest {
    private val world = WorldId(1)
    private val ticket = FrozenBattleTicket(world, "npc-result", "{}", sha("{}"),
        "a".repeat(64), "b".repeat(64), "c".repeat(64), 17, 3, 4,
        Instant.parse("2026-09-27T00:01:00Z"), Instant.parse("2026-09-27T00:06:00Z"),
        emptyList())

    @Test
    fun `terminal result retains frozen pacing and exact event log hash`() {
        val store = FakeStore(ticket)
        val key = BattleLeaseKey(world, ticket.battleId, "actor", 1)
        val publisher = BattleSessionResultPublisher(store)
        val resolved = BattleTickAttempt.Resolved(terminalState(), 1)
        assertTrue(publisher.publish(key, resolved))
        val result = requireNotNull(store.published)
        assertEquals(BattlePacingMode.ACCELERATED_NPC, result.pacingMode)
        assertTrue(result.resultJson.contains("\"pacingMode\":\"ACCELERATED_NPC\""))
        assertTrue(result.resultJson.contains("\"outcome\":\"ATTACKER\""))
        assertTrue(result.resultJson.contains("\"resolution\":\"TACTICAL\""))
        assertEquals(sha(result.resultJson), result.resultSha256)
        publisher.publish(key, resolved)
        assertEquals(result.replayHash, store.published?.replayHash)
    }

    @Test
    fun `late event prevents publishing a result with an incomplete replay`() {
        val store = FakeStore(ticket).apply { extraEvent = true }
        assertFailsWith<IllegalArgumentException> {
            BattleSessionResultPublisher(store).publish(
                BattleLeaseKey(world, ticket.battleId, "actor", 1),
                BattleTickAttempt.Resolved(terminalState(), 1))
        }
        assertEquals(null, store.published)
    }

    private fun terminalState(): TacticalState {
        fun retinue(id: Int) = Retinue(id, GeneralStats(id, 70, 70, 70, 70, 70), 100,
            UnitKind.INFANTRY, 50, 90, 0, 100, true)
        val field = Battlefield(192, "FIELD", List(64) { "P".repeat(64) })
        return TacticalBattle.start(17, field,
            BattleDeployment.default(BattleSide.ATTACKER, 1, listOf(retinue(1))),
            BattleDeployment.default(BattleSide.DEFENDER, 2, listOf(retinue(2))))
            .copy(outcome = BattleOutcome.ATTACKER)
    }

    private class FakeStore(private val frozen: FrozenBattleTicket) : BattleSessionStore {
        var published: BattleResultRecord? = null
        var extraEvent = false
        override fun ticket(worldId: WorldId, battleId: String) = frozen
        override fun eventsAfter(worldId: WorldId, battleId: String, eventSeq: Long): List<BattleEventRecord> {
            val events = mutableListOf(BattleEventRecord(1, 1, 0, 0, "SESSION_STARTED", "{}", sha("{}")))
            if (extraEvent) events += BattleEventRecord(2, 1, 0, 1, "COMMAND_ACCEPTED", "{}", sha("{}"))
            return events.filter { it.eventSeq > eventSeq }
        }
        override fun publishResult(result: BattleResultRecord): Boolean {
            published = result
            return true
        }
        override fun create(ticket: FrozenBattleTicket) = error("unused")
        override fun head(worldId: WorldId, battleId: String): BattleSessionHead? = error("unused")
        override fun claimEpoch(worldId: WorldId, battleId: String, owner: String,
                                leaseMillis: Long): BattleSessionHead? = error("unused")
        override fun renewLease(worldId: WorldId, battleId: String, owner: String,
                                sessionEpoch: Long, leaseMillis: Long) = error("unused")
        override fun startRun(worldId: WorldId, battleId: String, owner: String,
                              sessionEpoch: Long) = error("unused")
        override fun admit(command: BattleCommandRecord): CommandAdmission = error("unused")
        override fun appendTransition(transition: BattleTransition): Long? = error("unused")
        override fun advanceTick(worldId: WorldId, battleId: String, owner: String,
                                 sessionEpoch: Long, expectedTick: Int, expectedEventSeq: Long) = error("unused")
        override fun advanceResolvedTick(worldId: WorldId, battleId: String, owner: String,
                                         sessionEpoch: Long, expectedTick: Int, expectedEventSeq: Long) = error("unused")
        override fun resolveTimeout(worldId: WorldId, battleId: String, owner: String,
                                    sessionEpoch: Long, expectedTick: Int, expectedEventSeq: Long) = error("unused")
        override fun checkpoint(checkpoint: BattleCheckpoint) = error("unused")
        override fun latestCheckpoint(worldId: WorldId, battleId: String): BattleCheckpoint? = error("unused")
        override fun pendingResults(worldId: WorldId, limit: Int) = error("unused")
        override fun markApplied(worldId: WorldId, battleId: String, resultRevision: Int) = error("unused")
        override fun markBlocked(worldId: WorldId, battleId: String, resultRevision: Int,
                                 reason: String) = error("unused")
    }
}

private fun sha(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
