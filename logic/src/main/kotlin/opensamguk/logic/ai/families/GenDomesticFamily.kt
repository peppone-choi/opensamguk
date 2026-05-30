package opensamguk.logic.ai.families

import opensamguk.common.rng.RandUtil
import opensamguk.logic.util.phpRound
import opensamguk.logic.util.phpToInt
import opensamguk.logic.util.valueFit

/**
 * L-GENDOM — the general-domestic `do<한글>` command family: 일반내정 / 전쟁내정 / 긴급내정 / 금쌀구매 / 징병.
 *
 * GRAND TRUTH = PHP `legacy/devsam-core/hwe/sammo/GeneralAI.php` (read in full):
 *  - `do일반내정`   (`:2117-2218`, emits one of che_주민선정/정착장려/수비강화/성벽보수/치안강화/기술연구/농지개간/상업투자):
 *    ONE conditional `nextBool(0.3)` (`:2136`, **`&&` only when `nation['rice'] < GameConst::$baserice`** — the
 *    low-rice 30% skip) THEN a terminal `choiceUsingWeightPair($cmdList)` (`:2217`, only when `$cmdList` non-empty;
 *    `:2213` empty-guard returns null with NO draw).
 *  - `do전쟁내정`   (`:2253-2364`, same emit set): the **TWO `nextBool(0.3)` trap (decision #9)** —
 *    `:2271` conditional (**`&&` only when `nation['rice'] < $baserice`**) THEN `:2279` **ALWAYS-drawn (no guard)**,
 *    then a terminal `choiceUsingWeightPair($cmdList)` (`:2362`). NEVER collapse the two 0.3 draws into one.
 *  - `do긴급내정`   (`:2220-2251`): up to TWO conditional `nextBool` —
 *    `:2236` `nextBool($leadership / GameConst::$chiefStatMin)` (**`&&` only when `city['trust'] < 70`**) → 주민선정,
 *    `:2243` `nextBool($leadership / $chiefStatMin / 2)` (**`&&` only when `city['pop'] < minNPCRecruitCityPopulation`**)
 *    → 정착장려; else null.
 *  - `do금쌀구매`   (`:2367-2481`, emits che_군량매매): **ZERO RNG draws** — a fully deterministic buy/sell ladder;
 *    the che_모병/che_징병 cost-probes (`:2391/:2396`) are non-RNG cost estimates. ANY accidental draw desyncs the
 *    shared `"GeneralAI"` stream (decision #9). The trade amount is `valueFit(toInt((high-low)/(1+deathRate)),
 *    100, $maxResourceActionAmount)` (`:2430` buy / `:2461` sell — symmetric).
 *  - `do징병`       (`:2483-2651`, emits che_징병/che_모병): conditional `nextBool($remainPop / $maxPop)` (`:2512`,
 *    **`&&` only when `pop/pop_max < safeRecruitCityPopulationRatio` AND `!can한계징병`** — recruit-skip roll)
 *    → `choiceUsingWeight($availableArmType)` (`:2554`, **4-entry FOOTMAN/ARCHER/CAVALRY/WIZARD insertion order**,
 *    only when no preset `armType`) → `choiceUsingWeight($types)` (`:2580`, pickScore-weighted, always)
 *    → the half-crew fallback `Util::round($crew - 49, -2)` (`:2635`, PhpRound half-away at the 100s place — NOT
 *    `Math.round`, NOT `phpRound(v/100)*100`, NOT the TS deterministic-armType: PHP `choiceUsingWeight` WINS).
 *
 * This file holds the PURE, draw-order-bearing primitives each `do<한글>` composes (it mirrors the established
 * `GenFoundFamily`/`NationDeployFamily`/`NationRewardFamily`/`NationDiploFamily` pure-helper shape): the
 * candidate-set construction — the develRate buckets, the weighted `$cmdList`, the `hasFullConditionMet` gates,
 * the dex/pickScore tables, the cost ladder — is the foundations'/adapter's job; the family owns the per-method
 * DRAW ORDER + COUNT on the shared `"GeneralAI"` [RandUtil], the emitted RAW arg shape, and the deterministic
 * crew/trade arithmetic. F-BRIDGE's `candidateAllowed` gate (the PHP `hasFullConditionMet()` analogue)
 * accepts/rejects the emit — the family does NOT re-implement that gate.
 *
 * `choiceUsingWeight` over Int-keyed maps (armType / crewType) is expressed via [RandUtil.choiceUsingWeightPair]
 * over insertion-ordered `(id, weight)` pairs — the IDENTICAL one-`nextFloat1` walk semantics (the String-keyed
 * `RandUtil.choiceUsingWeight` would require Int→String key juggling); the candidate ORDER is the parity target.
 *
 * NO draws happen outside the documented `nextBool`/`choiceUsingWeightPair` sites. PURE `:logic`.
 */
