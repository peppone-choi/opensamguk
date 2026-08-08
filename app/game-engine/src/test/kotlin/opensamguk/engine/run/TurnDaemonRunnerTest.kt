package opensamguk.engine.run

import opensamguk.common.world.WorldId

import opensamguk.engine.boot.WorldStateAvailability
import opensamguk.engine.redis.RealtimePublisher
import opensamguk.engine.redis.RedisCommandStream
import opensamguk.engine.status.DaemonPauseGate
import opensamguk.engine.status.TurnDaemonHealthIndicator
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.springframework.boot.actuate.health.Status

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
        val runner = TurnDaemonRunner(provider(svc), WORLD_EXISTS, DaemonPauseGate(), daemonEnabled = false, idlePollMs = 10)
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
        val runner = TurnDaemonRunner(provider(svc), WORLD_EXISTS, DaemonPauseGate(), daemonEnabled = true, idlePollMs = 10)
        runner.start()
        try {
            assertTrue(runner.isRunning, "enabled runner reports running")
            assertTrue(latch.await(3, TimeUnit.SECONDS), "loop drove runTick on the cadence")
            assertTrue(ticks.get() >= 1, "at least one tick was driven")
            val recorded = waitUntil(2_000) {
                val snapshot = runner.diagnostics()
                snapshot.successfulTicks >= 1 && snapshot.lastTickCompletedAt != null
            }
            assertTrue(recorded, "successful tick metrics are recorded after runTick returns")
            val diagnostics = runner.diagnostics()
            assertTrue(diagnostics.successfulTicks >= 1, "successful tick count is exposed")
            assertEquals(0, diagnostics.consecutiveFailures, "success resets consecutive failures")
            assertNotNull(diagnostics.lastTickStartedAt, "started-at metric is exposed")
            assertNotNull(diagnostics.lastTickCompletedAt, "completed-at metric is exposed")
            // OPENSAM-175 — 헬스가 읽는 신규 필드가 실제 diagnostics()에서 채워지는지(손으로 만든
            // TurnDaemonDiagnostics가 아니라) 증명한다.
            assertNotNull(diagnostics.loopUptimeSeconds, "loop uptime is exposed while the loop is alive")
            assertNotNull(diagnostics.lastSuccessfulTickAgeSeconds, "wall-clock tick age is exposed after a tick")
            assertTrue(diagnostics.autoStartEnabled, "enable flag is exposed")
        } finally {
            runner.stop()
            assertTrue(!runner.isRunning, "stop() flips running false (graceful join)")
            assertNull(runner.diagnostics().loopUptimeSeconds, "stop() clears the loop uptime")
        }
    }

    /**
     * OPENSAM-175 회귀 — `running` 플래그만 믿으면 이 티켓이 없애려던 거짓 UP을 한 단계 위에서 반복한다.
     * [TurnDaemonRunner]의 loop는 `catch (e: Exception)`이라 `Error`(OOM/StackOverflow)를 못 잡고 스레드가
     * 죽는데, `stop()`이 안 불렸으니 `running`은 계속 true다.
     *
     * **이 테스트는 의도적으로 uncaught `StackOverflowError` 스택트레이스를 test `system-out`에 남긴다.**
     * `Error`가 스레드 밖으로 실제로 빠져나가 스레드를 죽이는 것이 검증 대상 자체라, 삼키면(스텁이 catch
     * 하거나 default uncaught handler를 갈아끼우면) 반증력이 사라진다 — 스텁이 catch하면 스레드가 안 죽어
     * 테스트가 아무것도 증명하지 못하고, `Thread.setDefaultUncaughtExceptionHandler`는 JVM 전역 가변 상태라
     * 병렬 테스트에 부작용을 남긴다. 따라서 그대로 둔다. 로그 스캐너("예상하지 않은 engine 로그"를 게이트
     * 신호로 쓰는 `docs/superpowers/plans/2026-07-13-v1-stabilization-and-v2-open-plan.md:233`)는
     * 이 스택트레이스를 화이트리스트에 넣어야 한다.
     */
    @Test
    fun `a loop thread killed by an Error is not reported as a running daemon`() {
        val svc = StubService(ticks = AtomicInteger(), killLoopWithError = true)
        val runner = TurnDaemonRunner(provider(svc), WORLD_EXISTS, DaemonPauseGate(), daemonEnabled = true, idlePollMs = 10)
        runner.start()
        try {
            val dead = waitUntil(3_000) { runner.diagnostics().loopUptimeSeconds == null }
            assertTrue(dead, "죽은 루프 스레드는 uptime을 내지 않는다")
            assertTrue(runner.isRunning, "running 플래그는 여전히 true — 이것이 거짓 UP의 원천이다")
            val health = TurnDaemonHealthIndicator.evaluate(paused = false, diagnostics = runner.diagnostics())
            assertEquals(Status.DOWN, health.status, "스레드가 죽었으면 헬스는 DOWN")
            assertEquals("loop_not_running", health.details["daemon"])
        } finally {
            runner.stop()
        }
    }

    @Test
    fun `runner records tick failures without killing the loop`() {
        val svc = StubService(ticks = AtomicInteger(), failTicks = true)
        val runner = TurnDaemonRunner(provider(svc), WORLD_EXISTS, DaemonPauseGate(), daemonEnabled = true, idlePollMs = 10)
        runner.start()
        try {
            val failed = waitUntil(2_000) { runner.diagnostics().failedTicks >= 1 }
            assertTrue(failed, "tick failure is recorded")
            val diagnostics = runner.diagnostics()
            assertTrue(runner.isRunning, "loop stays alive after a failed tick")
            assertTrue(diagnostics.consecutiveFailures >= 1, "consecutive failure count is exposed")
            assertNotNull(diagnostics.lastTickFailedAt, "failure-at metric is exposed")
            assertTrue(diagnostics.lastTickError?.contains("IllegalStateException") == true, "error class is exposed")
            // 실패한 틱은 성공 시각을 남기지 않는다 — 헬스의 `staleSeconds`(= 마지막 성공 틱 이후 경과)가
            // 사고를 검출하는 유일한 전제다. 이 단언이 없으면 실패 경로에 lastTickCompletedAt을 잘못
            // 심어도 전 테스트가 green이다.
            assertNull(diagnostics.lastTickCompletedAt, "실패 틱은 성공 시각을 갱신하지 않는다")
            assertNull(diagnostics.lastSuccessfulTickAgeSeconds, "성공 틱이 없으면 벽시계 age도 없다")
        } finally {
            runner.stop()
        }
    }

    @Test
    fun `enabled runner drains immediate intake while waiting for the next turn`() {
        val ticks = AtomicInteger()
        val intakeDrains = AtomicInteger()
        val intakeLatch = CountDownLatch(1)
        val svc = StubService(
            ticks = ticks,
            intakeDrains = intakeDrains,
            intakeLatch = intakeLatch,
            initialNextRun = Instant.now().plusSeconds(3600),
        )
        val runner = TurnDaemonRunner(provider(svc), WORLD_EXISTS, DaemonPauseGate(), daemonEnabled = true, idlePollMs = 10)
        runner.start()
        try {
            assertTrue(intakeLatch.await(3, TimeUnit.SECONDS), "loop drained immediate intake before the next turn")
            assertEquals(1, intakeDrains.get(), "one immediate intake drain was observed")
            assertEquals(0, ticks.get(), "scheduled tick stayed idle while nextRunTime is in the future")
        } finally {
            runner.stop()
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
        val runner = TurnDaemonRunner(provider(svc), WORLD_EXISTS, gate, daemonEnabled = true, idlePollMs = 10)
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

    @Test
    fun `paused runner drains immediate intake without executing the due reserved tick`() {
        val ticks = AtomicInteger()
        val intakeDrains = AtomicInteger()
        val intakeLatch = CountDownLatch(1)
        val gate = DaemonPauseGate()
        assertTrue(gate.lock(), "락걸기(첫 CAS) 성공")
        val svc = StubService(
            ticks = ticks,
            intakeDrains = intakeDrains,
            intakeLatch = intakeLatch,
        )
        val runner = TurnDaemonRunner(provider(svc), WORLD_EXISTS, gate, daemonEnabled = true, idlePollMs = 10)

        runner.start()
        try {
            assertTrue(intakeLatch.await(3, TimeUnit.SECONDS), "동결 중에도 즉시 인테이크는 드레인된다")
            assertEquals(1, intakeDrains.get(), "대기 중 즉시 인테이크를 한 번 처리한다")
            assertEquals(0, ticks.get(), "동결 중 due reserved tick은 실행하지 않는다")
        } finally {
            runner.stop()
        }
    }

    @Test
    fun `empty world keeps daemon alive without materializing turn service`() {
        val provided = AtomicInteger()
        val provider = countingProvider(StubService(ticks = AtomicInteger()), provided)
        val runner = TurnDaemonRunner(provider, WorldStateAvailability { false }, DaemonPauseGate(), true, 10)

        runner.start()
        try {
            assertTrue(runner.isRunning, "empty-world daemon thread stays alive")
            Thread.sleep(120)
            assertEquals(0, provided.get(), "TurnRunService is not materialized before world_state exists")
        } finally {
            runner.stop()
        }
    }

    @Test
    fun `daemon materializes turn service once world state appears`() {
        val ticks = AtomicInteger()
        val provided = AtomicInteger()
        val worldExists = AtomicInteger(0)
        val latch = CountDownLatch(1)
        val runner = TurnDaemonRunner(
            countingProvider(StubService(ticks = ticks, latch = latch), provided),
            WorldStateAvailability { worldExists.get() > 0 },
            DaemonPauseGate(),
            daemonEnabled = true,
            idlePollMs = 10,
        )

        runner.start()
        try {
            Thread.sleep(80)
            assertEquals(0, provided.get(), "service not requested while world_state is absent")
            worldExists.incrementAndGet()
            assertTrue(latch.await(3, TimeUnit.SECONDS), "loop starts ticking after world_state appears")
            assertEquals(1, provided.get(), "TurnRunService materialized exactly once")
            assertTrue(ticks.get() >= 1, "tick ran after service materialization")
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

    private val WORLD_EXISTS = WorldStateAvailability { true }

    private fun provider(svc: TurnRunService): ObjectProvider<TurnRunService> =
        countingProvider(svc, AtomicInteger())

    private fun countingProvider(svc: TurnRunService, provided: AtomicInteger): ObjectProvider<TurnRunService> =
        object : ObjectProvider<TurnRunService> {
            override fun getObject(vararg args: Any?): TurnRunService {
                provided.incrementAndGet()
                return svc
            }

            override fun getObject(): TurnRunService {
                provided.incrementAndGet()
                return svc
            }

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
        private val intakeDrains: AtomicInteger? = null,
        private val intakeLatch: CountDownLatch? = null,
        private val failTicks: Boolean = false,
        /** `Error`를 던져 루프 스레드를 통째로 죽인다 — loop()의 `catch (e: Exception)`이 못 잡는다. */
        private val killLoopWithError: Boolean = false,
        initialNextRun: Instant = Instant.now().minusSeconds(5),
    ) : TurnRunService(
        world = stubWorld(),
        commandStream = RedisCommandStream(StringRedisTemplate(), "che:test", WorldId(1), startId = "0"),
        lifecycle = stubLifecycle(),
        handler = stubHandler(),
        flushExecutor = NO_FLUSH,
        realtimePublisher = RealtimePublisher(StringRedisTemplate(), "che:test", WorldId(1)),
    ) {
        // Past ⇒ due now; after the first tick push it far out so the loop idles (one observable drive).
        @Volatile private var next: Instant = initialNextRun
        private val pendingIntakeDrains = AtomicInteger(if (intakeDrains == null) 0 else 1)

        override fun nextRunTime(): Instant = next

        override fun runIntakeCommands(blockMs: Long): Int {
            val counter = intakeDrains ?: return 0
            if (pendingIntakeDrains.getAndUpdate { if (it > 0) it - 1 else 0 } <= 0) {
                return 0
            }
            counter.incrementAndGet()
            intakeLatch?.countDown()
            return 1
        }

        override fun runTick(runTime: Instant): TickResult {
            ticks.incrementAndGet()
            if (killLoopWithError) {
                throw StackOverflowError("simulated JVM Error — loop() does not catch Error")
            }
            if (failTicks) {
                throw IllegalStateException("boom")
            }
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
                    worldId = opensamguk.common.world.WorldId((TurnWorldState(
                        id = 1, currentYear = 200, currentMonth = 1,
                        tickSeconds = 3600, lastTurnTime = Instant.parse("0200-01-01T00:00:00Z"),
                    )).id),
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
