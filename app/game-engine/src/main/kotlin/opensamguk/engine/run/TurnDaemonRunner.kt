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
    /** `opensamguk.daemon.enabled` — 루프가 **떠야 하는지**. false면 의도적으로 꺼진 것이다. */
    val autoStartEnabled: Boolean = true,
    /**
     * 루프 **스레드가 실제로 살아 있으면** 기동 후 경과 **벽시계** 초, 아니면 null.
     *
     * `running` 플래그만으로는 부족하다 — [TurnDaemonRunner.stop] 없이도 루프는 빠져나온다(외부 인터럽트
     * `break`), 그리고 `catch (e: Exception)`은 `Error`(OOM/StackOverflow — 월드 전체를 RAM에 올리는
     * 데몬에서 현실적)를 안 잡아 스레드째 죽는다. 그때 `running=true`만 믿으면 이 티켓이 없애려던
     * "프로세스는 살아있는데 데몬은 죽었다" 거짓 UP을 한 단계 위에서 그대로 반복한다.
     */
    val loopUptimeSeconds: Long? = null,
    /**
     * 마지막으로 [TurnRunService.runTick]이 **성공한 실제(벽시계) 시각**으로부터 경과 초. 한 번도 성공한
     * 틱이 없으면 null.
     *
     * 게임 클럭(`clock.lastTurnTime`)이 아니라 벽시계인 것이 핵심이다 — `TurnRunService.kt:489`가
     * `setLastTurnTime(runTime)`으로 심는 값은 **게임 스케줄 시각**이라, 사고 후 캐치업 중에는 데몬이
     * 정상 가동 중인 내내 며칠 뒤처진 채로 남는다(= 지연 오탐). 벽시계는 캐치업에서 오히려 더 자주
     * 갱신되므로 오탐이 원천적으로 불가능하다.
     */
    val lastSuccessfulTickAgeSeconds: Long? = null,
    /** [TurnRunService.clockSnapshot] 조회가 실패한 경우의 예외 메시지. 설정 이상(tickSeconds<=0)과 구분된다. */
    val clockError: String? = null,
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

    /**
     * 루프 스레드 기동 **벽시계** 시각. 아직 한 번도 틱을 성공하지 못한 부팅 직후 구간의 유예 기준이다
     * ([TurnDaemonDiagnostics.lastSuccessfulTickAgeSeconds]가 null일 때 헬스가 이 값을 대신 쓴다).
     */
    @Volatile private var loopStartedAt: Instant? = null
    private val successfulTicks = AtomicLong(0)
    private val failedTicks = AtomicLong(0)
    private val consecutiveFailures = AtomicInteger(0)

    override fun isAutoStartup(): Boolean = daemonEnabled

    /** Run late in the lifecycle (after web server / Redis / datasource are up). */
    override fun getPhase(): Int = Int.MAX_VALUE

    override fun isRunning(): Boolean = running.get()

    fun diagnostics(): TurnDaemonDiagnostics {
        val activeService = service
        var clockError: String? = null
        val clock = try {
            activeService?.clockSnapshot()
        } catch (e: Exception) {
            clockError = "${e::class.qualifiedName}: ${e.message}"
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
        val now = Instant.now()
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
            autoStartEnabled = daemonEnabled,
            // 살아 있는 스레드만 uptime을 낸다. `worker == null`(스레드 생성/기동 실패)도, 죽은 스레드
            // (`isAlive=false` — loop()의 `catch (e: Exception)`이 못 잡는 `Error`)도 모두 null이 된다.
            loopUptimeSeconds = loopStartedAt
                ?.takeIf { running.get() && worker?.isAlive == true }
                ?.let { Duration.between(it, now).seconds },
            lastSuccessfulTickAgeSeconds = lastTickCompletedAt?.let { Duration.between(it, now).seconds },
            clockError = clockError,
        )
    }

    override fun start() {
        if (!daemonEnabled) {
            log.info("TurnDaemonRunner disabled (opensamguk.daemon.enabled=false) — loop NOT started")
            return
        }
        if (!running.compareAndSet(false, true)) return
        // 스레드가 **실제로 뜬 뒤에만** 기동 시각을 심는다. `Thread(...)`/`start()`가
        // `OutOfMemoryError: unable to create native thread`로 던지면 `running=true` + `worker=null`이
        // 남는데, 그때 loopStartedAt이 이미 채워져 있으면 죽은 데몬이 영구히 uptime을 뿜는다(거짓 UP).
        // 그 사이 창(마이크로초)에 들어온 [diagnostics]는 `loop_not_running`을 한 번 볼 수 있지만,
        // 헬스 폴은 10초 간격 + `start_period: 90s`라 실제로 관측되지 않는다.
        val t = Thread({ loop() }, "turn-daemon-loop").apply { isDaemon = true }
        worker = t
        t.start()
        loopStartedAt = Instant.now()
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
        loopStartedAt = null
        // `lastTickCompletedAt`은 **일부러** 리셋하지 않는다. loopStartedAt은 "루프가 지금 살아 있는가"라는
        // 생존 신호라 미가동이면 반드시 null이어야 하지만, lastTickCompletedAt은 "마지막으로 턴이 실제
        // 진행된 시각"이라는 사실 기록이다. 유지하면 장기 정지 뒤 재기동은 즉시 `turn_stalled`로 뜨는데,
        // 세계가 실제로 그만큼 안 돈 것이므로 그게 정직한 보고다. successfulTicks/failedTicks 누적 카운터를
        // 리셋하지 않는 것과도 일관된다.
        // 범위는 정직하게: 이 보존이 유예 재발급을 실제로 막는 것은 **같은 프로세스 안에서** stop()→start()가
        // 다시 불리는 경우뿐이다(어드민/테스트 경로). `SmartLifecycle.stop()`은 사실상 컨텍스트 종료 =
        // 프로세스 종료라, 컨테이너 재기동 모드에서는 어차피 새 객체가 유예를 새로 받는다.
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

                if (pauseGate.isPaused()) {
                    if (activeService.runIntakeCommands(blockMs = 1) == 0) {
                        Thread.sleep(idlePollMs)
                    }
                    continue
                }
                // OPENSAM-132: freeze intake/tick while FLUSH_RETRY / RELOAD_REQUIRED.
                // FLUSH_RETRY may resume via same-batch retryRetainedFlush (known no-commit).
                // RELOAD_REQUIRED stays blocked until process reload marks READY (no blind retry).
                val recovery = try {
                    activeService.recoverySnapshot()
                } catch (_: Exception) {
                    null
                }
                if (recovery != null && !recovery.ready) {
                    if (recovery.mode == opensamguk.engine.flush.FlushRecoveryGate.Mode.FLUSH_RETRY) {
                        try {
                            val ok = activeService.retryRetainedFlush()
                            if (ok) {
                                log.info(
                                    "turn-daemon-loop FLUSH_RETRY recovered generation={} worldId={}",
                                    recovery.generation, recovery.worldId,
                                )
                                continue
                            }
                        } catch (retryEx: Exception) {
                            log.warn(
                                "turn-daemon-loop FLUSH_RETRY still blocked generation={} reason={}",
                                recovery.generation, retryEx.message,
                            )
                        }
                    }
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
