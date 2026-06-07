package opensamguk.logic.actions.military

import opensamguk.common.constants.GameConst
import opensamguk.common.constants.getTechCost
import opensamguk.logic.actions.GeneralActionDefinition
import opensamguk.logic.actions.GeneralActionResolveContext
import opensamguk.logic.constraints.*
import opensamguk.logic.domain.General
import opensamguk.logic.domain.LastTurn
import opensamguk.logic.domain.metaDouble
import opensamguk.logic.domain.withMeta
import opensamguk.logic.domestic.addDedication
import opensamguk.logic.domestic.addExperience
import opensamguk.logic.domestic.checkStatChange
import opensamguk.logic.stats.GeneralActionPipeline
import opensamguk.logic.util.phpRound

/**
 * che_전투태세 — 전투태세. che_전투태세.php run() (lines 66-130)의 충실 포팅. NONE-draw 결정형 명령.
 *
 * 다회차(preReqTurn=3) 충전형 명령이다. 일반 커맨드라 term 분기가 **run() 내부**에 있다
 * (che_전투태세.php:81-92) — 국가 커맨드의 capset-seq [addTermStack] 와는 별개 알고리즘(seq 없음):
 *   - lastTurn.command != name            → term = 1                         (php:81-83)
 *   - else if lastTurn.term == reqTurn(3)  → term = 1  (직전에 완료 → 새 사이클 시작)  (php:84-86)
 *   - else if lastTurn.term <  reqTurn      → term = lastTurn.term + 1         (php:87-89)
 *   - else                                  → 도달 불가(MustNotBeReached)        (php:90-92)
 *
 * term < 3 (충전 중, php:99-105): "병사들을 열심히 훈련중... ({term}/3) <1>date</>" 로그 + 결과턴만 기록,
 *   효과 없음. lastTurn(name, arg, term) 으로 결과턴을 적재한다.
 *
 * term == 3 (완료, php:107-129):
 *   - "전투태세 완료! ({term}/3) <1>date</>" 로그
 *   - increaseVarWithLimit('train', 0, maxTrainByCommand-5) → train 을 95 로 LOWER-fit (95보다 높으면 깎이지 않음)
 *   - increaseVarWithLimit('atmos', 0, maxAtmosByCommand-5) → atmos 를 95 로 LOWER-fit
 *   - exp = 100*3 = 300, ded = 70*3 = 210  (addExperience/addDedication — onCalcStat fold + 레벨 PLAIN 로그)
 *   - addDex(crewTypeObj, crew/100*3, false)
 *   - increaseVar('leadership_exp', 3)
 *   - 결과턴 lastTurn(name, arg, term=3)
 *   - checkStatChange() → 능력 PLAIN 로그
 *
 * getCost = [round(crew/100*3*techCost), 0] (php:52-56). PhpRound = half-away. tech 는 NATION 스탯.
 * constraints (php:39-47): NotBeNeutral / NotWanderingNation / OccupiedCity / ReqGeneralCrew /
 *   ReqGeneralGold(reqGold) / ReqGeneralRice(reqRice=0) /
 *   ReqGeneralTrainMargin(maxTrainByCommand-10) / ReqGeneralAtmosMargin(maxAtmosByCommand-10).
 */
