package opensamguk.engine.run

import opensamguk.common.rng.RandUtil
import opensamguk.engine.auction.AuctionExpiryDaemon
import opensamguk.engine.flush.DatabaseHooks
import opensamguk.engine.redis.RealtimePublisher
import opensamguk.engine.redis.RedisCommandStream
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.ReservedTurnHandler
import opensamguk.engine.turn.TurnDaemonLifecycle
import opensamguk.infra.persistence.FlushPayload
import opensamguk.infra.persistence.JdbcFlushExecutor
import opensamguk.infra.read.AuctionBidRepository
import opensamguk.infra.read.AuctionRepository
import opensamguk.logic.event.EventDispatcher
import opensamguk.logic.tick.MonthlyPipeline
import opensamguk.logic.tick.ServerClock
import java.time.Instant

/**
 * P1 Task F5 / P6 Task 6 — the daemon-side run orchestrator (steps 3-7 daemon side, design §12).
 *
 * One tick end-to-end:
 *
 *   [RedisCommandStream.readCommands] (drain the control commands enqueued by game-api) →
 *   [TurnDaemonLifecycle] drains ALL due generals through the [ReservedTurnHandler] in one pass →
 *   [JdbcFlushExecutor.flush] writes the post-state in ONE transaction (JDBC-only, never an
 *   `EntityManager`) → [RealtimePublisher.publishTurnCompleted] signals the SSE relay.
 *
 * When the [MonthlyPipeline] is wired (P6), the [TurnDaemonLifecycle.MonthBoundaryDriver]
 * interleaves ONE `runMonth` per crossed boundary between per-general drains; the flush still
 * fires exactly ONCE at the clean boundary.
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
    /** The monthly pipeline (null = fallback to general-only drain, P1-P4 behaviour). */
    private val pipeline: MonthlyPipeline<RandUtil>? = null,
    /** The dynamic-event dispatcher consumed by [MonthlyPipeline.runMonth]. */
    private val eventDispatcher: EventDispatcher? = null,
    /** How long [RedisCommandStream.readCommands] blocks for a control command before the tick proceeds. */
    private val commandBlockMs: Long = 0,
    /** JPA read repository for auction lookups (P6 T0.7). */
    private val auctionRepository: AuctionRepository? = null,
    /** JPA read repository for auction bid lookups (P6 T0.7). */
    private val auctionBidRepository: AuctionBidRepository? = null,
) {

    /**
     * Routes drained intake commands (auction bid/finalize, and the P6/P7 commands that follow) to
     * their engine handlers. Built per-run against the live [world] (mirrors the sibling per-run
     * handlers — the world is per-run state, not a Spring bean). Results are currently DISCARDED: the
     * events-stream `commandResult` publish is deferred per the P1 DECISION documented above.
     */
    private val commandDispatcher = if (auctionRepository != null && auctionBidRepository != null) {
        TurnDaemonCommandDispatcher(world, handler.recorder, auctionRepository, auctionBidRepository)
    } else {
        null
    }

    /**
     * Scans and expires auctions whose closeDate has passed. Built per-run against the live [world].
     * Runs after command dispatch and before the monthly boundary / flush.
     */
    private val auctionExpiryDaemon = if (auctionRepository != null && auctionBidRepository != null) {
        AuctionExpiryDaemon(auctionRepository, auctionBidRepository)
    } else {
        null
    }

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
     *
     * When [pipeline] and [eventDispatcher] are wired (P6), the [MonthBoundaryDriver] interleaves
     * the per-general drain with one `runMonth` per crossed boundary. The flush still fires exactly
     * ONCE after all drains and monthlies.
     */
    fun runTick(runTime: Instant = lifecycle.nextRunTime()): TickResult {
        // 1. drain the control-command stream (run/pause/troopJoin/...) AND route each command to its
        //    engine handler via [commandDispatcher] (P6: the intake seam that was previously dropped).
        //    Control commands (run/pause/...) advance the cursor and return null from the dispatcher;
        //    intake commands (auction bid/finalize, …) route to their handler. Results are discarded —
        //    the `commandResult` publish is deferred (P1 DECISION). The reserved general-turn ACTIONS
        //    live in the general_turn ring (ReservedTurnRepository), NOT on this stream.
        val commands = commandStream.readCommands(commandBlockMs)
        commandDispatcher?.dispatchAll(commands) ?: emptyList()

        // 1b. auction expiry scan (P6) — expire auctions whose closeDate has passed.
        auctionExpiryDaemon?.checkExpiredAuctions(world, handler.recorder, runTime)

        // 2. month boundary interleave (if pipeline is wired)
        val handled: List<ReservedTurnHandler.HandledTurn>
        val crossed: Int
        if (pipeline != null && eventDispatcher != null) {
            val driver = TurnDaemonLifecycle.MonthBoundaryDriver(
                drain = { upto -> lifecycle.runTick(upto) },
                runMonth = { nextTurn ->
                    val state = world.getState()
                    val startYear = (state.meta["startYear"] as? Number)?.toInt() ?: 0
                    val startTime = Instant.parse(
                        state.meta["startTime"] as? String ?: Instant.now().toString()
                    )
                    pipeline.runMonth(
                        nextTurn = nextTurn,
                        startYear = startYear,
                        startTime = startTime,
                        turnTerm = state.tickSeconds / 60,
                        oldYear = state.currentYear,
                        oldMonth = state.currentMonth,
                        dispatcher = { target, env ->
                            eventDispatcher.run(target) {
                                mutableMapOf<String, Any?>(
                                    "year" to env.year,
                                    "month" to env.month,
                                    "currentEventID" to env.currentEventID,
                                )
                            }
                        },
                    )
                },
            )
            val state = world.getState()
            val isUnitedState = state.meta["isunited"] as? Int ?: 0
            crossed = driver.run(state.lastTurnTime, runTime, state.tickSeconds / 60, isUnitedState)
            handled = emptyList() // lifecycle.runTick inside driver already handles generals
        } else {
            // Fallback: original behaviour when pipeline is not wired
            handled = lifecycle.runTick(runTime)
            crossed = 0
        }

        // 3. flush the recorder's dirty rows + the world's logs in ONE transaction (JDBC-only).
        val payload = buildFlushPayload()
        flushExecutor.flush(payload)

        // 4. advance the world calendar and publish the coarse turnCompleted realtime signal.
        val previousTurnTime = world.getState().lastTurnTime
        val state = world.getState()
        val startYear = (state.meta["startYear"] as? Number)?.toInt() ?: 0
        val startTime = Instant.parse(state.meta["startTime"] as? String ?: Instant.now().toString())
        val turnTerm = state.tickSeconds / 60
        val (newYear, newMonth) = ServerClock.turnDate(runTime, startYear, startTime, turnTerm)
        world.setCurrentDate(newYear, newMonth)
        world.setLastTurnTime(runTime)
        val atIso = runTime.toString()
        val lastTurnTimeIso = previousTurnTime.toString()
        val turnNumber = computeTurnNumber(previousTurnTime, startYear, startTime, turnTerm)
        realtimePublisher.publishTurnCompleted(atIso, lastTurnTimeIso, newYear, newMonth, turnNumber)

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

    /**
     * Compute the absolute turn number (0-based) from the install epoch.
     * Mirrors [ServerClock.turnDate] math: `num = intdiv(cutTurn - startTime, turnTerm*60)`.
     */
    private fun computeTurnNumber(
        turnTime: Instant,
        startYear: Int,
        startTime: Instant,
        turnTerm: Int,
    ): Int {
        val curturn = ServerClock.cutTurn(turnTime, turnTerm)
        val num = Math.floorDiv(curturn.epochSecond - startTime.epochSecond, turnTerm.toLong() * 60L)
        return num.toInt()
    }
}