object GenDomesticFamily {

    /** PHP `:2433/:2465` `buildGeneralCommandClass('che_군량매매', …)` — the do금쌀구매 emit. */
    const val TRADE_ACTION: String = "che_군량매매"

    /** PHP `:2620/:2636` `buildGeneralCommandClass('che_징병', …)` — the do징병 emit. */
    const val RECRUIT_ACTION: String = "che_징병"

    /** PHP `:2628` `buildGeneralCommandClass('che_모병', …)` — the do징병 can모병 emit. */
    const val RECRUIT_HIRE_ACTION: String = "che_모병"

    /** GameUnitConstBase.php:17 `const T_FOOTMAN = 1;` — the 1st `$availableArmType` insertion key. */
    const val T_FOOTMAN: Int = 1

    /** GameUnitConstBase.php:18 `const T_ARCHER = 2;` — the 2nd `$availableArmType` insertion key. */
    const val T_ARCHER: Int = 2

    /** GameUnitConstBase.php:19 `const T_CAVALRY = 3;` — the 3rd `$availableArmType` insertion key. */
    const val T_CAVALRY: Int = 3

    /** GameUnitConstBase.php:20 `const T_WIZARD = 4;` — the 4th `$availableArmType` insertion key. */
    const val T_WIZARD: Int = 4

    // ==================================================================================================
    // do일반내정 (:2117-2218) — ONE conditional nextBool(0.3) (:2136) + terminal choiceUsingWeightPair (:2217).
    // ==================================================================================================

    /**
     * `do일반내정`'s low-rice 30% skip gate (PHP `:2136`
     * `if (($nation['rice'] < GameConst::$baserice) && $this->rng->nextBool(0.3)) return null;`).
     *
     * The PHP `&&` short-circuits: when [riceBelowBaserice] is false the LEFT operand decides → the
     * `nextBool(0.3)` right operand is NEVER reached (ZERO draws). The caller passes the already-computed
     * `nation['rice'] < $baserice` boolean (the threshold constant lives in the foundations, not the family).
     *
     * @return true to SKIP the turn (`return null`): only when rice is low AND the 0.3 roll succeeds.
     */
    fun domesticLowRiceSkip(riceBelowBaserice: Boolean, rng: RandUtil): Boolean =
        riceBelowBaserice && rng.nextBool(0.3) // PHP :2136 — `&&` suppresses the draw when rice>=baserice.

    /**
     * `do일반내정`'s terminal pick (PHP `:2213-2217`): returns null with NO draw when `$cmdList` is empty
     * (`:2213` `if (!$cmdList) return null;`), else exactly ONE `choiceUsingWeightPair($cmdList)` (`:2217`).
     * The `(actionCode, weight)` candidate list is built deterministically by the adapter (the develRate-driven
     * weighted appends, each gated by `hasFullConditionMet`); the build/append order IS a parity target.
     *
     * @param cmdList the `(actionCode, weight)` pairs in PHP append order.
     * @return the picked action code, or null when [cmdList] is empty.
     */
    fun pickDomesticCommand(cmdList: List<Pair<String, Double>>, rng: RandUtil): String? {
        if (cmdList.isEmpty()) {
            return null // PHP :2213 `if (!$cmdList) return null;` — the empty guard, BEFORE any draw.
        }
        return rng.choiceUsingWeightPair(cmdList) // PHP :2217 — the ONE terminal draw (one nextFloat1).
    }

