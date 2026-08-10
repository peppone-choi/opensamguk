package opensamguk.logic.ai.families

import opensamguk.common.rng.LiteHashDrbg
import opensamguk.common.rng.RandUtil
import opensamguk.common.constants.GameUnitConst
import opensamguk.logic.actions.military.UnitSetTable
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
import opensamguk.logic.util.phpToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * L-REWARD — the WORLD-DRIVEN reward `do<한글>` bodies ([NationRewardFamily.bodies]).
 *
 * GRAND TRUTH = PHP `legacy/devsam-core/hwe/sammo/GeneralAI.php:1224-1762` (read in full):
 *  - `do유저장긴급포상` (`:1224-1310`), `do유저장포상` (`:1312-1417`),
 *  - `doNPC긴급포상` (`:1419-1511`), `doNPC포상` (`:1513-1632`), `doNPC몰수` (`:1634-1762`).
 *
 * Each test builds [NationRewardFamily.bodies] over a DETERMINISTIC fixture [AiWorldView]/[AiInstanceState]/
 * policy and asserts, for a representative scenario per method, the chosen `(actionCode, RAW args)` + the
 * ordered draw stream off a recording [RandUtil]. The candidate-set ORDER (PK-ascending bucket + STABLE
 * value-`usort`) and the `count - idx` / `takeAmount` weight are the draw-for-draw parity target; the body
 * calls the PURE [NationRewardFamily.pickReward] primitive (ONE `choiceUsingWeightPair`) then gates via
 * `candidateAllowed`. The two 긴급 methods set `reqUpdateInstance=true` (markDirty) AFTER a passed gate.
 */
class NationRewardBodiesTest {

    /** A draw-recording RNG over a REAL [LiteHashDrbg] — records the ordered (method,n) draw stream. */
    private class RecordingRng(seed: String) : RandUtil(LiteHashDrbg(seed)) {
        data class Draw(val kind: String, val n: Int)
        val draws = ArrayList<Draw>()
        override fun nextFloat1(): Double {
            draws.add(Draw("choiceUsingWeightPair", 0)); return super.nextFloat1()
        }
    }

    // --- fixture builders -----------------------------------------------------------------------------

    private fun city(id: Int, nation: Int = 1, supply: Int = 1, front: Int = 0): City = City(
        id = id, nationId = nation, level = 5,
        commerce = 0, commerceMax = 1, agriculture = 100, agricultureMax = 100,
        supplyState = supply, frontState = front, trust = 0.0,
        security = 0, securityMax = 1, defense = 0, defenseMax = 1, wall = 0, wallMax = 1,
        population = 100_000, populationMax = 100_000,
    )

    private fun general(
        no: Int,
        nation: Int = 1,
        cityId: Int = 100,
        officerLevel: Int = 1,
        npcType: Int = 0,
        gold: Int = 0,
        rice: Int = 0,
        crew: Int = 0,
        leadership: Int = 50,
        killturn: Int = 1000,
        crewTypeId: Int = GameUnitConst.DEFAULT_CREWTYPE,
    ): General = General(
        id = no, nationId = nation, cityId = cityId,
        leadership = leadership, strength = 50, intel = 50, injury = 0,
        experience = 0.0, dedication = 0.0,
        officerLevel = officerLevel, gold = gold, rice = rice,
        crew = crew, train = 0.0, atmos = 0.0, troop = 0, npcType = npcType,
        crewTypeId = crewTypeId,
        meta = linkedMapOf("killturn" to killturn, "belong" to 1),
    )

    private fun gv(g: General, recentWarSeconds: Long? = null): AiGeneralView =
        AiGeneralView(g, recentWarSeconds, null, g.leadership.toDouble())

    /** A re-run counter so a test can prove `markDirty()` was called (updateInstance re-derives the row). */
    private val nationLookups = intArrayOf(0)

    /** A full-resource nation so the threshold/`resVal` gates pass; tech=0 (techCost 1.0). */
    private fun instance(
        gold: Int = 9_999_999,
        rice: Int = 9_999_999,
        nationPolicy: AutorunNationPolicy,
    ): AiInstanceState {
        nationLookups[0] = 0
        val env = AiEnv(year = 200, month = 1, startYear = 180, develCost = 10)
        val st = AiInstanceState(
            generalNationId = 1,
            env = env,
            nationPolicy = nationPolicy,
            nationRowLookup = {
                nationLookups[0]++
                AiNationRow(nation = 1, level = 5, capital = 100, gold = gold, rice = rice)
            },
            nationStor = emptyMap(),
            diplomacyOf = { emptyList<AiDiplomacyRow>() },
            frontMaxOf = { 0 },
            kvRecorder = object : AiKvRecorder { override fun recordNationKv(nationId: Int, key: String, value: Any?) {} },
        )
        st.updateInstance()
        return st
    }

