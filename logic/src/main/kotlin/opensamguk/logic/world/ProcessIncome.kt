package opensamguk.logic.world

import opensamguk.common.constants.GameConst
import opensamguk.logic.domain.City
import opensamguk.logic.domestic.getBill
import opensamguk.logic.domestic.getGoldIncome
import opensamguk.logic.domestic.getOutcome
import opensamguk.logic.domestic.getRiceIncome
import opensamguk.logic.domestic.getWallIncome
import opensamguk.logic.event.EventAction
import opensamguk.logic.event.EventActionContext
import opensamguk.logic.log.HistoryTokens
import opensamguk.logic.stats.GeneralActionModule
import opensamguk.logic.stats.GeneralActionPipeline
import opensamguk.logic.util.phpRound
import opensamguk.logic.util.valueFit

/**
 * P3 / AREA A1 / Task IN2 — `ProcessIncome(gold|rice)` Action.
 *
 * Faithful port of PHP `Event/Action/ProcessIncome.php:32-209`. **The income FORMULAS are GREEN
 * (`logic/domestic/IncomeTick.kt`); IN2 is the per-nation orchestration + the 3-branch payout split +
 * the per-general payout, NOT the math.**
 *
 * Structure (per nation, in ASCENDING nation id = PHP `SELECT FROM nation` row order):
 *   - gold: `income = getGoldIncome(...)`  (single call);
 *   - rice: `income = getRiceIncome(...) + getWallIncome(...)`  (two calls SUMMED, ProcessIncome.php:137-138).
 *   - `originoutcome = getOutcome(100, generalDedications)`; `outcome = phpRound(bill/100 * originoutcome)`.
 *   - 3-branch payout split against `GameConst.basegold` (0) / `GameConst.baserice` (2000):
 *       (a) res+income < base       → realoutcome=0, ratio=0;
 *       (b) (res+income)-base < out → realoutcome=(res+income)-base, res→base, ratio=realoutcome/originoutcome;
 *       (c) else                    → realoutcome=outcome, res=(res+income)-outcome, ratio=outcome/originoutcome;
 *     then `res = valueFit(res, base)` (lower clamp).
 *   - `prev_income_{gold,rice} = income` (GROSS, not realoutcome) written to nation_env KV BEFORE the
 *     per-general payout (ProcessIncome.php:87/172) — the LITERAL key, for the history match.
 *   - per general: `pay = phpRound(getBill(dedication) * ratio)`, increaseVar(resource, pay);
 *     officer_level > 4 → an income line; ALL → a salary line.
 *   - one global history line after the loop (봄 for gold / 가을 for rice).
 *
 * READS `rate_tmp` (the [IncomeNation.taxRate] field is the snapshot preUpdateMonthly froze), NOT `rate`.
 * The income fold runs over the NATION-TYPE source ONLY (the income fns route through
 * [GeneralActionPipeline.nationIncomeFold]); it never goes through the 9-source general pipeline.
 *
 * `income` is the un-rounded float [getGoldIncome]/[getRiceIncome] product; the nation resource column
 * is an int, so the branch arithmetic runs in Double and the stored value is `phpRound`-ed at the end.
 * `prev_income` + the income/salary display lines round via `phpRound` (PHP `number_format` rounds the
 * float; the F7 [HistoryTokens] scaffold takes the rounded Int). G1 pins the exact KV representation.
 */

/** A nation's per-general payout target (PHP `general` row: dedication + officer_level). */
data class IncomeGeneral(
    val id: Int,
    val dedication: Double,
    val officerLevel: Int,
)

/**
 * One nation's income-tick input. `taxRate` is `rate_tmp` (NOT `rate`). `nationType` is the resolved
 * nation-type [GeneralActionModule] (null → identity fold). `officerCntByCity` is PHP's
 * `SELECT officer_city, count(*) ... WHERE officer_level IN (2,3,4) AND city = officer_city GROUP BY officer_city`.
 */
data class IncomeNation(
    val id: Int,
    val name: String,
    val gold: Int,
    val rice: Int,
    val level: Int,
    val taxRate: Double,
    val bill: Double,
    val capitalId: Int,
    val nationType: GeneralActionModule?,
    val cities: List<City>,
    val generals: List<IncomeGeneral>,
    val officerCntByCity: Map<Int, Int> = emptyMap(),
)

/** Post-income nation resource update: the clamped new gold/rice + the realized payout ratio. */
data class IncomeNationUpdate(
    val nationId: Int,
    val newResource: Int,
    val realOutcome: Int,
    val ratio: Double,
)

/** A general's realized payout + the action-log lines (income line when officer_level>4, always salary). */
data class IncomeGeneralPayout(
    val generalId: Int,
    val amount: Int,
    val logLines: List<String>,
)

/** The structured result the [EventAction] wrapper applies to the world + flush. */
data class ProcessIncomeResult(
    val resource: String,
    val nationUpdates: List<IncomeNationUpdate>,
    /** `prev_income_{gold,rice}` per nation (GROSS, phpRound of the float income). */
    val prevIncome: Map<Int, Int>,
    val generalPayouts: List<IncomeGeneralPayout>,
    val globalHistory: String,
)

/**
 * The pure IN2 core: compute the per-nation resource updates, the prev_income KV, the per-general
 * payouts + log lines, and the global history line. [resource] is "gold" or "rice".
 */
