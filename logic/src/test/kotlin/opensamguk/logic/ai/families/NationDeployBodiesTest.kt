package opensamguk.logic.ai.families

import opensamguk.common.constants.GameConst
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
import opensamguk.logic.ai.NationAiInput
import opensamguk.logic.domain.City
import opensamguk.logic.domain.General
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * L-DEPLOY — the WORLD-DRIVEN `do발령` bodies ([NationDeployFamily.bodies]).
 *
 * GRAND TRUTH = PHP `legacy/devsam-core/hwe/sammo/GeneralAI.php:294-1254` (read in full). Each test builds
 * the [NationDeployFamily.bodies] over a DETERMINISTIC fixture [AiWorldView]/[AiInstanceState]/policy and
 * asserts, for a representative scenario per method, the chosen `(actionCode, RAW args)` + the ordered draw
 * stream off a recording [RandUtil]. The candidate-set ORDER (PK-ascending buckets + name-order BFS) is the
 * draw-for-draw parity target; the body calls the PURE primitive then gates via `candidateAllowed`.
 */
class NationDeployBodiesTest {

    /** A draw-recording RNG over a REAL [LiteHashDrbg] — records the ordered (method, n) draw stream. */
    private class RecordingRng(seed: String) : RandUtil(LiteHashDrbg(seed)) {
        data class Draw(val kind: String, val n: Int)
        val draws = ArrayList<Draw>()
        override fun <T> choice(items: List<T>): T {
            draws.add(Draw("choice", items.size)); return super.choice(items)
        }
        override fun nextFloat1(): Double {
            draws.add(Draw("nextFloat1", 0)); return super.nextFloat1()
        }
    }

    // --- fixture builders -----------------------------------------------------------------------------

    private fun city(
        id: Int,
        nation: Int = 1,
        supply: Int = 1,
        front: Int = 0,
        pop: Int = 100_000,
        popMax: Int = 100_000,
        agri: Int = 100,
        agriMax: Int = 100,
    ): City = City(
        id = id, nationId = nation, level = 5,
        commerce = 0, commerceMax = 1, agriculture = agri, agricultureMax = agriMax,
        supplyState = supply, frontState = front, trust = 0.0,
        security = 0, securityMax = 1, defense = 0, defenseMax = 1, wall = 0, wallMax = 1,
        population = pop, populationMax = popMax,
    )

    private fun general(
        no: Int,
        nation: Int = 1,
        cityId: Int = 0,
        officerLevel: Int = 1,
        npcType: Int = 0,
        troop: Int = 0,
        crew: Int = 0,
        train: Double = 0.0,
        atmos: Double = 0.0,
        leadership: Int = 50,
        killturn: Int = 1000,
        belong: Int = 1,
        meta: Map<String, Any?> = linkedMapOf("killturn" to 1000, "belong" to 1),
    ): General = General(
        id = no, nationId = nation, cityId = cityId,
        leadership = leadership, strength = 50, intel = 50, injury = 0,
        experience = 0.0, dedication = 0.0,
        officerLevel = officerLevel, gold = 0, rice = 0,
        crew = crew, train = train, atmos = atmos, troop = troop, npcType = npcType,
        meta = (linkedMapOf<String, Any?>("killturn" to killturn, "belong" to belong) + meta),
    )

    private fun gv(
        g: General,
        recentWarSeconds: Long? = null,
        reservedCommandName: String? = null,
        fullLeadership: Double = g.leadership.toDouble(),
    ): AiGeneralView = AiGeneralView(g, recentWarSeconds, reservedCommandName, fullLeadership)

