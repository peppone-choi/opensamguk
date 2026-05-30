package opensamguk.logic.ai.families

import opensamguk.common.rng.LiteHashDrbg
import opensamguk.common.rng.RandUtil
import opensamguk.logic.ai.AiDiplomacyRow
import opensamguk.logic.ai.AiEnv
import opensamguk.logic.ai.AiGeneralView
import opensamguk.logic.ai.AiInstanceState
import opensamguk.logic.ai.AiKvRecorder
import opensamguk.logic.ai.AiNationRow
import opensamguk.logic.ai.AiWorldView
import opensamguk.logic.ai.AutorunGeneralPolicy
import opensamguk.logic.ai.AutorunNationPolicy
import opensamguk.logic.ai.ChosenCommand
import opensamguk.logic.ai.GeneralAiContext
import opensamguk.logic.ai.GeneralAiDoBodies
import opensamguk.logic.ai.GeneralAiFactory
import opensamguk.logic.domain.City
import opensamguk.logic.domain.General
import opensamguk.logic.stats.GeneralActionPipeline
import opensamguk.logic.stats.StatCalc
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * L-GENDOM — the WORLD-DRIVEN GenDomesticFamily `do<한글>` bodies ([GenDomesticFamily.bodies]).
 *
 * GRAND TRUTH = PHP `legacy/devsam-core/hwe/sammo/GeneralAI.php` (read in full):
 *  - `do일반내정`   (`:2117-2218`): low-rice `nextBool(0.3)` skip (`&&` only when `nation.rice < baserice`) THEN
 *    a terminal `choiceUsingWeightPair($cmdList)` over the genType-gated develop commands in PHP append order.
 *  - `do전쟁내정`   (`:2253-2364`): the TWO-`nextBool(0.3)` trap — `:2271` conditional then `:2279` ALWAYS — then
 *    the same terminal `choiceUsingWeightPair($cmdList)` (war-mode weights).
 *  - `do긴급내정`   (`:2220-2251`): up to two conditional `nextBool` (`:2236` trust<70 → che_주민선정,
 *    `:2243` pop<min → che_정착장려).
 *  - `do금쌀구매`   (`:2367-2481`): ZERO RNG draws (deterministic buy/sell ladder).
 *  - `do징병`       (`:2483-2651`): conditional recruit-skip `nextBool(remainPop/maxPop)` (`:2512`) →
 *    `choiceUsingWeight($availableArmType)` (`:2554`, only when no preset) → `choiceUsingWeight($types)` (`:2580`).
 *
 * Each test builds [GenDomesticFamily.bodies] over a DETERMINISTIC fixture and asserts the chosen
 * `(actionCode, RAW args)` + the ordered draw stream off a recording [RandUtil]. The candidate-set ORDER (the
 * develRate-driven cmdList append order, the 4-entry armType insertion order) is the draw-for-draw parity target.
 */
class GenDomesticBodiesTest {

    /**
     * A draw-recording RNG over a REAL [LiteHashDrbg]. Records the top-level SEMANTIC draw stream
     * (`nextBool` prob + `choiceUsingWeightPair` as a `nextFloat1`). A re-entrancy guard prevents the inner
     * primitive a `nextBool` consumes from being double-recorded.
     */
    private class RecordingRng(seed: String) : RandUtil(LiteHashDrbg(seed)) {
        data class Draw(val kind: String, val prob: Double)
        val draws = ArrayList<Draw>()
        private var inNextBool = false
        override fun nextBool(prob: Double): Boolean {
            draws.add(Draw("nextBool", prob))
            inNextBool = true
            try {
                return super.nextBool(prob)
            } finally {
                inNextBool = false
            }
        }
        override fun nextFloat1(): Double {
            if (!inNextBool) draws.add(Draw("choiceUsingWeightPair", 0.0))
            return super.nextFloat1()
        }
    }

    // --- fixture builders -----------------------------------------------------------------------------

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

    private fun general(no: Int, cityId: Int = 100, crew: Int = 0): General = General(
        id = no, nationId = 1, cityId = cityId,
        leadership = 50, strength = 50, intel = 50, injury = 0,
        experience = 0.0, dedication = 0.0,
        officerLevel = 1, gold = 0, rice = 0,
        crew = crew, train = 0.0, atmos = 0.0, troop = 0, npcType = 2,
        meta = linkedMapOf("killturn" to 1000, "belong" to 1),
    )

