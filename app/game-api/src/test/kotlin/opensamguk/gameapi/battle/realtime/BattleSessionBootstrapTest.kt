package opensamguk.gameapi.battle.realtime

import java.security.MessageDigest
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import opensamguk.common.world.WorldId
import opensamguk.infra.battle.realtime.*
import opensamguk.logic.battle.realtime.*

class BattleSessionBootstrapTest {
    private val world = WorldId(1)
    private val ref = BattleSessionRef(world, "battle-test")
    private fun sha(value: String) = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun ticket(participants: List<FrozenBattleParticipant> = listOf(
        FrozenBattleParticipant(1, 42, 1, "ATTACKER", 0))) = FrozenBattleTicket(world, ref.battleId, "{}", sha("{}"),
        "a".repeat(64), "b".repeat(64), "c".repeat(64), 17, 1, 1,
        Instant.parse("2026-09-27T00:01:00Z"), Instant.parse("2026-09-27T00:06:00Z"), participants)
    private fun initial(): TacticalState {
        fun retinue(id: Int) = Retinue(id, GeneralStats(id, 70, 70, 70, 70, 70), 100,
            UnitKind.INFANTRY, 50, 90, 0, 100, true)
        val field = Battlefield(192, "FIELD", List(64) { "P".repeat(64) })
        return TacticalBattle.start(17, field,
            BattleDeployment.default(BattleSide.ATTACKER, 1, listOf(retinue(1))),
            BattleDeployment.default(BattleSide.DEFENDER, 2, listOf(retinue(2))))
    }

    @Test
    fun `scan claims once waits for database joining gate then starts a durable tick`() {
        val store = FakeStore(ticket())
        val timer = ManualTimer()
        val cadence = BattleSessionCadence(store, timer)
        val results = mutableListOf<BattleTickAttempt>()
        val bootstrap = BattleSessionBootstrap(BattleSessionDiscovery { listOf(ref) }, store,
            cadence, { initial() }, "actor", { _, result -> results += result },
            { _, failure -> throw failure })
        assertEquals(1, bootstrap.scan())
        assertEquals(0, bootstrap.scan())
        assertEquals(1, store.claims)
        assertTrue(cadence.isAttached(world, ref.battleId))
        timer.fire()
        assertEquals(BattleTickAttempt.Contended, results.last())
        assertEquals(0, store.session.currentTick)
        store.startAllowed = true
        timer.fire()
        assertEquals(1, assertIs<BattleTickAttempt.Advanced>(results.last()).state.tick)
        assertEquals(1, store.session.currentTick)
        assertEquals(1L, store.session.latestEventSeq)
        cadence.close()
    }

    @Test
    fun `candidate lost at atomic claim is ignored`() {
        val store = FakeStore(ticket()).apply { claimAllowed = false }
        val timer = ManualTimer()
        val cadence = BattleSessionCadence(store, timer)
        val bootstrap = BattleSessionBootstrap(BattleSessionDiscovery { listOf(ref) }, store,
            cadence, { initial() }, "actor", { _, _ -> }, { _, failure -> throw failure })
        assertEquals(0, bootstrap.scan())
        assertEquals(0, cadence.activeCount())
        cadence.close()
    }

    @Test
    fun `NPC battle skips interval timer and advances without a join wait`() {
        val store = FakeStore(ticket(emptyList())).apply { startAllowed = true; maxTicks = 1 }
        val timer = ManualTimer()
        var submitted = 0
        val worker = BattleAcceleratedWorker { task ->
            submitted++
            task()
            AutoCloseable { }
        }
        val cadence = BattleSessionCadence(store, timer, worker)
        val results = mutableListOf<BattleTickAttempt>()
        val bootstrap = BattleSessionBootstrap(BattleSessionDiscovery { listOf(ref) }, store,
            cadence, { initial() }, "actor", { _, result -> results += result },
            { _, failure -> throw failure })
        assertEquals(1, bootstrap.scan())
        assertEquals(1, submitted)
        assertEquals(0, timer.schedules)
        assertEquals(1, store.session.currentTick)
        assertTrue(results.first() is BattleTickAttempt.Advanced)
        assertEquals(BattleTickAttempt.Contended, results.last())
        assertEquals(0, cadence.activeCount())
        cadence.close()
    }

