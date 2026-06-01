package opensamguk.logic.actions.nation

import opensamguk.common.josa.JosaUtil
import opensamguk.logic.actions.GeneralActionResolveContext
import opensamguk.logic.constraints.Constraint
import opensamguk.logic.constraints.ConstraintContext
import opensamguk.logic.constraints.allowDiplomacyBetweenStatus
import opensamguk.logic.constraints.beChief
import opensamguk.logic.constraints.existsDestGeneral
import opensamguk.logic.constraints.existsDestNation
import opensamguk.logic.constraints.notBeNeutral
import opensamguk.logic.diplomacy.DiplomacyState
import opensamguk.logic.domain.Diplomacy
import opensamguk.logic.stats.GeneralActionPipeline

/**
 * che_종전수락 — acceptance of a cease-fire / peace proposal.
 *
 * Triggered when the receiving nation's chief agrees to a diplomatic stop-war message.
 * Sets both directional rows to TRADE (2) / term=0, ending war or declaration states.
 *
 * PHP reference: `legacy/devsam-core/hwe/sammo/Command/Nation/che_종전수락.php`
 *
 * Constraints: BeChief, NotBeNeutral, ExistsDestNation, ExistsDestGeneral,
 *              AllowDiplomacyBetweenStatus([0,1]) — only war or declaration states may cease.
 */
fun cheJongjeonSuak(@Suppress("UNUSED_PARAMETER") pipeline: GeneralActionPipeline): CheJongjeonSuak =
    CheJongjeonSuak(pipeline)

class CheJongjeonSuak(@Suppress("UNUSED_PARAMETER") pipeline: GeneralActionPipeline) : NationCommand() {
    override val key: String get() = "che_종전수락"
    override val name: String get() = "종전 수락"
    override val category: String get() = "외교"
    override val argsSchema: Map<String, Any?> get() = linkedMapOf("destNationID" to "int")

    override fun getPreReqTurn(): Int = 0

    override fun buildMinConstraints(ctx: ConstraintContext): List<Constraint> = listOf(
        beChief(), notBeNeutral(),
    )

    override fun buildConstraints(ctx: ConstraintContext): List<Constraint> = listOf(
        beChief(),
        notBeNeutral(),
        existsDestNation(),
        existsDestGeneral(),
        allowDiplomacyBetweenStatus(
            listOf(DiplomacyState.WAR, DiplomacyState.DECLARATION),
            "교전 또는 선포 상태가 아닙니다.",
        ),
    )

    override fun parseArgs(raw: Map<String, Any?>): Map<String, Any?> {
        val destNationID = (raw["destNationID"] as? Int) ?: return emptyMap()
        if (destNationID < 1) return emptyMap()
        return linkedMapOf("destNationID" to destNationID)
    }

    /**
     * Resolve: set both directional diplomacy rows to TRADE / term=0.
     *
     * PHP run() (che_종전수락.php):
     *  1. Upsert Diplomacy(me, you): state=2, term=0
     *  2. Upsert Diplomacy(you, me): state=2, term=0
     *  3. Multi-scope logs (personal / national / global / history)
     */
    override fun resolve(context: GeneralActionResolveContext) {
        val draft = context.draft
        val me = draft.nation?.id ?: return
        val destNationID = (context.args["destNationID"] as? Int) ?: return

        // Both directions → TRADE / term=0
        val forward = Diplomacy(me = me, you = destNationID, state = DiplomacyState.TRADE, term = 0)
        val reverse = Diplomacy(me = destNationID, you = me, state = DiplomacyState.TRADE, term = 0)

        draft.cascadeDiplomacy.add(forward)
        draft.cascadeDiplomacy.add(reverse)

        // Multi-scope logging
        val actorName = context.generalName.ifEmpty { "아국" }
        val destName = context.destGeneralName.ifEmpty { "상대국" }
        val josaWa = JosaUtil.pick(destName, "과", "와")
        val josaI = JosaUtil.pick(actorName, "이", "가")

        // Personal action log
        context.addLog("<D><b>$destName</b></>$josaWa 종전에 성공했습니다.")

        // National history log (both nations)
        val nationalLog = "<D><b>$actorName</b></>$josaI <D><b>$destName</b></>$josaWa 종전을 맺었습니다."
        context.addGlobalActionLog(nationalLog)

        // Global history log
        val year = context.env.year
        val month = context.month
        context.addPlainLog("<D><b>$actorName</b></>와 <D><b>$destName</b></>가 ${year}년 ${month}월에 종전을 맺었습니다.")
    }
}
