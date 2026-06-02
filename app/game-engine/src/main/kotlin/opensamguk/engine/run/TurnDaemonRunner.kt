package opensamguk.engine.run

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.SmartLifecycle
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

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
    @Value("\${opensamguk.daemon.enabled:true}") private val daemonEnabled: Boolean,
    /** How long [opensamguk.engine.redis.RedisCommandStream] blocks per read (also caps the wake latency). */
    @Value("\${opensamguk.daemon.idle-poll-ms:1000}") private val idlePollMs: Long,
) : SmartLifecycle {

    private val log = LoggerFactory.getLogger(TurnDaemonRunner::class.java)
    private val running = AtomicBoolean(false)
    @Volatile private var worker: Thread? = null

    override fun isAutoStartup(): Boolean = daemonEnabled

    /** Run late in the lifecycle (after web server / Redis / datasource are up). */
    override fun getPhase(): Int = Int.MAX_VALUE

    override fun isRunning(): Boolean = running.get()

    override fun start() {
        if (!daemonEnabled) {
            log.info("TurnDaemonRunner disabled (opensamguk.daemon.enabled=false) — loop NOT started")
            return
        }
        if (!running.compareAndSet(false, true)) return
        val service = turnRunServiceProvider.getObject()
        val t = Thread({ loop(service) }, "turn-daemon-loop").apply { isDaemon = true }
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

    private fun loop(service: TurnRunService) {
        log.info("turn-daemon-loop entering run loop")
        while (running.get() && !Thread.currentThread().isInterrupted) {
            try {
                val nextRun = service.nextRunTime()
                val now = Instant.now()
                if (now.isBefore(nextRun)) {
                    // Next turn not yet due — wait (interruptibly), bounded by idlePollMs so a shutdown
                    // or a clock change is observed promptly. No tick, no flush while idle.
                    val waitMs = minOf(Duration.between(now, nextRun).toMillis(), idlePollMs).coerceAtLeast(1)
                    Thread.sleep(waitMs)
                    continue
                }
                // Due: drain the command stream + advance the turn(s) + flush ONCE at the boundary.
                val result = service.runTick(nextRun)
                log.debug(
                    "tick at {} — generals={} cities={} logs={}",
                    result.turnCompletedAt, result.flushedGenerals, result.flushedCities, result.flushedLogs,
                )
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            } catch (e: Exception) {
                // A tick failed; log and back off one poll interval so we don't hot-spin on a hard error.
                // The world is the single source of truth — the failed flush left no partial DB write
                // (JdbcFlushExecutor runs in ONE transaction), so the next tick retries cleanly.
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
}
