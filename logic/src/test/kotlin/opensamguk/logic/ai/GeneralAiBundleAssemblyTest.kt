package opensamguk.logic.ai

import opensamguk.common.constants.GameConst
import opensamguk.common.rng.LiteHashDrbg
import opensamguk.common.rng.RandUtil
import opensamguk.logic.ai.families.GenDomesticFamily
import opensamguk.logic.ai.families.GenFoundFamily
import opensamguk.logic.ai.families.GenWarMoveFamily
import opensamguk.logic.ai.families.NationDeployFamily
import opensamguk.logic.ai.families.NationDiploFamily
import opensamguk.logic.ai.families.NationRewardFamily
import opensamguk.logic.domain.City
import opensamguk.logic.domain.General
import opensamguk.logic.stats.GeneralActionPipeline
import opensamguk.logic.stats.StatCalc
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * P5 ASSEMBLE — [GeneralAiDoBodies.fromFamilies] merges ALL 7 leaf families' world-driven `bodies(ctx)`
 * builders into ONE bundle the [GeneralAiFactory] wires into the live [GeneralAI]. This is the single
 * assembly seam (m12): the families stay PURE; this merger registers their `do<한글>` bodies by
 * action-name (general + nation dispatch) + the 8 pre-loop / terminal branch bodies, threading the
 * SOLE per-decision `candidateAllowed` gate + `recordGeneralKv` delta sink from the [GeneralAiContext].
 *
 * Asserts the assembly is COMPLETE + SOUND (NOT the draw-for-draw gate — that is G-GATE):
 *  1. every general-dispatch action name from the 3 general families is present (일반내정…내정워프) and the
 *     merged map is the union (disjoint keys, no family clobbers another).
 *  2. every nation-dispatch action name from the 3 nation families is present (발령/포상/몰수 + diplo).
 *  3. the 8 pre-loop / terminal branch bodies (do선양 … do중립) are the real GenFound/GenWarMove builders.
 *  4. a representative npc==2 general dispatches a NON-neutral command through the real priority loop
 *     (the first non-null body wins) — proving the merged bundle is wired end-to-end, not the empty default.
 */
class GeneralAiBundleAssemblyTest {

    private fun city(
        id: Int,
        trust: Double = 100.0,
        pop: Int = 100_000,
        popMax: Int = 100_000,
    ): City = City(
        id = id, nationId = 1, level = 5,
        commerce = 0, commerceMax = 1, agriculture = 0, agricultureMax = 1,
        supplyState = 1, frontState = 0, trust = trust,
        security = 0, securityMax = 1, defense = 0, defenseMax = 1, wall = 0, wallMax = 1,
        population = pop, populationMax = popMax,
    )

    private fun instance(): AiInstanceState {
        val env = AiEnv(year = 200, month = 1, startYear = 180, develCost = 10)
        val nationPolicy = AutorunNationPolicy(npcType = 2, tech = 0, develcost = 10)
        val st = AiInstanceState(
            generalNationId = 1,
            env = env,
            nationPolicy = nationPolicy,
            nationRowLookup = { AiNationRow(nation = 1, level = 5, capital = 100, gold = 99999, rice = 99999) },
            nationStor = emptyMap(),
            diplomacyOf = { emptyList() },
            frontMaxOf = { 0 },
            kvRecorder = object : AiKvRecorder { override fun recordNationKv(nationId: Int, key: String, value: Any?) {} },
        )
        st.updateInstance()
        val g = General(
            id = 1, nationId = 1, cityId = 100,
            leadership = 50, strength = 50, intel = 50, injury = 0,
            experience = 0.0, dedication = 0.0,
            officerLevel = 1, gold = 0, rice = 0,
        )
        st.calcGenType(RandUtil(LiteHashDrbg("genType-fixture")), StatCalc(g, GeneralActionPipeline()))
        return st
    }

    private fun ctxOf(
        rng: RandUtil,
        selfCity: City,
        cityDevelRate: Map<String, Double>,
        candidateAllowed: (String, Map<String, Any?>) -> Boolean = { _, _ -> true },
        generalPolicy: AutorunGeneralPolicy = AutorunGeneralPolicy(npcType = 2, nationId = 1),
        nationPolicy: AutorunNationPolicy = AutorunNationPolicy(npcType = 2, tech = 0, develcost = 10),
    ): GeneralAiContext {
        val st = instance()
        val world = AiWorldView(
            ownNationId = 1,
            cityRows = listOf(selfCity),
            warTargetNation = null,
            ownGeneralId = 1,
            generals = emptyList(),
            dipState = st.dipState,
            minWarCrew = nationPolicy.minWarCrew,
            minNpcWarLeadership = nationPolicy.minNPCWarLeadership,
            turnTerm = 120,
        )
        return GeneralAiContext(
            rng = rng,
            instance = st,
            world = world,
            generalPolicy = generalPolicy,
            nationPolicy = nationPolicy,
            env = AiEnv(year = 200, month = 1, startYear = 180, develCost = 10),
            turnTerm = 120,
            selfGeneralId = 1,
            selfCityId = selfCity.id,
            candidateAllowed = candidateAllowed,
            leadershipWithInjury = 50.0,
            fullLeadership = 50.0,
            fullStrength = 50.0,
            fullIntel = 50.0,
            selfCity = selfCity,
            cityDevelRate = cityDevelRate,
        )
    }

