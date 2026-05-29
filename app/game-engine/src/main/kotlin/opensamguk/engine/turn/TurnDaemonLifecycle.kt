package opensamguk.engine.turn

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
    /** How the lifecycle obtains the reserved action code for a due general (the ring / enqueued command). */
    private val reservedActionOf: (generalId: Int) -> String,
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
                    actionCode = reservedActionOf(g.id),
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
}