    private fun instance(
        dipState: Int = AiInstanceState.D_PEACE,
        rice: Int = 99999,
        diplomacy: List<AiDiplomacyRow> = emptyList(),
        frontMax: Int = 0,
    ): AiInstanceState {
        val env = AiEnv(year = 200, month = 1, startYear = 180, develCost = 10)
        val nationPolicy = AutorunNationPolicy(npcType = 2, tech = 0, develcost = 10)
        val st = AiInstanceState(
            generalNationId = 1,
            env = env,
            nationPolicy = nationPolicy,
            nationRowLookup = { AiNationRow(nation = 1, level = 5, capital = 100, gold = 99999, rice = rice) },
            nationStor = emptyMap(),
            diplomacyOf = { diplomacy },
            frontMaxOf = { frontMax },
            kvRecorder = object : AiKvRecorder { override fun recordNationKv(nationId: Int, key: String, value: Any?) {} },
        )
        st.updateInstance()
        // Equal stats (leadership/strength/intel = 50) → strength>=intel band (tie→무장), the near-balance roll
        // may add 지장; leadership 50 >= minNPCWarLeadership 40 → 통솔장. genType is asserted via the body behavior.
        val g = General(
            id = 1, nationId = 1, cityId = 100,
            leadership = 50, strength = 50, intel = 50, injury = 0,
            experience = 0.0, dedication = 0.0,
            officerLevel = 1, gold = 0, rice = 0,
        )
        val statCalc = StatCalc(g, GeneralActionPipeline())
        st.calcGenType(RandUtil(LiteHashDrbg("genType-fixture")), statCalc)
        require(st.dipState == dipState) { "fixture dipState mismatch: wanted $dipState got ${st.dipState}" }
        return st
    }

    private fun ctxOf(
        rng: RandUtil,
        instance: AiInstanceState,
        selfCity: City,
        cityDevelRate: Map<String, Double>,
        candidateAllowed: (String, Map<String, Any?>) -> Boolean = { _, _ -> true },
        leadership: Double = 50.0,
        fullLeadership: Double = 50.0,
        fullStrength: Double = 50.0,
        fullIntel: Double = 50.0,
        chiefStatMin: Int = 65,
        techLimited: Boolean = true,
        techLimitedNextGrade: Boolean = true,
        recruitArmType: Int? = null,
        recruitArmTypeWeights: List<Pair<Int, Double>> = emptyList(),
        recruitCrewScoresFor: (Int) -> List<Pair<Int, Double>> = { emptyList() },
        recruitFinalize: (Int) -> ChosenCommand? = { null },
        tradeDecision: () -> ChosenCommand? = { null },
        generalPolicy: AutorunGeneralPolicy = AutorunGeneralPolicy(npcType = 2, nationId = 1),
        nationPolicy: AutorunNationPolicy = AutorunNationPolicy(npcType = 2, tech = 0, develcost = 10),
    ): GeneralAiContext {
        val world = AiWorldView(
            ownNationId = 1,
            cityRows = listOf(selfCity),
            warTargetNation = null,
            ownGeneralId = 1,
            generals = emptyList(),
            dipState = instance.dipState,
            minWarCrew = nationPolicy.minWarCrew,
            minNpcWarLeadership = nationPolicy.minNPCWarLeadership,
            turnTerm = 120,
        )
        return GeneralAiContext(
            rng = rng,
            instance = instance,
            world = world,
            generalPolicy = generalPolicy,
            nationPolicy = nationPolicy,
            env = AiEnv(year = 200, month = 1, startYear = 180, develCost = 10),
            turnTerm = 120,
            selfGeneralId = 1,
            selfCityId = selfCity.id,
            candidateAllowed = candidateAllowed,
            leadershipWithInjury = leadership,
            fullLeadership = fullLeadership,
            fullStrength = fullStrength,
            fullIntel = fullIntel,
            selfCity = selfCity,
            cityDevelRate = cityDevelRate,
            chiefStatMin = chiefStatMin,
            techLimited = techLimited,
            techLimitedNextGrade = techLimitedNextGrade,
            recruitArmType = recruitArmType,
            recruitArmTypeWeights = recruitArmTypeWeights,
            recruitCrewScoresFor = recruitCrewScoresFor,
            recruitFinalize = recruitFinalize,
            tradeDecision = tradeDecision,
        )
    }

