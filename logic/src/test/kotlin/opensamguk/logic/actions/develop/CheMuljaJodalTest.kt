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
import opensamguk.logic.domestic.UPGRADE_LIMIT
import opensamguk.logic.domestic.criticalScoreEx
import opensamguk.logic.domestic.getDomesticExpLevelBonus
import opensamguk.logic.event.StaticEventHandler
import opensamguk.logic.stats.GeneralActionModule
import opensamguk.logic.stats.GeneralActionPipeline
import opensamguk.logic.stats.getStatValue
import opensamguk.logic.util.phpRound
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * DV4 — che_물자조달 (PHP che_물자조달.php:65-158, VERIFIED).
 *
 * The 5-draw order (load-bearing):
 *   DRAW1 choice([['금','gold'],['쌀','rice']])  (line 75) — the EXTRA LEADING draw, BEFORE nextRange.
 *   DRAW2 nextRange(0.8,1.2)                      (line 82)
 *   DRAW3 choiceUsingWeight(fail,success,normal)  (line 91) — fixed success 0.1 / fail 0.3
 *   DRAW4 criticalScoreEx(rng, pick)              (line 96) — only success/fail draw
 *   DRAW5 choiceUsingWeight(incStat: RAW stats)   (line 136)
 *
 * Rounding/credit timing:
 *   - score = round(score) FIRST (line 99).
 *   - exp = score*0.7/3, ded = score*1.0/3 — from the PRE-debuff rounded score (lines 101-102).
 *   - front-debuff multiplies score AFTER exp/ded (lines 106-121).
 *   - log scoreText (line 123) + nation credit (lines 146-148) use the POST-debuff score.
 *   - log hardcodes `{resName}을` (literal 을, NOT josa).
 *   - getCost [0,0]; constraints NotBeNeutral/NotWanderingNation/OccupiedCity/SuppliedCity only.
 */
class CheMuljaJodalTest {

    private val pipeline = GeneralActionPipeline()
    private val FIXTURE_HIDDEN_SEED = "00000000000000000000000000000000"
    private val MONTH = 3
    private val date = "12:34"
    private val nation = Nation(id = 1, level = 2, capitalCityId = 99, gold = 10000, rice = 8000)
    private val env = WorldEnv(year = 190, startYear = 184, develCost = 120)

    private fun freshRng() = RandUtil(
        LiteHashDrbg(serializeSeed(FIXTURE_HIDDEN_SEED, "generalCommand", 190, MONTH, 42, "che_물자조달")))

    private fun general() = General(
        id = 42, nationId = 1, cityId = 7,
        leadership = 70, strength = 75, intel = 80,
        injury = 0, experience = 0.0, dedication = 0.0, officerLevel = 0,
        gold = 1000, rice = 1000,
        meta = linkedMapOf("explevel" to 10, "leadership_exp" to 1, "strength_exp" to 2, "intel_exp" to 3),
    )

    // non-front city (frontState=0) → no debuff
    private fun city(front: Int = 0) = City(
        id = 7, nationId = 1, level = 5,
        commerce = 1000, commerceMax = 20000,
        agriculture = 1000, agricultureMax = 20000,
        supplyState = 1, frontState = front,
        trust = 50.0, meta = linkedMapOf(),
    )

    private fun action() = cheMuljaJodal(pipeline)

    @AfterTest
    fun clearStaticHandlers() = StaticEventHandler.clear()

    /** Replay the PHP 5-draw algorithm with a parallel identically-seeded RNG (non-front city). */
    private data class Expected(
        val resKey: String, val resName: String, val pick: String, val incStat: String,
        val roundedScore: Int, val exp: Double, val ded: Double, val cityScore: Int,
    )

