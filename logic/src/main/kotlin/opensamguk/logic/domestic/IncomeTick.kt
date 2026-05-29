package opensamguk.logic.domestic

import opensamguk.logic.domain.City
import opensamguk.logic.domain.General
import opensamguk.logic.stats.GeneralActionModule
import opensamguk.logic.stats.GeneralActionPipeline
import opensamguk.logic.util.phpRound
import kotlin.math.pow

/**
 * The semi-annual INCOME-TICK — a faithful port of the income aggregation in `func_time_event.php`
 * (research Unit 12). All float math is transcribed verbatim; the ONLY rounding points are:
 *   1. each PER-CITY income is `Util::round`-ed to an int (after the nation-type income fold), and
 *   2. `getOutcome` rounds the SUM-of-bills once.
 * The `getGoldIncome/getRiceIncome/getWallIncome` aggregate does NOT round again after the tax scale —
 * it sums the rounded per-city ints THEN multiplies by `taxRate/20`, returning a float (PHP returns the
 * un-rounded product; the caller rounds at the debit site).
 *
 * The income fold uses the NATION-TYPE source ONLY (the [GeneralActionPipeline.nationIncomeFold] path,
 * research Unit 12 asymmetry); it never goes through the acting general's full 9-source stack. A null
 * nation-type source folds as identity.
 *
 * The capital factor is `1 + 1/(3*nationLevel)` for ALL THREE streams: PHP writes `1 + (1/3/$nationLevel)`
 * for gold/rice (which evaluates left-to-right as `(1/3)/level = 1/(3*level)`) and `1 + 1/(3*$nationLevel)`
 * for wall — the two are arithmetically identical.
 */

/** func_time_event.php:88-104 — per-city GOLD income (commerce-driven), rounded after the income fold. */
fun calcCityGoldIncome(
    city: City,
    officerCnt: Int,
    isCapital: Boolean,
    nationLevel: Int,
    nationType: GeneralActionModule?,
    pipeline: GeneralActionPipeline,
): Int {
    if (city.supplyState == 0) return 0
    val trustRatio = city.trust / 200 + 0.5            // 0.5 ~ 1
    var income = city.population.toDouble() * city.commerce / city.commerceMax * trustRatio / 30
    income *= 1 + city.security.toDouble() / city.securityMax / 10
    income *= 1.05.pow(officerCnt)
    if (isCapital) income *= 1 + (1.0 / 3 / nationLevel)
    return phpRound(pipeline.nationIncomeFold(nationType, "gold", income))
}

/** func_time_event.php:106-122 — per-city RICE income (agriculture-driven), rounded after the income fold. */
fun calcCityRiceIncome(
    city: City,
    officerCnt: Int,
    isCapital: Boolean,
    nationLevel: Int,
    nationType: GeneralActionModule?,
    pipeline: GeneralActionPipeline,
): Int {
    if (city.supplyState == 0) return 0
    val trustRatio = city.trust / 200 + 0.5
    var income = city.population.toDouble() * city.agriculture / city.agricultureMax * trustRatio / 30
    income *= 1 + city.security.toDouble() / city.securityMax / 10
    income *= 1.05.pow(officerCnt)
    if (isCapital) income *= 1 + (1.0 / 3 / nationLevel)
    return phpRound(pipeline.nationIncomeFold(nationType, "rice", income))
}

/** func_time_event.php:124-139 — per-city WALL rice income (def*wall/wall_max/3), rounded after the fold. */
fun calcCityWallRiceIncome(
    city: City,
    officerCnt: Int,
    isCapital: Boolean,
    nationLevel: Int,
    nationType: GeneralActionModule?,
    pipeline: GeneralActionPipeline,
): Int {
    if (city.supplyState == 0) return 0
    var wallIncome = city.defense.toDouble() * city.wall / city.wallMax / 3
    wallIncome *= 1 + city.security.toDouble() / city.securityMax / 10
    wallIncome *= 1.05.pow(officerCnt)
    if (isCapital) wallIncome *= 1 + 1.0 / (3 * nationLevel)
    return phpRound(pipeline.nationIncomeFold(nationType, "rice", wallIncome))
}

