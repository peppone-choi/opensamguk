package opensamguk.gameapi.battle.realtime

import java.util.concurrent.Executors
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import opensamguk.common.world.WorldId
import opensamguk.infra.battle.realtime.BattleSessionStore

data class BattleLeaseKey(val worldId: WorldId, val battleId: String,
                          val owner: String, val epoch: Long) {
    init { require(battleId.isNotBlank() && owner.isNotBlank() && epoch > 0) }
}

fun interface BattleIntervalTimer {
    fun schedule(periodMillis: Long, task: () -> Unit): AutoCloseable
}

/** A fixed-rate 100ms timer; one periodic task cannot execute concurrently with itself. */
class FixedRateBattleTimer(
    private val executor: ScheduledExecutorService = Executors.newScheduledThreadPool(2),
) : BattleIntervalTimer, AutoCloseable {
    override fun schedule(periodMillis: Long, task: () -> Unit): AutoCloseable {
        require(periodMillis == 100L)
        val future = executor.scheduleAtFixedRate(task, 0, periodMillis, TimeUnit.MILLISECONDS)
        return AutoCloseable { future.cancel(false) }
    }

    override fun close() { executor.shutdownNow() }
}

/** Registers already claimed actors. Discovery, joining, result flush and projections are caller-owned. */
class BattleSessionCadence(private val store: BattleSessionStore,
                           private val timer: BattleIntervalTimer) : AutoCloseable {
    private val entries = ConcurrentHashMap<Pair<WorldId, String>, Entry>()
    private var closed = false

    fun attach(key: BattleLeaseKey, tick: () -> BattleTickAttempt,
               onTick: (BattleTickAttempt) -> Unit,
               onFailure: (Throwable) -> Unit): Boolean {
        val identity = key.worldId to key.battleId
        val entry = Entry(key, tick, onTick, onFailure)
        synchronized(this) {
            check(!closed) { "battle cadence closed" }
            if (entries.putIfAbsent(identity, entry) != null) return false
        }
        try {
            entry.install(timer.schedule(100L) { entry.cycle() })
        } catch (failure: Throwable) {
            entry.stop()
            throw failure
        }
        return true
    }

    fun detach(worldId: WorldId, battleId: String): Boolean {
        val entry = entries.remove(worldId to battleId) ?: return false
        entry.stop()
        return true
    }

    fun activeCount(): Int = entries.size

    override fun close() {
        val active = synchronized(this) {
            closed = true
            entries.values.toList()
        }
        active.forEach { it.stop() }
    }

    private inner class Entry(
        val key: BattleLeaseKey,
        val tick: () -> BattleTickAttempt,
        val onTick: (BattleTickAttempt) -> Unit,
        val onFailure: (Throwable) -> Unit,
    ) {
        private var timerHandle: AutoCloseable? = null
        @Volatile var stopped = false
        private var cyclesSinceRenewal = 0

        @Synchronized
        fun install(handle: AutoCloseable) {
            if (stopped) handle.close() else timerHandle = handle
        }

        @Synchronized
        fun cycle() {
            if (stopped) return
            try {
                if (cyclesSinceRenewal == 0 && !store.renewLease(key.worldId, key.battleId,
                        key.owner, key.epoch, 15_000)) {
                    onTick(BattleTickAttempt.NotRunning)
                    stop()
                    return
                }
                val result = tick()
                onTick(result)
                cyclesSinceRenewal = (cyclesSinceRenewal + 1) % 50
                if (result is BattleTickAttempt.NotRunning || result is BattleTickAttempt.Resolved)
                    stop()
            } catch (failure: Throwable) {
                stop()
                onFailure(failure)
            }
        }

        @Synchronized
        fun stop() {
            if (stopped) return
            stopped = true
            try {
                timerHandle?.close()
            } finally {
                entries.remove(key.worldId to key.battleId, this)
            }
        }
    }
}
