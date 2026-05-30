package opensamguk.logic.ai.families

import opensamguk.common.rng.LiteHashDrbg
import opensamguk.common.rng.RandUtil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * L-GENDOM — GenDomesticFamily (일반내정 / 전쟁내정 / 긴급내정 / 금쌀구매 / 징병): the per-method DRAW STREAM.
 *
 * GRAND TRUTH = PHP `legacy/devsam-core/hwe/sammo/GeneralAI.php` (read in full):
 *  - `do일반내정`   (`:2117-2218`, emits one of che_주민선정/정착장려/수비강화/성벽보수/치안강화/기술연구/농지개간/상업투자):
 *    ONE conditional `nextBool(0.3)` (`:2136`, **`&&` only when `nation.rice < baserice`** — low-rice 30% skip)
 *    THEN a terminal `choiceUsingWeightPair($cmdList)` (`:2217`, only when `$cmdList` non-empty).
 *  - `do전쟁내정`   (`:2253-2364`, same emit set): the **TWO `nextBool(0.3)` trap (decision #9)** —
 *    `:2271` conditional (**`&&` only when `nation.rice < baserice`**) THEN `:2279` **ALWAYS-drawn (no guard)**,
 *    then a terminal `choiceUsingWeightPair($cmdList)` (`:2362`). NEVER collapse the two 0.3 draws into one.
 *  - `do긴급내정`   (`:2220-2251`): up to TWO conditional `nextBool` —
 *    `:2236` `nextBool(leadership / chiefStatMin)` (**`&&` only when `city.trust < 70`**) returns che_주민선정,
 *    `:2243` `nextBool(leadership / chiefStatMin / 2)` (**`&&` only when `city.pop < minNPCRecruitCityPopulation`**)
 *    returns che_정착장려; else null.
 *  - `do금쌀구매`   (`:2367-2481`, emits che_군량매매): **ZERO RNG draws** — a fully deterministic buy/sell ladder
 *    (the che_모병/che_징병 cost-probes at `:2391/:2396` are non-RNG cost estimates; ANY accidental draw desyncs).
 *  - `do징병`       (`:2483-2651`, emits che_징병/che_모병): conditional `nextBool(remainPop/maxPop)` (`:2512`,
 *    **`&&` only when `pop/pop_max < safeRecruitCityPopulationRatio` AND `!can한계징병`** — recruit-skip roll)
 *    → `choiceUsingWeight($availableArmType)` (`:2554`, **4-entry FOOTMAN/ARCHER/CAVALRY/WIZARD insertion order**,
 *    only when no preset armType) → `choiceUsingWeight($types)` (`:2580`, pickScore-weighted, always)
 *    → the half-crew fallback `Util::round($crew - 49, -2)` (`:2635`, PhpRound half-away — NOT Math.round, NOT
 *    the TS deterministic-armType: PHP `choiceUsingWeight` WINS).
 *
 * The family is PURE (mirrors GenFoundFamily/NationDeployFamily/NationRewardFamily/NationDiploFamily): the
 * candidate-set construction (develRate buckets, cmdList weights, hasFullConditionMet gates, the dex/pickScore
 * tables, the cost ladder) is the foundations'/adapter's job; the family owns the per-method DRAW ORDER + COUNT
 * on the shared `"GeneralAI"` [RandUtil], the emitted RAW arg shape, and the deterministic crew arithmetic.
 * F-BRIDGE's `candidateAllowed` gate (the PHP `hasFullConditionMet()` analogue) accepts/rejects the emit — the
 * family does NOT re-implement that gate.
 */
class GenDomesticFamilyTest {

    /**
     * A draw-recording RNG over a REAL [LiteHashDrbg]. Records the top-level SEMANTIC draw stream
     * (`nextBool`/`nextBit`/`choiceUsingWeightPair`); a re-entrancy guard ensures an inner `nextFloat1`/`nextBit`
     * consumed BY a `nextBool` is NOT double-recorded; a directly-recorded `nextFloat1` is a
     * `choiceUsingWeightPair` draw. `nextBoolCalls` counts EVERY `nextBool` invocation (including short-circuits
     * that consume no underlying byte); `nextFloat1Calls`/`nextBitCalls` count ONLY underlying primitive bytes.
     */
    private class RecordingRng(seed: String) : RandUtil(LiteHashDrbg(seed)) {
        data class Draw(val kind: String, val prob: Double)
        val draws = ArrayList<Draw>()
        var nextBoolCalls = 0
        var nextFloat1Calls = 0
        var nextBitCalls = 0
        private var inNextBool = false
        override fun nextBool(prob: Double): Boolean {
            nextBoolCalls++; draws.add(Draw("nextBool", prob))
            inNextBool = true
            try {
                return super.nextBool(prob)
            } finally {
                inNextBool = false
            }
        }
        override fun nextFloat1(): Double {
            nextFloat1Calls++
            if (!inNextBool) draws.add(Draw("nextFloat1", -1.0)) // top-level float = choiceUsingWeightPair.
            return super.nextFloat1()
        }
        override fun nextBit(): Boolean {
            nextBitCalls++
            if (!inNextBool) draws.add(Draw("nextBit", 0.5))
            return super.nextBit()
        }
    }

    // ==================================================================================================
    // (1) do일반내정 — ONE conditional nextBool(0.3) (rice<baserice) + terminal choiceUsingWeightPair.
    // ==================================================================================================

    @Test
    fun `domestic low-rice skip gate draws nextBool of 0_3 ONLY when rice is below baserice`() {
        // PHP :2136 `if (($nation['rice'] < GameConst::$baserice) && $this->rng->nextBool(0.3)) return null;`
        // The `&&` short-circuits: rice >= baserice → the left operand is false → nextBool is NEVER reached.
        val rngLow = RecordingRng("dom-seed")
        GenDomesticFamily.domesticLowRiceSkip(riceBelowBaserice = true, rng = rngLow)
        assertEquals(1, rngLow.nextBoolCalls, "rice<baserice → the && right operand draws nextBool(0.3) (PHP :2136)")
        assertEquals(0.3, rngLow.draws.first().prob, "the low-rice skip prob is 0.3")

        val rngHigh = RecordingRng("dom-seed")
        GenDomesticFamily.domesticLowRiceSkip(riceBelowBaserice = false, rng = rngHigh)
        assertEquals(0, rngHigh.nextBoolCalls, "rice>=baserice → && short-circuits → ZERO draws (PHP :2136 left operand false)")
    }

    @Test
    fun `domestic terminal pick is a single choiceUsingWeightPair over the cmdList`() {
        // PHP :2217 `return $this->rng->choiceUsingWeightPair($cmdList);` (only when $cmdList non-empty).
        val rng = RecordingRng("dom-seed")
        val cmdList = listOf("che_주민선정" to 12.0, "che_농지개간" to 8.0, "che_상업투자" to 4.0)
        val picked = GenDomesticFamily.pickDomesticCommand(cmdList, rng = rng)
        assertEquals(1, rng.nextFloat1Calls, "exactly ONE choiceUsingWeightPair draw (PHP :2217)")
        assertTrue(picked in listOf("che_주민선정", "che_농지개간", "che_상업투자"))
    }

    @Test
    fun `domestic empty cmdList returns null with ZERO draws`() {
        // PHP :2213 `if (!$cmdList) return null;` — the empty guard precedes the choiceUsingWeightPair draw.
        val rng = RecordingRng("dom-seed")
        val picked = GenDomesticFamily.pickDomesticCommand(emptyList(), rng = rng)
        assertEquals(null, picked, "empty cmdList → null (PHP :2213)")
        assertEquals(0, rng.nextBoolCalls + rng.nextFloat1Calls + rng.nextBitCalls, "no draw on the empty path")
    }

    // ==================================================================================================
    // (2) do전쟁내정 — TWO nextBool(0.3) (decision #9): :2271 conditional THEN :2279 ALWAYS. NEVER collapse.
    // ==================================================================================================

    @Test
    fun `war-domestic FIRST 0_3 skip draws ONLY when rice is below baserice (PHP 2271 conditional)`() {
        // PHP :2271 `if (($nation['rice'] < GameConst::$baserice) && $this->rng->nextBool(0.3)) return null;`
        val rngLow = RecordingRng("war-dom-seed")
        GenDomesticFamily.warDomesticLowRiceSkip(riceBelowBaserice = true, rng = rngLow)
        assertEquals(1, rngLow.nextBoolCalls, "rice<baserice → the FIRST nextBool(0.3) draws (PHP :2271)")
        assertEquals(0.3, rngLow.draws.first().prob)

        val rngHigh = RecordingRng("war-dom-seed")
        GenDomesticFamily.warDomesticLowRiceSkip(riceBelowBaserice = false, rng = rngHigh)
        assertEquals(0, rngHigh.nextBoolCalls, "rice>=baserice → && short-circuits → the FIRST 0.3 is NOT drawn (PHP :2271)")
    }

    @Test
    fun `war-domestic SECOND 0_3 skip is ALWAYS drawn (PHP 2279 no guard)`() {
        // PHP :2279 `if ($this->rng->nextBool(0.3)) return null;` — NO `&&` guard; ALWAYS draws.
        val rng = RecordingRng("war-dom-seed")
        GenDomesticFamily.warDomesticUnconditionalSkip(rng = rng)
        assertEquals(1, rng.nextBoolCalls, "the SECOND nextBool(0.3) ALWAYS draws (PHP :2279, no guard)")
        assertEquals(0.3, rng.draws.first().prob)
    }

    @Test
    fun `war-domestic emits BOTH 0_3 draws in order when rice is below baserice (the double-0_3 trap)`() {
        // The full pre-cmdList stream when rice<baserice and neither skip returns: :2271 nextBool(0.3) THEN
        // :2279 nextBool(0.3) — TWO draws (decision #9, the biggest collapse-into-one trap). NEVER one.
        val rng = RecordingRng("war-dom-seed")
        GenDomesticFamily.warDomesticLowRiceSkip(riceBelowBaserice = true, rng = rng) // :2271 (conditional, fires)
        GenDomesticFamily.warDomesticUnconditionalSkip(rng = rng) // :2279 (always)
        assertEquals(
            listOf("nextBool" to 0.3, "nextBool" to 0.3),
            rng.draws.map { it.kind to it.prob },
            "rice<baserice → TWO nextBool(0.3): :2271 conditional THEN :2279 unconditional (NEVER collapsed)",
        )
        assertEquals(2, rng.nextBoolCalls, "exactly two 0.3 draws")
    }

    @Test
    fun `war-domestic emits ONLY the unconditional 0_3 when rice is at-or-above baserice`() {
        // rice>=baserice → :2271 short-circuits (no draw) → only :2279 draws → exactly ONE 0.3 draw.
        val rng = RecordingRng("war-dom-seed")
        GenDomesticFamily.warDomesticLowRiceSkip(riceBelowBaserice = false, rng = rng) // :2271 short-circuits
        GenDomesticFamily.warDomesticUnconditionalSkip(rng = rng) // :2279 always draws
        assertEquals(
            listOf("nextBool" to 0.3),
            rng.draws.map { it.kind to it.prob },
            "rice>=baserice → the :2271 conditional is suppressed; only the :2279 unconditional 0.3 draws",
        )
    }

    @Test
    fun `war-domestic terminal pick is a single choiceUsingWeightPair over the cmdList`() {
        // PHP :2362 `$cmd = $this->rng->choiceUsingWeightPair($cmdList);` (only when $cmdList non-empty).
        val rng = RecordingRng("war-dom-seed")
        val cmdList = listOf("che_수비강화" to 9.0, "che_성벽보수" to 5.0)
        val picked = GenDomesticFamily.pickWarDomesticCommand(cmdList, rng = rng)
        assertEquals(1, rng.nextFloat1Calls, "exactly ONE choiceUsingWeightPair draw (PHP :2362)")
        assertTrue(picked in listOf("che_수비강화", "che_성벽보수"))
    }

    // ==================================================================================================
    // (3) do긴급내정 — up to TWO conditional nextBool (trust<70 ; pop<minNPCRecruitCityPopulation).
    // ==================================================================================================

    @Test
    fun `emergency-domestic trust gate draws nextBool of leadership over chiefStatMin ONLY when trust below 70`() {
        // PHP :2236 `if ($city['trust'] < 70 && $this->rng->nextBool($leadership / GameConst::$chiefStatMin)) {`
        val rngLow = RecordingRng("emerg-seed")
        GenDomesticFamily.emergencyTrustGate(trustBelow70 = true, leadership = 60.0, chiefStatMin = 65.0, rng = rngLow)
        assertEquals(1, rngLow.nextBoolCalls, "trust<70 → the && right operand draws nextBool(leadership/chiefStatMin) (PHP :2236)")
        assertEquals(60.0 / 65.0, rngLow.draws.first().prob, 1e-12, "prob = leadership / chiefStatMin (PHP :2236)")

        val rngHigh = RecordingRng("emerg-seed")
        GenDomesticFamily.emergencyTrustGate(trustBelow70 = false, leadership = 60.0, chiefStatMin = 65.0, rng = rngHigh)
        assertEquals(0, rngHigh.nextBoolCalls, "trust>=70 → && short-circuits → ZERO draws (PHP :2236 left operand false)")
    }

    @Test
    fun `emergency-domestic pop gate draws nextBool of leadership over chiefStatMin halved ONLY when pop below min`() {
        // PHP :2243 `if ($city['pop'] < minNPCRecruitCityPopulation && nextBool($leadership/chiefStatMin/2)) {`
        val rngLow = RecordingRng("emerg-seed")
        GenDomesticFamily.emergencyPopGate(popBelowMin = true, leadership = 60.0, chiefStatMin = 65.0, rng = rngLow)
        assertEquals(1, rngLow.nextBoolCalls, "pop<min → the && right operand draws nextBool(leadership/chiefStatMin/2) (PHP :2243)")
        assertEquals(60.0 / 65.0 / 2.0, rngLow.draws.first().prob, 1e-12, "prob = leadership / chiefStatMin / 2 (PHP :2243)")

        val rngHigh = RecordingRng("emerg-seed")
        GenDomesticFamily.emergencyPopGate(popBelowMin = false, leadership = 60.0, chiefStatMin = 65.0, rng = rngHigh)
        assertEquals(0, rngHigh.nextBoolCalls, "pop>=min → && short-circuits → ZERO draws (PHP :2243 left operand false)")
    }

    // ==================================================================================================
    // (4) do금쌀구매 — ZERO RNG draws (fully deterministic buy/sell ladder; ANY accidental draw desyncs).
    // ==================================================================================================

    @Test
    fun `rice-gold trade amount is a deterministic valueFit of toInt with ZERO draws`() {
        // PHP :2430 `$amount = Util::valueFit(Util::toInt(($relGold-$relRice)/(1+$deathRate)),100,maxResourceActionAmount);`
        // (and the symmetric :2461 sell side). Deterministic — ANY RNG draw here breaks parity (decision #9).
        val rng = RecordingRng("rice-seed")
        val amount = GenDomesticFamily.tradeAmount(
            relHigh = 50_000.0,
            relLow = 10_000.0,
            deathRate = 1.0,
            maxResourceActionAmount = 1_000_000,
            rng = rng,
        )
        // toInt((50000-10000)/(1+1)) = toInt(20000) = 20000; valueFit(20000,100,1000000) = 20000.
        assertEquals(20_000, amount, "amount = valueFit(toInt((high-low)/(1+deathRate)), 100, max) (PHP :2430)")
        assertEquals(0, rng.nextBoolCalls + rng.nextFloat1Calls + rng.nextBitCalls, "do금쌀구매 makes ZERO draws (decision #9)")
    }

    @Test
    fun `rice-gold trade amount lower-clamps to 100 with ZERO draws`() {
        // valueFit lower bound 100 (PHP :2430). Truncate-toward-zero via toInt, NOT round.
        val rng = RecordingRng("rice-seed")
        val amount = GenDomesticFamily.tradeAmount(
            relHigh = 150.0, relLow = 100.0, deathRate = 1.0, maxResourceActionAmount = 1_000_000, rng = rng,
        )
        // toInt((150-100)/2) = toInt(25) = 25; valueFit(25,100,..) = 100 (lower clamp).
        assertEquals(100, amount, "below-100 → clamps to the valueFit min 100 (PHP :2430)")
        assertEquals(0, rng.nextBoolCalls + rng.nextFloat1Calls + rng.nextBitCalls, "still ZERO draws")
    }

    // ==================================================================================================
    // (5) do징병 — conditional nextBool(remainPop/maxPop) → choiceUsingWeight(armType) → choiceUsingWeight(pickScore)
    //     → PhpRound(crew-49,-2). 4-entry FOOTMAN/ARCHER/CAVALRY/WIZARD insertion order is the parity target.
    // ==================================================================================================

    @Test
    fun `recruit skip gate draws nextBool of remainPop over maxPop ONLY when both conditions hold`() {
        // PHP :2511-2513 `if (($city['pop']/$city['pop_max'] < safeRecruitCityPopulationRatio)
        //                     && ($this->rng->nextBool($remainPop / $maxPop))) return null;`
        val rngBoth = RecordingRng("recruit-seed")
        GenDomesticFamily.recruitSkipGate(popRatioBelowSafe = true, remainPop = 3000, maxPop = 10000, rng = rngBoth)
        assertEquals(1, rngBoth.nextBoolCalls, "popRatio<safe → the && right operand draws nextBool(remainPop/maxPop) (PHP :2512)")
        assertEquals(3000.0 / 10000.0, rngBoth.draws.first().prob, 1e-12, "prob = remainPop / maxPop (PHP :2512)")

        val rngSafe = RecordingRng("recruit-seed")
        GenDomesticFamily.recruitSkipGate(popRatioBelowSafe = false, remainPop = 3000, maxPop = 10000, rng = rngSafe)
        assertEquals(0, rngSafe.nextBoolCalls, "popRatio>=safe → && short-circuits → ZERO draws (PHP :2511 left operand false)")
    }

    @Test
    fun `recruit arm type pick is a single weighted choice over the 4-entry insertion-ordered armType list`() {
        // PHP :2554 `$armType = $this->rng->choiceUsingWeight($availableArmType);` — insertion order
        // FOOTMAN(1), ARCHER(2), CAVALRY(3), WIZARD(4) (catalog §5.L / GAPS G7). One nextFloat1 regardless of size.
        val rng = RecordingRng("recruit-seed")
        val availableArmType = linkedMapOf(
            GenDomesticFamily.T_FOOTMAN to 80.0,
            GenDomesticFamily.T_ARCHER to 60.0,
            GenDomesticFamily.T_CAVALRY to 70.0,
            GenDomesticFamily.T_WIZARD to 30.0,
        )
        val picked = GenDomesticFamily.pickArmType(availableArmType, rng = rng)
        assertEquals(1, rng.nextFloat1Calls, "exactly ONE choiceUsingWeight draw (PHP :2554)")
        assertTrue(
            picked in listOf(
                GenDomesticFamily.T_FOOTMAN, GenDomesticFamily.T_ARCHER,
                GenDomesticFamily.T_CAVALRY, GenDomesticFamily.T_WIZARD,
            ),
            "the picked armType is one of the 4 insertion-ordered keys (FOOTMAN/ARCHER/CAVALRY/WIZARD)",
        )
    }

    @Test
    fun `arm type constants match PHP GameUnitConst`() {
        // GameUnitConstBase.php:17-21 — the 4-entry insertion order is FOOTMAN(1),ARCHER(2),CAVALRY(3),WIZARD(4).
        assertEquals(1, GenDomesticFamily.T_FOOTMAN)
        assertEquals(2, GenDomesticFamily.T_ARCHER)
        assertEquals(3, GenDomesticFamily.T_CAVALRY)
        assertEquals(4, GenDomesticFamily.T_WIZARD)
    }

    @Test
    fun `recruit crew type pick is a single weighted choice over the pickScore-weighted types`() {
        // PHP :2580 `$type = $this->rng->choiceUsingWeight($types);` (always, post arm type). One nextFloat1.
        val rng = RecordingRng("recruit-seed")
        val types = listOf(1100 to 90.0, 1101 to 70.0, 1102 to 50.0)
        val picked = GenDomesticFamily.pickCrewType(types, rng = rng)
        assertEquals(1, rng.nextFloat1Calls, "exactly ONE choiceUsingWeight draw over pickScore types (PHP :2580)")
        assertTrue(picked in listOf(1100, 1101, 1102))
    }

    @Test
    fun `recruit two armType-then-crewType picks make exactly two weighted draws in order`() {
        // The no-preset-armType path: choiceUsingWeight(armType) THEN choiceUsingWeight(types) — TWO draws.
        val rng = RecordingRng("recruit-seed")
        GenDomesticFamily.pickArmType(
            linkedMapOf(GenDomesticFamily.T_FOOTMAN to 80.0, GenDomesticFamily.T_ARCHER to 60.0), rng = rng,
        )
        GenDomesticFamily.pickCrewType(listOf(1100 to 90.0, 1101 to 70.0), rng = rng)
        assertEquals(
            listOf("nextFloat1", "nextFloat1"),
            rng.draws.map { it.kind },
            "armType pick (:2554) THEN crewType pick (:2580) = two choiceUsingWeight draws in order",
        )
    }

    @Test
    fun `recruit half-crew fallback rounds crew minus 49 at the hundreds place half-away (PhpRound -2)`() {
        // PHP :2635 `$crew = Util::round($crew - 49, -2);` — half-AWAY-from-zero at the 100s place (NOT Math.round,
        // NOT phpRound(v/100)*100 — M5). e.g. crew=8000 → round(7951,-2)=8000; crew=8100 → round(8051,-2)=8100.
        assertEquals(8000, GenDomesticFamily.halfCrew(crewBeforeHalf = 8000), "round(8000-49,-2)=round(7951,-2)=8000")
        assertEquals(8100, GenDomesticFamily.halfCrew(crewBeforeHalf = 8100), "round(8100-49,-2)=round(8051,-2)=8100")
        // crew=8049 → round(8000,-2)=8000 (8049-49=8000); crew=8050 → round(8001,-2)=8000.
        assertEquals(8000, GenDomesticFamily.halfCrew(crewBeforeHalf = 8049), "round(8049-49,-2)=round(8000,-2)=8000")
        assertEquals(8000, GenDomesticFamily.halfCrew(crewBeforeHalf = 8050), "round(8050-49,-2)=round(8001,-2)=8000")
        // crew=8149 → 8149-49=8100 → round(8100,-2)=8100.
        assertEquals(8100, GenDomesticFamily.halfCrew(crewBeforeHalf = 8149), "round(8149-49,-2)=round(8100,-2)=8100")
    }

    @Test
    fun `action codes match the emitted che commands`() {
        assertEquals("che_군량매매", GenDomesticFamily.TRADE_ACTION)
        assertEquals("che_징병", GenDomesticFamily.RECRUIT_ACTION)
        assertEquals("che_모병", GenDomesticFamily.RECRUIT_HIRE_ACTION)
    }

    @Test
    fun `the deterministic ladder uses truncate-toward-zero not floor for negative deltas`() {
        // toInt is TRUNCATE-toward-zero (phpToInt), distinct from floor. Guard the convention.
        val rng = RecordingRng("rice-seed")
        // (high-low)=-100, /(1+1)=-50 → toInt(-50)=-50 → valueFit(-50,100,max)=100 (lower clamp).
        val amount = GenDomesticFamily.tradeAmount(
            relHigh = 100.0, relLow = 200.0, deathRate = 1.0, maxResourceActionAmount = 1_000_000, rng = rng,
        )
        assertEquals(100, amount, "negative delta truncates then lower-clamps to 100 (PHP toInt + valueFit)")
        assertFalse(rng.nextBoolCalls > 0, "no draw")
    }
}