class CheJeontuTaese(
    private val pipeline: GeneralActionPipeline,
    private val maxLevel: Int = 255,
) : GeneralActionDefinition {
    override val key = "che_전투태세"
    override val name = "전투태세"
    override val category = "군사"

    /** che_전투태세.php:58 — preReqTurn = 3 (3턴 충전 후 효과 발동). */
    val preReqTurn: Int = 3

    /** che_전투태세.php:52-56 — gold = round(crew/100*3*techCost), rice = 0. tech 는 NATION 스탯. */
    fun getCostGold(general: General, tech: Int): Int =
        phpRound(general.crew / 100.0 * 3 * getTechCost(tech))

    override fun buildConstraints(ctx: ConstraintContext): List<Constraint> = listOf(
        notBeNeutral(), notWanderingNation(), occupiedCity(),
        reqGeneralCrew(),
        reqGeneralGold { c, view ->
            val g = view.get(RequirementKey.General(c.actorId)) as General
            // tech 는 NATION 스탯(che_전투태세.php:54). MemoryStateView 는 월드 전체를 들고 있어 nationId 로 조회 가능.
            val nation = c.nationId?.let { view.get(RequirementKey.Nation(it)) } as? opensamguk.logic.domain.Nation
            getCostGold(g, (nation?.tech ?: 0.0).toInt())
        },
        reqGeneralRice { _, _ -> 0 },
        // ReqGeneralTrainMargin(maxTrainByCommand - 10): train < 90 일 때만 통과.
        reqGeneralTrainMargin { _, _ -> GameConst.maxTrainByCommand - 10 },
        // ReqGeneralAtmosMargin(maxAtmosByCommand - 10): atmos < 90 일 때만 통과.
        reqGeneralAtmosMargin { _, _ -> GameConst.maxAtmosByCommand - 10 },
    )

    override fun resolve(context: GeneralActionResolveContext) {
        val d = context.draft
        val g0 = d.general
        val lastTurn = g0.lastTurn

        // term 분기 (che_전투태세.php:81-92) — 일반 커맨드라 run() 내부에서 직접 계산.
        val term: Int = when {
            lastTurn.command != name -> 1
            (lastTurn.term ?: 0) == preReqTurn -> 1
            (lastTurn.term ?: 0) < preReqTurn -> (lastTurn.term ?: 0) + 1
            // else 분기는 PHP MustNotBeReachedException — 정상 흐름에서 도달하지 않는다.
            else -> throw IllegalStateException("전투 태세에 올바른 턴이 아님")
        }
        // 모든 분기에서 결과턴은 LastTurn(name, arg, term) (php:77 + setTerm).
        val resultTurn = LastTurn(name, context.args.ifEmpty { null }, term = term)

        if (term < 3) {
            // 충전 중 (php:99-105): 로그 + 결과턴만, 효과 없음.
            context.addLog("병사들을 열심히 훈련중... ($term/3) <1>${context.date}</>")
            d.general = g0.copy(lastTurn = resultTurn)
            return
        }

        // 완료 (php:107-129).
        context.addLog("전투태세 완료! ($term/3) <1>${context.date}</>")

        // increaseVarWithLimit('train', 0, max-5) / ('atmos', 0, max-5): LOWER-fit 95 (95보다 높으면 유지).
        // LazyVarUpdater.php:80-92 — 3번째 인자는 min. value=0 이므로 train = max(train, 95).
        val trainFloor = (GameConst.maxTrainByCommand - 5).toDouble()
        val atmosFloor = (GameConst.maxAtmosByCommand - 5).toDouble()
        var g = g0.copy(
            train = maxOf(g0.train, trainFloor),
            atmos = maxOf(g0.atmos, atmosFloor),
        )

        // exp = 100*3, ded = 70*3 (php:112-116). addExperience/addDedication 순서 — onCalcStat fold + 레벨 PLAIN 로그.
        val expRes = addExperience(g, 100.0 * 3, pipeline)
        g = expRes.general
        expRes.plainLog?.let { context.addPlainLog(it) }
        val dedRes = addDedication(g, 70.0 * 3, pipeline)
        g = dedRes.general
        dedRes.plainLog?.let { context.addPlainLog(it) }

        // addDex(crewTypeObj, crew/100*3, false) (php:120) — getVar('crew') 는 변경 전 원본 crew.
        g = addDexForCrewType(pipeline, g, g.crewTypeId, g0.crew / 100.0 * 3)

        // increaseVar('leadership_exp', 3) (php:122) + 결과턴 (php:123).
        g = g.copy(
            meta = withMeta(g.meta, "leadership_exp" to metaDouble(g.meta, "leadership_exp") + 3),
            lastTurn = resultTurn,
        )

        // checkStatChange() (php:124) — 능력경험치 누적이 한계 넘으면 ±1 + PLAIN 로그.
        val statRes = checkStatChange(g)
        g = statRes.general
        statRes.plainLogs.forEach { context.addPlainLog(it) }

        d.general = g
    }
}
