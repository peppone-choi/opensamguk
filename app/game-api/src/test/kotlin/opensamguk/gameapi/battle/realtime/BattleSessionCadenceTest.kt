package opensamguk.gameapi.battle.realtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import opensamguk.common.world.WorldId
import opensamguk.infra.battle.realtime.*

class BattleSessionCadenceTest {
    private val key = BattleLeaseKey(WorldId(1), "battle-test", "actor", 1)

    @Test
    fun `one hundred millisecond cadence renews lease every fifty cycles`() {
        val store = FakeStore()
        val timer = ManualTimer()
        val results = mutableListOf<BattleTickAttempt>()
        val cadence = BattleSessionCadence(store, timer)
        assertTrue(cadence.attach(key, { BattleTickAttempt.Contended }, results::add, { throw it }))
        assertEquals(100L, timer.periodMillis)
        assertFalse(cadence.attach(key, { error("duplicate actor ran") }, results::add, { throw it }))
        repeat(50) { timer.fire() }
        assertEquals(1, store.renewals)
        assertEquals(50, results.size)
        timer.fire()
        assertEquals(2, store.renewals)
        assertEquals(1, cadence.activeCount())
        assertTrue(cadence.detach(key.worldId, key.battleId))
        assertTrue(timer.cancelled)
        assertEquals(0, cadence.activeCount())
        cadence.close()
    }

    @Test
    fun `lost lease stops before simulation and immediate timer completion closes its handle`() {
        val store = FakeStore().apply { renewResult = false }
        val timer = ManualTimer(fireDuringSchedule = true)
        val outcomes = mutableListOf<BattleTickAttempt>()
        val cadence = BattleSessionCadence(store, timer)
        assertTrue(cadence.attach(key, { error("simulation must not run") }, outcomes::add,
            { throw it }))
        assertEquals(1, outcomes.size)
        assertEquals(BattleTickAttempt.NotRunning, outcomes.single())
        assertEquals(0, cadence.activeCount())
        assertTrue(timer.cancelled)
        cadence.close()
    }

    @Test
    fun `actor failure is reported once and detached`() {
        val store = FakeStore()
        val timer = ManualTimer()
        val failures = mutableListOf<Throwable>()
        val cadence = BattleSessionCadence(store, timer)
        cadence.attach(key, { error("bad event tail") }, {}, failures::add)
        timer.fire()
        assertEquals(1, failures.size)
        assertEquals("bad event tail", failures.single().message)
        assertEquals(0, cadence.activeCount())
        assertTrue(timer.cancelled)
        cadence.close()
    }

    private class ManualTimer(private val fireDuringSchedule: Boolean = false) : BattleIntervalTimer {
        var periodMillis = -1L
        var cancelled = false
        private lateinit var task: () -> Unit
        override fun schedule(periodMillis: Long, task: () -> Unit): AutoCloseable {
            this.periodMillis = periodMillis
            this.task = task
            if (fireDuringSchedule) task()
            return AutoCloseable { cancelled = true }
        }
        fun fire() { if (!cancelled) task() }
    }

    private class FakeStore : BattleSessionStore {
        var renewals = 0
        var renewResult = true
        override fun renewLease(worldId: WorldId, battleId: String, owner: String,
                                sessionEpoch: Long, leaseMillis: Long): Boolean {
            assertEquals(15_000L, leaseMillis)
            renewals++
            return renewResult
        }
        override fun create(ticket: FrozenBattleTicket) = error("unused")
        override fun ticket(worldId: WorldId, battleId: String): FrozenBattleTicket? = error("unused")
        override fun head(worldId: WorldId, battleId: String): BattleSessionHead? = error("unused")
        override fun claimEpoch(worldId: WorldId, battleId: String, owner: String,
                                leaseMillis: Long): BattleSessionHead? = error("unused")
        override fun startRun(worldId: WorldId, battleId: String, owner: String, sessionEpoch: Long) = error("unused")
        override fun admit(command: BattleCommandRecord): CommandAdmission = error("unused")
        override fun appendTransition(transition: BattleTransition): Long? = error("unused")
        override fun advanceTick(worldId: WorldId, battleId: String, owner: String,
                                 sessionEpoch: Long, expectedTick: Int, expectedEventSeq: Long) = error("unused")
        override fun checkpoint(checkpoint: BattleCheckpoint) = error("unused")
        override fun eventsAfter(worldId: WorldId, battleId: String, eventSeq: Long): List<BattleEventRecord> = error("unused")
        override fun latestCheckpoint(worldId: WorldId, battleId: String): BattleCheckpoint? = error("unused")
        override fun publishResult(result: BattleResultRecord) = error("unused")
        override fun pendingResults(worldId: WorldId, limit: Int): List<BattleResultRecord> = error("unused")
        override fun markApplied(worldId: WorldId, battleId: String, resultRevision: Int) = error("unused")
        override fun markBlocked(worldId: WorldId, battleId: String, resultRevision: Int,
                                 reason: String) = error("unused")
    }
}
