package opensamguk.logic.actions

import opensamguk.logic.actions.develop.cheGisulYeongu
import opensamguk.logic.actions.develop.cheJeongchakJangnyeo
import opensamguk.logic.actions.develop.cheJuminSeonjeong
import opensamguk.logic.actions.develop.cheGunryangMaemae
import opensamguk.logic.actions.develop.cheMuljaJodal
import opensamguk.logic.actions.military.CheHullyeon
import opensamguk.logic.actions.military.CheIdong
import opensamguk.logic.actions.military.CheJiphap
import opensamguk.logic.actions.military.CheSagiJinjak
import opensamguk.logic.actions.military.CheSojipHaeje
import opensamguk.logic.actions.military.CrMaenghullyeon
import opensamguk.logic.actions.military.RecruitAlgorithm
import opensamguk.logic.actions.personnel.CheBangrang
import opensamguk.logic.actions.personnel.CheDeungyong
import opensamguk.logic.actions.personnel.CheEuntwe
import opensamguk.logic.actions.personnel.CheHaya
import opensamguk.logic.actions.personnel.CheImgwan
import opensamguk.logic.actions.personnel.CheJangsuDaesangImgwan
import opensamguk.logic.actions.personnel.CheRandomImgwan
import opensamguk.logic.constraints.*
import opensamguk.logic.stats.GeneralActionPipeline

/**
 * The 휴식 fallback: a no-op rest turn. No constraints, no mutation, no log beyond the rest log
 * (which is empty in P1 — a rest turn produces no mutation/log). Used by the handler whenever the
 * reserved action-code is unknown or denied.
 */
object RestAction : GeneralActionDefinition {
    override val key = "휴식"
    override val name = "휴식"
    override fun buildConstraints(ctx: ConstraintContext): List<Constraint> = emptyList()
    override fun resolve(context: GeneralActionResolveContext) { /* no-op: a rest turn produces no mutation/log in P1 */ }
}

/**
 * action-code → definition. Unknown / deny → the 휴식 fallback. The handler uses this to resolve the
 * reserved action-code and the fallback.
 */
class CommandRegistry(private val pipeline: GeneralActionPipeline, private val maxLevel: Int = 255) {
    fun resolve(actionCode: String): GeneralActionDefinition = when (actionCode) {
        "che_상업투자" -> cheSangeobTuja(pipeline, maxLevel)
        "che_농지개간" -> cheNongjigaegan(pipeline, maxLevel)
        "che_성벽보수" -> cheSeongbyeokBosu(pipeline, maxLevel)
        "che_수비강화" -> cheSubiGanghwa(pipeline, maxLevel)
        "che_치안강화" -> cheChianGanghwa(pipeline, maxLevel)
        "che_기술연구" -> cheGisulYeongu(pipeline, maxLevel)
        "che_정착장려" -> cheJeongchakJangnyeo(pipeline, maxLevel)
        "che_주민선정" -> cheJuminSeonjeong(pipeline, maxLevel)
        "che_물자조달" -> cheMuljaJodal(pipeline, maxLevel)
        "che_군량매매" -> cheGunryangMaemae(pipeline, maxLevel)
        "che_징병" -> RecruitAlgorithm.cheJingbyeong(pipeline, maxLevel)
        "che_모병" -> RecruitAlgorithm.cheMobyeong(pipeline, maxLevel)
        "che_훈련" -> CheHullyeon(pipeline, maxLevel)
        "cr_맹훈련" -> CrMaenghullyeon(pipeline, maxLevel)
        "che_사기진작" -> CheSagiJinjak(pipeline, maxLevel)
        "che_소집해제" -> CheSojipHaeje(pipeline)
        "che_이동" -> CheIdong(pipeline)
        "che_집합" -> CheJiphap(pipeline)
        "che_임관" -> CheImgwan(pipeline)
        "che_장수대상임관" -> CheJangsuDaesangImgwan(pipeline)
        "che_하야" -> CheHaya(pipeline)
        "che_방랑" -> CheBangrang(pipeline)
        "che_랜덤임관" -> CheRandomImgwan(pipeline)
        "che_은퇴" -> CheEuntwe(pipeline)
        "che_등용" -> CheDeungyong(pipeline)
        else -> RestAction
    }
    val fallback: GeneralActionDefinition get() = RestAction
}