    private fun ctxOf(
        rng: RandUtil,
        cityRows: List<City>,
        generals: List<AiGeneralView>,
        instance: AiInstanceState,
        nationPolicy: AutorunNationPolicy,
        candidateAllowed: (String, Map<String, Any?>) -> Boolean = { _, _ -> true },
        dipState: Int = AiInstanceState.D_PEACE,
    ): GeneralAiContext = GeneralAiContext(
        rng = rng,
        instance = instance,
        world = AiWorldView(
            ownNationId = 1,
            cityRows = cityRows,
            warTargetNation = mapOf(0 to 1),
            ownGeneralId = 1,
            generals = generals,
            dipState = dipState,
            minWarCrew = nationPolicy.minWarCrew,
            minNpcWarLeadership = nationPolicy.minNPCWarLeadership,
            turnTerm = 120,
        ),
        generalPolicy = AutorunGeneralPolicy(npcType = 2, nationId = 1),
        nationPolicy = nationPolicy,
        env = AiEnv(year = 200, month = 1, startYear = 180, develCost = 10),
        turnTerm = 120,
        selfGeneralId = 1,
        selfCityId = 100,
        candidateAllowed = candidateAllowed,
        // costWithTech(tech=0, toInt(leadership(false))) using the general's crew type (default 보병 1100).
        unitCostWithTechOf = { it.unitCostWithTech(nationTech = 0) },
    )

    /** The PHP `crewtype.costWithTech(nation.tech, toInt(leadership(false)))` value for a fixture general. */
    private fun AiGeneralView.unitCostWithTech(nationTech: Int): Double {
        val crew = UnitSetTable.byId(general.crewTypeId)!!
        return crew.costWithTech(nationTech, phpToInt(fullLeadership))
    }

    // ==================================================================================================
    // do유저장포상 (PHP :1312-1417) — value-ASC usort; weight count-idx; ONE choiceUsingWeightPair; che_포상.
    // ==================================================================================================

    @Test
    fun `do유저장포상 sorts user generals value-ASC and emits che_포상 via one weighted-pair draw`() {
        // Two user generals (npc<2), both LOW gold (below reqHumanWarRecommandGold so no break), killturn>5,
        // NOT war generals (so enoughMoney = reqHumanDevelGold * 1.2). nation gold huge so resVal>=reqNationGold.
        val cityRows = listOf(city(100), city(110))
        val g5 = general(5, cityId = 100, gold = 0, rice = 0)   // userCivil
        val g6 = general(6, cityId = 110, gold = 0, rice = 0)   // userCivil
        val generals = listOf(gv(g5), gv(g6))
        // develGold/Rice small so payAmount is bounded; recommand high so no early break; nation policy default.
        val policy = AutorunNationPolicy(
            npcType = 2, tech = 0, develcost = 10,
            nationPolicy = mapOf(
                "values" to mapOf(
                    "reqNationGold" to 1000, "reqNationRice" to 1000,
                    "reqHumanDevelGold" to 5000, "reqHumanDevelRice" to 5000,
                    "reqHumanWarRecommandGold" to 100000, "reqHumanWarRecommandRice" to 100000,
                    "minimumResourceActionAmount" to 100, "maximumResourceActionAmount" to 100000,
                ),
            ),
        )
        val st = instance(nationPolicy = policy)
        val rng = RecordingRng("user-posang")
        val ctx = ctxOf(rng, cityRows, generals, st, policy)

        val chosen = NationRewardFamily.bodies(ctx).getValue("유저장포상")(null)!!

        assertEquals("che_포상", chosen.actionCode)
        assertEquals(listOf("destGeneralID", "isGold", "amount"), chosen.args.keys.toList())
        assertTrue(chosen.args["destGeneralID"] in listOf(5, 6))
        // gold candidates appended FIRST (gold then rice in remainResource order); both gens qualify each res.
        // Exactly ONE choiceUsingWeightPair draw over the whole candidate list.
        assertEquals(
            listOf(RecordingRng.Draw("choiceUsingWeightPair", 0)),
            rng.draws,
            "ONE choiceUsingWeightPair (one nextFloat1) over the count-idx weighted candidate list",
        )
    }

    // ==================================================================================================
    // do유저장긴급포상 (PHP :1224-1310) — userWarGenerals; weight count-idx; reqUpdateInstance AFTER gate.
    // ==================================================================================================