    private class ManualTimer : BattleIntervalTimer {
        private lateinit var task: () -> Unit
        var schedules = 0
        override fun schedule(periodMillis: Long, task: () -> Unit): AutoCloseable {
            assertEquals(100L, periodMillis)
            schedules++
            this.task = task
            return AutoCloseable { }
        }
        fun fire() = task()
    }

    private class FakeStore(private val frozen: FrozenBattleTicket) : BattleSessionStore {
        var session = BattleSessionHead(frozen.worldId, frozen.battleId, BattleSessionPhase.READY,
            0, 0, 0, 0, null, null, frozen.joinDeadlineAt, frozen.deadlineAt)
        var claims = 0
        var claimAllowed = true
        var startAllowed = false
        var maxTicks = Int.MAX_VALUE
        private var log = emptyList<BattleEventRecord>()
        override fun claimEpoch(worldId: WorldId, battleId: String, owner: String,
                                leaseMillis: Long): BattleSessionHead? {
            claims++
            if (!claimAllowed) return null
            session = session.copy(phase = BattleSessionPhase.JOINING, sessionEpoch = 1,
                leaseOwner = owner, leaseUntil = Instant.now().plusMillis(leaseMillis))
            return session
        }
        override fun renewLease(worldId: WorldId, battleId: String, owner: String,
                                sessionEpoch: Long, leaseMillis: Long) = true
        override fun startRun(worldId: WorldId, battleId: String, owner: String,
                              sessionEpoch: Long): Boolean {
            if (!startAllowed) return false
            val payload = """{"schemaVersion":1,"kind":"SESSION_STARTED"}"""
            log = listOf(BattleEventRecord(1, sessionEpoch, 0, 0, "SESSION_STARTED", payload,
                MessageDigest.getInstance("SHA-256").digest(payload.toByteArray())
                    .joinToString("") { "%02x".format(it) }))
            session = session.copy(phase = BattleSessionPhase.RUNNING, latestEventSeq = 1)
            return true
        }
        override fun create(ticket: FrozenBattleTicket) = error("unused")
        override fun ticket(worldId: WorldId, battleId: String) = frozen
        override fun head(worldId: WorldId, battleId: String) = session
        override fun admit(command: BattleCommandRecord): CommandAdmission = error("unused")
        override fun appendTransition(transition: BattleTransition): Long? = error("unused")
        override fun advanceTick(worldId: WorldId, battleId: String, owner: String,
                                 sessionEpoch: Long, expectedTick: Int, expectedEventSeq: Long): Boolean {
            if (session.currentTick != expectedTick || session.latestEventSeq != expectedEventSeq)
                return false
            if (session.currentTick >= maxTicks) return false
            session = session.copy(currentTick = expectedTick + 1)
            return true
        }
        override fun advanceResolvedTick(worldId: WorldId, battleId: String, owner: String,
                                         sessionEpoch: Long, expectedTick: Int, expectedEventSeq: Long) =
            advanceTick(worldId, battleId, owner, sessionEpoch, expectedTick, expectedEventSeq)
        override fun checkpoint(checkpoint: BattleCheckpoint) = error("unused")
        override fun eventsAfter(worldId: WorldId, battleId: String, eventSeq: Long) =
            log.filter { it.eventSeq > eventSeq }
        override fun latestCheckpoint(worldId: WorldId, battleId: String): BattleCheckpoint? = null
        override fun publishResult(result: BattleResultRecord) = error("unused")
        override fun pendingResults(worldId: WorldId, limit: Int) = emptyList<BattleResultRecord>()
        override fun markApplied(worldId: WorldId, battleId: String, resultRevision: Int) = error("unused")
        override fun markBlocked(worldId: WorldId, battleId: String, resultRevision: Int,
                                 reason: String) = error("unused")
    }
}
