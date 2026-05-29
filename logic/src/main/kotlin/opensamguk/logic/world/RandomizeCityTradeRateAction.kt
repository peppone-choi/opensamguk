package opensamguk.logic.world

import opensamguk.logic.event.EventAction
import opensamguk.logic.event.EventActionContext
import opensamguk.logic.event.EventActionFactory
import opensamguk.logic.tick.MonthScopedRng

/**
 * P3 AREA A4, Task TR1 — the EventAction wrapper around the GREEN [randomizeCityTradeRate] pure body.
 *
 * Faithful port of PHP `Event/Action/RandomizeCityTradeRate.php:13-47`, registered as a month-1 & month-7
 * leaf (`GameConstBase.php:462/480`, priority 9000). The PHP `run(array $env)`:
 *   1. reads `$env['year']`, `$env['month']` (the F4-widened dispatch env);
 *   2. builds `$rng = new RandUtil(new LiteHashDRBG(Util::simpleSerialize(UniqueConst::$hiddenSeed,
 *      'randomizeCityTradeRate', $year, $month)))` — a FRESH self-seeded DRBG, NOT `$monthlyRng`;
 *   3. iterates `SELECT city,level FROM city` (DB PK ascending order — the iteration order IS the RNG
 *      draw order, the single highest parity risk for this unit, consolidated OQ #7);
 *   4. `UPDATE city SET trade = (prob>0 && nextBool(prob)) ? nextRangeInt(95,105) : null`.
 *
 * The pure body (`RandomizeCityTradeRate.kt`, GREEN, 5 passing tests) already owns steps 3-4 — the
 * `prob>0 && nextBool` short-circuit included. TR1 is WIRING only: source the RNG from
 * [MonthScopedRng.forTradeRate] (F5), feed the cities in the caller's order (the daemon supplies
 * ascending PK), and hand the `Map<Int,Int?>` (nulls included) to the flush sink — the daemon writes a
 * null entry as SQL NULL (F4's V4 migration dropped `city.trade` NOT NULL / DEFAULT 100 so the NULL is
 * byte-legal; G1 asserts a prob-0 city persists `trade IS NULL`).
 *
 * The Action keeps NO state; everything tick-scoped (year/month, hiddenSeed, the ascending-PK city list,
 * the trade write-set sink) rides the [RandomizeCityTradeRateContext] the daemon supplies — F2 owns the
 * base [EventActionContext]/[EventActionFactory]; this leaf only registers itself by name.
 */
class RandomizeCityTradeRateAction : EventAction {

    override fun run(ctx: EventActionContext) {
        val tradeCtx = ctx as? RandomizeCityTradeRateContext
            ?: error(
                "RandomizeCityTradeRate needs the richer RandomizeCityTradeRateContext " +
                    "(cities + hiddenSeed + trade sink) — the daemon must supply it at dispatch",
            )

        val year = (ctx.env["year"] as Number).toInt()
        val month = (ctx.env["month"] as Number).toInt()

        // (a) FRESH self-seeded RNG — the trade-rate sub-lineage, NOT the once-per-month $monthlyRng.
        val rng = MonthScopedRng.forTradeRate(tradeCtx.hiddenSeed, year, month)

        // (b) cities in the caller's order (= DB PK ascending) → (c) the GREEN body owns prob + short-circuit.
        val rates = randomizeCityTradeRate(tradeCtx.cities, rng)

        // (c) flush nulls included; the daemon writes a null entry as SQL NULL.
        tradeCtx.applyTradeRates(rates)
    }

    companion object {
        /** PHP class name (matches the F2 EventStore seed's `RandomizeCityTradeRate` row token). */
        const val NAME: String = "RandomizeCityTradeRate"

        /**
         * Register this leaf into the F2 [EventActionFactory] by name (the lone per-family touch-point on
         * F2-owned code, plan §append protocol). The seed names it with NO args, so the builder ignores
         * the (empty) arg list.
         */
        fun register(factory: EventActionFactory): EventActionFactory =
            factory.register(NAME) { RandomizeCityTradeRateAction() }
    }
}

/**
 * The richer dispatch context the RandomizeCityTradeRate leaf needs (the daemon supplies it; F2's base
 * [EventActionContext] env carries `year`/`month`/`currentEventID`, plan §append protocol).
 *
 *  - [cities] — the active `SELECT city,level FROM city` rows in DB PK ASCENDING order. The iteration
 *    order IS the RNG draw order (consolidated OQ #7); the daemon's WorldSnapshot loader provides the
 *    single shared `ORDER BY id` accessor so A2/A4/B2 inherit it (the Action never re-sorts).
 *  - [hiddenSeed] — the install-scoped 32-char hex threaded from `world_state.hidden_seed` (F4).
 *  - [applyTradeRates] — the trade write-set sink; a null entry flushes as SQL NULL.
 */
interface RandomizeCityTradeRateContext : EventActionContext {
    val cities: List<CityLevel>
    val hiddenSeed: String
    fun applyTradeRates(rates: Map<Int, Int?>)
}
