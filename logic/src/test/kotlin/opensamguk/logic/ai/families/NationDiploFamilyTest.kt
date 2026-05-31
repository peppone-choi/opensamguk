package opensamguk.logic.ai.families

import opensamguk.common.rng.LiteHashDrbg
import opensamguk.common.rng.RandUtil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * L-DIPLO — NationDiploFamily (불가침제의 / 선전포고 / 천도): SELECTION + boolean gate + draw stream ONLY.
 *
 * GRAND TRUTH = PHP `legacy/devsam-core/hwe/sammo/GeneralAI.php` (read in full):
 *  - `do불가침제의` (`:1765-1846`, emits `che_불가침제의`; ZERO RNG draws; stable `arsort` + 8-month cooldown +
 *    writes `resp_assist_try` ChangeRecorder delta BELOW the gate),
 *  - `do선전포고`   (`:1848-1973`, emits `che_선전포고`; up to 3 draws A+B+C; sets `reqUpdateInstance=true`),
 *  - `do천도`       (`:1976-2113`, emits `che_천도`; persistence-or-arsort path; `choice` next-hop ONLY if dist>1;
 *    writes `last천도Trial` ChangeRecorder delta).
 *
 * **SELECTION + the boolean gate + the draw stream ONLY (decision #11); the `che_*` state-mutation/logs are P6.
 * The G-GATE downstream-delta/log assertion EXCLUDES these three (m10).**
 *
 * ## The load-bearing parity facts (catalog §5.D/§5.M, R-GATE §3, M3)
 *  1. **do불가침제의: ZERO draws** — pure deterministic selection: filter `recv_assist` (the 8-month cooldown
 *     `respAssistTry[..][1] >= yearMonth - 8`, the `joinYearMonth` gate `:1791`, H-HELPERS §1), ONE stable
 *     `arsort($candidateList)` DESC (`:1814`), walk candidates and pick the first where `amount*4 >= income`
 *     (else break), then write `resp_assist_try["n{dest}"] = [dest, yearMonth]` BELOW the gate (decision #12).
 *  2. **do선전포고: up to 3 draws A+B+C — PHP WINS the TS 2-draw shape (M3, catalog §5.D):**
 *     (A) `nextBool($trialProp ** 6)` (`:1923`) — may consume 0 OR 1 draw when trialProp≥1/≤0,
 *     (B) `nextBool(1 / count($lowTargetNations))` (`:1959`) in the EMPTY-`$nations` fallback — TS DROPS this,
 *     (C) `choiceUsingWeight($nations)` weight `1/sqrt(power+1)` (`:1966`), insertion = `getAllNationStaticInfo`
 *     order. Sets `reqUpdateInstance=true` (`:1972`).
 *  3. **do천도: arsort score-DESC → top-quartile gate → `choice(candidates)` next-hop ONLY if `dist>1`** (TS
 *     DROPS the cooldown/sticky/BFS-restriction — PHP WINS): persistence path (`lastTurn->getCommand()==='천도'`
 *     `:1981`) re-emits with NO draw; else `arsort($cityScoreList)` (`:2059`), `enoughLimit = ceil(count*0.25)`
 *     top-quartile gate (`:2061`), `finalCityID = array_key_first` (`:2067`), `choice($candidates)` (`:2100`)
 *     ONLY when `dist>1`. Writes `last천도Trial` (`:2111`).
 *
 * The family is PURE (mirrors GenFoundFamily/NationDeployFamily/NationRewardFamily): the candidate-set
 * construction (income/recvAssist filter, isNeighbor partition, BFS distance maps, top-quartile bookkeeping) is
 * the foundations'/adapter's job; the family owns the per-method DRAW ORDER + COUNT + the emitted RAW arg map +
 * the reqUpdateInstance signal + the meta-KV delta key. F-BRIDGE's `candidateAllowed` gate (the
 * `hasFullConditionMet()` analogue) accepts/rejects the emit — the family does NOT re-implement it.
 */
class NationDiploFamilyTest {

    /**
     * A draw-recording RNG over a REAL [LiteHashDrbg]. Records the **top-level SEMANTIC** draw stream
     * (`nextBool`/`nextBit`/`choice`/`choiceUsingWeightPair`) — not the underlying primitive bytes. A
     * re-entrancy guard ensures an inner `nextFloat1`/`nextBit` consumed BY a `nextBool` is NOT double-recorded;
     * a `nextFloat1` recorded directly is the `choiceUsingWeightPair` (the (C) target-pick draw). `nextBoolCalls`
     * counts EVERY `nextBool` invocation (including short-circuits that consume no underlying byte);
     * `nextFloat1Calls`/`nextBitCalls` count ONLY underlying primitive bytes actually consumed.
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
            if (!inNextBool) draws.add(Draw("nextFloat1", -1.0)) // top-level float = choiceUsingWeightPair (C).
            return super.nextFloat1()
        }
        override fun nextBit(): Boolean {
            nextBitCalls++
            if (!inNextBool) draws.add(Draw("nextBit", 0.5))
            return super.nextBit()
        }
        override fun <T> choice(items: List<T>): T {
            draws.add(Draw("choice", items.size.toDouble())); return super.choice(items)
        }
        /** Only the `choice`-kind draws, in order (the relocate next-hop assertion). */
        fun choiceDraws(): List<String> = draws.filter { it.kind == "choice" }.map { it.kind }
    }

    // ==================================================================================================
    // (1) do불가침제의 — ZERO RNG draws + stable arsort pick + resp_assist_try delta + 8-month cooldown gate.
    // ==================================================================================================

    @Test
    fun `nonAggression candidate selection makes ZERO RNG draws`() {
        val rng = RecordingRng("diplo-seed")
        // amount*4 >= income picks the FIRST (highest, after arsort DESC) qualifying candidate.
        val candidateList = linkedMapOf(2 to 1000, 3 to 800, 4 to 100)
        val picked = NationDiploFamily.pickNonAggressionTarget(candidateList, income = 3000.0, rng = rng)
        assertEquals(0, rng.nextBoolCalls, "do불가침제의 draws NO nextBool (catalog §6 zero-draw)")
        assertEquals(0, rng.nextFloat1Calls, "do불가침제의 draws NO nextFloat1")
        assertEquals(0, rng.nextBitCalls, "do불가침제의 draws NO nextBit (arsort-only, PHP :1814)")
        // dest=2 (amount 1000): 1000*4=4000 >= 3000 → picked; diplomatMonth = 24*1000/3000 = 8.0 (FLOAT, PHP :1819).
        assertEquals(2, picked?.destNationId)
        assertEquals(8.0, picked?.diplomatMonth, "diplomatMonth = 24*amount/income FLOAT (PHP :1819; trunc at parseYearMonth)")
    }

    @Test
    fun `nonAggression stable arsort picks the highest-amount candidate preserving insertion order on ties`() {
        // arsort DESC, STABLE on ties (PHP 8). The walk then takes the FIRST entry passing amount*4>=income.
        val candidateList = linkedMapOf(5 to 500, 6 to 900, 7 to 900, 8 to 200)
        val sorted = NationDiploFamily.sortNonAggressionCandidates(candidateList)
        // 900 ties (6 then 7) keep insertion order, then 500, then 200.
        assertEquals(listOf(6, 7, 5, 8), sorted.keys.toList(), "arsort DESC stable; the two 900s keep 6,7 (NO secondary comparator)")
    }

    @Test
    fun `nonAggression breaks (returns null) when no candidate reaches the income-quarter threshold`() {
        val rng = RecordingRng("diplo-seed")
        // income very high → amount*4 < income for ALL → break → null (PHP :1808-1810 `break`).
        val candidateList = linkedMapOf(2 to 100, 3 to 50)
        val picked = NationDiploFamily.pickNonAggressionTarget(candidateList, income = 100_000.0, rng = rng)
        assertNull(picked, "no candidate with amount*4 >= income → null (PHP :1822 destNationID===null)")
        assertEquals(0, rng.nextBoolCalls + rng.nextFloat1Calls + rng.nextBitCalls, "still ZERO draws")
    }

    @Test
    fun `nonAggression empty candidate list returns null`() {
        val rng = RecordingRng("diplo-seed")
        val picked = NationDiploFamily.pickNonAggressionTarget(linkedMapOf(), income = 3000.0, rng = rng)
        assertNull(picked, "empty candidateList → null (PHP :1799-1801 `if (!\$candidateList) return null;`)")
    }

    @Test
    fun `nonAggression cooldown gate uses joinYearMonth minus 8`() {
        // PHP :1791 `($respAssistTry["n{cand}"][1] ?? 0) >= $yearMonth - 8` → skip (still on cooldown).
        // joinYearMonth = year*12 + month - 1 (H-HELPERS §1). yearMonth here is the joined int.
        val yearMonth = 200 * 12 + 6 - 1 // = 2405
        assertTrue(
            NationDiploFamily.nonAggressionOnCooldown(lastTryYearMonth = yearMonth - 4, currentYearMonth = yearMonth),
            "a try 4 months ago is still within the 8-month cooldown → skip",
        )
        assertFalse(
            NationDiploFamily.nonAggressionOnCooldown(lastTryYearMonth = yearMonth - 9, currentYearMonth = yearMonth),
            "a try 9 months ago is past the 8-month cooldown → eligible",
        )
        assertTrue(
            NationDiploFamily.nonAggressionOnCooldown(lastTryYearMonth = yearMonth - 8, currentYearMonth = yearMonth),
            "exactly 8 months ago is STILL on cooldown (>= yearMonth-8, PHP :1791)",
        )
    }

    @Test
    fun `nonAggression writes the resp_assist_try delta keyed n-destNationID below the gate`() {
        // PHP :1843 `$respAssistTry["n{destNationID}"] = [$destNationID, $yearMonth];`
        val (key, value) = NationDiploFamily.respAssistTryDelta(destNationId = 7, yearMonth = 2405)
        assertEquals("n7", key, "resp_assist_try key = `n{destNationID}` (PHP :1843)")
        assertEquals(listOf(7, 2405), value, "value = [destNationID, yearMonth]")
        assertEquals(
            "resp_assist_try",
            NationDiploFamily.RESP_ASSIST_TRY_KEY,
            "the nation_env meta-KV key written below the gate (decision #12 ChangeRecorder delta)",
        )
    }

    // ==================================================================================================
    // (2) do선전포고 — up to 3 draws A+B+C. The empty-nations fallback B is PRESENT (M3, PHP WINS the TS 2).
    // ==================================================================================================

    @Test
    fun `warDeclaration trial gate A draws nextBool of trialProp pow 6`() {
        // (A) PHP :1923 `nextBool($trialProp)` where $trialProp was already `** 6` at :1922.
        val rng = RecordingRng("war-seed")
        // trialProp in (0,1) → a real nextFloat1 draw inside nextBool.
        NationDiploFamily.warTrialGate(trialPropPow6 = 0.3, rng = rng)
        assertEquals(1, rng.nextBoolCalls, "(A) one nextBool(trialProp**6) draw")
        assertEquals(0.3, rng.draws.first().prob, "the prob is the ALREADY-pow6 trialProp (PHP :1922-1923)")
    }

    @Test
    fun `warDeclaration trial gate A consumes ZERO draws when trialProp pow 6 is at-or-above 1`() {
        // PHP nextBool short-circuit: prob>=1 → true, NO draw (RandUtil.php:41).
        val rng = RecordingRng("war-seed")
        val proceed = NationDiploFamily.warTrialGate(trialPropPow6 = 1.5, rng = rng)
        assertTrue(proceed, "trialProp**6 >= 1 → proceed (nextBool returns true)")
        assertEquals(0, rng.nextFloat1Calls + rng.nextBitCalls, "prob>=1 → NO underlying float/bit draw consumed (RandUtil short-circuit)")
    }

    @Test
    fun `warDeclaration trial gate A consumes ZERO underlying draws when trialProp pow 6 is at-or-below 0`() {
        val rng = RecordingRng("war-seed")
        val proceed = NationDiploFamily.warTrialGate(trialPropPow6 = 0.0, rng = rng)
        assertFalse(proceed, "trialProp**6 <= 0 → abort (nextBool returns false)")
        assertEquals(0, rng.nextFloat1Calls + rng.nextBitCalls, "no underlying draw consumed (short-circuit)")
    }

    @Test
    fun `warDeclaration retarget abort B draws nextBool of 1 over lowTargetNations count`() {
        // (B) PHP :1959 `nextBool(1 / count($lowTargetNations))` — TS DROPS THIS, PHP WINS (3 vs 2 — M3).
        val rng = RecordingRng("war-seed")
        // count=4 → prob 0.25 (not 0.5, not >=1, not <=0) → a real nextFloat1 draw.
        NationDiploFamily.warRetargetAbort(lowTargetNationsCount = 4, rng = rng)
        assertEquals(1, rng.nextBoolCalls, "(B) one nextBool(1/count) draw — the empty-nations fallback (PHP :1959)")
        assertEquals(0.25, rng.draws.first().prob, "prob = 1 / count(lowTargetNations)")
    }

    @Test
    fun `warDeclaration emits 3 draws A then B then C in the empty-nations fallback path (PHP wins TS 2)`() {
        // The full A+B+C stream when $nations is empty but $warNations + $lowTargetNations are non-empty and
        // the (B) abort does NOT fire: A nextBool(trialProp**6) → B nextBool(1/count) → C choiceUsingWeight.
        val rng = RecordingRng("war-seed")
        // (A) proceed (0.3 → real float draw, value low enough to pass for this seed is not asserted; we assert
        //     the STREAM SHAPE, not the boolean — the boolean depends on the seed and is the gate's concern).
        NationDiploFamily.warTrialGate(trialPropPow6 = 0.99, rng = rng) // (A) one draw
        NationDiploFamily.warRetargetAbort(lowTargetNationsCount = 8, rng = rng) // (B) one draw
        // (C) choiceUsingWeight over the warNations promoted to $nations — ordered (nationId, weight) pairs.
        val nations = listOf(11 to (1.0 / Math.sqrt(5.0 + 1)), 12 to (1.0 / Math.sqrt(9.0 + 1)))
        val target = NationDiploFamily.pickWarTarget(nations, rng = rng) // (C) one nextFloat1 draw
        assertEquals(
            listOf("nextBool", "nextBool", "nextFloat1"),
            rng.draws.map { it.kind },
            "the empty-nations path emits A(nextBool) → B(nextBool) → C(choiceUsingWeight=nextFloat1) = 3 draws (PHP WINS the TS 2-draw shape)",
        )
        assertTrue(target == 11 || target == 12, "C returns one of the warNations keys (insertion = getAllNationStaticInfo order)")
    }

    @Test
    fun `warDeclaration target pick C is a single choiceUsingWeight over insertion-ordered nation weights`() {
        // (C) PHP :1966 `choiceUsingWeight($nations)` weight `1/sqrt(power+1)`; the candidate ORDER (insertion =
        // getAllNationStaticInfo) is the parity target. One nextFloat1 regardless of list size.
        val rng = RecordingRng("war-seed")
        val nations = listOf(
            21 to (1.0 / Math.sqrt(0.0 + 1)),
            22 to (1.0 / Math.sqrt(3.0 + 1)),
            23 to (1.0 / Math.sqrt(8.0 + 1)),
        )
        val target = NationDiploFamily.pickWarTarget(nations, rng = rng)
        assertEquals(1, rng.nextFloat1Calls, "exactly ONE choiceUsingWeight draw (PHP :1966)")
        assertTrue(target in listOf(21, 22, 23))
    }

    @Test
    fun `warDeclaration weight formula is one over sqrt of power plus one (favors weak nations)`() {
        // PHP :1955/:1957 `1 / sqrt($destNationPower + 1)`. Weaker power → higher weight.
        assertEquals(1.0 / Math.sqrt(1.0), NationDiploFamily.warTargetWeight(power = 0), 1e-12)
        assertEquals(1.0 / Math.sqrt(5.0), NationDiploFamily.warTargetWeight(power = 4), 1e-12)
        assertTrue(
            NationDiploFamily.warTargetWeight(power = 1) > NationDiploFamily.warTargetWeight(power = 100),
            "weaker nation (lower power) gets the higher weight",
        )
    }

    @Test
    fun `warDeclaration sets reqUpdateInstance true (PHP 1972)`() {
        assertTrue(
            NationDiploFamily.WAR_DECLARATION_REQUIRES_INSTANCE_UPDATE,
            "do선전포고 sets reqUpdateInstance=true after the gate (PHP :1972)",
        )
        assertEquals("che_선전포고", NationDiploFamily.WAR_DECLARATION_ACTION)
        assertEquals("che_불가침제의", NationDiploFamily.NON_AGGRESSION_ACTION)
        assertEquals("che_천도", NationDiploFamily.RELOCATE_ACTION)
    }

    // ==================================================================================================
    // (3) do천도 — arsort + top-quartile gate + choice next-hop ONLY if dist>1 + last천도Trial delta.
    // ==================================================================================================

    @Test
    fun `relocate next-hop choice draws ONLY when the distance to the target city is greater than 1`() {
        // PHP :2069-2101: `if ($dist > 1) { ... $targetCityID = $this->rng->choice($candidates); }`.
        val rng = RecordingRng("relocate-seed")
        // dist==1 → adjacent → NO draw, the final city IS the target.
        val target1 = NationDiploFamily.relocateNextHop(dist = 1, finalCityId = 42, candidates = emptyList(), rng = rng)
        assertEquals(42, target1, "dist==1 → no intermediate hop, target = finalCityID")
        assertEquals(0, rng.nextBoolCalls + rng.nextFloat1Calls, "dist<=1 → ZERO draws (choice not reached)")

        // dist>1 → one choice over the next-hop candidates.
        val target2 = NationDiploFamily.relocateNextHop(dist = 3, finalCityId = 42, candidates = listOf(7, 9), rng = rng)
        assertTrue(target2 == 7 || target2 == 9, "dist>1 → choice(\$candidates) intermediate stop")
    }

    @Test
    fun `relocate dist greater than 1 draws exactly one choice`() {
        val rng = RecordingRng("relocate-seed")
        NationDiploFamily.relocateNextHop(dist = 2, finalCityId = 42, candidates = listOf(3, 5, 8), rng = rng)
        assertEquals(
            listOf("choice"),
            rng.choiceDraws(),
            "dist>1 → exactly one choice(\$candidates) (PHP :2100)",
        )
    }

    @Test
    fun `relocate arsort orders city scores DESC stable and the top is array_key_first`() {
        // PHP :2059 `arsort($cityScoreList)` DESC → :2067 `array_key_first` = the top score's city.
        val scores = linkedMapOf(10 to 5.0, 11 to 9.0, 12 to 9.0, 13 to 2.0)
        val sorted = NationDiploFamily.sortCityScores(scores)
        assertEquals(listOf(11, 12, 10, 13), sorted.keys.toList(), "arsort DESC stable; the two 9.0s keep 11,12")
        assertEquals(11, sorted.keys.first(), "array_key_first = the top-score city (PHP :2067)")
    }

    @Test
    fun `relocate top-quartile gate returns true (abort) when the capital ranks within the top 25 percent`() {
        // PHP :2061 `$enoughLimit = ceil(count($cityScoreList) * 0.25);` then if the capital's idx <= enoughLimit
        // → return null (already good enough). ceil distinct from round (H-HELPERS §0).
        // 8 cities → enoughLimit = ceil(8*0.25) = ceil(2.0) = 2; idx 0,1,2 are within the quartile.
        assertTrue(NationDiploFamily.relocateCapitalIsGoodEnough(capitalRankIdx = 0, cityCount = 8), "idx 0 <= ceil(2) → good enough")
        assertTrue(NationDiploFamily.relocateCapitalIsGoodEnough(capitalRankIdx = 2, cityCount = 8), "idx 2 <= ceil(2) → good enough")
        assertFalse(NationDiploFamily.relocateCapitalIsGoodEnough(capitalRankIdx = 3, cityCount = 8), "idx 3 > ceil(2) → relocate")
        // 5 cities → enoughLimit = ceil(5*0.25) = ceil(1.25) = 2.
        assertTrue(NationDiploFamily.relocateCapitalIsGoodEnough(capitalRankIdx = 2, cityCount = 5), "ceil(1.25)=2 → idx 2 within quartile")
    }

    @Test
    fun `relocate writes the last천도Trial delta from officerLevel and turnTime below the gate`() {
        // PHP :2111 `$nationStor->last천도Trial = [$general->getVar('officer_level'), $general->getTurnTime()];`
        val value = NationDiploFamily.last천도TrialDelta(officerLevel = 12, turnTime = "200-06-01 03:00:00")
        assertEquals(listOf(12, "200-06-01 03:00:00"), value, "last천도Trial = [officer_level, turnTime] (PHP :2111)")
        assertEquals(
            "last천도Trial",
            NationDiploFamily.LAST_RELOCATE_TRIAL_KEY,
            "the nation_env meta-KV key written below the gate (decision #12 ChangeRecorder delta)",
        )
    }

    @Test
    fun `relocate persistence path re-emits with no draw when the last turn was 천도 to a non-capital`() {
        // PHP :1981 `if ($lastTurn->getCommand() === '천도' && $lastTurn->getArg()['destCityID'] != capital)`.
        assertTrue(
            NationDiploFamily.relocatePersistenceApplies(lastCommand = "천도", lastDestCityId = 7, capitalCityId = 3),
            "last 천도 to a non-capital → keep relocating (sticky, PHP :1981)",
        )
        assertFalse(
            NationDiploFamily.relocatePersistenceApplies(lastCommand = "천도", lastDestCityId = 3, capitalCityId = 3),
            "last 천도 already targeting the capital → not sticky",
        )
        assertFalse(
            NationDiploFamily.relocatePersistenceApplies(lastCommand = "내정", lastDestCityId = 7, capitalCityId = 3),
            "last command not 천도 → not sticky",
        )
    }
}
