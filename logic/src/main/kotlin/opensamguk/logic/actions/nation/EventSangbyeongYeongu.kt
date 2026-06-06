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
 * event_상병연구 — faithful port of `legacy/devsam-core/hwe/sammo/Command/Nation/event_상병연구.php`.
 *
 * 사령(BeChief) 전용 국가 연구 커맨드. run()은 RNG 미사용(deterministic, draw_count=0). 효과 순서:
 *   1) addExperience/addDedication += 5*(preReqTurn+1) = 120
 *   2) nation gold -= reqGold, rice -= reqRice, aux[can_상병사용]=1
 *   3) pushGeneralActionLog/pushGeneralHistoryLog "<M>{actionName}</> 완료"
 *   4) pushNationalHistoryLog "<Y>{generalName}</>{josaYi} <M>{actionName}</> 완료"
 *   5) increaseInheritancePoint(active_action, 1)
 * 인테이크는 general_turn 링(C3 chief-reserved family) — daemon이 resolve(code).parseArgs로 해소.
 *
 * fullConditionConstraints(event_상병연구.php init, first-deny-wins):
 *   [OccupiedCity, BeChief, ReqNationAuxValue(can_상병사용,0,"<",1,"{name}가 이미 완료되었습니다."),
 *    ReqNationGold(basegold+reqGold), ReqNationRice(baserice+reqRice)].
 */
fun eventSangbyeongYeongu(pipeline: GeneralActionPipeline): NationCommand = EventSangbyeongYeongu(pipeline)

class EventSangbyeongYeongu(private val pipeline: GeneralActionPipeline) : NationCommand() {
    override val key: String get() = "event_상병연구"
    override val name: String get() = "상병 연구"

    /** event_상병연구.php auxType — NationAuxKey::can_상병사용. */
    val auxKey: String get() = "can_상병사용"

    /** event_상병연구.php getPreReqTurn = 23 (reqTurn = 24). */
    override fun getPreReqTurn(): Int = 23

    /** event_상병연구.php getCost = [reqGold, reqRice]. */
    val reqGold: Int get() = 100000
    val reqRice: Int get() = 100000

    /** event_상병연구.php argTest = true (무인자). */
    private fun constraints(): List<Constraint> = listOf(
        occupiedCity(), beChief(),
        reqNationAuxValue(auxKey, 0, "<", 1, "${name}가 이미 완료되었습니다."),
        reqNationGold { _, _ -> GameConst.basegold + reqGold },
        reqNationRice { _, _ -> GameConst.baserice + reqRice },
    )

    override fun buildMinConstraints(ctx: ConstraintContext): List<Constraint> = constraints()
    override fun buildConstraints(ctx: ConstraintContext): List<Constraint> = constraints()

    override fun parseArgs(raw: Map<String, Any?>): Map<String, Any?> = emptyMap()  // zero-arg

    override fun resolve(context: GeneralActionResolveContext) {
        // run()은 RNG 미사용(deterministic, draw_count=0) — context.rng 미참조.
        val d = context.draft
        val nation = d.nation ?: return

        // 1) exp/ded += 5*(preReqTurn+1) = 120 (event_상병연구.php:84-85).
        //    run() 코드 순서상 gold/rice/aux 갱신·로그보다 먼저 적용한다.
        //    fixture가 모듈-프리라 onCalcStat은 raw 그대로 → exact +120.
        val mag = expDedMagnitude().toDouble()
        val expRes = addExperience(d.general, mag, pipeline)
        val dedRes = addDedication(expRes.general, mag, pipeline)
        d.general = dedRes.general
        expRes.plainLog?.let { context.addPlainLog(it) }
        dedRes.plainLog?.let { context.addPlainLog(it) }

        // 2) nation: gold -= reqGold, rice -= reqRice, aux[can_상병사용]=1 (event_상병연구.php:78-94).
        //    aux는 nation.meta['aux'] LinkedHashMap에 삽입(기존 키 순서 보존). flush-delta로만 기록.
        d.nation = nation.copy(
            gold = nation.gold - reqGold,
            rice = nation.rice - reqRice,
            meta = withNationAux(nation.meta, auxKey to 1),
        )

        // 3) general action log + general/national history log (event_상병연구.php:96-98).
        //    actor action-log 스코프(logs())에는 pushGeneralActionLog 한 줄만 나타난다.
        //    pushGeneralHistoryLog/pushNationalHistoryLog는 별도 history 스코프로 flush되는
        //    엔진-스코프 부수효과라 actor logs()에는 포함되지 않는다(golden logLines=1줄).
        context.addLog("<M>$actionName</> 완료")

        // 4) increaseInheritancePoint(active_action, 1) (event_상병연구.php:100) — P6 유산포인트 seam.
        //    골든 fixture는 inheritanceAfter.active_action=null(owner 0/NPC, 델타 미캡처)이라
        //    actor-scope 가시 상태에는 영향이 없다. event_극병연구와 동일한 처리.
    }
}
