package opensamguk.logic.ai.families

import opensamguk.common.constants.GameConst
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
import opensamguk.logic.ai.ExternalSqlRandBranch
import opensamguk.logic.ai.ExternalSqlRandSelector
import opensamguk.logic.ai.GeneralAiContext
import opensamguk.logic.ai.KvDelta
import opensamguk.logic.domain.General
import opensamguk.logic.domain.GetNationColors
import opensamguk.logic.stats.GeneralActionPipeline
import opensamguk.logic.stats.StatCalc
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * L-GENFOUND — the WORLD-DRIVEN GenFoundFamily `do<한글>` BODIES ([GenFoundFamily.bodies] for the
 * dispatch-loop members + the pre-loop branch builders [GenFoundFamily.do선양]/[GenFoundFamily.do거병]/
 * [GenFoundFamily.do국가선택]/[GenFoundFamily.do건국]/[GenFoundFamily.do해산]/[GenFoundFamily.do중립]).
 *
 * GRAND TRUTH = PHP `legacy/devsam-core/hwe/sammo/GeneralAI.php` (read in full):
 *  - `do거병`        (`:3217-3288`): makelimit/npc>2/can건국 gates → `:3232` `nextBool(0.5)`=nextBit via `&&`
 *    (only on a non-foundable city) → BFS dist-3 scan over `foundOccupiedCities`-filtered candidates with
 *    `:3258` `nextBool()`=nextBit PER dist-3 candidate (variable, BFS-order) → `:3268` `nextFloat1()*midpoint`
 *    vs `ratio` → `:3278` `nextBool(0.0075*more)` → che_거병 + gate.
 *  - `do해산`        (`:3290-3300`): che_해산 gate → on pass, movingTargetCityID=null delta → emit. ZERO draws.
 *  - `do건국`        (`:3302-3318`): `:3304` `choice(availableNationType)` THEN `:3305` `choice(colors)` →
 *    che_건국 {nationName, nationType, colorType=INDEX} + gate → on pass, movingTargetCityID=null delta.
 *  - `do선양`        (`:3320-3332`): che_선양 {destGeneralID = min(no) F-QUAR substitute} + gate. ZERO draws.
 *  - `do국가선택`     (`:3334-3401`): npc==9 오랑캐 substitute (0-draw) → `:3358` `nextBool(0.3)` → {affinity==999
 *    null | early `:3371` `nextBool(pow(...))` | post-period `:3376` `nextBool()`=nextBit} → che_랜덤임관; ELSE
 *    sibling `:3390` `nextBool(0.2)` → `:3393` `choice(paths)` → che_이동; ELSE null.
 *  - `doNPC사망대비` (`:3403-3434`): killturn>5 null → nationID==0: che_인재탐색 then `:3413` `||` `nextBool()`=bit
 *    → che_견문; else gold+rice==0 → che_물자조달; else che_헌납 {isGold, amount=maxResourceActionAmount}.
 *  - `do중립`        (`:3436-3467`): TERMINAL — nationID==0: che_인재탐색 then `:3441` `||` `nextBool(0.8)` →
 *    che_견문; else `:3458` `choice(candidate)` ALWAYS (narrowed by reqNationGold/reqNationRice) → gate fallback.
 *
 * Each test builds the bodies over a DETERMINISTIC fixture world and asserts the chosen `(actionCode, RAW
 * args)` + the ordered draw stream off a recording [RandUtil]. The candidate-set ORDER (the BFS visitation
 * order, the availableNationType-then-color order, the path-neighbor order) is the draw-for-draw target.
 */
class GenFoundBodiesTest {

