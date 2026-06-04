package opensamguk.logic.actions.nation

import opensamguk.common.josa.JosaUtil
import opensamguk.logic.actions.GeneralActionResolveContext
import opensamguk.logic.constraints.Constraint
import opensamguk.logic.constraints.ConstraintContext
import opensamguk.logic.constraints.beChief
import opensamguk.logic.constraints.existsDestGeneral
import opensamguk.logic.constraints.friendlyDestGeneral
import opensamguk.logic.constraints.notBeNeutral
import opensamguk.logic.stats.GeneralActionPipeline

/**
 * che_부대탈퇴지시 — faithful port of `legacy/devsam-core/hwe/sammo/Command/Nation/che_부대탈퇴지시.php`.
 *
 * personnel(인사) 사령 커맨드. 아군(friendly) dest 장수를 소속 부대에서 강제 탈퇴시킨다(troop != 0 이고
 * != self인 MEMBER). dest action 라인. (troop==0 / troop==self은 다른 단일-라인 결과.) RNG 미사용
 * (deterministic). logLines=1, crossGeneral. actionName = '부대 탈퇴 지시'(공백 포함, lotteryActionName).
 *
 * argTest(che_부대탈퇴지시.php:30-44): `destGeneralID`(int,>0) 필요 → canonical `{destGeneralID}`.
 *
 * fullConditionConstraints(che_부대탈퇴지시.php:69-83), PHP ORDER:
 *   - self-target(destGeneralID==actor): AlwaysFail('본인입니다')
 *   - else: [NotBeNeutral, BeChief, ExistsDestGeneral, FriendlyDestGeneral].
 *
 * run()(che_부대탈퇴지시.php:100-141) 3분기:
 *   - troopID==0  → 액터 라인 "<Y>{dest}</>{은} 부대원이 아닙니다." (dest 미변경, dest 라인 없음).
 *   - troopID==dest.id → 액터 라인 "<Y>{dest}</>{은} 부대장입니다." (dest 미변경, dest 라인 없음).
 *   - else(MEMBER) → dest.troop=0, 액터 라인 "<Y>{dest}</>에게 부대 탈퇴를 지시했습니다.",
 *     dest 라인 "<Y>{actor}</>에게 부대 탈퇴를 지시 받았습니다." (둘 다 josa 없는 고정 '에게').
 */
fun cheBudaeTaltoejisi(@Suppress("UNUSED_PARAMETER") pipeline: GeneralActionPipeline): CheBudaeTaltoejisi =
    CheBudaeTaltoejisi(pipeline)

class CheBudaeTaltoejisi(@Suppress("UNUSED_PARAMETER") pipeline: GeneralActionPipeline) : NationCommand() {
    override val key: String get() = "che_부대탈퇴지시"
    override val name: String get() = "부대탈퇴지시"
    override val category: String get() = "인사"

    /** che_부대탈퇴지시.php:static $actionName = '부대 탈퇴 지시' (공백 포함 — 시드 lottery 토큰). */
    override val lotteryActionName: String get() = "부대 탈퇴 지시"
    override val argsSchema: Map<String, Any?> get() = linkedMapOf("destGeneralID" to "int")

    override fun getPreReqTurn(): Int = 0

    /** che_부대탈퇴지시.php:30-44 argTest. canonical `{destGeneralID}` 또는 invalid 시 null. */
    fun argTest(raw: Map<String, Any?>): Map<String, Any?>? {
        if (!raw.containsKey("destGeneralID")) return null
        val destGeneralID = (raw["destGeneralID"] as? Int) ?: return null  // PHP is_int
        if (destGeneralID <= 0) return null
        return linkedMapOf("destGeneralID" to destGeneralID)
    }

    override fun buildMinConstraints(ctx: ConstraintContext): List<Constraint> = listOf(
        notBeNeutral(), beChief(),
    )

    override fun buildConstraints(ctx: ConstraintContext): List<Constraint> {
        // self-target → AlwaysFail '본인입니다' (che_부대탈퇴지시.php:73-77).
        if ((ctx.args["destGeneralID"] as? Number)?.toInt() == ctx.actorId) {
            return listOf(alwaysFail("본인입니다"))
        }
        return listOf(
            notBeNeutral(), beChief(), existsDestGeneral(), friendlyDestGeneral(),
        )
    }

    override fun parseArgs(raw: Map<String, Any?>): Map<String, Any?> = argTest(raw) ?: emptyMap()

    override fun resolve(context: GeneralActionResolveContext) {
        val d = context.draft
        val destGeneral = d.destGeneral ?: return
        val destName = context.destGeneralName

        val troopId = destGeneral.troop
        // 분기 1: 부대 미소속 → 액터 라인만, dest 미변경 (che_부대탈퇴지시.php:115-121).
        if (troopId == 0) {
            val josaUn = JosaUtil.pick(destName, "은")
            context.addLog("<Y>$destName</>$josaUn 부대원이 아닙니다.")
            return
        }
        // 분기 2: 부대장 본인 → 액터 라인만, dest 미변경 (che_부대탈퇴지시.php:123-128).
        if (troopId == destGeneral.id) {
            val josaUn = JosaUtil.pick(destName, "은")
            context.addLog("<Y>$destName</>$josaUn 부대장입니다.")
            return
        }

        // 분기 3: 부대원 → troop=0 강제 탈퇴 (che_부대탈퇴지시.php:130-138). RNG 미사용.
        d.destGeneral = destGeneral.copy(troop = 0)

        // 액터 라인 + dest 라인 (josa 없는 고정 '에게'). dest 라인은 MONTH body(접두사 없음) — dest
        // 로거가 flush 시 `<C>●</>{month}월:`로 감싼다 (발령과 동일 경로, addLogTo).
        context.addLog("<Y>$destName</>에게 부대 탈퇴를 지시했습니다.")
        context.addLogTo(destGeneral.id, "<Y>${context.generalName}</>에게 부대 탈퇴를 지시 받았습니다.")
    }
}
