package opensamguk.logic.stats

import opensamguk.logic.domain.General
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Port-faithful tests for the General.php::getStatValue calcCache (lines 359-403) — research Unit 8,
 * "the #1 cache trap".
 *
 *   cacheKey = "{statName}_{withInjury}_{withIActionObj}_{withStatAdjust}"   (useFloor NOT in key)
 *   - cache READ at top (362-368): hit + useFloor → Util::toInt(cached); hit + !useFloor → cached.
 *   - cache WRITE at the bottom (400) sits AFTER the useFloor early-return (396-398) →
 *     ONLY the UN-FLOORED value is ever cached. A floored miss returns toInt WITHOUT writing.
 *   - full clear on any var mutation.
 *
 * General is immutable, so the cache rides a per-resolve mutable wrapper (StatCalc) — clear = a fresh
 * wrapper / cache.clear() after any draft .copy().
 */
class GetStatValueCacheTest {

    private fun general(intel: Int, strength: Int, injury: Int, leadership: Int = 50) = General(
        id = 1, nationId = 1, cityId = 1,
        leadership = leadership, strength = strength, intel = intel, injury = injury,
        experience = 0.0, dedication = 0.0, officerLevel = 0,
        gold = 1000, rice = 1000,
    )

    /** Counts how many times the pipeline fold actually runs (i.e. cache misses). */
    private class CountingModule : GeneralActionModule {
        var calls = 0
        override fun onCalcStat(general: General, statName: String, value: Double, aux: Map<String, Any?>): Double {
            calls++
            return value
        }
    }

    @Test
    fun `two unfloored calls reuse the cache - fold runs once`() {
        val counter = CountingModule()
        val calc = StatCalc(general(intel = 90, strength = 40, injury = 0), GeneralActionPipeline(listOf(counter)))
        val a = calc.getStatValue("intelligence", useFloor = false)
        val b = calc.getStatValue("intelligence", useFloor = false)
        assertEquals(a, b)
        // withStatAdjust default true: the recursive cross-stat read (withStatAdjust=false) is a DIFFERENT
        // cacheKey, so the first resolve folds twice (self + cross). The SECOND call must add zero folds.
        val afterFirst = counter.calls
        calc.getStatValue("intelligence", useFloor = false)
        assertEquals(afterFirst, counter.calls, "second unfloored call must be a pure cache hit (no extra fold)")
    }

    @Test
    fun `floored call reads cached unfloored value and truncates`() {
        // strength=45, intel=90, injury=10 → base 40.5 + round((90*0.9)/4)=20 = 60.5 (unfloored)
        val counter = CountingModule()
        val calc = StatCalc(general(intel = 90, strength = 45, injury = 10), GeneralActionPipeline(listOf(counter)))
        val unfloored = calc.getStatValue("strength", useFloor = false)
        assertEquals(60.5, unfloored)
        val callsAfterUnfloored = counter.calls
        // useFloor=true shares the cacheKey (useFloor excluded) → reads the cached 60.5 and truncates to 60,
        // WITHOUT re-running the fold.
        val floored = calc.getStatValue("strength", useFloor = true)
        assertEquals(60.0, floored)
        assertEquals(callsAfterUnfloored, counter.calls, "floored read must hit the cache, not refold")
    }

    @Test
    fun `floored-first miss does NOT write the cache - unfloored still recomputes`() {
        // PHP: a floored miss early-returns (396-398) BEFORE the cache write (400). So a floored-first call
        // leaves the cache empty and the subsequent unfloored call is a real miss (the fold runs again).
        val counter = CountingModule()
        val calc = StatCalc(general(intel = 90, strength = 45, injury = 10), GeneralActionPipeline(listOf(counter)))
        val floored = calc.getStatValue("strength", useFloor = true)
        assertEquals(60.0, floored)
        val callsAfterFloored = counter.calls
        // not cached → unfloored recomputes (fold runs at least once more for this stat)
        val unfloored = calc.getStatValue("strength", useFloor = false)
        assertEquals(60.5, unfloored)
        assert(counter.calls > callsAfterFloored) { "floored-first miss must NOT populate the cache" }
    }

    @Test
    fun `var mutation clears the cache - next call recomputes`() {
        val counter = CountingModule()
        val calc = StatCalc(general(intel = 90, strength = 40, injury = 0), GeneralActionPipeline(listOf(counter)))
        calc.getStatValue("intelligence", useFloor = false)
        val callsBeforeMutation = counter.calls
        // a draft .copy() mutates a var → clear the cache.
        calc.applyDraft(calc.general.copy(injury = 50))
        calc.getStatValue("intelligence", useFloor = false)
        assert(counter.calls > callsBeforeMutation) { "cache must clear on var mutation (next call refolds)" }
        // and the value reflects the new injury: (90*0.5) + round((40*0.5)/4) = 45 + round(5) = 50
        assertEquals(50.0, calc.getStatValue("intelligence", useFloor = false))
    }

    @Test
    fun `cacheKey excludes useFloor - floored and unfloored share one entry`() {
        val counter = CountingModule()
        val calc = StatCalc(general(intel = 90, strength = 45, injury = 10), GeneralActionPipeline(listOf(counter)))
        // populate with an unfloored read
        calc.getStatValue("strength", useFloor = false)
        val calls = counter.calls
        // both floored and unfloored now hit the same entry without refolding
        assertEquals(60.0, calc.getStatValue("strength", useFloor = true))
        assertEquals(60.5, calc.getStatValue("strength", useFloor = false))
        assertEquals(calls, counter.calls, "floored + unfloored share one cacheKey (useFloor excluded)")
    }
}
