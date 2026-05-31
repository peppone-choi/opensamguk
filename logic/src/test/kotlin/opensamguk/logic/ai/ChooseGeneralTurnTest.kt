package opensamguk.logic.ai

import opensamguk.common.constants.GameConst
import opensamguk.common.rng.RandUtil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Task FD1 — [GeneralAI.chooseGeneralTurn] (the general decision spine).
 *
 * Port target = PHP `legacy/devsam-core/hwe/sammo/GeneralAI.php:3709-3848` (GRAND TRUTH, read in full).
 *
 * The spine VERBATIM (PHP source-line order):
 *  - `:3715` `updateInstance()` prologue (calcGenType FIRST draw lives in F-INSTANCE — here a supplied hook).
 *  - `:3719` the npcmsg prologue draw — `$general->getVar('npcmsg') && rng->nextBool(npcMessageFreqByDay*term/(60*24))`
 *    — a `&&` short-circuit, side-effect-only (no return): ZERO draws when npcmsg is falsy.
 *  - `:3741` npcType>=2 sets `defence_train=80` (a meta-KV side-effect, no draw, no return).
 *  - `:3745` `do선양` if officer_level==12 && can선양 (reason 'do선양').
 *  - `:3753` npcType==5: nation 0 → `killturn=1` + reservedCommand (reason '사망'); else `do집합` (reason 'do집합').
 *  - `:3767` the RESERVED-HONOR (decision #4): a non-휴식 reserved command is honored WITHOUT a gate and
 *    WITHOUT a log (reason 'do예약턴'). The general path does NOT re-validate — do NOT add a check.
 *  - `:3772` injury > cureThreshold → `che_요양` (reason 'do요양', gate-exempt).
 *  - `:3778` (npcType 2|3) && nation 0 → `do거병` (reason 'do거병').
 *  - `:3786` nation 0 && can국가선택 → `do국가선택` else `do중립` (reasons 'do국가선택'/'do중립').
 *  - `:3797` npcType<2 && nation 0 && !can국가선택 → reservedCommand (reason '재야유저').
 *  - `:3802` npcType>=2 && officer_level==12 && !capital → do건국/do방랑군이동/do해산 (relYearMonth>1 gates 건국/해산).
 *  - `:3829` the PRIORITY LOOP: foreach generalPolicy.priority → can-flag gate → do{X}() first-non-null wins
 *    (reason 'do'+actionName). The merged priority ORDER IS the dispatch/log/draw order.
 *  - `:3845` `do중립` TERMINAL fallback (never null).
 */
class ChooseGeneralTurnTest {

    // A reserved 휴식 command (the "no reservation" baseline — the spine treats it as not-honored).
    private val rest = ChosenCommand("휴식", emptyMap(), reason = "")
    private val reservedNonRest = ChosenCommand("che_내정", linkedMapOf("x" to 1), reason = "")

    private fun rng() = RandUtil(opensamguk.common.rng.LiteHashDrbg("ChooseGeneralTurnTest"))

    /** A spine input with all the read-only scalars the branches consult. */
    private fun input(
        generalId: Int = 0,
        npcType: Int = 0,
        nationId: Int = 1,
        officerLevel: Int = 1,
        injury: Int = 0,
        npcmsg: String? = null,
        capital: Boolean = true,
        relYearMonth: Int = 0,
        can선양: Boolean = false,
        can국가선택: Boolean = true,
        cureThreshold: Int = 30,
        npcMessageProb: Double = GameConst.npcMessageFreqByDay * 2.0 / (60 * 24),
        rng: RandUtil = rng(),
    ) = GeneralAiInput(
        generalId = generalId,
        npcType = npcType,
        nationId = nationId,
        officerLevel = officerLevel,
        injury = injury,
        npcmsg = npcmsg,
        capital = capital,
        relYearMonth = relYearMonth,
        can선양 = can선양,
        can국가선택 = can국가선택,
        cureThreshold = cureThreshold,
        npcMessageProb = npcMessageProb,
        rng = rng,
    )

    // ─────────────────────────────────────────────────────────────────────────────────────────────
    // (1) the dispatch order EQUALS generalPolicy.priority — a crafted valid-name override (B1) fires
    //     do<한글> in the OVERRIDE order; first-non-null short-circuits later draws.
    // ─────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun `priority loop fires do methods in the merged priority order, first non-null wins`() {
        val fired = mutableListOf<String>()
        // A custom valid-name override (B1) — only these two names, in THIS order.
        val policy = AutorunGeneralPolicy(npcType = 2, nationId = 1, serverPolicy = mapOf("priority" to listOf("출병", "징병")))
        assertEquals(listOf("출병", "징병"), policy.priority)

        val dispatch = linkedMapOf<String, (opensamguk.logic.domain.LastTurn?) -> ChosenCommand?>(
            "징병" to { fired += "징병"; ChosenCommand("che_징병", emptyMap(), "") },
            "출병" to { fired += "출병"; null }, // returns null → falls through to 징병
        )
        val ai = GeneralAI(generalPolicy = policy, dispatch = dispatch)
        val chosen = ai.chooseGeneralTurn(reservedNonRest.copy(actionCode = "휴식"), input(npcType = 2))

        // do출병 fired first (priority[0]), returned null → do징병 fired next and won.
        assertEquals(listOf("출병", "징병"), fired, "dispatch order = merged priority order")
        assertEquals("do징병", chosen.reason)
        assertEquals("che_징병", chosen.actionCode)
    }

    @Test fun `first non-null short-circuits — a winning earlier do prevents later draws`() {
        val fired = mutableListOf<String>()
        val policy = AutorunGeneralPolicy(npcType = 2, nationId = 1, serverPolicy = mapOf("priority" to listOf("출병", "징병")))
        val dispatch = linkedMapOf<String, (opensamguk.logic.domain.LastTurn?) -> ChosenCommand?>(
            "출병" to { fired += "출병"; ChosenCommand("che_출병", emptyMap(), "") },
            "징병" to { fired += "징병"; ChosenCommand("che_징병", emptyMap(), "") },
        )
        val ai = GeneralAI(generalPolicy = policy, dispatch = dispatch)
        val chosen = ai.chooseGeneralTurn(reservedNonRest.copy(actionCode = "휴식"), input(npcType = 2))
        assertEquals(listOf("출병"), fired, "first non-null short-circuits → 징병 never fires")
        assertEquals("do출병", chosen.reason)
    }

    @Test fun `a priority item whose can-flag is false is skipped (no do call)`() {
        val fired = mutableListOf<String>()
        val policy = AutorunGeneralPolicy(npcType = 2, nationId = 1)
        policy.can출병 = false // can출병 = false → skip, no do call (guard-B :3834)
        val dispatch = linkedMapOf<String, (opensamguk.logic.domain.LastTurn?) -> ChosenCommand?>(
            "출병" to { fired += "출병"; ChosenCommand("che_출병", emptyMap(), "") },
        )
        val ai = GeneralAI(generalPolicy = policy, dispatch = dispatch)
        ai.chooseGeneralTurn(reservedNonRest.copy(actionCode = "휴식"), input(npcType = 2))
        assertFalse(fired.contains("출병"), "a can-flag-false action is skipped without a do call")
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────────
    // (2) the npcmsg prologue draw (:3719) fires ONLY when npcmsg is truthy (the && short-circuit).
    // ─────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun `the npcmsg prologue draw fires only when npcmsg is truthy`() {
        // npcmsg falsy → ZERO draws here; the recording rng's cursor stays at 0.
        val recFalsy = CountingRandUtil()
        val aiFalsy = GeneralAI(
            generalPolicy = AutorunGeneralPolicy(npcType = 2, nationId = 1),
            dispatch = linkedMapOf(),
            do중립 = { ChosenCommand("che_중립", emptyMap(), "") },
        )
        aiFalsy.chooseGeneralTurn(rest, input(npcType = 2, npcmsg = null, rng = recFalsy))
        assertEquals(0, recFalsy.boolDraws, "npcmsg falsy → the && short-circuit suppresses the :3719 draw")

        // npcmsg truthy → exactly ONE nextBool draw at :3719.
        val recTruthy = CountingRandUtil()
        val aiTruthy = GeneralAI(
            generalPolicy = AutorunGeneralPolicy(npcType = 2, nationId = 1),
            dispatch = linkedMapOf(),
            do중립 = { ChosenCommand("che_중립", emptyMap(), "") },
        )
        aiTruthy.chooseGeneralTurn(rest, input(npcType = 2, npcmsg = "안녕", rng = recTruthy))
        assertEquals(1, recTruthy.boolDraws, "npcmsg truthy → exactly one :3719 nextBool draw (side-effect, no return)")
    }

    @Test fun `the npcmsg prologue draw is side-effect-only — it never short-circuits the spine`() {
        // Even with npcmsg truthy, control flow continues to the do중립 fallback (no early return at :3719).
        val ai = GeneralAI(
            generalPolicy = AutorunGeneralPolicy(npcType = 2, nationId = 1),
            dispatch = linkedMapOf(),
            do중립 = { ChosenCommand("che_중립", emptyMap(), "do중립-was-built") },
        )
        val chosen = ai.chooseGeneralTurn(rest, input(npcType = 2, npcmsg = "안녕"))
        assertEquals("do중립", chosen.reason, "the npcmsg draw does NOT return — the spine continues")
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────────
    // (3) the RESERVED-HONOR general path (decision #4): a non-휴식 reserved command is honored with
    //     NO gate and NO log (reason 'do예약턴'); a 휴식 reserved command is NOT honored.
    // ─────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun `a non-rest reserved command is honored with no gate and no log`() {
        val gateCalls = mutableListOf<String>()
        val logCalls = mutableListOf<String>()
        val ai = GeneralAI(
            generalPolicy = AutorunGeneralPolicy(npcType = 2, nationId = 1),
            dispatch = linkedMapOf(),
            candidateAllowed = { code, _ -> gateCalls += code; false },
            logFailString = { logCalls += it },
            do중립 = { ChosenCommand("che_중립", emptyMap(), "") },
        )
        val chosen = ai.chooseGeneralTurn(reservedNonRest, input(npcType = 2))
        assertSame(reservedNonRest.actionCode, chosen.actionCode, "the reserved command itself is returned")
        assertEquals("do예약턴", chosen.reason)
        assertTrue(gateCalls.isEmpty(), "the general reserved-honor does NOT gate (decision #4)")
        assertTrue(logCalls.isEmpty(), "the general reserved-honor does NOT log (decision #4)")
    }

    @Test fun `a rest reserved command is NOT honored — control falls through`() {
        val ai = GeneralAI(
            generalPolicy = AutorunGeneralPolicy(npcType = 2, nationId = 1),
            dispatch = linkedMapOf(),
            do중립 = { ChosenCommand("che_중립", emptyMap(), "") },
        )
        val chosen = ai.chooseGeneralTurn(rest, input(npcType = 2))
        assertEquals("do중립", chosen.reason, "a 휴식 reservation is not honored → fall through to the fallback")
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────────
    // (4) the gate-exempt reserved-returns BYPASS candidateAllowed.
    // ─────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun `npc 5 with nation 0 returns the reserved command with reason 사망 and sets killturn`() {
        val kv = mutableListOf<Triple<Int, String, Any?>>()
        // PHP order: :3741 defence_train=80 (npcType>=2) BEFORE :3755 killturn=1 (the npc-5 nation-0 branch).
        val expectedKv: List<Triple<Int, String, Any?>> =
            listOf(Triple(7, "defence_train", 80), Triple(7, "killturn", 1))
        val ai = GeneralAI(
            generalPolicy = AutorunGeneralPolicy(npcType = 5, nationId = 0),
            dispatch = linkedMapOf(),
            candidateAllowed = { _, _ -> error("gate must not be consulted for the 사망 reserved-return") },
            recordGeneralKv = { id, k, v -> kv += Triple(id, k, v) },
        )
        val chosen = ai.chooseGeneralTurn(reservedNonRest, input(generalId = 7, npcType = 5, nationId = 0))
        assertEquals("사망", chosen.reason)
        assertSame(reservedNonRest.actionCode, chosen.actionCode)
        assertEquals(expectedKv, kv, "defence_train=80 (:3741) then killturn=1 (:3755) — meta-KV deltas in PHP order")
    }

    @Test fun `npc 5 with a nation dispatches do집합 gate-exempt`() {
        val ai = GeneralAI(
            generalPolicy = AutorunGeneralPolicy(npcType = 5, nationId = 1),
            dispatch = linkedMapOf(),
            candidateAllowed = { _, _ -> error("do집합 is gate-exempt") },
            do집합 = { ChosenCommand("che_집합", emptyMap(), "") },
        )
        val chosen = ai.chooseGeneralTurn(reservedNonRest, input(npcType = 5, nationId = 1))
        assertEquals("do집합", chosen.reason)
        assertEquals("che_집합", chosen.actionCode)
    }

    @Test fun `do요양 is built directly when injury exceeds cureThreshold (gate-exempt)`() {
        val ai = GeneralAI(
            generalPolicy = AutorunGeneralPolicy(npcType = 2, nationId = 1),
            dispatch = linkedMapOf(),
            candidateAllowed = { _, _ -> error("do요양 is gate-exempt") },
            do중립 = { ChosenCommand("che_중립", emptyMap(), "") },
        )
        // injury 50 > cureThreshold 30, with a 휴식 reservation so the reserved-honor does not fire first.
        val chosen = ai.chooseGeneralTurn(rest, input(npcType = 2, injury = 50, cureThreshold = 30))
        assertEquals("do요양", chosen.reason)
        assertEquals("che_요양", chosen.actionCode)
    }

    @Test fun `injury at or below cureThreshold does not trigger do요양`() {
        val ai = GeneralAI(
            generalPolicy = AutorunGeneralPolicy(npcType = 2, nationId = 1),
            dispatch = linkedMapOf(),
            do중립 = { ChosenCommand("che_중립", emptyMap(), "") },
        )
        val chosen = ai.chooseGeneralTurn(rest, input(npcType = 2, injury = 30, cureThreshold = 30))
        assertEquals("do중립", chosen.reason, "injury == cureThreshold is NOT > → no 요양")
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────────
    // (5) the pre-loop branches in PHP order.
    // ─────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun `do선양 fires for officer_level 12 with can선양, before npc-5 and reserved-honor`() {
        val ai = GeneralAI(
            generalPolicy = AutorunGeneralPolicy(npcType = 5, nationId = 1).also { it.can선양 = true },
            dispatch = linkedMapOf(),
            do선양 = { ChosenCommand("che_선양", emptyMap(), "") },
            do집합 = { error("do선양 wins before the npc-5 집합 branch") },
        )
        val chosen = ai.chooseGeneralTurn(reservedNonRest, input(npcType = 5, officerLevel = 12, can선양 = true))
        assertEquals("do선양", chosen.reason)
    }

    @Test fun `do거병 fires for npc 2 or 3 with nation 0 when no earlier branch fired`() {
        val ai = GeneralAI(
            generalPolicy = AutorunGeneralPolicy(npcType = 2, nationId = 0),
            dispatch = linkedMapOf(),
            do거병 = { ChosenCommand("che_거병", emptyMap(), "") },
            do중립 = { ChosenCommand("che_중립", emptyMap(), "") },
        )
        val chosen = ai.chooseGeneralTurn(rest, input(npcType = 2, nationId = 0))
        assertEquals("do거병", chosen.reason)
    }

    @Test fun `nation 0 with can국가선택 dispatches do국가선택 then do중립`() {
        // do국가선택 returns null → do중립 (the :3792 sibling within the same branch).
        // npcType<2 user-path resets can국가선택=false at construction; re-enable it for this branch.
        val ai = GeneralAI(
            generalPolicy = AutorunGeneralPolicy(npcType = 0, nationId = 0).also { it.can국가선택 = true },
            dispatch = linkedMapOf(),
            do국가선택 = { null },
            do중립 = { ChosenCommand("che_중립", emptyMap(), "") },
        )
        val chosen = ai.chooseGeneralTurn(rest, input(npcType = 0, nationId = 0, can국가선택 = true))
        assertEquals("do중립", chosen.reason, "do국가선택 null → do중립 within the nation-0 branch")
    }

    @Test fun `npc under 2 with nation 0 and no can국가선택 returns the reserved command as 재야유저`() {
        // 재야유저 (:3797) is reachable ONLY with a 휴식 reservation — a non-휴식 reservation is honored
        // first at :3767 ('do예약턴'). The branch returns the (휴식) reserved command with reason '재야유저'.
        val ai = GeneralAI(
            generalPolicy = AutorunGeneralPolicy(npcType = 0, nationId = 0).also { it.can국가선택 = false },
            dispatch = linkedMapOf(),
            do중립 = { error("재야유저 returns the reserved command before the fallback") },
        )
        val chosen = ai.chooseGeneralTurn(rest, input(npcType = 0, nationId = 0, can국가선택 = false))
        assertEquals("재야유저", chosen.reason)
        assertSame(rest.actionCode, chosen.actionCode)
    }

    @Test fun `wandering ruler with relYearMonth over 1 dispatches do건국 then 방랑군이동 then 해산`() {
        val fired = mutableListOf<String>()
        val ai = GeneralAI(
            generalPolicy = AutorunGeneralPolicy(npcType = 2, nationId = 1),
            dispatch = linkedMapOf(),
            do건국 = { fired += "do건국"; null },
            do방랑군이동 = { fired += "do방랑군이동"; null },
            do해산 = { fired += "do해산"; ChosenCommand("che_해산", emptyMap(), "") },
            do중립 = { ChosenCommand("che_중립", emptyMap(), "") },
        )
        val chosen = ai.chooseGeneralTurn(rest, input(npcType = 2, officerLevel = 12, capital = false, relYearMonth = 2))
        assertEquals(listOf("do건국", "do방랑군이동", "do해산"), fired)
        assertEquals("do해산", chosen.reason)
    }

    @Test fun `wandering ruler with relYearMonth 1 skips 건국 and 해산, only 방랑군이동`() {
        val fired = mutableListOf<String>()
        val ai = GeneralAI(
            generalPolicy = AutorunGeneralPolicy(npcType = 2, nationId = 1),
            dispatch = linkedMapOf(),
            do건국 = { fired += "do건국"; null },
            do방랑군이동 = { fired += "do방랑군이동"; null },
            do해산 = { fired += "do해산"; null },
            do중립 = { ChosenCommand("che_중립", emptyMap(), "") },
        )
        ai.chooseGeneralTurn(rest, input(npcType = 2, officerLevel = 12, capital = false, relYearMonth = 1))
        assertEquals(listOf("do방랑군이동"), fired, "relYearMonth==1 gates out 건국/해산 (relYearMonth>1 required)")
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────────
    // (6) do중립 is the guaranteed terminal fallback (never null).
    // ─────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun `do중립 is the terminal fallback when nothing else fires`() {
        val ai = GeneralAI(
            generalPolicy = AutorunGeneralPolicy(npcType = 2, nationId = 1),
            dispatch = linkedMapOf(),
            do중립 = { ChosenCommand("che_중립", emptyMap(), "fallback") },
        )
        val chosen = ai.chooseGeneralTurn(rest, input(npcType = 2))
        assertEquals("do중립", chosen.reason)
        assertEquals("che_중립", chosen.actionCode)
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────────
    // (7) updateInstance is the prologue (called before any branch).
    // ─────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun `updateInstance runs as the prologue before any branch`() {
        var updated = false
        val ai = GeneralAI(
            generalPolicy = AutorunGeneralPolicy(npcType = 2, nationId = 1),
            dispatch = linkedMapOf(),
            updateInstance = { updated = true },
            do중립 = {
                assertTrue(updated, "updateInstance ran before do중립")
                ChosenCommand("che_중립", emptyMap(), "")
            },
        )
        ai.chooseGeneralTurn(rest, input(npcType = 2))
        assertTrue(updated)
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────────
    // (8) ChosenCommand shape.
    // ─────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun `ChosenCommand carries actionCode, args, reason`() {
        val c = ChosenCommand("che_내정", linkedMapOf("destCityID" to 5), "do일반내정")
        assertEquals("che_내정", c.actionCode)
        assertEquals(linkedMapOf<String, Any?>("destCityID" to 5), c.args)
        assertEquals("do일반내정", c.reason)
        assertEquals("", ChosenCommand("x", emptyMap()).reason, "reason defaults to empty")
    }

    /** A nextBool-counting RandUtil to assert the :3719 && short-circuit draw count. */
    private class CountingRandUtil : RandUtil(opensamguk.common.rng.LiteHashDrbg("counting")) {
        var boolDraws = 0
        override fun nextBool(prob: Double): Boolean {
            boolDraws += 1
            return super.nextBool(prob)
        }
    }
}