    @Test
    fun `do유저장긴급포상 emits che_포상 and marks the instance dirty after a passed gate`() {
        // One userWarGeneral (recentWar 0 → userWar bucket), low gold so urgent-pay candidate; killturn>5.
        val cityRows = listOf(city(100))
        val g5 = general(5, cityId = 100, gold = 0, rice = 0, crew = 0, leadership = 50)
        val generals = listOf(gv(g5, recentWarSeconds = 0L))
        val policy = AutorunNationPolicy(
            npcType = 2, tech = 0, develcost = 10,
            nationPolicy = mapOf(
                "values" to mapOf(
                    "reqHumanWarUrgentGold" to 50000, "reqHumanWarUrgentRice" to 50000,
                    "minimumResourceActionAmount" to 100, "maximumResourceActionAmount" to 100000,
                ),
            ),
        )
        val st = instance(nationPolicy = policy)
        val rng = RecordingRng("user-urgent")
        val ctx = ctxOf(
            rng, cityRows, generals, st, policy,
            candidateAllowed = { code, _ -> code == "che_포상" },
            dipState = AiInstanceState.D_PEACE,
        )

        val lookupsBeforeBody = nationLookups[0] // 1 (from the instance() construction updateInstance).
        val chosen = NationRewardFamily.bodies(ctx).getValue("유저장긴급포상")(null)!!
        // reqUpdateInstance was set AFTER the gate (PHP :1308) → a follow-up updateInstance() RE-derives the
        // nation row (the dirty re-run). A non-dirty instance short-circuits and never re-looks-up.
        st.updateInstance()
        val dirtyReran = nationLookups[0] > lookupsBeforeBody

        assertEquals("che_포상", chosen.actionCode)
        assertEquals(5, chosen.args["destGeneralID"], "the only userWar general")
        assertEquals(true, chosen.args["isGold"] is Boolean)
        assertEquals(
            listOf(RecordingRng.Draw("choiceUsingWeightPair", 0)),
            rng.draws,
            "긴급포상 makes exactly ONE choiceUsingWeightPair draw",
        )
        assertTrue(dirtyReran, "do유저장긴급포상 set reqUpdateInstance=true AFTER the gate (PHP :1308) → updateInstance re-ran")
    }

    // ==================================================================================================
    // doNPC몰수 (PHP :1634-1762) — value-DESC usort; weight = takeAmount; ONE choiceUsingWeightPair; che_몰수.
    // ==================================================================================================

    @Test
    fun `doNPC몰수 takes from rich civil NPC generals value-DESC and emits che_몰수`() {
        // One npcCivilGeneral (npc>=2, killturn>5, low leadership → civil bucket) RICH in gold so 몰수 candidate.
        val cityRows = listOf(city(100))
        // npc 2, leadership below minNPCWarLeadership (40) → npcCivil; gold high (> reqNPCDevelGold*1.5).
        val rich = general(7, cityId = 100, npcType = 2, gold = 1_000_000, rice = 0, leadership = 10)
        val generals = listOf(gv(rich))
        val policy = AutorunNationPolicy(
            npcType = 2, tech = 0, develcost = 10,
            nationPolicy = mapOf(
                "values" to mapOf(
                    "reqNationGold" to 1000, "reqNationRice" to 1000,
                    "reqNPCWarGold" to 1000, "reqNPCWarRice" to 1000,
                    "reqNPCDevelGold" to 1000, "reqNPCDevelRice" to 1000,
                    "minNPCWarLeadership" to 40,
                    "minimumResourceActionAmount" to 100, "maximumResourceActionAmount" to 100000,
                ),
            ),
        )
        // nation gold low so the war loop's reqResValDelta is non-negative and the civil 몰수 fires.
        val st = instance(gold = 100, rice = 100, nationPolicy = policy)
        val rng = RecordingRng("npc-molsu")
        val ctx = ctxOf(rng, cityRows, generals, st, policy)

        val chosen = NationRewardFamily.bodies(ctx).getValue("NPC몰수")(null)!!

        assertEquals("che_몰수", chosen.actionCode)
        assertEquals(listOf("destGeneralID", "isGold", "amount"), chosen.args.keys.toList())
        assertEquals(7, chosen.args["destGeneralID"], "the only rich civil NPC")
        assertEquals(true, chosen.args["isGold"], "gold is taken first (remainResource gold-then-rice)")
        assertEquals(
            listOf(RecordingRng.Draw("choiceUsingWeightPair", 0)),
            rng.draws,
            "몰수 makes exactly ONE choiceUsingWeightPair draw (weight = takeAmount)",
        )
    }

