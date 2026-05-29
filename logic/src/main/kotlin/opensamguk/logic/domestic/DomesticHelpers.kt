package opensamguk.logic.domestic

import opensamguk.common.rng.RandUtil
import opensamguk.logic.domain.General
import opensamguk.logic.stats.GeneralActionPipeline
import opensamguk.logic.stats.getStatValue
import opensamguk.logic.util.clamp
import kotlin.math.pow

data class CriticalRatio(val success: Double, val fail: Double)

/** func_process.php:12-50 — NOTE: stats read with withInjury=FALSE. */
fun criticalRatioDomestic(general: General, type: String, pipeline: GeneralActionPipeline, maxLevel: Int = 255): CriticalRatio {
    val l = getStatValue(general, "leadership", pipeline, maxLevel, withInjury = false, useFloor = false)
    val s = getStatValue(general, "strength", pipeline, maxLevel, withInjury = false, useFloor = false)
    val i = getStatValue(general, "intelligence", pipeline, maxLevel, withInjury = false, useFloor = false)
    val avg = (l + s + i) / 3.0
    val statValue = when (type) { "leadership" -> l; "strength" -> s; "intel", "intelligence" -> i; else -> error("bad type $type") }
    val ratio = minOf(avg / statValue, 1.2)
    val fail = clamp((ratio / 1.2).pow(1.4) - 0.3, 0.0, 0.5)
    val success = clamp((ratio / 1.2).pow(1.5) - 0.25, 0.0, 0.5)
    return CriticalRatio(success = success, fail = fail)
}

/** func_process.php:63-71 — success draws nextRange(2.2,3.0); fail nextRange(0.2,0.4); normal=1 (no draw). */
fun criticalScoreEx(rng: RandUtil, pick: String): Double = when (pick) {
    "success" -> rng.nextRange(2.2, 3.0)
    "fail" -> rng.nextRange(0.2, 0.4)
    else -> 1.0
}

/** func_converter.php:906 — 1 + expLevel/500. */
fun getDomesticExpLevelBonus(expLevel: Int): Double = 1.0 + expLevel / 500.0

/** func_gamerule.php:942-952 — aux max_domestic_critical += score/2 (success) / =0 (non-success, per che run()).
 *  P1 emits ONLY the meta (aux) value for the General draft. The inheritance-point comparison/write
 *  (oldMaxDomesticCritical = getInheritancePoint; bump if greater) sits OUTSIDE the world/flush boundary
 *  and is a P6 seam — it is NOT computed or output here (OQ7; G4 asserts no inheritance table is written). */
fun updateMaxDomesticCritical(currentAux: Double, score: Int): Double = currentAux + score / 2.0
