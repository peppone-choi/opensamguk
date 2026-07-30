package opensamguk.logic.actions.nation

import opensamguk.common.constants.GameConst
import opensamguk.common.josa.JosaUtil
import opensamguk.logic.actions.GeneralActionResolveContext
import opensamguk.logic.actions.CommandFieldSpec
import opensamguk.logic.actions.CommandFormSpec
import opensamguk.logic.constraints.Constraint
import opensamguk.logic.constraints.ConstraintContext
import opensamguk.logic.constraints.beChief
import opensamguk.logic.constraints.differentDestNation
import opensamguk.logic.constraints.existsDestNation
import opensamguk.logic.constraints.occupiedCity
import opensamguk.logic.constraints.reqDestNationValue
import opensamguk.logic.constraints.reqNationGold
import opensamguk.logic.constraints.reqNationRice
import opensamguk.logic.constraints.reqNationValue
import opensamguk.logic.constraints.suppliedCity
import opensamguk.logic.domain.metaInt
import opensamguk.logic.domain.withMeta
import opensamguk.logic.domestic.addDedication
import opensamguk.logic.domestic.addExperience
import opensamguk.logic.stats.GeneralActionPipeline
import opensamguk.logic.util.numberFormat

/**
 * che_물자원조 — faithful port of `legacy/devsam-core/hwe/sammo/Command/Nation/che_물자원조.php`.
 *
 * diplomacy(외교) 사령 커맨드. 다른 국가에 금/쌀을 이전한다(양국 surlimit==0). 두 개의 ACTING 라인
 * (broadcast + PLAIN). pushGlobalHistoryLog(history, NOT globalAction). RNG 미사용(deterministic).
 * logLines=2. actionName = '원조'(lotteryActionName).
 *
 * argTest(che_물자원조.php:33-77): `destNationID`(int,>=1) + `amountList`([goldAmount, riceAmount],
 *   각 int>=0, 둘 다 0은 불가) 필요 → canonical `{destNationID, amountList}`.
 *
 * fullConditionConstraints(che_물자원조.php:97-122), PHP ORDER:
 *   - amount > level*coefAidAmount: AlwaysFail('작위 제한량 이상은 보낼 수 없습니다.')
 *   - else: [ExistsDestNation, OccupiedCity, BeChief, SuppliedCity, DifferentDestNation,
 *            ReqNationGold(basegold + (gold>0?1:0)), ReqNationRice(baserice + (rice>0?1:0)),
 *            ReqNationValue('surlimit','외교제한','==',0,…), ReqDestNationValue('surlimit','외교제한','==',0,…)].
 *
 * 작위 제한량(level*coefAidAmount) 비교는 nation.level이 필요 — staging이 `__aidLimit`(Int)을 주면
 * 그것으로, 부재 시 `ctx.env["nationLevel"]`로 계산한다(둘 다 없으면 제한 미적용 → 통과).
 *
 * Live path: NationActionResolverRegistry + logic bridge. Remaining backlog:
 *   ACTING 라인(2) + pushGlobalHistoryLog.
 */
fun cheMuljaWonjo(pipeline: GeneralActionPipeline): CheMuljaWonjo =
    CheMuljaWonjo(pipeline)

class CheMuljaWonjo(private val pipeline: GeneralActionPipeline) : NationCommand() {
    override val key: String get() = "che_물자원조"
    override val name: String get() = "물자원조"
    override val category: String get() = "외교"

    /** che_물자원조.php:static $actionName = '원조' (시드 lottery 토큰). */
    override val lotteryActionName: String get() = "원조"
    override val argsSchema: Map<String, Any?> get() =
        linkedMapOf("destNationID" to "int", "amountList" to "intList")
    override val formSpec: CommandFormSpec get() = CommandFormSpec(
        listOf(
            CommandFieldSpec("destNationID", "int", "select", "nations"),
            CommandFieldSpec("amountList", "intList", "amountList", "nationResources", min = 0),
        ),
    )

    override fun getPreReqTurn(): Int = 0

    /** che_물자원조.php:getPostReqTurn = 12 (자체 postReqTurn; setNextAvailable은 no-op). */
    override fun getPostReqTurn(): Int = 12

    /**
     * che_물자원조.php:33-77 argTest. `destNationID`(int,>=1) + `amountList`(int 2개, 각 >=0, 둘 다 0
     * 불가). canonical `{destNationID, amountList}` 또는 invalid 시 null.
     */
    fun argTest(raw: Map<String, Any?>): Map<String, Any?>? {
        if (!raw.containsKey("destNationID")) return null
        val destNationID = (raw["destNationID"] as? Int) ?: return null  // PHP is_int
        if (destNationID < 1) return null
        val amountList = (raw["amountList"] as? List<*>) ?: return null  // PHP is_array
        if (amountList.size != 2) return null
        val goldAmount = (amountList[0] as? Int) ?: return null  // PHP is_int
        val riceAmount = (amountList[1] as? Int) ?: return null
        if (goldAmount < 0 || riceAmount < 0) return null
        if (goldAmount == 0 && riceAmount == 0) return null
        return linkedMapOf("destNationID" to destNationID, "amountList" to listOf(goldAmount, riceAmount))
    }

    override fun buildMinConstraints(ctx: ConstraintContext): List<Constraint> = listOf(
        occupiedCity(), beChief(), suppliedCity(),
        reqNationValue("surlimit", "외교제한", "==", 0, "외교제한중입니다."),
    )

