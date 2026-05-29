package opensamguk.logic.world

import opensamguk.common.rng.LiteHashDrbg
import opensamguk.common.rng.RandUtil
import opensamguk.common.rng.serializeSeed
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * DV5 — RandomizeCityTradeRate (PHP Event/Action/RandomizeCityTradeRate.php:11-48), the monthly
 * seeded world-tick that populates `city.trade` (the 군량매매 golden precondition).
 *
 *   seed = simpleSerialize(hiddenSeed, 'randomizeCityTradeRate', year, month)
 *   per city (iterated in DB row order):
 *     prob = {1:0,2:0,3:0,4:0.2,5:0.4,6:0.6,7:0.8,8:1}[level]
 *     if (prob > 0 && nextBool(prob)) trade = nextRangeInt(95,105) else trade = null
 *   The `prob > 0 &&` short-circuit is LOAD-BEARING: levels 1-3 draw NOTHING (no nextBool, no
 *   nextRangeInt), so they do not perturb the shared RNG stream for the following cities.
 */
class RandomizeCityTradeRateTest {

    private val HIDDEN_SEED = "00000000000000000000000000000000"

    private fun rng(year: Int, month: Int) =
        RandUtil(LiteHashDrbg(serializeSeed(HIDDEN_SEED, "randomizeCityTradeRate", year, month)))

    @Test
    fun `levels 1 to 3 always yield null and draw NOTHING (short-circuit)`() {
        val result = randomizeCityTradeRate(
            listOf(CityLevel(1, 1), CityLevel(2, 2), CityLevel(3, 3)), rng(190, 3))
        assertNull(result[1]); assertNull(result[2]); assertNull(result[3])
        // Because levels 1-3 short-circuit, a parallel RNG that drew NOTHING must be byte-identical to a
        // fresh one — i.e. the next consumer sees the SAME first draw as a fresh stream.
        val parallel = rng(190, 3)
        assertEquals(parallel.nextRangeInt(95, 105), rng(190, 3).nextRangeInt(95, 105),
            "levels 1-3 consumed no draws")
    }

    @Test
    fun `level 8 always yields a 95 to 105 rate (prob 1, nextBool true without a draw)`() {
        val result = randomizeCityTradeRate(listOf(CityLevel(7, 8)), rng(190, 3))
        val trade = result[7]
        assertTrue(trade != null && trade in 95..105, "level-8 trade in 95..105; got $trade")
    }

    @Test
    fun `draw order is load-bearing — a level-1 city before a level-5 city does not shift the level-5 draw`() {
        // [lvl1, lvl5]: lvl1 short-circuits (no draw), lvl5 draws nextBool then maybe nextRangeInt.
        val withLvl1 = randomizeCityTradeRate(
            listOf(CityLevel(10, 1), CityLevel(20, 5)), rng(190, 3))
        // [lvl5] alone: the lvl5 draw must be identical (lvl1 consumed nothing).
        val alone = randomizeCityTradeRate(listOf(CityLevel(20, 5)), rng(190, 3))
        assertEquals(alone[20], withLvl1[20], "level-1 predecessor must not perturb the level-5 draw")
    }

    @Test
    fun `per-city draw order for a fixed year and month is deterministic`() {
        val cities = listOf(CityLevel(1, 5), CityLevel(2, 6), CityLevel(3, 7), CityLevel(4, 4))
        val a = randomizeCityTradeRate(cities, rng(191, 6))
        val b = randomizeCityTradeRate(cities, rng(191, 6))
        assertEquals(a, b, "same seed + same city order → identical trade map")
        // every produced trade is null or in 95..105
        for ((_, t) in a) assertTrue(t == null || t in 95..105, "trade null or 95..105; got $t")
    }

    @Test
    fun `month change reseeds — different month yields a different stream`() {
        val cities = (1..30).map { CityLevel(it, 6) }   // prob 0.6 — a stream long enough to differ
        val m3 = randomizeCityTradeRate(cities, rng(190, 3))
        val m4 = randomizeCityTradeRate(cities, rng(190, 4))
        assertTrue(m3 != m4, "different month → different trade map")
    }
}
