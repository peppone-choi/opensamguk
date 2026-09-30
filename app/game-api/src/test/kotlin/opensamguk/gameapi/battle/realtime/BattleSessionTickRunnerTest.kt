package opensamguk.gameapi.battle.realtime

import java.security.MessageDigest
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import opensamguk.common.world.WorldId
import opensamguk.infra.battle.realtime.*
import opensamguk.logic.battle.realtime.*

class BattleSessionTickRunnerTest {
    private val world = WorldId(1)
    private val field = Battlefield(192, "FIELD", List(64) { "P".repeat(64) })
    private fun sha(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun event(seq: Long, tick: Int, effective: Int, type: String, payload: String) =
        BattleEventRecord(seq, 1, tick, effective, type, payload, sha(payload))
    private fun initial(): TacticalState {
        fun unit(id: Int) = Retinue(id, GeneralStats(id, 70, 70, 70, 70, 70), 100,
            UnitKind.INFANTRY, 50, 90, 0, 100, true)
        return TacticalBattle.start(17, field,
            BattleDeployment.default(BattleSide.ATTACKER, 1, listOf(unit(1))),
            BattleDeployment.default(BattleSide.DEFENDER, 2, listOf(unit(2))))
    }
    private fun ticket(): FrozenBattleTicket = FrozenBattleTicket(world, "battle-test", "{}",
        sha("{}"), "a".repeat(64), "b".repeat(64), "c".repeat(64), 17, 1, 1,
        Instant.parse("2026-09-27T00:01:00Z"), Instant.parse("2026-09-27T00:06:00Z"), emptyList())
    private fun runner(store: FakeStore, epoch: Long = 1) = BattleSessionTickRunner(store,
        { initial() }, world, "battle-test", "actor", epoch)

    @Test
    fun `fifty durable ticks checkpoint and a fresh actor resumes from that state`() {
        val store = FakeStore(ticket())
        val actor = runner(store)
        var last: BattleTickAttempt.Advanced? = null
        repeat(50) { index ->
            val step = assertIs<BattleTickAttempt.Advanced>(actor.tick())
            last = step
            assertEquals(index + 1, step.state.tick)
            assertEquals(index == 49, step.checkpointed)
        }
        val checkpoint = assertNotNull(store.snapshot)
        assertEquals(50, checkpoint.tick)
        assertEquals(50L, checkpoint.eventSeq)
        assertEquals(50, store.log.count { it.type == "AI_ORDERS" })
        val expected = TacticalBattle.step(assertNotNull(last).state).state
        val resumed = assertIs<BattleTickAttempt.Advanced>(runner(store).tick())
        assertEquals(51, resumed.state.tick)
        assertEquals(TacticalBattle.stateHash(expected), TacticalBattle.stateHash(resumed.state))
        assertEquals(51, store.session.currentTick)
    }

    @Test
    fun `new command racing the tick CAS makes actor replay before advancing`() {
        val store = FakeStore(ticket())
        store.log += event(1, 0, 0, "HUMAN_JOIN", """{"schemaVersion":1,"side":"ATTACKER"}""")
        store.log += event(2, 0, 0, "SESSION_STARTED", """{"schemaVersion":1,"kind":"SESSION_STARTED"}""")
        store.session = store.session.copy(latestEventSeq = 2)
        val command = event(3, 0, 1, "COMMAND_ACCEPTED",
            """{"schemaVersion":1,"side":"ATTACKER","slot":null,"order":"CHARGE","rally":"CENTER"}""")
        store.injectOnce = command
        val actor = runner(store)
        assertEquals(BattleTickAttempt.Contended, actor.tick())
        assertEquals(0, store.session.currentTick)
        val advanced = assertIs<BattleTickAttempt.Advanced>(actor.tick())
        assertEquals(1, advanced.state.tick)
        assertEquals(4L, advanced.eventSeq)
        val direct = TacticalBattle.step(initial().copy(humanSides = setOf(BattleSide.ATTACKER)),
            listOf(TacticalCommand(0, 4, BattleSide.ATTACKER, null, BattleOrder.CHARGE))).state
        assertEquals(TacticalBattle.stateHash(direct), TacticalBattle.stateHash(advanced.state))
    }

    @Test
    fun `bad checkpoint and stale epoch never advance durable tick`() {
        val store = FakeStore(ticket())
        assertEquals(BattleTickAttempt.NotRunning, runner(store, epoch = 2).tick())
        assertEquals(0, store.session.currentTick)
        store.snapshot = BattleCheckpoint(world, "battle-test", 1, "actor", 0, 0,
            "0".repeat(64), TacticalStateCodec.encode(initial()))
        assertFailsWith<IllegalArgumentException> { runner(store).tick() }
        assertEquals(0, store.session.currentTick)
        assertFalse(store.advanced)
    }

    @Test
    fun `checkpoint ahead of a stale head is retried as contention`() {
        val store = FakeStore(ticket())
        val state50 = TacticalBattle.replay(initial(), emptyList(), 50)
        store.session = store.session.copy(currentTick = 50, latestSnapshotSeq = 1)
        store.snapshot = BattleCheckpoint(world, "battle-test", 1, "actor", 50, 0,
            TacticalBattle.stateHash(state50), TacticalStateCodec.encode(state50))
        store.staleHeadOnce = store.session.copy(currentTick = 49, latestSnapshotSeq = 0)
        val actor = runner(store)
        assertEquals(BattleTickAttempt.Contended, actor.tick())
        assertFalse(store.advanced)
        val resumed = assertIs<BattleTickAttempt.Advanced>(actor.tick())
        assertEquals(51, resumed.state.tick)
        assertEquals(TacticalBattle.stateHash(TacticalBattle.step(state50).state),
            TacticalBattle.stateHash(resumed.state))
    }

    @Test
    fun `terminal tick closes admission atomically and recovers as resolved`() {
        val store = FakeStore(ticket())
        val actor = runner(store)
        var result: BattleTickAttempt.Resolved? = null
        for (tick in 1..TacticalRules.CANON.battleTicks) {
            when (val attempt = actor.tick()) {
                is BattleTickAttempt.Resolved -> { result = attempt; break }
                is BattleTickAttempt.Advanced -> Unit
                else -> error("unexpected tick result: $attempt")
            }
        }
        val resolved = assertNotNull(result)
        assertEquals(BattleSessionPhase.RESOLVING, store.session.phase)
        assertTrue(resolved.state.tick <= TacticalRules.CANON.battleTicks)
        val recovered = assertIs<BattleTickAttempt.Resolved>(runner(store).tick())
        assertEquals(TacticalBattle.stateHash(resolved.state), TacticalBattle.stateHash(recovered.state))
    }

    private class FakeStore(private val frozen: FrozenBattleTicket) : BattleSessionStore {
        var session = BattleSessionHead(frozen.worldId, frozen.battleId, BattleSessionPhase.RUNNING,
            1, 0, 0, 0, "actor", Instant.now().plusSeconds(300), frozen.joinDeadlineAt,
            frozen.deadlineAt)
        var log = mutableListOf<BattleEventRecord>()
        var snapshot: BattleCheckpoint? = null
        var injectOnce: BattleEventRecord? = null
        val transitions = mutableMapOf<String, Long>()
        var staleHeadOnce: BattleSessionHead? = null
        var advanced = false
        override fun create(ticket: FrozenBattleTicket) = false
        override fun ticket(worldId: WorldId, battleId: String) = frozen
        override fun head(worldId: WorldId, battleId: String): BattleSessionHead {
            val stale = staleHeadOnce
            staleHeadOnce = null
            return stale ?: session
        }
        override fun claimEpoch(worldId: WorldId, battleId: String, owner: String,
                                leaseMillis: Long): BattleSessionHead? = null
        override fun renewLease(worldId: WorldId, battleId: String, owner: String,
                                sessionEpoch: Long, leaseMillis: Long) = false
        override fun startRun(worldId: WorldId, battleId: String, owner: String, sessionEpoch: Long) = false
        override fun admit(command: BattleCommandRecord): CommandAdmission = error("unused")
        override fun appendTransition(transition: BattleTransition): Long? {
            transitions[transition.transitionId]?.let { return it }
            if (session.phase != BattleSessionPhase.RUNNING || session.currentTick != transition.tick ||
                session.sessionEpoch != transition.sessionEpoch || session.leaseOwner != transition.leaseOwner)
                return null
            val seq = session.latestEventSeq + 1
            log += BattleEventRecord(seq, transition.sessionEpoch, transition.tick,
                transition.effectiveTick, transition.type, transition.payloadJson, transition.payloadSha256)
            transitions[transition.transitionId] = seq
            session = session.copy(latestEventSeq = seq)
            return seq
        }
        override fun advanceTick(worldId: WorldId, battleId: String, owner: String,
                                 sessionEpoch: Long, expectedTick: Int, expectedEventSeq: Long): Boolean {
            injectOnce?.let { event ->
                val inserted = event.copy(eventSeq = session.latestEventSeq + 1)
                log += inserted
                session = session.copy(latestEventSeq = inserted.eventSeq)
                injectOnce = null
            }
            if (session.currentTick != expectedTick || session.latestEventSeq != expectedEventSeq ||
                session.sessionEpoch != sessionEpoch || session.leaseOwner != owner) return false
            session = session.copy(currentTick = expectedTick + 1)
            advanced = true
            return true
        }
        override fun advanceResolvedTick(worldId: WorldId, battleId: String, owner: String,
                                         sessionEpoch: Long, expectedTick: Int, expectedEventSeq: Long): Boolean {
            if (!advanceTick(worldId, battleId, owner, sessionEpoch, expectedTick, expectedEventSeq)) return false
            session = session.copy(phase = BattleSessionPhase.RESOLVING)
            return true
        }
        override fun checkpoint(checkpoint: BattleCheckpoint): Boolean {
            if (checkpoint.tick != session.currentTick || checkpoint.eventSeq > session.latestEventSeq)
                return false
            snapshot = checkpoint
            session = session.copy(latestSnapshotSeq = session.latestSnapshotSeq + 1)
            return true
        }
        override fun eventsAfter(worldId: WorldId, battleId: String, eventSeq: Long) =
            log.filter { it.eventSeq > eventSeq }
        override fun latestCheckpoint(worldId: WorldId, battleId: String) = snapshot
        override fun publishResult(result: BattleResultRecord) = false
        override fun pendingResults(worldId: WorldId, limit: Int) = emptyList<BattleResultRecord>()
        override fun markApplied(worldId: WorldId, battleId: String, resultRevision: Int) = false
        override fun markBlocked(worldId: WorldId, battleId: String, resultRevision: Int,
                                 reason: String) = false
    }
}
