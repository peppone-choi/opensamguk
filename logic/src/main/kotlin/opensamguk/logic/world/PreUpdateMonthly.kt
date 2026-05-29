package opensamguk.logic.world

import opensamguk.common.constants.EffectiveGameConst
import opensamguk.logic.tick.PreUpdateMonthly
import kotlin.math.floor
import kotlin.math.max

/**
 * P3 / AREA B1 / Task PRE1 — `preUpdateMonthly` ordered P1-P7 side-effect set.
 *
 * Faithful port of PHP `func_gamerule.php:189-257`. **Runs at L6 of the [opensamguk.logic.tick.MonthlyPipeline],
 * BETWEEN PreMonth events and `turnDate` — the side-effect ORDER is itself a parity target** (the G1
 * log-sequence + numeric gate), so the pure core below executes the phases in EXACT PHP order and exposes
 * an ordered [PreUpdateMonthlyResult.phaseOrder] log:
 *
 * ```
 *   P1  $result = LogHistory();  if ($result == false) return false;     // abort the tick (after unlock)
 *   P2  $admin = $gameStor->getValues(['startyear','year','month'])      // read calendar
 *   P3  general_access_log.refresh_score_total = floor(refresh_score_total * 0.99)   // SQL floor — NOT phpRound
 *   P4  general.makelimit = greatest(0, makelimit - 1)
 *   P5  nation.strategic_cmd_limit = greatest(0, strategic_cmd_limit - 1);
 *       nation.surlimit            = greatest(0, surlimit - 1);
 *       nation.rate_tmp            = rate                                 // the parity-critical snapshot
 *   P6  gameStor.develcost = (year - startyear + 10) * 2
 *   P7  city.state CASE machine (31→0,32→31,33→0,34→33,41→0,42→41,43→42, else unchanged);
 *       city.term = greatest(0, term - 1);
 *       city.conflict = '{}' IF (post-decrement) term == 0 ELSE conflict;
 *       per-nation spy decay: SELECT nation,spy WHERE spy!=''/'{}' — each entry<=1 unset, else -=1.
 * ```
 *
 * **`rate_tmp = rate` is the parity-critical snapshot** (P5): [ProcessIncome] reads `rate_tmp` NOT `rate`,
 * so the tax is frozen at month-start. **P3 uses SQL `floor` (toward −∞), distinct from `phpRound`
 * (half-away-from-zero) and `Util::toInt` (truncate-toward-zero)** — for the non-negative refresh score
 * floor == truncate, but the port uses [floor] to stay faithful to the `floor(...)` SQL expression.
 *
 * The state CASE arms (`func_gamerule.php:225-233`): 31→0, 32→31, 33→0, 34→33, 41→0, 42→41, 43→42; every
 * other state value is left UNCHANGED (the `ELSE state` arm). `conflict` is cleared to `'{}'` ONLY when
 * the post-decrement `term` is 0 (the PHP `if(term = 0, '{}', conflict)` is evaluated AFTER `term-1` lands
 * in the same UPDATE — so a city that decrements 1→0 clears, and a city already at 0 stays 0 and clears).
 *
 * **PURE / in-memory** — no IO. P1's `LogHistory()` actually INSERTs `ng_history` (IO); the daemon's
 * context performs that insert and hands the pure core only its boolean outcome ([logHistorySucceeded]).
 */

/** A `general_access_log` row's refresh-score input (P3). */
data class PreUpdateAccessLog(val generalId: Int, val refreshScoreTotal: Int)

/** A general's `makelimit` input (P4). */
data class PreUpdateGeneral(val generalId: Int, val makelimit: Int)

/**
 * A nation's PreUpdate input (P5 + P7-spy). `rate` is the LIVE tax rate snapshotted into `rate_tmp`;
 * `spy` is the decoded `{cityNo: remainMonth}` map (PHP `Json::decode` of `nation.spy`).
 */
data class PreUpdateNation(
    val id: Int,
    val strategicCmdLimit: Int,
    val surlimit: Int,
    val rate: Double,
    val spy: Map<Int, Int> = emptyMap(),
)

/** A city's PreUpdate input (P7): the `state` CASE machine + `term` decay + `conflict` clear. */
data class PreUpdateCity(
    val id: Int,
    val state: Int,
    val term: Int,
    val conflict: String,
)

/** Per-`general_access_log` result (P3). */
data class PreUpdateAccessLogUpdate(val generalId: Int, val newRefreshScore: Int)

/** Per-general result (P4). */
data class PreUpdateGeneralUpdate(val generalId: Int, val newMakelimit: Int)

/** Per-nation result (P5 limits/rate_tmp + P7 spy decay). */
data class PreUpdateNationUpdate(
    val nationId: Int,
    val newStrategicCmdLimit: Int,
    val newSurlimit: Int,
    val newRateTmp: Double,
    val newSpy: Map<Int, Int>,
)

/** Per-city result (P7). */
data class PreUpdateCityUpdate(
    val cityId: Int,
    val newState: Int,
    val newTerm: Int,
    val newConflict: String,
)

/**
 * The structured PRE1 result the F1 hook applies to the world + flush. `succeeded == false` means
 * P1 [PreUpdateMonthly.run] aborts (LogHistory returned false) — all payload lists are empty.
 * [develcost] is the P6 KV write; [phaseOrder] is the ordered side-effect log for the G1 sequence gate.
 */
data class PreUpdateMonthlyResult(
    val succeeded: Boolean,
    val accessLogs: List<PreUpdateAccessLogUpdate> = emptyList(),
    val generals: List<PreUpdateGeneralUpdate> = emptyList(),
    val nations: List<PreUpdateNationUpdate> = emptyList(),
    val cities: List<PreUpdateCityUpdate> = emptyList(),
    val develcost: Int = 0,
    val phaseOrder: List<String> = emptyList(),
)

