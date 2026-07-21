package opensamguk.engine.run

import opensamguk.engine.boot.WorldStateAvailability
import opensamguk.engine.status.DaemonPauseGate
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.SmartLifecycle
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

data class TurnDaemonDiagnostics(
    val serviceMaterialized: Boolean,
    val clock: TurnClockSnapshot?,
    val lastTickStartedAt: String?,
    val lastTickCompletedAt: String?,
    val lastTickFailedAt: String?,
    val lastTickError: String?,
    val successfulTicks: Long,
    val failedTicks: Long,
    val consecutiveFailures: Int,
    val recoveryMode: String? = null,
    val recoveryReason: String? = null,
    val recoveryReady: Boolean = true,
)

/**
 * F-LOOP — the production daemon loop driver: the ONE thing that finally turns the wired
 * [TurnRunService] into a RUNNING turn loop.
 *
 * **What it does.** On Spring [SmartLifecycle.start] it spins a single dedicated daemon thread that:
 *  1. resolves the next run time (`lastTurnTime + tickSeconds`, the world's own cadence — project
 *     default 1 real hour = 1 game turn, configurable via the seeded `turnterm`/`tick_seconds`),
 *  2. waits (interruptibly) until that instant arrives,
 *  3. calls [TurnRunService.runTick] ONCE — which drains the Redis command stream, runs the
 *     per-general + nation + monthly passes, and flushes EXACTLY ONCE at the clean turn boundary
 *     (the P2 contract; runTick owns drain+flush — this runner NEVER flushes mid-pass),
 *  4. loops.
 *
 * **Enable gate (`opensamguk.daemon.enabled`, default true).** [isAutoStartup] returns this flag, so:
 *  - production / prod profile → the loop auto-starts,
 *  - `@SpringBootTest` context loads (e.g. `GameEngineApplicationTests`) set it `false`, so the bean is
 *    created but the loop NEVER starts (no world drain, no Redis dependency, no flush during a context
 *    load). The slice/unit tests construct [TurnRunService] by hand and never load this runner.
 *
 * **Graceful shutdown.** [stop] flips the run flag, interrupts the worker, and joins it (bounded), so a
 * tick in flight either finishes or is interrupted cleanly between waits — never mid-flush.
 *
 * **Lazy [TurnRunService].** Injected via [ObjectProvider] so the (`@Lazy`) service + its world drain is
 * materialized only when the loop actually starts, not at context refresh.
 */
