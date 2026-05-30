package opensamguk.engine.turn

import opensamguk.infra.persistence.ReservedTurnRepository.ReservedTurn
import opensamguk.logic.tick.ServerClock
import java.time.Duration
import java.time.Instant

/**
 * P1 Task F3 — minimal daemon lifecycle: resolve the next run time, list the generals DUE this
 * tick, and drive [ReservedTurnHandler] once per due general.
 *
 * **Processed-count gated, NOT wall-clock** (research §1e / N5): a wall-clock budget can flush
 * mid-turn and produce a DB state the PHP golden never had. P1 drains ALL due generals in one pass
 * (no partial checkpoint) so the golden compares at a clean turn boundary. The caller flushes the
 * recorder's accumulated patches ONCE after the drain (Task F4/F5), never mid-pass.
 *
 * A "due" general is one whose [TurnGeneral.turnTime] is at/after the resolved run time of the
 * current tick. Generals are processed in ascending `turnTime`, then ascending id (a stable,
 * deterministic order so a parity replay visits them in the same sequence every run).
 */
class TurnDaemonLifecycle(
    private val world: InMemoryTurnWorld,
    private val handler: ReservedTurnHandler,
    /**
     * How the lifecycle obtains the reserved `(actionCode, argJson)` for a due general (the
     * `general_turn` ring / enqueued command). Widened from `(Int)->String` to carry the stored `arg`
     * jsonb (R-SEAM §1 / FM1) — the seed still keys on `definition.key`, so the widening only feeds the
     * resolver's arg map; targeted reserved commands (이동/발령/…) now reach the resolver with their arg.
     */
    private val reservedActionOf: (generalId: Int) -> ReservedTurn,
) {

    /** Resolve the next run time: the previous run time + the world's tick interval. */
    fun nextRunTime(): Instant {
        val state = world.getState()
        return state.lastTurnTime.plus(Duration.ofSeconds(state.tickSeconds.toLong()))
    }

    /**
     * The generals due at [runTime], in deterministic order (ascending `turnTime`, then ascending id).
     * A general is due when its `turnTime` is not after [runTime].
     */
    fun dueGenerals(runTime: Instant): List<TurnGeneral> =
        world.listGenerals()
            .filter { !it.turnTime.isAfter(runTime) }
            .sortedWith(compareBy({ it.turnTime }, { it.id }))

    /**
     * Drain ALL generals due at [runTime] through the handler, in one pass (no mid-pass flush).
     * Returns the per-general outcomes in processed order. The `year`/`month`/`date` come from the
     * world state (the turn the tick resolves).
     */
    fun runTick(runTime: Instant = nextRunTime()): List<ReservedTurnHandler.HandledTurn> {
        val state = world.getState()
        val date = formatTurnTime(runTime)
        val due = dueGenerals(runTime)
        val handled = ArrayList<ReservedTurnHandler.HandledTurn>(due.size)
        for (g in due) {
            handled.add(
                handler.handle(
                    generalId = g.id,
                    reserved = reservedActionOf(g.id),
                    year = state.currentYear,
                    month = state.currentMonth,
                    date = date,
                ),
            )
        }
        return handled
    }

    /** `HH:MM` of the run time in UTC (the `<1>date</>` log suffix; PHP logs the turn clock time). */
    private fun formatTurnTime(at: Instant): String {
        val secondsOfDay = Math.floorMod(at.epochSecond, 86_400L)
        val hh = secondsOfDay / 3_600L
        val mm = (secondsOfDay % 3_600L) / 60L
        return "%02d:%02d".format(hh, mm)
    }

    /**
     * FT3 — the `executeAllCommand` two-level month-boundary loop driver (PHP
     * `TurnExecutionHelper.php:393-517`).
     *
     * This is the OUTER orchestrator that wraps the P2 single-pass per-general drain
     * ([TurnDaemonLifecycle.runTick] / [dueGenerals], `compareBy(turnTime,id)` ==
     * `ORDER BY turntime ASC, no ASC`) and interleaves ONE [MonthlyPipeline][opensamguk.logic.tick.MonthlyPipeline]
     * run per crossed month boundary. It is PURE/in-memory — the daemon supplies the two callbacks
     * ([drain] = the per-general pass; [runMonth] = `MonthlyPipeline.runMonth`), and flushes ONCE per
     * boundary (the P2 clean-boundary contract; the monthly bulk writes are recorded as
     * `ChangeRecorder` deltas alongside the per-general deltas, preserving the single-dirty-source
     * invariant — P2 Risk #4 — across the monthly batch).
     *
     * **Clean-boundary / processed-count model (consolidated OQ #4):** PHP's wall-clock budget can
     * partial-checkpoint mid-pass; we do NOT port that. We drain ALL due generals so the golden
     * compares at a clean monthly boundary (design §11 implies a clean boundary).
     *
     * The loop:
     * - `now < turntime` → no-op (the next turn has not arrived).
     * - `isUnitedState ∈ {2,3}` → freeze the whole tick (천통 — unification settled/locked).
     * - `prevTurn = cutTurn(turntime)`, `nextTurn = addTurn(prevTurn)`; `while (nextTurn <= now)`:
     *   **L1** [drain] all generals with `turnTime < nextTurn` (the P2 pass), **L2** [runMonth] at
     *   `nextTurn` (`MonthlyPipeline.runMonth`), **L11** advance `prevTurn=nextTurn`,
     *   `nextTurn=addTurn(prevTurn)`.
     * - After the loop: a FINAL sub-month [drain] at `now` (the partial month since the last
     *   boundary), then the daemon flushes.
     *
     * @return the number of month boundaries crossed (0 when no-op / frozen).
     */
    class MonthBoundaryDriver(
        /** The per-general drain pass for all generals due strictly before the given instant. */
        private val drain: (upto: Instant) -> Unit,
        /** `MonthlyPipeline.runMonth` for the month whose boundary is the given `nextTurn`. */
        private val runMonth: (nextTurn: Instant) -> Unit,
    ) {
        fun run(turntime: Instant, now: Instant, turnTerm: Int, isUnitedState: Int): Int {
            if (now.isBefore(turntime)) return 0           // next turn not yet arrived
            if (isUnitedState == 2 || isUnitedState == 3) return 0 // 천통 freeze

            var prevTurn = ServerClock.cutTurn(turntime, turnTerm)
            var nextTurn = ServerClock.addTurn(prevTurn, turnTerm)
            var crossed = 0
            while (!nextTurn.isAfter(now)) {
                drain(nextTurn)        // L1 — drain all generals with turnTime < nextTurn
                runMonth(nextTurn)     // L2 — the monthly 6-step pipeline
                prevTurn = nextTurn    // L11 — advance the boundary
                nextTurn = ServerClock.addTurn(prevTurn, turnTerm)
                crossed++
            }
            // Final sub-month drain of the partial month since the last crossed boundary.
            drain(now)
            return crossed
        }
    }
}
