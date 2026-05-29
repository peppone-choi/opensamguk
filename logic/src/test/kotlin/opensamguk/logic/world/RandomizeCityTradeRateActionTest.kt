package opensamguk.logic.world

import opensamguk.logic.event.EventAction
import opensamguk.logic.event.EventActionContext
import opensamguk.logic.event.EventActionFactory
import opensamguk.logic.tick.MonthScopedRng
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * P3 AREA A4, Task TR1 — wire the GREEN `randomizeCityTradeRate` pure body as a month 1&7 EventAction.
 *
 * Port target (PHP grand truth): `Event/Action/RandomizeCityTradeRate.php:13-47` +
 * `GameConstBase.php:462/480` (month 1&7, priority 9000). The pure body (`RandomizeCityTradeRate.kt`) is
 * already GREEN (5 passing tests); TR1 is WIRING only:
 *   (a) build a FRESH `rng = MonthScopedRng.forTradeRate(hidden, year, month)` (F5) at tick time;
 *   (b) supply cities in EXACT DB row order = `SELECT city,level FROM city` PK ascending (the iteration
 *       order IS the RNG draw order — the single highest parity risk for this unit, consolidated OQ #7);
 *   (c) flush the returned `Map<Int,Int?>` (incl. nulls) to `city.trade` (the daemon writes SQL NULL).
 *
 * The Action reads `year`/`month` from the dispatch env (F4-widened `WorldEnvBuilder.envMap`); the richer
 * tick concerns (hiddenSeed, the ascending-PK city list, the trade write-set sink) ride a
 * [RandomizeCityTradeRateContext] that the daemon supplies and the dispatcher's base env carries. The
 * `prob > 0 && nextBool` short-circuit is GREEN — not re-tested here.
 */
class RandomizeCityTradeRateActionTest {

    private val hidden = "0".repeat(32) // 32-zero unit-test placeholder (G1 swaps the live config hex)

    /** A recording stub of the richer tick context: a fixed (year,month) env + ascending-PK cities + a sink. */
    private class RecordingCtx(
        year: Int,
        month: Int,
        override val hiddenSeed: String,
        override val cities: List<CityLevel>,
    ) : RandomizeCityTradeRateContext {
        override val env: Map<String, Any?> = linkedMapOf("year" to year, "month" to month, "currentEventID" to null)
        var applied: Map<Int, Int?>? = null
        override fun applyTradeRates(rates: Map<Int, Int?>) {
            applied = rates
        }
    }

    /** prob-positive cities (lv4..8) + prob-0 cities (lv1..3) for the short-circuit / draw-order checks. */
    private fun sampleCities(): List<CityLevel> = listOf(
        CityLevel(1, 1),  // prob 0
        CityLevel(2, 4),  // prob 0.2
        CityLevel(3, 5),  // prob 0.4
        CityLevel(7, 8),  // prob 1.0  (out of contiguous order on purpose → ascending-PK is what matters)
        CityLevel(9, 2),  // prob 0
    )

    @Test
    fun `run produces the same per-city map as the GREEN body for the forTradeRate seed`() {
        val cities = sampleCities()
        val ctx = RecordingCtx(year = 200, month = 1, hiddenSeed = hidden, cities = cities)

        RandomizeCityTradeRateAction().run(ctx)

        // The oracle: the GREEN body fed the SAME forTradeRate RNG over the SAME ordered city list.
        val oracle = randomizeCityTradeRate(cities, MonthScopedRng.forTradeRate(hidden, 200, 1))
        assertEquals(oracle, ctx.applied, "Action must delegate to the GREEN body with forTradeRate(hidden,year,month)")
    }

    @Test
    fun `null trade is flushed as a null map entry (not coerced to 100)`() {
        // A lone prob-0 city draws NOTHING → trade stays null; the sink must receive null, never a default.
        val cities = listOf(CityLevel(5, 3)) // prob 0
        val ctx = RecordingCtx(year = 200, month = 1, hiddenSeed = hidden, cities = cities)

        RandomizeCityTradeRateAction().run(ctx)

        val applied = ctx.applied!!
        assertTrue(applied.containsKey(5), "prob-0 city must still appear in the flush map")
        assertNull(applied[5], "prob-0 city trade must flush as NULL, not coerced to 100")
    }

    @Test
    fun `month 1 vs month 7 draw different seeds`() {
        val cities = sampleCities()
        val jan = RecordingCtx(year = 200, month = 1, hiddenSeed = hidden, cities = cities)
        val jul = RecordingCtx(year = 200, month = 7, hiddenSeed = hidden, cities = cities)

        RandomizeCityTradeRateAction().run(jan)
        RandomizeCityTradeRateAction().run(jul)

        assertNotEquals(jan.applied, jul.applied, "month 1 and month 7 use distinct forTradeRate seeds → distinct draws")
    }

    @Test
    fun `city iteration order is the ascending-PK draw order`() {
        // Same cities, but supplied in REVERSED order → a different draw stream → a different result map
        // for the prob-positive cities. Proves the Action consumes the caller's order verbatim (the
        // daemon supplies ascending PK; G1 is the iteration-order parity gate).
        val ascending = sampleCities().sortedBy { it.cityId }
        val reversed = ascending.reversed()

        val asc = RecordingCtx(year = 200, month = 1, hiddenSeed = hidden, cities = ascending)
        val rev = RecordingCtx(year = 200, month = 1, hiddenSeed = hidden, cities = reversed)

        RandomizeCityTradeRateAction().run(asc)
        RandomizeCityTradeRateAction().run(rev)

        // The Action must NOT internally re-sort: feeding reversed cities yields the reversed-iteration draw.
        val ascOracle = randomizeCityTradeRate(ascending, MonthScopedRng.forTradeRate(hidden, 200, 1))
        val revOracle = randomizeCityTradeRate(reversed, MonthScopedRng.forTradeRate(hidden, 200, 1))
        assertEquals(ascOracle, asc.applied)
        assertEquals(revOracle, rev.applied)
    }

    @Test
    fun `leaf registers into the F2 factory by name and is dispatched as a no-arg action`() {
        val factory = EventActionFactory()
        RandomizeCityTradeRateAction.register(factory)
        assertTrue(factory.has("RandomizeCityTradeRate"), "leaf must register under its PHP class name")

        // The EventStore seed names it with NO args (month 1 & 7 rows) — create() must succeed with an empty arg list.
        val action: EventAction = factory.create(opensamguk.logic.event.RawAction("RandomizeCityTradeRate", emptyList()))
        assertTrue(action is RandomizeCityTradeRateAction)

        // And the dispatched leaf runs against the richer context.
        val cities = sampleCities()
        val ctx = RecordingCtx(year = 200, month = 7, hiddenSeed = hidden, cities = cities)
        action.run(ctx)
        assertEquals(
            randomizeCityTradeRate(cities, MonthScopedRng.forTradeRate(hidden, 200, 7)),
            ctx.applied,
        )
    }

    @Test
    fun `running against a base context that is not a trade-rate context throws (daemon must supply the richer ctx)`() {
        val bareEnv = object : EventActionContext {
            override val env: Map<String, Any?> = linkedMapOf("year" to 200, "month" to 1)
        }
        var threw = false
        try {
            RandomizeCityTradeRateAction().run(bareEnv)
        } catch (e: IllegalStateException) {
            threw = true
        }
        assertTrue(threw, "the Action needs the richer RandomizeCityTradeRateContext (cities + sink + hiddenSeed)")
    }
}