    private fun replay(front: Int = 0, capitalCity: Boolean = false): Expected {
        val g = general(); val rng = freshRng()
        // DRAW1
        val resPick = rng.choice(listOf(listOf("금", "gold"), listOf("쌀", "rice")))
        val resName = resPick[0]; val resKey = resPick[1]
        // score = (L + S + I) floored, then expLevelBonus, then DRAW2 nextRange
        var score = getStatValue(g, "leadership", pipeline, 255) +
                    getStatValue(g, "strength", pipeline, 255) +
                    getStatValue(g, "intelligence", pipeline, 255)
        score *= getDomesticExpLevelBonus(metaInt(g.meta, "explevel"))
        score *= rng.nextRange(0.8, 1.2)                                     // DRAW2
        val successRatio = 0.1; val failRatio = 0.3
        val normalRatio = 1.0 - failRatio - successRatio
        val pick = rng.choiceUsingWeight(linkedMapOf(                        // DRAW3
            "fail" to failRatio, "success" to successRatio, "normal" to normalRatio))
        score *= criticalScoreEx(rng, pick)                                  // DRAW4
        val roundedScore = phpRound(score)
        val exp = roundedScore * 0.7 / 3.0
        val ded = roundedScore * 1.0 / 3.0
        // front-debuff (front 1/3). debuff 0.5; capital ramp relYear<25.
        var cityScoreD = roundedScore.toDouble()
        if (front == 1 || front == 3) {
            var debuff = 0.5
            if (capitalCity && env.relYear < 25) {
                val scale = opensamguk.logic.util.clamp((env.relYear - 5).toDouble(), 0.0, 20.0) * 0.05
                debuff = scale * 0.5 + (1 - scale)
            }
            cityScoreD *= debuff
        }
        // nation credit = (int)$score via meekrodb %i cast → truncation toward zero of the post-debuff float.
        // (For non-front, score is already Util::round(...) so truncation is exact.)
        val cityScore = kotlin.math.truncate(cityScoreD).toInt()
        // DRAW5 incStat — RAW stats (all flags false)
        val incStat = rng.choiceUsingWeight(linkedMapOf(
            "leadership_exp" to getStatValue(g, "leadership", pipeline, 255, withInjury = false, withIActionObj = false, withStatAdjust = false, useFloor = false),
            "strength_exp" to getStatValue(g, "strength", pipeline, 255, withInjury = false, withIActionObj = false, withStatAdjust = false, useFloor = false),
            "intel_exp" to getStatValue(g, "intelligence", pipeline, 255, withInjury = false, withIActionObj = false, withStatAdjust = false, useFloor = false)))
        return Expected(resKey, resName, pick, incStat, roundedScore, exp, ded, cityScore)
    }

    @Test
    fun `key name and minimal constraints — no cost gate, no RemainCityCapacity`() {
        assertEquals("che_물자조달", action().key)
        assertEquals("물자조달", action().name)
        val ctx = ConstraintContext(actorId = 42, cityId = 7, nationId = 1, mode = ConstraintMode.FULL)
        val names = action().buildConstraints(ctx).map { it.name }
        assertEquals(listOf("NotBeNeutral", "NotWanderingNation", "OccupiedCity", "SuppliedCity"), names,
            "물자조달 has only the 4 base constraints; got $names")
    }

    @Test
    fun `resolve credits the nation resource by the rounded score (non-front) and bumps the weighted incStat`() {
        val exp = replay()
        val draft = GeneralActionDraft(general(), city(0), nation)
        val ctx = GeneralActionResolveContext(draft, freshRng(), env, MONTH, date)
        action().resolve(ctx)

        // nation resource credited (gold or rice depending on DRAW1)
        if (exp.resKey == "gold") {
            assertEquals(10000 + exp.cityScore, draft.nation!!.gold, "nation.gold += score")
            assertEquals(8000, draft.nation!!.rice, "rice untouched")
        } else {
            assertEquals(8000 + exp.cityScore, draft.nation!!.rice, "nation.rice += score")
            assertEquals(10000, draft.nation!!.gold, "gold untouched")
        }
        // weighted incStat _exp bumped by 1
        assertEquals(metaInt(general().meta, exp.incStat) + 1, metaInt(draft.general.meta, exp.incStat),
            "incStat ${exp.incStat} bumped")
        // exp/ded = rounded/3 ratios (no general gold/rice change — 물자조달 credits the NATION)
        assertEquals(exp.exp, draft.general.experience, 1e-9, "exp = round*0.7/3")
        assertEquals(exp.ded, draft.general.dedication, 1e-9, "ded = round*1.0/3")
        assertEquals(1000, draft.general.gold, "general gold untouched")
    }

    @Test
    fun `log hardcodes 을 (NOT josa) and the resource name`() {
        val exp = replay()
        val draft = GeneralActionDraft(general(), city(0), nation)
        val ctx = GeneralActionResolveContext(draft, freshRng(), env, MONTH, date)
        action().resolve(ctx)
        val log = ctx.logs()[0]
        // body carries `{resName}을` with literal 을 regardless of resName jongsung (금/쌀 both end up "을")
        assertTrue(log.contains("${exp.resName}을 <C>"), "literal 을 + resName; got: $log")
        assertTrue(log.contains("조달했습니다."), "조달 log shape; got: $log")
        assertTrue(log.endsWith("<1>$date</>"))
    }

    @Test
    fun `front-city debuff applies to log scoreText and nation credit but NOT to exp or ded`() {
        // non-capital front city (frontState=1) → flat 0.5 debuff
        val expFront = replay(front = 1, capitalCity = false)
        val draft = GeneralActionDraft(general(), city(1), nation)  // city 7 != capital 99 → flat debuff
        val ctx = GeneralActionResolveContext(draft, freshRng(), env, MONTH, date)
        action().resolve(ctx)

        // exp/ded computed from the PRE-debuff rounded score
        assertEquals(expFront.exp, draft.general.experience, 1e-9, "exp from PRE-debuff rounded score")
        assertEquals(expFront.ded, draft.general.dedication, 1e-9, "ded from PRE-debuff rounded score")
        // nation credit uses the POST-debuff score (halved vs the rounded score, so strictly less when score>1)
        val credited = if (expFront.resKey == "gold") draft.nation!!.gold - 10000 else draft.nation!!.rice - 8000
        assertTrue(credited < expFront.roundedScore, "post-debuff credit < pre-debuff score ($credited < ${expFront.roundedScore})")
    }

