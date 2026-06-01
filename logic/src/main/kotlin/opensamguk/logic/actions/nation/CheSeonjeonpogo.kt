package opensamguk.logic.actions.nation

import opensamguk.common.josa.JosaUtil
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
import opensamguk.logic.diplomacy.DiplomacyConst
import opensamguk.logic.diplomacy.DiplomacyState
import opensamguk.logic.domain.Diplomacy
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
            // NearNation: the map-adjacency predicate is preloaded by the caller (the AI bridge stages it as the
            // env `__isNeighbor` via AiDistance.isNeighbor over the live cities; precheck has no staging seam yet
            // → falls through to false, P7 read-side TODO). It
            // rides ctx.env (parseArgs drops non-schema arg keys). A missing/false stage denies (PHP
            // `\sammo\isNeighbor`, F-BFS-backed — NO adjacency walk inside test()).
            nearNation { ctx, _ -> (ctx.env["__isNeighbor"] as? Boolean) ?: (ctx.args["__isNeighbor"] as? Boolean) ?: false },
            disallowDiplomacyBetweenStatus(linkedMapOf(0 to "아국과 이미 교전중입니다.", 1 to "아국과 이미 선포중입니다.", 7 to "불가침국입니다.")),
        )
    }

    override fun parseArgs(raw: Map<String, Any?>): Map<String, Any?> = argTest(raw) ?: emptyMap()

    /**
     * P6 — the diplomacy.state=1/term=24 UPDATE + national message run() (che_선전포고.php:121-197).
     *
     * Resolve flow:
     *  1. Extract destNationID from args.
     *  2. Set both directional rows to DECLARATION (state=1) with term=24.
     *  3. Write multi-scope logs (personal / national / global).
     *  4. The national message broadcast is engine-layer (MessageStore).
     */
    override fun resolve(context: GeneralActionResolveContext) {
        val draft = context.draft
        val me = draft.nation?.id ?: return
        val destNationID = (context.args["destNationID"] as? Int) ?: return

        // Both directions → DECLARATION / term=24
        val forward = Diplomacy(
            me = me,
            you = destNationID,
            state = DiplomacyState.DECLARATION,
            term = DiplomacyConst.DEFAULT_DECLARE_WAR_TERM,
        )
        val reverse = Diplomacy(
            me = destNationID,
            you = me,
            state = DiplomacyState.DECLARATION,
            term = DiplomacyConst.DEFAULT_DECLARE_WAR_TERM,
        )

        draft.cascadeDiplomacy.add(forward)
        draft.cascadeDiplomacy.add(reverse)

        // Multi-scope logging
        val actorName = context.generalName.ifEmpty { "아국" }
        val destName = context.destGeneralName.ifEmpty { "상대국" }
        val josaI = JosaUtil.pick(actorName, "이", "가")
        val josaEul = JosaUtil.pick(destName, "을", "를")

        // Personal action log
        context.addLog("<D><b>$destName</b></>$josaEul 선전포고했습니다.")

        // National history log — broadcast to both nations
        val nationalLog = "<D><b>$actorName</b></>$josaI <D><b>$destName</b></>에 선전포고했습니다."
        context.addGlobalActionLog(nationalLog)

        // Global history log with exact date
        val year = context.env.year
        val month = context.month
        context.addPlainLog("<D><b>$actorName</b></>가 <D><b>$destName</b></>에게 ${year}년 ${month}월에 선전포고했습니다.")
    }
}
