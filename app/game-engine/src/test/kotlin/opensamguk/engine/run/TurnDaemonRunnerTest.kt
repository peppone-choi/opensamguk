package opensamguk.engine.run

import opensamguk.engine.redis.RealtimePublisher
import opensamguk.engine.redis.RedisCommandStream
import opensamguk.engine.status.DaemonPauseGate
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.ReservedTurnHandler
import opensamguk.engine.turn.TurnDaemonLifecycle
import opensamguk.engine.turn.TurnWorldState
import opensamguk.engine.turn.WorldSnapshot
import opensamguk.infra.persistence.JdbcFlushExecutor
import opensamguk.infra.persistence.ReservedTurnRepository.ReservedTurn
import opensamguk.logic.actions.CommandRegistry
import opensamguk.logic.stats.GeneralActionPipeline
import org.springframework.beans.factory.ObjectProvider
import org.springframework.data.redis.core.StringRedisTemplate
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * F-LOOP — focused, NON-CONTAINER test of the production daemon loop driver [TurnDaemonRunner].
 *
 * Proves the two contracts the new wiring adds (the full seed→drain→dispatch→flush→DB round-trip is
 * already covered by the Docker-gated [TurnRunServiceIT]):
 *  1. **enable gate** — `opensamguk.daemon.enabled=false` ⇒ [TurnDaemonRunner.start] never spins the
 *     loop, [TurnRunService.runTick] is never called (so `@SpringBootTest` context loads stay inert),
 *  2. **cadence drive** — when enabled and a tick is DUE, the loop calls [TurnRunService.runTick] on
 *     the world's cadence; graceful [TurnDaemonRunner.stop] joins the worker.
 *
 * The [TurnRunService] is a thin subclass overriding `nextRunTime`/`runTick` so we drive the loop
 * without a live Redis/Postgres — the loop logic (wait-until-due → runTick → repeat, interruptible
 * shutdown) is the unit under test, not the run orchestration (that is the IT's job).
 */
class TurnDaemonRunnerTest {

    @Test
    fun `disabled runner never starts the loop`() {
        val svc = StubService(ticks = AtomicInteger())
        val runner = TurnDaemonRunner(provider(svc), DaemonPauseGate(), daemonEnabled = false, idlePollMs = 10)
        runner.start()
        assertTrue(!runner.isRunning, "disabled runner reports not running")
        Thread.sleep(80)
        assertEquals(0, svc.ticks.get(), "runTick never called when disabled")
        runner.stop()
    }

    @Test
    fun `enabled runner drives runTick when a tick is due`() {
        val ticks = AtomicInteger()
        val latch = CountDownLatch(1)
        // nextRunTime in the past ⇒ immediately due ⇒ the loop ticks. The stub stops itself after the
        // first tick by advancing its own next-run far into the future, so we count exactly one drive.
        val svc = StubService(ticks = ticks, latch = latch)
        val runner = TurnDaemonRunner(provider(svc), DaemonPauseGate(), daemonEnabled = true, idlePollMs = 10)
        runner.start()
        try {
            assertTrue(runner.isRunning, "enabled runner reports running")
            assertTrue(latch.await(3, TimeUnit.SECONDS), "loop drove runTick on the cadence")
            assertTrue(ticks.get() >= 1, "at least one tick was driven")
        } finally {
            runner.stop()
            assertTrue(!runner.isRunning, "stop() flips running false (graceful join)")
        }
    }

    // ── B1b — pause(동결) 게이트: 동결 중 틱 미진행, 해제 후 재개 ──────────────────────────────────────
    @Test
    fun `paused runner skips ticks until resumed`() {
        val ticks = AtomicInteger()
        val gate = DaemonPauseGate()
        // 시작 전에 동결: 루프가 due여도 게이트에 막혀 틱을 건너뛴다.
        assertTrue(gate.lock(), "락걸기(첫 CAS) 성공")
        val svc = StubService(ticks = ticks)
        val runner = TurnDaemonRunner(provider(svc), gate, daemonEnabled = true, idlePollMs = 10)
        runner.start()
        try {
            assertTrue(runner.isRunning, "루프 스레드는 가동 중")
            Thread.sleep(120)
            assertEquals(0, ticks.get(), "동결 중에는 runTick이 호출되지 않는다")

            // 락풀기 → 다음 폴에서 즉시 재개(nextRunTime은 과거이므로 due).
            assertTrue(gate.unlock(), "락풀기 — 직전 동결이었으므로 changed")
            val resumed = waitUntil(2_000) { ticks.get() >= 1 }
            assertTrue(resumed, "락풀기 후 틱 재개")
        } finally {
            runner.stop()
        }
    }

    private fun waitUntil(timeoutMs: Long, cond: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return true
            Thread.sleep(10)
        }
        return cond()
    }

    // --- stubs ------------------------------------------------------------------------------------

    private fun provider(svc: TurnRunService): ObjectProvider<TurnRunService> =
        object : ObjectProvider<TurnRunService> {
            override fun getObject(vararg args: Any?): TurnRunService = svc
            override fun getObject(): TurnRunService = svc
            override fun getIfAvailable(): TurnRunService = svc
            override fun getIfUnique(): TurnRunService = svc
        }

    /**
     * A [TurnRunService] subclass that drives the loop deterministically without Redis/Postgres. The
     * super-ctor collaborators are cheap in-memory instances that are NEVER touched (both public
     * entry points are overridden). [StringRedisTemplate] is constructed un-connected — no I/O.
     */
    private class StubService(
        val ticks: AtomicInteger,
        private val latch: CountDownLatch? = null,
    ) : TurnRunService(
        world = stubWorld(),
        commandStream = RedisCommandStream(StringRedisTemplate(), "che:test", startId = "0"),
        lifecycle = stubLifecycle(),
        handler = stubHandler(),
        flushExecutor = NO_FLUSH,
        realtimePublisher = RealtimePublisher(StringRedisTemplate(), "che:test"),
    ) {
        // Past ⇒ due now; after the first tick push it far out so the loop idles (one observable drive).
        @Volatile private var next: Instant = Instant.now().minusSeconds(5)

        override fun nextRunTime(): Instant = next

        override fun runTick(runTime: Instant): TickResult {
            ticks.incrementAndGet()
            next = Instant.now().plusSeconds(3600)
            latch?.countDown()
            return TickResult(
                handled = emptyList(),
                flushedGenerals = 0,
                flushedCities = 0,
                flushedLogs = 0,
                turnCompletedAt = runTime.toString(),
                lastTurnTime = runTime.toString(),
            )
        }

        companion object {
            // A constructible (never-invoked) flush executor — runTick is overridden so flush never fires.
            private val NO_FLUSH = JdbcFlushExecutor(
                org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(
                    org.springframework.jdbc.datasource.SimpleDriverDataSource(),
                ),
                org.springframework.transaction.support.TransactionTemplate(),
            )

            private fun stubWorld(): InMemoryTurnWorld = InMemoryTurnWorld(
                WorldSnapshot(
                    state = TurnWorldState(
                        id = 1, currentYear = 200, currentMonth = 1,
                        tickSeconds = 3600, lastTurnTime = Instant.parse("0200-01-01T00:00:00Z"),
                    ),
                ),
            )

            private fun stubHandler(): ReservedTurnHandler =
                ReservedTurnHandler(stubWorld(), CommandRegistry(GeneralActionPipeline()), "00", 184)

            private fun stubLifecycle(): TurnDaemonLifecycle {
                val w = stubWorld()
                return TurnDaemonLifecycle(w, stubHandler()) { ReservedTurn("휴식", "") }
            }
        }
    }
}