    @Test
    fun `exp and dedication fold through a non identity pipeline with ordered PLAIN promotions`() {
        val boostedPipeline = GeneralActionPipeline(listOf(object : GeneralActionModule {
            override fun onCalcStat(general: General, statName: String, value: Double, aux: Map<String, Any?>): Double =
                when (statName) {
                    "experience" -> value * 2.0
                    "dedication" -> value * 3.0
                    else -> value
                }
        }))
        val actor = general().copy(
            experience = 99.0,
            dedication = 0.0,
            meta = linkedMapOf(
                "explevel" to 0,
                "dedlevel" to 0,
                "leadership_exp" to 1,
                "strength_exp" to 2,
                "intel_exp" to 3,
            ),
        )

        val identityDraft = GeneralActionDraft(actor, city(0), nation)
        cheMuljaJodal(GeneralActionPipeline()).resolve(
            GeneralActionResolveContext(identityDraft, freshRng(), env, MONTH, date),
        )

        val boostedDraft = GeneralActionDraft(actor, city(0), nation)
        val boostedContext = GeneralActionResolveContext(boostedDraft, freshRng(), env, MONTH, date)
        cheMuljaJodal(boostedPipeline).resolve(boostedContext)

        val rawExp = identityDraft.general.experience - actor.experience
        val rawDed = identityDraft.general.dedication - actor.dedication
        assertEquals(actor.experience + rawExp * 2.0, boostedDraft.general.experience, 1e-9)
        assertEquals(actor.dedication + rawDed * 3.0, boostedDraft.general.dedication, 1e-9)
        assertEquals(2, boostedContext.plainLogs().size, "addExperience then addDedication each emit once")
        assertTrue(boostedContext.plainLogs()[0].contains("레벨업"), "experience promotion is first")
        assertTrue(boostedContext.plainLogs()[1].contains("승급"), "dedication promotion is second")
        assertEquals(
            listOf(boostedContext.logs().single()) + boostedContext.plainLogs(),
            boostedContext.orderedLogEvents().map { it.text },
            "the existing action log stays before PHP helper PLAIN logs",
        )
    }

    @Test
    fun `tail credits nation then sets LastTurn and checks stats before StaticEventHandler`() {
        val actor = general().copy(
            meta = linkedMapOf(
                "explevel" to 0,
                "dedlevel" to 0,
                "leadership_exp" to UPGRADE_LIMIT - 1,
                "strength_exp" to UPGRADE_LIMIT - 1,
                "intel_exp" to UPGRADE_LIMIT - 1,
            ),
        )
        val draft = GeneralActionDraft(actor, city(0), nation)
        val context = GeneralActionResolveContext(draft, freshRng(), env, MONTH, date)
        val observedTurns = mutableListOf<String>()
        val observedStatTotals = mutableListOf<Int>()
        val observedNationGold = mutableListOf<Int>()
        val observedNationRice = mutableListOf<Int>()
        val observedPlainLogs = mutableListOf<List<String>>()
        StaticEventHandler.register("che_물자조달") { eventGeneral, _, _, _ ->
            observedTurns += eventGeneral.lastTurn.command
            observedStatTotals += eventGeneral.leadership + eventGeneral.strength + eventGeneral.intel
            observedNationGold += draft.nation!!.gold
            observedNationRice += draft.nation!!.rice
            observedPlainLogs += context.plainLogs()
        }

        action().resolve(context)

        assertEquals(listOf("물자조달"), observedTurns)
        assertEquals(listOf(actor.leadership + actor.strength + actor.intel + 1), observedStatTotals)
        assertEquals(listOf(draft.nation!!.gold), observedNationGold)
        assertEquals(listOf(draft.nation!!.rice), observedNationRice)
        assertTrue(draft.nation!!.gold > nation.gold || draft.nation!!.rice > nation.rice)
        assertTrue(observedPlainLogs.single().any { it.contains("올랐습니다!") })
    }

    @Test
    fun `determinism`() {
        val a = GeneralActionDraft(general(), city(0), nation)
        action().resolve(GeneralActionResolveContext(a, freshRng(), env, MONTH, date))
        val b = GeneralActionDraft(general(), city(0), nation)
        action().resolve(GeneralActionResolveContext(b, freshRng(), env, MONTH, date))
        assertEquals(a.nation!!.gold, b.nation!!.gold)
        assertEquals(a.nation!!.rice, b.nation!!.rice)
        assertEquals(a.general.experience, b.general.experience)
        assertEquals(a.general.dedication, b.general.dedication)
    }
}
