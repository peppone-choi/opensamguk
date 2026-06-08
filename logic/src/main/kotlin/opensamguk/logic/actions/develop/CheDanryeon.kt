package opensamguk.logic.actions.develop

import opensamguk.common.constants.GameConst
import opensamguk.logic.actions.GeneralActionDefinition
import opensamguk.logic.actions.GeneralActionResolveContext
import opensamguk.logic.actions.military.UnitSetTable
import opensamguk.logic.actions.military.addDexForCrewType
import opensamguk.logic.constraints.Constraint
import opensamguk.logic.constraints.ConstraintContext
import opensamguk.logic.constraints.notBeNeutral
import opensamguk.logic.constraints.reqGeneralAtmosMargin
import opensamguk.logic.constraints.reqGeneralCrew
import opensamguk.logic.constraints.reqGeneralGold
import opensamguk.logic.constraints.reqGeneralRice
import opensamguk.logic.constraints.reqGeneralTrainMargin
import opensamguk.logic.domain.LastTurn
import opensamguk.logic.domain.metaDouble
import opensamguk.logic.domain.withMeta
import opensamguk.logic.stats.GeneralActionPipeline
import opensamguk.logic.stats.getStatValue
import opensamguk.logic.util.numberFormat
import opensamguk.logic.util.phpRound

/**
 * che_단련 — PHP che_단련.php run() (lines 79-134). 병종 숙련도 향상 커맨드.
 *
 * 2-draw order (load-bearing):
 *   DRAW1 choiceUsingWeightPair([success,3],[normal,2],[fail,1]) — pick + multiplier.
 *   DRAW2 choiceUsingWeight(leadership_exp, strength_exp, intel_exp) — weighted by RAW stats (all false flags).
 *
 * Score calc:
 *   score = round(crew * train * atmos / 20 / 10000) * multiplier
 *   scoreText = number_format(score, 0)
 *
 * Side effects (in PHP execution order):
 *   - addDex(crewTypeObj, score, false)
 *   - exp = crew / 400 (raw float; addExperience does onCalcStat fold + level check)
 *   - gold -= develcost, rice -= develcost (increaseVarWithLimit(..., 0))
 *   - experience += exp
 *   - incStat += 1
 *   - setResultTurn(LastTurn)
 *   - checkStatChange (no visible effect when exp < UPGRADE_LIMIT)
 *
 * Log: "{armTypeText} 숙련도가 <C>{scoreText}</> 향상되었습니다. <1>{date}</>"
 *   fail prefix: "단련이 <span class='ev_failed'>지지부진</span>하여 "
 *   success prefix: "단련이 <S>일취월장</>하여 "
 *   normal: no prefix
 */