    // ==================================================================================================
    // Empty-candidate / no-general early guards → null with ZERO draws.
    // ==================================================================================================

    @Test
    fun `reward bodies return null with zero draws when no candidate qualifies`() {
        val cityRows = listOf(city(100))
        val policy = AutorunNationPolicy(npcType = 2, tech = 0, develcost = 10)
        val st = instance(nationPolicy = policy)
        val rng = RecordingRng("empty")
        // No generals at all → every reward body short-circuits before the draw.
        val ctx = ctxOf(rng, cityRows, emptyList(), st, policy)

        for (name in listOf("유저장긴급포상", "유저장포상", "NPC긴급포상", "NPC포상", "NPC몰수")) {
            assertNull(NationRewardFamily.bodies(ctx).getValue(name)(null), "$name → null with no generals")
        }
        assertTrue(rng.draws.isEmpty(), "no candidate → ZERO draws across every reward body")
    }

    @Test
    fun `gate deny makes the body return null even after the draw`() {
        val cityRows = listOf(city(100))
        val g5 = general(5, cityId = 100, gold = 0, rice = 0)
        val generals = listOf(gv(g5))
        val policy = AutorunNationPolicy(
            npcType = 2, tech = 0, develcost = 10,
            nationPolicy = mapOf(
                "values" to mapOf(
                    "reqNationGold" to 1000, "reqNationRice" to 1000,
                    "reqHumanDevelGold" to 5000, "reqHumanDevelRice" to 5000,
                    "reqHumanWarRecommandGold" to 100000, "reqHumanWarRecommandRice" to 100000,
                    "minimumResourceActionAmount" to 100, "maximumResourceActionAmount" to 100000,
                ),
            ),
        )
        val st = instance(nationPolicy = policy)
        val rng = RecordingRng("deny")
        val ctx = ctxOf(rng, cityRows, generals, st, policy, candidateAllowed = { _, _ -> false })

        assertNull(
            NationRewardFamily.bodies(ctx).getValue("유저장포상")(null),
            "the gate denies → null AFTER the choiceUsingWeightPair draw (PHP hasFullConditionMet false)",
        )
        assertEquals(
            listOf(RecordingRng.Draw("choiceUsingWeightPair", 0)),
            rng.draws,
            "the draw STILL happens before the gate deny (PHP draws inside buildNationCommandClass arg)",
        )
    }

    // ==================================================================================================
    // ASSEMBLE — the reward bodies register into the factory nationDispatch by action-name and fire
    // through GeneralAI.chooseNationTurn.
    // ==================================================================================================

    @Test
    fun `reward bodies wire into the factory nationDispatch and fire through chooseNationTurn`() {
        val cityRows = listOf(city(100))
        val g5 = general(5, cityId = 100, gold = 0, rice = 0)
        val generals = listOf(gv(g5))
        val policy = AutorunNationPolicy(
            npcType = 2, tech = 0, develcost = 10,
            nationPolicy = mapOf(
                "priority" to listOf("유저장포상"),
                "values" to mapOf(
                    "reqNationGold" to 1000, "reqNationRice" to 1000,
                    "reqHumanDevelGold" to 5000, "reqHumanDevelRice" to 5000,
                    "reqHumanWarRecommandGold" to 100000, "reqHumanWarRecommandRice" to 100000,
                    "minimumResourceActionAmount" to 100, "maximumResourceActionAmount" to 100000,
                ),
            ),
        )
        val st = instance(nationPolicy = policy)
        val rng = RandUtil(LiteHashDrbg("assemble"))
        val ctx = ctxOf(rng, cityRows, generals, st, policy)

        val bodies = GeneralAiDoBodies(nationDispatch = NationRewardFamily.bodies(ctx))
        val ai = GeneralAiFactory.build(
            generalPolicy = AutorunGeneralPolicy(npcType = 2, nationId = 1),
            nationPolicy = policy,
            bodies = bodies,
        )

        val chosen = ai.chooseNationTurn(
            reservedCommand = ChosenCommand("휴식", emptyMap()),
            lastTurn = null,
            input = NationAiInput(npcType = 2, officerLevel = 5, month = 1, rng = rng),
        )

        assertEquals("che_포상", chosen.actionCode, "the wired do유저장포상 body dispatched through the nation loop")
        assertEquals("do유저장포상", chosen.reason, "the loop tags reason 'do'+actionName")
        assertEquals(5, chosen.args["destGeneralID"])
    }
}
