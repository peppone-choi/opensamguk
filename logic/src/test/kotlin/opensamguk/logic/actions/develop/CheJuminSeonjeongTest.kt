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
import opensamguk.logic.domain.metaDouble
import opensamguk.logic.domain.metaInt
import opensamguk.logic.domain.withMeta
import opensamguk.logic.domestic.UPGRADE_LIMIT
import opensamguk.logic.domestic.criticalRatioDomestic
import opensamguk.logic.domestic.criticalScoreEx
import opensamguk.logic.domestic.getDedLevel
import opensamguk.logic.domestic.getDomesticExpLevelBonus
import opensamguk.logic.domestic.getExpLevel
import opensamguk.logic.event.StaticEventHandler
import opensamguk.logic.stats.GeneralActionModule
import opensamguk.logic.stats.GeneralActionPipeline
import opensamguk.logic.stats.getStatValue
import opensamguk.logic.util.clamp
import opensamguk.logic.util.valueFit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * DV3b — che_주민선정 (PHP che_주민선정.php). NOT a che_상업투자 subclass:
 *  - statKey='leadership', cityKey='trust' → mutates city.trust (clamp [0, 100]).
 *  - calcBaseScore: leadership × expLevelBonus × DRAW1 × valueFit(,1) — NO trust factor.
 *  - RemainCityTrust('주민 선정') constraint (NOT RemainCityCapacity).
 *  - getCost: develCost*2 as RICE.
 *  - run(): score is NOT rounded — exp/ded + max_domestic_critical are FRACTIONAL (the un-rounded score).
 *  - score /= 10 (after exp/ded + maxCrit).
 *  - scoreText = number_format(score, 1) — ONE decimal.
 *  - log: `주민 선정{을} … <C>{scoreText}</> 상승했습니다.`
 *  - increaseVar('leadership_exp', 1). NO front-debuff.
 */
class CheJuminSeonjeongTest {

    private val pipeline = GeneralActionPipeline()
    private val FIXTURE_HIDDEN_SEED = "00000000000000000000000000000000"
    private val MONTH = 3
    private val date = "12:34"
    private val nation = Nation(id = 1, level = 2, capitalCityId = 99)
    private val env = WorldEnv(year = 190, startYear = 184, develCost = 120)

    private fun freshRng() = RandUtil(
        LiteHashDrbg(serializeSeed(FIXTURE_HIDDEN_SEED, "generalCommand", 190, MONTH, 42, "che_주민선정")))

    private fun general() = General(
        id = 42, nationId = 1, cityId = 7,
        leadership = 90, strength = 50, intel = 50,
        injury = 0, experience = 0.0, dedication = 0.0, officerLevel = 0,
        gold = 100000, rice = 100000,
        meta = linkedMapOf("explevel" to 10, "leadership_exp" to 5, "max_domestic_critical" to 0.0),
    )

    private fun city(trust: Double = 40.0) = City(
        id = 7, nationId = 1, level = 5,
        commerce = 1000, commerceMax = 20000,
        agriculture = 1000, agricultureMax = 20000,
        supplyState = 1, frontState = 0,
        trust = trust, meta = linkedMapOf(),
    )

    private fun action() = cheJuminSeonjeong(pipeline)

    @AfterTest
    fun clearStaticHandlers() = StaticEventHandler.clear()

    @Test
    fun `key name and RemainCityTrust constraint + RICE cost`() {
        assertEquals("che_주민선정", action().key)
        assertEquals("주민 선정", action().name)
        assertEquals(env.develCost * 2, action().getReqRice(general(), env), "rice cost = develCost*2")
        val ctx = ConstraintContext(actorId = 42, cityId = 7, nationId = 1, mode = ConstraintMode.FULL)
        val names = action().buildConstraints(ctx).map { it.name }
        assertTrue(names.contains("RemainCityTrust"), "주민선정 has RemainCityTrust; got $names")
        assertTrue(!names.contains("RemainCityCapacity"), "no RemainCityCapacity; got $names")
        assertTrue(names.contains("ReqGeneralRice"))
    }

