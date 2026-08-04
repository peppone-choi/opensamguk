package opensamguk.logic.actions.develop

import opensamguk.common.rng.LiteHashDrbg
import opensamguk.common.rng.RandUtil
import opensamguk.common.rng.serializeSeed
import opensamguk.logic.actions.GeneralActionDraft
import opensamguk.logic.actions.GeneralActionResolveContext
import opensamguk.logic.constraints.ConstraintContext
import opensamguk.logic.constraints.ConstraintMode
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
import opensamguk.logic.util.phpRound
import opensamguk.logic.util.valueFit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * DV3a — che_정착장려 (PHP che_정착장려.php). NOT a che_상업투자 subclass:
 *  - statKey='leadership' (getLeadership), cityKey='pop' → mutates city.population.
 *  - calcBaseScore has NO trust factor (vs 상업투자) — leadership × expLevelBonus × DRAW1.
 *  - run() has NO `if(trust<80) successRatio*=trust/80` adjustment.
 *  - getCost: develCost*2 as RICE (gold=0); ReqGeneralRice gate.
 *  - score is ROUNDED, exp/ded from the rounded score, then **score *= 10** (AFTER exp/ded + maxCrit).
 *  - city.pop clamp [0, pop_max].
 *  - log: `정착 장려{을} … 주민이 <C>{number_format(score*10,0)}</>명 증가했습니다.`
 *  - increaseVar('leadership_exp', 1). NO front-debuff.
 */
class CheJeongchakJangnyeoTest {

    private val pipeline = GeneralActionPipeline()
    private val FIXTURE_HIDDEN_SEED = "00000000000000000000000000000000"
    private val MONTH = 3
    private val date = "12:34"
    private val nation = Nation(id = 1, level = 2, capitalCityId = 99)
    private val env = WorldEnv(year = 190, startYear = 184, develCost = 120)

    private fun freshRng() = RandUtil(
        LiteHashDrbg(serializeSeed(FIXTURE_HIDDEN_SEED, "generalCommand", 190, MONTH, 42, "che_정착장려")))

    private fun general() = General(
        id = 42, nationId = 1, cityId = 7,
        leadership = 90, strength = 50, intel = 50,
        injury = 0, experience = 0.0, dedication = 0.0, officerLevel = 0,
        gold = 100000, rice = 100000,
        meta = linkedMapOf("explevel" to 10, "leadership_exp" to 5, "max_domestic_critical" to 0.0),
    )

    private fun city() = City(
        id = 7, nationId = 1, level = 5,
        commerce = 1000, commerceMax = 20000,
        agriculture = 1000, agricultureMax = 20000,
        population = 5000, populationMax = 1_000_000,
        supplyState = 1, frontState = 0,
        trust = 50.0, meta = linkedMapOf(),
    )

    private fun action() = cheJeongchakJangnyeo(pipeline)

    @Test
    fun `key name and constraints — RemainCityCapacity present, RICE cost`() {
        assertEquals("che_정착장려", action().key)
        assertEquals("정착 장려", action().name)
        // getCost = develCost*2 RICE
        assertEquals(env.develCost * 2, action().getReqRice(general(), env), "rice cost = develCost*2")

        val ctx = ConstraintContext(actorId = 42, cityId = 7, nationId = 1, mode = ConstraintMode.FULL)
        val names = action().buildConstraints(ctx).map { it.name }
        assertTrue(names.contains("RemainCityCapacity"), "정착장려 has RemainCityCapacity; got $names")
        assertTrue(names.contains("ReqGeneralRice"))
    }

    @Test
    fun `resolve mutates city population, decrements rice, increments leadership_exp`() {
        val draft = GeneralActionDraft(general(), city(), nation)
        val ctx = GeneralActionResolveContext(draft, freshRng(), env, MONTH, date)
        action().resolve(ctx)

        assertTrue(draft.city.population > 5000, "population increased: ${draft.city.population}")
        assertEquals(1000, draft.city.commerce, "commerce untouched")
        assertEquals(100000 - env.develCost * 2, draft.general.rice, "rice -= develCost*2")
        assertEquals(100000, draft.general.gold, "gold untouched")
        assertEquals(metaInt(general().meta, "leadership_exp") + 1, metaInt(draft.general.meta, "leadership_exp"))
        assertEquals("정착 장려", draft.general.lastTurn.command)
        assertEquals(1, ctx.logs().size)
        val log = ctx.logs()[0]
        assertTrue(log.startsWith("<C>●</>${MONTH}월:정착 장려"), "log prefix: $log")
        assertTrue(log.contains("명 증가했습니다."), "정착장려 log shape: $log")
        assertTrue(log.endsWith("<1>$date</>"))
    }

    @Test
    fun `population delta equals the score times 10 (clamped)`() {
        // Replay the PHP che_정착장려 algorithm with a parallel identically-seeded RNG.
        val g = general(); val rng = freshRng()
        var score = getStatValue(g, "leadership", pipeline, 255, withInjury = true, useFloor = false)
        score *= getDomesticExpLevelBonus(metaInt(g.meta, "explevel"))
        score *= rng.nextRange(0.8, 1.2)                                   // DRAW 1 (no trust factor)
        score = valueFit(score, 1.0)
        val ratio = criticalRatioDomestic(g, "leadership", pipeline, 255)
        val successRatio = clamp(ratio.success, 0.0, 1.0)
        val failRatio = clamp(ratio.fail, 0.0, 1.0 - successRatio)
        val normalRatio = 1.0 - failRatio - successRatio
        val pick = rng.choiceUsingWeight(linkedMapOf(                       // DRAW 2
            "fail" to failRatio, "success" to successRatio, "normal" to normalRatio))
        score *= criticalScoreEx(rng, pick)                                // DRAW 3
        val rounded = phpRound(score)
        val expectedDelta = rounded * 10                                   // score *= 10 (after exp/ded)

        val draft = GeneralActionDraft(general(), city(), nation)
        action().resolve(GeneralActionResolveContext(draft, freshRng(), env, MONTH, date))
        assertEquals(5000 + expectedDelta, draft.city.population, "pop = old + round(score)*10")
    }

    @Test
    fun `determinism`() {
        val a = GeneralActionDraft(general(), city(), nation)
        action().resolve(GeneralActionResolveContext(a, freshRng(), env, MONTH, date))
        val b = GeneralActionDraft(general(), city(), nation)
        action().resolve(GeneralActionResolveContext(b, freshRng(), env, MONTH, date))
        assertEquals(a.city.population, b.city.population)
        assertEquals(a.general.rice, b.general.rice)
        assertEquals(a.general.experience, b.general.experience)
        assertEquals(a.general.dedication, b.general.dedication)
    }

    @Test
    fun `resolve refreshes stale levels before a later settlement turn`() {
        val stale = general().copy(
            experience = 2500.0,
            dedication = 2500.0,
            meta = linkedMapOf(
                "explevel" to 0,
                "dedlevel" to 1,
                "leadership_exp" to 0,
                "max_domestic_critical" to 0.0,
            ),
        )
        val first = GeneralActionDraft(stale, city(), nation)
        action().resolve(GeneralActionResolveContext(first, freshRng(), env, MONTH, date))

        assertEquals(15, metaInt(first.general.meta, "explevel"))
        assertEquals(6, metaInt(first.general.meta, "dedlevel"))

        val second = GeneralActionDraft(first.general, first.city, nation)
        action().resolve(GeneralActionResolveContext(second, freshRng(), env, MONTH, date))
        assertTrue(
            second.city.population - first.city.population > first.city.population - city().population,
            "the second identical turn consumes the refreshed experience-level bonus",
        )
    }
}