/**
 * func_time_event.php:78-86 — per-city WAR-GOLD income (P3 IN1, the ONLY income fn not yet ported).
 *
 * ```
 * if($rawCity['supply'] == 0){ return 0; }
 * $warIncome = $rawCity['dead'] / 10;
 * $warIncome = Util::round($nationType->onCalcNationalIncome('gold', $warIncome));
 * return $warIncome;
 * ```
 * `supply==0 → 0` (short-circuit BEFORE the fold); else `phpRound` of the NATION-TYPE 'gold' fold over
 * `dead/10.0` (a null nation type folds as identity). This is the per-city term `ProcessWarIncome` sums
 * across a nation's cities (`getWarGoldIncome`, func_time_event.php:166-175).
 */
fun calcCityWarGoldIncome(
    city: City,
    nationType: GeneralActionModule?,
    pipeline: GeneralActionPipeline,
): Int {
    if (city.supplyState == 0) return 0
    val warIncome = city.dead / 10.0
    return phpRound(pipeline.nationIncomeFold(nationType, "gold", warIncome))
}

/**
 * func_time_event.php:141-164 — national GOLD income: SUM the per-city rounded ints, THEN `*= taxRate/20`
 * (NO final round). Empty city list → 0. The capital city (`capitalId`) gets the capital factor.
 */
fun getGoldIncome(
    cityList: List<City>,
    capitalId: Int,
    nationLevel: Int,
    taxRate: Double,
    nationType: GeneralActionModule?,
    pipeline: GeneralActionPipeline,
    officerCntByCity: Map<Int, Int> = emptyMap(),
): Double {
    if (cityList.isEmpty()) return 0.0
    var cityIncome = 0
    for (c in cityList) {
        cityIncome += calcCityGoldIncome(c, officerCntByCity[c.id] ?: 0, capitalId == c.id, nationLevel, nationType, pipeline)
    }
    return cityIncome * (taxRate / 20)
}

/** func_time_event.php:187-211 — national RICE income (agriculture); same sum-then-scale shape. */
fun getRiceIncome(
    cityList: List<City>,
    capitalId: Int,
    nationLevel: Int,
    taxRate: Double,
    nationType: GeneralActionModule?,
    pipeline: GeneralActionPipeline,
    officerCntByCity: Map<Int, Int> = emptyMap(),
): Double {
    if (cityList.isEmpty()) return 0.0
    var cityIncome = 0
    for (c in cityList) {
        cityIncome += calcCityRiceIncome(c, officerCntByCity[c.id] ?: 0, capitalId == c.id, nationLevel, nationType, pipeline)
    }
    return cityIncome * (taxRate / 20)
}

/** func_time_event.php:213-237 — national WALL rice income; same sum-then-scale shape. */
fun getWallIncome(
    cityList: List<City>,
    capitalId: Int,
    nationLevel: Int,
    taxRate: Double,
    nationType: GeneralActionModule?,
    pipeline: GeneralActionPipeline,
    officerCntByCity: Map<Int, Int> = emptyMap(),
): Double {
    if (cityList.isEmpty()) return 0.0
    var cityIncome = 0
    for (c in cityList) {
        cityIncome += calcCityWallRiceIncome(c, officerCntByCity[c.id] ?: 0, capitalId == c.id, nationLevel, nationType, pipeline)
    }
    return cityIncome * (taxRate / 20)
}

/**
 * func_time_event.php:239-249 — total upkeep: SUM `getBill(dedication)` over the nation's generals, THEN
 * `Util::round(sum * billRate / 100)`. `dedications` are the generals' dedication values (PHP iterates
 * `$general['dedication']`).
 */
fun getOutcome(billRate: Double, dedications: List<Double>): Int {
    var outcome = 0
    for (d in dedications) outcome += getBill(d)
    return phpRound(outcome * billRate / 100)
}

/**
 * The strategic-command DOUBLE-ROUND consumer pattern (research Unit 12; e.g.
 * `che_의병모집.php:70-74`): the consumer rounds the base delay to an int (`Util::round(...)`) and THEN
 * folds it through `onCalcStrategic('delay', nextTerm)`, where 음양가/종횡가/평만지장도 round AGAIN. Two
 * rounding points. A pipeline with no strategic module folds as identity → just the base round.
 */
fun calcStrategicDelay(
    baseDelay: Double,
    turnType: String,
    general: General,
    pipeline: GeneralActionPipeline,
): Int {
    val rounded = phpRound(baseDelay)
    return pipeline.onCalcStrategic(general, turnType, "delay", rounded.toDouble()).toInt()
}