/**
 * The pure PRE1 core. Executes P1-P7 in EXACT PHP order; returns a structured result (empty payload
 * with `succeeded=false` when P1's [logHistorySucceeded] is false).
 *
 * @param logHistorySucceeded the P1 `LogHistory()` outcome (the daemon performs the `ng_history` insert).
 */
fun preUpdateMonthly(
    logHistorySucceeded: Boolean,
    year: Int,
    startYear: Int,
    accessLogs: List<PreUpdateAccessLog>,
    generals: List<PreUpdateGeneral>,
    nations: List<PreUpdateNation>,
    cities: List<PreUpdateCity>,
): PreUpdateMonthlyResult {
    // P1 — LogHistory abort: false short-circuits BEFORE any other side-effect (PHP returns false).
    if (!logHistorySucceeded) return PreUpdateMonthlyResult(succeeded = false)

    val phaseOrder = mutableListOf<String>()

    // P3 — general_access_log.refresh_score_total = floor(refresh_score_total * 0.99).
    val accessLogUpdates = accessLogs.map {
        PreUpdateAccessLogUpdate(it.generalId, floor(it.refreshScoreTotal * 0.99).toInt())
    }
    phaseOrder += "refresh_score"

    // P4 — general.makelimit = greatest(0, makelimit - 1).
    val generalUpdates = generals.map {
        PreUpdateGeneralUpdate(it.generalId, max(0, it.makelimit - 1))
    }
    phaseOrder += "makelimit"

    // P5 — nation limits decrement + rate_tmp = rate snapshot. (P7 spy decay is folded per-nation below
    //       so the result carries one PreUpdateNationUpdate per nation, but the PHASE ORDER records P5
    //       (nation_limits_rate_tmp) before P6 (develcost) before P7 (city_state) before P7-spy.)
    val nationLimits = nations.map {
        Triple(it, max(0, it.strategicCmdLimit - 1), max(0, it.surlimit - 1))
    }
    phaseOrder += "nation_limits_rate_tmp"

    // P6 — develcost = (year - startyear + 10) * 2 (shared with EffectiveGameConst.develcost).
    val develcost = EffectiveGameConst.develcost(year, startYear)
    phaseOrder += "develcost"

    // P7 — city.state CASE machine + term decay + conflict clear.
    val cityUpdates = cities.map { c ->
        val newState = when (c.state) {
            31 -> 0; 32 -> 31; 33 -> 0; 34 -> 33; 41 -> 0; 42 -> 41; 43 -> 42
            else -> c.state
        }
        val newTerm = max(0, c.term - 1)
        val newConflict = if (newTerm == 0) "{}" else c.conflict
        PreUpdateCityUpdate(c.id, newState, newTerm, newConflict)
    }
    phaseOrder += "city_state"

    // P7 (cont) — per-nation spy decay: entry<=1 unset, else -1 (insertion order preserved).
    val nationUpdates = nationLimits.map { (n, newStrat, newSur) ->
        val newSpy = LinkedHashMap<Int, Int>()
        for ((cityNo, remainMonth) in n.spy) {
            if (remainMonth <= 1) continue
            newSpy[cityNo] = remainMonth - 1
        }
        PreUpdateNationUpdate(n.id, newStrat, newSur, n.rate, newSpy)
    }
    phaseOrder += "spy"

    return PreUpdateMonthlyResult(
        succeeded = true,
        accessLogs = accessLogUpdates,
        generals = generalUpdates,
        nations = nationUpdates,
        cities = cityUpdates,
        develcost = develcost,
        phaseOrder = phaseOrder,
    )
}

/**
 * The richer dispatch context the F1 [PreUpdateMonthlyHook] consumes (the daemon supplies it). Mirrors
 * the [opensamguk.logic.event.EventActionContext] env shape (year/startYear/currentEventID) but is a
 * STANDALONE interface because `preUpdateMonthly` is an F1 PIPELINE HOOK (runs at L6), NOT an
 * F2-dispatched Action.
 *
 *  - [logHistory] — performs the `ng_history` INSERT (IO) and returns its outcome (P1).
 *  - [accessLogs]/[generals]/[nations]/[cities] — the world snapshots in DB PK ascending order.
 *  - [apply] — the write-set sink; the daemon folds the result into the boundary flush.
 */
interface PreUpdateMonthlyContext {
    val env: Map<String, Any?>
    val currentEventID: Int
    fun logHistory(): Boolean
    fun accessLogs(): List<PreUpdateAccessLog>
    fun generals(): List<PreUpdateGeneral>
    fun nations(): List<PreUpdateNation>
    fun cities(): List<PreUpdateCity>
    fun apply(result: PreUpdateMonthlyResult)
}

/**
 * The F1 [PreUpdateMonthly] hook adapter (the L6 step the [opensamguk.logic.tick.MonthlyPipeline] injects).
 * Reads the [PreUpdateMonthlyContext], runs the pure [preUpdateMonthly] core, applies the result on
 * success, and returns the boolean the pipeline uses to ABORT (false → pipeline returns after unlock).
 */
class PreUpdateMonthlyHook(private val ctx: PreUpdateMonthlyContext) : PreUpdateMonthly {
    override fun run(): Boolean {
        val result = preUpdateMonthly(
            logHistorySucceeded = ctx.logHistory(),
            year = (ctx.env["year"] as Number).toInt(),
            startYear = (ctx.env["startYear"] as Number).toInt(),
            accessLogs = ctx.accessLogs(),
            generals = ctx.generals(),
            nations = ctx.nations(),
            cities = ctx.cities(),
        )
        if (!result.succeeded) return false
        ctx.apply(result)
        return true
    }
}
