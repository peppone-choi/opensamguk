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
 * che_불가침파기수락 — acceptance of a non-aggression pact cancellation.
 *
 * Triggered when the receiving nation's chief agrees to cancel a non-aggression pact.
 * Sets both directional rows to TRADE (2) / term=0, dissolving the non-aggression agreement.
 *
 * PHP reference: `legacy/devsam-core/hwe/sammo/Command/Nation/che_불가침파기수락.php`
 *
 * Constraints: BeChief, NotBeNeutral, ExistsDestNation, ExistsDestGeneral,
 *              AllowDiplomacyBetweenStatus([7]) — only non-aggression state may be cancelled.
 */
fun cheBulgachimPagiSuak(@Suppress("UNUSED_PARAMETER") pipeline: GeneralActionPipeline): CheBulgachimPagiSuak =
    CheBulgachimPagiSuak(pipeline)

class CheBulgachimPagiSuak(@Suppress("UNUSED_PARAMETER") pipeline: GeneralActionPipeline) : NationCommand() {
    override val key: String get() = "che_불가침파기수락"
    override val name: String get() = "불가침 파기 수락"
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
            listOf(DiplomacyState.NON_AGGRESSION),
            "불가침 상태가 아닙니다.",
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
     * PHP run() (che_불가침파기수락.php):
     *  1. Upsert Diplomacy(me, you): state=2, term=0
     *  2. Upsert Diplomacy(you, me): state=2, term=0
     *  3. Log: `<D><b>{상대국}</b></>의 불가침을 파기했습니다.`
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

        // Log
        val destNationName = context.destGeneralName.ifEmpty { "상대국" }
        val josaUi = JosaUtil.pick(destNationName, "의")
        context.addLog("<D><b>$destNationName</b></>$josaUi 불가침을 파기했습니다.")
    }
}
