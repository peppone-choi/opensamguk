package opensamguk.logic.actions

import opensamguk.common.rng.LiteHashDrbg
import opensamguk.common.rng.RandUtil
import opensamguk.common.rng.serializeSeed
import opensamguk.logic.domain.City
import opensamguk.logic.domain.General
import opensamguk.logic.domain.Nation
import opensamguk.logic.domain.WorldEnv
import opensamguk.logic.domain.metaInt
import opensamguk.logic.domestic.criticalRatioDomestic
import opensamguk.logic.domestic.criticalScoreEx
import opensamguk.logic.domestic.getDomesticExpLevelBonus
import opensamguk.logic.stats.GeneralActionPipeline
import opensamguk.logic.stats.getStatValue
import opensamguk.logic.util.clamp
import opensamguk.logic.util.numberFormat
import opensamguk.logic.util.phpRound
import opensamguk.logic.util.valueFit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Port-faithful resolve test for the shared CommerceInvestment algorithm (PHP che_상업투자.php run()).
 * Pins the EXACT draw order (DRAW1 nextRange(0.8,1.2) → DRAW2 choiceUsingWeight(fail,success,normal)
 * → DRAW3 criticalScoreEx), the mutation order, the meta increments, the gold decrement, and the
 * literal log string (with <C>, number_format comma grouping, name WITH space, <1>date</> suffix).
 *
 * The expected values are produced by REPLAYING the identical algorithm with a parallel
 * identically-seeded RNG (structural correctness + determinism guard). The exact float golden
 * is pinned separately by the AREA G PHP oracle (G1) once the captured hiddenSeed lands.
 */
class CommerceInvestmentResolveTest {

    private val pipeline = GeneralActionPipeline()

    // FIXTURE INPUT — replaced by the G2-captured golden hiddenSeed (UniqueConst::$hiddenSeed) before lock.
    private val FIXTURE_HIDDEN_SEED = "00000000000000000000000000000000"  // placeholder; G1 captures the real per-game seed

    private val MONTH = 3   // RNG seed component 4 + the ActionLogger MONTH-format prefix month

    // serializeSeed(hiddenSeed, "generalCommand", year, month, generalId, shortClassName = registry key)
    private fun freshRng() = RandUtil(
        LiteHashDrbg(serializeSeed(FIXTURE_HIDDEN_SEED, "generalCommand", 190, MONTH, 42, "che_상업투자")))

    private fun general() = General(
        id = 42,
        nationId = 1,
        cityId = 7,
        leadership = 70,
        strength = 70,
        intel = 80,
        injury = 0,
        experience = 0.0,
        dedication = 0.0,
        officerLevel = 0,
        gold = 1000,
        rice = 1000,
        meta = linkedMapOf("explevel" to 10, "intel_exp" to 3, "max_domestic_critical" to 0.0),
    )

    private fun city() = City(
        id = 7,
        nationId = 1,
        level = 5,
        commerce = 1000, commerceMax = 20000,
        agriculture = 1000, agricultureMax = 20000,
        supplyState = 1,
        frontState = 0,            // not a front city — no debuff in this fixture
        trust = 50.0,              // INTEGER-valued (G1 invariant) but typed Double (PHP FLOAT)
        meta = linkedMapOf(),
    )

    private val nation = Nation(id = 1, level = 2, capitalCityId = 99)
    private val env = WorldEnv(year = 190, startYear = 184, develCost = 120)
    private val date = "12:34"

    private fun action() = CommerceInvestment(
        pipeline = pipeline, cityKey = "comm", statKey = "intel",
        actionKey = "상업", name = "상업 투자")

    /** Independent replay of the run() algorithm with a parallel identically-seeded RNG. */
    private data class Expected(
        val pick: String, val nextCommerce: Int, val nextGold: Int, val nextIntelExp: Int, val log: String)

