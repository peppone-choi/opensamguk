package opensamguk.logic.ai.families

import opensamguk.common.rng.LiteHashDrbg
import opensamguk.common.rng.RandUtil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * L-GENWAR — GenWarMoveFamily (전투준비 / 소집해제 / 출병 / 헌납 / 워프트리오 / 귀환 / 집합 / 방랑군이동):
 * the per-method DRAW STREAM off the shared `"GeneralAI"` [RandUtil].
 *
 * GRAND TRUTH = PHP `legacy/devsam-core/hwe/sammo/GeneralAI.php` (read in full):
 *  - `do전투준비`   (`:2653-2682`, emits che_훈련/che_사기진작): one terminal `choiceUsingWeightPair($cmdList)`
 *    (`:2681`, only when `$cmdList` non-empty; `:2678` empty-guard returns null with NO draw).
 *  - `do소집해제`   (`:2684-2703`, emits che_소집해제): `nextBool(0.75)` (`:2695`) **ALWAYS drawn** (no `&&` guard;
 *    reached after 3 non-RNG early-returns).
 *  - `do출병`       (`:2706-2775`, emits che_출병): the `nextBool(0.7)` at `:2720` is pulled via `&&`
 *    (`nation.rice<baserice && npc>=2 && nextBool(0.7)`) **BEFORE** four non-RNG early-returns
 *    (`:2729/2732/2735/2739`) — pin the EXACT statement order (m1). Then `choice($attackableCities)` (`:2769`,
 *    THROWS if none — guard traced, m3).
 *  - `doNPC헌납`    (`:2785-2863`, emits che_헌납): the `nextBool(($genRes/$reqRes)-0.5)` at `:2841` is drawn
 *    **PER-RESOURCE** inside `foreach(resourceMap)` rice-then-gold (`:2796`) → VARIABLE 0-2 draws (m2), used
 *    as `!nextBool(...)` (the continue-gate); then ONE terminal `choiceUsingWeightPair($args)` (`:2858`).
 *  - `do후방워프`   (`:2866-2970`, emits che_NPC능동): one `choiceUsingWeight($recruitableCityList)` (`:2960`).
 *  - `do전방워프`   (`:2972-3020`, emits che_NPC능동): one `choiceUsingWeight($candidateCities)` (`:3011`).
 *  - `do내정워프`   (`:3022-3092`, emits che_NPC능동): `nextBool(0.6)` (`:3029`) **ALWAYS** → `nextBool($warpProp)`
 *    (`:3050`, may consume 0 underlying draws at the `>=1`/`<=0` boundary, used as `!nextBool(...)`)
 *    → `choiceUsingWeight($candidateCities)` (`:3085`).
 *  - `do귀환`       (`:3095-3109`, emits che_귀환): **ZERO RNG draws** (deterministic supply/nation gate).
 *  - `do집합`       (`:3111-3125`, emits che_집합): **GATE-EXEMPT** (no `hasFullConditionMet`) but NOT draw-free —
 *    `getNPCType()==5` draws `nextRangeInt(2,4)` (`:3116`) and writes `killturn` = `(killturn+draw)%5 + 70`
 *    as a ChangeRecorder delta (m4, decision #12); else ZERO draws.
 *  - `do방랑군이동`  (`:3127-3215`, emits che_인재탐색/che_이동): up to 2 `choiceUsingWeightPair` (`:3180` target
 *    pick when no cached movingTargetCityID; `:3208` next-hop pick); the `choice([])` empty paths are guarded
 *    by `:3177`/`:3203` empty-candidate returns (m3).
 *
 * The family is PURE (mirrors GenFoundFamily/GenDomesticFamily/NationDeployFamily/NationRewardFamily/
 * NationDiploFamily): the candidate-set construction (backupCities/supplyCities buckets, the BFS dist maps,
 * the resourceMap thresholds, the develRate warpProp product, the cmdList weights, the hasFullConditionMet
 * gates) is the foundations'/adapter's job; the family owns the per-method DRAW ORDER + COUNT, the emitted
 * RAW arg shape, and the deterministic killturn arithmetic. F-BRIDGE's `candidateAllowed` gate (the PHP
 * `hasFullConditionMet()` analogue) accepts/rejects the emit — the family does NOT re-implement that gate.
 */
