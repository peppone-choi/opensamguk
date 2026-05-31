package opensamguk.logic.ai.families

import opensamguk.common.rng.LiteHashDrbg
import opensamguk.common.rng.RandUtil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * L-REWARD — NationRewardFamily (포상/긴급포상/몰수).
 *
 * GRAND TRUTH = PHP `legacy/devsam-core/hwe/sammo/GeneralAI.php` (read in full):
 *  - `do유저장긴급포상` (`:1224-1310`, che_포상, reqUpdateInstance AFTER gate),
 *  - `do유저장포상`     (`:1312-1417`, che_포상, NO reqUpdateInstance),
 *  - `doNPC긴급포상`    (`:1419-1511`, che_포상, reqUpdateInstance AFTER gate),
 *  - `doNPC포상`        (`:1513-1632`, che_포상, NO reqUpdateInstance, weight `max(warCnt,civilCnt)-idx`),
 *  - `doNPC몰수`        (`:1634-1762`, che_몰수, NO reqUpdateInstance, weight `takeAmount`).
 *
 * ## The load-bearing parity facts (catalog §5.C/§7, decision #2/#9)
 *  1. **ONE `choiceUsingWeightPair($candidateArgs)`** per method — the candidate list is built deterministically
 *     (nested `foreach resName` × `foreach generals`, NO draws), then a SINGLE draw picks the emit.
 *     An empty candidate list returns null with ZERO draws (PHP `if (!$candidateArgs) return null;`).
 *  2. **Weight = positional rank AFTER a STABLE `usort` — NO secondary comparator** (PHP 8 stable; AiUtils):
 *     `count($list) - $idx` for the 포상 single-list methods (`:1288/1382` etc.),
 *     `max(count(warGenerals), count(civilGenerals)) - $idx` for `doNPC포상` (`:1581/1620`),
 *     `$takeAmount` (the literal seized amount) for `doNPC몰수` (`:1726/1759`). A reorder on a tie shifts
 *     the `idx` → shifts the weight → shifts the single draw → desyncs every downstream draw.
 *  3. **The two 긴급 methods set `reqUpdateInstance=true` AFTER the `hasFullConditionMet()` gate** (decision #2
 *     dirty trigger); `do유저장포상`/`doNPC포상`/`doNPC몰수` do NOT. [RewardMethod.requiresInstanceUpdate]
 *     encodes the verbatim per-method signal; the dispatcher calls `markDirty()` only when it is set AND the
 *     gate passed.
 *  4. **The arg map insertion order** is `destGeneralID` THEN `isGold` THEN `amount` (the array-literal key
 *     order, a canonicalization parity target). `doNPC포상` value-ASC `usort`; `doNPC몰수` value-DESC `usort`
 *     (`- ($lhs <=> $rhs)`), NO secondary comparator either direction.
 *
 * The family is PURE (mirrors GenFoundFamily/NationDeployFamily): the candidate-set construction
 * (resource-threshold ladder, payAmount/takeAmount geomean math, killturn/reqMoney gates) is the
 * foundations'/adapter's job; the family owns the per-method WEIGHT + the SINGLE `choiceUsingWeightPair`
 * draw + the emitted RAW arg map + the reqUpdateInstance-after-gate signal. F-BRIDGE's `candidateAllowed`
 * gate (here the `hasFullConditionMet()` analogue) accepts/rejects the emit — the family does NOT re-implement it.
 */
class NationRewardFamilyTest {

    /** A draw-recording RNG over a REAL [LiteHashDrbg] — records the ordered (method,args) draw stream. */
    private class RecordingRng(seed: String) : RandUtil(LiteHashDrbg(seed)) {
        data class Draw(val kind: String, val n: Int)
        val draws = ArrayList<Draw>()
        override fun <T> choice(items: List<T>): T {
            draws.add(Draw("choice", items.size)); return super.choice(items)
        }
    }

    /** choiceUsingWeightPair draws ONE nextFloat1 — record it via a real DRBG subclass observing nextFloat1. */
    private class FloatRecordingRng(seed: String) : RandUtil(LiteHashDrbg(seed)) {
        var nextFloat1Calls = 0
        override fun nextFloat1(): Double {
            nextFloat1Calls++; return super.nextFloat1()
        }
    }