    private fun replay(): Expected {
        val g = general(); val c = city()
        val rng = freshRng()
        val act = action()

        val reqGold = act.getCost(g, env)
        val trust = valueFit(c.trust, 50.0)

        // calcBaseScore (DRAW 1)
        var score0 = getStatValue(g, "intelligence", pipeline, 255, withInjury = true, useFloor = false)
        score0 *= trust / 100.0
        score0 *= getDomesticExpLevelBonus(metaInt(g.meta, "explevel"))
        score0 *= rng.nextRange(0.8, 1.2)            // DRAW 1
        var score = valueFit(score0, 1.0)

        val ratio = criticalRatioDomestic(g, "intel", pipeline, 255)
        var successRatio = ratio.success; var failRatio = ratio.fail
        if (trust < 80) successRatio *= trust / 80.0
        successRatio = clamp(successRatio, 0.0, 1.0)
        failRatio = clamp(failRatio, 0.0, 1.0 - successRatio)
        val normalRatio = 1.0 - failRatio - successRatio

        val pick = rng.choiceUsingWeight(linkedMapOf(   // DRAW 2
            "fail" to failRatio, "success" to successRatio, "normal" to normalRatio))
        score *= criticalScoreEx(rng, pick)             // DRAW 3
        val roundedScore = phpRound(score)

        val scoreText = numberFormat(roundedScore)
        // ActionLogger MONTH-format prefix (<C>●</>{month}월:) is applied by the context's addLog (G2 oracle).
        val body = when (pick) {
            "fail"    -> "상업 투자를 <span class='ev_failed'>실패</span>하여 <C>$scoreText</> 상승했습니다. <1>$date</>"
            "success" -> "상업 투자를 <S>성공</>하여 <C>$scoreText</> 상승했습니다. <1>$date</>"
            else      -> "상업 투자를 하여 <C>$scoreText</> 상승했습니다. <1>$date</>"
        }
        val log = "<C>●</>${MONTH}월:$body"
        // frontState=0 -> no debuff; cityScore == roundedScore
        val nextCommerce = valueFit((c.commerce + roundedScore).toDouble(), 0.0, c.commerceMax.toDouble()).toInt()
        val nextGold = maxOf(0, g.gold - reqGold)
        val nextIntelExp = metaInt(g.meta, "intel_exp") + 1
        return Expected(pick, nextCommerce, nextGold, nextIntelExp, log)
    }

    @Test
    fun `resolve mutates draft per PHP run draw and mutation order`() {
        val expected = replay()

        val draft = GeneralActionDraft(general(), city(), nation)
        val ctx = GeneralActionResolveContext(draft, freshRng(), env, MONTH, date)
        action().resolve(ctx)

        // (b) resolved city.commerce delta
        assertEquals(expected.nextCommerce, draft.city.commerce, "city.commerce")
        // (c) general.gold decremented by reqGold (floored at 0)
        assertEquals(expected.nextGold, draft.general.gold, "general.gold")
        // (d) meta["intel_exp"] incremented
        assertEquals(expected.nextIntelExp, metaInt(draft.general.meta, "intel_exp"), "intel_exp")
        // (e) EXACT log string (incl. <C>, comma grouping, <1>date</>)
        assertEquals(1, ctx.logs().size, "exactly one log")
        assertEquals(expected.log, ctx.logs()[0], "log string")
        // log carries the deterministic pick's marker
        when (expected.pick) {
            "fail" -> assertTrue(ctx.logs()[0].contains("ev_failed"), "fail log marker")
            "success" -> assertTrue(ctx.logs()[0].contains("<S>성공</>"), "success log marker")
            else -> assertTrue(ctx.logs()[0].contains("투자를 하여"), "normal log marker")
        }
        // experience/dedication are raw Double accumulators (no per-add rounding) — exp = score*0.7, ded = score*1.0
        assertTrue(draft.general.experience >= 0.0)
        assertTrue(draft.general.dedication >= draft.general.experience, "ded(=score) >= exp(=0.7*score)")
    }

    @Test
    fun `two runs with the same seed are byte-identical (determinism)`() {
        val a = GeneralActionDraft(general(), city(), nation)
        val ctxA = GeneralActionResolveContext(a, freshRng(), env, MONTH, date)
        action().resolve(ctxA)

        val b = GeneralActionDraft(general(), city(), nation)
        val ctxB = GeneralActionResolveContext(b, freshRng(), env, MONTH, date)
        action().resolve(ctxB)

        assertEquals(a.city.commerce, b.city.commerce)
        assertEquals(a.general.gold, b.general.gold)
        assertEquals(a.general.experience, b.general.experience)
        assertEquals(a.general.dedication, b.general.dedication)
        assertEquals(metaInt(a.general.meta, "intel_exp"), metaInt(b.general.meta, "intel_exp"))
        assertEquals(ctxA.logs(), ctxB.logs())
    }

    @Test
    fun `key derives from name with the space stripped`() {
        assertEquals("che_상업투자", action().key)
    }
}
