package opensamguk.logic.ai.families

import opensamguk.common.rng.LiteHashDrbg
import opensamguk.common.rng.RandUtil
import opensamguk.logic.ai.AiDiplomacyRow
import opensamguk.logic.ai.AiEnv
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
import opensamguk.logic.ai.KvDelta
import opensamguk.logic.ai.NationCity
import opensamguk.logic.domain.City
import opensamguk.logic.domain.General
import opensamguk.logic.stats.GeneralActionPipeline
import opensamguk.logic.stats.StatCalc
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * L-GENWAR — the WORLD-DRIVEN GenWarMoveFamily `do<한글>` bodies ([GenWarMoveFamily.bodies] + the two
 * pre-loop branch builders [GenWarMoveFamily.do집합]/[GenWarMoveFamily.do방랑군이동]).
 *
 * GRAND TRUTH = PHP `legacy/devsam-core/hwe/sammo/GeneralAI.php` (read in full):
 *  - `do전투준비`   (`:2653-2682`): dipState∉{평화,선포} gate → train/atmos cmdList (각 hasFullConditionMet)
 *    → terminal `choiceUsingWeightPair($cmdList)` (`:2681`, empty → null NO draw).
 *  - `do소집해제`   (`:2684-2703`): 3 non-RNG early-returns (attackable/dipState!=평화/crew==0) → `nextBool(0.75)`
 *    ALWAYS (`:2695`) → che_소집해제 + gate.
 *  - `do출병`       (`:2706-2775`): attackable/d전쟁 gates → the `&&` `nextBool(0.7)` (`:2720`) drawn BEFORE the
 *    four non-RNG early-returns (`:2729/2732/2735/2739`) → `choice($attackableCities)` (`:2769`) + gate.
 *  - `doNPC헌납`    (`:2785-2863`): per-resource rice-then-gold `nextBool((genRes/reqRes)-0.5)` (`:2841`,
 *    `&&` reqRes>0) → terminal `choiceUsingWeightPair($args)` (`:2858`, empty → null NO draw) + gate.
 *  - `do후방워프`   (`:2866-2970`): dipState/can징병/통솔장/crew/pop gates → backup-then-supply recruitableCityList
 *    → `choiceUsingWeight($recruitableCityList)` (`:2960`) + gate, optionText='순간이동'.
 *  - `do전방워프`   (`:2972-3020`): attackable/dipState/통솔장/crew/front gates → frontCities supplied candidate
 *    → `choiceUsingWeight($candidateCities)` (`:3011`) + gate.
 *  - `do내정워프`   (`:3022-3092`): 통솔장&war early-return → `nextBool(0.6)` ALWAYS (`:3029`) → warpProp product
 *    → `nextBool($warpProp)` (`:3050`) → supplyCities candidate → `choiceUsingWeight` (`:3085`) + gate.
 *  - `do귀환`       (`:3095-3109`): ZERO draws (supply/nation gate) → che_귀환 + gate.
 *  - `do집합`       (`:3111-3125`): GATE-EXEMPT; npc==5 draws `nextRangeInt(2,4)` (`:3116`) + killturn delta.
 *  - `do방랑군이동`  (`:3127-3215`): dupLord/level gate → movingTargetCityID aux → target `choiceUsingWeightPair`
 *    (`:3180`) → at-target che_인재탐색 ELSE next-hop `choiceUsingWeightPair` (`:3208`) che_이동 + gate.
 *
 * Each test builds the bodies over a DETERMINISTIC fixture world and asserts the chosen `(actionCode, RAW
 * args)` + the ordered draw stream off a recording [RandUtil]. The candidate-set ORDER (the cmdList append
 * order, the backup-then-supply recruitable order, the BFS visitation order) is the draw-for-draw target.
 */
class GenWarMoveBodiesTest {

