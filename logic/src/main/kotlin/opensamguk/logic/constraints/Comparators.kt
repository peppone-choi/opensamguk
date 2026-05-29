package opensamguk.logic.constraints

/**
 * CP2 — the shared comparator core for the Req*Value constraint family.
 *
 * Faithful port of the `compList` closure table duplicated verbatim across PHP ReqGeneralValue /
 * ReqCityValue / ReqNationValue / ReqDestCityValue / ReqDestNationValue (ReqNationAuxValue and
 * ReqEnvValue carry a STRIPPED variant — boolean only, errMsg always — handled in their presets).
 *
 * Returns [TRUE] on pass, else the PHP derived-reason FRAGMENT:
 *   `<` / `<=`              → `너무 많습니다.`
 *   `==` / `!=` / `===` / `!==` → `올바르지 않은 {keyNick} 입니다.`   (keyNick interpolated here)
 *   `>=` → src==1 → `없습니다` (NO trailing period); else `부족합니다.`
 *   `>`  → src==0 → `없습니다` (NO trailing period); else `부족합니다.`
 *
 * The Req*Value presets then assemble the final reason as `{keyNick}{josa-이} {fragment}` (a SPACE
 * between josa and fragment) UNLESS a non-empty errMsg overrides it.
 */

/** Sentinel "passed" marker (distinct from a reason String). */
const val COMPARE_PASS: Boolean = true

/**
 * PHP loose `==` over the (int|float) operands the constraints feed: numbers compare by value.
 * Mirrors the PHP `$target == $src` / `===` distinction — `===` additionally requires same int/float type.
 */
private fun looseEq(a: Any?, b: Any?): Boolean = when {
    a is Number && b is Number -> a.toDouble() == b.toDouble()
    else -> a == b
}

private fun strictEq(a: Any?, b: Any?): Boolean = when {
    a is Number && b is Number -> a::class == b::class && a.toDouble() == b.toDouble()
    else -> a === b || a == b
}

private fun asDouble(v: Any?): Double = (v as? Number)?.toDouble()
    ?: error("compareValues requires numeric operand for ordering comparator, got $v")

/**
 * Apply [comp] to ([target] vs [src]); returns [COMPARE_PASS] (=== true) on pass, else the derived
 * reason fragment String. [keyNick] is only consumed by the eq/neq fragments (PHP `use($keyNick)`).
 */
fun compareValues(target: Any?, src: Any?, comp: String, keyNick: String): Any = when (comp) {
    "<" -> if (asDouble(target) < asDouble(src)) COMPARE_PASS else "너무 많습니다."
    "<=" -> if (asDouble(target) <= asDouble(src)) COMPARE_PASS else "너무 많습니다."
    "==" -> if (looseEq(target, src)) COMPARE_PASS else "올바르지 않은 $keyNick 입니다."
    "!=" -> if (!looseEq(target, src)) COMPARE_PASS else "올바르지 않은 $keyNick 입니다."
    "===" -> if (strictEq(target, src)) COMPARE_PASS else "올바르지 않은 $keyNick 입니다."
    "!==" -> if (!strictEq(target, src)) COMPARE_PASS else "올바르지 않은 $keyNick 입니다."
    ">=" -> when {
        asDouble(target) >= asDouble(src) -> COMPARE_PASS
        asDouble(src) == 1.0 -> "없습니다"   // PHP: src==1 → '없습니다' (NO trailing period)
        else -> "부족합니다."
    }
    ">" -> when {
        asDouble(target) > asDouble(src) -> COMPARE_PASS
        asDouble(src) == 0.0 -> "없습니다"   // PHP: src==0 → '없습니다' (NO trailing period)
        else -> "부족합니다."
    }
    else -> error("invalid comparator $comp")
}

/** The boolean-only comparator variant used by ReqNationAuxValue / ReqEnvValue (errMsg always; no fragment). */
fun compareBool(target: Any?, src: Any?, comp: String): Boolean = when (comp) {
    "<" -> asDouble(target) < asDouble(src)
    "<=" -> asDouble(target) <= asDouble(src)
    "==" -> looseEq(target, src)
    "!=" -> !looseEq(target, src)
    "===" -> strictEq(target, src)
    "!==" -> !strictEq(target, src)
    ">=" -> asDouble(target) >= asDouble(src)
    ">" -> asDouble(target) > asDouble(src)
    else -> error("invalid comparator $comp")
}

/**
 * Util::convPercentStrToFloat — `^(\d+(\.\d+)?)%$` → matched number / 100, else null.
 * (PHP: percent suffix required; any trailing/leading junk → null.)
 */
private val PERCENT_RE = Regex("""^(\d+(\.\d+)?)%$""")
fun parsePercent(text: String): Double? {
    val m = PERCENT_RE.matchEntire(text) ?: return null
    return m.groupValues[1].toDouble() / 100.0
}
