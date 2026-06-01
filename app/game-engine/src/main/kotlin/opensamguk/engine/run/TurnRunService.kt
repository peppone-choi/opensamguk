package opensamguk.engine.run

import opensamguk.engine.flush.DatabaseHooks
import opensamguk.engine.redis.RealtimePublisher
import opensamguk.engine.redis.RedisCommandStream
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.ReservedTurnHandler
import opensamguk.engine.turn.TurnDaemonLifecycle
import opensamguk.infra.persistence.FlushPayload
import opensamguk.infra.persistence.JdbcFlushExecutor
import java.time.Instant

/**
 * P1 Task F5 — the daemon-side run orchestrator (steps 3-7 daemon side, design §12).
 *
 * One tick end-to-end:
 *
 *   [RedisCommandStream.readCommands] (drain the control commands enqueued by game-api) →
 *   [TurnDaemonLifecycle] drains ALL due generals through the [ReservedTurnHandler] in one pass →
 *   [JdbcFlushExecutor.flush] writes the post-state in ONE transaction (JDBC-only, never an
 *   `EntityManager`) → [RealtimePublisher.publishTurnCompleted] signals the SSE relay.
 *
 * **Processed-count gated, NOT wall-clock** (research §1e / N5): the lifecycle drains every due
 * general in one pass and the flush fires exactly ONCE at the clean turn boundary — never mid-pass —
 * so a parity replay observes a DB state the PHP golden also produced.
 *
 * **The flush reads the [ReservedTurnHandler.recorder] — the SINGLE dirty source (design Risk #4).**
 * The handler applies the resolver's post-state to the world through the *dirty-free* apply path, so
 * the world's own dirty set is never the write path; the recorder's [RowPatch][opensamguk.engine.turn.RowPatch]
 * dirty-ids select which world rows to flush. Logs are pushed onto the world and drained from its log
 * list (the only world-dirty signal exercised in P1).
 *
 * **No `commandResult` publish in P1** (design DECISION): the events-stream `commandResult`
 * publisher+consumer is deferred (P-later). The P1 gate uses ONLY the `turnCompleted` realtime
 * pub/sub → SSE relay round-trip.
 */
class TurnRunService(
    private val world: InMemoryTurnWorld,
    private val commandStream: RedisCommandStream,
    private val lifecycle: TurnDaemonLifecycle,
    private val handler: ReservedTurnHandler,
    private val flushExecutor: JdbcFlushExecutor,
    private val realtimePublisher: RealtimePublisher,
    /** How long [RedisCommandStream.readCommands] blocks for a control command before the tick proceeds. */
    private val commandBlockMs: Long = 0,
) {

    /**
     * Routes drained intake commands (auction bid/finalize, and the P6/P7 commands that follow) to
     * their engine handlers. Built per-run against the live [world] (mirrors the sibling per-run
     * handlers — the world is per-run state, not a Spring bean). Results are currently DISCARDED: the
     * events-stream `commandResult` publish is deferred per the P1 DECISION documented above.
     */
    private val commandDispatcher = TurnDaemonCommandDispatcher(world)

    /** Outcome of one [runTick]: the resolved turns + whether a `turnCompleted` was published. */
    data class TickResult(
        val handled: List<ReservedTurnHandler.HandledTurn>,
        val flushedGenerals: Int,
        val flushedCities: Int,
        val flushedLogs: Int,
        val turnCompletedAt: String,
        val lastTurnTime: String,
    )

    /**
     * Drive ONE tick. The control commands drained from [commandStream] gate when game-api asks the
     * daemon to run; in P1 they are consumed (the stream cursor advances) and the tick proceeds.
     */
    fun runTick(runTime: Instant = lifecycle.nextRunTime()): TickResult {
        // 1. drain the control-command stream (run/pause/troopJoin/...) AND route each command to its
        //    engine handler via [commandDispatcher] (P6: the intake seam that was previously dropped).
        //    Control commands (run/pause/...) advance the cursor and return null from the dispatcher;
        //    intake commands (auction bid/finalize, …) route to their handler. Results are discarded —
        //    the `commandResult` publish is deferred (P1 DECISION). The reserved general-turn ACTIONS
        //    live in the general_turn ring (ReservedTurnRepository), NOT on this stream.
        val commands = commandStream.readCommands(commandBlockMs)
        commandDispatcher.dispatchAll(commands)

        // 2. drain ALL due generals in one pass through the handler (no mid-pass flush).
        //    FT3: this single drain is the INNER pass of the two-level executeAllCommand loop. The
        //    OUTER month-boundary loop is [TurnDaemonLifecycle.MonthBoundaryDriver]: it wraps this
        //    drain (`drain` callback) and interleaves ONE MonthlyPipeline.runMonth (`runMonth`
        //    callback) per crossed month boundary, then flushes ONCE per boundary (step 3 below) —
        //    the monthly bulk writes ride the SAME ChangeRecorder dirty source as the per-general
        //    deltas (single-dirty-source invariant, P2 Risk #4). The pipeline wiring (the concrete
        //    MonthlyPipeline + dispatcher + monthlyRng) is assembled by the consuming P3 waves
        //    (F2/F4/F5 + A1..B5); F1 lands the driver skeleton + the SEQUENTIAL contract here.
        val handled = lifecycle.runTick(runTime)

        // 3. flush the recorder's dirty rows + the world's logs in ONE transaction (JDBC-only).
        val payload = buildFlushPayload()
        flushExecutor.flush(payload)

        // 4. advance the world clock and publish the coarse turnCompleted realtime signal (only).
        val previousTurnTime = world.getState().lastTurnTime
        world.setLastTurnTime(runTime)
        val atIso = runTime.toString()
        val lastTurnTimeIso = previousTurnTime.toString()
        realtimePublisher.publishTurnCompleted(atIso, lastTurnTimeIso)

        return TickResult(
            handled = handled,
            flushedGenerals = payload.updatedGenerals.size,
            flushedCities = payload.updatedCities.size,
            flushedLogs = payload.logEntries.size,
            turnCompletedAt = atIso,
            lastTurnTime = lastTurnTimeIso,
        )
    }

    /**
     * Map the tick's accumulated state → an infra [FlushPayload], CONVERGED onto the single superset
     * builder [DatabaseHooks.toFlushPayload] (T0.3). Before this convergence `buildFlushPayload`
     * flushed ONLY general+city+logs and silently DROPPED every nation/rank/kv/diplomacy/deleted-nation
     * delta — so a nation gold change, a rank bump, a setNationMeta KV write, or a diplomacy state
     * transition ran in memory and vanished at flush. The recorder ([ReservedTurnHandler.recorder]) is
     * the lone dirty source for the dirty rows + rank + KV deltas; the world's drained [DirtyState]
     * carries created/deleted lifecycle effects + logs.
     */
    private fun buildFlushPayload(): FlushPayload {
        val dirty = world.consumeDirtyState()
        return DatabaseHooks.toFlushPayload(world, handler.recorder, dirty)
    }
}
