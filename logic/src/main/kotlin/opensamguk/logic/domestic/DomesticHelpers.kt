package opensamguk.logic.domestic

import opensamguk.common.constants.GameConst
import opensamguk.common.rng.RandUtil
import opensamguk.logic.domain.General
import opensamguk.logic.stats.GeneralActionPipeline
import opensamguk.logic.stats.getStatValue
import opensamguk.logic.util.clamp
import opensamguk.logic.util.valueFit
import kotlin.math.ceil
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.math.truncate

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

// === level constants + conversion helpers (GameConstBase.php:18/80/95, func_converter.php:631-670) ===

/** GameConst::$upgradeLimit = 30 — the per-stat _exp threshold checkStatChange trades for a stat point. */
const val UPGRADE_LIMIT = 30
/** GameConst::$maxDedLevel = 30 — dedication level ceiling (and the 품관 inversion base). */
const val MAX_DED_LEVEL = 30
/** GameConst::$maxLevel = 255 — leadership/strength/intel ceiling AND exp-level clamp. */
const val MAX_LEVEL = 255

/**
 * func_converter.php:631-641 — exp→level.
 *   experience < 1000 : intdiv(experience, 100)            (PHP intdiv coerces the float operand to int first)
 *   else              : Util::toInt(sqrt(experience/10))   (truncate toward zero)
 * then Util::clamp(level, 0, maxLevel).
 */
fun getExpLevel(experience: Double): Int {
    val level = if (experience < 1000.0) {
        truncate(experience).toLong() / 100L          // intdiv on the truncated operand
    } else {
        truncate(sqrt(experience / 10.0)).toLong()    // Util::toInt = intval = truncate toward zero
    }
    return clamp(level.toDouble(), 0.0, MAX_LEVEL.toDouble()).toInt()
}

/**
 * func_converter.php:643-651 — dedication→level: Util::valueFit(ceil(sqrt(dedication)/10), 0, maxDedLevel).
 */
fun getDedLevel(dedication: Double): Int =
    valueFit(ceil(sqrt(dedication) / 10.0), 0.0, MAX_DED_LEVEL.toDouble()).toInt()

/**
 * func_converter.php:602-609 — dedLevel→text. 0 = '무품관'; else "{maxDedLevel - dedLevel + 1}품관"
 * (inverted: level 1 → 30품관, level 30 → 1품관).
 */
fun getDedLevelText(dedLevel: Int): String {
    if (dedLevel == 0) return "무품관"
    val dedInvLevel = MAX_DED_LEVEL - dedLevel + 1
    return "${dedInvLevel}품관"
}

/** func_converter.php:668-670 — getBillByLevel = dedLevel * 200 + 400. */
fun getBillByLevel(dedLevel: Int): Int = dedLevel * 200 + 400

/** func_converter.php:664-666 — getBill = getBillByLevel(getDedLevel(dedication)). Per-general upkeep. */
fun getBill(dedication: Double): Int = getBillByLevel(getDedLevel(dedication))

/** func_gamerule.php:942-952 — aux max_domestic_critical += score/2 (success) / =0 (non-success, per che run()).
 *  P1 emits ONLY the meta (aux) value for the General draft. The inheritance-point comparison/write
 *  (oldMaxDomesticCritical = getInheritancePoint; bump if greater) sits OUTSIDE the world/flush boundary
 *  and is a P6 seam — it is NOT computed or output here (OQ7; G4 asserts no inheritance table is written). */
fun updateMaxDomesticCritical(currentAux: Double, score: Int): Double = currentAux + score / 2.0

// === DV2 — tech-level helpers (func_converter.php:676-697). Disjoint keys (DV2 consumer only). ===

/**
 * func_converter.php:676-681 — getTechLevel = Util::valueFit(floor(tech/1000), 0, GameConst::$maxTechLevel).
 */
fun getTechLevel(tech: Double): Int =
    valueFit(kotlin.math.floor(tech / 1000.0), 0.0, GameConst.maxTechLevel.toDouble()).toInt()

/**
 * func_converter.php:684-697 — TechLimit.
 *   relYear = year - startYear
 *   relMaxTech = Util::valueFit(floor(relYear/techLevelIncYear) + initialAllowedTechLevel, 1, maxTechLevel)
 *   return getTechLevel(tech) >= relMaxTech
 */
fun techLimit(startYear: Int, year: Int, tech: Double): Boolean {
    val relYear = year - startYear
    val relMaxTech = valueFit(
        kotlin.math.floor(relYear.toDouble() / GameConst.techLevelIncYear) + GameConst.initialAllowedTechLevel,
        1.0,
        GameConst.maxTechLevel.toDouble(),
    ).toInt()
    return getTechLevel(tech) >= relMaxTech
}