    @Test
    fun `resolve mutates city trust (clamp 100), decrements rice, increments leadership_exp`() {
        val draft = GeneralActionDraft(general(), city(40.0), nation)
        val ctx = GeneralActionResolveContext(draft, freshRng(), env, MONTH, date)
        action().resolve(ctx)

        assertTrue(draft.city.trust > 40.0, "trust increased: ${draft.city.trust}")
        assertTrue(draft.city.trust <= 100.0, "trust clamped to 100")
        assertEquals(100000 - env.develCost * 2, draft.general.rice, "rice -= develCost*2")
        assertEquals(metaInt(general().meta, "leadership_exp") + 1, metaInt(draft.general.meta, "leadership_exp"))
        assertEquals(1, ctx.logs().size)
        val log = ctx.logs()[0]
        assertTrue(log.startsWith("<C>●</>${MONTH}월:주민 선정"), "log prefix: $log")
        assertTrue(log.contains("상승했습니다."), "주민선정 log shape: $log")
        assertTrue(log.endsWith("<1>$date</>"))
    }

    @Test
    fun `score divided by 10 fractional with one-decimal format and fractional max_domestic_critical`() {
        // Replay the PHP algorithm with a parallel RNG to derive the exact fractional score and trust delta.
        val g = general(); val rng = freshRng()
        var score = getStatValue(g, "leadership", pipeline, 255, withInjury = true, useFloor = false)
        score *= getDomesticExpLevelBonus(metaInt(g.meta, "explevel"))
        score *= rng.nextRange(0.8, 1.2)                                  // DRAW 1
        score = valueFit(score, 1.0)                                      // calcBaseScore tail valueFit
        score = valueFit(score, 1.0)                                      // run() valueFit
        val ratio = criticalRatioDomestic(g, "leadership", pipeline, 255)
        val successRatio = clamp(ratio.success, 0.0, 1.0)
        val failRatio = clamp(ratio.fail, 0.0, 1.0 - successRatio)
        val normalRatio = 1.0 - failRatio - successRatio
        val pick = rng.choiceUsingWeight(linkedMapOf(
            "fail" to failRatio, "success" to successRatio, "normal" to normalRatio))
        score *= criticalScoreEx(rng, pick)                              // DRAW 3 — NO round
        val maxCritExpected = if (pick == "success") 0.0 + score / 2.0 else 0.0   // fractional unrounded
        val scoreDiv10 = score / 10.0
        val expectedTrust = valueFit(40.0 + scoreDiv10, 0.0, 100.0)
        val expectedScoreText = "%,.1f".format(scoreDiv10)

        val draft = GeneralActionDraft(general(), city(40.0), nation)
        val ctx = GeneralActionResolveContext(draft, freshRng(), env, MONTH, date)
        action().resolve(ctx)

        assertEquals(expectedTrust, draft.city.trust, 1e-9, "trust += score/10 (fractional)")
        // max_domestic_critical fractional (success) or 0 (else)
        assertEquals(maxCritExpected, metaDouble(draft.general.meta, "max_domestic_critical"), 1e-9, "fractional maxCrit")
        // exp = score*0.7, ded = score*1.0 — fractional, NOT from a rounded score
        assertEquals(score * 0.7, draft.general.experience, 1e-9, "exp from un-rounded score")
        assertEquals(score * 1.0, draft.general.dedication, 1e-9, "ded from un-rounded score")
        // one-decimal scoreText present in the log
        assertTrue(ctx.logs()[0].contains("<C>$expectedScoreText</> 상승"),
            "one-decimal scoreText <C>$expectedScoreText</>; got ${ctx.logs()[0]}")
    }