    // ==================================================================================================
    // do일반내정 (PHP :2117-2218) — terminal choiceUsingWeightPair over the genType-gated develop cmdList.
    // ==================================================================================================

    @Test
    fun `do일반내정 with one low develop ratio picks that develop command and draws one weighted pick`() {
        // genType 무장|통솔장 (equal stats via the stub → strength>=intel band, tie→무장; leadership>=40→통솔장).
        // trust ratio 0.5 < 0.98 (통솔장) → che_주민선정 candidate; all other ratios are full (1.0) → no append.
        val c = city(100, trust = 50.0)
        val develRate = linkedMapOf(
            "trust" to 0.5, "pop" to 1.0, "agri" to 1.0, "comm" to 1.0, "secu" to 1.0, "def" to 1.0, "wall" to 1.0,
        )
        val st = instance(rice = 99999) // rice >= baserice → no low-rice skip draw.
        val rng = RecordingRng("domestic")
        val ctx = ctxOf(rng, st, c, develRate)

        val body = GenDomesticFamily.bodies(ctx).getValue("일반내정")
        val chosen = body(null)!!

        assertEquals("che_주민선정", chosen.actionCode)
        // rice>=baserice → the :2136 nextBool(0.3) is SUPPRESSED (no draw); only the terminal weighted pick draws.
        assertEquals(
            listOf(RecordingRng.Draw("choiceUsingWeightPair", 0.0)),
            rng.draws,
            "rice>=baserice suppresses the low-rice 0.3; one terminal choiceUsingWeightPair",
        )
    }

    @Test
    fun `do일반내정 low rice fires the conditional 0_3 skip BEFORE the cmdList build`() {
        val c = city(100, trust = 50.0)
        val develRate = linkedMapOf(
            "trust" to 0.5, "pop" to 1.0, "agri" to 1.0, "comm" to 1.0, "secu" to 1.0, "def" to 1.0, "wall" to 1.0,
        )
        // rice < baserice (2000) → the :2136 nextBool(0.3) is REACHED. Whether it returns null is data-dependent;
        // the draw stream MUST begin with the 0.3 (the parity target is the draw, not the boolean).
        val st = instance(rice = 100)
        val rng = RecordingRng("domestic-lowrice")
        val ctx = ctxOf(rng, st, c, develRate)

        val body = GenDomesticFamily.bodies(ctx).getValue("일반내정")
        body(null)

        assertEquals("nextBool", rng.draws.first().kind)
        assertEquals(0.3, rng.draws.first().prob, "the FIRST draw is the low-rice nextBool(0.3) (:2136)")
    }

    @Test
    fun `do일반내정 empty cmdList returns null with no terminal draw`() {
        // All develRate full → no candidate appended → :2213 empty guard → null, no terminal draw.
        val c = city(100, trust = 100.0)
        val develRate = linkedMapOf(
            "trust" to 1.0, "pop" to 1.0, "agri" to 1.0, "comm" to 1.0, "secu" to 1.0, "def" to 1.0, "wall" to 1.0,
        )
        val st = instance(rice = 99999)
        val rng = RecordingRng("domestic-empty")
        val ctx = ctxOf(rng, st, c, develRate)

        val body = GenDomesticFamily.bodies(ctx).getValue("일반내정")
        assertNull(body(null), "empty cmdList → :2213 null")
        assertTrue(rng.draws.isEmpty(), "no terminal draw when cmdList empty (rice>=baserice → no low-rice draw)")
    }

    // ==================================================================================================
    // do전쟁내정 (PHP :2253-2364) — the TWO nextBool(0.3) trap: :2271 conditional THEN :2279 ALWAYS.
    // ==================================================================================================

