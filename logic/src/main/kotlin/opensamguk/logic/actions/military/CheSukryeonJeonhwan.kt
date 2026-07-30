package opensamguk.logic.actions.military

import opensamguk.common.constants.GameUnitConst
import opensamguk.common.josa.JosaUtil
import opensamguk.logic.actions.GeneralActionDefinition
import opensamguk.logic.actions.GeneralActionResolveContext
import opensamguk.logic.actions.CommandFieldSpec
import opensamguk.logic.actions.CommandFormSpec
import opensamguk.logic.constraints.Constraint
import opensamguk.logic.constraints.ConstraintContext
import opensamguk.logic.constraints.RequirementKey
import opensamguk.logic.constraints.notBeNeutral
import opensamguk.logic.constraints.occupiedCity
import opensamguk.logic.constraints.reqGeneralGold
import opensamguk.logic.constraints.reqGeneralRice
import opensamguk.logic.domain.General
import opensamguk.logic.domain.LastTurn
import opensamguk.logic.domain.metaDouble
import opensamguk.logic.domain.withMeta
import opensamguk.logic.domestic.addExperience
import opensamguk.logic.domestic.checkStatChange
import opensamguk.logic.event.StaticEventHandler
import opensamguk.logic.actions.founding.GeneralUniqueLotteryIntent
import opensamguk.logic.stats.GeneralActionPipeline
import opensamguk.logic.util.numberFormat
import opensamguk.logic.util.phpToInt

/**
 * che_숙련전환 — `legacy/devsam-core/hwe/sammo/Command/General/che_숙련전환.php`의 충실 포팅.
 *
 * 한 병종(srcArmType) 숙련(dex)의 일부를 다른 병종(destArmType) 숙련으로 전환한다. 결정론적(draw-0):
 * run()이 RNG를 한 번도 소비하지 않는다. (trailing tryUniqueItemLottery는 별도 'unique' 시드의 하류 seam)
 *
 * argTest (che_숙련전환.php:35-72): arg에 정수 srcArmType/destArmType이 있어야 하고, 둘 다
 * GameUnitConst::allType()(병종 armType 1..5)의 키여야 하며, src != dest. 정규화 결과는
 * {srcArmType, destArmType} 삽입순 맵.
 *
 * 비용 getCost (che_숙련전환.php:130-134): [develcost, develcost] — 자금 AND 군량 둘 다 develcost.
 *
 * full-condition constraints (che_숙련전환.php:101-106), PHP 순서(first-deny-wins):
 *   [NotBeNeutral, OccupiedCity, ReqGeneralGold(reqGold), ReqGeneralRice(reqRice)]
 *   (NotWanderingNation / SuppliedCity 없음 — 형제 내정 명령과 다름)
 *
 * run (che_숙련전환.php:159-185):
 *   srcDex   = getVar('dex{srcArmType}')                            (meta double)
 *   cutDex   = Util::toInt(srcDex * 0.4)        decreaseCoeff=0.4   (truncate-toward-zero)
 *   addDex   = Util::toInt(cutDex * 0.9)        convertCoeff=0.9    (truncate)
 *   increaseVar('dex{srcArmType}',  -cutDex)
 *   increaseVar('dex{destArmType}', +addDex)
 *   로그: "{srcName} 숙련 {number_format(cutDex)}{을} {destName} 숙련 {number_format(addDex)}{로} 전환했습니다. <1>date</>"
 *        — JosaUtil::pick(cutDex,'을') / JosaUtil::pick(addDex,'로')는 RAW 정수의 마지막 자리수로 조사 선택
 *          (number_format된 콤마 텍스트가 아님).
 *   addExperience(10); gold -= reqGold(floor 0); rice -= reqRice(floor 0); leadership_exp += 2;
 *   setResultTurn; checkStatChange; (StaticEventHandler / tryUniqueItemLottery는 하류 seam)
 */