    // ==================================================================================================
    // do전쟁내정 (:2253-2364) — TWO nextBool(0.3): :2271 conditional THEN :2279 ALWAYS. NEVER collapse (#9).
    // ==================================================================================================

    /**
     * `do전쟁내정`'s FIRST 0.3 skip gate (PHP `:2271`
     * `if (($nation['rice'] < GameConst::$baserice) && $this->rng->nextBool(0.3)) return null;`) — the SAME
     * `&&`-conditional shape as `do일반내정` `:2136`. Suppressed (ZERO draws) when rice >= baserice.
     *
     * This is the FIRST of the two-`nextBool(0.3)` trap (decision #9). It is DISTINCT from
     * [warDomesticUnconditionalSkip] (PHP `:2279`) which ALWAYS draws — the two must NEVER collapse into one.
     *
     * @return true to SKIP the turn (`return null`): only when rice is low AND the 0.3 roll succeeds.
     */
    fun warDomesticLowRiceSkip(riceBelowBaserice: Boolean, rng: RandUtil): Boolean =
        riceBelowBaserice && rng.nextBool(0.3) // PHP :2271 — conditional FIRST 0.3 (suppressed if rice>=baserice).

    /**
     * `do전쟁내정`'s SECOND 0.3 skip gate (PHP `:2279` `if ($this->rng->nextBool(0.3)) return null;`) — there is
     * **NO `&&` guard**, so this `nextBool(0.3)` ALWAYS draws (decision #9). It is reached AFTER the conditional
     * [warDomesticLowRiceSkip] (`:2271`) and BEFORE the `$cmdList` build, so the stream is: [warDomesticLowRiceSkip]
     * (0 OR 1 draw) THEN this (ALWAYS 1 draw) THEN [pickWarDomesticCommand] (0 OR 1 draw).
     *
     * @return true to SKIP the turn (`return null`): whenever the unconditional 0.3 roll succeeds.
     */
    fun warDomesticUnconditionalSkip(rng: RandUtil): Boolean =
        rng.nextBool(0.3) // PHP :2279 — ALWAYS-drawn unconditional SECOND 0.3 (no `&&` guard).

    /**
     * `do전쟁내정`'s terminal pick (PHP `:2358-2362`): returns null with NO draw when `$cmdList` is empty
     * (`:2358` `if (!$cmdList) return null;`), else exactly ONE `choiceUsingWeightPair($cmdList)` (`:2362`).
     * Same shape as [pickDomesticCommand]; the war-mode weight formulas differ (the adapter builds them).
     */
    fun pickWarDomesticCommand(cmdList: List<Pair<String, Double>>, rng: RandUtil): String? {
        if (cmdList.isEmpty()) {
            return null // PHP :2358 `if (!$cmdList) return null;` — the empty guard, BEFORE any draw.
        }
        return rng.choiceUsingWeightPair(cmdList) // PHP :2362 — the ONE terminal draw (one nextFloat1).
    }

    // ==================================================================================================
    // do긴급내정 (:2220-2251) — up to TWO conditional nextBool: :2236 (trust<70) ; :2243 (pop<min).
    // ==================================================================================================

    /**
     * `do긴급내정`'s 주민선정 trust gate (PHP `:2236`
     * `if ($city['trust'] < 70 && $this->rng->nextBool($leadership / GameConst::$chiefStatMin)) {`).
     *
     * The `&&` short-circuits: when [trustBelow70] is false the draw is NEVER reached (ZERO draws). The prob is
     * `$leadership / $chiefStatMin` (it MAY exceed 1 → `nextBool` returns true with NO underlying byte consumed,
     * per RandUtil's `prob>=1` short-circuit, but `nextBoolCalls` still counts the invocation — the && reached it).
     *
     * @return true when the gate fires (emit che_주민선정, subject to the downstream `hasFullConditionMet`).
     */
    fun emergencyTrustGate(trustBelow70: Boolean, leadership: Double, chiefStatMin: Double, rng: RandUtil): Boolean =
        trustBelow70 && rng.nextBool(leadership / chiefStatMin) // PHP :2236 — suppressed when trust>=70.

