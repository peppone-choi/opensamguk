package opensamguk.logic.actions.nation

import opensamguk.logic.actions.GeneralActionResolveContext
import opensamguk.logic.constraints.Constraint
import opensamguk.logic.constraints.ConstraintContext
import opensamguk.logic.constraints.beChief
import opensamguk.logic.constraints.disallowDiplomacyBetweenStatus
import opensamguk.logic.constraints.existsDestNation
import opensamguk.logic.constraints.nearNation
import opensamguk.logic.constraints.notBeNeutral
import opensamguk.logic.constraints.occupiedCity
import opensamguk.logic.constraints.reqEnvValue
import opensamguk.logic.constraints.suppliedCity
import opensamguk.logic.stats.GeneralActionPipeline

/**
 * che_선전포고 — faithful port of `legacy/devsam-core/hwe/sammo/Command/Nation/che_선전포고.php`.
 *
 * AI-emitted by `do선전포고` (GeneralAI.php:1965). P5 ports the SELECTION + boolean gate + argTest +
 * FULL pack (decision #10/#11); the `diplomacy.state=1/term=24` UPDATE + the national-message run()
 * internals are P6 (G-GATE downstream EXCLUDES this family — m10), so resolve() is a P6 seam stub.
 *
 * argTest (che_선전포고.php:32-54): require `destNationID` (int, >= 1) → canonical `{destNationID}`
 * (the dest nation need NOT exist — `멸망 직전에 턴을 넣을 수 있으므로`).
 *
 * fullConditionConstraints (che_선전포고.php:82-95), in PHP ORDER (first-deny-wins):
 *   [BeChief, NotBeNeutral, OccupiedCity, SuppliedCity,
 *    ReqEnvValue('year','>=',startYear+1,'초반제한 해제 2년전부터 가능합니다.'),
 *    ExistsDestNation, NearNation,
 *    DisallowDiplomacyBetweenStatus({0:'아국과 이미 교전중입니다.', 1:'아국과 이미 선포중입니다.', 7:'불가침국입니다.'})].
 *
 * The NearNation isNeighbor predicate is F-BFS-backed (preloaded — no adjacency walk inside test());
 * the daemon/precheck adapter supplies it. Here the def wires the preset with a lambda the staged
 * StateView fills (defaults to false → deferring, never silently passing) — the AI bridge stages the
 * adjacency for the real evaluation.
 */
fun cheSeonjeonpogo(@Suppress("UNUSED_PARAMETER") pipeline: GeneralActionPipeline): CheSeonjeonpogo =
    CheSeonjeonpogo(pipeline)

class CheSeonjeonpogo(@Suppress("UNUSED_PARAMETER") pipeline: GeneralActionPipeline) : NationCommand() {
    override val key: String get() = "che_선전포고"
    override val name: String get() = "선전포고"
    override val category: String get() = "외교"
    override val argsSchema: Map<String, Any?> get() = linkedMapOf("destNationID" to "int")

    override fun getPreReqTurn(): Int = 0

    /** che_선전포고.php:32-54 argTest. Returns the canonical `{destNationID}` or null on invalid. */
    fun argTest(raw: Map<String, Any?>): Map<String, Any?>? {
        if (!raw.containsKey("destNationID")) return null
        val destNationID = (raw["destNationID"] as? Int) ?: return null  // PHP is_int
        if (destNationID < 1) return null
        return linkedMapOf("destNationID" to destNationID)
    }

    override fun buildMinConstraints(ctx: ConstraintContext): List<Constraint> {
        val startYear = (ctx.env["startyear"] as? Number ?: ctx.env["startYear"] as? Number)?.toInt() ?: 0
        return listOf(
            beChief(), notBeNeutral(), occupiedCity(), suppliedCity(),
            reqEnvValue("year", ">=", startYear + 1, "초반제한 해제 2년전부터 가능합니다."),
        )
    }

    override fun buildConstraints(ctx: ConstraintContext): List<Constraint> {
        val startYear = (ctx.env["startyear"] as? Number ?: ctx.env["startYear"] as? Number)?.toInt() ?: 0
        return listOf(
            beChief(),
            notBeNeutral(),
            occupiedCity(),
            suppliedCity(),
            reqEnvValue("year", ">=", startYear + 1, "초반제한 해제 2년전부터 가능합니다."),
            existsDestNation(),
            nearNation { _, _ -> false },
            disallowDiplomacyBetweenStatus(linkedMapOf(0 to "아국과 이미 교전중입니다.", 1 to "아국과 이미 선포중입니다.", 7 to "불가침국입니다.")),
        )
    }

    override fun parseArgs(raw: Map<String, Any?>): Map<String, Any?> = argTest(raw) ?: emptyMap()

    /** P6 — the diplomacy.state=1/term=24 UPDATE + national message run() (che_선전포고.php:121-197). */
    override fun resolve(context: GeneralActionResolveContext) { /* P6: diplomacy state mutation */ }
}
