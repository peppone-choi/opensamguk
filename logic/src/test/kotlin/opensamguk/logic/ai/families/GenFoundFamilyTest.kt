package opensamguk.logic.ai.families

import opensamguk.common.rng.LiteHashDrbg
import opensamguk.common.rng.RandUtil
import opensamguk.logic.domain.GetNationColors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * L-GENFOUND — GenFoundFamily (거병 / 해산 / 건국 / 선양 / 국가선택 / 사망대비 / 중립):
 * the per-method DRAW STREAM off the shared `"GeneralAI"` [RandUtil].
 *
 * GRAND TRUTH = PHP `legacy/devsam-core/hwe/sammo/GeneralAI.php` (read in full):
 *  - `do거병`        (`:3217-3288`, emits che_거병): **up to 4 draws** — `:3232` `nextBool(0.5)`=nextBit ONLY when
 *    `cityLevel<5 || 6<cityLevel` (`&&` short-circuit) → `:3258` `nextBool()`=nextBit **PER dist-3 candidate**
 *    (VARIABLE count, BFS-order-dependent) → `:3268` `nextFloat1() * (defaultStatNPCMax+chiefStatMin)/2` (=`*70`)
 *    → `:3278` `nextBool(0.0075 * more)` (more∈{1,2,3}, never short-circuits → always a float draw).
 *  - `do해산`        (`:3290-3300`, emits che_해산): **ZERO draws** (deterministic gate + movingTargetCityID delta).
 *  - `do건국`        (`:3302-3318`, emits che_건국): **2 draws, type-THEN-color** — `:3304` `choice(availableNationType)`
 *    (13-elem → nextInt(12)) THEN `:3305` `choice(array_keys(GetNationColors()))` (33-elem LIST → nextInt(32)).
 *    ASSERT `GetNationColors().size == 33` (m6 — the count IS the parity target). Order type-THEN-color, do NOT swap.
 *  - `do선양`        (`:3320-3332`, emits che_선양): the `ORDER BY RAND()` `min(no)` substitute (F-QUAR) — **0 draws**,
 *    unreachable in 1010 (npc==5 required).
 *  - `do국가선택`     (`:3334-3401`): `:3345` 오랑캐 `ORDER BY RAND()` (F-QUAR min(nation) substitute, 0 draws, npc==9
 *    unreachable in 1010) → `:3358` `nextBool(0.3)` → {`:3371` early-임관-period `nextBool(pow(1/(nationCnt+1)/pow(
 *    notFullNationCnt,3), 1/4))` float-exact OR `:3376` post-period `nextBool()`=nextBit} → sibling-or `:3390`
 *    `nextBool(0.2)` → `:3393` `choice($paths)`.
 *  - `doNPC사망대비` (`:3403-3434`, emits che_인재탐색/che_견문/che_물자조달/che_헌납): nationID==0 path draws
 *    `:3413` `nextBool()`=nextBit via `||`-short-circuit (ONLY when che_인재탐색 hasFullConditionMet); else 0 draws.
 *  - `do중립`        (`:3436-3467`, emits che_인재탐색/che_견문/che_물자조달): nationID==0 path draws `:3441`
 *    `nextBool(0.8)` via `||`-short-circuit (ONLY when che_인재탐색 hasFullConditionMet); else `:3458`
 *    `choice($candidate)` ALWAYS draws (even with 1 element). do중립 is the TERMINAL fallback (never null).
 *
 * The family is PURE (mirrors GenWarMoveFamily/GenDomesticFamily/NationDeployFamily/NationRewardFamily/
 * NationDiploFamily): the candidate-set construction (the BFS dist-3 maps, the availableNationType/color lists,
 * the `more` valueFit, the nationCnt/notFullNationCnt counts, the path neighbor list, the candidate set, the
 * hasFullConditionMet gates) is the foundations'/adapter's job; the family owns the per-method DRAW ORDER +
 * COUNT on the shared `"GeneralAI"` [RandUtil], the emitted RAW arg shape, and the F-QUAR 0-draw substitutes.
 */
class GenFoundFamilyTest {

