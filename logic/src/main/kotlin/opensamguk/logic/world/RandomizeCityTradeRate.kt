package opensamguk.logic.world

import opensamguk.common.rng.RandUtil

/** (cityId, level) input row for the trade-rate world-tick (PHP `SELECT city,level FROM city`). */
data class CityLevel(val cityId: Int, val level: Int)

/**
 * DV5 — RandomizeCityTradeRate (PHP `Event/Action/RandomizeCityTradeRate.php:11-48`), the monthly
 * seeded world-tick that populates `city.trade` (the 군량매매 golden precondition).
 *
 * The caller builds the RNG from `serializeSeed(hiddenSeed, "randomizeCityTradeRate", year, month)`
 * (PHP `Util::simpleSerialize`). Cities are iterated in the SAME order the caller supplies (PHP DB
 * row order). The result map preserves that iteration order (insertion-ordered LinkedHashMap).
 *
 * Per city:
 *   prob = {1:0,2:0,3:0,4:0.2,5:0.4,6:0.6,7:0.8,8:1}[level]   (any other level → 0)
 *   if (prob > 0 && rng.nextBool(prob)) trade = rng.nextRangeInt(95,105) else trade = null
 *
 * The `prob > 0 &&` short-circuit is LOAD-BEARING: prob-0 levels (1-3 and anything off the table)
 * draw NOTHING — no nextBool, no nextRangeInt — so they do not perturb the shared RNG stream for the
 * cities iterated after them.
 */
fun randomizeCityTradeRate(cities: List<CityLevel>, rng: RandUtil): Map<Int, Int?> {
    val result = LinkedHashMap<Int, Int?>()
    for (city in cities) {
        val prob = when (city.level) {
            4 -> 0.2; 5 -> 0.4; 6 -> 0.6; 7 -> 0.8; 8 -> 1.0
            else -> 0.0   // levels 1/2/3 (and any off-table) → 0
        }
        val trade: Int? = if (prob > 0.0 && rng.nextBool(prob)) rng.nextRangeInt(95, 105) else null
        result[city.cityId] = trade
    }
    return result
}