    // --------------------------------------------------------------------------------------------------
    // (1) ONE choiceUsingWeightPair per reward decision; empty candidate list → null with ZERO draws.
    // --------------------------------------------------------------------------------------------------

    @Test
    fun `pickReward draws exactly one choiceUsingWeightPair over the candidate list`() {
        val rng = FloatRecordingRng("reward-seed")
        val candidates = listOf(
            NationRewardFamily.rewardArg(101, isGold = true, amount = 500) to 3.0,
            NationRewardFamily.rewardArg(102, isGold = true, amount = 300) to 2.0,
            NationRewardFamily.rewardArg(103, isGold = true, amount = 100) to 1.0,
        )
        val picked = NationRewardFamily.pickReward(candidates, rng)
        assertEquals(1, rng.nextFloat1Calls, "exactly ONE choiceUsingWeightPair draw (PHP rng->choiceUsingWeightPair)")
        assertTrue(picked in candidates.map { it.first })
    }

    @Test
    fun `pickReward with no candidates returns null and draws nothing`() {
        val rng = FloatRecordingRng("reward-seed")
        val empty: List<Pair<Map<String, Any?>, Double>> = emptyList()
        val picked = NationRewardFamily.pickReward(empty, rng)
        assertNull(picked, "empty candidateArgs → null (PHP `if (!\$candidateArgs) return null;`)")
        assertEquals(0, rng.nextFloat1Calls, "empty candidate list → ZERO draws (no choiceUsingWeightPair)")
    }

    // --------------------------------------------------------------------------------------------------
    // (2) Weight = count - idx (포상) and max(warCnt,civilCnt) - idx (doNPC포상); stable usort, NO secondary.
    // --------------------------------------------------------------------------------------------------

    @Test
    fun `rankWeight is count minus idx (positional rank after stable sort)`() {
        // PHP `count($userWarGenerals) - $idx` (:1288): best (idx 0) = count, last (idx count-1) = 1.
        assertEquals(3, NationRewardFamily.rankWeight(count = 3, idx = 0))
        assertEquals(2, NationRewardFamily.rankWeight(count = 3, idx = 1))
        assertEquals(1, NationRewardFamily.rankWeight(count = 3, idx = 2))
    }

    @Test
    fun `npcRewardWeight is max of war and civil counts minus idx`() {
        // PHP doNPC포상 `max(count($npcWarGenerals), count($npcCivilGenerals)) - $idx` (:1581/1620).
        assertEquals(5, NationRewardFamily.npcRewardWeight(warCnt = 5, civilCnt = 3, idx = 0))
        assertEquals(4, NationRewardFamily.npcRewardWeight(warCnt = 5, civilCnt = 3, idx = 1))
        // when civil dominates, max picks civilCnt.
        assertEquals(7, NationRewardFamily.npcRewardWeight(warCnt = 2, civilCnt = 7, idx = 0))
    }

    @Test
    fun `stableSortAsc preserves insertion order on ties with no secondary comparator`() {
        // value-ASC usort (포상): `$lhs->getVar($resName) <=> $rhs->getVar($resName)`. Ties keep insertion order.
        data class G(val id: Int, val res: Int)
        val gens = listOf(G(201, 100), G(202, 100), G(203, 50), G(204, 100))
        val sorted = NationRewardFamily.stableSortAsc(gens) { it.res }
        assertEquals(
            listOf(203, 201, 202, 204),
            sorted.map { it.id },
            "value-ASC; the three res==100 ties keep insertion order 201,202,204 (NO secondary comparator)",
        )
    }

    @Test
    fun `stableSortDesc preserves insertion order on ties with no secondary comparator`() {
        // value-DESC usort (몰수): `- ($lhs <=> $rhs)`. Ties keep insertion order.
        data class G(val id: Int, val res: Int)
        val gens = listOf(G(301, 100), G(302, 200), G(303, 100), G(304, 200))
        val sorted = NationRewardFamily.stableSortDesc(gens) { it.res }
        assertEquals(
            listOf(302, 304, 301, 303),
            sorted.map { it.id },
            "value-DESC; the 200 ties keep 302,304 then the 100 ties keep 301,303 (NO secondary comparator)",
        )
    }