    private fun instance(
        nationCapital: Int = 100,
        dipState: Int = AiInstanceState.D_PEACE,
        diplomacy: List<AiDiplomacyRow> = emptyList(),
        frontMax: Int = 0,
    ): AiInstanceState {
        val env = AiEnv(year = 200, month = 1, startYear = 180, develCost = 10)
        val nationPolicy = AutorunNationPolicy(npcType = 2, tech = 0, develcost = 10)
        val st = AiInstanceState(
            generalNationId = 1,
            env = env,
            nationPolicy = nationPolicy,
            nationRowLookup = {
                AiNationRow(nation = 1, level = 5, capital = nationCapital, gold = 99999, rice = 99999)
            },
            nationStor = emptyMap(),
            diplomacyOf = { diplomacy },
            frontMaxOf = { frontMax },
            kvRecorder = object : AiKvRecorder { override fun recordNationKv(nationId: Int, key: String, value: Any?) {} },
        )
        st.updateInstance()
        // Force the desired dipState directly only via the diplomacy/frontMax inputs above (no setter).
        require(st.dipState == dipState || dipState == AiInstanceState.D_PEACE) {
            "fixture dipState mismatch: wanted $dipState got ${st.dipState}"
        }
        return st
    }

    private fun ctxOf(
        rng: RandUtil,
        cityRows: List<City>,
        generals: List<AiGeneralView>,
        instance: AiInstanceState,
        nationPolicy: AutorunNationPolicy = AutorunNationPolicy(npcType = 2, tech = 0, develcost = 10),
        selfGeneralId: Int = 1,
        selfCityId: Int = 100,
        dipState: Int = AiInstanceState.D_PEACE,
        candidateAllowed: (String, Map<String, Any?>) -> Boolean = { _, _ -> true },
        turnTimeOf: (AiGeneralView) -> String = { "" },
        last발령Of: (AiGeneralView) -> Int? = { null },
        recruitPopScoreOf: (AiGeneralView) -> Double = { 100.0 },
        reservedIsRecruitOf: (AiGeneralView) -> Boolean = { true },
        chiefTurnTime: String = "2024-01-01 01:00:00.000000",
    ): GeneralAiContext {
        val world = AiWorldView(
            ownNationId = 1,
            cityRows = cityRows,
            warTargetNation = mapOf(2 to 2, 0 to 1),
            ownGeneralId = selfGeneralId,
            generals = generals,
            dipState = dipState,
            minWarCrew = nationPolicy.minWarCrew,
            minNpcWarLeadership = nationPolicy.minNPCWarLeadership,
            turnTerm = 120,
        )
        return GeneralAiContext(
            rng = rng,
            instance = instance,
            world = world,
            generalPolicy = AutorunGeneralPolicy(npcType = 2, nationId = 1),
            nationPolicy = nationPolicy,
            env = AiEnv(year = 200, month = 1, startYear = 180, develCost = 10),
            turnTerm = 120,
            selfGeneralId = selfGeneralId,
            selfCityId = selfCityId,
            candidateAllowed = candidateAllowed,
            chiefTurnTime = chiefTurnTime,
            turnTimeOf = turnTimeOf,
            last발령Of = last발령Of,
            recruitPopScoreOf = recruitPopScoreOf,
            reservedIsRecruitOf = reservedIsRecruitOf,
        )
    }

    // ==================================================================================================
    // do유저장전방발령 (PHP :817-875) — general choice FIRST + choiceUsingWeight(front 'important') SECOND.
    // ==================================================================================================

    @Test
    fun `do유저장전방발령 picks general then weighted front city and emits che_발령`() {
        // Two supplied cities: backup 100 (chief, not front), front 110 (front). War state (d전쟁).
        val cityRows = listOf(
            city(100, supply = 1, front = 0),
            city(110, supply = 1, front = 1),
        )
        // userWarGeneral 5 is in city 100 (a nation city, NOT front), crew>=minWarCrew, train>=properWarTrainAtmos,
        // no troop → a valid candidate. Bucketed into userWarGenerals via recentWar<=lastWar+12.
        val g5 = general(5, cityId = 100, crew = 2000, train = 95.0, atmos = 95.0, leadership = 50)
        val generals = listOf(gv(g5, recentWarSeconds = 0L)) // recentWar 0 → userWar bucket
        // d전쟁: diplomacy state 0 war target, frontMax !=0.
        val st = instance(
            dipState = AiInstanceState.D_WAR,
            diplomacy = listOf(AiDiplomacyRow(you = 2, state = 0, term = 0)),
            frontMax = 1,
        )
        val rng = RecordingRng("front-deploy")
        val ctx = ctxOf(rng, cityRows, generals, st, dipState = AiInstanceState.D_WAR)

        val body = NationDeployFamily.bodies(ctx).getValue("유저장전방발령")
        val chosen = body(null)!!

        assertEquals("che_발령", chosen.actionCode)
        assertEquals(listOf("destGeneralID", "destCityID"), chosen.args.keys.toList())
        assertEquals(5, chosen.args["destGeneralID"], "the only userWar candidate")
        assertEquals(110, chosen.args["destCityID"], "the only front city")
        // draw stream: choice(generals,1) FIRST then choiceUsingWeight(front,1) = one nextFloat1.
        assertEquals(
            listOf(RecordingRng.Draw("choice", 1), RecordingRng.Draw("nextFloat1", 0)),
            rng.draws,
            "general choice FIRST (array-literal L->R) then choiceUsingWeight SECOND",
        )
    }

