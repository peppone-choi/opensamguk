package opensamguk.logic.actions.nation

import opensamguk.common.josa.JosaUtil
import opensamguk.logic.actions.GeneralActionResolveContext
import opensamguk.logic.constraints.Constraint
import opensamguk.logic.constraints.ConstraintContext
import opensamguk.logic.constraints.allowDiplomacyBetweenStatus
import opensamguk.logic.constraints.beChief
import opensamguk.logic.constraints.existsDestNation
import opensamguk.logic.constraints.notBeNeutral
import opensamguk.logic.constraints.occupiedCity
import opensamguk.logic.constraints.suppliedCity
import opensamguk.logic.stats.GeneralActionPipeline

/**
 * che_종전제의 — faithful port of `legacy/devsam-core/hwe/sammo/Command/Nation/che_종전제의.php`.
 *
 * The cease-fire PROPOSAL command (paired with the [CheJongjeonSuak] acceptance). It sends a
 * [DiplomaticMessage] (action=stopWar) to the dest nation; the diplomacy state mutation happens only
 * when the recipient accepts. Mirrors the [CheBulgachimJeui] proposal pattern.
 *
 * argTest (che_종전제의.php:30-50): require `destNationID` (int, >= 1) → canonical `{destNationID}`.
 * The dest nation need NOT exist at argTest (`멸망 직전에 턴을 넣을 수 있으므로`).
 *
 * Constraints (PHP ORDER, first-deny-wins):
 *  - min (che_종전제의.php:61-66): `[BeChief, NotBeNeutral, OccupiedCity, SuppliedCity]`.
 *  - full (che_종전제의.php:73-83): `+ [ExistsDestNation, AllowDiplomacyBetweenStatus([0,1],
 *    '선포, 전쟁중인 상대국에게만 가능합니다.')]` — only DECLARED(1)/WAR(0) nations are cease-fire targets.
 *
 * getPreReqTurn = getPostReqTurn = 0, getCost = [0,0].
 */
fun cheJongjeonjeui(@Suppress("UNUSED_PARAMETER") pipeline: GeneralActionPipeline): CheJongjeonjeui =
    CheJongjeonjeui(pipeline)

class CheJongjeonjeui(@Suppress("UNUSED_PARAMETER") pipeline: GeneralActionPipeline) : NationCommand() {
    override val key: String get() = "che_종전제의"
    override val name: String get() = "종전 제의"
    override val category: String get() = "외교"
    override val argsSchema: Map<String, Any?> get() = linkedMapOf("destNationID" to "int")

    override fun getPreReqTurn(): Int = 0

    override fun buildMinConstraints(ctx: ConstraintContext): List<Constraint> = listOf(
        beChief(), notBeNeutral(), occupiedCity(), suppliedCity(),
    )

    override fun buildConstraints(ctx: ConstraintContext): List<Constraint> = listOf(
        beChief(), notBeNeutral(), occupiedCity(), suppliedCity(),
        existsDestNation(),
        allowDiplomacyBetweenStatus(listOf(0, 1), "선포, 전쟁중인 상대국에게만 가능합니다."),
    )

    override fun parseArgs(raw: Map<String, Any?>): Map<String, Any?> = run {
        val destNationID = (raw["destNationID"] as? Int) ?: return@run emptyMap()
        if (destNationID < 1) return@run emptyMap()
        linkedMapOf("destNationID" to destNationID)
    }

    /**
     * che_종전제의.php:105-173 run(). Pushes the action log + sends a stopWar [DiplomaticMessage].
     * The actual mailbox INSERT is the engine-layer message sink (wired in P6 step 3b); this resolve
     * produces the byte-faithful action log now.
     */
    override fun resolve(context: GeneralActionResolveContext) {
        val destNationID = (context.args["destNationID"] as? Int) ?: return
        val destNationName = context.destGeneralName.ifEmpty { "상대국" }
        val josaRo = JosaUtil.pick(destNationName, "로")
        context.addLog("<D><b>$destNationName</b></>$josaRo 종전 제의 서신을 보냈습니다.<1>${context.date}</>")
        // DiplomaticMessage(action=stopWar, deletable=false, validUntil=max(30, turnterm*3)분) send → step 3b.
    }
}