    @Test
    fun `one decimal formatting and log scoreText`() {
        val draft = GeneralActionDraft(general(), city(40.0), nation)
        val ctx = GeneralActionResolveContext(draft, freshRng(), env, MONTH, date)
        action().resolve(ctx)
        // the score-text token between <C> and </> after the action body must have exactly one decimal
        val m = Regex("<C>([0-9,]+\\.[0-9])</> 상승").find(ctx.logs()[0])
        assertTrue(m != null, "one-decimal <C>NN.N</> token present; got ${ctx.logs()[0]}")
    }

    @Test
    fun `determinism`() {
        val a = GeneralActionDraft(general(), city(40.0), nation)
        action().resolve(GeneralActionResolveContext(a, freshRng(), env, MONTH, date))
        val b = GeneralActionDraft(general(), city(40.0), nation)
        action().resolve(GeneralActionResolveContext(b, freshRng(), env, MONTH, date))
        assertEquals(a.city.trust, b.city.trust)
        assertEquals(a.general.rice, b.general.rice)
        assertEquals(a.general.experience, b.general.experience)
    }

    @Test
    fun `two consecutive turns fold and accumulate experience dedication through PHP helpers`() {
        val experienceInputs = mutableListOf<Double>()
        val dedicationInputs = mutableListOf<Double>()
        val module = object : GeneralActionModule {
            override fun onCalcStat(
                general: General,
                statName: String,
                value: Double,
                aux: Map<String, Any?>,
            ): Double = when (statName) {
                "experience" -> value.also(experienceInputs::add) * 1.1
                "dedication" -> value.also(dedicationInputs::add) * 1.2
                else -> value
            }
        }
        val foldedAction = cheJuminSeonjeong(GeneralActionPipeline(listOf(module)))
        val initial = general().copy(
            experience = 0.0,
            dedication = 0.0,
            meta = withMeta(general().meta, "explevel" to 0, "dedlevel" to 0),
        )
        val draft = GeneralActionDraft(initial, city(40.0), nation)

        repeat(2) {
            foldedAction.resolve(GeneralActionResolveContext(draft, freshRng(), env, MONTH, date))
        }

        assertEquals(2, experienceInputs.size, "General::addExperience must fold every turn")
        assertEquals(2, dedicationInputs.size, "General::addDedication must fold every turn")
        val expectedExperience = initial.experience + experienceInputs.sumOf { it * 1.1 }
        val expectedDedication = initial.dedication + dedicationInputs.sumOf { it * 1.2 }
        assertEquals(expectedExperience, draft.general.experience, 1e-9, "folded experience accumulates")
        assertEquals(expectedDedication, draft.general.dedication, 1e-9, "folded dedication accumulates")
        assertEquals(getExpLevel(expectedExperience), metaInt(draft.general.meta, "explevel"))
        assertEquals(getDedLevel(expectedDedication), metaInt(draft.general.meta, "dedlevel"))
    }

    @Test
    fun `tail sets LastTurn and checks leadership before StaticEventHandler`() {
        val actor = general().copy(
            meta = linkedMapOf(
                "explevel" to 0,
                "dedlevel" to 0,
                "leadership_exp" to UPGRADE_LIMIT - 1,
                "max_domestic_critical" to 0.0,
            ),
        )
        val draft = GeneralActionDraft(actor, city(40.0), nation)
        val context = GeneralActionResolveContext(draft, freshRng(), env, MONTH, date)
        val observedTurns = mutableListOf<String>()
        val observedLeadership = mutableListOf<Int>()
        val observedPlainLogs = mutableListOf<List<String>>()
        StaticEventHandler.register("che_주민선정") { eventGeneral, _, _, _ ->
            observedTurns += eventGeneral.lastTurn.command
            observedLeadership += eventGeneral.leadership
            observedPlainLogs += context.plainLogs()
        }

        action().resolve(context)

        assertEquals(listOf("주민 선정"), observedTurns)
        assertEquals(listOf(actor.leadership + 1), observedLeadership)
        assertTrue(observedPlainLogs.single().any { it.contains("<S>통솔</>이 <C>1</> 올랐습니다!") })
    }
}
