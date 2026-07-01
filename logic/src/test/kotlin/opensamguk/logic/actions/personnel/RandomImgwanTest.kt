package opensamguk.logic.actions.personnel

import opensamguk.common.rng.LiteHashDrbg
import opensamguk.common.rng.RandUtil
import opensamguk.common.rng.serializeSeed
import opensamguk.logic.actions.GeneralActionDraft
import opensamguk.logic.actions.GeneralActionResolveContext
import opensamguk.logic.domain.City
import opensamguk.logic.domain.General
import opensamguk.logic.domain.Nation
import opensamguk.logic.domain.WorldEnv
import opensamguk.logic.stats.GeneralActionPipeline
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Port-faithful test for che_랜덤임관 (`che_랜덤임관.php:113-289`) — the highest parity-risk personnel
 * command. Pins:
 *   - NPC-foreign branch: shuffle($nations) FIRST → score loop → choice(talk). The score loop draws ONE
 *     nextFloat1 per nation, keeps the MIN score (maxScore init 1<<30), and BUG-FAITHFULLY accumulates
 *     `$affinity` ACROSS iterations (each iter: affinity = abs(affinity - testNation.affinity);
 *     affinity = min(affinity, abs(affinity - 150)); score = log2(affinity+1) + nextFloat1 + sqrt(gennum/allGen)).
 *   - ELSE branch: weighted pair (1/(warpower+develpower))^3 via choiceUsingWeightPair, then choice(talk).
 *   - draw order [shuffle|weighted] → choice(talk); determinism with a fixed seed.
 */
class RandomImgwanTest {

    private val pipeline = GeneralActionPipeline()
    private val MONTH = 4
    private val FIXTURE_HIDDEN_SEED = "00000000000000000000000000000000"
    private fun freshRng() = RandUtil(
        LiteHashDrbg(serializeSeed(FIXTURE_HIDDEN_SEED, "generalCommand", 200, MONTH, 42, "che_랜덤임관")))
    private val env = WorldEnv(year = 200, startYear = 184, develCost = 120)
    private val date = "11:11"

    private fun neutralGeneral(affinity: Int = 50, npc: Int = 2) = General(
        id = 42, nationId = 0, cityId = 0,
        leadership = 70, strength = 70, intel = 80, injury = 0,
        experience = 0.0, dedication = 0.0, officerLevel = 0, gold = 1000, rice = 1000,
        npcType = npc,
        meta = linkedMapOf("explevel" to 10, "affinity" to affinity, "name" to "방랑객"),
    )

    private fun lordCityOf(nationId: Int, cityId: Int) = City(
        id = cityId, nationId = nationId, level = 5,
        commerce = 1, commerceMax = 1, agriculture = 1, agricultureMax = 1,
        supplyState = 1, frontState = 0, trust = 50.0,
    )

    // -------- NPC-foreign branch: in-loop affinity accumulation + MIN-score pick --------

    @Test
    fun `npc-foreign branch reproduces in-loop affinity accumulation and picks min score`() {
        val candidates = listOf(
            RandomImgwanNpcCandidate(nationId = 11, name = "위", gennum = 4, affinity = 10, lordCityId = 110),
            RandomImgwanNpcCandidate(nationId = 12, name = "촉", gennum = 6, affinity = 90, lordCityId = 120),
            RandomImgwanNpcCandidate(nationId = 13, name = "오", gennum = 2, affinity = 140, lordCityId = 130),
        )
        val draft = GeneralActionDraft(neutralGeneral(affinity = 50, npc = 2), City(
            id = 0, nationId = 0, level = 0, commerce = 0, commerceMax = 0,
            agriculture = 0, agricultureMax = 0, supplyState = 1, frontState = 0, trust = 0.0), null)

        val cmd = CheRandomImgwan(pipeline, npcCandidates = candidates, weightedCandidates = emptyList(), useNpcForeignBranch = true)
        val ctx = GeneralActionResolveContext(draft, freshRng(), env, MONTH, date)
        cmd.resolve(ctx)

        // independent replay of the EXACT PHP loop with a parallel identically-seeded rng.
        val expected = replayNpcBranch(candidates, startAffinity = 50)
        assertEquals(expected.nationId, draft.general.nationId, "picked nation matches replay")
        assertEquals(expected.lordCityId, draft.general.cityId, "joined city = picked lord's city")
        assertEquals(1, draft.general.officerLevel, "officer_level 1")
    }

    private data class NpcPick(val nationId: Int, val lordCityId: Int)