    // --------------------------------------------------------------------------------------------------
    // (3) reqUpdateInstance AFTER the gate — only the two 긴급 methods.
    // --------------------------------------------------------------------------------------------------

    @Test
    fun `only the 긴급 reward methods require an instance update after the gate`() {
        assertTrue(
            NationRewardFamily.RewardMethod.유저장긴급포상.requiresInstanceUpdate,
            "do유저장긴급포상 sets reqUpdateInstance=true AFTER gate (PHP :1308)",
        )
        assertTrue(
            NationRewardFamily.RewardMethod.NPC긴급포상.requiresInstanceUpdate,
            "doNPC긴급포상 sets reqUpdateInstance=true AFTER gate (PHP :1509)",
        )
        assertFalse(
            NationRewardFamily.RewardMethod.유저장포상.requiresInstanceUpdate,
            "do유저장포상 does NOT set reqUpdateInstance (PHP :1417)",
        )
        assertFalse(
            NationRewardFamily.RewardMethod.NPC포상.requiresInstanceUpdate,
            "doNPC포상 does NOT set reqUpdateInstance (PHP :1632)",
        )
        assertFalse(
            NationRewardFamily.RewardMethod.NPC몰수.requiresInstanceUpdate,
            "doNPC몰수 does NOT set reqUpdateInstance (PHP :1762)",
        )
    }

    @Test
    fun `che_포상 methods emit che_포상 and 몰수 emits che_몰수`() {
        assertEquals("che_포상", NationRewardFamily.RewardMethod.유저장긴급포상.actionCode)
        assertEquals("che_포상", NationRewardFamily.RewardMethod.유저장포상.actionCode)
        assertEquals("che_포상", NationRewardFamily.RewardMethod.NPC긴급포상.actionCode)
        assertEquals("che_포상", NationRewardFamily.RewardMethod.NPC포상.actionCode)
        assertEquals("che_몰수", NationRewardFamily.RewardMethod.NPC몰수.actionCode)
    }

    // --------------------------------------------------------------------------------------------------
    // (4) The RAW arg map insertion order — destGeneralID, isGold, amount.
    // --------------------------------------------------------------------------------------------------

    @Test
    fun `rewardArg insertion order is destGeneralID isGold amount`() {
        val arg = NationRewardFamily.rewardArg(destGeneralId = 77, isGold = true, amount = 250)
        assertEquals(listOf("destGeneralID", "isGold", "amount"), arg.keys.toList())
        assertEquals(77, arg["destGeneralID"])
        assertEquals(true, arg["isGold"])
        assertEquals(250, arg["amount"])
    }

    @Test
    fun `confiscateArg has the same insertion order as rewardArg`() {
        // doNPC몰수 builds `['destGeneralID'=>id, 'isGold'=>bool, 'amount'=>takeAmount]` (PHP :1721/1755).
        val arg = NationRewardFamily.confiscateArg(destGeneralId = 88, isGold = false, amount = 400)
        assertEquals(listOf("destGeneralID", "isGold", "amount"), arg.keys.toList())
        assertEquals(88, arg["destGeneralID"])
        assertEquals(false, arg["isGold"])
        assertEquals(400, arg["amount"])
    }

    // --------------------------------------------------------------------------------------------------
    // (5) getOutcome is half-AWAY (H-HELPERS §3) — cited, the family reuses the GREEN getBill kernel.
    //     (The dedicationList filter `npc != 5` + the dead unused-append live in the bill-RATE helpers
    //     `:4216/4263` (L-RATES), NOT the reward family — reward weights are count-idx / takeAmount.)
    // --------------------------------------------------------------------------------------------------

    @Test
    fun `the candidate weight integer is the raw positional rank not a rounded outcome`() {
        // The reward family's draw weight is the integer positional rank (count-idx) / takeAmount; no rounding
        // is applied to it (the payAmount/takeAmount valueFit/round is the adapter's, done before weighting).
        // This test pins that the weight passed to choiceUsingWeightPair is exactly the rank (parity with PHP
        // `count($list) - $idx` which is a plain integer subtraction, never a half-away round of an outcome).
        assertEquals(1, NationRewardFamily.rankWeight(count = 1, idx = 0))
        assertEquals(2, NationRewardFamily.npcRewardWeight(warCnt = 1, civilCnt = 2, idx = 0))
    }
}