    @Suppress("UNCHECKED_CAST")
    override fun buildConstraints(ctx: ConstraintContext): List<Constraint> {
        val amountList = (ctx.args["amountList"] as? List<*>)?.mapNotNull { (it as? Number)?.toInt() } ?: emptyList()
        val goldAmount = amountList.getOrElse(0) { 0 }
        val riceAmount = amountList.getOrElse(1) { 0 }

        // 작위 제한량 = level * coefAidAmount (che_물자원조.php:101-106). staging 우선.
        val aidLimit = (ctx.env["__aidLimit"] as? Number)?.toInt()
            ?: (ctx.env["nationLevel"] as? Number)?.toInt()?.let { it * GameConst.coefAidAmount }
        if (aidLimit != null && (goldAmount > aidLimit || riceAmount > aidLimit)) {
            return listOf(alwaysFail("작위 제한량 이상은 보낼 수 없습니다."))
        }

        return listOf(
            existsDestNation(), occupiedCity(), beChief(), suppliedCity(), differentDestNation(),
            reqNationGold { _, _ -> GameConst.basegold + (if (goldAmount > 0) 1 else 0) },
            reqNationRice { _, _ -> GameConst.baserice + (if (riceAmount > 0) 1 else 0) },
            reqNationValue("surlimit", "외교제한", "==", 0, "외교제한중입니다."),
            reqDestNationValue("surlimit", "외교제한", "==", 0, "상대국이 외교제한중입니다."),
        )
    }

    override fun parseArgs(raw: Map<String, Any?>): Map<String, Any?> = argTest(raw) ?: emptyMap()

    /**
     * che_물자원조.php:run() — RNG 미사용(deterministic, draw_count=0).
     *
     * 실행 순서(부수효과 순서 = 로그 순서):
     *  1) goldAmount/riceAmount를 valueFit([0, nation.gold-basegold] / [0, nation.rice-baserice])로 클램프.
     *  2) actor ActionLog 2줄: broadcast(MONTH) + PLAIN("…물자를 지원합니다. <1>date</>").
     *     (chief/dest-chief broadcast, *_HistoryLog, recv_assist KV는 별도 history/cross-general 싱크 —
     *      이 골든의 logLines/broadcastLines에는 포함되지 않으므로 actor ActionLog 2줄만 emit.)
     *  3) 아국 nation.gold/rice -= amount, surlimit += getPostReqTurn()(=12); dest nation.gold/rice += amount.
     *  4) addExperience(5) + addDedication(5) (트리거 폴드; 레벨 변동 시에만 PLAIN 로그 — 골든은 변동 없음).
     *
     * broadcastMessage(php) = "<D><b>{dest}</b></>{josaRo} 금<C>{gold}</> 쌀<C>{rice}</>을 지원했습니다."
     *   — '을'은 josa 픽이 아니라 하드코딩(PHP 원문 그대로).
     */
    override fun resolve(context: GeneralActionResolveContext) {
        val d = context.draft
        val nation = d.nation ?: return

        @Suppress("UNCHECKED_CAST")
        val amountList = (context.args["amountList"] as? List<*>)?.mapNotNull { (it as? Number)?.toInt() } ?: emptyList()
        var goldAmount = amountList.getOrElse(0) { 0 }
        var riceAmount = amountList.getOrElse(1) { 0 }

        // Util::valueFit(value, min, max) = clamp (che_물자원조.php:156-166).
        goldAmount = goldAmount.coerceIn(0, (nation.gold - GameConst.basegold).coerceAtLeast(0))
        riceAmount = riceAmount.coerceIn(0, (nation.rice - GameConst.baserice).coerceAtLeast(0))

        val goldAmountText = numberFormat(goldAmount)
        val riceAmountText = numberFormat(riceAmount)

        val destNationName = context.destGeneralName
        val josaRo = JosaUtil.pick(destNationName, "로")

        // (1) broadcast 라인(MONTH) — '을'은 PHP 원문 하드코딩 (che_물자원조.php:225 pushGeneralActionLog).
        context.addLog("<D><b>$destNationName</b></>$josaRo 금<C>$goldAmountText</> 쌀<C>$riceAmountText</>을 지원했습니다.")
        // (2) PLAIN 라인 — 자체 <1>date</> 접미사. che_물자원조.php:226 pushGeneralActionLog(.., PLAIN) =
        //     액터 자신의 액션로그(golden logLines)에 PLAIN 렌더로 들어간다 → addActionPlainLog(logs 스트림).
        context.addActionPlainLog("<D><b>$destNationName</b></>$josaRo 물자를 지원합니다. <1>${context.date}</>")

        // 아국 차감 + 외교제한(surlimit) += getPostReqTurn()(=12).
        val nextSurlimit = metaInt(nation.meta, "surlimit") + getPostReqTurn()
        d.nation = nation.copy(
            gold = nation.gold - goldAmount,
            rice = nation.rice - riceAmount,
            meta = withMeta(nation.meta, "surlimit" to nextSurlimit),
        )

        // 상대국 가산 (destNation carrier).
        d.destNation?.let { dn ->
            d.destNation = dn.copy(gold = dn.gold + goldAmount, rice = dn.rice + riceAmount)
        }

        // exp/ded += 5 (트리거 폴드; 레벨 변동 PLAIN은 변동 시에만).
        val expRes = addExperience(d.general, 5.0, pipeline)
        val dedRes = addDedication(expRes.general, 5.0, pipeline)
        d.general = dedRes.general
        expRes.plainLog?.let { context.addPlainLog(it) }
        dedRes.plainLog?.let { context.addPlainLog(it) }
    }
}
