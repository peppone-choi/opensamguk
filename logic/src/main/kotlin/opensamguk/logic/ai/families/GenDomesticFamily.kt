package opensamguk.logic.ai.families

import opensamguk.common.constants.GameConst
import opensamguk.common.rng.RandUtil
import opensamguk.logic.ai.AiInstanceState
import opensamguk.logic.ai.ChosenCommand
import opensamguk.logic.ai.GeneralAiContext
import opensamguk.logic.domain.LastTurn
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

    // ====================================================================================================
    // WORLD-DRIVEN do<한글> BODIES (PHP `GeneralAI.php:2117-2651`, read in full).
    //
    // Each body reads the [GeneralAiContext] (rng / instance / develRate / policy / stat caches), assembles the
    // exact candidate set / weighted cmdList in PHP insertion order, calls the PURE draw primitive above, GATES
    // the emit via `ctx.candidateAllowed(actionCode, args)` (PHP `$cmd->hasFullConditionMet()`), and returns the
    // `ChosenCommand` (or null → the dispatcher falls through to the next priority action). READ-ONLY over GAME
    // ENTITIES; NO meta-KV deltas (the domestic methods queue none in the AI). The candidate ORDER (the
    // genType-gated develRate cmdList append order + the 4-entry armType insertion order) IS the draw-for-draw
    // parity target. The 8 develop commands are 주민선정/정착장려/수비강화/성벽보수/치안강화/기술연구/농지개간/상업투자.
    // ====================================================================================================

    /** PHP develop command codes (`buildGeneralCommandClass('che_*', …)`), in the do일반내정 append order. */
    private const val C_RESIDENT = "che_주민선정"   // 통솔장 trust
    private const val C_SETTLE = "che_정착장려"     // 통솔장 pop
    private const val C_DEFENSE = "che_수비강화"    // 무장 def
    private const val C_WALL = "che_성벽보수"       // 무장 wall
    private const val C_SECURITY = "che_치안강화"   // 무장 secu
    private const val C_TECH = "che_기술연구"        // 지장 tech
    private const val C_FARM = "che_농지개간"        // 지장 agri
    private const val C_COMMERCE = "che_상업투자"    // 지장 comm

    /**
     * The 5 general-domestic `do<한글>` bodies keyed by ACTION-NAME (the bare `do<한글>` name, NOT `che_*`), in
     * PHP/policy order. The [opensamguk.logic.ai.GeneralAiFactory] registers these into the general dispatch;
     * the loop walks [AutorunGeneralPolicy.priority] first-non-null. A body returns null on every early-guard /
     * empty-candidate / gate-deny.
     */
    fun bodies(ctx: GeneralAiContext): Map<String, (LastTurn?) -> ChosenCommand?> = linkedMapOf(
        "일반내정" to { _ -> do일반내정(ctx) },
        "전쟁내정" to { _ -> do전쟁내정(ctx) },
        "긴급내정" to { _ -> do긴급내정(ctx) },
        "금쌀구매" to { _ -> do금쌀구매(ctx) },
        "징병" to { _ -> do징병(ctx) },
    )

    // --- shared read helpers (NO draws) ---

    private fun genType(ctx: GeneralAiContext): Int = ctx.instance.genType
    private fun has(genType: Int, flag: Int): Boolean = (genType and flag) != 0
    private fun rate(ctx: GeneralAiContext, key: String): Double = ctx.cityDevelRate[key] ?: 1.0

    /** `$cmd->hasFullConditionMet()` then append `[$cmd, weight]` (PHP `:2143-2145` shape). */
    private fun appendIfAllowed(
        ctx: GeneralAiContext,
        cmdList: ArrayList<Pair<String, Double>>,
        actionCode: String,
        weight: Double,
    ) {
        if (ctx.candidateAllowed(actionCode, emptyMap())) {
            cmdList.add(actionCode to weight) // PHP `:2144` `$cmdList[] = [$cmd, weight];`
        }
    }

    // --- do일반내정 (PHP `:2117-2218`) ---

    private fun do일반내정(ctx: GeneralAiContext): ChosenCommand? {
        val rng = ctx.rng
        val leadership = ctx.leadershipWithInjury // PHP `:2120` $this->leadership (WITH injury).
        val strength = ctx.strengthWithInjury // PHP `:2121` $this->strength (WITH injury).
        val intel = ctx.intelWithInjury // PHP `:2122` $this->intel (WITH injury).
        val genType = genType(ctx)
        val isSpringSummer = ctx.env.month <= 6 // PHP `:2132`.
        val cmdList = ArrayList<Pair<String, Double>>() // PHP `:2134`.

        // PHP `:2136` — low-rice 30% skip (`&&` suppresses the draw when rice>=baserice).
        if (domesticLowRiceSkip(ctx.instance.nation.rice < GameConst.baserice, rng)) {
            return null // PHP `:2137`.
        }

        if (has(genType, AiInstanceState.T_TONGSOLJANG)) { // PHP `:2140`.
            val trust = rate(ctx, "trust")
            if (trust < 0.98) { // PHP `:2141`.
                appendIfAllowed(ctx, cmdList, C_RESIDENT, leadership / valueFit(trust / 2 - 0.2, 0.001) * 2) // :2144
            }
            val pop = rate(ctx, "pop")
            if (pop < 0.8) { // PHP `:2147`.
                appendIfAllowed(ctx, cmdList, C_SETTLE, leadership / valueFit(pop, 0.001)) // :2150
            } else if (pop < 0.99) { // PHP `:2152`.
                appendIfAllowed(ctx, cmdList, C_SETTLE, leadership / valueFit(pop / 4, 0.001)) // :2155
            }
        }

        if (has(genType, AiInstanceState.T_MUJANG)) { // PHP `:2160`.
            val def = rate(ctx, "def")
            if (def < 1) { // PHP `:2161`.
                appendIfAllowed(ctx, cmdList, C_DEFENSE, strength / valueFit(def, 0.001)) // :2164
            }
            val wall = rate(ctx, "wall")
            if (wall < 1) { // PHP `:2167`.
                appendIfAllowed(ctx, cmdList, C_WALL, strength / valueFit(wall, 0.001)) // :2170
            }
            val secu = rate(ctx, "secu")
            if (secu < 0.9) { // PHP `:2173`.
                appendIfAllowed(ctx, cmdList, C_SECURITY, strength / valueFit(secu / 0.8, 0.001, 1.0)) // :2176
            } else if (secu < 1) { // PHP `:2178`.
                appendIfAllowed(ctx, cmdList, C_SECURITY, strength / 2 / valueFit(secu, 0.001)) // :2181
            }
        }

        if (has(genType, AiInstanceState.T_JIJANG)) { // PHP `:2186`.
            if (!ctx.techLimited) { // PHP `:2187` !TechLimit(startyear, year, nation.tech).
                val nextTech = (ctx.nationTech % 1000) + 1 // PHP `:2190`.
                val weight = if (!ctx.techLimitedNextGrade) {
                    intel / (nextTech / 2000.0) // PHP `:2193` ≥1 grade behind → work harder.
                } else {
                    intel // PHP `:2195`.
                }
                appendIfAllowed(ctx, cmdList, C_TECH, weight)
            }
            val agri = rate(ctx, "agri")
            if (agri < 1) { // PHP `:2199`.
                appendIfAllowed(
                    ctx, cmdList, C_FARM,
                    (if (isSpringSummer) 1.2 else 0.8) * intel / valueFit(agri, 0.001, 1.0), // :2202
                )
            }
            val comm = rate(ctx, "comm")
            if (comm < 1) { // PHP `:2205`.
                appendIfAllowed(
                    ctx, cmdList, C_COMMERCE,
                    (if (isSpringSummer) 0.8 else 1.2) * intel / valueFit(comm, 0.001, 1.0), // :2208
                )
            }
        }

        // PHP `:2213-2217` — empty guard (no draw) then the ONE terminal choiceUsingWeightPair.
        val picked = pickDomesticCommand(cmdList, rng) ?: return null
        return ChosenCommand(picked, emptyMap())
    }

    // --- do전쟁내정 (PHP `:2253-2364`) — the TWO nextBool(0.3) trap ---

    private fun do전쟁내정(ctx: GeneralAiContext): ChosenCommand? {
        val rng = ctx.rng
        if (ctx.instance.dipState == AiInstanceState.D_PEACE) return null // PHP `:2256`.

        val leadership = ctx.leadershipWithInjury // PHP `:2260`.
        val strength = ctx.strengthWithInjury // PHP `:2261`.
        val intel = ctx.intelWithInjury // PHP `:2262`.
        val genType = genType(ctx)

        // PHP `:2271` — FIRST 0.3 (conditional, `&&` suppressed when rice>=baserice).
        if (warDomesticLowRiceSkip(ctx.instance.nation.rice < GameConst.baserice, rng)) {
            return null // PHP `:2272`.
        }

        val isSpringSummer = ctx.env.month <= 6 // PHP `:2276`.
        val cmdList = ArrayList<Pair<String, Double>>() // PHP `:2277`.

        // PHP `:2279` — SECOND 0.3 (ALWAYS drawn, no guard). NEVER collapse with `:2271` (decision #9).
        if (warDomesticUnconditionalSkip(rng)) {
            return null // PHP `:2280`.
        }

        val front = ctx.selfCity?.frontState ?: 0
        val frontOneOrThree = front == 1 || front == 3 // PHP `in_array($city['front'], [1, 3])`.

        if (has(genType, AiInstanceState.T_TONGSOLJANG)) { // PHP `:2283`.
            val trust = rate(ctx, "trust")
            if (trust < 0.98) { // PHP `:2284`.
                appendIfAllowed(ctx, cmdList, C_RESIDENT, leadership / valueFit(trust / 2 - 0.2, 0.001) * 2) // :2287
            }
            val pop = rate(ctx, "pop")
            if (pop < 0.8) { // PHP `:2290`.
                val w =
                    if (frontOneOrThree) leadership / valueFit(pop, 0.001) // :2294
                    else leadership / valueFit(pop, 0.001) / 2 // :2296
                appendIfAllowed(ctx, cmdList, C_SETTLE, w)
            }
        }

        if (has(genType, AiInstanceState.T_MUJANG)) { // PHP `:2302`.
            val def = rate(ctx, "def")
            if (def < 0.5) { // PHP `:2303`.
                appendIfAllowed(ctx, cmdList, C_DEFENSE, strength / valueFit(def, 0.001) / 2) // :2306
            }
            val wall = rate(ctx, "wall")
            if (wall < 0.5) { // PHP `:2309`.
                appendIfAllowed(ctx, cmdList, C_WALL, strength / valueFit(wall, 0.001) / 2) // :2312
            }
            val secu = rate(ctx, "secu")
            if (secu < 0.5) { // PHP `:2315`.
                appendIfAllowed(ctx, cmdList, C_SECURITY, strength / valueFit(secu / 0.8, 0.001, 1.0) / 4) // :2318
            }
        }

        if (has(genType, AiInstanceState.T_JIJANG)) { // PHP `:2323`.
            if (!ctx.techLimited) { // PHP `:2324`.
                val nextTech = (ctx.nationTech % 1000) + 1 // PHP `:2327`.
                val weight = if (!ctx.techLimitedNextGrade) {
                    intel / (nextTech / 3000.0) // PHP `:2330` ≥1 grade behind, at war → 3000.
                } else {
                    intel // PHP `:2332`.
                }
                appendIfAllowed(ctx, cmdList, C_TECH, weight)
            }
            val agri = rate(ctx, "agri")
            if (agri < 0.5) { // PHP `:2336`.
                val seasonal = (if (isSpringSummer) 1.2 else 0.8) * intel
                val w =
                    if (frontOneOrThree) seasonal / 4 / valueFit(agri, 0.001, 1.0) // :2340
                    else seasonal / 2 / valueFit(agri, 0.001, 1.0) // :2342
                appendIfAllowed(ctx, cmdList, C_FARM, w)
            }
            val comm = rate(ctx, "comm")
            if (comm < 0.5) { // PHP `:2346`.
                val seasonal = (if (isSpringSummer) 0.8 else 1.2) * intel
                val w =
                    if (frontOneOrThree) seasonal / 4 / valueFit(comm, 0.001, 1.0) // :2350
                    else seasonal / 2 / valueFit(comm, 0.001, 1.0) // :2352
                appendIfAllowed(ctx, cmdList, C_COMMERCE, w)
            }
        }

        // PHP `:2358-2362` — empty guard (no draw) then the ONE terminal choiceUsingWeightPair.
        val picked = pickWarDomesticCommand(cmdList, rng) ?: return null
        return ChosenCommand(picked, emptyMap())
    }

    // --- do긴급내정 (PHP `:2220-2251`) ---

    private fun do긴급내정(ctx: GeneralAiContext): ChosenCommand? {
        val rng = ctx.rng
        if (ctx.instance.dipState == AiInstanceState.D_PEACE) return null // PHP `:2222`.

        val leadership = ctx.leadershipWithInjury // PHP `:2226`.
        val city = ctx.selfCity ?: return null // PHP reads $this->city; null (재야/lost) → no gate.
        val chiefStatMin = ctx.chiefStatMin.toDouble()

        // PHP `:2236` — trust<70 → nextBool(leadership/chiefStatMin) → che_주민선정 (`&&` suppressed when trust>=70).
        if (emergencyTrustGate(city.trust < 70.0, leadership, chiefStatMin, rng)) {
            if (ctx.candidateAllowed(C_RESIDENT, emptyMap())) { // PHP `:2238` hasFullConditionMet.
                return ChosenCommand(C_RESIDENT, emptyMap()) // PHP `:2239`.
            }
        }

        // PHP `:2243` — pop<min → nextBool(leadership/chiefStatMin/2) → che_정착장려 (`&&` suppressed when pop>=min).
        if (emergencyPopGate(
                city.population < ctx.nationPolicy.minNPCRecruitCityPopulation,
                leadership, chiefStatMin, rng,
            )
        ) {
            if (ctx.candidateAllowed(C_SETTLE, emptyMap())) { // PHP `:2245` hasFullConditionMet.
                return ChosenCommand(C_SETTLE, emptyMap()) // PHP `:2246`.
            }
        }

        return null // PHP `:2250`.
    }

    // --- do금쌀구매 (PHP `:2367-2481`) — ZERO RNG draws (deterministic ladder, delegated) ---

    private fun do금쌀구매(ctx: GeneralAiContext): ChosenCommand? =
        ctx.tradeDecision() // the deterministic buy/sell ladder (PHP `:2367-2480`); NO draws (decision #9).

    // --- do징병 (PHP `:2483-2651`) ---

    private fun do징병(ctx: GeneralAiContext): ChosenCommand? {
        val rng = ctx.rng
        // PHP `:2485` — d평화/d선포 → null.
        if (ctx.instance.dipState == AiInstanceState.D_PEACE || ctx.instance.dipState == AiInstanceState.D_DECLARED) {
            return null
        }
        // PHP `:2489` — must be 통솔장.
        if (!has(genType(ctx), AiInstanceState.T_TONGSOLJANG)) return null

        val city = ctx.selfCity ?: return null

        // PHP `:2500` — crew already at the war floor → null (BEFORE any draw).
        if (ctx.selfCrew >= ctx.nationPolicy.minWarCrew) return null

        // PHP `:2504-2516` — recruit-skip block (only when !can한계징병).
        if (!ctx.generalPolicy.can한계징병) {
            val remainPop = city.population - ctx.nationPolicy.minNPCRecruitCityPopulation -
                (ctx.fullLeadership * 100).toInt() // PHP `:2505`.
            if (remainPop <= 0) return null // PHP `:2506-2508`.

            val maxPop = city.populationMax - ctx.nationPolicy.minNPCRecruitCityPopulation // PHP `:2510`.
            val popRatioBelowSafe =
                city.population.toDouble() / city.populationMax < ctx.nationPolicy.safeRecruitCityPopulationRatio // :2511
            // PHP `:2512` — `&&` suppresses the draw when the ratio is safe.
            if (recruitSkipGate(popRatioBelowSafe, remainPop, maxPop, rng)) {
                return null // PHP `:2514`.
            }
        }

        // PHP `:2526-2555` — arm type: a usable preset short-circuits the draw; else choiceUsingWeight.
        val armType = ctx.recruitArmType ?: run {
            if (ctx.recruitArmTypeWeights.isEmpty()) return null // empty $availableArmType → choiceUsingWeight throws; guard.
            pickArmType(linkedMapOf<Int, Double>().apply { ctx.recruitArmTypeWeights.forEach { put(it.first, it.second) } }, rng) // :2554
        }

        // PHP `:2571-2583` — crew-type pickScore types; empty → MustNotBeReachedException (the adapter guards).
        val types = ctx.recruitCrewScoresFor(armType)
        if (types.isEmpty()) return null
        val crewType = pickCrewType(types, rng) // PHP `:2580`.

        // PHP `:2585-2650` — the deterministic post-pick cost ladder (can고급병종 override / can모병 / half-crew /
        // rice gate / hasFullConditionMet); NO draws. Delegated to the adapter, which threads the chosen crewType.
        return ctx.recruitFinalize(crewType)
    }
}
