package opensamguk.logic.ai

import opensamguk.common.constants.GameConst
import opensamguk.common.rng.LiteHashDrbg
import opensamguk.common.rng.RandUtil
import opensamguk.logic.domain.LastTurn
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * P5 Wire step — [GeneralAiFactory] assembles the PURE leaf-family `do<한글>` bodies into a live
 * [GeneralAI] (the general priority-loop dispatch + the 8 pre-loop ctor lambdas + the nation-side
 * dispatch/hooks). The factory is the single assembly seam (m12): a family NEVER edits [GeneralAI]
 * nor the dispatch loop; it supplies its do-bodies through [GeneralAiDoBodies] and the factory
 * registers them by ACTION-NAME in the policy/catalog order.
 *
 * These tests assert the wiring contract — NOT the draw-for-draw gate (that is G-GATE):
 *  1. a supplied general do-body fires through the priority loop end-to-end (a non-neutral dispatch).
 *  2. an UNSUPPLIED action name is a null no-op (the loop skips it and falls through).
 *  3. the priority loop honors the merged [AutorunGeneralPolicy.priority] ORDER (first non-null wins).
 *  4. the pre-loop ctor lambdas (do선양 … do중립) are wired from [GeneralAiDoBodies].
 *  5. the nation-side dispatch + the gated reserved-honor are wired ([GeneralAI.chooseNationTurn]).
 *  6. the factory is READ-ONLY over the bodies — it never invokes a body at construction.
 */
class GeneralAiFactoryTest {

    private fun rng() = RandUtil(LiteHashDrbg("GeneralAiFactoryTest"))

    private val rest = ChosenCommand("휴식", emptyMap())

    private fun genInput(
        npcType: Int = 2,
        nationId: Int = 1,
        officerLevel: Int = 1,
        rng: RandUtil = rng(),
    ) = GeneralAiInput(
        generalId = 7,
        npcType = npcType,
        nationId = nationId,
        officerLevel = officerLevel,
        injury = 0,
        npcmsg = null,
        capital = true,
        relYearMonth = 0,
        can선양 = false,
        can국가선택 = true,
        cureThreshold = 30,
        npcMessageProb = GameConst.npcMessageFreqByDay * 1.0 / (60 * 24),
        rng = rng,
    )

    // ── (1) a supplied general do-body fires through the priority loop end-to-end ──────────────────

    @Test fun `a supplied general do-body dispatches a non-neutral command through the priority loop`() {
        val fired = mutableListOf<String>()
        val bodies = GeneralAiDoBodies(
            generalDispatch = mapOf(
                "일반내정" to { _: LastTurn? -> fired += "일반내정"; ChosenCommand("che_내정", emptyMap()) },
            ),
        )
        val ai = GeneralAiFactory.build(
            generalPolicy = AutorunGeneralPolicy(npcType = 2, nationId = 1),
            bodies = bodies,
        )

        val chosen = ai.chooseGeneralTurn(rest, genInput(npcType = 2))

        assertEquals("che_내정", chosen.actionCode, "the wired do일반내정 body dispatched its command")
        assertEquals("do일반내정", chosen.reason, "the priority loop tags the reason 'do'+actionName")
        assertTrue("일반내정" in fired, "the wired body ran through the loop")
        assertFalse(chosen.actionCode == "che_중립", "the dispatch is NON-neutral (not the terminal fallback)")
    }

    // ── (2) an unsupplied action name is a null no-op (the loop skips it) ──────────────────────────

    @Test fun `an unsupplied action name is a null no-op and the loop falls through to do중립`() {
        // No general bodies wired at all → every priority entry is a null no-op → terminal fallback.
        val ai = GeneralAiFactory.build(
            generalPolicy = AutorunGeneralPolicy(npcType = 2, nationId = 1),
            bodies = GeneralAiDoBodies(),
        )

        val chosen = ai.chooseGeneralTurn(rest, genInput(npcType = 2))

        assertEquals("che_중립", chosen.actionCode, "no wired body → the do중립 terminal fallback fires")
        assertEquals("do중립", chosen.reason)
    }

    // ── (3) the priority loop honors the merged priority ORDER (first non-null wins) ───────────────

