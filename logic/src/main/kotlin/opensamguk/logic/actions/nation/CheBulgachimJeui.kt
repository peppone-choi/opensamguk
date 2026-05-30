package opensamguk.logic.actions.nation

import opensamguk.logic.actions.GeneralActionResolveContext
import opensamguk.logic.constraints.Constraint
import opensamguk.logic.constraints.ConstraintContext
import opensamguk.logic.constraints.beChief
import opensamguk.logic.constraints.differentDestNation
import opensamguk.logic.constraints.disallowDiplomacyBetweenStatus
import opensamguk.logic.constraints.existsDestNation
import opensamguk.logic.constraints.notBeNeutral
import opensamguk.logic.stats.GeneralActionPipeline

/**
 * che_불가침제의 — faithful port of `legacy/devsam-core/hwe/sammo/Command/Nation/che_불가침제의.php`.
 *
 * AI-emitted by `do불가침제의` (GeneralAI.php:1832). P5 ports the SELECTION + the boolean gate + the
 * argTest + the FULL constraint pack (decision #10/#11); the diplomatic-message state-mutation run()
 * internals are P6 (the G-GATE downstream-delta/log assertion EXCLUDES this family — m10), so the
 * resolve() body is a P6 seam stub.
 *
 * argTest (che_불가침제의.php:33-73): require `destNationID` (int, >= 1), `year` + `month` (both int,
 * 1 <= month <= 12, year >= env['startyear']) → canonical `{destNationID, year, month}` (note: the
 * dest nation need NOT exist — `멸망 직전에 턴을 넣을 수 있으므로`).
 *
 * fullConditionConstraints (che_불가침제의.php:90-127, computed in initWithArg from the year/month
 * 기한):
 *   - relYear/currentMonth/reqMonth math: `reqMonth = year*12 + month - 1`,
 *     `currentMonth = env.year*12 + env.month - 1`; when `reqMonth < currentMonth + 6` the WHOLE pack
 *     collapses to `[AlwaysFail('기한은 6개월 이상이어야 합니다.')]` (the 6-개월-이상 gate);
 *   - else the 5-constraint diplomacy pack, in PHP ORDER (first-deny-wins):
 *     `[BeChief, NotBeNeutral, ExistsDestNation, DifferentDestNation,
 *       DisallowDiplomacyBetweenStatus({0:'아국과 이미 교전중입니다.', 1:'아국과 이미 선포중입니다.'})]`.
 *
 * The 6-개월 gate reads the request year/month from `ctx.args` and the current year/month from
 * `ctx.env` (`year`/`month`). minConditionConstraints (BeChief, NotBeNeutral) are the reserve-time
 * preview; the AI bridge uses the FULL pack.
 */
fun cheBulgachimJeui(@Suppress("UNUSED_PARAMETER") pipeline: GeneralActionPipeline): CheBulgachimJeui =
    CheBulgachimJeui(pipeline)

class CheBulgachimJeui(@Suppress("UNUSED_PARAMETER") pipeline: GeneralActionPipeline) : NationCommand() {
    override val key: String get() = "che_불가침제의"
    override val name: String get() = "불가침 제의"
    override val category: String get() = "외교"
    override val argsSchema: Map<String, Any?> get() = linkedMapOf("destNationID" to "int", "year" to "int", "month" to "int")

    override fun getPreReqTurn(): Int = 0

    /**
     * che_불가침제의.php:33-73 argTest. Returns the canonical `{destNationID, year, month}` or null on a
     * missing/invalid required key. `startYear` is PHP `env['startyear']` (the year >= startyear gate).
     */
    fun argTest(raw: Map<String, Any?>, startYear: Int): Map<String, Any?>? {
        if (!raw.containsKey("destNationID")) return null
        val destNationID = (raw["destNationID"] as? Int) ?: return null  // PHP is_int
        if (destNationID < 1) return null
        if (!raw.containsKey("year") || !raw.containsKey("month")) return null
        val year = (raw["year"] as? Int) ?: return null
        val month = (raw["month"] as? Int) ?: return null
        if (month < 1 || month > 12) return null
        if (year < startYear) return null
        return linkedMapOf("destNationID" to destNationID, "year" to year, "month" to month)
    }

    override fun buildMinConstraints(ctx: ConstraintContext): List<Constraint> = listOf(
        beChief(), notBeNeutral(),
    )

    override fun buildConstraints(ctx: ConstraintContext): List<Constraint> {
        // initWithArg (che_불가침제의.php:100-126): the 6-개월-이상 기한 gate.
        val envYear = (ctx.env["year"] as? Number)?.toInt() ?: 0
        val envMonth = (ctx.env["month"] as? Number)?.toInt() ?: 1
        val reqYear = (ctx.args["year"] as? Number)?.toInt() ?: 0
        val reqMonth0 = (ctx.args["month"] as? Number)?.toInt() ?: 0
        val currentMonth = envYear * 12 + envMonth - 1
        val reqMonth = reqYear * 12 + reqMonth0 - 1
        if (reqMonth < currentMonth + 6) {
            return listOf(alwaysFail("기한은 6개월 이상이어야 합니다."))
        }
        return listOf(
            beChief(),
            notBeNeutral(),
            existsDestNation(),
            differentDestNation(),
            disallowDiplomacyBetweenStatus(linkedMapOf(0 to "아국과 이미 교전중입니다.", 1 to "아국과 이미 선포중입니다.")),
        )
    }

    override fun parseArgs(raw: Map<String, Any?>): Map<String, Any?> = run {
        // startyear unknown at pure parse time; the year >= startyear gate is re-checked at run via env.
        val destNationID = (raw["destNationID"] as? Int) ?: return@run emptyMap()
        if (destNationID < 1) return@run emptyMap()
        val year = (raw["year"] as? Int) ?: return@run emptyMap()
        val month = (raw["month"] as? Int) ?: return@run emptyMap()
        if (month < 1 || month > 12) return@run emptyMap()
        linkedMapOf("destNationID" to destNationID, "year" to year, "month" to month)
    }

    /** P6 — the diplomatic-message send + setResultTurn run() (che_불가침제의.php:154-229). m10: the AI
     *  selection + boolean gate + draw stream are the P5 gate target; this state-mutation is P6. */
    override fun resolve(context: GeneralActionResolveContext) { /* P6: diplomacy message internals */ }
}