    // ==================================================================================================
    // do부대전방발령 (PHP :294-397) — emits the destGenaralID TYPO → candidateAllowed rejects che_발령 → null
    // (the final choice draw STILL happens before the reject).
    // ==================================================================================================

    @Test
    fun `do부대전방발령 emits the destGenaralID typo and returns null after the final draw`() {
        // One troop leader (npcType 5) in a non-front supplied city; CombatForce has it with off-route
        // endpoints → the "front anywhere" branch (choice(frontCities)); then the final choice(troopCandidate).
        val cityRows = listOf(
            city(100, supply = 1, front = 0),
            city(110, supply = 1, front = 1),
        )
        val leader = general(7, cityId = 100, npcType = 5)
        val generals = listOf(gv(leader)) // npcType 5 → troopLeaders bucket
        val st = instance(nationCapital = 100)
        val rng = RecordingRng("troop-forward")
        // CombatForce[7] = [from=900, to=901] — both off the warRoute → "front anywhere" branch.
        val nationPolicy = object {} // placeholder; replaced below via reflection-free direct map
        val policy = AutorunNationPolicy(
            npcType = 2, tech = 0, develcost = 10,
            nationPolicy = mapOf("values" to mapOf("CombatForce" to mapOf(7 to listOf(900, 901)))),
        )
        // The typo means the canonical che_발령 destGeneralID is null → argTest reject → never allowed.
        var sawCheBallyeong = false
        val ctx = ctxOf(
            rng, cityRows, generals, st,
            nationPolicy = policy,
            selfCityId = 100,
            candidateAllowed = { code, args ->
                sawCheBallyeong = true
                // Reproduce the argTest: the typo emit lacks destGeneralID → reject (R-BRIDGE §4).
                code == "che_발령" && args["destGeneralID"] != null
            },
        )

        val body = NationDeployFamily.bodies(ctx).getValue("부대전방발령")
        val chosen = body(null)

        assertNull(chosen, "the destGenaralID typo makes destGeneralID null → argTest reject → always null")
        assertTrue(sawCheBallyeong, "the gate was consulted (the draw happened, then the reject)")
        // The "front anywhere" choice + the final choice(troopCandidate) both drew.
        assertEquals(
            listOf(RecordingRng.Draw("choice", 1), RecordingRng.Draw("choice", 1)),
            rng.draws,
            "front-anywhere choice (1 front city) THEN final choice(troopCandidate of 1) — both draw before the reject",
        )
    }

    // ==================================================================================================
    // do유저장구출발령 (PHP :758-815) — per-lostGeneral choice($supplyCities) + one final choice($args).
    // ==================================================================================================

