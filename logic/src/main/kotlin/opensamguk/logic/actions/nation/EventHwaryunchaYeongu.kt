package opensamguk.logic.actions.nation

import opensamguk.common.constants.GameConst
import opensamguk.logic.actions.GeneralActionResolveContext
import opensamguk.logic.constraints.Constraint
import opensamguk.logic.constraints.ConstraintContext
import opensamguk.logic.constraints.beChief
import opensamguk.logic.constraints.occupiedCity
import opensamguk.logic.constraints.reqNationAuxValue
import opensamguk.logic.constraints.reqNationGold
import opensamguk.logic.constraints.reqNationRice
import opensamguk.logic.domestic.addDedication
import opensamguk.logic.domestic.addExperience
import opensamguk.logic.stats.GeneralActionPipeline

/**
 * event_화륜차연구 — STUB(포팅 대기) of `legacy/devsam-core/hwe/sammo/Command/Nation/event_화륜차연구.php`.
 *
 * 사령(BeChief) 전용 국가 연구 커맨드. run()은 RNG 미사용(deterministic, draw_count=0). 효과 순서:
 *   1) addExperience/addDedication += 5*(preReqTurn+1) = 120
 *   2) nation gold -= reqGold, rice -= reqRice, aux[can_화륜차사용]=1
 *   3) pushGeneralActionLog/pushGeneralHistoryLog "<M>{actionName}</> 완료"
 *   4) pushNationalHistoryLog "<Y>{generalName}</>{josaYi} <M>{actionName}</> 완료"
 *   5) increaseInheritancePoint(active_action, 1)
 * 인테이크는 general_turn 링(C3 chief-reserved family) — daemon이 resolve(code).parseArgs로 해소.
 *
 * fullConditionConstraints(event_화륜차연구.php init, first-deny-wins):
 *   [OccupiedCity, BeChief, ReqNationAuxValue(can_화륜차사용,0,"<",1,"{name}가 이미 완료되었습니다."),
 *    ReqNationGold(basegold+reqGold), ReqNationRice(baserice+reqRice)].
 *
 * TODO(포팅): run() — exp/ded → gold/rice/aux flush-delta → 3 logs → inheritance.
 */
fun eventHwaryunchaYeongu(pipeline: GeneralActionPipeline): NationCommand = EventHwaryunchaYeongu(pipeline)

class EventHwaryunchaYeongu(@Suppress("UNUSED_PARAMETER") private val pipeline: GeneralActionPipeline) : NationCommand() {
    override val key: String get() = "event_화륜차연구"
    override val name: String get() = "화륜차 연구"

    /** event_화륜차연구.php auxType — NationAuxKey::can_화륜차사용. */
    val auxKey: String get() = "can_화륜차사용"

    /** event_화륜차연구.php getPreReqTurn = 23 (reqTurn = 24). */
    override fun getPreReqTurn(): Int = 23

    /** event_화륜차연구.php getCost = [reqGold, reqRice]. */
    val reqGold: Int get() = 100000
    val reqRice: Int get() = 100000

    /** event_화륜차연구.php argTest = true (무인자). */
    private fun constraints(): List<Constraint> = listOf(
        occupiedCity(), beChief(),
        reqNationAuxValue(auxKey, 0, "<", 1, "${name}가 이미 완료되었습니다."),
        reqNationGold { _, _ -> GameConst.basegold + reqGold },
        reqNationRice { _, _ -> GameConst.baserice + reqRice },
    )

    override fun buildMinConstraints(ctx: ConstraintContext): List<Constraint> = constraints()
    override fun buildConstraints(ctx: ConstraintContext): List<Constraint> = constraints()

    override fun parseArgs(raw: Map<String, Any?>): Map<String, Any?> = emptyMap()  // zero-arg

    /**
     * event_화륜차연구.php run() — RNG 미사용(deterministic, draw_count=0). PHP run() 순서를 그대로 따른다.
     *
     *  1) addExperience/addDedication += 5*(preReqTurn+1) = 120 (run() :84-85, 국고 갱신/로그 BEFORE).
     *  2) nation gold -= reqGold, rice -= reqRice, aux[can_화륜차사용]=1 (run() :90-94, flush-delta로 draft.nation에만).
     *  3) pushGeneralActionLog "<M>{actionName}</> 완료" (run() :96) — actor action 로그(골든 logLines 타겟).
     *  4) pushGeneralHistoryLog/pushNationalHistoryLog (run() :97-98) — 다른 로그 스코프(general-history/
     *     national-history)로 flush되는 엔진-스코프 부수효과. C3 패밀리(CheChotohwa 등)와 동일하게 actor
     *     scope(GeneralActionResolveContext)에는 나타나지 않으며, pushGlobalActionLog가 아니므로 broadcast도
     *     비어 있다(골든 broadcastLines=[]). 골든은 nationalHistoryLines를 별도 surface로 캡처하되 검증은
     *     actor logLines + post-state delta로 한다.
     *  5) increaseInheritancePoint(active_action, 1) (run() :100) — InheritanceKey 적립은 P6 엔진-스코프 seam.
     *     actor scope에 노출되지 않음(골든 inheritanceAfter active_action=null, owner=0 — 캡처 환경 미적립).
     */
    override fun resolve(context: GeneralActionResolveContext) {
        val d = context.draft

        // 1) exp/ded += 120 (run() :84-85). PHP run() 순서상 국고 갱신/로그 BEFORE.
        val mag = expDedMagnitude().toDouble()  // 5*(23+1) = 120
        val expRes = addExperience(d.general, mag, pipeline)
        val dedRes = addDedication(expRes.general, mag, pipeline)
        d.general = dedRes.general
        // 레벨업/다운 PLAIN 라인(있을 때만). 골든(exp 4582→4702, 21레벨 불변)은 발생 안 함.
        expRes.plainLog?.let { context.addPlainLog(it) }
        dedRes.plainLog?.let { context.addPlainLog(it) }

        // 2) nation gold/rice 차감 + aux[can_화륜차사용]=1 (run() :90-94). draft.nation에만 기록(flush-delta).
        val nation = d.nation
        if (nation != null) {
            d.nation = nation.copy(
                gold = nation.gold - reqGold,
                rice = nation.rice - reqRice,
                meta = withNationAux(nation.meta, auxKey to 1),
            )
        }

        // 3) actor action 로그 (run() :96 pushGeneralActionLog). <M>{actionName}</> 완료.
        context.addLog("<M>$name</> 완료")

        // 4) general-history/national-history (run() :97-98)는 엔진-스코프 history flush — actor scope 미노출.
        // 5) increaseInheritancePoint(active_action, 1) (run() :100)은 P6 inheritance seam — actor scope 미노출.
    }
}