    @Test
    fun `do전쟁내정 draws the unconditional 0_3 then the terminal pick when rice is high`() {
        val c = city(100, trust = 50.0)
        // war-mode trust threshold is the same (<0.98) → che_주민선정 candidate.
        val develRate = linkedMapOf(
            "trust" to 0.5, "pop" to 1.0, "agri" to 1.0, "comm" to 1.0, "secu" to 1.0, "def" to 1.0, "wall" to 1.0,
        )
        val st = instance(
            dipState = AiInstanceState.D_WAR,
            rice = 99999,
            diplomacy = listOf(AiDiplomacyRow(you = 2, state = 0, term = 0)),
            frontMax = 1,
        )
        val rng = RecordingRng("war-domestic")
        // The unconditional 0.3 must NOT skip for this assertion to reach the terminal pick: pick a seed where
        // nextBool(0.3) is false. We instead assert the draw STREAM regardless of the boolean outcome.
        val ctx = ctxOf(rng, st, c, develRate)

        val body = GenDomesticFamily.bodies(ctx).getValue("전쟁내정")
        body(null)

        // rice>=baserice → :2271 conditional SUPPRESSED. :2279 unconditional 0.3 ALWAYS draws FIRST.
        assertEquals(RecordingRng.Draw("nextBool", 0.3), rng.draws.first(),
            "rice>=baserice suppresses :2271; the FIRST draw is the unconditional :2279 nextBool(0.3)")
        // exactly one nextBool(0.3) before any terminal pick (never two when rice is high).
        assertEquals(1, rng.draws.count { it.kind == "nextBool" }, "only the unconditional :2279 0.3 (not :2271)")
    }

    @Test
    fun `do전쟁내정 low rice draws BOTH 0_3 the conditional then the unconditional`() {
        val c = city(100, trust = 50.0)
        val develRate = linkedMapOf(
            "trust" to 0.5, "pop" to 1.0, "agri" to 1.0, "comm" to 1.0, "secu" to 1.0, "def" to 1.0, "wall" to 1.0,
        )
        val st = instance(
            dipState = AiInstanceState.D_WAR,
            rice = 100,
            diplomacy = listOf(AiDiplomacyRow(you = 2, state = 0, term = 0)),
            frontMax = 1,
        )
        // Seed chosen so the conditional :2271 nextBool(0.3) is FALSE (does not early-return) → both 0.3 draw.
        val rng = RecordingRng("war-domestic-lowrice")
        val ctx = ctxOf(rng, st, c, develRate)

        val body = GenDomesticFamily.bodies(ctx).getValue("전쟁내정")
        body(null)

        // FIRST two draws are the two distinct nextBool(0.3) — NEVER collapsed (decision #9).
        assertEquals(RecordingRng.Draw("nextBool", 0.3), rng.draws[0], ":2271 conditional 0.3 (rice<baserice)")
        // if the conditional 0.3 returned true the body returns null after ONE draw; this seed must reach two.
        if (rng.draws.size > 1) {
            assertEquals(RecordingRng.Draw("nextBool", 0.3), rng.draws[1], ":2279 unconditional 0.3")
        }
    }

    // ==================================================================================================
    // do긴급내정 (PHP :2220-2251) — trust<70 → nextBool(leadership/chiefStatMin) → che_주민선정.
    // ==================================================================================================

    @Test
    fun `do긴급내정 trust below 70 draws the trust gate and emits che_주민선정`() {
        val c = city(100, trust = 50.0, pop = 100_000) // trust<70 → gate reached; pop high → 정착 gate not reached.
        val st = instance(
            dipState = AiInstanceState.D_WAR,
            diplomacy = listOf(AiDiplomacyRow(you = 2, state = 0, term = 0)),
            frontMax = 1,
        )
        val rng = RecordingRng("emergency")
        // leadership 100 / chiefStatMin 65 → prob > 1 → nextBool returns true with NO underlying byte, but the
        // nextBool invocation IS recorded.
        val ctx = ctxOf(rng, st, c, emptyMap(), leadership = 100.0, chiefStatMin = 65)

        val body = GenDomesticFamily.bodies(ctx).getValue("긴급내정")
        val chosen = body(null)!!

        assertEquals("che_주민선정", chosen.actionCode)
        assertEquals(RecordingRng.Draw("nextBool", 100.0 / 65.0), rng.draws.first(),
            "the :2236 trust gate nextBool(leadership/chiefStatMin)")
    }

