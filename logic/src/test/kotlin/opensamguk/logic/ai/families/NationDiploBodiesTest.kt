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
import opensamguk.logic.ai.KvDelta
import opensamguk.logic.ai.NationAiInput
import opensamguk.logic.domain.City
import opensamguk.logic.domain.LastTurn
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * L-DIPLO — the WORLD-DRIVEN diplomacy `do<한글>` bodies ([NationDiploFamily.bodies]).
 *
 * GRAND TRUTH = PHP `legacy/devsam-core/hwe/sammo/GeneralAI.php` (read in full):
 *  - `do불가침제의` (`:1765-1846`, emits `che_불가침제의`; ZERO RNG draws; recv_assist filter + 8mo cooldown +
 *    stable `arsort` DESC + income-quarter walk; writes `resp_assist_try` nation-env KV BELOW the gate),
 *  - `do선전포고`   (`:1848-1973`, emits `che_선전포고`; up to 3 draws A+B+C; neighbor partition via isNeighbor;
 *    sets `reqUpdateInstance=true`),
 *  - `do천도`       (`:1976-2113`, emits `che_천도`; persistence-or-arsort path; `choice` next-hop ONLY if dist>1;
 *    writes `last천도Trial` nation-env KV BELOW the gate).
 *
 * **SELECTION + boolean gate + draw stream ONLY (decision #11 / m10); the `che_*` state-mutation/logs are P6.**
 *
 * Each test builds [NationDiploFamily.bodies] over a DETERMINISTIC fixture [AiWorldView]/[AiInstanceState]/
 * policy + a family-local [NationDiploFamily.DiploInput] (the adapter materialises the diplo-specific world
 * reads — recv_assist KV / income / isNeighbor / getAllNationStaticInfo / devRate / TechLimit / the capital-
 * connected BFS distance maps — that are NOT plain logic columns and so are NOT on the frozen
 * [GeneralAiContext]) and asserts, for a representative scenario per method, the chosen `(actionCode, RAW
 * args)` + the ordered draw stream off a recording [RandUtil] + the meta-KV delta queued below the gate. The
 * candidate-set ORDER (arsort DESC / getAllNationStaticInfo insertion order / capital-path BFS name order) is
 * the draw-for-draw parity target; the body calls the PURE primitive then gates via `candidateAllowed`.
 */
class NationDiploBodiesTest {

    /** A draw-recording RNG over a REAL [LiteHashDrbg] — records the ordered top-level semantic draw stream. */
    private class RecordingRng(seed: String) : RandUtil(LiteHashDrbg(seed)) {
        data class Draw(val kind: String, val prob: Double)
        val draws = ArrayList<Draw>()
        var nextBoolCalls = 0
        var nextFloat1Calls = 0
        var nextBitCalls = 0
        var choiceCalls = 0
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
            if (!inNextBool) draws.add(Draw("nextFloat1", -1.0)) // top-level float = choiceUsingWeightPair (C).
            return super.nextFloat1()
        }
        override fun nextBit(): Boolean {
            nextBitCalls++
            if (!inNextBool) draws.add(Draw("nextBit", 0.5))
            return super.nextBit()
        }
        override fun <T> choice(items: List<T>): T {
            choiceCalls++; draws.add(Draw("choice", items.size.toDouble())); return super.choice(items)
        }
    }

    /** A recording nation-KV sink (the ChangeRecorder delta seam, decision #12). */
    private class RecordingKv : AiKvRecorder {
        val deltas = ArrayList<KvDelta>()
        override fun recordNationKv(nationId: Int, key: String, value: Any?) {
            deltas.add(KvDelta(nationId, key, value))
        }
    }

    // --- fixture builders -----------------------------------------------------------------------------

    private fun city(
        id: Int,
        nation: Int = 1,
        supply: Int = 1,
        front: Int = 0,
    ): City = City(
        id = id, nationId = nation, level = 5,
        commerce = 0, commerceMax = 1, agriculture = 100, agricultureMax = 100,
        supplyState = supply, frontState = front, trust = 0.0,
        security = 0, securityMax = 1, defense = 0, defenseMax = 1, wall = 0, wallMax = 1,
        population = 100_000, populationMax = 100_000,
    )

    private fun instance(
        kv: RecordingKv,
        nationCapital: Int = 100,
        diplomacy: List<AiDiplomacyRow> = emptyList(),
        frontMax: Int = 0,
        gold: Int = 99999,
        rice: Int = 99999,
    ): AiInstanceState {
        val env = AiEnv(year = 200, month = 1, startYear = 180, develCost = 10)
        val nationPolicy = AutorunNationPolicy(npcType = 2, tech = 0, develcost = 10)
        val st = AiInstanceState(
            generalNationId = 1,
            env = env,
            nationPolicy = nationPolicy,
            nationRowLookup = {
                AiNationRow(nation = 1, level = 5, capital = nationCapital, gold = gold, rice = rice)
            },
            nationStor = emptyMap(),
            diplomacyOf = { diplomacy },
            frontMaxOf = { frontMax },
            kvRecorder = kv,
        )
        st.updateInstance()
        return st
    }

    private fun ctxOf(
        rng: RandUtil,
        cityRows: List<City>,
        instance: AiInstanceState,
        selfGeneralId: Int = 1,
        selfCityId: Int = 100,
        selfOfficerLevel: Int = 12,
        techLimitedNextGrade: Boolean = true,
        candidateAllowed: (String, Map<String, Any?>) -> Boolean = { _, _ -> true },
    ): GeneralAiContext {
        val world = AiWorldView(
            ownNationId = 1,
            cityRows = cityRows,
            warTargetNation = mapOf(0 to 1),
            ownGeneralId = selfGeneralId,
            generals = emptyList<AiGeneralView>(),
            dipState = AiInstanceState.D_PEACE,
            minWarCrew = 0,
            minNpcWarLeadership = 0,
            turnTerm = 120,
        )
        return GeneralAiContext(
            rng = rng,
            instance = instance,
            world = world,
            generalPolicy = AutorunGeneralPolicy(npcType = 2, nationId = 1),
            nationPolicy = AutorunNationPolicy(npcType = 2, tech = 0, develcost = 10),
            env = AiEnv(year = 200, month = 1, startYear = 180, develCost = 10),
            turnTerm = 120,
            selfGeneralId = selfGeneralId,
            selfCityId = selfCityId,
            selfOfficerLevel = selfOfficerLevel,
            techLimitedNextGrade = techLimitedNextGrade,
            candidateAllowed = candidateAllowed,
        )
    }

    // ==================================================================================================
    // do불가침제의 (PHP :1765-1846) — ZERO draws + recv_assist/cooldown filter + arsort pick + resp_assist_try.
    // ==================================================================================================

    @Test
    fun `do불가침제의 picks the highest-amount candidate with ZERO draws and queues resp_assist_try`() {
        val kv = RecordingKv()
        val st = instance(kv)
        val rng = RecordingRng("non-aggression")
        val ctx = ctxOf(rng, listOf(city(100)), st)

        // yearMonth = joinYearMonth(200, 1) = 200*12 + 1 - 1 = 2400.
        val input = NationDiploFamily.DiploInput(
            nationId = 1,
            officerLevel = 12,
            yearMonth = 2400,
            // recv_assist: nation 2 gave 1000, nation 3 gave 800, nation 4 gave 100 (insertion order).
            recvAssist = listOf(2 to 1000, 3 to 800, 4 to 100),
            respAssist = emptyMap(),
            respAssistTry = emptyMap(),
            warTargetNationKeys = emptySet(),
            income = 3000.0, // amount*4 >= 3000 → nation 2 (4000) qualifies, picked first after arsort DESC.
            recordNationKv = kv,
        )

        val body = NationDiploFamily.bodies(ctx, input).getValue("불가침제의")
        val chosen = body(null)!!

        assertEquals("che_불가침제의", chosen.actionCode)
        // diplomatMonth = 24*1000/3000 = 8 → parseYearMonth(2400+8) = parseYearMonth(2408) = [200, 9].
        assertEquals(linkedMapOf("destNationID" to 2, "year" to 200, "month" to 9), chosen.args)
        assertEquals(0, rng.nextBoolCalls + rng.nextFloat1Calls + rng.nextBitCalls + rng.choiceCalls, "ZERO draws")
        // resp_assist_try["n2"] = [2, 2400] queued BELOW the gate (PHP :1841-1842).
        assertEquals(
            listOf(KvDelta(1, "resp_assist_try", mapOf("n2" to listOf(2, 2400)))),
            kv.deltas,
            "resp_assist_try delta keyed n{dest}=[dest,yearMonth], queued below the gate",
        )
    }

    @Test
    fun `do불가침제의 returns null below officer_level 12 with zero draws and no delta`() {
        val kv = RecordingKv()
        val st = instance(kv)
        val rng = RecordingRng("non-aggression")
        val ctx = ctxOf(rng, listOf(city(100)), st)
        val input = NationDiploFamily.DiploInput(
            nationId = 1, officerLevel = 11, yearMonth = 2400,
            recvAssist = listOf(2 to 1000), respAssist = emptyMap(), respAssistTry = emptyMap(),
            warTargetNationKeys = emptySet(), income = 3000.0, recordNationKv = kv,
        )
        assertNull(NationDiploFamily.bodies(ctx, input).getValue("불가침제의")(null), "officer_level<12 → null (PHP :1769)")
        assertTrue(kv.deltas.isEmpty() && rng.draws.isEmpty(), "no delta, no draw")
    }

    @Test
    fun `do불가침제의 filters cooldown and war-target and net-zero candidates before the arsort pick`() {
        val kv = RecordingKv()
        val st = instance(kv)
        val rng = RecordingRng("non-aggression")
        val ctx = ctxOf(rng, listOf(city(100)), st)

        // nation 2: amount 1000, but resp_assist already 1000 → net 0 → skip (PHP :1785-1788).
        // nation 3: amount 900, but it is a warTarget → skip (PHP :1789-1791).
        // nation 4: amount 900, but resp_assist_try stamp 2399 >= 2400-8 → on cooldown → skip (PHP :1792-1794).
        // nation 5: amount 800, clean → the only candidate.
        val input = NationDiploFamily.DiploInput(
            nationId = 1, officerLevel = 12, yearMonth = 2400,
            recvAssist = listOf(2 to 1000, 3 to 900, 4 to 900, 5 to 800),
            respAssist = mapOf("n2" to listOf(2, 1000)),
            respAssistTry = mapOf("n4" to listOf(4, 2399)),
            warTargetNationKeys = setOf(3),
            income = 3000.0, // 800*4 = 3200 >= 3000 → nation 5 qualifies.
            recordNationKv = kv,
        )

        val chosen = NationDiploFamily.bodies(ctx, input).getValue("불가침제의")(null)!!
        assertEquals(5, chosen.args["destNationID"], "only nation 5 survives the recv/war/cooldown filters")
        // diplomatMonth = 24*800/3000 = 6 → parseYearMonth(2406) = [200, 7].
        assertEquals(200, chosen.args["year"]); assertEquals(7, chosen.args["month"])
        assertEquals(listOf(KvDelta(1, "resp_assist_try", mapOf("n5" to listOf(5, 2400)))), kv.deltas)
        assertEquals(0, rng.draws.size, "ZERO draws")
    }

    @Test
    fun `do불가침제의 returns null when the gate rejects and queues no delta`() {
        val kv = RecordingKv()
        val st = instance(kv)
        val rng = RecordingRng("non-aggression")
        val ctx = ctxOf(rng, listOf(city(100)), st, candidateAllowed = { _, _ -> false })
        val input = NationDiploFamily.DiploInput(
            nationId = 1, officerLevel = 12, yearMonth = 2400,
            recvAssist = listOf(2 to 1000), respAssist = emptyMap(), respAssistTry = emptyMap(),
            warTargetNationKeys = emptySet(), income = 3000.0, recordNationKv = kv,
        )
        assertNull(NationDiploFamily.bodies(ctx, input).getValue("불가침제의")(null), "gate deny → null (PHP :1837-1839)")
        assertTrue(kv.deltas.isEmpty(), "resp_assist_try queued only BELOW a passing gate (PHP :1841)")
    }

    // ==================================================================================================
    // do선전포고 (PHP :1848-1973) — up to 3 draws A+B+C, neighbor partition.
    // ==================================================================================================

    private fun warInput(
        rng: RandUtil,
        lowTargetNations: Set<Int> = emptySet(),
        allNations: List<Triple<Int, Int, Int>> = emptyList(), // (nationId, level, power)
        isNeighbor: (Int) -> Boolean = { true },
        trialProp: Double = 1.5, // >=1 short-circuits A (no underlying draw) → deterministic test of B/C.
    ): NationDiploFamily.WarInput = NationDiploFamily.WarInput(
        trialPropPow6 = trialProp,
        lowTargetNations = lowTargetNations,
        allNationStaticInfo = allNations,
        isNeighbor = isNeighbor,
    )

    @Test
    fun `do선전포고 fires A then C in the normal (non-empty nations) path and emits che_선전포고`() {
        val kv = RecordingKv()
        val st = instance(kv) // d평화, no front, capital 100.
        val rng = RecordingRng("war-decl")
        val ctx = ctxOf(rng, listOf(city(100)), st)
        // Two neighbor nations not in lowTargetNations → $nations (the normal branch, no B draw).
        val input = warInput(
            rng,
            lowTargetNations = emptySet(),
            allNations = listOf(Triple(2, 5, 3), Triple(3, 5, 8)),
            isNeighbor = { it == 2 || it == 3 },
            trialProp = 0.99, // (0,1) → A draws one underlying float.
        )

        val chosen = NationDiploFamily.bodies(ctx, war = input).getValue("선전포고")(null)
        // A(nextBool) → C(choiceUsingWeight=nextFloat1). NO B (nations non-empty). 2 draws.
        assertEquals(listOf("nextBool", "nextFloat1"), rng.draws.map { it.kind }, "A then C; no empty-nations B")
        assertTrue(chosen != null, "the war-trial passed for this seed (asserting the stream shape + emit)")
        assertEquals("che_선전포고", chosen!!.actionCode)
        assertTrue(chosen.args["destNationID"] in listOf(2, 3), "target = choiceUsingWeight over the two neighbors")
    }

    @Test
    fun `do선전포고 fires the 3-draw A then B then C empty-nations fallback (PHP wins TS 2)`() {
        val kv = RecordingKv()
        val st = instance(kv)
        val rng = RecordingRng("war-decl-fallback")
        val ctx = ctxOf(rng, listOf(city(100)), st)
        // Both neighbors ARE in lowTargetNations → $nations empty, $warNations non-empty, lowTargetNations
        // non-empty → the B abort draw nextBool(1/count) fires, then (if not abort) C.
        val input = warInput(
            rng,
            lowTargetNations = setOf(2, 3),
            allNations = listOf(Triple(2, 5, 3), Triple(3, 5, 8)),
            isNeighbor = { it == 2 || it == 3 },
            trialProp = 1.5, // A short-circuits true with NO underlying draw → but still ONE nextBool call.
        )

        val chosen = NationDiploFamily.bodies(ctx, input).getValue("선전포고")(null)
        // A(nextBool short-circuit) → B(nextBool 1/2) → maybe C. The stream SHAPE asserts A+B present.
        assertTrue(rng.draws.size >= 2, "at least A and B draws happen in the empty-nations fallback")
        assertEquals("nextBool", rng.draws[0].kind, "A = nextBool(trialProp**6)")
        assertEquals("nextBool", rng.draws[1].kind, "B = nextBool(1/count(lowTargetNations)) — PHP WINS TS 2-draw")
        assertEquals(0.5, rng.draws[1].prob, 1e-12, "B prob = 1/count(lowTargetNations) = 1/2")
        // If B did NOT abort, C fires; if it aborted, chosen is null. Either is a valid stream — assert shape.
        if (chosen != null) {
            assertEquals("nextFloat1", rng.draws[2].kind, "C = choiceUsingWeight over warNations promoted to nations")
            assertEquals("che_선전포고", chosen.actionCode)
        }
    }

    @Test
    fun `do선전포고 aborts with no draw when no neighbor nations exist`() {
        val kv = RecordingKv()
        val st = instance(kv)
        val rng = RecordingRng("war-decl-none")
        val ctx = ctxOf(rng, listOf(city(100)), st)
        // A passes (>=1 no draw); but NO neighbors → both $nations and $warNations empty → null, no B/C draw.
        val input = warInput(
            rng,
            lowTargetNations = emptySet(),
            allNations = listOf(Triple(2, 5, 3)),
            isNeighbor = { false }, // nation 2 is not a neighbor → skipped.
            trialProp = 1.5,
        )
        val chosen = NationDiploFamily.bodies(ctx, input).getValue("선전포고")(null)
        assertNull(chosen, "no neighbor → both partitions empty → null (PHP :1952-1955)")
        assertEquals(listOf("nextBool"), rng.draws.map { it.kind }, "only A; no B/C (empty partitions return before)")
    }

    @Test
    fun `do선전포고 returns null when the trial gate A fails (one draw, no emit)`() {
        val kv = RecordingKv()
        val st = instance(kv)
        val rng = RecordingRng("war-decl-fail")
        val ctx = ctxOf(rng, listOf(city(100)), st)
        val input = warInput(rng, allNations = listOf(Triple(2, 5, 3)), isNeighbor = { true }, trialProp = 0.0)
        val chosen = NationDiploFamily.bodies(ctx, input).getValue("선전포고")(null)
        assertNull(chosen, "trialProp**6 <= 0 → nextBool false → null (PHP :1923-1924)")
        assertEquals(listOf("nextBool"), rng.draws.map { it.kind }, "A only; short-circuit false")
    }

    @Test
    fun `do선전포고 returns null in non-peace state with zero draws`() {
        val kv = RecordingKv()
        // d전쟁: a state-0 war row + a front city → dipState != d평화.
        val st = instance(
            kv,
            diplomacy = listOf(AiDiplomacyRow(you = 2, state = 0, term = 0)),
            frontMax = 1,
        )
        val rng = RecordingRng("war-decl-notpeace")
        val ctx = ctxOf(rng, listOf(city(100, front = 1)), st)
        val input = warInput(rng, allNations = listOf(Triple(2, 5, 3)))
        assertNull(NationDiploFamily.bodies(ctx, input).getValue("선전포고")(null), "dipState != d평화 → null (PHP :1856)")
        assertTrue(rng.draws.isEmpty(), "early guard before any draw")
    }

    @Test
    fun `do선전포고 returns null below ruler level with zero draws`() {
        val kv = RecordingKv()
        val st = instance(kv)
        val rng = RecordingRng("war-decl-non-ruler")
        val ctx = ctxOf(rng, listOf(city(100)), st, selfOfficerLevel = 11)
        val input = warInput(rng, allNations = listOf(Triple(2, 5, 3)), trialProp = 0.5)

        assertNull(NationDiploFamily.bodies(ctx, input).getValue("선전포고")(null))
        assertTrue(rng.draws.isEmpty(), "officer_level < 12 returns before trial draw (PHP :1852)")
    }

    @Test
    fun `do선전포고 returns null below the tech limit with zero draws`() {
        val kv = RecordingKv()
        val st = instance(kv)
        val rng = RecordingRng("war-decl-tech-limit")
        val ctx = ctxOf(rng, listOf(city(100)), st, techLimitedNextGrade = false)
        val input = warInput(rng, allNations = listOf(Triple(2, 5, 3)), trialProp = 0.5)

        assertNull(NationDiploFamily.bodies(ctx, input).getValue("선전포고")(null))
        assertTrue(rng.draws.isEmpty(), "unreached next tech grade returns before trial draw (PHP :1877)")
    }

    // ==================================================================================================
    // do천도 (PHP :1976-2113) — persistence/arsort + top-quartile gate + choice next-hop ONLY if dist>1.
    // ==================================================================================================

    private fun relocInput(
        capital: Int = 100,
        nationCityIds: List<Int> = listOf(100, 110, 120),
        distanceList: Map<Int, Map<Int, Int>> = mapOf(
            100 to mapOf(100 to 0, 110 to 1, 120 to 2),
            110 to mapOf(100 to 1, 110 to 0, 120 to 1),
            120 to mapOf(100 to 2, 110 to 1, 120 to 0),
        ),
        cityScores: Map<Int, Double> = linkedMapOf(100 to 1.0, 110 to 5.0, 120 to 9.0),
        capitalPathNeighbors: List<Int> = listOf(110),
        lastTrialBlocks: Boolean = false,
        kv: AiKvRecorder,
        officerLevel: Int = 12,
        turnTime: String = "200-01-01 00:00:00",
    ): NationDiploFamily.RelocateInput = NationDiploFamily.RelocateInput(
        capital = capital,
        nationCityIds = nationCityIds,
        distanceList = distanceList,
        cityScores = cityScores,
        capitalPathNeighbors = capitalPathNeighbors,
        lastTrialBlocks = lastTrialBlocks,
        recordNationKv = kv,
        nationId = 1,
        officerLevel = officerLevel,
        turnTime = turnTime,
    )

    @Test
    fun `do천도 picks the top-score city and (dist 2) draws one choice for the next hop`() {
        val kv = RecordingKv()
        val st = instance(kv, nationCapital = 100)
        val rng = RecordingRng("relocate")
        val ctx = ctxOf(rng, listOf(city(100), city(110), city(120)), st)
        // cityScores: 120=9 (top), 110=5, 100=1 (capital). enoughLimit=ceil(3*0.25)=ceil(0.75)=1.
        // capital idx in arsort DESC = 2 (after 120,110) → 2 > 1 → NOT good enough → relocate.
        // finalCityID=120, dist[100][120]=2 > 1 → one choice over capitalPathNeighbors [110].
        val input = relocInput(kv = kv)

        val chosen = NationDiploFamily.bodies(ctx, input).getValue("천도")(null)!!
        assertEquals("che_천도", chosen.actionCode)
        assertEquals(110, chosen.args["destCityID"], "dist>1 → intermediate stop = choice(capitalPathNeighbors)")
        assertEquals(listOf("choice"), rng.draws.map { it.kind }, "exactly one choice (dist>1) — PHP :2100")
        assertEquals(1.0, rng.draws[0].prob, "choice over the single one-step-closer neighbor")
        // last천도Trial = [officer_level, turnTime] queued below the gate (PHP :2111-2112).
        assertEquals(
            listOf(KvDelta(1, "last천도Trial", listOf(12, "200-01-01 00:00:00"))),
            kv.deltas,
        )
    }

    @Test
    fun `do천도 (dist 1) takes the final city directly with zero draws`() {
        val kv = RecordingKv()
        val st = instance(kv, nationCapital = 100)
        val rng = RecordingRng("relocate-adjacent")
        val ctx = ctxOf(rng, listOf(city(100), city(110), city(120)), st)
        // The top-score city 110 is ADJACENT to the capital (dist 1) → no intermediate hop, no draw.
        // Capital 100 is the LOWEST score (arsort DESC → [110,120,100], capital idx 2). enoughLimit=
        // ceil(3*0.25)=1; the PHP top-quartile walk (`:2079` `if ($idx > $enoughLimit) break;`) BREAKS at
        // idx 2 > 1 BEFORE the capital is flagged, so the relocation proceeds. (A 2-city nation would abort
        // here — PHP's `idx <= enoughLimit` walk flags the capital at idx 1 <= enoughLimit 1; that latent
        // off-by-one vs the "상위 25%" comment is PHP grand truth, so this fixture uses 3 cities to reach the
        // dist==1 path the test targets without tripping the abort.)
        val input = relocInput(
            kv = kv,
            nationCityIds = listOf(100, 110, 120),
            distanceList = mapOf(
                100 to mapOf(100 to 0, 110 to 1, 120 to 2),
                110 to mapOf(100 to 1, 110 to 0, 120 to 1),
                120 to mapOf(100 to 2, 110 to 1, 120 to 0),
            ),
            cityScores = linkedMapOf(100 to 1.0, 110 to 9.0, 120 to 5.0),
            capitalPathNeighbors = listOf(110),
        )
        val chosen = NationDiploFamily.bodies(ctx, input).getValue("천도")(null)!!
        assertEquals(110, chosen.args["destCityID"], "dist==1 → target = finalCityID directly")
        assertTrue(rng.draws.isEmpty(), "dist<=1 → ZERO draws")
        // last천도Trial still queued below the gate (dist==1 reaches the emit + the delta, PHP :2112).
        assertEquals(
            listOf(KvDelta(1, "last천도Trial", listOf(12, "200-01-01 00:00:00"))),
            kv.deltas,
        )
    }

    @Test
    fun `do천도 aborts (null) when the capital ranks within the top quartile`() {
        val kv = RecordingKv()
        val st = instance(kv, nationCapital = 100)
        val rng = RecordingRng("relocate-good")
        val ctx = ctxOf(rng, listOf(city(100), city(110), city(120)), st)
        // capital 100 has the HIGHEST score → idx 0 <= enoughLimit (ceil(3*0.25)=1) → already good → null.
        val input = relocInput(
            kv = kv,
            cityScores = linkedMapOf(100 to 9.0, 110 to 5.0, 120 to 1.0),
        )
        assertNull(NationDiploFamily.bodies(ctx, input).getValue("천도")(null), "capital in top quartile → null (PHP :2082-2084)")
        assertTrue(kv.deltas.isEmpty() && rng.draws.isEmpty(), "abort before the choice and the delta")
    }

    @Test
    fun `do천도 returns null with fewer than two connected cities and zero draws`() {
        val kv = RecordingKv()
        val st = instance(kv, nationCapital = 100)
        val rng = RecordingRng("relocate-tiny")
        val ctx = ctxOf(rng, listOf(city(100)), st)
        val input = relocInput(
            kv = kv,
            nationCityIds = listOf(100),
            distanceList = mapOf(100 to mapOf(100 to 0)),
            cityScores = linkedMapOf(100 to 1.0),
            capitalPathNeighbors = emptyList(),
        )
        assertNull(NationDiploFamily.bodies(ctx, input).getValue("천도")(null), "<=1 city → null (PHP :2024/2056)")
        assertTrue(rng.draws.isEmpty(), "ZERO draws")
    }

    @Test
    fun `do천도 sticky persistence re-emits the last destCityID with no draw`() {
        val kv = RecordingKv()
        val st = instance(kv, nationCapital = 100)
        val rng = RecordingRng("relocate-sticky")
        val ctx = ctxOf(rng, listOf(city(100), city(110), city(120)), st)
        val input = relocInput(kv = kv)
        // lastTurn = 천도 to non-capital 120 → re-emit the SAME arg with NO draw (PHP :1986-1992).
        val lastTurn = LastTurn(command = "천도", arg = mapOf("destCityID" to 120))
        val chosen = NationDiploFamily.bodies(ctx, input).getValue("천도")(lastTurn)!!
        assertEquals("che_천도", chosen.actionCode)
        assertEquals(120, chosen.args["destCityID"], "sticky: re-emit the previous destCityID")
        assertTrue(rng.draws.isEmpty(), "persistence path makes NO draw")
        assertEquals(
            listOf(KvDelta(1, "last천도Trial", listOf(12, "200-01-01 00:00:00"))),
            kv.deltas,
            "the persistence path ALSO queues last천도Trial below the gate (PHP :1989)",
        )
    }

    @Test
    fun `do천도 cooldown (lastTrialBlocks) returns null after the persistence check with no draw`() {
        val kv = RecordingKv()
        val st = instance(kv, nationCapital = 100)
        val rng = RecordingRng("relocate-cooldown")
        val ctx = ctxOf(rng, listOf(city(100), city(110), city(120)), st)
        val input = relocInput(kv = kv, lastTrialBlocks = true)
        assertNull(NationDiploFamily.bodies(ctx, input).getValue("천도")(null), "cooldown (PHP :1996-2005) → null")
        assertTrue(rng.draws.isEmpty() && kv.deltas.isEmpty(), "no draw, no delta")
    }

    // ==================================================================================================
    // ASSEMBLE — the diplo bodies wire into the factory nationDispatch and fire through chooseNationTurn.
    // ==================================================================================================

    @Test
    fun `diplo bodies wire into the factory nationDispatch and fire through chooseNationTurn`() {
        val kv = RecordingKv()
        val st = instance(kv)
        val rng = RandUtil(LiteHashDrbg("assemble"))
        val ctx = ctxOf(rng, listOf(city(100)), st)
        val input = NationDiploFamily.DiploInput(
            nationId = 1, officerLevel = 12, yearMonth = 2400,
            recvAssist = listOf(2 to 1000), respAssist = emptyMap(), respAssistTry = emptyMap(),
            warTargetNationKeys = emptySet(), income = 3000.0, recordNationKv = kv,
        )
        val warInput = NationDiploFamily.WarInput(0.0, emptySet(), emptyList()) { false }
        val relocInput = NationDiploFamily.RelocateInput(
            100, listOf(100), mapOf(100 to mapOf(100 to 0)), linkedMapOf(100 to 1.0),
            emptyList(), false, kv, 1, 12, "200-01-01 00:00:00",
        )

        val nationPolicy = AutorunNationPolicy(
            npcType = 2, tech = 0, develcost = 10,
            nationPolicy = mapOf("priority" to listOf("불가침제의")),
        )
        val bodies = GeneralAiDoBodies(
            nationDispatch = NationDiploFamily.bodies(ctx, input, warInput, relocInput),
        )
        val ai = GeneralAiFactory.build(
            generalPolicy = AutorunGeneralPolicy(npcType = 2, nationId = 1),
            nationPolicy = nationPolicy,
            bodies = bodies,
        )

        val chosen = ai.chooseNationTurn(
            reservedCommand = ChosenCommand("휴식", emptyMap()),
            lastTurn = null,
            input = NationAiInput(npcType = 2, officerLevel = 12, month = 1, rng = rng),
        )
        assertEquals("che_불가침제의", chosen.actionCode, "the wired do불가침제의 body dispatched through the nation loop")
        assertEquals("do불가침제의", chosen.reason, "the loop tags reason 'do'+actionName")
        assertEquals(2, chosen.args["destNationID"])
    }
}
