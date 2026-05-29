package opensamguk.logic.stats

import opensamguk.logic.domain.General
import opensamguk.logic.util.clamp
import opensamguk.logic.util.phpRound
import kotlin.math.truncate

/** maxLevel = GameConst.maxLevel = 255 (PHP grand truth). */
fun getStatValue(
    general: General,
    statName: String,                 // "leadership" | "strength" | "intelligence"
    pipeline: GeneralActionPipeline,
    maxLevel: Int = 255,
    withInjury: Boolean = true,
    withIActionObj: Boolean = true,
    withStatAdjust: Boolean = true,
    useFloor: Boolean = true,
): Double {
    fun raw(name: String): Int = when (name) {
        "leadership" -> general.leadership
        "strength" -> general.strength
        "intelligence", "intel" -> general.intel
        else -> error("unknown stat $name")
    }
    var v = raw(statName).toDouble()
    if (withInjury) v *= (100 - general.injury) / 100.0
    if (withStatAdjust) {
        // cross-stat (General.php:376-382): strength += round(intel/4); intel += round(strength/4).
        // The OTHER stat is read via a RECURSIVE getStatValue with withStatAdjust=false, useFloor=false
        // (withInjury/withIActionObj forwarded), then /4 then Util::round (half-away-from-zero).
        when (statName) {
            "strength" -> v += phpRound(crossBase(general, "intelligence", pipeline, maxLevel, withInjury, withIActionObj))
            "intelligence", "intel" -> v += phpRound(crossBase(general, "strength", pipeline, maxLevel, withInjury, withIActionObj))
        }
    }
    v = clamp(v, 0.0, maxLevel.toDouble())
    if (withIActionObj) v = pipeline.onCalcStat(general, statName, v)
    v = clamp(v, 0.0, maxLevel.toDouble())
    return if (useFloor) truncate(v) else v
}

/** General.php:378-381: getStatValue(other, withInjury, withIActionObj, withStatAdjust=false, useFloor=false) / 4. */
private fun crossBase(
    general: General, other: String, pipeline: GeneralActionPipeline, maxLevel: Int,
    withInjury: Boolean, withIActionObj: Boolean,
): Double =
    getStatValue(general, other, pipeline, maxLevel,
        withInjury = withInjury, withIActionObj = withIActionObj,
        withStatAdjust = false, useFloor = false) / 4.0
