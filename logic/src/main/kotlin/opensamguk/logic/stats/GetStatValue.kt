package opensamguk.logic.stats

import opensamguk.logic.domain.General
import opensamguk.logic.util.clamp
import opensamguk.logic.util.phpRound
import kotlin.math.truncate

/**
 * Per-resolve stat calculator with a calcCache — a faithful port of General.php::getStatValue
 * (lines 359-403), research Unit 8.
 *
 * `General` is an immutable data class, so the cache cannot ride the entity. Instead it rides this
 * per-resolve wrapper (PHP attaches `$this->calcCache` to the mutable General object). A var mutation
 * (`applyDraft(general.copy(...))`) swaps in the new snapshot and clears the cache — matching PHP, which
 * full-clears `calcCache` on any setVar.
 *
 *   cacheKey = "{statName}_{withInjury}_{withIActionObj}_{withStatAdjust}"  (useFloor is NOT in the key)
 *   - READ at the top: a hit returns Util::toInt(cached) when useFloor else the cached unfloored value.
 *   - WRITE at the bottom sits AFTER the useFloor early-return → ONLY the un-floored value is ever cached;
 *     a floored-first miss returns toInt(...) WITHOUT writing.
 */
class StatCalc(
    general: General,
    private val pipeline: GeneralActionPipeline,
    private val maxLevel: Int = 255,   // GameConst.maxLevel = 255 (PHP grand truth)
) {
    var general: General = general
        private set

    private val calcCache: MutableMap<String, Double> = HashMap()

    /** Mutate a var (PHP setVar) → full cache clear, then carry on with the new snapshot. */
    fun applyDraft(updated: General) {
        general = updated
        calcCache.clear()
    }

    fun getStatValue(
        statName: String,                 // "leadership" | "strength" | "intelligence"|"intel"
        withInjury: Boolean = true,
        withIActionObj: Boolean = true,
        withStatAdjust: Boolean = true,
        useFloor: Boolean = true,
    ): Double {
        val cKey = "${statName}_${withInjury}_${withIActionObj}_${withStatAdjust}"
        // --- cache READ (General.php:361-368) ---
        val cached = calcCache[cKey]
        if (cached != null) {
            return if (useFloor) truncate(cached) else cached
        }

        var v = raw(statName).toDouble()                       // 370
        if (withInjury) v *= (100 - general.injury) / 100.0    // 372-374
        if (withStatAdjust) {                                   // 376-382
            // cross-stat: the OTHER stat via a RECURSIVE resolve (withStatAdjust=false, useFloor=false,
            // withInjury/withIActionObj forwarded) /4 then Util::round (half-away-from-zero). The recursion
            // goes through THIS StatCalc so it shares (and populates) the same cache.
            when (statName) {
                "strength" -> v += phpRound(crossBase("intelligence", withInjury, withIActionObj))
                "intelligence", "intel" -> v += phpRound(crossBase("strength", withInjury, withIActionObj))
            }
        }
        v = clamp(v, 0.0, maxLevel.toDouble())                 // 384
        if (withIActionObj) v = pipeline.onCalcStat(general, statName, v)  // 386-392
        v = clamp(v, 0.0, maxLevel.toDouble())                 // 394

        // --- useFloor early-return BEFORE the cache write (General.php:396-398) ---
        if (useFloor) {
            return truncate(v)
        }

        // --- cache WRITE (General.php:400) — un-floored value only ---
        calcCache[cKey] = v
        return v
    }

    private fun raw(name: String): Int = when (name) {
        "leadership" -> general.leadership
        "strength" -> general.strength
        "intelligence", "intel" -> general.intel
        else -> error("unknown stat $name")
    }

    /** General.php:378-381: getStatValue(other, withInjury, withIActionObj, withStatAdjust=false, useFloor=false) / 4. */
    private fun crossBase(other: String, withInjury: Boolean, withIActionObj: Boolean): Double =
        getStatValue(other, withInjury = withInjury, withIActionObj = withIActionObj,
            withStatAdjust = false, useFloor = false) / 4.0
}

/**
 * Stateless convenience facade (no cache reuse across calls) — preserves the P1 free-function call sites.
 * Each invocation spins a fresh [StatCalc], so there is no cross-call caching here; the cache trap only
 * matters when a caller reuses a single StatCalc across multiple resolves within one turn.
 */
fun getStatValue(
    general: General,
    statName: String,
    pipeline: GeneralActionPipeline,
    maxLevel: Int = 255,
    withInjury: Boolean = true,
    withIActionObj: Boolean = true,
    withStatAdjust: Boolean = true,
    useFloor: Boolean = true,
): Double =
    StatCalc(general, pipeline, maxLevel)
        .getStatValue(statName, withInjury, withIActionObj, withStatAdjust, useFloor)
