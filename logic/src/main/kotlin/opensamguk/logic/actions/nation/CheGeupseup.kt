package opensamguk.logic.actions.nation

import opensamguk.common.josa.JosaUtil
import opensamguk.logic.actions.GeneralActionResolveContext
import opensamguk.logic.constraints.Constraint
import opensamguk.logic.constraints.ConstraintContext
import opensamguk.logic.constraints.allowDiplomacyWithTerm
import opensamguk.logic.constraints.beChief
import opensamguk.logic.constraints.existsDestNation
import opensamguk.logic.constraints.occupiedCity
import opensamguk.logic.domain.Diplomacy
import opensamguk.logic.domestic.addDedication
import opensamguk.logic.domestic.addExperience
import opensamguk.logic.stats.GeneralActionPipeline

/**
 * che_급습 — faithful port of `legacy/devsam-core/hwe/sammo/Command/Nation/che_급습.php`.
 *
 * 전략(strategic) 사령 커맨드. 선포(state 1) 12개월 이상인 상대국에 대해 외교 term을 -3 한다.
 * dest-nation PLAIN 로그 + national-history 라인을 쓴다. RNG 미사용(deterministic). logLines=1.
 *
 * argTest(che_급습.php:30-50): `destNationID`(int, >=1) 필요 → canonical `{destNationID}`
 *   (멸망 직전 턴 입력 허용 — 존재하지 않는 국가여도 argTest에서 탈락시키지 않음).
 *
 * fullConditionConstraints(che_급습.php:77-86), PHP ORDER(first-deny-wins):
 *   [OccupiedCity, BeChief, ExistsDestNation, AllowDiplomacyWithTerm(1, 12, …), AvailableStrategicCommand].
 *
 * TODO(포팅): run() — diplomacy term -3 적용 + dest-nation PLAIN/national-history 로그.
 */
fun cheGeupseup(pipeline: GeneralActionPipeline): CheGeupseup = CheGeupseup(pipeline)

class CheGeupseup(private val pipeline: GeneralActionPipeline) : NationCommand() {
    override val key: String get() = "che_급습"
    override val name: String get() = "급습"
    override val category: String get() = "전략"
    override val argsSchema: Map<String, Any?> get() = linkedMapOf("destNationID" to "int")

    override fun getPreReqTurn(): Int = 0

    /** che_급습.php:30-50 argTest. canonical `{destNationID}` 또는 invalid 시 null. */
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
        allowDiplomacyWithTerm(1, 12, "선포 12개월 이상인 상대국에만 가능합니다."),
        availableStrategicCommand(),
    )

    override fun parseArgs(raw: Map<String, Any?>): Map<String, Any?> = argTest(raw) ?: emptyMap()

    override fun resolve(context: GeneralActionResolveContext) {
        val d = context.draft
        val destNationID = (context.args["destNationID"] as? Number)?.toInt() ?: return

        // 1) actor action log — che_급습.php:157 pushGeneralActionLog("{commandName} 발동! <1>{date}</>")
        //    commandName = getName() = "급습"; date suffix는 turn-time HH:MM.
        context.addLog("$name 발동! <1>${context.date}</>")

        // 2) exp/ded += 5 * (preReqTurn + 1) = 5 (che_급습.php:159-160). PHP run() 순서상 로그 다음.
        val mag = expDedMagnitude().toDouble()
        val expRes = addExperience(d.general, mag, pipeline)
        val dedRes = addDedication(expRes.general, mag, pipeline)
        d.general = dedRes.general
        expRes.plainLog?.let { context.addPlainLog(it) }
        dedRes.plainLog?.let { context.addPlainLog(it) }

        // 3) broadcastMessage(아국/적국 장수 개별 PLAIN 로그 + 양국 사기·국사 로그)는 다른 장수의 ActionLogger로
        //    flush되는 엔진-스코프 부수효과(che_급습.php:162-187) — actor scope에는 나타나지 않으며
        //    pushGlobalActionLog가 아니므로 broadcast 로그도 비어 있다(golden broadcastLines=[]).

        // 4) nation.strategic_cmd_limit = onCalcStrategic(name, 'globalDelay', 9) (che_급습.php:189-191).
        //    기본 국가(음양가/종횡가 아님)는 9 그대로.
        val nation = d.nation
        if (nation != null) {
            val globalDelay = pipeline.onCalcStrategic(d.general, name, "globalDelay", 9.0).toInt()
            d.nation = nation.copy(meta = nation.meta + ("strategic_cmd_limit" to globalDelay))
        }

        // 5) diplomacy term -= 3 (양방향) (che_급습.php:192-194). state 변동 없음.
        val me = nation?.id ?: return
        d.cascadeDiplomacy.add(Diplomacy(me = me, you = destNationID, state = DIPLO_STATE_DECLARE, term = -3))
        d.cascadeDiplomacy.add(Diplomacy(me = destNationID, you = me, state = DIPLO_STATE_DECLARE, term = -3))
    }

    private companion object {
        /** 급습은 선포(state=1) 12개월 이상인 상대국에만 가능 — diplomacy term delta(-3)만 적용, state는 유지. */
        const val DIPLO_STATE_DECLARE = 1
    }
}