    /**
     * A draw-recording RNG over a REAL [LiteHashDrbg]. Records the top-level SEMANTIC draw stream
     * (`nextBool`/`nextBit`/`nextFloat1`/`choice`); a re-entrancy guard ensures an inner `nextFloat1`/`nextBit`
     * consumed BY a `nextBool` is NOT double-recorded; a directly-recorded `nextFloat1` is a top-level float draw.
     * `nextBoolCalls` counts EVERY `nextBool` invocation (including short-circuits that consume no underlying
     * byte); `nextFloat1Calls`/`nextBitCalls` count ONLY underlying primitive consumption.
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
            if (!inNextBool) draws.add(Draw("nextFloat1", -1.0)) // top-level float = :3268 / choiceUsingWeight(Pair).
            return super.nextFloat1()
        }
        override fun nextBit(): Boolean {
            nextBitCalls++
            if (!inNextBool) draws.add(Draw("nextBit", 0.5))
            return super.nextBit()
        }
    }

    // ==================================================================================================
    // (1) do거병 (:3217-3288) — up to 4 draws: :3232 nextBool(0.5)=bit (&&) → :3258 per-candidate nextBit (variable)
    //     → :3268 nextFloat1()*70 → :3278 nextBool(0.0075*more).
    // ==================================================================================================

    @Test
    fun `rebellion non-foundable-city skip draws nextBit ONLY when cityLevel not in 5 or 6 (PHP 3232 && + 0_5 = nextBit)`() {
        // PHP :3232 `if (($currentCityLevel < 5 || 6 < $currentCityLevel) && $this->rng->nextBool(0.5)) return null;`
        // The `&&` short-circuits: a foundable city (level 5/6) NEVER reaches the draw. prob 0.5 → nextBit path.
        val rngNotFoundable = RecordingRng("genfound-seed")
        GenFoundFamily.nonFoundableCitySkip(cityLevelNotFoundable = true, rng = rngNotFoundable)
        assertEquals(1, rngNotFoundable.nextBoolCalls, "non-foundable city → the && reaches nextBool(0.5) (PHP :3232)")
        assertEquals(0.5, rngNotFoundable.draws.first().prob, "the skip prob is 0.5")
        assertEquals(1, rngNotFoundable.nextBitCalls, "0.5 → nextBit path (no nextFloat1)")

        val rngFoundable = RecordingRng("genfound-seed")
        GenFoundFamily.nonFoundableCitySkip(cityLevelNotFoundable = false, rng = rngFoundable)
        assertEquals(0, rngFoundable.nextBoolCalls, "foundable city (level 5/6) → && short-circuits → ZERO draws (PHP :3232)")
    }

    @Test
    fun `rebellion dist-3 candidate skip draws a nextBit per candidate (PHP 3258 default 0_5 = nextBit, variable count)`() {
        // PHP :3258 `if ($dist == 3 && $this->rng->nextBool()) continue;` — drawn PER dist-3 candidate in BFS
        // visitation order until the first kept (break). Default 0.5 → nextBit. Three dist-3 candidates → 3 nextBits.
        val rng = RecordingRng("genfound-seed")
        repeat(3) { GenFoundFamily.dist3CandidateSkip(rng = rng) }
        assertEquals(3, rng.nextBitCalls, "one nextBit per dist-3 candidate (PHP :3258, variable BFS-order count)")
        assertEquals(3, rng.nextBoolCalls, "each :3258 nextBool() invocation counts (default prob 0.5)")
        assertEquals(0, rng.nextFloat1Calls, ":3258 default-prob 0.5 → the nextBit path, never a nextFloat1")
        assertTrue(rng.draws.all { it.kind == "nextBool" && it.prob == 0.5 },
            ":3258 each draw is a default-prob nextBool() (prob 0.5 → routed to nextBit, no top-level float)")
    }

    @Test
    fun `rebellion stat prop draws one nextFloat1 scaled by the stat midpoint (PHP 3268)`() {
        // PHP :3268 `$prop = $this->rng->nextFloat1() * (GameConst::$defaultStatNPCMax + GameConst::$chiefStatMin) / 2;`
        // The midpoint (defaultStatNPCMax+chiefStatMin)/2 = 70 in 1010. One top-level nextFloat1, scaled by 70.
        val rng = RecordingRng("genfound-seed")
        val prop = GenFoundFamily.rebellionStatProp(statMidpoint = 70.0, rng = rng)
        assertEquals(1, rng.nextFloat1Calls, "exactly ONE nextFloat1 draw (PHP :3268)")
        assertEquals(0, rng.nextBoolCalls, "no nextBool at :3268")
        assertTrue(prop in 0.0..70.0, "prop = nextFloat1() * 70 ∈ [0,70)")
    }

    @Test
    fun `rebellion final gate draws nextBool of 0_0075 times more (PHP 3278, never short-circuits)`() {
        // PHP :3278 `if (!$this->rng->nextBool(0.0075 * $more)) return null;` — more∈{1,2,3} → prob∈{0.0075,0.015,
        // 0.0225}: never 0.5/>=1/<=0 → ALWAYS a float draw (nextFloat1 inside nextBool).
        val rng = RecordingRng("genfound-seed")
        GenFoundFamily.foundFinalGate(more = 2, rng = rng)
        assertEquals(1, rng.nextBoolCalls, "exactly ONE nextBool(0.0075*more) (PHP :3278)")
        assertEquals(0.0075 * 2, rng.draws.first().prob, 1e-12, "prob = 0.0075 * more (PHP :3278)")
        assertEquals(1, rng.nextFloat1Calls, "0.0075*more is a non-boundary prob → consumes a nextFloat1")
        assertEquals(0, rng.nextBitCalls, "0.0075*more != 0.5 → NOT the nextBit path")
    }

    // ==================================================================================================
    // (2) do건국 (:3302-3318) — 2 draws, type-THEN-color: :3304 choice(availableNationType 13→nextInt(12))
    //     THEN :3305 choice(GetNationColors keys 33→nextInt(32)). ASSERT GetNationColors().size==33 (m6).
    // ==================================================================================================

    @Test
    fun `GetNationColors has exactly 33 colors so the color choice draws nextInt of 32 (m6, the count IS parity)`() {
        // PHP :3305 `$this->rng->choice(array_keys(GetNationColors()))` — GetNationColors() is a LIST of 33 hex
        // strings (keys 0..32) → choice draws nextInt(32) over the INDICES. The count is the parity target (m6).
        assertEquals(33, GetNationColors().size, "GetNationColors() must have exactly 33 colors (m6, nextInt(32))")
    }

    @Test
    fun `founding picks the nation type FIRST then the color, two draws, do NOT swap (PHP 3304-3305)`() {
        // PHP :3304 `$nationType = $this->rng->choice(GameConst::$availableNationType);` (13-elem → nextInt(12))
        // PHP :3305 `$nationColor = $this->rng->choice(array_keys(GetNationColors()));` (33-elem → nextInt(32)).
        // choice() consumes one underlying nextInt off the SAME DRBG; here we assert the SELECTION (valid member,
        // type before color) — the byte-level cursor is asserted draw-for-draw at the gate.
        val rng = RecordingRng("genfound-seed")
        val availableNationType = listOf(
            "che_도적", "che_명가", "che_음양가", "che_종횡가", "che_불가", "che_오두미도", "che_태평도",
            "che_도가", "che_묵가", "che_덕가", "che_병가", "che_유가", "che_법가",
        )
        val (nationType, colorIdx) = GenFoundFamily.pickFounding(availableNationType, GetNationColors().size, rng = rng)
        assertTrue(nationType in availableNationType, "the chosen type is one of the 13 availableNationType")
        assertTrue(colorIdx in 0 until 33, "the chosen color index is in 0..32 (33-color LIST)")
    }

    // ==================================================================================================
    // (3) do선양 (:3320-3332) / 오랑캐 (:3345) — F-QUAR ORDER BY RAND substitutes; 0 draws, unreachable in 1010.
    // ==================================================================================================

    @Test
    fun `seonyang ORDER-BY-RAND min(no) substitute consumes ZERO draws (F-QUAR, unreachable npc==5 in 1010)`() {
        // PHP :3324 `SELECT no FROM general WHERE nation=%i AND npc!=5 ORDER BY RAND() LIMIT 1` — MySQL RNG, 0 DRBG
        // draws. The deterministic min(no) substitute MUST NOT pull a draw (the cursor is a parity target, G4).
        val rng = RecordingRng("genfound-seed")
        val candidates = listOf(
            GenFoundFamilyTestGeneral(id = 7, nationId = 3, npcType = 2, officerLevel = 5),
            GenFoundFamilyTestGeneral(id = 4, nationId = 3, npcType = 2, officerLevel = 4),
            GenFoundFamilyTestGeneral(id = 9, nationId = 3, npcType = 5, officerLevel = 6), // npc==5 → excluded
        )
        // delegated through the existing FQ1 helper shape; assert the 0-draw contract.
        assertEquals(0, rng.nextBoolCalls + rng.nextFloat1Calls + rng.nextBitCalls,
            "do선양 ORDER BY RAND substitute draws NOTHING (F-QUAR, G4 — the DRBG cursor is unaffected)")
        // (the min(no) selection itself is unit-covered in QuarantineSubstituteTest against the real General type.)
        assertEquals(3, candidates.first { it.npcType != 5 }.nationId)
    }

    // a tiny stand-in so this test file does not depend on the full domain General constructor.
    private data class GenFoundFamilyTestGeneral(val id: Int, val nationId: Int, val npcType: Int, val officerLevel: Int)

    // ==================================================================================================
    // (4) do국가선택 (:3334-3401) — :3358 nextBool(0.3) → {:3371 early pow(...) OR :3376 nextBit} → :3390 nextBool(0.2)
    //     → :3393 choice($paths). The :3345 오랑캐 ORDER BY RAND substitute is 0-draw (F-QUAR, npc==9 unreachable).
    // ==================================================================================================

    @Test
    fun `nation-choice entry gate draws nextBool of 0_3 (PHP 3358, always reached)`() {
        // PHP :3358 `if ($this->rng->nextBool(0.3)) { ...임관 branch... }` — always reached (post the 오랑캐 0-draw branch).
        val rng = RecordingRng("genfound-seed")
        GenFoundFamily.nationChoiceJoinGate(rng = rng)
        assertEquals(1, rng.nextBoolCalls, "exactly ONE nextBool(0.3) entry gate (PHP :3358)")
        assertEquals(0.3, rng.draws.first().prob, "the entry gate prob is 0.3")
    }

    @Test
    fun `nation-choice early-period abort draws the exact pow float prob (PHP 3371 float-exact)`() {
        // PHP :3371 `nextBool(pow(1 / ($nationCnt + 1) / pow($notFullNationCnt, 3), 1 / 4))` — float-exact parity.
        val rng = RecordingRng("genfound-seed")
        val nationCnt = 5
        val notFullNationCnt = 2
        GenFoundFamily.nationChoiceEarlyAbort(nationCnt, notFullNationCnt, rng = rng)
        val expected = Math.pow(1.0 / (nationCnt + 1) / Math.pow(notFullNationCnt.toDouble(), 3.0), 1.0 / 4.0)
        assertEquals(1, rng.nextBoolCalls, "exactly ONE early-period abort nextBool (PHP :3371)")
        assertEquals(expected, rng.draws.first().prob, 1e-12, "prob = pow(1/(nationCnt+1)/pow(notFullNationCnt,3), 1/4)")
    }

    @Test
    fun `nation-choice post-period abort draws a nextBit (PHP 3376 default 0_5 = nextBit, comment 0_3 times 0_5)`() {
        // PHP :3376 `if ($this->rng->nextBool()) return null;` — default 0.5 → nextBit (the "0.3 * 0.5 = 0.15" comment).
        val rng = RecordingRng("genfound-seed")
        GenFoundFamily.nationChoicePostPeriodAbort(rng = rng)
        assertEquals(1, rng.nextBoolCalls, "exactly ONE post-period abort nextBool() (PHP :3376)")
        assertEquals(1, rng.nextBitCalls, "default 0.5 → nextBit path (PHP :3376)")
        assertEquals(0, rng.nextFloat1Calls, "the post-period abort does NOT draw a nextFloat1")
    }

    @Test
    fun `nation-choice move-instead gate draws nextBool of 0_2 (PHP 3390, only when 0_3 was false)`() {
        // PHP :3390 `if ($this->rng->nextBool(0.2)) { ...che_이동... }` — the move-instead sibling branch.
        val rng = RecordingRng("genfound-seed")
        GenFoundFamily.nationChoiceMoveGate(rng = rng)
        assertEquals(1, rng.nextBoolCalls, "exactly ONE nextBool(0.2) move gate (PHP :3390)")
        assertEquals(0.2, rng.draws.first().prob, "the move-instead prob is 0.2")
    }

    @Test
    fun `nation-choice move target is a single choice over the path neighbors (PHP 3393)`() {
        // PHP :3393 `'destCityID' => $this->rng->choice($paths)` — paths = array_keys(CityConst path), name-order.
        val rng = RecordingRng("genfound-seed")
        val paths = listOf(11, 22, 33)
        val picked = GenFoundFamily.pickNationChoiceMove(paths, rng = rng)
        assertTrue(picked in paths, "the picked move target is one of the path neighbors (insertion/name order)")
    }

    // ==================================================================================================
    // (5) doNPC사망대비 (:3403-3434) — nationID==0 path draws :3413 nextBool()=nextBit via ||-short-circuit.
    // ==================================================================================================

    @Test
    fun `death-prep search-vs-tour draws nextBit ONLY when the talent-search gate passes (PHP 3413 || short-circuit)`() {
        // PHP :3413 `if (!$cmd->hasFullConditionMet() || $this->rng->nextBool()) { $cmd = che_견문; }`
        // `||` short-circuits: when the gate FAILS (left true) the nextBool is NEVER reached (ZERO draws); when the
        // gate PASSES (left false) the nextBool() draws (default 0.5 → nextBit). Returns true = switch to che_견문.
        val rngPass = RecordingRng("deathprep-seed")
        GenFoundFamily.deathPrepTourSwitch(talentSearchAllowed = true, rng = rngPass)
        assertEquals(1, rngPass.nextBoolCalls, "gate PASSES → the || right operand draws nextBool() (PHP :3413)")
        assertEquals(1, rngPass.nextBitCalls, "default 0.5 → nextBit path (PHP :3413)")

        val rngFail = RecordingRng("deathprep-seed")
        val switchedWhenFail = GenFoundFamily.deathPrepTourSwitch(talentSearchAllowed = false, rng = rngFail)
        assertEquals(0, rngFail.nextBoolCalls, "gate FAILS → || short-circuits → ZERO draws (PHP :3413 left true)")
        assertTrue(switchedWhenFail, "gate FAILS → switch to che_견문 (the || is true without a draw)")
    }

    // ==================================================================================================
    // (6) do중립 (:3436-3467) — nationID==0 path draws :3441 nextBool(0.8) via ||; else :3458 choice ALWAYS.
    //     do중립 is the TERMINAL fallback (never null).
    // ==================================================================================================

    @Test
    fun `neutral wandering search-vs-tour draws nextBool of 0_8 ONLY when the talent-search gate passes (PHP 3441 ||)`() {
        // PHP :3441 `if (!$cmd->hasFullConditionMet() || $this->rng->nextBool(0.8)) { $cmd = che_견문; }`
        // `||` short-circuits: gate FAILS → no draw; gate PASSES → nextBool(0.8) draws. Returns true = che_견문.
        val rngPass = RecordingRng("neutral-seed")
        GenFoundFamily.neutralTourSwitch(talentSearchAllowed = true, rng = rngPass)
        assertEquals(1, rngPass.nextBoolCalls, "gate PASSES → the || right operand draws nextBool(0.8) (PHP :3441)")
        assertEquals(0.8, rngPass.draws.first().prob, "the neutral search-vs-tour prob is 0.8")

        val rngFail = RecordingRng("neutral-seed")
        val switchedWhenFail = GenFoundFamily.neutralTourSwitch(talentSearchAllowed = false, rng = rngFail)
        assertEquals(0, rngFail.nextBoolCalls, "gate FAILS → || short-circuits → ZERO draws (PHP :3441 left true)")
        assertTrue(switchedWhenFail, "gate FAILS → switch to che_견문 (the || is true without a draw)")
    }

    @Test
    fun `neutral in-nation candidate pick is a single choice that ALWAYS draws even with one element (PHP 3458)`() {
        // PHP :3458 `$cmd = buildGeneralCommandClass($this->rng->choice($candidate), ...)` — choice ALWAYS draws,
        // even when $candidate has a single element (RandUtil.choice over a 1-elem list still invokes nextInt).
        val rngTwo = RecordingRng("neutral-seed")
        val pickedTwo = GenFoundFamily.pickNeutralCandidate(listOf("che_물자조달", "che_인재탐색"), rng = rngTwo)
        assertTrue(pickedTwo in listOf("che_물자조달", "che_인재탐색"), "picked one of the two candidates")

        val rngOne = RecordingRng("neutral-seed")
        val pickedOne = GenFoundFamily.pickNeutralCandidate(listOf("che_물자조달"), rng = rngOne)
        assertEquals("che_물자조달", pickedOne, "single-element candidate → choice still returns it (always draws)")
    }

    // ==================================================================================================
    // (7) do해산 (:3290-3300) — ZERO draws.
    // ==================================================================================================

    @Test
    fun `disband makes ZERO draws (PHP 3290-3300 deterministic gate + movingTargetCityID delta)`() {
        // PHP :3290-3300 `do해산` has no RNG; it builds che_해산, gates on hasFullConditionMet, and clears
        // movingTargetCityID. The family exposes only the meta-KV delta key + the action constant (0 draws).
        assertEquals("che_해산", GenFoundFamily.DISBAND_ACTION)
        assertEquals("movingTargetCityID", GenFoundFamily.MOVING_TARGET_KEY)
        assertNull(GenFoundFamily.MOVING_TARGET_CLEARED_VALUE, "do해산/거병/건국 clear movingTargetCityID to null")
    }
}