class GenWarMoveFamilyTest {

    /**
     * A draw-recording RNG over a REAL [LiteHashDrbg]. Records the top-level SEMANTIC draw stream
     * (`nextBool`/`nextBit`/`nextRangeInt`/`choiceUsingWeightPair`/`choice`); a re-entrancy guard ensures
     * an inner `nextFloat1`/`nextBit` consumed BY a `nextBool` is NOT double-recorded; a directly-recorded
     * `nextFloat1` is a `choiceUsingWeight(Pair)` draw. `nextBoolCalls` counts EVERY `nextBool` invocation
     * (including short-circuits that consume no underlying byte); `nextFloat1Calls`/`nextBitCalls`/
     * `nextRangeIntCalls` count ONLY underlying primitive consumption.
     */
    private class RecordingRng(seed: String) : RandUtil(LiteHashDrbg(seed)) {
        data class Draw(val kind: String, val prob: Double)
        val draws = ArrayList<Draw>()
        var nextBoolCalls = 0
        var nextFloat1Calls = 0
        var nextBitCalls = 0
        var nextRangeIntCalls = 0
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
            if (!inNextBool) draws.add(Draw("nextFloat1", -1.0)) // top-level float = choiceUsingWeight(Pair).
            return super.nextFloat1()
        }
        override fun nextBit(): Boolean {
            nextBitCalls++
            if (!inNextBool) draws.add(Draw("nextBit", 0.5))
            return super.nextBit()
        }
        override fun nextRangeInt(minInclusive: Int, maxInclusive: Int): Int {
            nextRangeIntCalls++; draws.add(Draw("nextRangeInt", -1.0))
            return super.nextRangeInt(minInclusive, maxInclusive)
        }
    }

    // ==================================================================================================
    // (1) do전투준비 — one terminal choiceUsingWeightPair (:2681); empty cmdList → null, NO draw (:2678).
    // ==================================================================================================

    @Test
    fun `battle-prep terminal pick is a single choiceUsingWeightPair over the cmdList`() {
        // PHP :2681 `return $this->rng->choiceUsingWeightPair($cmdList);` (only when $cmdList non-empty).
        val rng = RecordingRng("warmove-seed")
        val cmdList = listOf("che_훈련" to 12.0, "che_사기진작" to 8.0)
        val picked = GenWarMoveFamily.pickBattlePrepCommand(cmdList, rng = rng)
        assertEquals(1, rng.nextFloat1Calls, "exactly ONE choiceUsingWeightPair draw (PHP :2681)")
        assertTrue(picked in listOf("che_훈련", "che_사기진작"))
    }

    @Test
    fun `battle-prep empty cmdList returns null with ZERO draws`() {
        // PHP :2678 `if (!$cmdList) return null;` — the empty guard precedes the choiceUsingWeightPair draw.
        val rng = RecordingRng("warmove-seed")
        val picked = GenWarMoveFamily.pickBattlePrepCommand(emptyList(), rng = rng)
        assertEquals(null, picked, "empty cmdList → null (PHP :2678)")
        assertEquals(0, rng.nextBoolCalls + rng.nextFloat1Calls + rng.nextBitCalls, "no draw on the empty path")
    }

    // ==================================================================================================
    // (2) do소집해제 — nextBool(0.75) ALWAYS (:2695, no && guard).
    // ==================================================================================================

    @Test
    fun `disband skip roll always draws nextBool of 0_75`() {
        // PHP :2695 `if ($this->rng->nextBool(0.75)) return null;` — NO `&&` guard; ALWAYS draws.
        val rng = RecordingRng("warmove-seed")
        GenWarMoveFamily.disbandSkip(rng = rng)
        assertEquals(1, rng.nextBoolCalls, "the disband skip nextBool(0.75) ALWAYS draws (PHP :2695)")
        assertEquals(0.75, rng.draws.first().prob, "the disband skip prob is 0.75")
    }

    // ==================================================================================================
    // (3) do출병 — nextBool(0.7) via && BEFORE four non-RNG early-returns (:2720); choice(attackableCities) (:2769).
    // ==================================================================================================

    @Test
    fun `sortie skip roll draws nextBool of 0_7 ONLY when low-rice AND npc-at-least-2 (PHP 2720 && chain)`() {
        // PHP :2720 `if (($nation['rice'] < baserice) && $general->getNPCType() >= 2 && nextBool(0.7)) return null;`
        // The `&&` chain short-circuits left-to-right: BOTH left operands must be true to reach nextBool(0.7).
        val rngBoth = RecordingRng("warmove-seed")
        GenWarMoveFamily.sortieSkip(riceBelowBaserice = true, npcAtLeast2 = true, rng = rngBoth)
        assertEquals(1, rngBoth.nextBoolCalls, "low-rice AND npc>=2 → the && chain reaches nextBool(0.7) (PHP :2720)")
        assertEquals(0.7, rngBoth.draws.first().prob, "the sortie skip prob is 0.7")

        val rngLowRiceOnly = RecordingRng("warmove-seed")
        GenWarMoveFamily.sortieSkip(riceBelowBaserice = true, npcAtLeast2 = false, rng = rngLowRiceOnly)
        assertEquals(0, rngLowRiceOnly.nextBoolCalls, "npc<2 → the second && short-circuits → ZERO draws (PHP :2720)")

        val rngHighRice = RecordingRng("warmove-seed")
        GenWarMoveFamily.sortieSkip(riceBelowBaserice = false, npcAtLeast2 = true, rng = rngHighRice)
        assertEquals(0, rngHighRice.nextBoolCalls, "rice>=baserice → the first && short-circuits → ZERO draws (PHP :2720)")
    }

    @Test
    fun `sortie target pick is a single choice over the attackable cities (the draw fires before the gate)`() {
        // PHP :2769 `'destCityID' => $this->rng->choice($attackableCities)`. `RandUtil.choice` consumes one
        // underlying nextInt off the SAME shared DRBG (not one of the overridable nextBool/nextFloat1 hooks),
        // so the byte-level draw is asserted at the gate; here we assert the pick is a valid member + insertion
        // order is preserved (choice walks the list by index — the candidate ORDER is the parity target).
        val rng = RecordingRng("warmove-seed")
        val attackableCities = listOf(11, 22, 33)
        val picked = GenWarMoveFamily.pickSortieTarget(attackableCities, rng = rng)
        assertTrue(picked in attackableCities, "the picked target is one of the insertion-ordered attackable cities")
    }

    @Test
    fun `sortie target pick throws on an empty list (PHP choice empty = nextInt minus one, guard traced m3)`() {
        // m3: choice([]) is unreachable in PHP because :2765 throws RuntimeException('출병 불가') BEFORE :2769.
        // The family mirrors RandUtil.choice's empty-throw; the adapter must guard upstream (never pass []).
        val rng = RecordingRng("warmove-seed")
        var threw = false
        try {
            GenWarMoveFamily.pickSortieTarget(emptyList(), rng = rng)
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw, "choice([]) throws (PHP nextInt(-1)); the :2765 count==0 guard makes it unreachable (m3)")
    }

    // ==================================================================================================
    // (4) doNPC헌납 — nextBool((genRes/reqRes)-0.5) PER-RESOURCE in foreach rice-then-gold (:2841) → VARIABLE 0-2;
    //     terminal choiceUsingWeightPair($args) (:2858). Used as `!nextBool(...)` (the continue-gate).
    // ==================================================================================================

    @Test
    fun `tribute resource gate draws nextBool of genRes-over-reqRes-minus-0_5 ONLY when reqRes positive`() {
        // PHP :2841 `if ($reqRes > 0 && !$this->rng->nextBool(($genRes/$reqRes) - 0.5)) { continue; }`
        // The `&&` short-circuits: reqRes<=0 → the right operand (nextBool) is NEVER reached (ZERO draws).
        val rngPos = RecordingRng("tribute-seed")
        GenWarMoveFamily.tributeResourceGate(reqResPositive = true, genRes = 9000.0, reqRes = 3000.0, rng = rngPos)
        assertEquals(1, rngPos.nextBoolCalls, "reqRes>0 → the && right operand draws nextBool((genRes/reqRes)-0.5) (PHP :2841)")
        assertEquals(9000.0 / 3000.0 - 0.5, rngPos.draws.first().prob, 1e-12, "prob = (genRes/reqRes) - 0.5 (PHP :2841)")

        val rngZero = RecordingRng("tribute-seed")
        GenWarMoveFamily.tributeResourceGate(reqResPositive = false, genRes = 9000.0, reqRes = 0.0, rng = rngZero)
        assertEquals(0, rngZero.nextBoolCalls, "reqRes<=0 → && short-circuits → ZERO draws (PHP :2841 left operand false)")
    }

    @Test
    fun `tribute gate draws PER-RESOURCE rice-then-gold for a VARIABLE 0-to-2 stream (m2)`() {
        // The :2796 foreach walks resourceMap rice-then-gold; each iteration MAY reach the :2841 nextBool.
        // Two reqRes>0 resources → two draws in rice,gold order (m2 — NOT a single draw).
        val rngTwo = RecordingRng("tribute-seed")
        GenWarMoveFamily.tributeResourceGate(reqResPositive = true, genRes = 9000.0, reqRes = 3000.0, rng = rngTwo) // rice
        GenWarMoveFamily.tributeResourceGate(reqResPositive = true, genRes = 8000.0, reqRes = 2000.0, rng = rngTwo) // gold
        assertEquals(
            listOf("nextBool" to (9000.0 / 3000.0 - 0.5), "nextBool" to (8000.0 / 2000.0 - 0.5)),
            rngTwo.draws.map { it.kind to it.prob },
            "two reqRes>0 resources → two nextBool draws rice-then-gold (m2 variable per-resource)",
        )
        assertEquals(2, rngTwo.nextBoolCalls, "exactly two per-resource draws")

        // The rice-skips-gold case: rice short-circuits (reqRes<=0) but gold draws → only ONE draw.
        val rngSkip = RecordingRng("tribute-seed")
        GenWarMoveFamily.tributeResourceGate(reqResPositive = false, genRes = 0.0, reqRes = 0.0, rng = rngSkip) // rice skips
        GenWarMoveFamily.tributeResourceGate(reqResPositive = true, genRes = 8000.0, reqRes = 2000.0, rng = rngSkip) // gold draws
        assertEquals(1, rngSkip.nextBoolCalls, "rice short-circuits, gold draws → ONE draw (variable 0-2, m2)")
    }

    @Test
    fun `tribute terminal pick is a single choiceUsingWeightPair over the args`() {
        // PHP :2858 `$this->rng->choiceUsingWeightPair($args)` (the args list is non-empty here; :2854 guards null).
        val rng = RecordingRng("tribute-seed")
        val args = listOf(GenWarMoveFamily.TributeArg(isGold = false, amount = 6000) to 6000.0,
            GenWarMoveFamily.TributeArg(isGold = true, amount = 5000) to 5000.0)
        val picked = GenWarMoveFamily.pickTributeArg(args, rng = rng)
        assertEquals(1, rng.nextFloat1Calls, "exactly ONE choiceUsingWeightPair draw (PHP :2858)")
        assertTrue(picked!!.amount in listOf(6000, 5000))
    }

    @Test
    fun `tribute empty args returns null with ZERO draws`() {
        // PHP :2854 `if (!$args) return null;` — the empty guard precedes the choiceUsingWeightPair draw.
        val rng = RecordingRng("tribute-seed")
        val picked = GenWarMoveFamily.pickTributeArg(emptyList(), rng = rng)
        assertEquals(null, picked, "empty args → null (PHP :2854)")
        assertEquals(0, rng.nextBoolCalls + rng.nextFloat1Calls + rng.nextBitCalls, "no draw on the empty path")
    }

    // ==================================================================================================
    // (5) warp trio — 후방워프 :2960 choiceUsingWeight ; 전방워프 :3011 choiceUsingWeight ;
    //     내정워프 :3029 nextBool(0.6) ALWAYS → :3050 nextBool(warpProp) → :3085 choiceUsingWeight.
    // ==================================================================================================

    @Test
    fun `backup warp dest pick is a single choiceUsingWeight over the recruitable city list`() {
        // PHP :2960 `'destCityID' => $this->rng->choiceUsingWeight($recruitableCityList)`. ONE nextFloat1.
        val rng = RecordingRng("warp-seed")
        val recruitableCityList = linkedMapOf(11 to 0.8, 22 to 0.6, 33 to 0.5)
        val picked = GenWarMoveFamily.pickBackupWarpDest(recruitableCityList, rng = rng)
        assertEquals(1, rng.nextFloat1Calls, "exactly ONE choiceUsingWeight draw (PHP :2960)")
        assertTrue(picked in listOf(11, 22, 33))
    }

    @Test
    fun `front warp dest pick is a single choiceUsingWeight over the candidate cities`() {
        // PHP :3011 `'destCityID' => $this->rng->choiceUsingWeight($candidateCities)`. ONE nextFloat1.
        val rng = RecordingRng("warp-seed")
        val candidateCities = linkedMapOf(44 to 3.0, 55 to 2.0)
        val picked = GenWarMoveFamily.pickFrontWarpDest(candidateCities, rng = rng)
        assertEquals(1, rng.nextFloat1Calls, "exactly ONE choiceUsingWeight draw (PHP :3011)")
        assertTrue(picked in listOf(44, 55))
    }

    @Test
    fun `internal warp 60-percent skip always draws nextBool of 0_6`() {
        // PHP :3029 `if ($this->rng->nextBool(0.6)) return null;` — NO `&&` guard; ALWAYS draws.
        val rng = RecordingRng("warp-seed")
        GenWarMoveFamily.internalWarpSkip(rng = rng)
        assertEquals(1, rng.nextBoolCalls, "the internal-warp skip nextBool(0.6) ALWAYS draws (PHP :3029)")
        assertEquals(0.6, rng.draws.first().prob, "the internal-warp skip prob is 0.6")
    }

    @Test
    fun `internal warp proceed gate draws nextBool of warpProp and consumes ZERO underlying bytes at the boundary`() {
        // PHP :3050 `if (!$this->rng->nextBool($warpProp)) return null;`. warpProp is a product of develVals;
        // RandUtil.nextBool short-circuits: warpProp>=1 → true (NO underlying byte), warpProp<=0 → false (NO byte).
        val rngHigh = RecordingRng("warp-seed")
        GenWarMoveFamily.internalWarpProceedGate(warpProp = 1.0, rng = rngHigh)
        assertEquals(1, rngHigh.nextBoolCalls, "the warpProp gate still INVOKES nextBool (counts) (PHP :3050)")
        assertEquals(0, rngHigh.nextFloat1Calls + rngHigh.nextBitCalls,
            "warpProp>=1 → nextBool short-circuits → ZERO underlying bytes consumed (the boundary trap)")

        val rngZero = RecordingRng("warp-seed")
        GenWarMoveFamily.internalWarpProceedGate(warpProp = 0.0, rng = rngZero)
        assertEquals(0, rngZero.nextFloat1Calls + rngZero.nextBitCalls,
            "warpProp<=0 → nextBool short-circuits → ZERO underlying bytes consumed")

        val rngMid = RecordingRng("warp-seed")
        GenWarMoveFamily.internalWarpProceedGate(warpProp = 0.4, rng = rngMid)
        assertEquals(1, rngMid.nextFloat1Calls, "0<warpProp<1 → nextBool draws one nextFloat1 (PHP :3050)")
    }

    @Test
    fun `internal warp dest pick is a single choiceUsingWeight over the candidate cities`() {
        // PHP :3085 `'destCityID' => $this->rng->choiceUsingWeight($candidateCities)`. ONE nextFloat1.
        val rng = RecordingRng("warp-seed")
        val candidateCities = linkedMapOf(66 to 1.5, 77 to 2.5)
        val picked = GenWarMoveFamily.pickInternalWarpDest(candidateCities, rng = rng)
        assertEquals(1, rng.nextFloat1Calls, "exactly ONE choiceUsingWeight draw (PHP :3085)")
        assertTrue(picked in listOf(66, 77))
    }

    @Test
    fun `internal warp full stream is nextBool(0_6) then nextBool(warpProp) then choiceUsingWeight in order`() {
        // The full do내정워프 stream when neither skip returns and warpProp is mid-range:
        // :3029 nextBool(0.6) THEN :3050 nextBool(warpProp) THEN :3085 choiceUsingWeight — three draws in order.
        val rng = RecordingRng("warp-seed")
        GenWarMoveFamily.internalWarpSkip(rng = rng) // :3029
        GenWarMoveFamily.internalWarpProceedGate(warpProp = 0.4, rng = rng) // :3050
        GenWarMoveFamily.pickInternalWarpDest(linkedMapOf(66 to 1.5, 77 to 2.5), rng = rng) // :3085
        assertEquals(
            listOf("nextBool" to 0.6, "nextBool" to 0.4, "nextFloat1" to -1.0),
            rng.draws.map { it.kind to it.prob },
            "do내정워프 stream: :3029 nextBool(0.6) → :3050 nextBool(warpProp) → :3085 choiceUsingWeight",
        )
    }

    // ==================================================================================================
    // (6) do귀환 — ZERO RNG draws (deterministic supply/nation gate).
    // ==================================================================================================

    @Test
    fun `return command emit is deterministic with ZERO draws`() {
        // PHP :3095-3109 — do귀환 has no `$this->rng->*` call; it is a pure supply/nation gate.
        // The family exposes only the emitted action code; the gate (city.supply && own nation) is the adapter's.
        assertEquals("che_귀환", GenWarMoveFamily.RETURN_ACTION)
    }

    // ==================================================================================================
    // (7) do집합 — GATE-EXEMPT but npc==5 draws nextRangeInt(2,4) + killturn = (killturn+draw)%5 + 70 delta (m4).
    // ==================================================================================================

    @Test
    fun `assemble npc-5 killturn reroll draws nextRangeInt(2,4) and computes (killturn+draw) mod 5 plus 70 (m4)`() {
        // PHP :3116-3118 `$newKillTurn = ($general->getVar('killturn') + $this->rng->nextRangeInt(2,4)) % 5;
        //                  $newKillTurn += 70; $general->setVar('killturn', $newKillTurn);`
        // The nextRangeInt(2,4) is the ONLY draw; the killturn write is a ChangeRecorder delta (m4, decision #12).
        val rng = RecordingRng("assemble-seed")
        val result = GenWarMoveFamily.assembleKillturnReroll(currentKillturn = 13, rng = rng)
        assertEquals(1, rng.nextRangeIntCalls, "exactly ONE nextRangeInt(2,4) draw (PHP :3116)")
        // newKillTurn = (13 + draw) % 5 + 70, draw in {2,3,4} → (15..17)%5 + 70 ∈ {70,71,72}.
        assertTrue(result in 70..72, "killturn = (13+draw)%5 + 70 with draw in [2,4] → [70,72]")
    }

    @Test
    fun `assemble killturn arithmetic identity holds for an explicit draw value`() {
        // Pure arithmetic check independent of the seed: (killturn + draw) % 5 + 70.
        assertEquals(70 + (13 + 2) % 5, GenWarMoveFamily.computeAssembleKillturn(13, 2), "(13+2)%5 + 70 = 70 + 0 = 70")
        assertEquals(70 + (13 + 3) % 5, GenWarMoveFamily.computeAssembleKillturn(13, 3), "(13+3)%5 + 70 = 70 + 1 = 71")
        assertEquals(70 + (13 + 4) % 5, GenWarMoveFamily.computeAssembleKillturn(13, 4), "(13+4)%5 + 70 = 70 + 2 = 72")
        assertEquals(70 + (70 + 4) % 5, GenWarMoveFamily.computeAssembleKillturn(70, 4), "(70+4)%5 + 70 = 70 + 4 = 74")
    }

    @Test
    fun `assemble exposes the killturn meta-KV key and emitted action code`() {
        assertEquals("che_집합", GenWarMoveFamily.ASSEMBLE_ACTION)
        assertEquals("killturn", GenWarMoveFamily.KILLTURN_KEY)
    }

    // ==================================================================================================
    // (8) do방랑군이동 — up to 2 choiceUsingWeightPair (:3180 target ; :3208 next-hop); choice([]) guarded (m3).
    // ==================================================================================================

    @Test
    fun `wander target pick is a single choiceUsingWeightPair over the candidate cities`() {
        // PHP :3180 `$movingTargetCityID = $this->rng->choiceUsingWeightPair($candidateCities);`
        // (only when movingTargetCityID===null; the :3177 empty-guard returns null with NO draw).
        val rng = RecordingRng("wander-seed")
        val candidateCities = listOf(101 to 0.5, 102 to 0.25, 103 to 0.125)
        val picked = GenWarMoveFamily.pickWanderTarget(candidateCities, rng = rng)
        assertEquals(1, rng.nextFloat1Calls, "exactly ONE choiceUsingWeightPair draw (PHP :3180)")
        assertTrue(picked in listOf(101, 102, 103))
    }

    @Test
    fun `wander target empty candidates returns null with ZERO draws (m3 guard)`() {
        // PHP :3177 `if (!$candidateCities) return null;` — empty guard before :3180. m3: never choice([]).
        val rng = RecordingRng("wander-seed")
        val picked = GenWarMoveFamily.pickWanderTarget(emptyList(), rng = rng)
        assertEquals(null, picked, "empty candidateCities → null (PHP :3177)")
        assertEquals(0, rng.nextBoolCalls + rng.nextFloat1Calls + rng.nextBitCalls, "no draw on the empty path")
    }

    @Test
    fun `wander next-hop pick is a single choiceUsingWeightPair over the candidate cities`() {
        // PHP :3208 `'destCityID' => $this->rng->choiceUsingWeightPair($candidateCities)`
        // (the :3203 empty-guard returns null with NO draw).
        val rng = RecordingRng("wander-seed")
        val candidateCities = listOf(201 to 10.0, 202 to 1.0)
        val picked = GenWarMoveFamily.pickWanderNextHop(candidateCities, rng = rng)
        assertEquals(1, rng.nextFloat1Calls, "exactly ONE choiceUsingWeightPair draw (PHP :3208)")
        assertTrue(picked in listOf(201, 202))
    }

    @Test
    fun `wander next-hop empty candidates returns null with ZERO draws (m3 guard)`() {
        // PHP :3203 `if (!$candidateCities) return null;` — empty guard before :3208. m3: never choice([]).
        val rng = RecordingRng("wander-seed")
        val picked = GenWarMoveFamily.pickWanderNextHop(emptyList(), rng = rng)
        assertEquals(null, picked, "empty candidateCities → null (PHP :3203)")
        assertEquals(0, rng.nextBoolCalls + rng.nextFloat1Calls + rng.nextBitCalls, "no draw on the empty path")
    }

    // ==================================================================================================
    // emitted che_* action codes.
    // ==================================================================================================

    @Test
    fun `action codes match the emitted che commands`() {
        assertEquals("che_훈련", GenWarMoveFamily.TRAIN_ACTION)
        assertEquals("che_사기진작", GenWarMoveFamily.MORALE_ACTION)
        assertEquals("che_소집해제", GenWarMoveFamily.DISBAND_ACTION)
        assertEquals("che_출병", GenWarMoveFamily.SORTIE_ACTION)
        assertEquals("che_헌납", GenWarMoveFamily.TRIBUTE_ACTION)
        assertEquals("che_NPC능동", GenWarMoveFamily.WARP_ACTION)
        assertEquals("che_귀환", GenWarMoveFamily.RETURN_ACTION)
        assertEquals("che_집합", GenWarMoveFamily.ASSEMBLE_ACTION)
        assertEquals("che_인재탐색", GenWarMoveFamily.WANDER_SEARCH_ACTION)
        assertEquals("che_이동", GenWarMoveFamily.WANDER_MOVE_ACTION)
        assertEquals("순간이동", GenWarMoveFamily.WARP_OPTION_TEXT)
    }
}