    /** Mirror of che_랜덤임관.php:152-173 — shuffle FIRST, then per-nation nextFloat1, keep MIN, accumulate affinity. */
    private fun replayNpcBranch(candidates: List<RandomImgwanNpcCandidate>, startAffinity: Int): NpcPick {
        val rng = freshRng()
        val shuffled = rng.shuffle(candidates)
        val allGen = shuffled.sumOf { it.gennum }.toDouble()
        var maxScore = (1 shl 30).toDouble()
        var affinity = startAffinity
        var pick: RandomImgwanNpcCandidate? = null
        for (n in shuffled) {
            affinity = abs(affinity - n.affinity)
            affinity = min(affinity, abs(affinity - 150))
            var score = ln((affinity + 1).toDouble()) / ln(2.0)
            score += rng.nextFloat1()
            score += sqrt(n.gennum / allGen)
            if (score < maxScore) { maxScore = score; pick = n }
        }
        return NpcPick(pick!!.nationId, pick.lordCityId)
    }

    @Test
    fun `npc-foreign branch draw order is shuffle then per-nation float then choice talk`() {
        val candidates = listOf(
            RandomImgwanNpcCandidate(nationId = 11, name = "위", gennum = 4, affinity = 10, lordCityId = 110),
            RandomImgwanNpcCandidate(nationId = 12, name = "촉", gennum = 6, affinity = 90, lordCityId = 120),
        )
        val draft = GeneralActionDraft(neutralGeneral(), City(
            id = 0, nationId = 0, level = 0, commerce = 0, commerceMax = 0,
            agriculture = 0, agricultureMax = 0, supplyState = 1, frontState = 0, trust = 0.0), null)
        val ctx = GeneralActionResolveContext(draft, freshRng(), env, MONTH, date)
        CheRandomImgwan(pipeline, candidates, emptyList(), useNpcForeignBranch = true).resolve(ctx)

        // replay the same draw order and assert the talk token chosen matches.
        val rng = freshRng()
        rng.shuffle(candidates)
        repeat(candidates.size) { rng.nextFloat1() }
        val talk = rng.choice(TALK_LIST)
        assertTrue(ctx.globalActionLogs().any { it.contains(talk) }, "global log carries the replayed talk token '$talk'; got ${ctx.globalActionLogs()}")
    }

    // -------- ELSE branch: weighted pair --------

    @Test
    fun `else branch picks via weighted pair and joins via choiceUsingWeightPair`() {
        val weighted = listOf(
            RandomImgwanWeightedCandidate(nationId = 21, name = "위", gennum = 3, warpower = 100.0, develpower = 50.0, npcLeq1 = false, lordCityId = 210),
            RandomImgwanWeightedCandidate(nationId = 22, name = "촉", gennum = 4, warpower = 200.0, develpower = 80.0, npcLeq1 = false, lordCityId = 220),
        )
        val draft = GeneralActionDraft(neutralGeneral(npc = 0), City(
            id = 0, nationId = 0, level = 0, commerce = 0, commerceMax = 0,
            agriculture = 0, agricultureMax = 0, supplyState = 1, frontState = 0, trust = 0.0), null)
        val ctx = GeneralActionResolveContext(draft, freshRng(), env, MONTH, date)
        CheRandomImgwan(pipeline, emptyList(), weighted, useNpcForeignBranch = false).resolve(ctx)

        // replay
        val rng = freshRng()
        val pairs = weighted.map { it to (1.0 / (it.warpower + it.develpower)).let { v -> v * v * v } }
        val picked = rng.choiceUsingWeightPair(pairs)
        assertEquals(picked.nationId, draft.general.nationId, "weighted-pair pick matches replay")
        assertEquals(picked.lordCityId, draft.general.cityId, "joined lord's city")
    }

    @Test
    fun `global join log uses the actor name supplied by the engine context`() {
        val weighted = listOf(
            RandomImgwanWeightedCandidate(
                nationId = 21,
                name = "견초",
                gennum = 3,
                warpower = 100.0,
                develpower = 50.0,
                npcLeq1 = false,
                lordCityId = 210,
            ),
        )
        val actorWithoutNameMeta = neutralGeneral(npc = 2).copy(
            meta = linkedMapOf("explevel" to 10, "affinity" to 50),
        )
        val draft = GeneralActionDraft(actorWithoutNameMeta, City(
            id = 0, nationId = 0, level = 0, commerce = 0, commerceMax = 0,
            agriculture = 0, agricultureMax = 0, supplyState = 1, frontState = 0, trust = 0.0), null)
        val ctx = GeneralActionResolveContext(draft, freshRng(), env, MONTH, date, generalName = "견초")

        CheRandomImgwan(pipeline, emptyList(), weighted, useNpcForeignBranch = false).resolve(ctx)

        val globalLog = ctx.globalActionLogs().single()
        assertTrue(globalLog.startsWith("<C>●</>${MONTH}월:<Y>견초</>가 "), "actor name should not be blank: $globalLog")
        assertTrue(globalLog.contains("<D><b>견초</b></>에 <S>임관</>했습니다."), "dest nation log mismatch: $globalLog")
    }

