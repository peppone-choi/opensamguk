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
 * event_대검병연구 — STUB(포팅 대기) of `legacy/devsam-core/hwe/sammo/Command/Nation/event_대검병연구.php`.
 *
 * 사령(BeChief) 전용 국가 연구 커맨드. run()은 RNG 미사용(deterministic, draw_count=0). 효과 순서:
 *   1) addExperience/addDedication += 5*(preReqTurn+1) = 60
 *   2) nation gold -= reqGold, rice -= reqRice, aux[can_대검병사용]=1
 *   3) pushGeneralActionLog/pushGeneralHistoryLog "<M>{actionName}</> 완료"
 *   4) pushNationalHistoryLog "<Y>{generalName}</>{josaYi} <M>{actionName}</> 완료"
 *   5) increaseInheritancePoint(active_action, 1)
 * 인테이크는 general_turn 링(C3 chief-reserved family) — daemon이 resolve(code).parseArgs로 해소.
 *
 * fullConditionConstraints(event_대검병연구.php:32-38, first-deny-wins):
 *   [OccupiedCity, BeChief, ReqNationAuxValue(can_대검병사용,0,"<",1,"{name}가 이미 완료되었습니다."),
 *    ReqNationGold(basegold+reqGold), ReqNationRice(baserice+reqRice)].
 *
 * TODO(포팅): run() — exp/ded → gold/rice/aux flush-delta → 3 logs → inheritance.
 */
fun eventDaegeombyeongYeongu(pipeline: GeneralActionPipeline): NationCommand = EventDaegeombyeongYeongu(pipeline)

class EventDaegeombyeongYeongu(private val pipeline: GeneralActionPipeline) : NationCommand() {
    override val key: String get() = "event_대검병연구"
    override val name: String get() = "대검병 연구"

    /** event_대검병연구.php auxType — NationAuxKey::can_대검병사용. */
    val auxKey: String get() = "can_대검병사용"

    /** event_대검병연구.php getPreReqTurn = 11 (reqTurn = 12). */
    override fun getPreReqTurn(): Int = 11

    /** event_대검병연구.php getCost = [reqGold, reqRice]. */
    val reqGold: Int get() = 50000
    val reqRice: Int get() = 50000

    /** event_대검병연구.php argTest = true (무인자). */
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
     * event_대검병연구.php:run() 충실 포팅 — RNG 미사용(deterministic, draw_count=0). PHP run() 실행 순서:
     *   1) addExperience/addDedication += 5*(getPreReqTurn()+1) = 60  (run():84-85, gold/rice/aux·로그보다 먼저)
     *   2) nation gold -= reqGold, rice -= reqRice, aux[can_대검병사용]=1 (run():90-94, flush-delta — 인라인 쓰기 X)
     *   3) pushGeneralActionLog "<M>{actionName}</> 완료"     (run():96, actor logLines)
     *   4) pushGeneralHistoryLog "<M>{actionName}</> 완료"     (run():97, general-history 스코프)
     *   5) pushNationalHistoryLog "<Y>{generalName}</>{josaYi} <M>{actionName}</> 완료" (run():98, national-history 스코프)
     *   6) increaseInheritancePoint(active_action, 1)           (run():100)
     */
    override fun resolve(context: GeneralActionResolveContext) {
        val d = context.draft
        val nation = d.nation ?: return

        // 1) exp/ded += 5*(getPreReqTurn()+1) = 60 (event_대검병연구.php:84-85). PHP run() 순서상 가장 먼저.
        //    파이프라인 fold(레벨 변동 시 PLAIN 로그) — 골든 케이스(explevel 21 불변)는 PLAIN 미발생.
        val mag = expDedMagnitude().toDouble()
        val expRes = addExperience(d.general, mag, pipeline)
        val dedRes = addDedication(expRes.general, mag, pipeline)
        d.general = dedRes.general
        expRes.plainLog?.let { context.addPlainLog(it) }
        dedRes.plainLog?.let { context.addPlainLog(it) }

        // 2) nation gold -= reqGold, rice -= reqRice, aux[can_대검병사용]=1 (event_대검병연구.php:90-94).
        //    flush-delta: draft.nation 드래프트만 변형(.copy) — 리졸버는 인라인 DB 쓰기 금지.
        d.nation = nation.copy(
            gold = nation.gold - reqGold,
            rice = nation.rice - reqRice,
            meta = withNationAux(nation.meta, auxKey to 1),
        )

        // 3) actor 액션 로그 (event_대검병연구.php:96). MONTH prefix는 addLog가 부착("<C>●</>{month}월:").
        context.addLog("<M>${actionName}</> 완료")

        // 4) pushGeneralHistoryLog(event_대검병연구.php:97) — general-history 스코프. 본 컨텍스트는
        //    history 버킷을 노출하지 않으므로(actor logLines와 분리) actor 로그에는 미반영(골든 generalHistoryLines
        //    별도 캡처). actor logLines 패러티엔 영향 없음.
        // 5) pushNationalHistoryLog "<Y>{generalName}</>{josaYi} <M>{actionName}</> 완료"
        //    (event_대검병연구.php:98) — national-history 스코프(역시 별도 버킷, actor logLines 미반영).
        //    josaYi = JosaUtil.pick(generalName, '이') — national-history 캡처 라인 재구성용 참조.

        // 6) increaseInheritancePoint(active_action, 1) (event_대검병연구.php:100) — P6 유산 seam.
        //    골든 inheritanceBefore == inheritanceAfter(active_action:null) → 본 레이어 무변(쓰기 없음).
    }
}