@Component
class TurnDaemonRunner(
    private val turnRunServiceProvider: ObjectProvider<TurnRunService>,
    private val worldStateAvailability: WorldStateAvailability,
    /**
     * B1b — 일시정지(동결) 게이트(`plock` 등가). [DaemonPauseGate.isPaused]가 true면 루프가 틱을 건너뛴다
     * (드레인·flush 없음). 어드민 `POST /admin/turn-daemon/pause`(락걸기)/`/resume`(락풀기)가 이 플래그를 토글한다.
     */
    private val pauseGate: DaemonPauseGate,
    @Value("\${opensamguk.daemon.enabled:true}") private val daemonEnabled: Boolean,
    /** How long [opensamguk.engine.redis.RedisCommandStream] blocks per read (also caps the wake latency). */
    @Value("\${opensamguk.daemon.idle-poll-ms:1000}") private val idlePollMs: Long,
) : SmartLifecycle {

    private val log = LoggerFactory.getLogger(TurnDaemonRunner::class.java)
    private val running = AtomicBoolean(false)
    @Volatile private var worker: Thread? = null
    @Volatile private var service: TurnRunService? = null
    @Volatile private var lastTickStartedAt: Instant? = null
    @Volatile private var lastTickCompletedAt: Instant? = null
    @Volatile private var lastTickFailedAt: Instant? = null
    @Volatile private var lastTickError: String? = null
    private val successfulTicks = AtomicLong(0)
    private val failedTicks = AtomicLong(0)
    private val consecutiveFailures = AtomicInteger(0)

    override fun isAutoStartup(): Boolean = daemonEnabled

    /** Run late in the lifecycle (after web server / Redis / datasource are up). */
    override fun getPhase(): Int = Int.MAX_VALUE

    override fun isRunning(): Boolean = running.get()

    fun diagnostics(): TurnDaemonDiagnostics {
        val activeService = service
        val clock = try {
            activeService?.clockSnapshot()
        } catch (e: Exception) {
            TurnClockSnapshot(
                currentYear = 0,
                currentMonth = 0,
                currentPhase = 0,
                currentPhaseText = "unknown",
                tickSeconds = 0,
                lastTurnTime = "unavailable",
                nextRunTime = "unavailable: ${e.message}",
            )
        }
        val recovery = try {
            activeService?.recoverySnapshot()
        } catch (_: Exception) {
            null
        }
        return TurnDaemonDiagnostics(
            serviceMaterialized = activeService != null,
            clock = clock,
            lastTickStartedAt = lastTickStartedAt?.toString(),
            lastTickCompletedAt = lastTickCompletedAt?.toString(),
            lastTickFailedAt = lastTickFailedAt?.toString(),
            lastTickError = lastTickError,
            successfulTicks = successfulTicks.get(),
            failedTicks = failedTicks.get(),
            consecutiveFailures = consecutiveFailures.get(),
            recoveryMode = recovery?.mode?.name,
            recoveryReason = recovery?.reason,
            recoveryReady = recovery?.ready ?: true,
        )
    }

    override fun start() {
        if (!daemonEnabled) {
            log.info("TurnDaemonRunner disabled (opensamguk.daemon.enabled=false) — loop NOT started")
            return
        }
        if (!running.compareAndSet(false, true)) return
        val t = Thread({ loop() }, "turn-daemon-loop").apply { isDaemon = true }
        worker = t
        t.start()
        log.info("TurnDaemonRunner started — idlePollMs={}", idlePollMs)
    }

    override fun stop() {
        if (!running.compareAndSet(true, false)) return
        worker?.let { w ->
            w.interrupt()
            try {
                w.join(Duration.ofSeconds(10).toMillis())
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        worker = null
        log.info("TurnDaemonRunner stopped")
    }

    private fun loop() {
        log.info("turn-daemon-loop entering run loop")
        var loggedEmptyWorld = false
        while (running.get() && !Thread.currentThread().isInterrupted) {
            try {
                if (service == null) {
                    if (!worldStateAvailability.hasWorld()) {
                        if (!loggedEmptyWorld) {
                            log.info("turn-daemon-loop waiting — world_state is empty; admin server creation required")
                            loggedEmptyWorld = true
                        }
                        Thread.sleep(idlePollMs)
                        continue
                    }
                    service = turnRunServiceProvider.getObject()
                    loggedEmptyWorld = false
                    log.info("turn-daemon-loop materialized TurnRunService after world_state became available")
                }

                val activeService = service
                    ?: error("TurnRunService unavailable after world_state availability check")

                // B1b — 동결(pause) 게이트(PHP plock>0). 동결 중이면 틱을 건너뛴다: 드레인도 flush도 없이
                // 한 폴 간격만 대기 → InMemoryTurnWorld가 그대로 멈춘다(턴 미진행). 락풀기(resume) 시 다음
                // 폴에서 즉시 due 판정으로 재개. nextRunTime은 진행하지 않으므로 동결 중 누적 지연은 없다.
                if (pauseGate.isPaused()) {
                    Thread.sleep(idlePollMs)
                    continue
                }
                // OPENSAM-132: freeze intake/tick while FLUSH_RETRY / RELOAD_REQUIRED.
                val recovery = try {
                    activeService.recoverySnapshot()
                } catch (_: Exception) {
                    null
                }
                if (recovery != null && !recovery.ready) {
                    log.warn(
                        "turn-daemon-loop blocked — recovery mode={} worldId={} generation={} reason={}",
                        recovery.mode, recovery.worldId, recovery.generation, recovery.reason,
                    )
                    Thread.sleep(idlePollMs)
                    continue
                }
                val nextRun = activeService.nextRunTime()
                val now = Instant.now()
                if (now.isBefore(nextRun)) {
                    if (activeService.runIntakeCommands(blockMs = 1) > 0) {
                        continue
                    }
                    // Next turn not yet due — wait (interruptibly), bounded by idlePollMs so a shutdown
                    // or a clock change is observed promptly. No tick, no flush while idle.
                    val waitMs = minOf(Duration.between(now, nextRun).toMillis(), idlePollMs).coerceAtLeast(1)
                    Thread.sleep(waitMs)
                    continue
                }
                // Due: drain the command stream + advance the turn(s) + flush ONCE at the boundary.
                lastTickStartedAt = Instant.now()
                val result = activeService.runTick(nextRun)
                lastTickCompletedAt = Instant.now()
                successfulTicks.incrementAndGet()
                consecutiveFailures.set(0)
                log.debug(
                    "tick at {} — generals={} cities={} logs={}",
                    result.turnCompletedAt, result.flushedGenerals, result.flushedCities, result.flushedLogs,
                )
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            } catch (e: Exception) {
                if (Thread.currentThread().isInterrupted || e.wasCausedByInterrupt()) {
                    Thread.currentThread().interrupt()
                    break
                }
                // A tick failed; log and back off one poll interval so we don't hot-spin on a hard error.
                // The world is the single source of truth — the failed flush left no partial DB write
                // (JdbcFlushExecutor runs in ONE transaction), so the next tick retries cleanly.
                lastTickFailedAt = Instant.now()
                lastTickError = "${e::class.qualifiedName}: ${e.message}"
                failedTicks.incrementAndGet()
                consecutiveFailures.incrementAndGet()
                log.error("turn-daemon-loop tick failed — backing off {}ms", idlePollMs, e)
                try {
                    Thread.sleep(idlePollMs)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        }
        log.info("turn-daemon-loop exited")
    }

    private fun Throwable.wasCausedByInterrupt(): Boolean =
        generateSequence(this) { it.cause }.any { it is InterruptedException }
}
