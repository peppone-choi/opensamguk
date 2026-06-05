package opensamguk.logic.actions.nation

import opensamguk.common.josa.JosaUtil
import opensamguk.logic.actions.GeneralActionResolveContext
import opensamguk.logic.constraints.Constraint
import opensamguk.logic.constraints.ConstraintContext
import opensamguk.logic.constraints.allowDiplomacyStatus
import opensamguk.logic.constraints.beChief
import opensamguk.logic.constraints.occupiedCity
import opensamguk.logic.domestic.addDedication
import opensamguk.logic.domestic.addExperience
import opensamguk.logic.stats.GeneralActionPipeline

/**
 * che_필사즉생 — faithful port of `legacy/devsam-core/hwe/sammo/Command/Nation/che_필사즉생.php`.
 *
 * 전략(strategic) 사령 커맨드. 아국이 교전중(state 0)일 때, 모든 아국 장수의 train/atmos를 100으로
 * 만든다(장수마다 PLAIN dest 라인). RNG 미사용(deterministic). 무인자(zero-arg). logLines=1, crossGeneral.
 *
 * fullConditionConstraints(che_필사즉생.php:37-45), PHP ORDER:
 *   [OccupiedCity, BeChief, AllowDiplomacyStatus(nationID, [0], '전쟁중이 아닙니다.'), AvailableStrategicCommand].
 *   AllowDiplomacyStatus([0]) = 아국이 교전중(state 0)인 외교관계가 하나라도 있어야 함.
 *
 * AllowDiplomacyStatus의 hasAllowedState 술어는 외교 preload(staging) 대상 —
 *   `ctx.env["__atWar"]`가 없으면 false(deferring)로 떨어진다(che_방랑 allowDiplomacyStatus 패턴).
 *
 * run()(che_필사즉생.php:84-138): RNG 미사용(deterministic, draw_count=0). 실행 순서:
 *   1) actor action 로그 "필사즉생 발동! <1>{date}</>" (pushGeneralActionLog).
 *   2) exp/ded += 5 * (preReqTurn + 1) = 15 (addExperience/addDedication, onCalcStat 폴드).
 *   3) 아국 장수(본인 제외) 순회: 각자 ActionLogger PLAIN broadcast
 *      "<Y>{actorName}</>{josaYi} <M>필사즉생</>을 발동하였습니다." + train<100→100, atmos<100→100.
 *   4) actor 본인 train<100→100, atmos<100→100.
 *   5) 국사/국가 history 로그(pushGeneralHistoryLog/pushNationalHistoryLog)는 별도 채널 —
 *      actor action scope에 안 나오고 pushGlobalActionLog도 아니므로 broadcastLines=[].
 *   6) nation.strategic_cmd_limit = onCalcStrategic(name,'globalDelay',9) = 9.
 *   broadcast 메시지는 actor 이름($generalName)으로 한 번 만들어 모든 dest에 동일 적용.
 */
fun chePilsaJeukSaeng(pipeline: GeneralActionPipeline): ChePilsaJeukSaeng =
    ChePilsaJeukSaeng(pipeline)

class ChePilsaJeukSaeng(private val pipeline: GeneralActionPipeline) : NationCommand() {
    override val key: String get() = "che_필사즉생"
    override val name: String get() = "필사즉생"
    override val category: String get() = "전략"

    /** che_필사즉생.php:getPreReqTurn = 2 (reqTurn = 3). */
    override fun getPreReqTurn(): Int = 2

    override fun buildMinConstraints(ctx: ConstraintContext): List<Constraint> = buildConstraints(ctx)

    override fun buildConstraints(ctx: ConstraintContext): List<Constraint> = listOf(
        occupiedCity(), beChief(),
        allowDiplomacyStatus("전쟁중이 아닙니다.") { c, _ ->
            (c.env["__atWar"] as? Boolean) ?: (c.args["__atWar"] as? Boolean) ?: false
        },
        availableStrategicCommand(),
    )

    override fun parseArgs(raw: Map<String, Any?>): Map<String, Any?> = emptyMap()  // zero-arg

    override fun resolve(context: GeneralActionResolveContext) {
        val d = context.draft

        // 1) actor action 로그 — che_필사즉생.php:97 pushGeneralActionLog("필사즉생 발동! <1>{date}</>").
        context.addLog("필사즉생 발동! <1>${context.date}</>")

        // 2) exp/ded += 5 * (preReqTurn + 1) = 15 (che_필사즉생.php:99-100). PHP run() 순서상 로그 다음.
        val mag = expDedMagnitude().toDouble()
        val expRes = addExperience(d.general, mag, pipeline)
        val dedRes = addDedication(expRes.general, mag, pipeline)
        d.general = dedRes.general
        expRes.plainLog?.let { context.addPlainLog(it) }
        dedRes.plainLog?.let { context.addPlainLog(it) }

        // 3) broadcast 메시지는 actor 이름으로 한 번만 만든다 (che_필사즉생.php:104).
        val josaYi = JosaUtil.pick(context.generalName, "이")
        val broadcastMessage = "<Y>${context.generalName}</>$josaYi <M>필사즉생</>을 발동하였습니다."

        // 4) 아국 장수(본인 제외) 순회 — 각자 ActionLogger PLAIN broadcast + train/atmos<100→100
        //    (che_필사즉생.php:106-118). candidateGenerals = `SELECT no FROM general WHERE nation=me AND no!=me`
        //    프리로드(adapter staged). 변경된 사본을 cascadeGenerals에 append.
        for (target in context.candidateGenerals) {
            context.addPlainLogTo(target.id, broadcastMessage)
            val moved = target.copy(
                train = if (target.train < 100.0) 100.0 else target.train,
                atmos = if (target.atmos < 100.0) 100.0 else target.atmos,
            )
            d.cascadeGenerals.add(moved)
        }

        // 5) actor 본인 train/atmos<100→100 (che_필사즉생.php:120-125).
        val self = d.general
        d.general = self.copy(
            train = if (self.train < 100.0) 100.0 else self.train,
            atmos = if (self.atmos < 100.0) 100.0 else self.atmos,
        )

        // 6) 국사/국가 history 로그(che_필사즉생.php:126-127)는 별도 채널 — actor action scope에 안 나오고
        //    pushGlobalActionLog도 아니므로 broadcastLines=[]에 기여하지 않음.

        // 7) nation.strategic_cmd_limit = onCalcStrategic(name, 'globalDelay', 9) (che_필사즉생.php:129-131).
        //    기본 국가(음양가/종횡가 아님)는 9 그대로.
        val nation = d.nation
        if (nation != null) {
            val globalDelay = pipeline.onCalcStrategic(d.general, name, "globalDelay", 9.0).toInt()
            d.nation = nation.copy(meta = nation.meta + ("strategic_cmd_limit" to globalDelay))
        }
    }
}
