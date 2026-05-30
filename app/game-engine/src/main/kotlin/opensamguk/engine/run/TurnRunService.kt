package opensamguk.engine.run

import opensamguk.engine.redis.RealtimePublisher
import opensamguk.engine.redis.RedisCommandStream
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.LogEntryDraft
import opensamguk.engine.turn.PerTurnOverlay
import opensamguk.engine.turn.ReservedTurnHandler
import opensamguk.engine.turn.TurnDaemonLifecycle
import opensamguk.infra.persistence.FlushPayload
import opensamguk.infra.persistence.JdbcFlushExecutor
import opensamguk.infra.persistence.LogRow
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
        // 1. drain the control-command stream (run/pause/troopJoin/...). P1 only advances the cursor;
        //    the reserved general-turn ACTIONS live in the general_turn ring (ReservedTurnRepository),
        //    not on this stream, and are read per-general inside the lifecycle's reservedActionOf.
        commandStream.readCommands(commandBlockMs)

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
     * Map the tick's accumulated state → an infra [FlushPayload]. The dirty GENERAL/CITY rows come
     * from the [ReservedTurnHandler.recorder] (the SINGLE dirty source); each dirty id resolves to the
     * world's already-applied post-state row, converted with the SAME [PerTurnOverlay] engine→logic
     * mapping the flush executor expects. Logs are drained from the world (the only world-dirty signal
     * in P1) and finalized with the tick's year/month.
     */
    private fun buildFlushPayload(): FlushPayload {
        val state = world.getState()

        val updatedGenerals = handler.recorder.dirtyGeneralIds()
            .mapNotNull { world.getGeneralById(it) }
            .map { PerTurnOverlay.toLogicGeneral(it) }
        val updatedCities = handler.recorder.dirtyCityIds()
            .mapNotNull { world.getCityById(it) }
            .map { PerTurnOverlay.toLogicCity(it) }

        val dirty = world.consumeDirtyState()
        val logEntries = dirty.logs.map { toLogRow(it, state.currentYear, state.currentMonth) }

        return FlushPayload(
            worldStateUpdate = linkedMapOf(
                "id" to state.id,
                "current_year" to state.currentYear,
                "current_month" to state.currentMonth,
            ),
            updatedGenerals = updatedGenerals,
            updatedCities = updatedCities,
            logEntries = logEntries,
        )
    }

    /**
     * Finalize an engine [LogEntryDraft] → an infra [LogRow]: stamp year/month from world state (the
     * draft does not carry them) and uppercase `scope`/`category` to the PG enum literals the INSERT
     * casts to. Mirrors `DatabaseHooks.toLogRow` (kept local so `:run` has no `:flush` write-path dep).
     */
    private fun toLogRow(draft: LogEntryDraft, year: Int, month: Int): LogRow = LogRow(
        scope = draft.scope.uppercase(),
        category = draft.category.uppercase(),
        text = draft.text,
        year = year,
        month = month,
        subType = draft.subType,
        generalId = draft.generalId,
        nationId = draft.nationId,
        userId = draft.userId,
        meta = draft.meta ?: linkedMapOf(),
    )
}