    @Test
    fun `do유저장구출발령 draws one city-choice per qualifying lostGeneral then one final and emits che_발령`() {
        // Two supplied cities; a lostGeneral (in a non-nation city → lost), npcType<2, not defensible.
        val cityRows = listOf(
            city(100, supply = 1, front = 0),
            city(120, supply = 1, front = 0),
        )
        // g8 in city 999 (NOT ours) → lostGenerals; npcType 0; crew below minWarCrew → not defensible-skipped.
        val g8 = general(8, cityId = 999, npcType = 0, crew = 0)
        val generals = listOf(gv(g8))
        val st = instance(nationCapital = 100, dipState = AiInstanceState.D_PEACE)
        val rng = RecordingRng("rescue")
        val ctx = ctxOf(rng, cityRows, generals, st, dipState = AiInstanceState.D_PEACE)

        val body = NationDeployFamily.bodies(ctx).getValue("유저장구출발령")
        val chosen = body(null)!!

        assertEquals("che_발령", chosen.actionCode)
        assertEquals(8, chosen.args["destGeneralID"])
        assertTrue(chosen.args["destCityID"] in listOf(100, 120))
        // 1 qualifying lostGeneral → choice($supplyCities,2) in loop, then final choice($args,1).
        assertEquals(
            listOf(RecordingRng.Draw("choice", 2), RecordingRng.Draw("choice", 1)),
            rng.draws,
            "draws = #qualifying (1) + 1 final (PHP :795/807)",
        )
    }

    // ==================================================================================================
    // do부대후방발령 (PHP :399-486) — troop choice FIRST + city choice SECOND.
    // ==================================================================================================

    @Test
    fun `do부대후방발령 picks troop then city and emits che_발령`() {
        // supply 100 (chief, backup high-pop), supply 110 (low-pop), front 130. SupportForce has leader 9.
        val cityRows = listOf(
            city(100, supply = 1, front = 0, pop = 90_000, popMax = 100_000), // backup, ratio 0.9 >= 0.5
            city(110, supply = 1, front = 0, pop = 10_000, popMax = 100_000), // troop leader's city, ratio 0.1 < 0.5
            city(130, supply = 1, front = 1, pop = 50_000, popMax = 100_000), // front
        )
        // leader 9 (npcType 5) in low-pop city 110 → candidate (popRatio < ratio).
        val leader = general(9, cityId = 110, npcType = 5)
        val generals = listOf(gv(leader))
        val st = instance(nationCapital = 100)
        val rng = RecordingRng("troop-rear")
        val policy = AutorunNationPolicy(
            npcType = 2, tech = 0, develcost = 10,
            nationPolicy = mapOf("values" to mapOf("SupportForce" to mapOf(0 to 9))),
        )
        val ctx = ctxOf(rng, cityRows, generals, st, nationPolicy = policy, selfCityId = 100)

        val body = NationDeployFamily.bodies(ctx).getValue("부대후방발령")
        val chosen = body(null)!!

        assertEquals("che_발령", chosen.actionCode)
        assertEquals(9, chosen.args["destGeneralID"], "the only SupportForce troop leader")
        assertEquals(100, chosen.args["destCityID"], "the only high-pop backup city")
        assertEquals(
            listOf(RecordingRng.Draw("choice", 1), RecordingRng.Draw("choice", 1)),
            rng.draws,
            "troop choice FIRST then city choice SECOND (array-literal L->R)",
        )
    }

    // ==================================================================================================
    // do유저장후방발령 (PHP :652-756) — general choice FIRST (→minRecruitPop) + choiceUsingWeight city SECOND.
    // ==================================================================================================

    @Test
    fun `do유저장후방발령 picks general then weighted recruitable city and emits che_발령`() {
        // d전쟁; userWarGeneral 5 in low-pop supply 110 (no troop, crew below minWarCrew, recruit score>1).
        // backup city 100 with enough pop → recruitable.
        val cityRows = listOf(
            city(100, supply = 1, front = 0, pop = 90_000, popMax = 100_000),
            city(110, supply = 1, front = 0, pop = 10_000, popMax = 100_000),
            city(130, supply = 1, front = 1, pop = 50_000, popMax = 100_000),
        )
        val g5 = general(5, cityId = 110, crew = 0, leadership = 50)
        val generals = listOf(gv(g5, recentWarSeconds = 0L, fullLeadership = 50.0))
        val st = instance(
            dipState = AiInstanceState.D_WAR,
            diplomacy = listOf(AiDiplomacyRow(you = 2, state = 0, term = 0)),
            frontMax = 1,
        )
        val rng = RecordingRng("user-rear")
        // minRecruitPop = 50*100 + 30000 = 35000; backup 100 pop 90000 >= → recruitable.
        val ctx = ctxOf(
            rng, cityRows, generals, st, dipState = AiInstanceState.D_WAR,
            selfCityId = 999, // chief NOT in a recruitable city
        )

        val body = NationDeployFamily.bodies(ctx).getValue("유저장후방발령")
        val chosen = body(null)!!

        assertEquals("che_발령", chosen.actionCode)
        assertEquals(5, chosen.args["destGeneralID"])
        assertEquals(100, chosen.args["destCityID"], "the only recruitable backup city")
        assertEquals(
            listOf(RecordingRng.Draw("choice", 1), RecordingRng.Draw("nextFloat1", 0)),
            rng.draws,
            "general choice FIRST then choiceUsingWeight (one nextFloat1) SECOND",
        )
        // sanity: the minRecruitPop formula uses minAvailableRecruitPop=30000.
        assertEquals(30000, GameConst.minAvailableRecruitPop)
    }