    /**
     * `do긴급내정`'s 정착장려 pop gate (PHP `:2243`
     * `if ($city['pop'] < $this->nationPolicy->minNPCRecruitCityPopulation
     *      && $this->rng->nextBool($leadership / GameConst::$chiefStatMin / 2)) {`).
     *
     * The `&&` short-circuits: when [popBelowMin] is false the draw is NEVER reached (ZERO draws). The prob is
     * `$leadership / $chiefStatMin / 2` — HALF the [emergencyTrustGate] prob.
     *
     * @return true when the gate fires (emit che_정착장려, subject to the downstream `hasFullConditionMet`).
     */
    fun emergencyPopGate(popBelowMin: Boolean, leadership: Double, chiefStatMin: Double, rng: RandUtil): Boolean =
        popBelowMin && rng.nextBool(leadership / chiefStatMin / 2) // PHP :2243 — suppressed when pop>=min.

    // ==================================================================================================
    // do금쌀구매 (:2367-2481) — ZERO RNG draws (fully deterministic buy/sell ladder).
    // ==================================================================================================

    /**
     * `do금쌀구매`'s trade amount (PHP `:2430` buy / `:2461` sell — symmetric):
     * `Util::valueFit(Util::toInt(($high - $low) / (1 + $deathRate)), 100, GameConst::$maxResourceActionAmount)`.
     * `Util::toInt` is TRUNCATE-toward-zero ([phpToInt], distinct from floor); `valueFit` lower-clamps to 100 and
     * upper-clamps to [maxResourceActionAmount].
     *
     * **ZERO RNG draws (decision #9):** [rng] is accepted only to document the 0-draw contract at the call site
     * (matching the GenFoundFamily/NationDiploFamily zero-draw convention — a reviewer must NOT "fix" this by
     * inserting a draw; the cursor is a parity target). The whole `do금쌀구매` ladder is deterministic.
     *
     * @param relHigh the larger relative resource (the buy side: `$relGold`; the sell side: `$relRice`).
     * @param relLow the smaller relative resource.
     * @param deathRate `$death / $kill` (PHP `:2377`, the adapter computes it).
     * @param maxResourceActionAmount `GameConst::$maxResourceActionAmount` (the foundations supply it).
     * @return the clamped, truncated trade amount (an Int — the RAW `amount` arg).
     */
    @Suppress("UNUSED_PARAMETER")
    fun tradeAmount(
        relHigh: Double,
        relLow: Double,
        deathRate: Double,
        maxResourceActionAmount: Int,
        rng: RandUtil,
    ): Int =
        valueFit(
            phpToInt((relHigh - relLow) / (1 + deathRate)).toDouble(), // PHP toInt — truncate-toward-zero.
            100.0,
            maxResourceActionAmount.toDouble(),
        ).toInt() // PHP :2430/:2461 valueFit(toInt(...), 100, maxResourceActionAmount). NO draw.

    // ==================================================================================================
    // do징병 (:2483-2651) — conditional nextBool(remainPop/maxPop) → choiceUsingWeight(armType)
    //   → choiceUsingWeight(pickScore) → PhpRound(crew-49,-2). 4-entry insertion order is the parity target.
    // ==================================================================================================