    @Test fun `the priority loop honors the merged policy order, first non-null wins`() {
        val fired = mutableListOf<String>()
        // A valid-name override (B1): only these two names, in THIS order. 출병 returns null → 징병 wins.
        val policy = AutorunGeneralPolicy(
            npcType = 2, nationId = 1,
            serverPolicy = mapOf("priority" to listOf("출병", "징병")),
        )
        val bodies = GeneralAiDoBodies(
            generalDispatch = mapOf(
                "출병" to { _: LastTurn? -> fired += "출병"; null },
                "징병" to { _: LastTurn? -> fired += "징병"; ChosenCommand("che_징병", emptyMap()) },
            ),
        )
        val ai = GeneralAiFactory.build(generalPolicy = policy, bodies = bodies)

        val chosen = ai.chooseGeneralTurn(rest, genInput(npcType = 2))

        assertEquals(listOf("출병", "징병"), fired, "the loop walked the override order, 출병 before 징병")
        assertEquals("che_징병", chosen.actionCode, "출병 returned null → 징병 won (first non-null)")
        assertEquals("do징병", chosen.reason)
    }

    // ── (4) the pre-loop ctor lambdas are wired from the bodies bundle ─────────────────────────────

    @Test fun `the pre-loop do거병 ctor lambda is wired from the bodies bundle`() {
        var ran = false
        // npcType 2 && nation 0 → the :3778 do거병 pre-loop branch fires BEFORE the priority loop.
        val bodies = GeneralAiDoBodies(
            do거병 = { ran = true; ChosenCommand("che_거병", emptyMap()) },
        )
        val ai = GeneralAiFactory.build(
            generalPolicy = AutorunGeneralPolicy(npcType = 2, nationId = 0),
            bodies = bodies,
        )

        val chosen = ai.chooseGeneralTurn(rest, genInput(npcType = 2, nationId = 0))

        assertTrue(ran, "the pre-loop do거병 lambda was wired + invoked")
        assertEquals("che_거병", chosen.actionCode)
        assertEquals("do거병", chosen.reason)
    }

    // ── (5) the nation-side dispatch + gated reserved-honor are wired ──────────────────────────────

    @Test fun `the nation dispatch wires a do-body that fires through chooseNationTurn`() {
        val fired = mutableListOf<String>()
        // A valid-name nation override: only 선전포고. npcType>=2 bypasses guard-C.
        val nationPolicy = AutorunNationPolicy(
            npcType = 2, tech = 0, develcost = 20,
            nationPolicy = mapOf("priority" to listOf("선전포고")),
        )
        val bodies = GeneralAiDoBodies(
            nationDispatch = mapOf(
                "선전포고" to { _: LastTurn? -> fired += "선전포고"; ChosenCommand("che_선전포고", emptyMap()) },
            ),
        )
        val ai = GeneralAiFactory.build(
            generalPolicy = AutorunGeneralPolicy(npcType = 2, nationId = 1),
            nationPolicy = nationPolicy,
            bodies = bodies,
        )

        val chosen = ai.chooseNationTurn(
            reservedCommand = rest,
            lastTurn = null,
            input = NationAiInput(npcType = 2, officerLevel = 12, month = 1, rng = rng()),
        )

        assertTrue("선전포고" in fired, "the wired nation do선전포고 body ran through the nation loop")
        assertEquals("che_선전포고", chosen.actionCode)
        assertEquals("do선전포고", chosen.reason)
    }

    @Test fun `the gated reserved-honor hook is wired for the nation pass`() {
        // A non-휴식 reserved command + a PASS gate → the nation pass honors it (reason 'reserved').
        val bodies = GeneralAiDoBodies(hasFullConditionMet = { true })
        val ai = GeneralAiFactory.build(
            generalPolicy = AutorunGeneralPolicy(npcType = 2, nationId = 1),
            nationPolicy = AutorunNationPolicy(npcType = 2, tech = 0, develcost = 20),
            bodies = bodies,
        )

        val chosen = ai.chooseNationTurn(
            reservedCommand = ChosenCommand("che_발령", linkedMapOf("x" to 1)),
            lastTurn = null,
            input = NationAiInput(npcType = 2, officerLevel = 5, month = 1, rng = rng()),
        )

        assertEquals("che_발령", chosen.actionCode, "the gated reserved command was honored on PASS")
        assertEquals("reserved", chosen.reason)
    }
}