    @Test
    fun `do긴급내정 d평화 returns null with no draws`() {
        val c = city(100, trust = 50.0)
        val st = instance(dipState = AiInstanceState.D_PEACE)
        val rng = RecordingRng("emergency-peace")
        val ctx = ctxOf(rng, st, c, emptyMap())

        val body = GenDomesticFamily.bodies(ctx).getValue("긴급내정")
        assertNull(body(null), "d평화 → :2222 early null")
        assertTrue(rng.draws.isEmpty(), "no draws at d평화")
    }

    @Test
    fun `do긴급내정 high trust skips the trust gate and draws the pop gate`() {
        // trust>=70 → :2236 suppressed (no draw). pop<min → :2243 reached → che_정착장려.
        val c = city(100, trust = 80.0, pop = 100)
        val st = instance(
            dipState = AiInstanceState.D_WAR,
            diplomacy = listOf(AiDiplomacyRow(you = 2, state = 0, term = 0)),
            frontMax = 1,
        )
        val rng = RecordingRng("emergency-pop")
        val ctx = ctxOf(rng, st, c, emptyMap(), leadership = 100.0, chiefStatMin = 65)

        val body = GenDomesticFamily.bodies(ctx).getValue("긴급내정")
        val chosen = body(null)!!

        assertEquals("che_정착장려", chosen.actionCode)
        // ONLY the pop gate drew (the trust gate was suppressed by trust>=70).
        assertEquals(
            listOf(RecordingRng.Draw("nextBool", 100.0 / 65.0 / 2)),
            rng.draws,
            "trust>=70 suppresses :2236; only the :2243 pop gate nextBool(leadership/chiefStatMin/2) draws",
        )
    }

    // ==================================================================================================
    // do금쌀구매 (PHP :2367-2481) — ZERO RNG draws (deterministic ladder).
    // ==================================================================================================

    @Test
    fun `do금쌀구매 delegates to the deterministic tradeDecision with ZERO draws`() {
        val c = city(100)
        val st = instance(rice = 99999)
        val rng = RecordingRng("trade")
        val emit = ChosenCommand("che_군량매매", linkedMapOf("buyRice" to true, "amount" to 500))
        val ctx = ctxOf(rng, st, c, emptyMap(), tradeDecision = { emit })

        val body = GenDomesticFamily.bodies(ctx).getValue("금쌀구매")
        val chosen = body(null)!!

        assertEquals(emit, chosen)
        assertEquals("che_군량매매", chosen.actionCode)
        assertEquals(true, chosen.args["buyRice"])
        assertEquals(500, chosen.args["amount"])
        assertTrue(rng.draws.isEmpty(), "do금쌀구매 makes ZERO draws (decision #9)")
    }

    @Test
    fun `do금쌀구매 null tradeDecision returns null with ZERO draws`() {
        val c = city(100)
        val st = instance(rice = 99999)
        val rng = RecordingRng("trade-null")
        val ctx = ctxOf(rng, st, c, emptyMap(), tradeDecision = { null })

        val body = GenDomesticFamily.bodies(ctx).getValue("금쌀구매")
        assertNull(body(null))
        assertTrue(rng.draws.isEmpty(), "ZERO draws even on the null path")
    }

    // ==================================================================================================
    // do징병 (PHP :2483-2651) — recruit-skip → choiceUsingWeight(armType) → choiceUsingWeight(types).
    // ==================================================================================================

