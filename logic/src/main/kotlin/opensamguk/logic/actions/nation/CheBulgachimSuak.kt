package opensamguk.logic.actions.nation

import opensamguk.common.josa.JosaUtil
import opensamguk.logic.actions.GeneralActionResolveContext
import opensamguk.logic.constraints.Constraint
import opensamguk.logic.constraints.ConstraintContext
import opensamguk.logic.constraints.allowDiplomacyBetweenStatus
import opensamguk.logic.constraints.beChief
import opensamguk.logic.constraints.differentDestNation
import opensamguk.logic.constraints.disallowDiplomacyBetweenStatus
import opensamguk.logic.constraints.existsDestGeneral
import opensamguk.logic.constraints.existsDestNation
import opensamguk.logic.constraints.notBeNeutral
import opensamguk.logic.diplomacy.DiplomacyConst
import opensamguk.logic.diplomacy.DiplomacyState
import opensamguk.logic.domain.Diplomacy
import opensamguk.logic.stats.GeneralActionPipeline
import opensamguk.logic.util.phpRound

/**
 * che_불가침수락 — acceptance of a non-aggression pact proposal.
 *
 * Triggered when the receiving nation's chief agrees to a diplomatic non-aggression message.
 * Sets both directional rows to NON_AGGRESSION (7) with a computed term.
 *
 * PHP reference: `legacy/devsam-core/hwe/sammo/Command/Nation/che_불가침수락.php`
 *
 * Constraints: BeChief, NotBeNeutral, ExistsDestNation, ExistsDestGeneral,
 *              reqFutureTreatyTerm (6-month gate handled upstream),
 *              DisallowDiplomacyBetweenStatus(0,1)
 */
fun cheBulgachimSuak(@Suppress("UNUSED_PARAMETER") pipeline: GeneralActionPipeline): CheBulgachimSuak =
    CheBulgachimSuak(pipeline)

class CheBulgachimSuak(@Suppress("UNUSED_PARAMETER") pipeline: GeneralActionPipeline) : NationCommand() {
    override val key: String get() = "che_불가침수락"
    override val name: String get() = "불가침 수락"
    override val category: String get() = "외교"
    override val argsSchema: Map<String, Any?> get() = linkedMapOf(
        "destNationID" to "int",
        "year" to "int",
        "month" to "int",
    )

    override fun getPreReqTurn(): Int = 0

    override fun buildMinConstraints(ctx: ConstraintContext): List<Constraint> = listOf(
        beChief(), notBeNeutral(),
    )

    override fun buildConstraints(ctx: ConstraintContext): List<Constraint> = listOf(
        beChief(),
        notBeNeutral(),
        existsDestNation(),
        existsDestGeneral(),
        disallowDiplomacyBetweenStatus(
            linkedMapOf(
                DiplomacyState.WAR to "아국과 이미 교전중입니다.",
                DiplomacyState.DECLARATION to "아국과 이미 선포중입니다.",
            ),
        ),
    )

    override fun parseArgs(raw: Map<String, Any?>): Map<String, Any?> {
        val destNationID = (raw["destNationID"] as? Int) ?: return emptyMap()
        if (destNationID < 1) return emptyMap()
        val year = (raw["year"] as? Int) ?: return emptyMap()
        val month = (raw["month"] as? Int) ?: return emptyMap()
        if (month < 1 || month > 12) return emptyMap()
        return linkedMapOf("destNationID" to destNationID, "year" to year, "month" to month)
    }

    /**
     * Resolve: set both directional diplomacy rows to NON_AGGRESSION with computed term.
     *
     * PHP run() (che_불가침수락.php):
     *  1. Compute term = (year * 12 + month - 1) - currentYearMonth
     *  2. Upsert Diplomacy(me, you): state=7, term=computed
     *  3. Upsert Diplomacy(you, me): state=7, term=computed
     *  4. Log: `<D><b>{상대국}</b></>와 {year}년 {month}월까지 불가침에 성공했습니다.`
     */
    override fun resolve(context: GeneralActionResolveContext) {
        val draft = context.draft
        val me = draft.nation?.id ?: return
        val destNationID = (context.args["destNationID"] as? Int) ?: return
        val year = (context.args["year"] as? Int) ?: return
        val month = (context.args["month"] as? Int) ?: return

        // Compute term in months from current game time to the agreed (year, month)
        val currentYearMonth = context.env.year * 12 + context.month - 1
        val reqYearMonth = year * 12 + month - 1
        val term = phpRound((reqYearMonth - currentYearMonth).toDouble())
        if (term < DiplomacyConst.MIN_NON_AGGRESSION_MONTHS) return

        // Create/update both directional diplomacy rows
        val forward = Diplomacy(me = me, you = destNationID, state = DiplomacyState.NON_AGGRESSION, term = term)
        val reverse = Diplomacy(me = destNationID, you = me, state = DiplomacyState.NON_AGGRESSION, term = term)

        // Add to cascadeDiplomacy for ChangeRecorder diff (these are UPSERTs, not brand-new inserts)
        draft.cascadeDiplomacy.add(forward)
        draft.cascadeDiplomacy.add(reverse)

        // Log
        val destNationName = context.destGeneralName.ifEmpty { "상대국" }
        val josaWa = JosaUtil.pick(destNationName, "과", "와")
        context.addLog("<D><b>$destNationName</b></>$josaWa ${year}년 ${month}월까지 불가침에 성공했습니다.")
    }
}
