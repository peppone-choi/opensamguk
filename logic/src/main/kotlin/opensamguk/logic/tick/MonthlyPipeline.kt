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
// open: 방어용. 현재 배선은 엔진 DaemonLoopConfig.turnRunService가 이 파이프라인을 per-run으로 직접 생성하므로
// Spring 프록시가 끼지 않는다. 다만 과거 EngineEventConfig가 @Bean @Lazy로 노출했을 때 Spring이 CGLIB 클래스
// 프록시를 만들었고(생성자 없이 Objenesis로 shell 생성 → 필드 전부 null), final 메서드는 CGLIB이 인터셉트하지
// 못해 null-필드 shell에서 직접 실행 → monthlyRngFactory NPE → 턴 데몬 clock 동결(2026-06-05 prod 회귀).
// 누군가 다시 @Lazy 빈으로 노출하더라도 같은 동결이 재발하지 않도록 class + 호출 메서드(runMonth)를 open으로
// 두고 MonthlyPipelineLazyProxyTest가 이 위임을 가드한다(동작 불변, Spring 의존 없음).
open class MonthlyPipeline<R>(
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
    // open: @Lazy CGLIB 프록시가 이 메서드를 인터셉트해 실제 빈으로 위임할 수 있어야 한다(클래스 주석 ⚠️ 참조).
    open fun runMonth(
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
