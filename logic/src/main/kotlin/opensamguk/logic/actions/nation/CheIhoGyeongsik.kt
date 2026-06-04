package opensamguk.logic.actions.nation

import opensamguk.logic.actions.GeneralActionResolveContext
import opensamguk.logic.constraints.Constraint
import opensamguk.logic.constraints.ConstraintContext
import opensamguk.logic.constraints.allowDiplomacyBetweenStatus
import opensamguk.logic.constraints.beChief
import opensamguk.logic.constraints.existsDestNation
import opensamguk.logic.constraints.occupiedCity
import opensamguk.logic.diplomacy.DiplomacyState
import opensamguk.logic.domain.Diplomacy
import opensamguk.logic.domestic.addDedication
import opensamguk.logic.domestic.addExperience
import opensamguk.logic.stats.GeneralActionPipeline

/**
 * che_이호경식 — faithful port of `legacy/devsam-core/hwe/sammo/Command/Nation/che_이호경식.php`.
 *
 * 전략(strategic) 사령 커맨드. 선포/전쟁중(state 0,1) 상대국에 대해 외교 term +3 + state→1,
 * SetNationFront 양국. dest-nation PLAIN + national-history. RNG 미사용(deterministic). logLines=1.
 *
 * argTest(che_이호경식.php:30-50): `destNationID`(int, >=1) 필요 → canonical `{destNationID}`.
 *
 * fullConditionConstraints(che_이호경식.php:70-83), PHP ORDER:
 *   [OccupiedCity, BeChief, ExistsDestNation, AllowDiplomacyBetweenStatus([0,1],…), AvailableStrategicCommand].
 *
 * run()(che_이호경식.php:122-201): 이호경식 발동 로그 1줄 + exp/ded +5 + nation strategic_cmd_limit=9 +
 * diplomacy state→1 + term +3(양방향). 아국/적국 장수 개별 PLAIN 로그·국사/국가 로그는 다른 ActionLogger로
 * flush되는 엔진-스코프 부수효과(che_이호경식.php:161-182)라 actor scope에 나타나지 않으며 pushGlobalActionLog도
 * 호출하지 않음 → broadcastLines=[]. SetNationFront 양국은 엔진-스코프(che_이호경식.php:192-193).
 */
fun cheIhoGyeongsik(pipeline: GeneralActionPipeline): CheIhoGyeongsik =
    CheIhoGyeongsik(pipeline)

class CheIhoGyeongsik(private val pipeline: GeneralActionPipeline) : NationCommand() {
    override val key: String get() = "che_이호경식"
    override val name: String get() = "이호경식"
    override val category: String get() = "전략"
    override val argsSchema: Map<String, Any?> get() = linkedMapOf("destNationID" to "int")

    override fun getPreReqTurn(): Int = 0

    /** che_이호경식.php:30-50 argTest. canonical `{destNationID}` 또는 invalid 시 null. */
    fun argTest(raw: Map<String, Any?>): Map<String, Any?>? {
        if (!raw.containsKey("destNationID")) return null
        val destNationID = (raw["destNationID"] as? Int) ?: return null  // PHP is_int
        if (destNationID < 1) return null
        return linkedMapOf("destNationID" to destNationID)
    }

    override fun buildMinConstraints(ctx: ConstraintContext): List<Constraint> = listOf(
        occupiedCity(), beChief(), availableStrategicCommand(),
    )

    override fun buildConstraints(ctx: ConstraintContext): List<Constraint> = listOf(
        occupiedCity(), beChief(), existsDestNation(),
        allowDiplomacyBetweenStatus(listOf(0, 1), "선포, 전쟁중인 상대국에게만 가능합니다."),
        availableStrategicCommand(),
    )

    override fun parseArgs(raw: Map<String, Any?>): Map<String, Any?> = argTest(raw) ?: emptyMap()

    override fun resolve(context: GeneralActionResolveContext) {
        val d = context.draft
        val destNationID = (context.args["destNationID"] as? Number)?.toInt() ?: return

        // 1) actor action log — che_이호경식.php:150-154 pushGeneralActionLog("{commandName} 발동! <1>{date}</>")
        //    commandName = getName() = "이호경식"; date suffix는 turn-time HH:MM.
        context.addLog("$name 발동! <1>${context.date}</>")

        // 2) exp/ded += 5 * (preReqTurn + 1) = 5 (che_이호경식.php:156-157). PHP run() 순서상 로그 다음.
        val mag = expDedMagnitude().toDouble()
        val expRes = addExperience(d.general, mag, pipeline)
        val dedRes = addDedication(expRes.general, mag, pipeline)
        d.general = dedRes.general
        expRes.plainLog?.let { context.addPlainLog(it) }
        dedRes.plainLog?.let { context.addPlainLog(it) }

        // 3) broadcastMessage(아국/적국 장수 개별 PLAIN 로그 + 국가 국사 로그)는 다른 장수의 ActionLogger로
        //    flush되는 엔진-스코프 부수효과(che_이호경식.php:159-182) — actor scope에 나타나지 않으며
        //    pushGlobalActionLog가 아니므로 broadcast 로그도 비어 있다(golden broadcastLines=[]).

        // 4) nation.strategic_cmd_limit = onCalcStrategic(name, 'globalDelay', 9) (che_이호경식.php:184-186).
        //    기본 국가(음양가/종횡가 아님)는 9 그대로.
        val nation = d.nation
        if (nation != null) {
            val globalDelay = pipeline.onCalcStrategic(d.general, name, "globalDelay", 9.0).toInt()
            d.nation = nation.copy(meta = nation.meta + ("strategic_cmd_limit" to globalDelay))
        }

        // 5) diplomacy state→1 + term = IF(state=0, 3, term+3) (양방향) (che_이호경식.php:187-190).
        //    선포(1)/전쟁중(0) 상대에 한해 발동(AllowDiplomacyBetweenStatus([0,1])). cascadeDiplomacy는
        //    additive term +3 델타를 state=DECLARATION으로 인코딩 — engine 행 단위 IF(state=0,3,…) 적용은 CheGeupseup(-3)과 동일.
        //    SetNationFront 양국(che_이호경식.php:192-193)은 엔진-스코프 부수효과.
        val me = nation?.id ?: return
        d.cascadeDiplomacy.add(Diplomacy(me = me, you = destNationID, state = DiplomacyState.DECLARATION, term = 3))
        d.cascadeDiplomacy.add(Diplomacy(me = destNationID, you = me, state = DiplomacyState.DECLARATION, term = 3))
    }
}
