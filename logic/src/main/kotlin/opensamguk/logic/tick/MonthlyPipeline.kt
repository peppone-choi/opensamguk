package opensamguk.logic.tick

import opensamguk.logic.event.EventTarget
import java.time.Instant

/**
 * The lazily-built environment a [MonthlyPipeline] hands to the [EventDispatcher] / hooks. It carries
 * the date the events should observe (PreMonth sees the OLD date; Month sees the NEW one) plus the
 * `currentEventID` slot the dispatcher injects per row (F2/F4 widen this; the contract field is here
 * so F1 lands first).
 */
data class MonthlyEnv(
    val year: Int,
    val month: Int,
    val currentEventID: Int = 0,
)

/**
 * The dynamic-event dispatcher CONTRACT (owned by F1, implemented by F2's `EventDispatcher`). The
 * pipeline calls it twice per month: once for [EventTarget.PRE_MONTH] (OLD date) and once for
 * [EventTarget.MONTH] (NEW date). The return is discarded — side-effects come from Action bodies.
 */
fun interface EventDispatcher {
    fun run(target: EventTarget, env: MonthlyEnv)
}

/** Builds the month-scoped RNG (F5's `MonthScopedRng`). Called ONCE per month, before PreMonth. */
fun interface MonthlyRngFactory<R> {
    operator fun invoke(year: Int, month: Int): R
}

/** `preUpdateMonthly` hook (B1): the P1-P7 ordered side-effect set. Returns false to ABORT the tick. */
fun interface PreUpdateMonthly {
    fun run(): Boolean
}

/** `postUpdateMonthly` hook (B2): the Q1-Q17 set. Receives the ONE month-scoped RNG. */
fun interface PostUpdateMonthly<R> {
    fun run(monthlyRng: R)
}

/** `checkStatistic` hook: fires once per game-year (when the NEW month is January). */
fun interface CheckStatistic {
    fun run()
}

/** Recomputes `(year, month)` from the advanced `nextTurn` (F1's [ServerClock.turnDate]). */
fun interface MonthlyClock {
    fun turnDate(nextTurn: Instant, startTime: Instant): Pair<Int, Int>
}

/**
 * FT2 — the 6-step SEQUENTIAL monthly interleave orchestrator.
 *
 * Faithful port of PHP `TurnExecutionHelper.php:461-481` (consolidated OQ #1 RESOLVED). The order is
 * itself a parity target (the G1 log-sequence gate), so the steps are fixed and order-preserving:
 *
 * ```
 *   L4  monthlyRng = monthlyRngFactory(year, month)        // built ONCE, pre-advance
 *   L5  dispatcher.run(PRE_MONTH, env(OLD year/month))     // PreMonth events see the OLD date
 *   L6  if (!preUpdateMonthly.run()) return                // false aborts the tick (after unlock)
 *   L7  (year, month) = clock.turnDate(nextTurn)           // advance the calendar BETWEEN batches
 *   L8  if (month == 1) checkStatistic.run()               // year-boundary statistics
 *   L9  dispatcher.run(MONTH, env(NEW year/month))         // Month events see the NEW date
 *   L10 postUpdateMonthly.run(monthlyRng)                  // the ONLY consumer of monthlyRng
 * ```
 *
 * **PURE / in-memory** — no IO. The daemon (FT3) drives drain → pipeline → flush. `monthlyRng` is
 * created exactly once and reaches ONLY [postUpdateMonthly]; the Month dispatcher receives NO rng
 * (events needing RNG self-seed their own DRBG, plan §"Three RNG lineages").
 *
 * Generic over the RNG type `R` so F5 can supply its concrete `MonthScopedRng` without F1 depending
 * on F5 (the FT2 thin-interface contract).
 */
class MonthlyPipeline<R>(
    private val monthlyRngFactory: MonthlyRngFactory<R>,
    private val clock: MonthlyClock,
    private val preUpdateMonthly: PreUpdateMonthly,
    private val checkStatistic: CheckStatistic,
    private val postUpdateMonthly: PostUpdateMonthly<R>,
) {

    /**
     * Run ONE month between per-general drains. `oldYear`/`oldMonth` are the calendar BEFORE this
     * month advances (what PreMonth observes); [clock] recomputes the NEW date from [nextTurn].
     */
    fun runMonth(
        nextTurn: Instant,
        startYear: Int,
        startTime: Instant,
        turnTerm: Int,
        oldYear: Int,
        oldMonth: Int,
        dispatcher: EventDispatcher,
    ) {
        // L4 — month-scoped RNG built ONCE, before the PreMonth batch (pre-advance).
        val monthlyRng = monthlyRngFactory(oldYear, oldMonth)

        // L5 — PreMonth events fire with the OLD year/month (turnDate has NOT run yet).
        dispatcher.run(EventTarget.PRE_MONTH, MonthlyEnv(oldYear, oldMonth))

        // L6 — preUpdateMonthly; false aborts the whole tick (PHP throws after unlock).
        if (!preUpdateMonthly.run()) return

        // L7 — advance the calendar BETWEEN the two event batches.
        val (newYear, newMonth) = clock.turnDate(nextTurn, startTime)

        // L8 — year-boundary statistics (only when the NEW month is January).
        if (newMonth == 1) checkStatistic.run()

        // L9 — Month events fire with the NEW year/month.
        dispatcher.run(EventTarget.MONTH, MonthlyEnv(newYear, newMonth))

        // L10 — postUpdateMonthly is the ONLY consumer of the month-scoped RNG.
        postUpdateMonthly.run(monthlyRng)
    }

    companion object {
        /**
         * The month boundaries the daemon must catch up across (delegates to [ServerClock]). Each
         * element is a `nextTurn` at which [runMonth] fires; the daemon iterates them between drains.
         */
        fun monthBoundaries(prevTurn: Instant, now: Instant, turnTerm: Int): List<Instant> =
            ServerClock.monthBoundaries(prevTurn, now, turnTerm)
    }
}