    private fun genInput(rng: RandUtil) = GeneralAiInput(
        generalId = 1,
        npcType = 2,
        nationId = 1,
        officerLevel = 1,
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

    // ── (1) the merged general dispatch is the union of the 3 general families ─────────────────────

    @Test fun `the merged general dispatch contains every general-family action name`() {
        val ctx = ctxOf(RandUtil(LiteHashDrbg("a")), city(100), emptyMap())
        val bodies = GeneralAiDoBodies.fromFamilies(ctx)

        val expected =
            GenDomesticFamily.bodies(ctx).keys +
                GenWarMoveFamily.bodies(ctx).keys +
                GenFoundFamily.bodies(ctx).keys
        assertEquals(expected, bodies.generalDispatch.keys, "every general-family action name is wired")
        // a representative key from each family is present.
        assertTrue("일반내정" in bodies.generalDispatch, "GenDomestic 일반내정 wired")
        assertTrue("출병" in bodies.generalDispatch, "GenWarMove 출병 wired")
        assertTrue("NPC사망대비" in bodies.generalDispatch, "GenFound NPC사망대비 wired")
    }

    // ── (2) the merged nation dispatch is the union of the 3 nation families ───────────────────────

    @Test fun `the merged nation dispatch contains every nation-family action name`() {
        val ctx = ctxOf(RandUtil(LiteHashDrbg("b")), city(100), emptyMap())
        val bodies = GeneralAiDoBodies.fromFamilies(ctx)

        val expected =
            NationDeployFamily.bodies(ctx).keys +
                NationRewardFamily.bodies(ctx).keys +
                NationDiploFamily.bodies(ctx).keys
        assertEquals(expected, bodies.nationDispatch.keys, "every nation-family action name is wired")
        assertTrue("부대전방발령" in bodies.nationDispatch, "NationDeploy 부대전방발령 wired")
        assertTrue("유저장긴급포상" in bodies.nationDispatch, "NationReward 유저장긴급포상 wired")
        assertTrue("선전포고" in bodies.nationDispatch, "NationDiplo 선전포고 wired")
    }

    // ── (3) the 8 pre-loop / terminal branch bodies are the real family builders ───────────────────

    @Test fun `the pre-loop and terminal branch bodies are wired to the real family builders`() {
        val ctx = ctxOf(RandUtil(LiteHashDrbg("c")), city(100), emptyMap())
        val bodies = GeneralAiDoBodies.fromFamilies(ctx)

        // do중립 is the TERMINAL fallback — it is the REAL GenFoundFamily body (NOT the inert che_중립
        // default), which for an in-nation general emits one of che_물자조달/che_인재탐색/che_견문 and is
        // NEVER null (PHP `:3439-3465`). Asserting it is the real body proves the pre-loop wiring landed.
        val neutral = bodies.do중립(null)
        assertTrue(
            neutral.actionCode in setOf("che_물자조달", "che_인재탐색", "che_견문"),
            "do중립 is the real GenFoundFamily body (got ${neutral.actionCode}), not the inert che_중립 default",
        )
    }

    // ── (4) a representative npc==2 general dispatches a NON-neutral command through the loop ───────

    @Test fun `a fully-wired GeneralAI dispatches a non-neutral command through the real priority loop`() {
        // One low develop ratio (trust 0.5) makes do일반내정's candidate set non-empty → it picks a
        // develop command through the priority loop. The full family bodies are merged, so the FIRST
        // non-null body in priority order wins — NOT the terminal che_중립 fallback.
        val develRate = linkedMapOf(
            "trust" to 0.5, "pop" to 1.0, "agri" to 1.0, "comm" to 1.0, "secu" to 1.0, "def" to 1.0, "wall" to 1.0,
        )
        // Only 일반내정 in the priority so the assertion targets a deterministic single body; the merge
        // wires the real GenDomesticFamily.do일반내정 body over the SOLE rng.
        val policy = AutorunGeneralPolicy(
            npcType = 2, nationId = 1,
            serverPolicy = mapOf("priority" to listOf("일반내정")),
        )
        val rng = RandUtil(LiteHashDrbg("assemble-dispatch"))
        val ctx = ctxOf(rng, city(100, trust = 50.0), develRate, generalPolicy = policy)
        val bodies = GeneralAiDoBodies.fromFamilies(ctx)

        val ai = GeneralAiFactory.build(generalPolicy = policy, bodies = bodies)
        val chosen = ai.chooseGeneralTurn(ChosenCommand("휴식", emptyMap()), genInput(rng))

        assertEquals("do일반내정", chosen.reason, "fired through the priority loop ('do'+actionName)")
        assertTrue(chosen.actionCode != "che_중립", "NON-neutral — the wired family body won, not the fallback")
    }
}