    // ==================================================================================================
    // Early-guard null with ZERO draws (no capital).
    // ==================================================================================================

    @Test
    fun `no-capital early guard returns null with zero draws`() {
        val cityRows = listOf(city(100, supply = 1, front = 1))
        val st = instance(nationCapital = 0)
        val rng = RecordingRng("no-cap")
        val ctx = ctxOf(rng, cityRows, emptyList(), st)

        for (name in listOf("부대전방발령", "부대후방발령", "부대구출발령", "유저장후방발령",
                "유저장구출발령", "유저장전방발령", "유저장내정발령", "NPC후방발령", "NPC구출발령",
                "NPC전방발령", "NPC내정발령")) {
            val body = NationDeployFamily.bodies(ctx).getValue(name)
            assertNull(body(null), "$name returns null when nation.capital == 0")
        }
        assertTrue(rng.draws.isEmpty(), "no capital → ZERO draws across every body")
    }

    // ==================================================================================================
    // ASSEMBLE — the deploy bodies register into the factory's nationDispatch by action-name and fire
    // through GeneralAI.chooseNationTurn (the by-name keys align with AutorunNationPolicy.priority).
    // ==================================================================================================

    @Test
    fun `deploy bodies wire into the factory nationDispatch and fire through chooseNationTurn`() {
        val cityRows = listOf(city(100, supply = 1, front = 0), city(110, supply = 1, front = 1))
        val g5 = general(5, cityId = 100, crew = 2000, train = 95.0, atmos = 95.0, leadership = 50)
        val st = instance(
            dipState = AiInstanceState.D_WAR,
            diplomacy = listOf(AiDiplomacyRow(you = 2, state = 0, term = 0)),
            frontMax = 1,
        )
        val rng = RandUtil(LiteHashDrbg("assemble"))
        val ctx = ctxOf(rng, cityRows, listOf(gv(g5, recentWarSeconds = 0L)), st, dipState = AiInstanceState.D_WAR)

        // The nation policy priority is restricted to 유저장전방발령 so it is the first dispatchable name.
        val nationPolicy = AutorunNationPolicy(
            npcType = 2, tech = 0, develcost = 10,
            nationPolicy = mapOf("priority" to listOf("유저장전방발령")),
        )
        val bodies = GeneralAiDoBodies(nationDispatch = NationDeployFamily.bodies(ctx))
        val ai = GeneralAiFactory.build(
            generalPolicy = AutorunGeneralPolicy(npcType = 2, nationId = 1),
            nationPolicy = nationPolicy,
            bodies = bodies,
        )

        val chosen = ai.chooseNationTurn(
            reservedCommand = ChosenCommand("휴식", emptyMap()),
            lastTurn = null,
            input = NationAiInput(npcType = 2, officerLevel = 5, month = 1, rng = rng),
        )

        assertEquals("che_발령", chosen.actionCode, "the wired do유저장전방발령 body dispatched through the nation loop")
        assertEquals("do유저장전방발령", chosen.reason, "the loop tags reason 'do'+actionName")
        assertEquals(5, chosen.args["destGeneralID"])
        assertEquals(110, chosen.args["destCityID"])
    }
}