class CheDanryeon(
    private val pipeline: GeneralActionPipeline,
    private val maxLevel: Int = 255,
) : GeneralActionDefinition {
    override val key = "che_단련"
    override val name = "단련"
    override val category = "개인"

    /** che_단련.php:66-69 — getCost = [develcost, develcost]. */
    fun getCost(env: Map<String, Any?>): Pair<Int, Int> {
        val cost = (env["develCost"] as? Number)?.toInt() ?: 0
        return cost to cost
    }
    fun getCost(env: opensamguk.logic.domain.WorldEnv): Pair<Int, Int> =
        env.develCost to env.develCost

    override fun buildConstraints(ctx: ConstraintContext): List<Constraint> {
        val env = ctx.env
        val (reqGold, reqRice) = getCost(env)
        return listOf(
            notBeNeutral(),
            reqGeneralCrew(),
            reqGeneralTrainMargin { _, _ -> GameConst.defaultTrainLow },
            reqGeneralAtmosMargin { _, _ -> GameConst.defaultAtmosLow },
            reqGeneralGold { _, _ -> reqGold },
            reqGeneralRice { _, _ -> reqRice },
        )
    }

    override fun resolve(context: GeneralActionResolveContext) {
        val d = context.draft
        val g0 = d.general
        val rng = context.rng
        val env = context.env

        // DRAW1 — choiceUsingWeightPair: [success,3], [normal,2], [fail,1]
        val pick = rng.choiceUsingWeightPair(listOf(
            Pair("success" to 3, 0.34),
            Pair("normal" to 2, 0.33),
            Pair("fail" to 1, 0.33),
        ))

        // score = round(crew * train * atmos / 20 / 10000) * multiplier
        var score = phpRound(g0.crew * g0.train * g0.atmos / 20.0 / 10000.0).toDouble()
        score *= pick.second.toDouble()
        val scoreInt = score.toInt()
        val scoreText = numberFormat(scoreInt)

        // armType text — PHP GameUnitConst::allType()[armType]
        val unit = UnitSetTable.byId(g0.crewTypeId)
        // PHP GameUnitConst::$typeData = [T_FOOTMAN=>'병', T_ARCHER=>'궁병', T_CAVALRY=>'기병',
        //   T_WIZARD=>'귀병', T_SIEGE=>'차병']
        val armTypeName = when (unit?.armType) {
            UnitSetTable.T_FOOTMAN -> "보병"
            UnitSetTable.T_ARCHER -> "궁병"
            UnitSetTable.T_CAVALRY -> "기병"
            UnitSetTable.T_WIZARD -> "귀병"
            UnitSetTable.T_SIEGE -> "차병"
            else -> "보병"
        }

        // Log (PHP execution order: log BEFORE side effects)
        val logBody = when (pick.first) {
            "fail" -> "단련이 <span class='ev_failed'>지지부진</span>하여 ${armTypeName} 숙련도가 <C>$scoreText</> 향상되었습니다. <1>${context.date}</>"
            "success" -> "단련이 <S>일취월장</>하여 ${armTypeName} 숙련도가 <C>$scoreText</> 향상되었습니다. <1>${context.date}</>"
            else -> "${armTypeName} 숙련도가 <C>$scoreText</> 향상되었습니다. <1>${context.date}</>"
        }
        context.addLog(logBody)

        // exp = crew / 400 (raw float)
        val exp = g0.crew / 400.0

        // addDex(crewTypeObj, score, false)
        var g = addDexForCrewType(pipeline, g0, g0.crewTypeId, score)

        // DRAW2 — choiceUsingWeight for incStat (RAW stats, all flags false)
        val incStat = rng.choiceUsingWeight(linkedMapOf(
            "leadership_exp" to getStatValue(g, "leadership", pipeline, maxLevel,
                withInjury = false, withIActionObj = false, withStatAdjust = false, useFloor = false),
            "strength_exp" to getStatValue(g, "strength", pipeline, maxLevel,
                withInjury = false, withIActionObj = false, withStatAdjust = false, useFloor = false),
            "intel_exp" to getStatValue(g, "intelligence", pipeline, maxLevel,
                withInjury = false, withIActionObj = false, withStatAdjust = false, useFloor = false),
        ))

        // cost deduction
        val (reqGold, reqRice) = getCost(env)
        g = g.copy(
            gold = maxOf(0, g.gold - reqGold),    // increaseVarWithLimit('gold', -reqGold, 0)
            rice = maxOf(0, g.rice - reqRice),    // increaseVarWithLimit('rice', -reqRice, 0)
            experience = g.experience + exp,       // addExperience(exp) — raw float add
            meta = withMeta(g.meta, incStat to metaDouble(g.meta, incStat) + 1),
            lastTurn = LastTurn(name),
        )

        // checkStatChange — PHP General.php:753-785. No visible effect when stat_exp < UPGRADE_LIMIT.
        val sc = opensamguk.logic.domestic.checkStatChange(g)
        g = sc.general
        for (plainLog in sc.plainLogs) {
            context.addPlainLog(plainLog)
        }

        // TODO(백로그): StaticEventHandler::handleEvent — zero PHP callers in 1010, no visible golden effect.
        // TODO(백로그): tryUniqueItemLottery — no visible golden effect captured.

        d.general = g
    }
}

fun cheDanryeon(pipeline: GeneralActionPipeline, maxLevel: Int = 255) = CheDanryeon(pipeline, maxLevel)