fun processIncome(
    resource: String,
    nations: List<IncomeNation>,
    pipeline: GeneralActionPipeline,
    year: Int,
    month: Int,
): ProcessIncomeResult {
    require(resource == "gold" || resource == "rice") { "잘못된 자원 타입" }
    val isGold = resource == "gold"
    val base = if (isGold) GameConst.basegold else GameConst.baserice

    // Per-nation in ascending id order (PHP SELECT row order — pin determinism).
    val ordered = nations.sortedBy { it.id }

    val nationUpdates = ArrayList<IncomeNationUpdate>(ordered.size)
    val prevIncome = LinkedHashMap<Int, Int>()
    val generalPayouts = ArrayList<IncomeGeneralPayout>()

    for (nation in ordered) {
        val income: Double = if (isGold) {
            getGoldIncome(nation.cities, nation.capitalId, nation.level, nation.taxRate, nation.nationType, pipeline, nation.officerCntByCity)
        } else {
            getRiceIncome(nation.cities, nation.capitalId, nation.level, nation.taxRate, nation.nationType, pipeline, nation.officerCntByCity) +
                getWallIncome(nation.cities, nation.capitalId, nation.level, nation.taxRate, nation.nationType, pipeline, nation.officerCntByCity)
        }

        val originOutcome = getOutcome(100.0, nation.generals.map { it.dedication })
        val outcome = phpRound(nation.bill / 100.0 * originOutcome)

        val resBefore = (if (isGold) nation.gold else nation.rice).toDouble()
        var res = resBefore + income
        // PHP keeps realoutcome as a FLOAT through the ratio division (it is `gold - base`, a float, in
        // branch (b)); only the admin-log surfaces it as a rounded int. Keep the float for the ratio.
        val realOutcome: Double
        val ratio: Double
        if (res < base) {
            realOutcome = 0.0
            ratio = 0.0
        } else if (res - base < outcome) {
            realOutcome = res - base // PHP: $realoutcome = gold - basegold (float gold)
            res = base.toDouble()
            ratio = if (originOutcome == 0) 0.0 else realOutcome / originOutcome
        } else {
            realOutcome = outcome.toDouble()
            res -= realOutcome
            ratio = if (originOutcome == 0) 0.0 else realOutcome / originOutcome
        }
        res = valueFit(res, base.toDouble())

        val grossIncome = phpRound(income)
        prevIncome[nation.id] = grossIncome
        nationUpdates.add(IncomeNationUpdate(nation.id, phpRound(res), phpRound(realOutcome), ratio))

        val incomeLine = if (isGold) HistoryTokens.goldIncomeLine(grossIncome) else HistoryTokens.riceIncomeLine(grossIncome)
        for (g in nation.generals) {
            val pay = phpRound(getBill(g.dedication) * ratio)
            val lines = ArrayList<String>(2)
            if (g.officerLevel > 4) lines.add(incomeLine)
            lines.add(if (isGold) HistoryTokens.goldSalaryLine(pay) else HistoryTokens.riceSalaryLine(pay))
            generalPayouts.add(IncomeGeneralPayout(g.id, pay, lines))
        }
    }

    val globalHistory = if (isGold) HistoryTokens.springIncomeGlobal() else HistoryTokens.autumnIncomeGlobal()
    return ProcessIncomeResult(resource, nationUpdates, prevIncome, generalPayouts, globalHistory)
}

/**
 * The F2-dispatched `ProcessIncome` leaf (registered by name into the [opensamguk.logic.event.EventActionFactory]).
 * F2's `month==1` row names `ProcessIncome("gold")` at index 2 (SPRING) and the `month==7` row names
 * `ProcessIncome("rice")` (AUTUMN); the calendar gating + the resource arg + the intra-row position are
 * F2-owned (the family does NOT author the row or its order).
 *
 * The leaf reads the nation/city/general world snapshot + the [GeneralActionPipeline] from a richer
 * [ProcessIncomeContext] (the daemon supplies it at dispatch; the dispatcher's bare env carries only
 * `year`/`month`/`currentEventID`). The applied result (resource updates, prev_income KV, per-general
 * payouts + log lines, the global history) lands through the F3 change-recorder / flush seam.
 */
class ProcessIncomeAction(val resource: String) : EventAction {
    init {
        require(resource == "gold" || resource == "rice") { "잘못된 자원 타입" }
    }

    override fun run(ctx: EventActionContext) {
        val pic = ctx as? ProcessIncomeContext
            ?: error("ProcessIncomeAction requires a ProcessIncomeContext")
        val year = (ctx.env["year"] as? Number)?.toInt() ?: 0
        val month = (ctx.env["month"] as? Number)?.toInt() ?: 0
        val result = processIncome(resource, pic.nations(), pic.pipeline, year, month)
        pic.applyIncome(result)
    }

    companion object {
        const val NAME = "ProcessIncome"

        /**
         * Register the `ProcessIncome` leaf into the F2-owned factory by name (the lone per-family
         * touch-point, plan §append protocol). The single arg is the resource string (`"gold"`/`"rice"`),
         * passed VERBATIM by F2's `month==1`/`month==7` rows; decoded as `args[0]`'s JSON primitive.
         */
        fun register(factory: opensamguk.logic.event.EventActionFactory): opensamguk.logic.event.EventActionFactory =
            factory.register(NAME) { args ->
                val resource = (args[0] as kotlinx.serialization.json.JsonPrimitive).content
                ProcessIncomeAction(resource)
            }
    }
}

/** The richer dispatch context an [ProcessIncomeAction] consumes (supplied by the daemon, not F2). */
interface ProcessIncomeContext : EventActionContext {
    val pipeline: GeneralActionPipeline
    fun nations(): List<IncomeNation>
    fun applyIncome(result: ProcessIncomeResult)
}
