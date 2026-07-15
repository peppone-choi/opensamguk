package opensamguk.logic.util

import java.math.BigDecimal
import java.math.RoundingMode

/** PHP Util::round = intval(round($v, 0)) = half-AWAY-FROM-ZERO (PHP_ROUND_HALF_UP), returns Int. */
fun phpRound(value: Double): Int =
    BigDecimal.valueOf(value).setScale(0, RoundingMode.HALF_UP).toInt()

/**
 * PHP `Util::round($v, $pos)` = `intval(round($v, $pos))` with `assert($pos <= 0)` (Util.php:14-17).
 * `$pos < 0` rounds at the `10^|pos|` position (e.g. `-2` = nearest 100), still half-AWAY-from-zero.
 * `BigDecimal.setScale` accepts a NEGATIVE scale = round at `10^|scale|` (M5: NEVER `phpRound(v/100)*100`
 * — that double-rounds). Consumers: `AutorunNationPolicy` derived defaults (`-2`), `do징병` `round(crew-49,-2)`.
 */
fun phpRound(value: Double, pos: Int): Int {
    require(pos <= 0) { "phpRound pos must be <= 0 (PHP assert(\$pos<=0), Util.php:15): $pos" }
    return BigDecimal.valueOf(value).setScale(pos, RoundingMode.HALF_UP).toInt()
}

fun phpRoundDecimal(value: Double, scale: Int): BigDecimal {
    require(scale >= 0) { "phpRoundDecimal scale must be >= 0: $scale" }
    return BigDecimal.valueOf(value).setScale(scale, RoundingMode.HALF_UP)
}

/**
 * PHP Util::toInt = intval($v) = TRUNCATE-TOWARD-ZERO (NOT floor). The war collapse gold/rice and
 * the ConquerCity city-reset `%d` int-casts use this; distinct from [phpRound] (half-away).
 */
fun phpToInt(value: Double): Int = kotlin.math.truncate(value).toInt()

/**
 * The processWar_NG damage-loop clamp `ceil()` (`process_war.php` damage rebalance) — true ceiling
 * toward +inf, negative-safe; DISTINCT from the [phpRound] applied inside calcDamage.
 */
fun phpCeil(value: Double): Int = kotlin.math.ceil(value).toInt()

/** PHP Util::clamp / valueFit: max<min → min; else lower-clamp then upper-clamp. min/max nullable. */
fun clamp(value: Double, min: Double? = null, max: Double? = null): Double {
    if (max != null && min != null && max < min) return min
    if (min != null && value < min) return min
    if (max != null && value > max) return max
    return value
}
fun valueFit(value: Double, min: Double? = null, max: Double? = null): Double = clamp(value, min, max)

/** PHP number_format($v, 0): comma thousands grouping, no decimals (e.g. 12345 -> "12,345"). */
fun numberFormat(value: Int): String = "%,d".format(value)
