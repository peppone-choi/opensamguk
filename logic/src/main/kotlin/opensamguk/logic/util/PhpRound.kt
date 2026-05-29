package opensamguk.logic.util

import java.math.BigDecimal
import java.math.RoundingMode

/** PHP Util::round = intval(round($v, 0)) = half-AWAY-FROM-ZERO (PHP_ROUND_HALF_UP), returns Int. */
fun phpRound(value: Double): Int =
    BigDecimal.valueOf(value).setScale(0, RoundingMode.HALF_UP).toInt()

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