    /** A draw-recording RNG over a REAL [LiteHashDrbg] (mirrors the family-test recorder). */
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
            if (!inNextBool) draws.add(Draw("nextFloat1", -1.0))
            return super.nextFloat1()
        }
        override fun nextBit(): Boolean {
            if (!inNextBool) draws.add(Draw("nextBit", 0.5))
            return super.nextBit()
        }
        override fun <T> choice(items: List<T>): T {
            draws.add(Draw("choice", -1.0))
            return super.choice(items)
        }
    }

    // --- fixture builders -----------------------------------------------------------------------------

    private fun instance(
        nationId: Int = 0,
        rice: Int = 99999,
        gold: Int = 99999,
        genTypeSeed: String = "genType-fixture",
    ): AiInstanceState {
        val env = AiEnv(year = 200, month = 1, startYear = 180, develCost = 10)
        val nationPolicy = AutorunNationPolicy(npcType = 2, tech = 0, develcost = 10)
        val st = AiInstanceState(
            generalNationId = nationId,
            env = env,
            nationPolicy = nationPolicy,
            nationRowLookup = {
                if (nationId == 0) null
                else AiNationRow(nation = nationId, level = 5, capital = 0, gold = gold, rice = rice)
            },
            nationStor = emptyMap(),
            diplomacyOf = { emptyList<AiDiplomacyRow>() },
            frontMaxOf = { 0 },
            kvRecorder = object : AiKvRecorder { override fun recordNationKv(nationId: Int, key: String, value: Any?) {} },
        )
        st.updateInstance()
        val g = General(
            id = 1, nationId = nationId, cityId = 100,
            leadership = 50, strength = 50, intel = 50, injury = 0,
            experience = 0.0, dedication = 0.0, officerLevel = 1, gold = 0, rice = 0,
        )
        val statCalc = StatCalc(g, GeneralActionPipeline())
        st.calcGenType(RandUtil(LiteHashDrbg(genTypeSeed)), statCalc)
        return st
    }

    private fun gen(id: Int, nationId: Int, npcType: Int = 6, officerLevel: Int = 1): General =
        General(
            id = id, nationId = nationId, cityId = 100,
            leadership = 50, strength = 50, intel = 50, injury = 0,
            experience = 0.0, dedication = 0.0, officerLevel = officerLevel, gold = 0, rice = 0,
            npcType = npcType,
        )

    private fun ctxOf(
        rng: RandUtil,
        instance: AiInstanceState,
        nationId: Int = 0,
        selfCityId: Int = 100,
        selfCityLevel: Int = 5,
        candidateAllowed: (String, Map<String, Any?>) -> Boolean = { _, _ -> true },
        recordGeneralKv: (Int, String, Any?) -> Unit = { _, _, _ -> },
        fullLeadership: Double = 50.0,
        fullStrength: Double = 50.0,
        fullIntel: Double = 50.0,
        selfNpcType: Int = 6,
        selfKillturn: Int = 0,
        selfGold: Int = 0,
        selfRice: Int = 0,
        selfMakeLimit: Int = 0,
        selfGeneralName: String = "조조맹덕",
        selfAffinity: Int = 0,
        foundOccupiedCities: Set<Int> = emptySet(),
        foundStatMidpoint: Double = 70.0,
        foundDeadlineMore: Int = 1,
        nationCount: Int = 0,
        notFullNationCount: Int = 0,
        seonyangCandidates: List<General> = emptyList(),
        orankaeRulerCandidates: List<General> = emptyList(),
        externalSqlRandSelector: ExternalSqlRandSelector? = null,
        generalPolicy: AutorunGeneralPolicy = AutorunGeneralPolicy(npcType = 6, nationId = nationId),
        nationPolicy: AutorunNationPolicy = AutorunNationPolicy(npcType = 2, tech = 0, develcost = 10),
    ): GeneralAiContext {
        val world = AiWorldView(
            ownNationId = nationId,
            cityRows = emptyList(),
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
            selfCityId = selfCityId,
            candidateAllowed = candidateAllowed,
            recordGeneralKv = recordGeneralKv,
            fullLeadership = fullLeadership,
            fullStrength = fullStrength,
            fullIntel = fullIntel,
            selfNpcType = selfNpcType,
            selfKillturn = selfKillturn,
            selfGold = selfGold,
            selfRice = selfRice,
            selfCityLevel = selfCityLevel,
            selfMakeLimit = selfMakeLimit,
            selfGeneralName = selfGeneralName,
            selfAffinity = selfAffinity,
            foundOccupiedCities = foundOccupiedCities,
            foundStatMidpoint = foundStatMidpoint,
            foundDeadlineMore = foundDeadlineMore,
            nationCount = nationCount,
            notFullNationCount = notFullNationCount,
            externalSqlRandSelector = externalSqlRandSelector,
            seonyangCandidates = seonyangCandidates,
            orankaeRulerCandidates = orankaeRulerCandidates,
        )
    }

    // ==================================================================================================
    // do해산 (PHP :3290-3300) — ZERO draws; gate then movingTargetCityID=null delta.
    // ==================================================================================================

    @Test
    fun `do해산 emits che_해산 with ZERO draws and queues movingTargetCityID=null delta on gate pass`() {
        val kv = ArrayList<KvDelta>()
        val rng = RecordingRng("disband")
        val ctx = ctxOf(rng, instance(nationId = 0), recordGeneralKv = { id, k, v -> kv.add(KvDelta(id, k, v)) })

        val chosen = GenFoundFamily.do해산(ctx)(null)!!
        assertEquals("che_해산", chosen.actionCode)
        assertTrue(rng.draws.isEmpty(), "do해산 makes ZERO draws (PHP :3290-3300)")
        assertEquals(listOf(KvDelta(1, "movingTargetCityID", null)), kv, "PHP :3297 setAuxVar(movingTargetCityID,null)")
    }

    @Test
    fun `do해산 returns null with NO delta when the gate denies`() {
        val kv = ArrayList<KvDelta>()
        val rng = RecordingRng("disband-deny")
        val ctx = ctxOf(rng, instance(nationId = 0), candidateAllowed = { _, _ -> false },
            recordGeneralKv = { id, k, v -> kv.add(KvDelta(id, k, v)) })

        assertNull(GenFoundFamily.do해산(ctx)(null), "gate deny → :3293 null")
        assertTrue(kv.isEmpty(), "the delta is queued only AFTER the gate passes (PHP :3293-3297)")
        assertTrue(rng.draws.isEmpty())
    }

    // ==================================================================================================
    // do건국 (PHP :3302-3318) — 2 draws type-THEN-color; che_건국 {nationName, nationType, colorType=INDEX}.
    // ==================================================================================================

    @Test
    fun `do건국 draws type-THEN-color and emits che_건국 with the substring nationName plus a clear delta`() {
        assertEquals(33, GetNationColors().size, "m6 — GetNationColors() MUST be 33 (the nextInt range parity target)")
        val kv = ArrayList<KvDelta>()
        val rng = RecordingRng("found")
        val ctx = ctxOf(rng, instance(nationId = 0), recordGeneralKv = { id, k, v -> kv.add(KvDelta(id, k, v)) })

        val chosen = GenFoundFamily.do건국(ctx)(null)!!
        assertEquals("che_건국", chosen.actionCode)
        // PHP :3304-3305 — exactly two choices, type FIRST then color.
        assertEquals(
            listOf(RecordingRng.Draw("choice", -1.0), RecordingRng.Draw("choice", -1.0)),
            rng.draws,
            "type-THEN-color: two choice draws (PHP :3304-3305)",
        )
        // PHP :3307 — nationName = "㉿" + mb_substr(name,1) (drop the surname's first char).
        assertEquals("㉿조맹덕", chosen.args["nationName"])
        assertTrue(chosen.args["nationType"] in GameConst.availableNationType, "nationType from availableNationType")
        val colorIdx = chosen.args["colorType"] as Int
        assertTrue(colorIdx in 0 until 33, "colorType is a 0..32 palette INDEX (PHP choice(array_keys))")
        assertEquals(listOf(KvDelta(1, "movingTargetCityID", null)), kv, "PHP :3315 clears movingTargetCityID on pass")
    }

    @Test
    fun `do건국 still draws both picks then returns null with NO delta when the gate denies`() {
        val kv = ArrayList<KvDelta>()
        val rng = RecordingRng("found-deny")
        val ctx = ctxOf(rng, instance(nationId = 0), candidateAllowed = { _, _ -> false },
            recordGeneralKv = { id, k, v -> kv.add(KvDelta(id, k, v)) })

        assertNull(GenFoundFamily.do건국(ctx)(null), "gate deny → :3311 null")
        assertEquals(2, rng.draws.size, "the two picks happen BEFORE the gate (PHP :3304-3305 precede :3311)")
        assertTrue(kv.isEmpty(), "the clear delta fires only after the gate passes (PHP :3315)")
    }

    // ==================================================================================================
    // do선양 (PHP :3320-3332) — ZERO draws; che_선양 {destGeneralID = min(no) F-QUAR substitute}.
    // ==================================================================================================

    @Test
    fun `do선양 emits che_선양 with the min(no) substitute destGeneralID and ZERO draws`() {
        // Same nation, npc != 5 → the min(no) candidate is general 7.
        val pool = listOf(gen(id = 9, nationId = 3, npcType = 6), gen(id = 7, nationId = 3, npcType = 6), gen(id = 5, nationId = 3, npcType = 5))
        val rng = RecordingRng("abdicate")
        val ctx = ctxOf(rng, instance(nationId = 3), nationId = 3, seonyangCandidates = pool,
            generalPolicy = AutorunGeneralPolicy(npcType = 6, nationId = 3))

        val chosen = GenFoundFamily.do선양(ctx)(null)!!
        assertEquals("che_선양", chosen.actionCode)
        assertEquals(7, chosen.args["destGeneralID"], "min(no) over (nation==3 && npc!=5): 7 (not the npc==5 id 5)")
        assertTrue(rng.draws.isEmpty(), "ORDER BY RAND substitute consumes ZERO DRBG draws (F-QUAR)")
    }

    @Test
    fun `do선양 with an empty candidate pool emits a null destGeneralID and is gate-rejected`() {
        val rng = RecordingRng("abdicate-empty")
        val ctx = ctxOf(rng, instance(nationId = 3), nationId = 3, seonyangCandidates = emptyList(),
            candidateAllowed = { _, args -> args["destGeneralID"] != null })

        assertNull(GenFoundFamily.do선양(ctx)(null), "null destGeneralID → gate deny → null (PHP :3327)")
        assertTrue(rng.draws.isEmpty())
    }

    @Test
    fun `do선양 replays a recorded SQL RAND selection without a DRBG draw`() {
        val pool = listOf(
            gen(id = 9, nationId = 3, npcType = 6),
            gen(id = 7, nationId = 3, npcType = 6),
            gen(id = 5, nationId = 3, npcType = 5),
        )
        val rng = RecordingRng("abdicate-recorded")
        val selector = ExternalSqlRandSelector { branch, actor, year, month, candidates ->
            assertEquals(ExternalSqlRandBranch.SEONYANG_DEST_GENERAL, branch)
            assertEquals(1, actor)
            assertEquals(200, year)
            assertEquals(1, month)
            assertEquals(listOf(9, 7), candidates)
            9
        }
        val ctx = ctxOf(
            rng,
            instance(nationId = 3),
            nationId = 3,
            seonyangCandidates = pool,
            externalSqlRandSelector = selector,
        )

        val chosen = GenFoundFamily.do선양(ctx)(null)!!
        assertEquals(9, chosen.args["destGeneralID"])
        assertTrue(rng.draws.isEmpty())
    }

    // ==================================================================================================
    // do거병 (PHP :3217-3288) — up to 4 draws; BFS dist-3 scan; che_거병.
    // ==================================================================================================

    @Test
    fun `do거병 early-returns with ZERO draws when makelimit is set`() {
        val rng = RecordingRng("reb-makelimit")
        val ctx = ctxOf(rng, instance(nationId = 0), selfMakeLimit = 1, selfNpcType = 2)
        assertNull(GenFoundFamily.do거병(ctx)(null), "makelimit → :3221 null BEFORE any draw")
        assertTrue(rng.draws.isEmpty())
    }

    @Test
    fun `do거병 early-returns with ZERO draws when npcType greater than 2`() {
        val rng = RecordingRng("reb-npc")
        val ctx = ctxOf(rng, instance(nationId = 0), selfNpcType = 6)
        assertNull(GenFoundFamily.do거병(ctx)(null), "npcType>2 → :3224 null BEFORE any draw")
        assertTrue(rng.draws.isEmpty())
    }

    @Test
    fun `do거병 early-returns with ZERO draws when can건국 is false`() {
        val rng = RecordingRng("reb-cannot")
        val gp = AutorunGeneralPolicy(npcType = 2, nationId = 0).also { it.can건국 = false }
        val ctx = ctxOf(rng, instance(nationId = 0), selfNpcType = 2, generalPolicy = gp)
        assertNull(GenFoundFamily.do거병(ctx)(null), "!can건국 → :3227 null BEFORE any draw")
        assertTrue(rng.draws.isEmpty())
    }

    @Test
    fun `do거병 preserves non-foundable city draw before occupied BFS filtering`() {
        val rng = RecordingRng("reb-occupied-current")
        val ctx = ctxOf(
            rng,
            instance(nationId = 0),
            selfNpcType = 2,
            selfCityId = 100,
            selfCityLevel = 1,
            foundOccupiedCities = (1..3000).toSet(),
        )
        assertNull(GenFoundFamily.do거병(ctx)(null))
        assertEquals(0.5, rng.draws.first().prob)
    }

    @Test
    fun `do거병 on a non-foundable city draws the 0_5 nextBit FIRST`() {
        // selfCityLevel=1 (non-foundable) → :3232 `&&` reaches nextBool(0.5)=nextBit as the FIRST draw.
        val rng = RecordingRng("reb-nonfoundable")
        val ctx = ctxOf(rng, instance(nationId = 0), selfNpcType = 2, selfCityLevel = 1)
        GenFoundFamily.do거병(ctx)(null) // result depends on the seed; we only assert the FIRST draw shape.
        assertEquals(0.5, rng.draws.first().prob, "the FIRST draw is the :3232 nextBool(0.5)=nextBit on a non-foundable city")
    }

    @Test
    fun `do거병 on a foundable city skips the 0_5 draw and proceeds to the BFS scan`() {
        // selfCityLevel=5 (foundable) → :3232 `&&` short-circuits → no 0.5 draw; the first draw is the BFS/threshold.
        val rng = RecordingRng("reb-foundable")
        // No reachable dist-3 foundable city (occupied everything) → BFS yields no candidate → :3264 null, ZERO draws.
        val ctx = ctxOf(
            rng, instance(nationId = 0), selfNpcType = 2, selfCityLevel = 5,
            foundOccupiedCities = (1..3000).toSet(),
        )
        assertNull(GenFoundFamily.do거병(ctx)(null), "no foundable near city → :3264 null")
        assertTrue(rng.draws.none { it.prob == 0.5 && it.kind == "nextBit" }, "foundable city → the :3232 0.5 draw is suppressed")
    }

    // ==================================================================================================
    // do국가선택 (PHP :3334-3401) — 오랑캐 substitute → 0.3 gate → 임관 / 0.2 move / null.
    // ==================================================================================================

    @Test
    fun `do국가선택 npc==9 오랑캐 emits che_임관 with the min(no) ruler nation and ZERO DRBG draws`() {
        // PHP :3343-3355 — npc==9 → ORDER BY RAND ruler substitute (0-draw), emit che_임관.
        val rulers = listOf(gen(id = 11, nationId = 5, npcType = 9, officerLevel = 12), gen(id = 8, nationId = 4, npcType = 9, officerLevel = 12))
        val rng = RecordingRng("join-orankae")
        val ctx = ctxOf(rng, instance(nationId = 0), selfNpcType = 9, orankaeRulerCandidates = rulers)

        val chosen = GenFoundFamily.do국가선택(ctx)(null)!!
        assertEquals("che_임관", chosen.actionCode)
        assertEquals(4, chosen.args["destNationID"], "nation of min(no): general 8 → nation 4 (F-QUAR substitute)")
        assertTrue(rng.draws.isEmpty(), "the ORDER BY RAND ruler substitute consumes ZERO DRBG draws")
    }

    @Test
    fun `do국가선택 replays a recorded SQL RAND nation without a DRBG draw`() {
        val rulers = listOf(
            gen(id = 11, nationId = 5, npcType = 9, officerLevel = 12),
            gen(id = 8, nationId = 4, npcType = 9, officerLevel = 12),
        )
        val rng = RecordingRng("join-orankae-recorded")
        val selector = ExternalSqlRandSelector { branch, actor, year, month, candidates ->
            assertEquals(ExternalSqlRandBranch.ORANKAE_RULER_NATION, branch)
            assertEquals(1, actor)
            assertEquals(200, year)
            assertEquals(1, month)
            assertEquals(listOf(5, 4), candidates)
            5
        }
        val ctx = ctxOf(
            rng,
            instance(nationId = 0),
            selfNpcType = 9,
            orankaeRulerCandidates = rulers,
            externalSqlRandSelector = selector,
        )

        val chosen = GenFoundFamily.do국가선택(ctx)(null)!!
        assertEquals(5, chosen.args["destNationID"])
        assertTrue(rng.draws.isEmpty())
    }

    @Test
    fun `do국가선택 0_3 gate FALSE then 0_2 gate FALSE returns null with two draws`() {
        // Find a seed where :3358 nextBool(0.3)=false AND :3390 nextBool(0.2)=false → null.
        var found = false
        for (s in 0..400) {
            val rng = RecordingRng("nc-null-$s")
            val ctx = ctxOf(rng, instance(nationId = 0), selfNpcType = 6, nationCount = 5, notFullNationCount = 5)
            val out = GenFoundFamily.do국가선택(ctx)(null)
            if (out == null && rng.draws.size == 2 &&
                rng.draws[0] == RecordingRng.Draw("nextBool", 0.3) &&
                rng.draws[1] == RecordingRng.Draw("nextBool", 0.2)
            ) {
                found = true; break
            }
        }
        assertTrue(found, "a 0.3-false then 0.2-false seed yields null after exactly [nextBool(0.3), nextBool(0.2)]")
    }

    @Test
    fun `do국가선택 affinity 999 aborts the 임관 sub-tree right after the 0_3 gate with no further draw`() {
        // Find a seed where :3358 nextBool(0.3)=true → affinity==999 → :3361 null with exactly one draw.
        var found = false
        for (s in 0..400) {
            val rng = RecordingRng("nc-aff-$s")
            val ctx = ctxOf(rng, instance(nationId = 0), selfNpcType = 6, selfAffinity = 999,
                nationCount = 5, notFullNationCount = 5)
            val out = GenFoundFamily.do국가선택(ctx)(null)
            if (out == null && rng.draws.size == 1 && rng.draws[0] == RecordingRng.Draw("nextBool", 0.3)) {
                // confirm this seed's 0.3 was TRUE by re-rolling the same seed
                found = true; break
            }
        }
        assertTrue(found, "affinity==999 after a TRUE 0.3 gate → null with ONLY the nextBool(0.3) draw (PHP :3359-3361)")
    }

    // ==================================================================================================
    // doNPC사망대비 (PHP :3403-3434) — killturn>5 null; 재야 search/tour; in-nation 물자조달/헌납.
    // ==================================================================================================

    @Test
    fun `doNPC사망대비 returns null with ZERO draws when killturn greater than 5`() {
        val rng = RecordingRng("death-killturn")
        val ctx = ctxOf(rng, instance(nationId = 0), selfKillturn = 6)
        assertNull(GenFoundFamily.bodies(ctx).getValue("NPC사망대비")(null), "killturn>5 → :3407 null")
        assertTrue(rng.draws.isEmpty())
    }

    @Test
    fun `doNPC사망대비 재야 keeps che_인재탐색 when the gate passes and the 0_5 bit is false`() {
        // nationID==0, gate passes → :3413 `||` draws nextBool()=nextBit. Find a false-bit seed.
        var chosen: ChosenCommand? = null
        var draws: List<RecordingRng.Draw> = emptyList()
        for (s in 0..200) {
            val rng = RecordingRng("death-keep-$s")
            val ctx = ctxOf(rng, instance(nationId = 0), selfKillturn = 0)
            val out = GenFoundFamily.bodies(ctx).getValue("NPC사망대비")(null)
            if (out?.actionCode == "che_인재탐색") { chosen = out; draws = rng.draws.toList(); break }
        }
        assertEquals("che_인재탐색", chosen!!.actionCode)
        // PHP :3413 `nextBool()` defaults prob=0.5; RandUtil.nextBool(0.5) takes the nextBit path internally,
        // so the recorded TOP-LEVEL draw is nextBool(0.5) (the inner nextBit is consumed under it, not re-recorded).
        assertEquals(listOf(RecordingRng.Draw("nextBool", 0.5)), draws, "the :3413 `||` draw is nextBool(0.5)=nextBit")
    }

    @Test
    fun `doNPC사망대비 재야 switches to che_견문 with ZERO draws when the che_인재탐색 gate fails`() {
        // `!hasFullConditionMet()` is true → the `||` short-circuits → ZERO draws, che_견문.
        val rng = RecordingRng("death-tour")
        val ctx = ctxOf(rng, instance(nationId = 0), selfKillturn = 0,
            candidateAllowed = { code, _ -> code != "che_인재탐색" })
        val chosen = GenFoundFamily.bodies(ctx).getValue("NPC사망대비")(null)!!
        assertEquals("che_견문", chosen.actionCode, "gate fail → :3414 che_견문")
        assertTrue(rng.draws.isEmpty(), "the `||` suppresses the :3413 draw when the gate fails")
    }

    @Test
    fun `doNPC사망대비 in-nation with zero gold and rice emits che_물자조달`() {
        val rng = RecordingRng("death-supply")
        val ctx = ctxOf(rng, instance(nationId = 3), nationId = 3, selfKillturn = 0, selfGold = 0, selfRice = 0,
            generalPolicy = AutorunGeneralPolicy(npcType = 6, nationId = 3))
        val chosen = GenFoundFamily.bodies(ctx).getValue("NPC사망대비")(null)!!
        assertEquals("che_물자조달", chosen.actionCode, "gold+rice==0 → :3420 che_물자조달")
        assertTrue(rng.draws.isEmpty())
    }

    @Test
    fun `doNPC사망대비 in-nation with more gold emits che_헌납 isGold true at maxResourceActionAmount`() {
        val rng = RecordingRng("death-tribute")
        val ctx = ctxOf(rng, instance(nationId = 3), nationId = 3, selfKillturn = 0, selfGold = 5000, selfRice = 1000,
            generalPolicy = AutorunGeneralPolicy(npcType = 6, nationId = 3))
        val chosen = GenFoundFamily.bodies(ctx).getValue("NPC사망대비")(null)!!
        assertEquals("che_헌납", chosen.actionCode, "gold>=rice → :3424 che_헌납 isGold=true")
        assertEquals(true, chosen.args["isGold"])
        assertEquals(GameConst.maxResourceActionAmount, chosen.args["amount"])
        assertTrue(rng.draws.isEmpty())
    }

    @Test
    fun `doNPC사망대비 in-nation with more rice emits che_헌납 isGold false`() {
        val rng = RecordingRng("death-tribute-rice")
        val ctx = ctxOf(rng, instance(nationId = 3), nationId = 3, selfKillturn = 0, selfGold = 1000, selfRice = 5000,
            generalPolicy = AutorunGeneralPolicy(npcType = 6, nationId = 3))
        val chosen = GenFoundFamily.bodies(ctx).getValue("NPC사망대비")(null)!!
        assertEquals("che_헌납", chosen.actionCode, "rice>gold → :3429 che_헌납 isGold=false")
        assertEquals(false, chosen.args["isGold"])
        assertEquals(GameConst.maxResourceActionAmount, chosen.args["amount"])
    }

    // ==================================================================================================
    // do중립 (PHP :3436-3467) — TERMINAL fallback (never null).
    // ==================================================================================================

    @Test
    fun `do중립 재야 keeps che_인재탐색 when the gate passes and the 0_8 draw is false`() {
        var chosen: ChosenCommand? = null
        var draws: List<RecordingRng.Draw> = emptyList()
        for (s in 0..200) {
            val rng = RecordingRng("neut-keep-$s")
            val ctx = ctxOf(rng, instance(nationId = 0))
            val out = GenFoundFamily.do중립(ctx)(null)
            if (out.actionCode == "che_인재탐색") { chosen = out; draws = rng.draws.toList(); break }
        }
        assertEquals("che_인재탐색", chosen!!.actionCode)
        assertEquals(listOf(RecordingRng.Draw("nextBool", 0.8)), draws, "the :3441 `||` draw is nextBool(0.8) (a float, not a bit)")
    }

    @Test
    fun `do중립 재야 switches to che_견문 with ZERO draws when the che_인재탐색 gate fails`() {
        val rng = RecordingRng("neut-tour")
        val ctx = ctxOf(rng, instance(nationId = 0), candidateAllowed = { code, _ -> code != "che_인재탐색" })
        val chosen = GenFoundFamily.do중립(ctx)(null)
        assertEquals("che_견문", chosen.actionCode, "gate fail → :3442 che_견문")
        assertTrue(rng.draws.isEmpty(), "the `||` suppresses the :3441 0.8 draw when the gate fails")
    }

    @Test
    fun `do중립 in-nation draws one choice over the two-element candidate and emits the pick`() {
        // nation gold/rice both >= req → candidate = [che_물자조달, che_인재탐색] → one choice draw.
        val rng = RecordingRng("neut-innation")
        val ctx = ctxOf(rng, instance(nationId = 3, gold = 999999, rice = 999999), nationId = 3,
            generalPolicy = AutorunGeneralPolicy(npcType = 6, nationId = 3))
        val chosen = GenFoundFamily.do중립(ctx)(null)
        assertEquals(listOf(RecordingRng.Draw("choice", -1.0)), rng.draws, "exactly one :3458 choice over the candidate list")
        assertTrue(chosen.actionCode in listOf("che_물자조달", "che_인재탐색"))
    }

    @Test
    fun `do중립 in-nation narrows to che_물자조달 only when nation gold is below req`() {
        // nation.gold < reqNationGold → candidate narrows to [che_물자조달] → choice still draws once.
        val np = AutorunNationPolicy(npcType = 2, tech = 0, develcost = 10)
        val rng = RecordingRng("neut-poor")
        val ctx = ctxOf(rng, instance(nationId = 3, gold = 0, rice = 999999), nationId = 3, nationPolicy = np,
            generalPolicy = AutorunGeneralPolicy(npcType = 6, nationId = 3))
        val chosen = GenFoundFamily.do중립(ctx)(null)
        assertEquals("che_물자조달", chosen.actionCode, "gold<reqNationGold → narrowed to [che_물자조달] (PHP :3450-3452)")
        assertEquals(listOf(RecordingRng.Draw("choice", -1.0)), rng.draws, "choice draws once even on a single-element list")
    }
}
