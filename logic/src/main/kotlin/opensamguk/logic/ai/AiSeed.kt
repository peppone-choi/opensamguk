package opensamguk.logic.ai

import opensamguk.common.rng.LiteHashDrbg
import opensamguk.common.rng.RandUtil
import opensamguk.common.rng.serializeSeed
import opensamguk.logic.stats.StatCalc
import opensamguk.logic.util.clamp

/**
 * F-SEED — the `"GeneralAI"` per-general-per-decision RNG lineage + the getStatValue flavor table.
 *
 * Port target = PHP `GeneralAI.php:153-159` (the ctor seed) + `General.php:405-418`/`359-403`
 * (the getStatValue flavors, G8). REUSES the GREEN [serializeSeed]/[RandUtil]/[LiteHashDrbg] — there is
 * NO new RNG primitive.
 *
 * ## The seed (decision #1, catalog §1)
 * ```php
 * $this->rng = new RandUtil(new LiteHashDRBG(Util::simpleSerialize(
 *     UniqueConst::$hiddenSeed, 'GeneralAI', $this->env['year'], $this->env['month'], $general->getID(),
 * )));                                                                       // GeneralAI.php:153-159
 * ```
 * **5 components, `hiddenSeed` DIRECT** — NOT the TS `buildSeedBase(world)`. ONE `RandUtil` per general per
 * DECISION, built ONCE in the ctor, threaded by reference through `calcGenType` and every `do<한글>`,
 * **NEVER re-seeded.** The `"GeneralAI"` token makes this stream DISTINCT from execution's
 * `"nationCommand"`/`"generalCommand"` per-command streams (R-SEAM §2): the AI decision picks the command,
 * then execution re-seeds its own RNG to resolve it. The Kotlin [serializeSeed] is byte-identical to PHP
 * `Util::simpleSerialize` for the `str(len,val)|...|int(n)` forms this lineage uses (one String token + four
 * Int tokens). The caller builds the [RandUtil] ONCE and threads it by reference — never per-draw.
 *
 * ## The getStatValue flavor table (decision #7, G8 — RESOLVED)
 * `calcGenType` reads `getX(false)` = `getStatValue(stat, withInjury=false, withIActionObj=true,
 * withStatAdjust=true, useFloor=true)`; strength/intel are then `Util::valueFit(v, 1)` = `clamp(v, 1.0)`
 * (min 1, no max), leadership has NO floor. Reward math = `(false,true,true,true)`. Promotion stat-gate =
 * `(false,false,false,false)`. **`useFloor=true` already truncates toward zero — do NOT re-round.**
 */
object AiSeed {

    /** The token that names the AI decision stream (distinct from execution's "nationCommand"/"generalCommand"). */
    const val LINEAGE_TOKEN: String = "GeneralAI"

    /**
     * The 5-component seed STRING — `serializeSeed(hiddenSeed, "GeneralAI", year, month, generalId)`.
     * `hiddenSeed` is the raw 32-hex `world_state.hidden_seed` value (P3 F4); pass it DIRECT.
     */
    fun seed(hidden: String, year: Int, month: Int, generalId: Int): String =
        serializeSeed(hidden, LINEAGE_TOKEN, year, month, generalId)

    /**
     * Build the SOLE per-general-per-decision [RandUtil]. Call ONCE in the AI ctor and thread the result by
     * reference; NEVER re-seed. The DRBG starts at the fresh `stateIdx=0,bufferIdx=0` cursor — every later
     * draw advances the single shared cursor (the byte-parity target).
     */
    fun rng(hidden: String, year: Int, month: Int, generalId: Int): RandUtil =
        RandUtil(LiteHashDrbg(seed(hidden, year, month, generalId)))

    // ---------------------------------------------------------------------------------------------------
    // The getStatValue flavor table (G8). These wrap the GREEN [StatCalc] so the per-decision calcCache is
    // shared with the resolve. No re-rounding: each returns the Double the GREEN getStatValue already
    // truncated (useFloor=true).
    // ---------------------------------------------------------------------------------------------------

    /** calcGenType leadership: `getLeadership(false)`, NO valueFit floor (GeneralAI.php:177, G8 §4). */
    fun genTypeLeadership(stat: StatCalc): Double =
        stat.getStatValue("leadership", withInjury = false)

    /** calcGenType strength: `Util::valueFit(getStrength(false), 1)` = clamp(v, min=1) (GeneralAI.php:178). */
    fun genTypeStrength(stat: StatCalc): Double =
        clamp(stat.getStatValue("strength", withInjury = false), 1.0)

    /** calcGenType intel: `Util::valueFit(getIntel(false), 1)` = clamp(v, min=1) (GeneralAI.php:179). */
    fun genTypeIntel(stat: StatCalc): Double =
        clamp(stat.getStatValue("intel", withInjury = false), 1.0)

    /** Reward math flavor = `(withInjury=false, withIActionObj=true, withStatAdjust=true, useFloor=true)`. */
    fun rewardStat(stat: StatCalc, statName: String): Double =
        stat.getStatValue(statName, withInjury = false)

    /** Promotion stat-gate flavor = `(false, false, false, false)` — raw stat, no injury/iAction/adjust. */
    fun promotionStat(stat: StatCalc, statName: String): Double =
        stat.getStatValue(
            statName,
            withInjury = false,
            withIActionObj = false,
            withStatAdjust = false,
            useFloor = false,
        )
}