    /**
     * A draw-recording RNG over a REAL [LiteHashDrbg]. `nextBool` records the prob; a top-level `nextFloat1`
     * is a `choiceUsingWeightPair`/`choice` draw; `nextRangeInt` records its own draw. A re-entrancy guard
     * stops the inner primitive a `nextBool`/`choice` consumes from being double-recorded.
     */
    private class RecordingRng(seed: String) : RandUtil(LiteHashDrbg(seed)) {
        data class Draw(val kind: String, val prob: Double)
        val draws = ArrayList<Draw>()
        var nextBoolCalls = 0
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
            if (!inNextBool) draws.add(Draw("choice", -1.0))
            return super.nextFloat1()
        }
        override fun nextBit(): Boolean {
            if (!inNextBool) draws.add(Draw("nextBit", 0.5))
            return super.nextBit()
        }
        override fun nextRangeInt(minInclusive: Int, maxInclusive: Int): Int {
            draws.add(Draw("nextRangeInt", -1.0))
            return super.nextRangeInt(minInclusive, maxInclusive)
        }
        override fun <T> choice(items: List<T>): T {
            // RandUtil.choice() draws via the underlying DRBG nextInt (NOT nextFloat1), so record it here.
            draws.add(Draw("choice", -1.0))
            return super.choice(items)
        }
    }

    // --- fixture builders -----------------------------------------------------------------------------

    private fun city(
        id: Int,
        nationId: Int = 1,
        level: Int = 5,
        supplyState: Int = 1,
        frontState: Int = 0,
        pop: Int = 100_000,
        popMax: Int = 100_000,
    ): City = City(
        id = id, nationId = nationId, level = level,
        commerce = 50, commerceMax = 100, agriculture = 50, agricultureMax = 100,
        supplyState = supplyState, frontState = frontState, trust = 100.0,
        security = 50, securityMax = 100, defense = 50, defenseMax = 100, wall = 50, wallMax = 100,
        population = pop, populationMax = popMax,
    )

    private fun instance(
        dipState: Int = AiInstanceState.D_WAR,
        attackable: Boolean = true,
        rice: Int = 99999,
        gold: Int = 99999,
        diplomacy: List<AiDiplomacyRow> = listOf(AiDiplomacyRow(you = 2, state = 0, term = 0)),
        frontMax: Int = 1,
        capital: Int = 100,
        genTypeSeed: String = "genType-fixture",
    ): AiInstanceState {
        val env = AiEnv(year = 200, month = 1, startYear = 180, develCost = 10)
        val nationPolicy = AutorunNationPolicy(npcType = 2, tech = 0, develcost = 10)
        val st = AiInstanceState(
            generalNationId = 1,
            env = env,
            nationPolicy = nationPolicy,
            nationRowLookup = { AiNationRow(nation = 1, level = 5, capital = capital, gold = gold, rice = rice) },
            nationStor = emptyMap(),
            diplomacyOf = { diplomacy },
            frontMaxOf = { frontMax },
            kvRecorder = object : AiKvRecorder { override fun recordNationKv(nationId: Int, key: String, value: Any?) {} },
        )
        st.updateInstance()
        val g = General(
            id = 1, nationId = 1, cityId = 100,
            leadership = 50, strength = 50, intel = 50, injury = 0,
            experience = 0.0, dedication = 0.0,
            officerLevel = 1, gold = 0, rice = 0,
        )
        val statCalc = StatCalc(g, GeneralActionPipeline())
        st.calcGenType(RandUtil(LiteHashDrbg(genTypeSeed)), statCalc)
        require(st.dipState == dipState) { "fixture dipState mismatch: wanted $dipState got ${st.dipState}" }
        require(st.attackable == attackable) { "fixture attackable mismatch: wanted $attackable got ${st.attackable}" }
        return st
    }

    private fun ctxOf(
        rng: RandUtil,
        instance: AiInstanceState,
        selfCity: City? = null,
        cityRows: List<City> = emptyList(),
        candidateAllowed: (String, Map<String, Any?>) -> Boolean = { _, _ -> true },
        recordGeneralKv: (Int, String, Any?) -> Unit = { _, _, _ -> },
        fullLeadership: Double = 50.0,
        selfCrew: Int = 0,
        selfTrain: Double = 100.0,
        selfAtmos: Double = 100.0,
        selfGold: Int = 0,
        selfRice: Int = 0,
        selfNpcType: Int = 2,
        selfKillturn: Int = 1000,
        attackableCitiesOf: (List<Int>, List<Int>) -> List<Int> = { _, _ -> emptyList() },
        cityDevelRateOf: (Int) -> List<Triple<String, Double, Int>> = { emptyList() },
        cityGeneralCountOf: (Int) -> Int = { 0 },
        wanderOccupiedCities: Set<Int> = emptySet(),
        movingTargetCityId: Int? = null,
        dupLordAtSelfCity: Int = 0,
        selfCityLevel: Int = 1,
        generalPolicy: AutorunGeneralPolicy = AutorunGeneralPolicy(npcType = 2, nationId = 1),
        nationPolicy: AutorunNationPolicy = AutorunNationPolicy(npcType = 2, tech = 0, develcost = 10),
    ): GeneralAiContext {
        val world = AiWorldView(
            ownNationId = 1,
            cityRows = if (cityRows.isEmpty() && selfCity != null) listOf(selfCity) else cityRows,
            warTargetNation = instance.warTargetNation,
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
            selfCityId = selfCity?.id ?: 100,
            candidateAllowed = candidateAllowed,
            recordGeneralKv = recordGeneralKv,
            fullLeadership = fullLeadership,
            selfCrew = selfCrew,
            selfCity = selfCity,
            selfTrain = selfTrain,
            selfAtmos = selfAtmos,
            selfGold = selfGold,
            selfRice = selfRice,
            selfNpcType = selfNpcType,
            selfKillturn = selfKillturn,
            attackableCitiesOf = attackableCitiesOf,
            cityDevelRateOf = cityDevelRateOf,
            cityGeneralCountOf = cityGeneralCountOf,
            wanderOccupiedCities = wanderOccupiedCities,
            movingTargetCityId = movingTargetCityId,
            dupLordAtSelfCity = dupLordAtSelfCity,
            selfCityLevel = selfCityLevel,
        )
    }

    // ==================================================================================================
    // do전투준비 (PHP :2653-2682)
    // ==================================================================================================

    @Test
    fun `do전투준비 builds the train+atmos cmdList and draws one terminal weighted pick`() {
        // d전쟁 (∉{평화,선포}); train<90 and atmos<90 → both candidates → one terminal choiceUsingWeightPair.
        val c = city(100)
        val st = instance()
        val rng = RecordingRng("battleprep")
        val ctx = ctxOf(rng, st, selfCity = c, selfTrain = 10.0, selfAtmos = 20.0)

        val chosen = GenWarMoveFamily.bodies(ctx).getValue("전투준비")(null)!!
        assertTrue(chosen.actionCode in listOf("che_훈련", "che_사기진작"))
        assertEquals(
            listOf(RecordingRng.Draw("choice", -1.0)),
            rng.draws,
            "exactly one terminal choiceUsingWeightPair (PHP :2681)",
        )
    }

    @Test
    fun `do전투준비 high train+atmos yields empty cmdList and null with ZERO draws`() {
        val c = city(100)
        val st = instance()
        val rng = RecordingRng("battleprep-empty")
        val ctx = ctxOf(rng, st, selfCity = c, selfTrain = 100.0, selfAtmos = 100.0)

        assertNull(GenWarMoveFamily.bodies(ctx).getValue("전투준비")(null), "empty cmdList → :2678 null")
        assertTrue(rng.draws.isEmpty(), "no terminal draw when cmdList empty")
    }

    @Test
    fun `do전투준비 at d평화 returns null with no draws`() {
        val c = city(100)
        val st = instance(dipState = AiInstanceState.D_PEACE, attackable = false, diplomacy = emptyList(), frontMax = 0)
        val rng = RecordingRng("battleprep-peace")
        val ctx = ctxOf(rng, st, selfCity = c, selfTrain = 10.0, selfAtmos = 10.0)

        assertNull(GenWarMoveFamily.bodies(ctx).getValue("전투준비")(null), "d평화 → :2655 null")
        assertTrue(rng.draws.isEmpty())
    }

    // ==================================================================================================
    // do소집해제 (PHP :2684-2703) — nextBool(0.75) ALWAYS after the 3 non-RNG early-returns.
    // ==================================================================================================

    @Test
    fun `do소집해제 draws the always-0_75 skip and emits che_소집해제 on the non-skip seed`() {
        // attackable=false, d평화, crew>0 → reach :2695. Pick a seed where nextBool(0.75) is FALSE.
        val st = instance(dipState = AiInstanceState.D_PEACE, attackable = false, diplomacy = emptyList(), frontMax = 0)
        var chosen: ChosenCommand? = null
        var firstProb = -1.0
        for (seed in 0..200) {
            val rng = RecordingRng("disband-$seed")
            val c = city(100)
            val ctx = ctxOf(rng, st, selfCity = c, selfCrew = 500)
            val out = GenWarMoveFamily.bodies(ctx).getValue("소집해제")(null)
            firstProb = rng.draws.first().prob
            if (out != null) { chosen = out; break }
        }
        assertEquals(0.75, firstProb, "the FIRST draw is the ALWAYS-drawn nextBool(0.75) (:2695)")
        assertEquals("che_소집해제", chosen!!.actionCode)
    }

    @Test
    fun `do소집해제 attackable returns null with no draws`() {
        val st = instance() // attackable=true
        val rng = RecordingRng("disband-attackable")
        val c = city(100)
        val ctx = ctxOf(rng, st, selfCity = c, selfCrew = 500)
        assertNull(GenWarMoveFamily.bodies(ctx).getValue("소집해제")(null), "attackable → :2686 null")
        assertTrue(rng.draws.isEmpty(), "the 3 early-returns precede the 0.75 draw")
    }

    @Test
    fun `do소집해제 zero crew returns null with no draws`() {
        val st = instance(dipState = AiInstanceState.D_PEACE, attackable = false, diplomacy = emptyList(), frontMax = 0)
        val rng = RecordingRng("disband-nocrew")
        val c = city(100)
        val ctx = ctxOf(rng, st, selfCity = c, selfCrew = 0)
        assertNull(GenWarMoveFamily.bodies(ctx).getValue("소집해제")(null), "crew==0 → :2692 null")
        assertTrue(rng.draws.isEmpty())
    }

    // ==================================================================================================
    // do출병 (PHP :2706-2775) — the :2720 `&&` 0.7 draw BEFORE the four non-RNG early-returns (m1).
    // ==================================================================================================

    @Test
    fun `do출병 low-rice npc2 draws the 0_7 BEFORE the train early-return`() {
        // low rice (<2000) AND npc>=2 → the :2720 nextBool(0.7) is reached. train=0 < 90 → the :2729 early-return
        // fires AFTER the draw. The parity target is that the 0.7 draw happened despite the train gate failing.
        val c = city(100, frontState = 3)
        val st = instance(rice = 100)
        val rng = RecordingRng("sortie-lowrice")
        val ctx = ctxOf(
            rng, st, selfCity = c, selfTrain = 0.0, selfAtmos = 100.0, selfCrew = 99999,
            selfNpcType = 2,
        )
        GenWarMoveFamily.bodies(ctx).getValue("출병")(null)
        assertEquals(RecordingRng.Draw("nextBool", 0.7), rng.draws.first(),
            "the :2720 nextBool(0.7) draws BEFORE the :2729 train early-return (m1)")
    }

    @Test
    fun `do출병 picks an attackable city and emits che_출병 with destCityID`() {
        val c = city(100, frontState = 3)
        val st = instance(rice = 99999) // rice>=baserice → the :2720 0.7 is SUPPRESSED (`&&` left false).
        val rng = RecordingRng("sortie")
        val ctx = ctxOf(
            rng, st, selfCity = c, selfTrain = 100.0, selfAtmos = 100.0, selfCrew = 99999,
            attackableCitiesOf = { _, _ -> listOf(201, 202, 203) },
        )
        val chosen = GenWarMoveFamily.bodies(ctx).getValue("출병")(null)!!
        assertEquals("che_출병", chosen.actionCode)
        assertTrue(chosen.args["destCityID"] in listOf(201, 202, 203))
        // rice>=baserice → no 0.7; only the choice over attackableCities.
        assertEquals(listOf(RecordingRng.Draw("choice", -1.0)), rng.draws,
            "rice>=baserice suppresses 0.7; one choice over attackableCities (:2769)")
    }

    @Test
    fun `do출병 front 0 returns null after the suppressed 0_7`() {
        val c = city(100, frontState = 0) // front==0 → :2739 early-return.
        val st = instance(rice = 99999)
        val rng = RecordingRng("sortie-front0")
        val ctx = ctxOf(rng, st, selfCity = c, selfTrain = 100.0, selfAtmos = 100.0, selfCrew = 99999)
        assertNull(GenWarMoveFamily.bodies(ctx).getValue("출병")(null), "front==0 → :2739 null")
        assertTrue(rng.draws.isEmpty(), "rice>=baserice suppresses 0.7; front gate is non-RNG")
    }

    // ==================================================================================================
    // doNPC헌납 (PHP :2785-2863) — per-resource rice-then-gold 0.5-offset gate + terminal weighted pick.
    // ==================================================================================================

    @Test
    fun `doNPC헌납 통솔장 rice-then-gold draws per resource then a terminal weighted pick`() {
        // genType 통솔장 (leadership 50>=40) → reqRes = reqNPCWar*. Both rice and gold qualify (genRes large).
        val st = instance(rice = 100, gold = 100) // nation rice<reqNationRice AND gold<reqNationGold.
        val rng = RecordingRng("tribute")
        val policy = AutorunNationPolicy(npcType = 2, tech = 0, develcost = 10)
        val ctx = ctxOf(
            rng, st, selfCity = city(100),
            selfRice = 5_000_000, selfGold = 5_000_000,
            nationPolicy = policy,
        )
        val chosen = GenWarMoveFamily.bodies(ctx).getValue("NPC헌납")(null)
        // Two per-resource nextBool gates (rice then gold) THEN one terminal choiceUsingWeightPair.
        val bools = rng.draws.count { it.kind == "nextBool" }
        assertTrue(bools in 1..2, "per-resource gates (rice then gold), reqRes>0 each → 1-2 nextBool")
        if (chosen != null) {
            assertEquals("che_헌납", chosen.actionCode)
            assertEquals("choice", rng.draws.last().kind, "the terminal choiceUsingWeightPair is LAST (:2858)")
        }
    }

    @Test
    fun `doNPC헌납 nothing qualifies returns null with no terminal draw`() {
        val st = instance(rice = 99999, gold = 99999) // nation rich → both resources `continue` at :2822.
        val rng = RecordingRng("tribute-none")
        val ctx = ctxOf(rng, st, selfCity = city(100), selfRice = 100, selfGold = 100)
        assertNull(GenWarMoveFamily.bodies(ctx).getValue("NPC헌납")(null), "empty args → :2854 null")
        assertTrue(rng.draws.none { it.kind == "choice" }, "no terminal weighted pick when args empty")
    }

    // ==================================================================================================
    // do후방워프 (PHP :2866-2970) — backup-then-supply recruitableCityList → choiceUsingWeight.
    // ==================================================================================================

    @Test
    fun `do후방워프 picks a backup city and emits che_NPC능동 순간이동`() {
        // 통솔장, crew < minWarCrew, self city low pop (needs recruit), a backup city with safe pop qualifies.
        val self = city(100, pop = 10_000, popMax = 100_000) // ratio 0.1 < 0.5 → not "pop 충분".
        val backup = city(201, supplyState = 1, frontState = 0, pop = 100_000, popMax = 100_000)
        val st = instance()
        val rng = RecordingRng("backwarp")
        val ctx = ctxOf(
            rng, st, selfCity = self, cityRows = listOf(self, backup), selfCrew = 0,
        )
        val chosen = GenWarMoveFamily.bodies(ctx).getValue("후방워프")(null)!!
        assertEquals("che_NPC능동", chosen.actionCode)
        assertEquals("순간이동", chosen.args["optionText"])
        assertEquals(201, chosen.args["destCityID"])
        assertEquals(listOf(RecordingRng.Draw("choice", -1.0)), rng.draws,
            "one choiceUsingWeight over the recruitableCityList (:2960)")
    }

    @Test
    fun `do후방워프 at d평화 returns null with no draws`() {
        val self = city(100, pop = 10_000)
        val st = instance(dipState = AiInstanceState.D_PEACE, attackable = false, diplomacy = emptyList(), frontMax = 0)
        val rng = RecordingRng("backwarp-peace")
        val ctx = ctxOf(rng, st, selfCity = self, cityRows = listOf(self), selfCrew = 0)
        assertNull(GenWarMoveFamily.bodies(ctx).getValue("후방워프")(null), "d평화 → :2872 null")
        assertTrue(rng.draws.isEmpty())
    }

    // ==================================================================================================
    // do전방워프 (PHP :2972-3020) — supplied frontCities candidate → choiceUsingWeight.
    // ==================================================================================================

    @Test
    fun `do전방워프 picks a supplied front city and emits che_NPC능동 순간이동`() {
        // attackable, 통솔장, crew >= minWarCrew, self city front==0; a supplied front city exists.
        val self = city(100, frontState = 0)
        val front = city(202, supplyState = 1, frontState = 1)
        val st = instance()
        val rng = RecordingRng("frontwarp")
        val ctx = ctxOf(rng, st, selfCity = self, cityRows = listOf(self, front), selfCrew = 99999)
        val chosen = GenWarMoveFamily.bodies(ctx).getValue("전방워프")(null)!!
        assertEquals("che_NPC능동", chosen.actionCode)
        assertEquals("순간이동", chosen.args["optionText"])
        assertEquals(202, chosen.args["destCityID"])
        assertEquals(listOf(RecordingRng.Draw("choice", -1.0)), rng.draws,
            "one choiceUsingWeight over the supplied frontCities (:3011)")
    }

    @Test
    fun `do전방워프 self city on front returns null with no draws`() {
        val self = city(100, frontState = 1) // on front → :2989 null.
        val st = instance()
        val rng = RecordingRng("frontwarp-onfront")
        val ctx = ctxOf(rng, st, selfCity = self, cityRows = listOf(self), selfCrew = 99999)
        assertNull(GenWarMoveFamily.bodies(ctx).getValue("전방워프")(null), "self front → :2989 null")
        assertTrue(rng.draws.isEmpty())
    }

    // ==================================================================================================
    // do내정워프 (PHP :3022-3092) — nextBool(0.6) ALWAYS → nextBool(warpProp) → choiceUsingWeight.
    // ==================================================================================================

    @Test
    fun `do내정워프 draws the 0_6 first then proceed-gate then dest pick`() {
        // 통솔장 (leadership 50>=40) but dipState∈{평화} → NOT in {징병,직전,전쟁} → the :3024 early-return is
        // skipped → reach the ALWAYS-drawn nextBool(0.6) at :3029.
        val self = city(100)
        val supply = city(201, supplyState = 1, frontState = 0)
        val st = instance(
            dipState = AiInstanceState.D_PEACE, attackable = false,
            diplomacy = emptyList(), frontMax = 0,
        )
        // 무장 develType for the warpProp; supply city has a low realDevelRate so it qualifies.
        val develRate = listOf(
            Triple("def", 0.5, AiInstanceState.T_MUJANG),
        )
        val rng = RecordingRng("internalwarp")
        val ctx = ctxOf(
            rng, st, selfCity = self, cityRows = listOf(self, supply),
            cityDevelRateOf = { develRate }, cityGeneralCountOf = { 0 },
        )
        GenWarMoveFamily.bodies(ctx).getValue("내정워프")(null)
        assertEquals(RecordingRng.Draw("nextBool", 0.6), rng.draws.first(),
            "the FIRST draw is the ALWAYS-drawn nextBool(0.6) (:3029)")
    }

    @Test
    fun `do내정워프 at war 통솔장 returns null with no draws`() {
        val self = city(100)
        val st = instance() // d전쟁 + 통솔장 → :3024 early-return.
        val rng = RecordingRng("internalwarp-war")
        val ctx = ctxOf(rng, st, selfCity = self, cityRows = listOf(self))
        assertNull(GenWarMoveFamily.bodies(ctx).getValue("내정워프")(null), "통솔장 & war → :3024 null")
        assertTrue(rng.draws.isEmpty())
    }

    // ==================================================================================================
    // do귀환 (PHP :3095-3109) — ZERO draws.
    // ==================================================================================================

    @Test
    fun `do귀환 in a non-supplied own city emits che_귀환 with ZERO draws`() {
        val self = city(100, nationId = 1, supplyState = 0) // own nation but unsupplied → NOT the early-return.
        val st = instance()
        val rng = RecordingRng("return")
        val ctx = ctxOf(rng, st, selfCity = self, cityRows = listOf(self))
        val chosen = GenWarMoveFamily.bodies(ctx).getValue("귀환")(null)!!
        assertEquals("che_귀환", chosen.actionCode)
        assertTrue(rng.draws.isEmpty(), "do귀환 makes ZERO draws")
    }

    @Test
    fun `do귀환 in a supplied own city returns null with ZERO draws`() {
        val self = city(100, nationId = 1, supplyState = 1)
        val st = instance()
        val rng = RecordingRng("return-supplied")
        val ctx = ctxOf(rng, st, selfCity = self, cityRows = listOf(self))
        assertNull(GenWarMoveFamily.bodies(ctx).getValue("귀환")(null), "own+supplied → :3099 null")
        assertTrue(rng.draws.isEmpty())
    }

    // ==================================================================================================
    // do집합 (PHP :3111-3125) — GATE-EXEMPT; npc==5 draws nextRangeInt(2,4) + killturn delta.
    // ==================================================================================================

    @Test
    fun `do집합 npc5 draws nextRangeInt and writes the killturn delta`() {
        val st = instance()
        val rng = RecordingRng("assemble-npc5")
        val deltas = ArrayList<KvDelta>()
        val ctx = ctxOf(
            rng, st, selfCity = city(100), selfNpcType = 5, selfKillturn = 3,
            recordGeneralKv = { gid, k, v -> deltas.add(KvDelta(gid, k, v)) },
        )
        val chosen = GenWarMoveFamily.do집합(ctx)(null)!!
        assertEquals("che_집합", chosen.actionCode)
        assertEquals(listOf(RecordingRng.Draw("nextRangeInt", -1.0)), rng.draws,
            "npc==5 draws exactly one nextRangeInt(2,4) (:3116)")
        assertEquals(1, deltas.size, "one killturn meta-KV delta (decision #12)")
        assertEquals(GenWarMoveFamily.KILLTURN_KEY, deltas[0].key)
        // (killturn 3 + draw[2..4]) % 5 + 70 ∈ {70,71,72,73,74}.
        assertTrue((deltas[0].value as Int) in 70..74)
    }

    @Test
    fun `do집합 non-npc5 emits che_집합 with ZERO draws and no delta`() {
        val st = instance()
        val rng = RecordingRng("assemble-npc2")
        val deltas = ArrayList<KvDelta>()
        val ctx = ctxOf(
            rng, st, selfCity = city(100), selfNpcType = 2,
            recordGeneralKv = { gid, k, v -> deltas.add(KvDelta(gid, k, v)) },
        )
        val chosen = GenWarMoveFamily.do집합(ctx)(null)!!
        assertEquals("che_집합", chosen.actionCode)
        assertTrue(rng.draws.isEmpty(), "non-npc-5 makes ZERO draws")
        assertTrue(deltas.isEmpty(), "no killturn delta for non-npc-5")
    }

    // ==================================================================================================
    // do방랑군이동 (PHP :3127-3215) — target pick (no cached) → at-target che_인재탐색 / next-hop che_이동.
    // ==================================================================================================

    @Test
    fun `do방랑군이동 with a cached target draws one next-hop pick and emits che_이동 toward it`() {
        // current = city 70 "호관" (level 3 관 → NOT in {5,6}, so the dupLord/level gate passes regardless).
        // cached movingTargetCityID = 23 "하내" (a real adjacent CityConst neighbour of 70; not occupied,
        // not == current). The body computes distMap from 23, builds the next-hop candidate set over 70's
        // path neighbours (name-order), and draws ONE choiceUsingWeightPair (:3208) → che_이동.
        val self = city(70, level = 3)
        val st = instance()
        val rng = RecordingRng("wander-nexthop")
        val ctx = ctxOf(
            rng, st, selfCity = self, selfCityLevel = 3, dupLordAtSelfCity = 1,
            movingTargetCityId = 23, wanderOccupiedCities = emptySet(),
        )
        val chosen = GenWarMoveFamily.do방랑군이동(ctx)(null)!!
        assertEquals("che_이동", chosen.actionCode)
        assertTrue(chosen.args["destCityID"] in CityConstNeighborsOf70(), "dest is a real adjacent city of 70")
        assertEquals(1, rng.draws.count { it.kind == "choice" },
            "exactly one next-hop choiceUsingWeightPair (:3208); no target draw (target cached)")
    }

    @Test
    fun `do방랑군이동 self city level 5 returns null with no draws`() {
        // dupLord<=1 AND city level∈{5,6} → :3137 null.
        val st = instance()
        val rng = RecordingRng("wander-lv5")
        val ctx = ctxOf(
            rng, st, selfCity = city(100, level = 5), selfCityLevel = 5, dupLordAtSelfCity = 1,
        )
        assertNull(GenWarMoveFamily.do방랑군이동(ctx)(null), "level 5 → :3137 null")
        assertTrue(rng.draws.isEmpty())
    }

    /** City 70 "호관" path neighbours (CityConst): 업/낙양/하내/진양 — used to assert the next-hop dest is real. */
    private fun CityConstNeighborsOf70(): Set<Int> =
        opensamguk.common.constants.CityConst.byId(70)!!.path.keys

    // ==================================================================================================
    // ASSEMBLE — the war/move bodies register into the factory by action-name + the two pre-loop branches.
    // ==================================================================================================

    @Test
    fun `war-move bodies register into the factory generalDispatch by action-name`() {
        val st = instance()
        val rng = RandUtil(LiteHashDrbg("assemble"))
        val ctx = ctxOf(rng, st, selfCity = city(100))

        val bodies = GeneralAiDoBodies(
            generalDispatch = GenWarMoveFamily.bodies(ctx),
            do집합 = GenWarMoveFamily.do집합(ctx),
            do방랑군이동 = GenWarMoveFamily.do방랑군이동(ctx),
        )
        GeneralAiFactory.build(
            generalPolicy = AutorunGeneralPolicy(npcType = 2, nationId = 1),
            bodies = bodies,
        )

        val keys = GenWarMoveFamily.bodies(ctx).keys.toList()
        assertEquals(
            listOf("전투준비", "소집해제", "출병", "NPC헌납", "후방워프", "전방워프", "내정워프", "귀환"),
            keys,
            "the 8 dispatch-loop war/move bodies keyed by bare do<한글> action-name in PHP order",
        )
    }
}