    /**
     * `do징병`'s recruit-skip roll (PHP `:2511-2513`
     * `if (($city['pop'] / $city['pop_max'] < safeRecruitCityPopulationRatio)
     *      && ($this->rng->nextBool($remainPop / $maxPop))) return null;`).
     *
     * The `&&` short-circuits: when [popRatioBelowSafe] is false the draw is NEVER reached (ZERO draws). This
     * whole branch is itself gated by `!can한계징병` (PHP `:2504`, the adapter checks it BEFORE calling this — when
     * `can한계징병` is true the block is skipped entirely with NO draw). The prob is `$remainPop / $maxPop`.
     *
     * @return true to SKIP recruiting (`return null`): only when the pop ratio is unsafe AND the roll succeeds.
     */
    fun recruitSkipGate(popRatioBelowSafe: Boolean, remainPop: Int, maxPop: Int, rng: RandUtil): Boolean =
        popRatioBelowSafe && rng.nextBool(remainPop.toDouble() / maxPop) // PHP :2512 — suppressed if ratio safe.

    /**
     * `do징병`'s arm-type pick (PHP `:2554` `$armType = $this->rng->choiceUsingWeight($availableArmType);`).
     * The candidate map's INSERTION ORDER is the parity target: FOOTMAN([T_FOOTMAN]), ARCHER([T_ARCHER]),
     * CAVALRY([T_CAVALRY]) (appended when `fullStrength > fullIntel*0.9`, PHP `:2545-2548`), THEN WIZARD
     * ([T_WIZARD]) (appended when `fullIntel > fullStrength*0.9`, PHP `:2550-2551`) — catalog §5.L / GAPS G7.
     * Reached ONLY when the general has no preset `armType` (PHP `:2535` `if (!$armType)`).
     *
     * PHP `choiceUsingWeight` returns the KEY (the arm-type id); the family uses the IDENTICAL one-`nextFloat1`
     * insertion-order walk via [RandUtil.choiceUsingWeightPair] over `(armTypeId, weight)` pairs and returns the id.
     *
     * @param availableArmType the `(armTypeId, weight)` map in FOOTMAN/ARCHER/CAVALRY/WIZARD insertion order.
     * @return the picked arm-type id.
     */
    fun pickArmType(availableArmType: Map<Int, Double>, rng: RandUtil): Int =
        rng.choiceUsingWeightPair(availableArmType.toList()) // PHP :2554 — one choiceUsingWeight (insertion order).

    /**
     * `do징병`'s crew-type pick (PHP `:2580` `$type = $this->rng->choiceUsingWeight($types);`) — ALWAYS reached
     * (post arm-type). `$types` is `crewtype.id => crewtype.pickScore($tech)` for each `isValid` crew of the
     * chosen `$armType` (PHP `:2572-2577`), in `GameUnitConst::byType($armType)` iteration order (a parity target).
     * The empty-`$types` path is a `MustNotBeReachedException` (PHP `:2582`, throw-unreachable — G7); the adapter
     * never passes an empty list here.
     *
     * @param types the `(crewTypeId, pickScore)` pairs in `GameUnitConst::byType` order.
     * @return the picked crew-type id.
     */
    fun pickCrewType(types: List<Pair<Int, Double>>, rng: RandUtil): Int =
        rng.choiceUsingWeightPair(types) // PHP :2580 — one choiceUsingWeight over pickScore-weighted types.

    /**
     * `do징병`'s half-crew fallback (PHP `:2633-2635`, the `gold < cost && gold*2 >= cost` branch):
     * `$crew *= 0.5; ... $crew = Util::round($crew - 49, -2);`. The `* 0.5` is folded into [crewBeforeHalf] (the
     * adapter halves the full crew before calling); this owns the `Util::round($crew - 49, -2)` — half-AWAY at the
     * 100s place via [phpRound] with `pos = -2` (M5: NEVER `Math.round`, NEVER `phpRound(crew/100)*100` — those
     * double-round). The `-49` then `round(...,-2)` biases toward the lower hundred (round-down-leaning).
     *
     * @param crewBeforeHalf the already-halved crew (`$crew * 0.5`), as an Int (PHP keeps it numeric; the
     *  `- 49` and `round(,-2)` operate on the value).
     * @return the rounded crew at the 100s place.
     */
    fun halfCrew(crewBeforeHalf: Int): Int =
        phpRound((crewBeforeHalf - 49).toDouble(), -2) // PHP :2635 Util::round($crew-49,-2) half-away at 100s.
}