class CheSukryeonJeonhwan(
    private val pipeline: GeneralActionPipeline,
    /** 파싱된 arg(srcArmType/destArmType). null = 미바인딩(레지스트리 기본) → resolve()는 no-op. */
    private val arg: Map<String, Any?>? = null,
) : GeneralActionDefinition {
    override val key: String = "che_숙련전환"
    override val name: String = "숙련전환"
    override val category: String = "군사"
    override val argsSchema: Map<String, Any?> get() = mapOf("srcArmType" to "int", "destArmType" to "int")
    override val formSpec: CommandFormSpec get() = CommandFormSpec(
        listOf(
            CommandFieldSpec("srcArmType", "int", "select", "armTypes"),
            CommandFieldSpec("destArmType", "int", "select", "armTypes"),
        ),
    )

    override fun bindArgs(parsed: Map<String, Any?>): CheSukryeonJeonhwan =
        CheSukryeonJeonhwan(pipeline, parsed)

    fun withArg(parsed: Map<String, Any?>): CheSukryeonJeonhwan = bindArgs(parsed)

    var lastUniqueLotteryIntent: GeneralUniqueLotteryIntent? = null
        private set

    /** che_숙련전환.php:32-33 — 고정 계수. */
    private val decreaseCoeff = 0.4
    private val convertCoeff = 0.9

    /**
     * argTest (che_숙련전환.php:35-72). srcArmType/destArmType는 정수이며 GameUnitConst.allType()(병종 1..5)의
     * 키여야 하고 src != dest. 정규화된 {srcArmType, destArmType} 맵 반환, reject 시 빈 맵(PHP arg=null → 실행 불가).
     */
    override fun parseArgs(raw: Map<String, Any?>): Map<String, Any?> {
        val src = raw["srcArmType"] as? Int ?: return emptyMap()
        val dest = raw["destArmType"] as? Int ?: return emptyMap()
        if (src !in GameUnitConst.allType().keys) return emptyMap()
        if (dest !in GameUnitConst.allType().keys) return emptyMap()
        if (src == dest) return emptyMap()
        return linkedMapOf("srcArmType" to src, "destArmType" to dest)
    }

    /** getCost (che_숙련전환.php:130-134): [develcost, develcost] — 자금 AND 군량 둘 다 develcost. */
    fun getCost(develCost: Int): Pair<Int, Int> = develCost to develCost

    override fun buildConstraints(ctx: ConstraintContext): List<Constraint> {
        val develCost = (ctx.env["develCost"] as? Number)?.toInt() ?: 0
        val (reqGold, reqRice) = getCost(develCost)
        return listOf(
            notBeNeutral(),
            occupiedCity(),
            reqGeneralGold { _, _ -> reqGold },
            reqGeneralRice { _, _ -> reqRice },
        )
    }

    override fun resolve(context: GeneralActionResolveContext) {
        lastUniqueLotteryIntent = null
        val parsed = arg ?: context.args
        val srcArmType = (parsed["srcArmType"] as? Number)?.toInt() ?: return   // 미바인딩 → argTest 실패 → 실행 안 됨
        val destArmType = (parsed["destArmType"] as? Number)?.toInt() ?: return
        val srcArmTypeName = GameUnitConst.allType()[srcArmType] ?: return
        val destArmTypeName = GameUnitConst.allType()[destArmType] ?: return

        val d = context.draft
        val develCost = context.env.develCost
        val (reqGold, reqRice) = getCost(develCost)

        val srcKey = "dex$srcArmType"
        val destKey = "dex$destArmType"

        // che_숙련전환.php:159-163 — srcDex 읽고 cut/add 계산(toInt = truncate).
        val srcDex = metaDouble(d.general.meta, srcKey)
        val cutDex = phpToInt(srcDex * decreaseCoeff)
        val addDex = phpToInt(cutDex.toDouble() * convertCoeff)
        val cutDexText = numberFormat(cutDex)
        val addDexText = numberFormat(addDex)

        // che_숙련전환.php:165-166 — dex 이전. increaseVar는 누적(여기선 double 보존).
        val nextDexSrc = metaDouble(d.general.meta, srcKey) - cutDex
        val nextDexDest = metaDouble(d.general.meta, destKey) + addDex
        var g = d.general.copy(meta = withMeta(d.general.meta, srcKey to nextDexSrc, destKey to nextDexDest))

        // che_숙련전환.php:168-169 — 조사는 RAW 정수(cutDex/addDex)의 마지막 자리수 기준.
        val josaUl = JosaUtil.pick(cutDex.toString(), "을")
        val josaRo = JosaUtil.pick(addDex.toString(), "로")

        // che_숙련전환.php:173 — 액션 로그(byte-exact).
        context.addLog(
            "$srcArmTypeName 숙련 $cutDexText$josaUl $destArmTypeName 숙련 $addDexText$josaRo 전환했습니다. <1>${context.date}</>"
        )

        // che_숙련전환.php:175-178 — addExperience(10) / gold·rice 차감(floor 0) / leadership_exp += 2.
        val expRes = addExperience(g, 10.0, pipeline)
        g = expRes.general
        expRes.plainLog?.let { context.addPlainLog(it) }

        g = g.copy(
            gold = maxOf(0, g.gold - reqGold),
            rice = maxOf(0, g.rice - reqRice),
            meta = withMeta(g.meta, "leadership_exp" to metaDouble(g.meta, "leadership_exp") + 2.0),
            lastTurn = LastTurn(command = name, arg = parsed),
        )

        // che_숙련전환.php:180 — checkStatChange.
        val sc = checkStatChange(g)
        g = sc.general
        for (line in sc.plainLogs) context.addPlainLog(line)

        d.general = g
        StaticEventHandler.handleEvent(d.general, d.destGeneral, rawClassName, emptyMap(), parsed)
        lastUniqueLotteryIntent = GeneralUniqueLotteryIntent(
            generalId = d.general.id,
            year = context.env.year,
            month = context.month,
            seedReason = lotteryActionName,
            acquireType = "아이템",
            afterTail = "setResultTurn>checkStatChange>StaticEventHandler",
        )
    }
}

fun cheSukryeonJeonhwan(pipeline: GeneralActionPipeline) = CheSukryeonJeonhwan(pipeline)