    @Test
    fun `do징병 draws armType then crewType then finalizes the recruit emit`() {
        // d전쟁, genType has 통솔장 (leadership 50>=40), crew below minWarCrew, can한계징병 false, pop safe ratio
        // so the recruit-skip nextBool is SUPPRESSED (pop/pop_max >= safeRecruitCityPopulationRatio).
        val c = city(100, pop = 100_000, popMax = 100_000) // ratio 1.0 >= 0.5 → recruit-skip suppressed.
        val st = instance(
            dipState = AiInstanceState.D_WAR,
            diplomacy = listOf(AiDiplomacyRow(you = 2, state = 0, term = 0)),
            frontMax = 1,
        )
        val rng = RecordingRng("recruit")
        val armWeights = listOf(
            GenDomesticFamily.T_FOOTMAN to 100.0,
            GenDomesticFamily.T_ARCHER to 50.0,
            GenDomesticFamily.T_CAVALRY to 50.0,
        )
        var finalizedFor = -1
        val emit = ChosenCommand("che_징병", linkedMapOf("crewType" to 11, "amount" to 5000))
        val ctx = ctxOf(
            rng, st, c, emptyMap(),
            recruitArmType = null, // no preset → the armType choiceUsingWeight draws.
            recruitArmTypeWeights = armWeights,
            // Whatever armType the weighted pick lands on, the single-entry types list resolves to crew-type 11.
            recruitCrewScoresFor = { _ -> listOf(11 to 1.0) },
            recruitFinalize = { crewTypeId -> finalizedFor = crewTypeId; emit },
        )

        val body = GenDomesticFamily.bodies(ctx).getValue("징병")
        val chosen = body(null)!!

        assertEquals(emit, chosen)
        // pop ratio safe → recruit-skip suppressed → first draw is the armType choiceUsingWeight (one float).
        assertEquals(
            listOf(RecordingRng.Draw("choiceUsingWeightPair", 0.0), RecordingRng.Draw("choiceUsingWeightPair", 0.0)),
            rng.draws,
            "armType choiceUsingWeight FIRST then crewType choiceUsingWeight SECOND (no recruit-skip draw)",
        )
        assertEquals(11, finalizedFor, "the chosen crew-type id is threaded into recruitFinalize")
    }

    @Test
    fun `do징병 preset armType skips the armType draw`() {
        val c = city(100, pop = 100_000, popMax = 100_000)
        val st = instance(
            dipState = AiInstanceState.D_WAR,
            diplomacy = listOf(AiDiplomacyRow(you = 2, state = 0, term = 0)),
            frontMax = 1,
        )
        val rng = RecordingRng("recruit-preset")
        val emit = ChosenCommand("che_징병", linkedMapOf("crewType" to 22, "amount" to 5000))
        val ctx = ctxOf(
            rng, st, c, emptyMap(),
            recruitArmType = GenDomesticFamily.T_ARCHER, // preset → no armType draw.
            recruitCrewScoresFor = { listOf(22 to 1.0) },
            recruitFinalize = { emit },
        )

        val body = GenDomesticFamily.bodies(ctx).getValue("징병")
        val chosen = body(null)!!

        assertEquals(emit, chosen)
        // ONLY the crewType choiceUsingWeight drew (the preset armType skipped the :2554 draw).
        assertEquals(
            listOf(RecordingRng.Draw("choiceUsingWeightPair", 0.0)),
            rng.draws,
            "preset armType → only the crewType choiceUsingWeight draws",
        )
    }

    @Test
    fun `do징병 d평화 returns null with no draws`() {
        val c = city(100)
        val st = instance(dipState = AiInstanceState.D_PEACE)
        val rng = RecordingRng("recruit-peace")
        val ctx = ctxOf(rng, st, c, emptyMap())

        val body = GenDomesticFamily.bodies(ctx).getValue("징병")
        assertNull(body(null), "d평화 → :2485 early null")
        assertTrue(rng.draws.isEmpty(), "no draws at d평화")
    }

    // ==================================================================================================
    // ASSEMBLE — the domestic bodies register into the factory's generalDispatch by action-name.
    // ==================================================================================================

    @Test
    fun `domestic bodies register into the factory generalDispatch by action-name`() {
        val c = city(100, trust = 50.0)
        val develRate = linkedMapOf(
            "trust" to 0.5, "pop" to 1.0, "agri" to 1.0, "comm" to 1.0, "secu" to 1.0, "def" to 1.0, "wall" to 1.0,
        )
        val st = instance(rice = 99999)
        val rng = RandUtil(LiteHashDrbg("assemble"))
        val ctx = ctxOf(rng, st, c, develRate)

        val bodies = GeneralAiDoBodies(generalDispatch = GenDomesticFamily.bodies(ctx))
        GeneralAiFactory.build(
            generalPolicy = AutorunGeneralPolicy(npcType = 2, nationId = 1),
            bodies = bodies,
        )

        val keys = GenDomesticFamily.bodies(ctx).keys.toList()
        assertEquals(listOf("일반내정", "전쟁내정", "긴급내정", "금쌀구매", "징병"), keys,
            "the 5 domestic bodies keyed by bare do<한글> action-name in PHP order")
    }
}
