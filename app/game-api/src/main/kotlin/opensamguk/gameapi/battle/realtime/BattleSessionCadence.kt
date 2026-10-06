package opensamguk.gameapi.battle.realtime

import java.util.concurrent.Executors
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import opensamguk.common.world.WorldId
import opensamguk.infra.battle.realtime.BattlePacingMode
import opensamguk.infra.battle.realtime.BattleSessionStore

data class BattleLeaseKey(val worldId: WorldId, val battleId: String,
                          val owner: String, val epoch: Long) {
    init { require(battleId.isNotBlank() && owner.isNotBlank() && epoch > 0) }
}

fun interface BattleIntervalTimer {
    fun schedule(periodMillis: Long, task: () -> Unit): AutoCloseable
}

fun interface BattleAcceleratedWorker {
    fun submit(task: () -> Unit): AutoCloseable
}

/** A bounded number of NPC actors execute without wall-clock pacing. */
class FixedPoolBattleWorker(
    private val executor: ExecutorService = ThreadPoolExecutor(2, 2, 0,
        TimeUnit.MILLISECONDS, ArrayBlockingQueue(32)),
) : BattleAcceleratedWorker, AutoCloseable {
    override fun submit(task: () -> Unit): AutoCloseable {
        val future: Future<*> = executor.submit(task)
        return AutoCloseable { future.cancel(false) }
    }

    override fun close() { executor.shutdownNow() }
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
                           private val timer: BattleIntervalTimer,
                           private val worker: BattleAcceleratedWorker = FixedPoolBattleWorker()) : AutoCloseable {
    private val entries = ConcurrentHashMap<Pair<WorldId, String>, Entry>()
    private var closed = false

    fun attach(key: BattleLeaseKey, tick: () -> BattleTickAttempt,
               onTick: (BattleTickAttempt) -> Unit,
               onFailure: (Throwable) -> Unit,
               pacingMode: BattlePacingMode = BattlePacingMode.REALTIME): Boolean {
        val identity = key.worldId to key.battleId
        val entry = Entry(key, tick, onTick, onFailure, pacingMode)
        synchronized(this) {
            check(!closed) { "battle cadence closed" }
            if (entries.putIfAbsent(identity, entry) != null) return false
        }
        try {
            entry.install(when (pacingMode) {
                BattlePacingMode.REALTIME -> timer.schedule(100L) { entry.cycle() }
                BattlePacingMode.ACCELERATED_NPC -> worker.submit {
                    while (entry.cycle()) { /* logical ticks, no clock delay */ }
                }
            })
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

    fun isAttached(worldId: WorldId, battleId: String): Boolean =
        entries.containsKey(worldId to battleId)

    override fun close() {
        val active = synchronized(this) {
            closed = true
            entries.values.toList()
        }
        active.forEach { it.stop() }
        (timer as? AutoCloseable)?.close()
        (worker as? AutoCloseable)?.close()
    }

    private inner class Entry(
        val key: BattleLeaseKey,
        val tick: () -> BattleTickAttempt,
        val onTick: (BattleTickAttempt) -> Unit,
        val onFailure: (Throwable) -> Unit,
        val pacingMode: BattlePacingMode,
    ) {
        private var timerHandle: AutoCloseable? = null
        @Volatile var stopped = false
        private var cyclesSinceRenewal = 0

        @Synchronized
        fun install(handle: AutoCloseable) {
            if (stopped) handle.close() else timerHandle = handle
        }

        @Synchronized
        fun cycle(): Boolean {
            if (stopped) return false
            try {
                if (cyclesSinceRenewal == 0 && !store.renewLease(key.worldId, key.battleId,
                        key.owner, key.epoch, 15_000)) {
                    onTick(BattleTickAttempt.NotRunning)
                    stop()
                    return false
                }
                val result = tick()
                onTick(result)
                cyclesSinceRenewal = (cyclesSinceRenewal + 1) % 50
                if (result is BattleTickAttempt.NotRunning || result is BattleTickAttempt.Resolved)
                    stop()
                // A lost CAS is retried by discovery; spinning would starve other battles.
                if (result is BattleTickAttempt.Contended && pacingMode == BattlePacingMode.ACCELERATED_NPC) {
                    stop()
                    return false
                }
                return !stopped
            } catch (failure: Throwable) {
                stop()
                onFailure(failure)
                return false
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