    // -------- exp branch + determinism --------

    @Test
    fun `exp is 700 below genLimit else 100 (npc branch)`() {
        val below = listOf(RandomImgwanNpcCandidate(nationId = 11, name = "위", gennum = 4, affinity = 10, lordCityId = 110))
        val d1 = GeneralActionDraft(neutralGeneral(), City(
            id = 0, nationId = 0, level = 0, commerce = 0, commerceMax = 0,
            agriculture = 0, agricultureMax = 0, supplyState = 1, frontState = 0, trust = 0.0), null)
        CheRandomImgwan(pipeline, below, emptyList(), useNpcForeignBranch = true)
            .resolve(GeneralActionResolveContext(d1, freshRng(), env, MONTH, date))
        assertEquals(700.0, d1.general.experience, "gennum 4 < 10 → exp 700")

        val atLimit = listOf(RandomImgwanNpcCandidate(nationId = 11, name = "위", gennum = 10, affinity = 10, lordCityId = 110))
        val d2 = GeneralActionDraft(neutralGeneral(), City(
            id = 0, nationId = 0, level = 0, commerce = 0, commerceMax = 0,
            agriculture = 0, agricultureMax = 0, supplyState = 1, frontState = 0, trust = 0.0), null)
        CheRandomImgwan(pipeline, atLimit, emptyList(), useNpcForeignBranch = true)
            .resolve(GeneralActionResolveContext(d2, freshRng(), env, MONTH, date))
        assertEquals(100.0, d2.general.experience, "gennum 10 >= 10 → exp 100")
    }

    @Test
    fun `no candidates yields the no-nation log and no transition`() {
        val draft = GeneralActionDraft(neutralGeneral(), City(
            id = 0, nationId = 0, level = 0, commerce = 0, commerceMax = 0,
            agriculture = 0, agricultureMax = 0, supplyState = 1, frontState = 0, trust = 0.0), null)
        val ctx = GeneralActionResolveContext(draft, freshRng(), env, MONTH, date)
        CheRandomImgwan(pipeline, emptyList(), emptyList(), useNpcForeignBranch = false).resolve(ctx)

        assertEquals(0, draft.general.nationId, "no transition")
        assertTrue(ctx.logs().any { it.contains("임관 가능한 국가가 없습니다.") }, "no-nation log; got ${ctx.logs()}")
    }

    @Test
    fun `two runs with the same seed are byte-identical`() {
        val candidates = listOf(
            RandomImgwanNpcCandidate(nationId = 11, name = "위", gennum = 4, affinity = 10, lordCityId = 110),
            RandomImgwanNpcCandidate(nationId = 12, name = "촉", gennum = 6, affinity = 90, lordCityId = 120),
            RandomImgwanNpcCandidate(nationId = 13, name = "오", gennum = 2, affinity = 140, lordCityId = 130),
        )
        fun run(): Triple<Int, Int, List<String>> {
            val d = GeneralActionDraft(neutralGeneral(), City(
                id = 0, nationId = 0, level = 0, commerce = 0, commerceMax = 0,
                agriculture = 0, agricultureMax = 0, supplyState = 1, frontState = 0, trust = 0.0), null)
            val c = GeneralActionResolveContext(d, freshRng(), env, MONTH, date)
            CheRandomImgwan(pipeline, candidates, emptyList(), useNpcForeignBranch = true).resolve(c)
            return Triple(d.general.nationId, d.general.cityId, c.globalActionLogs())
        }
        assertEquals(run(), run(), "deterministic with a fixed seed")
    }

    @Test
    fun `key name lotteryActionName`() {
        val a = CheRandomImgwan(pipeline, emptyList(), emptyList(), useNpcForeignBranch = false)
        assertEquals("che_랜덤임관", a.key)
        assertEquals("무작위 국가로 임관", a.name)
        assertEquals("che_랜덤임관", a.rawClassName)
        assertEquals("무작위 국가로 임관", a.lotteryActionName)
    }

    companion object {
        val TALK_LIST = listOf(
            "어쩌다 보니", "인연이 닿아", "발길이 닿는 대로", "소문을 듣고", "점괘에 따라", "천거를 받아",
            "유명한", "뜻을 펼칠 곳을 찾아", "고향에 가까운", "천하의 균형을 맞추기 위해", "오랜 은거를 마치고",
        )
    }
}
